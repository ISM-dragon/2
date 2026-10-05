package com.example.urlintelligence

import com.example.urlintelligence.adapter.GenericWebAdapter
import com.example.urlintelligence.adapter.HomesAdapter
import com.example.urlintelligence.adapter.PropertyParseResult
import com.example.urlintelligence.adapter.PropertySourceAdapter
import com.example.urlintelligence.adapter.RealtorAdapter
import com.example.urlintelligence.adapter.RedfinAdapter
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.adapter.ZillowAdapter
import com.example.urlintelligence.model.CanonicalListingStatus
import com.example.urlintelligence.model.CanonicalPropertyType
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.NormalizationOutcome
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.provenance.ExtractionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser contract tests driven by HTML fixtures.
 *
 * Adapters are pure functions of (document, url), so these tests need no network,
 * no coroutines and no mocking framework — they are the fastest signal that a
 * markup change broke extraction.
 */
class ParserFixturesTest {

    private fun parse(
        adapter: PropertySourceAdapter,
        url: String,
        fixture: String
    ): PropertyParseResult {
        val body = Fixtures.load(fixture)
        val response = SourceFetchResponse.Success(
            statusCode = 200,
            body = body,
            contentType = "text/html",
            finalUrl = url,
            fetchedAtEpochMillis = 1_700_000_000_000L
        )
        return adapter.parse(response, SourceFetchRequest(url, "corr-parser"))
    }

    private fun draft(adapter: PropertySourceAdapter, url: String, fixture: String) =
        parse(adapter, url, fixture).draftOrNull ?: error("expected a draft for $fixture")

    @Test
    fun `zillow fixture yields a complete draft`() {
        val result = parse(ZillowAdapter(FakeTransport()), Fixtures.ZILLOW_URL, "zillow_listing.html")
        assertTrue("expected success but was $result", result is PropertyParseResult.Success)
        val draft = result.draftOrNull!!

        assertEquals("12345678", draft.sourcePropertyId)
        assertEquals("2418 S Congress Ave", draft.string(PropertyField.ADDRESS_LINE1))
        assertEquals("Austin", draft.string(PropertyField.CITY))
        assertEquals("TX", draft.string(PropertyField.STATE))
        assertEquals("78704", draft.string(PropertyField.POSTAL_CODE))
        assertEquals(485000.0, draft.double(PropertyField.LIST_PRICE)!!, 0.001)
        assertEquals(4.0, draft.double(PropertyField.BEDROOMS)!!, 0.001)
        assertEquals(3.0, draft.double(PropertyField.BATHROOMS)!!, 0.001)
        assertEquals(2250, draft.int(PropertyField.LIVING_AREA_SQFT))
        assertEquals(2017, draft.int(PropertyField.YEAR_BUILT))
        assertEquals(6500, draft.int(PropertyField.LOT_SIZE_SQFT))
        assertEquals(5800.0, draft.double(PropertyField.ANNUAL_TAX_AMOUNT)!!, 0.001)
        assertEquals(180.0, draft.double(PropertyField.HOA_MONTHLY_FEE)!!, 0.001)
        assertEquals(12, draft.int(PropertyField.DAYS_ON_MARKET))
        assertEquals("TX-8842113", draft.string(PropertyField.MLS_ID))
        assertEquals("0304050607", draft.string(PropertyField.PARCEL_ID))
        assertEquals(30.2415, draft.double(PropertyField.LATITUDE)!!, 0.0001)
        assertEquals(-97.7551, draft.double(PropertyField.LONGITUDE)!!, 0.0001)
        assertEquals(CanonicalPropertyType.SINGLE_FAMILY, draft.raw(PropertyField.PROPERTY_TYPE))
        assertEquals(CanonicalListingStatus.FOR_SALE, draft.raw(PropertyField.LISTING_STATUS))
        assertEquals(3, draft.stringList(PropertyField.IMAGE_URLS).size)
    }

    @Test
    fun `redfin fixture yields a complete draft`() {
        val draft = draft(RedfinAdapter(FakeTransport()), Fixtures.REDFIN_URL, "redfin_listing.html")
        assertEquals("98765432", draft.sourcePropertyId)
        assertEquals("1820 E Thomas Rd", draft.string(PropertyField.ADDRESS_LINE1))
        assertEquals("Phoenix", draft.string(PropertyField.CITY))
        assertEquals("AZ", draft.string(PropertyField.STATE))
        assertEquals("85016", draft.string(PropertyField.POSTAL_CODE))
        assertEquals(540000.0, draft.double(PropertyField.LIST_PRICE)!!, 0.001)
        assertEquals(8.0, draft.double(PropertyField.BEDROOMS)!!, 0.001)
        assertEquals(4.0, draft.double(PropertyField.BATHROOMS)!!, 0.001)
        assertEquals(3600, draft.int(PropertyField.LIVING_AREA_SQFT))
        assertEquals(1985, draft.int(PropertyField.YEAR_BUILT))
    }

    @Test
    fun `realtor fixture yields a complete draft`() {
        val draft = draft(RealtorAdapter(FakeTransport()), Fixtures.REALTOR_URL, "realtor_listing.html")
        assertEquals("AB12CD34", draft.sourcePropertyId)
        assertEquals("842 Piedmont Ave NE", draft.string(PropertyField.ADDRESS_LINE1))
        assertEquals("Atlanta", draft.string(PropertyField.CITY))
        assertEquals("GA", draft.string(PropertyField.STATE))
        assertEquals("30308", draft.string(PropertyField.POSTAL_CODE))
        assertEquals(590000.0, draft.double(PropertyField.LIST_PRICE)!!, 0.001)
        assertEquals(6.0, draft.double(PropertyField.BEDROOMS)!!, 0.001)
        assertEquals(3.0, draft.double(PropertyField.BATHROOMS)!!, 0.001)
        assertEquals(3100, draft.int(PropertyField.LIVING_AREA_SQFT))
        assertEquals(1965, draft.int(PropertyField.YEAR_BUILT))
        assertEquals("GA-77213", draft.string(PropertyField.MLS_ID))
    }

    @Test
    fun `homes fixture yields a complete draft`() {
        val draft = draft(HomesAdapter(FakeTransport()), Fixtures.HOMES_URL, "homes_listing.html")
        assertEquals("5544332211", draft.sourcePropertyId)
        assertEquals("12405 Memorial Dr", draft.string(PropertyField.ADDRESS_LINE1))
        assertEquals("Houston", draft.string(PropertyField.CITY))
        assertEquals("TX", draft.string(PropertyField.STATE))
        assertEquals("77024", draft.string(PropertyField.POSTAL_CODE))
        assertEquals(320000.0, draft.double(PropertyField.LIST_PRICE)!!, 0.001)
        assertEquals(3.0, draft.double(PropertyField.BEDROOMS)!!, 0.001)
        assertEquals(2.5, draft.double(PropertyField.BATHROOMS)!!, 0.001)
        assertEquals(1750, draft.int(PropertyField.LIVING_AREA_SQFT))
        assertEquals(180.0, draft.double(PropertyField.HOA_MONTHLY_FEE)!!, 0.001)
        assertEquals(CanonicalPropertyType.TOWNHOUSE, draft.raw(PropertyField.PROPERTY_TYPE))
    }

    @Test
    fun `generic fallback parses a brokerage page without structured data`() {
        val draft = draft(GenericWebAdapter(FakeTransport()), Fixtures.GENERIC_URL, "generic_brokerage.html")
        assertEquals("4821 Maple Ridge Dr", draft.string(PropertyField.ADDRESS_LINE1))
        assertEquals("Denver", draft.string(PropertyField.CITY))
        assertEquals("CO", draft.string(PropertyField.STATE))
        assertEquals("80211", draft.string(PropertyField.POSTAL_CODE))
        assertEquals(615000.0, draft.double(PropertyField.LIST_PRICE)!!, 0.001)
        assertEquals(3.0, draft.double(PropertyField.BEDROOMS)!!, 0.001)
        assertEquals(2.5, draft.double(PropertyField.BATHROOMS)!!, 0.001)
        assertEquals(1980, draft.int(PropertyField.LIVING_AREA_SQFT))
        assertEquals(2009, draft.int(PropertyField.YEAR_BUILT))
    }

    @Test
    fun `partial fixture reports missing core fields`() {
        val result = parse(ZillowAdapter(FakeTransport()), Fixtures.ZILLOW_URL, "zillow_partial.html")
        assertTrue("expected partial result", result is PropertyParseResult.Partial)
        val partial = result as PropertyParseResult.Partial
        assertTrue(partial.warnings.any { it.contains("missing core fields") })
        assertTrue(partial.draft.present().contains(PropertyField.LIST_PRICE))
        assertEquals(512000.0, partial.draft.double(PropertyField.LIST_PRICE)!!, 0.001)
    }

    @Test
    fun `anti bot page is classified as a soft block`() {
        val result = parse(ZillowAdapter(FakeTransport()), Fixtures.ZILLOW_URL, "blocked_captcha.html")
        assertTrue(result is PropertyParseResult.Failure)
        val failure = (result as PropertyParseResult.Failure).failure
        assertTrue("expected a blocked failure but was $failure", failure is com.example.urlintelligence.failure.SourceFailure.Blocked)
    }

    @Test
    fun `page without property data fails to parse`() {
        val result = parse(GenericWebAdapter(FakeTransport()), Fixtures.GENERIC_URL, "empty_page.html")
        assertTrue(result is PropertyParseResult.Failure)
    }

    @Test
    fun `empty body is rejected before parsing`() {
        val adapter = ZillowAdapter(FakeTransport())
        val response = SourceFetchResponse.Success(200, "", "text/html", Fixtures.ZILLOW_URL, 0L)
        val result = adapter.parse(response, SourceFetchRequest(Fixtures.ZILLOW_URL, "corr"))
        assertTrue(result is PropertyParseResult.Failure)
    }

    @Test
    fun `normalized zillow property carries structured data provenance`() {
        val draft = draft(ZillowAdapter(FakeTransport()), Fixtures.ZILLOW_URL, "zillow_listing.html")
        val outcome = PropertyNormalizer(TestClock()).normalize(draft)

        assertTrue(outcome is NormalizationOutcome.Success)
        val property = (outcome as NormalizationOutcome.Success).property

        assertEquals("zillow:12345678", property.canonicalId)
        assertEquals("zillow", property.sourceId)
        assertEquals(CanonicalPropertyType.SINGLE_FAMILY, property.propertyType)
        assertEquals(CanonicalListingStatus.FOR_SALE, property.listingStatus)
        assertEquals(485000.0, property.listPriceUsd!!, 0.001)
        assertEquals("2418 S Congress Ave, Austin, TX 78704", property.address.formatted)
        assertEquals(30.2415, property.address.geo!!.latitude, 0.0001)
        assertEquals(-97.7551, property.address.geo!!.longitude, 0.0001)
        assertTrue(property.completeness.isComplete)
        assertTrue(property.completeness.score > 0.75)

        val priceProvenance = property.provenance[PropertyField.LIST_PRICE]
        assertTrue(priceProvenance != null)
        assertEquals(ExtractionMethod.STRUCTURED_DATA, priceProvenance!!.method)
        assertEquals("485000", priceProvenance.rawValue)
        assertEquals("zillow", priceProvenance.sourceId)
        assertEquals(Fixtures.ZILLOW_URL, priceProvenance.sourceUrl)
        assertTrue(property.provenance.size > 10)
    }

    @Test
    fun `every normalized field has provenance`() {
        val draft = draft(HomesAdapter(FakeTransport()), Fixtures.HOMES_URL, "homes_listing.html")
        val property = (PropertyNormalizer(TestClock()).normalize(draft) as NormalizationOutcome.Success).property
        property.completeness.present.forEach { field ->
            assertTrue("missing provenance for $field", property.provenance.contains(field))
        }
    }

    @Test
    fun `final url is used as the provenance source url`() {
        val body = Fixtures.load("zillow_listing.html")
        val adapter = ZillowAdapter(FakeTransport())
        val request = SourceFetchRequest(Fixtures.ZILLOW_URL, "corr")
        val response = SourceFetchResponse.Success(
            statusCode = 200,
            body = body,
            contentType = "text/html",
            finalUrl = "https://www.zillow.com/homedetails/redirected/12345678_zpid",
            fetchedAtEpochMillis = 0L
        )
        val draft = (adapter.parse(response, request) as PropertyParseResult.Success).draft
        assertEquals("https://www.zillow.com/homedetails/redirected/12345678_zpid", draft.resolvedUrl)
        assertEquals(
            "https://www.zillow.com/homedetails/redirected/12345678_zpid",
            draft.provenance(0L)[PropertyField.LIST_PRICE]!!.sourceUrl
        )
    }
}
