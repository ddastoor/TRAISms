package com.example.data.crypto

import com.example.data.db.SmsCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupMessageRecord(
    val threadKey: String,
    val rawAddress: String,
    val contactName: String? = null,
    val body: String,
    val bodySha256: String,
    val timestampMillis: Long,
    val isIncoming: Boolean,
    val isRead: Boolean,
    val isArchived: Boolean,
    val category: SmsCategory,
    val extractedOtp: String? = null,
    val confidenceScore: Float = 1.0f,
    val classificationSource: String = "RESTORED",
    val transportType: String = "SMS",
    val mmsSubject: String? = null,
    val replyToSender: String? = null,
    val replyToSnippet: String? = null,
    val reactionsJson: String? = null,
    val groupRecipientsJson: String? = null
)

@Serializable
data class BackupSenderRuleRecord(
    val normalizedSender: String,
    val displaySender: String,
    val category: SmsCategory,
    val updatedAtMillis: Long
)

@Serializable
data class EncryptedBackupPayload(
    val formatVersion: Int = 1,
    val createdAtMillis: Long,
    val messages: List<BackupMessageRecord>,
    val senderRules: List<BackupSenderRuleRecord>
)

/**
 * Handles PBKDF2-HMAC-SHA256 (600,000 iterations) + GZIP + AES-256-GCM encryption
 * and integrity-verified decryption for `.trai` backup archives.
 */
object BackupCryptoEngine {

    private val MAGIC_HEADER = byteArrayOf('T'.code.toByte(), 'R'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte(), 1)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_LENGTH_BITS = 256
    private const val MAX_DECOMPRESSED_BYTES = 128 * 1024 * 1024 // 128 MB zip-bomb safety cap

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun encryptPayload(payload: EncryptedBackupPayload, passphrase: String): ByteArray {
        require(passphrase.isNotBlank()) { "Backup passphrase must not be empty." }

        val jsonBytes = json.encodeToString(EncryptedBackupPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)

        val compressedBytes = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(jsonBytes)
            }
            baos.toByteArray()
        }

        val secureRandom = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }

        val secretKey = deriveAesKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(MAGIC_HEADER)
        val cipherTextWithTag = cipher.doFinal(compressedBytes)

        val out = ByteArrayOutputStream(MAGIC_HEADER.size + SALT_BYTES + IV_BYTES + cipherTextWithTag.size)
        out.write(MAGIC_HEADER)
        out.write(salt)
        out.write(iv)
        out.write(cipherTextWithTag)
        return out.toByteArray()
    }

    fun decryptPayload(encryptedArchive: ByteArray, passphrase: String): EncryptedBackupPayload {
        require(passphrase.isNotBlank()) { "Passphrase is required to unlock backup." }
        val minSize = MAGIC_HEADER.size + SALT_BYTES + IV_BYTES + 16
        require(encryptedArchive.size >= minSize) { "Invalid or truncated .trai backup file." }

        for (i in MAGIC_HEADER.indices) {
            if (encryptedArchive[i] != MAGIC_HEADER[i]) {
                throw IllegalArgumentException("Unrecognized file header. Please select a valid .trai backup file.")
            }
        }

        val saltStart = MAGIC_HEADER.size
        val ivStart = saltStart + SALT_BYTES
        val payloadStart = ivStart + IV_BYTES

        val salt = encryptedArchive.copyOfRange(saltStart, ivStart)
        val iv = encryptedArchive.copyOfRange(ivStart, payloadStart)
        val cipherTextWithTag = encryptedArchive.copyOfRange(payloadStart, encryptedArchive.size)

        val secretKey = deriveAesKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(MAGIC_HEADER)

        val compressedBytes = try {
            cipher.doFinal(cipherTextWithTag)
        } catch (e: Exception) {
            throw SecurityException("Incorrect backup password or corrupted archive (AES-GCM verification failed).", e)
        }

        val decompressedBytes = ByteArrayOutputStream().use { baos ->
            GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { gzip ->
                val buffer = ByteArray(8192)
                var totalRead = 0
                var read = gzip.read(buffer)
                while (read != -1) {
                    totalRead += read
                    if (totalRead > MAX_DECOMPRESSED_BYTES) {
                        throw SecurityException("Backup exceeds maximum safe decompressed size.")
                    }
                    baos.write(buffer, 0, read)
                    read = gzip.read(buffer)
                }
            }
            baos.toByteArray()
        }

        return json.decodeFromString(
            EncryptedBackupPayload.serializer(),
            decompressedBytes.toString(Charsets.UTF_8)
        )
    }

    private fun deriveAesKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = factory.generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}
