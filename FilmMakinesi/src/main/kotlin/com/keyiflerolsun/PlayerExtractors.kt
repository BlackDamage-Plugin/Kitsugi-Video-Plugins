package com.keyiflerolsun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * FilmMakinesi'nin iki oynatıcısı için çözücüler. İkisi de aynı karartma
 * ailesini kullanır; çözümleme [PlayerResolver] + [PlayerDecoder] ile yapılır.
 * Uygulamanın genel loadExtractor() akışı bu host'lara gelirse de çalışır.
 */
open class CloseLoad : ExtractorApi() {
    override val name = "Close"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resolved = PlayerResolver.resolve(
            url, referer, name, FilmMakinesiHttp.interceptor, PlayerResolver.CLOSE_USER_AGENT
        ) ?: return
        resolved.subtitles.forEach(subtitleCallback)
        callback(resolved.link)
    }
}

open class Rapidrame : ExtractorApi() {
    override val name = "Rapid"
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resolved = PlayerResolver.resolve(url, referer, name, FilmMakinesiHttp.interceptor) ?: return
        resolved.subtitles.forEach(subtitleCallback)
        callback(resolved.link)
    }
}
