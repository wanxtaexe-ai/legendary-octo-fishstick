package com.animeext.cloudstream

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object WordPressStreamResolvers {

    private const val TPX_REDIRECT_KEY = "7eea12b58ee6bd4833706e6d5a9468a865315209"
    private const val HSA_AES_KEY = "kiemtienmua911ca"

    private val deadHosts = listOf(
        "asurasever.cloud",
        "hindidubbedanime.com",
        "srnky.com",
        "shrinkearn.com"
    )

    private val mirroredSupportedHosts = listOf(
        "pixeldrain",
        "gofile",
        "streamtape",
        "krakenfiles",
        "mediafire",
        "usersdrive",
        "dood",
        "vidmoly",
        "filemoon",
        "mixdrop"
    )

    fun isKnownDeadUrl(url: String): Boolean {
        val lower = url.lowercase()
        return deadHosts.any { lower.contains(it) }
    }

    /**
     * Decodes `https://links.tpxsub.com/redirect/main2.php?url=<encoded>` offline.
     * Each 2-char chunk is reversed base-36 minus the repeating 40-char key byte.
     */
    fun decodeTpxRedirectUrl(url: String): String? {
        if (!url.contains("links.tpxsub.com", ignoreCase = true) && !url.contains("main2.php", ignoreCase = true)) {
            return null
        }
        val rawParam = Regex("[?&]url=([^&#]+)").find(url)?.groupValues?.get(1) ?: return null
        val encoded = runCatching { URLDecoder.decode(rawParam, "UTF-8") }.getOrDefault(rawParam).trim()
        if (encoded.length < 16 || encoded.length % 2 != 0) return null

        val sb = StringBuilder(encoded.length / 2)
        for (idx in 0 until encoded.length / 2) {
            val pair = encoded.substring(idx * 2, idx * 2 + 2)
            val reversed = "${pair[1]}${pair[0]}"
            val num = reversed.toIntOrNull(36) ?: return null
            val ch = (num - TPX_REDIRECT_KEY[idx % TPX_REDIRECT_KEY.length].code)
            if (ch < 32 || ch > 126) return null
            sb.append(ch.toChar())
        }
        val decoded = sb.toString()
        return if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
    }

    /**
     * Extracts base64-encoded `?id=<b64>` destination URLs (e.g. animedekho/dl2.php).
     */
    fun decodeBase64QueryUrl(url: String): String? {
        val b64 = Regex("[?&](?:id|url)=([A-Za-z0-9+/=_-]{12,})").find(url)?.groupValues?.get(1) ?: return null
        return runCatching {
            val normalized = b64.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
        }.getOrNull()
    }

    fun extractHsaStreamId(url: String): String? {
        if (!url.contains("hsastream.", ignoreCase = true)) return null
        Regex("#([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("/e/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("[?&]id=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun deriveHsaStreamIv(videoId: String): ByteArray {
        val o = "https:"
        val d = "$o//"
        val t = o.length * d.length // 48
        val sb = StringBuilder()
        for (z in 1..9) {
            sb.append((z + t).toChar())
        }
        val h = 3 * (videoId.firstOrNull()?.code ?: 0)
        val le = 111 + o.length
        val l = le + 4
        val x = o[1].code
        val te = x - 2
        sb.append(t.toChar())
            .append(111.toChar())
            .append(h.toChar())
            .append(le.toChar())
            .append(l.toChar())
            .append(x.toChar())
            .append(te.toChar())
        return sb.toString().toByteArray(StandardCharsets.ISO_8859_1).copyOf(16)
    }

    private fun decryptHsaStreamHex(hexData: String, videoId: String): String? {
        val cleaned = hexData.replace(Regex("[^0-9a-fA-F]"), "")
        if (cleaned.length < 32 || cleaned.length % 2 != 0) return null
        val bytes = ByteArray(cleaned.length / 2)
        for (i in bytes.indices) {
            bytes[i] = cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(HSA_AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(deriveHsaStreamIv(videoId))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(bytes)
        return String(decrypted, StandardCharsets.UTF_8).replace("\\/", "/")
    }

    suspend fun resolveHsaStream(
        embedUrl: String,
        sourceName: String,
        refererHost: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = extractHsaStreamId(embedUrl) ?: return false
        val parsed = runCatching { URI(embedUrl) }.getOrNull()
        val host = parsed?.host ?: "hsastream.com"
        val scheme = parsed?.scheme ?: "https"
        val apiUrl = "$scheme://$host/api/v1/video?id=$videoId&w=360&h=800&r=$refererHost"
        val referer = "$scheme://$host/"

        val hexText = runCatching {
            app.get(apiUrl, referer = referer).text.trim()
        }.getOrNull().orEmpty()
        if (hexText.isBlank()) return false

        val decrypted = runCatching { decryptHsaStreamHex(hexText, videoId) }.getOrNull() ?: return false
        var emitted = false

        val fields = listOf("source" to "Google", "cfNative" to "Cloudflare", "cf" to "CF")
        for ((key, label) in fields) {
            val match = Regex("\"$key\"\\s*:\\s*\"(https?://[^\"]+)\"").find(decrypted) ?: continue
            val streamUrl = match.groupValues[1]
            if (key == "cf" && streamUrl.endsWith(".txt", ignoreCase = true)) continue
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = "$sourceName HSA ($label)",
                    url = streamUrl,
                    type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            emitted = true
        }

        Regex("\"hlsVideoTiktok\"\\s*:\\s*\"([^\"]+)\"").find(decrypted)?.groupValues?.get(1)?.let { raw ->
            val streamUrl = if (raw.startsWith("/")) "$scheme://$host$raw" else raw
            if (streamUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = sourceName,
                        name = "$sourceName HSA (HLS)",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted = true
            }
        }

        return emitted
    }

    /**
     * Resolves `mirrored.to/files/<id>/...` into native CloudStream hosts
     * (Pixeldrain, Gofile, StreamTape, KrakenFiles, MediaFire, etc.) via HTTP.
     */
    suspend fun resolveMirroredTo(
        mirroredUrl: String,
        sourceName: String,
        qualityLabel: String,
        qualityValue: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileId = Regex("mirrored\\.to/files/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(mirroredUrl)?.groupValues?.get(1) ?: return false
        val basePageUrl = "https://www.mirrored.to/files/$fileId/"

        val firstHtml = runCatching { app.get(basePageUrl).text }.getOrNull().orEmpty()
        if (firstHtml.isBlank()) return false

        val firstDoc = Jsoup.parse(firstHtml, basePageUrl)
        val dlListPath = firstDoc.selectFirst("a[href*=\"hash=\"]")?.attr("href")
            ?: firstDoc.selectFirst("a[href*=\"dl=0\"]")?.attr("href")
        val mirrorsUrl = WordPressSemanticParser.resolveUrl(dlListPath, basePageUrl) ?: basePageUrl

        val mirrorsHtml = if (mirrorsUrl == basePageUrl) {
            firstHtml
        } else {
            runCatching { app.get(mirrorsUrl, referer = basePageUrl).text }.getOrNull().orEmpty()
        }
        if (mirrorsHtml.isBlank()) return false

        val mirrorsDoc = Jsoup.parse(mirrorsHtml, mirrorsUrl)
        val getLinkUrls = mutableListOf<Pair<Int, String>>()
        for (row in mirrorsDoc.select("tr")) {
            val rowHint = "${row.text()} ${row.html()}".lowercase()
            val link = row.selectFirst("a[href*=/getlink/]") ?: continue
            val resolved = WordPressSemanticParser.resolveUrl(link.attr("href"), mirrorsUrl) ?: continue
            val hostPriority = mirroredSupportedHosts.indexOfFirst { rowHint.contains(it) }
            if (hostPriority >= 0) {
                getLinkUrls.add(hostPriority to resolved)
            }
        }
        getLinkUrls.sortBy { it.first }

        // Fallback if table rows lacked host labels
        if (getLinkUrls.isEmpty()) {
            for (link in mirrorsDoc.select("a[href*=/getlink/]")) {
                val resolved = WordPressSemanticParser.resolveUrl(link.attr("href"), mirrorsUrl) ?: continue
                getLinkUrls.add(99 to resolved)
            }
        }

        var emitted = false
        for ((_, getLinkUrl) in getLinkUrls.take(6)) {
            val finalPage = runCatching { app.get(getLinkUrl, referer = mirrorsUrl).text }.getOrNull().orEmpty()
            if (finalPage.isBlank()) continue
            val finalDoc = Jsoup.parse(finalPage, getLinkUrl)

            val candidateUrls = linkedSetOf<String>()
            finalDoc.select("code").map { it.text().trim() }.filter { it.startsWith("http") }.forEach {
                candidateUrls.add(it)
            }
            finalDoc.select("a[href]").mapNotNull { WordPressSemanticParser.resolveUrl(it.attr("href"), getLinkUrl) }
                .filter { u ->
                    !u.contains("mirrored.to", ignoreCase = true) &&
                        mirroredSupportedHosts.any { u.lowercase().contains(it) }
                }
                .forEach { candidateUrls.add(it) }

            for (target in candidateUrls) {
                val pd = WordPressSemanticParser.normalizePixeldrainUrl(target)
                if (pd != null) {
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name = "$sourceName Pixeldrain ($qualityLabel)",
                            url = pd,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = target
                            this.quality = qualityValue
                        }
                    )
                    emitted = true
                } else if (loadExtractor(target, getLinkUrl, subtitleCallback, callback)) {
                    emitted = true
                }
            }
        }
        return emitted
    }

    /**
     * Resolves TpXAnime intermediate player pages (`beta.tpxsub.com/server3/player.php`,
     * `tpxbeta.xyz/server3/player.html`).
     */
    suspend fun resolveTpxPlayerPage(
        playerUrl: String,
        sourceName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = runCatching { app.get(playerUrl, referer = referer) }.getOrNull() ?: return false
        val html = resp.text
        if (html.isBlank()) return false

        var emitted = false
        val (media, iframes) = WordPressSemanticParser.extractEmbeddedMedia(html, resp.url)
        for (m in media) {
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = "$sourceName Embedded",
                    url = m.url,
                    type = if (m.isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.referer = resp.url
                    this.quality = m.qualityValue
                }
            )
            emitted = true
        }
        for (frameUrl in iframes) {
            if (extractHsaStreamId(frameUrl) != null) {
                val host = runCatching { URI(resp.url).host }.getOrNull() ?: "tpxsub.com"
                if (resolveHsaStream(frameUrl, sourceName, host, callback)) emitted = true
            } else if (loadExtractor(frameUrl, resp.url, subtitleCallback, callback)) {
                emitted = true
            }
        }

        val doc = Jsoup.parse(html, resp.url)
        for (a in doc.select("a[href]")) {
            val href = WordPressSemanticParser.resolveUrl(a.attr("href"), resp.url) ?: continue
            val unwrapped = decodeBase64QueryUrl(href) ?: href
            if (unwrapped.contains("openx.xyz", ignoreCase = true) ||
                unwrapped.contains("tpxsub.com", ignoreCase = true)
            ) {
                continue
            }
            val pd = WordPressSemanticParser.normalizePixeldrainUrl(unwrapped)
            if (pd != null) {
                callback.invoke(
                    newExtractorLink(
                        source = sourceName,
                        name = "$sourceName Pixeldrain",
                        url = pd,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = unwrapped
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted = true
            } else if (loadExtractor(unwrapped, resp.url, subtitleCallback, callback)) {
                emitted = true
            }
        }
        return emitted
    }
}
