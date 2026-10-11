package com.litetv.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientTest {

    @Test
    fun fetchString_rejectsNonHttpSchemes() {
        val ftpResult = HttpClient.fetchString("ftp://example.com/playlist.m3u")
        assertNull(ftpResult)

        val rtspResult = HttpClient.fetchString("rtsp://example.com/live.stream")
        assertNull(rtspResult)

        val fileResult = HttpClient.fetchString("file:///etc/passwd")
        assertNull(fileResult)
    }

    @Test
    fun fetchStringConditional_rejectsInvalidScheme() {
        val result = HttpClient.fetchStringConditional("gopher://example.com")
        assertEquals(-1, result.statusCode)
        assertNull(result.body)
        assertFalse(result.isNotModified)
    }

    @Test
    fun httpResult_dataClassProperties() {
        val result = HttpResult(
            statusCode = 304,
            body = null,
            etag = "\"etag-123\"",
            lastModified = "Thu, 1 Jan 2026 00:00:00 GMT",
            isNotModified = true,
            contentLength = 0L
        )

        assertEquals(304, result.statusCode)
        assertTrue(result.isNotModified)
        assertEquals("\"etag-123\"", result.etag)
        assertEquals("Thu, 1 Jan 2026 00:00:00 GMT", result.lastModified)
        assertNull(result.body)
    }

    @Test
    fun resolveRedirectUrl_resolvesRelativeAndAbsoluteLocations() {
        val base = "https://example.com/api/v1/data.json"

        // Relative path
        val rel1 = HttpClient.resolveRedirectUrl(base, "/v2/data.json")
        assertEquals("https://example.com/v2/data.json", rel1)

        // Relative sibling
        val rel2 = HttpClient.resolveRedirectUrl(base, "other.json")
        assertEquals("https://example.com/api/v1/other.json", rel2)

        // Relative with ..
        val rel3 = HttpClient.resolveRedirectUrl(base, "../v2/data.json")
        assertEquals("https://example.com/api/v2/data.json", rel3)

        // Absolute URL
        val abs = HttpClient.resolveRedirectUrl(base, "https://cdn.example.com/live/data.json")
        assertEquals("https://cdn.example.com/live/data.json", abs)
    }
}
