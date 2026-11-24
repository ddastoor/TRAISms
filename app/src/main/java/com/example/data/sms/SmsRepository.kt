package com.example.data.sms

import android.content.Context
import android.net.Uri
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.crypto.BackupMessageRecord
import com.example.data.crypto.BackupSenderRuleRecord
import com.example.data.crypto.EncryptedBackupPayload
import com.example.data.crypto.EncryptedSettingsStore
import com.example.data.db.CategoryUnreadCount
import com.example.data.db.SenderCategoryRuleEntity
import com.example.data.db.SenderRuleDao
import com.example.data.db.SmsCategory
import com.example.data.db.SmsDao
import com.example.data.db.SmsMessageEntity
import com.example.data.db.ThreadSummary
import com.example.domain.classifier.PhoneAndSenderNormalizer
import com.example.domain.classifier.RegionProfileDetector
import com.example.domain.classifier.SmsCategorizationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncProgressState(
    val isSyncing: Boolean = false,
    val importedCount: Int = 0
)

data class RestoreSummary(
    val insertedCount: Int,
    val skippedDuplicates: Int,
    val restoredRulesCount: Int
)

/**
 * Central offline SMS repository coordinating SQLCipher Room persistence, Android `Telephony.Sms`
 * ContentProvider synchronization, Multi-Layer Categorization, Archive/Swipe rules, and SAF AES-GCM Backups.
 */
class SmsRepository(
    context: Context,
    private val smsDao: SmsDao,
    private val senderRuleDao: SenderRuleDao,
    private val settingsStore: EncryptedSettingsStore,
    private val regionDetector: RegionProfileDetector,
    private val pipeline: SmsCategorizationPipeline,
    val contactCache: ContactLookupCache,
    val systemSmsHelper: SystemSmsHelper,
    private val notificationHelper: SmsNotificationHelper
) {
    private val appContext = context.applicationContext
    private val importMutex = Mutex()

    private val _syncProgress = MutableStateFlow(SyncProgressState())
    val syncProgress: StateFlow<SyncProgressState> = _syncProgress.asStateFlow()

    fun observeCategoryThreads(category: SmsCategory): Flow<List<ThreadSummary>> =
        smsDao.observeCategoryThreads(category)

    fun searchActiveThreads(query: String): Flow<List<ThreadSummary>> =
        smsDao.searchActiveThreads(query)

    fun observeArchivedThreads(): Flow<List<ThreadSummary>> =
        smsDao.observeArchivedThreads()

    fun observeCategoryCounts(): Flow<List<CategoryUnreadCount>> =
        smsDao.observeCategoryCounts()

    fun observeArchivedMessageCount(): Flow<Int> =
        smsDao.observeArchivedMessageCount()

    fun observeTotalMessageCount(): Flow<Int> =
        smsDao.observeTotalMessageCount()

    fun observeThreadMessages(threadKey: String): Flow<List<SmsMessageEntity>> =
        smsDao.observeThreadMessages(threadKey)

    fun observeSenderRules(): Flow<List<SenderCategoryRuleEntity>> =
        senderRuleDao.observeAllRules()

    suspend fun getTotalMessageCount(): Int = withContext(Dispatchers.IO) {
        smsDao.getTotalMessageCount()
    }

    /**
     * Asynchronous chunked import (500 messages per batch on Dispatchers.IO) reading existing
     * SMS messages from `content://sms`, resolving contacts via cached map, running the
     * Multi-Layer Categorization Engine, and saving into SQLCipher-encrypted Room.
     */
    suspend fun syncDeviceSmsInChunks() = withContext(Dispatchers.IO) {
        if (!systemSmsHelper.hasReadSmsPermission()) return@withContext
        if (_syncProgress.value.isSyncing) return@withContext

        importMutex.withLock {
            _syncProgress.value = SyncProgressState(isSyncing = true, importedCount = 0)
            try {
                contactCache.warmCache(forceRefresh = true)
                val settings = settingsStore.getSettingsSnapshot()
                val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)
                val existingRules = senderRuleDao.getAllRules().associateBy { it.normalizedSender }
                val alreadyImportedIds = smsDao.getImportedSystemSmsIds().toHashSet()

                var runningImported = 0
                systemSmsHelper.readSystemSmsInBatches(
                    batchSize = 500,
                    skipIds = alreadyImportedIds
                ) { batch ->
                    val entities = ArrayList<SmsMessageEntity>(batch.size)
                    for (raw in batch) {
                        val threadKey = PhoneAndSenderNormalizer.normalizeThreadKey(raw.address)
                        val bodySha = BackupCryptoEngine.sha256Hex(raw.body)
                        if (smsDao.existsDuplicate(threadKey, raw.timestampMillis, bodySha)) {
                            continue
                        }
                        val contactName = contactCache.resolveContactName(raw.address)
                        val ruleCategory = existingRules[threadKey]?.category
                        val outcome = pipeline.categorize(
                            rawAddress = raw.address,
                            body = raw.body,
                            isSavedContact = contactName != null,
                            customRuleCategory = ruleCategory,
                            activeRegion = region
                        )
                        entities.add(
                            SmsMessageEntity(
                                systemSmsId = raw.systemId,
                                threadKey = threadKey,
                                rawAddress = raw.address,
                                contactName = contactName,
                                body = raw.body,
                                bodySha256 = bodySha,
                                timestampMillis = raw.timestampMillis,
                                isIncoming = raw.isIncoming,
                                isRead = raw.isRead,
                                isArchived = false,
                                category = outcome.category,
                                extractedOtp = outcome.extractedOtp,
                                confidenceScore = outcome.confidenceScore,
                                classificationSource = outcome.source
                            )
                        )
                    }
                    if (entities.isNotEmpty()) {
                        smsDao.insertMessages(entities)
                        runningImported += entities.size
                        _syncProgress.value = SyncProgressState(
                            isSyncing = true,
                            importedCount = runningImported
                        )
                    }
                }

                settingsStore.updateSettings { it.copy(initialSyncCompleted = true) }
            } finally {
                _syncProgress.value = SyncProgressState(isSyncing = false, importedCount = 0)
            }
        }
    }

    /**
     * Seeds realistic multi-category sample SMS messages ONLY when user explicitly taps
     * "Preview Demo Inbox" on an empty emulator.
     */
    suspend fun seedEmulatorDemoInbox() = withContext(Dispatchers.IO) {
        val settings = settingsStore.getSettingsSnapshot()
        val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)
        val demoMessages = DemoInboxSeeder.buildDemoMessages(pipeline, region)
        smsDao.insertMessages(demoMessages)
        settingsStore.updateSettings {
            it.copy(initialSyncCompleted = true, onboardingDismissed = true)
        }
    }

    /**
     * Processes a newly received SMS PDU from `SmsDeliverReceiver`:
     * writes to system `Telephony.Sms.Inbox`, categorizes via pipeline, saves to SQLCipher Room,
     * honors `unarchiveOnNewSms`, and dispatches notification.
     */
    suspend fun handleIncomingSms(
        rawAddress: String,
        body: String,
        timestampMillis: Long
    ) = withContext(Dispatchers.IO) {
        val cleanAddress = rawAddress.trim().ifEmpty { "UNKNOWN" }
        val threadKey = PhoneAndSenderNormalizer.normalizeThreadKey(cleanAddress)
        val settings = settingsStore.getSettingsSnapshot()
        val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)

        val systemId = systemSmsHelper.insertIntoSystemProvider(
            address = cleanAddress,
            body = body,
            timestampMillis = timestampMillis,
            isIncoming = true,
            isRead = false
        )

        val contactName = contactCache.resolveContactName(cleanAddress)
        val rule = senderRuleDao.getRuleForSender(threadKey)
        val outcome = pipeline.categorize(
            rawAddress = cleanAddress,
            body = body,
            isSavedContact = contactName != null,
            customRuleCategory = rule?.category,
            activeRegion = region
        )

        val wasArchived = smsDao.isThreadCurrentlyArchived(threadKey)
        val keepArchived = if (wasArchived && settings.unarchiveOnNewSms) {
            smsDao.setThreadArchived(threadKey, false)
            false
        } else {
            wasArchived
        }

        val entity = SmsMessageEntity(
            systemSmsId = systemId,
            threadKey = threadKey,
            rawAddress = cleanAddress,
            contactName = contactName,
            body = body,
            bodySha256 = BackupCryptoEngine.sha256Hex(body),
            timestampMillis = timestampMillis,
            isIncoming = true,
            isRead = false,
            isArchived = keepArchived,
            category = outcome.category,
            extractedOtp = outcome.extractedOtp,
            confidenceScore = outcome.confidenceScore,
            classificationSource = outcome.source
        )
        val insertedMessageId = smsDao.insertMessage(entity)

        if (!keepArchived) {
            notificationHelper.postIncomingSmsNotification(
                messageId = insertedMessageId,
                threadKey = threadKey,
                rawAddress = cleanAddress,
                senderTitle = contactName ?: cleanAddress,
                body = body,
                category = outcome.category,
                extractedOtp = outcome.extractedOtp,
                redactPreviewForAppLock = settings.appLockEnabled
            )
        }
    }

    /**
     * Sends an outgoing SMS via `SmsManager.sendMultipartTextMessage` and records it in both
     * Android's `Telephony.Sms.Sent` provider (when Default SMS) and SQLCipher Room.
     */
    suspend fun sendOutgoingSms(
        destinationAddress: String,
        body: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmedDest = destinationAddress.trim()
        val trimmedBody = body.trim()
        if (trimmedDest.isEmpty() || trimmedBody.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Recipient and message cannot be empty."))
        }

        val sendResult = systemSmsHelper.sendMultipartSms(trimmedDest, trimmedBody)
        // If running on an emulator without a cellular radio or without SEND_SMS yet, report error cleanly
        if (sendResult.isFailure && !systemSmsHelper.isRunningOnEmulator()) {
            return@withContext Result.failure(
                sendResult.exceptionOrNull() ?: IllegalStateException("Failed to transmit SMS.")
            )
        }

        val now = System.currentTimeMillis()
        val systemId = systemSmsHelper.insertIntoSystemProvider(
            address = trimmedDest,
            body = trimmedBody,
            timestampMillis = now,
            isIncoming = false,
            isRead = true
        )

        val threadKey = PhoneAndSenderNormalizer.normalizeThreadKey(trimmedDest)
        val contactName = contactCache.resolveContactName(trimmedDest)
        val settings = settingsStore.getSettingsSnapshot()
        val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)
        val rule = senderRuleDao.getRuleForSender(threadKey)

        // Outgoing messages to a person/number stay in Personal/Unknown or existing rule
        val outcome = pipeline.categorize(
            rawAddress = trimmedDest,
            body = trimmedBody,
            isSavedContact = contactName != null,
            customRuleCategory = rule?.category,
            activeRegion = region
        )
        val assignedCategory = if (contactName != null) {
            rule?.category ?: SmsCategory.PERSONAL
        } else {
            outcome.category
        }

        smsDao.insertMessage(
            SmsMessageEntity(
                systemSmsId = systemId,
                threadKey = threadKey,
                rawAddress = trimmedDest,
                contactName = contactName,
                body = trimmedBody,
                bodySha256 = BackupCryptoEngine.sha256Hex(trimmedBody),
                timestampMillis = now,
                isIncoming = false,
                isRead = true,
                isArchived = false,
                category = assignedCategory,
                extractedOtp = null,
                confidenceScore = 1.0f,
                classificationSource = "SENT"
            )
        )
        Result.success(threadKey)
    }

    suspend fun markThreadAsRead(threadKey: String) = withContext(Dispatchers.IO) {
        smsDao.markThreadRead(threadKey)
        val systemIds = smsDao.getSystemIdsForThread(threadKey)
        systemSmsHelper.markSystemMessagesRead(systemIds)
    }

    suspend fun setThreadArchived(threadKey: String, archived: Boolean) = withContext(Dispatchers.IO) {
        smsDao.setThreadArchived(threadKey, archived)
    }

    suspend fun setThreadsArchived(threadKeys: List<String>, archived: Boolean) = withContext(Dispatchers.IO) {
        if (threadKeys.isEmpty()) return@withContext
        smsDao.setThreadsArchived(threadKeys, archived)
    }

    /**
     * Moves a conversation's category and optionally saves a permanent `SenderCategoryRuleEntity`
     * so all existing and future SMS from this sender route to [targetCategory].
     */
    suspend fun assignSenderCategory(
        threadKey: String,
        displaySender: String,
        targetCategory: SmsCategory,
        savePermanentRule: Boolean
    ) = withContext(Dispatchers.IO) {
        if (savePermanentRule) {
            senderRuleDao.upsertRule(
                SenderCategoryRuleEntity(
                    normalizedSender = threadKey,
                    displaySender = displaySender,
                    category = targetCategory,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
        smsDao.updateCategoryForThread(
            threadKey = threadKey,
            category = targetCategory,
            source = if (savePermanentRule) "USER_RULE" else "MANUAL_MOVE"
        )
    }

    suspend fun deleteSingleMessage(messageId: Long) = withContext(Dispatchers.IO) {
        val sysId = smsDao.getSystemIdForMessage(messageId)
        if (sysId != null) {
            systemSmsHelper.deleteFromSystemProvider(listOf(sysId))
        }
        smsDao.deleteMessageById(messageId)
    }

    suspend fun deleteNotificationMessage(
        messageId: Long,
        fallbackThreadKey: String? = null
    ) = withContext(Dispatchers.IO) {
        if (messageId > 0L) {
            deleteSingleMessage(messageId)
        } else if (!fallbackThreadKey.isNullOrBlank()) {
            val latestId = smsDao.getLatestMessageIdForThread(fallbackThreadKey)
            if (latestId != null) {
                deleteSingleMessage(latestId)
            }
        }
    }

    suspend fun deleteThreadPermanently(threadKey: String) = withContext(Dispatchers.IO) {
        val sysIds = smsDao.getSystemIdsForThread(threadKey)
        systemSmsHelper.deleteFromSystemProvider(sysIds)
        smsDao.deleteThread(threadKey)
    }

    suspend fun deleteThreadsPermanently(threadKeys: List<String>) = withContext(Dispatchers.IO) {
        if (threadKeys.isEmpty()) return@withContext
        val sysIds = smsDao.getSystemIdsForThreads(threadKeys)
        systemSmsHelper.deleteFromSystemProvider(sysIds)
        smsDao.deleteThreads(threadKeys)
    }

    suspend fun countActiveMessagesInCategory(category: SmsCategory): Int = withContext(Dispatchers.IO) {
        smsDao.countActiveMessagesInCategory(category)
    }

    suspend fun deleteAllActiveInCategory(category: SmsCategory): Int = withContext(Dispatchers.IO) {
        val sysIds = smsDao.getSystemIdsInActiveCategory(category)
        systemSmsHelper.deleteFromSystemProvider(sysIds)
        smsDao.deleteActiveMessagesInCategory(category)
    }

    /**
     * Strictly deletes ONLY unarchived `PROMOTIONAL` messages older than [daysOld] days
     * from both SQLCipher Room and `Telephony.Sms` ContentProvider.
     */
    suspend fun purgeExpiredPromotionalMessages(daysOld: Int): Int = withContext(Dispatchers.IO) {
        if (daysOld <= 0) return@withContext 0
        val cutoffMillis = System.currentTimeMillis() - (daysOld.toLong() * 24L * 60L * 60L * 1000L)
        val sysIds = smsDao.getExpiredPromotionalSystemIds(cutoffMillis)
        systemSmsHelper.deleteFromSystemProvider(sysIds)
        smsDao.deleteExpiredPromotionalMessages(cutoffMillis)
    }

    /**
     * Strictly deletes ONLY unarchived `OTP` messages older than [daysOld] days
     * from both SQLCipher Room and `Telephony.Sms` ContentProvider.
     */
    suspend fun purgeExpiredOtpMessages(daysOld: Int): Int = withContext(Dispatchers.IO) {
        if (daysOld <= 0) return@withContext 0
        val cutoffMillis = System.currentTimeMillis() - (daysOld.toLong() * 24L * 60L * 60L * 1000L)
        val sysIds = smsDao.getExpiredOtpSystemIds(cutoffMillis)
        systemSmsHelper.deleteFromSystemProvider(sysIds)
        smsDao.deleteExpiredOtpMessages(cutoffMillis)
    }

    suspend fun deleteAllMessagesInApp() = withContext(Dispatchers.IO) {
        val allSysIds = smsDao.getAllSystemIds()
        systemSmsHelper.deleteFromSystemProvider(allSysIds)
        systemSmsHelper.wipeAllFromSystemProvider()
        smsDao.deleteAllMessages()
    }

    /**
     * Re-evaluates all stored messages through the Multi-Layer Categorization Engine
     * (useful when user switches region override or updates contacts).
     */
    suspend fun rerunCategorizationOnAllMessages(): Int = withContext(Dispatchers.IO) {
        contactCache.warmCache(forceRefresh = true)
        val settings = settingsStore.getSettingsSnapshot()
        val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)
        val rulesMap = senderRuleDao.getAllRules().associateBy { it.normalizedSender }
        val allMessages = smsDao.getAllMessagesSnapshot()
        if (allMessages.isEmpty()) return@withContext 0

        val updated = allMessages.map { msg ->
            val contactName = contactCache.resolveContactName(msg.rawAddress) ?: msg.contactName
            val ruleCategory = rulesMap[msg.threadKey]?.category
            val outcome = pipeline.categorize(
                rawAddress = msg.rawAddress,
                body = msg.body,
                isSavedContact = contactName != null,
                customRuleCategory = ruleCategory,
                activeRegion = region
            )
            msg.copy(
                contactName = contactName,
                category = outcome.category,
                extractedOtp = outcome.extractedOtp,
                confidenceScore = outcome.confidenceScore,
                classificationSource = outcome.source
            )
        }

        for (chunk in updated.chunked(500)) {
            smsDao.updateMessages(chunk)
        }
        updated.size
    }

    /**
     * Exports all SMS messages, archive states, and custom sender rules as GZIP + AES-256-GCM
     * encrypted `.trai` archive to the user's Google Drive SAF URI (`openOutputStream(uri, "wt")`).
     */
    suspend fun exportEncryptedBackupToUri(
        targetUri: Uri,
        passphrase: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val messages = smsDao.getAllMessagesSnapshot().map { msg ->
                BackupMessageRecord(
                    threadKey = msg.threadKey,
                    rawAddress = msg.rawAddress,
                    contactName = msg.contactName,
                    body = msg.body,
                    bodySha256 = msg.bodySha256,
                    timestampMillis = msg.timestampMillis,
                    isIncoming = msg.isIncoming,
                    isRead = msg.isRead,
                    isArchived = msg.isArchived,
                    category = msg.category,
                    extractedOtp = msg.extractedOtp,
                    confidenceScore = msg.confidenceScore,
                    classificationSource = msg.classificationSource
                )
            }
            val rules = senderRuleDao.getAllRules().map { r ->
                BackupSenderRuleRecord(
                    normalizedSender = r.normalizedSender,
                    displaySender = r.displaySender,
                    category = r.category,
                    updatedAtMillis = r.updatedAtMillis
                )
            }

            val payload = EncryptedBackupPayload(
                formatVersion = 1,
                createdAtMillis = System.currentTimeMillis(),
                messages = messages,
                senderRules = rules
            )
            val encryptedBytes = BackupCryptoEngine.encryptPayload(payload, passphrase)

            val outputStream = appContext.contentResolver.openOutputStream(targetUri, "wt")
                ?: appContext.contentResolver.openOutputStream(targetUri)
                ?: throw IllegalStateException("Unable to open Google Drive backup destination URI.")

            outputStream.use { out ->
                out.write(encryptedBytes)
                out.flush()
            }

            val now = System.currentTimeMillis()
            settingsStore.updateSettings {
                it.copy(
                    lastBackupEpochMillis = now,
                    lastBackupStatus = "Success (AES-256-GCM verified)",
                    lastBackupCount = messages.size
                )
            }
            messages.size
        }.onFailure { err ->
            settingsStore.updateSettings {
                it.copy(lastBackupStatus = "Failed: ${err.message ?: "Write error"}")
            }
        }
    }

    /**
     * Restores `.trai` encrypted backup from Google Drive SAF URI, verifies GCM authenticity,
     * deduplicates by `(normalizedAddress/threadKey, timestampMillis, bodySha256)`, writes missing
     * messages to both SQLCipher Room and `Telephony.Sms` (if Default SMS), and restores rules.
     */
    suspend fun restoreEncryptedBackupFromUri(
        sourceUri: Uri,
        passphrase: String
    ): Result<RestoreSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val encryptedBytes = appContext.contentResolver.openInputStream(sourceUri)?.use {
                it.readBytes()
            } ?: throw IllegalStateException("Unable to read selected backup file.")

            val payload = BackupCryptoEngine.decryptPayload(encryptedBytes, passphrase)

            // 1. Restore custom sender rules
            val restoredRules = payload.senderRules.map { r ->
                SenderCategoryRuleEntity(
                    normalizedSender = r.normalizedSender,
                    displaySender = r.displaySender,
                    category = r.category,
                    updatedAtMillis = r.updatedAtMillis
                )
            }
            if (restoredRules.isNotEmpty()) {
                senderRuleDao.upsertRules(restoredRules)
            }

            // 2. Deduplicate and restore SMS messages
            var inserted = 0
            var duplicates = 0
            val toInsert = ArrayList<SmsMessageEntity>()

            for (record in payload.messages) {
                val normalizedKey = PhoneAndSenderNormalizer.normalizeThreadKey(record.rawAddress)
                val bodySha = record.bodySha256.ifEmpty { BackupCryptoEngine.sha256Hex(record.body) }

                if (smsDao.existsDuplicate(normalizedKey, record.timestampMillis, bodySha)) {
                    duplicates++
                    // Still restore archive state if archived in backup
                    if (record.isArchived) {
                        smsDao.setThreadArchived(normalizedKey, true)
                    }
                    continue
                }

                val sysId = systemSmsHelper.insertIntoSystemProvider(
                    address = record.rawAddress,
                    body = record.body,
                    timestampMillis = record.timestampMillis,
                    isIncoming = record.isIncoming,
                    isRead = record.isRead
                )

                toInsert.add(
                    SmsMessageEntity(
                        systemSmsId = sysId,
                        threadKey = normalizedKey,
                        rawAddress = record.rawAddress,
                        contactName = record.contactName ?: contactCache.resolveContactName(record.rawAddress),
                        body = record.body,
                        bodySha256 = bodySha,
                        timestampMillis = record.timestampMillis,
                        isIncoming = record.isIncoming,
                        isRead = record.isRead,
                        isArchived = record.isArchived,
                        category = record.category,
                        extractedOtp = record.extractedOtp,
                        confidenceScore = record.confidenceScore,
                        classificationSource = record.classificationSource
                    )
                )
                inserted++

                if (toInsert.size >= 500) {
                    smsDao.insertMessages(toInsert.toList())
                    toInsert.clear()
                }
            }

            if (toInsert.isNotEmpty()) {
                smsDao.insertMessages(toInsert)
            }

            // Re-apply restored custom rules across existing threads
            for (rule in restoredRules) {
                smsDao.updateCategoryForThread(
                    threadKey = rule.normalizedSender,
                    category = rule.category,
                    source = "USER_RULE"
                )
            }

            RestoreSummary(
                insertedCount = inserted,
                skippedDuplicates = duplicates,
                restoredRulesCount = restoredRules.size
            )
        }
    }
}
