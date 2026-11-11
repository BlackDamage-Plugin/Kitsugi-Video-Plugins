package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

class BelgeselProvider : MainAPI() {
    override var mainUrl = "https://www.dmax.com.tr"
    val tlcUrl = "https://www.tlctv.com.tr"
    val dmaxUrl = "https://www.dmax.com.tr"
    val bXUrl = "https://belgeselx.com"
    
    // version bump
    override var name = "Belgesel & Yaşam (AIO)"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Documentary)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    override val mainPage = mainPageOf(
        // DMAX (7 Adet)
        "$dmaxUrl/kesfet?size=500" to "DMAX: Öne Çıkanlar",
        "$dmaxUrl/kesfet/a-z?size=500" to "DMAX: A-Z",
        "$dmaxUrl/kesfet/zorlu-isler?size=500" to "DMAX: Zorlu İşler",
        "$dmaxUrl/kesfet/dogayla-ic-ice?size=500" to "DMAX: Doğayla İç İçe",
        "$dmaxUrl/kesfet/turbo?size=500" to "DMAX: Turbo",
        "$dmaxUrl/kesfet/belgesel?size=500" to "DMAX: Belgesel",
        "$dmaxUrl/kesfet/nasil-yapiliyor?size=500" to "DMAX: Nasıl Yapılıyor?",

        // TLC (8 Adet)
        "$tlcUrl/kesfet?size=500" to "TLC: Öne Çıkanlar",
        "$tlcUrl/kesfet/a-z?size=500" to "TLC: A-Z",
        "$tlcUrl/kesfet/sira-disi-hayatlar?size=500" to "TLC: Sıra Dışı Hayatlar",
        "$tlcUrl/kesfet/ev-dekorasyon?size=500" to "TLC: Ev & Dekorasyon",
        "$tlcUrl/kesfet/suc-arastirma?size=500" to "TLC: Suç & Araştırma",
        "$tlcUrl/kesfet/yasam?size=500" to "TLC: Yaşam",
        "$tlcUrl/kesfet/evlilik?size=500" to "TLC: Evlilik",
        "$tlcUrl/kesfet/yemek?size=500" to "TLC: Yemek",

        // BELGESELX - TRT BELGESEL (1 Adet)
        "$bXUrl/belgeselkanali/trt-belgesel" to "BX: TRT Belgesel",

        // BELGESELX - TÜRLER (10 Adet)
        "$bXUrl/konu/turk-tarihi-belgeselleri" to "BX Tür: Türk Tarihi",
        "$bXUrl/konu/tarih-belgeselleri" to "BX Tür: Tarih",
        "$bXUrl/konu/bilim-belgeselleri" to "BX Tür: Bilim & Teknoloji",
        "$bXUrl/konu/doga-belgeselleri" to "BX Tür: Doğa",
        "$bXUrl/konu/hayvan-belgeselleri" to "BX Tür: Vahşi Yaşam",
        "$bXUrl/konu/polisiye-belgeselleri" to "BX Tür: Suç & Polisiye",
        "$bXUrl/konu/seyehat-belgeselleri" to "BX Tür: Seyahat & Dünya",
        "$bXUrl/konu/muhendislik-belgeselleri" to "BX Tür: Mühendislik",
        "$bXUrl/konu/sanat-belgeselleri" to "BX Tür: Sanat & Kültür",
        "$bXUrl/konu/psikoloji-belgeselleri" to "BX Tür: Psikoloji"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val items = mutableListOf<SearchResponse>()
        var hasNext = false

        try {
            when {
                url.contains("tlctv") || url.contains("dmax") -> {
                    val currentBase = if (url.contains("tlctv")) tlcUrl else dmaxUrl
                    val doc = if (page == 1) {
                        app.get(url, headers = defaultHeaders, cacheTime = 0).document
                    } else {
                        val cleanSlug = url.substringBefore("?").removeSuffix("/").split("/").last()
                        val targetSlug = if (cleanSlug == "kesfet") "" else cleanSlug
                        app.post(
                            "$currentBase/ajax/more",
                            headers = defaultHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url),
                            data = mapOf("type" to "discover", "slug" to targetSlug, "page" to page.toString())
                        ).document
                    }

                    doc.select(".poster").forEach { el ->
                        if (page == 1 && el.parents().any { it.hasClass("owl-carousel") }) return@forEach

                        val linkEl = el.selectFirst("a") ?: return@forEach
                        val href = linkEl.attr("href") ?: return@forEach
                        if (href.startsWith("/kesfet/") || href == "#" || href.contains("javascript:")) return@forEach

                        val slug = href.removeSuffix("/").split("/").lastOrNull() ?: ""
                        val title = el.attr("title").takeIf { !it.isNullOrBlank() }
                            ?: linkEl.attr("title").takeIf { !it.isNullOrBlank() }
                            ?: el.selectFirst("img")?.attr("alt").takeIf { !it.isNullOrBlank() }
                            ?: slug.replace("-", " ").capitalizeWords()

                        val img = el.selectFirst("img")
                        val poster = img?.attr("data-src").takeIf { !it.isNullOrBlank() } ?: img?.attr("src")

                        val cleanPoster = poster?.let { fix(it, currentBase) }

                        if (title.isNotBlank()) {
                            items.add(newAnimeSearchResponse(title, fix(href, currentBase), TvType.Documentary) {
                                this.posterUrl = cleanPoster
                                this.posterHeaders = defaultHeaders + mapOf("Referer" to currentBase)
                            })
                        }
                    }
                    hasNext = items.isNotEmpty()
                }

                url.contains("belgeselx") -> {
                    val targetUrl = if (page > 1) {
                        val cleanSlug = url.substringBefore("?").removeSuffix("/").split("/").last()
                        if (url.contains("/konu/")) {
                            "$bXUrl/ajax_konukat.php?url=$cleanSlug&page=$page"
                        } else if (url.contains("/belgeselkanali/")) {
                            "$bXUrl/ajax_belgeselkat.php?url=$cleanSlug&page=$page"
                        } else {
                            return newHomePageResponse(request.name, emptyList(), false)
                        }
                    } else {
                        url
                    }
                    val res = app.get(targetUrl, headers = defaultHeaders)
                    
                    if (res.isSuccessful) {
                        val doc = res.document
                        val seenUrls = mutableSetOf<String>()
                        
                        for (el in doc.select("a.px-card")) {
                            val href = el.attr("href") ?: continue
                            if (href == "/" || href == bXUrl || href == "$bXUrl/") continue
    
                            val fullUrl = fix(href, bXUrl)
                            if (fullUrl in seenUrls) continue
                            
                            val title = el.selectFirst(".px-card-title, .px-title, h3, h4, .title")?.text()?.trim()
                                ?: el.selectFirst("img")?.attr("alt")?.trim()
                                ?: el.attr("title").trim().takeIf { it.isNotBlank() }
                                ?: continue
                            
                            if (title.length < 2 || title.length > 200) continue
                            
                            val rawPoster = el.selectFirst("img.px-card-img")?.let { img -> img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src") }
                                ?: el.selectFirst("img")?.let { img -> img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src") }
                            
                            val cleanPoster = rawPoster?.let { fix(it, bXUrl) }?.let { 
                                if (it.endsWith("jpeg", true) && !it.contains(".jpeg", true)) it.replace("jpeg", ".jpg", true) else it 
                            }
                            
                            seenUrls.add(fullUrl)
                            items.add(newAnimeSearchResponse(title.trim(), fullUrl, TvType.Documentary) {
                                this.posterUrl = cleanPoster ?: "$bXUrl/images/noimage.jpg"
                                this.posterHeaders = defaultHeaders + mapOf("Referer" to bXUrl)
                            })
                        }
                        
                        hasNext = items.isNotEmpty()
                    } else {
                        hasNext = false
                    }
                }
            }
        } catch (_: Exception) {}

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext)
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { 
        it.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } 
    }

    private fun fix(href: String, baseUrl: String): String {
        return when {
            href.startsWith("http") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "${baseUrl.removeSuffix("/")}$href"
            else -> "${baseUrl.removeSuffix("/")}/$href"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.lowercase().trim()
            .replace(Regex("""\s*(?:belgeseli|belgeselleri|izle)$"""), "")
            .trim()

        val categoryUrl = when (cleanQuery) {
            "zorlu işler", "zorlu isler", "zorlu" -> "$dmaxUrl/kesfet/zorlu-isler?size=500"
            "doğa", "doga", "doğayla iç içe", "dogayla ic ice" -> "$dmaxUrl/kesfet/dogayla-ic-ice?size=500"
            "turbo", "araba", "motor" -> "$dmaxUrl/kesfet/turbo?size=500"
            "nasıl yapılıyor", "nasil yapiliyor", "nasılyapılıyor" -> "$dmaxUrl/kesfet/nasil-yapiliyor?size=500"
            "sıra dışı", "sira disi", "sıradışı" -> "$tlcUrl/kesfet/sira-disi-hayatlar?size=500"
            "ev", "dekorasyon", "tasarım", "tasarim" -> "$tlcUrl/kesfet/ev-dekorasyon?size=500"
            "suç", "suc", "suç araştırma", "suc arastirma" -> "$tlcUrl/kesfet/suc-arastirma?size=500"
            "yaşam", "yasam" -> "$tlcUrl/kesfet/yasam?size=500"
            "evlilik", "gelin" -> "$tlcUrl/kesfet/evlilik?size=500"
            "yemek", "mutfak", "şef", "sef" -> "$tlcUrl/kesfet/yemek?size=500"
            "türk tarihi", "turk tarihi" -> "$bXUrl/konu/turk-tarihi-belgeselleri"
            "tarih", "tarihi" -> "$bXUrl/konu/tarih-belgeselleri"
            "bilim", "teknoloji", "bilim teknoloji", "bilim & teknoloji" -> "$bXUrl/konu/bilim-belgeselleri"
            "doğa belgeselleri", "doga belgeselleri" -> "$bXUrl/konu/doga-belgeselleri"
            "hayvan", "hayvanlar", "vahşi", "vahşi yaşam" -> "$bXUrl/konu/hayvan-belgeselleri"
            "polisiye" -> "$bXUrl/konu/polisiye-belgeselleri"
            "seyahat", "seyehat", "dünya" -> "$bXUrl/konu/seyehat-belgeselleri"
            "mühendislik", "muhendislik" -> "$bXUrl/konu/muhendislik-belgeselleri"
            "sanat", "kültür" -> "$bXUrl/konu/sanat-belgeselleri"
            "psikoloji" -> "$bXUrl/konu/psikoloji-belgeselleri"
            else -> null
        }

        val searchItems = mutableListOf<SearchResponse>()

        if (categoryUrl != null) {
            try {
                if (categoryUrl.contains("tlctv") || categoryUrl.contains("dmax")) {
                    val currentBase = if (categoryUrl.contains("tlctv")) tlcUrl else dmaxUrl
                    val doc = app.get(categoryUrl, headers = defaultHeaders, cacheTime = 0).document
                    doc.select(".poster").forEach { el ->
                        if (el.parents().any { it.hasClass("owl-carousel") }) return@forEach

                        val linkEl = el.selectFirst("a") ?: return@forEach
                        val href = linkEl.attr("href") ?: return@forEach
                        if (href.startsWith("/kesfet/") || href == "#" || href.contains("javascript:")) return@forEach

                        val slug = href.removeSuffix("/").split("/").lastOrNull() ?: ""
                        val title = el.attr("title").takeIf { !it.isNullOrBlank() }
                            ?: linkEl.attr("title").takeIf { !it.isNullOrBlank() }
                            ?: el.selectFirst("img")?.attr("alt").takeIf { !it.isNullOrBlank() }
                            ?: slug.replace("-", " ").capitalizeWords()

                        val img = el.selectFirst("img")
                        val poster = img?.attr("data-src").takeIf { !it.isNullOrBlank() } ?: img?.attr("src")
                        val cleanPoster = poster?.let { fix(it, currentBase) }

                        if (title.isNotBlank()) {
                            searchItems.add(newAnimeSearchResponse(title, fix(href, currentBase), TvType.Documentary) {
                                this.posterUrl = cleanPoster
                                this.posterHeaders = defaultHeaders + mapOf("Referer" to currentBase)
                            })
                        }
                    }
                } else if (categoryUrl.contains("belgeselx")) {
                    val res = app.get(categoryUrl, headers = defaultHeaders)
                    if (res.isSuccessful) {
                        val doc = res.document
                        val seenUrls = mutableSetOf<String>()
                        for (el in doc.select("a.px-card")) {
                            val href = el.attr("href") ?: continue
                            if (href == "/" || href == bXUrl || href == "$bXUrl/") continue
                            val fullUrl = fix(href, bXUrl)
                            if (fullUrl in seenUrls) continue
                            val title = el.selectFirst(".px-card-title, .px-title, h3, h4, .title")?.text()?.trim()
                                ?: el.selectFirst("img")?.attr("alt")?.trim()
                                ?: el.attr("title").trim().takeIf { it.isNotBlank() }
                                ?: continue
                            val rawPoster = el.selectFirst("img.px-card-img")?.let { img -> img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src") }
                                ?: el.selectFirst("img")?.let { img -> img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src") }
                            val cleanPoster = rawPoster?.let { fix(it, bXUrl) }?.let { 
                                if (it.endsWith("jpeg", true) && !it.contains(".jpeg", true)) it.replace("jpeg", ".jpg", true) else it 
                            }
                            seenUrls.add(fullUrl)
                            searchItems.add(newAnimeSearchResponse(title.trim(), fullUrl, TvType.Documentary) {
                                this.posterUrl = cleanPoster ?: "$bXUrl/images/noimage.jpg"
                                this.posterHeaders = defaultHeaders + mapOf("Referer" to bXUrl)
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            coroutineScope {
                val urls = listOf(
                    Pair(tlcUrl, "$tlcUrl/arama?sorgu=$query"),
                    Pair(dmaxUrl, "$dmaxUrl/arama?sorgu=$query")
                )
                
                urls.map { (base, searchUrl) ->
                    async {
                        try {
                            val res = app.get(searchUrl, headers = defaultHeaders)
                            if (res.isSuccessful) {
                                val doc = Jsoup.parse(res.text)
                                doc.select(".page-search-result-group .poster, .page-search-results .poster").forEach { el ->
                                    val linkEl = el.selectFirst("a") ?: return@forEach
                                    val href = linkEl.attr("href") ?: return@forEach
                                    if (href.contains("javascript:") || href == "#") return@forEach
                                    
                                    val slug = href.removeSuffix("/").split("/").lastOrNull() ?: ""
                                    val title = el.attr("title").takeIf { !it.isNullOrBlank() }
                                        ?: linkEl.attr("title").takeIf { !it.isNullOrBlank() }
                                        ?: el.selectFirst("img")?.attr("alt").takeIf { !it.isNullOrBlank() }
                                        ?: slug.replace("-", " ").capitalizeWords()

                                    val img = el.selectFirst("img")
                                    val poster = img?.attr("data-src").takeIf { !it.isNullOrBlank() } ?: img?.attr("src")
                                    val cleanPoster = poster?.let { fix(it, base) }

                                    if (title.isNotBlank()) {
                                        synchronized(searchItems) {
                                            searchItems.add(newAnimeSearchResponse(title, fix(href, base), TvType.Documentary) {
                                                this.posterUrl = cleanPoster
                                                this.posterHeaders = defaultHeaders + mapOf("Referer" to base)
                                            })
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        }

        return searchItems.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = defaultHeaders)
        if (!res.isSuccessful) return null
        val doc = Jsoup.parse(res.text)

        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text() ?: doc.title()
        val title = rawTitle.replace(Regex("""(?i)Bölümler, Kısa Videolar, Haberler|Bölümler, Kısa Videolar|\| TLC|\| DMAX|İzle"""), "").trim()
            
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.select("img").firstOrNull { 
                val src = it.attr("src") ?: ""
                src.contains("/upload/files/") || src.contains("mncdn.com")
            }?.attr("src")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val currentBase = when {
            url.contains("tlctv") -> tlcUrl
            url.contains("dmax") -> dmaxUrl
            else -> bXUrl
        }

        val episodes = mutableListOf<Episode>()

        if (url.contains("tlctv") || url.contains("dmax")) {
            val cleanUrl = url.removeSuffix("/").substringBefore("?")
            val progSlug = if (cleanUrl.contains("-bolum")) {
                cleanUrl.split("/").dropLast(1).last()
            } else {
                cleanUrl.split("/").last()
            }
            val programId = doc.selectFirst(".dyn-content.program-episodes")?.attr("data-program-id")
            val seasonOptions = doc.select("#video-filter-changer option").mapNotNull { it.attr("value") }.distinct()
            val ajaxUrl = if (url.contains("tlctv")) "$tlcUrl/ajax/more" else "$dmaxUrl/ajax/more"

            fun parseEpisodesFromDoc(document: Document, currentSeason: Int? = null, isAjax: Boolean = false) {
                document.select("a").forEach { el ->
                    val epHref = el.attr("href") ?: return@forEach
                    if (epHref.contains("javascript:") || epHref.contains("#") || 
                        epHref.contains("/kisa-video") || epHref.contains("/fragman") || 
                        epHref.contains("/haber") || epHref.contains("/blog/") || 
                        epHref.contains("/kategori/") || epHref.contains("/klip") || 
                        epHref.contains("son-bolum")) return@forEach

                    val isMatch = if (isAjax) {
                        epHref.contains("-sezon") && epHref.contains("-bolum")
                    } else {
                        epHref.contains(progSlug) && epHref.contains("-sezon") && epHref.contains("-bolum")
                    }

                    if (isMatch) {
                        val fullUrl = fix(epHref, currentBase)
                        val epTitleEl = el.selectFirst(".item-meta-title strong, .video-title, .title, .card-title, .item-meta-title") ?: el
                        val epRawTitle = (epTitleEl.text().trim().ifBlank { el.attr("title").trim() }.ifBlank { epHref.removeSuffix("/").split("/").lastOrNull()?.replace("-", " ")?.capitalizeWords() }) ?: "Bölüm"

                        val epImg = el.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } } ?: poster

                        val seasonFromUrl = Regex("""(\d+)-sezon""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: currentSeason
                        val episodeFromUrl = Regex("""(\d+)-bolum""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()

                        val rawS = seasonFromUrl ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epRawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                        val titleS = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epRawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val s = if (rawS == 0 && titleS != null) titleS else rawS
                        val ep = episodeFromUrl ?: Regex("""(\d+)\.\s*Bölüm|(\d+)\.\s*Ep""", RegexOption.IGNORE_CASE).find(epRawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

                        val cleanTitle = epRawTitle.replace(Regex("""(?i)\b(?:izle)\b|-"""), "").trim().ifBlank { "Bölüm ${ep ?: 1}" }

                        val targetEpUrl = fullUrl

                        episodes.add(newEpisode(targetEpUrl) {
                            this.name = cleanTitle
                            this.season = s
                            this.episode = ep
                            this.posterUrl = epImg
                        })
                    }
                }
            }

            parseEpisodesFromDoc(doc)

            if (programId != null && programId.isNotBlank() && seasonOptions.isNotEmpty()) {
                coroutineScope {
                    val ajaxTasks = seasonOptions.flatMap { seasonVal ->
                        (0..2).map { pageNum ->
                            async {
                                try {
                                    val postRes = app.post(
                                        ajaxUrl,
                                        headers = mapOf(
                                            "User-Agent" to defaultHeaders["User-Agent"]!!,
                                            "X-Requested-With" to "XMLHttpRequest",
                                            "Referer" to url
                                        ),
                                        data = mapOf(
                                            "type" to "episodes",
                                            "program_id" to programId,
                                            "season" to seasonVal,
                                            "page" to pageNum.toString()
                                        )
                                    )
                                    if (postRes.isSuccessful && postRes.text.isNotBlank() && postRes.text.contains("item")) {
                                        Pair(seasonVal.toIntOrNull(), Jsoup.parse(postRes.text))
                                    } else null
                                } catch (_: Exception) { null }
                            }
                        }
                    }.awaitAll().filterNotNull()

                    for ((sNum, ajaxDoc) in ajaxTasks) {
                        parseEpisodesFromDoc(ajaxDoc, sNum, true)
                    }
                }
            }

            val hasVideoPlayer = doc.select("div[id*=Player_EHD], video, iframe[src*=player]").isNotEmpty()
            if (episodes.isEmpty() && hasVideoPlayer) {
                episodes.add(newEpisode(url) { 
                    this.name = title 
                    this.posterUrl = poster
                })
            }
        } else if (url.contains("belgeselx")) {
            val epButtons = doc.select("a[onclick*='diziGetir'], a[onclick*='butonKaydet'], .px-ep-card, .px-ep-row")
            if (epButtons.isNotEmpty()) {
                epButtons.forEach { el ->
                    val onclick = el.attr("onclick") ?: ""
                    val idMatch = Regex("""['"](\d+)['"]""").find(onclick) ?: Regex("""no(\d+)""").find(el.attr("id") ?: "")
                    if (idMatch != null) {
                        val epId = idMatch.groupValues[1]
                        val epTitle = el.selectFirst(".px-ep-title, .px-ep-row-title")?.text()?.trim()
                            ?: el.text().trim().replace(Regex("""\s+"""), " ")
                        
                        val sMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)
                        val bMatch = Regex("""B(\d+)""", RegexOption.IGNORE_CASE).find(epTitle) ?: Regex("""(\d+)\s*$""").find(epTitle)
                        val s = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val ep = bMatch?.groupValues?.get(1)?.toIntOrNull()

                        val cleanTitle = epTitle.replace(Regex("""S\d+\s*·\s*B\d+|0\d+"""), "").trim()

                        val elHref = el.attr("href") ?: ""
                        val targetBaseUrl = if (elHref.isNotBlank() && elHref != "#") fix(elHref, bXUrl) else url

                        episodes.add(newEpisode("$targetBaseUrl?bx_id=$epId") {
                            this.name = cleanTitle.ifBlank { "Bölüm ${ep ?: 1}" }
                            this.season = s
                            this.episode = ep
                        })
                    }
                }
            } else {
                episodes.add(newEpisode(url) { this.name = title })
            }
        } else {
            episodes.add(newEpisode(url) { this.name = title })
        }

        val distinctEpisodes = episodes.distinctBy { "${it.name}_${it.data}" }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, distinctEpisodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.contains("bx_id=") || data.contains("belgeselx")) {
            val epId = if (data.contains("bx_id=")) {
                data.substringAfter("bx_id=").substringBefore("&")
            } else {
                val targetUrl = data.substringBefore("?bx_id=")
                val res = app.get(targetUrl, headers = defaultHeaders)
                if (!res.isSuccessful) return false
                val doc = Jsoup.parse(res.text)
                (Regex("""butonId['"]?\s*,\s*['"](\d+)""").find(doc.html()) ?: Regex("""diziGetir\(['"](\d+)""").find(doc.html()))?.groupValues?.get(1)
            }

            if (epId != null) {
                val fList = listOf("new1", "new2", "new3", "new4", "new5")
                coroutineScope {
                    fList.map { f ->
                        async {
                            try {
                                val dataUrl = "https://belgeselx.com/video/data/$f.php?id=$epId"
                                val dataRes = app.get(dataUrl, headers = defaultHeaders)
                                if (dataRes.isSuccessful) {
                                    val text = dataRes.text
                                    val sources = Regex("""file\s*:\s*["'](https?://[^"']+)["'].*?label\s*:\s*["']([^"']+)["']""").findAll(text)
                                    sources.forEach { m ->
                                        val rawUrl = m.groupValues[1]
                                        val label = m.groupValues[2]
                                        val isGoogle = rawUrl.contains("googlevideo") || rawUrl.contains("google")
                                        val streamRef = if (isGoogle) "" else "https://belgeselx.com/"

                                        callback(newExtractorLink("BelgeselX", "Stream $label", rawUrl, type = INFER_TYPE) {
                                            this.referer = streamRef
                                            if (label.contains("1080")) this.quality = Qualities.P1080.value
                                            else if (label.contains("720")) this.quality = Qualities.P720.value
                                            else if (label.contains("480")) this.quality = Qualities.P480.value
                                            else this.quality = Qualities.Unknown.value
                                        })
                                    }
                                    
                                    val iframeSrc = Jsoup.parse(text).select("iframe").mapNotNull { it.attr("src") }.firstOrNull { it.isNotBlank() }
                                    if (iframeSrc != null) {
                                        val finalSrc = when {
                                            iframeSrc.startsWith("AF1Qip") -> "https://photos.google.com/share/$iframeSrc"
                                            iframeSrc.startsWith("http") -> iframeSrc
                                            iframeSrc.startsWith("//") -> "https:$iframeSrc"
                                            else -> "https://belgeselx.com/video/data/$iframeSrc"
                                        }
                                        loadExtractor(finalSrc, subtitleCallback, callback)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }.awaitAll()
                }
                return true
            }
        }

        val targetUrl = data.substringBefore("?bx_id=")
        val res = app.get(targetUrl, headers = defaultHeaders)
        if (!res.isSuccessful) return false
        val doc = Jsoup.parse(res.text)

        val currentBase = when {
            data.contains("tlctv") -> tlcUrl
            data.contains("dmax") -> dmaxUrl
            else -> bXUrl
        }

        if (data.contains("tlctv") || data.contains("dmax")) {
            val videoCode = doc.selectFirst("[data-video-code]")?.attr("data-video-code")
                ?: Regex("""data-video-code=["']([^"']+)["']""").find(doc.html())?.groupValues?.getOrNull(1)
            
            if (!videoCode.isNullOrBlank()) {
                val publisherId = if (data.contains("tlctv")) "20" else "27"
                val sourceName = if (data.contains("tlctv")) "TLC TV" else "DMAX"
                val vidUrl = "https://dygvideo.dygdigital.com/api/redirect?PublisherId=$publisherId&ReferenceId=$videoCode&SecretKey=NtvApiSecret2014*"
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$sourceName (HLS)",
                        url = vidUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = if (data.contains("tlctv")) "$tlcUrl/" else "$dmaxUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        }

        val ytId = doc.select("iframe[src*='youtube.com'], iframe[src*='youtu.be']").attr("src")
            .let { src -> Regex("embed/([^/?]+)").find(src)?.groupValues?.get(1) }
        
        if (ytId != null) {
            return loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
        }

        val metaVideoUrl = doc.select("meta[property=og:video:url], meta[property=og:video:secure_url], meta[property=og:video]")
            .mapNotNull { it.attr("content") }
            .firstOrNull { it.contains("youtube.com") || it.contains("youtu.be") || it.contains("vimeo") || it.contains("dailymotion") || it.endsWith(".mp4") || it.endsWith(".m3u8") }
            
        if (metaVideoUrl != null && metaVideoUrl.isNotBlank()) {
            loadExtractor(metaVideoUrl, subtitleCallback, callback)
            return true
        }

        val iframeSrc = doc.select("iframe").mapNotNull { it.attr("src") }.firstOrNull { it.isNotBlank() && !it.contains("preview_mode=1") && !it.contains("watch_trailer=1") }
        if (iframeSrc != null) {
            val finalSrc = fix(iframeSrc, currentBase)
            loadExtractor(finalSrc, subtitleCallback, callback)
            return true
        }

        val script = doc.select("script").filter { it.html().contains("hls") || it.html().contains("m3u8") }.firstOrNull()?.html()
        if (script != null) {
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)?.let { m3u8 ->
                callback(newExtractorLink("Belgesel", "HLS Stream", m3u8, type = INFER_TYPE) {
                    this.referer = data
                })
                return true
            }
        }

        return true
    }
}
