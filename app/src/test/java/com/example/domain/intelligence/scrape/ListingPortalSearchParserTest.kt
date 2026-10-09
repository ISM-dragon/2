package com.example.domain.intelligence.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the search-page readers.
 *
 * These are the tests that tell you a portal redesign happened: each reader is driven by a saved
 * page, and the assertions pin the fields the UI and the underwriter rely on.
 */
class ListingPortalSearchParserTest {

    private val parser = ListingPortalSearchParser()

    @Test
    fun `embedded search state yields every card with its attribution`() {
        val parse = parser.parse(ScrapeFixtures.text("zillow-search-results.html"), SEARCH_URL)

        assertEquals("embedded-state", parse.strategy)
        assertEquals(3, parse.listings.size)

        val house = parse.listings.first { it.externalId == "20451237" }
        assertEquals("4127 Oak Hollow Dr, Austin, TX 78745", house.address)
        assertEquals("4127 Oak Hollow Dr", house.street)
        assertEquals("Austin", house.city)
        assertEquals("TX", house.state)
        assertEquals("78745", house.zipCode)
        assertEquals(565_000.0, house.price!!, 0.01)
        assertEquals(3, house.bedrooms)
        assertEquals(2.0, house.bathrooms!!, 0.01)
        assertEquals(1842, house.livingAreaSqFt)
        assertEquals(7_405L, house.lotSizeSqFt)
        assertEquals(1996, house.yearBuilt)
        assertEquals(12, house.daysOnMarket)
        assertEquals("Single Family", house.propertyType)
        assertEquals("FOR_SALE", house.status)
        assertEquals("ABC1234567", house.mlsId)
        assertEquals(30.2174, house.latitude!!, 0.0001)
        assertEquals(
            "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/",
            house.detailUrl
        )
        assertEquals("https://photos.zillowstatic.com/fp/8d1f2c3a/4127-oak-hollow-dr.jpg", house.photos.single())
    }

    @Test
    fun `agent and office contact data survives the mapping`() {
        val house = parser.parse(ScrapeFixtures.text("zillow-search-results.html"), SEARCH_URL)
            .listings.first { it.externalId == "20451237" }

        assertEquals("Marcus Lee", house.listingAgentName)
        assertEquals("+15125550134", house.listingAgentPhone)
        assertEquals("Compass RE Texas - Austin", house.listingOfficeName)
        assertEquals("+15125550199", house.listingOfficePhone)
        assertEquals("Compass RE Texas", house.brokerName)
        assertFalse("this card is not broker-paid placement", house.isBrokerPaidPlacement)
    }

    @Test
    fun `rental cards keep their price unit and protocol-relative photo`() {
        val rental = parser.parse(ScrapeFixtures.text("zillow-search-results.html"), SEARCH_URL)
            .listings.first { it.externalId == "206699112" }

        assertEquals(2_400.0, rental.price!!, 0.01)
        assertEquals("\$2,400/mo", rental.priceLabel)
        assertEquals("month", rental.priceUnit)
        assertEquals(1.5, rental.bathrooms!!, 0.01)
        assertEquals("Condo", rental.propertyType)
        assertEquals("FOR_RENT", rental.status)
        assertTrue("this card is broker-paid placement", rental.isBrokerPaidPlacement)
        assertEquals(
            "https://photos.zillowstatic.com/fp/aa11bb22/1801-east-6th-st.jpg",
            rental.photos.single()
        )
        assertNull("no agent name was published for this card", rental.listingAgentName)
        assertNull(rental.listingAgentPhone)
    }

    @Test
    fun `cards without hdpData fall back to the card fields`() {
        val condo = parser.parse(ScrapeFixtures.text("zillow-search-results.html"), SEARCH_URL)
            .listings.first { it.externalId == "84123456" }

        assertEquals("2200 Barton Springs Rd, Austin, TX 78704", condo.address)
        assertEquals(1_250_000.0, condo.price!!, 0.01)
        assertEquals(4, condo.bedrooms)
        assertEquals(2_650, condo.livingAreaSqFt)
        // No payload behind the card: nothing is invented.
        assertNull(condo.city)
        assertNull(condo.yearBuilt)
        assertNull(condo.mlsId)
        // daysOnZillow is absent, so the card's timeOnZillow (5 days) is used instead.
        assertEquals(5, condo.daysOnMarket)
    }

    @Test
    fun `newer searchList shape with nested list nodes is flattened`() {
        val parse = parser.parse(ScrapeFixtures.text("zillow-search-list.html"), SEARCH_URL)

        assertEquals("embedded-state", parse.strategy)
        assertEquals(listOf("30551001", "30551002"), parse.listings.map { it.externalId })

        val first = parse.listings.first()
        assertEquals("Sat. 1-4pm", first.openHouse)
        assertEquals("Dana Whitfield", first.listingAgentName)
        assertEquals("+15125550177", first.listingOfficePhone)
        assertEquals("Keller Williams Greater Austin", first.listingOfficeName)

        val second = parse.listings.last()
        assertEquals("eXp Realty LLC", second.brokerName)
        assertNull(second.listingAgentName)
        assertNull(second.listingAgentPhone)
    }

    @Test
    fun `json-ld item list is used when there is no embedded state`() {
        val parse = parser.parse(ScrapeFixtures.text("zillow-search-jsonld.html"), SEARCH_URL)

        assertEquals("json-ld", parse.strategy)
        assertEquals(2, parse.listings.size)

        val house = parse.listings.first()
        assertEquals("77112233", house.externalId)
        assertEquals("913 E Dean Ave, Austin, TX 78704", house.address)
        assertEquals(439_000.0, house.price!!, 0.01)
        assertEquals(2, house.bedrooms)
        assertEquals(1952, house.yearBuilt)
        assertEquals(1120, house.livingAreaSqFt)
        assertEquals(7_100L, house.lotSizeSqFt)
        assertEquals("Austin", house.city)
        assertEquals("2026-09-30T12:00:00Z", house.listDate)

        val apartment = parse.listings.last()
        assertEquals(1_725.0, apartment.price!!, 0.01)
        assertEquals(1, apartment.bedrooms)
    }

    @Test
    fun `rendered cards are read when the payload is gone`() {
        val parse = parser.parse(ScrapeFixtures.text("zillow-search-cards.html"), SEARCH_URL)

        assertEquals("html-cards", parse.strategy)
        assertEquals(2, parse.listings.size)

        val house = parse.listings.first()
        assertEquals("55112233", house.externalId)
        assertEquals("18004 Cypresswood Dr, Pflugerville, TX 78660", house.address)
        assertEquals(349_900.0, house.price!!, 0.01)
        assertEquals(3, house.bedrooms)
        assertEquals(2.0, house.bathrooms!!, 0.01)
        assertEquals(1480, house.livingAreaSqFt)
        assertEquals(
            "https://www.zillow.com/homedetails/18004-Cypresswood-Dr-Pflugerville-TX-78660/55112233_zpid/",
            house.detailUrl
        )
        assertEquals("https://photos.zillowstatic.com/fp/55aa0011/18004-cypresswood-dr.jpg", house.photos.single())

        val rental = parse.listings.last()
        assertEquals(1_895.0, rental.price!!, 0.01)
        assertEquals(2, rental.bedrooms)
        assertEquals(1.5, rental.bathrooms!!, 0.01)
    }

    @Test
    fun `an empty result set is reported, not invented`() {
        val parse = parser.parse(ScrapeFixtures.text("zillow-no-results.html"), SEARCH_URL)

        assertTrue(parse.listings.isEmpty())
        assertNull(parse.strategy)
        assertTrue(
            "the parser must say why it found nothing",
            parse.warnings.any { it.contains("no listing nodes") || it.contains("listing cards") }
        )
    }

    @Test
    fun `a page with no recognisable content yields no listings and a warning`() {
        val parse = parser.parse("<html><body><p>Nothing here</p></body></html>", SEARCH_URL)
        assertTrue(parse.listings.isEmpty())
        assertTrue(parse.warnings.isNotEmpty())
    }

    @Test
    fun `balanced brace extraction survives braces and quotes inside strings`() {
        val html = """{"searchPageState":{"cat1":{"note":"open {braces} and \"quotes\" inside","total":1}}}"""
        val raw = ListingPortalSearchParser.extractBalancedObject(html, html.indexOf('{'))
        assertEquals(html, raw)
    }

    @Test
    fun `phone normalization handles the formats portals publish`() {
        assertEquals("+15125550134", ListingPortalSearchParser.normalizePhone("(512) 555-0134"))
        assertEquals("+15125550134", ListingPortalSearchParser.normalizePhone("512.555.0134"))
        assertEquals("+15125550134", ListingPortalSearchParser.normalizePhone("+1 512 555 0134"))
        assertEquals("+15125550134", ListingPortalSearchParser.normalizePhone("1-512-555-0134"))
        assertNull(ListingPortalSearchParser.normalizePhone("call us"))
        assertNull(ListingPortalSearchParser.normalizePhone("555-0134"))
    }

    @Test
    fun `money parsing and formatting round trip the portal formats`() {
        assertEquals(565_000.0, ListingPortalSearchParser.parseMoney("\$565,000")!!, 0.01)
        assertEquals(2_400.0, ListingPortalSearchParser.parseMoney("\$2,400/mo")!!, 0.01)
        assertEquals(1_725.5, ListingPortalSearchParser.parseMoney("1725.5")!!, 0.01)
        assertNull(ListingPortalSearchParser.parseMoney("Price on request"))
        assertEquals("\$565,000", ListingPortalSearchParser.formatMoney(565_000.0))
        assertEquals("\$1,250,000", ListingPortalSearchParser.formatMoney(1_250_000.0))
    }

    private companion object {
        const val SEARCH_URL = "https://www.zillow.com/homes/for_sale/austin-tx_rb/"
    }
}
