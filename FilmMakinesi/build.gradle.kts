version = 80

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

cloudstream {
    authors     = listOf("keyiflerolsun", "usdogu")
    language    = "tr"
    description = "Film Makinesi, en yeni ve en güncel filmleri sitemizde full HD kalite farkı ile izleyebilirsiniz. HD film izle denildiğinde akla gelen en kaliteli film izleme sitesi."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=filmmakinesi.film&sz=%size%"
}
