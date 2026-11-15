package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloseFallbackTest {
    @Test fun `paired players from the shared episode map to hd close`() {
        assertEquals("https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/?rapidrame_id=6d4mkrakdi1a",
            CloseFallback.playerUrl("https://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681", "https://rapid.filmmakinesi.to/embed-6d4mkrakdi1a/"))
    }
    @Test fun `untrusted or ambiguous inputs are not rewritten`() {
        val rapid = "https://rapid.filmmakinesi.to/embed-6d4mkrakdi1a/"
        listOf("https://evil.test/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681",
            "https://closeload.filmmakinesi.to.evil.test/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681",
            "https://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?token=x",
            "http://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681").forEach {
            assertNull(CloseFallback.playerUrl(it, rapid))
        }
        assertNull(CloseFallback.playerUrl("https://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681", "https://evil.test/embed-6d4mkrakdi1a/"))
    }
}
