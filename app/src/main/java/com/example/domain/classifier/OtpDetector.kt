package com.example.domain.classifier

/**
 * Layer 1B High-Precision OTP Fast-Path Detector & Code Extractor (All Countries).
 * Matches 4-to-8-character numeric/alphanumeric verification codes paired with strong OTP
 * context keywords while filtering out currency amounts, dates, and debit/credit ledger numbers.
 */
object OtpDetector {

    private val strongOtpContextRegex = Regex(
        pattern = "(?i)\\b(otp|one[\\s-]time[\\s-]password|verification\\s+code|security\\s+code|" +
            "login\\s+code|auth(?:entication)?\\s+code|passcode|2fa\\s+code|sign[\\s-]in\\s+code|" +
            "verify\\s+your|verification\\s+pin|secret\\s+code|confirm(?:ation)?\\s+code)\\b"
    )

    private val secrecyGuardRegex = Regex(
        pattern = "(?i)(do\\s+not\\s+share|never\\s+share|don't\\s+share|valid\\s+for\\s+\\d+\\s*(?:min|sec))"
    )

    private val pureTransactionExclusionRegex = Regex(
        pattern = "(?i)\\b(debited|credited|withdrawn|deposited|avl\\s*bal|available\\s+balance|statement\\s+for)\\b"
    )

    // Currency prefix directly before a number (e.g., INR 4500, Rs. 500, $1200, £400, €250)
    private val currencyPrefixedTokenRegex = Regex(
        pattern = "(?i)(?:inr|rs\\.?|usd|gbp|eur|cad|\\$|£|€|₹)\\s*[0-9,]+(?:\\.\\d{1,2})?"
    )

    // Candidate 4-to-8 digit numeric codes or hyphenated 3-3 codes or alphanumeric codes with digits
    private val numericCodeCandidateRegex = Regex("\\b(\\d{4,8}|\\d{3}-\\d{3})\\b")
    private val alphaNumCodeRegex = Regex("(?i)(?:code|otp|pin|passcode)\\s*(?:is|:|-)?\\s*([A-Z0-9]{4,8})\\b")

    data class OtpDetectionResult(
        val isStrictOtpMessage: Boolean,
        val extractedCode: String?
    )

    fun analyze(body: String): OtpDetectionResult {
        if (body.isBlank()) return OtpDetectionResult(false, null)

        val hasStrongOtpKeyword = strongOtpContextRegex.containsMatchIn(body)
        val hasSecrecyPhrase = secrecyGuardRegex.containsMatchIn(body)
        val hasPureTxnVerb = pureTransactionExclusionRegex.containsMatchIn(body)

        // If message is purely a debit/credit notification without an explicit OTP keyword, exclude it
        if (!hasStrongOtpKeyword && !(hasSecrecyPhrase && !hasPureTxnVerb)) {
            return OtpDetectionResult(false, null)
        }

        // Scrub currency amounts first so "OTP for Rs. 2500 txn is 849201" never extracts "2500"
        val scrubbedBody = currencyPrefixedTokenRegex.replace(body, " [AMOUNT] ")

        // 1. Check explicit "OTP is XXXX" / "code: XXXX" pattern first for highest precision
        val directMatch = alphaNumCodeRegex.find(scrubbedBody)
        if (directMatch != null) {
            val candidate = directMatch.groupValues[1]
            if (candidate.any { it.isDigit() } && !isLikelyYearOrPhoneFragment(candidate, scrubbedBody)) {
                return OtpDetectionResult(
                    isStrictOtpMessage = true,
                    extractedCode = candidate
                )
            }
        }

        // 2. Scan 4-to-8 digit numeric candidates in scrubbed body
        val candidates = numericCodeCandidateRegex.findAll(scrubbedBody)
            .map { it.groupValues[1].replace("-", "") }
            .filter { it.length in 4..8 && !isLikelyYearOrPhoneFragment(it, scrubbedBody) }
            .toList()

        if (candidates.isNotEmpty()) {
            // Prefer 6-digit codes when multiple numbers appear (e.g., "valid for 10 mins" vs "482910")
            val bestCode = candidates.firstOrNull { it.length == 6 }
                ?: candidates.firstOrNull { it.length in 4..8 }
            if (bestCode != null) {
                return OtpDetectionResult(
                    isStrictOtpMessage = true,
                    extractedCode = bestCode
                )
            }
        }

        return OtpDetectionResult(false, null)
    }

    private fun isLikelyYearOrPhoneFragment(token: String, context: String): Boolean {
        // Avoid treating "ending 1234" or "XX1234" card masks as the OTP when another code exists
        val maskedCardPattern = Regex("(?i)(?:ending|xx+|\\*+)\\s*${Regex.escape(token)}\\b")
        if (maskedCardPattern.containsMatchIn(context)) return true
        return false
    }
}
