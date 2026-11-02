package com.kraptor

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.Collections

/**
 * TrAnimeIzle akış çözücüsü — **tek, deterministik zincir** (v30).
 *
 * Neden yeniden yazıldı?
 *  v17–v29 arasındaki sürümler zinciri "her ihtimale karşı" onlarca yolla
 *  deniyordu (WebView oturumu, DOM kanıt katmanı, host sayfası taraması…).
 *  Sonuç: kullanıcının gördüğü şey **dakikalarca süren** bir yükleme ve çoğu
 *  zaman BOŞ kaynak listesiydi. Oysa sitenin kendi oynatıcı zinciri tek ve
 *  sabittir (site.js, 22.09.2026 arşivi + 25.10.2026 canlı istemci):
 *
 *   1. Bölüm sayfası                 → `input#EpisodeId` (yoksa `animeWatch.initialize(aId, eId…)`)
 *   2. Fansub seçiciler              → `li/div.fansubSelector[data-fid]`
 *   3. POST /api/fansubSources       → JSON gövde `{"EpisodeId":…,"FansubId":…}`
 *                                     → HTML: `li.sourceBtn[data-id]` + `p` = host adı
 *   4. POST /api/sourcePlayer/{id}   → `{"source":"<iframe src=…>"}`
 *                                     → iframe `…/embed2/?id=<GERÇEK HOST URL>`
 *   5. Host URL                      → uygulamanın extractor'ları (filemoon, voe,
 *                                     sibnet, ok.ru, sendvid, streamlare, vudeo,
 *                                     mp4upload, mixdrop, yourupload…)
 *
 *  Bu sınıf tam olarak bu zinciri uygular. Ek olarak:
 *  - **Hız**: fansub/kaynak/host çözümlemesi PARALEL (semaphore), her istek
 *    zaman aşımlı, WebView **yok**, toplam süre bütçeli. Sonuçlar çözüldükçe
 *    yayınlanır (kullanıcı ilk kaynağı ~2 sn içinde görür).
 *  - **Eksiksizlik**: listedeki HER kaynak denenir; `sourcePlayer` boşsa sitenin
 *    ikinci oynatıcı yolu `POST /api/preVideoAd/{id}` devreye girer.
 *  - **Kalite**: HLS master listeleri varyantlarına ayrılır (`M3u8Helper`),
 *    böylece 1080p/720p/480p ayrı ayrı görünür.
 *  - **Captcha**: site ara sıra `/api/Captcha` doğrulaması ister (Aniyomi
 *    istemcisi `ShittyCaptchaInterceptor` ile çözüyor). Burada da aynı yöntem
 *    (en farklı görselin hash'i) uygulanır; yalnızca gerektiğinde çalışır.
 *
 * v39 (oynatıcı sarmalları + eksik hostlar):
 *  - Hedef listesi tek yerde: [TrAnimeIzleSite.playerTargets]. Sıra:
 *    iframe → `…/embed2/?id=<gerçek adres>` (ham VE URL-çözülmüş),
 *    `downloadru2.php?url=<base64>` (Aitr indirme köprüsü) → doğrudan
 *    m3u8/mp4/mpd → gövdedeki tanınan host adresleri.
 *  - Yeni yerel çözücüler: **AitrVip/OpTraco** (`file: "…"`), **Vidmoly**
 *    (`sources:[{file:…}]`), **SendVid** (`video_source`). Bunlar uygulamanın
 *    extractor katmanı tanımadığında da akışı üretir.
 *  - Sarmal çözümü özyinelemeli; derinlik 3 ile sınırlı (sonsuz döngü yok).
 */
internal class TrAnimeIzleStreams(
    private val pluginName: String,
    private val base: () -> String,
    private val http: TrStreamHttp
) {

    /** Fansub listesinden gelen tek bir kaynak butonu. */
    private data class Src(val id: String, val label: String, val fansub: String)

    /** Aynı adresin iki kez yayınlanmaması için (paralel iş parçacıkları güvenli). */
    private val emitted: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val seenSubs: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val emitLock = Any()

    @Volatile
    private var emittedCount: Int = 0

    /** Captcha çözümü tek seferlik ve eşzamanlı çağrılarda yarışsız. */
    private val captchaLock = Mutex()

    @Volatile
    private var captchaSolved = false

    // ------------------------------------------------------------------
    // Giriş noktası
    // ------------------------------------------------------------------

    suspend fun load(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val site = base()
        val referer = "$site/"
        val html = http.get(pageUrl, referer)
        if (html.isNullOrBlank()) {
            Log.w(TAG, "bölüm sayfası alınamadı: $pageUrl")
            return 0
        }
        val doc = Jsoup.parse(html, pageUrl)
        emitSubtitles(html, pageUrl, subtitleCallback)

        val episodeId = TrAnimeIzleSite.episodeIdFrom(html, doc) ?: 0
        val fansubs = TrAnimeIzleSite.fansubsFrom(doc)
        Log.d(TAG, "episodeId=$episodeId fansub=${fansubs.size}")

        // 1) Fansub → kaynak butonları (paralel, en fazla 4 eşzamanlı).
        val srcGate = AsyncGate(4)
        val sources = coroutineScope {
            fansubs.map { fansub ->
                async {
                    srcGate.withPermit {
                        try {
                            fetchSources(fansub, episodeId, pageUrl)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.w(TAG, "fansub ${fansub.id} kaynakları alınamadı: ${e.message}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.id }

        // 2) API hiç kaynak vermediyse sayfadaki hazır butonlar (tema yedeği).
        val pageButtons = doc.select("li.sourceBtn[data-id], a.sourceBtn[data-id], div.sourceBtn[data-id]")
            .mapNotNull { el ->
                val id = el.attr("data-id").trim()
                if (id.isBlank() || id.any { it.isWhitespace() }) null
                else Src(id, el.selectFirst("p.title")?.text() ?: el.text(), "Sayfa")
            }
        val allSources = if (sources.isEmpty() && pageButtons.isEmpty()) {
            pageLevelSources(doc, html, episodeId)
        } else {
            (sources + pageButtons).distinctBy { it.id }
        }
        Log.d(TAG, "kaynak butonu: ${allSources.size}")

        if (allSources.isEmpty()) return emittedCount

        // 3) Kaynak butonlarını çöz — 8 eşzamanlı, dar zaman aşımı, sonuç
        //    çözüldükçe yayınlanır.
        runBounded(allSources, concurrency = 10, perItemMs = PER_ITEM_MS) { src ->
            val label = buildLabel(src)
            // Sayfa düzeyinde bulunan HAZIR host adresleri (`Src.id` = URL):
            // sourcePlayer ucu yok, doğrudan çözülür.
            if (src.id.startsWith("http")) {
                resolveTarget(src.id, pageUrl, label, subtitleCallback, callback, depth = 0)
                return@runBounded
            }
            val body = http.postEmpty("$site/api/sourcePlayer/${src.id}", pageUrl)
            val ok = !body.isNullOrBlank() && resolvePlayerBody(body, pageUrl, label, subtitleCallback, callback)
            if (!ok) {
                // Sitenin ikinci oynatıcı yolu (ilk tıklama): preVideoAd.
                val alt = http.postEmpty("$site/api/preVideoAd/${src.id}", pageUrl)
                if (!alt.isNullOrBlank()) {
                    resolvePlayerBody(alt, pageUrl, label, subtitleCallback, callback)
                }
            }
        }
        Log.d(TAG, "toplam ${emittedCount} akış yayınlandı")
        return emittedCount
    }

    private fun buildLabel(src: Src): String {
        val fansub = src.fansub.trim()
        val host = src.label.trim()
        return when {
            fansub.isEmpty() && host.isEmpty() -> "Kaynak"
            fansub.isEmpty() -> host
            host.isEmpty() -> fansub
            host.contains(fansub, ignoreCase = true) -> host
            else -> "$fansub • $host"
        }
    }

    // ------------------------------------------------------------------
    // 1) Kaynak listesi
    // ------------------------------------------------------------------

    private suspend fun fetchSources(
        fansub: TrFansub,
        episodeId: Int,
        pageUrl: String
    ): List<Src> {
        val site = base()
        val url = "$site/api/fansubSources"
        val json = """{"EpisodeId":$episodeId,"FansubId":${fansub.id}}"""
        val form = mapOf("EpisodeId" to episodeId.toString(), "FansubId" to fansub.id.toString())

        var body = http.postJson(url, json, pageUrl)
        // Eski/yeni sürümlerde uç yalnızca form gövdesini kabul edebiliyor (yuzono portu).
        if (body.isNullOrBlank()) body = http.postForm(url, form, pageUrl)
        if (body.isNullOrBlank()) {
            Log.d(TAG, "fansubSources boş (fid=${fansub.id})")
            return emptyList()
        }
        if (isProbeGate(body)) {
            // Anti-bot "rtt probe" kapısı: tarayıcı parmak izi ister; çözmek yerine
            // diğer yollara (sayfa düzeyi butonlar, preVideoAd) bırakılır.
            Log.w(TAG, "fansubSources rtt-probe kapısı döndürdü (fid=${fansub.id})")
            return emptyList()
        }
        if (looksLikeCaptcha(body)) {
            Log.w(TAG, "fansubSources captcha istedi (fid=${fansub.id})")
            // NOT: `synchronized` kullanılamaz — içinde suspend çağrı var.
            // Mutex, kotlinx-coroutines-core'dadır ve Anizm eklentisi de kullanıyor.
            captchaLock.withLock {
                if (!captchaSolved) captchaSolved = http.solveCaptcha()
            }
            body = http.postJson(url, json, pageUrl) ?: http.postForm(url, form, pageUrl)
            if (body.isNullOrBlank()) return emptyList()
        }

        var parsed = TrAnimeIzleSite.sourcesFrom(body)
        if (parsed.isEmpty()) {
            // JSON gövdede buton yok → aynı uç form gövdesiyle bir kez daha denenir.
            val alt = http.postForm(url, form, pageUrl)
            if (!alt.isNullOrBlank()) parsed = TrAnimeIzleSite.sourcesFrom(alt)
        }
        if (parsed.isEmpty()) {
            Log.d(TAG, "kaynak butonu yok (fid=${fansub.id}, ${body.length} krkt)")
        }
        return parsed.map { Src(it.id, it.label, fansub.label) }
    }

    /** Anti-bot "rtt probe" / Cloudflare JS kapısı mı? */
    private fun isProbeGate(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("rtt-cfg") ||
            lower.contains("rtt probe") ||
            (lower.contains("<title>just a moment") && lower.contains("cloudflare"))
    }

    /** Sayfada sunucu tarafında render edilmiş kaynak butonları. */
    private fun pageLevelSources(doc: org.jsoup.nodes.Document, html: String, episodeId: Int): List<Src> {
        val fromDoc = doc.select("li.sourceBtn, a.sourceBtn, div.sourceBtn")
            .mapNotNull { el ->
                val id = el.attr("data-id").trim()
                if (id.isEmpty()) return@mapNotNull null
                val label = el.selectFirst("p.title")?.text()?.trim()
                    ?: el.selectFirst("p, span, strong")?.text()?.trim()
                    ?: el.text().trim()
                Src(id, label, "Sayfa")
            }
        if (fromDoc.isNotEmpty()) return fromDoc
        // Sayfada JS ile duran host adresleri.
        val hosts = embedUrlsFrom(html).map { Src(it, "Oynatıcı", "Sayfa") }
        if (hosts.isNotEmpty()) return hosts

        // Son yedek: sayfadaki data-eid/data-id çiftleri (episodeId eşleşmeli).
        return doc.select("[data-id][data-eid]").mapNotNull { el ->
            val id = el.attr("data-id").trim()
            if (id.isEmpty() || id.any { it.isWhitespace() }) return@mapNotNull null
            val eid = el.attr("data-eid").toIntOrNull()
            if (episodeId > 0 && eid != episodeId) return@mapNotNull null
            Src(id, el.text().trim(), "Sayfa")
        }.distinctBy { it.id }
    }

    // ------------------------------------------------------------------
    // 2) Oynatıcı gövdesi → hedef adresler
    // ------------------------------------------------------------------

    /**
     * `sourcePlayer` / `preVideoAd` cevabını çözer: JSON sarmalı açılır,
     * iframe adresleri ve doğrudan medya adresleri toplanıp çözülür.
     */
    private suspend fun resolvePlayerBody(
        body: String,
        pageUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isProbeGate(body)) {
            Log.d(TAG, "oynatıcı cevabı rtt-probe kapısı (${body.length} krkt)")
            return false
        }
        val html = TrAnimeIzleSite.unwrapJsonHtml(body).ifBlank { body }
        emitSubtitles(html, pageUrl, subtitleCallback)

        // v39: hedefler artık TEK yerde üretiliyor ([TrAnimeIzleSite.playerTargets]):
        // iframe → `embed2/?id=` iç adresi (ham + URL-çözülmüş), `downloadru2.php`
        // base64 köprüsü, doğrudan m3u8/mp4 ve gövdedeki tanınan host adresleri.
        val targets = TrAnimeIzleSite.playerTargets(html, pageUrl)

        if (targets.isEmpty()) return false

        // Each source must decide its own fallback independently. A concurrent
        // source publishing a link must not suppress this source's preVideoAd.
        val any = java.util.concurrent.atomic.AtomicBoolean(false)
        runBounded(targets, concurrency = 4, perItemMs = SOURCE_BUDGET_MS) { target ->
            if (resolveTarget(target, pageUrl, label, subtitleCallback, callback, depth = 0)) any.set(true)
        }
        return any.get()
    }

    // ------------------------------------------------------------------
    // 3) Hedef adres çözümleme
    // ------------------------------------------------------------------

    private suspend fun resolveTarget(
        rawUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        val url = normalize(rawUrl)
        if (url.isEmpty()) return false
        // Sarmal katmanları saymamak için sert bir derinlik sınırı: site bir
        // oynatıcıyı başka bir sarmalın içine koyduğunda sonsuz döngü olmaz.
        if (depth > 3) return false

        // Doğrudan medya (m3u8/mp4/mpd).
        if (isDirectMedia(url)) {
            emitMedia(url, label, referer, callback)
            return true
        }

        // Sarmal katmanlar (v39): `downloadru2.php?url=<base64>` (Aitr köprüsü) ve
        // `…/embed2/?id=<gerçek adres>` (luffytra2). İç adres önce çözülür.
        TrAnimeIzleSite.downloadBridgeUrl(url)?.let { inner ->
            if (inner != url &&
                resolveTarget(inner, referer, label, subtitleCallback, callback, depth + 1)
            ) return true
        }
        TrAnimeIzleSite.embedInnerUrl(url)?.let { inner ->
            if (inner != url &&
                resolveTarget(inner, referer, label, subtitleCallback, callback, depth + 1)
            ) return true
        }

        // Site içi oynatıcılar (Beta Player / puffytr) — HLS master'ı doğrudan verir.
        if (resolveSitePlayer(url, label, callback)) return true

        // v39 yerel host çözücüleri: AitrVip/OpTraco, Vidmoly, SendVid.
        // (Uygulamanın extractor katmanı bu hostlarda sürüm sürüm kırılıyor;
        // sayfa biçimi sabit olduğu için yerel çözüm daha güvenilir.)
        if (resolveOptraco(url, label, callback)) return true
        if (resolveVidmoly(url, label, callback)) return true
        if (resolveSendvid(url, label, callback)) return true
        // v42: Hdvid/Hexload extractor SINIFLARI kaldırıldı; mantık buraya taşındı
        // (ExtractorApi'yi miras almak yükleme anında AbstractMethodError üretiyordu).
        if (resolveHdvid(url, label, callback)) return true
        if (resolveHexload(url, label, callback)) return true

        // Gömülü "token blob" + /api/videos/<slug>/resolve (FireStream ve klonları).
        if (resolveTokenBlobPlayer(url, referer, label, callback)) return true

        // Sistenn ailesi (uns.bio / rpmvid / strp2p …): AES'li /api/v1/video?
        if (resolveSistennPlayer(url, label, callback)) return true

        // Host'a özel yerel çözücüler (extractor katmanı bu iki hostta kırılgan).
        if (isOkRu(url) && resolveOkRu(url, label, callback)) return true
        if (isSibnet(url) && resolveSibnet(url, label, callback)) return true

        // Google Drive → gdriveplayer köprüsü (tarayıcıda oynayan yol).
        if (url.contains("drive.google.com") || url.contains("docs.google.com")) {
            if (resolveGoogleDrive(url, referer, label, subtitleCallback, callback)) return true
        }

        // Uygulamanın kayıtlı extractor'ları (325 site: filemoon, voe, sibnet,
        // ok.ru, sendvid, streamlare, mp4upload, mixdrop, yourupload, uqload…).
        if (tryExtractors(url, referer, label, subtitleCallback, callback)) return true

        if (depth >= 2) return false

        // Host sayfasını çek: içinde m3u8/mp4 ya da bir iç iframe ara.
        val page = http.get(url, referer)
        if (page.isNullOrBlank()) return false
        emitSubtitles(page, url, subtitleCallback)

        val media = TrAnimeIzleSite.directMediaFrom(page, url)
        var found = false
        // StreamCash benzeri oynatıcılar: base64 yapılandırma (`window.__PCr`).
        for (stream in inlineConfigMedia(page).take(32)) {
            emitMedia(stream, label, url, callback, trusted = true)
            found = true
        }
        for (stream in media.take(32)) {
            emitMedia(stream, label, url, callback)
            found = true
        }
        if (found) return true

        // Bir katman iç iframe (luffytra sarmalı, reklam katmanı vb.).
        val nested = TrAnimeIzleSite.iframesFrom(page, url)
            .flatMap { TrAnimeIzleSite.embedCandidates(it) }
            .map { decode(it) }
            .filter { it != url }
            .distinct()
        for (inner in nested.take(8)) {
            if (resolveTarget(inner, url, label, subtitleCallback, callback, depth + 1)) found = true
        }
        // JS ile gömülen host adresleri (iframe'e dönüşmemiş olabilir).
        for (host in embedUrlsFrom(page).filter { it != url }.take(8)) {
            if (resolveTarget(host, url, label, subtitleCallback, callback, depth + 1)) found = true
        }
        return found
    }

    /**
     * HTML/JS içinde geçen, tanınan video host'larına ait adresler. Oynatıcı
     * katmanı iframe yerine `sources: [{file: "https://voe.sx/e/…"}]` yazdığında
     * kaynak kaybolmasın diye.
     */
    private fun embedUrlsFrom(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val unescaped = html.replace("\\/", "/")
        val out = LinkedHashSet<String>()
        for (match in Regex("""https?://[^"'\s\\]+""").findAll(unescaped)) {
            val url = match.value.trimEnd('"', '\'', ')', ',', ';', '\\')
            if (!EMBED_HOSTS.any { url.contains(it, ignoreCase = true) }) continue
            if (TrAnimeIzleSite.isAdUrl(url)) continue
            out.add(url)
            if (out.size >= 6) break
        }
        return out.toList()
    }

    // ------------------------------------------------------------------
    // Yerel host çözücüleri (ok.ru, sibnet)
    // ------------------------------------------------------------------

    private fun isOkRu(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        return host.endsWith("ok.ru") || host.endsWith("odnoklassniki.ru")
    }

    private fun isSibnet(url: String): Boolean = url.contains("sibnet.ru", ignoreCase = true)

    /** ok.ru: `videoPlayerMetadata` → `hlsManifestUrl` + `videos[]`. */
    private suspend fun resolveOkRu(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:videoembed|video)/(\d{6,})""").find(url)?.groupValues?.getOrNull(1)
            ?: return false
        val body = http.get("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id", "$OKRU/") ?: return false
        val json = runCatching { org.json.JSONObject(body.trim()) }.getOrNull() ?: return false
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
            TrAnimeIzleSite.absolute("$SIBNET/", path) ?: path,
            label,
            "$SIBNET/",
            callback,
            trusted = true
        )
        return true
    }

    /**
     * **AitrVip / OpTraco** (`optraco.top/explorer/<id>`) — sitenin kendi
     * oynatıcısı. Sayfa medya adresini `file: "…"` olarak gömer (çoğu zaman
     * göreli `/hls/…m3u8`). v42: tek yol budur — ayrı bir extractor sınıfı
     * (ExtractorApi mirası) yükleme anında LinkageError üretebiliyordu.
     */
    private suspend fun resolveOptraco(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("optraco.", ignoreCase = true)) return false
        val origin = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: "https://optraco.top"
        val page = http.get(url, "$origin/") ?: return false
        val media = TrAnimeIzleSite.optracoMediaUrl(page, url) ?: return false
        emitMedia(media, label, "$origin/", callback, trusted = true)
        return true
    }

    /** **Vidmoly** (`vidmoly.to|me|biz` `/watch|embed/<id>`) — `sources:[{file:"…"}]`. */
    private suspend fun resolveVidmoly(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("vidmoly.", ignoreCase = true)) return false
        val page = http.get(url, "https://vidmoly.to/") ?: return false
        val raw = Regex(
            """sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(page)?.groupValues?.getOrNull(1)
            ?: Regex(
                """file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).find(page)?.groupValues?.getOrNull(1)
        val media = raw?.let { TrAnimeIzleSite.absolute(url, it) } ?: return false
        emitMedia(media, label, url, callback, trusted = true)
        return true
    }

    /** **SendVid** (`sendvid.com/<id>` veya `/embed/<id>`) — `video_source = "…mp4"`. */
    private suspend fun resolveSendvid(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("sendvid.com", ignoreCase = true)) return false
        val embed = if (url.contains("/embed/")) {
            url
        } else {
            val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
            if (id.isEmpty()) return false
            "https://sendvid.com/embed/$id"
        }
        val page = http.get(embed, "https://sendvid.com/") ?: return false
        val raw = Regex("""video_source\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.getOrNull(1)
            ?: Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(page)?.groupValues?.getOrNull(1)
        val media = raw?.let { TrAnimeIzleSite.absolute(embed, it) } ?: return false
        emitMedia(media, label, embed, callback, trusted = true)
        return true
    }

    /**
     * **Hdvid** (`hdvid.tv/...`) — sayfada `sources: [{file:"…"}]`.
     *
     * v42: Eskiden `HdvidExtractor : ExtractorApi()` sınıfıydı. `ExtractorApi`
     * soyut metot imzaları sürüme duyarlı olduğundan sınıf yüklenirken
     * `AbstractMethodError`/`NoSuchMethodError` üretebiliyor ve eklenti hiç
     * açılmıyordu (KTS-CSP-9110). Artık düz bir fonksiyon.
     */
    private suspend fun resolveHdvid(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("hdvid.", ignoreCase = true)) return false
        val page = http.get(url, url) ?: return false
        val raw = Regex(
            """sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(page)?.groupValues?.getOrNull(1) ?: return false
        val media = TrAnimeIzleSite.absolute(url, raw.replace("http://", "https://")) ?: return false
        emitMedia(media, label, url, callback, trusted = true)
        return true
    }

    /**
     * **Hexload** (`hexload.com/<id>`) — `POST /download` (`op=download3`) →
     * JSON `result.url`.
     *
     * v42: Eskiden `Hexload : ExtractorApi()` sınıfıydı ve Jackson
     * (`@JsonProperty` + `ResponseParser.parseSafe`) kullanıyordu — hem soyut
     * sınıf hem NiceHttp sürümü riski. Artık `org.json` ile düz HTTP.
     */
    private suspend fun resolveHexload(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("hexload.", ignoreCase = true)) return false
        val origin = "https://hexload.com"
        val id = if (url.contains("id=")) {
            url.substringAfterLast("id=").substringBefore("&").substringBefore("/")
        } else {
            url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        }
        if (id.isBlank()) return false
        val body = try {
            val connection = (java.net.URL("$origin/download")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", url)
                setRequestProperty("Origin", origin)
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val payload = "op=download3&id=" + java.net.URLEncoder.encode(id, "UTF-8") +
                "&ajax=1&method_free=1&dataType=json"
            connection.outputStream.use { it.write(payload.toByteArray()) }
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.d(TAG, "hexload isteği başarısız: ${e.message}")
            return false
        }
        val media = try {
            org.json.JSONObject(body).optJSONObject("result")?.optString("url")
        } catch (e: Exception) {
            null
        }?.takeIf { it.startsWith("http") } ?: return false
        emitMedia(media, label, url, callback, trusted = true)
        return true
    }

    /** Kayıtlı extractor'ları dener; yayınlanan her linki kendi etiketimizle geçirir. */
    private suspend fun tryExtractors(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bag = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val handled = try {
            loadExtractor(url, referer, subtitleCallback) { link -> bag.add(link) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "loadExtractor hatası (${url.take(90)}): ${e.message}")
            false
        }
        if (bag.isEmpty()) return false
        for (link in bag) {
            emitLink(
                link.url,
                label,
                link.referer.ifBlank { referer },
                link.type,
                link.quality,
                link.headers,
                callback,
                trusted = true
            )
        }
        return handled || bag.isNotEmpty()
    }

    private suspend fun resolveGoogleDrive(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""/d/([A-Za-z0-9_-]{6,})""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]id=([A-Za-z0-9_-]{6,})""").find(url)?.groupValues?.getOrNull(1)
            ?: return false

        // 0) Drive'ın kendi oynatma API'si — indirme kotasına girmez.
        val playback = drivePlaybackStreams(id)
        if (playback.isNotEmpty()) {
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

        // 1) Drive uyarı sayfasındaki gizli form alanlarından GERÇEK indirme adresi.
        // (Büyük dosyalarda `...&confirm=t` tek başına HTML döndürüyor.)
        val page = http.get(
            "https://drive.usercontent.google.com/download?id=$id&export=download",
            "https://drive.google.com/"
        )
        val resolved = page?.let { driveDownloadUrl(it) }
        if (resolved != null) {
            emitMedia(resolved, label, "https://drive.google.com/", callback, trusted = true)
            return true
        }
        emitMedia(
            "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t",
            label,
            "https://drive.google.com/",
            callback,
            trusted = true
        )
        return true
    }

    /** Drive uyarı sayfası → gerçek indirme adresi (form alanları veya hazır bağlantı). */
    /**
     * Drive oynatma API'si: `preview` sayfasındaki `AIza…` anahtarı +
     * `content-workspacevideo-pa.googleapis.com/v1/drive/media/<id>/playback`
     * → `progressiveTranscodes[]` (yükseklik + doğrudan MP4).
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
            val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: continue
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
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        return action + (if (action.contains('?')) "&" else "?") + query
    }

    // ------------------------------------------------------------------
    // 4) Yayınlama
    // ------------------------------------------------------------------

    /** Medya adresini yayınlar; HLS master ise kalite varyantlarına ayırır. */
    /**
     * **Site içi oynatıcı** — `pl.puffytr.tr/watch/<hash>` ("Beta Player").
     *
     * Oynatıcının kendi script'i: `const masterUrl = "/stream/<hash>/master.txt"`
     * ve `nativeMasterUrl = "/stream/<hash>/native.m3u8"`. Yalnızca `#EXTM3U`
     * içeren yanıt yayınlanır.
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
        for (path in listOf("/stream/$slug/native.m3u8", "/stream/$slug/master.txt")) {
            val target = origin + path
            val body = http.get(target, "$origin/") ?: continue
            if (!body.contains("#EXTM3U")) continue
            emitMedia(target, label, "$origin/", callback, trusted = true)
            return true
        }
        return false
    }

    /**
     * **FireStream ve klonları** — sayfa `<script id="…token-blob">` içindeki
     * imzalı blob'u `POST /api/videos/<slug>/resolve` ile değişir; yanıt
     * `signedVideoUrl` (HLS/MP4) taşır.
     */
    private suspend fun resolveTokenBlobPlayer(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.getOrNull(1) ?: return false
        val page = http.get(url, referer) ?: return false
        if (!page.contains("token-blob") && !page.contains("/api/videos/")) return false
        val slug = url.substringBefore('#').trimEnd('/').substringAfterLast('/')
        val blob = Regex("""<script[^>]*token-blob[^>]*>([^<]{4,4000})</script>""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.getOrNull(1)?.trim()
        val bodies = mutableListOf<String>()
        if (!blob.isNullOrBlank()) bodies += org.json.JSONObject().put("blob", blob).toString()
        bodies += "{}"
        for (body in bodies) {
            val json = http.postJson("$origin/api/videos/$slug/resolve", body, url) ?: continue
            val media = mediaFromJsonText(json)
            if (media.isEmpty()) continue
            for (item in media.take(32)) emitMedia(item, label, url, callback)
            return true
        }
        return false
    }

    /**
     * **Sistenn ailesi** — `https://host/#<kod>`; akış `AES-128-CBC` (sabit
     * anahtar/IV) ile şifrelenmiş `/api/v1/video` yanıtında gelir:
     * `cfNative`, `cf`, `source`, `hlsVideoTiktok`, `hlsVideoGoogle`.
     */
    private suspend fun resolveSistennPlayer(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = Regex("""^https?://([^/#?]+)/?#([A-Za-z0-9_-]{4,32})(?:[?&].*)?$""").find(url.trim())
            ?: return false
        val host = match.groupValues[1]
        val code = match.groupValues[2]
        val origin = "https://$host"
        val apiHeaders = streamHeaders("$origin/", "$origin/") + mapOf(
            "Origin" to origin,
            "Accept" to "*/*",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )
        runCatching { http.getWith("$origin/api/v1/info?id=$code", apiHeaders) }
        val body = http.getWith(
            "$origin/api/v1/video?id=$code&w=1920&h=1080&r=www.tranimeizle.io",
            apiHeaders
        ) ?: return false
        val plain = sistennDecrypt(body.trim()) ?: return false
        val json = runCatching { org.json.JSONObject(plain) }.getOrNull() ?: return false
        val sources = sistennSources(json, origin)
        if (sources.isEmpty()) return false
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
        return found > 0
    }

    private data class SistennSource(val kind: String, val url: String)

    private fun sistennSources(json: org.json.JSONObject, origin: String): List<SistennSource> {
        fun abs(value: String): String? {
            val v = value.trim()
            if (v.isEmpty()) return null
            if (v.startsWith("//")) return "https:$v"
            if (v.startsWith("http")) return v
            return runCatching { java.net.URI("$origin/").resolve(v).toString() }.getOrNull()
        }
        val config = runCatching { org.json.JSONObject(json.optString("streamingConfig", "")) }.getOrNull()
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
                    "$it=" + java.net.URLEncoder.encode(params.optString(it), "UTF-8")
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
            null
        }
    }

    /** Gövdedeki base64 oynatıcı yapılandırması (`window.__PCr`) → medya adresleri. */
    private fun inlineConfigMedia(html: String): List<String> {
        if (!html.contains("__PCr")) return emptyList()
        val b64 = Regex("""__PCr\s*=\s*["']([A-Za-z0-9+/=]{24,})["']""")
            .find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        // v41: `android.util.Base64` KULLANILMAZ (plugin sınıf yükleyicisinde
        // Android çerçeve sınıfına bağlanmak riskli; saf Kotlin çözücü yeter).
        val decoded = TrAnimeIzleSite.base64Decode(b64) ?: return emptyList()
        return mediaFromJsonText(decoded)
    }

    /** JSON/gövde metnindeki oynatılabilir adresler (`signedVideoUrl`, `url`, …). */
    private fun mediaFromJsonText(text: String): List<String> {
        val found = LinkedHashSet<String>()
        val json = runCatching {
            val t = text.trim()
            when {
                t.startsWith("{") -> org.json.JSONObject(t)
                t.startsWith("[") -> org.json.JSONArray(t)
                else -> null
            }
        }.getOrNull()
        if (json != null) collectMedia(json, found, 0)
        if (found.isEmpty()) {
            Regex("""https?://[^"'\s\\<>()]+""").findAll(text).forEach { m ->
                val candidate = normalize(m.value.replace("\\/", "/"))
                if (isDirectMedia(candidate)) found += candidate
            }
        }
        return found.toList()
    }

    private fun collectMedia(node: Any?, out: MutableSet<String>, depth: Int) {
        if (depth > 6 || out.size >= 32) return
        when (node) {
            is org.json.JSONObject -> {
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

    private suspend fun emitMedia(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        trusted: Boolean = false
    ) {
        val clean = normalize(url)
        if (clean.isEmpty()) return
        val isHls = clean.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.contains(".m3u8") }
        if (isHls) {
            val variants = try {
                withTimeoutOrNull(10_000) {
                    M3u8Helper.generateM3u8(
                        pluginName,
                        clean,
                        referer,
                        headers = streamHeaders(referer, clean)
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
                    val qualityLabel = when {
                        variant.quality in 144..4320 -> "$label ${variant.quality}p"
                        else -> label
                    }
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
            clean,
            label,
            referer,
            when {
                isHls -> ExtractorLinkType.M3U8
                clean.substringBefore('?').substringBefore('#').endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            },
            null,
            null,
            callback,
            trusted = trusted
        )
    }

    /**
     * @param trusted adresi biz çözmediysek (kayıtlı extractor'ın ürettiği adres,
     *   m3u8 varyantı, ok.ru/sibnet medyası) `true`. Bu adresler tanım gereği
     *   oynatılabilir medyadır; uzantısız CDN adresleri (`/get_video?…`,
     *   `…googleusercontent.com/…=m22`) yanlışlıkla elenmesin.
     */
    private suspend fun emitLink(
        url: String,
        label: String,
        referer: String,
        type: ExtractorLinkType,
        quality: Int?,
        extraHeaders: Map<String, String>?,
        callback: (ExtractorLink) -> Unit,
        trusted: Boolean = false
    ) {
        val clean = normalize(url)
        if (clean.isEmpty()) return
        // EMİSYON KURALI: sayfa/embed adresi ASLA yayınlanmaz. Yayınlanırsa
        // uygulama onu kendi derin tarayıcısına (EmbedMediaScanner) veriyor ve
        // tarayıcı yolu patladığında kartta KTS-CSP-9110 görünüyor (v31'de yaşandı).
        if (!trusted && !isPlayableUrl(clean, type)) {
            Log.d(TAG, "medya olmayan adres yayınlanmadı (9110 koruması): ${clean.take(110)}")
            return
        }
        if (!emitted.add(clean)) return
        val ref = referer.ifBlank { base() + "/" }
        val headers = streamHeaders(ref, clean) + (extraHeaders ?: emptyMap())
        val link = newExtractorLink(
            source = pluginName,
            name = label.ifBlank { pluginName },
            url = clean,
            type = type
        ) {
            this.referer = ref
            this.headers = headers
            this.quality = quality ?: getQualityFromName(label)
        }
        synchronized(emitLock) {
            emittedCount += 1
            callback(link)
        }
    }

    /**
     * Doğrudan oynatılabilir medya mı? (sayfa/embed adresleri yayınlanmaz)
     *
     * `M3U8`/`DASH` tipi zaten medya demektir; `VIDEO` tipinde yol uzantısına
     * bakılır. Yalnızca **bizim HTML'den kazıdığımız** adaylar bu kontrolü
     * görür — extractor çıktıları `trusted` olarak geçer.
     */
    private fun isPlayableUrl(url: String, type: ExtractorLinkType): Boolean {
        if (type != ExtractorLinkType.VIDEO) return true
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val mediaExt = listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".ts", ".flv")
        if (mediaExt.any { path.endsWith(it) }) return true
        return path.contains(".m3u8") || path.contains(".mp4")
    }

    /**
     * Akış isteklerinde gönderilen başlıklar. `Origin` BİLİNÇLİ olarak
     * gönderilmiyor: bazı CDN'ler (ok.ru, sibnet) yabancı Origin'de 403 veriyor.
     */
    private fun streamHeaders(referer: String, streamUrl: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "User-Agent" to UA
    )

    // ------------------------------------------------------------------
    // 5) Altyazı
    // ------------------------------------------------------------------

    private suspend fun emitSubtitles(html: String?, base: String, subtitleCallback: (SubtitleFile) -> Unit) {
        if (html.isNullOrBlank()) return
        for ((lang, url) in TrAnimeIzleSite.subtitleTracks(html, base)) {
            if (seenSubs.add(url)) subtitleCallback(newSubtitleFile(lang, url))
        }
    }

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------

    /**
     * v41: `kotlinx.coroutines.sync.Semaphore` yerine **saf kotlinx-coroutines-core**
     * ile eşzamanlılık kapısı.
     *
     * Neden: eklenti dex'inde `kotlinx.coroutines.sync` paketine bağlanmak,
     * uygulamanın eklenti sınıf yükleyicisi o paketi paylaşmadığında
     * "çalışma zamanı uyumsuzluğu" (LinkageError) üretebiliyor. Buradaki
     * uygulama yalnızca `Mutex` + `withLock` (çekirdek API) kullanır.
     */
    private class AsyncGate(permits: Int) {
        private val mutex = Mutex()
        private var available = permits.coerceAtLeast(1)
        private val waiting = ArrayDeque<CompletableDeferred<Unit>>()

        suspend fun <T> withPermit(block: suspend () -> T): T {
            acquire()
            return try {
                block()
            } finally {
                release()
            }
        }

        private suspend fun acquire() {
            val waiter: CompletableDeferred<Unit>? = mutex.withLock {
                if (available > 0) {
                    available -= 1
                    null
                } else {
                    CompletableDeferred<Unit>().also { waiting.addLast(it) }
                }
            }
            waiter?.await()
        }

        private suspend fun release() {
            val next = mutex.withLock {
                val waiter = waiting.removeFirstOrNull()
                if (waiter == null) available += 1
                waiter
            }
            next?.complete(Unit)
        }
    }

    /**
     * Sınırlı eşzamanlılık + toplam süre bütçesi. Tek bir yavaş host
     * tüm listeyi kilitlemez; bütçe dolunca o ana kadar çözülenler geçerlidir.
     */
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
        val gate = AsyncGate(concurrency)
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

    private fun decode(raw: String): String = try {
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        raw
    }

    private fun isDirectMedia(url: String): Boolean = TrAnimeIzleSite.isDirectMedia(url)

    private fun looksLikeCaptcha(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("captcha") || lower.contains("guvenlik dogrulama") ||
            lower.contains("güvenlik doğrulama")
    }

    companion object {
        private const val TAG = "kraptor_TrAnimeStreams"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

        /** Tüm kaynak araması için üst sınır — kullanıcı bu süreden fazla beklemez. */
        /** Tek bir kaynak butonunun (sourcePlayer → iframe → akış) üst sınırı. */
        private const val PER_ITEM_MS = 75_000L

        /** Tek bir kaynak butonunun çözüm bütçesi. */
        private const val SOURCE_BUDGET_MS = 25_000L

        /** Sitede görülen video host'ları (oynatıcı gövdelerinde aranır). */
        private val EMBED_HOSTS = listOf(
            "voe.", "filemoon", "mixdrop", "mp4upload", "sendvid", "sibnet", "ok.ru",
            "odnoklassniki", "vudeo", "upstream", "hdvid", "hexload", "optraco", "luffytra",
            "mega.nz", "mega.co", "mail.ru", "yandex", "drive.google", "docs.google",
            "vidcache", "yourupload", "streamlare", "dood", "vidmoly", "luluvdo", "uqload",
            "gofile", "streamtape", "abyssplayer", "playmogo", "sitemio", "bysejeseji",
            "krakenfiles", "hitfile", "turbobit"
        )

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
    }
}

/**
 * Akış zincirinin ihtiyaç duyduğu HTTP yüzeyi.
 *
 * Eklenti sınıfı (`TrAnimeIzle`) bunu `MainAPI.app` + tarayıcı başlıklarıyla
 * uygular; böylece bu dosya uygulama nesnesine bağlı kalmadan test edilebilir.
 */
internal interface TrStreamHttp {

    /** Belge (GET). */
    suspend fun get(url: String, referer: String? = null): String?

    /** XHR GET. */
    suspend fun getXhr(url: String, referer: String? = null): String?

    /** Başlık denetimli GET (Sistenn `/api/v1/...` uçları Origin/Sec-Fetch bekler). */
    suspend fun getWith(url: String, extra: Map<String, String>): String?

    /** JSON gövdeli POST (`Content-Type: application/json`). */
    suspend fun postJson(url: String, json: String, referer: String? = null): String?

    /** Gövdesiz POST (sitenin oynatıcı uçları böyle çalışır). */
    suspend fun postEmpty(url: String, referer: String? = null): String?

    /** Form gövdeli POST (captcha uçları). */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null
    ): String?

    /** Ham bayt (captcha görselleri). */
    suspend fun bytes(url: String, referer: String? = null): ByteArray?

    /**
     * Sitenin `/api/Captcha` doğrulamasını çözer (en farklı görselin hash'i).
     * Gerekmedikçe çağrılmaz; başarısız olursa sessizce döner.
     */
    suspend fun solveCaptcha(): Boolean
}
