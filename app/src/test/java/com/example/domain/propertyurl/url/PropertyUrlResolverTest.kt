package com.example.domain.propertyurl.url

import com.example.domain.propertyurl.source.SourceCatalog
import com.example.domain.propertyurl.source.SourceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver is the front door: it accepts whatever a user pastes (a bare URL, a share-sheet text,
 * an email body) and turns it into a normalized, source-attributed listing URL — or a typed rejection.
 * These tests pin the accept/reject boundary, which is what protects the rest of the pipeline.
 */
class PropertyUrlResolverTest {

    private val registry = SourceRegistry.build(SourceCatalog.ALL)
    private val resolver = PropertyUrlResolver(registry)

    @Test
    fun `bare zillow link is validated, normalized and attributed`() {
        val result = resolver.resolve("https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/?utm_source=newsletter&utm_medium=email")

        val resolved = (result as UrlResolutionResult.Resolved).resolved
        assertEquals("zillow", resolved.sourceId)
        assertEquals("20451237", resolved.externalListingId)
        assertEquals("zillow:20451237", resolved.idempotencyKey)
        assertTrue("tracking params must be stripped", !resolved.url.normalized.contains("utm_source"))
        assertTrue(resolved.isSourceSupported)
    }

    @Test
    fun `share-sheet text with surrounding chatter still resolves`() {
        val text = """
            Hey! Found this one on Zillow, looks promising for the client:
            https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/
            Want me to schedule a tour?
        """.trimIndent()

        val resolved = (resolver.resolve(text) as UrlResolutionResult.Resolved).resolved
        assertEquals("zillow", resolved.sourceId)
        assertEquals("20451237", resolved.externalListingId)
    }

    @Test
    fun `two different listings in one message are reported as ambiguous`() {
        val text = """
            Comparing two options:
            https://www.zillow.com/homedetails/1/20451237_zpid/
            https://www.redfin.com/TX/Austin/1810-Guadalupe-St-12/home/145879032
        """.trimIndent()

        val result = resolver.resolve(text)

        assertTrue("several listings must not silently pick one", result is UrlResolutionResult.Ambiguous)
        assertEquals(2, (result as UrlResolutionResult.Ambiguous).candidates.size)
    }

    @Test
    fun `the same link twice is not ambiguous`() {
        val text = "https://www.zillow.com/homedetails/1/20451237_zpid/ and again https://www.zillow.com/homedetails/1/20451237_zpid/?utm_source=x"

        val result = resolver.resolve(text)

        assertTrue(result is UrlResolutionResult.Resolved)
    }

    @Test
    fun `redfin and homes listing ids come from their own url shapes`() {
        val redfin = (resolver.resolve("https://www.redfin.com/TX/Austin/1810-Guadalupe-St-12/home/145879032") as UrlResolutionResult.Resolved).resolved
        assertEquals("redfin", redfin.sourceId)
        assertEquals("145879032", redfin.externalListingId)

        val homes = resolver.resolve("https://www.homes.com/property/7200-birdhouse-ln-spicewood-tx/9k4j2lmn3pq1w/")
        assertEquals("homes_com", (homes as UrlResolutionResult.Resolved).resolved.sourceId)
    }

    @Test
    fun `unknown but valid property page falls back to the generic source`() {
        val resolved = (
            resolver.resolve("https://www.some-brokerage.com/listings/4127-oak-hollow-dr-austin-tx") as
                UrlResolutionResult.Resolved
            ).resolved

        val sourceId = resolved.sourceId
        assertTrue("generic pages must still import", sourceId == null || sourceId == "generic_web")
        assertTrue(
            "normalization may drop www, but the listing path must survive",
            resolved.url.normalized.startsWith("https://some-brokerage.com/listings/")
        )
    }

    @Test
    fun `text without a link is rejected with a typed issue`() {
        val result = resolver.resolve("call me about the property on Oak Hollow")

        assertTrue(result is UrlResolutionResult.Rejected)
        val rejected = result as UrlResolutionResult.Rejected
        assertEquals(UrlValidationCode.NOT_A_URL, rejected.primaryCode)
        assertTrue(rejected.summary.isNotBlank())
    }

    @Test
    fun `javascript and file URLs are rejected`() {
        listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "ftp://example.com/listing"
        ).forEach { input ->
            val result = resolver.resolve(input)
            assertTrue("$input must not resolve", result !is UrlResolutionResult.Resolved)
        }
    }

    @Test
    fun `cleartext property URLs are rejected before fetch`() {
        val result = resolver.resolve("http://www.zillow.com/homedetails/12345678_zpid")
        assertTrue(result is UrlResolutionResult.Rejected)
        assertEquals(UrlValidationCode.UNSUPPORTED_SCHEME, (result as UrlResolutionResult.Rejected).primaryCode)
    }

    @Test
    fun `private network hosts are rejected (ssrf guard)`() {
        listOf(
            "http://127.0.0.1:8080/admin",
            "http://localhost/listing",
            "http://192.168.0.10/listing",
            "http://169.254.169.254/latest/meta-data/"
        ).forEach { input ->
            val result = resolver.resolve(input)
            assertTrue("$input must be rejected", result is UrlResolutionResult.Rejected)
        }
    }

    @Test
    fun `lookalike domains do not fool source detection`() {
        val resolved = (
            resolver.resolve("https://www.zillow.com.evil-clone.net/homedetails/1/20451237_zpid/") as
                UrlResolutionResult.Resolved
            ).resolved

        assertTrue("must not be attributed to zillow", resolved.sourceId != "zillow")
        assertNull(resolved.externalListingId)
    }

    @Test
    fun `resolution is deterministic and idempotent`() {
        val url = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/?utm_source=a"
        val first = (resolver.resolve(url) as UrlResolutionResult.Resolved).resolved
        val second = (resolver.resolve(first.url.normalized) as UrlResolutionResult.Resolved).resolved

        assertEquals(first.idempotencyKey, second.idempotencyKey)
        assertEquals(first.normalizedUrl, second.normalizedUrl)
    }

    @Test
    fun `notes explain unusual inputs`() {
        val resolved = (
            resolver.resolve("check this: www.zillow.com/homedetails/1/20451237_zpid/") as UrlResolutionResult.Resolved
            ).resolved

        assertEquals("zillow", resolved.sourceId)
        assertNotNull("a schemeless link must be explained, not silently rewritten", resolved.notes)
    }
}
