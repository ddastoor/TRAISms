package com.example

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.Process
import android.provider.Telephony
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.crypto.LeftSwipeAction
import com.example.data.db.CategoryUnreadCount
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.db.ThreadSummary
import com.example.data.sms.SmsNotificationHelper
import com.example.data.sms.SyncProgressState
import com.example.data.sms.SystemSmsHelper
import com.example.ui.MainViewModel
import com.example.ui.messages.MessagesScreen
import com.example.ui.theme.TraiSmsTheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowRoleManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class CategoryMarkAllReadRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var app: TraiSmsApp
    private lateinit var systemSmsHelper: SystemSmsHelper
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationHelper: SmsNotificationHelper
    private lateinit var smsProvider: FakeSmsContentProvider

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        app = context as TraiSmsApp
        systemSmsHelper = SystemSmsHelper(context)

        Shadows.shadowOf(app as Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS
        )
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        notificationHelper = SmsNotificationHelper(context)

        smsProvider = FakeSmsContentProvider()
        smsProvider.attachInfo(context, ProviderInfo().apply { authority = "sms" })
        ShadowContentResolver.registerProviderInternal("sms", smsProvider)

        ShadowRoleManager.reset()

        app.container.database.smsDao().deleteAllMessages()
        app.container.settingsStore.updateSettings {
            AppSettings(
                initialSyncCompleted = true,
                onboardingDismissed = true,
                categoryShowUnreadOnly = emptyMap()
            )
        }
    }

    @After
    fun tearDown() = runBlocking {
        notificationManager.cancelAll()
        smsProvider.clear()
        ShadowRoleManager.reset()
        app.container.database.smsDao().deleteAllMessages()
        app.container.settingsStore.updateSettings {
            it.copy(categoryShowUnreadOnly = emptyMap())
        }
    }

    @Test
    fun `markAllActiveInCategoryAsRead marks strictly active unread messages in target category updates Telephony Sms cancels category notifications and preserves archived and other categories`() = runBlocking {
        grantDefaultSmsRole()
        assertTrue(app.container.systemSmsHelper.isDefaultSmsApp())

        val smsDao = app.container.database.smsDao()
        val repository = app.container.smsRepository
        val baseTime = 1_700_300_000_000L

        // PERSONAL: 2 active unread, 1 active read, and 1 ARCHIVED unread
        val personalUnreadThread1 = "+919811112222"
        val personalUnreadThread2 = "+919833334444"
        val personalReadThread = "+919855556666"
        val archivedPersonalThread = "+919877778888"

        smsProvider.seedExistingMessage(
            id = 5001L,
            address = personalUnreadThread1,
            body = "Are we meeting at 7 PM?",
            dateMillis = baseTime + 8_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        val personalUnreadId1 = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 5001L,
                threadKey = personalUnreadThread1,
                body = "Are we meeting at 7 PM?",
                timestampMillis = baseTime + 8_000L,
                category = SmsCategory.PERSONAL,
                isRead = false,
                isArchived = false
            )
        )

        smsProvider.seedExistingMessage(
            id = 5002L,
            address = personalUnreadThread2,
            body = "Call me once you reach.",
            dateMillis = baseTime + 7_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        val personalUnreadId2 = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 5002L,
                threadKey = personalUnreadThread2,
                body = "Call me once you reach.",
                timestampMillis = baseTime + 7_000L,
                category = SmsCategory.PERSONAL,
                isRead = false,
                isArchived = false
            )
        )

        smsProvider.seedExistingMessage(
            id = 5003L,
            address = personalReadThread,
            body = "Earlier read conversation",
            dateMillis = baseTime + 6_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 1
        )
        val personalReadId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 5003L,
                threadKey = personalReadThread,
                body = "Earlier read conversation",
                timestampMillis = baseTime + 6_000L,
                category = SmsCategory.PERSONAL,
                isRead = true,
                isArchived = false
            )
        )

        smsProvider.seedExistingMessage(
            id = 5004L,
            address = archivedPersonalThread,
            body = "Archived unread personal message",
            dateMillis = baseTime + 5_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        val archivedPersonalUnreadId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 5004L,
                threadKey = archivedPersonalThread,
                body = "Archived unread personal message",
                timestampMillis = baseTime + 5_000L,
                category = SmsCategory.PERSONAL,
                isRead = false,
                isArchived = true
            )
        )

        // TRANSACTIONAL: 2 active unread messages
        val txnUnreadThread1 = "VM-HDFCBK-T"
        val txnUnreadThread2 = "AD-ICICIB-T"

        smsProvider.seedExistingMessage(
            id = 5005L,
            address = txnUnreadThread1,
            body = "Acct XX9012 credited with INR 18,500.00 via IMPS.",
            dateMillis = baseTime + 4_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        val txnUnreadId1 = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 5005L,
                threadKey = txnUnreadThread1,
                body = "Acct XX9012 credited with INR 18,500.00 via IMPS.",
                timestampMillis = baseTime + 4_000L,
                category = SmsCategory.TRANSACTIONAL,
                isRead = false,
                isArchived = false
            )
        )

        smsProvider.seedExistingMessage(
            id = 5006L,
            address = txnUnreadThread2,
            body = "Acct XX1234 debited for INR 950.00 via UPI.",
            dateMillis = baseTime + 3_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        val txnUnreadId2 = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 5006L,
                threadKey = txnUnreadThread2,
                body = "Acct XX1234 debited for INR 950.00 via UPI.",
                timestampMillis = baseTime + 3_000L,
                category = SmsCategory.TRANSACTIONAL,
                isRead = false,
                isArchived = false
            )
        )

        // OTP: 1 active unread message
        val otpUnreadThread = "VM-SBIBNK-T"
        smsProvider.seedExistingMessage(
            id = 5007L,
            address = otpUnreadThread,
            body = "482910 is your SBI login verification OTP.",
            dateMillis = baseTime + 2_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        val otpUnreadId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 5007L,
                threadKey = otpUnreadThread,
                body = "482910 is your SBI login verification OTP.",
                timestampMillis = baseTime + 2_000L,
                category = SmsCategory.OTP,
                isRead = false,
                isArchived = false,
                extractedOtp = "482910"
            )
        )

        // SERVICE: 1 active unread message
        val serviceUnreadThread = "AX-AIRTEL-S"
        smsProvider.seedExistingMessage(
            id = 5008L,
            address = serviceUnreadThread,
            body = "Your Airtel Fiber invoice is ready.",
            dateMillis = baseTime + 1_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        val serviceUnreadId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 5008L,
                threadKey = serviceUnreadThread,
                body = "Your Airtel Fiber invoice is ready.",
                timestampMillis = baseTime + 1_000L,
                category = SmsCategory.SERVICE,
                isRead = false,
                isArchived = false
            )
        )

        // Post active notifications for one unread PERSONAL thread and one unread TRANSACTIONAL thread
        notificationHelper.postIncomingSmsNotification(
            messageId = personalUnreadId1,
            threadKey = personalUnreadThread1,
            rawAddress = personalUnreadThread1,
            senderTitle = "Alice Sharma",
            body = "Are we meeting at 7 PM?",
            category = SmsCategory.PERSONAL,
            extractedOtp = null,
            redactPreviewForAppLock = false
        )
        notificationHelper.postIncomingSmsNotification(
            messageId = txnUnreadId1,
            threadKey = txnUnreadThread1,
            rawAddress = txnUnreadThread1,
            senderTitle = txnUnreadThread1,
            body = "Acct XX9012 credited with INR 18,500.00 via IMPS.",
            category = SmsCategory.TRANSACTIONAL,
            extractedOtp = null,
            redactPreviewForAppLock = false
        )

        val personalNotifId = SmsNotificationHelper.notificationIdForThread(personalUnreadThread1)
        val transactionalNotifId = SmsNotificationHelper.notificationIdForThread(txnUnreadThread1)
        assertNotNull(
            "Precondition: PERSONAL notification must be active before markAllActiveInCategoryAsRead",
            Shadows.shadowOf(notificationManager).getNotification(personalNotifId)
        )
        assertNotNull(
            "Precondition: TRANSACTIONAL notification must be active before markAllActiveInCategoryAsRead",
            Shadows.shadowOf(notificationManager).getNotification(transactionalNotifId)
        )

        // Verify DAO lookup queries strictly scope to active unread PERSONAL rows before mutation
        assertEquals(
            setOf(5001L, 5002L),
            smsDao.getUnreadSystemIdsInActiveCategory(SmsCategory.PERSONAL).toSet()
        )
        assertEquals(
            setOf(personalUnreadThread1, personalUnreadThread2),
            smsDao.getUnreadThreadKeysInActiveCategory(SmsCategory.PERSONAL).toSet()
        )

        // Invoke markAllActiveInCategoryAsRead(SmsCategory.PERSONAL) -> must mark strictly the 2 active unread PERSONAL messages
        val markedCount = repository.markAllActiveInCategoryAsRead(SmsCategory.PERSONAL)
        assertEquals(2, markedCount)

        // Verify Room entities across every seeded message
        val messagesById = smsDao.getAllMessagesSnapshot().associateBy { it.id }
        assertEquals(8, messagesById.size)

        assertTrue(messagesById.getValue(personalUnreadId1).isRead)
        assertFalse(messagesById.getValue(personalUnreadId1).isArchived)

        assertTrue(messagesById.getValue(personalUnreadId2).isRead)
        assertFalse(messagesById.getValue(personalUnreadId2).isArchived)

        assertTrue(messagesById.getValue(personalReadId).isRead)
        assertFalse(messagesById.getValue(personalReadId).isArchived)

        // Archived PERSONAL message MUST remain unread (isRead = false) and archived
        assertFalse(
            "Archived PERSONAL message must remain isRead = false",
            messagesById.getValue(archivedPersonalUnreadId).isRead
        )
        assertTrue(messagesById.getValue(archivedPersonalUnreadId).isArchived)

        // TRANSACTIONAL, OTP, and SERVICE unread messages MUST remain unread (isRead = false)
        assertFalse(messagesById.getValue(txnUnreadId1).isRead)
        assertFalse(messagesById.getValue(txnUnreadId2).isRead)
        assertFalse(messagesById.getValue(otpUnreadId).isRead)
        assertFalse(messagesById.getValue(serviceUnreadId).isRead)

        // Verify observeCategoryCounts() reflects PERSONAL unreadCount == 0 while preserving all other categories
        val countsByCategory = repository.observeCategoryCounts().first().associateBy { it.category }
        val personalCounts = countsByCategory.getValue(SmsCategory.PERSONAL)
        assertEquals(0, personalCounts.unreadCount)
        assertEquals(3, personalCounts.totalCount)

        val txnCounts = countsByCategory.getValue(SmsCategory.TRANSACTIONAL)
        assertEquals(2, txnCounts.unreadCount)
        assertEquals(2, txnCounts.totalCount)

        val otpCounts = countsByCategory.getValue(SmsCategory.OTP)
        assertEquals(1, otpCounts.unreadCount)
        assertEquals(1, otpCounts.totalCount)

        val serviceCounts = countsByCategory.getValue(SmsCategory.SERVICE)
        assertEquals(1, serviceCounts.unreadCount)
        assertEquals(1, serviceCounts.totalCount)

        // Verify unread vs all thread flows and archived threads flow
        assertTrue(
            repository.observeCategoryThreads(SmsCategory.PERSONAL, unreadOnly = true)
                .first()
                .isEmpty()
        )
        val allActivePersonalThreads = repository.observeCategoryThreads(
            category = SmsCategory.PERSONAL,
            unreadOnly = false
        ).first()
        assertEquals(3, allActivePersonalThreads.size)
        assertTrue(allActivePersonalThreads.all { it.unreadCount == 0 })

        val archivedSummaries = repository.observeArchivedThreads().first()
        assertEquals(listOf(archivedPersonalThread), archivedSummaries.map { it.threadKey })
        assertEquals(1, archivedSummaries.single().unreadCount)

        // Verify notifications: PERSONAL notification cancelled, TRANSACTIONAL notification still active
        assertNull(
            "PERSONAL thread notification must be cancelled when PERSONAL is marked read",
            Shadows.shadowOf(notificationManager).getNotification(personalNotifId)
        )
        assertNotNull(
            "TRANSACTIONAL thread notification must remain active when PERSONAL is marked read",
            Shadows.shadowOf(notificationManager).getNotification(transactionalNotifId)
        )

        // Verify Android Telephony.Sms ContentProvider READ flags
        assertEquals(1, smsProvider.getReadFlag(5001L))
        assertEquals(1, smsProvider.getReadFlag(5002L))
        assertEquals(1, smsProvider.getReadFlag(5003L))
        assertEquals(
            "Archived PERSONAL provider row must not be marked READ=1",
            0,
            smsProvider.getReadFlag(5004L)
        )
        assertEquals(0, smsProvider.getReadFlag(5005L))
        assertEquals(0, smsProvider.getReadFlag(5006L))
        assertEquals(0, smsProvider.getReadFlag(5007L))
        assertEquals(0, smsProvider.getReadFlag(5008L))

        // Calling markAllActiveInCategoryAsRead(PERSONAL) again when 0 active unread remain is an idempotent no-op
        assertEquals(0, repository.markAllActiveInCategoryAsRead(SmsCategory.PERSONAL))
    }

    @Test
    fun `MessagesScreen inline category_mark_all_read_button and overflow menu_mark_all_read_in_tab isolate read state strictly per tab`() {
        val personalUnread1 = buildThreadSummary(
            id = 101L,
            threadKey = "+919811112222",
            contactName = "Alice Sharma",
            body = "Dinner at 8 PM tonight?",
            timestampMillis = 1_700_400_050_000L,
            category = SmsCategory.PERSONAL,
            unreadCount = 1,
            messageCount = 2
        )
        val personalUnread2 = buildThreadSummary(
            id = 102L,
            threadKey = "+919833334444",
            contactName = "Bob Patel",
            body = "Can you send the address?",
            timestampMillis = 1_700_400_040_000L,
            category = SmsCategory.PERSONAL,
            unreadCount = 1,
            messageCount = 1
        )
        val personalRead = buildThreadSummary(
            id = 103L,
            threadKey = "+919855556666",
            contactName = "Charlie Singh",
            body = "Reached home safely.",
            timestampMillis = 1_700_400_030_000L,
            category = SmsCategory.PERSONAL,
            unreadCount = 0,
            messageCount = 1
        )

        val transactionalUnread1 = buildThreadSummary(
            id = 201L,
            threadKey = "VM-HDFCBK-T",
            contactName = null,
            body = "Acct XX9012 credited with INR 25,000.00",
            timestampMillis = 1_700_400_060_000L,
            category = SmsCategory.TRANSACTIONAL,
            unreadCount = 2,
            messageCount = 2
        )
        val transactionalUnread2 = buildThreadSummary(
            id = 202L,
            threadKey = "AD-ICICIB-T",
            contactName = null,
            body = "Acct XX1234 debited for INR 500.00",
            timestampMillis = 1_700_400_020_000L,
            category = SmsCategory.TRANSACTIONAL,
            unreadCount = 1,
            messageCount = 1
        )
        val transactionalRead = buildThreadSummary(
            id = 203L,
            threadKey = "AX-SBIBNK-T",
            contactName = null,
            body = "Monthly statement generated.",
            timestampMillis = 1_700_400_010_000L,
            category = SmsCategory.TRANSACTIONAL,
            unreadCount = 0,
            messageCount = 1
        )

        val otpUnread = buildThreadSummary(
            id = 301L,
            threadKey = "VM-OTPBANK-T",
            contactName = null,
            body = "739104 is your login OTP.",
            timestampMillis = 1_700_400_070_000L,
            category = SmsCategory.OTP,
            unreadCount = 1,
            messageCount = 1
        )

        var threadsByCategory by mutableStateOf(
            mapOf(
                SmsCategory.PERSONAL to listOf(personalUnread1, personalUnread2, personalRead),
                SmsCategory.TRANSACTIONAL to listOf(
                    transactionalUnread1,
                    transactionalUnread2,
                    transactionalRead
                ),
                SmsCategory.OTP to listOf(otpUnread)
            )
        )
        var selectedCategory by mutableStateOf(SmsCategory.PERSONAL)
        var searchQuery by mutableStateOf("")
        var uiSettings by mutableStateOf(AppSettings())
        val recordedMarkReadCalls = mutableListOf<Pair<SmsCategory, Int>>()

        composeTestRule.setContent {
            val countsByCategory = threadsByCategory.mapValues { (category, list) ->
                CategoryUnreadCount(
                    category = category,
                    unreadCount = list.sumOf { it.unreadCount },
                    totalCount = list.sumOf { it.messageCount }
                )
            }
            val showUnreadOnly = uiSettings.isCategoryUnreadOnly(selectedCategory)
            val allInTab = threadsByCategory[selectedCategory].orEmpty()
            val displayedThreads = if (searchQuery.isNotBlank()) {
                threadsByCategory.values.flatten().filter {
                    it.latestBody.contains(searchQuery, ignoreCase = true) ||
                        it.rawAddress.contains(searchQuery, ignoreCase = true)
                }
            } else if (showUnreadOnly) {
                allInTab.filter { it.unreadCount > 0 }
            } else {
                allInTab
            }

            TraiSmsTheme {
                Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                    MessagesScreen(
                        selectedCategory = selectedCategory,
                        threads = displayedThreads,
                        categoryCounts = countsByCategory,
                        totalMessageCount = countsByCategory.values.sumOf { it.totalCount },
                        searchQuery = searchQuery,
                        leftSwipeAction = LeftSwipeAction.ARCHIVE,
                        activeRegionBadge = "IN • India",
                        syncProgress = SyncProgressState(),
                        isDefaultSmsApp = true,
                        showSetupBanner = false,
                        showEmulatorDemoBanner = false,
                        systemSmsHelper = systemSmsHelper,
                        onSelectCategory = { selectedCategory = it },
                        onSearchQueryChange = { searchQuery = it },
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
                        showUnreadOnly = showUnreadOnly,
                        onToggleCategoryUnreadOnly = { category, unreadOnly ->
                            uiSettings = uiSettings.copy(
                                categoryShowUnreadOnly = uiSettings.categoryShowUnreadOnly +
                                    (category.name to unreadOnly)
                            )
                        },
                        onMarkAllReadInCurrentTab = { targetCategory, onDone ->
                            val currentThreads = threadsByCategory[targetCategory].orEmpty()
                            val markedCount = currentThreads.sumOf { it.unreadCount }
                            threadsByCategory = threadsByCategory + (
                                targetCategory to currentThreads.map { it.copy(unreadCount = 0) }
                            )
                            recordedMarkReadCalls.add(targetCategory to markedCount)
                            onDone(markedCount)
                        }
                    )
                }
            }
        }

        // 1. On PERSONAL (unreadCount = 2), inline category_mark_all_read_button ("Mark all as read") is displayed
        composeTestRule.onNodeWithTag("category_unread_filter_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("category_unread_only_switch")
            .assertIsDisplayed()
            .assertIsOn()
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("Unread only (2)")
        composeTestRule.onNodeWithTag("category_mark_all_read_button")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertTextContains("Mark all as read")

        composeTestRule.onNodeWithTag("thread_row_+919811112222").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thread_row_+919833334444").assertIsDisplayed()

        // Clicking inline category_mark_all_read_button marks ONLY PERSONAL read without toggling Show Unread switch
        composeTestRule.onNodeWithTag("category_mark_all_read_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(SmsCategory.PERSONAL to 2), recordedMarkReadCalls)
        assertEquals(0, threadsByCategory.getValue(SmsCategory.PERSONAL).sumOf { it.unreadCount })
        assertEquals(3, threadsByCategory.getValue(SmsCategory.TRANSACTIONAL).sumOf { it.unreadCount })
        assertEquals(1, threadsByCategory.getValue(SmsCategory.OTP).sumOf { it.unreadCount })

        composeTestRule.onNodeWithTag("category_unread_only_switch").assertIsOn()
        composeTestRule.onAllNodesWithTag("category_mark_all_read_button").assertCountEquals(0)
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("Unread only (0)")
        composeTestRule.onNodeWithText("Marked 2 Personal message(s) as read").assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()

        // Overflow menu item menu_mark_all_read_in_tab is disabled on PERSONAL once unreadCount == 0
        composeTestRule.onNodeWithTag("inbox_overflow_menu_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("menu_mark_all_read_in_tab")
            .assertIsDisplayed()
            .assertTextContains("Mark all as read in Personal")
            .assertIsNotEnabled()

        // Close overflow menu cleanly before switching tabs
        composeTestRule.onNodeWithTag("menu_sync_sms").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("menu_mark_all_read_in_tab").assertCountEquals(0)

        // 2. Switch to tab_transactional -> TRANSACTIONAL's 3 unread messages remain untouched
        composeTestRule.onNodeWithTag("tab_transactional")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(SmsCategory.TRANSACTIONAL, selectedCategory)
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("Unread only (3)")
        composeTestRule.onNodeWithTag("category_mark_all_read_button")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertTextContains("Mark all as read")
        composeTestRule.onNodeWithTag("thread_row_VM-HDFCBK-T").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thread_row_AD-ICICIB-T").assertIsDisplayed()

        // Open inbox_overflow_menu_button -> click menu_mark_all_read_in_tab ("Mark all as read in Transactional")
        composeTestRule.onNodeWithTag("inbox_overflow_menu_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("menu_mark_all_read_in_tab")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertTextContains("Mark all as read in Transactional")
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            listOf(SmsCategory.PERSONAL to 2, SmsCategory.TRANSACTIONAL to 3),
            recordedMarkReadCalls
        )
        assertEquals(0, threadsByCategory.getValue(SmsCategory.TRANSACTIONAL).sumOf { it.unreadCount })
        assertEquals(1, threadsByCategory.getValue(SmsCategory.OTP).sumOf { it.unreadCount })

        composeTestRule.onAllNodesWithTag("menu_mark_all_read_in_tab").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("category_mark_all_read_button").assertCountEquals(0)
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("Unread only (0)")
        composeTestRule.onNodeWithText("Marked 3 Transactional message(s) as read").assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()

        // Re-opening overflow menu on TRANSACTIONAL now shows menu_mark_all_read_in_tab disabled
        composeTestRule.onNodeWithTag("inbox_overflow_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_mark_all_read_in_tab")
            .assertIsDisplayed()
            .assertTextContains("Mark all as read in Transactional")
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("menu_sync_sms").performClick()
        composeTestRule.waitForIdle()

        // OTP tab's unread message remains untouched after both PERSONAL and TRANSACTIONAL actions
        composeTestRule.runOnIdle { selectedCategory = SmsCategory.OTP }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("category_mark_all_read_button")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithTag("thread_row_VM-OTPBANK-T").assertIsDisplayed()
    }

    @Test
    fun `MainViewModel markAllReadInCategory updates categoryCounts and activeThreads reactively without affecting other categories or archived threads`() = runBlocking {
        val smsDao = app.container.database.smsDao()
        val baseTime = 1_700_500_000_000L

        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 6001L,
                threadKey = "+919811112222",
                body = "First unread personal message",
                timestampMillis = baseTime + 4_000L,
                category = SmsCategory.PERSONAL,
                isRead = false,
                isArchived = false
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 6002L,
                threadKey = "+919833334444",
                body = "Second unread personal message",
                timestampMillis = baseTime + 3_000L,
                category = SmsCategory.PERSONAL,
                isRead = false,
                isArchived = false
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 6003L,
                threadKey = "+919877778888",
                body = "Archived unread personal message",
                timestampMillis = baseTime + 2_000L,
                category = SmsCategory.PERSONAL,
                isRead = false,
                isArchived = true
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 6004L,
                threadKey = "VM-HDFCBK-T",
                body = "Unread transactional credit alert",
                timestampMillis = baseTime + 5_000L,
                category = SmsCategory.TRANSACTIONAL,
                isRead = false,
                isArchived = false
            )
        )

        val viewModel = MainViewModel(app.container)
        val threadsSub = launch(Dispatchers.Unconfined) {
            viewModel.activeThreads.collect {}
        }
        val countsSub = launch(Dispatchers.Unconfined) {
            viewModel.categoryCounts.collect {}
        }

        try {
            waitForCondition {
                viewModel.categoryCounts.value[SmsCategory.PERSONAL]?.unreadCount == 2 &&
                    viewModel.categoryCounts.value[SmsCategory.TRANSACTIONAL]?.unreadCount == 1 &&
                    viewModel.activeThreads.value.map { it.threadKey } ==
                    listOf("+919811112222", "+919833334444")
            }

            val callbackCount = AtomicInteger(-1)
            viewModel.markAllReadInCategory(SmsCategory.PERSONAL) { marked ->
                callbackCount.set(marked)
            }

            waitForCondition {
                callbackCount.get() == 2 &&
                    viewModel.categoryCounts.value[SmsCategory.PERSONAL]?.unreadCount == 0 &&
                    viewModel.activeThreads.value.isEmpty()
            }

            assertEquals(2, viewModel.categoryCounts.value[SmsCategory.PERSONAL]?.totalCount)
            assertEquals(1, viewModel.categoryCounts.value[SmsCategory.TRANSACTIONAL]?.unreadCount)

            // Default parameter overload compiles and executes cleanly as a no-op when already read
            viewModel.markAllReadInCategory(SmsCategory.PERSONAL)

            // Archived PERSONAL message remains unread
            val archivedMsg = smsDao.observeThreadMessages("+919877778888").first().single()
            assertFalse(archivedMsg.isRead)
            assertTrue(archivedMsg.isArchived)

            // Switching to TRANSACTIONAL confirms its unread thread is untouched
            viewModel.selectCategory(SmsCategory.TRANSACTIONAL)
            waitForCondition {
                viewModel.activeThreads.value.map { it.threadKey } == listOf("VM-HDFCBK-T")
            }
        } finally {
            threadsSub.cancel()
            countsSub.cancel()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun grantDefaultSmsRole() {
        ShadowRoleManager.addRoleHolder(
            RoleManager.ROLE_SMS,
            context.packageName,
            Process.myUserHandle()
        )
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
        extractedOtp: String? = null
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
            extractedOtp = extractedOtp,
            confidenceScore = 0.99f,
            classificationSource = "TEST"
        )
    }

    private fun buildThreadSummary(
        id: Long,
        threadKey: String,
        contactName: String?,
        body: String,
        timestampMillis: Long,
        category: SmsCategory,
        unreadCount: Int,
        messageCount: Int
    ): ThreadSummary {
        return ThreadSummary(
            latestMessageId = id,
            threadKey = threadKey,
            rawAddress = threadKey,
            contactName = contactName,
            latestBody = body,
            latestTimestampMillis = timestampMillis,
            category = category,
            extractedOtp = null,
            isArchived = false,
            unreadCount = unreadCount,
            messageCount = messageCount,
            classificationSource = "TEST"
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

    private class FakeSmsContentProvider : ContentProvider() {

        data class SmsRow(
            val id: Long,
            val address: String,
            val body: String,
            val dateMillis: Long,
            val type: Int,
            @Volatile var read: Int
        )

        private val nextId = AtomicLong(9_000L)
        private val rowsById = ConcurrentHashMap<Long, SmsRow>()
        val recordedUpdates = CopyOnWriteArrayList<ContentValues>()

        override fun onCreate(): Boolean = true

        fun clear() {
            rowsById.clear()
            recordedUpdates.clear()
        }

        fun seedExistingMessage(
            id: Long,
            address: String,
            body: String,
            dateMillis: Long,
            type: Int,
            read: Int
        ) {
            rowsById[id] = SmsRow(
                id = id,
                address = address,
                body = body,
                dateMillis = dateMillis,
                type = type,
                read = read
            )
        }

        fun getReadFlag(id: Long): Int? = rowsById[id]?.read

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val cursor = MatrixCursor(
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.READ
                )
            )
            for (row in rowsById.values.sortedByDescending { it.dateMillis }) {
                cursor.addRow(
                    arrayOf<Any?>(
                        row.id,
                        row.address,
                        row.body,
                        row.dateMillis,
                        row.type,
                        row.read
                    )
                )
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val safeValues = ContentValues(values ?: ContentValues())
            val id = nextId.incrementAndGet()
            rowsById[id] = SmsRow(
                id = id,
                address = safeValues.getAsString(Telephony.Sms.ADDRESS).orEmpty(),
                body = safeValues.getAsString(Telephony.Sms.BODY).orEmpty(),
                dateMillis = safeValues.getAsLong(Telephony.Sms.DATE) ?: 0L,
                type = safeValues.getAsInteger(Telephony.Sms.TYPE)
                    ?: Telephony.Sms.MESSAGE_TYPE_INBOX,
                read = safeValues.getAsInteger(Telephony.Sms.READ) ?: 0
            )
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            val safeValues = ContentValues(values ?: ContentValues())
            recordedUpdates.add(safeValues)
            val newRead = safeValues.getAsInteger(Telephony.Sms.READ) ?: return 0
            var updatedCount = 0
            if (selection != null && selection.contains(Telephony.Sms._ID) && selectionArgs != null) {
                for (arg in selectionArgs) {
                    val id = arg.toLongOrNull() ?: continue
                    val row = rowsById[id] ?: continue
                    row.read = newRead
                    updatedCount++
                }
            } else if (selection == null) {
                for (row in rowsById.values) {
                    row.read = newRead
                    updatedCount++
                }
            }
            return updatedCount
        }

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            if (selection == null) {
                val size = rowsById.size
                rowsById.clear()
                return size
            }
            var deletedCount = 0
            if (selection.contains(Telephony.Sms._ID) && selectionArgs != null) {
                for (arg in selectionArgs) {
                    val id = arg.toLongOrNull() ?: continue
                    if (rowsById.remove(id) != null) {
                        deletedCount++
                    }
                }
            }
            return deletedCount
        }
    }
}
