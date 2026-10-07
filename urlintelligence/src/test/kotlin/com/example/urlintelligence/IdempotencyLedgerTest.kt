package com.example.urlintelligence

import com.example.urlintelligence.adapter.ZillowAdapter
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.idempotency.Digests
import com.example.urlintelligence.idempotency.IdempotencyKey
import com.example.urlintelligence.idempotency.ImportIdempotency
import com.example.urlintelligence.idempotency.ImportLedgerEntry
import com.example.urlintelligence.idempotency.InMemoryIdempotencyStore
import com.example.urlintelligence.idempotency.InMemoryImportLedger
import com.example.urlintelligence.job.InMemoryPropertyImportJobStore
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.resolver.PropertyImportResult
import com.example.urlintelligence.resolver.PropertyUrlResolver
import com.example.urlintelligence.resolver.ResolveOptions
import com.example.urlintelligence.source.KnownSources
import com.example.urlintelligence.source.SourceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Idempotency: one fetch per listing and one import per property.
 *
 * URL-level idempotency ("same link, one fetch") and property-level idempotency ("same house,
 * different link, still one import") are both asserted here, together with the rule that a
 * failure never leaves a reservation behind that would block a later retry.
 */
class IdempotencyLedgerTest {

    private val zillowListing = Fixtures.ZILLOW_URL
    private val zillowVariant =
        "https://www.zillow.com/homedetails/2418-S-Congress-Ave-Austin-TX-78704/12345678_zpid"

    private class Harness(
        val transport: FakeTransport,
        val ledger: InMemoryImportLedger<PropertyImportResult>,
        val resultStore: InMemoryIdempotencyStore<PropertyImportResult>,
        val jobStore: InMemoryPropertyImportJobStore,
        val resolver: PropertyUrlResolver
    )

    private fun harness(
        bodies: List<String> = listOf(Fixtures.load("zillow_listing.html")),
        failures: List<SourceFailure?> = listOf(null)
    ): Harness {
        val transport = FakeTransport(bodies = bodies, failures = failures)
        val ledger = InMemoryImportLedger<PropertyImportResult>(TestClock())
        val resultStore = InMemoryIdempotencyStore<PropertyImportResult>(TestClock())
        val jobStore = InMemoryPropertyImportJobStore()
        val registry = SourceRegistry(
            descriptors = KnownSources.all(),
            adapters = listOf(ZillowAdapter(transport))
        )
        val resolver = PropertyUrlResolver(
            registry = registry,
            normalizer = PropertyNormalizer(TestClock()),
            clock = TestClock(),
            jobStore = jobStore,
            resultStore = resultStore,
            allowedSourceIds = setOf("zillow"),
            ledger = ledger,
            idGenerator = { "job-${jobStore.size() + 1}" },
            sleeper = com.example.urlintelligence.retry.RecordingSleeper()
        )
        return Harness(transport, ledger, resultStore, jobStore, resolver)
    }

    // ---- URL level --------------------------------------------------------------------------

    @Test
    fun `the same url is fetched once and replayed afterwards`() {
        val h = harness()
        val first = runBlocking { h.resolver.resolve(zillowListing) } as PropertyImportResult.Success
        val second = runBlocking { h.resolver.resolve("$zillowListing?utm_source=share#photos") }
            as PropertyImportResult.Success

        assertEquals(1, h.transport.requests.size)
        assertEquals(ImportIdempotency.FRESH, first.idempotency)
        assertEquals(ImportIdempotency.REPLAYED_URL, second.idempotency)
        assertEquals(first.property.canonicalId, second.property.canonicalId)
    }

    @Test
    fun `refresh always fetches again`() {
        val h = harness()
        runBlocking { h.resolver.resolve(zillowListing) }
        val refreshed = runBlocking {
            h.resolver.resolve(zillowListing, ResolveOptions(refresh = true))
        } as PropertyImportResult.Success

        assertEquals(2, h.transport.requests.size)
        assertEquals(ImportIdempotency.FRESH, refreshed.idempotency)
    }

    // ---- property level ----------------------------------------------------------------------

    @Test
    fun `a different url for the same listing reuses the previous import without fetching`() {
        val h = harness()
        val first = runBlocking { h.resolver.resolve(zillowListing) } as PropertyImportResult.Success

        // Same zpid, different path shape: the ledger must recognise the property.
        val second = runBlocking { h.resolver.resolve(zillowVariant) } as PropertyImportResult.Success

        assertEquals("only the first import may touch the network", 1, h.transport.requests.size)
        assertEquals(ImportIdempotency.REPLAYED_PROPERTY, second.idempotency)
        assertEquals(first.property.canonicalId, second.property.canonicalId)
        assertEquals("the reused result keeps the original job id", first.jobId, second.jobId)
    }

    @Test
    fun `a reused import is recorded as a job so callers can still track it`() {
        val h = harness()
        val first = runBlocking { h.resolver.resolve(zillowListing) } as PropertyImportResult.Success
        runBlocking { h.resolver.resolve(zillowVariant) }

        val jobs = h.jobStore.all()
        assertEquals("both requests must be auditable", 2, jobs.size)

        val original = h.jobStore.findById(first.jobId)
        assertNotNull(original)
        assertTrue(original!!.transitions.any { it.note == "canonical=${h.resolver.inspect(zillowListing).normalizedUrl!!.canonical}" })

        val duplicate = jobs.first { it.jobId != first.jobId }
        assertEquals(
            "the duplicate request did no fetch work",
            com.example.urlintelligence.job.ImportJobState.CANCELLED,
            duplicate.state
        )
        assertTrue(
            duplicate.transitions.any { it.note?.contains("canonical property") == true }
        )
    }

    @Test
    fun `inspection reports that a property is already imported`() {
        val h = harness()
        val before = h.resolver.inspect(zillowListing)
        assertFalse(before.alreadyImported)

        runBlocking { h.resolver.resolve(zillowListing) }

        assertTrue(h.resolver.inspect(zillowListing).alreadyImported)
        assertTrue(h.resolver.inspect(zillowVariant).alreadyImported)
    }

    @Test
    fun `refresh ignores the ledger and records new content`() {
        val h = harness(bodies = listOf(Fixtures.load("zillow_listing.html")))
        runBlocking { h.resolver.resolve(zillowListing) }
        val refreshed = runBlocking {
            h.resolver.resolve(zillowListing, ResolveOptions(refresh = true))
        } as PropertyImportResult.Success

        assertEquals(2, h.transport.requests.size)
        assertEquals(ImportIdempotency.FRESH, refreshed.idempotency)
        assertEquals(2, h.ledger.find(refreshed.property.canonicalId)!!.imports)
    }

    @Test
    fun `changed source content is surfaced as a warning`() {
        val h = harness(
            bodies = listOf(
                Fixtures.load("zillow_listing.html"),
                Fixtures.load("zillow_drifted.html")
            )
        )
        runBlocking { h.resolver.resolve(zillowListing) }
        val refreshed = runBlocking {
            h.resolver.resolve(zillowListing, ResolveOptions(refresh = true))
        } as PropertyImportResult.Success

        assertTrue(
            "expected a change warning but got ${refreshed.warnings}",
            refreshed.warnings.any { it.contains("source content changed") }
        )
    }

    // ---- failure hygiene ----------------------------------------------------------------------

    @Test
    fun `a failed import leaves no reservation behind`() {
        val h = harness(
            bodies = listOf(Fixtures.load("zillow_listing.html")),
            failures = listOf(SourceFailure.NotFound("404"))
        )
        val result = runBlocking { h.resolver.resolve(zillowListing) }

        assertTrue(result is PropertyImportResult.Failure)
        assertEquals(0, h.resultStore.size())
        assertEquals(0, h.ledger.size())
    }

    @Test
    fun `an unsupported source never reserves or records anything`() {
        val h = harness()
        val result = runBlocking { h.resolver.resolve("https://unknown-portal.test/listing/1") }

        assertTrue(result is PropertyImportResult.Failure)
        assertTrue((result as PropertyImportResult.Failure).failure is SourceFailure.UnsupportedSource)
        assertEquals(0, h.resultStore.size())
        assertEquals(0, h.ledger.size())
        assertEquals(0, h.transport.requests.size)
    }

    @Test
    fun `a conflict does not corrupt the ledger`() {
        val h = harness()
        val canonical = h.resolver.inspect(zillowListing).normalizedUrl!!.canonical
        h.resultStore.putIfAbsent(
            IdempotencyKey.forUrl(canonical),
            com.example.urlintelligence.idempotency.IdempotencyRecord(
                key = IdempotencyKey.forUrl(canonical),
                state = com.example.urlintelligence.idempotency.IdempotencyState.IN_FLIGHT,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L
            )
        )
        val result = runBlocking { h.resolver.resolve(zillowListing) }
        assertTrue((result as PropertyImportResult.Failure).failure is SourceFailure.Conflict)
        assertEquals(0, h.ledger.size())
    }

    // ---- ledger mechanics ---------------------------------------------------------------------

    @Test
    fun `ledger entries expire and can be forgotten`() {
        val clock = TestClock()
        val ledger = InMemoryImportLedger<PropertyImportResult>(clock, ttlMillis = 1_000L)
        ledger.record(ImportLedgerEntry<PropertyImportResult>("zillow:1", "zillow", "u", "d", clock.now(), value = null))
        assertEquals(1, ledger.size())

        clock.advance(1_500L)
        assertEquals(0, ledger.size())
        assertNull(ledger.find("zillow:1"))

        ledger.record(ImportLedgerEntry<PropertyImportResult>("zillow:2", "zillow", "u", "d", clock.now(), value = null))
        ledger.forget("zillow:2")
        assertEquals(0, ledger.size())
    }

    @Test
    fun `ledger counts repeated imports of the same property`() {
        val ledger = InMemoryImportLedger<PropertyImportResult>(TestClock())
        val entry = ImportLedgerEntry<PropertyImportResult>("zillow:1", "zillow", "u", "d", 0L, value = null)
        assertEquals(1, ledger.record(entry).imports)
        assertEquals(2, ledger.record(entry).imports)
        assertEquals(3, ledger.record(entry).imports)
        assertEquals(1, ledger.size())
        assertEquals(3, ledger.find("zillow:1")!!.imports)
    }

    @Test
    fun `the ledger is bounded`() {
        val ledger = InMemoryImportLedger<PropertyImportResult>(TestClock(), maxEntries = 3)
        (1..6).forEach { index ->
            ledger.record(
                ImportLedgerEntry<PropertyImportResult>(
                    "zillow:$index", "zillow", "u$index", "d$index", 0L, value = null
                )
            )
        }
        assertEquals(3, ledger.size())
        assertEquals(3, ledger.all().size)
    }

    // ---- digests -------------------------------------------------------------------------------

    @Test
    fun `content digests are stable, order independent and value sensitive`() {
        val a = Digests.fieldsDigest(
            "zillow",
            mapOf(PropertyField.LIST_PRICE to 485_000.0, PropertyField.CITY to "Austin")
        )
        val b = Digests.fieldsDigest(
            "zillow",
            mapOf(PropertyField.CITY to "Austin", PropertyField.LIST_PRICE to 485_000.0)
        )
        val c = Digests.fieldsDigest(
            "zillow",
            mapOf(PropertyField.LIST_PRICE to 486_000.0, PropertyField.CITY to "Austin")
        )
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(64, a.length)
        assertEquals(a, Digests.fieldsDigest(
            "zillow",
            mapOf(PropertyField.LIST_PRICE to 485_000.0, PropertyField.CITY to "austin")
        ))
    }

    @Test
    fun `digests ignore the source when the data is identical`() {
        val fields = mapOf(PropertyField.CITY to "Austin")
        assertNotEquals(
            Digests.fieldsDigest("zillow", fields),
            Digests.fieldsDigest("redfin", fields)
        )
    }

    @Test
    fun `imported properties carry a content digest`() {
        val h = harness()
        val result = runBlocking { h.resolver.resolve(zillowListing) } as PropertyImportResult.Success
        assertEquals(64, result.property.contentDigest.length)
        assertEquals(
            result.property.contentDigest,
            h.ledger.find(result.property.canonicalId)!!.contentDigest
        )
    }

    @Test
    fun `a partial import is still idempotent`() {
        val h = harness(bodies = listOf(Fixtures.load("zillow_partial.html")))
        val first = runBlocking { h.resolver.resolve(zillowListing) }
        assertTrue(first is PropertyImportResult.Partial)

        val second = runBlocking { h.resolver.resolve(zillowVariant) }
        assertTrue(second is PropertyImportResult.Partial)
        assertEquals(1, h.transport.requests.size)
        assertEquals(ImportIdempotency.REPLAYED_PROPERTY, (second as PropertyImportResult.Partial).idempotency)
    }
}
