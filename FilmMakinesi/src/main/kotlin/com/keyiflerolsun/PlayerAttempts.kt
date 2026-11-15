package com.keyiflerolsun

/** Plan requests only from the embeds actually present on the FilmMakinesi page. */
internal object PlayerAttempts {
    data class Attempt(val key: String, val url: String, val referer: String, val label: String, val userAgent: String?)

    fun forSources(sources: List<SourceLinks.Source>, pageUrl: String): List<Attempt> = sources
        .filter { it.kind != SourceLinks.Kind.OTHER && !SourceLinks.isOtherProvider(it.url) }
        .distinctBy { it.url }
        .flatMap { source ->
            listOf(
                Attempt(source.url, source.url, pageUrl, source.label, null),
                Attempt(source.url, source.url, pageUrl, source.label, "Mozilla/5.0")
            )
        }
}
