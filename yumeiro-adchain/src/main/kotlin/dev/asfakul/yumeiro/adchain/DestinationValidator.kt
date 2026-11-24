package dev.asfakul.yumeiro.adchain

import java.net.URI
import java.net.URLDecoder

sealed class ValidatedDestination {
    data class DirectMedia(val url: String, val isHls: Boolean, val isDash: Boolean) : ValidatedDestination()
    data class CloudFileHost(val hostName: String, val normalizedUrl: String, val originalUrl: String) : ValidatedDestination()
    data class WebEmbedPage(val url: String) : ValidatedDestination()
    data class Invalid(val url: String, val reason: String) : ValidatedDestination()
}

/**
 * Validates destination URLs before passing them to Yumeiro's built-in player (`StreamResolver` /
 * `OnlinePlayback`) or download manager (`WebDownloads`). Strips tracking wrappers, rejects unsafe
 * schemes and expired/parked domains, and normalizes cloud file-host endpoints.
 */
object DestinationValidator {

    private val mediaExts = listOf(".m3u8", ".m3u", ".mpd", ".mp4", ".mkv", ".webm", ".mov", ".avi")

    private val cloudHostDomains = mapOf(
        "pixeldrain" to listOf("pixeldrain.com", "pixeldrain.net", "pixeldrain.dev", "pixeldra.in"),
        "gofile" to listOf("gofile.io"),
        "streamtape" to listOf("streamtape.com", "streamtape.net", "streamta.pe"),
        "krakenfiles" to listOf("krakenfiles.com"),
        "mediafire" to listOf("mediafire.com"),
        "mirrored" to listOf("mirrored.to"),
        "gdrive" to listOf("drive.google.com", "drive.usercontent.google.com"),
        "dropbox" to listOf("dropbox.com"),
        "onedrive" to listOf("1drv.ms", "onedrive.live.com")
    )

    private val parkedOrDeadDomains = setOf(
        "asurasever.cloud",
        "hindidubbedanime.com"
    )

    private val shortenerGateDomains = setOf(
        "shrinkme.io", "shrinkme.click", "shrinkme.us", "shrinkme.site",
        "shrinkearn.com", "srnky.com", "tpi.li", "oii.la", "clksz.com",
        "themezon.net", "mrproblogger.com", "shortsync.epdexx.com",
        "hsalinks.in", "hsalinks.eu.org"
    )

    fun stripTrackingWrappers(url: String): String {
        var current = url.trim()
        repeat(3) {
            val host = AdFilterPolicy.hostOf(current) ?: return current
            val query = current.substringAfter('?', "")
            val unwrapped = when {
                (host == "google.com" || host.endsWith(".google.com")) && current.contains("/url?") ->
                    extractParam(query, "q") ?: extractParam(query, "url")
                host.endsWith("facebook.com") && current.contains("/l.php") ->
                    extractParam(query, "u")
                else -> null
            }
            if (unwrapped != null && unwrapped.startsWith("http")) {
                current = unwrapped
            } else {
                return current
            }
        }
        return current
    }

    private fun extractParam(query: String, name: String): String? {
        for (pair in query.split('&')) {
            if (pair.substringBefore('=').equals(name, ignoreCase = true)) {
                return runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
            }
        }
        return null
    }

    fun isDirectMediaOrCloudHost(url: String): Boolean {
        val clean = stripTrackingWrappers(url)
        val path = clean.substringBefore('?').substringBefore('#').lowercase()
        if (mediaExts.any { path.endsWith(it) }) return true
        val host = AdFilterPolicy.hostOf(clean) ?: return false
        return cloudHostDomains.values.flatten().any { domain -> host == domain || host.endsWith(".$domain") }
    }

    fun isShortenerGateHost(url: String): Boolean {
        val host = AdFilterPolicy.hostOf(url) ?: return false
        return shortenerGateDomains.any { d -> host == d || host.endsWith(".$d") }
    }

    fun normalizePixeldrain(url: String): String? {
        val m = Regex("https?://(?:www\\.)?pixeldrain\\.(?:com|net|dev|in)/(?:u|api/file)/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(url) ?: return null
        return "https://pixeldrain.com/api/file/${m.groupValues[1]}"
    }

    fun validate(rawUrl: String): ValidatedDestination {
        val clean = stripTrackingWrappers(rawUrl)
        val scheme = clean.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") {
            return ValidatedDestination.Invalid(clean, "unsupported_scheme:$scheme")
        }
        val host = AdFilterPolicy.hostOf(clean) ?: return ValidatedDestination.Invalid(clean, "missing_host")

        if (AdFilterPolicy.isAdHost(host)) {
            return ValidatedDestination.Invalid(clean, "ad_network_domain")
        }
        if (parkedOrDeadDomains.any { d -> host == d || host.endsWith(".$d") }) {
            return ValidatedDestination.Invalid(clean, "parked_or_disavowed_domain")
        }
        if (isShortenerGateHost(clean)) {
            return ValidatedDestination.Invalid(clean, "unresolved_shortener_gate")
        }

        val path = clean.substringBefore('?').substringBefore('#').lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) {
            return ValidatedDestination.DirectMedia(clean, isHls = true, isDash = false)
        }
        if (path.endsWith(".mpd")) {
            return ValidatedDestination.DirectMedia(clean, isHls = false, isDash = true)
        }
        if (mediaExts.any { path.endsWith(it) }) {
            return ValidatedDestination.DirectMedia(clean, isHls = false, isDash = false)
        }

        normalizePixeldrain(clean)?.let { pd ->
            return ValidatedDestination.CloudFileHost("Pixeldrain", pd, clean)
        }

        for ((name, domains) in cloudHostDomains) {
            if (domains.any { d -> host == d || host.endsWith(".$d") }) {
                return ValidatedDestination.CloudFileHost(name, clean, clean)
            }
        }

        return ValidatedDestination.WebEmbedPage(clean)
    }
}
