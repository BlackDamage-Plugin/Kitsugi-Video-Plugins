package com.keyiflerolsun

/** Plan requests only from the embeds actually present on the FilmMakinesi page. */
internal object PlayerAttempts {
    data class Attempt(val key: String, val url: String, val referer: String, val label: String, val userAgent: String?)

    fun forSources(sources: List<SourceLinks.Source>, pageUrl: String): List<Attempt> = sources
        .filter { it.kind != SourceLinks.Kind.OTHER && !SourceLinks.isOtherProvider(it.url) }
        .distinctBy { it.url }
        .map { source ->
            // CloseLoad serves the player to a short UA and 404s a desktop UA.
            // Rapidrame does the opposite on the same family of hosts.
            val userAgent = if (source.kind == SourceLinks.Kind.CLOSE) "Mozilla/5.0" else null
            Attempt(source.url, source.url, pageUrl, source.label, userAgent)
        }
}
