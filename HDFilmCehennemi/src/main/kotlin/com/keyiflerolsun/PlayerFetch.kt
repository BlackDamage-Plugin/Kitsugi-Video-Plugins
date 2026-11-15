package com.keyiflerolsun

import android.util.Log
import kotlinx.coroutines.CancellationException
import com.lagradost.cloudstream3.app
import okhttp3.Interceptor

/**
 * CloseLoad / Rapidrame oynatıcı sayfalarına **nasıl** gidileceğini ve çözülen
 * akışın gerçekten oynatılabilir olup olmadığını belirler.
 *
 * NEDEN BU KATMAN VAR:
 *  - Close host'u (`hdfilmcehennemi.mobi`, bazı sürümlerde `closeload.*`) masaüstü
 *    User-Agent ile 404 dönerken kısa `Mozilla/5.0` ile oynatıcıyı veriyor. Eski kod
 *    bu UA'yı **host adına** sabitliyordu; site oynatıcı host'unu değiştirdiğinde
 *    Close isteği 404 alıyor ve kaynak sessizce listeden düşüyordu (Rapidrame aynı
 *    sayfada çalışmaya devam ettiği için sebep görünmüyordu).
 *  - Aynı şekilde Referer kuralı da değişebiliyor: bazen film sayfası, bazen yalnızca
 *    host kökü kabul ediliyor. Tek bir sabit seçim yapmak yerine sırayla deniyoruz.
 *  - Çözülen akış adresi imzalı/kısa ömürlü olabildiği için kullanıma sunmadan önce
 *    ayrıştırıcıdan gerçek bir yanıt isteyip `#EXTM3U` görüyor muyuz diye bakıyoruz.
 *    Böylece "kaynak listede var ama açılmıyor" durumu ayıklanıyor.
 */
internal object PlayerFetch {
    private const val TAG = "KitsugiPlayer"

    /** Close host'larının oynatıcı verdiği en küçük tarayıcı kimliği. */
    const val SHORT_USER_AGENT = "Mozilla/5.0"

    /** Son çare: mobil tarayıcı kimliği (bazı host'lar masaüstünü reddediyor). */
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Tek bir oynatıcı isteği denemesi. */
    data class Variant(val userAgent: String, val referer: String?, val why: String)

    /** Oynatıcı sayfası isteğinin sonucu. */
    class Response(val body: String, val finalUrl: String, val status: Int, val error: String?) {
        val usable: Boolean get() = error == null && status in 200..299 && body.isNotBlank()

        /** Host bu kimliği/isteği reddetti: aynı UA ile diğer Referer'ları denemek anlamsız. */
        val blocked: Boolean
            get() = error == null && (status == 401 || status == 403 || status == 404 || status == 429 || status == 451)
    }

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = java.net.URI(url)
            val host = uri.host
            if (host.isNullOrBlank()) {
                null
            } else {
                val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
                "${uri.scheme ?: "https"}://$host$port"
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Close ailesi mi? Karar YALNIZCA host'a bakar; yol tahmini yanlış olursa
     * [variants] sırasındaki yedek User-Agent yine de doğru sonucu verir.
     */
    fun isCloseLike(url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty() || host.contains("rapid")) return false
        return host.endsWith(".mobi") || host.contains("closeload") || host.contains("close")
    }

    /**
     * Denenecek istek varyantları (sırayla): önce host'a uygun UA + film sayfası
     * Referer'ı, sonra kök Referer, sonra yedek UA'lar.
     */
    fun variants(embedUrl: String, pageReferer: String?): List<Variant> {
        val preferred = if (isCloseLike(embedUrl)) SHORT_USER_AGENT else PlayerResolver.USER_AGENT
        val alternate = if (preferred == SHORT_USER_AGENT) PlayerResolver.USER_AGENT else SHORT_USER_AGENT
        val page = pageReferer?.takeIf { it.isNotBlank() }
        val root = originOf(page)?.let { "$it/" }
        val out = ArrayList<Variant>()
        fun add(userAgent: String, referer: String?, why: String) {
            if (out.any { it.userAgent == userAgent && it.referer == referer }) return
            out.add(Variant(userAgent, referer, why))
        }
        add(preferred, page, "varsayılan")
        add(preferred, root, "kök referer")
        add(alternate, page, "yedek UA")
        add(alternate, root, "yedek UA + kök referer")
        add(MOBILE_USER_AGENT, page, "mobil UA")
        return out
    }

    /** Oynatıcı sayfasını bir varyantla indirir; ağ hatasını durum kodu olarak döndürür. */
    suspend fun load(
        embedUrl: String,
        variant: Variant,
        interceptor: Interceptor?,
        timeoutSec: Long = 12L
    ): Response {
        val headers = linkedMapOf(
            "User-Agent" to variant.userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
        try {
            val res = app.get(
                embedUrl,
                referer = variant.referer,
                headers = headers,
                interceptor = interceptor,
                cacheTime = 0,
                timeout = timeoutSec
            )
            if (!res.isSuccessful) {
                Log.w(TAG, "oynatıcı HTTP ${res.code} (${variant.why}, ${variant.userAgent.take(24)}) $embedUrl")
                return Response("", embedUrl, res.code, null)
            }
            val body = try {
                res.text
            } catch (e: Exception) {
                Log.w(TAG, "oynatıcı gövdesi okunamadı (${variant.why}): ${e.message}")
                return Response("", embedUrl, res.code, e.message)
            }
            return Response(body, res.url.ifBlank { embedUrl }, res.code, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "oynatıcı isteği başarısız (${variant.why}): ${e.message} · $embedUrl")
            return Response("", embedUrl, 0, e.message)
        }
    }
}

/**
 * Çözülen akışın gerçekten yayın verip vermediğini denetler.
 *
 * Oynatıcılar imzalı/kısa ömürlü adres üretiyor ve CDN'ler Referer'sız 404 dönüyor.
 * Denetim, oynatıcının kendi başlıklarıyla küçük bir HLS isteği yapar:
 *  - `#EXTM3U` görülürse  → oynatılabilir,
 *  - HTML/hata gövdesi ya da 4xx → oynatılamaz,
 *  - ağ hatası/zaman aşımı       → bilinmiyor (kaynak yine de sunulur).
 */
internal object StreamCheck {
    private const val TAG = "KitsugiPlayer"

    /** Yalnızca çalma listesi (küçük metin) adresleri denetlenir; mp4 indirmeyiz. */
    fun isPlaylistUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".txt") || path.endsWith(".urlset")
    }

    /** @param playable null = bilinmiyor, true/false = karar verildi. */
    data class Verdict(val playable: Boolean?, val referer: String)

    suspend fun verify(streamUrl: String, playerUrl: String, displayName: String): Verdict {
        if (!isPlaylistUrl(streamUrl)) return Verdict(null, playerUrl)
        val origin = PlayerFetch.originOf(playerUrl)
        val referers = listOfNotNull(playerUrl, origin?.let { "$it/" }).distinct()
        var blocked = false
        var unknown = false
        for (referer in referers) {
            val headers = linkedMapOf(
                "User-Agent" to PlayerResolver.USER_AGENT,
                "Referer" to referer,
                "Accept" to "*/*",
                "Range" to "bytes=0-4095"
            )
            if (!origin.isNullOrBlank()) headers["Origin"] = origin
            val res = try {
                app.get(streamUrl, referer = referer, headers = headers, cacheTime = 0, timeout = 6L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "$displayName: akış denetlenemedi (${e.message})")
                return Verdict(null, playerUrl)
            }
            if (res.code in 200..299) {
                val text = try {
                    res.text
                } catch (e: Exception) {
                    ""
                }
                val head = text.take(4096)
                when {
                    head.contains("#EXTM3U") -> return Verdict(true, referer)
                    head.trimStart().startsWith("<") -> blocked = true
                    head.isBlank() -> unknown = true
                    else -> unknown = true
                }
                continue
            }
            if (res.code == 416) {
                // Aralık isteği reddedildi: adres ölü sayılmaz.
                unknown = true
            } else if (res.code in 400..499) {
                Log.w(TAG, "$displayName: akış HTTP ${res.code} (referer=${referer.take(48)})")
                blocked = true
            } else {
                unknown = true
            }
        }
        return Verdict(if (blocked && !unknown) false else null, playerUrl)
    }
}
