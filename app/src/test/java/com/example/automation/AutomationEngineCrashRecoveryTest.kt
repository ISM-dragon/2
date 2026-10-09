package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.AutomationEffect
import com.example.data.local.entity.AutomationExecutionEntity
import com.example.data.local.entity.AutomationRunStatus
import com.example.data.local.entity.EffectStatus
import com.example.data.local.entity.JobState
import com.example.domain.automation.AutomationSteps
import com.example.domain.automation.CycleOutcome
import com.example.domain.automation.CycleRequest
import com.example.domain.automation.CycleTrigger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crash recovery: what happens to jobs and runs that a previous process left behind.
 * Every scenario seeds durable state the way a hard kill (no exception handler) would leave it:
 * a job stuck in an in-flight state, an expired execution lease and a run without heartbeat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationEngineCrashRecoveryTest {

    private lateinit var harness: EngineTestHarness

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        harness = EngineTestHarness(context)
        // Recovery semantics are exercised without automatic sending: a job parked at OFFER_READY
        // must wait for the operator (or an engine that explicitly arms autoSendOffers, as
        // `work interrupted by process death` does for its final phase) instead of being auto-sent.
        harness.seedRules(testRules())
    }

    @After
    fun tearDown() {
        harness.close()
    }

    private fun expiredLeaseOwner() = "engine-dead"

    // ------------------------------------------------------------------ runs

    @Test
    fun `a run without heartbeat is marked interrupted and keeps its real start time`() = runBlocking {
        val realStart = harness.clock.now() - 3_600_000L
        val runId = harness.dao.insertRun(
            testRun(startTime = realStart, heartbeatAt = realStart, trigger = CycleTrigger.PERIODIC)
        )

        val report = harness.engine.recoverInterruptedWork()

        assertEquals(1, report.interruptedRuns)
        val run = harness.dao.getRunById(runId)!!
        assertEquals(AutomationRunStatus.INTERRUPTED, run.status)
        assertEquals("the real start time must never be rewritten", realStart, run.startTime)
        assertNotNull(run.endTime)
        assertEquals(CycleTrigger.PERIODIC, run.trigger)
    }

    @Test
    fun `a live run is left untouched`() = runBlocking {
        val runId = harness.dao.insertRun(testRun(startTime = harness.clock.now(), heartbeatAt = harness.clock.now()))

        harness.engine.recoverInterruptedWork()

        assertEquals(AutomationRunStatus.RUNNING, harness.dao.getRunById(runId)!!.status)
    }

    // ------------------------------------------------------------------ jobs

    @Test
    fun `interrupted analysis resumes from the persisted analysis`() = runBlocking {
        harness.financial.hasAnalysisFlag = true
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-ANALYZING",
                state = JobState.ANALYZING,
                lastSuccessful = JobState.DISCOVERED,
                leaseOwner = expiredLeaseOwner(),
                leaseExpiresAt = harness.clock.now() - 1_000L
            )
        )

        val report = harness.engine.recoverInterruptedWork()

        assertEquals(1, report.reconciledJobs)
        val job = harness.dao.getJobById("JOB-ANALYZING")!!
        assertEquals(JobState.ANALYZED, job.state())
        assertEquals(1, job.recoveryCount)
        assertNotNull(job.lastRecoveredAt)
        assertNull("the abandoned lease must be cleared", job.leaseOwner)
    }

    @Test
    fun `interrupted analysis without persisted evidence restarts the job`() = runBlocking {
        harness.financial.hasAnalysisFlag = false
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-NO-EVIDENCE",
                state = JobState.ANALYZING,
                lastSuccessful = JobState.DISCOVERED,
                leaseOwner = expiredLeaseOwner(),
                leaseExpiresAt = harness.clock.now() - 1_000L
            )
        )

        harness.engine.recoverInterruptedWork()

        assertEquals(JobState.DISCOVERED, harness.dao.getJobById("JOB-NO-EVIDENCE")!!.state())
    }

    @Test
    fun `offer generation recovery adopts the offer that already exists`() = runBlocking {
        // The regression this fixes: the previous implementation always dropped back to QUALIFIED
        // and lost the link to the offer that had already been generated and persisted.
        harness.offers.offers["OFFER-77"] = testOffer(id = "OFFER-77", propertyId = "prop-77", status = "READY")
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-OFFERGEN",
                propertyId = "prop-77",
                state = JobState.OFFER_GENERATION,
                lastSuccessful = JobState.QUALIFIED,
                leaseOwner = expiredLeaseOwner(),
                leaseExpiresAt = harness.clock.now() - 1_000L
            )
        )

        val report = harness.engine.recoverInterruptedWork()

        assertEquals(1, report.reconciledJobs)
        val job = harness.dao.getJobById("JOB-OFFERGEN")!!
        assertEquals(JobState.OFFER_READY, job.state())
        assertEquals("OFFER-77", job.offerId)
        assertEquals(0, harness.offers.generateCalls)

        // Continuing the cycle must not regenerate the offer either.
        harness.source.bundles = emptyList()
        harness.engine.executeCycle(CycleRequest())
        assertEquals(0, harness.offers.generateCalls)
        assertEquals(JobState.OFFER_READY, harness.dao.getJobById("JOB-OFFERGEN")!!.state())
    }

    @Test
    fun `offer generation recovery without an offer replays the step`() = runBlocking {
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-OFFERGEN-2",
                propertyId = "prop-88",
                state = JobState.OFFER_GENERATION,
                lastSuccessful = JobState.QUALIFIED,
                leaseOwner = expiredLeaseOwner(),
                leaseExpiresAt = harness.clock.now() - 1_000L
            )
        )
        harness.propertyDaoSeed("prop-88")

        harness.engine.recoverInterruptedWork()

        val job = harness.dao.getJobById("JOB-OFFERGEN-2")!!
        assertEquals(JobState.QUALIFIED, job.state())
        assertNull(job.offerId)
    }

    @Test
    fun `an ambiguous send is reconciled and never blindly re-sent`() = runBlocking {
        harness.offers.offers["OFFER-99"] = testOffer(id = "OFFER-99", propertyId = "prop-99", status = "READY")
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-SENDING",
                propertyId = "prop-99",
                state = JobState.SENDING,
                lastSuccessful = JobState.OFFER_READY,
                offerId = "OFFER-99",
                leaseOwner = expiredLeaseOwner(),
                leaseExpiresAt = harness.clock.now() - 1_000L
            )
        )

        harness.engine.recoverInterruptedWork()
        val reconciled = harness.dao.getJobById("JOB-SENDING")!!
        assertEquals("an unknown delivery outcome must be reconciled", JobState.RECONCILING, reconciled.state())
        assertEquals("no email may be sent while the outcome is unknown", 0, harness.offers.sendAttempts)

        // The next cycle resolves the ambiguity: a safe re-send is scheduled with backoff.
        harness.source.bundles = emptyList()
        harness.engine.executeCycle(CycleRequest())

        val scheduled = harness.dao.getJobById("JOB-SENDING")!!
        assertEquals(JobState.FAILED_RETRYABLE, scheduled.state())
        assertEquals(AutomationSteps.SEND_OFFER, scheduled.failedStep)
        assertEquals(0, harness.offers.sendAttempts)

        // After the backoff, exactly one delivery happens.
        harness.clock.advance(10 * 60_000L)
        harness.engine.executeCycle(CycleRequest())

        assertEquals(1, harness.offers.sendAttempts)
        assertEquals(JobState.SENT, harness.dao.getJobById("JOB-SENDING")!!.state())
    }

    @Test
    fun `a delivery recorded in the ledger completes the job without a second email`() = runBlocking {
        harness.offers.offers["OFFER-55"] = testOffer(id = "OFFER-55", propertyId = "prop-55", status = "READY")
        harness.dao.insertOrUpdateExecution(
            AutomationExecutionEntity(
                idempotencyKey = AutomationEffect.idempotencyKey(AutomationEffect.SEND_OFFER, "OFFER-55"),
                jobId = "JOB-LEDGER",
                runId = 42L,
                effect = AutomationEffect.SEND_OFFER,
                status = EffectStatus.SUCCEEDED.name,
                attempt = 1,
                resultRef = "GMAIL-OFFER-55",
                startedAt = harness.clock.now() - 60_000L,
                finishedAt = harness.clock.now() - 59_000L
            )
        )
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-LEDGER",
                propertyId = "prop-55",
                state = JobState.SENDING,
                lastSuccessful = JobState.OFFER_READY,
                offerId = "OFFER-55",
                leaseOwner = expiredLeaseOwner(),
                leaseExpiresAt = harness.clock.now() - 1_000L
            )
        )
        harness.source.bundles = emptyList()

        harness.engine.executeCycle(CycleRequest())

        val job = harness.dao.getJobById("JOB-LEDGER")!!
        assertEquals(JobState.SENT, job.state())
        assertEquals("GMAIL-OFFER-55", job.emailMessageId)
        assertEquals("the ledger proves delivery: no new email", 0, harness.offers.sendAttempts)
    }

    @Test
    fun `a live execution lease is never stolen from another worker`() = runBlocking {
        val liveLease = harness.clock.now() + 120_000L
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-LIVE",
                state = JobState.ANALYZING,
                lastSuccessful = JobState.DISCOVERED,
                leaseOwner = "engine-alive",
                leaseExpiresAt = liveLease
            )
        )

        val report = harness.engine.recoverInterruptedWork()

        assertEquals(0, report.reconciledJobs)
        val job = harness.dao.getJobById("JOB-LIVE")!!
        assertEquals(JobState.ANALYZING, job.state())
        assertEquals("engine-alive", job.leaseOwner)
        assertEquals(liveLease, job.leaseExpiresAt)
    }

    @Test
    fun `the recovery budget stops a crash loop`() = runBlocking {
        harness.seedRules(testRules(maxRecoveryAttempts = 3))
        harness.financial.hasAnalysisFlag = true
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-LOOP",
                state = JobState.ANALYZING,
                lastSuccessful = JobState.DISCOVERED,
                recoveryCount = 3,
                leaseOwner = expiredLeaseOwner(),
                leaseExpiresAt = harness.clock.now() - 1_000L
            )
        )

        harness.engine.recoverInterruptedWork()

        val job = harness.dao.getJobById("JOB-LOOP")!!
        assertEquals(JobState.FAILED_TERMINAL, job.state())
        assertNotNull(job.completedAt)
        assertTrue(job.lastError!!.contains("Recovery budget"))
    }

    @Test
    fun `recovery is idempotent`() = runBlocking {
        harness.financial.hasAnalysisFlag = true
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-IDEMPOTENT",
                state = JobState.ANALYZING,
                lastSuccessful = JobState.DISCOVERED,
                leaseOwner = expiredLeaseOwner(),
                leaseExpiresAt = harness.clock.now() - 1_000L
            )
        )

        val first = harness.engine.recoverInterruptedWork()
        val second = harness.engine.recoverInterruptedWork()

        assertEquals(1, first.reconciledJobs)
        assertEquals("the second pass has nothing left to do", 0, second.reconciledJobs)
        assertEquals(1, harness.dao.getJobById("JOB-IDEMPOTENT")!!.recoveryCount)
    }

    // ------------------------------------------------------------------ end to end

    @Test
    fun `work interrupted by process death is resumed and finished by the next cycle`() = runBlocking {
        // 1. A first cycle prepares an offer but is not allowed to send it yet.
        harness.seedRules(testRules(autoSendOffers = false))
        harness.source.bundles = listOf(testBundle(id = "prop-crash"))
        harness.engine.executeCycle(CycleRequest())
        val job = harness.jobs().single()
        assertEquals(JobState.OFFER_READY, job.state())
        assertNotNull(job.offerId)
        assertEquals(1, harness.offers.generateCalls)

        // 2. Simulate a hard kill while re-entering offer generation, leaving a dead lease and a
        //    RUNNING run without heartbeat behind (no exception handler ever ran).
        val deadLease = harness.clock.now() - 60_000L
        harness.dao.insertOrUpdateJob(
            job.copy(
                currentState = JobState.OFFER_GENERATION.name,
                lastSuccessfulState = JobState.QUALIFIED.name,
                leaseOwner = expiredLeaseOwner(),
                leaseExpiresAt = deadLease,
                attempts = 0
            )
        )
        val deadRunStart = harness.clock.now() - 30 * 60_000L
        val deadRunId = harness.dao.insertRun(testRun(startTime = deadRunStart, heartbeatAt = deadRunStart))

        // 3. A fresh process starts: reconcile from durable state and re-arm the durable worker.
        harness.clock.advance(60_000L)
        val restarted = EngineTestHarness(
            context = ApplicationProvider.getApplicationContext(),
            instanceId = "engine-restarted",
            database = harness.database,
            ownsDatabase = false
        )
        restarted.seedRules(testRules(autoSendOffers = true))
        restarted.engine.onProcessStart()

        assertEquals(AutomationRunStatus.INTERRUPTED, harness.dao.getRunById(deadRunId)!!.status)
        assertEquals("the dead run keeps its real start time", deadRunStart, harness.dao.getRunById(deadRunId)!!.startTime)
        val recovered = harness.dao.getJobById(job.jobId)!!
        assertEquals(JobState.OFFER_READY, recovered.state())
        assertEquals("the recovered job must be linked to its existing offer", job.offerId, recovered.offerId)
        assertEquals("no regeneration during recovery", 1, harness.offers.generateCalls)
        assertTrue(
            "durable work must be re-enqueued after a restart",
            restarted.scheduler.immediateRequests.contains(CycleTrigger.PROCESS_START)
        )

        // 4. The resumed cycle finishes the interrupted job exactly once.
        val outcome = restarted.engine.executeCycle(CycleRequest(trigger = CycleTrigger.WORKER))
        assertTrue(outcome is CycleOutcome.Completed)
        val finalJob = harness.dao.getJobById(job.jobId)!!
        assertEquals(JobState.SENT, finalJob.state())
        assertEquals("the existing offer must not be regenerated", 1, harness.offers.generateCalls)
        assertEquals("exactly one email in total", 1, harness.offers.sendAttempts)
    }
}

/** Seeds a property row so recovery can resolve the property for offer regeneration. */
private suspend fun EngineTestHarness.propertyDaoSeed(propertyId: String) {
    propertyDao.insertProperty(testBundle(id = propertyId).property)
}
