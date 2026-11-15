package com.keyiflerolsun

import java.net.URI

/** Only pair the two explicit player IDs supplied by the same page. */
internal object CloseFallback {
    private val closePath = Regex("^/video/embed/([a-zA-Z0-9]{11})/?$")
    fun playerUrl(close: String, rapid: String): String? {
        val uri = try { URI(close) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || !uri.host.equals("closeload.filmmakinesi.to", true) ||
            uri.userInfo != null || uri.port != -1 || uri.rawFragment != null) return null
        val query = uri.rawQuery ?: return null
        if (!query.matches(Regex("imdb_id=tt[0-9]+"))) return null
        val id = closePath.matchEntire(uri.path ?: "")?.groupValues?.get(1) ?: return null
        val rapidUrl = RapidFallback.playerUrl(rapid) ?: return null
        val rapidId = rapidUrl.trimEnd('/').substringAfterLast('/')
        return "https://hdfilmcehennemi.mobi/video/embed/$id/?rapidrame_id=$rapidId"
    }
}
