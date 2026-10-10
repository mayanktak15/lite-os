package com.litetv.app.iptv

import android.content.ContextWrapper
import com.litetv.app.cache.DiskCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaylistRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var diskCache: DiskCache
    private lateinit var playlistRepository: PlaylistRepository

    private val sampleM3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="AajTak" tvg-name="Aaj Tak" tvg-logo="https://example.com/aajtak.png" group-title="News",Aaj Tak
        https://example.com/stream/aajtak.m3u8
        #EXTINF:-1 tvg-id="StarSports1" tvg-name="Star Sports 1" tvg-logo="https://example.com/starsports.png" group-title="Sports",Star Sports 1
        https://example.com/stream/starsports.m3u8
    """.trimIndent()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("playlist_repo_test").toFile()
        diskCache = DiskCache(ContextWrapper(null), tempDir)
        playlistRepository = PlaylistRepository(ContextWrapper(null), diskCacheOverride = diskCache)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun loadFromCache_whenCacheHasData_loadsChannelsAndPopulatesSearchIndex() {
        // Pre-populate disk cache (simulating prior successful sync)
        diskCache.write(PlaylistRepository.PLAYLIST_CACHE_KEY, sampleM3u)

        val loaded = playlistRepository.loadFromCache()
        assertTrue(loaded)
        assertEquals(2, playlistRepository.channels.size)
        assertEquals("Aaj Tak", playlistRepository.channels[0].name)
        assertEquals("Star Sports 1", playlistRepository.channels[1].name)

        // Verify Acceptance Criterion 4: Search index is populated and reflects loaded catalogue
        val searchResults = playlistRepository.searchIndex.search("Aaj")
        assertEquals(1, searchResults.size)
        assertEquals("Aaj Tak", searchResults[0].name)

        val sportsResults = playlistRepository.searchIndex.search("Sports")
        assertTrue(sportsResults.any { it.name == "Star Sports 1" })
    }

    @Test
    fun loadFromCache_whenCacheEmpty_returnsFalseAndLeavesChannelsEmpty() {
        val loaded = playlistRepository.loadFromCache()
        assertFalse(loaded)
        assertTrue(playlistRepository.channels.isEmpty())
        assertEquals(0, playlistRepository.searchIndex.size)
    }

    @Test
    fun catalogueListeners_areInvokedOnCatalogueUpdate() {
        val listener: (List<Channel>, CatalogueDiff) -> Unit = { channels, _ ->
            assertEquals(2, channels.size)
        }

        playlistRepository.addListener(listener)
        playlistRepository.removeListener(listener)
    }

    @Test
    fun channelsByCategory_groupsCorrectly() {
        diskCache.write(PlaylistRepository.PLAYLIST_CACHE_KEY, sampleM3u)
        playlistRepository.loadFromCache()

        val grouped = playlistRepository.channelsByCategory
        assertTrue(grouped.containsKey("News"))
        assertTrue(grouped.containsKey("Sports"))
        assertEquals(1, grouped["News"]?.size)
        assertEquals(1, grouped["Sports"]?.size)
    }
}
