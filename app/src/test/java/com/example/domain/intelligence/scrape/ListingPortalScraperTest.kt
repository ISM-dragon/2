package com.example.domain.intelligence.scrape

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end behaviour of one search: consent, pacing, page reading, contact assembly, and — the
 * part that matters most for trust — telling the difference between "no homes matched" and "the
 * portal refused to show us anything".
 */
class ListingPortalScraperTest {

    /** One page: the default shape of a user-initiated search. */
    private val query = ListingSearchQuery(location = "Austin, TX")

    /** Three pages, so pagination behaviour is observable. */
    private val multiPageQuery = ListingSearchQuery(location = "Austin, TX", maxPages = 3)

    private fun scraper(
        fetcher: ListingPageFetcher,
        settings: PortalScrapeSettings = CONSENTED,
        sleeper: Sleeper = RecordingSleeper(),
        clock: () -> Long = { 1_700_000_000_000L }
    ) = ListingPortalScraper(
        fetcher = fetcher,
        policy = ListingScrapePolicy { settings },
        sleeper = sleeper,
        clock = clock
    )

    @Test
    fun `a clean page becomes listings plus a contact card for each`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ScrapeFixtures.page("zillow-search-results.html") },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val result = scraper(fetcher).search(query)

        assertNull(result.failure?.toString(), result.failure)
        assertEquals(3, result.listingCount)
        assertEquals(3, result.contacts.size)
        assertEquals("embedded-state", result.parseStrategy)
        assertEquals(1, result.fetchedPages)
        assertTrue(result.searchUrl.startsWith("https://www.zillow.com/homes/for_sale/austin-tx_rb/"))

        val house = result.listings.first { it.externalId == "20451237" }
        val contact = result.contactFor(house)
        assertNotNull(contact)
        assertEquals("Marcus Lee", contact!!.personName)
        assertEquals("+15125550134", contact.phone)
        assertTrue(contact.hasDirectRoute)

        assertEquals("one page, one request", 1, fetcher.requestedUrls.size)
    }

    @Test
    fun `the portal contact route is offered even when no number is published`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ScrapeFixtures.page("zillow-search-results.html") },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val result = scraper(fetcher).search(query)
        val rental = result.listings.first { it.externalId == "206699112" }
        val contact = result.contactFor(rental)!!

        assertNull(contact.phone)
        assertFalse(contact.hasDirectRoute)
        assertTrue(contact.actions.first { it.channel == ContactChannel.PORTAL_MESSAGE }.isAvailable)
    }

    @Test
    fun `an anti-bot wall is reported as such, with the remediation that works`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ScrapeFixtures.page("zillow-bot-wall.html", status = 403) },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val result = scraper(fetcher).search(query)
        val failure = result.failure!!

        assertEquals(ListingScrapeFailureKind.BLOCKED_BY_ANTIBOT, failure.kind)
        assertTrue(failure.message.contains("PerimeterX/HUMAN"))
        assertTrue(failure.remediation!!.contains("proxy"))
        assertTrue(result.listings.isEmpty())
    }

    @Test
    fun `429 is a rate limit, not a bot wall`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ScrapeFixtures.page("zillow-bot-wall.html", status = 429) },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val failure = scraper(fetcher).search(query).failure!!
        assertEquals(ListingScrapeFailureKind.RATE_LIMITED, failure.kind)
        assertTrue(failure.kind.retryable)
    }

    @Test
    fun `an unreadable page is parser drift, not an empty market`() = runTest {
        val fetcher = RecordingFetcher(
            responder = {
                ListingPageFetch(
                    requestedUrl = "https://www.zillow.com/homes/for_sale/austin-tx_rb/",
                    status = 200,
                    body = "<html><body><div id=\"app\"></div><script>/* payload moved to xhr */</script></body></html>"
                )
            },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val failure = scraper(fetcher).search(query).failure!!
        assertEquals(ListingScrapeFailureKind.PARSE_DRIFT, failure.kind)
        assertTrue(failure.remediation!!.contains("ListingPortalSearchParser"))
    }

    @Test
    fun `a genuine empty result set says no listings matched`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ScrapeFixtures.page("zillow-no-results.html") },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val failure = scraper(fetcher).search(query).failure!!
        assertEquals(ListingScrapeFailureKind.EMPTY_RESULTS, failure.kind)
        assertTrue(failure.message.contains("no matching listings"))
    }

    @Test
    fun `a transport failure is classified as network`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ScrapeFixtures.page("zillow-search-results.html", status = 0, transportError = "TLS_FAILURE") },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val failure = scraper(fetcher).search(query).failure!!
        assertEquals(ListingScrapeFailureKind.NETWORK_ERROR, failure.kind)
        assertEquals("TLS_FAILURE", failure.detail)
    }

    @Test
    fun `a 404 tells the operator the location token is wrong`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ListingPageFetch(requestedUrl = "https://www.zillow.com/x", status = 404, body = "<html></html>") },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val failure = scraper(fetcher).search(query).failure!!
        assertEquals(ListingScrapeFailureKind.HTTP_ERROR, failure.kind)
        assertTrue(failure.remediation!!.contains("location token"))
    }

    @Test
    fun `without consent the fetcher is never touched`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ScrapeFixtures.page("zillow-search-results.html") },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val result = scraper(fetcher, settings = PortalScrapeSettings(userConsented = false)).search(query)

        assertEquals(ListingScrapeFailureKind.DENIED_BY_POLICY, result.failure!!.kind)
        assertEquals("no request may leave the device without consent", 0, fetcher.requestedUrls.size)
        assertEquals(0, fetcher.robotsRequests)
    }

    @Test
    fun `robots disallow stops the search before any listing request`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ScrapeFixtures.page("zillow-search-results.html") },
            robotsTxt = RESTRICTIVE_ROBOTS
        )

        val result = scraper(fetcher, settings = CONSENTED).search(query)

        assertEquals(ListingScrapeFailureKind.DENIED_BY_POLICY, result.failure!!.kind)
        assertEquals(0, fetcher.requestedUrls.size)
        assertEquals(1, fetcher.robotsRequests)
    }

    @Test
    fun `later pages are walked, paced, and stop at the end of the result set`() = runTest {
        val sleeper = RecordingSleeper()
        // Page 2 is only distinguishable by its encoded searchQueryState, so the fake counts calls.
        var calls = 0
        val fetcher = RecordingFetcher(
            responder = {
                calls++
                if (calls == 1) ScrapeFixtures.page("zillow-search-results.html")
                else ScrapeFixtures.page("zillow-no-results.html")
            },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val result = scraper(fetcher, settings = CONSENTED.copy(minRequestIntervalMillis = 1_500), sleeper = sleeper)
            .search(multiPageQuery)

        assertEquals(3, result.listingCount)
        assertEquals(2, result.fetchedPages)
        assertNull(result.failure)
        assertTrue("page 2 must carry a different encoded state", fetcher.requestedUrls[0] != fetcher.requestedUrls[1])
        assertTrue(
            "the partial result must say why it stopped",
            result.warnings.any { it.contains("Stopped after page 2") }
        )
        assertTrue("page 2 must be paced behind page 1", sleeper.sleeps.isNotEmpty())
    }

    @Test
    fun `a repeated page does not become duplicate inventory`() = runTest {
        val fetcher = RecordingFetcher(
            // Portals serve the same cards at page boundaries; the scraper must notice.
            responder = { ScrapeFixtures.page("zillow-search-results.html") },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val result = scraper(fetcher).search(multiPageQuery)

        assertNull(result.failure?.toString(), result.failure)
        assertEquals(3, result.listingCount)
        assertEquals(3, result.contacts.size)
    }

    @Test
    fun `maxResults caps what reaches the UI`() = runTest {
        val fetcher = RecordingFetcher(
            responder = { ScrapeFixtures.page("zillow-search-results.html") },
            robotsTxt = ALLOW_ALL_ROBOTS
        )

        val result = scraper(fetcher).search(query.copy(maxResults = 2))
        assertEquals(2, result.listingCount)
        assertEquals(2, result.contacts.size)
    }

    @Test
    fun `interpretPage is usable without the network for diagnostics`() {
        val fetcher = RecordingFetcher(responder = { ScrapeFixtures.page("zillow-search-results.html") })
        val page = ScrapeFixtures.page("zillow-search-results.html")

        val result = scraper(fetcher).interpretPage(page, query, page.requestedUrl)

        assertEquals(3, result.listingCount)
        assertNull(result.failure)
        assertEquals("embedded-state", result.parseStrategy)
    }

    private companion object {
        val CONSENTED = PortalScrapeSettings(userConsented = true)
    }
}
