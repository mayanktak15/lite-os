package com.litetv.app.sdui

import android.content.ContextWrapper
import com.litetv.app.BuildConfig
import com.litetv.app.cache.DiskCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SduiConfigRepositoryTest {

    @Test
    fun defaultRemoteUrl_matchesExpectedDefaultOrBuildConfig() {
        assertEquals("https://mayanktak15.github.io/lite-os/config.json", SduiConfigRepository.FALLBACK_REMOTE_URL)
        assertEquals(BuildConfig.LITE_TV_REMOTE_CONFIG_URL, SduiConfigRepository.DEFAULT_REMOTE_URL)
    }

    @Test
    fun remoteConfigUrl_usesDefaultWhenNullOrBlank() {
        val dummyContext = ContextWrapper(null)
        val repoDefault = SduiConfigRepository(dummyContext)
        assertEquals(SduiConfigRepository.DEFAULT_REMOTE_URL, repoDefault.remoteConfigUrl)

        val repoEmpty = SduiConfigRepository(dummyContext, "")
        assertEquals(SduiConfigRepository.DEFAULT_REMOTE_URL, repoEmpty.remoteConfigUrl)

        val repoBlank = SduiConfigRepository(dummyContext, "   ")
        assertEquals(SduiConfigRepository.DEFAULT_REMOTE_URL, repoBlank.remoteConfigUrl)
    }

    @Test
    fun remoteConfigUrl_usesCustomUrlWhenProvided() {
        val dummyContext = ContextWrapper(null)
        val customUrl = "https://custom.example.com/tv/config.json"
        val repo = SduiConfigRepository(dummyContext, customUrl)
        assertEquals(customUrl, repo.remoteConfigUrl)
    }

    @Test
    fun getCachedOrDefault_readsValidCachedConfig() {
        val tempDir = Files.createTempDirectory("sdui_repo_test").toFile()
        try {
            val dummyContext = ContextWrapper(null)
            val diskCache = DiskCache(dummyContext, tempDir)
            val validConfigJson = """
                {
                  "schemaVersion": 1,
                  "minAppVersion": "1.0.0",
                  "theme": { "backgroundColor": "#000", "cardColor": "#111", "accentColor": "#0F0", "textColor": "#FFF" },
                  "sections": [],
                  "navigation": ["home"],
                  "playlistSources": [],
                  "refreshIntervalMinutes": 60
                }
            """.trimIndent()
            diskCache.write(SduiConfigRepository.CONFIG_CACHE_KEY, validConfigJson)

            val repo = SduiConfigRepository(dummyContext, diskCacheOverride = diskCache)
            val config = repo.getCachedOrDefault()

            assertNotNull(config)
            assertEquals(1, config.schemaVersion)
            assertEquals("#000", config.theme.backgroundColor)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun getCachedOrDefault_whenCorrupted_rollsBackToBackup() {
        val tempDir = Files.createTempDirectory("sdui_repo_rollback_test").toFile()
        try {
            val dummyContext = ContextWrapper(null)
            val diskCache = DiskCache(dummyContext, tempDir)
            val goodConfigJson = """
                {
                  "schemaVersion": 1,
                  "minAppVersion": "1.0.0",
                  "theme": { "backgroundColor": "#123", "cardColor": "#234", "accentColor": "#345", "textColor": "#FFF" },
                  "sections": [],
                  "navigation": ["home"],
                  "playlistSources": [],
                  "refreshIntervalMinutes": 60
                }
            """.trimIndent()
            // Write good version first (this will become the backup)
            diskCache.write(SduiConfigRepository.CONFIG_CACHE_KEY, goodConfigJson, preserveBackup = true)

            // Overwrite with corrupted JSON
            diskCache.write(SduiConfigRepository.CONFIG_CACHE_KEY, "{ corrupted_data }", preserveBackup = true)

            val repo = SduiConfigRepository(dummyContext, diskCacheOverride = diskCache)
            val config = repo.getCachedOrDefault()

            assertNotNull(config)
            // Rollback should have restored the good version
            assertEquals("#123", config.theme.backgroundColor)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

