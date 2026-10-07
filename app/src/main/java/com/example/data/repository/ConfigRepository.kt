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
        // Update only status columns so a concurrent token refresh cannot be overwritten by a stale read.
        if (configDao.updateGmailAuthStatus(status, lastError) == 0) {
            val current = getGmailConfig()
            saveGmailConfig(current.copy(authStatus = status, lastError = lastError))
        }
    }

    suspend fun updateGmailAccountSettings(
        accountEmail: String,
        senderName: String,
        signature: String,
        defaultSubjectTemplate: String
    ) = withContext(Dispatchers.IO) {
        if (configDao.updateGmailAccountSettings(
                accountEmail = accountEmail,
                senderName = senderName,
                signature = signature,
                defaultSubjectTemplate = defaultSubjectTemplate
            ) == 0
        ) {
            saveGmailConfig(
                GmailConfigurationEntity(
                    accountEmail = accountEmail,
                    senderName = senderName,
                    signature = signature,
                    defaultSubjectTemplate = defaultSubjectTemplate,
                    authStatus = if (accountEmail.isBlank()) {
                        com.example.data.local.entity.GmailAuthStatus.NOT_CONFIGURED
                    } else {
                        com.example.data.local.entity.GmailAuthStatus.AUTH_REQUIRED
                    }
                )
            )
        }
    }

    suspend fun disconnectGmail() = withContext(Dispatchers.IO) {
        if (configDao.disconnectGmail(com.example.data.local.entity.GmailAuthStatus.NOT_CONFIGURED) == 0) {
            val current = getGmailConfig()
            saveGmailConfig(
                current.copy(
                    isConnected = false,
                    authStatus = com.example.data.local.entity.GmailAuthStatus.NOT_CONFIGURED,
                    accessToken = null,
                    refreshToken = null,
                    expiresAt = 0L,
                    lastError = null
                )
            )
        }
    }

    suspend fun updateGmailTokens(
        newAccessToken: String?,
        newRefreshToken: String? = null,
        expiresAt: Long,
        authStatus: String,
        lastError: String? = null
    ) = withContext(Dispatchers.IO) {
        val isAuth = !newAccessToken.isNullOrBlank() &&
            authStatus != com.example.data.local.entity.GmailAuthStatus.FAILED &&
            authStatus != com.example.data.local.entity.GmailAuthStatus.AUTH_EXPIRED &&
            authStatus != com.example.data.local.entity.GmailAuthStatus.NOT_CONFIGURED
        val encryptedAccess = newAccessToken?.let { CryptoManager.encrypt(it) }
        val encryptedRefresh = newRefreshToken?.let { CryptoManager.encrypt(it) }

        // COALESCE in the DAO preserves the old refresh token when Google omits a rotated token.
        if (configDao.updateGmailTokens(
                accessToken = encryptedAccess,
                refreshToken = encryptedRefresh,
                expiresAt = expiresAt,
                authStatus = authStatus,
                isConnected = isAuth,
                lastError = lastError
            ) == 0
        ) {
            val current = getGmailConfig()
            saveGmailConfig(
                current.copy(
                    accessToken = newAccessToken,
                    refreshToken = newRefreshToken ?: current.refreshToken,
                    expiresAt = expiresAt,
                    authStatus = authStatus,
                    isConnected = isAuth,
                    lastError = lastError
                )
            )
        }
    }

    /** CAS token persistence prevents a stale refresh from resurrecting a disconnected/re-authorized account. */
    suspend fun updateGmailTokensIfUnchanged(
        expectedAccessToken: String?,
        expectedRefreshToken: String,
        newAccessToken: String,
        newRefreshToken: String?,
        expiresAt: Long,
        authStatus: String,
        lastError: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val stored = configDao.getGmailConfig() ?: return@withContext false
        val storedAccessToken = stored.accessToken?.let { CryptoManager.decrypt(it) }
        val storedRefreshToken = stored.refreshToken?.let { CryptoManager.decrypt(it) }
        if (storedAccessToken != expectedAccessToken || storedRefreshToken != expectedRefreshToken) {
            return@withContext false
        }
        val expectedStoredRefreshToken = stored.refreshToken ?: return@withContext false
        val isAuth = authStatus != com.example.data.local.entity.GmailAuthStatus.FAILED &&
            authStatus != com.example.data.local.entity.GmailAuthStatus.AUTH_EXPIRED &&
            authStatus != com.example.data.local.entity.GmailAuthStatus.NOT_CONFIGURED
        configDao.updateGmailTokensIfUnchanged(
            expectedStoredAccessToken = stored.accessToken,
            expectedStoredRefreshToken = expectedStoredRefreshToken,
            accessToken = CryptoManager.encrypt(newAccessToken),
            refreshToken = newRefreshToken?.let { CryptoManager.encrypt(it) },
            expiresAt = expiresAt,
            authStatus = authStatus,
            isConnected = isAuth,
            lastError = lastError
        ) > 0
    }

    fun getOAuthClientId(): String? {
        return try {
            val field = BuildConfig::class.java.getField("GOOGLE_OAUTH_CLIENT_ID")
            val value = field.get(null) as? String
            if (!value.isNullOrBlank()) value.trim() else null
        } catch (e: Throwable) {
            try {
                val field = BuildConfig::class.java.getField("OAUTH_CLIENT_ID")
                val value = field.get(null) as? String
                if (!value.isNullOrBlank()) value.trim() else null
            } catch (e2: Throwable) {
                val envVal = System.getenv("GOOGLE_OAUTH_CLIENT_ID")
                if (!envVal.isNullOrBlank()) envVal.trim() else null
            }
        }
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
