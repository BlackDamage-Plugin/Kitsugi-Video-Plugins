package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

class DizirollProvider : MainAPI() {
    override var mainUrl = "https://diziroll.club"
    override var name = "Diziroll"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private var resolvedUrl = "https://diziroll.club"
    private var isResolved = false

    private suspend fun resolveDomain() {
        if (isResolved) return
        try {
            val res = app.get(mainUrl, headers = defaultHeaders, cacheTime = 60)
            if (res.isSuccessful) {
                val redirectedUrl = res.url.removeSuffix("/")
                if (redirectedUrl.startsWith("http")) {
                    resolvedUrl = redirectedUrl
                    isResolved = true
                }
            }
        } catch (e: Exception) {
            // Fallback to default
        }
    }

    override val mainPage = mainPageOf(
        "dizi-izle" to "Son Eklenen Diziler",
        "trend" to "Trend Diziler",
        "turkce-dublaj-diziler" to "Türkçe Dublaj Diziler",
        "asya-dizileri" to "Asya Dizileri",
        "animeler" to "Animeler",
        "kesfet" to "Keşfet Dizileri",
        "kategori/aksiyon" to "Aksiyon Dizileri",
        "kategori/bilim-kurgu" to "Bilim Kurgu Dizileri",
        "kategori/komedi" to "Komedi Dizileri",
        "kategori/korku" to "Korku Dizileri"
    )

    private fun extractPosterFromElement(imgEl: Element?, container: Element? = null): String? {
        val raw = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("data-srcset")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("srcset")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("data-original")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("data-lazy-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: container?.selectFirst("img[data-src]")?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: container?.selectFirst("img[data-srcset]")?.attr("data-srcset")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: container?.selectFirst("img[srcset]")?.attr("srcset")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: container?.selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: return null

        val urlCandidate = if (raw.contains(" ")) {
            val parts = raw.split(",").map { it.trim() }
            val highestRes = parts.lastOrNull { it.contains("http") || it.contains("/") } ?: parts.firstOrNull() ?: raw
            highestRes.split(" ").firstOrNull { it.startsWith("http") || it.startsWith("/") || it.startsWith("//") } ?: raw
        } else raw

        return when {
            urlCandidate.startsWith("http") -> urlCandidate
            urlCandidate.startsWith("//") -> "https:$urlCandidate"
            urlCandidate.startsWith("/") -> "$mainUrl$urlCandidate"
            else -> "$mainUrl/$urlCandidate"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val requestPath = request.data
        val pageUrl = "$resolvedUrl/$requestPath"

        val items = mutableListOf<SearchResponse>()
        try {
            val res = app.get(pageUrl, headers = defaultHeaders, cacheTime = 60)
            if (res.isSuccessful) {
                val doc = Jsoup.parse(res.text)

                val cardContainers = doc.select(".poster-mb-bx, .poster-long, div.poster-long-image, div.poster, div.card, .flex-wrap > div, article, .col-6, .col-md-3, .col-lg-2")
                for (card in cardContainers) {
                    val item = parseCard(card) ?: continue
                    items.add(item)
                }

                val allLinks = doc.select(".content a[href*='/dizi/'], main a[href*='/dizi/'], .grid a[href*='/dizi/'], .flex-wrap a[href*='/dizi/'], article a[href*='/dizi/']")
                val linksToUse = if (allLinks.isNotEmpty()) allLinks else doc.select("a[href*='/dizi/']")
                val groupedLinks = linksToUse.groupBy { fixUrl(it.attr("href")) }

                for ((href, links) in groupedLinks) {
                    if (href.contains("/sezon-") || href.contains("/kategori/") || href.contains("/oyuncular")) continue
                    if (href.endsWith("/dizi/naruto") || href.endsWith("/dizi/one-piece") || href.endsWith("/dizi/naruto/") || href.endsWith("/dizi/one-piece/")) continue
                    if (items.any { it.url == href }) continue

                    var title: String? = null
                    var posterUrl: String? = null
                    var scoreVal: Double? = null

                    for (a in links) {
                        if (title.isNullOrBlank()) {
                            val rawTitle = a.attr("title").takeIf { it.isNotBlank() && !it.contains("Dizi sayfasına") }
                                ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() && !it.contains("Dizi sayfasına") }
                                ?: a.selectFirst("h2, h3, h4, .title")?.text()?.trim()?.takeIf { it.isNotBlank() && !it.contains("Dizi sayfasına") }
                                ?: a.text().trim().takeIf { it.isNotBlank() && !it.contains("Dizi sayfasına") && it.length > 1 }
                            if (!rawTitle.isNullOrBlank()) {
                                title = Jsoup.parse(rawTitle).text().trim()
                            }
                        }
                        if (posterUrl.isNullOrBlank()) {
                            val imgEl = a.selectFirst("img")
                            posterUrl = extractPosterFromElement(imgEl, a)
                        }
                        if (scoreVal == null) {
                            val ratingText = a.selectFirst(".rating, .imdb, .score")?.text()?.trim()
                            scoreVal = ratingText?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
                        }
                    }

                    if (posterUrl.isNullOrBlank() && links.isNotEmpty()) {
                        val parent = links.first().parent()
                        if (parent != null) {
                            posterUrl = extractPosterFromElement(parent.selectFirst("img"), parent)
                        }
                    }

                    if (!title.isNullOrBlank() && title.length >= 2) {
                        val lowerTitle = title.lowercase().trim()
                        if (lowerTitle == "diziroll" || lowerTitle.contains("naruto izle") || lowerTitle.contains("one piece izle")) continue
                        items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            scoreVal?.let { if (it > 0.0) this.score = Score.from10(it) }
                        })
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = false)
    }

    private fun parseCard(card: Element): SearchResponse? {
        val aTag = card.selectFirst("a[href*='/dizi/']") ?: card.selectFirst("a") ?: return null
        val href = aTag.attr("href") ?: return null
        if (href.contains("/sezon-") || href.contains("/kategori/") || href.contains("/oyuncular")) return null
        if (href.endsWith("/dizi/naruto") || href.endsWith("/dizi/one-piece") || href.endsWith("/dizi/naruto/") || href.endsWith("/dizi/one-piece/")) return null
        val fullUrl = fixUrl(href)

        val imgEl = card.selectFirst("img")
        val title = aTag.attr("title").takeIf { it.isNotBlank() && !it.contains("Dizi sayfasına") }
            ?: imgEl?.attr("alt")?.takeIf { it.isNotBlank() && !it.contains("Dizi sayfasına") }
            ?: card.selectFirst("h2, h3, h4, .title")?.text()?.trim()?.takeIf { it.isNotBlank() && !it.contains("Dizi sayfasına") }
            ?: return null

        val cleanTitle = Jsoup.parse(title).text().trim()
        val lowerTitle = cleanTitle.lowercase()
        if (cleanTitle.isBlank() || cleanTitle.length < 2 || lowerTitle == "diziroll" || lowerTitle.contains("naruto izle") || lowerTitle.contains("one piece izle")) return null

        val fixedPoster = extractPosterFromElement(imgEl, card)

        val ratingText = card.selectFirst(".rating, .imdb, .score")?.text()?.trim()
        val scoreVal = ratingText?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

        return newTvSeriesSearchResponse(cleanTitle, fullUrl, TvType.TvSeries) {
            this.posterUrl = fixedPoster
            if (scoreVal != null && scoreVal > 0.0) this.score = Score.from10(scoreVal)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveDomain()
        val results = mutableListOf<SearchResponse>()
        try {
            val cleanQuery = query.trim()
            val res = app.post(
                "$resolvedUrl/bg/searchcontent",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$resolvedUrl/"
                ),
                data = mapOf("input" to cleanQuery)
            )

            if (res.isSuccessful) {
                val json = JSONObject(res.text)
                val htmlResult = json.optString("result", "")
                if (htmlResult.isNotEmpty()) {
                    val doc = Jsoup.parse(htmlResult)
                    val cards = doc.select(".poster-mb-bx, .poster-long, a")
                    for (card in cards) {
                        val item = parseCard(card) ?: continue
                        results.add(item)
                    }
                }
            }

            if (results.isEmpty()) {
                val htmlRes = app.get("$resolvedUrl/kesfet?q=${URLEncoder.encode(cleanQuery, "UTF-8")}", headers = defaultHeaders)
                if (htmlRes.isSuccessful) {
                    val doc = Jsoup.parse(htmlRes.text)
                    val cards = doc.select(".poster-mb-bx, .poster-long, div.poster-long-image, div.poster, div.card, .flex-wrap > div")
                    for (card in cards) {
                        val item = parseCard(card) ?: continue
                        results.add(item)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveDomain()
        val res = app.get(url, headers = defaultHeaders)
        if (!res.isSuccessful) return null

        val doc = Jsoup.parse(res.text)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst(".title, .series-title")?.text()?.trim()
            ?: return null

        val imgEl = doc.selectFirst(".poster-image img, .series-poster img, img.poster, img[data-src], img[data-srcset]")
        val fixedPoster = extractPosterFromElement(imgEl, doc)

        val plot = doc.selectFirst(".description, .synopsis, .story, .overview, p.text-sm")?.text()?.trim()
        val year = doc.selectFirst(".year, .release-date")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val tags = doc.select("a[href*='/kategori/']").map { it.text().trim() }.filter { it.isNotEmpty() }

        val episodes = mutableListOf<Episode>()
        val epElements = doc.select("a[href*='/dizi/'][href*='/sezon-'], a[href*='/bolum-']")

        for (ep in epElements) {
            val href = ep.attr("href") ?: continue
            val fullEpUrl = fixUrl(href)
            val seasonMatch = Regex("""sezon-(\d+)""").find(href)
            val episodeMatch = Regex("""bolum-(\d+)""").find(href)

            val sNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val eNum = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epName = ep.text().trim().ifEmpty { "$sNum. Sezon $eNum. Bölüm" }

            episodes.add(newEpisode(fullEpUrl) {
                this.name = epName
                this.season = sNum
                this.episode = eNum
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.posterUrl = fixedPoster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()
        var found = false
        try {
            val res = app.get(data, headers = defaultHeaders)
            if (!res.isSuccessful) return false
            val html = res.text
            val doc = Jsoup.parse(html)

            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.startsWith("about:") && !src.startsWith("javascript:")) {
                    val fullIframeUrl = fixUrl(src)
                    loadExtractor(fullIframeUrl, "$resolvedUrl/", subtitleCallback, callback)
                    found = true
                }
            }

            val playerMatch = Regex("""player_url\s*:\s*["']([^"']+)["']""").find(html)
            if (playerMatch != null) {
                val playerUrl = fixUrl(playerMatch.groupValues[1])
                loadExtractor(playerUrl, "$resolvedUrl/", subtitleCallback, callback)
                found = true
            }

            val players = listOf("0", "1", "2", "3", "4")
            for (p in players) {
                try {
                    val pUrl = if (data.contains("?")) "$data&player=$p" else "$data?player=$p"
                    val pRes = app.get(pUrl, headers = defaultHeaders)
                    if (pRes.isSuccessful) {
                        val pDoc = Jsoup.parse(pRes.text)
                        val pIframe = pDoc.selectFirst("iframe[src]")?.attr("src")
                        if (!pIframe.isNullOrBlank()) {
                            loadExtractor(fixUrl(pIframe), "$resolvedUrl/", subtitleCallback, callback)
                            found = true
                        }
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }
}
