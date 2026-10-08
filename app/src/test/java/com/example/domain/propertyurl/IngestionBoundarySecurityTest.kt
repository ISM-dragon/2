package com.example.domain.propertyurl

import com.example.domain.propertyurl.job.IdempotencyPolicy
import com.example.domain.propertyurl.job.InMemoryPropertyImportJobStore
import com.example.domain.propertyurl.job.RetryPolicy
import com.example.domain.propertyurl.normalize.ValueGuards
import com.example.domain.propertyurl.parse.RobotsTxt
import com.example.domain.propertyurl.pipeline.ImportOutcome
import com.example.domain.propertyurl.pipeline.PropertyImportOptions
import com.example.domain.propertyurl.pipeline.PropertyUrlIntelligenceFactory
import com.example.domain.propertyurl.port.CredentialProvider
import com.example.domain.propertyurl.port.DefaultFetchPolicies
import com.example.domain.propertyurl.port.FetchPolicyDecision
import com.example.domain.propertyurl.port.RobotsTxtFetchPolicy
import com.example.domain.propertyurl.port.SourceCredentials
import com.example.domain.propertyurl.port.TransportFailureKind
import com.example.domain.propertyurl.source.SourceCatalog
import com.example.domain.propertyurl.source.SourceRegistry
import com.example.domain.propertyurl.url.PropertyUrl
import com.example.domain.propertyurl.url.PropertyUrlNormalizer
import com.example.domain.propertyurl.url.PropertyUrlResolver
import com.example.domain.propertyurl.url.PropertyUrlValidator
import com.example.domain.propertyurl.url.UrlHosts
import com.example.domain.propertyurl.url.UrlResolutionResult
import com.example.domain.propertyurl.url.UrlValidationCode
import com.example.domain.propertyurl.url.UrlValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Regression tests for the property ingestion / credential boundary:
 *
 *  * URLs extracted from a hostile listing document (image URLs) are screened the same way as the
 *    listing URL itself, because they are persisted and later fetched by the image loader.
 *  * the non-routable host screen covers infrastructure name spaces and reserved address ranges,
 *  * a malformed robots.txt cannot silently re-allow a disallowed path,
 *  * the robots gate fails closed when the rules cannot be evaluated,
 *  * public listing pages are fetched anonymously, even when a credential provider offers tokens
 *    for their source id.
 */
class IngestionBoundarySecurityTest {

    private val validator = PropertyUrlValidator()

    private fun url(raw: String): PropertyUrl = (validator.validate(raw) as UrlValidationResult.Valid).url

    // --- Image URLs coming out of an untrusted document ---------------------------------------------

    @Test
    fun `image urls from a listing document cannot point at private or local endpoints`() {
        val hostile = listOf(
            "http://127.0.0.1/router.jpg",
            "http://localhost:8080/admin/probe.jpg",
            "https://192.168.1.1/status.jpg",
            "https://10.10.0.5/internal.jpg",
            "https://172.16.4.9/x.jpg",
            "http://169.254.169.254/latest/meta-data/iam.jpg",
            "https://[::1]/loopback.jpg",
            "https://[fe80::1]/link-local.jpg",
            "https://router.internal/dashboard.jpg",
            "https://nas.home.arpa/photo.jpg",
            "https://metadata.google.internal/computeMetadata.jpg",
            "https://user:unit-test-secret@cdn.example.com/a.jpg",
            "https://cdn.example.com/../../etc/passwd.jpg",
            "//cdn.example.com/protocol-relative.jpg",
            "file:///data/data/com.example/secret.jpg",
            "data:image/png;base64,QUJDREVGRw=="
        )

        hostile.forEach { candidate ->
            val guard = ValueGuards.imageUrl(candidate)
            assertFalse("must be rejected: $candidate", guard.accepted)
        }
    }

    @Test
    fun `image urls on public hosts still pass the guard`() {
        assertTrue(ValueGuards.imageUrl("https://photos.zillowstatic.com/fp/8d1f2c3a/front.jpg").accepted)
        assertTrue(ValueGuards.imageUrl("https://ssl.cdn-redfin.com/photo/1/sample-front.jpg").accepted)
        assertTrue(ValueGuards.imageUrl("http://cdn.example-brokerage.com/listings/4127/front.jpg").accepted)
        assertFalse(ValueGuards.imageUrl("not-a-url").accepted)
        assertFalse(ValueGuards.imageUrl(null).accepted)
    }

    // --- Non-routable host screening -----------------------------------------------------------------

    @Test
    fun `private network screening covers infrastructure names and reserved ranges`() {
        listOf(
            "localhost",
            "db.localhost",
            "printer.local",
            "nas.home.arpa",
            "gateway.internal",
            "host.localdomain",
            "1.0.0.127.in-addr.arpa",
            "metadata",
            "metadata.google.internal",
            "10.0.0.1",
            "172.20.3.4",
            "192.168.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "127.0.0.1",
            "192.0.0.9",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
            "fc00::1",
            "fe80::1"
        ).forEach { host ->
            assertTrue("must be treated as non-routable: $host", UrlHosts.isPrivateNetwork(host))
        }

        // Ordinary public listing hosts must not be affected.
        listOf(
            "www.zillow.com",
            "photos.zillowstatic.com",
            "ssl.cdn-redfin.com",
            "some-broker.example",
            "milan.example",
            "fc.example.com"
        ).forEach { host ->
            assertFalse("public host flagged as private: $host", UrlHosts.isPrivateNetwork(host))
        }
    }

    @Test
    fun `the url validator rejects infrastructure hosts before any fetch`() {
        val rejected = validator.validate("https://gateway.internal/listings/4127")

        assertTrue(rejected is UrlValidationResult.Invalid)
        val codes = (rejected as UrlValidationResult.Invalid).issues.map { it.code }
        assertTrue(
            "expected PRIVATE_NETWORK_HOST but was $codes",
            codes.contains(UrlValidationCode.PRIVATE_NETWORK_HOST)
        )
    }

    @Test
    fun `credential like query values never reach the canonical url`() {
        val normalized = PropertyUrlNormalizer.normalize(
            "https://www.zillow.com/homedetails/4127/20451237_zpid/" +
                "?access_token=unit-test-canonical-token&zpid=20451237"
        )

        assertFalse(
            "the canonical url is fetched and stored: ${normalized.url.normalized}",
            normalized.url.normalized.contains("unit-test-canonical-token")
        )
        assertFalse(normalized.url.identitySeed.contains("unit-test-canonical-token"))
        assertTrue(
            "the dropped parameter must still be reported by name",
            normalized.sensitiveParameters.contains("access_token")
        )
        assertTrue(
            "a non-credential query parameter must survive",
            normalized.url.normalized.contains("zpid=20451237")
        )
    }

    // --- robots.txt ---------------------------------------------------------------------------------

    @Test
    fun `a blank user agent token cannot shadow the wildcard disallow rule`() {
        val robots = RobotsTxt.parse(
            """
            User-agent:
            Allow: /

            User-agent: *
            Disallow: /
            """.trimIndent()
        )

        assertFalse(
            "a blank User-agent names no crawler, so the '*' Disallow must still apply",
            robots.isAllowed("/homedetails/4127", "RealEstateAI-PropertyIntel/1.0")
        )
        assertFalse(robots.isAllowed("/homedetails/4127", "some-other-bot"))
    }

    @Test
    fun `named groups keep winning over the wildcard group`() {
        val robots = RobotsTxt.parse(
            """
            User-agent: *
            Disallow: /search

            User-agent: RealEstateAI-PropertyIntel
            Disallow: /homedetails/*/print
            Allow: /
            """.trimIndent()
        )

        val agent = "RealEstateAI-PropertyIntel/1.0"
        assertEquals("realestateai-propertyintel", robots.selectGroup(agent)!!.agents.first())
        assertTrue(robots.isAllowed("/homedetails/4127", agent))
        assertFalse(robots.isAllowed("/homedetails/4127/print", agent))
        assertFalse(robots.isAllowed("/search?q=austin", "some-other-bot"))
        assertTrue(robots.isAllowed("/homedetails/4127", "some-other-bot"))
    }

    @Test
    fun `robots gate denies when the rules cannot be evaluated`() = runBlocking {
        val unavailableStatuses = listOf(500, 502, 503)
        unavailableStatuses.forEach { status ->
            val fetcher = FakeHttpFetcher().on("robots.txt", status = status, body = "boom")
            val policy = DefaultFetchPolicies.robotsAware(fetcher, FixedClock(), "RealEstateAI-PropertyIntel/1.0")

            val decision = policy.check(
                url("https://www.zillow.com/homedetails/4127/20451237_zpid/"),
                SourceCatalog.ZILLOW
            )

            assertTrue("HTTP $status must fail closed, was $decision", decision is FetchPolicyDecision.Denied)
            assertEquals("robots-unavailable", (decision as FetchPolicyDecision.Denied).rule)
        }

        val transportFailure = FakeHttpFetcher().on(
            "robots.txt",
            transportError = TransportFailureKind.TIMEOUT
        )
        val transportPolicy = DefaultFetchPolicies.robotsAware(
            transportFailure,
            FixedClock(),
            "RealEstateAI-PropertyIntel/1.0"
        )
        val transportDecision = transportPolicy.check(
            url("https://www.zillow.com/homedetails/4127/20451237_zpid/"),
            SourceCatalog.ZILLOW
        )
        assertTrue(transportDecision is FetchPolicyDecision.Denied)
    }

    @Test
    fun `robots gate still allows when no rules are published and honours published rules`() =
        runBlocking {
            val missing = FakeHttpFetcher().on("robots.txt", status = 404, body = "not found")
            val missingPolicy = DefaultFetchPolicies.robotsAware(missing, FixedClock(), "bot")
            assertTrue(
                missingPolicy.check(url("https://www.zillow.com/homedetails/1/20451237_zpid/"), SourceCatalog.ZILLOW)
                    is FetchPolicyDecision.Allowed
            )

            val published = FakeHttpFetcher().on("robots.txt", body = Fixtures.text("robots.txt"))
            val publishedPolicy =
                DefaultFetchPolicies.robotsAware(published, FixedClock(), "RealEstateAI-PropertyIntel/1.0")
            assertTrue(
                publishedPolicy.check(
                    url("https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"),
                    SourceCatalog.ZILLOW
                ) is FetchPolicyDecision.Allowed
            )
            assertTrue(
                publishedPolicy.check(
                    url("https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/print"),
                    SourceCatalog.ZILLOW
                ) is FetchPolicyDecision.Denied
            )
        }

    @Test
    fun `an operator can still opt an origin into allow when robots is unavailable`() = runBlocking {
        val fetcher = FakeHttpFetcher().on("robots.txt", status = 503, body = "boom")
        val policy = RobotsTxtFetchPolicy(
            httpFetcher = fetcher,
            clock = FixedClock(),
            userAgent = "RealEstateAI-PropertyIntel/1.0",
            behaviorWhenUnavailable = RobotsTxtFetchPolicy.UnavailableBehavior.ALLOW
        )

        assertTrue(
            policy.check(
                url("https://www.zillow.com/homedetails/4127/20451237_zpid/"),
                SourceCatalog.ZILLOW
            ) is FetchPolicyDecision.Allowed
        )
    }

    // --- Credential boundary ------------------------------------------------------------------------

    @Test
    fun `public listing pages are fetched without credentials`() = runBlocking {
        val fetcher = FakeHttpFetcher()
            .on("zillow.com/homedetails", body = Fixtures.text("zillow-homedetails.html"))
            .on("some-broker.example/listings", body = Fixtures.text("generic-brokerage-listing.html"))

        val engine = PropertyUrlIntelligenceFactory.create(
            jobStore = InMemoryPropertyImportJobStore(),
            httpFetcher = fetcher,
            clock = FixedClock(),
            idGenerator = SequentialIdGenerator(),
            credentialProvider = CredentialProvider.of(
                "zillow" to SourceCredentials(
                    authHeaderValue = "Bearer unit-test-zsecret",
                    cookieHeader = "zsession=unit-test-zcookie",
                    apiKey = "unit-test-zkey"
                ),
                "generic_web" to SourceCredentials(
                    authHeaderValue = "Bearer unit-test-gsecret",
                    cookieHeader = "gsession=unit-test-gcookie"
                )
            ),
            sleeper = RecordingSleeper(),
            options = PropertyImportOptions(maxFetchAttempts = 1),
            retryPolicy = RetryPolicy(maxAttempts = 1),
            idempotencyPolicy = IdempotencyPolicy(),
            rateLimiter = null,
            healthTracker = null
        )

        val portal = engine.import("https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/")
        val generic = engine.import("https://some-broker.example/listings/4127-oak-hollow-dr")

        assertTrue("portal import must run, was ${portal.describe()}", portal !is ImportOutcome.Rejected)
        assertTrue("generic import must run, was ${generic.describe()}", generic !is ImportOutcome.Rejected)

        val listingRequests = fetcher.requests.filterNot { it.url.contains("robots.txt") }
        assertTrue("the listings must have been fetched", listingRequests.isNotEmpty())
        listingRequests.forEach { request ->
            val sent = request.headers.keys.map { it.lowercase(Locale.US) }
            assertTrue(
                "no credential header may be sent to ${request.url}, saw ${request.headers.keys}",
                sent.none { it in CREDENTIAL_HEADERS }
            )
        }
    }

    @Test
    fun `a source that declares credentials still receives them`() = runBlocking {
        val fetcher = FakeHttpFetcher()
            .on("some-broker.example/listings", body = Fixtures.text("generic-brokerage-listing.html"))

        val credentialedGeneric = SourceCatalog.GENERIC_WEB.copy(
            capabilities = SourceCatalog.GENERIC_WEB.capabilities.copy(requiresCredentials = true)
        )
        val engine = PropertyUrlIntelligenceFactory.create(
            jobStore = InMemoryPropertyImportJobStore(),
            httpFetcher = fetcher,
            clock = FixedClock(),
            idGenerator = SequentialIdGenerator(),
            credentialProvider = CredentialProvider.of(
                "generic_web" to SourceCredentials(authHeaderValue = "Bearer unit-test-ptoken")
            ),
            sleeper = RecordingSleeper(),
            registry = SourceRegistry.build(SourceCatalog.ALL).plus(credentialedGeneric),
            options = PropertyImportOptions(maxFetchAttempts = 1),
            retryPolicy = RetryPolicy(maxAttempts = 1),
            idempotencyPolicy = IdempotencyPolicy(),
            rateLimiter = null,
            healthTracker = null
        )

        val outcome = engine.import("https://some-broker.example/listings/4127-oak-hollow-dr")

        assertTrue("import must run, was ${outcome.describe()}", outcome !is ImportOutcome.Rejected)
        val listingRequest = fetcher.requests.first { !it.url.contains("robots.txt") }
        assertEquals(
            "Bearer unit-test-ptoken",
            listingRequest.headers["Authorization"]
        )
    }

    @Test
    fun `credential values never reach the persisted job record`() = runBlocking {
        val fetcher = FakeHttpFetcher().on("zillow.com/homedetails", status = 404, body = "gone")
        val engine = PropertyUrlIntelligenceFactory.create(
            jobStore = InMemoryPropertyImportJobStore(),
            httpFetcher = fetcher,
            clock = FixedClock(),
            idGenerator = SequentialIdGenerator(),
            credentialProvider = CredentialProvider.of(
                "zillow" to SourceCredentials(authHeaderValue = "Bearer unit-test-leak")
            ),
            sleeper = RecordingSleeper(),
            options = PropertyImportOptions(maxFetchAttempts = 1),
            retryPolicy = RetryPolicy(maxAttempts = 1),
            rateLimiter = null,
            healthTracker = null
        )

        val outcome = engine.import("https://www.zillow.com/homedetails/4127/20451237_zpid/?access_token=unit-test-query-token")

        val job = outcome.job
        val serialized = com.example.domain.propertyurl.store.JobCodec.encode(job)
        assertFalse(serialized.contains("unit-test-query-token"))
        assertFalse(serialized.contains("unit-test-leak"))
        fetcher.requests.forEach { request ->
            val sent = request.headers.keys.map { it.lowercase(Locale.US) }
            assertTrue(
                "no credential header may be sent to ${request.url}, saw ${request.headers.keys}",
                sent.none { it in CREDENTIAL_HEADERS }
            )
        }
    }

    @Test
    fun `url derived idempotency keys stay deterministic and secret free`() {
        val resolver = PropertyUrlResolver(SourceRegistry.build(SourceCatalog.ALL))
        val pasted = "https://some-broker.example/listings/4127-oak-hollow-dr?access_token=unit-test-url-token"

        val first = (resolver.resolve(pasted) as UrlResolutionResult.Resolved).resolved
        val second = (resolver.resolve(pasted) as UrlResolutionResult.Resolved).resolved

        assertEquals("the same listing must keep deduplicating", first.idempotencyKey, second.idempotencyKey)
        assertTrue("key must be derived, was ${first.idempotencyKey}", first.idempotencyKey.startsWith("url:"))
        assertFalse(first.idempotencyKey.contains("unit-test-url-token"))
    }

    @Test
    fun `a credential bearing url is not written to the job store in clear text`() = runBlocking {
        val fetcher = FakeHttpFetcher()
            .on("some-broker.example/listings", body = Fixtures.text("generic-brokerage-listing.html"))
        val engine = PropertyUrlIntelligenceFactory.create(
            jobStore = InMemoryPropertyImportJobStore(),
            httpFetcher = fetcher,
            clock = FixedClock(),
            idGenerator = SequentialIdGenerator(),
            sleeper = RecordingSleeper(),
            options = PropertyImportOptions(maxFetchAttempts = 1),
            retryPolicy = RetryPolicy(maxAttempts = 1),
            rateLimiter = null,
            healthTracker = null
        )

        val outcome = engine.import(
            "https://some-broker.example/listings/4127-oak-hollow-dr?access_token=unit-test-url-token"
        )

        val serialized = com.example.domain.propertyurl.store.JobCodec.encode(outcome.job)
        assertFalse("the token must not be persisted: $serialized", serialized.contains("unit-test-url-token"))
    }

    private companion object {
        val CREDENTIAL_HEADERS = setOf("authorization", "cookie", "x-api-key", "proxy-authorization")
    }
}
