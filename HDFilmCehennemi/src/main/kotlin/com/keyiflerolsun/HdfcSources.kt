package com.keyiflerolsun

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * HDFilmCehennemi film/bölüm sayfasındaki oynatıcı kaynakları.
 *
 * Canlı yapı (www.hdfilmcehennemi.nl, Ekim 2026):
 *
 *   <div class="language-tab"><button class="language-link" data-lang="en">Türkçe Altyazılı</button></div>
 *   <div class="alternative-links" data-lang="en">
 *     <button class="alternative-link" data-video="325463">Close</button>
 *     <button class="alternative-link" data-video="325422">Rapidrame</button>
 *   </div>
 *   <iframe class="close" data-src="https://hdfilmcehennemi.mobi/video/embed/ID/?rapidrame_id=RID">
 *
 *   GET {mainUrl}/video/{data-video}/  (X-Requested-With: fetch)
 *     → {"success":true,"data":{"html":"<iframe class=\"close\" data-src=\"https:\/\/hdfilmcehennemi.mobi\/video\/embed\/ID\/?rapidrame_id=RID\" ...>"}}
 *     → {"success":true,"data":{"html":"<iframe class=\"rapidrame\" data-src=\"https:\/\/www.hdfilmcehennemi.nl\/rplayer\/RID\/\" ...>"}}
 */
internal object HdfcSources {

    data class Button(val videoId: String, val name: String, val language: String?) {
        val label: String get() = if (language.isNullOrBlank()) name else "$name · $language"
    }

    data class Embed(val label: String, val url: String)

    private fun clean(text: String): String = text
        .replace(Regex("\\([^)]*\\)"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .filterNot { it.contains("bet", ignoreCase = true) || it.contains("reklam", ignoreCase = true) }
        .joinToString(" ")
        .trim()

    private fun defaultLanguage(code: String): String? = when (code.lowercase()) {
        "tr" -> "Türkçe Dublaj"
        "en" -> "Türkçe Altyazılı"
        else -> null
    }

    /** Dil sekmeleri ve kaynak düğmeleri (data-video kimlikleri). */
    fun buttons(document: Document): List<Button> {
        val languageNames = HashMap<String, String>()
        for (b in document.select(".language-link[data-lang]")) {
            val name = clean(b.text())
            if (name.isNotEmpty()) languageNames[b.attr("data-lang")] = name
        }
        val out = ArrayList<Button>()
        for (group in document.select(".alternative-links")) {
            val code = group.attr("data-lang")
            val language = languageNames[code] ?: defaultLanguage(code)
            for (b in group.select("[data-video]")) {
                val id = b.attr("data-video").trim()
                if (id.isEmpty()) continue
                out.add(Button(id, clean(b.text()).ifEmpty { "Kaynak" }, language))
            }
        }
        if (out.isEmpty()) {
            for (b in document.select("button.alternative-link[data-video], .alternative-link[data-video]")) {
                val id = b.attr("data-video").trim()
                if (id.isNotEmpty()) out.add(Button(id, clean(b.text()).ifEmpty { "Kaynak" }, null))
            }
        }
        return out.distinctBy { it.videoId }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    fun normalize(raw: String?, baseUrl: String): String? {
        var value = raw?.trim()?.replace("\\/", "/")?.replace("&amp;", "&") ?: return null
        if (value.isEmpty() || value == "#" || value.startsWith("javascript:", true) || value.startsWith("about:", true)) return null
        if (value.startsWith("//")) value = "https:$value"
        val absolute = if (value.startsWith("http://") || value.startsWith("https://")) value
        else runCatching { java.net.URI(baseUrl).resolve(value).toString() }.getOrNull() ?: return null
        return if (hostOf(absolute).isEmpty()) null else absolute
    }

    /** Sitenin kendi oynatıcıları: /video/embed/ (Close), /rplayer/ (Rapidrame) ve yeni kısa yollar. */
    fun isPlayerUrl(url: String): Boolean {
        val host = hostOf(url)
        val lower = url.lowercase()
        if (host.contains("youtube") || host.contains("youtu.be")) return false
        if (lower.contains("/video/embed/") || lower.contains("/rplayer/") || lower.contains("/embed-")) return true
        if (host.contains("closeload") || host.contains("rapidrame") || host.contains("rapidvid")) return true
        // Site oynatıcı yolunu değiştirebiliyor (/embed/, /player/, /e/...). Yalnızca
        // oynatıcı ailesine benzeyen host'larda kabul edilir; alakasız iframe'ler dışarıda kalır.
        val playerHost = host.contains("hdfilmcehennemi") || host.contains("close") || host.contains("rapid")
        return playerHost && Regex("/(embed|player|e|video|v)/[A-Za-z0-9_-]{4,}/?$").containsMatchIn(lower)
    }

    fun nameForUrl(url: String): String {
        val host = hostOf(url)
        val lower = url.lowercase()
        return when {
            lower.contains("/rplayer/") || host.contains("rapid") -> "Rapidrame"
            host.contains("closeload") || host.contains("close") || lower.contains("/video/embed/") -> "Close"
            host.isEmpty() -> "Kaynak"
            else -> host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun queryParam(url: String, key: String): String? =
        Regex("[?&]" + Regex.escape(key) + "=([^&#]+)").find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** Sayfaya doğrudan gömülü oynatıcı(lar) + rapidrame_id'den türetilen Rapidrame. */
    fun pageEmbeds(document: Document, pageUrl: String, mainUrl: String): List<Embed> {
        val out = ArrayList<Embed>()
        for (frame in document.select("iframe[data-src], iframe[src]")) {
            val url = normalize(frame.attr("data-src").ifBlank { frame.attr("src") }, pageUrl) ?: continue
            if (!isPlayerUrl(url)) continue
            if (out.none { it.url == url }) out.add(Embed(nameForUrl(url), url))
        }
        for (e in out.toList()) {
            val rid = queryParam(e.url, "rapidrame_id") ?: continue
            val rplayer = "${mainUrl.trimEnd('/')}/rplayer/$rid/"
            if (out.none { it.url == rplayer }) out.add(Embed("Rapidrame", rplayer))
        }
        return out
    }

    /** `/video/{id}/` JSON yanıtındaki iframe adresini çıkarır. */
    fun embedFromApi(body: String, baseUrl: String): String? {
        val html = jsonStringField(body, "html") ?: body
        val frame = Jsoup.parse(html).selectFirst("iframe")
        val raw = frame?.attr("data-src")?.ifBlank { frame.attr("src") }
            ?: Regex("""data-src=\\?["']([^"'\\]+)""").find(body)?.groupValues?.get(1)
        val url = normalize(raw, baseUrl) ?: return null
        return if (isPlayerUrl(url)) url else null
    }

    /** Küçük JSON metin alanı okuyucu (bağımlılıksız). */
    fun jsonStringField(json: String, field: String): String? {
        val m = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"").find(json) ?: return null
        val sb = StringBuilder()
        var i = m.range.last + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < json.length) {
                val n = json[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        val hex = json.substring(i + 2, minOf(i + 6, json.length))
                        val v = hex.toIntOrNull(16)
                        if (v != null && hex.length == 4) { sb.append(v.toChar()); i += 6; continue }
                        sb.append('u')
                    }
                    else -> sb.append(n)
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return null
    }
}
