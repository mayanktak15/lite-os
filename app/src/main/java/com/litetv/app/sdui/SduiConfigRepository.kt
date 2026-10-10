package com.litetv.app.sdui

import android.content.Context
import android.util.Log
import com.litetv.app.R
import com.litetv.app.cache.DiskCache
import com.litetv.app.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository that manages SDUI configuration with a waterfall strategy:
 * 1. Return cached config immediately (instant launch)
 * 2. Fetch remote config in background
 * 3. Validate → apply & cache if valid
 * 4. Fall back to bundled default if no cache exists
 */
class SduiConfigRepository(private val context: Context) {

    private val diskCache = DiskCache(context)

    companion object {
        private const val TAG = "SduiConfigRepo"
        private const val CONFIG_CACHE_KEY = "sdui_config"
        private const val MAX_CONFIG_SIZE = 1024 * 1024 // 1 MB limit
        // Default remote URL — can be overridden via config
        private const val DEFAULT_REMOTE_URL = ""
    }

    /**
     * Get the best available config: cached → bundled default.
     * This is synchronous and called at startup for instant launch.
     */
    fun getCachedOrDefault(): SduiConfig {
        // Try cached config first
        val cached = diskCache.read(CONFIG_CACHE_KEY)
        if (cached != null) {
            val config = SduiConfigParser.parse(cached)
            if (config != null) {
                Log.d(TAG, "Using cached SDUI config")
                return config
            }
        }

        // Fall back to bundled default
        Log.d(TAG, "Using bundled default SDUI config")
        return loadBundledDefault()
    }

    /**
     * Fetch remote config, validate, and cache if valid.
     * Returns the new config or null if fetch/validation failed.
     */
    suspend fun fetchRemoteConfig(url: String): SduiConfig? = withContext(Dispatchers.IO) {
        if (url.isEmpty()) {
            Log.d(TAG, "No remote config URL configured")
            return@withContext null
        }

        try {
            val jsonString = HttpClient.fetchString(url, maxSize = MAX_CONFIG_SIZE)
            if (jsonString == null) {
                Log.w(TAG, "Failed to fetch remote config")
                return@withContext null
            }

            val config = SduiConfigParser.parse(jsonString)
            if (config != null) {
                diskCache.write(CONFIG_CACHE_KEY, jsonString)
                Log.d(TAG, "Remote SDUI config fetched and cached")
            } else {
                Log.w(TAG, "Remote config failed validation")
            }
            config
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching remote config", e)
            null
        }
    }

    /**
     * Load the bundled default config from res/raw.
     */
    private fun loadBundledDefault(): SduiConfig {
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
        refreshIntervalMinutes = 60
    )
}
