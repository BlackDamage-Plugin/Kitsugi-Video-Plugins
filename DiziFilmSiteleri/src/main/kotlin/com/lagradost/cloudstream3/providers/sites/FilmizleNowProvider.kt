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

class FilmizleNowProvider : MainAPI() {
    override var mainUrl = "https://filmizle.now"
    override var name = "FilmizleNow"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Dest" to "empty"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Filmler",
        "$mainUrl/kesfet" to "Keşfet",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/macera" to "Macera",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/gerilim" to "Gerilim",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/tur/fantastik" to "Fantastik",
        "$mainUrl/tur/dram" to "Dram",
        "$mainUrl/tur/gizem" to "Gizem",
        "$mainUrl/tur/suc" to "Suç",
        "$mainUrl/tur/romantik" to "Romantik",
        "$mainUrl/tur/aile" to "Aile",
        "$mainUrl/tur/animasyon" to "Animasyon",
        "$mainUrl/tur/belgesel" to "Belgesel",
        "$mainUrl/tur/biyografi" to "Biyografi",
        "$mainUrl/tur/muzik" to "Müzik",
        "$mainUrl/tur/muzikal" to "Müzikal",
        "$mainUrl/tur/savas" to "Savaş",
        "$mainUrl/tur/tarih" to "Tarih",
        "$mainUrl/tur/western" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
        } else {
            request.data
        }
        val items = mutableListOf<SearchResponse>()
        try {
            val res = app.get(pageUrl, headers = defaultHeaders, cacheTime = 60)
            if (res.isSuccessful) {
                val doc = Jsoup.parse(res.text)
                doc.select("a[href*='/film/']").forEach { a ->
                    val href = fixUrl(a.attr("href"))
                    if (href.isBlank() || href == "$mainUrl/" || isCategoryOrFilterLink(href)) return@forEach

                    val img = a.selectFirst("img")
                    var title = img?.attr("alt")?.trim()
                    if (title.isNullOrBlank() || title.matches(Regex("""^\d{4}$"""))) {
                        title = a.selectFirst("h2, h3, .title, .font-bold")?.text()?.trim()
                    }
                    if (title.isNullOrBlank() || title.matches(Regex("""^\d{4}$"""))) {
                        title = a.attr("title").trim()
                    }

                    title = cleanTitle(title)
                    if (title.isBlank() || title.contains("Filmleri") || title.matches(Regex("""^\d{4}$"""))) return@forEach
                    val posterUrl = fixUrlNull(img?.attr("src") ?: img?.attr("data-src"))

                    items.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val results = mutableListOf<SearchResponse>()
        try {
            val res = app.get(searchUrl, headers = defaultHeaders)
            if (res.isSuccessful) {
                val doc = Jsoup.parse(res.text)
                doc.select("a[href*='/film/']").forEach { a ->
                    val href = fixUrl(a.attr("href"))
                    if (href.isBlank() || isCategoryOrFilterLink(href)) return@forEach

                    val img = a.selectFirst("img")
                    var title = img?.attr("alt")?.trim()
                    if (title.isNullOrBlank() || title.matches(Regex("""^\d{4}$"""))) {
                        title = a.selectFirst("h2, h3, .title, .font-bold")?.text()?.trim()
                    }
                    if (title.isNullOrBlank() || title.matches(Regex("""^\d{4}$"""))) {
                        title = a.attr("title").trim()
                    }

                    title = cleanTitle(title)
                    if (title.isBlank() || title.matches(Regex("""^\d{4}$"""))) return@forEach
                    val posterUrl = fixUrlNull(img?.attr("src") ?: img?.attr("data-src"))

                    results.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results.distinctBy { it.url }
    }

    private fun isCategoryOrFilterLink(href: String): Boolean {
        return href.contains("/yil/") || href.contains("/tur/") || href.contains("/kategori/") ||
                href.contains("/giris") || href.contains("/kesfet") || href.contains("/diziler")
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("""\s*(?:\(\d{4}\))?\s*(?:izle|filmi|full hd izle)$""", RegexOption.IGNORE_CASE), "").trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val res = app.get(url, headers = defaultHeaders)
            val doc = res.document
            val title = cleanTitle(doc.selectFirst("h1")?.text() ?: return null)
            val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
            val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            val year = doc.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()

            val csrfToken = doc.selectFirst("meta[name='csrf-token']")?.attr("content") ?: ""
            val cookies = res.headers["set-cookie"] ?: ""

            val bxMatch = Regex("""bx\(JSON\.parse\('([^']+)'\)\)""").find(res.text)
            val bxData = bxMatch?.groupValues?.get(1)?.replace("\\u0022", "\"") ?: ""

            val payloadObj = JSONObject()
            payloadObj.put("url", url)
            payloadObj.put("csrf", csrfToken)
            payloadObj.put("cookies", cookies)
            payloadObj.put("bx", bxData)

            return newMovieLoadResponse(title, url, TvType.Movie, payloadObj.toString()) {
                this.posterUrl = poster
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
            val obj = JSONObject(data)
            val pageUrl = obj.optString("url", mainUrl)
            val csrf = obj.optString("csrf", "")
            val cookies = obj.optString("cookies", "")
            val bxStr = obj.optString("bx", "")

            if (bxStr.isBlank()) return@coroutineScope false
            val bxArray = JSONArray(bxStr)
            var found = false

            for (i in 0 until bxArray.length()) {
                val item = bxArray.optJSONObject(i) ?: continue
                val itemId = item.optLong("i", 0)
                val itemS = item.optString("s", "")
                if (itemId == 0L || itemS.isEmpty()) continue

                val postRes = app.post(
                    "$mainUrl/px",
                    headers = mapOf(
                        "User-Agent" to MOBILE_USER_AGENT,
                        "X-Requested-With" to "XMLHttpRequest",
                        "X-CSRF-TOKEN" to csrf,
                        "Accept" to "application/json",
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Cookie" to cookies,
                        "Referer" to pageUrl
                    ),
                    data = mapOf(
                        "i" to itemId.toString(),
                        "s" to itemS
                    )
                )

                if (postRes.isSuccessful) {
                    val resJson = JSONObject(postRes.text)
                    val embedUrl = resJson.optString("u", "")
                    if (embedUrl.isNotEmpty()) {
                        val fullEmbed = fixUrl(embedUrl)
                        if (fullEmbed.contains("vidmixi.com")) {
                            extractVidmixi(fullEmbed, callback, subtitleCallback)
                            found = true
                        } else if (fullEmbed.contains("m3u8") || fullEmbed.contains("mp4")) {
                            callback(
                                newExtractorLink("FilmizleNow", "FilmizleNow Direct", fullEmbed, if (fullEmbed.contains("m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                                    this.referer = "$mainUrl/"
                                    this.headers = mapOf("User-Agent" to MOBILE_USER_AGENT, "Referer" to "$mainUrl/")
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        } else {
                            loadExtractor(fullEmbed, "$mainUrl/", subtitleCallback, callback)
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
            val res = app.get(embedUrl, headers = mapOf("User-Agent" to MOBILE_USER_AGENT, "Referer" to "$mainUrl/"))
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
                        this.headers = mapOf("User-Agent" to MOBILE_USER_AGENT, "Referer" to "$mainUrl/")
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
}
