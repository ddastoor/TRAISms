package com.example

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.sms.ContactLookupCache
import com.example.ui.theme.TraiSmsTheme
import com.example.ui.thread.ComposeSmsScreen
import com.example.ui.thread.ThreadDetailScreen
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ThreadDetailMessageActionsRobolectricTest {

    @get:Rule(order = 0)
    val snapshotSyncRule: TestRule = createComposeSnapshotSyncRule()

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var contactCache: ContactLookupCache

    @Before
    fun setUp(): Unit {
        context = ApplicationProvider.getApplicationContext()
        clipboardManager = context.getSystemService(ClipboardManager::class.java)
        clipboardManager.clearPrimaryClip()

        val contacts = listOf(
            "Alice Sharma" to "+91 98765 43210",
            "Bob Patel" to "(415) 555-2671",
            "Charlie Singh" to "+91 98201 12233"
        )
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): Cursor {
                val cursor = MatrixCursor(
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )
                )
                contacts.forEach { (name, number) -> cursor.addRow(arrayOf(name, number)) }
                return cursor
            }

            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)

        contactCache = ContactLookupCache(context)
    }

    @After
    fun tearDown(): Unit {
        clipboardManager.clearPrimaryClip()
    }

    @Test
    fun `long pressing SMS bubble displays strictly Copy and Forward options and Copy copies full body and dismisses prompt`(): Unit {
        val threadKey = "+919876543210"
        val fullBody = "Flight AI-402 boarding starts at Gate 14B at 18:45 hrs. Booking PNR: K9M2Q."
        val messages = listOf(
            buildTestMessage(
                id = 101L,
                threadKey = threadKey,
                rawAddress = threadKey,
                contactName = "Alice Sharma",
                body = fullBody,
                isIncoming = true,
                category = SmsCategory.PERSONAL
            ),
            buildTestMessage(
                id = 102L,
                threadKey = threadKey,
                rawAddress = threadKey,
                contactName = "Alice Sharma",
                body = "Thanks! On my way to Terminal 3 now.",
                isIncoming = false,
                category = SmsCategory.PERSONAL,
                timestampMillis = 1_700_000_060_000L
            )
        )

        composeTestRule.setContent {
            TraiSmsTheme {
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

        composeTestRule.onAllNodesWithTag("message_actions_prompt").assertCountEquals(0)
        composeTestRule.onNodeWithTag("thread_message_bubble_101")
            .assertIsDisplayed()
            .performTouchInput { longClick() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("message_actions_prompt")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("message_actions_prompt").assertIsDisplayed()
        composeTestRule.onNodeWithTag("message_action_copy")
            .assertIsDisplayed()
            .assertTextContains("Copy")
        composeTestRule.onNodeWithTag("message_action_forward")
            .assertIsDisplayed()
            .assertTextContains("Forward")

        val promptNode = composeTestRule.onNodeWithTag("message_actions_prompt").fetchSemanticsNode()
        val actionOptionNodes = collectClickableTextNodes(promptNode)
        assertEquals(2, actionOptionNodes.size)
        assertTrue(
            actionOptionNodes[0].config.getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .any { it.text == "Copy" }
        )
        assertTrue(
            actionOptionNodes[1].config.getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .any { it.text == "Forward" }
        )

        composeTestRule.onNodeWithTag("message_action_copy").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("message_actions_prompt")
                .fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onAllNodesWithTag("message_actions_prompt").assertCountEquals(0)

        val clip = clipboardManager.primaryClip
        assertNotNull(clip)
        assertEquals(fullBody, clip!!.getItemAt(0).text.toString())

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Message copied to clipboard")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Message copied to clipboard").assertIsDisplayed()
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `long pressing SMS bubble and tapping Forward dismisses prompt invokes callback and pre-populates ComposeSmsScreen`(): Unit {
        val threadKey = "VM-HDFCBK-T"
        val forwardedTargetBody = "Acct XX9012 credited with INR 25,000.00 on 24-Nov via NEFT Ref HDFC0091827."
        val messages = listOf(
            buildTestMessage(
                id = 201L,
                threadKey = threadKey,
                rawAddress = threadKey,
                body = forwardedTargetBody,
                isIncoming = true,
                category = SmsCategory.TRANSACTIONAL
            )
        )

        val forwardedPayloads = mutableListOf<String>()
        var composeInitialBody by mutableStateOf<String?>(null)
        var sentRecipient: String? = null
        var sentBody: String? = null

        composeTestRule.setContent {
            TraiSmsTheme {
                Box(modifier = Modifier.size(width = 411.dp, height = 526.dp)) {
                    val bodyForCompose = composeInitialBody
                    if (bodyForCompose == null) {
                        ThreadDetailScreen(
                            threadKey = threadKey,
                            messages = messages,
                            onBack = {},
                            onMarkThreadRead = {},
                            onSendReply = { _, _, _ -> },
                            onDeleteSingleMessage = {},
                            onToggleArchive = { _, _ -> },
                            onAssignCategory = { _, _, _, _ -> },
                            onForwardMessage = { body ->
                                forwardedPayloads.add(body)
                            }
                        )
                    } else {
                        ComposeSmsScreen(
                            initialRecipient = "",
                            initialBody = bodyForCompose,
                            contactCache = contactCache,
                            onBack = { composeInitialBody = null },
                            onSendSms = { recipient, body, onResult ->
                                sentRecipient = recipient
                                sentBody = body
                                onResult(Result.success(recipient))
                            }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("thread_message_bubble_201")
            .assertIsDisplayed()
            .performTouchInput { longClick() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("message_actions_prompt")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("message_action_forward")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("message_actions_prompt")
                .fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onAllNodesWithTag("message_actions_prompt").assertCountEquals(0)
        assertEquals(listOf(forwardedTargetBody), forwardedPayloads)

        // Transition to ComposeSmsScreen(initialRecipient = "", initialBody = forwardedBody, ...)
        composeTestRule.runOnIdle {
            composeInitialBody = forwardedPayloads.single()
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Alice Sharma").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("compose_contacts_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compose_body_input")
            .assertIsDisplayed()
            .assertTextContains(forwardedTargetBody)
        assertEquals(
            forwardedTargetBody,
            composeTestRule.onNodeWithTag("compose_body_input")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
        )
        composeTestRule.onNodeWithTag("compose_send_button")
            .assertIsDisplayed()
            .assertIsNotEnabled()

        // Selecting a contact enables Send and forwards the exact pre-populated SMS body
        composeTestRule.onNodeWithText("Alice Sharma").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("compose_send_button")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals("+91 98765 43210", sentRecipient)
        assertEquals(forwardedTargetBody, sentBody)
    }

    @Test
    fun `OTP bubble single-tap copies extracted OTP code while long-press opens prompt to copy or forward full SMS`(): Unit {
        val threadKey = "VM-HDFCBK-T"
        val otpCode = "739104"
        val fullOtpBody = buildString {
            appendLine("HDFC Bank Security Alert:")
            appendLine("739104 is your One Time Password (OTP) for netbanking login.")
            appendLine("Transaction Amount: INR 14,500.00")
            appendLine("Valid for 10 minutes only.")
            append("Do not share this OTP with anyone.")
        }
        val messages = listOf(
            buildTestMessage(
                id = 301L,
                threadKey = threadKey,
                rawAddress = threadKey,
                body = fullOtpBody,
                isIncoming = true,
                category = SmsCategory.OTP,
                extractedOtp = otpCode
            )
        )
        val forwardedBodies = mutableListOf<String>()

        composeTestRule.setContent {
            TraiSmsTheme {
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
                        onForwardMessage = { body -> forwardedBodies.add(body) }
                    )
                }
            }
        }

        // 1. Single-tap on the inline OTP chip copies strictly the extracted OTP code
        composeTestRule.onNodeWithTag("bubble_copy_otp_301")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("message_actions_prompt").assertCountEquals(0)
        val otpClip = clipboardManager.primaryClip
        assertNotNull(otpClip)
        assertEquals(otpCode, otpClip!!.getItemAt(0).text.toString())

        // 2. Long-press on the OTP message bubble opens message_actions_prompt to copy full SMS text
        composeTestRule.onNodeWithTag("thread_message_bubble_301")
            .assertIsDisplayed()
            .performTouchInput { longClick() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("message_actions_prompt")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("message_action_copy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("message_action_forward").assertIsDisplayed()
        composeTestRule.onNodeWithTag("message_action_copy").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("message_actions_prompt")
                .fetchSemanticsNodes().isEmpty()
        }
        val fullMessageClip = clipboardManager.primaryClip
        assertNotNull(fullMessageClip)
        assertEquals(fullOtpBody, fullMessageClip!!.getItemAt(0).text.toString())

        // 3. Long-press on the OTP message bubble also supports forwarding the full SMS text
        composeTestRule.onNodeWithTag("thread_message_bubble_301")
            .performTouchInput { longClick() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("message_actions_prompt")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("message_action_forward").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("message_actions_prompt")
                .fetchSemanticsNodes().isEmpty()
        }
        assertEquals(listOf(fullOtpBody), forwardedBodies)
        composeTestRule.mainClock.advanceTimeBy(5_000)
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

    private fun collectClickableTextNodes(node: SemanticsNode): List<SemanticsNode> {
        val matches = mutableListOf<SemanticsNode>()
        if (SemanticsActions.OnClick in node.config && SemanticsProperties.Text in node.config) {
            matches.add(node)
        } else {
            node.children.forEach { child ->
                matches.addAll(collectClickableTextNodes(child))
            }
        }
        return matches
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
