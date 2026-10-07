package com.example.domain.propertyurl.parse

import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.numberOf
import com.example.domain.propertyurl.textOf
import com.example.domain.propertyurl.testParserContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visible-text parser is the last resort. These tests pin down the two properties that make it
 * safe: it only fires on text that really contains the fields, and it always marks its output as a
 * heuristic (so provenance-based merging prefers structured data).
 */
class VisibleTextFactsParserTest {

    private val parser = VisibleTextFactsParser()

    @Test
    fun `body copy of a portal page yields the listing facts`() {
        val url = "https://www.homes.com/property/7200-birdhouse-ln-spicewood-tx/abc123/"
        val document = Fixtures.document("homes-com-listing.html", url)
        val result = parser.parse(document, testParserContext(document, sourceId = "homes_com"))

        assertTrue(result.facts.isNotEmpty())
        assertEquals(1_150_000.0, result.facts.numberOf(PropertyField.PRICE_AMOUNT)!!, 0.01)
        assertEquals(3_240.0, result.facts.numberOf(PropertyField.LIVING_AREA_SQFT)!!, 0.01)
        assertEquals(4.0, result.facts.numberOf(PropertyField.BEDROOMS)!!, 0.01)
        assertEquals("7200 Birdhouse Ln", result.facts.textOf(PropertyField.ADDRESS_LINE_1))
        assertEquals("TX", result.facts.textOf(PropertyField.STATE))
        assertEquals("78669", result.facts.textOf(PropertyField.POSTAL_CODE))

        val provenance = result.facts.forField(PropertyField.LIVING_AREA_SQFT).first().provenance
        assertEquals(ProvenanceMethod.TEXT_HEURISTIC, provenance.method)
        assertTrue("heuristics must not claim high confidence", provenance.confidence <= 0.65)
    }

    @Test
    fun `scripts and styles never leak into the extracted text`() {
        val html = """
            <html><head><script>var price = "$1,000,000";</script>
            <style>.price:after { content: "$2,000,000"; }</style></head>
            <body><h1>123 Main St, Denver, CO 80202</h1>
            <p>Listed at $450,000 · 2 bds · 2 ba · 980 sqft. Bright corner unit with mountain views,
               updated kitchen, in-unit laundry and a reserved parking space. Walkable to downtown.</p>
            </body></html>
        """.trimIndent()
        val document = Fixtures.document("generic-brokerage-listing.html", "https://example.com/1")
            .copy(body = html, bodyBytes = html.length)

        val result = parser.parse(document, testParserContext(document, sourceId = "generic_web"))

        assertEquals(450_000.0, result.facts.numberOf(PropertyField.PRICE_AMOUNT)!!, 0.01)
        assertEquals("123 Main St", result.facts.textOf(PropertyField.ADDRESS_LINE_1))
    }

    @Test
    fun `a consent wall page produces no property facts`() {
        val html = """
            <html><body><h1>We value your privacy</h1>
            <p>We and our partners use cookies to store and access personal data. Please accept to continue.</p>
            </body></html>
        """.trimIndent()
        val document = Fixtures.document("generic-brokerage-listing.html", "https://example.com/1")
            .copy(body = html, bodyBytes = html.length)

        val result = parser.parse(document, testParserContext(document))

        assertTrue("no price/address may be invented", result.facts.isEmpty())
    }
}
