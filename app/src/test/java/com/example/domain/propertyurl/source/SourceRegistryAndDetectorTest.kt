package com.example.domain.propertyurl.source

import com.example.domain.propertyurl.url.PropertyUrl
import com.example.domain.propertyurl.url.PropertyUrlValidator
import com.example.domain.propertyurl.url.UrlValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the extension point: adding Zillow/Redfin/Realtor/Homes (or the next portal) must be
 * data, not code. These tests guard that contract and the detection ladder that consumes it.
 */
class SourceRegistryAndDetectorTest {

    private val registry = SourceRegistry.build(SourceCatalog.ALL)
    private val detector = SourceDetector(registry)
    private val validator = PropertyUrlValidator()

    private fun url(raw: String): PropertyUrl = (validator.validate(raw) as UrlValidationResult.Valid).url

    @Test
    fun `catalogue is internally consistent`() {
        val ids = registry.ids
        assertEquals("ids must be unique", ids.size, registry.definitions.size)
        registry.definitions.forEach { definition ->
            assertTrue("${definition.sourceId} needs a display name", definition.displayName.isNotBlank())
            assertTrue("${definition.sourceId} needs a trust rank in 0..10", definition.trustRank in 0..10)
            // Domain-less entries are the API/feed sources (MLS, county records, partner APIs).
            if (definition.kind == PropertySourceKind.LISTING_PORTAL) {
                assertTrue("${definition.sourceId} needs domains", definition.domains.isNotEmpty())
            }
            definition.pathRules.forEach { rule ->
                assertTrue("${definition.sourceId}/${rule.name} needs a compiled regex", rule.regex.pattern.isNotBlank())
            }
        }
    }

    @Test
    fun `registry is immutable and extensible without mutation`() {
        val extended = registry.plus(SourceCatalog.TRULIA.copy(sourceId = "trulia_test"))
        assertNotNull(extended.byId("trulia_test"))
        assertNull("the original registry must not change", registry.byId("trulia_test"))
    }

    @Test
    fun `duplicate ids are rejected at build time`() {
        val failure = runCatching { SourceRegistry.build(listOf(SourceCatalog.ZILLOW, SourceCatalog.ZILLOW)) }
        assertTrue("duplicate source ids must fail fast", failure.isFailure)
    }

    @Test
    fun `lookup is case insensitive and unknown ids return null`() {
        assertNotNull(registry.byId("ZILLOW"))
        assertNull(registry.byId("does-not-exist"))
    }

    @Test
    fun `detection follows domain plus path rules with high confidence`() {
        val detection = detector.detect(url("https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"))

        assertEquals("zillow", detection.sourceId)
        assertTrue(detection.confidence > 0.9)
        assertTrue(detection.isSupported)
        assertTrue("evidence must explain the decision", detection.evidence.isNotEmpty())
    }

    @Test
    fun `planned sources are recognised but flagged as unsupported`() {
        val detection = detector.detect(url("https://www.apartments.com/austin-tx/4127-oak-hollow/1a2b3c4/"))

        assertEquals("apartments_com", detection.sourceId)
        assertTrue("planned sources must not claim support", !detection.isSupported)
    }

    @Test
    fun `unknown domains are not mis-attributed`() {
        val detection = detector.detect(url("https://random-broker.example/listing/42"))

        assertTrue(
            "unknown hosts must stay unattributed",
            detection.sourceId == null || detection.sourceId == "generic_web" || !detection.isSupported
        )
    }

    @Test
    fun `lookalike domain does not match the portal it imitates`() {
        val detection = detector.detect(url("https://www.zillow.com.evil-clone.net/homedetails/1/20451237_zpid/"))

        assertTrue("lookalike hosts must not detect as zillow", detection.sourceId != "zillow")
    }

    @Test
    fun `validator rejects private networks and non-http schemes`() {
        listOf("http://10.0.0.5/x", "http://[::1]/x", "javascript:void(0)", "ftp://host/file").forEach { input ->
            val result = validator.validate(input)
            assertTrue("$input must be invalid", result is UrlValidationResult.Invalid)
        }
    }

    @Test
    fun `validator accepts and normalizes a real listing url`() {
        val result = validator.validate("https://www.redfin.com/TX/Austin/1810-Guadalupe-St-12/home/145879032?a=1&b=2")

        val valid = result as UrlValidationResult.Valid
        assertEquals("redfin.com", valid.url.registrableDomain)
        assertTrue("listing path must survive normalization", valid.url.normalized.contains("/home/145879032"))
    }
}
