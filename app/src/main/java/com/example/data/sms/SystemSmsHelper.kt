package com.example.data.sms

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

data class RawSystemSms(
    val systemId: Long,
    val address: String,
    val body: String,
    val timestampMillis: Long,
    val isIncoming: Boolean,
    val isRead: Boolean
)

/**
 * Encapsulates OS-level Default SMS Role checks, `Telephony.Sms` ContentProvider
 * reads/writes/deletes, and `SmsManager.sendMultipartTextMessage` dispatch.
 */
class SystemSmsHelper(private val context: Context) {

    private val appContext = context.applicationContext

    fun isDefaultSmsApp(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = appContext.getSystemService(RoleManager::class.java)
                roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
            } else {
                Telephony.Sms.getDefaultSmsPackage(appContext) == appContext.packageName
            }
        } catch (_: Exception) {
            false
        }
    }

    fun createDefaultSmsRequestIntent(): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = appContext.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                } else {
                    null
                }
            } else {
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, appContext.packageName)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns true when TRAISms appears to have been sideloaded outside a trusted app store
     * (e.g. GitHub Releases APK via browser or file manager).
     *
     * Uses strictly public SDK APIs guarded across `minSdk 24` through `compileSdk 36.1`.
     */
    fun isSideloadedInstall(): Boolean {
        return runCatching {
            val pm = appContext.packageManager
            val packageName = appContext.packageName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sourceInfo = pm.getInstallSourceInfo(packageName)
                when (sourceInfo.packageSource) {
                    PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE,
                    PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> true
                    PackageInstaller.PACKAGE_SOURCE_STORE -> false
                    else -> sourceInfo.installingPackageName !in TRUSTED_STORE_INSTALLERS
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val installer = pm.getInstallSourceInfo(packageName).installingPackageName
                installer !in TRUSTED_STORE_INSTALLERS
            } else {
                @Suppress("DEPRECATION")
                val installer = pm.getInstallerPackageName(packageName)
                installer !in TRUSTED_STORE_INSTALLERS
            }
        }.getOrDefault(false)
    }

    /**
     * Returns true on Android 15+ (API 35+) when TRAISms is sideloaded and does not yet hold
     * `ROLE_SMS` — the condition under which Android Enhanced Confirmation Mode (ECM) blocks
     * Default SMS assignment until "Allow restricted settings" is enabled in App Info.
     *
     * Uses public SDK APIs only (`EnhancedConfirmationManager` is `@SystemApi` and privileged).
     */
    fun isSmsRoleRestricted(): Boolean {
        return !isDefaultSmsApp() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            isSideloadedInstall()
    }

    /**
     * Builds an Intent for Android's System App Info screen for TRAISms
     * (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`), where users on Android 13–16 tap
     * `⋮ → Allow restricted settings`.
     */
    fun buildAppInfoSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", appContext.packageName, null)
        }
    }

    /**
     * Builds an Intent for Android's Default Apps settings screen
     * (`Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, API 24+).
     */
    fun buildDefaultAppsSettingsIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    fun hasReadSmsPermission(): Boolean {
        return isDefaultSmsApp() || ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasSendSmsPermission(): Boolean {
        return isDefaultSmsApp() || ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isRunningOnEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val device = Build.DEVICE.lowercase()
        return fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            fingerprint.contains("test-keys") ||
            model.contains("sdk") ||
            model.contains("emulator") ||
            model.contains("gphone") ||
            model.contains("cuttlefish") ||
            model.contains("android sdk") ||
            product.contains("sdk") ||
            product.contains("cutf") ||
            product.contains("cf_") ||
            product.contains("vsoc") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("cutf") ||
            hardware.contains("vsoc") ||
            board.contains("cutf") ||
            board.contains("vsoc") ||
            device.contains("vsoc") ||
            device.contains("emu")
    }

    fun getSystemSmsCount(): Int {
        if (!hasReadSmsPermission()) return 0
        return runCatching {
            appContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null,
                null,
                null
            )?.use { it.count } ?: 0
        }.getOrDefault(0)
    }

    /**
     * Streams existing device SMS messages in chunks of [batchSize] (default 500)
     * from `content://sms` without loading the entire cursor into memory at once.
     */
    suspend fun readSystemSmsInBatches(
        batchSize: Int = 500,
        skipIds: Set<Long>,
        onBatch: suspend (List<RawSystemSms>) -> Unit
    ) {
        if (!hasReadSmsPermission()) return

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )

        val buffer = ArrayList<RawSystemSms>(batchSize)
        appContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)

            while (cursor.moveToNext()) {
                val sysId = cursor.getLong(idIdx)
                if (sysId in skipIds) continue
                val address = cursor.getString(addrIdx)?.trim().orEmpty()
                val body = cursor.getString(bodyIdx).orEmpty()
                if (address.isEmpty() && body.isEmpty()) continue

                val date = cursor.getLong(dateIdx).takeIf { it > 0L } ?: System.currentTimeMillis()
                val type = cursor.getInt(typeIdx)
                val isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX || type == Telephony.Sms.MESSAGE_TYPE_ALL
                val isRead = cursor.getInt(readIdx) == 1 || !isIncoming

                buffer.add(
                    RawSystemSms(
                        systemId = sysId,
                        address = address.ifEmpty { "UNKNOWN" },
                        body = body,
                        timestampMillis = date,
                        isIncoming = isIncoming,
                        isRead = isRead
                    )
                )

                if (buffer.size >= batchSize) {
                    onBatch(buffer.toList())
                    buffer.clear()
                }
            }
        }

        if (buffer.isNotEmpty()) {
            onBatch(buffer.toList())
            buffer.clear()
        }
    }

    fun insertIntoSystemProvider(
        address: String,
        body: String,
        timestampMillis: Long,
        isIncoming: Boolean,
        isRead: Boolean
    ): Long? {
        if (!isDefaultSmsApp()) return null
        return runCatching {
            val targetUri = if (isIncoming) Telephony.Sms.Inbox.CONTENT_URI else Telephony.Sms.Sent.CONTENT_URI
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestampMillis)
                put(Telephony.Sms.READ, if (isRead) 1 else 0)
                put(Telephony.Sms.SEEN, 1)
                put(
                    Telephony.Sms.TYPE,
                    if (isIncoming) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT
                )
            }
            val insertedUri: Uri? = appContext.contentResolver.insert(targetUri, values)
            insertedUri?.lastPathSegment?.toLongOrNull()
        }.getOrNull()
    }

    fun markSystemMessagesRead(systemIds: List<Long>) {
        if (!isDefaultSmsApp() || systemIds.isEmpty()) return
        runCatching {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            for (chunk in systemIds.chunked(200)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                appContext.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "${Telephony.Sms._ID} IN ($placeholders)",
                    args
                )
            }
        }
    }

    fun deleteFromSystemProvider(systemIds: List<Long>) {
        if (!isDefaultSmsApp() || systemIds.isEmpty()) return
        runCatching {
            for (chunk in systemIds.chunked(200)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                appContext.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms._ID} IN ($placeholders)",
                    args
                )
            }
        }
    }

    fun wipeAllFromSystemProvider() {
        if (!isDefaultSmsApp()) return
        runCatching {
            appContext.contentResolver.delete(Telephony.Sms.CONTENT_URI, null, null)
        }
    }

    fun sendMultipartSms(destinationAddress: String, messageText: String): Result<Unit> {
        if (!hasSendSmsPermission()) {
            return Result.failure(SecurityException("SEND_SMS permission or Default SMS role is required to transmit carrier SMS."))
        }
        return runCatching {
            val smsManager = appContext.getSystemService(SmsManager::class.java)
                ?: throw IllegalStateException("Device SmsManager unavailable.")
            val parts = smsManager.divideMessage(messageText)
            smsManager.sendMultipartTextMessage(
                destinationAddress,
                null,
                parts,
                null,
                null
            )
        }
    }

    companion object {
        private val TRUSTED_STORE_INSTALLERS = setOf(
            "com.android.vending",
            "com.amazon.venezia",
            "com.sec.android.app.samsungapps"
        )
    }
}
