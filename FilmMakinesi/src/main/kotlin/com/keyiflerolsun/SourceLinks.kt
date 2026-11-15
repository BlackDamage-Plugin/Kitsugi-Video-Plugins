package com.keyiflerolsun

import org.jsoup.nodes.Document

/**
 * FilmMakinesi film/bölüm sayfasındaki oynatıcı kaynakları.
 *
 * Canlı yapı (filmmakinesi.to, Ekim 2026):
 *  - Film:  <div class="video-parts">
 *             <a data-video_url="https://closeload.filmmakinesi.to/video/embed/ID/?imdb_id=tt..">DUAL Close</a>
 *             <a data-video_url="https://rapid.filmmakinesi.to/embed-ID/">DUAL Rapid</a>
 *           </div>
 *  - Bölüm: `.video-parts` yok; tek kaynak `.after-player iframe[data-src]` içinde.
 */
internal object SourceLinks {

    enum class Kind { CLOSE, RAPID, OTHER }

    data class Source(val label: String, val url: String, val kind: Kind)

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    /** Never add another provider's player, even if an iframe points to one. */
    fun isOtherProvider(url: String): Boolean =
        hostOf(url).split('.').any { Regex("^hdfilmcehennemi[0-9]*$").matches(it) }

    fun kindOf(url: String): Kind {
        val host = hostOf(url)
        val lower = url.lowercase()
        return when {
            host.contains("closeload") || lower.contains("/video/embed/") -> Kind.CLOSE
            host.startsWith("rapid.") || host.contains("rapidrame") || host.contains("rapidvid") ||
                lower.contains("/embed-") || lower.contains("/rplayer/") -> Kind.RAPID
            else -> Kind.OTHER
        }
    }

    /** Ham değeri mutlak, kullanılabilir bir oynatıcı adresine çevirir; değilse null. */
    fun normalize(raw: String?, pageUrl: String): String? {
        var value = raw?.trim()?.replace("\\/", "/")?.replace("&amp;", "&") ?: return null
        if (value.isEmpty() || value == "#" || value.startsWith("javascript:", true) ||
            value.startsWith("about:", true) || value.startsWith("data:", true)
        ) return null
        if (value.startsWith("//")) value = "https:$value"
        val absolute = if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            runCatching { java.net.URI(pageUrl).resolve(value).toString() }.getOrNull() ?: return null
        }
        val host = hostOf(absolute)
        if (host.isEmpty() || isOtherProvider(absolute)) return null
        if (host.contains("youtube.") || host.contains("youtu.be") || host.contains("google.")) return null
        return absolute
    }

    /** "DUAL Close" → "Close · DUAL"; bahis/reklam kelimeleri ayıklanır. */
    fun labelFor(text: String, url: String): String {
        val kindName = when (kindOf(url)) {
            Kind.CLOSE -> "Close"
            Kind.RAPID -> "Rapid"
            Kind.OTHER -> hostOf(url).removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
        }
        val extra = text
            .replace(Regex("\\([^)]*\\)"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .filterNot { it.equals(kindName, ignoreCase = true) || it.equals("rapidrame", true) || it.equals("closeload", true) }
            .filterNot { it.contains("bet", ignoreCase = true) || it.contains("reklam", ignoreCase = true) }
            .joinToString(" ")
            .trim()
        return if (extra.isEmpty()) kindName else "$kindName · $extra"
    }

    fun collect(document: Document, pageUrl: String): List<Source> {
        val out = LinkedHashMap<String, Source>()
        for (el in document.select(".video-parts [data-video_url], .video-parts [data-video-url], [data-video_url]")) {
            val raw = el.attr("data-video_url").ifBlank { el.attr("data-video-url") }
            val url = normalize(raw, pageUrl) ?: continue
            // Fragman düğmesi (trailer-button) kaynak değildir.
            if (el.hasClass("trailer-button") || el.closest(".trailer-button, .trailer") != null) continue
            out.putIfAbsent(url, Source(labelFor(el.text(), url), url, kindOf(url)))
        }
        for (frame in document.select(".after-player iframe, #player-section iframe, .player--area iframe")) {
            val raw = frame.attr("data-src").ifBlank { frame.attr("src") }
            val url = normalize(raw, pageUrl) ?: continue
            out.putIfAbsent(url, Source(labelFor("", url), url, kindOf(url)))
        }
        // Close önce, sonra Rapid, sonra diğerleri.
        return out.values.sortedBy { it.kind.ordinal }
    }
}
