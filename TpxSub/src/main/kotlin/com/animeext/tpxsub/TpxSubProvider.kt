package com.animeext.tpxsub

import com.animeext.cloudstream.WordPressBaseProvider
import com.lagradost.cloudstream3.mainPageOf

class TpxSubProvider : WordPressBaseProvider(
    siteId = "tpxsub",
    defaultBaseUrl = "https://www.tpxsub.com",
    name = "TpXAnime"
) {
    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "/category/anime/" to "Anime Series",
        "/page/2/" to "Recent Releases",
        "/page/3/" to "More Anime",
        "/page/4/" to "Archive"
    )
}
