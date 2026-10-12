package com.example.domain.intelligence.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contact routes are what makes a scraped listing useful, and they are also where a scraper can
 * quietly lie. These tests pin both halves: publish what the page gave us, and say so when it
 * gave us nothing.
 */
class ListingContactExtractorTest {

    private val extractor = ListingContactExtractor()

    @Test
    fun `a listing with an agent phone gets call, text and whatsapp routes`() {
        val contact = extractor.contactFor(listingWithPhone)

        assertEquals("Marcus Lee", contact.personName)
        assertEquals("Listing agent", contact.role)
        assertEquals("+15125550134", contact.phone)
        assertEquals("Compass RE Texas - Austin", contact.brokerage)
        assertTrue(contact.hasDirectRoute)
        assertTrue(contact.missing.isEmpty())
        assertEquals("payload:attributionInfo.agentPhoneNumber", contact.provenance)

        assertEquals("tel:+15125550134", contact.action(ContactChannel.CALL).uri)
        assertEquals("sms:+15125550134", contact.action(ContactChannel.SMS).uri)
        assertEquals("https://wa.me/15125550134", contact.action(ContactChannel.WHATSAPP).uri)
        assertEquals("Marcus Lee · Compass RE Texas - Austin", contact.action(ContactChannel.CALL).target)
    }

    @Test
    fun `the portal message route exists for every listing`() {
        val contact = extractor.contactFor(listingWithPhone)
        val portal = contact.action(ContactChannel.PORTAL_MESSAGE)

        assertTrue(portal.isAvailable)
        assertEquals(listingWithPhone.detailUrl, portal.uri)
        assertEquals(listingWithPhone.detailUrl, contact.action(ContactChannel.LISTING_PAGE).uri)
    }

    @Test
    fun `a listing without a published number says so instead of offering a dead button`() {
        val contact = extractor.contactFor(listingWithoutPhone)

        assertNull(contact.phone)
        assertFalse(contact.hasDirectRoute)
        assertEquals(
            "No direct phone in the listing payload — the portal routes messages through its contact form.",
            contact.missing.first()
        )
        assertTrue(contact.missing.any { it.contains("No agent name") })

        val call = contact.action(ContactChannel.CALL)
        assertFalse("an unavailable route must stay unavailable", call.isAvailable)
        assertEquals("No number published on the listing card", call.unavailableReason)
        // The routes that always exist are still there.
        assertTrue(contact.action(ContactChannel.PORTAL_MESSAGE).isAvailable)
        assertTrue(contact.action(ContactChannel.LISTING_PAGE).isAvailable)
        assertEquals("payload:none", contact.provenance)
    }

    @Test
    fun `office phone is used when the agent number is withheld`() {
        val contact = extractor.contactFor(listingWithPhone.copy(listingAgentPhone = null))

        assertEquals("+15125550199", contact.phone)
        assertEquals("payload:attributionInfo.officePhoneNumber", contact.provenance)
        assertTrue(contact.hasDirectRoute)
    }

    @Test
    fun `a single phone on the page is attributed, two are not`() {
        val oneNumberPage = """<html><body><a href="tel:(512) 555-0100">Call the office</a></body></html>"""
        val contact = extractor.contactFor(listingWithoutPhone, fallbackPhone = "+15125550100")
        assertEquals("+15125550100", contact.phone)
        assertEquals("html:tel-link (single unambiguous number on page)", contact.provenance)

        assertEquals(listOf("+15125550100"), extractor.pagePhones(oneNumberPage))

        val twoNumbersPage = """
            <html><body>
              <a href="tel:+1-512-555-0100">Office</a>
              <a href="tel:512.555.0200">Agent</a>
            </body></html>
        """.trimIndent()
        assertEquals(listOf("+15125550100", "+15125550200"), extractor.pagePhones(twoNumbersPage))

        // With two candidates the caller must not pick one, so nothing is attributed.
        val ambiguous = extractor.pagePhones(twoNumbersPage).distinct().singleOrNull()
        assertNull(ambiguous)
    }

    @Test
    fun `contacts are built for every listing in a search result`() {
        val contacts = extractor.contactsFor(listOf(listingWithPhone, listingWithoutPhone))

        assertEquals(2, contacts.size)
        assertEquals("20451237", contacts.first().listingExternalId)
        assertTrue(contacts.first().hasDirectRoute)
        assertFalse(contacts.last().hasDirectRoute)
    }

    @Test
    fun `whatsapp links carry digits only`() {
        assertEquals("https://wa.me/15125550134", extractor.whatsappUri("+15125550134"))
        assertEquals("https://wa.me/15125550134", extractor.whatsappUri("(512) 555-0134"))
    }

    private fun ListingContact.action(channel: ContactChannel): ContactAction =
        actions.first { it.channel == channel }

    private val listingWithPhone = ScrapedListing(
        sourceId = "zillow",
        externalId = "20451237",
        detailUrl = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/",
        address = "4127 Oak Hollow Dr, Austin, TX 78745",
        price = 565_000.0,
        listingAgentName = "Marcus Lee",
        listingAgentPhone = "(512) 555-0134",
        listingOfficeName = "Compass RE Texas - Austin",
        listingOfficePhone = "512-555-0199",
        parseStrategy = "embedded-state"
    )

    private val listingWithoutPhone = ScrapedListing(
        sourceId = "zillow",
        externalId = "206699112",
        detailUrl = "https://www.zillow.com/homedetails/1801-E-6th-St-AUSTIN-TX-78702/206699112_zpid/",
        address = "1801 E 6th St, Austin, TX 78702",
        price = 2_400.0,
        parseStrategy = "embedded-state"
    )
}
