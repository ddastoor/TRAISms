package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.crypto.BackupMessageRecord
import com.example.data.crypto.BackupSenderRuleRecord
import com.example.data.crypto.EncryptedBackupPayload
import com.example.data.db.SmsCategory
import com.example.domain.classifier.OfflineSmsMlClassifier
import com.example.domain.classifier.RegionProfile
import com.example.domain.classifier.SmsCategorizationPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var pipeline: SmsCategorizationPipeline

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        pipeline = SmsCategorizationPipeline(OfflineSmsMlClassifier(context))
    }

    @Test
    fun `verify TRAISms app_name resource`() {
        assertEquals("TRAISms", context.getString(R.string.app_name))
    }

    @Test
    fun `waterfall categorizes saved contacts as Personal and unsaved raw numbers as Unknown`() {
        val personal = pipeline.categorize(
            rawAddress = "+919820112233",
            body = "Are we meeting for lunch today?",
            isSavedContact = true,
            customRuleCategory = null,
            activeRegion = RegionProfile.IN
        )
        assertEquals(SmsCategory.PERSONAL, personal.category)

        val unknown = pipeline.categorize(
            rawAddress = "+919899001122",
            body = "Hi please call back regarding parcel",
            isSavedContact = false,
            customRuleCategory = null,
            activeRegion = RegionProfile.IN
        )
        assertEquals(SmsCategory.UNKNOWN, unknown.category)
    }

    @Test
    fun `OTP fast-path takes priority over TRAI -T and -S headers while ignoring currency amounts`() {
        val otpOutcome = pipeline.categorize(
            rawAddress = "VM-HDFCBK-T",
            body = "739104 is your One Time Password (OTP) for netbanking txn of INR 4,500.00. Do not share.",
            isSavedContact = false,
            customRuleCategory = null,
            activeRegion = RegionProfile.IN
        )
        assertEquals(SmsCategory.OTP, otpOutcome.category)
        assertEquals("739104", otpOutcome.extractedOtp)
    }

    @Test
    fun `TRAI DLT header suffixes and 127xxx consent route accurately`() {
        assertEquals(
            SmsCategory.TRANSACTIONAL,
            pipeline.categorize("AD-ICICIB-T", "Acct XX1234 debited INR 500.", false, null, RegionProfile.IN).category
        )
        assertEquals(
            SmsCategory.SERVICE,
            pipeline.categorize("AX-AIRTEL-S", "Your data pack is active.", false, null, RegionProfile.IN).category
        )
        assertEquals(
            SmsCategory.PROMOTIONAL,
            pipeline.categorize("JD-MYNTRA-P", "Big fashion sale starts now!", false, null, RegionProfile.IN).category
        )
        assertEquals(
            SmsCategory.GOVERNMENT,
            pipeline.categorize("AX-NDMAEW-G", "Cyclone alert for coastal districts.", false, null, RegionProfile.IN).category
        )
        assertEquals(
            SmsCategory.SERVICE,
            pipeline.categorize("127001", "TRAI DLT consent verification notice.", false, null, RegionProfile.IN).category
        )
    }

    @Test
    fun `local ML model classifies UK US CA messages and routes low-confidence text to Other`() {
        val ukGov = pipeline.categorize(
            rawAddress = "NHS",
            body = "NHS Reminder: Your vaccination appointment at local GP clinic is confirmed.",
            isSavedContact = false,
            customRuleCategory = null,
            activeRegion = RegionProfile.GB
        )
        assertEquals(SmsCategory.GOVERNMENT, ukGov.category)

        val usPromo = pipeline.categorize(
            rawAddress = "65432",
            body = "Flash Sale! Get 40% discount voucher today. Reply STOP to opt out.",
            isSavedContact = false,
            customRuleCategory = null,
            activeRegion = RegionProfile.US
        )
        assertEquals(SmsCategory.PROMOTIONAL, usPromo.category)

        val ambiguous = pipeline.categorize(
            rawAddress = "54321",
            body = "Ping ref 00X9A",
            isSavedContact = false,
            customRuleCategory = null,
            activeRegion = RegionProfile.GLOBAL_EN
        )
        assertEquals(SmsCategory.OTHER, ambiguous.category)
    }

    @Test
    fun `AES-256-GCM PBKDF2 backup encrypts and decrypts cleanly and rejects wrong passphrase`() {
        val payload = EncryptedBackupPayload(
            createdAtMillis = 1700000000000L,
            messages = listOf(
                BackupMessageRecord(
                    threadKey = "VM-HDFCBK-T",
                    rawAddress = "VM-HDFCBK-T",
                    body = "Acct credited INR 1000",
                    bodySha256 = BackupCryptoEngine.sha256Hex("Acct credited INR 1000"),
                    timestampMillis = 1700000000000L,
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
                    updatedAtMillis = 1700000000000L
                )
            )
        )

        val encrypted = BackupCryptoEngine.encryptPayload(payload, "CorrectHorseBatteryStaple!")
        val decrypted = BackupCryptoEngine.decryptPayload(encrypted, "CorrectHorseBatteryStaple!")
        assertEquals(1, decrypted.messages.size)
        assertEquals("VM-HDFCBK-T", decrypted.messages.first().rawAddress)
        assertEquals(1, decrypted.senderRules.size)

        assertThrows(SecurityException::class.java) {
            BackupCryptoEngine.decryptPayload(encrypted, "WrongPassphrase123")
        }
    }

    @Test
    fun `MainActivity launches cleanly without FLAG_SECURE blacking out streaming emulator`() {
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            val hasFlagSecure = (activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
            assertEquals(false, hasFlagSecure)
        }
    }
}
