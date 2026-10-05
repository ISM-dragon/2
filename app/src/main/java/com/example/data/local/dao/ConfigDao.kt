package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    // API Slots 1..6
    @Query("SELECT * FROM api_configurations ORDER BY slotIndex ASC")
    fun getAllApiConfigs(): Flow<List<ApiConfigurationEntity>>

    @Query("SELECT * FROM api_configurations ORDER BY slotIndex ASC")
    suspend fun getAllApiConfigsList(): List<ApiConfigurationEntity>

    @Query("SELECT * FROM api_configurations WHERE slotIndex = :slotIndex LIMIT 1")
    suspend fun getApiConfigBySlot(slotIndex: Int): ApiConfigurationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateApiConfig(config: ApiConfigurationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApiConfigs(configs: List<ApiConfigurationEntity>)

    @Query("UPDATE api_configurations SET status = :status, cooldownUntil = :cooldownUntil, lastErrorMessage = :error WHERE slotIndex = :slotIndex")
    suspend fun updateSlotStatus(slotIndex: Int, status: String, cooldownUntil: Long, error: String?)

    @Query("UPDATE api_configurations SET usageCount = usageCount + 1, lastRequestTime = :time WHERE slotIndex = :slotIndex")
    suspend fun incrementSlotUsage(slotIndex: Int, time: Long)

    @Query("UPDATE api_configurations SET errorCount = errorCount + 1, lastErrorMessage = :error WHERE slotIndex = :slotIndex")
    suspend fun incrementSlotError(slotIndex: Int, error: String)

    // Gmail Configuration
    @Query("SELECT * FROM gmail_configuration WHERE id = 1 LIMIT 1")
    fun getGmailConfigFlow(): Flow<GmailConfigurationEntity?>

    @Query("SELECT * FROM gmail_configuration WHERE id = 1 LIMIT 1")
    suspend fun getGmailConfig(): GmailConfigurationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGmailConfig(config: GmailConfigurationEntity)

    // Offer Templates
    @Query("SELECT * FROM offer_templates WHERE id = 'DEFAULT' LIMIT 1")
    fun getOfferTemplateFlow(): Flow<OfferTemplateEntity?>

    @Query("SELECT * FROM offer_templates WHERE id = 'DEFAULT' LIMIT 1")
    suspend fun getOfferTemplate(): OfferTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOfferTemplate(template: OfferTemplateEntity)
}
