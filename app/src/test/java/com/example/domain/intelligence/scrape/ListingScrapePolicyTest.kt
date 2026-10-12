package com.example.domain.intelligence.scrape

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compliance gate decides whether a fetch happens at all, so its defaults are the product's
 * legal position. Every denial must name the rule that fired and say what the operator can do.
 */
class ListingScrapePolicyTest {

    private val searchUrl = "https://www.zillow.com/homes/for_sale/austin-tx_rb/?searchQueryState=eyJhIjoxfQ%3D%3D"

    private fun policy(settings: PortalScrapeSettings) = ListingScrapePolicy { settings }

    @Test
    fun `nothing is fetched before the user consents`() = runTest {
        val gate = policy(PortalScrapeSettings(userConsented = false)).check(searchUrl) { ALLOW_ALL_ROBOTS }

        val failure = (gate as ScrapeGate.Denied).failure
        assertEquals(ListingScrapeFailureKind.DENIED_BY_POLICY, failure.kind)
        assertTrue(failure.message.contains("switched off"))
        assertTrue("the user must be told where to turn it on", failure.remediation!!.contains("Settings"))
    }

    @Test
    fun `hosts outside the allowlist are refused`() = runTest {
        val rogue = policy(PortalScrapeSettings(userConsented = true, allowedHosts = setOf("www.zillow.com")))
            .check("https://listings.example.com/homes/for_sale/austin-tx_rb/") { ALLOW_ALL_ROBOTS }

        val failure = (rogue as ScrapeGate.Denied).failure
        assertEquals(ListingScrapeFailureKind.DENIED_BY_POLICY, failure.kind)
        assertTrue(failure.message.contains("listings.example.com"))
    }

    @Test
    fun `a disallow rule stops the search in enforce mode and quotes the rule`() = runTest {
        val gate = policy(PortalScrapeSettings(userConsented = true, robotsCompliance = RobotsCompliance.ENFORCE))
            .check(searchUrl) { RESTRICTIVE_ROBOTS }

        val failure = (gate as ScrapeGate.Denied).failure
        assertEquals(ListingScrapeFailureKind.DENIED_BY_POLICY, failure.kind)
        assertTrue(failure.message.contains("robots.txt"))
        assertTrue("the rule that fired must be visible: ${failure.message}", failure.message.contains("/homes/for_sale/"))
        assertTrue(failure.remediation!!.contains("licensed feed"))
    }

    @Test
    fun `an allowed path passes the gate`() = runTest {
        val gate = policy(PortalScrapeSettings(userConsented = true))
            .check(searchUrl) { ALLOW_ALL_ROBOTS }

        assertEquals(ScrapeGate.Allowed, gate)
    }

    @Test
    fun `advisory mode proceeds but the warning reaches the caller`() = runTest {
        val gate = policy(PortalScrapeSettings(userConsented = true, robotsCompliance = RobotsCompliance.ADVISORY))
            .check(searchUrl) { RESTRICTIVE_ROBOTS }

        val warnings = (gate as ScrapeGate.AllowedWithWarnings).warnings
        assertTrue(warnings.single().contains("advisory mode"))
    }

    @Test
    fun `an unreadable robots file fails closed in enforce mode and warns in advisory mode`() = runTest {
        val strict = policy(PortalScrapeSettings(userConsented = true)).check(searchUrl) { null }
        assertEquals(ListingScrapeFailureKind.DENIED_BY_POLICY, (strict as ScrapeGate.Denied).failure.kind)

        val advisory = policy(PortalScrapeSettings(userConsented = true, robotsCompliance = RobotsCompliance.ADVISORY))
            .check(searchUrl) { null }
        assertTrue((advisory as ScrapeGate.AllowedWithWarnings).warnings.single().contains("could not be read"))
    }

    @Test
    fun `settings reject impossible page counts`() {
        var thrown = false
        try {
            PortalScrapeSettings(maxPagesPerSearch = 0)
        } catch (expected: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `the rate limiter spaces page fetches without sleeping`() {
        var now = 1_000L
        val limiter = ScrapeRateLimiter(clock = { now })

        assertEquals(0L, limiter.waitBeforeNextRequest("https://www.zillow.com", 2_000))
        limiter.markRequest("https://www.zillow.com")

        now = 1_500L
        assertEquals(1_500L, limiter.waitBeforeNextRequest("https://www.zillow.com", 2_000))

        now = 3_001L
        assertEquals(0L, limiter.waitBeforeNextRequest("https://www.zillow.com", 2_000))

        // A different origin is not affected by the first one's pacing.
        assertEquals(0L, limiter.waitBeforeNextRequest("https://www.redfin.com", 2_000))
        // Zero interval means "never pace".
        limiter.markRequest("https://www.zillow.com")
        assertEquals(0L, limiter.waitBeforeNextRequest("https://www.zillow.com", 0))
    }
}
