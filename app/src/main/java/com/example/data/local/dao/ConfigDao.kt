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

    @Query("UPDATE gmail_configuration SET authStatus = :status, lastError = :lastError WHERE id = 1")
    suspend fun updateGmailAuthStatus(status: String, lastError: String?): Int

    @Query("""
        UPDATE gmail_configuration
        SET accountEmail = :accountEmail,
            senderName = :senderName,
            signature = :signature,
            defaultSubjectTemplate = :defaultSubjectTemplate
        WHERE id = 1
    """)
    suspend fun updateGmailAccountSettings(
        accountEmail: String,
        senderName: String,
        signature: String,
        defaultSubjectTemplate: String
    ): Int

    @Query("""
        UPDATE gmail_configuration
        SET isConnected = 0,
            authStatus = :authStatus,
            accessToken = NULL,
            refreshToken = NULL,
            expiresAt = 0,
            lastError = NULL
        WHERE id = 1
    """)
    suspend fun disconnectGmail(authStatus: String): Int

    @Query("""
        UPDATE gmail_configuration
        SET accessToken = :accessToken,
            refreshToken = COALESCE(:refreshToken, refreshToken),
            expiresAt = :expiresAt,
            authStatus = :authStatus,
            isConnected = :isConnected,
            lastError = :lastError
        WHERE id = 1
    """)
    suspend fun updateGmailTokens(
        accessToken: String?,
        refreshToken: String?,
        expiresAt: Long,
        authStatus: String,
        isConnected: Boolean,
        lastError: String?
    ): Int

    @Query("""
        UPDATE gmail_configuration
        SET accessToken = :accessToken,
            refreshToken = COALESCE(:refreshToken, refreshToken),
            expiresAt = :expiresAt,
            authStatus = :authStatus,
            isConnected = :isConnected,
            lastError = :lastError
        WHERE id = 1 AND accessToken IS :expectedStoredAccessToken AND refreshToken = :expectedStoredRefreshToken
    """)
    suspend fun updateGmailTokensIfUnchanged(
        expectedStoredAccessToken: String?,
        expectedStoredRefreshToken: String,
        accessToken: String,
        refreshToken: String?,
        expiresAt: Long,
        authStatus: String,
        isConnected: Boolean,
        lastError: String?
    ): Int

    // Offer Templates
    @Query("SELECT * FROM offer_templates WHERE id = 'DEFAULT' LIMIT 1")
    fun getOfferTemplateFlow(): Flow<OfferTemplateEntity?>

    @Query("SELECT * FROM offer_templates WHERE id = 'DEFAULT' LIMIT 1")
    suspend fun getOfferTemplate(): OfferTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOfferTemplate(template: OfferTemplateEntity)
}
