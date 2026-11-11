package com.lagradost.cloudstream3.providers

import android.util.Log
import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl              = "https://www.fullhdfilmizlesene.life"
    override var name                 = "FullHDFilmizlesene"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    private var isDomainResolved = false

    private suspend fun resolveActiveDomain() {
        if (isDomainResolved) return
        val candidates = listOf(
            "https://www.fullhdfilmizlesene.life",
            "https://www.fullhdfilmizlesene.store",
            "https://www.fullhdfilmizlesene.pw"
        )
        for (candidate in candidates) {
            try {
                val res = app.get(
                    candidate,
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"),
                    timeout = 5000L
                )
                if (res.isSuccessful) {
                    val redirectedUrl = res.url.removeSuffix("/")
                    if (redirectedUrl.startsWith("http")) {
                        mainUrl = redirectedUrl
                        isDomainResolved = true
                        return
                    }
                }
            } catch (e: Exception) {
                // Try next
            }
        }
    }

    override val mainPage = mainPageOf(
        "https://www.fullhdfilmizlesene.life/populer-filmler/"                    to "Popüler Filmler",
        "https://www.fullhdfilmizlesene.life/en-cok-izlenen-filmler/"             to "En Çok İzlenen Filmler",
        "https://www.fullhdfilmizlesene.life/filmizle/aile-filmleri/"             to "Aile Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/aksiyon-filmleri/"          to "Aksiyon Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/animasyon-filmleri/"        to "Animasyon Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/belgesel-filmleri/"         to "Belgeseller",
        "https://www.fullhdfilmizlesene.life/filmizle/bilim-kurgu-filmleri/"      to "Bilim Kurgu Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/bluray-filmler/"            to "Blu Ray Filmler",
        "https://www.fullhdfilmizlesene.life/filmizle/cizgi-filmler/"             to "Çizgi Filmler",
        "https://www.fullhdfilmizlesene.life/filmizle/dram-filmleri/"             to "Dram Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/fantastik-filmler/"         to "Fantastik Filmler",
        "https://www.fullhdfilmizlesene.life/filmizle/gerilim-filmleri/"          to "Gerilim Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/gizem-filmleri/"            to "Gizem Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/hint-filmleri/"             to "Hint Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/komedi-filmleri/"           to "Komedi Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/korku-filmleri/"            to "Korku Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/macera-filmleri/"           to "Macera Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/muzikal-filmler/"           to "Müzikal Filmler",
        "https://www.fullhdfilmizlesene.life/filmizle/polisiye-filmleri/"         to "Polisiye Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/psikolojik-filmler/"        to "Psikolojik Filmler",
        "https://www.fullhdfilmizlesene.life/filmizle/romantik-filmler/"          to "Romantik Filmler",
        "https://www.fullhdfilmizlesene.life/filmizle/savas-filmleri/"            to "Savaş Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/suc-filmleri/"              to "Suç Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/tarih-filmleri/"            to "Tarih Filmleri",
        "https://www.fullhdfilmizlesene.life/filmizle/western-filmler/"           to "Western Filmler",
        "https://www.fullhdfilmizlesene.life/filmizle/yerli-filmler/"             to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveActiveDomain()
        val targetData = request.data.replace("https://www.fullhdfilmizlesene.life", mainUrl)
        val url = if (targetData.endsWith("/")) "$targetData$page" else "$targetData/$page"
        val document = app.get(url).document
        var elements = document.select("li.film")
        if (elements.isEmpty()) elements = document.select("a[href*='/filmizle/'], article, .movie-item")
        
        var home = elements.mapNotNull { it.toSearchResult() }

        if (request.name == "IMDB Puanı Yüksek Filmler") {
            home = home.sortedByDescending {
                it.posterHeaders?.get("IMDb")?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull() ?: 0.0
            }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.film-title")?.text() ?: this.attr("title").takeIf { it.isNotBlank() } ?: this.selectFirst("img")?.attr("alt") ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(this.selectFirst("img")?.attr("src"))
        val imdb = this.selectFirst("span.imdb")?.text()?.trim()
        val dil = this.selectFirst("span.trz")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            if (dil?.contains("Dublaj", ignoreCase = true) == true) {
                addDub(1)
            }
            if (dil?.contains("Altyazı", ignoreCase = true) == true || dil?.contains("Sub", ignoreCase = true) == true) {
                addSub(1)
            }
            if (!imdb.isNullOrBlank()) {
                imdb.replace(Regex("[^0-9.]"), "").toDoubleOrNull()?.let { scoreVal ->
                    this.score = Score.from10(scoreVal)
                }
            }
            val headers = mutableMapOf<String, String>()
            if (!imdb.isNullOrBlank()) headers["IMDb"] = imdb
            this.posterHeaders = headers
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveActiveDomain()
        val cleanQuery = query.lowercase().trim()
            .replace(Regex("""\s*(?:filmi|dizisi|filmleri|dizileri|izle)$"""), "")
            .trim()

        val categorySlug = when (cleanQuery) {
            "aksiyon" -> "aksiyon-filmleri"
            "macera" -> "macera-filmleri"
            "korku" -> "korku-filmleri"
            "komedi" -> "komedi-filmleri"
            "gerilim" -> "gerilim-filmleri"
            "dram" -> "dram-filmleri"
            "gizem" -> "gizem-filmleri"
            "fantastik" -> "fantastik-filmler"
            "romantik" -> "romantik-filmler"
            "animasyon" -> "animasyon-filmleri"
            "belgesel" -> "belgesel-filmleri"
            "aile" -> "aile-filmleri"
            "bilim kurgu", "bilimkurgu" -> "bilim-kurgu-filmleri"
            "suç", "suc" -> "suc-filmleri"
            "yerli" -> "yerli-filmler"
            "western" -> "western-filmler"
            "savaş", "savas" -> "savas-filmleri"
            "tarih" -> "tarih-filmleri"
            "polisiye" -> "polisiye-filmleri"
            "psikolojik" -> "psikolojik-filmler"
            "müzikal", "muzikal" -> "muzikal-filmler"
            "hint" -> "hint-filmleri"
            "çizgi film", "cizgi film", "çizgi", "cizgi" -> "cizgi-filmler"
            else -> null
        }

        val url = if (categorySlug != null) {
            "${mainUrl}/filmizle/$categorySlug/"
        } else {
            "${mainUrl}/arama/${query}"
        }

        val document = app.get(url).document
        return document.select("li.film").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        resolveActiveDomain()
        val cleanUrl = if (!url.startsWith(mainUrl)) {
            url.replace(Regex("https?://[^/]+"), mainUrl)
        } else {
            url
        }
        val document = app.get(cleanUrl, cacheTime = 0).document

        val title           = document.selectFirst("div[class=izle-titles]")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("div img")?.attr("data-src"))
        val year            = document.selectFirst("div.dd a.category")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val description     = document.selectFirst("div.ozet-ic > p")?.text()?.trim()
        val tags            = document.select("a[rel='category tag']").map { it.text() }
        val duration        = document.selectFirst("span.sure")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val trailer         = Regex("""embedUrl": "(.*)"""").find(document.html())?.groupValues?.get(1)
        val actors          = document.select("div.film-info ul li:nth-child(2) a > span").map {
            Actor(it.text())
        }


        val recommendations = document.selectXpath("//div[span[text()='Benzer Filmler']]/following-sibling::section/ul/li").mapNotNull {
            val recName      = it.selectFirst("span.film-title")?.text() ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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

    private fun atob(s: String): String {
        return String(Base64.decode(s, Base64.DEFAULT))
    }

    private fun rtt(s: String): String {
        fun rot13Char(c: Char): Char {
            return when (c) {
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                else -> c
            }
        }

        return s.map { rot13Char(it) }.joinToString("")
    }

    private fun getVideoLinks(document: Document): List<Map<String, String>> {
        val scriptElement = document.select("script").firstOrNull { it.data().contains("scx") || it.html().contains("scx") }
        val scriptContent = scriptElement?.data()?.trim()?.takeIf { it.isNotEmpty() }
            ?: scriptElement?.html()?.trim()
            ?: return emptyList()

        val scxData = Regex("""scx\s*=\s*(.*?);""").find(scriptContent)?.groupValues?.get(1) ?: return emptyList()
        val json = org.json.JSONObject(scxData)
        val keys = listOf("atom", "advid", "advidprox", "proton", "fast", "fastly", "tr", "en")
        val linkList = mutableListOf<Map<String, String>>()

        for (key in keys) {
            val keyObj = json.optJSONObject(key) ?: continue
            val sx = keyObj.optJSONObject("sx") ?: continue
            val tObj = sx.opt("t") ?: continue
            
            val decryptedLinks = mutableListOf<String>()
            if (tObj is org.json.JSONArray) {
                for (i in 0 until tObj.length()) {
                    val rawLink = tObj.optString(i)
                    if (rawLink.isNotBlank()) {
                        decryptedLinks.add(atob(rtt(rawLink)))
                    }
                }
            } else if (tObj is org.json.JSONObject) {
                val linksMap = mutableMapOf<String, String>()
                val keysIterator = tObj.keys()
                while (keysIterator.hasNext()) {
                    val k = keysIterator.next()
                    val v = tObj.optString(k)
                    if (v.isNotBlank()) {
                        linksMap[k] = atob(rtt(v))
                    }
                }
                if (linksMap.isNotEmpty()) {
                    linkList.add(linksMap)
                }
                continue
            } else if (tObj is String) {
                decryptedLinks.add(atob(rtt(tObj)))
            }
            
            if (decryptedLinks.isNotEmpty()) {
                linkList.add(mapOf(key to decryptedLinks.joinToString(",")))
            }
        }

        return linkList
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        resolveActiveDomain()
        val cleanData = if (!data.startsWith(mainUrl)) {
            data.replace(Regex("https?://[^/]+"), mainUrl)
        } else {
            data
        }
        Log.d("FHD", "data » $cleanData")
        val document    = app.get(cleanData, cacheTime = 0).document
        val videoLinks = getVideoLinks(document)
        Log.d("FHD", "videoLinks » $videoLinks")
        if (videoLinks.isEmpty()) return false


        for (videoMap in videoLinks) {
            for ((key, value) in videoMap) {
                val videoUrl = fixUrlNull(value) ?: continue
                if (videoUrl.contains("turbo.imgz.me")) {
                    loadExtractor("${key}||${videoUrl}", "${mainUrl}/", subtitleCallback, callback)
                } else {
                    loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
