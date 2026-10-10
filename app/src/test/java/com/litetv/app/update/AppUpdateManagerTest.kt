package com.litetv.app.update

import android.content.ContextWrapper
import com.litetv.app.sdui.AppUpdateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdateManagerTest {

    private lateinit var updateManager: AppUpdateManager

    @Before
    fun setUp() {
        updateManager = AppUpdateManager(ContextWrapper(null))
    }

    @Test
    fun checkUpdate_whenConfigNull_returnsUpToDate() {
        val status = updateManager.checkUpdate(null)
        assertTrue(status is AppUpdateStatus.UpToDate)
    }

    @Test
    fun checkUpdate_whenHigherVersionAvailable_returnsUpdateAvailable() {
        val config = AppUpdateConfig(
            latestVersionCode = updateManager.currentVersionCode + 1,
            latestVersionName = "2.0.0",
            minRequiredVersionCode = 1,
            updateUrl = "https://example.com/release",
            releaseNotes = "Major updates"
        )

        val status = updateManager.checkUpdate(config)
        assertTrue(status is AppUpdateStatus.UpdateAvailable)
        val update = status as AppUpdateStatus.UpdateAvailable
        assertFalse(update.isMandatory)
        assertEquals("2.0.0", update.config.latestVersionName)
    }

    @Test
    fun checkUpdate_whenBelowMinRequired_returnsMandatoryUpdate() {
        val config = AppUpdateConfig(
            latestVersionCode = updateManager.currentVersionCode + 5,
            latestVersionName = "3.0.0",
            minRequiredVersionCode = updateManager.currentVersionCode + 1, // Requires higher version
            updateUrl = "https://example.com/release",
            releaseNotes = "Security fix"
        )

        val status = updateManager.checkUpdate(config)
        assertTrue(status is AppUpdateStatus.UpdateAvailable)
        val update = status as AppUpdateStatus.UpdateAvailable
        assertTrue(update.isMandatory)
    }

    @Test
    fun checkUpdate_whenForceUpdateTrue_returnsMandatoryUpdate() {
        val config = AppUpdateConfig(
            latestVersionCode = updateManager.currentVersionCode + 1,
            latestVersionName = "1.0.1",
            minRequiredVersionCode = 1,
            updateUrl = "https://example.com/release",
            forceUpdate = true
        )

        val status = updateManager.checkUpdate(config)
        assertTrue(status is AppUpdateStatus.UpdateAvailable)
        val update = status as AppUpdateStatus.UpdateAvailable
        assertTrue(update.isMandatory)
    }

    @Test
    fun checkUpdate_whenCurrentVersionHigherOrEqual_returnsUpToDate() {
        val config = AppUpdateConfig(
            latestVersionCode = updateManager.currentVersionCode,
            latestVersionName = updateManager.currentVersionName,
            minRequiredVersionCode = 1,
            updateUrl = "https://example.com/release"
        )

        val status = updateManager.checkUpdate(config)
        assertTrue(status is AppUpdateStatus.UpToDate)
    }
}
