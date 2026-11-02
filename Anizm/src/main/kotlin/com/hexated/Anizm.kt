package com.hexated

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Anizm — puffytr.com (eski/paralel adlar: anizm.net, anizm.com.tr, anizle.org).
 *
 * ## Bu sürümün amacı (v3, 2026-10-25)
 * Önceki sürüm, sitenin **tek bir tema varsayımına** dayanıyordu: arama `/arama?q=`,
 * kaynaklar `button[data-v3-translator]`, oynatıcı yalnızca `videoSource`. Site bu
 * üç noktadan biri değiştiğinde arama 0 sonuç, kaynak listesi boş dönüyordu
 * (kullanıcı ekranı: "Akış bulunamadı").
 *
 * Yeni sürüm **kanıtlanmış iki istemcinin** zincirini birlikte uygular:
 *  - Aniyomi `src/tr/anizm` (2026-10-25): `/anime-izle?sayfa=N`, `div#episodesMiddle
 *    div.posterBlock > a`, `h2.anizm_pageTitle`, `div.infoPosterImg > img`,
 *    `div.episodeListTabContent div > a`, `div#fansec > a[translator]`,
 *    `a.videoPlayerButtons[video]`, `/video/` → `/player/` 302,
 *    anizmplayer = `POST /player/index.php?data=<hash>&do=getVideo` → `securedLink`.
 *  - CloudStream `SalooRepo/Anizm` (2026-10-15) + `hexated` orijinali:
 *    `/searchAnime?query=..&page=..&type=detailed&limit=..` (XHR başlıkları şart),
 *    `/getAnimeListForSearch` (tüm katalog, JSON), `/fullViewSearch?search=`.
 *
 * Zincir üç kuşak API'yi birlikte dener (hangisi canlıysa o kullanılır):
 *  1. **v3**: `[data-sources]` → çevirmen JSON'u → `/video/{id}` → `url`
 *  2. **v2**: `a[translator]` → `{data: html}` → `a[video|videoPlayerButtons]` → `{player: iframe}`
 *  3. **sayfa**: doğrudan `iframe[src]`, `a[video]`, `<video>` ve JS içindeki m3u8/mp4
 *
 * ## +18 / +16 yaş uyarısı
 * Bölüm ve detay sayfalarındaki `<dialog data-rating="18">` **yalnızca istemci
 * tarafında** çalışır (`v3-content-warning.js` v1.0.5: `sessionStorage`
 * `anizm.v3.content-warning.<animeId>`). Sunucu onay beklemez: bölüm sayfası ve
 * `/bolum/{ep}/cevirmen/{cid}` ucu onay verilmeden de HTTP 200 döner.
 * Eklenti WebView kullanmadığı ve yalnızca HTTP ile çalıştığı için uyarı
 * **hiç görünmez**; ayrıştırma sırasında diyalog düğümü atlanır
 * ([AnizmApi.parseTranslators]).
 *
 * ## Uygulama (Kitsugi) uyumu
 *  - Arama, uygulamanın gönderdiği İngilizce/Türkçe başlıkları [AnizmMatch] ile
 *    çoklu varyanta çevirir ve sonuçları benzerliğe göre sıralar (site Türkçe/romanize
 *    ad kullandığı için ham arama sıfır dönüyordu).
 *  - Bölümlere `episode`/`season` numarası **her zaman** yazılır
 *    (Kitsugi `CsEpisodeMatcher` numarasız bölümü eşleştiremiyor).
 *  - `loadLinks` yalnızca **oynatılabilir** adres yayınlar (m3u8/mp4 ya da
 *    `loadExtractor`'ın çözdüğü host). Oynatıcı sayfası URL'si asla akış olarak
 *    verilmez — Kitsugi bunu embed tarayıcıya sokuyor ve cihazda
 *    `EmbedMediaScanner` kaynaklı KTS-CSP-9110'a düşebiliyor.
 *  - Hiçbir adım istisna fırlatmaz; hata = boş liste + log (uygulama çökmez).
 */
class Anizm : MainAPI(), AnizmHttp {

    override var mainUrl = DEFAULT_BASE
    override var name = "Anizm"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val hasDownloadSupport = true

    /** mainPage URI'leri kurulum anındaki domain ile üretilir; domain değişirse haritalanır. */
    private val mainPageBase: String = mainUrl

    override val mainPage = mainPageOf(
        // Ana sayfanın GERÇEK bölümleri (02.11.2026 ölçümü:
        // `section#populer` 19, `section#son-bolumler` 18, `section#yeni-seriler` 20 kart).
        // `#…` parçalı adresler "bölüm" olarak okunur — sayfalama yoktur.
        "$mainUrl/#populer" to "Popüler",
        "$mainUrl/#son-bolumler" to "Son Bölümler",
        "$mainUrl/yeniler?sayfa={page}" to "Son Eklenenler",
        "$mainUrl/#yeni-seriler" to "Yeni Seriler",
        // Türler: site kategorileri ALFABETİK sıralıyor (`.hack//…`, `#Zombie`,
        // `16bit…` ile başlıyordu). Kartlarda puan var → satırlar puana göre
        // dizilir, "en iyiler" görünür.
        "$mainUrl/kategoriler/2?sayfa={page}" to "Aksiyon",
        "$mainUrl/kategoriler/1?sayfa={page}" to "Macera",
        "$mainUrl/kategoriler/3?sayfa={page}" to "Komedi",
        "$mainUrl/kategoriler/4?sayfa={page}" to "Dram",
        "$mainUrl/kategoriler/13?sayfa={page}" to "Fantastik",
        "$mainUrl/kategoriler/8?sayfa={page}" to "Bilim Kurgu",
        "$mainUrl/kategoriler/28?sayfa={page}" to "Romantizm",
        "$mainUrl/kategoriler/34?sayfa={page}" to "Shounen",
        "$mainUrl/temalar/3?sayfa={page}" to "Isekai",
    )

    /**
     * Laravel CSRF belirteci (bölüm sayfasındaki `meta[name=csrf-token]`).
     * v3 kaynak uçları `/bolum/{ep}/cevirmen/{id}` ve `/video/{id}` bu başlığı
     * bekliyor; yoksa HTML kabuğu/403 dönebiliyor.
     */
    @Volatile
    private var csrfToken: String? = null

    private val csrfRegex = Regex(
        """<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)""",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------------
    // HTTP katmanı
    // ------------------------------------------------------------------

    private fun headers(referer: String = mainUrl + "/", xhr: Boolean = false): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        put("Referer", referer)
        if (xhr) {
            put("Accept", "application/json, text/javascript, */*; q=0.01")
            put("X-Requested-With", "XMLHttpRequest")
            put("sec-fetch-dest", "empty")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-site", "same-origin")
            csrfToken?.let { put("X-CSRF-TOKEN", it) }
        } else {
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            put("Upgrade-Insecure-Requests", "1")
        }
    }

    /**
     * Token **her** HTML yanıtında tazelenir. Eski sürüm yalnızca ilk kez
     * (`csrfToken == null`) okuyordu; oturum döndüğünde token bayatlıyor ve
     * `/bolum/{ep}/cevirmen/{cid}` çağrısı 419 dönüyordu → "kaynak yok".
     */
    private fun rememberCsrf(html: String) {
        csrfRegex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
            csrfToken = it
        }
    }

    /** Sayfayı tazeleyip token'ı yeniden okur (419 kurtarma). */
    private suspend fun refreshCsrf(referer: String) {
        csrfToken = null
        try {
            val page = app.get(
                mainUrl + "/",
                headers = headers(mainUrl + "/", xhr = false),
                timeout = 8
            )
            if (page.isSuccessful) rememberCsrf(page.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(LOG_TAG, "CSRF tazeleme hatası: ${e.message}")
        }
    }

    /** GET — hata/timeout durumunda null döner (akışı boğmaz). */
    private suspend fun getText(
        url: String,
        referer: String = mainUrl + "/",
        xhr: Boolean = false,
        timeout: Long = 12L
    ): String? {
        return try {
            var response = app.get(url, headers = headers(referer, xhr), timeout = timeout)
            // Laravel oturumu dönerse token bayatlar (419). Sayfa bir kez yenilenip
            // taze token ile istek tekrarlanır — yoksa kaynak listesi sessizce boş kalır.
            if (response.code == 419 && xhr) {
                Log.w(LOG_TAG, "CSRF bayat (419), token tazeleniyor: ${url.take(80)}")
                refreshCsrf(referer)
                response = app.get(url, headers = headers(referer, xhr), timeout = timeout)
            }
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "GET ${response.code}: ${url.take(120)}")
                null
            } else {
                val text = response.text
                rememberCsrf(text)
                text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "GET hatası (${e.javaClass.simpleName}): ${url.take(120)}")
            null
        }
    }

    /** POST — JSON gövde, form gövde veya boş gövde. */
    private suspend fun postText(
        url: String,
        referer: String,
        xhr: Boolean = true,
        json: String? = null,
        form: Map<String, String>? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeout: Long = 12L
    ): String? {
        val merged = headers(referer, xhr) + extraHeaders
        return try {
            val response = when {
                json != null -> app.post(url, headers = merged, json = JsonAsString(json), timeout = timeout)
                form != null -> app.post(url, headers = merged, data = form, timeout = timeout)
                else -> app.post(url, headers = merged, timeout = timeout)
            }
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "POST ${response.code}: ${url.take(120)}")
                null
            } else {
                response.text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "POST hatası (${e.javaClass.simpleName}): ${url.take(120)}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Domain çözümleme (site adı sık değişiyor + .com/.com.tr ikizleri var)
    // ------------------------------------------------------------------

    private fun looksBlocked(html: String?): Boolean {
        if (html.isNullOrBlank()) return true
        val title = try {
            Jsoup.parse(html).selectFirst("title")?.text() ?: ""
        } catch (_: Exception) {
            ""
        }
        val lower = title.lowercase()
        return lower.contains("just a moment") ||
            lower.contains("access denied") ||
            lower.contains("attention required") ||
            lower.contains("checking your browser") ||
            lower.contains("bot doğrulama") ||
            lower.contains("403 forbidden")
    }

    /**
     * Sayfa gerçekten site mi (park/duvar sayfası değil mi)?
     *
     * v3 işaretlemesi `article.v3-catalog-card` / `a.v3-catalog-poster` /
     * `section.v3-*` kullanıyor; eski seçiciler yalnızca v2 kuşağında vardı ve
     * sağlıklı bir v3 sayfasında **hiç eşleşmiyordu** (bu yüzden prob canlı
     * domaini "ölü" sanıp gereksiz yere tüm adayları deniyordu).
     */
    private fun pageHasContent(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        return try {
            val doc = Jsoup.parse(html)
            val v3 = doc.select(
                "article.v3-catalog-card, article.v3-series-card, article.v3-episode-card, " +
                    "a.v3-catalog-poster, [data-v3-slider], .v3-catalog-grid, .v3-page-shell"
            ).size
            val legacy = doc.select(
                "a[href*='-bolum'], div.posterBlock, div.anime-card, a.slideAnimeLink, " +
                    "div.cat-card, div#episodesMiddle, div.ui.card"
            ).size
            v3 >= 3 || legacy >= 3 || html.contains("v3-catalog") || html.contains("v3-series")
        } catch (_: Exception) {
            false
        }
    }

    @Volatile
    private var lastBaseProbeAt = 0L

    @Volatile
    private var baseResolved = false

    /**
     * Yaşayan domaini belirler. Mevcut domain sağlıklıysa **tek** istek atar ve
     * sonucu [BASE_TTL_MS] boyunca hatırlar — her aramada ek gecikme olmaz.
     */
    private suspend fun ensureBase(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && baseResolved && now - lastBaseProbeAt < BASE_TTL_MS) return
        lastBaseProbeAt = now
        baseResolved = true

        if (probe(mainUrl)) return

        for (candidate in BASE_CANDIDATES) {
            if (candidate == mainUrl) continue
            if (probe(candidate)) {
                Log.i(LOG_TAG, "Domain değişti: $mainUrl → $candidate")
                mainUrl = candidate
                return
            }
        }
        Log.w(LOG_TAG, "Canlı domain bulunamadı; $mainUrl ile devam ediliyor")
    }

    private suspend fun probe(base: String): Boolean {
        val html = try {
            app.get(base + "/", headers = headers(base + "/"), timeout = 6).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(LOG_TAG, "domain prob hatası: $base (${e.javaClass.simpleName})")
            null
        }
        val ok = !looksBlocked(html) && pageHasContent(html)
        Log.d(LOG_TAG, "domain prob: $base → ${if (ok) "CANLI" else "ölü"}")
        return ok
    }

    private fun remapBase(url: String): String =
        if (url.startsWith(mainPageBase)) mainUrl + url.substring(mainPageBase.length) else url

    // ------------------------------------------------------------------
    // Arama
    // ------------------------------------------------------------------

    override suspend fun search(query: String, page: Int): SearchResponseList {
        return try {
            searchInternal(query, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "search('$query') hatası: ${e.javaClass.simpleName}: ${e.message}")
            newSearchResponseList(emptyList(), false)
        }
    }

    private suspend fun searchInternal(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.length < 2) return newSearchResponseList(emptyList(), false)
        ensureBase()

        val variants = AnizmMatch.queryVariants(trimmed).ifEmpty { listOf(trimmed) }
        Log.d(LOG_TAG, "search('$trimmed') varyantlar: $variants")

        val collected = LinkedHashMap<String, AnizmCard>()
        var hasNext = false

        fun merge(card: AnizmCard) {
            val existing = collected[card.url]
            if (existing == null) {
                collected[card.url] = card
            } else if (card.altNames.isNotEmpty()) {
                collected[card.url] = existing.copy(
                    altNames = (existing.altNames + card.altNames).distinct(),
                    poster = existing.poster ?: card.poster,
                    score = existing.score ?: card.score
                )
            }
        }

        // 1) Sunucu tarafı JSON araması (en isabetli yol).
        for (variant in variants.take(2)) {
            val cards = searchServer(variant, page)
            if (cards.isNotEmpty()) {
                cards.forEach(::merge)
                if (cards.size >= SEARCH_PAGE_SIZE) hasNext = true
            }
            if (collected.size >= 6) break
        }

        // 1.5) `/arama-onerileri` — sitenin kendi canlı öneri ucu (2026-10 fixture:
        //      {"results":[{"title","year","url"}]}). Poster döndürmez ama TAM
        //      slug verir → yanlış eşleşme riski yok.
        if (collected.size < 6) {
            for (variant in variants.take(2)) {
                searchSuggestions(variant).forEach(::merge)
                if (collected.size >= 6) break
            }
        }

        // 2) HTML arama sayfası — canlı sitede doğrulandı (25.10.2026):
        //    `/arama?q=one+piece` gerçek sonuç kartları döndürüyor (küçük, hızlı).
        if (collected.size < 8) {
            for (variant in variants.take(3)) {
                val cards = searchHtml(variant)
                cards.forEach(::merge)
                if (collected.size >= 10) break
            }
            Log.d(LOG_TAG, "HTML arama sonrası: ${collected.size} kart")
        }

        // 2.5) v5: İngilizce başlık ↔ sitenin romanize adı.
        //      "Attack on Titan" → Kitsu alternatif adları ("Shingeki no Kyojin")
        //      → bunlarla sunucu/HTML araması. Kullanıcının "aradığım anime
        //      çıkmıyor" şikâyetinin ana nedeni buydu.
        val synonyms = if (page <= 1) titlesFromKitsu(trimmed) else emptyList()
        if (collected.size < 3 && synonyms.isNotEmpty()) {
            for (alt in synonyms.take(4)) {
                if (collected.size >= 8) break
                if (variants.any { it.equals(alt, ignoreCase = true) }) continue
                searchServer(alt, 1).forEach(::merge)
                if (collected.size < 5) searchHtml(alt).forEach(::merge)
            }
            Log.d(LOG_TAG, "eş anlamlı arama sonrası: ${collected.size} kart (${synonyms.size} ad)")
        }

        // 3) Yerel katalog (/getAnimeListForSearch — sitenin kendi arama indeksi).
        //    İngilizce/Japonca alternatif adlarla eşleşmenin asıl kaynağı ama
        //    ÇOK büyük (canlı ölçüm: >3 MB). Bu yüzden yalnızca yukarıdakiler
        //    yetersizse ve 6 saatlik önbellekle indirilir.
        if (collected.size < 3) {
            val catalogue = loadCatalogue()
            if (catalogue.isNotEmpty()) {
                val ranked = AnizmMatch.rank(
                    items = catalogue,
                    queries = variants,
                    nameOf = { listOf(it.name) + it.altNames },
                    limit = 40
                )
                ranked.forEach(::merge)
                Log.d(LOG_TAG, "katalog eşleşmesi: ${ranked.size}/${catalogue.size}")
            }
        }

        val ranked = AnizmMatch.rank(
            items = collected.values.toList(),
            queries = (variants + synonyms).distinct(),
            nameOf = { listOf(it.name) + it.altNames },
            limit = 40
        )
        Log.d(LOG_TAG, "search('$trimmed') → ${ranked.size} sonuç")
        return newSearchResponseList(ranked.map { it.toSearchResponse() }, hasNext || ranked.size >= SEARCH_PAGE_SIZE)
    }

    private fun AnizmCard.toSearchResponse(): AnimeSearchResponse =
        newAnimeSearchResponse(name, url, TvType.Anime) {
            this.posterUrl = this@toSearchResponse.poster
            this.score = this@toSearchResponse.score?.let { Score.from10(it) }
        }

    /** Kitsu ad önbelleği (sorgu → alternatif adlar). */
    private val kitsuTitleCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * Kitsu'dan alternatif adlar (canonicalTitle + titles{} + abbreviatedTitles).
     * Türkçe siteler romanize/İngilizce ad kullandığı için arama bu adlarla
     * genişletilir. Tek istek, 8 sn zaman aşımı, önbellekli.
     */
    private suspend fun titlesFromKitsu(query: String): List<String> {
        val key = query.lowercase().trim()
        if (key.length < 2) return emptyList()
        kitsuTitleCache[key]?.let { return it }
        val out = LinkedHashSet<String>()
        try {
            val url = "https://kitsu.io/api/edge/anime?filter[text]=" +
                URLEncoder.encode(key, "UTF-8") + "&page[limit]=3"
            val data = JSONObject(app.get(url, timeout = 8).text).optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val attrs = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                    attrs.optString("canonicalTitle").takeIf { it.isNotBlank() }?.let(out::add)
                    attrs.optJSONObject("titles")?.let { titles ->
                        for (lang in titles.keys()) {
                            titles.optString(lang).takeIf { it.isNotBlank() }?.let(out::add)
                        }
                    }
                    attrs.optJSONArray("abbreviatedTitles")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            arr.optString(j).takeIf { it.isNotBlank() }?.let(out::add)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(LOG_TAG, "Kitsu adları alınamadı ('$query'): ${e.message}")
        }
        val list = out.map { it.trim() }.filter { it.length in 2..120 }.distinct().take(8)
        kitsuTitleCache[key] = list
        return list
    }

    /** `GET /searchAnime?query=..&page=..&type=detailed` (XHR başlıkları zorunlu). */
    private suspend fun searchServer(query: String, page: Int): List<AnizmCard> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/searchAnime?query=$encoded&page=${page.coerceAtLeast(1)}" +
            "&type=detailed&limit=$SEARCH_PAGE_SIZE&priorityField=info_title" +
            "&orderBy=info_id&orderDirection=DESC"
        val body = getText(url, mainUrl + "/", xhr = true) ?: return emptyList()
        return parseSearchJson(body)
    }

    /** `GET /arama-onerileri?q=…` → `{"results":[{"title","year","url"}]}` (postersiz). */
    private suspend fun searchSuggestions(query: String): List<AnizmCard> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = getText("$mainUrl/arama-onerileri?q=$encoded", mainUrl + "/", xhr = true)
            ?: return emptyList()
        val results = runCatching { JSONObject(body.trim()).optJSONArray("results") }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<AnizmCard>()
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            AnizmSite.cardFromJsonFields(
                base = mainUrl,
                title = item.optString("title"),
                slug = item.optString("url"),
                poster = null,
                year = item.optString("year").toIntOrNull(),
                score = null,
                altNames = emptyList()
            )?.let(out::add)
        }
        return out
    }

    /** `/searchAnime` ve `/getAnimeListForSearch` aynı alan adlarını kullanır. */
    private fun parseSearchJson(body: String): List<AnizmCard> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()
        // 1) Düz dizi
        parseJsonArray(text)?.let { if (it.isNotEmpty()) return it }
        // 2) Sarmallı cevaplar: {data:[...]}, {animes:[...]}, {results:[...]}, {items:[...]}
        for (key in listOf("data", "animes", "results", "items", "list")) {
            val arrayText = extractJsonArray(text, key) ?: continue
            parseJsonArray(arrayText)?.let { if (it.isNotEmpty()) return it }
        }
        // 3) Satır satır JSON (NDJSON)
        val lines = text.lineSequence().filter { it.trim().startsWith("{") }.toList()
        if (lines.isNotEmpty()) {
            val cards = lines.mapNotNull { line -> runCatching { cardFromJsonObject(JSONObject(line)) }.getOrNull() }
            if (cards.isNotEmpty()) return cards
        }
        return emptyList()
    }

    private fun parseJsonArray(text: String): List<AnizmCard>? {
        val trimmed = text.trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            val array = org.json.JSONArray(trimmed.substring(start, end + 1))
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { cardFromJsonObject(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJsonArray(text: String, key: String): String? {
        val index = text.indexOf("\"$key\"")
        if (index < 0) return null
        val start = text.indexOf('[', index)
        if (start < 0) return null
        // Dengeli parantez taraması (iç içe diziler için).
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun cardFromJsonObject(obj: JSONObject): AnizmCard? {
        val title = obj.optString("info_title").ifBlank { obj.optString("title") }
        val slug = obj.optString("info_slug").ifBlank { obj.optString("slug") }
        // Poster alanı sürümden sürüme ad değiştiriyor; hepsi sırayla denenir
        // (boş poster = arama listesinde boş kart).
        val poster = listOf(
            "info_poster",
            "info_pcover",
            "pcover",
            "poster",
            "cover",
            "image",
            "poster_url",
            "thumbnail"
        ).asSequence().map { key -> obj.optString(key).trim() }
            .firstOrNull { it.isNotEmpty() } ?: ""
        val year = obj.optString("info_year").toIntOrNull() ?: obj.optInt("year", 0).takeIf { it > 0 }
        val score = obj.optString("info_malpoint").replace(',', '.').toDoubleOrNull()
            ?: obj.optDouble("malpoint", 0.0).takeIf { it > 0.0 }
        val altNames = mutableListOf<String>()
        fun addAlt(raw: String?) {
            // Tam adı da koru ("Fate/stay night" bölünmemeli), sonra parçaları ekle.
            val text = raw?.trim().orEmpty()
            if (text.length in 2..120) altNames.add(text)
            text.split(',', '/', '|')
                .map { it.trim() }
                .filter { it.length in 2..120 && it != text }
                .let(altNames::addAll)
        }
        addAlt(obj.optString("info_othernames"))
        addAlt(obj.optString("info_japanese"))
        // v6: "Attack on Titan" gibi İngilizce/romaji aramaların anahtarı.
        addAlt(obj.optString("info_titleenglish"))
        addAlt(obj.optString("info_titleoriginal"))
        obj.optJSONArray("names")?.let { names ->
            for (i in 0 until names.length()) {
                names.optString(i).takeIf { it.isNotBlank() }?.let(altNames::add)
            }
        }
        return AnizmSite.cardFromJsonFields(
            base = mainUrl,
            title = title,
            slug = slug,
            poster = poster,
            year = year,
            score = score,
            altNames = altNames
        )
    }

    @Volatile
    private var catalogueCache: List<AnizmCard> = emptyList()

    @Volatile
    private var catalogueFetchedAt = 0L

    /**
     * Tüm katalog (`/getAnimeListForSearch`) — yaklaşık 6 bin kayıt, tek istek.
     * Uygulama İngilizce başlıkla aradığında Türkçe site araması boş dönerken bu
     * liste `info_othernames`/`info_japanese` üzerinden doğru animeyi bulur.
     * [CATALOGUE_TTL_MS] boyunca bellekte tutulur.
     */
    private suspend fun loadCatalogue(): List<AnizmCard> {
        val now = System.currentTimeMillis()
        if (catalogueCache.isNotEmpty() && now - catalogueFetchedAt < CATALOGUE_TTL_MS) return catalogueCache
        val body = getText("$mainUrl/getAnimeListForSearch", mainUrl + "/", xhr = true, timeout = 20)
            ?: return catalogueCache
        val parsed = parseSearchJson(body)
        if (parsed.isNotEmpty()) {
            catalogueCache = parsed
            catalogueFetchedAt = now
            Log.d(LOG_TAG, "katalog yüklendi: ${parsed.size} kayıt")
        }
        return catalogueCache
    }

    /** HTML arama (`/arama?q=`, `/fullViewSearch?search=`, `/arama/{q}`). */
    private suspend fun searchHtml(query: String): List<AnizmCard> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val pathEncoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val attempts = listOf(
            "$mainUrl/arama?q=$encoded",
            // Canlı sitede gözlenen parametre adları (farklı tema/sürümler):
            "$mainUrl/arama?arama=$encoded",
            "$mainUrl/arama?search=$encoded",
            "$mainUrl/fullViewSearch?search=$encoded&skip=0",
            "$mainUrl/arama/$pathEncoded"
        )
        for (url in attempts) {
            val html = getText(url, mainUrl + "/") ?: continue
            if (looksBlocked(html)) continue
            val doc = Jsoup.parse(html, url)
            val cards = AnizmSite.parseCards(doc, url)
            if (cards.isNotEmpty()) {
                Log.d(LOG_TAG, "HTML arama ($url) → ${cards.size} kart")
                return cards
            }
        }
        return emptyList()
    }

    // ------------------------------------------------------------------
    // Ana sayfa
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            ensureBase()
            // `{page}` şablonu desteklenir: kimi bölümler sayfayı yol sonuna ekler
            // ("/anime-izle?sayfa=2"), kimileri sorgu içinde taşır.
            val template = remapBase(request.data)

            // 1) Ana sayfa bölümü (`/#populer`, `/#son-bolumler`, `/#yeni-seriler`).
            val sectionId = SECTION_PATTERN.find(template)?.groupValues?.getOrNull(1)
            if (sectionId != null) {
                val homeUrl = template.substringBefore('#')
                val html = cachedHome(homeUrl)
                if (html.isNullOrBlank()) return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html, homeUrl)
                val section = doc.selectFirst("section#$sectionId") ?: doc
                val cards = AnizmSite.parseCards(section, homeUrl)
                Log.d(LOG_TAG, "mainPage('${request.name}') bölüm #$sectionId → ${cards.size} kart")
                return newHomePageResponse(request.name, cards.map { it.toSearchResponse() }, false)
            }

            // 2) Liste sayfası (`/yeniler`, `/kategoriler/N`, `/temalar/N` …).
            val url = if (template.contains("{page}")) template.replace("{page}", page.toString())
            else template + page
            val html = getText(url, mainUrl + "/")
            if (html.isNullOrBlank()) return newHomePageResponse(request.name, emptyList(), false)
            val doc = Jsoup.parse(html, url)
            var cards = AnizmSite.parseCards(doc, url)
            if (cards.isEmpty()) {
                // Kategori yolu değişmiş olabilir (site v3'te /kategoriler/N yerine
                // farklı yol kullanıyor). Boş bölüm göstermek yerine "son eklenenler"
                // listesine düşülür.
                val fallbackUrl = "$mainUrl/anime-izle?sayfa=$page"
                val fallbackHtml = getText(fallbackUrl, mainUrl + "/")
                if (!fallbackHtml.isNullOrBlank()) {
                    cards = AnizmSite.parseCards(Jsoup.parse(fallbackHtml, fallbackUrl), fallbackUrl)
                    Log.w(LOG_TAG, "mainPage('${request.name}') boş → son eklenenler: ${cards.size}")
                }
            }
            // Tür satırları: site alfabetik veriyor; puanı olan kartlar puana göre
            // dizilir (`.hack//…`, `#Zombie`, `16bit…` gibi "alfabetik çöp" başta
            // görünmesin). Puansız kartlar sona gider, kendi sıralarını korur.
            if (GENRE_ROW.containsMatchIn(request.data) && cards.count { (it.score ?: 0.0) > 0.0 } >= 3) {
                cards = cards.sortedByDescending { it.score ?: 0.0 }
            }
            Log.d(LOG_TAG, "mainPage('${request.name}') → ${cards.size} kart")
            val hasNext = cards.isNotEmpty() && page < MAX_MAIN_PAGE
            newHomePageResponse(request.name, cards.map { it.toSearchResponse() }, hasNext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "getMainPage('${request.name}') hatası: ${e.javaClass.simpleName}: ${e.message}")
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    /**
     * Ana sayfa HTML'i, bölüm satırları için kısa süreli önbelleklenir.
     *
     * `Popüler` / `Son Bölümler` / `Yeni Seriler` satırları aynı `/` sayfasından
     * besleniyor; önbellek olmadan tek ana sayfa açılışında 3 istek gidiyordu.
     */
    @Volatile
    private var homeCache: Pair<Long, String>? = null

    private suspend fun cachedHome(url: String): String? {
        val now = System.currentTimeMillis()
        homeCache?.let { (at, html) -> if (now - at < HOME_CACHE_MS) return html }
        val html = getText(url, mainUrl + "/") ?: return null
        homeCache = now to html
        return html
    }

    // ------------------------------------------------------------------
    // Detay + bölümler
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        ensureBase()
        var pageUrl = remapBase(url).let { if (AnizmSite.isEpisodeUrl(it)) AnizmSite.toSeriesUrl(it) ?: it else it }
        var html = getText(pageUrl, mainUrl + "/") ?: throw ErrorLoadingException("Anizm sayfası yüklenemedi")
        var doc = Jsoup.parse(html, pageUrl)

        // Doğrudan BÖLÜM sayfasına gelinmiş olabilir (derin bağlantı, film/OVA
        // bölümü, arama sonucu). O sayfadan "seri" kurmak yanlış detay üretir;
        // kanonik adres varsa seri sayfasına dönülür. Ek istek yalnızca bu
        // durumda yapılır — normal akışta maliyet yok.
        if (doc.selectFirst("[data-v3-translator], #v3-player-screen, section.v3-player") != null) {
            val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")?.trim()
                ?.takeIf { it.startsWith("http") }
                ?: doc.selectFirst("meta[property=og:url]")?.attr("content")?.trim()
                    ?.takeIf { it.startsWith("http") }
            if (canonical != null && canonical.trimEnd('/') != pageUrl.trimEnd('/')) {
                Log.d(LOG_TAG, "bölüm sayfasından seri sayfasına: $canonical")
                pageUrl = canonical
                html = getText(pageUrl, mainUrl + "/") ?: html
                doc = Jsoup.parse(html, pageUrl)
            }
        }
        val detail = AnizmSite.parseDetail(doc, pageUrl, pageUrl)

        // Sayfa düzeyinde hiç bölüm yoksa "tek parça" (film/OVA) olarak sunulur.
        val episodes = detail.episodes
        val season = AnizmMatch.seasonOf(detail.title) ?: 1
        val episodeList = episodes.map { episode ->
            val number = episode.number
            newEpisode(episode.url) {
                this.name = episode.name?.ifBlank { null } ?: number?.let { "$it. Bölüm" } ?: "Bölüm"
                this.episode = number
                this.season = season
                this.posterUrl = episode.poster ?: detail.poster
            }
        }

        val commonTags = detail.tags.distinct()

        return if (episodeList.isEmpty()) {
            val singleLink = doc.select("a[href*='-bolum'], a[href*='/bolum/'], a[href*='izle']")
                .mapNotNull { AnizmSite.absolute(pageUrl, it.attr("href")) }
                .firstOrNull { AnizmSite.isSiteUrl(it) }
                ?: pageUrl
            newMovieLoadResponse(detail.title, pageUrl, TvType.AnimeMovie, singleLink) {
                this.posterUrl = detail.poster
                this.plot = detail.plot
                this.year = detail.year
                this.tags = commonTags
                this.score = detail.score?.let { Score.from10(it) }
                this.duration = 24
            }
        } else {
            newAnimeLoadResponse(detail.title, pageUrl, TvType.Anime, false) {
                this.posterUrl = detail.poster
                this.plot = detail.plot
                this.year = detail.year
                this.tags = commonTags
                this.score = detail.score?.let { Score.from10(it) }
                this.showStatus = if (episodeList.size >= 2) ShowStatus.Ongoing else ShowStatus.Completed
                addEpisodes(DubStatus.Subbed, episodeList)
            }
        }
    }

    // ------------------------------------------------------------------
    // Video kaynakları
    // ------------------------------------------------------------------

    /**
     * `/player/{id}` adresinin **302 hedefini** döndürür.
     *
     * Anizm'in (anizm.net/puffytr ailesi) kendi oynatıcı yolu bu: bölüm sayfasındaki
     * `a.videoPlayerButtons[video]` → `/video/{id}`; site oynatıcıyı `/player/{id}`
     * üzerinden açar ve **yönlendirme (Location) başlığıyla** gerçek host'a
     * (anizmplayer / filemoon / sibnet …) gönderir. İstemci yönlendirmeyi kendi
     * izlerse hedef adres kaybolur; bu yüzden yönlendirme KAPATILARAK okunur.
     */
    private suspend fun followLocation(url: String, referer: String): String? {
        return try {
            val response = app.get(
                url,
                headers = headers(referer, xhr = false),
                allowRedirects = false,
                timeout = 12L
            )
            // Bu fork'ta `Response.headers` Map<String, String> (başlık adı büyük/küçük
            // harfe duyarlı olabildiği için iki biçim de denenir).
            val location = (response.headers["Location"]
                ?: response.headers["location"])
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (location == null) null else AnizmSite.absolute(url, location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(LOG_TAG, "Location okunamadı (${url.take(80)}): ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // v5: akış zinciri — AnizmStreams (paralel, WebView'siz, eksiksiz)
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Domain ölü olabilir (site sık adres değiştiriyor) — tek seferlik,
        // TTL önbellekli prob. Akış isteği ölü domain'e gitmez.
        ensureBase()
        var pageUrl = remapBase(data)
        val streams = AnizmStreams(name, { mainUrl }, this)
        var count = try {
            streams.load(pageUrl, subtitleCallback, callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "loadLinks hatası: ${e.javaClass.simpleName}: ${e.message}")
            0
        }
        // Bölüm sayfası hiç alınamadıysa (domain duvarı/blok) canlı ayna aranır ve
        // istek bir kez daha denenir — kullanıcıya "kaynak yok" demeden önce.
        if (count == 0 && streams.pageFetchFailed) {
            ensureBase(force = true)
            val retryUrl = remapBase(data)
            if (retryUrl != pageUrl) {
                pageUrl = retryUrl
                Log.i(LOG_TAG, "bölüm sayfası yeniden deneniyor: $pageUrl")
                count = try {
                    AnizmStreams(name, { mainUrl }, this)
                        .load(pageUrl, subtitleCallback, callback)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(LOG_TAG, "loadLinks (tekrar) hatası: ${e.message}")
                    0
                }
            }
        }
        if (count > 0) {
            Log.d(LOG_TAG, "loadLinks: $count akış yayınlandı")
            return true
        }
        Log.w(LOG_TAG, "loadLinks: kaynak bulunamadı ($pageUrl)")
        throw ErrorLoadingException(
            "Kaynak bulunamadı: site bu bölüm için oynatıcı listesi döndürmedi. " +
                "Başka bir bölüm deneyin veya eklentiyi güncelleyin."
        )
    }

    // ------------------------------------------------------------------
    // AnizmHttp — akış zincirinin HTTP yüzeyi
    // ------------------------------------------------------------------

    override suspend fun get(url: String, referer: String?): String? =
        getText(url, referer ?: mainUrl + "/", xhr = false, timeout = 12L)

    override suspend fun inspectDrive(url: String, referer: String): AnizmDriveResponse = try {
        val response = app.get(
            url,
            headers = headers(referer, xhr = false) + mapOf("Range" to "bytes=0-262143"),
            timeout = 12L
        )
        response.okhttpResponse.use { raw ->
            // Range can be ignored. Peek only a small prefix and close the response;
            // response.text would otherwise buffer an entire video on older NiceHttp.
            val sample = if (raw.isSuccessful) raw.peekBody(4096).bytes() else ByteArray(0)
            val kind = AnizmPlaybackPolicy.classifyDrive(
                raw.code, raw.header("Content-Type"), raw.header("Content-Disposition"), sample
            )
            val body = if (kind == AnizmDriveKind.HTML) raw.peekBody(262144).string() else null
            AnizmDriveResponse(kind, body)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Drive probe hatası (${e.javaClass.simpleName}): ${url.take(100)}")
        AnizmDriveResponse(AnizmDriveKind.UNKNOWN)
    }

    /**
     * v3 kaynak uçları (`/bolum/{ep}/cevirmen/{id}`, `/video/{vid}`) AJAX ile
     * beslenir: `X-Requested-With` + `Accept: application/json` + `Sec-Fetch-*: same-origin`.
     * Düz belge isteği bu uçlardan HTML kabuğu döndürebiliyor.
     */
    override suspend fun getXhr(url: String, referer: String?): String? =
        getText(url, referer ?: mainUrl + "/", xhr = true, timeout = 12L)

    override suspend fun location(url: String, referer: String?): String? =
        followLocation(url, referer ?: mainUrl + "/")

    override suspend fun getWith(url: String, extra: Map<String, String>): String? = try {
        val base = url.substringBefore("/api/").ifEmpty { mainUrl }
        val response = app.get(
            url,
            headers = headers("$base/", true) + extra,
            timeout = 12L
        )
        if (!response.isSuccessful) null else response.text
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "GET(w) hatası (${e.javaClass.simpleName}): ${url.take(120)}")
        null
    }

    override suspend fun postJson(url: String, json: String, referer: String?): String? =
        postText(
            url = url,
            referer = referer ?: mainUrl + "/",
            xhr = true,
            json = json,
            extraHeaders = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Content-Type" to "application/json"
            ),
            timeout = 12L
        )

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String?
    ): String? = postText(
        url = url,
        referer = referer ?: mainUrl + "/",
        xhr = true,
        form = form,
        extraHeaders = mapOf(
            "Origin" to PLAYER_HOST,
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        ),
        timeout = 15L
    )

    companion object {
        private const val LOG_TAG = "hexated_Anizm"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        private const val PLAYER_HOST = "https://anizmplayer.com"

        /** Site içi oynatıcı yolu — 302 Location ile gerçek host'a gider. */
        private val IN_SITE_PLAYER = Regex("/player/\\d+|/video/\\d+", RegexOption.IGNORE_CASE)
        /**
         * Başlangıç alan adı. 26.10.2026 ölçümünde `anizm.net` Cloudflare
         * duvarından 403 dönerken `anizm.com.tr` tam siteyi (arama, bölüm
         * listesi, `/bolum/{id}/cevirmen/{id}` JSON'u, `/player/{id}` 302'si)
         * eksiksiz verdi; bu yüzden canlı ayna ile başlanır. `anizm.net` yine
         * adaydır — duvar kalkarsa `probe` oraya da geçebilir.
         */
        /** `/#populer` gibi ana sayfa bölüm adresleri. */
        private val SECTION_PATTERN = Regex("""#([a-z0-9-]+)$""")

        /** Tür/kategori satırları (puan sıralaması uygulanır). */
        private val GENRE_ROW = Regex("""/(?:kategoriler|temalar)/\d+""")

        private const val DEFAULT_BASE = "https://anizm.com.tr"
        private const val SEARCH_PAGE_SIZE = 24
        private const val MAX_MAIN_PAGE = 60
        private const val BASE_TTL_MS = 10 * 60 * 1000L

        /** Ana sayfa bölüm satırları için önbellek ömrü. */
        private const val HOME_CACHE_MS = 60_000L
        private const val CATALOGUE_TTL_MS = 6 * 60 * 60 * 1000L

        /**
         * Aynı sitenin paralel adları — **sıra = öncelik**; prob canlıyı seçer.
         *
         * 26.10.2026 canlı ölçümü (GitHub runner, aynı ağ sınıfı):
         *  - `anizm.net`      → **403** (Cloudflare "Just a moment…")
         *  - `puffytr.com`    → **403** (aynı duvar)
         *  - `anizm.com.tr`   → **200**, 144 KB, `v3-series-card` × 39
         *
         * Bu yüzden önce `anizm.com.tr` denenir. Aynamı aynı veritabanıdır:
         * `one-piece-1-bolum-izle` sayfası `/bolum/18853/cevirmen/18853`
         * üretiyor — bu kimlik, sitenin kendi arşivindeki One Piece 1. bölüm
         * kimliğiyle birebir aynı.
         */
        private val BASE_CANDIDATES = listOf(
            "https://anizm.com.tr",
            "https://anizm.net",
            "https://puffytr.com",
            "https://www.puffytr.com",
            "https://puffytr.com.tr",
            "https://anizle.org",
            "https://anizle.co"
        )

        /**
         * Reklam/ön-roll ağları — bunlar "oynatıcı adayı" gibi görünse de video
         * vermez. Kısa jenerik parçalar ("bet", "cpm") bilinçli olarak YOK:
         * gerçek host adlarını yanlışlıkla elerler.
         */
        private val AD_HOSTS = listOf(
            "doubleclick", "googlesyndication", "adsterra", "popads", "popcash", "propellerads",
            "onclickads", "a-ads.com", "adsystem", "juicyads", "exoclick", "hilltopads",
            "clickadu", "mgid.com", "adtng", "tsyndicate", "trafficjunky"
        )
    }
}

/**
 * `SubtitleFile` public constructor'ı fork'ta deprecated; fabrika fonksiyonu
 * aynı nesneyi üretir (davranış birebir aynı).
 */
private fun newSubtitleFile(lang: String, url: String): SubtitleFile = SubtitleFile(lang, url)
