package com.keyiflerolsun

import java.net.URI

/**
 * The two site snapshots shared the same Rapidrame ID for the same episode.
 * Try the other site's player as a fallback; never rewrite arbitrary hosts or IDs.
 * The player still has to return a real, decoded stream; the URL isn't a stream.
 */
internal object RapidFallback {
    private val idPath = Regex("^/embed-([a-z0-9]{12})/?$", RegexOption.IGNORE_CASE)
    fun playerUrl(embed: String): String? {
        val uri = try { URI(embed) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || !uri.host.equals("rapid.filmmakinesi.to", true) ||
            uri.userInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null
        val id = idPath.matchEntire(uri.path ?: "")?.groupValues?.get(1) ?: return null
        return "https://www.hdfilmcehennemi.nl/rplayer/$id/"
    }
}
