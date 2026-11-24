package com.animeext.wordpress.parser

import com.animeext.wordpress.model.CatalogItem
import com.animeext.wordpress.model.EmbeddedIframe
import com.animeext.wordpress.model.EmbeddedSource
import com.animeext.wordpress.model.Episode
import com.animeext.wordpress.model.EpisodeStream
import com.animeext.wordpress.model.QualityLabel
import com.animeext.wordpress.model.SeriesDetail
import com.animeext.wordpress.resolver.MediaHostPatterns
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

object SemanticHtmlParser {
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

    fun resolveUrl(rawUrl: String?, baseUrl: String): String? {
        if (rawUrl.isNullOrBlank()) return null
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("#") || trimmed.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        return runCatching { URI(baseUrl).resolve(trimmed).toString() }.getOrNull()
    }

    fun isNoiseLink(url: String?, text: String = ""): Boolean {
        if (url.isNullOrBlank()) return true
        val lowerUrl = url.lowercase()
        val lowerText = text.lowercase()
        if (noiseUrlFragments.any { lowerUrl.contains(it) }) return true
        if (
            lowerText.contains("join our telegram") ||
            lowerText.contains("join telegram") ||
            lowerText.contains("watch tutorial") ||
            lowerText.contains("report your complaint") ||
            lowerText.contains("report link")
        ) {
            return true
        }
        return false
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

    fun extractFileSize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return sizeRegex.find(raw)?.groupValues?.get(1)?.replace(Regex("\\s+"), "")?.uppercase()
    }

    fun parseCatalogCards(html: String, baseUrl: String): List<CatalogItem> {
        val doc: Document = Jsoup.parse(html, baseUrl)
        val items = mutableListOf<CatalogItem>()
        val seenUrls = mutableSetOf<String>()

        fun addItem(item: CatalogItem) {
            val normalized = item.url.trimEnd('/').lowercase()
            if (seenUrls.contains(normalized)) return
            if (isNoiseLink(item.url, item.title)) return
            if (skipCategorySlugRegex.containsMatchIn(normalized)) return
            seenUrls.add(normalized)
            items.add(item)
        }

        // Strategy 1: Semantic <article> posts
        val articles = doc.select("main article, [role=main] article, article")
        for (el in articles) {
            val link = el.selectFirst("h2 a, h3 a, h4 a, header a[href], a[rel=bookmark]")
                ?: el.selectFirst("a[href]")
                ?: continue
            val url = resolveUrl(link.attr("href"), baseUrl) ?: continue
            val title = link.text().ifBlank { link.attr("title") }.replace(Regex("\\s+"), " ").trim()
            if (title.length < 2) continue

            val thumb = extractImageSrc(el.selectFirst("img"), baseUrl)
            val timeEl = el.selectFirst("time")
            val date = timeEl?.attr("datetime")?.ifBlank { timeEl.text() }
                ?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }
            val summary = el.selectFirst("p")?.text()?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }
            val genres = el.select("a[rel=category tag], a[href*=/category/], a[href*=/genre/]")
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
                .distinct()

            addItem(CatalogItem(title, url, thumb, date, summary, genres))
        }

        // Strategy 2: List/card items with direct heading links
        if (items.isEmpty()) {
            for (el in doc.select("main li, section li, ul li, div")) {
                val heading = el.selectFirst("> h2 a, > h3 a, > header h2 a, > header h3 a") ?: continue
                val url = resolveUrl(heading.attr("href"), baseUrl) ?: continue
                val title = heading.text().replace(Regex("\\s+"), " ").trim()
                if (title.isBlank()) continue
                val thumb = extractImageSrc(el.selectFirst("img"), baseUrl)
                val date = el.selectFirst("time")?.text()?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }
                val summary = el.selectFirst("> p")?.text()?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }
                addItem(CatalogItem(title, url, thumb, date, summary))
            }
        }

        // Strategy 3: Kiranime /anime/ links
        if (items.isEmpty()) {
            for (a in doc.select("a[href*=/anime/]")) {
                val url = resolveUrl(a.attr("href"), baseUrl) ?: continue
                val container = a.parents().firstOrNull {
                    it.tagName() in setOf("article", "li", "div")
                }
                val title = a.selectFirst("h2, h3, h4")?.text()
                    ?.ifBlank { a.attr("title") }
                    ?.ifBlank { a.text() }
                    ?.ifBlank { container?.selectFirst("h2, h3, h4")?.text().orEmpty() }
                    ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (title.isBlank() || Regex("^(watch now|detail)$", RegexOption.IGNORE_CASE).matches(title)) {
                    continue
                }
                val img = a.selectFirst("img") ?: container?.selectFirst("img")
                val thumb = extractImageSrc(img, baseUrl)
                addItem(CatalogItem(title, url, thumb))
            }
        }

        return items
    }

    fun parseNextPageUrl(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)
        val el = doc.selectFirst("a[rel=next]")
            ?: doc.selectFirst("nav[aria-label*=pagination i] a.next")
            ?: doc.selectFirst("a.next.page-numbers")
            ?: doc.select("a").firstOrNull {
                Regex("^\\s*(next|next page|›|»)\\s*$", RegexOption.IGNORE_CASE).matches(it.text())
            }
        return resolveUrl(el?.attr("href"), baseUrl)
    }

    fun parseSeriesDetail(html: String, pageUrl: String, siteId: String): SeriesDetail {
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
            if (el.tagName() == "tr") {
                val cells = el.select("th, td")
                if (cells.size >= 2) {
                    val key = cells[0].text().replace(Regex("[:\\s]+$"), "").trim().lowercase()
                    val value = cells[1].text().replace(Regex("\\s+"), " ").trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) metadata[key] = value
                }
                continue
            }
            val text = el.text().replace(Regex("\\s+"), " ").trim()
            labelValueRegex.find(text)?.let { match ->
                val rawKey = match.groupValues[1].lowercase()
                val value = match.groupValues[2].trim()
                metadata[rawKey] = value
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

        val synopsis = extractSynopsis(root, contentRoot)

        val statusRaw = (
            metadata["status"]
                ?: metadata["total episodes"]
                ?: metadata["episodes"]
                ?: title
            ).lowercase()
        val status = when {
            statusRaw.contains("completed") || statusRaw.contains("finished") -> "Completed"
            statusRaw.contains("ongoing") || statusRaw.contains("airing") -> "Ongoing"
            else -> "Unknown"
        }

        val watchUrl = resolveUrl(root.selectFirst("a[href*=/watch/]")?.attr("href"), pageUrl)
        val episodes = parseEpisodesAndQualities(html, pageUrl)
        val (embeddedSources, embeddedIframes) = extractEmbeddedVideoSources(html, pageUrl)

        return SeriesDetail(
            siteId = siteId,
            title = title,
            url = pageUrl,
            poster = poster,
            synopsis = synopsis,
            genres = genres.toList(),
            status = status,
            metadata = metadata,
            watchUrl = watchUrl,
            episodes = episodes,
            embeddedStreams = embeddedSources,
            embeddedIframes = embeddedIframes
        )
    }

    private fun extractSynopsis(root: Element, contentRoot: Element): String? {
        val heading = root.select("h2, h3, h4, strong, b").firstOrNull {
            Regex("^(synopsis|overview)\\s*:?$", RegexOption.IGNORE_CASE).matches(it.text().trim())
        }
        if (heading != null) {
            val parentP = heading.closest("p")
            if (parentP != null) {
                val sb = StringBuilder()
                val inline = parentP.text().replace(Regex("^synopsis\\s*:?\\s*", RegexOption.IGNORE_CASE), "").trim()
                if (inline.length > 20) sb.append(inline)
                var next = parentP.nextElementSibling()
                while (next != null && next.tagName() == "p" &&
                    !Regex("^(episode|download|note|subbed by)", RegexOption.IGNORE_CASE).containsMatchIn(next.text().trim())
                ) {
                    val t = next.text().replace(Regex("\\s+"), " ").trim()
                    if (t.isNotEmpty() && !labelValueRegex.matches(t) && !t.contains("[Written by", ignoreCase = true)) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(t)
                    }
                    next = next.nextElementSibling()
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
        }

        val fallbackParagraphs = contentRoot.select("p")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter {
                it.length > 60 &&
                    !labelValueRegex.matches(it) &&
                    !Regex("^(note:|episode|fhd|hd\\s*[–-]|sd\\s*[–-]|download)", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }
            .take(2)

        return fallbackParagraphs.joinToString("\n").ifBlank { null }
    }

    fun parseEpisodesAndQualities(html: String, baseUrl: String): List<Episode> {
        val doc = Jsoup.parse(html, baseUrl)
        val contentRoot = doc.selectFirst("article .entry-content, .entry-content, article, main") ?: doc

        val episodesMap = linkedMapOf<String, MutableList<EpisodeStream>>()
        val episodeMetaMap = linkedMapOf<String, Triple<String, Int?, String?>>()
        val episodeSeasonMap = linkedMapOf<String, Int>()

        var currentSeason = 1
        var currentEpisodeKey: String? = null
        var currentQualityHint = QualityLabel.UNKNOWN
        var currentSizeHint: String? = null

        fun ensureEpisode(label: String, season: Int): String {
            val cleanLabel = label.replace(Regex("\\s+"), " ").trim()
            val key = "$season|${cleanLabel.lowercase()}"
            if (!episodesMap.containsKey(key)) {
                val range = Regex("(\\d+(?:\\s*-\\s*\\d+)?)").find(cleanLabel)?.groupValues?.get(1)
                    ?.replace(Regex("\\s+"), "")
                val singleNum = range?.takeIf { !it.contains("-") }?.toIntOrNull()
                episodesMap[key] = mutableListOf()
                episodeMetaMap[key] = Triple(cleanLabel, singleNum, range)
                episodeSeasonMap[key] = season
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
                currentQualityHint = QualityLabel.UNKNOWN
                currentSizeHint = null
                continue
            }

            if (links.isNotEmpty()) {
                if (epMatch != null) {
                    epMatch.groupValues[1].toIntOrNull()?.let { currentSeason = it }
                    val rangePart = epMatch.groupValues[2].replace(Regex("\\s+"), "")
                    currentEpisodeKey = ensureEpisode("Episode $rangePart", currentSeason)
                }

                val rawHtml = el.html()
                val anchorBounds = anchorRegex.findAll(rawHtml).map { it.range }.toList()

                links.forEachIndexed { idx, a ->
                    val linkText = a.text().replace(Regex("\\s+"), " ").trim()
                    val resolvedUrl = resolveUrl(a.attr("href"), baseUrl) ?: return@forEachIndexed
                    if (isNoiseLink(resolvedUrl, linkText)) return@forEachIndexed
                    if (Regex("^watch\\s*now$", RegexOption.IGNORE_CASE).matches(linkText)) return@forEachIndexed
                    if (skipCategorySlugRegex.containsMatchIn(resolvedUrl)) return@forEachIndexed

                    val isEpisodeOrDownload = currentEpisodeKey != null ||
                        Regex("\\b(480p|720p|1080p|fhd|hd|sd|link|mir|pl1|mega|theta|hsa|pixeldrain|gofile)\\b", RegexOption.IGNORE_CASE)
                            .containsMatchIn(linkText) ||
                        Regex("\\.(mp4|m3u8|mkv)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(resolvedUrl) ||
                        Regex("(short|redirect|go/|7k29m4n8q|pixeldrain|gofile|asurasever)", RegexOption.IGNORE_CASE)
                            .containsMatchIn(resolvedUrl)
                    if (!isEpisodeOrDownload) return@forEachIndexed

                    val epKey = currentEpisodeKey ?: ensureEpisode("Episode 1", currentSeason).also {
                        currentEpisodeKey = it
                    }

                    val precedingText = anchorBounds.getOrNull(idx)?.let { bounds ->
                        val prevEnd = if (idx > 0) anchorBounds[idx - 1].last + 1 else 0
                        val segHtml = rawHtml.substring(prevEnd, bounds.first)
                        Jsoup.parseBodyFragment(segHtml).text().replace(Regex("\\s+"), " ").trim()
                    }.orEmpty()

                    val segQuality = QualityLabel.fromRaw(precedingText)
                    val segSize = extractFileSize(precedingText)
                    if (segQuality != QualityLabel.UNKNOWN) currentQualityHint = segQuality
                    if (segSize != null) currentSizeHint = segSize

                    val linkQuality = QualityLabel.fromRaw(linkText)
                    val effectiveQuality = when {
                        linkQuality != QualityLabel.UNKNOWN -> linkQuality
                        segQuality != QualityLabel.UNKNOWN -> segQuality
                        else -> currentQualityHint
                    }
                    val effectiveSize = extractFileSize(linkText) ?: segSize ?: currentSizeHint

                    val isDirectVideo = Regex("\\.(mp4|m3u8|mkv|webm)(\\?.*)?$", RegexOption.IGNORE_CASE)
                        .containsMatchIn(resolvedUrl)
                    val isDirectHost = isDirectVideo ||
                        Regex("pixeldrain\\.(com|dev)|gofile\\.io", RegexOption.IGNORE_CASE).containsMatchIn(resolvedUrl)

                    val streams = episodesMap[epKey] ?: return@forEachIndexed
                    if (streams.none { it.url == resolvedUrl && it.quality == effectiveQuality }) {
                        streams.add(
                            EpisodeStream(
                                quality = effectiveQuality,
                                rawQualityLabel = when {
                                    linkQuality != QualityLabel.UNKNOWN -> linkText
                                    segQuality != QualityLabel.UNKNOWN -> precedingText
                                    else -> effectiveQuality.display
                                },
                                size = effectiveSize,
                                hostLabel = linkText.ifBlank { "Link" },
                                url = resolvedUrl,
                                isDirectStream = isDirectVideo,
                                requiresWebView = !isDirectHost
                            )
                        )
                    }
                }
            }
        }

        return episodesMap.entries
            .filter { it.value.isNotEmpty() }
            .map { (key, streams) ->
                val (label, num, range) = episodeMetaMap.getValue(key)
                Episode(
                    episodeLabel = label,
                    episodeNumber = num,
                    episodeRange = range,
                    season = episodeSeasonMap.getValue(key),
                    streams = streams
                )
            }
    }

    fun extractEmbeddedVideoSources(
        html: String,
        baseUrl: String
    ): Pair<List<EmbeddedSource>, List<EmbeddedIframe>> {
        val doc = Jsoup.parse(html, baseUrl)
        val sources = mutableListOf<EmbeddedSource>()
        val iframes = mutableListOf<EmbeddedIframe>()
        val seenUrls = mutableSetOf<String>()

        fun addSource(rawUrl: String?, qualityHint: String, sourceType: String) {
            val resolved = resolveUrl(rawUrl, baseUrl) ?: return
            if (seenUrls.contains(resolved) || MediaHostPatterns.isAdOrNoiseUrl(resolved)) return
            seenUrls.add(resolved)
            sources.add(
                EmbeddedSource(
                    url = resolved,
                    quality = QualityLabel.fromRaw(qualityHint),
                    mimeType = MediaHostPatterns.inferMimeType(resolved),
                    sourceType = sourceType
                )
            )
        }

        for (video in doc.select("video")) {
            val directSrc = video.attr("src")
            if (directSrc.isNotBlank()) {
                addSource(directSrc, video.attr("data-quality").ifBlank { video.attr("title") }, "video-tag")
            }
            for (src in video.select("source")) {
                addSource(
                    src.attr("src"),
                    src.attr("label").ifBlank { src.attr("size") }.ifBlank { src.attr("title") },
                    "video-source"
                )
            }
        }

        for (frame in doc.select("iframe[src], iframe[data-src]")) {
            val raw = frame.attr("src").ifBlank { frame.attr("data-src") }
            val resolved = resolveUrl(raw, baseUrl) ?: continue
            if (MediaHostPatterns.isAdOrNoiseUrl(resolved)) continue
            if (Regex("youtube\\.com|youtu\\.be|facebook\\.com|disqus\\.com|recaptcha", RegexOption.IGNORE_CASE)
                    .containsMatchIn(resolved)
            ) {
                continue
            }
            iframes.add(
                EmbeddedIframe(
                    url = resolved,
                    label = frame.attr("title").ifBlank { frame.attr("name") }.ifBlank { "Embedded Player" }
                )
            )
        }

        val keyedQualityRegex = Regex(
            "[\"'](2160p|1080p|720p|480p|360p|FHD|HD|SD)[\"']\\s*:\\s*[\"'](https?://[^\"']+\\.(?:m3u8|mp4|mkv|webm)[^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        )
        val objSourceRegex = Regex(
            "(?:file|src|url)\\s*:\\s*[\"'](https?://[^\"']+\\.(?:m3u8|mp4|mkv|webm)[^\"']*)[\"'][^}]{0,160}?(?:label|quality|res)\\s*:\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
        val rawVideoUrlRegex = Regex(
            "https?://[^\\s\"'<>\\\\]+\\.(?:m3u8|mp4|mkv|webm)(?:\\?[^\\s\"'<>\\\\]*)?",
            RegexOption.IGNORE_CASE
        )

        for (script in doc.select("script")) {
            val code = script.data()
            if (code.isBlank()) continue
            keyedQualityRegex.findAll(code).forEach { m ->
                addSource(m.groupValues[2], m.groupValues[1], "script-quality-map")
            }
            objSourceRegex.findAll(code).forEach { m ->
                addSource(m.groupValues[1], m.groupValues[2], "script-player-config")
            }
            rawVideoUrlRegex.findAll(code).forEach { m ->
                addSource(m.value, m.value, "script-url")
            }
        }

        return sources to iframes
    }
}
