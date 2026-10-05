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
    private val configDao: ConfigDao
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
            val defaultKey = try {
                // Check if BuildConfig has GEMINI_API_KEY injected
                val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
                val keyVal = field.get(null) as? String
                if (keyVal.isNullOrBlank() || keyVal == "MY_GEMINI_API_KEY") "" else keyVal
            } catch (e: Exception) {
                ""
            }

            val defaultSlots = listOf(
                ApiConfigurationEntity(
                    slotIndex = 1,
                    label = "Slot 1: Primary Flash",
                    apiKey = CryptoManager.encrypt(defaultKey),
                    model = "gemini-2.5-flash",
                    status = if (defaultKey.isNotBlank()) "READY" else "DISABLED"
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

        if (configDao.getGmailConfig() == null) {
            configDao.saveGmailConfig(
                GmailConfigurationEntity(
                    id = 1,
                    isConnected = true,
                    accountEmail = "dragonlorde3@gmail.com",
                    senderName = "Acquisitions Director",
                    signature = "Best regards,\nReal Estate Acquisitions Dept\nReal Estate AI Capital",
                    defaultSubjectTemplate = "Purchase Offer & Letter of Intent - {property_address}"
                )
            )
        }

        if (configDao.getOfferTemplate() == null) {
            configDao.saveOfferTemplate(OfferTemplateEntity())
        }
    }
}
