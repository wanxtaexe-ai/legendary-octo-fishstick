package com.animeext.tpxsub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TpxSubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TpxSubProvider())
    }
}
