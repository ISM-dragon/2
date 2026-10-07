package com.example

import com.example.domain.identity.CanonicalPropertyIdentity
import com.example.domain.identity.DeduplicationConfig
import com.example.domain.identity.DeduplicationStatus
import com.example.domain.identity.IdentityMatchMethod
import com.example.domain.identity.PropertyIdentityDeduplicationEngine
import com.example.domain.identity.SourcePropertyIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyIdentityDeduplicationEngineTest {
    private val engine = PropertyIdentityDeduplicationEngine()

    @Test
    fun parcelIdIsHighestPriorityAndIgnoresSeparatorsAndCase() {
        val incoming = identity(
            source = "Redfin",
            providerListingId = "rf-42",
            parcelId = "APN: 014-22-0007",
            address = "123 Main St",
            city = "Austin",
            state = "TX",
            postalCode = "78701",
            latitude = 30.0,
            longitude = -97.0
        )
        val candidate = canonical(
            id = "canonical-1",
            parcelId = "014220007",
            address = "123 Main Street",
            city = "Austin",
            state = "Texas",
            postalCode = "78701",
            latitude = 30.0,
            longitude = -97.0,
            sourceIdentities = setOf(identity("Redfin", "rf-42", "014220007", "123 Main Street"))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.APN, result.matchMethod)
        assertEquals("canonical-1", result.matchedCanonicalId)
        assertTrue(result.confidence > 0.99)
    }

    @Test
    fun normalizedAddressHandlesCasingDirectionsAndStreetSuffixes() {
        val incoming = identity(
            address = "2418 s. congress ave.", city = "Austin", state = "Texas", postalCode = "78704"
        )
        val candidate = canonical(
            id = "austin-home",
            address = "2418 South Congress Avenue",
            city = "AUSTIN",
            state = "TX",
            postalCode = "78704"
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
    }

    @Test
    fun normalizedAddressRemovesAppendedCityStateAndZip() {
        val incoming = identity(
            address = "123 Main Street, Austin, TX 78701",
            city = "Austin",
            state = "TX",
            postalCode = "78701"
        )
        val candidate = canonical(
            id = "main-street",
            address = "123 Main St",
            city = "Austin",
            state = "Texas",
            postalCode = "78701-4420"
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
    }

    @Test
    fun normalizedAddressTreatsApartmentAndHashAsTheSameUnit() {
        val incoming = identity(
            address = "1900 N Bayshore Drive Apt. 1402",
            city = "Miami",
            state = "FL",
            postalCode = "33132"
        )
        val candidate = canonical(
            id = "condo-1402",
            address = "1900 North Bayshore Dr #1402",
            city = "Miami",
            state = "Florida",
            postalCode = "33132"
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
    }

    @Test
    fun distinctApartmentUnitsAreNotAddressMatches() {
        val incoming = identity(address = "1900 N Bayshore Dr Unit 1604", city = "Miami", state = "FL", postalCode = "33132")
        val candidate = canonical(
            id = "condo-1402", address = "1900 N Bayshore Dr Unit 1402", city = "Miami", state = "FL", postalCode = "33132"
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.NEW, result.status)
    }

    @Test
    fun omittedUnitIsReviewOnlyRatherThanAutomaticallyMerged() {
        val incoming = identity(address = "1900 N Bayshore Dr #1402", city = "Miami", state = "FL", postalCode = "33132")
        val candidate = canonical(id = "condo-building", address = "1900 N Bayshore Dr", city = "Miami", state = "FL", postalCode = "33132")

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.POSSIBLE_MATCH, result.status)
        assertEquals(IdentityMatchMethod.FUZZY_ADDRESS, result.matchMethod)
        assertNull(result.canonicalIdentity)
    }

    @Test
    fun distinctApartmentUnitsAtSameBuildingCoordinatesAreAConflict() {
        val incoming = identity(
            address = "1900 N Bayshore Dr #1604", city = "Miami", state = "FL", postalCode = "33132",
            latitude = 25.7945, longitude = -80.1884
        )
        val candidate = canonical(
            id = "condo-1402", address = "1900 N Bayshore Dr Apt 1402", city = "Miami", state = "FL",
            postalCode = "33132", latitude = 25.7945, longitude = -80.1884
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertTrue(result.reason.contains("unit", ignoreCase = true))
    }

    @Test
    fun sameAddressWithCityAliasAndSameZipCanMatch() {
        val incoming = identity(address = "500 Congress Ave", city = "Downtown Austin", state = "TX", postalCode = "78701")
        val candidate = canonical(id = "congress-home", address = "500 Congress Avenue", city = "Austin", state = "TX", postalCode = "78701")

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
    }

    @Test
    fun missingCityAndZipDoNotBlockAnOtherwiseExactAddress() {
        val incoming = identity(address = "500 Congress Ave", city = null, state = null, postalCode = null)
        val candidate = canonical(id = "congress-home", address = "500 Congress Avenue", city = "Austin", state = "TX", postalCode = "78701")

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
    }

    @Test
    fun sameStreetInDifferentCitiesWithoutSharedZipIsNotAnExactMatch() {
        val incoming = identity(address = "500 Congress Ave", city = "Austin", state = "TX")
        val candidate = canonical(id = "other-city", address = "500 Congress Avenue", city = "Houston", state = "TX")

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun differentPostalCodesPreventAnAddressMatch() {
        val incoming = identity(address = "500 Congress Ave", city = "Austin", state = "TX", postalCode = "78701")
        val candidate = canonical(id = "other-zip", address = "500 Congress Ave", city = "Austin", state = "TX", postalCode = "78702")

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun differentStatesPreventAnAddressMatch() {
        val incoming = identity(address = "123 Main St", city = "Springfield", state = "IL", postalCode = "62701")
        val candidate = canonical(id = "other-state", address = "123 Main St", city = "Springfield", state = "MO", postalCode = "65806")

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun exactCoordinatesCanMatchWithoutAddressOrParcelData() {
        val incoming = identity(latitude = 30.2672, longitude = -97.7431)
        val candidate = canonical(id = "geo-home", latitude = 30.2672, longitude = -97.7431)

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.COORDINATES, result.matchMethod)
    }

    @Test
    fun smallCoordinateDriftStillMatchesWithinConfiguredRadius() {
        val incoming = identity(latitude = 30.0, longitude = -97.0)
        val candidate = canonical(id = "geo-home", latitude = 30.00005, longitude = -97.0)

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.COORDINATES, result.matchMethod)
    }

    @Test
    fun nearbyCoordinatesOutsideAutoMatchRadiusAreReviewOnly() {
        val incoming = identity(latitude = 30.0, longitude = -97.0)
        val candidate = canonical(id = "near-home", latitude = 30.0008, longitude = -97.0)

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.POSSIBLE_MATCH, result.status)
        assertEquals(IdentityMatchMethod.COORDINATES, result.matchMethod)
        assertEquals(listOf("near-home"), result.candidateCanonicalIds)
        assertTrue(result.reason.contains("review", ignoreCase = true))
    }

    @Test
    fun coordinatesBeyondReviewRadiusDoNotMatch() {
        val incoming = identity(latitude = 30.0, longitude = -97.0)
        val candidate = canonical(id = "far-home", latitude = 30.003, longitude = -97.0)

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun incompleteCoordinatePairsAreIgnored() {
        val incoming = identity(latitude = 30.0, longitude = null)
        val candidate = canonical(id = "partial-geo", latitude = 30.0, longitude = null)

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun invalidCoordinatesAndNullIslandSentinelAreIgnored() {
        val incoming = identity(latitude = 91.0, longitude = 181.0)
        val invalid = canonical(id = "invalid", latitude = 91.0, longitude = 181.0)
        val nullIsland = canonical(id = "null-island", latitude = 0.0, longitude = 0.0)

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(invalid, nullIsland)).status)
    }

    @Test
    fun nonFiniteCoordinatesAreIgnored() {
        val incoming = identity(latitude = Double.NaN, longitude = Double.POSITIVE_INFINITY)
        val candidate = canonical(id = "not-a-point", latitude = Double.NaN, longitude = Double.POSITIVE_INFINITY)

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun providerListingIdMatchesWithinTheSameProviderNamespace() {
        val incoming = identity(source = "Zillow", providerListingId = "  Z-100  ")
        val candidate = canonical(
            id = "zillow-home",
            sourceIdentities = setOf(identity(source = "Zillow", providerListingId = "Z-100"))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.PROVIDER_LISTING_ID, result.matchMethod)
    }

    @Test
    fun providerNamespaceAndListingIdComparisonAreCaseInsensitive() {
        val incoming = identity(source = "  REDFIN ", providerListingId = "Listing-X9")
        val candidate = canonical(
            id = "redfin-home",
            sourceIdentities = setOf(identity(source = "Redfin", providerListingId = "listing-x9"))
        )

        assertEquals(DeduplicationStatus.MATCHED, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun sameListingIdFromDifferentProvidersDoesNotMatch() {
        val incoming = identity(source = "Zillow", providerListingId = "12345")
        val candidate = canonical(
            id = "redfin-home",
            sourceIdentities = setOf(identity(source = "Redfin", providerListingId = "12345"))
        )

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun providerListingMatchWithContradictoryStreetAddressIsAConflict() {
        val incoming = identity(source = "Zillow", providerListingId = "z-1", address = "555 Oak Ave", city = "Austin", state = "TX")
        val candidate = canonical(
            id = "main-home",
            sourceIdentities = setOf(
                identity(source = "Zillow", providerListingId = "z-1", address = "123 Main St", city = "Austin", state = "TX")
            )
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertTrue(result.reason.contains("address", ignoreCase = true))
    }

    @Test
    fun providerListingMatchWithContradictoryParcelIdIsAConflict() {
        val incoming = identity(source = "ATTOM", providerListingId = "attom-9", parcelId = "PARCEL ID: 22-001")
        val candidate = canonical(
            id = "parcel-home",
            parcelId = "22-002",
            sourceIdentities = setOf(identity(source = "ATTOM", providerListingId = "attom-9", parcelId = "22-002"))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertTrue(result.reason.contains("parcel", ignoreCase = true))
    }

    @Test
    fun exactAddressWithDifferentKnownParcelIdsIsAConflict() {
        val incoming = identity(parcelId = "parcel-2", address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701")
        val candidate = canonical(
            id = "parcel-1", parcelId = "parcel-1", address = "123 Main Street", city = "Austin", state = "TX", postalCode = "78701"
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertTrue(result.candidateCanonicalIds.contains("parcel-1"))
    }

    @Test
    fun exactParcelIdWithDifferentHouseNumberIsAConflict() {
        val incoming = identity(parcelId = "same-parcel", address = "125 Main St", city = "Austin", state = "TX")
        val candidate = canonical(id = "parcel-home", parcelId = "SAME-PARCEL", address = "123 Main St", city = "Austin", state = "TX")

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertTrue(result.reason.contains("address", ignoreCase = true))
    }

    @Test
    fun exactCoordinateMatchWithContradictoryKnownAddressIsAConflict() {
        val incoming = identity(address = "555 Oak Ave", city = "Austin", state = "TX", latitude = 30.0, longitude = -97.0)
        val candidate = canonical(
            id = "main-home", address = "123 Main St", city = "Austin", state = "TX", latitude = 30.0, longitude = -97.0
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
    }

    @Test
    fun providerMatchWithCoordinatesFarApartIsAConflict() {
        val incoming = identity(source = "ATTOM", providerListingId = "a-1", latitude = 30.0, longitude = -97.0)
        val candidate = canonical(
            id = "attom-home", latitude = 35.0, longitude = -100.0,
            sourceIdentities = setOf(identity(source = "ATTOM", providerListingId = "a-1"))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertTrue(result.reason.contains("coordinates", ignoreCase = true))
    }

    @Test
    fun apnAndAddressPointToDifferentCanonicalProperties() {
        val incoming = identity(parcelId = "parcel-a", address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701")
        val parcelMatch = canonical(id = "canonical-a", parcelId = "parcel-a", address = "77 Oak Rd", city = "Austin", state = "TX")
        val addressMatch = canonical(id = "canonical-b", parcelId = "parcel-b", address = "123 Main Street", city = "Austin", state = "TX", postalCode = "78701")

        val result = engine.deduplicate(incoming, listOf(addressMatch, parcelMatch))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertEquals(listOf("canonical-a", "canonical-b"), result.candidateCanonicalIds)
        assertTrue(result.reason.contains("different canonical properties"))
    }

    @Test
    fun coordinatesAndProviderIdPointToDifferentCanonicalProperties() {
        val incoming = identity(source = "Redfin", providerListingId = "r-10", latitude = 30.0, longitude = -97.0)
        val coordinateMatch = canonical(id = "coordinate-home", latitude = 30.0, longitude = -97.0)
        val providerMatch = canonical(
            id = "provider-home",
            sourceIdentities = setOf(identity(source = "Redfin", providerListingId = "r-10"))
        )

        val result = engine.deduplicate(incoming, listOf(providerMatch, coordinateMatch))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertEquals(listOf("coordinate-home", "provider-home"), result.candidateCanonicalIds)
    }

    @Test
    fun duplicateApnAcrossCanonicalRecordsIsAmbiguousConflict() {
        val incoming = identity(parcelId = "dup-apn")
        val candidates = listOf(
            canonical(id = "property-a", parcelId = "DUP-APN"),
            canonical(id = "property-b", parcelId = "dup apn")
        )

        val result = engine.deduplicate(incoming, candidates)

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertEquals(listOf("property-a", "property-b"), result.candidateCanonicalIds)
    }

    @Test
    fun duplicateNormalizedAddressAcrossCanonicalRecordsIsAmbiguousConflict() {
        val incoming = identity(address = "123 Main Street", city = "Austin", state = "TX", postalCode = "78701")
        val candidates = listOf(
            canonical(id = "property-a", address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701"),
            canonical(id = "property-b", address = "123 Main St.", city = "Austin", state = "TX", postalCode = "78701")
        )

        val result = engine.deduplicate(incoming, candidates)

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertTrue(result.candidateCanonicalIds.containsAll(listOf("property-a", "property-b")))
    }

    @Test
    fun coordinateMatchToMultipleCanonicalPropertiesIsAmbiguousConflict() {
        val incoming = identity(latitude = 30.0, longitude = -97.0)
        val candidates = listOf(
            canonical(id = "geo-a", latitude = 30.0, longitude = -97.0),
            canonical(id = "geo-b", latitude = 30.0, longitude = -97.0)
        )

        val result = engine.deduplicate(incoming, candidates)

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertEquals(listOf("geo-a", "geo-b"), result.candidateCanonicalIds)
    }

    @Test
    fun normalizedAddressHasPriorityOverCoordinatesAndProviderListingId() {
        val incoming = identity(
            source = "Zillow", providerListingId = "z-3", address = "123 Main St", city = "Austin", state = "TX",
            postalCode = "78701", latitude = 30.0, longitude = -97.0
        )
        val candidate = canonical(
            id = "same-property", address = "123 Main Street", city = "Austin", state = "TX", postalCode = "78701",
            latitude = 30.0, longitude = -97.0,
            sourceIdentities = setOf(identity(source = "Zillow", providerListingId = "z-3"))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
    }

    @Test
    fun coordinatesHavePriorityOverProviderListingId() {
        val incoming = identity(source = "Redfin", providerListingId = "r-4", latitude = 30.0, longitude = -97.0)
        val candidate = canonical(
            id = "same-property", latitude = 30.0, longitude = -97.0,
            sourceIdentities = setOf(identity(source = "Redfin", providerListingId = "r-4"))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.COORDINATES, result.matchMethod)
    }

    @Test
    fun providerIdTakesPriorityOverFuzzyAddressEvidence() {
        val incoming = identity(
            source = "Zillow", providerListingId = "z-8", address = "123 Mane St", city = "Austin", state = "TX"
        )
        val candidate = canonical(
            id = "zillow-home",
            sourceIdentities = setOf(
                identity(source = "Zillow", providerListingId = "z-8", address = "123 Main St", city = "Austin", state = "TX")
            )
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.PROVIDER_LISTING_ID, result.matchMethod)
    }

    @Test
    fun fuzzyTypoReturnsPossibleMatchAndNeverAutoMerges() {
        val incoming = identity(address = "123 Mane St", city = "Austin", state = "TX", postalCode = "78701")
        val candidate = canonical(id = "main-home", address = "123 Main Street", city = "Austin", state = "TX", postalCode = "78701")

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.POSSIBLE_MATCH, result.status)
        assertEquals(IdentityMatchMethod.FUZZY_ADDRESS, result.matchMethod)
        assertNull(result.canonicalIdentity)
        assertEquals(listOf("main-home"), result.candidateCanonicalIds)
        assertTrue(result.reason.contains("similarity", ignoreCase = true))
    }

    @Test
    fun fuzzyMatchingDifferentHouseNumbersIsDisallowed() {
        val incoming = identity(address = "124 Main St", city = "Austin", state = "TX", postalCode = "78701")
        val candidate = canonical(id = "main-home", address = "123 Main Street", city = "Austin", state = "TX", postalCode = "78701")

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun fuzzyMatchingAcrossDifferentLocalitiesIsDisallowed() {
        val incoming = identity(address = "123 Mane St", city = "Austin", state = "TX", postalCode = "78701")
        val candidate = canonical(id = "other-market", address = "123 Main St", city = "Austin", state = "TX", postalCode = "78702")

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun fuzzySimilarityCanReturnSeveralReviewCandidatesWithoutChoosingOne() {
        val incoming = identity(address = "123 Mane St", city = "Austin", state = "TX", postalCode = "78701")
        val candidates = listOf(
            canonical(id = "main-a", address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701"),
            canonical(id = "main-b", address = "123 Maine St", city = "Austin", state = "TX", postalCode = "78701")
        )

        val result = engine.deduplicate(incoming, candidates)

        assertEquals(DeduplicationStatus.POSSIBLE_MATCH, result.status)
        assertEquals(IdentityMatchMethod.FUZZY_ADDRESS, result.matchMethod)
        assertEquals(listOf("main-a", "main-b"), result.candidateCanonicalIds)
    }

    @Test
    fun exactAddressWithSlightlyDifferentCoordinatesStillMatchesByAddress() {
        val incoming = identity(address = "123 Main St", city = "Austin", state = "TX", latitude = 30.0, longitude = -97.0)
        val candidate = canonical(
            id = "main-home", address = "123 Main Street", city = "Austin", state = "TX",
            latitude = 30.0002, longitude = -97.0
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.MATCHED, result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
    }

    @Test
    fun aLargeCoordinateDiscrepancyAlongsideExactApnIsConflict() {
        val incoming = identity(parcelId = "parcel-123", latitude = 30.0, longitude = -97.0)
        val candidate = canonical(id = "parcel-home", parcelId = "parcel-123", latitude = 35.0, longitude = -100.0)

        assertEquals(DeduplicationStatus.CONFLICT, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun canonicalSourceAliasesCanMatchTheirProviderListingIds() {
        val incoming = identity(source = "Redfin", providerListingId = "r-55")
        val candidate = canonical(
            id = "canonical-property",
            sourceIdentities = setOf(
                identity(source = "Zillow", providerListingId = "z-99"),
                identity(source = "Redfin", providerListingId = "r-55")
            )
        )

        assertEquals(DeduplicationStatus.MATCHED, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun emptyIdentityReturnsNewAndExplainsWhy() {
        val result = engine.deduplicate(identity(), listOf(canonical(id = "incomplete")))

        assertEquals(DeduplicationStatus.NEW, result.status)
        assertEquals(IdentityMatchMethod.NONE, result.matchMethod)
        assertNotNull(result.reason)
        assertTrue(result.reason.contains("No APN/parcel ID"))
        assertNull(result.matchedCanonicalId)
    }

    @Test
    fun emptyCanonicalCatalogReturnsNew() {
        val result = engine.deduplicate(identity(parcelId = "apn-1"), emptyList())

        assertEquals(DeduplicationStatus.NEW, result.status)
        assertTrue(result.candidateCanonicalIds.isEmpty())
    }

    @Test
    fun resultReasonsArePresentForMatchedPossibleAndConflictStates() {
        val matched = engine.deduplicate(
            identity(parcelId = "parcel-1"), listOf(canonical(id = "one", parcelId = "parcel-1"))
        )
        val possible = engine.deduplicate(
            identity(address = "123 Mane St", city = "Austin", state = "TX"),
            listOf(canonical(id = "main", address = "123 Main St", city = "Austin", state = "TX"))
        )
        val conflict = engine.deduplicate(
            identity(parcelId = "parcel-1", address = "123 Main St", city = "Austin", state = "TX"),
            listOf(
                canonical(id = "parcel", parcelId = "parcel-1", address = "999 Oak Rd", city = "Austin", state = "TX"),
                canonical(id = "address", parcelId = "parcel-2", address = "123 Main St", city = "Austin", state = "TX")
            )
        )

        listOf(matched, possible, conflict).forEach { assertTrue(it.reason.isNotBlank()) }
        assertEquals(DeduplicationStatus.MATCHED, matched.status)
        assertEquals(DeduplicationStatus.POSSIBLE_MATCH, possible.status)
        assertEquals(DeduplicationStatus.CONFLICT, conflict.status)
    }

    @Test
    fun candidateResultsAreDeterministicRegardlessOfInputOrder() {
        val incoming = identity(parcelId = "same-apn")
        val first = canonical(id = "z-property", parcelId = "same-apn")
        val second = canonical(id = "a-property", parcelId = "same-apn")

        val forward = engine.deduplicate(incoming, listOf(first, second))
        val reversed = engine.deduplicate(incoming, listOf(second, first))

        assertEquals(forward.status, reversed.status)
        assertEquals(forward.candidateCanonicalIds, reversed.candidateCanonicalIds)
        assertEquals(listOf("a-property", "z-property"), forward.candidateCanonicalIds)
    }

    @Test
    fun invalidDeduplicationThresholdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DeduplicationConfig(coordinateMatchRadiusMeters = 100.0, coordinatePossibleMatchRadiusMeters = 50.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeduplicationConfig(fuzzyAddressThreshold = 1.2)
        }
    }

    @Test
    fun resultConfidenceReflectsResolutionStrength() {
        val apn = engine.deduplicate(identity(parcelId = "parcel-1"), listOf(canonical(id = "apn", parcelId = "parcel-1")))
        val address = engine.deduplicate(
            identity(address = "123 Main St", city = "Austin", state = "TX"),
            listOf(canonical(id = "address", address = "123 Main Street", city = "Austin", state = "TX"))
        )
        val provider = engine.deduplicate(
            identity(source = "Zillow", providerListingId = "z-1"),
            listOf(canonical(id = "provider", sourceIdentities = setOf(identity(source = "Zillow", providerListingId = "z-1"))))
        )

        assertTrue(apn.confidence > address.confidence)
        assertTrue(address.confidence > provider.confidence)
        assertEquals(0.0, engine.deduplicate(identity(), emptyList()).confidence, 0.0)
        assertNotEquals(DeduplicationStatus.CONFLICT, apn.status)
    }

    private fun identity(
        source: String = "Zillow",
        providerListingId: String? = null,
        parcelId: String? = null,
        address: String? = null,
        city: String? = null,
        state: String? = null,
        postalCode: String? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ) = SourcePropertyIdentity(
        source = source,
        providerListingId = providerListingId,
        parcelId = parcelId,
        address = address,
        city = city,
        state = state,
        postalCode = postalCode,
        latitude = latitude,
        longitude = longitude
    )

    private fun canonical(
        id: String,
        parcelId: String? = null,
        address: String? = null,
        city: String? = null,
        state: String? = null,
        postalCode: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        sourceIdentities: Set<SourcePropertyIdentity> = emptySet()
    ) = CanonicalPropertyIdentity(
        canonicalId = id,
        parcelId = parcelId,
        address = address,
        city = city,
        state = state,
        postalCode = postalCode,
        latitude = latitude,
        longitude = longitude,
        sourceIdentities = sourceIdentities
    )
}
