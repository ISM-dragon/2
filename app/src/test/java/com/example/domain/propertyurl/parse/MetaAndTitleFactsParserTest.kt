package com.example.domain.propertyurl.parse

import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.numberOf
import com.example.domain.propertyurl.testParserContext
import com.example.domain.propertyurl.textOf
import com.example.domain.propertyurl.textsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Meta/OpenGraph are the reliability floor: every portal ships them, so they must never be skipped. */
class MetaAndTitleFactsParserTest {

    private val parser = MetaAndTitleFactsParser()

    @Test
    fun `title and description of a generic brokerage page produce guarded facts`() {
        val url = "https://brokerage.example.com/listings/4127-oak-hollow-dr"
        val document = Fixtures.document("generic-brokerage-listing.html", url)
        val result = parser.parse(document, testParserContext(document, sourceId = "generic_web"))

        assertEquals(565_000.0, result.facts.numberOf(PropertyField.PRICE_AMOUNT)!!, 0.01)
        assertEquals(1842.0, result.facts.numberOf(PropertyField.LIVING_AREA_SQFT)!!, 0.01)
        assertEquals(3.0, result.facts.numberOf(PropertyField.BEDROOMS)!!, 0.01)
        assertEquals(1996.0, result.facts.numberOf(PropertyField.YEAR_BUILT)!!, 0.01)

        val image = result.facts.textOf(PropertyField.IMAGE_URLS)
        assertNotNull("og:image should be captured", image)
        assertTrue(image!!.startsWith("https://cdn.example-brokerage.com/"))

        // The price lives in the <title>, so it is a text heuristic — never dressed up as structured data.
        val provenance = result.facts.forField(PropertyField.PRICE_AMOUNT).first().provenance
        assertEquals(ProvenanceMethod.TEXT_HEURISTIC, provenance.method)
        assertTrue(provenance.confidence <= 0.65)
    }

    @Test
    fun `og image lists are emitted as image urls`() {
        val html = """
            <html><head>
              <title>1200 Lakeshore Dr, Chicago, IL 60611 | $899,000</title>
              <meta property="og:image" content="https://cdn.example.com/a.jpg">
              <meta property="og:image" content="https://cdn.example.com/b.jpg">
              <meta property="og:image" content="not-a-url">
            </head><body></body></html>
        """.trimIndent()
        val document = Fixtures.document("generic-brokerage-listing.html", "https://example.com/listings/1200")
            .copy(body = html, bodyBytes = html.length)

        val result = parser.parse(document, testParserContext(document, sourceId = "generic_web"))

        val images = result.facts.textsOf(PropertyField.IMAGE_URLS)
        assertEquals("only absolute http(s) urls pass the guard", 2, images.size)
        assertEquals("899000", result.facts.numberOf(PropertyField.PRICE_AMOUNT)!!.toLong().toString())
    }

    @Test
    fun `a document without meta tags declines cleanly`() {
        val html = "<html><body><p>Nothing to see here</p></body></html>"
        val document = Fixtures.document("generic-brokerage-listing.html", "https://example.com/x")
            .copy(body = html, bodyBytes = html.length)

        val result = parser.parse(document, testParserContext(document))

        assertTrue(result.facts.isEmpty())
        assertEquals(null, result.failure)
    }
}
