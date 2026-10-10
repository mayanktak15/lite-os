package com.litetv.app.cache

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Metadata associated with a cached asset (for conditional HTTP headers and integrity checks).
 */
data class CacheMetadata(
    val etag: String? = null,
    val lastModified: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val sha256: String? = null
)

/**
 * Robust file-based disk cache with:
 * 1. Atomic file writes (temp file + fsync + atomic rename) to prevent cache corruption on power loss
 * 2. Last known-good backup retention with rollback capability
 * 3. HTTP caching metadata (ETag, Last-Modified, SHA-256) per item
 * 4. Internal app storage (no external storage permissions required)
 */
class DiskCache(context: Context, baseDirOverride: File? = null) {

    companion object {
        private const val TAG = "DiskCache"
        private const val CACHE_DIR = "litetv_cache"
        private const val TMP_SUFFIX = ".tmp"
        private const val BAK_SUFFIX = ".bak"
        private const val META_SUFFIX = ".meta"
    }

    private val cacheDir: File = (baseDirOverride ?: File(context.filesDir, CACHE_DIR)).also {
        if (!it.exists()) it.mkdirs()
    }

    /**
     * Write data atomically to a cache file.
     * Interrupted writes never corrupt the destination file.
     * If [preserveBackup] is true, the existing file is kept as a known-good backup for rollback.
     */
    fun write(key: String, data: String, preserveBackup: Boolean = true): Boolean {
        val safeKey = sanitizeKey(key)
        val targetFile = File(cacheDir, safeKey)
        val tempFile = File(cacheDir, safeKey + TMP_SUFFIX)
        val backupFile = File(cacheDir, safeKey + BAK_SUFFIX)

        return try {
            // Write to temporary file with flush and sync
            FileOutputStream(tempFile).use { fos ->
                fos.write(data.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (e: Exception) {
                    Log.w(TAG, "fd.sync not supported or failed: ${e.message}")
                }
            }

            // If target exists and backup requested, preserve target as backup
            if (targetFile.exists() && preserveBackup) {
                targetFile.copyTo(backupFile, overwrite = true)
            }

            // Atomic rename
            if (!tempFile.renameTo(targetFile)) {
                // Fallback for filesystems where atomic rename across exists fails
                targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    Log.e(TAG, "Failed to rename temp file to target: $safeKey")
                    tempFile.delete()
                    return false
                }
            }

            Log.d(TAG, "Atomically cached ${data.length} chars as '$key'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cache '$key' atomically", e)
            if (tempFile.exists()) tempFile.delete()
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
     * Roll back the cache entry to the last known-good backup (.bak) if available.
     * Returns true if rollback was successful.
     */
    fun rollback(key: String): Boolean {
        val safeKey = sanitizeKey(key)
        val targetFile = File(cacheDir, safeKey)
        val backupFile = File(cacheDir, safeKey + BAK_SUFFIX)

        return try {
            if (!backupFile.exists()) {
                Log.w(TAG, "No backup found to rollback for '$key'")
                return false
            }
            backupFile.copyTo(targetFile, overwrite = true)
            Log.i(TAG, "Successfully rolled back '$key' to last known-good backup")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rollback failed for '$key'", e)
            false
        }
    }

    /**
     * Read the backup copy directly if needed.
     */
    fun readBackup(key: String): String? {
        return try {
            val backupFile = File(cacheDir, sanitizeKey(key) + BAK_SUFFIX)
            if (!backupFile.exists()) null else backupFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read backup for '$key'", e)
            null
        }
    }

    /**
     * Save HTTP and validation metadata associated with a cache key.
     */
    fun saveMetadata(key: String, metadata: CacheMetadata): Boolean {
        return try {
            val metaFile = File(cacheDir, sanitizeKey(key) + META_SUFFIX)
            val json = JSONObject().apply {
                putOpt("etag", metadata.etag)
                putOpt("lastModified", metadata.lastModified)
                put("timestamp", metadata.timestamp)
                putOpt("sha256", metadata.sha256)
            }
            metaFile.writeText(json.toString(), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata for '$key'", e)
            false
        }
    }

    /**
     * Read HTTP and validation metadata associated with a cache key.
     */
    fun getMetadata(key: String): CacheMetadata? {
        return try {
            val metaFile = File(cacheDir, sanitizeKey(key) + META_SUFFIX)
            if (!metaFile.exists()) return null
            val json = JSONObject(metaFile.readText(Charsets.UTF_8))
            CacheMetadata(
                etag = json.optString("etag").takeIf { it.isNotEmpty() },
                lastModified = json.optString("lastModified").takeIf { it.isNotEmpty() },
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                sha256 = json.optString("sha256").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read metadata for '$key'", e)
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
     * Delete a specific cache entry, including temporary files, backups, and metadata.
     */
    fun delete(key: String): Boolean {
        val safeKey = sanitizeKey(key)
        return try {
            File(cacheDir, safeKey).delete()
            File(cacheDir, safeKey + TMP_SUFFIX).delete()
            File(cacheDir, safeKey + BAK_SUFFIX).delete()
            File(cacheDir, safeKey + META_SUFFIX).delete()
            true
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
     * Helper to compute SHA-256 hash of a string.
     */
    fun computeSha256(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Sanitize cache key to a safe filename.
     */
    private fun sanitizeKey(key: String): String {
        return key.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
    }
}

