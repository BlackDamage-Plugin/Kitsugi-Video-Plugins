// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Base64

class CizgiMax : MainAPI() {
    override var mainUrl              = "https://cizgimax.online"
    override var name                 = "CizgiMax"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Cartoon)

    // ─── Ana sayfa kategorileri ────────────────────────────────────────────────
    // Pagination: ?page=N query param (eski /page/N yolu artık 404 veriyor)
    override val mainPage = mainPageOf(
        "${mainUrl}/yeni-eklenenler/"                 to "Yeni Eklenenler",
        "${mainUrl}/diziler/cizgi-film/"              to "Çizgi Film",
        "${mainUrl}/diziler/anime/"                   to "Anime",
        "${mainUrl}/arsiv/?sort=populer&donem=weekly" to "Popüler",
        "${mainUrl}/tur/aksiyon/"                     to "Aksiyon",
        "${mainUrl}/tur/komedi/"                      to "Komedi",
        "${mainUrl}/tur/macera/"                      to "Macera",
    )

    // ─── Ana sayfa yükleme ─────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep      = if (request.data.contains("?")) "&" else "?"
        val pageUrl  = if (page == 1) request.data else "${request.data}${sep}page=${page}"
        val document = app.get(pageUrl).document
        val home     = document.select("div.film-list div.film-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    // ─── Kart → SearchResponse dönüştürücü ────────────────────────────────────
    // Yeni DOM: div.film-item > div.inner > a.film-name  (title + href)
    //                                      a.poster > img (src)
    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("a.film-name")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a.film-name")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a.poster img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) { this.posterUrl = posterUrl }
    }

    // ─── Arama ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ara/?q=${query}").document
        return document.select("div.film-list div.film-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ─── Detay sayfası ────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1 a.anime-title-link")?.text()?.trim()
                 ?: document.selectFirst("h1")?.text()?.trim()
                 ?: return null

        val poster      = fixUrlNull(document.selectFirst("div.anime-poster img")?.attr("src"))
        val description = document.selectFirst("p.anime-desc")?.text()?.trim()
        val tags        = document.select("a[href*='/tur/']").mapNotNull { it.text().trim().ifEmpty { null } }

        val episodes = parseEpisodes(document)

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
        }
    }

    // Bölümleri sezon panolarından çıkar
    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Her div.ep-grid-numbers bir sezondur; data-season-pane attr'si sezon numarasını taşır
        document.select("div.ep-grid-numbers").forEach { pane ->
            val seasonNum = pane.attr("data-season-pane").toIntOrNull() ?: 1
            pane.select("a.ep-num-btn").forEach { btn ->
                val href  = fixUrlNull(btn.attr("href")) ?: return@forEach
                val epNum = btn.selectFirst("span.ep-num-label")?.text()?.trim()?.toIntOrNull()
                val name  = btn.attr("title").trim().ifEmpty { null }
                episodes.add(newEpisode(href) {
                    this.name    = name
                    this.season  = seasonNum
                    this.episode = epNum
                })
            }
        }

        // Sezon paneli yoksa (tek sezon / film) düz liste olarak al
        if (episodes.isEmpty()) {
            document.select("a.ep-num-btn").forEach { btn ->
                val href  = fixUrlNull(btn.attr("href")) ?: return@forEach
                val epNum = btn.selectFirst("span.ep-num-label")?.text()?.trim()?.toIntOrNull()
                val name  = btn.attr("title").trim().ifEmpty { null }
                episodes.add(newEpisode(href) {
                    this.name    = name
                    this.season  = 1
                    this.episode = epNum
                })
            }
        }

        return episodes
    }

    // ─── Stream bağlantıları ──────────────────────────────────────────────────
    // Bölüm sayfası, sunucu listesini base64-encoded JSON olarak gömer:
    //   var servers = JSON.parse(atob("BASE64"));
    // Her sunucu; type, streamUrl (/api/stream/sibnet/?t=...), label, lang gibi alanlar içerir.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("CZGM", "loadLinks » $data")
        val html = app.get(data).document.html()

        val b64Re  = Regex("""var\s+servers\s*=\s*JSON\.parse\(atob\("([^"]+)"\)\)""")
        val match  = b64Re.find(html)

        if (match != null) {
            runCatching {
                val rawB64     = match.groupValues[1].replace("\\", "")
                val json       = String(Base64.getDecoder().decode(rawB64))
                Log.d("CZGM", "servers » $json")

                val urlRe   = Regex(""""streamUrl"\s*:\s*"([^"]+)"""")
                val labelRe = Regex(""""label"\s*:\s*"([^"]+)"""")
                val urls    = urlRe.findAll(json).map { it.groupValues[1] }.toList()
                val labels  = labelRe.findAll(json).map { it.groupValues[1] }.toList()

                urls.forEachIndexed { i, path ->
                    val fullUrl = fixUrl(path)
                    val label   = labels.getOrElse(i) { "Server ${i + 1}" }
                    Log.d("CZGM", "stream[$i] $label → $fullUrl")

                    val handled = loadExtractor(fullUrl, "$mainUrl/", subtitleCallback, callback)
                    if (!handled) {
                        callback.invoke(
                            newExtractorLink(
                                source  = "$name - $label",
                                name    = "$name - $label",
                                url     = fullUrl,
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }.onFailure { Log.e("CZGM", "servers parse hatası: ${it.message}") }
        } else {
            // Eski iframe fallback (geçiş dönemi için)
            Log.w("CZGM", "base64 servers bulunamadı, iframe fallback deneniyor")
            app.get(data).document.select("ul.linkler li").forEach {
                val iframe = fixUrlNull(it.selectFirst("a")?.attr("data-frame")) ?: return@forEach
                loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }
}
