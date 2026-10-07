package com.example.urlintelligence

import com.example.urlintelligence.adapter.DeclarativeSourceAdapter
import com.example.urlintelligence.adapter.GenericWebAdapter
import com.example.urlintelligence.compliance.AccessPolicy
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.idempotency.InMemoryIdempotencyStore
import com.example.urlintelligence.job.InMemoryPropertyImportJobStore
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.resolver.PropertyImportResult
import com.example.urlintelligence.resolver.PropertyUrlResolver
import com.example.urlintelligence.resolver.ResolveOptions
import com.example.urlintelligence.source.KnownSources
import com.example.urlintelligence.source.SourceDescriptor
import com.example.urlintelligence.source.SourceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extensibility proof: a brand-new provider is added here — in test code — using only public
 * building blocks (a [SourceDescriptor], a [ParserSpec] and a selector table handed to
 * [DeclarativeSourceAdapter]). Nothing in the resolver, job machine, retry, provenance,
 * idempotency or compliance layers had to change, which is the property the architecture
 * promises for "Zillow, Redfin, Realtor, Homes.com and future sources".
 */
class ProviderExtensionTest {

    private fun registry(transport: FakeTransport, allowed: Boolean): SourceRegistry =
        SourceRegistry(
            descriptors = listOf(ExamplePortal.DESCRIPTOR, KnownSources.GENERIC),
            adapters = listOf(
                DeclarativeSourceAdapter(transport, ExamplePortal.DESCRIPTOR, ExamplePortal.PARSER_SPEC, ExamplePortal.SELECTORS),
                GenericWebAdapter(transport)
            )
        ).also {
            if (!allowed) it.unregister(ExamplePortal.SOURCE_ID)
        }

    private fun resolver(
        transport: FakeTransport,
        allowPortal: Boolean = true
    ): PropertyUrlResolver {
        val registry = SourceRegistry(
            descriptors = listOf(ExamplePortal.DESCRIPTOR, KnownSources.GENERIC),
            adapters = listOf(
                DeclarativeSourceAdapter(transport, ExamplePortal.DESCRIPTOR, ExamplePortal.PARSER_SPEC, ExamplePortal.SELECTORS),
                GenericWebAdapter(transport)
            )
        )
        return PropertyUrlResolver(
            registry = registry,
            normalizer = PropertyNormalizer(TestClock()),
            clock = TestClock(),
            jobStore = InMemoryPropertyImportJobStore(),
            resultStore = InMemoryIdempotencyStore(TestClock()),
            allowedSourceIds = if (allowPortal) setOf(ExamplePortal.SOURCE_ID, "generic.web") else setOf("generic.web"),
            accessPolicy = AccessPolicy.PERMISSIVE_PUBLIC_WEB,
            idGenerator = { "job-portal" },
            sleeper = com.example.urlintelligence.retry.RecordingSleeper()
        )
    }

    @Test
    fun `a new provider is added with a descriptor a spec and a selector table`() {
        val transport = FakeTransport(
            bodiesByUrl = mapOf(ExamplePortal.URL to Fixtures.load(ExamplePortal.FIXTURE))
        )
        val result = runBlocking { resolver(transport).resolve(ExamplePortal.URL) }
        assertTrue("expected success but was $result", result is PropertyImportResult.Success)
        val property = (result as PropertyImportResult.Success).property

        assertEquals(ExamplePortal.SOURCE_ID, property.sourceId)
        assertEquals("example-portal:ep-88213", property.canonicalId)
        assertEquals(412_000.0, property.listPriceUsd!!, 0.001)
        assertEquals("Pittsburgh", property.address.city)
        assertEquals("18 Foundry Row", property.address.line1)
        assertEquals("15222", property.address.postalCode)
        assertEquals("EP-88213", property.mlsId)

        // Provenance and verification follow the same rules as the built-in providers.
        assertEquals("example-portal.html@1", property.parserVersions.single())
        assertTrue(property.isParserVerified)
        assertFalse(property.isLiveVerified)
        val entry = property.provenance[PropertyField.LIST_PRICE]!!
        assertEquals(ExamplePortal.SOURCE_ID, entry.sourceId)
        assertEquals("example-portal.html", entry.parserId)
    }

    @Test
    fun `the detection layer resolves the new provider without a code change`() {
        val detection = com.example.urlintelligence.source.SourceDetector(listOf(ExamplePortal.DESCRIPTOR, KnownSources.GENERIC))
            .detectCanonical(ExamplePortal.URL)
        assertEquals(ExamplePortal.SOURCE_ID, detection.sourceId)
        assertEquals("EP-88213", detection.sourcePropertyId)
        assertTrue(detection.confidence > 0.7)
    }

    @Test
    fun `a provider that is announced but not implemented fails as unsupported`() {
        val announced = SourceDescriptor(
            id = "future-portal",
            displayName = "Future Portal",
            hostSuffixes = listOf("future-portal.test")
        )
        val registry = SourceRegistry(
            descriptors = listOf(announced, KnownSources.GENERIC),
            adapters = listOf(GenericWebAdapter(FakeTransport()))
        )
        val resolver = PropertyUrlResolver(
            registry = registry,
            normalizer = PropertyNormalizer(TestClock()),
            clock = TestClock(),
            jobStore = InMemoryPropertyImportJobStore(),
            resultStore = InMemoryIdempotencyStore(TestClock()),
            accessPolicy = AccessPolicy.PERMISSIVE_PUBLIC_WEB,
            idGenerator = { "job-future" },
            sleeper = com.example.urlintelligence.retry.RecordingSleeper()
        )

        val result = runBlocking { resolver.resolve("https://www.future-portal.test/listing/42") }
        assertTrue(result is PropertyImportResult.Failure)
        val failure = (result as PropertyImportResult.Failure).failure
        assertTrue(failure is SourceFailure.UnsupportedSource)
        assertEquals("future-portal", (failure as SourceFailure.UnsupportedSource).sourceId)
        assertTrue(registry.isAnnouncedOnly("future-portal"))
    }

    @Test
    fun `a new provider still respects the opt-in gate`() {
        val transport = FakeTransport(
            bodiesByUrl = mapOf(ExamplePortal.URL to Fixtures.load(ExamplePortal.FIXTURE))
        )
        val result = runBlocking { resolver(transport, allowPortal = false).resolve(ExamplePortal.URL) }

        assertTrue(result is PropertyImportResult.Failure)
        assertTrue((result as PropertyImportResult.Failure).failure is SourceFailure.PolicyBlocked)
        assertEquals("a refused source must not be fetched", 0, transport.requests.size)
    }

    @Test
    fun `the registry binds descriptors and adapters together`() {
        val transport = FakeTransport()
        val registry = registry(transport, allowed = true)
        val registered = registry.registeredSources().first { it.descriptor.id == ExamplePortal.SOURCE_ID }
        assertTrue(registered.isResolvable)
        assertEquals(ExamplePortal.SOURCE_ID, registered.adapter!!.descriptor.id)
        assertNotNull(registry.adapterFor(ExamplePortal.SOURCE_ID))
        assertEquals(ExamplePortal.SOURCE_ID, registry.descriptorFor(ExamplePortal.SOURCE_ID)!!.id)
        assertTrue(registry.resolvableSourceIds().contains(ExamplePortal.SOURCE_ID))
    }

    @Test
    fun `the declarative adapter is equivalent to a hand written one`() {
        val body = Fixtures.load(ExamplePortal.FIXTURE)
        val request = com.example.urlintelligence.adapter.SourceFetchRequest(ExamplePortal.URL, "corr")
        val response = com.example.urlintelligence.adapter.SourceFetchResponse.Success(
            statusCode = 200,
            body = body,
            contentType = "text/html",
            finalUrl = ExamplePortal.URL,
            fetchedAtEpochMillis = 0L
        )
        val declarative = DeclarativeSourceAdapter(FakeTransport(), ExamplePortal.DESCRIPTOR, ExamplePortal.PARSER_SPEC, ExamplePortal.SELECTORS)
        val result = declarative.parse(response, request)
        assertTrue(result is com.example.urlintelligence.adapter.PropertyParseResult.Success)

        val draft = (result as com.example.urlintelligence.adapter.PropertyParseResult.Success).draft
        assertEquals(ExamplePortal.SOURCE_ID, draft.sourceId)
        assertEquals("EP-88213", draft.sourcePropertyId)
        assertEquals(412_000.0, draft.double(PropertyField.LIST_PRICE)!!, 0.001)
        assertEquals("example-portal.html", draft.parserId)
    }

    @Test
    fun `unknown hosts still fall back to the generic parser`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("generic_brokerage.html")))
        val registry = SourceRegistry(
            descriptors = KnownSources.all(),
            adapters = listOf(GenericWebAdapter(transport))
        )
        val resolver = PropertyUrlResolver(
            registry = registry,
            normalizer = PropertyNormalizer(TestClock()),
            clock = TestClock(),
            jobStore = InMemoryPropertyImportJobStore(),
            resultStore = InMemoryIdempotencyStore(TestClock()),
            accessPolicy = AccessPolicy.PERMISSIVE_PUBLIC_WEB,
            idGenerator = { "job-generic" },
            sleeper = com.example.urlintelligence.retry.RecordingSleeper()
        )
        val result = runBlocking { resolver.resolve(Fixtures.GENERIC_URL) }
        assertTrue(result is PropertyImportResult.Success)
        assertEquals("generic.web", (result as PropertyImportResult.Success).property.sourceId)
    }

    @Test
    fun `provider notes record the opt-in decision for operators`() {
        assertTrue(ExamplePortal.DESCRIPTOR.requiresOptIn)
        assertTrue(ExamplePortal.DESCRIPTOR.notes!!.contains("no core change"))
        assertTrue(
            "shipped providers must state their opt-in requirement",
            KnownSources.all().filter { it.id != SourceDescriptor.GENERIC_SOURCE_ID }
                .all { it.requiresOptIn }
        )
    }

    @Test
    fun `resolver options can require a policy for a newly added provider too`() {
        val transport = FakeTransport(
            bodiesByUrl = mapOf(ExamplePortal.URL to Fixtures.load(ExamplePortal.FIXTURE))
        )
        val result = runBlocking {
            resolver(transport).resolve(ExamplePortal.URL, ResolveOptions(requireAccessPolicy = true))
        }
        assertTrue(result is PropertyImportResult.Success)
    }
}
