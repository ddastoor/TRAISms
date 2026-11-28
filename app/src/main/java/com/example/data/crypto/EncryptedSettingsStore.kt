package com.example.data.crypto

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.example.data.db.SmsCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@Serializable
enum class LeftSwipeAction(val label: String) {
    ARCHIVE("Archive"),
    DELETE("Delete"),
    ASSIGN_CATEGORY("Assign Category")
}

typealias SwipeAction = LeftSwipeAction

@Serializable
enum class SmsFontSize(val label: String) {
    DEFAULT("Default"),
    LARGE("Large"),
    LARGER("Larger")
}

@Serializable
enum class BackupSchedule(val label: String, val intervalDays: Int) {
    OFF("Off", 0),
    DAILY("Daily", 1),
    EVERY_3_DAYS("Every 3 Days", 3),
    WEEKLY("Weekly", 7),
    MONTHLY("Monthly", 30),
    CUSTOM("Custom (N Days)", -1)
}

@Serializable
data class AppSettings(
    val promoAutoDeleteDays: Int = 7, // Default: 7 Days (0 = Off, 1, 3, 7, 15, 30)
    val otpAutoDeleteDays: Int = 0, // Default: 0 = Off; options: 0, 1, 3, 7, 15, 30
    val leftSwipeAction: LeftSwipeAction = LeftSwipeAction.ARCHIVE,
    val unarchiveOnNewSms: Boolean = true,
    val regionOverride: String = "AUTO", // AUTO, IN, US, GB, CA, EU_EN, GLOBAL_EN
    val backupSchedule: BackupSchedule = BackupSchedule.OFF,
    val customBackupDays: Int = 7,
    val persistedDriveUri: String = "",
    val driveFileName: String = "",
    val backupPassphrase: String = "",
    val lastBackupEpochMillis: Long = 0L,
    val lastBackupStatus: String = "No backup created yet",
    val lastBackupCount: Int = 0,
    val appLockEnabled: Boolean = false, // OFF by default
    val initialSyncCompleted: Boolean = false,
    val onboardingDismissed: Boolean = false,
    val smsFontSize: SmsFontSize = SmsFontSize.DEFAULT,
    val smsFontBold: Boolean = false,
    val categoryShowUnreadOnly: Map<String, Boolean> = emptyMap(), // Per-tab Show Unread toggle; absent keys default to true = Unread Only
    val rightSwipeAction: LeftSwipeAction = LeftSwipeAction.ASSIGN_CATEGORY
) {
    fun isCategoryUnreadOnly(category: SmsCategory): Boolean =
        categoryShowUnreadOnly[category.name] ?: true
}

/**
 * Tink AEAD Serializer for Jetpack DataStore so sensitive settings, SAF URIs,
 * and unattended auto-backup passphrases are encrypted at rest with AES-256-GCM.
 */
class TinkAppSettingsSerializer(
    private val tinkKeyManager: TinkKeyManager
) : Serializer<AppSettings> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        val encryptedBytes = input.readBytes()
        if (encryptedBytes.isEmpty()) return defaultValue
        return try {
            val decryptedBytes = tinkKeyManager.getAead().decrypt(encryptedBytes, ASSOCIATED_DATA)
            json.decodeFromString(AppSettings.serializer(), decryptedBytes.decodeToString())
        } catch (_: Exception) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        val jsonBytes = json.encodeToString(AppSettings.serializer(), t).encodeToByteArray()
        val encryptedBytes = tinkKeyManager.getAead().encrypt(jsonBytes, ASSOCIATED_DATA)
        output.write(encryptedBytes)
    }

    companion object {
        private val ASSOCIATED_DATA = "traisms_encrypted_datastore_v1".toByteArray(Charsets.UTF_8)
    }
}

class EncryptedSettingsStore(
    context: Context,
    tinkKeyManager: TinkKeyManager
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore: DataStore<AppSettings> = DataStoreFactory.create(
        serializer = TinkAppSettingsSerializer(tinkKeyManager),
        scope = scope,
        produceFile = { File(appContext.noBackupFilesDir, "traisms_secure_settings.pb") }
    )

    val settingsFlow: Flow<AppSettings> = dataStore.data

    suspend fun getSettingsSnapshot(): AppSettings = dataStore.data.first()

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.updateData(transform)
    }
}
