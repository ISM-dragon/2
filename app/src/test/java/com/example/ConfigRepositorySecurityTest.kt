package com.example

import com.example.data.local.dao.ConfigDao
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferTemplateEntity
import com.example.data.repository.ConfigRepository
import com.example.data.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositorySecurityTest {
    @Test
    fun seededGeminiSlotsNeverReadOrPersistBuildConfigKeys() = runBlocking {
        val dao = InMemoryConfigDao()
        ConfigRepository(dao).seedDefaultsIfEmpty()

        assertEquals(6, dao.apiConfigurations.size)
        assertTrue(dao.apiConfigurations.all { it.apiKey.isEmpty() })
        assertTrue(dao.apiConfigurations.all { it.status == "DISABLED" })
        assertFalse(BuildConfig::class.java.fields.any { it.name == "GEMINI_API_KEY" })
        assertTrue(BuildConfig::class.java.fields.any { it.name == "GOOGLE_OAUTH_CLIENT_ID" })
    }

    @Test
    fun geminiApiKeysAreEncryptedBeforeDaoPersistence() = runBlocking {
        val dao = InMemoryConfigDao()
        val repository = ConfigRepository(dao)
        val apiKey = "test-only-gemini-api-key"

        repository.saveApiConfig(
            ApiConfigurationEntity(
                slotIndex = 1,
                label = "test slot",
                apiKey = apiKey,
                status = "READY"
            )
        )

        val storedKey = dao.apiConfigurations.single().apiKey
        assertNotEquals(apiKey, storedKey)
        assertFalse(storedKey.contains(apiKey))
        assertEquals(apiKey, CryptoManager.decrypt(storedKey))
        assertEquals(apiKey, repository.apiConfigs.first().single().apiKey)
    }

    @Test
    fun oauthClientIdComesOnlyFromTheExplicitPublicBuildConfigField() {
        val expected = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.trim().takeIf { it.isNotBlank() }
        assertEquals(expected, ConfigRepository(InMemoryConfigDao()).getOAuthClientId())
    }

    private class InMemoryConfigDao : ConfigDao {
        var apiConfigurations: List<ApiConfigurationEntity> = emptyList()
        private var gmailConfiguration: GmailConfigurationEntity? = null
        private var offerTemplate: OfferTemplateEntity? = null

        override fun getAllApiConfigs(): Flow<List<ApiConfigurationEntity>> = flow { emit(apiConfigurations) }
        override suspend fun getAllApiConfigsList(): List<ApiConfigurationEntity> = apiConfigurations
        override suspend fun getApiConfigBySlot(slotIndex: Int): ApiConfigurationEntity? =
            apiConfigurations.firstOrNull { it.slotIndex == slotIndex }

        override suspend fun insertOrUpdateApiConfig(config: ApiConfigurationEntity) {
            apiConfigurations = apiConfigurations.filterNot { it.slotIndex == config.slotIndex } + config
        }

        override suspend fun insertApiConfigs(configs: List<ApiConfigurationEntity>) {
            apiConfigurations = configs
        }

        override suspend fun updateSlotStatus(slotIndex: Int, status: String, cooldownUntil: Long, error: String?) = Unit
        override suspend fun incrementSlotUsage(slotIndex: Int, time: Long) = Unit
        override suspend fun incrementSlotError(slotIndex: Int, error: String) = Unit

        override fun getGmailConfigFlow(): Flow<GmailConfigurationEntity?> = flowOf(gmailConfiguration)
        override suspend fun getGmailConfig(): GmailConfigurationEntity? = gmailConfiguration
        override suspend fun saveGmailConfig(config: GmailConfigurationEntity) {
            gmailConfiguration = config
        }

        override fun getOfferTemplateFlow(): Flow<OfferTemplateEntity?> = flowOf(offerTemplate)
        override suspend fun getOfferTemplate(): OfferTemplateEntity? = offerTemplate
        override suspend fun saveOfferTemplate(template: OfferTemplateEntity) {
            offerTemplate = template
        }
    }
}
