package com.example.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.TraiSmsApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Default SMS BroadcastReceiver (`android.provider.Telephony.WAP_PUSH_DELIVER`)
 * required by Android RoleManager (`ROLE_SMS`) for MMS WAP Push compliance.
 * Protected by `android.permission.BROADCAST_WAP_PUSH` in AndroidManifest.xml.
 * Operates strictly via OS-brokered carrier MMS service in 100% Offline Mode
 * with zero direct app network permissions or HTTP clients.
 */
class MmsWapPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        if (intent.type != "application/vnd.wap.mms-message") return

        val pendingResult: PendingResult? = goAsync()
        val app = context.applicationContext as? TraiSmsApp
        if (app == null) {
            pendingResult?.finish()
            return
        }

        val pduBytes = intent.getByteArrayExtra("data")
        val subscriptionId = intent.getIntExtra("subscription", -1)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (pduBytes != null && pduBytes.isNotEmpty()) {
                    app.container.smsRepository.handleIncomingMmsWapPush(
                        pduBytes = pduBytes,
                        subscriptionId = subscriptionId,
                        timestampMillis = System.currentTimeMillis()
                    )
                } else {
                    app.container.smsRepository.handleIncomingSms(
                        rawAddress = "MMS-GATEWAY",
                        body = "[Incoming Multimedia Message (MMS) notification received. Carrier data fetch is disabled in 100% Offline Mode.]",
                        timestampMillis = System.currentTimeMillis()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
