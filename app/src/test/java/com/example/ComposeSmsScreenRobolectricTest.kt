package com.example

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.sms.ContactLookupCache
import com.example.ui.theme.TraiSmsTheme
import com.example.ui.thread.ComposeSmsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ComposeSmsScreenRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var contactCache: ContactLookupCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val contacts = listOf(
            "Alice Sharma" to "+91 98765 43210",
            "Bob Patel" to "(415) 555-2671",
            "Charlie Singh" to "+91 98201 12233",
            "Deepa Nair" to "+44 7700 900077",
            "Esha Verma" to "+1 202-555-0199"
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

    @Test
    fun `ComposeSmsScreen keeps To field and contacts list and composer visible in Pixel 7 keyboard-open viewport`() {
        composeTestRule.setContent {
            TraiSmsTheme {
                // Exact Pixel 7 portrait remaining height with status bar (~48dp) + soft keyboard (~340dp) open
                Box(modifier = Modifier.size(width = 411.dp, height = 526.dp)) {
                    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { scaffoldPadding ->
                        Box(
                            modifier = Modifier
                                .padding(scaffoldPadding)
                                .consumeWindowInsets(scaffoldPadding)
                        ) {
                            ComposeSmsScreen(
                                initialRecipient = "",
                                initialBody = "",
                                contactCache = contactCache,
                                onBack = {},
                                onSendSms = { _, _, onResult -> onResult(Result.success("thread_1")) }
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Alice Sharma").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("compose_recipient_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compose_contacts_list").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice Sharma").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob Patel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compose_body_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compose_send_button").assertIsDisplayed().assertIsNotEnabled()

        val contactsBounds = composeTestRule.onNodeWithTag("compose_contacts_list").getUnclippedBoundsInRoot()
        val bodyBounds = composeTestRule.onNodeWithTag("compose_body_input").getUnclippedBoundsInRoot()
        val contactsHeight = contactsBounds.bottom - contactsBounds.top

        assertTrue("Contacts LazyColumn collapsed (height=$contactsHeight)", contactsHeight > 120.dp)
        assertTrue(
            "Composer bottomBar overlaps contacts list (contactsBottom=${contactsBounds.bottom}, bodyTop=${bodyBounds.top})",
            contactsBounds.bottom <= bodyBounds.top
        )
    }

    @Test
    fun `ComposeSmsScreen filters formatted contacts by digits and selects contact and clears recipient and sends SMS`() {
        var sentRecipient: String? = null
        var sentBody: String? = null

        composeTestRule.setContent {
            TraiSmsTheme {
                Box(modifier = Modifier.size(width = 411.dp, height = 526.dp)) {
                    ComposeSmsScreen(
                        initialRecipient = "",
                        initialBody = "",
                        contactCache = contactCache,
                        onBack = {},
                        onSendSms = { recipient, body, onResult ->
                            sentRecipient = recipient
                            sentBody = body
                            onResult(Result.success(recipient))
                        }
                    )
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Alice Sharma").fetchSemanticsNodes().isNotEmpty()
        }

        // Typing raw digits matches formatted "+91 98765 43210"
        composeTestRule.onNodeWithTag("compose_recipient_input").performTextInput("9876543")
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Bob Patel").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText("Alice Sharma").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("compose_recipient_input")
            .assertTextContains("+91 98765 43210", substring = true)

        // Clearing recipient restores full contact list
        composeTestRule.onNodeWithTag("compose_recipient_clear").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Bob Patel").fetchSemanticsNodes().isNotEmpty()
        }

        // Entering an unsaved phone number shows direct-send helper and enables Send once body is typed
        composeTestRule.onNodeWithTag("compose_recipient_input").performTextInput("+14155550199")
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("compose_direct_recipient_hint").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("compose_direct_recipient_hint").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compose_send_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("compose_body_input").performTextInput("Hello from TRAISms")
        composeTestRule.onNodeWithTag("compose_send_button").assertIsEnabled().performClick()

        assertEquals("+14155550199", sentRecipient)
        assertEquals("Hello from TRAISms", sentBody)
    }
}
