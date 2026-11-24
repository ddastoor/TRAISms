package com.example

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
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
import com.example.data.crypto.EncryptedSettingsStore
import com.example.data.crypto.LeftSwipeAction
import com.example.data.crypto.TinkAppSettingsSerializer
import com.example.data.db.CategoryUnreadCount
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.db.ThreadSummary
import com.example.data.sms.SyncProgressState
import com.example.data.sms.SystemSmsHelper
import com.example.ui.MainViewModel
import com.example.ui.messages.MessagesScreen
import com.example.ui.theme.TraiSmsTheme
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class CategoryUnreadFilterRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var app: TraiSmsApp
    private lateinit var systemSmsHelper: SystemSmsHelper
    private val tempStorageDirs = mutableListOf<File>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        app = context as TraiSmsApp
        systemSmsHelper = SystemSmsHelper(context)
        app.container.database.smsDao().deleteAllMessages()
        app.container.settingsStore.updateSettings {
            AppSettings(
                onboardingDismissed = true,
                categoryShowUnreadOnly = emptyMap()
            )
        }
    }

    @After
    fun tearDown() = runBlocking {
        app.container.database.smsDao().deleteAllMessages()
        app.container.settingsStore.updateSettings {
            it.copy(categoryShowUnreadOnly = emptyMap())
        }
        tempStorageDirs.forEach { dir -> dir.deleteRecursively() }
        tempStorageDirs.clear()
    }

    @Test
    fun `AppSettings defaults isCategoryUnreadOnly to true across all 8 tabs and EncryptedSettingsStore persists per-tab state across cold reload`() = runBlocking {
        val defaultSettings = AppSettings()
        assertTrue(defaultSettings.categoryShowUnreadOnly.isEmpty())
        assertEquals(8, SmsCategory.orderedTabs.size)

        SmsCategory.orderedTabs.forEach { category ->
            assertTrue(
                "Default Show Unread state for $category must be true",
                defaultSettings.isCategoryUnreadOnly(category)
            )
        }

        val writerDir = createIsolatedDir("unread_filter_writer")
        val writerStore = EncryptedSettingsStore(
            context = buildIsolatedStoreContext(context, writerDir),
            tinkKeyManager = app.container.tinkKeyManager
        )

        writerStore.updateSettings { current ->
            current.copy(
                categoryShowUnreadOnly = current.categoryShowUnreadOnly +
                    (SmsCategory.PERSONAL.name to false) +
                    (SmsCategory.OTP.name to false)
            )
        }

        val writtenSnapshot = writerStore.getSettingsSnapshot()
        assertFalse(writtenSnapshot.isCategoryUnreadOnly(SmsCategory.PERSONAL))
        assertFalse(writtenSnapshot.isCategoryUnreadOnly(SmsCategory.OTP))

        val persistedFile = File(writerDir, "traisms_secure_settings.pb")
        assertTrue(
            "Expected Tink-encrypted DataStore file on disk",
            persistedFile.exists() && persistedFile.length() > 0L
        )
        assertFalse(
            "Ciphertext on disk must not expose plaintext field names",
            persistedFile.readBytes().decodeToString().contains("categoryShowUnreadOnly")
        )

        // Cold-reload from copied encrypted file into a fresh EncryptedSettingsStore
        val readerDir = createIsolatedDir("unread_filter_reader")
        persistedFile.copyTo(File(readerDir, "traisms_secure_settings.pb"), overwrite = true)

        val coldRestoredStore = EncryptedSettingsStore(
            context = buildIsolatedStoreContext(context, readerDir),
            tinkKeyManager = app.container.tinkKeyManager
        )
        val restoredSnapshot = coldRestoredStore.getSettingsSnapshot()

        assertFalse(restoredSnapshot.isCategoryUnreadOnly(SmsCategory.PERSONAL))
        assertFalse(restoredSnapshot.isCategoryUnreadOnly(SmsCategory.OTP))

        val untouchedCategories = listOf(
            SmsCategory.TRANSACTIONAL,
            SmsCategory.SERVICE,
            SmsCategory.PROMOTIONAL,
            SmsCategory.GOVERNMENT,
            SmsCategory.UNKNOWN,
            SmsCategory.OTHER
        )
        assertEquals(6, untouchedCategories.size)
        untouchedCategories.forEach { category ->
            assertTrue(
                "Untouched category $category must remain true after cold reload",
                restoredSnapshot.isCategoryUnreadOnly(category)
            )
        }

        // Toggling PERSONAL back to true leaves OTP false and all other 6 categories true
        coldRestoredStore.updateSettings { current ->
            current.copy(
                categoryShowUnreadOnly = current.categoryShowUnreadOnly +
                    (SmsCategory.PERSONAL.name to true)
            )
        }
        val reToggledSnapshot = coldRestoredStore.getSettingsSnapshot()
        assertTrue(reToggledSnapshot.isCategoryUnreadOnly(SmsCategory.PERSONAL))
        assertFalse(reToggledSnapshot.isCategoryUnreadOnly(SmsCategory.OTP))
        untouchedCategories.forEach { category ->
            assertTrue(reToggledSnapshot.isCategoryUnreadOnly(category))
        }

        // Legacy Tink-encrypted JSON payloads omitting categoryShowUnreadOnly default all 8 tabs to true
        val legacyJson = """{"promoAutoDeleteDays":7,"otpAutoDeleteDays":3,"onboardingDismissed":true}""".encodeToByteArray()
        val legacyCiphertext = app.container.tinkKeyManager.getAead().encrypt(
            legacyJson,
            "traisms_encrypted_datastore_v1".toByteArray(Charsets.UTF_8)
        )
        val serializer = TinkAppSettingsSerializer(app.container.tinkKeyManager)
        val migratedSettings = serializer.readFrom(ByteArrayInputStream(legacyCiphertext))

        assertTrue(migratedSettings.categoryShowUnreadOnly.isEmpty())
        SmsCategory.orderedTabs.forEach { category ->
            assertTrue(
                "Legacy payload omitting categoryShowUnreadOnly must default $category to true",
                migratedSettings.isCategoryUnreadOnly(category)
            )
        }
    }

    @Test
    fun `SmsRepository observeCategoryThreads filters strictly unread incoming messages when unreadOnly is true and updates reactively on markThreadAsRead`() = runBlocking {
        val smsDao = app.container.database.smsDao()
        val repository = app.container.smsRepository
        val baseTime = 1_700_000_000_000L

        // PERSONAL: 1 unread incoming thread (+919811112222 with 1 older read + 1 newer unread incoming)
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 3001L,
                threadKey = "+919811112222",
                body = "Earlier read message from Alice",
                timestampMillis = baseTime + 1_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = true
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 3002L,
                threadKey = "+919811112222",
                body = "Are we still on for dinner at 8 PM?",
                timestampMillis = baseTime + 5_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = false
            )
        )

        // PERSONAL: 2 read threads (+919833334444, +919855556666)
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 3003L,
                threadKey = "+919833334444",
                body = "Reached home safely.",
                timestampMillis = baseTime + 4_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = true
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 3004L,
                threadKey = "+919855556666",
                body = "Shared the meeting notes.",
                timestampMillis = baseTime + 3_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = true
            )
        )

        // PERSONAL: 1 archived unread thread (must never leak into active unread or active all)
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 3005L,
                threadKey = "+919877778888",
                body = "Archived unread message",
                timestampMillis = baseTime + 6_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = false,
                isArchived = true
            )
        )

        // TRANSACTIONAL: 1 unread incoming thread (VM-HDFCBK-T) and 1 read thread (AD-ICICIB-T)
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 3006L,
                threadKey = "VM-HDFCBK-T",
                body = "Acct XX9012 credited with INR 25,000.00 via NEFT.",
                timestampMillis = baseTime + 5_500L,
                category = SmsCategory.TRANSACTIONAL,
                isIncoming = true,
                isRead = false
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 3007L,
                threadKey = "AD-ICICIB-T",
                body = "Acct XX1234 debited for INR 500.00 via UPI.",
                timestampMillis = baseTime + 2_000L,
                category = SmsCategory.TRANSACTIONAL,
                isIncoming = true,
                isRead = true
            )
        )

        // SERVICE: outgoing message with isRead = false must NOT count as unread incoming
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 3008L,
                threadKey = "AX-AIRTEL-S",
                body = "Outgoing service query",
                timestampMillis = baseTime + 2_500L,
                category = SmsCategory.SERVICE,
                isIncoming = false,
                isRead = false
            )
        )

        val livePersonalUnreadEmissions = CopyOnWriteArrayList<List<String>>()
        val collectorJob = launch(Dispatchers.IO) {
            repository.observeCategoryThreads(SmsCategory.PERSONAL, unreadOnly = true)
                .collect { summaries ->
                    livePersonalUnreadEmissions.add(summaries.map { it.threadKey })
                }
        }

        try {
            waitForCondition {
                livePersonalUnreadEmissions.lastOrNull() == listOf("+919811112222")
            }

            // 1. unreadOnly = true on PERSONAL emits strictly ["+919811112222"] with accurate counts
            val personalUnreadInitial = repository.observeCategoryThreads(
                category = SmsCategory.PERSONAL,
                unreadOnly = true
            ).first()
            assertEquals(listOf("+919811112222"), personalUnreadInitial.map { it.threadKey })
            assertEquals(1, personalUnreadInitial.single().unreadCount)
            assertEquals(2, personalUnreadInitial.single().messageCount)
            assertEquals("Are we still on for dinner at 8 PM?", personalUnreadInitial.single().latestBody)

            // 2. unreadOnly = false (and default single-arg call) on PERSONAL emits all 3 active threads
            val personalAllInitial = repository.observeCategoryThreads(
                category = SmsCategory.PERSONAL,
                unreadOnly = false
            ).first()
            assertEquals(
                listOf("+919811112222", "+919833334444", "+919855556666"),
                personalAllInitial.map { it.threadKey }
            )
            assertEquals(
                listOf("+919811112222", "+919833334444", "+919855556666"),
                repository.observeCategoryThreads(SmsCategory.PERSONAL).first().map { it.threadKey }
            )

            // 3. TRANSACTIONAL unreadOnly = true vs false, and SERVICE outgoing-only check
            assertEquals(
                listOf("VM-HDFCBK-T"),
                repository.observeCategoryThreads(SmsCategory.TRANSACTIONAL, unreadOnly = true)
                    .first()
                    .map { it.threadKey }
            )
            assertEquals(
                listOf("VM-HDFCBK-T", "AD-ICICIB-T"),
                repository.observeCategoryThreads(SmsCategory.TRANSACTIONAL, unreadOnly = false)
                    .first()
                    .map { it.threadKey }
            )
            assertTrue(
                repository.observeCategoryThreads(SmsCategory.SERVICE, unreadOnly = true)
                    .first()
                    .isEmpty()
            )

            // 4. markThreadAsRead("+919811112222") reactively clears PERSONAL unread while preserving PERSONAL all and TRANSACTIONAL unread
            repository.markThreadAsRead("+919811112222")

            waitForCondition {
                livePersonalUnreadEmissions.lastOrNull() == emptyList<String>()
            }

            assertTrue(
                repository.observeCategoryThreads(SmsCategory.PERSONAL, unreadOnly = true)
                    .first()
                    .isEmpty()
            )

            val personalAllAfterRead = repository.observeCategoryThreads(
                category = SmsCategory.PERSONAL,
                unreadOnly = false
            ).first()
            assertEquals(
                listOf("+919811112222", "+919833334444", "+919855556666"),
                personalAllAfterRead.map { it.threadKey }
            )
            assertEquals(0, personalAllAfterRead.first { it.threadKey == "+919811112222" }.unreadCount)

            val transactionalUnreadAfterPersonalRead = repository.observeCategoryThreads(
                category = SmsCategory.TRANSACTIONAL,
                unreadOnly = true
            ).first()
            assertEquals(
                listOf("VM-HDFCBK-T"),
                transactionalUnreadAfterPersonalRead.map { it.threadKey }
            )
            assertEquals(1, transactionalUnreadAfterPersonalRead.single().unreadCount)
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun `MessagesScreen isolates per-category Show Unread toggle across tabs and hides filter bar during active search`() {
        val personalUnread = buildThreadSummary(
            id = 101L,
            threadKey = "+919811112222",
            contactName = "Alice Sharma",
            body = "Dinner at 8 PM tonight?",
            timestampMillis = 1_700_000_050_000L,
            category = SmsCategory.PERSONAL,
            unreadCount = 1,
            messageCount = 2
        )
        val personalRead1 = buildThreadSummary(
            id = 102L,
            threadKey = "+919833334444",
            contactName = "Bob Patel",
            body = "Reached home safely.",
            timestampMillis = 1_700_000_040_000L,
            category = SmsCategory.PERSONAL,
            unreadCount = 0,
            messageCount = 1
        )
        val personalRead2 = buildThreadSummary(
            id = 103L,
            threadKey = "+919855556666",
            contactName = "Charlie Singh",
            body = "Sent the PDF notes.",
            timestampMillis = 1_700_000_030_000L,
            category = SmsCategory.PERSONAL,
            unreadCount = 0,
            messageCount = 1
        )
        val transactionalUnread = buildThreadSummary(
            id = 201L,
            threadKey = "VM-HDFCBK-T",
            contactName = null,
            body = "Acct XX9012 credited with INR 25,000.00",
            timestampMillis = 1_700_000_060_000L,
            category = SmsCategory.TRANSACTIONAL,
            unreadCount = 1,
            messageCount = 1
        )
        val transactionalRead = buildThreadSummary(
            id = 202L,
            threadKey = "AD-ICICIB-T",
            contactName = null,
            body = "Acct XX1234 debited for INR 500.00",
            timestampMillis = 1_700_000_020_000L,
            category = SmsCategory.TRANSACTIONAL,
            unreadCount = 0,
            messageCount = 1
        )

        val threadsByCategory = mapOf(
            SmsCategory.PERSONAL to listOf(personalUnread, personalRead1, personalRead2),
            SmsCategory.TRANSACTIONAL to listOf(transactionalUnread, transactionalRead)
        )
        val countsByCategory = mapOf(
            SmsCategory.PERSONAL to CategoryUnreadCount(
                category = SmsCategory.PERSONAL,
                unreadCount = 1,
                totalCount = 4
            ),
            SmsCategory.TRANSACTIONAL to CategoryUnreadCount(
                category = SmsCategory.TRANSACTIONAL,
                unreadCount = 1,
                totalCount = 2
            )
        )

        var selectedCategory by mutableStateOf(SmsCategory.PERSONAL)
        var searchQuery by mutableStateOf("")
        var uiSettings by mutableStateOf(AppSettings())

        composeTestRule.setContent {
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
                        totalMessageCount = 6,
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
                        }
                    )
                }
            }
        }

        // 1. On PERSONAL, switch starts ON and displays strictly the unread PERSONAL thread
        composeTestRule.onNodeWithTag("category_unread_filter_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("category_unread_only_switch")
            .assertIsDisplayed()
            .assertIsOn()
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("Unread only (1)")

        composeTestRule.onNodeWithTag("thread_row_+919811112222").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("thread_row_+919833334444").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("thread_row_+919855556666").assertCountEquals(0)

        // 2. Clicking category_unread_only_switch turns PERSONAL OFF and reveals read PERSONAL threads too
        composeTestRule.onNodeWithTag("category_unread_only_switch").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("category_unread_only_switch").assertIsOff()
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("All (4)")

        composeTestRule.onNodeWithTag("thread_row_+919811112222").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thread_row_+919833334444").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thread_row_+919855556666").assertIsDisplayed()

        // 3. Clicking tab_transactional switches to TRANSACTIONAL where switch is STILL ON and shows only unread
        composeTestRule.onNodeWithTag("tab_transactional")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(SmsCategory.TRANSACTIONAL, selectedCategory)
        composeTestRule.onNodeWithTag("category_unread_only_switch")
            .assertIsDisplayed()
            .assertIsOn()
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("Unread only (1)")

        composeTestRule.onNodeWithTag("thread_row_VM-HDFCBK-T").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("thread_row_AD-ICICIB-T").assertCountEquals(0)

        // 4. Switching back to tab_personal preserves OFF and displays all PERSONAL threads
        composeTestRule.onNodeWithTag("tab_personal")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(SmsCategory.PERSONAL, selectedCategory)
        composeTestRule.onNodeWithTag("category_unread_only_switch")
            .assertIsDisplayed()
            .assertIsOff()
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("All (4)")

        composeTestRule.onNodeWithTag("thread_row_+919811112222").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thread_row_+919833334444").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thread_row_+919855556666").assertIsDisplayed()

        // 5. Active search hides category_unread_filter_bar and clearing search restores it
        composeTestRule.runOnIdle { searchQuery = "ICICIB" }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("category_unread_filter_bar").assertCountEquals(0)
        composeTestRule.onNodeWithTag("thread_row_AD-ICICIB-T").assertIsDisplayed()

        composeTestRule.runOnIdle { searchQuery = "" }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("category_unread_filter_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("category_unread_only_switch").assertIsOff()
    }

    @Test
    fun `MessagesScreen displays empty_unread_show_all_button when category has zero unread and positive read messages and clicking it reveals all messages`() {
        val personalRead1 = buildThreadSummary(
            id = 301L,
            threadKey = "+919833334444",
            contactName = "Bob Patel",
            body = "Reached home safely.",
            timestampMillis = 1_700_000_040_000L,
            category = SmsCategory.PERSONAL,
            unreadCount = 0,
            messageCount = 1
        )
        val personalRead2 = buildThreadSummary(
            id = 302L,
            threadKey = "+919855556666",
            contactName = "Charlie Singh",
            body = "Sent the PDF notes.",
            timestampMillis = 1_700_000_030_000L,
            category = SmsCategory.PERSONAL,
            unreadCount = 0,
            messageCount = 1
        )

        val threadsByCategory = mapOf(
            SmsCategory.PERSONAL to listOf(personalRead1, personalRead2),
            SmsCategory.GOVERNMENT to emptyList()
        )
        val countsByCategory = mapOf(
            SmsCategory.PERSONAL to CategoryUnreadCount(
                category = SmsCategory.PERSONAL,
                unreadCount = 0,
                totalCount = 2
            ),
            SmsCategory.GOVERNMENT to CategoryUnreadCount(
                category = SmsCategory.GOVERNMENT,
                unreadCount = 0,
                totalCount = 0
            )
        )

        var selectedCategory by mutableStateOf(SmsCategory.PERSONAL)
        var uiSettings by mutableStateOf(AppSettings())

        composeTestRule.setContent {
            val showUnreadOnly = uiSettings.isCategoryUnreadOnly(selectedCategory)
            val allInTab = threadsByCategory[selectedCategory].orEmpty()
            val displayedThreads = if (showUnreadOnly) {
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
                        totalMessageCount = 2,
                        searchQuery = "",
                        leftSwipeAction = LeftSwipeAction.ARCHIVE,
                        activeRegionBadge = "IN • India",
                        syncProgress = SyncProgressState(),
                        isDefaultSmsApp = true,
                        showSetupBanner = false,
                        showEmulatorDemoBanner = false,
                        systemSmsHelper = systemSmsHelper,
                        onSelectCategory = { selectedCategory = it },
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
                        showUnreadOnly = showUnreadOnly,
                        onToggleCategoryUnreadOnly = { category, unreadOnly ->
                            uiSettings = uiSettings.copy(
                                categoryShowUnreadOnly = uiSettings.categoryShowUnreadOnly +
                                    (category.name to unreadOnly)
                            )
                        }
                    )
                }
            }
        }

        // 1. On PERSONAL (0 unread, 2 read, showUnreadOnly = true): filter bar + empty_unread_show_all_button shown
        composeTestRule.onNodeWithTag("category_unread_filter_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("category_unread_only_switch")
            .assertIsDisplayed()
            .assertIsOn()
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("Unread only (0)")

        composeTestRule.onNodeWithText("No unread Personal messages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Turn off Show unread above to view all 2 message(s) in Personal.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("empty_unread_show_all_button")
            .assertIsDisplayed()
            .assertTextContains("Show all Personal messages")

        // 2. When a category has 0 unread AND 0 total (GOVERNMENT), empty_unread_show_all_button is NOT rendered
        composeTestRule.runOnIdle { selectedCategory = SmsCategory.GOVERNMENT }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("category_unread_filter_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("category_unread_only_switch").assertIsOn()
        composeTestRule.onNodeWithText("No unread Government messages").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("empty_unread_show_all_button").assertCountEquals(0)

        // 3. Back on PERSONAL, clicking empty_unread_show_all_button switches PERSONAL to showUnreadOnly = false and displays both read threads
        composeTestRule.runOnIdle { selectedCategory = SmsCategory.PERSONAL }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("empty_unread_show_all_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertFalse(uiSettings.isCategoryUnreadOnly(SmsCategory.PERSONAL))
        assertTrue(uiSettings.isCategoryUnreadOnly(SmsCategory.GOVERNMENT))

        composeTestRule.onNodeWithTag("category_unread_only_switch").assertIsOff()
        composeTestRule.onNodeWithTag("category_unread_filter_summary", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("All (2)")
        composeTestRule.onAllNodesWithTag("empty_unread_show_all_button").assertCountEquals(0)

        composeTestRule.onNodeWithTag("thread_row_+919833334444").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thread_row_+919855556666").assertIsDisplayed()
    }

    @Test
    fun `MainViewModel exposes per-category currentCategoryUnreadOnly and updates activeThreads reactively`() = runBlocking {
        val smsDao = app.container.database.smsDao()
        val baseTime = 1_700_000_000_000L

        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 4001L,
                threadKey = "+919811112222",
                body = "Unread personal message",
                timestampMillis = baseTime + 3_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = false
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 4002L,
                threadKey = "+919833334444",
                body = "Read personal message",
                timestampMillis = baseTime + 2_000L,
                category = SmsCategory.PERSONAL,
                isIncoming = true,
                isRead = true
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 4003L,
                threadKey = "VM-HDFCBK-T",
                body = "Unread HDFC credit alert",
                timestampMillis = baseTime + 4_000L,
                category = SmsCategory.TRANSACTIONAL,
                isIncoming = true,
                isRead = false
            )
        )
        smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 4004L,
                threadKey = "AD-ICICIB-T",
                body = "Read ICICI debit alert",
                timestampMillis = baseTime + 1_000L,
                category = SmsCategory.TRANSACTIONAL,
                isIncoming = true,
                isRead = true
            )
        )

        val viewModel = MainViewModel(app.container)
        val unreadSub = launch(Dispatchers.Unconfined) {
            viewModel.currentCategoryUnreadOnly.collect {}
        }
        val threadsSub = launch(Dispatchers.Unconfined) {
            viewModel.activeThreads.collect {}
        }

        try {
            // Default PERSONAL starts with currentCategoryUnreadOnly == true -> only unread thread
            waitForCondition {
                viewModel.currentCategoryUnreadOnly.value &&
                    viewModel.activeThreads.value.map { it.threadKey } == listOf("+919811112222")
            }

            // Toggle PERSONAL unreadOnly -> false -> both PERSONAL threads appear
            viewModel.updateCategoryUnreadOnly(SmsCategory.PERSONAL, false)
            waitForCondition {
                !viewModel.currentCategoryUnreadOnly.value &&
                    viewModel.activeThreads.value.map { it.threadKey } == listOf("+919811112222", "+919833334444")
            }

            // Switch to TRANSACTIONAL -> currentCategoryUnreadOnly is still true -> only VM-HDFCBK-T appears
            viewModel.selectCategory(SmsCategory.TRANSACTIONAL)
            waitForCondition {
                viewModel.currentCategoryUnreadOnly.value &&
                    viewModel.activeThreads.value.map { it.threadKey } == listOf("VM-HDFCBK-T")
            }

            // Active search finds read threads even when current tab has Show Unread ON
            viewModel.updateSearchQuery("ICICIB")
            waitForCondition {
                viewModel.activeThreads.value.map { it.threadKey } == listOf("AD-ICICIB-T")
            }

            // Clear search and return to PERSONAL -> PERSONAL still remembers false
            viewModel.updateSearchQuery("")
            viewModel.selectCategory(SmsCategory.PERSONAL)
            waitForCondition {
                !viewModel.currentCategoryUnreadOnly.value &&
                    viewModel.activeThreads.value.map { it.threadKey } == listOf("+919811112222", "+919833334444")
            }
        } finally {
            unreadSub.cancel()
            threadsSub.cancel()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
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
}
