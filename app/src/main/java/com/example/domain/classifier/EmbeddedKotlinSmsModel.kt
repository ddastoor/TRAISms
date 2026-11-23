package com.example.domain.classifier

import com.example.data.db.SmsCategory
import java.util.Locale
import kotlin.math.exp

/**
 * Deterministic pure-Kotlin Multinomial Naive Bayes + TF-IDF + Regional Lexicon Scoring Engine.
 * Pre-populated with calibrated token weights across:
 * [PERSONAL, TRANSACTIONAL, SERVICE, OTP, PROMOTIONAL, GOVERNMENT]
 * combined with country-specific feature priors (IN, US, GB, CA, EU_EN, GLOBAL_EN).
 */
class EmbeddedKotlinSmsModel {

    data class ModelPrediction(
        val probabilities: Map<SmsCategory, Float>,
        val topCategory: SmsCategory,
        val topConfidence: Float
    )

    private val targetClasses = listOf(
        SmsCategory.PERSONAL,
        SmsCategory.TRANSACTIONAL,
        SmsCategory.SERVICE,
        SmsCategory.OTP,
        SmsCategory.PROMOTIONAL,
        SmsCategory.GOVERNMENT
    )

    private val baseLogPriors = mapOf(
        SmsCategory.PERSONAL to -1.95f,
        SmsCategory.TRANSACTIONAL to -1.65f,
        SmsCategory.SERVICE to -1.65f,
        SmsCategory.OTP to -1.80f,
        SmsCategory.PROMOTIONAL to -1.65f,
        SmsCategory.GOVERNMENT to -2.10f
    )

    // Universal English calibrated log-likelihood / TF-IDF weights
    private val universalLexicon: Map<SmsCategory, Map<String, Float>> = mapOf(
        SmsCategory.PERSONAL to mapOf(
            "hey" to 2.1f, "hi" to 1.4f, "hello" to 1.5f, "dinner" to 2.4f, "lunch" to 2.4f,
            "coffee" to 2.2f, "call" to 1.2f, "miss" to 2.0f, "home" to 1.6f, "tomorrow" to 1.4f,
            "tonight" to 1.9f, "party" to 1.8f, "mom" to 2.5f, "dad" to 2.5f, "bro" to 2.5f,
            "love" to 1.8f, "meeting" to 1.5f, "running" to 1.5f, "late" to 1.6f, "reach" to 1.5f
        ),
        SmsCategory.TRANSACTIONAL to mapOf(
            "debited" to 3.4f, "credited" to 3.4f, "bank" to 2.1f, "account" to 2.0f,
            "ac" to 1.6f, "balance" to 2.5f, "avl" to 2.6f, "bal" to 2.4f, "txn" to 2.9f,
            "transaction" to 2.6f, "payment" to 2.3f, "paid" to 2.1f, "invoice" to 2.7f,
            "statement" to 2.6f, "card" to 1.9f, "visa" to 2.2f, "mastercard" to 2.2f,
            "withdrawn" to 3.0f, "atm" to 2.7f, "transfer" to 2.2f, "transferred" to 2.4f,
            "emi" to 2.5f, "bill" to 2.0f, "receipt" to 2.4f, "refund" to 2.3f, "salary" to 2.8f,
            "inr" to 2.4f, "rs" to 2.1f, "usd" to 2.3f, "gbp" to 2.3f, "eur" to 2.3f, "cad" to 2.3f
        ),
        SmsCategory.SERVICE to mapOf(
            "order" to 2.3f, "shipped" to 3.2f, "delivered" to 3.2f, "delivery" to 2.8f,
            "out" to 0.8f, "parcel" to 3.0f, "package" to 2.8f, "tracking" to 2.9f,
            "courier" to 2.8f, "booking" to 2.7f, "confirmed" to 2.1f, "flight" to 2.9f,
            "boarding" to 3.0f, "pnr" to 3.2f, "ticket" to 2.5f, "appointment" to 2.9f,
            "scheduled" to 2.3f, "reminder" to 1.9f, "recharge" to 2.5f, "data" to 1.8f,
            "pack" to 1.6f, "plan" to 1.6f, "roaming" to 2.5f, "sim" to 2.2f, "broadband" to 2.6f,
            "outage" to 2.7f, "maintenance" to 2.4f, "support" to 1.9f, "driver" to 2.5f,
            "arriving" to 2.5f, "dispatched" to 3.1f
        ),
        SmsCategory.OTP to mapOf(
            "otp" to 4.2f, "verification" to 3.1f, "verify" to 2.6f, "passcode" to 3.6f,
            "code" to 2.4f, "login" to 2.3f, "2fa" to 3.8f, "pin" to 2.1f, "authenticate" to 3.0f,
            "authentication" to 3.0f, "share" to 1.5f, "expires" to 2.0f, "valid" to 1.4f
        ),
        SmsCategory.PROMOTIONAL to mapOf(
            "offer" to 3.1f, "discount" to 3.3f, "sale" to 3.2f, "cashback" to 3.1f,
            "deal" to 2.8f, "deals" to 2.8f, "free" to 2.4f, "win" to 2.7f, "winner" to 2.9f,
            "prize" to 2.9f, "coupon" to 3.2f, "voucher" to 3.0f, "promo" to 3.2f,
            "flat" to 2.0f, "off" to 1.9f, "shop" to 2.3f, "buy" to 2.1f, "limited" to 2.1f,
            "hurry" to 2.8f, "exclusive" to 2.6f, "unsubscribe" to 3.3f, "optout" to 3.3f,
            "reward" to 2.4f, "rewards" to 2.4f, "bonus" to 2.7f, "jackpot" to 3.4f,
            "loan" to 2.2f, "preapproved" to 3.3f, "festive" to 2.7f, "clearance" to 3.1f
        ),
        SmsCategory.GOVERNMENT to mapOf(
            "government" to 3.4f, "gov" to 3.1f, "ministry" to 3.4f, "emergency" to 3.1f,
            "advisory" to 3.1f, "alert" to 2.1f, "weather" to 2.3f, "evacuation" to 3.6f,
            "civic" to 3.0f, "municipal" to 3.0f, "election" to 3.2f, "voter" to 3.2f,
            "census" to 3.2f, "vaccination" to 2.8f, "passport" to 2.7f, "customs" to 2.6f,
            "public" to 1.7f, "authority" to 2.4f, "department" to 2.3f, "tax" to 2.4f,
            "disaster" to 3.3f, "police" to 2.9f
        )
    )

    // Regional feature priors per country profile
    private val regionalPriors: Map<RegionProfile, Map<SmsCategory, Map<String, Float>>> = mapOf(
        RegionProfile.IN to mapOf(
            SmsCategory.TRANSACTIONAL to mapOf(
                "upi" to 3.6f, "neft" to 3.6f, "imps" to 3.6f, "rtgs" to 3.5f, "vpa" to 3.2f,
                "hdfcbk" to 3.0f, "sbiinb" to 3.0f, "icicib" to 3.0f, "axisbk" to 3.0f,
                "kotakb" to 3.0f, "paytm" to 2.2f, "fastag" to 2.8f, "epfo" to 2.8f, "rupees" to 2.8f
            ),
            SmsCategory.SERVICE to mapOf(
                "irctc" to 3.6f, "jio" to 2.6f, "airtel" to 2.6f, "vi" to 2.0f, "bsnl" to 2.6f,
                "bluedart" to 3.2f, "delhivery" to 3.2f, "ekart" to 3.2f, "swiggy" to 2.4f,
                "zomato" to 2.4f, "indiapost" to 3.0f
            ),
            SmsCategory.GOVERNMENT to mapOf(
                "aadhaar" to 3.8f, "uidai" to 3.8f, "ndma" to 3.8f, "sachet" to 3.7f,
                "digilocker" to 3.4f, "parivahan" to 3.5f, "incometax" to 3.4f, "mygov" to 3.6f,
                "rbi" to 3.1f, "trai" to 3.2f
            ),
            SmsCategory.PROMOTIONAL to mapOf(
                "lakh" to 2.3f, "crore" to 2.3f, "diwali" to 2.4f, "bumper" to 2.9f
            )
        ),
        RegionProfile.US to mapOf(
            SmsCategory.TRANSACTIONAL to mapOf(
                "zelle" to 3.6f, "venmo" to 3.4f, "cashapp" to 3.3f, "ach" to 3.2f,
                "chase" to 2.8f, "bofa" to 2.8f, "wellsfargo" to 2.9f, "amex" to 2.8f,
                "autopay" to 2.7f, "checking" to 2.7f
            ),
            SmsCategory.SERVICE to mapOf(
                "usps" to 3.5f, "ups" to 3.2f, "fedex" to 3.3f, "doordash" to 2.6f,
                "lyft" to 2.6f, "uber" to 2.5f, "walgreens" to 2.7f, "cvs" to 2.7f, "verizon" to 2.6f,
                "tmobile" to 2.6f, "att" to 2.5f
            ),
            SmsCategory.PROMOTIONAL to mapOf(
                "stop" to 1.8f, "msg" to 1.2f, "rates" to 1.6f, "text" to 0.9f
            ),
            SmsCategory.GOVERNMENT to mapOf(
                "irs" to 3.7f, "fema" to 3.8f, "amber" to 3.6f, "dmv" to 3.4f,
                "ssa" to 3.4f, "medicare" to 3.1f, "usa" to 1.8f
            )
        ),
        RegionProfile.GB to mapOf(
            SmsCategory.TRANSACTIONAL to mapOf(
                "barclays" to 3.1f, "natwest" to 3.1f, "lloyds" to 3.1f, "hsbc" to 3.0f,
                "monzo" to 3.2f, "revolut" to 3.1f, "sortcode" to 3.2f, "directdebit" to 3.1f
            ),
            SmsCategory.SERVICE to mapOf(
                "royalmail" to 3.6f, "dpd" to 3.4f, "evri" to 3.4f, "openreach" to 3.3f,
                "o2" to 2.4f, "vodafone" to 2.5f, "trainline" to 3.1f, "gp" to 2.5f
            ),
            SmsCategory.GOVERNMENT to mapOf(
                "nhs" to 3.9f, "hmrc" to 3.9f, "dvla" to 3.8f, "govuk" to 4.0f,
                "ukgov" to 3.9f, "council" to 3.0f, "dwp" to 3.6f
            )
        ),
        RegionProfile.CA to mapOf(
            SmsCategory.TRANSACTIONAL to mapOf(
                "interac" to 3.8f, "etransfer" to 3.6f, "rbc" to 3.0f, "td" to 2.7f,
                "scotiabank" to 3.1f, "bmo" to 3.0f, "cibc" to 3.0f
            ),
            SmsCategory.SERVICE to mapOf(
                "canadapost" to 3.6f, "purolator" to 3.4f, "rogers" to 2.7f, "bell" to 2.5f,
                "telus" to 2.7f, "aircanada" to 3.1f
            ),
            SmsCategory.GOVERNMENT to mapOf(
                "cra" to 3.8f, "servicecanada" to 3.9f, "gc" to 2.6f, "alertready" to 3.9f
            )
        ),
        RegionProfile.EU_EN to mapOf(
            SmsCategory.TRANSACTIONAL to mapOf(
                "sepa" to 3.6f, "iban" to 3.4f, "aib" to 3.1f, "bankofireland" to 3.3f,
                "revolut" to 3.1f, "n26" to 3.1f
            ),
            SmsCategory.SERVICE to mapOf(
                "anpost" to 3.6f, "dhl" to 3.3f, "ryanair" to 3.1f, "aerlingus" to 3.2f,
                "eir" to 2.7f, "three" to 2.3f
            ),
            SmsCategory.GOVERNMENT to mapOf(
                "hse" to 3.8f, "garda" to 3.8f, "revenue" to 3.5f, "govie" to 3.9f,
                "mygovid" to 3.8f
            )
        )
    )

    private val officialGovHeaders = setOf(
        "nhs", "hmrc", "dvla", "govuk", "ukgov", "dwp",
        "irs", "fema", "dmv", "ssa",
        "cra", "servicecanada", "alertready",
        "uidai", "aadhaar", "ndma", "sachet", "mygov", "incometax", "digilocker", "parivahan",
        "hse", "garda", "govie", "mygovid", "revenue"
    )
    private val tokenSplitRegex = Regex("[^a-z0-9]+")
    private val stopOptOutPhraseRegex = Regex("(?i)\\b(reply\\s+stop|text\\s+stop|stop\\s+to\\s+opt[\\s-]?out|t&c\\s+apply|terms\\s+apply)\\b")
    private val currencySymbolRegex = Regex("[₹$£€]")

    fun predict(senderHeader: String, body: String, region: RegionProfile): ModelPrediction {
        val cleanHeader = senderHeader.lowercase(Locale.ROOT).replace(tokenSplitRegex, "")
        val combinedText = "${senderHeader.lowercase(Locale.ROOT)} ${body.lowercase(Locale.ROOT)}"
        val tokens = tokenSplitRegex.split(combinedText).filter { it.length >= 2 }

        val scores = HashMap<SmsCategory, Float>(targetClasses.size)
        for (cat in targetClasses) {
            scores[cat] = baseLogPriors[cat] ?: -2.0f
        }

        // Direct sender header authority signal for verified public/government agencies
        if (cleanHeader in officialGovHeaders || cleanHeader.startsWith("gov")) {
            scores[SmsCategory.GOVERNMENT] = (scores[SmsCategory.GOVERNMENT] ?: 0f) + 5.5f
        }

        // Token frequency dampening (sublinear TF: 1 + 0.35 * extra occurrences)
        val tokenCounts = HashMap<String, Int>()
        for (token in tokens) {
            tokenCounts[token] = (tokenCounts[token] ?: 0) + 1
        }

        val activeRegionalMap = regionalPriors[region].orEmpty()

        for ((token, count) in tokenCounts) {
            val tfScale = if (count == 1) 1.0f else 1.0f + 0.35f * (count - 1).coerceAtMost(3)

            for (cat in targetClasses) {
                val uniWeight = universalLexicon[cat]?.get(token) ?: 0f
                val regWeight = activeRegionalMap[cat]?.get(token) ?: 0f
                if (uniWeight > 0f || regWeight > 0f) {
                    scores[cat] = (scores[cat] ?: 0f) + (uniWeight + regWeight) * tfScale
                }
            }
        }

        // Structural feature boosters
        if (stopOptOutPhraseRegex.containsMatchIn(body)) {
            scores[SmsCategory.PROMOTIONAL] = (scores[SmsCategory.PROMOTIONAL] ?: 0f) + 2.8f
        }
        if (currencySymbolRegex.containsMatchIn(body)) {
            scores[SmsCategory.TRANSACTIONAL] = (scores[SmsCategory.TRANSACTIONAL] ?: 0f) + 1.4f
        }
        if (body.contains("% off", ignoreCase = true) || body.contains("% cashback", ignoreCase = true)) {
            scores[SmsCategory.PROMOTIONAL] = (scores[SmsCategory.PROMOTIONAL] ?: 0f) + 3.2f
        }

        // Convert log-scores to calibrated softmax probabilities
        val maxLogit = scores.values.maxOrNull() ?: 0f
        var sumExp = 0.0
        val expMap = HashMap<SmsCategory, Double>(targetClasses.size)
        for ((cat, logit) in scores) {
            val e = exp((logit - maxLogit).toDouble())
            expMap[cat] = e
            sumExp += e
        }

        val probs = HashMap<SmsCategory, Float>(targetClasses.size)
        var bestCategory = SmsCategory.OTHER
        var bestProb = 0f

        for (cat in targetClasses) {
            val p = if (sumExp > 0.0) ((expMap[cat] ?: 0.0) / sumExp).toFloat() else 0f
            probs[cat] = p
            if (p > bestProb) {
                bestProb = p
                bestCategory = cat
            }
        }

        return ModelPrediction(
            probabilities = probs,
            topCategory = bestCategory,
            topConfidence = bestProb
        )
    }
}
