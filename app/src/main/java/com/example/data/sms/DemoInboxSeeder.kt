package com.example.data.sms

import com.example.data.crypto.BackupCryptoEngine
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.domain.classifier.PhoneAndSenderNormalizer
import com.example.domain.classifier.RegionProfile
import com.example.domain.classifier.SmsCategorizationPipeline

/**
 * Provides a realistic multi-category sample inbox strictly for testing on an Android Emulator
 * when the device has 0 existing SMS messages.
 */
object DemoInboxSeeder {

    private data class RawSeed(
        val sender: String,
        val contactName: String?,
        val body: String,
        val minutesAgo: Long,
        val isRead: Boolean = false
    )

    fun buildDemoMessages(
        pipeline: SmsCategorizationPipeline,
        region: RegionProfile
    ): List<SmsMessageEntity> {
        val now = System.currentTimeMillis()
        val seeds = listOf(
            // 1. Personal (Saved Contacts)
            RawSeed(
                sender = "+919820114477",
                contactName = "Aarav Mehta",
                body = "Hey! Are we still meeting at Blue Tokai around 6:30 PM today? Let me know when you leave.",
                minutesAgo = 8,
                isRead = false
            ),
            RawSeed(
                sender = "+14155550148",
                contactName = "Maya Lin",
                body = "Landed safely! Picking up luggage now, see you at home in 30 mins.",
                minutesAgo = 52,
                isRead = true
            ),
            // 2. OTP (Priority extraction even on -T / -S headers & international senders)
            RawSeed(
                sender = "VM-HDFCBK-T",
                contactName = null,
                body = "849206 is your SECRET One Time Password (OTP) for netbanking login of INR 12,500.00. Do not share this code with anyone.",
                minutesAgo = 3,
                isRead = false
            ),
            RawSeed(
                sender = "22395",
                contactName = null,
                body = "Your verification code is 591034. Valid for 10 mins. Never share this security code.",
                minutesAgo = 19,
                isRead = false
            ),
            // 3. Transactional (TRAI -T + Banking / UPI)
            RawSeed(
                sender = "AD-ICICIB-T",
                contactName = null,
                body = "Acct XX4091 debited for INR 1,480.00 on 23-Nov via UPI Ref 63281904412. Avl Bal: INR 84,210.50.",
                minutesAgo = 27,
                isRead = false
            ),
            RawSeed(
                sender = "CHASE",
                contactName = null,
                body = "Chase Alert: Direct deposit of $3,420.00 credited to checking account ending 8821.",
                minutesAgo = 140,
                isRead = true
            ),
            // 4. Service (TRAI -S + 127xxx Consent + Delivery/Travel)
            RawSeed(
                sender = "AX-IRCTCi-S",
                contactName = null,
                body = "PNR 2841905532 confirmed for Train 12951 Seat B2-34. Boarding at 17:00 hrs. Carry valid ID.",
                minutesAgo = 45,
                isRead = false
            ),
            RawSeed(
                sender = "127001",
                contactName = null,
                body = "TRAI DLT Digital Consent Advisory: Review or manage your brand communication preferences anytime.",
                minutesAgo = 310,
                isRead = true
            ),
            RawSeed(
                sender = "ROYALMAIL",
                contactName = null,
                body = "Your tracked parcel #RM948201GB has been dispatched and is scheduled for delivery tomorrow between 09:30-11:30.",
                minutesAgo = 420,
                isRead = true
            ),
            // 5. Promotional (TRAI -P + Opt-out offers)
            RawSeed(
                sender = "JD-MYNTRA-P",
                contactName = null,
                body = "Weekend Clearance Sale! Get flat 50% OFF + extra 15% cashback on top styles. Use coupon FESTIVE50 today!",
                minutesAgo = 64,
                isRead = false
            ),
            RawSeed(
                sender = "68421",
                contactName = null,
                body = "Exclusive VIP offer: Claim your $25 bonus voucher on orders above $75! Hurry, limited time deal. Reply STOP to opt out.",
                minutesAgo = 780,
                isRead = true
            ),
            // 6. Government (TRAI -G + Official Civic Agencies)
            RawSeed(
                sender = "AX-NDMAEW-G",
                contactName = null,
                body = "Official Weather Advisory: Heavy coastal rainfall expected over next 24 hours. Follow municipal safety guidelines.",
                minutesAgo = 95,
                isRead = false
            ),
            RawSeed(
                sender = "GOVUK",
                contactName = null,
                body = "DVLA Official Reminder: Vehicle tax renewal for registration AB24 XYZ is due next month at gov.uk/vehicle-tax.",
                minutesAgo = 1100,
                isRead = true
            ),
            // 7. Unknown (Unsaved raw 10-15 digit phone numbers without contact entry)
            RawSeed(
                sender = "+919900887766",
                contactName = null,
                body = "Hi, I am standing near Gate 2 with the documents you requested. Please let security know.",
                minutesAgo = 38,
                isRead = false
            ),
            // 8. Other (Ambiguous shortcode note below ML confidence threshold)
            RawSeed(
                sender = "54321",
                contactName = null,
                body = "System reference ping 99B-ALPHA recorded.",
                minutesAgo = 1520,
                isRead = true
            )
        )

        return seeds.map { seed ->
            val threadKey = PhoneAndSenderNormalizer.normalizeThreadKey(seed.sender)
            val outcome = pipeline.categorize(
                rawAddress = seed.sender,
                body = seed.body,
                isSavedContact = seed.contactName != null,
                customRuleCategory = null,
                activeRegion = region
            )
            val ts = now - (seed.minutesAgo * 60_000L)
            SmsMessageEntity(
                systemSmsId = null,
                threadKey = threadKey,
                rawAddress = seed.sender,
                contactName = seed.contactName,
                body = seed.body,
                bodySha256 = BackupCryptoEngine.sha256Hex(seed.body),
                timestampMillis = ts,
                isIncoming = true,
                isRead = seed.isRead,
                isArchived = false,
                category = outcome.category,
                extractedOtp = outcome.extractedOtp,
                confidenceScore = outcome.confidenceScore,
                classificationSource = outcome.source
            )
        }
    }
}
