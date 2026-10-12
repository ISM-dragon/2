package com.example.domain.intelligence.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search URL is the contract with the portal: a wrong path or a mis-encoded `searchQueryState`
 * silently produces an empty page, which the user reads as "no homes for sale here".
 */
class ZillowSearchUrlBuilderTest {

    private val builder = ZillowSearchUrlBuilder()

    @Test
    fun `free text locations become the portal location token`() {
        assertEquals("austin-tx", builder.locationToken("Austin, TX"))
        assertEquals("austin-tx", builder.locationToken("  Austin ,  tx  "))
        assertEquals("78704", builder.locationToken("78704"))
        assertEquals("travis-county-texas", builder.locationToken("Travis County, Texas"))
        assertEquals("new-york-ny", builder.locationToken("New York, NY"))
        assertEquals("united-states", builder.locationToken("!!!"))
    }

    @Test
    fun `sale search builds the portal path and encoded state`() {
        val url = builder.searchUrl(
            ListingSearchQuery(location = "Austin, TX", minPrice = 200_000.0, maxPrice = 500_000.0, minBeds = 3, minBaths = 2.0)
        )

        assertTrue(url.startsWith("https://www.zillow.com/homes/for_sale/austin-tx_rb/?searchQueryState="))
        val encodedState = url.substringAfter("searchQueryState=")
        assertFalse("base64 must be percent-encoded inside a query parameter", encodedState.contains('+'))
        assertFalse("base64 must be percent-encoded inside a query parameter", encodedState.contains('/'))
        assertFalse("base64 must be percent-encoded inside a query parameter", encodedState.contains('='))

        val state = PortalBase64.decode(encodedState.decodePercent())
        assertTrue(state.contains("\"usersSearchTerm\":\"Austin, TX\""))
        assertTrue(state.contains("\"price\":{\"min\":200000,\"max\":500000}"))
        assertTrue(state.contains("\"beds\":{\"min\":3}"))
        assertTrue(state.contains("\"ba\":{\"min\":2}"))
        assertTrue(state.contains("\"isListVisible\":true"))
    }

    @Test
    fun `half baths keep their decimal and pagination is carried`() {
        val state = PortalBase64.decode(
            builder.searchQueryState(
                ListingSearchQuery(location = "Austin, TX", minBaths = 1.5, page = 2)
            )
        )
        assertTrue(state.contains("\"ba\":{\"min\":1.5}"))
        assertTrue(state.contains("\"pagination\":{\"currentPage\":2}"))
    }

    @Test
    fun `intent picks the portal search path`() {
        assertEquals(
            "/homes/for_rent/austin-tx_rb/",
            java.net.URI.create(builder.searchUrl(ListingSearchQuery("Austin, TX", ListingIntent.FOR_RENT))).path
        )
        assertEquals(
            "/homes/recently_sold/austin-tx_rb/",
            java.net.URI.create(builder.searchUrl(ListingSearchQuery("Austin, TX", ListingIntent.SOLD))).path
        )
        assertTrue(
            PortalBase64.decode(builder.searchQueryState(ListingSearchQuery("Austin, TX", ListingIntent.FOR_RENT)))
                .contains("\"fr\":{\"value\":true}")
        )
    }

    @Test
    fun `type filters state every known type so the portal narrows instead of ignoring`() {
        val state = PortalBase64.decode(
            builder.searchQueryState(
                ListingSearchQuery(
                    location = "Austin, TX",
                    propertyTypes = setOf(ListingPropertyTypeFilter.MULTIFAMILY, ListingPropertyTypeFilter.HOUSE)
                )
            )
        )
        assertTrue(state.contains("\"mf\":{\"value\":true}"))
        assertTrue(state.contains("\"sf\":{\"value\":true}"))
        assertTrue(state.contains("\"condo\":{\"value\":false}"))
    }

    @Test
    fun `quotes and control characters in the location are escaped`() {
        val state = PortalBase64.decode(
            builder.searchQueryState(ListingSearchQuery(location = "Austin \"TX\"\nMetro"))
        )
        assertTrue(state.contains("\\\"TX\\\""))
        assertTrue("a raw newline would break the JSON payload", state.contains("\\n"))
        assertFalse(state.contains("\n"))
    }

    @Test
    fun `plain url stays browser openable without the encoded blob`() {
        assertEquals(
            "https://www.zillow.com/homes/for_sale/austin-tx_rb/",
            builder.plainSearchUrl(ListingSearchQuery("Austin, TX"))
        )
    }

    @Test
    fun `base64 encoder round trips`() {
        val text = "{\"pagination\":{},\"usersSearchTerm\":\"Austin, TX\"}"
        val encoded = PortalBase64.encode(text.toByteArray(Charsets.UTF_8))
        assertEquals(text, PortalBase64.decode(encoded))
        // known-answer check against the standard alphabet
        assertEquals("YQ==", PortalBase64.encode("a".toByteArray()))
        assertEquals("YWI=", PortalBase64.encode("ab".toByteArray()))
        assertEquals("YWJj", PortalBase64.encode("abc".toByteArray()))
    }

    private fun String.decodePercent(): String =
        replace("%2B", "+").replace("%2F", "/").replace("%3D", "=").replace("%26", "&")
}
