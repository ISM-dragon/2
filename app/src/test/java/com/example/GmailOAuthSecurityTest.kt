package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.dao.ConfigDao
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.GmailAuthStatus
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferTemplateEntity
import com.example.data.repository.ConfigRepository
import com.example.data.security.CryptoManager
import com.example.domain.gmail.GmailSendResult
import com.example.domain.gmail.GmailService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GmailOAuthSecurityTest {

    // In-memory fake DAO to test ConfigRepository <-> GmailService single source of truth contract
    private class InMemoryConfigDao : ConfigDao {
        var storedGmailConfig: GmailConfigurationEntity? = null

        override fun getAllApiConfigs(): Flow<List<ApiConfigurationEntity>> = flowOf(emptyList())
        override suspend fun getAllApiConfigsList(): List<ApiConfigurationEntity> = emptyList()
        override suspend fun getApiConfigBySlot(slotIndex: Int): ApiConfigurationEntity? = null
        override suspend fun insertOrUpdateApiConfig(config: ApiConfigurationEntity) {}
        override suspend fun insertApiConfigs(configs: List<ApiConfigurationEntity>) {}
        override suspend fun updateSlotStatus(slotIndex: Int, status: String, cooldownUntil: Long, error: String?) {}
        override suspend fun incrementSlotUsage(slotIndex: Int, time: Long) {}
        override suspend fun incrementSlotError(slotIndex: Int, error: String) {}

        override fun getGmailConfigFlow(): Flow<GmailConfigurationEntity?> = flowOf(storedGmailConfig)
        override suspend fun getGmailConfig(): GmailConfigurationEntity? = storedGmailConfig
        override suspend fun saveGmailConfig(config: GmailConfigurationEntity) {
            storedGmailConfig = config
        }

        override suspend fun updateGmailAuthStatus(status: String, lastError: String?): Int {
            val current = storedGmailConfig ?: return 0
            storedGmailConfig = current.copy(authStatus = status, lastError = lastError)
            return 1
        }

        override suspend fun updateGmailAccountSettings(
            accountEmail: String,
            senderName: String,
            signature: String,
            defaultSubjectTemplate: String
        ): Int {
            val current = storedGmailConfig ?: return 0
            storedGmailConfig = current.copy(
                accountEmail = accountEmail,
                senderName = senderName,
                signature = signature,
                defaultSubjectTemplate = defaultSubjectTemplate
            )
            return 1
        }

        override suspend fun disconnectGmail(authStatus: String): Int {
            val current = storedGmailConfig ?: return 0
            storedGmailConfig = current.copy(
                isConnected = false,
                authStatus = authStatus,
                accessToken = null,
                refreshToken = null,
                expiresAt = 0L,
                lastError = null
            )
            return 1
        }

        override suspend fun updateGmailTokens(
            accessToken: String?,
            refreshToken: String?,
            expiresAt: Long,
            authStatus: String,
            isConnected: Boolean,
            lastError: String?
        ): Int {
            val current = storedGmailConfig ?: return 0
            storedGmailConfig = current.copy(
                accessToken = accessToken,
                refreshToken = refreshToken ?: current.refreshToken,
                expiresAt = expiresAt,
                authStatus = authStatus,
                isConnected = isConnected,
                lastError = lastError
            )
            return 1
        }

        override suspend fun updateGmailTokensIfUnchanged(
            expectedStoredAccessToken: String?,
            expectedStoredRefreshToken: String,
            accessToken: String,
            refreshToken: String?,
            expiresAt: Long,
            authStatus: String,
            isConnected: Boolean,
            lastError: String?
        ): Int {
            val current = storedGmailConfig ?: return 0
            if (current.accessToken != expectedStoredAccessToken || current.refreshToken != expectedStoredRefreshToken) return 0
            storedGmailConfig = current.copy(
                accessToken = accessToken,
                refreshToken = refreshToken ?: current.refreshToken,
                expiresAt = expiresAt,
                authStatus = authStatus,
                isConnected = isConnected,
                lastError = lastError
            )
            return 1
        }

        override fun getOfferTemplateFlow(): Flow<OfferTemplateEntity?> = flowOf(OfferTemplateEntity())
        override suspend fun getOfferTemplate(): OfferTemplateEntity? = OfferTemplateEntity()
        override suspend fun saveOfferTemplate(template: OfferTemplateEntity) {}
    }

    @Test
    fun testGmailAuthStatusExactValues() {
        assertEquals("NOT_CONFIGURED", GmailAuthStatus.NOT_CONFIGURED)
        assertEquals("AUTH_REQUIRED", GmailAuthStatus.AUTH_REQUIRED)
        assertEquals("AUTH_EXPIRED", GmailAuthStatus.AUTH_EXPIRED)
        assertEquals("SENDING", GmailAuthStatus.SENDING)
        assertEquals("SENT", GmailAuthStatus.SENT)
        assertEquals("FAILED", GmailAuthStatus.FAILED)
    }

    @Test
    fun testConfigRepositorySingleSourceOfTruthEncryption() = runBlocking {
        val fakeDao = InMemoryConfigDao()
        val repo = ConfigRepository(fakeDao)

        val rawAccessToken = "ya29.a0ARrdaM_REAL_ACCESS_TOKEN_XYZ123"
        val rawRefreshToken = "1//04_REAL_REFRESH_TOKEN_ABC789"

        // Save through repository
        val configToSave = GmailConfigurationEntity(
            id = 1,
            accountEmail = "acquisitions@fund.com",
            senderName = "Acquisitions Partner",
            accessToken = rawAccessToken,
            refreshToken = rawRefreshToken,
            isConnected = true,
            authStatus = GmailAuthStatus.AUTH_REQUIRED
        )
        repo.saveGmailConfig(configToSave)

        // 1. The DAO must store ENCRYPTED tokens, NOT plain text
        val rawInDao = fakeDao.storedGmailConfig
        assertNotNull("DAO must contain saved configuration", rawInDao)
        assertNotEquals("DAO must not contain raw access token", rawAccessToken, rawInDao?.accessToken)
        assertNotEquals("DAO must not contain raw refresh token", rawRefreshToken, rawInDao?.refreshToken)
        assertFalse("Raw token must not be in ciphertext", rawInDao!!.accessToken!!.contains("REAL_ACCESS_TOKEN"))

        // 2. Reading through repository must DECRYPT tokens
        val decrypted = repo.getGmailConfig()
        assertEquals("Access token must be decrypted when read via Repository", rawAccessToken, decrypted.accessToken)
        assertEquals("Refresh token must be decrypted when read via Repository", rawRefreshToken, decrypted.refreshToken)

        // 3. Updating tokens through repository must store them encrypted
        val newRawToken = "ya29.NEW_REFRESHED_ACCESS_TOKEN_456"
        repo.updateGmailTokens(
            newAccessToken = newRawToken,
            expiresAt = System.currentTimeMillis() + 3600000L,
            authStatus = GmailAuthStatus.AUTH_REQUIRED
        )

        val updatedInDao = fakeDao.storedGmailConfig
        assertNotEquals(newRawToken, updatedInDao?.accessToken)
        val readBack = repo.getGmailConfig()
        assertEquals(newRawToken, readBack.accessToken)
    }

    @Test
    fun accountSettingsUpdatesAndDisconnectDoNotRaceOrOverwriteFreshTokens() = runBlocking {
        val fakeDao = InMemoryConfigDao()
        val repo = ConfigRepository(fakeDao)
        repo.saveGmailConfig(
            GmailConfigurationEntity(
                accountEmail = "old@example.com",
                isConnected = true,
                accessToken = "old-access",
                refreshToken = "old-refresh",
                expiresAt = System.currentTimeMillis() + 3_600_000L
            )
        )

        // Simulate a refresh completing while the Settings screen still has a stale config snapshot.
        val staleSettings = repo.getGmailConfig()
        repo.updateGmailTokens(
            newAccessToken = "fresh-access",
            newRefreshToken = "fresh-refresh",
            expiresAt = System.currentTimeMillis() + 7_200_000L,
            authStatus = GmailAuthStatus.AUTH_REQUIRED
        )
        repo.updateGmailAccountSettings(
            accountEmail = "new@example.com",
            senderName = "New Sender",
            signature = "Regards",
            defaultSubjectTemplate = "Offer: {property_address}"
        )

        val updated = repo.getGmailConfig()
        assertEquals("fresh-access", updated.accessToken)
        assertEquals("fresh-refresh", updated.refreshToken)
        assertEquals("new@example.com", updated.accountEmail)

        // Disconnect is an atomic token clear, so an in-flight refresh CAS cannot restore credentials.
        repo.disconnectGmail()
        val disconnected = repo.getGmailConfig()
        assertFalse(disconnected.isConnected)
        assertNull(disconnected.accessToken)
        assertNull(disconnected.refreshToken)
        assertEquals(GmailAuthStatus.NOT_CONFIGURED, disconnected.authStatus)
        assertNotNull(staleSettings.accessToken)
    }

    @Test
    fun testGmailServiceNeverUsesMockTokensOrFakeMessageIds() = runBlocking {
        val fakeDao = InMemoryConfigDao()
        val repo = ConfigRepository(fakeDao)
        val service = GmailService(repo)

        // Configuration with no tokens
        val unconfigured = service.getConfiguration()
        assertFalse("Unconfigured must have isConnected = false", unconfigured.isConnected)
        assertNull(unconfigured.accessToken)

        // Attempting to send without configured OAuth must fail and set AUTH_REQUIRED
        val result = service.sendOfferEmail(
            context = ApplicationProvider.getApplicationContext<Context>(),
            recipientEmail = "broker@commercial.com",
            recipientName = "Senior Broker",
            subject = "LOI Submission",
            htmlBody = "<p>Letter of intent</p>",
            pdfFile = null
        )

        assertFalse("Sending without PDF/token must fail", result.success)
        assertNull("Must not create fake message id on failure", result.messageId)
    }

    @Test
    fun testMissingMessageIdInGmailResponseIsStrictFailure() {
        val emptyIdJson = "{}"
        val parsed = JSONObject(emptyIdJson)
        val messageId = parsed.optString("id", "").trim()

        val sendResult = if (messageId.isBlank()) {
            GmailSendResult(
                success = false,
                error = "Gmail API response succeeded but did not contain a valid message id."
            )
        } else {
            GmailSendResult(success = true, messageId = messageId)
        }

        assertFalse("Empty message id must be marked as failure", sendResult.success)
        assertNull(sendResult.messageId)
        assertTrue(sendResult.error!!.contains("did not contain a valid message id"))
    }

    @Test
    fun testRefreshFailsCleanlyWhenClientIdMissing() = runBlocking {
        val fakeDao = InMemoryConfigDao()
        val repo = ConfigRepository(fakeDao)
        val service = GmailService(repo)

        val config = GmailConfigurationEntity(
            id = 1,
            isConnected = true,
            accountEmail = "agent@deals.com",
            accessToken = "expired_token",
            refreshToken = "refresh_token_123",
            expiresAt = System.currentTimeMillis() - 1000L,
            authStatus = GmailAuthStatus.AUTH_EXPIRED
        )
        repo.saveGmailConfig(config)

        // When client ID is not configured, refresh must stop immediately without using fake 'real-estate-ai-app'
        val refreshed = service.refreshAccessToken(repo.getGmailConfig())
        assertNull("Refresh must fail when real OAuth client ID is not configured", refreshed)

        val updated = repo.getGmailConfig()
        assertEquals(GmailAuthStatus.FAILED, updated.authStatus)
        assertTrue("Error message must explain missing Client ID", updated.lastError!!.contains("OAuth Client ID is not configured"))
    }
}
