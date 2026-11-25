package com.animeext.downloader.resolver

import java.net.URI
import java.net.URLDecoder

object DownloadAndSchemeGuard {

    enum class UrlSafety {
        SAFE_HTTP_OR_HTTPS,
        BLOCKED_UNSAFE_SCHEME,
        BLOCKED_EXECUTABLE_OR_APK
    }

    private val blockedExtensions = listOf(
        ".apk", ".xapk", ".apkm", ".aab", ".exe", ".msi", ".bat", ".cmd",
        ".sh", ".scr", ".vbs", ".ps1", ".jar", ".dmg", ".pkg", ".deb"
    )

    private val blockedMimeTypes = setOf(
        "application/vnd.android.package-archive",
        "application/x-msdownload",
        "application/x-dosexec",
        "application/x-executable",
        "application/x-sh",
        "application/x-bat"
    )

    private val safeVideoExtensions = listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".m3u8", ".m3u", ".mpd")
    private val safeArchiveExtensions = listOf(".zip", ".rar", ".7z")

    private val cloudDownloadHosts = listOf(
        "pixeldrain.com", "pixeldrain.net", "pixeldrain.dev", "pixeldra.in",
        "gofile.io",
        "streamtape.com", "streamtape.net", "streamta.pe",
        "krakenfiles.com",
        "mediafire.com",
        "mirrored.to",
        "drive.google.com", "drive.usercontent.google.com"
    )

    fun inspectUrl(rawUrl: String): UrlSafety {
        val trimmed = rawUrl.trim()
        val scheme = trimmed.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlSafety.BLOCKED_UNSAFE_SCHEME
        }

        val path = trimmed.substringBefore('?').substringBefore('#').lowercase()
        if (blockedExtensions.any { path.endsWith(it) }) {
            return UrlSafety.BLOCKED_EXECUTABLE_OR_APK
        }

        val queryDecoded = runCatching {
            URLDecoder.decode(trimmed.substringAfter('?', ""), "UTF-8").lowercase()
        }.getOrDefault("")
        if (blockedExtensions.any { ext ->
                queryDecoded.contains("filename=$ext") ||
                    Regex("=[^&]+\\${ext}(?:&|$)").containsMatchIn(queryDecoded)
            }
        ) {
            return UrlSafety.BLOCKED_EXECUTABLE_OR_APK
        }

        return UrlSafety.SAFE_HTTP_OR_HTTPS
    }

    fun unwrapIntentUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("intent://", ignoreCase = true)) return null
        val bodyAndMeta = trimmed.substring("intent://".length)
        val metaIdx = bodyAndMeta.indexOf("#Intent;", ignoreCase = true)
        val hostAndPath = if (metaIdx >= 0) bodyAndMeta.substring(0, metaIdx) else bodyAndMeta
        val meta = if (metaIdx >= 0) bodyAndMeta.substring(metaIdx + "#Intent;".length) else ""

        var scheme: String? = null
        var fallbackUrl: String? = null
        for (part in meta.split(';')) {
            when {
                part.startsWith("scheme=", ignoreCase = true) ->
                    scheme = part.substringAfter('=').trim().lowercase()
                part.startsWith("S.browser_fallback_url=", ignoreCase = true) ->
                    fallbackUrl = runCatching {
                        URLDecoder.decode(part.substringAfter('='), "UTF-8")
                    }.getOrNull()?.trim()
            }
        }

        if (scheme != null && scheme != "http" && scheme != "https") {
            if (!fallbackUrl.isNullOrBlank() && inspectUrl(fallbackUrl) == UrlSafety.SAFE_HTTP_OR_HTTPS) {
                return fallbackUrl
            }
            return null
        }

        if (!fallbackUrl.isNullOrBlank() && hostAndPath.isBlank() &&
            inspectUrl(fallbackUrl) == UrlSafety.SAFE_HTTP_OR_HTTPS
        ) {
            return fallbackUrl
        }

        val effectiveScheme = scheme ?: "https"
        if (hostAndPath.isBlank()) return null
        val reconstructed = "$effectiveScheme://$hostAndPath"
        return reconstructed.takeIf { inspectUrl(it) == UrlSafety.SAFE_HTTP_OR_HTTPS }
    }

    fun isBlockedMimeType(mimeType: String?): Boolean {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return normalized in blockedMimeTypes || normalized.contains("android.package-archive")
    }

    fun isDirectVideoUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return safeVideoExtensions.any { path.endsWith(it) }
    }

    fun isArchiveUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return safeArchiveExtensions.any { path.endsWith(it) }
    }

    fun isDirectDownloadOrMediaTarget(url: String, mimeType: String? = null): Boolean {
        if (inspectUrl(url) != UrlSafety.SAFE_HTTP_OR_HTTPS) return false
        if (isBlockedMimeType(mimeType)) return false
        if (isDirectVideoUrl(url) || isArchiveUrl(url)) return true
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.startsWith("video/") || mime == "application/zip" || mime == "application/x-zip-compressed" ||
            mime == "application/vnd.apple.mpegurl" || mime == "application/x-mpegurl"
        ) {
            return true
        }
        return false
    }

    fun isKnownCloudFileHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase()?.trimEnd('.') }.getOrNull() ?: return false
        return cloudDownloadHosts.any { d -> host == d || host.endsWith(".$d") }
    }
}
