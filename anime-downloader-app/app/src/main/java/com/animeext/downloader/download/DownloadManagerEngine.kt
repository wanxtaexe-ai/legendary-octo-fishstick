package com.animeext.downloader.download

import android.content.Context
import android.os.Environment
import com.animeext.downloader.model.DownloadStatus
import com.animeext.downloader.model.DownloadTask
import com.animeext.downloader.model.LocalMediaItem
import com.animeext.downloader.model.ResolvedTarget
import com.animeext.downloader.network.NetworkClient
import com.animeext.downloader.resolver.DownloadAndSchemeGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DownloadManagerEngine(
    private val context: Context,
    private val network: NetworkClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _localFiles = MutableStateFlow<List<LocalMediaItem>>(emptyList())
    val localFiles: StateFlow<List<LocalMediaItem>> = _localFiles.asStateFlow()

    @Volatile
    var autoExtractZip: Boolean = true

    fun rootDownloadDir(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, "AnimeDownloads").apply { if (!exists()) mkdirs() }
    }

    init {
        refreshLocalLibrary()
    }

    fun refreshLocalLibrary() {
        val root = rootDownloadDir()
        val videoExts = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v")
        val found = mutableListOf<LocalMediaItem>()
        root.listFiles()?.forEach { seriesDir ->
            if (seriesDir.isDirectory) {
                seriesDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.extension.lowercase() in videoExts && file.length() > 0L) {
                        found.add(
                            LocalMediaItem(
                                seriesFolder = seriesDir.name,
                                fileName = file.name,
                                filePath = file.absolutePath,
                                sizeBytes = file.length(),
                                lastModifiedMs = file.lastModified()
                            )
                        )
                    }
                }
            } else if (seriesDir.isFile && seriesDir.extension.lowercase() in videoExts && seriesDir.length() > 0L) {
                found.add(
                    LocalMediaItem(
                        seriesFolder = "Downloads",
                        fileName = seriesDir.name,
                        filePath = seriesDir.absolutePath,
                        sizeBytes = seriesDir.length(),
                        lastModifiedMs = seriesDir.lastModified()
                    )
                )
            }
        }
        _localFiles.value = found.sortedByDescending { it.lastModifiedMs }
    }

    fun deleteLocalFile(item: LocalMediaItem) {
        runCatching { item.file.delete() }
        refreshLocalLibrary()
    }

    fun enqueueDownload(
        seriesTitle: String,
        episodeLabel: String,
        target: ResolvedTarget
    ): DownloadTask? {
        if (DownloadAndSchemeGuard.inspectUrl(target.url) != DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS) {
            return null
        }

        val safeSeries = sanitizeFileName(seriesTitle).ifBlank { "Anime" }
        val seriesDir = File(rootDownloadDir(), safeSeries).apply { if (!exists()) mkdirs() }

        val guessedName = guessFileName(seriesTitle, episodeLabel, target)
        val destFile = File(seriesDir, guessedName)

        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            seriesTitle = seriesTitle,
            episodeLabel = episodeLabel,
            url = target.url,
            fileName = guessedName,
            destinationPath = destFile.absolutePath,
            headers = target.headers,
            megaKeyHex = target.megaKeyHex,
            megaNonceHex = target.megaNonceHex,
            status = DownloadStatus.QUEUED
        )

        _tasks.update { listOf(task) + it }
        startTask(task.id)
        return task
    }

    fun pauseDownload(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        updateTask(taskId) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
    }

    fun resumeDownload(taskId: String) {
        startTask(taskId)
    }

    fun cancelDownload(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        val task = _tasks.value.firstOrNull { it.id == taskId }
        if (task != null) {
            runCatching { File(task.destinationPath).delete() }
        }
        updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, speedBytesPerSec = 0L) }
    }

    private fun startTask(taskId: String) {
        activeJobs[taskId]?.cancel()
        val job = scope.launch {
            val task = _tasks.value.firstOrNull { it.id == taskId } ?: return@launch
            var destFile = File(task.destinationPath)
            val isMega = !task.megaKeyHex.isNullOrBlank() && !task.megaNonceHex.isNullOrBlank()
            val rawExisting = if (destFile.exists()) destFile.length() else 0L
            val existingBytes = if (isMega) (rawExisting / 16L) * 16L else rawExisting

            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.DOWNLOADING,
                    downloadedBytes = existingBytes,
                    errorMessage = null
                )
            }

            try {
                val reqBuilder = Request.Builder().url(task.url)
                for ((k, v) in task.headers) {
                    reqBuilder.header(k, v)
                }
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                network.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        updateTask(taskId) {
                            it.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = "HTTP ${resp.code}",
                                speedBytesPerSec = 0L
                            )
                        }
                        return@launch
                    }

                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    if (DownloadAndSchemeGuard.isBlockedMimeType(contentType)) {
                        updateTask(taskId) {
                            it.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = "Blocked executable MIME: $contentType",
                                speedBytesPerSec = 0L
                            )
                        }
                        return@launch
                    }

                    if (contentType.contains("text/html")) {
                        updateTask(taskId) {
                            it.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = "Host returned HTML page instead of media file",
                                speedBytesPerSec = 0L
                            )
                        }
                        return@launch
                    }

                    // Update filename from Content-Disposition if available and starting fresh
                    if (existingBytes == 0L && !isMega) {
                        extractDispositionFileName(resp.header("Content-Disposition"))?.let { dispName ->
                            val cleanDisp = sanitizeFileName(dispName)
                            if (cleanDisp.isNotBlank() && DownloadAndSchemeGuard.inspectUrl("https://host/$cleanDisp") == DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS) {
                                val renamed = File(destFile.parentFile, cleanDisp)
                                destFile = renamed
                                updateTask(taskId) { it.copy(fileName = cleanDisp, destinationPath = renamed.absolutePath) }
                            }
                        }
                    }

                    val appendMode = resp.code == 206 && existingBytes > 0L
                    val body = resp.body ?: throw IllegalStateException("Empty response body")
                    val contentLen = body.contentLength()
                    val totalBytes = when {
                        contentLen <= 0L -> -1L
                        appendMode -> existingBytes + contentLen
                        else -> contentLen
                    }

                    updateTask(taskId) {
                        it.copy(
                            totalBytes = totalBytes,
                            downloadedBytes = if (appendMode) existingBytes else 0L
                        )
                    }

                    val megaCipher: Cipher? = if (isMega) {
                        val keyBytes = hexToBytes(task.megaKeyHex!!)
                        val nonceBytes = hexToBytes(task.megaNonceHex!!)
                        val iv = ByteArray(16)
                        System.arraycopy(nonceBytes, 0, iv, 0, minOf(8, nonceBytes.size))
                        var blockCounter = if (appendMode) existingBytes / 16L else 0L
                        for (idx in 15 downTo 8) {
                            iv[idx] = (blockCounter and 0xFFL).toByte()
                            blockCounter = blockCounter ushr 8
                        }
                        Cipher.getInstance("AES/CTR/NoPadding").apply {
                            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                        }
                    } else null

                    var written = if (appendMode) existingBytes else 0L
                    var lastTickMs = System.currentTimeMillis()
                    var bytesSinceTick = 0L
                    val buffer = ByteArray(64 * 1024)

                    if (appendMode) {
                        body.byteStream().use { input ->
                            RandomAccessFile(destFile, "rw").use { raf ->
                                raf.seek(existingBytes)
                                raf.setLength(existingBytes)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    val outBytes = megaCipher?.update(buffer, 0, read) ?: buffer
                                    val outLen = if (megaCipher != null) outBytes.size else read
                                    if (outLen > 0) raf.write(outBytes, 0, outLen)
                                    written += read
                                    bytesSinceTick += read
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - lastTickMs
                                    if (elapsed >= 350L) {
                                        val speed = (bytesSinceTick * 1000L) / elapsed.coerceAtLeast(1L)
                                        updateTask(taskId) { it.copy(downloadedBytes = written, speedBytesPerSec = speed) }
                                        lastTickMs = now
                                        bytesSinceTick = 0L
                                    }
                                }
                                megaCipher?.doFinal()?.takeIf { it.isNotEmpty() }?.let { raf.write(it) }
                            }
                        }
                    } else {
                        body.byteStream().use { input ->
                            FileOutputStream(destFile, false).use { fos ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    val outBytes = megaCipher?.update(buffer, 0, read) ?: buffer
                                    val outLen = if (megaCipher != null) outBytes.size else read
                                    if (outLen > 0) fos.write(outBytes, 0, outLen)
                                    written += read
                                    bytesSinceTick += read
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - lastTickMs
                                    if (elapsed >= 350L) {
                                        val speed = (bytesSinceTick * 1000L) / elapsed.coerceAtLeast(1L)
                                        updateTask(taskId) { it.copy(downloadedBytes = written, speedBytesPerSec = speed) }
                                        lastTickMs = now
                                        bytesSinceTick = 0L
                                    }
                                }
                                megaCipher?.doFinal()?.takeIf { it.isNotEmpty() }?.let { fos.write(it) }
                            }
                        }
                    }

                    // Auto-extract ZIP batches
                    val extractedList = mutableListOf<String>()
                    if (autoExtractZip && destFile.extension.equals("zip", ignoreCase = true)) {
                        updateTask(taskId) {
                            it.copy(
                                status = DownloadStatus.EXTRACTING,
                                downloadedBytes = written,
                                speedBytesPerSec = 0L
                            )
                        }
                        val outDir = destFile.parentFile ?: rootDownloadDir()
                        val extractedFiles = ZipExtractor.extractMediaSafely(destFile, outDir)
                        extractedFiles.forEach { extractedList.add(it.absolutePath) }
                        if (extractedFiles.isNotEmpty()) {
                            runCatching { destFile.delete() }
                        }
                    }

                    updateTask(taskId) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            downloadedBytes = written,
                            totalBytes = if (totalBytes > 0L) totalBytes else written,
                            speedBytesPerSec = 0L,
                            extractedFiles = extractedList
                        )
                    }
                    refreshLocalLibrary()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = e.message ?: "Download failed",
                        speedBytesPerSec = 0L
                    )
                }
            } finally {
                activeJobs.remove(taskId)
            }
        }
        activeJobs[taskId] = job
    }

    private fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == taskId) transform(it) else it } }
    }

    private fun guessFileName(seriesTitle: String, episodeLabel: String, target: ResolvedTarget): String {
        val hint = target.fileNameHint
        if (!hint.isNullOrBlank()) {
            val clean = sanitizeFileName(hint)
            if (clean.contains('.')) return clean
        }
        val urlPath = target.url.substringBefore('?').substringBefore('#')
        val lastSeg = urlPath.substringAfterLast('/', "")
        val ext = lastSeg.substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("mp4", "mkv", "webm", "zip", "rar", "7z", "avi", "mov") }
            ?: if (target.isZip) "zip" else "mp4"

        val base = sanitizeFileName("${seriesTitle}_${episodeLabel}").take(72)
        return "$base.$ext"
    }

    private fun extractDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""filename\*?=(?:UTF-8'')?["']?([^"';\r\n]+)["']?""", RegexOption.IGNORE_CASE)
            .find(header)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
