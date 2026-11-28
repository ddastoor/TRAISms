package com.example.data.sms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale

enum class MmsPduType(val code: Int) {
    SEND_REQ(0x80),
    SEND_CONF(0x81),
    NOTIFICATION_IND(0x82),
    NOTIFYRESP_IND(0x83),
    RETRIEVE_CONF(0x84),
    ACKNOWLEDGE_IND(0x85),
    DELIVERY_IND(0x86),
    READ_REC_IND(0x87),
    READ_ORIG_IND(0x88);

    companion object {
        fun fromCode(code: Int): MmsPduType? = entries.firstOrNull { it.code == code }
    }
}

data class MmsPart(
    val contentType: String,
    val contentId: String? = null,
    val contentLocation: String? = null,
    val fileName: String? = null,
    val charset: Int = 106,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MmsPart) return false
        return contentType == other.contentType &&
            contentId == other.contentId &&
            contentLocation == other.contentLocation &&
            fileName == other.fileName &&
            charset == other.charset &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = contentType.hashCode()
        result = 31 * result + (contentId?.hashCode() ?: 0)
        result = 31 * result + (contentLocation?.hashCode() ?: 0)
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + charset
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class MmsSendReqInput(
    val transactionId: String,
    val toAddresses: List<String>,
    val fromAddress: String? = null,
    val subject: String? = null,
    val epochSeconds: Long,
    val deliveryReport: Boolean = true,
    val readReport: Boolean = false,
    val parts: List<MmsPart>
)

data class ParsedMmsPdu(
    val messageType: MmsPduType,
    val transactionId: String? = null,
    val messageId: String? = null,
    val contentLocation: String? = null,
    val fromAddress: String? = null,
    val toAddresses: List<String> = emptyList(),
    val ccAddresses: List<String> = emptyList(),
    val subject: String? = null,
    val dateEpochSeconds: Long = 0L,
    val messageSizeBytes: Long = 0L,
    val statusByte: Int? = null,
    val responseStatus: Int? = null,
    val readReportRequested: Boolean = false,
    val parts: List<MmsPart> = emptyList()
)

/**
 * Pure-Kotlin OMA-TS-MMS-ENC / WAP-209 binary MMS PDU encoder and decoder.
 * Zero hidden `@hide` framework APIs and zero network dependencies.
 */
object MmsPduCodec {

    // MMS Header Field Assignments (OMA-TS-MMS-ENC Table 12)
    private const val HEADER_BCC = 0x81
    private const val HEADER_CC = 0x82
    private const val HEADER_CONTENT_LOCATION = 0x83
    private const val HEADER_CONTENT_TYPE = 0x84
    private const val HEADER_DATE = 0x85
    private const val HEADER_DELIVERY_REPORT = 0x86
    private const val HEADER_EXPIRY = 0x88
    private const val HEADER_FROM = 0x89
    private const val HEADER_MESSAGE_CLASS = 0x8A
    private const val HEADER_MESSAGE_ID = 0x8B
    private const val HEADER_MESSAGE_TYPE = 0x8C
    private const val HEADER_MMS_VERSION = 0x8D
    private const val HEADER_MESSAGE_SIZE = 0x8E
    private const val HEADER_PRIORITY = 0x8F
    private const val HEADER_READ_REPORT = 0x90
    private const val HEADER_REPORT_ALLOWED = 0x91
    private const val HEADER_RESPONSE_STATUS = 0x92
    private const val HEADER_RESPONSE_TEXT = 0x93
    private const val HEADER_SENDER_VISIBILITY = 0x94
    private const val HEADER_STATUS = 0x95
    private const val HEADER_SUBJECT = 0x96
    private const val HEADER_TO = 0x97
    private const val HEADER_TRANSACTION_ID = 0x98
    private const val HEADER_RETRIEVE_STATUS = 0x99
    private const val HEADER_RETRIEVE_TEXT = 0x9A
    private const val HEADER_READ_STATUS = 0x9B

    // MMS Field Values
    private const val MMS_VERSION_1_0 = 0x90
    private const val MMS_VERSION_1_2 = 0x92
    private const val VALUE_YES = 0x80
    private const val VALUE_NO = 0x81
    private const val FROM_ADDRESS_PRESENT = 0x80
    private const val FROM_INSERT_ADDRESS = 0x81

    // WSP Part Headers
    private const val PART_HEADER_CONTENT_LOCATION = 0x8E
    private const val PART_HEADER_CONTENT_ID = 0xC0

    // WSP Content-Type Parameters
    private const val PARAM_CHARSET = 0x81
    private const val PARAM_NAME = 0x85
    private const val PARAM_TYPE_REL = 0x89
    private const val PARAM_START = 0x8A
    private const val PARAM_FILENAME = 0x97

    private const val CHARSET_UTF8 = 106

    private val WELL_KNOWN_CONTENT_TYPES = mapOf(
        0x00 to "*/*",
        0x01 to "text/*",
        0x02 to "text/html",
        0x03 to "text/plain",
        0x04 to "text/x-hdml",
        0x05 to "text/x-ttml",
        0x06 to "text/x-vCalendar",
        0x07 to "text/x-vCard",
        0x08 to "text/vnd.wap.wml",
        0x09 to "text/vnd.wap.wmlscript",
        0x0A to "text/vnd.wap.wta-event",
        0x0B to "multipart/*",
        0x0C to "multipart/mixed",
        0x0D to "multipart/form-data",
        0x0E to "multipart/byteranges",
        0x0F to "multipart/alternative",
        0x10 to "application/*",
        0x11 to "application/java-vm",
        0x12 to "application/x-www-form-urlencoded",
        0x13 to "application/x-hdmlc",
        0x14 to "application/vnd.wap.wmlc",
        0x15 to "application/vnd.wap.wmlscriptc",
        0x16 to "application/vnd.wap.wta-eventc",
        0x17 to "application/vnd.wap.uaprof",
        0x18 to "application/vnd.wap.wtls-ca-certificate",
        0x19 to "application/vnd.wap.wtls-user-certificate",
        0x1A to "application/x-x509-ca-cert",
        0x1B to "application/x-x509-user-cert",
        0x1C to "image/*",
        0x1D to "image/gif",
        0x1E to "image/jpeg",
        0x1F to "image/tiff",
        0x20 to "image/png",
        0x21 to "image/vnd.wap.wbmp",
        0x22 to "application/vnd.wap.multipart.*",
        0x23 to "application/vnd.wap.multipart.mixed",
        0x24 to "application/vnd.wap.multipart.form-data",
        0x25 to "application/vnd.wap.multipart.byteranges",
        0x26 to "application/vnd.wap.multipart.alternative",
        0x27 to "application/xml",
        0x28 to "text/xml",
        0x29 to "application/vnd.wap.wbxml",
        0x2A to "application/x-x968-cross-cert",
        0x2B to "application/x-x968-ca-cert",
        0x2C to "application/x-x968-user-cert",
        0x2D to "text/vnd.wap.si",
        0x2E to "application/vnd.wap.sic",
        0x2F to "text/vnd.wap.sl",
        0x30 to "application/vnd.wap.slc",
        0x31 to "text/vnd.wap.co",
        0x32 to "application/vnd.wap.coc",
        0x33 to "application/vnd.wap.multipart.related",
        0x34 to "application/vnd.wap.sia"
    )

    private val CONTENT_TYPE_TO_CODE = WELL_KNOWN_CONTENT_TYPES.entries
        .associate { (k, v) -> v.lowercase(Locale.ROOT) to k }

    private val WELL_KNOWN_CHARSETS = mapOf(
        0x03 to "US-ASCII",
        0x04 to "ISO-8859-1",
        0x6A to "UTF-8",
        0x03EA to "UTF-16BE",
        0x03EB to "UTF-16LE",
        0x03F7 to "UTF-16"
    )

    fun encodeSendReq(input: MmsSendReqInput): ByteArray {
        val out = ByteArrayOutputStream()

        // X-Mms-Message-Type: M-Send.req
        out.write(HEADER_MESSAGE_TYPE)
        out.write(MmsPduType.SEND_REQ.code)

        // X-Mms-Transaction-Id
        out.write(HEADER_TRANSACTION_ID)
        writeTextString(out, input.transactionId.ifBlank { "tx_${input.epochSeconds}" })

        // X-Mms-MMS-Version: 1.0 (0x90)
        out.write(HEADER_MMS_VERSION)
        out.write(MMS_VERSION_1_0)

        // Date (long-integer epoch seconds)
        if (input.epochSeconds > 0L) {
            out.write(HEADER_DATE)
            writeLongInteger(out, input.epochSeconds)
        }

        // From
        out.write(HEADER_FROM)
        val cleanFrom = input.fromAddress?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanFrom == null) {
            // Value-length = 1, Insert-address-token = 0x81
            out.write(1)
            out.write(FROM_INSERT_ADDRESS)
        } else {
            val formattedFrom = formatMmsAddress(cleanFrom)
            val fromBytes = ByteArrayOutputStream()
            fromBytes.write(FROM_ADDRESS_PRESENT)
            writeEncodedStringValue(fromBytes, formattedFrom)
            val payload = fromBytes.toByteArray()
            writeValueLength(out, payload.size)
            out.write(payload)
        }

        // To (multiple headers for group MMS)
        input.toAddresses
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { addr ->
                out.write(HEADER_TO)
                writeEncodedStringValue(out, formatMmsAddress(addr))
            }

        // Optional Subject
        input.subject?.trim()?.takeIf { it.isNotEmpty() }?.let { subj ->
            out.write(HEADER_SUBJECT)
            writeEncodedStringValue(out, subj)
        }

        // X-Mms-Delivery-Report
        out.write(HEADER_DELIVERY_REPORT)
        out.write(if (input.deliveryReport) VALUE_YES else VALUE_NO)

        // X-Mms-Read-Report
        out.write(HEADER_READ_REPORT)
        out.write(if (input.readReport) VALUE_YES else VALUE_NO)

        // Build SMIL presentation + payload parts
        val hasSmilAlready = input.parts.any { it.contentType.equals("application/smil", ignoreCase = true) }
        val allParts = if (hasSmilAlready || input.parts.isEmpty()) {
            input.parts
        } else {
            val smilXml = buildSmilXml(input.parts)
            val smilPart = MmsPart(
                contentType = "application/smil",
                contentId = "<smil>",
                contentLocation = "smil.xml",
                charset = CHARSET_UTF8,
                data = smilXml.toByteArray(Charsets.UTF_8)
            )
            listOf(smilPart) + input.parts
        }

        // Content-Type MUST be the final header before multipart body
        out.write(HEADER_CONTENT_TYPE)
        if (allParts.any { it.contentType.equals("application/smil", ignoreCase = true) }) {
            val ctBytes = ByteArrayOutputStream()
            // application/vnd.wap.multipart.related = 0x33 -> short-int 0xB3
            ctBytes.write(0x33 or 0x80)
            ctBytes.write(PARAM_TYPE_REL)
            writeTextString(ctBytes, "application/smil")
            ctBytes.write(PARAM_START)
            writeTextString(ctBytes, "<smil>")
            val rawCt = ctBytes.toByteArray()
            writeValueLength(out, rawCt.size)
            out.write(rawCt)
        } else {
            // application/vnd.wap.multipart.mixed = 0x23 -> short-int 0xA3
            out.write(0x23 or 0x80)
        }

        // Multipart Body: uintvar count followed by each part
        writeUintvar(out, allParts.size)
        for ((index, part) in allParts.withIndex()) {
            writeMultipartEntry(out, part, index)
        }

        return out.toByteArray()
    }

    fun encodeNotifyRespInd(transactionId: String, status: Int = 0x81): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(HEADER_MESSAGE_TYPE)
        out.write(MmsPduType.NOTIFYRESP_IND.code)
        out.write(HEADER_TRANSACTION_ID)
        writeTextString(out, transactionId.ifBlank { "tx_notify" })
        out.write(HEADER_MMS_VERSION)
        out.write(MMS_VERSION_1_0)
        out.write(HEADER_STATUS)
        out.write(status or 0x80)
        out.write(HEADER_REPORT_ALLOWED)
        out.write(VALUE_YES)
        return out.toByteArray()
    }

    fun encodeAcknowledgeInd(transactionId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(HEADER_MESSAGE_TYPE)
        out.write(MmsPduType.ACKNOWLEDGE_IND.code)
        out.write(HEADER_TRANSACTION_ID)
        writeTextString(out, transactionId.ifBlank { "tx_ack" })
        out.write(HEADER_MMS_VERSION)
        out.write(MMS_VERSION_1_0)
        return out.toByteArray()
    }

    fun encodeReadRecInd(messageId: String, toAddress: String, epochSeconds: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(HEADER_MESSAGE_TYPE)
        out.write(MmsPduType.READ_REC_IND.code)
        out.write(HEADER_MMS_VERSION)
        out.write(MMS_VERSION_1_2)
        out.write(HEADER_MESSAGE_ID)
        writeTextString(out, messageId)
        out.write(HEADER_TO)
        writeEncodedStringValue(out, formatMmsAddress(toAddress))
        out.write(HEADER_FROM)
        out.write(1)
        out.write(FROM_INSERT_ADDRESS)
        out.write(HEADER_DATE)
        writeLongInteger(out, epochSeconds)
        out.write(HEADER_READ_STATUS)
        out.write(0x80) // Read
        return out.toByteArray()
    }

    fun buildSmilXml(parts: List<MmsPart>): String {
        val nonSmil = parts.filterNot { it.contentType.equals("application/smil", ignoreCase = true) }
        val bodyElements = StringBuilder()
        var hasImage = false
        var hasText = false

        nonSmil.forEachIndexed { idx, part ->
            val src = part.contentLocation?.takeIf { it.isNotBlank() }
                ?: part.fileName?.takeIf { it.isNotBlank() }
                ?: "part_$idx"
            val cidRef = part.contentId?.trim('<', '>')?.let { "cid:$it" } ?: src
            val mime = part.contentType.lowercase(Locale.ROOT)
            when {
                mime.startsWith("image/") -> {
                    hasImage = true
                    bodyElements.append("""<img src="$cidRef" region="Image"/>""")
                }
                mime.startsWith("video/") -> {
                    hasImage = true
                    bodyElements.append("""<video src="$cidRef" region="Image"/>""")
                }
                mime.startsWith("audio/") -> {
                    bodyElements.append("""<audio src="$cidRef"/>""")
                }
                mime == "text/plain" -> {
                    hasText = true
                    bodyElements.append("""<text src="$cidRef" region="Text"/>""")
                }
                else -> {
                    bodyElements.append("""<ref src="$cidRef"/>""")
                }
            }
        }

        val regions = StringBuilder()
        regions.append("""<root-layout width="100%" height="100%"/>""")
        if (hasImage) regions.append("""<region id="Image" width="100%" height="70%" fit="meet"/>""")
        if (hasText) regions.append("""<region id="Text" width="100%" height="30%" fit="scroll"/>""")

        return """<smil><head><layout>$regions</layout></head><body><par dur="5000ms">$bodyElements</par></body></smil>"""
    }

    fun decodePdu(pduBytes: ByteArray): ParsedMmsPdu? {
        if (pduBytes.size < 2) return null
        return runCatching {
            val reader = PduReader(pduBytes)
            var messageType: MmsPduType? = null
            var transactionId: String? = null
            var messageId: String? = null
            var contentLocation: String? = null
            var fromAddress: String? = null
            val toAddresses = mutableListOf<String>()
            val ccAddresses = mutableListOf<String>()
            var subject: String? = null
            var dateEpochSeconds = 0L
            var messageSizeBytes = 0L
            var statusByte: Int? = null
            var responseStatus: Int? = null
            var readReportRequested = false
            var bodyContentType: String? = null

            while (reader.hasRemaining()) {
                val field = reader.readUnsignedByte() ?: break
                when (field) {
                    HEADER_MESSAGE_TYPE -> {
                        val code = reader.readUnsignedByte() ?: return null
                        messageType = MmsPduType.fromCode(code) ?: return null
                    }
                    HEADER_TRANSACTION_ID -> {
                        transactionId = reader.readTextString()
                    }
                    HEADER_MMS_VERSION -> {
                        reader.readUnsignedByte()
                    }
                    HEADER_MESSAGE_ID -> {
                        messageId = reader.readTextString()
                    }
                    HEADER_CONTENT_LOCATION -> {
                        contentLocation = reader.readTextString()
                    }
                    HEADER_DATE -> {
                        dateEpochSeconds = reader.readLongInteger()
                    }
                    HEADER_MESSAGE_SIZE -> {
                        messageSizeBytes = reader.readLongInteger()
                    }
                    HEADER_EXPIRY -> {
                        // Value-length + token + long-integer
                        val len = reader.readValueLength()
                        if (len > 0) reader.skip(len)
                    }
                    HEADER_FROM -> {
                        val len = reader.readValueLength()
                        if (len > 0) {
                            val sub = reader.slice(len)
                            val token = sub.readUnsignedByte()
                            if (token == FROM_ADDRESS_PRESENT && sub.hasRemaining()) {
                                fromAddress = stripMmsAddressSuffix(sub.readEncodedStringValue())
                            }
                        }
                    }
                    HEADER_TO -> {
                        val raw = stripMmsAddressSuffix(reader.readEncodedStringValue())
                        if (raw.isNotEmpty()) toAddresses.add(raw)
                    }
                    HEADER_CC -> {
                        val raw = stripMmsAddressSuffix(reader.readEncodedStringValue())
                        if (raw.isNotEmpty()) ccAddresses.add(raw)
                    }
                    HEADER_BCC -> {
                        reader.readEncodedStringValue()
                    }
                    HEADER_SUBJECT -> {
                        subject = reader.readEncodedStringValue().takeIf { it.isNotEmpty() }
                    }
                    HEADER_DELIVERY_REPORT,
                    HEADER_SENDER_VISIBILITY,
                    HEADER_PRIORITY,
                    HEADER_REPORT_ALLOWED -> {
                        reader.readUnsignedByte()
                    }
                    HEADER_READ_REPORT -> {
                        val v = reader.readUnsignedByte()
                        readReportRequested = (v == VALUE_YES)
                    }
                    HEADER_STATUS -> {
                        statusByte = reader.readUnsignedByte()
                    }
                    HEADER_READ_STATUS -> {
                        statusByte = reader.readUnsignedByte()
                    }
                    HEADER_RESPONSE_STATUS,
                    HEADER_RETRIEVE_STATUS -> {
                        responseStatus = reader.readUnsignedByte()
                    }
                    HEADER_RESPONSE_TEXT,
                    HEADER_RETRIEVE_TEXT -> {
                        reader.readEncodedStringValue()
                    }
                    HEADER_MESSAGE_CLASS -> {
                        val next = reader.peekUnsignedByte() ?: break
                        if (next >= 0x80) reader.readUnsignedByte() else reader.readTextString()
                    }
                    HEADER_CONTENT_TYPE -> {
                        bodyContentType = reader.readContentType().first
                        // Content-Type is the last header before multipart body in SEND_REQ and RETRIEVE_CONF
                        if (messageType == MmsPduType.SEND_REQ || messageType == MmsPduType.RETRIEVE_CONF) {
                            break
                        }
                    }
                    else -> {
                        // Skip unknown well-known header safely
                        val peek = reader.peekUnsignedByte() ?: break
                        when {
                            peek < 31 -> {
                                val len = reader.readValueLength()
                                if (len > 0) reader.skip(len)
                            }
                            peek == 31 -> {
                                reader.readUnsignedByte()
                                val len = reader.readUintvar()
                                if (len > 0) reader.skip(len)
                            }
                            peek in 32..127 -> reader.readTextString()
                            else -> reader.readUnsignedByte()
                        }
                    }
                }
            }

            val resolvedType = messageType ?: return null
            val parts = if ((resolvedType == MmsPduType.SEND_REQ || resolvedType == MmsPduType.RETRIEVE_CONF) &&
                bodyContentType != null &&
                reader.hasRemaining()
            ) {
                readMultipartBody(reader)
            } else {
                emptyList()
            }

            ParsedMmsPdu(
                messageType = resolvedType,
                transactionId = transactionId,
                messageId = messageId,
                contentLocation = contentLocation,
                fromAddress = fromAddress,
                toAddresses = toAddresses,
                ccAddresses = ccAddresses,
                subject = subject,
                dateEpochSeconds = dateEpochSeconds,
                messageSizeBytes = messageSizeBytes,
                statusByte = statusByte,
                responseStatus = responseStatus,
                readReportRequested = readReportRequested,
                parts = parts
            )
        }.getOrNull()
    }

    fun extractSmilOrderedParts(parts: List<MmsPart>): List<MmsPart> {
        val smilPart = parts.firstOrNull { it.contentType.equals("application/smil", ignoreCase = true) }
        val contentParts = parts.filterNot { it.contentType.equals("application/smil", ignoreCase = true) }
        if (smilPart == null || contentParts.size <= 1) return contentParts

        val smilXml = runCatching { smilPart.data.toString(Charsets.UTF_8) }.getOrNull() ?: return contentParts
        val srcRegex = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val ordered = mutableListOf<MmsPart>()
        val remaining = contentParts.toMutableList()

        for (match in srcRegex.findAll(smilXml)) {
            val rawRef = match.groupValues[1].trim()
            val cidClean = rawRef.removePrefix("cid:").trim('<', '>')
            val foundIdx = remaining.indexOfFirst { part ->
                val pCid = part.contentId?.trim('<', '>')
                val pLoc = part.contentLocation
                val pFile = part.fileName
                pCid == cidClean || pLoc == rawRef || pLoc == cidClean || pFile == rawRef
            }
            if (foundIdx >= 0) {
                ordered.add(remaining.removeAt(foundIdx))
            }
        }
        ordered.addAll(remaining)
        return ordered
    }

    private fun writeMultipartEntry(out: ByteArrayOutputStream, part: MmsPart, index: Int) {
        val headerBytes = ByteArrayOutputStream()
        writeContentType(headerBytes, part)

        part.contentId?.trim()?.takeIf { it.isNotEmpty() }?.let { cid ->
            headerBytes.write(PART_HEADER_CONTENT_ID)
            val normalizedCid = if (cid.startsWith("<")) cid else "<$cid>"
            writeQuotedString(headerBytes, normalizedCid)
        }

        val loc = part.contentLocation?.trim()?.takeIf { it.isNotEmpty() }
            ?: part.fileName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "part_$index"
        headerBytes.write(PART_HEADER_CONTENT_LOCATION)
        writeTextString(headerBytes, loc)

        val headersRaw = headerBytes.toByteArray()
        writeUintvar(out, headersRaw.size)
        writeUintvar(out, part.data.size)
        out.write(headersRaw)
        out.write(part.data)
    }

    private fun writeContentType(out: ByteArrayOutputStream, part: MmsPart) {
        val cleanMime = part.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
            .ifEmpty { "application/octet-stream" }
        val knownCode = CONTENT_TYPE_TO_CODE[cleanMime]
        val hasParams = cleanMime.startsWith("text/") || !part.fileName.isNullOrBlank()

        if (!hasParams) {
            if (knownCode != null) {
                out.write(knownCode or 0x80)
            } else {
                writeTextString(out, cleanMime)
            }
            return
        }

        val buf = ByteArrayOutputStream()
        if (knownCode != null) {
            buf.write(knownCode or 0x80)
        } else {
            writeTextString(buf, cleanMime)
        }

        if (cleanMime.startsWith("text/")) {
            buf.write(PARAM_CHARSET)
            buf.write(part.charset or 0x80)
        }

        part.fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            buf.write(PARAM_NAME)
            writeTextString(buf, name)
        }

        val raw = buf.toByteArray()
        writeValueLength(out, raw.size)
        out.write(raw)
    }

    private fun readMultipartBody(reader: PduReader): List<MmsPart> {
        val count = reader.readUintvar().coerceIn(0, 64)
        val parts = ArrayList<MmsPart>(count)

        repeat(count) {
            if (!reader.hasRemaining()) return@repeat
            val headersLen = reader.readUintvar()
            val dataLen = reader.readUintvar()
            if (headersLen <= 0 || dataLen < 0) return@repeat

            val headerReader = reader.slice(headersLen)
            val (contentType, params) = headerReader.readContentType()
            var contentId: String? = null
            var contentLocation: String? = null

            while (headerReader.hasRemaining()) {
                val hdr = headerReader.readUnsignedByte() ?: break
                when (hdr) {
                    PART_HEADER_CONTENT_ID -> {
                        contentId = headerReader.readTextString().trim('"', '<', '>')
                    }
                    PART_HEADER_CONTENT_LOCATION -> {
                        contentLocation = headerReader.readTextString()
                    }
                    else -> {
                        val peek = headerReader.peekUnsignedByte() ?: break
                        if (peek < 31) {
                            val len = headerReader.readValueLength()
                            if (len > 0) headerReader.skip(len)
                        } else if (peek == 31) {
                            headerReader.readUnsignedByte()
                            val len = headerReader.readUintvar()
                            if (len > 0) headerReader.skip(len)
                        } else if (peek in 32..127) {
                            headerReader.readTextString()
                        } else {
                            headerReader.readUnsignedByte()
                        }
                    }
                }
            }

            val data = reader.readBytes(dataLen)
            val fileName = params["name"] ?: params["filename"] ?: contentLocation
            val charset = params["charset"]?.toIntOrNull() ?: CHARSET_UTF8

            parts.add(
                MmsPart(
                    contentType = contentType,
                    contentId = contentId,
                    contentLocation = contentLocation,
                    fileName = fileName,
                    charset = charset,
                    data = data
                )
            )
        }
        return parts
    }

    private fun formatMmsAddress(address: String): String {
        val trimmed = address.trim()
        if (trimmed.contains("/TYPE=", ignoreCase = true) || trimmed.contains('@')) {
            return trimmed
        }
        return "$trimmed/TYPE=PLMN"
    }

    private fun stripMmsAddressSuffix(address: String): String {
        val idx = address.indexOf("/TYPE=", ignoreCase = true)
        return (if (idx >= 0) address.substring(0, idx) else address).trim()
    }

    private fun writeTextString(out: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) >= 0x80) {
            out.write(0x7F) // Quote prefix for text-string starting >= 0x80
        }
        out.write(bytes)
        out.write(0)
    }

    private fun writeQuotedString(out: ByteArrayOutputStream, text: String) {
        out.write('"'.code)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.write(0)
    }

    private fun writeEncodedStringValue(out: ByteArrayOutputStream, text: String) {
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        val hasNonAscii = utf8Bytes.any { (it.toInt() and 0x80) != 0 }
        if (!hasNonAscii) {
            writeTextString(out, text)
            return
        }
        // Value-length + Charset(UTF-8 = 0x6A -> short-int 0xEA) + Text-string
        val payload = ByteArrayOutputStream()
        payload.write(CHARSET_UTF8 or 0x80)
        writeTextString(payload, text)
        val raw = payload.toByteArray()
        writeValueLength(out, raw.size)
        out.write(raw)
    }

    private fun writeLongInteger(out: ByteArrayOutputStream, value: Long) {
        if (value == 0L) {
            out.write(1)
            out.write(0)
            return
        }
        val bytes = mutableListOf<Int>()
        var remaining = value
        while (remaining > 0L) {
            bytes.add((remaining and 0xFFL).toInt())
            remaining = remaining ushr 8
        }
        out.write(bytes.size)
        for (i in bytes.indices.reversed()) {
            out.write(bytes[i])
        }
    }

    private fun writeValueLength(out: ByteArrayOutputStream, length: Int) {
        if (length <= 30) {
            out.write(length)
        } else {
            out.write(31)
            writeUintvar(out, length)
        }
    }

    private fun writeUintvar(out: ByteArrayOutputStream, value: Int) {
        var v = value.coerceAtLeast(0)
        val chunks = IntArray(5)
        var count = 0
        do {
            chunks[count++] = v and 0x7F
            v = v ushr 7
        } while (v > 0 && count < 5)

        for (i in (count - 1) downTo 0) {
            val b = chunks[i] or if (i > 0) 0x80 else 0
            out.write(b)
        }
    }

    private class PduReader(
        private val data: ByteArray,
        private var pos: Int = 0,
        private val limit: Int = data.size
    ) {
        fun hasRemaining(): Boolean = pos < limit

        fun peekUnsignedByte(): Int? = if (pos < limit) data[pos].toInt() and 0xFF else null

        fun readUnsignedByte(): Int? = if (pos < limit) data[pos++].toInt() and 0xFF else null

        fun skip(count: Int) {
            pos = (pos + count).coerceAtMost(limit)
        }

        fun slice(length: Int): PduReader {
            val safeLen = length.coerceAtLeast(0).coerceAtMost(limit - pos)
            val start = pos
            pos += safeLen
            return PduReader(data, start, start + safeLen)
        }

        fun readBytes(length: Int): ByteArray {
            val safeLen = length.coerceAtLeast(0).coerceAtMost(limit - pos)
            val copy = data.copyOfRange(pos, pos + safeLen)
            pos += safeLen
            return copy
        }

        fun readUintvar(): Int {
            var result = 0
            var count = 0
            while (pos < limit && count < 5) {
                val b = data[pos++].toInt() and 0xFF
                result = (result shl 7) or (b and 0x7F)
                count++
                if ((b and 0x80) == 0) break
            }
            return result
        }

        fun readValueLength(): Int {
            val first = readUnsignedByte() ?: return 0
            return when {
                first <= 30 -> first
                first == 31 -> readUintvar()
                else -> 0
            }
        }

        fun readLongInteger(): Long {
            val len = (readUnsignedByte() ?: return 0L).coerceIn(0, 8)
            var result = 0L
            repeat(len) {
                val b = readUnsignedByte() ?: return result
                result = (result shl 8) or b.toLong()
            }
            return result
        }

        fun readTextString(charset: Charset = Charsets.UTF_8): String {
            if (pos >= limit) return ""
            val first = data[pos].toInt() and 0xFF
            if (first == 0x7F || first == '"'.code) {
                pos++
            }
            val start = pos
            while (pos < limit && data[pos] != 0.toByte()) {
                pos++
            }
            val end = pos
            if (pos < limit && data[pos] == 0.toByte()) {
                pos++
            }
            return if (end > start) String(data, start, end - start, charset) else ""
        }

        fun readEncodedStringValue(): String {
            val peek = peekUnsignedByte() ?: return ""
            if (peek >= 32) {
                return readTextString()
            }
            val len = readValueLength()
            if (len <= 0) return ""
            val sub = slice(len)
            val charsetToken = sub.peekUnsignedByte() ?: return ""
            val charset = if (charsetToken >= 0x80) {
                val mib = (sub.readUnsignedByte() ?: 0xEA) and 0x7F
                resolveCharset(mib)
            } else if (charsetToken < 32) {
                val intVal = sub.readIntegerValue()
                resolveCharset(intVal)
            } else {
                Charsets.UTF_8
            }
            return sub.readTextString(charset)
        }

        fun readIntegerValue(): Int {
            val peek = peekUnsignedByte() ?: return 0
            return if (peek >= 0x80) {
                (readUnsignedByte() ?: 0x80) and 0x7F
            } else {
                readLongInteger().toInt()
            }
        }

        fun readContentType(): Pair<String, Map<String, String>> {
            val peek = peekUnsignedByte() ?: return "application/octet-stream" to emptyMap()
            return when {
                peek >= 0x80 -> {
                    val code = (readUnsignedByte() ?: 0x80) and 0x7F
                    val mime = WELL_KNOWN_CONTENT_TYPES[code] ?: "application/octet-stream"
                    mime to emptyMap()
                }
                peek in 32..127 -> {
                    val mime = readTextString().ifBlank { "application/octet-stream" }
                    mime to emptyMap()
                }
                else -> {
                    val len = readValueLength()
                    val sub = slice(len)
                    val first = sub.peekUnsignedByte() ?: return "application/octet-stream" to emptyMap()
                    val mime = if (first >= 0x80) {
                        val code = (sub.readUnsignedByte() ?: 0x80) and 0x7F
                        WELL_KNOWN_CONTENT_TYPES[code] ?: "application/octet-stream"
                    } else {
                        sub.readTextString().ifBlank { "application/octet-stream" }
                    }
                    val params = mutableMapOf<String, String>()
                    while (sub.hasRemaining()) {
                        val p = sub.readUnsignedByte() ?: break
                        val pCode = p and 0x7F
                        when (pCode) {
                            (PARAM_CHARSET and 0x7F) -> {
                                val cs = sub.readIntegerValue()
                                params["charset"] = cs.toString()
                            }
                            (PARAM_NAME and 0x7F) -> {
                                params["name"] = sub.readTextString()
                            }
                            (PARAM_FILENAME and 0x7F) -> {
                                params["filename"] = sub.readTextString()
                            }
                            else -> {
                                val vPeek = sub.peekUnsignedByte() ?: break
                                if (vPeek in 32..127) {
                                    sub.readTextString()
                                } else if (vPeek >= 0x80) {
                                    sub.readUnsignedByte()
                                } else {
                                    sub.readIntegerValue()
                                }
                            }
                        }
                    }
                    mime to params
                }
            }
        }

        private fun resolveCharset(mibEnum: Int): Charset {
            val name = WELL_KNOWN_CHARSETS[mibEnum] ?: "UTF-8"
            return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }
    }
}
