package com.example.data.db

import kotlinx.serialization.Serializable

/**
 * Exhaustive 8-tab SMS categorization taxonomy for TRAISms:
 * Personal, Transactional, Service, OTP, Promotional, Government, Unknown, Other.
 */
@Serializable
enum class SmsCategory(
    val title: String,
    val shortBadge: String,
    val subtitle: String
) {
    PERSONAL(
        title = "Personal",
        shortBadge = "Personal",
        subtitle = "Saved contacts & personal rules"
    ),
    TRANSACTIONAL(
        title = "Transactional",
        shortBadge = "Txn • -T",
        subtitle = "Banking, UPI, cards, billing & TRAI -T"
    ),
    SERVICE(
        title = "Service",
        shortBadge = "Service • -S",
        subtitle = "Telecom, shipping, bookings & TRAI -S"
    ),
    OTP(
        title = "OTP",
        shortBadge = "OTP • 2FA",
        subtitle = "Verification codes & login PINs"
    ),
    PROMOTIONAL(
        title = "Promotional",
        shortBadge = "Promo • -P",
        subtitle = "Offers, marketing & TRAI -P"
    ),
    GOVERNMENT(
        title = "Government",
        shortBadge = "Gov • -G",
        subtitle = "Civic notices, alerts & TRAI -G"
    ),
    UNKNOWN(
        title = "Unknown",
        shortBadge = "Unknown",
        subtitle = "Unsaved raw mobile numbers"
    ),
    OTHER(
        title = "Other",
        shortBadge = "Other",
        subtitle = "Uncategorized messages"
    );

    companion object {
        val orderedTabs: List<SmsCategory> = entries.toList()
    }
}
