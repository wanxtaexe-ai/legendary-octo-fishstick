/*
 * Yumeiro AdChain Drop-in Integration
 * Connects yumeiro-adchain (HTTP fast-path + hardened WebView + DestinationValidator)
 * into Yumeiro's StreamResolver and OnlinePlayback / WebDownloads.
 */
package dev.asfakul.yumeiro.stream

import android.content.Context
import dev.asfakul.yumeiro.adchain.AdChainHttpFastPath
import dev.asfakul.yumeiro.adchain.AdChainResolution
import dev.asfakul.yumeiro.adchain.AdChainSessionCoordinator
import dev.asfakul.yumeiro.adchain.AdProviderRecognizer
import dev.asfakul.yumeiro.adchain.DestinationValidator
import dev.asfakul.yumeiro.adchain.InMemorySessionCookieJar
import dev.asfakul.yumeiro.adchain.ValidatedDestination
import dev.asfakul.yumeiro.adchain.android.AdChainWebViewRunner
import dev.asfakul.yumeiro.network.NetworkClient
import dev.asfakul.yumeiro.online.web.BrowserCheck
import dev.asfakul.yumeiro.ui.screens.online.AdChainGateCoordinator
import okhttp3.OkHttpClient

object YumeiroAdChainBridge {

    fun looksLikeAdChain(url: String, html: String? = null): Boolean {
        if (AdProviderRecognizer.unwrapTokenUrl(url) != null) return true
        if (DestinationValidator.isShortenerGateHost(url)) return true
        if (!html.isNullOrBlank()) {
            val classified = AdProviderRecognizer.classify(url, html)
            return classified.family in setOf(
                dev.asfakul.yumeiro.adchain.AdProviderFamily.ADLINKFLY,
                dev.asfakul.yumeiro.adchain.AdProviderFamily.WP_SAFELINK,
                dev.asfakul.yumeiro.adchain.AdProviderFamily.COUNTDOWN_INTERSTITIAL,
                dev.asfakul.yumeiro.adchain.AdProviderFamily.HUMAN_CHALLENGE,
                dev.asfakul.yumeiro.adchain.AdProviderFamily.TOKEN_WRAPPER,
            )
        }
        return false
    }

    /**
     * Called from [StreamResolver.resolve] when normal probing landed on [Resolution.NeedsBrowser]
     * or [Resolution.Failed] for a shortener / ad-gate URL.
     *
     * Fast-path HTTP runs first (no WebView cost); if JS/CAPTCHA is genuinely required,
     * [AdChainWebViewRunner] + [AdChainGateHost] run with session cookie preservation.
     */
    suspend fun tryResolveIfAdChain(
        context: Context,
        request: StreamRequest,
        prior: Resolution,
    ): Resolution? {
        val targetUrl = when (prior) {
            is Resolution.NeedsBrowser -> prior.pageUrl
            is Resolution.Failed -> request.url
            else -> return null
        }.trim()

        if (!targetUrl.startsWith("http", ignoreCase = true)) return null
        if (!looksLikeAdChain(targetUrl) && prior !is Resolution.NeedsBrowser) return null

        val appCtx = context.applicationContext
        val ua = runCatching { BrowserCheck.userAgent() }.getOrElse { StreamResolver.defaultUserAgent(appCtx) }
        val baseClient: OkHttpClient = StreamInterceptors.clientFor(targetUrl, NetworkClient.get(appCtx))
        val jar = InMemorySessionCookieJar()

        val fastPath = AdChainHttpFastPath(
            cookieJar = jar,
            userAgent = ua,
            baseClient = baseClient,
        )
        val runner = AdChainWebViewRunner(
            context = appCtx,
            userAgent = ua,
            humanGatePresenter = AdChainGateCoordinator::present,
        )
        val coordinator = AdChainSessionCoordinator(fastPath = fastPath, webViewDriver = runner)

        return when (val out = coordinator.resolve(targetUrl, request.referer ?: request.pageUrl)) {
            is AdChainResolution.PlayOrDownload -> toYumeiroResolution(request, out, ua, baseClient)
            is AdChainResolution.HumanInteractionRequired -> Resolution.NeedsBrowser(
                pageUrl = out.gateUrl,
                reason = "This link requires human verification (${out.challengeKind?.name ?: "check"}).",
                base = request,
                auto = false,
            )
            is AdChainResolution.Unresolvable -> null
        }
    }

    private fun toYumeiroResolution(
        original: StreamRequest,
        resolved: AdChainResolution.PlayOrDownload,
        userAgent: String,
        client: OkHttpClient,
    ): Resolution? {
        val cookieHeader = resolved.cookies.entries
            .takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { "${it.key}=${it.value}" }

        return when (val d = resolved.destination) {
            is ValidatedDestination.DirectMedia -> {
                val sniff = StreamSniffer.fromUrl(d.url)
                val fmt = when {
                    d.kindHint == "hls" -> StreamFormat.HLS
                    d.kindHint == "dash" -> StreamFormat.DASH
                    sniff != null -> sniff.format
                    else -> StreamFormat.PROGRESSIVE
                }
                Resolution.Playable(
                    original.copy(
                        url = d.url,
                        format = fmt,
                        userAgent = resolved.userAgent,
                        referer = resolved.referer ?: original.referer,
                        cookie = cookieHeader ?: original.cookie,
                        pageUrl = original.pageUrl ?: original.url,
                    ),
                    sniff ?: Sniff(MediaKind.VIDEO, "Stream", "adchain"),
                )
            }
            is ValidatedDestination.CloudFileHost -> {
                val updated = original.copy(
                    url = d.normalizedUrl,
                    userAgent = resolved.userAgent,
                    referer = resolved.referer ?: original.referer,
                    cookie = cookieHeader ?: original.cookie,
                    pageUrl = original.pageUrl ?: original.url,
                )
                StreamResolver.resolveBlocking(updated, userAgent, client, thorough = true)
            }
            is ValidatedDestination.WebEmbedPage -> {
                val updated = original.copy(
                    url = d.url,
                    userAgent = resolved.userAgent,
                    referer = resolved.referer ?: original.referer,
                    cookie = cookieHeader ?: original.cookie,
                    pageUrl = original.pageUrl ?: d.url,
                )
                StreamResolver.resolveBlocking(updated, userAgent, client, thorough = true)
            }
            is ValidatedDestination.Invalid -> null
        }
    }
}
