package dev.asfakul.yumeiro.adchain

import kotlinx.coroutines.delay
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class InMemorySessionCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.computeIfAbsent(url.host) { mutableListOf() }
        synchronized(list) {
            for (c in cookies) {
                list.removeAll { it.name == c.name && it.path == c.path }
                if (c.expiresAt >= System.currentTimeMillis()) {
                    list.add(c)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val list = store[url.host] ?: return emptyList()
        synchronized(list) {
            list.removeAll { it.expiresAt < now }
            return list.toList()
        }
    }

    fun snapshotForHost(host: String): Map<String, String> {
        val list = store[host] ?: return emptyMap()
        synchronized(list) {
            return list.associate { it.name to it.value }
        }
    }

    fun seedCookies(url: HttpUrl, cookies: Map<String, String>) {
        val built = cookies.map { (k, v) ->
            Cookie.Builder()
                .name(k)
                .value(v)
                .domain(url.host)
                .path("/")
                .build()
        }
        saveFromResponse(url, built)
    }
}

sealed class FastPathOutcome {
    data class Resolved(
        val destination: ValidatedDestination,
        val hopCount: Int,
        val lastReferer: String
    ) : FastPathOutcome()

    data class NeedsWebViewOrHuman(
        val currentUrl: String,
        val reason: String,
        val challengeKind: HumanChallengeKind? = null,
        val hopCount: Int = 0
    ) : FastPathOutcome()

    data class Failed(val lastUrl: String, val reason: String) : FastPathOutcome()
}

/**
 * Pure-HTTP multi-hop ad-chain resolver using an isolated session [CookieJar].
 * Handles token wrappers, 3xx redirects, meta-refresh, literal JS redirects, WP Safelink forms,
 * and non-CAPTCHA AdLinkFly `/links/go` POST exchanges without launching a WebView.
 */
class AdChainHttpFastPath(
    val cookieJar: InMemorySessionCookieJar = InMemorySessionCookieJar(),
    private val userAgent: String = DEFAULT_UA,
    private val maxHops: Int = 12,
    private val maxTimerWaitSeconds: Int = 20
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(startUrl: String, initialReferer: String? = null): FastPathOutcome {
        val initialUnwrapped = AdProviderRecognizer.unwrapTokenUrl(startUrl) ?: startUrl
        if (DestinationValidator.isDirectMediaOrCloudHost(initialUnwrapped)) {
            val validated = DestinationValidator.validate(initialUnwrapped)
            if (validated !is ValidatedDestination.Invalid) {
                return FastPathOutcome.Resolved(validated, hopCount = 0, lastReferer = initialReferer ?: startUrl)
            }
        }

        var method = "GET"
        var currentUrl = initialUnwrapped
        var referer = initialReferer
        var postFields: Map<String, String>? = null
        var ajax = false
        var lastDelaySeconds = 0
        var timerRetries = 0
        val visited = HashSet<String>()

        for (hop in 1..maxHops) {
            val key = "$method|$currentUrl|${postFields?.hashCode() ?: 0}"
            if (!visited.add(key)) {
                return FastPathOutcome.Failed(currentUrl, "redirect_loop")
            }

            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", userAgent)
                .header("Accept", if (ajax) "application/json, text/javascript, */*; q=0.01" else "text/html,application/xhtml+xml,*/*;q=0.8")
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", referer)
            }
            if (method == "POST") {
                val formBuilder = FormBody.Builder()
                postFields.orEmpty().forEach { (k, v) -> formBuilder.add(k, v) }
                reqBuilder.post(formBuilder.build())
                if (ajax) {
                    reqBuilder.header("X-Requested-With", "XMLHttpRequest")
                    AdFilterPolicy.hostOf(currentUrl)?.let { h ->
                        val scheme = currentUrl.substringBefore("://", "https")
                        reqBuilder.header("Origin", "$scheme://$h")
                    }
                }
            }

            val (statusCode, landedUrl, contentType, body) = runCatching {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    Quad(resp.code, resp.request.url.toString(), resp.header("Content-Type").orEmpty(), resp.body?.string().orEmpty())
                }
            }.getOrElse { err ->
                return FastPathOutcome.Failed(currentUrl, "network_error:${err.message}")
            }

            if (DestinationValidator.isDirectMediaOrCloudHost(landedUrl)) {
                val validated = DestinationValidator.validate(landedUrl)
                if (validated !is ValidatedDestination.Invalid) {
                    return FastPathOutcome.Resolved(validated, hop, referer ?: landedUrl)
                }
            }

            // JSON destination response (e.g. AdLinkFly /links/go)
            val trimmed = body.trimStart()
            if (contentType.contains("json", ignoreCase = true) || trimmed.startsWith("{")) {
                val jsonDest = parseAdLinkFlyJson(trimmed)
                if (jsonDest != null) {
                    val validated = DestinationValidator.validate(jsonDest)
                    return if (validated is ValidatedDestination.Invalid) {
                        FastPathOutcome.Failed(jsonDest, validated.reason)
                    } else {
                        FastPathOutcome.Resolved(validated, hop, landedUrl)
                    }
                }
            }

            // If a CDN/WAF returned 403/503 challenge, hand off to WebView / Human Gate
            val pageClass = AdProviderRecognizer.classify(landedUrl, body)
            if (statusCode in setOf(403, 429, 503) && pageClass.challengeKind != null) {
                return FastPathOutcome.NeedsWebViewOrHuman(
                    currentUrl = landedUrl,
                    reason = "http_${statusCode}_challenge",
                    challengeKind = pageClass.challengeKind,
                    hopCount = hop
                )
            }

            when (pageClass.family) {
                AdProviderFamily.TOKEN_WRAPPER -> {
                    val next = pageClass.unwrappedTokenUrl!!
                    method = "GET"
                    referer = landedUrl
                    currentUrl = next
                    postFields = null
                    ajax = false
                }
                AdProviderFamily.HUMAN_CHALLENGE -> {
                    return FastPathOutcome.NeedsWebViewOrHuman(
                        currentUrl = landedUrl,
                        reason = "human_verification_required",
                        challengeKind = pageClass.challengeKind,
                        hopCount = hop
                    )
                }
                AdProviderFamily.ADLINKFLY -> {
                    method = "POST"
                    referer = landedUrl
                    currentUrl = pageClass.adLinkFlyActionUrl!!
                    postFields = pageClass.adLinkFlyFields
                    ajax = true
                }
                AdProviderFamily.WP_SAFELINK -> {
                    if (pageClass.safelinkDelaySeconds > 0) {
                        lastDelaySeconds = maxOf(lastDelaySeconds, pageClass.safelinkDelaySeconds)
                    }
                    val direct = pageClass.safelinkDirectTarget
                    if (!direct.isNullOrBlank()) {
                        method = "GET"
                        referer = landedUrl
                        currentUrl = direct
                        postFields = null
                        ajax = false
                    } else if (!pageClass.safelinkFormAction.isNullOrBlank()) {
                        if (pageClass.safelinkFormMethod == "POST") {
                            method = "POST"
                            referer = landedUrl
                            currentUrl = pageClass.safelinkFormAction
                            postFields = pageClass.safelinkFields
                            ajax = false
                        } else {
                            val qs = pageClass.safelinkFields.entries.joinToString("&") { (k, v) ->
                                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                            }
                            val sep = if (pageClass.safelinkFormAction.contains("?")) "&" else "?"
                            method = "GET"
                            referer = landedUrl
                            currentUrl = if (qs.isNotBlank()) "${pageClass.safelinkFormAction}$sep$qs" else pageClass.safelinkFormAction
                            postFields = null
                            ajax = false
                        }
                    } else {
                        return FastPathOutcome.NeedsWebViewOrHuman(landedUrl, "safelink_requires_js", hopCount = hop)
                    }
                }
                AdProviderFamily.COUNTDOWN_INTERSTITIAL -> {
                    if (pageClass.isServerTimerTooEarly && timerRetries < 2) {
                        timerRetries++
                        visited.remove(key)
                        val waitSec = minOf(maxOf(lastDelaySeconds + 1, 2), maxTimerWaitSeconds)
                        delay(waitSec * 1000L)
                        method = "GET"
                        currentUrl = landedUrl
                        postFields = null
                        ajax = false
                    } else {
                        val next = pageClass.metaRefreshUrl
                            ?: pageClass.jsRedirectUrl
                            ?: pageClass.continueLinkUrl
                        if (next != null && next != landedUrl) {
                            method = "GET"
                            referer = landedUrl
                            currentUrl = next
                            postFields = null
                            ajax = false
                        } else {
                            return FastPathOutcome.NeedsWebViewOrHuman(landedUrl, "js_timer_or_dom_gate", hopCount = hop)
                        }
                    }
                }
                AdProviderFamily.MEDIA_OR_CLOUD_HOST -> {
                    val validated = DestinationValidator.validate(landedUrl)
                    return if (validated is ValidatedDestination.Invalid) {
                        FastPathOutcome.Failed(landedUrl, validated.reason)
                    } else {
                        FastPathOutcome.Resolved(validated, hop, referer ?: landedUrl)
                    }
                }
                AdProviderFamily.GENERIC_HTML -> {
                    val validated = DestinationValidator.validate(landedUrl)
                    return if (validated is ValidatedDestination.Invalid) {
                        FastPathOutcome.NeedsWebViewOrHuman(landedUrl, validated.reason, hopCount = hop)
                    } else {
                        FastPathOutcome.Resolved(validated, hop, referer ?: landedUrl)
                    }
                }
            }
        }

        return FastPathOutcome.Failed(currentUrl, "max_hops_exceeded")
    }

    private fun parseAdLinkFlyJson(body: String): String? {
        return runCatching {
            val obj = JSONObject(body)
            listOf("url", "link", "destination", "target", "redirect")
                .firstNotNullOfOrNull { k -> obj.optString(k, "").takeIf { it.startsWith("http") } }
        }.getOrNull()
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}
