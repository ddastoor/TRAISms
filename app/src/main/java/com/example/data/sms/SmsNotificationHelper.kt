package com.example.data.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.SmsCategory

/**
 * Category-aware SMS Notification dispatcher respecting Android 13+ POST_NOTIFICATIONS,
 * silent Promotional filtering, App Lock privacy redaction, and actionable shade buttons:
 * - OTP messages (when unlocked): 4 buttons via `DecoratedCustomViewStyle` (`Copy OTP`,
 *   `Archive`, `Assign Category`, `Delete`) overcoming SystemUI's 3-action limit.
 * - Non-OTP / App-Locked messages: 3 standard `NotificationCompat` actions (`Archive`,
 *   `Assign Category`, `Delete`).
 */
class SmsNotificationHelper(private val context: Context) {

    private val appContext = context.applicationContext

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return

        val channels = listOf(
            NotificationChannel(
                CHANNEL_PRIORITY,
                "Personal, OTP & Urgent Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority notifications for Personal contacts, OTP codes, Banking, and Civic alerts"
            },
            NotificationChannel(
                CHANNEL_STANDARD,
                "Service, Unknown & General SMS",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Standard notifications for deliveries, bookings, and unknown numbers"
            },
            NotificationChannel(
                CHANNEL_PROMO,
                "Promotional Offers (Silent)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Low-priority silent notifications for marketing and TRAI -P messages"
                setSound(null, null)
                enableVibration(false)
            }
        )
        manager.createNotificationChannels(channels)
    }

    fun postIncomingSmsNotification(
        messageId: Long = -1L,
        threadKey: String,
        rawAddress: String = threadKey,
        senderTitle: String,
        body: String,
        category: SmsCategory,
        extractedOtp: String?,
        redactPreviewForAppLock: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val channelId = when (category) {
            SmsCategory.PERSONAL,
            SmsCategory.OTP,
            SmsCategory.TRANSACTIONAL,
            SmsCategory.GOVERNMENT -> CHANNEL_PRIORITY
            SmsCategory.PROMOTIONAL -> CHANNEL_PROMO
            SmsCategory.SERVICE,
            SmsCategory.UNKNOWN,
            SmsCategory.OTHER -> CHANNEL_STANDARD
        }

        val notificationId = notificationIdForThread(threadKey)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val actionableOtp = extractedOtp?.trim()?.takeIf { it.isNotEmpty() && !redactPreviewForAppLock }

        // Slot 0: Content tap -> Open conversation thread in MainActivity
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_THREAD_KEY, threadKey)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val openPendingIntent = PendingIntent.getActivity(
            appContext,
            31 * notificationId + 0,
            openIntent,
            pendingIntentFlags
        )

        // Slot 1: Copy OTP -> Broadcast to SmsNotificationActionReceiver
        val copyOtpPendingIntent = actionableOtp?.let { otp ->
            val copyIntent = Intent(appContext, SmsNotificationActionReceiver::class.java).apply {
                action = ACTION_COPY_OTP
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_OTP_CODE, otp)
                putExtra(EXTRA_THREAD_KEY, threadKey)
                putExtra(EXTRA_MESSAGE_ID, messageId)
            }
            PendingIntent.getBroadcast(
                appContext,
                31 * notificationId + 1,
                copyIntent,
                pendingIntentFlags
            )
        }

        // Slot 2: Archive -> Broadcast to SmsNotificationActionReceiver
        val archiveIntent = Intent(appContext, SmsNotificationActionReceiver::class.java).apply {
            action = ACTION_ARCHIVE
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_THREAD_KEY, threadKey)
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        val archivePendingIntent = PendingIntent.getBroadcast(
            appContext,
            31 * notificationId + 2,
            archiveIntent,
            pendingIntentFlags
        )

        // Slot 3: Assign Category -> Direct Activity PendingIntent to MainActivity (Android 12+ trampoline compliant)
        val assignCategoryIntent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_ASSIGN_CATEGORY
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_THREAD_KEY, threadKey)
            putExtra(EXTRA_RAW_ADDRESS, rawAddress)
            putExtra(EXTRA_SENDER_TITLE, senderTitle)
            putExtra(EXTRA_CURRENT_CATEGORY, category.name)
        }
        val assignCategoryPendingIntent = PendingIntent.getActivity(
            appContext,
            31 * notificationId + 3,
            assignCategoryIntent,
            pendingIntentFlags
        )

        // Slot 4: Delete -> Broadcast to SmsNotificationActionReceiver
        val deleteIntent = Intent(appContext, SmsNotificationActionReceiver::class.java).apply {
            action = ACTION_DELETE
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_THREAD_KEY, threadKey)
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            appContext,
            31 * notificationId + 4,
            deleteIntent,
            pendingIntentFlags
        )

        val displayTitle = if (redactPreviewForAppLock) {
            "New ${category.title} SMS"
        } else if (actionableOtp != null) {
            "$senderTitle • OTP: $actionableOtp"
        } else {
            "$senderTitle (${category.title})"
        }

        val displayBody = if (redactPreviewForAppLock) {
            "Unlock TRAISms to view encrypted message"
        } else {
            body
        }

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(displayTitle)
            .setContentText(displayBody)
            .setPriority(
                if (channelId == CHANNEL_PRIORITY) NotificationCompat.PRIORITY_HIGH
                else if (channelId == CHANNEL_PROMO) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)

        if (actionableOtp != null && copyOtpPendingIntent != null) {
            val collapsedViews = RemoteViews(
                appContext.packageName,
                R.layout.notification_sms_otp_collapsed
            ).apply {
                setTextViewText(R.id.notif_collapsed_title, displayTitle)
                setTextViewText(R.id.notif_collapsed_body, displayBody)
            }

            fun buildExpandedOtpViews(maxBodyLines: Int): RemoteViews {
                return RemoteViews(
                    appContext.packageName,
                    R.layout.notification_sms_otp_expanded
                ).apply {
                    setTextViewText(R.id.notif_expanded_title, displayTitle)
                    setTextViewText(R.id.notif_expanded_body, displayBody)
                    setInt(R.id.notif_expanded_body, "setMaxLines", maxBodyLines)
                    setOnClickPendingIntent(R.id.notif_btn_copy_otp, copyOtpPendingIntent)
                    setOnClickPendingIntent(R.id.notif_btn_archive, archivePendingIntent)
                    setOnClickPendingIntent(R.id.notif_btn_assign_category, assignCategoryPendingIntent)
                    setOnClickPendingIntent(R.id.notif_btn_delete, deletePendingIntent)
                }
            }

            val expandedViews = buildExpandedOtpViews(maxBodyLines = 4)
            val headsUpViews = buildExpandedOtpViews(maxBodyLines = 2)

            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsedViews)
                .setCustomBigContentView(expandedViews)
                .setCustomHeadsUpContentView(headsUpViews)
        } else {
            builder
                .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
                .addAction(
                    0,
                    appContext.getString(R.string.notif_action_archive),
                    archivePendingIntent
                )
                .addAction(
                    0,
                    appContext.getString(R.string.notif_action_assign_category),
                    assignCategoryPendingIntent
                )
                .addAction(
                    0,
                    appContext.getString(R.string.notif_action_delete),
                    deletePendingIntent
                )
        }

        runCatching {
            NotificationManagerCompat.from(appContext)
                .notify(notificationId, builder.build())
        }
    }

    companion object {
        const val CHANNEL_PRIORITY = "traisms_channel_priority"
        const val CHANNEL_STANDARD = "traisms_channel_standard"
        const val CHANNEL_PROMO = "traisms_channel_promo"
        const val EXTRA_OPEN_THREAD_KEY = "extra_open_thread_key"

        const val ACTION_COPY_OTP = "com.traisms.app.action.NOTIF_COPY_OTP"
        const val ACTION_ARCHIVE = "com.traisms.app.action.NOTIF_ARCHIVE"
        const val ACTION_DELETE = "com.traisms.app.action.NOTIF_DELETE"
        const val ACTION_ASSIGN_CATEGORY = "com.traisms.app.action.NOTIF_ASSIGN_CATEGORY"

        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        const val EXTRA_THREAD_KEY = "extra_thread_key"
        const val EXTRA_RAW_ADDRESS = "extra_raw_address"
        const val EXTRA_SENDER_TITLE = "extra_sender_title"
        const val EXTRA_CURRENT_CATEGORY = "extra_current_category"
        const val EXTRA_OTP_CODE = "extra_otp_code"

        fun notificationIdForThread(threadKey: String): Int = threadKey.hashCode()

        fun cancelNotification(context: Context, notificationId: Int) {
            runCatching {
                NotificationManagerCompat.from(context.applicationContext).cancel(notificationId)
            }
        }
    }
}
