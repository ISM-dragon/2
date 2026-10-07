package com.example.domain.propertyurl.pipeline

import com.example.domain.propertyurl.job.PropertyImportJob
import com.example.domain.propertyurl.job.PropertyImportStatus
import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureCategory
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.port.FetchOptions
import com.example.domain.propertyurl.url.UrlResolutionResult

/** Tuning knobs of the layer; defaults are the production values. */
data class PropertyImportOptions(
    /** Unknown hosts are still imported through the generic adapter (partial record). */
    val allowGenericFallback: Boolean = true,
    /** Sources flagged PLANNED are rejected unless this is on (they have no adapter). */
    val allowPlannedSources: Boolean = false,
    /** Build a canonical record even when identity/essential fields are missing. */
    val allowPartialSuccess: Boolean = true,
    val maxConcurrentImports: Int = 2,
    val maxFetchAttempts: Int = 3,
    val respectRateLimiter: Boolean = true,
    /** Longest rate-limit wait performed inline; anything longer defers the job. */
    val maxInlineRateLimitWaitMillis: Long = 5_000,
    /** Total time budget for one import() call, retries included. */
    val maxTotalImportMillis: Long = 90_000,
    /** In-flight jobs older than this are considered interrupt by process death. */
    val interruptedJobThresholdMillis: Long = 15 * 60_000,
    val fetchOptions: FetchOptions = FetchOptions(),
    /** Persist a `DUPLICATE_SUPPRESSED` row per repeated import (audit trail). */
    val recordSuppressedDuplicates: Boolean = true
)

/** Per-call options. */
data class ImportRequest(
    val requestId: String = "",
    /** Ignore cached/previous results and fetch again (still recorded for audit). */
    val forceRefresh: Boolean = false,
    /** Validate + resolve + policy check only; performs no network call and persists nothing. */
    val dryRun: Boolean = false,
    /** Overrides [PropertyImportOptions.maxFetchAttempts] for this call. */
    val maxAttempts: Int? = null,
    /** Skip the rate limiter/health tracker (used by back-office tooling). */
    val skipRateLimits: Boolean = false
) {
    companion object {
        val DEFAULT = ImportRequest()
        val FORCE_REFRESH = ImportRequest(forceRefresh = true)
        val PREVIEW = ImportRequest(dryRun = true)
    }
}

/** Everything the layer can return for one input. Partial success is a first-class outcome. */
sealed interface ImportOutcome {

    val job: PropertyImportJob

    val status: PropertyImportStatus get() = job.status

    val property: CanonicalProperty? get() = job.canonicalProperty

    val failure: SourceFailure? get() = job.lastFailure

    /** Full canonical record with identity + essential fields present. */
    data class Success(override val job: PropertyImportJob) : ImportOutcome

    /** Canonical record built, but it is missing identity/essential fields. */
    data class Partial(override val job: PropertyImportJob) : ImportOutcome {
        val missingFields: Set<PropertyField> get() = job.canonicalProperty?.missingCriticalFields() ?: emptySet()
    }

    /** A previous import for the same listing was reused (see [reusedJobId]). */
    data class Duplicate(override val job: PropertyImportJob, val reusedJobId: String) : ImportOutcome

    /** The input was refused before any fetch (invalid URL, unsupported source, policy). */
    data class Rejected(override val job: PropertyImportJob) : ImportOutcome

    /** Fetch/parse failed; retrying may help (see [job.nextAttemptAtEpochMillis]). */
    data class Failed(override val job: PropertyImportJob) : ImportOutcome

    /** The job was scheduled for a later attempt instead of finishing inside this call. */
    data class Deferred(override val job: PropertyImportJob, val nextAttemptAtEpochMillis: Long) : ImportOutcome

    data class Cancelled(override val job: PropertyImportJob) : ImportOutcome

    val isSuccess: Boolean get() = this is Success || this is Partial

    val isTerminal: Boolean get() = this !is Deferred

    fun propertyOrNull(): CanonicalProperty? = job.canonicalProperty

    fun describe(): String = when (this) {
        is Success -> "SUCCESS ${job.jobId} → ${job.canonicalProperty?.formattedAddress() ?: job.normalizedUrl}"
        is Partial -> "PARTIAL ${job.jobId} (missing ${missingFields.joinToString(",") { it.name.lowercase() }})"
        is Duplicate -> "DUPLICATE ${job.jobId} of $reusedJobId"
        is Rejected -> "REJECTED ${job.jobId}: ${job.lastFailure?.kind?.name}"
        is Failed -> "FAILED ${job.jobId}: ${job.lastFailure?.kind?.name}"
        is Deferred -> "DEFERRED ${job.jobId} until $nextAttemptAtEpochMillis"
        is Cancelled -> "CANCELLED ${job.jobId}"
    }
}

/** Aggregated counters for a batch import — the shape a dashboard wants. */
data class ImportSummary(
    val total: Int,
    val succeeded: Int,
    val partial: Int,
    val duplicates: Int,
    val rejected: Int,
    val failed: Int,
    val deferred: Int,
    val cancelled: Int,
    val failuresByKind: Map<SourceFailureKind, Int>,
    val failuresByCategory: Map<SourceFailureCategory, Int>,
    val sourcesTouched: Set<String>,
    val partialFieldHistogram: Map<PropertyField, Int>
) {
    val hasFailures: Boolean get() = failed > 0 || rejected > 0

    val successfulOrPartial: Int get() = succeeded + partial

    val successRate: Double
        get() = if (total == 0) 0.0 else (succeeded + partial).toDouble() / total.toDouble()

    val dominantFailure: SourceFailureKind?
        get() = failuresByKind.maxByOrNull { it.value }?.key

    companion object {
        fun of(outcomes: List<ImportOutcome>): ImportSummary {
            val failuresByKind = LinkedHashMap<SourceFailureKind, Int>()
            val failuresByCategory = LinkedHashMap<SourceFailureCategory, Int>()
            val partialHistogram = LinkedHashMap<PropertyField, Int>()

            outcomes.forEach { outcome ->
                outcome.failure?.let { failure ->
                    failuresByKind[failure.kind] = (failuresByKind[failure.kind] ?: 0) + 1
                    failuresByCategory[failure.category] = (failuresByCategory[failure.category] ?: 0) + 1
                }
                if (outcome is ImportOutcome.Partial) {
                    outcome.missingFields.forEach { field ->
                        partialHistogram[field] = (partialHistogram[field] ?: 0) + 1
                    }
                }
            }

            return ImportSummary(
                total = outcomes.size,
                succeeded = outcomes.count { it is ImportOutcome.Success },
                partial = outcomes.count { it is ImportOutcome.Partial },
                duplicates = outcomes.count { it is ImportOutcome.Duplicate },
                rejected = outcomes.count { it is ImportOutcome.Rejected },
                failed = outcomes.count { it is ImportOutcome.Failed },
                deferred = outcomes.count { it is ImportOutcome.Deferred },
                cancelled = outcomes.count { it is ImportOutcome.Cancelled },
                failuresByKind = failuresByKind,
                failuresByCategory = failuresByCategory,
                sourcesTouched = outcomes.mapNotNull { it.job.sourceId }.toSet(),
                partialFieldHistogram = partialHistogram
            )
        }
    }
}

/** Result of importing a list of URLs/messages: one outcome per input, never all-or-nothing. */
data class ImportBatchReport(
    val requestId: String,
    val outcomes: List<ImportOutcome>,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long
) {
    val summary: ImportSummary get() = ImportSummary.of(outcomes)

    val durationMillis: Long get() = finishedAtEpochMillis - startedAtEpochMillis

    fun properties(): List<CanonicalProperty> = outcomes.mapNotNull { it.property }

    fun failures(): List<SourceFailure> = outcomes.mapNotNull { it.failure }
}

/** Offline preview of an input: validation + source detection, no network, no persistence. */
data class UrlInspection(
    val rawInput: String,
    val resolution: UrlResolutionResult,
    val sourceId: String?,
    val sourceDisplayName: String?,
    val isSupported: Boolean,
    val requiresCredentials: Boolean,
    val normalizedUrl: String?,
    val externalListingId: String?,
    val notes: List<String>
) {
    val isResolved: Boolean get() = resolution is UrlResolutionResult.Resolved

    val rejectionSummary: String?
        get() = (resolution as? UrlResolutionResult.Rejected)?.summary

    fun userFacingSummary(): String = when {
        resolution is UrlResolutionResult.Ambiguous ->
            "Several listing links found (${resolution.candidates.size}); import them one by one."
        resolution is UrlResolutionResult.Rejected -> rejectionSummary ?: "This link cannot be imported."
        !isSupported -> "$sourceDisplayName is recognised but not supported yet."
        requiresCredentials -> "This source needs credentials configured before importing."
        else -> "Ready to import from ${sourceDisplayName ?: "the web"}."
    }
}
