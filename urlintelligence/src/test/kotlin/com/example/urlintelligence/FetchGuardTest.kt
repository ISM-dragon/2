package com.example.urlintelligence

import com.example.urlintelligence.adapter.GenericWebAdapter
import com.example.urlintelligence.adapter.PropertyParseResult
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.failure.BlockReason
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.failure.SourceFailureClassifier
import com.example.urlintelligence.fetch.ContentKind
import com.example.urlintelligence.fetch.ContentTypes
import com.example.urlintelligence.fetch.FetchLimits
import com.example.urlintelligence.fetch.ResponseGuard
import com.example.urlintelligence.fetch.ResponseGuardResult
import com.example.urlintelligence.provenance.FetchOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Response guarding: everything a provider (or a broken transport) can return that must never
 * reach a parser — oversized, empty, binary, off-host-redirect, challenge pages.
 */
class FetchGuardTest {

    private val limits = FetchLimits(
        maxResponseBytes = 4_000,
        minResponseBytes = 32,
        maxRedirects = 2,
        allowedContentTypes = setOf("text/html", "application/json")
    )
    private val guard = ResponseGuard(limits)

    private fun request(url: String = Fixtures.ZILLOW_URL) =
        SourceFetchRequest(url, "corr-guard", limits = limits)

    private fun response(
        body: String = Fixtures.load("zillow_listing.html"),
        status: Int = 200,
        contentType: String? = "text/html; charset=utf-8",
        finalUrl: String = Fixtures.ZILLOW_URL,
        headers: Map<String, String> = emptyMap(),
        byteSize: Int = -1,
        redirectCount: Int = 0,
        origin: FetchOrigin = FetchOrigin.REPLAYED
    ) = SourceFetchResponse.Success(
        statusCode = status,
        body = body,
        contentType = contentType,
        finalUrl = finalUrl,
        fetchedAtEpochMillis = 1_700_000_000_000L,
        headers = headers,
        reportedByteSize = byteSize,
        redirectCount = redirectCount,
        origin = origin
    )

    private fun rejection(response: SourceFetchResponse.Success): SourceFailure {
        val result = guard.inspect(request(), response)
        assertTrue("expected a rejection but got $result", result is ResponseGuardResult.Rejected)
        return (result as ResponseGuardResult.Rejected).failure
    }

    // ---- healthy documents ---------------------------------------------------------------

    @Test
    fun `a normal html document passes and reports its kind`() {
        val result = guard.inspect(request(), response())
        assertTrue(result is ResponseGuardResult.Usable)
        val inspected = (result as ResponseGuardResult.Usable).inspected
        assertEquals(ContentKind.HTML, inspected.kind)
        assertFalse(inspected.redirected)
        assertEquals(Fixtures.ZILLOW_URL, inspected.finalUrl)
    }

    @Test
    fun `a missing content type is sniffed from the body`() {
        val result = guard.inspect(request(), response(contentType = null))
        val inspected = (result as ResponseGuardResult.Usable).inspected
        assertEquals(ContentKind.HTML, inspected.kind)
    }

    @Test
    fun `content types are parsed without parameters and case differences`() {
        assertEquals("text/html", ContentTypes.parse("TEXT/HTML; charset=UTF-8"))
        assertEquals(ContentKind.JSON, ContentTypes.kindOf("application/json"))
        assertEquals(ContentKind.EMPTY, ContentTypes.sniff("   "))
        assertEquals(ContentKind.HTML, ContentTypes.sniff("<!DOCTYPE html><html></html>"))
    }

    // ---- size / emptiness ----------------------------------------------------------------

    @Test
    fun `an oversized document is rejected and never parsed`() {
        val failure = rejection(response(byteSize = 999_999))
        assertTrue(failure is SourceFailure.PayloadTooLarge)
        assertEquals("PAYLOAD_TOO_LARGE", failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun `a blank document is an empty response`() {
        val failure = rejection(response(body = "   \n  "))
        assertTrue(failure is SourceFailure.EmptyResponse)
        assertEquals("EMPTY_RESPONSE", failure.code)
    }

    @Test
    fun `a truncated document below the usable minimum is rejected`() {
        val failure = rejection(response(body = "<html>"))
        assertTrue(failure is SourceFailure.EmptyResponse)
    }

    @Test
    fun `an empty body fails the adapter instead of producing a draft`() {
        val adapter = GenericWebAdapter(FakeTransport())
        val result = adapter.parse(
            response(body = ""),
            request()
        )
        assertTrue(result is PropertyParseResult.Failure)
        assertTrue((result as PropertyParseResult.Failure).failure is SourceFailure.EmptyResponse)
    }

    // ---- content type ---------------------------------------------------------------------

    @Test
    fun `a binary payload is rejected as an unsupported content type`() {
        val binary = "\u0000\u0001\u0002\u0003\u0004binary".repeat(20)
        val failure = rejection(response(body = binary, contentType = "application/pdf"))
        assertTrue(failure is SourceFailure.UnsupportedContentType)
        assertEquals("UNSUPPORTED_CONTENT_TYPE", failure.code)
        assertEquals("application/pdf", (failure as SourceFailure.UnsupportedContentType).contentType)
    }

    @Test
    fun `an image served with a 200 is not parsed as html`() {
        val png = "\u0089PNG\r\n\u001a\n" + "\u0000".repeat(80)
        val failure = rejection(response(body = png, contentType = "image/png"))
        assertTrue(failure is SourceFailure.UnsupportedContentType)
    }

    // ---- redirects --------------------------------------------------------------------------

    @Test
    fun `a same-family redirect is allowed and reported`() {
        val result = guard.inspect(
            request("https://zillow.com/homedetails/x/12345678_zpid"),
            response(
                finalUrl = "https://www.zillow.com/homedetails/x/12345678_zpid",
                redirectCount = 1
            )
        )
        val inspected = (result as ResponseGuardResult.Usable).inspected
        assertTrue(inspected.redirected)
        assertFalse(inspected.crossHostRedirect)
    }

    @Test
    fun `a redirect that leaves the provider host family is refused`() {
        val failure = rejection(
            response(finalUrl = "https://evil.example.net/collect", redirectCount = 1)
        )
        assertTrue(failure is SourceFailure.RedirectNotAllowed)
        assertEquals("REDIRECT_NOT_ALLOWED", failure.code)
        assertFalse("policy failures are never retried", failure.retryable)
    }

    @Test
    fun `a redirect to a private host is refused even when cross host redirects are enabled`() {
        val permissiveGuard = ResponseGuard(limits.copy(allowCrossHostRedirects = true))
        val result = permissiveGuard.inspect(
            request("https://zillow.com/homedetails/x/12345678_zpid"),
            response(
                finalUrl = "http://169.254.169.254/latest/meta-data/",
                redirectCount = 1
            )
        )
        assertTrue(result is ResponseGuardResult.Rejected)
        val failure = (result as ResponseGuardResult.Rejected).failure
        assertTrue(failure is SourceFailure.RedirectNotAllowed)
    }

    @Test
    fun `a redirect loop beyond the budget is refused`() {
        val failure = rejection(response(redirectCount = 7))
        assertTrue(failure is SourceFailure.TooManyRedirects)
        assertEquals("TOO_MANY_REDIRECTS", failure.code)
    }

    @Test
    fun `host families include subdomains in both directions`() {
        assertTrue(guard.sameHostFamily("https://www.zillow.com/a", "https://zillow.com/a"))
        assertTrue(guard.sameHostFamily("https://zillow.com/a", "https://m.zillow.com/a"))
        assertFalse(guard.sameHostFamily("https://zillow.com/a", "https://zillow.com.evil.test/a"))
        assertFalse(guard.sameHostFamily("https://zillow.com/a", "https://redfin.com/a"))
    }

    // ---- hostile / interstitial pages ----------------------------------------------------

    @Test
    fun `captcha consent and login walls are classified and never parsed`() {
        val captcha = rejection(response(body = Fixtures.load("captcha_cloudflare.html")))
        assertTrue(captcha is SourceFailure.Blocked)
        assertEquals(BlockReason.CAPTCHA, (captcha as SourceFailure.Blocked).reason)

        val consent = rejection(response(body = Fixtures.load("consent_wall.html")))
        assertTrue(consent is SourceFailure.Blocked)
        assertEquals(BlockReason.CONSENT_WALL, (consent as SourceFailure.Blocked).reason)

        val login = rejection(response(body = Fixtures.load("login_wall.html")))
        assertTrue(login is SourceFailure.AuthRequired)
        assertEquals("AUTH_REQUIRED", login.code)

        listOf(captcha, consent, login).forEach { failure ->
            assertFalse("interstitials are never retried", failure.retryable)
        }
    }

    @Test
    fun `an x robots tag that forbids indexing stops the import`() {
        val failure = SourceFailureClassifier.detectMetaNoindex(
            mapOf("X-Robots-Tag" to "noindex, nofollow")
        )
        assertTrue(failure is SourceFailure.Blocked)
        assertEquals(BlockReason.NOINDEX_DIRECTIVE, (failure as SourceFailure.Blocked).reason)

        assertEquals(
            null,
            SourceFailureClassifier.detectMetaNoindex(mapOf("X-Robots-Tag" to "index, follow"))
        )
        assertEquals(null, SourceFailureClassifier.detectMetaNoindex(emptyMap()))
    }

    // ---- limits ----------------------------------------------------------------------------

    @Test
    fun `limits reject impossible configurations`() {
        var failed = false
        try {
            FetchLimits(connectTimeoutMillis = 0)
        } catch (t: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)

        failed = false
        try {
            FetchLimits(readTimeoutMillis = 5_000, callTimeoutMillis = 1_000)
        } catch (t: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)

        failed = false
        try {
            FetchLimits(minResponseBytes = 100, maxResponseBytes = 10)
        } catch (t: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `requested timeouts are clamped to the configured budget`() {
        val mobile = FetchLimits.MOBILE
        assertEquals(1_000L, mobile.effectiveTimeoutMillis(0L))
        assertEquals(mobile.callTimeoutMillis, mobile.effectiveTimeoutMillis(10_000_000L))
    }

    @Test
    fun `resolver options propagate fetch limits to the transport and the guard`() {
        val transport = FakeTransport(bodies = listOf(Fixtures.load("zillow_listing.html")))
        val registry = com.example.urlintelligence.source.SourceRegistry(
            descriptors = com.example.urlintelligence.source.KnownSources.all(),
            adapters = listOf(com.example.urlintelligence.adapter.ZillowAdapter(transport))
        )
        val resolver = com.example.urlintelligence.resolver.PropertyUrlResolver(
            registry = registry,
            normalizer = com.example.urlintelligence.normalization.PropertyNormalizer(TestClock()),
            clock = TestClock(),
            jobStore = com.example.urlintelligence.job.InMemoryPropertyImportJobStore(),
            resultStore = com.example.urlintelligence.idempotency.InMemoryIdempotencyStore(TestClock()),
            allowedSourceIds = setOf("zillow"),
            idGenerator = { "job-limits" }
        )
        val tight = FetchLimits(maxResponseBytes = 100, minResponseBytes = 64)
        val result = kotlinx.coroutines.runBlocking {
            resolver.resolve(Fixtures.ZILLOW_URL, com.example.urlintelligence.resolver.ResolveOptions(limits = tight))
        }

        assertTrue(result is com.example.urlintelligence.resolver.PropertyImportResult.Failure)
        val failure = (result as com.example.urlintelligence.resolver.PropertyImportResult.Failure).failure
        assertTrue("expected a size rejection but was $failure", failure is SourceFailure.PayloadTooLarge)
        assertEquals("the transport must receive the same limits", tight, transport.requests.single().limits)
    }

    @Test
    fun `a third party adapter cannot bypass the response guard`() {
        // FakeAdapter implements only the interface and never calls ResponseGuard itself: the
        // resolver must still refuse to hand it an oversized document.
        val transport = FakeTransport(
            bodies = listOf(Fixtures.load("zillow_listing.html")),
            reportedByteSize = 5_000_000
        )
        val descriptor = com.example.urlintelligence.source.SourceDescriptor(
            id = "third-party",
            displayName = "Third party",
            hostSuffixes = listOf("third-party.test"),
            requiresOptIn = false
        )
        val registry = com.example.urlintelligence.source.SourceRegistry(
            descriptors = listOf(descriptor),
            adapters = listOf(FakeAdapter(descriptor))
        )
        val resolver = com.example.urlintelligence.resolver.PropertyUrlResolver(
            registry = registry,
            normalizer = com.example.urlintelligence.normalization.PropertyNormalizer(TestClock()),
            clock = TestClock(),
            jobStore = com.example.urlintelligence.job.InMemoryPropertyImportJobStore(),
            resultStore = com.example.urlintelligence.idempotency.InMemoryIdempotencyStore(TestClock()),
            idGenerator = { "job-third-party" }
        )

        val result = kotlinx.coroutines.runBlocking {
            resolver.resolve("https://third-party.test/listing/1")
        }
        assertTrue(result is com.example.urlintelligence.resolver.PropertyImportResult.Failure)
        assertTrue(
            (result as com.example.urlintelligence.resolver.PropertyImportResult.Failure).failure
                is SourceFailure.PayloadTooLarge
        )
    }

    @Test
    fun `the resolver reports whether compliance and idempotency are wired`() {
        val registry = com.example.urlintelligence.source.SourceRegistry(
            descriptors = com.example.urlintelligence.source.KnownSources.all()
        )
        val plain = com.example.urlintelligence.resolver.PropertyUrlResolver(registry = registry)
        assertFalse(plain.hasAccessPolicy)
        assertFalse(plain.hasImportLedger)

        val wired = com.example.urlintelligence.resolver.PropertyUrlResolver(
            registry = registry,
            accessPolicy = com.example.urlintelligence.compliance.AccessPolicy.PERMISSIVE_PUBLIC_WEB,
            ledger = com.example.urlintelligence.idempotency.InMemoryImportLedger(TestClock())
        )
        assertTrue(wired.hasAccessPolicy)
        assertTrue(wired.hasImportLedger)
    }

    @Test
    fun `the resolver rejects a hostile response before any adapter sees it`() {
        val transport = FakeTransport(
            bodies = listOf(Fixtures.load("captcha_cloudflare.html"))
        )
        val registry = com.example.urlintelligence.source.SourceRegistry(
            descriptors = com.example.urlintelligence.source.KnownSources.all(),
            adapters = listOf(
                com.example.urlintelligence.adapter.ZillowAdapter(transport),
                com.example.urlintelligence.adapter.GenericWebAdapter(transport)
            )
        )
        val resolver = com.example.urlintelligence.resolver.PropertyUrlResolver(
            registry = registry,
            normalizer = com.example.urlintelligence.normalization.PropertyNormalizer(TestClock()),
            clock = TestClock(),
            jobStore = com.example.urlintelligence.job.InMemoryPropertyImportJobStore(),
            resultStore = com.example.urlintelligence.idempotency.InMemoryIdempotencyStore(TestClock()),
            allowedSourceIds = setOf("zillow"),
            idGenerator = { "job-guard" }
        )

        val result = kotlinx.coroutines.runBlocking { resolver.resolve(Fixtures.ZILLOW_URL) }
        assertTrue(result is com.example.urlintelligence.resolver.PropertyImportResult.Failure)
        val failure = (result as com.example.urlintelligence.resolver.PropertyImportResult.Failure).failure
        assertTrue("expected a block, was $failure", failure is SourceFailure.Blocked)
        assertEquals("BLOCKED_CAPTCHA", failure.code)
    }
}
