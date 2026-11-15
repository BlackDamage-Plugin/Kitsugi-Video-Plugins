// ! MiniJs yorumlayıcısının JS anlamsal uyumluluk testleri (çözücülerin
// ! kullandığı tüm yapılar: bit işlemleri, atob, splice, replace(fn), ...).

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsTest {

    private fun run(code: String, host: String = "closeload.filmmakinesi.to"): Any? {
        val js = MiniJs("https://$host/video/embed/abc/")
        return js.runCode(code, tolerant = false)
    }

    private fun str(code: String) = MiniJs.toStr(run(code))

    @Test
    fun `sayilar ve bit islemleri JS gibi`() {
        assertEquals("6", str("(5 ^ 3) & 255"))
        assertEquals("-1", str("-7 % 3"))
        assertEquals("-2147483648", str("1 << 31"))
        assertEquals("4294967295", str("-1 >>> 0"))
        assertEquals("0.5", str("'' + 0.5"))
        assertEquals("1e+21", str("'' + 1e21"))
        assertEquals("ff", str("(255).toString(16)"))
        assertEquals("NaN", str("'' + parseInt('zz')"))
        assertEquals("255", str("parseInt('0xff')"))
        assertEquals("3", str("Math.floor(3.7)"))
        assertEquals("true", str("0.1 + 0.2 !== 0.3"))
        assertEquals("12", str("'1' + 2"))
        assertEquals("-1", str("'3' - 4"))
    }

    @Test
    fun `metin islemleri`() {
        assertEquals("cba", str("'abc'.split('').reverse().join('')"))
        assertEquals("a+b+c", str("'a-b-c'.replace(/-/g, function (m) { return '+'; })"))
        assertEquals("a+b-c", str("'a-b-c'.replace('-', '+')"))
        assertEquals("hello", str("atob('aGVsbG8=')"))
        assertEquals("aGk=", str("btoa('hi')"))
        assertEquals("Hi", str("String.fromCharCode(72, 105)"))
        assertEquals("104", str("'h'.charCodeAt(0)"))
        assertEquals("b", str("'abc'.charAt(1)"))
        assertEquals("bc", str("'abc'.substr(1)"))
        assertEquals("x,y,,z", str("'x@y@@z'.split('@').join(',')"))
        assertEquals("NOPQ", str("'ABCD'.replace(/[a-zA-Z]/g, function (c) { var x = c.charCodeAt(0), b = x <= 90 ? 65 : 97; return String.fromCharCode((x - b + 13) % 26 + b); })"))
        assertEquals("[\$&]", str("'x'.replace(/x/, '[\$\$&]')"))
    }

    @Test
    fun `dizi islemleri`() {
        assertEquals("c|a,b,d,e", str("var a = ['a','b','c','d','e']; var r = a.splice(2, 1)[0]; r + '|' + a.join(',')"))
        assertEquals("3", str("var k = []; k[2] = 1; k.length"))
        assertEquals("b,a,c", str("var y = ['a','b','c']; var t = y[1]; y[1] = y[0]; y[0] = t; y.join(',')"))
        assertEquals("2,4,6", str("[1,2,3].map(function (x) { return x * 2; }).join(',')"))
        assertEquals("6", str("[1,2,3].reduce(function (a, b) { return a + b; }, 0)"))
    }

    @Test
    fun `fonksiyonlar kapanislar ve kaldirma`() {
        assertEquals("42", str("function f() { return g(); } function g() { return 42; } f()"))
        assertEquals("3", str("var mk = function () { var n = 0; return function () { return ++n; }; }; var c = mk(); c(); c(); c()"))
        assertEquals("9", str("var sq = (x) => x * x; sq(3)"))
        assertEquals("undefined", str("typeof hicTanimlanmadi"))
        assertEquals("7", str("var o = { a: 3, b: function () { return this.a + 4; } }; o.b()"))
        assertEquals("v=5", str("var v = 5; `v=\${v}`"))
    }

    @Test
    fun `kontrol akisi`() {
        assertEquals("11", str("var s = 0; for (var i = 0; i < 5; i++) { if (i == 3) continue; s += i; } s + 4"))
        assertEquals("ab", str("var r = ''; for (var k in { a: 1, b: 2 }) r += k; r"))
        assertEquals("iki", str("var x = 2, r; switch (x) { case 1: r = 'bir'; break; case 2: r = 'iki'; break; default: r = '?'; } r"))
        assertEquals("yakalandi", str("var r; try { throw new Error('x'); } catch (e) { r = 'yakalandi'; } r"))
        assertEquals("3", str("var n = 0; do { n++; } while (n < 3); n"))
    }

    @Test
    fun `tarayici ortami`() {
        assertEquals("closeload.filmmakinesi.to", str("document.location['hos' + 'tname']"))
        assertEquals("closeload.filmmakinesi.to", str("location.hostname"))
        assertEquals("closeload.filmmakinesi.to", str("window.location.hostname"))
        // Bilinmeyen tarayıcı API'leri hata vermeden geçer
        assertEquals("ok", str("\$('body').on('click', function () {}); localStorage['x'] = 1; 'ok'"))
    }

    @Test
    fun `jwplayer setup yakalanir`() {
        val js = MiniJs("https://rapid.filmmakinesi.to/embed-x/")
        js.runCode("var u = 'https://cdn.example/hls/master.m3u8'; jwplayer('p').setup({ sources: [{ file: u }] }).on('ready', function () {});")
        assertEquals(1, js.capturedSetups.size)
        val cfg = js.capturedSetups[0] as JsObject
        val src = (cfg.props["sources"] as JsArray).list[0] as JsObject
        assertEquals("https://cdn.example/hls/master.m3u8", src.props["file"])
    }

    @Test
    fun `packer acilir`() {
        val packed = "eval(function(p,a,c,k,e,d){e=function(c){return c.toString(36)};if(!''.replace(/^/,String)){while(c--){d[c.toString(a)]=k[c]||c.toString(a)}k=[function(e){return d[e]}];e=function(){return'\\\\w+'};c=1};while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c])}}return p}('3 0=\"2\";1(0)',4,4,'msg|alert|merhaba|var'.split('|'),0,{}))"
        val unpacked = Packer.unpackAll(packed)
        assertEquals(listOf("var msg=\"merhaba\";alert(msg)"), unpacked)
        // eval yolu da aynı kodu çalıştırır
        val js = MiniJs()
        js.runCode(packed, tolerant = false)
        assertEquals("merhaba", js.global.vars["msg"])
    }

    @Test
    fun `close anahtari querySelectorAll id ve createElement tagName`() {
        val js = MiniJs(
            "https://hdfilmcehennemi.mobi/video/embed/abc/",
            elementsById = mapOf("n0y" to mapOf("id" to "n0y", "data-u2oi" to "wuE8xN1KVd"))
        )
        val code = """
            var k = '';
            k += window.location['hos'+'tname'];
            k += document.querySelectorAll('#n0y')[0].getAttribute('data-u2oi');
            k += document.createElement('Div').tagName;
            k
        """.trimIndent()
        assertEquals("hdfilmcehennemi.mobiwuE8xN1KVdDIV", MiniJs.toStr(js.runCode(code, tolerant = false)))
    }

    @Test
    fun `adim siniri sonsuz donguyu durdurur`() {
        val js = MiniJs("https://x.y/", stepLimit = 10_000L)
        var stopped = false
        try {
            js.runCode("while (true) {}", tolerant = false)
        } catch (e: MiniJs.StepLimitException) {
            stopped = true
        }
        assertTrue(stopped)
    }
}
