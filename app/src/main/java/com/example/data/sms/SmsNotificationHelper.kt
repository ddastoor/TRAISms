package com.example.data.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.SmsCategory

/**
 * Category-aware SMS Notification dispatcher respecting Android 13+ POST_NOTIFICATIONS,
 * silent Promotional filtering, and App Lock privacy redaction.
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
        threadKey: String,
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

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_THREAD_KEY, threadKey)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            threadKey.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayTitle = if (redactPreviewForAppLock) {
            "New ${category.title} SMS"
        } else if (extractedOtp != null) {
            "$senderTitle • OTP: $extractedOtp"
        } else {
            "$senderTitle (${category.title})"
        }

        val displayBody = if (redactPreviewForAppLock) {
            "Unlock TRAISms to view encrypted message"
        } else {
            body
        }

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(displayTitle)
            .setContentText(displayBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
            .setPriority(
                if (channelId == CHANNEL_PRIORITY) NotificationCompat.PRIORITY_HIGH
                else if (channelId == CHANNEL_PROMO) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext)
                .notify(threadKey.hashCode(), notification)
        }
    }

    companion object {
        const val CHANNEL_PRIORITY = "traisms_channel_priority"
        const val CHANNEL_STANDARD = "traisms_channel_standard"
        const val CHANNEL_PROMO = "traisms_channel_promo"
        const val EXTRA_OPEN_THREAD_KEY = "extra_open_thread_key"
    }
}
