package com.example.domain.propertyurl.job

import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.SourceFailure
import java.util.Locale

/**
 * Life cycle of a single URL import.
 *
 * Terminal states never transition again; every state change is appended to
 * [PropertyImportJob.transitions] so an interrupted import can be audited (and resumed) after a
 * process death — the app is a mobile app, process death is the normal case, not the exception.
 */
enum class PropertyImportJobState {
    /** Raw input accepted; validation/resolution pending. */
    RECEIVED,
    /** URL did not validate (syntax, scheme, SSRF guard…). */
    REJECTED_INVALID_URL,
    /** Source recognised but no adapter is wired (see SourceStatus.PLANNED). */
    REJECTED_UNSUPPORTED_SOURCE,
    /** The deployment's fetch policy (robots.txt, allow-list, credentials) refused the fetch. */
    BLOCKED_BY_POLICY,
    /** Resolved + validated; waiting for an attempt (rate limit, backoff or user action). */
    QUEUED,
    FETCHING,
    /** Attempt failed with a retryable failure and a delay was scheduled. */
    FETCH_RETRY_SCHEDULED,
    /** Attempts exhausted or a permanent failure occurred before parsing. */
    FETCH_FAILED,
    PARSING,
    /** Nothing usable could be extracted (layout drift, not a listing page…). */
    PARSE_FAILED,
    NORMALIZING,
    /** Canonical record built but missing identity/essential fields. */
    PARTIAL_SUCCESS,
    SUCCEEDED,
    /** Another job for the same listing produced the result (idempotency). */
    DUPLICATE_SUPPRESSED,
    CANCELLED;

    val isTerminal: Boolean
        get() = when (this) {
            RECEIVED, QUEUED, FETCHING, FETCH_RETRY_SCHEDULED, PARSING, NORMALIZING -> false
            else -> true
        }

    val isSuccess: Boolean get() = this == SUCCEEDED || this == PARTIAL_SUCCESS

    val isFailure: Boolean
        get() = this == REJECTED_INVALID_URL || this == REJECTED_UNSUPPORTED_SOURCE ||
            this == BLOCKED_BY_POLICY || this == FETCH_FAILED || this == PARSE_FAILED

    val isInProgress: Boolean get() = !isTerminal
}

/** Coarse status for UI/badges, derived from the fine-grained job state. */
enum class PropertyImportStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    PARTIAL,
    REJECTED,
    FAILED,
    DUPLICATE,
    CANCELLED;

    companion object {
        fun of(state: PropertyImportJobState): PropertyImportStatus = when (state) {
            PropertyImportJobState.RECEIVED, PropertyImportJobState.QUEUED -> PENDING
            PropertyImportJobState.FETCHING, PropertyImportJobState.FETCH_RETRY_SCHEDULED,
            PropertyImportJobState.PARSING, PropertyImportJobState.NORMALIZING -> IN_PROGRESS
            PropertyImportJobState.SUCCEEDED -> SUCCEEDED
            PropertyImportJobState.PARTIAL_SUCCESS -> PARTIAL
            PropertyImportJobState.REJECTED_INVALID_URL, PropertyImportJobState.REJECTED_UNSUPPORTED_SOURCE,
            PropertyImportJobState.BLOCKED_BY_POLICY -> REJECTED
            PropertyImportJobState.FETCH_FAILED, PropertyImportJobState.PARSE_FAILED -> FAILED
            PropertyImportJobState.DUPLICATE_SUPPRESSED -> DUPLICATE
            PropertyImportJobState.CANCELLED -> CANCELLED
        }
    }
}

/** Append-only audit record of a single state change. */
data class JobStateTransition(
    val from: PropertyImportJobState,
    val to: PropertyImportJobState,
    val atEpochMillis: Long,
    val reasonCode: String,
    val detail: String? = null
) {
    fun describe(): String = buildString {
        append(from.name).append(" → ").append(to.name).append(" (").append(reasonCode).append(')')
        if (detail != null) append(": ").append(detail)
    }
}

/** One import job: the durable unit of work, retry and idempotency. */
data class PropertyImportJob(
    val jobId: String,
    val rawInput: String,
    val normalizedUrl: String? = null,
    val sourceId: String? = null,
    val adapterId: String? = null,
    val externalListingId: String? = null,
    val idempotencyKey: String,
    val state: PropertyImportJobState = PropertyImportJobState.RECEIVED,
    val attempts: Int = 0,
    val maxAttempts: Int = 3,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val nextAttemptAtEpochMillis: Long? = null,
    val lastFailure: SourceFailure? = null,
    val failures: List<SourceFailure> = emptyList(),
    val warnings: List<ImportWarning> = emptyList(),
    val canonicalProperty: CanonicalProperty? = null,
    /** Set on `DUPLICATE_SUPPRESSED` jobs: the job whose result was reused. */
    val duplicateOfJobId: String? = null,
    val usedParsers: List<String> = emptyList(),
    val requestId: String = "",
    val transitions: List<JobStateTransition> = emptyList()
) {
    val status: PropertyImportStatus get() = PropertyImportStatus.of(state)

    val isTerminal: Boolean get() = state.isTerminal

    val canRetry: Boolean get() = attempts < maxAttempts && !state.isTerminal

    fun isDue(nowEpochMillis: Long): Boolean =
        nextAttemptAtEpochMillis == null || nowEpochMillis >= nextAttemptAtEpochMillis

    fun attemptCount(): Int = attempts

    fun withTransition(
        to: PropertyImportJobState,
        reasonCode: String,
        atEpochMillis: Long,
        detail: String? = null
    ): PropertyImportJob = copy(
        state = to,
        updatedAtEpochMillis = atEpochMillis,
        transitions = transitions + JobStateTransition(state, to, atEpochMillis, reasonCode, detail)
    )

    fun describe(): String = buildString {
        append(jobId)
        append(" [").append(state.name)
        append(", attempts=").append(attempts).append('/').append(maxAttempts)
        if (sourceId != null) append(", source=").append(sourceId)
        append("] ")
        append(normalizedUrl ?: rawInput.take(60))
    }

    /** Short label used in logs; never contains credentials (URLs are normalized and fragment-free). */
    fun toLogLine(): String = describe()

    fun latestFailureSummary(): String? = lastFailure?.let { "${it.kind.name.lowercase(Locale.US)}: ${it.message}" }
}
