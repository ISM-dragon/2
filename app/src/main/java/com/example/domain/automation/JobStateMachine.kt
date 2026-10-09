package com.example.domain.automation

import com.example.data.local.entity.AutomationJobEntity
import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.JobState
import kotlin.random.Random

/**
 * How a failure must be treated by the execution system.
 *
 * - [RETRYABLE]: transient (network, IO, DB lock, 5xx). Retried with exponential backoff.
 * - [TERMINAL]: deterministic defect. Never retried automatically.
 * - [BLOCKED]: deterministic *but fixable by the operator* (bad email, missing PDF, Gmail not
 *   connected). Parked in [JobState.BLOCKED] instead of burning retries.
 * - [CANCELLED]: cooperative cancellation, not a failure.
 */
enum class FailureKind {
    RETRYABLE,
    TERMINAL,
    BLOCKED,
    CANCELLED;

    companion object {
        fun fromName(raw: String?): FailureKind? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * Exponential backoff with jitter, plus the crash-loop budget of a single job.
 *
 * `maxRetries` counts *consecutive failed attempts of the current step*; it is reset as soon as a
 * step succeeds, so a job can only ever exhaust its retries while failing the same step.
 */
data class RetryPolicy(
    val baseDelayMs: Long = 30_000L,
    val maxDelayMs: Long = 30 * 60_000L,
    val multiplier: Double = 2.0,
    val jitterFraction: Double = 0.25,
    val defaultMaxRetries: Int = 3,
    val recoveryBudget: Int = 20
) {
    /** Delay before attempt number [attempt] (1-based). Always inside `[0, maxDelayMs]`. */
    fun backoffFor(attempt: Int, random: Random = Random.Default): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        var delay = baseDelayMs.toDouble().coerceAtLeast(0.0)
        repeat(safeAttempt - 1) {
            if (delay < maxDelayMs) delay *= multiplier
        }
        val capped = delay.coerceIn(0.0, maxDelayMs.toDouble().coerceAtLeast(0.0))
        if (jitterFraction <= 0.0) return capped.toLong()
        val jitter = (random.nextDouble() * 2.0 - 1.0) * (capped * jitterFraction)
        return (capped + jitter).toLong().coerceAtLeast(0L)
    }

    companion object {
        val DEFAULT = RetryPolicy()

        fun fromRules(rules: AutomationRuleEntity): RetryPolicy = RetryPolicy(
            baseDelayMs = rules.retryBackoffBaseSeconds.coerceAtLeast(1) * 1000L,
            maxDelayMs = rules.retryBackoffMaxMinutes.coerceAtLeast(1) * 60_000L,
            defaultMaxRetries = rules.maxRetries.coerceAtLeast(1),
            recoveryBudget = rules.maxRecoveryAttempts.coerceAtLeast(1)
        )
    }
}

/**
 * Maps states to the *step* they execute. The step name is persisted in
 * [AutomationJobEntity.failedStep] so a retry can resume exactly where it stopped instead of
 * replaying the whole pipeline.
 */
object AutomationSteps {
    const val ANALYSIS = "ANALYSIS"
    const val QUALIFY = "QUALIFY"
    const val GENERATE_OFFER = "GENERATE_OFFER"
    const val VALIDATE_SEND = "VALIDATE_SEND"
    const val SEND_OFFER = "SEND_OFFER"
    const val RECONCILE_DELIVERY = "RECONCILE_DELIVERY"

    private val STEP_ORDER = listOf(ANALYSIS, QUALIFY, GENERATE_OFFER, VALIDATE_SEND, SEND_OFFER)

    fun stepForState(state: JobState): String? = when (state) {
        JobState.DISCOVERED, JobState.ANALYZING -> ANALYSIS
        JobState.ANALYZED, JobState.QUALIFYING -> QUALIFY
        JobState.QUALIFIED, JobState.OFFER_GENERATION -> GENERATE_OFFER
        JobState.OFFER_READY, JobState.VALIDATING_SEND -> VALIDATE_SEND
        JobState.SENDING -> SEND_OFFER
        JobState.RECONCILING -> RECONCILE_DELIVERY
        else -> null
    }

    fun stateForStep(step: String?): JobState? = when (step) {
        ANALYSIS -> JobState.ANALYZING
        QUALIFY -> JobState.QUALIFYING
        GENERATE_OFFER -> JobState.OFFER_GENERATION
        VALIDATE_SEND -> JobState.VALIDATING_SEND
        SEND_OFFER -> JobState.SENDING
        RECONCILE_DELIVERY -> JobState.RECONCILING
        else -> null
    }

    fun stepIndex(step: String?): Int = if (step == null) -1 else STEP_ORDER.indexOf(step)

    /**
     * "Safely resume from the last successful state": the state that performs the next unverified
     * step after [state]. Used when a job has no [AutomationJobEntity.failedStep] recorded.
     */
    fun nextStateAfter(state: JobState): JobState = when (state) {
        JobState.DISCOVERED -> JobState.ANALYZING
        JobState.ANALYZING -> JobState.ANALYZED
        JobState.ANALYZED -> JobState.QUALIFYING
        JobState.QUALIFYING -> JobState.QUALIFIED
        JobState.QUALIFIED -> JobState.OFFER_GENERATION
        JobState.OFFER_GENERATION -> JobState.OFFER_READY
        JobState.OFFER_READY -> JobState.VALIDATING_SEND
        JobState.VALIDATING_SEND -> JobState.SENDING
        JobState.SENDING -> JobState.RECONCILING
        JobState.RECONCILING -> JobState.SENT
        else -> state
    }
}

/** Result of a state machine transition attempt. */
data class TransitionOutcome(
    val job: AutomationJobEntity,
    val applied: Boolean,
    val rejectionReason: String? = null
)

/**
 * Pure, side-effect free implementation of the job state machine.
 *
 * Responsibilities:
 *  - reject illegal transitions (see [JobState.canTransitionTo] for the full topology),
 *  - treat same-state transitions as idempotent no-ops (nothing is mutated, so a duplicated
 *    worker delivery can never create a second side effect or a second audit record),
 *  - enforce structural preconditions (OFFER_READY requires a persisted offer id; SENT
 *    requires delivery evidence) *before* the state can be entered,
 *  - derive retry semantics (attempts, backoff, terminal escalation),
 *  - maintain the real start/completion timestamps and the last successful milestone,
 *  - keep a recovery counter so a job that keeps crashing cannot loop forever.
 *
 * The engine persists the outcome with a compare-and-swap SQL update, so the *decision* stays
 * testable on the JVM while the *concurrency* stays in the database.
 */
object JobStateMachine {

    fun canTransition(from: JobState, to: JobState): Boolean = from.canTransitionTo(to)

    @Suppress("LongParameterList")
    fun transition(
        job: AutomationJobEntity,
        target: JobState,
        now: Long,
        retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
        error: String? = null,
        failedStep: String? = null,
        failureKind: FailureKind? = null,
        offerId: String? = null,
        emailMessageId: String? = null,
        recipientEmail: String? = null,
        blockageReason: String? = null,
        analysisId: String? = null,
        recovery: Boolean = false,
        random: Random = Random.Default
    ): TransitionOutcome {
        val from = job.state()

        // Idempotent no-op: a duplicated transition never mutates the row, so it can never
        // duplicate a side effect or an audit record. The engine treats `applied = false` with an
        // unchanged row as "nothing to persist, nothing to audit".
        if (from == target) {
            return TransitionOutcome(job, applied = false, rejectionReason = "idempotent no-op: job is already in $target")
        }
        if (!from.canTransitionTo(target)) {
            return TransitionOutcome(job, applied = false, rejectionReason = "illegal transition $from -> $target")
        }
        if (recovery && job.recoveryCount + 1 > retryPolicy.recoveryBudget) {
            return escalatedOutcome(
                job = job,
                now = now,
                reason = "Recovery budget exhausted (${job.recoveryCount}/${retryPolicy.recoveryBudget}); " +
                    "parking job after $from",
                failedStep = failedStep ?: job.failedStep ?: AutomationSteps.stepForState(from)
            )
        }

        val resolvedOfferId = offerId ?: job.offerId
        if (target == JobState.OFFER_READY && resolvedOfferId.isNullOrBlank()) {
            return TransitionOutcome(
                job,
                applied = false,
                rejectionReason = "OFFER_READY requires a persisted offerId (idempotency guard)"
            )
        }
        if (target == JobState.SENT && resolvedOfferId.isNullOrBlank() &&
            emailMessageId.isNullOrBlank() && job.emailMessageId.isNullOrBlank()
        ) {
            return TransitionOutcome(
                job,
                applied = false,
                rejectionReason = "SENT requires delivery evidence (offerId or emailMessageId)"
            )
        }

        // Escalate to a terminal failure once the step has burned all of its attempts.
        val effectiveTarget = if (target == JobState.FAILED_RETRYABLE && job.attempts + 1 >= job.maxRetries) {
            JobState.FAILED_TERMINAL
        } else {
            target
        }

        val isFailureTarget = effectiveTarget.isFailure
        val isBlockedTarget = effectiveTarget == JobState.BLOCKED

        val attempts = when {
            isFailureTarget -> job.attempts + 1
            effectiveTarget.isMilestone -> 0
            else -> job.attempts
        }

        val escalated = effectiveTarget != target
        val resolvedFailureKind = when {
            isFailureTarget && escalated -> FailureKind.TERMINAL
            isFailureTarget -> failureKind
                ?: if (effectiveTarget.isTerminalFailure) FailureKind.TERMINAL else FailureKind.RETRYABLE
            isBlockedTarget -> failureKind ?: FailureKind.BLOCKED
            else -> null
        }

        val nextAttemptAt = if (effectiveTarget == JobState.FAILED_RETRYABLE) {
            now + retryPolicy.backoffFor(job.attempts + 1, random)
        } else {
            0L
        }

        val resolvedError = when {
            isFailureTarget -> error ?: job.lastError
            isBlockedTarget -> error ?: job.lastError
            else -> null // a successful step clears the previous error
        }

        val resolvedBlockage = when {
            isBlockedTarget || effectiveTarget == JobState.DISQUALIFIED ->
                blockageReason ?: job.blockageReason ?: error
            from == JobState.BLOCKED -> null
            else -> job.blockageReason
        }

        val updated = job.copy(
            currentState = effectiveTarget.name,
            lastSuccessfulState = if (effectiveTarget.isMilestone) effectiveTarget.name else job.lastSuccessfulState,
            failedStep = when {
                isFailureTarget || isBlockedTarget -> failedStep ?: job.failedStep ?: AutomationSteps.stepForState(from)
                else -> null
            },
            analysisId = analysisId ?: job.analysisId,
            offerId = resolvedOfferId,
            emailMessageId = emailMessageId ?: job.emailMessageId,
            recipientEmail = recipientEmail ?: job.recipientEmail,
            attempts = attempts,
            lastError = resolvedError,
            failureKind = resolvedFailureKind?.name,
            blockageReason = resolvedBlockage,
            nextAttemptAt = nextAttemptAt,
            leaseOwner = if (effectiveTarget.isTerminal || effectiveTarget.isFailure || isBlockedTarget) null else job.leaseOwner,
            leaseExpiresAt = if (effectiveTarget.isTerminal || effectiveTarget.isFailure || isBlockedTarget) 0L else job.leaseExpiresAt,
            recoveryCount = if (recovery) job.recoveryCount + 1 else job.recoveryCount,
            lastRecoveredAt = if (recovery) now else job.lastRecoveredAt,
            startedAt = job.startedAt ?: if (effectiveTarget.isInFlight || effectiveTarget.isMilestone) now else job.startedAt,
            completedAt = if (effectiveTarget.isTerminal) now else job.completedAt,
            updatedAt = now
        )

        return TransitionOutcome(updated, applied = true)
    }

    /** Convenience for the crash-recovery path: escalate straight to FAILED_TERMINAL. */
    fun escalatedOutcome(
        job: AutomationJobEntity,
        now: Long,
        reason: String,
        failedStep: String? = null
    ): TransitionOutcome {
        if (job.state().isTerminal) {
            return TransitionOutcome(job, applied = false, rejectionReason = "already terminal (${job.state()})")
        }
        val updated = job.copy(
            currentState = JobState.FAILED_TERMINAL.name,
            failedStep = failedStep ?: job.failedStep,
            attempts = job.attempts + 1,
            lastError = reason,
            failureKind = FailureKind.TERMINAL.name,
            nextAttemptAt = 0L,
            leaseOwner = null,
            leaseExpiresAt = 0L,
            completedAt = now,
            updatedAt = now
        )
        return TransitionOutcome(updated, applied = true)
    }
}
