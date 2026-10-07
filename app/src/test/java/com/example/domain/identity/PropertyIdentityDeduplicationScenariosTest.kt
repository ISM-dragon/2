package com.example.domain.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario checklist for the property identity layer: exact matches, partial matches, conflicting
 * listings, address formatting changes, duplicate URLs, multiple providers, missing identifiers,
 * idempotent repeated imports, and deterministic behavior across repeated runs.
 */
class PropertyIdentityDeduplicationScenariosTest {

    private val engine = PropertyIdentityDeduplicationEngine()

    // ── exact matches ───────────────────────────────────────────────────────────────────────────

    @Test
    fun reimportingTheSameRecordYieldsTheSameExactMatchEveryTime() {
        val incoming = identity(
            source = "Zillow",
            providerListingId = "z-100",
            parcelId = "11-22",
            address = "123 Main St",
            city = "Austin",
            state = "TX",
            postalCode = "78701",
            mlsId = "A1050837"
        )
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "home-1",
            parcelId = "1122",
            address = "123 Main Street",
            city = "Austin",
            state = "TX",
            postalCode = "78701",
            mlsId = "A1050837",
            sourceIdentities = setOf(incoming)
        )

        val results = (1..10).map { engine.deduplicate(incoming, listOf(canonical)) }

        assertTrue(results.all { it.status == DeduplicationStatus.EXACT_MATCH })
        assertTrue(results.all { it.autoMergeAllowed })
        assertTrue("repeated runs must be byte-for-byte identical", results.all { it == results.first() })
        assertEquals("home-1", results.first().matchedCanonicalId)
    }

    @Test
    fun exactSourceRecordMatchSurvivesAddressReformattingOnTheSameProvider() {
        val original = identity(
            source = "Redfin",
            providerListingId = "r-7",
            address = "1900 North Bayshore Drive Apt. 1402",
            city = "Miami",
            state = "Florida",
            postalCode = "33132"
        )
        val reformatted = identity(
            source = "Redfin",
            providerListingId = "r-7",
            address = "1900 N Bayshore Dr #1402",
            city = "MIAMI",
            state = "FL",
            postalCode = "33132-2204"
        )
        val canonical = CanonicalPropertyIdentity(canonicalId = "condo-1402", sourceIdentities = setOf(original))

        val result = engine.deduplicate(reformatted, listOf(canonical))

        assertEquals(DeduplicationStatus.EXACT_MATCH, result.status)
        assertEquals(IdentityMatchMethod.SOURCE_LISTING_ID, result.matchMethod)
    }

    // ── partial matches ─────────────────────────────────────────────────────────────────────────

    @Test
    fun partialEvidenceProducesReviewOnlyCandidatesNeverMerges() {
        // House number missing: the address cannot identify the property exactly, but the weak
        // similarity is still surfaced for review instead of silently merging or silently losing it.
        val incoming = identity(address = "123 Mane St", city = "Austin", state = "TX", postalCode = "78701")
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "home-1",
            address = "123 Main Street",
            city = "Austin",
            state = "TX",
            postalCode = "78701"
        )

        val result = engine.deduplicate(incoming, listOf(canonical))

        assertEquals(DeduplicationStatus.POSSIBLE_MATCH, result.status)
        assertEquals(listOf("home-1"), result.candidateCanonicalIds)
        assertFalse(result.autoMergeAllowed)
        assertTrue(result.requiresReview)
        assertNull(result.canonicalIdentity)
    }

    @Test
    fun addressWithoutAHouseNumberIsNotAMatchEvenWithCityStateAndZip() {
        val incoming = identity(address = "Congress Ave", city = "Austin", state = "TX", postalCode = "78704")
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "congress-500",
            address = "500 Congress Avenue",
            city = "Austin",
            state = "TX",
            postalCode = "78704"
        )

        val result = engine.deduplicate(incoming, listOf(canonical))

        assertEquals(DeduplicationStatus.NEW, result.status)
    }

    @Test
    fun nearbyCoordinatesAreReviewOnlyAndNeverMerged() {
        val incoming = identity(latitude = 30.2672, longitude = -97.7431)
        val canonical = CanonicalPropertyIdentity(canonicalId = "nearby", latitude = 30.26765, longitude = -97.7431)

        val result = engine.deduplicate(incoming, listOf(canonical))

        assertEquals(DeduplicationStatus.POSSIBLE_MATCH, result.status)
        assertEquals(IdentityMatchMethod.COORDINATES, result.matchMethod)
        assertFalse(result.autoMergeAllowed)
    }

    // ── conflicting listings ────────────────────────────────────────────────────────────────────

    @Test
    fun sameListingIdClaimingTwoDifferentAddressesIsAConflict() {
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "zillow-home",
            sourceIdentities = setOf(
                identity(source = "Zillow", providerListingId = "z-1", address = "123 Main St", city = "Austin", state = "TX")
            )
        )
        val contradictory = identity(
            source = "Zillow", providerListingId = "z-1", address = "999 Oak Rd", city = "Austin", state = "TX"
        )

        val result = engine.deduplicate(contradictory, listOf(canonical))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertEquals(listOf("zillow-home"), result.candidateCanonicalIds)
        assertFalse(result.autoMergeAllowed)
        assertTrue(result.requiresReview)
    }

    @Test
    fun apnAndMlsDisagreeingAboutThePropertyIsAConflict() {
        val incoming = identity(parcelId = "parcel-a", mlsId = "A1050837")
        val byParcel = CanonicalPropertyIdentity(canonicalId = "parcel-home", parcelId = "parcel-a")
        val byMls = CanonicalPropertyIdentity(canonicalId = "mls-home", mlsId = "A1050837")

        val result = engine.deduplicate(incoming, listOf(byParcel, byMls))

        assertEquals(DeduplicationStatus.CONFLICT, result.status)
        assertEquals(listOf("mls-home", "parcel-home"), result.candidateCanonicalIds)
    }

    @Test
    fun aConflictingKnownParcelIdDisqualifiesWeakEvidence() {
        val incoming = identity(
            parcelId = "parcel-2",
            address = "123 Mane St",
            city = "Austin",
            state = "TX",
            latitude = 30.0,
            longitude = -97.0
        )
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "other-parcel",
            parcelId = "parcel-1",
            address = "123 Main St",
            city = "Austin",
            state = "TX",
            // ~110 m away: inside the review radius but outside the exact-match radius, so any
            // match would have to come from the (parcel-conflicting) weak evidence.
            latitude = 30.001,
            longitude = -97.0
        )

        val result = engine.deduplicate(incoming, listOf(canonical))

        assertEquals(DeduplicationStatus.NEW, result.status)
        assertTrue(result.candidateCanonicalIds.isEmpty())
    }

    // ── address formatting changes ──────────────────────────────────────────────────────────────

    @Test
    fun formattingVariantsOfOneAddressMatchAcrossProviders() {
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "home-1",
            address = "2418 South Congress Avenue",
            city = "AUSTIN",
            state = "Texas",
            postalCode = "78704"
        )
        val variants = listOf(
            identity(address = "2418 s. congress ave.", city = "Austin", state = "TX", postalCode = "78704"),
            identity(address = "2418 South Congress Ave", city = "austin", state = "tx", postalCode = "78704-1122"),
            identity(address = "2418 S Congress Avenue, Austin, TX 78704", city = "Austin", state = "TX", postalCode = "78704")
        )

        variants.forEach { variant ->
            val result = engine.deduplicate(variant, listOf(canonical))
            assertEquals("variant '${variant.address}' must match", DeduplicationStatus.CANONICAL_MATCH, result.status)
            assertEquals(IdentityMatchMethod.NORMALIZED_ADDRESS, result.matchMethod)
        }
    }

    @Test
    fun streetNamesThatLookLikeStateNamesAreNotMangled() {
        // Before this rule, "Maine" was folded to the state code "me": two different streets could
        // collide. They must stay distinct identities.
        val maineStreet = identity(address = "123 Maine St", city = "Portland", state = "ME", postalCode = "04101")
        val meStreet = CanonicalPropertyIdentity(
            canonicalId = "abbrev-home", address = "123 Me St", city = "Portland", state = "ME", postalCode = "04101"
        )
        val washington = CanonicalPropertyIdentity(
            canonicalId = "washington-home", address = "123 Washington Avenue", city = "Portland", state = "ME", postalCode = "04101"
        )

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(maineStreet, listOf(meStreet)).status)
        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(maineStreet, listOf(washington)).status)

        // ...while the real thing still matches through ordinary suffix normalization.
        val washingtonVariant = identity(address = "123 Washington Ave", city = "Portland", state = "ME", postalCode = "04101")
        assertEquals(
            DeduplicationStatus.CANONICAL_MATCH,
            engine.deduplicate(washingtonVariant, listOf(washington)).status
        )
    }

    @Test
    fun unitDesignatorVariantsStayConsistentAndUnitsAreNeverConflated() {
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "unit-4b",
            address = "10 Elm Street Unit 4B",
            city = "Austin",
            state = "TX",
            postalCode = "78701"
        )

        assertEquals(
            DeduplicationStatus.CANONICAL_MATCH,
            engine.deduplicate(
                identity(address = "10 Elm St #4b", city = "Austin", state = "TX", postalCode = "78701"),
                listOf(canonical)
            ).status
        )
        assertEquals(
            DeduplicationStatus.NEW,
            engine.deduplicate(
                identity(address = "10 Elm St Apt 4C", city = "Austin", state = "TX", postalCode = "78701"),
                listOf(canonical)
            ).status
        )
    }

    // ── duplicate URLs ──────────────────────────────────────────────────────────────────────────

    @Test
    fun urlVariantsThatAddressTheSameListingPageAreOneRecord() {
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "listing-1",
            sourceIdentities = setOf(
                identity(
                    source = "Zillow",
                    sourceUrl = "https://www.zillow.com/homedetails/123-Main-St-Austin-TX-78701/99999_zpid/"
                )
            )
        )
        val variants = listOf(
            "http://zillow.com/homedetails/123-Main-St-Austin-TX-78701/99999_zpid",
            "HTTPS://WWW.ZILLOW.COM/homedetails/123-main-st-austin-tx-78701/99999_zpid?utm_campaign=digest#gallery",
            "https://zillow.com:443/homedetails/123-Main-St-Austin-TX-78701/99999_zpid?fbclid=zc9"
        )

        variants.forEach { url ->
            val result = engine.deduplicate(identity(source = "Zillow", sourceUrl = url), listOf(canonical))
            assertEquals("url '$url' must resolve to the same record", DeduplicationStatus.EXACT_MATCH, result.status)
            assertEquals(IdentityMatchMethod.SOURCE_URL, result.matchMethod)
        }
    }

    @Test
    fun urlsWithDifferentListingPathsStayDistinct() {
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "listing-1",
            sourceIdentities = setOf(identity(source = "Zillow", sourceUrl = "https://www.zillow.com/homedetails/99999_zpid"))
        )
        val differentListing = identity(source = "Zillow", sourceUrl = "https://www.zillow.com/homedetails/88888_zpid")
        // Same host, same shape, different listing: the tracking-parameter cleanup must not
        // collapse two different listings.
        val sameQueryDifferentListing = identity(
            source = "Zillow",
            sourceUrl = "https://www.zillow.com/homedetails/99999_zpid?zpid=88888"
        )

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(differentListing, listOf(canonical)).status)
        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(sameQueryDifferentListing, listOf(canonical)).status)
    }

    // ── multiple providers ──────────────────────────────────────────────────────────────────────

    @Test
    fun threeProvidersImportOnePropertyWithoutDuplicates() {
        // Zillow imports first; Redfin matches by APN; ATTOM matches by MLS ID.
        val zillow = identity(
            source = "Zillow", providerListingId = "z-1", parcelId = "11-22", mlsId = "A1050837",
            address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701"
        )
        val catalog = mutableListOf<CanonicalPropertyIdentity>()

        val first = engine.deduplicate(zillow, catalog)
        assertEquals(DeduplicationStatus.NEW, first.status)
        catalog += CanonicalPropertyIdentity(
            canonicalId = "property-1",
            parcelId = "1122",
            mlsId = "A1050837",
            address = "123 Main Street",
            sourceIdentities = setOf(zillow)
        )

        val redfin = identity(source = "Redfin", providerListingId = "r-9", parcelId = "APN: 1122")
        val second = engine.deduplicate(redfin, catalog)
        assertEquals(DeduplicationStatus.CANONICAL_MATCH, second.status)
        assertEquals(IdentityMatchMethod.APN, second.matchMethod)

        val attom = identity(source = "ATTOM", providerListingId = "a-3", mlsId = "MLS# a10-50837")
        val third = engine.deduplicate(attom, catalog)
        assertEquals(DeduplicationStatus.CANONICAL_MATCH, third.status)
        assertEquals(IdentityMatchMethod.MLS_ID, third.matchMethod)

        assertEquals("property-1", second.matchedCanonicalId)
        assertEquals("property-1", third.matchedCanonicalId)
    }

    @Test
    fun sameListingIdFromTwoProvidersIsNotACrossProviderMatch() {
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "zillow-home",
            sourceIdentities = setOf(identity(source = "Zillow", providerListingId = "12345"))
        )
        val redfin = identity(source = "Redfin", providerListingId = "12345")

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(redfin, listOf(canonical)).status)
    }

    // ── missing identifiers ─────────────────────────────────────────────────────────────────────

    @Test
    fun placeholderIdentifiersAreTreatedAsMissing() {
        val incoming = identity(
            providerListingId = "  ",
            parcelId = "N/A",
            mlsId = "unknown",
            sourceUrl = "null",
            address = "...",
            city = "",
            state = "",
            postalCode = ""
        )
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "home-1",
            address = "123 Main St",
            city = "Austin",
            state = "TX",
            postalCode = "78701"
        )

        val result = engine.deduplicate(incoming, listOf(canonical))

        assertEquals(DeduplicationStatus.NEW, result.status)
        assertEquals(IdentityMatchMethod.NONE, result.matchMethod)
        assertTrue(result.evidence.isNotEmpty())
    }

    @Test
    fun aSourceListingIdWithoutASourceNameCannotMatchAnything() {
        val incoming = identity(source = "", providerListingId = "z-100")
        val canonical = CanonicalPropertyIdentity(
            canonicalId = "home-1",
            sourceIdentities = setOf(identity(source = "Zillow", providerListingId = "z-100"))
        )

        assertEquals(DeduplicationStatus.NEW, engine.deduplicate(incoming, listOf(canonical)).status)
    }

    @Test
    fun everyResultExplainsItselfEvenWhenNothingMatched() {
        val incoming = identity(address = "123 Main St", city = "Austin", state = "TX")
        val canonical = CanonicalPropertyIdentity(canonicalId = "far-away", latitude = 40.0, longitude = -80.0)

        val result = engine.deduplicate(incoming, listOf(canonical))

        assertEquals(DeduplicationStatus.NEW, result.status)
        assertTrue(result.reason.isNotBlank())
        assertTrue(result.evidence.all { it.detail.isNotBlank() })
        assertTrue(result.evidence.any { it.signal == IdentityMatchMethod.NORMALIZED_ADDRESS })
    }

    // ── determinism across repeated runs ────────────────────────────────────────────────────────

    @Test
    fun repeatedRunsOverShuffledCatalogsAreIdentical() {
        val incoming = identity(
            source = "Redfin", providerListingId = "r-42", parcelId = "11-22",
            address = "123 Main St", city = "Austin", state = "TX", postalCode = "78701",
            latitude = 30.0, longitude = -97.0
        )
        val catalog = listOf(
            CanonicalPropertyIdentity(canonicalId = "a-home", address = "123 Main Street", city = "Austin", state = "TX", postalCode = "78701"),
            CanonicalPropertyIdentity(canonicalId = "b-home", parcelId = "11-22"),
            CanonicalPropertyIdentity(canonicalId = "c-home", latitude = 30.0005, longitude = -97.0),
            CanonicalPropertyIdentity(canonicalId = "d-home", address = "777 Oak Rd", city = "Austin", state = "TX")
        )

        val baseline = engine.deduplicate(incoming, catalog)
        val variants = (1..40).map { seed -> engine.deduplicate(incoming, catalog.shuffled(java.util.Random(seed.toLong()))) }

        assertTrue(variants.all { it == baseline })
        assertEquals(DeduplicationStatus.CONFLICT, baseline.status)
        assertEquals(listOf("a-home", "b-home"), baseline.candidateCanonicalIds)
    }

    @Test
    fun engineInstanceIsStatelessAcrossRuns() {
        val engine = PropertyIdentityDeduplicationEngine()
        val incoming = identity(parcelId = "parcel-1")
        val catalog = listOf(CanonicalPropertyIdentity(canonicalId = "home-1", parcelId = "parcel-1"))

        val first = engine.deduplicate(incoming, catalog)
        engine.deduplicate(identity(address = "999 Oak Rd", city = "Austin", state = "TX"), catalog)
        engine.deduplicate(identity(), emptyList())
        val second = engine.deduplicate(incoming, catalog)

        assertEquals(first, second)
        assertEquals(DeduplicationStatus.CANONICAL_MATCH, first.status)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

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
}
