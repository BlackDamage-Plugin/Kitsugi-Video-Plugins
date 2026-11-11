plugins {
    // CloudStream Gradle plugin automatically applied by parent
}

version = 60

cloudstream {
    authors     = listOf("caca1403")
    language    = "tr"
    description = "Doğa, tarih, uzay, bilim ve suç araştırma kategorilerindeki belgesel yayınlarını yüksek çözünürlükle sunan belgesel izleme kaynağı."
    status      = 1
    tvTypes     = listOf("Documentary")
    iconUrl     = "https://images.unsplash.com/photo-1500485035595-cbe6f645feb1?w=512&q=80"
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.11")

    // HTML parsing
    implementation("org.jsoup:jsoup:1.15.3")

    // HTTP client (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}


android {
    namespace = "com.lagradost.cloudstream3.providers"
}
