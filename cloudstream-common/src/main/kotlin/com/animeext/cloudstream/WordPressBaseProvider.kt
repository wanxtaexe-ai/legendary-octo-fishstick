package com.animeext.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodePayload(
    val referer: String = "",
    val watchUrl: String? = null,
    val streams: List<ParsedStreamLink> = emptyList()
)

abstract class WordPressBaseProvider(
    private val siteId: String,
    defaultBaseUrl: String,
    override var name: String
) : MainAPI() {

    override var mainUrl = defaultBaseUrl
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)
    override val mainPage = mainPageOf("" to "Latest Updates")

    private suspend fun refreshBaseUrl(): RemoteSiteConfig {
        val cfg = DomainConfigLoader.getSiteConfig(siteId)
        if (cfg.baseUrl.isNotBlank()) {
            mainUrl = cfg.baseUrl.trimEnd('/')
        }
        return cfg
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cfg = refreshBaseUrl()
        val url = if (page <= 1) {
            val latest = cfg.latestPath.let { if (it.startsWith("/")) it else "/$it" }
            "$mainUrl$latest"
        } else {
            val pagePath = cfg.pagePath.replace("{page}", page.toString())
                .let { if (it.startsWith("/")) it else "/$it" }
            "$mainUrl$pagePath"
        }

        val html = app.get(url).text
        val cards = WordPressSemanticParser.parseCatalogCards(html, url)
        val hasNext = WordPressSemanticParser.hasNextPage(html, url)
        val items = cards.map { card ->
            newAnimeSearchResponse(card.title, card.url, TvType.Anime) {
                this.posterUrl = card.posterUrl
            }
        }
        return newHomePageResponse(request, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val cfg = refreshBaseUrl()
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")

        val primaryTemplate = cfg.searchPath.replace("{query}", encoded)
            .let { if (it.startsWith("/")) it else "/$it" }
        val primaryUrl = "$mainUrl$primaryTemplate"

        var cards = runCatching {
            val html = app.get(primaryUrl).text
            WordPressSemanticParser.parseCatalogCards(html, primaryUrl)
        }.getOrDefault(emptyList())

        val fallback = cfg.fallbackSearchPath
        if (cards.isEmpty() && !fallback.isNullOrBlank() && fallback != cfg.searchPath) {
            val fallbackTemplate = fallback.replace("{query}", encoded)
                .let { if (it.startsWith("/")) it else "/$it" }
            val fallbackUrl = "$mainUrl$fallbackTemplate"
            cards = runCatching {
                val html = app.get(fallbackUrl).text
                WordPressSemanticParser.parseCatalogCards(html, fallbackUrl)
            }.getOrDefault(emptyList())
        }

        return cards.map { card ->
            newAnimeSearchResponse(card.title, card.url, TvType.Anime) {
                this.posterUrl = card.posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        refreshBaseUrl()
        val html = app.get(url).text
        val detail = WordPressSemanticParser.parseSeriesDetail(html, url)

        val episodeList: List<Episode> = if (detail.episodes.isNotEmpty()) {
            detail.episodes.map { ep ->
                val payload = EpisodePayload(
                    referer = url,
                    watchUrl = detail.watchUrl,
                    streams = ep.streams
                ).toJson()
                newEpisode(
                    url = payload,
                    fix = false,
                    initializer = {
                        this.name = ep.episodeLabel
                        this.season = ep.season
                        this.episode = ep.episodeNumber
                    }
                )
            }
        } else if (!detail.watchUrl.isNullOrBlank()) {
            val payload = EpisodePayload(
                referer = url,
                watchUrl = detail.watchUrl,
                streams = emptyList()
            ).toJson()
            listOf(
                newEpisode(
                    url = payload,
                    fix = false,
                    initializer = {
                        this.name = "Watch Stream"
                        this.season = 1
                        this.episode = 1
                    }
                )
            )
        } else {
            emptyList()
        }

        return newAnimeLoadResponse(detail.title, url, TvType.Anime) {
            this.posterUrl = detail.posterUrl
            this.plot = detail.synopsis
            this.tags = detail.genres
            this.showStatus = if (detail.isCompleted) ShowStatus.Completed else ShowStatus.Ongoing
            val dubStatus = if (detail.isDubbed) DubStatus.Dubbed else DubStatus.Subbed
            addEpisodes(dubStatus, episodeList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { parseJson<EpisodePayload>(data) }.getOrNull()
            ?: EpisodePayload(referer = mainUrl, streams = listOf(ParsedStreamLink(url = data)))

        var emittedAny = false

        // 1. Extract embedded streams from watchUrl if present (e.g. AnimeVilla /watch/ pages)
        if (!payload.watchUrl.isNullOrBlank()) {
            runCatching {
                val watchHtml = app.get(payload.watchUrl, referer = payload.referer).text
                val (media, iframes) = WordPressSemanticParser.extractEmbeddedMedia(watchHtml, payload.watchUrl)
                for (m in media) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name Embedded",
                            url = m.url,
                            type = if (m.isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                        ) {
                            this.referer = payload.watchUrl
                            this.quality = m.qualityValue
                        }
                    )
                    emittedAny = true
                }
                for (frameUrl in iframes) {
                    if (loadExtractor(frameUrl, payload.watchUrl, subtitleCallback, callback)) {
                        emittedAny = true
                    }
                }
            }
        }

        // 2. Process episode stream / redirector links
        val mediaInterceptRegex =
            Regex("https?://.*(?:pixeldrain\\.(?:com|dev)/(?:u|api/file)/|gofile\\.io/(?:d|download)/|\\.(?:mp4|m3u8|mkv|webm)(?:\\?.*)?$)", RegexOption.IGNORE_CASE)

        for (stream in payload.streams) {
            val targetUrl = stream.url
            if (targetUrl.isBlank()) continue

            // Direct Pixeldrain normalization
            val pixeldrainDirect = WordPressSemanticParser.normalizePixeldrainUrl(targetUrl)
            if (pixeldrainDirect != null) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Pixeldrain (${stream.quality})",
                        url = pixeldrainDirect,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = targetUrl
                        this.quality = stream.qualityValue
                    }
                )
                emittedAny = true
                continue
            }

            // Direct .mp4 / .m3u8
            if (stream.isDirectVideo || WordPressSemanticParser.isDirectVideoUrl(targetUrl)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ${stream.hostLabel} (${stream.quality})",
                        url = targetUrl,
                        type = if (targetUrl.contains(".m3u8", ignoreCase = true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.referer = payload.referer
                        this.quality = stream.qualityValue
                    }
                )
                emittedAny = true
                continue
            }

            // Try CloudStream built-in extractors first (Gofile, Mega, Streamtape, etc.)
            if (loadExtractor(targetUrl, payload.referer, subtitleCallback, callback)) {
                emittedAny = true
                continue
            }

            // Use CloudStream WebViewResolver for intermediate shortlink redirectors
            runCatching {
                val resolver = WebViewResolver(
                    interceptUrl = mediaInterceptRegex,
                    timeout = 45_000L
                )
                val (finalReq, extraReqs) = resolver.resolveUsingWebView(
                    url = targetUrl,
                    referer = payload.referer
                )
                val candidates = (listOfNotNull(finalReq) + extraReqs).map { it.url.toString() }.distinct()
                for (candidateUrl in candidates) {
                    val pd = WordPressSemanticParser.normalizePixeldrainUrl(candidateUrl)
                    val resolvedUrl = pd ?: candidateUrl
                    if (pd != null || WordPressSemanticParser.isDirectVideoUrl(resolvedUrl)) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ${stream.hostLabel} (${stream.quality})",
                                url = resolvedUrl,
                                type = if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                                    ExtractorLinkType.M3U8
                                } else {
                                    ExtractorLinkType.VIDEO
                                }
                            ) {
                                this.referer = targetUrl
                                this.quality = stream.qualityValue
                            }
                        )
                        emittedAny = true
                    } else if (loadExtractor(resolvedUrl, targetUrl, subtitleCallback, callback)) {
                        emittedAny = true
                    }
                }
            }
        }

        return emittedAny
    }
}
