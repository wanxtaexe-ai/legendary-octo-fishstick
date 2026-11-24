package com.animeext.animevilla

import com.animeext.cloudstream.WordPressBaseProvider
import com.lagradost.cloudstream3.mainPageOf

class AnimeVillaProvider : WordPressBaseProvider(
    siteId = "animevilla",
    defaultBaseUrl = "https://animevilla.org",
    name = "AnimeVilla"
) {
    override val mainPage = mainPageOf(
        "/homepage/" to "Latest Updates",
        "/status/ongoing/" to "Ongoing",
        "/status/completed/" to "Completed",
        "/status/anime-movies/" to "Movies",
        "/anime/" to "All Anime"
    )
}
