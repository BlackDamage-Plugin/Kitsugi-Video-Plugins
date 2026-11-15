package com.keyiflerolsun

/** The Close embed host can return 404 to a desktop UA while serving a player to a minimal UA. */
internal fun playerPageUserAgent(url: String): String {
    val host = try { java.net.URI(url).host?.lowercase() } catch (_: Exception) { null }
    return if (host == "hdfilmcehennemi.mobi") "Mozilla/5.0" else PlayerResolver.USER_AGENT
}
