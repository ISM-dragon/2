package com.example

import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.adapter.PropertySourceAdapter
import com.example.data.adapter.PropertySourceManager
import com.example.data.local.entity.MarketDataEntity
import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.RentEstimateEntity
import com.example.data.local.entity.TaxRecordEntity
import com.example.domain.identity.CanonicalPropertyIdentity
import com.example.domain.identity.DeduplicationStatus
import com.example.domain.identity.IdentityMatchMethod
import com.example.domain.identity.SourcePropertyIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertySourceDeduplicationIntegrationTest {
    @Test
    fun sourceManagerDeduplicatesCrossProviderListingsByNormalizedAddress() = runBlocking {
        val zillowBundle = bundle(property("zillow-101", "123 Main Street"))
        val redfinBundle = bundle(property("redfin-802", "123 Main St."))
        val manager = PropertySourceManager(
            listOf(adapter("Zillow", zillowBundle), adapter("Redfin", redfinBundle))
        )

        val decisions = manager.fetchAllSourcesWithDeduplication()
        val safeNew = manager.fetchAllSources()

        assertEquals(listOf(DeduplicationStatus.NEW, DeduplicationStatus.MATCHED), decisions.map { it.result.status })
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, decisions[1].result.matchMethod)
        assertEquals(listOf("zillow-101"), safeNew.map { it.property.id })
    }

    @Test
    fun possibleAndConflictingListingsAreReportedButNotReturnedAsNew() = runBlocking {
        val first = bundle(
            property("first", "123 Main St"),
            sourceIdentity = SourcePropertyIdentity(source = "Zillow", providerListingId = "z-shared")
        )
        val typo = bundle(property("typo", "123 Mane St"))
        val conflict = bundle(
            property("conflict", "999 Oak Rd"),
            sourceIdentity = SourcePropertyIdentity(source = "Zillow", providerListingId = "z-shared")
        )
        val manager = PropertySourceManager(
            listOf(adapter("Zillow", first), adapter("Redfin", typo), adapter("Zillow", conflict))
        )

        val decisions = manager.fetchAllSourcesWithDeduplication()
        val safeNew = manager.fetchAllSources()

        assertEquals(
            listOf(DeduplicationStatus.NEW, DeduplicationStatus.POSSIBLE_MATCH, DeduplicationStatus.CONFLICT),
            decisions.map { it.result.status }
        )
        // Only the certain NEW item is returned; the typo and contradictory provider ID need review.
        assertEquals(setOf("first"), safeNew.map { it.property.id }.toSet())
        assertTrue(decisions[1].result.reason.contains("review", ignoreCase = true))
        assertTrue(decisions[2].result.reason.contains("address", ignoreCase = true))
    }

    @Test
    fun persistedCanonicalPropertiesAreUsedAsCandidatesDuringSync() = runBlocking {
        val incoming = bundle(property("new-provider-id", "123 Main St"))
        val manager = PropertySourceManager(listOf(adapter("Redfin", incoming)))
        val known = listOf(
            CanonicalPropertyIdentity(
                canonicalId = "already-stored",
                address = "123 Main Street",
                city = "Austin",
                state = "TX",
                postalCode = "78701"
            )
        )

        val decisions = manager.fetchAllSourcesWithDeduplication(existingCanonicalProperties = known)

        assertEquals(1, decisions.size)
        assertEquals(DeduplicationStatus.MATCHED, decisions.single().result.status)
        assertEquals("already-stored", decisions.single().result.matchedCanonicalId)
    }

    @Test
    fun adapterCanPassApnAndProviderIdsIntoTheDeduplicationEngine() = runBlocking {
        val first = bundle(
            property("zillow-row", ""),
            sourceIdentity = SourcePropertyIdentity(source = "Zillow", providerListingId = "z-77", parcelId = "APN 11-22")
        )
        val second = bundle(
            property("attom-row", ""),
            sourceIdentity = SourcePropertyIdentity(source = "ATTOM", providerListingId = "a-99", parcelId = "1122")
        )
        val manager = PropertySourceManager(listOf(adapter("Zillow", first), adapter("ATTOM", second)))

        val decisions = manager.fetchAllSourcesWithDeduplication()

        assertEquals(DeduplicationStatus.NEW, decisions[0].result.status)
        assertEquals(DeduplicationStatus.MATCHED, decisions[1].result.status)
        assertEquals(IdentityMatchMethod.APN, decisions[1].result.matchMethod)
    }

    @Test
    fun sameProviderListingIdWithDifferentAddressIsSurfacedAsConflict() = runBlocking {
        val first = bundle(
            property("z-row-1", "123 Main St"),
            sourceIdentity = SourcePropertyIdentity(source = "Zillow", providerListingId = "same-id")
        )
        val contradictory = bundle(
            property("z-row-2", "555 Oak Ave"),
            sourceIdentity = SourcePropertyIdentity(source = "Zillow", providerListingId = "same-id")
        )
        val manager = PropertySourceManager(listOf(adapter("Zillow", first), adapter("Zillow", contradictory)))

        val decisions = manager.fetchAllSourcesWithDeduplication()

        assertEquals(DeduplicationStatus.NEW, decisions[0].result.status)
        assertEquals(DeduplicationStatus.CONFLICT, decisions[1].result.status)
        assertTrue(decisions[1].result.reason.contains("address", ignoreCase = true))
    }

    private fun adapter(name: String, vararg bundles: NormalizedPropertyBundle) =
        object : PropertySourceAdapter {
            override val sourceName: String = name
            override val sourceType: String = "ON_MARKET"

            override suspend fun fetchProperties(
                query: String?,
                minPrice: Double?,
                maxPrice: Double?,
                limit: Int
            ): List<NormalizedPropertyBundle> = bundles.toList().take(limit)
        }

    private fun bundle(
        property: PropertyEntity,
        sourceIdentity: SourcePropertyIdentity? = null
    ) = NormalizedPropertyBundle(
        property = property,
        images = emptyList(),
        marketData = MarketDataEntity(
            propertyId = property.id,
            estimatedValue = property.price,
            neighborhoodAppreciationRate = 0.0,
            medianAreaPrice = property.price,
            averageDaysOnMarket = 0,
            pricePerSqFt = 0.0,
            marketDemand = "Balanced"
        ),
        rentEstimate = RentEstimateEntity(
            propertyId = property.id,
            estimatedRent = 0.0,
            rentRangeLow = 0.0,
            rentRangeHigh = 0.0,
            rentConfidenceScore = 0.0,
            grossYield = 0.0
        ),
        taxRecord = TaxRecordEntity(
            propertyId = property.id,
            annualTaxAmount = 0.0,
            assessmentYear = 2026,
            assessedValue = 0.0
        ),
        salesHistory = emptyList(),
        comps = emptyList(),
        sourceIdentity = sourceIdentity
    )

    private fun property(id: String, address: String) = PropertyEntity(
        id = id,
        sourceType = "ON_MARKET",
        title = address.ifBlank { "Unlocated property" },
        address = address,
        city = if (address.isBlank()) "" else "Austin",
        state = if (address.isBlank()) "" else "TX",
        zipCode = if (address.isBlank()) "" else "78701",
        latitude = 0.0,
        longitude = 0.0,
        price = 300_000.0,
        propertyType = "Single Family",
        bedrooms = 3,
        bathrooms = 2.0,
        squareFeet = 1_500,
        yearBuilt = 2000,
        lotSizeSqFt = 5_000,
        description = "",
        status = "Active",
        primaryImageUrl = "",
        scannedAt = 1L
    )
}
