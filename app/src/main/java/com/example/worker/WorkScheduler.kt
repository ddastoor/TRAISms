package com.example.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.crypto.BackupSchedule
import java.util.concurrent.TimeUnit

/**
 * Configures periodic offline WorkManager jobs (zero network constraints).
 */
class WorkScheduler(context: Context) {

    private val appContext = context.applicationContext

    private val offlineConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .build()

    fun syncPromotionalCleanupSchedule(promoAutoDeleteDays: Int) {
        val workManager = runCatching { WorkManager.getInstance(appContext) }.getOrNull() ?: return
        if (promoAutoDeleteDays <= 0) {
            workManager.cancelUniqueWork(PromotionalCleanupWorker.UNIQUE_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<PromotionalCleanupWorker>(12, TimeUnit.HOURS)
            .setConstraints(offlineConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PromotionalCleanupWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun syncBackupSchedule(schedule: BackupSchedule, customDays: Int) {
        val workManager = runCatching { WorkManager.getInstance(appContext) }.getOrNull() ?: return
        val intervalDays = when (schedule) {
            BackupSchedule.OFF -> 0
            BackupSchedule.CUSTOM -> customDays.coerceAtLeast(1)
            else -> schedule.intervalDays
        }

        if (intervalDays <= 0) {
            workManager.cancelUniqueWork(ScheduledBackupWorker.UNIQUE_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(
            intervalDays.toLong(),
            TimeUnit.DAYS
        )
            .setConstraints(offlineConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ScheduledBackupWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
