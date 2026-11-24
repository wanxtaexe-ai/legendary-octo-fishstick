package com.animeext.ybxanime

import com.animeext.cloudstream.WordPressBaseProvider
import com.lagradost.cloudstream3.mainPageOf

class YbxAnimeProvider : WordPressBaseProvider(
    siteId = "ybxanime",
    defaultBaseUrl = "https://ybxanime.com",
    name = "YBXAnime"
) {
    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "/category/action/" to "Action",
        "/page/2/" to "Recent Releases",
        "/page/3/" to "Archive"
    )
}
