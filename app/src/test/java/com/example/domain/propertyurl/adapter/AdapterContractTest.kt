package com.example.domain.propertyurl.adapter

import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.FakeHttpFetcher
import com.example.domain.propertyurl.source.SourceCatalog
import com.example.domain.propertyurl.source.SourceRegistry
import com.example.domain.propertyurl.source.SourceStatus
import com.example.domain.propertyurl.model.SourceFailureCategory
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.testAdapterContext
import com.example.domain.propertyurl.url.PropertyUrlResolver
import com.example.domain.propertyurl.url.ResolvedPropertyUrl
import com.example.domain.propertyurl.url.UrlResolutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every adapter — shipped or future — must obey the same contract. That is what makes
 * "add Zillow/Redfin/Realtor/Homes later" a data change instead of a refactor.
 */
class AdapterContractTest {

    private val registry = SourceRegistry.build(SourceCatalog.ALL)
    private val resolver = PropertyUrlResolver(registry)

    private val shippedAdapters: List<PropertySourceAdapter> = listOf(
        ZillowAdapter(),
        RedfinAdapter(),
        RealtorComAdapter(),
        HomesComAdapter(),
        GenericWebListingAdapter()
    )

    private fun resolved(url: String): ResolvedPropertyUrl =
        (resolver.resolve(url) as UrlResolutionResult.Resolved).resolved

    @Test
    fun `descriptors are unique, complete and registered in the catalogue`() {
        val sourceIds = shippedAdapters.map { it.descriptor.sourceId }
        assertEquals("one adapter per source", sourceIds.size, sourceIds.distinct().size)
        assertNotNull("the generic fallback adapter must exist", PropertyAdapterRegistry.build(shippedAdapters).forSource("generic_web"))

        shippedAdapters.forEach { adapter ->
            val descriptor = adapter.descriptor
            assertTrue("adapter id required", descriptor.adapterId.isNotBlank())
            assertTrue("display name required", descriptor.displayName.isNotBlank())
            assertTrue("version required", descriptor.version.isNotBlank())
            assertNotNull(
                "adapter ${descriptor.adapterId} must map to a catalogue source",
                registry.byId(descriptor.sourceId)
            )
        }
    }

    @Test
    fun `registry rejects duplicate adapters for one source`() {
        val failure = runCatching { PropertyAdapterRegistry.build(listOf(ZillowAdapter(), ZillowAdapter())) }
        assertTrue("duplicate adapters must fail fast", failure.isFailure)
    }

    @Test
    fun `each portal adapter claims exactly its own listings`() {
        val cases = mapOf(
            "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/" to "zillow",
            "https://www.redfin.com/TX/Austin/1810-Guadalupe-St-12/home/145879032" to "redfin",
            "https://www.realtor.com/realestateandhomes-detail/4127-Oak-Hollow-Dr_Austin_TX_78745_M12345-67890" to "realtor_com",
            "https://www.homes.com/property/7200-birdhouse-ln-spicewood-tx/9k4j2lmn3pq1w/" to "homes_com"
        )

        cases.forEach { (url, expectedSourceId) ->
            val adapterRegistry = PropertyAdapterRegistry.build(shippedAdapters)
            val resolvedUrl = resolved(url)
            val adapter = adapterRegistry.forResolved(resolvedUrl)
            assertNotNull("no adapter claims $url", adapter)
            assertEquals("wrong adapter for $url", expectedSourceId, adapter!!.descriptor.sourceId)
            assertTrue("adapter must accept its own listing", adapter.supports(resolvedUrl))
        }
    }

    @Test
    fun `an unknown host is handled by the generic adapter`() {
        val adapterRegistry = PropertyAdapterRegistry.build(shippedAdapters)
        val resolvedUrl = resolved("https://some-broker.example/listings/4127-oak-hollow-dr")
        val adapter = adapterRegistry.forResolved(resolvedUrl)

        assertNotNull(adapter)
        assertEquals(PropertyAdapterRegistry.GENERIC_SOURCE_ID, adapter!!.descriptor.sourceId)
    }

    @Test
    fun `a 404 is a classified failure, never an exception`() = runBlocking {
        val url = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"
        val resolvedUrl = resolved(url)
        val fetcher = FakeHttpFetcher().on("zillow.com", status = 404, body = "<html><body>Listing not found</body></html>")
        val context = testAdapterContext("zillow", "zillow-html", fetcher = fetcher)
        val adapter = ZillowAdapter()

        val outcome = adapter.fetch(adapter.buildRequest(resolvedUrl, context), context)

        val failure = (outcome as SourceFetchOutcome.Failed).failure
        assertEquals(SourceFailureKind.HTTP_NOT_FOUND, failure.kind)
        assertEquals("zillow", failure.sourceId)
        assertFalse("removed listings must not be retried", failure.isRetryable)
    }

    @Test
    fun `an anti-bot interstitial is classified instead of parsed`() = runBlocking {
        val url = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"
        val resolvedUrl = resolved(url)
        val fetcher = FakeHttpFetcher().on("zillow.com", status = 403, body = Fixtures.text("anti-bot-page.html"))
        val context = testAdapterContext("zillow", "zillow-html", fetcher = fetcher)
        val adapter = ZillowAdapter()

        val outcome = adapter.fetch(adapter.buildRequest(resolvedUrl, context), context)

        val failure = (outcome as SourceFetchOutcome.Failed).failure
        assertEquals(SourceFailureCategory.ANTI_BOT, failure.kind.category)
        assertTrue(
            "bot protection must be recognised",
            failure.kind == SourceFailureKind.BOT_PROTECTION_CHALLENGE ||
                failure.kind == SourceFailureKind.HTTP_FORBIDDEN ||
                failure.isSourceLevel
        )
    }

    @Test
    fun `a successful fetch returns a document with the body and final url`() = runBlocking {
        val url = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"
        val resolvedUrl = resolved(url)
        val fetcher = FakeHttpFetcher().on("zillow.com", body = Fixtures.text("zillow-homedetails.html"))
        val context = testAdapterContext("zillow", "zillow-html", fetcher = fetcher)
        val adapter = ZillowAdapter()

        val outcome = adapter.fetch(adapter.buildRequest(resolvedUrl, context), context)

        val document = (outcome as SourceFetchOutcome.Fetched).document
        assertTrue(document.hasBody)
        assertEquals("the normalized url is what we fetch", resolvedUrl.url.normalized, document.finalUrl)
        assertTrue(document.isHtml)
        assertEquals(200, document.httpStatus)
    }

    @Test
    fun `parsers never throw on hostile or empty documents`() {
        val empty = Fixtures.document("zillow-homedetails.html", ZILLOW_URL).copy(body = "", bodyBytes = 0)
        val junk = Fixtures.document("zillow-homedetails.html", ZILLOW_URL)
            .copy(body = "<html><body><script>throw new Error('boom')</script></body></html>")

        shippedAdapters.forEach { adapter ->
            val context = testAdapterContext(adapter.descriptor.sourceId, adapter.descriptor.adapterId)
            listOf(empty, junk).forEach { document ->
                val outcome = adapter.parse(document, context)
                assertFalse(
                    "${adapter.descriptor.adapterId} claimed facts from an empty document",
                    outcome.hasFacts
                )
            }
        }
    }

    @Test
    fun `adapters use the shared parser chain on the fixtures`() {
        val zillow = ZillowAdapter()
        val context = testAdapterContext("zillow", zillow.descriptor.adapterId)
        val outcome = zillow.parse(Fixtures.document("zillow-homedetails.html", ZILLOW_URL), context)

        assertTrue(outcome.hasFacts)
        assertTrue(
            "the shared parser chain must run",
            outcome.usedParsers.any { it.startsWith("schema-org-json-ld") }
        )
        assertTrue(
            "the adapter-local zpid parser must be part of the chain",
            zillow.extraParsers().any { it.parserId == "zillow-zpid" }
        )
        // The chain short-circuits once the canonical essentials are covered, so a document without
        // structured data falls through to the embedded-state parser instead.
        val embeddedOnly = Fixtures.document("homes-com-listing.html", ZILLOW_URL)
        val fromState = zillow.parse(embeddedOnly, context)
        assertTrue(fromState.hasFacts)
        assertTrue(fromState.usedParsers.any { it.startsWith("embedded-json-state") })
    }

    @Test
    fun `adding the next portal is a definition plus a subclass`() {
        // This is the documented extension path: the template adapter must be registry-compatible.
        val future = FutureSourceAdapters.apartmentsCom()

        assertEquals("apartments_com", future.descriptor.sourceId)
        assertEquals(SourceStatus.PLANNED, registry.byId("apartments_com")?.status)
        val adapterRegistry = PropertyAdapterRegistry.build(shippedAdapters + future)
        assertNotNull(adapterRegistry.forSource("apartments_com"))
    }

    private companion object {
        const val ZILLOW_URL = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"
    }
}
