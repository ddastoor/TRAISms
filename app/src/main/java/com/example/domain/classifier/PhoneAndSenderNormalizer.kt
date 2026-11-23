package com.example.domain.classifier

import android.telephony.PhoneNumberUtils
import java.util.Locale

enum class SenderAddressType {
    ALPHANUMERIC_HEADER,
    COMMERCIAL_SHORTCODE,
    FULL_NUMERIC_PHONE
}

object PhoneAndSenderNormalizer {

    private val letterRegex = Regex("[A-Za-z]")
    private val digitsOnlyRegex = Regex("[^0-9]")
    private val whitespaceCollapseRegex = Regex("\\s+")

    /**
     * Classifies raw SMS originating address into:
     * 1) ALPHANUMERIC_HEADER (e.g., VM-HDFCBK-T, NHS, AMAZON, GOVUK)
     * 2) COMMERCIAL_SHORTCODE (3 to 6 digits, e.g., 56767, 127001, 22395, 7726)
     * 3) FULL_NUMERIC_PHONE (7 to 15+ digits, e.g., +919876543210, +14155552671)
     */
    fun classifySenderType(rawAddress: String): SenderAddressType {
        val trimmed = rawAddress.trim()
        if (trimmed.isEmpty()) return SenderAddressType.ALPHANUMERIC_HEADER

        if (letterRegex.containsMatchIn(trimmed)) {
            return SenderAddressType.ALPHANUMERIC_HEADER
        }

        val digits = digitsOnlyRegex.replace(trimmed, "")
        return when {
            digits.length in 3..6 -> SenderAddressType.COMMERCIAL_SHORTCODE
            digits.length >= 7 -> SenderAddressType.FULL_NUMERIC_PHONE
            else -> SenderAddressType.COMMERCIAL_SHORTCODE
        }
    }

    /**
     * Produces a canonical thread/sender key so messages from the same phone number
     * or same TRAI DLT core header group cleanly.
     */
    fun normalizeThreadKey(rawAddress: String): String {
        val trimmed = rawAddress.trim()
        if (trimmed.isEmpty()) return "UNKNOWN_SENDER"

        return when (classifySenderType(trimmed)) {
            SenderAddressType.ALPHANUMERIC_HEADER -> {
                trimmed.uppercase(Locale.ROOT).replace(whitespaceCollapseRegex, "-")
            }
            SenderAddressType.COMMERCIAL_SHORTCODE -> {
                digitsOnlyRegex.replace(trimmed, "")
            }
            SenderAddressType.FULL_NUMERIC_PHONE -> {
                val normalized = runCatching { PhoneNumberUtils.normalizeNumber(trimmed) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: trimmed
                val digits = digitsOnlyRegex.replace(normalized, "")
                // Match last 10 digits for consistent domestic/+country-code grouping when >= 10 digits
                if (digits.length >= 10) {
                    "NUM_${digits.takeLast(10)}"
                } else {
                    "NUM_$digits"
                }
            }
        }
    }

    /**
     * Extracts normalized lookup key for fast O(1) Contact phone matching.
     */
    fun contactMatchKey(phone: String): String? {
        if (letterRegex.containsMatchIn(phone)) return null
        val digits = digitsOnlyRegex.replace(phone, "")
        if (digits.length < 7) return null
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }
}
