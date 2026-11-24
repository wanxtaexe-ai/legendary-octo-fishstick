package dev.asfakul.yumeiro.adchain

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class AdChainResolverTest {

    @Test
    fun `resolves full ShrinkMe - Safelink - AdLinkFly chain via HTTP fast path with session cookies`() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            val safelinkJson = """{"linkr":"$base/adlinkfly/step3","delay":1}"""
            val safelinkB64 = Base64.getEncoder().encodeToString(safelinkJson.toByteArray(StandardCharsets.UTF_8))

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/shrink/abc" -> MockResponse()
                            .setResponseCode(302)
                            .addHeader("Set-Cookie", "yum_sess=step1; Path=/")
                            .addHeader("Location", "$base/safelink/article1")

                        "/safelink/article1" -> MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """
                                <html><body>
                                  <form name="tp" method="POST" action="$base/safelink/article2">
                                    <input type="hidden" name="newwpsafelink" value="$safelinkB64" />
                                  </form>
                                </body></html>
                                """.trimIndent()
                            )

                        "/adlinkfly/step3" -> MockResponse()
                            .setResponseCode(200)
                            .addHeader("Set-Cookie", "csrf_tok=xyz123; Path=/")
                            .setBody(
                                """
                                <html><body>
                                  <form id="go-link" method="POST" action="$base/links/go">
                                    <input type="hidden" name="ad_form_data" value="signed_payload_99" />
                                    <input type="hidden" name="_csrfToken" value="xyz123" />
                                  </form>
                                </body></html>
                                """.trimIndent()
                            )

                        "/links/go" -> {
                            val cookie = request.getHeader("Cookie").orEmpty()
                            val xhr = request.getHeader("X-Requested-With")
                            if (cookie.contains("yum_sess=step1") && cookie.contains("csrf_tok=xyz123") && xhr == "XMLHttpRequest") {
                                MockResponse()
                                    .setResponseCode(200)
                                    .addHeader("Content-Type", "application/json")
                                    .setBody("""{"status":"success","url":"https://pixeldrain.com/u/AbCdEf12"}""")
                            } else {
                                MockResponse().setResponseCode(403).setBody("""{"status":"error","message":"missing session"}""")
                            }
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val coordinator = AdChainSessionCoordinator()
            val outcome = coordinator.resolve("$base/shrink/abc")
            assertTrue(outcome is AdChainResolution.PlayOrDownload, "Expected PlayOrDownload, got $outcome")
            val result = outcome as AdChainResolution.PlayOrDownload
            assertEquals(false, result.usedWebView)
            assertTrue(result.destination is ValidatedDestination.CloudFileHost)
            val cloud = result.destination as ValidatedDestination.CloudFileHost
            assertEquals("Pixeldrain", cloud.hostName)
            assertEquals("https://pixeldrain.com/api/file/AbCdEf12", cloud.normalizedUrl)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `detects SRNKY human verification gate instead of looping blindly`() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        <html><title>Health Shield</title><body>
                          <p>Please click on below captcha box to proceed to the destination page.</p>
                          <div class="g-recaptcha" data-sitekey="6LcExample"></div>
                        </body></html>
                        """.trimIndent()
                    )
            )

            val coordinator = AdChainSessionCoordinator()
            val outcome = coordinator.resolve("$base/ljpaf")
            assertTrue(outcome is AdChainResolution.HumanInteractionRequired, "Expected HumanInteractionRequired, got $outcome")
            val gate = outcome as AdChainResolution.HumanInteractionRequired
            assertEquals(HumanChallengeKind.RECAPTCHA, gate.challengeKind)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `hands off to WebView driver when JS timer gate blocks HTTP fast path and validates final HLS stream`() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("<html><body><p>Please wait 5 seconds...</p><a id='get-link-btn' class='disabled'>Wait</a></body></html>")
            )

            val fakeWebView = object : WebViewChainDriver {
                override suspend fun runChainInWebView(
                    startUrl: String,
                    referer: String?,
                    cookieJar: InMemorySessionCookieJar
                ): WebViewChainOutcome {
                    return WebViewChainOutcome.DestinationReached(
                        url = "https://cdn.example.net/master.m3u8?token=ok",
                        referer = startUrl,
                        cookies = mapOf("cf_clearance" to "valid123"),
                        userAgent = AdChainHttpFastPath.DEFAULT_UA
                    )
                }
            }

            val coordinator = AdChainSessionCoordinator(webViewDriver = fakeWebView)
            val outcome = coordinator.resolve("$base/gate")
            assertTrue(outcome is AdChainResolution.PlayOrDownload)
            val play = outcome as AdChainResolution.PlayOrDownload
            assertEquals(true, play.usedWebView)
            assertEquals("valid123", play.cookies["cf_clearance"])
            assertTrue(play.destination is ValidatedDestination.DirectMedia)
            assertEquals(true, (play.destination as ValidatedDestination.DirectMedia).isHls)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `preserves verification scripts while blocking ad networks in AdFilterPolicy`() {
        assertEquals(
            AdFilterPolicy.SubresourceDecision.ALLOW,
            AdFilterPolicy.decideSubresource("https://challenges.cloudflare.com/turnstile/v0/api.js", "srnky.com")
        )
        assertEquals(
            AdFilterPolicy.SubresourceDecision.ALLOW,
            AdFilterPolicy.decideSubresource("https://www.google.com/recaptcha/api.js", "srnky.com")
        )
        assertEquals(
            AdFilterPolicy.SubresourceDecision.ALLOW,
            AdFilterPolicy.decideSubresource("https://mrproblogger.com/links/go", "mrproblogger.com")
        )
        assertEquals(
            AdFilterPolicy.SubresourceDecision.BLOCK_AD,
            AdFilterPolicy.decideSubresource("https://tryedesign.com/4/19b5ded025", "srnky.com")
        )
        assertEquals(
            AdFilterPolicy.NavigationDecision.BLOCK_UNSAFE_SCHEME,
            AdFilterPolicy.decideMainFrameNavigation("https://srnky.com/", "intent://ad#Intent;end", hasUserGesture = false, isRedirect = false)
        )
    }

    @Test
    fun `validates destinations, strips tracking wrappers, and rejects dead or unresolved gates`() {
        val wrapped = DestinationValidator.validate("https://www.google.com/url?q=https%3A%2F%2Fpixeldrain.com%2Fu%2FTest123")
        assertTrue(wrapped is ValidatedDestination.CloudFileHost)
        assertEquals("https://pixeldrain.com/api/file/Test123", (wrapped as ValidatedDestination.CloudFileHost).normalizedUrl)

        val dead = DestinationValidator.validate("https://asurasever.cloud/file/123")
        assertTrue(dead is ValidatedDestination.Invalid)

        val unresolvedGate = DestinationValidator.validate("https://srnky.com/ljpaf")
        assertTrue(unresolvedGate is ValidatedDestination.Invalid)

        val decodedTpx = AdProviderRecognizer.unwrapTokenUrl(
            "https://links.tpxsub.com/redirect/main2.php?url=z3u3v3t3s3w1y1n1e36333u3p3r2w2f2r3t3z3v2x2e3p353g1j14262c221d1p2r2g121d181p2k2d272p1m1q2p1l2n2o1g1m1422281j2d2c282g2h2u1n262j2n23121p1e1t2j112f1l2t1h2w202s2g1u2o232i171e1g191j17121m1g1b1i1u2f2f2g181m2d2g1t2n23212s2q2n2p221q2m2n2p2i2v1"
        )
        assertEquals(
            "https://www.mirrored.to/files/F2A10447/[TPX]-Moribito.Guardian.of.the.Spirit.S01.E01-HSub-FHD.mp4_links",
            decodedTpx
        )
    }
}
