package com.example.data.repository

import com.example.BuildConfig
import com.example.data.local.dao.ConfigDao
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferTemplateEntity
import com.example.data.security.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ConfigRepository(
    private val configDao: ConfigDao,
    private val oauthClientId: String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
) {
    // Flow exposes decrypted configurations for internal and UI consumption
    val apiConfigs: Flow<List<ApiConfigurationEntity>> = configDao.getAllApiConfigs().map { list ->
        list.map { cfg ->
            cfg.copy(apiKey = CryptoManager.decrypt(cfg.apiKey))
        }
    }

    val gmailConfig: Flow<GmailConfigurationEntity?> = configDao.getGmailConfigFlow().map { cfg ->
        cfg?.copy(
            accessToken = cfg.accessToken?.let { CryptoManager.decrypt(it) },
            refreshToken = cfg.refreshToken?.let { CryptoManager.decrypt(it) }
        )
    }

    val offerTemplate: Flow<OfferTemplateEntity?> = configDao.getOfferTemplateFlow()

    suspend fun getOfferTemplate(): OfferTemplateEntity? = withContext(Dispatchers.IO) {
        configDao.getOfferTemplate()
    }

    suspend fun getGmailConfig(): GmailConfigurationEntity = withContext(Dispatchers.IO) {
        val cfg = configDao.getGmailConfig() ?: GmailConfigurationEntity()
        cfg.copy(
            accessToken = cfg.accessToken?.let { CryptoManager.decrypt(it) },
            refreshToken = cfg.refreshToken?.let { CryptoManager.decrypt(it) }
        )
    }

    suspend fun saveGmailConfig(config: GmailConfigurationEntity) = withContext(Dispatchers.IO) {
        val encryptedAccess = config.accessToken?.let { CryptoManager.encrypt(it) }
        val encryptedRefresh = config.refreshToken?.let { CryptoManager.encrypt(it) }
        configDao.saveGmailConfig(
            config.copy(
                accessToken = encryptedAccess,
                refreshToken = encryptedRefresh
            )
        )
    }

    suspend fun updateGmailAuthStatus(status: String, lastError: String? = null) = withContext(Dispatchers.IO) {
        val current = getGmailConfig()
        saveGmailConfig(
            current.copy(
                authStatus = status,
                lastError = lastError
            )
        )
    }

    suspend fun updateGmailTokens(
        newAccessToken: String?,
        newRefreshToken: String? = null,
        expiresAt: Long,
        authStatus: String,
        lastError: String? = null
    ) = withContext(Dispatchers.IO) {
        val current = getGmailConfig()
        val isAuth = !newAccessToken.isNullOrBlank() &&
            authStatus != com.example.data.local.entity.GmailAuthStatus.FAILED &&
            authStatus != com.example.data.local.entity.GmailAuthStatus.AUTH_EXPIRED &&
            authStatus != com.example.data.local.entity.GmailAuthStatus.NOT_CONFIGURED

        val updated = current.copy(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken ?: current.refreshToken,
            expiresAt = expiresAt,
            authStatus = authStatus,
            isConnected = isAuth,
            lastError = lastError
        )
        saveGmailConfig(updated)
    }

    fun getOAuthClientId(): String? = oauthClientId
        .trim()
        .takeIf { it.isNotBlank() }

    suspend fun saveApiConfig(config: ApiConfigurationEntity) = withContext(Dispatchers.IO) {
        val encryptedKey = CryptoManager.encrypt(config.apiKey.trim())
        configDao.insertOrUpdateApiConfig(config.copy(apiKey = encryptedKey))
    }

    suspend fun saveOfferTemplate(template: OfferTemplateEntity) = withContext(Dispatchers.IO) {
        configDao.saveOfferTemplate(template)
    }

    suspend fun seedDefaultsIfEmpty() = withContext(Dispatchers.IO) {
        val existingConfigs = configDao.getAllApiConfigsList()
        if (existingConfigs.isEmpty()) {
            // API keys are entered by the user at runtime and must never be sourced from BuildConfig.
            val defaultSlots = listOf(
                ApiConfigurationEntity(
                    slotIndex = 1,
                    label = "Slot 1: Primary Flash",
                    apiKey = "",
                    model = "gemini-2.5-flash",
                    status = "DISABLED"
                ),
                ApiConfigurationEntity(
                    slotIndex = 2,
                    label = "Slot 2: Secondary Flash",
                    apiKey = "",
                    model = "gemini-2.5-flash",
                    status = "DISABLED"
                ),
                ApiConfigurationEntity(
                    slotIndex = 3,
                    label = "Slot 3: High-Reasoning Pro",
                    apiKey = "",
                    model = "gemini-3.1-pro-preview",
                    status = "DISABLED"
                ),
                ApiConfigurationEntity(
                    slotIndex = 4,
                    label = "Slot 4: Backup 01",
                    apiKey = "",
                    model = "gemini-2.5-flash",
                    status = "DISABLED"
                ),
                ApiConfigurationEntity(
                    slotIndex = 5,
                    label = "Slot 5: Backup 02",
                    apiKey = "",
                    model = "gemini-2.5-flash",
                    status = "DISABLED"
                ),
                ApiConfigurationEntity(
                    slotIndex = 6,
                    label = "Slot 6: Emergency Failover",
                    apiKey = "",
                    model = "gemini-2.5-flash",
                    status = "DISABLED"
                )
            )
            configDao.insertApiConfigs(defaultSlots)
        }

        val existingGmail = configDao.getGmailConfig()
        if (existingGmail == null) {
            configDao.saveGmailConfig(
                GmailConfigurationEntity(
                    id = 1,
                    isConnected = false,
                    authStatus = "NOT_CONFIGURED",
                    accountEmail = "",
                    senderName = "Acquisitions Director",
                    signature = "Best regards,\nReal Estate Acquisitions Dept\nReal Estate AI Capital",
                    defaultSubjectTemplate = "Purchase Offer & Letter of Intent - {property_address}"
                )
            )
        } else if (existingGmail.accessToken.isNullOrBlank() && existingGmail.isConnected) {
            // Ensure no mock connected state without a real OAuth token
            configDao.saveGmailConfig(
                existingGmail.copy(
                    isConnected = false,
                    authStatus = "NOT_CONFIGURED"
                )
            )
        }

        if (configDao.getOfferTemplate() == null) {
            configDao.saveOfferTemplate(OfferTemplateEntity())
        }
    }
}
