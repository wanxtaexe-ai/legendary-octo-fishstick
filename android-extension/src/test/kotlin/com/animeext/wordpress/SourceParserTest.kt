package com.animeext.wordpress.sources

import com.animeext.wordpress.config.DomainConfigManager
import com.animeext.wordpress.http.ExtensionHttpClient
import com.animeext.wordpress.http.PersistentCookieJar
import com.animeext.wordpress.model.QualityLabel
import com.animeext.wordpress.parser.SemanticHtmlParser
import com.animeext.wordpress.resolver.MediaHostPatterns
import com.animeext.wordpress.resolver.WebViewSessionController
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceParserTest {
    private fun createLocalConfigManager(): DomainConfigManager {
        val candidates = listOf(File("../domains.json"), File("domains.json"))
        val configFile = candidates.first { it.exists() }
        return DomainConfigManager.fromFile(configFile)
    }

    @Test
    fun `domains json loads all 5 sites and supports hot url override`() = runTest {
        val manager = createLocalConfigManager()
        val ids = listOf("lordsanime", "tpxsub", "ybxanime", "hindisubbedacademy", "animevilla")
        for (id in ids) {
            val site = manager.getSiteConfig(id)
            assertTrue(site.enabled)
            assertTrue(site.baseUrl.startsWith("https://"))
        }

        manager.updateBaseUrl("lordsanime", "https://mirror.lordsanime.net/")
        assertEquals("https://mirror.lordsanime.net", manager.getBaseUrl("lordsanime"))
        assertEquals("https://mirror.lordsanime.net/?s=komi", manager.buildSearchUrl("lordsanime", "komi"))
    }

    @Test
    fun `semantic parser extracts series metadata and 480p 720p 1080p episodes`() {
        val html = """
            <html><body><main><article>
              <h1>Moribito – Guardian of the Spirit</h1>
              <div class="entry-content">
                <p><strong>Genres :</strong> Action, Adventure, Fantasy</p>
                <p><strong>Status :</strong> Completed</p>
                <p><strong>Episode 01</strong></p>
                <p><strong>FHD[400MB]</strong> – <a href="https://links.tpxsub.com/redirect/main2.php?url=1080">Mir</a></p>
                <p><strong>HD [200MB]</strong> – <a href="https://links.tpxsub.com/redirect/main2.php?url=720">PL1</a></p>
                <p><strong>SD [99MB]</strong> – <a href="https://pixeldrain.com/u/SdTest01">Mega</a></p>
              </div>
            </article></main></body></html>
        """.trimIndent()

        val detail = SemanticHtmlParser.parseSeriesDetail(html, "https://www.tpxsub.com/moribito/", "tpxsub")
        assertEquals("Completed", detail.status)
        assertTrue(detail.genres.contains("Fantasy"))
        assertEquals(1, detail.episodes.size)
        val qualities = detail.episodes.first().streams.map { it.quality }
        assertEquals(listOf(QualityLabel.Q_1080P, QualityLabel.Q_720P, QualityLabel.Q_480P), qualities)
        assertEquals("400MB", detail.episodes.first().streams[0].size)
        assertEquals(false, detail.episodes.first().streams[2].requiresWebView)
    }

    @Test
    fun `webview session controller detects pixeldrain and blocks ad hosts`() {
        var resolvedUrl: String? = null
        val controller = WebViewSessionController(
            initialUrl = "https://shortsync.epdexx.com/go/abc",
            expectedQuality = QualityLabel.Q_1080P,
            onResolved = { resolvedUrl = it.url }
        )

        val (blockAd, _) = controller.shouldInterceptRequest("https://doubleclick.net/ad.js")
        assertTrue(blockAd)

        controller.onPageStarted("https://shortsync.epdexx.com/step2")
        val (_, hit) = controller.shouldInterceptRequest("https://pixeldrain.com/u/TestVid88")
        assertNotNull(hit)
        assertEquals("https://pixeldrain.com/api/file/TestVid88", resolvedUrl)
        assertEquals("https://pixeldrain.com/api/file/TestVid88", MediaHostPatterns.matchDirectMediaHost("https://pixeldrain.com/u/TestVid88")?.streamUrl)
    }

    @Test
    fun `webview controller blocks popups, intent schemes, and captures download listener`() {
        val controller = WebViewSessionController(
            initialUrl = "https://hsalinks.eu.org/short.php?link=step1",
            expectedQuality = QualityLabel.Q_720P
        )

        assertTrue(controller.shouldOverrideUrlLoading("intent://spam/#Intent;scheme=https;end"))
        assertTrue(controller.shouldOverrideUrlLoading("https://adsterra.com/redirect", isForMainFrame = true))
        assertEquals(false, controller.onPopupAttempt("https://popads.net/win"))
        assertTrue(controller.blockedHijacksCount >= 3)

        controller.adFilterEnabled = false
        val (blockedWhenOff, _) = controller.shouldInterceptRequest("https://adsterra.com/script.js")
        assertEquals(false, blockedWhenOff)

        val dl = controller.onDownloadTriggered(
            "https://cdn.example.net/ep1.mp4",
            "video/mp4",
            "attachment; filename=ep1.mp4"
        )
        assertNotNull(dl)
        assertEquals("https://cdn.example.net/ep1.mp4", dl?.url)
    }

    @Test
    fun `http client persists cookies across mock server redirects`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/catalog")
                .addHeader("Set-Cookie", "wp_test=cookie_ok; Path=/")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><body><article><h2><a href=\"/anime/demo/\">Demo</a></h2></article></body></html>")
        )
        server.start()

        try {
            val cookieJar = PersistentCookieJar()
            val httpClient = ExtensionHttpClient(cookieJar = cookieJar)
            val manager = createLocalConfigManager()
            manager.updateBaseUrl("lordsanime", server.url("").toString().trimEnd('/'))

            val source = SourceRegistry(manager, httpClient).getSource("lordsanime")
            val latest = source.getLatestUpdates()
            assertEquals(1, latest.items.size)

            val firstReq = server.takeRequest()
            val secondReq = server.takeRequest()
            assertTrue(firstReq.getHeader("User-Agent")?.contains("Mozilla/5.0") == true)
            assertTrue(secondReq.getHeader("Cookie")?.contains("wp_test=cookie_ok") == true)
        } finally {
            server.shutdown()
        }
    }
}
