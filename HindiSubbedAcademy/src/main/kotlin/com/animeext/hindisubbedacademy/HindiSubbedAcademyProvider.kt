package com.animeext.hindisubbedacademy

import com.animeext.cloudstream.WordPressBaseProvider
import com.lagradost.cloudstream3.mainPageOf

class HindiSubbedAcademyProvider : WordPressBaseProvider(
    siteId = "hindisubbedacademy",
    defaultBaseUrl = "https://hindisubbedacademy.com",
    name = "HindiSubbedAcademy"
) {
    override val mainPage = mainPageOf(
        "/homepage/" to "Latest Updates",
        "/status/ongoing/" to "Ongoing",
        "/status/completed/" to "Completed",
        "/status/anime-movies/" to "Movies",
        "/anime/" to "All Anime"
    )
}
