// ! HDFilmCehennemi sayfa/API ayrıştırma testleri — canlı sayfa ve
// ! /video/{id}/ yanıtları (24 Ekim 2026, src/test/resources/live).

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup
import java.io.File

class HdfcSourcesTest {

    private fun live(name: String): String {
        val stream = javaClass.getResourceAsStream("/live/$name")
        if (stream != null) return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val file = File("src/test/resources/live/$name")
        if (file.exists()) return file.readText(Charsets.UTF_8)
        error("Fixture bulunamadı: $name")
    }

    private val mainUrl = "https://www.hdfilmcehennemi.nl"
    private val pageUrl = "https://www.hdfilmcehennemi.nl/insidious-out-of-the-further-26/"

    @Test
    fun `dil sekmesi ve kaynak dugmeleri okunur`() {
        val buttons = HdfcSources.buttons(Jsoup.parse(live("hd_page_film.html"), pageUrl))
        assertEquals(
            listOf(
                HdfcSources.Button("325463", "Close", "Türkçe Altyazılı"),
                HdfcSources.Button("325422", "Rapidrame", "Türkçe Altyazılı")
            ),
            buttons
        )
        assertEquals("Close · Türkçe Altyazılı", buttons[0].label)
    }

    @Test
    fun `video API yanitindan oynatici adresi cikar`() {
        assertEquals(
            "https://hdfilmcehennemi.mobi/video/embed/uYHaTijMbIV/?rapidrame_id=mviazhjhc2rx",
            HdfcSources.embedFromApi(live("hd_api_close.json"), pageUrl)
        )
        assertEquals(
            "https://www.hdfilmcehennemi.nl/rplayer/mviazhjhc2rx/",
            HdfcSources.embedFromApi(live("hd_api_rapid.json"), pageUrl)
        )
        assertNull(HdfcSources.embedFromApi("{\"success\":false}", pageUrl))
    }

    @Test
    fun `sayfadaki varsayilan oynatici ve rapidrame_id yedegi`() {
        val embeds = HdfcSources.pageEmbeds(Jsoup.parse(live("hd_page_film.html"), pageUrl), pageUrl, mainUrl)
        assertEquals(
            listOf(
                HdfcSources.Embed("Close", "https://hdfilmcehennemi.mobi/video/embed/uYHaTijMbIV/?rapidrame_id=mviazhjhc2rx"),
                HdfcSources.Embed("Rapidrame", "https://www.hdfilmcehennemi.nl/rplayer/mviazhjhc2rx/")
            ),
            embeds
        )
    }

    @Test
    fun `gonderilen bolum yapisindaki iki dugme ve varsayilan iframe`() {
        val html = """<div class="alternative-links" data-lang="en">
            <button class="alternative-link" data-active="1" data-video="111">Close</button>
            <button class="alternative-link" data-active="" data-video="222">Rapidrame</button>
            </div><iframe class="close" data-src="https://hdfilmcehennemi.mobi/video/embed/CLOSE/?rapidrame_id=RAPID"></iframe>"""
        val doc = Jsoup.parse(html, pageUrl)
        assertEquals(listOf("Close", "Rapidrame"), HdfcSources.buttons(doc).map { it.name })
        assertEquals(listOf("Close", "Rapidrame"), HdfcSources.pageEmbeds(doc, pageUrl, mainUrl).map { it.label })
    }

    @Test
    fun `json metin alani cozulur`() {
        assertEquals("<a href=\"/x\">é</a>", HdfcSources.jsonStringField("{\"html\":\"<a href=\\\"\\/x\\\">\\u00e9<\\/a>\"}", "html"))
        assertTrue(HdfcSources.isPlayerUrl("https://www.hdfilmcehennemi.nl/rplayer/abc/"))
        assertTrue(!HdfcSources.isPlayerUrl("https://www.youtube.com/embed/abc"))
    }
}
