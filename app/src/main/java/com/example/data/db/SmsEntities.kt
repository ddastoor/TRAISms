package com.example.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter

@Entity(
    tableName = "sms_messages",
    indices = [
        Index(value = ["threadKey", "isArchived", "timestampMillis"]),
        Index(value = ["category", "isArchived", "timestampMillis"]),
        Index(value = ["systemSmsId"]),
        Index(value = ["threadKey", "timestampMillis", "bodySha256"])
    ]
)
data class SmsMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val systemSmsId: Long? = null,
    val threadKey: String,
    val rawAddress: String,
    val contactName: String? = null,
    val body: String,
    val bodySha256: String,
    val timestampMillis: Long,
    val isIncoming: Boolean,
    val isRead: Boolean,
    val isArchived: Boolean = false,
    val category: SmsCategory,
    val extractedOtp: String? = null,
    val confidenceScore: Float = 1.0f,
    val classificationSource: String = "PIPELINE",
    val transportType: String = "SMS",
    val deliveryStatus: String = "NONE",
    val systemMmsId: Long? = null,
    val mmsSubject: String? = null,
    val mmsContentLocation: String? = null,
    val mmsTransactionId: String? = null,
    val mmsSizeBytes: Long = 0L,
    val groupRecipientsJson: String? = null,
    val replyToMessageId: Long? = null,
    val replyToSender: String? = null,
    val replyToSnippet: String? = null,
    val reactionsJson: String? = null,
    val isHiddenReaction: Boolean = false
)

@Entity(
    tableName = "mms_attachments",
    indices = [Index(value = ["messageId"])]
)
data class MmsAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val messageId: Long,
    val partIndex: Int = 0,
    val contentType: String,
    val fileName: String? = null,
    val localFilePath: String? = null,
    val contentId: String? = null,
    val sizeBytes: Long = 0L,
    val textContent: String? = null
)

data class SmsMessageWithAttachments(
    @Embedded val message: SmsMessageEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId"
    )
    val attachments: List<MmsAttachmentEntity> = emptyList()
)

@Entity(tableName = "sender_category_rules")
data class SenderCategoryRuleEntity(
    @PrimaryKey
    val normalizedSender: String,
    val displaySender: String,
    val category: SmsCategory,
    val updatedAtMillis: Long
)

data class ThreadSummary(
    val latestMessageId: Long,
    val threadKey: String,
    val rawAddress: String,
    val contactName: String?,
    val latestBody: String,
    val latestTimestampMillis: Long,
    val category: SmsCategory,
    val extractedOtp: String?,
    val isArchived: Boolean,
    val unreadCount: Int,
    val messageCount: Int,
    val classificationSource: String,
    val transportType: String = "SMS",
    val deliveryStatus: String = "NONE",
    val hasAttachments: Boolean = false,
    val mmsSubject: String? = null,
    val groupRecipientsJson: String? = null
)

data class CategoryUnreadCount(
    val category: SmsCategory,
    val unreadCount: Int,
    val totalCount: Int
)

class SmsTypeConverters {
    @TypeConverter
    fun fromCategory(category: SmsCategory): String = category.name

    @TypeConverter
    fun toCategory(value: String): SmsCategory =
        runCatching { SmsCategory.valueOf(value) }.getOrDefault(SmsCategory.OTHER)
}
