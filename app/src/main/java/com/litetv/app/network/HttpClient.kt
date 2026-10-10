package com.litetv.app.network

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Lightweight HTTP client using built-in HttpURLConnection.
 * No OkHttp, no Retrofit — zero extra dependencies.
 *
 * Features:
 * - HTTPS-only by default
 * - Bounded download sizes
 * - Configurable timeouts
 * - Follows redirects (max 3)
 * - Returns null on error (no exceptions thrown to callers)
 */
object HttpClient {

    private const val TAG = "HttpClient"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 3
    private const val DEFAULT_MAX_SIZE = 10 * 1024 * 1024  // 10 MB
    private const val BUFFER_SIZE = 8192

    /**
     * Fetch a URL and return the response as a String.
     * Returns null on any error.
     *
     * @param url HTTPS URL to fetch
     * @param maxSize Maximum response size in bytes
     */
    fun fetchString(url: String, maxSize: Int = DEFAULT_MAX_SIZE): String? {
        val bytes = fetchBytes(url, maxSize) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Fetch a URL and return the response as a byte array.
     * Returns null on any error.
     */
    fun fetchBytes(url: String, maxSize: Int = DEFAULT_MAX_SIZE): ByteArray? {
        // Security: only allow HTTPS
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

                // Handle redirects manually to track count
                if (responseCode in 301..308) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrEmpty()) {
                        Log.w(TAG, "Redirect without Location header")
                        return null
                    }
                    currentUrl = location
                    redirectCount++
                    connection.disconnect()
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "HTTP $responseCode for ${url.take(80)}")
                    return null
                }

                // Read response with size limit
                val contentLength = connection.contentLength
                if (contentLength > maxSize) {
                    Log.w(TAG, "Response too large: $contentLength bytes (max $maxSize)")
                    return null
                }

                val inputStream = connection.inputStream
                val output = ByteArrayOutputStream(
                    if (contentLength > 0) contentLength.coerceAtMost(maxSize) else BUFFER_SIZE
                )
                val buffer = ByteArray(BUFFER_SIZE)
                var totalRead = 0

                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                    if (totalRead > maxSize) {
                        Log.w(TAG, "Response exceeded max size: $maxSize bytes")
                        return null
                    }
                    output.write(buffer, 0, bytesRead)
                }

                return output.toByteArray()

            } catch (e: IOException) {
                Log.e(TAG, "Network error fetching ${url.take(80)}: ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching ${url.take(80)}", e)
                return null
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }

        Log.w(TAG, "Too many redirects for ${url.take(80)}")
        return null
    }
}
