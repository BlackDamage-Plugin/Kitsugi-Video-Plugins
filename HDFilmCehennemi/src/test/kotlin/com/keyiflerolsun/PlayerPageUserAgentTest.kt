package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPageUserAgentTest {
    @Test
    fun `short agent only for close embed host`() {
        assertEquals("Mozilla/5.0", playerPageUserAgent("https://hdfilmcehennemi.mobi/video/embed/example"))
        assertEquals("Mozilla/5.0", playerPageUserAgent("https://closeload.filmmakinesi.to/video/embed/example"))
        assertEquals(PlayerResolver.USER_AGENT, playerPageUserAgent("https://www.hdfilmcehennemi.nl/rplayer/example/"))
        assertEquals(PlayerResolver.USER_AGENT, playerPageUserAgent("https://hdfilmcehennemi.mobi.example.com/video/embed/example"))
        assertEquals(PlayerResolver.USER_AGENT, playerPageUserAgent("not a url"))
    }
}
