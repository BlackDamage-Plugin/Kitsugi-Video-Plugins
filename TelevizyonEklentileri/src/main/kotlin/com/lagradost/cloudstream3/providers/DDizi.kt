package com.lagradost.cloudstream3.providers

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

private inline fun <reified T> parseJson(json: String): T {
    return mapper.readValue<T>(json)
}

private inline fun <reified T> tryParseJson(json: String): T? {
    return try {
        mapper.readValue<T>(json)
    } catch (e: Exception) {
        null
    }
}

class DDizi : MainAPI() {
    override var mainUrl              = "https://www.ddizi.im"
    override var name                 = "DDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-eklenenler1"  to "Son Eklenen Bölümler",
        "$mainUrl"                   to "Güncel Yerli Diziler",
        "$mainUrl/eski.diziler"      to "Eski Diziler"
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
        val cleanData = request.data.replace("https://www.ddizi.im", mainUrl)
        val url = if (page > 1) "${cleanData}/$page" else cleanData
        val document = app.get(url, headers = getHeaders(mainUrl)).document
    
        val home = when (request.name) {
            "Güncel Yerli Diziler" -> {
                val leftSidebar = document.select("div.left_sidebar ul.list_ li > a")
                leftSidebar.mapNotNull { el ->
                    val titleVal = el.attr("title").ifBlank { el.text() }.trim()
                    if (titleVal.isEmpty()) return@mapNotNull null
                    val rawHref = el.attr("href") ?: return@mapNotNull null
                    if (rawHref.contains("eski.diziler") || rawHref.contains("yabanci-dizi-izle")) return@mapNotNull null
                    val cleanTitle = parseTitle(titleVal).first
                    val cleanedHref = fixUrl(rawHref.replace(Regex("-\\d*-?son-bolum-izle/?$"), ""))
                    newAnimeSearchResponse(cleanTitle, cleanedHref, TvType.TvSeries) {
                        this.posterUrl = "$mainUrl/images/logo.png"
                        this.posterHeaders = getHeaders(mainUrl)
                        addDub(null)
                        addSub(null)
                    }
                }.distinctBy { it.url }
            }

            "Eski Diziler" -> {
                document.select("div.dizi-boxpost, div.dizi-boxpost-cat").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            }
        
            else -> {
                var elements = document.select("div.dizi-boxpost, div.dizi-boxpost-cat")
                if (elements.isEmpty()) elements = document.select("a[href*='/dizi/'], a[href*='/diziler/']")
                elements.mapNotNull { it.toSearchResult() }
            }
        }
    
        val hasNextPage = document.selectFirst(".pagination a:contains(Sonraki)") != null
        return newHomePageResponse(request.name, home, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = if (this.tagName() == "a") this else selectFirst("a") ?: return null
        val titleRaw = linkElement.attr("title").ifBlank { linkElement.text() }.trim() ?: return null
        val title = parseTitle(titleRaw).first
        val href = fixUrl(linkElement.attr("href") ?: return null)
        val posterUrl = selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
        }
        val ratingVal = selectFirst(".imdb, .puan, .rate, .score")?.text()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl ?: "$mainUrl/images/logo.png"
            this.posterHeaders = getHeaders(mainUrl)
            addDub(null)
            addSub(null)
            ratingVal?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveActiveDomain()
        Log.d("DDizi:", "Searching for $query")
        
        val formData = mapOf("arama" to query)
        
        val document = app.post(
            "$mainUrl/arama/", 
            data = formData, 
            headers = getHeaders(mainUrl)
        ).document
        val results = ArrayList<SearchResponse>()
        
        try {
            val boxCatResults = document.select("div.dizi-boxpost-cat").mapNotNull { it.toSearchResult() }
            if (boxCatResults.isNotEmpty()) {
                Log.d("DDizi:", "Found ${boxCatResults.size} box-cat results")
                results.addAll(boxCatResults)
            }
        } catch (e: Exception) {
            Log.d("DDizi:", "Error parsing box-cat search results: ${e.message}")
        }
        
        if (results.isEmpty()) {
            try {
                val boxResults = document.select("div.dizi-boxpost").mapNotNull { it.toSearchResult() }
                if (boxResults.isNotEmpty()) {
                    Log.d("DDizi:", "Found ${boxResults.size} box results")
                    results.addAll(boxResults)
                }
            } catch (e: Exception) {
                Log.d("DDizi:", "Error parsing box search results: ${e.message}")
            }
        }
        
        if (results.isEmpty()) {
            try {
                val altResults = document.select("div.dizi-listesi a, div.yerli-diziler li a, div.yabanci-diziler li a").mapNotNull { 
                    val title = it.text()?.trim() ?: return@mapNotNull null
                    val href = fixUrl(it.attr("href") ?: return@mapNotNull null)
                    
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = null
                    }
                }
                
                if (altResults.isNotEmpty()) {
                    Log.d("DDizi:", "Found ${altResults.size} alternative results")
                    results.addAll(altResults)
                }
            } catch (e: Exception) {
                Log.d("DDizi:", "Error parsing alternative search results: ${e.message}")
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        resolveActiveDomain()
        val cleanUrl = url.replace("https://www.ddizi.im", mainUrl)
        val document = app.get(cleanUrl, headers = getHeaders(mainUrl)).document
        val fullTitle = document.selectFirst("h1, h2, div.dizi-boxpost-cat a")?.text()?.trim() ?: ""

        val (title, season, episode) = parseTitle(fullTitle)
    
        val posterUrl = document.selectFirst("div.afis img, img.afis, img.img-back, img.img-back-cat")
            ?.let { fixUrlNull(it.attr("data-src") ?: it.attr("src")) }
    
        val plot = document.selectFirst("div.dizi-aciklama, div.aciklama, p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
    
        if (cleanUrl.contains("/dizi/") || cleanUrl.contains("/diziler/")) {
            var currentPage = 0
            var hasMorePages = true
 
            while (hasMorePages) {
                val pageUrl = if (currentPage == 0) cleanUrl else "$cleanUrl/sayfa-$currentPage"
                val pageDoc = if (currentPage == 0) document else app.get(pageUrl, headers = getHeaders(mainUrl)).document
 
                val pageEpisodes = pageDoc.select("div.bolumler a, div.sezonlar a, div.dizi-arsiv a, div.dizi-boxpost-cat a")
                    .mapNotNull { ep ->
                        val name = ep.text().trim()
                        val href = fixUrl(ep.attr("href"))
                        val (epTitle, epSeason, epEpisode) = parseTitle(name)
                        newEpisode(href) {
                            this.name = epTitle
                            this.season = epSeason
                            this.episode = epEpisode
                        }
                    }
 
                episodes.addAll(pageEpisodes)
                currentPage++
                hasMorePages = pageEpisodes.isNotEmpty() && pageDoc.selectFirst(".pagination a:contains(Sonraki)") != null
            }
        } else {
            episodes.add(newEpisode(url) {
                this.name = fullTitle
                this.season = season
                this.episode = episode
                this.description = plot
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    private fun parseTitle(fullTitle: String): Triple<String, Int, Int?> {
        val seasonMatch = Regex("""(\d+)\.?\s*Sezon""", RegexOption.IGNORE_CASE).find(fullTitle)
        val episodeMatch = Regex("""(\d+)\.?\s*Bölüm""", RegexOption.IGNORE_CASE).find(fullTitle)
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

        val cleanFullTitle = fullTitle.replace(Regex("""\s*(-|_|\s)\s*(\d*\s*son\s*bölüm\s*izle|\d*\s*son\s*bolum\s*izle|\d*\s*bölüm\s*izle|\d*\s*bolum\s*izle|izle|fragmanı|fragman)""", RegexOption.IGNORE_CASE), "").trim()
        val title = cleanFullTitle.replace(Regex("""^\d+\.?\s*|\d+\.?\s*Sezon\s*|\d+\.?\s*Bölüm\s*|Sezon Finali""", RegexOption.IGNORE_CASE), "").trim()
        return Triple(title, season, episode)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveActiveDomain()
        val cleanData = data.replace("https://www.ddizi.im", mainUrl)
        val document = app.get(cleanData, headers = getHeaders(mainUrl)).document

        // Check for iframe with YouTube in src
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (iframeSrc?.contains("youtube", ignoreCase = true) == true) {
            Log.d("DDizi:", "iframeSrc = $iframeSrc")
            
            val youtubeUrl = Regex("""id=([^&]+)""").find(iframeSrc)?.groupValues?.get(1)
            if (youtubeUrl != null) {
                Log.d("DDizi:", "Calling loadExtractor with $youtubeUrl")
                try {
                    loadExtractor("https://www.youtube.com/watch?v=$youtubeUrl", "", subtitleCallback, callback)
                    return true
                } catch (e: Exception) {
                    Log.e("DDizi:", "loadExtractor failed: ${e.message}", e)
                }
            }
        }

        // Proceed to og:video extraction if YouTube iframe is not present or fails
        val ogVideo = document.selectFirst("iframe")?.attr("src")
            ?: return loadExtractor(cleanData, cleanData, subtitleCallback, callback) // Fallback to loadExtractor if no og:video
        Log.d("DDizi:", "iframe src $ogVideo")
        val playerUrl = if (ogVideo.startsWith("http")) ogVideo else "${mainUrl.removeSuffix("/")}/${ogVideo.removePrefix("/")}"
        val playerDoc = app.get(playerUrl, headers = getHeaders(cleanData)).document
        val jwScript = playerDoc.select("script").firstOrNull { it.html().contains("jwplayer") && it.html().contains("sources") }
            ?: return loadExtractor(ogVideo, cleanData, subtitleCallback, callback) // Fallback to loadExtractor if no JW script
        Log.d("DDizi:", "jwScript $jwScript")
        
        // Fallback: JSON Parse attempt
        try {
            val sourcesBlock = Regex("""sources:\s*(\[.*?\])\s*,""", RegexOption.DOT_MATCHES_ALL).find(jwScript.html())?.groupValues?.get(1)
            if (sourcesBlock != null) {
                val validJson = sourcesBlock.replace(Regex("""([{,]\s*)([a-zA-Z0-9_]+)\s*:"""), "$1\"$2\":").replace("'", "\"")
                val sources = parseJson<List<Map<String, String>>>(validJson)
                sources.forEach { source ->
                    val fileUrl = source["file"] ?: return@forEach
                    val isHls = fileUrl.contains(".m3u8") || fileUrl.contains("/hls/") || fileUrl.contains("master.txt")
                    val quality = source["label"] ?: "Auto"
                    val videoHeaders = if (fileUrl.contains("master.txt")) mapOf("accept" to "*/*", "user-agent" to USER_AGENT, "referer" to "$mainUrl/") else getHeaders("$mainUrl/")
                    callback.invoke(newExtractorLink(name, "$name - $quality", fileUrl, if (isHls) ExtractorLinkType.M3U8 else INFER_TYPE) {
                        this.quality = getQualityFromName(quality)
                        headers = mapOf("Referer" to "$mainUrl/")
                    })
                    if (isHls) M3u8Helper.generateM3u8(name, fileUrl, "$mainUrl/", headers = videoHeaders).forEach(callback)
                }
                if (sources.isNotEmpty()) return true
            }
        } catch (e: Exception) {
            Log.e("DDizi", "JSON Parse failed, falling back to Regex: ${e.message}")
        }

        // Original Regex (Fallback)
        val sourcesRegex = Regex("""sources:\s*\[\s*\{(.*?)\}\s*,?\s*\]""", RegexOption.DOT_MATCHES_ALL)
        val fileRegex = Regex("""file:\s*["'](.*?)["']""")
        val sourcesMatch = sourcesRegex.find(jwScript.html()) ?: return false
        val fileUrl = fileRegex.find(sourcesMatch.groupValues[1])?.groupValues?.get(1) ?: return false
        Log.d("DDizi: fileurl", fileUrl)
        val isHls = fileUrl.contains(".m3u8") || fileUrl.contains("/hls/") || fileUrl.contains("master.txt")
        val quality = Regex("""label:\s*["'](.*?)["']""").find(sourcesMatch.groupValues[1])?.groupValues?.get(1) ?: "Auto"
        val videoHeaders = if (fileUrl.contains("master.txt")) {
            mapOf(
                "accept" to "*/*",
                "user-agent" to USER_AGENT,
                "referer" to "$mainUrl/"
            )
        } else {
            getHeaders("$mainUrl/")
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name - $quality",
                url = fileUrl,
                type = if (isHls) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                this.quality = getQualityFromName(quality)
                headers = mapOf("Referer" to "$mainUrl/")
              }
        )

        if (isHls) {
            M3u8Helper.generateM3u8(name, fileUrl, "$mainUrl/", headers = videoHeaders).forEach(callback)
        }

        return true
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        private fun getHeaders(referer: String): Map<String, String> = mapOf(
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "user-agent" to USER_AGENT,
            "referer" to referer
        )
    }
}
