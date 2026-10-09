package com.example.urlintelligence

import com.example.urlintelligence.adapter.GenericWebAdapter
import com.example.urlintelligence.adapter.HomesAdapter
import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.RealtorAdapter
import com.example.urlintelligence.adapter.RedfinAdapter
import com.example.urlintelligence.adapter.ZillowAdapter
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.idempotency.IdempotencyKey
import com.example.urlintelligence.idempotency.IdempotencyRecord
import com.example.urlintelligence.idempotency.IdempotencyState
import com.example.urlintelligence.idempotency.IdempotencyStore
import com.example.urlintelligence.idempotency.InMemoryIdempotencyStore
import com.example.urlintelligence.job.ImportJobState
import com.example.urlintelligence.job.InMemoryPropertyImportJobStore
import com.example.urlintelligence.job.PropertyImportJobStore
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.resolver.PropertyImportResult
import com.example.urlintelligence.resolver.PropertyUrlResolver
import com.example.urlintelligence.resolver.ResolveOptions
import com.example.urlintelligence.retry.RecordingSleeper
import com.example.urlintelligence.retry.RetryPolicy
import com.example.urlintelligence.source.KnownSources
import com.example.urlintelligence.source.SourceDetector
import com.example.urlintelligence.source.SourceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end resolver tests with a scripted transport: validation, detection,
 * retry, idempotency, partial success and failure classification — all without
 * touching the network.
 */
class PropertyUrlResolverTest {

    private val clock = TestClock()
    private val sleeper = RecordingSleeper()
    private val jobStore: PropertyImportJobStore = InMemoryPropertyImportJobStore()
    private val resultStore: IdempotencyStore<PropertyImportResult> = InMemoryIdempotencyStore(clock)

    private val allSourceIds = KnownSources.all().map { it.id }.toSet()

    private fun resolver(
        transport: PropertyHttpTransport,
        allowed: Set<String> = allSourceIds,
        policy: RetryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMillis = 1L, jitterRatio = 0.0),
        adapters: Boolean = true
    ): PropertyUrlResolver {
        val registry = SourceRegistry(
            descriptors = KnownSources.all(),
            adapters = if (adapters) {
                listOf(
                    ZillowAdapter(transport, clock),
                    RedfinAdapter(transport, clock),
                    RealtorAdapter(transport, clock),
                    HomesAdapter(transport, clock),
                    GenericWebAdapter(transport, clock)
                )
            } else emptyList()
        )
        return PropertyUrlResolver(
            registry = registry,
            detector = SourceDetector(registry.descriptors()),
            normalizer = com.example.urlintelligence.normalization.PropertyNormalizer(clock),
            retryPolicy = policy,
            clock = clock,
            sleeper = sleeper,
            jobStore = jobStore,
            resultStore = resultStore,
            allowedSourceIds = allowed,
            random = { 0.0 },
            idGenerator = { "job-${jobStore.size() + 1}" }
        )
    }

    private fun run(
        resolver: PropertyUrlResolver,
        url: String,
        options: ResolveOptions = ResolveOptions()
    ): PropertyImportResult = runBlocking { resolver.resolve(url, options) }

    @Test
    fun `resolves a zillow url end to end`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val result = run(resolver(transport), Fixtures.ZILLOW_URL)

        assertTrue("expected success but was $result", result is PropertyImportResult.Success)
        val success = result as PropertyImportResult.Success
        assertEquals("zillow:12345678", success.property.canonicalId)
        assertEquals(485000.0, success.property.listPriceUsd!!, 0.001)
        assertEquals("Austin", success.property.address.city)
        assertEquals(1, transport.requests.size)
        assertEquals(1, success.attempts)
        assertTrue(success.property.provenance.contains(PropertyField.LIST_PRICE))

        val job = jobStore.findById(success.jobId)
        assertNotNull(job)
        assertEquals(ImportJobState.SUCCEEDED, job!!.state)
        assertEquals("zillow", job.sourceId)
        assertEquals("12345678", job.sourcePropertyId)
    }

    @Test
    fun `credential-like query values are omitted from fetched and persisted job URLs`() {
        val fakeCredentialValue = "unit-test-query-token"
        val input = "${Fixtures.ZILLOW_URL}?token=$fakeCredentialValue&listing_id=123"
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val result = run(resolver(transport), input)

        assertTrue("expected success but was $result", result is PropertyImportResult.Success)
        val success = result as PropertyImportResult.Success
        assertEquals(1, transport.requests.size)
        assertFalse(transport.requests.single().requestUrl.contains(fakeCredentialValue))

        val storedJob = jobStore.findById(success.jobId)
        assertNotNull(storedJob)
        assertFalse(storedJob!!.rawUrl.contains(fakeCredentialValue))
        assertTrue(storedJob.rawUrl.contains("REDACTED"))
    }

    @Test
    fun `embedded URL credentials are redacted before invalid jobs are persisted`() {
        val fakeCredentialValue = "unit-test-embedded-password"
        val input = "https://listing-user:$fakeCredentialValue@www.zillow.com/homedetails/12345678_zpid/"
        val result = run(resolver(FakeTransport()), input)

        assertTrue(result is PropertyImportResult.Failure)
        val jobId = (result as PropertyImportResult.Failure).jobId
        assertNotNull(jobId)
        val storedJob = jobStore.findById(jobId!!)
        assertNotNull(storedJob)
        assertFalse(storedJob!!.rawUrl.contains(fakeCredentialValue))
        assertFalse(storedJob.rawUrl.contains("listing-user"))
    }

    @Test
    fun `second resolve of the same url is served from the idempotency cache`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val resolver = resolver(transport)

        val first = run(resolver, Fixtures.ZILLOW_URL)
        val second = run(resolver, "  ${Fixtures.ZILLOW_URL}?utm_source=share  ")

        assertTrue(first is PropertyImportResult.Success)
        assertTrue(second is PropertyImportResult.Success)
        assertEquals(1, transport.requests.size)
        assertEquals((first as PropertyImportResult.Success).property.canonicalId,
            (second as PropertyImportResult.Success).property.canonicalId)
    }

    @Test
    fun `refresh bypasses the cache`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val resolver = resolver(transport)
        run(resolver, Fixtures.ZILLOW_URL)
        val refreshed = run(resolver, Fixtures.ZILLOW_URL, ResolveOptions(refresh = true))
        assertTrue(refreshed is PropertyImportResult.Success)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `an in flight import is reported as a conflict`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val resolver = resolver(transport)
        val canonical = resolver.inspect(Fixtures.ZILLOW_URL).normalizedUrl!!.canonical
        resultStore.putIfAbsent(
            IdempotencyKey.forUrl(canonical),
            IdempotencyRecord(
                key = IdempotencyKey.forUrl(canonical),
                state = IdempotencyState.IN_FLIGHT,
                createdAtEpochMillis = clock.now(),
                updatedAtEpochMillis = clock.now()
            )
        )

        val result = run(resolver, Fixtures.ZILLOW_URL)
        assertTrue(result is PropertyImportResult.Failure)
        assertTrue((result as PropertyImportResult.Failure).failure is SourceFailure.Conflict)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `partial data yields a usable partial result`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_partial.html")))
        val result = run(resolver(transport), Fixtures.ZILLOW_URL)

        assertTrue("expected partial but was $result", result is PropertyImportResult.Partial)
        val partial = result as PropertyImportResult.Partial
        assertNotNull(partial.propertyOrNull)
        assertTrue(partial.missingFields.contains(PropertyField.ADDRESS_LINE1))
        assertTrue(partial.missingFields.contains(PropertyField.POSTAL_CODE))
        assertEquals(512000.0, partial.property.listPriceUsd!!, 0.001)
        assertTrue(partial.warnings.isNotEmpty())

        val job = jobStore.findById(partial.jobId)
        assertEquals(ImportJobState.PARTIALLY_SUCCEEDED, job!!.state)
    }

    @Test
    fun `caller can reject partial data`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_partial.html")))
        val result = run(resolver(transport), Fixtures.ZILLOW_URL, ResolveOptions(allowPartial = false))

        assertTrue(result is PropertyImportResult.Failure)
        assertTrue((result as PropertyImportResult.Failure).failure is SourceFailure.IncompleteData)
        assertEquals(ImportJobState.FAILED, jobStore.findByState(ImportJobState.FAILED).first().state)
        assertEquals(0, resultStore.size())
    }

    @Test
    fun `transient failures are retried and then succeed`() {
        val transport = FakeTransport(
            bodies = listOf(Fixtures.load("zillow_listing.html")),
            failures = listOf(
                SourceFailure.Network("connection reset"),
                SourceFailure.HttpStatus(503, "unavailable"),
                null
            )
        )
        val result = run(resolver(transport), Fixtures.ZILLOW_URL)

        assertTrue("expected success after retries but was $result", result is PropertyImportResult.Success)
        assertEquals(3, (result as PropertyImportResult.Success).attempts)
        assertEquals(3, transport.requests.size)
        assertEquals(2, sleeper.delays.size)
        assertEquals(3, transport.requests.last().attempt)
    }

    @Test
    fun `permanent failures are not retried`() {
        val transport = FakeTransport(statuses = listOf(404))
        val result = run(resolver(transport), Fixtures.ZILLOW_URL)

        assertTrue(result is PropertyImportResult.Failure)
        val failure = (result as PropertyImportResult.Failure).failure
        assertTrue("expected NotFound but was $failure", failure is SourceFailure.NotFound)
        assertEquals(1, transport.requests.size)
        assertEquals(1, result.attempts)
        assertTrue(sleeper.delays.isEmpty())
        assertEquals(0, resultStore.size())
    }

    @Test
    fun `rate limited responses honor retry after and exhaust the budget`() {
        val transport = FakeTransport(
            failures = listOf(SourceFailure.RateLimited("slow down", 25L))
        )
        val result = run(
            resolver(transport, policy = RetryPolicy(maxAttempts = 2, initialDelayMillis = 5L, jitterRatio = 0.0)),
            Fixtures.ZILLOW_URL
        )

        assertTrue(result is PropertyImportResult.Failure)
        assertTrue((result as PropertyImportResult.Failure).failure is SourceFailure.RateLimited)
        assertEquals(2, transport.requests.size)
        assertEquals(listOf(25L), sleeper.delays)
    }

    @Test
    fun `anti bot pages are classified as blocked and never retried`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("blocked_captcha.html")))
        val result = run(resolver(transport), Fixtures.ZILLOW_URL)

        assertTrue(result is PropertyImportResult.Failure)
        val failure = (result as PropertyImportResult.Failure).failure
        assertTrue(failure is SourceFailure.Blocked)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `invalid urls fail before any network call`() {
        val transport = FakeTransport()
        val resolver = resolver(transport)

        listOf(
            "   ",
            "javascript:alert(1)",
            "http://localhost/admin",
            "https://zillow.com"
        ).forEach { url ->
            val result = run(resolver, url)
            assertTrue("$url should fail validation", result is PropertyImportResult.Failure)
            assertTrue((result as PropertyImportResult.Failure).failure is SourceFailure.InvalidUrl)
        }
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `unknown sources without an adapter are reported as unsupported`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val result = run(resolver(transport, adapters = false), Fixtures.ZILLOW_URL)
        assertTrue(result is PropertyImportResult.Failure)
        val failure = (result as PropertyImportResult.Failure).failure
        assertTrue("expected UnsupportedSource but was $failure", failure is SourceFailure.UnsupportedSource)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `sources that require opt in are blocked unless allow listed`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val blocked = run(resolver(transport, allowed = emptySet()), Fixtures.ZILLOW_URL)
        assertTrue(blocked is PropertyImportResult.Failure)
        assertTrue((blocked as PropertyImportResult.Failure).failure is SourceFailure.PolicyBlocked)
        assertEquals(0, transport.requests.size)

        val allowed = run(resolver(transport, allowed = setOf("zillow")), Fixtures.ZILLOW_URL)
        assertTrue(allowed is PropertyImportResult.Success)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `credential headers are stripped before the request leaves`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        run(
            resolver(transport),
            Fixtures.ZILLOW_URL,
            ResolveOptions(headers = mapOf("Authorization" to "Bearer secret", "Accept-Language" to "en-US"))
        )
        val headers = transport.requests.first().headers
        assertFalse(headers.keys.any { it.equals("authorization", ignoreCase = true) })
        assertTrue(headers["Accept-Language"] == "en-US")
    }

    @Test
    fun `generic sources are imported without an opt in`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("generic_brokerage.html")))
        val result = run(resolver(transport, allowed = emptySet()), Fixtures.GENERIC_URL)
        assertTrue("expected success but was $result", result is PropertyImportResult.Success)
        val property = (result as PropertyImportResult.Success).property
        assertEquals(615000.0, property.listPriceUsd!!, 0.001)
        assertEquals("Denver", property.address.city)
        assertEquals("CO", property.address.stateOrProvince)
    }

    @Test
    fun `inspection reports support without fetching`() {
        val transport = FakeTransport()
        val resolver = resolver(transport)

        val inspection = resolver.inspect(Fixtures.ZILLOW_URL)
        assertTrue(inspection.isValid)
        assertEquals("zillow", inspection.detection!!.sourceId)
        assertTrue(inspection.adapterRegistered)
        assertTrue(inspection.requiresOptIn)
        assertTrue(inspection.importAllowed)

        val unknown = resolver.inspect("https://example-brokerage.test/listings/4821")
        assertTrue(unknown.isValid)
        assertEquals("generic.web", unknown.detection!!.sourceId)
        assertTrue(unknown.importAllowed)

        val invalid = resolver.inspect("not a url")
        assertFalse(invalid.isValid)
        assertFalse(invalid.importAllowed)
        assertNotNull(invalid.error)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `batch import resolves every url independently`() {
        val transport = FakeTransport(
            bodies = listOf(
                Fixtures.load("zillow_listing.html"),
                Fixtures.load("redfin_listing.html")
            ),
            statuses = listOf(200)
        )
        val results = runBlocking {
            resolver(transport).resolveAll(listOf(Fixtures.ZILLOW_URL, Fixtures.REDFIN_URL))
        }
        assertEquals(2, results.size)
        assertTrue(results[0] is PropertyImportResult.Success)
        assertTrue(results[1] is PropertyImportResult.Success)
        assertEquals("zillow:12345678", results[0].propertyOrNull!!.canonicalId)
        assertEquals("redfin:98765432", results[1].propertyOrNull!!.canonicalId)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `failures keep the job history for later inspection`() {
        val transport = FakeTransport(statuses = listOf(500))
        val result = run(resolver(transport), Fixtures.ZILLOW_URL)

        val job = jobStore.findById((result as PropertyImportResult.Failure).jobId!!)
        assertNotNull(job)
        assertEquals(ImportJobState.FAILED, job!!.state)
        assertNotNull(job.failure)
        assertTrue(job.transitions.any { it.event == com.example.urlintelligence.job.ImportJobEvent.FETCH_FAILED })
        assertTrue(job.transitions.any { it.event == com.example.urlintelligence.job.ImportJobEvent.SOURCE_DETECTED })
    }
}
