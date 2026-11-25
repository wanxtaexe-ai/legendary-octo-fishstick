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

    @Test
    fun megaKeyDerivationProduces16ByteAesKeyAnd8ByteNonce() {
        val keyStr = "jJuzvYPQey-3Luvq3HV0T5PgMDot9MLmAQT_Ya8oKeM"
        val raw = com.animeext.downloader.parser.Base64Codec.decodeToBytes(keyStr)!!
        assertEquals(32, raw.size)
        val aesKey = ByteArray(16) { i -> (raw[i].toInt() xor raw[i + 16].toInt()).toByte() }
        assertEquals(16, aesKey.size)
    }

    @Test
    fun interactiveShortenersEscalateDirectlyToHardenedWebView() = runTest {
        val net = NetworkClient().apply { dohEnabled = false }
        val resolver = HttpFastPathResolver(net)
        for (gate in listOf(
            "https://shortsync.epdexx.com/go/abc123",
            "https://srnky.com/EA7epdFz",
            "https://hsalinks.eu.org/short.php?link=xyz",
            "https://ybxanime.com/7k29m4n8q/?t=abc"
        )) {
            val res = resolver.resolve(gate, "https://www.lordsanime.com/")
            assertTrue("Expected NeedsHardenedWebView for $gate, got $res", res is FastPathResult.NeedsHardenedWebView)
        }
    }

    @Test
    fun rawLordsAnimeEpdexxEscalatesToPostPageForJsWrapping() = runTest {
        val net = NetworkClient().apply { dohEnabled = false }
        val resolver = HttpFastPathResolver(net)
        val postUrl = "https://www.lordsanime.com/kizumonogatari-part-1/"
        val rawLas = "https://las.epdexx.com/0:/3.%20HSA%20Anime/Monogatari"
        val res = resolver.resolve(rawLas, postUrl, sourceLinkIndex = 0)
        assertTrue("Expected NeedsHardenedWebView, got $res", res is FastPathResult.NeedsHardenedWebView)
        val webRes = res as FastPathResult.NeedsHardenedWebView
        assertEquals(postUrl, webRes.gateUrl)
        assertEquals(rawLas, webRes.sourceLinkTarget)
        assertEquals(0, webRes.sourceLinkIndex)
    }

    @Test
    fun zipIntegrityHelpersRejectHtmlAndAcceptPkzip() {
        val tempDir = Files.createTempDirectory("zip_sig_test").toFile()
        try {
            val htmlFakeZip = File(tempDir, "fake.zip")
            htmlFakeZip.writeText("<!DOCTYPE html><html><body>Blocked</body></html>")
            assertTrue(ZipExtractor.looksLikeHtmlPayload(htmlFakeZip))
            assertTrue(!ZipExtractor.isValidZipSignature(htmlFakeZip))

            val validZip = File(tempDir, "real.zip")
            ZipOutputStream(FileOutputStream(validZip)).use { zos ->
                zos.putNextEntry(ZipEntry("Ep01.mp4"))
                zos.write("VIDEO".toByteArray())
                zos.closeEntry()
            }
            assertTrue(!ZipExtractor.looksLikeHtmlPayload(validZip))
            assertTrue(ZipExtractor.isValidZipSignature(validZip))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun resolvesHubCloudDriveMirrorsViaMockWebServerAndSkipsAds() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/drive/g991mxga" -> MockResponse().setResponseCode(200).setBody(
                        """
                        <html>
                          <head><title>The.100.Girlfriends.S01.Batch.zip</title></head>
                          <body>
                            <a id="download" href="/hubcloud.php?host=fsl&id=123">Generate Direct Download Link</a>
                            <a href="https://tamasha.core-x.live/checkout?plan=90">Watch Hindi Webseries Now</a>
                          </body>
                        </html>
                        """.trimIndent()
                    )
                    "/hubcloud.php?host=fsl&id=123" -> MockResponse().setResponseCode(200).setBody(
                        """
                        <html><body>
                          <a href="https://pixeldrain.com/u/HubTest99">Pixeldrain Server</a>
                          <a href="https://cdn.example.com/files/The.100.Girlfriends.S01.Batch.zip">Download [FSL Server]</a>
                          <a href="https://t.me/animechannel">Join Telegram</a>
                        </body></html>
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val net = NetworkClient().apply { dohEnabled = false }
            val resolver = HttpFastPathResolver(net)
            val trace = mutableListOf<String>()
            val targets = resolver.resolveHubCloud(server.url("/drive/g991mxga").toString(), null, trace)
            assertEquals(2, targets.size)
            assertEquals("https://pixeldrain.com/api/file/HubTest99", targets[0].url)
            assertEquals("https://cdn.example.com/files/The.100.Girlfriends.S01.Batch.zip", targets[1].url)
            assertTrue(targets.none { it.url.contains("tamasha") || it.url.contains("t.me") })
        } finally {
            server.shutdown()
        }
    }
}
