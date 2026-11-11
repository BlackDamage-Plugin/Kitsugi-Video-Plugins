import java.net.URLClassLoader
import java.net.URL

version = 135

cloudstream {
    authors     = listOf("caca1403")
    language    = "tr"
    description = "Film, dizi, anime, belgesel ve canlı TV yayınlarını gelişmiş çapraz arama, IMDb/TMDB listeleri ve özelleştirilebilir kategori filtreleri ile tek çatı altında sunan birleşik medya sağlayıcısı."
    status  = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Cartoon", "Documentary", "Live")
    iconUrl = "https://cdn-icons-png.flaticon.com/512/8634/8634073.png"
}

dependencies {
    implementation(project(":DiziFilmSiteleri"))
    implementation(project(":TelevizyonEklentileri"))
    implementation(project(":AnimeSiteleri"))
    implementation(project(":BelgeselProvider"))
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.1")
}

tasks.register("inspectAnimeSearchResponse") {
    doLast {
        val classpath = configurations.getByName("debugCompileClasspath")
            .incoming.artifactView { lenient(true) }.files
        val urls = classpath.map { it.toURI().toURL() }.toTypedArray()
        val classLoader = URLClassLoader(urls, project.buildscript.classLoader)
        listOf("com.lagradost.cloudstream3.plugins.PluginKt", "com.lagradost.cloudstream3.plugins.PluginManagerKt", "com.lagradost.cloudstream3.plugins.PluginManager").forEach { name ->
            try {
                val clazz = classLoader.loadClass(name)
                println("--- $name Methods ---")
                clazz.methods.forEach { method -> println("Method: " + method.name + "(" + method.parameterTypes.map { it.simpleName } + ")") }
            } catch (e: Exception) {
                println("Could not load $name: $e")
            }
        }
    }
}


android {
    namespace = "com.lagradost.cloudstream3.providers"
}
