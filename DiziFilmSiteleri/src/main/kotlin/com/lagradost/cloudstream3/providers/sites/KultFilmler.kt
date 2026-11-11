package com.lagradost.cloudstream3.providers

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class KultFilmler : MainAPI() {
    override var mainUrl              = "https://kultfilmler.net"
    override var name                 = "Kült Filmler"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film-arsivi/" to "Kült Filmler",
        "$mainUrl/category/aksiyon-filmleri-izle/" to "Aksiyon",
        "$mainUrl/category/animasyon-filmleri-izle/" to "Animasyon",
        "$mainUrl/category/bilim-kurgu-filmleri-izle/" to "Bilim Kurgu",
        "$mainUrl/category/dram-filmleri-izle/" to "Dram",
        "$mainUrl/category/fantastik-filmleri-izle/" to "Fantastik",
        "$mainUrl/category/gerilim-filmleri-izle/" to "Gerilim",
        "$mainUrl/category/gizem-filmleri-izle/" to "Gizem",
        "$mainUrl/category/komedi-filmleri-izle/" to "Komedi",
        "$mainUrl/category/korku-filmleri-izle/" to "Korku",
        "$mainUrl/category/macera-filmleri-izle/" to "Macera",
        "$mainUrl/category/suc-filmleri-izle/" to "Suç"
    )

    private var isDomainResolved = false

    private suspend fun resolveActiveDomain() {
        if (isDomainResolved) return
        try {
            val res = app.get(mainUrl, headers = mapOf("User-Agent" to USER_AGENT), cacheTime = 60)
            if (res.isSuccessful) {
                val redirectedUrl = res.url.removeSuffix("/")
                if (redirectedUrl.startsWith("http") && redirectedUrl != mainUrl) {
                    mainUrl = redirectedUrl
                    isDomainResolved = true
                }
            }
        } catch (e: Exception) {
            Log.e("KultFilmler", "Domain resolution failed: ${e.message}")
        }
    }

    private fun getPaginationUrl(baseUrl: String, webPage: Int): String {
        if (webPage <= 1) return baseUrl
        return if (baseUrl.contains("film-arsivi")) {
            if (baseUrl.contains("?")) {
                "$baseUrl&sayfa=$webPage"
            } else {
                val cleanBase = baseUrl.removeSuffix("/")
                "$cleanBase/?sayfa=$webPage"
            }
        } else {
            val cleanBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            "${cleanBase}page/$webPage/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveActiveDomain()
        val cleanData = request.data.replace("https://kultfilmler.net", mainUrl)
        
        val webPage1 = (page * 2) - 1
        val webPage2 = page * 2
        
        val url1 = getPaginationUrl(cleanData, webPage1)
        val url2 = getPaginationUrl(cleanData, webPage2)
        
        val doc1 = app.get(url1, headers = getHeaders(mainUrl)).document
        val items1 = doc1.select(".mcard, .dcard, .film-box, .series-box, .movie-box").mapNotNull { el ->
            toSearchResult(el)
        }
        
        val nextPage = webPage2 + 1
        var hasNextPage = false
        val items2 = try {
            val res2 = app.get(url2, headers = getHeaders(mainUrl))
            val doc2 = res2.document
            hasNextPage = doc2.selectFirst(".keremiya-pagenavi, .pagination, .wp-pagenavi, .pager")
                ?.select("a")?.any { a ->
                    val href = a.attr("href")
                    href.contains("sayfa=$nextPage") || href.contains("page/$nextPage")
                } == true
            doc2.select(".mcard, .dcard, .film-box, .series-box, .movie-box").mapNotNull { el ->
                toSearchResult(el)
            }
        } catch (e: Exception) {
            emptyList()
        }
        
        val home = (items1 + items2).distinctBy { it.url }
        if (items2.isEmpty()) {
            val nextPageFromDoc1 = webPage1 + 1
            hasNextPage = doc1.selectFirst(".keremiya-pagenavi, .pagination, .wp-pagenavi, .pager")
                ?.select("a")?.any { a ->
                    val href = a.attr("href")
                    href.contains("sayfa=$nextPageFromDoc1") || href.contains("page/$nextPageFromDoc1")
                } == true
        }
        
        return newHomePageResponse(request.name, home, hasNextPage)
    }

    private fun toSearchResult(el: Element): SearchResponse? {
        val linkElement = if (el.tagName() == "a") el else el.selectFirst("a") ?: return null
        val rawHref = linkElement.attr("href") ?: return null
        val href = fixUrl(rawHref)
        
        val title = el.selectFirst("h3")?.text()?.trim()
            ?: el.selectFirst(".name a, .name, .title")?.text()?.trim() 
            ?: linkElement.attr("title").trim().takeIf { it.isNotBlank() } 
            ?: el.selectFirst("img")?.attr("alt")?.trim() 
            ?: return null
            
        val poster = el.selectFirst("img")?.let { img ->
            val dataSrc = img.attr("data-src")
            val src = img.attr("src")
            val lazySrc = img.attr("data-lazy-src")
            dataSrc.ifBlank { src }.ifBlank { lazySrc }
        }?.let { fixUrlNull(it) }

        val ratingVal = el.selectFirst(".mscore")?.text()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
            ?: el.selectFirst(".rating, .imdb, .score")?.text()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.posterHeaders = getHeaders(mainUrl)
                ratingVal?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.posterHeaders = getHeaders(mainUrl)
                ratingVal?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveActiveDomain()
        Log.d("KultFilmler", "Searching for $query")
        
        val document = app.get(
            "$mainUrl/",
            params = mapOf("s" to query.trim()),
            headers = getHeaders(mainUrl)
        ).document
        
        return document.select(".mcard, .dcard, .film-box, .series-box, .movie-box").mapNotNull { el ->
            toSearchResult(el)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        resolveActiveDomain()
        val cleanUrl = url.replace("https://kultfilmler.net", mainUrl)
        val document = app.get(cleanUrl, headers = getHeaders(mainUrl)).document

        val title = document.selectFirst("h1")?.text()?.replace(Regex("(?i)izle"), "")?.trim() 
            ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.replace(Regex("(?i)izle"), "")?.trim() 
            ?: ""
        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content") 
            ?: document.selectFirst(".poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        
        val plot = document.selectFirst("p.desc")?.text()?.trim()
            ?: document.selectFirst("div.description")?.text()?.trim()
            ?: document.selectFirst("meta[property=\"og:description\"]")?.attr("content") 
            ?: document.selectFirst(".movie-excerpt p.story, p.story")?.text()?.trim()
            
        val year = document.selectFirst("a[href*=\"/yapim/\"]")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
            ?: document.selectFirst(".film-bilgileri li.release, li.release")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val tags = document.select(".info a[href*=\"/genre/\"], .info a[href*=\"/dizi-kategori/\"], .film-bilgileri a[href*=\"/category/\"], .movies-data a[href*=\"/category/\"]").map { it.text().trim() }
        
        val recommendations = document.select(".mcard, .dcard, .film-box, .movie-box, .series-box").mapNotNull { el ->
            toSearchResult(el)
        }.distinctBy { it.url }

        val epCards = document.select("a.ep")
        val epBoxes = document.select(".ep-box")
        if (epCards.isNotEmpty() || epBoxes.isNotEmpty() || cleanUrl.contains("/dizi/")) {
            val episodes = if (epCards.isNotEmpty()) {
                epCards.mapNotNull { epCard ->
                    val rawHref = epCard.attr("href") ?: return@mapNotNull null
                    val href = fixUrl(rawHref)
                    
                    val titleEl = epCard.selectFirst("h4")
                    val titleText = titleEl?.text()?.trim() ?: ""
                    
                    var season = 1
                    var episode = 1
                    
                    val sMatch = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(titleText)
                    if (sMatch != null) {
                        season = sMatch.groupValues[1].toIntOrNull() ?: 1
                    }
                    
                    val eMatch = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(titleText)
                    if (eMatch != null) {
                        episode = eMatch.groupValues[1].toIntOrNull() ?: 1
                    } else {
                        val urlMatch = Regex("""(\d+)-bolum""", RegexOption.IGNORE_CASE).find(href)
                        if (urlMatch != null) {
                            episode = urlMatch.groupValues[1].toIntOrNull() ?: 1
                        }
                    }
                    
                    newEpisode(href) {
                        this.season = season
                        this.episode = episode
                        this.name = "Sezon $season Bölüm $episode"
                    }
                }
            } else {
                epBoxes.mapNotNull { epBox ->
                    val a = epBox.selectFirst("a[href*=\"/bolum/\"]") ?: return@mapNotNull null
                    val rawHref = a.attr("href") ?: return@mapNotNull null
                    val href = fixUrl(rawHref)
                    
                    val titleEl = epBox.selectFirst(".episodetitle")
                    val titleText = titleEl?.text()?.trim() ?: ""
                    
                    var season = 1
                    var episode = 1
                    
                    val sMatch = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(titleText)
                    if (sMatch != null) {
                        season = sMatch.groupValues[1].toIntOrNull() ?: 1
                    }
                    
                    val eMatch = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(titleText)
                    if (eMatch != null) {
                        episode = eMatch.groupValues[1].toIntOrNull() ?: 1
                    } else {
                        val urlMatch = Regex("""(\d+)-bolum""", RegexOption.IGNORE_CASE).find(href)
                        if (urlMatch != null) {
                            episode = urlMatch.groupValues[1].toIntOrNull() ?: 1
                        }
                    }
                    
                    newEpisode(href) {
                        this.season = season
                        this.episode = episode
                        this.name = "Sezon $season Bölüm $episode"
                    }
                }
            }
            
            return newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveActiveDomain()
        val cleanData = data.replace("https://kultfilmler.net", mainUrl)
        val document = app.get(cleanData, headers = getHeaders(mainUrl)).document

        val srcDataEl = document.selectFirst("script#kf-srcdata")
        val srcDataText = srcDataEl?.data()?.trim() ?: srcDataEl?.html()?.trim()

        val resolvedUrls = mutableListOf<String>()

        if (!srcDataText.isNullOrEmpty()) {
            try {
                val sources = jacksonObjectMapper().readValue<List<KfSource>>(srcDataText)
                for (source in sources) {
                    val html = source.html ?: continue
                    val parsedHtml = Jsoup.parse(html)
                    val iframeSrc = parsedHtml.selectFirst("iframe")?.attr("src")
                    val videoSrc = parsedHtml.selectFirst("video")?.attr("src")
                    val resolved = if (iframeSrc != null) fixUrl(iframeSrc) else videoSrc?.let { fixUrl(it) }
                    if (resolved != null) {
                        resolvedUrls.add(resolved)
                    }
                }
            } catch (e: Exception) {
                Log.e("KultFilmler", "Failed to parse JSON sources: ${e.message}")
            }
        }

        if (resolvedUrls.isEmpty()) {
            val scripts = document.select("script")
            var foundBase64: String? = null
            for (script in scripts) {
                val text = script.html()
                if (text.contains("ContentManager")) {
                    val match = Regex("""new\s+ContentManager\s*\(\s*\[[\s\S]*?\]\s*,\s*["']([A-Za-z0-9+/=]{10,})["']""").find(text)
                    if (match != null) {
                        foundBase64 = match.groupValues[1]
                        break
                    }
                }
            }

            if (!foundBase64.isNullOrEmpty()) {
                val decodedHtml = try {
                    String(android.util.Base64.decode(foundBase64, android.util.Base64.DEFAULT))
                } catch (e: Exception) {
                    Log.e("KultFilmler", "Failed to decode base64: ${e.message}")
                    null
                }
                if (decodedHtml != null) {
                    val decodedDoc = Jsoup.parse(decodedHtml)
                    val iframeSrc = decodedDoc.selectFirst("iframe")?.attr("src")
                    val videoSrc = decodedDoc.selectFirst("video")?.attr("src")
                    val resolved = if (iframeSrc != null) fixUrl(iframeSrc) else videoSrc?.let { fixUrl(it) }
                    if (resolved != null) {
                        resolvedUrls.add(resolved)
                    }
                }
            }
        }

        if (resolvedUrls.isEmpty()) {
            Log.d("KultFilmler", "No video sources resolved")
            return false
        }

        var anySuccessful = false

        for (resolvedSrc in resolvedUrls) {
            Log.d("KultFilmler", "Processing resolved embed source: $resolvedSrc")
            if (resolvedSrc.contains("vidpapi.xyz") || resolvedSrc.contains("vidpapi")) {
                val hash = Regex("(?i)/video/([A-Fa-f0-9]+)").find(resolvedSrc)?.groupValues?.get(1)
                if (hash != null) {
                    try {
                        // Fetch the iframe page HTML to parse packed subtitles!
                        try {
                            val iframePageRes = app.get(resolvedSrc, headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to cleanData
                            ))
                            if (iframePageRes.isSuccessful) {
                                val html = iframePageRes.text
                                val unpacked = JsUnpacker.unpack(html)
                                if (unpacked != null) {
                                    val trackBlockRegex = """\{[^}]+?\.(?:srt|vtt)[^}]+?\}""".toRegex()
                                    trackBlockRegex.findAll(unpacked).forEach { match ->
                                        val block = match.value
                                        var fileUrl = Regex("""["'](?:file|url)["']\s*:\s*["']([^"']+)["']""").find(block)?.groupValues?.get(1)?.replace("\\/", "/")
                                        val label = Regex("""["']label["']\s*:\s*["']([^"']+)["']""").find(block)?.groupValues?.get(1) ?: "Türkçe"
                                        if (!fileUrl.isNullOrEmpty()) {
                                            var absoluteUrl = fileUrl
                                            if (absoluteUrl.startsWith("/")) {
                                                val uri = java.net.URI(resolvedSrc)
                                                absoluteUrl = "${uri.scheme ?: "https"}://${uri.host}$absoluteUrl"
                                            } else if (absoluteUrl.startsWith("//")) {
                                                absoluteUrl = "https:$absoluteUrl"
                                            }
                                            subtitleCallback(
                                                newSubtitleFile(
                                                    lang = label,
                                                    url = absoluteUrl
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("KultFilmler", "Failed to fetch vidpapi iframe subtitles: ${e.message}")
                        }

                        val postUrl = "https://vidpapi.xyz/player/index.php?data=$hash&do=getVideo"
                        val res = app.post(
                            postUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to resolvedSrc,
                                "X-Requested-With" to "XMLHttpRequest"
                            ),
                            data = mapOf(
                                "hash" to hash,
                                "r" to mainUrl
                            )
                        )

                        if (res.isSuccessful) {
                            val securedLink = Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(res.text)
                                ?.groupValues?.get(1)
                                ?.replace("\\/", "/")
                            if (!securedLink.isNullOrEmpty()) {
                                callback(
                                    newExtractorLink(
                                        source = "VidPapi",
                                        name = "VidPapi HD",
                                        url = securedLink,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.headers = mapOf("Referer" to resolvedSrc)
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                anySuccessful = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("KultFilmler", "VidPapi stream extraction failed: ${e.message}")
                    }
                }
            } else {
                // Fallback to the standard loader for OK.ru and other players
                if (loadExtractor(resolvedSrc, cleanData, subtitleCallback, callback)) {
                    anySuccessful = true
                }
            }
        }

        return anySuccessful
    }

    data class KfSource(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("langLabel") val langLabel: String? = null,
        @JsonProperty("html") val html: String? = null
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private fun getHeaders(referer: String): Map<String, String> = mapOf(
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "user-agent" to USER_AGENT,
            "referer" to referer
        )
    }
}

object JsUnpacker {
    fun unpack(packed: String): String? {
        val regex = """\}\s*\(\s*['"]([\s\S]*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([\s\S]*?)['"]\.split""".toRegex()
        val match = regex.find(packed) ?: return null
        
        val p = match.groupValues[1]
        val a = match.groupValues[2].toIntOrNull() ?: 36
        val c = match.groupValues[3].toIntOrNull() ?: 0
        val k = match.groupValues[4].split("|")
        
        fun baseN(num: Int, base: Int): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            if (num == 0) return "0"
            val sb = StringBuilder()
            var temp = num
            while (temp > 0) {
                sb.append(chars[temp % base])
                temp /= base
            }
            return sb.reverse().toString()
        }
        
        var result = p
        for (i in c - 1 downTo 0) {
            val word = if (i < k.size && k[i].isNotEmpty()) k[i] else baseN(i, a)
            val baseNRepresentation = baseN(i, a)
            result = result.replace(Regex("\\b$baseNRepresentation\\b"), word)
        }
        
        return result
    }
}
