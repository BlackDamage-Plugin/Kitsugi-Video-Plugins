version = 6

cloudstream {
    authors     = listOf("nikyokki")
    language    = "tr"
    description = "En yeni ve sorunsuz ⚡  Full HD Film izle keyfi her yerde sizinle ! 4K ve 1080p online film izleme farkıyla 4KFilmizlesene&amp;#039;nin Tadını Çıkart."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 0 // Down - Domain satılıkta / kapalı
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.4kfilmizlesene.org&sz=%size%"
}

android {
    namespace = "com.nikyokki"
}
