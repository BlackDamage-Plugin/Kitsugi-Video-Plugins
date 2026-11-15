package com.keyiflerolsun

import org.jsoup.nodes.Document

/** Bölüm listesi: sitedeki `a.item-ep` ve `/sezon-N/bolum-M/` adresi. */
internal object SeriesEpisodes {
    data class Ref(val href: String, val name: String, val season: Int, val episode: Int)

    private val PATH = Regex("""/sezon-(\d+)/bolum-(\d+)/?""")

    fun list(document: Document): List<Ref> {
        val items = document.select("a.item-ep[href]").ifEmpty {
            document.select("a[href*='/sezon-'][href*='/bolum-']")
        }
        val out = LinkedHashMap<String, Ref>()
        for (el in items) {
            val href = el.attr("href").trim()
            val match = PATH.find(href) ?: continue
            val season = match.groupValues[1].toInt()
            val episode = match.groupValues[2].toInt()
            val name = el.selectFirst(".ep-title")?.text()?.trim().orEmpty()
                .ifBlank { el.selectFirst(".ep-details span, .ep-details")?.text()?.trim().orEmpty() }
                .ifBlank { "$season. Sezon / $episode. Bölüm" }
            out.putIfAbsent(href, Ref(href, name, season, episode))
        }
        return out.values.toList()
    }
}
