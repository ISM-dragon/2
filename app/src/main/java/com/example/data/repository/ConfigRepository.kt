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
    // Flows expose decrypted values only after authenticated decryption succeeds. A corrupt API
    // key is surfaced as an ERROR slot, never as a usable empty configuration.
    val apiConfigs: Flow<List<ApiConfigurationEntity>> = configDao.getAllApiConfigs().map { list ->
        list.map(::decryptApiConfiguration)
    }

    // Corrupt or key-inaccessible Gmail credentials are projected as disconnected and unavailable.
    // The original ciphertext is left untouched for explicit recovery/diagnosis, not silently erased.
    val gmailConfig: Flow<GmailConfigurationEntity?> = configDao.getGmailConfigFlow().map { cfg ->
        cfg?.let(::decryptGmailConfiguration)
    }

    val offerTemplate: Flow<OfferTemplateEntity?> = configDao.getOfferTemplateFlow()

    suspend fun getOfferTemplate(): OfferTemplateEntity? = withContext(Dispatchers.IO) {
        configDao.getOfferTemplate()
    }

    suspend fun getGmailConfig(): GmailConfigurationEntity = withContext(Dispatchers.IO) {
        decryptGmailConfiguration(configDao.getGmailConfig() ?: GmailConfigurationEntity())
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
        val storedRefreshCiphertext = configDao.getGmailConfig()?.refreshToken
        val existingRefreshToken = storedRefreshCiphertext?.let { CryptoManager.decryptOrNull(it) }
        val encryptedRefresh = when {
            newRefreshToken != null -> CryptoManager.encrypt(newRefreshToken)
            storedRefreshCiphertext != null && existingRefreshToken == null -> ""
            else -> null // A valid existing refresh token is preserved when Google omits rotation.
        }

        // COALESCE preserves an authenticated old token when Google omits rotation. If stored
        // ciphertext is unreadable, explicitly replace it with an empty value so it cannot keep an
        // otherwise valid re-authorization in a permanently disconnected state.
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
        val storedAccessToken = stored.accessToken?.let { CryptoManager.decryptOrNull(it) }
        val storedRefreshToken = stored.refreshToken?.let { CryptoManager.decryptOrNull(it) }
        if (
            (stored.accessToken != null && storedAccessToken == null) ||
            (stored.refreshToken != null && storedRefreshToken == null) ||
            storedAccessToken != expectedAccessToken ||
            storedRefreshToken != expectedRefreshToken
        ) {
            // A refresh race must not make corrupt or key-inaccessible credentials appear current.
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

    private fun decryptApiConfiguration(stored: ApiConfigurationEntity): ApiConfigurationEntity {
        val plaintext = CryptoManager.decryptOrNull(stored.apiKey)
        return if (plaintext != null) {
            stored.copy(apiKey = plaintext)
        } else {
            stored.copy(
                apiKey = "",
                status = "ERROR",
                lastErrorMessage = API_KEY_DECRYPTION_ERROR
            )
        }
    }

    private fun decryptGmailConfiguration(stored: GmailConfigurationEntity): GmailConfigurationEntity {
        val accessToken = stored.accessToken?.let { CryptoManager.decryptOrNull(it) }
        val refreshToken = stored.refreshToken?.let { CryptoManager.decryptOrNull(it) }
        val unreadableCredential =
            (stored.accessToken != null && accessToken == null) ||
                (stored.refreshToken != null && refreshToken == null)

        return if (unreadableCredential) {
            stored.copy(
                isConnected = false,
                authStatus = com.example.data.local.entity.GmailAuthStatus.AUTH_EXPIRED,
                accessToken = null,
                refreshToken = null,
                lastError = GMAIL_CREDENTIAL_DECRYPTION_ERROR
            )
        } else {
            stored.copy(accessToken = accessToken, refreshToken = refreshToken)
        }
    }

    private companion object {
        const val API_KEY_DECRYPTION_ERROR = "Stored API key could not be decrypted. Re-enter the key."
        const val GMAIL_CREDENTIAL_DECRYPTION_ERROR =
            "Saved Gmail credentials could not be decrypted. Re-authorize Gmail."
    }
}
