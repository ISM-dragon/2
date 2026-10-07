package com.example.domain.property

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the US normalization rules. Everything the deduplication pipeline relies on
 * (canonical key, APN/MLS/FIPS tokens, comp address labels, property type vocabulary) is derived
 * from [UsPropertyNormalizer], so these tests protect the identity of every stored property.
 */
class UsPropertyNormalizerTest {

    @Test
    fun `street suffixes and directionals follow USPS publication 28`() {
        assertEquals("2418 s congress ave", UsPropertyNormalizer.normalizeStreet("2418 South Congress Avenue"))
        assertEquals("100 n main st", UsPropertyNormalizer.normalizeStreet("100 North Main Street"))
        assertEquals("55 s w 5th ave", UsPropertyNormalizer.normalizeStreet("55 South-West 5th Avenue"))
        assertEquals("7 w oak ct apt 3", UsPropertyNormalizer.normalizeStreet("7 West Oak Court, Apt 3"))
        assertEquals("", UsPropertyNormalizer.normalizeStreet("   "))
    }

    @Test
    fun `normalize whitespace collapses all whitespace kinds`() {
        assertEquals("2418 S Congress Ave", UsPropertyNormalizer.normalizeWhitespace("  2418   S  Congress \t Ave "))
    }

    @Test
    fun `zip codes split into zip5 and plus four`() {
        assertEquals("78704" to "1234", UsPropertyNormalizer.splitZip("78704-1234"))
        assertEquals("78704" to "", UsPropertyNormalizer.splitZip("78704"))
        assertEquals("78704" to "1234", UsPropertyNormalizer.splitZip("787041234"))
        assertEquals("" to "", UsPropertyNormalizer.splitZip("n/a"))
    }

    @Test
    fun `unit designators are normalized and never invented`() {
        assertEquals("unit 4b", UsPropertyNormalizer.normalizeUnit("Apt. #4B"))
        assertEquals("unit 12", UsPropertyNormalizer.normalizeUnit("UNIT 12"))
        assertEquals("unit 200", UsPropertyNormalizer.normalizeUnit("Ste 200"))
        assertEquals("", UsPropertyNormalizer.normalizeUnit("#"))
        assertEquals("", UsPropertyNormalizer.normalizeUnit("Apartment"))
        assertEquals("", UsPropertyNormalizer.normalizeUnit(""))
    }

    @Test
    fun `canonical key collapses feed spelling differences`() {
        val fromMls = UsPropertyNormalizer.address(
            streetAddress = "2418 South Congress Avenue",
            unit = "Apt 4B",
            city = "Austin",
            stateCode = "TX",
            zipCode = "78704-1234"
        )
        val fromWholesaler = UsPropertyNormalizer.address(
            streetAddress = "2418 S Congress Ave",
            unit = "UNIT 4B",
            city = "austin",
            stateCode = "tx",
            zipCode = "78704"
        )
        assertEquals(UsPropertyNormalizer.canonicalKey(fromMls), UsPropertyNormalizer.canonicalKey(fromWholesaler))
        assertEquals("2418 s congress ave unit 4b|austin|TX|78704", UsPropertyNormalizer.canonicalKey(fromMls))
    }

    @Test
    fun `canonical key separates different units of the same building`() {
        val unitA = UsPropertyNormalizer.address("2418 S Congress Ave", "Apt 1", "Austin", "TX", "78704")
        val unitB = UsPropertyNormalizer.address("2418 S Congress Ave", "Apt 2", "Austin", "TX", "78704")
        assertNotEquals(UsPropertyNormalizer.canonicalKey(unitA), UsPropertyNormalizer.canonicalKey(unitB))
    }

    @Test
    fun `canonical street label is a readable USPS form`() {
        assertEquals("2402 S Congress Ave", UsPropertyNormalizer.canonicalStreetLabel("2402 South Congress Avenue"))
        assertEquals("2402 S Congress Ave", UsPropertyNormalizer.canonicalStreetLabel("2402 S Congress Ave"))
        assertEquals("", UsPropertyNormalizer.canonicalStreetLabel(""))
    }

    @Test
    fun `apn mls and fips are stripped to comparable tokens`() {
        assertEquals("0412345678", UsPropertyNormalizer.normalizeApn("0412-345-678"))
        assertEquals("ABC123", UsPropertyNormalizer.normalizeMlsNumber("abc 123"))
        assertEquals("48453", UsPropertyNormalizer.normalizeCountyFips("48453", "TX"))
        assertEquals("00048", UsPropertyNormalizer.normalizeCountyFips("48", "TX"))
        assertEquals("48", UsPropertyNormalizer.normalizeCountyFips("", "TX"))
    }

    @Test
    fun `state codes are validated against the USPS list`() {
        assertTrue(UsPropertyNormalizer.isValidStateCode("tx"))
        assertFalse(UsPropertyNormalizer.isValidStateCode("XX"))
        assertEquals("TX", UsPropertyNormalizer.normalizeStateCode("tx"))
    }

    @Test
    fun `county is carried through the canonical address`() {
        val address = UsPropertyNormalizer.address(
            streetAddress = "2418 S Congress Ave",
            city = "Austin",
            stateCode = "TX",
            zipCode = "78704",
            county = "Travis",
            countyFips = "48453"
        )
        assertEquals("Travis", address.county)
        assertEquals("48453", address.countyFips)
        assertEquals("2418 S Congress Ave, Austin, TX 78704", address.singleLine)
    }

    @Test
    fun `property type and listing status vocabularies map to canonical values`() {
        assertEquals(UsPropertyType.MULTI_FAMILY, UsPropertyType.fromRaw("Duplex"))
        assertEquals(UsPropertyType.CONDO, UsPropertyType.fromRaw("Condominium"))
        assertEquals(UsPropertyType.SINGLE_FAMILY, UsPropertyType.fromRaw("Single Family Residence"))
        assertEquals(UsPropertyType.LAND, UsPropertyType.fromRaw("Vacant Land"))
        assertEquals(UsPropertyType.OTHER, UsPropertyType.fromRaw("Unicorn Farm"))

        assertEquals(UsListingStatus.UNDER_CONTRACT, UsListingStatus.fromRaw("Active Under Contract"))
        assertEquals(UsListingStatus.COMING_SOON, UsListingStatus.fromRaw("Coming Soon"))
        assertEquals(UsListingStatus.FORECLOSURE, UsListingStatus.fromRaw("REO / Bank Owned"))
        assertEquals(UsListingStatus.UNKNOWN, UsListingStatus.fromRaw(""))
    }

    @Test
    fun `total bathrooms follow the US half bath convention`() {
        val property = CanonicalProperty(
            propertyId = "prop-1",
            address = UsPropertyNormalizer.address("2418 S Congress Ave", "", "Austin", "TX", "78704"),
            fullBathrooms = 2,
            halfBathrooms = 1
        )
        assertEquals(2.5, property.totalBathrooms, 0.0)
        assertEquals(0.0, property.copy(listPrice = 0.0).pricePerSqFt, 0.0)
    }

    @Test
    fun `haversine distance is zero for identical coordinates and positive across town`() {
        assertEquals(0.0, UsPropertyNormalizer.distanceMiles(30.2415, -97.7551, 30.2415, -97.7551), 0.0001)
        val acrossTown = UsPropertyNormalizer.distanceMiles(30.2415, -97.7551, 30.2672, -97.7431)
        assertTrue("expected a few miles, got $acrossTown", acrossTown in 0.5..5.0)
        assertEquals(Double.MAX_VALUE, UsPropertyNormalizer.distanceMiles(0.0, 0.0, 30.0, -97.0), 0.0)
    }
}
