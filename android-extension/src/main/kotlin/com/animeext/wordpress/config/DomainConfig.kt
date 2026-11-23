package com.animeext.wordpress.config

import kotlinx.serialization.Serializable

@Serializable
data class DomainsConfig(
    val version: Int = 1,
    val updatedAt: String = "",
    val defaultUserAgent: String = DEFAULT_UA,
    val cacheTtlSeconds: Long = 3600L,
    val sites: Map<String, SiteConfig>,
    val directMediaHosts: List<MediaHostRule> = emptyList(),
    val redirectorHosts: List<String> = emptyList()
) {
    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}

@Serializable
data class SiteConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
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

@Serializable
data class MediaHostRule(
    val name: String,
    val type: String,
    val pattern: String
)
