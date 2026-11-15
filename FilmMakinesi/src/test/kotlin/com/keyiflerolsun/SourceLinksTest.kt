// ! FilmMakinesi kaynak toplama testleri — canlı film ve bölüm sayfaları
// ! (24 Ekim 2026, src/test/resources/live).

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.jsoup.Jsoup
import java.io.File

class SourceLinksTest {

    private fun live(name: String): String {
        val stream = javaClass.getResourceAsStream("/live/$name")
        if (stream != null) return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val file = File("src/test/resources/live/$name")
        if (file.exists()) return file.readText(Charsets.UTF_8)
        error("Fixture bulunamadı: $name")
    }

    @Test
    fun `film sayfasinda Close ve Rapid bulunur`() {
        val pageUrl = "https://filmmakinesi.to/film/orumcek-adam-yepyeni-bir-gun-2026-fm3/"
        val sources = SourceLinks.collect(Jsoup.parse(live("fm_page_film.html"), pageUrl), pageUrl)
        assertEquals(
            listOf(
                SourceLinks.Source(
                    "Close · DUAL",
                    "https://closeload.filmmakinesi.to/video/embed/EWeEdu0Ig0q/?imdb_id=tt22084616",
                    SourceLinks.Kind.CLOSE
                ),
                SourceLinks.Source(
                    "Rapid · DUAL",
                    "https://rapid.filmmakinesi.to/embed-rsyi6h14btcs/",
                    SourceLinks.Kind.RAPID
                )
            ),
            sources
        )
    }

    @Test
    fun `bolum sayfasinda tek iframe kaynagi bulunur`() {
        val pageUrl = "https://filmmakinesi.to/dizi/happys-place-izle-2024/sezon-1/bolum-1/"
        val sources = SourceLinks.collect(Jsoup.parse(live("fm_page_episode.html"), pageUrl), pageUrl)
        assertEquals(
            listOf(SourceLinks.Source("Rapid", "https://rapid.filmmakinesi.to/embed-r6b2y7qlpt3r/", SourceLinks.Kind.RAPID)),
            sources
        )
    }

    @Test
    fun `gonderilen bolum yapisinda iki alternatif yinelenmeden okunur`() {
        val page = "https://filmmakinesi.to/dizi/example/sezon-1/bolum-1/"
        val html = """<div class="video-parts">
          <a class="btn active" data-video_url="https://closeload.filmmakinesi.to/video/embed/CLOSE/?imdb_id=tt123">DUAL Close</a>
          <a class="btn" data-video_url="https://rapid.filmmakinesi.to/embed-RAPID/">DUAL Rapid</a>
          </div><div class="after-player"><iframe data-src="https://closeload.filmmakinesi.to/video/embed/CLOSE/?imdb_id=tt123"></iframe></div>"""
        val result = SourceLinks.collect(Jsoup.parse(html, page), page)
        assertEquals(listOf(SourceLinks.Kind.CLOSE, SourceLinks.Kind.RAPID), result.map { it.kind })
    }

    @Test
    fun `baska sitenin iframe ve butonlari eklenmez`() {
        val page = "https://filmmakinesi.to/film/x/"
        val html = """<div class="video-parts">
          <a data-video_url="https://hdfilmcehennemi.mobi/video/embed/abc/">Close</a>
          <a data-video_url="https://rapid.filmmakinesi.to/embed-abc/">Rapid</a>
          </div><div class="after-player"><iframe src="https://www.hdfilmcehennemi.nl/rplayer/abc/"></iframe></div>"""
        val sources = SourceLinks.collect(Jsoup.parse(html, page), page)
        assertEquals(listOf("https://rapid.filmmakinesi.to/embed-abc/"), sources.map { it.url })
        assertNull(SourceLinks.normalize("https://foo.hdfilmcehennemi.mobi/video/embed/abc/", page))
    }

    @Test
    fun `adres normalizasyonu`() {
        val page = "https://filmmakinesi.to/film/x/"
        assertEquals("https://rapid.filmmakinesi.to/embed-a/", SourceLinks.normalize("//rapid.filmmakinesi.to/embed-a/", page))
        assertEquals("https://filmmakinesi.to/player/1", SourceLinks.normalize("/player/1", page))
        assertNull(SourceLinks.normalize("https://www.youtube.com/embed/xyz", page))
        assertNull(SourceLinks.normalize("#", page))
        assertNull(SourceLinks.normalize("javascript:void(0)", page))
    }

    @Test
    fun `kaynak adi bahis kelimelerinden arindirilir`() {
        assertEquals("Close", SourceLinks.labelFor("Close (HDrip Xbet)", "https://closeload.filmmakinesi.to/video/embed/a/"))
        assertEquals("Rapid · Türkçe Dublaj", SourceLinks.labelFor("Türkçe Dublaj Rapid", "https://rapid.filmmakinesi.to/embed-a/"))
    }
}
