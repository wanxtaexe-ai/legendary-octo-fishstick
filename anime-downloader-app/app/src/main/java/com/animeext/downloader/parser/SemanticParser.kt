package com.animeext.downloader.parser

import com.animeext.downloader.model.AnimeCard
import com.animeext.downloader.model.EpisodeEntry
import com.animeext.downloader.model.SeriesDetail
import com.animeext.downloader.model.SiteProfile
import com.animeext.downloader.model.StreamOption
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

data class EmbeddedMediaResult(
    val directMediaUrls: List<String>,
    val iframeUrls: List<String>
)

object SemanticParser {
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
        if (raw.isNullOrBlank()) return "Unknown" to 0
        return when {
            Regex("\\b(2160p|4k|uhd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "2160p" to 2160
            Regex("\\b(1080p|fhd|full\\s*hd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "1080p" to 1080
            Regex("\\b(720p|hd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "720p" to 720
            Regex("\\b(480p|sd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "480p" to 480
            Regex("\\b360p\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "360p" to 360
            else -> "Unknown" to 0
        }
    }

    fun parseCatalogCards(html: String, baseUrl: String, site: SiteProfile): List<AnimeCard> {
        val doc = Jsoup.parse(html, baseUrl)
        val items = mutableListOf<AnimeCard>()
        val seen = mutableSetOf<String>()

        fun addCard(title: String, url: String, poster: String?) {
            val normalized = url.trimEnd('/').lowercase()
            if (seen.contains(normalized) || isNoiseLink(url, title) || skipCategorySlugRegex.containsMatchIn(normalized)) {
                return
            }
            seen.add(normalized)
            items.add(
                AnimeCard(
                    siteId = site.id,
                    siteName = site.name,
                    title = title,
                    url = url,
                    posterUrl = poster
                )
            )
        }

        for (el in doc.select("main article, [role=main] article, article")) {
            val link = el.selectFirst("h2 a, h3 a, h4 a, header a[href], a[rel=bookmark]")
                ?: el.selectFirst("a[href]") ?: continue
            val url = resolveUrl(link.attr("href"), baseUrl) ?: continue
            val title = link.text().ifBlank { link.attr("title") }.replace(Regex("\\s+"), " ").trim()
            if (title.length < 2) continue
            addCard(title, url, extractImageSrc(el.selectFirst("img"), baseUrl))
        }

        if (items.isEmpty()) {
            for (el in doc.select("main li, section li, ul li, div")) {
                val heading = el.selectFirst("> h2 a, > h3 a, > header h2 a, > header h3 a") ?: continue
                val url = resolveUrl(heading.attr("href"), baseUrl) ?: continue
                val title = heading.text().replace(Regex("\\s+"), " ").trim()
                if (title.isBlank()) continue
                addCard(title, url, extractImageSrc(el.selectFirst("img"), baseUrl))
            }
        }

        if (items.size < 3) {
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

    fun parseSeriesDetail(html: String, pageUrl: String, site: SiteProfile): SeriesDetail {
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

        return SeriesDetail(
            siteId = site.id,
            siteName = site.name,
            title = title,
            url = pageUrl,
            posterUrl = poster,
            synopsis = synopsis,
            genres = genres.toList(),
            isCompleted = isCompleted,
            isDubbed = isDubbed,
            watchUrl = watchUrl,
            episodes = episodes
        )
    }

    fun parseEpisodesAndQualities(html: String, baseUrl: String): List<EpisodeEntry> {
        val doc = Jsoup.parse(html, baseUrl)
        val contentRoot = doc.selectFirst("article .entry-content, .entry-content, article, main") ?: doc

        val episodesMap = linkedMapOf<String, MutableList<StreamOption>>()
        val episodeMeta = linkedMapOf<String, Triple<String, Int?, Int>>()
        var currentSeason = 1
        var currentEpisodeKey: String? = null
        var currentQuality = "Unknown" to 0
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

            val seasonMatch = seasonHeaderRegex.find(fullText)
            if (seasonMatch != null) {
                currentSeason = seasonMatch.groupValues[1].toIntOrNull() ?: currentSeason
                continue
            }

            val epMatch = episodeHeaderRegex.find(fullText)
            val links = el.select("a[href]")

            if (epMatch != null && links.isEmpty()) {
                epMatch.groupValues[1].toIntOrNull()?.let { currentSeason = it }
                val rangePart = epMatch.groupValues[2].replace(Regex("\\s+"), "")
                currentEpisodeKey = ensureEpisode("Episode $rangePart", currentSeason)
                currentQuality = "Unknown" to 0
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
                    if (segQ.second != 0) currentQuality = segQ
                    if (segSize != null) currentSize = segSize

                    val linkQ = normalizeQuality(linkText)
                    val effectiveQ = when {
                        linkQ.second != 0 -> linkQ
                        segQ.second != 0 -> segQ
                        else -> currentQuality
                    }

                    val streams = episodesMap[epKey] ?: return@forEachIndexed
                    if (streams.none { it.url == resolvedUrl && it.quality == effectiveQ.first }) {
                        streams.add(
                            StreamOption(
                                quality = effectiveQ.first,
                                size = segSize ?: currentSize,
                                hostLabel = linkText.ifBlank { "Link" },
                                url = resolvedUrl,
                                isDirectVideo = directVideoExtRegex.containsMatchIn(resolvedUrl),
                                referer = baseUrl
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
                val nonTelegram = streams.filterNot { it.hostLabel.equals("telegram", ignoreCase = true) }
                val ordered = if (nonTelegram.isNotEmpty()) nonTelegram else streams
                EpisodeEntry(
                    label = label,
                    episodeNumber = num,
                    season = season,
                    streams = ordered.sortedByDescending { normalizeQuality(it.quality).second }
                )
            }
    }

    fun extractEmbeddedMedia(html: String, baseUrl: String): EmbeddedMediaResult {
        val doc = Jsoup.parse(html, baseUrl)
        val media = mutableListOf<String>()
        val iframes = mutableListOf<String>()

        fun addMedia(raw: String?) {
            val resolved = resolveUrl(raw, baseUrl) ?: return
            if (directVideoExtRegex.containsMatchIn(resolved) && !media.contains(resolved)) {
                media.add(resolved)
            }
        }

        fun addIframe(raw: String?) {
            val cleaned = raw?.trim().orEmpty().replace("\\/", "/").replace("&amp;", "&")
            if (cleaned.isBlank()) return
            val resolved = resolveUrl(cleaned, baseUrl) ?: return
            if (Regex("youtube\\.com|youtu\\.be|facebook\\.com|disqus\\.com|recaptcha", RegexOption.IGNORE_CASE)
                    .containsMatchIn(resolved)
            ) return
            if (!iframes.contains(resolved)) iframes.add(resolved)
        }

        for (video in doc.select("video")) {
            addMedia(video.attr("src"))
            for (src in video.select("source")) {
                addMedia(src.attr("src"))
            }
        }

        for (frame in doc.select("iframe[src], iframe[data-src], [data-video], [data-url]")) {
            addIframe(
                frame.attr("src")
                    .ifBlank { frame.attr("data-src") }
                    .ifBlank { frame.attr("data-video") }
                    .ifBlank { frame.attr("data-url") }
            )
        }

        for (el in doc.select("[data-embed-id]")) {
            val rawB64 = el.attr("data-embed-id").trim()
            if (rawB64.isBlank()) continue
            val decoded = Base64Codec.decodeToUtf8(rawB64)?.replace("\\/", "/")?.replace("&amp;", "&") ?: continue
            Regex("(?:https?:)?//[^\\s\"'<>]+").findAll(decoded).forEach { addIframe(it.value) }
        }

        val knownPlayerRegex = Regex(
            "https?://(?:[a-z0-9-]+\\.)?(?:hsastream|rpmhub|rpmstream|rpmshare|upns|uns\\.bio|p2pplay|vidstack|strp2p|abyssplayer|abysscdn|playhydrax|streamhg|vidhide)\\.[^\\s\"'<>\\\\]+",
            RegexOption.IGNORE_CASE
        )
        knownPlayerRegex.findAll(html.replace("\\/", "/")).forEach { match ->
            addIframe(match.value.trimEnd(',', ';', ')'))
        }

        val rawVideoUrlRegex = Regex(
            "https?://[^\\s\"'<>\\\\]+\\.(?:m3u8|mp4|mkv|webm)(?:\\?[^\\s\"'<>\\\\]*)?",
            RegexOption.IGNORE_CASE
        )
        for (script in doc.select("script")) {
            rawVideoUrlRegex.findAll(script.data()).forEach { addMedia(it.value) }
        }

        return EmbeddedMediaResult(directMediaUrls = media, iframeUrls = iframes)
    }

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
