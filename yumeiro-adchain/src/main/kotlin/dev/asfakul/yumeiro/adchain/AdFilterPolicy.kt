package dev.asfakul.yumeiro.adchain

import java.net.URI

/**
 * Context-aware filtering policy for Yumeiro WebViews during ad-chain traversal.
 *
 * Key invariant: never block verification/CAPTCHA scripts (`challenges.cloudflare.com`,
 * `recaptcha.net`, `hcaptcha.com`, `gstatic.com`) or first-party chain APIs (`/links/go`,
 * safelink form submissions), because breaking those leaves users stuck on infinite verification
 * loops.
 */
object AdFilterPolicy {

    enum class SubresourceDecision {
        ALLOW,
        BLOCK_AD
    }

    enum class NavigationDecision {
        ALLOW,
        BLOCK_POPUP_OR_AD,
        BLOCK_UNSAFE_SCHEME
    }

    private val trustedVerificationDomains = setOf(
        "challenges.cloudflare.com",
        "cloudflare.com",
        "recaptcha.net",
        "www.google.com",
        "gstatic.com",
        "hcaptcha.com",
        "newassets.hcaptcha.com",
        "ddos-guard.net"
    )

    private val adHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "imasdk.googleapis.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerclick.com",
        "onclickads.net",
        "adsterra.com",
        "adsterratools.com",
        "hilltopads.net",
        "clickadu.com",
        "admaven.com",
        "ad-maven.com",
        "monetag.com",
        "galaksion.com",
        "exoclick.com",
        "exosrv.com",
        "juicyads.com",
        "trafficjunky.net",
        "trafficstars.com",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "revcontent.com",
        "richpush.co",
        "zeydoo.com",
        "tryedesign.com",
        "rvpaste.com"
    )

    private val videoAdPathRegex = Regex(
        """[/?&._-](vast|vpaid|vmap|preroll|pre-roll|midroll|adtag|ad_tag|popunder|pop-under)([/?&=._-]|$)""",
        RegexOption.IGNORE_CASE
    )

    fun hostOf(url: String?): String? = runCatching {
        URI(url?.trim() ?: return null).host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun isTrustedVerificationHost(host: String): Boolean {
        return generateSequence(host.lowercase()) { h ->
            h.substringAfter('.', "").takeIf { it.contains('.') }
        }.any { it in trustedVerificationDomains }
    }

    fun isAdHost(host: String): Boolean {
        return generateSequence(host.lowercase()) { h ->
            h.substringAfter('.', "").takeIf { it.contains('.') }
        }.any { it in adHosts }
    }

    fun decideSubresource(url: String, pageHost: String?): SubresourceDecision {
        val scheme = url.trim().substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") return SubresourceDecision.ALLOW
        val host = hostOf(url) ?: return SubresourceDecision.ALLOW

        // Never break CAPTCHA or Turnstile verification scripts
        if (isTrustedVerificationHost(host)) return SubresourceDecision.ALLOW

        // Never block same-site APIs (e.g. /links/go or safelink endpoints)
        if (pageHost != null && (host == pageHost || host.endsWith(".$pageHost"))) {
            return if (videoAdPathRegex.containsMatchIn(url)) SubresourceDecision.BLOCK_AD else SubresourceDecision.ALLOW
        }

        if (isAdHost(host) || videoAdPathRegex.containsMatchIn(url)) {
            return SubresourceDecision.BLOCK_AD
        }
        return SubresourceDecision.ALLOW
    }

    fun decideMainFrameNavigation(
        currentUrl: String?,
        targetUrl: String,
        hasUserGesture: Boolean,
        isRedirect: Boolean
    ): NavigationDecision {
        val scheme = targetUrl.trim().substringBefore(':').lowercase()
        if (scheme == "about" || scheme == "blob") return NavigationDecision.ALLOW
        if (scheme != "http" && scheme != "https") return NavigationDecision.BLOCK_UNSAFE_SCHEME

        val targetHost = hostOf(targetUrl) ?: return NavigationDecision.BLOCK_UNSAFE_SCHEME
        if (isTrustedVerificationHost(targetHost)) return NavigationDecision.ALLOW
        if (isAdHost(targetHost)) return NavigationDecision.BLOCK_POPUP_OR_AD

        val currentHost = hostOf(currentUrl)
        if (currentHost != null && (targetHost == currentHost || targetHost.endsWith(".$currentHost"))) {
            return NavigationDecision.ALLOW
        }

        // Cross-origin navigation is allowed when triggered by server 3xx redirect, known chain
        // destination, or explicit user gesture (after ad hosts are filtered).
        if (isRedirect || hasUserGesture || DestinationValidator.isDirectMediaOrCloudHost(targetUrl)) {
            return NavigationDecision.ALLOW
        }

        // Block unsolicited client-side pop-under jumps to unknown domains
        return NavigationDecision.BLOCK_POPUP_OR_AD
    }
}
