package com.example

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.hardware.biometrics.BiometricManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.crypto.EncryptedSettingsStore
import com.example.data.crypto.LeftSwipeAction
import com.example.data.crypto.TinkAppSettingsSerializer
import com.example.data.db.MmsAttachmentEntity
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.db.ThreadSummary
import com.example.data.db.TraiSmsDatabase
import com.example.data.sms.ContactLookupCache
import com.example.data.sms.MmsPart
import com.example.data.sms.MmsPduCodec
import com.example.data.sms.MmsPduType
import com.example.data.sms.MmsSendReqInput
import com.example.data.sms.MmsWapPushReceiver
import com.example.data.sms.PendingMmsAttachment
import com.example.data.sms.RcsInteropCodec
import com.example.data.sms.SmsMmsStatusReceiver
import com.example.data.sms.SystemSmsHelper
import com.example.ui.common.ThreadRowItem
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.TraiSmsTheme
import com.example.ui.thread.ComposeSmsScreen
import com.example.ui.thread.OfflineLocationTextDialog
import com.example.ui.thread.ThreadDetailScreen
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowRoleManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class MmsRcsDefaultSmsRobolectricTest {

    @get:Rule(order = 0)
    val snapshotSyncRule: TestRule = createComposeSnapshotSyncRule()

    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var app: TraiSmsApp
    private lateinit var systemSmsHelper: SystemSmsHelper
    private lateinit var smsProvider: FakeSmsContentProvider
    private lateinit var mmsProvider: FakeMmsContentProvider
    private lateinit var contactsProvider: FakeContactsContentProvider
    private val tempStorageDirs = mutableListOf<File>()

    @Before
    fun setUp(): Unit {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            app = context as TraiSmsApp
            systemSmsHelper = SystemSmsHelper(context)

            smsProvider = FakeSmsContentProvider()
            smsProvider.attachInfo(context, ProviderInfo().apply { authority = "sms" })
            ShadowContentResolver.registerProviderInternal("sms", smsProvider)

            mmsProvider = FakeMmsContentProvider(createIsolatedDir("mms_provider_blobs"))
            mmsProvider.attachInfo(context, ProviderInfo().apply { authority = "mms" })
            ShadowContentResolver.registerProviderInternal("mms", mmsProvider)

            contactsProvider = FakeContactsContentProvider()
            contactsProvider.attachInfo(
                context,
                ProviderInfo().apply { authority = ContactsContract.AUTHORITY }
            )
            ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, contactsProvider)

            Shadows.shadowOf(app as Application).grantPermissions(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.RECEIVE_MMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.POST_NOTIFICATIONS
            )
            ShadowRoleManager.reset()
            ShadowBiometricManager.reset()
            ShadowDialog.reset()
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            Shadows.shadowOf(keyguard).setIsDeviceSecure(false)
            context.getSystemService(NotificationManager::class.java).cancelAll()

            app.container.database.smsDao().deleteAllAttachments()
            app.container.database.smsDao().deleteAllMessages()
            app.container.settingsStore.updateSettings {
                AppSettings(
                    appLockEnabled = false,
                    initialSyncCompleted = true,
                    onboardingDismissed = true,
                    rcsChatsEnabled = true,
                    autoDownloadMms = true,
                    sendReadReceipts = true
                )
            }
        }
    }

    @After
    fun tearDown(): Unit {
        runBlocking {
            ShadowDialog.reset()
            ShadowRoleManager.reset()
            ShadowBiometricManager.reset()
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            Shadows.shadowOf(keyguard).setIsDeviceSecure(false)
            context.getSystemService(NotificationManager::class.java).cancelAll()

            smsProvider.clear()
            mmsProvider.clear()
            contactsProvider.setContacts(emptyList())
            app.container.database.smsDao().deleteAllAttachments()
            app.container.database.smsDao().deleteAllMessages()
            app.container.settingsStore.updateSettings {
                AppSettings(
                    appLockEnabled = false,
                    initialSyncCompleted = true,
                    onboardingDismissed = true,
                    rcsChatsEnabled = true,
                    autoDownloadMms = true,
                    sendReadReceipts = true
                )
            }
            tempStorageDirs.forEach { it.deleteRecursively() }
            tempStorageDirs.clear()
        }
    }

    @Test
    fun `MmsPduCodec round-trips group M-Send req with SMIL multipart media and handles edge cases`(): Unit {
        val pngBytes = createTinyPngBytes()
        val videoBytes = byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        val audioBytes = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x01)
        val vCardBytes = RcsInteropCodec.buildVCardContent("Alice Sharma", "+919876543210").toByteArray(Charsets.UTF_8)

        val pdu = MmsPduCodec.encodeSendReq(
            MmsSendReqInput(
                transactionId = "tx_roundtrip_1",
                toAddresses = listOf("+919876543210", "+919820112233"),
                fromAddress = "+919811122233",
                subject = "Holiday Photos 🎉",
                epochSeconds = 1_732_500_000L,
                deliveryReport = true,
                readReport = true,
                parts = listOf(
                    MmsPart(
                        contentType = "text/plain",
                        contentId = "<text_0>",
                        contentLocation = "text_0.txt",
                        fileName = "text_0.txt",
                        data = "Dinner tonight!".toByteArray(Charsets.UTF_8)
                    ),
                    MmsPart(
                        contentType = "image/png",
                        contentId = "<img_1>",
                        contentLocation = "photo.png",
                        fileName = "photo.png",
                        data = pngBytes
                    ),
                    MmsPart(
                        contentType = "video/mp4",
                        contentId = "<vid_2>",
                        contentLocation = "clip.mp4",
                        fileName = "clip.mp4",
                        data = videoBytes
                    ),
                    MmsPart(
                        contentType = "audio/mpeg",
                        contentId = "<aud_3>",
                        contentLocation = "voice.mp3",
                        fileName = "voice.mp3",
                        data = audioBytes
                    ),
                    MmsPart(
                        contentType = "text/x-vCard",
                        contentId = "<vcf_4>",
                        contentLocation = "alice.vcf",
                        fileName = "alice.vcf",
                        data = vCardBytes
                    )
                )
            )
        )

        val decoded = MmsPduCodec.decodePdu(pdu)
        assertNotNull(decoded)
        decoded!!
        assertEquals(MmsPduType.SEND_REQ, decoded.messageType)
        assertEquals("tx_roundtrip_1", decoded.transactionId)
        assertEquals("+919811122233", decoded.fromAddress)
        assertEquals(listOf("+919876543210", "+919820112233"), decoded.toAddresses)
        assertEquals("Holiday Photos 🎉", decoded.subject)
        assertEquals(1_732_500_000L, decoded.dateEpochSeconds)
        assertTrue(decoded.readReportRequested)

        // 1 SMIL + 5 content parts
        assertEquals(6, decoded.parts.size)
        assertEquals("application/smil", decoded.parts.first().contentType)
        val smilText = decoded.parts.first().data.toString(Charsets.UTF_8)
        assertTrue(smilText.contains("<text "))
        assertTrue(smilText.contains("<img "))
        assertTrue(smilText.contains("<video "))
        assertTrue(smilText.contains("<audio "))

        val ordered = MmsPduCodec.extractSmilOrderedParts(decoded.parts)
        assertEquals(5, ordered.size)
        assertEquals("Dinner tonight!", ordered[0].data.toString(Charsets.UTF_8))
        assertArrayEquals(pngBytes, ordered[1].data)
        assertArrayEquals(videoBytes, ordered[2].data)
        assertArrayEquals(audioBytes, ordered[3].data)
        assertArrayEquals(vCardBytes, ordered[4].data)

        // Control PDUs round-trip cleanly
        val notifyResp = MmsPduCodec.decodePdu(MmsPduCodec.encodeNotifyRespInd("tx_n1"))
        assertEquals(MmsPduType.NOTIFYRESP_IND, notifyResp?.messageType)
        assertEquals("tx_n1", notifyResp?.transactionId)

        val ack = MmsPduCodec.decodePdu(MmsPduCodec.encodeAcknowledgeInd("tx_a1"))
        assertEquals(MmsPduType.ACKNOWLEDGE_IND, ack?.messageType)
        assertEquals("tx_a1", ack?.transactionId)

        val readRec = MmsPduCodec.decodePdu(
            MmsPduCodec.encodeReadRecInd("msg_rr_1", "+919876543210", 1_732_500_111L)
        )
        assertEquals(MmsPduType.READ_REC_IND, readRec?.messageType)
        assertEquals("msg_rr_1", readRec?.messageId)
        assertEquals(listOf("+919876543210"), readRec?.toAddresses)

        // Edge cases: empty & malformed PDU bytes
        assertNull(MmsPduCodec.decodePdu(ByteArray(0)))
        assertNull(MmsPduCodec.decodePdu(byteArrayOf(0x01)))
        assertNull(MmsPduCodec.decodePdu(byteArrayOf(0x8C.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `TraiSmsDatabase MIGRATION_1_2 preserves existing v1 messages and rules while validating v2 Room schema`(): Unit {
        runBlocking {
        val dbDir = createIsolatedDir("room_mig")
        val dbPath = File(dbDir, "v1_to_v2.db").absolutePath

        // 1. Create and populate an exact v1 SQLite database
        val v1Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `sms_messages` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `systemSmsId` INTEGER,
                                `threadKey` TEXT NOT NULL,
                                `rawAddress` TEXT NOT NULL,
                                `contactName` TEXT,
                                `body` TEXT NOT NULL,
                                `bodySha256` TEXT NOT NULL,
                                `timestampMillis` INTEGER NOT NULL,
                                `isIncoming` INTEGER NOT NULL,
                                `isRead` INTEGER NOT NULL,
                                `isArchived` INTEGER NOT NULL,
                                `category` TEXT NOT NULL,
                                `extractedOtp` TEXT,
                                `confidenceScore` REAL NOT NULL,
                                `classificationSource` TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_messages_threadKey_isArchived_timestampMillis` ON `sms_messages` (`threadKey`, `isArchived`, `timestampMillis`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_messages_category_isArchived_timestampMillis` ON `sms_messages` (`category`, `isArchived`, `timestampMillis`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_messages_systemSmsId` ON `sms_messages` (`systemSmsId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_messages_threadKey_timestampMillis_bodySha256` ON `sms_messages` (`threadKey`, `timestampMillis`, `bodySha256`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `sender_category_rules` (
                                `normalizedSender` TEXT NOT NULL,
                                `displaySender` TEXT NOT NULL,
                                `category` TEXT NOT NULL,
                                `updatedAtMillis` INTEGER NOT NULL,
                                PRIMARY KEY(`normalizedSender`)
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v1Helper.writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO sms_messages (
                    id, systemSmsId, threadKey, rawAddress, contactName, body, bodySha256,
                    timestampMillis, isIncoming, isRead, isArchived, category, extractedOtp,
                    confidenceScore, classificationSource
                ) VALUES (
                    10, 777, 'VM-HDFCBK-T', 'VM-HDFCBK-T', NULL, '918273 is your OTP', 'hash918273',
                    1700000000000, 1, 0, 0, 'OTP', '918273', 0.99, 'TRAI_OTP'
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO sender_category_rules (normalizedSender, displaySender, category, updatedAtMillis) VALUES ('NUM_9876543210', 'Alice', 'PERSONAL', 1700000000000)"
            )
        }
        v1Helper.close()

        // 2. Open with Room v2 + MIGRATION_1_2 (Room validates full schema against entities)
        val v2Db = Room.databaseBuilder(context, TraiSmsDatabase::class.java, dbPath)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(TraiSmsDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedMsgs = v2Db.smsDao().getAllMessagesSnapshot()
            assertEquals(1, migratedMsgs.size)
            val msg = migratedMsgs.single()
            assertEquals(10L, msg.id)
            assertEquals("918273 is your OTP", msg.body)
            assertEquals("918273", msg.extractedOtp)
            assertEquals("SMS", msg.transportType)
            assertEquals("NONE", msg.deliveryStatus)
            assertFalse(msg.isHiddenReaction)

            val rules = v2Db.senderRuleDao().getAllRules()
            assertEquals(1, rules.size)
            assertEquals("Alice", rules.single().displaySender)
            assertEquals(SmsCategory.PERSONAL, rules.single().category)

            // Verify mms_attachments table works seamlessly after migration
            v2Db.smsDao().insertAttachments(
                listOf(
                    MmsAttachmentEntity(
                        messageId = 10L,
                        partIndex = 0,
                        contentType = "image/png",
                        fileName = "receipt.png",
                        sizeBytes = 64L
                    )
                )
            )
            val joined = v2Db.smsDao().observeThreadMessagesWithAttachments("VM-HDFCBK-T").first()
            assertEquals(1, joined.size)
            assertEquals(1, joined.single().attachments.size)
            assertEquals("receipt.png", joined.single().attachments.single().fileName)
        } finally {
            v2Db.close()
        }
        }
    }

    @Test
    fun `MmsWapPushReceiver FileProvider SmsMmsStatusReceiver and Telephony Mms sync work end-to-end offline`(): Unit {
        runBlocking {
        grantDefaultSmsRole()
        val repository = app.container.smsRepository
        val smsDao = app.container.database.smsDao()

        // 1. Non-exported FileProvider staging URI
        val pduStageFile = File(systemSmsHelper.getMmsPduCacheDir(), "stage_test.pdu").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val fpUri = systemSmsHelper.buildFileProviderUri(pduStageFile)
        assertEquals("content", fpUri.scheme)
        assertEquals("${context.packageName}.mmsfileprovider", fpUri.authority)
        pduStageFile.delete()

        // 2. Empty WAP push preserves offline notice fallback
        MmsWapPushReceiver().onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                type = "application/vnd.wap.mms-message"
            }
        )
        waitForCondition { smsDao.getAllMessagesSnapshot().any { it.body.contains("100% Offline Mode") } }

        // 3. M-Notification.ind with autoDownloadMms = false creates PENDING_DOWNLOAD MMS card
        app.container.settingsStore.updateSettings { it.copy(autoDownloadMms = false) }
        val notifIndPdu = buildBinaryNotificationIndPdu(
            transactionId = "tx_notif_manual",
            fromAddress = "+919876543210",
            contentLocation = "http://mmsc.carrier.local/mms/tx_notif_manual",
            subject = "Manual Photo",
            sizeBytes = 4096L,
            epochSeconds = 1_732_500_200L
        )
        MmsWapPushReceiver().onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                type = "application/vnd.wap.mms-message"
                putExtra("data", notifIndPdu)
                putExtra("subscription", 1)
            }
        )
        waitForCondition {
            smsDao.getMessageByTransactionId("tx_notif_manual")?.deliveryStatus == "PENDING_DOWNLOAD"
        }
        val pendingMsg = smsDao.getMessageByTransactionId("tx_notif_manual")!!
        assertEquals("MMS", pendingMsg.transportType)
        assertEquals("Manual Photo", pendingMsg.mmsSubject)
        assertEquals("http://mmsc.carrier.local/mms/tx_notif_manual", pendingMsg.mmsContentLocation)

        // 4. Download completion (M-Retrieve.conf) persists attachments in noBackupFilesDir/mms_media & deletes cache PDU
        val pngBytes = createTinyPngBytes()
        val retrievePdu = buildBinaryRetrieveConfPdu(
            messageId = "srv_msg_1",
            transactionId = "tx_notif_manual",
            fromAddress = "+919876543210",
            toAddress = "+919811122233",
            subject = "Manual Photo",
            epochSeconds = 1_732_500_201L,
            bodyText = "Look at this photo!",
            imageBytes = pngBytes
        )
        val dlPduFile = File(systemSmsHelper.getMmsPduCacheDir(), "dl_manual.pdu").apply {
            writeBytes(retrievePdu)
        }
        repository.handleDownloadedMmsPdu(
            messageId = pendingMsg.id,
            pduFilePath = dlPduFile.absolutePath,
            resultCode = Activity.RESULT_OK
        )
        assertFalse("Temp PDU file must be deleted after download processing", dlPduFile.exists())

        val downloadedWithAtts = smsDao.observeThreadMessagesWithAttachments(pendingMsg.threadKey)
            .first()
            .first { it.message.id == pendingMsg.id }
        assertEquals("Look at this photo!", downloadedWithAtts.message.body)
        assertEquals(1, downloadedWithAtts.attachments.size)
        val savedMediaPath = downloadedWithAtts.attachments.single().localFilePath!!
        assertTrue(savedMediaPath.contains("mms_media"))
        assertTrue(File(savedMediaPath).exists())

        // 5. M-Notification.ind with autoDownloadMms = true stages temp download PDU file
        app.container.settingsStore.updateSettings { it.copy(autoDownloadMms = true) }
        val autoNotifPdu = buildBinaryNotificationIndPdu(
            transactionId = "tx_notif_auto",
            fromAddress = "+919876543210",
            contentLocation = "http://mmsc.carrier.local/mms/tx_notif_auto",
            subject = "Auto MMS",
            sizeBytes = 2048L,
            epochSeconds = 1_732_500_210L
        )
        MmsWapPushReceiver().onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                type = "application/vnd.wap.mms-message"
                putExtra("data", autoNotifPdu)
            }
        )
        waitForCondition { smsDao.getMessageByTransactionId("tx_notif_auto") != null }
        val autoMsg = smsDao.getMessageByTransactionId("tx_notif_auto")!!
        assertTrue(
            "Expected staged download PDU in cacheDir/mms_pdu",
            systemSmsHelper.getMmsPduCacheDir().listFiles().orEmpty().any {
                it.name.startsWith("dl_conf_${autoMsg.id}_")
            }
        )

        // 6. Outgoing SMS attaches immutable sent/delivery PendingIntents + SmsMmsStatusReceiver failure + status upgrades
        val sendSmsResult = repository.sendOutgoingSms("+919876543210", "Status tracked SMS")
        assertTrue(sendSmsResult.isSuccess)
        val sentSms = smsDao.getMessagesInThreadSnapshot("NUM_9876543210").first { it.body == "Status tracked SMS" }
        assertEquals("SENDING", sentSms.deliveryStatus)

        val smsManager = context.getSystemService(SmsManager::class.java)
        val multipartParams = Shadows.shadowOf(smsManager).lastSentMultipartTextMessageParams
        assertNotNull(multipartParams)
        assertEquals(1, multipartParams.sentIntents.size)
        assertEquals(1, multipartParams.deliveryIntents.size)
        val sentSavedIntent = Shadows.shadowOf(multipartParams.sentIntents[0]).savedIntent
        assertEquals(SmsMmsStatusReceiver.ACTION_SMS_SENT, sentSavedIntent.action)
        assertEquals(sentSms.id, sentSavedIntent.getLongExtra(SmsMmsStatusReceiver.EXTRA_MESSAGE_ID, -1L))

        // Positive transitions: SENDING -> SENT -> DELIVERED (and never downgrades)
        repository.handleSmsDeliveryStatusCallback(sentSms.id, isDeliveryReport = false, resultCode = Activity.RESULT_OK)
        assertEquals("SENT", smsDao.getMessageById(sentSms.id)?.deliveryStatus)
        repository.handleSmsDeliveryStatusCallback(sentSms.id, isDeliveryReport = true, resultCode = Activity.RESULT_OK)
        assertEquals("DELIVERED", smsDao.getMessageById(sentSms.id)?.deliveryStatus)
        // SmsMmsStatusReceiver failure callback on an already DELIVERED message must NOT downgrade it
        SmsMmsStatusReceiver().onReceive(context, sentSavedIntent)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)
        assertEquals("DELIVERED", smsDao.getMessageById(sentSms.id)?.deliveryStatus)

        // Failure callback on SENDING message marks FAILED via SmsMmsStatusReceiver
        repository.sendOutgoingSms("+919876543210", "Will fail")
        val failSms = smsDao.getMessagesInThreadSnapshot("NUM_9876543210").first { it.body == "Will fail" }
        SmsMmsStatusReceiver().onReceive(
            context,
            Intent(context, SmsMmsStatusReceiver::class.java).apply {
                action = SmsMmsStatusReceiver.ACTION_SMS_SENT
                putExtra(SmsMmsStatusReceiver.EXTRA_MESSAGE_ID, failSms.id)
            }
        )
        waitForCondition { smsDao.getMessageById(failSms.id)?.deliveryStatus == "FAILED" }

        // 7. Outgoing Group MMS + M-Send.conf + M-Delivery.ind + M-Read-Orig.ind status progression
        val mmsSendRes = repository.sendOutgoingMms(
            recipients = listOf("+919876543210", "+919820112233"),
            body = "Group photo",
            subject = "Team Lunch",
            attachments = listOf(
                PendingMmsAttachment(
                    contentType = "text/plain",
                    fileName = "note.txt",
                    sizeBytes = 5L,
                    textContent = "Hello"
                )
            )
        )
        assertTrue(mmsSendRes.isSuccess)
        val grpThreadKey = mmsSendRes.getOrThrow()
        assertTrue(grpThreadKey.startsWith("GRP_"))
        val grpMsg = smsDao.getMessagesInThreadSnapshot(grpThreadKey).single()
        assertEquals("SENDING", grpMsg.deliveryStatus)

        val sendConfBytes = buildBinarySendConfPdu("srv_mms_tx_99")
        val stagedSendPdu = File(systemSmsHelper.getMmsPduCacheDir(), "temp_send_clean.pdu").apply { writeText("x") }
        repository.handleMmsSentResult(
            messageId = grpMsg.id,
            pduFilePath = stagedSendPdu.absolutePath,
            resultCode = Activity.RESULT_OK,
            responsePduBytes = sendConfBytes
        )
        assertFalse(stagedSendPdu.exists())
        assertEquals("SENT", smsDao.getMessageById(grpMsg.id)?.deliveryStatus)
        assertEquals("srv_mms_tx_99", smsDao.getMessageById(grpMsg.id)?.mmsTransactionId)

        // Receive M-Delivery.ind and M-Read-Orig.ind via MmsWapPushReceiver
        MmsWapPushReceiver().onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                type = "application/vnd.wap.mms-message"
                putExtra("data", buildBinaryDeliveryIndPdu("srv_mms_tx_99"))
            }
        )
        waitForCondition { smsDao.getMessageById(grpMsg.id)?.deliveryStatus == "DELIVERED" }

        MmsWapPushReceiver().onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                type = "application/vnd.wap.mms-message"
                putExtra("data", buildBinaryReadOrigIndPdu("srv_mms_tx_99"))
            }
        )
        waitForCondition { smsDao.getMessageById(grpMsg.id)?.deliveryStatus == "READ" }

        // 8. System MMS + RCS-bridged sync and Google Messages reaction folding
        val seedTimeSec = 1_732_500_300L
        mmsProvider.seedMms(
            id = 9001L,
            threadId = 77L,
            dateSec = seedTimeSec,
            box = Telephony.Mms.MESSAGE_BOX_INBOX,
            read = 0,
            subject = "RCS Sync",
            creator = "com.google.android.apps.messaging",
            fromAddress = "+919820112233",
            toAddresses = listOf("+919811122233"),
            textBody = "Checking RCS sync!",
            extraPartMime = "application/gsma.rcs",
            extraPartBytes = "rcs".toByteArray()
        )
        smsProvider.seedExistingMessage(
            id = 8001L,
            address = "+919820112233",
            body = "Loved “Checking RCS sync!”",
            dateMillis = (seedTimeSec + 5L) * 1000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        repository.syncDeviceSmsInChunks()
        val syncedThread = smsDao.observeThreadMessages("NUM_9820112233").first()
        val rcsMsg = syncedThread.single { it.body == "Checking RCS sync!" }
        assertEquals("RCS", rcsMsg.transportType)
        val reactions = RcsInteropCodec.decodeReactions(rcsMsg.reactionsJson)
        assertEquals(listOf("❤️"), reactions.map { it.emoji })
        assertTrue(
            "Folded reaction message must be hidden from visible thread",
            smsDao.getAllMessagesSnapshot().any { it.body.startsWith("Loved") && it.isHiddenReaction }
        )
        }
    }

    @Test
    fun `RcsInteropCodec reactions vCard and Tink EncryptedSettingsStore RCS MMS toggles persist cleanly`(): Unit {
        runBlocking {
        // 1. Encode/parse all 6 quick tapbacks, media fallback, and removals
        RcsInteropCodec.QUICK_TAPBACK_EMOJIS.forEach { emoji ->
            val fallback = RcsInteropCodec.encodeReactionFallback(emoji, "Lunch at 1?")
            val parsed = RcsInteropCodec.parseReactionFallback(fallback)
            assertNotNull(parsed)
            assertEquals(emoji, parsed!!.emoji)
            assertEquals("Lunch at 1?", parsed.quotedText)
            assertFalse(parsed.isRemoval)
        }
        assertEquals("Liked an image", RcsInteropCodec.encodeReactionFallback("👍", "", "image/png"))
        val parsedRemoval = RcsInteropCodec.parseReactionFallback(
            RcsInteropCodec.encodeReactionFallback("😂", "Funny meme", isRemoval = true)
        )
        assertEquals("😂", parsedRemoval?.emoji)
        assertTrue(parsedRemoval?.isRemoval == true)

        // 2. Toggle reactions: add -> replace -> remove
        val (addedJson, removed1) = RcsInteropCodec.toggleReaction(null, "👍", "SELF", "You", true, 100L)
        assertFalse(removed1)
        val (replacedJson, removed2) = RcsInteropCodec.toggleReaction(addedJson, "❤️", "SELF", "You", true, 200L)
        assertFalse(removed2)
        assertEquals(listOf("❤️"), RcsInteropCodec.decodeReactions(replacedJson).map { it.emoji })
        val (clearedJson, removed3) = RcsInteropCodec.toggleReaction(replacedJson, "❤️", "SELF", "You", true, 300L)
        assertTrue(removed3)
        assertNull(clearedJson)

        // 3. vCard & status labels
        val vcf = RcsInteropCodec.buildVCardContent("Bob Patel", "+14155552671")
        assertEquals("Bob Patel" to "+14155552671", RcsInteropCodec.parseVCardSummary(vcf))
        assertEquals("Sending…", RcsInteropCodec.formatDeliveryStatusLabel("SENDING"))
        assertEquals("Sent", RcsInteropCodec.formatDeliveryStatusLabel("SENT"))
        assertEquals("Delivered", RcsInteropCodec.formatDeliveryStatusLabel("DELIVERED"))
        assertEquals("Read", RcsInteropCodec.formatDeliveryStatusLabel("READ"))
        assertEquals("Failed", RcsInteropCodec.formatDeliveryStatusLabel("FAILED"))

        // 4. SmsRepository.sendReaction persists pill and dispatches SMS fallback
        grantDefaultSmsRole()
        val msgId = app.container.database.smsDao().insertMessage(
            SmsMessageEntity(
                threadKey = "NUM_9876543210",
                rawAddress = "+919876543210",
                body = "Are we still on?",
                bodySha256 = BackupCryptoEngine.sha256Hex("Are we still on?"),
                timestampMillis = 1_700_000_000_000L,
                isIncoming = true,
                isRead = true,
                category = SmsCategory.PERSONAL
            )
        )
        assertTrue(app.container.smsRepository.sendReaction(msgId, "👍").isSuccess)
        val updatedMsg = app.container.database.smsDao().getMessageById(msgId)!!
        assertEquals(listOf("👍"), RcsInteropCodec.decodeReactions(updatedMsg.reactionsJson).map { it.emoji })
        val lastSms = Shadows.shadowOf(context.getSystemService(SmsManager::class.java)).lastSentMultipartTextMessageParams
        assertEquals("Liked “Are we still on?”", lastSms.parts.joinToString(""))

        // 5. Tink EncryptedSettingsStore persists & migrates legacy JSON safely
        val storeDir = createIsolatedDir("rcs_settings_store")
        val isolatedCtx = buildIsolatedStoreContext(context, storeDir)
        val store = EncryptedSettingsStore(isolatedCtx, app.container.tinkKeyManager)
        store.updateSettings {
            it.copy(rcsChatsEnabled = false, autoDownloadMms = false, sendReadReceipts = false)
        }
        val reloaded = EncryptedSettingsStore(isolatedCtx, app.container.tinkKeyManager).getSettingsSnapshot()
        assertFalse(reloaded.rcsChatsEnabled)
        assertFalse(reloaded.autoDownloadMms)
        assertFalse(reloaded.sendReadReceipts)

        val legacyBytes = """{"promoAutoDeleteDays":7,"unknownLegacyField":true}""".encodeToByteArray()
        val cipher = app.container.tinkKeyManager.getAead().encrypt(
            legacyBytes,
            "traisms_encrypted_datastore_v1".toByteArray(Charsets.UTF_8)
        )
        val migrated = TinkAppSettingsSerializer(app.container.tinkKeyManager)
            .readFrom(ByteArrayInputStream(cipher))
        assertTrue(migrated.rcsChatsEnabled)
        assertTrue(migrated.autoDownloadMms)
        assertTrue(migrated.sendReadReceipts)
        }
    }

    @Test
    fun `ThreadDetailScreen ComposeSmsScreen ThreadRowItem and SettingsScreen render and trigger MMS RCS actions`(): Unit {
        val imgFile = File(createIsolatedDir("ui_media"), "sample.png").apply {
            writeBytes(createTinyPngBytes())
        }
        val vCardText = RcsInteropCodec.buildVCardContent("Alice Sharma", "+919876543210")

        val incomingPendingMms = SmsMessageEntity(
            id = 11L,
            threadKey = "NUM_9876543210",
            rawAddress = "+919876543210",
            contactName = "Alice Sharma",
            body = "Incoming MMS (4 KB) • 100% Offline Mode",
            bodySha256 = BackupCryptoEngine.sha256Hex("pending"),
            timestampMillis = 1_700_000_010_000L,
            isIncoming = true,
            isRead = false,
            category = SmsCategory.PERSONAL,
            transportType = "MMS",
            deliveryStatus = "PENDING_DOWNLOAD",
            mmsSubject = "Road Trip"
        )
        val richMediaMsg = SmsMessageEntity(
            id = 12L,
            threadKey = "NUM_9876543210",
            rawAddress = "+919876543210",
            contactName = "Alice Sharma",
            body = "Check out these attachments!",
            bodySha256 = BackupCryptoEngine.sha256Hex("rich"),
            timestampMillis = 1_700_000_020_000L,
            isIncoming = true,
            isRead = true,
            category = SmsCategory.PERSONAL,
            transportType = "MMS",
            reactionsJson = RcsInteropCodec.encodeReactions(
                listOf(
                    com.example.data.sms.MessageReaction(
                        emoji = "👍",
                        senderAddress = "SELF",
                        senderDisplay = "You",
                        timestampMillis = 1_700_000_025_000L,
                        isFromSelf = true
                    )
                )
            )
        )
        val quotedOutgoingDelivered = SmsMessageEntity(
            id = 13L,
            threadKey = "NUM_9876543210",
            rawAddress = "+919876543210",
            contactName = "Alice Sharma",
            body = "Awesome!",
            bodySha256 = BackupCryptoEngine.sha256Hex("awesome"),
            timestampMillis = 1_700_000_030_000L,
            isIncoming = false,
            isRead = true,
            category = SmsCategory.PERSONAL,
            transportType = "SMS",
            deliveryStatus = "DELIVERED",
            replyToMessageId = 12L,
            replyToSender = "Alice Sharma",
            replyToSnippet = "Check out these attachments!"
        )
        val failedOutgoingMsg = SmsMessageEntity(
            id = 14L,
            threadKey = "NUM_9876543210",
            rawAddress = "+919876543210",
            contactName = "Alice Sharma",
            body = "Retry me",
            bodySha256 = BackupCryptoEngine.sha256Hex("retry"),
            timestampMillis = 1_700_000_040_000L,
            isIncoming = false,
            isRead = true,
            category = SmsCategory.PERSONAL,
            transportType = "SMS",
            deliveryStatus = "FAILED"
        )
        val attachmentsByMsg = mapOf(
            12L to listOf(
                MmsAttachmentEntity(id = 201L, messageId = 12L, partIndex = 0, contentType = "image/png", fileName = "sample.png", localFilePath = imgFile.absolutePath, sizeBytes = imgFile.length()),
                MmsAttachmentEntity(id = 202L, messageId = 12L, partIndex = 1, contentType = "video/mp4", fileName = "clip.mp4", sizeBytes = 1024L),
                MmsAttachmentEntity(id = 203L, messageId = 12L, partIndex = 2, contentType = "audio/mpeg", fileName = "voice.mp3", sizeBytes = 512L),
                MmsAttachmentEntity(id = 204L, messageId = 12L, partIndex = 3, contentType = "text/x-vcard", fileName = "alice.vcf", sizeBytes = vCardText.length.toLong(), textContent = vCardText)
            )
        )

        val downloadedIds = mutableListOf<Long>()
        val retriedIds = mutableListOf<Long>()
        val toggledReactions = mutableListOf<Pair<Long, String>>()
        val sentQuotes = mutableListOf<Triple<String, String, Long?>>()

        Robolectric.buildActivity(ComponentActivity::class.java).use { controller ->
            controller.setup()
            val host = controller.get()
            try {
                host.setContent {
                    TraiSmsTheme {
                        Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                            ThreadDetailScreen(
                                threadKey = "NUM_9876543210",
                                messages = listOf(incomingPendingMms, richMediaMsg, quotedOutgoingDelivered, failedOutgoingMsg),
                                onBack = {},
                                onMarkThreadRead = {},
                                onSendReply = { _, _, _ -> },
                                onDeleteSingleMessage = {},
                                onToggleArchive = { _, _ -> },
                                onAssignCategory = { _, _, _, _ -> },
                                attachmentsByMessageId = attachmentsByMsg,
                                rcsChatsEnabled = true,
                                systemSmsHelper = systemSmsHelper,
                                onSendReplyWithQuote = { dest, text, quoteId, onRes ->
                                    sentQuotes.add(Triple(dest, text, quoteId))
                                    onRes(Result.success("NUM_9876543210"))
                                },
                                onToggleReaction = { id, emoji -> toggledReactions.add(id to emoji) },
                                onDownloadMms = { id -> downloadedIds.add(id) },
                                onRetryMessage = { id -> retriedIds.add(id) }
                            )
                        }
                    }
                }

                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("mms_download_card_11").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("mms_subject_header_11").assertIsDisplayed()
                composeTestRule.onNodeWithTag("download_mms_button_11").performClick()
                assertEquals(listOf(11L), downloadedIds)

                // Verify media attachments + reaction pill + quoted preview + status labels
                composeTestRule.onNodeWithTag("mms_image_preview_201").performScrollTo().assertIsDisplayed()
                composeTestRule.onNodeWithTag("mms_video_card_202").performScrollTo().assertIsDisplayed()
                composeTestRule.onNodeWithTag("mms_audio_card_203").performScrollTo().assertIsDisplayed()
                composeTestRule.onNodeWithTag("mms_file_card_204").performScrollTo().assertIsDisplayed()
                composeTestRule.onNodeWithTag("reaction_pills_row_12").performScrollTo().assertIsDisplayed()
                composeTestRule.onNodeWithTag("reaction_pill_12_👍").assertIsDisplayed()
                composeTestRule.onNodeWithTag("quoted_reply_preview_13").performScrollTo().assertIsDisplayed()
                composeTestRule.onNodeWithTag("message_status_13").performScrollTo().assertTextContains("Delivered", substring = true)
                composeTestRule.onNodeWithTag("message_status_14").performScrollTo().assertTextContains("Failed", substring = true)
                composeTestRule.onNodeWithTag("retry_failed_msg_14").performScrollTo().performClick()
                assertEquals(listOf(14L), retriedIds)

                // Emoji reaction bar + tapback selection
                composeTestRule.onNodeWithTag("react_msg_button_12").performScrollTo().performClick()
                composeTestRule.onNodeWithTag("emoji_reaction_bar_12").assertIsDisplayed()
                composeTestRule.onNodeWithTag("reaction_emoji_12_1").performClick()
                assertEquals(listOf(12L to "❤️"), toggledReactions)

                // Reply banner + cancel + send quoted reply
                composeTestRule.onNodeWithTag("reply_msg_button_12").performScrollTo().performClick()
                composeTestRule.onNodeWithTag("reply_context_banner").assertIsDisplayed()
                composeTestRule.onNodeWithTag("cancel_reply_button").performClick()
                composeTestRule.onAllNodesWithTag("reply_context_banner").assertCountEquals(0)

                composeTestRule.onNodeWithTag("reply_msg_button_12").performScrollTo().performClick()
                composeTestRule.onNodeWithTag("thread_reply_input").performTextInput("Sounds good!")
                composeTestRule.onNodeWithTag("thread_send_button").performClick()
                assertEquals(listOf(Triple("+919876543210", "Sounds good!", 12L)), sentQuotes)

                // Long-press preserves strictly Copy & Forward prompt contract
                composeTestRule.onNodeWithTag("thread_message_bubble_13").performScrollTo().performTouchInput { longClick() }
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("message_actions_prompt").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("message_action_copy").assertIsDisplayed()
                composeTestRule.onNodeWithTag("message_action_forward").assertIsDisplayed()
            } finally {
                host.setContentView(View(host))
                flushMainLooperAndSnapshots()
            }
        }

        // ComposeSmsScreen: group MMS chips, subject, attachment picker sheet, and ThreadRowItem + SettingsScreen
        val mmsSentCalls = mutableListOf<Triple<List<String>, String?, String>>()
        Robolectric.buildActivity(ComponentActivity::class.java).use { controller ->
            controller.setup()
            val host = controller.get()
            var uiSettings by mutableStateOf(AppSettings())
            var mode by mutableStateOf("COMPOSE")
            try {
                host.setContent {
                    TraiSmsTheme {
                        Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                            when (mode) {
                                "COMPOSE" -> ComposeSmsScreen(
                                    initialRecipient = "",
                                    initialBody = "",
                                    contactCache = ContactLookupCache(context),
                                    onBack = {},
                                    onSendSms = { _, _, _ -> },
                                    systemSmsHelper = systemSmsHelper,
                                    onSendMms = { recipients, body, subject, _, onRes ->
                                        mmsSentCalls.add(Triple(recipients, subject, body))
                                        onRes(Result.success("GRP_TEST"))
                                    }
                                )
                                "ROW_AND_SETTINGS" -> Column {
                                    ThreadRowItem(
                                        thread = ThreadSummary(
                                            latestMessageId = 1L,
                                            threadKey = "RCS_THREAD",
                                            rawAddress = "+919876543210",
                                            contactName = "Alice",
                                            latestBody = "Photo attached",
                                            latestTimestampMillis = 1_700_000_000_000L,
                                            category = SmsCategory.PERSONAL,
                                            extractedOtp = null,
                                            isArchived = false,
                                            unreadCount = 1,
                                            messageCount = 2,
                                            classificationSource = "TEST",
                                            transportType = "RCS",
                                            hasAttachments = true
                                        ),
                                        isArchiveScreen = false,
                                        leftSwipeAction = LeftSwipeAction.ARCHIVE,
                                        showCategoryPill = true,
                                        onClick = {},
                                        onCopyOtp = {},
                                        onSwipeLeftAction = {}
                                    )
                                    SettingsScreen(
                                        settings = uiSettings,
                                        isDefaultSmsApp = true,
                                        detectedRegionLabel = "IN • India",
                                        totalMessageCount = 2,
                                        systemSmsHelper = systemSmsHelper,
                                        onRefreshRoleStatus = {},
                                        onUpdatePromoAutoDelete = {},
                                        onUpdateLeftSwipeAction = {},
                                        onUpdateUnarchiveOnNewSms = {},
                                        onUpdateRegionOverride = {},
                                        onUpdateBackupSchedule = { _, _ -> },
                                        onNavigateToBackupTab = {},
                                        onToggleAppLock = {},
                                        onRerunCategorization = {},
                                        onDeleteAllMessagesInApp = {},
                                        onUpdateRcsChatsEnabled = { v -> uiSettings = uiSettings.copy(rcsChatsEnabled = v) },
                                        onUpdateAutoDownloadMms = { v -> uiSettings = uiSettings.copy(autoDownloadMms = v) },
                                        onUpdateSendReadReceipts = { v -> uiSettings = uiSettings.copy(sendReadReceipts = v) }
                                    )
                                }
                            }
                        }
                    }
                }
                composeTestRule.waitForIdle()

                // Open attachment picker bottom sheet and verify all 4 options
                composeTestRule.onNodeWithTag("compose_attach_button").performClick()
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("attachment_picker_sheet").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("attach_option_media").assertIsDisplayed()
                composeTestRule.onNodeWithTag("attach_option_file").assertIsDisplayed()
                composeTestRule.onNodeWithTag("attach_option_contact").assertIsDisplayed()
                composeTestRule.onNodeWithTag("attach_option_location").assertIsDisplayed()
                // Dismiss sheet cleanly by pressing back on host
                host.onBackPressedDispatcher.onBackPressed()
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("attachment_picker_sheet").fetchSemanticsNodes().isEmpty()
                }

                // Group recipients + MMS subject + Send MMS
                composeTestRule.onNodeWithTag("compose_recipient_input").performTextInput("+919876543210")
                composeTestRule.onNodeWithTag("compose_add_group_recipient_button").performClick()
                composeTestRule.onNodeWithTag("compose_group_recipients_row").assertIsDisplayed()
                composeTestRule.onNodeWithTag("compose_recipient_input").performTextInput("+919820112233")
                composeTestRule.onNodeWithTag("compose_toggle_subject_button").performClick()
                composeTestRule.onNodeWithTag("compose_mms_subject_input").performTextInput("Weekend")
                composeTestRule.onNodeWithTag("compose_body_input").performTextInput("Plan confirmed")
                composeTestRule.onNodeWithTag("compose_send_button").assertIsEnabled().performClick()
                assertEquals(
                    listOf(Triple(listOf("+919876543210", "+919820112233"), "Weekend", "Plan confirmed")),
                    mmsSentCalls
                )

                // Switch to ThreadRowItem + SettingsScreen RCS/MMS card toggles
                composeTestRule.runOnIdle { mode = "ROW_AND_SETTINGS" }
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("thread_transport_badge_RCS_THREAD").assertIsDisplayed()
                composeTestRule.onNodeWithTag("thread_attachment_icon_RCS_THREAD").assertIsDisplayed()

                composeTestRule.onNodeWithTag("rcs_mms_settings_card").performScrollTo().assertIsDisplayed()
                composeTestRule.onNodeWithTag("rcs_chats_switch").performScrollTo().assertIsOn().performClick()
                composeTestRule.onNodeWithTag("auto_download_mms_switch").performScrollTo().assertIsOn().performClick()
                composeTestRule.onNodeWithTag("send_read_receipts_switch").performScrollTo().assertIsOn().performClick()
                composeTestRule.waitForIdle()

                composeTestRule.onNodeWithTag("rcs_chats_switch").assertIsOff()
                composeTestRule.onNodeWithTag("auto_download_mms_switch").assertIsOff()
                composeTestRule.onNodeWithTag("send_read_receipts_switch").assertIsOff()
            } finally {
                host.setContentView(View(host))
                flushMainLooperAndSnapshots()
            }
        }
    }

    @Test
    fun `OfflineLocationTextDialog mounts AlertDialog safely without Compose waitForIdle deadlock`(): Unit {
        ShadowDialog.reset()
        Robolectric.buildActivity(ComponentActivity::class.java).use { controller ->
            controller.setup()
            val host = controller.get()
            var showDialog by mutableStateOf(true)
            try {
                host.setContent {
                    TraiSmsTheme {
                        if (showDialog) {
                            OfflineLocationTextDialog(
                                onDismiss = { showDialog = false },
                                onConfirmLocation = { showDialog = false }
                            )
                        }
                    }
                }
                flushMainLooperAndSnapshots()
                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull("Expected OfflineLocationTextDialog AlertDialog", dialog)
                assertTrue(dialog.isShowing)
                val dialogWindow = dialog.window
                assertNotNull("Expected OfflineLocationTextDialog to have an Android Window", dialogWindow)
                assertEquals(
                    "OfflineLocationTextDialog window must not set FLAG_SECURE",
                    0,
                    dialogWindow!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
                )
            } finally {
                showDialog = false
                flushMainLooperAndSnapshots()
                runCatching { ShadowDialog.getLatestDialog()?.dismiss() }
                host.setContentView(View(host))
                flushMainLooperAndSnapshots()
            }
        }
    }

    @Test
    fun `MainActivity launches Default SMS chooser on cold start and onStart return without looping on dismissal or when locked or already default`(): Unit {
        runBlocking {
        // Verify fallback intent carries ACTION_CHANGE_DEFAULT + package name
        val fallbackIntent = systemSmsHelper.createFallbackDefaultSmsIntent()
        assertEquals(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT, fallbackIntent.action)
        assertEquals(context.packageName, fallbackIntent.getStringExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME))

        // 1. Cold start when NOT default SMS app -> pops chooser once settings emit
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            val shadowActivity = Shadows.shadowOf(activity)
            try {
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    shadowActivity.peekNextStartedActivityForResult() != null
                }
                val firstLaunch = shadowActivity.nextStartedActivityForResult
                assertNotNull(firstLaunch)
                assertTrue(
                    firstLaunch.intent.action == Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT ||
                        firstLaunch.intent.getStringExtra("android.intent.extra.ROLE_NAME") == RoleManager.ROLE_SMS ||
                        firstLaunch.intent.action?.contains("ROLE", ignoreCase = true) == true
                )

                // 2. Dismissing chooser via onPause -> onResume must NOT relaunch (anti-loop guard)
                controller.pause().resume()
                flushMainLooperAndSnapshots()
                composeTestRule.waitForIdle()
                assertNull(
                    "onPause->onResume dismissal must not trigger infinite relaunch",
                    shadowActivity.peekNextStartedActivityForResult()
                )

                // 3. Full-screen chooser onStop -> ActivityResult(CANCELED) -> onStart -> onResume must NOT relaunch
                controller.pause().stop()
                activity.activityResultRegistry.dispatchResult(
                    firstLaunch.requestCode,
                    Activity.RESULT_CANCELED,
                    null
                )
                controller.start().resume()
                flushMainLooperAndSnapshots()
                composeTestRule.waitForIdle()
                assertNull(
                    "Returning from chooser ActivityResult must not loop",
                    shadowActivity.peekNextStartedActivityForResult()
                )

                // 4. Genuine backgrounding (onStop -> onStart while NOT requesting role) prompts once again
                controller.pause().stop()
                controller.start().resume()
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    shadowActivity.peekNextStartedActivityForResult() != null
                }
                val relaunched = shadowActivity.nextStartedActivityForResult
                assertNotNull("Expected chooser prompt on foreground return after onStop->onStart", relaunched)
                activity.activityResultRegistry.dispatchResult(
                    relaunched.requestCode,
                    Activity.RESULT_CANCELED,
                    null
                )
            } finally {
                activity.setContentView(View(activity))
                flushMainLooperAndSnapshots()
            }
        }

        // 5. Locked behind AppLockGateScreen -> does NOT pop chooser while locked
        app.container.settingsStore.updateSettings { it.copy(appLockEnabled = true) }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        Shadows.shadowOf(keyguard).setIsDeviceSecure(true)
        val biometricManager = context.getSystemService(BiometricManager::class.java)
        val shadowBiometric = Shadow.extract<ShadowBiometricManager>(biometricManager)
        shadowBiometric.setCanAuthenticate(true)
        shadowBiometric.setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            try {
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("app_lock_gate_screen").fetchSemanticsNodes().isNotEmpty()
                }
                flushMainLooperAndSnapshots()
                assertNull(
                    "Default SMS chooser must wait until AppLockGateScreen is unlocked",
                    Shadows.shadowOf(activity).peekNextStartedActivityForResult()
                )
            } finally {
                activity.setContentView(View(activity))
                flushMainLooperAndSnapshots()
            }
        }

        // 6. Already Default SMS app -> never pops chooser on cold start or foreground return
        app.container.settingsStore.updateSettings { it.copy(appLockEnabled = false) }
        ShadowBiometricManager.reset()
        Shadows.shadowOf(keyguard).setIsDeviceSecure(false)
        grantDefaultSmsRole()

        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            try {
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("bottom_navigation_bar").fetchSemanticsNodes().isNotEmpty()
                }
                controller.pause().stop()
                controller.start().resume()
                flushMainLooperAndSnapshots()
                composeTestRule.waitForIdle()
                assertNull(
                    "Already Default SMS app must never show chooser",
                    Shadows.shadowOf(activity).peekNextStartedActivityForResult()
                )
            } finally {
                activity.setContentView(View(activity))
                flushMainLooperAndSnapshots()
            }
        }
        }
    }

    private fun grantDefaultSmsRole() {
        ShadowRoleManager.addRoleHolder(
            RoleManager.ROLE_SMS,
            context.packageName,
            Process.myUserHandle()
        )
    }

    private fun createTinyPngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun buildBinaryNotificationIndPdu(
        transactionId: String,
        fromAddress: String,
        contentLocation: String,
        subject: String,
        sizeBytes: Long,
        epochSeconds: Long
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x8C); out.write(0x82) // M-Notification.ind
        out.write(0x98); writeTextStr(out, transactionId)
        out.write(0x8D); out.write(0x90) // MMS 1.0
        out.write(0x89)
        val fromPayload = ByteArrayOutputStream().apply {
            write(0x80)
            writeTextStr(this, "$fromAddress/TYPE=PLMN")
        }.toByteArray()
        out.write(fromPayload.size); out.write(fromPayload)
        out.write(0x96); writeTextStr(out, subject)
        out.write(0x8E); writeLongInt(out, sizeBytes)
        out.write(0x85); writeLongInt(out, epochSeconds)
        out.write(0x83); writeTextStr(out, contentLocation)
        return out.toByteArray()
    }

    private fun buildBinaryRetrieveConfPdu(
        messageId: String,
        transactionId: String,
        fromAddress: String,
        toAddress: String,
        subject: String,
        epochSeconds: Long,
        bodyText: String,
        imageBytes: ByteArray
    ): ByteArray {
        // Reuse MmsPduCodec.encodeSendReq multipart payload after swapping header to RETRIEVE_CONF (0x84)
        val sendReq = MmsPduCodec.encodeSendReq(
            MmsSendReqInput(
                transactionId = transactionId,
                toAddresses = listOf(toAddress),
                fromAddress = fromAddress,
                subject = subject,
                epochSeconds = epochSeconds,
                deliveryReport = false,
                readReport = false,
                parts = listOf(
                    MmsPart("text/plain", contentId = "<t0>", contentLocation = "t0.txt", fileName = "t0.txt", data = bodyText.toByteArray(Charsets.UTF_8)),
                    MmsPart("image/png", contentId = "<img0>", contentLocation = "pic.png", fileName = "pic.png", data = imageBytes)
                )
            )
        )
        // Patch message-type byte (index 1: 0x80 -> 0x84) and prepend Message-Id header right after Transaction-Id
        val patched = sendReq.copyOf()
        patched[1] = 0x84.toByte()
        // Append Message-Id via a clean header prefix before existing headers after type
        val prefix = ByteArrayOutputStream()
        prefix.write(0x8C); prefix.write(0x84)
        prefix.write(0x8B); writeTextStr(prefix, messageId)
        prefix.write(patched, 2, patched.size - 2)
        return prefix.toByteArray()
    }

    private fun buildBinarySendConfPdu(messageId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x8C); out.write(0x81) // M-Send.conf
        out.write(0x98); writeTextStr(out, "tx_conf")
        out.write(0x8D); out.write(0x90)
        out.write(0x92); out.write(0x80) // Response-Status: OK
        out.write(0x8B); writeTextStr(out, messageId)
        return out.toByteArray()
    }

    private fun buildBinaryDeliveryIndPdu(messageId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x8C); out.write(0x86) // M-Delivery.ind
        out.write(0x8D); out.write(0x90)
        out.write(0x8B); writeTextStr(out, messageId)
        out.write(0x95); out.write(0x81) // Retrieved
        return out.toByteArray()
    }

    private fun buildBinaryReadOrigIndPdu(messageId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x8C); out.write(0x88) // M-Read-Orig.ind
        out.write(0x8D); out.write(0x92)
        out.write(0x8B); writeTextStr(out, messageId)
        out.write(0x9B); out.write(0x80) // Read
        return out.toByteArray()
    }

    private fun writeTextStr(out: ByteArrayOutputStream, s: String) {
        out.write(s.toByteArray(Charsets.UTF_8))
        out.write(0)
    }

    private fun writeLongInt(out: ByteArrayOutputStream, value: Long) {
        if (value == 0L) {
            out.write(1); out.write(0); return
        }
        val bytes = mutableListOf<Int>()
        var rem = value
        while (rem > 0L) {
            bytes.add((rem and 0xFFL).toInt())
            rem = rem ushr 8
        }
        out.write(bytes.size)
        for (i in bytes.indices.reversed()) out.write(bytes[i])
    }

    private fun createIsolatedDir(prefix: String): File {
        val dir = File(context.cacheDir, "${prefix}_${System.nanoTime()}").apply { mkdirs() }
        tempStorageDirs.add(dir)
        return dir
    }

    private fun buildIsolatedStoreContext(base: Context, storageDir: File): Context {
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = storageDir
        }
    }

    private fun createComposeSnapshotSyncRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                flushMainLooperAndSnapshots()
                val pendingApply = AtomicBoolean(false)
                val observerHandle: ObserverHandle = Snapshot.registerGlobalWriteObserver {
                    if (pendingApply.compareAndSet(false, true)) {
                        Handler(Looper.getMainLooper()).post {
                            pendingApply.set(false)
                            Snapshot.sendApplyNotifications()
                        }
                    }
                }
                try {
                    base.evaluate()
                } finally {
                    observerHandle.dispose()
                    runCatching { flushMainLooperAndSnapshots() }
                }
            }
        }
    }

    private fun flushMainLooperAndSnapshots() {
        runCatching {
            val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor]
            if (dispatcher != null) {
                val callbackField = dispatcher.javaClass.getDeclaredField("dispatchCallback").apply {
                    isAccessible = true
                }
                (callbackField.get(dispatcher) as? Runnable)?.run()
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun waitForCondition(
        timeoutMillis: Long = 5_000L,
        predicate: suspend () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(25)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition was not satisfied within ${timeoutMillis}ms", predicate())
    }

    private class FakeSmsContentProvider : ContentProvider() {
        data class SmsRow(val id: Long, val address: String, val body: String, val dateMillis: Long, val type: Int, var read: Int)
        private val nextId = AtomicLong(1000L)
        private val rows = ConcurrentHashMap<Long, SmsRow>()

        override fun onCreate(): Boolean = true
        fun clear() = rows.clear()
        fun seedExistingMessage(id: Long, address: String, body: String, dateMillis: Long, type: Int, read: Int) {
            rows[id] = SmsRow(id, address, body, dateMillis, type, read)
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val c = MatrixCursor(arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ))
            rows.values.sortedByDescending { it.dateMillis }.forEach {
                c.addRow(arrayOf<Any?>(it.id, it.address, it.body, it.dateMillis, it.type, it.read))
            }
            return c
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val id = nextId.incrementAndGet()
            val v = values ?: ContentValues()
            rows[id] = SmsRow(
                id,
                v.getAsString(Telephony.Sms.ADDRESS).orEmpty(),
                v.getAsString(Telephony.Sms.BODY).orEmpty(),
                v.getAsLong(Telephony.Sms.DATE) ?: 0L,
                v.getAsInteger(Telephony.Sms.TYPE) ?: Telephony.Sms.MESSAGE_TYPE_INBOX,
                v.getAsInteger(Telephony.Sms.READ) ?: 0
            )
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private class FakeMmsContentProvider(private val blobDir: File) : ContentProvider() {
        data class MmsRow(val id: Long, val threadId: Long, val dateSec: Long, val box: Int, var read: Int, val subject: String?, val creator: String)
        data class AddrRow(val mmsId: Long, val address: String, val type: Int)
        data class PartRow(val partId: Long, val mmsId: Long, val ct: String, val name: String?, val text: String?, val blobFile: File?)

        private val nextMmsId = AtomicLong(2000L)
        private val nextPartId = AtomicLong(3000L)
        private val mmsRows = ConcurrentHashMap<Long, MmsRow>()
        private val addrRows = mutableListOf<AddrRow>()
        private val partRows = ConcurrentHashMap<Long, PartRow>()

        override fun onCreate(): Boolean = true
        fun clear() {
            mmsRows.clear()
            addrRows.clear()
            partRows.clear()
        }

        fun seedMms(
            id: Long,
            threadId: Long,
            dateSec: Long,
            box: Int,
            read: Int,
            subject: String?,
            creator: String,
            fromAddress: String,
            toAddresses: List<String>,
            textBody: String,
            extraPartMime: String? = null,
            extraPartBytes: ByteArray? = null
        ) {
            mmsRows[id] = MmsRow(id, threadId, dateSec, box, read, subject, creator)
            addrRows.add(AddrRow(id, fromAddress, 137))
            toAddresses.forEach { addrRows.add(AddrRow(id, it, 151)) }
            val textPartId = nextPartId.incrementAndGet()
            partRows[textPartId] = PartRow(textPartId, id, "text/plain", "body.txt", textBody, null)
            if (extraPartMime != null && extraPartBytes != null) {
                val pId = nextPartId.incrementAndGet()
                val f = File(blobDir, "part_$pId.bin").apply { writeBytes(extraPartBytes) }
                partRows[pId] = PartRow(pId, id, extraPartMime, "rcs.dat", null, f)
            }
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val segs = uri.pathSegments
            if (segs.size == 2 && segs[1] == "addr") {
                val mmsId = segs[0].toLongOrNull() ?: -1L
                val c = MatrixCursor(arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE))
                addrRows.filter { it.mmsId == mmsId }.forEach { c.addRow(arrayOf<Any?>(it.address, it.type)) }
                return c
            }
            if (segs.size == 1 && segs[0] == "part") {
                val mmsId = selectionArgs?.firstOrNull()?.toLongOrNull() ?: -1L
                val c = MatrixCursor(arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.NAME, Telephony.Mms.Part.CONTENT_LOCATION, Telephony.Mms.Part.CONTENT_ID, Telephony.Mms.Part.TEXT))
                partRows.values.filter { it.mmsId == mmsId }.forEach {
                    c.addRow(arrayOf<Any?>(it.partId, it.ct, it.name, it.name, null, it.text))
                }
                return c
            }
            val c = MatrixCursor(arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ, Telephony.Mms.SUBJECT, Telephony.Mms.CREATOR))
            mmsRows.values.sortedByDescending { it.dateSec }.forEach {
                c.addRow(arrayOf<Any?>(it.id, it.threadId, it.dateSec, it.box, it.read, it.subject, it.creator))
            }
            return c
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val segs = uri.pathSegments
            if (segs.size == 2 && segs[0] == "part") {
                val pId = segs[1].toLongOrNull() ?: return null
                val file = partRows[pId]?.blobFile ?: File(blobDir, "part_$pId.bin").also {
                    if (!it.exists()) it.createNewFile()
                    partRows[pId] = partRows[pId]?.copy(blobFile = it) ?: PartRow(pId, 0L, "application/octet-stream", null, null, it)
                }
                val pfdMode = if (mode.contains("w")) ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE else ParcelFileDescriptor.MODE_READ_ONLY
                return ParcelFileDescriptor.open(file, pfdMode)
            }
            return null
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val segs = uri.pathSegments
            val v = values ?: ContentValues()
            if (segs.size == 2 && segs[1] == "addr") {
                val mmsId = segs[0].toLongOrNull() ?: return null
                addrRows.add(AddrRow(mmsId, v.getAsString(Telephony.Mms.Addr.ADDRESS).orEmpty(), v.getAsInteger(Telephony.Mms.Addr.TYPE) ?: 151))
                return uri
            }
            if (segs.size == 2 && segs[1] == "part") {
                val mmsId = segs[0].toLongOrNull() ?: return null
                val pId = nextPartId.incrementAndGet()
                val f = File(blobDir, "part_$pId.bin").apply { createNewFile() }
                partRows[pId] = PartRow(pId, mmsId, v.getAsString(Telephony.Mms.Part.CONTENT_TYPE).orEmpty(), v.getAsString(Telephony.Mms.Part.NAME), v.getAsString(Telephony.Mms.Part.TEXT), f)
                return Uri.parse("content://mms/part/$pId")
            }
            val id = nextMmsId.incrementAndGet()
            mmsRows[id] = MmsRow(
                id,
                0L,
                v.getAsLong(Telephony.Mms.DATE) ?: 0L,
                v.getAsInteger(Telephony.Mms.MESSAGE_BOX) ?: Telephony.Mms.MESSAGE_BOX_INBOX,
                v.getAsInteger(Telephony.Mms.READ) ?: 0,
                v.getAsString(Telephony.Mms.SUBJECT),
                "com.traisms.app"
            )
            return ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private class FakeContactsContentProvider : ContentProvider() {
        private var list: List<Pair<String, String>> = emptyList()
        override fun onCreate(): Boolean = true
        fun setContacts(items: List<Pair<String, String>>) { list = items }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val c = MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER))
            list.forEach { (n, p) -> c.addRow(arrayOf(n, p)) }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
