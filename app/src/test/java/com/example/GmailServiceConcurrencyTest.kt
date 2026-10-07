package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.dao.ConfigDao
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferTemplateEntity
import com.example.data.repository.ConfigRepository
import com.example.domain.gmail.GmailFailureKind
import com.example.domain.gmail.GmailService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GmailServiceConcurrencyTest {

    @Test
    fun concurrentRefreshesShareOneOAuthRequestAndKeepRotatedToken() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(200, TimeUnit.MILLISECONDS)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"access_token":"new-access","expires_in":30}""")
            )
            val dao = InMemoryConfigDao()
            val repository = ConfigRepository(dao)
            repository.saveGmailConfig(
                GmailConfigurationEntity(
                    accountEmail = "sender@example.com",
                    isConnected = true,
                    accessToken = "old-access",
                    refreshToken = "refresh-secret",
                    expiresAt = System.currentTimeMillis() - 1_000L
                )
            )
            val expired = repository.getGmailConfig()
            val service = GmailService(
                configRepository = repository,
                httpClient = OkHttpClient(),
                oauthTokenUrl = server.url("/token").toString(),
                oauthClientIdProvider = { "test-client-id" }
            )

            val results = coroutineScope {
                (1..8).map {
                    async(Dispatchers.IO) { service.refreshAccessToken(expired) }
                }.awaitAll()
            }

            assertEquals("Only one network refresh should be performed", 1, server.requestCount)
            assertTrue(results.all { it?.accessToken == "new-access" })
            val saved = repository.getGmailConfig()
            assertEquals("new-access", saved.accessToken)
            assertEquals("Refresh token must be retained when rotation is omitted", "refresh-secret", saved.refreshToken)
            assertTrue(saved.expiresAt > System.currentTimeMillis())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun concurrentFailedRefreshesShareOneOAuthFailureInsteadOfStampeding() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(150, TimeUnit.MILLISECONDS)
                    .setResponseCode(400)
                    .setBody("""{"error":"invalid_grant"}""")
            )
            val repository = ConfigRepository(InMemoryConfigDao())
            repository.saveGmailConfig(
                GmailConfigurationEntity(
                    isConnected = true,
                    accessToken = "old-access",
                    refreshToken = "refresh-secret",
                    expiresAt = System.currentTimeMillis() - 1_000L
                )
            )
            val expired = repository.getGmailConfig()
            val service = GmailService(
                configRepository = repository,
                httpClient = OkHttpClient(),
                oauthTokenUrl = server.url("/token").toString(),
                oauthClientIdProvider = { "test-client-id" }
            )

            val results = coroutineScope {
                (1..8).map { async(Dispatchers.IO) { service.refreshAccessToken(expired) } }.awaitAll()
            }

            assertTrue(results.all { it == null })
            assertEquals("Concurrent callers must share one failed refresh", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aRefreshCannotResurrectAnAccountDisconnectedWhileTheRequestWasInFlight() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(400, TimeUnit.MILLISECONDS)
                    .setBody("""{"access_token":"stale-refresh-result","expires_in":3600}""")
            )
            val repository = ConfigRepository(InMemoryConfigDao())
            repository.saveGmailConfig(
                GmailConfigurationEntity(
                    isConnected = true,
                    accessToken = "old-access",
                    refreshToken = "refresh-secret",
                    expiresAt = System.currentTimeMillis() - 1L
                )
            )
            val expired = repository.getGmailConfig()
            val service = GmailService(
                configRepository = repository,
                httpClient = OkHttpClient(),
                oauthTokenUrl = server.url("/token").toString(),
                oauthClientIdProvider = { "test-client-id" }
            )

            val refresh = async(Dispatchers.IO) { service.refreshAccessToken(expired) }
            delay(80)
            repository.saveGmailConfig(
                expired.copy(
                    isConnected = false,
                    accessToken = null,
                    refreshToken = null,
                    expiresAt = 0L
                )
            )

            assertNull(refresh.await())
            val current = repository.getGmailConfig()
            assertFalse(current.isConnected)
            assertNull(current.accessToken)
            assertNull(current.refreshToken)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun gmailSendCarriesStableKeyAndMIMEAndRejectsMissingMessageIdAsUnknown() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        server.start()
        val pdf = File(context.filesDir, "offers/offer-test.pdf").apply {
            parentFile?.mkdirs()
            writeBytes("%PDF-1.7\nTest".toByteArray())
        }
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"gmail-message-42"}""")
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}")
            )
            val repository = ConfigRepository(InMemoryConfigDao()).also {
                it.saveGmailConfig(
                    GmailConfigurationEntity(
                        accountEmail = "sender@example.com",
                        senderName = "Real Estate AI",
                        isConnected = true,
                        accessToken = "access-secret",
                        refreshToken = "refresh-secret",
                        expiresAt = System.currentTimeMillis() + 3_600_000L
                    )
                )
            }
            val service = GmailService(
                configRepository = repository,
                httpClient = OkHttpClient(),
                gmailSendUrl = server.url("/gmail/send").toString()
            )

            val sent = service.sendOfferEmail(
                context = context,
                recipientEmail = "agent@example.com",
                recipientName = "Zoë",
                subject = "Offer — 東京",
                htmlBody = "<p>Offer — €</p>",
                pdfFile = pdf,
                idempotencyKey = "offer-send-v1:OFFER-001"
            )
            assertTrue(sent.success)
            assertEquals("gmail-message-42", sent.messageId)

            val request = server.takeRequest()
            assertEquals("Bearer access-secret", request.getHeader("Authorization"))
            assertNotNull(request.getHeader("Idempotency-Key"))
            val raw = JSONObject(request.body.readUtf8()).getString("raw")
            assertFalse(raw.contains("=")) // Gmail raw uses unpadded base64url.
            val decoded = android.util.Base64.decode(
                raw,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            val mime = String(decoded, Charsets.US_ASCII)
            assertTrue(mime.contains("Subject: =?UTF-8?B?"))
            assertTrue(mime.contains("Content-Transfer-Encoding: base64"))
            assertFalse(mime.substringBefore("\r\n\r\n").contains("東京"))

            val missingMessageId = service.sendOfferEmail(
                context = context,
                recipientEmail = "agent@example.com",
                recipientName = "Agent",
                subject = "Offer",
                htmlBody = "<p>Offer</p>",
                pdfFile = pdf,
                idempotencyKey = "offer-send-v1:OFFER-002"
            )
            assertFalse(missingMessageId.success)
            assertEquals(GmailFailureKind.DELIVERY_UNKNOWN, missingMessageId.failureKind)
            assertEquals(2, server.requestCount)
        } finally {
            pdf.delete()
            server.shutdown()
        }
    }

    @Test
    fun oauthErrorBodyIsNeverCopiedIntoPersistentAuthError() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":"invalid_grant","echo":"refresh-secret"}""")
            )
            val repository = ConfigRepository(InMemoryConfigDao()).also {
                it.saveGmailConfig(
                    GmailConfigurationEntity(
                        isConnected = true,
                        accessToken = "old-access",
                        refreshToken = "refresh-secret",
                        expiresAt = System.currentTimeMillis() - 1L
                    )
                )
            }
            val service = GmailService(
                configRepository = repository,
                httpClient = OkHttpClient(),
                oauthTokenUrl = server.url("/token").toString(),
                oauthClientIdProvider = { "test-client-id" }
            )

            assertNull(service.refreshAccessToken(repository.getGmailConfig()))
            val error = repository.getGmailConfig().lastError.orEmpty()
            assertFalse(error.contains("refresh-secret"))
            assertFalse(error.contains("invalid_grant"))
            assertTrue(error.contains("HTTP 400"))
        } finally {
            server.shutdown()
        }
    }

    private class InMemoryConfigDao : ConfigDao {
        @Volatile
        private var gmailConfig: GmailConfigurationEntity? = null

        override fun getAllApiConfigs(): Flow<List<ApiConfigurationEntity>> = flowOf(emptyList())
        override suspend fun getAllApiConfigsList(): List<ApiConfigurationEntity> = emptyList()
        override suspend fun getApiConfigBySlot(slotIndex: Int): ApiConfigurationEntity? = null
        override suspend fun insertOrUpdateApiConfig(config: ApiConfigurationEntity) = Unit
        override suspend fun insertApiConfigs(configs: List<ApiConfigurationEntity>) = Unit
        override suspend fun updateSlotStatus(slotIndex: Int, status: String, cooldownUntil: Long, error: String?) = Unit
        override suspend fun incrementSlotUsage(slotIndex: Int, time: Long) = Unit
        override suspend fun incrementSlotError(slotIndex: Int, error: String) = Unit
        override fun getGmailConfigFlow(): Flow<GmailConfigurationEntity?> = flowOf(gmailConfig)
        override suspend fun getGmailConfig(): GmailConfigurationEntity? = gmailConfig

        override suspend fun saveGmailConfig(config: GmailConfigurationEntity) {
            synchronized(this) { gmailConfig = config }
        }

        override suspend fun updateGmailAuthStatus(status: String, lastError: String?): Int = synchronized(this) {
            val current = gmailConfig ?: return@synchronized 0
            gmailConfig = current.copy(authStatus = status, lastError = lastError)
            1
        }

        override suspend fun updateGmailAccountSettings(
            accountEmail: String,
            senderName: String,
            signature: String,
            defaultSubjectTemplate: String
        ): Int = synchronized(this) {
            val current = gmailConfig ?: return@synchronized 0
            gmailConfig = current.copy(
                accountEmail = accountEmail,
                senderName = senderName,
                signature = signature,
                defaultSubjectTemplate = defaultSubjectTemplate
            )
            1
        }

        override suspend fun disconnectGmail(authStatus: String): Int = synchronized(this) {
            val current = gmailConfig ?: return@synchronized 0
            gmailConfig = current.copy(
                isConnected = false,
                authStatus = authStatus,
                accessToken = null,
                refreshToken = null,
                expiresAt = 0L,
                lastError = null
            )
            1
        }

        override suspend fun updateGmailTokens(
            accessToken: String?,
            refreshToken: String?,
            expiresAt: Long,
            authStatus: String,
            isConnected: Boolean,
            lastError: String?
        ): Int = synchronized(this) {
            val current = gmailConfig ?: return@synchronized 0
            gmailConfig = current.copy(
                accessToken = accessToken,
                refreshToken = refreshToken ?: current.refreshToken,
                expiresAt = expiresAt,
                authStatus = authStatus,
                isConnected = isConnected,
                lastError = lastError
            )
            1
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
        ): Int = synchronized(this) {
            val current = gmailConfig ?: return@synchronized 0
            if (current.accessToken != expectedStoredAccessToken || current.refreshToken != expectedStoredRefreshToken) {
                return@synchronized 0
            }
            gmailConfig = current.copy(
                accessToken = accessToken,
                refreshToken = refreshToken ?: current.refreshToken,
                expiresAt = expiresAt,
                authStatus = authStatus,
                isConnected = isConnected,
                lastError = lastError
            )
            1
        }

        override fun getOfferTemplateFlow(): Flow<OfferTemplateEntity?> = flowOf(OfferTemplateEntity())
        override suspend fun getOfferTemplate(): OfferTemplateEntity? = OfferTemplateEntity()
        override suspend fun saveOfferTemplate(template: OfferTemplateEntity) = Unit
    }
}
