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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end deduplication behavior of [PropertySourceManager] across providers, covering the
 * identity checklist: exact re-imports, partial matches, conflicting listings, address formatting
 * changes, duplicate URLs, multiple providers, and missing identifiers - plus idempotency of
 * repeated imports and deterministic conflict handling by source priority.
 */
class PropertySourceDeduplicationIntegrationTest {

    // ── cross-provider matching ─────────────────────────────────────────────────────────────────

    @Test
    fun sourceManagerDeduplicatesCrossProviderListingsByNormalizedAddress() = runBlocking {
        val zillowBundle = bundle(property("zillow-101", "123 Main Street"))
        val redfinBundle = bundle(property("redfin-802", "123 Main St."))
        val manager = PropertySourceManager(
            listOf(adapter("Zillow", zillowBundle), adapter("Redfin", redfinBundle))
        )

        val decisions = manager.fetchAllSourcesWithDeduplication()
        val safeNew = manager.fetchAllSources()

        assertEquals(
            listOf(DeduplicationStatus.NEW, DeduplicationStatus.CANONICAL_MATCH),
            decisions.map { it.result.status }
        )
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, decisions[1].result.matchMethod)
        assertEquals("zillow-101", decisions[1].result.matchedCanonicalId)
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
        // Ambiguous decisions are marked for review and never expose a merge target.
        assertTrue(decisions[1].result.requiresReview)
        assertTrue(decisions[2].result.requiresReview)
        assertTrue(!decisions[1].result.autoMergeAllowed && !decisions[2].result.autoMergeAllowed)
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
        assertEquals(DeduplicationStatus.CANONICAL_MATCH, decisions.single().result.status)
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
        assertEquals(DeduplicationStatus.CANONICAL_MATCH, decisions[1].result.status)
        assertEquals(IdentityMatchMethod.APN, decisions[1].result.matchMethod)
    }

    @Test
    fun multipleProvidersConvergeOnOneCanonicalProperty() = runBlocking {
        // The first provider establishes the canonical property including APN and MLS number.
        val zillow = bundle(
            property("zillow-1", "123 Main St"),
            sourceIdentity = SourcePropertyIdentity(
                source = "Zillow",
                providerListingId = "z-1",
                parcelId = "11-22",
                mlsId = "A1050837"
            )
        )
        // The second matches by APN, the third by MLS ID - both are different sources.
        val redfin = bundle(
            property("redfin-1", ""),
            sourceIdentity = SourcePropertyIdentity(source = "Redfin", providerListingId = "r-1", parcelId = "APN: 1122")
        )
        val attom = bundle(
            property("attom-1", ""),
            sourceIdentity = SourcePropertyIdentity(source = "ATTOM", providerListingId = "a-1", mlsId = "MLS# a10-50837")
        )
        val manager = PropertySourceManager(
            listOf(adapter("Zillow", zillow), adapter("Redfin", redfin), adapter("ATTOM", attom))
        )

        val decisions = manager.fetchAllSourcesWithDeduplication()
        val safeNew = manager.fetchAllSources()

        assertEquals(
            listOf(DeduplicationStatus.NEW, DeduplicationStatus.CANONICAL_MATCH, DeduplicationStatus.CANONICAL_MATCH),
            decisions.map { it.result.status }
        )
        assertEquals(IdentityMatchMethod.APN, decisions[1].result.matchMethod)
        assertEquals(IdentityMatchMethod.MLS_ID, decisions[2].result.matchMethod)
        assertEquals("zillow-1", decisions[1].result.matchedCanonicalId)
        assertEquals("zillow-1", decisions[2].result.matchedCanonicalId)
        assertEquals(listOf("zillow-1"), safeNew.map { it.property.id })
    }

    @Test
    fun addressFormattingChangesDoNotCreateDuplicates() = runBlocking {
        val verbose = bundle(property("verbose-listing", "2418 South Congress Avenue").copy(city = "Austin", state = "Texas", zipCode = "78704"))
        val terse = bundle(property("terse-listing", "2418 s. congress ave.").copy(city = "AUSTIN", state = "TX", zipCode = "78704-1234"))
        val manager = PropertySourceManager(listOf(adapter("Zillow", verbose), adapter("Redfin", terse)))

        val decisions = manager.fetchAllSourcesWithDeduplication()

        assertEquals(DeduplicationStatus.NEW, decisions[0].result.status)
        assertEquals(DeduplicationStatus.CANONICAL_MATCH, decisions[1].result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, decisions[1].result.matchMethod)
        assertEquals("verbose-listing", decisions[1].result.matchedCanonicalId)
    }

    // ── duplicate URLs (same-record identity / idempotency) ─────────────────────────────────────

    @Test
    fun duplicateListingUrlIsAnIdempotentExactMatch() = runBlocking {
        val url = "https://www.zillow.com/homedetails/123-Main-St-Austin-TX-78701/99999_zpid/"
        val first = bundle(
            property("zillow-url-1", "123 Main St"),
            externalUrl = url,
            sourceIdentity = SourcePropertyIdentity(source = "Zillow", providerListingId = "z-1")
        )
        // The same page re-imported with a tracking parameter and a different adapter label.
        val duplicate = bundle(
            property("zillow-url-2", "123 Main St"),
            externalUrl = "$url?utm_source=email&fbclid=xyz",
            sourceIdentity = SourcePropertyIdentity(source = "Zillow Partner Feed", providerListingId = "zp-9")
        )
        val manager = PropertySourceManager(listOf(adapter("Zillow", first), adapter("Zillow Partner Feed", duplicate)))

        val decisions = manager.fetchAllSourcesWithDeduplication()
        val safeNew = manager.fetchAllSources()

        assertEquals(DeduplicationStatus.NEW, decisions[0].result.status)
        assertEquals(DeduplicationStatus.EXACT_MATCH, decisions[1].result.status)
        assertEquals(IdentityMatchMethod.SOURCE_URL, decisions[1].result.matchMethod)
        assertEquals("zillow-url-1", decisions[1].result.matchedCanonicalId)
        assertEquals(listOf("zillow-url-1"), safeNew.map { it.property.id })
    }

    @Test
    fun theSameRecordAppearingTwiceInOneFeedIsImportedOnce() = runBlocking {
        val record = bundle(
            property("zillow-77", "123 Main St"),
            sourceIdentity = SourcePropertyIdentity(source = "Zillow", providerListingId = "z-77")
        )
        val manager = PropertySourceManager(listOf(adapter("Zillow", record, record)))

        val decisions = manager.fetchAllSourcesWithDeduplication()
        val safeNew = manager.fetchAllSources()

        assertEquals(listOf(DeduplicationStatus.NEW, DeduplicationStatus.EXACT_MATCH), decisions.map { it.result.status })
        assertEquals(listOf("zillow-77"), safeNew.map { it.property.id })
    }

    @Test
    fun reimportingTheSameSourceAfterARunIsAnExactMatchAndChangesNothing() = runBlocking {
        val record = bundle(
            property("zillow-55", "123 Main St"),
            externalUrl = "https://www.zillow.com/homedetails/55_zpid",
            sourceIdentity = SourcePropertyIdentity(source = "Zillow", providerListingId = "z-55")
        )
        val manager = PropertySourceManager(listOf(adapter("Zillow", record)))

        val firstRun = manager.fetchAllSourcesWithDeduplication()
        // Persist the catalog the way the repository would: canonical identities with their
        // linked source records. NEW decisions expose the identity to persist.
        val persistedCatalog = firstRun.mapNotNull { it.result.resolvedCanonicalIdentity }

        val secondRun = manager.fetchAllSourcesWithDeduplication(existingCanonicalProperties = persistedCatalog)

        assertEquals(listOf(DeduplicationStatus.NEW), firstRun.map { it.result.status })
        assertEquals(listOf(DeduplicationStatus.EXACT_MATCH), secondRun.map { it.result.status })
        assertEquals("zillow-55", secondRun.single().result.matchedCanonicalId)
        // The exact re-import is an idempotent refresh: nothing becomes "new" again.
        val thirdRun = manager.fetchAllSourcesWithDeduplication(
            existingCanonicalProperties = secondRun.mapNotNull { it.result.resolvedCanonicalIdentity }
        )
        assertEquals(listOf(DeduplicationStatus.EXACT_MATCH), thirdRun.map { it.result.status })
    }

    // ── missing identifiers ─────────────────────────────────────────────────────────────────────

    @Test
    fun listingsWithoutAnyIdentifierAreImportedAsNewWithoutCrashing() = runBlocking {
        val unlocated = PropertyEntity(
            id = "mystery-row",
            sourceType = "ON_MARKET",
            title = "Unlocated property",
            address = "",
            city = "",
            state = "",
            zipCode = "",
            latitude = 0.0,
            longitude = 0.0,
            price = 100_000.0,
            propertyType = "Single Family",
            bedrooms = 0,
            bathrooms = 0.0,
            squareFeet = 0,
            yearBuilt = 0,
            lotSizeSqFt = 0,
            description = "",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = 1L
        )
        val located = bundle(property("located-row", "123 Main St"))
        val manager = PropertySourceManager(
            listOf(adapter("Zillow", bundle(unlocated), located))
        )

        val decisions = manager.fetchAllSourcesWithDeduplication()

        assertEquals(
            listOf(DeduplicationStatus.NEW, DeduplicationStatus.NEW),
            decisions.map { it.result.status }
        )
        // The empty identity still explains itself instead of failing.
        assertTrue(decisions[0].result.reason.contains("No APN/parcel ID"))
        assertEquals(2, manager.fetchAllSources().size)
    }

    // ── conflicting source values + source priority ─────────────────────────────────────────────

    @Test
    fun lowerPrioritySourceWinsFieldConflictsDuringCatalogMerge() = runBlocking {
        // Zillow is the authoritative feed (priority 10); the partner feed is lower authority (90).
        val authoritative = bundle(
            property("z-priority", "123 Main Street").copy(city = "Austin", state = "TX", zipCode = "78701"),
            sourceIdentity = SourcePropertyIdentity(
                source = "Zillow",
                providerListingId = "z-priority",
                parcelId = "11-22",
                sourcePriority = 10,
                observedAt = 1_000L
            )
        )
        val lowerAuthority = bundle(
            property("partner-priority", "123 Main St").copy(city = "Austin", state = "TX", zipCode = "78701"),
            sourceIdentity = SourcePropertyIdentity(
                source = "Partner Feed",
                providerListingId = "p-1",
                parcelId = "11-22",
                sourcePriority = 90,
                observedAt = 2_000L
            )
        )
        val observer = bundle(
            property("attom-observer", ""),
            sourceIdentity = SourcePropertyIdentity(
                source = "ATTOM",
                providerListingId = "a-1",
                parcelId = "1122",
                sourcePriority = 50,
                observedAt = 3_000L
            )
        )
        val manager = PropertySourceManager(
            listOf(adapter("Zillow", authoritative), adapter("Partner Feed", lowerAuthority), adapter("ATTOM", observer))
        )

        val decisions = manager.fetchAllSourcesWithDeduplication()

        // Both later sources resolve to the first property by APN.
        assertEquals(
            listOf(DeduplicationStatus.NEW, DeduplicationStatus.CANONICAL_MATCH, DeduplicationStatus.CANONICAL_MATCH),
            decisions.map { it.result.status }
        )
        // The third decision exposes the catalog entry after all merges: the authoritative
        // spelling survived even though the lower-priority feed was observed most recently.
        val mergedIdentity = decisions[2].result.canonicalIdentity
        assertNotNull(mergedIdentity)
        assertEquals("123 Main Street", mergedIdentity!!.address)
        assertEquals(3, mergedIdentity.sourceIdentities.size)
        assertTrue(mergedIdentity.sourceIdentities.any { it.source == "Zillow" })
        assertTrue(mergedIdentity.sourceIdentities.any { it.source == "Partner Feed" })
        assertTrue(mergedIdentity.sourceIdentities.any { it.source == "ATTOM" })
    }

    @Test
    fun conflictingExactSignalsSurfaceAsConflictAndAreNeverMerged() = runBlocking {
        val base = bundle(
            property("base-row", "123 Main St"),
            sourceIdentity = SourcePropertyIdentity(source = "Zillow", providerListingId = "z-1", parcelId = "11-22")
        )
        // Matches the stored parcel ID but claims a completely different address.
        val contradictory = bundle(
            property("contradictory-row", "999 Oak Rd"),
            sourceIdentity = SourcePropertyIdentity(source = "ATTOM", providerListingId = "a-9", parcelId = "1122")
        )
        val manager = PropertySourceManager(listOf(adapter("Zillow", base), adapter("ATTOM", contradictory)))

        val decisions = manager.fetchAllSourcesWithDeduplication()

        assertEquals(DeduplicationStatus.NEW, decisions[0].result.status)
        assertEquals(DeduplicationStatus.CONFLICT, decisions[1].result.status)
        assertTrue(decisions[1].result.requiresReview)
        assertEquals(listOf("base-row"), decisions[1].result.candidateCanonicalIds)
        assertEquals(listOf("base-row"), manager.fetchAllSources().map { it.property.id })
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

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

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
        sourceIdentity: SourcePropertyIdentity? = null,
        externalUrl: String = ""
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
        sourceIdentity = sourceIdentity,
        externalUrl = externalUrl
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
