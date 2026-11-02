package com.hexated

import org.junit.Assert.*
import org.junit.Test

class AnizmPlaybackPolicyTest {
    @Test
    fun `extractor headers override defaults without case-sensitive duplicates`() {
        val headers = AnizmPlaybackPolicy.mergeHeaders(
            mapOf("Referer" to "https://www.yourupload.com/embed/abc", "User-Agent" to "Default"),
            mapOf("referer" to "https://override.example/", "user-agent" to "HostUA", "Origin" to "https://yourupload.com")
        )
        assertEquals("https://override.example/", AnizmPlaybackPolicy.header(headers, "Referer"))
        assertEquals("HostUA", AnizmPlaybackPolicy.header(headers, "USER-AGENT"))
        assertEquals(1, headers.keys.count { it.equals("referer", true) })
    }

    @Test
    fun `drive html is not a video and protected responses are unknown`() {
        assertEquals(AnizmDriveKind.MEDIA, AnizmPlaybackPolicy.classifyDrive(206, "video/mp4", null, byteArrayOf(0, 1, 2)))
        assertEquals(AnizmDriveKind.HTML, AnizmPlaybackPolicy.classifyDrive(200, "application/octet-stream", "attachment", "<!DOCTYPE html><html>quota</html>".toByteArray()))
        assertEquals(AnizmDriveKind.HTML, AnizmPlaybackPolicy.classifyDrive(200, "text/html", null, "<form>warning</form>".toByteArray()))
        assertEquals(AnizmDriveKind.UNKNOWN, AnizmPlaybackPolicy.classifyDrive(403, "text/html", null, "forbidden".toByteArray()))
        assertEquals(AnizmDriveKind.DEAD, AnizmPlaybackPolicy.classifyDrive(404, null, null, byteArrayOf()))
    }

    @Test
    fun `download urls are recognized without confusing preview or untrusted host`() {
        assertTrue(AnizmPlaybackPolicy.isDriveDownload("https://drive.google.com/uc?id=A&confirm=token&uuid=uuid"))
        assertTrue(AnizmPlaybackPolicy.isDriveDownload("https://drive.usercontent.google.com/download?id=A&confirm=token"))
        assertFalse(AnizmPlaybackPolicy.isDriveDownload("https://drive.google.com/file/d/A/preview"))
        assertFalse(AnizmPlaybackPolicy.isDriveHost("https://drive.google.com.attacker.test/uc?id=A"))
    }
}
