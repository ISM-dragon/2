package com.example.domain.propertyurl.parse

import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.numberOf
import com.example.domain.propertyurl.testParserContext
import com.example.domain.propertyurl.textOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the embedded-state parser (`__NEXT_DATA__`, preloaded/redux state, NUXT …).
 * This parser is what makes React portals work when they ship no JSON-LD.
 */
class EmbeddedJsonStateParserTest {

    private val parser = EmbeddedJsonStateParser()

    @Test
    fun `next_data payload on zillow is extracted`() {
        val url = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"
        val document = Fixtures.document("zillow-homedetails.html", url)
        val result = parser.parse(document, testParserContext(document))

        assertEquals("4127 Oak Hollow Dr", result.facts.textOf(PropertyField.ADDRESS_LINE_1))
        assertEquals("Austin", result.facts.textOf(PropertyField.CITY))
        assertEquals("TX", result.facts.textOf(PropertyField.STATE))
        assertEquals("78745", result.facts.textOf(PropertyField.POSTAL_CODE))
        assertEquals(565_000.0, result.facts.numberOf(PropertyField.PRICE_AMOUNT)!!, 0.01)
        assertEquals(3.0, result.facts.numberOf(PropertyField.BEDROOMS)!!, 0.01)
        assertEquals(1842.0, result.facts.numberOf(PropertyField.LIVING_AREA_SQFT)!!, 0.01)

        val provenance = result.facts.forField(PropertyField.PRICE_AMOUNT).first().provenance
        assertEquals(ProvenanceMethod.EMBEDDED_STATE, provenance.method)
        assertTrue("embedded state must be attributed to the payload location", provenance.rawPath!!.contains("__NEXT_DATA__"))
    }

    @Test
    fun `window assignment state on redfin is extracted`() {
        val url = "https://www.redfin.com/TX/Austin/1810-Guadalupe-St-12/home/145879032"
        val document = Fixtures.document("redfin-listing.html", url)
        val result = parser.parse(document, testParserContext(document, sourceId = "redfin"))

        assertEquals("1810 Guadalupe St", result.facts.textOf(PropertyField.ADDRESS_LINE_1))
        assertEquals("Austin", result.facts.textOf(PropertyField.CITY))
        assertEquals(432_500.0, result.facts.numberOf(PropertyField.PRICE_AMOUNT)!!, 0.01)
        assertEquals(2.0, result.facts.numberOf(PropertyField.BEDROOMS)!!, 0.01)
        assertEquals(1180.0, result.facts.numberOf(PropertyField.LIVING_AREA_SQFT)!!, 0.01)
    }

    @Test
    fun `nested initialState on homes-dot-com is extracted`() {
        val url = "https://www.homes.com/property/7200-birdhouse-ln-spicewood-tx/abc123/"
        val document = Fixtures.document("homes-com-listing.html", url)
        val result = parser.parse(document, testParserContext(document, sourceId = "homes_com"))

        assertEquals("7200 Birdhouse Ln", result.facts.textOf(PropertyField.ADDRESS_LINE_1))
        assertEquals("Spicewood", result.facts.textOf(PropertyField.CITY))
        assertEquals(1_150_000.0, result.facts.numberOf(PropertyField.PRICE_AMOUNT)!!, 0.01)
        assertEquals(4.0, result.facts.numberOf(PropertyField.BEDROOMS)!!, 0.01)
    }

    @Test
    fun `malformed embedded json is reported and does not throw`() {
        val html = """
            <html><body>
            <script id="__NEXT_DATA__" type="application/json">{ "props": { "price": 550000, "beds": 3, } }</script>
            </body></html>
        """.trimIndent()
        val document = Fixtures.document("zillow-homedetails.html", "https://www.zillow.com/homedetails/x/1_zpid/")
            .copy(body = html, bodyBytes = html.length)

        val result = parser.parse(document, testParserContext(document))

        assertTrue("a trailing comma must never be turned into data", result.facts.isEmpty())
        assertTrue(result.warnings.any { it.code == ImportWarning.WarningCode.PARSER_SCHEMA_DRIFT })
    }

    @Test
    fun `truncated payload (anti-bot edge case) yields no facts and no crash`() {
        val html = """<html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"price":550000"""
        val document = Fixtures.document("zillow-homedetails.html", "https://www.zillow.com/homedetails/x/1_zpid/")
            .copy(body = html, bodyBytes = html.length)

        val result = parser.parse(document, testParserContext(document))

        assertTrue(result.facts.isEmpty())
        org.junit.Assert.assertNull(result.failure)
    }

    @Test
    fun `json bodies are accepted directly (first-party api payloads)`() {
        val body = """{"listing":{"address":"99 Congress Ave, Austin, TX 78701","price":875000,"bedrooms":2,"bathrooms":2}}"""
        val document = Fixtures.document("zillow-homedetails.html", "https://api.example.com/listing/1")
            .copy(body = body, bodyBytes = body.length, contentType = "application/json")

        val result = parser.parse(document, testParserContext(document, sourceId = "generic_web"))

        assertEquals(875_000.0, result.facts.numberOf(PropertyField.PRICE_AMOUNT)!!, 0.01)
        assertEquals("Austin", result.facts.textOf(PropertyField.CITY))
    }
}
