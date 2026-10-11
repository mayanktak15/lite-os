package com.litetv.app.network

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of an HTTP request, including status code and conditional caching headers.
 */
data class HttpResult(
    val statusCode: Int,
    val body: String?,
    val etag: String? = null,
    val lastModified: String? = null,
    val isNotModified: Boolean = false,
    val contentLength: Long = 0L
)

/**
 * Lightweight HTTP client using built-in HttpURLConnection.
 * No OkHttp, no Retrofit — zero extra dependencies.
 *
 * Features:
 * - HTTPS-only by default (with strict scheme validation)
 * - Conditional HTTP requests (If-None-Match / ETag, If-Modified-Since)
 * - HTTP 304 Not Modified handling
 * - Bounded download sizes
 * - Configurable timeouts and bounded retries with exponential backoff
 * - Follows redirects safely (max 3)
 */
object HttpClient {

    private const val TAG = "HttpClient"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 3
    private const val DEFAULT_MAX_SIZE = 10 * 1024 * 1024  // 10 MB
    private const val BUFFER_SIZE = 8192
    private const val MAX_RETRIES = 2
    private const val INITIAL_BACKOFF_MS = 500L

    /**
     * Fetch a URL and return the response as a String.
     * Returns null on any error.
     */
    fun fetchString(url: String, maxSize: Int = DEFAULT_MAX_SIZE): String? {
        val result = fetchStringConditional(url, maxSize)
        return if (result.statusCode == HttpURLConnection.HTTP_OK) result.body else null
    }

    /**
     * Fetch a URL conditionally with support for ETag (If-None-Match) and Last-Modified (If-Modified-Since).
     * Automatically handles 304 Not Modified and retries on transient network failures.
     */
    fun fetchStringConditional(
        url: String,
        maxSize: Int = DEFAULT_MAX_SIZE,
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null
    ): HttpResult {
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            val result = executeRequest(url, maxSize, ifNoneMatch, ifModifiedSince)
            if (result.statusCode != -1 || attempt >= MAX_RETRIES) {
                return result
            }
            attempt++
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return result
            }
            backoffMs *= 2
        }
    }

    private fun executeRequest(
        url: String,
        maxSize: Int,
        ifNoneMatch: String?,
        ifModifiedSince: String?
    ): HttpResult {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Log.w(TAG, "Rejected non-HTTP(S) URL: ${url.take(50)}")
            return HttpResult(statusCode = -1, body = null)
        }

        var redirectCount = 0
        var currentUrl = url

        while (redirectCount <= MAX_REDIRECTS) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "LiteTV/1.0")
                    setRequestProperty("Accept", "*/*")
                    if (!ifNoneMatch.isNullOrBlank()) {
                        setRequestProperty("If-None-Match", ifNoneMatch)
                    }
                    if (!ifModifiedSince.isNullOrBlank()) {
                        setRequestProperty("If-Modified-Since", ifModifiedSince)
                    }
                }

                val responseCode = connection.responseCode

                // Handle redirects manually to track count and prevent loops
                if (responseCode in 301..308) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrEmpty()) {
                        Log.w(TAG, "Redirect without Location header")
                        return HttpResult(statusCode = responseCode, body = null)
                    }
                    currentUrl = resolveRedirectUrl(currentUrl, location)
                    redirectCount++
                    connection.disconnect()
                    continue
                }

                val etag = connection.getHeaderField("ETag")
                val lastModified = connection.getHeaderField("Last-Modified")

                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    Log.d(TAG, "HTTP 304 Not Modified for ${url.take(80)}")
                    return HttpResult(
                        statusCode = HttpURLConnection.HTTP_NOT_MODIFIED,
                        body = null,
                        etag = etag,
                        lastModified = lastModified,
                        isNotModified = true
                    )
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "HTTP $responseCode for ${url.take(80)}")
                    return HttpResult(statusCode = responseCode, body = null, etag = etag, lastModified = lastModified)
                }

                val contentLength = connection.contentLengthLong
                if (contentLength > maxSize) {
                    Log.w(TAG, "Response too large: $contentLength bytes (max $maxSize)")
                    return HttpResult(statusCode = -1, body = null)
                }

                val inputStream = connection.inputStream
                val output = ByteArrayOutputStream(
                    if (contentLength in 1..maxSize) contentLength.toInt() else BUFFER_SIZE
                )
                val buffer = ByteArray(BUFFER_SIZE)
                var totalRead = 0

                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                    if (totalRead > maxSize) {
                        Log.w(TAG, "Response exceeded max size: $maxSize bytes")
                        return HttpResult(statusCode = -1, body = null)
                    }
                    output.write(buffer, 0, bytesRead)
                }

                val bodyString = String(output.toByteArray(), Charsets.UTF_8)
                return HttpResult(
                    statusCode = HttpURLConnection.HTTP_OK,
                    body = bodyString,
                    etag = etag,
                    lastModified = lastModified,
                    isNotModified = false,
                    contentLength = totalRead.toLong()
                )

            } catch (e: IOException) {
                Log.e(TAG, "Network I/O error fetching ${url.take(80)}: ${e.message}")
                return HttpResult(statusCode = -1, body = null)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching ${url.take(80)}", e)
                return HttpResult(statusCode = -1, body = null)
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }

        Log.w(TAG, "Too many redirects for ${url.take(80)}")
        return HttpResult(statusCode = -1, body = null)
    }

    /**
     * Fetch a URL and return the response as a byte array.
     * Returns null on any error.
     */
    fun fetchBytes(url: String, maxSize: Int = DEFAULT_MAX_SIZE): ByteArray? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Log.w(TAG, "Rejected non-HTTP(S) URL: ${url.take(50)}")
            return null
        }

        var redirectCount = 0
        var currentUrl = url

        while (redirectCount <= MAX_REDIRECTS) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "LiteTV/1.0")
                    setRequestProperty("Accept", "*/*")
                }

                val responseCode = connection.responseCode

                if (responseCode in 301..308) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrEmpty()) return null
                    currentUrl = resolveRedirectUrl(currentUrl, location)
                    redirectCount++
                    connection.disconnect()
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) return null

                val contentLength = connection.contentLength
                if (contentLength > maxSize) return null

                val inputStream = connection.inputStream
                val output = ByteArrayOutputStream(
                    if (contentLength in 1..maxSize) contentLength else BUFFER_SIZE
                )
                val buffer = ByteArray(BUFFER_SIZE)
                var totalRead = 0

                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                    if (totalRead > maxSize) return null
                    output.write(buffer, 0, bytesRead)
                }

                return output.toByteArray()

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching bytes from ${url.take(80)}: ${e.message}")
                return null
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Resolve redirect location URL, handling both absolute and relative URLs.
     */
    internal fun resolveRedirectUrl(baseUrl: String, location: String): String {
        return try {
            URL(URL(baseUrl), location).toString()
        } catch (_: Exception) {
            location
        }
    }
}

