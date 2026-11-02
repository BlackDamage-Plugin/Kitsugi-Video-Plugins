package com.hexated

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Sözleşme testleri (ağsız).**
 *
 * Girdiler `src/test/resources/fixtures/` altındaki **gerçek** canlı çıktılardır
 * (02.11.2026, anizm.net ↔ anizm.com.tr):
 *
 *  - `search_one_piece.html` — `/arama?q=one+piece`
 *  - `detail_movie.html`     — film detay sayfası
 *  - `episode_78177.html`    — +18 işaretli bölüm sayfası (yaş kapısı diyalogu **dahil**)
 *  - `cevirmen_78177_101517.json` — `/bolum/78177/cevirmen/101517` (15 video)
 *  - `video_1739970.json`    — `/bolum/78177/video/1739970` (göreli `/player/…`)
 *  - `anizmplayer_getvideo.json` — `getVideo` cevabı (`master.txt` + imzalı m3u8)
 */
class AnizmApiTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    private val episodeUrl =
        "https://anizm.com.tr/shingeki-no-kyojin-movie-kanketsu-hen-the-last-attack-" +
            "shingeki-no-kyojin-movie-kanketsu-hen---the-last-attack-bolum-izle"

    // ------------------------------------------------------------------
    // Bölüm sayfası → çevirmenler
    // ------------------------------------------------------------------

    @Test
    fun `gercek bolum sayfasindan cevirmeni okur`() {
        val doc = Jsoup.parse(fixture("episode_78177.html"), episodeUrl)
        val translators = AnizmApi.parseTranslators(doc, episodeUrl)

        assertEquals("çevirmen sayısı", 1, translators.size)
        val only = translators.first()
        assertEquals("Seicode", only.name)
        assertEquals("https://anizm.com.tr/bolum/78177/cevirmen/101517", only.sourcesUrl)
    }

    @Test
    fun `bolum kimligi raporve izleme baglantisindan okunur`() {
        val doc = Jsoup.parse(fixture("episode_78177.html"), episodeUrl)
        assertEquals("78177", AnizmApi.episodeId(doc, episodeUrl))
        assertEquals("78177", AnizmApi.episodeIdOf(episodeUrl))
    }

    /** +18 diyalogu içindeki bağlantılar kaynak sanılmamalı. */
    @Test
    fun `yas kapisi diyalogu yok sayilir`() {
        val html = """
            <html><body>
              <dialog data-v3-content-warning data-rating="18">
                 <button data-sources="https://anizm.com.tr/bolum/1/cevirmen/999">Kabul et</button>
              </dialog>
              <button class="v3-translator-button" data-v3-translator
                      data-translator-id="101517" data-sources="/bolum/78177/cevirmen/101517"
                      title="Seicode">TR Seicode</button>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, episodeUrl)
        val translators = AnizmApi.parseTranslators(doc, episodeUrl)

        assertEquals(1, translators.size)
        assertTrue(translators.first().sourcesUrl.endsWith("/bolum/78177/cevirmen/101517"))
    }

    /** `data-sources` yoksa kimlik + bölüm kimliğinden yol kurulur (uydurma yok). */
    @Test
    fun `data-sources yoksa yol kimlikten kurulur`() {
        val html = """
            <html><body>
              <div data-progress="/bolum/4242/izleme"></div>
              <button data-v3-translator data-translator-id="77" title="Fansub X">X</button>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, episodeUrl)
        val translators = AnizmApi.parseTranslators(doc, episodeUrl)

        assertEquals(1, translators.size)
        assertEquals("Fansub X", translators.first().name)
        assertTrue(translators.first().sourcesUrl.endsWith("/bolum/4242/cevirmen/77"))
    }

    // ------------------------------------------------------------------
    // Çevirmen JSON'u → videolar
    // ------------------------------------------------------------------

    @Test
    fun `cevirmen jsonundan on bes video cikar`() {
        val body = fixture("cevirmen_78177_101517.json")
        val videos = AnizmApi.parseVideos(body, "https://anizm.com.tr/bolum/78177/cevirmen/101517")

        assertEquals(15, videos.size)
        assertEquals("1739970", videos.first().id)
        assertEquals("Aincrad (Reklamsız)", videos.first().name)
        assertTrue("Vidmoly listede", videos.any { it.name == "Vidmoly" })
        assertTrue("Sistenn listede", videos.any { it.name.startsWith("Sistenn") })
    }

    @Test
    fun `video adresi cevirmen yolundan kurulur`() {
        assertEquals(
            "https://anizm.com.tr/bolum/78177/video/1739970",
            AnizmApi.videoUrl("https://anizm.com.tr/bolum/78177/cevirmen/101517", "1739970")
        )
    }

    @Test
    fun `eski kusak html cevabi da okunur`() {
        val body = """{"data":"<div><a video=\"/bolum/9/video/4242\">FirePlayer</a></div>"}"""
        val videos = AnizmApi.parseVideos(body, "https://anizm.com.tr/bolum/9/cevirmen/3")
        assertEquals(1, videos.size)
        assertEquals("4242", videos.first().id)
    }

    // ------------------------------------------------------------------
    // /video → /player  (göreli adres ATILMAZ)
    // ------------------------------------------------------------------

    @Test
    fun `goreli player adresi mutlaklastirilir`() {
        val body = fixture("video_1739970.json")
        val url = AnizmApi.parsePlayerUrl(body, "https://anizm.com.tr/bolum/78177/video/1739970")
        assertEquals("https://anizm.com.tr/player/1739970", url)
    }

    @Test
    fun `mutlak player adresi korunur`() {
        val body = """{"success":true,"url":"https://anizm.net/player/1739970"}"""
        val url = AnizmApi.parsePlayerUrl(body, "https://anizm.com.tr/bolum/78177/video/1739970")
        assertEquals("https://anizm.net/player/1739970", url)
    }

    @Test
    fun `player html iframe icinden okunur`() {
        val body = """{"success":true,"player":"<iframe src=\"https://anizmplayer.com/video/abc123def456\"></iframe>"}"""
        val url = AnizmApi.parsePlayerUrl(body, "https://anizm.com.tr/bolum/78177/video/1")
        assertEquals("https://anizmplayer.com/video/abc123def456", url)
    }

    @Test
    fun `basarisiz cevap oynatici uretmez`() {
        assertEquals(null, AnizmApi.parsePlayerUrl("""{"success":false}""", "https://anizm.com.tr/x"))
    }

    // ------------------------------------------------------------------
    // anizmplayer getVideo → securedLink önceliği
    // ------------------------------------------------------------------

    @Test
    fun `anizmplayer imzali m3u8 tercih edilir master-txt edilmez`() {
        val parsed = AnizmApi.parseAnizmPlayerMedia(fixture("anizmplayer_getvideo.json"))

        assertEquals(1, parsed.media.size)
        val link = parsed.media.first()
        assertTrue("imzalı master", link.startsWith("https://anizmplayer.com/cdn/hls/"))
        assertTrue("m3u8 uzantılı", link.substringBefore('?').endsWith(".m3u8"))
        assertTrue("imza parametreli", link.contains("md5=") && link.contains("expires="))
        assertEquals(1, parsed.textual.size)
        assertTrue(parsed.textual.first().endsWith("master.txt"))
    }

    @Test
    fun `yalnizca metin kopya varsa medya listesi bos doner`() {
        val body = """{"hls":true,"videoSource":"https://anizmplayer.com/cdn/hls/x/master.txt"}"""
        val parsed = AnizmApi.parseAnizmPlayerMedia(body)
        assertTrue(parsed.media.isEmpty())
        assertEquals(1, parsed.textual.size)
    }

    // ------------------------------------------------------------------
    // Arama + detay (gerçek HTML)
    // ------------------------------------------------------------------

    @Test
    fun `gercek arama sayfasindan kartlar cikar`() {
        val doc = Jsoup.parse(fixture("search_one_piece.html"), "https://anizm.com.tr")
        val cards = AnizmSite.parseCards(doc, "https://anizm.com.tr")

        assertTrue("en az 10 kart (bulunan ${cards.size})", cards.size >= 10)
        val onePiece = cards.firstOrNull { it.name == "One Piece" }
        assertNotNull("One Piece kartı", onePiece)
        assertEquals("https://anizm.com.tr/one-piece", onePiece!!.url)
        assertNotNull("poster", onePiece.poster)
        assertTrue(
            "poster yolu: ${onePiece.poster}",
            onePiece.poster!!.contains("/storage/pcovers/3")
        )
    }

    @Test
    fun `gercek detay sayfasindan kunye ve bolum cikar`() {
        val url = "https://anizm.com.tr/shingeki-no-kyojin-movie-kanketsu-hen-the-last-attack"
        val doc = Jsoup.parse(fixture("detail_movie.html"), url)
        val detail = AnizmSite.parseDetail(doc, "https://anizm.com.tr", url)

        assertTrue("başlık: ${detail.title}", detail.title.contains("Shingeki", ignoreCase = true))
        assertNotNull("poster", detail.poster)
        assertTrue("etiketler", detail.tags.isNotEmpty())
        assertTrue("bölüm listesi boş olmamalı", detail.episodes.isNotEmpty())
        assertTrue(
            "bölüm adresi: ${detail.episodes.first().url}",
            detail.episodes.first().url.contains("-bolum-izle")
        )
        assertFalse("bölüm adresi http olmalı", detail.episodes.first().url.startsWith("/"))
    }

    // ------------------------------------------------------------------
    // Dizi sayfası → bölümler (gerçek HTML: #bolumler)
    // ------------------------------------------------------------------

    private fun seriesDoc() = Jsoup.parse(
        fixture("series_one_piece_bolumler.html"),
        "https://anizm.com.tr/one-piece"
    )

    @Test
    fun `gercek dizi sayfasindan bolumler artan sirada cikar`() {
        val episodes = AnizmSite.parseEpisodes(
            seriesDoc(),
            "https://anizm.com.tr",
            "https://anizm.com.tr/one-piece"
        )

        assertEquals("bölüm sayısı", 33, episodes.size)
        val numbers = episodes.mapNotNull { it.number }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(1, numbers.first())
        assertEquals(1180, numbers.last())
        assertEquals(
            "https://anizm.com.tr/one-piece-1-bolum-izle",
            episodes.first().url
        )
        assertEquals("1. Bölüm", episodes.first().name)
    }

    @Test
    fun `dizi sayfasinda ilgili animeler bolum sanilmaz`() {
        val episodes = AnizmSite.parseEpisodes(
            seriesDoc(),
            "https://anizm.com.tr",
            "https://anizm.com.tr/one-piece"
        )
        assertTrue(
            "ilgili anime sızmamalı: ${episodes.map { it.url }.filter { u -> !u.contains("-bolum-izle") }}",
            episodes.all { it.url.contains("-bolum-izle") }
        )
        assertFalse(episodes.any { it.url.contains("fan-letter") || it.url.contains("film-gold") })
    }

    /** Sayfa anizm.net'ten, bağlantılar anizm.com.tr'den gelse de bölümler bulunur. */
    @Test
    fun `farkli alan adindaki bolum baglantilari da eslesir`() {
        val episodes = AnizmSite.parseEpisodes(
            seriesDoc(),
            "https://anizm.com.tr",
            "https://anizm.net/one-piece"
        )
        assertEquals(33, episodes.size)
    }
}
