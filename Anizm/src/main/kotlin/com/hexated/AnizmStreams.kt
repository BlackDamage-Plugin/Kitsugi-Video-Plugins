package com.hexated

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Collections

/**
 * Anizm akış çözücüsü — **v3 API + eski kuşak yedekleri** (v6).
 *
 * Sitenin 2026-10 itibarıyla canlı zinciri (kanıt: canlı bölüm sayfası HTML'i,
 * `button[data-v3-translator]` / `.v3-translator-button[data-sources]`):
 *
 *   1. Bölüm sayfası  → `button[data-v3-translator][data-sources]`
 *                       `data-sources = /bolum/{bolumId}/cevirmen/{cevirmenId}`
 *                       (aynı sayfada hazır `button[data-video][data-url]` de olabilir)
 *   2. GET {data-sources} (XHR) → JSON `{videos:[{id,name}]}` **veya** `{data:"<html>"}`
 *                                 → `a[video]` / `button[data-video]` etiketleri
 *   3. Her video: `{sourcesUrl}` içindeki `/cevirmen/{id}` → `/video/{vid}`
 *                 → GET (XHR) → JSON `{url, player}`; `player` bir iframe HTML'i olabilir
 *   4. Oynatıcı → `anizmplayer.com/video/<hash>` → `POST /player/index.php?data=<hash>&do=getVideo`
 *                 → `securedLink` (HLS master → varyantlar)
 *   5. Çözülemeyen host → uygulamanın 325 kayıtlı extractor'ı → host sayfası taraması
 *
 * Eski kuşak (`div#fansec a[translator]` → `{data:html}` → `a.videoPlayerButtons`)
 * ve sayfa düzeyi oynatıcılar da desteklenir; tema/kuşak değişse bile kaynak gelir.
 *
 * Hız: kaynaklar PARALEL çözülür (semaphore + istek zaman aşımı + toplam bütçe),
 * sonuçlar çözüldükçe yayınlanır. Hiçbir adım istisna fırlatmaz.
 */
internal class AnizmStreams(
    private val pluginName: String,
    private val base: () -> String,
    private val http: AnizmHttp
) {

    /** Çözülecek oynatıcı adayı. */
    private data class Source(val fansub: String, val host: String, val url: String)

    private val emitted: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val seenSubs: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val emitLock = Mutex()

    @Volatile
    private var emittedCount: Int = 0

    /** Bölüm sayfası hiç alınamadı mı? (üst katman canlı aynayı değiştirip tekrar dener) */
    @Volatile
    var pageFetchFailed: Boolean = false
        private set

    // ------------------------------------------------------------------

    /**
     * Bölüm sayfası → **tüm** çevirmenler → **tüm** videolar → oynatıcı → akış.
     *
     * Zincir paralel yürür: bir çevirmenin/oynatıcının yavaşlığı diğerlerini
     * durdurmaz (her adım kendi zaman aşımına sahip). Yayınlanan akış sayısı
     * döner.
     */
    suspend fun load(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val site = base().trimEnd('/')
        val html = http.get(pageUrl, "$site/")
        if (html.isNullOrBlank()) {
            pageFetchFailed = true
            Log.w(TAG, "bölüm sayfası alınamadı: $pageUrl")
            return 0
        }
        pageFetchFailed = false
        emitSubtitles(html, pageUrl, subtitleCallback)
        val doc = Jsoup.parse(html, pageUrl)

        // 1) Çevirmenler (data-sources / data-translator-id)
        var translators = AnizmApi.parseTranslators(doc, pageUrl)

        // 2) Çevirmen yoksa bölüm dizini: /bolum/{id}
        if (translators.isEmpty()) {
            translators = directoryTranslators(doc, html, pageUrl, site)
        }
        Log.i(TAG, "çevirmen: ${translators.size} (${translators.joinToString { it.name }})")

        // 3) Her çevirmenin video listesi — paralel, kendi zaman aşımıyla
        val collected = Collections.synchronizedList(mutableListOf<Source>())
        if (translators.isNotEmpty()) {
            coroutineScope {
                translators.map { translator ->
                    async {
                        val videos = withTimeoutOrNull(TRANSLATOR_MS) {
                            fetchVideos(translator, pageUrl)
                        } ?: emptyList()
                        collected.addAll(videos)
                    }
                }.awaitAll()
            }
        }

        // 4) Sayfa düzeyi hazır oynatıcılar (varsa)
        val pageSources = pageLevelSources(doc, html, pageUrl, site)

        // 5) Eski kuşak yedek (v2 işaretleme) — yalnızca hiç aday yoksa
        var stage = "v3"
        var all = (collected.toList() + pageSources).distinctBy { it.url }
        if (all.isEmpty()) {
            all = (collectV2(doc, pageUrl) + pageSources).distinctBy { it.url }
            stage = "v2"
        }
        Log.d(TAG, "aday: $stage=${all.size} çevirmen=${translators.size} sayfa=${pageSources.size}")
        if (all.isEmpty()) return emittedCount

        runBounded(all, concurrency = 8, perItemMs = PER_ITEM_MS) { source ->
            resolveSource(source, pageUrl, subtitleCallback, callback)
        }
        Log.i(TAG, "toplam $emittedCount akış yayınlandı")
        return emittedCount
    }

    // ------------------------------------------------------------------
    // 1) Çevirmen → video listesi
    // ------------------------------------------------------------------

    /**
     * `GET {data-sources}` → `{videos:[{id,name}]}`.
     *
     * Uç `Accept: application/json` + `X-Requested-With` + `X-CSRF-TOKEN` bekler;
     * [AnizmHttp.getXhr] bunları gönderir. CSRF düşerse (ör. oturum çerezi yok)
     * düz GET ile bir kez daha denenir — sunucu tarafında yaş kapısı yoktur,
     * yalnızca başlık denetimi vardır.
     */
    private suspend fun fetchVideos(translator: AnizmTranslator, pageUrl: String): List<Source> {
        var body = http.getXhr(translator.sourcesUrl, pageUrl)
        if (body.isNullOrBlank()) body = http.get(translator.sourcesUrl, pageUrl)
        val videos = AnizmApi.parseVideos(body, translator.sourcesUrl)
        if (videos.isEmpty()) {
            Log.d(TAG, "çevirmen boş: ${translator.name} → ${lastError(translator.sourcesUrl)}")
            return emptyList()
        }
        return videos.map { video ->
            Source(
                fansub = translator.name,
                host = video.name,
                url = AnizmApi.videoUrl(translator.sourcesUrl, video.id)
            )
        }
    }

    /** Bölüm dizini `/bolum/{id}` üzerinden çevirmenleri bulur (sayfa düzeyi). */
    private suspend fun directoryTranslators(
        doc: org.jsoup.nodes.Document,
        html: String,
        pageUrl: String,
        site: String
    ): List<AnizmTranslator> {
        val episodeId = AnizmApi.episodeId(doc, pageUrl) ?: return emptyList()
        val directoryUrl = "$site/bolum/$episodeId"
        val body = http.get(directoryUrl, pageUrl) ?: return emptyList()
        val found = AnizmApi.parseTranslators(Jsoup.parse(body, directoryUrl), directoryUrl)
        if (found.isNotEmpty()) return found
        // Son çare: sayfa gövdesindeki çevirmen yolları (JS/JSON içinde gömülü).
        val fromHtml = Regex("""/bolum/\d+/(?:cevirmen|translator)/\d+""").findAll(html + body)
            .mapNotNull { AnizmSite.absolute(pageUrl, it.value) }
            .distinct()
            .map { AnizmTranslator("Çevirmen", it) }
            .toList()
        return fromHtml
    }

    /** Ayrıştırma hatasını log'a taşır (sessiz yutma yok). */
    private fun lastError(url: String): String =
        "kaynak listesi boş ($url)"

    // ------------------------------------------------------------------
    // 2) Eski kuşak: div#fansec a[translator] → {data:"<html>"} → a[video]
    // ------------------------------------------------------------------

    private suspend fun collectV2(
        doc: org.jsoup.nodes.Document,
        pageUrl: String
    ): List<Source> {
        val anchors = doc.select("div#fansec [translator], [translator]")
        if (anchors.isEmpty()) return emptyList()
        val out = mutableListOf<Source>()
        for (anchor in anchors) {
            val url = AnizmSite.absolute(pageUrl, anchor.attr("translator")) ?: continue
            val fansub = anchor.text().trim().ifBlank { "Çevirmen" }
            val body = http.getXhr(url, pageUrl) ?: continue
            val html = runCatching { JSONObject(body).optString("data") }.getOrDefault("")
                .ifBlank { body }
            out.addAll(parsePlayerTags(html, url, fansub))
        }
        return out.distinctBy { it.url }
    }

    private fun parsePlayerTags(html: String, baseUrl: String, fansub: String): List<Source> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Source>()
        for (el in doc.select("[video], [data-video], [data-url]")) {
            val raw = el.attr("video").ifBlank { el.attr("data-video") }
                .ifBlank { el.attr("data-url") }
            val url = AnizmSite.absolute(baseUrl, raw) ?: continue
            if (!url.startsWith("http")) continue
            val host = el.attr("data-video-name").ifBlank { el.text() }.trim()
                .replace(Regex("""\s+"""), " ")
            out.add(Source(fansub, host.ifBlank { "Oynatıcı" }, url))
        }
        return out.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // 3) Sayfa düzeyi
    // ------------------------------------------------------------------

    private fun pageLevelSources(
        doc: org.jsoup.nodes.Document,
        html: String,
        pageUrl: String,
        site: String
    ): List<Source> {
        val out = mutableListOf<Source>()
        for (frame in doc.select("iframe[src], iframe[data-src]")) {
            val url = AnizmSite.absolute(pageUrl, frame.attr("src").ifBlank { frame.attr("data-src") })
                ?: continue
            if (isAdHost(url)) continue
            out.add(Source("Sayfa", "Oynatıcı", url))
        }
        for (video in doc.select("video[src], video source[src]")) {
            val url = AnizmSite.absolute(pageUrl, video.attr("src")) ?: continue
            out.add(Source("Sayfa", "Doğrudan", url))
        }
        if (out.isEmpty()) {
            for (media in mediaFromHtml(html, pageUrl).take(3)) {
                out.add(Source("Sayfa", "Doğrudan", media))
            }
        }
        // Sayfada JS ile duran host adresleri (iframe'e dönüşmeden).
        for (host in embedUrlsFrom(html).take(4)) {
            out.add(Source("Sayfa", "Oynatıcı", host))
        }
        // anizmplayer hash'i doğrudan sayfada varsa
        val hash = Regex("anizmplayer\\.com/(?:video|player|embed)/([A-Za-z0-9_-]{8,64})")
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""index\\.php\\?data=([a-f0-9]{32})""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
        if (hash != null) out.add(Source("Sayfa", "AnizmPlayer", "$PLAYER_HOST/video/$hash"))
        return out.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // Aday → akış
    // ------------------------------------------------------------------

    private suspend fun resolveSource(
        source: Source,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val label = buildLabel(source)
        val url = source.url
        if (url.isBlank()) return

        // /video/{id} → JSON {url, player} (XHR), sonra oynatıcı.
        if (url.contains("/video/") && !url.contains(PLAYER_HOST)) {
            val target = videoTargetFrom(url, pageUrl)
            if (target != null && target != url) {
                if (resolveTarget(target, pageUrl, label, subtitleCallback, callback, depth = 0)) return
            }
        }
        if (resolveTarget(url, pageUrl, label, subtitleCallback, callback, depth = 0)) return
        // /video/{id} oynatıcı sayfasına yönlenmiyorsa doğrudan /player/{id} denenir.
        if (url.contains("/video/")) {
            val player = url.replace("/video/", "/player/")
            resolveTarget(player, pageUrl, label, subtitleCallback, callback, depth = 1)
        }
    }

    /**
     * `/video/{id}` → oynatıcı adresi.
     *
     * v3 cevabı: `{"success":true,"url":"/player/<id>","source":"…"}`. `url`
     * **göreli** gelebilir; bu yüzden [AnizmApi.parsePlayerUrl] ile istek
     * adresine göre çözülür (eski sürüm göreli adresi atıyordu ve oynatıcı
     * kayboluyordu).
     */
    private suspend fun videoTargetFrom(videoUrl: String, pageUrl: String): String? {
        val body = http.getXhr(videoUrl, pageUrl) ?: return null
        return AnizmApi.parsePlayerUrl(body, videoUrl)
    }

    private suspend fun resolveTarget(
        rawUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        val url = normalize(AnizmSite.absolute(referer, rawUrl) ?: rawUrl)
        if (url.isEmpty()) return false

        if (isDirectMedia(url)) {
            emitMedia(url, label, referer, callback)
            return true
        }

        val hash = anizmPlayerHash(url)
        if (hash != null && resolveAnizmPlayer(hash, url, label, callback)) return true

        // Site içi /player/{id} → 302 Location hedefi.
        if (url.contains("/player/")) {
            val location = http.location(url, referer)
            if (location != null && location != url && !isAdHost(location)) {
                if (resolveTarget(location, referer, label, subtitleCallback, callback, depth)) return true
            }
        }

        // Anizm'in KENDİ oynatıcısı (pl.puffytr.tr) — HLS master'ı doğrudan verir.
        if (resolveSitePlayer(url, label, callback)) return true

        // Gömülü "token blob" + /api/videos/<slug>/resolve (FireStream ve klonları).
        if (resolveTokenBlobPlayer(url, referer, label, callback)) return true

        // Sistenn ailesi (uns.bio / rpmvid / strp2p …): AES'li /api/v1/video?…
        if (resolveSistennPlayer(url, referer, label, callback)) return true

        // Host'a özel yerel çözücüler (extractor katmanı bozulsa da çalışır).
        if (isOkRu(url) && resolveOkRu(url, label, callback)) return true
        if (isSibnet(url) && resolveSibnet(url, label, callback)) return true

        if (url.contains("drive.google") || url.contains("docs.google")) {
            if (resolveGoogleDrive(url, label, referer, callback)) return true
        }

        if (tryExtractors(url, referer, label, subtitleCallback, callback)) return true
        if (depth >= 2) return false

        val page = http.get(url, referer) ?: return false
        emitSubtitles(page, url, subtitleCallback)

        var found = false
        // StreamCash ve benzerleri: oynatıcı yapılandırması gövdede base64
        // (`window.__PCr = "…"`) durur; çözüldüğünde akış adresi çıkar.
        for (stream in inlineConfigMedia(page).take(3)) {
            emitMedia(stream, label, url, callback, trusted = true)
            found = true
        }
        for (stream in mediaFromHtml(page, url).take(4)) {
            emitMedia(stream, label, url, callback)
            found = true
        }
        if (found) return true

        val innerHash = Regex("anizmplayer\\.com/(?:video|player|embed)/([A-Za-z0-9_-]{8,64})")
            .find(page)?.groupValues?.getOrNull(1)
        if (innerHash != null && resolveAnizmPlayer(innerHash, url, label, callback)) return true

        val nested = Jsoup.parse(page, url)
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { AnizmSite.absolute(url, it.attr("src").ifBlank { it.attr("data-src") }) }
            .filterNot { isAdHost(it) }
            .filter { it != url }
            .distinct()
        for (inner in nested.take(2)) {
            if (resolveTarget(inner, url, label, subtitleCallback, callback, depth + 1)) found = true
        }
        // JS ile gömülen host adresleri (iframe yoksa): tanınan video host'ları
        // gövdeden çıkarılıp aynı zincirle çözülür (Gdrive/Mega/Voe/SendVid/…).
        for (host in embedUrlsFrom(page).filter { it != url }.take(3)) {
            if (resolveTarget(host, url, label, subtitleCallback, callback, depth + 1)) found = true
        }
        return found
    }

    // ------------------------------------------------------------------
    // Yerel host çözücüleri (ok.ru, sibnet)
    // ------------------------------------------------------------------

    private fun isOkRu(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        return host.endsWith("ok.ru") || host.endsWith("odnoklassniki.ru")
    }

    /**
     * **Site içi oynatıcı** — `pl.puffytr.tr/watch/<slug>` (kaynak adı "Beta Player").
     *
     * Kanıt (26.10.2026, canlı HTML): oynatıcı sayfasının kendi script'i
     *
     *     const masterUrl       = "/stream/<slug>/master.txt";
     *     const nativeMasterUrl = "/stream/<slug>/native.m3u8";
     *
     * diyor. Yani akış, iframe'in içinde JS ile değil, doğrudan HLS master
     * adresinde duruyor. Adres yine de **doğrulanır**: yalnızca `#EXTM3U`
     * içeren yanıt yayınlanır (ölü bağlantı gösterilmez).
     */
    private suspend fun resolveSitePlayer(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("puffytr.", ignoreCase = true)) return false
        val slug = Regex("""/(?:watch|embed|v)/([A-Za-z0-9_-]{8,})""").find(url)
            ?.groupValues?.getOrNull(1) ?: return false
        val origin = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return false
        var found = false
        for (path in listOf("/stream/$slug/native.m3u8", "/stream/$slug/master.txt")) {
            val streamUrl = origin + path
            val body = http.get(streamUrl, "$origin/") ?: continue
            if (!body.contains("#EXTM3U")) {
                Log.d(TAG, "site oynatıcısı: manifest değil ($streamUrl)")
                continue
            }
            emitMedia(streamUrl, label, "$origin/", callback, trusted = true)
            found = true
            break
        }
        if (!found) Log.d(TAG, "site oynatıcısı: akış bulunamadı ($slug)")
        return found
    }

    /**
     * **FireStream ve klonları** — sayfa, akışı imzalı bir "blob" ile alır:
     *
     *     <script id="…-token-blob" type="application/json"> … </script>
     *     fetch('/api/videos/' + slug + '/resolve', { method:'POST',
     *            body: JSON.stringify({ blob: blob }) })
     *
     * Yanıt `video.signedVideoUrl` (HLS/MP4) içerir. Sayfa kendi JS'inde
     * `video.signedVideoUrl` nesnesini de gömebilir; ikisi de denenir.
     */
    private suspend fun resolveTokenBlobPlayer(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.getOrNull(1) ?: return false
        val page = http.get(url, referer) ?: return false
        if (!page.contains("token-blob") && !page.contains("/api/videos/")) {
            // Sayfa içinde hazır imzalı adres varsa onu kullan.
            val inline = mediaFromJsonText(page)
            for (media in inline.take(2)) emitMedia(media, label, url, callback)
            return inline.isNotEmpty()
        }
        val slug = url.substringBefore('#').trimEnd('/').substringAfterLast('/')
        val blob = Regex(
            """<script[^>]*(?:token-blob|blob)[^>]*>([\s\S]{8,8000}?)</script>""",
            RegexOption.IGNORE_CASE
        ).find(page)?.groupValues?.getOrNull(1)?.trim()

        val payloads = mutableListOf<String>()
        if (!blob.isNullOrBlank()) {
            payloads += JSONObject().put("blob", blob).toString()
        }
        payloads += "{}"
        for (body in payloads) {
            val json = http.postJson("$origin/api/videos/$slug/resolve", body, url) ?: continue
            val media = mediaFromJsonText(json)
            if (media.isEmpty()) continue
            for (item in media.take(2)) emitMedia(item, label, url, callback)
            return true
        }
        // Son çare: sayfadaki gömülü video nesnesi.
        val inline = mediaFromJsonText(page)
        for (item in inline.take(2)) emitMedia(item, label, url, callback)
        return inline.isNotEmpty()
    }

    /**
     * **Sistenn ailesi** — `sistenn.uns.bio`, `anizm.rpmvid.com`, `anizm.strp2p.com`
     * (Vue/React SPA, `player-version="16.x"`, sayfa adresi `https://host/#<kod>`).
     *
     * Protokol (oynatıcının kendi paketinden çözüldü; 2026-09-26 tarihli
     * bağımsız doğrulama ve 26.10.2026 canlı ölçümüyle teyitli):
     *
     *   GET /api/v1/video?id=<kod>&w=<g>&h=<y>&r=<anizm-host>
     *       → **hex** gövde = AES-128-CBC(JSON)
     *         anahtar "kiemtienmua911ca", IV "1234567890oiuytr" (sabit)
     *   JSON: { cfNative, cf, source, hlsVideoTiktok, hlsVideoGoogle,
     *           streamingConfig:{order:[…], adjust:{<tür>:{domain, params, disabled}}},
     *           pk:{k, kx}, delivery:{inHouse, capacityToken, retryAfter} }
     *
     * `cfNative` kendi token'ını taşıyan HLS master'ıdır; `/v4/` yayınlarına
     * `pk` alanından gelen `k`/`kx` eklenir. Tiktok/Google (`hlsVideo*`)
     * segmentleri resim gibi gizlenmiştir — yine de M3U8 olarak verilir.
     */
    private suspend fun resolveSistennPlayer(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = Regex("""^https?://([^/#?]+)/?#([A-Za-z0-9_-]{4,32})(?:[?&].*)?$""").find(url.trim())
            ?: return false
        val host = match.groupValues[1]
        val code = match.groupValues[2]
        val origin = "https://$host"
        val siteHost = runCatching { java.net.URI(base()).host.orEmpty() }.getOrDefault("")
        val apiHeaders = streamHeaders("$origin/") + mapOf(
            "Origin" to origin,
            "Accept" to "*/*",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )

        // Sayfa önce /info'yu çağırır; kimlik geçersizse hemen söyler.
        runCatching { http.getWith("$origin/api/v1/info?id=$code", apiHeaders) }

        var capacity: Pair<String, String>? = null
        var video: JSONObject? = null
        var tried = 0
        while (tried < 2 && video == null) {
            val capQuery = capacity?.let {
                "&capacityToken=" + URLEncoder.encode(it.first, "UTF-8") +
                    "&capacityTokenExpire=" + URLEncoder.encode(it.second, "UTF-8")
            }.orEmpty()
            val target = "$origin/api/v1/video?id=$code&w=1920&h=1080&r=$siteHost$capQuery"
            val body = http.getWith(target, apiHeaders)
            if (body.isNullOrBlank()) return false
            val plain = sistennDecrypt(body.trim()) ?: run {
                Log.d(TAG, "Sistenn: yanıt çözülemedi (${body.take(24)})")
                return false
            }
            val json = runCatching { JSONObject(plain) }.getOrNull() ?: return false
            val sources = sistennSources(json, origin)
            if (sources.isNotEmpty()) {
                video = json
                val pk = json.optJSONObject("pk")
                val pkQuery = pk?.optString("k")?.takeIf { it.isNotEmpty() }
                    ?.let { "k=$it&kx=${pk.optLong("kx")}" }.orEmpty()
                var found = 0
                for (source in sources) {
                    var media = source.url
                    if (pkQuery.isNotEmpty() && media.contains("/v4/")) {
                        media += (if (media.contains('?')) "&" else "?") + pkQuery
                    }
                    emitMedia(media, "$label ${source.kind}".trim(), "$origin/", callback, trusted = true)
                    found++
                }
                Log.d(TAG, "Sistenn: $code → ${sources.joinToString { it.kind }}")
                return found > 0
            }
            val delivery = json.optJSONObject("delivery")
            val capToken = delivery?.optString("capacityToken").orEmpty()
            val saturated = delivery?.optString("inHouse") == "saturated"
            if (saturated && capToken.isNotEmpty() && tried == 0) {
                capacity = capToken to delivery?.optString("capacityTokenExpire").orEmpty()
                tried++
                continue
            }
            Log.d(TAG, "Sistenn: $code akış yok (anahtarlar ${json.keys().asSequence().take(8).joinToString()})")
            return false
        }
        return video != null
    }

    /** Sistenn yanıtındaki yayın türleri — site kendi sırasını `streamingConfig` ile verir. */
    private data class SistennSource(val kind: String, val url: String)

    private fun sistennSources(json: JSONObject, origin: String): List<SistennSource> {
        fun abs(value: String): String? {
            val v = value.trim()
            if (v.isEmpty()) return null
            if (v.startsWith("//")) return "https:$v"
            if (v.startsWith("http")) return v
            return runCatching { java.net.URI("$origin/").resolve(v).toString() }.getOrNull()
        }
        val config = runCatching { JSONObject(json.optString("streamingConfig", "")) }.getOrNull()
        val order = config?.optJSONArray("order")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        } ?: listOf("Tiktok", "Google", "Cloudflare", "In-House")
        val adjust = config?.optJSONObject("adjust")
        val native = abs(json.optString("cfNative"))
        val raw = mapOf(
            "Cloudflare" to json.optString("cf"),
            "Tiktok" to json.optString("hlsVideoTiktok"),
            "Google" to json.optString("hlsVideoGoogle"),
            "In-House" to json.optString("source")
        )
        val out = ArrayList<SistennSource>()
        var nativeAdded = false
        for (kind in order) {
            if (kind == "Cloudflare" && native != null) {
                out += SistennSource("cfNative", native)
                nativeAdded = true
            }
            var media = abs(raw[kind].orEmpty()) ?: continue
            val adjustment = adjust?.optJSONObject(kind)
            if (adjustment?.optBoolean("disabled") == true) continue
            adjustment?.optJSONObject("params")?.let { params ->
                val extra = params.keys().asSequence().joinToString("&") {
                    "$it=" + URLEncoder.encode(params.optString(it), "UTF-8")
                }
                if (extra.isNotEmpty()) media += (if (media.contains('?')) "&" else "?") + extra
            }
            val domain = adjustment?.optString("domain").orEmpty()
            if (domain.isNotEmpty() && media.contains("/hls/")) {
                media = media.replaceFirst("/hls/", "/hlsmod/$domain/")
            }
            out += SistennSource(kind, media)
        }
        if (native != null && !nativeAdded) out += SistennSource("cfNative", native)
        return out
    }

    /** Sistenn AES-128-CBC (sabit anahtar/IV) — hex gövdeyi JSON metnine çevirir. */
    private fun sistennDecrypt(hex: String): String? {
        val h = hex.trim()
        if (h.length < 32 || h.length % 2 != 0) return null
        if (!h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(SISTENN_KEY, "AES"),
                javax.crypto.spec.IvParameterSpec(SISTENN_IV)
            )
            val bytes = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            String(cipher.doFinal(bytes), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d(TAG, "Sistenn: AES hatası (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Gövdedeki base64 oynatıcı yapılandırmasını çözer:
     * `window.__PCr = "<base64>"` → JSON (`src`, `file`, `source`, …).
     */
    private fun inlineConfigMedia(html: String): List<String> {
        if (!html.contains("__PCr")) return emptyList()
        val b64 = Regex("""__PCr\s*=\s*["']([A-Za-z0-9+/=]{24,})["']""")
            .find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        val decoded = runCatching {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return emptyList()
        return mediaFromJsonText(decoded)
    }

    /**
     * Bir JSON/gövde metnindeki **oynatılabilir adresleri** toplar.
     * `signedVideoUrl`, `encodedPath`, `file`, `src`, `url`, `m3u8`, `hls` …
     * anahtarları önceliklidir; ardından gövdedeki medya uzantılı adresler.
     */
    private fun mediaFromJsonText(text: String): List<String> {
        val found = LinkedHashSet<String>()
        val json = runCatching {
            val t = text.trim()
            when {
                t.startsWith("{") -> JSONObject(t)
                t.startsWith("[") -> org.json.JSONArray(t)
                else -> null
            }
        }.getOrNull()
        if (json != null) collectMedia(json, found, 0)
        if (found.isEmpty()) {
            Regex("""https?://[^"'\s\\<>()]+""").findAll(text).forEach { m ->
                val candidate = m.value.replace("\\/", "/").trimEnd('\'', '"', ',', ')', ']', '}')
                if (isDirectMedia(candidate)) found += candidate
            }
        }
        return found.toList()
    }

    private fun collectMedia(node: Any?, out: MutableSet<String>, depth: Int) {
        if (depth > 6 || out.size >= 4) return
        when (node) {
            is JSONObject -> {
                for (key in node.keys()) {
                    val value = node.opt(key)
                    val lower = key.lowercase()
                    if (value is String) {
                        val candidate = value.replace("\\/", "/")
                        val looksMedia = lower in MEDIA_KEYS || isDirectMedia(candidate)
                        if (looksMedia && (candidate.startsWith("http") || candidate.startsWith("//"))) {
                            out += candidate
                        }
                    } else {
                        collectMedia(value, out, depth + 1)
                    }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) collectMedia(node.opt(i), out, depth + 1)
            }
        }
    }

    /**
     * **Google Drive** — Anizm'de en sık geçen kaynak türü ("GDrive").
     *
     * Ölçüm (26.10.2026): `/player/{id}` → 302 → `drive.google.com/file/d/<id>/preview`.
     * `drive.usercontent.google.com/download?id=<id>&export=download&confirm=t`
     * adresi büyük dosyalarda **HTML uyarı sayfası** döndürüyor (content-type
     * text/html) → oynatıcı açılmıyordu. Doğru yol, tarayıcının yaptığı gibi o
     * sayfadaki gizli form alanlarını (`id/export/confirm/uuid`) alıp gerçek
     * indirme adresini kurmaktır.
     */
    private suspend fun resolveGoogleDrive(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileId = Regex("""/file/d/([A-Za-z0-9_-]{6,})""").find(url)
            ?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]id=([A-Za-z0-9_-]{6,})""").find(url)
                ?.groupValues?.getOrNull(1)
            ?: return false

        // 0) Drive'ın KENDİ oynatma API'si (indirme kotasına girmez).
        //    Kanıt: `preview` sayfasındaki `AIza…` anahtarı +
        //    content-workspacevideo-pa.googleapis.com/v1/drive/media/<id>/playback
        //    → mediaStreamingData.formatStreamingData.progressiveTranscodes[].url
        val playback = drivePlaybackStreams(fileId)
        if (playback.isNotEmpty()) {
            Log.d(TAG, "gdrive: oynatma API'si ${playback.size} akış verdi ($fileId)")
            for ((height, mediaUrl) in playback) {
                emitMedia(
                    mediaUrl,
                    if (height > 0) "$label ${height}p" else label,
                    "https://drive.google.com/",
                    callback,
                    trusted = true
                )
            }
            return true
        }

        // 1) Uyarı sayfasını çek ve gizli form alanlarından gerçek adresi kur.
        val warningPage = http.get(
            "https://drive.usercontent.google.com/download?id=$fileId&export=download",
            "https://drive.google.com/"
        )
        val resolved = warningPage?.let { driveDownloadUrl(it) }
        if (resolved != null) {
            Log.d(TAG, "gdrive: form çözümlendi ($fileId)")
            emitMedia(resolved, label, "https://drive.google.com/", callback, trusted = true)
            return true
        }

        // 2) Uyarı sayfası yoksa dosya doğrudan iniyordur.
        emitMedia(
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
            label,
            "https://drive.google.com/",
            callback,
            trusted = true
        )
        return true
    }

    /**
     * Drive oynatma API'si — `progressiveTranscodes` (yükseklik + doğrudan MP4).
     * Anahtar, `preview` sayfasındaki `AIza…` (35 karakter) değeridir; birden
     * çok anahtar olabilir, ilk çalışan kullanılır.
     */
    private suspend fun drivePlaybackStreams(fileId: String): List<Pair<Int, String>> {
        val preview = http.get(
            "https://drive.google.com/file/d/$fileId/preview",
            "https://drive.google.com/"
        ) ?: return emptyList()
        val keys = Regex("""AIza[0-9A-Za-z_\-]{35}""").findAll(preview)
            .map { it.value }.distinct().take(4).toList()
        if (keys.isEmpty()) return emptyList()
        for (key in keys) {
            val body = http.getWith(
                "https://content-workspacevideo-pa.googleapis.com/v1/drive/media/$fileId/playback" +
                    "?key=$key&auditContext=forDisplay",
                mapOf(
                    "Origin" to "https://drive.google.com",
                    "Referer" to "https://drive.google.com/",
                    "Accept" to "*/*"
                )
            ) ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val progressive = json.optJSONObject("mediaStreamingData")
                ?.optJSONObject("formatStreamingData")
                ?.optJSONArray("progressiveTranscodes") ?: continue
            val out = ArrayList<Pair<Int, String>>()
            for (index in 0 until progressive.length()) {
                val item = progressive.optJSONObject(index) ?: continue
                val mediaUrl = item.optString("url").takeIf { it.startsWith("http") } ?: continue
                val height = item.optJSONObject("transcodeMetadata")?.optInt("height", 0) ?: 0
                out += height to mediaUrl
            }
            if (out.isNotEmpty()) return out.distinctBy { it.first }.sortedByDescending { it.first }
        }
        return emptyList()
    }

    /**
     * Drive uyarı sayfasındaki `<form action=…>` + gizli `<input>` alanlarını
     * gerçek indirme adresine çevirir. Alternatif olarak sayfada hazır duran
     * `drive.usercontent.google.com/download?…` bağlantısı da kabul edilir.
     */
    private fun driveDownloadUrl(body: String): String? {
        if (body.isBlank()) return null
        val unescaped = body.replace("&amp;", "&").replace("\\/", "/")
        val ready = Regex("""(https://drive\.usercontent\.google\.com/download\?[^"'<>\\\s]+)""")
            .find(unescaped)?.groupValues?.getOrNull(1)
        if (ready != null && ready.contains("confirm=")) return ready
        val action = Regex("""<form[^>]+action="(https://drive\.usercontent\.google\.com/download[^"]*)"""")
            .find(unescaped)?.groupValues?.getOrNull(1) ?: return ready
        val fields = Regex("""<input[^>]+name="([^"]+)"[^>]*value="([^"]*)"""")
            .findAll(unescaped)
            .associate { it.groupValues[1] to it.groupValues[2] }
        if (fields.isEmpty()) return ready
        val query = fields.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return action + (if (action.contains('?')) "&" else "?") + query
    }

    private fun isSibnet(url: String): Boolean =
        url.contains("sibnet.ru", ignoreCase = true)

    /**
     * ok.ru: `metadataUrl` → `{videos:[{name,url}], hlsManifestUrl}`.
     * (Tarayıcıdaki `postMessage`/`flashvars` yolunun HTTP karşılığı.)
     */
    private suspend fun resolveOkRu(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:videoembed|video)/(\d{6,})""").find(url)?.groupValues?.getOrNull(1)
            ?: return false
        val body = http.get("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id", "$OKRU/") ?: return false
        val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return false
        var found = false
        json.optString("hlsManifestUrl").takeIf { it.startsWith("http") }?.let { hls ->
            emitMedia(hls, label, "$OKRU/", callback, trusted = true)
            found = true
        }
        json.optJSONArray("videos")?.let { videos ->
            for (index in 0 until videos.length()) {
                val item = videos.optJSONObject(index) ?: continue
                val file = item.optString("url")
                if (!file.startsWith("http")) continue
                val name = item.optString("name")
                emitMedia(
                    file,
                    if (name.isBlank()) label else "$label • $name",
                    "$OKRU/",
                    callback,
                    trusted = true
                )
                found = true
            }
        }
        return found
    }

    /** sibnet: `shell.php?videoid=<id>` → `player.src([{src:"/videos/…"}])`. */
    private suspend fun resolveSibnet(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:videoid=|/video)(\d{3,})""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""/([A-Za-z0-9_-]{6,})/?$""").find(url)?.groupValues?.getOrNull(1)
            ?: return false
        val shell = if (url.contains("shell.php")) url else "$SIBNET/shell.php?videoid=$id"
        val body = http.get(shell, "$SIBNET/") ?: return false
        val path = Regex("""player\.src\(\[\{src:\s*["']([^"']+)["']""").find(body)?.groupValues?.getOrNull(1)
            ?: Regex("""["'](/videos/[^"']+\.(?:mp4|m3u8)[^"']*)["']""").find(body)?.groupValues?.getOrNull(1)
            ?: return false
        emitMedia(
            AnizmSite.absolute("$SIBNET/", path) ?: path,
            label,
            "$SIBNET/",
            callback,
            trusted = true
        )
        return true
    }

    private fun buildLabel(source: Source): String {
        val fansub = source.fansub.trim()
        val host = source.host.trim()
        return when {
            fansub.isEmpty() && host.isEmpty() -> "Kaynak"
            fansub.isEmpty() -> host
            host.isEmpty() -> fansub
            host.contains(fansub, ignoreCase = true) -> host
            else -> "$fansub • $host"
        }
    }

    // ------------------------------------------------------------------
    // anizmplayer.com (Aincrad / FirePlayer)
    // ------------------------------------------------------------------

    private fun anizmPlayerHash(url: String): String? {
        if (!url.contains(PLAYER_HOST, ignoreCase = true)) return null
        return Regex("/(?:video|player|e)/([A-Za-z0-9_-]{6,64})").find(url)?.groupValues?.getOrNull(1)
    }

    /**
     * `POST https://anizmplayer.com/player/index.php?data=<hash>&do=getVideo`
     * (form: `hash`, `r`) → JSON.
     *
     * Canlı ölçüm (02.11.2026, `research/anizm-probe/anizmplayer_getVideo_*.json`):
     * ```json
     * {"hls":true,
     *  "videoSource":"https://anizmplayer.com/cdn/hls/<md5>/master.txt",
     *  "securedLink":"https://anizmplayer.com/cdn/hls/<md5>/master.m3u8?md5=…&expires=…"}
     * ```
     * **Oynatılabilir olan `securedLink`'tir** (imzalı HLS master'ı). `videoSource`
     * aynı dizinin `.txt` uzantılı kopyasıdır; `ExtractorLinkType.VIDEO` olarak
     * verilirse oynatıcı metin dosyası açmaya çalışıp hata veriyordu (v18'de
     * "kaynak görünüyor ama açılmıyor" şikâyetinin nedeni). Bu yüzden sıra:
     * `securedLink` → medya uzantılı `videoSource` → (yalnızca o da yoksa)
     * gövdesi `#EXTM3U` olan `.txt` yayını.
     */
    private suspend fun resolveAnizmPlayer(
        hash: String,
        playerUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val body = http.postForm(
            "$PLAYER_HOST/player/index.php?data=$hash&do=getVideo",
            mapOf("hash" to hash, "r" to (base().trimEnd('/') + "/")),
            playerUrl
        )
        if (body.isNullOrBlank()) {
            Log.d(TAG, "anizmplayer yanıt yok: $hash")
            return false
        }
        var found = false
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            val secured = resolvePlayerUrl(json.optString("securedLink"))
            val videoSource = resolvePlayerUrl(json.optString("videoSource"))
            // 1) İmzalı HLS master'ı — asıl oynatılabilir adres.
            val ordered = buildList {
                secured?.takeUnless { it.isTextualManifest() }?.let { add(it) }
                videoSource?.takeUnless { it.isTextualManifest() }?.let { add(it) }
                secured?.takeIf { it.isTextualManifest() }?.let { add(it) }
            }.distinct()
            for (url in ordered) {
                emitMedia(url, label, "$PLAYER_HOST/", callback, trusted = true)
                found = true
            }
            // 2) Yalnızca `.txt` kopya varsa: gövde gerçekten HLS listesi mi?
            if (ordered.isEmpty() && videoSource != null) {
                val manifest = http.get(videoSource, "$PLAYER_HOST/")
                if (manifest != null && manifest.trimStart().startsWith("#EXTM3U")) {
                    emitLink(
                        videoSource,
                        label,
                        "$PLAYER_HOST/",
                        ExtractorLinkType.M3U8,
                        null,
                        streamHeaders("$PLAYER_HOST/"),
                        callback,
                        trusted = true
                    )
                    found = true
                } else {
                    Log.d(TAG, "anizmplayer: yalnızca metin kopya var, atlandı ($videoSource)")
                }
            }
            val array = json.optJSONArray("video")
                ?: json.optJSONArray("videoSources")
                ?: json.optJSONArray("sources")
            array?.let { sources ->
                for (index in 0 until sources.length()) {
                    val item = sources.optJSONObject(index) ?: continue
                    val file = item.optString("file").ifBlank { item.optString("src") }
                        .ifBlank { item.optString("url") }
                    if (file.isBlank()) continue
                    val quality = item.optString("label").ifBlank { item.optString("quality") }
                    emitMedia(
                        file,
                        if (quality.isBlank()) label else "$label • $quality",
                        "$PLAYER_HOST/",
                        callback,
                        trusted = true
                    )
                    found = true
                }
            }
        }
        if (!found) {
            for (stream in mediaFromHtml(body, playerUrl).take(3)) {
                emitMedia(stream, label, playerUrl, callback)
                found = true
            }
        }
        if (!found) {
            // getVideo ucu engellendiğinde (Cloudflare, 403) oynatıcı SAYFASI
            // okunur: sayfa JWPlayer kurulumunu ve `securedLink` alanını satır
            // içi taşıyabiliyor. Böylece API kapalıyken de kaynak düşmez.
            for (pageUrl in arrayOf(
                "$PLAYER_HOST/video/$hash",
                "$PLAYER_HOST/embed/$hash",
                "$PLAYER_HOST/player/$hash"
            )) {
                val page = http.get(pageUrl, "$PLAYER_HOST/") ?: continue
                for (stream in mediaFromHtml(page, pageUrl).take(3)) {
                    emitMedia(stream, label, pageUrl, callback)
                    found = true
                }
                if (!found) {
                    val secured = Regex(
                        """"securedLink"\s*:\s*"([^"]+)""""
                    ).find(page)?.groupValues?.getOrNull(1)
                    val url = resolvePlayerUrl(secured)
                    if (url != null) {
                        emitMedia(url, label, "$PLAYER_HOST/", callback, trusted = true)
                        found = true
                    }
                }
                if (found) break
            }
            if (!found) Log.d(TAG, "anizmplayer: getVideo ve sayfa fallback sonuçsuz ($hash)")
        }
        return found
    }

    /**
     * Oynatıcı cevabındaki **mutlak** medya adresi. Göreli değer (`<hash>/master.m3u8`)
     * KABUL EDİLMEZ: oynatıcı köküne göre çözüldüğünde 403 / "Video bulunamadı"
     * veriyordu (v6'da cihazda kanıtlandı). Bu durumda adres uydurmak yerine
     * `null` döneriz; başka adaylar (`video[]`, sayfa taraması) denenir.
     */
    /** `.txt`/`.html` uzantılı kopya: HLS listesi olsa bile doğrudan akış sayılmaz. */
    private fun String.isTextualManifest(): Boolean {
        val path = substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".txt") || path.endsWith(".html") || path.endsWith(".htm")
    }

    private fun resolvePlayerUrl(raw: String?): String? {
        val value = raw?.trim()?.replace("\\/", "/") ?: return null
        if (value.isEmpty()) return null
        // Protokol-göreli adres (`//cdn.host/…`) mutlaktır: host bellidir.
        if (value.startsWith("//")) return "https:$value"
        if (!value.startsWith("http")) {
            Log.d(TAG, "oynatıcı göreli adres atlandı: ${value.take(90)}")
            return null
        }
        return value
    }

    // ------------------------------------------------------------------
    // Yayınlama
    // ------------------------------------------------------------------

    private suspend fun tryExtractors(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bag = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            loadExtractor(url, referer, subtitleCallback) { link -> bag.add(link) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "loadExtractor hatası (${url.take(90)}): ${e.message}")
        }
        if (bag.isEmpty()) return false
        for (link in bag) {
            emitLink(
                link.url,
                label,
                referer,
                link.type,
                link.quality,
                link.headers,
                callback,
                trusted = true
            )
        }
        return true
    }

    private suspend fun emitMedia(
        rawUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        trusted: Boolean = false
    ) {
        val url = normalize(AnizmSite.absolute(referer, rawUrl) ?: rawUrl)
        if (url.isEmpty()) return
        val isHls = url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.contains(".m3u8") }
        if (isHls) {
            val variants = try {
                withTimeoutOrNull(8_000) {
                    M3u8Helper.generateM3u8(
                        pluginName,
                        url,
                        referer,
                        headers = streamHeaders(referer)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.d(TAG, "m3u8 varyant çözümü başarısız: ${e.message}")
                null
            }
            if (!variants.isNullOrEmpty()) {
                for (variant in variants) {
                    val qualityLabel = if (variant.quality in 144..4320) "$label ${variant.quality}p" else label
                    emitLink(
                        variant.url,
                        qualityLabel,
                        referer,
                        ExtractorLinkType.M3U8,
                        variant.quality,
                        variant.headers,
                        callback,
                        trusted = true
                    )
                }
                return
            }
        }
        emitLink(
            url,
            label,
            referer,
            if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            null,
            null,
            callback,
            trusted = trusted
        )
    }

    /**
     * @param trusted adresi biz çözmediysek (kayıtlı extractor çıktısı, m3u8
     *   varyantı, oynatıcı JSON'undaki `videoSource`, ok.ru/sibnet medyası)
     *   `true`. Bunlar tanım gereği oynatılabilir; uzantısız CDN adresleri
     *   (`/get_video?…`) yanlışlıkla elenmesin.
     */
    private suspend fun emitLink(
        rawUrl: String,
        label: String,
        referer: String,
        type: ExtractorLinkType,
        quality: Int?,
        extraHeaders: Map<String, String>?,
        callback: (ExtractorLink) -> Unit,
        trusted: Boolean = false
    ) {
        val url = normalize(rawUrl)
        if (url.isEmpty() || !emitted.add(url)) return
        // EMİSYON KURALI: sayfa/embed adresi ASLA yayınlanmaz. Yayınlanırsa
        // uygulama onu kendi derin tarayıcısına (EmbedMediaScanner) veriyor ve
        // tarayıcı yolu patladığında kartta KTS-CSP-9110 görünüyor (v31'de yaşandı).
        if (!trusted && !isPlayableUrl(url, type)) {
            Log.d(TAG, "medya olmayan adres yayınlanmadı (9110 koruması): ${url.take(110)}")
            return
        }
        val ref = referer.ifBlank { base() + "/" }
        val headers = streamHeaders(ref) + (extraHeaders ?: emptyMap())
        val link = newExtractorLink(
            source = pluginName,
            name = label.ifBlank { pluginName },
            url = url,
            type = type
        ) {
            this.referer = ref
            this.headers = headers
            this.quality = quality ?: getQualityFromName(label)
        }
        emitLock.withLock {
            emittedCount += 1
            callback(link)
        }
    }

    /**
     * Doğrudan oynatılabilir medya mı? (sayfa/embed adresleri yayınlanmaz)
     * `M3U8`/`DASH` tipi zaten medya demektir; `VIDEO` tipinde yol uzantısına
     * bakılır. Yalnızca HTML'den kazınan adaylar bu kontrolü görür.
     */
    private fun isPlayableUrl(url: String, type: ExtractorLinkType): Boolean {
        if (type != ExtractorLinkType.VIDEO) return true
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val mediaExt = listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".ts", ".flv")
        if (mediaExt.any { path.endsWith(it) }) return true
        // Sorgu içinde uzantı taşıyan CDN adresleri (…?format=m3u8, …&type=mp4)
        return path.contains(".m3u8") || path.contains(".mp4")
    }

    private fun streamHeaders(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "User-Agent" to UA
    )

    // ------------------------------------------------------------------

    private suspend fun emitSubtitles(html: String?, base: String, subtitleCallback: (SubtitleFile) -> Unit) {
        if (html.isNullOrBlank()) return
        val doc = try {
            Jsoup.parse(html, base)
        } catch (_: Exception) {
            return
        }
        for (track in doc.select("track[src]")) {
            val kind = track.attr("kind").ifBlank { "subtitles" }
            if (!kind.equals("subtitles", true) && !kind.equals("captions", true)) continue
            val src = AnizmSite.absolute(base, track.attr("src")) ?: continue
            val lang = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Altyazı" }
            if (seenSubs.add(src)) subtitleCallback(newSubtitleFile(lang, src))
        }
    }

    /**
     * Havuz: en fazla [concurrency] eşzamanlı, **her öğe için ayrı** zaman aşımı.
     *
     * v34 KRİTİK DÜZELTME — "eksik kaynak" hatasının kökü: eski sürüm tüm havuzu
     * tek bir `withTimeoutOrNull(bütçe)` ile sarmalıyordu. Süre dolduğunda
     * **henüz başlamamış ve sırada bekleyen kaynaklar iptal ediliyordu**; bölümde
     * 20+ kaynak varsa son dalga hiç denenmeden yok oluyordu. Kullanıcı bunu
     * "kaynak eksik geliyor" olarak görüyordu.
     *
     * Yeni davranış: iptal YOK. Her kaynak kendi zaman aşımına sahip; biri
     * yavaşsa yalnızca kendisi düşer, diğerleri etkilenmez.
     */
    private suspend fun <T> runBounded(
        items: List<T>,
        concurrency: Int,
        perItemMs: Long,
        block: suspend (T) -> Unit
    ) {
        if (items.isEmpty()) return
        val gate = Semaphore(concurrency)
        val timedOut = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            items.map { item ->
                async {
                    gate.withPermit {
                        try {
                            val ok = withTimeoutOrNull(perItemMs) { block(item); true }
                            if (ok == null) timedOut.incrementAndGet()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.d(TAG, "çözümleme hatası: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
        }
        if (timedOut.get() > 0) {
            Log.w(TAG, "zaman aşımı: ${timedOut.get()}/${items.size} kaynak (diğerleri tamam)")
        }
    }

    private fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return ""
        if (url.startsWith("//")) url = "https:$url"
        if (!url.startsWith("http")) return ""
        return url.replace("\\/", "/").replace("&amp;", "&")
    }

    private fun isDirectMedia(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd") ||
            path.endsWith(".mkv") || path.endsWith(".webm")
    }

    private fun isAdHost(url: String): Boolean {
        val lower = url.lowercase()
        return AD_HOSTS.any { lower.contains(it) }
    }

    /**
     * HTML/JS içinde geçen, **tanınan video host'larına** ait adresler.
     * Oynatıcı katmanı iframe yerine `var url = "https://voe.sx/e/…"` yazdığında
     * kaynak kaybolmasın diye (Anizm bölüm sayfası + site `/player/{id}` sayfası).
     */
    private fun embedUrlsFrom(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val unescaped = html.replace("\\/", "/")
        val out = LinkedHashSet<String>()
        for (match in Regex("""https?://[^"'\s\\]+""").findAll(unescaped)) {
            val url = match.value.trimEnd('"', '\'', ')', ',', ';', '\\')
            if (!EMBED_HOSTS.any { url.contains(it, ignoreCase = true) }) continue
            if (isAdHost(url)) continue
            out.add(url)
            if (out.size >= 6) break
        }
        return out.toList()
    }

    /** HTML/JS içindeki m3u8/mp4/mpd adresleri (kaçışlı `\/` biçimi dahil). */
    private fun mediaFromHtml(html: String, base: String): List<String> {
        if (html.isBlank()) return emptyList()
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/").replace("&amp;", "&")
        val found = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("""["'](https?://[^"']+?\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|hls)\s*[:=]\s*["']([^"']+?\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+?\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+?\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+?\.mpd[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(unescaped)) {
                val raw = match.groupValues.lastOrNull()?.trim().orEmpty()
                if (raw.isEmpty() || raw.startsWith("data:") || raw.startsWith("blob:")) continue
                val absolute = normalize(AnizmSite.absolute(base, raw) ?: raw)
                if (absolute.isEmpty() || isAdHost(absolute)) continue
                found.add(absolute)
                if (found.size >= 6) return found.toList()
            }
        }
        return found.toList()
    }

    companion object {
        private const val TAG = "hexated_AnizmStreams"

        /** Anizm'in kullandığı video host'ları (bölüm sayfası/oynatıcı gövdelerinde aranır). */
        private val EMBED_HOSTS = listOf(
            "anizmplayer", "voe.", "filemoon", "mixdrop", "mp4upload", "sendvid", "sibnet",
            "ok.ru", "odnoklassniki", "vudeo", "upstream", "hdvid", "hexload", "optraco",
            "luffytra", "mega.nz", "mega.co", "mail.ru", "yandex", "drive.google",
            "docs.google", "vidcache", "yourupload", "streamlare", "dood", "vidmoly",
            "luluvdo", "uqload", "gofile", "bylt", "streamtape", "dailymotion", "youtube",
            "abyssplayer", "playmogo", "sitemio"
        )
        private const val PLAYER_HOST = "https://anizmplayer.com"

        /** Sistenn AES-128-CBC anahtarı/IV'si (oynatıcı paketinden; sabit). */
        private val SISTENN_KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        private val SISTENN_IV = "1234567890oiuytr".toByteArray(Charsets.UTF_8)

        /** JSON anahtarları: değeri doğrudan oynatılabilir adres olanlar. */
        private val MEDIA_KEYS = setOf(
            "signedvideourl", "encodedpath", "file", "src", "url", "link", "m3u8",
            "hls", "hlsurl", "video", "videourl", "playlist", "master", "stream"
        )
        private const val OKRU = "https://ok.ru"
        private const val SIBNET = "https://video.sibnet.ru"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        /** Tek bir kaynağın (video → oynatıcı → akış) üst sınırı. */
        private const val PER_ITEM_MS = 18_000L

        /** Bir çevirmenin video listesini getirme üst sınırı. */
        private const val TRANSLATOR_MS = 10_000L

        /** Reklam/analitik host işaretleri — oynatıcı adayı olarak alınmaz. */
        private val AD_HOSTS = listOf(
            "doubleclick", "googlesyndication", "adsterra", "popads", "popcash",
            "propellerads", "onclickads", "adservice", "juicyads", "exoclick",
            "hilltopads", "clickadu", "mgid.com", "adtng", "tsyndicate",
            "trafficjunky", "betweendigital", "reklam", "google-analytics",
            "googletagmanager", "facebook.net", "hotjar"
        )
    }
}

/**
 * Anizm akış zincirinin HTTP yüzeyi (eklenti sınıfı `MainAPI.app` ile uygular).
 */
internal interface AnizmHttp {

    /** Belge GET. */
    suspend fun get(url: String, referer: String? = null): String?

    /** XHR GET (JSON uçları: `data-sources`, `/video/{id}`). */
    suspend fun getXhr(url: String, referer: String? = null): String?

    /** Yönlendirme İZLENMEDEN GET → `Location` başlığı (302 hedefi). */
    suspend fun location(url: String, referer: String? = null): String?

    /** Başlık denetimli GET (Sistenn `/api/v1/...` uçları Origin/Sec-Fetch bekler). */
    suspend fun getWith(url: String, extra: Map<String, String>): String?

    /** JSON gövdeli POST (FireStream `resolve` gibi uçlar). */
    suspend fun postJson(url: String, json: String, referer: String? = null): String?

    /** Form gövdeli POST (anizmplayer). */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null
    ): String?
}
