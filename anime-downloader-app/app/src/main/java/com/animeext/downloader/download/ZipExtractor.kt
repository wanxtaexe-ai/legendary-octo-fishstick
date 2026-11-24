package com.animeext.downloader.download

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ZipExtractor {

    private val allowedMediaExtensions = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "srt", "ass", "vtt"
    )

    /**
     * Extracts media and subtitle files from [zipFile] into [destDir], rejecting any
     * Zip-Slip path traversal entries (`../`). Returns extracted file paths.
     */
    fun extractMediaSafely(zipFile: File, destDir: File): List<File> {
        if (!destDir.exists()) destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile
        val extracted = mutableListOf<File>()

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(64 * 1024)
            while (entry != null) {
                if (!entry.isDirectory) {
                    val safeName = File(entry.name).name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    val ext = safeName.substringAfterLast('.', "").lowercase()
                    if (safeName.isNotBlank() && ext in allowedMediaExtensions) {
                        val outFile = File(canonicalDest, safeName).canonicalFile
                        if (!outFile.path.startsWith(canonicalDest.path + File.separator)) {
                            throw SecurityException("Blocked Zip-Slip entry: ${entry.name}")
                        }
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val read = zis.read(buffer)
                                if (read <= 0) break
                                fos.write(buffer, 0, read)
                            }
                        }
                        extracted.add(outFile)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return extracted
    }
}
