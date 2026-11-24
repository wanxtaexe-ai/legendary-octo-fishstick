package com.animeext.cloudstream

import android.util.Base64
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.nio.charset.StandardCharsets

data class ParsedCard(
    val title: String,
    val url: String,
    val posterUrl: String? = null
)

data class ParsedStreamLink(
    val quality: String = "Unknown",
    val qualityValue: Int = Qualities.Unknown.value,
    val size: String? = null,
    val hostLabel: String = "Link",
    val url: String = "",
    val isDirectVideo: Boolean = false
)

data class ParsedEpisodeBlock(
    val episodeLabel: String,
    val episodeNumber: Int? = null,
    val season: Int = 1,
    val streams: List<ParsedStreamLink> = emptyList()
)

data class ParsedSeriesPage(
    val title: String,
    val posterUrl: String? = null,
    val synopsis: String? = null,
    val genres: List<String> = emptyList(),
    val isCompleted: Boolean = false,
    val isDubbed: Boolean = false,
    val watchUrl: String? = null,
    val episodes: List<ParsedEpisodeBlock> = emptyList()
)

data class ParsedEmbeddedMedia(
    val url: String,
    val qualityValue: Int,
    val isM3u8: Boolean
)

object WordPressSemanticParser {
    private val noiseUrlFragments = listOf(
        "facebook.com/sharer",
        "x.com/intent",
        "twitter.com/intent",
        "linkedin.com/share",
        "pinterest.com/pin",
        "reddit.com/submit",
        "api.whatsapp.com",
        "telegram.me/share",
        "t.me/share",
        "vk.com/share",
        "tumblr.com/",
        "mailto:",
        "wp-login.php",
        "#respond",
        "tutorial-video",
        "t.me/",
        "telegram.me/",
        "telegram.dog/",
        "animeyuga.com",
        "animevilla.in/tutorial"
    )

    private val skipCategorySlugRegex =
        Regex("/(author|category|genre|tag|status|type|studio|producer|licensor|anime-type|page)/", RegexOption.IGNORE_CASE)
    private val sizeRegex = Regex("(\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB))", RegexOption.IGNORE_CASE)
    private val seasonHeaderRegex = Regex("^\\s*(?:\\[\\s*)?Season\\s*(\\d+)(?:\\s*\\])?\\s*$", RegexOption.IGNORE_CASE)
    private val episodeHeaderRegex = Regex("\\b(?:S(\\d+)\\s*)?(?:Episode|Ep\\.?)\\s*(\\d+(?:\\s*-\\s*\\d+)?)\\b", RegexOption.IGNORE_CASE)
    private val labelValueRegex = Regex(
        "^(Name|Other Name|Japanese|English|Season|Genre|Genres|Myanimelist|Total Episodes|Episodes|Duration|Language|Quality|Size|Status|Type|Aired|Premiered|Studios?|Producers?|Licensors?|Rating)\\s*:+\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val pixeldrainRegex =
        Regex("^https?://(?:www\\.)?pixeldrain\\.(?:com|dev)/(?:u|api/file)/([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val directVideoExtRegex =
        Regex("\\.(mp4|m3u8|mkv|webm)(\\?.*)?$", RegexOption.IGNORE_CASE)

    fun resolveUrl(rawUrl: String?, baseUrl: String): String? {
        if (rawUrl.isNullOrBlank()) return null
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("#") || trimmed.startsWith("javascript:", ignoreCase = true)) return null
        return runCatching { URI(baseUrl).resolve(trimmed).toString() }.getOrNull()
    }

    fun isNoiseLink(url: String?, text: String = ""): Boolean {
        if (url.isNullOrBlank()) return true
        val lowerUrl = url.lowercase()
        val lowerText = text.lowercase()
        if (noiseUrlFragments.any { lowerUrl.contains(it) }) return true
        return lowerText.contains("join our telegram") ||
            lowerText.contains("join telegram") ||
            lowerText.contains("watch tutorial") ||
            lowerText.contains("report your complaint") ||
            lowerText.contains("report link")
    }

    fun extractImageSrc(img: Element?, baseUrl: String): String? {
        if (img == null) return null
        val srcsetFirst = img.attr("srcset").split(",").firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-url"),
            srcsetFirst.orEmpty(),
            img.attr("src")
        )
        for (c in candidates) {
            val trimmed = c.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("data:image/svg+xml", ignoreCase = true) ||
                trimmed.startsWith("data:image/gif", ignoreCase = true)
            ) {
                continue
            }
            resolveUrl(trimmed, baseUrl)?.let { return it }
        }
        return null
    }

    fun normalizeQuality(raw: String?): Pair<String, Int> {
        if (raw.isNullOrBlank()) return "Unknown" to Qualities.Unknown.value
        return when {
            Regex("\\b(2160p|4k|uhd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) ->
                "2160p" to Qualities.P2160.value
            Regex("\\b(1080p|fhd|full\\s*hd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) ->
                "1080p" to Qualities.P1080.value
            Regex("\\b(720p|hd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) ->
                "720p" to Qualities.P720.value
            Regex("\\b(480p|sd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) ->
                "480p" to Qualities.P480.value
            Regex("\\b360p\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) ->
                "360p" to Qualities.P360.value
            else -> "Unknown" to Qualities.Unknown.value
        }
    }

    fun normalizePixeldrainUrl(url: String): String? {
        val match = pixeldrainRegex.find(url) ?: return null
        return "https://pixeldrain.com/api/file/${match.groupValues[1]}"
    }

    fun isDirectVideoUrl(url: String): Boolean = directVideoExtRegex.containsMatchIn(url)

    fun parseCatalogCards(html: String, baseUrl: String): List<ParsedCard> {
        val doc = Jsoup.parse(html, baseUrl)
        val items = mutableListOf<ParsedCard>()
        val seen = mutableSetOf<String>()

        fun addCard(title: String, url: String, poster: String?) {
            val normalized = url.trimEnd('/').lowercase()
            if (seen.contains(normalized) || isNoiseLink(url, title) || skipCategorySlugRegex.containsMatchIn(normalized)) {
                return
            }
            seen.add(normalized)
            items.add(ParsedCard(title = title, url = url, posterUrl = poster))
        }

        // Strategy 1: Semantic <article>
        for (el in doc.select("main article, [role=main] article, article")) {
            val link = el.selectFirst("h2 a, h3 a, h4 a, header a[href], a[rel=bookmark]")
                ?: el.selectFirst("a[href]") ?: continue
            val url = resolveUrl(link.attr("href"), baseUrl) ?: continue
            val title = link.text().ifBlank { link.attr("title") }.replace(Regex("\\s+"), " ").trim()
            if (title.length < 2) continue
            addCard(title, url, extractImageSrc(el.selectFirst("img"), baseUrl))
        }

        // Strategy 2: List/card blocks with direct heading link
        if (items.isEmpty()) {
            for (el in doc.select("main li, section li, ul li, div")) {
                val heading = el.selectFirst("> h2 a, > h3 a, > header h2 a, > header h3 a") ?: continue
                val url = resolveUrl(heading.attr("href"), baseUrl) ?: continue
                val title = heading.text().replace(Regex("\\s+"), " ").trim()
                if (title.isBlank()) continue
                addCard(title, url, extractImageSrc(el.selectFirst("img"), baseUrl))
            }
        }

        // Strategy 3: Kiranime /anime/ cards
        if (items.isEmpty()) {
            for (a in doc.select("a[href*=/anime/]")) {
                val url = resolveUrl(a.attr("href"), baseUrl) ?: continue
                val container = a.parents().firstOrNull { it.tagName() in setOf("article", "li", "div") }
                val title = a.selectFirst("h2, h3, h4")?.text()
                    ?.ifBlank { a.attr("title") }
                    ?.ifBlank { a.text() }
                    ?.ifBlank { container?.selectFirst("h2, h3, h4")?.text().orEmpty() }
                    ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (title.isBlank() || Regex("^(watch now|detail)$", RegexOption.IGNORE_CASE).matches(title)) continue
                val img = a.selectFirst("img") ?: container?.selectFirst("img")
                addCard(title, url, extractImageSrc(img, baseUrl))
            }
        }

        return items
    }

    fun hasNextPage(html: String, baseUrl: String): Boolean {
        val doc = Jsoup.parse(html, baseUrl)
        val el = doc.selectFirst("a[rel=next]")
            ?: doc.selectFirst("nav[aria-label*=pagination i] a.next")
            ?: doc.selectFirst("a.next.page-numbers")
            ?: doc.select("a").firstOrNull {
                Regex("^\\s*(next|next page|›|»)\\s*$", RegexOption.IGNORE_CASE).matches(it.text())
            }
        return el != null
    }

    fun parseSeriesDetail(html: String, pageUrl: String): ParsedSeriesPage {
        val doc = Jsoup.parse(html, pageUrl)
        val root = doc.selectFirst("article, main, [role=main]") ?: doc
        val contentRoot = root.selectFirst(".entry-content") ?: root

        val title = (
            root.selectFirst("h1")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title()
            ).replace(Regex("\\s+"), " ").trim()

        var poster = resolveUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"), pageUrl)
        if (poster == null) {
            for (img in contentRoot.select("img")) {
                val src = extractImageSrc(img, pageUrl)
                if (src != null && !Regex("\\.gif(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(src)) {
                    poster = src
                    break
                }
            }
        }

        val metadata = linkedMapOf<String, String>()
        for (el in root.select("li, p, tr")) {
            val text = el.text().replace(Regex("\\s+"), " ").trim()
            labelValueRegex.find(text)?.let { match ->
                metadata[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
            }
        }

        val genres = linkedSetOf<String>()
        for (g in root.select("a[href*=/genre/], a[rel=tag], a[rel=category tag]")) {
            val text = g.text().replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty() && text.length < 40) genres.add(text)
        }
        (metadata["genres"] ?: metadata["genre"])?.split(",")?.forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) genres.add(trimmed)
        }

        val synopsis = contentRoot.select("p")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter {
                it.length > 60 &&
                    !labelValueRegex.matches(it) &&
                    !Regex("^(note:|episode|fhd|hd\\s*[–-]|sd\\s*[–-]|download)", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }
            .take(2)
            .joinToString("\n")
            .ifBlank { null }

        val statusRaw = (
            metadata["status"] ?: metadata["total episodes"] ?: metadata["episodes"] ?: title
            ).lowercase()
        val isCompleted = statusRaw.contains("completed") || statusRaw.contains("finished")
        val isDubbed = title.contains("hindi dub", ignoreCase = true) ||
            (metadata["language"]?.contains("hindi dub", ignoreCase = true) == true)
        val watchUrl = resolveUrl(root.selectFirst("a[href*=/watch/]")?.attr("href"), pageUrl)
        val episodes = parseEpisodesAndQualities(html, pageUrl)

        return ParsedSeriesPage(
            title = title,
            posterUrl = poster,
            synopsis = synopsis,
            genres = genres.toList(),
            isCompleted = isCompleted,
            isDubbed = isDubbed,
            watchUrl = watchUrl,
            episodes = episodes
        )
    }

    fun parseEpisodesAndQualities(html: String, baseUrl: String): List<ParsedEpisodeBlock> {
        val doc = Jsoup.parse(html, baseUrl)
        val contentRoot = doc.selectFirst("article .entry-content, .entry-content, article, main") ?: doc

        val episodesMap = linkedMapOf<String, MutableList<ParsedStreamLink>>()
        val episodeMeta = linkedMapOf<String, Triple<String, Int?, Int>>()
        var currentSeason = 1
        var currentEpisodeKey: String? = null
        var currentQuality = "Unknown" to Qualities.Unknown.value
        var currentSize: String? = null

        fun ensureEpisode(label: String, season: Int): String {
            val clean = label.replace(Regex("\\s+"), " ").trim()
            val key = "$season|${clean.lowercase()}"
            if (!episodesMap.containsKey(key)) {
                val range = Regex("(\\d+(?:\\s*-\\s*\\d+)?)").find(clean)?.groupValues?.get(1)
                    ?.replace(Regex("\\s+"), "")
                val epNum = range?.split("-")?.firstOrNull()?.toIntOrNull()
                episodesMap[key] = mutableListOf()
                episodeMeta[key] = Triple(clean, epNum, season)
            }
            return key
        }

        val anchorRegex = Regex("<a\\b[^>]*>[\\s\\S]*?</a>", RegexOption.IGNORE_CASE)

        for (el in contentRoot.select("h1, h2, h3, h4, h5, p, li, div")) {
            if (el.tagName() == "div" && el.selectFirst("p, h2, h3, h4, li") != null) continue
            val fullText = el.text().replace(Regex("\\s+"), " ").trim()
            if (fullText.isEmpty()) continue

            seasonHeaderRegex.find(fullText)?.let {
                currentSeason = it.groupValues[1].toIntOrNull() ?: currentSeason
                continue
            }

            val epMatch = episodeHeaderRegex.find(fullText)
            val links = el.select("a[href]")

            if (epMatch != null && links.isEmpty()) {
                epMatch.groupValues[1].toIntOrNull()?.let { currentSeason = it }
                val rangePart = epMatch.groupValues[2].replace(Regex("\\s+"), "")
                currentEpisodeKey = ensureEpisode("Episode $rangePart", currentSeason)
                currentQuality = "Unknown" to Qualities.Unknown.value
                currentSize = null
                continue
            }

            if (links.isNotEmpty()) {
                if (epMatch != null) {
                    epMatch.groupValues[1].toIntOrNull()?.let { currentSeason = it }
                    val rangePart = epMatch.groupValues[2].replace(Regex("\\s+"), "")
                    currentEpisodeKey = ensureEpisode("Episode $rangePart", currentSeason)
                }

                val rawHtml = el.html()
                val bounds = anchorRegex.findAll(rawHtml).map { it.range }.toList()

                links.forEachIndexed { idx, a ->
                    val linkText = a.text().replace(Regex("\\s+"), " ").trim()
                    val resolvedUrl = resolveUrl(a.attr("href"), baseUrl) ?: return@forEachIndexed
                    if (isNoiseLink(resolvedUrl, linkText)) return@forEachIndexed
                    if (Regex("^watch\\s*now$", RegexOption.IGNORE_CASE).matches(linkText)) return@forEachIndexed
                    if (skipCategorySlugRegex.containsMatchIn(resolvedUrl)) return@forEachIndexed

                    val isEpisodeOrDownload = currentEpisodeKey != null ||
                        Regex("\\b(480p|720p|1080p|fhd|hd|sd|link|mir|pl1|mega|theta|hsa|pixeldrain|gofile)\\b", RegexOption.IGNORE_CASE)
                            .containsMatchIn(linkText) ||
                        directVideoExtRegex.containsMatchIn(resolvedUrl) ||
                        Regex("(short|redirect|go/|7k29m4n8q|pixeldrain|gofile|asurasever)", RegexOption.IGNORE_CASE)
                            .containsMatchIn(resolvedUrl)
                    if (!isEpisodeOrDownload) return@forEachIndexed

                    val epKey = currentEpisodeKey ?: ensureEpisode("Episode 1", currentSeason).also {
                        currentEpisodeKey = it
                    }

                    val precedingText = bounds.getOrNull(idx)?.let { b ->
                        val prevEnd = if (idx > 0) bounds[idx - 1].last + 1 else 0
                        Jsoup.parseBodyFragment(rawHtml.substring(prevEnd, b.first)).text().trim()
                    }.orEmpty()

                    val segQ = normalizeQuality(precedingText)
                    val segSize = sizeRegex.find(precedingText)?.groupValues?.get(1)?.replace(Regex("\\s+"), "")?.uppercase()
                    if (segQ.second != Qualities.Unknown.value) currentQuality = segQ
                    if (segSize != null) currentSize = segSize

                    val linkQ = normalizeQuality(linkText)
                    val effectiveQ = when {
                        linkQ.second != Qualities.Unknown.value -> linkQ
                        segQ.second != Qualities.Unknown.value -> segQ
                        else -> currentQuality
                    }

                    val streams = episodesMap[epKey] ?: return@forEachIndexed
                    if (streams.none { it.url == resolvedUrl && it.quality == effectiveQ.first }) {
                        streams.add(
                            ParsedStreamLink(
                                quality = effectiveQ.first,
                                qualityValue = effectiveQ.second,
                                size = segSize ?: currentSize,
                                hostLabel = linkText.ifBlank { "Link" },
                                url = resolvedUrl,
                                isDirectVideo = isDirectVideoUrl(resolvedUrl)
                            )
                        )
                    }
                }
            }
        }

        return episodesMap.entries
            .filter { it.value.isNotEmpty() }
            .map { (key, streams) ->
                val (label, num, season) = episodeMeta.getValue(key)
                ParsedEpisodeBlock(
                    episodeLabel = label,
                    episodeNumber = num,
                    season = season,
                    streams = streams
                )
            }
    }

    fun extractEmbeddedMedia(html: String, baseUrl: String): Pair<List<ParsedEmbeddedMedia>, List<String>> {
        val doc = Jsoup.parse(html, baseUrl)
        val media = mutableListOf<ParsedEmbeddedMedia>()
        val iframes = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun addMedia(raw: String?, hint: String) {
            val resolved = resolveUrl(raw, baseUrl) ?: return
            if (seen.contains(resolved) || !directVideoExtRegex.containsMatchIn(resolved)) return
            seen.add(resolved)
            val (_, qVal) = normalizeQuality(hint)
            media.add(
                ParsedEmbeddedMedia(
                    url = resolved,
                    qualityValue = qVal,
                    isM3u8 = resolved.contains(".m3u8", ignoreCase = true)
                )
            )
        }

        for (video in doc.select("video")) {
            addMedia(video.attr("src"), video.attr("data-quality").ifBlank { video.attr("title") })
            for (src in video.select("source")) {
                addMedia(src.attr("src"), src.attr("label").ifBlank { src.attr("size") })
            }
        }

        for (frame in doc.select("iframe[src], iframe[data-src]")) {
            val resolved = resolveUrl(frame.attr("src").ifBlank { frame.attr("data-src") }, baseUrl) ?: continue
            if (Regex("youtube\\.com|youtu\\.be|facebook\\.com|disqus\\.com|recaptcha", RegexOption.IGNORE_CASE)
                    .containsMatchIn(resolved)
            ) {
                continue
            }
            if (!iframes.contains(resolved)) iframes.add(resolved)
        }

        // Kiranime / AnimeVilla base64-encoded embed attributes (`data-embed-id`)
        for (el in doc.select("[data-embed-id]")) {
            val b64 = el.attr("data-embed-id").trim()
            if (b64.isBlank()) continue
            val decoded = runCatching {
                String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            }.getOrNull() ?: continue
            val urlMatch = Regex("https?://[^\\s\"'<>]+").find(decoded)?.value ?: continue
            if (!iframes.contains(urlMatch)) iframes.add(urlMatch)
        }

        val hsaStreamRegex = Regex(
            "https?://hsastream\\.[^\\s\"'<>\\\\]+",
            RegexOption.IGNORE_CASE
        )
        hsaStreamRegex.findAll(html).forEach { match ->
            val url = match.value.trimEnd(',', ';', ')')
            if (!iframes.contains(url)) iframes.add(url)
        }

        val keyedQualityRegex = Regex(
            "[\"'](2160p|1080p|720p|480p|360p|FHD|HD|SD)[\"']\\s*:\\s*[\"'](https?://[^\"']+\\.(?:m3u8|mp4|mkv|webm)[^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        )
        val rawVideoUrlRegex = Regex(
            "https?://[^\\s\"'<>\\\\]+\\.(?:m3u8|mp4|mkv|webm)(?:\\?[^\\s\"'<>\\\\]*)?",
            RegexOption.IGNORE_CASE
        )

        for (script in doc.select("script")) {
            val code = script.data()
            if (code.isBlank()) continue
            keyedQualityRegex.findAll(code).forEach { addMedia(it.groupValues[2], it.groupValues[1]) }
            rawVideoUrlRegex.findAll(code).forEach { addMedia(it.value, it.value) }
        }

        return media to iframes
    }

    /**
     * Parses Kiranime `/watch/...` episode switcher links (`a.episode-list-item`).
     */
    fun parseKiranimeWatchEpisodes(html: String, baseUrl: String): List<Pair<Int, String>> {
        val doc = Jsoup.parse(html, baseUrl)
        val results = mutableListOf<Pair<Int, String>>()
        val seen = mutableSetOf<String>()
        for (el in doc.select("a.episode-list-item[href], a[href*=/watch/][data-episode-search-query]")) {
            val href = resolveUrl(el.attr("href"), baseUrl) ?: continue
            if (!href.contains("/watch/", ignoreCase = true) || seen.contains(href)) continue
            seen.add(href)
            val epAttr = el.attr("data-episode-search-query").trim().toIntOrNull()
                ?: Regex("(\\d+)").find(el.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: (results.size + 1)
            results.add(epAttr to href)
        }
        return results
    }
}
