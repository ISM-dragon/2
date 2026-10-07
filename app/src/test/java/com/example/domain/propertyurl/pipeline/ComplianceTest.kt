package com.example.domain.propertyurl.pipeline

import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.FakeHttpFetcher
import com.example.domain.propertyurl.FixedClock
import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.parse.RobotsTxt
import com.example.domain.propertyurl.port.DefaultFetchPolicies
import com.example.domain.propertyurl.port.FetchPolicyDecision
import com.example.domain.propertyurl.source.SourceCatalog
import com.example.domain.propertyurl.source.SourceRegistry
import com.example.domain.propertyurl.url.PropertyUrl
import com.example.domain.propertyurl.url.PropertyUrlValidator
import com.example.domain.propertyurl.url.UrlValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compliance guardrails: robots.txt, per-source rate limiting and the circuit breaker. These are the
 * parts that decide whether the feature stays welcome on the sources it depends on.
 */
class ComplianceTest {

    private val registry = SourceRegistry.build(SourceCatalog.ALL)
    private val validator = PropertyUrlValidator()

    private fun url(raw: String): PropertyUrl = (validator.validate(raw) as UrlValidationResult.Valid).url

    // --- robots.txt --------------------------------------------------------------------------------

    @Test
    fun `robots file is parsed into groups with crawl delay`() {
        val robots = RobotsTxt.parse(Fixtures.text("robots.txt"))

        assertEquals(2, robots.groups.size)
        assertTrue(robots.isAllowed("/homedetails/4127-oak-hollow-dr", "some-other-bot"))
        assertFalse("Disallow rules must be honoured", robots.isAllowed("/search?q=austin", "some-other-bot"))
        assertEquals(
            "crawl delay must be exposed per group",
            2.0,
            robots.selectGroup("some-other-bot")!!.crawlDelaySeconds!!,
            0.001
        )
    }

    @Test
    fun `the most specific user-agent group wins`() {
        val robots = RobotsTxt.parse(Fixtures.text("robots.txt"))

        val agent = "RealEstateAI-PropertyIntel/1.0"
        assertEquals("the named group", "realestateai-propertyintel", robots.selectGroup(agent)!!.agents.first())
        assertFalse(
            "the named group disallows print pages",
            robots.isAllowed("/homedetails/4127/print", agent)
        )
        assertTrue("but allows the listing itself", robots.isAllowed("/homedetails/4127", agent))
    }

    @Test
    fun `wildcards and anchors are supported`() {
        val robots = RobotsTxt.parse(
            """
            User-agent: *
            Disallow: /*.pdf$
            Disallow: /private/
            Allow: /private/public-page
            """.trimIndent()
        )

        assertFalse(robots.isAllowed("/docs/listing.pdf", "bot"))
        assertTrue("inner pages are fine", robots.isAllowed("/docs/listing.pdf.html", "bot"))
        assertFalse(robots.isAllowed("/private/secret", "bot"))
        assertTrue("the more specific allow wins", robots.isAllowed("/private/public-page", "bot"))
    }

    @Test
    fun `an empty robots file allows everything and a disallow-all blocks everything`() {
        assertTrue(RobotsTxt.ALLOW_ALL.isAllowed("/anything", "bot"))
        assertFalse(RobotsTxt.DENY_ALL.isAllowed("/anything", "bot"))
    }

    @Test
    fun `robots policy denies disallowed paths and allows the rest`() = runBlocking {
        val fetcher = FakeHttpFetcher().on("robots.txt", body = Fixtures.text("robots.txt"))
        val clock = FixedClock()
        val policy = DefaultFetchPolicies.robotsAware(fetcher, clock, "RealEstateAI-PropertyIntel/1.0")

        val allowed = policy.check(url("https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"), SourceCatalog.ZILLOW)
        val denied = policy.check(url("https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/print"), SourceCatalog.ZILLOW)

        assertTrue(allowed is FetchPolicyDecision.Allowed)
        assertTrue(denied is FetchPolicyDecision.Denied)
        assertEquals("the rule that denied the fetch must be explained", "robots-disallow", (denied as FetchPolicyDecision.Denied).rule)
    }

    @Test
    fun `robots is cached per origin and a missing file allows fetching`() = runBlocking {
        val fetcher = FakeHttpFetcher().on("robots.txt", status = 404, body = "not found")
        val clock = FixedClock()
        val policy = DefaultFetchPolicies.robotsAware(fetcher, clock, "bot")

        val first = policy.check(url("https://www.zillow.com/homedetails/1/20451237_zpid/"), SourceCatalog.ZILLOW)
        val second = policy.check(url("https://www.zillow.com/homedetails/2/20451238_zpid/"), SourceCatalog.ZILLOW)

        assertTrue(first is FetchPolicyDecision.Allowed)
        assertTrue(second is FetchPolicyDecision.Allowed)
        assertEquals("robots.txt is read once per origin, not per listing", 1, fetcher.totalHits)
    }

    @Test
    fun `disabled and planned sources are denied by the capability policy`() = runBlocking {
        val policy = DefaultFetchPolicies.unrestricted()

        val planned = policy.check(url("https://www.apartments.com/austin-tx/1/1a2b3c4/"), SourceCatalog.APARTMENTS_COM)
        assertTrue("a planned source has no adapter yet", planned is FetchPolicyDecision.Denied)

        val available = policy.check(url("https://www.zillow.com/homedetails/1/20451237_zpid/"), SourceCatalog.ZILLOW)
        assertTrue(available is FetchPolicyDecision.Allowed)
    }

    // --- Rate limiter ------------------------------------------------------------------------------

    @Test
    fun `rate limiter allows a burst then makes the caller wait`() {
        val clock = FixedClock()
        val limiter = SourceRateLimiter(clock, defaultRequestsPerMinute = 60, burstAllowance = 1)

        // capacity = 1 token + the burst allowance, so the first two calls on a cold bucket are free.
        assertEquals("the first request is free", 0L, limiter.acquire("zillow"))
        assertEquals("the burst allowance covers the second", 0L, limiter.acquire("zillow"))

        val wait = limiter.acquire("zillow")
        assertTrue("after the burst the caller must wait for a token", wait > 0)
        assertTrue("one token per second at 60 rpm", wait <= 1_000)

        // `acquire` reserves the slot: the caller sleeps the returned time and only then fetches, so
        // steady-state pacing asks for one interval per request.
        clock.advance(wait)
        assertEquals("pacing stays at one request per interval", wait, limiter.acquire("zillow"))
    }

    @Test
    fun `rate limits are independent per source`() {
        val limiter = SourceRateLimiter(FixedClock(), defaultRequestsPerMinute = 60, burstAllowance = 1)

        assertEquals(0L, limiter.acquire("zillow"))
        assertEquals("another source must not be throttled by this one", 0L, limiter.acquire("redfin"))
    }

    @Test
    fun `per source overrides are honoured`() {
        val limiter = SourceRateLimiter(
            FixedClock(),
            defaultRequestsPerMinute = 600,
            burstAllowance = 0,
            overrides = mapOf("zillow" to 12)
        )

        assertEquals(0L, limiter.acquire("zillow"))
        val wait = limiter.acquire("zillow")
        assertTrue("zillow must follow its 12 rpm override, not the default", wait >= 4_000)
    }

    // --- Circuit breaker ---------------------------------------------------------------------------

    private fun failure(kind: SourceFailureKind = SourceFailureKind.HTTP_SERVER_ERROR) = SourceFailure(
        kind = kind,
        message = "boom",
        sourceId = "zillow",
        occurredAtEpochMillis = 0
    )

    @Test
    fun `the breaker opens after repeated source level failures and then half-opens`() {
        val clock = FixedClock()
        val breaker = SourceHealthTracker(clock, failureThreshold = 3, openDurationMillis = 60_000)

        assertNull("a healthy source is allowed", breaker.beforeFetch("zillow"))
        repeat(3) { breaker.recordFailure("zillow", failure()) }

        val blocked = breaker.beforeFetch("zillow")
        assertNotNull("an open breaker must block the fetch", blocked)
        assertEquals(SourceFailureKind.SOURCE_DISABLED, blocked!!.kind)
        assertNotNull("the caller is told when to come back", blocked.retryAfterSeconds)
        assertTrue("blocked fetches wait for the cool-down instead of retrying at once", blocked.isCooldownRetry)

        clock.advance(61_000)
        assertNull("after the cool-down the breaker probes again", breaker.beforeFetch("zillow"))
        assertEquals(SourceHealthTracker.CircuitState.HALF_OPEN, breaker.snapshot("zillow").state)

        breaker.recordSuccess("zillow")
        assertEquals(SourceHealthTracker.CircuitState.CLOSED, breaker.snapshot("zillow").state)
    }

    @Test
    fun `client errors do not trip the breaker`() {
        val breaker = SourceHealthTracker(FixedClock(), failureThreshold = 2)

        repeat(5) { breaker.recordFailure("zillow", failure(SourceFailureKind.HTTP_NOT_FOUND)) }

        assertNull("a deleted listing says nothing about source health", breaker.beforeFetch("zillow"))
        assertEquals(SourceHealthTracker.CircuitState.CLOSED, breaker.snapshot("zillow").state)
    }

    @Test
    fun `rate limits trip the breaker for that source only`() {
        val breaker = SourceHealthTracker(FixedClock(), failureThreshold = 1)

        breaker.recordFailure("zillow", failure(SourceFailureKind.HTTP_RATE_LIMITED))
        assertNotNull(breaker.beforeFetch("zillow"))
        assertNull("other sources stay available", breaker.beforeFetch("redfin"))
    }

    @Test
    fun `half-open failures re-open the breaker`() {
        val clock = FixedClock()
        val breaker = SourceHealthTracker(clock, failureThreshold = 1, openDurationMillis = 1_000)

        breaker.recordFailure("zillow", failure())
        clock.advance(1_001)
        assertNull(breaker.beforeFetch("zillow"))
        breaker.recordFailure("zillow", failure())

        assertEquals(SourceHealthTracker.CircuitState.OPEN, breaker.snapshot("zillow").state)
        assertNotNull(breaker.beforeFetch("zillow"))
    }

    @Test
    fun `health snapshot exposes counters for the dashboard`() {
        val breaker = SourceHealthTracker(FixedClock(), failureThreshold = 5)
        breaker.recordFailure("zillow", failure())

        val snapshot = breaker.snapshot("zillow")
        assertEquals(1, snapshot.consecutiveFailures)
        assertEquals(SourceHealthTracker.CircuitState.CLOSED, snapshot.state)
        assertNull(snapshot.openedAtEpochMillis)
    }
}
