package com.hexated

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Anizm'in **v3 bölüm API sözleşmesi** — ağ yok, saf ayrıştırma.
 *
 * Sözleşme 02.11.2026'da canlı siteden birebir ölçüldü
 * (`research/anizm-probe/`, anizm.net ↔ anizm.com.tr aynı veritabanı):
 *
 * ```
 * bölüm sayfası HTML
 *   <button class="v3-translator-button" title="Seicode"
 *           data-v3-translator data-translator-id="101517"
 *           data-sources="https://anizm.com.tr/bolum/78177/cevirmen/101517">
 *
 * GET  {data-sources}                            → {"success":true,
 *                                                    "videos":[{"id":1739970,
 *                                                               "name":"Aincrad (Reklamsız)",
 *                                                               "watchTogether":false}]}
 * GET  /bolum/{ep}/video/{videoId}   (XHR+CSRF)   → {"success":true,
 *                                                    "url":"https://…/player/1739970",
 *                                                    "source":"Aincrad (Reklamsız)"}
 * GET  /player/{id}                              → 302 Location → sağlayıcı
 * ```
 *
 * Oynatıcı istekleri `Accept: application/json`, `X-Requested-With: XMLHttpRequest`
 * ve `X-CSRF-TOKEN` (sayfadaki `meta[name=csrf-token]`) bekler; bizim tarafımızda
 * bunlar [AnizmHttp.getXhr] ile gönderilir.
 *
 * ## +18 kapısı
 * Bölüm sayfasındaki `<dialog data-v3-content-warning data-rating="18">` **tamamen
 * istemci tarafındadır** (`v3-content-warning.js` v1.0.5: `sessionStorage` anahtarı
 * `anizm.v3.content-warning.<animeId>`, `localStorage` anahtarı
 * `anizm.v3.content-warning.remembered-rating`). Sunucu, onay bekleyen bir çerez
 * ya da oturum **istemiyor**: yukarıdaki iki uç onay verilmeden de JSON döner
 * (ölçüm: 15 video, HTTP 200). Bu yüzden eklenti kapıyı "kabul etmek" zorunda
 * değildir — sayfa ayrıştırılırken diyalog düğümü yalnızca yanlış bağlantı
 * toplamamak için atlanır.
 */
internal data class AnizmTranslator(
    /** Çevirmen/fansub adı (`title` özniteliği, yoksa düğüm metni). */
    val name: String,
    /** `/bolum/{ep}/cevirmen/{cid}` — video listesini döndüren uç. */
    val sourcesUrl: String,
    val active: Boolean = false
)

/** Bir çevirmenin altındaki video alternatifi. */
internal data class AnizmVideo(
    val id: String,
    val name: String,
    val watchTogether: Boolean = false
)

internal object AnizmApi {

    /** Yaş kapısı diyalogu — bağlantı/oynatıcı taramasında atlanır. */
    private const val CONTENT_WARNING = "[data-v3-content-warning], .v3-content-warning, dialog.v3-content-warning"

    private val TRANSLATOR_SELECTOR =
        "[data-sources], [data-v3-translator], [data-translator-id], [data-translator], [translator]"

    private val SOURCES_PATH = Regex("""/(?:bolum|episode)/(\d+)/(?:cevirmen|translator)/(\d+)""")

    // ------------------------------------------------------------------
    // Sayfa → çevirmenler
    // ------------------------------------------------------------------

    /**
     * Bölüm sayfasındaki çevirmen düğmelerini okur.
     *
     * Sıra: `data-sources` (mutlak ya da göreli) → `data-translator-id` +
     * sayfadaki bölüm kimliğinden kurulan yol. Kimlik uydurulmaz: site
     * `data-translator-id` vermiyorsa çevirmen listesi boş kalır ve üst katman
     * bölüm dizini yedeğine (`/bolum/{id}`) geçer.
     */
    fun parseTranslators(doc: Document, pageUrl: String): List<AnizmTranslator> {
        val episodeId = episodeId(doc, pageUrl)
        val out = LinkedHashMap<String, AnizmTranslator>()

        for (el in doc.select(TRANSLATOR_SELECTOR)) {
            if (el.parents().any { it.`is`(CONTENT_WARNING) }) continue
            val name = translatorName(el)
            val active = el.attr("aria-pressed").equals("true", true) ||
                el.classNames().any { it.contains("active", true) }

            val rawSources = el.attr("data-sources").ifBlank { el.attr("data-v3-translator") }
                .ifBlank { el.attr("data-translator") }
                .ifBlank { el.attr("translator") }
            val fromAttr = urlsIn(rawSources, pageUrl)
            for (url in fromAttr) {
                out.putIfAbsent(url, AnizmTranslator(name, url, active))
            }
            if (fromAttr.isNotEmpty()) continue

            // Yol kurulamıyorsa düğümdeki kimlikle tamamla.
            val cid = el.attr("data-translator-id").ifBlank { el.attr("data-id") }
                .ifBlank { el.attr("data-cid") }
                .trim()
            if (cid.isNotEmpty() && cid.all(Char::isDigit) && episodeId != null) {
                val url = AnizmSite.absolute(pageUrl, "/bolum/$episodeId/cevirmen/$cid")
                if (url != null) out.putIfAbsent(url, AnizmTranslator(name, url, active))
            }
        }

        // jsoup'un kaçırdığı (bozuk) işaretleme için ham metin taraması.
        if (out.isEmpty()) {
            val html = doc.outerHtml()
            for (match in Regex("""data-sources=["']([^"']+)["']""").findAll(html)) {
                for (url in urlsIn(match.groupValues[1], pageUrl)) {
                    out.putIfAbsent(url, AnizmTranslator("Çevirmen", url))
                }
            }
        }
        return out.values.toList()
    }

    /** Bölüm kimliği: `/bolum/{id}` (v3) — oynatıcı, rapor ve ilerleme URL'lerinden. */
    fun episodeId(doc: Document, pageUrl: String): String? {
        for (el in doc.select("[data-progress], [data-url], [data-episode-id], a[href*=/raporver/]")) {
            for (attr in listOf("data-progress", "data-url", "data-episode-id", "href")) {
                val value = el.attr(attr)
                if (value.isBlank()) continue
                episodeIdOf(value)?.let { return it }
            }
        }
        return episodeIdOf(pageUrl)
    }

    fun episodeIdOf(text: String): String? =
        Regex("""/(?:bolum|episode)/(\d+)""").find(text)?.groupValues?.getOrNull(1)

    private fun translatorName(el: Element): String {
        val raw = el.attr("title").ifBlank { el.attr("data-fansub-name") }
            .ifBlank { el.attr("data-name") }
            .ifBlank { el.text() }
        return raw.replace(Regex("""\s+"""), " ").trim().ifBlank { "Çevirmen" }
    }

    private fun urlsIn(raw: String, pageUrl: String): List<String> {
        val value = raw.trim()
        if (value.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        if (value.startsWith("[") || value.startsWith("{")) {
            for (match in Regex("""https?://[^"'\s\\]+""").findAll(value.replace("\\/", "/"))) {
                out.add(match.value)
            }
        } else {
            for (part in value.split(',', ' ', '|', ';')) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                val absolute = AnizmSite.absolute(pageUrl, trimmed) ?: continue
                if (SOURCES_PATH.containsMatchIn(absolute) || absolute.startsWith("http")) out.add(absolute)
            }
        }
        return out.toList()
    }

    // ------------------------------------------------------------------
    // `{videos:[…]}` cevabı
    // ------------------------------------------------------------------

    /**
     * Çevirmen ucunun cevabı: `{"success":true,"videos":[{id,name,watchTogether}]}`.
     * Eski kuşak `{data:"<html>"}` biçimi de desteklenir (HTML içindeki
     * `[data-video]` düğmeleri).
     */
    fun parseVideos(body: String?, sourcesUrl: String): List<AnizmVideo> {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, AnizmVideo>()

        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json != null) {
            val array = json.optJSONObject("data")?.optJSONArray("videos")
                ?: json.optJSONArray("videos")
                ?: json.optJSONArray("items")
                ?: json.optJSONArray("data")
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.opt("id")?.toString()?.trim()
                        ?: item.opt("video_id")?.toString()?.trim()
                        ?: continue
                    if (id.isEmpty() || !id.all(Char::isDigit)) continue
                    val name = item.optString("name")
                        .ifBlank { item.optString("source") }
                        .ifBlank { item.optString("host") }
                        .ifBlank { "Kaynak ${index + 1}" }
                    out.putIfAbsent(id, AnizmVideo(id, name, item.optBoolean("watchTogether")))
                }
            }
            val html = json.optString("data").ifBlank { json.optString("html") }
            if (html.contains('<')) {
                for (video in videosFromHtml(html, sourcesUrl)) out.putIfAbsent(video.id, video)
            }
        } else if (text.contains('<')) {
            for (video in videosFromHtml(text, sourcesUrl)) out.putIfAbsent(video.id, video)
        }
        return out.values.toList()
    }

    private fun videosFromHtml(html: String, baseUrl: String): List<AnizmVideo> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<AnizmVideo>()
        for (el in doc.select("[data-video], [video], [data-url]")) {
            val raw = el.attr("data-video").ifBlank { el.attr("video") }.ifBlank { el.attr("data-url") }
            val id = Regex("""(\d{2,})""").find(raw)?.groupValues?.getOrNull(1) ?: continue
            val name = el.attr("data-video-name").ifBlank { el.text() }.trim()
                .replace(Regex("""\s+"""), " ")
            out.add(AnizmVideo(id, name.ifBlank { "Oynatıcı" }))
        }
        return out
    }

    /**
     * `sourcesUrl` + video kimliği → `/video/{id}` ucu.
     * (Site kendi JS'inde `button.dataset.url = sources.replace(/\/cevirmen\/\d+$/, '/video/' + id)`
     * yapıyor; aynı dönüşüm uygulanır.)
     */
    fun videoUrl(sourcesUrl: String, videoId: String): String =
        Regex("""/cevirmen/\d+/?$""").replace(sourcesUrl, "/video/$videoId")

    // ------------------------------------------------------------------
    // `/video/{id}` → `/player/{id}`
    // ------------------------------------------------------------------

    /**
     * Videounun oynatıcı adresi: `{"url":"https://…/player/<id>", "source":"…"}`.
     * `url` göreli olabilir (`playerOrigin` varsa ona, yoksa istek adresine göre
     * çözülür); gövde HTML ise `iframe[src]` okunur.
     */
    fun parsePlayerUrl(body: String?, requestUrl: String): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json != null) {
            if (!json.optBoolean("success", true)) return null
            val origin = json.optString("playerOrigin").takeIf { it.startsWith("http") }
            val html = json.optString("player").ifBlank { json.optString("html") }
            if (html.contains('<')) {
                iframeSrc(html, origin ?: requestUrl)?.let { return it }
            }
            for (key in listOf("url", "player", "src", "link")) {
                val value = json.optString(key).trim()
                if (value.isEmpty() || value.contains('<')) continue
                val absolute = AnizmSite.absolute(origin ?: requestUrl, value) ?: continue
                if (absolute.startsWith("http")) return absolute
            }
        }
        iframeSrc(text, requestUrl)?.let { return it }
        // Eski kuşak: oynatıcı hash'i doğrudan gövdede.
        Regex("""anizmplayer\.com/(?:video|player|embed)/([A-Za-z0-9_-]{8,64})""")
            .find(text)?.groupValues?.getOrNull(1)?.let { return "https://anizmplayer.com/video/$it" }
        return null
    }

    private fun iframeSrc(html: String, base: String): String? {
        val doc = runCatching { Jsoup.parse(html, base) }.getOrNull() ?: return null
        val src = doc.selectFirst("iframe[src], iframe[data-src]")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            ?: return null
        return AnizmSite.absolute(base, src)?.takeIf { it.startsWith("http") }
    }
}
