package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope


class DizipalProvider : MainAPI() {
    override var mainUrl = "https://dizipal.im"
    override var name = "Dizipal"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Movie)

    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/" to "Diziler",
        "$mainUrl/filmler/" to "Filmler",
        "$mainUrl/animeler/" to "Animeler",
        "$mainUrl/platform/netflix/" to "Netflix",
        "$mainUrl/platform/exxen/" to "Exxen",
        "$mainUrl/platform/gain/" to "Gain",
        "$mainUrl/platform/disney/" to "Disney+",
        "$mainUrl/platform/prime-video/" to "Prime Video",
        "$mainUrl/platform/hbomax/" to "Max",
        "$mainUrl/platform/tabii/" to "Tabii",
        "$mainUrl/platform/apple-tv/" to "Apple TV+"
    )

    private var isDomainResolved = false

    private suspend fun resolveActiveDomain() {
        if (isDomainResolved) return
        try {
            val res = app.get(mainUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"), cacheTime = 60)
            if (res.isSuccessful) {
                val redirectedUrl = res.url.removeSuffix("/")
                if (redirectedUrl.startsWith("http") && redirectedUrl != mainUrl) {
                    mainUrl = redirectedUrl
                    isDomainResolved = true
                }
            }
        } catch (e: Exception) {
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveActiveDomain()
        val requestUrl = request.data.replace("https://dizipal.im", mainUrl)
        val url = if (page > 1) {
            val base = requestUrl.removeSuffix("/")
            "$base/page/$page/"
        } else {
            requestUrl
        }
        
        val items = mutableListOf<SearchResponse>()
        
        try {
            val res = app.get(url, headers = defaultHeaders, cacheTime = 0)
            if (res.isSuccessful) {
                val doc = Jsoup.parse(res.text)
                var elements = doc.select(".post-item a, .group a[title], .swiper-slide a[title], .grid a[title], article a[title]")
                if (elements.isEmpty()) {
                    elements = doc.select("a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']")
                }
                if (elements.isNotEmpty()) {
                    coroutineScope {
                        val deferred = elements.map { el ->
                            async { el.toSearchResult() }
                        }
                        items.addAll(deferred.awaitAll().filterNotNull().distinctBy { it.url })
                    }
                }
                
                // Fallback using JSON API if HTML fails
                if (items.isEmpty()) {
                    val apiPath = when {
                        requestUrl.contains("/diziler/") -> "categories=2"
                        requestUrl.contains("/filmler/") -> "categories=1"
                        else -> ""
                    }
                    val jsonUrl = "$mainUrl/wp-json/wp/v2/posts?per_page=30&page=$page${if (apiPath.isNotEmpty()) "&$apiPath" else ""}"
                    val jsonRes = app.get(jsonUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
                    if (jsonRes.isSuccessful) {
                        val jsonArray = JSONArray(jsonRes.text)
                        for (i in 0 until jsonArray.length()) {
                            val post = jsonArray.getJSONObject(i)
                            val titleVal = post.getJSONObject("title").optString("rendered")
                                .replace("&#8211;", "-").replace("&#8217;", "'").trim()
                            val link = post.optString("link")?.replace("https://dizipal.im", mainUrl)
                            val yoast = post.optJSONObject("yoast_head_json")
                            val posterVal = yoast?.optJSONArray("og_image")?.optJSONObject(0)?.optString("url")
                            
                            if (!link.isNullOrBlank()) {
                                val isTv = link.contains("/dizi/") || link.contains("/anime/")
                                if (isTv) {
                                    items.add(newTvSeriesSearchResponse(titleVal, link, TvType.TvSeries) {
                                        this.posterUrl = posterVal
                                    })
                                } else {
                                    items.add(newMovieSearchResponse(titleVal, link, TvType.Movie) {
                                        this.posterUrl = posterVal
                                    })
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
    }

    private suspend fun Element.toSearchResult(): SearchResponse? {
        val hrefVal = attr("href") ?: return null
        if (hrefVal.contains("/kategori/") || hrefVal.contains("/etiket/") || hrefVal.contains("/yil/") || hrefVal.contains("/yapim/") || hrefVal.contains("/platform/")) return null
        
        val urlVal = if (hrefVal.startsWith("http")) hrefVal else "$mainUrl$hrefVal"
        val titleVal = attr("title")?.trim() 
            ?: selectFirst("h2, h3, h4, .title, .post-title")?.text()?.trim() 
            ?: text().trim()
            
        if (titleVal.isBlank() || titleVal.length < 2 || titleVal == "Oynat" || titleVal == "Şimdi izle") return null
        
        val posterVal = selectFirst("img")?.let { img ->
            val srcVal = img.attr("data-src").ifBlank { img.attr("src") }
            if (srcVal.isNotBlank() && !srcVal.startsWith("data:")) {
                if (srcVal.startsWith("http")) srcVal else "$mainUrl$srcVal"
            } else null
        }
        
        val isTvVal = urlVal.contains("/dizi/") || urlVal.contains("/anime/") || urlVal.contains("/bolum/") || urlVal.contains("/episode/")
        val ratingHtml = selectFirst(".imdb, .rating, .score, .rate, .vote, .badge, .puan")?.text()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
        val realRating = ratingHtml
        
        return if (isTvVal) {
            newTvSeriesSearchResponse(titleVal, urlVal, TvType.TvSeries) {
                this.posterUrl = posterVal
                realRating?.takeIf { it > 0.0 }?.let { 
                    this.score = Score.from10(it)
                    this.posterHeaders = mapOf("IMDb" to String.format("%.1f", it))
                }
            }
        } else {
            newMovieSearchResponse(titleVal, urlVal, TvType.Movie) {
                this.posterUrl = posterVal
                realRating?.takeIf { it > 0.0 }?.let { 
                    this.score = Score.from10(it)
                    this.posterHeaders = mapOf("IMDb" to String.format("%.1f", it))
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveActiveDomain()
        val cleanQuery = query.lowercase().trim()
            .replace(Regex("""\s*(?:filmi|dizisi|filmleri|dizileri|izle)$"""), "")
            .trim()

        val categorySlug = when (cleanQuery) {
            "aksiyon" -> "aksiyon"
            "macera" -> "macera"
            "korku" -> "korku"
            "komedi" -> "komedi"
            "gerilim" -> "gerilim"
            "dram" -> "dram"
            "gizem" -> "gizem"
            "fantastik" -> "fantastik"
            "romantik" -> "romantik"
            "animasyon" -> "animasyon"
            "belgesel" -> "belgesel"
            "aile" -> "aile"
            "bilim kurgu", "bilimkurgu" -> "bilim-kurgu"
            "suç", "suc" -> "suc"
            else -> null
        }

        val urlVal = if (categorySlug != null) {
            "$mainUrl/kategori/$categorySlug/"
        } else {
            val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            "$mainUrl/?s=$encodedQuery"
        }

        val resVal = app.get(urlVal, headers = defaultHeaders, cacheTime = 0)
        if (!resVal.isSuccessful) return emptyList()
        
        val docVal = Jsoup.parse(resVal.text)
        var elementsVal = docVal.select(".post-item a, .group a[title], .grid a[title]")
        if (elementsVal.isEmpty()) elementsVal = docVal.select("a[href*='/dizi/'], a[href*='/anime/']")
        return coroutineScope {
            val deferred = elementsVal.map { el ->
                async { el.toSearchResult() }
            }
            deferred.awaitAll().filterNotNull().distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveActiveDomain()
        val cleanUrl = url.replace("https://dizipal.im", mainUrl)
        val res = app.get(cleanUrl, headers = defaultHeaders, cacheTime = 0)
        if (!res.isSuccessful) return null
        
        val doc = Jsoup.parse(res.text)
        
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(" izle - Dizipal", "")
            ?.replace(" - Dizipal", "")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().replace(" - Dizipal", "").trim()
        
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        
        val year = doc.select("a[href*='/yapim/'], a[href*='/yil/'], .year, .release-year").firstOrNull()?.text()?.toIntOrNull()
        val tags = doc.select("a[href*='/kategori/'], a[href*='/dizi-kategori/'], .genres a").map { it.text().trim() }

        if (cleanUrl.contains("/dizi/") || cleanUrl.contains("/anime/") || cleanUrl.contains("/bolum/") || cleanUrl.contains("/episode/")) {
            val allEpisodes = mutableListOf<Episode>()
            
            fun parseEpisodesFromDoc(document: Document): List<Episode> {
                return document.select(".episode-item, .episodes a, .ep-item, .bolum-listesi a, .episodes-list a, .episode-list-item-link a").mapNotNull { el ->
                    val aTag = if (el.tagName() == "a") el else el.selectFirst("a[href*='/bolum/'], a[href*='/episode/']") ?: el.selectFirst("a")
                    val epHref = aTag?.attr("href") ?: return@mapNotNull null
                    if (epHref.contains("javascript:;")) return@mapNotNull null
                    
                    val epUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                    
                    val titleEl = el.selectFirst("h4 a, .title a, h3 a, h2 a") ?: aTag
                    val rawTitle = titleEl.attr("title").ifBlank { titleEl.text() }.trim()
                    
                    if (rawTitle.lowercase().contains("ilk bölümü izle") || rawTitle.lowercase().contains("son bölümü izle")) return@mapNotNull null
                    
                    val epPoster = el.selectFirst("img")?.let { img ->
                        img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                    } ?: poster
 
                    val seasonFromUrl = Regex("""(\d+)-sezon""", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val episodeFromUrl = Regex("""(\d+)-bolum""", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()

                    val season = seasonFromUrl ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val episode = episodeFromUrl ?: Regex("""(\d+)\.\s*Bölüm|(\d+)\.\s*Ep""", RegexOption.IGNORE_CASE).find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    
                    val cleanTitle = rawTitle.replace(Regex("""(?i)\b(?:from|dizipal|\d+\.\s*sezon|\d+\.\s*bölüm|izle)\b|-"""), "").trim().ifBlank { "Bölüm ${episode ?: 1}" }

                    newEpisode(epUrl) {
                        this.name = cleanTitle
                        this.season = season
                        this.episode = episode
                        this.posterUrl = epPoster
                    }
                }
            }

            allEpisodes.addAll(parseEpisodesFromDoc(doc))

            // Diğer sezon sayfalarını coroutine ile yakala
            val seasonLinks = doc.select("#season-options-list a, ul li a[href*='sezon='], ul li a[href*='season='], .seasons a")
                .mapNotNull { it.attr("href") }
                .filter { it.isNotBlank() && (it.contains("sezon=") || it.contains("season=")) }
                .map { if (it.startsWith("http")) it else "$mainUrl$it" }
                .distinct()
                .filter { !it.substringAfter("?").equals(cleanUrl.substringAfter("?")) && it != cleanUrl }

            if (seasonLinks.isNotEmpty()) {
                coroutineScope {
                    val additionalDocs = seasonLinks.map { sUrl ->
                        async {
                            try {
                                app.get(sUrl, headers = defaultHeaders).document
                            } catch (_: Exception) { null }
                        }
                    }.awaitAll().filterNotNull()

                    for (sDoc in additionalDocs) {
                        allEpisodes.addAll(parseEpisodesFromDoc(sDoc))
                    }
                }
            }

            val distinctEpisodes = allEpisodes.distinctBy { it.data }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })

            return newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, distinctEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
        
        return null
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        resolveActiveDomain()
        val cleanData = data.replace("https://dizipal.im", mainUrl)
        return try {
            val res = app.get(cleanData, headers = defaultHeaders, cacheTime = 0)
            if (!res.isSuccessful) return false
            
            val doc = Jsoup.parse(res.text)
            
            val iframeSrc = doc.select("iframe[src*='ag2m4'], .responsive-player iframe, .player iframe, iframe[src*='embed']").attr("src")
                .takeIf { it.isNotBlank() }
                ?: doc.selectFirst("iframe")?.attr("src")
            
            if (iframeSrc != null && iframeSrc.isNotBlank()) {
                val embedUrl = when {
                    iframeSrc.startsWith("//") -> "https:$iframeSrc"
                    iframeSrc.startsWith("http") -> iframeSrc
                    else -> "$mainUrl$iframeSrc"
                }
                
                val embedRes = app.get(embedUrl, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Referer" to cleanData
                ), cacheTime = 0)

                // Extract subtitles from player configuration
                val subtitleRegex = Regex("""["']subtitle["']\s*:\s*["']([^"']+)["']""")
                val subtitleMatch = subtitleRegex.find(embedRes.text)
                if (subtitleMatch != null) {
                    val subtitleVal = subtitleMatch.groupValues[1]
                    if (subtitleVal.isNotBlank()) {
                        subtitleVal.split(",").forEach { part ->
                            if (part.isNotBlank()) {
                                val langRegex = Regex("""^\[([^\]]+)\](.*)$""")
                                val langMatch = langRegex.find(part)
                                if (langMatch != null) {
                                    val lang = langMatch.groupValues[1]
                                    val url = langMatch.groupValues[2]
                                    subtitleCallback(
                                        newSubtitleFile(
                                            lang = lang,
                                            url = url
                                        )
                                    )
                                } else {
                                    subtitleCallback(
                                        newSubtitleFile(
                                            lang = "Türkçe",
                                            url = part
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                val m3u8Regex = Regex("""['"](https?://[^"']*\.m3u8[^"']*)['"]""")
                val m3u8Match = m3u8Regex.find(embedRes.text)
                if (m3u8Match != null) {
                    val streamUrl = m3u8Match.groupValues[1]
                    callback(
                        newExtractorLink("Dizipal", "Dizipal HD", streamUrl, type = INFER_TYPE) {
                            headers = getBrowserHeaders(embedUrl)
                        }
                    )
                    return true
                }
                
                val mp4Regex = Regex("""['"](https?://[^"']*\.mp4[^"']*)['"]""")
                val mp4Match = mp4Regex.find(embedRes.text)
                if (mp4Match != null && !mp4Match.groupValues[1].contains("blank.mp4")) {
                    val streamUrl = mp4Match.groupValues[1]
                    callback(
                        newExtractorLink("Dizipal", "Dizipal MP4", streamUrl, type = INFER_TYPE) {
                            headers = getBrowserHeaders(embedUrl)
                        }
                    )
                    return true
                }
                
                val fileId = Regex("""\$\.cookie\(\s*['"]file_id['"]\s*,\s*['"](\d+)['"]""").find(embedRes.text)?.groupValues?.get(1) ?: "125781"
                val apiPath = Regex("""fetch\(\s*['"]([^'"]+)['"]\s*\)""").find(embedRes.text)?.groupValues?.get(1)
                
                if (apiPath != null) {
                    val embedUri = java.net.URI(embedUrl)
                    val embedHost = embedUri.host ?: "x.ag2m4.cfd"
                    val embedBase = "${embedUri.scheme ?: "https"}://$embedHost"
                    val streamApiUrl = "$embedBase$apiPath"
                    
                    val streamRes = app.get(
                        streamApiUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            "Referer" to embedUrl,
                            "Cookie" to "file_id=$fileId; aff=1; ref_url=dizipal.im",
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "same-origin"
                        ),
                        cacheTime = 0
                    )
                    
                    if (streamRes.isSuccessful) {
                        val streamUrl = try {
                            JSONObject(streamRes.text).optString("url")
                        } catch (_: Exception) {
                            Regex("""["']url["']\s*:\s*["']([^"']+)["']""").find(streamRes.text)?.groupValues?.get(1)
                        }
                        
                        if (!streamUrl.isNullOrBlank()) {
                            callback(
                                newExtractorLink("Dizipal", "Dizipal HD", streamUrl, type = INFER_TYPE) {
                                     headers = getBrowserHeaders(embedUrl)
                                }
                            )
                            return true
                        }
                    }
                }

                loadExtractor(embedUrl, referer = cleanData, subtitleCallback = subtitleCallback) { link ->
                    callback(link)
                }
                return true
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
}
