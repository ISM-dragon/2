package com.example.domain.propertyurl.job

import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.SourceFailure

/**
 * Explicit state machine for [PropertyImportJob].
 *
 * The transition table is the single source of truth for "what may happen next"; every mutation of a
 * job goes through [transition], which:
 *  - rejects illegal transitions with a typed result instead of corrupting job state,
 *  - timestamps the transition and appends it to the audit trail,
 *  - keeps attempts/next-attempt/failures/warnings/property consistent with the new state.
 */
object PropertyImportStateMachine {

    /** States an interrupted (process death) job can be resumed from. */
    val RESUMABLE_STATES: Set<PropertyImportJobState> = setOf(
        PropertyImportJobState.RECEIVED,
        PropertyImportJobState.QUEUED,
        PropertyImportJobState.FETCHING,
        PropertyImportJobState.FETCH_RETRY_SCHEDULED,
        PropertyImportJobState.PARSING,
        PropertyImportJobState.NORMALIZING
    )

    private val ALLOWED: Map<PropertyImportJobState, Set<PropertyImportJobState>> = mapOf(
        PropertyImportJobState.RECEIVED to setOf(
            PropertyImportJobState.QUEUED,
            PropertyImportJobState.REJECTED_INVALID_URL,
            PropertyImportJobState.REJECTED_UNSUPPORTED_SOURCE,
            PropertyImportJobState.BLOCKED_BY_POLICY,
            PropertyImportJobState.DUPLICATE_SUPPRESSED,
            PropertyImportJobState.CANCELLED
        ),
        PropertyImportJobState.QUEUED to setOf(
            PropertyImportJobState.FETCHING,
            PropertyImportJobState.BLOCKED_BY_POLICY,
            PropertyImportJobState.CANCELLED,
            // The breaker/policy can refuse an attempt before it ever starts, and a long rate-limit
            // wait defers the job: both are legal outcomes of a queued job.
            PropertyImportJobState.FETCH_RETRY_SCHEDULED,
            PropertyImportJobState.FETCH_FAILED
        ),
        PropertyImportJobState.FETCHING to setOf(
            PropertyImportJobState.PARSING,
            PropertyImportJobState.FETCH_RETRY_SCHEDULED,
            PropertyImportJobState.FETCH_FAILED,
            PropertyImportJobState.BLOCKED_BY_POLICY,
            // Recovery edge: the process died mid-attempt, so the attempt is rewound on restart.
            PropertyImportJobState.QUEUED,
            PropertyImportJobState.CANCELLED
        ),
        PropertyImportJobState.FETCH_RETRY_SCHEDULED to setOf(
            PropertyImportJobState.QUEUED,
            PropertyImportJobState.FETCH_FAILED,
            PropertyImportJobState.CANCELLED
        ),
        PropertyImportJobState.PARSING to setOf(
            PropertyImportJobState.NORMALIZING,
            PropertyImportJobState.PARSE_FAILED,
            // Recovery edge (process death while parsing).
            PropertyImportJobState.QUEUED,
            PropertyImportJobState.CANCELLED
        ),
        PropertyImportJobState.NORMALIZING to setOf(
            PropertyImportJobState.SUCCEEDED,
            PropertyImportJobState.PARTIAL_SUCCESS,
            PropertyImportJobState.PARSE_FAILED,
            // Recovery edge (process death while normalizing).
            PropertyImportJobState.QUEUED,
            PropertyImportJobState.CANCELLED
        ),
        PropertyImportJobState.SUCCEEDED to emptySet(),
        PropertyImportJobState.PARTIAL_SUCCESS to emptySet(),
        PropertyImportJobState.FETCH_FAILED to emptySet(),
        PropertyImportJobState.PARSE_FAILED to emptySet(),
        PropertyImportJobState.REJECTED_INVALID_URL to emptySet(),
        PropertyImportJobState.REJECTED_UNSUPPORTED_SOURCE to emptySet(),
        PropertyImportJobState.BLOCKED_BY_POLICY to emptySet(),
        PropertyImportJobState.DUPLICATE_SUPPRESSED to emptySet(),
        PropertyImportJobState.CANCELLED to emptySet()
    )

    fun canTransition(from: PropertyImportJobState, to: PropertyImportJobState): Boolean =
        from == to || ALLOWED[from]?.contains(to) == true

    fun allowedTargets(from: PropertyImportJobState): Set<PropertyImportJobState> = ALLOWED[from] ?: emptySet()

    /**
     * Applies a transition, or explains why it was refused.
     *
     * @param failure recorded in the job's failure list (and as `lastFailure`) when present.
     * @param attemptIncrement increments the attempt counter (fetch attempts only).
     */
    fun transition(
        job: PropertyImportJob,
        target: PropertyImportJobState,
        reasonCode: String,
        atEpochMillis: Long,
        failure: SourceFailure? = null,
        attemptIncrement: Int = 0,
        nextAttemptAtEpochMillis: Long? = null,
        canonicalProperty: CanonicalProperty? = null,
        warnings: List<ImportWarning> = emptyList(),
        usedParsers: List<String> = emptyList(),
        detail: String? = null
    ): TransitionResult {
        if (!canTransition(job.state, target)) {
            return TransitionResult.Illegal(job.state, target, "transition not allowed by the state machine")
        }

        val updated = job.copy(
            attempts = job.attempts + attemptIncrement,
            lastFailure = failure ?: job.lastFailure,
            failures = if (failure != null) job.failures + failure else job.failures,
            nextAttemptAtEpochMillis = when (target) {
                PropertyImportJobState.FETCH_RETRY_SCHEDULED -> nextAttemptAtEpochMillis
                PropertyImportJobState.QUEUED -> nextAttemptAtEpochMillis ?: job.nextAttemptAtEpochMillis
                else -> null
            },
            warnings = (job.warnings + warnings).distinct(),
            canonicalProperty = canonicalProperty ?: job.canonicalProperty,
            usedParsers = if (usedParsers.isNotEmpty()) usedParsers else job.usedParsers
        ).withTransition(target, reasonCode, atEpochMillis, detail ?: failure?.kind?.name)

        return TransitionResult.Applied(updated)
    }

    /** Convenience for tests and for internal call sites that treat an illegal transition as a bug. */
    fun transitionOrThrow(
        job: PropertyImportJob,
        target: PropertyImportJobState,
        reasonCode: String,
        atEpochMillis: Long,
        failure: SourceFailure? = null,
        attemptIncrement: Int = 0,
        nextAttemptAtEpochMillis: Long? = null,
        canonicalProperty: CanonicalProperty? = null,
        warnings: List<ImportWarning> = emptyList(),
        usedParsers: List<String> = emptyList()
    ): PropertyImportJob = when (
        val result = transition(
            job, target, reasonCode, atEpochMillis, failure, attemptIncrement,
            nextAttemptAtEpochMillis, canonicalProperty, warnings, usedParsers
        )
    ) {
        is TransitionResult.Applied -> result.job
        is TransitionResult.Illegal -> throw IllegalStateException(
            "Illegal job transition ${result.from} → ${result.to}: ${result.reason} (job ${job.jobId})"
        )
    }
}

sealed interface TransitionResult {

    data class Applied(val job: PropertyImportJob) : TransitionResult

    data class Illegal(
        val from: PropertyImportJobState,
        val to: PropertyImportJobState,
        val reason: String
    ) : TransitionResult
}

/**
 * Convenience accessor for call sites where an illegal transition can only be a programming error.
 * Pipeline code that must *tolerate* an illegal transition (recovery paths) matches on the sealed
 * type explicitly instead.
 */
val TransitionResult.applied: PropertyImportJob
    get() = when (this) {
        is TransitionResult.Applied -> job
        is TransitionResult.Illegal ->
            throw IllegalStateException("Illegal job transition $from → $to: $reason")
    }
