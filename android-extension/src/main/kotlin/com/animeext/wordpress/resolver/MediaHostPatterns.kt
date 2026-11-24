package com.animeext.wordpress.resolver

import java.net.URI

data class MediaMatch(
    val hostType: String,
    val originalUrl: String,
    val streamUrl: String,
    val mimeType: String,
    val isDirectStream: Boolean
)

object MediaHostPatterns {
    private val adHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "facebook.net",
        "adservice.google.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "onclickads.net",
        "adsterra.com",
        "highperformanceformat.com",
        "effectiveratecpm.com",
        "profitablegate.com",
        "hilltopads.net",
        "admaven.com",
        "monetag.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "mc.yandex.ru"
    )

    private val externalHijackSchemes = listOf(
        "intent:",
        "market:",
        "tg:",
        "whatsapp:",
        "upi:",
        "tel:"
    )

    private val nonMediaExtRegex =
        Regex("\\.(css|js|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|eot|ts|vtt|srt)(\\?.*)?$", RegexOption.IGNORE_CASE)
    private val directVideoExtRegex =
        Regex("\\.(mp4|m3u8|mkv|webm)(\\?.*)?$", RegexOption.IGNORE_CASE)
    private val pixeldrainRegex =
        Regex("^https?://(?:www\\.)?pixeldrain\\.(?:com|dev)/(?:u|api/file)/([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val gofileRegex =
        Regex("^https?://(?:[a-z0-9-]+\\.)?gofile\\.io/(?:d|download)/([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val asuraDirectRegex =
        Regex("^https?://(?:www\\.)?asurasever\\.cloud/.+\\.(mp4|mkv|webm|m3u8)(\\?.*)?$", RegexOption.IGNORE_CASE)

    fun isExternalHijackScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.trim().lowercase()
        return externalHijackSchemes.any { lower.startsWith(it) }
    }

    fun getAdStubResponse(url: String?): Pair<String, String> {
        val lower = url.orEmpty().lowercase()
        return when {
            Regex("\\.js(\\?|$)").containsMatchIn(lower) ->
                "application/javascript" to "/* ad-stub */ window.adsbygoogle = window.adsbygoogle || [];"
            Regex("\\.css(\\?|$)").containsMatchIn(lower) ->
                "text/css" to "/* ad-stub */"
            else ->
                "text/html" to "<!doctype html><html><body></body></html>"
        }
    }

    fun isAdHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return adHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isAdOrNoiseUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return true
        if (nonMediaExtRegex.containsMatchIn(lower)) return true
        return isAdHost(url)
    }

    fun matchDirectMediaHost(url: String?): MediaMatch? {
        if (url.isNullOrBlank() || isAdOrNoiseUrl(url)) return null

        pixeldrainRegex.find(url)?.let { match ->
            val fileId = match.groupValues[1]
            return MediaMatch(
                hostType = "pixeldrain",
                originalUrl = url,
                streamUrl = "https://pixeldrain.com/api/file/$fileId",
                mimeType = "video/mp4",
                isDirectStream = true
            )
        }

        gofileRegex.find(url)?.let {
            val isDirectFile = url.contains("/download/", ignoreCase = true) &&
                directVideoExtRegex.containsMatchIn(url)
            return MediaMatch(
                hostType = "gofile",
                originalUrl = url,
                streamUrl = url,
                mimeType = if (isDirectFile) inferMimeType(url) else "text/html",
                isDirectStream = isDirectFile
            )
        }

        if (asuraDirectRegex.containsMatchIn(url) || directVideoExtRegex.containsMatchIn(url)) {
            return MediaMatch(
                hostType = "direct",
                originalUrl = url,
                streamUrl = url,
                mimeType = inferMimeType(url),
                isDirectStream = true
            )
        }

        return null
    }

    fun inferMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            Regex("\\.m3u8(\\?|$)").containsMatchIn(lower) -> "application/x-mpegURL"
            Regex("\\.mp4(\\?|$)").containsMatchIn(lower) -> "video/mp4"
            Regex("\\.mkv(\\?|$)").containsMatchIn(lower) -> "video/x-matroska"
            Regex("\\.webm(\\?|$)").containsMatchIn(lower) -> "video/webm"
            else -> "video/*"
        }
    }
}
