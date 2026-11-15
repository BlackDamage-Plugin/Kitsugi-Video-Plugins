package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class PlayerFetchTest {

    private val pageUrl = "https://www.hdfilmcehennemi.nl/insidious-out-of-the-further-26/"
    private val closeEmbed = "https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/?rapidrame_id=6d4mkrakdi1a"
    private val rapidEmbed = "https://www.hdfilmcehennemi.nl/rplayer/6d4mkrakdi1a/"

    @Test
    fun `close hostu kisa user agent kullanir`() {
        assertEquals(PlayerFetch.SHORT_USER_AGENT, PlayerFetch.userAgentFor(closeEmbed))
        assertEquals(PlayerFetch.SHORT_USER_AGENT, playerPageUserAgent(closeEmbed))
        assertEquals(PlayerFetch.SHORT_USER_AGENT, playerPageUserAgent("https://closeload.example.to/video/embed/abc/"))
    }

    @Test
    fun `rapid oynatcisinda masaustu user agent kullanilir`() {
        assertEquals(PlayerResolver.USER_AGENT, PlayerFetch.userAgentFor(rapidEmbed))
        assertEquals(PlayerResolver.USER_AGENT, playerPageUserAgent(rapidEmbed))
    }

    @Test
    fun `cloudflare killer close mobi ve embed yoluna girmez`() {
        assertFalse(PlayerFetch.allowCloudflareKiller(closeEmbed))
        assertFalse(PlayerFetch.allowCloudflareKiller("https://closeload.top/video/embed/abc/"))
        assertFalse(PlayerFetch.allowCloudflareKiller(rapidEmbed))
        assertTrue(PlayerFetch.allowCloudflareKiller(pageUrl))
        assertFalse(PlayerFetch.isCloudflareChallenge(404, "text/html", "<html>cloudflare 404</html>"))
        assertTrue(PlayerFetch.isCloudflareChallenge(403, "text/html", "<html>Just a moment...</html>"))
        assertFalse(PlayerFetch.isCloudflareChallenge(200, "text/html", "<html>jwplayer</html>"))
    }

    @Test
    fun `yeni close hostu da close sayilir`() {
        assertTrue(PlayerFetch.isCloseLike("https://closeload.example.to/video/embed/abc/"))
        assertTrue(PlayerFetch.isCloseLike("https://closeload.top/video/embed/abc/"))
        assertTrue(PlayerFetch.isCloseLike(closeEmbed))
        assertFalse(PlayerFetch.isCloseLike("https://hdfilmcehennemi.mobi.example.com/video/embed/abc"))
        assertFalse(PlayerFetch.isCloseLike(rapidEmbed))
        assertFalse(PlayerFetch.isCloseLike("bozuk adres"))
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

    @Test
    fun `canli bolum sayfasindaki close iframe okunur`() {
        val html = """<div class="alternative-links" data-lang="en">
            <button class="alternative-link" data-video="264491">Close</button>
            <button class="alternative-link" data-video="234442">Rapidrame</button>
            </div>
            <iframe class="close" data-src="https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/?rapidrame_id=6d4mkrakdi1a" scrolling="no" allowfullscreen="true">"""
        val page = "https://www.hdfilmcehennemi.nl/supernatural-1-sezon-1-bolum/"
        val embeds = HdfcSources.pageEmbeds(Jsoup.parse(html, page), page, "https://www.hdfilmcehennemi.nl")
        assertEquals("https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/?rapidrame_id=6d4mkrakdi1a", embeds.first().url)
        assertEquals("Close", embeds.first().label)
        assertTrue(embeds.any { it.label == "Rapidrame" && it.url.contains("/rplayer/6d4mkrakdi1a/") })
        assertEquals(PlayerFetch.SHORT_USER_AGENT, playerPageUserAgent(embeds.first().url))
    }
}
