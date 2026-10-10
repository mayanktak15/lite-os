package com.litetv.app.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChannelSearchIndexTest {

    private lateinit var searchIndex: ChannelSearchIndex

    private val channels = listOf(
        Channel("1", "BBC News", "https://logo1.png", "News", "https://s1.m3u8"),
        Channel("2", "Sky Sports Football", "https://logo2.png", "Sports", "https://s2.m3u8"),
        Channel("3", "Star Gold Movies", "https://logo3.png", "Movies", "https://s3.m3u8"),
        Channel("4", "Cartoon Network", "https://logo4.png", "Kids", "https://s4.m3u8"),
        Channel("5", "DD National", "https://logo5.png", "Regional", "https://s5.m3u8")
    )

    @Before
    fun setUp() {
        searchIndex = ChannelSearchIndex()
        searchIndex.update(channels)
    }

    @Test
    fun search_exactMatch_findsChannel() {
        val results = searchIndex.search("BBC News")
        assertEquals(1, results.size)
        assertEquals("BBC News", results[0].name)
    }

    @Test
    fun search_prefixMatch_findsChannel() {
        val results = searchIndex.search("Sky")
        assertEquals(1, results.size)
        assertEquals("Sky Sports Football", results[0].name)
    }

    @Test
    fun search_caseInsensitive_findsChannel() {
        val results = searchIndex.search("cartoon")
        assertEquals(1, results.size)
        assertEquals("Cartoon Network", results[0].name)
    }

    @Test
    fun search_categoryMatch_findsChannelsInGroup() {
        val results = searchIndex.search("Movies")
        assertTrue(results.any { it.name == "Star Gold Movies" })
    }

    @Test
    fun search_emptyQuery_returnsEmpty() {
        val results = searchIndex.search("")
        assertTrue(results.isEmpty())
    }

    @Test
    fun update_replacesIndexCompletely() {
        val updatedChannels = listOf(
            Channel("10", "Al Jazeera English", "https://logo10.png", "News", "https://s10.m3u8")
        )
        searchIndex.update(updatedChannels)

        // Old channels should no longer be in the search index
        val oldResults = searchIndex.search("BBC News")
        assertTrue(oldResults.isEmpty())

        // New channel should be immediately found
        val newResults = searchIndex.search("Al Jazeera")
        assertEquals(1, newResults.size)
        assertEquals("Al Jazeera English", newResults[0].name)
    }

    @Test
    fun normalize_handlesAccentsAndSpecialCharacters() {
        val normalized = ChannelSearchIndex.normalize("Télévision Français")
        assertEquals("television francais", normalized)
    }
}
