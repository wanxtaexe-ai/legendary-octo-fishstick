package com.animeext.wordpress.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Loads and caches remote `domains.json` so site base URLs can be updated without recompiling.
 * Falls back to a local/bundled JSON provider when offline or unreachable.
 */
class DomainConfigManager(
    private val remoteUrl: String? = null,
    private val localJsonProvider: () -> String,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val mutex = Mutex()
    private var cachedConfig: DomainsConfig? = null
    private var lastLoadedAtMs: Long = 0L
    private val runtimeUrlOverrides = mutableMapOf<String, String>()

    suspend fun load(forceRefresh: Boolean = false): DomainsConfig = mutex.withLock {
        val now = System.currentTimeMillis()
        val existing = cachedConfig
        if (!forceRefresh && existing != null) {
            val ttlMs = existing.cacheTtlSeconds * 1000L
            if (now - lastLoadedAtMs < ttlMs) {
                return existing
            }
        }

        val rawJson = fetchRemoteOrNull() ?: localJsonProvider()
        val parsed = json.decodeFromString<DomainsConfig>(rawJson)
        cachedConfig = parsed
        lastLoadedAtMs = now
        return parsed
    }

    private suspend fun fetchRemoteOrNull(): String? {
        val url = remoteUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
                okHttpClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) res.body?.string() else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun getSiteConfig(siteId: String): SiteConfig {
        val config = load()
        val site = config.sites[siteId]
            ?: throw IllegalArgumentException("Unknown site id: $siteId")
        val override = runtimeUrlOverrides[siteId]
        return if (override != null) site.copy(baseUrl = override) else site
    }

    suspend fun getBaseUrl(siteId: String): String =
        getSiteConfig(siteId).baseUrl.trimEnd('/')

    fun updateBaseUrl(siteId: String, newBaseUrl: String) {
        runtimeUrlOverrides[siteId] = newBaseUrl.trimEnd('/')
    }

    suspend fun getDefaultUserAgent(): String = load().defaultUserAgent

    suspend fun buildLatestUrl(siteId: String, page: Int = 1): String {
        val site = getSiteConfig(siteId)
        val base = site.baseUrl.trimEnd('/')
        if (page <= 1) {
            val path = site.latestPath.let { if (it.startsWith("/")) it else "/$it" }
            return "$base$path"
        }
        val pagePath = site.pagePath.replace("{page}", page.toString())
            .let { if (it.startsWith("/")) it else "/$it" }
        return "$base$pagePath"
    }

    suspend fun buildSearchUrl(
        siteId: String,
        query: String,
        page: Int = 1,
        useFallback: Boolean = false
    ): String {
        val site = getSiteConfig(siteId)
        val base = site.baseUrl.trimEnd('/')
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val template = if (useFallback && !site.fallbackSearchPath.isNullOrBlank()) {
            site.fallbackSearchPath
        } else {
            site.searchPath
        }
        val searchPart = template.replace("{query}", encoded)
            .let { if (it.startsWith("/")) it else "/$it" }

        return when {
            page > 1 && searchPart.startsWith("/?") -> "$base/page/$page$searchPart"
            page > 1 && !searchPart.contains("page=") -> {
                val sep = if (searchPart.contains("?")) "&" else "?"
                "$base$searchPart${sep}page=$page"
            }
            else -> "$base$searchPart"
        }
    }

    companion object {
        fun fromFile(file: File, remoteUrl: String? = null): DomainConfigManager =
            DomainConfigManager(
                remoteUrl = remoteUrl,
                localJsonProvider = { file.readText(StandardCharsets.UTF_8) }
            )
    }
}
