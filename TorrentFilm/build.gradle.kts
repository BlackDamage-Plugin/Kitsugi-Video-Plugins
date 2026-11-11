import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

tasks.named("make") {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        buildDir.mkdirs()
        val cs3File = File(buildDir, "${project.name}.cs3")
        val zipFile = ZipOutputStream(cs3File.outputStream())
        listOf("manifest.json", "classes.dex").forEach { fileName ->
            val file = file(fileName)
            if (file.exists()) {
                zipFile.putNextEntry(ZipEntry(fileName))
                file.inputStream().use { it.copyTo(zipFile) }
                zipFile.closeEntry()
            }
        }
        zipFile.close()
    }
}
