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
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

    private val vidstackP2pHosts = listOf(
        "hsastream.",
        "rpmhub.",
        "rpmstream.",
        "rpmshare.",
        "upns.",
        "uns.bio",
        "p2pplay.",
        "vidstack.",
        "strp2p."
    )

    private val abyssHosts = listOf(
        "abyssplayer.",
        "abysssplayer.",
        "abysscdn.",
        "playhydrax.",
        "hydraxcdn.",
        "embedplayabyss.",
        "abyss.to",
        "short.icu"
    )

    fun isVidstackP2pHost(url: String): Boolean {
        val lower = url.lowercase()
        return vidstackP2pHosts.any { lower.contains(it) }
    }

    fun isAbyssHost(url: String): Boolean {
        val lower = url.lowercase()
        return abyssHosts.any { lower.contains(it) }
    }

    fun extractHsaStreamId(url: String): String? {
        if (!isVidstackP2pHost(url)) return null
        Regex("#([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("/e/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("[?&](?:id|v|t)=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    fun normalizeTpxPlayerUrl(url: String): String {
        if (!url.contains("tpxbeta.", ignoreCase = true)) return url
        val vid = Regex("[?&]v=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: Regex("#([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: return url
        return "https://beta.tpxsub.com/server3/player.php?v=$vid"
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

    private fun decryptAesCbcWithIv(bytes: ByteArray, ivBytes: ByteArray): String? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val keySpec = SecretKeySpec(HSA_AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(ivBytes.copyOf(16))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(bytes)
            String(decrypted, StandardCharsets.UTF_8).replace("\\/", "/")
        }.getOrNull()
    }

    private fun decodeP2pPayloadCandidates(rawBody: String, videoId: String): List<String> {
        val trimmed = rawBody.trim()
        if (trimmed.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        if (trimmed.startsWith("{")) {
            out.add(trimmed.replace("\\/", "/"))
            return out
        }
        val cleaned = trimmed.replace(Regex("[^0-9a-fA-F]"), "")
        if (cleaned.length < 32 || cleaned.length % 2 != 0) return out
        val bytes = ByteArray(cleaned.length / 2)
        for (i in bytes.indices) {
            bytes[i] = cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val ivs = listOf(
            deriveHsaStreamIv(videoId),
            "1234567890oiuytr".toByteArray(StandardCharsets.UTF_8),
            "0123456789abcdef".toByteArray(StandardCharsets.UTF_8)
        )
        for (iv in ivs) {
            val dec = decryptAesCbcWithIv(bytes, iv) ?: continue
            if (dec.contains("http://") || dec.contains("https://") || dec.contains("\"source\"")) {
                out.add(dec)
            }
        }
        return out
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
        val referer = "$scheme://$host/"
        val reqHeaders = mapOf(
            "Origin" to "$scheme://$host",
            "Referer" to embedUrl
        )

        val endpoints = listOf(
            "$scheme://$host/api/v1/video?id=$videoId&w=1920&h=1080&r=$refererHost",
            "$scheme://$host/api/v1/info?id=$videoId&w=1920&h=1080&r=$refererHost"
        )

        var emitted = false
        val seenUrls = hashSetOf<String>()
        val fields = listOf(
            "source" to "Google",
            "cfNative" to "Cloudflare",
            "hls" to "HLS",
            "cfStream" to "CFStream",
            "ggStream" to "GGStream",
            "httpStream" to "HTTP",
            "videoSource" to "Video",
            "cf" to "CF"
        )

        for (apiUrl in endpoints) {
            val bodyText = runCatching {
                app.get(apiUrl, headers = reqHeaders, referer = referer).text.trim()
            }.getOrNull().orEmpty()
            if (bodyText.isBlank()) continue

            val candidates = decodeP2pPayloadCandidates(bodyText, videoId)
            for (decrypted in candidates) {
                for ((key, label) in fields) {
                    val match = Regex("\"$key\"\\s*:\\s*\"(https?://[^\"]+)\"").find(decrypted) ?: continue
                    val streamUrl = match.groupValues[1]
                    if ((key == "cf" || key == "cfNative") && streamUrl.endsWith(".txt", ignoreCase = true)) continue
                    if (!seenUrls.add(streamUrl)) continue
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name = "$sourceName Player ($label)",
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
                    if (streamUrl.startsWith("http") && seenUrls.add(streamUrl)) {
                        callback.invoke(
                            newExtractorLink(
                                source = sourceName,
                                name = "$sourceName Player (HLS)",
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
            }
            if (emitted) break
        }

        return emitted
    }

    /**
     * Offline AES-256-CTR extractor for Abyss / HydraX (`abyssplayer.com`, `abysscdn.com`,
     * `playhydrax.com`), with `enc-dec.app/api/dec-abyss` fallback.
     */
    suspend fun resolveAbyssPlayer(
        embedUrl: String,
        sourceName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = Regex("[?&]v=([A-Za-z0-9_-]+)").find(embedUrl)?.groupValues?.get(1)
            ?: Regex("abyss(?:s?player)?\\.(?:com|to)/([A-Za-z0-9_-]{5,})", RegexOption.IGNORE_CASE)
                .find(embedUrl)?.groupValues?.get(1)
        val candidates = buildList {
            if (!vid.isNullOrBlank()) {
                add("https://abysscdn.com/?v=$vid" to "https://abysscdn.com/")
            }
            add(embedUrl to referer)
        }

        for ((targetUrl, ref) in candidates) {
            val html = runCatching {
                app.get(
                    targetUrl,
                    headers = mapOf(
                        "Origin" to "https://playhydrax.com",
                        "Referer" to ref
                    ),
                    referer = ref
                ).text
            }.getOrNull().orEmpty()
            if (html.isBlank()) continue

            val directM3u8 = Regex("https?://[^\\s\"'<>\\\\]+\\.m3u8[^\\s\"'<>\\\\]*", RegexOption.IGNORE_CASE)
                .find(html)?.value
            if (directM3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        source = sourceName,
                        name = "$sourceName Abyss (HLS)",
                        url = directM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = ref
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            val encryptedDatas = Regex("(?:const|var)\\s+datas\\s*=\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1) ?: continue

            val metadata = decryptAbyssDatasOffline(encryptedDatas)
                ?: decryptAbyssDatasOnlineFallback(encryptedDatas)
                ?: continue

            var emitted = false
            val mp4Obj = metadata.optJSONObject("mp4")
            val sourcesArr = mp4Obj?.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val src = sourcesArr.optJSONObject(i) ?: continue
                    val directFile = src.optString("file", "").replace("\\/", "/")
                    val base = src.optString("url", "").trimEnd('/')
                    val path = src.optString("path", "").trimStart('/')
                    val streamUrl = when {
                        directFile.startsWith("http") -> directFile
                        base.startsWith("http") && path.isNotBlank() -> "$base/$path".replace("\\/", "/")
                        else -> ""
                    }
                    if (!streamUrl.startsWith("http")) continue
                    val label = src.optString("label", "").ifBlank { src.optString("type", "") }
                    val (_, qVal) = WordPressSemanticParser.normalizeQuality(label)
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name = "$sourceName Abyss${if (label.isNotBlank()) " ($label)" else ""}",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://abysscdn.com/"
                            this.quality = qVal
                        }
                    )
                    emitted = true
                }
            }

            val hlsObj = metadata.optJSONObject("hls")
            if (hlsObj != null) {
                val hlsUrl = listOf("file", "url", "master", "src", "source")
                    .firstNotNullOfOrNull { k -> hlsObj.optString(k, "").replace("\\/", "/").takeIf { it.startsWith("http") } }
                if (hlsUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name = "$sourceName Abyss (HLS)",
                            url = hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://abysscdn.com/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    emitted = true
                }
            }

            if (emitted) return true
        }
        return false
    }

    private fun decryptAbyssDatasOffline(encodedDatas: String): JSONObject? {
        return runCatching {
            val decodedBytes = Base64.decode(encodedDatas, Base64.DEFAULT)
            val decodedJson = JSONObject(String(decodedBytes, StandardCharsets.ISO_8859_1))
            val userId = decodedJson.optString("user_id", "")
            val slug = decodedJson.optString("slug", "")
            val md5Id = decodedJson.optString("md5_id", "")
            val mediaStr = decodedJson.optString("media", "")
            if (slug.isBlank() || mediaStr.isBlank()) return null

            val seed = "$userId:$slug:$md5Id"
            val digest = MessageDigest.getInstance("MD5").digest(seed.toByteArray(StandardCharsets.UTF_8))
            val md5Hex = digest.joinToString("") { "%02x".format(it) }
            val keyBytes = md5Hex.toByteArray(StandardCharsets.UTF_8)
            val ivBytes = keyBytes.copyOf(16)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            val decrypted = cipher.doFinal(mediaStr.toByteArray(StandardCharsets.ISO_8859_1))
            JSONObject(String(decrypted, StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private suspend fun decryptAbyssDatasOnlineFallback(encodedDatas: String): JSONObject? {
        return runCatching {
            val resp = app.post(
                url = "https://enc-dec.app/api/dec-abyss",
                headers = mapOf(
                    "Origin" to "https://playhydrax.com",
                    "Referer" to "https://playhydrax.com/",
                    "Content-Type" to "application/json"
                ),
                json = mapOf("text" to encodedDatas)
            ).text
            val root = JSONObject(resp)
            val result = root.optJSONObject("result") ?: return null
            val sources = result.optJSONArray("sources") ?: return null
            val mp4 = JSONObject().put("sources", sources)
            JSONObject().put("mp4", mp4)
        }.getOrNull()
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
                } else if (resolveStreamTapeFallback(target, sourceName, qualityLabel, qualityValue, subtitleCallback, callback)) {
                    emitted = true
                }
            }
        }
        return emitted
    }

    /**
     * Fallback for StreamTape `/v/<id>/...` links when built-in extractor returns false
     * (tries `/e/<id>` via `loadExtractor` and direct `robotlink` parsing).
     */
    suspend fun resolveStreamTapeFallback(
        url: String,
        sourceName: String,
        qualityLabel: String,
        qualityValue: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("streamtape.", ignoreCase = true) &&
            !url.contains("shavetape.", ignoreCase = true) &&
            !url.contains("watchadsontape.", ignoreCase = true)
        ) {
            return false
        }
        val vid = Regex("/[ve]/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: return false
        val embedUrl = "https://streamtape.com/e/$vid"
        if (embedUrl != url && loadExtractor(embedUrl, url, subtitleCallback, callback)) {
            return true
        }
        val html = runCatching { app.get(embedUrl, referer = url).text }.getOrNull().orEmpty()
        if (html.isBlank()) return false
        val robotMatch = Regex("""robotlink['"]?\s*[=:]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
        val tokenMatch = Regex("""token\s*[=:]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
        if (!robotMatch.isNullOrBlank() && !tokenMatch.isNullOrBlank()) {
            val prefix = if (robotMatch.startsWith("//")) "https:$robotMatch" else if (robotMatch.startsWith("http")) robotMatch else "https://$robotMatch"
            val streamUrl = "$prefix&token=$tokenMatch&stream=1"
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = "$sourceName StreamTape ($qualityLabel)",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = embedUrl
                    this.quality = qualityValue
                }
            )
            return true
        }
        return false
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
        val normalizedUrl = normalizeTpxPlayerUrl(playerUrl)
        val resp = runCatching { app.get(normalizedUrl, referer = referer) }.getOrNull() ?: return false
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
            } else if (isAbyssHost(frameUrl)) {
                if (resolveAbyssPlayer(frameUrl, sourceName, resp.url, callback)) emitted = true
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
            } else if (isAbyssHost(unwrapped)) {
                if (resolveAbyssPlayer(unwrapped, sourceName, resp.url, callback)) emitted = true
            } else if (extractHsaStreamId(unwrapped) != null) {
                val host = runCatching { URI(resp.url).host }.getOrNull() ?: "tpxsub.com"
                if (resolveHsaStream(unwrapped, sourceName, host, callback)) emitted = true
            } else if (loadExtractor(unwrapped, resp.url, subtitleCallback, callback)) {
                emitted = true
            }
        }
        return emitted
    }
}
