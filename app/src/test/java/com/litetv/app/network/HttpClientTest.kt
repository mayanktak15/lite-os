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
}
