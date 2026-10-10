package com.litetv.app.sdui

import android.content.Context
import android.util.Log
import com.litetv.app.BuildConfig
import com.litetv.app.R
import com.litetv.app.cache.CacheMetadata
import com.litetv.app.cache.DiskCache
import com.litetv.app.network.HttpClient
import com.litetv.app.network.HttpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection

/**
 * Repository that manages SDUI configuration with a waterfall resilience strategy:
 * 1. Return cached config immediately (instant startup)
 * 2. Fetch remote config in background using conditional HTTP (ETag / If-Modified-Since)
 * 3. Validate schema and components → apply & atomically cache if valid
 * 4. On malformed remote data, roll back to last known-good backup or bundled default
 */
class SduiConfigRepository(
    private val context: Context,
    customRemoteUrl: String? = null,
    diskCacheOverride: DiskCache? = null
) {

    private val diskCache = diskCacheOverride ?: DiskCache(context)

    companion object {
        private const val TAG = "SduiConfigRepo"
        const val CONFIG_CACHE_KEY = "sdui_config"
        private const val MAX_CONFIG_SIZE = 1024 * 1024 // 1 MB limit

        /**
         * Safe default remote URL if build-time configuration is absent or blank.
         */
        const val FALLBACK_REMOTE_URL = "https://mayanktak15.github.io/lite-os/config.json"

        /**
         * Resolved default remote URL read from BuildConfig (configured via LITE_TV_REMOTE_CONFIG_URL).
         * Safely falls back to [FALLBACK_REMOTE_URL] if empty, blank, or inaccessible.
         */
        val DEFAULT_REMOTE_URL: String = try {
            BuildConfig.LITE_TV_REMOTE_CONFIG_URL.ifBlank { FALLBACK_REMOTE_URL }
        } catch (e: Throwable) {
            FALLBACK_REMOTE_URL
        }
    }

    /**
     * Active remote configuration URL for this repository instance.
     */
    val remoteConfigUrl: String = customRemoteUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_REMOTE_URL

    /**
     * Diagnostic status of the last configuration synchronization.
     */
    @Volatile
    var lastSyncStatus: String = "Not synced yet"
        private set

    /**
     * Timestamp of the last successful configuration load or sync.
     */
    @Volatile
    var lastSyncTime: Long = 0L
        private set

    /**
     * Get the best available config: cached → backup → bundled default.
     * This is synchronous and called at startup for instant launch.
     */
    fun getCachedOrDefault(): SduiConfig {
        // 1. Try cached config first
        val cached = diskCache.read(CONFIG_CACHE_KEY)
        if (cached != null) {
            val config = SduiConfigParser.parse(cached)
            if (config != null) {
                Log.d(TAG, "Using cached SDUI config")
                return config
            } else {
                Log.w(TAG, "Cached SDUI config failed validation, trying rollback")
                if (diskCache.rollback(CONFIG_CACHE_KEY)) {
                    val rolledBack = diskCache.read(CONFIG_CACHE_KEY)
                    val rolledConfig = rolledBack?.let { SduiConfigParser.parse(it) }
                    if (rolledConfig != null) {
                        Log.i(TAG, "Successfully rolled back to last known-good SDUI config")
                        return rolledConfig
                    }
                }
            }
        }

        // 2. Fall back to bundled default
        Log.d(TAG, "Using bundled default SDUI config")
        return loadBundledDefault()
    }

    /**
     * Fetch remote config conditionally, validate, and cache if valid.
     * Returns the new config, or null if fetch failed or data was unchanged (304).
     */
    suspend fun fetchRemoteConfig(url: String = remoteConfigUrl): SduiConfig? = withContext(Dispatchers.IO) {
        val targetUrl = url.ifBlank { remoteConfigUrl }
        if (targetUrl.isEmpty()) {
            Log.d(TAG, "No remote config URL configured")
            lastSyncStatus = "No remote URL"
            return@withContext null
        }

        try {
            val meta = diskCache.getMetadata(CONFIG_CACHE_KEY)
            val httpResult: HttpResult = HttpClient.fetchStringConditional(
                url = targetUrl,
                maxSize = MAX_CONFIG_SIZE,
                ifNoneMatch = meta?.etag,
                ifModifiedSince = meta?.lastModified
            )

            // Handle HTTP 304 Not Modified
            if (httpResult.isNotModified) {
                Log.d(TAG, "Remote SDUI config not modified (HTTP 304)")
                lastSyncStatus = "Up-to-date (HTTP 304)"
                lastSyncTime = System.currentTimeMillis()
                return@withContext getCachedOrDefault()
            }

            val jsonString = httpResult.body
            if (httpResult.statusCode != HttpURLConnection.HTTP_OK || jsonString == null) {
                Log.w(TAG, "Failed to fetch remote config (HTTP ${httpResult.statusCode})")
                lastSyncStatus = "Fetch failed (HTTP ${httpResult.statusCode})"
                return@withContext null
            }

            // Validate against schema
            val config = SduiConfigParser.parse(jsonString)
            if (config != null) {
                // Atomic write with known-good backup retention
                val written = diskCache.write(CONFIG_CACHE_KEY, jsonString, preserveBackup = true)
                if (written) {
                    val newMeta = CacheMetadata(
                        etag = httpResult.etag,
                        lastModified = httpResult.lastModified,
                        timestamp = System.currentTimeMillis(),
                        sha256 = diskCache.computeSha256(jsonString)
                    )
                    diskCache.saveMetadata(CONFIG_CACHE_KEY, newMeta)
                }
                lastSyncStatus = "Updated (v${config.schemaVersion})"
                lastSyncTime = System.currentTimeMillis()
                Log.i(TAG, "Remote SDUI config fetched, validated, and cached")
                config
            } else {
                Log.w(TAG, "Remote config failed schema validation. Retaining last known-good config.")
                lastSyncStatus = "Rejected (validation failure)"
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching remote config", e)
            lastSyncStatus = "Error: ${e.message}"
            null
        }
    }

    /**
     * Load the bundled default config from res/raw.
     */
    fun loadBundledDefault(): SduiConfig {
        return try {
            val inputStream = context.resources.openRawResource(R.raw.default_config)
            val json = inputStream.bufferedReader().use { it.readText() }
            SduiConfigParser.parse(json) ?: fallbackConfig()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled config", e)
            fallbackConfig()
        }
    }

    /**
     * Absolute last-resort fallback if even the bundled config can't be loaded.
     */
    private fun fallbackConfig() = SduiConfig(
        schemaVersion = 1,
        minAppVersion = "1.0.0",
        theme = ThemeConfig("#0D0D0D", "#242424", "#00BFA5", "#FFFFFF"),
        sections = listOf(
            Section("all", "channel_row", "All Channels", true, 0, null, null)
        ),
        navigation = listOf("home", "settings"),
        playlistSources = listOf(
            PlaylistSource("iptv_org_india", "IPTV-Org India", "https://iptv-org.github.io/iptv/countries/in.m3u")
        ),
        refreshIntervalMinutes = 60,
        featureFlags = emptyMap(),
        searchConfig = SearchConfig(),
        appUpdate = null,
        cacheTtlMinutes = 60
    )
}

