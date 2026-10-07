package com.example.domain.intelligence.dedup

import com.example.data.local.entity.PropertyEntity
import com.example.domain.intelligence.model.CanonicalProperty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardening tests for the URL-intelligence deduplicator.
 *
 * The previous implementation walked the DAO rows in storage order and merged on the first
 * hit - including fuzzy Jaccard address matches and coordinates within 35 m. These tests pin the
 * replacement contract: deterministic precedence, no silent merging of ambiguous evidence, and
 * identical results across runs and row orders.
 */
class PropertyDeduplicatorHardeningTest {

    @Test
    fun exactAddressMatchIsReportedWithTheMatchedProperty() = runBlocking {
        val existing = property("PROP-1", "1420 South Congress Avenue")
        val deduplicator = PropertyDeduplicator(FakePropertyDao(listOf(existing)))

        val match = deduplicator.findExistingMatch(incoming("1420 S Congress Ave, Austin, TX 78704"))

        assertTrue(match.isMatch)
        assertEquals("PROP-1", match.existingProperty?.id)
        assertEquals("EXACT_ADDRESS", match.matchType)
        assertFalse(match.needsReview)
        assertTrue(match.reason.isNotBlank())
    }

    @Test
    fun tightCoordinatesMatchEvenWhenTheAddressFormattingDiffers() = runBlocking {
        val existing = property("PROP-2", "1420 South Congress Avenue", latitude = 30.2501, longitude = -97.7495)
        val deduplicator = PropertyDeduplicator(FakePropertyDao(listOf(existing)))

        val match = deduplicator.findExistingMatch(
            incoming("1420 Congress Ave", latitude = 30.25014, longitude = -97.7495)
        )

        assertTrue(match.isMatch)
        assertEquals("PROP-2", match.existingProperty?.id)
        assertEquals("COORDINATES", match.matchType)
    }

    @Test
    fun fuzzyAddressEvidenceIsReviewOnlyAndNeverSilentlyMerged() = runBlocking {
        val existing = property("PROP-3", "1420 South Congress Avenue", latitude = 30.0, longitude = -97.0)
        val deduplicator = PropertyDeduplicator(FakePropertyDao(listOf(existing)))

        // A single-token typo: similar, but not the same record - the old implementation merged
        // this silently whenever the Jaccard score crossed 0.75.
        val match = deduplicator.findExistingMatch(incoming("1420 S Congres Ave", latitude = 30.5, longitude = -97.5))

        assertFalse("ambiguous fuzzy evidence must never auto-merge", match.isMatch)
        assertNull(match.existingProperty)
        assertTrue(match.needsReview)
        assertEquals("FUZZY_ADDRESS", match.matchType)
        assertTrue(match.reason.contains("review", ignoreCase = true))
    }

    @Test
    fun nearbyCoordinatesAreReviewOnlyAndNeverSilentlyMerged() = runBlocking {
        val existing = property("PROP-4", "100 Oak Street", latitude = 30.2501, longitude = -97.7495)
        val deduplicator = PropertyDeduplicator(FakePropertyDao(listOf(existing)))

        // ~55 m away with no address on the incoming record: inside the "possible" radius,
        // outside the exact-match radius.
        val match = deduplicator.findExistingMatch(
            incoming("", latitude = 30.2506, longitude = -97.7495)
        )

        assertFalse(match.isMatch)
        assertNull(match.existingProperty)
        assertTrue(match.needsReview)
        assertTrue(match.matchType == "COORDINATES" || match.matchType == "FUZZY_ADDRESS")
    }

    @Test
    fun ambiguousDuplicateRowsProduceAConflictInsteadOfPickingOne() = runBlocking {
        val first = property("PROP-5", "1420 South Congress Avenue")
        val second = property("PROP-6", "1420 S Congress Ave")
        val deduplicator = PropertyDeduplicator(FakePropertyDao(listOf(first, second)))

        val match = deduplicator.findExistingMatch(incoming("1420 South Congress Avenue, Austin, TX 78704"))

        assertFalse(match.isMatch)
        assertNull(match.existingProperty)
        assertTrue(match.needsReview)
        assertEquals("CONFLICT", match.matchType)
    }

    @Test
    fun parcelIdMatchesThroughTheSharedIdentityEngine() = runBlocking {
        val existing = property("PROP-7", "").copy(apn = "11-22")
        val deduplicator = PropertyDeduplicator(FakePropertyDao(listOf(existing)))

        val match = deduplicator.findExistingMatch(incoming("", apn = "APN 1122"))

        assertTrue(match.isMatch)
        assertEquals("PROP-7", match.existingProperty?.id)
        assertEquals("APN", match.matchType)
    }

    @Test
    fun emptyCatalogIsNewWithoutReview() = runBlocking {
        val deduplicator = PropertyDeduplicator(FakePropertyDao(emptyList()))

        val match = deduplicator.findExistingMatch(incoming("1420 S Congress Ave"))

        assertFalse(match.isMatch)
        assertNull(match.existingProperty)
        assertFalse(match.needsReview)
        assertEquals("NONE", match.matchType)
    }

    @Test
    fun resultsDoNotDependOnDaoRowOrderAndRepeatIdentically() = runBlocking {
        val rows = listOf(
            property("PROP-A", "1420 South Congress Avenue"),
            property("PROP-B", "999 Oak Road"),
            property("PROP-C", "1420 S Congress Ave")
        )
        val incoming = incoming("1420 S Congress Ave, Austin, TX 78704")

        val baseline = PropertyDeduplicator(FakePropertyDao(rows)).findExistingMatch(incoming)
        val shuffledRuns = (1..15).map { seed ->
            PropertyDeduplicator(FakePropertyDao(rows, shuffleSeed = seed.toLong())).findExistingMatch(incoming)
        }

        assertTrue("row order must not change the decision", shuffledRuns.all { it == baseline })
        assertEquals(
            baseline,
            PropertyDeduplicator(FakePropertyDao(rows)).findExistingMatch(incoming)
        )
        // Two rows share the normalized address (PROP-A/PROP-C): that is a conflict for review,
        // never a silent pick of whichever row the DAO returned first.
        assertEquals("CONFLICT", baseline.matchType)
    }

    @Test
    fun unknownListingUrlDoesNotMatchStoredRows() = runBlocking {
        val existing = property("PROP-URL", "1420 South Congress Avenue")
        val deduplicator = PropertyDeduplicator(FakePropertyDao(listOf(existing)))

        // The stored row carries no URL identity, so a first URL import is new; the engine never
        // invents identifiers it was not given.
        val first = deduplicator.findExistingMatch(incoming("", sourceUrl = "https://www.redfin.com/TX/Austin/1420/home/1"))

        assertFalse(first.isMatch)
        assertFalse(first.needsReview)
        assertEquals("NONE", first.matchType)
    }

    @Test
    fun conflictingParcelIdBlocksAFuzzyMergeEvenWithASimilarAddress() = runBlocking {
        val existing = property("PROP-8", "1420 South Congress Avenue").copy(apn = "99-99")
        val deduplicator = PropertyDeduplicator(FakePropertyDao(listOf(existing)))

        val match = deduplicator.findExistingMatch(
            incoming("1420 S Congres Ave", apn = "11-22")
        )

        assertFalse(match.isMatch)
        assertFalse("a parcel conflict must not surface weak candidates", match.needsReview)
        assertEquals("NONE", match.matchType)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun incoming(
        address: String,
        apn: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        sourceUrl: String = ""
    ) = CanonicalProperty(
        propertyId = "INCOMING",
        sourceUrl = sourceUrl,
        source = "Redfin",
        address = address,
        city = "Austin",
        state = "TX",
        zipCode = "78704",
        latitude = latitude,
        longitude = longitude,
        listPrice = 485_000.0,
        apn = apn
    )

    private fun property(
        id: String,
        address: String,
        latitude: Double = 0.0,
        longitude: Double = 0.0
    ) = PropertyEntity(
        id = id,
        sourceType = "ON_MARKET",
        title = address.ifBlank { "Unlocated property" },
        address = address,
        city = if (address.isBlank()) "" else "Austin",
        state = if (address.isBlank()) "" else "TX",
        zipCode = if (address.isBlank()) "" else "78704",
        latitude = latitude,
        longitude = longitude,
        price = 485_000.0,
        propertyType = "Single Family",
        bedrooms = 3,
        bathrooms = 2.0,
        squareFeet = 1_850,
        yearBuilt = 2014,
        lotSizeSqFt = 6_500,
        description = "",
        status = "Active",
        primaryImageUrl = "",
        scannedAt = 1L
    )
}
