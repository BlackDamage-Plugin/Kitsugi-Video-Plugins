// ! Bu araÃƒÂ§ @caca1403 tarafÃ„Â±ndan yapÃ„Â±lmÃ„Â±Ã…Å¸tÃ„Â±r.

package com.lagradost.cloudstream3.providers

import android.net.Uri
import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import kotlinx.coroutines.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest


class FilmMakinesi : MainAPI() {
    override var mainUrl              = "https://filmmakinesi.to"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    private var isDomainResolved = false
    private suspend fun resolveDomain() {
        if (isDomainResolved) return
        try {
            val domains = listOf("https://filmmakinesi.to", "https://filmmakinesi.pw", "https://filmmakinesi.net", "https://filmmakinesi.de")
            for (domain in domains) {
                try {
                    val res = app.get(domain, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"), cacheTime = 60)
                    if (res.isSuccessful) {
                        val redirectedUrl = res.url.removeSuffix("/")
                        if (redirectedUrl.startsWith("http")) {
                            mainUrl = redirectedUrl
                            isDomainResolved = true
                            break
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    private var selcukflixUrl = "https://selcukflix.co"
    private var isSelcukflixResolved = false

    private suspend fun resolveSelcukflix() {
        if (isSelcukflixResolved) return
        try {
            val res = app.get(
                "https://selcukflix.co",
                headers = mapOf("User-Agent" to USER_AGENT),
                cacheTime = 60
            )
            if (res.isSuccessful) {
                val redirectedUrl = res.url.removeSuffix("/")
                if (redirectedUrl.startsWith("http")) {
                    selcukflixUrl = redirectedUrl
                    isSelcukflixResolved = true
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
    }



    private fun decryptSelcukflix(encryptedText: String): String {
        return try {
            val baseKey = "!!22xx!!90!!"
            val md = MessageDigest.getInstance("SHA-256")
            val sha256Bytes = md.digest(baseKey.toByteArray(Charsets.UTF_8))
            val keyBase64 = Base64.encodeToString(sha256Bytes, Base64.NO_WRAP)
            val key32 = keyBase64.substring(0, 32)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(key32.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(ByteArray(16))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            val encryptedBytes = Base64.decode(encryptedText, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // ! CloudFlare bypass
    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 20L
    override var sequentialMainPageScrollDelay = 20L

    override val mainPage = mainPageOf(
        // FÃ„Â°LMLER
        "${mainUrl}/filmler-1/sayfa/"                                to "Son Filmler",
        "${mainUrl}/tur/aksiyon-fm1/film/sayfa/"                       to "Aksiyon Filmleri",
        "${mainUrl}/tur/aile-fm2/film/sayfa/"                          to "Aile Filmleri",
        "${mainUrl}/tur/animasyon-fm2/film/sayfa/"                     to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel/film/sayfa/"                          to "Belgesel Filmleri",
        "${mainUrl}/tur/biyografi/film/sayfa/"                         to "Biyografi Filmleri",
        "${mainUrl}/tur/bilim-kurgu-fm3/film/sayfa/"                   to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/dram-fm1/film/sayfa/"                          to "Dram Filmleri",
        "${mainUrl}/tur/fantastik-fm1/film/sayfa/"                     to "Fantastik Filmleri",
        "${mainUrl}/tur/gerilim-fm1/film/sayfa/"                       to "Gerilim Filmleri",
        "${mainUrl}/tur/gizem/film/sayfa/"                             to "Gizem Filmleri",
        "${mainUrl}/tur/komedi-fm1/film/sayfa/"                        to "Komedi Filmleri",
        "${mainUrl}/tur/korku-fm1/film/sayfa/"                         to "Korku Filmleri",
        "${mainUrl}/tur/macera-fm1/film/sayfa/"                        to "Macera Filmleri",
        "${mainUrl}/tur/romantik-fm1/film/sayfa/"                      to "Romantik Filmleri",
        "${mainUrl}/tur/tarih-fm1/film/sayfa/"                         to "Tarih Filmleri",
        
        // DÃ„Â°ZÃ„Â°LER
        "${mainUrl}/yabanci-dizi-izle-1/sayfa/"                       to "Son Diziler",
        "${mainUrl}/tur/aksiyon-fm1/dizi/sayfa/"                       to "Aksiyon Dizileri",
        "${mainUrl}/tur/aile-fm2/dizi/sayfa/"                          to "Aile Dizileri",
        "${mainUrl}/tur/animasyon-fm2/dizi/sayfa/"                     to "Animasyon Dizileri",
        "${mainUrl}/tur/belgesel/dizi/sayfa/"                          to "Belgesel Dizileri",
        "${mainUrl}/tur/biyografi/dizi/sayfa/"                         to "Biyografi Dizileri",
        "${mainUrl}/tur/bilim-kurgu-fm3/dizi/sayfa/"                   to "Bilim Kurgu Dizileri",
        "${mainUrl}/tur/dram-fm1/dizi/sayfa/"                          to "Dram Dizileri",
        "${mainUrl}/tur/fantastik-fm1/dizi/sayfa/"                     to "Fantastik Dizileri",
        "${mainUrl}/tur/gerilim-fm1/dizi/sayfa/"                       to "Gerilim Dizileri",
        "${mainUrl}/tur/gizem/dizi/sayfa/"                             to "Gizem Dizileri",
        "${mainUrl}/tur/komedi-fm1/dizi/sayfa/"                        to "Komedi Dizileri",
        "${mainUrl}/tur/korku-fm1/dizi/sayfa/"                         to "Korku Dizileri",
        "${mainUrl}/tur/macera-fm1/dizi/sayfa/"                        to "Macera Dizileri",
        "${mainUrl}/tur/romantik-fm1/dizi/sayfa/"                      to "Romantik Dizileri",
        "${mainUrl}/tur/tarih-fm1/dizi/sayfa/"                         to "Tarih Dizileri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        val cleanedUrl = request.data.removeSuffix("/")
        val url = if (page > 1) {
            "$cleanedUrl/$page/"
        } else {
            cleanedUrl.replace(Regex("/sayfa/?$"), "")
        }

        val extraUrl = when (request.name) {
            "Gizem Filmleri", "Gerilim Filmleri" -> {
                val cleanedPolisiyeUrl = "${mainUrl}/tur/polisiye/film/sayfa/"
                if (page > 1) "$cleanedPolisiyeUrl$page/" else cleanedPolisiyeUrl
            }
            "Gizem Dizileri", "Gerilim Dizileri" -> {
                val cleanedPolisiyeUrl = "${mainUrl}/tur/polisiye/dizi/sayfa/"
                if (page > 1) "$cleanedPolisiyeUrl$page/" else cleanedPolisiyeUrl
            }
            else -> null
        }

        val home = if (extraUrl != null) {
            coroutineScope {
                val doc1Deferred = async {
                    try {
                        app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl), cacheTime = 180).document
                    } catch (e: Exception) { null }
                }
                val doc2Deferred = async {
                    try {
                        app.get(extraUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl), cacheTime = 180).document
                    } catch (e: Exception) { null }
                }
                
                val doc1 = doc1Deferred.await()
                val doc2 = doc2Deferred.await()
                
                val list1 = doc1?.select("div.film-list div.item-relative")?.mapNotNull { it.toSearchResult() } ?: emptyList()
                val list2 = doc2?.select("div.film-list div.item-relative")?.mapNotNull { it.toSearchResult() } ?: emptyList()
                
                (list1 + list2).distinctBy { it.url }
            }
        } else {
            val document = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            ), cacheTime = 180).document

            document.select("div.film-list div.item-relative")
                .mapNotNull { it.toSearchResult() }
        }

        Log.d("FLMM", "Toplam iÃƒÂ§erik: ${home.size}")
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("a.item") ?: return null
        val title = aTag.attr("data-title").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        
        val imgEl = aTag.selectFirst("img")
        // Posterler site tarafÃ„Â±ndan .webp thumb olarak src'de geliyor, data-src genellikle boÃ…Å¸
        val posterUrl = fixUrlNull(
            imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
        )

        val tags = select(".item-info div").map { it.text().trim() }.filter { it.isNotBlank() }

        val isDizi = href.contains("/dizi/")
        val type = if (isDizi) TvType.TvSeries else TvType.Movie

        // Dub/Sub: sitedeki tag'lardan oku Ã¢â‚¬â€ dizilerde de filmler gibi koÃ…Å¸ulla
        val hasDub = tags.any {
            it.equals("Dual", ignoreCase = true)
            || it.contains("Dublaj", ignoreCase = true)
            || it.contains("TÃƒÂ¼rkÃƒÂ§e Dub", ignoreCase = true)
        }
        val hasSub = tags.any {
            it.equals("Dual", ignoreCase = true)
            || it.contains("AltyazÃ„Â±", ignoreCase = true)
            || it.contains("Sub", ignoreCase = true)
        }

        val imdbRating = tags.firstOrNull { it.matches(Regex("\\d+\\.\\d+")) }?.toDoubleOrNull()

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            if (hasDub) addDub(1)
            if (hasSub) addSub(1)
            imdbRating?.takeIf { it > 0.0 }?.let { this.score = Score.from10(it) }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.select("a").last()?.text() ?: return null
        val href  = fixUrlNull(this.select("a").last()?.attr("href")) ?: return null
        val imgEl = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
        )
        val isDizi = href.contains("/dizi/")
        val type   = if (isDizi) TvType.TvSeries else TvType.Movie
        return newAnimeSearchResponse(title, href, type) { this.posterUrl = posterUrl }
    }



    private suspend fun searchSelcukflix(query: String): List<SearchResponse> {
        try {
            resolveSelcukflix()
            val baseUrl = selcukflixUrl
            val searchApiUrl = "$baseUrl/api/bg/searchContent?searchterm=${Uri.encode(query)}"
            val searchResponse = app.post(
                searchApiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$baseUrl/",
                    "Content-Type" to "application/json"
                ),
                json = mapOf<String, String>()
            ).text
            
            val searchJsonObj = org.json.JSONObject(searchResponse)
            val encodedResponse = searchJsonObj.optString("response", "")
            if (encodedResponse.isEmpty()) return emptyList()
            
            val decodedText = decryptSelcukflix(encodedResponse)
            if (decodedText.isEmpty()) return emptyList()
            
            val decodedJsonObj = org.json.JSONObject(decodedText)
            val resultList = decodedJsonObj.optJSONArray("result") ?: return emptyList()
            
            val results = mutableListOf<SearchResponse>()
            for (i in 0 until resultList.length()) {
                val r = resultList.optJSONObject(i) ?: continue
                val objectName = r.optString("object_name", "")
                val usedSlug = r.optString("used_slug", "")
                val usedType = r.optString("used_type", "")
                val poster = r.optString("object_poster", "")
                
                val href = "$baseUrl/$usedSlug"
                val type = if (usedType == "Series") TvType.TvSeries else TvType.Movie
                
                results.add(
                    newAnimeSearchResponse(objectName, href, type) {
                        this.posterUrl = fixUrlNull(poster)
                    }
                )
            }
            return results
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.lowercase().trim()
            .replace(Regex("""\s*(?:filmi|dizisi|filmleri|dizileri|izle)$"""), "")
            .trim()

        val categorySlug = when (cleanQuery) {
            "aksiyon" -> "aksiyon-fm1"
            "aile" -> "aile-fm2"
            "animasyon" -> "animasyon-fm2"
            "belgesel" -> "belgesel"
            "biyografi" -> "biyografi"
            "bilim kurgu", "bilimkurgu" -> "bilim-kurgu-fm3"
            "dram" -> "dram-fm1"
            "fantastik" -> "fantastik-fm1"
            "gerilim" -> "gerilim-fm1"
            "gizem" -> "gizem"
            "komedi" -> "komedi-fm1"
            "korku" -> "korku-fm1"
            "macera" -> "macera-fm1"
            "mÃƒÂ¼zik", "muzik" -> "muzik"
            "polisiye" -> "polisiye"
            "romantik" -> "romantik-fm1"
            "savaÃ…Å¸", "savas" -> "savas-fm1"
            "spor" -> "spor"
            "tarih" -> "tarih-fm1"
            "western" -> "western-fm1"
            else -> null
        }

        if (categorySlug != null) {
            val list = mutableListOf<SearchResponse>()
            val urls = listOf(
                "${mainUrl}/tur/$categorySlug/film/",
                "${mainUrl}/tur/$categorySlug/dizi/"
            )
            urls.forEach { url ->
                try {
                    val document = app.get(url, cacheTime = 0).document
                    val items = document.select("div.film-list div.item-relative").mapNotNull { it.toSearchResult() }
                    list.addAll(items)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return list.distinctBy { it.url }
        }

        val results = mutableListOf<SearchResponse>()
        try {
            val document = app.get("${mainUrl}/arama/?s=${query}", cacheTime = 0).document
            val nativeItems = document.select("div.film-list div.item-relative").mapNotNull { it.toSearchResult() }
            results.addAll(nativeItems)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val fallbackItems = coroutineScope {
            val selcukflixDeferred = async { searchSelcukflix(query) }
            try {
                selcukflixDeferred.await()
            } catch (e: Exception) {
                emptyList<SearchResponse>()
            }
        }

        fallbackItems.forEach { item ->
            val cleanItemTitle = item.name.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
            val exists = results.any { nativeItem ->
                val cleanNativeTitle = nativeItem.name.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
                cleanNativeTitle.contains(cleanItemTitle) || cleanItemTitle.contains(cleanNativeTitle)
            }
            if (!exists) {
                results.add(item)
            }
        }

        return results.distinctBy { it.url }
    }


    private suspend fun loadSelcukflix(url: String): LoadResponse? {
        try {
            resolveSelcukflix()
            val targetPageHtml = app.get(url, cacheTime = 0).text
            val secureData = Regex("""\"secureData\"\s*:\s*\"([^\"]+)\"""").find(targetPageHtml)?.groupValues?.get(1) ?: return null
            
            val decryptedText = decryptSelcukflix(secureData)
            if (decryptedText.isEmpty()) return null
            
            val decodedJsonObj = org.json.JSONObject(decryptedText)
            val contentItem = decodedJsonObj.optJSONObject("contentItem") ?: return null
            
            val title = contentItem.optString("original_title", "").takeIf { it.isNotEmpty() } 
                ?: contentItem.optString("used_title", "")
            val poster = fixUrlNull(contentItem.optString("poster_url", ""))
            val description = contentItem.optString("description", "")
            val year = contentItem.optInt("release_year", 0).takeIf { it > 0 }
            val categoriesStr = contentItem.optString("categories", "")
            val tags = if (categoriesStr.isNotEmpty()) categoriesStr.split(",").map { it.trim() } else null
            
            val isDizi = url.contains("/dizi/")
            
            if (isDizi) {
                val relatedResults = decodedJsonObj.optJSONObject("RelatedResults") ?: return null
                val getSerieSeasonAndEpisodes = relatedResults.optJSONObject("getSerieSeasonAndEpisodes") ?: return null
                val seasons = getSerieSeasonAndEpisodes.optJSONArray("result") ?: return null
                
                val episodes = mutableListOf<Episode>()
                for (i in 0 until seasons.length()) {
                    val s = seasons.optJSONObject(i) ?: continue
                    val seasonNumber = s.optInt("season_no")
                    val episodesList = s.optJSONArray("episodes") ?: continue
                    for (j in 0 until episodesList.length()) {
                        val ep = episodesList.optJSONObject(j) ?: continue
                        val episodeNumber = ep.optInt("episode_no")
                        val epSlug = ep.optString("used_slug", "")
                        val epTitle = ep.optString("episode_subtitle", "")
                        if (epSlug.isNotEmpty()) {
                            val epUrl = "$selcukflixUrl/$epSlug"
                            episodes.add(newEpisode(epUrl) {
                                this.name = epTitle.takeIf { it.isNotEmpty() } ?: ep.optString("episode_text", "")
                                this.season = seasonNumber
                                this.episode = episodeNumber
                            })
                        }
                    }
                }
                
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                }
            } else {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("selcukflix")) {
            return loadSelcukflix(url)
        }
        try {
            val document = try {
                app.get(url, headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer"    to mainUrl
                ), cacheTime = 0).document
            } catch (e: Exception) {
                Log.e("FLMM", "load() network hatasÃ„Â±: ${e.message}")
                return null
            }

            val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
            val poster          = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
            
            // YardÃ„Â±mcÃ„Â± dt/dd okuma fonksiyonu (eski tasarÃ„Â±m uyumluluÃ„Å¸u iÃƒÂ§in %100 gÃƒÂ¼venli)
            val getOldMeta = { label: String ->
                val dt = document.select("dt").firstOrNull { it.text().contains(label, ignoreCase = true) }
                dt?.nextElementSibling()
            }

            // Robust description fallback
            val description     = document.select("div.info-description p, div.info-content p").last()?.text()?.trim()
                ?: document.selectFirst("div.info-description")?.text()?.trim()
                
            // Robust tags/genres fallback
            val tags            = document.select("div.info div.type a").map { it.text().trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                ?: getOldMeta("TÃƒÂ¼r")?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }
                ?: getOldMeta("TÃƒÂ¼r")?.text()?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() }
                
            // Robust year fallback
            val year            = (document.selectFirst("span.date a, a[href*='/yil/']")?.text()?.trim() ?: getOldMeta("YapÃ„Â±m YÃ„Â±lÃ„Â±")?.text()?.trim())?.toIntOrNull()
                ?: Regex("\\b(19\\d\\d|20\\d\\d)\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()

            // Robust duration fallback
            val durationText    = document.selectFirst("div.time")?.text() ?: getOldMeta("Film SÃƒÂ¼resi")?.text() ?: ""
            val durationElement = getOldMeta("Film SÃƒÂ¼resi")?.selectFirst("time")?.attr("datetime") ?: ""
            val duration        = if (durationElement.startsWith("PT") && durationElement.endsWith("M")) {
                durationElement.drop(2).dropLast(1).toIntOrNull() ?: 0
            } else {
                Regex("(\\d+)").find(durationText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }

            // Recommendations
            val recommendations = document.select("div.film-list div.item-relative").mapNotNull { it.toRecommendResult() }
            
            // Robust actors fallback (with actor images!)
            val actors          = document.select("div.oyuncu-list a.cast").mapNotNull { cast ->
                val name = cast.selectFirst("div.cast-name")?.text()?.trim() ?: return@mapNotNull null
                val image = fixUrlNull(cast.selectFirst("img")?.attr("src") ?: cast.selectFirst("img")?.attr("data-src"))
                Actor(name, image)
            }.takeIf { it.isNotEmpty() }
                ?: getOldMeta("Oyuncular")?.text()?.split(", ")?.map {
                    Actor(it.trim())
                }

            // Robust trailer fallback
            val trailer = document.selectFirst("a.trailer-button, div.left a.trailer-button")?.attr("data-video_url")?.substringAfter("embed/", "")?.let { 
                if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null 
            }

            if (url.contains("/dizi/")) {
                val episodes = document.select("div.tab-pane").flatMap { tab ->
                    val seasonMatch = Regex("tab-sezon-(\\d+)").find(tab.attr("id") ?: "")
                    val seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                    tab.select("a.item-ep").mapNotNull { el ->
                        val epHref = el.attr("href") ?: return@mapNotNull null
                        val epUrl = fixUrl(epHref)
                        val epTitle = el.selectFirst("div.ep-details span")?.text()?.trim()
                        
                        val epTitleText = el.selectFirst("div.ep-title")?.text()?.trim() ?: ""
                        val episodeMatch = Regex("(\\d+)\\.\\s*BÃƒÂ¶lÃƒÂ¼m").find(epTitleText)
                        val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull()
                        
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                        }
                    }
                }
                
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl       = poster
                    this.year            = year
                    this.plot            = description
                    this.tags            = tags
                    this.recommendations = recommendations
                    addActors(actors)
                    addTrailer(trailer)
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
        } catch (e: Exception) {
            Log.e("FLMM", "load() iÃƒÂ§inde beklenmeyen hata: ${e.message}")
            e.printStackTrace()
            return null
        }
    }


    private suspend fun loadSelcukflixLinksDirect(
        url: String, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            resolveSelcukflix()
            val targetPageHtml = app.get(url, cacheTime = 0).text
            val secureData = Regex("""\"secureData\"\s*:\s*\"([^\"]+)\"""").find(targetPageHtml)?.groupValues?.get(1) ?: return false
            
            val decryptedText = decryptSelcukflix(secureData)
            if (decryptedText.isEmpty()) return false
            
            val decodedPageJson = org.json.JSONObject(decryptedText)
            val relatedResults = decodedPageJson.optJSONObject("RelatedResults") ?: return false
            
            val sources = mutableListOf<String>()
            
            val getEpisodeSources = relatedResults.optJSONObject("getEpisodeSources")
            if (getEpisodeSources != null) {
                val epSourcesList = getEpisodeSources.optJSONArray("result")
                if (epSourcesList != null) {
                    for (i in 0 until epSourcesList.length()) {
                        val s = epSourcesList.optJSONObject(i) ?: continue
                        val sourceContent = s.optString("source_content", "")
                        val src = Regex("""src=["']([^"']+)["']""").find(sourceContent)?.groupValues?.get(1) ?: ""
                        if (src.isNotEmpty()) {
                            sources.add(if (src.startsWith("//")) "https:$src" else src)
                        }
                    }
                }
            }
            
            val keys = relatedResults.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("getMoviePartSourcesById_")) {
                    val partObj = relatedResults.optJSONObject(key) ?: continue
                    val partResult = partObj.optJSONArray("result") ?: continue
                    for (i in 0 until partResult.length()) {
                        val s = partResult.optJSONObject(i) ?: continue
                        val sourceContent = s.optString("source_content", "")
                        val src = Regex("""src=["']([^"']+)["']""").find(sourceContent)?.groupValues?.get(1) ?: ""
                        if (src.isNotEmpty()) {
                            sources.add(if (src.startsWith("//")) "https:$src" else src)
                        }
                    }
                }
            }
            
            if (sources.isEmpty()) return false
            
            val finalSources = mutableListOf<Triple<String, String, String>>()
            
            sources.distinct().forEach { iframeUrl ->
                try {
                    val iframeHtml = app.get(iframeUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$selcukflixUrl/"), cacheTime = 0).text
                    val playlistKey = Regex("""openPlayer\(\s*'([^']+)'""").find(iframeHtml)?.groupValues?.get(1) ?: ""
                    if (playlistKey.isNotEmpty()) {
                        val iframeDomain = Regex("https?://([^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: "four.pichive.online"
                        val apiUrl = "https://$iframeDomain/source2.php?v=${Uri.encode(playlistKey)}"
                        val apiResponse = app.get(
                            apiUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to iframeUrl,
                                "X-Requested-With" to "XMLHttpRequest"
                            ),
                            cacheTime = 0
                        ).text
                        
                        val apiJson = org.json.JSONObject(apiResponse)
                        if (apiJson.optBoolean("state")) {
                            val playlist = apiJson.optJSONArray("playlist")
                            if (playlist != null) {
                                for (i in 0 until playlist.length()) {
                                    val item = playlist.optJSONObject(i) ?: continue
                                    val itemTitle = item.optString("title", item.optString("label", ""))
                                    val sourcesArray = item.optJSONArray("sources") ?: continue
                                    for (j in 0 until sourcesArray.length()) {
                                        val sourceObj = sourcesArray.optJSONObject(j) ?: continue
                                        val fileUrl = sourceObj.optString("file", "")
                                        val sourceTitle = sourceObj.optString("title", sourceObj.optString("label", sourceObj.optString("name", "")))
                                        val title = when {
                                            sourceTitle.isNotEmpty() -> sourceTitle
                                            itemTitle.isNotEmpty() -> itemTitle
                                            else -> "Video"
                                        }
                                        if (fileUrl.isNotEmpty()) {
                                            val streamUrl = if (fileUrl.contains("m.php")) fileUrl.replace("m.php", "master.m3u8") else fileUrl
                                            finalSources.add(Triple(streamUrl, title, "https://$iframeDomain/"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            var found = false
            finalSources.distinctBy { it.first }.forEach { (streamUrl, title, iframeReferer) ->
                Log.d("FLMM", "Selcukflix Final Source Direct: $streamUrl ($title)")
                val displayTitle = when {
                    title.contains("Dublaj", ignoreCase = true) || title.contains("tr", ignoreCase = true) -> "Dublaj"
                    title.contains("AltyazÃ„Â±", ignoreCase = true) || title.contains("sub", ignoreCase = true) -> "AltyazÃ„Â±"
                    else -> title
                }
                callback(
                    newExtractorLink(
                        source = "Selcukflix",
                        name = "Selcukflix - $displayTitle",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframeReferer
                        this.headers = mapOf(
                            "Referer" to iframeReferer,
                            "Origin" to iframeReferer.removeSuffix("/"),
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*"
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
            return found
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.contains("selcukflix")) {
            return loadSelcukflixLinksDirect(data, subtitleCallback, callback)
        }
        Log.d("FLMM", "data Ã‚Â» $data")
        val document = try {
            app.get(data, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer"    to mainUrl
            ), cacheTime = 0).document
        } catch (e: Exception) {
            Log.e("FLMM", "loadLinks() hata: ${e.message}")
            return false
        }
        val iframes = document.select("iframe").mapNotNull {
            val dSrc = it.attr("data-src")
            val src = it.attr("src")
            if (dSrc.isNotEmpty()) dSrc else if (src.isNotEmpty()) src else null
        }
        val videoUrls = document.select("[data-video_url]").map { it.attr("data-video_url") }
        val hrefUrls = document.select(".video-parts a, .video-options a").map { it.attr("href") ?: it.attr("data-href") ?: "" }
        val allUrls = (iframes + videoUrls + hrefUrls).filter { 
            it.isNotEmpty() && !it.contains("youtube.com") && !it.contains("youtu.be") 
        }.distinct()

        var foundNativeLinks = false
        val nativeCallback = { link: ExtractorLink ->
            foundNativeLinks = true
            callback(link)
        }

        allUrls.forEach { url ->
            Log.d("FLMM", "Processing URL: $url")
            if (url.contains("closeload") || url.contains("rapid")) {
                try {
                    CloseLoad().getUrl(url, "${mainUrl}/", subtitleCallback, nativeCallback)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                loadExtractor(url, "${mainUrl}/", subtitleCallback, nativeCallback)
            }
        }

        // Output "YOK" dummy links for any sites that found absolutely 0 links
        if (!foundNativeLinks) {
            callback(
                newExtractorLink(
                    source = "FilmMakinesi",
                    name = "FilmMakinesi - YOK",
                    url = "https://filmmakinesi.to/yok",
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return allUrls.isNotEmpty()
    }
}
