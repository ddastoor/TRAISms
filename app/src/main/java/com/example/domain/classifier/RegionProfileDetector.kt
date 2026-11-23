package com.example.domain.classifier

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

enum class RegionProfile(
    val code: String,
    val displayName: String,
    val description: String
) {
    IN("IN", "India (TRAI DLT + UPI)", "TRAI -P/-S/-T/-G headers, 127xxx consent & UPI/IRCTC priors"),
    US("US", "United States", "5-6 digit shortcodes, Zelle/Venmo/IRS/USPS & carrier priors"),
    GB("GB", "United Kingdom", "Alphanumeric headers, NHS/HMRC/DVLA/GOVUK & RoyalMail priors"),
    CA("CA", "Canada", "Canadian shortcodes, Interac/CRA/ServiceCanada priors"),
    EU_EN("EU_EN", "Ireland & Europe (EN)", "SEPA/IBAN, Garda/Revenue/AnPost & EU English priors"),
    GLOBAL_EN("GLOBAL_EN", "Universal English", "Global banking, carrier, OTP, promo & civic ML model");

    companion object {
        private val europeanCountryCodes = setOf(
            "IE", "MT", "DE", "FR", "NL", "ES", "IT", "PT", "BE", "AT",
            "SE", "DK", "FI", "NO", "CH", "PL", "CZ", "GR", "LU", "CY"
        )

        fun fromIsoCountry(isoCode: String?): RegionProfile {
            val upper = isoCode?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return when {
                upper == "IN" -> IN
                upper == "US" -> US
                upper == "GB" || upper == "UK" -> GB
                upper == "CA" -> CA
                upper in europeanCountryCodes || upper == "EU_EN" -> EU_EN
                upper == "GLOBAL_EN" -> GLOBAL_EN
                else -> GLOBAL_EN
            }
        }
    }
}

/**
 * 100% offline country/region detector following the strict chain:
 * TelephonyManager.networkCountryIso -> TelephonyManager.simCountryIso -> Locale.getDefault().country
 */
class RegionProfileDetector(private val context: Context) {

    fun detectActiveRegion(overrideSetting: String): Pair<RegionProfile, String> {
        if (overrideSetting.isNotBlank() && overrideSetting != "AUTO") {
            val profile = RegionProfile.fromIsoCountry(overrideSetting)
            return profile to "Manual (${profile.code})"
        }

        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val networkIso = runCatching { telephony?.networkCountryIso?.trim() }
            .getOrNull()
            ?.takeIf { it.length == 2 }
        if (networkIso != null) {
            val profile = RegionProfile.fromIsoCountry(networkIso)
            return profile to "Network (${networkIso.uppercase(Locale.ROOT)})"
        }

        val simIso = runCatching { telephony?.simCountryIso?.trim() }
            .getOrNull()
            ?.takeIf { it.length == 2 }
        if (simIso != null) {
            val profile = RegionProfile.fromIsoCountry(simIso)
            return profile to "SIM (${simIso.uppercase(Locale.ROOT)})"
        }

        val localeIso = Locale.getDefault().country.trim().takeIf { it.isNotEmpty() } ?: "US"
        val profile = RegionProfile.fromIsoCountry(localeIso)
        return profile to "Locale (${localeIso.uppercase(Locale.ROOT)})"
    }
}
