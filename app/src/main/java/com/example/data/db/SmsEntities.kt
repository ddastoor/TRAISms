package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
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
    val classificationSource: String = "PIPELINE"
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
    val classificationSource: String
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
