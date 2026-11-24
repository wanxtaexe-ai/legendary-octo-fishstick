package com.animeext.wordpress.sources

import com.animeext.wordpress.config.DomainConfigManager
import com.animeext.wordpress.http.ExtensionHttpClient
import com.animeext.wordpress.parser.WordPressSource

class HindiSubbedAcademySource(
    configManager: DomainConfigManager,
    httpClient: ExtensionHttpClient
) : WordPressSource("hindisubbedacademy", configManager, httpClient)
