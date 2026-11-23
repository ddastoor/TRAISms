package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SenderRuleDao {

    @Query("SELECT * FROM sender_category_rules ORDER BY updatedAtMillis DESC")
    fun observeAllRules(): Flow<List<SenderCategoryRuleEntity>>

    @Query("SELECT * FROM sender_category_rules")
    suspend fun getAllRules(): List<SenderCategoryRuleEntity>

    @Query("SELECT * FROM sender_category_rules WHERE normalizedSender = :normalizedSender LIMIT 1")
    suspend fun getRuleForSender(normalizedSender: String): SenderCategoryRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: SenderCategoryRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(rules: List<SenderCategoryRuleEntity>)

    @Query("DELETE FROM sender_category_rules WHERE normalizedSender = :normalizedSender")
    suspend fun deleteRule(normalizedSender: String)
}
