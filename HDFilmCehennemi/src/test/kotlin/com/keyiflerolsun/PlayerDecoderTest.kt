// ! Oynatıcı çözücü regresyon testleri (HDFilmCehennemi).
// ! Fixture'lar 24-25 Ekim 2026'da TR çıkışlı bağlantıyla CANLI siteden
// ! alındı (src/test/resources/live). Beklenen adresler aynı sayfaların
// ! gerçek tarayıcı JS'iyle (Node vm) çalıştırılmasıyla elde edildi ve
// ! CDN'den 200 + #EXTM3U döndüğü doğrulandı.

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerDecoderTest {

    private fun live(name: String): String {
        val stream = javaClass.getResourceAsStream("/live/$name")
        if (stream != null) return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val file = File("src/test/resources/live/$name")
        if (file.exists()) return file.readText(Charsets.UTF_8)
        error("Fixture bulunamadı: $name")
    }

    private data class Case(val fixture: String, val pageUrl: String, val expected: String, val usesHostname: Boolean)

    private val cases = listOf(
        // Close: hdfilmcehennemi.mobi/video/embed (çözücü anahtarı location.hostname ile karışık)
        Case(
            "hd_close_1.html",
            "https://hdfilmcehennemi.mobi/video/embed/uYHaTijMbIV/?rapidrame_id=mviazhjhc2rx",
            "https://srv9.cdnimages1912.cyou/hls/insidiousoutofthefurther-2026-webdl-tt32393988mp4-uYHaTijMbIV.mp4/txt/master.txt",
            true
        ),
        // Rapidrame: /rplayer/ (bu örnekte şablon host adını kullanmıyor)
        Case(
            "hd_rapid_1.html",
            "https://www.hdfilmcehennemi.nl/rplayer/rsyi6h14btcs/",
            "https://s231.rapidrame.com/hls2/01/00037/rsyi6h14btcs_,l,n,.urlset/master.m3u8?t=mmC1_YtDaa81V7zTS6dlBh5ZnMQVsdjaAq-0cK4cVmY&s=1792878917&e=14400&f=185925&srv=s430&i=0.0&sp=0&n=r97qgif14t9xn0ex&p1=s231&p2=s231",
            false
        ),
        // Aynı Rapid oynatıcısının host adını kullanan şablon varyantı
        Case(
            "rapid_hostname_1.html",
            "https://rapid.filmmakinesi.to/embed-rsyi6h14btcs/",
            "https://s231.rapidrame.com/hls2/01/00037/rsyi6h14btcs_,l,n,.urlset/master.m3u8?t=4rrNkWyH2exNvO7Fq_0xr7u1MrpY2UHxWtU8km69q70&s=1792878421&e=14400&f=185925&srv=s430&i=0.0&sp=0&n=oprbostr0qf9hq43&p1=s231&p2=s231",
            true
        )
    )

    @Test
    fun `oynatici gercek data v elemanini arar`() {
        val html = """
            <div data-v="wrong"></div><div data-v="guard-value"></div>
            <script>
            var configs = { sources: [{file:(function(){
                try { if(typeof document==='undefined') return '';
                    var a=document.querySelectorAll('[data-v]'); var ok=false;
                    for(var i=0;i<a.length;i++){
                        if(a[i].getAttribute('data-v')==="guard-value"){ok=true;break;}
                    }
                    if(!ok) return '';
                } catch(x){return '';}
                return 'https://example.com/stream.m3u8';
            })()}] };
            jwplayer('player').setup(configs);
            </script>
        """.trimIndent()
        for (targeted in listOf(true, false)) {
            val result = PlayerDecoder.decode(html, "https://example.com/player", targeted)
            assertEquals("https://example.com/stream.m3u8", result?.file)
            assertNull(PlayerDecoder.decode(html.replace("data-v=\"guard-value\"", "data-v=\"other\""), "https://example.com/player", targeted))
        }
    }

    @Test
    fun `rapid querySelector guard ilk gercek data v elemanini kullanir`() {
        val html = """
            <div id="pk" data-v="PzfFNe"></div>
            <script>
            var configs = { sources: [{file:(function(){
                try { if(typeof document==='undefined') return '';
                    var e=document.querySelector('[data-v]');
                    if(!e||e.getAttribute('data-v')!=="PzfFNe") return '';
                } catch(x) { return ''; }
                return 'https://example.com/stream.m3u8';
            })()}] };
            jwplayer('player').setup(configs);
            </script>
        """.trimIndent()
        for (targeted in listOf(true, false)) {
            assertEquals("https://example.com/stream.m3u8", PlayerDecoder.decode(html, "https://example.com/player", targeted)?.file)
            assertNull(PlayerDecoder.decode(html.replace("data-v=\"PzfFNe\"", "data-v=\"wrong\""), "https://example.com/player", targeted))
            assertNull(PlayerDecoder.decode(html.replace("data-v=\"PzfFNe\"", ""), "https://example.com/player", targeted))
            assertNull(PlayerDecoder.decode("<div data-v=\"wrong\"></div>" + html, "https://example.com/player", targeted))
        }
    }

    @Test
    fun `rapid id guard gercek dom elemanini kullanir`() {
        val html = """
            <div id="pk" data-v="SEC5738RX"></div>
            <script>
            var configs = { sources: [{file:(function(){
                if(typeof document==='undefined'||!document) return '';
                try { var e=document.getElementById('pk');
                    if(!e||e.getAttribute('data-v')!=="SEC5738RX") return '';
                } catch(x) { return ''; }
                return 'https://example.com/stream.m3u8';
            })()}] };
            jwplayer('player').setup(configs);
            </script>
        """.trimIndent()
        for (targeted in listOf(true, false)) {
            assertEquals("https://example.com/stream.m3u8", PlayerDecoder.decode(html, "https://example.com/player", targeted)?.file)
            assertNull(PlayerDecoder.decode(html.replace("id=\"pk\"", "id=\"other\""), "https://example.com/player", targeted))
            assertNull(PlayerDecoder.decode(html.replace("data-v=\"SEC5738RX\"", "data-v=\"wrong\""), "https://example.com/player", targeted))
            assertNull(PlayerDecoder.decode(html.replace("data-v=\"SEC5738RX\"", ""), "https://example.com/player", targeted))
        }
    }

    @Test
    fun `kasim 2026 close yaniti querySelectorAll id ile cozulur`() {
        val decoded = PlayerDecoder.decode(
            live("hd_close_s01e01.html"),
            "https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/?rapidrame_id=6d4mkrakdi1a"
        )
        assertNotNull(decoded)
        assertTrue(decoded!!.file.startsWith("https://srv12.cdnimages2607.cyou/hls/tt0460681-s01e01-dual-hgsnmgssuo27mp4-RFeVmBTmaCG.mp4/txt/master.txt"))
        assertTrue(decoded.file.contains("cdnimages"))
    }

    @Test
    fun `opsiyonel yeni close yaniti cozulur`() {
        val path = System.getenv("LIVE_CLOSE_CAPTURE")
        org.junit.Assume.assumeTrue(!path.isNullOrBlank() && File(path).isFile)
        val html = File(path!!).readText(Charsets.UTF_8)
        val result = PlayerDecoder.decode(html, "https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/?rapidrame_id=6d4mkrakdi1a")
        assertNotNull("Yeni Close oynatıcı yanıtı çözülemedi", result)
        assertTrue(result!!.file.startsWith("https://"))
    }

    @Test
    fun `opsiyonel yeni rapid yaniti cozulur`() {
        val path = System.getenv("LIVE_RAPID_CAPTURE")
        org.junit.Assume.assumeTrue(!path.isNullOrBlank() && File(path).isFile)
        val html = File(path!!).readText(Charsets.UTF_8)
        val url = "https://www.hdfilmcehennemi.nl/rplayer/6d4mkrakdi1a/"
        val result = PlayerDecoder.decode(html, url)
        assertNotNull("Yeni Rapid oynatıcı yanıtı çözülemedi", result)
        assertTrue("Çözülen akış Rapidrame adresi olmalı", result!!.file.contains("rapidrame"))
    }

    @Test
    fun `gercek yayin adresi cozulur`() {
        for (c in cases) {
            val decoded = PlayerDecoder.decode(live(c.fixture), c.pageUrl)
            assertNotNull("${c.fixture}: çözülemedi", decoded)
            assertEquals(c.fixture, c.expected, decoded!!.file)
            assertEquals(c.fixture, "sources", decoded.method)
        }
    }

    @Test
    fun `genel yol (tum sayfa + jwplayer setup) ayni adresi verir`() {
        for (c in cases) {
            val decoded = PlayerDecoder.decode(live(c.fixture), c.pageUrl, targeted = false)
            assertNotNull("${c.fixture}: genel yol çözemedi", decoded)
            assertEquals(c.fixture, c.expected, decoded!!.file)
        }
    }

    @Test
    fun `tuzak adres asla donmez`() {
        val html = live("hd_close_1.html")
        // Sayfada tuzak adres gerçekten var (JSON-LD contentUrl) ...
        assertTrue(html.contains("playmix.uno"))
        // ... ama çözücü onu döndürmez.
        val file = PlayerDecoder.decode(html, cases[0].pageUrl)!!.file
        assertFalse(file.contains("playmix.uno"))
        assertFalse(file.contains("filmakinesimp4"))
    }

    @Test
    fun `anahtar host adina bagli - yanlis hostta sahte adres uretilmez`() {
        for (c in cases.filter { it.usesHostname }) {
            val wrongHost = c.pageUrl.replace(java.net.URI(c.pageUrl).host, "example.org")
            assertNull(c.fixture, PlayerDecoder.decode(live(c.fixture), wrongHost))
        }
    }

    @Test
    fun `altyazilar cikarilir`() {
        val close = PlayerDecoder.decode(live("hd_close_1.html"), cases[0].pageUrl)!!
        assertTrue(close.tracks.any { it.label == "Turkish" })
        assertTrue(close.tracks.all { it.file.startsWith("https://hdfilmcehennemi.mobi/vtt/") })
        val rapid = PlayerDecoder.decode(live("hd_rapid_1.html"), cases[1].pageUrl)!!
        assertEquals(
            listOf(
                "https://www.hdfilmcehennemi.nl/srt/00037/rsyi6h14btcs_English.vtt",
                "https://www.hdfilmcehennemi.nl/srt/00037/rsyi6h14btcs_Turkish.vtt"
            ),
            rapid.tracks.map { it.file }
        )
    }
}
