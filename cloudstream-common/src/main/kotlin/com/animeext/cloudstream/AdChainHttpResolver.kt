package com.animeext.cloudstream

import android.util.Base64
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class AdChainResult {
    data class Resolved(val url: String, val referer: String, val hops: Int) : AdChainResult()
    data class HumanGateRequired(val gateUrl: String, val reason: String) : AdChainResult()
    data class DeadOrBlocked(val url: String, val reason: String) : AdChainResult()
}

/**
 * Provider-agnostic pure-HTTP multi-hop ad-chain resolver for CloudStream extensions.
 * Recognizes provider families (token wrappers, WP Safelink, AdLinkFly /links/go, meta/JS
 * redirects) instead of per-domain hacks, maintains cookies across hops, and halts cleanly when
 * genuine human verification (Turnstile, reCAPTCHA, hCaptcha) is encountered.
 */
object AdChainHttpResolver {

    private const val MAX_HOPS = 8

    private val challengeMarkers = listOf(
        "g-recaptcha",
        "cf-turnstile",
        "h-captcha",
        "challenges.cloudflare.com",
        "/cdn-cgi/challenge-platform/",
        "please click on below captcha box",
        "adcopy_challenge"
    )

    private val interstitialMarkers = listOf(
        "please wait",
        "just a moment",
        "redirecting",
        "verifying",
        "safelink",
        "too early",
        "almost ready"
    )

    private val continueTexts = setOf(
        "continue",
        "proceed",
        "get link",
        "go to link",
        "click here to continue",
        "skip ad"
    )

    fun unwrapTokenUrl(url: String): String? {
        WordPressStreamResolvers.decodeTpxRedirectUrl(url)?.let { return it }
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return null

        val paramNames = listOf("url", "safe", "shortid", "link", "target", "dest")
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=').lowercase()
            if (key !in paramNames) continue
            val raw = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }
                .getOrDefault(pair.substringAfter('=', "")).trim()
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                return raw
            }
            decodeBase64Url(raw)?.let { return it }
        }
        return null
    }

    private fun decodeBase64Url(raw: String): String? {
        if (raw.length < 12) return null
        return runCatching {
            val normalized = raw.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
        }.getOrNull()
    }

    suspend fun resolve(startUrl: String, initialReferer: String): AdChainResult {
        val unwrappedStart = unwrapTokenUrl(startUrl) ?: startUrl
        if (WordPressStreamResolvers.isKnownDeadUrl(unwrappedStart)) {
            return AdChainResult.DeadOrBlocked(unwrappedStart, "dead_or_ad_only_host")
        }

        val cookies = LinkedHashMap<String, String>()
        val visited = HashSet<String>()
        var method = "GET"
        var currentUrl = unwrappedStart
        var referer = initialReferer
        var postFields: Map<String, String>? = null
        var ajax = false
        var lastKnownDelaySec = 0
        var retries = 0

        for (hop in 1..MAX_HOPS) {
            val visitKey = "$method|$currentUrl|${postFields?.hashCode() ?: 0}"
            if (!visited.add(visitKey)) {
                return AdChainResult.DeadOrBlocked(currentUrl, "redirect_loop")
            }

            val resp = runCatching {
                if (method == "POST") {
                    val headers = if (ajax) {
                        val uri = URI(currentUrl)
                        mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Origin" to "${uri.scheme}://${uri.authority}"
                        )
                    } else {
                        emptyMap()
                    }
                    app.post(
                        url = currentUrl,
                        headers = headers,
                        referer = referer,
                        cookies = cookies,
                        data = postFields ?: emptyMap()
                    )
                } else {
                    app.get(
                        url = currentUrl,
                        referer = referer,
                        cookies = cookies
                    )
                }
            }.getOrNull() ?: return AdChainResult.DeadOrBlocked(currentUrl, "network_error")

            cookies.putAll(resp.cookies)
            val landedUrl = resp.url
            if (WordPressStreamResolvers.isKnownDeadUrl(landedUrl)) {
                return AdChainResult.DeadOrBlocked(landedUrl, "dead_or_ad_only_host")
            }

            val unwrappedLanded = unwrapTokenUrl(landedUrl)
            if (unwrappedLanded != null && unwrappedLanded != landedUrl) {
                return AdChainResult.Resolved(unwrappedLanded, landedUrl, hop)
            }

            val body = resp.text
            val trimmed = body.trimStart()

            // JSON destination response (AdLinkFly /links/go)
            if (trimmed.startsWith("{")) {
                val jsonUrl = runCatching {
                    val obj = JSONObject(trimmed)
                    listOf("url", "link", "destination", "target", "redirect")
                        .firstNotNullOfOrNull { k -> obj.optString(k, "").takeIf { it.startsWith("http") } }
                }.getOrNull()
                if (jsonUrl != null) {
                    return AdChainResult.Resolved(jsonUrl, landedUrl, hop)
                }
            }

            val lower = body.lowercase()
            // Genuine human verification gate check
            val matchedChallenge = challengeMarkers.firstOrNull { lower.contains(it) }
            if (matchedChallenge != null) {
                return AdChainResult.HumanGateRequired(landedUrl, matchedChallenge)
            }

            // Server-enforced "Too Early" timer retry (bounded)
            if (lower.contains("too early") && retries < 1 && lastKnownDelaySec in 1..15) {
                retries++
                visited.remove(visitKey)
                delay((lastKnownDelaySec + 1) * 1000L)
                method = "GET"
                currentUrl = landedUrl
                postFields = null
                ajax = false
                continue
            }

            val doc = Jsoup.parse(body, landedUrl)
            val forms = doc.select("form")

            // 1. WP Safelink (`newwpsafelink` JSON payload or `form[name=tp]`)
            var nextActionSet = false
            for (form in forms) {
                val inputs = LinkedHashMap<String, String>()
                for (inp in form.select("input[name]")) {
                    val type = inp.attr("type").lowercase()
                    if (type in setOf("submit", "button", "image", "reset", "file")) continue
                    inputs[inp.attr("name")] = inp.attr("value")
                }

                val safeB64 = inputs["newwpsafelink"]
                if (!safeB64.isNullOrBlank()) {
                    val safeJson = decodeJsonObject(safeB64)
                    if (safeJson != null) {
                        val d = safeJson.optInt("delay", 0)
                        if (d > 0) lastKnownDelaySec = maxOf(lastKnownDelaySec, d)
                        val target = listOf("linkr", "second_safelink_url", "newsafelink")
                            .firstNotNullOfOrNull { k -> safeJson.optString(k, "").takeIf { it.startsWith("http") } }
                        if (target != null) {
                            method = "GET"
                            currentUrl = target
                            referer = landedUrl
                            postFields = null
                            ajax = false
                            nextActionSet = true
                            break
                        }
                    }
                }

                val formAction = WordPressSemanticParser.resolveUrl(form.attr("action").ifBlank { landedUrl }, landedUrl)
                    ?: landedUrl
                val formMethod = form.attr("method").ifBlank { "get" }.uppercase()
                val formId = form.attr("id")
                val formName = form.attr("name")

                // AdLinkFly destination form (`#go-link` / `ad_form_data` / `/links/go`)
                if (inputs.containsKey("ad_form_data") || formId == "go-link" || formAction.trimEnd('/').endsWith("/links/go")) {
                    method = "POST"
                    currentUrl = formAction
                    referer = landedUrl
                    postFields = inputs
                    ajax = true
                    nextActionSet = true
                    break
                }

                // Generic WP Safelink `form[name=tp]` or hidden `go` token form
                if (formName == "tp" || inputs.containsKey("go")) {
                    if (formMethod == "POST") {
                        method = "POST"
                        currentUrl = formAction
                        referer = landedUrl
                        postFields = inputs
                        ajax = false
                    } else {
                        val qs = inputs.entries.joinToString("&") { (k, v) ->
                            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                        }
                        val sep = if (formAction.contains("?")) "&" else "?"
                        method = "GET"
                        currentUrl = if (qs.isNotBlank()) "$formAction$sep$qs" else formAction
                        referer = landedUrl
                        postFields = null
                        ajax = false
                    }
                    nextActionSet = true
                    break
                }
            }
            if (nextActionSet) continue

            // 2. Meta refresh
            val metaTarget = doc.selectFirst("meta[http-equiv~=refresh i]")
                ?.attr("content")
                ?.let { Regex("url\\s*=\\s*['\"]?([^'\";\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?.let { WordPressSemanticParser.resolveUrl(it, landedUrl) }
            if (!metaTarget.isNullOrBlank() && metaTarget != landedUrl) {
                method = "GET"
                currentUrl = metaTarget
                referer = landedUrl
                postFields = null
                ajax = false
                continue
            }

            // 3. Literal JS location redirect
            val jsTarget = Regex(
                "(?:window|document|top)\\.location(?:\\.href)?\\s*=\\s*['\"](https?://[^'\"]+)['\"]",
                RegexOption.IGNORE_CASE
            ).find(body)?.groupValues?.get(1)?.replace("\\/", "/")
            if (!jsTarget.isNullOrBlank() && jsTarget != landedUrl) {
                method = "GET"
                currentUrl = jsTarget
                referer = landedUrl
                postFields = null
                ajax = false
                continue
            }

            // 4. Interstitial continue / get-link anchor
            if (interstitialMarkers.any { lower.contains(it) }) {
                val nextHref = doc.select("#nextPage a, a.get-link, #btn-get-link, #get-link-btn, a[href]")
                    .firstOrNull { a ->
                        val href = a.attr("href").trim()
                        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:", true)) return@firstOrNull false
                        val idOrClass = "${a.id()} ${a.className()}".lowercase()
                        val text = a.text().trim().lowercase()
                        idOrClass.contains("get-link") || idOrClass.contains("tp-snp2") || text in continueTexts
                    }
                    ?.let { WordPressSemanticParser.resolveUrl(it.attr("href"), landedUrl) }
                if (!nextHref.isNullOrBlank() && nextHref != landedUrl) {
                    method = "GET"
                    currentUrl = nextHref
                    referer = landedUrl
                    postFields = null
                    ajax = false
                    continue
                }
            }

            return AdChainResult.Resolved(landedUrl, referer, hop)
        }

        return AdChainResult.DeadOrBlocked(currentUrl, "max_hops_exceeded")
    }

    private fun decodeJsonObject(b64: String): JSONObject? {
        return runCatching {
            val normalized = b64.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val text = String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
            JSONObject(text)
        }.getOrNull()
    }
}
