package com.example.data.sms

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import com.example.R
import com.example.TraiSmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles background SMS notification actions (`Copy OTP`, `Archive`, `Delete`).
 * Registered with `android:exported="false"` in `AndroidManifest.xml`.
 *
 * Note: `Assign Category` uses `PendingIntent.getActivity` directly to `MainActivity`
 * to comply with Android 12+ (`targetSdk 36`) Notification Trampoline restrictions.
 */
class SmsNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != SmsNotificationHelper.ACTION_COPY_OTP &&
            action != SmsNotificationHelper.ACTION_ARCHIVE &&
            action != SmsNotificationHelper.ACTION_DELETE
        ) {
            return
        }

        val threadKey = intent.getStringExtra(SmsNotificationHelper.EXTRA_THREAD_KEY)?.trim()
        if (intent.hasExtra(SmsNotificationHelper.EXTRA_NOTIFICATION_ID)) {
            val notificationId = intent.getIntExtra(SmsNotificationHelper.EXTRA_NOTIFICATION_ID, 0)
            SmsNotificationHelper.cancelNotification(context, notificationId)
        } else if (!threadKey.isNullOrEmpty()) {
            SmsNotificationHelper.cancelNotification(
                context,
                SmsNotificationHelper.notificationIdForThread(threadKey)
            )
        }

        if (action == SmsNotificationHelper.ACTION_COPY_OTP) {
            val otp = intent.getStringExtra(SmsNotificationHelper.EXTRA_OTP_CODE)?.trim()
            if (!otp.isNullOrEmpty()) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val clip = ClipData.newPlainText("OTP", otp)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboard?.setPrimaryClip(clip)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    runCatching {
                        Toast.makeText(
                            context.applicationContext,
                            context.getString(R.string.otp_copied_toast, otp),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            return
        }

        val app = context.applicationContext as? TraiSmsApp ?: return
        val messageId = intent.getLongExtra(SmsNotificationHelper.EXTRA_MESSAGE_ID, -1L)
        val pendingResult: PendingResult? = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    SmsNotificationHelper.ACTION_ARCHIVE -> {
                        if (!threadKey.isNullOrEmpty()) {
                            app.container.smsRepository.setThreadArchived(threadKey, true)
                        }
                    }
                    SmsNotificationHelper.ACTION_DELETE -> {
                        app.container.smsRepository.deleteNotificationMessage(
                            messageId = messageId,
                            fallbackThreadKey = threadKey
                        )
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
