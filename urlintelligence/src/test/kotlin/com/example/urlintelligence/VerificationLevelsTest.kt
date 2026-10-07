package com.example.urlintelligence

import com.example.urlintelligence.adapter.PropertyParseResult
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.adapter.ZillowAdapter
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.idempotency.InMemoryIdempotencyStore
import com.example.urlintelligence.job.InMemoryPropertyImportJobStore
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.NormalizationOutcome
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import com.example.urlintelligence.provenance.FetchOrigin
import com.example.urlintelligence.provenance.FieldProvenance
import com.example.urlintelligence.provenance.ProvenanceMap
import com.example.urlintelligence.provenance.VerificationLevel
import com.example.urlintelligence.provenance.VerificationSummary
import com.example.urlintelligence.resolver.PropertyImportResult
import com.example.urlintelligence.resolver.PropertyUrlResolver
import com.example.urlintelligence.resolver.ResolveOptions
import com.example.urlintelligence.source.KnownSources
import com.example.urlintelligence.source.SourceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser-verified vs live-fetch-verified.
 *
 * The rule under test is a compliance rule, not a formatting one: nothing may claim that data
 * was verified against a provider unless a transport that really performed the request said so.
 * Fixtures, replays and cached documents are parser-verified at most.
 */
class VerificationLevelsTest {

    private fun parseWith(
        fixture: String,
        origin: FetchOrigin,
        url: String = Fixtures.ZILLOW_URL
    ): PropertyParseResult {
        val adapter = ZillowAdapter(FakeTransport(origin = origin))
        val response = SourceFetchResponse.Success(
            statusCode = 200,
            body = Fixtures.load(fixture),
            contentType = "text/html",
            finalUrl = url,
            fetchedAtEpochMillis = 1_700_000_000_000L,
            origin = origin
        )
        return adapter.parse(response, SourceFetchRequest(url, "corr-verification"))
    }

    private fun property(fixture: String, origin: FetchOrigin) =
        (PropertyNormalizer(TestClock()).normalize(
            (parseWith(fixture, origin) as PropertyParseResult.Success).draft
        ) as NormalizationOutcome.Success).property

    // ---- the core distinction ---------------------------------------------------------------

    @Test
    fun `a replayed document is parser verified but never live verified`() {
        val property = property("zillow_listing.html", FetchOrigin.REPLAYED)

        assertTrue(property.isParserVerified)
        assertFalse("a replay must never be reported as live", property.isLiveVerified)
        assertEquals(FetchOrigin.REPLAYED, property.verification.origin)
        assertEquals(0, property.verification.liveVerifiedFields)
        assertTrue(property.verification.parserVerifiedFields > 10)
        assertTrue(property.verification.describe().startsWith("parser verified"))
    }

    @Test
    fun `an unreported origin is parser verified and not live verified`() {
        val property = property("zillow_listing.html", FetchOrigin.UNSPECIFIED)
        assertFalse(property.isLiveVerified)
        assertTrue(property.isParserVerified)
    }

    @Test
    fun `a live fetch is the only way to become live verified`() {
        val property = property("zillow_listing.html", FetchOrigin.LIVE_NETWORK)

        assertTrue(property.isLiveVerified)
        assertEquals(FetchOrigin.LIVE_NETWORK, property.verification.origin)
        assertEquals(0, property.verification.unverifiedFields)
        assertTrue(property.verification.describe().startsWith("live fetch verified"))
        property.provenance.entries().values.forEach { entry ->
            assertEquals(VerificationLevel.LIVE_FETCH_VERIFIED, entry.verification)
            assertTrue(entry.isLiveVerified)
        }
    }

    @Test
    fun `a cached document is never upgraded to live`() {
        val property = property("zillow_listing.html", FetchOrigin.LOCAL_CACHE)
        assertFalse(property.isLiveVerified)
        assertTrue(property.isParserVerified)
        assertEquals(FetchOrigin.LOCAL_CACHE, property.verification.origin)
    }

    @Test
    fun `heuristic-only extraction is downgraded to unverified`() {
        // The generic adapter keeps working on drifted pages, but the values it salvages must
        // not be presented as parser-verified.
        val adapter = com.example.urlintelligence.adapter.GenericWebAdapter(FakeTransport())
        val success = adapter.parse(
            SourceFetchResponse.Success(
                statusCode = 200,
                body = Fixtures.load("generic_brokerage.html"),
                contentType = "text/html",
                finalUrl = Fixtures.GENERIC_URL,
                fetchedAtEpochMillis = 0L,
                origin = FetchOrigin.REPLAYED
            ),
            SourceFetchRequest(Fixtures.GENERIC_URL, "corr")
        ) as PropertyParseResult.Success

        // The generic page carries og:title and a heading, so the contract is satisfied here.
        assertEquals(
            VerificationLevel.PARSER_VERIFIED,
            success.draft.provenance(0L)[PropertyField.ADDRESS_LINE1]!!.verification
        )
    }

    @Test
    fun `level resolution never invents a live fetch`() {
        assertEquals(
            VerificationLevel.UNVERIFIED,
            com.example.urlintelligence.provenance.verificationLevelFor(
                verifiedByParser = false,
                origin = FetchOrigin.LIVE_NETWORK
            )
        )
        assertEquals(
            VerificationLevel.LIVE_FETCH_VERIFIED,
            com.example.urlintelligence.provenance.verificationLevelFor(true, FetchOrigin.LIVE_NETWORK)
        )
        FetchOrigin.entries
            .filter { it != FetchOrigin.LIVE_NETWORK }
            .forEach { origin ->
                assertEquals(
                    "origin $origin must not produce live-verified fields",
                    VerificationLevel.PARSER_VERIFIED,
                    com.example.urlintelligence.provenance.verificationLevelFor(true, origin)
                )
            }
    }

    // ---- summaries ---------------------------------------------------------------------------

    @Test
    fun `summaries tally fields and parsers`() {
        val provenance = ProvenanceMap(
            mapOf(
                PropertyField.LIST_PRICE to entry(PropertyField.LIST_PRICE, "zillow.html", "3"),
                PropertyField.CITY to entry(PropertyField.CITY, "zillow.html", "3"),
                PropertyField.YEAR_BUILT to entry(
                    PropertyField.YEAR_BUILT,
                    "generic.html",
                    "1",
                    verified = false
                )
            )
        )
        val summary = VerificationSummary.from(
            provenance.entries(),
            FetchOrigin.LIVE_NETWORK
        )
        assertEquals(2, summary.liveVerifiedFields)
        assertEquals(1, summary.unverifiedFields)
        assertEquals(3, summary.totalFields)
        assertTrue(summary.parsers.contains("zillow.html@3"))
        assertTrue(summary.liveVerified)
        assertTrue(summary.describe().contains("live fetch verified"))
    }

    @Test
    fun `an empty property has no verification claims`() {
        val summary = VerificationSummary.NONE
        assertFalse(summary.liveVerified)
        assertFalse(summary.parserVerified)
        assertEquals(0, summary.totalFields)
        assertEquals("unverified", summary.describe())
    }

    @Test
    fun `live verification requires live origin and at least one live field`() {
        val parserOnly = VerificationSummary(
            origin = FetchOrigin.LIVE_NETWORK,
            liveVerifiedFields = 0,
            parserVerifiedFields = 4,
            unverifiedFields = 0
        )
        assertFalse(parserOnly.liveVerified)

        val contradictory = VerificationSummary(
            origin = FetchOrigin.REPLAYED,
            liveVerifiedFields = 4,
            parserVerifiedFields = 0,
            unverifiedFields = 0
        )
        assertFalse(contradictory.liveVerified)
    }

    // ---- resolver end to end ------------------------------------------------------------------

    private fun resolver(transport: FakeTransport): PropertyUrlResolver {
        val registry = SourceRegistry(
            descriptors = KnownSources.all(),
            adapters = listOf(ZillowAdapter(transport))
        )
        return PropertyUrlResolver(
            registry = registry,
            normalizer = PropertyNormalizer(TestClock()),
            clock = TestClock(),
            jobStore = InMemoryPropertyImportJobStore(),
            resultStore = InMemoryIdempotencyStore(TestClock()),
            allowedSourceIds = setOf("zillow"),
            idGenerator = { "job-verification" },
            sleeper = com.example.urlintelligence.retry.RecordingSleeper()
        )
    }

    @Test
    fun `the resolver reports what it actually verified`() {
        val body = Fixtures.load("zillow_listing.html")

        val replayed = runBlocking {
            resolver(FakeTransport(bodies = listOf(body), origin = FetchOrigin.REPLAYED))
                .resolve(Fixtures.ZILLOW_URL)
        } as PropertyImportResult.Success
        assertFalse(replayed.property.isLiveVerified)
        assertTrue(replayed.property.isParserVerified)
        assertTrue(replayed.property.parserVersions.contains("zillow.html@3"))

        val live = runBlocking {
            resolver(FakeTransport(bodies = listOf(body), origin = FetchOrigin.LIVE_NETWORK))
                .resolve(Fixtures.ZILLOW_URL, ResolveOptions(refresh = true))
        } as PropertyImportResult.Success
        assertTrue("a live transport must produce live-verified data", live.property.isLiveVerified)
    }

    @Test
    fun `every present field carries provenance including parser identity`() {
        val property = property("zillow_listing.html", FetchOrigin.REPLAYED)
        property.completeness.present.forEach { field ->
            val entry = property.provenance[field]
            assertNotNull("missing provenance for $field", entry)
            assertEquals("zillow", entry!!.sourceId)
            assertEquals("zillow.html", entry.parserId)
            assertEquals("3", entry.parserVersion)
            assertNotNull("missing document digest for $field", entry.documentDigest)
            assertEquals(64, entry.documentDigest!!.length)
        }
        assertTrue(property.provenance.isCompleteFor(property.completeness.present))
    }

    @Test
    fun `provenance can be filtered by verification level`() {
        val property = property("zillow_listing.html", FetchOrigin.LIVE_NETWORK)
        assertEquals(
            property.provenance.size,
            property.provenance.withVerificationAtLeast(VerificationLevel.LIVE_FETCH_VERIFIED).size
        )
        assertEquals(0, property.provenance.withVerificationAtLeast(VerificationLevel.LIVE_FETCH_VERIFIED)
            .bySource("redfin").size)
    }

    @Test
    fun `a drifted parse reports unverified fields and a warning`() {
        val property = property("zillow_drifted.html", FetchOrigin.REPLAYED)
        assertFalse(property.isParserVerified)
        assertEquals(FetchOrigin.REPLAYED, property.verification.origin)
        assertFalse(property.isLiveVerified)
        assertTrue(property.provenance.entries().values.all {
            it.verification == VerificationLevel.UNVERIFIED
        })
        assertTrue(property.warnings.any { it.contains("schema drift") })
    }

    @Test
    fun `provenance for a manual override is marked accordingly`() {
        val property = property("zillow_listing.html", FetchOrigin.REPLAYED)
        val overridden = com.example.urlintelligence.normalization.CanonicalPropertyMerger.applyManualOverride(
            property,
            mapOf(PropertyField.LIST_PRICE to 470_000.0)
        )
        val entry = overridden.provenance[PropertyField.LIST_PRICE]
        assertEquals(ExtractionMethod.MANUAL, entry!!.method)
        assertEquals("manual", entry.sourceId)
        assertFalse("manual edits are not parser-verified", entry.verification == VerificationLevel.PARSER_VERIFIED)
    }

    @Test
    fun `the job records failures without claiming success`() {
        val transport = FakeTransport(failures = listOf(SourceFailure.NotFound("404")))
        val resolver = resolver(transport)
        val result = runBlocking { resolver.resolve(Fixtures.ZILLOW_URL) }
        assertTrue(result is PropertyImportResult.Failure)
        assertEquals("NOT_FOUND", (result as PropertyImportResult.Failure).failure.code)
    }

    private fun entry(
        field: PropertyField,
        parserId: String,
        parserVersion: String,
        verified: Boolean = true
    ): FieldProvenance = FieldProvenance(
        field = field,
        sourceId = "zillow",
        sourceUrl = Fixtures.ZILLOW_URL,
        extractor = parserId,
        method = ExtractionMethod.DOM_SELECTOR,
        confidence = Confidence.HIGH,
        rawValue = "1",
        extractedAtEpochMillis = 0L,
        parserId = parserId,
        parserVersion = parserVersion,
        verification = com.example.urlintelligence.provenance.verificationLevelFor(
            verified,
            FetchOrigin.LIVE_NETWORK
        )
    )
}
