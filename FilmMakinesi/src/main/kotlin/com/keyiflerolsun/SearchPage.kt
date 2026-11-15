package com.keyiflerolsun

import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** Search form and suggestion markup, without Android or network dependencies. */
internal object SearchPage {
    data class Card(val title: String, val url: String, val poster: String?, val series: Boolean)

    fun queryUrl(base: String, query: String, autocomplete: Boolean = false): String {
        val path = if (autocomplete) "/ajax/autocomplete/" else "/arama/"
        return base.trimEnd('/') + path + "?s=" + URLEncoder.encode(query.trim(), "UTF-8")
    }

    private fun absolute(raw: String, base: String): String? {
        if (raw.isBlank()) return null
        return try {
            val uri = URI(base).resolve(raw.trim())
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) null else uri.toString()
        } catch (_: Exception) { null }
    }

    private fun card(anchor: Element, base: String): Card? {
        val href = absolute(anchor.attr("href"), base) ?: return null
        val uri = try { URI(href) } catch (_: Exception) { return null }
        val baseHost = try { URI(base).host } catch (_: Exception) { null }
        if (baseHost == null || !uri.host.removePrefix("www.").equals(baseHost.removePrefix("www."), true)) return null
        val path = uri.path ?: return null
        val series = path.startsWith("/dizi/")
        if (!series && !path.startsWith("/film/")) return null
        val img = anchor.selectFirst("img")
        val title = sequenceOf(anchor.attr("data-title"), anchor.attr("title"), img?.attr("alt").orEmpty(), anchor.text())
            .map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val rawPoster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src").orEmpty()
        return Card(title, href, absolute(rawPoster, base), series)
    }

    fun results(document: Document, base: String): List<Card> = document
        .select("div.film-list div.item-relative a.item[href]")
        .mapNotNull { card(it, base) }.distinctBy { it.url }

    /** The site's JS inserts the JSON `kayitlar` value as HTML. */
    fun suggestions(html: String, base: String): List<Card> = Jsoup.parse(html, base)
        .select("a[href]").mapNotNull { card(it, base) }.distinctBy { it.url }
}

/** Only use suggestions when the full search fails or returns no cards. */
internal suspend fun <T> searchWithFallback(primary: suspend () -> List<T>, fallback: suspend () -> List<T>): List<T> {
    val first = try { primary() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    if (first.isNotEmpty()) return first
    return try { fallback() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
}
