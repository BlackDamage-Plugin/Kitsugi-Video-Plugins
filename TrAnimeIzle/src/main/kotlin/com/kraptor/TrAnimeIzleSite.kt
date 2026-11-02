package com.kraptor

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

/** Liste/arama kartı. */
internal data class TrCard(
    val name: String,
    val url: String,
    val poster: String? = null,
    val score: Double? = null,
    /** Kart üzerinde yazan bölüm/sezon ipucu (varsa). */
    val episodeHint: Int? = null,
    val seasonHint: Int? = null
)

/** Bölüm satırı — `season` ve `number` HER ZAMAN doludur (uygulama eşleştiricisi ikisini de ister). */
internal data class TrEpisode(
    val url: String,
    val number: Int,
    val season: Int,
    val name: String?,
    val date: String? = null,
    val poster: String? = null
)

/** Detay sayfası çıktısı. */
internal data class TrDetail(
    val title: String,
    val poster: String?,
    val plot: String?,
    val tags: List<String>,
    val year: Int?,
    val score: Double?,
    val typeText: String?,
    val totalEpisodes: Int?,
    val fansubs: List<String>,
    val episodes: List<TrEpisode>
)

/** Fansub (çevirmen) seçicisi: `data-fid`. */
internal data class TrFansub(val id: Int, val label: String)

/** Kaynak butonu: `data-id` (+ `data-eid`). */
internal data class TrSource(val id: String, val episodeId: Int?, val label: String)

/**
 * TrAnimeIzle (tranimeizle.io ailesi) HTML çözümleyicisi — saf ayrıştırma, ağ YOK.
 *
 * Seçiciler 2026-10 itibarıyla çalışan bağımsız istemcilerden + cihaz kanıtlarından
 * (v17–v27 raporları) alınmıştır:
 *  - Aniyomi `yuzono/anime-extensions` (tr/tranimeizle)
 *  - CloudStream `UmayTrade/MyTV` (mytv-TrAnimeIzle)
 *  - site.js (22.09.2026 arşivi): fansubSources → sourceBtn → sourcePlayer/preVideoAd
 *
 * TASARIM KURALI: her alan için birden çok seçici denenir; hiçbiri tek başına zorunlu
 * değildir. Site teması değişse bile veri üretimi durmaz.
 */
internal object TrAnimeIzleSite {

    /** Oynatıcı iframe'lerinin sarıldığı ara yüz (site kendi "luffytra" katmanını kullanıyor). */
    const val LUFFY_MARKER = "embed2/?id="

    private val IMAGE_ATTRS = listOf("data-src", "data-original", "data-lazy-src", "src")

    private val AD_HOST_MARKERS = listOf(
        "doubleclick", "googlesyndication", "adsterra", "popads", "popcash", "propellerads",
        "onclickads", "adservice", "juicyads", "exoclick", "hilltopads", "clickadu",
        "mgid.com", "adtng", "tsyndicate", "trafficjunky", "betweendigital", "reklam"
    )

    /** Anime olmayan yollar (menü, liste, hesap sayfaları). */
    private val BLOCKED_SEGMENTS = setOf(
        "arama", "listeler", "animeizle", "profil", "giris", "giris-yap", "kayit", "kayit-ol",
        "uye", "uyelik", "hesap", "takvim", "fansub", "fansublar", "bolumler", "anime",
        "kategori", "kategoriler", "sayfa", "api", "assets", "uploads", "images", "css", "js",
        "iletisim", "hakkimizda", "sss", "gizlilik", "kurallar", "dmca", "reklam", "yorumlar",
        "yorum", "oy", "puan", "random", "rastgele", "rapor"
    )

    // ------------------------------------------------------------------
    // URL yardımcıları
    // ------------------------------------------------------------------

    fun hostOf(url: String): String = try {
        (URI(url).host ?: "").lowercase().removePrefix("www.")
    } catch (_: Exception) {
        ""
    }

    /** Sitedeki göreli adresi mutlak adrese çevirir. */
    fun absolute(base: String, href: String?): String? {
        val raw = href?.trim() ?: return null
        if (raw.isEmpty() || raw.startsWith("javascript") || raw.startsWith("#")) return null
        return try {
            val uri = URI(raw)
            if (uri.isAbsolute) {
                if (uri.scheme != "http" && uri.scheme != "https") null else raw
            } else {
                URI(base).resolve(raw).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isDirectMedia(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd") ||
            path.endsWith(".webm") || path.endsWith(".mkv") || path.contains(".m3u8") ||
            path.contains(".mp4?")
    }

    fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_HOST_MARKERS.any { lower.contains(it) }
    }

    /** Detay sayfası adresi mi? (`…-izle`, `…-izle-hd`) */
    fun isSeriesUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').trim('/').lowercase()
        if (path.isEmpty()) return false
        val last = path.substringAfterLast('/')
        if (TrAnimeIzleMatch.episodeFromUrl(url) != null) return false
        return last.endsWith("-izle") || last.endsWith("-izle-hd") ||
            last.endsWith("-anime-izle") || !last.contains("-bolum")
    }

    // ------------------------------------------------------------------
    // Görsel
    // ------------------------------------------------------------------

    fun imageUrl(img: Element?): String? {
        if (img == null) return null
        for (attr in IMAGE_ATTRS) {
            val value = img.attr(attr).trim()
            if (value.isEmpty() || value.startsWith("data:")) continue
            if (value.contains("placeholder") || value.contains("no-image") || value.endsWith(".svg")) continue
            return img.absUrl(attr).ifBlank { value }
        }
        return null
    }

    private fun posterOf(el: Element): String? =
        imageUrl(el.selectFirst("img") ?: el.parents().firstOrNull { it.selectFirst("img") != null }
            ?.selectFirst("img"))

    // ------------------------------------------------------------------
    // Kartlar (arama + ana sayfa + katalog yedeği)
    // ------------------------------------------------------------------

    private fun titleOf(el: Element): String? {
        val candidates = listOfNotNull(
            el.selectFirst("h4")?.text(),
            el.selectFirst("h3, h5, h2")?.text(),
            el.selectFirst("div.bar h4, div.bar .title, p.title, span.title, .anime-title")?.text(),
            el.attr("title").takeIf { it.isNotBlank() },
            el.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
        )
        for (candidate in candidates) {
            val cleaned = TrAnimeIzleMatch.cleanTitle(candidate) ?: continue
            if (!TrAnimeIzleMatch.isInvalidTitle(cleaned)) return cleaned
        }
        return null
    }

    private fun cardFromBlock(block: Element, base: String): TrCard? {
        // Kök öğe <a> ise (tema değişimi yedeği) kendi href'i kullanılır.
        val ownHref = if (block.tagName().equals("a", ignoreCase = true)) block.attr("href") else null
        val url = absolute(base, block.attr("data-href"))
            ?: absolute(base, ownHref)
            ?: absolute(base, block.selectFirst("a[href]")?.attr("href"))
            ?: return null
        if (hostOf(url).isEmpty()) return null
        // Kart çoğu zaman "son bölüm" adresini taşır; dizi sayfasına çevir.
        val seriesUrl = TrAnimeIzleMatch.toSeriesUrl(url) ?: url
        val name = titleOf(block) ?: TrAnimeIzleMatch.cleanTitle(TrAnimeIzleMatch.titleFromSlug(seriesUrl))
            ?: return null
        if (TrAnimeIzleMatch.isInvalidTitle(name)) return null
        val scoreText = block.selectFirst("rating")?.attr("data-src")
            ?: block.selectFirst(".rating, .score, span.puan")?.text()
        val score = scoreText?.replace(',', '.')?.let {
            Regex("\\d{1,2}(?:\\.\\d)?").find(it)?.value?.toDoubleOrNull()
        }?.takeIf { it in 0.1..10.0 }
        val text = block.text()
        return TrCard(
            name = name,
            url = seriesUrl,
            poster = posterOf(block),
            score = score,
            episodeHint = TrAnimeIzleMatch.episodeOf(text) ?: TrAnimeIzleMatch.episodeFromUrl(url),
            seasonHint = TrAnimeIzleMatch.seasonOf(text)
        )
    }

    /**
     * Arama/liste sayfasındaki kartlar.
     *
     * Sıra: bilinen `div.flx-block` yapısı → genel bağlantı taraması. Aynı animenin
     * bölüm varyantları [TrAnimeIzleMatch.seriesKey] ile tekilleştirilir; böylece
     * ana sayfadaki "son bölüm" kartları aynı animenin onlarca kopyasını üretmez.
     */
    fun parseCards(doc: Document, base: String, limit: Int = 60): List<TrCard> {
        val out = LinkedHashMap<String, TrCard>()
        fun take(block: Element) {
            if (out.size >= limit) return
            val card = cardFromBlock(block, base) ?: return
            val key = TrAnimeIzleMatch.seriesKey(card.url)
            if (key.isEmpty()) return
            val existing = out[key]
            if (existing == null) {
                out[key] = card
            } else if ((existing.episodeHint ?: 0) < (card.episodeHint ?: 0)) {
                // Aynı seri: daha ileri bölümü olan kart seri sayfasını da taşır.
                out[key] = existing.copy(episodeHint = card.episodeHint, seasonHint = card.seasonHint ?: existing.seasonHint)
            }
        }

        for (block in doc.select("div.flx-block, .flx-block")) take(block)
        if (out.size < 3) {
            for (a in doc.select("a[href]")) {
                if (out.size >= limit) break
                val href = a.attr("href")
                if (href.isEmpty() || href.startsWith("#")) continue
                val url = absolute(base, href) ?: continue
                if (hostOf(url).isEmpty()) continue
                val path = url.substringBefore('?').lowercase()
                val looksLikeContent = path.contains("-bolum-izle") ||
                    path.contains("-bolum") || path.contains("-anime-izle") ||
                    path.endsWith("-izle") || path.endsWith("-izle-hd") ||
                    path.contains("/anime/")
                if (!looksLikeContent) continue
                if (a.selectFirst("img") == null) continue
                val segment = path.substringAfter("://").substringAfter("/").substringBefore("/")
                if (segment in BLOCKED_SEGMENTS) continue
                take(a)
            }
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------
    // Detay sayfası
    // ------------------------------------------------------------------

    private fun firstText(doc: Document, vararg selectors: String): String? {
        for (selector in selectors) {
            val text = doc.selectFirst(selector)?.text()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun meta(doc: Document, vararg selectors: String): String? {
        for (selector in selectors) {
            val value = doc.selectFirst(selector)?.attr("content")?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun infoValue(doc: Document, label: String): String? = firstText(
        doc,
        "dd:contains($label) + dt",
        "dt:contains($label) + dd",
        "div.col-sm-7 dd:contains($label) + dt",
        "span.dataTitle:contains($label) + span"
    )

    fun parseDetail(doc: Document, base: String, pageUrl: String): TrDetail {
        val rawTitle = firstText(
            doc,
            "div.playlist-title h1",
            "h1.playlist-title",
            "div.animeDetail-title h1",
            "h1.title",
            "h1"
        ) ?: meta(doc, "meta[property=og:title]") ?: doc.title()

        val title = TrAnimeIzleMatch.cleanTitle(rawTitle)
            ?: TrAnimeIzleMatch.cleanTitle(TrAnimeIzleMatch.titleFromSlug(pageUrl))
            ?: "TrAnimeIzle"

        val poster = imageUrl(
            doc.selectFirst("div.poster img, div.poster .social-icon img, div.animeDetail-poster img, img.anime-poster")
                ?: doc.selectFirst("article img, .detail img")
        ) ?: meta(doc, "meta[property=og:image]", "meta[property=og:image:secure_url]", "meta[itemprop=image]")

        val plot = firstText(
            doc,
            "div.p-10 p",
            "div.animeDetail-description",
            "div.description",
            "div.ozet",
            "p.text-muted"
        ) ?: meta(doc, "meta[name=description]", "meta[property=og:description]")

        val tags = LinkedHashSet<String>()
        doc.select("div.tags-inner a, a.genre, a[href*=/animeizle/]").forEach { el ->
            val text = el.text().trim()
            if (text.length in 2..40 && !TrAnimeIzleMatch.isInvalidTitle(text) &&
                !text.contains("izle", ignoreCase = true)
            ) tags.add(text)
        }

        val year = infoValue(doc, "Yapım Yılı")?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }
        val score = firstText(doc, "span.dt_rating_vgs", "div.rating span", "span.imdb-point")
            ?.let { Regex("\\d{1,2}(?:[.,]\\d)?").find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }
            ?.takeIf { it in 0.1..10.0 }
        val typeText = infoValue(doc, "Anime Tipi") ?: infoValue(doc, "Tür")
        val totalEpisodes = infoValue(doc, "Bölüm Sayısı")?.substringBefore("/")?.trim()?.toIntOrNull()
        val fansubs = doc.select("dd:contains(Fansublar) + dt a, div.fansub-list a").map { it.text().trim() }
            .filter { it.isNotBlank() }

        return TrDetail(
            title = title,
            poster = poster,
            plot = plot?.take(4000),
            tags = tags.toList(),
            year = year,
            score = score,
            typeText = typeText,
            totalEpisodes = totalEpisodes,
            fansubs = fansubs,
            episodes = parseEpisodes(doc, base, pageUrl, title)
        )
    }

    /**
     * Bölüm listesi. Site bölümleri üç ayrı yerde tutabiliyor
     * (`animeDetail-items`, `animeDetail-playlist`, `div.episode-li`); hepsi taranır.
     *
     * GARANTİ: dönen her bölümde `number` ve `season` doludur — Kitsugi'nin bölüm
     * eşleştiricisi (CsEpisodeMatcher) ikisi de olmayan bölümü eşleştiremez.
     */
    fun parseEpisodes(doc: Document, base: String, pageUrl: String, pageTitle: String? = null): List<TrEpisode> {
        val pageKey = TrAnimeIzleMatch.seriesKey(pageUrl)
        val pageSeason = TrAnimeIzleMatch.seasonOf(pageTitle ?: "") ?: 1
        val found = LinkedHashMap<Int, TrEpisode>()
        var running = 1

        fun consider(anchor: Element, forcedNumber: Int? = null, explicitUrl: String? = null) {
            val url = explicitUrl ?: absolute(base, anchor.attr("href")) ?: return
            if (!url.startsWith("http")) return
            val rawName = firstNonNull(
                anchor.selectFirst("div.etitle span")?.text(),
                anchor.selectFirst("p.title")?.text(),
                anchor.selectFirst("span.title")?.text(),
                anchor.selectFirst("span")?.text(),
                anchor.text()
            ) ?: return
            val number = forcedNumber
                ?: TrAnimeIzleMatch.episodeOf(rawName)
                ?: TrAnimeIzleMatch.episodeFromUrl(url)
                ?: running
            if (number <= 0) return
            val key = TrAnimeIzleMatch.seriesKey(url)
            // Sayfa bir seri sayfasıysa yalnızca o seriye ait bölümler alınır.
            if (pageKey.isNotEmpty() && key.isNotEmpty() && key != pageKey &&
                TrAnimeIzleMatch.episodeFromUrl(url) != null
            ) {
                // Farklı seri (öneri/yan liste) → atla.
                if (TrAnimeIzleMatch.score(pageKey.replace('-', ' '), key.replace('-', ' ')) < 0.6) return
            }
            if (found.containsKey(number)) return
            val cleaned = TrAnimeIzleMatch.cleanTitle(rawName)
            val date = anchor.selectFirst("small.author, small, time, .date")?.text()?.trim()
            found[number] = TrEpisode(
                url = url,
                number = number,
                season = TrAnimeIzleMatch.seasonOf(rawName) ?: pageSeason,
                name = cleaned?.takeIf { it.length in 2..120 } ?: "$number. Bölüm",
                date = date,
                poster = imageUrl(anchor.selectFirst("img"))
            )
            running = number + 1
        }

        val containers = doc.select(
            "div.animeDetail-items li a, div.animeDetail-items ol a, div.animeDetail-playlist li a, " +
                "div.episode-list li a, li:has(div.episode-li) a"
        )
        for (anchor in containers) consider(anchor)

        // v40 — ÖLÇÜM: sitenin kendi istemci kodu (`/js/site.js`, arşiv
        // 31.08.2026) bölüm kartlarını şöyle açıyor:
        //     $(".episodeBtn").click(function(){ window.location.href =
        //         "/" + $(this).data("slug"); });
        // Yani kart bir `<a href>` DEĞİL, `data-slug` taşıyan bir öğe olabilir.
        // Eskiden bu kartlar hiç görülmüyordu → bölüm listesi eksik geliyordu.
        for (el in doc.select(".episodeBtn[data-slug]")) {
            val slug = el.attr("data-slug").trim()
            if (slug.isEmpty()) continue
            val url = absolute(base, "/" + slug.trimStart('/')) ?: continue
            consider(el, explicitUrl = url)
        }

        if (found.isEmpty()) {
            // Tema değişmiş olabilir: bölüm URL desenine göre genel tarama.
            for (anchor in doc.select("a[href]")) {
                // Hidden nofollow links on detail pages are not episodes.
                if (anchor.closest("[aria-hidden=true], [hidden]") != null) continue
                val url = absolute(base, anchor.attr("href")) ?: continue
                if (TrAnimeIzleMatch.episodeFromUrl(url) == null) continue
                if (hostOf(url).isEmpty()) continue
                consider(anchor)
            }
        }
        return found.values.sortedBy { it.number }
    }

    private fun firstNonNull(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    // ------------------------------------------------------------------
    // Oynatıcı zinciri (saf metin işlemleri)
    // ------------------------------------------------------------------

    /** `input#EpisodeId` → yoksa `animeWatch.initialize(aId, eId, ...)` script çağrısı. */
    fun episodeIdFrom(html: String, doc: Document? = null): Int? {
        val parsed = doc ?: runCatching { org.jsoup.Jsoup.parse(html) }.getOrNull()
        parsed?.selectFirst("input#EpisodeId")?.attr("value")?.toIntOrNull()?.let { return it }
        parsed?.selectFirst("input[name=EpisodeId]")?.attr("value")?.toIntOrNull()?.let { return it }
        Regex("animeWatch\\s*\\.\\s*initialize\\s*\\(\\s*\\d+\\s*,\\s*(\\d+)")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("EpisodeId\"?\\s*[:=]\\s*\"?(\\d{2,7})")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("data-eid=[\"']?(\\d{2,7})")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /** Bölüm sayfasındaki fansub seçicileri. */
    fun fansubsFrom(doc: Document): List<TrFansub> {
        val choices = doc.select("li.fansubSelector, div.fansubSelector, a.fansubSelector")
            .ifEmpty { doc.select("[data-fid]") }
            .mapNotNull { el ->
                val id = el.attr("data-fid").toIntOrNull() ?: return@mapNotNull null
                val label = el.attr("data-fad").ifBlank { el.text().trim() }.ifBlank { "Fansub $id" }
                TrFansub(id, label)
            }.toMutableList()
        // The page also declares the default fansub in animeWatch.initialize.
        // Keep it even if there is no selector (or other selectors are present).
        val init = Regex("""animeWatch\s*\.\s*initialize\s*\(\s*\d+\s*,\s*\d+\s*,\s*(\d+)""")
            .find(doc.html())
        val id = init?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (id != null && id > 0 && choices.none { it.id == id }) choices.add(TrFansub(id, "Fansub $id"))
        return choices.distinctBy { it.id }
    }

    /** `fansubSources` cevabındaki kaynak butonları (JSON sarmalı da açılır). */
    fun sourcesFrom(body: String): List<TrSource> {
        val html = unwrapJsonHtml(body)
        val doc = runCatching { org.jsoup.Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val raw = doc.select("li.sourceBtn, a.sourceBtn, div.sourceBtn")
            .ifEmpty {
                doc.select("[data-id]").filter { it.attr("data-id").isNotBlank() }
            }
        return raw.mapNotNull { el ->
            val id = el.attr("data-id").trim()
            if (id.isEmpty() || id.any { it.isWhitespace() }) return@mapNotNull null
            val label = el.selectFirst("p.title")?.text()?.trim()
                ?: el.selectFirst("p, span, strong")?.text()?.trim()
                ?: el.text().trim()
            TrSource(
                id = id,
                episodeId = el.attr("data-eid").toIntOrNull(),
                label = label.ifBlank { "Kaynak $id" }
            )
        }.distinctBy { it.id }
    }

    /** `{"source": "<html>"}` / `{"data": "<html>"}` / düz HTML → HTML metni. */
    fun unwrapJsonHtml(body: String?): String {
        val text = body?.trim() ?: return ""
        if (!text.startsWith("{")) return text
        for (key in listOf("source", "data", "html", "content")) {
            val value = runCatching {
                val obj = org.json.JSONObject(text)
                if (obj.has(key)) obj.optString(key, "") else ""
            }.getOrDefault("")
            if (value.isNotBlank() && value.contains('<')) {
                return value.replace("\\/", "/")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "")
            }
        }
        return ""
    }

    /** Oynatıcı HTML'indeki iframe adresleri (mutlak, reklamlar ayıklanmış). */
    fun iframesFrom(html: String, base: String): List<String> {
        val doc = runCatching { org.jsoup.Jsoup.parse(html, base) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { frame ->
            val raw = frame.attr("src").ifBlank { frame.attr("data-src") }
                .ifBlank { frame.attr("data-lazy-src") }
            if (raw.isBlank() || raw.startsWith("javascript") || raw.startsWith("about:")) return@forEach
            val url = absolute(base, raw) ?: return@forEach
            if (isAdUrl(url)) return@forEach
            out.add(url)
        }
        return out.toList()
    }

    /**
     * Oynatıcı HTML'indeki **doğrudan** medya adresleri (m3u8/mp4/mpd).
     * Gömülü kaçış karakterleri (`\/`, `\u0026`) temizlenir; mutlaklaştırılır.
     */
    fun directMediaFrom(html: String, base: String): List<String> {
        val unescaped = html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val out = LinkedHashSet<String>()
        val doc = org.jsoup.Jsoup.parse(unescaped, base)
        doc.select("video[src], source[src]").forEach { element ->
            val url = absolute(base, element.attr("src"))
            if (url != null && isDirectMedia(url) && !isAdUrl(url)) out.add(url)
        }
        val patterns = listOf(
            Regex("""["'](https?://[^"'\s]+?\.m3u8[^"'\s]*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"'\s]+?\.mpd[^"'\s]*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"'\s]+?\.mp4[^"'\s]*)["']""", RegexOption.IGNORE_CASE),
            Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:src|source|url)\s*:\s*["']([^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(unescaped)) {
                val raw = match.groupValues.lastOrNull()?.trim().orEmpty()
                if (raw.isEmpty() || raw.startsWith("data:") || raw.startsWith("blob:")) continue
                val url = absolute(base, raw) ?: continue
                if (isAdUrl(url)) continue
                out.add(url)
            }
        }
        return out.toList()
    }

    /** `<track>` ve `.vtt/.srt` adresleri → (dil, adres). */
    fun subtitleTracks(html: String, base: String): List<Pair<String, String>> {
        val doc = runCatching { org.jsoup.Jsoup.parse(html, base) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<Pair<String, String>>()
        doc.select("track[src]").forEach { track ->
            val kind = track.attr("kind").ifBlank { "subtitles" }
            if (!kind.equals("subtitles", true) && !kind.equals("captions", true)) return@forEach
            val src = absolute(base, track.attr("src")) ?: return@forEach
            val lang = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Altyazı" }
            out.add(lang to src)
        }
        val unescaped = html.replace("\\/", "/")
        for (m in Regex("""["'](https?://[^"'\s]+?\.(?:vtt|srt))["']""", RegexOption.IGNORE_CASE).findAll(unescaped)) {
            val src = absolute(base, m.groupValues[1]) ?: continue
            out.add("Altyazı" to src)
        }
        return out.toList()
    }

    /**
     * Site kendi oynatıcısını `…/embed2/?id={videoId}` katmanıyla sarıyor.
     * Hem ham video kimliği hem de tam iframe adresi denenebilsin diye ikisi döner.
     */
    fun embedCandidates(iframeUrl: String): List<String> {
        val id = iframeUrl.substringAfter(LUFFY_MARKER, "")
        return if (id.isBlank()) listOf(iframeUrl) else listOf(id, iframeUrl)
    }

    /**
     * `…/embed2/?id=<gerçek adres>` sarmalını açar (luffytra2 / "luffy" katmanı).
     *
     * Sitedeki `sourcePlayer` cevabı HER ZAMAN bu sarmalı taşır; `id` parametresi
     * çoğu zaman gerçek video hostu adresidir (ok.ru, sibnet, voe, sendvid…),
     * bazen de yalnızca bir kimlik olur. Kimlik ise `null` döner ve çağıran
     * iframe'in kendisini dener.
     *
     * Kural: `&` ile kesilmez — gerçek adresin kendi sorgu parametreleri
     * (`?x=1&y=2`) korunmalı. Hem ham hem URL-çözülmüş biçim denenir.
     */
    fun embedInnerUrl(url: String): String? {
        if (url.isBlank()) return null
        val index = url.indexOf(LUFFY_MARKER, ignoreCase = true)
        if (index < 0) return null
        val tail = url.substring(index + LUFFY_MARKER.length)
            .substringBefore('"')
            .substringBefore('\'')
            .substringBefore('<')
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
        if (tail.isEmpty()) return null
        val decoded = try {
            URLDecoder.decode(tail.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            tail
        }
        for (candidate in listOf(tail, decoded)) {
            val value = if (candidate.startsWith("//")) "https:$candidate" else candidate
            // DİKKAT: yalnızca "http" ile başlamak YETMEZ — URL-encoded değer
            // ("https%3A%2F%2F…") de "https" ile başlar; şema ayırıcısı şart.
            if (value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
            ) return value
        }
        return null
    }

    /**
     * `downloadru2.php?url=<base64>` köprüsü — Aitr'nin indirme yönlendiricisi.
     * Base64 çözülünce gerçek host adresi çıkar (ör. `https://voe.sx/e/…`).
     *
     * `android.util.Base64` KULLANILMAZ: bu nesne birim testlerinde (saf JVM)
     * ve API 26 altı cihazlarda da çalışmak zorunda.
     */
    fun downloadBridgeUrl(url: String): String? {
        if (url.isBlank()) return null
        if (!url.contains("downloadru", ignoreCase = true)) return null
        val raw = Regex("""[?&]url=([^&"'\s<>]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1) ?: return null
        val decoded = base64Decode(raw)?.trim() ?: return null
        if (!decoded.startsWith("http://", ignoreCase = true) &&
            !decoded.startsWith("https://", ignoreCase = true)
        ) return null
        if (isAdUrl(decoded)) return null
        return decoded
    }

    /** Standart + URL-güvenli base64 çözücü (padding toleranslı, saf Kotlin). */
    fun base64Decode(raw: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        var buffer = 0
        var bits = 0
        val out = StringBuilder()
        for (ch in raw.trim()) {
            if (ch == '=') break
            if (ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') continue
            val normalized = when (ch) {
                '-' -> '+'
                '_' -> '/'
                else -> ch
            }
            val value = alphabet.indexOf(normalized)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return if (out.isEmpty()) null else out.toString()
    }

    /**
     * AitrVip / OpTraco oynatıcı sayfasındaki medya adresi.
     * Sayfa her zaman `file: "…"` yazar; göreli değer (`/hls/…m3u8`) base'e göre çözülür.
     */
    fun optracoMediaUrl(page: String, base: String): String? {
        if (page.isBlank()) return null
        val text = page.replace("\\/", "/").replace("&amp;", "&")
        val patterns = listOf(
            Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']([^"']*\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (raw.isEmpty() || raw.startsWith("data:") || raw.startsWith("blob:")) continue
                val resolved = absolute(base, raw) ?: continue
                if (!resolved.startsWith("http")) continue
                if (isAdUrl(resolved)) continue
                return resolved
            }
        }
        return null
    }

    /**
     * Oynatıcı gövdesinden çıkan **tüm** hedef adresler — sarmallar AÇILMIŞ hâlde.
     *
     * Sıra: iframe'ler (ve varsa `id=` iç adresi) → doğrudan medya → gövdedeki
     * tanınan host adresleri. `downloadru2` ve `embed2` sarmalları burada
     * çözülür; böylece akış katmanı tek bir liste üzerinden çalışır.
     */
    fun playerTargets(html: String, base: String, limit: Int = 64): List<String> {
        if (html.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (frame in iframesFrom(html, base)) {
            embedInnerUrl(frame)?.let { out.add(it) }
            downloadBridgeUrl(frame)?.let { out.add(it) }
            out.add(frame)
        }
        for (media in directMediaFrom(html, base)) out.add(media)
        val unescaped = html.replace("\\/", "/")
        for (match in Regex("""https?://[^"'\s<>\\]+""").findAll(unescaped)) {
            if (out.size >= limit * 3) break
            val url = match.value.trimEnd('"', '\'', ')', ',', ';')
            if (isAdUrl(url)) continue
            embedInnerUrl(url)?.let { out.add(it) }
            downloadBridgeUrl(url)?.let { out.add(it) }
        }
        return out.take(limit)
    }

    /** Üzerinde `data-fid` bulunan, sayfadaki ilk fansub (WebView DOM'u için). */
    fun firstFansubElement(doc: Document): Element? =
        doc.selectFirst("li.fansubSelector, [data-fid]")
}
