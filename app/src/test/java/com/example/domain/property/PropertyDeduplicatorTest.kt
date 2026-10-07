package com.example.domain.property

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the deduplication ladder and the field level merge policy. Both are pure functions, so the
 * rules that decide whether two source records describe the same house are pinned here.
 */
class PropertyDeduplicatorTest {

    private fun input(
        id: String? = "prop-1",
        key: String? = null,
        street: String = "2418 S Congress Ave",
        unit: String = "",
        city: String = "austin",
        state: String = "TX",
        zip: String = "78704",
        apn: String = "",
        mls: String = "",
        lat: Double = 30.2415,
        lng: Double = -97.7551,
        beds: Int = 4,
        sqft: Int = 2250,
        type: UsPropertyType = UsPropertyType.MULTI_FAMILY
    ) = DedupInput(
        propertyId = id,
        canonicalKey = key,
        normalizedAddress = street,
        unit = unit,
        city = city,
        stateCode = state,
        zip5 = zip,
        apn = apn,
        mlsNumber = mls,
        latitude = lat,
        longitude = lng,
        bedrooms = beds,
        livingAreaSqFt = sqft,
        propertyType = type
    )

    // ── ladder ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `canonical key match wins over everything else`() {
        val key = "2418 s congress ave|austin|TX|78704"
        val decision = PropertyDeduplicator.decide(
            incoming = input(key = key, mls = "NEW-LISTING"),
            candidates = listOf(input(key = key, mls = "OLD-LISTING"))
        )
        assertEquals(DedupStrategy.CANONICAL_KEY, decision.strategy)
        assertEquals(DedupAction.UPDATE_EXISTING, decision.action)
        assertEquals(1.0, decision.confidence, 0.0)
        assertFalse(decision.claimCanonicalKey)
    }

    @Test
    fun `apn match merges and claims the missing canonical key of a legacy row`() {
        val decision = PropertyDeduplicator.decide(
            incoming = input(key = null, apn = "0412345678", city = "", zip = ""),
            candidates = listOf(input(key = null, apn = "0412345678"))
        )
        assertEquals(DedupStrategy.PARCEL_APN, decision.strategy)
        assertEquals(DedupAction.MERGE_INTO_EXISTING, decision.action)
        assertTrue(decision.claimCanonicalKey)
    }

    @Test
    fun `mls number catches address typos inside one feed`() {
        val decision = PropertyDeduplicator.decide(
            incoming = input(street = "2418 S Congres Ave", mls = "ACT-9931"),
            candidates = listOf(input(street = "2418 S Congress Avenue", mls = "ACT-9931"))
        )
        assertEquals(DedupStrategy.MLS_NUMBER, decision.strategy)
        assertEquals(0.9, decision.confidence, 0.0)
    }

    @Test
    fun `fuzzy match accepts re-spelled addresses with the same house number`() {
        val decision = PropertyDeduplicator.decide(
            incoming = input(street = "2418 S Congress Street"),
            candidates = listOf(input(street = "2418 S Congress Ave"))
        )
        assertEquals(DedupStrategy.FUZZY_ADDRESS, decision.strategy)
        assertTrue(
            "score ${decision.confidence} below threshold",
            decision.confidence >= PropertyDeduplicator.FUZZY_THRESHOLD
        )
    }

    @Test
    fun `different house numbers in the same zip do not match`() {
        val decision = PropertyDeduplicator.decide(
            incoming = input(street = "2500 S Congress Ave", lat = 0.0, lng = 0.0),
            candidates = listOf(input(street = "2418 S Congress Ave", lat = 0.0, lng = 0.0))
        )
        assertEquals(DedupStrategy.NONE, decision.strategy)
        assertEquals(DedupAction.INSERT_NEW, decision.action)
        assertFalse(decision.matched)
    }

    @Test
    fun `different units never collapse into one property`() {
        val unitA = input(unit = "unit 1")
        val unitB = input(unit = "unit 2")
        assertEquals(0.0, PropertyDeduplicator.similarity(unitA, unitB), 0.0)

        val decision = PropertyDeduplicator.decide(incoming = unitA, candidates = listOf(unitB))
        assertEquals(DedupStrategy.NONE, decision.strategy)
    }

    @Test
    fun `similarity grows with geo size and classification agreement`() {
        val close = PropertyDeduplicator.similarity(input(), input())
        val farAway = PropertyDeduplicator.similarity(input(lat = 0.0, lng = 0.0), input(lat = 0.0, lng = 0.0))
        assertTrue(close > farAway)

        val differentType = PropertyDeduplicator.similarity(input(), input(type = UsPropertyType.CONDO))
        assertTrue(close > differentType)
    }

    // ── merge policy ────────────────────────────────────────────────────────────────────────────

    private fun canonical(
        price: Double = 485_000.0,
        sqft: Int = 2250,
        apn: String = "",
        mls: String = ""
    ) = CanonicalProperty(
        propertyId = "prop-1",
        address = UsPropertyNormalizer.address("2418 S Congress Ave", "", "Austin", "TX", "78704"),
        apn = apn,
        mlsNumber = mls,
        livingAreaSqFt = sqft,
        listPrice = price
    )

    @Test
    fun `merge lets the more authoritative source win volatile fields`() {
        val existing = canonical(price = 485_000.0, apn = "0412345678", mls = "MLS-1")
        val incoming = canonical(price = 450_000.0, apn = "9999", mls = "WS-9")

        val outcome = PropertyMergePolicy.merge(
            existing = existing,
            existingSourcePriority = 10, // MLS
            incoming = incoming,
            incomingSourcePriority = 40 // wholesaler
        )

        assertEquals(485_000.0, outcome.merged.listPrice, 0.001)
        assertEquals("0412345678", outcome.merged.apn)
        assertEquals("MLS-1", outcome.merged.mlsNumber)
        assertTrue("apn conflict not reported", "apn" in outcome.conflictingFields)
        assertTrue("mlsNumber conflict not reported", "mlsNumber" in outcome.conflictingFields)
    }

    @Test
    fun `merge fills gaps and reports conflicts when the incoming source is stronger`() {
        val existing = canonical(price = 485_000.0, sqft = 0, apn = "")
        val incoming = canonical(price = 450_000.0, sqft = 2250, apn = "0412345678")

        val outcome = PropertyMergePolicy.merge(
            existing = existing,
            existingSourcePriority = 40,
            incoming = incoming,
            incomingSourcePriority = 10
        )

        assertEquals(450_000.0, outcome.merged.listPrice, 0.001)
        assertEquals(2250, outcome.merged.livingAreaSqFt)
        assertEquals("0412345678", outcome.merged.apn)
        assertTrue("listPrice conflict not reported", "listPrice" in outcome.conflictingFields)
    }
}
