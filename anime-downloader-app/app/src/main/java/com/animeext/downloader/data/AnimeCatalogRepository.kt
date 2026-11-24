package com.animeext.downloader.data

import com.animeext.downloader.model.AnimeCard
import com.animeext.downloader.model.EpisodeEntry
import com.animeext.downloader.model.SeriesDetail
import com.animeext.downloader.model.SiteProfile
import com.animeext.downloader.model.StreamOption
import com.animeext.downloader.network.NetworkClient
import com.animeext.downloader.parser.SemanticParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class CatalogPageResult(
    val cards: List<AnimeCard>,
    val hasNextPage: Boolean
)

class AnimeCatalogRepository(private val network: NetworkClient) {

    suspend fun fetchCatalogPage(site: SiteProfile, categoryPath: String, page: Int): CatalogPageResult {
        val cleanPath = categoryPath.trim().ifBlank { site.latestPath }
        val section = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
        val url = if (page <= 1) {
            "${site.baseUrl}$section"
        } else {
            val baseSection = section.replace(Regex("/page/\\d+/?$"), "").trimEnd('/')
            if (baseSection.isBlank()) {
                val templ = site.pagePath.replace("{page}", page.toString())
                    .let { if (it.startsWith("/")) it else "/$it" }
                "${site.baseUrl}$templ"
            } else {
                "${site.baseUrl}$baseSection/page/$page/"
            }
        }

        val resp = network.get(url)
        val cards = SemanticParser.parseCatalogCards(resp.body, resp.url, site)
        val hasNext = SemanticParser.hasNextPage(resp.body, resp.url)
        return CatalogPageResult(cards = cards, hasNextPage = hasNext)
    }

    suspend fun searchSite(site: SiteProfile, query: String): List<AnimeCard> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name()).replace("+", "%20")

        val isKiranime = site.theme.equals("kiranime", ignoreCase = true) || site.supportsEmbeddedPlayer
        var cards = if (isKiranime) {
            searchWpJsonAnime(site, encoded).ifEmpty {
                searchKiranimeTitleApi(site, encoded)
            }
        } else {
            emptyList()
        }

        if (cards.isEmpty()) {
            val primaryTemplate = site.searchPath.replace("{query}", encoded)
                .let { if (it.startsWith("/")) it else "/$it" }
            val primaryUrl = "${site.baseUrl}$primaryTemplate"
            cards = runCatching {
                val resp = network.get(primaryUrl)
                SemanticParser.parseCatalogCards(resp.body, resp.url, site)
            }.getOrDefault(emptyList())
        }

        val fallback = site.fallbackSearchPath
        if (cards.isEmpty() && !fallback.isNullOrBlank() && fallback != site.searchPath) {
            val fallbackTemplate = fallback.replace("{query}", encoded)
                .let { if (it.startsWith("/")) it else "/$it" }
            val fallbackUrl = "${site.baseUrl}$fallbackTemplate"
            cards = runCatching {
                val resp = network.get(fallbackUrl)
                SemanticParser.parseCatalogCards(resp.body, resp.url, site)
            }.getOrDefault(emptyList())
        }

        return cards
    }

    suspend fun searchAllSites(sites: List<SiteProfile>, query: String): List<AnimeCard> = coroutineScope {
        sites.map { site ->
            async {
                runCatching { searchSite(site, query) }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    private suspend fun searchWpJsonAnime(site: SiteProfile, encodedQuery: String): List<AnimeCard> {
        val apiUrl = "${site.baseUrl}/wp-json/wp/v2/anime?search=$encodedQuery&_embed=wp:featuredmedia&per_page=20"
        val body = runCatching { network.get(apiUrl).body.trim() }.getOrNull().orEmpty()
        if (!body.startsWith("[")) return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            val cards = mutableListOf<AnimeCard>()
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
                cards.add(
                    AnimeCard(
                        siteId = site.id,
                        siteName = site.name,
                        title = title,
                        url = link,
                        posterUrl = poster
                    )
                )
            }
            cards
        }.getOrDefault(emptyList())
    }

    private suspend fun searchKiranimeTitleApi(site: SiteProfile, encodedQuery: String): List<AnimeCard> {
        val apiUrl = "${site.baseUrl}/wp-json/kiranime/v1/anime/title?query=$encodedQuery"
        val body = runCatching { network.get(apiUrl).body.trim() }.getOrNull().orEmpty()
        if (!body.startsWith("[")) return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            val cards = mutableListOf<AnimeCard>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val slug = item.optString("slug", "").trim()
                val title = Jsoup.parse(item.optString("title", "")).text().replace(Regex("\\s+"), " ").trim()
                if (slug.isBlank() || title.isBlank()) continue
                cards.add(
                    AnimeCard(
                        siteId = site.id,
                        siteName = site.name,
                        title = title,
                        url = "${site.baseUrl}/anime/$slug/",
                        posterUrl = null
                    )
                )
            }
            cards
        }.getOrDefault(emptyList())
    }

    suspend fun fetchSeriesDetail(site: SiteProfile, seriesUrl: String): SeriesDetail {
        val resp = network.get(seriesUrl)
        val detail = SemanticParser.parseSeriesDetail(resp.body, resp.url, site)

        // If Kiranime series page has a /watch/ page, inspect it for streaming servers too
        val watchUrl = detail.watchUrl
        if (!watchUrl.isNullOrBlank()) {
            val watchResp = runCatching { network.get(watchUrl, referer = resp.url) }.getOrNull()
            if (watchResp != null) {
                val watchEps = SemanticParser.parseKiranimeWatchEpisodes(watchResp.body, watchResp.url)
                if (watchEps.isNotEmpty()) {
                    val watchEntries = watchEps.map { (epNum, epUrl) ->
                        EpisodeEntry(
                            label = "Watch · Episode $epNum",
                            episodeNumber = epNum,
                            season = 1,
                            streams = listOf(
                                StreamOption(
                                    quality = "Stream",
                                    hostLabel = "Watch Server",
                                    url = epUrl,
                                    referer = watchResp.url
                                )
                            )
                        )
                    }
                    return detail.copy(episodes = detail.episodes + watchEntries)
                }

                if (detail.episodes.isEmpty()) {
                    val embedded = SemanticParser.extractEmbeddedMedia(watchResp.body, watchResp.url)
                    val streams = mutableListOf<StreamOption>()
                    embedded.directMediaUrls.forEach {
                        streams.add(StreamOption(quality = "HD", hostLabel = "Direct", url = it, isDirectVideo = true, referer = watchResp.url))
                    }
                    embedded.iframeUrls.forEach {
                        streams.add(StreamOption(quality = "Stream", hostLabel = "Embed", url = it, referer = watchResp.url))
                    }
                    if (streams.isNotEmpty()) {
                        return detail.copy(episodes = listOf(EpisodeEntry("Episode 1", 1, 1, streams)))
                    }
                }
            }
        }

        return detail
    }
}
