package com.example

import com.example.data.local.entity.ComparablePropertyEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.ai.analyst.RealEstateAnalystInputFactory
import com.example.domain.ai.analyst.RealEstateAnalystInputSchema
import com.example.domain.ai.analyst.toJsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealEstateAnalystInputFactoryTest {
    @Test
    fun mapsOnlyWhitelistedSelectedPropertyDataAndCapsComparables() {
        val property = PropertyEntity(
            id = "internal-property-id-secret",
            sourceType = "ON_MARKET",
            title = "Internal listing title not sent",
            address = "111 Private Street",
            city = "Austin",
            state = "TX",
            zipCode = "78704",
            latitude = 30.0,
            longitude = -97.0,
            price = 450000.0,
            propertyType = "Single Family",
            bedrooms = 3,
            bathrooms = 2.0,
            squareFeet = 1800,
            yearBuilt = 2012,
            lotSizeSqFt = 6000,
            description = "PRIVATE DESCRIPTION MUST NOT REACH THE MODEL",
            status = "Active",
            primaryImageUrl = "https://private.invalid/image.jpg",
            scannedAt = 100L,
            isSaved = true,
            isSavedDeal = true,
            dealScore = 99
        )
        val comparables = (1..9).map { index ->
            ComparablePropertyEntity(
                id = index.toLong(),
                targetPropertyId = property.id,
                compAddress = "Comparable Street $index",
                compPrice = 400000.0 + index,
                compBeds = 3,
                compBaths = 2.0,
                compSqFt = 1700 + index,
                distanceMiles = 0.2,
                saleDate = "Recent sale"
            )
        }

        val input = RealEstateAnalystInputFactory().create(property, comparables = comparables)
        val json = input.toJsonObject()
        val serialized = json.toString()

        assertEquals(RealEstateAnalystInputSchema.MAX_COMPARABLES, json.getJSONArray("comparables").length())
        assertTrue(serialized.contains("askingPriceUsd"))
        assertTrue(serialized.contains("property.asking_price_usd"))
        assertFalse(serialized.contains(property.id))
        assertFalse(serialized.contains(property.title))
        assertFalse(serialized.contains(property.address))
        assertFalse(serialized.contains(property.description))
        assertFalse(serialized.contains(property.primaryImageUrl))
        assertFalse(serialized.contains("latitude"))
        assertFalse(serialized.contains("dealScore"))
        assertFalse(serialized.contains("Comparable Street 9"))
    }

    @Test
    fun unavailableRecordsRemainExplicitlyNullRatherThanInventedDefaults() {
        val property = PropertyEntity(
            id = "internal-property-id-secret",
            sourceType = "ON_MARKET",
            title = "",
            address = "",
            city = "Austin",
            state = "TX",
            zipCode = "78704",
            latitude = 0.0,
            longitude = 0.0,
            price = 450000.0,
            propertyType = "Single Family",
            bedrooms = 3,
            bathrooms = 2.0,
            squareFeet = 1800,
            yearBuilt = 2012,
            lotSizeSqFt = 0,
            description = "",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = 0L
        )

        val json = RealEstateAnalystInputFactory().create(property).toJsonObject()

        assertTrue(json.getJSONObject("market").getJSONObject("estimatedValueUsd").get("value") === JSONObject.NULL)
        assertTrue(json.getJSONObject("rent").getJSONObject("estimatedMonthlyRentUsd").get("value") === JSONObject.NULL)
        assertTrue(json.getJSONObject("taxes").getJSONObject("annualTaxUsd").get("value") === JSONObject.NULL)
        assertTrue(json.getJSONObject("deterministicFinancialMetrics").getJSONObject("capRatePct").get("value") === JSONObject.NULL)
        assertFalse(json.getJSONArray("evidence").toString().contains("internal-property-id"))
    }
}
