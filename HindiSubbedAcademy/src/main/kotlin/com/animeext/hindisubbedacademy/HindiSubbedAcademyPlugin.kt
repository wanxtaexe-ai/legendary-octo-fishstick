package com.animeext.hindisubbedacademy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HindiSubbedAcademyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HindiSubbedAcademyProvider())
    }
}
