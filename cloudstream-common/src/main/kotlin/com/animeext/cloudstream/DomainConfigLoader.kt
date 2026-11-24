package com.animeext.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteDomainsConfig(
    val version: Int = 1,
    val cacheTtlSeconds: Long = 3600L,
    val sites: Map<String, RemoteSiteConfig> = emptyMap(),
    val redirectorHosts: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteSiteConfig(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val fallbackUrls: List<String> = emptyList(),
    val language: String = "hi-IN",
    val theme: String = "wordpress",
    val latestPath: String = "/",
    val searchPath: String = "/?s={query}",
    val fallbackSearchPath: String? = null,
    val pagePath: String = "/page/{page}/",
    val supportsEmbeddedPlayer: Boolean = false,
    val enabled: Boolean = true
)

/**
 * Fetches remote `domains.json` so site base URLs can be updated without recompiling `.cs3` files.
 */
object DomainConfigLoader {
    const val REMOTE_DOMAINS_URL =
        "https://raw.githubusercontent.com/wanxtaexe-ai/legendary-octo-fishstick/main/domains.json"

    private val mutex = Mutex()
    private var cachedConfig: RemoteDomainsConfig? = null
    private var lastFetchMs: Long = 0L

    private val defaults = mapOf(
        "lordsanime" to RemoteSiteConfig(
            id = "lordsanime",
            name = "Lord's Anime",
            baseUrl = "https://www.lordsanime.com",
            latestPath = "/",
            searchPath = "/?s={query}",
            pagePath = "/page/{page}/"
        ),
        "tpxsub" to RemoteSiteConfig(
            id = "tpxsub",
            name = "TpXAnime",
            baseUrl = "https://www.tpxsub.com",
            latestPath = "/",
            searchPath = "/?s={query}",
            pagePath = "/page/{page}/"
        ),
        "ybxanime" to RemoteSiteConfig(
            id = "ybxanime",
            name = "YBX Anime",
            baseUrl = "https://ybxanime.com",
            latestPath = "/",
            searchPath = "/?s={query}",
            pagePath = "/page/{page}/"
        ),
        "hindisubbedacademy" to RemoteSiteConfig(
            id = "hindisubbedacademy",
            name = "Hindi Subbed Academy",
            baseUrl = "https://hindisubbedacademy.com",
            latestPath = "/homepage/",
            searchPath = "/?s={query}",
            fallbackSearchPath = "/search/?keyword={query}",
            pagePath = "/anime/page/{page}/",
            supportsEmbeddedPlayer = true
        ),
        "animevilla" to RemoteSiteConfig(
            id = "animevilla",
            name = "AnimeVilla",
            baseUrl = "https://animevilla.org",
            latestPath = "/homepage/",
            searchPath = "/?s={query}",
            fallbackSearchPath = "/search/?keyword={query}",
            pagePath = "/anime/page/{page}/",
            supportsEmbeddedPlayer = true
        )
    )

    suspend fun getSiteConfig(siteId: String): RemoteSiteConfig {
        val remote = getRemoteConfig()
        val site = remote?.sites?.get(siteId) ?: defaults[siteId]
        return site ?: RemoteSiteConfig(id = siteId, baseUrl = "")
    }

    private suspend fun getRemoteConfig(): RemoteDomainsConfig? = mutex.withLock {
        val now = System.currentTimeMillis()
        val existing = cachedConfig
        if (existing != null && (now - lastFetchMs) < existing.cacheTtlSeconds * 1000L) {
            return existing
        }

        val fetched = runCatching {
            val text = app.get(REMOTE_DOMAINS_URL).text
            parseJson<RemoteDomainsConfig>(text)
        }.getOrNull()

        if (fetched != null && fetched.sites.isNotEmpty()) {
            cachedConfig = fetched
            lastFetchMs = now
            return fetched
        }
        return cachedConfig
    }
}
