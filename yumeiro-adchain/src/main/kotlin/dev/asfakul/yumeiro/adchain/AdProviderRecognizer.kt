package dev.asfakul.yumeiro.adchain

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class AdProviderFamily {
    ADLINKFLY,
    WP_SAFELINK,
    TOKEN_WRAPPER,
    COUNTDOWN_INTERSTITIAL,
    HUMAN_CHALLENGE,
    MEDIA_OR_CLOUD_HOST,
    GENERIC_HTML
}

enum class HumanChallengeKind {
    CLOUDFLARE_TURNSTILE,
    RECAPTCHA,
    HCAPTCHA,
    ADCOPY,
    CLOUDFLARE_INTERSTITIAL
}

data class PageClassification(
    val family: AdProviderFamily,
    val challengeKind: HumanChallengeKind? = null,
    val unwrappedTokenUrl: String? = null,
    val safelinkDelaySeconds: Int = 0,
    val adLinkFlyActionUrl: String? = null,
    val adLinkFlyFields: Map<String, String> = emptyMap(),
    val safelinkFormAction: String? = null,
    val safelinkFormMethod: String = "GET",
    val safelinkFields: Map<String, String> = emptyMap(),
    val safelinkDirectTarget: String? = null,
    val continueLinkUrl: String? = null,
    val metaRefreshUrl: String? = null,
    val jsRedirectUrl: String? = null,
    val isServerTimerTooEarly: Boolean = false
)

/**
 * Structural provider-family recognizer. Classifies shortener and ad-gate pages by DOM/form/script
 * signatures (AdLinkFly, WP Safelink, token wrappers, countdown interstitials, human challenges)
 * rather than maintaining fragile per-domain hardcoded lists.
 */
object AdProviderRecognizer {

    private const val TPX_REDIRECT_KEY = "7eea12b58ee6bd4833706e6d5a9468a865315209"

    private val interstitialMarkers = listOf(
        "please wait",
        "just a moment",
        "redirecting",
        "verifying",
        "safelink",
        "too early",
        "almost ready",
        "your link is almost ready"
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
        decodeTpxRedirect(url)?.let { return it }

        val query = url.substringAfter('?', "")
        if (query.isBlank()) return null
        val candidateKeys = setOf("url", "safe", "shortid", "link", "target", "dest", "go")
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=').lowercase()
            if (key !in candidateKeys) continue
            val raw = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }
                .getOrDefault(pair.substringAfter('=', "")).trim()
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                return raw
            }
            decodeBase64Url(raw)?.let { return it }
        }
        return null
    }

    fun decodeTpxRedirect(url: String): String? {
        if (!url.contains("main2.php", ignoreCase = true) &&
            !url.contains("links.tpxsub.com", ignoreCase = true)
        ) {
            return null
        }
        val rawParam = Regex("[?&]url=([^&#]+)").find(url)?.groupValues?.get(1) ?: return null
        val encoded = runCatching { URLDecoder.decode(rawParam, "UTF-8") }.getOrDefault(rawParam).trim()
        if (encoded.length < 16 || encoded.length % 2 != 0) return null

        val sb = StringBuilder(encoded.length / 2)
        for (i in 0 until encoded.length / 2) {
            val pair = encoded.substring(i * 2, i * 2 + 2)
            val rev = "${pair[1]}${pair[0]}"
            val num = rev.toIntOrNull(36) ?: return null
            val ch = num - TPX_REDIRECT_KEY[i % TPX_REDIRECT_KEY.length].code
            if (ch < 32 || ch > 126) return null
            sb.append(ch.toChar())
        }
        val out = sb.toString()
        return if (out.startsWith("http://") || out.startsWith("https://")) out else null
    }

    private fun decodeBase64Url(raw: String): String? {
        if (raw.length < 12) return null
        return runCatching {
            val normalized = raw.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val decoded = String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8).trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
        }.getOrNull()
    }

    fun detectHumanChallenge(html: String): HumanChallengeKind? {
        val lower = html.lowercase()
        return when {
            lower.contains("cf-turnstile") ||
                lower.contains("challenges.cloudflare.com/turnstile") -> HumanChallengeKind.CLOUDFLARE_TURNSTILE
            lower.contains("/cdn-cgi/challenge-platform/") ||
                lower.contains("cf-chl-") ||
                lower.contains("<title>just a moment") -> HumanChallengeKind.CLOUDFLARE_INTERSTITIAL
            lower.contains("g-recaptcha") ||
                lower.contains("recaptcha/api.js") ||
                lower.contains("please click on below captcha box") -> HumanChallengeKind.RECAPTCHA
            lower.contains("h-captcha") ||
                lower.contains("hcaptcha.com/1/api.js") -> HumanChallengeKind.HCAPTCHA
            lower.contains("adcopy_challenge") -> HumanChallengeKind.ADCOPY
            else -> null
        }
    }

    fun classify(url: String, html: String): PageClassification {
        unwrapTokenUrl(url)?.let { unwrapped ->
            if (unwrapped != url) {
                return PageClassification(
                    family = AdProviderFamily.TOKEN_WRAPPER,
                    unwrappedTokenUrl = unwrapped
                )
            }
        }

        if (DestinationValidator.isDirectMediaOrCloudHost(url)) {
            return PageClassification(family = AdProviderFamily.MEDIA_OR_CLOUD_HOST)
        }

        val challenge = detectHumanChallenge(html)
        if (challenge != null) {
            return PageClassification(
                family = AdProviderFamily.HUMAN_CHALLENGE,
                challengeKind = challenge
            )
        }

        val lower = html.lowercase()
        val tooEarly = lower.contains("too early")
        val doc = Jsoup.parse(html, url)

        // Inspect forms for WP Safelink or AdLinkFly structures
        var safelinkAction: String? = null
        var safelinkMethod = "GET"
        var safelinkFields: Map<String, String> = emptyMap()
        var safelinkDirect: String? = null
        var safelinkDelay = 0

        var adLinkFlyAction: String? = null
        var adLinkFlyFields: Map<String, String> = emptyMap()

        for (form in doc.select("form")) {
            val inputs = LinkedHashMap<String, String>()
            for (inp in form.select("input[name]")) {
                val type = inp.attr("type").lowercase()
                if (type in setOf("submit", "button", "image", "reset", "file")) continue
                inputs[inp.attr("name")] = inp.attr("value")
            }

            val action = resolveUrl(form.attr("action").ifBlank { url }, url) ?: url
            val method = form.attr("method").ifBlank { "get" }.uppercase()
            val formId = form.attr("id")
            val formName = form.attr("name")

            val newWpSafe = inputs["newwpsafelink"]
            if (!newWpSafe.isNullOrBlank()) {
                val decoded = decodeJsonMap(newWpSafe)
                if (decoded != null) {
                    safelinkDelay = (decoded["delay"] as? Number)?.toInt() ?: 0
                    safelinkDirect = listOf("linkr", "second_safelink_url", "newsafelink")
                        .firstNotNullOfOrNull { key -> (decoded[key] as? String)?.takeIf { it.startsWith("http") } }
                }
                safelinkAction = action
                safelinkMethod = method
                safelinkFields = inputs
            } else if (formName == "tp" || inputs.containsKey("go")) {
                safelinkAction = action
                safelinkMethod = method
                safelinkFields = inputs
            }

            if (inputs.containsKey("ad_form_data") ||
                formId == "go-link" ||
                action.trimEnd('/').endsWith("/links/go")
            ) {
                adLinkFlyAction = action
                adLinkFlyFields = inputs
            }
        }

        if (adLinkFlyAction != null) {
            return PageClassification(
                family = AdProviderFamily.ADLINKFLY,
                adLinkFlyActionUrl = adLinkFlyAction,
                adLinkFlyFields = adLinkFlyFields,
                isServerTimerTooEarly = tooEarly
            )
        }

        if (safelinkAction != null || safelinkDirect != null) {
            return PageClassification(
                family = AdProviderFamily.WP_SAFELINK,
                safelinkDelaySeconds = safelinkDelay,
                safelinkFormAction = safelinkAction,
                safelinkFormMethod = safelinkMethod,
                safelinkFields = safelinkFields,
                safelinkDirectTarget = safelinkDirect,
                isServerTimerTooEarly = tooEarly
            )
        }

        val metaRefresh = doc.selectFirst("meta[http-equiv~=refresh i]")
            ?.attr("content")
            ?.let { Regex("url\\s*=\\s*['\"]?([^'\";\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.let { resolveUrl(it, url) }

        val jsRedirect = Regex(
            "(?:window|document|top)\\.location(?:\\.href)?\\s*=\\s*['\"](https?://[^'\"]+)['\"]",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.replace("\\/", "/")

        val continueLink = doc.select("#nextPage a, a.get-link, #btn-get-link, #get-link-btn, a[href]")
            .firstOrNull { a ->
                val href = a.attr("href").trim()
                if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:", true)) return@firstOrNull false
                val idClass = "${a.id()} ${a.className()}".lowercase()
                val text = a.text().trim().lowercase()
                idClass.contains("get-link") || idClass.contains("tp-snp2") || text in continueTexts
            }
            ?.let { resolveUrl(it.attr("href"), url) }

        val isInterstitial = tooEarly ||
            interstitialMarkers.any { lower.contains(it) } ||
            metaRefresh != null ||
            jsRedirect != null ||
            continueLink != null

        return PageClassification(
            family = if (isInterstitial) AdProviderFamily.COUNTDOWN_INTERSTITIAL else AdProviderFamily.GENERIC_HTML,
            continueLinkUrl = continueLink,
            metaRefreshUrl = metaRefresh,
            jsRedirectUrl = jsRedirect,
            isServerTimerTooEarly = tooEarly
        )
    }

    private fun decodeJsonMap(b64: String): Map<String, Any?>? {
        return runCatching {
            val normalized = b64.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val text = String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
            val obj = org.json.JSONObject(text)
            obj.keys().asSequence().associateWith { obj.opt(it) }
        }.getOrNull()
    }

    fun resolveUrl(raw: String?, baseUrl: String): String? {
        val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate.startsWith("javascript:", true) ||
            candidate.startsWith("mailto:", true) ||
            candidate.startsWith("#")
        ) {
            return null
        }
        return runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrNull()
    }
}
