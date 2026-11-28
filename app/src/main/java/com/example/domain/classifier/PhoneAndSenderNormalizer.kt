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
    private val schemePrefixRegex = Regex("^(?i)(?:tel|sips?|smsto|sms|mmsto|mms):")
    private val mmsTypeSuffixRegex = Regex("(?i)/TYPE=(?:PLMN|IPv4|IPv6).*$")

    /**
     * Strips transport-level URI schemes (`tel:`, `sip:`, `sips:`, `smsto:`),
     * OMA MMS `/TYPE=PLMN` suffixes, URI parameters, and `@ims...` domains on numeric handles.
     */
    fun unwrapTransportAddress(rawAddress: String): String {
        var cleaned = rawAddress.trim()
        if (cleaned.isEmpty()) return ""
        cleaned = schemePrefixRegex.replace(cleaned, "").trim()
        cleaned = mmsTypeSuffixRegex.replace(cleaned, "").trim()
        cleaned = cleaned.substringBefore(';').trim()
        if ('@' in cleaned) {
            val localPart = cleaned.substringBefore('@').trim()
            val localDigits = digitsOnlyRegex.replace(localPart, "")
            if (!letterRegex.containsMatchIn(localPart) && localDigits.length >= 7) {
                return localPart
            }
        }
        return cleaned
    }

    /**
     * Classifies raw SMS originating address into:
     * 1) ALPHANUMERIC_HEADER (e.g., VM-HDFCBK-T, NHS, AMAZON, GOVUK)
     * 2) COMMERCIAL_SHORTCODE (3 to 6 digits, e.g., 56767, 127001, 22395, 7726)
     * 3) FULL_NUMERIC_PHONE (7 to 15+ digits, e.g., +919876543210, +14155552671)
     */
    fun classifySenderType(rawAddress: String): SenderAddressType {
        val unwrapped = unwrapTransportAddress(rawAddress)
        if (unwrapped.isEmpty()) return SenderAddressType.ALPHANUMERIC_HEADER

        if (letterRegex.containsMatchIn(unwrapped)) {
            return SenderAddressType.ALPHANUMERIC_HEADER
        }

        val digits = digitsOnlyRegex.replace(unwrapped, "")
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
        val unwrapped = unwrapTransportAddress(rawAddress)
        if (unwrapped.isEmpty()) return "UNKNOWN_SENDER"

        return when (classifySenderType(unwrapped)) {
            SenderAddressType.ALPHANUMERIC_HEADER -> {
                unwrapped.uppercase(Locale.ROOT).replace(whitespaceCollapseRegex, "-")
            }
            SenderAddressType.COMMERCIAL_SHORTCODE -> {
                digitsOnlyRegex.replace(unwrapped, "")
            }
            SenderAddressType.FULL_NUMERIC_PHONE -> {
                val normalized = runCatching { PhoneNumberUtils.normalizeNumber(unwrapped) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: unwrapped
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
        val unwrapped = unwrapTransportAddress(phone)
        if (unwrapped.isEmpty() || letterRegex.containsMatchIn(unwrapped)) return null
        val digits = digitsOnlyRegex.replace(unwrapped, "")
        if (digits.length < 7) return null
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }
}
