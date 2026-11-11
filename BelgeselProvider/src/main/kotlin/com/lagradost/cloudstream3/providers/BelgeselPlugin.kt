package com.lagradost.cloudstream3.providers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BelgeselPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BelgeselProvider())
    }
}
