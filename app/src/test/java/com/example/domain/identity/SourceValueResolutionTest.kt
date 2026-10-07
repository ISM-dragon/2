package com.example.domain.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the explicit source-priority / provenance rules used when several sources disagree about a
 * property value: lower priority wins, recency breaks ties, names break full ties, missing values
 * never win, and every rejected value is recorded as a conflict instead of being dropped.
 */
class SourceValueResolutionTest {

    private fun value(
        source: String,
        priority: Int,
        observedAt: Long,
        recordRef: String? = null,
        text: String? = "123 Main St"
    ): ProvenancedValue<String?> = ProvenancedValue(text, SourceProvenance(source, priority, observedAt, recordRef))

    @Test
    fun lowerPriorityNumberWinsOverRecency() {
        val resolved = SourceValueResolver.resolveString(
            "address",
            listOf(
                value("Wholesaler Feed", priority = 90, observedAt = 5_000L, text = "123 Main St"),
                value("MLS", priority = 10, observedAt = 1_000L, text = "123 Main Street")
            )
        )

        assertEquals("123 Main Street", resolved.value)
        assertEquals("MLS", resolved.provenance?.source)
        assertEquals(1, resolved.conflicts.size)
        val conflict = resolved.conflicts.single()
        assertEquals("123 Main St", conflict.rejectedValue)
        assertEquals("Wholesaler Feed", conflict.rejectedProvenance.source)
        assertTrue(conflict.reason.contains("outranks"))
    }

    @Test
    fun equalPriorityLetsTheMoreRecentObservationWin() {
        val resolved = SourceValueResolver.resolveString(
            "address",
            listOf(
                value("Zillow", priority = 50, observedAt = 1_000L, text = "123 Main Street"),
                value("Zillow", priority = 50, observedAt = 9_000L, recordRef = "z-9", text = "123 Main St")
            )
        )

        assertEquals("123 Main St", resolved.value)
        assertEquals(9_000L, resolved.provenance?.observedAt)
        assertEquals(1, resolved.conflicts.size)
    }

    @Test
    fun fullTiesAreBrokenDeterministicallyBySourceName() {
        val observations = listOf(
            value("zeta-feed", priority = 50, observedAt = 1_000L, text = "123 Main Street"),
            value("alpha-feed", priority = 50, observedAt = 1_000L, text = "123 Main St")
        )

        val forward = SourceValueResolver.resolveString("address", observations)
        val reversed = SourceValueResolver.resolveString("address", observations.reversed())

        assertEquals("123 Main St", forward.value)
        assertEquals("alpha-feed", forward.provenance?.source)
        assertEquals(forward, reversed)
    }

    @Test
    fun resolutionIsIndependentOfObservationOrder() {
        val observations = listOf(
            value("A", priority = 5, observedAt = 10L, text = "one"),
            value("B", priority = 9, observedAt = 20L, text = "two"),
            value("C", priority = 9, observedAt = 30L, text = "three"),
            value("D", priority = 9, observedAt = 30L, text = null) // missing
        )

        val baseline = SourceValueResolver.resolveString("field", observations)
        assertEquals("one", baseline.value)

        val permutations = listOf(
            observations.reversed(),
            listOf(observations[2], observations[0], observations[3], observations[1]),
            listOf(observations[3], observations[2], observations[1], observations[0])
        )
        permutations.forEach { shuffled ->
            val resolved = SourceValueResolver.resolveString("field", shuffled)
            assertEquals(baseline.value, resolved.value)
            assertEquals(baseline.provenance, resolved.provenance)
            assertEquals(baseline.conflicts, resolved.conflicts)
        }
    }

    @Test
    fun missingValuesNeverWinAndNeverCreateConflicts() {
        val resolved = SourceValueResolver.resolveString(
            "address",
            listOf(
                value("Blank Feed", priority = 1, observedAt = 9_000L, text = "   "),
                value("Null Feed", priority = 1, observedAt = 9_000L, text = null),
                value("Real Feed", priority = 99, observedAt = 1L, text = "123 Main St")
            )
        )

        assertEquals("123 Main St", resolved.value)
        assertEquals("Real Feed", resolved.provenance?.source)
        assertTrue(resolved.conflicts.isEmpty())
    }

    @Test
    fun agreeingSourcesProduceNoConflictAndRecordTheStrongestProvenance() {
        val resolved = SourceValueResolver.resolveString(
            "city",
            listOf(
                value("Weak Feed", priority = 90, observedAt = 5_000L, text = "Austin"),
                value("Strong Feed", priority = 10, observedAt = 1_000L, text = "Austin  ")
            )
        )

        assertEquals("Austin", resolved.value)
        assertEquals("Strong Feed", resolved.provenance?.source)
        assertTrue(resolved.conflicts.isEmpty())
        assertTrue(resolved.rule.contains("agreed"))
    }

    @Test
    fun noUsableValueIsReportedExplicitly() {
        val resolved = SourceValueResolver.resolveString(
            "parcelId",
            listOf(value("Feed", priority = 10, observedAt = 1L, text = ""))
        )

        assertNull(resolved.value)
        assertNull(resolved.provenance)
        assertTrue(resolved.rule.contains("no source supplied"))
    }

    @Test
    fun coordinatesAreResolvedAsAnAtomicPairAndSentinelsAreMissing() {
        val resolved = SourceValueResolver.resolveCoordinates(
            "coordinates",
            listOf(
                ProvenancedValue(0.0 to 0.0, SourceProvenance("Sentinel Feed", priority = 1, observedAt = 9_000L)),
                ProvenancedValue(30.2672 to -97.7431, SourceProvenance("Real Feed", priority = 99, observedAt = 1L))
            )
        )

        assertEquals(30.2672 to -97.7431, resolved.value)
        assertEquals("Real Feed", resolved.provenance?.source)
        assertTrue(resolved.conflicts.isEmpty())
    }

    // ── canonical identity merging ──────────────────────────────────────────────────────────────

    private fun observation(
        source: String,
        priority: Int,
        observedAt: Long,
        address: String? = null,
        parcelId: String? = null,
        mlsId: String? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ) = SourcePropertyIdentity(
        source = source,
        providerListingId = "$source-listing",
        parcelId = parcelId,
        address = address,
        city = "Austin",
        state = "TX",
        postalCode = "78701",
        latitude = latitude,
        longitude = longitude,
        mlsId = mlsId,
        sourcePriority = priority,
        observedAt = observedAt
    )

    @Test
    fun mergerKeepsTheAuthoritativeAddressAndRecordsTheRejectedSpelling() {
        val authoritative = observation("MLS", priority = 10, observedAt = 1_000L, address = "123 Main Street", parcelId = "11-22")
        val noisy = observation("Wholesaler", priority = 90, observedAt = 9_000L, address = "123 Main St", parcelId = "11-22")

        val report = CanonicalIdentityMerger.merge(
            CanonicalPropertyIdentity(canonicalId = "home-1"),
            listOf(noisy, authoritative)
        )

        assertEquals("123 Main Street", report.merged.address)
        assertEquals("MLS", report.fieldProvenance["address"]?.source)
        assertEquals("11-22", report.merged.parcelId)
        assertTrue(report.hasConflicts)
        val addressConflict = report.conflicts.single { it.field == "address" }
        assertEquals("123 Main St", addressConflict.rejectedValue)
        assertEquals("Wholesaler", addressConflict.rejectedProvenance.source)
        assertTrue(report.explain().contains("123 Main Street"))
    }

    @Test
    fun mergerIsIdempotentAndOrderIndependent() {
        val first = observation("Zillow", priority = 50, observedAt = 2_000L, address = "123 Main St", mlsId = "A1")
        val second = observation("ATTOM", priority = 50, observedAt = 1_000L, address = "123 Main Street", parcelId = "11-22")

        val once = CanonicalIdentityMerger.merge(
            CanonicalPropertyIdentity(canonicalId = "home-1"),
            listOf(first, second)
        )
        val again = CanonicalIdentityMerger.merge(once.merged, listOf(second, first))
        val reMerged = CanonicalIdentityMerger.merge(again.merged)

        assertEquals(once.merged, again.merged)
        assertEquals(once.merged, reMerged.merged)
        assertEquals(2, reMerged.merged.sourceIdentities.size)
    }

    @Test
    fun mergerFallsBackToTheCanonicalValueWhenNoSourceSuppliesAField() {
        val report = CanonicalIdentityMerger.merge(
            CanonicalPropertyIdentity(
                canonicalId = "home-1",
                address = "999 Fallback Ave",
                sourceIdentities = setOf(observation("Feed", priority = 50, observedAt = 1L, parcelId = "11-22"))
            )
        )

        assertEquals("999 Fallback Ave", report.merged.address)
        // parcelId came from the source; address only existed on the canonical row.
        assertEquals("Feed", report.fieldProvenance["parcelId"]?.source)
        assertNull(report.fieldProvenance["address"])
    }

    @Test
    fun mergerResolvesCoordinatesByProvenanceToo() {
        val report = CanonicalIdentityMerger.merge(
            CanonicalPropertyIdentity(canonicalId = "home-1"),
            listOf(
                observation("Low Authority", priority = 90, observedAt = 9_000L, latitude = 30.1, longitude = -97.1),
                observation("High Authority", priority = 10, observedAt = 1L, latitude = 30.2672, longitude = -97.7431)
            )
        )

        assertEquals(30.2672, report.merged.latitude!!, 0.0)
        assertEquals(-97.7431, report.merged.longitude!!, 0.0)
        assertEquals("High Authority", report.fieldProvenance["coordinates"]?.source)
    }

    @Test
    fun provenanceComparisonOrderIsTotal() {
        val strictest = SourceProvenance("a", priority = 1, observedAt = 0L)
        val looserPriority = SourceProvenance("a", priority = 2, observedAt = 0L)
        val samePriorityNewer = SourceProvenance("b", priority = 1, observedAt = 5L)
        val samePrioritySameAge = SourceProvenance("a", priority = 1, observedAt = 0L)

        assertTrue(strictest < looserPriority)
        // Recency breaks the priority tie: the newer observation is the stronger provenance.
        assertTrue(samePriorityNewer < strictest)
        assertEquals(0, strictest.compareTo(samePrioritySameAge))
        assertEquals(
            listOf(samePriorityNewer, strictest, looserPriority),
            listOf(strictest, samePriorityNewer, looserPriority).sorted()
        )
    }
}
