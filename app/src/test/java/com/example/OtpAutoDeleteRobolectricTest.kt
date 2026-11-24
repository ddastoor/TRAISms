package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.crypto.EncryptedSettingsStore
import com.example.data.crypto.LeftSwipeAction
import com.example.data.crypto.TinkAppSettingsSerializer
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.sms.SyncProgressState
import com.example.data.sms.SystemSmsHelper
import com.example.ui.messages.MessagesScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.TraiSmsTheme
import com.example.worker.OtpCleanupWorker
import java.io.ByteArrayInputStream
import java.io.File
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class OtpAutoDeleteRobolectricTest {

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
                promoAutoDeleteDays = 7,
                otpAutoDeleteDays = 0,
                onboardingDismissed = true
            )
        }
    }

    @After
    fun tearDown() = runBlocking {
        app.container.database.smsDao().deleteAllMessages()
        app.container.settingsStore.updateSettings {
            it.copy(
                promoAutoDeleteDays = 7,
                otpAutoDeleteDays = 0
            )
        }
        tempStorageDirs.forEach { dir -> dir.deleteRecursively() }
        tempStorageDirs.clear()
    }

    @Test
    fun `purgeExpiredOtpMessages deletes strictly expired unarchived OTP messages while keeping recent OTP archived OTP and all 7 other categories`() = runBlocking {
        val smsDao = app.container.database.smsDao()
        val repository = app.container.smsRepository

        val now = System.currentTimeMillis()
        val tenDaysAgo = now - (10L * DAY_MILLIS)
        val oneHourAgo = now - HOUR_MILLIS

        // 2 expired unarchived OTP messages (10 days old)
        val expiredOtpId1 = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 1001L,
                threadKey = "VM-HDFCBK-T",
                body = "482910 is your OTP for netbanking login.",
                timestampMillis = tenDaysAgo,
                category = SmsCategory.OTP,
                isArchived = false,
                extractedOtp = "482910"
            )
        )
        val expiredOtpId2 = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 1002L,
                threadKey = "AD-ICICIB-T",
                body = "739104 is your One Time Password for card txn.",
                timestampMillis = tenDaysAgo - 60_000L,
                category = SmsCategory.OTP,
                isArchived = false,
                extractedOtp = "739104"
            )
        )

        // 1 recent unarchived OTP message (1 hour old)
        val recentOtpId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 1003L,
                threadKey = "VM-SBIBNK-T",
                body = "654321 is your SBI login verification code.",
                timestampMillis = oneHourAgo,
                category = SmsCategory.OTP,
                isArchived = false,
                extractedOtp = "654321"
            )
        )

        // 1 expired ARCHIVED OTP message (10 days old, isArchived = true)
        val archivedExpiredOtpId = smsDao.insertMessage(
            buildSeededMessage(
                systemSmsId = 1004L,
                threadKey = "AX-AXISBK-T",
                body = "998877 is your archived OTP code.",
                timestampMillis = tenDaysAgo,
                category = SmsCategory.OTP,
                isArchived = true,
                extractedOtp = "998877"
            )
        )

        // Expired unarchived messages across all 7 non-OTP categories (all 10 days old)
        val nonOtpCategories = listOf(
            SmsCategory.PERSONAL,
            SmsCategory.TRANSACTIONAL,
            SmsCategory.SERVICE,
            SmsCategory.PROMOTIONAL,
            SmsCategory.GOVERNMENT,
            SmsCategory.UNKNOWN,
            SmsCategory.OTHER
        )
        assertEquals(7, nonOtpCategories.size)
        assertEquals(SmsCategory.entries.filter { it != SmsCategory.OTP }.toSet(), nonOtpCategories.toSet())

        val nonOtpSeededIds = nonOtpCategories.mapIndexed { index, category ->
            category to smsDao.insertMessage(
                buildSeededMessage(
                    systemSmsId = 2001L + index,
                    threadKey = "SENDER-${category.name}",
                    body = "Expired 10-day message in ${category.title}",
                    timestampMillis = tenDaysAgo,
                    category = category,
                    isArchived = false
                )
            )
        }.toMap()

        assertEquals(11, smsDao.getAllMessagesSnapshot().size)

        // 1. Disabled / non-positive retention (0 and negative) must delete 0 messages
        assertEquals(0, repository.purgeExpiredOtpMessages(0))
        assertEquals(0, repository.purgeExpiredOtpMessages(-1))
        assertEquals(11, smsDao.getAllMessagesSnapshot().size)

        // 2. Retention window wider than 10 days (15 days) must also delete 0 messages
        assertEquals(0, repository.purgeExpiredOtpMessages(15))
        assertEquals(11, smsDao.getAllMessagesSnapshot().size)

        // 3. DAO system-ID lookup for 7-day cutoff returns strictly the 2 expired unarchived OTP system IDs
        val sevenDayCutoff = System.currentTimeMillis() - (7L * DAY_MILLIS)
        assertEquals(
            setOf(1001L, 1002L),
            smsDao.getExpiredOtpSystemIds(sevenDayCutoff).toSet()
        )

        // 4. purgeExpiredOtpMessages(7) deletes strictly the 2 expired unarchived OTP messages
        val purgedCount = repository.purgeExpiredOtpMessages(7)
        assertEquals(2, purgedCount)

        val remainingMessages = smsDao.getAllMessagesSnapshot()
        assertEquals(9, remainingMessages.size)

        val remainingById = remainingMessages.associateBy { it.id }
        assertFalse("Expired unarchived OTP #1 must be deleted", expiredOtpId1 in remainingById)
        assertFalse("Expired unarchived OTP #2 must be deleted", expiredOtpId2 in remainingById)

        // Recent unarchived OTP is preserved
        val keptRecentOtp = remainingById[recentOtpId]
        assertTrue("Recent unarchived OTP must remain intact", keptRecentOtp != null)
        assertEquals(SmsCategory.OTP, keptRecentOtp!!.category)
        assertFalse(keptRecentOtp.isArchived)
        assertEquals("654321", keptRecentOtp.extractedOtp)

        // Expired ARCHIVED OTP is preserved
        val keptArchivedOtp = remainingById[archivedExpiredOtpId]
        assertTrue("Archived OTP must never be auto-deleted", keptArchivedOtp != null)
        assertEquals(SmsCategory.OTP, keptArchivedOtp!!.category)
        assertTrue(keptArchivedOtp.isArchived)
        assertEquals("998877", keptArchivedOtp.extractedOtp)

        // Every single message across all 7 non-OTP categories is 100% intact
        nonOtpSeededIds.forEach { (category, seededId) ->
            val keptNonOtp = remainingById[seededId]
            assertTrue("Expired message in $category must never be touched by OTP cleanup", keptNonOtp != null)
            assertEquals(category, keptNonOtp!!.category)
            assertFalse(keptNonOtp.isArchived)
        }

        // Verify WorkScheduler schedule sync & OtpCleanupWorker constant execute cleanly offline
        assertEquals("traisms_otp_auto_cleanup_work", OtpCleanupWorker.UNIQUE_WORK_NAME)
        app.container.workScheduler.syncOtpCleanupSchedule(7)
        app.container.workScheduler.syncOtpCleanupSchedule(0)
    }

    @Test
    fun `MessagesScreen displays in-tab auto-delete bar only on OTP and Promotional tabs and updates presets accurately`() {
        var selectedCategory by mutableStateOf(SmsCategory.PERSONAL)
        var searchQuery by mutableStateOf("")
        var otpAutoDeleteDays by mutableStateOf(0)
        var promoAutoDeleteDays by mutableStateOf(7)

        val recordedOtpUpdates = mutableListOf<Int>()
        val recordedPromoUpdates = mutableListOf<Int>()

        composeTestRule.setContent {
            TraiSmsTheme {
                Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                    MessagesScreen(
                        selectedCategory = selectedCategory,
                        threads = emptyList(),
                        categoryCounts = emptyMap(),
                        totalMessageCount = 0,
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
                        promoAutoDeleteDays = promoAutoDeleteDays,
                        otpAutoDeleteDays = otpAutoDeleteDays,
                        onUpdatePromoAutoDelete = { days ->
                            promoAutoDeleteDays = days
                            recordedPromoUpdates.add(days)
                        },
                        onUpdateOtpAutoDelete = { days ->
                            otpAutoDeleteDays = days
                            recordedOtpUpdates.add(days)
                        }
                    )
                }
            }
        }

        // 1. Hidden on SmsCategory.PERSONAL and all other non-OTP / non-Promotional tabs
        val nonAutoDeleteTabs = listOf(
            SmsCategory.PERSONAL,
            SmsCategory.TRANSACTIONAL,
            SmsCategory.SERVICE,
            SmsCategory.GOVERNMENT,
            SmsCategory.UNKNOWN,
            SmsCategory.OTHER
        )
        nonAutoDeleteTabs.forEach { tab ->
            composeTestRule.runOnIdle { selectedCategory = tab }
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodesWithTag("inbox_auto_delete_bar").assertCountEquals(0)
        }

        // 2. Visible on SmsCategory.OTP with inbox_otp_preset_0 .. inbox_otp_preset_30
        composeTestRule.runOnIdle { selectedCategory = SmsCategory.OTP }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("inbox_auto_delete_bar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto-delete OTP:").performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("inbox_promo_preset_0").assertCountEquals(0)

        PRESET_DAYS.forEach { days ->
            val node = composeTestRule.onNodeWithTag("inbox_otp_preset_$days")
            node.performScrollTo().assertIsDisplayed()
            if (days == 0) {
                node.assertIsSelected()
            } else {
                node.assertIsNotSelected()
            }
        }

        // Click 1 -> 7 -> 30 -> 0 in horizontal scroll bar
        listOf(1, 7, 30, 0).forEach { targetDays ->
            composeTestRule.onNodeWithTag("inbox_otp_preset_$targetDays")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            composeTestRule.mainClock.advanceTimeBy(5_000)
            composeTestRule.waitForIdle()

            assertEquals(targetDays, otpAutoDeleteDays)
            PRESET_DAYS.forEach { days ->
                val chip = composeTestRule.onNodeWithTag("inbox_otp_preset_$days")
                if (days == targetDays) {
                    chip.assertIsSelected()
                } else {
                    chip.assertIsNotSelected()
                }
            }
        }
        assertEquals(listOf(1, 7, 30, 0), recordedOtpUpdates)
        assertTrue(recordedPromoUpdates.isEmpty())

        // Non-blank search query hides inbox_auto_delete_bar even on OTP tab
        composeTestRule.runOnIdle { searchQuery = "482910" }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("inbox_auto_delete_bar").assertCountEquals(0)

        composeTestRule.runOnIdle { searchQuery = "" }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("inbox_auto_delete_bar").assertIsDisplayed()

        // 3. Visible on SmsCategory.PROMOTIONAL with inbox_promo_preset_0 .. inbox_promo_preset_30
        composeTestRule.runOnIdle { selectedCategory = SmsCategory.PROMOTIONAL }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("inbox_auto_delete_bar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto-delete Promo:").performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("inbox_otp_preset_0").assertCountEquals(0)

        PRESET_DAYS.forEach { days ->
            val node = composeTestRule.onNodeWithTag("inbox_promo_preset_$days")
            node.performScrollTo().assertIsDisplayed()
            if (days == 7) {
                node.assertIsSelected()
            } else {
                node.assertIsNotSelected()
            }
        }

        listOf(15, 30, 0, 3).forEach { targetDays ->
            composeTestRule.onNodeWithTag("inbox_promo_preset_$targetDays")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            composeTestRule.mainClock.advanceTimeBy(5_000)
            composeTestRule.waitForIdle()

            assertEquals(targetDays, promoAutoDeleteDays)
            PRESET_DAYS.forEach { days ->
                val chip = composeTestRule.onNodeWithTag("inbox_promo_preset_$days")
                if (days == targetDays) {
                    chip.assertIsSelected()
                } else {
                    chip.assertIsNotSelected()
                }
            }
        }
        assertEquals(listOf(15, 30, 0, 3), recordedPromoUpdates)
        assertEquals(listOf(1, 7, 30, 0), recordedOtpUpdates)
    }

    @Test
    fun `SettingsScreen OTP Auto-Delete card selects presets and persists otpAutoDeleteDays to EncryptedSettingsStore`() = runBlocking {
        val initialSnapshot = app.container.settingsStore.getSettingsSnapshot()
        assertEquals(0, initialSnapshot.otpAutoDeleteDays)
        assertEquals(7, initialSnapshot.promoAutoDeleteDays)

        var uiSettings by mutableStateOf(initialSnapshot)
        val recordedOtpSelections = mutableListOf<Int>()

        composeTestRule.setContent {
            TraiSmsTheme {
                Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                    SettingsScreen(
                        settings = uiSettings,
                        isDefaultSmsApp = true,
                        detectedRegionLabel = "IN • India",
                        totalMessageCount = 12,
                        systemSmsHelper = systemSmsHelper,
                        onRefreshRoleStatus = {},
                        onUpdatePromoAutoDelete = { newPromoDays ->
                            uiSettings = uiSettings.copy(promoAutoDeleteDays = newPromoDays)
                        },
                        onUpdateLeftSwipeAction = {},
                        onUpdateUnarchiveOnNewSms = {},
                        onUpdateRegionOverride = {},
                        onUpdateBackupSchedule = { _, _ -> },
                        onNavigateToBackupTab = {},
                        onToggleAppLock = {},
                        onToggleBlockScreenshots = {},
                        onRerunCategorization = {},
                        onDeleteAllMessagesInApp = {},
                        onUpdateSmsFontSize = {},
                        onUpdateSmsFontBold = {},
                        onUpdateOtpAutoDelete = { newOtpDays ->
                            recordedOtpSelections.add(newOtpDays)
                            uiSettings = uiSettings.copy(otpAutoDeleteDays = newOtpDays)
                            runBlocking {
                                app.container.settingsStore.updateSettings {
                                    it.copy(otpAutoDeleteDays = newOtpDays)
                                }
                            }
                        }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("OTP Auto-Delete")
            .performScrollTo()
            .assertIsDisplayed()

        // Verify initial state: Off (0) selected for OTP, 7 Days selected for Promotional
        PRESET_DAYS.forEach { days ->
            val chip = composeTestRule.onNodeWithTag("otp_preset_$days")
            chip.performScrollTo().assertIsDisplayed()
            if (days == 0) {
                chip.assertIsSelected()
            } else {
                chip.assertIsNotSelected()
            }
        }
        composeTestRule.onNodeWithTag("promo_preset_7")
            .performScrollTo()
            .assertIsSelected()

        // Cycle through every OTP auto-delete preset: 1, 3, 7, 15, 30, and back to 0 (Off)
        listOf(1, 3, 7, 15, 30, 0).forEach { targetDays ->
            composeTestRule.onNodeWithTag("otp_preset_$targetDays")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            composeTestRule.mainClock.advanceTimeBy(5_000)
            composeTestRule.waitForIdle()

            PRESET_DAYS.forEach { days ->
                val chip = composeTestRule.onNodeWithTag("otp_preset_$days")
                if (days == targetDays) {
                    chip.assertIsSelected()
                } else {
                    chip.assertIsNotSelected()
                }
            }

            val snapshot = app.container.settingsStore.getSettingsSnapshot()
            assertEquals(targetDays, snapshot.otpAutoDeleteDays)
            assertEquals(7, snapshot.promoAutoDeleteDays)
        }

        assertEquals(listOf(1, 3, 7, 15, 30, 0), recordedOtpSelections)
        composeTestRule.onNodeWithTag("promo_preset_7")
            .performScrollTo()
            .assertIsSelected()
    }

    @Test
    fun `EncryptedSettingsStore persists otpAutoDeleteDays across cold reload and defaults legacy snapshots to Off`() = runBlocking {
        assertEquals(0, AppSettings().otpAutoDeleteDays)
        assertEquals(7, AppSettings().promoAutoDeleteDays)

        val writerDir = createIsolatedDir("otp_settings_writer")
        val writerStore = EncryptedSettingsStore(
            context = buildIsolatedStoreContext(context, writerDir),
            tinkKeyManager = app.container.tinkKeyManager
        )

        writerStore.updateSettings {
            it.copy(
                promoAutoDeleteDays = 3,
                otpAutoDeleteDays = 15
            )
        }
        val writtenSnapshot = writerStore.getSettingsSnapshot()
        assertEquals(15, writtenSnapshot.otpAutoDeleteDays)
        assertEquals(3, writtenSnapshot.promoAutoDeleteDays)

        val persistedFile = File(writerDir, "traisms_secure_settings.pb")
        assertTrue(
            "Expected encrypted DataStore file on disk",
            persistedFile.exists() && persistedFile.length() > 0L
        )
        assertFalse(
            "Ciphertext on disk must not expose plaintext field names",
            persistedFile.readBytes().decodeToString().contains("otpAutoDeleteDays")
        )

        // Copy ciphertext file to a cold directory and instantiate a fresh EncryptedSettingsStore
        val readerDir = createIsolatedDir("otp_settings_reader")
        persistedFile.copyTo(File(readerDir, "traisms_secure_settings.pb"), overwrite = true)

        val coldRestoredStore = EncryptedSettingsStore(
            context = buildIsolatedStoreContext(context, readerDir),
            tinkKeyManager = app.container.tinkKeyManager
        )
        val restoredSnapshot = coldRestoredStore.getSettingsSnapshot()
        assertEquals(15, restoredSnapshot.otpAutoDeleteDays)
        assertEquals(3, restoredSnapshot.promoAutoDeleteDays)

        // Legacy Tink-encrypted AppSettings payloads without otpAutoDeleteDays must default safely to 0 (Off)
        val legacyJson = """{"promoAutoDeleteDays":30,"onboardingDismissed":true}""".encodeToByteArray()
        val legacyCiphertext = app.container.tinkKeyManager.getAead().encrypt(
            legacyJson,
            "traisms_encrypted_datastore_v1".toByteArray(Charsets.UTF_8)
        )
        val serializer = TinkAppSettingsSerializer(app.container.tinkKeyManager)
        val migratedSettings = serializer.readFrom(ByteArrayInputStream(legacyCiphertext))
        assertEquals(0, migratedSettings.otpAutoDeleteDays)
        assertEquals(30, migratedSettings.promoAutoDeleteDays)
        assertTrue(migratedSettings.onboardingDismissed)
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
            isIncoming = true,
            isRead = true,
            isArchived = isArchived,
            category = category,
            extractedOtp = extractedOtp,
            confidenceScore = 0.99f,
            classificationSource = "TEST"
        )
    }

    companion object {
        private const val HOUR_MILLIS = 60L * 60L * 1000L
        private const val DAY_MILLIS = 24L * HOUR_MILLIS
        private val PRESET_DAYS = listOf(0, 1, 3, 7, 15, 30)
    }
}
