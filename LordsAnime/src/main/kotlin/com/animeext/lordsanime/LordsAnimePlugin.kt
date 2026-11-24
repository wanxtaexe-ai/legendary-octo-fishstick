package com.animeext.lordsanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LordsAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LordsAnimeProvider())
    }
}
