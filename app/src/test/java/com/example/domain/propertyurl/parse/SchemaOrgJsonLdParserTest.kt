package com.example.domain.propertyurl.parse

import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.numberOf
import com.example.domain.propertyurl.testParserContext
import com.example.domain.propertyurl.textOf
import com.example.domain.propertyurl.textsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the primary structured-data parser. JSON-LD is the highest-trust extraction path,
 * so expectations are the strictest here: exact values, provenance and graceful degradation.
 */
class SchemaOrgJsonLdParserTest {

    private val parser = SchemaOrgJsonLdParser()
    private val zillowUrl =
        "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"

    private fun parseFixture(name: String, url: String, sourceId: String = "zillow") =
        parser.parse(Fixtures.document(name, url), testParserContext(Fixtures.document(name, url), sourceId))

    @Test
    fun `zillow fixture yields the full listing`() {
        val result = parseFixture("zillow-homedetails.html", zillowUrl)
        val facts = result.facts
        assertFalse("fixture should produce facts", facts.isEmpty())

        assertEquals("4127 Oak Hollow Dr", facts.textOf(PropertyField.ADDRESS_LINE_1))
        assertEquals("Austin", facts.textOf(PropertyField.CITY))
        assertEquals("TX", facts.textOf(PropertyField.STATE))
        assertEquals("78745", facts.textOf(PropertyField.POSTAL_CODE))
        assertEquals(565_000.0, facts.numberOf(PropertyField.PRICE_AMOUNT)!!, 0.01)
        assertEquals(3.0, facts.numberOf(PropertyField.BEDROOMS)!!, 0.01)
        assertEquals(2.0, facts.numberOf(PropertyField.BATHROOMS)!!, 0.01)
        assertEquals(1842.0, facts.numberOf(PropertyField.LIVING_AREA_SQFT)!!, 0.01)
        assertEquals(1996.0, facts.numberOf(PropertyField.YEAR_BUILT)!!, 0.01)

        val latitude = facts.numberOf(PropertyField.LATITUDE)
        val longitude = facts.numberOf(PropertyField.LONGITUDE)
        assertNotNull("geo coordinates should be extracted", latitude)
        assertNotNull(longitude)
        assertEquals(30.2174, latitude!!, 0.0001)
        assertEquals(-97.8132, longitude!!, 0.0001)

        assertEquals(
            "both offer images should be captured",
            2,
            facts.textsOf(PropertyField.IMAGE_URLS).size
        )
    }

    @Test
    fun `every fact carries provenance pointing at the source document`() {
        val result = parseFixture("zillow-homedetails.html", zillowUrl)
        val facts = result.facts.facts
        assertTrue(facts.isNotEmpty())

        facts.forEach { fact ->
            assertEquals("zillow", fact.provenance.sourceId)
            assertEquals("test-adapter", fact.provenance.adapterId)
            assertEquals(zillowUrl, fact.provenance.sourceUrl)
            assertTrue(
                "structured data should outrank heuristics",
                fact.provenance.method == ProvenanceMethod.STRUCTURED_DATA
            )
            assertTrue("confidence must be a probability", fact.provenance.confidence in 0.0..1.0)
            assertNotNull("raw path must locate the field", fact.provenance.rawPath)
        }
    }

    @Test
    fun `breadcrumb-only documents decline instead of inventing facts`() {
        val html = """
            <html><head><script type="application/ld+json">
            {"@context":"https://schema.org","@type":"BreadcrumbList","itemListElement":[]}
            </script></head><body></body></html>
        """.trimIndent()
        val document = Fixtures.document("zillow-homedetails.html", "https://www.zillow.com/homedetails/x/1_zpid/")
            .copy(body = html, bodyBytes = html.length)

        val result = parser.parse(document, testParserContext(document))

        assertTrue("no listing node → no facts", result.facts.isEmpty())
        assertNull(result.failure)
    }

    @Test
    fun `malformed json-ld is reported as schema drift never as a crash`() {
        val html = """
            <html><head><script type="application/ld+json">
            { "@type": "House", "numberOfBedrooms": 3,, }
            </script></head><body><h1>3 bd house</h1></body></html>
        """.trimIndent()
        val document = Fixtures.document("zillow-homedetails.html", "https://www.zillow.com/homedetails/x/1_zpid/")
            .copy(body = html, bodyBytes = html.length)

        val result = parser.parse(document, testParserContext(document))

        assertTrue(result.facts.isEmpty())
        assertTrue(
            "malformed payload must be reported",
            result.warnings.any { it.code == ImportWarning.WarningCode.PARSER_SCHEMA_DRIFT }
        )
        assertNull("a malformed block is not a hard failure", result.failure)
    }

    @Test
    fun `parser is deterministic for the same document`() {
        val document = Fixtures.document("zillow-homedetails.html", zillowUrl)
        val context = testParserContext(document)

        val first = parser.parse(document, context)
        val second = parser.parse(document, context)

        assertEquals(first.facts.size, second.facts.size)
        assertEquals(
            first.facts.facts.map { it.field to it.value },
            second.facts.facts.map { it.field to it.value }
        )
    }

    @Test
    fun `redfin fixture is parsed through the same generic parser`() {
        val url = "https://www.redfin.com/TX/Austin/1810-Guadalupe-St-12/home/145879032"
        val result = parseFixture("redfin-listing.html", url, sourceId = "redfin")

        // Street line and locality are separated from the single `name` string the portal publishes.
        assertEquals("1810 Guadalupe St", result.facts.textOf(PropertyField.ADDRESS_LINE_1))
        assertEquals("TX", result.facts.textOf(PropertyField.STATE))
        assertEquals("78701", result.facts.textOf(PropertyField.POSTAL_CODE))
        assertEquals("Austin", result.facts.textOf(PropertyField.CITY))
        assertEquals(432_500.0, result.facts.numberOf(PropertyField.PRICE_AMOUNT)!!, 0.01)
    }
}
