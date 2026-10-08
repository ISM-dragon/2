package com.example.urlintelligence.resolver

import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.PropertyParseResult
import com.example.urlintelligence.adapter.PropertySourceAdapter
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.failure.SourceFailureClassifier
import com.example.urlintelligence.idempotency.IdempotencyKey
import com.example.urlintelligence.idempotency.IdempotencyRecord
import com.example.urlintelligence.idempotency.IdempotencyState
import com.example.urlintelligence.idempotency.IdempotencyStore
import com.example.urlintelligence.idempotency.InMemoryIdempotencyStore
import com.example.urlintelligence.job.ImportJobEvent
import com.example.urlintelligence.job.ImportJobState
import com.example.urlintelligence.job.InMemoryPropertyImportJobStore
import com.example.urlintelligence.job.PropertyImportJob
import com.example.urlintelligence.job.PropertyImportJobStore
import com.example.urlintelligence.job.TransitionResult
import com.example.urlintelligence.model.CanonicalProperty
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.NormalizationOutcome
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.retry.AttemptOutcome
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.retry.RetryExecutor
import com.example.urlintelligence.retry.RetryOutcome
import com.example.urlintelligence.retry.RetryPolicy
import com.example.urlintelligence.retry.Sleeper
import com.example.urlintelligence.source.SourceDetection
import com.example.urlintelligence.source.SourceDetector
import com.example.urlintelligence.source.SourceRegistry
import com.example.urlintelligence.url.NormalizedUrl
import com.example.urlintelligence.url.PropertyUrlValidator
import com.example.urlintelligence.url.SensitiveUrlParameters
import com.example.urlintelligence.url.UrlValidationError
import com.example.urlintelligence.url.UrlValidationResult
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

data class ResolveOptions(
    /** Keep partially resolved properties instead of failing the import. */
    val allowPartial: Boolean = true,
    val maxAttempts: Int? = null,
    val correlationId: String? = null,
    val timeoutMillis: Long = 15_000L,
    /** Non-sensitive headers only; credential headers are stripped defensively. */
    val headers: Map<String, String> = emptyMap(),
    /** Ignore a cached result and re-fetch (still re-reserves the idempotency key). */
    val refresh: Boolean = false,
    /** Enforce the per-source opt-in flag declared on the descriptor. */
    val requireSourceOptIn: Boolean = true
)

/** Outcome of a URL import. Partial success is a first-class, usable result. */
sealed class PropertyImportResult {

    data class Success(
        val property: CanonicalProperty,
        val jobId: String,
        val attempts: Int,
        val durationMillis: Long,
        val warnings: List<String> = emptyList()
    ) : PropertyImportResult()

    data class Partial(
        val property: CanonicalProperty,
        val jobId: String,
        val missingFields: Set<PropertyField>,
        val warnings: List<String> = emptyList(),
        val attempts: Int,
        val durationMillis: Long
    ) : PropertyImportResult()

    data class Failure(
        val failure: SourceFailure,
        val jobId: String?,
        val attempts: Int,
        val durationMillis: Long
    ) : PropertyImportResult()

    val isUsable: Boolean
        get() = this is Success || this is Partial

    val isPartial: Boolean
        get() = this is Partial

    val propertyOrNull: CanonicalProperty?
        get() = when (this) {
            is Success -> property
            is Partial -> property
            is Failure -> null
        }

    val jobIdOrNull: String?
        get() = when (this) {
            is Success -> jobId
            is Partial -> jobId
            is Failure -> jobId
        }

    val attempts: Int
        get() = when (this) {
            is Success -> attempts
            is Partial -> attempts
            is Failure -> attempts
        }
}

/** Cheap, network-free inspection: what is this URL, and can we import it? */
data class UrlInspection(
    val rawUrl: String,
    val isValid: Boolean,
    val normalizedUrl: NormalizedUrl?,
    val error: UrlValidationError?,
    val detection: SourceDetection?,
    val adapterRegistered: Boolean,
    val requiresOptIn: Boolean,
    val importAllowed: Boolean
)

/**
 * The generic property-URL resolver.
 *
 * Pipeline: validate -> canonicalize -> detect source -> dispatch to adapter
 * (with retry + backoff) -> parse -> normalize -> score completeness -> persist job.
 *
 * Guarantees:
 *  * **Idempotent** — the same canonical URL resolved twice performs one fetch;
 *    the second call returns the cached result (or a [SourceFailure.Conflict]
 *    while the first is still in flight).
 *  * **Classified failures** — every error path produces a typed [SourceFailure],
 *    never an exception, so callers can branch on retryability.
 *  * **Partial success** — a listing with missing core fields is returned as
 *    [PropertyImportResult.Partial] rather than dropped, unless the caller opts out.
 *  * **Provenance** — every field on the returned property knows where it came from.
 */
class PropertyUrlResolver(
    private val registry: SourceRegistry,
    private val validator: PropertyUrlValidator = PropertyUrlValidator(),
    private val detector: SourceDetector = SourceDetector(registry.descriptors()),
    private val normalizer: PropertyNormalizer = PropertyNormalizer(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val clock: Clock = Clock.SYSTEM,
    private val sleeper: Sleeper = Sleeper.DEFAULT,
    private val jobStore: PropertyImportJobStore = InMemoryPropertyImportJobStore(),
    private val resultStore: IdempotencyStore<PropertyImportResult> = InMemoryIdempotencyStore(clock),
    /** null = no allow-list (all non-opt-in sources permitted). */
    private val allowedSourceIds: Set<String>? = null,
    private val random: () -> Double = { Random.nextDouble() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {

    private val correlationCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** Network-free inspection used by callers that want to validate before importing. */
    fun inspect(rawUrl: String, requireSourceOptIn: Boolean = true): UrlInspection {
        val validation = validator.validate(rawUrl.trim())
        if (validation is UrlValidationResult.Invalid) {
            return UrlInspection(
                rawUrl = rawUrl,
                isValid = false,
                normalizedUrl = null,
                error = validation.error,
                detection = null,
                adapterRegistered = false,
                requiresOptIn = false,
                importAllowed = false
            )
        }
        val url = (validation as UrlValidationResult.Valid).url
        val detection = detector.detect(url)
        val adapterRegistered = registry.adapterFor(detection.sourceId) != null
        val requiresOptIn = detection.descriptor?.requiresOptIn == true
        val importAllowed = adapterRegistered && (!requireSourceOptIn || !requiresOptIn || isAllowListed(detection.sourceId))
        return UrlInspection(
            rawUrl = rawUrl,
            isValid = true,
            normalizedUrl = url,
            error = null,
            detection = detection,
            adapterRegistered = adapterRegistered,
            requiresOptIn = requiresOptIn,
            importAllowed = importAllowed
        )
    }

    suspend fun resolve(rawUrl: String, options: ResolveOptions = ResolveOptions()): PropertyImportResult {
        val startedAt = clock.now()
        val trimmed = rawUrl.trim()
        val rawKey = IdempotencyKey.forUrl(trimmed.lowercase())
        var job = PropertyImportJob.create(
            rawUrl = sanitizeRawUrlForStorage(trimmed),
            idempotencyKey = rawKey,
            jobId = idGenerator(),
            maxAttempts = options.maxAttempts ?: retryPolicy.maxAttempts,
            now = startedAt
        )
        jobStore.save(job)

        return try {
            resolveInternal(job, trimmed, rawKey, options, startedAt)
        } catch (t: kotlinx.coroutines.CancellationException) {
            // Cancellation is control flow, not a failure: never swallow it.
            jobStore.save(job)
            throw t
        } catch (t: Throwable) {
            val failure = SourceFailureClassifier.fromThrowable(t)
            val failed = failJob(job, ImportJobEvent.FETCH_FAILED, failure, "unexpected error")
            finishFailure(failed, failure, 0, startedAt, listOf(rawKey))
        }
    }

    /** Sequential batch import; each URL keeps its own idempotency and job state. */
    suspend fun resolveAll(
        urls: List<String>,
        options: ResolveOptions = ResolveOptions()
    ): List<PropertyImportResult> = urls.map { resolve(it, options) }

    private suspend fun resolveInternal(
        initialJob: PropertyImportJob,
        trimmedUrl: String,
        rawKey: IdempotencyKey,
        options: ResolveOptions,
        startedAt: Long
    ): PropertyImportResult {
        var job = initialJob
        val keys = ArrayList<IdempotencyKey>().apply { add(rawKey) }

        if (!options.refresh) {
            resultStore.get(rawKey)?.value?.let { cached -> return cached }
        }

        job = advance(job, ImportJobEvent.START_VALIDATION, "validating url")

        val validation = validator.validate(trimmedUrl)
        if (validation is UrlValidationResult.Invalid) {
            val failure = SourceFailure.InvalidUrl("${validation.error.code}: ${validation.error.message}")
            job = failJob(job, ImportJobEvent.VALIDATION_FAILED, failure, validation.error.code)
            return finishFailure(job, failure, 0, startedAt, keys)
        }

        val url = (validation as UrlValidationResult.Valid).url
        job = advance(job, ImportJobEvent.VALIDATION_SUCCEEDED, "canonical=${url.canonical}") {
            it.copy(canonicalUrl = url.canonical)
        }

        val canonicalKey = IdempotencyKey.forUrl(url.canonical)
        if (canonicalKey.value != rawKey.value) {
            keys.add(canonicalKey)
            if (!options.refresh) {
                resultStore.get(canonicalKey)?.value?.let { cached -> return cached }
            }
        }

        if (options.refresh) {
            // A refresh explicitly discards any previous result or reservation.
            keys.forEach { key -> resultStore.release(key) }
        }

        // Reserve the work: a second identical request is a conflict, not a second fetch.
        val now = clock.now()
        if (!resultStore.putIfAbsent(
                canonicalKey,
                IdempotencyRecord(canonicalKey, IdempotencyState.IN_FLIGHT, now, now)
            )
        ) {
            val failure = SourceFailure.Conflict("an import for this url is already in progress")
            job = cancelJob(job, failure, "duplicate in-flight import")
            return finishFailure(job, failure, 0, startedAt, keys)
        }

        val detection = detector.detect(url)
        val descriptor = detection.descriptor
        val adapter = registry.adapterFor(detection.sourceId)

        if (descriptor == null || adapter == null) {
            val failure = SourceFailure.UnsupportedSource(detection.sourceId, url.host)
            job = failJob(job, ImportJobEvent.SOURCE_UNSUPPORTED, failure, detection.reason)
            return finishFailure(job, failure, 0, startedAt, keys)
        }

        if (options.requireSourceOptIn && descriptor.requiresOptIn && !isAllowListed(detection.sourceId)) {
            val failure = SourceFailure.PolicyBlocked(
                sourceId = detection.sourceId,
                rule = "source requires opt-in and is not allow-listed"
            )
            job = failJob(job, ImportJobEvent.POLICY_BLOCKED, failure, "opt-in required")
            return finishFailure(job, failure, 0, startedAt, keys)
        }

        job = advance(job, ImportJobEvent.SOURCE_DETECTED, detection.reason) {
            it.copy(sourceId = detection.sourceId, sourcePropertyId = detection.sourcePropertyId)
        }
        job = advance(job, ImportJobEvent.DISPATCHED, "adapter=${adapter.javaClass.simpleName}")

        val policy = options.maxAttempts?.let { retryPolicy.copy(maxAttempts = it) } ?: retryPolicy
        val executor = RetryExecutor(policy = policy, clock = clock, sleeper = sleeper, random = random)
        val correlationId = options.correlationId
            ?: "${job.jobId}-${correlationCounter.incrementAndGet()}"

        val outcome: RetryOutcome<SourceFetchResponse.Success> = executor.execute(
            onRetry = { attempt, failure, _ ->
                job = advance(job, ImportJobEvent.RETRY_SCHEDULED, failure.toLogString()) {
                    it.copy(attempt = attempt)
                }
                job = advance(job, ImportJobEvent.RETRY_STARTED, "attempt=${attempt + 1}") {
                    it.copy(attempt = attempt + 1)
                }
            }
        ) { attempt ->
            job = job.copy(attempt = attempt)
            val request = SourceFetchRequest(
                requestUrl = url.canonical,
                correlationId = correlationId,
                sourcePropertyId = detection.sourcePropertyId,
                attempt = attempt,
                timeoutMillis = options.timeoutMillis,
                headers = sanitizeHeaders(options.headers)
            )
            when (val response = adapter.fetch(request)) {
                is SourceFetchResponse.Success -> AttemptOutcome.Success(response)
                is SourceFetchResponse.Failure -> AttemptOutcome.Failure(response.failure)
            }
        }

        val response = when (outcome) {
            is RetryOutcome.Success -> outcome.value
            is RetryOutcome.Exhausted -> {
                job = failJob(
                    job,
                    ImportJobEvent.FETCH_FAILED,
                    outcome.failure,
                    "retry exhausted after ${outcome.attempts} attempt(s)"
                )
                return finishFailure(job, outcome.failure, outcome.attempts, startedAt, keys)
            }
        }

        job = advance(job, ImportJobEvent.FETCH_SUCCEEDED, "status=${response.statusCode}")

        val request = SourceFetchRequest(
            requestUrl = url.canonical,
            correlationId = correlationId,
            sourcePropertyId = detection.sourcePropertyId,
            attempt = job.attempt,
            timeoutMillis = options.timeoutMillis,
            headers = sanitizeHeaders(options.headers)
        )

        val warnings = ArrayList<String>()
        val draft = when (val parsed = adapter.parse(response, request)) {
            is PropertyParseResult.Success -> parsed.draft
            is PropertyParseResult.Partial -> {
                warnings.addAll(parsed.warnings)
                parsed.draft
            }
            is PropertyParseResult.Failure -> {
                job = failJob(job, ImportJobEvent.PARSE_FAILED, parsed.failure, "parse failed")
                return finishFailure(job, parsed.failure, outcome.attempts, startedAt, keys)
            }
        }

        job = advance(job, ImportJobEvent.PARSE_SUCCEEDED, "fields=${draft.present().size}")

        val normalized = normalizer.normalize(draft, warnings)
        val property = when (normalized) {
            is NormalizationOutcome.Success -> {
                warnings.clear()
                warnings.addAll(normalized.warnings)
                normalized.property
            }
            is NormalizationOutcome.Failure -> {
                job = failJob(
                    job,
                    ImportJobEvent.NORMALIZE_FAILED,
                    normalized.failure,
                    "normalization produced no usable property"
                )
                return finishFailure(job, normalized.failure, outcome.attempts, startedAt, keys)
            }
        }

        val missing = property.completeness.missingRequired
        val durationMillis = (clock.now() - startedAt).coerceAtLeast(0L)

        if (missing.isNotEmpty() && !options.allowPartial) {
            val failure = SourceFailure.IncompleteData(missing.map { it.stableName })
            job = failJob(job, ImportJobEvent.NORMALIZE_FAILED, failure, "partial data rejected by caller")
            return finishFailure(job, failure, outcome.attempts, startedAt, keys)
        }

        val result: PropertyImportResult = if (missing.isEmpty()) {
            job = advance(
                job,
                ImportJobEvent.NORMALIZE_SUCCEEDED,
                "completeness=${"%.2f".format(property.completeness.score)}"
            )
            PropertyImportResult.Success(
                property = property,
                jobId = job.jobId,
                attempts = outcome.attempts,
                durationMillis = durationMillis,
                warnings = job.warnings + warnings
            )
        } else {
            job = advance(
                job,
                ImportJobEvent.NORMALIZE_PARTIAL,
                "missing=${missing.joinToString { it.stableName }}"
            )
            PropertyImportResult.Partial(
                property = property,
                jobId = job.jobId,
                missingFields = missing,
                warnings = job.warnings + warnings,
                attempts = outcome.attempts,
                durationMillis = durationMillis
            )
        }

        keys.forEach { key -> resultStore.complete(key, result) }
        jobStore.save(job)
        return result
    }

    /** Store a credential-free copy of the pasted URL; validation/fetch still use the original input. */
    private fun sanitizeRawUrlForStorage(rawUrl: String): String {
        val candidate = rawUrl.trim().let { value ->
            if (value.contains("://")) value else "https://$value"
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return "Invalid URL (redacted)"
        val authority = uri.rawAuthority?.substringAfterLast('@') ?: return "Invalid URL (redacted)"
        val safeQuery = uri.rawQuery?.split('&')?.joinToString("&") { parameter ->
            val name = parameter.substringBefore('=')
            if (SensitiveUrlParameters.isSensitive(name)) "$name=REDACTED" else parameter
        }
        return buildString {
            append(uri.scheme?.lowercase(Locale.US) ?: "https")
            append("://").append(authority)
            append(uri.rawPath.orEmpty())
            if (!safeQuery.isNullOrBlank()) append('?').append(safeQuery)
            // Omit fragments; they are not sent over HTTP and may carry OAuth-style tokens.
        }
    }

    private fun isAllowListed(sourceId: String): Boolean =
        allowedSourceIds?.contains(sourceId) ?: false

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { key -> key.lowercase() !in SourceFetchRequest.FORBIDDEN_HEADERS }

    private fun advance(
        job: PropertyImportJob,
        event: ImportJobEvent,
        note: String? = null,
        patch: (PropertyImportJob) -> PropertyImportJob = { it }
    ): PropertyImportJob {
        val result = job.transition(event, clock.now(), note, patch)
        return when (result) {
            is TransitionResult.Accepted -> result.job.also { jobStore.save(it) }
            is TransitionResult.Rejected ->
                throw IllegalStateException("illegal transition: ${result.reason}")
        }
    }

    private fun failJob(
        job: PropertyImportJob,
        event: ImportJobEvent,
        failure: SourceFailure,
        note: String
    ): PropertyImportJob {
        val result = job.transition(event, clock.now(), note) { it.copy(failure = failure) }
        return when (result) {
            is TransitionResult.Accepted -> result.job.also { jobStore.save(it) }
            is TransitionResult.Rejected -> job.copy(failure = failure).also { jobStore.save(it) }
        }
    }

    private fun cancelJob(
        job: PropertyImportJob,
        failure: SourceFailure,
        note: String
    ): PropertyImportJob {
        val result = job.transition(ImportJobEvent.CANCEL, clock.now(), note) {
            it.copy(failure = failure)
        }
        return when (result) {
            is TransitionResult.Accepted -> result.job.also { jobStore.save(it) }
            is TransitionResult.Rejected -> job.copy(failure = failure).also { jobStore.save(it) }
        }
    }

    private fun finishFailure(
        job: PropertyImportJob,
        failure: SourceFailure,
        attempts: Int,
        startedAt: Long,
        keys: List<IdempotencyKey>
    ): PropertyImportResult.Failure {
        // Failures are never cached as results: releasing the reservation lets the
        // caller retry the same URL later with a clean idempotency slot.
        keys.forEach { key -> resultStore.release(key) }
        jobStore.save(job)
        return PropertyImportResult.Failure(
            failure = failure,
            jobId = job.jobId,
            attempts = attempts,
            durationMillis = (clock.now() - startedAt).coerceAtLeast(0L)
        )
    }
}

/** Convenience factory for hosts that just want the default adapter set. */
object PropertyUrlResolverFactory {

    fun create(
        transport: PropertyHttpTransport,
        allowedSourceIds: Set<String> = emptySet(),
        retryPolicy: RetryPolicy = RetryPolicy(),
        clock: Clock = Clock.SYSTEM,
        sleeper: Sleeper = Sleeper.DEFAULT
    ): PropertyUrlResolver {
        val registry = SourceRegistry(
            descriptors = com.example.urlintelligence.source.KnownSources.all(),
            adapters = listOf(
                com.example.urlintelligence.adapter.ZillowAdapter(transport, clock),
                com.example.urlintelligence.adapter.RedfinAdapter(transport, clock),
                com.example.urlintelligence.adapter.RealtorAdapter(transport, clock),
                com.example.urlintelligence.adapter.HomesAdapter(transport, clock),
                com.example.urlintelligence.adapter.GenericWebAdapter(transport, clock)
            )
        )
        return PropertyUrlResolver(
            registry = registry,
            detector = SourceDetector(registry.descriptors()),
            normalizer = PropertyNormalizer(clock),
            retryPolicy = retryPolicy,
            clock = clock,
            sleeper = sleeper,
            allowedSourceIds = allowedSourceIds
        )
    }
}

/** Helper for tests and for callers that only need the state diagram. */
object ImportJobStates {
    fun all(): List<ImportJobState> = ImportJobState.values().toList()
    fun terminal(): List<ImportJobState> = all().filter { it.isTerminal }
}
