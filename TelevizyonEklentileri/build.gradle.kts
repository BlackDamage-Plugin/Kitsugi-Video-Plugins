version = 41

cloudstream {
    authors     = listOf("caca1403")
    language    = "tr"
    description = "Ulusal, haber, belgesel ve yerel canlı TV yayın akışları ile nostaljik yerli dizi arşivlerine erişim sağlayan televizyon yayın sağlayıcısı."
    status      = 1
    tvTypes     = listOf("LiveStream", "TvSeries")
    iconUrl     = "https://cdn-icons-png.flaticon.com/512/3075/3075908.png"
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
