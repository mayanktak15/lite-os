package com.litetv.app.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for PlayerManager, stream recovery, error classification, and failover behavior.
 */
class PlayerManagerTest {

    // --- 1. Stream URL Sanitization Tests ---

    @Test
    fun sanitizeStreamUrl_stripsSensitiveTokensAndCredentials() {
        val urlWithToken = "https://user:password@live.stream.provider.com:8080/live/user123/pass456/10293.m3u8?token=SECRET_JWT_TOKEN&auth=xyz"
        val sanitized = PlayerManager.sanitizeStreamUrl(urlWithToken)

        assertFalse("Sanitized URL must not contain sensitive password", sanitized.contains("password"))
        assertFalse("Sanitized URL must not contain sensitive token query", sanitized.contains("SECRET_JWT_TOKEN"))
        assertFalse("Sanitized URL must not contain query parameters", sanitized.contains("auth=xyz"))
        assertTrue("Sanitized URL should contain host", sanitized.contains("live.stream.provider.com"))
        assertTrue("Sanitized URL should contain stream file", sanitized.contains("10293.m3u8"))
    }

    @Test
    fun sanitizeStreamUrl_handlesMalformedOrEmptyUrls() {
        assertEquals("", PlayerManager.sanitizeStreamUrl(""))
        val invalidUrl = "not a valid url"
        val sanitized = PlayerManager.sanitizeStreamUrl(invalidUrl)
        assertTrue(sanitized.endsWith("..."))
    }

    @Test
    fun getHostFromUrl_extractsCorrectHostOrFallback() {
        assertEquals("stream.provider.com", PlayerManager.getHostFromUrl("https://stream.provider.com/live/ch1.m3u8"))
        assertEquals("192.168.1.100", PlayerManager.getHostFromUrl("http://192.168.1.100:8000/stream.ts"))
        assertEquals("unknown", PlayerManager.getHostFromUrl(""))
        assertEquals("unknown", PlayerManager.getHostFromUrl(":::bad-uri:::"))
    }

    // --- 2. Stream MIME Type Sniffing & MediaItem Creation Tests ---

    @Test
    fun detectMimeType_correctlyIdentifiesHlsStreams() {
        assertEquals(MimeTypes.APPLICATION_M3U8, PlayerManager.detectMimeType("https://example.com/live/stream.m3u8"))
        assertEquals(MimeTypes.APPLICATION_M3U8, PlayerManager.detectMimeType("https://example.com/hls/channel_01"))
        assertEquals(MimeTypes.APPLICATION_M3U8, PlayerManager.detectMimeType("https://example.com/play?format=m3u8&id=10"))
    }

    @Test
    fun detectMimeType_correctlyIdentifiesMpegTsStreams() {
        assertEquals(MimeTypes.VIDEO_MP2T, PlayerManager.detectMimeType("http://example.com:8000/live/user/pass/5432.ts"))
        assertEquals(MimeTypes.VIDEO_MP2T, PlayerManager.detectMimeType("http://example.com/stream/mpegts_live"))
    }

    @Test
    fun detectMimeType_correctlyIdentifiesDashStreams() {
        assertEquals(MimeTypes.APPLICATION_MPD, PlayerManager.detectMimeType("https://example.com/live/manifest.mpd"))
    }

    @Test
    fun detectMimeType_leavesDefaultProgressiveWhenNoMatch() {
        assertNull(PlayerManager.detectMimeType("https://example.com/video/sample.mp4"))
    }

    // --- 3. Error Classification & Recoverability Tests ---

    @Test
    fun isErrorRecoverable_marksHttp403And404AsPermanent() {
        val playerManager = PlayerManager()
        val exception = PlaybackException("HTTP Error", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        // 403 Forbidden -> unauthorized stream, do not retry indefinitely
        assertFalse(playerManager.isErrorRecoverable(exception, 403))

        // 404 Not Found -> dead feed, do not retry indefinitely
        assertFalse(playerManager.isErrorRecoverable(exception, 404))

        // 401 Unauthorized -> unauthorized stream, fail fast
        assertFalse(playerManager.isErrorRecoverable(exception, 401))

        // 410 Gone -> expired stream URL
        assertFalse(playerManager.isErrorRecoverable(exception, 410))
    }

    @Test
    fun isErrorRecoverable_marksHttp5xxAndNetworkTimeoutsAsRecoverable() {
        val playerManager = PlayerManager()
        val exception = PlaybackException("HTTP Error", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        // 502/503/504 Bad Gateway / Server Unavailable -> transient, recoverable
        assertTrue(playerManager.isErrorRecoverable(exception, 502))
        assertTrue(playerManager.isErrorRecoverable(exception, 503))
        assertTrue(playerManager.isErrorRecoverable(exception, 504))

        // Network connection timeout -> transient network issue, recoverable
        val timeoutException = PlaybackException("Timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertTrue(playerManager.isErrorRecoverable(timeoutException, null))
    }

    @Test
    fun isErrorRecoverable_marksHardwareDecoderAndMalformedManifestAsNonRecoverable() {
        val playerManager = PlayerManager()

        // Hardware decoder failure on target device (e.g. unsupported HEVC/AV1 profile)
        val decoderError = PlaybackException("Decoder Init Failed", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertFalse(playerManager.isErrorRecoverable(decoderError, null))

        // Malformed HLS manifest
        val malformedManifestError = PlaybackException("Manifest Corrupt", null, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)
        assertFalse(playerManager.isErrorRecoverable(malformedManifestError, null))

        // Malformed container segments
        val malformedContainerError = PlaybackException("Container Corrupt", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertFalse(playerManager.isErrorRecoverable(malformedContainerError, null))
    }

    @Test
    fun categorizeError_providesAccurateUserFacingMessages() {
        val playerManager = PlayerManager()

        // 403 Forbidden
        val err403 = PlaybackException("Forbidden", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val (title403, msg403) = playerManager.categorizeError(err403, 403)
        assertEquals("Stream Unauthorized", title403)
        assertTrue(msg403.contains("403"))

        // 404 Not Found
        val err404 = PlaybackException("Not Found", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val (title404, msg404) = playerManager.categorizeError(err404, 404)
        assertEquals("Stream Not Found", title404)
        assertTrue(msg404.contains("404"))

        // Decoder Failure
        val errDecoder = PlaybackException("Decoder", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        val (titleDecoder, msgDecoder) = playerManager.categorizeError(errDecoder, null)
        assertEquals("Codec Unsupported", titleDecoder)
        assertTrue(msgDecoder.contains("television hardware"))

        // Connection Timeout
        val errTimeout = PlaybackException("Timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        val (titleTimeout, msgTimeout) = playerManager.categorizeError(errTimeout, null)
        assertEquals("Connection Timed Out", titleTimeout)
        assertTrue(msgTimeout.contains("timed out"))
    }

    @Test
    fun determineCategory_mapsAppropriateCategoryEnum() {
        val playerManager = PlayerManager()

        val errNetwork = PlaybackException("Net", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(PlaybackErrorInfo.Category.NETWORK, playerManager.determineCategory(errNetwork, null))

        val errTimeout = PlaybackException("Timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(PlaybackErrorInfo.Category.TIMEOUT, playerManager.determineCategory(errTimeout, null))

        val errMalformed = PlaybackException("Malformed", null, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)
        assertEquals(PlaybackErrorInfo.Category.MALFORMED, playerManager.determineCategory(errMalformed, null))

        val errDecoder = PlaybackException("Decoder", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertEquals(PlaybackErrorInfo.Category.UNSUPPORTED, playerManager.determineCategory(errDecoder, null))
    }

    @Test
    fun findHttpErrorCode_extractsNestedHttpDataSourceCode() {
        val playerManager = PlayerManager()

        val httpException = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            emptyMap(),
            androidx.media3.datasource.DataSpec(android.net.Uri.parse("https://example.com/stream.m3u8")),
            ByteArray(0)
        )
        val wrappedException = PlaybackException("Wrapper", httpException, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        val code = playerManager.findHttpErrorCode(wrappedException)
        assertEquals(403, code)

        val noHttpException = PlaybackException("No HTTP", IOException("Disk read failure"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertNull(playerManager.findHttpErrorCode(noHttpException))
    }

    // --- 4. Bounded Failover Algorithm Tests ---

    @Test
    fun failoverAlgorithm_skipsFailedChannelsAndStopsAtExhaustion() {
        val channels = listOf("ch0_url", "ch1_url", "ch2_url", "ch3_url")
        val failedIndices = mutableSetOf<Int>()
        val maxConsecutive = 3

        var currentIndex = 0
        var consecutiveFailures = 0

        fun findNextEligible(): Int {
            for (i in 1 until channels.size) {
                val candidate = (currentIndex + i) % channels.size
                if (!failedIndices.contains(candidate)) {
                    return candidate
                }
            }
            return -1
        }

        // Channel 0 fails
        failedIndices.add(0)
        consecutiveFailures++
        val next1 = findNextEligible()
        assertEquals(1, next1)
        currentIndex = next1

        // Channel 1 fails
        failedIndices.add(1)
        consecutiveFailures++
        val next2 = findNextEligible()
        assertEquals(2, next2)
        currentIndex = next2

        // Channel 2 fails
        failedIndices.add(2)
        consecutiveFailures++
        assertEquals(3, consecutiveFailures)
        // Hit max consecutive failovers cap
        assertTrue(consecutiveFailures >= maxConsecutive)

        // User manually retries or channel switches
        failedIndices.add(3)
        // All channels in playlist are exhausted
        assertEquals(4, failedIndices.size)
        val nextExhausted = findNextEligible()
        assertEquals(-1, nextExhausted)
    }

    // --- 5. PlaybackState & Error Info Structure Tests ---

    @Test
    fun playbackErrorInfo_formatsDiagnosticsAccurately() {
        val errorInfo = PlaybackErrorInfo(
            title = "Channel Unavailable",
            message = "Stream connection timed out after 3 attempts.",
            category = PlaybackErrorInfo.Category.TIMEOUT,
            httpStatusCode = 504,
            media3ErrorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            isRecoverable = false,
            sanitizedHost = "cdn.stream.com",
            sanitizedUrl = "cdn.stream.com/.../ch1.m3u8",
            bufferingDurationMs = 15420L,
            retryAttempts = 3,
            diagnostics = "Buffering stall: 15420ms elapsed"
        )

        assertEquals("2002", errorInfo.errorCode)
        assertEquals("Stream connection timed out after 3 attempts.", errorInfo.userFriendlyMessage)
        assertEquals("cdn.stream.com", errorInfo.sanitizedHost)
        assertEquals(3, errorInfo.retryAttempts)
        assertEquals(15420L, errorInfo.bufferingDurationMs)
        assertFalse(errorInfo.isRecoverable)

        val httpOnlyError = PlaybackErrorInfo(
            title = "Not Found",
            message = "Channel not found",
            httpStatusCode = 404,
            media3ErrorCode = null
        )
        assertEquals("404", httpOnlyError.errorCode)
    }
}
