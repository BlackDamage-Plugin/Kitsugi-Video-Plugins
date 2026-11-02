package com.hexated

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Katalog/arama kartı. */
internal data class AnizmCard(
    val name: String,
    val url: String,
    val poster: String? = null,
    val score: Double? = null,
    /** Kart üzerinde yazan son bölüm numarası (varsa) — "Sub: 12" rozeti için. */
    val episodeHint: Int? = null,
    /** Aynı animenin alternatif adları (JSON aramasında dolu gelir). */
    val altNames: List<String> = emptyList()
)

/** Bölüm satırı. */
internal data class AnizmEpisode(
    val url: String,
    val number: Int?,
    val name: String?,
    val date: String? = null,
    val poster: String? = null
)

/** Detay sayfası çıktısı. */
internal data class AnizmDetail(
    val title: String,
    val poster: String?,
    val plot: String?,
    val tags: List<String>,
    val year: Int?,
    val score: Double?,
    val studio: String?,
    val typeText: String?,
    val episodes: List<AnizmEpisode>
)

/**
 * Anizm (puffytr.com / anizm.net ailesi) HTML çözümleyicisi.
 *
 * TASARIM KURALI: Her alan için **birden fazla seçici** denenir ve hiçbiri tek başına
 * zorunlu değildir. Site teması (v2 "anizm_pageTitle" ↔ v3 "v3-catalog-card") değişse
 * bile eklenti veri üretmeye devam eder. Seçiciler, 2026-10 itibarıyla çalışan iki
 * bağımsız istemciden alınmıştır:
 *  - Aniyomi `yuzono/anime-extensions` (src/tr/anizm, 2026-10-25 güncellemesi)
 *  - CloudStream `Saloo1575/SalooRepo` (Anizm, 2026-10-15) + `hexated` orijinali
 *
 * Ağ/IO YOKTUR: bu dosya saf ayrıştırmadır, birim testleri ağ olmadan koşar.
 */
internal object AnizmSite {

    /** Anime slug'ı OLMAYAN yollar. */
    private val BLOCKED_PATHS = setOf(
        "kategoriler", "temalar", "profil", "arama", "giris-yap", "kayit-ol", "girisyap", "uyeol",
        "listeler", "bolum", "player", "raporver", "storage", "upload", "assets", "js", "css",
        "images", "api", "arama-sonuclari", "hakkimizda", "iletisim", "sss", "duyurular",
        "gizlilik", "kullanim-sartlari", "cerez", "destek", "kurallar", "haber", "blog", "tv",
        "yayin", "takvim", "sozluk", "fansublar", "fansub", "manga", "discord", "ara", "search",
        "sifremi-unuttum", "premium", "profil-ayarlari", "random", "sasirt-beni", "tavsiye-robotu"
    )

    /** Bu eklentinin kabul ettiği host işaretleri (site domain değiştirdiğinde de çalışır). */
    private val SITE_HOST_MARKERS = listOf("puffytr", "anizm", "anizle")

    /**
     * Bölüm eki (2026-10 v3, fixture ile doğrulandı):
     *   `-1-bolum-izle`, `-28-bolum-final-izle`, `-41bolum-izle`
     *   (53 One Piece bölümünde "bolum"dan önce tire YOK).
     * Grup 1 = seri slug'ı, grup 2 = bölüm numarası.
     *
     * DİKKAT — çift bölüm belirsizliği: `one-piece-215-216-bolum-izle` (215-216
     * ÇİFT bölüm) ile `ghost-22-1-bolum-izle` (seri "ghost-22", bölüm 1) aynı
     * biçimdedir. Varsayılan SON sayıdır (bölüm); çift bölüm yalnızca
     * [DOUBLE_EPISODE_PATH] ile ve etiket "215-216. Bölüm" dediğinde ayrılır.
     */
    private val EPISODE_PATH = Regex(
        "^(.*?)-(\\d{1,4})-?bolum(?:[-.][a-z0-9-]*)?$",
        RegexOption.IGNORE_CASE
    )

    /** Çift/birleşik bölüm eki: `one-piece-215-216-bolum-izle`. */
    private val DOUBLE_EPISODE_PATH = Regex(
        "^(.*?)-(\\d{1,4})-(\\d{1,4})-?bolum(?:[-.][a-z0-9-]*)?$",
        RegexOption.IGNORE_CASE
    )

    /**
     * **Numarasız** bölüm eki: `…-bolum`, `…-bolum-izle`.
     * Film/OVA bölümlerinin slug'ında sıra numarası olmuyor
     * (ör. `…-the-last-attack-shingeki-…---the-last-attack-bolum-izle`);
     * eski sürüm bunları bölüm saymıyor, sayfadaki tüm bağlantıları bölüm
     * listesine dolduruyordu.
     */
    private val NAMELESS_EPISODE_SUFFIX = Regex(
        "-bolum(?:[-.][a-z0-9-]*)?$",
        RegexOption.IGNORE_CASE
    )

    /** v3 sayısal bölüm yolu: `/bolum/73483`. */
    private val BOLUM_ID_PATH = Regex("/bolum/(\\d+)")

    /**
     * Poster adresi taşıyabilen öznitelikler (lazy-load eklentileri farklı adlar
     * kullanıyor). Sıra = güven sırası: `src` çoğu zaman 1x1 yer tutucudur,
     * bu yüzden sonda.
     */
    private val IMAGE_ATTRS = listOf(
        // v3 ana sayfa görselleri `src` yerine burada taşınıyor; site JS'i sonra kopyalıyor.
        "data-v3-deferred-src",
        "data-src",
        "data-original",
        "data-lazy-src",
        "data-lazy",
        "data-url",
        "data-image",
        "data-poster",
        "data-cover",
        "src"
    )

    /** Sitenin yer tutucu kapağı — poster olarak kullanılamaz. */
    private val DEFAULT_COVER = Regex("defaultcover", RegexOption.IGNORE_CASE)

    /**
     * Liste görselleri `/storage/pcovers/<id>.webp` (225x318) gelir; aynı görselin
     * büyük hâli `<id>l.webp` (425x600) her zaman vardır (fixture: 16 kapak).
     * Poster kalitesi için büyük sürüme yükseltilir.
     */
    fun largeCover(url: String?): String? {
        if (url.isNullOrBlank()) return url
        val match = COVER_SMALL.find(url) ?: return url
        val big = match.groupValues[1] + "l." + match.groupValues[2]
        return url.replaceRange(match.range, big)
    }

    /** `/storage/pcovers/<id>.<ext>` — küçük (liste) kapak. */
    private val COVER_SMALL = Regex(
        """(/storage/pcovers/\d+)\.(webp|jpe?g|png)""",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------------
    // URL yardımcıları
    // ------------------------------------------------------------------

    /** Göreli/tam adresi mutlak adrese çevirir; geçersizse null. */
    fun absolute(base: String, href: String?): String? {
        val raw = href?.trim() ?: return null
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("data:")) return null
        val lower = raw.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:")) return null
        return try {
            URI(base).resolve(raw).toString()
        } catch (_: Exception) {
            if (lower.startsWith("http")) raw else null
        }
    }

    fun hostOf(url: String): String = try {
        (URI(url).host ?: "").lowercase().removePrefix("www.")
    } catch (_: Exception) {
        ""
    }

    fun isSiteUrl(url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty()) return false
        return SITE_HOST_MARKERS.any { host.contains(it) }
    }

    /** "/one-piece" veya "one-piece" — karşılaştırma için tek biçim. */
    fun seriesPathOf(url: String): String? {
        val path = url.substringAfter("://", url)
            .substringAfter("/", "")
            .substringBefore("?")
            .substringBefore("#")
            .trim('/')
            .lowercase()
        if (path.isEmpty()) return null
        val stripped = EPISODE_PATH.replace(path, "$1").trim('/')
            .replace(NAMELESS_EPISODE_SUFFIX, "")
        val normalised = stripped.removeSuffix("-izle").trim('-')
        return normalised.ifBlank { null }
    }

    /** Bölüm URL'sinden numara ("-12-bolum"). */
    fun episodeNumberFromUrl(url: String): Int? {
        val path = url.substringAfter("://", url).substringAfter("/", "").substringBefore("?")
        val m = EPISODE_PATH.find(path) ?: return null
        return m.groupValues.getOrNull(2)?.toIntOrNull()
    }

    /**
     * Bölüm URL'sini seri (detay) URL'sine indirger. Bölüm değilse null.
     * [label] verilirse çift bölüm ("215-216. Bölüm") doğru ayrıştırılır.
     */
    fun toSeriesUrl(url: String, label: String? = null): String? {
        val path = url.substringAfter("://", url).substringAfter("/", "").substringBefore("?")
        val isRange = label != null && RANGE_EPISODE.containsMatchIn(label)
        val m = (if (isRange) DOUBLE_EPISODE_PATH.find(path) else null)
            ?: EPISODE_PATH.find(path)
            ?: run {
                // Numarasız bölüm (film/OVA): eki at, geri kalan seri yoludur.
                if (!NAMELESS_EPISODE_SUFFIX.containsMatchIn(path)) return null
                val stripped = path.replace(NAMELESS_EPISODE_SUFFIX, "")
                if (stripped.isBlank() || stripped == path) return null
                val prefix = url.substringBefore("://") + "://" +
                    url.substringAfter("://").substringBefore("/")
                return "$prefix/$stripped"
            }
        val prefix = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/")
        // "-izle" ile biten detay adresleri (…/tsuki-ga-kirei-izle) korunur.
        val seriesPath = m.groupValues[1]
        return "$prefix/$seriesPath"
    }

    fun isEpisodeUrl(url: String): Boolean = episodeNumberFromUrl(url) != null

    /** Slug'dan okunabilir başlık (son çare). */
    fun titleFromSlug(url: String): String {
        val slug = url.substringAfterLast("/").substringBefore("?").removeSuffix(".html")
        val cleaned = EPISODE_PATH.replace(slug, "$1").removeSuffix("-izle")
        return cleaned.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    /**
     * Başlık gerçekten bir içerik adı mı?
     *
     * DİKKAT: burada "slug gibi görünüyorsa ele" kuralı **yoktur** — "Bleach",
     * "Naruto", "Monster" gibi tek kelimelik gerçek adlar da slug biçimindedir.
     * Yalnızca menü/gezinme etiketleri ve salt sayılar elenir.
     */
    fun isInvalidTitle(title: String?): Boolean {
        val t = title?.trim() ?: return true
        if (t.length < 2) return true
        val key = AnizmMatch.fold(t)
        if (key.isEmpty()) return true
        if (key in BLOCKED_TITLES) return true
        if (key.matches(Regex("^\\d{1,4}$"))) return true
        if (key.matches(Regex("^\\d{1,4} bolum$"))) return true
        return false
    }

    /** Menü/gezinme etiketleri (fold edilmiş biçimde). */
    private val BLOCKED_TITLES = setOf(
        "izle", "bolum", "giris yap", "kayit ol", "kategoriler", "temalar", "profil", "hesabim",
        "anasayfa", "devamini oku", "hepsini izle", "ilk bolumu izle", "tum bolumler", "fragman",
        "menu", "ara", "arama", "son eklenenler", "yeni bolumler", "populer animeler", "fansublar",
        "manga", "takvim", "discord", "sasirt beni", "tavsiye robotu", "cikis yap", "ayarlar",
        "bildirimler", "mesajlar", "paylas", "rapor ver", "oy ver"
    )

    // ------------------------------------------------------------------
    // Kart çözümleme (arama / ana sayfa)
    // ------------------------------------------------------------------

    /**
     * Bir <img> öğesinden **mutlak** poster adresi (lazy-load öznitelikleri dahil).
     *
     * v32: adres artık her zaman mutlaklaştırılır. Eskiden `img.absUrl(attr)`
     * boş dönünce öznitelik ham hâliyle (ör. `/storage/pcovers/x.jpg`) veriliyordu
     * ve uygulama göreli adresi yükleyemiyordu → **boş poster**.
     */
    fun imageUrl(img: Element?, base: String? = null): String? {
        if (img == null) return null
        val docBase = base?.takeIf { it.isNotBlank() } ?: img.baseUri()
        for (attr in IMAGE_ATTRS) {
            val value = img.attr(attr).trim()
            if (value.isEmpty() || value.startsWith("data:")) continue
            if (value.contains("placeholder") || value.endsWith(".svg")) continue
            if (DEFAULT_COVER.containsMatchIn(value)) continue
            absoluteImage(docBase, value)?.let { return largeCover(it) }
        }
        // srcset / data-srcset: son giriş genelde en büyük görsel.
        val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }.trim()
        if (srcset.isNotEmpty()) {
            val last = srcset.split(',').map { it.trim().substringBefore(' ') }
                .lastOrNull { it.isNotEmpty() }
            if (last != null) absoluteImage(docBase, last)?.let { return largeCover(it) }
        }
        return null
    }

    /** Görsel adresini mutlaklaştırır (protokol-göreli ve kök-göreli dahil). */
    private fun absoluteImage(base: String, raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("data:")) return null
        if (value.startsWith("http")) return value
        if (value.startsWith("//")) return "https:$value"
        val resolved = if (base.isBlank()) null else absolute(base, value)
        return resolved ?: value.takeIf { it.startsWith("http") }
    }

    private fun bestImage(el: Element): String? {
        val img = el.selectFirst("img") ?: el.parents().firstOrNull { it.selectFirst("img") != null }
            ?.selectFirst("img")
            ?: return null
        return imageUrl(img, el.baseUri())
    }

    private fun titleOf(el: Element): String? {
        val candidates = listOfNotNull(
            el.selectFirst("h1, h2, h3, h4, h5, h6")?.text(),
            el.selectFirst("span.title, div.title, .animeName, .slideAnimeTitle, .anime-title, .card-title")?.text(),
            el.attr("title"),
            el.selectFirst("img")?.attr("alt")
        )
        for (candidate in candidates) {
            val cleaned = AnizmMatch.cleanTitle(candidate) ?: continue
            if (!isInvalidTitle(cleaned)) return cleaned
        }
        return null
    }

    private fun cardFromAnchor(anchor: Element, base: String): AnizmCard? {
        val href = absolute(base, anchor.attr("href").ifBlank { anchor.attr("data-href") }) ?: return null
        if (!isSiteUrl(href)) return null

        val path = href.substringAfter("://", "").substringAfter("/", "").substringBefore("?").trim('/')
        val firstSegment = path.substringBefore('/').lowercase()
        if (firstSegment.isEmpty() || AnizmMatch.isSlug(firstSegment).not()) return null
        if (BLOCKED_PATHS.contains(firstSegment)) return null
        if (href.contains("/profil/") || href.contains("/fansub/") || href.contains("/bolum/")) return null

        val seriesUrl = toSeriesUrl(href) ?: href
        val name = titleOf(anchor) ?: AnizmMatch.cleanTitle(titleFromSlug(seriesUrl)) ?: return null
        if (isInvalidTitle(name)) return null

        val scoreText = anchor.selectFirst(".v3-catalog-score, .score, .rating, rating")?.text()
            ?: anchor.attr("data-score")
        val score = scoreText?.replace(',', '.')?.let { text ->
            Regex("\\d{1,2}(?:\\.\\d)?").find(text)?.value?.toDoubleOrNull()
        }?.takeIf { it > 0.0 && it <= 10.0 }

        val hint = AnizmMatch.episodeOf(anchor.text()) ?: episodeNumberFromUrl(href)

        return AnizmCard(
            name = name,
            url = seriesUrl,
            poster = bestImage(anchor),
            score = score,
            episodeHint = hint
        )
    }

    /**
     * Sayfadaki katalog kartlarını döndürür. Sıra: bilinen kart yapıları → genel
     * bağlantı taraması. Aynı animenin tekrarı ayıklanır.
     */
    fun parseCards(doc: Document, base: String, limit: Int = 60): List<AnizmCard> {
        val out = LinkedHashMap<String, AnizmCard>()

        fun put(card: AnizmCard?) {
            if (card == null || out.size >= limit) return
            if (!out.containsKey(card.url)) out[card.url] = card
        }

        fun take(anchor: Element?) {
            if (anchor == null || out.size >= limit) return
            put(cardFromAnchor(anchor, base))
        }

        // 0) v3 kartları (2026-10, fixture ile doğrulandı): başlık ve kapak
        //    <a>'nın İÇİNDE değil, kardeş öğelerde → kapsayıcıdan okunur.
        //    Bölüm kartları bölüm URL'sine bağlanır; seri URL'si türetilir.
        for (el in doc.select(
            "article.v3-catalog-card, article.v3-series-card, article.v3-episode-card, " +
                "section.v3-hero article, [data-v3-slide], " +
                "#son-bolumler article, #populer article, #yeni-seriler article, " +
                ".v3-catalog-grid article, .v3-card"
        )) {
            put(cardFromContainer(el, base))
        }
        // 1) "Son eklenenler" listesi (div#episodesMiddle div.posterBlock > a)
        for (a in doc.select("div#episodesMiddle div.posterBlock > a, div.posterBlock > a")) take(a)
        // 2) Anasayfa carousel'i (a.slideAnimeLink)
        for (a in doc.select("a.slideAnimeLink, div.popularAnimeCarousel a")) take(a)
        // 3) Kategori/tema kartları
        for (card in doc.select("div.anime-card, div.cat-card, div.ui.card, div.animeCard, article.anime")) {
            take(card.selectFirst("a.anime-title, a.image, a[href]"))
        }
        // 4) Genel: görsel taşıyan anime bağlantıları (tema değişimine dayanıklı yedek)
        if (out.size < 5) {
            for (a in doc.select("a[href]")) {
                if (out.size >= limit) break
                if (a.selectFirst("img") == null && a.selectFirst("h1, h2, h3, h4, h5, h6") == null) continue
                take(a)
            }
        }
        return out.values.toList()
    }

    /**
     * v3 kart kapsayıcısı → katalog kartı. Başlık sırası (fixture):
     * `.v3-card-copy h3` / `.v3-catalog-card-copy h2` / `.v3-catalog-preview h3` /
     * `h3` / `h2` → `img[alt]` → `a[aria-label]` (hero slaytları).
     */
    private fun cardFromContainer(el: Element, base: String): AnizmCard? {
        val anchor = el.selectFirst("a.v3-catalog-poster[href], a[href]")
            ?: if (el.tagName() == "a") el else return null
        val href = absolute(base, anchor.attr("href")) ?: return null
        if (!isSiteUrl(href)) return null
        if (href.contains("/profil/") || href.contains("/fansub/") || href.contains("/bolum/")) return null
        // Bölümler ve fragmanlar seri sayfasına indirgenir (v3'te kart bölüme gider).
        val cardLabel = el.selectFirst(".v3-card-copy span, .v3-card-tag, .v3-catalog-preview-meta span")?.text()
        val seriesUrl = toSeriesUrl(href, cardLabel) ?: href
        if (href.contains("fragman") || href.contains("-pv-")) return null

        val rawTitle = listOfNotNull(
            el.selectFirst(".v3-card-copy h3, .v3-catalog-card-copy h2, .v3-catalog-preview h3")?.text(),
            el.selectFirst("h3, h2, h4")?.text(),
            el.selectFirst("img")?.attr("alt")?.replace(Regex("""\s+(?:anime\s+)?posteri$""", RegexOption.IGNORE_CASE), ""),
            anchor.attr("aria-label").replace(Regex("""\s+anime\s+detay\s+sayfas[ıi]$""", RegexOption.IGNORE_CASE), "")
        ).firstOrNull { it.isNotBlank() }
        val name = AnizmMatch.cleanTitle(rawTitle) ?: AnizmMatch.cleanTitle(titleFromSlug(seriesUrl)) ?: return null
        if (isInvalidTitle(name)) return null

        val score = el.selectFirst(".v3-catalog-score, .score, rating")?.text()
            ?.let { Regex("\\d{1,2}(?:\\.\\d)?").find(it)?.value?.toDoubleOrNull() }
            ?.takeIf { it > 0.0 && it <= 10.0 }
        return AnizmCard(
            name = name,
            url = seriesUrl,
            poster = bestImage(el),
            score = score,
            episodeHint = AnizmMatch.episodeOf(anchor.text())
                ?: el.selectFirst(".v3-card-copy span, .v3-card-tag")?.text()
                    ?.let { tag -> AnizmMatch.episodeOf(tag) }
                ?: episodeNumberFromUrl(href)
        )
    }

    /** Arama sonuç sayfasındaki JSON'u (searchAnime / getAnimeListForSearch) karta çevirir. */
    fun cardFromJsonFields(
        base: String,
        title: String?,
        slug: String?,
        poster: String?,
        year: Int?,
        score: Double?,
        altNames: List<String>
    ): AnizmCard? {
        val cleanSlug = slug?.trim()?.trim('/') ?: return null
        if (cleanSlug.isEmpty()) return null
        val name = AnizmMatch.cleanTitle(title) ?: AnizmMatch.cleanTitle(titleFromSlug(cleanSlug)) ?: return null
        if (isInvalidTitle(name)) return null
        val url = if (cleanSlug.startsWith("http")) cleanSlug else "$base/${cleanSlug.removePrefix("/")}"
        // Poster değeri siteden üç biçimde gelebiliyor:
        //  1) tam adres            → olduğu gibi
        //  2) "storage/pcovers/x"  → kök göreli
        //  3) yalnızca dosya adı   → /storage/pcovers/ altında
        val posterUrl = poster?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            ?.let { raw ->
                when {
                    raw.startsWith("http") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("/") -> "$base$raw"
                    raw.contains('/') -> "$base/${raw.removePrefix("/")}"
                    else -> "$base/storage/pcovers/$raw"
                }
            }?.let(::largeCover)
        return AnizmCard(
            name = name,
            url = url,
            poster = posterUrl,
            score = score?.takeIf { it > 0.0 && it <= 10.0 },
            episodeHint = null,
            altNames = altNames
        )
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

    private fun yearFrom(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("\\b(19\\d{2}|20\\d{2})\\b").find(text)?.value?.toIntOrNull()
    }

    fun parseDetail(doc: Document, base: String, pageUrl: String): AnizmDetail {
        val rawTitle = firstText(
            doc,
            // v3: h1 "Sousou no Frieren izle" yazıyor; bölüm bölümünün başlığı çıplak ad.
            "#bolumler .v3-section-kicker",
            "header.v3-anime-detail-header h1",
            "h1.anizm_pageTitle",
            "h2.anizm_pageTitle",
            "h1.animeName",
            "h1.anime-title",
            "h1.title",
            "div.animeDetail-title h1",
            "h1"
        ) ?: meta(doc, "meta[property=og:title]", "meta[name=twitter:title]")
            ?: doc.selectFirst("title")?.text()

        val title = AnizmMatch.cleanTitle(rawTitle)
            ?: AnizmMatch.cleanTitle(titleFromSlug(pageUrl))
            ?: "Anizm"

        val poster = imageUrl(
            doc.selectFirst(".v3-anime-detail-cover img, div.infoPosterImg > img, div.infoPosterImg img, img.animePoster, .anizm_poster img")
                ?: doc.selectFirst("div.poster img, .poster-image img, img.img-fluid, article img"),
            base = pageUrl
        ) ?: meta(doc, "meta[itemprop=image]", "meta[property=og:image]", "meta[property=og:image:secure_url]")
            ?.let { absoluteImage(pageUrl, it) }

        val plot = firstText(
            doc,
            "p#v3-anime-summary-content",
            "#v3-anime-summary-content",
            "div.infoDesc",
            "div.anizm_conText",
            "div.animeDescription",
            "div.anime-description",
            "div.ozet",
            "p.text-muted"
        ) ?: meta(doc, "meta[itemprop=description]", "meta[property=og:description]", "meta[name=description]")

        val tags = LinkedHashSet<String>()
        doc.select(
            ".v3-anime-detail-tags a, " +
                "span.dataValue span.ui.label, span.dataValue span.tag span.label, " +
                "a.ui.label[href*=/kategoriler/], a[href*=/temalar/], a.genre, div.tags a"
        ).forEach { element ->
            val text = element.text().trim()
            if (text.length in 2..40 && !isInvalidTitle(text)) tags.add(text)
        }

        val infoText = doc.body()?.text() ?: ""
        val year = doc.select(".v3-anime-detail-meta span, .v3-anime-detail-meta li")
            .map { it.text().trim() }
            .firstOrNull { it.matches(Regex("^\\d{4}$")) && it.toInt() in 1950..2100 }
            ?.toIntOrNull()
            ?: yearFrom(firstText(doc, "span.dataTitle:contains(Yapım Yılı) + span"))
            ?: yearFrom(Regex("(?i)yap[ıi]m\\s*y[ıi]l[ıi]\\s*[:\\-]?\\s*(\\d{4})").find(infoText)?.value)
            ?: yearFrom(meta(doc, "meta[property=og:title]"))

        val scoreRaw = firstText(
            doc,
            "span.dataTitle:contains(Puan) + span",
            "span.dataValue:contains(Puan)",
            "div.star span",
            "span.malPoint"
        )
        val score = scoreRaw?.let { Regex("\\d{1,2}(?:[.,]\\d)?").find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }
            ?.takeIf { it > 0.0 && it <= 10.0 }

        val studio = firstText(doc, "span.dataTitle:contains(Stüdyo) + span", "span.dataTitle:contains(Studyosu) + span")
        val typeText = firstText(doc, "span.dataTitle:contains(Tür) + span", "span.dataTitle:contains(Tip) + span")

        return AnizmDetail(
            title = title,
            poster = poster,
            plot = plot?.take(4000),
            tags = tags.toList(),
            year = year,
            score = score,
            studio = studio,
            typeText = typeText,
            episodes = parseEpisodes(doc, base, pageUrl)
        )
    }

    /**
     * Bölüm listesi. Sayfa hangi temada olursa olsun (v2 "episodeListTabContent",
     * v3 "animeDetail-items") tek kural uygulanır: bağlantının yolundan bölüm numarası
     * çıkarılabiliyorsa ve yol **bu animenin** slug'ıyla başlıyorsa bölümdür.
     */
    fun parseEpisodes(doc: Document, base: String, pageUrl: String): List<AnizmEpisode> {
        val targetSeries = seriesPathOf(pageUrl)
        val strict = LinkedHashMap<String, AnizmEpisode>()
        /** Seri yolu tutmasa da **bölüm eki** taşıyan bağlantılar (film sayfaları). */
        val episodeLike = LinkedHashMap<String, AnizmEpisode>()
        val loose = LinkedHashMap<String, AnizmEpisode>()
        // Numara etiketten/URL'den okunamazsa sıra numarası verilir (yalnızca düzenli
        // bölümler sayılır; ön gösterim/çift bölüm sayacı kaydırmaz).
        var counter = 0

        val pageKey = pageUrl.trimEnd('/').substringBefore('?').substringBefore('#')

        fun consider(anchor: Element, forceNumber: Int? = null) {
            val url = absolute(base, anchor.attr("href")) ?: return
            if (!isSiteUrl(url)) return
            // Sayfanın KENDİSİNE giden bağlantı (breadcrumb / kanonik adres) bölüm değildir.
            if (url.trimEnd('/').substringBefore('?').substringBefore('#') == pageKey) return
            if (strict.containsKey(url) || loose.containsKey(url)) return

            // v3 bölüm kartı: `<a data-episode-title="1. Bölüm">…<strong>…</strong>`.
            // Uzun bağlantı metni ("1 1. Bölüm Sousou … · Türkçe altyazılı izle")
            // yalnızca son çaredir.
            val label = anchor.attr("data-episode-title").trim()
                .ifBlank { anchor.selectFirst("strong")?.text()?.trim().orEmpty() }
                .ifBlank { anchor.text().trim() }
            val lower = label.lowercase()
            if (url.contains("fragman") || lower.contains("fragman")) return
            if (url.contains("-pv-") || lower == "pv" || lower.endsWith(" pv")) return

            // "215-216. Bölüm", "1-2. Bölüm (Ön Gösterim)": tek numara vermek
            // gerçek bölümleri kaydırır → 0 (yine oynatılabilir).
            val special = RANGE_EPISODE.containsMatchIn(label) ||
                lower.contains("önizleme") || lower.contains("ön gösterim") ||
                url.contains("onizleme") || url.contains("on-gosterim")

            val number: Int = forceNumber ?: if (special) {
                0
            } else {
                counter += 1
                // SIRA ÖNEMLİ (fixture: One Piece 1180): `data-episode-number`
                // sitenin İÇ sırasıdır ve kayar → en sona bırakıldı.
                AnizmMatch.episodeOf(label)
                    ?: episodeNumberFromUrl(url)
                    ?: anchor.attr("data-episode-number").trim().toIntOrNull()
                    ?: counter
            }

            val date = anchor.selectFirst("small, .date, .episode-date, time")?.text()?.trim()
            val episode = AnizmEpisode(
                url = url,
                number = number,
                // "2. Bölüm" gibi bilgi taşımayan etiketler temizlenince boşalır;
                // o durumda ham etiket korunur (oynatıcıda "2. Bölüm" görünür).
                name = AnizmMatch.cleanTitle(label) ?: label.takeIf { it.isNotBlank() }
                    ?: "$number. Bölüm",
                date = date,
                // Bölüm küçük görseli; yoksa detay sayfasının posteri kullanılır
                // (Anizm.kt `posterUrl = episode.poster ?: detail.poster`).
                poster = imageUrl(anchor.selectFirst("img"), base)
            )
            loose[url] = episode
            if (containsEpisodeSuffix(url)) episodeLike[url] = episode
            val series = seriesPathOf(url)
            if (targetSeries == null || series == null || series == targetSeries) strict[url] = episode
        }

        // 1) v3: tüm bölümler tek ızgarada (12'lik gruplar istemci tarafında).
        for (anchor in doc.select("[data-anime-episode-grid] > a[href]")) consider(anchor)
        // 2) v3 ikinci yol: #bolumler içindeki bölüm bağlantıları
        //    (`-N-bolum` VE tiresiz `-41bolum` biçimleri).
        if (strict.isEmpty()) {
            for (anchor in doc.select("#bolumler a[href]")) {
                if (containsEpisodeSuffix(anchor.attr("href"))) consider(anchor)
            }
        }
        // 3) Eski kuşak kapsayıcılar (Aniyomi: "div.episodeListTabContent div > a").
        if (strict.isEmpty()) {
            for (anchor in doc.select(
                "div.episodeListTabContent div > a, div.episodeListTabContent a[href], " +
                    "div.episodeListTabContent li a, div.animeDetail-items li a, " +
                    "div.animeDetail-items ol a, div.animeDetail-playlist li a, li:has(.episode-li) a"
            )) {
                consider(anchor)
            }
        }
        // 4) Seri yoluna göre dar tarama (host'tan bağımsız).
        //    Eski sürüm `"$root-"` ön ekini karşılaştırıyordu: sayfa
        //    anizm.net'ten, bağlantılar anizm.com.tr'den geldiğinde hiçbir şey
        //    eşleşmiyor ve dizi sayfasındaki TÜM bağlantılar (ilgili animeler
        //    dahil) bölüm sanılıyordu.
        if (strict.isEmpty() && targetSeries != null) {
            for (anchor in doc.select("a[href]")) {
                val url = absolute(base, anchor.attr("href")) ?: continue
                if (seriesPathOf(url) == targetSeries) consider(anchor)
            }
        }
        if (strict.isEmpty()) {
            for (anchor in doc.select("a[href]")) consider(anchor)
        }

        // Seçim sırası: seri yolu eşleşenler → bölüm eki taşıyanlar → son çare hepsi.
        // Film/OVA sayfalarında bölüm slug'ı seri slug'ından FARKLI oluyor
        // (ör. `…-the-last-attack-shingeki-…---the-last-attack-bolum-izle`);
        // eski sürüm bu durumda sayfadaki TÜM bağlantıları bölüm sayıyordu.
        // Bölüm eki taşıyanlar her zaman önce gelir: seri yolu eşleşmesi
        // breadcrumb/kanonik bağlantı yüzünden yanlış olumlu verebiliyor.
        val realEpisodes = strict.filterKeys { containsEpisodeSuffix(it) }
        val chosen = when {
            realEpisodes.isNotEmpty() -> realEpisodes.values
            strict.isNotEmpty() -> strict.values
            episodeLike.isNotEmpty() -> episodeLike.values
            else -> loose.values
        }
        return chosen
            .distinctBy { it.url }
            .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.url }))
    }

    /** Bağlantı yolunda bölüm eki var mı? (`-12-bolum-izle`, `-41bolum-izle`) */
    private fun containsEpisodeSuffix(href: String): Boolean {
        val last = href.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return EPISODE_PATH.containsMatchIn(last) || NAMELESS_EPISODE_SUFFIX.containsMatchIn(last)
    }

    /** "215-216. Bölüm" / "1-2. Bölüm" gibi birleşik bölüm etiketi. */
    private val RANGE_EPISODE = Regex(
        "\\d{1,4}\\s*[-–]\\s*\\d{1,4}\\.?\\s*[Bb][oöô]l[uüû]m"
    )
}
