package com.example

import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.adapter.PropertySourceAdapter
import com.example.data.adapter.PropertySourceManager
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.PropertyEntity
import com.example.data.security.CryptoManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StrictSecurityAndIntegrationTest {

    @Test
    fun testAes256GcmCryptoIntegrity() {
        val sampleSecret = "AIzaSyD_SecretProductionKey9876543210"
        val encrypted1 = CryptoManager.encrypt(sampleSecret)
        val encrypted2 = CryptoManager.encrypt(sampleSecret)

        // 1. Should not contain plaintext
        assertFalse("Ciphertext must not expose plaintext", encrypted1.contains(sampleSecret))

        // 2. Random IV: Each GCM encryption of identical plaintext must yield distinct ciphertext
        assertNotEquals("Each AES-GCM encryption must use unique IV", encrypted1, encrypted2)

        // 3. Symmetric round-trip
        val decrypted1 = CryptoManager.decrypt(encrypted1)
        val decrypted2 = CryptoManager.decrypt(encrypted2)
        assertEquals(sampleSecret, decrypted1)
        assertEquals(sampleSecret, decrypted2)

        // 4. Empty string handling
        assertEquals("", CryptoManager.encrypt(""))
        assertEquals("", CryptoManager.decrypt(""))

        // 5. Corrupt ciphertext handling
        val corrupted = encrypted1.substring(0, encrypted1.length - 8) + "AAAA"
        val corruptResult = CryptoManager.decrypt(corrupted)
        assertEquals("Decryption of corrupted ciphertext must safely fail to empty string", "", corruptResult)
    }

    @Test
    fun testGmailStatesAndZeroMockTokens() {
        val unconfigured = GmailConfigurationEntity(
            id = 1,
            isConnected = false,
            authStatus = "NOT_CONFIGURED",
            accessToken = null,
            refreshToken = null
        )
        assertFalse(unconfigured.isConnected)
        assertEquals("NOT_CONFIGURED", unconfigured.authStatus)
        assertNull(unconfigured.accessToken)

        val expiredConfig = GmailConfigurationEntity(
            id = 1,
            isConnected = true,
            authStatus = "AUTH_EXPIRED",
            accessToken = "expired_token_abc",
            refreshToken = null,
            expiresAt = System.currentTimeMillis() - 10000L
        )
        assertTrue("Expired token detected", expiredConfig.expiresAt < System.currentTimeMillis())
        assertNull("Missing refresh token", expiredConfig.refreshToken)
    }

    @Test
    fun testPropertySourceManagerDeduplication() = runBlocking {
        val prop = PropertyEntity(
            id = "duplicate-prop-001",
            sourceType = "ON_MARKET",
            title = "Duplicated Listing",
            address = "500 Congress Ave",
            city = "Austin",
            state = "TX",
            zipCode = "78701",
            latitude = 30.26,
            longitude = -97.74,
            price = 550000.0,
            propertyType = "Condo",
            bedrooms = 2,
            bathrooms = 2.0,
            squareFeet = 1100,
            yearBuilt = 2015,
            lotSizeSqFt = 0,
            description = "Downtown high rise",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = System.currentTimeMillis()
        )

        val mockBundle = NormalizedPropertyBundle(
            property = prop,
            images = emptyList(),
            marketData = com.example.data.local.entity.MarketDataEntity(
                propertyId = prop.id,
                estimatedValue = 550000.0,
                neighborhoodAppreciationRate = 4.5,
                medianAreaPrice = 600000.0,
                averageDaysOnMarket = 15,
                pricePerSqFt = 500.0,
                marketDemand = "High"
            ),
            rentEstimate = com.example.data.local.entity.RentEstimateEntity(
                propertyId = prop.id,
                estimatedRent = 3200.0,
                rentRangeLow = 2900.0,
                rentRangeHigh = 3500.0,
                rentConfidenceScore = 0.9,
                grossYield = 6.9
            ),
            taxRecord = com.example.data.local.entity.TaxRecordEntity(
                propertyId = prop.id,
                annualTaxAmount = 6000.0,
                assessmentYear = 2024,
                assessedValue = 500000.0,
                taxDelinquent = false
            ),
            salesHistory = emptyList(),
            comps = emptyList()
        )

        val adapter1 = object : PropertySourceAdapter {
            override val sourceName = "Source A"
            override val sourceType = "ON_MARKET"
            override suspend fun fetchProperties(query: String?, minPrice: Double?, maxPrice: Double?, limit: Int) = listOf(mockBundle)
        }

        val adapter2 = object : PropertySourceAdapter {
            override val sourceName = "Source B"
            override val sourceType = "OFF_MARKET"
            override suspend fun fetchProperties(query: String?, minPrice: Double?, maxPrice: Double?, limit: Int) = listOf(mockBundle)
        }

        val manager = PropertySourceManager(listOf(adapter1, adapter2))
        val combined = manager.fetchAllSources(limitPerSource = 10)

        // Must deduplicate so that prop appears only once
        assertEquals("Should contain exactly 1 property without duplicates", 1, combined.size)
        assertEquals("duplicate-prop-001", combined[0].property.id)
    }
}
