package com.litetv.app.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.litetv.app.cache.CacheMetadata
import com.litetv.app.cache.DiskCache
import com.litetv.app.network.HttpClient
import com.litetv.app.network.HttpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection

/**
 * Result of a catalogue synchronization event.
 */
data class CatalogueSyncResult(
    val success: Boolean,
    val isNotModified: Boolean = false,
    val diff: CatalogueDiff = CatalogueDiff(),
    val channelCount: Int = 0,
    val fromCache: Boolean = false,
    val message: String = ""
)

/**
 * Repository managing IPTV playlist fetching, parsing, caching, and diff synchronization.
 *
 * Architecture highlights:
 * 1. Two-tier storage: In-memory channel catalogue + atomic disk cache
 * 2. Incremental conditional requests: Uses ETag and If-Modified-Since to eliminate unnecessary payload transfers
 * 3. Atomic catalogue replacement: Parsing or network failures NEVER overwrite working catalogue
 * 4. Automatic diff detection: Tracks added, removed, renamed, and recategorized channels
 * 5. Synchronized search indexing: Updates [ChannelSearchIndex] on every catalogue modification
 * 6. Offline resilience: Retains and falls back to last known-good cached catalogue
 */
class PlaylistRepository(
    private val context: Context,
    diskCacheOverride: DiskCache? = null,
    prefsOverride: SharedPreferences? = null
) {

    private val diskCache = diskCacheOverride ?: DiskCache(context)
    private val prefs: SharedPreferences? = prefsOverride ?: try {
        context.getSharedPreferences("playlist_prefs", Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }

    private var inMemorySourceUrl: String = DEFAULT_SOURCE_URL
    private var inMemoryRefreshTime: Long = 0L
    private var inMemoryDiffSummary: String = "No updates yet"

    companion object {
        private const val TAG = "PlaylistRepo"
        const val PLAYLIST_CACHE_KEY = "cached_playlist"
        private const val PREF_ACTIVE_SOURCE = "active_source_url"
        private const val PREF_LAST_REFRESH = "last_refresh_time"
        private const val PREF_LAST_DIFF_SUMMARY = "last_diff_summary"
        private const val MAX_PLAYLIST_SIZE = 10 * 1024 * 1024 // 10 MB limit
        const val DEFAULT_SOURCE_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"
    }

    /** In-memory channel list — the single source of truth */
    @Volatile
    var channels: List<Channel> = emptyList()
        private set

    /** Fast in-memory search index kept strictly in sync with [channels] */
    val searchIndex = ChannelSearchIndex()

    /** Last computed diff from the most recent sync */
    @Volatile
    var lastDiff: CatalogueDiff = CatalogueDiff()
        private set

    /** Last sync result details */
    @Volatile
    var lastSyncResult: CatalogueSyncResult? = null
        private set

    /** Listeners notified when the catalogue updates */
    private val listeners = mutableListOf<(List<Channel>, CatalogueDiff) -> Unit>()

    /** Get channels grouped by category */
    val channelsByCategory: Map<String, List<Channel>>
        get() = channels.filter { it.group.isNotEmpty() }.groupBy { it.group }

    /** Get the currently active source URL */
    var activeSourceUrl: String
        get() = prefs?.getString(PREF_ACTIVE_SOURCE, inMemorySourceUrl) ?: inMemorySourceUrl
        set(value) {
            inMemorySourceUrl = value
            prefs?.edit()?.putString(PREF_ACTIVE_SOURCE, value)?.apply()
        }

    /** Get the last refresh timestamp */
    val lastRefreshTime: Long
        get() = prefs?.getLong(PREF_LAST_REFRESH, inMemoryRefreshTime) ?: inMemoryRefreshTime

    /** Get summary of the last diff */
    val lastDiffSummary: String
        get() = prefs?.getString(PREF_LAST_DIFF_SUMMARY, inMemoryDiffSummary) ?: inMemoryDiffSummary

    /**
     * Add a catalogue change observer.
     */
    fun addListener(listener: (List<Channel>, CatalogueDiff) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove a catalogue change observer.
     */
    fun removeListener(listener: (List<Channel>, CatalogueDiff) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Load channels on app launch:
     * 1. Immediately loads cached channels (instant startup)
     * 2. Issues conditional remote request to fetch updates if any
     * Returns true if channels are available from cache or remote.
     */
    suspend fun loadChannels(): Boolean = withContext(Dispatchers.IO) {
        val cached = loadFromCache()
        if (cached) {
            Log.d(TAG, "Loaded ${channels.size} channels from disk cache")
        }

        val syncResult = syncRemote(activeSourceUrl, force = false)
        lastSyncResult = syncResult

        if (syncResult.success) {
            Log.d(TAG, "Synced catalogue: ${channels.size} channels (${syncResult.message})")
            return@withContext true
        }

        // Return true if cache provided usable channels even if remote sync failed
        return@withContext cached
    }

    /**
     * Refresh the active playlist source.
     * Backwards-compatible signature returning Boolean.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        syncCatalogue(force = true).success
    }

    /**
     * Perform a full catalogue sync and return rich diagnostic results.
     */
    suspend fun syncCatalogue(force: Boolean = false): CatalogueSyncResult = withContext(Dispatchers.IO) {
        val result = syncRemote(activeSourceUrl, force = force)
        lastSyncResult = result
        result
    }

    /**
     * Switch to a different playlist source and sync.
     */
    suspend fun switchSource(url: String): Boolean = withContext(Dispatchers.IO) {
        activeSourceUrl = url
        // Reset cached metadata for new source to force clean download
        diskCache.delete(PLAYLIST_CACHE_KEY)
        val result = syncRemote(url, force = true)
        lastSyncResult = result
        result.success
    }

    /**
     * Load channels from disk cache and initialize search index.
     */
    fun loadFromCache(): Boolean {
        return try {
            val cached = diskCache.read(PLAYLIST_CACHE_KEY) ?: return false
            val inputStream = ByteArrayInputStream(cached.toByteArray(Charsets.UTF_8))
            val parsed = M3uParser.parse(inputStream)
            if (parsed.isNotEmpty()) {
                channels = parsed
                searchIndex.update(parsed)
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
     * Synchronize playlist from remote source with conditional HTTP,
     * atomic caching, diff computation, and safe fallback.
     */
    private fun syncRemote(url: String, force: Boolean): CatalogueSyncResult {
        return try {
            val meta = if (!force) diskCache.getMetadata(PLAYLIST_CACHE_KEY) else null
            val ifNoneMatch = meta?.etag
            val ifModifiedSince = meta?.lastModified

            val httpResult: HttpResult = HttpClient.fetchStringConditional(
                url = url,
                maxSize = MAX_PLAYLIST_SIZE,
                ifNoneMatch = ifNoneMatch,
                ifModifiedSince = ifModifiedSince
            )

            // 1. Handle HTTP 304 Not Modified
            if (httpResult.isNotModified) {
                Log.d(TAG, "Remote playlist not modified (HTTP 304)")
                inMemoryRefreshTime = System.currentTimeMillis()
                prefs?.edit()?.putLong(PREF_LAST_REFRESH, inMemoryRefreshTime)?.apply()
                return CatalogueSyncResult(
                    success = true,
                    isNotModified = true,
                    channelCount = channels.size,
                    message = "Catalogue up-to-date (HTTP 304)"
                )
            }

            // 2. Check for successful body download
            val data = httpResult.body
            if (httpResult.statusCode != HttpURLConnection.HTTP_OK || data.isNullOrEmpty()) {
                Log.w(TAG, "Remote fetch failed with status ${httpResult.statusCode}")
                return CatalogueSyncResult(
                    success = channels.isNotEmpty(),
                    fromCache = channels.isNotEmpty(),
                    channelCount = channels.size,
                    message = "Network error (HTTP ${httpResult.statusCode})"
                )
            }

            // 3. Parse channels atomically
            val inputStream = ByteArrayInputStream(data.toByteArray(Charsets.UTF_8))
            val parsedChannels = M3uParser.parse(inputStream)

            if (parsedChannels.isEmpty()) {
                Log.w(TAG, "Parsed 0 valid channels from remote. Preserving existing working catalogue.")
                return CatalogueSyncResult(
                    success = false,
                    channelCount = channels.size,
                    message = "Remote source yielded 0 channels (rejected)"
                )
            }

            // 4. Compute granular catalogue diff
            val diff = CatalogueDiff.compute(channels, parsedChannels)
            lastDiff = diff

            // 5. Atomic write to disk cache (retains .bak if previous exists)
            val cacheWritten = diskCache.write(PLAYLIST_CACHE_KEY, data, preserveBackup = true)
            if (cacheWritten) {
                val newMeta = CacheMetadata(
                    etag = httpResult.etag,
                    lastModified = httpResult.lastModified,
                    timestamp = System.currentTimeMillis(),
                    sha256 = diskCache.computeSha256(data)
                )
                diskCache.saveMetadata(PLAYLIST_CACHE_KEY, newMeta)
            }

            // 6. Update in-memory state and search index
            channels = parsedChannels
            searchIndex.update(parsedChannels)

            inMemoryRefreshTime = System.currentTimeMillis()
            inMemoryDiffSummary = diff.summary
            prefs?.edit()
                ?.putLong(PREF_LAST_REFRESH, inMemoryRefreshTime)
                ?.putString(PREF_LAST_DIFF_SUMMARY, inMemoryDiffSummary)
                ?.apply()

            // 7. Notify catalogue change listeners
            val notifyList = synchronized(listeners) { listeners.toList() }
            for (listener in notifyList) {
                try {
                    listener(parsedChannels, diff)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in catalogue listener", e)
                }
            }

            Log.i(TAG, "Successfully synced ${parsedChannels.size} channels. Diff: ${diff.summary}")
            CatalogueSyncResult(
                success = true,
                isNotModified = false,
                diff = diff,
                channelCount = parsedChannels.size,
                message = "Catalogue updated (${diff.summary})"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Catalogue sync error for $url", e)
            CatalogueSyncResult(
                success = channels.isNotEmpty(),
                fromCache = channels.isNotEmpty(),
                channelCount = channels.size,
                message = "Sync failed: ${e.message}"
            )
        }
    }
}

