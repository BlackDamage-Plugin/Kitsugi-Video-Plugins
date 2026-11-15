package com.keyiflerolsun

import android.util.Log
import kotlinx.coroutines.CancellationException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor

/**
 * Oynatıcı sayfasını indirir, [PlayerDecoder] ile gerçek yayın adresini çözer.
 *
     * Close: kısa UA, 404/403 olursa bir kez masaüstü UA (her biri 5 sn).
     * Rapid: masaüstü UA. Cloudflare Killer Close `.mobi` isteğine girmez.
     */
internal object PlayerResolver {
    private const val TAG = "KitsugiPlayer"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    class Resolved(val link: ExtractorLink, val subtitles: List<SubtitleFile>)

    fun originOf(url: String): String? = PlayerFetch.originOf(url)

    private fun linkTypeFor(url: String): ExtractorLinkType {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return if (path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm")) {
            ExtractorLinkType.VIDEO
        } else {
            ExtractorLinkType.M3U8
        }
    }

    private fun subtitleLabel(track: PlayerDecoder.Track): String {
        val label = track.label.trim()
        val lang = track.language?.lowercase().orEmpty()
        return when {
            label.equals("forced", ignoreCase = true) -> "Türkçe (Forced)"
            label.equals("turkish", ignoreCase = true) || label.contains("rkçe", ignoreCase = true) ||
                (label.isBlank() && lang == "tr") -> "Türkçe"
            label.equals("english", ignoreCase = true) || label.contains("ngilizce", ignoreCase = true) ||
                (label.isBlank() && lang == "en") -> "İngilizce"
            label.isNotBlank() -> label
            lang.isNotBlank() -> lang
            else -> "Altyazı"
        }
    }

    suspend fun resolve(
        embedUrl: String,
        pageReferer: String?,
        displayName: String,
        interceptor: Interceptor? = null
    ): Resolved? {
        val agents = LinkedHashSet<String>().apply {
            add(playerPageUserAgent(embedUrl))
            if (PlayerFetch.isCloseLike(embedUrl)) add(USER_AGENT)
        }
        var body: String? = null
        var finalUrl = embedUrl
        for (agent in agents) {
            val response = try {
                app.get(
                    embedUrl,
                    referer = pageReferer,
                    headers = mapOf(
                        "User-Agent" to agent,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                    ),
                    interceptor = interceptor,
                    cacheTime = 0,
                    timeout = 5L
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$displayName: oynatıcı sayfası alınamadı ($embedUrl): ${e.message}")
                continue
            }
            if (response.isSuccessful) {
                body = response.text
                finalUrl = response.url.ifBlank { embedUrl }
                break
            }
            Log.w(TAG, "$displayName: oynatıcı sayfası HTTP ${response.code} (ua=${agent.take(24)})")
            if (response.code !in listOf(403, 404)) return null
        }
        val html = body ?: return null
        val decoded = try {
            runPlayerDecoding { PlayerDecoder.decode(html, finalUrl) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "$displayName: çözücü hatası: ${e.message}")
            null
        }
        if (decoded == null) {
            Log.w(TAG, "$displayName: yayın adresi çözülemedi ($finalUrl, ${html.length} bayt)")
            return null
        }
        val origin = originOf(finalUrl) ?: return null
        val link = newExtractorLink(
            source = displayName,
            name = displayName,
            url = decoded.file,
            type = linkTypeFor(decoded.file)
        ) {
            this.referer = finalUrl
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to finalUrl,
                "Origin" to origin,
                "Accept" to "*/*"
            )
        }
        val subtitles = decoded.tracks.map { newSubtitleFile(subtitleLabel(it), it.file) }
        Log.i(TAG, "$displayName -> ${decoded.file} [${decoded.method}, ${subtitles.size} altyazı]")
        return Resolved(link, subtitles)
    }
}
