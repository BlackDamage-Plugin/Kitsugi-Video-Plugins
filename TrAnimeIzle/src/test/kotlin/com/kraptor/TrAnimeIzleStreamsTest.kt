package com.kraptor

import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Network-free integration of the episode -> fansub -> source -> player chain. */
class TrAnimeIzleStreamsTest {
    private val base = "https://www.tranimeizle.live"

    private class FakeHttp(private val base: String) : TrStreamHttp {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun get(url: String, referer: String?): String? {
            if (url != "$base/attack-on-titan-1-bolum-izle") return null
            return """<div class="fansubSelector" data-fid="302" data-fad="TR Altyazılı"></div>
                <script>animeWatch.initialize(37, 566, 302, "TR Altyazılı", "https://www.tranimeizle.io/");</script>"""
        }
        override suspend fun postJson(url: String, json: String, referer: String?): String? {
            requests.add("$url $json")
            if (!url.endsWith("/api/fansubSources")) return null
            return (1..16).joinToString("") { """<li class="sourceBtn" data-id="$it"><p class="title">Kaynak $it</p></li>""" }
        }
        override suspend fun postEmpty(url: String, referer: String?): String? {
            requests.add(url)
            val id = url.substringAfterLast('/').toIntOrNull() ?: return null
            // Only the preVideoAd fallback produces source 16.
            if (id == 16 && url.contains("sourcePlayer")) return ""
            val ext = if (id == 15) "mpd" else "mp4"
            return """<iframe src="https://cdn.example/video-$id.$ext"></iframe>"""
        }
        override suspend fun postForm(url: String, form: Map<String, String>, referer: String?): String? = null
        override suspend fun getXhr(url: String, referer: String?): String? = null
        override suspend fun getWith(url: String, extra: Map<String, String>): String? = null
        override suspend fun bytes(url: String, referer: String?): ByteArray? = null
        override suspend fun solveCaptcha() = false
    }

    @Test
    fun allSourceButtonsAreAttemptedAndFallbackIsIndependent() = runBlocking {
        val http = FakeHttp(base)
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val count = TrAnimeIzleStreams("TrAnimeIzle", { base }, http)
            .load("$base/attack-on-titan-1-bolum-izle", {}, { links.add(it) })

        assertEquals(16, count)
        assertEquals(16, links.map { it.url }.distinct().size)
        assertEquals(com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH, links.single { it.url.endsWith(".mpd") }.type)
        assertTrue(http.requests.any { it.contains("\"EpisodeId\":566") && it.contains("\"FansubId\":302") })
        assertTrue((1..16).all { id -> http.requests.any { it.endsWith("/api/sourcePlayer/$id") } })
        assertTrue(http.requests.any { it.endsWith("/api/preVideoAd/16") })
    }
}
