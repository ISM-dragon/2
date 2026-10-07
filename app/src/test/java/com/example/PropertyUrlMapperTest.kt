package com.example

import com.example.data.urlintelligence.CanonicalPropertyMapper
import com.example.urlintelligence.model.CanonicalAddress
import com.example.urlintelligence.model.CanonicalListingStatus
import com.example.urlintelligence.model.CanonicalProperty
import com.example.urlintelligence.model.CanonicalPropertyType
import com.example.urlintelligence.model.CompletenessReport
import com.example.urlintelligence.model.GeoPoint
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that canonical properties map cleanly onto the app's Room model. */
class PropertyUrlMapperTest {

    private fun property(
        status: CanonicalListingStatus = CanonicalListingStatus.FOR_SALE,
        price: Double? = 485_000.0,
        rent: Double? = 4_400.0,
        type: CanonicalPropertyType = CanonicalPropertyType.MULTI_FAMILY,
        daysOnMarket: Int? = 12
    ): CanonicalProperty {
        val draft = PropertyDraft("zillow", "https://www.zillow.com/homedetails/1_zpid")
        draft.sourcePropertyId = "12345678"
        draft.put(PropertyField.LIST_PRICE, price, ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "test")
        draft.put(PropertyField.ESTIMATED_MONTHLY_RENT, rent, ExtractionMethod.STRUCTURED_DATA, Confidence.HIGH, "test")

        return CanonicalProperty(
            canonicalId = "zillow:12345678",
            sourceId = "zillow",
            sourcePropertyId = "12345678",
            canonicalUrl = "https://www.zillow.com/homedetails/1_zpid",
            resolvedUrl = "https://www.zillow.com/homedetails/1_zpid",
            address = CanonicalAddress(
                line1 = "2418 S Congress Ave",
                unit = "B",
                city = "Austin",
                stateOrProvince = "TX",
                postalCode = "78704",
                geo = GeoPoint(30.2415, -97.7551)
            ),
            propertyType = type,
            listingStatus = status,
            listPriceUsd = price,
            bedrooms = 4.0,
            bathrooms = 3.0,
            livingAreaSqFt = 2250,
            lotSizeSqFt = 6500,
            yearBuilt = 2017,
            description = "Turnkey duplex",
            primaryImageUrl = "https://img.test/a.jpg",
            imageUrls = listOf("https://img.test/a.jpg", "https://img.test/b.jpg"),
            estimatedMonthlyRentUsd = rent,
            annualPropertyTaxUsd = 5_800.0,
            daysOnMarket = daysOnMarket,
            fetchedAtEpochMillis = 1_700_000_000_000L,
            completeness = CompletenessReport.compute(
                setOf(
                    PropertyField.ADDRESS_LINE1,
                    PropertyField.CITY,
                    PropertyField.STATE,
                    PropertyField.POSTAL_CODE,
                    PropertyField.LIST_PRICE,
                    PropertyField.ESTIMATED_MONTHLY_RENT
                )
            ),
            provenance = draft.provenance(1_700_000_000_000L)
        )
    }

    @Test
    fun `maps a canonical property onto the room bundle`() {
        val bundle = CanonicalPropertyMapper.toBundle(property())
        val entity = bundle.property

        assertEquals("zillow:12345678", entity.id)
        assertEquals("ON_MARKET", entity.sourceType)
        assertEquals("2418 S Congress Ave, Unit B, Austin, TX 78704", entity.title)
        assertEquals("2418 S Congress Ave Unit B", entity.address)
        assertEquals("Austin", entity.city)
        assertEquals("TX", entity.state)
        assertEquals("78704", entity.zipCode)
        assertEquals(30.2415, entity.latitude, 0.0001)
        assertEquals(485_000.0, entity.price, 0.001)
        assertEquals("Multi-Family", entity.propertyType)
        assertEquals(4, entity.bedrooms)
        assertEquals(3.0, entity.bathrooms, 0.001)
        assertEquals(2250, entity.squareFeet)
        assertEquals(2017, entity.yearBuilt)
        assertEquals(6500, entity.lotSizeSqFt)
        assertEquals("Active", entity.status)
        assertEquals("https://img.test/a.jpg", entity.primaryImageUrl)
        assertEquals(1_700_000_000_000L, entity.scannedAt)
        assertFalse(entity.isSaved)
    }

    @Test
    fun `rent estimate and gross yield are derived from provenance confidence`() {
        val bundle = CanonicalPropertyMapper.toBundle(property())
        assertEquals(4_400.0, bundle.rentEstimate.estimatedRent, 0.001)
        assertEquals(90.0, bundle.rentEstimate.rentConfidenceScore, 0.001)
        assertTrue(bundle.rentEstimate.grossYield > 10.0)
        assertEquals(12, bundle.marketData.averageDaysOnMarket)
        assertEquals("High", bundle.marketData.marketDemand)
        assertEquals(5_800.0, bundle.taxRecord.annualTaxAmount, 0.001)
    }

    @Test
    fun `off market statuses map to the off market source type`() {
        assertEquals("OFF_MARKET", CanonicalPropertyMapper.toBundle(
            property(status = CanonicalListingStatus.SOLD)
        ).property.sourceType)
        assertEquals("ON_MARKET", CanonicalPropertyMapper.toBundle(
            property(status = CanonicalListingStatus.PENDING)
        ).property.sourceType)
        assertEquals("Pending", CanonicalPropertyMapper.statusLabel(CanonicalListingStatus.PENDING))
    }

    @Test
    fun `missing values degrade to safe defaults`() {
        val bundle = CanonicalPropertyMapper.toBundle(property(price = null, rent = null, daysOnMarket = null))
        assertEquals(0.0, bundle.property.price, 0.001)
        assertEquals(0.0, bundle.rentEstimate.estimatedRent, 0.001)
        assertEquals(0.0, bundle.rentEstimate.grossYield, 0.001)
        assertEquals(0, bundle.marketData.averageDaysOnMarket)
        assertEquals("Unknown", bundle.marketData.marketDemand)
    }

    @Test
    fun `provenance summary lists sources per field`() {
        val summary = CanonicalPropertyMapper.provenanceSummary(property())
        assertTrue(summary.contains("listing.price"))
        assertTrue(summary.contains("zillow"))
        assertTrue(summary.contains("STRUCTURED_DATA"))
    }

    @Test
    fun `images are converted with the first marked primary`() {
        val images = CanonicalPropertyMapper.toBundle(property()).images
        assertEquals(2, images.size)
        assertTrue(images.first().isPrimary)
        assertFalse(images.last().isPrimary)
        assertEquals("zillow:12345678", images.first().propertyId)
    }
}
