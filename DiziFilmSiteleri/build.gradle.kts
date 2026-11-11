version = 125

cloudstream {
    authors     = listOf("caca1403")
    language    = "tr"
    description = "Yerli ve yabancı film arşivleri ile popüler dizi platformlarına erişim sağlayan, gelişmiş oynatıcı ve içerik filtreleme destekli medya eklentisi."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Cartoon")
    iconUrl = "https://cdn-icons-png.flaticon.com/512/3163/3163478.png"
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.1")
}


android {
    namespace = "com.lagradost.cloudstream3.providers"
}
