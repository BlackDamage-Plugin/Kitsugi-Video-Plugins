package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

private inline fun <reified T> parseJson(json: String): T {
    return mapper.readValue<T>(json)
}

class Sinewix : MainAPI() {
    override var mainUrl = "https://ydfvfdizipanel.ru"
    override var name = "Sinewix"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Cartoon
    )

    private val apiToken = "TOKEN_PLACEHOLDER"
    private var cachedToken: String? = null
    private suspend fun getToken(): String {
        cachedToken?.let { return it }
        try {
            val html = app.get(mainUrl).text
            val match = Regex("""apiToken\s*=\s*['"]([^'"]+)['"]""").find(html)
            if (match != null) { cachedToken = match.groupValues[1]; return cachedToken!! }
        } catch(e: Exception) {}
        cachedToken = "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA" // Fallback Token
        return cachedToken!!
    }

    private val sineHeaders = mapOf(
        "hash256" to "711bff4afeb47f07ab08a0b07e85d3835e739295e8a6361db77eebd93d96306b",
        "signature" to "3082058830820370a00302010202145bbfbba9791db758ad12295636e094ab4b07dc24300d06092a864886f70d01010b05003074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643020170d3231313231353232303433335a180f32303531313231353232303433335a3074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820222300d06092a864886f70d01010105000382020f003082020a0282020100a5106a24bb3f9c0aaf3a2b228f794b5eaf1757ba758b19736a39d1bdc73fc983a7237b8d5ca5156cfa999c1dab3418bbc2be0920e0ee001c8aa4812d1dae75d080f09e91e0abda83ff9a76e8384a4429f4849248069a59505b12ac2c14ba2e4d1a13afcdaf54e508697ff928a9f738e6f4a6fc27409c55329eb149b5ff89c5a2d7c06bf9e62086f955cad17d7be2623ee9d5ec56068eadc23cb0965a13ff97d49fe10ef41afc6eeca36b4ace9582097faff89f590bc831cdb3a69eec5d15b67c3f2cad49e37ed053733e3d2d400c47755b932bdbe15d749fd6ad1dce30ba5e66094dfb6ee6f64cafb807e11b19a990c5d078c6d6701cda0bdeb21e99404ff166074f4c89b04c418f4e7940db5c78647c475bcfb85d4c4e836ee7d7c1d53e9e736b5d96d4b4d8b98209064b729ac6a682d55a6a930e518d849898bb28329ca0aaa133b5e5270a9d5940cac6af4802a57fd971efda91abb602882dd6aa6ce2b236b57b52ee2481498f0cacbcc2c36c238bc84becad7eaaf1125b9a1ca9ded6c79f3f283a52050377809b2a9995d66e1636b0ed426fdd8685c47cb18e82077f4aefcc07887e1dc58b4d64be1632f0e7b4625da6f40c65a8512a6454a4b96963e7f876136e6c0069a519a79ad632078ed965aa12482458060c030ed50db706d854f88cb004630b49285d8af8b471ff8f6070687826412287b50049bcb7d1b6b62ef90203010001a310300e300c0603551d13040530030101ff300d06092a864886f70d01010b0500038202010051c0b7bd793181dc29ca777d3773f928a366c8469ecf2fa3cfb076e8831970d19bb2b96e44e8ccc647cf0696bb824ac61c23d958525d283cab26037b04d58aa79bf92192db843adf5c26a980f081d2f0e14f759fc5ff4c5bb3dce0860299bfe7b349a8155a2efaf731ba25ce796a80c1442c7bf80f8c1a7912ff0b6f6592264315337251a846460194fa594f81f38f9e5233a63201e931ad9cab5bf119f24025613f307194eaa6eb39a83f3c05a49ba34455b1aff7c6839bbb657d9392ffdf397432af6e56ba9534a8b07d7060fe09691c6cf07cb5324f67b3cc0871a8c621d81fe71d71085c55206a4f57e25f774fd4b979b299e8bb076b50fca42fa57da2d519fd35a4a7c0137babaed4345f8031b63b6a71f5e8268f709d658ccd7c2a58849379d25bfa598c3f4a2c3d9b7d89285fefeb7f0ec65137d38b08ce432a15688b624a179e6a4a505ebc3bcdfbc4d4330508ee2d8d0f016924dcec21a6838ef7d834c6f43bde4a5201ed0b3bb4e9bd377b470e36bcf5bc3d56169dbd8e39567aa7dce4d1a8a8a54a5e1aa6fb1a8aab0062669a966f96e15ccce6fe12ea5e6a8b8c8823bdc94988ca39759fd1cc8fd8ae5c3d74db50b174cf7d77655016c075c91d439ed01cc0a9f695c99fad3b5495fb6cb1e01a5fa020cc6022a85c07ec55f9eba89719f86e49d34ab5bd208c5f70cced2b7b7963c014f8404432979b506de29e",
        "User-Agent" to "EasyPlex (Android 14; SM-A546B; Samsung Galaxy A54 5G; tr)",
        "Accept" to "application/json"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/public/api/media/seriesEpisodesAll/$apiToken" to "Yeni Bölümler",
        "$mainUrl/public/api/genres/latestmovies/all/$apiToken" to "Son Filmler",
        "$mainUrl/public/api/genres/latestseries/all/$apiToken" to "Son Diziler",
        "$mainUrl/public/api/genres/latestanimes/all/$apiToken" to "Son Animeler",
        "$mainUrl/public/api/genres/movies/show/28/$apiToken" to "Aksiyon Filmleri",
        "$mainUrl/public/api/genres/movies/show/27/$apiToken" to "Korku Filmleri",
        "$mainUrl/public/api/genres/movies/show/53/$apiToken" to "Gerilim Filmleri",
        "$mainUrl/public/api/genres/movies/show/10749/$apiToken" to "Romantik & Aşk Filmleri",
        "$mainUrl/public/api/genres/movies/show/35/$apiToken" to "Komedi Filmleri",
        "$mainUrl/public/api/genres/movies/show/878/$apiToken" to "Bilim Kurgu & Fantastik Filmler",
        "$mainUrl/public/api/genres/movies/show/80/$apiToken" to "Suç Filmleri",
        "$mainUrl/public/api/genres/movies/show/12/$apiToken" to "Macera Filmleri",
        "$mainUrl/public/api/genres/movies/show/16/$apiToken" to "Animasyon Filmleri",
        "$mainUrl/public/api/genres/movies/show/18/$apiToken" to "Dram Filmleri",
        "$mainUrl/public/api/genres/movies/show/9648/$apiToken" to "Gizem Filmleri",
        "$mainUrl/public/api/genres/movies/show/10752/$apiToken" to "Savaş & Tarih Filmleri",
        "$mainUrl/public/api/genres/movies/show/10751/$apiToken" to "Aile Filmleri",
        "$mainUrl/public/api/genres/series/show/18/$apiToken" to "Dram Dizileri",
        "$mainUrl/public/api/genres/series/show/9648/$apiToken" to "Gizem Dizileri",
        "$mainUrl/public/api/genres/series/show/35/$apiToken" to "Komedi Dizileri",
        "$mainUrl/public/api/genres/series/show/10759/$apiToken" to "Aksiyon & Macera Dizileri",
        "$mainUrl/public/api/genres/series/show/10765/$apiToken" to "Bilim Kurgu & Fantastik Dizileri",
        "$mainUrl/public/api/genres/series/show/80/$apiToken" to "Suç Dizileri",
        "$mainUrl/public/api/genres/series/show/53/$apiToken" to "Korku & Gerilim Dizileri",
        "$mainUrl/public/api/genres/series/show/10749/$apiToken" to "Romantik Diziler",
        "$mainUrl/public/api/genres/series/show/10769/$apiToken" to "Kore Dizileri (K-Drama)",
        "$mainUrl/public/api/genres/series/show/16/$apiToken" to "Animasyon Dizileri"
    )

    private fun parseSineWixItems(jsonString: String): List<SineWixIcerikler> {
        return try {
            val root = org.json.JSONObject(jsonString)
            val dataArray = if (root.has("data")) {
                val dataObj = root.get("data")
                if (dataObj is org.json.JSONObject && dataObj.has("data")) {
                    dataObj.getJSONArray("data")
                } else if (dataObj is org.json.JSONArray) {
                    dataObj
                } else null
            } else if (root.has("search")) {
                root.getJSONArray("search")
            } else null
            
            if (dataArray != null) {
                parseJson<List<SineWixIcerikler>>(dataArray.toString())
            } else {
                parseJson<SineWixResponseHash>(jsonString).data ?: emptyList()
            }
        } catch (e: Exception) {
            parseJson<SineWixResponseHash>(jsonString).data ?: emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val currentToken = getToken()
        val cleanUrl = request.data.replace("TOKEN_PLACEHOLDER", currentToken)
        val items = if (request.name == "Yeni Bölümler") {
            val startPage = (page - 1) * 3 + 1
            val endPage = page * 3
            val allItems = mutableListOf<SearchResponse>()
            coroutineScope {
                val deferred = (startPage..endPage).map { p ->
                    async {
                        try {
                            val response = app.get("$cleanUrl?page=$p", headers = sineHeaders).text
                            val newItems = parseJson<SineWixYeniBolumResponse>(response).data ?: emptyList()
                            coroutineScope {
                                newItems.map { item -> async { item.toSearchResponse() } }.awaitAll().filterNotNull()
                            }
                        } catch (e: Exception) { emptyList() }
                    }
                }
                deferred.awaitAll().forEach { allItems.addAll(it) }
            }
            allItems.distinctBy { it.url }
        } else {
            val startPage = (page - 1) * 3 + 1
            val endPage = page * 3
            val allItems = mutableListOf<SearchResponse>()
            val inferredType = when {
                cleanUrl.contains("anime", ignoreCase = true) -> TvType.Anime
                cleanUrl.contains("series", ignoreCase = true) -> TvType.TvSeries
                else -> TvType.Movie
            }
            coroutineScope {
                val deferred = (startPage..endPage).map { p ->
                    async {
                        try {
                            val response = app.get("$cleanUrl?page=$p", headers = sineHeaders).text
                            val list = parseSineWixItems(response)
                            coroutineScope {
                                list.map { item -> async { item.toSearchResponse(inferredType) } }.awaitAll().filterNotNull()
                            }
                        } catch (e: Exception) { emptyList() }
                    }
                }
                deferred.awaitAll().forEach { allItems.addAll(it) }
            }
            allItems.distinctBy { it.url }
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val currentToken = getToken()
        val cleanQuery = query.lowercase().trim()
            .replace(Regex("""\s*(?:filmi|dizisi|filmleri|dizileri|izle)$"""), "")
            .trim()

        val categoryId = when (cleanQuery) {
            "aksiyon" -> "movies/show/28"
            "korku" -> "movies/show/27"
            "gerilim" -> "movies/show/53"
            "romantik", "aşk", "ask" -> "movies/show/10749"
            "komedi" -> "movies/show/35"
            "bilim kurgu", "bilimkurgu", "fantastik" -> "movies/show/878"
            "suç", "suc" -> "movies/show/80"
            "macera" -> "movies/show/12"
            "animasyon" -> "movies/show/16"
            "dram" -> "movies/show/18"
            "gizem" -> "movies/show/9648"
            "savaş", "savas", "tarih" -> "movies/show/10752"
            "aile" -> "movies/show/10751"
            "kore", "k-drama", "kdrama" -> "series/show/10769"
            else -> null
        }

        val response = if (categoryId != null) {
            app.get("$mainUrl/public/api/genres/$categoryId/$currentToken", headers = sineHeaders).text
        } else {
            app.get("$mainUrl/public/api/search/$query/$currentToken", headers = sineHeaders).text
        }

        val list = parseSineWixItems(response)
        val inferredType = when {
            categoryId != null && categoryId.startsWith("series") -> TvType.TvSeries
            else -> TvType.Movie
        }
        return coroutineScope {
            list.map { async { it.toSearchResponse(inferredType) } }.awaitAll().filterNotNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val currentToken = getToken()
        val cleanedUrl = if (url.contains("/public/api/")) {
            url.substringBeforeLast("/") + "/$currentToken"
        } else {
            url
        }
        val responseText = app.get(cleanedUrl, headers = sineHeaders).text
        val it = parseJson<SineWixIcerikler>(responseText)
        
        val title = it.name ?: it.title ?: return null
        val poster = it.posterPath ?: it.backdropPath ?: it.backdropPathTv ?: ""
        val type = if (cleanedUrl.contains("serie") || it.type == "serie") TvType.TvSeries 
                   else if (cleanedUrl.contains("anime") || it.type == "anime") TvType.Anime
                   else TvType.Movie
        
        return if (type == TvType.TvSeries || type == TvType.Anime) {
            val episodes = it.seasons?.flatMap { season ->
                season.episodes?.map { episode ->
                    val videosJson = try {
                        jacksonObjectMapper().writeValueAsString(episode.videos ?: emptyList<SineWixVideo>())
                    } catch (e: Exception) {
                        ""
                    }
                    newEpisode(videosJson) {
                        this.name = episode.name ?: "Bölüm ${episode.episodeNumber}"
                        this.season = season.seasonNumber
                        this.episode = episode.episodeNumber
                        this.posterUrl = episode.stillPath ?: episode.stillPathTv
                    }
                } ?: emptyList()
            } ?: emptyList()
            
            newTvSeriesLoadResponse(title, cleanedUrl, type, episodes) {
                this.posterUrl = poster
                this.plot = it.overview
                this.year = it.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            }
        } else {
            val videosJson = try {
                jacksonObjectMapper().writeValueAsString(it.videos ?: emptyList<SineWixVideo>())
            } catch (e: Exception) {
                ""
            }
            newMovieLoadResponse(title, cleanedUrl, type, videosJson) {
                this.posterUrl = poster
                this.plot = it.overview
                this.year = it.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        if (data.isBlank()) return@coroutineScope false
        
        val videos = try {
            parseJson<List<SineWixVideo>>(data)
        } catch (e: Exception) {
            listOf(SineWixVideo(link = data))
        }
        
        val scope = this@coroutineScope
        videos.forEach { video ->
            val link = video.link ?: return@forEach
            val serverName = video.server?.trim() ?: "Sinewix"
            
            val isDirect = (link.contains(".mkv", true) || link.contains(".mp4", true) || link.contains(".m3u8", true) || link.contains(".avi", true) || link.contains(".flv", true))
                && !link.contains("mediafire.com", true)
                && !link.contains("google.com", true)
            if (isDirect) {
                val isM3u8 = link.contains(".m3u8", true)
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                val displayName = if (serverName.isNotBlank() && serverName != "Sinewix") "Sinewix - $serverName" else "Sinewix"
                scope.launch {
                    callback.invoke(
                        newExtractorLink(displayName, displayName, link, type = linkType) {
                            this.quality = Qualities.P1080.value
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "User-Agent" to "EasyPlex (Android 14; SM-A546B; Samsung Galaxy A54 5G; tr)",
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl,
                                "Accept" to "*/*"
                            )
                        }
                    )
                }
            } else {
                loadExtractor(link, "$mainUrl/", subtitleCallback) { extLink ->
                    val finalName = if (serverName != "Sinewix" && serverName.isNotBlank()) "Sinewix - $serverName (${extLink.name})" else extLink.name
                    scope.launch {
                        callback.invoke(
                            newExtractorLink(finalName, finalName, extLink.url, extLink.type) {
                                this.quality = extLink.quality
                                this.referer = extLink.referer.ifBlank { "$mainUrl/" }
                                this.headers = extLink.headers.ifEmpty {
                                    mapOf(
                                        "User-Agent" to "EasyPlex (Android 14; SM-A546B; Samsung Galaxy A54 5G; tr)",
                                        "Referer" to "$mainUrl/",
                                        "Accept" to "*/*"
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
        return@coroutineScope true
    }

    private val tmdbScoreCache = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private suspend fun fetchTmdbScore(title: String, isTv: Boolean, year: String?): Double? {
        val cacheKey = "$title-$isTv-$year"
        val cached = tmdbScoreCache[cacheKey]
        if (cached != null) return cached
        return try {
            kotlinx.coroutines.withTimeoutOrNull(1500) {
                val queryClean = java.net.URLEncoder.encode(title, "UTF-8")
                val yearParam = if (year != null) "&year=$year" else ""
                val typeStr = if (isTv) "tv" else "movie"
                val urlVal = "https://api.themoviedb.org/3/search/$typeStr?api_key=04c35731a5ee918f014970082a0088b1&query=$queryClean$yearParam"
                val resText = app.get(urlVal, cacheTime = 1440).text // 24 saat önbellek
                val jsonObj = parseJson<TmdbSearchResponse>(resText)
                val scoreVal = jsonObj.results?.firstOrNull()?.voteAverage
                if (scoreVal != null && scoreVal > 0.0) {
                    tmdbScoreCache[cacheKey] = scoreVal
                    scoreVal
                } else null
            }
        } catch (e: Exception) { null }
    }

    private suspend fun SineWixIcerikler.toSearchResponse(inferredType: TvType? = null): SearchResponse? {
        val titleVal = name ?: title ?: return null
        val poster = posterPath ?: backdropPath ?: backdropPathTv ?: ""
        val typeVal = if (this.type == "serie") TvType.TvSeries 
                      else if (this.type == "anime") TvType.Anime 
                      else if (this.type == "movie") TvType.Movie
                      else inferredType ?: TvType.Movie
        val currentToken = cachedToken ?: "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA"
        val href = when (typeVal) {
            TvType.Anime -> "$mainUrl/public/api/animes/show/$id/$currentToken"
            TvType.TvSeries -> "$mainUrl/public/api/series/show/$id/$currentToken"
            else -> "$mainUrl/public/api/media/detail/$id/$currentToken"
        }
        val releaseYear = releaseDate?.split("-")?.firstOrNull()
        val isTv = typeVal == TvType.TvSeries || typeVal == TvType.Anime
        val tmdbScore = fetchTmdbScore(titleVal, isTv, releaseYear) ?: voteAverage
        
        return if (typeVal == TvType.TvSeries) {
            newTvSeriesSearchResponse(titleVal, href, typeVal) {
                this.posterUrl = poster
                val pHeaders = mutableMapOf<String, String>()
                tmdbScore?.takeIf { it > 0.0 }?.let {
                    this.score = Score.from10(it)
                    pHeaders["TMDb"] = String.format("%.1f", it)
                }
                this.posterHeaders = pHeaders
            }
        } else if (typeVal == TvType.Anime) {
            newAnimeSearchResponse(titleVal, href, typeVal) {
                this.posterUrl = poster
                val pHeaders = mutableMapOf<String, String>()
                tmdbScore?.takeIf { it > 0.0 }?.let {
                    this.score = Score.from10(it)
                    pHeaders["TMDb"] = String.format("%.1f", it)
                }
                this.posterHeaders = pHeaders
            }
        } else {
            newMovieSearchResponse(titleVal, href, typeVal) {
                this.posterUrl = poster
                val pHeaders = mutableMapOf<String, String>()
                tmdbScore?.takeIf { it > 0.0 }?.let {
                    this.score = Score.from10(it)
                    pHeaders["TMDb"] = String.format("%.1f", it)
                }
                this.posterHeaders = pHeaders
            }
        }
    }

    private suspend fun SineWixYeniBolum.toSearchResponse(): SearchResponse? {
        val titleVal = showName ?: return null
        val poster = posterPath ?: ""
        val displayTitle = "$titleVal ${seasonNumber}x${episodeNumber.toString().padStart(2, '0')}"
        val currentToken = cachedToken ?: "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA"
        val isAnime = this.type == "anime"
        val href = if (isAnime) {
            "$mainUrl/public/api/animes/show/$id/$currentToken"
        } else {
            "$mainUrl/public/api/series/show/$id/$currentToken"
        }
        val targetType = if (isAnime) TvType.Anime else TvType.TvSeries
        val isTv = targetType == TvType.TvSeries || targetType == TvType.Anime
        val tmdbScore = fetchTmdbScore(titleVal, isTv, null) ?: voteAverage
        
        return if (targetType == TvType.TvSeries) {
            newTvSeriesSearchResponse(displayTitle, href, targetType) {
                this.posterUrl = poster
                val pHeaders = mutableMapOf<String, String>()
                tmdbScore?.takeIf { it > 0.0 }?.let {
                    this.score = Score.from10(it)
                    pHeaders["TMDb"] = String.format("%.1f", it)
                }
                this.posterHeaders = pHeaders
            }
        } else {
            newAnimeSearchResponse(displayTitle, href, targetType) {
                this.posterUrl = poster
                val pHeaders = mutableMapOf<String, String>()
                tmdbScore?.takeIf { it > 0.0 }?.let {
                    this.score = Score.from10(it)
                    pHeaders["TMDb"] = String.format("%.1f", it)
                }
                this.posterHeaders = pHeaders
            }
        }
    }

    data class TmdbSearchResponse(
        @JsonProperty("results") val results: List<TmdbResult>? = null
    )

    data class TmdbResult(
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )
    
    data class SineWixResponseHash(
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("data") val data: List<SineWixIcerikler>? = null,
        @JsonProperty("search") val searchResponse: List<SineWixIcerikler>? = null
    )

    data class SineWixYeniBolumResponse(
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("data") val data: List<SineWixYeniBolum>? = null
    )

    data class SineWixIcerikler(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("backdrop_path_tv") val backdropPathTv: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("genre_name") val genreName: String? = null,
        @JsonProperty("seasons") val seasons: List<SineWixSeason>? = null,
        @JsonProperty("videos") val videos: List<SineWixVideo>? = null
    )

    data class SineWixYeniBolum(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val showName: String? = null,
        @JsonProperty("episode_name") val episodeName: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class SineWixSeason(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episodes") val episodes: List<SineWixEpisode>? = null
    )

    data class SineWixEpisode(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("still_path_tv") val stillPathTv: String? = null,
        @JsonProperty("videos") val videos: List<SineWixVideo>? = null
    )

    data class SineWixVideo(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("server") val server: String? = null
    )
}
