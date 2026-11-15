// ! Oynatıcı çözücü regresyon testleri.
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
        Case(
            "fm_close_1.html",
            "https://closeload.filmmakinesi.to/video/embed/EWeEdu0Ig0q/?imdb_id=tt22084616",
            "https://srv9.cdnimages2312.cyou/hls/spidermanbrandnewday-2026-webdl-trdual-tt22084616mp4-EWeEdu0Ig0q.mp4/txt/master.txt",
            true
        ),
        Case(
            "fm_close_2.html",
            "https://closeload.filmmakinesi.to/video/embed/t8ZxnA5918H/?imdb_id=tt36983905",
            "https://srv9.cdnimages2042.cyou/hls/theuprising-2026-webdl-tt36983905mp4-t8ZxnA5918H.mp4/txt/master.txt",
            true
        ),
        Case(
            "fm_rapid_1.html",
            "https://rapid.filmmakinesi.to/embed-rsyi6h14btcs/",
            "https://s231.rapidrame.com/hls2/01/00037/rsyi6h14btcs_,l,n,.urlset/master.m3u8?t=4rrNkWyH2exNvO7Fq_0xr7u1MrpY2UHxWtU8km69q70&s=1792878421&e=14400&f=185925&srv=s430&i=0.0&sp=0&n=oprbostr0qf9hq43&p1=s231&p2=s231",
            true
        ),
        Case(
            "fm_rapid_2.html",
            "https://rapid.filmmakinesi.to/embed-ma9kx7ys9744/",
            "https://s231.rapidrame.com/hls2/01/00037/ma9kx7ys9744_,l,n,.urlset/master.m3u8?t=8yHxmrpra9WLT1QTFj6fPMPhHdxOA1ZAMlR1xCj1HRQ&s=1792878459&e=14400&f=185624&srv=s429&i=0.0&sp=0&n=4tjzxsu6s6ohn3do&p1=s231&p2=s231",
            true
        )
    )

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
    fun `close querySelectorAll id ve data yildiz anahtari`() {
        val html = """
            <i id="n0y" data-u2oi="secretK" hidden></i>
            <script>
            var configs = { sources: [{file:(function(){
                try {
                    var k = document.querySelectorAll('#n0y')[0].getAttribute('data-u2oi');
                    k += document.createElement('Div').tagName;
                    if(k!=='secretKDIV') return '';
                } catch(x) { return ''; }
                return 'https://cdn.example/hls/master.txt';
            })()}] };
            jwplayer('p').setup(configs);
            </script>
        """.trimIndent()
        for (targeted in listOf(true, false)) {
            assertEquals("https://cdn.example/hls/master.txt", PlayerDecoder.decode(html, "https://closeload.filmmakinesi.to/video/embed/abc/", targeted)?.file)
            assertNull(PlayerDecoder.decode(html.replace("id=\"n0y\"", "id=\"other\""), "https://closeload.filmmakinesi.to/video/embed/abc/", targeted))
        }
    }

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
    fun `kasim 2026 Rapid data-v ve i8k yayin adresini cozer`() {
        val pageUrl = "https://rapid.filmmakinesi.to/embed-6d4mkrakdi1a/"
        val decoded = PlayerDecoder.decode(live("fm_rapid_s01e01_2026-11-15.html"), pageUrl)
        assertNotNull(decoded)
        assertEquals(
            "https://s230.rapidrame.com/hls2/01/00029/6d4mkrakdi1a_,l,n,.urlset/master.m3u8?t=JPXwNhwRzhX-WZ_lojzeUUTqz_ru1J5Y9ScxdMo29XA&s=1794698899&e=14400&f=145463&srv=s427&i=0.0&sp=0&n=i54db4hg6timh04j&p1=s230&p2=s230",
            decoded!!.file
        )
        assertFalse(decoded.file.contains("filmakinesimp4"))
        assertTrue(decoded.tracks.any { it.file.contains("6d4mkrakdi1a") && it.file.endsWith(".vtt") })
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
            assertEquals(c.fixture, "setup", decoded.method)
        }
    }

    @Test
    fun `tuzak adres asla donmez`() {
        // Her Close sayfasında aynı olan sahte adres (JSON-LD contentUrl da bunu gösterir).
        for (c in cases) {
            val html = live(c.fixture)
            val file = PlayerDecoder.decode(html, c.pageUrl)!!.file
            assertFalse(c.fixture, file.contains("filmakinesimp4"))
            assertFalse(c.fixture, file.contains("playmix.uno"))
        }
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
        val close = PlayerDecoder.decode(live("fm_close_1.html"), cases[0].pageUrl)!!
        assertTrue(close.tracks.any { it.label == "Turkish" && it.file.endsWith(".vtt") })
        assertTrue(close.tracks.all { it.file.startsWith("https://closeload.filmmakinesi.to/vtt/") })

        val rapid = PlayerDecoder.decode(live("fm_rapid_1.html"), cases[2].pageUrl)!!
        assertEquals(
            listOf(
                "https://rapid.filmmakinesi.to/srt/00037/rsyi6h14btcs_English.vtt",
                "https://rapid.filmmakinesi.to/srt/00037/rsyi6h14btcs_Turkish.vtt"
            ),
            rapid.tracks.map { it.file }
        )
        // "thumbnails" türündeki iz altyazı sayılmaz
        assertTrue(rapid.tracks.none { it.file.contains("get_slides") })
    }

    @Test
    fun `yayin adresi dogrulamasi`() {
        assertTrue(PlayerDecoder.isPlausibleStreamUrl("https://srv9.cdnimages1.cyou/hls/a.mp4/txt/master.txt"))
        assertTrue(PlayerDecoder.isPlausibleStreamUrl("https://s1.rapidrame.com/hls2/x/master.m3u8?t=1&s=2"))
        assertFalse(PlayerDecoder.isPlausibleStreamUrl("https://closeload.filmmakinesi.to/vtt/a.vtt"))
        assertFalse(PlayerDecoder.isPlausibleStreamUrl("https://x.com/img/poster.jpg"))
        assertFalse(PlayerDecoder.isPlausibleStreamUrl("\u0012\u00ff garbage"))
        assertFalse(PlayerDecoder.isPlausibleStreamUrl("javascript:alert(1)"))
        assertFalse(PlayerDecoder.isPlausibleStreamUrl(null))
    }
}
