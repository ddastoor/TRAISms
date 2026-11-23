package com.example.domain.classifier

import com.example.data.db.SmsCategory
import java.util.Locale

/**
 * Strictly ordered Multi-Layer SMS Categorization Waterfall Engine:
 * Layer 0: User Custom Sender Override (SenderCategoryRuleEntity)
 * Layer 1A: Contact & Raw Mobile Number Check (Personal vs. Unknown)
 * Layer 1B: High-Precision OTP Fast-Path Detector
 * Layer 1C: India TRAI DLT Header Suffix Rules (-P, -S, -T, -G & 127xxx consent shortcodes)
 * Layer 2: Local Bundled ML Model (OfflineSmsMlClassifier)
 * Layer 3: Confidence Threshold (>= 0.60) & Other Fallback
 */
class SmsCategorizationPipeline(
    private val mlClassifier: OfflineSmsMlClassifier
) {

    data class CategorizationOutcome(
        val category: SmsCategory,
        val extractedOtp: String?,
        val confidenceScore: Float,
        val source: String
    )

    // Indian TRAI TCCCPR DLT commercial header pattern: e.g. VM-HDFCBK-T, AD-AIRTEL-S, JD-MYNTRA-P, AX-NDMAEW-G
    private val traiDltHeaderRegex = Regex("(?i)^[A-Z]{2}[-\\s_]?[A-Z0-9]{3,9}[-\\s_]([PSTG])$")
    private val traiConsentShortcodeRegex = Regex("^127\\d{3}$")

    fun categorize(
        rawAddress: String,
        body: String,
        isSavedContact: Boolean,
        customRuleCategory: SmsCategory?,
        activeRegion: RegionProfile
    ): CategorizationOutcome {
        val otpAnalysis = OtpDetector.analyze(body)

        // LAYER 0: User Custom Sender Override
        if (customRuleCategory != null) {
            return CategorizationOutcome(
                category = customRuleCategory,
                extractedOtp = otpAnalysis.extractedCode,
                confidenceScore = 1.0f,
                source = "USER_RULE"
            )
        }

        // LAYER 1A: Contact & Raw Mobile Number Check (Personal vs. Unknown)
        if (isSavedContact) {
            return CategorizationOutcome(
                category = SmsCategory.PERSONAL,
                extractedOtp = otpAnalysis.extractedCode,
                confidenceScore = 1.0f,
                source = "SAVED_CONTACT"
            )
        }

        val senderType = PhoneAndSenderNormalizer.classifySenderType(rawAddress)
        if (senderType == SenderAddressType.FULL_NUMERIC_PHONE) {
            // Unsaved 7+ digit raw mobile/telephone numbers without contact entry or alphanumeric header -> strictly Unknown
            return CategorizationOutcome(
                category = SmsCategory.UNKNOWN,
                extractedOtp = otpAnalysis.extractedCode,
                confidenceScore = 1.0f,
                source = "UNSAVED_NUMBER"
            )
        }

        // LAYER 1B: High-Precision OTP Fast-Path Detector (priority over -T / -S headers)
        if (otpAnalysis.isStrictOtpMessage && otpAnalysis.extractedCode != null) {
            return CategorizationOutcome(
                category = SmsCategory.OTP,
                extractedOtp = otpAnalysis.extractedCode,
                confidenceScore = 0.99f,
                source = "OTP_DETECTOR"
            )
        }

        // LAYER 1C: India TRAI DLT Header Suffix Rules (Applied in IN or whenever sender matches TRAI DLT syntax)
        val trimmedAddress = rawAddress.trim()
        val traiMatch = traiDltHeaderRegex.matchEntire(trimmedAddress)
        if (traiMatch != null) {
            val suffix = traiMatch.groupValues[1].uppercase(Locale.ROOT)
            val category = when (suffix) {
                "P" -> SmsCategory.PROMOTIONAL
                "S" -> SmsCategory.SERVICE
                "T" -> SmsCategory.TRANSACTIONAL
                "G" -> SmsCategory.GOVERNMENT
                else -> SmsCategory.OTHER
            }
            return CategorizationOutcome(
                category = category,
                extractedOtp = otpAnalysis.extractedCode,
                confidenceScore = 0.98f,
                source = "TRAI_DLT_$suffix"
            )
        }

        val digitsOnly = trimmedAddress.filter { it.isDigit() }
        if ((activeRegion == RegionProfile.IN || trimmedAddress.length == 6) &&
            traiConsentShortcodeRegex.matches(digitsOnly)
        ) {
            return CategorizationOutcome(
                category = SmsCategory.SERVICE,
                extractedOtp = otpAnalysis.extractedCode,
                confidenceScore = 0.96f,
                source = "TRAI_127_CONSENT"
            )
        }

        // LAYER 2: Local Bundled ML Model (OfflineSmsMlClassifier)
        val mlResult = mlClassifier.classify(
            senderHeader = trimmedAddress,
            body = body,
            region = activeRegion
        )

        // LAYER 3: Confidence Threshold (>= 0.60) & Other Fallback
        return if (mlResult.confidence >= CONFIDENCE_THRESHOLD) {
            CategorizationOutcome(
                category = mlResult.predictedCategory,
                extractedOtp = otpAnalysis.extractedCode,
                confidenceScore = mlResult.confidence,
                source = mlResult.engineUsed
            )
        } else {
            CategorizationOutcome(
                category = SmsCategory.OTHER,
                extractedOtp = otpAnalysis.extractedCode,
                confidenceScore = mlResult.confidence,
                source = "LOW_CONF_FALLBACK"
            )
        }
    }

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.60f
    }
}
