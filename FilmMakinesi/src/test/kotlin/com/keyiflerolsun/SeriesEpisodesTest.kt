package com.keyiflerolsun

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesEpisodesTest {
    @Test fun `sezon ve bolum numarasi basliktan degil adresten okunur`() {
        val html = """
            <a href="/dizi/supernatural-2005-fm8t9q/sezon-1/bolum-2/" class="btn">Sonraki</a>
            <a href="/dizi/supernatural-2005-fm8t9q/sezon-8/bolum-1/" class="item-ep">
              <div class="ep-date">03 Ekim 2012 / 43 dk</div>
              <div class="ep-title">8. Sezon / 1. Bölüm</div>
              <div class="ep-details"><span>We Need to Talk About Kevin</span></div>
            </a>
            <a href="/dizi/supernatural-2005-fm8t9q/sezon-1/bolum-1/" class="item-ep">
              <div class="ep-title">1. Sezon / 1. Bölüm</div>
            </a>
        """.trimIndent()
        val refs = SeriesEpisodes.list(Jsoup.parse(html))
        assertEquals(listOf(8 to 1, 1 to 1), refs.map { it.season to it.episode })
        assertEquals("8. Sezon / 1. Bölüm", refs[0].name)
    }
}
