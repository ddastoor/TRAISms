package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsDao {

    @Query(
        """
        SELECT 
            m.id AS latestMessageId,
            m.threadKey AS threadKey,
            m.rawAddress AS rawAddress,
            m.contactName AS contactName,
            m.body AS latestBody,
            m.timestampMillis AS latestTimestampMillis,
            m.category AS category,
            m.extractedOtp AS extractedOtp,
            m.isArchived AS isArchived,
            agg.unreadCount AS unreadCount,
            agg.messageCount AS messageCount,
            m.classificationSource AS classificationSource,
            m.transportType AS transportType,
            m.deliveryStatus AS deliveryStatus,
            EXISTS(SELECT 1 FROM mms_attachments att WHERE att.messageId = m.id) AS hasAttachments,
            m.mmsSubject AS mmsSubject,
            m.groupRecipientsJson AS groupRecipientsJson
        FROM sms_messages m
        INNER JOIN (
            SELECT 
                threadKey,
                MAX(timestampMillis * 1000000 + id) AS maxSortKey,
                SUM(CASE WHEN isRead = 0 AND isIncoming = 1 THEN 1 ELSE 0 END) AS unreadCount,
                COUNT(*) AS messageCount
            FROM sms_messages
            WHERE isArchived = 0 AND category = :category AND isHiddenReaction = 0
            GROUP BY threadKey
        ) agg ON m.threadKey = agg.threadKey AND (m.timestampMillis * 1000000 + m.id) = agg.maxSortKey
        WHERE m.isArchived = 0 AND m.category = :category AND m.isHiddenReaction = 0
        ORDER BY m.timestampMillis DESC, m.id DESC
        """
    )
    fun observeCategoryThreads(category: SmsCategory): Flow<List<ThreadSummary>>

    @Query(
        """
        SELECT 
            m.id AS latestMessageId,
            m.threadKey AS threadKey,
            m.rawAddress AS rawAddress,
            m.contactName AS contactName,
            m.body AS latestBody,
            m.timestampMillis AS latestTimestampMillis,
            m.category AS category,
            m.extractedOtp AS extractedOtp,
            m.isArchived AS isArchived,
            agg.unreadCount AS unreadCount,
            agg.messageCount AS messageCount,
            m.classificationSource AS classificationSource,
            m.transportType AS transportType,
            m.deliveryStatus AS deliveryStatus,
            EXISTS(SELECT 1 FROM mms_attachments att WHERE att.messageId = m.id) AS hasAttachments,
            m.mmsSubject AS mmsSubject,
            m.groupRecipientsJson AS groupRecipientsJson
        FROM sms_messages m
        INNER JOIN (
            SELECT 
                threadKey,
                MAX(timestampMillis * 1000000 + id) AS maxSortKey,
                COUNT(*) AS unreadCount,
                (SELECT COUNT(*) FROM sms_messages all_m WHERE all_m.threadKey = sms_messages.threadKey AND all_m.isArchived = 0 AND all_m.category = :category AND all_m.isHiddenReaction = 0) AS messageCount
            FROM sms_messages
            WHERE isArchived = 0 AND category = :category AND isRead = 0 AND isIncoming = 1 AND isHiddenReaction = 0
            GROUP BY threadKey
        ) agg ON m.threadKey = agg.threadKey AND (m.timestampMillis * 1000000 + m.id) = agg.maxSortKey
        WHERE m.isArchived = 0 AND m.category = :category AND m.isRead = 0 AND m.isIncoming = 1 AND m.isHiddenReaction = 0
        ORDER BY m.timestampMillis DESC, m.id DESC
        """
    )
    fun observeUnreadCategoryThreads(category: SmsCategory): Flow<List<ThreadSummary>>

    @Query(
        """
        SELECT 
            m.id AS latestMessageId,
            m.threadKey AS threadKey,
            m.rawAddress AS rawAddress,
            m.contactName AS contactName,
            m.body AS latestBody,
            m.timestampMillis AS latestTimestampMillis,
            m.category AS category,
            m.extractedOtp AS extractedOtp,
            m.isArchived AS isArchived,
            agg.unreadCount AS unreadCount,
            agg.messageCount AS messageCount,
            m.classificationSource AS classificationSource,
            m.transportType AS transportType,
            m.deliveryStatus AS deliveryStatus,
            EXISTS(SELECT 1 FROM mms_attachments att WHERE att.messageId = m.id) AS hasAttachments,
            m.mmsSubject AS mmsSubject,
            m.groupRecipientsJson AS groupRecipientsJson
        FROM sms_messages m
        INNER JOIN (
            SELECT 
                threadKey,
                MAX(timestampMillis * 1000000 + id) AS maxSortKey,
                SUM(CASE WHEN isRead = 0 AND isIncoming = 1 THEN 1 ELSE 0 END) AS unreadCount,
                COUNT(*) AS messageCount
            FROM sms_messages
            WHERE isArchived = 0 AND isHiddenReaction = 0 AND (
                rawAddress LIKE '%' || :query || '%' OR
                IFNULL(contactName, '') LIKE '%' || :query || '%' OR
                body LIKE '%' || :query || '%' OR
                IFNULL(mmsSubject, '') LIKE '%' || :query || '%' OR
                IFNULL(extractedOtp, '') LIKE '%' || :query || '%'
            )
            GROUP BY threadKey
        ) agg ON m.threadKey = agg.threadKey AND (m.timestampMillis * 1000000 + m.id) = agg.maxSortKey
        WHERE m.isArchived = 0 AND m.isHiddenReaction = 0
        ORDER BY m.timestampMillis DESC, m.id DESC
        """
    )
    fun searchActiveThreads(query: String): Flow<List<ThreadSummary>>

    @Query(
        """
        SELECT 
            m.id AS latestMessageId,
            m.threadKey AS threadKey,
            m.rawAddress AS rawAddress,
            m.contactName AS contactName,
            m.body AS latestBody,
            m.timestampMillis AS latestTimestampMillis,
            m.category AS category,
            m.extractedOtp AS extractedOtp,
            m.isArchived AS isArchived,
            agg.unreadCount AS unreadCount,
            agg.messageCount AS messageCount,
            m.classificationSource AS classificationSource,
            m.transportType AS transportType,
            m.deliveryStatus AS deliveryStatus,
            EXISTS(SELECT 1 FROM mms_attachments att WHERE att.messageId = m.id) AS hasAttachments,
            m.mmsSubject AS mmsSubject,
            m.groupRecipientsJson AS groupRecipientsJson
        FROM sms_messages m
        INNER JOIN (
            SELECT 
                threadKey,
                MAX(timestampMillis * 1000000 + id) AS maxSortKey,
                SUM(CASE WHEN isRead = 0 AND isIncoming = 1 THEN 1 ELSE 0 END) AS unreadCount,
                COUNT(*) AS messageCount
            FROM sms_messages
            WHERE isArchived = 1 AND isHiddenReaction = 0
            GROUP BY threadKey
        ) agg ON m.threadKey = agg.threadKey AND (m.timestampMillis * 1000000 + m.id) = agg.maxSortKey
        WHERE m.isArchived = 1 AND m.isHiddenReaction = 0
        ORDER BY m.timestampMillis DESC, m.id DESC
        """
    )
    fun observeArchivedThreads(): Flow<List<ThreadSummary>>

    @Query(
        """
        SELECT 
            category AS category,
            SUM(CASE WHEN isRead = 0 AND isIncoming = 1 THEN 1 ELSE 0 END) AS unreadCount,
            COUNT(*) AS totalCount
        FROM sms_messages
        WHERE isArchived = 0 AND isHiddenReaction = 0
        GROUP BY category
        """
    )
    fun observeCategoryCounts(): Flow<List<CategoryUnreadCount>>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE isArchived = 1 AND isHiddenReaction = 0")
    fun observeArchivedMessageCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE isHiddenReaction = 0")
    fun observeTotalMessageCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE isHiddenReaction = 0")
    suspend fun getTotalMessageCount(): Int

    @Query("SELECT * FROM sms_messages WHERE threadKey = :threadKey AND isHiddenReaction = 0 ORDER BY timestampMillis ASC, id ASC")
    fun observeThreadMessages(threadKey: String): Flow<List<SmsMessageEntity>>

    @Transaction
    @Query("SELECT * FROM sms_messages WHERE threadKey = :threadKey AND isHiddenReaction = 0 ORDER BY timestampMillis ASC, id ASC")
    fun observeThreadMessagesWithAttachments(threadKey: String): Flow<List<SmsMessageWithAttachments>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<MmsAttachmentEntity>)

    @Query("SELECT * FROM mms_attachments WHERE messageId = :messageId ORDER BY partIndex ASC, id ASC")
    suspend fun getAttachmentsForMessage(messageId: Long): List<MmsAttachmentEntity>

    @Query("SELECT * FROM sms_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: Long): SmsMessageEntity?

    @Query("SELECT * FROM sms_messages WHERE mmsTransactionId = :transactionId LIMIT 1")
    suspend fun getMessageByTransactionId(transactionId: String): SmsMessageEntity?

    @Query("SELECT * FROM sms_messages WHERE threadKey = :threadKey AND isHiddenReaction = 0 ORDER BY timestampMillis DESC, id DESC")
    suspend fun getMessagesInThreadSnapshot(threadKey: String): List<SmsMessageEntity>

    @Query("SELECT * FROM sms_messages ORDER BY timestampMillis ASC, id ASC")
    suspend fun getAllMessagesSnapshot(): List<SmsMessageEntity>

    @Query("SELECT systemSmsId FROM sms_messages WHERE systemSmsId IS NOT NULL")
    suspend fun getImportedSystemSmsIds(): List<Long>

    @Query("SELECT systemMmsId FROM sms_messages WHERE systemMmsId IS NOT NULL")
    suspend fun getImportedSystemMmsIds(): List<Long>

    @Query("SELECT systemMmsId FROM sms_messages WHERE threadKey = :threadKey AND systemMmsId IS NOT NULL")
    suspend fun getSystemMmsIdsForThread(threadKey: String): List<Long>

    @Query("SELECT systemMmsId FROM sms_messages WHERE threadKey IN (:threadKeys) AND systemMmsId IS NOT NULL")
    suspend fun getSystemMmsIdsForThreads(threadKeys: List<String>): List<Long>

    @Query("SELECT systemMmsId FROM sms_messages WHERE category = :category AND isArchived = 0 AND systemMmsId IS NOT NULL")
    suspend fun getSystemMmsIdsInActiveCategory(category: SmsCategory): List<Long>

    @Query("SELECT systemMmsId FROM sms_messages WHERE id = :id AND systemMmsId IS NOT NULL")
    suspend fun getSystemMmsIdForMessage(id: Long): Long?

    @Query("SELECT systemMmsId FROM sms_messages WHERE systemMmsId IS NOT NULL")
    suspend fun getAllSystemMmsIds(): List<Long>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sms_messages 
            WHERE threadKey = :threadKey 
              AND timestampMillis = :timestampMillis 
              AND bodySha256 = :bodySha256
        )
        """
    )
    suspend fun existsDuplicate(threadKey: String, timestampMillis: Long, bodySha256: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sms_messages WHERE threadKey = :threadKey AND isArchived = 1)")
    suspend fun isThreadCurrentlyArchived(threadKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: SmsMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<SmsMessageEntity>)

    @Update
    suspend fun updateMessage(message: SmsMessageEntity)

    @Update
    suspend fun updateMessages(messages: List<SmsMessageEntity>)

    @Query("UPDATE sms_messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun updateDeliveryStatus(id: Long, status: String)

    @Query("UPDATE sms_messages SET reactionsJson = :reactionsJson WHERE id = :id")
    suspend fun updateReactionsJson(id: Long, reactionsJson: String?)

    @Query("UPDATE sms_messages SET isRead = 1 WHERE threadKey = :threadKey AND isRead = 0")
    suspend fun markThreadRead(threadKey: String)

    @Query("UPDATE sms_messages SET isArchived = :archived WHERE threadKey = :threadKey")
    suspend fun setThreadArchived(threadKey: String, archived: Boolean)

    @Query("UPDATE sms_messages SET isArchived = :archived WHERE threadKey IN (:threadKeys)")
    suspend fun setThreadsArchived(threadKeys: List<String>, archived: Boolean)

    @Query(
        """
        UPDATE sms_messages 
        SET category = :category, classificationSource = :source, confidenceScore = 1.0 
        WHERE threadKey = :threadKey
        """
    )
    suspend fun updateCategoryForThread(threadKey: String, category: SmsCategory, source: String)

    @Query("SELECT systemSmsId FROM sms_messages WHERE id = :id AND systemSmsId IS NOT NULL")
    suspend fun getSystemIdForMessage(id: Long): Long?

    @Query("SELECT id FROM sms_messages WHERE threadKey = :threadKey ORDER BY timestampMillis DESC, id DESC LIMIT 1")
    suspend fun getLatestMessageIdForThread(threadKey: String): Long?

    @Query("DELETE FROM mms_attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsForMessage(messageId: Long)

    @Query("DELETE FROM mms_attachments WHERE messageId IN (SELECT id FROM sms_messages WHERE threadKey = :threadKey)")
    suspend fun deleteAttachmentsForThread(threadKey: String)

    @Query("DELETE FROM mms_attachments WHERE messageId IN (SELECT id FROM sms_messages WHERE threadKey IN (:threadKeys))")
    suspend fun deleteAttachmentsForThreads(threadKeys: List<String>)

    @Query("DELETE FROM mms_attachments WHERE messageId IN (SELECT id FROM sms_messages WHERE category = :category AND isArchived = 0)")
    suspend fun deleteAttachmentsInActiveCategory(category: SmsCategory)

    @Query("DELETE FROM mms_attachments")
    suspend fun deleteAllAttachments()

    @Query("DELETE FROM sms_messages WHERE id = :id")
    suspend fun deleteMessageById(id: Long)

    @Query("SELECT systemSmsId FROM sms_messages WHERE threadKey = :threadKey AND systemSmsId IS NOT NULL")
    suspend fun getSystemIdsForThread(threadKey: String): List<Long>

    @Query("DELETE FROM sms_messages WHERE threadKey = :threadKey")
    suspend fun deleteThread(threadKey: String)

    @Query("SELECT systemSmsId FROM sms_messages WHERE threadKey IN (:threadKeys) AND systemSmsId IS NOT NULL")
    suspend fun getSystemIdsForThreads(threadKeys: List<String>): List<Long>

    @Query("DELETE FROM sms_messages WHERE threadKey IN (:threadKeys)")
    suspend fun deleteThreads(threadKeys: List<String>)

    @Query("SELECT COUNT(*) FROM sms_messages WHERE category = :category AND isArchived = 0 AND isHiddenReaction = 0")
    suspend fun countActiveMessagesInCategory(category: SmsCategory): Int

    @Query("SELECT systemSmsId FROM sms_messages WHERE category = :category AND isArchived = 0 AND systemSmsId IS NOT NULL")
    suspend fun getSystemIdsInActiveCategory(category: SmsCategory): List<Long>

    @Query("DELETE FROM sms_messages WHERE category = :category AND isArchived = 0")
    suspend fun deleteActiveMessagesInCategory(category: SmsCategory): Int

    @Query(
        """
        SELECT systemSmsId FROM sms_messages 
        WHERE category = :category 
          AND isArchived = 0 
          AND isRead = 0 
          AND isIncoming = 1 
          AND isHiddenReaction = 0 
          AND systemSmsId IS NOT NULL
        """
    )
    suspend fun getUnreadSystemIdsInActiveCategory(category: SmsCategory): List<Long>

    @Query(
        """
        SELECT systemMmsId FROM sms_messages 
        WHERE category = :category 
          AND isArchived = 0 
          AND isRead = 0 
          AND isIncoming = 1 
          AND isHiddenReaction = 0 
          AND systemMmsId IS NOT NULL
        """
    )
    suspend fun getUnreadSystemMmsIdsInActiveCategory(category: SmsCategory): List<Long>

    @Query(
        """
        SELECT DISTINCT threadKey FROM sms_messages 
        WHERE category = :category 
          AND isArchived = 0 
          AND isRead = 0 
          AND isIncoming = 1 
          AND isHiddenReaction = 0
        """
    )
    suspend fun getUnreadThreadKeysInActiveCategory(category: SmsCategory): List<String>

    @Query(
        """
        UPDATE sms_messages 
        SET isRead = 1 
        WHERE category = :category 
          AND isArchived = 0 
          AND isRead = 0 
          AND isIncoming = 1 
          AND isHiddenReaction = 0
        """
    )
    suspend fun markAllReadInActiveCategory(category: SmsCategory): Int

    /**
     * Strictly deletes ONLY unarchived Promotional messages older than cutoffMillis.
     * Never touches Personal, Transactional, Service, OTP, Government, Unknown, Other, or Archived.
     */
    @Query(
        """
        SELECT systemSmsId FROM sms_messages 
        WHERE category = 'PROMOTIONAL' 
          AND isArchived = 0 
          AND timestampMillis < :cutoffMillis 
          AND systemSmsId IS NOT NULL
        """
    )
    suspend fun getExpiredPromotionalSystemIds(cutoffMillis: Long): List<Long>

    @Query(
        """
        DELETE FROM sms_messages 
        WHERE category = 'PROMOTIONAL' 
          AND isArchived = 0 
          AND timestampMillis < :cutoffMillis
        """
    )
    suspend fun deleteExpiredPromotionalMessages(cutoffMillis: Long): Int

    /**
     * Strictly deletes ONLY unarchived OTP messages older than cutoffMillis.
     * Never touches Personal, Transactional, Service, Promotional, Government, Unknown, Other, or Archived.
     */
    @Query(
        """
        SELECT systemSmsId FROM sms_messages 
        WHERE category = 'OTP' 
          AND isArchived = 0 
          AND timestampMillis < :cutoffMillis 
          AND systemSmsId IS NOT NULL
        """
    )
    suspend fun getExpiredOtpSystemIds(cutoffMillis: Long): List<Long>

    @Query(
        """
        DELETE FROM sms_messages 
        WHERE category = 'OTP' 
          AND isArchived = 0 
          AND timestampMillis < :cutoffMillis
        """
    )
    suspend fun deleteExpiredOtpMessages(cutoffMillis: Long): Int

    @Query("SELECT systemSmsId FROM sms_messages WHERE systemSmsId IS NOT NULL")
    suspend fun getAllSystemIds(): List<Long>

    @Query("DELETE FROM sms_messages")
    suspend fun deleteAllMessages()
}
