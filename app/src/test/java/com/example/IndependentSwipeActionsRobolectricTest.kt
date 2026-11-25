package com.example

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AppSettings
import com.example.data.crypto.EncryptedSettingsStore
import com.example.data.crypto.LeftSwipeAction
import com.example.data.crypto.SwipeAction
import com.example.data.crypto.TinkAppSettingsSerializer
import com.example.data.db.CategoryUnreadCount
import com.example.data.db.SmsCategory
import com.example.data.db.ThreadSummary
import com.example.data.sms.SyncProgressState
import com.example.data.sms.SystemSmsHelper
import com.example.ui.MainViewModel
import com.example.ui.archive.ArchiveScreen
import com.example.ui.messages.MessagesScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.TraiSmsTheme
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class IndependentSwipeActionsRobolectricTest {

    @get:Rule(order = 0)
    val snapshotSyncRule: TestRule = createComposeSnapshotSyncRule()

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var app: TraiSmsApp
    private lateinit var systemSmsHelper: SystemSmsHelper
    private val tempStorageDirs = mutableListOf<File>()

    private data class RecordedAssignCategoryCall(
        val threadKey: String,
        val senderTitle: String,
        val targetCategory: SmsCategory,
        val alwaysRule: Boolean
    )

    @Before
    fun setUp(): Unit {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            app = context as TraiSmsApp
            systemSmsHelper = SystemSmsHelper(context)
            app.container.database.smsDao().deleteAllMessages()
            app.container.settingsStore.updateSettings {
                AppSettings(
                    initialSyncCompleted = true,
                    onboardingDismissed = true
                )
            }
        }
    }

    @After
    fun tearDown(): Unit {
        runBlocking {
            app.container.database.smsDao().deleteAllMessages()
            app.container.settingsStore.updateSettings {
                AppSettings(
                    initialSyncCompleted = true,
                    onboardingDismissed = true
                )
            }
            tempStorageDirs.forEach { dir -> dir.deleteRecursively() }
            tempStorageDirs.clear()
        }
    }

    @Test
    fun `AppSettings defaults all 9 left-right combinations cold reload MainViewModel updates and legacy Tink payloads work cleanly`(): Unit {
        runBlocking {
        // 1. Enum entries, labels, SwipeAction typealias, and out-of-the-box AppSettings() defaults
        assertEquals(
            listOf(LeftSwipeAction.ARCHIVE, LeftSwipeAction.DELETE, LeftSwipeAction.ASSIGN_CATEGORY),
            LeftSwipeAction.entries
        )
        assertEquals("Archive", LeftSwipeAction.ARCHIVE.label)
        assertEquals("Delete", LeftSwipeAction.DELETE.label)
        assertEquals("Assign Category", LeftSwipeAction.ASSIGN_CATEGORY.label)

        val aliasedAssignAction: SwipeAction = SwipeAction.ASSIGN_CATEGORY
        assertEquals(LeftSwipeAction.ASSIGN_CATEGORY, aliasedAssignAction)

        val defaultSettings = AppSettings()
        assertEquals(LeftSwipeAction.ARCHIVE, defaultSettings.leftSwipeAction)
        assertEquals(LeftSwipeAction.ASSIGN_CATEGORY, defaultSettings.rightSwipeAction)

        // 2. Exercise all 3 x 3 = 9 (leftSwipeAction, rightSwipeAction) combinations via EncryptedSettingsStore + cold reload
        val allNinePairs: List<Pair<LeftSwipeAction, LeftSwipeAction>> =
            LeftSwipeAction.entries.flatMap { left ->
                LeftSwipeAction.entries.map { right -> left to right }
            }
        assertEquals(9, allNinePairs.size)
        assertTrue((LeftSwipeAction.ASSIGN_CATEGORY to LeftSwipeAction.ARCHIVE) in allNinePairs)
        assertTrue((LeftSwipeAction.DELETE to LeftSwipeAction.ARCHIVE) in allNinePairs)
        assertTrue((LeftSwipeAction.DELETE to LeftSwipeAction.DELETE) in allNinePairs)

        val writerDir = createIsolatedDir("swipe_settings_writer")
        val writerStore = EncryptedSettingsStore(
            context = buildIsolatedStoreContext(context, writerDir),
            tinkKeyManager = app.container.tinkKeyManager
        )
        val persistedFile = File(writerDir, "traisms_secure_settings.pb")

        allNinePairs.forEachIndexed { index, (expectedLeft, expectedRight) ->
            writerStore.updateSettings {
                it.copy(
                    leftSwipeAction = expectedLeft,
                    rightSwipeAction = expectedRight
                )
            }

            val hotSnapshot = writerStore.getSettingsSnapshot()
            assertEquals(expectedLeft, hotSnapshot.leftSwipeAction)
            assertEquals(expectedRight, hotSnapshot.rightSwipeAction)

            assertTrue(
                "Expected Tink-encrypted DataStore file on disk for pair #$index ($expectedLeft, $expectedRight)",
                persistedFile.exists() && persistedFile.length() > 0L
            )
            val rawDiskString = persistedFile.readBytes().decodeToString()
            assertFalse(
                "Ciphertext on disk must not expose plaintext field names",
                rawDiskString.contains("leftSwipeAction") || rawDiskString.contains("rightSwipeAction")
            )

            val coldReaderDir = createIsolatedDir("swipe_settings_cold_reader_$index")
            persistedFile.copyTo(File(coldReaderDir, "traisms_secure_settings.pb"), overwrite = true)

            val coldStore = EncryptedSettingsStore(
                context = buildIsolatedStoreContext(context, coldReaderDir),
                tinkKeyManager = app.container.tinkKeyManager
            )
            val coldSnapshot = coldStore.getSettingsSnapshot()
            assertEquals(expectedLeft, coldSnapshot.leftSwipeAction)
            assertEquals(expectedRight, coldSnapshot.rightSwipeAction)
        }

        // 3. Legacy Tink-encrypted JSON payloads missing rightSwipeAction must default rightSwipeAction to ASSIGN_CATEGORY
        val associatedData = "traisms_encrypted_datastore_v1".toByteArray(Charsets.UTF_8)
        val serializer = TinkAppSettingsSerializer(app.container.tinkKeyManager)

        val legacyWithDeleteLeftJson =
            """{"promoAutoDeleteDays":15,"leftSwipeAction":"DELETE","onboardingDismissed":true}""".encodeToByteArray()
        val legacyWithDeleteLeftCiphertext = app.container.tinkKeyManager.getAead().encrypt(
            legacyWithDeleteLeftJson,
            associatedData
        )
        val migratedDeleteLeft = serializer.readFrom(ByteArrayInputStream(legacyWithDeleteLeftCiphertext))
        assertEquals(15, migratedDeleteLeft.promoAutoDeleteDays)
        assertTrue(migratedDeleteLeft.onboardingDismissed)
        assertEquals(LeftSwipeAction.DELETE, migratedDeleteLeft.leftSwipeAction)
        assertEquals(LeftSwipeAction.ASSIGN_CATEGORY, migratedDeleteLeft.rightSwipeAction)

        val legacyMinimalJson = """{"promoAutoDeleteDays":3,"onboardingDismissed":true}""".encodeToByteArray()
        val legacyMinimalCiphertext = app.container.tinkKeyManager.getAead().encrypt(
            legacyMinimalJson,
            associatedData
        )
        val migratedMinimal = serializer.readFrom(ByteArrayInputStream(legacyMinimalCiphertext))
        assertEquals(3, migratedMinimal.promoAutoDeleteDays)
        assertEquals(LeftSwipeAction.ARCHIVE, migratedMinimal.leftSwipeAction)
        assertEquals(LeftSwipeAction.ASSIGN_CATEGORY, migratedMinimal.rightSwipeAction)

        // Also verify cold EncryptedSettingsStore startup over a legacy encrypted file on disk
        val legacyStoreDir = createIsolatedDir("swipe_legacy_disk_store")
        File(legacyStoreDir, "traisms_secure_settings.pb").writeBytes(legacyWithDeleteLeftCiphertext)
        val legacyStore = EncryptedSettingsStore(
            context = buildIsolatedStoreContext(context, legacyStoreDir),
            tinkKeyManager = app.container.tinkKeyManager
        )
        val legacyDiskSnapshot = legacyStore.getSettingsSnapshot()
        assertEquals(LeftSwipeAction.DELETE, legacyDiskSnapshot.leftSwipeAction)
        assertEquals(LeftSwipeAction.ASSIGN_CATEGORY, legacyDiskSnapshot.rightSwipeAction)

        // 4. MainViewModel updateLeftSwipeAction and updateRightSwipeAction mutate independently
        val vmStore = ViewModelStore()
        val viewModel = ViewModelProvider(
            vmStore,
            MainViewModel.provideFactory(app.container)
        )[MainViewModel::class.java]
        val settingsCollector = launch(Dispatchers.Unconfined) {
            viewModel.settings.collect {}
        }
        try {
            waitForCondition {
                viewModel.settings.value.leftSwipeAction == LeftSwipeAction.ARCHIVE &&
                    viewModel.settings.value.rightSwipeAction == LeftSwipeAction.ASSIGN_CATEGORY
            }

            viewModel.updateLeftSwipeAction(LeftSwipeAction.ASSIGN_CATEGORY)
            waitForCondition {
                viewModel.settings.value.leftSwipeAction == LeftSwipeAction.ASSIGN_CATEGORY
            }
            assertEquals(LeftSwipeAction.ASSIGN_CATEGORY, viewModel.settings.value.rightSwipeAction)

            viewModel.updateRightSwipeAction(LeftSwipeAction.ARCHIVE)
            waitForCondition {
                viewModel.settings.value.rightSwipeAction == LeftSwipeAction.ARCHIVE
            }
            assertEquals(LeftSwipeAction.ASSIGN_CATEGORY, viewModel.settings.value.leftSwipeAction)

            viewModel.updateLeftSwipeAction(LeftSwipeAction.DELETE)
            viewModel.updateRightSwipeAction(LeftSwipeAction.DELETE)
            waitForCondition {
                viewModel.settings.value.leftSwipeAction == LeftSwipeAction.DELETE &&
                    viewModel.settings.value.rightSwipeAction == LeftSwipeAction.DELETE
            }
        } finally {
            settingsCollector.cancel()
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        }
    }

    @Test
    fun `SettingsScreen renders all 6 swipe chips reflects defaults and updates Left and Right independently`(): Unit {
        runBlocking {
        val initialSnapshot = app.container.settingsStore.getSettingsSnapshot()
        assertEquals(LeftSwipeAction.ARCHIVE, initialSnapshot.leftSwipeAction)
        assertEquals(LeftSwipeAction.ASSIGN_CATEGORY, initialSnapshot.rightSwipeAction)

        var uiSettings by mutableStateOf(initialSnapshot)
        val recordedLeftUpdates = mutableListOf<LeftSwipeAction>()
        val recordedRightUpdates = mutableListOf<LeftSwipeAction>()

        composeTestRule.setContent {
            TraiSmsTheme {
                Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                    SettingsScreen(
                        settings = uiSettings,
                        isDefaultSmsApp = true,
                        detectedRegionLabel = "IN • India",
                        totalMessageCount = 10,
                        systemSmsHelper = systemSmsHelper,
                        onRefreshRoleStatus = {},
                        onUpdatePromoAutoDelete = {},
                        onUpdateLeftSwipeAction = { newLeft ->
                            recordedLeftUpdates.add(newLeft)
                            uiSettings = uiSettings.copy(leftSwipeAction = newLeft)
                            runBlocking {
                                app.container.settingsStore.updateSettings {
                                    it.copy(leftSwipeAction = newLeft)
                                }
                            }
                        },
                        onUpdateRightSwipeAction = { newRight ->
                            recordedRightUpdates.add(newRight)
                            uiSettings = uiSettings.copy(rightSwipeAction = newRight)
                            runBlocking {
                                app.container.settingsStore.updateSettings {
                                    it.copy(rightSwipeAction = newRight)
                                }
                            }
                        },
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
                        onUpdateOtpAutoDelete = {}
                    )
                }
            }
        }

        // 1. Verify all 6 chips render via performScrollTo() and reflect initial defaults (Left = ARCHIVE, Right = ASSIGN_CATEGORY)
        assertSwipeChipsSelection(
            expectedLeft = LeftSwipeAction.ARCHIVE,
            expectedRight = LeftSwipeAction.ASSIGN_CATEGORY
        )

        // 2. Cycle Left: ARCHIVE -> ASSIGN_CATEGORY -> DELETE while Right stays ASSIGN_CATEGORY untouched
        composeTestRule.onNodeWithTag("swipe_left_assign_category_chip")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertSwipeChipsSelection(
            expectedLeft = LeftSwipeAction.ASSIGN_CATEGORY,
            expectedRight = LeftSwipeAction.ASSIGN_CATEGORY
        )
        assertEquals(listOf(LeftSwipeAction.ASSIGN_CATEGORY), recordedLeftUpdates)
        assertTrue(recordedRightUpdates.isEmpty())
        assertEquals(
            LeftSwipeAction.ASSIGN_CATEGORY,
            app.container.settingsStore.getSettingsSnapshot().leftSwipeAction
        )
        assertEquals(
            LeftSwipeAction.ASSIGN_CATEGORY,
            app.container.settingsStore.getSettingsSnapshot().rightSwipeAction
        )

        composeTestRule.onNodeWithTag("swipe_left_delete_chip")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertSwipeChipsSelection(
            expectedLeft = LeftSwipeAction.DELETE,
            expectedRight = LeftSwipeAction.ASSIGN_CATEGORY
        )
        assertEquals(
            listOf(LeftSwipeAction.ASSIGN_CATEGORY, LeftSwipeAction.DELETE),
            recordedLeftUpdates
        )
        assertTrue(recordedRightUpdates.isEmpty())
        assertEquals(
            LeftSwipeAction.DELETE,
            app.container.settingsStore.getSettingsSnapshot().leftSwipeAction
        )
        assertEquals(
            LeftSwipeAction.ASSIGN_CATEGORY,
            app.container.settingsStore.getSettingsSnapshot().rightSwipeAction
        )

        // 3. Cycle Right: ASSIGN_CATEGORY -> ARCHIVE -> DELETE while Left stays DELETE untouched
        composeTestRule.onNodeWithTag("swipe_right_archive_chip")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertSwipeChipsSelection(
            expectedLeft = LeftSwipeAction.DELETE,
            expectedRight = LeftSwipeAction.ARCHIVE
        )
        assertEquals(
            listOf(LeftSwipeAction.ASSIGN_CATEGORY, LeftSwipeAction.DELETE),
            recordedLeftUpdates
        )
        assertEquals(listOf(LeftSwipeAction.ARCHIVE), recordedRightUpdates)
        assertEquals(
            LeftSwipeAction.DELETE,
            app.container.settingsStore.getSettingsSnapshot().leftSwipeAction
        )
        assertEquals(
            LeftSwipeAction.ARCHIVE,
            app.container.settingsStore.getSettingsSnapshot().rightSwipeAction
        )

        composeTestRule.onNodeWithTag("swipe_right_delete_chip")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertSwipeChipsSelection(
            expectedLeft = LeftSwipeAction.DELETE,
            expectedRight = LeftSwipeAction.DELETE
        )
        assertEquals(
            listOf(LeftSwipeAction.ASSIGN_CATEGORY, LeftSwipeAction.DELETE),
            recordedLeftUpdates
        )
        assertEquals(
            listOf(LeftSwipeAction.ARCHIVE, LeftSwipeAction.DELETE),
            recordedRightUpdates
        )
        assertEquals(
            LeftSwipeAction.DELETE,
            app.container.settingsStore.getSettingsSnapshot().leftSwipeAction
        )
        assertEquals(
            LeftSwipeAction.DELETE,
            app.container.settingsStore.getSettingsSnapshot().rightSwipeAction
        )
        }
    }

    @Test
    fun `MessagesScreen executes swipeLeft and swipeRight actions and updates live when reconfigured dynamically`(): Unit {
        val threadKey = "VM-HDFCBK-T"
        val inboxThread = buildThreadSummary(
            id = 801L,
            threadKey = threadKey,
            body = "Acct XX9012 credited with INR 25,000.00 via IMPS.",
            category = SmsCategory.TRANSACTIONAL,
            isArchived = false,
            unreadCount = 1
        )

        var leftAction by mutableStateOf(LeftSwipeAction.ASSIGN_CATEGORY)
        var rightAction by mutableStateOf(LeftSwipeAction.ARCHIVE)

        val recordedArchiveCalls = mutableListOf<Pair<String, Boolean>>()
        val recordedDeleteCalls = mutableListOf<String>()
        val recordedAssignCalls = mutableListOf<RecordedAssignCategoryCall>()

        composeTestRule.setContent {
            TraiSmsTheme {
                Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                    MessagesScreen(
                        selectedCategory = SmsCategory.TRANSACTIONAL,
                        threads = listOf(inboxThread),
                        categoryCounts = mapOf(
                            SmsCategory.TRANSACTIONAL to CategoryUnreadCount(
                                category = SmsCategory.TRANSACTIONAL,
                                unreadCount = 1,
                                totalCount = 1
                            )
                        ),
                        totalMessageCount = 1,
                        searchQuery = "",
                        leftSwipeAction = leftAction,
                        rightSwipeAction = rightAction,
                        activeRegionBadge = "IN • India",
                        syncProgress = SyncProgressState(),
                        isDefaultSmsApp = true,
                        showSetupBanner = false,
                        showEmulatorDemoBanner = false,
                        systemSmsHelper = systemSmsHelper,
                        onSelectCategory = {},
                        onSearchQueryChange = {},
                        onOpenThread = {},
                        onOpenCompose = {},
                        onOpenOnboardingGuide = {},
                        onDismissSetupBanner = {},
                        onArchiveThread = { key, archived ->
                            recordedArchiveCalls.add(key to archived)
                        },
                        onDeleteThread = { key ->
                            recordedDeleteCalls.add(key)
                        },
                        onAssignSenderCategory = { key, senderTitle, target, alwaysRule ->
                            recordedAssignCalls.add(
                                RecordedAssignCategoryCall(key, senderTitle, target, alwaysRule)
                            )
                        },
                        onDeleteAllInCurrentTab = { _, _ -> },
                        onRequestCountInCurrentTab = { 0 },
                        onDeleteAllInApp = {},
                        onTriggerDeviceSync = {},
                        onSeedDemoInbox = {},
                        showUnreadOnly = false
                    )
                }
            }
        }

        // Phase 1: Left = ASSIGN_CATEGORY, Right = ARCHIVE
        composeTestRule.onAllNodesWithTag("move_category_bottom_sheet").assertCountEquals(0)

        // swipeLeft() (EndToStart) -> opens move_category_bottom_sheet
        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeLeft() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("move_category_bottom_sheet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("move_category_bottom_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("move_category_sender_title")
            .assertIsDisplayed()
            .assertTextContains(threadKey)
        assertTrue(recordedArchiveCalls.isEmpty())
        assertTrue(recordedDeleteCalls.isEmpty())

        // Complete & close move_category_bottom_sheet cleanly before the next gesture
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
        assertEquals(
            listOf(RecordedAssignCategoryCall(threadKey, threadKey, SmsCategory.SERVICE, true)),
            recordedAssignCalls
        )
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()

        // swipeRight() (StartToEnd) -> calls onArchiveThread(threadKey, true)
        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(threadKey to true), recordedArchiveCalls)
        assertTrue(recordedDeleteCalls.isEmpty())
        composeTestRule.onAllNodesWithTag("move_category_bottom_sheet").assertCountEquals(0)
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()

        // Phase 2: Reconfigure LIVE on the same mounted screen to Left = ARCHIVE, Right = DELETE
        // Verifies rememberUpdatedState refreshes confirmValueChange closures without stale state!
        recordedArchiveCalls.clear()
        recordedDeleteCalls.clear()
        recordedAssignCalls.clear()

        composeTestRule.runOnIdle {
            leftAction = LeftSwipeAction.ARCHIVE
            rightAction = LeftSwipeAction.DELETE
        }
        composeTestRule.waitForIdle()

        // swipeRight() now calls onDeleteThread(threadKey)
        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(threadKey), recordedDeleteCalls)
        assertTrue(recordedArchiveCalls.isEmpty())
        composeTestRule.onAllNodesWithTag("move_category_bottom_sheet").assertCountEquals(0)
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()

        // swipeLeft() now calls onArchiveThread(threadKey, true)
        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(threadKey to true), recordedArchiveCalls)
        assertEquals(listOf(threadKey), recordedDeleteCalls)
        composeTestRule.onAllNodesWithTag("move_category_bottom_sheet").assertCountEquals(0)
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()

        // Phase 3: Reconfigure LIVE to Left = DELETE, Right = ASSIGN_CATEGORY so all 3 actions are proven on both directions
        recordedArchiveCalls.clear()
        recordedDeleteCalls.clear()

        composeTestRule.runOnIdle {
            leftAction = LeftSwipeAction.DELETE
            rightAction = LeftSwipeAction.ASSIGN_CATEGORY
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(threadKey), recordedDeleteCalls)
        assertTrue(recordedArchiveCalls.isEmpty())
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeRight() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("move_category_bottom_sheet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("move_category_bottom_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_move_category_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("move_category_bottom_sheet")
                .fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `ArchiveScreen executes swipeLeft and swipeRight for Delete Unarchive and Assign Category with live reconfiguration`(): Unit {
        val threadKey = "AX-AIRTEL-S"
        val archivedThread = buildThreadSummary(
            id = 901L,
            threadKey = threadKey,
            body = "Your monthly broadband invoice is ready.",
            category = SmsCategory.SERVICE,
            isArchived = true,
            unreadCount = 0
        )

        var leftAction by mutableStateOf(LeftSwipeAction.DELETE)
        var rightAction by mutableStateOf(LeftSwipeAction.ARCHIVE)

        val recordedSetArchivedCalls = mutableListOf<Pair<String, Boolean>>()
        val recordedDeleteThreadsCalls = mutableListOf<List<String>>()
        val recordedAssignCalls = mutableListOf<RecordedAssignCategoryCall>()

        composeTestRule.setContent {
            TraiSmsTheme {
                Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                    ArchiveScreen(
                        archivedThreads = listOf(archivedThread),
                        leftSwipeAction = leftAction,
                        rightSwipeAction = rightAction,
                        onOpenThread = {},
                        onSetThreadArchived = { key, archived ->
                            recordedSetArchivedCalls.add(key to archived)
                        },
                        onSetThreadsArchived = { _, _ -> },
                        onDeleteThreads = { keys ->
                            recordedDeleteThreadsCalls.add(keys)
                        },
                        onAssignSenderCategory = { key, senderTitle, target, alwaysRule ->
                            recordedAssignCalls.add(
                                RecordedAssignCategoryCall(key, senderTitle, target, alwaysRule)
                            )
                        }
                    )
                }
            }
        }

        // Phase 1: Left = DELETE, Right = ARCHIVE (Unarchive)
        // swipeLeft() -> calls onDeleteThreads(listOf(threadKey))
        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(listOf(threadKey)), recordedDeleteThreadsCalls)
        assertTrue(recordedSetArchivedCalls.isEmpty())
        composeTestRule.onAllNodesWithTag("move_category_bottom_sheet").assertCountEquals(0)
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()

        // swipeRight() -> unarchives via onSetThreadArchived(threadKey, false)
        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(threadKey to false), recordedSetArchivedCalls)
        assertEquals(listOf(listOf(threadKey)), recordedDeleteThreadsCalls)
        composeTestRule.onAllNodesWithTag("move_category_bottom_sheet").assertCountEquals(0)
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()

        // Phase 2: Reconfigure LIVE on ArchiveScreen to Left = ASSIGN_CATEGORY, Right = DELETE
        recordedSetArchivedCalls.clear()
        recordedDeleteThreadsCalls.clear()

        composeTestRule.runOnIdle {
            leftAction = LeftSwipeAction.ASSIGN_CATEGORY
            rightAction = LeftSwipeAction.DELETE
        }
        composeTestRule.waitForIdle()

        // swipeLeft() -> opens move_category_bottom_sheet on ArchiveScreen
        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeLeft() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("move_category_bottom_sheet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("move_category_bottom_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("move_category_sender_title")
            .assertIsDisplayed()
            .assertTextContains(threadKey)

        composeTestRule.onNodeWithTag("sheet_option_transactional")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("confirm_move_category_button")
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("move_category_bottom_sheet")
                .fetchSemanticsNodes().isEmpty()
        }
        assertEquals(
            listOf(RecordedAssignCategoryCall(threadKey, threadKey, SmsCategory.TRANSACTIONAL, true)),
            recordedAssignCalls
        )
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()

        // swipeRight() -> calls onDeleteThreads(listOf(threadKey))
        composeTestRule.onNodeWithTag("thread_row_$threadKey")
            .assertIsDisplayed()
            .performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(listOf(threadKey)), recordedDeleteThreadsCalls)
        assertTrue(recordedSetArchivedCalls.isEmpty())
        composeTestRule.mainClock.advanceTimeBy(15_000)
        composeTestRule.waitForIdle()
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

    private fun assertSwipeChipsSelection(
        expectedLeft: LeftSwipeAction,
        expectedRight: LeftSwipeAction
    ) {
        val leftChips = listOf(
            LeftSwipeAction.ARCHIVE to "swipe_left_archive_chip",
            LeftSwipeAction.DELETE to "swipe_left_delete_chip",
            LeftSwipeAction.ASSIGN_CATEGORY to "swipe_left_assign_category_chip"
        )
        val rightChips = listOf(
            LeftSwipeAction.ARCHIVE to "swipe_right_archive_chip",
            LeftSwipeAction.DELETE to "swipe_right_delete_chip",
            LeftSwipeAction.ASSIGN_CATEGORY to "swipe_right_assign_category_chip"
        )

        leftChips.forEach { (action, tag) ->
            val chip = composeTestRule.onNodeWithTag(tag)
            chip.performScrollTo().assertIsDisplayed()
            if (action == expectedLeft) {
                chip.assertIsSelected()
            } else {
                chip.assertIsNotSelected()
            }
        }

        rightChips.forEach { (action, tag) ->
            val chip = composeTestRule.onNodeWithTag(tag)
            chip.performScrollTo().assertIsDisplayed()
            if (action == expectedRight) {
                chip.assertIsSelected()
            } else {
                chip.assertIsNotSelected()
            }
        }
    }

    private fun buildThreadSummary(
        id: Long,
        threadKey: String,
        body: String,
        category: SmsCategory,
        isArchived: Boolean,
        unreadCount: Int
    ): ThreadSummary {
        return ThreadSummary(
            latestMessageId = id,
            threadKey = threadKey,
            rawAddress = threadKey,
            contactName = null,
            latestBody = body,
            latestTimestampMillis = 1_700_600_000_000L,
            category = category,
            extractedOtp = null,
            isArchived = isArchived,
            unreadCount = unreadCount,
            messageCount = 1,
            classificationSource = "TEST"
        )
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

    private suspend fun waitForCondition(
        timeoutMillis: Long = 5_000L,
        predicate: suspend () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            delay(20)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Timed out waiting for condition", predicate())
    }
}
