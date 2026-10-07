package com.example.automation

import com.example.data.local.entity.JobState
import com.example.domain.automation.AutomationSteps
import com.example.domain.automation.FailureKind
import com.example.domain.automation.JobStateMachine
import com.example.domain.automation.RetryPolicy
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * State transition tests for the persisted job state machine.
 *
 * These are pure JVM tests: the state machine holds no Android, database or coroutine state, so
 * every transition rule is checkable deterministically.
 */
class JobStateMachineTest {

    private val now = 1_700_000_000_000L
    private val policy = RetryPolicy(baseDelayMs = 1_000L, maxDelayMs = 60_000L, jitterFraction = 0.0)

    // ------------------------------------------------------------------ topology

    @Test
    fun `happy path transitions are accepted`() {
        val chain = listOf(
            JobState.DISCOVERED to JobState.ANALYZING,
            JobState.ANALYZING to JobState.ANALYZED,
            JobState.ANALYZED to JobState.QUALIFYING,
            JobState.QUALIFYING to JobState.QUALIFIED,
            JobState.QUALIFIED to JobState.OFFER_GENERATION,
            JobState.OFFER_GENERATION to JobState.OFFER_READY,
            JobState.OFFER_READY to JobState.VALIDATING_SEND,
            JobState.VALIDATING_SEND to JobState.SENDING,
            JobState.SENDING to JobState.SENT
        )
        chain.forEach { (from, to) ->
            assertTrue("$from -> $to must be legal", from.canTransitionTo(to))
        }
    }

    @Test
    fun `pipeline shortcuts and revivals of terminal states are rejected`() {
        assertFalse(JobState.DISCOVERED.canTransitionTo(JobState.SENT))
        assertFalse(JobState.DISCOVERED.canTransitionTo(JobState.OFFER_GENERATION))
        assertFalse(JobState.DISQUALIFIED.canTransitionTo(JobState.OFFER_GENERATION))
        assertFalse(JobState.DISQUALIFIED.canTransitionTo(JobState.ANALYZING))
        assertFalse(JobState.SENT.canTransitionTo(JobState.ANALYZING))
        assertFalse(JobState.SENT.canTransitionTo(JobState.SENDING))
        assertFalse(JobState.CANCELLED.canTransitionTo(JobState.SENDING))
        assertFalse(JobState.FAILED_TERMINAL.canTransitionTo(JobState.ANALYZING))
        assertFalse(JobState.OFFER_READY.canTransitionTo(JobState.QUALIFIED))
    }

    @Test
    fun `sent and cancelled can never leave their state`() {
        JobState.entries.forEach { target ->
            assertFalse("SENT must not move to $target", JobState.SENT.canTransitionTo(target))
            assertFalse("CANCELLED must not move to $target", JobState.CANCELLED.canTransitionTo(target))
        }
    }

    @Test
    fun `every non terminal state can fail and be cancelled`() {
        val inFlight = listOf(
            JobState.DISCOVERED, JobState.ANALYZING, JobState.ANALYZED, JobState.QUALIFYING,
            JobState.QUALIFIED, JobState.OFFER_GENERATION, JobState.OFFER_READY,
            JobState.VALIDATING_SEND, JobState.SENDING, JobState.RECONCILING
        )
        inFlight.forEach { state ->
            assertTrue("$state must be cancellable", state.canTransitionTo(JobState.CANCELLED))
            assertTrue("$state must support retryable failure", state.canTransitionTo(JobState.FAILED_RETRYABLE))
            assertTrue("$state must support terminal failure", state.canTransitionTo(JobState.FAILED_TERMINAL))
        }
    }

    @Test
    fun `terminal classification matches the persisted contract`() {
        assertTrue(JobState.SENT.isTerminal)
        assertTrue(JobState.DISQUALIFIED.isTerminal)
        assertTrue(JobState.FAILED_TERMINAL.isTerminal)
        assertTrue(JobState.CANCELLED.isTerminal)
        assertFalse(JobState.BLOCKED.isTerminal)
        assertFalse(JobState.RECONCILING.isTerminal)
        assertTrue(JobState.BLOCKED.needsOperatorAction)
        assertTrue(JobState.FAILED_RETRYABLE.isAutoResumable)
        assertFalse(JobState.BLOCKED.isAutoResumable)
        assertTrue(JobState.IN_FLIGHT.all { it.isInFlight })
        assertFalse(JobState.SENT in JobState.PENDING)
    }

    // ------------------------------------------------------------------ transition semantics

    @Test
    fun `retryable failure increments attempts and schedules backoff`() {
        val job = testJob(state = JobState.SENDING, lastSuccessful = JobState.OFFER_READY, attempts = 0)
        val outcome = JobStateMachine.transition(
            job = job,
            target = JobState.FAILED_RETRYABLE,
            now = now,
            retryPolicy = policy,
            error = "Network timeout",
            failedStep = AutomationSteps.SEND_OFFER,
            failureKind = FailureKind.RETRYABLE,
            random = Random(1)
        )

        assertTrue(outcome.applied)
        assertEquals(JobState.FAILED_RETRYABLE, outcome.job.state())
        assertEquals(1, outcome.job.attempts)
        assertEquals(JobState.OFFER_READY.name, outcome.job.lastSuccessfulState)
        assertEquals(AutomationSteps.SEND_OFFER, outcome.job.failedStep)
        assertEquals(FailureKind.RETRYABLE.name, outcome.job.failureKind)
        assertTrue("backoff must be scheduled", outcome.job.nextAttemptAt > now)
        assertTrue("backoff must respect the base delay", outcome.job.nextAttemptAt >= now + 1_000L)
        assertEquals("Network timeout", outcome.job.lastError)
    }

    @Test
    fun `failures escalate to terminal once attempts are exhausted`() {
        val job = testJob(state = JobState.SENDING, attempts = 2, maxRetries = 3)
        val outcome = JobStateMachine.transition(
            job = job,
            target = JobState.FAILED_RETRYABLE,
            now = now,
            retryPolicy = policy,
            error = "Network timeout",
            failedStep = AutomationSteps.SEND_OFFER,
            failureKind = FailureKind.RETRYABLE
        )

        assertEquals(JobState.FAILED_TERMINAL, outcome.job.state())
        assertEquals(3, outcome.job.attempts)
        assertEquals(FailureKind.TERMINAL.name, outcome.job.failureKind)
        assertEquals(0L, outcome.job.nextAttemptAt)
        assertEquals("terminal states must be stamped", now, outcome.job.completedAt)
    }

    @Test
    fun `blocked failures park the job without burning retries`() {
        val job = testJob(state = JobState.VALIDATING_SEND, attempts = 0, maxRetries = 3)
        val outcome = JobStateMachine.transition(
            job = job,
            target = JobState.BLOCKED,
            now = now,
            retryPolicy = policy,
            error = "Recipient email is blank.",
            failedStep = AutomationSteps.VALIDATE_SEND,
            failureKind = FailureKind.BLOCKED,
            blockageReason = "Recipient email is blank."
        )

        assertEquals(JobState.BLOCKED, outcome.job.state())
        assertEquals(0, outcome.job.attempts)
        assertEquals(0L, outcome.job.nextAttemptAt)
        assertTrue(outcome.job.state().needsOperatorAction)
        assertNull("blocked jobs are not completed", outcome.job.completedAt)
        assertEquals(0L, outcome.job.leaseExpiresAt)
    }

    @Test
    fun `successful milestones reset the attempt counter and update the resume point`() {
        val job = testJob(state = JobState.QUALIFYING, attempts = 2, lastSuccessful = JobState.ANALYZED)
        val outcome = JobStateMachine.transition(
            job = job,
            target = JobState.QUALIFIED,
            now = now,
            retryPolicy = policy
        )

        assertEquals(JobState.QUALIFIED, outcome.job.state())
        assertEquals(0, outcome.job.attempts)
        assertEquals(JobState.QUALIFIED.name, outcome.job.lastSuccessfulState)
        assertNull(outcome.job.lastError)
    }

    @Test
    fun `offer ready requires a persisted offer id`() {
        val job = testJob(state = JobState.QUALIFIED)
        val rejected = JobStateMachine.transition(job, JobState.OFFER_READY, now, policy)
        assertFalse(rejected.applied)
        assertTrue(rejected.rejectionReason?.contains("offerId") == true)

        val accepted = JobStateMachine.transition(job, JobState.OFFER_READY, now, policy, offerId = "OFFER-9")
        assertTrue(accepted.applied)
        assertEquals("OFFER-9", accepted.job.offerId)
    }

    @Test
    fun `sent requires delivery evidence`() {
        val job = testJob(state = JobState.SENDING, offerId = null, emailMessageId = null)
        val rejected = JobStateMachine.transition(job, JobState.SENT, now, policy)
        assertFalse(rejected.applied)

        val accepted = JobStateMachine.transition(job, JobState.SENT, now, policy, emailMessageId = "GMAIL-1")
        assertTrue(accepted.applied)
        assertEquals(JobState.SENT, accepted.job.state())
        assertTrue(accepted.job.state().isTerminal)
    }

    @Test
    fun `real start time is stamped once and completion is stamped at the end`() {
        val discovered = testJob(state = JobState.DISCOVERED)
        assertNull(discovered.startedAt)

        val analyzing = JobStateMachine.transition(discovered, JobState.ANALYZING, now, policy).job
        assertEquals(now, analyzing.startedAt)

        val analyzed = JobStateMachine.transition(analyzing, JobState.ANALYZED, now + 5_000L, policy).job
        assertEquals("start time must not drift", now, analyzed.startedAt)

        val sent = JobStateMachine.transition(analyzed, JobState.FAILED_TERMINAL, now + 9_000L, policy).job
        assertEquals(now, sent.startedAt)
        assertEquals(now + 9_000L, sent.completedAt)
    }

    @Test
    fun `same state transitions are idempotent no-ops`() {
        val job = testJob(state = JobState.ANALYZING)
        val outcome = JobStateMachine.transition(job, JobState.ANALYZING, now, policy)
        assertFalse(outcome.applied)
        assertEquals("no-op must not modify the row", job, outcome.job)
    }

    @Test
    fun `recovery increments the crash counter and respects the budget`() {
        val job = testJob(state = JobState.ANALYZING, recoveryCount = 1)
        val recovered = JobStateMachine.transition(
            job = job,
            target = JobState.ANALYZED,
            now = now,
            retryPolicy = policy,
            recovery = true
        )
        assertEquals(2, recovered.job.recoveryCount)
        assertEquals(now, recovered.job.lastRecoveredAt)

        val exhausted = testJob(state = JobState.ANALYZING, recoveryCount = 3)
        val escalated = JobStateMachine.transition(
            job = exhausted,
            target = JobState.ANALYZED,
            now = now,
            retryPolicy = RetryPolicy(recoveryBudget = 3),
            recovery = true
        )
        assertTrue(escalated.applied)
        assertEquals(JobState.FAILED_TERMINAL, escalated.job.state())
        assertEquals(now, escalated.job.completedAt)
    }

    @Test
    fun `escalation refuses to revive terminal jobs`() {
        val sent = testJob(state = JobState.SENT, completedAt = now)
        val outcome = JobStateMachine.escalatedOutcome(sent, now + 1_000L, "should not happen")
        assertFalse(outcome.applied)
        assertEquals(JobState.SENT, outcome.job.state())
    }

    // ------------------------------------------------------------------ retry policy / steps

    @Test
    fun `backoff grows exponentially and stays capped`() {
        val policy = RetryPolicy(baseDelayMs = 1_000L, maxDelayMs = 10_000L, multiplier = 2.0, jitterFraction = 0.0)
        assertEquals(1_000L, policy.backoffFor(1))
        assertEquals(2_000L, policy.backoffFor(2))
        assertEquals(4_000L, policy.backoffFor(3))
        assertEquals(8_000L, policy.backoffFor(4))
        assertEquals(10_000L, policy.backoffFor(5))
        assertEquals(10_000L, policy.backoffFor(50))
    }

    @Test
    fun `jittered backoff stays inside the configured envelope`() {
        val policy = RetryPolicy(baseDelayMs = 10_000L, maxDelayMs = 10_000L, jitterFraction = 0.25)
        repeat(200) { seed ->
            val delay = policy.backoffFor(1, Random(seed))
            assertTrue("delay $delay out of range", delay in 7_500L..12_500L)
        }
    }

    @Test
    fun `retry policy derives from persisted rules`() {
        val policy = RetryPolicy.fromRules(testRules(retryBackoffBaseSeconds = 5, retryBackoffMaxMinutes = 2, maxRetries = 4))
        assertEquals(5_000L, policy.baseDelayMs)
        assertEquals(120_000L, policy.maxDelayMs)
        assertEquals(4, policy.defaultMaxRetries)
    }

    @Test
    fun `step planning maps states to steps and back`() {
        assertEquals(AutomationSteps.ANALYSIS, AutomationSteps.stepForState(JobState.ANALYZING))
        assertEquals(AutomationSteps.SEND_OFFER, AutomationSteps.stepForState(JobState.SENDING))
        assertEquals(JobState.SENDING, AutomationSteps.stateForStep(AutomationSteps.SEND_OFFER))
        assertNull(AutomationSteps.stateForStep("NOT_A_STEP"))

        assertEquals(JobState.ANALYZING, AutomationSteps.nextStateAfter(JobState.DISCOVERED))
        assertEquals(JobState.QUALIFYING, AutomationSteps.nextStateAfter(JobState.ANALYZED))
        assertEquals(JobState.OFFER_GENERATION, AutomationSteps.nextStateAfter(JobState.QUALIFIED))
        assertEquals(JobState.VALIDATING_SEND, AutomationSteps.nextStateAfter(JobState.OFFER_READY))
        assertEquals(JobState.RECONCILING, AutomationSteps.nextStateAfter(JobState.SENDING))
        assertEquals(JobState.SENT, AutomationSteps.nextStateAfter(JobState.SENT))
    }
}
