package com.litetv.app.iptv

import java.text.Normalizer
import java.util.Locale

/**
 * Fast, memory-conscious in-memory search index for TV channels.
 *
 * Designed for low-resource Android TV hardware (1 GB RAM):
 * - References existing [Channel] instances directly (zero data duplication)
 * - Thread-safe atomic swaps on catalogue updates
 * - Normalizes accents, casing, and special characters
 * - Multi-token prefix and substring matching with relevance scoring
 */
class ChannelSearchIndex {

    private data class IndexEntry(
        val channel: Channel,
        val normalizedName: String,
        val normalizedGroup: String,
        val nameTokens: List<String>
    )

    @Volatile
    private var entries: List<IndexEntry> = emptyList()

    /**
     * Rebuild the search index from the current channel catalogue.
     * This operation is non-blocking to active queries via an atomic reference swap.
     */
    fun update(channels: List<Channel>) {
        val newEntries = ArrayList<IndexEntry>(channels.size)
        for (channel in channels) {
            val normName = normalize(channel.name)
            val normGroup = normalize(channel.group)
            val tokens = normName.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
            newEntries.add(
                IndexEntry(
                    channel = channel,
                    normalizedName = normName,
                    normalizedGroup = normGroup,
                    nameTokens = tokens
                )
            )
        }
        entries = newEntries
    }

    /**
     * Search the channel index for the given query string.
     *
     * Matches against channel names, groups, and tokens.
     * Returns results sorted by relevance:
     * 1. Exact name match
     * 2. Name starts with query
     * 3. Word token starts with query
     * 4. Name contains query
     * 5. Group/category contains query
     *
     * @param query User search input
     * @param limit Maximum results to return (capped to preserve memory)
     */
    fun search(query: String, limit: Int = 50): List<Channel> {
        val cleanQuery = normalize(query.trim())
        if (cleanQuery.isEmpty()) {
            return emptyList()
        }

        val queryTokens = cleanQuery.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val currentEntries = entries

        val scoredResults = ArrayList<Pair<Channel, Int>>()

        for (entry in currentEntries) {
            var score = 0

            // 1. Exact match on full name
            if (entry.normalizedName == cleanQuery) {
                score += 1000
            }
            // 2. Full name starts with query
            else if (entry.normalizedName.startsWith(cleanQuery)) {
                score += 500
            }
            // 3. Full name contains full query phrase
            else if (entry.normalizedName.contains(cleanQuery)) {
                score += 200
            }

            // 4. Token-level matching
            var allQueryTokensMatched = true
            for (qToken in queryTokens) {
                val tokenMatched = entry.nameTokens.any { it.startsWith(qToken) || it.contains(qToken) }
                if (tokenMatched) {
                    score += 50
                } else if (entry.normalizedGroup.contains(qToken)) {
                    score += 20
                } else {
                    allQueryTokensMatched = false
                }
            }

            if (score > 0 && (allQueryTokensMatched || score >= 200)) {
                scoredResults.add(Pair(entry.channel, score))
            }
        }

        scoredResults.sortByDescending { it.second }

        val resultSize = scoredResults.size.coerceAtMost(limit)
        val results = ArrayList<Channel>(resultSize)
        for (i in 0 until resultSize) {
            results.add(scoredResults[i].first)
        }
        return results
    }

    /**
     * Total indexed channel count.
     */
    val size: Int
        get() = entries.size

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Normalize string: lowercases, decomposes diacritics, and strips non-alphanumeric marks.
         */
        fun normalize(text: String): String {
            if (text.isEmpty()) return ""
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
            return decomposed
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .lowercase(Locale.ROOT)
        }
    }
}
