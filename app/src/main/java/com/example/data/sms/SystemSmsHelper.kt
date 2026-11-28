package com.example.data.sms

import android.Manifest
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

data class RawSystemSms(
    val systemId: Long,
    val address: String,
    val body: String,
    val timestampMillis: Long,
    val isIncoming: Boolean,
    val isRead: Boolean
)

data class PendingMmsAttachment(
    val uri: Uri? = null,
    val contentType: String,
    val fileName: String,
    val localFilePath: String? = null,
    val sizeBytes: Long = 0L,
    val textContent: String? = null
)

data class RawMmsPart(
    val partIndex: Int,
    val contentType: String,
    val fileName: String?,
    val localFilePath: String?,
    val contentId: String?,
    val sizeBytes: Long,
    val textContent: String?
)

data class RawSystemMms(
    val systemMmsId: Long,
    val threadId: Long,
    val fromAddress: String,
    val allAddresses: List<String>,
    val body: String,
    val subject: String?,
    val timestampMillis: Long,
    val isIncoming: Boolean,
    val isRead: Boolean,
    val isRcsBridged: Boolean,
    val parts: List<RawMmsPart>
)

/**
 * Encapsulates OS-level Default SMS Role checks, `Telephony.Sms` & `Telephony.Mms` ContentProvider
 * reads/writes/deletes, `SmsManager.sendMultipartTextMessage`, and OS-brokered carrier
 * `SmsManager.sendMultimediaMessage` / `downloadMultimediaMessage` via FileProvider.
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
                    createFallbackDefaultSmsIntent()
                }
            } else {
                createFallbackDefaultSmsIntent()
            }
        } catch (_: Exception) {
            createFallbackDefaultSmsIntent()
        }
    }

    fun createFallbackDefaultSmsIntent(): Intent {
        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, appContext.packageName)
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

    /**
     * Streams existing device MMS & RCS-bridged MMS messages in chunks of [batchSize]
     * from `content://mms`, `content://mms/<id>/addr`, and `content://mms/part`.
     */
    suspend fun readSystemMmsInBatches(
        batchSize: Int = 100,
        skipMmsIds: Set<Long>,
        onBatch: suspend (List<RawSystemMms>) -> Unit
    ) {
        if (!hasReadSmsPermission()) return

        val projection = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.DATE,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.READ,
            Telephony.Mms.SUBJECT,
            Telephony.Mms.CREATOR
        )

        val buffer = ArrayList<RawSystemMms>(batchSize)
        runCatching {
            appContext.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Mms.DATE} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Telephony.Mms._ID)
                val threadIdx = cursor.getColumnIndex(Telephony.Mms.THREAD_ID)
                val dateIdx = cursor.getColumnIndex(Telephony.Mms.DATE)
                val boxIdx = cursor.getColumnIndex(Telephony.Mms.MESSAGE_BOX)
                val readIdx = cursor.getColumnIndex(Telephony.Mms.READ)
                val subIdx = cursor.getColumnIndex(Telephony.Mms.SUBJECT)
                val creatorIdx = cursor.getColumnIndex(Telephony.Mms.CREATOR)

                while (cursor.moveToNext()) {
                    if (idIdx < 0) continue
                    val mmsId = cursor.getLong(idIdx)
                    if (mmsId in skipMmsIds) continue

                    val threadId = if (threadIdx >= 0) cursor.getLong(threadIdx) else 0L
                    val rawDate = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    val timestampMillis = when {
                        rawDate <= 0L -> System.currentTimeMillis()
                        rawDate < 10_000_000_000L -> rawDate * 1000L
                        else -> rawDate
                    }
                    val box = if (boxIdx >= 0) cursor.getInt(boxIdx) else Telephony.Mms.MESSAGE_BOX_INBOX
                    val isIncoming = box == Telephony.Mms.MESSAGE_BOX_INBOX || box == Telephony.Mms.MESSAGE_BOX_ALL
                    val isRead = (if (readIdx >= 0) cursor.getInt(readIdx) == 1 else true) || !isIncoming
                    val subject = if (subIdx >= 0) cursor.getString(subIdx)?.trim()?.takeIf { it.isNotEmpty() } else null
                    val creator = if (creatorIdx >= 0) cursor.getString(creatorIdx).orEmpty() else ""

                    val (fromAddr, allAddrs) = readMmsAddresses(mmsId)
                    val (bodyText, parts, hasRcsPart) = readMmsParts(mmsId)
                    if (allAddrs.isEmpty() && bodyText.isEmpty() && parts.isEmpty()) continue

                    val isRcs = creator.contains("rcs", ignoreCase = true) ||
                        creator.contains("messaging", ignoreCase = true) && hasRcsPart ||
                        hasRcsPart

                    buffer.add(
                        RawSystemMms(
                            systemMmsId = mmsId,
                            threadId = threadId,
                            fromAddress = fromAddr.ifEmpty { allAddrs.firstOrNull() ?: "UNKNOWN" },
                            allAddresses = allAddrs.ifEmpty { listOf(fromAddr.ifEmpty { "UNKNOWN" }) },
                            body = bodyText,
                            subject = subject,
                            timestampMillis = timestampMillis,
                            isIncoming = isIncoming,
                            isRead = isRead,
                            isRcsBridged = isRcs,
                            parts = parts
                        )
                    )

                    if (buffer.size >= batchSize) {
                        onBatch(buffer.toList())
                        buffer.clear()
                    }
                }
            }
        }

        if (buffer.isNotEmpty()) {
            onBatch(buffer.toList())
            buffer.clear()
        }
    }

    private fun readMmsAddresses(mmsId: Long): Pair<String, List<String>> {
        var fromAddress = ""
        val allAddresses = LinkedHashSet<String>()
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        runCatching {
            appContext.contentResolver.query(
                addrUri,
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                null,
                null,
                null
            )?.use { c ->
                val addrCol = c.getColumnIndex(Telephony.Mms.Addr.ADDRESS)
                val typeCol = c.getColumnIndex(Telephony.Mms.Addr.TYPE)
                while (c.moveToNext()) {
                    val addr = if (addrCol >= 0) c.getString(addrCol)?.trim().orEmpty() else ""
                    val type = if (typeCol >= 0) c.getInt(typeCol) else 0
                    if (addr.isEmpty() || addr.equals("insert-address-token", ignoreCase = true)) continue
                    if (type == 137) {
                        fromAddress = addr
                    }
                    allAddresses.add(addr)
                }
            }
        }
        return fromAddress to allAddresses.toList()
    }

    private data class ReadPartsOutcome(
        val bodyText: String,
        val parts: List<RawMmsPart>,
        val hasRcsPart: Boolean
    )

    private fun readMmsParts(mmsId: Long): ReadPartsOutcome {
        val partUri = Uri.parse("content://mms/part")
        val textParts = mutableListOf<String>()
        val mediaParts = mutableListOf<RawMmsPart>()
        var hasRcs = false
        var idx = 0

        runCatching {
            appContext.contentResolver.query(
                partUri,
                arrayOf(
                    Telephony.Mms.Part._ID,
                    Telephony.Mms.Part.CONTENT_TYPE,
                    Telephony.Mms.Part.NAME,
                    Telephony.Mms.Part.CONTENT_LOCATION,
                    Telephony.Mms.Part.CONTENT_ID,
                    Telephony.Mms.Part.TEXT
                ),
                "${Telephony.Mms.Part.MSG_ID} = ?",
                arrayOf(mmsId.toString()),
                null
            )?.use { c ->
                val idCol = c.getColumnIndex(Telephony.Mms.Part._ID)
                val ctCol = c.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                val nameCol = c.getColumnIndex(Telephony.Mms.Part.NAME)
                val clCol = c.getColumnIndex(Telephony.Mms.Part.CONTENT_LOCATION)
                val cidCol = c.getColumnIndex(Telephony.Mms.Part.CONTENT_ID)
                val textCol = c.getColumnIndex(Telephony.Mms.Part.TEXT)

                while (c.moveToNext()) {
                    val partId = if (idCol >= 0) c.getLong(idCol) else continue
                    val ct = (if (ctCol >= 0) c.getString(ctCol) else null)?.trim()
                        ?.ifEmpty { "application/octet-stream" } ?: "application/octet-stream"
                    if (ct.contains("rcs", ignoreCase = true) || ct.contains("gsma", ignoreCase = true)) {
                        hasRcs = true
                    }
                    if (ct.equals("application/smil", ignoreCase = true)) continue

                    val name = (if (nameCol >= 0) c.getString(nameCol) else null)?.trim()
                        ?: (if (clCol >= 0) c.getString(clCol) else null)?.trim()
                    val cid = if (cidCol >= 0) c.getString(cidCol) else null

                    if (ct.equals("text/plain", ignoreCase = true)) {
                        val inlineText = if (textCol >= 0) c.getString(textCol) else null
                        val resolvedText = inlineText?.takeIf { it.isNotEmpty() } ?: runCatching {
                            appContext.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
                                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        }.getOrNull()
                        if (!resolvedText.isNullOrBlank()) {
                            textParts.add(resolvedText)
                        }
                    } else {
                        val savedFile = copyPartStreamToMediaDir(partId, ct, name)
                        val textPreview = if (ct.contains("vcard", ignoreCase = true) && savedFile != null) {
                            runCatching { savedFile.readText(Charsets.UTF_8) }.getOrNull()
                        } else null
                        mediaParts.add(
                            RawMmsPart(
                                partIndex = idx++,
                                contentType = ct,
                                fileName = name ?: savedFile?.name,
                                localFilePath = savedFile?.absolutePath,
                                contentId = cid,
                                sizeBytes = savedFile?.length() ?: 0L,
                                textContent = textPreview
                            )
                        )
                    }
                }
            }
        }
        return ReadPartsOutcome(
            bodyText = textParts.joinToString("\n").trim(),
            parts = mediaParts,
            hasRcsPart = hasRcs
        )
    }

    private fun copyPartStreamToMediaDir(partId: Long, contentType: String, fileName: String?): File? {
        return runCatching {
            val mediaDir = getMmsMediaDir()
            val safeExt = extensionForMime(contentType, fileName)
            val destFile = File(mediaDir, "sys_part_${partId}_${System.currentTimeMillis()}.$safeExt")
            appContext.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            if (destFile.length() <= 0L) {
                destFile.delete()
                null
            } else {
                destFile
            }
        }.getOrNull()
    }

    fun getMmsMediaDir(): File = File(appContext.noBackupFilesDir, "mms_media").apply { mkdirs() }

    fun getMmsPduCacheDir(): File = File(appContext.cacheDir, "mms_pdu").apply { mkdirs() }

    fun buildFileProviderUri(file: File): Uri {
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.mmsfileprovider",
            file
        )
    }

    fun stageLocalAttachmentFromUri(
        sourceUri: Uri,
        fallbackMime: String,
        fallbackName: String
    ): Result<PendingMmsAttachment> {
        return runCatching {
            val resolver = appContext.contentResolver
            val detectedMime = resolver.getType(sourceUri)?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallbackMime
            var displayName = fallbackName
            resolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { displayName = it }
                }
            }

            val mediaDir = getMmsMediaDir()
            val ext = extensionForMime(detectedMime, displayName)
            val destFile = File(mediaDir, "att_${UUID.randomUUID()}.$ext")
            resolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Unable to read selected attachment.")

            val sizeBytes = destFile.length()
            if (sizeBytes <= 0L) {
                destFile.delete()
                throw IllegalStateException("Selected attachment is empty.")
            }

            val textContent = if (detectedMime.contains("vcard", ignoreCase = true) ||
                detectedMime.equals("text/plain", ignoreCase = true)
            ) {
                runCatching { destFile.readText(Charsets.UTF_8) }.getOrNull()
            } else null

            PendingMmsAttachment(
                uri = buildFileProviderUriIfCacheable(destFile),
                contentType = detectedMime,
                fileName = displayName,
                localFilePath = destFile.absolutePath,
                sizeBytes = sizeBytes,
                textContent = textContent
            )
        }
    }

    fun stageTextAttachment(
        contentType: String,
        fileName: String,
        content: String
    ): Result<PendingMmsAttachment> {
        return runCatching {
            val mediaDir = getMmsMediaDir()
            val ext = extensionForMime(contentType, fileName)
            val destFile = File(mediaDir, "att_${UUID.randomUUID()}.$ext")
            val bytes = content.toByteArray(Charsets.UTF_8)
            destFile.writeBytes(bytes)
            PendingMmsAttachment(
                uri = null,
                contentType = contentType,
                fileName = fileName,
                localFilePath = destFile.absolutePath,
                sizeBytes = bytes.size.toLong(),
                textContent = content
            )
        }
    }

    fun persistMmsPartBytes(
        partIndex: Int,
        contentType: String,
        fileName: String?,
        bytes: ByteArray
    ): File? {
        return runCatching {
            val mediaDir = getMmsMediaDir()
            val ext = extensionForMime(contentType, fileName)
            val destFile = File(mediaDir, "mms_${System.currentTimeMillis()}_${partIndex}_${UUID.randomUUID().toString().take(6)}.$ext")
            destFile.writeBytes(bytes)
            destFile
        }.getOrNull()
    }

    private fun buildFileProviderUriIfCacheable(file: File): Uri? {
        return runCatching { Uri.fromFile(file) }.getOrNull()
    }

    private fun extensionForMime(mime: String, fileName: String?): String {
        val fromName = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 1..6 }
        if (fromName != null) return fromName
        return when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "video/3gpp" -> "3gp"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/aac" -> "aac"
            "audio/amr" -> "amr"
            "audio/ogg" -> "ogg"
            "text/x-vcard", "text/vcard" -> "vcf"
            "text/plain" -> "txt"
            "application/pdf" -> "pdf"
            else -> "bin"
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

    fun insertMmsIntoSystemProvider(
        fromAddress: String,
        toAddresses: List<String>,
        subject: String?,
        body: String,
        parts: List<MmsPart> = emptyList(),
        timestampMillis: Long,
        isIncoming: Boolean,
        isRead: Boolean,
        transactionId: String? = null
    ): Long? {
        if (!isDefaultSmsApp()) return null
        return runCatching {
            val targetUri = if (isIncoming) Telephony.Mms.Inbox.CONTENT_URI else Telephony.Mms.Sent.CONTENT_URI
            val values = ContentValues().apply {
                put(Telephony.Mms.DATE, timestampMillis / 1000L)
                put(Telephony.Mms.READ, if (isRead) 1 else 0)
                put(Telephony.Mms.SEEN, 1)
                put(
                    Telephony.Mms.MESSAGE_BOX,
                    if (isIncoming) Telephony.Mms.MESSAGE_BOX_INBOX else Telephony.Mms.MESSAGE_BOX_SENT
                )
                put(Telephony.Mms.MESSAGE_TYPE, if (isIncoming) 132 else 128)
                put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
                subject?.takeIf { it.isNotBlank() }?.let { put(Telephony.Mms.SUBJECT, it) }
                transactionId?.takeIf { it.isNotBlank() }?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            }
            val mmsUri = appContext.contentResolver.insert(targetUri, values) ?: return null
            val mmsId = mmsUri.lastPathSegment?.toLongOrNull() ?: return null

            val addrUri = Uri.parse("content://mms/$mmsId/addr")
            val fromVals = ContentValues().apply {
                put(Telephony.Mms.Addr.ADDRESS, fromAddress.ifBlank { "insert-address-token" })
                put(Telephony.Mms.Addr.TYPE, 137)
                put(Telephony.Mms.Addr.CHARSET, 106)
            }
            appContext.contentResolver.insert(addrUri, fromVals)

            toAddresses.filter { it.isNotBlank() }.forEach { dest ->
                val toVals = ContentValues().apply {
                    put(Telephony.Mms.Addr.ADDRESS, dest)
                    put(Telephony.Mms.Addr.TYPE, 151)
                    put(Telephony.Mms.Addr.CHARSET, 106)
                }
                appContext.contentResolver.insert(addrUri, toVals)
            }

            val partUri = Uri.parse("content://mms/$mmsId/part")
            if (body.isNotBlank()) {
                val textVals = ContentValues().apply {
                    put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
                    put(Telephony.Mms.Part.CHARSET, 106)
                    put(Telephony.Mms.Part.NAME, "body.txt")
                    put(Telephony.Mms.Part.TEXT, body)
                }
                appContext.contentResolver.insert(partUri, textVals)
            }

            for ((idx, part) in parts.withIndex()) {
                if (part.contentType.equals("application/smil", ignoreCase = true) ||
                    part.contentType.equals("text/plain", ignoreCase = true)
                ) continue
                val pVals = ContentValues().apply {
                    put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
                    put(Telephony.Mms.Part.NAME, part.fileName ?: "part_$idx")
                    part.contentLocation?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, it) }
                    part.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, it) }
                }
                val insertedPartUri = appContext.contentResolver.insert(partUri, pVals)
                if (insertedPartUri != null && part.data.isNotEmpty()) {
                    appContext.contentResolver.openOutputStream(insertedPartUri)?.use { out ->
                        out.write(part.data)
                    }
                }
            }
            mmsId
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

    fun markSystemMmsRead(systemMmsIds: List<Long>) {
        if (!isDefaultSmsApp() || systemMmsIds.isEmpty()) return
        runCatching {
            val values = ContentValues().apply { put(Telephony.Mms.READ, 1) }
            for (chunk in systemMmsIds.chunked(200)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                appContext.contentResolver.update(
                    Telephony.Mms.CONTENT_URI,
                    values,
                    "${Telephony.Mms._ID} IN ($placeholders)",
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

    fun deleteMmsFromSystemProvider(systemMmsIds: List<Long>) {
        if (!isDefaultSmsApp() || systemMmsIds.isEmpty()) return
        runCatching {
            for (chunk in systemMmsIds.chunked(200)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                appContext.contentResolver.delete(
                    Telephony.Mms.CONTENT_URI,
                    "${Telephony.Mms._ID} IN ($placeholders)",
                    args
                )
            }
        }
    }

    fun wipeAllFromSystemProvider() {
        if (!isDefaultSmsApp()) return
        runCatching {
            appContext.contentResolver.delete(Telephony.Sms.CONTENT_URI, null, null)
            appContext.contentResolver.delete(Telephony.Mms.CONTENT_URI, null, null)
        }
    }

    fun sendMultipartSms(
        destinationAddress: String,
        messageText: String,
        messageId: Long = -1L,
        requestDeliveryReport: Boolean = true
    ): Result<Unit> {
        if (!hasSendSmsPermission()) {
            return Result.failure(SecurityException("SEND_SMS permission or Default SMS role is required to transmit carrier SMS."))
        }
        return runCatching {
            val smsManager = resolveSmsManager(-1)
                ?: throw IllegalStateException("Device SmsManager unavailable.")
            val parts = smsManager.divideMessage(messageText)

            val immutableFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val sentIntents = if (messageId > 0L) {
                ArrayList<PendingIntent>(parts.size).apply {
                    parts.indices.forEach { idx ->
                        val intent = Intent(appContext, SmsMmsStatusReceiver::class.java).apply {
                            action = SmsMmsStatusReceiver.ACTION_SMS_SENT
                            putExtra(SmsMmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
                        }
                        add(
                            PendingIntent.getBroadcast(
                                appContext,
                                ((messageId.toInt() and 0xFFFF) * 31 + idx) * 2,
                                intent,
                                immutableFlags
                            )
                        )
                    }
                }
            } else null

            val deliveryIntents = if (messageId > 0L && requestDeliveryReport) {
                ArrayList<PendingIntent>(parts.size).apply {
                    parts.indices.forEach { idx ->
                        val intent = Intent(appContext, SmsMmsStatusReceiver::class.java).apply {
                            action = SmsMmsStatusReceiver.ACTION_SMS_DELIVERED
                            putExtra(SmsMmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
                        }
                        add(
                            PendingIntent.getBroadcast(
                                appContext,
                                ((messageId.toInt() and 0xFFFF) * 31 + idx) * 2 + 1,
                                intent,
                                immutableFlags
                            )
                        )
                    }
                }
            } else null

            smsManager.sendMultipartTextMessage(
                destinationAddress,
                null,
                parts,
                sentIntents,
                deliveryIntents
            )
        }
    }

    fun sendMultimediaMessage(
        pduBytes: ByteArray,
        messageId: Long,
        subscriptionId: Int = -1
    ): Result<File> {
        if (!hasSendSmsPermission()) {
            return Result.failure(SecurityException("SEND_SMS / Default SMS role is required to send carrier MMS."))
        }
        return runCatching {
            val smsManager = resolveSmsManager(subscriptionId)
                ?: throw IllegalStateException("Device SmsManager unavailable.")
            val pduFile = File(getMmsPduCacheDir(), "send_req_${messageId}_${System.currentTimeMillis()}.pdu")
            pduFile.writeBytes(pduBytes)
            val contentUri = buildFileProviderUri(pduFile)

            grantCarrierUriPermissions(
                uri = contentUri,
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val sentIntent = Intent(appContext, SmsMmsStatusReceiver::class.java).apply {
                action = SmsMmsStatusReceiver.ACTION_MMS_SENT
                putExtra(SmsMmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
                putExtra(SmsMmsStatusReceiver.EXTRA_PDU_FILE_PATH, pduFile.absolutePath)
                putExtra(SmsMmsStatusReceiver.EXTRA_SUBSCRIPTION_ID, subscriptionId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                (messageId.toInt() and 0xFFFF) * 7 + 3,
                sentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            smsManager.sendMultimediaMessage(
                appContext,
                contentUri,
                null,
                null,
                pendingIntent
            )
            pduFile
        }
    }

    fun downloadMultimediaMessage(
        contentLocation: String,
        messageId: Long,
        transactionId: String,
        subscriptionId: Int = -1
    ): Result<File> {
        if (contentLocation.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing MMS Content-Location URL."))
        }
        return runCatching {
            val smsManager = resolveSmsManager(subscriptionId)
                ?: throw IllegalStateException("Device SmsManager unavailable.")
            val pduFile = File(getMmsPduCacheDir(), "dl_conf_${messageId}_${System.currentTimeMillis()}.pdu")
            if (!pduFile.exists()) {
                pduFile.createNewFile()
            }
            val contentUri = buildFileProviderUri(pduFile)

            grantCarrierUriPermissions(
                uri = contentUri,
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val dlIntent = Intent(appContext, SmsMmsStatusReceiver::class.java).apply {
                action = SmsMmsStatusReceiver.ACTION_MMS_DOWNLOADED
                putExtra(SmsMmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
                putExtra(SmsMmsStatusReceiver.EXTRA_PDU_FILE_PATH, pduFile.absolutePath)
                putExtra(SmsMmsStatusReceiver.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                putExtra("transaction_id", transactionId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                (messageId.toInt() and 0xFFFF) * 7 + 5,
                dlIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            smsManager.downloadMultimediaMessage(
                appContext,
                contentLocation,
                contentUri,
                null,
                pendingIntent
            )
            pduFile
        }
    }

    private fun grantCarrierUriPermissions(uri: Uri, flags: Int) {
        val carrierPackages = listOf("com.android.mms.service", "com.android.phone")
        carrierPackages.forEach { pkg ->
            runCatching { appContext.grantUriPermission(pkg, uri, flags) }
        }
    }

    private fun resolveSmsManager(subscriptionId: Int): SmsManager? {
        val base = appContext.getSystemService(SmsManager::class.java)
        return if (subscriptionId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base?.createForSubscriptionId(subscriptionId) ?: base
        } else {
            base
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
