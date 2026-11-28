package com.example.data.sms

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import com.example.data.crypto.BackupCryptoEngine
import com.example.data.crypto.BackupMessageRecord
import com.example.data.crypto.BackupSenderRuleRecord
import com.example.data.crypto.EncryptedBackupPayload
import com.example.data.crypto.EncryptedSettingsStore
import com.example.data.db.CategoryUnreadCount
import com.example.data.db.MmsAttachmentEntity
import com.example.data.db.SenderCategoryRuleEntity
import com.example.data.db.SenderRuleDao
import com.example.data.db.SmsCategory
import com.example.data.db.SmsDao
import com.example.data.db.SmsMessageEntity
import com.example.data.db.SmsMessageWithAttachments
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

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
 * Central offline SMS/MMS repository coordinating SQLCipher Room persistence, Android `Telephony.Sms`
 * & `Telephony.Mms` ContentProvider synchronization, Multi-Layer Categorization, Archive/Swipe rules,
 * Google Messages-style RCS/SMS fallback reactions, and SAF AES-GCM Backups.
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
    private val json = Json { ignoreUnknownKeys = true }

    private val _syncProgress = MutableStateFlow(SyncProgressState())
    val syncProgress: StateFlow<SyncProgressState> = _syncProgress.asStateFlow()

    fun observeCategoryThreads(
        category: SmsCategory,
        unreadOnly: Boolean = false
    ): Flow<List<ThreadSummary>> =
        if (unreadOnly) smsDao.observeUnreadCategoryThreads(category)
        else smsDao.observeCategoryThreads(category)

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

    fun observeThreadMessagesWithAttachments(threadKey: String): Flow<List<SmsMessageWithAttachments>> =
        smsDao.observeThreadMessagesWithAttachments(threadKey)

    fun observeSenderRules(): Flow<List<SenderCategoryRuleEntity>> =
        senderRuleDao.observeAllRules()

    suspend fun getTotalMessageCount(): Int = withContext(Dispatchers.IO) {
        smsDao.getTotalMessageCount()
    }

    /**
     * Asynchronous chunked import (500 SMS + 100 MMS per batch on Dispatchers.IO) reading existing
     * SMS and MMS/RCS-bridged messages, resolving contacts via cached map, running the
     * Multi-Layer Categorization Engine, folding Google Messages reactions, and saving into SQLCipher Room.
     */
    suspend fun syncDeviceSmsInChunks() = withContext(Dispatchers.IO) {
        if (!systemSmsHelper.hasReadSmsPermission()) return@withContext
        if (_syncProgress.value.isSyncing) return@withContext

        importMutex.withLock {
            _syncProgress.value = SyncProgressState(isSyncing = true, importedCount = 0)
            try {
                contactCache.warmCache(forceRefresh = true)
                val settings = settingsStore.getSettingsSnapshot()
                val isPostInitialDeltaSync = settings.initialSyncCompleted
                val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)
                val existingRules = senderRuleDao.getAllRules().associateBy { it.normalizedSender }
                val alreadyImportedIds = smsDao.getImportedSystemSmsIds().toHashSet()

                var runningImported = 0
                systemSmsHelper.readSystemSmsInBatches(
                    batchSize = 500,
                    skipIds = alreadyImportedIds
                ) { batch ->
                    val entities = ArrayList<SmsMessageEntity>(batch.size)
                    val promoSystemIdsToMarkRead = ArrayList<Long>()
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
                        val effectiveIsRead = if (isPostInitialDeltaSync && outcome.category == SmsCategory.PROMOTIONAL) {
                            true
                        } else {
                            raw.isRead
                        }
                        if (isPostInitialDeltaSync && outcome.category == SmsCategory.PROMOTIONAL && !raw.isRead) {
                            promoSystemIdsToMarkRead.add(raw.systemId)
                        }

                        val parsedReaction = RcsInteropCodec.parseReactionFallback(raw.body)
                        val folded = if (parsedReaction != null) {
                            tryFoldReactionIntoThread(
                                threadKey = threadKey,
                                senderAddress = raw.address,
                                senderDisplay = if (raw.isIncoming) (contactName ?: raw.address) else "You",
                                isFromSelf = !raw.isIncoming,
                                timestampMillis = raw.timestampMillis,
                                parsed = parsedReaction
                            )
                        } else false

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
                                isRead = effectiveIsRead || folded,
                                isArchived = false,
                                category = outcome.category,
                                extractedOtp = outcome.extractedOtp,
                                confidenceScore = outcome.confidenceScore,
                                classificationSource = outcome.source,
                                transportType = if (parsedReaction != null && settings.rcsChatsEnabled) "RCS" else "SMS",
                                deliveryStatus = if (raw.isIncoming) "NONE" else "SENT",
                                isHiddenReaction = folded
                            )
                        )
                    }
                    if (entities.isNotEmpty()) {
                        smsDao.insertMessages(entities)
                        if (promoSystemIdsToMarkRead.isNotEmpty()) {
                            systemSmsHelper.markSystemMessagesRead(promoSystemIdsToMarkRead)
                        }
                        runningImported += entities.size
                        _syncProgress.value = SyncProgressState(
                            isSyncing = true,
                            importedCount = runningImported
                        )
                    }
                }

                // Sync MMS & RCS-bridged messages from Telephony.Mms
                val importedMmsIds = smsDao.getImportedSystemMmsIds().toHashSet()
                systemSmsHelper.readSystemMmsInBatches(
                    batchSize = 100,
                    skipMmsIds = importedMmsIds
                ) { mmsBatch ->
                    val promoMmsIdsToMarkRead = ArrayList<Long>()
                    var insertedMmsCount = 0
                    for (rawMms in mmsBatch) {
                        val isGroup = rawMms.allAddresses.size > 2 ||
                            (!rawMms.isIncoming && rawMms.allAddresses.size > 1)
                        val groupRecipients = if (isGroup) {
                            rawMms.allAddresses.filterNot { it.equals("insert-address-token", ignoreCase = true) }
                        } else emptyList()
                        val threadKey = if (groupRecipients.size > 1) {
                            buildGroupThreadKey(groupRecipients)
                        } else {
                            PhoneAndSenderNormalizer.normalizeThreadKey(rawMms.fromAddress)
                        }
                        val displayBody = rawMms.body.ifBlank {
                            rawMms.subject ?: buildAttachmentFallbackSummary(rawMms.parts.map { it.contentType })
                        }
                        val bodySha = BackupCryptoEngine.sha256Hex("$displayBody|${rawMms.parts.size}")
                        if (smsDao.existsDuplicate(threadKey, rawMms.timestampMillis, bodySha)) {
                            continue
                        }

                        val contactName = contactCache.resolveContactName(rawMms.fromAddress)
                        val ruleCategory = existingRules[threadKey]?.category
                        val outcome = pipeline.categorize(
                            rawAddress = rawMms.fromAddress,
                            body = displayBody,
                            isSavedContact = contactName != null || groupRecipients.isNotEmpty(),
                            customRuleCategory = ruleCategory,
                            activeRegion = region
                        )
                        val effectiveIsRead = if (isPostInitialDeltaSync && outcome.category == SmsCategory.PROMOTIONAL) {
                            true
                        } else {
                            rawMms.isRead
                        }
                        if (isPostInitialDeltaSync && outcome.category == SmsCategory.PROMOTIONAL && !rawMms.isRead) {
                            promoMmsIdsToMarkRead.add(rawMms.systemMmsId)
                        }

                        val parsedReaction = RcsInteropCodec.parseReactionFallback(displayBody)
                        val folded = if (parsedReaction != null) {
                            tryFoldReactionIntoThread(
                                threadKey = threadKey,
                                senderAddress = rawMms.fromAddress,
                                senderDisplay = if (rawMms.isIncoming) (contactName ?: rawMms.fromAddress) else "You",
                                isFromSelf = !rawMms.isIncoming,
                                timestampMillis = rawMms.timestampMillis,
                                parsed = parsedReaction
                            )
                        } else false

                        val insertedMsgId = smsDao.insertMessage(
                            SmsMessageEntity(
                                systemMmsId = rawMms.systemMmsId,
                                threadKey = threadKey,
                                rawAddress = rawMms.fromAddress,
                                contactName = contactName,
                                body = displayBody,
                                bodySha256 = bodySha,
                                timestampMillis = rawMms.timestampMillis,
                                isIncoming = rawMms.isIncoming,
                                isRead = effectiveIsRead || folded,
                                isArchived = false,
                                category = outcome.category,
                                extractedOtp = outcome.extractedOtp,
                                confidenceScore = outcome.confidenceScore,
                                classificationSource = outcome.source,
                                transportType = if (rawMms.isRcsBridged) "RCS" else "MMS",
                                deliveryStatus = if (rawMms.isIncoming) "NONE" else "SENT",
                                mmsSubject = rawMms.subject,
                                groupRecipientsJson = encodeRecipientsList(groupRecipients),
                                isHiddenReaction = folded
                            )
                        )

                        if (rawMms.parts.isNotEmpty()) {
                            smsDao.insertAttachments(
                                rawMms.parts.map { p ->
                                    MmsAttachmentEntity(
                                        messageId = insertedMsgId,
                                        partIndex = p.partIndex,
                                        contentType = p.contentType,
                                        fileName = p.fileName,
                                        localFilePath = p.localFilePath,
                                        contentId = p.contentId,
                                        sizeBytes = p.sizeBytes,
                                        textContent = p.textContent
                                    )
                                }
                            )
                        }
                        insertedMmsCount++
                    }

                    if (promoMmsIdsToMarkRead.isNotEmpty()) {
                        systemSmsHelper.markSystemMmsRead(promoMmsIdsToMarkRead)
                    }
                    if (insertedMmsCount > 0) {
                        runningImported += insertedMmsCount
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
     * categorizes via pipeline first, folds Google Messages fallback reactions if matched,
     * auto-marks `PROMOTIONAL` as read, writes to system `Telephony.Sms.Inbox`, saves to Room,
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

        val contactName = contactCache.resolveContactName(cleanAddress)
        val rule = senderRuleDao.getRuleForSender(threadKey)
        val outcome = pipeline.categorize(
            rawAddress = cleanAddress,
            body = body,
            isSavedContact = contactName != null,
            customRuleCategory = rule?.category,
            activeRegion = region
        )
        val autoMarkRead = outcome.category == SmsCategory.PROMOTIONAL

        val systemId = systemSmsHelper.insertIntoSystemProvider(
            address = cleanAddress,
            body = body,
            timestampMillis = timestampMillis,
            isIncoming = true,
            isRead = autoMarkRead
        )

        val wasArchived = smsDao.isThreadCurrentlyArchived(threadKey)
        val keepArchived = if (wasArchived && settings.unarchiveOnNewSms) {
            smsDao.setThreadArchived(threadKey, false)
            false
        } else {
            wasArchived
        }

        val parsedReaction = RcsInteropCodec.parseReactionFallback(body)
        val folded = if (parsedReaction != null) {
            tryFoldReactionIntoThread(
                threadKey = threadKey,
                senderAddress = cleanAddress,
                senderDisplay = contactName ?: cleanAddress,
                isFromSelf = false,
                timestampMillis = timestampMillis,
                parsed = parsedReaction
            )
        } else false

        val entity = SmsMessageEntity(
            systemSmsId = systemId,
            threadKey = threadKey,
            rawAddress = cleanAddress,
            contactName = contactName,
            body = body,
            bodySha256 = BackupCryptoEngine.sha256Hex(body),
            timestampMillis = timestampMillis,
            isIncoming = true,
            isRead = autoMarkRead || folded,
            isArchived = keepArchived,
            category = outcome.category,
            extractedOtp = outcome.extractedOtp,
            confidenceScore = outcome.confidenceScore,
            classificationSource = outcome.source,
            transportType = if (parsedReaction != null && settings.rcsChatsEnabled) "RCS" else "SMS",
            deliveryStatus = "NONE",
            isHiddenReaction = folded
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
     * Handles incoming binary MMS WAP Push PDU (`M-Notification.ind`, `M-Delivery.ind`,
     * `M-Read-Orig.ind`, or direct `M-Retrieve.conf`).
     */
    suspend fun handleIncomingMmsWapPush(
        pduBytes: ByteArray?,
        subscriptionId: Int = -1,
        timestampMillis: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        if (pduBytes == null || pduBytes.isEmpty()) return@withContext
        val parsed = MmsPduCodec.decodePdu(pduBytes)
        if (parsed == null) {
            handleIncomingSms(
                rawAddress = "MMS-GATEWAY",
                body = "[Incoming Multimedia Message (MMS) notification received in 100% Offline Mode.]",
                timestampMillis = timestampMillis
            )
            return@withContext
        }

        when (parsed.messageType) {
            MmsPduType.DELIVERY_IND -> {
                val txId = parsed.transactionId ?: parsed.messageId
                if (!txId.isNullOrBlank()) {
                    val target = smsDao.getMessageByTransactionId(txId)
                    if (target != null) {
                        upgradeDeliveryStatus(target.id, target.deliveryStatus, "DELIVERED")
                    }
                }
            }
            MmsPduType.READ_ORIG_IND -> {
                val txId = parsed.messageId ?: parsed.transactionId
                if (!txId.isNullOrBlank()) {
                    val target = smsDao.getMessageByTransactionId(txId)
                    if (target != null) {
                        upgradeDeliveryStatus(target.id, target.deliveryStatus, "READ")
                    }
                }
            }
            MmsPduType.RETRIEVE_CONF -> {
                persistRetrievedMms(
                    parsed = parsed,
                    existingPendingMessage = null,
                    fallbackTimestampMillis = timestampMillis
                )
            }
            MmsPduType.NOTIFICATION_IND -> {
                val settings = settingsStore.getSettingsSnapshot()
                val rawSender = parsed.fromAddress?.trim()?.ifEmpty { "MMS-GATEWAY" } ?: "MMS-GATEWAY"
                val threadKey = PhoneAndSenderNormalizer.normalizeThreadKey(rawSender)
                val contactName = contactCache.resolveContactName(rawSender)
                val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)
                val rule = senderRuleDao.getRuleForSender(threadKey)
                val placeholderBody = parsed.subject?.takeIf { it.isNotBlank() }
                    ?: "Incoming MMS (${formatBytesLabel(parsed.messageSizeBytes)}) • 100% Offline Mode"
                val outcome = pipeline.categorize(
                    rawAddress = rawSender,
                    body = placeholderBody,
                    isSavedContact = contactName != null,
                    customRuleCategory = rule?.category,
                    activeRegion = region
                )

                val txId = parsed.transactionId ?: "mms_${timestampMillis}"
                val pendingId = smsDao.insertMessage(
                    SmsMessageEntity(
                        threadKey = threadKey,
                        rawAddress = rawSender,
                        contactName = contactName,
                        body = placeholderBody,
                        bodySha256 = BackupCryptoEngine.sha256Hex("$txId|$placeholderBody"),
                        timestampMillis = parsed.dateEpochSeconds.takeIf { it > 0L }?.times(1000L)
                            ?: timestampMillis,
                        isIncoming = true,
                        isRead = false,
                        isArchived = false,
                        category = outcome.category,
                        extractedOtp = outcome.extractedOtp,
                        confidenceScore = outcome.confidenceScore,
                        classificationSource = outcome.source,
                        transportType = "MMS",
                        deliveryStatus = "PENDING_DOWNLOAD",
                        mmsSubject = parsed.subject,
                        mmsContentLocation = parsed.contentLocation,
                        mmsTransactionId = txId,
                        mmsSizeBytes = parsed.messageSizeBytes
                    )
                )

                val contentLoc = parsed.contentLocation
                if (settings.autoDownloadMms && !contentLoc.isNullOrBlank()) {
                    systemSmsHelper.downloadMultimediaMessage(
                        contentLocation = contentLoc,
                        messageId = pendingId,
                        transactionId = txId,
                        subscriptionId = subscriptionId
                    )
                } else {
                    notificationHelper.postIncomingSmsNotification(
                        messageId = pendingId,
                        threadKey = threadKey,
                        rawAddress = rawSender,
                        senderTitle = contactName ?: rawSender,
                        body = placeholderBody,
                        category = outcome.category,
                        extractedOtp = outcome.extractedOtp,
                        redactPreviewForAppLock = settings.appLockEnabled
                    )
                }
            }
            else -> Unit
        }
    }

    suspend fun downloadMmsByMessageId(messageId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val msg = smsDao.getMessageById(messageId)
            ?: return@withContext Result.failure(IllegalArgumentException("MMS message not found."))
        val contentLoc = msg.mmsContentLocation?.trim()
        if (contentLoc.isNullOrEmpty()) {
            return@withContext Result.failure(IllegalStateException("MMS download URL unavailable."))
        }
        val txId = msg.mmsTransactionId ?: "dl_$messageId"
        systemSmsHelper.downloadMultimediaMessage(
            contentLocation = contentLoc,
            messageId = messageId,
            transactionId = txId
        ).map { Unit }
    }

    suspend fun handleDownloadedMmsPdu(
        messageId: Long,
        pduFilePath: String,
        resultCode: Int,
        subscriptionId: Int = -1
    ) = withContext(Dispatchers.IO) {
        val file = File(pduFilePath)
        try {
            val existing = smsDao.getMessageById(messageId) ?: return@withContext
            if (resultCode != Activity.RESULT_OK || !file.exists() || file.length() == 0L) {
                smsDao.updateDeliveryStatus(messageId, "PENDING_DOWNLOAD")
                return@withContext
            }
            val pduBytes = file.readBytes()
            val parsed = MmsPduCodec.decodePdu(pduBytes)
            if (parsed == null) {
                smsDao.updateDeliveryStatus(messageId, "PENDING_DOWNLOAD")
                return@withContext
            }
            persistRetrievedMms(
                parsed = parsed,
                existingPendingMessage = existing,
                fallbackTimestampMillis = existing.timestampMillis
            )
        } finally {
            runCatching { file.delete() }
        }
    }

    private suspend fun persistRetrievedMms(
        parsed: ParsedMmsPdu,
        existingPendingMessage: SmsMessageEntity?,
        fallbackTimestampMillis: Long
    ) {
        val settings = settingsStore.getSettingsSnapshot()
        val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)
        val rawSender = parsed.fromAddress?.trim()?.takeIf { it.isNotEmpty() }
            ?: existingPendingMessage?.rawAddress
            ?: "MMS-GATEWAY"
        val recipients = (listOf(rawSender) + parsed.toAddresses + parsed.ccAddresses)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val isGroup = recipients.size > 2
        val threadKey = if (isGroup) {
            buildGroupThreadKey(recipients)
        } else {
            PhoneAndSenderNormalizer.normalizeThreadKey(rawSender)
        }

        val orderedParts = MmsPduCodec.extractSmilOrderedParts(parsed.parts)
        val textSegments = mutableListOf<String>()
        val attachmentEntities = mutableListOf<MmsAttachmentEntity>()
        val persistedParts = mutableListOf<MmsPart>()

        orderedParts.forEachIndexed { index, part ->
            if (part.contentType.equals("text/plain", ignoreCase = true)) {
                val txt = runCatching { part.data.toString(Charsets.UTF_8) }.getOrNull().orEmpty().trim()
                if (txt.isNotEmpty()) textSegments.add(txt)
            } else {
                val savedFile = systemSmsHelper.persistMmsPartBytes(
                    partIndex = index,
                    contentType = part.contentType,
                    fileName = part.fileName ?: part.contentLocation,
                    bytes = part.data
                )
                val vCardText = if (part.contentType.contains("vcard", ignoreCase = true)) {
                    runCatching { part.data.toString(Charsets.UTF_8) }.getOrNull()
                } else null
                attachmentEntities.add(
                    MmsAttachmentEntity(
                        messageId = existingPendingMessage?.id ?: 0L,
                        partIndex = index,
                        contentType = part.contentType,
                        fileName = part.fileName ?: part.contentLocation ?: savedFile?.name,
                        localFilePath = savedFile?.absolutePath,
                        contentId = part.contentId,
                        sizeBytes = part.data.size.toLong(),
                        textContent = vCardText
                    )
                )
                persistedParts.add(part)
            }
        }

        val bodyText = textSegments.joinToString("\n").trim().ifEmpty {
            parsed.subject ?: buildAttachmentFallbackSummary(attachmentEntities.map { it.contentType })
        }
        val contactName = contactCache.resolveContactName(rawSender)
        val rule = senderRuleDao.getRuleForSender(threadKey)
        val outcome = pipeline.categorize(
            rawAddress = rawSender,
            body = bodyText,
            isSavedContact = contactName != null || isGroup,
            customRuleCategory = rule?.category,
            activeRegion = region
        )
        val autoMarkRead = outcome.category == SmsCategory.PROMOTIONAL
        val timestampMillis = parsed.dateEpochSeconds.takeIf { it > 0L }?.times(1000L)
            ?: fallbackTimestampMillis

        val parsedReaction = RcsInteropCodec.parseReactionFallback(bodyText)
        val folded = if (parsedReaction != null && attachmentEntities.isEmpty()) {
            tryFoldReactionIntoThread(
                threadKey = threadKey,
                senderAddress = rawSender,
                senderDisplay = contactName ?: rawSender,
                isFromSelf = false,
                timestampMillis = timestampMillis,
                parsed = parsedReaction
            )
        } else false

        val systemMmsId = systemSmsHelper.insertMmsIntoSystemProvider(
            fromAddress = rawSender,
            toAddresses = parsed.toAddresses.ifEmpty { listOf("self") },
            subject = parsed.subject,
            body = bodyText,
            parts = persistedParts,
            timestampMillis = timestampMillis,
            isIncoming = true,
            isRead = autoMarkRead || folded,
            transactionId = parsed.transactionId ?: existingPendingMessage?.mmsTransactionId
        )

        val wasArchived = smsDao.isThreadCurrentlyArchived(threadKey)
        val keepArchived = if (wasArchived && settings.unarchiveOnNewSms) {
            smsDao.setThreadArchived(threadKey, false)
            false
        } else {
            wasArchived
        }

        val updatedEntity = SmsMessageEntity(
            id = existingPendingMessage?.id ?: 0L,
            systemMmsId = systemMmsId,
            threadKey = threadKey,
            rawAddress = rawSender,
            contactName = contactName,
            body = bodyText,
            bodySha256 = BackupCryptoEngine.sha256Hex(bodyText),
            timestampMillis = timestampMillis,
            isIncoming = true,
            isRead = autoMarkRead || folded,
            isArchived = keepArchived,
            category = outcome.category,
            extractedOtp = outcome.extractedOtp,
            confidenceScore = outcome.confidenceScore,
            classificationSource = outcome.source,
            transportType = "MMS",
            deliveryStatus = if (parsed.readReportRequested && !autoMarkRead) "READ_REPORT_REQUESTED" else "NONE",
            mmsSubject = parsed.subject ?: existingPendingMessage?.mmsSubject,
            mmsContentLocation = parsed.contentLocation ?: existingPendingMessage?.mmsContentLocation,
            mmsTransactionId = parsed.messageId ?: parsed.transactionId ?: existingPendingMessage?.mmsTransactionId,
            mmsSizeBytes = parsed.messageSizeBytes.takeIf { it > 0L }
                ?: attachmentEntities.sumOf { it.sizeBytes },
            groupRecipientsJson = encodeRecipientsList(if (isGroup) recipients else emptyList()),
            isHiddenReaction = folded
        )

        val targetId = if (existingPendingMessage != null) {
            smsDao.updateMessage(updatedEntity)
            existingPendingMessage.id
        } else {
            smsDao.insertMessage(updatedEntity)
        }

        if (attachmentEntities.isNotEmpty()) {
            smsDao.insertAttachments(attachmentEntities.map { it.copy(messageId = targetId) })
        }

        if (!keepArchived && !folded) {
            notificationHelper.postIncomingSmsNotification(
                messageId = targetId,
                threadKey = threadKey,
                rawAddress = rawSender,
                senderTitle = contactName ?: rawSender,
                body = bodyText,
                category = outcome.category,
                extractedOtp = outcome.extractedOtp,
                redactPreviewForAppLock = settings.appLockEnabled
            )
        }
    }

    /**
     * Sends an outgoing SMS via `SmsManager.sendMultipartTextMessage` with immutable
     * sent/delivery `PendingIntent`s and records it in SQLCipher Room + `Telephony.Sms.Sent`.
     */
    suspend fun sendOutgoingSms(
        destinationAddress: String,
        body: String,
        replyToMessageId: Long? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmedDest = destinationAddress.trim()
        val trimmedBody = body.trim()
        if (trimmedDest.isEmpty() || trimmedBody.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Recipient and message cannot be empty."))
        }

        val now = System.currentTimeMillis()
        val threadKey = PhoneAndSenderNormalizer.normalizeThreadKey(trimmedDest)
        val contactName = contactCache.resolveContactName(trimmedDest)
        val settings = settingsStore.getSettingsSnapshot()
        val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)
        val rule = senderRuleDao.getRuleForSender(threadKey)

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

        val quotedTarget = replyToMessageId?.let { smsDao.getMessageById(it) }
        val replyToSender = quotedTarget?.let {
            if (it.isIncoming) (it.contactName ?: it.rawAddress) else "You"
        }
        val replyToSnippet = quotedTarget?.body?.trim()?.take(100)

        val systemId = systemSmsHelper.insertIntoSystemProvider(
            address = trimmedDest,
            body = trimmedBody,
            timestampMillis = now,
            isIncoming = false,
            isRead = true
        )

        val insertedId = smsDao.insertMessage(
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
                classificationSource = "SENT",
                transportType = "SMS",
                deliveryStatus = "SENDING",
                replyToMessageId = quotedTarget?.id,
                replyToSender = replyToSender,
                replyToSnippet = replyToSnippet
            )
        )

        val sendResult = systemSmsHelper.sendMultipartSms(
            destinationAddress = trimmedDest,
            messageText = trimmedBody,
            messageId = insertedId,
            requestDeliveryReport = true
        )
        if (sendResult.isFailure) {
            if (systemSmsHelper.isRunningOnEmulator()) {
                smsDao.updateDeliveryStatus(insertedId, "SENT")
            } else {
                smsDao.updateDeliveryStatus(insertedId, "FAILED")
                return@withContext Result.failure(
                    sendResult.exceptionOrNull() ?: IllegalStateException("Failed to transmit SMS.")
                )
            }
        }
        Result.success(threadKey)
    }

    /**
     * Sends an outgoing MMS (single or Group MMS, optional subject, multi-part SMIL media/vCard/text)
     * via OS-brokered `SmsManager.sendMultimediaMessage`.
     */
    suspend fun sendOutgoingMms(
        recipients: List<String>,
        body: String,
        subject: String? = null,
        attachments: List<PendingMmsAttachment>,
        replyToMessageId: Long? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanRecipients = recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val trimmedBody = body.trim()
        val trimmedSubject = subject?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanRecipients.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("At least one recipient is required."))
        }
        if (trimmedBody.isEmpty() && attachments.isEmpty() && trimmedSubject.isNullOrEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Message text or attachment is required."))
        }

        val now = System.currentTimeMillis()
        val primaryAddress = cleanRecipients.first()
        val isGroup = cleanRecipients.size > 1
        val threadKey = if (isGroup) {
            buildGroupThreadKey(cleanRecipients)
        } else {
            PhoneAndSenderNormalizer.normalizeThreadKey(primaryAddress)
        }

        val contactName = if (isGroup) {
            cleanRecipients.joinToString(", ") { addr -> contactCache.resolveContactName(addr) ?: addr }
        } else {
            contactCache.resolveContactName(primaryAddress)
        }

        val settings = settingsStore.getSettingsSnapshot()
        val (region, _) = regionDetector.detectActiveRegion(settings.regionOverride)
        val rule = senderRuleDao.getRuleForSender(threadKey)
        val displayBody = trimmedBody.ifEmpty {
            trimmedSubject ?: buildAttachmentFallbackSummary(attachments.map { it.contentType })
        }
        val outcome = pipeline.categorize(
            rawAddress = primaryAddress,
            body = displayBody,
            isSavedContact = contactName != null || isGroup,
            customRuleCategory = rule?.category,
            activeRegion = region
        )
        val assignedCategory = if (contactName != null || isGroup) {
            rule?.category ?: SmsCategory.PERSONAL
        } else {
            outcome.category
        }

        val quotedTarget = replyToMessageId?.let { smsDao.getMessageById(it) }
        val replyToSender = quotedTarget?.let {
            if (it.isIncoming) (it.contactName ?: it.rawAddress) else "You"
        }
        val replyToSnippet = quotedTarget?.body?.trim()?.take(100)

        val pduParts = mutableListOf<MmsPart>()
        if (trimmedBody.isNotEmpty()) {
            pduParts.add(
                MmsPart(
                    contentType = "text/plain",
                    contentId = "<text_0>",
                    contentLocation = "text_0.txt",
                    fileName = "text_0.txt",
                    data = trimmedBody.toByteArray(Charsets.UTF_8)
                )
            )
        }

        val attachmentEntities = mutableListOf<MmsAttachmentEntity>()
        attachments.forEachIndexed { idx, att ->
            val bytes = when {
                !att.localFilePath.isNullOrBlank() && File(att.localFilePath).exists() ->
                    File(att.localFilePath).readBytes()
                att.textContent != null -> att.textContent.toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }
            val persistedPath = if (!att.localFilePath.isNullOrBlank()) {
                att.localFilePath
            } else if (bytes.isNotEmpty()) {
                systemSmsHelper.persistMmsPartBytes(idx, att.contentType, att.fileName, bytes)?.absolutePath
            } else null

            val cid = "<part_$idx>"
            pduParts.add(
                MmsPart(
                    contentType = att.contentType,
                    contentId = cid,
                    contentLocation = att.fileName,
                    fileName = att.fileName,
                    data = bytes
                )
            )
            attachmentEntities.add(
                MmsAttachmentEntity(
                    messageId = 0L,
                    partIndex = idx,
                    contentType = att.contentType,
                    fileName = att.fileName,
                    localFilePath = persistedPath,
                    contentId = cid,
                    sizeBytes = bytes.size.toLong().coerceAtLeast(att.sizeBytes),
                    textContent = att.textContent
                )
            )
        }

        val txId = "traisms_${now}_${UUID.randomUUID().toString().take(6)}"
        val systemMmsId = systemSmsHelper.insertMmsIntoSystemProvider(
            fromAddress = "insert-address-token",
            toAddresses = cleanRecipients,
            subject = trimmedSubject,
            body = trimmedBody,
            parts = pduParts,
            timestampMillis = now,
            isIncoming = false,
            isRead = true,
            transactionId = txId
        )

        val messageId = smsDao.insertMessage(
            SmsMessageEntity(
                systemMmsId = systemMmsId,
                threadKey = threadKey,
                rawAddress = primaryAddress,
                contactName = contactName,
                body = displayBody,
                bodySha256 = BackupCryptoEngine.sha256Hex("$txId|$displayBody"),
                timestampMillis = now,
                isIncoming = false,
                isRead = true,
                isArchived = false,
                category = assignedCategory,
                extractedOtp = null,
                confidenceScore = 1.0f,
                classificationSource = "SENT",
                transportType = "MMS",
                deliveryStatus = "SENDING",
                mmsSubject = trimmedSubject,
                mmsTransactionId = txId,
                mmsSizeBytes = pduParts.sumOf { it.data.size.toLong() },
                groupRecipientsJson = encodeRecipientsList(if (isGroup) cleanRecipients else emptyList()),
                replyToMessageId = quotedTarget?.id,
                replyToSender = replyToSender,
                replyToSnippet = replyToSnippet
            )
        )

        if (attachmentEntities.isNotEmpty()) {
            smsDao.insertAttachments(attachmentEntities.map { it.copy(messageId = messageId) })
        }

        val pduBytes = MmsPduCodec.encodeSendReq(
            MmsSendReqInput(
                transactionId = txId,
                toAddresses = cleanRecipients,
                subject = trimmedSubject,
                epochSeconds = now / 1000L,
                deliveryReport = true,
                readReport = settings.sendReadReceipts,
                parts = pduParts
            )
        )

        val sendResult = systemSmsHelper.sendMultimediaMessage(pduBytes, messageId)
        if (sendResult.isFailure) {
            if (systemSmsHelper.isRunningOnEmulator()) {
                smsDao.updateDeliveryStatus(messageId, "SENT")
            } else {
                smsDao.updateDeliveryStatus(messageId, "FAILED")
                return@withContext Result.failure(
                    sendResult.exceptionOrNull() ?: IllegalStateException("Failed to transmit MMS.")
                )
            }
        }
        Result.success(threadKey)
    }

    suspend fun handleSmsDeliveryStatusCallback(
        messageId: Long,
        isDeliveryReport: Boolean,
        resultCode: Int
    ) = withContext(Dispatchers.IO) {
        val msg = smsDao.getMessageById(messageId) ?: return@withContext
        val targetStatus = if (isDeliveryReport) {
            if (resultCode == Activity.RESULT_OK) "DELIVERED" else return@withContext
        } else {
            if (resultCode == Activity.RESULT_OK) "SENT" else "FAILED"
        }
        upgradeDeliveryStatus(msg.id, msg.deliveryStatus, targetStatus)
    }

    suspend fun handleMmsSentResult(
        messageId: Long,
        pduFilePath: String?,
        resultCode: Int,
        responsePduBytes: ByteArray?
    ) = withContext(Dispatchers.IO) {
        pduFilePath?.let { path -> runCatching { File(path).delete() } }
        val msg = smsDao.getMessageById(messageId) ?: return@withContext
        if (resultCode == Activity.RESULT_OK) {
            val decodedResp = responsePduBytes?.let { MmsPduCodec.decodePdu(it) }
            val updatedMsgId = decodedResp?.messageId
            if (!updatedMsgId.isNullOrBlank()) {
                smsDao.updateMessage(
                    msg.copy(
                        deliveryStatus = if (statusRank(msg.deliveryStatus) < statusRank("SENT")) "SENT" else msg.deliveryStatus,
                        mmsTransactionId = updatedMsgId
                    )
                )
            } else {
                upgradeDeliveryStatus(msg.id, msg.deliveryStatus, "SENT")
            }
        } else {
            upgradeDeliveryStatus(msg.id, msg.deliveryStatus, "FAILED")
        }
    }

    suspend fun retryFailedMessage(messageId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val msg = smsDao.getMessageById(messageId)
            ?: return@withContext Result.failure(IllegalArgumentException("Message not found."))
        smsDao.updateDeliveryStatus(messageId, "SENDING")

        if (msg.transportType == "MMS") {
            val recipients = decodeRecipientsList(msg.groupRecipientsJson)
                .ifEmpty { listOf(msg.rawAddress) }
            val attachments = smsDao.getAttachmentsForMessage(messageId)
            val pduParts = mutableListOf<MmsPart>()
            if (msg.body.isNotBlank()) {
                pduParts.add(
                    MmsPart(
                        contentType = "text/plain",
                        contentId = "<text_0>",
                        contentLocation = "text_0.txt",
                        fileName = "text_0.txt",
                        data = msg.body.toByteArray(Charsets.UTF_8)
                    )
                )
            }
            attachments.forEachIndexed { idx, att ->
                val data = att.localFilePath?.let { File(it) }?.takeIf { it.exists() }?.readBytes()
                    ?: att.textContent?.toByteArray(Charsets.UTF_8)
                    ?: ByteArray(0)
                pduParts.add(
                    MmsPart(
                        contentType = att.contentType,
                        contentId = att.contentId ?: "<part_$idx>",
                        contentLocation = att.fileName ?: "part_$idx",
                        fileName = att.fileName,
                        data = data
                    )
                )
            }
            val txId = msg.mmsTransactionId ?: "retry_${System.currentTimeMillis()}"
            val pdu = MmsPduCodec.encodeSendReq(
                MmsSendReqInput(
                    transactionId = txId,
                    toAddresses = recipients,
                    subject = msg.mmsSubject,
                    epochSeconds = System.currentTimeMillis() / 1000L,
                    parts = pduParts
                )
            )
            systemSmsHelper.sendMultimediaMessage(pdu, messageId).map { Unit }
        } else {
            systemSmsHelper.sendMultipartSms(msg.rawAddress, msg.body, messageId = messageId)
        }.onFailure {
            if (systemSmsHelper.isRunningOnEmulator()) {
                smsDao.updateDeliveryStatus(messageId, "SENT")
            } else {
                smsDao.updateDeliveryStatus(messageId, "FAILED")
            }
        }
    }

    /**
     * Toggles a tapback emoji reaction on [targetMessageId] locally and optionally dispatches
     * a Google Messages-compatible SMS/MMS fallback reaction when RCS/MMS interop is active.
     */
    suspend fun sendReaction(targetMessageId: Long, emoji: String): Result<Unit> = withContext(Dispatchers.IO) {
        val target = smsDao.getMessageById(targetMessageId)
            ?: return@withContext Result.failure(IllegalArgumentException("Target message not found."))
        val now = System.currentTimeMillis()
        val (updatedJson, wasRemoved) = RcsInteropCodec.toggleReaction(
            existingJson = target.reactionsJson,
            emoji = emoji,
            senderAddress = "SELF",
            senderDisplay = "You",
            isFromSelf = true,
            timestampMillis = now
        )
        smsDao.updateReactionsJson(targetMessageId, updatedJson)

        val settings = settingsStore.getSettingsSnapshot()
        if (settings.rcsChatsEnabled &&
            PhoneAndSenderNormalizer.classifySenderType(target.rawAddress) != com.example.domain.classifier.SenderAddressType.ALPHANUMERIC_HEADER
        ) {
            val firstAttachmentMime = smsDao.getAttachmentsForMessage(targetMessageId).firstOrNull()?.contentType
            val fallbackText = RcsInteropCodec.encodeReactionFallback(
                emoji = emoji,
                targetBody = target.body,
                primaryAttachmentMime = firstAttachmentMime,
                isRemoval = wasRemoved
            )
            val recipients = decodeRecipientsList(target.groupRecipientsJson)
                .ifEmpty { listOf(target.rawAddress) }
            if (recipients.size > 1) {
                val pdu = MmsPduCodec.encodeSendReq(
                    MmsSendReqInput(
                        transactionId = "rx_${now}",
                        toAddresses = recipients,
                        epochSeconds = now / 1000L,
                        deliveryReport = false,
                        readReport = false,
                        parts = listOf(
                            MmsPart(
                                contentType = "text/plain",
                                contentId = "<text_0>",
                                contentLocation = "text_0.txt",
                                data = fallbackText.toByteArray(Charsets.UTF_8)
                            )
                        )
                    )
                )
                systemSmsHelper.sendMultimediaMessage(pdu, targetMessageId)
            } else if (systemSmsHelper.hasSendSmsPermission()) {
                systemSmsHelper.sendMultipartSms(
                    destinationAddress = recipients.first(),
                    messageText = fallbackText,
                    messageId = -1L,
                    requestDeliveryReport = false
                )
            }
        }
        Result.success(Unit)
    }

    private suspend fun tryFoldReactionIntoThread(
        threadKey: String,
        senderAddress: String,
        senderDisplay: String,
        isFromSelf: Boolean,
        timestampMillis: Long,
        parsed: ParsedReactionFallback
    ): Boolean {
        val candidates = smsDao.getMessagesInThreadSnapshot(threadKey)
        if (candidates.isEmpty()) return false

        val quoted = parsed.quotedText?.trim()?.lowercase(Locale.ROOT)
        val matched = if (!quoted.isNullOrEmpty()) {
            candidates.firstOrNull { msg ->
                val b = msg.body.trim().lowercase(Locale.ROOT)
                b == quoted || b.startsWith(quoted) || b.contains(quoted)
            }
        } else {
            candidates.firstOrNull()
        } ?: return false

        val currentList = RcsInteropCodec.decodeReactions(matched.reactionsJson).toMutableList()
        if (parsed.isRemoval) {
            currentList.removeAll { it.senderAddress == senderAddress && it.emoji == parsed.emoji }
        } else {
            currentList.removeAll { it.senderAddress == senderAddress }
            currentList.add(
                MessageReaction(
                    emoji = parsed.emoji,
                    senderAddress = senderAddress,
                    senderDisplay = senderDisplay,
                    timestampMillis = timestampMillis,
                    isFromSelf = isFromSelf
                )
            )
        }
        smsDao.updateReactionsJson(matched.id, RcsInteropCodec.encodeReactions(currentList))
        return true
    }

    private suspend fun upgradeDeliveryStatus(messageId: Long, currentStatus: String, newStatus: String) {
        if (newStatus == "FAILED") {
            if (statusRank(currentStatus) <= statusRank("SENDING")) {
                smsDao.updateDeliveryStatus(messageId, "FAILED")
            }
            return
        }
        if (statusRank(newStatus) > statusRank(currentStatus)) {
            smsDao.updateDeliveryStatus(messageId, newStatus)
        }
    }

    private fun statusRank(status: String): Int = when (status.uppercase(Locale.ROOT)) {
        "NONE" -> 0
        "FAILED" -> 1
        "SENDING" -> 2
        "SENT" -> 3
        "DELIVERED" -> 4
        "READ" -> 5
        else -> 0
    }

    private fun buildGroupThreadKey(recipients: List<String>): String {
        val normalized = recipients
            .map { PhoneAndSenderNormalizer.normalizeThreadKey(it) }
            .distinct()
            .sorted()
            .joinToString("|")
        return "GRP_${BackupCryptoEngine.sha256Hex(normalized).take(12)}"
    }

    private fun encodeRecipientsList(recipients: List<String>): String? {
        if (recipients.isEmpty()) return null
        return json.encodeToString(ListSerializer(String.serializer()), recipients)
    }

    private fun decodeRecipientsList(recipientsJson: String?): List<String> {
        if (recipientsJson.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), recipientsJson)
        }.getOrDefault(emptyList())
    }

    private fun buildAttachmentFallbackSummary(mimeTypes: List<String>): String {
        val first = mimeTypes.firstOrNull()?.lowercase(Locale.ROOT) ?: return "MMS message"
        return when {
            first.startsWith("image/") -> "📷 Photo"
            first.startsWith("video/") -> "🎬 Video"
            first.startsWith("audio/") -> "🎵 Audio message"
            first.contains("vcard") -> "👤 Contact card"
            else -> "📎 Attachment"
        }
    }

    private fun formatBytesLabel(bytes: Long): String = when {
        bytes <= 0L -> "MMS"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    suspend fun markThreadAsRead(threadKey: String) = withContext(Dispatchers.IO) {
        val threadMsgs = smsDao.getMessagesInThreadSnapshot(threadKey)
        val settings = settingsStore.getSettingsSnapshot()
        if (settings.sendReadReceipts && systemSmsHelper.isDefaultSmsApp()) {
            threadMsgs.filter {
                it.isIncoming &&
                    it.transportType == "MMS" &&
                    it.deliveryStatus == "READ_REPORT_REQUESTED"
            }.forEach { mmsMsg ->
                val pdu = MmsPduCodec.encodeReadRecInd(
                    messageId = mmsMsg.mmsTransactionId ?: "rr_${mmsMsg.id}",
                    toAddress = mmsMsg.rawAddress,
                    epochSeconds = System.currentTimeMillis() / 1000L
                )
                systemSmsHelper.sendMultimediaMessage(pdu, mmsMsg.id)
                smsDao.updateDeliveryStatus(mmsMsg.id, "NONE")
            }
        }
        smsDao.markThreadRead(threadKey)
        val systemIds = smsDao.getSystemIdsForThread(threadKey)
        systemSmsHelper.markSystemMessagesRead(systemIds)
        val mmsIds = smsDao.getSystemMmsIdsForThread(threadKey)
        systemSmsHelper.markSystemMmsRead(mmsIds)
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
        val mmsId = smsDao.getSystemMmsIdForMessage(messageId)
        if (mmsId != null) {
            systemSmsHelper.deleteMmsFromSystemProvider(listOf(mmsId))
        }
        smsDao.getAttachmentsForMessage(messageId).forEach { att ->
            att.localFilePath?.let { path -> runCatching { File(path).delete() } }
        }
        smsDao.deleteAttachmentsForMessage(messageId)
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
        val mmsIds = smsDao.getSystemMmsIdsForThread(threadKey)
        systemSmsHelper.deleteMmsFromSystemProvider(mmsIds)
        smsDao.deleteAttachmentsForThread(threadKey)
        smsDao.deleteThread(threadKey)
    }

    suspend fun deleteThreadsPermanently(threadKeys: List<String>) = withContext(Dispatchers.IO) {
        if (threadKeys.isEmpty()) return@withContext
        val sysIds = smsDao.getSystemIdsForThreads(threadKeys)
        systemSmsHelper.deleteFromSystemProvider(sysIds)
        val mmsIds = smsDao.getSystemMmsIdsForThreads(threadKeys)
        systemSmsHelper.deleteMmsFromSystemProvider(mmsIds)
        smsDao.deleteAttachmentsForThreads(threadKeys)
        smsDao.deleteThreads(threadKeys)
    }

    suspend fun countActiveMessagesInCategory(category: SmsCategory): Int = withContext(Dispatchers.IO) {
        smsDao.countActiveMessagesInCategory(category)
    }

    suspend fun deleteAllActiveInCategory(category: SmsCategory): Int = withContext(Dispatchers.IO) {
        val sysIds = smsDao.getSystemIdsInActiveCategory(category)
        systemSmsHelper.deleteFromSystemProvider(sysIds)
        val mmsIds = smsDao.getSystemMmsIdsInActiveCategory(category)
        systemSmsHelper.deleteMmsFromSystemProvider(mmsIds)
        smsDao.deleteAttachmentsInActiveCategory(category)
        smsDao.deleteActiveMessagesInCategory(category)
    }

    /**
     * Strictly marks ONLY unarchived unread messages in [category] as read (`isRead = true`)
     * in both SQLCipher Room and Android's `Telephony.Sms` / `Telephony.Mms` ContentProvider,
     * leaving all other categories and archived conversations untouched.
     */
    suspend fun markAllActiveInCategoryAsRead(category: SmsCategory): Int = withContext(Dispatchers.IO) {
        val sysIds = smsDao.getUnreadSystemIdsInActiveCategory(category)
        val mmsIds = smsDao.getUnreadSystemMmsIdsInActiveCategory(category)
        val threadKeys = smsDao.getUnreadThreadKeysInActiveCategory(category)
        val markedCount = smsDao.markAllReadInActiveCategory(category)
        if (sysIds.isNotEmpty()) {
            systemSmsHelper.markSystemMessagesRead(sysIds)
        }
        if (mmsIds.isNotEmpty()) {
            systemSmsHelper.markSystemMmsRead(mmsIds)
        }
        threadKeys.forEach { key ->
            SmsNotificationHelper.cancelNotification(
                appContext,
                SmsNotificationHelper.notificationIdForThread(key)
            )
        }
        markedCount
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
        val allMmsIds = smsDao.getAllSystemMmsIds()
        systemSmsHelper.deleteMmsFromSystemProvider(allMmsIds)
        systemSmsHelper.wipeAllFromSystemProvider()
        runCatching { systemSmsHelper.getMmsMediaDir().deleteRecursively() }
        smsDao.deleteAllAttachments()
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
     * Exports all SMS/MMS messages, archive states, reactions, replies, and custom sender rules
     * as GZIP + AES-256-GCM encrypted `.trai` archive to the user's Google Drive SAF URI.
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
                    classificationSource = msg.classificationSource,
                    transportType = msg.transportType,
                    mmsSubject = msg.mmsSubject,
                    replyToSender = msg.replyToSender,
                    replyToSnippet = msg.replyToSnippet,
                    reactionsJson = msg.reactionsJson,
                    groupRecipientsJson = msg.groupRecipientsJson
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

            // 2. Deduplicate and restore SMS/MMS messages
            var inserted = 0
            var duplicates = 0
            val toInsert = ArrayList<SmsMessageEntity>()

            for (record in payload.messages) {
                val groupRecipients = decodeRecipientsList(record.groupRecipientsJson)
                val normalizedKey = if (groupRecipients.size > 1) {
                    buildGroupThreadKey(groupRecipients)
                } else {
                    PhoneAndSenderNormalizer.normalizeThreadKey(record.rawAddress)
                }
                val bodySha = record.bodySha256.ifEmpty { BackupCryptoEngine.sha256Hex(record.body) }

                if (smsDao.existsDuplicate(normalizedKey, record.timestampMillis, bodySha)) {
                    duplicates++
                    if (record.isArchived) {
                        smsDao.setThreadArchived(normalizedKey, true)
                    }
                    continue
                }

                val sysId = if (record.transportType == "SMS") {
                    systemSmsHelper.insertIntoSystemProvider(
                        address = record.rawAddress,
                        body = record.body,
                        timestampMillis = record.timestampMillis,
                        isIncoming = record.isIncoming,
                        isRead = record.isRead
                    )
                } else null

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
                        classificationSource = record.classificationSource,
                        transportType = record.transportType,
                        mmsSubject = record.mmsSubject,
                        replyToSender = record.replyToSender,
                        replyToSnippet = record.replyToSnippet,
                        reactionsJson = record.reactionsJson,
                        groupRecipientsJson = record.groupRecipientsJson
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
