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
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.AppSettings
import com.example.data.db.CategoryUnreadCount
import com.example.data.db.SenderCategoryRuleEntity
import com.example.data.db.SmsCategory
import com.example.domain.classifier.PhoneAndSenderNormalizer
import com.example.ui.MainViewModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowRoleManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromotionalAutoReadRobolectricTest {

    private lateinit var context: Context
    private lateinit var app: TraiSmsApp
    private lateinit var notificationManager: NotificationManager
    private lateinit var smsProvider: FakeSmsContentProvider
    private lateinit var contactsProvider: FakeContactsContentProvider

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        app = context as TraiSmsApp
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()

        smsProvider = FakeSmsContentProvider()
        smsProvider.attachInfo(context, ProviderInfo().apply { authority = "sms" })
        ShadowContentResolver.registerProviderInternal("sms", smsProvider)

        contactsProvider = FakeContactsContentProvider()
        contactsProvider.attachInfo(
            context,
            ProviderInfo().apply { authority = ContactsContract.AUTHORITY }
        )
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, contactsProvider)

        Shadows.shadowOf(app as Application).grantPermissions(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        ShadowRoleManager.reset()
        app.container.contactCache.warmCache(forceRefresh = true)

        clearDatabaseAndResetSettings()
    }

    @After
    fun tearDown() = runBlocking {
        notificationManager.cancelAll()
        smsProvider.clear()
        contactsProvider.setContacts(emptyList())
        app.container.contactCache.warmCache(forceRefresh = true)
        ShadowRoleManager.reset()
        clearDatabaseAndResetSettings()
    }

    @Test
    fun `handleIncomingSms auto-marks Promotional SMS as read in Room and Telephony Sms provider across TRAI -P header ML classifier and custom sender rule`() = runBlocking {
        grantDefaultSmsRole()
        assertTrue(app.container.systemSmsHelper.isDefaultSmsApp())

        val smsDao = app.container.database.smsDao()
        val senderRuleDao = app.container.database.senderRuleDao()
        val repository = app.container.smsRepository
        val baseTime = 1_700_100_000_000L

        // Custom rule overriding a -S header sender into SmsCategory.PROMOTIONAL
        val customPromoSender = "AX-CLUBHQ-S"
        val customPromoThreadKey = PhoneAndSenderNormalizer.normalizeThreadKey(customPromoSender)
        senderRuleDao.upsertRule(
            SenderCategoryRuleEntity(
                normalizedSender = customPromoThreadKey,
                displaySender = customPromoSender,
                category = SmsCategory.PROMOTIONAL,
                updatedAtMillis = baseTime
            )
        )

        // 1. TRAI DLT -P header
        repository.handleIncomingSms(
            rawAddress = "JD-MYNTRA-P",
            body = "Big Fashion Festival! Flat 50% off on top footwear brands today.",
            timestampMillis = baseTime + 1_000L
        )

        // 2. Local offline ML promotional classification on shortcode
        repository.handleIncomingSms(
            rawAddress = "65432",
            body = "Flash Sale! Get 50% discount voucher today. Reply STOP to opt out.",
            timestampMillis = baseTime + 2_000L
        )

        // 3. User custom sender rule routed to PROMOTIONAL
        repository.handleIncomingSms(
            rawAddress = customPromoSender,
            body = "Your annual club membership digest is ready to view.",
            timestampMillis = baseTime + 3_000L
        )

        // Categorization ran BEFORE insertIntoSystemProvider: all 3 inserts went directly to
        // content://sms/inbox with READ = 1 and zero post-insert update calls.
        assertEquals(3, smsProvider.recordedInserts.size)
        assertTrue(
            "Expected zero post-insert update calls when READ=1 is passed directly on insert",
            smsProvider.recordedUpdates.isEmpty()
        )
        smsProvider.recordedInserts.forEach { recorded ->
            assertEquals(Telephony.Sms.Inbox.CONTENT_URI, recorded.uri)
            assertEquals(1, recorded.values.getAsInteger(Telephony.Sms.READ))
            assertEquals(1, recorded.values.getAsInteger(Telephony.Sms.SEEN))
            assertEquals(
                Telephony.Sms.MESSAGE_TYPE_INBOX,
                recorded.values.getAsInteger(Telephony.Sms.TYPE)
            )
            assertEquals(1, smsProvider.getReadFlag(recorded.assignedId))
        }

        // Verify Room entities store isRead = true and category = PROMOTIONAL for all 3 paths
        val expectedThreadKeys = listOf("AX-CLUBHQ-S", "65432", "JD-MYNTRA-P")
        expectedThreadKeys.forEach { threadKey ->
            val messages = smsDao.observeThreadMessages(threadKey).first()
            assertEquals(1, messages.size)
            val saved = messages.single()
            assertEquals(SmsCategory.PROMOTIONAL, saved.category)
            assertTrue("Expected isIncoming=true for $threadKey", saved.isIncoming)
            assertTrue("Expected Promotional SMS for $threadKey to be auto-marked read", saved.isRead)
            assertNotNull("Expected systemSmsId when Default SMS role is held", saved.systemSmsId)
        }

        // Verify category badge counts and per-category unread/all thread queries
        val countsByCategory = repository.observeCategoryCounts().first().associateBy { it.category }
        val promoCount = countsByCategory[SmsCategory.PROMOTIONAL]
        assertNotNull(promoCount)
        assertEquals(0, promoCount!!.unreadCount)
        assertEquals(3, promoCount.totalCount)

        assertTrue(
            "observeCategoryThreads(PROMOTIONAL, unreadOnly = true) must be empty when all promos are auto-read",
            repository.observeCategoryThreads(SmsCategory.PROMOTIONAL, unreadOnly = true)
                .first()
                .isEmpty()
        )

        val allPromoThreads = repository.observeCategoryThreads(
            category = SmsCategory.PROMOTIONAL,
            unreadOnly = false
        ).first()
        assertEquals(expectedThreadKeys, allPromoThreads.map { it.threadKey })
        allPromoThreads.forEach { summary ->
            assertEquals(SmsCategory.PROMOTIONAL, summary.category)
            assertEquals(0, summary.unreadCount)
            assertEquals(1, summary.messageCount)
        }
    }

    @Test
    fun `handleIncomingSms keeps all 7 non-Promotional categories unread including OTP on TRAI -P sender and non-Promotional custom rule on -P sender`() = runBlocking {
        grantDefaultSmsRole()
        contactsProvider.setContacts(
            listOf("Alice Sharma" to "+91 98201 12233")
        )
        app.container.contactCache.warmCache(forceRefresh = true)

        val smsDao = app.container.database.smsDao()
        val senderRuleDao = app.container.database.senderRuleDao()
        val repository = app.container.smsRepository
        val baseTime = 1_700_200_000_000L

        data class NonPromoCase(
            val rawAddress: String,
            val body: String,
            val expectedCategory: SmsCategory
        )

        val canonicalSevenNonPromoCases = listOf(
            NonPromoCase(
                rawAddress = "+919820112233",
                body = "Hey! Saw a 50% discount sale at the mall, are we meeting for dinner?",
                expectedCategory = SmsCategory.PERSONAL
            ),
            NonPromoCase(
                rawAddress = "AD-ICICIB-T",
                body = "Acct XX1234 debited for INR 1,250.00 via UPI Ref 409182.",
                expectedCategory = SmsCategory.TRANSACTIONAL
            ),
            NonPromoCase(
                rawAddress = "AX-AIRTEL-S",
                body = "Your daily 2GB high-speed data pack is now active.",
                expectedCategory = SmsCategory.SERVICE
            ),
            NonPromoCase(
                rawAddress = "VM-HDFCBK-T",
                body = "482910 is your One Time Password (OTP) for netbanking login. Do not share.",
                expectedCategory = SmsCategory.OTP
            ),
            NonPromoCase(
                rawAddress = "AX-NDMAEW-G",
                body = "Heavy rainfall advisory issued for coastal districts.",
                expectedCategory = SmsCategory.GOVERNMENT
            ),
            NonPromoCase(
                rawAddress = "+919899001122",
                body = "Hi please call back regarding the courier gate pass.",
                expectedCategory = SmsCategory.UNKNOWN
            ),
            NonPromoCase(
                rawAddress = "54321",
                body = "Ping ref 00X9A",
                expectedCategory = SmsCategory.OTHER
            )
        )

        assertEquals(
            SmsCategory.entries.filter { it != SmsCategory.PROMOTIONAL }.toSet(),
            canonicalSevenNonPromoCases.map { it.expectedCategory }.toSet()
        )

        canonicalSevenNonPromoCases.forEachIndexed { index, testCase ->
            repository.handleIncomingSms(
                rawAddress = testCase.rawAddress,
                body = testCase.body,
                timestampMillis = baseTime + (index + 1) * 1_000L
            )
        }

        // Verify all 7 non-Promotional categories have unreadCount == 1, isRead == false in Room, and READ == 0 in Telephony.Sms
        assertEquals(7, smsProvider.recordedInserts.size)
        smsProvider.recordedInserts.forEach { recorded ->
            assertEquals(
                "Non-Promotional SMS from ${recorded.values.getAsString(Telephony.Sms.ADDRESS)} must insert with READ=0",
                0,
                recorded.values.getAsInteger(Telephony.Sms.READ)
            )
        }

        val countsAfterSeven = repository.observeCategoryCounts().first().associateBy { it.category }
        assertNull(countsAfterSeven[SmsCategory.PROMOTIONAL])

        canonicalSevenNonPromoCases.forEach { testCase ->
            val expectedThreadKey = PhoneAndSenderNormalizer.normalizeThreadKey(testCase.rawAddress)
            val saved = smsDao.observeThreadMessages(expectedThreadKey).first().single()
            assertEquals(testCase.expectedCategory, saved.category)
            assertFalse(
                "Category ${testCase.expectedCategory} message must remain unread (isRead=false)",
                saved.isRead
            )

            val catCount = countsAfterSeven[testCase.expectedCategory]
            assertNotNull("Expected count entry for ${testCase.expectedCategory}", catCount)
            assertEquals(1, catCount!!.unreadCount)
            assertEquals(1, catCount.totalCount)

            val unreadThreads = repository.observeCategoryThreads(
                category = testCase.expectedCategory,
                unreadOnly = true
            ).first()
            assertEquals(listOf(expectedThreadKey), unreadThreads.map { it.threadKey })
            assertEquals(1, unreadThreads.single().unreadCount)
        }

        // Critical edge case A: OTP sent from a TRAI -P promotional header ("JD-SHOP-P") routes via Layer 1B OTP fast-path to OTP and stays UNREAD!
        repository.handleIncomingSms(
            rawAddress = "JD-SHOP-P",
            body = "Your OTP is 839201 for MyShop login. Valid for 10 min. Do not share.",
            timestampMillis = baseTime + 20_000L
        )

        val otpOnPromoHeader = smsDao.observeThreadMessages("JD-SHOP-P").first().single()
        assertEquals(SmsCategory.OTP, otpOnPromoHeader.category)
        assertEquals("839201", otpOnPromoHeader.extractedOtp)
        assertFalse(
            "OTP message on a -P header must NOT be auto-marked read",
            otpOnPromoHeader.isRead
        )
        assertEquals(0, smsProvider.recordedInserts.last().values.getAsInteger(Telephony.Sms.READ))

        // Critical edge case B: -P header overridden by custom user rule to TRANSACTIONAL stays UNREAD!
        senderRuleDao.upsertRule(
            SenderCategoryRuleEntity(
                normalizedSender = "JD-BANKOF-P",
                displaySender = "JD-BANKOF-P",
                category = SmsCategory.TRANSACTIONAL,
                updatedAtMillis = baseTime
            )
        )
        repository.handleIncomingSms(
            rawAddress = "JD-BANKOF-P",
            body = "Flat 20% cashback offer on your debit card statement.",
            timestampMillis = baseTime + 21_000L
        )

        val ruleOverriddenPromoHeader = smsDao.observeThreadMessages("JD-BANKOF-P").first().single()
        assertEquals(SmsCategory.TRANSACTIONAL, ruleOverriddenPromoHeader.category)
        assertFalse(
            "-P sender overridden to TRANSACTIONAL via custom rule must remain unread",
            ruleOverriddenPromoHeader.isRead
        )
        assertEquals(0, smsProvider.recordedInserts.last().values.getAsInteger(Telephony.Sms.READ))

        val finalCounts = repository.observeCategoryCounts().first().associateBy { it.category }
        assertNull(finalCounts[SmsCategory.PROMOTIONAL])
        assertEquals(2, finalCounts[SmsCategory.OTP]?.unreadCount)
        assertEquals(2, finalCounts[SmsCategory.TRANSACTIONAL]?.unreadCount)
        assertTrue(
            repository.observeCategoryThreads(SmsCategory.OTP, unreadOnly = true)
                .first()
                .map { it.threadKey }
                .containsAll(listOf("JD-SHOP-P", "VM-HDFCBK-T"))
        )
    }

    @Test
    fun `handleIncomingSms auto-marks Promotional read in Room even when TRAISms is not Default SMS app`() = runBlocking {
        assertFalse(app.container.systemSmsHelper.isDefaultSmsApp())
        val smsDao = app.container.database.smsDao()
        val repository = app.container.smsRepository
        val baseTime = 1_700_300_000_000L

        repository.handleIncomingSms(
            rawAddress = "JD-AJIOIN-P",
            body = "Season Clearance! Flat 40% off across all styles today.",
            timestampMillis = baseTime + 1_000L
        )
        repository.handleIncomingSms(
            rawAddress = "AD-SBIBNK-T",
            body = "Acct XX4321 credited with INR 5,000.00 via IMPS.",
            timestampMillis = baseTime + 2_000L
        )

        assertTrue(smsProvider.recordedInserts.isEmpty())

        val savedPromo = smsDao.observeThreadMessages("JD-AJIOIN-P").first().single()
        assertEquals(SmsCategory.PROMOTIONAL, savedPromo.category)
        assertNull(savedPromo.systemSmsId)
        assertTrue(savedPromo.isRead)

        val savedTxn = smsDao.observeThreadMessages("AD-SBIBNK-T").first().single()
        assertEquals(SmsCategory.TRANSACTIONAL, savedTxn.category)
        assertNull(savedTxn.systemSmsId)
        assertFalse(savedTxn.isRead)
    }

    @Test
    fun `syncDeviceSmsInChunks preserves historical unread state on initial sync then auto-marks newly synced Promotional SMS read on delta sync`() = runBlocking {
        grantDefaultSmsRole()
        assertTrue(app.container.systemSmsHelper.isDefaultSmsApp())
        assertTrue(app.container.systemSmsHelper.hasReadSmsPermission())

        val smsDao = app.container.database.smsDao()
        val repository = app.container.smsRepository
        val baseTime = 1_700_400_000_000L

        app.container.settingsStore.updateSettings {
            it.copy(initialSyncCompleted = false)
        }

        // Phase 1: Initial historical sync (initialSyncCompleted == false)
        smsProvider.seedExistingMessage(
            id = 7001L,
            address = "JD-MYNTRA-P",
            body = "Historical unread promotional offer 30% off.",
            dateMillis = baseTime + 1_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        smsProvider.seedExistingMessage(
            id = 7002L,
            address = "VK-NYKAA-P",
            body = "Historical already-read promotional beauty sale.",
            dateMillis = baseTime + 2_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 1
        )
        smsProvider.seedExistingMessage(
            id = 7003L,
            address = "AD-ICICIB-T",
            body = "Historical unread bank debit alert for INR 900.00.",
            dateMillis = baseTime + 3_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )

        repository.syncDeviceSmsInChunks()

        assertTrue(app.container.settingsStore.getSettingsSnapshot().initialSyncCompleted)
        assertTrue(
            "Initial historical sync must NOT auto-mark existing unread device promos read in Telephony.Sms",
            smsProvider.recordedUpdates.isEmpty()
        )
        assertEquals(0, smsProvider.getReadFlag(7001L))
        assertFalse(smsDao.observeThreadMessages("JD-MYNTRA-P").first().single().isRead)
        assertTrue(smsDao.observeThreadMessages("VK-NYKAA-P").first().single().isRead)
        assertFalse(smsDao.observeThreadMessages("AD-ICICIB-T").first().single().isRead)

        // Phase 2: Post-initial delta sync (initialSyncCompleted == true)
        smsProvider.seedExistingMessage(
            id = 7004L,
            address = "VM-FLIPKT-P",
            body = "Big Billion Sale! Flat 60% discount coupon. Reply STOP to opt out.",
            dateMillis = baseTime + 10_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        smsProvider.seedExistingMessage(
            id = 7005L,
            address = "65432",
            body = "Flash Sale! Get 50% discount voucher today. Reply STOP to opt out.",
            dateMillis = baseTime + 11_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        smsProvider.seedExistingMessage(
            id = 7006L,
            address = "JD-SHOP-P",
            body = "Your OTP is 948271 for MyShop login. Do not share.",
            dateMillis = baseTime + 12_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        smsProvider.seedExistingMessage(
            id = 7007L,
            address = "AX-AIRTEL-S",
            body = "Your monthly broadband invoice is generated.",
            dateMillis = baseTime + 13_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )
        smsProvider.seedExistingMessage(
            id = 7008L,
            address = "AX-NDMAEW-G",
            body = "Cyclone alert issued by NDMA for coastal areas.",
            dateMillis = baseTime + 14_000L,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = 0
        )

        repository.syncDeviceSmsInChunks()

        // Newly delta-synced Promotional messages (#7004 and #7005) are auto-marked read in Room AND Telephony.Sms
        val deltaTraiPromo = smsDao.observeThreadMessages("VM-FLIPKT-P").first().single()
        assertEquals(SmsCategory.PROMOTIONAL, deltaTraiPromo.category)
        assertTrue("Delta-synced TRAI -P promo must be stored with isRead=true", deltaTraiPromo.isRead)
        assertEquals(1, smsProvider.getReadFlag(7004L))

        val deltaMlPromo = smsDao.observeThreadMessages("65432").first().single()
        assertEquals(SmsCategory.PROMOTIONAL, deltaMlPromo.category)
        assertTrue("Delta-synced ML promo must be stored with isRead=true", deltaMlPromo.isRead)
        assertEquals(1, smsProvider.getReadFlag(7005L))

        assertTrue(
            "Expected content://sms update call marking delta-synced promo IDs read",
            smsProvider.recordedUpdates.isNotEmpty()
        )
        val updatedIds = smsProvider.recordedUpdates
            .flatMap { it.selectionArgs.orEmpty() }
            .mapNotNull { it.toLongOrNull() }
            .toSet()
        assertEquals(setOf(7004L, 7005L), updatedIds)

        // Historical unread promo (#7001) and newly delta-synced non-Promotional messages (#7006..#7008) remain unread
        assertEquals(0, smsProvider.getReadFlag(7001L))
        assertFalse(smsDao.observeThreadMessages("JD-MYNTRA-P").first().single().isRead)

        val deltaOtpOnPromoSender = smsDao.observeThreadMessages("JD-SHOP-P").first().single()
        assertEquals(SmsCategory.OTP, deltaOtpOnPromoSender.category)
        assertFalse("Delta-synced OTP on -P header must remain unread", deltaOtpOnPromoSender.isRead)
        assertEquals(0, smsProvider.getReadFlag(7006L))

        val deltaService = smsDao.observeThreadMessages("AX-AIRTEL-S").first().single()
        assertEquals(SmsCategory.SERVICE, deltaService.category)
        assertFalse("Delta-synced SERVICE SMS must remain unread", deltaService.isRead)
        assertEquals(0, smsProvider.getReadFlag(7007L))

        val deltaGov = smsDao.observeThreadMessages("AX-NDMAEW-G").first().single()
        assertEquals(SmsCategory.GOVERNMENT, deltaGov.category)
        assertFalse("Delta-synced GOVERNMENT SMS must remain unread", deltaGov.isRead)
        assertEquals(0, smsProvider.getReadFlag(7008L))
    }

    @Test
    fun `MainViewModel keeps Promotional tab unread-empty by default on incoming promo SMS and reveals auto-read promos when Show Unread is toggled off`() = runBlocking {
        val repository = app.container.smsRepository
        val baseTime = 1_700_500_000_000L

        val viewModel = MainViewModel(app.container)
        val unreadSub = launch(Dispatchers.Unconfined) {
            viewModel.currentCategoryUnreadOnly.collect {}
        }
        val threadsSub = launch(Dispatchers.Unconfined) {
            viewModel.activeThreads.collect {}
        }
        val countsSub = launch(Dispatchers.Unconfined) {
            viewModel.categoryCounts.collect {}
        }

        try {
            repository.handleIncomingSms(
                rawAddress = "JD-SWIGGY-P",
                body = "Weekend Feast! Flat 50% discount on top restaurants tonight.",
                timestampMillis = baseTime + 1_000L
            )
            repository.handleIncomingSms(
                rawAddress = "AD-HDFCBK-T",
                body = "Acct XX9012 debited for INR 750.00 via UPI.",
                timestampMillis = baseTime + 2_000L
            )

            viewModel.selectCategory(SmsCategory.PROMOTIONAL)
            waitForCondition {
                viewModel.selectedCategory.value == SmsCategory.PROMOTIONAL &&
                    viewModel.currentCategoryUnreadOnly.value &&
                    viewModel.categoryCounts.value[SmsCategory.PROMOTIONAL] == CategoryUnreadCount(
                        category = SmsCategory.PROMOTIONAL,
                        unreadCount = 0,
                        totalCount = 1
                    ) &&
                    viewModel.activeThreads.value.isEmpty()
            }

            // Toggling Promotional Show Unread OFF reveals the auto-read promotional thread
            viewModel.updateCategoryUnreadOnly(SmsCategory.PROMOTIONAL, false)
            waitForCondition {
                !viewModel.currentCategoryUnreadOnly.value &&
                    viewModel.activeThreads.value.map { it.threadKey } == listOf("JD-SWIGGY-P")
            }
            assertEquals(0, viewModel.activeThreads.value.single().unreadCount)
            assertEquals(1, viewModel.activeThreads.value.single().messageCount)

            // Switching to TRANSACTIONAL still shows unread non-Promotional messages under default Unread ON
            viewModel.selectCategory(SmsCategory.TRANSACTIONAL)
            waitForCondition {
                viewModel.selectedCategory.value == SmsCategory.TRANSACTIONAL &&
                    viewModel.currentCategoryUnreadOnly.value &&
                    viewModel.activeThreads.value.map { it.threadKey } == listOf("AD-HDFCBK-T")
            }
            assertEquals(1, viewModel.activeThreads.value.single().unreadCount)
        } finally {
            unreadSub.cancel()
            threadsSub.cancel()
            countsSub.cancel()
        }
    }

    private fun grantDefaultSmsRole() {
        ShadowRoleManager.addRoleHolder(
            RoleManager.ROLE_SMS,
            context.packageName,
            Process.myUserHandle()
        )
    }

    private suspend fun clearDatabaseAndResetSettings() {
        app.container.database.smsDao().deleteAllMessages()
        val ruleDao = app.container.database.senderRuleDao()
        ruleDao.getAllRules().forEach { rule ->
            ruleDao.deleteRule(rule.normalizedSender)
        }
        app.container.settingsStore.updateSettings {
            AppSettings(
                regionOverride = "IN",
                initialSyncCompleted = true,
                onboardingDismissed = true,
                categoryShowUnreadOnly = emptyMap()
            )
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

        data class RecordedInsert(
            val assignedId: Long,
            val uri: Uri,
            val values: ContentValues
        )

        data class RecordedUpdate(
            val uri: Uri,
            val values: ContentValues,
            val selection: String?,
            val selectionArgs: List<String>?
        )

        private val nextId = AtomicLong(5_000L)
        private val rowsById = ConcurrentHashMap<Long, SmsRow>()
        val recordedInserts = CopyOnWriteArrayList<RecordedInsert>()
        val recordedUpdates = CopyOnWriteArrayList<RecordedUpdate>()

        override fun onCreate(): Boolean = true

        fun clear() {
            rowsById.clear()
            recordedInserts.clear()
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
            val sortedRows = rowsById.values.sortedByDescending { it.dateMillis }
            for (row in sortedRows) {
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
            recordedInserts.add(
                RecordedInsert(
                    assignedId = id,
                    uri = uri,
                    values = safeValues
                )
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
            recordedUpdates.add(
                RecordedUpdate(
                    uri = uri,
                    values = safeValues,
                    selection = selection,
                    selectionArgs = selectionArgs?.toList()
                )
            )
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

    private class FakeContactsContentProvider : ContentProvider() {

        @Volatile
        private var contacts: List<Pair<String, String>> = emptyList()

        override fun onCreate(): Boolean = true

        fun setContacts(newContacts: List<Pair<String, String>>) {
            contacts = newContacts.toList()
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val isPhoneLookup = uri.toString().startsWith(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.toString()
            )
            if (isPhoneLookup) {
                val cursor = MatrixCursor(arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME))
                val filterPhone = Uri.decode(uri.lastPathSegment.orEmpty())
                val filterKey = PhoneAndSenderNormalizer.contactMatchKey(filterPhone)
                if (filterKey != null) {
                    contacts.firstOrNull { (_, phone) ->
                        PhoneAndSenderNormalizer.contactMatchKey(phone) == filterKey
                    }?.let { (displayName, _) ->
                        cursor.addRow(arrayOf<Any?>(displayName))
                    }
                }
                return cursor
            }

            val cursor = MatrixCursor(
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
            )
            contacts.forEach { (displayName, number) ->
                cursor.addRow(arrayOf<Any?>(displayName, number))
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }
}
