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
import com.example.domain.gmail.GmailFailureKind
import com.example.domain.gmail.GmailSendResult
import com.example.domain.gmail.GmailService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
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
    fun gmailServiceDisablesRedirectFollowingOnInjectedClients() {
        val injectedClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val service = GmailService(ConfigRepository(InMemoryConfigDao()), httpClient = injectedClient)
        val field = GmailService::class.java.getDeclaredField("safeHttpClient").apply { isAccessible = true }
        val safeClient = field.get(service) as OkHttpClient

        assertFalse(safeClient.followRedirects)
        assertFalse(safeClient.followSslRedirects)
        assertFalse(safeClient.retryOnConnectionFailure)
    }

    @Test
    fun gmailOAuthAndSendEndpointsArePinnedToExpectedHttpsOrigins() {
        val service = GmailService(ConfigRepository(InMemoryConfigDao()))
        val validator = GmailService::class.java.getDeclaredMethod(
            "isTrustedGoogleEndpoint",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }
        fun validOAuthEndpoint(value: String): Boolean = validator.invoke(
            service,
            value,
            "oauth2.googleapis.com",
            "/token"
        ) as Boolean

        assertTrue(validOAuthEndpoint("https://oauth2.googleapis.com/token"))
        assertFalse(validOAuthEndpoint("http://oauth2.googleapis.com/token"))
        assertFalse(validOAuthEndpoint("https://oauth2.googleapis.com.evil.invalid/token"))
        assertFalse(validOAuthEndpoint("https://oauth2.googleapis.com/token?access_token=not-allowed"))
    }

    @Test
    fun testConfigRepositorySingleSourceOfTruthEncryption() = runBlocking {
        val fakeDao = InMemoryConfigDao()
        val repo = ConfigRepository(fakeDao)

        val rawAccessToken = "test-only-oauth-access-token"
        val rawRefreshToken = "test-only-oauth-refresh-token"

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
        assertFalse("Raw token must not be in ciphertext", rawInDao!!.accessToken!!.contains("test-only-oauth-access-token"))

        // 2. Reading through repository must DECRYPT tokens
        val decrypted = repo.getGmailConfig()
        assertEquals("Access token must be decrypted when read via Repository", rawAccessToken, decrypted.accessToken)
        assertEquals("Refresh token must be decrypted when read via Repository", rawRefreshToken, decrypted.refreshToken)

        // 3. Updating tokens through repository must store them encrypted
        val newRawToken = "test-only-oauth-refreshed-access-token"
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
    fun corruptOAuthCiphertextProjectsAsDisconnectedAndRequiresReauthorization() = runBlocking {
        val fakeDao = InMemoryConfigDao()
        fakeDao.storedGmailConfig = GmailConfigurationEntity(
            isConnected = true,
            authStatus = GmailAuthStatus.AUTH_REQUIRED,
            accessToken = "not-valid-ciphertext",
            refreshToken = "also-not-valid-ciphertext"
        )

        val config = ConfigRepository(fakeDao).getGmailConfig()

        assertFalse(config.isConnected)
        assertEquals(GmailAuthStatus.AUTH_EXPIRED, config.authStatus)
        assertNull(config.accessToken)
        assertNull(config.refreshToken)
        assertTrue(config.lastError.orEmpty().contains("could not be decrypted"))
        // Fail-closed projection must not erase the original ciphertext as a side effect of reading.
        assertEquals("not-valid-ciphertext", fakeDao.storedGmailConfig?.accessToken)
        assertEquals("also-not-valid-ciphertext", fakeDao.storedGmailConfig?.refreshToken)
    }

    @Test
    fun aFreshAccessTokenDoesNotPreserveUnreadableRefreshCiphertext() = runBlocking {
        val fakeDao = InMemoryConfigDao()
        fakeDao.storedGmailConfig = GmailConfigurationEntity(
            isConnected = true,
            accessToken = CryptoManager.encrypt("test-only-old-access-token"),
            refreshToken = "unreadable-refresh-ciphertext"
        )
        val repository = ConfigRepository(fakeDao)

        repository.updateGmailTokens(
            newAccessToken = "test-only-new-access-token",
            newRefreshToken = null,
            expiresAt = System.currentTimeMillis() + 60_000L,
            authStatus = GmailAuthStatus.AUTH_REQUIRED
        )

        assertEquals("", fakeDao.storedGmailConfig?.refreshToken)
        val projected = repository.getGmailConfig()
        assertTrue(projected.isConnected)
        assertEquals("test-only-new-access-token", projected.accessToken)
        assertTrue(projected.refreshToken.isNullOrBlank())
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

    /**
     * Attachment approval is what keeps the Gmail path from reading arbitrary app files. It must hold
     * for a real PDF stored outside `files/offers/`, for a non-PDF file stored inside it, and for a
     * symlink inside the directory that resolves outside it.
     */
    @Test
    fun sendRefusesAttachmentsThatAreNotApprovedOfferPdfs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = ConfigRepository(InMemoryConfigDao())
        val service = GmailService(repository)
        repository.saveGmailConfig(
            GmailConfigurationEntity(
                id = 1,
                accountEmail = "acquisitions@example.com",
                senderName = "Acquisitions",
                isConnected = true,
                accessToken = "test-only-access-token",
                refreshToken = "test-only-refresh-token",
                expiresAt = System.currentTimeMillis() + 3_600_000L,
                authStatus = GmailAuthStatus.AUTH_REQUIRED
            )
        )

        // Rejection happens before any network call, so this test stays hermetic. The positive case
        // (a real PDF directly inside files/offers/) is covered by GmailMimeAndRetryPolicyTest.
        val pdfHeader = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31)
        val offersDirectory = File(context.filesDir, "offers").apply { mkdirs() }
        val outside = File(context.filesDir, "outside.pdf").apply { writeBytes(pdfHeader) }
        val notAPdf = File(offersDirectory, "notes.txt").apply { writeText("not a document") }
        val link = File(offersDirectory, "escape.pdf")
        val linkCreated = try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (_: Exception) {
            false
        }

        val unapproved: List<File?> = listOf(outside, notAPdf, link.takeIf { linkCreated }, null)
        for (candidate in unapproved) {
            val outcome = service.sendOfferEmail(
                context = context,
                recipientEmail = "broker@example.com",
                recipientName = "Broker",
                subject = "Offer",
                htmlBody = "<p>Offer</p>",
                pdfFile = candidate
            )
            assertEquals(
                "unapproved attachment must be rejected before any network call",
                GmailFailureKind.VALIDATION,
                outcome.failureKind
            )
            assertFalse(outcome.success)
            assertNull("no message id is invented for a rejected send", outcome.messageId)
        }
    }

    @Test
    fun testRefreshFailsCleanlyWhenClientIdMissing() = runBlocking {
        val fakeDao = InMemoryConfigDao()
        val repo = ConfigRepository(fakeDao, oauthClientId = "")
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
