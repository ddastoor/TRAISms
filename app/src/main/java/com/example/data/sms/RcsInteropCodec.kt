package com.example.data.sms

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class MessageReaction(
    val emoji: String,
    val senderAddress: String,
    val senderDisplay: String,
    val timestampMillis: Long,
    val isFromSelf: Boolean = false
)

data class ParsedReactionFallback(
    val emoji: String,
    val quotedText: String?,
    val mediaKind: String?,
    val isRemoval: Boolean = false
)

/**
 * Google Messages-style RCS / SMS/MMS reaction fallback interoperability codec,
 * delivery label formatter, and offline vCard builder/parser.
 */
object RcsInteropCodec {

    val QUICK_TAPBACK_EMOJIS: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "😡")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val VERB_TO_EMOJI = mapOf(
        "liked" to "👍",
        "loved" to "❤️",
        "laughed at" to "😂",
        "disliked" to "👎",
        "surprised by" to "😮",
        "cried at" to "😢",
        "emphasized" to "‼️",
        "questioned" to "❓"
    )

    private val REMOVAL_NOUN_TO_EMOJI = mapOf(
        "like" to "👍",
        "love" to "❤️",
        "laugh" to "😂",
        "dislike" to "👎",
        "reaction" to "👍"
    )

    // Matches: Liked “Hello world”, Loved "Hello", Reacted 😮 to “Hello”, Laughed at “...”
    private val QUOTED_REACTION_REGEX = Regex(
        """^(Loved|Liked|Laughed at|Disliked|Surprised by|Cried at|Emphasized|Questioned)\s+[“"]([\s\S]+)[”"]\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val GENERIC_REACTED_QUOTED_REGEX = Regex(
        """^Reacted\s+(\S+)\s+to\s+[“"]([\s\S]+)[”"]\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val MEDIA_REACTION_REGEX = Regex(
        """^(Loved|Liked|Laughed at|Disliked|Surprised by|Cried at|Emphasized|Questioned)\s+(?:an|a)\s+(image|photo|video|audio message|file|contact|message)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val GENERIC_MEDIA_REACTED_REGEX = Regex(
        """^Reacted\s+(\S+)\s+to\s+(?:an|a)\s+(image|photo|video|audio message|file|contact|message)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val REMOVED_QUOTED_REGEX = Regex(
        """^Removed\s+(?:a|an)\s+(like|love|laugh|dislike|reaction)\s+from\s+[“"]([\s\S]+)[”"]\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val REMOVED_EMOJI_REGEX = Regex(
        """^Removed\s+(\S+)\s+(?:reaction\s+)?from\s+[“"]([\s\S]+)[”"]\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun encodeReactionFallback(
        emoji: String,
        targetBody: String,
        primaryAttachmentMime: String? = null,
        isRemoval: Boolean = false
    ): String {
        val cleanBody = targetBody.trim()
        val mediaNoun = when {
            primaryAttachmentMime == null -> null
            primaryAttachmentMime.startsWith("image/", ignoreCase = true) -> "an image"
            primaryAttachmentMime.startsWith("video/", ignoreCase = true) -> "a video"
            primaryAttachmentMime.startsWith("audio/", ignoreCase = true) -> "an audio message"
            else -> "a file"
        }

        val snippet = cleanBody.take(80)
        if (isRemoval) {
            val noun = when (emoji) {
                "👍" -> "like"
                "❤️" -> "love"
                "😂" -> "laugh"
                "👎" -> "dislike"
                else -> emoji
            }
            return if (cleanBody.isNotEmpty()) {
                if (noun == emoji) "Removed $emoji from “$snippet”"
                else "Removed a $noun from “$snippet”"
            } else {
                "Removed $emoji from ${mediaNoun ?: "a message"}"
            }
        }

        val verb = when (emoji) {
            "👍" -> "Liked"
            "❤️" -> "Loved"
            "😂" -> "Laughed at"
            "👎" -> "Disliked"
            else -> null
        }

        return if (cleanBody.isNotEmpty()) {
            if (verb != null) "$verb “$snippet”" else "Reacted $emoji to “$snippet”"
        } else {
            val targetMedia = mediaNoun ?: "a message"
            if (verb != null) "$verb $targetMedia" else "Reacted $emoji to $targetMedia"
        }
    }

    fun parseReactionFallback(body: String): ParsedReactionFallback? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        REMOVED_QUOTED_REGEX.matchEntire(trimmed)?.let { m ->
            val noun = m.groupValues[1].lowercase(Locale.ROOT)
            val emoji = REMOVAL_NOUN_TO_EMOJI[noun] ?: "👍"
            return ParsedReactionFallback(
                emoji = emoji,
                quotedText = m.groupValues[2],
                mediaKind = null,
                isRemoval = true
            )
        }

        REMOVED_EMOJI_REGEX.matchEntire(trimmed)?.let { m ->
            return ParsedReactionFallback(
                emoji = m.groupValues[1],
                quotedText = m.groupValues[2],
                mediaKind = null,
                isRemoval = true
            )
        }

        QUOTED_REACTION_REGEX.matchEntire(trimmed)?.let { m ->
            val verb = m.groupValues[1].lowercase(Locale.ROOT)
            val emoji = VERB_TO_EMOJI[verb] ?: return null
            return ParsedReactionFallback(
                emoji = emoji,
                quotedText = m.groupValues[2],
                mediaKind = null,
                isRemoval = false
            )
        }

        GENERIC_REACTED_QUOTED_REGEX.matchEntire(trimmed)?.let { m ->
            return ParsedReactionFallback(
                emoji = m.groupValues[1],
                quotedText = m.groupValues[2],
                mediaKind = null,
                isRemoval = false
            )
        }

        MEDIA_REACTION_REGEX.matchEntire(trimmed)?.let { m ->
            val verb = m.groupValues[1].lowercase(Locale.ROOT)
            val emoji = VERB_TO_EMOJI[verb] ?: return null
            return ParsedReactionFallback(
                emoji = emoji,
                quotedText = null,
                mediaKind = m.groupValues[2].lowercase(Locale.ROOT),
                isRemoval = false
            )
        }

        GENERIC_MEDIA_REACTED_REGEX.matchEntire(trimmed)?.let { m ->
            return ParsedReactionFallback(
                emoji = m.groupValues[1],
                quotedText = null,
                mediaKind = m.groupValues[2].lowercase(Locale.ROOT),
                isRemoval = false
            )
        }

        return null
    }

    fun decodeReactions(reactionsJson: String?): List<MessageReaction> {
        if (reactionsJson.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(MessageReaction.serializer()), reactionsJson)
        }.getOrDefault(emptyList())
    }

    fun encodeReactions(reactions: List<MessageReaction>): String? {
        if (reactions.isEmpty()) return null
        return json.encodeToString(ListSerializer(MessageReaction.serializer()), reactions)
    }

    /**
     * Toggles a reaction from [senderAddress] with [emoji].
     * Returns Pair(updatedJsonOrNull, wasRemoved).
     */
    fun toggleReaction(
        existingJson: String?,
        emoji: String,
        senderAddress: String,
        senderDisplay: String,
        isFromSelf: Boolean,
        timestampMillis: Long
    ): Pair<String?, Boolean> {
        val current = decodeReactions(existingJson).toMutableList()
        val existingIdx = current.indexOfFirst {
            it.senderAddress == senderAddress && it.emoji == emoji
        }
        if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            return encodeReactions(current) to true
        }
        // Replace any prior reaction from this sender so each participant holds 1 active tapback
        current.removeAll { it.senderAddress == senderAddress }
        current.add(
            MessageReaction(
                emoji = emoji,
                senderAddress = senderAddress,
                senderDisplay = senderDisplay,
                timestampMillis = timestampMillis,
                isFromSelf = isFromSelf
            )
        )
        return encodeReactions(current) to false
    }

    fun formatDeliveryStatusLabel(status: String): String = when (status.uppercase(Locale.ROOT)) {
        "SENDING" -> "Sending…"
        "SENT" -> "Sent"
        "DELIVERED" -> "Delivered"
        "READ" -> "Read"
        "FAILED" -> "Failed"
        "PENDING_DOWNLOAD" -> "Tap to download MMS"
        else -> ""
    }

    fun buildVCardContent(displayName: String, phoneNumber: String): String {
        val safeName = displayName.trim().ifEmpty { phoneNumber.trim() }
        val safePhone = phoneNumber.trim()
        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("FN:$safeName")
            appendLine("TEL;TYPE=CELL:$safePhone")
            append("END:VCARD")
        }
    }

    fun parseVCardSummary(vCardText: String): Pair<String, String>? {
        if (vCardText.isBlank()) return null
        var name: String? = null
        var phone: String? = null
        for (rawLine in vCardText.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("FN:", ignoreCase = true)) {
                name = line.substringAfter(':').trim()
            } else if (line.startsWith("N:", ignoreCase = true) && name.isNullOrBlank()) {
                val nVal = line.substringAfter(':').split(';').filter { it.isNotBlank() }.reversed().joinToString(" ")
                if (nVal.isNotBlank()) name = nVal
            } else if (line.startsWith("TEL", ignoreCase = true) && line.contains(':')) {
                if (phone.isNullOrBlank()) {
                    phone = line.substringAfter(':').trim()
                }
            }
        }
        val finalName = name?.takeIf { it.isNotBlank() } ?: phone?.takeIf { it.isNotBlank() } ?: return null
        return finalName to (phone ?: "")
    }
}
