version = 19

cloudstream {
    authors     = listOf("hexated", "byayzen")
    language    = "tr"
    description = "Anizm (anizm.net / anizm.com.tr) anime arşivi: isim eşleştirmeli arama, kategoriler, detay ve bölümler; tüm fansub/video kaynaklarıyla oynatıcı çözücü. +18/+16 yaş uyarısı eklentide gösterilmez."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status      = 1
    tvTypes     = listOf("Anime")
    iconUrl     = "https://www.google.com/s2/favicons?domain=anizm.net&sz=%size%"
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Canlı prob (LiveChainProbeTest) çıktısı CI günlüğünde/PR yorumunda görünsün.
tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    outputs.upToDateWhen { false }
}
