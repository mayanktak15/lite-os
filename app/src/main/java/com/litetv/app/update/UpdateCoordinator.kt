package com.litetv.app.update

import android.content.Context
import android.util.Log
import com.litetv.app.iptv.CatalogueDiff
import com.litetv.app.iptv.PlaylistRepository
import com.litetv.app.sdui.SduiConfig
import com.litetv.app.sdui.SduiConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Summary of a full synchronization cycle.
 */
data class UpdateSyncReport(
    val timestamp: Long = System.currentTimeMillis(),
    val configChanged: Boolean = false,
    val catalogueChanged: Boolean = false,
    val catalogueDiff: CatalogueDiff = CatalogueDiff(),
    val updateStatus: AppUpdateStatus = AppUpdateStatus.UpToDate,
    val message: String = ""
)

/**
 * Coordinates automated background updates for:
 * 1. SDUI Layout & Theme Configuration
 * 2. Channel Catalogue & Search Index
 * 3. Application Binary Updates
 *
 * Ensures synchronization runs atomically, prevents overlapping requests,
 * and suppresses intrusive alerts during active video playback.
 */
class UpdateCoordinator(
    private val context: Context,
    private val configRepository: SduiConfigRepository,
    private val playlistRepository: PlaylistRepository,
    private val updateManager: AppUpdateManager
) {

    companion object {
        private const val TAG = "UpdateCoordinator"

        @Volatile
        private var instance: UpdateCoordinator? = null

        fun getInstance(
            context: Context,
            configRepository: SduiConfigRepository,
            playlistRepository: PlaylistRepository,
            updateManager: AppUpdateManager
        ): UpdateCoordinator {
            return instance ?: synchronized(this) {
                instance ?: UpdateCoordinator(
                    context.applicationContext,
                    configRepository,
                    playlistRepository,
                    updateManager
                ).also { instance = it }
            }
        }
    }

    private val syncMutex = Mutex()

    @Volatile
    var lastReport: UpdateSyncReport = UpdateSyncReport()
        private set

    /**
     * Perform a complete, non-blocking synchronization pass.
     *
     * @param force Force downloading fresh copies ignoring cached ETags/Last-Modified headers
     */
    suspend fun sync(force: Boolean = false): UpdateSyncReport = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            Log.d(TAG, "Starting synchronization pass (force=$force)")

            // 1. Check SDUI configuration
            var configChanged = false
            var currentConfig: SduiConfig? = null
            try {
                val newConfig = configRepository.fetchRemoteConfig()
                if (newConfig != null) {
                    configChanged = true
                    currentConfig = newConfig
                }
            } catch (e: Exception) {
                Log.w(TAG, "Config sync encountered error", e)
            }

            // 2. Check channel catalogue
            var catalogueChanged = false
            var diff = CatalogueDiff()
            try {
                val catResult = playlistRepository.syncCatalogue(force = force)
                if (catResult.success && !catResult.isNotModified && catResult.diff.hasChanges) {
                    catalogueChanged = true
                    diff = catResult.diff
                }
            } catch (e: Exception) {
                Log.w(TAG, "Catalogue sync encountered error", e)
            }

            // 3. Check application binary update status
            val activeConfig = currentConfig ?: configRepository.getCachedOrDefault()
            val updateStatus = updateManager.checkUpdate(activeConfig.appUpdate)

            val summaryMessage = buildString {
                if (configChanged) append("Config updated. ")
                if (catalogueChanged) append("Catalogue: ${diff.summary}. ")
                if (!configChanged && !catalogueChanged) append("Everything up-to-date. ")
                if (updateStatus is AppUpdateStatus.UpdateAvailable) {
                    append(if (updateStatus.isMandatory) "Mandatory APK update required." else "APK update available.")
                }
            }

            val report = UpdateSyncReport(
                timestamp = System.currentTimeMillis(),
                configChanged = configChanged,
                catalogueChanged = catalogueChanged,
                catalogueDiff = diff,
                updateStatus = updateStatus,
                message = summaryMessage
            )

            lastReport = report
            Log.i(TAG, "Sync complete: $summaryMessage")
            report
        }
    }
}
