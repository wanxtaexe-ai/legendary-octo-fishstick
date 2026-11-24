package com.animeext.wordpress.sources

import com.animeext.wordpress.config.DomainConfigManager
import com.animeext.wordpress.http.ExtensionHttpClient
import com.animeext.wordpress.parser.WordPressSource

class SourceRegistry(
    private val configManager: DomainConfigManager,
    private val httpClient: ExtensionHttpClient = ExtensionHttpClient()
) {
    private val sources: Map<String, WordPressSource> = linkedMapOf(
        "lordsanime" to LordsAnimeSource(configManager, httpClient),
        "tpxsub" to TpxSubSource(configManager, httpClient),
        "ybxanime" to YbxAnimeSource(configManager, httpClient),
        "hindisubbedacademy" to HindiSubbedAcademySource(configManager, httpClient),
        "animevilla" to AnimeVillaSource(configManager, httpClient)
    )

    fun getSource(siteId: String): WordPressSource =
        sources[siteId] ?: throw IllegalArgumentException("Unsupported source id: $siteId")

    fun getAllSources(): List<WordPressSource> = sources.values.toList()
}
