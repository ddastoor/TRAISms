package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.crypto.EncryptedSettingsStore
import com.example.data.crypto.LeftSwipeAction
import com.example.data.crypto.SmsFontSize
import com.example.data.crypto.TinkAppSettingsSerializer
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.db.ThreadSummary
import com.example.data.sms.SystemSmsHelper
import com.example.ui.common.ThreadRowItem
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.TraiSmsTheme
import com.example.ui.thread.ThreadDetailScreen
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
class SmsTextAppearanceRobolectricTest {

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
        app.container.settingsStore.updateSettings {
            AppSettings(
                onboardingDismissed = true,
                smsFontSize = SmsFontSize.DEFAULT,
                smsFontBold = false
            )
        }
    }

    @After
    fun tearDown() = runBlocking {
        app.container.settingsStore.updateSettings {
            it.copy(
                smsFontSize = SmsFontSize.DEFAULT,
                smsFontBold = false
            )
        }
        tempStorageDirs.forEach { dir -> dir.deleteRecursively() }
        tempStorageDirs.clear()
    }

    @Test
    fun `SettingsScreen renders font size chips bold switch and live preview and updates TextLayoutResult style and store`() = runBlocking {
        val initialSnapshot = app.container.settingsStore.getSettingsSnapshot()
        assertEquals(SmsFontSize.DEFAULT, initialSnapshot.smsFontSize)
        assertFalse(initialSnapshot.smsFontBold)

        var uiSettings by mutableStateOf(initialSnapshot)

        composeTestRule.setContent {
            TraiSmsTheme(
                smsFontSize = uiSettings.smsFontSize,
                smsFontBold = uiSettings.smsFontBold
            ) {
                Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                    SettingsScreen(
                        settings = uiSettings,
                        isDefaultSmsApp = true,
                        detectedRegionLabel = "IN • India",
                        totalMessageCount = 12,
                        systemSmsHelper = systemSmsHelper,
                        onRefreshRoleStatus = {},
                        onUpdatePromoAutoDelete = {},
                        onUpdateLeftSwipeAction = {},
                        onUpdateUnarchiveOnNewSms = {},
                        onUpdateRegionOverride = {},
                        onUpdateBackupSchedule = { _, _ -> },
                        onNavigateToBackupTab = {},
                        onToggleAppLock = {},
                        onToggleBlockScreenshots = {},
                        onRerunCategorization = {},
                        onDeleteAllMessagesInApp = {},
                        onUpdateSmsFontSize = { newSize ->
                            uiSettings = uiSettings.copy(smsFontSize = newSize)
                            runBlocking {
                                app.container.settingsStore.updateSettings {
                                    it.copy(smsFontSize = newSize)
                                }
                            }
                        },
                        onUpdateSmsFontBold = { boldEnabled ->
                            uiSettings = uiSettings.copy(smsFontBold = boldEnabled)
                            runBlocking {
                                app.container.settingsStore.updateSettings {
                                    it.copy(smsFontBold = boldEnabled)
                                }
                            }
                        }
                    )
                }
            }
        }

        // Initial defaults: Default selected, Large & Larger unselected, Bold switch OFF, 15.sp Normal preview
        composeTestRule.onNodeWithText(context.getString(R.string.settings_sms_text_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("sms_font_size_default")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsSelected()
        composeTestRule.onNodeWithTag("sms_font_size_large")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotSelected()
        composeTestRule.onNodeWithTag("sms_font_size_larger")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotSelected()
        composeTestRule.onNodeWithTag("sms_font_bold_switch")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOff()
        composeTestRule.onNodeWithTag("sms_font_live_preview")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("sms_font_preview_text")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(context.getString(R.string.settings_sms_font_preview_sample))

        val initialPreviewStyle = fetchRenderedTextStyle("sms_font_preview_text", useUnmergedTree = false)
        assertEquals(15.sp, initialPreviewStyle.fontSize)
        assertEquals(22.sp, initialPreviewStyle.lineHeight)
        assertEquals(FontWeight.Normal, initialPreviewStyle.fontWeight)

        // Select Large -> 18.sp / 26.sp, FontWeight.Normal
        composeTestRule.onNodeWithTag("sms_font_size_large")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("sms_font_size_large").assertIsSelected()
        composeTestRule.onNodeWithTag("sms_font_size_default").assertIsNotSelected()
        composeTestRule.onNodeWithTag("sms_font_size_larger").assertIsNotSelected()
        composeTestRule.onNodeWithTag("sms_font_preview_text").performScrollTo()

        val largePreviewStyle = fetchRenderedTextStyle("sms_font_preview_text", useUnmergedTree = false)
        assertEquals(18.sp, largePreviewStyle.fontSize)
        assertEquals(26.sp, largePreviewStyle.lineHeight)
        assertEquals(FontWeight.Normal, largePreviewStyle.fontWeight)
        assertEquals(SmsFontSize.LARGE, app.container.settingsStore.getSettingsSnapshot().smsFontSize)

        // Select Larger -> 21.sp / 30.sp, FontWeight.Normal
        composeTestRule.onNodeWithTag("sms_font_size_larger")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("sms_font_size_larger").assertIsSelected()
        composeTestRule.onNodeWithTag("sms_font_size_large").assertIsNotSelected()
        composeTestRule.onNodeWithTag("sms_font_preview_text").performScrollTo()

        val largerPreviewStyle = fetchRenderedTextStyle("sms_font_preview_text", useUnmergedTree = false)
        assertEquals(21.sp, largerPreviewStyle.fontSize)
        assertEquals(30.sp, largerPreviewStyle.lineHeight)
        assertEquals(FontWeight.Normal, largerPreviewStyle.fontWeight)
        assertEquals(SmsFontSize.LARGER, app.container.settingsStore.getSettingsSnapshot().smsFontSize)

        // Toggle Bold switch ON -> 21.sp, FontWeight.Bold
        composeTestRule.onNodeWithTag("sms_font_bold_switch")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("sms_font_bold_switch").assertIsOn()
        composeTestRule.onNodeWithTag("sms_font_preview_text").performScrollTo()

        val largerBoldPreviewStyle = fetchRenderedTextStyle("sms_font_preview_text", useUnmergedTree = false)
        assertEquals(21.sp, largerBoldPreviewStyle.fontSize)
        assertEquals(FontWeight.Bold, largerBoldPreviewStyle.fontWeight)

        val boldSnapshot = app.container.settingsStore.getSettingsSnapshot()
        assertEquals(SmsFontSize.LARGER, boldSnapshot.smsFontSize)
        assertTrue(boldSnapshot.smsFontBold)

        // Select Default while Bold remains ON -> 15.sp, FontWeight.Bold, then toggle Bold OFF -> Normal
        composeTestRule.onNodeWithTag("sms_font_size_default")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("sms_font_size_default").assertIsSelected()
        composeTestRule.onNodeWithTag("sms_font_size_larger").assertIsNotSelected()
        composeTestRule.onNodeWithTag("sms_font_preview_text").performScrollTo()

        val defaultBoldPreviewStyle = fetchRenderedTextStyle("sms_font_preview_text", useUnmergedTree = false)
        assertEquals(15.sp, defaultBoldPreviewStyle.fontSize)
        assertEquals(FontWeight.Bold, defaultBoldPreviewStyle.fontWeight)

        composeTestRule.onNodeWithTag("sms_font_bold_switch")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("sms_font_bold_switch").assertIsOff()
        composeTestRule.onNodeWithTag("sms_font_preview_text").performScrollTo()

        val resetPreviewStyle = fetchRenderedTextStyle("sms_font_preview_text", useUnmergedTree = false)
        assertEquals(15.sp, resetPreviewStyle.fontSize)
        assertEquals(FontWeight.Normal, resetPreviewStyle.fontWeight)

        val resetSnapshot = app.container.settingsStore.getSettingsSnapshot()
        assertEquals(SmsFontSize.DEFAULT, resetSnapshot.smsFontSize)
        assertFalse(resetSnapshot.smsFontBold)
    }

    @Test
    fun `EncryptedSettingsStore persists and restores smsFontSize and smsFontBold across cold reload and defaults legacy snapshots`() = runBlocking {
        val writerDir = createIsolatedDir("settings_writer")
        val writerStore = EncryptedSettingsStore(
            context = buildIsolatedStoreContext(context, writerDir),
            tinkKeyManager = app.container.tinkKeyManager
        )

        writerStore.updateSettings {
            it.copy(
                smsFontSize = SmsFontSize.LARGER,
                smsFontBold = true
            )
        }
        val writtenSnapshot = writerStore.getSettingsSnapshot()
        assertEquals(SmsFontSize.LARGER, writtenSnapshot.smsFontSize)
        assertTrue(writtenSnapshot.smsFontBold)

        val persistedFile = File(writerDir, "traisms_secure_settings.pb")
        assertTrue("Expected encrypted DataStore file on disk", persistedFile.exists() && persistedFile.length() > 0L)
        assertFalse(
            "Ciphertext on disk must not expose plaintext enum values",
            persistedFile.readBytes().decodeToString().contains("LARGER")
        )

        // Copy ciphertext file to a cold directory and instantiate a fresh EncryptedSettingsStore
        val readerDir = createIsolatedDir("settings_reader")
        persistedFile.copyTo(File(readerDir, "traisms_secure_settings.pb"), overwrite = true)

        val coldRestoredStore = EncryptedSettingsStore(
            context = buildIsolatedStoreContext(context, readerDir),
            tinkKeyManager = app.container.tinkKeyManager
        )
        val restoredSnapshot = coldRestoredStore.getSettingsSnapshot()
        assertEquals(SmsFontSize.LARGER, restoredSnapshot.smsFontSize)
        assertTrue(restoredSnapshot.smsFontBold)

        // Verify legacy Tink-encrypted AppSettings payloads without smsFontSize/smsFontBold deserialize cleanly
        val legacyJson = """{"promoAutoDeleteDays":15,"onboardingDismissed":true}""".encodeToByteArray()
        val legacyCiphertext = app.container.tinkKeyManager.getAead().encrypt(
            legacyJson,
            "traisms_encrypted_datastore_v1".toByteArray(Charsets.UTF_8)
        )
        val serializer = TinkAppSettingsSerializer(app.container.tinkKeyManager)
        val migratedSettings = serializer.readFrom(ByteArrayInputStream(legacyCiphertext))
        assertEquals(15, migratedSettings.promoAutoDeleteDays)
        assertTrue(migratedSettings.onboardingDismissed)
        assertEquals(SmsFontSize.DEFAULT, migratedSettings.smsFontSize)
        assertFalse(migratedSettings.smsFontBold)
    }

    @Test
    fun `ThreadDetailScreen scales open SMS bubble text across Default Large and Larger and applies Bold weight`() {
        val threadKey = "+919876543210"
        val messages = listOf(
            buildTestMessage(
                id = 501L,
                threadKey = threadKey,
                rawAddress = threadKey,
                contactName = "Alice Sharma",
                body = "Flight AI-402 boarding starts at Gate 14B at 18:45 hrs.",
                isIncoming = true,
                category = SmsCategory.PERSONAL,
                timestampMillis = 1_700_000_000_000L
            ),
            buildTestMessage(
                id = 502L,
                threadKey = threadKey,
                rawAddress = threadKey,
                contactName = "Alice Sharma",
                body = "Thanks! Walking over to Gate 14B right now.",
                isIncoming = false,
                category = SmsCategory.PERSONAL,
                timestampMillis = 1_700_000_060_000L
            )
        )

        var activeSize by mutableStateOf(SmsFontSize.DEFAULT)
        var activeBold by mutableStateOf(false)

        composeTestRule.setContent {
            TraiSmsTheme(
                smsFontSize = activeSize,
                smsFontBold = activeBold
            ) {
                Box(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                    ThreadDetailScreen(
                        threadKey = threadKey,
                        messages = messages,
                        onBack = {},
                        onMarkThreadRead = {},
                        onSendReply = { _, _, _ -> },
                        onDeleteSingleMessage = {},
                        onToggleArchive = { _, _ -> },
                        onAssignCategory = { _, _, _, _ -> },
                        onForwardMessage = {}
                    )
                }
            }
        }

        // DEFAULT (15.sp / 22.sp) + Normal weight
        val defaultIncoming = fetchRenderedTextStyle("thread_message_text_501", useUnmergedTree = true)
        val defaultOutgoing = fetchRenderedTextStyle("thread_message_text_502", useUnmergedTree = true)
        assertEquals(15.sp, defaultIncoming.fontSize)
        assertEquals(22.sp, defaultIncoming.lineHeight)
        assertEquals(FontWeight.Normal, defaultIncoming.fontWeight)
        assertEquals(15.sp, defaultOutgoing.fontSize)
        assertEquals(FontWeight.Normal, defaultOutgoing.fontWeight)

        // LARGE (18.sp / 26.sp) + Normal weight
        composeTestRule.runOnIdle { activeSize = SmsFontSize.LARGE }
        composeTestRule.waitForIdle()

        val largeIncoming = fetchRenderedTextStyle("thread_message_text_501", useUnmergedTree = true)
        val largeOutgoing = fetchRenderedTextStyle("thread_message_text_502", useUnmergedTree = true)
        assertEquals(18.sp, largeIncoming.fontSize)
        assertEquals(26.sp, largeIncoming.lineHeight)
        assertEquals(FontWeight.Normal, largeIncoming.fontWeight)
        assertEquals(18.sp, largeOutgoing.fontSize)
        assertEquals(FontWeight.Normal, largeOutgoing.fontWeight)

        // LARGER (21.sp / 30.sp) + Normal weight
        composeTestRule.runOnIdle { activeSize = SmsFontSize.LARGER }
        composeTestRule.waitForIdle()

        val largerIncoming = fetchRenderedTextStyle("thread_message_text_501", useUnmergedTree = true)
        val largerOutgoing = fetchRenderedTextStyle("thread_message_text_502", useUnmergedTree = true)
        assertEquals(21.sp, largerIncoming.fontSize)
        assertEquals(30.sp, largerIncoming.lineHeight)
        assertEquals(FontWeight.Normal, largerIncoming.fontWeight)
        assertEquals(21.sp, largerOutgoing.fontSize)
        assertEquals(FontWeight.Normal, largerOutgoing.fontWeight)

        // LARGER (21.sp) + Bold weight
        composeTestRule.runOnIdle { activeBold = true }
        composeTestRule.waitForIdle()

        val largerBoldIncoming = fetchRenderedTextStyle("thread_message_text_501", useUnmergedTree = true)
        val largerBoldOutgoing = fetchRenderedTextStyle("thread_message_text_502", useUnmergedTree = true)
        assertEquals(21.sp, largerBoldIncoming.fontSize)
        assertEquals(FontWeight.Bold, largerBoldIncoming.fontWeight)
        assertEquals(21.sp, largerBoldOutgoing.fontSize)
        assertEquals(FontWeight.Bold, largerBoldOutgoing.fontWeight)

        // DEFAULT (15.sp) + Bold weight -> then Normal weight
        composeTestRule.runOnIdle { activeSize = SmsFontSize.DEFAULT }
        composeTestRule.waitForIdle()

        val defaultBoldIncoming = fetchRenderedTextStyle("thread_message_text_501", useUnmergedTree = true)
        assertEquals(15.sp, defaultBoldIncoming.fontSize)
        assertEquals(FontWeight.Bold, defaultBoldIncoming.fontWeight)

        composeTestRule.runOnIdle { activeBold = false }
        composeTestRule.waitForIdle()

        val defaultNormalIncoming = fetchRenderedTextStyle("thread_message_text_501", useUnmergedTree = true)
        assertEquals(15.sp, defaultNormalIncoming.fontSize)
        assertEquals(FontWeight.Normal, defaultNormalIncoming.fontWeight)
    }

    @Test
    fun `ThreadRowItem scales inbox and archive SMS snippet previews across Default Large and Larger and applies Bold weight`() {
        val inboxReadThread = ThreadSummary(
            latestMessageId = 701L,
            threadKey = "VM-HDFCBK-T",
            rawAddress = "VM-HDFCBK-T",
            contactName = null,
            latestBody = "Acct XX9012 credited with INR 25,000.00 via NEFT.",
            latestTimestampMillis = 1_700_000_000_000L,
            category = SmsCategory.TRANSACTIONAL,
            extractedOtp = null,
            isArchived = false,
            unreadCount = 0,
            messageCount = 3,
            classificationSource = "TEST"
        )
        val archiveUnreadThread = ThreadSummary(
            latestMessageId = 702L,
            threadKey = "AX-AIRTEL-S",
            rawAddress = "AX-AIRTEL-S",
            contactName = null,
            latestBody = "Your monthly broadband statement of INR 999 is ready.",
            latestTimestampMillis = 1_700_000_060_000L,
            category = SmsCategory.SERVICE,
            extractedOtp = null,
            isArchived = true,
            unreadCount = 2,
            messageCount = 1,
            classificationSource = "TEST"
        )

        var activeSize by mutableStateOf(SmsFontSize.DEFAULT)
        var activeBold by mutableStateOf(false)

        composeTestRule.setContent {
            TraiSmsTheme(
                smsFontSize = activeSize,
                smsFontBold = activeBold
            ) {
                Column(modifier = Modifier.size(width = 411.dp, height = 890.dp)) {
                    ThreadRowItem(
                        thread = inboxReadThread,
                        isArchiveScreen = false,
                        leftSwipeAction = LeftSwipeAction.ARCHIVE,
                        showCategoryPill = true,
                        onClick = {},
                        onCopyOtp = {},
                        onSwipeLeftAction = {},
                        onSwipeRightAssignCategory = {}
                    )
                    ThreadRowItem(
                        thread = archiveUnreadThread,
                        isArchiveScreen = true,
                        leftSwipeAction = LeftSwipeAction.ARCHIVE,
                        showCategoryPill = true,
                        onClick = {},
                        onCopyOtp = {},
                        onSwipeLeftAction = {},
                        onSwipeRightAssignCategory = {}
                    )
                }
            }
        }

        // DEFAULT (14.sp / 20.sp): read row uses Normal, unread row uses Medium when smsFontBold = false
        val defaultReadSnippet = fetchRenderedTextStyle("thread_row_body_VM-HDFCBK-T", useUnmergedTree = true)
        val defaultUnreadArchiveSnippet = fetchRenderedTextStyle("thread_row_body_AX-AIRTEL-S", useUnmergedTree = true)
        assertEquals(14.sp, defaultReadSnippet.fontSize)
        assertEquals(20.sp, defaultReadSnippet.lineHeight)
        assertEquals(FontWeight.Normal, defaultReadSnippet.fontWeight)
        assertEquals(14.sp, defaultUnreadArchiveSnippet.fontSize)
        assertEquals(20.sp, defaultUnreadArchiveSnippet.lineHeight)
        assertEquals(FontWeight.Medium, defaultUnreadArchiveSnippet.fontWeight)

        // LARGE (16.5.sp / 23.sp)
        composeTestRule.runOnIdle { activeSize = SmsFontSize.LARGE }
        composeTestRule.waitForIdle()

        val largeReadSnippet = fetchRenderedTextStyle("thread_row_body_VM-HDFCBK-T", useUnmergedTree = true)
        val largeUnreadArchiveSnippet = fetchRenderedTextStyle("thread_row_body_AX-AIRTEL-S", useUnmergedTree = true)
        assertEquals(16.5.sp, largeReadSnippet.fontSize)
        assertEquals(23.sp, largeReadSnippet.lineHeight)
        assertEquals(FontWeight.Normal, largeReadSnippet.fontWeight)
        assertEquals(16.5.sp, largeUnreadArchiveSnippet.fontSize)
        assertEquals(23.sp, largeUnreadArchiveSnippet.lineHeight)
        assertEquals(FontWeight.Medium, largeUnreadArchiveSnippet.fontWeight)

        // LARGER (19.sp / 26.sp)
        composeTestRule.runOnIdle { activeSize = SmsFontSize.LARGER }
        composeTestRule.waitForIdle()

        val largerReadSnippet = fetchRenderedTextStyle("thread_row_body_VM-HDFCBK-T", useUnmergedTree = true)
        val largerUnreadArchiveSnippet = fetchRenderedTextStyle("thread_row_body_AX-AIRTEL-S", useUnmergedTree = true)
        assertEquals(19.sp, largerReadSnippet.fontSize)
        assertEquals(26.sp, largerReadSnippet.lineHeight)
        assertEquals(FontWeight.Normal, largerReadSnippet.fontWeight)
        assertEquals(19.sp, largerUnreadArchiveSnippet.fontSize)
        assertEquals(26.sp, largerUnreadArchiveSnippet.lineHeight)
        assertEquals(FontWeight.Medium, largerUnreadArchiveSnippet.fontWeight)

        // Bold ON: both read inbox snippet and unread archive snippet render at FontWeight.Bold
        composeTestRule.runOnIdle { activeBold = true }
        composeTestRule.waitForIdle()

        val largerBoldReadSnippet = fetchRenderedTextStyle("thread_row_body_VM-HDFCBK-T", useUnmergedTree = true)
        val largerBoldArchiveSnippet = fetchRenderedTextStyle("thread_row_body_AX-AIRTEL-S", useUnmergedTree = true)
        assertEquals(19.sp, largerBoldReadSnippet.fontSize)
        assertEquals(FontWeight.Bold, largerBoldReadSnippet.fontWeight)
        assertEquals(19.sp, largerBoldArchiveSnippet.fontSize)
        assertEquals(FontWeight.Bold, largerBoldArchiveSnippet.fontWeight)

        // DEFAULT (14.sp) + Bold ON
        composeTestRule.runOnIdle { activeSize = SmsFontSize.DEFAULT }
        composeTestRule.waitForIdle()

        val defaultBoldReadSnippet = fetchRenderedTextStyle("thread_row_body_VM-HDFCBK-T", useUnmergedTree = true)
        val defaultBoldArchiveSnippet = fetchRenderedTextStyle("thread_row_body_AX-AIRTEL-S", useUnmergedTree = true)
        assertEquals(14.sp, defaultBoldReadSnippet.fontSize)
        assertEquals(FontWeight.Bold, defaultBoldReadSnippet.fontWeight)
        assertEquals(14.sp, defaultBoldArchiveSnippet.fontSize)
        assertEquals(FontWeight.Bold, defaultBoldArchiveSnippet.fontWeight)
    }

    private fun fetchRenderedTextStyle(
        testTag: String,
        useUnmergedTree: Boolean = true
    ): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        composeTestRule
            .onNodeWithTag(testTag, useUnmergedTree = useUnmergedTree)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(results)
            }
        assertTrue("Expected TextLayoutResult for tag=$testTag", results.isNotEmpty())
        return results.first().layoutInput.style
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

    private fun buildTestMessage(
        id: Long,
        threadKey: String,
        rawAddress: String,
        body: String,
        isIncoming: Boolean,
        category: SmsCategory,
        contactName: String? = null,
        extractedOtp: String? = null,
        timestampMillis: Long = 1_700_000_000_000L
    ): SmsMessageEntity {
        return SmsMessageEntity(
            id = id,
            systemSmsId = null,
            threadKey = threadKey,
            rawAddress = rawAddress,
            contactName = contactName,
            body = body,
            bodySha256 = BackupCryptoEngine.sha256Hex(body),
            timestampMillis = timestampMillis,
            isIncoming = isIncoming,
            isRead = true,
            isArchived = false,
            category = category,
            extractedOtp = extractedOtp,
            confidenceScore = 0.99f,
            classificationSource = "TEST"
        )
    }
}
