package com.example

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.crypto.BackupMessageRecord
import com.example.data.crypto.BackupSenderRuleRecord
import com.example.data.crypto.EncryptedBackupPayload
import com.example.data.crypto.EncryptedSettingsStore
import com.example.data.crypto.TinkAppSettingsSerializer
import com.example.data.crypto.TinkKeyManager
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.sms.SmsNotificationActionReceiver
import com.example.data.sms.SmsNotificationHelper
import com.example.data.sms.SystemSmsHelper
import com.example.ui.MainViewModel
import com.example.ui.common.GlobalDeleteAllMessagesDialog
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.TraiSmsTheme
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenshotSupportRobolectricTest {

    @get:Rule(order = 0)
    val snapshotSyncRule: TestRule = createComposeSnapshotSyncRule()

    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var app: TraiSmsApp
    private lateinit var systemSmsHelper: SystemSmsHelper
    private lateinit var notificationManager: NotificationManager
    private val tempStorageDirs = mutableListOf<File>()

    @Before
    fun setUp(): Unit {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            app = context as TraiSmsApp
            systemSmsHelper = SystemSmsHelper(context)
            Shadows.shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.cancelAll()

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
                AppSettings(
                    appLockEnabled = false,
                    initialSyncCompleted = true,
                    onboardingDismissed = true
                )
            }
            tempStorageDirs.forEach { dir -> dir.deleteRecursively() }
            tempStorageDirs.clear()
        }
    }

    @Test
    fun `MainActivity unconditionally clears FLAG_SECURE across physical device emulator and recreated windows`(): Unit {
        val buildFields = listOf("FINGERPRINT", "MODEL", "PRODUCT", "HARDWARE", "BOARD", "DEVICE")
        val savedBuildValues = buildFields.associateWith { getBuildField(it) }

        try {
            // 1. Physical release-like device profile (isRunningOnEmulator() == false)
            setBuildProfile(
                fingerprint = "google/husky/husky:14/UQ1A.240205.004/11269751:user/release-keys",
                model = "Pixel 8 Pro",
                product = "husky",
                hardware = "husky",
                board = "husky",
                device = "husky"
            )
            assertFalse(
                "Expected physical device profile for non-emulator screenshot verification",
                systemSmsHelper.isRunningOnEmulator()
            )

            Robolectric.buildActivity(MainActivity::class.java).use { controller ->
                controller.setup()
                val activity = controller.get()
                try {
                    assertScreenshotsAllowedAcrossAllWindows(activity)

                    // Even if an external caller sets FLAG_SECURE on the window, recreation clears it in onCreate
                    activity.window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                    controller.recreate()
                    val recreated = controller.get()
                    assertScreenshotsAllowedAcrossAllWindows(recreated)
                } finally {
                    controller.get().setContentView(View(controller.get()))
                    flushMainLooperAndSnapshots()
                }
            }

            // 2. Emulator device profile (isRunningOnEmulator() == true)
            setBuildProfile(
                fingerprint = "google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.036/1100:userdebug/dev-keys",
                model = "sdk_gphone64_x86_64",
                product = "sdk_gphone64_x86_64",
                hardware = "ranchu",
                board = "goldfish_x86_64",
                device = "emu64xa"
            )
            assertTrue(
                "Expected emulator device profile",
                systemSmsHelper.isRunningOnEmulator()
            )

            Robolectric.buildActivity(MainActivity::class.java).use { controller ->
                controller.setup()
                try {
                    assertScreenshotsAllowedAcrossAllWindows(controller.get())
                } finally {
                    controller.get().setContentView(View(controller.get()))
                    flushMainLooperAndSnapshots()
                }
            }
        } finally {
            savedBuildValues.forEach { (field, value) ->
                if (value != null) setBuildField(field, value)
            }
        }
    }

    @Test
    fun `All primary routes 8 category tabs onboarding thread detail compose dialogs and bottom sheets allow screenshots`(): Unit {
        runBlocking {
            val smsDao = app.container.database.smsDao()
            SmsCategory.orderedTabs.forEachIndexed { idx, category ->
                smsDao.insertMessage(
                    SmsMessageEntity(
                        systemSmsId = 5000L + idx,
                        threadKey = "SENDER-${category.name}",
                        rawAddress = "SENDER-${category.name}",
                        contactName = null,
                        body = "Sample ${category.title} message ${100000 + idx}",
                        bodySha256 = BackupCryptoEngine.sha256Hex("Sample ${category.title} message ${100000 + idx}"),
                        timestampMillis = 1_700_000_000_000L + idx * 1000L,
                        isIncoming = true,
                        isRead = false,
                        isArchived = false,
                        category = category,
                        extractedOtp = if (category == SmsCategory.OTP) "482910" else null,
                        confidenceScore = 0.99f,
                        classificationSource = "TEST"
                    )
                )
            }
            smsDao.insertMessage(
                SmsMessageEntity(
                    systemSmsId = 5999L,
                    threadKey = "ARCHIVED-SENDER",
                    rawAddress = "ARCHIVED-SENDER",
                    contactName = null,
                    body = "Archived bank statement notice",
                    bodySha256 = BackupCryptoEngine.sha256Hex("Archived bank statement notice"),
                    timestampMillis = 1_700_000_999_000L,
                    isIncoming = true,
                    isRead = true,
                    isArchived = true,
                    category = SmsCategory.TRANSACTIONAL,
                    extractedOtp = null,
                    confidenceScore = 0.99f,
                    classificationSource = "TEST"
                )
            )
        }

        // 1. Messages across all 8 tabs, Overflow Menu -> Onboarding, Archive, Backup, Settings, and Global Delete Dialog
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("bottom_navigation_bar").assertIsDisplayed()
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }

                for (category in SmsCategory.orderedTabs) {
                    val tabTag = "tab_${category.name.lowercase(Locale.ROOT)}"
                    runCatching { composeTestRule.onNodeWithTag(tabTag).performScrollTo() }
                    runCatching {
                        composeTestRule.onNodeWithTag(tabTag).performClick()
                    }.onFailure {
                        composeTestRule.onNodeWithTag(tabTag).performSemanticsAction(SemanticsActions.OnClick)
                    }
                    composeTestRule.waitForIdle()
                    scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }
                }

                // Overflow DropdownMenu + OnboardingRoute
                composeTestRule.onNodeWithTag("inbox_overflow_menu_button").performClick()
                composeTestRule.waitForIdle()
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }

                composeTestRule.onNodeWithTag("menu_open_onboarding").performClick()
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("onboarding_screen").assertIsDisplayed()
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }

                composeTestRule.onNodeWithTag("skip_onboarding_button")
                    .performScrollTo()
                    .performClick()
                composeTestRule.waitForIdle()

                // ArchiveRoute
                composeTestRule.onNodeWithTag("bottom_nav_archive").performClick()
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("archive_search_input").assertIsDisplayed()
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }

                // BackupRoute
                composeTestRule.onNodeWithTag("bottom_nav_backup").performClick()
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("backup_screen").assertIsDisplayed()
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }

                // SettingsRoute
                composeTestRule.onNodeWithTag("bottom_nav_settings").performClick()
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
                composeTestRule.onAllNodesWithTag("flag_secure_switch").assertCountEquals(0)
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }
            } finally {
                scenario.onActivity { it.setContentView(View(it)) }
                flushMainLooperAndSnapshots()
            }
        }
    }

    @Test
    fun `ThreadDetailComposeSmsAndMoveCategoryBottomSheetRoutesAllowScreenshots`(): Unit {
        runBlocking {
            val smsDao = app.container.database.smsDao()
            smsDao.insertMessage(
                SmsMessageEntity(
                    systemSmsId = 6102L,
                    threadKey = "SENDER-PERSONAL",
                    rawAddress = "SENDER-PERSONAL",
                    contactName = null,
                    body = "Sample Personal message 100004",
                    bodySha256 = BackupCryptoEngine.sha256Hex("Sample Personal message 100004"),
                    timestampMillis = 1_700_000_004_000L,
                    isIncoming = true,
                    isRead = false,
                    isArchived = false,
                    category = SmsCategory.PERSONAL,
                    extractedOtp = null,
                    confidenceScore = 0.99f,
                    classificationSource = "TEST"
                )
            )
        }

        // 1. ThreadDetailRoute + Message Options ModalBottomSheet via inbox row click
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("thread_row_SENDER-PERSONAL")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("thread_row_SENDER-PERSONAL").performClick()

                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("thread_messages_list")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }

                composeTestRule.onNodeWithText("Sample Personal message 100004", substring = true)
                    .performTouchInput { longClick() }
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("message_actions_prompt")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("message_action_copy").assertIsDisplayed()
                composeTestRule.onNodeWithTag("message_action_forward").assertIsDisplayed()
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }
            } finally {
                scenario.onActivity { it.setContentView(View(it)) }
                flushMainLooperAndSnapshots()
            }
        }

        // 2. ComposeSmsRoute via compose_sms_fab click
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("compose_sms_fab")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("compose_sms_fab").performClick()

                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("compose_body_input")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("compose_recipient_input").assertIsDisplayed()
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }
            } finally {
                scenario.onActivity { it.setContentView(View(it)) }
                flushMainLooperAndSnapshots()
            }
        }

        // 3. MoveSenderCategoryBottomSheet via ACTION_ASSIGN_CATEGORY
        val assignIntent = Intent(context, MainActivity::class.java).apply {
            action = SmsNotificationHelper.ACTION_ASSIGN_CATEGORY
            putExtra(SmsNotificationHelper.EXTRA_THREAD_KEY, "SENDER-PERSONAL")
            putExtra(SmsNotificationHelper.EXTRA_RAW_ADDRESS, "SENDER-PERSONAL")
            putExtra(SmsNotificationHelper.EXTRA_SENDER_TITLE, "SENDER-PERSONAL")
            putExtra(SmsNotificationHelper.EXTRA_CURRENT_CATEGORY, SmsCategory.PERSONAL.name)
        }
        ActivityScenario.launch<MainActivity>(assignIntent).use { scenario ->
            try {
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag("move_category_bottom_sheet")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag("confirm_move_category_button")
                    .performScrollTo()
                    .assertIsDisplayed()
                scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }
            } finally {
                scenario.onActivity { it.setContentView(View(it)) }
                flushMainLooperAndSnapshots()
            }
        }
    }

    @Test
    fun `Tablet NavigationRail layout allows screenshots without FLAG_SECURE`(): Unit {
        RuntimeEnvironment.setQualifiers("w840dp-h900dp-xxhdpi")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    composeTestRule.waitForIdle()
                    composeTestRule.onNodeWithTag("primary_navigation_rail").assertIsDisplayed()
                    scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }
                } finally {
                    scenario.onActivity { it.setContentView(View(it)) }
                    flushMainLooperAndSnapshots()
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers("w411dp-h914dp-xxhdpi")
        }
    }

    @Test
    fun `SettingsScreen omits flag_secure_switch keeps Security Hardening App Lock and preserves defaulted parameter`(): Unit {
        val viewModelMethods = MainViewModel::class.java.declaredMethods.map { it.name }.toSet()
        assertFalse(
            "MainViewModel must not expose updateBlockScreenshots",
            "updateBlockScreenshots" in viewModelMethods
        )
        assertTrue(
            "MainViewModel must preserve updateAppLockEnabled",
            "updateAppLockEnabled" in viewModelMethods
        )

        val settingsFields = AppSettings::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("AppSettings must not contain blockScreenshots", "blockScreenshots" in settingsFields)
        assertFalse(
            "AppSettings must not contain userForcedFlagSecureInStream",
            "userForcedFlagSecureInStream" in settingsFields
        )
        assertTrue("AppSettings must preserve appLockEnabled", "appLockEnabled" in settingsFields)

        Robolectric.buildActivity(ComponentActivity::class.java).use { controller ->
            controller.setup()
            val hostActivity = controller.get()
            try {
                var uiSettings by mutableStateOf(AppSettings(appLockEnabled = false))
                val recordedAppLockChanges = mutableListOf<Boolean>()

                hostActivity.setContent {
                    TraiSmsTheme {
                        Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                            // Omit onToggleBlockScreenshots to verify defaulted parameter source/binary contract
                            SettingsScreen(
                                settings = uiSettings,
                                isDefaultSmsApp = true,
                                detectedRegionLabel = "IN • India",
                                totalMessageCount = 8,
                                systemSmsHelper = systemSmsHelper,
                                onRefreshRoleStatus = {},
                                onUpdatePromoAutoDelete = {},
                                onUpdateLeftSwipeAction = {},
                                onUpdateUnarchiveOnNewSms = {},
                                onUpdateRegionOverride = {},
                                onUpdateBackupSchedule = { _, _ -> },
                                onNavigateToBackupTab = {},
                                onToggleAppLock = { enabled ->
                                    recordedAppLockChanges.add(enabled)
                                    uiSettings = uiSettings.copy(appLockEnabled = enabled)
                                },
                                onRerunCategorization = {},
                                onDeleteAllMessagesInApp = {}
                            )
                        }
                    }
                }
                composeTestRule.waitForIdle()

                composeTestRule.onNodeWithText("Security Hardening")
                    .performScrollTo()
                    .assertIsDisplayed()
                composeTestRule.onNodeWithText(
                    "SQLCipher 256-bit DB encryption & Tink Hardware Keystore are always active."
                )
                    .performScrollTo()
                    .assertIsDisplayed()

                composeTestRule.onNodeWithTag("app_lock_switch")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertIsOff()

                composeTestRule.onAllNodesWithTag("flag_secure_switch").assertCountEquals(0)
                composeTestRule.onAllNodesWithText(
                    "Block Screenshots & Recents Preview (FLAG_SECURE)"
                ).assertCountEquals(0)

                composeTestRule.onNodeWithTag("app_lock_switch")
                    .performScrollTo()
                    .performClick()
                composeTestRule.waitForIdle()

                composeTestRule.onNodeWithTag("app_lock_switch").assertIsOn()
                assertEquals(listOf(true), recordedAppLockChanges)
                assertScreenshotsAllowedAcrossAllWindows(hostActivity)
            } finally {
                hostActivity.setContentView(View(hostActivity))
                flushMainLooperAndSnapshots()
            }
        }
    }

    @Test
    fun `TinkAppSettingsSerializer cleanly migrates legacy encrypted settings containing blockScreenshots true`(): Unit {
        runBlocking {
            val legacyJson = """
                {
                    "promoAutoDeleteDays": 15,
                    "otpAutoDeleteDays": 3,
                    "appLockEnabled": true,
                    "blockScreenshots": true,
                    "userForcedFlagSecureInStream": true,
                    "regionOverride": "IN",
                    "backupPassphrase": "UpgradeSafeVaultKey!2026",
                    "onboardingDismissed": true,
                    "smsFontBold": true
                }
            """.trimIndent().encodeToByteArray()

            val associatedData = "traisms_encrypted_datastore_v1".toByteArray(Charsets.UTF_8)
            val legacyCiphertext = app.container.tinkKeyManager.getAead().encrypt(legacyJson, associatedData)

            // 1. Direct TinkAppSettingsSerializer deserialization
            val serializer = TinkAppSettingsSerializer(app.container.tinkKeyManager)
            val migrated = serializer.readFrom(ByteArrayInputStream(legacyCiphertext))
            assertEquals(15, migrated.promoAutoDeleteDays)
            assertEquals(3, migrated.otpAutoDeleteDays)
            assertTrue(migrated.appLockEnabled)
            assertEquals("IN", migrated.regionOverride)
            assertEquals("UpgradeSafeVaultKey!2026", migrated.backupPassphrase)
            assertTrue(migrated.onboardingDismissed)
            assertTrue(migrated.smsFontBold)

            // 2. Cold EncryptedSettingsStore boot from persisted traisms_secure_settings.pb in noBackupFilesDir
            val upgradeDir = createIsolatedDir("legacy_upgrade_store")
            val settingsFile = File(upgradeDir, "traisms_secure_settings.pb")
            settingsFile.writeBytes(legacyCiphertext)

            val upgradedStore = EncryptedSettingsStore(
                context = buildIsolatedStoreContext(context, upgradeDir),
                tinkKeyManager = app.container.tinkKeyManager
            )
            val snapshot = upgradedStore.getSettingsSnapshot()
            assertEquals(15, snapshot.promoAutoDeleteDays)
            assertEquals(3, snapshot.otpAutoDeleteDays)
            assertTrue(snapshot.appLockEnabled)
            assertEquals("UpgradeSafeVaultKey!2026", snapshot.backupPassphrase)

            // Re-saving removes legacy unknown fields while keeping ciphertext encrypted on disk
            upgradedStore.updateSettings { it.copy(promoAutoDeleteDays = 30) }
            val rewrittenBytes = settingsFile.readBytes()
            assertFalse(
                "Persisted settings file must remain Tink-encrypted on disk",
                rewrittenBytes.decodeToString().contains("UpgradeSafeVaultKey!2026")
            )
            val decryptedRewritten = app.container.tinkKeyManager
                .getAead()
                .decrypt(rewrittenBytes, associatedData)
                .decodeToString()
            assertFalse("Rewritten JSON must not contain blockScreenshots", decryptedRewritten.contains("blockScreenshots"))
            assertFalse(
                "Rewritten JSON must not contain userForcedFlagSecureInStream",
                decryptedRewritten.contains("userForcedFlagSecureInStream")
            )

            // 3. Corrupted or wrong-AAD ciphertext falls back safely to defaultValue without crashing
            val wrongAadCiphertext = app.container.tinkKeyManager
                .getAead()
                .encrypt(legacyJson, "wrong_aad".toByteArray(Charsets.UTF_8))
            val fallback = serializer.readFrom(ByteArrayInputStream(wrongAadCiphertext))
            assertEquals(AppSettings(), fallback)
        }
    }

    @Test
    fun `Security hardening features remain intact including AppLockGate TinkKeyManager BackupCrypto NotificationRedaction ClipboardMasking and DeleteConfirmation`(): Unit {
        runBlocking {
            // 1. TinkKeyManager 32-byte encrypted database passphrase in noBackupFilesDir
            val keyDir = createIsolatedDir("tink_key_dir")
            val isolatedTink = TinkKeyManager(buildIsolatedStoreContext(context, keyDir))
            val dbPassphrase1 = isolatedTink.getOrCreateDatabasePassphrase()
            val dbPassphrase2 = isolatedTink.getOrCreateDatabasePassphrase()
            assertEquals(32, dbPassphrase1.size)
            assertArrayEquals(dbPassphrase1, dbPassphrase2)
            val encKeyFile = File(keyDir, "traisms_sqlcipher_passphrase.enc")
            assertTrue("Encrypted SQLCipher passphrase file must exist in noBackupFilesDir", encKeyFile.exists())
            assertTrue("Ciphertext must exceed 32-byte raw key due to GCM IV + tag", encKeyFile.length() > 32L)

            // 2. BackupCryptoEngine PBKDF2-HMAC-SHA256 + AES-256-GCM + AAD integrity checks
            val backupPayload = EncryptedBackupPayload(
                createdAtMillis = 1_700_000_000_000L,
                messages = listOf(
                    BackupMessageRecord(
                        threadKey = "VM-HDFCBK-T",
                        rawAddress = "VM-HDFCBK-T",
                        body = "INR 10,000 credited",
                        bodySha256 = BackupCryptoEngine.sha256Hex("INR 10,000 credited"),
                        timestampMillis = 1_700_000_000_000L,
                        isIncoming = true,
                        isRead = true,
                        isArchived = false,
                        category = SmsCategory.TRANSACTIONAL
                    )
                ),
                senderRules = listOf(
                    BackupSenderRuleRecord(
                        normalizedSender = "VM-HDFCBK-T",
                        displaySender = "VM-HDFCBK-T",
                        category = SmsCategory.TRANSACTIONAL,
                        updatedAtMillis = 1_700_000_000_000L
                    )
                )
            )
            val archiveBytes = BackupCryptoEngine.encryptPayload(backupPayload, "StrongPass#2026")
            assertEquals('T'.code.toByte(), archiveBytes[0])
            assertEquals('R'.code.toByte(), archiveBytes[1])
            assertEquals('A'.code.toByte(), archiveBytes[2])
            assertEquals('I'.code.toByte(), archiveBytes[3])
            assertEquals(1.toByte(), archiveBytes[4])

            val restoredPayload = BackupCryptoEngine.decryptPayload(archiveBytes, "StrongPass#2026")
            assertEquals(1, restoredPayload.messages.size)
            assertThrows(SecurityException::class.java) {
                BackupCryptoEngine.decryptPayload(archiveBytes, "WrongPassword")
            }
            val tamperedHeader = archiveBytes.copyOf().also { it[4] = 99 }
            assertThrows(IllegalArgumentException::class.java) {
                BackupCryptoEngine.decryptPayload(tamperedHeader, "StrongPass#2026")
            }
            val tamperedCiphertext = archiveBytes.copyOf().also {
                it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0xFF).toByte()
            }
            assertThrows(SecurityException::class.java) {
                BackupCryptoEngine.decryptPayload(tamperedCiphertext, "StrongPass#2026")
            }

            // 3. Notification VISIBILITY_PRIVATE, App Lock OTP redaction, and Android 13+ EXTRA_IS_SENSITIVE clipboard
            val notifHelper = SmsNotificationHelper(context)
            notifHelper.postIncomingSmsNotification(
                messageId = 901L,
                threadKey = "LOCKED-OTP",
                rawAddress = "LOCKED-OTP",
                senderTitle = "HDFC Bank",
                body = "837261 is your secret OTP.",
                category = SmsCategory.OTP,
                extractedOtp = "837261",
                redactPreviewForAppLock = true
            )
            val lockedNotif = Shadows.shadowOf(notificationManager).getNotification(
                SmsNotificationHelper.notificationIdForThread("LOCKED-OTP")
            )
            assertNotNull(lockedNotif)
            assertEquals(Notification.VISIBILITY_PRIVATE, lockedNotif.visibility)
            assertFalse(
                lockedNotif.extras.getCharSequence("android.title")?.toString().orEmpty().contains("837261")
            )
            assertFalse(
                lockedNotif.extras.getCharSequence("android.text")?.toString().orEmpty().contains("837261")
            )
            assertEquals(
                listOf("Archive", "Assign Category", "Delete"),
                lockedNotif.actions?.map { it.title.toString() }.orEmpty()
            )

            notifHelper.postIncomingSmsNotification(
                messageId = 902L,
                threadKey = "UNLOCKED-OTP",
                rawAddress = "UNLOCKED-OTP",
                senderTitle = "SBI Bank",
                body = "519203 is your OTP.",
                category = SmsCategory.OTP,
                extractedOtp = "519203",
                redactPreviewForAppLock = false
            )
            val unlockedNotif = Shadows.shadowOf(notificationManager).getNotification(
                SmsNotificationHelper.notificationIdForThread("UNLOCKED-OTP")
            )
            assertNotNull(unlockedNotif)
            val shadowApp = Shadows.shadowOf(app as Application)
            shadowApp.clearBroadcastIntents()
            val expanded = unlockedNotif.bigContentView.apply(context, FrameLayout(context))
            expanded.findViewById<TextView>(R.id.notif_btn_copy_otp).performClick()
            val copyIntent = shadowApp.broadcastIntents.single {
                it.action == SmsNotificationHelper.ACTION_COPY_OTP
            }
            SmsNotificationActionReceiver().onReceive(context, copyIntent)
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            assertNotNull(clip)
            assertEquals("519203", clip!!.getItemAt(0).text.toString())
            assertEquals(true, clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))

            // 4. AppLockGateScreen blocks inbox access until authenticated while still allowing screenshots
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

            val lockIntent = Intent(context, MainActivity::class.java).apply {
                action = SmsNotificationHelper.ACTION_ASSIGN_CATEGORY
                putExtra(SmsNotificationHelper.EXTRA_THREAD_KEY, "LOCKED-OTP")
                putExtra(SmsNotificationHelper.EXTRA_RAW_ADDRESS, "LOCKED-OTP")
                putExtra(SmsNotificationHelper.EXTRA_SENDER_TITLE, "HDFC Bank")
                putExtra(SmsNotificationHelper.EXTRA_CURRENT_CATEGORY, SmsCategory.OTP.name)
            }
            ActivityScenario.launch<MainActivity>(lockIntent).use { scenario ->
                try {
                    composeTestRule.waitUntil(timeoutMillis = 5_000) {
                        composeTestRule.onAllNodesWithTag("app_lock_gate_screen")
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    composeTestRule.onNodeWithTag("app_lock_gate_screen").assertIsDisplayed()
                    composeTestRule.onNodeWithTag("unlock_biometric_button").assertIsDisplayed()
                    assertTrue(
                        composeTestRule.onAllNodesWithTag("bottom_navigation_bar")
                            .fetchSemanticsNodes().isEmpty()
                    )
                    scenario.onActivity { assertScreenshotsAllowedAcrossAllWindows(it) }
                } finally {
                    scenario.onActivity { it.setContentView(View(it)) }
                    flushMainLooperAndSnapshots()
                }
            }
        }
    }

    @Test
    fun `GlobalDeleteAllMessagesDialog mounts Dialog window without FLAG_SECURE`(): Unit {
        ShadowDialog.reset()
        Robolectric.buildActivity(ComponentActivity::class.java).use { controller ->
            controller.setup()
            val hostActivity = controller.get()
            var showDialog by mutableStateOf(true)
            try {
                hostActivity.setContent {
                    TraiSmsTheme {
                        if (showDialog) {
                            GlobalDeleteAllMessagesDialog(
                                totalCount = 42,
                                onDismiss = { showDialog = false },
                                onConfirmDeleteAll = {}
                            )
                        }
                    }
                }
                flushMainLooperAndSnapshots()

                val latestDialog = ShadowDialog.getLatestDialog()
                assertNotNull("Expected AlertDialog window to be mounted", latestDialog)
                assertTrue("Expected AlertDialog to be showing", latestDialog.isShowing)
                val dialogWindow = latestDialog.window
                assertNotNull("Expected AlertDialog to have an Android Window", dialogWindow)
                assertEquals(
                    "GlobalDeleteAllMessagesDialog window must not set FLAG_SECURE",
                    0,
                    dialogWindow!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
                )
                assertScreenshotsAllowedAcrossAllWindows(hostActivity)
            } finally {
                showDialog = false
                flushMainLooperAndSnapshots()
                runCatching { ShadowDialog.getLatestDialog()?.dismiss() }
                hostActivity.setContentView(View(hostActivity))
                flushMainLooperAndSnapshots()
            }
        }
    }

    private fun assertScreenshotsAllowedAcrossAllWindows(activity: ComponentActivity) {
        val activityFlags = activity.window.attributes.flags
        assertEquals(
            "MainActivity window must never set WindowManager.LayoutParams.FLAG_SECURE",
            0,
            activityFlags and WindowManager.LayoutParams.FLAG_SECURE
        )
        val decorParams = activity.window.decorView.layoutParams as? WindowManager.LayoutParams
        if (decorParams != null) {
            assertEquals(
                "DecorView LayoutParams must never set WindowManager.LayoutParams.FLAG_SECURE",
                0,
                decorParams.flags and WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        val rootViews = runCatching { WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
        for (rootView in rootViews) {
            val lp = rootView.layoutParams as? WindowManager.LayoutParams ?: continue
            assertEquals(
                "Dialog/sheet/popup sub-window (${lp.title}) must never set FLAG_SECURE",
                0,
                lp.flags and WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    private fun setBuildProfile(
        fingerprint: String,
        model: String,
        product: String,
        hardware: String,
        board: String,
        device: String
    ) {
        setBuildField("FINGERPRINT", fingerprint)
        setBuildField("MODEL", model)
        setBuildField("PRODUCT", product)
        setBuildField("HARDWARE", hardware)
        setBuildField("BOARD", board)
        setBuildField("DEVICE", device)
    }

    private fun getBuildField(fieldName: String): String? =
        runCatching {
            ReflectionHelpers.getStaticField<String>(Build::class.java, fieldName)
        }.getOrNull()

    private fun setBuildField(fieldName: String, value: String) {
        runCatching {
            ReflectionHelpers.setStaticField(Build::class.java, fieldName, value)
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
}
