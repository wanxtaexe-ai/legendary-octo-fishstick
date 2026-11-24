package com.animeext.lordsanime

import com.animeext.cloudstream.WordPressBaseProvider
import com.lagradost.cloudstream3.mainPageOf

class LordsAnimeProvider : WordPressBaseProvider(
    siteId = "lordsanime",
    defaultBaseUrl = "https://www.lordsanime.com",
    name = "LordsAnime"
) {
    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "/category/hindisubbed/" to "Hindi Subbed",
        "/page/2/" to "Recent Releases",
        "/page/3/" to "Archive"
    )
}
