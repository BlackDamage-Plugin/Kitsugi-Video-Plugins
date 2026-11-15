package com.keyiflerolsun

/**
 * CloseLoad / Rapidrame oynatıcı sayfalarının host ve kimlik seçimi.
 *
 * Close (`hdfilmcehennemi.mobi`, `closeload.*`) masaüstü UA ile 404,
 * kısa `Mozilla/5.0` ile oynatıcı veriyor. Rapidrame tersi.
 * Çoklu UA/Referer denemesi uygulamanın 15 sn sınırında Close'u kesiyordu.
 */
internal object PlayerFetch {
    const val SHORT_USER_AGENT = "Mozilla/5.0"

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = java.net.URI(url)
            val host = uri.host
            if (host.isNullOrBlank()) {
                null
            } else {
                val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
                "${uri.scheme ?: "https"}://$host$port"
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isCloseLike(url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty() || host.contains("rapid")) return false
        return host == "hdfilmcehennemi.mobi" || host.endsWith(".mobi") ||
            host.contains("closeload") || host.contains("close")
    }

    fun userAgentFor(url: String): String =
        if (isCloseLike(url)) SHORT_USER_AGENT else PlayerResolver.USER_AGENT
}
