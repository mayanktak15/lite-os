package com.litetv.app.update

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Schedules periodic background catalogue and configuration checks via Android's [JobScheduler].
 */
object UpdateSyncScheduler {

    private const val TAG = "UpdateSyncScheduler"
    private const val MIN_PERIODIC_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes minimum enforced by Android OS

    /**
     * Schedule periodic synchronization.
     *
     * @param context Application context
     * @param intervalMinutes Interval in minutes specified by SDUI configuration
     */
    fun schedulePeriodicSync(context: Context, intervalMinutes: Int) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        if (jobScheduler == null) {
            Log.w(TAG, "JobScheduler service unavailable")
            return
        }

        val requestedIntervalMs = (intervalMinutes * 60 * 1000L).coerceAtLeast(MIN_PERIODIC_INTERVAL_MS)
        val componentName = ComponentName(context, UpdateSyncJobService::class.java)

        val jobInfo = JobInfo.Builder(UpdateSyncJobService.JOB_ID, componentName)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(requestedIntervalMs)
            .build()

        val resultCode = jobScheduler.schedule(jobInfo)
        if (resultCode == JobScheduler.RESULT_SUCCESS) {
            Log.i(TAG, "Scheduled periodic update job every $intervalMinutes min ($requestedIntervalMs ms)")
        } else {
            Log.w(TAG, "Failed to schedule update job (result code: $resultCode)")
        }
    }

    /**
     * Cancel any scheduled periodic sync jobs.
     */
    fun cancelPeriodicSync(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        jobScheduler?.cancel(UpdateSyncJobService.JOB_ID)
        Log.d(TAG, "Cancelled periodic update job")
    }

    /**
     * Check if the update job is currently scheduled.
     */
    fun isScheduled(context: Context): Boolean {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        return jobScheduler?.allPendingJobs?.any { it.id == UpdateSyncJobService.JOB_ID } == true
    }
}
