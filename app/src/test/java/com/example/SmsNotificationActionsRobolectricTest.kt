package com.example

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.sms.SmsNotificationActionReceiver
import com.example.data.sms.SmsNotificationHelper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class SmsNotificationActionsRobolectricTest {

    @get:Rule(order = 0)
    val snapshotSyncRule: TestRule = createComposeSnapshotSyncRule()

    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var app: TraiSmsApp
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationHelper: SmsNotificationHelper

    @Before
    fun setUp(): Unit {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            app = context as TraiSmsApp
            Shadows.shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.cancelAll()
            notificationHelper = SmsNotificationHelper(context)

            app.container.database.smsDao().deleteAllMessages()
            app.container.settingsStore.updateSettings {
                AppSettings(
                    appLockEnabled = false,
                    initialSyncCompleted = true,
                    onboardingDismissed = true
                )
            }
        }
    }

    @After
    fun tearDown(): Unit {
        runBlocking {
            notificationManager.cancelAll()
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            Shadows.shadowOf(keyguard).setIsDeviceSecure(false)
            ShadowBiometricManager.reset()
            app.container.database.smsDao().deleteAllMessages()
            app.container.settingsStore.updateSettings {
                it.copy(
                    appLockEnabled = false,
                    initialSyncCompleted = true,
                    onboardingDismissed = true
                )
            }
        }
    }

    @Test
    fun `non-OTP and App-Locked notifications expose 3 standard actions without Copy OTP`(): Unit {
        val threadKey = "AD-ICICIB-T"
        notificationHelper.postIncomingSmsNotification(
            messageId = 101L,
            threadKey = threadKey,
            rawAddress = threadKey,
            senderTitle = threadKey,
            body = "Acct XX1234 debited for INR 1,250.00 via UPI.",
            category = SmsCategory.TRANSACTIONAL,
            extractedOtp = null,
            redactPreviewForAppLock = false
        )

        val notifId = SmsNotificationHelper.notificationIdForThread(threadKey)
        val nonOtpNotif = Shadows.shadowOf(notificationManager).getNotification(notifId)
        assertNotNull(nonOtpNotif)
        assertNull(nonOtpNotif.bigContentView)

        val actionTitles = nonOtpNotif.actions?.map { it.title.toString() }.orEmpty()
        assertEquals(listOf("Archive", "Assign Category", "Delete"), actionTitles)
        assertFalse(actionTitles.contains("Copy OTP"))

        // App-Locked OTP notification must redact OTP preview and omit Copy OTP button
        val lockedThreadKey = "VM-HDFCBK-T"
        notificationHelper.postIncomingSmsNotification(
            messageId = 102L,
            threadKey = lockedThreadKey,
            rawAddress = lockedThreadKey,
            senderTitle = lockedThreadKey,
            body = "918273 is your OTP for netbanking login.",
            category = SmsCategory.OTP,
            extractedOtp = "918273",
            redactPreviewForAppLock = true
        )

        val lockedNotif = Shadows.shadowOf(notificationManager).getNotification(
            SmsNotificationHelper.notificationIdForThread(lockedThreadKey)
        )
        assertNotNull(lockedNotif)
        assertNull(lockedNotif.bigContentView)
        assertEquals(
            listOf("Archive", "Assign Category", "Delete"),
            lockedNotif.actions?.map { it.title.toString() }.orEmpty()
        )
        val extrasTitle = lockedNotif.extras.getCharSequence("android.title")?.toString().orEmpty()
        val extrasText = lockedNotif.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertFalse(extrasTitle.contains("918273"))
        assertFalse(extrasText.contains("918273"))
    }

    @Test
    fun `unlocked OTP notification exposes all 4 clickable RemoteViews buttons with zero duplicate standard actions`(): Unit {
        val threadKey = "VM-HDFCBK-T"
        notificationHelper.postIncomingSmsNotification(
            messageId = 201L,
            threadKey = threadKey,
            rawAddress = threadKey,
            senderTitle = "HDFC Bank",
            body = "482910 is your One Time Password for txn.",
            category = SmsCategory.OTP,
            extractedOtp = "482910",
            redactPreviewForAppLock = false
        )

        val notifId = SmsNotificationHelper.notificationIdForThread(threadKey)
        val otpNotif = Shadows.shadowOf(notificationManager).getNotification(notifId)
        assertNotNull(otpNotif)
        assertTrue(otpNotif.actions.isNullOrEmpty())
        assertNotNull(otpNotif.contentView)
        assertNotNull(otpNotif.bigContentView)
        assertNotNull(otpNotif.headsUpContentView)

        val collapsedRoot = otpNotif.contentView.apply(context, FrameLayout(context))
        assertEquals(
            "HDFC Bank • OTP: 482910",
            collapsedRoot.findViewById<TextView>(R.id.notif_collapsed_title).text.toString()
        )

        val expandedRoot = otpNotif.bigContentView.apply(context, FrameLayout(context))
        val copyBtn = expandedRoot.findViewById<TextView>(R.id.notif_btn_copy_otp)
        val archiveBtn = expandedRoot.findViewById<TextView>(R.id.notif_btn_archive)
        val assignBtn = expandedRoot.findViewById<TextView>(R.id.notif_btn_assign_category)
        val deleteBtn = expandedRoot.findViewById<TextView>(R.id.notif_btn_delete)

        assertEquals("Copy OTP", copyBtn.text.toString())
        assertEquals("Archive", archiveBtn.text.toString())
        assertEquals("Assign Category", assignBtn.text.toString())
        assertEquals("Delete", deleteBtn.text.toString())
        assertTrue(copyBtn.isClickable)
        assertTrue(archiveBtn.isClickable)
        assertTrue(assignBtn.isClickable)
        assertTrue(deleteBtn.isClickable)

        val shadowApp = Shadows.shadowOf(app as Application)
        shadowApp.clearBroadcastIntents()
        copyBtn.performClick()
        archiveBtn.performClick()
        deleteBtn.performClick()

        val broadcastActions = shadowApp.broadcastIntents.map { it.action }
        assertEquals(
            listOf(
                SmsNotificationHelper.ACTION_COPY_OTP,
                SmsNotificationHelper.ACTION_ARCHIVE,
                SmsNotificationHelper.ACTION_DELETE
            ),
            broadcastActions
        )

        assignBtn.performClick()
        val startedActivityIntent = shadowApp.nextStartedActivity
        assertNotNull(startedActivityIntent)
        assertEquals(SmsNotificationHelper.ACTION_ASSIGN_CATEGORY, startedActivityIntent.action)
        assertEquals(MainActivity::class.java.name, startedActivityIntent.component?.className)
    }

    @Test
    fun `ACTION_COPY_OTP copies exact OTP with EXTRA_IS_SENSITIVE and cancels notification`(): Unit {
        // Use a threadKey with a negative hashCode() to verify hasExtra(EXTRA_NOTIFICATION_ID) handling
        val threadKey = "polygenelubricants"
        assertTrue(SmsNotificationHelper.notificationIdForThread(threadKey) < 0)

        notificationHelper.postIncomingSmsNotification(
            messageId = 301L,
            threadKey = threadKey,
            rawAddress = threadKey,
            senderTitle = "Bank OTP",
            body = "Your login OTP is 739104.",
            category = SmsCategory.OTP,
            extractedOtp = "739104",
            redactPreviewForAppLock = false
        )

        val notifId = SmsNotificationHelper.notificationIdForThread(threadKey)
        val otpNotif = Shadows.shadowOf(notificationManager).getNotification(notifId)
        assertNotNull(otpNotif)

        val shadowApp = Shadows.shadowOf(app as Application)
        shadowApp.clearBroadcastIntents()
        val expandedRoot = otpNotif.bigContentView.apply(context, FrameLayout(context))
        expandedRoot.findViewById<TextView>(R.id.notif_btn_copy_otp).performClick()

        val copyIntent = shadowApp.broadcastIntents.single {
            it.action == SmsNotificationHelper.ACTION_COPY_OTP
        }
        SmsNotificationActionReceiver().onReceive(context, copyIntent)

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("739104", clip!!.getItemAt(0).text.toString())
        assertEquals(
            true,
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)
        )
        assertNull(Shadows.shadowOf(notificationManager).getNotification(notifId))
    }

    @Test
    fun `ACTION_ARCHIVE marks conversation archived in Room DB and cancels notification`(): Unit {
        runBlocking {
        val threadKey = "AX-AIRTEL-S"
        val insertedId = insertTestMessage(
            threadKey = threadKey,
            body = "Your fiber bill is ready.",
            category = SmsCategory.SERVICE
        )

        notificationHelper.postIncomingSmsNotification(
            messageId = insertedId,
            threadKey = threadKey,
            rawAddress = threadKey,
            senderTitle = threadKey,
            body = "Your fiber bill is ready.",
            category = SmsCategory.SERVICE,
            extractedOtp = null,
            redactPreviewForAppLock = false
        )

        val notifId = SmsNotificationHelper.notificationIdForThread(threadKey)
        val notif = Shadows.shadowOf(notificationManager).getNotification(notifId)
        assertNotNull(notif)

        val archiveAction = notif.actions.first { it.title.toString() == "Archive" }
        val archiveIntent = Shadows.shadowOf(archiveAction.actionIntent).savedIntent
        SmsNotificationActionReceiver().onReceive(context, archiveIntent)

        assertNull(Shadows.shadowOf(notificationManager).getNotification(notifId))
        waitForCondition {
            app.container.database.smsDao().isThreadCurrentlyArchived(threadKey)
        }
        assertTrue(app.container.database.smsDao().isThreadCurrentlyArchived(threadKey))
        }
    }

    @Test
    fun `ACTION_DELETE removes target message from Room DB and cancels notification`(): Unit {
        runBlocking {
        val threadKey = "JD-MYNTRA-P"
        val olderId = insertTestMessage(
            threadKey = threadKey,
            body = "Previous promo message",
            category = SmsCategory.PROMOTIONAL,
            timestampMillis = 1_700_000_000_000L
        )
        val targetId = insertTestMessage(
            threadKey = threadKey,
            body = "Flash sale 50% off today!",
            category = SmsCategory.PROMOTIONAL,
            timestampMillis = 1_700_000_060_000L
        )

        notificationHelper.postIncomingSmsNotification(
            messageId = targetId,
            threadKey = threadKey,
            rawAddress = threadKey,
            senderTitle = threadKey,
            body = "Flash sale 50% off today!",
            category = SmsCategory.PROMOTIONAL,
            extractedOtp = null,
            redactPreviewForAppLock = false
        )

        val notifId = SmsNotificationHelper.notificationIdForThread(threadKey)
        val notif = Shadows.shadowOf(notificationManager).getNotification(notifId)
        assertNotNull(notif)

        val deleteAction = notif.actions.first { it.title.toString() == "Delete" }
        val deleteIntent = Shadows.shadowOf(deleteAction.actionIntent).savedIntent
        SmsNotificationActionReceiver().onReceive(context, deleteIntent)

        assertNull(Shadows.shadowOf(notificationManager).getNotification(notifId))
        waitForCondition {
            app.container.database.smsDao().observeThreadMessages(threadKey).first().size == 1
        }
        val remaining = app.container.database.smsDao().observeThreadMessages(threadKey).first()
        assertEquals(listOf(olderId), remaining.map { it.id })
        }
    }

    @Test
    fun `MainActivity ACTION_ASSIGN_CATEGORY cancels notification and opens MoveSenderCategoryBottomSheet and applies category`(): Unit {
        runBlocking {
        val threadKey = "+919899001122"
        val msgId = insertTestMessage(
            threadKey = threadKey,
            body = "Your parcel is at the gate.",
            category = SmsCategory.UNKNOWN
        )

        notificationHelper.postIncomingSmsNotification(
            messageId = msgId,
            threadKey = threadKey,
            rawAddress = threadKey,
            senderTitle = threadKey,
            body = "Your parcel is at the gate.",
            category = SmsCategory.UNKNOWN,
            extractedOtp = null,
            redactPreviewForAppLock = false
        )

        val notifId = SmsNotificationHelper.notificationIdForThread(threadKey)
        val notif = Shadows.shadowOf(notificationManager).getNotification(notifId)
        assertNotNull(notif)

        val assignAction = notif.actions.first { it.title.toString() == "Assign Category" }
        val launchIntent = Shadows.shadowOf(assignAction.actionIntent).savedIntent

        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            try {
                assertNull(Shadows.shadowOf(notificationManager).getNotification(notifId))

                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("move_category_bottom_sheet")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("move_category_sender_title").assertIsDisplayed()

                composeTestRule.onNodeWithTag("sheet_option_service")
                    .performScrollTo()
                    .performClick()
                composeTestRule.onNodeWithTag("confirm_move_category_button")
                    .performScrollTo()
                    .performClick()

                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("move_category_bottom_sheet")
                        .fetchSemanticsNodes().isEmpty()
                }

                waitForCondition {
                    app.container.database.smsDao()
                        .observeThreadMessages(threadKey)
                        .first()
                        .firstOrNull()?.category == SmsCategory.SERVICE
                }
                val updated = app.container.database.smsDao()
                    .observeThreadMessages(threadKey)
                    .first()
                    .single()
                assertEquals(SmsCategory.SERVICE, updated.category)
            } finally {
                scenario.onActivity { activity ->
                    activity.setContentView(View(activity))
                }
                flushMainLooperAndSnapshots()
            }
        }
        }
    }

    @Test
    fun `MainActivity ACTION_ASSIGN_CATEGORY respects AppLockGateScreen when locked`(): Unit {
        runBlocking {
        app.container.settingsStore.updateSettings {
            it.copy(
                appLockEnabled = true,
                initialSyncCompleted = true,
                onboardingDismissed = true
            )
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        Shadows.shadowOf(keyguard).setIsDeviceSecure(true)
        val biometricManager = context.getSystemService(BiometricManager::class.java)
        val shadowBiometric = Shadow.extract<ShadowBiometricManager>(biometricManager)
        shadowBiometric.setCanAuthenticate(true)
        shadowBiometric.setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        val threadKey = "VM-SECRET-T"
        val notifId = SmsNotificationHelper.notificationIdForThread(threadKey)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = SmsNotificationHelper.ACTION_ASSIGN_CATEGORY
            putExtra(SmsNotificationHelper.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(SmsNotificationHelper.EXTRA_THREAD_KEY, threadKey)
            putExtra(SmsNotificationHelper.EXTRA_RAW_ADDRESS, threadKey)
            putExtra(SmsNotificationHelper.EXTRA_SENDER_TITLE, "Secret Sender")
            putExtra(SmsNotificationHelper.EXTRA_CURRENT_CATEGORY, SmsCategory.TRANSACTIONAL.name)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            try {
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("app_lock_gate_screen")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("app_lock_gate_screen").assertIsDisplayed()
                assertTrue(
                    composeTestRule.onAllNodesWithTag("move_category_bottom_sheet")
                        .fetchSemanticsNodes().isEmpty()
                )
            } finally {
                scenario.onActivity { activity ->
                    activity.setContentView(View(activity))
                }
                flushMainLooperAndSnapshots()
            }
        }
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

    private suspend fun insertTestMessage(
        threadKey: String,
        body: String,
        category: SmsCategory,
        timestampMillis: Long = 1_700_000_000_000L
    ): Long {
        return app.container.database.smsDao().insertMessage(
            SmsMessageEntity(
                systemSmsId = null,
                threadKey = threadKey,
                rawAddress = threadKey,
                contactName = null,
                body = body,
                bodySha256 = BackupCryptoEngine.sha256Hex(body),
                timestampMillis = timestampMillis,
                isIncoming = true,
                isRead = false,
                isArchived = false,
                category = category,
                extractedOtp = null,
                confidenceScore = 0.95f,
                classificationSource = "TEST"
            )
        )
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
}
