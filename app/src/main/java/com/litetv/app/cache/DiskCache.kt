package com.litetv.app.cache

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Simple file-based disk cache for SDUI config and playlist data.
 * Uses the app's internal storage (no external storage permission needed).
 * Each cached item is a separate file in a dedicated cache directory.
 */
class DiskCache(context: Context) {

    companion object {
        private const val TAG = "DiskCache"
        private const val CACHE_DIR = "litetv_cache"
    }

    private val cacheDir: File = File(context.filesDir, CACHE_DIR).also {
        if (!it.exists()) it.mkdirs()
    }

    /**
     * Write data to a cache file.
     */
    fun write(key: String, data: String): Boolean {
        return try {
            val file = File(cacheDir, sanitizeKey(key))
            file.writeText(data, Charsets.UTF_8)
            Log.d(TAG, "Cached ${data.length} chars as '$key'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cache '$key'", e)
            false
        }
    }

    /**
     * Read data from a cache file. Returns null if not found or on error.
     */
    fun read(key: String): String? {
        return try {
            val file = File(cacheDir, sanitizeKey(key))
            if (!file.exists()) return null
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cache '$key'", e)
            null
        }
    }

    /**
     * Check if a cache entry exists.
     */
    fun exists(key: String): Boolean {
        return File(cacheDir, sanitizeKey(key)).exists()
    }

    /**
     * Delete a specific cache entry.
     */
    fun delete(key: String): Boolean {
        return try {
            File(cacheDir, sanitizeKey(key)).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete cache '$key'", e)
            false
        }
    }

    /**
     * Clear all cached data.
     */
    fun clearAll() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }

    /**
     * Sanitize cache key to a safe filename.
     */
    private fun sanitizeKey(key: String): String {
        return key.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
    }
}
