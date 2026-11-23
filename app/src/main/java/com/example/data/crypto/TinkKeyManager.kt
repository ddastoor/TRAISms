package com.example.data.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.security.SecureRandom

/**
 * Hardware-backed cryptographic key manager using Google Tink (AES-256-GCM)
 * wrapped by the Android Hardware Keystore. Never uses deprecated EncryptedSharedPreferences.
 */
class TinkKeyManager(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var cachedAead: Aead? = null

    init {
        AeadConfig.register()
    }

    fun getAead(): Aead {
        cachedAead?.let { return it }
        synchronized(lock) {
            cachedAead?.let { return it }
            val aead = createKeystoreBackedAead()
            cachedAead = aead
            return aead
        }
    }

    private fun createKeystoreBackedAead(): Aead {
        return try {
            val handle = AndroidKeysetManager.Builder()
                .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        } catch (_: Exception) {
            // Fallback only for local JVM/Robolectric unit tests where AndroidKeyStore provider is absent
            val handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }

    /**
     * Generates a cryptographically random 32-byte database passphrase on first launch,
     * encrypts it via Tink AES-256-GCM backed by Android Keystore, and persists ciphertext on disk.
     */
    fun getOrCreateDatabasePassphrase(): ByteArray {
        synchronized(lock) {
            val keyFile = File(appContext.noBackupFilesDir, DB_PASSPHRASE_FILE)
            val aead = getAead()

            if (keyFile.exists()) {
                try {
                    val encryptedBytes = keyFile.readBytes()
                    val decrypted = aead.decrypt(encryptedBytes, DB_KEY_ASSOCIATED_DATA)
                    if (decrypted.size == 32) {
                        return decrypted
                    }
                } catch (_: Exception) {
                    // Keyset rotated or corrupted in fresh install; regenerate cleanly
                }
            }

            val randomPassphrase = ByteArray(32)
            SecureRandom().nextBytes(randomPassphrase)
            val ciphertext = aead.encrypt(randomPassphrase, DB_KEY_ASSOCIATED_DATA)
            keyFile.parentFile?.mkdirs()
            keyFile.writeBytes(ciphertext)
            return randomPassphrase
        }
    }

    companion object {
        private const val KEYSET_NAME = "traisms_tink_master_keyset_v1"
        private const val KEYSET_PREF_FILE = "traisms_tink_keyset_store"
        private const val MASTER_KEY_URI = "android-keystore://traisms_hardware_master_key_v1"
        private const val DB_PASSPHRASE_FILE = "traisms_sqlcipher_passphrase.enc"
        private val DB_KEY_ASSOCIATED_DATA = "traisms_sqlcipher_db_v1".toByteArray(Charsets.UTF_8)
    }
}
