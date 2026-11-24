package com.animeext.animevilla

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeVillaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeVillaProvider())
    }
}
