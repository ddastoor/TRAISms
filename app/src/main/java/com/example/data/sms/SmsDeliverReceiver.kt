package com.example.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.TraiSmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Default SMS BroadcastReceiver (`android.provider.Telephony.SMS_DELIVER`).
 * Protected by `android.permission.BROADCAST_SMS` in AndroidManifest.xml.
 * Parses incoming PDUs, reassembles multipart messages per sender, inserts into
 * `Telephony.Sms.Inbox` + SQLCipher Room, runs categorization, and posts notification.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group multipart PDU fragments by originating address
        val groupedBodies = LinkedHashMap<String, StringBuilder>()
        val groupedTimestamps = HashMap<String, Long>()

        for (pduMsg in messages) {
            val sender = pduMsg.displayOriginatingAddress
                ?: pduMsg.originatingAddress
                ?: "UNKNOWN"
            val fragment = pduMsg.displayMessageBody ?: pduMsg.messageBody.orEmpty()
            groupedBodies.getOrPut(sender) { StringBuilder() }.append(fragment)
            if (sender !in groupedTimestamps) {
                groupedTimestamps[sender] = pduMsg.timestampMillis.takeIf { it > 0L }
                    ?: System.currentTimeMillis()
            }
        }

        val pendingResult: PendingResult? = goAsync()
        val app = context.applicationContext as? TraiSmsApp
        if (app == null) {
            pendingResult?.finish()
            return
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                for ((sender, bodyBuilder) in groupedBodies) {
                    val fullBody = bodyBuilder.toString()
                    if (fullBody.isNotEmpty()) {
                        app.container.smsRepository.handleIncomingSms(
                            rawAddress = sender,
                            body = fullBody,
                            timestampMillis = groupedTimestamps[sender] ?: System.currentTimeMillis()
                        )
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
