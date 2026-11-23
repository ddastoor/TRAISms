package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.TraiSmsApp

/**
 * Periodic WorkManager task (runs every 12 hours, zero network constraints).
 * Strictly deletes ONLY unarchived `Promotional` messages older than the user's selected
 * retention preset from BOTH SQLCipher Room and Android's `Telephony.Sms` ContentProvider.
 */
class PromotionalCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TraiSmsApp ?: return Result.success()
        val settings = app.container.settingsStore.getSettingsSnapshot()
        val days = settings.promoAutoDeleteDays
        if (days <= 0) {
            return Result.success()
        }

        return try {
            app.container.smsRepository.purgeExpiredPromotionalMessages(days)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "traisms_promotional_auto_cleanup_work"
    }
}
