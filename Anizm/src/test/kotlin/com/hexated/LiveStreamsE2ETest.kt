package com.hexated

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.zip.GZIPInputStream

/**
 * **Uçtan uca akış testi — eklentinin GERÇEK kodu, canlı site.**
 *
 * [AnizmStreams] sınıfı olduğu gibi koşar; yalnızca HTTP yüzeyi
 * ([AnizmHttp]) burada `HttpURLConnection` + çerez kavanozu ile uygulanır.
 * Böylece "eklenti gerçekten kaç oynatılabilir adres üretiyor" sorusu
 * tahminle değil ölçümle yanıtlanır:
 *
 *   bölüm sayfası (+18 diyalogu **açık**) → çevirmenler → video listesi
 *   → `/video/<id>` → `/player/<id>` → 302 → sağlayıcı → akış
 *
 * `LIVE_PROBE=1` verilmezse atlanır (normal `:Anizm:test` ağa çıkmaz).
 */
class LiveStreamsE2ETest {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val cookieJar = Collections.synchronizedMap(mutableMapOf<String, String>())

    private val candidates = listOf(
        "https://anizm.com.tr",
        "https://anizm.net",
        "https://www.puffytr.com"
    )

    private fun p(line: String) = println("[LIVE] $line")

    private data class Raw(
        val status: Int,
        val body: String,
        val location: String?
    )

    private fun raw(
        url: String,
        referer: String?,
        xhr: Boolean,
        form: Map<String, String>? = null,
        extra: Map<String, String> = emptyMap(),
        follow: Boolean = true,
        jsonBody: String? = null
    ): Raw {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (form == null && jsonBody == null) "GET" else "POST"
                instanceFollowRedirects = follow
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9")
                if (referer != null) setRequestProperty("Referer", referer)
                if (xhr) {
                    setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("sec-fetch-site", "same-origin")
                } else {
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                }
                extra.forEach { (k, v) -> setRequestProperty(k, v) }
                cookieJar[url.substringAfter("://").substringBefore("/")]?.let {
                    setRequestProperty("Cookie", it)
                }
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(jsonBody.toByteArray()) }
                }
                if (form != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val body = form.entries.joinToString("&") {
                        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                    }
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val status = conn.responseCode
            conn.headerFields["Set-Cookie"]?.let { cookies ->
                if (cookies.isNotEmpty()) {
                    val host = url.substringAfter("://").substringBefore("/")
                    cookieJar[host] = cookies.joinToString("; ") { it.substringBefore(";") }
                }
            }
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { input ->
                val stream2 = if ("gzip".equals(conn.contentEncoding, true)) {
                    GZIPInputStream(input)
                } else {
                    input
                }
                val out = ByteArrayOutputStream()
                stream2.copyTo(out)
                out.toByteArray()
            } ?: ByteArray(0)
            Raw(status, bytes.toString(Charsets.UTF_8), conn.getHeaderField("Location"))
        } catch (e: Exception) {
            Raw(0, "", null)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Canlı siteye karşı gerçek [AnizmHttp]. */
    private inner class Network : AnizmHttp {
        override suspend fun get(url: String, referer: String?): String? =
            raw(url, referer, xhr = false).let { if (it.status == 200) it.body else null }

        override suspend fun getXhr(url: String, referer: String?): String? =
            raw(url, referer, xhr = true).let { if (it.status == 200) it.body else null }

        override suspend fun location(url: String, referer: String?): String? =
            raw(url, referer, xhr = false, follow = false).location

        override suspend fun getWith(url: String, extra: Map<String, String>): String? =
            raw(url, extra["Referer"], xhr = false, extra = extra)
                .let { if (it.status in 200..299) it.body else null }

        override suspend fun postJson(url: String, json: String, referer: String?): String? =
            raw(url, referer, xhr = true, jsonBody = json)
                .let { if (it.status in 200..299) it.body else null }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            referer: String?
        ): String? = raw(url, referer, xhr = true, form = form)
            .let { if (it.status in 200..299) it.body else null }
    }

    @Test
    fun `canli bolumden akis uretir`() = runBlocking {
        assumeTrue("LIVE_PROBE=1 değil", System.getenv("LIVE_PROBE") == "1")

        val base = candidates.firstOrNull { candidate ->
            val res = raw(candidate + "/", null, xhr = false)
            res.status == 200 && res.body.contains("v3-catalog")
        }
        if (base == null) {
            p("E2E: canlı alan adı bulunamadı (${candidates.joinToString()})")
            return@runBlocking
        }
        p("E2E: alan adı = $base")

        val episodeUrl = "$base/shingeki-no-kyojin-movie-kanketsu-hen-the-last-attack-" +
            "shingeki-no-kyojin-movie-kanketsu-hen---the-last-attack-bolum-izle"

        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())

        val count = AnizmStreams("Anizm", { base }, Network()).load(
            episodeUrl,
            { subs.add(it) },
            { links.add(it) }
        )

        p("E2E: yayınlanan akış = $count, altyazı = ${subs.size}")
        for (link in links) {
            p("E2E: ${link.name} | ${link.type} | ${link.quality ?: "-"} | ${link.url.take(140)}")
        }
        // Oynatılabilirlik: üretilen her adres gerçekten medya mı?
        var playable = 0
        for (link in links.distinctBy { it.url }.take(6)) {
            val probe = raw(link.url, link.referer.ifBlank { base + "/" }, xhr = false)
            val head = probe.body.take(40).replace("\n", "\\n")
            val isHls = probe.body.contains("#EXTM3U")
            val looksMedia = probe.status in 200..206 &&
                (isHls || probe.body.contains("#EXT-X") || link.type !=
                    com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO)
            if (looksMedia) playable++
            p("E2E: doğrulama ${probe.status} ${if (isHls) "HLS" else "?"} → ${link.url.take(90)} | $head")
        }
        p("E2E: doğrulanan oynatılabilir adres = $playable")
        if (count == 0) p("E2E: SONUÇ — hiç akış üretilmedi (regresyon)")
    }
}
