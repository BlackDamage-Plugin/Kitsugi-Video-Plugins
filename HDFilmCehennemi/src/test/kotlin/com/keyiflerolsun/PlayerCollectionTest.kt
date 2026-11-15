package com.keyiflerolsun

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCollectionTest {
    @Test fun `fast player is emitted before slow player completes`() = runBlocking {
        val emitted = mutableListOf<String>()
        val found = collectPlayerResults(listOf("slow", "fast"), 250, resolve = {
            if (it == "slow") delay(2_000) else delay(10)
            it
        }, emit = { emitted.add(it) })
        assertTrue(found)
        assertEquals(listOf("fast"), emitted)
    }

    @Test fun `one failed player does not block another`() = runBlocking {
        val emitted = mutableListOf<String>()
        val found = collectPlayerResults(listOf("bad", "good"), 1_000, resolve = {
            if (it == "bad") error("failure")
            it
        }, emit = { emitted.add(it) })
        assertTrue(found)
        assertEquals(listOf("good"), emitted)
    }

    @Test fun `timeout without result returns false`() = runBlocking {
        assertFalse(collectPlayerResults(listOf(1), 30, resolve = {
            delay(1000)
            it
        }, emit = {}))
    }
    @Test fun `page and API sharing embed fetch only once`() = runBlocking {
        val requests = SingleFlight<String, String>()
        val emitted = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var calls = 0
        val found = collectPlayerResults(listOf("page", "api"), 1000, resolve = {
            val embed = "https://example.test/rplayer/id/"
            val stream = requests.get(embed) {
                calls++
                delay(30)
                "https://cdn.test/master.m3u8?token=$calls"
            } ?: return@collectPlayerResults null
            embed to stream
        }, emit = { (embed, stream) -> if (seen.add(embed)) emitted.add(stream) })
        assertTrue(found)
        assertEquals(1, calls)
        assertEquals(1, emitted.size)
    }

    @Test fun `different embeds remain independent`() = runBlocking {
        val requests = SingleFlight<String, String>()
        val emitted = mutableListOf<String>()
        collectPlayerResults(listOf("close", "rapid"), 1000,
            resolve = { key -> requests.get(key) { key } },
            emit = { emitted.add(it) })
        assertEquals(setOf("close", "rapid"), emitted.toSet())
    }
    @Test fun `slow synchronous decode does not prevent another player being reported`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val delivered = kotlinx.coroutines.CompletableDeferred<String>()
        val work = launch {
            collectPlayerResults(listOf("slow", "fast"), 2_000, resolve = { player ->
                if (player == "slow") runPlayerDecoding {
                    started.countDown()
                    release.await()
                    player
                } else {
                    while (started.count != 0L) delay(1)
                    player
                }
            }, emit = { delivered.complete(it) })
        }
        try {
            assertEquals("fast", kotlinx.coroutines.withTimeout(1_000) { delivered.await() })
        } finally {
            release.countDown()
            work.join()
        }
    }
}
