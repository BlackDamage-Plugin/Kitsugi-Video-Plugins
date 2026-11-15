package com.keyiflerolsun

/**
 * Close host'ları (`hdfilmcehennemi.mobi`, `closeload.*`) masaüstü tarayıcı
 * kimliğiyle 404 dönebilirken kısa `Mozilla/5.0` ile oynatıcı veriyor.
 *
 * Bu fonksiyon yalnızca **ilk denenecek** kimliği seçer; [PlayerFetch.variants]
 * sırası boş sonuç/404 durumunda diğer kimlikleri ve Referer'ları da dener, bu
 * yüzden host adı değiştiğinde kaynak sessizce kaybolmaz.
 */
internal fun playerPageUserAgent(url: String): String =
    if (PlayerFetch.isCloseLike(url)) PlayerFetch.SHORT_USER_AGENT else PlayerResolver.USER_AGENT
