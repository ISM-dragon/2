package com.example.urlintelligence

import com.example.urlintelligence.adapter.GenericWebAdapter
import com.example.urlintelligence.adapter.ZillowAdapter
import com.example.urlintelligence.compliance.AccessDecision
import com.example.urlintelligence.compliance.AccessPolicy
import com.example.urlintelligence.compliance.AccessRequest
import com.example.urlintelligence.compliance.AccessRule
import com.example.urlintelligence.compliance.CompositeAccessPolicy
import com.example.urlintelligence.compliance.RobotsPolicy
import com.example.urlintelligence.compliance.RobotsRules
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.idempotency.InMemoryIdempotencyStore
import com.example.urlintelligence.job.ImportJobState
import com.example.urlintelligence.job.InMemoryPropertyImportJobStore
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.resolver.PropertyImportResult
import com.example.urlintelligence.resolver.PropertyUrlResolver
import com.example.urlintelligence.resolver.ResolveOptions
import com.example.urlintelligence.retry.RetryPolicy
import com.example.urlintelligence.source.KnownSources
import com.example.urlintelligence.source.SourceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compliance: robots.txt, opt-in gates and the guarantee that this layer never works around
 * an access control. Nothing here "solves" anything — the assertions are that a refusal is
 * honoured, classified and never retried.
 */
class ComplianceRobotsTest {

    private val userAgent = "RealEstateAI/1.0 (+tests)"

    private fun policy(fixture: String?, status: Int = 200): Pair<RobotsPolicy, FakeTransport> {
        val transport = FakeTransport(
            bodies = listOf(fixture?.let { Fixtures.load(it) } ?: ""),
            statuses = listOf(status)
        )
        return RobotsPolicy(transport, userAgent, TestClock()) to transport
    }

    private fun accessRequest(url: String, path: String? = null) = AccessRequest(
        url = url,
        host = com.example.urlintelligence.url.UrlParts.hostOf(url),
        path = path ?: com.example.urlintelligence.url.UrlParts.pathOf(url),
        sourceId = "zillow",
        displayName = "Zillow"
    )

    // ---- rules parsing ---------------------------------------------------------------------

    @Test
    fun `robots rules honour prefixes wildcards and anchors`() {
        val rules = RobotsRules.parse(
            """
            User-agent: *
            Disallow: /search/
            Disallow: /*.json$
            Disallow: /photos/
            Allow: /photos/public/
            """.trimIndent()
        )

        assertFalse(rules.allows("/search/homes", userAgent))
        assertTrue(rules.allows("/homedetails/123", userAgent))
        assertFalse(rules.allows("/data/listing.json", userAgent))
        assertTrue(rules.allows("/data/listing.json.html", userAgent))
        assertFalse(rules.allows("/photos/private.jpg", userAgent))
        assertTrue("longest matching allow wins", rules.allows("/photos/public/front.jpg", userAgent))
    }

    @Test
    fun `the most specific user-agent group wins`() {
        val rules = RobotsRules.parse(Fixtures.load("robots_wildcards.txt"))
        // "*" group: /photos/ is disallowed, but /photos/listing-* is allowed.
        assertTrue(rules.allows("/photos/listing-1.jpg", "SomeOtherBot/2.0"))
        assertFalse(rules.allows("/photos/other.jpg", "SomeOtherBot/2.0"))
        // Named group overrides the wildcard group for that agent.
        assertFalse(rules.allows("/homedetails/123", userAgent))
    }

    @Test
    fun `a deny-all file denies everything and an empty file allows everything`() {
        val denyAll = RobotsRules.parse(Fixtures.load("robots_deny_all.txt"))
        assertFalse(denyAll.allows("/anything", userAgent))
        assertFalse(denyAll.allows("/", userAgent))

        assertTrue(RobotsRules.parse("").allows("/anything", userAgent))
        assertTrue(RobotsRules.parse("# only a comment\n").allows("/anything", userAgent))
        assertTrue(RobotsRules.parse("User-agent: *\nDisallow:\n").allows("/any", userAgent))
    }

    @Test
    fun `sitemaps and crawl delays are not access rules`() {
        val rules = RobotsRules.parse(
            "User-agent: *\nSitemap: https://x.test/sitemap.xml\nCrawl-delay: 30\nDisallow: /admin"
        )
        assertTrue(rules.allows("/homedetails/x", userAgent))
        assertFalse(rules.allows("/admin", userAgent))
    }

    // ---- the policy ------------------------------------------------------------------------

    @Test
    fun `a disallowed path is denied and an allowed path is permitted`() {
        val (robots, _) = policy("robots_disallow_listings.txt")
        runBlocking {
            val allowed = robots.check(accessRequest("https://www.example-portal.test/homedetails/12"))
            assertTrue(allowed is AccessDecision.Allowed)

            val denied = robots.check(accessRequest("https://www.example-portal.test/search/homes"))
            assertTrue(denied is AccessDecision.Denied)
            assertEquals(AccessRule.ROBOTS_DISALLOWED, (denied as AccessDecision.Denied).rule)
            assertTrue(denied.detail.contains("/search/"))
        }
    }

    @Test
    fun `robots rules are cached per origin`() {
        val (robots, transport) = policy("robots_disallow_listings.txt")
        runBlocking {
            robots.check(accessRequest("https://www.example-portal.test/homedetails/1"))
            robots.check(accessRequest("https://www.example-portal.test/homedetails/2"))
            robots.check(accessRequest("https://www.example-portal.test/homedetails/3"))
        }
        assertEquals("robots.txt must be fetched once per origin", 1, transport.requests.size)
        assertEquals(
            "https://www.example-portal.test/robots.txt",
            transport.requests.first().requestUrl
        )
    }

    @Test
    fun `a missing robots file allows crawling and an unavailable one denies it`() {
        val (missing, _) = policy(null, status = 404)
        runBlocking {
            assertTrue(missing.check(accessRequest("https://x.test/homedetails/1")) is AccessDecision.Allowed)
        }

        val (unavailable, _) = policy(null, status = 503)
        runBlocking {
            val decision = unavailable.check(accessRequest("https://x.test/homedetails/1"))
            assertTrue("policies must be fail-closed", decision is AccessDecision.Denied)
            assertEquals(AccessRule.ROBOTS_UNAVAILABLE, (decision as AccessDecision.Denied).rule)
        }

        val failing = RobotsPolicy(
            FakeTransport(failures = listOf(SourceFailure.Network("dns failure"))),
            userAgent,
            TestClock()
        )
        runBlocking {
            val decision = failing.check(accessRequest("https://x.test/homedetails/1"))
            assertTrue(decision is AccessDecision.Denied)
        }
    }

    @Test
    fun `a request for robots txt itself is always allowed`() {
        val (robots, _) = policy("robots_deny_all.txt")
        runBlocking {
            val decision = robots.check(accessRequest("https://x.test/robots.txt"))
            assertTrue(decision is AccessDecision.Allowed)
        }
    }

    @Test
    fun `composite policies deny on the first denial`() {
        val deny = AccessPolicy { AccessDecision.Denied(AccessRule.OPERATOR_DISABLED, "off") }
        val allow = AccessPolicy { AccessDecision.Allowed }
        val composite = CompositeAccessPolicy(allow, deny, allow)
        runBlocking {
            val decision = composite.check(accessRequest("https://x.test/homedetails/1"))
            assertTrue(decision is AccessDecision.Denied)
            assertEquals(AccessRule.OPERATOR_DISABLED, (decision as AccessDecision.Denied).rule)
        }
    }

    // ---- resolver integration ---------------------------------------------------------------

    private fun resolver(
        transport: FakeTransport,
        policy: AccessPolicy?,
        requirePolicy: Boolean = false,
        jobStore: InMemoryPropertyImportJobStore = InMemoryPropertyImportJobStore()
    ): Pair<PropertyUrlResolver, InMemoryPropertyImportJobStore> {
        val registry = SourceRegistry(
            descriptors = KnownSources.all(),
            adapters = listOf(ZillowAdapter(transport), GenericWebAdapter(transport))
        )
        val resolver = PropertyUrlResolver(
            registry = registry,
            normalizer = PropertyNormalizer(TestClock()),
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMillis = 1L, jitterRatio = 0.0),
            clock = TestClock(),
            jobStore = jobStore,
            resultStore = InMemoryIdempotencyStore(TestClock()),
            allowedSourceIds = setOf("zillow", "generic.web"),
            accessPolicy = policy,
            random = { 0.0 },
            idGenerator = { "job-policy" },
            sleeper = com.example.urlintelligence.retry.RecordingSleeper()
        )
        return resolver to jobStore
    }

    @Test
    fun `a robots denial stops the import before any network call`() {
        val transport = FakeTransport(
            bodies = listOf(Fixtures.load("robots_deny_all.txt")),
            bodiesByUrl = mapOf(
                "https://www.example-portal.test/robots.txt" to Fixtures.load("robots_deny_all.txt")
            )
        )
        val robots = RobotsPolicy(transport, userAgent, TestClock())
        val (resolver, jobStore) = resolver(transport, robots)

        val result = runBlocking { resolver.resolve(Fixtures.ZILLOW_URL) }
        assertTrue(result is PropertyImportResult.Failure)
        val failure = (result as PropertyImportResult.Failure).failure
        assertTrue("expected a policy block but was $failure", failure is SourceFailure.PolicyBlocked)
        assertEquals("POLICY_BLOCKED", failure.code)
        assertFalse(failure.retryable)

        // Exactly one network call: robots.txt. The listing itself was never requested.
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().requestUrl.endsWith("/robots.txt"))
        assertEquals(ImportJobState.FAILED, jobStore.findById("job-policy")!!.state)
    }

    @Test
    fun `an allowed robots file lets the import proceed`() {
        val transport = FakeTransport(
            bodiesByUrl = mapOf(
                "https://www.zillow.com/robots.txt" to Fixtures.load("robots_disallow_listings.txt"),
                Fixtures.ZILLOW_URL to Fixtures.load("zillow_listing.html")
            )
        )
        val robots = RobotsPolicy(transport, userAgent, TestClock())
        val (resolver, _) = resolver(transport, robots)

        val result = runBlocking { resolver.resolve(Fixtures.ZILLOW_URL) }
        assertTrue("expected success but was $result", result is PropertyImportResult.Success)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `an unconfigured policy can be made fatal`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val (resolver, _) = resolver(transport, policy = null, requirePolicy = true)

        val result = runBlocking {
            resolver.resolve(Fixtures.ZILLOW_URL, ResolveOptions(requireAccessPolicy = true))
        }
        assertTrue(result is PropertyImportResult.Failure)
        val failure = (result as PropertyImportResult.Failure).failure
        assertTrue(failure is SourceFailure.PolicyBlocked)
        assertTrue((failure as SourceFailure.PolicyBlocked).rule.contains("POLICY_UNCONFIGURED"))
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `an unconfigured policy is a warning when the caller accepts it`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val (resolver, _) = resolver(transport, policy = null)

        val result = runBlocking { resolver.resolve(Fixtures.ZILLOW_URL) }
        assertTrue(result is PropertyImportResult.Success)
        assertTrue(
            (result as PropertyImportResult.Success).warnings.any { it.contains("no access policy") }
        )
    }

    @Test
    fun `a broken policy fails closed instead of allowing the fetch`() {
        val exploding = AccessPolicy { throw IllegalStateException("policy backend down") }
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val (resolver, _) = resolver(transport, exploding)

        val result = runBlocking { resolver.resolve(Fixtures.ZILLOW_URL) }
        assertTrue(result is PropertyImportResult.Failure)
        assertTrue((result as PropertyImportResult.Failure).failure is SourceFailure.PolicyBlocked)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `inspection reports whether a policy is wired`() {
        val transport = FakeTransport()
        val (withPolicy, _) = resolver(transport, AccessPolicy.PERMISSIVE_PUBLIC_WEB)
        val (withoutPolicy, _) = resolver(transport, policy = null)

        assertTrue(withPolicy.inspect(Fixtures.ZILLOW_URL).accessPolicyConfigured)
        assertFalse(withoutPolicy.inspect(Fixtures.ZILLOW_URL).accessPolicyConfigured)
        assertNotNull(withoutPolicy.inspect(Fixtures.ZILLOW_URL).detection)
        assertNull(withoutPolicy.inspect("not a url at all").detection)
    }
}
