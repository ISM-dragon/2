package com.example.urlintelligence

import com.example.urlintelligence.source.KnownSources
import com.example.urlintelligence.source.SourceDescriptor
import com.example.urlintelligence.source.SourceDetection
import com.example.urlintelligence.source.SourceDetector
import com.example.urlintelligence.source.SourceRegistry
import com.example.urlintelligence.url.NormalizedUrl
import com.example.urlintelligence.url.PropertyUrlValidator
import com.example.urlintelligence.url.UrlValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDetectionTest {

    private val validator = PropertyUrlValidator()
    private val detector = SourceDetector()

    private fun detect(url: String, body: String? = null): SourceDetection {
        val result = validator.validate(url)
        assertTrue("expected $url to be valid", result is UrlValidationResult.Valid)
        return detector.detect((result as UrlValidationResult.Valid).url, body)
    }

    @Test
    fun `detects zillow and extracts the zpid`() {
        val detection = detect(Fixtures.ZILLOW_URL)
        assertEquals("zillow", detection.sourceId)
        assertEquals("12345678", detection.sourcePropertyId)
        assertTrue(detection.confidence >= 0.9)
        assertTrue(detection.isKnownSource)
    }

    @Test
    fun `detects redfin and extracts the listing id`() {
        val detection = detect(Fixtures.REDFIN_URL)
        assertEquals("redfin", detection.sourceId)
        assertEquals("98765432", detection.sourcePropertyId)
        assertTrue(detection.confidence >= 0.9)
    }

    @Test
    fun `detects realtor and extracts the slug id`() {
        val detection = detect(Fixtures.REALTOR_URL)
        assertEquals("realtor", detection.sourceId)
        assertEquals("AB12CD34", detection.sourcePropertyId)
    }

    @Test
    fun `detects homes and extracts the numeric id`() {
        val detection = detect(Fixtures.HOMES_URL)
        assertEquals("homes", detection.sourceId)
        assertEquals("5544332211", detection.sourcePropertyId)
    }

    @Test
    fun `unknown host falls back to the generic parser`() {
        val detection = detect(Fixtures.GENERIC_URL)
        assertEquals(SourceDescriptor.GENERIC_SOURCE_ID, detection.sourceId)
        assertFalse(detection.isKnownSource)
        assertTrue(detection.confidence <= 0.3)
    }

    @Test
    fun `recovers the listing id from the document body when the url has none`() {
        val body = "<script>{\"zpid\":55667788,\"price\":\"$400,000\"}</script>"
        val detection = detect("https://www.zillow.com/homedetails/some-slug/", body)
        assertEquals("zillow", detection.sourceId)
        assertEquals("55667788", detection.sourcePropertyId)
        assertTrue(detection.reason.contains("document body"))
    }

    @Test
    fun `subdomains of a known host still match`() {
        val detection = detect("https://m.zillow.com/homedetails/1_zpid")
        assertEquals("zillow", detection.sourceId)
    }

    @Test
    fun `detection works from a canonical string`() {
        val detection = SourceDetector().detectCanonical(Fixtures.ZILLOW_URL)
        assertEquals("zillow", detection.sourceId)
        assertEquals("12345678", detection.sourcePropertyId)
    }
}

class SourceRegistryTest {

    private val zillow = KnownSources.ZILLOW

    @Test
    fun `registry binds adapter and descriptor together`() {
        val adapter = FakeAdapter(zillow)
        val registry = SourceRegistry.empty().registerAdapter(adapter)
        assertEquals(adapter, registry.adapterFor("zillow"))
        assertEquals(zillow, registry.descriptorFor("zillow"))
        assertTrue(registry.resolvableSourceIds().contains("zillow"))
    }

    @Test
    fun `descriptor can be registered before an adapter exists`() {
        val future = SourceDescriptor(
            id = "loopnet",
            displayName = "LoopNet",
            hostSuffixes = listOf("loopnet.com")
        )
        val registry = SourceRegistry.empty().registerDescriptor(future)
        assertTrue(registry.contains("loopnet"))
        assertNull(registry.adapterFor("loopnet"))
        assertTrue(registry.isAnnouncedOnly("loopnet"))

        registry.registerAdapter(FakeAdapter(future))
        assertFalse(registry.isAnnouncedOnly("loopnet"))
        assertEquals(2, registry.size())
    }

    @Test
    fun `re-registering replaces the adapter for a source`() {
        val first = FakeAdapter(zillow)
        val second = FakeAdapter(zillow)
        val registry = SourceRegistry.empty().registerAdapter(first).registerAdapter(second)
        assertEquals(second, registry.adapterFor("zillow"))
        assertEquals(1, registry.adapters().size)
    }

    @Test
    fun `unregister removes descriptor and adapter`() {
        val registry = SourceRegistry.empty().registerAdapter(FakeAdapter(zillow))
        registry.unregister("zillow")
        assertFalse(registry.contains("zillow"))
        assertNull(registry.adapterFor("zillow"))
    }

    @Test
    fun `detection only registry knows every source but resolves none`() {
        val registry = SourceRegistry.detectionOnly()
        assertEquals(KnownSources.all().size, registry.descriptors().size)
        assertTrue(registry.adapters().isEmpty())
        registry.descriptors().forEach { descriptor ->
            assertTrue(registry.isAnnouncedOnly(descriptor.id))
        }
    }

    @Test
    fun `registry detection delegates to the detector`() {
        val registry = SourceRegistry.detectionOnly()
        val url = NormalizedUrl(
            original = Fixtures.ZILLOW_URL,
            canonical = Fixtures.ZILLOW_URL.trimEnd('/'),
            scheme = "https",
            host = "www.zillow.com",
            port = null,
            path = "/homedetails/2418-S-Congress-Ave-Austin-TX-78704/12345678_zpid",
            query = emptyMap(),
            droppedParameters = emptyList()
        )
        assertEquals("zillow", registry.detect(url).sourceId)
    }
}
