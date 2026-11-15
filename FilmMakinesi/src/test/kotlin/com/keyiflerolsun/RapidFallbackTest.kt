package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RapidFallbackTest {
    @Test fun `shared episode ID maps to the other player`() {
        assertEquals("https://www.hdfilmcehennemi.nl/rplayer/6d4mkrakdi1a/",
            RapidFallback.playerUrl("https://rapid.filmmakinesi.to/embed-6d4mkrakdi1a/"))
    }
    @Test fun `unknown hosts and ambiguous urls are never rewritten`() {
        listOf("https://evil.test/embed-6d4mkrakdi1a/",
            "https://rapid.filmmakinesi.to.evil.test/embed-6d4mkrakdi1a/",
            "https://rapid.filmmakinesi.to/embed-short/",
            "https://rapid.filmmakinesi.to/embed-6d4mkrakdi1a/?token=x",
            "http://rapid.filmmakinesi.to/embed-6d4mkrakdi1a/",
            "https://rapid.filmmakinesi.to/video/embed/6d4mkrakdi1a/").forEach {
            assertNull(it, RapidFallback.playerUrl(it))
        }
    }
    @Test fun `fast fallback wins when original stalls and source appears once`() = kotlinx.coroutines.runBlocking {
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val attempts = listOf("original", "fallback")
        collectPlayerResults(attempts, 150, resolve = { route ->
            if (route == "original") kotlinx.coroutines.delay(500) else kotlinx.coroutines.delay(10)
            "rapid-id" to route
        }, emit = { (key, route) -> if (seen.add(key)) results.add(route) })
        assertEquals(listOf("fallback"), results)
    }
    @Test fun `two successful routes still produce a single source`() = kotlinx.coroutines.runBlocking {
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        collectPlayerResults(listOf("original", "fallback"), 500, resolve = { route ->
            "rapid-id" to route
        }, emit = { (key, route) -> if (seen.add(key)) results.add(route) })
        assertEquals(1, results.size)
    }
}
