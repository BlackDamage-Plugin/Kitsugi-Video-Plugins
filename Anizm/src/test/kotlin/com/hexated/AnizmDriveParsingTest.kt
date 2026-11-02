package com.hexated

import org.junit.Assert.*
import org.junit.Test

class AnizmDriveParsingTest {
    private val http = object : AnizmHttp {
        override suspend fun get(url: String, referer: String?) = null
        override suspend fun getXhr(url: String, referer: String?) = null
        override suspend fun inspectDrive(url: String, referer: String) = AnizmDriveResponse(AnizmDriveKind.UNKNOWN)
        override suspend fun location(url: String, referer: String?) = null
        override suspend fun getWith(url: String, extra: Map<String, String>) = null
        override suspend fun postJson(url: String, json: String, referer: String?) = null
        override suspend fun postForm(url: String, form: Map<String, String>, referer: String?) = null
    }
    private val streams = AnizmStreams("Anizm", { "https://anizm.example" }, http)

    @Test
    fun `confirmation form keeps all fields regardless of attribute order`() {
        val html = """<form action='https://drive.usercontent.google.com/download?existing=1' method='get'>
          <input value='FILE123' name='id'><input name='confirm' value='t'>
          <input value='a+b/=' name='uuid'>
        </form>"""
        assertEquals(
            "https://drive.usercontent.google.com/download?existing=1&id=FILE123&confirm=t&uuid=a%2Bb%2F%3D",
            streams.driveDownloadUrl(html, "https://drive.google.com/uc?id=FILE123")
        )
    }

    @Test
    fun `quota page and unrelated forms are not download links`() {
        assertNull(streams.driveDownloadUrl("<html>Download quota exceeded</html>", "https://drive.google.com/uc?id=A"))
        assertNull(streams.driveDownloadUrl("<form action='https://attacker.test/download'><input name='id' value='A'></form>", "https://drive.google.com/uc?id=A"))
        assertNull(streams.driveDownloadUrl("<form method='post' action='https://drive.google.com/uc'><input name='id' value='A'></form>", "https://drive.google.com/uc?id=A"))
        assertNull(streams.driveDownloadUrl("<form action='http://drive.google.com/uc'><input name='id' value='A'></form>", "https://drive.google.com/uc?id=A"))
    }
}
