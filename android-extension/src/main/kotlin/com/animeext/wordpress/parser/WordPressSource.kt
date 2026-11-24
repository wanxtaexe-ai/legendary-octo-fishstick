package com.animeext.wordpress.parser

import com.animeext.wordpress.config.DomainConfigManager
import com.animeext.wordpress.http.ExtensionHttpClient
import com.animeext.wordpress.model.CatalogPage
import com.animeext.wordpress.model.EmbeddedIframe
import com.animeext.wordpress.model.EmbeddedSource
import com.animeext.wordpress.model.EpisodeStream
import com.animeext.wordpress.model.QualityLabel
import com.animeext.wordpress.model.ResolutionResult
import com.animeext.wordpress.model.ResolvedStream
import com.animeext.wordpress.model.SeriesDetail
import com.animeext.wordpress.resolver.MediaHostPatterns

abstract class WordPressSource(
    val siteId: String,
    protected val configManager: DomainConfigManager,
    protected val httpClient: ExtensionHttpClient
) {
    suspend fun getBaseUrl(): String = configManager.getBaseUrl(siteId)

    suspend fun getLatestUpdates(page: Int = 1): CatalogPage {
        val url = configManager.buildLatestUrl(siteId, page)
        val response = httpClient.getHtml(url)
        val items = SemanticHtmlParser.parseCatalogCards(response.html, response.url)
        val nextUrl = SemanticHtmlParser.parseNextPageUrl(response.html, response.url)
        return CatalogPage(
            siteId = siteId,
            page = page,
            url = response.url,
            hasNextPage = nextUrl != null,
            nextPageUrl = nextUrl,
            items = items
        )
    }

    suspend fun search(query: String, page: Int = 1): CatalogPage {
        if (query.isBlank()) {
            return CatalogPage(siteId, page, getBaseUrl(), hasNextPage = false, items = emptyList())
        }
        val site = configManager.getSiteConfig(siteId)
        val primaryUrl = configManager.buildSearchUrl(siteId, query, page, useFallback = false)

        var items = emptyList<com.animeext.wordpress.model.CatalogItem>()
        var finalUrl = primaryUrl
        var nextUrl: String? = null

        runCatching {
            val res = httpClient.getHtml(primaryUrl)
            finalUrl = res.url
            items = SemanticHtmlParser.parseCatalogCards(res.html, res.url)
            nextUrl = SemanticHtmlParser.parseNextPageUrl(res.html, res.url)
        }

        if (items.isEmpty() && !site.fallbackSearchPath.isNullOrBlank() && site.fallbackSearchPath != site.searchPath) {
            val fallbackUrl = configManager.buildSearchUrl(siteId, query, page, useFallback = true)
            val res = httpClient.getHtml(fallbackUrl)
            finalUrl = res.url
            items = SemanticHtmlParser.parseCatalogCards(res.html, res.url)
            nextUrl = SemanticHtmlParser.parseNextPageUrl(res.html, res.url)
        }

        return CatalogPage(
            siteId = siteId,
            page = page,
            url = finalUrl,
            hasNextPage = nextUrl != null,
            nextPageUrl = nextUrl,
            items = items
        )
    }

    suspend fun getSeriesDetail(seriesUrl: String): SeriesDetail {
        val res = httpClient.getHtml(seriesUrl)
        val detail = SemanticHtmlParser.parseSeriesDetail(res.html, res.url, siteId)
        val site = configManager.getSiteConfig(siteId)

        if (site.supportsEmbeddedPlayer && !detail.watchUrl.isNullOrBlank()) {
            val watchSources = runCatching {
                extractWatchStreams(detail.watchUrl, res.url)
            }.getOrNull()
            if (watchSources != null && watchSources.first.isNotEmpty()) {
                return detail.copy(
                    embeddedStreams = watchSources.first,
                    embeddedIframes = watchSources.second
                )
            }
        }
        return detail
    }

    suspend fun extractWatchStreams(
        watchUrl: String,
        referer: String? = null
    ): Pair<List<EmbeddedSource>, List<EmbeddedIframe>> {
        val res = httpClient.getHtml(watchUrl, referer)
        val (sources, iframes) = SemanticHtmlParser.extractEmbeddedVideoSources(res.html, res.url)
        val combinedSources = sources.toMutableList()

        if (combinedSources.isEmpty() && iframes.isNotEmpty()) {
            for (frame in iframes) {
                runCatching {
                    val inner = httpClient.getHtml(frame.url, res.url)
                    val (innerSources, _) = SemanticHtmlParser.extractEmbeddedVideoSources(inner.html, inner.url)
                    combinedSources.addAll(innerSources)
                }
            }
        }
        return combinedSources to iframes
    }

    suspend fun resolveStream(streamLink: EpisodeStream, referer: String? = null): ResolutionResult {
        val direct = MediaHostPatterns.matchDirectMediaHost(streamLink.url)
        if (direct != null && (direct.isDirectStream || direct.hostType == "pixeldrain" || direct.hostType == "gofile")) {
            return ResolutionResult.Resolved(
                stream = ResolvedStream(
                    url = direct.streamUrl,
                    originalUrl = direct.originalUrl,
                    hostType = direct.hostType,
                    mimeType = direct.mimeType,
                    quality = streamLink.quality,
                    headers = httpClient.buildHeadersForUrl(direct.streamUrl, referer),
                    resolvedVia = "direct-match"
                ),
                method = "direct"
            )
        }

        runCatching {
            val probed = httpClient.probeUrl(streamLink.url, referer)
            val landed = MediaHostPatterns.matchDirectMediaHost(probed.url)
            if (landed != null && landed.isDirectStream) {
                return ResolutionResult.Resolved(
                    stream = ResolvedStream(
                        url = landed.streamUrl,
                        originalUrl = landed.originalUrl,
                        hostType = landed.hostType,
                        mimeType = landed.mimeType,
                        quality = streamLink.quality,
                        headers = httpClient.buildHeadersForUrl(landed.streamUrl, streamLink.url),
                        resolvedVia = "http-redirect"
                    ),
                    method = "http-redirect"
                )
            }

            if (probed.html.isNotBlank()) {
                val (embedded, _) = SemanticHtmlParser.extractEmbeddedVideoSources(probed.html, probed.url)
                if (embedded.isNotEmpty()) {
                    val preferred = embedded.firstOrNull { it.quality == streamLink.quality } ?: embedded.first()
                    return ResolutionResult.Resolved(
                        stream = ResolvedStream(
                            url = preferred.url,
                            originalUrl = preferred.url,
                            hostType = "embedded",
                            mimeType = preferred.mimeType,
                            quality = if (preferred.quality != QualityLabel.UNKNOWN) preferred.quality else streamLink.quality,
                            headers = httpClient.buildHeadersForUrl(preferred.url, probed.url),
                            resolvedVia = preferred.sourceType
                        ),
                        method = "embedded-extractor"
                    )
                }
            }
        }

        return ResolutionResult.RequiresWebView(
            initialUrl = streamLink.url,
            headers = httpClient.buildHeadersForUrl(streamLink.url, referer),
            expectedQuality = streamLink.quality
        )
    }
}
