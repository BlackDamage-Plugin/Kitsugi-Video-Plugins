package com.keyiflerolsun

import android.util.Log
import kotlinx.coroutines.CancellationException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor

/**
 * Oynatıcı (embed) sayfasını indirir, [PlayerDecoder] ile GERÇEK yayın
 * adresini çözer ve oynatılabilir bir [ExtractorLink] üretir.
 *
 * Erişim tek bir User-Agent/Referer kombinasyonuna bağlı değildir: sırayla
 * [PlayerFetch.variants] denenir (kısa UA ↔ masaüstü UA, film sayfası ↔ kök
 * Referer). Çözülen adres [StreamCheck] ile ayrıştırıcıdan doğrulanır; böylece
 * ölü/tuzak adres kaynak olarak sunulmaz, çalışan adres sunulur.
 *
 * Canlı doğrulama (Ekim 2026, TR çıkışlı):
 *  - Close CDN  : srvN.cdnimagesNNNN.cyou/hls/<ad>.mp4/txt/master.txt
 *  - Rapid CDN  : sNNN.rapidrame.com/hls2/.../master.m3u8?t=...
 *  - İkisi de Referer'sız 404 döner; Referer = oynatıcı sayfası,
 *    Origin = oynatıcı sayfasının kökü ile 200 + #EXTM3U döner.
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

    private fun buildLink(displayName: String, streamUrl: String, referer: String): ExtractorLink {
        val origin = originOf(referer)
        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "*/*"
        )
        if (!origin.isNullOrBlank()) headers["Origin"] = origin
        return newExtractorLink(
            source = displayName,
            name = displayName,
            url = streamUrl,
            type = linkTypeFor(streamUrl)
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = headers
        }
    }

    /**
     * @param embedUrl    oynatıcı sayfası (closeload/rapid/rplayer/hdfilmcehennemi.mobi)
     * @param pageReferer oynatıcıyı gömen film/bölüm sayfası
     * @param displayName kaynak adı (ör. "Close · Altyazılı")
     * @param deadlineMs  bu kaynak için ayrılan süre (uygulamanın 15 sn sınırının altında kalınır)
     */
    suspend fun resolve(
        embedUrl: String,
        pageReferer: String?,
        displayName: String,
        interceptor: Interceptor? = null,
        deadlineMs: Long = 9_000L
    ): Resolved? {
        val deadline = System.currentTimeMillis() + deadlineMs
        var fallback: Resolved? = null
        var fallbackWhy = ""
        var skipUserAgent: String? = null

        for (variant in PlayerFetch.variants(embedUrl, pageReferer)) {
            if (variant.userAgent == skipUserAgent) continue
            val remaining = deadline - System.currentTimeMillis()
            if (remaining < 1_500L) {
                Log.d(TAG, "$displayName: süre doldu, '${variant.why}' denenemedi")
                break
            }
            val timeoutSec = (remaining / 1000L).coerceIn(3L, 12L)
            val page = PlayerFetch.load(embedUrl, variant, interceptor, timeoutSec)
            if (!page.usable) {
                // Host bu kimliği reddettiyse aynı UA ile diğer Referer'ları denemek anlamsız.
                if (page.blocked) skipUserAgent = variant.userAgent
                continue
            }
            // Çözücü anahtarı location.hostname'e bağlı: önce yönlendirme sonrası adres,
            // sonra istenen adres (yönlendirme olduysa iki host da denenir).
            val decodeUrls = listOf(page.finalUrl, embedUrl).distinct().filter { it.isNotBlank() }
            for (decodeUrl in decodeUrls) {
                val targeted = decode(page.body, decodeUrl, variant, displayName, targeted = true) ?: continue
                val (resolved, playable) = evaluate(targeted, displayName, decodeUrl, variant)
                if (playable != false) return resolved
                if (fallback == null) {
                    fallback = resolved
                    fallbackWhy = "${variant.why}/${targeted.method}"
                }
                // Hedefli adres yayında ölü çıktı: sayfanın tamamı jwplayer taklidiyle
                // çalıştırılıp yakalanan setup() yapılandırması ikinci aday olarak denenir.
                val general = decode(page.body, decodeUrl, variant, displayName, targeted = false)
                if (general != null && general.file != targeted.file) {
                    val (resolvedGeneral, playableGeneral) = evaluate(general, displayName, decodeUrl, variant)
                    if (playableGeneral != false) return resolvedGeneral
                    if (fallback == null) {
                        fallback = resolvedGeneral
                        fallbackWhy = "${variant.why}/${general.method}"
                    }
                }
            }
        }

        if (fallback != null) {
            Log.w(TAG, "$displayName: akış doğrulanamadı, yine de sunuluyor ($fallbackWhy)")
        } else {
            Log.w(TAG, "$displayName: hiçbir varyant sonuç vermedi ($embedUrl)")
        }
        return fallback
    }

    private suspend fun decode(
        html: String,
        playerUrl: String,
        variant: PlayerFetch.Variant,
        displayName: String,
        targeted: Boolean
    ): PlayerDecoder.Decoded? {
        return try {
            PlayerDecoder.decode(html, playerUrl, targeted).also { decoded ->
                if (decoded == null) {
                    Log.w(
                        TAG,
                        "$displayName: adres çözülemedi (${variant.why}, hedefli=$targeted, ${html.length} bayt)"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "$displayName: çözücü hatası (${variant.why}): ${e.message}")
            null
        }
    }

    /** Adayı akış denetiminden geçirir; doğrulanamasa da sunulabilir hâlde döndürür. */
    private suspend fun evaluate(
        candidate: PlayerDecoder.Decoded,
        displayName: String,
        playerUrl: String,
        variant: PlayerFetch.Variant
    ): Pair<Resolved, Boolean?> {
        val verdict = StreamCheck.verify(candidate.file, playerUrl, displayName)
        val resolved = Resolved(
            buildLink(displayName, candidate.file, verdict.referer),
            candidate.tracks.map { newSubtitleFile(subtitleLabel(it), it.file) }
        )
        Log.i(
            TAG,
            "$displayName -> ${candidate.file} [${candidate.method}/${variant.why}/doğrulama=${verdict.playable}]"
        )
        return resolved to verdict.playable
    }
}
