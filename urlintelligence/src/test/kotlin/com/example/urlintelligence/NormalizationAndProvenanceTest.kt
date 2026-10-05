package com.example.urlintelligence

import com.example.urlintelligence.model.CanonicalAddress
import com.example.urlintelligence.model.CanonicalListingStatus
import com.example.urlintelligence.model.CanonicalProperty
import com.example.urlintelligence.model.CanonicalPropertyType
import com.example.urlintelligence.model.CompletenessReport
import com.example.urlintelligence.model.GeoPoint
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.AreaParser
import com.example.urlintelligence.normalization.CanonicalPropertyMerger
import com.example.urlintelligence.normalization.DateParser
import com.example.urlintelligence.normalization.ListingStatusParser
import com.example.urlintelligence.normalization.MoneyParser
import com.example.urlintelligence.normalization.NormalizationOutcome
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.normalization.PropertyTypeParser
import com.example.urlintelligence.normalization.StateNormalizer
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import com.example.urlintelligence.provenance.FieldProvenance
import com.example.urlintelligence.provenance.MergePolicy
import com.example.urlintelligence.provenance.ProvenanceMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueNormalizerTest {

    @Test
    fun `parses money in every common format`() {
        assertEquals(485000.0, MoneyParser.parse("$485,000")!!, 0.001)
        assertEquals(485000.0, MoneyParser.parse("485000")!!, 0.001)
        assertEquals(1_250_000.0, MoneyParser.parse("$1.25M")!!, 0.001)
        assertEquals(320_000.0, MoneyParser.parse("320k")!!, 0.001)
        assertNull(MoneyParser.parse("call for price"))
    }

    @Test
    fun `parses areas with and without units`() {
        assertEquals(2250, AreaParser.sqFt("2,250 sqft"))
        assertEquals(6098, AreaParser.sqFt("0.14 acres"))
        assertEquals(1750, AreaParser.sqFt("1750"))
    }

    @Test
    fun `normalizes state names and codes`() {
        assertEquals("TX", StateNormalizer.normalize("Texas"))
        assertEquals("TX", StateNormalizer.normalize("tx"))
        assertEquals("CA", StateNormalizer.normalize("california"))
        assertEquals("DC", StateNormalizer.normalize("Washington DC"))
    }

    @Test
    fun `maps free text to canonical enums`() {
        assertEquals(CanonicalPropertyType.MULTI_FAMILY, PropertyTypeParser.parse("Multi-Family"))
        assertEquals(CanonicalPropertyType.CONDO, PropertyTypeParser.parse("Condominium"))
        assertEquals(CanonicalListingStatus.PENDING, ListingStatusParser.parse("Pending"))
        assertEquals(CanonicalListingStatus.FOR_RENT, ListingStatusParser.parse("For Rent"))
    }

    @Test
    fun `parses dates without java time`() {
        assertTrue(DateParser.epochMillis("1970-01-01") != null)
        assertTrue(DateParser.epochMillis("2024-08-10")!! > 1_700_000_000_000L)
        assertTrue(DateParser.epochMillis("Aug 10, 2024")!! > 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, DateParser.epochMillis("1700000000000"))
        assertEquals(1_700_000_000_000L, DateParser.epochMillis("1700000000"))
        assertNull(DateParser.epochMillis("not a date"))
    }
}

class PropertyNormalizerTest {

    private val clock = TestClock(1_700_000_000_000L)
    private val normalizer = PropertyNormalizer(clock)

    private fun draft(block: PropertyDraft.() -> Unit): PropertyDraft =
        PropertyDraft("test", "https://example.test/p/1").apply {
            sourcePropertyId = "1"
            block()
        }

    @Test
    fun `splits unit out of the address line`() {
        val outcome = normalizer.normalize(
            draft {
                put(PropertyField.ADDRESS_LINE1, "1900 N Bayshore Dr #1402", ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
                put(PropertyField.CITY, "Miami", ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
                put(PropertyField.STATE, "Florida", ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
                put(PropertyField.POSTAL_CODE, "33132", ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
            }
        )
        val property = (outcome as NormalizationOutcome.Success).property
        assertEquals("1900 N Bayshore Dr", property.address.line1)
        assertEquals("1402", property.address.unit)
        assertEquals("FL", property.address.stateOrProvince)
        assertTrue(property.completeness.isComplete)
    }

    @Test
    fun `drops implausible values and records a warning`() {
        val outcome = normalizer.normalize(
            draft {
                put(PropertyField.LIST_PRICE, 12.0, ExtractionMethod.REGEX_HEURISTIC, Confidence.LOW, "t")
                put(PropertyField.LIVING_AREA_SQFT, 2, ExtractionMethod.REGEX_HEURISTIC, Confidence.LOW, "t")
                put(PropertyField.BEDROOMS, 900.0, ExtractionMethod.REGEX_HEURISTIC, Confidence.LOW, "t")
            }
        )
        val success = outcome as NormalizationOutcome.Success
        assertNull(success.property.listPriceUsd)
        assertNull(success.property.livingAreaSqFt)
        assertNull(success.property.bedrooms)
        assertTrue(success.warnings.any { it.contains("list price") })
    }

    @Test
    fun `non numeric values are coerced when possible`() {
        val outcome = normalizer.normalize(
            draft {
                put(PropertyField.LIST_PRICE, "$485,000", ExtractionMethod.DOM_SELECTOR, Confidence.MEDIUM, "t")
                put(PropertyField.LIVING_AREA_SQFT, "2,250 sqft", ExtractionMethod.DOM_SELECTOR, Confidence.MEDIUM, "t")
                put(PropertyField.YEAR_BUILT, "2017", ExtractionMethod.DOM_SELECTOR, Confidence.MEDIUM, "t")
                put(PropertyField.BATHROOMS, "2.5", ExtractionMethod.DOM_SELECTOR, Confidence.MEDIUM, "t")
            }
        )
        val property = (outcome as NormalizationOutcome.Success).property
        assertEquals(485000.0, property.listPriceUsd!!, 0.001)
        assertEquals(2250, property.livingAreaSqFt)
        assertEquals(2017, property.yearBuilt)
        assertEquals(2.5, property.bathrooms!!, 0.001)
    }

    @Test
    fun `a draft with neither address nor source id fails`() {
        val outcome = normalizer.normalize(
            PropertyDraft("test", "https://example.test/p/1").apply {
                put(PropertyField.LIST_PRICE, 485000.0, ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
            }
        )
        assertTrue(outcome is NormalizationOutcome.Failure)
    }

    @Test
    fun `an empty draft fails normalization`() {
        val outcome = normalizer.normalize(PropertyDraft("test", "https://example.test/p/1"))
        assertTrue(outcome is NormalizationOutcome.Failure)
        assertTrue((outcome as NormalizationOutcome.Failure).failure is com.example.urlintelligence.failure.SourceFailure.ParseError)
    }

    @Test
    fun `missing core fields lower the completeness score`() {
        val outcome = normalizer.normalize(
            draft {
                put(PropertyField.LIST_PRICE, 485000.0, ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
            }
        )
        val property = (outcome as NormalizationOutcome.Success).property
        assertTrue(property.completeness.missingRequired.contains(PropertyField.ADDRESS_LINE1))
        assertTrue(property.completeness.missingRequired.contains(PropertyField.POSTAL_CODE))
        assertTrue(!property.completeness.isComplete)
        assertTrue(property.completeness.score < 0.5)
    }

    @Test
    fun `canonical id prefers the source listing id`() {
        val withId = normalizer.normalize(
            draft { put(PropertyField.LIST_PRICE, 1.0, ExtractionMethod.MANUAL, Confidence.EXACT, "t") }
        ) as NormalizationOutcome.Success
        assertEquals("test:1", withId.property.canonicalId)

        val withoutId = normalizer.normalize(
            PropertyDraft("test", "https://example.test/p/1").apply {
                sourcePropertyId = null
                put(PropertyField.ADDRESS_LINE1, "1 Main St", ExtractionMethod.MANUAL, Confidence.EXACT, "t")
            }
        ) as NormalizationOutcome.Success
        assertTrue(withoutId.property.canonicalId.startsWith("test:url-hash:"))
    }
}

class ProvenanceTest {

    private val at = 1_700_000_000_000L

    private fun entry(
        field: PropertyField,
        sourceId: String,
        confidence: Double,
        at: Long = this.at
    ) = FieldProvenance(
        field = field,
        sourceId = sourceId,
        sourceUrl = "https://$sourceId.test/p/1",
        extractor = "extractor",
        method = ExtractionMethod.STRUCTURED_DATA,
        confidence = confidence,
        rawValue = "raw",
        extractedAtEpochMillis = at
    )

    @Test
    fun `highest confidence wins`() {
        val map = ProvenanceMap(mapOf(PropertyField.LIST_PRICE to entry(PropertyField.LIST_PRICE, "zillow", 0.9)))
            .with(entry(PropertyField.LIST_PRICE, "redfin", 0.7))
        assertEquals("zillow", map[PropertyField.LIST_PRICE]!!.sourceId)

        val upgraded = map.with(entry(PropertyField.LIST_PRICE, "redfin", 1.0))
        assertEquals("redfin", upgraded[PropertyField.LIST_PRICE]!!.sourceId)
    }

    @Test
    fun `source priority overrides confidence`() {
        val map = ProvenanceMap(mapOf(PropertyField.LIST_PRICE to entry(PropertyField.LIST_PRICE, "redfin", 1.0)))
        val merged = map.merge(
            ProvenanceMap(mapOf(PropertyField.LIST_PRICE to entry(PropertyField.LIST_PRICE, "county", 0.4))),
            MergePolicy.SourcePriority(listOf("county", "redfin"))
        )
        assertEquals("county", merged[PropertyField.LIST_PRICE]!!.sourceId)
    }

    @Test
    fun `prefer existing and prefer incoming behave as documented`() {
        val base = ProvenanceMap(mapOf(PropertyField.CITY to entry(PropertyField.CITY, "zillow", 0.5)))
        val incoming = ProvenanceMap(mapOf(PropertyField.CITY to entry(PropertyField.CITY, "redfin", 0.9)))
        assertEquals("zillow", base.merge(incoming, MergePolicy.PreferExisting)[PropertyField.CITY]!!.sourceId)
        assertEquals("redfin", base.merge(incoming, MergePolicy.PreferIncoming)[PropertyField.CITY]!!.sourceId)
        assertEquals("redfin", base.merge(incoming)[PropertyField.CITY]!!.sourceId)
    }

    @Test
    fun `provenance can be filtered by source`() {
        val map = ProvenanceMap(
            mapOf(
                PropertyField.CITY to entry(PropertyField.CITY, "zillow", 1.0),
                PropertyField.LIST_PRICE to entry(PropertyField.LIST_PRICE, "redfin", 1.0)
            )
        )
        assertEquals(1, map.bySource("zillow").size)
        assertEquals(2, map.size)
        assertTrue(map.fields().containsAll(listOf(PropertyField.CITY, PropertyField.LIST_PRICE)))
    }

    @Test
    fun `confidence is validated`() {
        var thrown = false
        try {
            entry(PropertyField.CITY, "zillow", 1.5)
        } catch (t: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}

class CanonicalPropertyMergerTest {

    private fun property(sourceId: String, price: Double?, city: String?): CanonicalProperty =
        CanonicalProperty(
            canonicalId = "$sourceId:1",
            sourceId = sourceId,
            sourcePropertyId = "1",
            canonicalUrl = "https://$sourceId.test/1",
            resolvedUrl = "https://$sourceId.test/1",
            address = CanonicalAddress(line1 = "1 Main St", city = city, stateOrProvince = "TX", postalCode = "78704"),
            listPriceUsd = price,
            bedrooms = null,
            fetchedAtEpochMillis = 0L,
            completeness = CompletenessReport.compute(setOf(PropertyField.ADDRESS_LINE1)),
            provenance = ProvenanceMap(
                mapOf(
                    PropertyField.ADDRESS_LINE1 to FieldProvenance(
                        field = PropertyField.ADDRESS_LINE1,
                        sourceId = sourceId,
                        sourceUrl = "https://$sourceId.test/1",
                        extractor = "t",
                        method = ExtractionMethod.STRUCTURED_DATA,
                        confidence = 1.0,
                        rawValue = "1 Main St",
                        extractedAtEpochMillis = 0L
                    )
                )
            )
        )

    @Test
    fun `merge fills gaps from the secondary source`() {
        val merged = CanonicalPropertyMerger.merge(
            primary = property("zillow", price = null, city = null),
            secondary = property("redfin", price = 500_000.0, city = "Austin")
        )
        assertEquals(500_000.0, merged.listPriceUsd!!, 0.001)
        assertEquals("Austin", merged.address.city)
        assertEquals("zillow", merged.sourceId)
        assertTrue(merged.provenance.contains(PropertyField.ADDRESS_LINE1))
        assertEquals(1, merged.provenance.size)
    }

    @Test
    fun `manual overrides are recorded in provenance`() {
        val property = property("zillow", 500_000.0, "Austin")
        val overridden = CanonicalPropertyMerger.applyManualOverride(
            property,
            mapOf(PropertyField.LIST_PRICE to 450_000.0)
        )
        assertEquals(500_000.0, overridden.listPriceUsd!!, 0.001)
        assertEquals("manual", overridden.provenance[PropertyField.LIST_PRICE]!!.sourceId)
        assertEquals(ExtractionMethod.MANUAL, overridden.provenance[PropertyField.LIST_PRICE]!!.method)
    }

    @Test
    fun `geo points validate their ranges`() {
        var thrown = false
        try {
            GeoPoint(200.0, 0.0)
        } catch (t: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
