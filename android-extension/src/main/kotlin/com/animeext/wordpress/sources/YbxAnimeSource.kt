package com.animeext.wordpress.sources

import com.animeext.wordpress.config.DomainConfigManager
import com.animeext.wordpress.http.ExtensionHttpClient
import com.animeext.wordpress.parser.WordPressSource

class YbxAnimeSource(
    configManager: DomainConfigManager,
    httpClient: ExtensionHttpClient
) : WordPressSource("ybxanime", configManager, httpClient)
