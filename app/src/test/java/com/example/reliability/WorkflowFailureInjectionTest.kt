package com.example.reliability

import com.example.data.adapter.PropertySourceManager
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferEmailSendStatus
import com.example.data.local.entity.PropertyImportStatus
import com.example.data.local.entity.PropertySourceDefaults
import com.example.data.local.entity.PropertySourceSyncStatus
import com.example.domain.gmail.GmailFailureKind
import com.example.domain.gmail.GmailSendResult
import com.example.domain.intelligence.job.JobStatus
import com.example.domain.propertyurl.job.PropertyImportJobState
import com.example.domain.propertyurl.port.TransportFailureKind
import com.example.domain.propertyurl.pipeline.ImportOutcome
import com.example.domain.propertyurl.pipeline.PropertyImportQueue
import com.example.domain.propertyurl.pipeline.PropertyUrlIntelligenceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Failure-injection audit of the same property-to-offer chain.
 *
 * The injected faults are realistic (provider outage, timeouts, a bot wall, an ambiguous Gmail
 * response, a process killed between two attempts, two workers racing) and they are produced by the
 * scripted transport/sender only: they prove how *our* code behaves under those faults, not that a
 * live provider behaves in any particular way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowFailureInjectionTest {

    private lateinit var harness: WorkflowHarness

    @Before
    fun setUp() {
        harness = WorkflowHarness()
        harness.seedSources()
    }

    @After
    fun tearDown() {
        harness.dispose()
    }

    // ── invalid input and unsupported sources ────────────────────────────────────────────────────

    @Test
    fun `an invalid url is refused before anything is fetched or stored`() = runBlocking {
        val state = harness.intelligenceRepository.importPropertyUrl(
            "please import my house at 4127 Oak Hollow Dr, call 512-555-0100"
        )

        assertEquals(JobStatus.FAILED, state.status)
        assertNull(state.propertyId)
        assertNotNull("the caller must get a reason", state.errorMessage)
        assertEquals("no network call may be made for unusable input", 0, harness.fetcher.totalHits)
        assertEquals(0, harness.database.propertyDao().getPropertiesCount())
    }

    @Test
    fun `a message carrying two listings is refused instead of guessing one`() = runBlocking {
        val input = "${WorkflowHarness.ZILLOW_URL} https://www.redfin.com/TX/Austin/1810-Guadalupe-St-12/home/145879032"

        val inspection = harness.bridge.inspect(input)
        assertTrue("two candidate links must be reported as ambiguous", inspection.resolution is com.example.domain.propertyurl.url.UrlResolutionResult.Ambiguous)

        val state = harness.intelligenceRepository.importPropertyUrl(input)

        assertEquals(JobStatus.FAILED, state.status)
        assertEquals(0, harness.fetcher.totalHits)
        assertEquals(0, harness.database.propertyDao().getPropertiesCount())
    }

    @Test
    fun `a recognised source without an adapter is rejected with actionable state`() = runBlocking {
        val truliaUrl = "https://www.trulia.com/p/4127-oak-hollow-dr-austin-tx-78745/"

        val inspection = harness.bridge.inspect(truliaUrl)
        assertEquals("trulia", inspection.sourceId)
        assertFalse("a PLANNED source must not be reported as supported", inspection.isSupported)

        val outcome = harness.bridge.import(truliaUrl)

        assertTrue("expected a rejection, got ${outcome.describe()}", outcome is ImportOutcome.Rejected)
        assertEquals(PropertyImportJobState.REJECTED_UNSUPPORTED_SOURCE, outcome.job.state)
        assertEquals(
            "rejection happens before the transport, so no request may be made",
            0,
            harness.fetcher.totalHits
        )
        assertEquals(0, harness.database.propertyDao().getPropertiesCount())
    }

    // ── transport faults, retries and budget ──────────────────────────────────────────────────────

    @Test
    fun `a transient portal outage is retried with backoff and the import still lands`() = runBlocking {
        harness.stubZillowListing(failuresBeforeSuccess = 2)

        val state = harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL)

        assertEquals(JobStatus.COMPLETED, state.status)
        assertEquals(
            "the attempt budget must be used before giving up",
            WorkflowHarness.MAX_FETCH_ATTEMPTS,
            harness.fetcher.hitsFor(WorkflowHarness.ZILLOW_ROUTE)
        )
        assertTrue("backoff must be applied between attempts", harness.sleeper.totalMillis > 0)
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
        assertNotNull(state.propertyId)
    }

    @Test
    fun `an exhausted retry budget fails the import without touching stored data`() = runBlocking {
        harness.stubZillowListing()
        val propertyId = requireNotNull(
            harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL).propertyId
        )
        val before = requireNotNull(harness.database.propertyDao().getPropertyById(propertyId))

        // Second listing: the provider never answers in time.
        harness.fetcher.on(
            "some-broker.example",
            body = "",
            transportError = TransportFailureKind.TIMEOUT
        )
        val outcome = harness.bridge.importAndStore("https://some-broker.example/listings/4127-oak-hollow-dr")

        assertFalse("a timeout is not a success", outcome.isSuccess)
        assertEquals(PropertyImportJobState.FETCH_FAILED, outcome.job.state)
        assertEquals(
            "the failure must be classified as a timeout, not as a parse problem",
            "TIMEOUT",
            outcome.failure?.kind?.name ?: outcome.job.lastFailure?.kind?.name ?: "NONE"
        )
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
        val after = requireNotNull(harness.database.propertyDao().getPropertyById(propertyId))
        assertEquals(before.price, after.price, 0.001)
        assertEquals(before.canonicalKey, after.canonicalKey)
        assertEquals(1, harness.database.propertySourceDao().getProvenanceForProperty(propertyId).size)
    }

    @Test
    fun `a bot wall page is classified as a failure and stores nothing`() = runBlocking {
        harness.fetcher.on("blocked.example", body = com.example.domain.propertyurl.Fixtures.text("anti-bot-page.html"))

        val outcome = harness.bridge.importAndStore("https://blocked.example/property/4127-oak-hollow-dr")

        assertFalse("an interstitial may never be turned into a property", outcome.isSuccess)
        assertEquals(0, harness.database.propertyDao().getPropertiesCount())
        assertNotNull("the failure kind must say what happened", outcome.failure)
    }

    @Test
    fun `an interrupted import is resumed by the durable worker and still reaches the database`() = runBlocking {
        // A tiny call budget makes the layer schedule the retry instead of burning it inline, which
        // is exactly the state a killed process leaves behind.
        harness.maxTotalImportMillis = 1
        harness.stubZillowListing(failuresBeforeSuccess = 1)

        val interrupted = harness.bridge.import(WorkflowHarness.ZILLOW_URL)

        assertTrue("expected a deferred retry, got ${interrupted.describe()}", interrupted is ImportOutcome.Deferred)
        assertEquals(PropertyImportJobState.FETCH_RETRY_SCHEDULED, interrupted.job.state)
        assertEquals(0, harness.database.propertyDao().getPropertiesCount())

        // ── process death: every in-memory object is gone, only the files remain ──────────────────
        harness.restart()
        val persistedJob = requireNotNull(harness.jobStore.findById(interrupted.job.jobId)) {
            "the interrupted job must survive in the durable job store"
        }
        assertFalse("a job left for the next process must stay resumable, not terminal", persistedJob.isTerminal)
        assertEquals(PropertyImportJobState.FETCH_RETRY_SCHEDULED, persistedJob.state)

        harness.pipelineClock.advance(60_000)
        harness.clock.set(WorkflowHarness.NOW + 60_000)

        val persisted = mutableListOf<String>()
        val queue: PropertyImportQueue = PropertyUrlIntelligenceFactory.createQueue(
            intelligence = harness.pipeline,
            jobStore = harness.jobStore,
            clock = harness.pipelineClock,
            scope = CoroutineScope(Dispatchers.IO),
            onPropertyImported = { property ->
                persisted += property.canonicalId
                harness.properties.insertBundle(harness.bridge.toBundle(property))
            }
        )

        val report = queue.pollOnce(requestId = "after-restart")

        assertEquals("the resumed job must complete once", 1, report.summary.succeeded)
        assertEquals("the sink must be handed exactly the record the layer produced", 1, persisted.size)
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
        val stored = harness.properties.allProperties.first().single()
        assertEquals("4127 Oak Hollow Dr", stored.address)
        assertEquals(
            "the resumed record must be attributed to its source",
            1,
            harness.database.propertySourceDao().getProvenanceForProperty(stored.id).size
        )

        // A second poll must not import the same house again.
        harness.pipelineClock.advance(60_000)
        val again = queue.pollOnce(requestId = "after-restart-2")
        assertEquals(0, again.summary.succeeded)
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
    }

    // ── thin records must not become fabricated deals ─────────────────────────────────────────────

    @Test
    fun `a property without a price cannot produce an offer`() = runBlocking {
        val propertyId = harness.importer.importBundle(
            WorkflowHarness.bareBundle(WorkflowHarness.listingRow(id = "thin-1", price = 0.0))
                .copy(sourceId = PropertySourceDefaults.MANUAL_ID, externalId = "thin-1")
        ).propertyId

        val withoutPrice = runCatching {
            harness.offers.generateOffer(harness.context, propertyId, customPrice = null)
        }
        val unknownProperty = runCatching {
            harness.offers.generateOffer(harness.context, "never-imported", customPrice = 250_000.0)
        }

        assertTrue(
            "a zero-price listing must be refused, not turned into an offer with no price",
            withoutPrice.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            "an offer may only be drafted for a stored property",
            unknownProperty.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue("a refused offer must not be half written", harness.offers.allOffers.first().isEmpty())
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
    }

    @Test
    fun `a failed record inside a batch leaves the good records and counts the failure`() = runBlocking {
        val adapter = WorkflowHarness.ScriptedFeedAdapter(
            sourceName = "MLS Feed",
            sourceId = PropertySourceDefaults.MLS_ID,
            bundles = mutableListOf(
                WorkflowHarness.bareBundle(WorkflowHarness.listingRow(id = "batch-1"))
            )
        )
        val manager = PropertySourceManager(listOf(adapter))

        val summary = harness.importer.runImport(
            sourceId = PropertySourceDefaults.MLS_ID,
            triggerKind = "AUTOMATION",
            fetch = { manager.fetchSource(PropertySourceDefaults.MLS_ID, limit = 20) }
        )

        assertEquals(PropertyImportStatus.COMPLETED, summary.status)
        assertEquals(1, summary.inserted)
        assertEquals(0, summary.failed)
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
        assertEquals(1, adapter.calls.get())

        // A source whose fetch throws marks the job FAILED and must not touch stored rows.
        val outage = WorkflowHarness.OutagedFeedAdapter(
            sourceName = "Wholesale Feed",
            sourceId = PropertySourceDefaults.WHOLESALE_ID
        )
        val failedRun = harness.importer.runImport(
            sourceId = PropertySourceDefaults.WHOLESALE_ID,
            triggerKind = "AUTOMATION",
            fetch = { outage.fetchProperties(limit = 20) }
        )

        assertEquals(PropertyImportStatus.FAILED, failedRun.status)
        assertEquals(1, failedRun.failed)
        assertEquals(0, failedRun.inserted)
        assertEquals("the good records must survive a failed run", 1, harness.database.propertyDao().getPropertiesCount())
        assertEquals(
            "a failed run must be visible on the source row",
            PropertySourceSyncStatus.FAILED,
            harness.database.propertySourceDao().getSourceById(PropertySourceDefaults.WHOLESALE_ID)?.lastSyncStatus
        )
        assertEquals(
            PropertySourceSyncStatus.SUCCESS,
            harness.database.propertySourceDao().getSourceById(PropertySourceDefaults.MLS_ID)?.lastSyncStatus
        )
    }

    /**
     * Pins a *known gap* (GAP-2 in `docs/e2e-reliability-audit.md`): the source manager swallows an
     * adapter exception per source, so a total provider outage reaches `runImport` as "0 records".
     * The job and the source row therefore read as a clean, empty sync.
     *
     * This test asserts today's behaviour on purpose. When the outage becomes observable, this test
     * must be updated together with the audit document - not deleted quietly.
     */
    @Test
    fun `a provider outage inside the source manager is currently recorded as an empty successful run`() = runBlocking {
        val manager = PropertySourceManager(
            listOf(
                WorkflowHarness.OutagedFeedAdapter(
                    sourceName = "Wholesale Feed",
                    sourceId = PropertySourceDefaults.WHOLESALE_ID
                )
            )
        )

        val summary = harness.importer.runImport(
            sourceId = PropertySourceDefaults.WHOLESALE_ID,
            triggerKind = "SCHEDULED",
            fetch = { manager.fetchSource(PropertySourceDefaults.WHOLESALE_ID, limit = 20) }
        )

        assertEquals(PropertyImportStatus.COMPLETED, summary.status)
        assertEquals(0, summary.fetched)
        assertEquals(0, summary.failed)
        assertEquals(
            "the outage is invisible to the source row today: it reads as a successful empty sync",
            PropertySourceSyncStatus.SUCCESS,
            harness.database.propertySourceDao().getSourceById(PropertySourceDefaults.WHOLESALE_ID)?.lastSyncStatus
        )
    }

    // ── delivery faults ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a missing authorization blocks delivery and preserves the offer for the authorized retry`() = runBlocking {
        val offerId = seedDeliverableOffer("offer-noauth", connected = false)

        val blocked = harness.offers.sendOfferDetailed(harness.context, offerId)

        assertFalse(blocked.success)
        assertEquals(OfferEmailSendStatus.BLOCKED, blocked.status)
        assertTrue("a blocked send is explicitly retryable", blocked.safeToRetry)
        assertTrue(blocked.error.orEmpty().contains("not connected"))
        assertEquals("the provider may not be called without authorization", 0, harness.gmail.callCount.get())

        // Authorization arrives, but Gmail rejects the credentials.
        connectGmail()
        harness.gmail.script(
            GmailSendResult(
                success = false,
                error = "401 Invalid Credentials (ya29.test-token-value)",
                failureKind = GmailFailureKind.AUTHENTICATION
            )
        )
        val authFailed = harness.offers.sendOfferDetailed(harness.context, offerId)

        assertFalse(authFailed.success)
        assertEquals(1, harness.gmail.callCount.get())
        assertEquals("an auth failure must not be retried automatically", OfferEmailSendStatus.BLOCKED, authFailed.status)
        val afterAuthFailure = requireNotNull(harness.database.offerDao().getEmailSendForOffer(offerId))
        assertEquals(OfferEmailSendStatus.BLOCKED, afterAuthFailure.status)
        assertNull("no retry may be scheduled for a blocked credential", afterAuthFailure.nextAttemptAt)
        assertFalse(
            "the token must never be copied into the persisted error",
            afterAuthFailure.lastError.orEmpty().contains("ya29.test-token-value")
        )
        assertEquals("FAILED", harness.database.offerDao().getOfferById(offerId)?.status)

        // The credential is fixed: the same offer goes out once.
        harness.gmail.script(GmailSendResult(success = true, messageId = "gmail-msg-auth"))
        val sent = harness.offers.sendOfferDetailed(harness.context, offerId)

        assertTrue(sent.error ?: "send failed", sent.success)
        assertEquals(2, harness.gmail.callCount.get())
        assertEquals("SENT", harness.database.offerDao().getOfferById(offerId)?.status)
        val audit = harness.offers.getAuditTrailForOffer(offerId).first()
        assertTrue(audit.map { it.eventType }.contains("SEND_BLOCKED"))
        assertTrue(audit.map { it.eventType }.contains("EMAIL_SENT"))
    }

    @Test
    fun `an unprovable delivery outcome stays unknown across a restart and is never re-sent`() = runBlocking {
        val offerId = seedDeliverableOffer("offer-unknown", connected = true)
        harness.gmail.script(
            GmailSendResult(
                success = false,
                error = "connection reset after the request was written",
                failureKind = GmailFailureKind.DELIVERY_UNKNOWN
            )
        )

        val first = harness.offers.sendOfferDetailed(harness.context, offerId)

        assertFalse(first.success)
        assertEquals(OfferEmailSendStatus.UNKNOWN, first.status)
        assertFalse("an unknown outcome must never be retried automatically", first.safeToRetry)

        harness.restart()

        assertEquals(
            "the unknown outcome must be stored, not remembered in memory",
            OfferEmailSendStatus.UNKNOWN,
            harness.database.offerDao().getEmailSendForOffer(offerId)?.status
        )
        // Recovery on app start keeps ambiguous sends out of the dispatch loop.
        harness.offers.recoverInterruptedSends()
        val replay = harness.offers.sendOfferDetailed(harness.context, offerId)

        assertFalse(replay.success)
        assertEquals(OfferEmailSendStatus.UNKNOWN, replay.status)
        assertEquals("an unknown outcome may never reach Gmail a second time", 1, harness.gmail.callCount.get())
        assertEquals(1, harness.database.offerDao().getEmailSendForOffer(offerId)?.attemptCount)
    }

    @Test
    fun `a known rejection is scheduled for one bounded retry and honoured after its due time`() = runBlocking {
        val offerId = seedDeliverableOffer("offer-rate-limited", connected = true)
        val now = harness.clock
        harness.gmail.script(
            GmailSendResult(
                success = false,
                error = "429 Too Many Requests",
                failureKind = GmailFailureKind.RETRYABLE_REJECTED,
                retryAfterMillis = 30_000L
            ),
            GmailSendResult(success = true, messageId = "gmail-msg-rate")
        )

        val first = harness.offers.sendOfferDetailed(harness.context, offerId)

        assertFalse(first.success)
        assertEquals(OfferEmailSendStatus.RETRYABLE, first.status)
        val dueAt = requireNotNull(first.nextAttemptAt) { "a retryable failure must carry a due time" }

        // Too early: nothing must be sent.
        val early = harness.offers.sendOfferDetailed(harness.context, offerId)
        assertFalse(early.success)
        assertEquals(1, harness.gmail.callCount.get())

        now.set(dueAt + 1)
        val late = harness.offers.sendOfferDetailed(harness.context, offerId)
        assertTrue(late.error ?: "send failed", late.success)
        assertEquals(2, harness.gmail.callCount.get())
        assertEquals(2, harness.database.offerDao().getEmailSendForOffer(offerId)?.attemptCount)
    }

    // ── racing workers ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `concurrent imports of the same listing converge on one canonical row`() = runBlocking {
        harness.stubZillowListing()

        val outcomes = coroutineScope {
            (1..4).map {
                async(Dispatchers.IO) { harness.bridge.importAndStore(WorkflowHarness.ZILLOW_URL) }
            }.awaitAll()
        }

        assertEquals(
            "exactly one canonical row may exist after four racing workers",
            1,
            harness.database.propertyDao().getPropertiesCount()
        )
        val stored = harness.properties.allProperties.first().single()
        assertTrue(
            "every concurrent worker must resolve the same canonical row",
            outcomes.mapNotNull { it.propertyOrNull()?.canonicalId }.all { it == stored.id }
        )
        assertEquals(
            "satellites must not be multiplied by the race",
            1,
            harness.database.propertySourceDao().getProvenanceForProperty(stored.id).size
        )
        assertEquals(1, harness.database.propertyDao().getImagesListForProperty(stored.id).size)
        assertTrue(
            "no worker may report a fabricated record",
            outcomes.none { it is ImportOutcome.Failed }
        )
    }

    @Test
    fun `concurrent imports from two sources keep one canonical row with both provenance rows`() = runBlocking {
        val bundle = WorkflowHarness.bareBundle(WorkflowHarness.listingRow(id = "race-1"))

        val results = coroutineScope {
            (1..3).map { index ->
                async(Dispatchers.IO) {
                    harness.importer.importBundle(
                        bundle.copy(
                            sourceId = if (index % 2 == 0) PropertySourceDefaults.WHOLESALE_ID else PropertySourceDefaults.MLS_ID,
                            externalId = "ext-$index"
                        )
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
        val propertyId = harness.properties.allProperties.first().single().id
        assertTrue(results.all { it.propertyId == propertyId })
        assertEquals(3, harness.database.propertySourceDao().getProvenanceForProperty(propertyId).size)
        assertEquals(1, results.count { it.outcome.name == "INSERTED" })
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private suspend fun seedDeliverableOffer(offerId: String, connected: Boolean): String {
        val propertyId = "$offerId-property"
        harness.properties.createProperty(WorkflowHarness.listingRow(id = propertyId))
        val pdf = harness.writeOfferPdf(offerId)
        harness.database.offerDao().insertOffer(harness.draftOffer(propertyId, offerId, pdf))
        if (connected) connectGmail()
        return offerId
    }

    private suspend fun connectGmail() {
        harness.configRepository.saveGmailConfig(
            GmailConfigurationEntity(
                accountEmail = "buyer@example.com",
                senderName = "Acquisitions Team",
                accessToken = "test-access-token",
                refreshToken = "test-refresh-token",
                expiresAt = harness.clock.get() + 3_600_000L,
                isConnected = true
            )
        )
    }
}
