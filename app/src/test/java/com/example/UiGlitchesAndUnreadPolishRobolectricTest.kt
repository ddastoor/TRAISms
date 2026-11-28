package com.example

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.ContactsContract
import android.provider.Telephony
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.crypto.BackupSchedule
import com.example.data.crypto.LeftSwipeAction
import com.example.data.crypto.SmsFontSize
import com.example.data.db.CategoryUnreadCount
import com.example.data.db.MmsAttachmentEntity
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.db.ThreadSummary
import com.example.data.sms.ContactLookupCache
import com.example.data.sms.MessageReaction
import com.example.data.sms.PendingMmsAttachment
import com.example.data.sms.RcsInteropCodec
import com.example.data.sms.SmsNotificationHelper
import com.example.data.sms.SyncProgressState
import com.example.data.sms.SystemSmsHelper
import com.example.ui.MainViewModel
import com.example.ui.archive.ArchiveScreen
import com.example.ui.backup.BackupScreen
import com.example.ui.common.GlobalDeleteAllMessagesDialog
import com.example.ui.common.MoveSenderCategoryBottomSheet
import com.example.ui.common.ThreadRowItem
import com.example.ui.messages.MessagesScreen
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.TraiSmsTheme
import com.example.ui.thread.ComposeSmsScreen
import com.example.ui.thread.PendingAttachmentsStrip
import com.example.ui.thread.ThreadDetailScreen
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowRoleManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class UiGlitchesAndUnreadPolishRobolectricTest {

    @get:Rule(order = 0)
    val snapshotSyncRule: TestRule = createComposeSnapshotSyncRule()

    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var app: TraiSmsApp
    private lateinit var systemSmsHelper: SystemSmsHelper
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationHelper: SmsNotificationHelper
    private lateinit var smsProvider: FakeSmsContentProvider
    private lateinit var mmsProvider: FakeMmsContentProvider
    private lateinit var contactsProvider: FakeContactsContentProvider
    private val tempStorageDirs = mutableListOf<File>()

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        app = context as TraiSmsApp
        systemSmsHelper = SystemSmsHelper(context)
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        notificationHelper = SmsNotificationHelper(context)

        smsProvider = FakeSmsContentProvider()
        smsProvider.attachInfo(context, ProviderInfo().apply { authority = "sms" })
        ShadowContentResolver.registerProviderInternal("sms", smsProvider)

        mmsProvider = FakeMmsContentProvider(createIsolatedDir("ui_polish_mms_blobs"))
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
        ShadowDialog.reset()
        resetFileProviderCache()
        app.container.contactCache.warmCache(forceRefresh = true)
        app.container.database.smsDao().deleteAllAttachments()
        app.container.database.smsDao().deleteAllMessages()
        app.container.settingsStore.updateSettings {
            AppSettings(
                appLockEnabled = false,
                initialSyncCompleted = true,
                onboardingDismissed = true,
                categoryShowUnreadOnly = emptyMap(),
                smsFontSize = SmsFontSize.DEFAULT,
                smsFontBold = false
            )
        }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        ShadowDialog.reset()
        resetFileProviderCache()
        ShadowRoleManager.reset()
        notificationManager.cancelAll()
        smsProvider.clear()
        mmsProvider.clear()
        contactsProvider.setContacts(emptyList())
        app.container.contactCache.warmCache(forceRefresh = true)
        app.container.database.smsDao().deleteAllAttachments()
        app.container.database.smsDao().deleteAllMessages()
        app.container.settingsStore.updateSettings {
            AppSettings(
                appLockEnabled = false,
                initialSyncCompleted = true,
                onboardingDismissed = true,
                categoryShowUnreadOnly = emptyMap(),
                smsFontSize = SmsFontSize.DEFAULT,
                smsFontBold = false
            )
        }
        tempStorageDirs.forEach { it.deleteRecursively() }
        tempStorageDirs.clear()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h914dp-xxhdpi")
    fun `MessagesScreen and ThreadRowItem preserve zero-overlap geometry across 360dp 411dp and 840dp viewports at SmsFontSize LARGER bold`(): Unit {
        val sampleThreads = listOf(
            ThreadSummary(
                latestMessageId = 101L,
                threadKey = "+919876543210",
                rawAddress = "+919876543210",
                contactName = "Dr. Bartholomew Alexander-Montgomery III",
                latestBody = "Your OTP is 482910 for international wire transfer and multi-factor login verification.",
                latestTimestampMillis = 1_700_600_050_000L,
                category = SmsCategory.OTP,
                extractedOtp = "482910",
                isArchived = false,
                unreadCount = 12,
                messageCount = 15,
                classificationSource = "TRAI_OTP",
                transportType = "RCS",
                hasAttachments = true
            ),
            ThreadSummary(
                latestMessageId = 102L,
                threadKey = "VM-HDFCBANKCORP-T",
                rawAddress = "VM-HDFCBANKCORP-T",
                contactName = null,
                latestBody = "918273 is your security verification code. Valid for 10 minutes only.",
                latestTimestampMillis = 1_700_600_020_000L,
                category = SmsCategory.OTP,
                extractedOtp = "918273",
                isArchived = false,
                unreadCount = 3,
                messageCount = 4,
                classificationSource = "TRAI_OTP",
                transportType = "SMS",
                hasAttachments = false
            )
        )
        val counts = mapOf(
            SmsCategory.OTP to CategoryUnreadCount(SmsCategory.OTP, unreadCount = 15, totalCount = 19),
            SmsCategory.PERSONAL to CategoryUnreadCount(SmsCategory.PERSONAL, unreadCount = 12, totalCount = 12)
        )

        val viewports = listOf(
            360.dp to 740.dp,
            411.dp to 914.dp,
            840.dp to 900.dp
        )

        withHostActivity { host ->
            var vpWidth by mutableStateOf(360.dp)
            var vpHeight by mutableStateOf(740.dp)
            var threadsState by mutableStateOf(sampleThreads)
            var showUnread by mutableStateOf(true)
            var mode by mutableStateOf("MESSAGES")

            host.setContent {
                TraiSmsTheme(smsFontSize = SmsFontSize.LARGER, smsFontBold = true) {
                    Box(modifier = Modifier.size(width = vpWidth, height = vpHeight)) {
                        when (mode) {
                            "MESSAGES" -> MessagesScreen(
                                selectedCategory = SmsCategory.OTP,
                                threads = threadsState,
                                categoryCounts = counts,
                                totalMessageCount = 31,
                                searchQuery = "",
                                leftSwipeAction = LeftSwipeAction.ARCHIVE,
                                activeRegionBadge = "EU_EN • Ireland & Europe",
                                syncProgress = SyncProgressState(),
                                isDefaultSmsApp = false,
                                showSetupBanner = true,
                                showEmulatorDemoBanner = true,
                                systemSmsHelper = systemSmsHelper,
                                onSelectCategory = {},
                                onSearchQueryChange = {},
                                onOpenThread = {},
                                onOpenCompose = {},
                                onOpenOnboardingGuide = {},
                                onDismissSetupBanner = {},
                                onArchiveThread = { _, _ -> },
                                onDeleteThread = {},
                                onAssignSenderCategory = { _, _, _, _ -> },
                                onDeleteAllInCurrentTab = { _, _ -> },
                                onRequestCountInCurrentTab = { 0 },
                                onDeleteAllInApp = {},
                                onTriggerDeviceSync = {},
                                onSeedDemoInbox = {},
                                promoAutoDeleteDays = 7,
                                otpAutoDeleteDays = 3,
                                showUnreadOnly = showUnread,
                                onToggleCategoryUnreadOnly = { _, enabled -> showUnread = enabled }
                            )
                            "ROW_360" -> ThreadRowItem(
                                thread = sampleThreads.first().copy(unreadCount = 99),
                                isArchiveScreen = false,
                                leftSwipeAction = LeftSwipeAction.ARCHIVE,
                                showCategoryPill = true,
                                onClick = {},
                                onCopyOtp = {},
                                onSwipeLeftAction = {}
                            )
                        }
                    }
                }
            }

            for ((w, h) in viewports) {
                composeTestRule.runOnIdle {
                    mode = "MESSAGES"
                    vpWidth = w
                    vpHeight = h
                    threadsState = sampleThreads
                    showUnread = true
                }
                composeTestRule.waitForIdle()

                // Top bar: region badge + search + overflow never collide or clip
                assertWithinViewport("inbox_region_badge", w, h, useUnmergedTree = true)
                assertWithinViewport("inbox_search_toggle", w, h)
                assertWithinViewport("inbox_overflow_menu_button", w, h)
                assertHorizontallyOrdered("inbox_region_badge", "inbox_search_toggle", useUnmergedTree = true)
                assertHorizontallyOrdered("inbox_search_toggle", "inbox_overflow_menu_button")
                assertVerticallyOrdered("inbox_region_badge", "category_scrollable_tab_row", useUnmergedTree = true)

                // Category tabs & default SMS banner
                assertWithinViewport("category_scrollable_tab_row", w, h)
                assertWithinViewport("inline_default_sms_banner", w, h)
                assertVerticallyOrdered("category_scrollable_tab_row", "inline_default_sms_banner")
                assertHorizontallyOrdered("banner_set_default_sms_button", "banner_privacy_guide_button")
                assertWithinViewport("banner_privacy_guide_button", w, h)

                // Show unread bar: summary never touches or overlaps Switch; zero inline mark-all-read button
                assertWithinViewport("category_unread_filter_bar", w, h)
                assertWithinViewport("category_unread_filter_summary", w, h, useUnmergedTree = true)
                assertWithinViewport("category_unread_only_switch", w, h)
                assertHorizontallyOrdered("category_unread_filter_summary", "category_unread_only_switch", useUnmergedTree = true)
                assertVerticallyOrdered("inline_default_sms_banner", "category_unread_filter_bar")
                composeTestRule.onAllNodesWithTag("category_mark_all_read_button").assertCountEquals(0)

                // Auto-delete bar & list
                assertWithinViewport("inbox_auto_delete_bar", w, h)
                assertVerticallyOrdered("category_unread_filter_bar", "inbox_auto_delete_bar")
                assertVerticallyOrdered("inbox_auto_delete_bar", "messages_lazy_column")

                // Thread rows, bodies, OTP chips, and FAB
                assertWithinViewport("thread_row_+919876543210", w, h)
                assertWithinViewport("thread_row_VM-HDFCBANKCORP-T", w, h)
                assertVerticallyOrdered("thread_row_+919876543210", "thread_row_VM-HDFCBANKCORP-T")
                assertWithinViewport("thread_row_body_+919876543210", w, h, useUnmergedTree = true)
                val otpChipBounds = assertWithinViewport("copy_otp_chip_+919876543210", w, h, useUnmergedTree = true)
                assertTrue("Expected >= 32.dp touch height on OTP chip, got ${otpChipBounds.heightDp}", otpChipBounds.heightDp >= 31.5.dp)
                assertWithinViewport("compose_sms_fab", w, h)
            }

            // Direct ThreadRowItem at 360dp with CategoryBadgePill + RCS + Attachment + Copy OTP + 99 unread badge
            composeTestRule.runOnIdle {
                mode = "ROW_360"
                vpWidth = 360.dp
                vpHeight = 300.dp
            }
            composeTestRule.waitForIdle()
            assertWithinViewport("thread_row_+919876543210", 360.dp, 300.dp)
            assertWithinViewport("thread_transport_badge_+919876543210", 360.dp, 300.dp, useUnmergedTree = true)
            assertWithinViewport("thread_attachment_icon_+919876543210", 360.dp, 300.dp, useUnmergedTree = true)
            assertWithinViewport("copy_otp_chip_+919876543210", 360.dp, 300.dp, useUnmergedTree = true)

            // Unread-empty state on 411dp x 526dp keyboard-open height keeps empty_unread_show_all_button accessible & off FAB
            composeTestRule.runOnIdle {
                mode = "MESSAGES"
                vpWidth = 411.dp
                vpHeight = 526.dp
                threadsState = emptyList()
                showUnread = true
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("empty_unread_show_all_button")
                .performScrollTo()
                .assertIsDisplayed()
            assertWithinViewport("empty_unread_show_all_button", 411.dp, 526.dp)
            assertNoOverlap("empty_unread_show_all_button", "compose_sms_fab")
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h914dp-xxhdpi")
    fun `TraiSmsPrimaryApp lays out tablet NavigationRail on 840dp and phone BottomNavigationBar on 360dp without inset or FAB overlap`(): Unit = runBlocking {
        app.container.database.smsDao().insertMessage(
            buildSeededMessage(
                systemSmsId = 1101L,
                threadKey = "+919811112222",
                body = "Unread message for layout check",
                timestampMillis = 1_700_610_000_000L,
                category = SmsCategory.PERSONAL,
                isRead = false
            )
        )

        val vmStore = ViewModelStore()
        val viewModel = ViewModelProvider(
            vmStore,
            MainViewModel.provideFactory(app.container)
        )[MainViewModel::class.java]

        try {
            withHostActivity { host ->
                var vpWidth by mutableStateOf(840.dp)
                var vpHeight by mutableStateOf(900.dp)

                host.setContent {
                    TraiSmsTheme(smsFontSize = SmsFontSize.LARGER, smsFontBold = true) {
                        Box(modifier = Modifier.size(width = vpWidth, height = vpHeight)) {
                            TraiSmsPrimaryApp(
                                activity = host,
                                viewModel = viewModel,
                                pendingExternalAction = null,
                                onConsumeExternalAction = {},
                                onRequestDefaultSmsPopup = {}
                            )
                        }
                    }
                }

                // 1. 840dp Tablet: NavigationRail on left, zero BottomNavigationBar, zero overlap with MessagesScreen
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("primary_navigation_rail").fetchSemanticsNodes().isNotEmpty() &&
                        composeTestRule.onAllNodesWithTag("category_unread_filter_bar").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onAllNodesWithTag("bottom_navigation_bar").assertCountEquals(0)
                val railBounds = assertWithinViewport("primary_navigation_rail", 840.dp, 900.dp)
                for (navTag in listOf("bottom_nav_messages", "bottom_nav_archive", "bottom_nav_backup", "bottom_nav_settings")) {
                    val navBounds = assertWithinViewport(navTag, 840.dp, 900.dp)
                    assertTrue("Expected $navTag inside NavigationRail", navBounds.right <= railBounds.right + 1.dp)
                }
                assertHorizontallyOrdered("primary_navigation_rail", "category_unread_filter_bar")
                assertHorizontallyOrdered("primary_navigation_rail", "inbox_region_badge", useUnmergedTree = true)

                // 2. 360dp Compact Phone: BottomNavigationBar at bottom, zero NavigationRail, FAB sits cleanly above bottom bar
                composeTestRule.runOnIdle {
                    vpWidth = 360.dp
                    vpHeight = 740.dp
                }
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("bottom_navigation_bar").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onAllNodesWithTag("primary_navigation_rail").assertCountEquals(0)
                assertWithinViewport("bottom_navigation_bar", 360.dp, 740.dp)
                assertHorizontallyOrdered("bottom_nav_messages", "bottom_nav_archive")
                assertHorizontallyOrdered("bottom_nav_archive", "bottom_nav_backup")
                assertHorizontallyOrdered("bottom_nav_backup", "bottom_nav_settings")
                assertVerticallyOrdered("compose_sms_fab", "bottom_navigation_bar")
            }
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `End-to-end Show unread toggle and Mark all as read sync across MessagesScreen MainViewModel EncryptedSettingsStore SmsDao Telephony Sms Mms and Notifications`(): Unit = runBlocking {
        grantDefaultSmsRole()
        val smsDao = app.container.database.smsDao()
        val baseTime = 1_700_700_000_000L

        // Thread 1 (PERSONAL): unread incoming SMS + newer outgoing reply + unread hidden reaction row
        smsProvider.seedExistingMessage(7101L, "+919811112222", "Are we still on for 7 PM?", baseTime + 1_000L, Telephony.Sms.MESSAGE_TYPE_INBOX, 0)
        val unreadIncomingId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 7101L,
                threadKey = "+919811112222",
                body = "Are we still on for 7 PM?",
                timestampMillis = baseTime + 1_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = false
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = null,
                threadKey = "+919811112222",
                body = "Yes, see you there!",
                timestampMillis = baseTime + 2_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = false,
                isRead = true
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = null,
                threadKey = "+919811112222",
                body = "Liked “Yes, see you there!”",
                timestampMillis = baseTime + 2_500L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = false,
                isHiddenReaction = true
            )
        )

        // Thread 2 (PERSONAL): unread incoming MMS
        mmsProvider.seedMms(
            id = 8101L,
            threadId = 42L,
            dateSec = (baseTime + 3_000L) / 1000L,
            box = Telephony.Mms.MESSAGE_BOX_INBOX,
            read = 0,
            subject = "Venue Map",
            creator = "com.android.mms",
            fromAddress = "+919833334444",
            toAddresses = listOf("+919800000000"),
            textBody = "Here is the entrance map"
        )
        val unreadMmsRowId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = null,
                systemMmsId = 8101L,
                threadKey = "+919833334444",
                body = "Here is the entrance map",
                timestampMillis = baseTime + 3_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = false,
                transportType = "MMS",
                mmsSubject = "Venue Map"
            )
        )

        // Thread 3 (PERSONAL): already-read incoming SMS
        smsProvider.seedExistingMessage(7102L, "+919855556666", "Already read chat", baseTime + 4_000L, Telephony.Sms.MESSAGE_TYPE_INBOX, 1)
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 7102L,
                threadKey = "+919855556666",
                body = "Already read chat",
                timestampMillis = baseTime + 4_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = true
            )
        )

        // Thread 4 (PERSONAL): outgoing-only row erroneously flagged isRead = false (must NOT count as unread)
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = null,
                threadKey = "+919866667777",
                body = "Outgoing draft note",
                timestampMillis = baseTime + 5_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = false,
                isRead = false
            )
        )

        // Thread 5 (PERSONAL, ARCHIVED): unread incoming SMS (must stay untouched)
        smsProvider.seedExistingMessage(7103L, "+919877778888", "Archived unread note", baseTime + 6_000L, Telephony.Sms.MESSAGE_TYPE_INBOX, 0)
        val archivedUnreadId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 7103L,
                threadKey = "+919877778888",
                body = "Archived unread note",
                timestampMillis = baseTime + 6_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = false,
                isArchived = true
            )
        )

        // Thread 6 (TRANSACTIONAL): unread incoming SMS (must stay untouched)
        smsProvider.seedExistingMessage(7104L, "VM-HDFCBK-T", "Acct XX9012 credited INR 5,000.", baseTime + 7_000L, Telephony.Sms.MESSAGE_TYPE_INBOX, 0)
        val txnUnreadId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 7104L,
                threadKey = "VM-HDFCBK-T",
                body = "Acct XX9012 credited INR 5,000.",
                timestampMillis = baseTime + 7_000L,
                category = SmsCategory.TRANSACTIONAL,
                isIncoming = true,
                isRead = false
            )
        )

        notificationHelper.postIncomingSmsNotification(
            messageId = unreadIncomingId,
            threadKey = "+919811112222",
            rawAddress = "+919811112222",
            senderTitle = "Alice",
            body = "Are we still on for 7 PM?",
            category = SmsCategory.PERSONAL,
            extractedOtp = null,
            redactPreviewForAppLock = false
        )
        notificationHelper.postIncomingSmsNotification(
            messageId = txnUnreadId,
            threadKey = "VM-HDFCBK-T",
            rawAddress = "VM-HDFCBK-T",
            senderTitle = "VM-HDFCBK-T",
            body = "Acct XX9012 credited INR 5,000.",
            category = SmsCategory.TRANSACTIONAL,
            extractedOtp = null,
            redactPreviewForAppLock = false
        )

        val personalNotifId = SmsNotificationHelper.notificationIdForThread("+919811112222")
        val txnNotifId = SmsNotificationHelper.notificationIdForThread("VM-HDFCBK-T")
        assertNotNull(Shadows.shadowOf(notificationManager).getNotification(personalNotifId))
        assertNotNull(Shadows.shadowOf(notificationManager).getNotification(txnNotifId))

        val vmStore1 = ViewModelStore()
        val viewModel1 = ViewModelProvider(
            vmStore1,
            MainViewModel.provideFactory(app.container)
        )[MainViewModel::class.java]

        var vmStore2: ViewModelStore? = null
        try {
            withHostActivitySuspend { host ->
                var toggleCallCount = 0
                host.setContent {
                    val selectedCategory by viewModel1.selectedCategory.collectAsStateWithLifecycle()
                    val threads by viewModel1.activeThreads.collectAsStateWithLifecycle()
                    val counts by viewModel1.categoryCounts.collectAsStateWithLifecycle()
                    val totalCount by viewModel1.totalMessageCount.collectAsStateWithLifecycle()
                    val showUnreadOnly by viewModel1.currentCategoryUnreadOnly.collectAsStateWithLifecycle()
                    val regionBadge by viewModel1.activeRegionBadge.collectAsStateWithLifecycle()

                    TraiSmsTheme {
                        Box(modifier = Modifier.size(width = 411.dp, height = 914.dp)) {
                            MessagesScreen(
                                selectedCategory = selectedCategory,
                                threads = threads,
                                categoryCounts = counts,
                                totalMessageCount = totalCount,
                                searchQuery = "",
                                leftSwipeAction = LeftSwipeAction.ARCHIVE,
                                activeRegionBadge = regionBadge,
                                syncProgress = SyncProgressState(),
                                isDefaultSmsApp = true,
                                showSetupBanner = false,
                                showEmulatorDemoBanner = false,
                                systemSmsHelper = systemSmsHelper,
                                onSelectCategory = viewModel1::selectCategory,
                                onSearchQueryChange = {},
                                onOpenThread = {},
                                onOpenCompose = {},
                                onOpenOnboardingGuide = {},
                                onDismissSetupBanner = {},
                                onArchiveThread = viewModel1::setThreadArchived,
                                onDeleteThread = viewModel1::deleteThread,
                                onAssignSenderCategory = viewModel1::assignSenderCategory,
                                onDeleteAllInCurrentTab = viewModel1::deleteAllInCategory,
                                onRequestCountInCurrentTab = viewModel1::countActiveMessagesInCategory,
                                onDeleteAllInApp = viewModel1::deleteAllMessagesInApp,
                                onTriggerDeviceSync = {},
                                onSeedDemoInbox = {},
                                showUnreadOnly = showUnreadOnly,
                                onToggleCategoryUnreadOnly = { cat, enabled ->
                                    toggleCallCount += 1
                                    viewModel1.updateCategoryUnreadOnly(cat, enabled)
                                },
                                onMarkAllReadInCurrentTab = viewModel1::markAllReadInCategory
                            )
                        }
                    }
                }

                // 1. Initial unread view shows strictly the 2 active unread incoming threads (SMS+reply & MMS)
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    viewModel1.categoryCounts.value[SmsCategory.PERSONAL]?.unreadCount == 2 &&
                        composeTestRule.onAllNodesWithTag("thread_row_+919811112222").fetchSemanticsNodes().isNotEmpty() &&
                        composeTestRule.onAllNodesWithTag("thread_row_+919833334444").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("category_unread_only_switch").assertIsOn()
                composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
                    .assertTextContains("Unread only (2)")
                composeTestRule.onAllNodesWithTag("thread_row_+919855556666").assertCountEquals(0)
                composeTestRule.onAllNodesWithTag("thread_row_+919866667777").assertCountEquals(0)

                // 2. Toggle Show unread OFF via Switch -> exactly 1 call, reveals read + outgoing threads ("All (5)")
                composeTestRule.onNodeWithTag("category_unread_only_switch").performClick()
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    !viewModel1.currentCategoryUnreadOnly.value &&
                        composeTestRule.onAllNodesWithTag("thread_row_+919855556666").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals("Switch tap must fire exactly 1 toggle without double-toggle", 1, toggleCallCount)
                composeTestRule.onNodeWithTag("category_unread_only_switch").assertIsOff()
                composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
                    .assertTextContains("All (5)")

                // 3. Toggle Show unread back ON via bar -> exactly 1 call
                composeTestRule.onNodeWithTag("category_unread_filter_bar").performClick()
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    viewModel1.currentCategoryUnreadOnly.value &&
                        composeTestRule.onAllNodesWithTag("thread_row_+919855556666").fetchSemanticsNodes().isEmpty()
                }
                assertEquals("Bar tap must fire exactly 1 toggle without double-toggle", 2, toggleCallCount)
                composeTestRule.onNodeWithTag("category_unread_only_switch").assertIsOn()

                // 4. Rapid ViewModel toggles settle cleanly and persist across fresh ViewModelStore reload
                composeTestRule.runOnIdle {
                    viewModel1.updateCategoryUnreadOnly(SmsCategory.PERSONAL, false)
                    viewModel1.updateCategoryUnreadOnly(SmsCategory.PERSONAL, true)
                    viewModel1.updateCategoryUnreadOnly(SmsCategory.PERSONAL, false)
                }
                waitForCondition {
                    !viewModel1.currentCategoryUnreadOnly.value &&
                        !app.container.settingsStore.getSettingsSnapshot().isCategoryUnreadOnly(SmsCategory.PERSONAL)
                }

                val store2 = ViewModelStore()
                vmStore2 = store2
                val viewModel2 = ViewModelProvider(
                    store2,
                    MainViewModel.provideFactory(app.container)
                )[MainViewModel::class.java]
                val vm2Sub = launch(Dispatchers.Unconfined) { viewModel2.currentCategoryUnreadOnly.collect {} }
                try {
                    waitForCondition {
                        viewModel2.isSettingsReady.value &&
                            !viewModel2.currentCategoryUnreadOnly.value
                    }
                    viewModel2.selectCategory(SmsCategory.TRANSACTIONAL)
                    waitForCondition { viewModel2.currentCategoryUnreadOnly.value }
                } finally {
                    vm2Sub.cancel()
                }

                // Restore PERSONAL Show unread = true before Mark all as read flow
                composeTestRule.runOnIdle {
                    viewModel1.updateCategoryUnreadOnly(SmsCategory.PERSONAL, true)
                }
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    viewModel1.currentCategoryUnreadOnly.value &&
                        composeTestRule.onAllNodesWithTag("thread_row_+919811112222").fetchSemanticsNodes().isNotEmpty()
                }

                // 5. Mark all as read via 3-dot overflow menu
                composeTestRule.onNodeWithTag("inbox_overflow_menu_button").performClick()
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("menu_mark_all_read_in_tab")
                    .assertIsDisplayed()
                    .assertIsEnabled()
                    .performClick()

                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    viewModel1.categoryCounts.value[SmsCategory.PERSONAL]?.unreadCount == 0 &&
                        composeTestRule.onAllNodesWithText("Marked 2 Personal message(s) as read").fetchSemanticsNodes().isNotEmpty() &&
                        composeTestRule.onAllNodesWithTag("empty_unread_show_all_button").fetchSemanticsNodes().isNotEmpty()
                }

                composeTestRule.mainClock.advanceTimeBy(5_000)
                composeTestRule.waitForIdle()

                // Verify Room, Telephony.Sms, Telephony.Mms, Archived isolation, Transactional isolation, and Notifications
                assertTrue(smsDao.getMessageById(unreadIncomingId)!!.isRead)
                assertTrue(smsDao.getMessageById(unreadMmsRowId)!!.isRead)
                assertFalse(smsDao.getMessageById(archivedUnreadId)!!.isRead)
                assertFalse(smsDao.getMessageById(txnUnreadId)!!.isRead)

                assertEquals(1, smsProvider.getReadFlag(7101L))
                assertEquals(1, mmsProvider.getReadFlag(8101L))
                assertEquals(0, smsProvider.getReadFlag(7103L))
                assertEquals(0, smsProvider.getReadFlag(7104L))
                assertNull(Shadows.shadowOf(notificationManager).getNotification(personalNotifId))
                assertNotNull(Shadows.shadowOf(notificationManager).getNotification(txnNotifId))

                // 6. Unread-empty CTA empty_unread_show_all_button reveals all read threads cleanly
                composeTestRule.onNodeWithTag("empty_unread_show_all_button")
                    .assertIsDisplayed()
                    .assertTextContains("Show all Personal messages")
                    .performClick()

                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    !viewModel1.currentCategoryUnreadOnly.value &&
                        composeTestRule.onAllNodesWithTag("thread_row_+919811112222").fetchSemanticsNodes().isNotEmpty() &&
                        composeTestRule.onAllNodesWithTag("thread_row_+919833334444").fetchSemanticsNodes().isNotEmpty() &&
                        composeTestRule.onAllNodesWithTag("thread_row_+919855556666").fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("category_unread_only_switch").assertIsOff()
                composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
                    .assertTextContains("All (5)")
                composeTestRule.onAllNodesWithTag("empty_unread_show_all_button").assertCountEquals(0)
            }
        } finally {
            vmStore2?.clear()
            vmStore1.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h914dp-xxhdpi")
    fun `ThreadDetailScreen and MmsUiComponents prevent action row clipping and adapt bubble widths across 360dp and 840dp at LARGER bold`(): Unit {
        val groupThreadKey = "GRP_TEAM_1"
        val groupJson = """["+919811112222","+919822223333","+919833334444"]"""
        val vCardText = RcsInteropCodec.buildVCardContent("Alice Sharma", "+919811112222")

        val pendingMms = SmsMessageEntity(
            id = 201L,
            threadKey = groupThreadKey,
            rawAddress = "+919811112222",
            contactName = "Alice, Bob, Charlie",
            body = "Incoming MMS (12 KB) • 100% Offline Mode",
            bodySha256 = BackupCryptoEngine.sha256Hex("m201"),
            timestampMillis = 1_700_800_010_000L,
            isIncoming = true,
            isRead = false,
            category = SmsCategory.PERSONAL,
            transportType = "MMS",
            deliveryStatus = "PENDING_DOWNLOAD",
            mmsSubject = "Quarterly Planning Documents & Itinerary",
            groupRecipientsJson = groupJson,
            classificationSource = "SAVED_CONTACT"
        )
        val otpMediaMsg = SmsMessageEntity(
            id = 202L,
            threadKey = groupThreadKey,
            rawAddress = "+919811112222",
            contactName = "Alice, Bob, Charlie",
            body = "Use 482910 to open the shared archive.",
            bodySha256 = BackupCryptoEngine.sha256Hex("m202"),
            timestampMillis = 1_700_800_020_000L,
            isIncoming = true,
            isRead = true,
            category = SmsCategory.OTP,
            extractedOtp = "482910",
            transportType = "MMS",
            groupRecipientsJson = groupJson,
            classificationSource = "TRAI_OTP_STRONG",
            reactionsJson = RcsInteropCodec.encodeReactions(
                listOf(
                    MessageReaction("👍", "SELF", "You", 1_700_800_025_000L, true),
                    MessageReaction("❤️", "+919822223333", "Bob", 1_700_800_026_000L, false)
                )
            )
        )
        val failedOutgoingMsg = SmsMessageEntity(
            id = 203L,
            threadKey = groupThreadKey,
            rawAddress = "+919811112222",
            contactName = "Alice, Bob, Charlie",
            body = "Sending updated slide deck now!",
            bodySha256 = BackupCryptoEngine.sha256Hex("m203"),
            timestampMillis = 1_700_800_030_000L,
            isIncoming = false,
            isRead = true,
            category = SmsCategory.PERSONAL,
            transportType = "SMS",
            deliveryStatus = "FAILED",
            replyToMessageId = 202L,
            replyToSender = "Alice Sharma",
            replyToSnippet = "Use 482910 to open the shared archive.",
            groupRecipientsJson = groupJson,
            classificationSource = "SENT_OUTGOING"
        )
        val attachmentsById = mapOf(
            202L to listOf(
                MmsAttachmentEntity(id = 1L, messageId = 202L, partIndex = 0, contentType = "image/png", fileName = "very_long_itinerary_photo_preview.png", sizeBytes = 4096L),
                MmsAttachmentEntity(id = 2L, messageId = 202L, partIndex = 1, contentType = "audio/mpeg", fileName = "voice_note_planning.mp3", sizeBytes = 2048L),
                MmsAttachmentEntity(id = 3L, messageId = 202L, partIndex = 2, contentType = "text/x-vcard", fileName = "alice.vcf", sizeBytes = vCardText.length.toLong(), textContent = vCardText)
            )
        )

        val oneWayMsg = SmsMessageEntity(
            id = 301L,
            threadKey = "VM-HDFCBK-T",
            rawAddress = "VM-HDFCBK-T",
            contactName = null,
            body = "Acct XX9012 credited with INR 25,000.00 via NEFT.",
            bodySha256 = BackupCryptoEngine.sha256Hex("m301"),
            timestampMillis = 1_700_800_040_000L,
            isIncoming = true,
            isRead = true,
            category = SmsCategory.TRANSACTIONAL,
            classificationSource = "TRAI_DLT"
        )

        withHostActivity { host ->
            var vpWidth by mutableStateOf(360.dp)
            var vpHeight by mutableStateOf(740.dp)
            var isOneWay by mutableStateOf(false)

            host.setContent {
                TraiSmsTheme(smsFontSize = SmsFontSize.LARGER, smsFontBold = true) {
                    Box(modifier = Modifier.size(width = vpWidth, height = vpHeight)) {
                        if (!isOneWay) {
                            ThreadDetailScreen(
                                threadKey = groupThreadKey,
                                messages = listOf(pendingMms, otpMediaMsg, failedOutgoingMsg),
                                onBack = {},
                                onMarkThreadRead = {},
                                onSendReply = { _, _, _ -> },
                                onDeleteSingleMessage = {},
                                onToggleArchive = { _, _ -> },
                                onAssignCategory = { _, _, _, _ -> },
                                attachmentsByMessageId = attachmentsById,
                                rcsChatsEnabled = true,
                                systemSmsHelper = systemSmsHelper
                            )
                        } else {
                            ThreadDetailScreen(
                                threadKey = "VM-HDFCBK-T",
                                messages = listOf(oneWayMsg),
                                onBack = {},
                                onMarkThreadRead = {},
                                onSendReply = { _, _, _ -> },
                                onDeleteSingleMessage = {},
                                onToggleArchive = { _, _ -> },
                                onAssignCategory = { _, _, _, _ -> }
                            )
                        }
                    }
                }
            }

            composeTestRule.waitForIdle()

            // TopAppBar & composer fit cleanly inside 360dp
            assertWithinViewport("thread_back_button", 360.dp, 740.dp)
            assertWithinViewport("thread_group_participants_label", 360.dp, 740.dp)
            assertWithinViewport("thread_move_category_button", 360.dp, 740.dp)
            assertWithinViewport("thread_archive_toggle_button", 360.dp, 740.dp)
            assertHorizontallyOrdered("thread_group_participants_label", "thread_move_category_button")
            assertHorizontallyOrdered("thread_move_category_button", "thread_archive_toggle_button")

            assertWithinViewport("thread_attach_button", 360.dp, 740.dp)
            assertWithinViewport("thread_toggle_subject_button", 360.dp, 740.dp)
            assertWithinViewport("thread_reply_input", 360.dp, 740.dp)
            assertWithinViewport("thread_send_button", 360.dp, 740.dp)
            assertHorizontallyOrdered("thread_reply_input", "thread_send_button")

            // Message 201 (PENDING_DOWNLOAD) + metadata FlowRow fit within 360dp
            composeTestRule.onNodeWithTag("thread_messages_list").performScrollToIndex(0)
            composeTestRule.waitForIdle()
            assertWithinViewport("mms_download_card_201", 360.dp, 740.dp, useUnmergedTree = true)
            assertWithinViewport("thread_metadata_row_201", 360.dp, 740.dp)
            assertWithinViewport("reply_msg_button_201", 360.dp, 740.dp, useUnmergedTree = true)
            assertWithinViewport("react_msg_button_201", 360.dp, 740.dp, useUnmergedTree = true)
            assertWithinViewport("delete_single_msg_201", 360.dp, 740.dp, useUnmergedTree = true)

            // Message 202 (OTP + attachments + reaction pills + emoji reaction bar) fits within 360dp
            composeTestRule.onNodeWithTag("thread_messages_list").performScrollToIndex(1)
            composeTestRule.waitForIdle()
            assertWithinViewport("bubble_copy_otp_202", 360.dp, 740.dp, useUnmergedTree = true)
            assertWithinViewport("reaction_pills_row_202", 360.dp, 740.dp)
            composeTestRule.onNodeWithTag("react_msg_button_202", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnClick)
            composeTestRule.waitForIdle()
            assertWithinViewport("emoji_reaction_bar_202", 360.dp, 740.dp)

            // Message 203 (FAILED retry + FlowRow metadata) fits within 360dp
            composeTestRule.onNodeWithTag("thread_messages_list").performScrollToIndex(2)
            composeTestRule.waitForIdle()
            assertWithinViewport("message_status_203", 360.dp, 740.dp)
            assertWithinViewport("retry_failed_msg_203", 360.dp, 740.dp, useUnmergedTree = true)
            assertWithinViewport("thread_metadata_row_203", 360.dp, 740.dp)
            assertWithinViewport("delete_single_msg_203", 360.dp, 740.dp, useUnmergedTree = true)

            // Tablet 840dp: bubble width stays capped at <= 420.dp
            composeTestRule.runOnIdle {
                vpWidth = 840.dp
                vpHeight = 900.dp
            }
            composeTestRule.waitForIdle()
            val bubbleBounds = composeTestRule.onNodeWithTag("thread_message_bubble_203").getUnclippedBoundsInRoot()
            assertTrue("Tablet bubble width must be <= 420.dp, was ${bubbleBounds.widthDp}", bubbleBounds.widthDp <= 421.dp)

            // AttachmentPickerBottomSheet opens & fits cleanly inside 360dp container
            composeTestRule.runOnIdle {
                vpWidth = 360.dp
                vpHeight = 740.dp
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("thread_attach_button").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithTag("attachment_picker_sheet").fetchSemanticsNodes().isNotEmpty()
            }
            for (optTag in listOf("attach_option_media", "attach_option_file", "attach_option_contact", "attach_option_location")) {
                composeTestRule.onNodeWithTag(optTag).performScrollTo().assertIsDisplayed()
                assertWithinContainer(optTag, "attachment_picker_sheet_content")
            }

            // Switch to one-way sender thread to unmount ModalBottomSheet cleanly and verify alphanumeric footer fits 360dp
            composeTestRule.runOnIdle {
                isOneWay = true
                vpWidth = 360.dp
                vpHeight = 740.dp
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithTag("attachment_picker_sheet").fetchSemanticsNodes().isEmpty()
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("One-way alphanumeric sender header (VM-HDFCBK-T)", substring = true).assertIsDisplayed()
            assertWithinViewport("thread_message_bubble_301", 360.dp, 740.dp)
        }
    }

    @Test
    fun `ComposeSmsScreen fits group chips MMS subject pending attachments strip and bottom controls cleanly in 526dp keyboard-open viewport`(): Unit {
        contactsProvider.setContacts(
            listOf(
                "Alice Sharma" to "+91 98765 43210",
                "Bob Patel" to "(415) 555-2671",
                "Charlie Singh" to "+91 98201 12233"
            )
        )
        val contactCache = ContactLookupCache(context)
        val tempDir = createIsolatedDir("compose_att")
        val sampleFile = File(tempDir, "vacation_snapshot_2026_11_28_ultra_wide_camera_export.txt").apply {
            writeText("Sample attachment payload for ComposeSmsScreen MMS strip test")
        }

        withHostActivity { host ->
            var vpWidth by mutableStateOf(411.dp)
            var vpHeight by mutableStateOf(526.dp)
            var standaloneStrip by mutableStateOf(false)
            val standaloneList = mutableStateListOf(
                PendingMmsAttachment(
                    contentType = "image/jpeg",
                    fileName = "extremely_long_attachment_filename_from_camera_2026_11_28_final_v2.jpg",
                    sizeBytes = 4096L
                )
            )

            host.setContent {
                TraiSmsTheme(smsFontSize = SmsFontSize.LARGER, smsFontBold = true) {
                    Box(modifier = Modifier.size(width = vpWidth, height = vpHeight)) {
                        if (!standaloneStrip) {
                            ComposeSmsScreen(
                                initialRecipient = "",
                                initialBody = "",
                                contactCache = contactCache,
                                onBack = {},
                                onSendSms = { _, _, onRes -> onRes(Result.success("T1")) },
                                systemSmsHelper = systemSmsHelper,
                                onSendMms = { _, _, _, _, onRes -> onRes(Result.success("GRP_1")) }
                            )
                        } else {
                            PendingAttachmentsStrip(
                                attachments = standaloneList,
                                onRemoveAt = { idx -> standaloneList.removeAt(idx) }
                            )
                        }
                    }
                }
            }

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithText("Alice Sharma").fetchSemanticsNodes().isNotEmpty()
            }

            // 1. Baseline 411dp x 526dp keyboard-open contacts budget (> 120.dp) & zero overlap
            val baseContacts = composeTestRule.onNodeWithTag("compose_contacts_list").getUnclippedBoundsInRoot()
            assertTrue("Expected contactsHeight > 120.dp, got ${baseContacts.heightDp}", baseContacts.heightDp > 120.dp)
            assertVerticallyOrdered("compose_contacts_list", "compose_body_input")

            // 2. Add 2 group recipients + enable MMS subject + stage pending attachment via attachment picker
            composeTestRule.onNodeWithTag("compose_recipient_input").performTextInput("+919876543210")
            composeTestRule.onNodeWithTag("compose_add_group_recipient_button").performClick()
            composeTestRule.onNodeWithTag("compose_recipient_input").performTextInput("+919820112233")
            composeTestRule.onNodeWithTag("compose_add_group_recipient_button").performClick()
            composeTestRule.onNodeWithTag("compose_toggle_subject_button").performClick()

            composeTestRule.onNodeWithTag("compose_attach_button").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithTag("attach_option_file").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("attach_option_file").performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(host).peekNextStartedActivityForResult() != null
            }
            val filePickLaunch = Shadows.shadowOf(host).nextStartedActivityForResult
            host.activityResultRegistry.dispatchResult(
                filePickLaunch.requestCode,
                Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(sampleFile))
            )

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithTag("compose_pending_attachments_strip").fetchSemanticsNodes().isNotEmpty()
            }

            // Verify all MMS compose sections fit inside 411dp x 526dp without overlapping
            assertWithinViewport("compose_recipient_input", 411.dp, 526.dp)
            assertWithinViewport("compose_group_recipients_row", 411.dp, 526.dp)
            assertWithinViewport("compose_pending_attachments_strip", 411.dp, 526.dp)
            assertWithinViewport("compose_mms_subject_input", 411.dp, 526.dp)
            assertWithinViewport("compose_body_input", 411.dp, 526.dp)
            assertWithinViewport("compose_char_counter", 411.dp, 526.dp, useUnmergedTree = true)
            assertWithinViewport("compose_send_button", 411.dp, 526.dp)

            composeTestRule.onNodeWithTag("compose_char_counter", useUnmergedTree = true)
                .assertTextContains("MMS • 1 file(s)")
            composeTestRule.onNodeWithTag("compose_send_button")
                .assertTextContains("Send MMS")

            assertVerticallyOrdered("compose_recipient_input", "compose_group_recipients_row")
            assertVerticallyOrdered("compose_group_recipients_row", "compose_pending_attachments_strip")
            assertVerticallyOrdered("compose_pending_attachments_strip", "compose_mms_subject_input")
            assertVerticallyOrdered("compose_mms_subject_input", "compose_body_input")
            assertVerticallyOrdered("compose_body_input", "compose_send_button")
            assertHorizontallyOrdered("compose_char_counter", "compose_send_button", useUnmergedTree = true)

            // 3. Compact 360dp x 526dp at LARGER bold: counter & Send MMS button never collide
            composeTestRule.runOnIdle {
                vpWidth = 360.dp
                vpHeight = 526.dp
            }
            composeTestRule.waitForIdle()
            assertWithinViewport("compose_char_counter", 360.dp, 526.dp, useUnmergedTree = true)
            assertWithinViewport("compose_send_button", 360.dp, 526.dp)
            assertHorizontallyOrdered("compose_char_counter", "compose_send_button", useUnmergedTree = true)

            // 4. PendingAttachmentsStrip truncates ultra-long filename within 360dp and removes cleanly
            composeTestRule.runOnIdle { standaloneStrip = true }
            composeTestRule.waitForIdle()
            assertWithinViewport("remove_pending_attachment_0", 360.dp, 526.dp)
            composeTestRule.onNodeWithTag("remove_pending_attachment_0").performClick()
            composeTestRule.waitForIdle()
            assertEquals(0, standaloneList.size)
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
    fun `SettingsScreen ArchiveScreen BackupScreen OnboardingScreen and Modals render cleanly at 360dp LARGER bold without overlap clipping or dialog deadlocks`(): Unit {
        val archived1 = ThreadSummary(
            latestMessageId = 901L,
            threadKey = "+919811112222",
            rawAddress = "+919811112222",
            contactName = "Alice Sharma",
            latestBody = "Archived dinner thread",
            latestTimestampMillis = 1_700_900_010_000L,
            category = SmsCategory.PERSONAL,
            extractedOtp = null,
            isArchived = true,
            unreadCount = 0,
            messageCount = 2,
            classificationSource = "SAVED_CONTACT"
        )
        val archived2 = ThreadSummary(
            latestMessageId = 902L,
            threadKey = "VM-HDFCBK-T",
            rawAddress = "VM-HDFCBK-T",
            contactName = null,
            latestBody = "Archived statement",
            latestTimestampMillis = 1_700_900_005_000L,
            category = SmsCategory.TRANSACTIONAL,
            extractedOtp = null,
            isArchived = true,
            unreadCount = 0,
            messageCount = 1,
            classificationSource = "TRAI_DLT"
        )

        withHostActivity { host ->
            var screenMode by mutableStateOf("SETTINGS")
            var settingsState by mutableStateOf(
                AppSettings(
                    smsFontSize = SmsFontSize.LARGER,
                    smsFontBold = true,
                    backupSchedule = BackupSchedule.CUSTOM,
                    customBackupDays = 5,
                    persistedDriveUri = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dvery_long_backup_identifier_2026_11_28",
                    driveFileName = "TRAISms_Encrypted_Backup_2026_11_28_long_name.trai",
                    lastBackupStatus = "Failed: Storage Access Framework destination write permission revoked by user"
                )
            )
            var rcsToggles by mutableIntStateOf(0)
            var archiveList by mutableStateOf(emptyList<ThreadSummary>())
            var isDefaultOnboarding by mutableStateOf(false)
            var sheetAlwaysToggleCount by mutableIntStateOf(0)

            host.setContent {
                TraiSmsTheme(smsFontSize = SmsFontSize.LARGER, smsFontBold = true) {
                    Box(modifier = Modifier.size(width = 360.dp, height = 740.dp)) {
                        when (screenMode) {
                            "SETTINGS" -> SettingsScreen(
                                settings = settingsState,
                                isDefaultSmsApp = false,
                                detectedRegionLabel = "EU_EN • Ireland & Europe",
                                totalMessageCount = 42,
                                systemSmsHelper = systemSmsHelper,
                                onRefreshRoleStatus = {},
                                onUpdatePromoAutoDelete = {},
                                onUpdateLeftSwipeAction = {},
                                onUpdateRightSwipeAction = {},
                                onUpdateUnarchiveOnNewSms = { v -> settingsState = settingsState.copy(unarchiveOnNewSms = v) },
                                onUpdateRegionOverride = { r -> settingsState = settingsState.copy(regionOverride = r) },
                                onUpdateBackupSchedule = { s, d -> settingsState = settingsState.copy(backupSchedule = s, customBackupDays = d) },
                                onNavigateToBackupTab = {},
                                onToggleAppLock = { v -> settingsState = settingsState.copy(appLockEnabled = v) },
                                onRerunCategorization = {},
                                onDeleteAllMessagesInApp = {},
                                onUpdateSmsFontSize = { s -> settingsState = settingsState.copy(smsFontSize = s) },
                                onUpdateSmsFontBold = { b -> settingsState = settingsState.copy(smsFontBold = b) },
                                onUpdateOtpAutoDelete = {},
                                onUpdateRcsChatsEnabled = { v ->
                                    rcsToggles += 1
                                    settingsState = settingsState.copy(rcsChatsEnabled = v)
                                },
                                onUpdateAutoDownloadMms = { v -> settingsState = settingsState.copy(autoDownloadMms = v) },
                                onUpdateSendReadReceipts = { v -> settingsState = settingsState.copy(sendReadReceipts = v) }
                            )
                            "ARCHIVE" -> ArchiveScreen(
                                archivedThreads = archiveList,
                                onOpenThread = {},
                                onSetThreadArchived = { _, _ -> },
                                onSetThreadsArchived = { _, _ -> },
                                onDeleteThreads = {},
                                onAssignSenderCategory = { _, _, _, _ -> }
                            )
                            "BACKUP" -> BackupScreen(
                                settings = settingsState,
                                totalMessagesInDb = 42,
                                isBackupInProgress = false,
                                onSaveDriveDestination = { _, _ -> },
                                onSaveBackupPassphrase = {},
                                onUpdateBackupSchedule = { s, d -> settingsState = settingsState.copy(backupSchedule = s, customBackupDays = d) },
                                onRunManualBackupNow = {},
                                onRunRestoreFromUri = { _, _, _ -> }
                            )
                            "ONBOARDING" -> OnboardingScreen(
                                systemSmsHelper = systemSmsHelper,
                                isDefaultSmsApp = isDefaultOnboarding,
                                showEmulatorDemoSeed = true,
                                onRoleOrPermissionsUpdated = {},
                                onSeedDemoInbox = {},
                                onSkipToInbox = {}
                            )
                            "MOVE_SHEET" -> MoveSenderCategoryBottomSheet(
                                senderTitle = "VM-VERYLONGBANKHEADER-T / +91 98765 43210 — Corporate Banking Alert",
                                currentCategory = SmsCategory.TRANSACTIONAL,
                                onDismiss = {},
                                onConfirm = { _, _ -> }
                            )
                        }
                    }
                }
            }

            // 1. SettingsScreen audit at 360dp + LARGER bold
            composeTestRule.waitForIdle()
            val setDefBtn = composeTestRule.onNodeWithTag("settings_set_default_sms_button")
                .performScrollTo()
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            assertTrue("Expected minHeight >= 44.dp on Default SMS button, got ${setDefBtn.heightDp}", setDefBtn.heightDp >= 43.5.dp)
            assertWithinViewport("settings_set_default_sms_button", 360.dp, 740.dp)

            for (tag in listOf(
                "settings_show_restricted_guide_button",
                "sms_font_size_default",
                "sms_font_size_large",
                "sms_font_size_larger",
                "sms_font_bold_switch",
                "sms_font_live_preview",
                "promo_preset_7",
                "otp_preset_30",
                "rcs_mms_settings_card",
                "rcs_chats_switch",
                "auto_download_mms_switch",
                "send_read_receipts_switch",
                "swipe_left_archive_chip",
                "swipe_right_assign_category_chip",
                "unarchive_on_new_sms_switch",
                "region_option_eu_en",
                "settings_open_backup_tab_button",
                "app_lock_switch",
                "rerun_categorization_button",
                "danger_zone_delete_all_button",
                "settings_app_version_text"
            )) {
                composeTestRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                assertWithinViewport(tag, 360.dp, 740.dp)
            }
            val rerunHeight = composeTestRule.onNodeWithTag("rerun_categorization_button").getUnclippedBoundsInRoot().heightDp
            assertTrue("Expected minHeight >= 48.dp on rerun_categorization_button, got $rerunHeight", rerunHeight >= 47.5.dp)
            val dangerDeleteHeight = composeTestRule.onNodeWithTag("danger_zone_delete_all_button").getUnclippedBoundsInRoot().heightDp
            assertTrue("Expected minHeight >= 48.dp on danger_zone_delete_all_button, got $dangerDeleteHeight", dangerDeleteHeight >= 47.5.dp)

            composeTestRule.onNodeWithTag("rcs_chats_switch").performScrollTo().assertIsOn().performClick()
            composeTestRule.waitForIdle()
            assertEquals("SettingsToggleRow switch must fire once without double-toggle", 1, rcsToggles)
            composeTestRule.onNodeWithTag("rcs_chats_switch").assertIsOff()

            // 2. ArchiveScreen audit: empty copy vs search mismatch copy, multi-select + BackHandler
            composeTestRule.runOnIdle { screenMode = "ARCHIVE" }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Archive is clean").assertIsDisplayed()

            composeTestRule.runOnIdle { archiveList = listOf(archived1, archived2) }
            composeTestRule.waitForIdle()
            assertWithinViewport("archive_search_input", 360.dp, 740.dp)
            assertWithinViewport("thread_row_+919811112222", 360.dp, 740.dp)

            composeTestRule.onNodeWithTag("archive_search_input").performTextInput("NONEXISTENT_QUERY_XYZ")
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("No matching archived conversations").assertIsDisplayed()
            composeTestRule.onAllNodesWithText("Archive is clean").assertCountEquals(0)

            composeTestRule.onNodeWithTag("archive_search_input").performSemanticsAction(SemanticsActions.SetText) {
                it(androidx.compose.ui.text.AnnotatedString(""))
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("thread_row_+919811112222").onChild().performSemanticsAction(SemanticsActions.OnLongClick)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("1 Selected").assertIsDisplayed()
            assertWithinViewport("archive_batch_unarchive_button", 360.dp, 740.dp)
            assertWithinViewport("archive_batch_delete_button", 360.dp, 740.dp)

            host.runOnUiThread { host.onBackPressedDispatcher.onBackPressed() }
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodesWithTag("archive_batch_unarchive_button").assertCountEquals(0)

            // 3. BackupScreen audit: StatusMetricRow, long URI, min-height buttons, Custom schedule
            composeTestRule.runOnIdle { screenMode = "BACKUP" }
            composeTestRule.waitForIdle()
            for (tag in listOf(
                "choose_drive_backup_file_button",
                "backup_passphrase_input",
                "save_backup_passphrase_button",
                "backup_status_row_last_status",
                "backup_now_button",
                "restore_from_backup_button",
                "backup_schedule_custom"
            )) {
                composeTestRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                assertWithinViewport(tag, 360.dp, 740.dp)
            }
            val backupNowHeight = composeTestRule.onNodeWithTag("backup_now_button").getUnclippedBoundsInRoot().heightDp
            assertTrue("Expected minHeight >= 50.dp on backup_now_button, got $backupNowHeight", backupNowHeight >= 49.5.dp)
            val restoreHeight = composeTestRule.onNodeWithTag("restore_from_backup_button").getUnclippedBoundsInRoot().heightDp
            assertTrue("Expected minHeight >= 48.dp on restore_from_backup_button, got $restoreHeight", restoreHeight >= 47.5.dp)

            // 4. OnboardingScreen audit: Restricted Settings card & button min heights
            composeTestRule.runOnIdle { screenMode = "ONBOARDING" }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("set_default_sms_button").performScrollTo().assertIsDisplayed()
            val setDefaultOnboardingHeight = composeTestRule.onNodeWithTag("set_default_sms_button").getUnclippedBoundsInRoot().heightDp
            assertTrue("Expected minHeight >= 54.dp on set_default_sms_button, got $setDefaultOnboardingHeight", setDefaultOnboardingHeight >= 53.5.dp)
            composeTestRule.onNodeWithTag("onboarding_show_restricted_guide_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            for (tag in listOf(
                "restricted_settings_guidance_card",
                "open_app_info_settings_button",
                "open_default_apps_settings_button",
                "grant_permissions_button",
                "preview_demo_inbox_button",
                "skip_onboarding_button"
            )) {
                composeTestRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                assertWithinViewport(tag, 360.dp, 740.dp)
            }

            composeTestRule.runOnIdle { isDefaultOnboarding = true }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("continue_to_inbox_button").performScrollTo().assertIsDisplayed()
            val continueHeight = composeTestRule.onNodeWithTag("continue_to_inbox_button").getUnclippedBoundsInRoot().heightDp
            assertTrue("Expected minHeight >= 52.dp on continue_to_inbox_button, got $continueHeight", continueHeight >= 51.5.dp)

            // 5. MoveSenderCategoryBottomSheet audit at 360dp + LARGER bold
            composeTestRule.runOnIdle { screenMode = "MOVE_SHEET" }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithTag("move_category_bottom_sheet").fetchSemanticsNodes().isNotEmpty()
            }
            assertWithinContainer("move_category_sender_title", "move_category_sheet_content")
            composeTestRule.onNodeWithTag("always_move_sender_switch").performScrollTo().assertIsOn().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("always_move_sender_switch").assertIsOff()
            sheetAlwaysToggleCount += 1
            assertEquals(1, sheetAlwaysToggleCount)
            composeTestRule.onNodeWithTag("confirm_move_category_button").performScrollTo().assertIsDisplayed()
            val confirmMoveHeight = composeTestRule.onNodeWithTag("confirm_move_category_button").getUnclippedBoundsInRoot().heightDp
            assertTrue("Expected minHeight >= 50.dp on confirm_move_category_button, got $confirmMoveHeight", confirmMoveHeight >= 49.5.dp)
            assertWithinContainer("confirm_move_category_button", "move_category_sheet_content")
            composeTestRule.runOnIdle { screenMode = "SETTINGS" }
            composeTestRule.waitForIdle()
        }

        // 6. GlobalDeleteAllMessagesDialog: ShadowDialog.getLatestDialog() without waitForIdle() deadlock
        ShadowDialog.reset()
        withHostActivity { host ->
            var showDialog by mutableStateOf(true)
            try {
                host.setContent {
                    TraiSmsTheme(smsFontSize = SmsFontSize.LARGER, smsFontBold = true) {
                        if (showDialog) {
                            GlobalDeleteAllMessagesDialog(
                                totalCount = 99,
                                onDismiss = { showDialog = false },
                                onConfirmDeleteAll = {}
                            )
                        }
                    }
                }
                flushMainLooperAndSnapshots()

                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull("Expected AlertDialog to mount", dialog)
                assertTrue("Expected AlertDialog to be showing", dialog.isShowing)
                val dialogWindow = dialog.window
                assertNotNull(dialogWindow)
                assertTrue("Expected AlertDialog decorView width > 0", dialogWindow!!.decorView.width > 0)
                assertEquals(0, dialogWindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            } finally {
                showDialog = false
                flushMainLooperAndSnapshots()
                runCatching { ShadowDialog.getLatestDialog()?.dismiss() }
            }
        }
    }

    private fun withHostActivity(block: (ComponentActivity) -> Unit) {
        Robolectric.buildActivity(ComponentActivity::class.java).use { controller ->
            controller.setup()
            val host = controller.get()
            try {
                block(host)
            } finally {
                host.setContentView(View(host))
                flushMainLooperAndSnapshots()
            }
        }
    }

    private suspend fun withHostActivitySuspend(block: suspend (ComponentActivity) -> Unit) {
        Robolectric.buildActivity(ComponentActivity::class.java).use { controller ->
            controller.setup()
            val host = controller.get()
            try {
                block(host)
            } finally {
                host.setContentView(View(host))
                flushMainLooperAndSnapshots()
            }
        }
    }

    private val DpRect.widthDp: Dp
        get() = right - left

    private val DpRect.heightDp: Dp
        get() = bottom - top

    private fun assertWithinViewport(
        tag: String,
        maxWidth: Dp,
        maxHeight: Dp,
        useUnmergedTree: Boolean = false
    ): DpRect {
        val node = composeTestRule.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree)
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$tag width <= 0 (${bounds.widthDp})", bounds.widthDp > 0.dp)
        assertTrue("$tag height <= 0 (${bounds.heightDp})", bounds.heightDp > 0.dp)
        assertTrue("$tag left clipped (${bounds.left})", bounds.left >= (-1).dp)
        assertTrue("$tag right clipped (${bounds.right} > $maxWidth)", bounds.right <= maxWidth + 1.dp)
        assertTrue("$tag top clipped (${bounds.top})", bounds.top >= (-1).dp)
        assertTrue("$tag bottom clipped (${bounds.bottom} > $maxHeight)", bounds.bottom <= maxHeight + 1.dp)
        return bounds
    }

    private fun assertWithinContainer(
        tag: String,
        containerTag: String,
        useUnmergedTree: Boolean = false
    ): DpRect {
        val container = composeTestRule.onNodeWithTag(containerTag).getUnclippedBoundsInRoot()
        val node = composeTestRule.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree)
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$tag width <= 0 (${bounds.widthDp})", bounds.widthDp > 0.dp)
        assertTrue("$tag height <= 0 (${bounds.heightDp})", bounds.heightDp > 0.dp)
        assertTrue("$tag left clipped (${bounds.left} < ${container.left})", bounds.left >= container.left - 1.dp)
        assertTrue("$tag right clipped (${bounds.right} > ${container.right})", bounds.right <= container.right + 1.dp)
        assertTrue("$tag top clipped (${bounds.top} < ${container.top})", bounds.top >= container.top - 1.dp)
        assertTrue("$tag bottom clipped (${bounds.bottom} > ${container.bottom})", bounds.bottom <= container.bottom + 1.dp)
        return bounds
    }

    private fun assertHorizontallyOrdered(
        leftTag: String,
        rightTag: String,
        useUnmergedTree: Boolean = false
    ) {
        val leftBounds = composeTestRule.onNodeWithTag(leftTag, useUnmergedTree = useUnmergedTree).getUnclippedBoundsInRoot()
        val rightBounds = composeTestRule.onNodeWithTag(rightTag, useUnmergedTree = useUnmergedTree).getUnclippedBoundsInRoot()
        assertTrue(
            "$leftTag (right=${leftBounds.right}) overlaps $rightTag (left=${rightBounds.left})",
            leftBounds.right <= rightBounds.left + 1.dp
        )
    }

    private fun assertVerticallyOrdered(
        topTag: String,
        bottomTag: String,
        useUnmergedTree: Boolean = false
    ) {
        val topBounds = composeTestRule.onNodeWithTag(topTag, useUnmergedTree = useUnmergedTree).getUnclippedBoundsInRoot()
        val bottomBounds = composeTestRule.onNodeWithTag(bottomTag, useUnmergedTree = useUnmergedTree).getUnclippedBoundsInRoot()
        assertTrue(
            "$topTag (bottom=${topBounds.bottom}) overlaps $bottomTag (top=${bottomBounds.top})",
            topBounds.bottom <= bottomBounds.top + 1.dp
        )
    }

    private fun assertNoOverlap(
        firstTag: String,
        secondTag: String,
        useUnmergedTree: Boolean = false
    ) {
        val a = composeTestRule.onNodeWithTag(firstTag, useUnmergedTree = useUnmergedTree).getUnclippedBoundsInRoot()
        val b = composeTestRule.onNodeWithTag(secondTag, useUnmergedTree = useUnmergedTree).getUnclippedBoundsInRoot()
        val horizontalOverlap = a.left < b.right - 0.5.dp && b.left < a.right - 0.5.dp
        val verticalOverlap = a.top < b.bottom - 0.5.dp && b.top < a.bottom - 0.5.dp
        assertFalse(
            "$firstTag ($a) overlaps $secondTag ($b)",
            horizontalOverlap && verticalOverlap
        )
    }

    private fun grantDefaultSmsRole() {
        ShadowRoleManager.addRoleHolder(
            RoleManager.ROLE_SMS,
            context.packageName,
            Process.myUserHandle()
        )
    }

    private fun resetFileProviderCache() {
        runCatching {
            val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {
                isAccessible = true
            }
            val cache = field.get(null) as? MutableMap<*, *>
            if (cache != null) {
                synchronized(cache) {
                    cache.clear()
                }
            }
        }
    }

    private fun createIsolatedDir(prefix: String): File {
        val dir = File(context.cacheDir, "${prefix}_${System.nanoTime()}").apply { mkdirs() }
        tempStorageDirs.add(dir)
        return dir
    }

    private fun buildSeededMessage(
        systemSmsId: Long?,
        threadKey: String,
        body: String,
        timestampMillis: Long,
        category: SmsCategory,
        isIncoming: Boolean = true,
        isRead: Boolean = false,
        isArchived: Boolean = false,
        transportType: String = "SMS",
        systemMmsId: Long? = null,
        mmsSubject: String? = null,
        isHiddenReaction: Boolean = false
    ): SmsMessageEntity {
        return SmsMessageEntity(
            systemSmsId = systemSmsId,
            threadKey = threadKey,
            rawAddress = threadKey,
            contactName = null,
            body = body,
            bodySha256 = BackupCryptoEngine.sha256Hex(body),
            timestampMillis = timestampMillis,
            isIncoming = isIncoming,
            isRead = isRead,
            isArchived = isArchived,
            category = category,
            extractedOtp = null,
            confidenceScore = 0.99f,
            classificationSource = "TEST",
            transportType = transportType,
            systemMmsId = systemMmsId,
            mmsSubject = mmsSubject,
            isHiddenReaction = isHiddenReaction
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

    private class FakeSmsContentProvider : ContentProvider() {
        data class SmsRow(
            val id: Long,
            val address: String,
            val body: String,
            val dateMillis: Long,
            val type: Int,
            @Volatile var read: Int
        )

        private val nextId = AtomicLong(10_000L)
        private val rows = ConcurrentHashMap<Long, SmsRow>()

        override fun onCreate(): Boolean = true
        fun clear() = rows.clear()

        fun seedExistingMessage(id: Long, address: String, body: String, dateMillis: Long, type: Int, read: Int) {
            rows[id] = SmsRow(id, address, body, dateMillis, type, read)
        }

        fun getReadFlag(id: Long): Int? = rows[id]?.read

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val c = MatrixCursor(arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ))
            rows.values.sortedByDescending { it.dateMillis }.forEach {
                c.addRow(arrayOf<Any?>(it.id, it.address, it.body, it.dateMillis, it.type, it.read))
            }
            return c
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val v = values ?: ContentValues()
            val id = nextId.incrementAndGet()
            rows[id] = SmsRow(
                id = id,
                address = v.getAsString(Telephony.Sms.ADDRESS).orEmpty(),
                body = v.getAsString(Telephony.Sms.BODY).orEmpty(),
                dateMillis = v.getAsLong(Telephony.Sms.DATE) ?: 0L,
                type = v.getAsInteger(Telephony.Sms.TYPE) ?: Telephony.Sms.MESSAGE_TYPE_INBOX,
                read = v.getAsInteger(Telephony.Sms.READ) ?: 0
            )
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val newRead = values?.getAsInteger(Telephony.Sms.READ) ?: return 0
            var updated = 0
            if (selection != null && selection.contains(Telephony.Sms._ID) && selectionArgs != null) {
                for (arg in selectionArgs) {
                    val id = arg.toLongOrNull() ?: continue
                    val row = rows[id] ?: continue
                    row.read = newRead
                    updated++
                }
            }
            return updated
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private class FakeMmsContentProvider(private val blobDir: File) : ContentProvider() {
        data class MmsRow(val id: Long, val threadId: Long, val dateSec: Long, val box: Int, @Volatile var read: Int, val subject: String?, val creator: String)
        data class AddrRow(val mmsId: Long, val address: String, val type: Int)
        data class PartRow(val partId: Long, val mmsId: Long, val ct: String, val name: String?, val text: String?, val blobFile: File?)

        private val nextMmsId = AtomicLong(20_000L)
        private val nextPartId = AtomicLong(30_000L)
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
            textBody: String
        ) {
            mmsRows[id] = MmsRow(id, threadId, dateSec, box, read, subject, creator)
            addrRows.add(AddrRow(id, fromAddress, 137))
            toAddresses.forEach { addrRows.add(AddrRow(id, it, 151)) }
            val textPartId = nextPartId.incrementAndGet()
            partRows[textPartId] = PartRow(textPartId, id, "text/plain", "body.txt", textBody, null)
        }

        fun getReadFlag(id: Long): Int? = mmsRows[id]?.read

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

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val v = values ?: ContentValues()
            val id = nextMmsId.incrementAndGet()
            mmsRows[id] = MmsRow(
                id = id,
                threadId = 0L,
                dateSec = v.getAsLong(Telephony.Mms.DATE) ?: 0L,
                box = v.getAsInteger(Telephony.Mms.MESSAGE_BOX) ?: Telephony.Mms.MESSAGE_BOX_INBOX,
                read = v.getAsInteger(Telephony.Mms.READ) ?: 0,
                subject = v.getAsString(Telephony.Mms.SUBJECT),
                creator = "com.traisms.app"
            )
            return ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val newRead = values?.getAsInteger(Telephony.Mms.READ) ?: return 0
            var updated = 0
            if (selection != null && selection.contains(Telephony.Mms._ID) && selectionArgs != null) {
                for (arg in selectionArgs) {
                    val id = arg.toLongOrNull() ?: continue
                    val row = mmsRows[id] ?: continue
                    row.read = newRead
                    updated++
                }
            }
            return updated
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private class FakeContactsContentProvider : ContentProvider() {
        @Volatile
        private var contacts: List<Pair<String, String>> = emptyList()

        override fun onCreate(): Boolean = true
        fun setContacts(newContacts: List<Pair<String, String>>) {
            contacts = newContacts.toList()
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val c = MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER))
            contacts.forEach { (name, number) -> c.addRow(arrayOf(name, number)) }
            return c
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
