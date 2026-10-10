package com.litetv.app.update

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.litetv.app.iptv.PlaylistRepository
import com.litetv.app.sdui.SduiConfigRepository
import kotlinx.coroutines.*

/**
 * Standard Android [JobService] for periodic background synchronization.
 *
 * Characteristics:
 * - Uses platform-native JobScheduler (zero third-party dependencies / WorkManager overhead)
 * - Optimized for low-RAM embedded hardware (Wisdom Share TV / Android 9 API 28)
 * - Respects network constraints (runs only when network is connected)
 * - Never interrupts active playback
 */
class UpdateSyncJobService : JobService() {

    companion object {
        private const val TAG = "UpdateSyncJobService"
        const val JOB_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Periodic update job started")

        // Suppress background sync if video playback is actively running to avoid buffering/stutter
        if (AppUpdateManager.isPlaybackActive) {
            Log.d(TAG, "Playback is currently active; deferring background update")
            jobFinished(params, false)
            return false
        }

        serviceScope.launch {
            try {
                val configRepo = SduiConfigRepository(applicationContext)
                val playlistRepo = PlaylistRepository(applicationContext)
                val updateManager = AppUpdateManager(applicationContext)

                val coordinator = UpdateCoordinator.getInstance(
                    applicationContext,
                    configRepo,
                    playlistRepo,
                    updateManager
                )

                coordinator.sync(force = false)
            } catch (e: Exception) {
                Log.e(TAG, "Periodic update job encountered error", e)
            } finally {
                jobFinished(params, false)
            }
        }

        return true // Work is running asynchronously
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Update job stopped by system")
        serviceScope.coroutineContext.cancelChildren()
        return true // Reschedule if cancelled unexpectedly
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
