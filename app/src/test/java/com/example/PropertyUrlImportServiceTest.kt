package com.example

import com.example.data.urlintelligence.PropertyUrlImportService
import com.example.data.urlintelligence.UrlIntelligenceModule
import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.resolver.ResolveOptions
import com.example.urlintelligence.source.KnownSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Application-side smoke tests for the URL import pipeline: wiring, policy gating
 * and persistence hand-off, with a scripted transport instead of the network.
 */
class PropertyUrlImportServiceTest {

    private val zillowDocument = """
        <!doctype html>
        <html><head>
        <meta property="og:title" content="2418 S Congress Ave, Austin, TX 78704">
        <script type="application/ld+json">
        {
          "@context": "https://schema.org",
          "@type": "SingleFamilyResidence",
          "address": {
            "@type": "PostalAddress",
            "streetAddress": "2418 S Congress Ave",
            "addressLocality": "Austin",
            "addressRegion": "TX",
            "postalCode": "78704"
          },
          "numberOfBedrooms": 4,
          "numberOfBathroomsTotal": 3,
          "floorSize": { "@type": "QuantitativeValue", "value": 2250 },
          "yearBuilt": 2017,
          "offers": { "@type": "Offer", "price": "485000", "priceCurrency": "USD",
                      "availability": "https://schema.org/InStock" }
        }
        </script>
        </head><body><h1>2418 S Congress Ave</h1></body></html>
    """.trimIndent()

    private val url = "https://www.zillow.com/homedetails/2418-S-Congress-Ave-Austin-TX-78704/12345678_zpid/"

    private class ScriptedTransport(private val body: String) : PropertyHttpTransport {
        val requests = mutableListOf<SourceFetchRequest>()

        override suspend fun execute(request: SourceFetchRequest): SourceFetchResponse {
            requests += request
            return SourceFetchResponse.Success(
                statusCode = 200,
                body = body,
                contentType = "text/html",
                finalUrl = request.requestUrl,
                fetchedAtEpochMillis = 1_700_000_000_000L
            )
        }
    }

    @Test
    fun `registry is wired with every source adapter`() {
        val registry = UrlIntelligenceModule.createRegistry(ScriptedTransport(zillowDocument))
        assertEquals(KnownSources.all().size, registry.descriptors().size)
        KnownSources.withAdapters().forEach { descriptor ->
            assertTrue(
                "${descriptor.id} must have an adapter registered",
                registry.adapterFor(descriptor.id) != null
            )
        }
    }

    @Test
    fun `import resolves a url and reports success without persistence`() {
        val transport = ScriptedTransport(zillowDocument)
        val service = PropertyUrlImportService(UrlIntelligenceModule.createResolver(transport))

        val outcome = runBlocking { service.import(url) }

        assertTrue("expected success but was $outcome", outcome is PropertyUrlImportService.Outcome.Success)
        val success = outcome as PropertyUrlImportService.Outcome.Success
        assertEquals("zillow:12345678", success.property.canonicalId)
        assertEquals(485_000.0, success.property.listPriceUsd!!, 0.001)
        assertEquals("Austin", success.property.address.city)
        assertEquals(1, transport.requests.size)
        assertEquals(false, success.persisted)
        assertTrue(success.property.provenance.size > 3)
    }

    @Test
    fun `importing twice performs a single fetch`() {
        val transport = ScriptedTransport(zillowDocument)
        val service = PropertyUrlImportService(UrlIntelligenceModule.createResolver(transport))

        runBlocking {
            service.import(url)
            service.import(url)
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `invalid urls are rejected before any request`() {
        val transport = ScriptedTransport(zillowDocument)
        val service = PropertyUrlImportService(UrlIntelligenceModule.createResolver(transport))

        val outcome = runBlocking { service.import("https://zillow.com") }
        assertTrue(outcome is PropertyUrlImportService.Outcome.Failure)
        val failure = outcome as PropertyUrlImportService.Outcome.Failure
        assertEquals("INVALID_URL", failure.code)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `partial results stay usable and list what is missing`() {
        val partialDocument = """
            <!doctype html>
            <html><head>
            <script type="application/ld+json">
            { "@context": "https://schema.org", "@type": "Residence",
              "offers": { "@type": "Offer", "price": "512000" } }
            </script>
            </head><body></body></html>
        """.trimIndent()
        val transport = ScriptedTransport(partialDocument)
        val service = PropertyUrlImportService(UrlIntelligenceModule.createResolver(transport))

        val outcome = runBlocking { service.import(url) }
        assertTrue("expected partial but was $outcome", outcome is PropertyUrlImportService.Outcome.Partial)
        val partial = outcome as PropertyUrlImportService.Outcome.Partial
        assertTrue(partial.missingFields.isNotEmpty())
        assertEquals(512_000.0, partial.property.listPriceUsd!!, 0.001)
    }

    @Test
    fun `opt in gating blocks sources that are not allow listed`() {
        val transport = ScriptedTransport(zillowDocument)
        val resolver = UrlIntelligenceModule.createResolver(transport, allowedSourceIds = emptySet())
        val service = PropertyUrlImportService(resolver)

        val outcome = runBlocking { service.import(url) }
        assertTrue(outcome is PropertyUrlImportService.Outcome.Failure)
        assertEquals("POLICY_BLOCKED", (outcome as PropertyUrlImportService.Outcome.Failure).code)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `callers can refuse partial data`() {
        val partialDocument = """
            <!doctype html>
            <html><head>
            <script type="application/ld+json">
            { "@context": "https://schema.org", "@type": "Residence",
              "offers": { "@type": "Offer", "price": "512000" } }
            </script>
            </head><body></body></html>
        """.trimIndent()
        val transport = ScriptedTransport(partialDocument)
        val service = PropertyUrlImportService(UrlIntelligenceModule.createResolver(transport))

        val outcome = runBlocking {
            service.import(url, ResolveOptions(allowPartial = false))
        }
        assertTrue(outcome is PropertyUrlImportService.Outcome.Failure)
        assertEquals("INCOMPLETE_DATA", (outcome as PropertyUrlImportService.Outcome.Failure).code)
    }
}
