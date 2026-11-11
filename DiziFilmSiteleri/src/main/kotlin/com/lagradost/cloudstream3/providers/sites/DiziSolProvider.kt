package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import com.lagradost.cloudstream3.APIHolder

class DiziSolProvider : MainAPI() {
    override var mainUrl = "https://dizisol.com"
    override var name = "DiziSol"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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

    private val dizisolHeaders = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-Dest" to "empty"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/library/browse?type=movie&page=" to "Filmler",
        "$mainUrl/api/library/browse?type=tv&page=" to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "${request.data}$page"
        val items = mutableListOf<SearchResponse>()
        try {
            val res = app.get(pageUrl, headers = defaultHeaders, cacheTime = 60)
            if (res.isSuccessful) {
                val json = JSONObject(res.text)
                val results = json.optJSONArray("results") ?: JSONArray()
                for (i in 0 until results.length()) {
                    val m = results.optJSONObject(i) ?: continue
                    val id = m.optLong("id", 0)
                    if (id == 0L) continue

                    val mediaType = m.optString("media_type", if (request.name == "Diziler") "tv" else "movie")
                    val title = m.optString("title", m.optString("name", "")).trim()
                    if (title.isEmpty()) continue

                    val type = if (mediaType == "tv") TvType.TvSeries else TvType.Movie
                    val prefix = if (mediaType == "tv") "dizi" else "film"
                    val href = "$mainUrl/$prefix/$id"
                    val poster = m.optString("poster_path", "")
                    val scoreVal = m.optDouble("vote_average", 0.0)

                    if (type == TvType.TvSeries) {
                        items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = poster.takeIf { it.isNotEmpty() }
                            if (scoreVal > 0.0) this.score = Score.from10(scoreVal)
                        })
                    } else {
                        items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = poster.takeIf { it.isNotEmpty() }
                            if (scoreVal > 0.0) this.score = Score.from10(scoreVal)
                        })
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/api/tmdb/search/multi?query=${URLEncoder.encode(query, "UTF-8")}"
        val results = mutableListOf<SearchResponse>()
        try {
            val res = app.get(searchUrl, headers = defaultHeaders)
            if (res.isSuccessful) {
                val json = JSONObject(res.text)
                val array = json.optJSONArray("results") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val m = array.optJSONObject(i) ?: continue
                    val id = m.optLong("id", 0)
                    if (id == 0L) continue

                    val mediaType = m.optString("media_type", "movie")
                    if (mediaType != "movie" && mediaType != "tv") continue
                    val title = m.optString("title", m.optString("name", "")).trim()
                    if (title.isEmpty()) continue

                    val type = if (mediaType == "tv") TvType.TvSeries else TvType.Movie
                    val prefix = if (mediaType == "tv") "dizi" else "film"
                    val href = "$mainUrl/$prefix/$id"
                    val poster = m.optString("poster_path", "")
                    val scoreVal = m.optDouble("vote_average", 0.0)

                    if (type == TvType.TvSeries) {
                        results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = poster.takeIf { it.isNotEmpty() }
                            if (scoreVal > 0.0) this.score = Score.from10(scoreVal)
                        })
                    } else {
                        results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = poster.takeIf { it.isNotEmpty() }
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
            val isTv = url.contains("/dizi/")
            val id = url.substringAfterLast("/")
            val apiDetailUrl = "$mainUrl/api/tmdb/${if (isTv) "tv" else "movie"}/$id"

            val res = app.get(apiDetailUrl, headers = defaultHeaders)
            if (!res.isSuccessful) return null

            val json = JSONObject(res.text)
            val title = json.optString("title", json.optString("name", "")).trim()
            if (title.isEmpty()) return null

            val poster = json.optString("poster_path", "")
                .let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w500$it" }
            val plot = json.optString("overview", "")
            val releaseDate = json.optString("release_date", json.optString("first_air_date", ""))
            val year = releaseDate.take(4).toIntOrNull()

            val genresArr = json.optJSONArray("genres") ?: JSONArray()
            val genres = mutableListOf<String>()
            for (i in 0 until genresArr.length()) {
                val g = genresArr.optJSONObject(i)
                val gName = g?.optString("name", "") ?: ""
                if (gName.isNotEmpty()) genres.add(gName)
            }

            return if (isTv) {
                val seasonsArr = json.optJSONArray("seasons") ?: JSONArray()
                val episodes = mutableListOf<Episode>()
                for (s in 0 until seasonsArr.length()) {
                    val season = seasonsArr.optJSONObject(s) ?: continue
                    val sNum = season.optInt("season_number", 0)
                    val epCount = season.optInt("episode_count", 0)
                    if (sNum <= 0 || epCount <= 0) continue

                    for (e in 1..epCount) {
                        val epData = JSONObject()
                        epData.put("id", id)
                        epData.put("season", sNum)
                        epData.put("episode", e)
                        epData.put("isTv", true)

                        episodes.add(newEpisode(epData.toString()) {
                            this.name = "$sNum. Sezon $e. Bölüm"
                            this.season = sNum
                            this.episode = e
                        })
                    }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = genres
                }
            } else {
                val dataObj = JSONObject()
                dataObj.put("id", id)
                dataObj.put("isTv", false)
                newMovieLoadResponse(title, url, TvType.Movie, dataObj.toString()) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = genres
                }
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
    ): Boolean {
        try {
            if (data.isBlank()) return false
            val dataObj = JSONObject(data)
            val id = dataObj.optString("id", "")
            val isTv = dataObj.optBoolean("isTv", false)
            if (id.isEmpty()) return false

            val apiUrl = if (isTv) {
                val season = dataObj.optInt("season", 1)
                val episode = dataObj.optInt("episode", 1)
                "$mainUrl/api/movies/by-tmdb/$id?season=$season&episode=$episode"
            } else {
                "$mainUrl/api/movies/by-tmdb/$id"
            }

            var res = app.get(apiUrl, headers = defaultHeaders)
            if (!res.isSuccessful && isTv) {
                val season = dataObj.optInt("season", 1)
                val episode = dataObj.optInt("episode", 1)
                val altUrl1 = "$mainUrl/api/library/watch/$id?season=$season&episode=$episode"
                res = app.get(altUrl1, headers = defaultHeaders)
                if (!res.isSuccessful) {
                    val altUrl2 = "$mainUrl/api/tmdb/tv/$id/season/$season/episode/$episode"
                    res = app.get(altUrl2, headers = defaultHeaders)
                }
            }
            if (!res.isSuccessful) return false

            val json = JSONObject(res.text)
            var found = false

            // 1. Try sources[] array first (most reliable, has multiple providers with subtitles)
            val sourcesArr = json.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val src = sourcesArr.optJSONObject(i) ?: continue
                    if (!src.optBoolean("isActive", true)) continue

                    val m3u8 = src.optString("m3u8Url", "")
                    val provider = src.optString("provider", "DiziSol")
                    val subTr = src.optString("subtitleTr", "")
                    val subEn = src.optString("subtitleEn", "")

                    if (m3u8.isNotEmpty()) {
                        if (m3u8.startsWith("http")) {
                            // Register subtitles
                            if (subTr.isNotEmpty()) subtitleCallback(SubtitleFile("Türkçe", subTr))
                            if (subEn.isNotEmpty()) subtitleCallback(SubtitleFile("English", subEn))

                            callback(
                                newExtractorLink("DiziSol [$provider]", "DiziSol [$provider]", m3u8, ExtractorLinkType.M3U8) {
                                    this.referer = "$mainUrl/"
                                    this.headers = dizisolHeaders
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        } else if (m3u8.startsWith("diziyou::") || provider == "diziyou") {
                            val sourceUrl = src.optString("sourceUrl", "")
                            if (sourceUrl.startsWith("http")) {
                                loadExtractor(sourceUrl, "https://www.diziyou.one/", subtitleCallback, callback)
                                found = true
                            }
                        }
                    }
                }
            }

            // 2. Try top-level m3u8Url
            val topM3u8 = json.optString("m3u8Url", "")
            if (topM3u8.isNotEmpty()) {
                if (topM3u8.startsWith("http") && !topM3u8.startsWith("diziyou::")) {
                    // Also register top-level subtitles
                    val subTr = json.optString("subtitleTr", "")
                    val subEn = json.optString("subtitleEn", "")
                    if (subTr.isNotEmpty()) subtitleCallback(SubtitleFile("Türkçe", subTr))
                    if (subEn.isNotEmpty()) subtitleCallback(SubtitleFile("English", subEn))

                    callback(
                        newExtractorLink("DiziSol", "DiziSol HLS", topM3u8, ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"
                            this.headers = dizisolHeaders
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                } else if (topM3u8.startsWith("setfilmizle::")) {
                    val base64Url = topM3u8.substringAfter("setfilmizle::").substringBefore("::")
                    val decodedUrl = String(android.util.Base64.decode(base64Url, android.util.Base64.DEFAULT))
                    if (decodedUrl.startsWith("http")) {
                        val setfilmizleApi = APIHolder.getApiFromNameNull("SetFilmizle")
                        if (setfilmizleApi != null) {
                            setfilmizleApi.loadLinks(decodedUrl, isCasting, subtitleCallback, callback)
                            found = true
                        }
                    }
                }
            }

            // 3. diziyou:: format — use sourceUrl (diziyou.one player page)
            if (topM3u8.startsWith("diziyou::")) {
                val sourceUrl = json.optString("sourceUrl", "")
                if (sourceUrl.startsWith("http")) {
                    // sourceUrl is like https://www.diziyou.one/player/10551.html
                    loadExtractor(sourceUrl, "https://www.diziyou.one/", subtitleCallback, callback)
                    found = true
                }
            }

            return found
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
