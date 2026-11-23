package com.example.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.TraiSmsApp
import com.example.data.crypto.BackupSchedule

/**
 * Scheduled unattended encrypted SAF backup worker writing AES-256-GCM `.trai` snapshots
 * via `contentResolver.openOutputStream(persistedUri, "wt")` with zero network permissions.
 */
class ScheduledBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TraiSmsApp ?: return Result.success()
        val settings = app.container.settingsStore.getSettingsSnapshot()

        if (settings.backupSchedule == BackupSchedule.OFF ||
            settings.persistedDriveUri.isBlank() ||
            settings.backupPassphrase.isBlank()
        ) {
            return Result.success()
        }

        val uri = runCatching { Uri.parse(settings.persistedDriveUri) }.getOrNull()
            ?: return Result.failure()

        val exportResult = app.container.smsRepository.exportEncryptedBackupToUri(
            targetUri = uri,
            passphrase = settings.backupPassphrase
        )
        return if (exportResult.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "traisms_scheduled_saf_backup_work"
    }
}
