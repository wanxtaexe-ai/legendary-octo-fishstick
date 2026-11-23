package com.animeext.wordpress.sources

import com.animeext.wordpress.config.DomainConfigManager
import com.animeext.wordpress.http.ExtensionHttpClient
import com.animeext.wordpress.parser.WordPressSource

class LordsAnimeSource(
    configManager: DomainConfigManager,
    httpClient: ExtensionHttpClient
) : WordPressSource("lordsanime", configManager, httpClient)
