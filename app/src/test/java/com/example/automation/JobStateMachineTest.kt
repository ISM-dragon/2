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

    // ------------------------------------------------------------------ full transition table
    //
    // The complete topology as an explicit data set: every pair (from, to) is asserted, so any
    // future edge that sneaks in without documentation fails this test.

    private val allowedEdges = setOf(
        // forward pipeline
        JobState.DISCOVERED to JobState.ANALYZING,
        JobState.ANALYZING to JobState.ANALYZED,
        JobState.ANALYZED to JobState.QUALIFYING,
        JobState.QUALIFYING to JobState.QUALIFIED,
        JobState.QUALIFYING to JobState.DISQUALIFIED,
        JobState.QUALIFIED to JobState.OFFER_GENERATION,
        JobState.OFFER_GENERATION to JobState.OFFER_READY,
        JobState.OFFER_READY to JobState.VALIDATING_SEND,
        JobState.VALIDATING_SEND to JobState.SENDING,
        JobState.SENDING to JobState.SENT,
        JobState.SENDING to JobState.RECONCILING,
        JobState.RECONCILING to JobState.SENT,

        // evidence-based forward jumps (structural preconditions enforced in transition())
        JobState.QUALIFIED to JobState.OFFER_READY,
        JobState.QUALIFIED to JobState.SENT,
        JobState.OFFER_GENERATION to JobState.SENT,
        JobState.OFFER_READY to JobState.SENT,

        // recovery rewinds (documented continuations of AutomationRecoveryPolicy)
        JobState.ANALYZING to JobState.DISCOVERED,
        JobState.QUALIFYING to JobState.ANALYZED,
        JobState.OFFER_GENERATION to JobState.QUALIFIED,
        JobState.VALIDATING_SEND to JobState.OFFER_READY,
        JobState.VALIDATING_SEND to JobState.QUALIFIED,
        JobState.SENDING to JobState.QUALIFIED,
        JobState.RECONCILING to JobState.OFFER_READY,
        JobState.RECONCILING to JobState.QUALIFIED,

        // failure / parking / cancellation paths
        JobState.DISCOVERED to JobState.BLOCKED,
        JobState.DISCOVERED to JobState.FAILED_RETRYABLE,
        JobState.DISCOVERED to JobState.FAILED_TERMINAL,
        JobState.DISCOVERED to JobState.CANCELLED,
        JobState.ANALYZING to JobState.BLOCKED,
        JobState.ANALYZING to JobState.FAILED_RETRYABLE,
        JobState.ANALYZING to JobState.FAILED_TERMINAL,
        JobState.ANALYZING to JobState.CANCELLED,
        JobState.ANALYZED to JobState.BLOCKED,
        JobState.ANALYZED to JobState.FAILED_RETRYABLE,
        JobState.ANALYZED to JobState.FAILED_TERMINAL,
        JobState.ANALYZED to JobState.CANCELLED,
        JobState.QUALIFYING to JobState.FAILED_RETRYABLE,
        JobState.QUALIFYING to JobState.FAILED_TERMINAL,
        JobState.QUALIFYING to JobState.CANCELLED,
        JobState.QUALIFIED to JobState.BLOCKED,
        JobState.QUALIFIED to JobState.FAILED_RETRYABLE,
        JobState.QUALIFIED to JobState.FAILED_TERMINAL,
        JobState.QUALIFIED to JobState.CANCELLED,
        JobState.DISQUALIFIED to JobState.CANCELLED,
        JobState.OFFER_GENERATION to JobState.BLOCKED,
        JobState.OFFER_GENERATION to JobState.FAILED_RETRYABLE,
        JobState.OFFER_GENERATION to JobState.FAILED_TERMINAL,
        JobState.OFFER_GENERATION to JobState.CANCELLED,
        JobState.OFFER_READY to JobState.BLOCKED,
        JobState.OFFER_READY to JobState.FAILED_RETRYABLE,
        JobState.OFFER_READY to JobState.FAILED_TERMINAL,
        JobState.OFFER_READY to JobState.CANCELLED,
        JobState.VALIDATING_SEND to JobState.BLOCKED,
        JobState.VALIDATING_SEND to JobState.FAILED_RETRYABLE,
        JobState.VALIDATING_SEND to JobState.FAILED_TERMINAL,
        JobState.VALIDATING_SEND to JobState.CANCELLED,
        JobState.SENDING to JobState.FAILED_RETRYABLE,
        JobState.SENDING to JobState.FAILED_TERMINAL,
        JobState.SENDING to JobState.CANCELLED,
        JobState.RECONCILING to JobState.FAILED_RETRYABLE,
        JobState.RECONCILING to JobState.FAILED_TERMINAL,
        JobState.RECONCILING to JobState.CANCELLED,

        // retryable failure can resume anywhere work happens (plus escalate or park)
        JobState.FAILED_RETRYABLE to JobState.DISCOVERED,
        JobState.FAILED_RETRYABLE to JobState.ANALYZING,
        JobState.FAILED_RETRYABLE to JobState.ANALYZED,
        JobState.FAILED_RETRYABLE to JobState.QUALIFYING,
        JobState.FAILED_RETRYABLE to JobState.QUALIFIED,
        JobState.FAILED_RETRYABLE to JobState.OFFER_GENERATION,
        JobState.FAILED_RETRYABLE to JobState.OFFER_READY,
        JobState.FAILED_RETRYABLE to JobState.VALIDATING_SEND,
        JobState.FAILED_RETRYABLE to JobState.SENDING,
        JobState.FAILED_RETRYABLE to JobState.RECONCILING,
        JobState.FAILED_RETRYABLE to JobState.FAILED_RETRYABLE,
        JobState.FAILED_RETRYABLE to JobState.FAILED_TERMINAL,
        JobState.FAILED_RETRYABLE to JobState.BLOCKED,
        JobState.FAILED_RETRYABLE to JobState.CANCELLED,

        // terminal failure and operator parking can still be cancelled / resumed
        JobState.FAILED_TERMINAL to JobState.CANCELLED,
        JobState.BLOCKED to JobState.DISCOVERED,
        JobState.BLOCKED to JobState.ANALYZING,
        JobState.BLOCKED to JobState.ANALYZED,
        JobState.BLOCKED to JobState.QUALIFYING,
        JobState.BLOCKED to JobState.QUALIFIED,
        JobState.BLOCKED to JobState.OFFER_GENERATION,
        JobState.BLOCKED to JobState.OFFER_READY,
        JobState.BLOCKED to JobState.VALIDATING_SEND,
        JobState.BLOCKED to JobState.SENDING,
        JobState.BLOCKED to JobState.RECONCILING,
        JobState.BLOCKED to JobState.FAILED_RETRYABLE,
        JobState.BLOCKED to JobState.FAILED_TERMINAL,
        JobState.BLOCKED to JobState.CANCELLED
    )

    private fun expectedEdge(from: JobState, to: JobState): Boolean {
        if (from == to) return !from.isTerminal
        return (from to to) in allowedEdges
    }

    @Test
    fun `every allowed and forbidden transition is pinned by the transition table`() {
        JobState.entries.forEach { from ->
            JobState.entries.forEach { to ->
                val expected = expectedEdge(from, to)
                assertEquals(
                    "transition table mismatch for $from -> $to",
                    expected,
                    from.canTransitionTo(to)
                )
                assertEquals(
                    expected,
                    JobStateMachine.canTransition(from, to)
                )
            }
        }
    }

    @Test
    fun `terminal states are absorbing - SENT and CANCELLED accept no edge at all`() {
        listOf(JobState.SENT, JobState.CANCELLED).forEach { absorbing ->
            JobState.entries.forEach { to ->
                assertFalse("$absorbing must not transition to $to", absorbing.canTransitionTo(to))
            }
        }
        // DISQUALIFIED and FAILED_TERMINAL are final for the engine; the only edge they keep is
        // the operator burying them as CANCELLED.
        JobState.entries.forEach { to ->
            if (to != JobState.CANCELLED) {
                assertFalse("DISQUALIFIED must not transition to $to", JobState.DISQUALIFIED.canTransitionTo(to))
                assertFalse("FAILED_TERMINAL must not transition to $to", JobState.FAILED_TERMINAL.canTransitionTo(to))
            }
        }
    }

    @Test
    fun `evidence based resumes are legal only with their structural preconditions`() {
        // OFFER_READY is reachable from the states that can legitimately have an offer already
        // persisted - but never without the offer id itself.
        val offerReadyStates = listOf(
            JobState.QUALIFIED,
            JobState.OFFER_GENERATION,
            JobState.RECONCILING,
            JobState.FAILED_RETRYABLE,
            JobState.BLOCKED
        )
        offerReadyStates.forEach { from ->
            assertTrue("$from -> OFFER_READY must be a legal edge", from.canTransitionTo(JobState.OFFER_READY))
            val job = testJob(state = from, offerId = null, lastSuccessful = from)
            val rejected = JobStateMachine.transition(job, JobState.OFFER_READY, now, policy)
            assertFalse("OFFER_READY without a persisted offer id must be rejected from $from", rejected.applied)
            assertTrue(
                "the rejection must name the missing offerId (was: ${rejected.rejectionReason})",
                rejected.rejectionReason?.contains("offerId") == true
            )
            val accepted = JobStateMachine.transition(job, JobState.OFFER_READY, now, policy, offerId = "OFFER-9")
            assertTrue("a persisted offer id unlocks $from -> OFFER_READY", accepted.applied)
            assertEquals("OFFER-9", accepted.job.offerId)
        }
    }

    @Test
    fun `sent entry is only legal with delivery evidence`() {
        val sentStates = listOf(
            JobState.SENDING,
            JobState.RECONCILING,
            JobState.QUALIFIED,
            JobState.OFFER_GENERATION,
            JobState.OFFER_READY
        )
        sentStates.forEach { from ->
            val bare = testJob(state = from, offerId = null, emailMessageId = null)
            val rejected = JobStateMachine.transition(bare, JobState.SENT, now, policy)
            assertFalse("SENT without delivery evidence must be rejected from $from", rejected.applied)

            val withMessage = JobStateMachine.transition(
                testJob(state = from, offerId = null, emailMessageId = null),
                JobState.SENT,
                now,
                policy,
                emailMessageId = "GMAIL-1"
            )
            assertTrue("an email message id is delivery evidence for $from -> SENT", withMessage.applied)
            assertEquals(JobState.SENT, withMessage.job.state())
        }
    }

    @Test
    fun `recovery rewinds replay the earliest unproven step`() {
        val rewinds = listOf(
            JobState.ANALYZING to JobState.DISCOVERED,
            JobState.QUALIFYING to JobState.ANALYZED,
            JobState.OFFER_GENERATION to JobState.QUALIFIED,
            JobState.VALIDATING_SEND to JobState.OFFER_READY,
            JobState.VALIDATING_SEND to JobState.QUALIFIED,
            JobState.SENDING to JobState.QUALIFIED,
            JobState.RECONCILING to JobState.QUALIFIED
        )
        rewinds.forEach { (from, to) ->
            assertTrue("$from -> $to must be a legal recovery rewind", from.canTransitionTo(to))
        }
        // The one rewind that stays forbidden: OFFER_READY proves the offer exists.
        assertFalse(JobState.OFFER_READY.canTransitionTo(JobState.QUALIFIED))
    }

    @Test
    fun `retry exhaustion produces exactly one terminal outcome`() {
        val job = testJob(state = JobState.SENDING, attempts = 2, maxRetries = 3)
        val first = JobStateMachine.transition(
            job = job,
            target = JobState.FAILED_RETRYABLE,
            now = now,
            retryPolicy = policy,
            error = "boom",
            failedStep = AutomationSteps.SEND_OFFER,
            failureKind = FailureKind.RETRYABLE
        )
        assertEquals(JobState.FAILED_TERMINAL, first.job.state())
        assertEquals(3, first.job.attempts)

        // Every further *automatic* attempt to move or fail the terminal row is refused and
        // changes nothing; the only edge left is an operator cancelling the dead job.
        JobState.entries.forEach { target ->
            val again = JobStateMachine.transition(first.job, target, now + 1_000L, policy)
            if (target == JobState.CANCELLED) {
                assertTrue("an operator may bury the dead job as CANCELLED", again.applied)
                assertEquals(JobState.CANCELLED, again.job.state())
            } else {
                assertFalse("terminal job must not move to $target", again.applied)
                assertEquals("the row must be untouched", first.job, again.job)
            }
        }
        val escalatedAgain = JobStateMachine.escalatedOutcome(first.job, now + 2_000L, "again")
        assertFalse(escalatedAgain.applied)
        assertEquals(first.job, escalatedAgain.job)
    }

    @Test
    fun `duplicate delivery of the same transition is an idempotent no-op`() {
        val job = testJob(state = JobState.SENDING, offerId = "OFFER-1")
        val applied = JobStateMachine.transition(job, JobState.SENT, now, policy, emailMessageId = "GMAIL-1")
        assertTrue(applied.applied)

        // The very same transition delivered again (redelivered work request, replayed worker):
        // nothing is applied and the persisted row is returned byte-identical.
        val duplicate = JobStateMachine.transition(applied.job, JobState.SENT, now + 5_000L, policy, emailMessageId = "GMAIL-2")
        assertFalse(duplicate.applied)
        assertEquals(applied.job, duplicate.job)
    }
}
