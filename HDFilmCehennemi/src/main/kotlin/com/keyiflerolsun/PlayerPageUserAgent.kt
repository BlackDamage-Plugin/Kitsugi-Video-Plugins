package com.keyiflerolsun

/** Close host'ları masaüstü kimliğiyle 404, kısa `Mozilla/5.0` ile oynatıcı verir. */
internal fun playerPageUserAgent(url: String): String = PlayerFetch.userAgentFor(url)
