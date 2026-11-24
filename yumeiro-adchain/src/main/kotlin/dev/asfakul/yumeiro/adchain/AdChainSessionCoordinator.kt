package dev.asfakul.yumeiro.adchain

/**
 * Abstraction for Yumeiro's WebView + Human-Gate UI layer (`SafeWebView` + `BrowserCheckHost`).
 * Allows pure-JVM unit testing of the full state machine without an Android Looper.
 */
interface WebViewChainDriver {
    suspend fun runChainInWebView(
        startUrl: String,
        referer: String?,
        cookieJar: InMemorySessionCookieJar
    ): WebViewChainOutcome
}

sealed class WebViewChainOutcome {
    data class DestinationReached(
        val url: String,
        val referer: String?,
        val cookies: Map<String, String>,
        val userAgent: String
    ) : WebViewChainOutcome()

    data class HumanVerificationCancelled(val url: String, val challengeKind: HumanChallengeKind?) : WebViewChainOutcome()
    data class Failed(val url: String, val reason: String) : WebViewChainOutcome()
}

sealed class AdChainResolution {
    data class PlayOrDownload(
        val destination: ValidatedDestination,
        val referer: String?,
        val cookies: Map<String, String>,
        val userAgent: String,
        val usedWebView: Boolean
    ) : AdChainResolution()

    data class HumanInteractionRequired(
        val gateUrl: String,
        val challengeKind: HumanChallengeKind?,
        val reason: String
    ) : AdChainResolution()

    data class Unresolvable(
        val lastUrl: String,
        val reason: String
    ) : AdChainResolution()
}

/**
 * Orchestrates Yumeiro's end-to-end ad-chain resolution:
 * 1. HTTP Fast-Path (`AdChainHttpFastPath`) with session cookie jar
 * 2. Hardened WebView automation (`WebViewChainDriver`) + minimal-interruption human gate handoff
 * 3. Strict destination validation (`DestinationValidator`) before playback or download handoff
 */
class AdChainSessionCoordinator(
    private val fastPath: AdChainHttpFastPath = AdChainHttpFastPath(),
    private val webViewDriver: WebViewChainDriver? = null
) {
    suspend fun resolve(startUrl: String, initialReferer: String? = null): AdChainResolution {
        return when (val http = fastPath.resolve(startUrl, initialReferer)) {
            is FastPathOutcome.Resolved -> {
                val host = AdFilterPolicy.hostOf(
                    when (val d = http.destination) {
                        is ValidatedDestination.DirectMedia -> d.url
                        is ValidatedDestination.CloudFileHost -> d.normalizedUrl
                        is ValidatedDestination.WebEmbedPage -> d.url
                        is ValidatedDestination.Invalid -> d.url
                    }
                )
                val cookies = host?.let { fastPath.cookieJar.snapshotForHost(it) }.orEmpty()
                AdChainResolution.PlayOrDownload(
                    destination = http.destination,
                    referer = http.lastReferer,
                    cookies = cookies,
                    userAgent = AdChainHttpFastPath.DEFAULT_UA,
                    usedWebView = false
                )
            }
            is FastPathOutcome.NeedsWebViewOrHuman -> {
                val driver = webViewDriver
                    ?: return AdChainResolution.HumanInteractionRequired(
                        gateUrl = http.currentUrl,
                        challengeKind = http.challengeKind,
                        reason = http.reason
                    )

                when (val wv = driver.runChainInWebView(http.currentUrl, initialReferer, fastPath.cookieJar)) {
                    is WebViewChainOutcome.DestinationReached -> {
                        val validated = DestinationValidator.validate(wv.url)
                        if (validated is ValidatedDestination.Invalid) {
                            AdChainResolution.Unresolvable(wv.url, validated.reason)
                        } else {
                            AdChainResolution.PlayOrDownload(
                                destination = validated,
                                referer = wv.referer,
                                cookies = wv.cookies,
                                userAgent = wv.userAgent,
                                usedWebView = true
                            )
                        }
                    }
                    is WebViewChainOutcome.HumanVerificationCancelled ->
                        AdChainResolution.HumanInteractionRequired(wv.url, wv.challengeKind, "user_cancelled")
                    is WebViewChainOutcome.Failed ->
                        AdChainResolution.Unresolvable(wv.url, wv.reason)
                }
            }
            is FastPathOutcome.Failed -> AdChainResolution.Unresolvable(http.lastUrl, http.reason)
        }
    }
}
