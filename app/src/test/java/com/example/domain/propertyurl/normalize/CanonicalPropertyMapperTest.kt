package com.example.domain.propertyurl.normalize

import com.example.domain.propertyurl.model.CanonicalIds
import com.example.domain.propertyurl.model.ExtractedFactsBuilder
import com.example.domain.propertyurl.model.FieldValue
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.parse.ParserChain
import com.example.domain.propertyurl.parse.SchemaOrgJsonLdParser
import com.example.domain.propertyurl.parse.EmbeddedJsonStateParser
import com.example.domain.propertyurl.parse.MetaAndTitleFactsParser
import com.example.domain.propertyurl.parse.VisibleTextFactsParser
import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.source.SourceCatalog
import com.example.domain.propertyurl.source.SourceRegistry
import com.example.domain.propertyurl.testParserContext
import com.example.domain.propertyurl.url.PropertyUrlResolver
import com.example.domain.propertyurl.url.ResolvedPropertyUrl
import com.example.domain.propertyurl.url.UrlResolutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonicalization is where the layer earns its trust: it merges competing extractions through
 * provenance, guards every value and reports what is missing instead of inventing it.
 */
class CanonicalPropertyMapperTest {

    private val registry = SourceRegistry.build(SourceCatalog.ALL)
    private val mapper = CanonicalPropertyMapper(registry, now = { 1_700_000_000_000L })
    private val resolver = PropertyUrlResolver(registry)

    private fun resolved(url: String): ResolvedPropertyUrl =
        (resolver.resolve(url) as UrlResolutionResult.Resolved).resolved

    private fun factsFor(builder: ExtractedFactsBuilder.() -> Unit) =
        ExtractedFactsBuilder().apply(builder).build()

    @Test
    fun `structured data beats heuristics for the same field`() {
        val facts = factsFor {
            addText(
                PropertyField.ADDRESS_LINE_1, "4127 Oak Hollow Dr",
                testProvenance("zillow", ProvenanceMethod.STRUCTURED_DATA, 0.9)
            )
            addText(
                PropertyField.ADDRESS_LINE_1, "4127 Oak Hollow Drive (from title)",
                testProvenance("zillow", ProvenanceMethod.TEXT_HEURISTIC, 0.6)
            )
        }

        val mapping = mapper.map(facts, resolved(ZILLOW_URL), "zillow")

        assertEquals("4127 Oak Hollow Dr", mapping.property.text(PropertyField.ADDRESS_LINE_1))
        assertEquals(
            ProvenanceMethod.STRUCTURED_DATA,
            mapping.property.provenanceOf(PropertyField.ADDRESS_LINE_1)?.method
        )
    }

    @Test
    fun `a higher trust source wins over a lower trust one`() {
        val facts = factsFor {
            addText(
                PropertyField.PRICE_AMOUNT, "550000",
                testProvenance("generic_web", ProvenanceMethod.STRUCTURED_DATA, 0.9)
            )
            addNumber(
                PropertyField.PRICE_AMOUNT, 565_000.0,
                testProvenance("zillow", ProvenanceMethod.STRUCTURED_DATA, 0.9)
            )
        }

        val mapping = mapper.map(facts, resolved(ZILLOW_URL), "zillow")

        assertEquals(565_000.0, mapping.property.price!!, 0.01)
        assertEquals("zillow", mapping.property.provenanceOf(PropertyField.PRICE_AMOUNT)?.sourceId)
    }

    @Test
    fun `conflicting values are recorded as a warning, never silently dropped`() {
        val facts = factsFor {
            addNumber(PropertyField.PRICE_AMOUNT, 565_000.0, testProvenance("zillow", ProvenanceMethod.STRUCTURED_DATA, 0.9))
            addNumber(PropertyField.PRICE_AMOUNT, 549_000.0, testProvenance("redfin", ProvenanceMethod.STRUCTURED_DATA, 0.9))
            addText(PropertyField.ADDRESS_LINE_1, "4127 Oak Hollow Dr", testProvenance("zillow", ProvenanceMethod.STRUCTURED_DATA, 0.9))
            addText(PropertyField.CITY, "Austin", testProvenance("zillow", ProvenanceMethod.STRUCTURED_DATA, 0.9))
            addText(PropertyField.STATE, "TX", testProvenance("zillow", ProvenanceMethod.STRUCTURED_DATA, 0.9))
            addText(PropertyField.POSTAL_CODE, "78745", testProvenance("zillow", ProvenanceMethod.STRUCTURED_DATA, 0.9))
        }

        val mapping = mapper.map(facts, resolved(ZILLOW_URL), "zillow")

        assertTrue(
            "cross-source disagreement must be surfaced",
            mapping.warnings.any { it.code == ImportWarning.WarningCode.FIELD_CONFLICT } || mapping.conflicts.isNotEmpty()
        )
    }

    @Test
    fun `values that fail their guard are rejected instead of stored`() {
        val facts = factsFor {
            addNumber(PropertyField.PRICE_AMOUNT, 12.0, testProvenance("generic_web", ProvenanceMethod.TEXT_HEURISTIC, 0.5))
            addNumber(PropertyField.LATITUDE, 991.0, testProvenance("generic_web", ProvenanceMethod.TEXT_HEURISTIC, 0.5))
            addText(PropertyField.POSTAL_CODE, "not-a-zip", testProvenance("generic_web", ProvenanceMethod.TEXT_HEURISTIC, 0.5))
        }

        val mapping = mapper.map(facts, resolved(ZILLOW_URL), "zillow")

        assertNull("an implausible price must not become data", mapping.property.price)
        assertNull(mapping.property.decimal(PropertyField.LATITUDE))
        assertNull(mapping.property.text(PropertyField.POSTAL_CODE))
    }

    @Test
    fun `a complete record is usable and has a canonical id`() {
        val facts = factsFor {
            val provenance = testProvenance("zillow", ProvenanceMethod.STRUCTURED_DATA, 0.9)
            addText(PropertyField.ADDRESS_LINE_1, "4127 Oak Hollow Dr", provenance)
            addText(PropertyField.CITY, "Austin", provenance)
            addText(PropertyField.STATE, "TX", provenance)
            addText(PropertyField.POSTAL_CODE, "78745", provenance)
            addNumber(PropertyField.PRICE_AMOUNT, 565_000.0, provenance)
            addNumber(PropertyField.BEDROOMS, 3.0, provenance)
            addNumber(PropertyField.BATHROOMS, 2.0, provenance)
            addInt(PropertyField.LIVING_AREA_SQFT, 1842, provenance)
        }

        val mapping = mapper.map(facts, resolved(ZILLOW_URL), "zillow")
        val property = mapping.property

        assertTrue(property.completeness.isUsable)
        assertTrue(property.canonicalId.startsWith("cp-"))
        assertEquals("4127 Oak Hollow Dr, Austin, TX, 78745", property.formattedAddress())
        assertEquals(565_000.0 / 1842.0, property.decimal(PropertyField.PRICE_PER_SQFT)!!, 0.01)
        assertEquals("20451237", property.sourceListingId)
        assertEquals("US", property.text(PropertyField.COUNTRY_CODE))
    }

    @Test
    fun `identity is stable across sources for the same address`() {
        fun facts(price: Double) = factsFor {
            val provenance = testProvenance("generic_web", ProvenanceMethod.STRUCTURED_DATA, 0.9)
            addText(PropertyField.ADDRESS_LINE_1, "4127 Oak Hollow Dr", provenance)
            addText(PropertyField.CITY, "Austin", provenance)
            addText(PropertyField.STATE, "TX", provenance)
            addText(PropertyField.POSTAL_CODE, "78745", provenance)
            addNumber(PropertyField.PRICE_AMOUNT, price, provenance)
            addNumber(PropertyField.BEDROOMS, 3.0, provenance)
            addNumber(PropertyField.BATHROOMS, 2.0, provenance)
        }

        val fromZillow = mapper.map(facts(565_000.0), resolved(ZILLOW_URL), "zillow").property
        val fromRedfin = mapper.map(
            facts(549_000.0),
            resolved("https://www.redfin.com/TX/Austin/4127-Oak-Hollow-Dr-78745/home/145879032"),
            "redfin"
        ).property

        assertEquals("the same house must collapse onto one canonical id", fromZillow.canonicalId, fromRedfin.canonicalId)
        assertEquals(
            CanonicalIds.canonicalId("4127 Oak Hollow Dr", "Austin", "TX", "78745"),
            fromZillow.canonicalId
        )
    }

    @Test
    fun `partial records are marked partial and list what is missing`() {
        val facts = factsFor {
            val provenance = testProvenance("generic_web", ProvenanceMethod.TEXT_HEURISTIC, 0.5)
            addText(PropertyField.ADDRESS_LINE_1, "4127 Oak Hollow Dr", provenance)
            addText(PropertyField.CITY, "Austin", provenance)
        }

        val mapping = mapper.map(facts, resolved(ZILLOW_URL), "zillow")

        assertTrue("no price → not usable", !mapping.property.completeness.isUsable)
        assertTrue(mapping.property.missingCriticalFields().contains(PropertyField.PRICE_AMOUNT))
        assertTrue(
            "missing essentials must be reported",
            mapping.warnings.any { it.code == ImportWarning.WarningCode.MISSING_CRITICAL_FIELD }
        )
    }

    @Test
    fun `address is derived from the listing title when nothing else has it`() {
        val facts = factsFor {
            val provenance = testProvenance("generic_web", ProvenanceMethod.HTML_META, 0.7)
            addText(PropertyField.LISTING_TITLE, "4127 Oak Hollow Dr, Austin, TX 78745 | For Sale", provenance)
            addNumber(PropertyField.PRICE_AMOUNT, 565_000.0, provenance)
            addNumber(PropertyField.BEDROOMS, 3.0, provenance)
            addNumber(PropertyField.BATHROOMS, 2.0, provenance)
            addInt(PropertyField.LIVING_AREA_SQFT, 1842, provenance)
        }

        val property = mapper.map(facts, resolved(ZILLOW_URL), "zillow").property

        assertEquals("4127 Oak Hollow Dr", property.addressLine1)
        assertEquals("Austin", property.city)
        assertEquals("TX", property.state)
        assertEquals("78745", property.postalCode)
        assertTrue(
            "derived values must be labelled as derived",
            property.provenanceOf(PropertyField.ADDRESS_LINE_1)!!.method == ProvenanceMethod.DERIVED ||
                property.warnings.any { it.code == ImportWarning.WarningCode.DERIVED_FIELD }
        )
    }

    @Test
    fun `end to end fixture mapping produces a complete canonical property`() {
        val url = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"
        val document = Fixtures.document("zillow-homedetails.html", url)
        val chain = ParserChain(
            listOf(
                SchemaOrgJsonLdParser(),
                EmbeddedJsonStateParser(),
                MetaAndTitleFactsParser(),
                VisibleTextFactsParser()
            )
        )
        val outcome = chain.parse(document, testParserContext(document, sourceId = "zillow"))

        val mapping = mapper.map(outcome.facts, resolved(url), "zillow")
        val property = mapping.property

        assertTrue("fixture must yield a usable record", property.completeness.isUsable)
        assertEquals("4127 Oak Hollow Dr", property.addressLine1)
        assertEquals("Austin", property.city)
        assertEquals("TX", property.state)
        assertEquals("78745", property.postalCode)
        assertEquals(565_000.0, property.price!!, 0.01)
        assertEquals(3, property.bedrooms)
        assertEquals(2.0, property.bathrooms!!, 0.01)
        assertEquals(1842, property.livingAreaSqFt)
        assertEquals(2, property.imageUrls.size)

        // Provenance must cover every single stored value.
        property.fields.forEach { (field, sourced) ->
            assertEquals("field $field is missing its source", "zillow", sourced.provenance.sourceId)
            assertNotNull("field $field is missing its method", sourced.provenance.method)
        }
    }

    private fun testProvenance(
        sourceId: String,
        method: ProvenanceMethod,
        confidence: Double
    ) = com.example.domain.propertyurl.model.Provenance(
        sourceId = sourceId,
        method = method,
        confidence = confidence,
        extractedAtEpochMillis = 1_700_000_000_000L,
        adapterId = "test",
        adapterVersion = "1.0.0",
        sourceUrl = ZILLOW_URL
    )

    private companion object {
        const val ZILLOW_URL = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"
    }
}
