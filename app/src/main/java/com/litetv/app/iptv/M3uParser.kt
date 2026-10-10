package com.litetv.app.iptv

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Memory-efficient M3U playlist parser.
 * Reads line-by-line from an InputStream — never loads the full file into a String.
 * Supports #EXTM3U, #EXTINF with tvg-id, tvg-name, tvg-logo, group-title.
 * Skips malformed entries, deduplicates by stream URL, bounded at [Channel.MAX_CHANNELS].
 */
object M3uParser {

    private const val TAG = "M3uParser"
    private const val EXTINF_PREFIX = "#EXTINF:"
    private const val EXTM3U_PREFIX = "#EXTM3U"

    /**
     * Parse an M3U playlist from an InputStream.
     * Returns a list of parsed channels, bounded at MAX_CHANNELS.
     */
    fun parse(inputStream: InputStream): List<Channel> {
        val channels = mutableListOf<Channel>()
        val seenUrls = HashSet<String>(1024)
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 8192)

        var currentExtInf: String? = null
        var lineCount = 0

        try {
            reader.forEachLine { line ->
                lineCount++
                val trimmed = line.trim()

                when {
                    trimmed.isEmpty() || trimmed.startsWith(EXTM3U_PREFIX) -> {
                        // Skip empty lines and M3U header
                    }
                    trimmed.startsWith(EXTINF_PREFIX) -> {
                        currentExtInf = trimmed
                    }
                    trimmed.startsWith("#") -> {
                        // Skip other directives
                    }
                    else -> {
                        // This is a stream URL
                        if (channels.size >= Channel.MAX_CHANNELS) {
                            return@forEachLine
                        }

                        val url = trimmed
                        if (url.isNotEmpty() && url !in seenUrls) {
                            val channel = parseChannel(currentExtInf, url, channels.size)
                            if (channel != null) {
                                channels.add(channel)
                                seenUrls.add(url)
                            }
                        }
                        currentExtInf = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing M3U at line $lineCount", e)
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }

        Log.d(TAG, "Parsed ${channels.size} channels from $lineCount lines")
        return channels
    }

    /**
     * Parse a single channel from an #EXTINF line and stream URL.
     */
    private fun parseChannel(extInf: String?, url: String, index: Int): Channel? {
        if (url.isEmpty()) return null

        // Validate URL scheme
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.w(TAG, "Skipping non-HTTP URL: ${url.take(50)}")
            return null
        }

        if (extInf == null) {
            // No metadata — create with minimal info
            return Channel(
                id = "channel_$index",
                name = "Channel ${index + 1}",
                logoUrl = "",
                group = "",
                streamUrl = url
            )
        }

        val tvgId = extractAttribute(extInf, "tvg-id") ?: "channel_$index"
        val tvgName = extractAttribute(extInf, "tvg-name")
        val tvgLogo = extractAttribute(extInf, "tvg-logo") ?: ""
        val groupTitle = extractAttribute(extInf, "group-title") ?: ""

        // Display name: tvg-name, or the text after the last comma in EXTINF
        val displayName = tvgName ?: extractDisplayName(extInf) ?: "Channel ${index + 1}"

        return Channel(
            id = tvgId,
            name = displayName,
            logoUrl = tvgLogo,
            group = normalizeGroup(groupTitle),
            streamUrl = url
        )
    }

    /**
     * Extract an attribute value from an EXTINF line.
     * Example: tvg-name="Channel Name" → "Channel Name"
     */
    private fun extractAttribute(extInf: String, attribute: String): String? {
        val key = "$attribute=\""
        val startIndex = extInf.indexOf(key)
        if (startIndex == -1) return null

        val valueStart = startIndex + key.length
        val valueEnd = extInf.indexOf('"', valueStart)
        if (valueEnd == -1) return null

        val value = extInf.substring(valueStart, valueEnd).trim()
        return value.ifEmpty { null }
    }

    /**
     * Extract the display name from the EXTINF line (text after last comma).
     * Example: #EXTINF:-1 ...,Channel Name → "Channel Name"
     */
    private fun extractDisplayName(extInf: String): String? {
        val commaIndex = extInf.lastIndexOf(',')
        if (commaIndex == -1 || commaIndex >= extInf.length - 1) return null
        val name = extInf.substring(commaIndex + 1).trim()
        return name.ifEmpty { null }
    }

    /**
     * Normalize group names to standard categories.
     */
    private fun normalizeGroup(group: String): String {
        if (group.isEmpty()) return ""
        val lower = group.lowercase()
        return when {
            lower.contains("news") -> "News"
            lower.contains("sport") -> "Sports"
            lower.contains("movie") || lower.contains("cinema") -> "Movies"
            lower.contains("entertainment") || lower.contains("general") -> "Entertainment"
            lower.contains("kids") || lower.contains("children") -> "Kids"
            lower.contains("music") -> "Music"
            lower.contains("regional") || lower.contains("local") -> "Regional"
            else -> group  // Keep original if no match
        }
    }
}
