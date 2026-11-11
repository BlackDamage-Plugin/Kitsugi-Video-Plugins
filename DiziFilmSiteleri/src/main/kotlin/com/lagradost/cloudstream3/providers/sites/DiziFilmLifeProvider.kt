package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

class DiziFilmLifeProvider : MainAPI() {
    override var mainUrl = "https://dizifilmizle.to"
    override var name = "DiziFilm"
    override var lang = "tr"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Dest" to "empty"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=" to "Son Eklenen Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/turkce-dublaj-filmler" to "Türkçe Dublaj Filmler",
        "$mainUrl/turkce-altyazili-filmler" to "Türkçe Altyazılı Filmler",
        "$mainUrl/ulke/turkiye-hd" to "Yerli Filmler",
        "$mainUrl/efsane-diziler" to "Efsane Diziler"
    )

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        if (url.contains("/_next/image")) return url
        val cleanUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        return try {
            "$mainUrl/_next/image?url=${URLEncoder.encode(cleanUrl, "UTF-8")}&w=640&q=75"
        } catch (e: Exception) {
            cleanUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        var hasNext = false
        try {
            if (request.data.contains("/api/movies")) {
                val pageUrl = "${request.data}$page"
                val res = app.get(pageUrl, headers = defaultHeaders, cacheTime = 60)
                if (res.isSuccessful) {
                    val json = JSONObject(res.text)
                    val movies = json.optJSONArray("movies") ?: JSONArray()
                    for (i in 0 until movies.length()) {
                        val m = movies.optJSONObject(i) ?: continue
                        val title = m.optString("title", m.optString("original_title", "")).trim()
                        val slug = m.optString("slug", "")
                        if (title.isEmpty() || slug.isEmpty()) continue

                        val href = "$mainUrl/film/$slug"
                        val poster = m.optString("poster_url", "")
                        val scoreVal = m.optDouble("imdb_rating", 0.0)

                        items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = fixPosterUrl(poster)
                            if (scoreVal > 0.0) this.score = Score.from10(scoreVal)
                        })
                    }
                    hasNext = items.isNotEmpty()
                }
            } else {
                if (page == 1) {
                    val res = app.get(request.data, headers = defaultHeaders, cacheTime = 60)
                    if (res.isSuccessful) {
                        val doc = Jsoup.parse(res.text)
                        val elements = doc.select("a[href*='/dizi/'], a[href*='/film/']")
                        for (el in elements) {
                            val href = el.attr("href")
                            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                            val isDizi = href.contains("/dizi/")
                            
                            val img = el.selectFirst("img")
                            val alt = img?.attr("alt") ?: ""
                            val title = alt.replace(Regex("""\s*(?:izle|filmi)$""", RegexOption.IGNORE_CASE), "").trim()
                            if (title.isEmpty()) continue
                            
                            val src = img?.attr("src") ?: ""
                            var poster: String? = null
                            if (src.contains("url=")) {
                                try {
                                    val encUrl = src.substringAfter("url=").substringBefore("&")
                                    poster = java.net.URLDecoder.decode(encUrl, "UTF-8")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            if (poster.isNullOrEmpty()) {
                                poster = src
                            }
                            
                            val scoreText = el.selectFirst(".imdb, .rating, [class*='imdb'], [class*='rating']")?.text()
                            val scoreVal = scoreText?.toDoubleOrNull()
                            
                            if (isDizi) {
                                items.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                                    this.posterUrl = fixPosterUrl(poster)
                                    if (scoreVal != null && scoreVal > 0.0) this.score = Score.from10(scoreVal)
                                })
                            } else {
                                items.add(newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                                    this.posterUrl = fixPosterUrl(poster)
                                    if (scoreVal != null && scoreVal > 0.0) this.score = Score.from10(scoreVal)
                                })
                            }
                        }
                    }
                }
                hasNext = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val results = mutableListOf<SearchResponse>()
        try {
            val res = app.get(searchUrl, headers = defaultHeaders)
            if (res.isSuccessful) {
                val json = JSONObject(res.text)
                val array = json.optJSONArray("results") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val m = array.optJSONObject(i) ?: continue
                    val title = m.optString("title", m.optString("original_title", "")).trim()
                    val slug = m.optString("slug", "")
                    if (title.isEmpty() || slug.isEmpty()) continue

                    val contentType = m.optString("content_type", "movie")
                    val isSeries = contentType == "series"
                    val href = if (isSeries) "$mainUrl/dizi/$slug" else "$mainUrl/film/$slug"
                    val type = if (isSeries) TvType.TvSeries else TvType.Movie
                    
                    val poster = m.optString("poster_url", "")
                    val scoreVal = m.optDouble("imdb_rating", 0.0)

                    if (isSeries) {
                        results.add(newTvSeriesSearchResponse(title, href, type) {
                            this.posterUrl = fixPosterUrl(poster)
                            if (scoreVal > 0.0) this.score = Score.from10(scoreVal)
                        })
                    } else {
                        results.add(newMovieSearchResponse(title, href, type) {
                            this.posterUrl = fixPosterUrl(poster)
                            if (scoreVal > 0.0) this.score = Score.from10(scoreVal)
                        })
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val res = app.get(url, headers = defaultHeaders)
            val htmlRes = res.text
            val unescaped = htmlRes.replace("\\\"", "\"").replace("\\/", "/")

            val doc = Jsoup.parse(htmlRes)
            val title = doc.selectFirst("h1")?.text()?.replace(Regex("""\s*(?:izle|filmi)$""", RegexOption.IGNORE_CASE), "")?.trim()
                ?: Regex("""movie":\{[^}]*"title":"([^"]+)"""").find(unescaped)?.groupValues?.get(1)?.trim()
                ?: return null

            val poster = Regex(""""poster_url"\s*:\s*"([^"]+)"""").find(unescaped)?.groupValues?.get(1)
                ?: fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))

            val plot = Regex(""""description"\s*:\s*"([^"]+)"""").find(unescaped)?.groupValues?.get(1)
                ?: doc.selectFirst("meta[property='og:description']")?.attr("content")

            val year = Regex(""""start_year"\s*:\s*(\d{4})""").find(unescaped)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex(""""year"\s*:\s*(\d{4})""").find(unescaped)?.groupValues?.get(1)?.toIntOrNull()
                ?: doc.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()

            val isSeries = url.contains("/dizi/")
            if (isSeries) {
                val decoded = extractNextFPayload(htmlRes)
                val seasonsJson = extractJsonArray(decoded, "\"seasonsWithEpisodes\"")
                val episodesJson = extractJsonArray(decoded, "\"episodes\"")
                if (seasonsJson != null && episodesJson != null) {
                    val seasonsMap = mutableMapOf<Int, Int>()
                    val seasonsArray = JSONArray(seasonsJson)
                    for (i in 0 until seasonsArray.length()) {
                        val sObj = seasonsArray.optJSONObject(i) ?: continue
                        seasonsMap[sObj.optInt("id")] = sObj.optInt("season_number")
                    }

                    val episodesList = mutableListOf<Episode>()
                    val episodesArray = JSONArray(episodesJson)
                    for (i in 0 until episodesArray.length()) {
                        val epObj = episodesArray.optJSONObject(i) ?: continue
                        val epNum = epObj.optInt("episode_number")
                        val seasonId = epObj.optInt("season_id")
                        val seasonNum = seasonsMap[seasonId] ?: 1

                        val epTitle = epObj.optString("title", "Bölüm $epNum")
                        val epPoster = epObj.optString("thumbnail_url")
                        val epDesc = epObj.optString("overview")

                        val embed1 = epObj.optString("embed_player_url_1")
                        val embed2 = epObj.optString("embed_player_url_2")

                        val dataObj = JSONObject()
                        dataObj.put("embed_player_url_1", embed1)
                        dataObj.put("embed_player_url_2", embed2)

                        episodesList.add(newEpisode(dataObj.toString()) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = fixPosterUrl(epPoster)
                            this.description = epDesc.takeIf { it.isNotEmpty() }
                        })
                    }

                    if (episodesList.isNotEmpty()) {
                        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList.distinctBy { it.data }) {
                            this.posterUrl = fixPosterUrl(poster)
                            this.year = year
                            this.plot = plot
                        }
                    }
                }

                // Fallback: Parse episode anchors directly from HTML if JSON payload empty
                val fallbackEps = mutableListOf<Episode>()
                val epAnchors = doc.select("a[href*='sezon'], a[href*='bolum']")
                for (a in epAnchors) {
                    val href = a.attr("href") ?: continue
                    if (!href.contains("/dizi/")) continue
                    val sMatch = Regex("""sezon-(\d+)""").find(href)
                    val eMatch = Regex("""bolum-(\d+)""").find(href)
                    val sNum = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val eNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val fullEpUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                    val epName = a.text().trim().ifEmpty { "$sNum. Sezon $eNum. Bölüm" }

                    fallbackEps.add(newEpisode(fullEpUrl) {
                        this.name = epName
                        this.season = sNum
                        this.episode = eNum
                    })
                }

                if (fallbackEps.isNotEmpty()) {
                    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, fallbackEps.distinctBy { it.data }) {
                        this.posterUrl = fixPosterUrl(poster)
                        this.year = year
                        this.plot = plot
                    }
                }
            }

            val partsMatch = Regex(""""parts"\s*:\s*(\[\s*\{.*?\}\s*\])""").find(unescaped)
            val partsJson = partsMatch?.groupValues?.get(1) ?: ""

            return newMovieLoadResponse(title, url, TvType.Movie, partsJson) {
                this.posterUrl = fixPosterUrl(poster)
                this.year = year
                this.plot = plot
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        try {
            if (data.isBlank()) return@coroutineScope false
            var found = false
            if (data.trim().startsWith("{")) {
                val obj = JSONObject(data)
                val embed1 = obj.optString("embed_player_url_1", "")
                val embed2 = obj.optString("embed_player_url_2", "")
                val embeds = listOf(embed1, embed2).filter { it.isNotEmpty() }
                for (embedUrl in embeds) {
                    if (embedUrl.contains("vidmixi.com")) {
                        extractVidmixi(embedUrl, callback, subtitleCallback)
                        found = true
                    } else if (embedUrl.contains("m3u8") || embedUrl.contains("mp4")) {
                        callback(
                            newExtractorLink("DiziFilm", "DiziFilm Direct", embedUrl, if (embedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                                this.referer = "$mainUrl/"
                                this.headers = defaultHeaders
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    } else {
                        loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                        found = true
                    }
                }
            } else if (data.trim().startsWith("[")) {
                val array = JSONArray(data)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val embedUrl = item.optString("url", "")
                    if (embedUrl.isNotEmpty()) {
                        if (embedUrl.contains("vidmixi.com")) {
                            extractVidmixi(embedUrl, callback, subtitleCallback)
                            found = true
                        } else if (embedUrl.contains("m3u8") || embedUrl.contains("mp4")) {
                            callback(
                                newExtractorLink("DiziFilm", "DiziFilm Direct", embedUrl, if (embedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                                    this.referer = "$mainUrl/"
                                    this.headers = defaultHeaders
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        } else {
                            loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                            found = true
                        }
                    }
                }
            }
            return@coroutineScope found
        } catch (e: Exception) {
            e.printStackTrace()
            return@coroutineScope false
        }
    }

    private suspend fun extractVidmixi(embedUrl: String, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val res = app.get(embedUrl, headers = defaultHeaders)
            val html = res.text
            val match = Regex("""bePlayer\s*\(\s*'([^']+)'\s*,\s*'([^']+)'\s*\)""").find(html) ?: return
            val key = match.groupValues[1]
            val jsonStr = match.groupValues[2].replace("\\\"", "\"").replace("\\/", "/")

            val jsonObj = JSONObject(jsonStr)
            val salt = hexToBytes(jsonObj.getString("s"))
            val ciphertext = base64ToBytes(jsonObj.getString("ct"))

            val (aesKey, iv) = evpBytesToKey(key.toByteArray(Charsets.UTF_8), salt, 32, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            val decrypted = String(cipher.doFinal(ciphertext), Charsets.UTF_8)

            val decObj = JSONObject(decrypted)
            val m3u8Url = decObj.optString("video_location", "").replace("\\/", "/")
            if (m3u8Url.isNotEmpty()) {
                callback(
                    newExtractorLink("Vidmixi", "Vidmixi HLS", m3u8Url, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = defaultHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            val subs = decObj.optJSONArray("strSubtitles")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val sub = subs.optJSONObject(i) ?: continue
                    val file = sub.optString("file", "")
                    if (file.isNotEmpty()) {
                        val subUrl = if (file.startsWith("http")) file else "https://vidmixi.com$file"
                        val lang = sub.optString("label", "Sub")
                        subtitleCallback(SubtitleFile(lang, subUrl))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun base64ToBytes(base64: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(base64)
        } catch (e: Exception) {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        }
    }

    private fun evpBytesToKey(pass: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        var keyAndIv = ByteArray(0)
        var currentHash = ByteArray(0)
        val md = MessageDigest.getInstance("MD5")
        while (keyAndIv.size < keyLen + ivLen) {
            md.reset()
            md.update(currentHash)
            md.update(pass)
            md.update(salt)
            currentHash = md.digest()
            keyAndIv += currentHash
        }
        val key = keyAndIv.copyOfRange(0, keyLen)
        val iv = keyAndIv.copyOfRange(keyLen, keyLen + ivLen)
        return Pair(key, iv)
    }

    private fun extractNextFPayload(html: String): String {
        val matches = Regex("""self\.__next_f\.push\(\s*\[\s*\d+\s*,\s*(["'])([\s\S]*?)\1\s*\]\s*\)""").findAll(html)
        val sb = StringBuilder()
        for (m in matches) {
            var rawStr = m.groupValues[2]
            rawStr = rawStr.replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
            sb.append(rawStr)
        }
        return sb.toString()
    }

    private fun extractJsonArray(text: String, key: String): String? {
        val startIndex = text.indexOf(key)
        if (startIndex == -1) return null
        val arrayStart = text.indexOf("[", startIndex)
        if (arrayStart == -1) return null
        var bracketCount = 0
        for (i in arrayStart until text.length) {
            val char = text[i]
            if (char == '[') {
                bracketCount++
            } else if (char == ']') {
                bracketCount--
                if (bracketCount == 0) {
                    return text.substring(arrayStart, i + 1)
                }
            }
        }
        return null
    }
}
