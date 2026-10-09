package com.example.automation

import com.example.data.local.entity.AutomationEffect
import com.example.data.local.entity.EffectStatus
import com.example.data.local.entity.JobState
import com.example.domain.automation.AutomationAuditLogger
import com.example.domain.automation.AutomationExecutionLedger
import com.example.domain.automation.AutomationSteps
import com.example.domain.automation.FailureKind
import com.example.domain.automation.JobStateMachine
import com.example.domain.automation.RetryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit-trail and idempotency-ledger contracts of the execution system.
 *
 * Pure JVM: the ledger and the audit logger sit on top of [FakeAutomationDao], so the durability
 * semantics (one audit event per committed transition, no record for a no-op, exactly-once side
 * effects) are checkable without Android or Room.
 */
class AutomationAuditAndLedgerContractTest {

    private val now = 1_700_000_000_000L
    private val policy = RetryPolicy(baseDelayMs = 1_000L, maxDelayMs = 60_000L, jitterFraction = 0.0)

    // ------------------------------------------------------------------ audit trail

    @Test
    fun `a committed transition writes exactly one STATE_TRANSITION record with before and after`() = runBlocking {
        val dao = FakeAutomationDao()
        val audit = AutomationAuditLogger(dao, FakeClock(now))
        val job = testJob(state = JobState.DISCOVERED)

        val outcome = JobStateMachine.transition(job, JobState.ANALYZING, now, policy)
        assertTrue(outcome.applied)
        dao.insertOrUpdateJob(outcome.job)
        audit.transition(outcome.job, JobState.DISCOVERED, JobState.ANALYZING, runId = 7, correlationId = "RUN-1", detail = "started")

        val transitions = dao.logsForJob(job.jobId).filter { it.tag == "STATE_TRANSITION" }
        assertEquals("exactly one audit event per committed transition", 1, transitions.size)
        val record = transitions.single()
        assertEquals(JobState.DISCOVERED.name, record.stateBefore)
        assertEquals(JobState.ANALYZING.name, record.stateAfter)
        assertEquals(7L, record.runId)
        assertTrue(record.message.contains("DISCOVERED -> ANALYZING"))
    }

    @Test
    fun `a rejected or rolled-back transition leaves no STATE_TRANSITION record`() = runBlocking {
        val dao = FakeAutomationDao()
        val audit = AutomationAuditLogger(dao, FakeClock(now))

        // Illegal transition: nothing is applied, so nothing may be audited as a transition.
        val job = testJob(state = JobState.DISCOVERED)
        val rejected = JobStateMachine.transition(job, JobState.SENT, now, policy, emailMessageId = "GMAIL-1")
        assertFalse(rejected.applied)

        // Same-state duplicate: idempotent no-op, again no record.
        val duplicate = JobStateMachine.transition(job, JobState.DISCOVERED, now, policy)
        assertFalse(duplicate.applied)

        assertEquals(
            "a rolled-back attempt must not leave a misleading audit entry",
            0,
            dao.logsForJob(job.jobId).count { it.tag == "STATE_TRANSITION" }
        )
    }

    @Test
    fun `failure transitions are audited as WARN and keep the state machine trail intact`() = runBlocking {
        val dao = FakeAutomationDao()
        val audit = AutomationAuditLogger(dao, FakeClock(now))
        val job = testJob(state = JobState.SENDING, offerId = "OFFER-1")

        val outcome = JobStateMachine.transition(
            job = job,
            target = JobState.FAILED_RETRYABLE,
            now = now,
            retryPolicy = policy,
            error = "Gmail delivery failed or rejected.",
            failedStep = AutomationSteps.SEND_OFFER,
            failureKind = FailureKind.RETRYABLE
        )
        assertTrue(outcome.applied)
        dao.insertOrUpdateJob(outcome.job)
        audit.transition(outcome.job, JobState.SENDING, JobState.FAILED_RETRYABLE, runId = 9, correlationId = "RUN-2", detail = "step failed")

        val record = dao.logsForJob(job.jobId).single { it.tag == "STATE_TRANSITION" }
        assertEquals("WARN", record.level)
        assertEquals(JobState.SENDING.name, record.stateBefore)
        assertEquals(JobState.FAILED_RETRYABLE.name, record.stateAfter)
        assertEquals(outcome.job.attempts, record.attempt)
    }

    @Test
    fun `audit history agrees with persisted state after a full pipeline walk`() = runBlocking {
        val dao = FakeAutomationDao()
        val audit = AutomationAuditLogger(dao, FakeClock(now))
        var job = testJob(state = JobState.DISCOVERED)

        val chain = listOf(
            JobState.ANALYZING to JobState.ANALYZED,
            JobState.ANALYZED to JobState.QUALIFYING,
            JobState.QUALIFYING to JobState.QUALIFIED,
            JobState.QUALIFIED to JobState.OFFER_GENERATION,
            JobState.OFFER_GENERATION to JobState.OFFER_READY
        )
        job = JobStateMachine.transition(job, JobState.ANALYZING, now, policy).also {
            dao.insertOrUpdateJob(it.job)
            audit.transition(it.job, JobState.DISCOVERED, JobState.ANALYZING, 1, "WALK", "walk")
        }.job
        chain.forEach { (from, to) ->
            val previous = job
            val offerId = if (to == JobState.OFFER_READY) "OFFER-1" else null
            val outcome = JobStateMachine.transition(previous, to, now, policy, offerId = offerId)
            assertTrue("$from -> $to must apply", outcome.applied)
            dao.insertOrUpdateJob(outcome.job)
            audit.transition(outcome.job, from, to, 1, "WALK", "walk")
            job = outcome.job
        }

        val transitions = dao.logsForJob(job.jobId).filter { it.tag == "STATE_TRANSITION" }
        assertEquals("one record per committed transition", 6, transitions.size)

        // The audit chain must be a contiguous walk that ends in the persisted state.
        transitions.zipWithNext().forEach { (earlier, later) ->
            assertEquals(earlier.stateAfter, later.stateBefore)
        }
        assertEquals(job.currentState, transitions.last().stateAfter)
        assertEquals(JobState.OFFER_READY.name, job.currentState)
    }

    // ------------------------------------------------------------------ idempotency ledger

    @Test
    fun `a succeeded effect is never performed twice`() = runBlocking {
        val dao = FakeAutomationDao()
        val ledger = AutomationExecutionLedger(dao, FakeClock(now))

        val first = ledger.begin(AutomationEffect.SEND_OFFER, "OFFER-1", "JOB-1", runId = 1)
        assertTrue("the first attempt owns the effect", first.allowed)
        assertFalse(first.alreadySucceeded)
        ledger.markSucceeded(AutomationEffect.SEND_OFFER, "OFFER-1", "JOB-1", runId = 1, resultRef = "GMAIL-1")

        // Duplicate delivery / repeated worker execution: the ledger says "already done".
        val second = ledger.begin(AutomationEffect.SEND_OFFER, "OFFER-1", "JOB-1", runId = 2)
        assertFalse("the effect must not run twice", second.allowed)
        assertTrue(second.alreadySucceeded)
        assertEquals("GMAIL-1", second.resultRef)

        val snapshot = ledger.snapshot(AutomationEffect.SEND_OFFER, "OFFER-1")
        assertEquals(EffectStatus.SUCCEEDED.name, snapshot?.status)
        assertEquals("GMAIL-1", snapshot?.resultRef)
    }

    @Test
    fun `a failed effect is retried but keeps its attempt count`() = runBlocking {
        val dao = FakeAutomationDao()
        val ledger = AutomationExecutionLedger(dao, FakeClock(now))

        val first = ledger.begin(AutomationEffect.GENERATE_OFFER, "prop-1", "JOB-1", runId = 1)
        assertEquals(1, first.attempt)
        ledger.markFailed(AutomationEffect.GENERATE_OFFER, "prop-1", "JOB-1", runId = 1, error = "boom")

        val second = ledger.begin(AutomationEffect.GENERATE_OFFER, "prop-1", "JOB-1", runId = 2)
        assertTrue("a failed effect may be attempted again", second.allowed)
        assertFalse(second.alreadySucceeded)
        assertEquals("the ledger counts attempts durably", 2, second.attempt)
    }

    @Test
    fun `an in-flight effect is not trusted after a crash`() = runBlocking {
        val dao = FakeAutomationDao()
        val ledger = AutomationExecutionLedger(dao, FakeClock(now))

        val first = ledger.begin(AutomationEffect.SEND_OFFER, "OFFER-9", "JOB-9", runId = 1)
        assertTrue(first.allowed)
        // No outcome was written: the process died mid-effect.
        val afterCrash = ledger.begin(AutomationEffect.SEND_OFFER, "OFFER-9", "JOB-9", runId = 2)
        assertTrue("an IN_PROGRESS row must not block the durable retry", afterCrash.allowed)
        assertFalse(afterCrash.alreadySucceeded)
        assertNull(ledger.snapshot(AutomationEffect.SEND_OFFER, "OFFER-9")?.resultRef)
        assertNotNull(ledger.snapshot(AutomationEffect.SEND_OFFER, "OFFER-9"))
    }
}
