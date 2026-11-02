package com.hexated

import java.net.URI
import java.util.Locale

enum class AnizmDriveKind { MEDIA, HTML, DEAD, UNKNOWN }
data class AnizmDriveResponse(val kind: AnizmDriveKind, val body: String? = null)

/** Pure policies shared by Drive inspection and extractor re-emission. */
internal object AnizmPlaybackPolicy {
    fun mergeHeaders(defaults: Map<String, String>, overrides: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun put(name: String, value: String) {
            if (name.isBlank() || value.isBlank()) return
            result.keys.filter { it.equals(name, ignoreCase = true) }.toList().forEach(result::remove)
            result[name] = value
        }
        defaults.forEach(::put)
        overrides.forEach(::put)
        return result
    }

    fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.lastOrNull { it.key.equals(name, true) && it.value.isNotBlank() }?.value

    fun isDriveHost(url: String): Boolean = try {
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            (host == "drive.google.com" || host == "docs.google.com" || host == "drive.usercontent.google.com")
    } catch (_: Exception) { false }

    fun isDriveDownload(url: String): Boolean = try {
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path.orEmpty().lowercase(Locale.ROOT).trimEnd('/')
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            ((host == "drive.usercontent.google.com" && path == "/download") ||
                ((host == "drive.google.com" || host == "docs.google.com") && path == "/uc"))
    } catch (_: Exception) { false }

    /** A Range response is sampled only; access errors are not proof of an invalid video. */
    fun classifyDrive(status: Int, contentType: String?, disposition: String?, sample: ByteArray): AnizmDriveKind {
        if (status == 404 || status == 410) return AnizmDriveKind.DEAD
        if (status !in 200..299) return AnizmDriveKind.UNKNOWN
        val text = sample.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase(Locale.ROOT)
        if (text.startsWith("<") && listOf("<!doctype html", "<html", "<head", "<body", "<form", "<script", "<div", "<meta").any(text::contains)) {
            return AnizmDriveKind.HTML
        }
        val mime = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        return when {
            mime == "text/html" || mime == "application/xhtml+xml" -> AnizmDriveKind.HTML
            mime.startsWith("video/") || mime == "application/octet-stream" ||
                disposition.orEmpty().contains("attachment", ignoreCase = true) -> AnizmDriveKind.MEDIA
            else -> AnizmDriveKind.UNKNOWN
        }
    }
}
