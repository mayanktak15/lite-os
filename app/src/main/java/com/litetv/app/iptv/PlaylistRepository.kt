package com.litetv.app.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.litetv.app.cache.DiskCache
import com.litetv.app.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Repository managing playlist fetching, parsing, and caching.
 * Implements the following strategy:
 * 1. Return cached channels if available (instant display)
 * 2. Fetch remote playlist in background
 * 3. Parse and cache on success
 * 4. Fall back to cached data on failure
 */
class PlaylistRepository(private val context: Context) {

    private val diskCache = DiskCache(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("playlist_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "PlaylistRepo"
        private const val PLAYLIST_CACHE_KEY = "cached_playlist"
        private const val PREF_ACTIVE_SOURCE = "active_source_url"
        private const val PREF_LAST_REFRESH = "last_refresh_time"
        private const val MAX_PLAYLIST_SIZE = 10 * 1024 * 1024 // 10 MB limit
        private const val DEFAULT_SOURCE_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"
    }

    /** In-memory channel list — the single source of truth */
    @Volatile
    var channels: List<Channel> = emptyList()
        private set

    /** Get channels grouped by category */
    val channelsByCategory: Map<String, List<Channel>>
        get() = channels.filter { it.group.isNotEmpty() }.groupBy { it.group }

    /** Get the currently active source URL */
    var activeSourceUrl: String
        get() = prefs.getString(PREF_ACTIVE_SOURCE, DEFAULT_SOURCE_URL) ?: DEFAULT_SOURCE_URL
        set(value) = prefs.edit().putString(PREF_ACTIVE_SOURCE, value).apply()

    /** Get the last refresh timestamp */
    val lastRefreshTime: Long
        get() = prefs.getLong(PREF_LAST_REFRESH, 0L)

    /**
     * Load channels — first from cache, then attempt remote refresh.
     * Returns true if channels were loaded from any source.
     */
    suspend fun loadChannels(): Boolean = withContext(Dispatchers.IO) {
        // Try cache first
        val cached = loadFromCache()
        if (cached) {
            Log.d(TAG, "Loaded ${channels.size} channels from cache")
        }

        // Try remote fetch
        val remote = fetchRemote(activeSourceUrl)
        if (remote) {
            Log.d(TAG, "Loaded ${channels.size} channels from remote")
            return@withContext true
        }

        return@withContext cached
    }

    /**
     * Force refresh from the active source.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        fetchRemote(activeSourceUrl)
    }

    /**
     * Switch to a different playlist source and refresh.
     */
    suspend fun switchSource(url: String): Boolean = withContext(Dispatchers.IO) {
        activeSourceUrl = url
        fetchRemote(url)
    }

    /**
     * Load channels from disk cache.
     */
    private fun loadFromCache(): Boolean {
        return try {
            val cached = diskCache.read(PLAYLIST_CACHE_KEY) ?: return false
            val inputStream = ByteArrayInputStream(cached.toByteArray(Charsets.UTF_8))
            val parsed = M3uParser.parse(inputStream)
            if (parsed.isNotEmpty()) {
                channels = parsed
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached playlist", e)
            false
        }
    }

    /**
     * Fetch and parse a remote playlist.
     */
    private fun fetchRemote(url: String): Boolean {
        return try {
            val data = HttpClient.fetchString(url, maxSize = MAX_PLAYLIST_SIZE) ?: return false
            val inputStream = ByteArrayInputStream(data.toByteArray(Charsets.UTF_8))
            val parsed = M3uParser.parse(inputStream)

            if (parsed.isNotEmpty()) {
                channels = parsed
                // Cache the raw playlist data
                diskCache.write(PLAYLIST_CACHE_KEY, data)
                prefs.edit().putLong(PREF_LAST_REFRESH, System.currentTimeMillis()).apply()
                true
            } else {
                Log.w(TAG, "Remote playlist parsed 0 channels")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch remote playlist", e)
            false
        }
    }
}
