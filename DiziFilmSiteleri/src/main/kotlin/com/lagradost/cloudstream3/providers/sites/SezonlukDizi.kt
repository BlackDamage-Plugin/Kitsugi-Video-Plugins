package com.lagradost.cloudstream3.providers

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class SezonlukDizi : MainAPI() {
    override var mainUrl              = "https://sezonlukdizi8.com"
    override var name                 = "SezonlukDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler.asp?siralama_tipi=id&s="          to "Son Eklenenler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&dil=0&s="    to "Türkçe Dublaj Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&durum=5&s="  to "Final Yapan Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&durum=1&s="  to "Devam Eden Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=2&s="    to "Yerli Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=1&s="    to "Yabancı Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=3&s="    to "Asya Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=4&s="    to "Animasyonlar",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=5&s="    to "Animeler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=6&s="    to "Belgeseller",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=aksiyon&s=" to "Aksiyon Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=bilimkurgu&s=" to "Bilim Kurgu Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=komedi&s=" to "Komedi Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=dram&s=" to "Dram Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=korku&s=" to "Korku Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=fantastik&s=" to "Fantastik Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=gerilim&s=" to "Gerilim Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=macera&s=" to "Macera Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=gizem&s=" to "Gizem Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=suc&s=" to "Suç & Polisiye Dizileri"
    )

    private var isDomainResolved = false

    private suspend fun resolveActiveDomain() {
        if (isDomainResolved) return
        try {
            val res = app.get(mainUrl, cacheTime = 60)
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
        val requestUrl = request.data.replace("https://sezonlukdizi8.com", mainUrl)
        val document = app.get("${requestUrl}${page}").document
        
        val home = document.select("div.afis a").mapNotNull { 
            it.toSearchResult() 
        }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.description")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        if (href.contains("webteizle")) return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val ratingText = this.selectFirst(".imdbp, .puan, .rate, .imdb, span.right")?.text() ?: ""
        val ratingClean = ratingText.replace("IMDb", "", ignoreCase = true).replace(",", ".").replace(Regex("[^0-9.]"), "").trim()
        val ratingVal = ratingClean.toDoubleOrNull()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
            this.posterUrl = posterUrl 
            ratingVal?.takeIf { it > 0.0 }?.let { 
                this.score = Score.from10(it)
                this.posterHeaders = mapOf("IMDb" to "$it")
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveActiveDomain()
        val cleanQuery = query.lowercase().trim()
            .replace(Regex("""\s*(?:filmi|dizisi|filmleri|dizileri|izle)$"""), "")
            .trim()

        val categorySlug = when (cleanQuery) {
            "aksiyon" -> "tur=aksiyon"
            "macera" -> "tur=macera"
            "korku" -> "tur=korku"
            "komedi" -> "tur=komedi"
            "gerilim" -> "tur=gerilim"
            "dram" -> "tur=dram"
            "gizem" -> "tur=gizem"
            "fantastik" -> "tur=fantastik"
            "romantik" -> "tur=romantik"
            "bilim kurgu", "bilimkurgu" -> "tur=bilimkurgu"
            "suç", "suc" -> "tur=suc"
            "belgesel" -> "kat=6"
            "anime" -> "kat=5"
            "animasyon" -> "kat=4"
            "yerli" -> "kat=2"
            "yabancı", "yabanci" -> "kat=1"
            "asya" -> "kat=3"
            else -> null
        }

        if (categorySlug != null) {
            val url = "$mainUrl/diziler.asp?siralama_tipi=id&${categorySlug}&s=1"
            try {
                val document = app.get(url).document
                return document.select("div.afis a").mapNotNull { 
                    it.toSearchResult() 
                }.distinctBy { it.url }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val searchRes = mutableListOf<SearchResponse>()
        try {
            val res = app.post(
                "$mainUrl/ajax/arama.asp",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                ),
                data = mapOf("q" to query)
            ).parsedSafe<SearchRoot>()

            res?.results?.values?.forEach { cat ->
                if (cat.name != "Sanatçılar" && cat.name != "Sanatcilar" && cat.name != "Filmler" && cat.name != "filmler") {
                    cat.results?.forEach { item ->
                        val title = item.title ?: return@forEach
                        val url = item.url ?: return@forEach
                        if (url.contains("webteizle")) return@forEach
                        val fullUrl = fixUrl(url)
                        val posterUrl = fixUrlNull(item.image)
                        searchRes.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            item.imdb?.takeIf { it > 0.0 }?.let { scoreVal ->
                                this.score = Score.from10(scoreVal)
                                this.posterHeaders = mapOf("IMDb" to "$scoreVal")
                            }
                        })
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return searchRes.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        resolveActiveDomain()
        val cleanUrl = url.replace("https://sezonlukdizi8.com", mainUrl)
        val document = app.get(cleanUrl).document

        val title       = document.selectFirst("div.header")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.image img")?.attr("data-src")) ?: return null
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it.text().trim() }
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()

        val endpoint    = cleanUrl.split("/").last()

        val actorsReq  = app.get("${mainUrl}/oyuncular/${endpoint}").document
        val actors     = actorsReq.select("div.doubling div.ui").map {
            Actor(
                it.selectFirst("div.header")!!.text().trim(),
                fixUrlNull(it.selectFirst("img")?.attr("src"))
            )
        }


        val episodesReq = app.get("${mainUrl}/bolumler/${endpoint}").document
        val dubEpisodes = mutableListOf<Episode>()
        val subEpisodes = mutableListOf<Episode>()

        for (sezon in episodesReq.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val epName    = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val epHref    = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")) ?: continue
                val epEpisode = bolum.selectFirst("td:nth-of-type(3)")?.text()?.substringBefore(".Bölüm")?.trim()?.toIntOrNull()
                val epSeason  = bolum.selectFirst("td:nth-of-type(2)")?.text()?.substringBefore(".Sezon")?.trim()?.toIntOrNull()

                val dVal = bolum.selectFirst("i[d]")?.attr("d")
                val isDub = dVal == "0" || dVal == "2"
                val isSub = dVal == "1" || dVal == "2" || dVal == "0" || dVal == null

                val epObj = newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                }

                if (isDub) dubEpisodes.add(epObj)
                if (isSub) subEpisodes.add(epObj)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            addActors(actors)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        resolveActiveDomain()
        Log.d("SZD", "data » $data")
        val cleanData = data.replace("https://sezonlukdizi8.com", mainUrl)
        val document = app.get(cleanData).document
        val aspData = getAspData()
        val bid = document.selectFirst("div#dilsec")?.attr("data-id") ?: return@coroutineScope false

        val ajaxHeaders = mapOf(
            "Referer" to cleanData,
            "X-Requested-With" to "XMLHttpRequest"
        )

        // --- ALTYAZI KISMI ---
        try {
            val altyaziResponse = app.post(
                "${mainUrl}/ajax/dataAlternatif${aspData.alternatif}.asp",
                headers = ajaxHeaders,
                data = mapOf(
                    "bid" to bid,
                    "dil" to "1"
                )
            ).parsedSafe<Kaynak>()

            if (altyaziResponse?.status == "success" && altyaziResponse.data != null) {
                for (veri in altyaziResponse.data) {
                    this@coroutineScope.launch {
                        Log.d("SZD", "dil»1 | veri.baslik » ${veri.baslik}")
                        try {
                            val veriResponse = app.post(
                                "${mainUrl}/ajax/dataEmbed${aspData.embed}.asp",
                                headers = ajaxHeaders,
                                data = mapOf("id" to "${veri.id}"),
                                timeout = 15000L
                            )
                            val embedHtml = veriResponse.text
                            val iframeSrc = veriResponse.document.selectFirst("iframe")?.attr("src")
                            val iframe = fixUrlNull(iframeSrc)
                            if (iframe != null) {
                                if (!iframe.contains("reCAPTCHADATA.asp", ignoreCase = true)) {
                                    Log.d("SZD", "dil»1 | iframe » $iframe")
                                    extractVideoLink(iframe, veri.baslik, false, this@coroutineScope, subtitleCallback, callback)
                                }
                            } else {
                                val scriptText = veriResponse.document.select("script").map { it.html() }.firstOrNull { it.contains("var vid") }
                                if (scriptText != null) {
                                    val vid = Regex("""var vid\s*=\s*['"](.*?)['"]""").find(scriptText)?.groupValues?.get(1)
                                    if (vid != null) {
                                        val dzenUrl = "https://dzen.ru/embed/$vid"
                                        extractDzenLink(dzenUrl, veri.baslik, false, this@coroutineScope, callback)
                                    }
                                }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // --- DUBLAJ KISMI ---
        try {
            val dublajResponse = app.post(
                "${mainUrl}/ajax/dataAlternatif${aspData.alternatif}.asp",
                headers = ajaxHeaders,
                data = mapOf(
                    "bid" to bid,
                    "dil" to "0"
                )
            ).parsedSafe<Kaynak>()

            if (dublajResponse?.status == "success" && dublajResponse.data != null) {
                for (veri in dublajResponse.data) {
                    this@coroutineScope.launch {
                        Log.d("SZD", "dil»0 | veri.baslik » ${veri.baslik}")
                        try {
                            val veriResponse = app.post(
                                "${mainUrl}/ajax/dataEmbed${aspData.embed}.asp",
                                headers = ajaxHeaders,
                                data = mapOf("id" to "${veri.id}"),
                                timeout = 15000L
                            )
                            val embedHtml = veriResponse.text
                            val iframeSrc = veriResponse.document.selectFirst("iframe")?.attr("src")
                            val iframe = fixUrlNull(iframeSrc)
                            if (iframe != null) {
                                if (!iframe.contains("reCAPTCHADATA.asp", ignoreCase = true)) {
                                    Log.d("SZD", "dil»0 | iframe » $iframe")
                                    extractVideoLink(iframe, veri.baslik, true, this@coroutineScope, subtitleCallback, callback)
                                }
                            } else {
                                val scriptText = veriResponse.document.select("script").map { it.html() }.firstOrNull { it.contains("var vid") }
                                if (scriptText != null) {
                                    val vid = Regex("""var vid\s*=\s*['"](.*?)['"]""").find(scriptText)?.groupValues?.get(1)
                                    if (vid != null) {
                                        val dzenUrl = "https://dzen.ru/embed/$vid"
                                        extractDzenLink(dzenUrl, veri.baslik, true, this@coroutineScope, callback)
                                    }
                                }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        true
    }

    private suspend fun extractVideoLink(
        iframe: String,
        veriBaslik: String,
        isDub: Boolean,
        scope: CoroutineScope,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val label = if (isDub) "Dublaj - $veriBaslik" else "AltYazı - $veriBaslik"
        
        var cleanUrl = iframe.trim()
        if (cleanUrl.startsWith("//")) {
            cleanUrl = "https:$cleanUrl"
        }
        
        if (cleanUrl.contains("vidmoly", ignoreCase = true)) {
            cleanUrl = cleanUrl.replace(Regex("""vidmoly\.[a-z0-9]+"""), "vidmoly.net")
        }
        
        if (cleanUrl.contains("bysejikuar", ignoreCase = true)) {
            cleanUrl = cleanUrl.replace(Regex("""bysejikuar\.[a-z0-9]+"""), "filemoon.sx")
        }
        
        if (cleanUrl.contains("upstream", ignoreCase = true)) {
            cleanUrl = cleanUrl.replace(Regex("""upstream\.[a-z0-9]+"""), "upstream.to")
        }
        
        if (cleanUrl.contains("vidoza", ignoreCase = true)) {
            cleanUrl = cleanUrl.replace(Regex("""vidoza\.[a-z0-9]+"""), "vidoza.co")
        }
        
        if (cleanUrl.contains("mixdrop", ignoreCase = true)) {
            cleanUrl = cleanUrl.replace(Regex("""mixdrop\.[a-z0-9]+"""), "mixdrop.co")
        }
        
        if (cleanUrl.contains("streamruby") || cleanUrl.contains("rubyvid") || cleanUrl.contains("rubystream") || cleanUrl.contains("abstream")) {
            try {
                val response = app.get(cleanUrl, headers = mapOf("Referer" to "${mainUrl}/")).text
                val unpacked = SzdJsUnpacker.unpack(response)
                if (unpacked != null) {
                    val m3u8Url = Regex("""file\s*:\s*["'](http[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                    if (m3u8Url != null) {
                        scope.launch {
                            callback(
                                newExtractorLink(
                                    source = "StreamRuby",
                                    name = label,
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = mapOf("Referer" to cleanUrl)
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        if (cleanUrl.contains("odnoklassniki.ru")) {
            cleanUrl = cleanUrl.replace("odnoklassniki.ru", "ok.ru")
        }
        
        loadExtractor(cleanUrl, "${mainUrl}/", subtitleCallback) { link ->
            scope.launch {
                callback(
                    newExtractorLink(
                        source = this@SezonlukDizi.name,
                        name = label,
                        url = link.url,
                        link.type
                    ) {
                        this.quality = link.quality
                        this.headers = link.headers
                    }
                )
            }
        }
    }

    private suspend fun extractDzenLink(
        dzenUrl: String,
        veriBaslik: String,
        isDub: Boolean,
        scope: CoroutineScope,
        callback: (ExtractorLink) -> Unit
    ) {
        val label = if (isDub) "Dublaj - Dzen ($veriBaslik)" else "AltYazı - Dzen ($veriBaslik)"
        try {
            val html = app.get(dzenUrl).text
            var m3u8Url = Regex("""https?://[^\s"'\\<>]+?\.m3u8[^\s"'\\<>]*""").find(html)?.value
            if (m3u8Url == null) {
                m3u8Url = Regex("""https?:\\/\\/[^\s"'<>]+?\.m3u8[^\s"'<>]*""").find(html)?.value?.replace("\\/", "/")
            }
            if (m3u8Url != null) {
                m3u8Url = m3u8Url.replace("\\u0026", "&")
                scope.launch {
                    callback(
                        newExtractorLink(
                            source = "Dzen",
                            name = label,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf("Referer" to dzenUrl)
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    //Helper function for getting the number (probably some kind of version?) after the dataAlternatif and dataEmbed
    private suspend fun getAspData() : AspData {
        val websiteCustomJavascript = try {
            app.get(
                "${this.mainUrl}/js/site.min.js",
                headers = mapOf(
                    "Referer" to "${this.mainUrl}/"
                )
            ).text
        } catch (e: Exception) {
            ""
        }
        val dataAlternatifAsp = Regex("""dataAlternatif(.*?).asp""").find(websiteCustomJavascript)?.groupValues?.get(1)
            ?: "22"
        val dataEmbedAsp = Regex("""dataEmbed(.*?).asp""").find(websiteCustomJavascript)?.groupValues?.get(1)
            ?: "22"
        return AspData(dataAlternatifAsp, dataEmbedAsp)
    }
}

object SzdJsUnpacker {
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
