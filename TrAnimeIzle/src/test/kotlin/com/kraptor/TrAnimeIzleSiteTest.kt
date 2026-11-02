package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TrAnimeIzle HTML/oynatıcı çözümleyici testleri (ağsız, sentetik fixture).
 *
 * Seçiciler 2026-10 itibarıyla çalışan istemcilerden (Aniyomi `yuzono`, CloudStream
 * `UmyTrade/MyTV`) ve site.js zincirinden alınmıştır; testler tema değişiminde
 * hangi çıktının bozulduğunu gösterir.
 */
class TrAnimeIzleSiteTest {

    private val base = "https://www.tranimeizle.io"

    @Test
    fun defaultFansubIsReadFromActualInitializeShape() {
        val html = """<script>animeWatch.initialize(37, 566, 302, "TR Altyaz&#x131;l&#x131;", "https://www.tranimeizle.io/");</script>"""
        val doc = Jsoup.parse(html, base)
        assertEquals(566, TrAnimeIzleSite.episodeIdFrom(html, doc))
        assertEquals(listOf(302), TrAnimeIzleSite.fansubsFrom(doc).map { it.id })
    }

    @Test
    fun hiddenHoneypotIsNotAnEpisodeFallback() {
        val html = """<a href="/icerik-denetim-q4x9-1-bolum-izle" rel="nofollow" tabindex="-1" aria-hidden="true">.</a>"""
        assertTrue(TrAnimeIzleSite.parseEpisodes(Jsoup.parse(html, base), base, "$base/anime/test-izle").isEmpty())
    }

    @Test
    fun directVideoElementsResolveRelativeSources() {
        val html = """<video src="/media/video.mp4?token=123"><source src="../hls/master.m3u8?x=1&amp;y=2"></video>"""
        val urls = TrAnimeIzleSite.directMediaFrom(html, "$base/watch/episode")
        assertTrue(urls.contains("$base/media/video.mp4?token=123"))
        assertTrue(urls.contains("$base/hls/master.m3u8?x=1&y=2"))
    }

    // ------------------------------------------------------------------
    // Kartlar
    // ------------------------------------------------------------------

    @Test
    fun parsesFlxBlockCardsAndCollapsesEpisodeVariants() {
        val html = """
            <html><body><div class="post-body">
              <div class="flx-block" data-href="$base/one-piece-1120-bolum-izle">
                <img src="$base/uploads/one-piece.jpg">
                <div class="bar"><h4>One Piece 1120. Bölüm İzle</h4></div>
                <rating data-src="9.1"></rating>
              </div>
              <div class="flx-block" data-href="$base/one-piece-1119-bolum-izle">
                <img src="$base/uploads/one-piece.jpg">
                <div class="bar"><h4>One Piece 1119. Bölüm İzle</h4></div>
              </div>
              <div class="flx-block" data-href="$base/the-apothecary-diaries-3-sezon-12-bolum-izle">
                <img data-src="$base/uploads/apothecary.jpg">
                <div class="bar"><h4>The Apothecary Diaries 3. Sezon 12. Bölüm İzle</h4></div>
              </div>
            </div></body></html>
        """.trimIndent()

        val cards = TrAnimeIzleSite.parseCards(Jsoup.parse(html, base), base)

        // One Piece iki bölüm kartı → TEK seri kartı (en ileri bölüm ipucuyla).
        assertEquals(2, cards.size)
        val op = cards.first { it.name == "One Piece" }
        // Kart "son bölüm" adresi taşır; çözümleyici kanonik dizi adresine çevirir.
        assertEquals("$base/anime/one-piece-izle", op.url)
        assertEquals(1120, op.episodeHint)
        assertEquals("$base/uploads/one-piece.jpg", op.poster)
        assertEquals(9.1, op.score!!, 0.001)

        val apothecary = cards.first { it.name.contains("Apothecary") }
        assertEquals(3, apothecary.seasonHint)
        assertEquals("$base/uploads/apothecary.jpg", apothecary.poster)
    }

    @Test
    fun genericFallbackFindsCardsWhenThemeChanges() {
        val html = """
            <html><body>
              <div class="new-card">
                <a href="$base/bleach-1-bolum-izle"><img src="/uploads/bleach.jpg" alt="Bleach"></a>
                <a href="$base/bleach-1-bolum-izle"><h3>Bleach</h3></a>
              </div>
              <a href="$base/arama/naruto"><h3>Arama sayfası</h3><img src="/x.jpg"></a>
            </body></html>
        """.trimIndent()

        val cards = TrAnimeIzleSite.parseCards(Jsoup.parse(html, base), base)
        assertEquals(1, cards.size)
        assertEquals("Bleach", cards.first().name)
        assertEquals("$base/anime/bleach-izle", cards.first().url)
    }

    // ------------------------------------------------------------------
    // Detay + bölümler
    // ------------------------------------------------------------------

    @Test
    fun parsesDetailPage() {
        val pageUrl = "$base/one-piece-izle"
        val html = """
            <html><body>
              <div class="playlist-title"><h1>One Piece İzle</h1></div>
              <div class="poster"><img src="$base/uploads/op.jpg"></div>
              <div class="p-10"><p>Korsanlar çağında geçen bir hikâye.</p></div>
              <div class="tags-inner"><a href="/animeizle/aksiyon-anime-izle">Aksiyon</a>
                <a href="/animeizle/macera-anime-izle">Macera</a></div>
              <dl>
                <dd>Yapım Yılı</dd><dt>1999</dt>
                <dd>Bölüm Sayısı</dd><dt>1120/1120</dt>
                <dd>Anime Tipi</dd><dt>Dizi</dt>
              </dl>
              <span class="dt_rating_vgs">9.1</span>
            </body></html>
        """.trimIndent()

        val detail = TrAnimeIzleSite.parseDetail(Jsoup.parse(html, pageUrl), base, pageUrl)

        assertEquals("One Piece", detail.title)
        assertEquals("$base/uploads/op.jpg", detail.poster)
        assertTrue(detail.plot!!.contains("Korsanlar"))
        assertEquals(listOf("Aksiyon", "Macera"), detail.tags)
        assertEquals(1999, detail.year)
        assertEquals(9.1, detail.score!!, 0.001)
        assertEquals("Dizi", detail.typeText)
        assertEquals(1120, detail.totalEpisodes)
    }

    @Test
    fun parsesEpisodesWithGuaranteedSeasonAndNumber() {
        val pageUrl = "$base/one-piece-izle"
        val html = """
            <html><body>
              <div class="animeDetail-items"><ol>
                <li><a href="$base/one-piece-1-bolum-izle">
                  <div class="episode-li"></div><div class="etitle"><span>1. Bölüm</span></div>
                  <small class="author">25 Ekim 1999</small>
                </a></li>
                <li><a href="$base/one-piece-2-bolum-izle">
                  <div class="episode-li"></div><div class="etitle"><span>2. Bölüm</span></div>
                </a></li>
                <li><a href="$base/one-piece-2-bolum-izle"><div class="episode-li"></div><span>2. Bölüm</span></a></li>
              </ol></div>
              <div class="srelacionados">
                <a href="$base/bleach-5-bolum-izle"><img src="/b.jpg"><h3>Bleach 5. Bölüm</h3></a>
              </div>
            </body></html>
        """.trimIndent()

        val episodes = TrAnimeIzleSite.parseEpisodes(Jsoup.parse(html, pageUrl), base, pageUrl, "One Piece")

        assertEquals(2, episodes.size)
        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertTrue(episodes.all { it.season == 1 })
        assertEquals("1. Bölüm", episodes.first().name)
        assertEquals("25 Ekim 1999", episodes.first().date)
        assertTrue(episodes.all { it.url.contains("/one-piece-") })
    }

    @Test
    fun seasonFromPageTitleIsAppliedToAllEpisodes() {
        val pageUrl = "$base/the-apothecary-diaries-3-sezon-izle"
        val html = """
            <html><body>
              <div class="animeDetail-playlist"><ul>
                <li><a href="$base/the-apothecary-diaries-3-sezon-12-bolum-izle">12. Bölüm</a></li>
              </ul></div>
            </body></html>
        """.trimIndent()

        val episodes = TrAnimeIzleSite.parseEpisodes(
            Jsoup.parse(html, pageUrl), base, pageUrl, "The Apothecary Diaries 3. Sezon İzle"
        )
        assertEquals(1, episodes.size)
        assertEquals(3, episodes.first().season)
        assertEquals(12, episodes.first().number)
    }

    @Test
    fun episodeListFallsBackToUrlPattern() {
        val pageUrl = "$base/bleach-izle"
        val html = """
            <html><body>
              <div class="whatever"><a href="$base/bleach-4-bolum-izle">Bleach 4. Bölüm</a></div>
              <div class="whatever"><a href="$base/bleach-3-bolum-izle">Bleach 3. Bölüm</a></div>
            </body></html>
        """.trimIndent()

        val episodes = TrAnimeIzleSite.parseEpisodes(Jsoup.parse(html, pageUrl), base, pageUrl, "Bleach")
        assertEquals(listOf(3, 4), episodes.map { it.number })
    }

    // ------------------------------------------------------------------
    // Oynatıcı zinciri
    // ------------------------------------------------------------------

    @Test
    fun readsEpisodeIdFromAllKnownLocations() {
        assertEquals(
            4242,
            TrAnimeIzleSite.episodeIdFrom("<input type=\"hidden\" id=\"EpisodeId\" value=\"4242\">")
        )
        assertEquals(
            5151,
            TrAnimeIzleSite.episodeIdFrom("<script>animeWatch.initialize(77, 5151, 12, 0);</script>")
        )
        assertEquals(
            6161,
            TrAnimeIzleSite.episodeIdFrom("{\"EpisodeId\":6161,\"FansubId\":3}")
        )
        assertEquals(
            7070,
            TrAnimeIzleSite.episodeIdFrom("<li class=\"sourceBtn\" data-id=\"9\" data-eid=\"7070\">")
        )
        assertNull(TrAnimeIzleSite.episodeIdFrom("<html>yok</html>"))
    }

    @Test
    fun parsesFansubSelectors() {
        val html = """
            <html><body>
              <li class="fansubSelector" data-fid="12" data-fad="RidoSubs"></li>
              <li class="fansubSelector" data-fid="19"></li>
              <li class="fansubSelector" data-fid="12" data-fad="RidoSubs"></li>
            </body></html>
        """.trimIndent()

        val fansubs = TrAnimeIzleSite.fansubsFrom(Jsoup.parse(html, base))
        assertEquals(listOf(12, 19), fansubs.map { it.id })
        assertEquals("RidoSubs", fansubs.first().label)
        assertEquals("Fansub 19", fansubs[1].label)
    }

    /**
     * v30 regresyonu: sitenin GERÇEK sourcePlayer cevabı iframe'i
     * `…/embed2/?id=<GERÇEK HOST URL>` biçiminde sarar. Bu adres çıkarılamazsa
     * hiçbir kaynak oynatılamaz (v17–v29 arasındaki "kaynak yok" şikâyeti).
     */
    @Test
    fun episodesFromDataSlugButtons() {
        // ÖLÇÜM (js/site.js, arşiv 31.08.2026):
        //   $(".episodeBtn").click → window.location.href = "/" + $(this).data("slug")
        // Bölüm kartı <a href> değil, data-slug taşıyan öğe olabilir.
        val pageUrl = "$base/naruto-izle"
        val html = """
            <html><body>
              <div class="animeDetail-items"><ol>
                <li class="episodeBtn" data-slug="naruto-1-bolum-izle"><span>1. Bölüm</span></li>
                <li class="episodeBtn" data-slug="naruto-2-bolum-izle"><span>2. Bölüm</span></li>
              </ol></div>
            </body></html>
        """.trimIndent()

        val episodes = TrAnimeIzleSite.parseEpisodes(Jsoup.parse(html, pageUrl), base, pageUrl, "Naruto")

        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals("$base/naruto-1-bolum-izle", episodes.first().url)
    }

    @Test
    fun sourcePlayerIframeCarriesRealHostUrlInsideEmbed2() {
        val body = """{"source":"<iframe src=\"https://luffytra2.top/embed2/?id=https://filemoon.sx/e/abc123\" allowfullscreen></iframe>","contributerNotes":""}"""
        val html = TrAnimeIzleSite.unwrapJsonHtml(body)
        val frames = TrAnimeIzleSite.iframesFrom(html, base)
        assertEquals(1, frames.size)
        assertEquals("https://luffytra2.top/embed2/?id=https://filemoon.sx/e/abc123", frames.first())

        val candidates = TrAnimeIzleSite.embedCandidates(frames.first())
        assertTrue(candidates.contains("https://filemoon.sx/e/abc123"))
        assertEquals("https://filemoon.sx/e/abc123", candidates.first())
    }

    /** fansubSources cevabı: host adı `p` içinde, kimlik `data-id` içinde. */
    @Test
    fun parsesFansubSourcesHostNames() {
        val html = """
            <ul id="sourceList">
              <li class="sourceBtn" data-id="901"><p>Filemoon</p></li>
              <li class="sourceBtn" data-id="902"><p>Voe</p></li>
              <li class="sourceBtn" data-id="903"><p>Sibnet</p></li>
            </ul>
        """.trimIndent()
        val sources = TrAnimeIzleSite.sourcesFrom(html)
        assertEquals(listOf("901", "902", "903"), sources.map { it.id })
        assertEquals(listOf("Filemoon", "Voe", "Sibnet"), sources.map { it.label })
    }

    @Test
    fun parsesSourceButtonsFromPlainHtmlAndJsonWrapper() {
        val plain = """
            <ul id="sourceList">
              <li class="sourceBtn" data-id="11" data-eid="4242"><p class="title">1080p</p></li>
              <li class="sourceBtn" data-id="12" data-eid="4242"><p class="title">720p</p></li>
            </ul>
        """.trimIndent()
        val sources = TrAnimeIzleSite.sourcesFrom(plain)
        assertEquals(listOf("11", "12"), sources.map { it.id })
        assertEquals("1080p", sources.first().label)
        assertEquals(4242, sources.first().episodeId)

        val wrapped = """{"source":"<li class=\"sourceBtn\" data-id=\"33\"><p class=\"title\">Altyazılı 720p</p></li>"}"""
        val fromJson = TrAnimeIzleSite.sourcesFrom(wrapped)
        assertEquals(1, fromJson.size)
        assertEquals("33", fromJson.first().id)
        assertEquals("Altyazılı 720p", fromJson.first().label)
    }

    @Test
    fun extractsDirectMediaAndSkipsAds() {
        val html = """
            <script>
              var player = videojs('v', {sources: [{file: "https:\/\/cdn.example.com\/hls\/master.m3u8"}]});
              var ad = "https://ads.doubleclick.net/video.mp4";
              var mp4 = "https:\/\/cdn.example.com\/video\/720.mp4?token=1";
            </script>
        """.trimIndent()

        val urls = TrAnimeIzleSite.directMediaFrom(html, base)
        assertTrue(urls.contains("https://cdn.example.com/hls/master.m3u8"))
        assertTrue(urls.contains("https://cdn.example.com/video/720.mp4?token=1"))
        assertFalse(urls.any { it.contains("doubleclick") })
        assertTrue(TrAnimeIzleSite.isDirectMedia("https://cdn.example.com/x/master.m3u8"))
        assertFalse(TrAnimeIzleSite.isDirectMedia("https://www.tranimeizle.io/one-piece-izle"))
    }

    @Test
    fun extractsIframesAndSkipsAdFrames() {
        val html = """
            <div id="videoPlayer">
              <iframe src="//ads.doubleclick.net/frame"></iframe>
              <iframe data-src="/embed2/?id=abc123"></iframe>
            </div>
        """.trimIndent()
        val frames = TrAnimeIzleSite.iframesFrom(html, base)
        assertEquals(1, frames.size)
        assertEquals("$base/embed2/?id=abc123", frames.first())
        // Site kendi oynatıcısını luffytra katmanıyla sarıyor: hem kimlik hem tam adres denenir.
        val candidates = TrAnimeIzleSite.embedCandidates("$base/embed2/?id=abc123")
        assertEquals(listOf("abc123", "$base/embed2/?id=abc123"), candidates)
        assertEquals(listOf("https://x/y"), TrAnimeIzleSite.embedCandidates("https://x/y"))
    }

    @Test
    fun extractsSubtitleTracksAndVttLinks() {
        val html = """
            <video><track kind="subtitles" src="/subs/tr.vtt" srclang="tr" label="Türkçe"></video>
            <script>var s = "https:\/\/cdn.example.com\/subs\/en.srt";</script>
        """.trimIndent()

        val subs = TrAnimeIzleSite.subtitleTracks(html, base)
        assertTrue(subs.any { it.first == "Türkçe" && it.second == "$base/subs/tr.vtt" })
        assertTrue(subs.any { it.second == "https://cdn.example.com/subs/en.srt" })
    }

    @Test
    fun unwrapJsonHtmlHandlesWrappedAndPlainBodies() {
        assertEquals("<b>x</b>", TrAnimeIzleSite.unwrapJsonHtml("{\"source\":\"<b>x</b>\"}"))
        assertEquals("<b>x</b>", TrAnimeIzleSite.unwrapJsonHtml("{\"data\":\"<b>x</b>\"}"))
        assertEquals("<b>x</b>", TrAnimeIzleSite.unwrapJsonHtml("<b>x</b>"))
        assertEquals("", TrAnimeIzleSite.unwrapJsonHtml("{\"foo\":1}"))
        assertEquals("", TrAnimeIzleSite.unwrapJsonHtml(null))
    }

    @Test
    fun absoluteUsesBaseAndRejectsJavascript() {
        assertEquals("$base/a/b", TrAnimeIzleSite.absolute("$base/a/", "/a/b"))
        assertEquals("$base/x", TrAnimeIzleSite.absolute(base, "x"))
        assertNull(TrAnimeIzleSite.absolute(base, "javascript:void(0)"))
        assertNull(TrAnimeIzleSite.absolute(base, "  "))
        assertNotNull(TrAnimeIzleSite.absolute(base, "$base/one-piece-izle"))
        assertEquals("tranimeizle.io", TrAnimeIzleSite.hostOf("$base/x"))
    }

    // ------------------------------------------------------------------
    // v39: oynatıcı sarmalları (embed2 / downloadru2) ve yeni hedef listesi
    // ------------------------------------------------------------------

    @Test
    fun embedInnerUrlKeepsInnerQueryString() {
        assertEquals(
            "https://voe.sx/e/abc?x=1&y=2",
            TrAnimeIzleSite.embedInnerUrl("https://luffytra2.top/embed2/?id=https://voe.sx/e/abc?x=1&y=2")
        )
        // Protokolsüz (//) iç adres https'e yükseltilir.
        assertEquals(
            "https://ok.ru/videoembed/1",
            TrAnimeIzleSite.embedInnerUrl("https://x.top/embed2/?id=//ok.ru/videoembed/1")
        )
    }

    @Test
    fun embedInnerUrlDecodesUrlEncodedValueAndRejectsBareIds() {
        assertEquals(
            "https://voe.sx/e/abc?x=1&y=2",
            TrAnimeIzleSite.embedInnerUrl(
                "https://luffytra2.top/embed2/?id=https%3A%2F%2Fvoe.sx%2Fe%2Fabc%3Fx%3D1%26y%3D2"
            )
        )
        // Yalnızca kimlik taşıyan sarmal → null (çağıran iframe'in kendisini dener).
        assertNull(TrAnimeIzleSite.embedInnerUrl("https://luffytra2.top/embed2/?id=12345"))
        assertNull(TrAnimeIzleSite.embedInnerUrl("https://voe.sx/e/abc"))
    }

    @Test
    fun downloadBridgeUrlDecodesBase64Target() {
        assertEquals(
            "https://ok.ru/videoembed/123",
            TrAnimeIzleSite.downloadBridgeUrl(
                "https://www.tranimeizle.io/downloadru2.php?url=aHR0cHM6Ly9vay5ydS92aWRlb2VtYmVkLzEyMw=="
            )
        )
        assertNull(TrAnimeIzleSite.downloadBridgeUrl("https://ok.ru/videoembed/1"))
    }

    @Test
    fun base64DecodeHandlesPaddingAndUrlSafe() {
        assertEquals("https://voe.sx/e/AbC123", TrAnimeIzleSite.base64Decode("aHR0cHM6Ly92b2Uuc3gvZS9BYkMxMjM="))
        assertEquals("https://voe.sx/e/AbC123", TrAnimeIzleSite.base64Decode("aHR0cHM6Ly92b2Uuc3gvZS9BYkMxMjM"))
        assertNull(TrAnimeIzleSite.base64Decode("!!!not-base64!!!"))
        assertNull(TrAnimeIzleSite.base64Decode(""))
    }

    @Test
    fun optracoMediaUrlResolvesRelativeHlsAndSkipsAds() {
        val page = """
            <script>
              var player = new Player({ file: "/hls/a1b2/master.m3u8", type: "hls" });
              var reklam = "https://adsterra.com/x.mp4";
            </script>
        """.trimIndent()
        assertEquals(
            "https://optraco.top/hls/a1b2/master.m3u8",
            TrAnimeIzleSite.optracoMediaUrl(page, "https://optraco.top/explorer/abc")
        )
        assertNull(TrAnimeIzleSite.optracoMediaUrl("<html>oynatıcı yok</html>", base))
    }

    @Test
    fun playerTargetsUnwrapsEmbedAndReturnsDirectMedia() {
        val html = """
            <div id="videoPlayer">
              <iframe src="https://luffytra2.top/embed2/?id=https://voe.sx/e/abc"></iframe>
              <iframe src="https://luffytra2.top/embed2/?id=aHR0cHM6Ly9vay5ydS92aWRlb2VtYmVkLzEyMw=="></iframe>
              <source src="https://cdn.example.com/master.m3u8">
            </div>
        """.trimIndent()

        val targets = TrAnimeIzleSite.playerTargets(html, base)

        assertTrue(targets.contains("https://voe.sx/e/abc"))
        assertTrue(targets.contains("https://cdn.example.com/master.m3u8"))
        // Sarmalın kendisi de hedef olarak korunur (iç adres kimlikse).
        assertTrue(targets.any { it.contains("luffytra2.top/embed2/") })
    }
}
