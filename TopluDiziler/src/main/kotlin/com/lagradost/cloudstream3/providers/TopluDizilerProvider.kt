package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import kotlinx.coroutines.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import android.content.Context

class TopluDizilerProvider : MainAPI() {
    companion object {
        private val mainPageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, HomePageResponse>>()
        private const val CACHE_EXPIRATION_MS = 5 * 60 * 1000 // 5 minutes in milliseconds

        fun clearCache() {
            mainPageCache.clear()
        }
    }

    private fun getActiveProvidersForPage(
        allProviders: List<Pair<String, suspend () -> HomePageResponse?>>,
        page: Int
    ): List<Pair<String, suspend () -> HomePageResponse?>> {
        return allProviders
    }

    override var mainUrl = "https://cagi-topludiziler.com"
    override var name = "VizyonHub"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Cartoon,
        TvType.Documentary,
        TvType.Live
    )

    private val jsonMapper = jacksonObjectMapper()

    private fun <T> writeJson(obj: T): String {
        return jsonMapper.writeValueAsString(obj)
    }

    private inline fun <reified T> readJson(json: String): T? {
        return try {
            jsonMapper.readValue<T>(json)
        } catch (e: Throwable) {
            null
        }
    }

    private val sinewixMainUrl = "https://ydfvfdizipanel.ru"
    private val sineHeaders = mapOf(
        "hash256" to "711bff4afeb47f07ab08a0b07e85d3835e739295e8a6361db77eebd93d96306b",
        "signature" to "3082058830820370a00302010202145bbfbba9791db758ad12295636e094ab4b07dc24300d06092a864886f70d01010b05003074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643020170d3231313231353232303433335a180f32303531313231353232303433335a3074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820222300d06092a864886f70d01010105000382020f003082020a0282020100a5106a24bb3f9c0aaf3a2b228f794b5eaf1757ba758b19736a39d1bdc73fc983a7237b8d5ca5156cfa999c1dab3418bbc2be0920e0ee001c8aa4812d1dae75d080f09e91e0abda83ff9a76e8384a4429f4849248069a59505b12ac2c14ba2e4d1a13afcdaf54e508697ff928a9f738e6f4a6fc27409c55329eb149b5ff89c5a2d7c06bf9e62086f955cad17d7be2623ee9d5ec56068eadc23cb0965a13ff97d49fe10ef41afc6eeca36b4ace9582097faff89f590bc831cdb3a69eec5d15b67c3f2cad49e37ed053733e3d2d400c47755b932bdbe15d749fd6ad1dce30ba5e66094dfb6ee6f64cafb807e11b19a990c5d078c6d6701cda0bdeb21e99404ff166074f4c89b04c418f4e7940db5c78647c475bcfb85d4c4e836ee7d7c1d53e9e736b5d96d4b4d8b98209064b729ac6a682d55a6a930e518d849898bb28329ca0aaa133b5e5270a9d5940cac6af4802a57fd971efda91abb602882dd6aa6ce2b236b57b52ee2481498f0cacbcc2c36c238bc84becad7eaaf1125b9a1ca9ded6c79f3f283a52050377809b2a9995d66e1636b0ed426fdd8685c47cb18e82077f4aefcc07887e1dc58b4d64be1632f0e7b4625da6f40c65a8512a6454a4b96963e7f876136e6c0069a519a79ad632078ed965aa12482458060c030ed50db706d854f88cb004630b49285d8af8b471ff8f6070687826412287b50049bcb7d1b6b62ef90203010001a310300e300c0603551d13040530030101ff300d06092a864886f70d01010b0500038202010051c0b7bd793181dc29ca777d3773f928a366c8469ecf2fa3cfb076e8831970d19bb2b96e44e8ccc647cf0696bb824ac61c23d958525d283cab26037b04d58aa79bf92192db843adf5c26a980f081d2f0e14f759fc5ff4c5bb3dce0860299bfe7b349a8155a2efaf731ba25ce796a80c1442c7bf80f8c1a7912ff0b6f6592264315337251a846460194fa594f81f38f9e5233a63201e931ad9cab5bf119f24025613f307194eaa6eb39a83f3c05a49ba34455b1aff7c6839bbb657d9392ffdf397432af6e56ba9534a8b07d7060fe09691c6cf07cb5324f67b3cc0871a8c621d81fe71d71085c55206a4f57e25f774fd4b979b299e8bb076b50fca42fa57da2d519fd35a4a7c0137babaed4345f8031b63b6a71f5e8268f709d658ccd7c2a58849379d25bfa598c3f4a2c3d9b7d89285fefeb7f0ec65137d38b08ce432a15688b624a179e6a4a505ebc3bcdfbc4d4330508ee2d8d0f016924dcec21a6838ef7d834c6f43bde4a5201ed0b3bb4e9bd377b470e36bcf5bc3d56169dbd8e39567aa7dce4d1a8a8a54a5e1aa6fb1a8aab0062669a966f96e15ccce6fe12ea5e6a8b8c8823bdc94988ca39759fd1cc8fd8ae5c3d74db50b174cf7d77655016c075c91d439ed01cc0a9f695c99fad3b5495fb6cb1e01a5fa020cc6022a85c07ec55f9eba89719f86e49d34ab5bd208c5f70cced2b7b7963c014f8404432979b506de29e",
        "User-Agent" to "EasyPlex (Android 14; SM-A546B; Samsung Galaxy A54 5G; tr)",
        "Accept" to "application/json"
    )
    private var sinewixToken: String? = "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA"
    private suspend fun getSinewixToken(): String {
        return sinewixToken ?: "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA"
    }

    private suspend fun getSinewixMetadata(title: String): Triple<String?, String?, Int?> {
        try {
            val cleanTitle = title.replace(Regex("""\s*\(\d{4}\)"""), "")
                .replace(Regex("""\d+\s*\.?\s*(?:sezon|season|bolum|episode).*""", RegexOption.IGNORE_CASE), "")
                .trim()
            val token = withTimeoutOrNull(3000) { getSinewixToken() } ?: return Triple(null, null, null)
            val searchUrl = "$sinewixMainUrl/public/api/search/${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}/$token"
            val searchRes = withTimeoutOrNull(8000) { app.get(searchUrl, headers = sineHeaders).text } ?: return Triple(null, null, null)
            val root = JSONObject(searchRes)
            val dataArray = if (root.has("data")) {
                val dataObj = root.get("data")
                if (dataObj is JSONObject && dataObj.has("data")) {
                    dataObj.getJSONArray("data")
                } else if (dataObj is JSONArray) {
                    dataObj
                } else null
            } else if (root.has("search")) {
                root.getJSONArray("search")
            } else null

            if (dataArray != null && dataArray.length() > 0) {
                var bestMatch: JSONObject? = null
                val cleanTitleNormalized = cleanTitle.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val nameVal = item.optString("name", item.optString("title", ""))
                    val nameNormalized = nameVal.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
                    if (nameNormalized == cleanTitleNormalized) {
                        bestMatch = item
                        break
                    }
                }
                if (bestMatch == null) {
                    bestMatch = dataArray.getJSONObject(0)
                }

                if (bestMatch != null) {
                    val swPoster = bestMatch.optString("posterPath", bestMatch.optString("backdropPath", ""))
                    val swOverview = bestMatch.optString("overview", "")
                    val swReleaseDate = bestMatch.optString("releaseDate", "")
                    val swYear = swReleaseDate.split("-").firstOrNull()?.toIntOrNull()

                    return Triple(
                        swPoster.takeIf { it.isNotEmpty() },
                        swOverview.takeIf { it.isNotEmpty() },
                        swYear
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return Triple(null, null, null)
    }

    private fun normalizeTitle(title: String): String {
        var text = title.lowercase()
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace("İ", "i")
            .trim()
        
        // Remove year like (2010)
        text = text.replace(Regex("""\s*\(\d{4}\)"""), "")
        
        // Replace season/episode words but keep the numbers!
        text = text.replace(Regex("""(\d+)\s*\.?\s*(?:sezon|season|bolum|episode|ep)""", RegexOption.IGNORE_CASE), "$1")
        text = text.replace(Regex("""(?:season|sezon|bolum|episode|ep)\s*(\d+)""", RegexOption.IGNORE_CASE), "$1")
        
        // Remove trailing stream/dub/sub metadata suffixes after a hyphen or space
        text = text.replace(Regex("""\s*-\s*(?:turkce|dublaj|altyazi|altyazili|dub|sub|izle|hizli|hd|fullhd|sansursuz|dual).*""", RegexOption.IGNORE_CASE), "")
        
        // Replace hyphens with space
        text = text.replace("-", " ")
        
        // Remove non-alphanumeric chars
        text = text.replace(Regex("""[^a-z0-9\s]"""), "")
        
        // Remove common Turkish and English stream metadata words
        val stopWords = listOf(
            "izle", "filmi", "dizisi", "filmleri", "dizileri", "izlesene", "fullhd", "hd",
            "turkce", "dublaj", "altyazi", "altyazili", "eng", "tr", "org", "net"
        )
        var words = text.split(Regex("""\s+""")).filter { it.isNotEmpty() && !stopWords.contains(it) }
        
        // If everything was filtered out, fallback to original words
        if (words.isEmpty()) {
            words = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        }
        
        return words.joinToString("")
    }

    private fun cleanSearchQuery(title: String): String {
        var text = title
            .replace(Regex("""\s*\(\d{4}\)"""), "") // Remove year
            // Remove season suffixes like "- 1. Sezon" or "- Türkçe Dublaj"
            .replace(Regex("""\s*-\s*\d+\s*\.?\s*(?:sezon|season|bolum|episode).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*(?:turkce|dublaj|altyazi|altyazili|dub|sub|izle|sansursuz|hizli|hd|fullhd|dual).*""", RegexOption.IGNORE_CASE), "")
            // Remove plain season/episode words without hyphen
            .replace(Regex("""\d+\s*\.?\s*(?:sezon|season|bolum|episode).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?:season|sezon|bolum|episode)\s*\d+.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ\s\-]"""), "") // Keep hyphen in clean query!
            .trim()
        return text
    }

    private fun getCoreWords(title: String): List<String> {
        var text = title.lowercase()
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace("İ", "i")
            .trim()
            
        // Remove year like (2010)
        text = text.replace(Regex("""\s*\(\d{4}\)"""), "")
        
        // Remove season designations completely
        text = text.replace(Regex("""\d+\s*\.?\s*(?:sezon|season|bolum|episode|ep)""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""(?:season|sezon|bolum|episode|ep)\s*(\d+)""", RegexOption.IGNORE_CASE), "")
        
        // Remove standalone years (1900-2099) only if there are other words in the title
        val words = text.split(Regex("""\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val hasOtherWords = words.any { !it.matches(Regex("""^(?:19|20)\d{2}$""")) }
        if (hasOtherWords) {
            text = text.replace(Regex("""\b(?:19|20)\d{2}\b"""), "")
        }
        
        // Replace non-alphanumeric with space
        text = text.replace(Regex("""[^a-z0-9\s]"""), " ")
        
        val stopWords = setOf(
            "izle", "filmi", "dizisi", "filmleri", "dizileri", "izlesene", "fullhd", "hd",
            "turkce", "dublaj", "altyazi", "altyazili", "eng", "tr", "org", "net",
            "season", "sezon", "bolum", "episode", "ep", "oav", "ova", "special", "ozel",
            "the", "a", "an", "bir", "and", "ve", "with", "ile", "de", "da",
            "dizi", "film", "sinema", "part", "tek", "parca", "dub", "sub",
            "tum", "tüm", "bolumler", "bölümler", "bolumu", "bölümü", "bolumleri", "bölümleri",
            "sezonu", "sezonları", "sezonlari", "serisi", "serisiizle", "hdizle", "tekparca",
            "arsivi", "arsiv", "koleksiyonu", "boxset", "sansursuz", "1080p", "720p", "4k", "dual",
            "filmizle", "diziizle", "turkish", "english", "ingilizce"
        )
        
        return text.split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in stopWords }
    }

    private fun getSeasonNumber(title: String): String? {
        val seasonRegex = Regex("""(\d+)\s*\.?\s*(?:sezon|season)""", RegexOption.IGNORE_CASE)
        val seasonRegex2 = Regex("""(?:season|sezon)\s*(\d+)""", RegexOption.IGNORE_CASE)
        return seasonRegex.find(title)?.groupValues?.get(1) 
            ?: seasonRegex2.find(title)?.groupValues?.get(1)
    }

    private fun getPartNumber(title: String): Int? {
        val normalized = title.lowercase()
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace("İ", "i")
            
        // Look for digit
        val digitRegex = Regex("""\b(\d+)\b""")
        val digits = digitRegex.findAll(normalized).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val filtered = digits.filter { it < 1900 || it > 2100 }
        if (filtered.isNotEmpty()) {
            return filtered.first()
        }
        
        // Look for roman numerals
        val romanMap = mapOf(
            "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10
        )
        for ((roman, num) in romanMap) {
            if (normalized.contains(Regex("""\b$roman\b"""))) {
                return num
            }
        }
        
        // Look for words
        val wordMap = mapOf(
            "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "iki" to 2, "uc" to 3, "dort" to 4, "bes" to 5, "alti" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9, "on" to 10
        )
        for ((word, num) in wordMap) {
            if (normalized.contains(Regex("""\b$word\b"""))) {
                return num
            }
        }
        
        return null
    }

    private fun isTitleMatch(title1: String, title2: String): Boolean {
        val targetParts = title2.split("|").map { it.trim() }
        
        return targetParts.any { part ->
            val w1 = getCoreWords(title1)
            val w2 = getCoreWords(part)
            
            val set1 = w1.toSet()
            val set2 = w2.toSet()
            if (set1.isEmpty() || set2.isEmpty()) return@any false
            
            val intersect = set1.intersect(set2).size
            val union = set1.union(set2).size
            val jaccard = intersect.toDouble() / union
            
            // Use a stricter threshold - especially important for short titles like "The Boys"
            // where a subtitle ("Diabolical") would add extra words making it a false match
            val isCoreMatch = jaccard >= 0.75
            if (!isCoreMatch) return@any false
            
            // If one title has significantly more words, require they are a strict subset
            val sizeDiff = kotlin.math.abs(set1.size - set2.size)
            if (sizeDiff >= 2 && intersect < minOf(set1.size, set2.size)) return@any false
            
            // Check part/sequel number
            val p1 = getPartNumber(title1) ?: 1
            val p2 = getPartNumber(part) ?: 1
            if (p1 != p2) return@any false
            
            // Check season conflict
            val s1 = getSeasonNumber(title1)
            val s2 = getSeasonNumber(part)
            if (s1 != null && s2 != null && s1 != s2) {
                return@any false // Conflicting seasons
            }
            
            true
        }
    }

    private fun getBestPoster(items: List<Pair<String, SearchResponse>>): String? {
        val posterPriorityMap = mapOf(
            "FilmizleCh" to 10,
            "Sinewix" to 9,
            "FilmMakinesi" to 8,
            "AnimeciX" to 7,
            "Kült Filmler" to 6,
            "SezonlukDizi" to 5,
            "Dizipal" to 4,
            "DDizi" to 3,
            "OpenAnime" to 2,
            "Belgesel & Yaşam (AIO)" to 1
        )
        
        val sortedForPoster = items.sortedByDescending { 
            val priority = posterPriorityMap[it.first] ?: 0
            val poster = getPosterFromSearchResponse(it.second)
            if (!poster.isNullOrEmpty()) priority else -1
        }
        
        return sortedForPoster.firstOrNull()?.let { getPosterFromSearchResponse(it.second) }
    }

    private suspend fun getTmdbPoster(title: String, size: String = "w500"): String? {
        return try {
            val cleanTitle = title.replace(Regex("""\s*\(\d{4}\)"""), "")
                .replace(Regex("""\d+\s*\.?\s*(?:sezon|season|bolum|episode).*""", RegexOption.IGNORE_CASE), "")
                .trim()
            val encoded = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val apiKey = "04c35731a5ee918f014970082a0088b1"
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$encoded&language=tr-TR"
            val res = app.get(url, cacheTime = 86400) // Cache for a day
            if (res.isSuccessful) {
                val posterPath = Regex(""""poster_path"\s*:\s*"([^"]+)"""").find(res.text)?.groupValues?.get(1)
                if (!posterPath.isNullOrBlank() && posterPath != "null") {
                    "https://image.tmdb.org/t/p/$size$posterPath"
                } else null
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun getTmdbAlternativeTitles(title: String): List<String> {
        val cleanTitle = title.replace(Regex("""\s*\(\d{4})"""), "")
            .replace(Regex("""\d+\s*\.?\s*(?:sezon|season|bolum|episode).*""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanTitle.isBlank()) return listOf(title)

        val resultList = mutableListOf<String>()
        resultList.add(title)
        resultList.add(cleanTitle)

        try {
            val encoded = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val apiKey = "04c35731a5ee918f014970082a0088b1"
            
            coroutineScope {
                val trDeferred = async { 
                    try {
                        app.get("https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$encoded&language=tr-TR", cacheTime = 86400)
                    } catch(_: Throwable) { null }
                }
                val enDeferred = async {
                    try {
                        app.get("https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$encoded&language=en-US", cacheTime = 86400)
                    } catch(_: Throwable) { null }
                }
                
                val trRes = trDeferred.await()
                val enRes = enDeferred.await()
                
                if (trRes != null && trRes.isSuccessful) {
                    val root = JSONObject(trRes.text)
                    val results = root.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val first = results.getJSONObject(0)
                        val id = first.optInt("id", 0)
                        
                        val trTitle = first.optString("title", first.optString("name", "")).trim()
                        val trOriginal = first.optString("original_title", first.optString("original_name", "")).trim()
                        if (trTitle.isNotEmpty()) resultList.add(trTitle)
                        if (trOriginal.isNotEmpty()) resultList.add(trOriginal)
                        
                        if (enRes != null && enRes.isSuccessful && id != 0) {
                            val enRoot = JSONObject(enRes.text)
                            val enResults = enRoot.optJSONArray("results")
                            if (enResults != null) {
                                for (j in 0 until enResults.length()) {
                                    val enItem = enResults.getJSONObject(j)
                                    if (enItem.optInt("id", 0) == id) {
                                        val enTitle = enItem.optString("title", enItem.optString("name", "")).trim()
                                        if (enTitle.isNotEmpty()) resultList.add(enTitle)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        
        return resultList.distinct()
    }


    private suspend fun getTmdbTrends(type: String, page: Int): HomePageResponse? {
        return try {
            val apiKey = "04c35731a5ee918f014970082a0088b1"
            val url = "https://api.themoviedb.org/3/trending/$type/week?api_key=$apiKey&language=tr-TR&page=$page"
            val res = app.get(url, cacheTime = 3600)
            if (res.isSuccessful) {
                val root = JSONObject(res.text)
                val resultsArray = root.optJSONArray("results") ?: return null
                val totalPages = root.optInt("total_pages", 1)
                
                val items = mutableListOf<SearchResponse>()
                for (i in 0 until resultsArray.length()) {
                    val item = resultsArray.getJSONObject(i)
                    val title = item.optString("title", item.optString("name", "")).trim()
                    if (title.isBlank()) continue
                    
                    val originalTitle = item.optString("original_title", item.optString("original_name", "")).trim()
                    val combinedTitle = if (originalTitle.isNotEmpty() && originalTitle != title) {
                        "$title | $originalTitle"
                    } else {
                        title
                    }
                    
                    val posterPath = item.optString("poster_path", "")
                    val posterUrl = if (posterPath.isNotEmpty() && posterPath != "null") {
                        "https://image.tmdb.org/t/p/w185$posterPath"
                    } else null
                    
                    val voteAverage = item.optDouble("vote_average", 0.0)
                    val scoreVal = if (voteAverage > 0.0) {
                        Score.from10(voteAverage)
                    } else null
                    
                    val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                    
                    val serialized = writeJson(TopluResultData(combinedTitle, emptyMap()))
                    
                    items.add(newAnimeSearchResponse(title, serialized, tvType) {
                        this.posterUrl = posterUrl
                        scoreVal?.let { this.score = it }
                    })
                }
                newHomePageResponse(if (type == "movie") "Trend Filmler" else "Trend Diziler", items, page < totalPages)
            } else null
        } catch (e: Exception) {
            Log.e("TopluDiziler", "TMDB trending error: ${e.message}", e)
            null
        }
    }

    private suspend fun getTmdbTopRated(type: String, page: Int): HomePageResponse? {
        return try {
            val apiKey = "04c35731a5ee918f014970082a0088b1"
            val url = "https://api.themoviedb.org/3/$type/top_rated?api_key=$apiKey&language=tr-TR&page=$page"
            val res = app.get(url, cacheTime = 3600)
            if (res.isSuccessful) {
                val root = JSONObject(res.text)
                val resultsArray = root.optJSONArray("results") ?: return null
                val totalPages = root.optInt("total_pages", 1)
                
                val items = mutableListOf<SearchResponse>()
                for (i in 0 until resultsArray.length()) {
                    val item = resultsArray.getJSONObject(i)
                    val title = item.optString("title", item.optString("name", "")).trim()
                    if (title.isBlank()) continue
                    
                    val originalTitle = item.optString("original_title", item.optString("original_name", "")).trim()
                    val combinedTitle = if (originalTitle.isNotEmpty() && originalTitle != title) {
                        "$title | $originalTitle"
                    } else {
                        title
                    }
                    
                    val posterPath = item.optString("poster_path", "")
                    val posterUrl = if (posterPath.isNotEmpty() && posterPath != "null") {
                        "https://image.tmdb.org/t/p/w185$posterPath"
                    } else null
                    
                    val voteAverage = item.optDouble("vote_average", 0.0)
                    val scoreVal = if (voteAverage > 0.0) {
                        Score.from10(voteAverage)
                    } else null
                    
                    val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
                    
                    val serialized = writeJson(TopluResultData(combinedTitle, emptyMap()))
                    
                    items.add(newAnimeSearchResponse(title, serialized, tvType) {
                        this.posterUrl = posterUrl
                        scoreVal?.let { this.score = it }
                    })
                }
                newHomePageResponse(if (type == "movie") "⭐ IMDb Top 250 Filmler" else "⭐ IMDb Top 250 Diziler", items, page < totalPages)
            } else null
        } catch (e: Exception) {
            Log.e("TopluDiziler", "TMDB top rated error: ${e.message}", e)
            null
        }
    }

    private suspend fun getTmdbTopAnime(page: Int): HomePageResponse? {
        return try {
            val apiKey = "04c35731a5ee918f014970082a0088b1"
            val url = "https://api.themoviedb.org/3/discover/tv?api_key=$apiKey&language=tr-TR&with_genres=16&with_original_language=ja&sort_by=vote_average.desc&vote_count.gte=100&page=$page"
            val res = app.get(url, cacheTime = 3600)
            if (res.isSuccessful) {
                val root = JSONObject(res.text)
                val resultsArray = root.optJSONArray("results") ?: return null
                val totalPages = root.optInt("total_pages", 1)
                
                val items = mutableListOf<SearchResponse>()
                for (i in 0 until resultsArray.length()) {
                    val item = resultsArray.getJSONObject(i)
                    val title = item.optString("title", item.optString("name", "")).trim()
                    if (title.isBlank()) continue
                    
                    val originalTitle = item.optString("original_title", item.optString("original_name", "")).trim()
                    val combinedTitle = if (originalTitle.isNotEmpty() && originalTitle != title) {
                        "$title | $originalTitle"
                    } else {
                        title
                    }
                    
                    val posterPath = item.optString("poster_path", "")
                    val posterUrl = if (posterPath.isNotEmpty() && posterPath != "null") {
                        "https://image.tmdb.org/t/p/w185$posterPath"
                    } else null
                    
                    val voteAverage = item.optDouble("vote_average", 0.0)
                    val scoreVal = if (voteAverage > 0.0) {
                        Score.from10(voteAverage)
                    } else null
                    
                    val serialized = writeJson(TopluResultData(combinedTitle, emptyMap()))
                    
                    items.add(newAnimeSearchResponse(title, serialized, TvType.Anime) {
                        this.posterUrl = posterUrl
                        scoreVal?.let { this.score = it }
                    })
                }
                newHomePageResponse("⭐ En İyi Animeler (Top Rated Anime)", items, page < totalPages)
            } else null
        } catch (e: Exception) {
            Log.e("TopluDiziler", "TMDB top anime error: ${e.message}", e)
            null
        }
    }

    private fun detectProvider(url: String): MainAPI? {
        if (url.contains("dmax.com.tr") || url.contains("tlctv.com.tr") || url.contains("belgeselx.com")) {
            return APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
        }
        return APIHolder.getApiFromUrlNull(url)
    }

    private fun getPosterFromSearchResponse(item: SearchResponse): String? {
        return when (item) {
            is MovieSearchResponse -> item.posterUrl
            is TvSeriesSearchResponse -> item.posterUrl
            is AnimeSearchResponse -> item.posterUrl
            is LiveSearchResponse -> item.posterUrl
            else -> null
        }
    }

    private fun getScoreFromSearchResponse(item: SearchResponse): Score? {
        return when (item) {
            is MovieSearchResponse -> item.score
            is TvSeriesSearchResponse -> item.score
            is AnimeSearchResponse -> item.score
            else -> null
        }
    }

    private fun getDubStatusFromSearchResponse(item: SearchResponse): Set<DubStatus> {
        return try {
            val field = item.javaClass.getDeclaredField("dubStatus")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            field.get(item) as? Set<DubStatus> ?: emptySet()
        } catch (e: Throwable) {
            try {
                val method = item.javaClass.getMethod("getDubStatus")
                @Suppress("UNCHECKED_CAST")
                method.invoke(item) as? Set<DubStatus> ?: emptySet()
            } catch (e2: Throwable) {
                emptySet()
            }
        }
    }

    private fun getCategoryModeOfRequest(data: String): String {
        if (data.startsWith("toplu-trend-filmler") || data.startsWith("toplu-filmler") || data.startsWith("toplu-kultfilmler") || data.startsWith("toplu-imdb-top250-filmler")) return "movie"
        if (data.startsWith("toplu-animeler") || data.startsWith("toplu-anime")) return "anime"
        if (data.startsWith("toplu-canlitv")) return "live"
        if (data.startsWith("toplu-belgeseller")) return "doc"
        if (data.startsWith("toplu-trend-diziler") || data.startsWith("toplu-diziler") || data.startsWith("toplu-yerlidiziler") || data.startsWith("toplu-imdb-top250-diziler") || data.startsWith("toplu-diziler-") || data == "toplu-cocuk") return "tv"
        return "all"
    }

    override val mainPage: List<MainPageData>
        get() {
            val context = TopluDizilerPlugin.pluginContext ?: TopluDizilerPlugin.currentActivity
            val prefs = context?.getSharedPreferences("vizyonhub_prefs", Context.MODE_PRIVATE)
            val currentMode = prefs?.getString("watch_mode", "all") ?: "all"

            val allPages = mainPageOf(
                // Filmler
                "toplu-filmler" to "🎬 Son Eklenen Filmler",
                "toplu-imdb-top250-filmler" to "⭐ IMDb Top 250 Filmler",
                "toplu-filmler-yerli" to "🇹🇷 Yerli Filmler",
                "toplu-kultfilmler" to "🏆 Kült Filmler",
                "toplu-filmler-aksiyon" to "💥 Aksiyon Filmleri",
                "toplu-filmler-komedi" to "🤣 Komedi Filmleri",
                "toplu-filmler-korku" to "😱 Korku & Gerilim Filmleri",
                "toplu-filmler-bilimkurgu" to "🚀 Bilim Kurgu Filmleri",
                "toplu-filmler-dram" to "🎭 Dram Filmleri",
                "toplu-filmler-animasyon" to "🍿 Animasyon Filmleri",

                // Diziler
                "toplu-diziler" to "📺 Son Eklenen Diziler",
                "toplu-imdb-top250-diziler" to "⭐ IMDb Top 250 Diziler",
                "toplu-yerlidiziler" to "🇹🇷 Yerli Diziler",
                "toplu-diziler-aksiyon" to "💥 Aksiyon & Macera Dizileri",
                "toplu-diziler-bilimkurgu" to "🚀 Bilim Kurgu & Fantastik Dizileri",
                "toplu-diziler-korku" to "😱 Korku & Gerilim Dizileri",
                "toplu-diziler-komedi" to "🤣 Komedi Dizileri",
                "toplu-diziler-dram" to "🎭 Dram Dizileri",

                // Belgeseller
                "toplu-belgeseller-dmax" to "DMAX: Öne Çıkanlar",
                "toplu-belgeseller-tlc" to "TLC: Öne Çıkanlar",
                "toplu-belgeseller-trt" to "TRT Belgesel",
                "toplu-belgeseller-doga" to "Doğa Belgeselleri",
                "toplu-belgeseller-tarih" to "Tarih Belgeselleri",
                "toplu-belgeseller-uzay" to "Bilim & Teknoloji Belgeselleri",
                "toplu-belgeseller-succrime" to "Suç & Araştırma Belgeselleri",

                // Animeler
                "toplu-animeler" to "⛩️ Son Eklenen Animeler",
                "toplu-anime-top250" to "⭐ En İyi Animeler (Top Rated Anime)",
                "toplu-animeler-aksiyon" to "💥 Aksiyon Animeleri",
                "toplu-animeler-komedi" to "🤣 Komedi Animeleri",
                "toplu-animeler-bilimkurgu" to "🚀 Bilim Kurgu & Fantastik Animeler",
                "toplu-animeler-gizem" to "🔍 Gizem & Gerilim Animeleri",

                // Canlı TV
                "toplu-canlitv-ulusal" to "📺 Ulusal Kanallar",
                "toplu-canlitv-haber" to "📰 Haber Kanalları",
                "toplu-canlitv-belgesel" to "🎥 Belgesel Kanalları",
                "toplu-canlitv-yerel" to "📡 Yerel Kanallar"
            )

            if (currentMode == "all") return allPages

            return allPages.filter { page ->
                getCategoryModeOfRequest(page.data) == currentMode
            }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val context = TopluDizilerPlugin.pluginContext ?: TopluDizilerPlugin.currentActivity
        val prefs = context?.getSharedPreferences("vizyonhub_prefs", Context.MODE_PRIVATE)
        val currentMode = prefs?.getString("watch_mode", "all") ?: "all"

        val reqMode = getCategoryModeOfRequest(request.data)
        if (currentMode != "all" && currentMode != reqMode) {
            return null
        }

        val cacheKey = "${request.data}-$page"
        val cached = mainPageCache[cacheKey]
        if (cached != null && (System.currentTimeMillis() - cached.first < CACHE_EXPIRATION_MS)) {
            Log.d("TopluDiziler", "Returning cached home page for $cacheKey")
            return cached.second
        }
        val result = getMainPageInternal(page, request)
        if (result != null) {
            mainPageCache[cacheKey] = Pair(System.currentTimeMillis(), result)
        }
        return result
    }

    private suspend fun getMainPageInternal(page: Int, request: MainPageRequest): HomePageResponse? {
        val context = TopluDizilerPlugin.pluginContext
        if (context != null) {
            try {
                val dir = context.externalCacheDir ?: context.cacheDir
                val file = java.io.File(dir, "topludiziler_debug.txt")
                val apisList = APIHolder.apis
                val apisText = apisList.map { it.name }.toString()
                val details = "Request Name: ${request.name}\nRequest Data: ${request.data}\nAPIs count: ${apisList.size}\nAPIs: $apisText\n"
                file.writeText(details)
                Log.d("TopluDiziler", "Debug file written successfully: $details")
            } catch (e: Throwable) {
                Log.e("TopluDiziler", "Failed to write debug file: ${e.message}")
            }
        }
        return try {
            when (request.data) {
                "toplu-trend-filmler" -> {
                    getTmdbTrends("movie", page)
                }
                "toplu-trend-diziler" -> {
                    getTmdbTrends("tv", page)
                }
                "toplu-imdb-top250-filmler" -> {
                    getTmdbTopRated("movie", page)
                }
                "toplu-imdb-top250-diziler" -> {
                    getTmdbTopRated("tv", page)
                }

                "toplu-filmler" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Son Filmler", "${p.mainUrl}/filmler-1/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Popüler Filmler", "https://www.fullhdfilmizlesene.life/populer-filmler/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Son Filmler", "${p?.mainUrl ?: "https://kultfilmler.net"}/film-arsivi/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Son Filmler", "$sinewixMainUrl/public/api/genres/latestmovies/all/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Son Eklenen Filmler", "${p?.mainUrl ?: "https://dizifilmizle.to"}/api/movies?page=", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Son Filmler", "${p?.mainUrl ?: "https://www.filmizle.now"}/", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Son Filmler", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&page=", false))
                            }
                        ,
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Son Filmler", "film/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-kultfilmler" -> {
                    coroutineScope {
                        val p = APIHolder.getApiFromNameNull("Kült Filmler")
                        val response = try {
                            val mainUrlVal = p?.mainUrl ?: "https://kultfilmler.net"
                            p?.getMainPage(page, MainPageRequest("Kült Filmler", "$mainUrlVal/film-arsivi/", false))
                        } catch (e: Throwable) { null }

                        if (response == null) return@coroutineScope null

                        val transformed = response.items.firstOrNull()?.list?.map { item ->
                            async {
                                val title = item.name
                                val urlMap = mapOf("Kült Filmler" to item.url)
                                val serialized = writeJson(TopluResultData(title, urlMap))
                                var poster = getPosterFromSearchResponse(item)
                                val type = item.type ?: TvType.Movie
                                val scoreVal = getScoreFromSearchResponse(item)
                                newAnimeSearchResponse(title, serialized, type) {
                                    this.posterUrl = poster
                                    scoreVal?.let { this.score = it }
                                }
                            }
                        }?.awaitAll() ?: emptyList()

                        newHomePageResponse(request.name, transformed, response.hasNext)
                    }
                }
                "toplu-filmler-aksiyon" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Filmleri", "${p.mainUrl}/tur/aksiyon-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/aksiyon-filmleri/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Filmleri", "${p?.mainUrl ?: "https://kultfilmler.net"}/category/aksiyon-filmleri-izle/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Aksiyon Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/28/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/aksiyon", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/aksiyon", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=aksiyon&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Filmleri", "tur/aksiyon/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-komedi" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Komedi Filmleri", "${p.mainUrl}/tur/komedi-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Komedi Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/komedi-filmleri/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Komedi Filmleri", "${p?.mainUrl ?: "https://kultfilmler.net"}/category/komedi-filmleri-izle/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Komedi Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/35/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Komedi Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/komedi", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Komedi Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/komedi", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Komedi Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=komedi&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Komedi Filmleri", "tur/komedi/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-dram" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Dram Filmleri", "${p.mainUrl}/tur/dram-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Dram Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/dram-filmleri/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Dram Filmleri", "${p?.mainUrl ?: "https://kultfilmler.net"}/category/dram-filmleri-izle/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Dram Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/18/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Dram Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/dram", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Dram Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/dram", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Dram Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=dram&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Dram Filmleri", "tur/dram/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-korku" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Korku Filmleri", "${p.mainUrl}/tur/korku-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Korku Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/korku-filmleri/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Korku Filmleri", "${p?.mainUrl ?: "https://kultfilmler.net"}/category/korku-filmleri-izle/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Korku Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/27/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Korku Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/korku", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Korku Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/korku", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Korku Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=korku&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Korku Filmleri", "tur/korku/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-gerilim" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Gerilim Filmleri", "${p.mainUrl}/tur/gerilim-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Gerilim Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/gerilim-filmleri/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Gerilim Filmleri", "${p?.mainUrl ?: "https://kultfilmler.net"}/category/gerilim-filmleri-izle/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Gerilim Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/53/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Gerilim Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/gerilim", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Gerilim Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/gerilim", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Gerilim Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=gerilim&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Gerilim Filmleri", "tur/gerilim/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-bilimkurgu" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Filmleri", "${p.mainUrl}/tur/bilim-kurgu-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/bilim-kurgu-filmleri/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Filmleri", "${p?.mainUrl ?: "https://kultfilmler.net"}/category/bilim-kurgu-filmleri-izle/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Bilim Kurgu Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/878/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/bilim-kurgu", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/bilim-kurgu", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=bilim-kurgu&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Filmleri", "tur/bilim-kurgu/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-macera" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Macera Filmleri", "${p.mainUrl}/tur/macera-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Macera Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/macera-filmleri/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Macera Filmleri", "${p?.mainUrl ?: "https://kultfilmler.net"}/category/macera-filmleri-izle/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Macera Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/12/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Macera Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/macera", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Macera Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/macera", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Macera Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=macera&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Macera Filmleri", "tur/macera/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-romantik" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Romantik Filmleri", "${p.mainUrl}/tur/romantik-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Romantik Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/romantik-filmleri/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Romantik Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/10749/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Romantik Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/romantik", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Romantik Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/romantik", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Romantik Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=romantik&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Romantik Filmleri", "tur/romantik/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-tarih" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Tarih Filmleri", "${p.mainUrl}/tur/tarih-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Tarih Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/tarih-filmleri/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Tarih Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/36/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Tarih Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/tarih", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Tarih Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/tarih", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Tarih Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=tarih&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Tarih Filmleri", "tur/tarih/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-suc" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Suç Filmleri", "${p.mainUrl}/tur/suc-fm2/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Suç Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/suc-filmleri-full-izle/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Suç Filmleri", "${p?.mainUrl ?: "https://kultfilmler.net"}/category/suc-filmleri-izle/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Suç Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/80/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Suç Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/suc", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Suç Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/suc", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Suç Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=suc&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Suç Filmleri", "tur/suc/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-animasyon" -> {
                    coroutineScope {
                        val sineToken = try { withTimeoutOrNull(8000) { getSinewixToken() } } catch(e: Throwable) { null }
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Animasyon Filmleri", "${p.mainUrl}/tur/animasyon-fm1/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Animasyon Filmleri", "https://www.fullhdfilmizlesene.life/filmizle/animasyon-filmleri/", false))
                            },
                            "Kült Filmler" to suspend {
                                val p = APIHolder.getApiFromNameNull("Kült Filmler")
                                p?.getMainPage(page, MainPageRequest("Animasyon Filmleri", "${p?.mainUrl ?: "https://kultfilmler.net"}/category/animasyon-filmleri-izle/", false))
                            },
                            "Sinewix" to suspend {
                                if (sineToken != null) {
                                    val p = APIHolder.getApiFromNameNull("Sinewix")
                                    p?.getMainPage(page, MainPageRequest("Animasyon Filmleri", "$sinewixMainUrl/public/api/genres/movies/show/16/$sineToken", false))
                                } else null
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Animasyon Filmleri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/animasyon", false))
                            },
                            "FilmizleNow" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleNow")
                                p?.getMainPage(page, MainPageRequest("Animasyon Filmleri", "${p?.mainUrl ?: "https://www.filmizle.now"}/tur/animasyon", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Animasyon Filmleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&genres=animasyon&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Animasyon Filmleri", "tur/animasyon/page/", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-filmler-yerli" -> {
                    coroutineScope {
                        val providers = listOf(
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Yerli Filmler", "https://www.fullhdfilmizlesene.life/filmizle/yerli-filmler/", false))
                            },
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Yerli Filmler", "${p.mainUrl}/ulke/turkiye/sayfa/", false))
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Yerli Filmler", "${p?.mainUrl ?: "https://dizifilmizle.to"}/ulke/turkiye-hd", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Yerli Filmler", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=movie&countries=turkiye&page=", false))
                            })
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-cocuk" -> {
                    coroutineScope {
                        val providers = listOf(
                            "Canlı TV (Canlitv)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Canlı TV (Canlitv)")
                                p?.getMainPage(page, MainPageRequest("Çocuk Kanalları", "https://www.canlitv.diy/cocuk-kanallari", false))
                            },
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Animasyon Filmleri", "${p.mainUrl}/tur/animasyon-fm2/film/sayfa/", false))
                            },
                            "FullHDFilmizlesene" to suspend {
                                val p = APIHolder.getApiFromNameNull("FullHDFilmizlesene")
                                p?.getMainPage(page, MainPageRequest("Çizgi Filmler", "https://www.fullhdfilmizlesene.life/filmizle/cizgi-filmler/", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-canlitv-ulusal" -> {
                    coroutineScope {
                        val p = APIHolder.getApiFromNameNull("Canlı TV (Canlitv)")
                        val res = withTimeoutOrNull(8000) {
                            p?.getMainPage(page, MainPageRequest("Ulusal Kanallar", "https://www.canlitv.diy/genel-tv-kanallari", false))
                        }
                        res ?: newHomePageResponse(request.name, emptyList())
                    }
                }
                "toplu-canlitv-belgesel" -> {
                    coroutineScope {
                        val p = APIHolder.getApiFromNameNull("Canlı TV (Canlitv)")
                        val res = withTimeoutOrNull(8000) {
                            p?.getMainPage(page, MainPageRequest("Belgesel Kanalları", "https://www.canlitv.diy/belgesel-kanallari", false))
                        }
                        res ?: newHomePageResponse(request.name, emptyList())
                    }
                }
                "toplu-canlitv-yerel" -> {
                    coroutineScope {
                        val p = APIHolder.getApiFromNameNull("Canlı TV (Canlitv)")
                        val res = withTimeoutOrNull(8000) {
                            p?.getMainPage(page, MainPageRequest("Yerel Kanallar", "https://www.canlitv.diy/yerel-tv-kanallari", false))
                        }
                        res ?: newHomePageResponse(request.name, emptyList())
                    }
                }
                "toplu-canlitv-haber" -> {
                    coroutineScope {
                        val p = APIHolder.getApiFromNameNull("Canlı TV (Canlitv)")
                        val res = withTimeoutOrNull(8000) {
                            p?.getMainPage(page, MainPageRequest("Haber Kanalları", "https://www.canlitv.diy/haber-kanallari", false))
                        }
                        res ?: newHomePageResponse(request.name, emptyList())
                    }
                }
                "toplu-yerlidiziler" -> {
                    coroutineScope {
                        val providers = listOf(
                            "DDizi - Güncel" to suspend {
                                val p = APIHolder.getApiFromNameNull("DDizi")
                                p?.getMainPage(page, MainPageRequest("Güncel Yerli Diziler", "https://www.ddizi.im", false))
                            },
                            "DDizi - Eski" to suspend {
                                val p = APIHolder.getApiFromNameNull("DDizi")
                                p?.getMainPage(page, MainPageRequest("Eski Diziler", "https://www.ddizi.im/eski.diziler", false))
                            },
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Yerli Diziler", "${p?.mainUrl ?: "https://sezonlukdizi.com"}/diziler.asp?siralama_tipi=id&kat=2&s=", false))
                            },
                            "FilmizleCh" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleCh")
                                p?.getMainPage(page, MainPageRequest("Yerli Diziler", "${p?.mainUrl ?: "https://filmizlech.com"}/diziler?cats=yerli-dizi", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Yerli Diziler", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=tv&countries=turkiye&page=", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-animeler" -> {
                    coroutineScope {
                        val providers = listOf(
                            "AnimeciX" to suspend {
                                val p = APIHolder.getApiFromNameNull("AnimeciX")
                                p?.getMainPage(page, MainPageRequest("Son Eklenenler", if (p.mainUrl.isNotEmpty()) "${p.mainUrl}/secure/last-episodes" else "", false))
                            },
                            "OpenAnime" to suspend {
                                val p = APIHolder.getApiFromNameNull("OpenAnime")
                                p?.getMainPage(page, MainPageRequest("Popüler", "popular-series", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-animeler-aksiyon" -> {
                    coroutineScope {
                        val providers = listOf(
                            "AnimeciX" to suspend {
                                val p = APIHolder.getApiFromNameNull("AnimeciX")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Animeleri", "genre_Aksiyon", false))
                            },
                            "OpenAnime" to suspend {
                                val p = APIHolder.getApiFromNameNull("OpenAnime")
                                p?.getMainPage(page, MainPageRequest("Aksiyon & Macera Animeleri", "genre_Aksiyon & Macera", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-animeler-komedi" -> {
                    coroutineScope {
                        val providers = listOf(
                            "AnimeciX" to suspend {
                                val p = APIHolder.getApiFromNameNull("AnimeciX")
                                p?.getMainPage(page, MainPageRequest("Komedi Animeleri", "genre_Komedi", false))
                            },
                            "OpenAnime" to suspend {
                                val p = APIHolder.getApiFromNameNull("OpenAnime")
                                p?.getMainPage(page, MainPageRequest("Komedi Animeleri", "genre_Komedi", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Komedi Animeleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=anime&genres=komedi&page=", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-animeler-dram" -> {
                    coroutineScope {
                        val providers = listOf(
                            "AnimeciX" to suspend {
                                val p = APIHolder.getApiFromNameNull("AnimeciX")
                                p?.getMainPage(page, MainPageRequest("Dram Animeleri", "genre_Dram", false))
                            },
                            "OpenAnime" to suspend {
                                val p = APIHolder.getApiFromNameNull("OpenAnime")
                                p?.getMainPage(page, MainPageRequest("Dram Animeleri", "genre_Dram", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-animeler-bilimkurgu" -> {
                    coroutineScope {
                        val providers = listOf(
                            "AnimeciX" to suspend {
                                val p = APIHolder.getApiFromNameNull("AnimeciX")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu & Fantastik Animeler", "genre_Bilim Kurgu & Fantastik", false))
                            },
                            "OpenAnime" to suspend {
                                val p = APIHolder.getApiFromNameNull("OpenAnime")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu & Fantastik Animeler", "genre_Bilim Kurgu & Fantazi", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-animeler-romantik" -> {
                    coroutineScope {
                        val providers = listOf(
                            "AnimeciX" to suspend {
                                val p = APIHolder.getApiFromNameNull("AnimeciX")
                                p?.getMainPage(page, MainPageRequest("Romantik Animeler", "genre_Romantizm", false))
                            },
                            "OpenAnime" to suspend {
                                val p = APIHolder.getApiFromNameNull("OpenAnime")
                                p?.getMainPage(page, MainPageRequest("Romantik Animeler", "genre_Romantik", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-animeler-gizem" -> {
                    coroutineScope {
                        val providers = listOf(
                            "AnimeciX" to suspend {
                                val p = APIHolder.getApiFromNameNull("AnimeciX")
                                p?.getMainPage(page, MainPageRequest("Gizem Animeleri", "genre_Gizem", false))
                            },
                            "OpenAnime" to suspend {
                                val p = APIHolder.getApiFromNameNull("OpenAnime")
                                p?.getMainPage(page, MainPageRequest("Gizem Animeleri", "genre_Gizem", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Gizem Animeleri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=anime&genres=gizem&page=", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-diziler" -> {
                    coroutineScope {
                        val providers = listOf(
                            "Sinewix" to suspend {
                                val p = APIHolder.getApiFromNameNull("Sinewix")
                                p?.getMainPage(page, MainPageRequest("Yeni Bölümler", "${p.mainUrl}/yeni-bolumler", false))
                            },
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Son Eklenenler", "${p.mainUrl}/diziler.asp?siralama_tipi=id&s=", false))
                            },
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Son Diziler", "${p.mainUrl}/yabanci-dizi-izle-1/sayfa/", false))
                            },
                            "Dizipal" to suspend {
                                val p = APIHolder.getApiFromNameNull("Dizipal")
                                p?.getMainPage(page, MainPageRequest("Diziler", "${p.mainUrl}/diziler/", false))
                            },
                            "FilmizleCh" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleCh")
                                p?.getMainPage(page, MainPageRequest("Diziler", "${p.mainUrl}/diziler", false))
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Yabancı Diziler", "${p?.mainUrl ?: "https://dizifilmizle.to"}/yabanci-dizi-izle", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Son Diziler", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=tv&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Son Diziler", "dizi/page/", false))
                            },
                            "Diziroll" to suspend {
                                val p = APIHolder.getApiFromNameNull("Diziroll")
                                p?.getMainPage(page, MainPageRequest("Son Diziler", "${p?.mainUrl ?: "https://diziroll.club"}/diziler", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-diziler-aksiyon" -> {
                    coroutineScope {
                        val providers = listOf(
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Dizileri", "${p.mainUrl}/tur/aksiyon/diziler.asp", false))
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Dizileri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/aksiyon", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Dizileri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=tv&genres=aksiyon&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Dizileri", "tur/aksiyon/page/", false))
                            },
                            "Diziroll" to suspend {
                                val p = APIHolder.getApiFromNameNull("Diziroll")
                                p?.getMainPage(page, MainPageRequest("Aksiyon Dizileri", "${p?.mainUrl ?: "https://diziroll.club"}/kategori/aksiyon", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-diziler-bilimkurgu" -> {
                    coroutineScope {
                        val providers = listOf(
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Dizileri", "${p.mainUrl}/tur/bilim-kurgu/diziler.asp", false))
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Dizileri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/bilim-kurgu", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Dizileri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=tv&genres=bilim-kurgu&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Dizileri", "tur/bilim-kurgu/page/", false))
                            },
                            "Diziroll" to suspend {
                                val p = APIHolder.getApiFromNameNull("Diziroll")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu Dizileri", "${p?.mainUrl ?: "https://diziroll.club"}/kategori/bilim-kurgu", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-diziler-komedi" -> {
                    coroutineScope {
                        val providers = listOf(
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Komedi Dizileri", "${p.mainUrl}/tur/komedi/diziler.asp", false))
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Komedi Dizileri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/komedi", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Komedi Dizileri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=tv&genres=komedi&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Komedi Dizileri", "tur/komedi/page/", false))
                            },
                            "Diziroll" to suspend {
                                val p = APIHolder.getApiFromNameNull("Diziroll")
                                p?.getMainPage(page, MainPageRequest("Komedi Dizileri", "${p?.mainUrl ?: "https://diziroll.club"}/kategori/komedi", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-diziler-korku" -> {
                    coroutineScope {
                        val providers = listOf(
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Korku Dizileri", "${p.mainUrl}/tur/korku/diziler.asp", false))
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Korku Dizileri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/korku", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Korku Dizileri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=tv&genres=korku&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Korku Dizileri", "tur/korku/page/", false))
                            },
                            "Diziroll" to suspend {
                                val p = APIHolder.getApiFromNameNull("Diziroll")
                                p?.getMainPage(page, MainPageRequest("Korku Dizileri", "${p?.mainUrl ?: "https://diziroll.club"}/kategori/korku", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-diziler-dram" -> {
                    coroutineScope {
                        val providers = listOf(
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Dram Dizileri", "${p.mainUrl}/tur/dram/diziler.asp", false))
                            },
                            "DiziFilm" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziFilm")
                                p?.getMainPage(page, MainPageRequest("Dram Dizileri", "${p?.mainUrl ?: "https://dizifilmizle.to"}/tur/dram", false))
                            },
                            "DiziSol" to suspend {
                                val p = APIHolder.getApiFromNameNull("DiziSol")
                                p?.getMainPage(page, MainPageRequest("Dram Dizileri", "${p?.mainUrl ?: "https://dizisol.com"}/api/library/browse?type=tv&genres=dram&page=", false))
                            },
                            "SetFilmizle" to suspend {
                                val p = APIHolder.getApiFromNameNull("SetFilmizle")
                                p?.getMainPage(page, MainPageRequest("Dram Dizileri", "tur/dram/page/", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-belgeseller" -> {
                    coroutineScope {
                        val providers = listOf(
                            "DMAX" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("DMAX", "https://www.dmax.com.tr/kesfet?size=500", false))
                            },
                            "TLC" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("TLC", "https://www.tlctv.com.tr/kesfet?size=500", false))
                            },
                            "TRT Belgesel" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("TRT Belgesel", "https://belgeselx.com/belgeselkanali/trt-belgesel", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-aksiyon" -> {
                    coroutineScope {
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Aksiyon", "${p.mainUrl}/tur/aksiyon-fm1/dizi/sayfa/", false))
                            },
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Aksiyon", "${p.mainUrl}/diziler.asp?siralama_tipi=id&tur=aksiyon&s=", false))
                            },
                            "Dizipal" to suspend {
                                val p = APIHolder.getApiFromNameNull("Dizipal")
                                p?.getMainPage(page, MainPageRequest("Aksiyon", "${p.mainUrl}/kategori/aksiyon/", false))
                            },
                            "FilmizleCh" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleCh")
                                p?.getMainPage(page, MainPageRequest("Aksiyon", "${p.mainUrl}/diziler?cats=aksiyon", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-bilimkurgu" -> {
                    coroutineScope {
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu", "${p.mainUrl}/tur/bilim-kurgu-fm3/dizi/sayfa/", false))
                            },
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu", "${p.mainUrl}/diziler.asp?siralama_tipi=id&tur=bilimkurgu&s=", false))
                            },
                            "Dizipal" to suspend {
                                val p = APIHolder.getApiFromNameNull("Dizipal")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu", "${p.mainUrl}/kategori/bilim-kurgu/", false))
                            },
                            "FilmizleCh" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleCh")
                                p?.getMainPage(page, MainPageRequest("Bilim Kurgu", "${p.mainUrl}/diziler?cats=bilim-kurgu", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-korku" -> {
                    coroutineScope {
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Korku", "${p.mainUrl}/tur/korku-fm1/dizi/sayfa/", false))
                            },
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Korku", "${p.mainUrl}/diziler.asp?siralama_tipi=id&tur=korku&s=", false))
                            },
                            "Dizipal" to suspend {
                                val p = APIHolder.getApiFromNameNull("Dizipal")
                                p?.getMainPage(page, MainPageRequest("Korku", "${p.mainUrl}/kategori/korku/", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-gerilim" -> {
                    coroutineScope {
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Gerilim", "${p.mainUrl}/tur/gerilim-fm1/dizi/sayfa/", false))
                            },
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Gerilim", "${p.mainUrl}/diziler.asp?siralama_tipi=id&tur=gerilim&s=", false))
                            },
                            "Dizipal" to suspend {
                                val p = APIHolder.getApiFromNameNull("Dizipal")
                                p?.getMainPage(page, MainPageRequest("Gerilim", "${p.mainUrl}/kategori/gerilim/", false))
                            },
                            "FilmizleCh" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleCh")
                                p?.getMainPage(page, MainPageRequest("Gerilim", "${p.mainUrl}/diziler?cats=gizem", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-komedi" -> {
                    coroutineScope {
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Komedi", "${p.mainUrl}/tur/komedi-fm1/dizi/sayfa/", false))
                            },
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Komedi", "${p.mainUrl}/diziler.asp?siralama_tipi=id&tur=komedi&s=", false))
                            },
                            "Dizipal" to suspend {
                                val p = APIHolder.getApiFromNameNull("Dizipal")
                                p?.getMainPage(page, MainPageRequest("Komedi", "${p.mainUrl}/kategori/komedi/", false))
                            },
                            "FilmizleCh" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleCh")
                                p?.getMainPage(page, MainPageRequest("Komedi", "${p.mainUrl}/diziler?cats=komedi", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-macera" -> {
                    coroutineScope {
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Macera", "${p.mainUrl}/tur/macera-fm1/dizi/sayfa/", false))
                            },
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Macera", "${p.mainUrl}/diziler.asp?siralama_tipi=id&tur=macera&s=", false))
                            },
                            "Dizipal" to suspend {
                                val p = APIHolder.getApiFromNameNull("Dizipal")
                                p?.getMainPage(page, MainPageRequest("Macera", "${p.mainUrl}/kategori/macera/", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-dram" -> {
                    coroutineScope {
                        val providers = listOf(
                            "FilmMakinesi" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmMakinesi")
                                p?.getMainPage(page, MainPageRequest("Dram", "${p.mainUrl}/tur/dram-fm1/dizi/sayfa/", false))
                            },
                            "SezonlukDizi" to suspend {
                                val p = APIHolder.getApiFromNameNull("SezonlukDizi")
                                p?.getMainPage(page, MainPageRequest("Dram", "${p.mainUrl}/diziler.asp?siralama_tipi=id&tur=dram&s=", false))
                            },
                            "Dizipal" to suspend {
                                val p = APIHolder.getApiFromNameNull("Dizipal")
                                p?.getMainPage(page, MainPageRequest("Dram", "${p.mainUrl}/kategori/dram/", false))
                            },
                            "FilmizleCh" to suspend {
                                val p = APIHolder.getApiFromNameNull("FilmizleCh")
                                p?.getMainPage(page, MainPageRequest("Dram", "${p.mainUrl}/diziler?cats=drama", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-anime-top250" -> {
                    getTmdbTopAnime(page)
                }
                "toplu-belgeseller-dmax" -> {
                    coroutineScope {
                        val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                        val res = p?.getMainPage(page, MainPageRequest("DMAX: Öne Çıkanlar", "https://www.dmax.com.tr/kesfet?size=500", false))
                        transformHomePageResponse(res, "Belgesel & Yaşam (AIO)", request.name) ?: newHomePageResponse(request.name, emptyList(), false)
                    }
                }
                "toplu-belgeseller-tlc" -> {
                    coroutineScope {
                        val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                        val res = p?.getMainPage(page, MainPageRequest("TLC: Öne Çıkanlar", "https://www.tlctv.com.tr/kesfet?size=500", false))
                        transformHomePageResponse(res, "Belgesel & Yaşam (AIO)", request.name) ?: newHomePageResponse(request.name, emptyList(), false)
                    }
                }
                "toplu-belgeseller-trt" -> {
                    coroutineScope {
                        val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                        val res = p?.getMainPage(page, MainPageRequest("TRT Belgesel", "https://belgeselx.com/belgeselkanali/trt-belgesel", false))
                        transformHomePageResponse(res, "Belgesel & Yaşam (AIO)", request.name) ?: newHomePageResponse(request.name, emptyList(), false)
                    }
                }
                "toplu-belgeseller-doga" -> {
                    coroutineScope {
                        val providers = listOf(
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("DMAX Doğa", "https://www.dmax.com.tr/kesfet/dogayla-ic-ice?size=500", false))
                            },
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("BelgeselX Doğa", "https://belgeselx.com/konu/doga-belgeselleri", false))
                            },
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("BelgeselX Vahşi Yaşam", "https://belgeselx.com/konu/hayvan-belgeselleri", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-belgeseller-tarih" -> {
                    coroutineScope {
                        val providers = listOf(
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("BelgeselX Tarih", "https://belgeselx.com/konu/tarih-belgeselleri", false))
                            },
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("BelgeselX Türk Tarihi", "https://belgeselx.com/konu/turk-tarihi-belgeselleri", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-belgeseller-uzay" -> {
                    coroutineScope {
                        val providers = listOf(
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("DMAX Turbo", "https://www.dmax.com.tr/kesfet/turbo?size=500", false))
                            },
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("BelgeselX Bilim", "https://belgeselx.com/konu/bilim-belgeselleri", false))
                            },
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("BelgeselX Mühendislik", "https://belgeselx.com/konu/muhendislik-belgeselleri", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                "toplu-belgeseller-succrime" -> {
                    coroutineScope {
                        val providers = listOf(
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("TLC Suç", "https://www.tlctv.com.tr/kesfet/suc-arastirma?size=500", false))
                            },
                            "Belgesel & Yaşam (AIO)" to suspend {
                                val p = APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)")
                                p?.getMainPage(page, MainPageRequest("BelgeselX Polisiye", "https://belgeselx.com/konu/polisiye-belgeselleri", false))
                            }
                        )
                        val responses = getActiveProvidersForPage(providers, page).map { (name, fetch) ->
                            async {
                                try {
                                    name to withTimeoutOrNull(8000) { fetch() }
                                } catch (e: Throwable) {
                                    name to null
                                }
                            }
                        }.awaitAll()
                        mergeHomePages(responses, request.name)
                    }
                }
                else -> null
            }
        } catch (e: Throwable) {
            Log.e("TopluDiziler", "getMainPage failed: ${e.message}")
            null
        }
    }

    private fun transformHomePageResponse(response: HomePageResponse?, sourceProvider: String, sectionName: String): HomePageResponse? {
        if (response == null) return null
        val searchResponses = response.items.firstOrNull()?.list ?: emptyList()
        val transformed = searchResponses.map { item ->
            val title = item.name
            val urlMap = mapOf(sourceProvider to item.url)
            val serialized = writeJson(TopluResultData(title, urlMap))
            val poster = getPosterFromSearchResponse(item)
            val type = item.type ?: TvType.TvSeries
            val scoreVal = getScoreFromSearchResponse(item)
            val dubs = getDubStatusFromSearchResponse(item)
            val isDub = dubs.contains(DubStatus.Dubbed)
            val isSub = dubs.contains(DubStatus.Subbed)

            newAnimeSearchResponse(title, serialized, type) {
                this.posterUrl = poster
                scoreVal?.let { this.score = it }
                if (isDub) addDub(1)
                if (isSub) addSub(1)
            }
        }
        return newHomePageResponse(sectionName, transformed, response.hasNext)
    }

    private suspend fun mergeHomePages(responses: List<Pair<String, HomePageResponse?>>, sectionName: String): HomePageResponse = coroutineScope {
        val allResults = mutableListOf<Pair<String, SearchResponse>>()
        var hasNextPage = false

        responses.forEach { (providerName, response) ->
            if (response != null) {
                if (response.hasNext) {
                    hasNextPage = true
                }
                response.items.firstOrNull()?.list?.forEach { item ->
                    // Skip items with blank names
                    if (item.name.isNotBlank()) {
                        allResults.add(providerName to item)
                    }
                }
            }
        }

        // Group by normalized title, skipping blank normalized keys
        val grouped = allResults.groupBy { normalizeTitle(it.second.name) }
            .filter { (key, _) -> key.isNotBlank() }

        val priorityMap = mapOf(
            "Sinewix" to 12,
            "AnimeciX" to 11,
            "SezonlukDizi" to 10,
            "FilmMakinesi" to 9,
            "FullHDFilmizlesene" to 8,
            "FilmizleCh" to 7,
            "Dizipal" to 6,
            "Kült Filmler" to 5,
            "DDizi" to 4,
            "OpenAnime" to 3,
            "Belgesel & Yaşam (AIO)" to 2,
            "Canlı TV (Canlitv)" to 1
        )

        val mergedResults = grouped.map { (_, items) ->
            async {
                if (items.isEmpty()) return@async null

                val sortedItems = items.sortedWith(compareByDescending<Pair<String, SearchResponse>> {
                    priorityMap[it.first] ?: 0
                }.thenByDescending {
                    getPosterFromSearchResponse(it.second)?.isNotEmpty() ?: false
                })

                val bestItem = sortedItems.first().second
                val title = bestItem.name

                // Prioritize FilmizleCh poster if it exists in the merged list to avoid extra network requests
                var poster = items.firstOrNull { it.first == "FilmizleCh" }?.let { getPosterFromSearchResponse(it.second) }
                if (poster.isNullOrEmpty()) {
                    poster = getBestPoster(items) ?: getPosterFromSearchResponse(bestItem)
                }

                val urlMap = items.associate { (pName, searchRes) ->
                    val canonicalName = if (pName.startsWith("DMAX") || pName.startsWith("TLC") || pName.startsWith("TRT") || pName.startsWith("Belgesel")) {
                        "Belgesel & Yaşam (AIO)"
                    } else pName
                    canonicalName to searchRes.url
                }
                val serializedData = writeJson(TopluResultData(title, urlMap))

                val scoreVal = sortedItems.firstNotNullOfOrNull { getScoreFromSearchResponse(it.second) }
                val isDub = sortedItems.any { getDubStatusFromSearchResponse(it.second).contains(DubStatus.Dubbed) }
                val isSub = sortedItems.any { getDubStatusFromSearchResponse(it.second).contains(DubStatus.Subbed) }
                val finalType = bestItem.type ?: TvType.TvSeries

                if (finalType == TvType.Live) {
                    newLiveSearchResponse(title, serializedData, finalType) {
                        this.posterUrl = poster
                    }
                } else {
                    newAnimeSearchResponse(title, serializedData, finalType) {
                        this.posterUrl = poster
                        scoreVal?.let { this.score = it }
                        if (isDub) addDub(1)
                        if (isSub) addSub(1)
                    }
                }
            }
        }.awaitAll().filterNotNull()

        // Put items with posters first
        val sortedResults = mergedResults.sortedWith(compareByDescending { getPosterFromSearchResponse(it) != null })
        val slicedResults = sortedResults.take(60)
        val hasNext = hasNextPage || mergedResults.size > 60
        newHomePageResponse(sectionName, slicedResults, hasNext)
    }


    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val providerNames = listOf(
            "SezonlukDizi", "DDizi", "FilmMakinesi", "FullHDFilmizlesene", "Sinewix", "Dizipal",
            "FilmizleCh", "Kült Filmler", "AnimeciX", "OpenAnime", "Belgesel & Yaşam (AIO)", "Canlı TV (Canlitv)",
            "DiziFilm", "FilmizleNow", "DiziSol", "SetFilmizle"
        )
        val searchProviders = providerNames.mapNotNull { APIHolder.getApiFromNameNull(it) }

        // Step 1: Query TMDB in both Turkish and English for canonical metadata
        val apiKey = "04c35731a5ee918f014970082a0088b1"
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        
        val trDeferred = async { 
            try {
                app.get("https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$encodedQuery&language=tr-TR", cacheTime = 86400)
            } catch(_: Throwable) { null }
        }
        val enDeferred = async {
            try {
                app.get("https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$encodedQuery&language=en-US", cacheTime = 86400)
            } catch(_: Throwable) { null }
        }
        
        val trRes = trDeferred.await()
        val enRes = enDeferred.await()
        
        val tmdbItems = mutableListOf<TmdbItem>()
        if (trRes != null && trRes.isSuccessful) {
            val trRoot = JSONObject(trRes.text)
            val trResults = trRoot.optJSONArray("results")
            val enResults = if (enRes != null && enRes.isSuccessful) {
                JSONObject(enRes.text).optJSONArray("results")
            } else null
            
            if (trResults != null) {
                val limit = minOf(trResults.length(), 6)
                for (i in 0 until limit) {
                    val trItem = trResults.getJSONObject(i)
                    val mediaType = trItem.optString("media_type", "")
                    if (mediaType != "movie" && mediaType != "tv") continue
                    
                    val id = trItem.optInt("id", 0)
                    if (id == 0) continue
                    
                    val trTitle = trItem.optString("title", trItem.optString("name", "")).trim()
                    val trOriginal = trItem.optString("original_title", trItem.optString("original_name", "")).trim()
                    
                    var enTitle = trTitle
                    if (enResults != null) {
                        for (j in 0 until enResults.length()) {
                            val enItem = enResults.getJSONObject(j)
                            if (enItem.optInt("id", 0) == id) {
                                enTitle = enItem.optString("title", enItem.optString("name", "")).trim()
                                break
                            }
                        }
                    }
                    
                    val releaseDate = trItem.optString("release_date", trItem.optString("first_air_date", ""))
                    val year = releaseDate.split("-").firstOrNull()?.toIntOrNull()
                    val type = if (mediaType == "movie") TvType.Movie else TvType.TvSeries
                    val posterPath = trItem.optString("poster_path", "").takeIf { it.isNotEmpty() && it != "null" }
                    val voteAverage = trItem.optDouble("vote_average", 0.0)
                    
                    tmdbItems.add(TmdbItem(id, trTitle, trOriginal, enTitle, year, type, posterPath, voteAverage))
                }
            }
        }

        // Helper function to extract year from provider titles
        val getYearFromTitle = { t: String ->
            Regex("""\b(19\d\d|20\d\d)\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        }

        // Match provider result to TMDB item
        val matchTmdb = { resName: String, resType: TvType ->
            val resYear = getYearFromTitle(resName)
            tmdbItems.firstOrNull { tmdb ->
                val typeMatch = (resType == tmdb.type) || (resType == TvType.TvSeries && tmdb.type == TvType.TvSeries) || (resType == TvType.Anime && tmdb.type == TvType.TvSeries)
                if (!typeMatch) return@firstOrNull false
                
                val nameMatch = isTitleMatch(resName, tmdb.title) || 
                                isTitleMatch(resName, tmdb.originalTitle) || 
                                isTitleMatch(resName, tmdb.enTitle)
                
                if (nameMatch) {
                    if (resYear != null && tmdb.year != null && resYear != tmdb.year) {
                        false
                    } else {
                        true
                    }
                } else false
            }
        }

        // Step 2: Query all providers in parallel
        val allResults = mutableListOf<Pair<String, SearchResponse>>()
        val trTitle = tmdbItems.firstOrNull()?.title
        val jobs = searchProviders.map { provider ->
            async {
                try {
                    withTimeoutOrNull(8000) {
                        val trProviders = setOf("DDizi", "DiziFilm", "FilmizleNow")
                        val searchQuery = if (trProviders.contains(provider.name) && trTitle != null && trTitle.isNotEmpty() && trTitle.lowercase().trim() != query.lowercase().trim()) {
                            trTitle
                        } else {
                            query
                        }
                        val resList = provider.search(searchQuery)
                        resList?.forEach { res ->
                            synchronized(allResults) {
                                allResults.add(provider.name to res)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("TopluDiziler", "Search failed for ${provider.name}: ${e.message}")
                }
            }
        }
        jobs.awaitAll()

        // Step 3: Group by TMDB canonical ID or fallback to normalized title
        val grouped = allResults.groupBy { item ->
            val matched = matchTmdb(item.second.name, item.second.type ?: TvType.TvSeries)
            if (matched != null) {
                "TMDB_${matched.id}"
            } else {
                "NORMAL_${normalizeTitle(item.second.name)}"
            }
        }

        // Step 4: Merge results in each group
        val mergedResults = grouped.map { (key, items) ->
            async {
                if (items.isEmpty()) return@async null
                
                val isTmdb = key.startsWith("TMDB_")
                val tmdbId = if (isTmdb) key.substringAfter("TMDB_").toIntOrNull() else null
                val tmdb = tmdbItems.find { it.id == tmdbId }

                val priorityMap = mapOf(
                    "Sinewix" to 12,
                    "AnimeciX" to 11,
                    "SezonlukDizi" to 10,
                    "FilmMakinesi" to 9,
                    "FullHDFilmizlesene" to 8,
                    "FilmizleCh" to 7,
                    "Dizipal" to 6,
                    "Kült Filmler" to 5,
                    "DDizi" to 4,
                    "OpenAnime" to 3,
                    "DiziFilm" to 3,
                    "FilmizleNow" to 3,
                    "DiziSol" to 2,
                    "SetFilmizle" to 8,
                    "Belgesel & Yaşam (AIO)" to 2,
                    "Canlı TV (Canlitv)" to 1
                )

                val sortedItems = items.sortedWith(compareByDescending<Pair<String, SearchResponse>> {
                    priorityMap[it.first] ?: 0
                }.thenByDescending {
                    getPosterFromSearchResponse(it.second)?.isNotEmpty() ?: false
                })

                val bestItem = sortedItems.first().second
                
                val title = if (isTmdb && tmdb != null) {
                    val orig = tmdb.originalTitle
                    if (orig.isNotEmpty() && orig != tmdb.title) {
                        "${tmdb.title} | $orig"
                    } else {
                        tmdb.title
                    }
                } else {
                    bestItem.name
                }
                
                val poster = if (isTmdb && tmdb?.posterPath != null) {
                    "https://image.tmdb.org/t/p/w185${tmdb.posterPath}"
                } else {
                    getBestPoster(items) ?: getPosterFromSearchResponse(bestItem)
                }

                val urlMap = items.associate { it.first to it.second.url }
                val serializedData = writeJson(TopluResultData(title, urlMap))

                val scoreVal = if (isTmdb && tmdb != null && tmdb.voteAverage > 0.0) {
                    Score.from10(tmdb.voteAverage)
                } else {
                    sortedItems.firstNotNullOfOrNull { getScoreFromSearchResponse(it.second) }
                }
                val isDub = sortedItems.any { getDubStatusFromSearchResponse(it.second).contains(DubStatus.Dubbed) }
                val isSub = sortedItems.any { getDubStatusFromSearchResponse(it.second).contains(DubStatus.Subbed) }
                val finalType = if (isTmdb && tmdb != null) {
                    tmdb.type
                } else {
                    bestItem.type ?: TvType.TvSeries
                }

                if (finalType == TvType.Live) {
                    newLiveSearchResponse(title, serializedData, finalType) {
                        this.posterUrl = poster
                    }
                } else {
                    newAnimeSearchResponse(title, serializedData, finalType) {
                        this.posterUrl = poster
                        scoreVal?.let { this.score = it }
                        if (isDub) addDub(1)
                        if (isSub) addSub(1)
                    }
                }
            }
        }.awaitAll().filterNotNull()

        val cleanQuery = query.lowercase().trim()
        val sortedMergedResults = mergedResults.sortedWith(compareByDescending<SearchResponse> { item ->
            val cleanName = item.name.lowercase().trim()
            if (cleanName == cleanQuery) {
                1000
            } else if (cleanName.startsWith(cleanQuery)) {
                500 + (cleanQuery.length * 100 / cleanName.length)
            } else if (cleanName.contains(cleanQuery)) {
                200 + (cleanQuery.length * 100 / cleanName.length)
            } else {
                val queryWords = cleanQuery.split(Regex("""\s+""")).filter { it.length > 1 }
                if (queryWords.isNotEmpty()) {
                    val matchingWords = queryWords.count { cleanName.contains(it) }
                    matchingWords * 10
                } else {
                    0
                }
            }
        })

        sortedMergedResults
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        Log.d("TopluDiziler", "=== LOAD START === Url: $url")
        if (url == "https://cagi-topludiziler.com/open-settings") {
            val context = TopluDizilerPlugin.pluginContext ?: TopluDizilerPlugin.currentActivity
            if (context != null) {
                TopluDizilerPlugin.showSettingsDialog(context)
            }
            return@coroutineScope newMovieLoadResponse("VizyonHub Tür Ayarları", url, TvType.Movie, url) {
                this.plot = "İçerik türü seçim penceresi açıldı. Seçiminizi yaptıktan sonra sayfayı yenileyiniz."
                this.posterUrl = "https://cdn-icons-png.flaticon.com/512/3524/3524659.png"
            }
        }
        val logBuilder = java.lang.StringBuilder()
        logBuilder.append("EKLENTİ TARAMA GÜNLÜĞÜ (LOG):\n")
        logBuilder.append("Başlangıç Url: $url\n")

        val resultData = try {
            if (url.trim().startsWith("{")) {
                val parsed = readJson<TopluResultData>(url)
                logBuilder.append("Ana Başlık: '${parsed?.title}'\n")
                logBuilder.append("Başlangıç Kaynakları: ${parsed?.urls?.keys}\n")
                if (parsed != null) {
                    if (parsed.urls.containsKey("Canlı TV (Canlitv)")) {
                        logBuilder.append("Canlı TV algılandı. Arama atlanıyor.\n")
                        TopluResultData(parsed.title, parsed.urls)
                    } else {
                        val providerNames = listOf(
                            "SezonlukDizi", "DDizi", "FilmMakinesi", "FullHDFilmizlesene", "Sinewix", "Dizipal",
                            "FilmizleCh", "Kült Filmler", "AnimeciX", "OpenAnime", "Belgesel & Yaşam (AIO)",
                            "DiziFilm", "FilmizleNow", "DiziSol"
                        )
                        val missingProviders = providerNames.filter { !parsed.urls.containsKey(it) }
                        val searchProviders = missingProviders.mapNotNull { APIHolder.getApiFromNameNull(it) }
                        val urlMap = parsed.urls.toMutableMap()

                        logBuilder.append("Aranacak Siteler: ${missingProviders}\n")
                        logBuilder.append("Aktif Yüklü Siteler: ${searchProviders.map { it.name }}\n")

                        searchProviders.map { provider ->
                            async {
                                try {
                                    val titleParts = parsed.title.split("|").map { it.trim() }
                                    var match: SearchResponse? = null
                                    for (part in titleParts) {
                                        val cleanQuery = cleanSearchQuery(part)
                                        logBuilder.append("[Taranıyor] ${provider.name} | Kelime: '$cleanQuery'\n")
                                        val searchRes = withTimeoutOrNull(6000) { provider.search(cleanQuery) }
                                        logBuilder.append("  ↳ ${provider.name}: ${searchRes?.size ?: 0} sonuç bulundu.\n")
                                        
                                        searchRes?.forEach { res ->
                                            logBuilder.append("    - Sonuç Başlığı: '${res.name}'\n")
                                        }

                                        match = searchRes?.firstOrNull { 
                                            val matches = isTitleMatch(it.name, parsed.title)
                                            logBuilder.append("    [Eşleştirme] '${it.name}' <-> '${parsed.title}' | Sonuç: $matches\n")
                                            matches
                                        }
                                        if (match != null) break
                                    }
                                    if (match != null) {
                                        synchronized(urlMap) {
                                            urlMap[provider.name] = match.url
                                        }
                                        logBuilder.append("  >>> Eşleşme BAŞARILI! URL eklendi.\n")
                                    } else {
                                        logBuilder.append("  >>> Eşleşen başlık bulunamadı.\n")
                                    }
                                } catch (e: Throwable) {
                                    logBuilder.append("  >>> ${provider.name} Aramasında Hata: ${e.toString()}\n")
                                }
                            }
                        }.awaitAll()
                        TopluResultData(parsed.title, urlMap)
                    }
                } else null
            } else {
                val primaryProvider = detectProvider(url)
                logBuilder.append("Düz URL Yüklemesi. Algılanan eklenti: ${primaryProvider?.name}\n")
                if (primaryProvider != null) {
                    try {
                        val primaryLoad = primaryProvider.load(url)
                        val title = primaryLoad?.name
                        logBuilder.append("Yapım Başlığı: '$title'\n")
                        if (title != null) {
                            if (primaryProvider.name == "Canlı TV (Canlitv)") {
                                logBuilder.append("Canlı TV algılandı. Arama atlanıyor.\n")
                                TopluResultData(title, mapOf(primaryProvider.name to url))
                            } else {
                                val providerNames = listOf(
                                    "SezonlukDizi", "DDizi", "FilmMakinesi", "FullHDFilmizlesene", "Sinewix", "Dizipal",
                                    "FilmizleCh", "Kült Filmler", "AnimeciX", "OpenAnime", "Belgesel & Yaşam (AIO)",
                                    "DiziFilm", "FilmizleNow", "DiziSol", "SetFilmizle"
                                )
                                val searchProviders = providerNames.mapNotNull { APIHolder.getApiFromNameNull(it) }
                                val urlMap = mutableMapOf<String, String>()
                                urlMap[primaryProvider.name] = url

                                val alternativeTitles = getTmdbAlternativeTitles(title)

                                searchProviders.filter { it.name != primaryProvider.name }.map { provider ->
                                    async {
                                        try {
                                            var match: SearchResponse? = null
                                            for (part in alternativeTitles) {
                                                val cleanQuery = cleanSearchQuery(part)
                                                val searchRes = withTimeoutOrNull(8000) { provider.search(cleanQuery) }
                                                match = searchRes?.firstOrNull { 
                                                    isTitleMatch(it.name, title) || alternativeTitles.any { alt -> isTitleMatch(it.name, alt) }
                                                }
                                                if (match != null) break
                                            }
                                            if (match != null) {
                                                synchronized(urlMap) {
                                                    urlMap[provider.name] = match.url
                                                }
                                            }
                                        } catch (e: Throwable) {
                                            // Ignore
                                        }
                                    }
                                }.awaitAll()
                                TopluResultData(title, urlMap)
                            }
                        } else null
                    } catch (e: Throwable) {
                        null
                    }
                } else null
            }
        } catch (globalErr: Throwable) {
            logBuilder.append("Kritik Hata: ${globalErr.toString()}\n")
            null
        }

        if (resultData == null) {
            logBuilder.append("ResultData null döndü, yükleme sonlandırılıyor.\n")
            // Write debug file anyway
            val context = TopluDizilerPlugin.pluginContext
            if (context != null) {
                try {
                    val dir = context.externalCacheDir ?: context.cacheDir
                    val file = java.io.File(dir, "topludiziler_load_debug.txt")
                    file.writeText(logBuilder.toString())
                } catch (_: Throwable) {}
            }
            return@coroutineScope null
        }

        Log.d("TopluDiziler", "Final urlMap resolved: ${resultData.urls}")
        logBuilder.append("Nihai Kaynak URL Haritası: ${resultData.urls}\n")

        val loadedResponses = mutableListOf<Pair<String, LoadResponse>>()

        val providersMap = mapOf(
            "SezonlukDizi" to { APIHolder.getApiFromNameNull("SezonlukDizi") },
            "DDizi" to { APIHolder.getApiFromNameNull("DDizi") },
            "FilmMakinesi" to { APIHolder.getApiFromNameNull("FilmMakinesi") },
            "FullHDFilmizlesene" to { APIHolder.getApiFromNameNull("FullHDFilmizlesene") },
            "Sinewix" to { APIHolder.getApiFromNameNull("Sinewix") },
            "Dizipal" to { APIHolder.getApiFromNameNull("Dizipal") },
            "FilmizleCh" to { APIHolder.getApiFromNameNull("FilmizleCh") },
            "Kült Filmler" to { APIHolder.getApiFromNameNull("Kült Filmler") },
            "AnimeciX" to { APIHolder.getApiFromNameNull("AnimeciX") },
            "OpenAnime" to { APIHolder.getApiFromNameNull("OpenAnime") },
            "Belgesel & Yaşam (AIO)" to { APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)") },
            "Canlı TV (Canlitv)" to { APIHolder.getApiFromNameNull("Canlı TV (Canlitv)") },
            "DiziFilm" to { APIHolder.getApiFromNameNull("DiziFilm") },
            "FilmizleNow" to { APIHolder.getApiFromNameNull("FilmizleNow") },
            "DiziSol" to { APIHolder.getApiFromNameNull("DiziSol") },
            "SetFilmizle" to { APIHolder.getApiFromNameNull("SetFilmizle") }
        )

        val jobs = resultData.urls.map { (providerName, providerUrl) ->
            async {
                try {
                    val provider = providersMap[providerName]?.invoke()
                    if (provider != null) {
                        Log.d("TopluDiziler", "Loading details from $providerName using URL: $providerUrl")
                        logBuilder.append("[$providerName] Detaylar çekiliyor: $providerUrl\n")
                        val res = withTimeoutOrNull(8000) { provider.load(providerUrl) }
                        if (res != null) {
                            synchronized(loadedResponses) {
                                loadedResponses.add(providerName to res)
                            }
                            Log.d("TopluDiziler", "Successfully loaded $providerName details")
                            logBuilder.append("[$providerName] Başarıyla çekildi.\n")
                        } else {
                            Log.d("TopluDiziler", "$providerName details returned null")
                            logBuilder.append("[$providerName] Detay çekimi boş döndü.\n")
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("TopluDiziler", "Load failed for $providerName: ${e.toString()}")
                    logBuilder.append("[$providerName] Detay çekiminde hata: ${e.toString()}\n")
                }
            }
        }
        jobs.awaitAll()

        Log.d("TopluDiziler", "Successfully gathered responses from: ${loadedResponses.map { it.first }}")

        // Write debug file
        val context = TopluDizilerPlugin.pluginContext
        if (context != null) {
            try {
                val dir = context.externalCacheDir ?: context.cacheDir
                val file = java.io.File(dir, "topludiziler_load_debug.txt")
                file.writeText(logBuilder.toString())
            } catch (_: Throwable) {}
        }

        if (loadedResponses.isEmpty()) return@coroutineScope null

        val priorityMap = mapOf(
            "Sinewix" to 11,
            "AnimeciX" to 10,
            "SezonlukDizi" to 9,
            "FilmMakinesi" to 8,
            "FilmizleCh" to 7,
            "Dizipal" to 6,
            "Kült Filmler" to 5,
            "DDizi" to 4,
            "OpenAnime" to 3,
            "DiziFilm" to 3,
            "FilmizleNow" to 3,
            "DiziSol" to 2,
            "Belgesel & Yaşam (AIO)" to 2,
            "Canlı TV (Canlitv)" to 1
        )

        val sortedResponses = loadedResponses.sortedByDescending { priorityMap[it.first] ?: 0 }

        val bestResponse = sortedResponses.first().second
        val title = bestResponse.name

        val (swPoster, swPlot, swYear) = getSinewixMetadata(title)

        var poster = swPoster ?: getTmdbPoster(title)
        if (poster.isNullOrEmpty()) {
            poster = sortedResponses.firstNotNullOfOrNull { it.second.posterUrl?.takeIf { p -> p.isNotEmpty() } } ?: bestResponse.posterUrl
        }
        val plot = swPlot ?: sortedResponses.firstNotNullOfOrNull { it.second.plot?.takeIf { p -> p.isNotEmpty() } } ?: bestResponse.plot

        val year = swYear ?: sortedResponses.firstNotNullOfOrNull { it.second.year }
        val duration = sortedResponses.firstNotNullOfOrNull { it.second.duration?.takeIf { d -> d > 0 } }

        if (bestResponse is LiveStreamLoadResponse || bestResponse.type == TvType.Live) {
            val liveUrls = sortedResponses.mapNotNull {
                val providerName = it.first
                val providerUrl = (it.second as? LiveStreamLoadResponse)?.dataUrl ?: return@mapNotNull null
                providerName to providerUrl
            }.toMap()
            val serializedLive = writeJson(TopluEpisodeData(1, 1, liveUrls))
            return@coroutineScope newLiveStreamLoadResponse(title, url, serializedLive) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        if (bestResponse is MovieLoadResponse || bestResponse.type == TvType.Movie) {
            val movieUrls = sortedResponses.mapNotNull {
                val providerName = it.first
                val providerUrl = (it.second as? MovieLoadResponse)?.dataUrl ?: return@mapNotNull null
                providerName to providerUrl
            }.toMap()
            val serializedMovie = writeJson(TopluEpisodeData(1, 1, movieUrls))
            return@coroutineScope newMovieLoadResponse(title, url, bestResponse.type, serializedMovie) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
            }
        }


        val tags = loadedResponses.flatMap { it.second.tags ?: emptyList() }.distinct()
        val actors = loadedResponses.flatMap { getActorsFromLoadResponse(it.second) }.distinctBy { it.name.lowercase() }
        val recommendations = loadedResponses.flatMap { it.second.recommendations ?: emptyList() }.distinctBy { normalizeTitle(it.name) }

        val episodesMap = mutableMapOf<EpKey, MutableMap<String, String>>()
        val episodeMetadata = mutableMapOf<EpKey, Episode>()

        loadedResponses.forEach { (providerName, res) ->
            val epsMap = getEpisodesFromLoadResponse(res)
            epsMap.values.flatten().forEach { ep ->
                val s = ep.season ?: 1
                val e = ep.episode ?: 1
                val key = EpKey(s, e)
                val map = episodesMap.getOrPut(key) { mutableMapOf() }
                map[providerName] = ep.data

                val existing = episodeMetadata[key]
                if (existing == null || (existing.name.isNullOrBlank() && !ep.name.isNullOrBlank())) {
                    episodeMetadata[key] = ep
                }
            }
        }

        val mergedEpisodes = episodesMap.map { (key, providerUrls) ->
            val meta = episodeMetadata[key]
            val nameVal = meta?.name ?: "Bölüm ${key.episode}"
            val posterVal = meta?.posterUrl
            val descVal = meta?.description

            val serializedData = writeJson(TopluEpisodeData(key.season, key.episode, providerUrls))

            newEpisode(serializedData) {
                this.name = nameVal
                this.season = key.season
                this.episode = key.episode
                this.posterUrl = posterVal
                this.description = descVal
            }
        }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 })

        Log.d("TopluDiziler", "Merged ${mergedEpisodes.size} episodes")

        if (bestResponse is AnimeLoadResponse || sortedResponses.any { it.second is AnimeLoadResponse }) {
            newAnimeLoadResponse(title, url, bestResponse.type) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.tags = tags
                addActors(actors)
                addEpisodes(DubStatus.Subbed, mergedEpisodes)
            }
        } else {
            newTvSeriesLoadResponse(title, url, bestResponse.type, mergedEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.tags = tags
                addActors(actors)
            }
        }
    }

    private fun getEpisodesFromLoadResponse(res: LoadResponse): Map<DubStatus, List<Episode>> {
        val map = mutableMapOf<DubStatus, List<Episode>>()
        when (res) {
            is TvSeriesLoadResponse -> {
                map[DubStatus.Subbed] = res.episodes
            }
            is AnimeLoadResponse -> {
                res.episodes.forEach { (status, list) ->
                    map[status] = list
                }
            }
        }
        return map
    }

    private fun getActorsFromLoadResponse(res: LoadResponse): List<Actor> {
        val rawActors = when (res) {
            is TvSeriesLoadResponse -> res.actors
            is AnimeLoadResponse -> res.actors
            is MovieLoadResponse -> res.actors
            else -> null
        }
        return rawActors?.filterIsInstance<Actor>() ?: emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val epData = readJson<TopluEpisodeData>(data) ?: return@coroutineScope false

        val providersMap = mapOf(
            "SezonlukDizi" to { APIHolder.getApiFromNameNull("SezonlukDizi") },
            "DDizi" to { APIHolder.getApiFromNameNull("DDizi") },
            "FilmMakinesi" to { APIHolder.getApiFromNameNull("FilmMakinesi") },
            "FullHDFilmizlesene" to { APIHolder.getApiFromNameNull("FullHDFilmizlesene") },
            "Sinewix" to { APIHolder.getApiFromNameNull("Sinewix") },
            "Dizipal" to { APIHolder.getApiFromNameNull("Dizipal") },
            "FilmizleCh" to { APIHolder.getApiFromNameNull("FilmizleCh") },
            "Kült Filmler" to { APIHolder.getApiFromNameNull("Kült Filmler") },
            "AnimeciX" to { APIHolder.getApiFromNameNull("AnimeciX") },
            "OpenAnime" to { APIHolder.getApiFromNameNull("OpenAnime") },
            "Belgesel & Yaşam (AIO)" to { APIHolder.getApiFromNameNull("Belgesel & Yaşam (AIO)") },
            "Canlı TV (Canlitv)" to { APIHolder.getApiFromNameNull("Canlı TV (Canlitv)") },
            "DiziFilm" to { APIHolder.getApiFromNameNull("DiziFilm") },
            "FilmizleNow" to { APIHolder.getApiFromNameNull("FilmizleNow") },
            "DiziSol" to { APIHolder.getApiFromNameNull("DiziSol") },
            "SetFilmizle" to { APIHolder.getApiFromNameNull("SetFilmizle") }
        )

        val jobs = epData.urls.map { (providerName, providerEpData) ->
            async {
                try {
                    val provider = providersMap[providerName]?.invoke()
                    if (provider != null) {
                        provider.loadLinks(providerEpData, isCasting, subtitleCallback) { link ->
                            launch {
                                val modifiedLink = newExtractorLink(
                                    source = link.source,
                                    name = "[$providerName] ${link.name}",
                                    url = link.url,
                                    type = link.type
                                ) {
                                    this.quality = link.quality
                                    this.headers = link.headers
                                }
                                callback.invoke(modifiedLink)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("TopluDiziler", "loadLinks failed for $providerName: ${e.toString()}")
                }
            }
        }
        jobs.awaitAll()
        true
    }

    data class TmdbItem(
        val id: Int,
        val title: String,
        val originalTitle: String,
        val enTitle: String,
        val year: Int?,
        val type: TvType,
        val posterPath: String?,
        val voteAverage: Double
    )

    data class TopluResultData(
        val title: String,
        val urls: Map<String, String>
    )

    data class TopluEpisodeData(
        val season: Int,
        val episode: Int,
        val urls: Map<String, String>
    )

    data class EpKey(val season: Int, val episode: Int)

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbTrendingResult(
        val title: String? = null,
        val name: String? = null,
        val poster_path: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbTrendingResponse(
        val results: List<TmdbTrendingResult>? = null,
        val page: Int? = null,
        val total_pages: Int? = null
    )

    private fun findSwipeRefreshLayout(view: android.view.View): android.view.View? {
        if (view.javaClass.name.contains("SwipeRefreshLayout")) {
            return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                val found = findSwipeRefreshLayout(child)
                if (found != null) return found
            }
        }
        return null
    }

}
