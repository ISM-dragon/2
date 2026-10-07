package com.example.urlintelligence

import com.example.urlintelligence.adapter.GenericWebAdapter
import com.example.urlintelligence.adapter.HomesAdapter
import com.example.urlintelligence.adapter.PropertyParseResult
import com.example.urlintelligence.adapter.PropertySourceAdapter
import com.example.urlintelligence.adapter.RealtorAdapter
import com.example.urlintelligence.adapter.RedfinAdapter
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.adapter.ZillowAdapter
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.NormalizationOutcome
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.parser.DriftLevel
import com.example.urlintelligence.parser.DriftPolicy
import com.example.urlintelligence.parser.ParserSpec
import com.example.urlintelligence.parser.SignatureProbe
import com.example.urlintelligence.provenance.VerificationLevel
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.source.KnownSources
import com.example.urlintelligence.source.SourceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser versioning and schema-drift detection.
 *
 * Every provider parser carries a versioned contract. These tests pin the three behaviours the
 * pipeline depends on:
 *
 *  1. a healthy page reports no drift and stamps its parser id/version onto every field,
 *  2. a reworked page is reported as drift (warning by default, typed failure when the parser
 *     is configured to fail) and its fields stop being parser-verified,
 *  3. minor drift (an optional marker moved) does not throw away good data.
 */
class ParserVersionAndDriftTest {

    private fun parse(
        adapter: PropertySourceAdapter,
        url: String,
        fixture: String,
        response: SourceFetchResponse.Success? = null
    ): PropertyParseResult {
        val body = Fixtures.load(fixture)
        return adapter.parse(
            response ?: SourceFetchResponse.Success(
                statusCode = 200,
                body = body,
                contentType = "text/html",
                finalUrl = url,
                fetchedAtEpochMillis = 1_700_000_000_000L
            ),
            SourceFetchRequest(url, "corr-parser")
        )
    }

    // ---- parser contracts -----------------------------------------------------------------

    @Test
    fun `every shipped adapter declares a versioned parser contract`() {
        val transport = FakeTransport()
        val adapters = listOf<PropertySourceAdapter>(
            ZillowAdapter(transport),
            RedfinAdapter(transport),
            RealtorAdapter(transport),
            HomesAdapter(transport),
            GenericWebAdapter(transport)
        )
        val ids = adapters.map { adapter -> adapter.descriptor.id }.toSet()
        assertEquals(KnownSources.all().map { it.id }.toSet(), ids)

        adapters.forEach { adapter ->
            val spec = (adapter as? com.example.urlintelligence.adapter.StructuredDataPropertySourceAdapter)
                ?.parserSpec
            assertNotNull("adapter ${adapter.descriptor.id} must declare a parser spec", spec)
            assertTrue(spec!!.parserId.isNotBlank())
            assertTrue(spec.version.isNotBlank())
            assertEquals("${spec.parserId}@${spec.version}", spec.qualifiedVersion)
            assertTrue(
                "provider parsers must watch for schema drift",
                spec.hasSignature()
            )
        }
    }

    @Test
    fun `parser spec rejects invalid contracts`() {
        var failed = false
        try {
            ParserSpec(parserId = " ", version = "1")
        } catch (t: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)

        failed = false
        try {
            ParserSpec(parserId = "x", version = "1", minFieldsExtracted = -1)
        } catch (t: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `a healthy document reports no drift`() {
        val adapter = ZillowAdapter(FakeTransport())
        val result = parse(adapter, Fixtures.ZILLOW_URL, "zillow_listing.html")
        assertTrue("expected success but was $result", result is PropertyParseResult.Success)

        val draft = (result as PropertyParseResult.Success).draft
        val drift = draft.drift
        assertNotNull(drift)
        assertEquals(DriftLevel.NONE, drift!!.level)
        assertEquals("zillow.html", drift.parserId)
        assertEquals(ZillowAdapter.PARSER_SPEC.version, drift.parserVersion)
        assertFalse(drift.isDrift)

        // Parser identity is stamped onto every field so a value can be traced to a version.
        val provenance = draft.provenance(0L)
        assertEquals("zillow.html", provenance[PropertyField.LIST_PRICE]!!.parserId)
        assertEquals(ZillowAdapter.PARSER_SPEC.version, provenance[PropertyField.LIST_PRICE]!!.parserVersion)
        assertNotNull(provenance[PropertyField.LIST_PRICE]!!.documentDigest)
        assertEquals("zillow.html@${ZillowAdapter.PARSER_SPEC.version}",
            provenance[PropertyField.LIST_PRICE]!!.parserRef)
        assertTrue(provenance[PropertyField.LIST_PRICE]!!.verification.rank >=
            VerificationLevel.PARSER_VERIFIED.rank)
    }

    // ---- drift ----------------------------------------------------------------------------

    @Test
    fun `a reworked page is reported as major drift and stops being parser verified`() {
        val adapter = ZillowAdapter(FakeTransport())
        val result = parse(adapter, Fixtures.ZILLOW_URL, "zillow_drifted.html")

        // The page still yields the address from meta tags, so the import is usable — but the
        // missing zpid/JSON-LD markers must be reported, and nothing may claim to be verified.
        assertTrue("expected a usable parse but was $result", result is PropertyParseResult.Partial)
        val partial = result as PropertyParseResult.Partial
        assertEquals(DriftLevel.MAJOR, partial.draft.drift!!.level)
        assertTrue(partial.draft.drift!!.missingProbes.contains("zpid"))
        assertTrue(
            "drift must be reported on the result",
            partial.warnings.any { it.contains("schema drift") }
        )

        val provenance = partial.draft.provenance(0L)
        assertTrue(provenance.isNotEmpty())
        provenance.entries().values.forEach { entry ->
            assertEquals(
                "fields from a drifted document must not be parser-verified",
                VerificationLevel.UNVERIFIED,
                entry.verification
            )
        }
    }

    @Test
    fun `minor drift keeps the data but records a warning`() {
        val adapter = RedfinAdapter(FakeTransport())
        val result = parse(adapter, Fixtures.REDFIN_URL, "redfin_minor_drift.html")
        assertTrue("expected success but was $result", result is PropertyParseResult.Success)
        val success = result as PropertyParseResult.Success

        assertEquals(DriftLevel.MINOR, success.draft.drift!!.level)
        assertTrue(success.warnings.any { it.contains("schema drift") })

        // Minor drift must not downgrade values: the page is still the page we know.
        val provenance = success.draft.provenance(0L)
        assertEquals(
            VerificationLevel.PARSER_VERIFIED,
            provenance[PropertyField.LIST_PRICE]!!.verification
        )
    }

    @Test
    fun `drift can be configured to fail the parse instead of warning`() {
        val adapter = object : com.example.urlintelligence.adapter.StructuredDataPropertySourceAdapter(
            transport = FakeTransport(),
            descriptor = KnownSources.ZILLOW,
            clock = Clock.SYSTEM,
            driftPolicy = DriftPolicy.FAIL
        ) {
            override val parserSpec: ParserSpec = ZillowAdapter.PARSER_SPEC
            override fun selectors() = ZillowAdapter.SELECTORS
        }

        val result = parse(adapter, Fixtures.ZILLOW_URL, "zillow_drifted.html")
        assertTrue("expected a typed drift failure but was $result", result is PropertyParseResult.Failure)
        val failure = (result as PropertyParseResult.Failure).failure
        assertTrue(failure is SourceFailure.SchemaDrift)
        val drift = failure as SourceFailure.SchemaDrift
        assertEquals("zillow.html", drift.parserId)
        assertEquals("3", drift.parserVersion)
        assertEquals("MAJOR", drift.level)
        assertEquals("SCHEMA_DRIFT", drift.code)
        assertFalse("drift is permanent, never retried", drift.retryable)
    }

    @Test
    fun `drift report summarises what changed without leaking document content`() {
        val spec = ParserSpec(
            parserId = "demo.html",
            version = "9",
            probes = listOf(
                SignatureProbe("required-marker", Regex("data-listing-id")),
                SignatureProbe("optional-marker", Regex("data-agent-block"))
            ),
            minProbesMatched = 1,
            minFieldsExtracted = 2
        )
        val report = spec.inspect("<html>no markers here</html>", extractedFields = 1)
        assertEquals(DriftLevel.MAJOR, report.level)
        val summary = report.summary()
        assertTrue(summary.contains("demo.html@9"))
        assertTrue(summary.contains("MAJOR"))
        assertTrue(summary.contains("required-marker"))
        assertFalse("summaries must not carry page content", summary.contains("no markers here"))
    }

    @Test
    fun `specs require the minimum number of signature markers`() {
        val spec = ParserSpec(
            parserId = "demo.html",
            version = "1",
            probes = listOf(
                SignatureProbe("a", "alpha".toRegex()),
                SignatureProbe("b", "beta".toRegex()),
                SignatureProbe("c", "gamma".toRegex())
            ),
            minProbesMatched = 2,
            minFieldsExtracted = 1
        )
        assertEquals(DriftLevel.NONE, spec.inspect("alpha beta gamma", 5).level)
        assertEquals(DriftLevel.MINOR, spec.inspect("alpha beta", 5).level)
        assertEquals(DriftLevel.MAJOR, spec.inspect("alpha", 5).level)
    }

    @Test
    fun `a document that yields nothing is a parse error not drift`() {
        val adapter = GenericWebAdapter(FakeTransport())
        val result = parse(adapter, Fixtures.GENERIC_URL, "empty_page.html")
        assertTrue(result is PropertyParseResult.Failure)
    }

    @Test
    fun `drift warnings survive normalization into the canonical property`() {
        val adapter = ZillowAdapter(FakeTransport())
        val partial = parse(adapter, Fixtures.ZILLOW_URL, "zillow_drifted.html")
            as PropertyParseResult.Partial
        val outcome = PropertyNormalizer(TestClock()).normalize(partial.draft, partial.warnings)
        assertTrue(outcome is NormalizationOutcome.Success)
        val property = (outcome as NormalizationOutcome.Success).property
        assertTrue(property.warnings.any { it.contains("schema drift") })
        assertFalse("a drifted parse must not claim parser verification", property.isParserVerified)
    }

    @Test
    fun `parser versions are visible on the canonical property`() {
        val adapter = ZillowAdapter(FakeTransport())
        val result = parse(adapter, Fixtures.ZILLOW_URL, "zillow_listing.html")
            as PropertyParseResult.Success
        val property = (PropertyNormalizer(TestClock()).normalize(result.draft, result.warnings)
            as NormalizationOutcome.Success).property
        assertTrue(property.parserVersions.contains("zillow.html@3"))
    }

    @Test
    fun `unknown descriptor ids cannot be used to bypass versioning`() {
        val descriptor = SourceDescriptor(
            id = "no-spec",
            displayName = "No spec",
            hostSuffixes = listOf("no-spec.test")
        )
        assertNull(KnownSources.byId(descriptor.id))
        assertTrue(KnownSources.GENERIC.enabledByDefault)
    }
}
