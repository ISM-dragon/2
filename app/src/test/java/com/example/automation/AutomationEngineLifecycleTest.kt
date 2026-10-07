package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.AutomationRunStatus
import com.example.data.local.entity.EffectStatus
import com.example.data.local.entity.JobState
import com.example.domain.automation.AutomationStatus
import com.example.domain.automation.CycleOutcome
import com.example.domain.automation.CycleRequest
import com.example.domain.automation.CycleTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end cycle behaviour of the execution system against a real (in-memory) Room database:
 * lifecycle, idempotency, retry policy, kill switch and durable run bookkeeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationEngineLifecycleTest {

    private lateinit var harness: EngineTestHarness

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        harness = EngineTestHarness(context)
        harness.seedRules(testRules(autoSendOffers = false))
        harness.source.bundles = listOf(testBundle(id = "prop-1"))
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // ------------------------------------------------------------------ happy path

    @Test
    fun `a cycle discovers, underwrites, qualifies and prepares an offer`() = runBlocking {
        harness.seedRules(testRules(autoSendOffers = false))

        val outcome = harness.engine.executeCycle(CycleRequest(trigger = CycleTrigger.MANUAL))

        assertTrue("cycle should complete: $outcome", outcome is CycleOutcome.Completed)
        val stats = (outcome as CycleOutcome.Completed).stats
        assertEquals(1, stats.propertiesFound)
        assertEquals(1, stats.propertiesAnalyzed)
        assertEquals(1, stats.dealsQualified)
        assertEquals(1, stats.offersCreated)
        assertEquals(0, stats.offersSent)

        val job = harness.jobs().single()
        assertEquals(JobState.OFFER_READY, job.state())
        assertEquals(JobState.OFFER_READY.name, job.lastSuccessfulState)
        assertNotNull("offer id must be linked for recovery", job.offerId)
        assertNotNull("real start time must be recorded", job.startedAt)

        val offer = harness.offers.offerFor("prop-1")
        assertNotNull(offer)
        assertEquals("READY", offer!!.status)

        val run = harness.runs().single()
        assertEquals(AutomationRunStatus.COMPLETED, run.status)
        assertEquals(1, run.offersCreated)
        assertEquals(1, run.propertiesAnalyzed)
    }

    @Test
    fun `the real run start time is preserved after completion`() = runBlocking {
        val startedAt = harness.clock.now()

        harness.engine.executeCycle(CycleRequest(trigger = CycleTrigger.MANUAL))

        harness.clock.advance(60_000L)
        val run = harness.runs().single()
        assertEquals("startTime must never be overwritten", startedAt, run.startTime)
        assertNotNull(run.endTime)
        assertTrue("endTime must be after startTime", (run.endTime ?: 0L) >= run.startTime)
        assertEquals(CycleTrigger.MANUAL, run.trigger)
        assertTrue(run.correlationId.isNotBlank())
    }

    @Test
    fun `jobs record start and completion timestamps`() = runBlocking {
        harness.seedRules(testRules(autoGenerateOffers = false))

        harness.engine.executeCycle(CycleRequest())

        val job = harness.jobs().single()
        assertNotNull(job.startedAt)
        assertNull("the job is not finished yet", job.completedAt)
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    fun `running the same cycle twice does not duplicate work`() = runBlocking {
        harness.engine.executeCycle(CycleRequest())
        val firstJob = harness.jobs().single()
        val generatedOffers = harness.offers.generateCalls

        // Second cycle, same property discovered again, and the offer is already usable.
        harness.clock.advance(60_000L)
        harness.engine.executeCycle(CycleRequest())

        val jobs = harness.jobs()
        assertEquals("the same property must not create a second job", 1, jobs.size)
        assertEquals(firstJob.jobId, jobs.single().jobId)
        assertEquals("no duplicate offer generation", generatedOffers, harness.offers.generateCalls)
        assertEquals("exactly one email was ever sent", 0, harness.offers.sendAttempts)
    }

    @Test
    fun `duplicate discovery inside one run creates a single job`() = runBlocking {
        harness.source.bundles = listOf(testBundle(id = "prop-1"), testBundle(id = "prop-1", address = "100 Test St"))

        harness.engine.executeCycle(CycleRequest())

        assertEquals(1, harness.jobs().size)
        assertEquals(1, harness.offers.generateCalls)
    }

    @Test
    fun `the idempotency ledger records every irreversible effect`() = runBlocking {
        harness.engine.executeCycle(CycleRequest())

        val offer = harness.offers.offerFor("prop-1")!!
        val ledgerEntry = harness.dao.getExecution("GENERATE_OFFER:prop-1")
        assertNotNull("offer generation must be recorded in the ledger", ledgerEntry)
        assertEquals(EffectStatus.SUCCEEDED.name, ledgerEntry!!.status)
        assertEquals(offer.id, ledgerEntry.resultRef)
    }

    @Test
    fun `auto send transmits exactly once and never resends on later cycles`() = runBlocking {
        harness.seedRules(testRules(autoSendOffers = true))

        val first = harness.engine.executeCycle(CycleRequest())
        assertEquals(1, (first as CycleOutcome.Completed).stats.offersSent)
        assertEquals(1, harness.offers.sendAttempts)
        val job = harness.jobs().single()
        assertEquals(JobState.SENT, job.state())
        assertEquals("SENT", harness.offers.offerFor("prop-1")!!.status)
        assertNotNull(job.emailMessageId)

        harness.clock.advance(120_000L)
        harness.engine.executeCycle(CycleRequest())

        assertEquals("a delivered offer must never be emailed twice", 1, harness.offers.sendAttempts)
        assertEquals(JobState.SENT, harness.jobs().single().state())
    }

    // ------------------------------------------------------------------ retry semantics

    @Test
    fun `a transient send failure schedules a bounded retry and honours the backoff`() = runBlocking {
        harness.seedRules(testRules(autoSendOffers = true, retryBackoffBaseSeconds = 60, retryBackoffMaxMinutes = 30))
        harness.offers.sendSucceeds = false
        harness.offers.sendFailureMessage = "Gmail API timeout while contacting the server"

        harness.engine.executeCycle(CycleRequest())

        val failed = harness.jobs().single()
        assertEquals(JobState.FAILED_RETRYABLE, failed.state())
        assertEquals(1, failed.attempts)
        assertEquals("SEND_OFFER", failed.failedStep)
        assertTrue("backoff must be scheduled in the future", failed.nextAttemptAt > harness.clock.now())

        // Second cycle before the backoff elapsed: no new attempt is made.
        harness.clock.advance(10_000L)
        harness.engine.executeCycle(CycleRequest())
        assertEquals("the backoff must protect Gmail", 1, harness.offers.sendAttempts)
        assertEquals(JobState.FAILED_RETRYABLE, harness.jobs().single().state())

        // After the backoff the retry runs and succeeds.
        harness.offers.sendSucceeds = true
        harness.clock.advance(120_000L)
        harness.engine.executeCycle(CycleRequest())

        assertEquals(2, harness.offers.sendAttempts)
        assertEquals(JobState.SENT, harness.jobs().single().state())
    }

    @Test
    fun `retries escalate to a terminal failure when exhausted`() = runBlocking {
        harness.seedRules(testRules(autoSendOffers = true, maxRetries = 2, retryBackoffBaseSeconds = 1))
        harness.offers.sendSucceeds = false

        harness.engine.executeCycle(CycleRequest())
        assertEquals(JobState.FAILED_RETRYABLE, harness.jobs().single().state())

        harness.clock.advance(5_000L)
        harness.engine.executeCycle(CycleRequest())

        val terminal = harness.jobs().single()
        assertEquals(JobState.FAILED_TERMINAL, terminal.state())
        assertEquals(2, terminal.attempts)
        assertNotNull(terminal.completedAt)

        // Terminal jobs are never retried again, even much later.
        harness.clock.advance(10 * 60_000L)
        harness.engine.executeCycle(CycleRequest())
        assertEquals(2, harness.offers.sendAttempts)
    }

    @Test
    fun `a deterministic blocker parks the job instead of burning retries`() = runBlocking {
        harness.seedRules(testRules(autoSendOffers = true))
        harness.offers.validationResult = com.example.data.repository.PreSendValidationResult(
            isValid = false,
            blockageReason = "Gmail account is not connected with a valid authorized OAuth token."
        )

        harness.engine.executeCycle(CycleRequest())

        val blocked = harness.jobs().single()
        assertEquals(JobState.BLOCKED, blocked.state())
        assertEquals(0, blocked.attempts)
        assertEquals(0, harness.offers.sendAttempts)
        assertTrue(blocked.blockageReason!!.contains("Gmail"))

        // Blocked jobs are not retried automatically.
        harness.clock.advance(60 * 60_000L)
        harness.engine.executeCycle(CycleRequest())
        assertEquals(JobState.BLOCKED, harness.jobs().single().state())
    }

    @Test
    fun `an operator retry resumes a blocked job`() = runBlocking {
        harness.seedRules(testRules(autoSendOffers = true))
        harness.offers.validationResult = com.example.data.repository.PreSendValidationResult(false, "Recipient email address is blank.")
        harness.engine.executeCycle(CycleRequest())
        val blocked = harness.jobs().single()
        assertEquals(JobState.BLOCKED, blocked.state())

        // Operator fixes the configuration, then retries explicitly.
        harness.offers.validationResult = com.example.data.repository.PreSendValidationResult(true, null)
        val retried = harness.engine.retryJob(blocked.jobId)

        assertTrue("operator retry must be accepted", retried)
        assertEquals(JobState.VALIDATING_SEND, harness.jobs().single().state())

        harness.engine.executeCycle(CycleRequest())
        assertEquals(JobState.SENT, harness.jobs().single().state())
    }

    @Test
    fun `a terminal job is requeued as a successor instead of being revived`() = runBlocking {
        harness.seedRules(testRules(autoGenerateOffers = false))
        harness.financial.failure = IllegalStateException("deterministic underwriting defect")

        harness.engine.executeCycle(CycleRequest())
        val failed = harness.jobs().single()
        assertEquals(JobState.FAILED_TERMINAL, failed.state())

        val retried = harness.engine.retryJob(failed.jobId)

        assertTrue(retried)
        val jobs = harness.jobs()
        assertEquals("a successor job must be created", 2, jobs.size)
        val successor = jobs.first { it.jobId != failed.jobId }
        assertEquals(JobState.DISCOVERED, successor.state())
        assertEquals("the original terminal row is kept for audit", JobState.FAILED_TERMINAL, jobs.first { it.jobId == failed.jobId }.state())
    }

    // ------------------------------------------------------------------ kill switch & stop

    @Test
    fun `the kill switch is persisted and refuses execution until cleared`() = runBlocking {
        harness.engine.engageKillSwitch("operator pressed the emergency stop")

        val state = harness.state()!!
        assertTrue(state.killSwitchEngaged)
        assertEquals("operator pressed the emergency stop", state.killSwitchReason)
        assertFalse("automation must be disarmed", state.isEnabled)

        val refused = harness.engine.executeCycle(CycleRequest())
        assertTrue(refused is CycleOutcome.Skipped)
        assertTrue((refused as CycleOutcome.Skipped).killSwitch)
        assertEquals(0, harness.offers.generateCalls)
        assertEquals(AutomationStatus.KILLED, harness.engine.status.value)

        harness.engine.clearKillSwitch("operator")
        assertFalse(harness.state()!!.killSwitchEngaged)

        harness.seedRules(testRules(autoSendOffers = false))
        assertTrue(harness.engine.executeCycle(CycleRequest()) is CycleOutcome.Completed)
    }

    @Test
    fun `a kill switch engaged mid cycle halts the pipeline and marks the run killed`() = runBlocking {
        // Engage the kill switch while the property feed is being read.
        harness.source.onFetch = { runBlocking { harness.engine.engageKillSwitch("halt mid cycle") } }

        val outcome = harness.engine.executeCycle(CycleRequest())

        assertTrue("cycle must be reported as killed, was $outcome", outcome is CycleOutcome.Killed)
        assertTrue("no work may be started after the kill switch", harness.offers.generateCalls == 0)
        val run = harness.runs().last()
        assertEquals(AutomationRunStatus.KILLED, run.status)
    }

    @Test
    fun `stop automation disarms the engine and closes the running cycle`() = runBlocking {
        harness.engine.startAutomation(CycleTrigger.MANUAL)
        assertTrue("durable work must be enqueued", harness.scheduler.immediateRequests.isNotEmpty())
        assertTrue("periodic scan must be scheduled", harness.scheduler.periodicRequests.isNotEmpty())
        assertTrue(harness.state()!!.isEnabled)

        harness.engine.stopAutomation("operator stop")

        assertFalse(harness.state()!!.isEnabled)
        assertTrue("queued work must be cancelled", harness.scheduler.cancellations >= 1)
        assertEquals(AutomationStatus.STOPPED, harness.engine.status.value)
        assertFalse("stopped automation must not run scheduled work", harness.engine.shouldRunScheduledWork())
    }

    @Test
    fun `armed automation is durable and survives process restart`() = runBlocking {
        harness.engine.startAutomation(CycleTrigger.MANUAL)
        harness.scheduler.immediateRequests.clear()

        // Simulate a fresh process: same durable database, brand new engine instance.
        val restarted = EngineTestHarness(
            context = ApplicationProvider.getApplicationContext(),
            instanceId = "test-2",
            database = harness.database,
            ownsDatabase = false
        )
        try {
            restarted.seedRules(testRules(autoSendOffers = false))
            restarted.engine.onProcessStart()

            assertTrue(
                "armed automation must re-enqueue durable work after process death",
                restarted.scheduler.immediateRequests.contains(CycleTrigger.PROCESS_START)
            )
            assertTrue(restarted.state()!!.isEnabled)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `offline devices pause the cycle and ask for a retry`() = runBlocking {
        harness.network.online = false

        val outcome = harness.engine.executeCycle(CycleRequest())

        assertTrue(outcome is CycleOutcome.Completed)
        assertTrue((outcome as CycleOutcome.Completed).stats.pausedOffline)
        assertEquals(0, harness.offers.generateCalls)
        assertEquals("OFFLINE_PAUSED", harness.state()!!.currentStage)
        assertEquals(AutomationStatus.PAUSED_OFFLINE, harness.engine.status.value)
    }

    @Test
    fun `job cancellation is auditable and terminal`() = runBlocking {
        harness.engine.executeCycle(CycleRequest())
        val job = harness.jobs().single()

        assertTrue(harness.engine.cancelJob(job.jobId, "operator"))
        assertEquals(JobState.CANCELLED, harness.jobs().single().state())
        assertTrue(
            "cancellation must be visible in the audit trail",
            harness.dao.getLogsForJobFlow(job.jobId).first().any { it.tag == "OPERATOR_CANCEL" }
        )
    }

    @Test
    fun `audit trail records every state transition`() = runBlocking {
        harness.engine.executeCycle(CycleRequest())
        val job = harness.jobs().single()

        val transitions = harness.dao.getLogsForJobFlow(job.jobId).first().filter { it.tag == "STATE_TRANSITION" }
        assertTrue("transitions must be logged", transitions.size >= 4)
        assertTrue(transitions.all { !it.stateBefore.isNullOrBlank() && !it.stateAfter.isNullOrBlank() })
        assertTrue(transitions.any { it.stateBefore == JobState.DISCOVERED.name && it.stateAfter == JobState.ANALYZING.name })
        assertTrue(transitions.any { it.stateAfter == JobState.OFFER_READY.name })
        assertTrue(transitions.all { it.runId != null })
    }
}
