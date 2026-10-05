package com.example.domain.propertyurl.pipeline

import com.example.domain.propertyurl.FakeHttpFetcher
import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.FixedClock
import com.example.domain.propertyurl.RecordingSleeper
import com.example.domain.propertyurl.RecordingTelemetry
import com.example.domain.propertyurl.SequentialIdGenerator
import com.example.domain.propertyurl.job.IdempotencyPolicy
import com.example.domain.propertyurl.job.InMemoryPropertyImportJobStore
import com.example.domain.propertyurl.job.PropertyImportJob
import com.example.domain.propertyurl.job.PropertyImportJobState
import com.example.domain.propertyurl.job.RetryPolicy
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.SourceFailureCategory
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.port.CredentialProvider
import com.example.domain.propertyurl.port.DefaultFetchPolicies
import com.example.domain.propertyurl.port.FetchOptions
import com.example.domain.propertyurl.testParserContext
import com.example.domain.propertyurl.url.PropertyUrlResolver
import com.example.domain.propertyurl.url.UrlResolutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests of the orchestrator: validation → detection → policy → fetch/retry → parse →
 * normalize → job state → persistence. Everything is driven by fixtures and canned transport, so the
 * whole acceptance surface is covered without touching the network.
 */
class PropertyUrlIntelligenceTest {

    private val zillowUrl = "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"
    private val genericUrl = "https://some-broker.example/listings/4127-oak-hollow-dr"
    private val redfinUrl = "https://www.redfin.com/TX/Austin/1810-Guadalupe-St-12/home/145879032"

    private class Harness(
        val fetcher: FakeHttpFetcher = FakeHttpFetcher(),
        val clock: FixedClock = FixedClock(),
        val sleeper: RecordingSleeper = RecordingSleeper(),
        val telemetry: RecordingTelemetry = RecordingTelemetry(),
        val store: InMemoryPropertyImportJobStore = InMemoryPropertyImportJobStore(),
        options: PropertyImportOptions = PropertyImportOptions(maxFetchAttempts = 3)
    ) {
        val engine: PropertyUrlIntelligence = PropertyUrlIntelligenceFactory.create(
            jobStore = store,
            httpFetcher = fetcher,
            clock = clock,
            idGenerator = SequentialIdGenerator(),
            telemetry = telemetry,
            credentialProvider = CredentialProvider.NONE,
            sleeper = sleeper,
            options = options,
            retryPolicy = RetryPolicy(baseDelayMillis = 1_000, jitterRatio = 0.0),
            idempotencyPolicy = IdempotencyPolicy(),
            rateLimiter = null,
            healthTracker = null
        )
    }

    private fun Harness.withZillowFixture(): Harness {
        fetcher.on("zillow.com/homedetails", body = Fixtures.text("zillow-homedetails.html"))
        return this
    }

    // --- Happy path ---------------------------------------------------------------------------------

    @Test
    fun `a portal listing becomes a canonical property with provenance`() = runBlocking {
        val harness = Harness().withZillowFixture()

        val outcome = harness.engine.import(zillowUrl)

        assertTrue("expected success but was ${outcome.describe()}", outcome is ImportOutcome.Success)
        val property = outcome.property!!
        assertEquals("4127 Oak Hollow Dr", property.addressLine1)
        assertEquals("Austin", property.city)
        assertEquals("TX", property.state)
        assertEquals("78745", property.postalCode)
        assertEquals(565_000.0, property.price!!, 0.01)
        assertEquals(3, property.bedrooms)
        assertEquals(1842, property.livingAreaSqFt)
        assertTrue(property.canonicalId.startsWith("cp-"))
        assertTrue("usable records must be complete on identity + essentials", property.completeness.isUsable)

        // Provenance on every field, all pointing at the fetched document.
        property.fields.forEach { (field, sourced) ->
            assertEquals("field $field", "zillow", sourced.provenance.sourceId)
            assertTrue(sourced.provenance.confidence in 0.0..1.0)
        }
        assertTrue("the zillow listing id must be recorded", outcome.job.usedParsers.isNotEmpty())
        assertEquals(PropertyImportJobState.SUCCEEDED, outcome.job.state)
        assertNotNull("the job must be persisted for the UI", harness.store.findById(outcome.job.jobId))
        assertTrue(harness.telemetry.has("import.started"))
        assertTrue(harness.telemetry.has("import.succeeded"))
    }

    @Test
    fun `the imported job exposes the audit trail`() = runBlocking {
        val harness = Harness().withZillowFixture()

        val job = harness.engine.import(zillowUrl).job

        val chain = listOf(job.transitions.first().from.name) + job.transitions.map { it.to.name }
        assertEquals(
            listOf("RECEIVED", "QUEUED", "FETCHING", "PARSING", "NORMALIZING", "SUCCEEDED"),
            chain
        )
        assertEquals(1, job.attempts)
        assertNotNull(job.normalizedUrl)
        assertEquals("zillow", job.sourceId)
        assertEquals("20451237", job.externalListingId)
    }

    @Test
    fun `a page without price yields an explicit partial success`() = runBlocking {
        val harness = Harness()
        harness.fetcher.on(
            "some-broker.example",
            body = """
                <html><head><title>4127 Oak Hollow Dr, Austin, TX 78745</title>
                <meta name="description" content="Call for pricing. 4127 Oak Hollow Dr, Austin, TX 78745. Well kept home on a quiet street.">
                <meta property="og:image" content="https://cdn.example.com/front.jpg"></head>
                <body><h1>4127 Oak Hollow Dr, Austin, TX 78745</h1>
                <p>Contact the listing agent for current pricing and to schedule a private showing of this
                   updated home with a large back yard and a two car garage, close to schools and shops.</p></body></html>
            """.trimIndent()
        )

        val outcome = harness.engine.import(genericUrl)

        assertTrue("expected a partial import but was ${outcome.describe()}", outcome is ImportOutcome.Partial)
        val partial = outcome as ImportOutcome.Partial
        assertTrue("the reason must be machine readable", partial.missingFields.contains(PropertyField.PRICE_AMOUNT))
        assertEquals(PropertyImportJobState.PARTIAL_SUCCESS, outcome.job.state)
        assertNotNull("a partial record is still useful to the user", partial.property!!.addressLine1)
        assertTrue(harness.telemetry.has("import.partial"))
    }

    // --- Rejections (no network) ---------------------------------------------------------------------

    @Test
    fun `garbage input is rejected without touching the network`() = runBlocking {
        val harness = Harness()

        val outcome = harness.engine.import("call me about the Oak Hollow house")

        assertTrue(outcome is ImportOutcome.Rejected)
        assertEquals(PropertyImportJobState.REJECTED_INVALID_URL, outcome.job.state)
        assertEquals(SourceFailureKind.INVALID_URL, outcome.failure!!.kind)
        assertEquals(SourceFailureCategory.INPUT, outcome.failure!!.category)
        assertEquals("nothing may be fetched", 0, harness.fetcher.totalHits)
    }

    @Test
    fun `a message with two listings is rejected as ambiguous`() = runBlocking {
        val harness = Harness()

        val outcome = harness.engine.import("$zillowUrl and also $redfinUrl")

        assertTrue(outcome is ImportOutcome.Rejected)
        assertEquals(SourceFailureKind.MULTIPLE_URLS_FOUND, outcome.failure!!.kind)
        assertEquals(0, harness.fetcher.totalHits)
    }

    @Test
    fun `a source without an adapter is rejected with actionable copy`() = runBlocking {
        val harness = Harness()

        val outcome = harness.engine.import("https://www.apartments.com/austin-tx/some-building/1a2b3c4/")

        assertTrue(outcome is ImportOutcome.Rejected)
        assertEquals(PropertyImportJobState.REJECTED_UNSUPPORTED_SOURCE, outcome.job.state)
        assertEquals(SourceFailureKind.SOURCE_NOT_SUPPORTED, outcome.failure!!.kind)
        assertTrue(outcome.failure!!.userMessage.isNotBlank())
    }

    @Test
    fun `private network urls never reach the fetcher`() = runBlocking {
        val harness = Harness()

        val outcome = harness.engine.import("http://127.0.0.1:8080/admin")

        assertTrue(outcome is ImportOutcome.Rejected)
        assertEquals(0, harness.fetcher.totalHits)
    }

    // --- Failure classification and retries ----------------------------------------------------------

    @Test
    fun `transient failures are retried and then succeed`() = runBlocking {
        val harness = Harness()
        harness.fetcher.on(
            "zillow.com/homedetails",
            body = Fixtures.text("zillow-homedetails.html"),
            failuresBeforeSuccess = 2
        )

        val outcome = harness.engine.import(zillowUrl)

        assertTrue(outcome is ImportOutcome.Success)
        assertEquals(3, outcome.job.attempts)
        assertEquals(
            "each retry must be scheduled and audited",
            2,
            outcome.job.transitions.count { it.to == PropertyImportJobState.FETCH_RETRY_SCHEDULED }
        )
        assertEquals("backoff must be observable", 2, harness.sleeper.sleeps.size)
        assertTrue(harness.telemetry.has("import.attempt.failed"))
    }

    @Test
    fun `exhausted retries end in a classified failure`() = runBlocking {
        val harness = Harness(options = PropertyImportOptions(maxFetchAttempts = 2))
        harness.fetcher.on("zillow.com/homedetails", status = 503, body = "<html><body>down</body></html>")

        val outcome = harness.engine.import(zillowUrl)

        assertTrue("expected failure but was ${outcome.describe()}", outcome is ImportOutcome.Failed)
        assertEquals(PropertyImportJobState.FETCH_FAILED, outcome.job.state)
        assertEquals(SourceFailureKind.HTTP_SERVER_ERROR, outcome.failure!!.kind)
        assertEquals(2, outcome.job.attempts)
        assertTrue("retryable failures stay retryable for the caller", outcome.failure!!.isRetryable)
        assertNotNull("the job records when to try again", outcome.job.lastFailure)
    }

    @Test
    fun `a removed listing fails permanently without retrying`() = runBlocking {
        val harness = Harness()
        harness.fetcher.on("zillow.com/homedetails", status = 404, body = "<html><body>gone</body></html>")

        val outcome = harness.engine.import(zillowUrl)

        assertTrue(outcome is ImportOutcome.Failed)
        assertEquals(SourceFailureKind.HTTP_NOT_FOUND, outcome.failure!!.kind)
        assertEquals("a 404 must be attempted exactly once", 1, harness.fetcher.totalHits)
        assertEquals("and never scheduled for retry", 0, harness.sleeper.sleeps.size)
        assertFalse(outcome.failure!!.isRetryable)
    }

    @Test
    fun `bot walls are classified as an anti-bot failure`() = runBlocking {
        val harness = Harness()
        harness.fetcher.on("zillow.com/homedetails", status = 403, body = Fixtures.text("anti-bot-page.html"))

        val outcome = harness.engine.import(zillowUrl)

        assertTrue(outcome is ImportOutcome.Failed)
        assertEquals(SourceFailureCategory.ANTI_BOT, outcome.failure!!.kind.category)
        assertTrue("bot walls must be reported at source level", outcome.failure!!.isSourceLevel)
    }

    @Test
    fun `a page without usable data fails as a parse failure`() = runBlocking {
        val harness = Harness()
        harness.fetcher.on(
            "some-broker.example",
            body = "<html><head><title>Coming soon</title></head><body><p>Listing details are on the way.</p></body></html>"
        )

        val outcome = harness.engine.import(genericUrl)

        assertTrue(outcome is ImportOutcome.Failed)
        assertEquals(PropertyImportJobState.PARSE_FAILED, outcome.job.state)
        assertTrue(
            outcome.failure!!.kind == SourceFailureKind.PARSE_FAILED ||
                outcome.failure!!.kind == SourceFailureKind.MISSING_REQUIRED_FIELDS
        )
    }

    // --- Idempotency --------------------------------------------------------------------------------

    @Test
    fun `importing the same listing twice reuses the first result`() = runBlocking {
        val harness = Harness().withZillowFixture()

        val first = harness.engine.import(zillowUrl)
        val second = harness.engine.import("$zillowUrl?utm_source=email")

        assertTrue(first is ImportOutcome.Success)
        assertTrue("expected duplicate but was ${second.describe()}", second is ImportOutcome.Duplicate)
        assertEquals(first.job.jobId, (second as ImportOutcome.Duplicate).reusedJobId)
        assertEquals("the second import must not fetch again", 1, harness.fetcher.totalHits)
        assertEquals(first.property!!.canonicalId, second.property!!.canonicalId)
        assertEquals(PropertyImportJobState.DUPLICATE_SUPPRESSED, second.job.state)
        assertNotNull("the duplicate is recorded for audit", harness.store.findById(second.job.jobId))
    }

    @Test
    fun `force refresh fetches again and replaces the record`() = runBlocking {
        val harness = Harness().withZillowFixture()
        harness.engine.import(zillowUrl)

        val refreshed = harness.engine.import(zillowUrl, ImportRequest.FORCE_REFRESH)

        assertTrue(refreshed is ImportOutcome.Success)
        assertEquals(2, harness.fetcher.totalHits)
        assertEquals("a forced refresh is a real import, not a duplicate", 2, harness.store.count())
    }

    // --- Compliance ---------------------------------------------------------------------------------

    @Test
    fun `a path disallowed by robots is blocked before fetching`() = runBlocking {
        val harness = Harness()
        harness.fetcher
            .on("robots.txt", body = "User-agent: *\nDisallow: /homedetails/\n")
            .on("zillow.com/homedetails", body = Fixtures.text("zillow-homedetails.html"))

        val engine = PropertyUrlIntelligenceFactory.create(
            jobStore = harness.store,
            httpFetcher = harness.fetcher,
            clock = harness.clock,
            idGenerator = SequentialIdGenerator(),
            telemetry = harness.telemetry,
            credentialProvider = CredentialProvider.NONE,
            options = PropertyImportOptions(),
            fetchPolicy = DefaultFetchPolicies.robotsAware(
                harness.fetcher,
                harness.clock,
                FetchOptions.DEFAULT_USER_AGENT
            ),
            rateLimiter = null,
            healthTracker = null
        )

        val outcome = engine.import(zillowUrl)

        assertTrue(outcome is ImportOutcome.Rejected)
        assertEquals(PropertyImportJobState.BLOCKED_BY_POLICY, outcome.job.state)
        assertEquals(SourceFailureKind.POLICY_DISALLOWED, outcome.failure!!.kind)
        assertEquals("only robots.txt may be fetched", 1, harness.fetcher.totalHits)
        assertEquals(1, harness.fetcher.hitsFor("robots.txt"))
    }

    @Test
    fun `rate limits defer the import instead of blocking the caller`() = runBlocking {
        val harness = Harness(options = PropertyImportOptions(maxInlineRateLimitWaitMillis = 0))
        harness.fetcher.on("zillow.com/homedetails", body = Fixtures.text("zillow-homedetails.html"))

        val limiter = SourceRateLimiter(harness.clock, defaultRequestsPerMinute = 1, burstAllowance = 0)
        val engine = PropertyUrlIntelligenceFactory.create(
            jobStore = harness.store,
            httpFetcher = harness.fetcher,
            clock = harness.clock,
            idGenerator = SequentialIdGenerator(),
            telemetry = harness.telemetry,
            credentialProvider = CredentialProvider.NONE,
            options = PropertyImportOptions(maxInlineRateLimitWaitMillis = 0),
            rateLimiter = limiter,
            healthTracker = null
        )

        assertTrue(engine.import(zillowUrl) is ImportOutcome.Success)
        // Same source, different listing: its bucket is exhausted, so the import is deferred by design.
        val deferred = engine.import("https://www.zillow.com/homedetails/2/20451238_zpid/")

        assertTrue("expected deferral but was ${deferred.describe()}", deferred is ImportOutcome.Deferred)
        assertEquals(PropertyImportJobState.FETCH_RETRY_SCHEDULED, deferred.job.state)
        val deferredOutcome = deferred as ImportOutcome.Deferred
        assertTrue(
            "the retry must be in the future",
            deferredOutcome.nextAttemptAtEpochMillis > harness.clock.nowEpochMillis()
        )
        assertEquals("the second listing was never fetched", 1, harness.fetcher.totalHits)
    }

    @Test
    fun `an unhealthy source is circuit broken instead of hammered`() = runBlocking {
        val harness = Harness(options = PropertyImportOptions(maxFetchAttempts = 1))
        harness.fetcher
            .on("zillow.com/homedetails", status = 503, body = "<html><body>down</body></html>")
            .on("redfin.com", body = Fixtures.text("redfin-listing.html"))

        val engine = PropertyUrlIntelligenceFactory.create(
            jobStore = harness.store,
            httpFetcher = harness.fetcher,
            clock = harness.clock,
            idGenerator = SequentialIdGenerator(),
            telemetry = harness.telemetry,
            credentialProvider = CredentialProvider.NONE,
            options = PropertyImportOptions(maxFetchAttempts = 1),
            rateLimiter = null,
            healthTracker = SourceHealthTracker(harness.clock, failureThreshold = 2, openDurationMillis = 60_000)
        )

        engine.import(zillowUrl)
        engine.import("https://www.zillow.com/homedetails/2/20451238_zpid/")
        val hitsBefore = harness.fetcher.hitsFor("zillow.com/homedetails")
        val blocked = engine.import("https://www.zillow.com/homedetails/3/20451239_zpid/")

        assertTrue(blocked is ImportOutcome.Failed)
        assertEquals(SourceFailureKind.SOURCE_DISABLED, blocked.failure!!.kind)
        assertEquals("the source must not be contacted while the breaker is open", hitsBefore, harness.fetcher.hitsFor("zillow.com/homedetails"))

        val healthy = engine.import(redfinUrl)
        assertTrue("other sources are unaffected", healthy is ImportOutcome.Success)
    }

    // --- Batch, inspection, resume ------------------------------------------------------------------

    @Test
    fun `a batch reports per-item outcomes and never aborts`() = runBlocking {
        val harness = Harness().withZillowFixture()

        val report = harness.engine.importMany(
            listOf(
                zillowUrl,
                "not a url at all",
                "https://www.apartments.com/austin-tx/1/1a2b3c4/",
                "$redfinUrl and $zillowUrl"
            )
        )

        assertEquals(4, report.summary.total)
        assertEquals(1, report.summary.succeeded)
        assertEquals(3, report.summary.rejected)
        assertTrue(report.summary.hasFailures)
        assertTrue(report.summary.failuresByKind.containsKey(SourceFailureKind.INVALID_URL))
        assertTrue(report.summary.failuresByKind.containsKey(SourceFailureKind.SOURCE_NOT_SUPPORTED))
        assertTrue(report.summary.failuresByKind.containsKey(SourceFailureKind.MULTIPLE_URLS_FOUND))
        assertEquals(1, report.properties().size)
        assertTrue(report.durationMillis >= 0)
    }

    @Test
    fun `inspection answers support questions without any network call`() {
        val harness = Harness()

        val zillow = harness.engine.inspect(zillowUrl)
        assertTrue(zillow.isResolved)
        assertEquals("zillow", zillow.sourceId)
        assertEquals("Zillow", zillow.sourceDisplayName)
        assertTrue(zillow.isSupported)
        assertFalse(zillow.requiresCredentials)
        assertEquals("20451237", zillow.externalListingId)
        assertTrue(zillow.userFacingSummary().startsWith("Ready to import"))

        val planned = harness.engine.inspect("https://www.apartments.com/austin-tx/1/1a2b3c4/")
        assertFalse("planned sources report as unsupported", planned.isSupported)
        assertTrue(planned.userFacingSummary().contains("not supported yet"))

        val broken = harness.engine.inspect("hello there")
        assertFalse(broken.isResolved)
        assertNotNull(broken.rejectionSummary)

        assertEquals("inspection is offline", 0, harness.fetcher.totalHits)
    }

    @Test
    fun `only one of several pasted links is imported at a time`() = runBlocking {
        val harness = Harness()
        harness.fetcher
            .on("zillow.com/homedetails", body = Fixtures.text("zillow-homedetails.html"))
            .on("redfin.com", body = Fixtures.text("redfin-listing.html"))

        val report = harness.engine.importMany(listOf(zillowUrl, redfinUrl))

        assertEquals(2, report.summary.succeeded)
        assertEquals(2, report.summary.sourcesTouched.size)
        assertEquals(setOf("zillow", "redfin"), report.summary.sourcesTouched)
    }

    @Test
    fun `jobs interrupted by process death are resumed`() = runBlocking {
        val harness = Harness(options = PropertyImportOptions(interruptedJobThresholdMillis = 0))
        harness.fetcher.on("zillow.com/homedetails", body = Fixtures.text("zillow-homedetails.html"))

        val interrupted = PropertyImportJob(
            jobId = "job-interrupted",
            rawInput = zillowUrl,
            normalizedUrl = zillowUrl,
            sourceId = "zillow",
            adapterId = "zillow-html",
            idempotencyKey = "zillow:20451237",
            state = PropertyImportJobState.FETCHING,
            attempts = 1,
            createdAtEpochMillis = harness.clock.nowEpochMillis() - 60_000,
            updatedAtEpochMillis = harness.clock.nowEpochMillis() - 60_000
        )
        harness.store.save(interrupted)

        val report = harness.engine.resumeInterruptedJobs()

        assertEquals(1, report.outcomes.size)
        assertTrue("the interrupted job must finish", report.outcomes.first() is ImportOutcome.Success)
        assertEquals("job-interrupted", report.outcomes.first().job.jobId)
        assertEquals(PropertyImportJobState.SUCCEEDED, harness.store.findById("job-interrupted")!!.state)
    }

    @Test
    fun `deferred retries are picked up when their backoff has elapsed`() = runBlocking {
        val harness = Harness()
        harness.fetcher.on("zillow.com/homedetails", body = Fixtures.text("zillow-homedetails.html"))

        // A job whose backoff elapsed while the app was closed (the mobile-typical case).
        val due = PropertyImportJob(
            jobId = "job-due",
            rawInput = zillowUrl,
            normalizedUrl = zillowUrl,
            sourceId = "zillow",
            adapterId = "zillow-html",
            idempotencyKey = "zillow:20451237",
            state = PropertyImportJobState.FETCH_RETRY_SCHEDULED,
            attempts = 1,
            maxAttempts = 3,
            createdAtEpochMillis = harness.clock.nowEpochMillis() - 120_000,
            updatedAtEpochMillis = harness.clock.nowEpochMillis() - 120_000,
            nextAttemptAtEpochMillis = harness.clock.nowEpochMillis() - 60_000
        )
        harness.store.save(due)
        // A job that is not due yet must stay untouched.
        val future = due.copy(
            jobId = "job-future",
            state = PropertyImportJobState.FETCH_RETRY_SCHEDULED,
            nextAttemptAtEpochMillis = harness.clock.nowEpochMillis() + 600_000
        )
        harness.store.save(future)

        val report = harness.engine.retryDueJobs()

        assertTrue(
            "the due job must be retried: ${report.outcomes.map { it.describe() }}",
            report.outcomes.any { it.job.jobId == "job-due" && it is ImportOutcome.Success }
        )
        assertFalse(
            "a job inside its backoff window must not be retried",
            report.outcomes.any { it.job.jobId == "job-future" }
        )
        assertEquals(PropertyImportJobState.SUCCEEDED, harness.store.findById("job-due")!!.state)
    }

    @Test
    fun `cancelling a job stops it and is auditable`() = runBlocking {
        val harness = Harness().withZillowFixture()
        val job = PropertyImportJob(
            jobId = "job-cancelled",
            rawInput = zillowUrl,
            idempotencyKey = "zillow:20451237",
            state = PropertyImportJobState.QUEUED,
            createdAtEpochMillis = harness.clock.nowEpochMillis(),
            updatedAtEpochMillis = harness.clock.nowEpochMillis()
        )
        harness.store.save(job)

        val cancelled = harness.engine.cancel("job-cancelled")

        assertNotNull(cancelled)
        assertEquals(PropertyImportJobState.CANCELLED, cancelled!!.job.state)
        assertNull("cancelling an unknown job is a no-op", harness.engine.cancel("missing"))
    }

    @Test
    fun `dry run produces a preview without fetching or persisting`() = runBlocking {
        val harness = Harness().withZillowFixture()

        val outcome = harness.engine.import(zillowUrl, ImportRequest.PREVIEW)

        assertTrue(outcome is ImportOutcome.Deferred)
        assertEquals(0, harness.fetcher.totalHits)
        assertEquals(0, harness.store.count())
        assertEquals(PropertyImportJobState.RECEIVED, outcome.job.state)
    }

    @Test
    fun `the queue bounds its backlog and drains due retries`() = runBlocking {
        val harness = Harness()
        harness.fetcher.on("zillow.com/homedetails", body = Fixtures.text("zillow-homedetails.html"))

        val due = PropertyImportJob(
            jobId = "job-queue",
            rawInput = zillowUrl,
            normalizedUrl = zillowUrl,
            sourceId = "zillow",
            adapterId = "zillow-html",
            idempotencyKey = "zillow:20451237",
            state = PropertyImportJobState.FETCH_RETRY_SCHEDULED,
            attempts = 1,
            createdAtEpochMillis = harness.clock.nowEpochMillis() - 60_000,
            updatedAtEpochMillis = harness.clock.nowEpochMillis() - 60_000,
            nextAttemptAtEpochMillis = harness.clock.nowEpochMillis() - 1_000
        )
        harness.store.save(due)

        val queue = PropertyImportQueue(
            intelligence = harness.engine,
            jobStore = harness.store,
            clock = harness.clock,
            telemetry = harness.telemetry,
            pollIntervalMillis = 60_000,
            maxParallel = 1,
            capacity = 1
        )

        assertTrue(queue.enqueue(zillowUrl))
        assertFalse("the backlog must be bounded", queue.enqueue("https://www.zillow.com/homedetails/2/20451238_zpid/"))
        assertEquals(1, queue.backlogCount)
        assertTrue("in-flight work is visible to the UI", queue.pendingJobs().isNotEmpty())

        val report = queue.pollOnce()
        assertTrue(
            "the poller must finish the due job: ${report.outcomes.map { it.describe() }}",
            report.outcomes.any { it.job.jobId == "job-queue" && it is ImportOutcome.Success }
        )
        queue.stop()
        assertEquals(0, queue.runningCount)
    }
}
