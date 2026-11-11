package com.lagradost.cloudstream3.providers

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class CloseLoad : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val uri = java.net.URI(url)
        val domain = "${uri.scheme}://${uri.host}"
        val headers2 = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Referer" to "$domain/",
            "Origin" to domain
        )

        try {
            val response = app.get(url, referer = domain, headers = headers2)
            val html = unpackDeanEdwards(response.text) 

            // 1. JS Deşifre Algoritmasını Dene
            var realUrl = decryptNative(html)

            // 2. Fallback Mekanizması: Eğer JS şifre çözücü başarısız olursa JSON-LD bloğundaki şifresiz contentUrl'i ara
            if (realUrl.isNullOrBlank()) {
                Log.w("Kekik_${this.name}", "Native deşifre başarısız, Fallback JSON-LD aranıyor...")
                val ldJsonMatch = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex().find(html)
                realUrl = ldJsonMatch?.groupValues?.get(1)?.replace("\\/", "/")
            }

            // 3. Fallback Mekanizması 2: "file": "..." veya file: "..." ara
            if (realUrl.isNullOrBlank()) {
                val fileMatch = """(?:file|url)\s*:\s*"([^"]+)"""".toRegex().find(html)
                    ?: """(?:file|url)\s*:\s*'([^']+)'""".toRegex().find(html)
                realUrl = fileMatch?.groupValues?.get(1)?.replace("\\/", "/")
            }

            // 4. Fallback Mekanizması 3: Herhangi bir master.m3u8, master.txt veya .mp4 linki ara
            if (realUrl.isNullOrBlank()) {
                val streamUrlMatch = """(https?://[^\s"']+\.(?:m3u8|mp4|txt)[^\s"']*)""".toRegex().find(html)
                realUrl = streamUrlMatch?.groupValues?.get(1)?.replace("\\/", "/")
            }

            if (!realUrl.isNullOrBlank() && realUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = realUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "Referer" to "$domain/",
                            "User-Agent" to headers2["User-Agent"]!!
                        )
                    }
                )
            } else {
                Log.e("Kekik_${this.name}", "Real URL bulunamadı veya deşifre edilemedi.")
            }

            processSubtitles(html, subtitleCallback)

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Hata: ${e.message}")
        }
    }

    private fun decryptNative(html: String): String? {
        try {
            // JS bloğunu yakala
            val scriptBlockMatch = """<script[^>]*>(.*?dc_[a-zA-Z0-9_]+\(.*?</script>)""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            val scriptContent = scriptBlockMatch?.groupValues?.get(1) ?: return null

            // 1. Şifreli diziyi çıkar
            val arrayMatch = """\(\[((?:"[^"]+",?\s*)+)\]\)""".toRegex().find(scriptContent)
            val parts = arrayMatch?.groupValues?.get(1)?.split(",")?.map { 
                it.trim().trim('"').replace("\\/", "/") 
            } ?: return null

            // 2. Dinamik Modulo Çarpanlarını Çıkar
            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(scriptContent)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5

            // 3. Fonksiyon Gövdesini Regex OLMADAN İzole Et
            val funcStartIdx = scriptContent.indexOf("function dc_")
            val funcEndIdx = scriptContent.indexOf("function d1x()", funcStartIdx).takeIf { it != -1 } ?: scriptContent.length
            val functionBody = if (funcStartIdx != -1) scriptContent.substring(funcStartIdx, funcEndIdx) else scriptContent

            // 4. KRİTİK DOKUNUŞ: Dinamik ROT (Caesar) Kaydırma (Shift) Değerini Çıkar
            // JS'teki `c.charCodeAt(0) + 13` veya yeni değer neyse onu dinamik okuruz (bulamazsa default 13)
            val rotShiftMatch = """charCodeAt\(0\)\s*\+\s*(\d+)""".toRegex().find(functionBody)
            val rotShift = rotShiftMatch?.groupValues?.get(1)?.toIntOrNull() ?: 13

            // --- OPERASYON SIRASINI DİNAMİK OKU --- //
            val operations = mutableListOf<Pair<Int, String>>()
            
            var index = 0
            while (index < functionBody.length) {
                val nextReverse = functionBody.indexOf("reverse", index)
                if (nextReverse == -1) break
                operations.add(Pair(nextReverse, "reverse"))
                index = nextReverse + 1
            }
            
            index = 0
            while (index < functionBody.length) {
                val nextAtob = functionBody.indexOf("atob", index)
                if (nextAtob == -1) break
                operations.add(Pair(nextAtob, "atob"))
                index = nextAtob + 1
            }
            
            index = 0
            while (index < functionBody.length) {
                val nextReplace = functionBody.indexOf("replace", index)
                if (nextReplace == -1) break
                operations.add(Pair(nextReplace, "rot"))
                index = nextReplace + 1
            }
            
            val sortedOperations = operations.sortedBy { it.first }.map { it.second }

            var result = parts.joinToString("")

            // İşlemleri sitenin belirlediği sıraya göre ateşle
            for (op in sortedOperations) {
                when (op) {
                    "reverse" -> {
                        result = result.reversed()
                    }
                    "atob" -> {
                        // Base64 padding (==) eksikliklerine karşı güvenlik
                        var paddedResult = result
                        while (paddedResult.length % 4 != 0) {
                            paddedResult += "="
                        }
                        result = String(Base64.decode(paddedResult, Base64.NO_WRAP), Charsets.ISO_8859_1)
                    }
                    "rot" -> {
                        // Statik 13 yerine dinamik 'rotShift' kullanıyoruz
                        val rot = StringBuilder()
                        for (c in result) {
                            if (c in 'a'..'z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else if (c in 'A'..'Z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'Z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else {
                                rot.append(c)
                            }
                        }
                        result = rot.toString()
                    }
                }
            }

            // --- SON ADIM: Modulo Unmix (Daima en sonda çalışır) --- //
            val unmix = StringBuilder()
            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decryptedCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                unmix.append(decryptedCode.toInt().toChar())
            }

            return unmix.toString()

        } catch (e: Exception) {
            Log.e("Kekik_Extractor", "Native Çözümleme Hatası: ${e.message}")
            return null
        }
    }


    private fun processSubtitles(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // JWPlayer setup içindeki tracks: [...] JSON bloğu
            val tracksMatch = """tracks\s*:\s*(\[.*?\])""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            tracksMatch?.groupValues?.get(1)?.let { tracksJson ->
                
                val trackPattern = """\{[^}]*\}""".toRegex()
                val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                val labelRegex = """"label"\s*:\s*"([^"]+)"""".toRegex()

                trackPattern.findAll(tracksJson).forEach { match ->
                    val block = match.value
                    val file = fileRegex.find(block)?.groupValues?.get(1)?.replace("\\/", "/")
                    val label = labelRegex.find(block)?.groupValues?.get(1) ?: "Altyazı"

                    // file null değilse ve http ile başlıyorsa fırlat
                    if (!file.isNullOrBlank() && file.startsWith("http")) {
                        subtitleCallback.invoke(SubtitleFile(label, file))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Altyazı Çözümleme Hatası: ${e.message}")
        }
    }

    private fun unpackDeanEdwards(html: String): String {
        val pattern = """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*.*?\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        var unpackedHtml = html
        pattern.findAll(html).forEach { match ->
            try {
                val pEscaped = match.groupValues[1]
                val a = match.groupValues[2].toInt()
                val c = match.groupValues[3].toInt()
                val k = match.groupValues[4].split('|')
                
                val p = unescapeJS(pEscaped)
                
                val d = Array(c) { "" }
                for (i in 0 until c) {
                    val key = baseN(i, a)
                    d[i] = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                }
                
                val dict = (0 until c).map { baseN(it, a) }.zip(d).toMap()
                
                val tokenPattern = """\b[0-9a-zA-Z]+\b""".toRegex()
                val unpackedScript = tokenPattern.replace(p) { m ->
                    dict[m.value] ?: m.value
                }
                
                unpackedHtml = unpackedHtml.replace(match.value, unpackedScript)
            } catch (e: Exception) {
                Log.e("Kekik_${this.name}", "Dean Edwards unpacking error: ${e.message}")
            }
        }
        return unpackedHtml
    }

    private fun baseN(num: Int, base: Int): String {
        if (num == 0) return "0"
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var temp = num
        val sb = StringBuilder()
        while (temp > 0) {
            sb.append(chars[temp % base])
            temp /= base
        }
        return sb.reverse().toString()
    }

    private fun unescapeJS(s: String): String {
        var str = s
        val unicodePattern = """\\u([0-9a-fA-F]{4})""".toRegex()
        str = unicodePattern.replace(str) { 
            it.groupValues[1].toInt(16).toChar().toString()
        }
        str = str.replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
        return str
    }
}
