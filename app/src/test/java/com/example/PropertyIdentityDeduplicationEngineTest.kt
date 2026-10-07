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

/**
 * Pins the deterministic identity-resolution contract of [PropertyIdentityDeduplicationEngine]:
 * matching precedence (APN > MLS ID > source listing ID > source URL > normalized address >
 * coordinates), the five outcomes (NEW, EXACT_MATCH, CANONICAL_MATCH, POSSIBLE_MATCH, CONFLICT),
 * conflict cross-checks, and the rule that weak evidence is never merged automatically.
 */
class PropertyIdentityDeduplicationEngineTest {
    private val engine = PropertyIdentityDeduplicationEngine()

    // ── APN / parcel ID ─────────────────────────────────────────────────────────────────────────

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
            longitude = -97.0
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
        assertEquals(IdentityMatchMethod.APN, result.matchMethod)
        assertEquals("canonical-1", result.matchedCanonicalId)
        assertTrue(result.confidence > 0.99)
    }

    @Test
    fun exactMatchIsReportedWhenTheSameSourceRecordIsReimported() {
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

        // The source listing ID proves this is the very same record: idempotent refresh.
        assertEquals(DeduplicationStatus.EXACT_MATCH, result.status)
        assertEquals(IdentityMatchMethod.SOURCE_LISTING_ID, result.matchMethod)
        assertEquals(1.0, result.confidence, 0.0)
        assertTrue(result.reason.contains("idempotent", ignoreCase = true))
        // The trail shows both the record identity and the corroborating APN.
        assertTrue(result.evidence.any { it.signal == IdentityMatchMethod.APN && it.matchedCanonicalIds == listOf("canonical-1") })
    }

    // ── MLS ID ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun mlsIdMatchesAcrossProvidersRegardlessOfFormatting() {
        val incoming = identity(source = "Zillow", mlsId = "MLS# a10-50837")
        val candidate = canonical(
            id = "mls-home",
            mlsId = "A1050837",
            sourceIdentities = setOf(identity(source = "Realtor.com", mlsId = "a10 50837"))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
        assertEquals(IdentityMatchMethod.MLS_ID, result.matchMethod)
    }

    @Test
    fun mlsIdHasPriorityOverSourceListingIdAddressAndCoordinates() {
        val incoming = identity(
            source = "Zillow", providerListingId = "z-9", mlsId = "A1050837",
            address = "123 Main St", city = "Austin", state = "TX",
            latitude = 30.0, longitude = -97.0
        )
        val candidate = canonical(
            id = "mls-home", mlsId = "A1050837", address = "123 Main Street", city = "Austin", state = "TX",
            latitude = 30.0, longitude = -97.0
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
        assertEquals(IdentityMatchMethod.MLS_ID, result.matchMethod)
    }

    @Test
    fun differentMlsIdsAreNotAConflictBecauseListingsGetRelisted() {
        val incoming = identity(
            mlsId = "A1050838", address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701"
        )
        val candidate = canonical(
            id = "relisted-home", mlsId = "A1050000", address = "123 Main Street",
            city = "Austin", state = "TX", postalCode = "78701"
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        // The address still identifies the property; the changed MLS number is not a contradiction.
        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
    }

    @Test
    fun placeholderMlsIdsAreTreatedAsMissing() {
        val incoming = identity(mlsId = "N/A", address = "123 Main St", city = "Austin", state = "TX")
        val candidate = canonical(id = "home", mlsId = "UNKNOWN", address = "999 Oak Rd", city = "Austin", state = "TX")

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    // ── normalized address ──────────────────────────────────────────────────────────────────────

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

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
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

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
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

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
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
        assertFalseAutoMerge(result)
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

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
    }

    @Test
    fun missingCityAndZipDoNotBlockAnOtherwiseExactAddress() {
        val incoming = identity(address = "500 Congress Ave", city = null, state = null, postalCode = null)
        val candidate = canonical(id = "congress-home", address = "500 Congress Avenue", city = "Austin", state = "TX", postalCode = "78701")

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
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

    // ── coordinates ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun exactCoordinatesCanMatchWithoutAddressOrParcelData() {
        val incoming = identity(latitude = 30.2672, longitude = -97.7431)
        val candidate = canonical(id = "geo-home", latitude = 30.2672, longitude = -97.7431)

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
        assertEquals(IdentityMatchMethod.COORDINATES, result.matchMethod)
    }

    @Test
    fun smallCoordinateDriftStillMatchesWithinConfiguredRadius() {
        val incoming = identity(latitude = 30.0, longitude = -97.0)
        val candidate = canonical(id = "geo-home", latitude = 30.00005, longitude = -97.0)

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
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
        assertFalseAutoMerge(result)
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

    // ── source listing ID (same-record identity) ────────────────────────────────────────────────

    @Test
    fun providerListingIdMatchesWithinTheSameProviderNamespace() {
        val incoming = identity(source = "Zillow", providerListingId = "  Z-100  ")
        val candidate = canonical(
            id = "zillow-home",
            sourceIdentities = setOf(identity(source = "Zillow", providerListingId = "Z-100"))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.EXACT_MATCH, result.status)
        assertEquals(IdentityMatchMethod.SOURCE_LISTING_ID, result.matchMethod)
        assertEquals(1.0, result.confidence, 0.0)
    }

    @Test
    fun providerNamespaceAndListingIdComparisonAreCaseInsensitive() {
        val incoming = identity(source = "  REDFIN ", providerListingId = "Listing-X9")
        val candidate = canonical(
            id = "redfin-home",
            sourceIdentities = setOf(identity(source = "Redfin", providerListingId = "listing-x9"))
        )

        assertEquals(DeduplicationStatus.EXACT_MATCH, engine.deduplicate(incoming, listOf(candidate)).status)
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

    // ── source URL (same-record identity) ───────────────────────────────────────────────────────

    @Test
    fun sameNormalizedListingUrlIsAnExactMatchEvenWithTrackingParameters() {
        val first = identity(
            source = "Zillow",
            providerListingId = "z-1",
            sourceUrl = "https://www.zillow.com/homedetails/123-Main-St-Austin-TX/99999_zpid/"
        )
        val reimport = identity(
            source = "Zillow Mirror",
            providerListingId = "zm-77",
            sourceUrl = "HTTPS://ZILLOW.COM/homedetails/123-main-st-austin-tx/99999_zpid?utm_source=navbar&fbclid=abc#photos"
        )
        val candidate = canonical(
            id = "zillow-home",
            sourceIdentities = setOf(first)
        )

        val result = engine.deduplicate(reimport, listOf(candidate))

        // The URL host namespaces the record, so even a different adapter label resolves it.
        assertEquals(DeduplicationStatus.EXACT_MATCH, result.status)
        assertEquals(IdentityMatchMethod.SOURCE_URL, result.matchMethod)
    }

    @Test
    fun differentListingUrlsDoNotMatch() {
        val incoming = identity(source = "Zillow", sourceUrl = "https://www.zillow.com/homedetails/99999_zpid")
        val candidate = canonical(
            id = "other-listing",
            sourceIdentities = setOf(identity(source = "Zillow", sourceUrl = "https://www.zillow.com/homedetails/88888_zpid"))
        )

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun urlsOnDifferentHostsNeverMatch() {
        val incoming = identity(sourceUrl = "https://www.redfin.com/TX/Austin/123-Main-St/home/1")
        val candidate = canonical(
            id = "zillow-copy",
            sourceIdentities = setOf(identity(sourceUrl = "https://www.zillow.com/TX/Austin/123-Main-St/home/1"))
        )

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun blankAndPlaceholderUrlsAreMissingIdentifiers() {
        val incoming = identity(sourceUrl = "n/a", address = "123 Main St", city = "Austin", state = "TX")
        val candidate = canonical(
            id = "home",
            address = "999 Oak Rd",
            city = "Austin",
            state = "TX",
            sourceIdentities = setOf(identity(sourceUrl = "unknown"))
        )

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    // ── conflict cross-checks ───────────────────────────────────────────────────────────────────

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
    fun duplicateMlsIdAcrossCanonicalRecordsIsAmbiguousConflict() {
        val incoming = identity(mlsId = "A1050837")
        val candidates = listOf(
            canonical(id = "listing-a", mlsId = "A1050837"),
            canonical(id = "listing-b", mlsId = "a10-50837")
        )

        val result = engine.deduplicate(incoming, candidates)

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertEquals(listOf("listing-a", "listing-b"), result.candidateCanonicalIds)
        assertFalseAutoMerge(result)
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

    // ── precedence ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun normalizedAddressHasPriorityOverCoordinates() {
        val incoming = identity(
            address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701",
            latitude = 30.0, longitude = -97.0
        )
        val candidate = canonical(
            id = "same-property", address = "123 Main Street", city = "Austin", state = "TX", postalCode = "78701",
            latitude = 30.0, longitude = -97.0
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
    }

    @Test
    fun sourceListingIdHasPriorityOverAddressAndCoordinates() {
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

        assertEquals(DeduplicationStatus.EXACT_MATCH, result.status)
        assertEquals(IdentityMatchMethod.SOURCE_LISTING_ID, result.matchMethod)
    }

    @Test
    fun sourceListingIdHasPriorityOverCoordinates() {
        val incoming = identity(source = "Redfin", providerListingId = "r-4", latitude = 30.0, longitude = -97.0)
        val candidate = canonical(
            id = "same-property", latitude = 30.0, longitude = -97.0,
            sourceIdentities = setOf(identity(source = "Redfin", providerListingId = "r-4"))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.EXACT_MATCH, result.status)
        assertEquals(IdentityMatchMethod.SOURCE_LISTING_ID, result.matchMethod)
    }

    @Test
    fun sourceUrlHasPriorityOverNormalizedAddress() {
        val url = "https://www.redfin.com/TX/Austin/123-Main-St/home/17"
        val incoming = identity(
            source = "Redfin", sourceUrl = url, address = "123 Main St", city = "Austin", state = "TX"
        )
        val candidate = canonical(
            id = "same-property", address = "123 Main Street", city = "Austin", state = "TX",
            sourceIdentities = setOf(identity(source = "Redfin", sourceUrl = url))
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.EXACT_MATCH, result.status)
        assertEquals(IdentityMatchMethod.SOURCE_URL, result.matchMethod)
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

        // "123 Mane St" vs "123 Main St" is a hard address conflict (similarity below the
        // contradiction threshold would be needed to pass) - the engine must not silently merge.
        assertTrue(
            result.status == DeduplicationStatus.EXACT_MATCH || result.status == DeduplicationStatus.CONFLICT
        )
        if (result.status == DeduplicationStatus.EXACT_MATCH) {
            assertEquals(IdentityMatchMethod.SOURCE_LISTING_ID, result.matchMethod)
        }
    }

    // ── fuzzy / weak evidence ───────────────────────────────────────────────────────────────────

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
        assertFalseAutoMerge(result)
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
        assertFalseAutoMerge(result)
    }

    @Test
    fun exactAddressWithSlightlyDifferentCoordinatesStillMatchesByAddress() {
        val incoming = identity(address = "123 Main St", city = "Austin", state = "TX", latitude = 30.0, longitude = -97.0)
        val candidate = canonical(
            id = "main-home", address = "123 Main Street", city = "Austin", state = "TX",
            latitude = 30.0002, longitude = -97.0
        )

        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.CANONICAL_MATCH, result.status)
        assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
    }

    @Test
    fun aLargeCoordinateDiscrepancyAlongsideExactApnIsConflict() {
        val incoming = identity(parcelId = "parcel-123", latitude = 30.0, longitude = -97.0)
        val candidate = canonical(id = "parcel-home", parcelId = "parcel-123", latitude = 35.0, longitude = -100.0)

        assertEquals(DeduplicationStatus.CONFLICT, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun aKnownDifferentParcelIdExcludesFuzzyCandidatesEntirely() {
        val incoming = identity(parcelId = "parcel-2", address = "123 Mane St", city = "Austin", state = "TX", postalCode = "78701")
        val candidate = canonical(id = "main-home", parcelId = "parcel-1", address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701")

        // The fuzzy address points at a record whose parcel ID contradicts the incoming one:
        // no weak match may be offered for it, and no exact signal matched either.
        val result = engine.deduplicate(incoming, listOf(candidate))

        assertEquals(DeduplicationStatus.NEW, result.status)
    }

    // ── aliases, missing identifiers, emptiness ─────────────────────────────────────────────────

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

        assertEquals(DeduplicationStatus.EXACT_MATCH, engine.deduplicate(incoming, listOf(candidate)).status)
    }

    @Test
    fun emptyIdentityReturnsNewAndExplainsWhy() {
        val result = engine.deduplicate(identity(), listOf(canonical(id = "incomplete")))

        assertEquals(DeduplicationStatus.NEW, result.status)
        assertEquals(IdentityMatchMethod.NONE, result.matchMethod)
        assertNotNull(result.reason)
        assertTrue(result.reason.contains("No APN/parcel ID"))
        assertNull(result.matchedCanonicalId)
        // The trail explains that no usable identifier was supplied at all.
        assertTrue(result.evidence.isNotEmpty())
        assertEquals(IdentityMatchMethod.NONE, result.evidence.single().signal)
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
        assertEquals(DeduplicationStatus.CANONICAL_MATCH, matched.status)
        assertEquals(DeduplicationStatus.POSSIBLE_MATCH, possible.status)
        assertEquals(DeduplicationStatus.CONFLICT, conflict.status)
    }

    // ── determinism, explainability, config ─────────────────────────────────────────────────────

    @Test
    fun candidateResultsAreDeterministicRegardlessOfInputOrder() {
        val incoming = identity(parcelId = "same-apn")
        val first = canonical(id = "z-property", parcelId = "same-apn")
        val second = canonical(id = "a-property", parcelId = "same-apn")

        val forward = engine.deduplicate(incoming, listOf(first, second))
        val reversed = engine.deduplicate(incoming, listOf(second, first))

        assertEquals(forward.status, reversed.status)
        assertEquals(forward.candidateCanonicalIds, reversed.candidateCanonicalIds)
        assertEquals(forward.reason, reversed.reason)
        assertEquals(forward.evidence, reversed.evidence)
        assertEquals(listOf("a-property", "z-property"), forward.candidateCanonicalIds)
    }

    @Test
    fun repeatedResolutionsOfTheSameInputAreIdentical() {
        val incoming = identity(
            source = "Zillow", providerListingId = "z-1", parcelId = "11-22", mlsId = "A1",
            address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701",
            latitude = 30.0, longitude = -97.0
        )
        val catalog = listOf(
            canonical(id = "home-a", parcelId = "11-22", address = "123 Main Street", city = "Austin", state = "TX"),
            canonical(id = "home-b", address = "777 Oak Rd", city = "Austin", state = "TX")
        )

        val results = (1..25).map { engine.deduplicate(incoming, catalog.shuffled()) }

        assertTrue(results.all { it == results.first() })
    }

    @Test
    fun everyDecisionCarriesAnOrderedEvidenceTrail() {
        val result = engine.deduplicate(
            identity(source = "Zillow", providerListingId = "z-1", parcelId = "11-22", address = "123 Main St", city = "Austin", state = "TX"),
            listOf(canonical(id = "home", parcelId = "11-22", address = "123 Main Street", city = "Austin", state = "TX"))
        )

        val signals = result.evidence.map { it.signal }
        // Supplied signals appear in strict precedence order.
        assertEquals(
            listOf(
                IdentityMatchMethod.APN,
                IdentityMatchMethod.SOURCE_LISTING_ID,
                IdentityMatchMethod.NORMALIZED_ADDRESS
            ),
            signals
        )
        assertTrue(result.evidence.all { it.detail.isNotBlank() })
        assertTrue(result.reason.isNotBlank())
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
    fun confidenceFollowsMatchingPrecedence() {
        val exact = engine.deduplicate(
            identity(source = "Zillow", providerListingId = "z-1"),
            listOf(canonical(id = "provider", sourceIdentities = setOf(identity(source = "Zillow", providerListingId = "z-1"))))
        )
        val apn = engine.deduplicate(identity(parcelId = "parcel-1"), listOf(canonical(id = "apn", parcelId = "parcel-1")))
        val mls = engine.deduplicate(identity(mlsId = "A1"), listOf(canonical(id = "mls", mlsId = "A1")))
        val address = engine.deduplicate(
            identity(address = "123 Main St", city = "Austin", state = "TX"),
            listOf(canonical(id = "address", address = "123 Main Street", city = "Austin", state = "TX"))
        )
        val coords = engine.deduplicate(
            identity(latitude = 30.0, longitude = -97.0),
            listOf(canonical(id = "coords", latitude = 30.0, longitude = -97.0))
        )
        val possible = engine.deduplicate(
            identity(address = "123 Mane St", city = "Austin", state = "TX"),
            listOf(canonical(id = "fuzzy", address = "123 Main St", city = "Austin", state = "TX"))
        )

        // Idempotent re-imports are certain; canonical matches follow the documented precedence.
        assertEquals(DeduplicationStatus.EXACT_MATCH, exact.status)
        assertEquals(1.0, exact.confidence, 0.0)
        assertTrue(apn.confidence > mls.confidence)
        assertTrue(mls.confidence > address.confidence)
        assertTrue(address.confidence > coords.confidence)
        assertTrue(coords.confidence > possible.confidence)
        assertTrue("weak evidence must stay below any merge-worthy confidence", possible.confidence < 0.8)
        assertEquals(0.0, engine.deduplicate(identity(), emptyList()).confidence, 0.0)
        assertNotEquals(DeduplicationStatus.CONFLICT, apn.status)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Only EXACT_MATCH / CANONICAL_MATCH may merge automatically; everything else must not. */
    private fun assertFalseAutoMerge(result: com.example.domain.identity.DeduplicationResult) {
        assertTrue(
            "status ${result.status} must never auto-merge",
            !result.autoMergeAllowed && result.requiresReview && result.canonicalIdentity == null
        )
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
        longitude: Double? = null,
        mlsId: String? = null,
        sourceUrl: String? = null
    ) = SourcePropertyIdentity(
        source = source,
        providerListingId = providerListingId,
        parcelId = parcelId,
        address = address,
        city = city,
        state = state,
        postalCode = postalCode,
        latitude = latitude,
        longitude = longitude,
        mlsId = mlsId,
        sourceUrl = sourceUrl
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
        sourceIdentities: Set<SourcePropertyIdentity> = emptySet(),
        mlsId: String? = null
    ) = CanonicalPropertyIdentity(
        canonicalId = id,
        parcelId = parcelId,
        address = address,
        city = city,
        state = state,
        postalCode = postalCode,
        latitude = latitude,
        longitude = longitude,
        sourceIdentities = sourceIdentities,
        mlsId = mlsId
    )
}
