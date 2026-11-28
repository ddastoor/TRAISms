package com.example.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.example.TraiSmsApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Non-exported BroadcastReceiver handling OS-brokered SMS/MMS sent, delivery,
 * and MMS download completion callbacks from `SmsManager`.
 */
class SmsMmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? TraiSmsApp ?: return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val pduFilePath = intent.getStringExtra(EXTRA_PDU_FILE_PATH)
        val subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1)
        val responseData = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
        val resultCode = resultCode

        val pendingResult: PendingResult? = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_SMS_SENT -> if (messageId > 0L) {
                        app.container.smsRepository.handleSmsDeliveryStatusCallback(
                            messageId = messageId,
                            isDeliveryReport = false,
                            resultCode = resultCode
                        )
                    }
                    ACTION_SMS_DELIVERED -> if (messageId > 0L) {
                        app.container.smsRepository.handleSmsDeliveryStatusCallback(
                            messageId = messageId,
                            isDeliveryReport = true,
                            resultCode = resultCode
                        )
                    }
                    ACTION_MMS_SENT -> if (messageId > 0L) {
                        app.container.smsRepository.handleMmsSentResult(
                            messageId = messageId,
                            pduFilePath = pduFilePath,
                            resultCode = resultCode,
                            responsePduBytes = responseData
                        )
                    }
                    ACTION_MMS_DOWNLOADED -> if (messageId > 0L && !pduFilePath.isNullOrBlank()) {
                        app.container.smsRepository.handleDownloadedMmsPdu(
                            messageId = messageId,
                            pduFilePath = pduFilePath,
                            resultCode = resultCode,
                            subscriptionId = subscriptionId
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_SMS_SENT = "com.traisms.app.action.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.traisms.app.action.SMS_DELIVERED"
        const val ACTION_MMS_SENT = "com.traisms.app.action.MMS_SENT"
        const val ACTION_MMS_DOWNLOADED = "com.traisms.app.action.MMS_DOWNLOADED"

        const val EXTRA_MESSAGE_ID = "extra_message_id"
        const val EXTRA_PDU_FILE_PATH = "extra_pdu_file_path"
        const val EXTRA_THREAD_KEY = "extra_thread_key"
        const val EXTRA_SUBSCRIPTION_ID = "extra_subscription_id"
    }
}
