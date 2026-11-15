// ! Close/Rapid erişim katmanı regresyon testleri.
// ! "Close kaynağı bir süre sonra gelmiyor" şikâyetinin kök nedeni, erişimin tek bir
// ! User-Agent/Referer'e sabitlenmesiydi. Bu testler varyant sırasını ve kapsamı korur.

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class PlayerFetchTest {

    private val pageUrl = "https://www.hdfilmcehennemi.nl/insidious-out-of-the-further-26/"
    private val closeEmbed = "https://hdfilmcehennemi.mobi/video/embed/uYHaTijMbIV/?rapidrame_id=mviazhjhc2rx"
    private val rapidEmbed = "https://www.hdfilmcehennemi.nl/rplayer/mviazhjhc2rx/"

    @Test
    fun `close hostu once kisa user agent ile denenir`() {
        val variants = PlayerFetch.variants(closeEmbed, pageUrl)
        assertEquals(PlayerFetch.SHORT_USER_AGENT, variants.first().userAgent)
        assertEquals(pageUrl, variants.first().referer)
        // Masaüstü UA yedek olarak korunur: host değişirse kaynak düşmez.
        assertTrue(variants.any { it.userAgent == PlayerResolver.USER_AGENT })
        // Kök referer varyantı da denenir (bazı host'lar yalnız onu kabul ediyor).
        assertTrue(variants.any { it.referer == "https://www.hdfilmcehennemi.nl/" })
        // Mobil UA son çare olarak bulunur.
        assertEquals(PlayerFetch.MOBILE_USER_AGENT, variants.last().userAgent)
    }

    @Test
    fun `rapid oynatcisinda masaustu user agent once gelir`() {
        val variants = PlayerFetch.variants(rapidEmbed, pageUrl)
        assertEquals(PlayerResolver.USER_AGENT, variants.first().userAgent)
        assertTrue(variants.any { it.userAgent == PlayerFetch.SHORT_USER_AGENT })
    }

    @Test
    fun `yeni close hostu da close sayilir`() {
        assertTrue(PlayerFetch.isCloseLike("https://closeload.example.to/video/embed/abc/"))
        assertTrue(PlayerFetch.isCloseLike("https://closeload.top/video/embed/abc/"))
        assertTrue(PlayerFetch.isCloseLike(closeEmbed))
        // Alt alan adı taklidi close host'u sayılmaz (mevcut UA testiyle uyumlu).
        assertFalse(PlayerFetch.isCloseLike("https://hdfilmcehennemi.mobi.example.com/video/embed/abc"))
        assertFalse(PlayerFetch.isCloseLike(rapidEmbed))
        assertFalse(PlayerFetch.isCloseLike("bozuk adres"))
    }

    @Test
    fun `varyant tekrari yok ve referer yoksa da calisir`() {
        val variants = PlayerFetch.variants(closeEmbed, null)
        assertTrue(variants.isNotEmpty())
        assertEquals(variants.size, variants.distinctBy { it.userAgent to it.referer }.size)
        assertNull(variants.first().referer)
    }

    @Test
    fun `yalnizca calma listesi adresleri dogrulanir`() {
        assertTrue(StreamCheck.isPlaylistUrl("https://srv9.cdnimages1912.cyou/hls/a.mp4/txt/master.txt"))
        assertTrue(StreamCheck.isPlaylistUrl("https://s231.rapidrame.com/hls2/01/00037/x_,l,n,.urlset/master.m3u8?t=abc"))
        assertFalse(StreamCheck.isPlaylistUrl("https://cdn.example/film.mp4"))
        assertFalse(StreamCheck.isPlaylistUrl("https://cdn.example/film.mkv"))
    }

    @Test
    fun `oynatici adresi tanima yeni yollari kapsar`() {
        assertTrue(HdfcSources.isPlayerUrl(closeEmbed))
        assertTrue(HdfcSources.isPlayerUrl(rapidEmbed))
        assertTrue(HdfcSources.isPlayerUrl("https://closeload.filmmakinesi.to/video/embed/abc/"))
        assertTrue(HdfcSources.isPlayerUrl("https://rapid.example.to/embed-abc/"))
        assertTrue(HdfcSources.isPlayerUrl("https://player.hdfilmcehennemi.nl/e/abc12345/"))
        assertFalse(HdfcSources.isPlayerUrl("https://www.youtube.com/embed/abc"))
        assertFalse(HdfcSources.isPlayerUrl("https://baska.example.com/embed/abc"))
        assertEquals("Close", HdfcSources.nameForUrl("https://closeload.top/video/embed/abc/"))
        assertEquals("Rapidrame", HdfcSources.nameForUrl("https://rapid.example.to/embed-abc/"))
    }

    @Test
    fun `kok referer ve port korunur`() {
        assertEquals("https://www.hdfilmcehennemi.nl", PlayerFetch.originOf(pageUrl))
        assertEquals("http://example.test:8080", PlayerFetch.originOf("http://example.test:8080/oynat/1/"))
        assertNull(PlayerFetch.originOf(null))
        assertNull(PlayerFetch.originOf("bozuk adres"))
        // Aynı (User-Agent, Referer) çifti tekrar denenmez; sayfa referansı ilk sırada.
        val variants = PlayerFetch.variants(closeEmbed, pageUrl)
        assertEquals(variants.size, variants.distinctBy { it.userAgent to it.referer }.size)
        assertEquals(pageUrl, variants.first().referer)
        assertTrue(variants.any { it.referer == "https://www.hdfilmcehennemi.nl/" })
    }

    @Test
    fun `sayfadaki close dugmesi ilk sirada denenir`() {
        val html = """<div class="alternative-links" data-lang="en">
            <button class="alternative-link" data-video="222">Rapidrame</button>
            <button class="alternative-link" data-video="111">Close</button>
            </div>"""
        val buttons = HdfcSources.buttons(Jsoup.parse(html, pageUrl))
            .sortedBy { if (it.name.contains("close", true)) 0 else 1 }
        assertEquals(listOf("Close", "Rapidrame"), buttons.map { it.name })
    }
}
