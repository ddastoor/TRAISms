package com.example.data.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import com.example.TraiSmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Default SMS Headless Quick-Response Service (`android.intent.action.RESPOND_VIA_MESSAGE`)
 * protected by `android.permission.SEND_RESPOND_VIA_MESSAGE`.
 * Enables system incoming-call quick-reply SMS dispatch.
 */
class HeadlessSmsSendService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val dataUri = intent.data
        val recipient = dataUri?.schemeSpecificPart
            ?.substringBefore('?')
            ?.trim()
            .orEmpty()
        val messageText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()

        val app = applicationContext as? TraiSmsApp
        if (recipient.isEmpty() || messageText.isEmpty() || app == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                app.container.smsRepository.sendOutgoingSms(
                    destinationAddress = recipient,
                    body = messageText
                )
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
