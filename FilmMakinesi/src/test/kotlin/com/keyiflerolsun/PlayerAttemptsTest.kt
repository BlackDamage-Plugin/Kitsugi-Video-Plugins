package com.keyiflerolsun

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerAttemptsTest {
    private val page = "https://filmmakinesi.to/dizi/supernatural/sezon-1/bolum-1/"
    private val close = SourceLinks.Source("Close · DUAL", "https://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681", SourceLinks.Kind.CLOSE)
    private val rapid = SourceLinks.Source("Rapid · DUAL", "https://rapid.filmmakinesi.to/embed-6d4mkrakdi1a/", SourceLinks.Kind.RAPID)

    @Test fun `istekler yalnizca sayfada verilen iki adrese gider`() {
        val unrelated = SourceLinks.Source("HD", "https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/", SourceLinks.Kind.CLOSE)
        val attempts = PlayerAttempts.forSources(listOf(close, rapid, close, unrelated), page)
        assertEquals(2, attempts.size)
        assertEquals(listOf(close.url, rapid.url), attempts.map { it.url })
        assertTrue(attempts.all { it.key == it.url && it.referer == page && !it.label.contains("HDFilmCehennemi") })
        assertEquals("Mozilla/5.0", attempts.single { it.url == close.url }.userAgent)
        assertEquals(null, attempts.single { it.url == rapid.url }.userAgent)
    }

    @Test fun `iki kullanici aracisi tek kaynak olarak bildirilir`() = runBlocking {
        val attempts = PlayerAttempts.forSources(listOf(rapid), page)
        val emitted = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        collectPlayerResults(attempts, 1000, resolve = { attempt ->
            if (attempt.userAgent == null) delay(20)
            attempt.key
        }, emit = { key -> if (seen.add(key)) emitted.add(key) })
        assertEquals(listOf(rapid.url), emitted)
    }
}
