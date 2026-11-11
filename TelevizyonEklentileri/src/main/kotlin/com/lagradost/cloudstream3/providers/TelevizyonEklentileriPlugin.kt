package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TelevizyonEklentileriPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CanlitvProvider())
        registerMainAPI(DDizi())
    }
}
