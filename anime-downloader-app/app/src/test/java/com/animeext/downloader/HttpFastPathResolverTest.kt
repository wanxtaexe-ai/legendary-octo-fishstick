package com.animeext.downloader

import com.animeext.downloader.download.ZipExtractor
import com.animeext.downloader.network.NetworkClient
import com.animeext.downloader.resolver.FastPathResult
import com.animeext.downloader.resolver.HttpFastPathResolver
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HttpFastPathResolverTest {

    @Test
    fun decodesTpxRedirectUrlOffline() {
        // Construct an encoded test token using the exact reversed base-36 algorithm
        val target = "https://www.mirrored.to/files/ABC123/"
        val key = "7eea12b58ee6bd4833706e6d5a9468a865315209"
        val encoded = buildString {
            target.forEachIndexed { idx, c ->
                val v = c.code + key[idx % key.length].code
                val b36 = v.toString(36).padStart(2, '0')
                append(b36[1]).append(b36[0])
            }
        }
        val decoded = HttpFastPathResolver.decodeTpxRedirectUrl("https://links.tpxsub.com/redirect/main2.php?url=$encoded")
        assertEquals(target, decoded)
    }

    @Test
    fun zipExtractorExtractsMediaAndBlocksZipSlip() {
        val tempDir = Files.createTempDirectory("zip_test").toFile()
        try {
            val zipFile = File(tempDir, "batch.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.putNextEntry(ZipEntry("Episode_01.mp4"))
                zos.write("FAKE_MP4".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("../../outside/evil.exe"))
                zos.write("BAD".toByteArray())
                zos.closeEntry()
            }
            val outDir = File(tempDir, "extracted")
            val extracted = ZipExtractor.extractMediaSafely(zipFile, outDir)
            assertEquals(1, extracted.size)
            assertEquals("Episode_01.mp4", extracted.first().name)
            assertTrue(extracted.first().canonicalPath.startsWith(outDir.canonicalPath))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun resolvesAdLinkFlyGoFormViaMockWebServer() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/gate" -> MockResponse().setResponseCode(200).setBody(
                        """
                        <html><body>
                          <form id="go-link" action="/links/go" method="post">
                            <input type="hidden" name="ad_form_data" value="tok123" />
                          </form>
                        </body></html>
                        """.trimIndent()
                    )
                    "/links/go" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"success","url":"https://pixeldrain.com/u/SampleId99"}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val net = NetworkClient().apply { dohEnabled = false }
            val resolver = HttpFastPathResolver(net)
            val res = resolver.resolve(server.url("/gate").toString(), initialReferer = null)
            assertTrue("Expected Resolved, got $res", res is FastPathResult.Resolved)
            val resolved = res as FastPathResult.Resolved
            assertEquals("https://pixeldrain.com/api/file/SampleId99", resolved.targets.first().url)
        } finally {
            server.shutdown()
        }
    }
}
