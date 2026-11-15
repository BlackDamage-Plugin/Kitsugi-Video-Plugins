package com.keyiflerolsun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SearchPageTest {
    private val base = "https://filmmakinesi.to"

    @Test fun `form aramasinin sonucu dizi ve filmi ayirt eder`() {
        // Minimal structure from the user's saved supernatural search-result HTML.
        val html = """<div class="ic-sayfa"><div class="content-container"><div class="film-list"><div class="content row">
          <div class="item-relative"><a class="item" href="/dizi/supernatural-2005-fm8t9q/" data-title="Supernatural"><div class="thumbnail-outer"><img src="/uploads/postlar/afis/supernatural-2005.jpg-thumb-liste.webp" alt="Supernatural"></div></a></div>
          <div class="item-relative"><a class="item" href="/film/theater-of-the-supernatural-city-of-the-dead-izle-2022/" data-title="Theater of the Supernatural: City of the Dead"><img src="/uploads/postlar/afis/example.webp"></a></div>
        </div></div></div></div>"""
        val cards = SearchPage.results(Jsoup.parse(html, base), base)
        assertEquals(2, cards.size)
        assertEquals("Supernatural", cards[0].title)
        assertEquals("https://filmmakinesi.to/dizi/supernatural-2005-fm8t9q/", cards[0].url)
        assertEquals("https://filmmakinesi.to/uploads/postlar/afis/supernatural-2005.jpg-thumb-liste.webp", cards[0].poster)
        assertTrue(cards[0].series)
        assertFalse(cards[1].series)
    }

    @Test fun `arama cubugu adresleri dogru kodlanir`() {
        assertEquals("https://filmmakinesi.to/arama/?s=T%C3%BCrk%C3%A7e+%26+film", SearchPage.queryUrl(base, " Türkçe & film "))
        assertEquals("https://filmmakinesi.to/ajax/autocomplete/?s=supernatural", SearchPage.queryUrl(base, "supernatural", true))
    }

    @Test fun `onerilerde yalniz site film ve dizi baglantilari alinir`() {
        // Synthetic fragment: exact live autocomplete markup has not been captured.
        val html = """<a href="/dizi/supernatural-2005-fm8t9q/" title="Supernatural"><img data-src="/poster.webp"></a>
          <a href="/film/ornek/"><img alt="Örnek Film"></a>
          <a href="https://evil.example/dizi/fake/" title="Fake"></a>
          <a href="/arama/?s=other">Tüm sonuçlar</a>
          <a href="/dizi/supernatural-2005-fm8t9q/" title="duplicate"></a>"""
        val cards = SearchPage.suggestions(html, base)
        assertEquals(listOf("Supernatural", "Örnek Film"), cards.map { it.title })
        assertEquals(listOf(true, false), cards.map { it.series })
        assertEquals("https://filmmakinesi.to/poster.webp", cards.first().poster)
    }

    @Test fun `oneriler yalniz sonuc yoksa denenir ve iptal korunur`() = runBlocking {
        var used = false
        assertEquals(listOf(1), searchWithFallback(primary = { listOf(1) }, fallback = { used = true; listOf(2) }))
        assertFalse(used)
        assertEquals(listOf(2), searchWithFallback(primary = { emptyList<Int>() }, fallback = { listOf(2) }))
        assertEquals(listOf(2), searchWithFallback(primary = { throw IllegalStateException("network") }, fallback = { listOf(2) }))
        try {
            searchWithFallback<Int>(primary = { throw CancellationException("cancel") }, fallback = { fail("must not run"); emptyList() })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
