package com.lagradost.cloudstream3.providers

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*

private inline fun <reified T> tryParseJson(json: String): T? {
    return try {
        mapper.readValue<T>(json)
    } catch (e: Exception) {
        null
    }
}

class CanlitvProvider : MainAPI() {
    override var mainUrl = "https://www.canlitv.diy"
    override var name = "Canlı TV (Canlitv)"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val imageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/genel-tv-kanallari" to "Ulusal Kanallar",
        "$mainUrl/haber-kanallari" to "Haber Kanalları",
        "$mainUrl/spor-kanallari" to "Spor Kanalları",
        "$mainUrl/belgesel-kanallari" to "Belgesel Kanalları",
        "$mainUrl/cocuk-kanallari" to "Çocuk Kanalları",
        "$mainUrl/dini-tv-kanallari" to "Dini Kanallar",
        "$mainUrl/yerel-tv-kanallari" to "Yerel Kanallar"
    )

    private val excludedChannels = setOf("tom and jerry", "tom and jery", "pijamaskeliler", "kral şakir", "kral sakir")

    private val turkeyLogos = setOf(
        "24", "360", "4-eylul", "a-haber", "a-news", "a-para", "a-spor", "a2", "ada-tv", "agro-tv", "akit-tv", "aksu-tv",
        "altas-tv", "anadolu-dernek", "atv-avrupa", "atv", "bahar-turk", "bbn-turk", "bengu-turk", "berat-tv", "beyaz-tv",
        "beykent-tv", "bizimev-tv", "bloomberg-ht", "brt-1", "brt-2", "brt-3", "brtv", "bursaspor-tv", "cay-tv", "cem-tv",
        "ciftci-tv", "cnbc-e", "cnn-turk", "cocuk-smart", "disney-channel", "diyanet-tv", "diyar-tv", "dost-tv", "dream-turk",
        "drt-denizli", "edessa-tv", "ege-tv", "ekoturk", "es-tv", "euro-d", "euro-star", "fenerbahce-tv", "flash-tv", "fm-tv",
        "fox", "galatasaray-tv", "gaziantep-olay-tv", "guneydogu-tv", "haber-global", "haberturk", "halk-tv", "hrt-akdeniz",
        "kadirga", "kanal-15", "kanal-16", "kanal-23", "kanal-26", "kanal-33", "kanal-42", "kanal-58", "kanal-7-avrupa",
        "kanal-7", "kanal-avrupa", "kanal-b", "kanal-d", "kanal-ege", "kanal-firat", "kanal-sim", "kanal-t", "kanal-urfa",
        "kanal-v", "kanal-z", "kardelen-tv", "kent-turk", "kocaeli-tv", "kon-tv", "koy-tv", "koza-tv", "kral-pop", "kral-tv",
        "krt", "lalegul-tv", "line-tv", "manisa-tv", "mavi-karadeniz", "mercan-tv", "minika-cocuk", "minika-go", "now",
        "ntv", "on4-tv", "pamukkale-tv", "planet-cocuk", "planet-pembe", "planet-sinema", "planet-turk", "power-tv",
        "powerturk", "rehber-tv", "rumeli-tv", "s-sport-2", "s-sport-plus", "s-sport", "semerkand-tv", "show-max", "show",
        "show-turk", "sports-tv", "star-tv", "tatlises-tv", "tay-tv", "tbmm-tv", "tek-rumeli", "tele1", "tempo-tv", "teve2",
        "tgrt-belgesel", "tgrt-eu", "tgrt-haber", "tjk-tv", "tmb", "toprak-tv", "trt-1", "trt-2", "trt-3-spor", "trt-4k",
        "trt-arabi", "trt-avaz", "trt-belgesel", "trt-cocuk", "trt-haber", "trt-kurdi", "trt-muzik", "trt-spor-2", "trt-spor",
        "trt-spor-yildiz", "trt-turk", "trt-world", "tv-den", "tv-kayseri", "tv1", "tv100", "tv2", "tv264", "tv4", "tv5",
        "tv52", "tv8-int", "tv8", "tv85", "tvnet", "ucankus", "ulke-tv", "ulusal-tv", "uzay-haber", "uzay-tv", "vatan-tv",
        "vav-tv", "vizyon-58", "vizyon-turk", "yaban"
    )

    private fun getChannelLogo(title: String, channelId: String): String {
        val cleanTitle = title.lowercase()
            .replace("ı", "i").replace("ş", "s").replace("ğ", "g")
            .replace("ç", "c").replace("ö", "o").replace("ü", "u")
            .replace(Regex("""[^a-z0-9]"""), "")

        if (cleanTitle == "dmax" || cleanTitle == "dmaxtv") return "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/germany/dmax-de.png"
        if (cleanTitle == "tlc" || cleanTitle == "tlctv") return "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/tlc-int.png"
        if (cleanTitle == "cartoonnetwork") return "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/cartoon-network-int.png"
        if (cleanTitle == "disneychannel" || cleanTitle == "disney") return "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/disney-channel-int.png"

        val customSlug = when {
            cleanTitle == "trt1" -> "trt-1"
            cleanTitle == "trt2" -> "trt-2"
            cleanTitle == "trt3" -> "trt-3"
            cleanTitle == "trthaber" -> "trt-haber"
            cleanTitle == "trtspor" || cleanTitle == "trtspor1" -> "trt-spor"
            cleanTitle == "trtsporyildiz" || cleanTitle == "trtspor2" -> "trt-spor-yildiz"
            cleanTitle == "trtbelgesel" -> "trt-belgesel"
            cleanTitle == "trtcocuk" -> "trt-cocuk"
            cleanTitle == "trtmuzik" -> "trt-muzik"
            cleanTitle == "trtavaz" -> "trt-avaz"
            cleanTitle == "trtkurdi" -> "trt-kurdi"
            cleanTitle == "trtarabi" -> "trt-arabi"
            cleanTitle == "trtworld" -> "trt-world"
            cleanTitle == "showtv" || cleanTitle == "show" -> "show"
            cleanTitle == "showmax" -> "show-max"
            cleanTitle == "showturk" -> "show-turk"
            cleanTitle == "kanald" -> "kanal-d"
            cleanTitle == "eurod" -> "euro-d"
            cleanTitle == "atv" -> "atv"
            cleanTitle == "atvavrupa" -> "atv-avrupa"
            cleanTitle == "a2" || cleanTitle == "a2tv" -> "a2"
            cleanTitle == "startv" || cleanTitle == "star" -> "star-tv"
            cleanTitle == "eurostar" -> "euro-star"
            cleanTitle == "tv8" -> "tv8"
            cleanTitle == "tv85" || cleanTitle == "tv8bucuk" -> "tv85"
            cleanTitle == "tv8int" -> "tv8-int"
            cleanTitle == "nowtv" || cleanTitle == "now" -> "now"
            cleanTitle == "haberturk" || cleanTitle == "haberturktv" -> "haberturk"
            cleanTitle == "ntv" -> "ntv"
            cleanTitle == "cnnturk" -> "cnn-turk"
            cleanTitle == "halktv" || cleanTitle == "halk" -> "halk-tv"
            cleanTitle == "beyaztv" -> "beyaz-tv"
            cleanTitle == "ahaber" -> "a-haber"
            cleanTitle == "aspor" -> "a-spor"
            cleanTitle == "apara" -> "a-para"
            cleanTitle == "anews" -> "a-news"
            cleanTitle == "tv100" -> "tv100"
            cleanTitle == "haberglobal" || cleanTitle == "global" -> "haber-global"
            cleanTitle == "ulketv" || cleanTitle == "ulke" -> "ulke-tv"
            cleanTitle == "kanal7" -> "kanal-7"
            cleanTitle == "kanal7avrupa" -> "kanal-7-avrupa"
            cleanTitle == "tgrthaber" -> "tgrt-haber"
            cleanTitle == "tgrtbelgesel" -> "tgrt-belgesel"
            cleanTitle == "tgrteu" -> "tgrt-eu"
            cleanTitle == "bloomberg" || cleanTitle == "bloomberght" -> "bloomberg-ht"
            cleanTitle == "teve2" -> "teve2"
            cleanTitle == "360" || cleanTitle == "360tv" -> "360"
            cleanTitle == "24tv" || cleanTitle == "24" -> "24"
            cleanTitle == "tele1" || cleanTitle == "tele1tv" -> "tele1"
            cleanTitle == "krt" || cleanTitle == "krttv" -> "krt"
            cleanTitle == "tvnet" -> "tvnet"
            cleanTitle == "flashtv" || cleanTitle == "flashhaber" -> "flash-tv"
            cleanTitle == "ulusalkanal" || cleanTitle == "ulusaltv" -> "ulusal-tv"
            cleanTitle == "benguturk" || cleanTitle == "benguturktv" -> "bengu-turk"
            cleanTitle == "cemtv" -> "cem-tv"
            cleanTitle == "diyanettv" || cleanTitle == "diyanet" -> "diyanet-tv"
            cleanTitle == "lalegultv" || cleanTitle == "lalegul" -> "lalegul-tv"
            cleanTitle == "semerkandtv" || cleanTitle == "semerkand" -> "semerkand-tv"
            cleanTitle == "vavtv" || cleanTitle == "vav" -> "vav-tv"
            cleanTitle == "ssport" || cleanTitle == "ssport1" -> "s-sport"
            cleanTitle == "ssport2" -> "s-sport-2"
            cleanTitle == "sportstv" -> "sports-tv"
            cleanTitle == "tjktv" || cleanTitle == "tjk" -> "tjk-tv"
            cleanTitle == "fbtv" || cleanTitle == "fenerbahcetv" -> "fenerbahce-tv"
            cleanTitle == "gstv" || cleanTitle == "galatasaraytv" -> "galatasaray-tv"
            cleanTitle == "caytv" || cleanTitle == "cay" -> "cay-tv"
            cleanTitle == "kozatv" -> "koza-tv"
            cleanTitle == "linetv" -> "line-tv"
            cleanTitle == "koytv" -> "koy-tv"
            cleanTitle == "ciftcitv" -> "ciftci-tv"
            cleanTitle == "yabantv" || cleanTitle == "yaban" -> "yaban"
            cleanTitle == "kralpop" || cleanTitle == "kralpoptv" -> "kral-pop"
            cleanTitle == "kraltv" -> "kral-tv"
            cleanTitle == "dreamturk" -> "dream-turk"
            cleanTitle == "kanalb" -> "kanal-b"
            cleanTitle == "kanavrupa" || cleanTitle == "kanalavrupa" -> "kanal-avrupa"
            cleanTitle == "uzayhaber" -> "uzay-haber"
            cleanTitle == "uzaytv" -> "uzay-tv"
            cleanTitle == "vatantv" -> "vatan-tv"
            cleanTitle == "akittv" -> "akit-tv"
            cleanTitle == "brtv" -> "brtv"
            cleanTitle == "brt1" || cleanTitle == "brt1hd" -> "brt-1"
            cleanTitle == "brt2" || cleanTitle == "brt2hd" -> "brt-2"
            cleanTitle == "brt3" -> "brt-3"
            cleanTitle == "kocaeli" || cleanTitle == "kocaelitv" -> "kocaeli-tv"
            cleanTitle == "kontv" || cleanTitle == "kontvhd" -> "kon-tv"
            cleanTitle == "kardelentv" -> "kardelen-tv"
            cleanTitle == "gaziantepolaytv" || cleanTitle == "olaytv" -> "gaziantep-olay-tv"
            cleanTitle == "ekoturk" || cleanTitle == "ekoturktv" -> "ekoturk"
            cleanTitle == "cnbce" -> "cnbc-e"
            else -> {
                val candidate = cleanTitle.replace("tv", "")
                turkeyLogos.find {
                    val cleanIt = it.replace("-", "")
                    cleanIt == candidate || cleanIt == cleanTitle || cleanTitle.startsWith(cleanIt)
                }
            }
        }

        return if (customSlug != null && turkeyLogos.contains(customSlug)) {
            "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/$customSlug-tr.png"
        } else {
            "https://www.canlitv.diy/kanal/logo/$channelId.jpg"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        if (page > 1) return@coroutineScope newHomePageResponse(request.name, emptyList())

        // Fetch Canlitv.diy (primary)
        val primaryJob = async {
            try {
                val res = app.get(request.data, headers = defaultHeaders)
                val doc = Jsoup.parse(res.text)
                doc.select("ul li[class*='ft_']").mapNotNull { el ->
                    val aTag = el.selectFirst("a[href]") ?: return@mapNotNull null
                    val title = aTag.attr("title").replace("canlı izle", "").replace("izle", "").trim().ifBlank { aTag.text().trim() }
                    if (excludedChannels.any { ch -> title.lowercase().contains(ch) }) return@mapNotNull null

                    val href = aTag.attr("href") ?: return@mapNotNull null
                    val idMatch = Regex("""ft_(\d+)""").find(el.attr("class")) ?: return@mapNotNull null
                    val channelId = idMatch.groupValues[1]

                    val poster = getChannelLogo(title, channelId)
                    val channelUrl = if (href.startsWith("http")) href else "$mainUrl$href"

                    newLiveSearchResponse(title, "$channelUrl?id=$channelId", TvType.Live) {
                        this.posterUrl = poster
                        this.posterHeaders = imageHeaders
                    }
                }
            } catch (e: Exception) {
                emptyList<SearchResponse>()
            }
        }

        // Fetch Canlitvizle.tv (alternative)
        val alternativeCategoryUrl = when {
            request.data.contains("genel-tv-kanallari") -> "https://amp.canlitvizle.tv/category/ulusalkanallar"
            request.data.contains("haber-kanallari") -> "https://amp.canlitvizle.tv/category/haber-kanallari-izle"
            request.data.contains("spor-kanallari") -> "https://amp.canlitvizle.tv/category/spor-kanallari-hd"
            request.data.contains("belgesel-kanallari") -> "https://amp.canlitvizle.tv/category/belgesel-kanallari"
            request.data.contains("cocuk-kanallari") -> "https://amp.canlitvizle.tv/category/cocuk-kanallari"
            request.data.contains("dini-tv-kanallari") -> "https://amp.canlitvizle.tv/category/dini-kanallar"
            request.data.contains("yerel-tv-kanallari") -> "https://amp.canlitvizle.tv/category/yerel-kanallar"
            else -> null
        }

        val altJob = async {
            if (alternativeCategoryUrl == null) return@async emptyList<SearchResponse>()
            try {
                val res = app.get(alternativeCategoryUrl, headers = mapOf("User-Agent" to defaultHeaders["User-Agent"]!!))
                val doc = Jsoup.parse(res.text)
                doc.select("a.channel-card").mapNotNull { el ->
                    val title = el.selectFirst(".channel-name")?.text()?.trim() ?: el.attr("title").replace("canlı izle", "").replace("izle", "").trim()
                    if (title.isBlank()) return@mapNotNull null
                    if (excludedChannels.any { ch -> title.lowercase().contains(ch) }) return@mapNotNull null

                    val href = el.attr("href") ?: return@mapNotNull null
                    val logo = el.selectFirst("amp-img")?.attr("src") ?: el.selectFirst("img")?.attr("src") ?: ""

                    newLiveSearchResponse("$title (Alternatif)", "$href?source=canlitvizle", TvType.Live) {
                        this.posterUrl = if (logo.startsWith("http")) logo else "https://amp.canlitvizle.tv$logo"
                        this.posterHeaders = imageHeaders
                    }
                }
            } catch (e: Exception) {
                emptyList<SearchResponse>()
            }
        }

        val primaryItems = primaryJob.await()
        val altItems = altJob.await()

        val merged = (primaryItems + altItems).distinctBy { item -> item.name }

        newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = merged,
                isHorizontalImages = true
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val primaryJob = async {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$mainUrl/modul/liste.php?ara=$encodedQuery&Kanal_id=4"
                val res = app.get(searchUrl, headers = mapOf(
                    "User-Agent" to defaultHeaders["User-Agent"]!!,
                    "Referer" to "$mainUrl/",
                    "X-Requested-With" to "XMLHttpRequest"
                ))
                
                val doc = Jsoup.parse(res.text)
                doc.select("li[class*='ft_']").mapNotNull { el ->
                    val aTag = el.selectFirst("a[href]") ?: return@mapNotNull null
                    val title = aTag.attr("title").replace("canlı izle", "").replace("izle", "").trim().ifBlank { aTag.text().trim() }
                    if (excludedChannels.any { ch -> title.lowercase().contains(ch) }) return@mapNotNull null

                    val href = aTag.attr("href") ?: return@mapNotNull null
                    val idMatch = Regex("""ft_(\d+)""").find(el.attr("class")) ?: return@mapNotNull null
                    val channelId = idMatch.groupValues[1]

                    val poster = getChannelLogo(title, channelId)
                    val channelUrl = if (href.startsWith("http")) href else "$mainUrl$href"

                    newLiveSearchResponse(title, "$channelUrl?id=$channelId", TvType.Live) {
                        this.posterUrl = poster
                        this.posterHeaders = imageHeaders
                    }
                }
            } catch (e: Exception) {
                emptyList<SearchResponse>()
            }
        }

        val altJob = async {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://amp.canlitvizle.tv/s?s=$encodedQuery"
                val res = app.get(searchUrl, headers = mapOf("User-Agent" to defaultHeaders["User-Agent"]!!))
                val doc = Jsoup.parse(res.text)
                doc.select("a.channel-card").mapNotNull { el ->
                    val title = el.selectFirst(".channel-name")?.text()?.trim() ?: el.attr("title").replace("canlı izle", "").replace("izle", "").trim()
                    if (title.isBlank()) return@mapNotNull null
                    if (excludedChannels.any { ch -> title.lowercase().contains(ch) }) return@mapNotNull null

                    val href = el.attr("href") ?: return@mapNotNull null
                    val logo = el.selectFirst("amp-img")?.attr("src") ?: el.selectFirst("img")?.attr("src") ?: ""

                    newLiveSearchResponse("$title (Alternatif)", "$href?source=canlitvizle", TvType.Live) {
                        this.posterUrl = if (logo.startsWith("http")) logo else "https://amp.canlitvizle.tv$logo"
                        this.posterHeaders = imageHeaders
                    }
                }
            } catch (e: Exception) {
                emptyList<SearchResponse>()
            }
        }

        val primaryItems = primaryJob.await()
        val altItems = altJob.await()

        (primaryItems + altItems).distinctBy { item -> item.name }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("canlitvizle")) {
            val cleanUrl = url.substringBefore("?source=")
            val res = app.get(cleanUrl, headers = mapOf("User-Agent" to defaultHeaders["User-Agent"]!!))
            val doc = Jsoup.parse(res.text)
            val title = doc.selectFirst("h1")?.text()?.replace(" canlı izle", "")?.replace(" izle", "")?.trim()
                ?: "Canlı Yayın (Alternatif)"
            val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            val logo = doc.selectFirst("amp-img")?.attr("src") ?: doc.selectFirst("img[src*='poster']")?.attr("src") ?: ""
            val posterUrl = if (logo.startsWith("http")) logo else "https://amp.canlitvizle.tv$logo"

            return newLiveStreamLoadResponse(title, url, url) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
                this.plot = plot
            }
        }

        val cleanUrl = url.substringBefore("?id=")
        val channelId = url.substringAfter("?id=").substringBefore("&")

        val res = app.get(cleanUrl, headers = defaultHeaders)
        val doc = Jsoup.parse(res.text)

        val title = doc.selectFirst("h1")?.text()?.replace(" canlı izle", "")?.replace(" izle", "")?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Canlı Tv izle | Canlitv.com", "")?.trim()
            ?: "Canlı Yayın"

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val poster = getChannelLogo(title, channelId)

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = poster
            this.posterHeaders = imageHeaders
            this.plot = plot
        }
    }

    private suspend fun getTurkuvazStream(baseUrl: String): String? {
        val rand = (100000..999999).random()
        val tokenUrl = "https://securevideotoken.tmgrup.com.tr/webtv/secure?$rand&url=${URLEncoder.encode(baseUrl, "UTF-8")}"
        val timestamp = System.currentTimeMillis().toString()
        val res = app.get(tokenUrl, headers = mapOf(
            "X-isApp" to "false",
            "X-Rand" to timestamp,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer" to "https://www.atv.com.tr/"
        ))
        if (res.isSuccessful) {
            val json = tryParseJson<Map<String, Any>>(res.text)
            return json?.get("Url") as? String
        }
        return null
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.contains("canlitvizle")) {
            try {
                val cleanUrl = data.substringBefore("?source=")
                val pageRes = app.get(cleanUrl, headers = mapOf(
                    "User-Agent" to defaultHeaders["User-Agent"]!!,
                    "Referer" to "https://amp.canlitvizle.tv/"
                ))
                val geoliveUrl = Regex("""<(?:amp-)?iframe[^>]+src=["'](https://iframe\.canlitvizle\.tv/geolive\.php[^"']+)["']""").find(pageRes.text)?.groupValues?.get(1)
                
                if (geoliveUrl != null) {
                    val geoliveRes = app.get(geoliveUrl, headers = mapOf(
                        "User-Agent" to defaultHeaders["User-Agent"]!!,
                        "Referer" to cleanUrl
                    ))
                    val liveUrl = Regex("""<(?:amp-)?iframe[^>]+src=["'](https://iframe\.canlitvizle\.tv/live\.php[^"']+)["']""").find(geoliveRes.text)?.groupValues?.get(1)
                    
                    if (liveUrl != null) {
                        val liveRes = app.get(liveUrl, headers = mapOf(
                            "User-Agent" to defaultHeaders["User-Agent"]!!,
                            "Referer" to geoliveUrl
                        ))
                        
                        val bodyText = liveRes.text
                        val fileMatch = Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(bodyText)?.groupValues?.get(1)
                        if (fileMatch != null) {
                            val decryptedUrl = decodeCanlitvizleUrl(fileMatch)
                            if (decryptedUrl.isNotEmpty()) {
                                callback(newExtractorLink(name, "Canlı Yayın (Alternatif HLS)", decryptedUrl, type = ExtractorLinkType.M3U8) {
                                    this.quality = Qualities.P1080.value
                                    this.headers = mapOf(
                                        "User-Agent" to defaultHeaders["User-Agent"]!!,
                                        "Referer" to "https://iframe.canlitvizle.tv/"
                                    )
                                })
                                return true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CanlitvProvider", "canlitvizle loadLinks error: ${e.message}", e)
            }
            return false
        }

        val refererUrl = data.substringBefore("?id=")
        val channelId = data.substringAfter("?id=").substringBefore("&")

        if (channelId.isBlank() || channelId == data) return false

        val playerUrl = "$mainUrl/player/index.php?id=$channelId&mobile=0"
        val playerRes = app.get(playerUrl, headers = mapOf(
            "User-Agent" to defaultHeaders["User-Agent"]!!,
            "Referer" to refererUrl
        ))

        var linkFound = false
        val bodyText = playerRes.text

        // 1. Tabii.com (TRT Kanalları) Yönlendirme Kontrolü
        val tabiiRegex = Regex("""<a[^>]+href=["'](https?://www\.tabii\.com/[^"']+)["']""")
        val tabiiMatch = tabiiRegex.find(bodyText)
        if (tabiiMatch != null) {
            try {
                val tabiiUrl = tabiiMatch.groupValues[1]
                val tabiiRes = app.get(tabiiUrl, headers = mapOf("User-Agent" to defaultHeaders["User-Agent"]!!))
                
                if (tabiiRes.text.contains("\"liveChannel\":")) {
                    val mediaBlock = tabiiRes.text.substringAfter("\"liveChannel\":").substringAfter("\"media\":").substringBefore("]")
                    Regex("""["']url["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(mediaBlock).forEach { m ->
                        val hlsUrl = m.groupValues[1].replace("\\u0026", "&")
                        val sourceName = if (hlsUrl.contains("daion")) "Tabii (Daion CDN)" else "Tabii (TRT Medya)"
                        callback(newExtractorLink(name, "$sourceName HLS", hlsUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                        })
                        linkFound = true
                    }
                }
            } catch (e: Exception) {
                Log.e("CanlitvProvider", "Tabii error: ${e.message}", e)
            }
        }

        // 2. Turkuvaz Grubu (ATV, A2, A Haber, A Spor, Minika Go, Minika Çocuk)
        if (!linkFound) {
            val turkuvazBaseUrl = when {
                bodyText.contains("atv.com.tr") -> "https://trkvz.daioncdn.net/atv/atv.m3u8?ce=3&app=d1ce2d40-5256-4550-b02e-e73c185a314e"
                bodyText.contains("a2tv.com.tr") -> "https://trkvz.daioncdn.net/a2tv/a2tv.m3u8?ce=3&app=59363a60-be96-4f73-9eff-355d0ff2c758"
                bodyText.contains("ahaber.com.tr") -> "https://trkvz.daioncdn.net/ahaber/ahaber.m3u8?app=web"
                bodyText.contains("aspor.com.tr") -> "https://trkvz.daioncdn.net/aspor/aspor.m3u8?ce=3&app=45f847c4-04e8-419a-a561-2ebf87084765"
                bodyText.contains("minikago.com.tr") -> {
                    if (data.lowercase().contains("cocuk") || data.lowercase().contains("çocuk")) {
                        "https://trkvz.daioncdn.net/minikago_cocuk/minikago_cocuk.m3u8?app=web&ce=3"
                    } else {
                        "https://trkvz.daioncdn.net/minikago/minikago.m3u8?app=web&ce=3"
                    }
                }
                else -> null
            }
            if (turkuvazBaseUrl != null) {
                try {
                    val signedUrl = getTurkuvazStream(turkuvazBaseUrl)
                    if (signedUrl != null) {
                        callback(newExtractorLink(name, "Canlı Yayın (HLS)", signedUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("Referer" to "https://www.atv.com.tr/")
                        })
                        linkFound = true
                    }
                } catch (e: Exception) {
                    Log.e("CanlitvProvider", "Turkuvaz stream error: ${e.message}", e)
                }
            }
        }

        // 3. Kanal D / Teve2
        if (!linkFound) {
            val demirorenPageUrl = when {
                bodyText.contains("kanald.com.tr") -> "https://www.kanald.com.tr/canli-yayin"
                bodyText.contains("teve2.com.tr") || bodyText.contains("tv2.com.tr") -> "https://www.tv2.com.tr/canli-yayin"
                else -> null
            }
            if (demirorenPageUrl != null) {
                try {
                    val res = app.get(demirorenPageUrl, headers = mapOf("User-Agent" to defaultHeaders["User-Agent"]!!))
                    if (res.isSuccessful) {
                        val match = Regex("""data-url=["'](https?://demiroren\.daioncdn\.net/[^"']+)["']""").find(res.text)
                        if (match != null) {
                            val rawStreamUrl = match.groupValues[1].replace("&amp;", "&")
                            callback(newExtractorLink(name, "Canlı Yayın (HLS)", rawStreamUrl, type = ExtractorLinkType.M3U8) {
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf("Referer" to demirorenPageUrl)
                            })
                            linkFound = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CanlitvProvider", "Demiroren error: ${e.message}", e)
                }
            }
        }

        // 4. Now TV
        if (!linkFound && bodyText.contains("nowtv.com.tr")) {
            try {
                val res = app.get("https://www.nowtv.com.tr/canli-yayin", headers = mapOf("User-Agent" to defaultHeaders["User-Agent"]!!))
                if (res.isSuccessful) {
                    val match = Regex("""(https://nowtv\.daioncdn\.net/nowtv/nowtv\.m3u8[^"'\s]*)""").find(res.text)
                    if (match != null) {
                        val signedUrl = match.groupValues[1].replace("&amp;", "&")
                        callback(newExtractorLink(name, "Canlı Yayın (HLS)", signedUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("Referer" to "https://www.nowtv.com.tr/")
                        })
                        linkFound = true
                    }
                }
            } catch (e: Exception) {
                Log.e("CanlitvProvider", "Now TV error: ${e.message}", e)
            }
        }

        // 5. Tv8
        if (!linkFound && bodyText.contains("tv8.com.tr")) {
            try {
                val res = app.get("https://www.tv8.com.tr/canli-yayin", headers = mapOf("User-Agent" to defaultHeaders["User-Agent"]!!))
                if (res.isSuccessful) {
                    val match = Regex("""(https?://tv8\.daioncdn\.net/tv8/tv8\.m3u8[^"'\s]*)""").find(res.text)
                    if (match != null) {
                        val signedUrl = match.groupValues[1].replace("&amp;", "&")
                        callback(newExtractorLink(name, "Canlı Yayın (HLS)", signedUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("Referer" to "https://www.tv8.com.tr/")
                        })
                        linkFound = true
                    }
                }
            } catch (e: Exception) {
                Log.e("CanlitvProvider", "Tv8 error: ${e.message}", e)
            }
        }

        // 6. Doğrudan m3u8 kontrolü (playlist.m3u8, master.m3u8, beyaztv.m3u8 vb.)
        if (!linkFound) {
            Regex("""(https?://[^"']+\.m3u8[^"']*)""").findAll(bodyText).forEach { match ->
                val rawUrl = match.groupValues[1]
                callback(newExtractorLink(name, "Canlı Yayın (HLS)", rawUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.quality = Qualities.P1080.value
                })
                linkFound = true
            }
        }

        val ytRegex = Regex("""youtube\.com/embed/([a-zA-Z0-9_-]{11})""")

        // 7. YouTube Embed / Canlı Yayın (11 haneli video ID veya tam URL)
        if (!linkFound) {
            val ytMatch = ytRegex.find(bodyText) ?: Regex("""<a href=["']([a-zA-Z0-9_-]{11})["']""").find(bodyText)
            if (ytMatch != null) {
                val ytId = ytMatch.groupValues[1]
                if (loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)) {
                    linkFound = true
                }
            }
        }

        // 8. Harici iframe veya html5video.php servisleri
        if (!linkFound) {
            Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""").findAll(bodyText).forEach { match ->
                val iframeUrl = match.groupValues[1]
                if (!iframeUrl.contains("youtube") && !linkFound) {
                    try {
                        val iframeRes = app.get(iframeUrl, headers = mapOf(
                            "Referer" to playerUrl,
                            "User-Agent" to defaultHeaders["User-Agent"]!!
                        ))
                        Regex("""(https?://[^"']+\.m3u8[^"']*)""").findAll(iframeRes.text).forEach { m ->
                            callback(newExtractorLink(name, "Canlı Yayın (HLS)", m.groupValues[1], type = ExtractorLinkType.M3U8) {
                                this.referer = iframeUrl
                                this.quality = Qualities.P1080.value
                            })
                            linkFound = true
                        }
                        val ytSubMatch = ytRegex.find(iframeRes.text) ?: Regex("""<iframe[^>]+src=["'](https://www\.youtube\.com/embed/[^"']+)["']""").find(iframeRes.text)
                        if (ytSubMatch != null && !linkFound) {
                            val fullYt = if (ytSubMatch.groupValues[1].length == 11) "https://www.youtube.com/watch?v=${ytSubMatch.groupValues[1]}" else ytSubMatch.groupValues[1]
                            if (loadExtractor(fullYt, subtitleCallback, callback)) {
                                  linkFound = true
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return linkFound
    }

    private fun decodeCanlitvizleUrl(str: String): String {
        return try {
            val parts = str.split("x|Xf|x")
            if (parts.size < 2) return ""
            val rawIndex = parts[0]
            val digitsMatch = Regex("""\d+""").find(rawIndex)?.value ?: return ""
            var startIndex = digitsMatch.toInt()
            var body = parts[1]

            val replaceChars = listOf(
                "€", "$", "Ă", "Ä", "Ë", "Ģ", "Ḩ", "Ķ", "Ḽ", "Ņ", "Ň", "Š", "Ț", "Ž", "Ә", "Є", "Б", "Җ", "Ч", "Ж", "Д", "Ӡ", "Ф", "Ғ", "Ӷ", "Ы", "\u0418", "К", "Љ", "Ө", "Ў", "Њ", "Һ", "Г", "Ş"
            )
            val targetChars = listOf(
                "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "&", "=", "w", "?", "c", "o", "m", "a", "f", "l", "i", "h", "t", "s", ":", "/", "r", "e", "d", "n", "k", "p", "_", "-"
            )

            for (i in targetChars.indices) {
                if (startIndex >= replaceChars.size) {
                    startIndex = 0
                }
                val repChar = replaceChars[startIndex]
                if (repChar == "\u0418") {
                    body = body.replace("\u0418", targetChars[i]).replace("\u0130", targetChars[i])
                } else {
                    body = body.replace(repChar, targetChars[i])
                }
                startIndex++
            }
            body
        } catch (e: Exception) {
            ""
        }
    }
}
