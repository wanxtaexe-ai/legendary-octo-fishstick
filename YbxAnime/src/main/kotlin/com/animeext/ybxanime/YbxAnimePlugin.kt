package com.animeext.ybxanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YbxAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YbxAnimeProvider())
    }
}
