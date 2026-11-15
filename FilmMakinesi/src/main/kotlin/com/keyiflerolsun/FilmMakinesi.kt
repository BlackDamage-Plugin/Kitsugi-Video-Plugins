// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

// TMDB genel (public) API anahtarı — IMDb ID → başlık çözümlemesi için.
private const val TMDB_API_KEY = "c4ffcab48dfaa7b41625ac13d61aec31"


class FilmMakinesi : MainAPI() {
    // ! .to ölürse sırayla denenir; ayakta olan sabitlenir.
    override var mainUrl              = "https://filmmakinesi.to"
    private val domainCandidates      = listOf(
        "https://filmmakinesi.to",
        "https://filmmakinesi.film",
        "https://filmmakinesi.tv",
        "https://filmmakinesi.pw",
        "https://filmmakinesi.net",
        "https://filmmakinesi.de",
        "https://filmmakinesi.com",
    )
    private var domainResolved        = false
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false

    // ! ÖNEMLİ: Site hem film hem yabancı dizi barındırıyor.
    // ! Sadece TvType.Movie bildirildiğinde Kitsugi uygulaması dizi (TvShow)
    // ! aramalarında bu eklentiyi HİÇ ÇAĞIRMIYOR ("Bu eklenti film türlerini
    // ! destekliyor" filtresi) — The Mentalist / Supernatural gibi diziler bu
    // ! yüzden hiç sonuç vermiyordu.
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // ! IMDb ID ile doğrudan içerik açma (Anizium ile aynı davranış).
    // ! Uygulama önce getLoadUrl() dener; başlık aramasındaki yazım
    // ! farklılıklarından (Türkçe/İngilizce isim) etkilenmez.
    override val supportedSyncNames   = setOf(SyncIdName.Imdb)

    // ! CloudFlare bypass
    override var sequentialMainPage            = true // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 50L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 50L  // ? 0.05 saniye

    // ! Yollar göreli: domain sonradan çözülse de doğru host'a gidilir.
    override val mainPage = mainPageOf(
        "/filmler-1/sayfa/"                                to "Son Filmler",
        "/film-izle/olmeden-izlenmesi-gerekenler-fm1/sayfa/" to "Ölmeden İzle",
        "/tur/aksiyon-fm1/film/sayfa/"                       to "Aksiyon",
        "/tur/bilim-kurgu-fm2/film/sayfa/"                   to "Bilim Kurgu",
        "/tur/macera-fm1/film/sayfa/"                        to "Macera",
        "/tur/komedi-fm1/film/sayfa/"                        to "Komedi",
        "/tur/romantik-fm1/film/sayfa/"                      to "Romantik",
        "/tur/belgesel/film/sayfa/"                          to "Belgesel",
        "/tur/fantastik-fm1/film/sayfa/"                     to "Fantastik",
        "/tur/polisiye/film/sayfa/"                          to "Polisiye Suç",
        "/tur/korku-fm1/film/sayfa/"                         to "Korku",
        // "${mainUrl}/tur/savas/film/sayfa/"                      to "Tarihi ve Savaş",
        // "${mainUrl}/film-izle/gerilim-filmleri-izle/sayfa/"     to "Gerilim Heyecan",
        // "${mainUrl}/film-izle/gizemli/sayfa/"                   to "Gizem",
        // "${mainUrl}/film-izle/aile-filmleri/sayfa/"             to "Aile",
        // "${mainUrl}/film-izle/animasyon-filmler/sayfa/"         to "Animasyon",
        // "${mainUrl}/film-izle/western/sayfa/"                   to "Western",
        // "${mainUrl}/film-izle/biyografi/sayfa/"                 to "Biyografik",
        // "${mainUrl}/film-izle/dram/sayfa/"                      to "Dram",
        // "${mainUrl}/film-izle/muzik/sayfa/"                     to "Müzik",
        // "${mainUrl}/film-izle/spor/sayfa/"                      to "Spor"
    )

    private suspend fun resolveDomain() {
        if (domainResolved) return
        // Do not spend the application's 15-second search budget serially probing mirrors.
        withTimeoutOrNull(1500L) { probeDomains() }
        domainResolved = true
    }

    private suspend fun probeDomains() {
        for (candidate in domainCandidates) {
            try {
                val res = app.get(
                    candidate,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$candidate/",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                    ),
                    interceptor = FilmMakinesiHttp.interceptor,
                    cacheTime = 60
                )
                if (!res.isSuccessful) continue
                val pinned = originOf(res.url) ?: continue
                if (!siteLooksAlive(
                        pinned,
                        res.text,
                        "filmmakinesi",
                        listOf("film-list", "item-relative", "data-title", "filmler")
                    )
                ) continue
                mainUrl = pinned
                domainResolved = true
                Log.d("FLMM", "domain sabitlendi: $mainUrl")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("FLMM", "domain adayi olmadi: $candidate (${e.message})")
            }
        }
        Log.w("FLMM", "hicbir domain ayakta degil, varsayilan kaldi: $mainUrl")
    }

    /** Eski domaindeki kayıtlı adresi çözülen host'a taşır. CloseLoad vb. dış host'lara dokunmaz. */
    private fun migrateUrl(url: String): String {
        val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        if (!host.contains("filmmakinesi")) return url
        return try {
            updateUrl(url)
        } catch (e: Exception) {
            url
        }
    }

    private fun originOf(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            val scheme = uri.scheme ?: "https"
            val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (e: Exception) {
            null
        }
    }

    private fun siteLooksAlive(finalUrl: String, body: String, marker: String, required: List<String>): Boolean {
        val host = runCatching { java.net.URI(finalUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        if (!host.contains(marker)) return false
        val lower = body.lowercase()
        val dead = listOf(
            "yasal nedenlerle",
            "unavailable for legal reasons",
            "domain for sale",
            "buy this domain",
            "is for sale",
            "godaddy",
            "default web site page",
            "cgi-sys/defaultwebpage",
        )
        if (dead.any { lower.contains(it) }) return false
        if (body.length < 300) return false
        return required.any { lower.contains(it) }
    }

    private fun absoluteUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return mainUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        val cleanedUrl = absoluteUrl(request.data).removeSuffix("/")
        val url = if (page > 1) {
            "$cleanedUrl/$page"
        } else {
            cleanedUrl.replace(Regex("/sayfa/?$"), "")
        }

        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            ),
            interceptor = FilmMakinesiHttp.interceptor
        ).document

    val home = document.select("div.film-list div.item-relative")
        .mapNotNull { it.toSearchResult() }

    Log.d("FLMM", "Toplam film: ${home.size}")
    return newHomePageResponse(request.name, home)
}

private fun Element.toSearchResult(): SearchResponse? {
    val aTag = selectFirst("a.item") ?: return null
    val title = aTag.attr("data-title").takeIf { it.isNotBlank() }
        ?: aTag.attr("title").takeIf { it.isNotBlank() }
        ?: aTag.text().trim().takeIf { it.isNotBlank() }
        ?: return null
    val href = fixUrlNull(aTag.attr("href"))?.let { migrateUrl(it) } ?: return null
    val img = aTag.selectFirst("img")
    val posterUrl = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))

    Log.d("FLMM", "Film: $title, Href: $href, Poster: $posterUrl")

    // ! Site dizileri /dizi/... adresinde yayınlıyor. Önceden her sonuç
    // ! TvType.Movie olarak dönüyordu; uygulama dizi aramasında bu kayıtları
    // ! "film" sanıp eliyordu.
    return if (href.contains("/dizi/")) {
        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    } else {
        newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}
    private fun Element.toRecommendResult(): SearchResponse? {
        val title     = this.select("a").last()?.text() ?: return null
        val href      = fixUrlNull(this.select("a").last()?.attr("href")) ?: return null
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))

        return newMovieSearchResponse(title, migrateUrl(href), TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveDomain()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = app.get(
            "${mainUrl}/arama/?s=$encoded",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            ),
            interceptor = FilmMakinesiHttp.interceptor
        ).document

        return document.select("div.film-list div.item-relative").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ── IMDb ID → içerik sayfası ─────────────────────────────────────────────
    // Uygulama "video izle" akışında önce eklentinin supportedSyncNames
    // bildirimine bakıp getLoadUrl(Imdb, "tt1234567") çağırıyor. Burada IMDb
    // kimliği TMDB üzerinden başlığa çevrilir, site kendi aramasıyla eşleştirilir
    // ve doğrudan içerik sayfasının adresi döndürülür. Böylece başlık yazım
    // farkları (Türkçe/İngilizce ad) yüzünden veri gelmemesi engellenir.
    private data class TmdbItem(
        @JsonProperty("title")          val title: String?         = null,
        @JsonProperty("name")           val name: String?          = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name")  val originalName: String?  = null
    )

    private data class TmdbFind(
        @JsonProperty("movie_results") val movieResults: List<TmdbItem>? = null,
        @JsonProperty("tv_results")    val tvResults: List<TmdbItem>?    = null
    )

    private suspend fun tmdbTitlesByImdb(imdbId: String): List<String> {
        val body = app.get(
            "https://api.themoviedb.org/3/find/$imdbId" +
                "?api_key=$TMDB_API_KEY&external_source=imdb_id&language=tr-TR"
        ).text
        val parsed  = AppUtils.tryParseJson<TmdbFind>(body) ?: return emptyList()
        val entries = parsed.movieResults.orEmpty() + parsed.tvResults.orEmpty()
        return entries
            .flatMap { listOfNotNull(it.title, it.originalTitle, it.name, it.originalName) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun titleScore(a: String, b: String): Double {
        val x = normalizeTitle(a)
        val y = normalizeTitle(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.85
        return 0.0
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        if (name != SyncIdName.Imdb || id.isBlank()) return null
        val titles = try { tmdbTitlesByImdb(id) } catch (e: Exception) { emptyList() }
        for (title in titles) {
            val results = try { search(title) } catch (e: Exception) { emptyList() }
            val best = results
                .map { it to titleScore(title, it.name) }
                .filter { it.second >= 0.7 }
                .maxByOrNull { it.second } ?: continue
            return best.first.url
        }
        return null
    }

    /** /dizi/... sayfalarındaki bölüm bağlantılarını ayrıştırır. */
    private fun parseSeriesEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        return document.select("a[href*='bolum'], div.episodes a, div.partlar a")
            .mapNotNull { el ->
                val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                if (!href.contains("bolum") && !href.contains("/dizi/")) return@mapNotNull null
                val epTitle = el.text().trim().ifBlank { el.attr("title").trim() }
                val season  = Regex("""(\d+)\.\s*Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                // ! Genel sayı yakalama KULLANILMIYOR: "2. Sezon" başlığındaki 2'yi
                // ! bölüm numarası sanıp yanlış eşleşmeye yol açıyordu.
                val episode = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(href) {
                    this.name    = epTitle.ifBlank { "Bölüm" }
                    this.season  = season
                    this.episode = episode ?: 1
                }
            }
            .distinctBy { it.data }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveDomain()
        val pageUrl = migrateUrl(url)
        val document = app.get(
            pageUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            ),
            interceptor = FilmMakinesiHttp.interceptor
        ).document

        // ! Dizi sayfası mı? /dizi/ adresi veya bölüm bağlantıları varsa dizi.
        val isSeries = url.contains("/dizi/") || parseSeriesEpisodes(document).isNotEmpty()
        if (isSeries) {
            val seriesTitle = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("[property='og:title']")?.attr("content")?.trim()
                ?: return null
            val seriesPoster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
            val seriesPlot   = document.selectFirst("[property='og:description']")
                ?.attr("content")?.trim()
                ?: document.select("div.info-description p").last()?.text()?.trim()
            val seriesYear   = document.selectFirst("dt:contains(Yapım Yılı:) + dd")
                ?.text()?.trim()?.toIntOrNull()
            val episodes     = parseSeriesEpisodes(document)
            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(seriesTitle, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = seriesPoster
                this.plot      = seriesPlot
                this.year      = seriesYear
            }
        }

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description     = document.select("div.info-description p").last()?.text()?.trim()
        val tags            = document.selectFirst("dt:contains(Tür:) + dd")?.text()?.split(", ")
        val year            = document.selectFirst("dt:contains(Yapım Yılı:) + dd")?.text()?.trim()?.toIntOrNull()

        val durationElement = document.select("dt:contains(Film Süresi:) + dd time").attr("datetime")
        // ? ISO 8601 süre formatını ayrıştırma (örneğin "PT129M")
        val duration        = if (durationElement.startsWith("PT") && durationElement.endsWith("M")) {
            durationElement.drop(2).dropLast(1).toIntOrNull() ?: 0
        } else {
            0
        }

        val recommendations = document.select("div.film-list div.item-relative").mapNotNull { it.toRecommendResult() }
        val actors          = document.selectFirst("dt:contains(Oyuncular:) + dd")?.text()?.split(", ")?.map {
            Actor(it.trim())
        }

        val trailer = document.selectFirst("div.left a.trailer-button")?.attr("data-video_url")?.substringAfter("embed/", "")?.let { 
    if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null 
}

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }


    /**
     * Video kaynakları.
     *
     * Sayfadaki iki oynatıcı (Close = closeload, Rapid = rapidrame) aynı anda
     * çözülür. Her oynatıcı sayfasında video adresi karartılmış JS ile
     * üretilir; [PlayerResolver]/[PlayerDecoder] sitenin kendi çözücüsünü
     * çalıştırıp jwplayer `sources` değerini alır (tuzak adres elenir).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()
        val pageUrl = migrateUrl(data)
        val document = app.get(
            pageUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            ),
            interceptor = FilmMakinesiHttp.interceptor,
            timeout = 20L
        ).document

        val sources = SourceLinks.collect(document, pageUrl)
        Log.d("FLMM", "kaynaklar (${sources.size}): " + sources.joinToString(" | ") { "${it.label} -> ${it.url}" })
        if (sources.isEmpty()) return false

        val players = sources.filter { it.kind != SourceLinks.Kind.OTHER }
        // Known Rapidrame IDs can also be tried on HDFilmCehennemi's player.
        // Run both paths concurrently: a stalled original must not block fallback.
        data class Attempt(val key: String, val url: String, val referer: String, val label: String, val userAgent: String? = null)
        val attempts = players.flatMap { src ->
            val original = Attempt(src.url, src.url, pageUrl, src.label)
            val alternative = if (src.kind == SourceLinks.Kind.RAPID) RapidFallback.playerUrl(src.url) else null
            val hdClose = if (src.kind == SourceLinks.Kind.CLOSE) {
                val rapid = players.filter { it.kind == SourceLinks.Kind.RAPID }.singleOrNull()
                if (players.count { it.kind == SourceLinks.Kind.CLOSE } == 1 && rapid != null)
                    CloseFallback.playerUrl(src.url, rapid.url) else null
            } else null
            listOfNotNull(original,
                Attempt(src.url, src.url, pageUrl, src.label, "Mozilla/5.0"),
                alternative?.let { Attempt(src.url, it, "https://www.hdfilmcehennemi.nl/", "${src.label} (HDFilmCehennemi)") },
                hdClose?.let { Attempt(src.url, it, "https://www.hdfilmcehennemi.nl/", "${src.label} (HDFilmCehennemi)", "Mozilla/5.0") }
            )
        }
        val seenSubtitles = HashSet<String>()
        val deliveredSources = HashSet<String>()
        var found = collectPlayerResults(attempts, 12_000L, resolve = { attempt ->
            PlayerResolver.resolve(attempt.url, attempt.referer, attempt.label, FilmMakinesiHttp.interceptor, attempt.userAgent)
                ?.let { attempt.key to it }
        }, emit = { (key, result) ->
            if (deliveredSources.add(key)) {
                result.subtitles.forEach { if (seenSubtitles.add(it.url)) subtitleCallback(it) }
                callback(result.link)
            }
        })

        // Tanınmayan barındırıcılar için CloudStream'in genel çözücüleri.
        for (src in sources.filter { it.kind == SourceLinks.Kind.OTHER }) {
            try {
                if (loadExtractor(src.url, pageUrl, subtitleCallback) { link -> found = true; callback(link) }) found = true
            } catch (e: Exception) {
                Log.w("FLMM", "genel çözücü başarısız: ${src.url} (${e.message})")
            }
        }
        return found
    }
}
