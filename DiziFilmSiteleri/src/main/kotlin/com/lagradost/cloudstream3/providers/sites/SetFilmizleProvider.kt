package com.lagradost.cloudstream3.providers

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

class SetFilmizleProvider : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.uk"
    override var name = "SetFilmizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val MOBILE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private var resolvedUrl = "https://www.setfilmizle.uk"
    private var isResolved = false

    private var selcukflixUrl = "https://selcukflix.co"
    private var isSelcukflixResolved = false

    private suspend fun resolveDomain() {
        if (isResolved) return
        try {
            val res = app.get(
                "https://www.setfilmizle.uk",
                headers = mapOf("User-Agent" to MOBILE_USER_AGENT),
                cacheTime = 60
            )
            if (res.isSuccessful) {
                val redirectedUrl = res.url.removeSuffix("/")
                if (redirectedUrl.startsWith("http")) {
                    resolvedUrl = redirectedUrl
                    isResolved = true
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
    }

    private suspend fun resolveSelcukflix() {
        if (isSelcukflixResolved) return
        try {
            val res = app.get(
                "https://selcukflix.co",
                headers = mapOf("User-Agent" to MOBILE_USER_AGENT),
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
            ""
        }
    }

    override val mainPage = mainPageOf(
        "dizi/page/" to "Son Eklenen Diziler",
        "film/page/" to "Son Eklenen Filmler",
        "tur/aksiyon/page/" to "Aksiyon & Macera",
        "tur/bilim-kurgu/page/" to "Bilim Kurgu & Fantastik",
        "tur/komedi/page/" to "Komedi Dizileri & Filmleri",
        "tur/korku/page/" to "Korku & Gerilim",
        "tur/dram/page/" to "Dram Dizileri & Filmleri",
        "tur/suc/page/" to "Suç & Polisiye",
        "tur/gizem/page/" to "Gizem",
        "tur/animasyon/page/" to "Animasyon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        val url = "$resolvedUrl/${request.data}$page/"
        val response = app.get(url, headers = mapOf("User-Agent" to MOBILE_USER_AGENT), cacheTime = 180).text
        val doc = Jsoup.parse(response)
        
        val items = doc.select("article.item").mapNotNull { item ->
            val aTag = item.selectFirst("div.poster a") ?: return@mapNotNull null
            val href = aTag.attr("href") ?: return@mapNotNull null
            val img = aTag.selectFirst("img")
            val imgTitle = img?.attr("alt") ?: ""
            val posterUrl = img?.attr("data-src").takeIf { !it.isNullOrEmpty() } ?: img?.attr("src")
            
            val cleanTitle = imgTitle.replace("Poster", "").trim()
            val isDizi = href.contains("/dizi/")
            val type = if (isDizi) TvType.TvSeries else TvType.Movie
            
            val hasDub = item.select("span.flm-turkce-dublaj, span.blm-turkce-dublaj").isNotEmpty()
            val hasSub = item.select("span.flm-turkce-altyazi, span.blm-turkce-altyazi").isNotEmpty()

            newAnimeSearchResponse(cleanTitle, href, type) {
                this.posterUrl = posterUrl
                if (hasDub) addDub(1)
                if (hasSub) addSub(1)
            }
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveDomain()
        val searchUrl = "$resolvedUrl/?s=${Uri.encode(query)}"
        val response = app.get(searchUrl, headers = mapOf("User-Agent" to MOBILE_USER_AGENT)).text
        val doc = Jsoup.parse(response)
        
        return doc.select("article.item").mapNotNull { item ->
            val aTag = item.selectFirst("div.poster a") ?: return@mapNotNull null
            val href = aTag.attr("href") ?: return@mapNotNull null
            val img = aTag.selectFirst("img")
            val imgTitle = img?.attr("alt") ?: ""
            val posterUrl = img?.attr("data-src").takeIf { !it.isNullOrEmpty() } ?: img?.attr("src")
            
            val cleanTitle = imgTitle.replace("Poster", "").trim()
            val isDizi = href.contains("/dizi/")
            val type = if (isDizi) TvType.TvSeries else TvType.Movie
            
            val hasDub = item.select("span.flm-turkce-dublaj, span.blm-turkce-dublaj").isNotEmpty()
            val hasSub = item.select("span.flm-turkce-altyazi, span.blm-turkce-altyazi").isNotEmpty()

            newAnimeSearchResponse(cleanTitle, href, type) {
                this.posterUrl = posterUrl
                if (hasDub) addDub(1)
                if (hasSub) addSub(1)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveDomain()
        val mainDoc = app.get(url, headers = mapOf("User-Agent" to MOBILE_USER_AGENT)).document
        val title = mainDoc.selectFirst("h1")?.text()?.substringBefore("izle")?.trim() ?: return null
        val poster = fixUrlNull(mainDoc.selectFirst("div.poster img")?.attr("src") ?: mainDoc.selectFirst("div.poster img")?.attr("data-src"))
        val description = mainDoc.selectFirst("div.wp-content p")?.text()?.trim()
        val year = mainDoc.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        
        val isDizi = url.contains("/dizi/")
        val tags = mainDoc.select("a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotEmpty() }
        val actors = mainDoc.select("a[href*='/oyuncu/']").map { Actor(it.text().trim()) }

        if (isDizi) {
            // Fetch all season pages (e.g. /dizi/name/2-sezon/) to extract ALL season 2+ episodes
            val seasonUrls = mainDoc.select("a[href*='sezon']").mapNotNull { it.attr("href") }
                .filter { it.contains("/dizi/") }
                .map { fixUrl(it) }
                .distinct()

            val docsToScan = mutableListOf(mainDoc)

            if (seasonUrls.isNotEmpty()) {
                coroutineScope {
                    val deferredDocs = seasonUrls.map { sUrl ->
                        async {
                            try {
                                app.get(sUrl, headers = mapOf("User-Agent" to MOBILE_USER_AGENT), cacheTime = 180).document
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                    docsToScan.addAll(deferredDocs.awaitAll().filterNotNull())
                }
            }

            val episodes = mutableListOf<Episode>()
            for (doc in docsToScan) {
                val foundEps = doc.select("a[href*='/bolum/']").mapNotNull { a ->
                    val epHref = a.attr("href") ?: return@mapNotNull null
                    val seasonMatch = Regex("(\\d+)-sezon").find(epHref)
                    val episodeMatch = Regex("(\\d+)-bolum").find(epHref)
                    
                    if (seasonMatch == null && episodeMatch == null) return@mapNotNull null
                    
                    val epUrl = fixUrl(epHref)
                    val seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                    newEpisode(epUrl) {
                        this.name = a.text().trim().ifEmpty { "${seasonNumber}. Sezon ${episodeNumber}. Bölüm" }
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }
                episodes.addAll(foundEps)
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addActors(actors)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addActors(actors)
            }
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
        val targetPageHtml = try {
            app.get(data, headers = mapOf("User-Agent" to MOBILE_USER_AGENT), cacheTime = 0).text
        } catch (e: Exception) { "" }

        val targetDoc = Jsoup.parse(targetPageHtml)
        val titleText = targetDoc.selectFirst("h1")?.text()?.substringBefore("izle")?.trim()

        try {
            val ajaxUrl = "$resolvedUrl/wp-admin/admin-ajax.php"
            val videoNonce = Regex("""video\s*:\s*\"([a-f0-9]+)\"""").find(targetPageHtml)?.groupValues?.get(1)
            
            val players = targetDoc.select("a.options2").mapNotNull { a ->
                val postId = a.attr("data-post-id") ?: return@mapNotNull null
                val playerName = a.attr("data-player-name") ?: return@mapNotNull null
                val partKey = a.attr("data-part-key") ?: return@mapNotNull null
                Triple(postId, playerName, partKey)
            }
            
            if (videoNonce != null && players.isNotEmpty()) {
                players.forEach { (postId, playerName, partKey) ->
                    try {
                        val videoUrlResponse = app.post(
                            ajaxUrl,
                            headers = mapOf(
                                "User-Agent" to MOBILE_USER_AGENT,
                                "Referer" to data,
                                "Content-Type" to "application/x-www-form-urlencoded"
                            ),
                            data = mapOf(
                                "action" to "get_video_url",
                                "nonce" to videoNonce,
                                "post_id" to postId,
                                "player_name" to playerName,
                                "part_key" to partKey
                            )
                        ).text.trim().removePrefix("\uFEFF")
                        
                        val videoUrlObj = JSONObject(videoUrlResponse)
                        if (videoUrlObj.optBoolean("success")) {
                            val videoUrl = videoUrlObj.optJSONObject("data")?.optString("url") ?: ""
                            if (videoUrl.isNotEmpty()) {
                                val displayKey = if (partKey.lowercase().contains("dublaj")) "Dublaj" else if (partKey.lowercase().contains("altyazi")) "Altyazı" else partKey
                                
                                if (videoUrl.contains("fastplay")) {
                                    val domain = Regex("https?://([^/]+)").find(videoUrl)?.groupValues?.get(1) ?: "fastplay.mom"
                                    val id = videoUrl.substringAfter("/video/").substringBefore("?").substringBefore("/")
                                    if (id.isNotEmpty()) {
                                        val streamUrl = "https://$domain/manifests/$id/master.txt"
                                        callback(
                                            newExtractorLink(
                                                source = "SetFilmizle",
                                                name = "SetFilmizle - FastPlay ($displayKey)",
                                                url = streamUrl,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = "https://$domain/video/$id"
                                                this.headers = mapOf(
                                                    "Referer" to "https://$domain/video/$id",
                                                    "Origin" to "https://$domain",
                                                    "User-Agent" to MOBILE_USER_AGENT
                                                )
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                        found = true
                                    }
                                } else if (videoUrl.contains("setplay")) {
                                    val pageRes = app.get(videoUrl, headers = mapOf("User-Agent" to MOBILE_USER_AGENT, "Referer" to data), cacheTime = 0)
                                    val pageHtml = pageRes.text
                                    val streamUrlPart = Regex("""\"videoUrl\"\s*:\s*\"([^\"]+)\"""").find(pageHtml)?.groupValues?.get(1)?.replace("\\/", "/")
                                    val videoServer = Regex("""\"videoServer\"\s*:\s*\"([^\"]+)\"""").find(pageHtml)?.groupValues?.get(1)
                                    
                                    val sessionCookie = pageRes.headers.filter { it.first.equals("set-cookie", ignoreCase = true) }
                                        .map { it.second.substringBefore(";") }
                                        .firstOrNull { it.startsWith("PHPSESSID") } ?: ""

                                    if (streamUrlPart != null && videoServer != null) {
                                        val streamUrl = "https://setplay.shop$streamUrlPart?s=$videoServer"
                                        callback(
                                            newExtractorLink(
                                                source = "SetFilmizle",
                                                name = "SetFilmizle - SetPlay ($displayKey)",
                                                url = streamUrl,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = videoUrl
                                                this.headers = mapOf(
                                                    "Referer" to videoUrl,
                                                    "Origin" to "https://setplay.shop",
                                                    "User-Agent" to MOBILE_USER_AGENT,
                                                    "Cookie" to sessionCookie,
                                                    "Sec-Fetch-Dest" to "empty",
                                                    "Sec-Fetch-Mode" to "cors",
                                                    "Sec-Fetch-Site" to "same-origin"
                                                )
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                        found = true
                                    }
                                } else {
                                    loadExtractor(videoUrl, "$resolvedUrl/", subtitleCallback, callback)
                                    found = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // FULL SELCUKFLIX EXTRACTOR INTEGRATION
        try {
            val seasonMatch = Regex("(\\d+)-sezon").find(data)
            val episodeMatch = Regex("(\\d+)-bolum").find(data)
            val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
            val epNum = episodeMatch?.groupValues?.get(1)?.toIntOrNull()
            val cleanTitle = titleText?.replace(Regex("""\s*\(\d{4}\)"""), "")
                ?.replace(Regex("""\d+\s*\.?\s*(?:sezon|season|bolum|episode).*""", RegexOption.IGNORE_CASE), "")
                ?.trim()

            if (!cleanTitle.isNullOrEmpty()) {
                val selcukflixFound = loadSelcukflixDirect(cleanTitle, data.contains("/dizi/"), seasonNum, epNum, subtitleCallback, callback)
                if (selcukflixFound) found = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return found
    }

    private suspend fun loadSelcukflixDirect(
        title: String,
        isDizi: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            resolveSelcukflix()
            val baseUrl = selcukflixUrl

            val slugFromTitle = title.lowercase().trim()
                .replace(" ", "-")
                .replace(Regex("[^a-z0-9-]"), "")

            val possibleUrls = mutableListOf<String>()
            if (isDizi && season != null && episode != null) {
                possibleUrls.add("$baseUrl/dizi/$slugFromTitle/sezon-$season/bolum-$episode")
            } else {
                possibleUrls.add("$baseUrl/film/$slugFromTitle")
            }

            for (targetUrl in possibleUrls) {
                try {
                    val pageHtml = app.get(targetUrl, headers = mapOf("User-Agent" to MOBILE_USER_AGENT), cacheTime = 0).text
                    val secureData = Regex("""\"secureData\"\s*:\s*\"([^\"]+)\"""").find(pageHtml)?.groupValues?.get(1) ?: continue
                    
                    val decodedPageText = decryptSelcukflix(secureData)
                    if (decodedPageText.isEmpty()) continue
                    val decodedPageJson = JSONObject(decodedPageText)
                    
                    val sources = mutableListOf<Triple<String, String, String>>() // <iframeUrl, languageName, qualityName>
                    val relatedResults = decodedPageJson.optJSONObject("RelatedResults") ?: continue

                    if (isDizi) {
                        val getEpisodeSources = relatedResults.optJSONObject("getEpisodeSources") ?: continue
                        val epSourcesList = getEpisodeSources.optJSONArray("result") ?: continue
                        for (i in 0 until epSourcesList.length()) {
                            val s = epSourcesList.optJSONObject(i) ?: continue
                            val sourceContent = s.optString("source_content", "")
                            val langName = s.optString("language_name", "Türkçe")
                            val qualName = s.optString("quality_name", "1080P")
                            val src = Regex("""src=["']([^"']+)["']""").find(sourceContent)?.groupValues?.get(1) ?: ""
                            if (src.isNotEmpty()) {
                                val fullSrc = if (src.startsWith("//")) "https:$src" else src
                                sources.add(Triple(fullSrc, langName, qualName))
                            }
                        }
                    } else {
                        val keys = relatedResults.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key.startsWith("getMoviePartSourcesById_")) {
                                val partObj = relatedResults.optJSONObject(key) ?: continue
                                val partResult = partObj.optJSONArray("result") ?: continue
                                for (i in 0 until partResult.length()) {
                                    val s = partResult.optJSONObject(i) ?: continue
                                    val sourceContent = s.optString("source_content", "")
                                    val langName = s.optString("language_name", "Türkçe")
                                    val qualName = s.optString("quality_name", "1080P")
                                    val src = Regex("""src=["']([^"']+)["']""").find(sourceContent)?.groupValues?.get(1) ?: ""
                                    if (src.isNotEmpty()) {
                                        val fullSrc = if (src.startsWith("//")) "https:$src" else src
                                        sources.add(Triple(fullSrc, langName, qualName))
                                    }
                                }
                            }
                        }
                    }

                    for ((iframeUrl, langName, qualName) in sources.distinctBy { it.first }) {
                        try {
                            val iframeHtml = app.get(iframeUrl, headers = mapOf("User-Agent" to MOBILE_USER_AGENT, "Referer" to "$baseUrl/"), cacheTime = 0).text
                            val playlistKey = Regex("""openPlayer\(\s*'([^']+)'""").find(iframeHtml)?.groupValues?.get(1) ?: ""
                            if (playlistKey.isNotEmpty()) {
                                val iframeDomain = Regex("https?://([^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: "four.pichive.online"
                                val apiUrl = "https://$iframeDomain/source2.php?v=${Uri.encode(playlistKey)}"
                                val apiResponse = app.get(
                                    apiUrl,
                                    headers = mapOf(
                                        "User-Agent" to MOBILE_USER_AGENT,
                                        "Referer" to iframeUrl,
                                        "X-Requested-With" to "XMLHttpRequest"
                                    ),
                                    cacheTime = 0
                                ).text
                                
                                val apiJson = JSONObject(apiResponse)
                                if (apiJson.optBoolean("state")) {
                                    val playlist = apiJson.optJSONArray("playlist")
                                    if (playlist != null) {
                                        for (i in 0 until playlist.length()) {
                                            val item = playlist.optJSONObject(i) ?: continue
                                            val sourcesArray = item.optJSONArray("sources") ?: continue
                                            for (j in 0 until sourcesArray.length()) {
                                                val sourceObj = sourcesArray.optJSONObject(j) ?: continue
                                                val fileUrl = sourceObj.optString("file", "")
                                                if (fileUrl.isNotEmpty()) {
                                                    callback(
                                                        newExtractorLink(
                                                            source = "Selcukflix",
                                                            name = "Selcukflix - $langName ($qualName)",
                                                            url = fileUrl,
                                                            type = ExtractorLinkType.M3U8
                                                        ) {
                                                            this.referer = "https://$iframeDomain/"
                                                            this.headers = mapOf(
                                                                "Referer" to "https://$iframeDomain/",
                                                                "Origin" to "https://$iframeDomain",
                                                                "User-Agent" to MOBILE_USER_AGENT,
                                                                "Accept" to "*/*"
                                                            )
                                                            this.quality = Qualities.Unknown.value
                                                        }
                                                    )
                                                    found = true
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                loadExtractor(iframeUrl, "$baseUrl/", subtitleCallback, callback)
                                found = true
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }
}
