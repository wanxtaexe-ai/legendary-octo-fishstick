package com.animeext.cloudstream

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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

    private fun encodeEpisodePayload(payload: EpisodePayload): String {
        val obj = JSONObject()
        obj.put("referer", payload.referer)
        if (!payload.watchUrl.isNullOrBlank()) {
            obj.put("watchUrl", payload.watchUrl)
        }
        val arr = JSONArray()
        for (stream in payload.streams) {
            val sObj = JSONObject()
            sObj.put("quality", stream.quality)
            sObj.put("qualityValue", stream.qualityValue)
            if (!stream.size.isNullOrBlank()) sObj.put("size", stream.size)
            sObj.put("hostLabel", stream.hostLabel)
            sObj.put("url", stream.url)
            sObj.put("isDirectVideo", stream.isDirectVideo)
            arr.put(sObj)
        }
        obj.put("streams", arr)
        return obj.toString()
    }

    private fun decodeEpisodePayload(data: String): EpisodePayload {
        val trimmed = data.trim()
        if (!trimmed.startsWith("{")) {
            return EpisodePayload(
                referer = mainUrl,
                streams = listOf(ParsedStreamLink(url = data))
            )
        }
        return runCatching {
            val obj = JSONObject(trimmed)
            val referer = obj.optString("referer", mainUrl)
            val watchUrl = obj.optString("watchUrl", "").ifBlank { null }
            val streamsArr = obj.optJSONArray("streams") ?: JSONArray()
            val streams = mutableListOf<ParsedStreamLink>()
            for (i in 0 until streamsArr.length()) {
                val sObj = streamsArr.optJSONObject(i) ?: continue
                val url = sObj.optString("url", "")
                if (url.isBlank()) continue
                streams.add(
                    ParsedStreamLink(
                        quality = sObj.optString("quality", "Unknown"),
                        qualityValue = sObj.optInt("qualityValue", Qualities.Unknown.value),
                        size = sObj.optString("size", "").ifBlank { null },
                        hostLabel = sObj.optString("hostLabel", "Link"),
                        url = url,
                        isDirectVideo = sObj.optBoolean("isDirectVideo", false)
                    )
                )
            }
            EpisodePayload(referer = referer, watchUrl = watchUrl, streams = streams)
        }.getOrElse {
            EpisodePayload(referer = mainUrl, streams = listOf(ParsedStreamLink(url = data)))
        }
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

    private enum class TargetOutcome {
        RESOLVED,
        SKIP_WEBVIEW,
        TRY_WEBVIEW
    }

    private suspend fun searchWpJson(baseUrl: String, endpointType: String, encodedQuery: String): List<ParsedCard> {
        val apiUrl = "$baseUrl/wp-json/wp/v2/$endpointType?search=$encodedQuery&_embed=wp:featuredmedia&per_page=20"
        val body = runCatching { app.get(apiUrl).text.trim() }.getOrNull().orEmpty()
        if (!body.startsWith("[")) return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            val cards = mutableListOf<ParsedCard>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val link = item.optString("link", "").trim()
                val rawTitle = item.optJSONObject("title")?.optString("rendered", "").orEmpty()
                val title = Jsoup.parse(rawTitle).text().replace(Regex("\\s+"), " ").trim()
                if (link.isBlank() || title.isBlank()) continue
                val poster = item.optJSONObject("_embedded")
                    ?.optJSONArray("wp:featuredmedia")
                    ?.optJSONObject(0)
                    ?.optString("source_url", "")
                    ?.takeIf { it.startsWith("http") }
                cards.add(ParsedCard(title = title, url = link, posterUrl = poster))
            }
            cards
        }.getOrDefault(emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val cfg = refreshBaseUrl()
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")

        val isKiranime = cfg.theme.equals("kiranime", ignoreCase = true) || cfg.supportsEmbeddedPlayer
        var cards = if (isKiranime) {
            searchWpJson(mainUrl, "anime", encoded)
        } else {
            emptyList()
        }

        if (cards.isEmpty()) {
            val primaryTemplate = cfg.searchPath.replace("{query}", encoded)
                .let { if (it.startsWith("/")) it else "/$it" }
            val primaryUrl = "$mainUrl$primaryTemplate"
            cards = runCatching {
                val html = app.get(primaryUrl).text
                WordPressSemanticParser.parseCatalogCards(html, primaryUrl)
            }.getOrDefault(emptyList())
        }

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

        if (cards.isEmpty() && !isKiranime) {
            cards = searchWpJson(mainUrl, "posts", encoded)
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

        var resolvedWatchUrl = detail.watchUrl
        if (resolvedWatchUrl.isNullOrBlank() && detail.episodes.isEmpty() && url.contains("/anime/", ignoreCase = true)) {
            val slug = url.trimEnd('/').substringAfterLast('/')
            if (slug.isNotBlank()) {
                for (candidate in listOf("$mainUrl/watch/$slug/", "$mainUrl/watch/$slug-episode-1/")) {
                    val ok = runCatching {
                        val resp = app.get(candidate, referer = url)
                        resp.code in 200..299 &&
                            (resp.text.contains("hsastream", ignoreCase = true) ||
                                resp.text.contains("data-embed-id", ignoreCase = true) ||
                                resp.text.contains("episode-list-item", ignoreCase = true))
                    }.getOrDefault(false)
                    if (ok) {
                        resolvedWatchUrl = candidate
                        break
                    }
                }
            }
        }

        val episodeList: List<Episode> = if (detail.episodes.isNotEmpty()) {
            detail.episodes.map { ep ->
                val payload = encodeEpisodePayload(
                    EpisodePayload(
                        referer = url,
                        watchUrl = resolvedWatchUrl,
                        streams = ep.streams
                    )
                )
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
        } else if (!resolvedWatchUrl.isNullOrBlank()) {
            val watchEpisodes = runCatching {
                val watchHtml = app.get(resolvedWatchUrl, referer = url).text
                WordPressSemanticParser.parseKiranimeWatchEpisodes(watchHtml, resolvedWatchUrl)
            }.getOrDefault(emptyList())

            if (watchEpisodes.size > 1) {
                watchEpisodes.map { (epNum, epWatchUrl) ->
                    val payload = encodeEpisodePayload(
                        EpisodePayload(
                            referer = url,
                            watchUrl = epWatchUrl,
                            streams = emptyList()
                        )
                    )
                    newEpisode(
                        url = payload,
                        fix = false,
                        initializer = {
                            this.name = "Episode $epNum"
                            this.season = 1
                            this.episode = epNum
                        }
                    )
                }
            } else {
                val payload = encodeEpisodePayload(
                    EpisodePayload(
                        referer = url,
                        watchUrl = resolvedWatchUrl,
                        streams = emptyList()
                    )
                )
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
            }
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

    private suspend fun emitDirectOrExtractor(
        resolvedUrl: String,
        referer: String,
        hostLabel: String,
        qualityLabel: String,
        qualityValue: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (resolvedUrl.isBlank() || WordPressStreamResolvers.isKnownDeadUrl(resolvedUrl)) {
            return false
        }
        val pd = WordPressSemanticParser.normalizePixeldrainUrl(resolvedUrl)
        if (pd != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name Pixeldrain ($qualityLabel)",
                    url = pd,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = resolvedUrl
                    this.quality = qualityValue
                }
            )
            return true
        }
        if (WordPressSemanticParser.isDirectVideoUrl(resolvedUrl)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name $hostLabel ($qualityLabel)",
                    url = resolvedUrl,
                    type = if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = referer
                    this.quality = qualityValue
                }
            )
            return true
        }
        if (WordPressStreamResolvers.extractHsaStreamId(resolvedUrl) != null) {
            val host = runCatching { URI(referer).host }.getOrNull() ?: "animevilla.org"
            if (WordPressStreamResolvers.resolveHsaStream(resolvedUrl, name, host, callback)) {
                return true
            }
        }
        if (resolvedUrl.contains("mirrored.to/files/", ignoreCase = true)) {
            if (WordPressStreamResolvers.resolveMirroredTo(
                    mirroredUrl = resolvedUrl,
                    sourceName = name,
                    qualityLabel = qualityLabel,
                    qualityValue = qualityValue,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            ) {
                return true
            }
        }
        if (WordPressStreamResolvers.isAbyssHost(resolvedUrl)) {
            if (WordPressStreamResolvers.resolveAbyssPlayer(resolvedUrl, name, referer, callback)) {
                return true
            }
        }
        if (resolvedUrl.contains("tpxbeta.", ignoreCase = true) ||
            resolvedUrl.contains("beta.tpxsub.", ignoreCase = true)
        ) {
            if (WordPressStreamResolvers.resolveTpxPlayerPage(
                    playerUrl = resolvedUrl,
                    sourceName = name,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            ) {
                return true
            }
        }
        if (loadExtractor(resolvedUrl, referer, subtitleCallback, callback)) {
            return true
        }
        return WordPressStreamResolvers.resolveStreamTapeFallback(
            url = resolvedUrl,
            sourceName = name,
            qualityLabel = qualityLabel,
            qualityValue = qualityValue,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun resolveSingleTarget(
        targetUrl: String,
        referer: String,
        hostLabel: String,
        qualityLabel: String,
        qualityValue: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): TargetOutcome {
        if (targetUrl.isBlank() || WordPressStreamResolvers.isKnownDeadUrl(targetUrl)) {
            return TargetOutcome.SKIP_WEBVIEW
        }

        // Offline decoder for links.tpxsub.com / token query wrappers
        val effectiveUrl = AdChainHttpResolver.unwrapTokenUrl(targetUrl) ?: targetUrl
        if (WordPressStreamResolvers.isKnownDeadUrl(effectiveUrl)) {
            return TargetOutcome.SKIP_WEBVIEW
        }

        if (emitDirectOrExtractor(
                resolvedUrl = effectiveUrl,
                referer = referer,
                hostLabel = hostLabel,
                qualityLabel = qualityLabel,
                qualityValue = qualityValue,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        ) {
            return TargetOutcome.RESOLVED
        }

        // Provider-agnostic multi-hop HTTP ad-chain resolver (WP Safelink, AdLinkFly /links/go, meta/JS redirects)
        return when (val chain = AdChainHttpResolver.resolve(effectiveUrl, referer)) {
            is AdChainResult.Resolved -> {
                if (chain.url != effectiveUrl &&
                    emitDirectOrExtractor(
                        resolvedUrl = chain.url,
                        referer = chain.referer,
                        hostLabel = hostLabel,
                        qualityLabel = qualityLabel,
                        qualityValue = qualityValue,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                ) {
                    TargetOutcome.RESOLVED
                } else {
                    TargetOutcome.TRY_WEBVIEW
                }
            }
            is AdChainResult.HumanGateRequired,
            is AdChainResult.DeadOrBlocked -> TargetOutcome.SKIP_WEBVIEW
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = decodeEpisodePayload(data)
        var emittedAny = false
        val unresolvedForWebView = mutableListOf<ParsedStreamLink>()

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
                    if (emitDirectOrExtractor(
                            resolvedUrl = frameUrl,
                            referer = payload.watchUrl,
                            hostLabel = "Player",
                            qualityLabel = "Auto",
                            qualityValue = Qualities.Unknown.value,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    ) {
                        emittedAny = true
                    }
                }
            }
        }

        // 2. Process episode stream / redirector links via pure HTTP decoders & extractors
        for (stream in payload.streams) {
            if (stream.url.isBlank()) continue
            val outcome = runCatching {
                resolveSingleTarget(
                    targetUrl = stream.url,
                    referer = payload.referer,
                    hostLabel = stream.hostLabel,
                    qualityLabel = stream.quality,
                    qualityValue = stream.qualityValue,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }.getOrDefault(TargetOutcome.TRY_WEBVIEW)
            when (outcome) {
                TargetOutcome.RESOLVED -> emittedAny = true
                TargetOutcome.TRY_WEBVIEW -> if (!WordPressStreamResolvers.isKnownDeadUrl(stream.url)) {
                    unresolvedForWebView.add(stream)
                }
                TargetOutcome.SKIP_WEBVIEW -> Unit
            }
        }

        // 3. Only if pure HTTP returned zero links and non-human-gated links remain, attempt short-timeout WebView interception
        if (!emittedAny && unresolvedForWebView.isNotEmpty()) {
            val mediaInterceptRegex = Regex(
                "https?://.*(?:pixeldrain\\.(?:com|dev)/(?:u|api/file)/|gofile\\.io/(?:d|download)/|streamtape\\.[a-z]+/[ve]/|krakenfiles\\.com/|hsastream\\.|rpmhub\\.|upns\\.|p2pplay\\.|abyssplayer\\.|abysscdn\\.|\\.(?:mp4|m3u8|mkv|webm)(?:\\?.*)?$)",
                RegexOption.IGNORE_CASE
            )

            for (stream in unresolvedForWebView.take(3)) {
                val decoded = WordPressStreamResolvers.decodeTpxRedirectUrl(stream.url) ?: stream.url
                if (WordPressStreamResolvers.isKnownDeadUrl(decoded)) continue
                runCatching {
                    val resolver = WebViewResolver(
                        interceptUrl = mediaInterceptRegex,
                        timeout = 15_000L
                    )
                    val (finalReq, extraReqs) = resolver.resolveUsingWebView(
                        url = decoded,
                        referer = payload.referer,
                        headers = emptyMap()
                    )
                    val candidates = (listOfNotNull(finalReq) + extraReqs).map { it.url.toString() }.distinct()
                    for (candidateUrl in candidates) {
                        if (emitDirectOrExtractor(
                                resolvedUrl = candidateUrl,
                                referer = decoded,
                                hostLabel = stream.hostLabel,
                                qualityLabel = stream.quality,
                                qualityValue = stream.qualityValue,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        ) {
                            emittedAny = true
                        }
                    }
                }
            }
        }

        return emittedAny
    }
}
