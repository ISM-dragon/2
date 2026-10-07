package com.example.domain.propertyurl.pipeline

import com.example.domain.propertyurl.adapter.AdapterContext
import com.example.domain.propertyurl.adapter.PropertyAdapterRegistry
import com.example.domain.propertyurl.adapter.PropertySourceAdapter
import com.example.domain.propertyurl.adapter.SourceFetchOutcome
import com.example.domain.propertyurl.job.IdempotencyKeys
import com.example.domain.propertyurl.job.PropertyImportJob
import com.example.domain.propertyurl.job.PropertyImportJobState
import com.example.domain.propertyurl.job.PropertyImportJobStore
import com.example.domain.propertyurl.job.PropertyImportStateMachine
import com.example.domain.propertyurl.job.PropertyImportStateMachine.RESUMABLE_STATES
import com.example.domain.propertyurl.job.RetryDecision
import com.example.domain.propertyurl.job.RetryPolicy
import com.example.domain.propertyurl.job.TransitionResult
import com.example.domain.propertyurl.job.applied
import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureClassifier
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.normalize.CanonicalPropertyMapper
import com.example.domain.propertyurl.parse.ParseOutcome
import com.example.domain.propertyurl.parse.ParserChain
import com.example.domain.propertyurl.port.Clock
import com.example.domain.propertyurl.port.CredentialProvider
import com.example.domain.propertyurl.port.FetchPolicy
import com.example.domain.propertyurl.port.FetchPolicyDecision
import com.example.domain.propertyurl.port.HttpFetcher
import com.example.domain.propertyurl.port.IdGenerator
import com.example.domain.propertyurl.port.Sleeper
import com.example.domain.propertyurl.port.TelemetryEvent
import com.example.domain.propertyurl.port.TelemetrySink
import com.example.domain.propertyurl.source.SourceRegistry
import com.example.domain.propertyurl.source.SourceStatus
import com.example.domain.propertyurl.url.PropertyUrlResolver
import com.example.domain.propertyurl.url.ResolvedPropertyUrl
import com.example.domain.propertyurl.url.UrlResolutionResult
import com.example.domain.propertyurl.util.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Infrastructure ports and policies of the layer (grouped to keep the constructor readable). */
data class PropertyIntelEnvironment(
    val clock: Clock,
    val idGenerator: IdGenerator,
    val httpFetcher: HttpFetcher,
    val sleeper: Sleeper,
    val telemetry: TelemetrySink,
    val credentialProvider: CredentialProvider,
    val fetchPolicy: FetchPolicy,
    val retryPolicy: RetryPolicy,
    val idempotencyPolicy: com.example.domain.propertyurl.job.IdempotencyPolicy,
    val options: PropertyImportOptions = PropertyImportOptions(),
    val rateLimiter: SourceRateLimiter? = null,
    val healthTracker: SourceHealthTracker? = null
)

/**
 * Property URL Intelligence — the orchestrator.
 *
 * One entry point that turns a pasted link (or a share text containing one) into a canonical,
 * provenance-carrying property record:
 *
 * ```
 * validate → detect source → policy → idempotency → fetch (retry/backoff/circuit breaker)
 *          → parse (parser chain) → normalize (canonical + provenance) → persist job
 * ```
 *
 * Guarantees the rest of the app relies on:
 *  - **never throws for expected conditions**; everything becomes a typed [ImportOutcome],
 *  - **partial success is explicit** (`Partial` with the missing fields listed),
 *  - **idempotent**: the same listing imported twice reuses the previous result (`Duplicate`),
 *  - **crash safe**: job state is persisted at every transition, so imports resume after process death,
 *  - **compliant**: every fetch passes the injected [FetchPolicy] (robots.txt/allow-lists),
 *  - **quiet**: sources are rate limited and circuit-broken before they block the deployment.
 */
class PropertyUrlIntelligence(
    private val registry: SourceRegistry,
    private val adapters: PropertyAdapterRegistry,
    private val resolver: PropertyUrlResolver,
    private val mapper: CanonicalPropertyMapper,
    private val jobStore: PropertyImportJobStore,
    private val parserChain: ParserChain,
    private val failureClassifier: SourceFailureClassifier,
    private val environment: PropertyIntelEnvironment
) {

    private val clock = environment.clock
    private val options = environment.options

    // --- Public API -----------------------------------------------------------------------

    /** Imports a single URL/message. Never throws for expected failures; returns a typed outcome. */
    suspend fun import(rawInput: String, request: ImportRequest = ImportRequest()): ImportOutcome {
        val requestId = request.requestId.ifEmpty { environment.idGenerator.newId("req") }
        val now = clock.nowEpochMillis()

        // 1. Resolve + validate (pure).
        val resolution = resolver.resolve(rawInput)
        when (resolution) {
            is UrlResolutionResult.Rejected -> {
                val failure = failureClassifier.invalidUrl(resolution.summary, Redaction.url(rawInput.take(512)))
                return reject(
                    rawInput = rawInput,
                    requestId = requestId,
                    idempotencyKey = IdempotencyKeys.forDocument(rawInput.take(512)),
                    state = PropertyImportJobState.REJECTED_INVALID_URL,
                    reasonCode = "INVALID_URL",
                    failure = failure,
                    persist = !request.dryRun,
                    now = now
                )
            }
            is UrlResolutionResult.Ambiguous -> {
                val failure = failureClassifier.multipleUrls(resolution.candidates.size)
                return reject(
                    rawInput = rawInput,
                    requestId = requestId,
                    idempotencyKey = IdempotencyKeys.forDocument(rawInput.take(512)),
                    state = PropertyImportJobState.REJECTED_INVALID_URL,
                    reasonCode = "MULTIPLE_URLS",
                    failure = failure,
                    persist = !request.dryRun,
                    now = now
                )
            }
            is UrlResolutionResult.Resolved -> Unit
        }

        val resolved = (resolution as UrlResolutionResult.Resolved).resolved
        val idempotencyKey = IdempotencyKeys.forResolved(resolved)
        val definition = resolved.detection.definition

        // 2. Static source checks (no network).
        val staticRejection = checkSourceSupport(resolved)
        if (staticRejection != null) {
            return reject(
                rawInput = rawInput,
                requestId = requestId,
                idempotencyKey = idempotencyKey,
                state = PropertyImportJobState.REJECTED_UNSUPPORTED_SOURCE,
                reasonCode = "SOURCE_NOT_SUPPORTED",
                failure = staticRejection,
                persist = !request.dryRun,
                now = now,
                resolved = resolved
            )
        }

        val adapter = adapters.forResolved(resolved) ?: run {
            val failure = failureClassifier.notSupported(
                sourceId = definition?.sourceId ?: "unknown",
                displayName = definition?.displayName ?: "This website",
                url = resolved.url.redacted()
            )
            return reject(
                rawInput = rawInput,
                requestId = requestId,
                idempotencyKey = idempotencyKey,
                state = PropertyImportJobState.REJECTED_UNSUPPORTED_SOURCE,
                reasonCode = "NO_ADAPTER",
                failure = failure,
                persist = !request.dryRun,
                now = now,
                resolved = resolved
            )
        }

        // 3. Compliance gate (may perform a robots.txt fetch, cached per origin).
        val policyDecision = environment.fetchPolicy.check(resolved.url, definition)
        if (policyDecision is FetchPolicyDecision.Denied) {
            val failure = failureClassifier.policyDisallowed(
                reason = policyDecision.reason,
                sourceId = definition?.sourceId,
                url = resolved.url.redacted()
            )
            return reject(
                rawInput = rawInput,
                requestId = requestId,
                idempotencyKey = idempotencyKey,
                state = PropertyImportJobState.BLOCKED_BY_POLICY,
                reasonCode = policyDecision.rule?.uppercase(Locale.US) ?: "POLICY_DENIED",
                failure = failure,
                persist = !request.dryRun,
                now = now,
                resolved = resolved
            )
        }

        // 4. Idempotency: reuse a recent result for the same listing.
        if (!request.forceRefresh) {
            val previous = jobStore.findLatestByKey(idempotencyKey)
            val reusable = environment.idempotencyPolicy.findReusable(previous, idempotencyKey, now, forceRefresh = false)
            if (reusable != null && reusable.canonicalProperty != null) {
                return suppressDuplicate(reusable, rawInput, requestId, idempotencyKey, resolved, request, now)
            }
        }

        // 5. Dry run: everything above was pure/statically checked — stop before fetching.
        if (request.dryRun) {
            val previewJob = newJob(
                rawInput = rawInput,
                requestId = requestId,
                idempotencyKey = idempotencyKey,
                resolved = resolved,
                adapterId = adapter.descriptor.adapterId,
                now = now
            ).copy(
                warnings = listOf(
                    ImportWarning(
                        code = ImportWarning.WarningCode.DRY_RUN_NO_FETCH,
                        message = "Preview only: no request was performed",
                        sourceId = definition?.sourceId
                    )
                )
            )
            return ImportOutcome.Deferred(previewJob, now)
        }

        // 6. Real work.
        val job = newJob(
            rawInput = rawInput,
            requestId = requestId,
            idempotencyKey = idempotencyKey,
            resolved = resolved,
            adapterId = adapter.descriptor.adapterId,
            now = now
        ).let { created ->
            persisted(created).let { persistedJob ->
                transition(persistedJob, PropertyImportJobState.QUEUED, "RESOLVED", now).applied
            }
        }

        return execute(job, resolved, adapter.descriptor.adapterId, adapter, request)
    }

    /** Imports many inputs with bounded concurrency; a failing item never aborts the batch. */
    suspend fun importMany(
        inputs: List<String>,
        request: ImportRequest = ImportRequest()
    ): ImportBatchReport = coroutineScope {
        val startedAt = clock.nowEpochMillis()
        val requestId = request.requestId.ifEmpty { environment.idGenerator.newId("batch") }
        val semaphore = Semaphore(options.maxConcurrentImports.coerceAtLeast(1))

        val outcomes = inputs.map { input ->
            async {
                semaphore.withPermit {
                    try {
                        import(input, request.copy(requestId = requestId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Defensive: a bug must not take down the whole batch.
                        failedOutcome(input, requestId, e)
                    }
                }
            }
        }.awaitAll()

        val finishedAt = clock.nowEpochMillis()
        val report = ImportBatchReport(requestId, outcomes, startedAt, finishedAt)
        environment.telemetry.record(
            TelemetryEvent(
                name = "import.batch.finished",
                requestId = requestId,
                durationMillis = report.durationMillis,
                attributes = mapOf(
                    "total" to report.summary.total.toString(),
                    "succeeded" to report.summary.succeeded.toString(),
                    "partial" to report.summary.partial.toString(),
                    "failed" to report.summary.failed.toString(),
                    "rejected" to report.summary.rejected.toString(),
                    "duplicates" to report.summary.duplicates.toString()
                )
            )
        )
        report
    }

    /**
     * Resumes jobs that were interrupted by process death (the normal case on mobile) and jobs that
     * were deferred by a cool-down. Safe to call on every app start.
     */
    suspend fun resumeInterruptedJobs(request: ImportRequest = ImportRequest()): ImportBatchReport {
        val startedAt = clock.nowEpochMillis()
        val requestId = request.requestId.ifEmpty { environment.idGenerator.newId("resume") }
        val now = clock.nowEpochMillis()

        val candidates = jobStore.recent(200).filter { job ->
            job.state in RESUMABLE_STATES && now - job.updatedAtEpochMillis >= options.interruptedJobThresholdMillis
        }

        val outcomes = ArrayList<ImportOutcome>()
        candidates.forEach { job ->
            coroutineContext.ensureActive()
            outcomes.add(resume(job, requestId, request))
        }

        return ImportBatchReport(requestId, outcomes, startedAt, clock.nowEpochMillis())
    }

    /**
     * Runs every job that is due right now (queued work and retries whose backoff elapsed).
     * The background worker in [PropertyImportQueue] calls this on a timer.
     */
    suspend fun retryDueJobs(request: ImportRequest = ImportRequest()): ImportBatchReport {
        val startedAt = clock.nowEpochMillis()
        val requestId = request.requestId.ifEmpty { environment.idGenerator.newId("due") }
        val now = clock.nowEpochMillis()
        val candidates = jobStore.recent(200)
            .filter { it.state in RESUMABLE_STATES && it.state != PropertyImportJobState.RECEIVED && it.isDue(now) }

        val outcomes = ArrayList<ImportOutcome>()
        candidates.forEach { job ->
            coroutineContext.ensureActive()
            outcomes.add(resume(job, requestId, request))
        }
        return ImportBatchReport(requestId, outcomes, startedAt, clock.nowEpochMillis())
    }

    /** Marks an in-flight job as cancelled (user navigated away, dialogs closed…). */
    suspend fun cancel(jobId: String): ImportOutcome.Cancelled? {
        val job = jobStore.findById(jobId) ?: return null
        if (job.isTerminal) return null
        val result = transition(job, PropertyImportJobState.CANCELLED, "USER_CANCELLED", clock.nowEpochMillis())
        return ImportOutcome.Cancelled(persisted(result.applied))
    }

    /** Offline preview used by UI: validation + detection + support check, no network, no writes. */
    fun inspect(rawInput: String): UrlInspection {
        val resolution = resolver.resolve(rawInput)
        val resolved = (resolution as? UrlResolutionResult.Resolved)?.resolved
        val definition = resolved?.detection?.definition
        val adapter = resolved?.let { adapters.forResolved(it) }
        val notes = ArrayList<String>()
        resolved?.notes?.let { notes.addAll(it) }
        return UrlInspection(
            rawInput = rawInput,
            resolution = resolution,
            sourceId = definition?.sourceId,
            sourceDisplayName = definition?.displayName,
            isSupported = definition?.isSupported == true && adapter != null,
            requiresCredentials = definition?.capabilities?.requiresCredentials == true,
            normalizedUrl = resolved?.url?.normalized,
            externalListingId = resolved?.externalListingId,
            notes = notes
        )
    }

    suspend fun recentJobs(limit: Int = 25): List<PropertyImportJob> = jobStore.recent(limit)

    suspend fun jobById(jobId: String): PropertyImportJob? = jobStore.findById(jobId)

    /** Observability snapshot: per-source circuit state and rate limiter tokens. */
    fun sourceHealth(): Map<String, String> {
        val tracker = environment.healthTracker ?: return emptyMap()
        return registry.definitions.associate { definition ->
            definition.sourceId to tracker.snapshot(definition.sourceId).state.name
        }
    }

    // --- Internals ------------------------------------------------------------------------

    private suspend fun execute(
        job: PropertyImportJob,
        resolved: ResolvedPropertyUrl,
        adapterId: String,
        adapter: PropertySourceAdapter,
        request: ImportRequest
    ): ImportOutcome {
        val sourceId = resolved.sourceId ?: adapter.descriptor.sourceId
        val maxAttempts = (request.maxAttempts ?: options.maxFetchAttempts).coerceAtLeast(1)
        val policy = environment.retryPolicy.copy(maxAttempts = maxAttempts)
        val startedAt = clock.nowEpochMillis()

        var current = job
        var attempt = current.attempts
        var lastFailure: SourceFailure? = null

        environment.telemetry.record(
            TelemetryEvent(
                name = "import.started",
                jobId = current.jobId,
                requestId = current.requestId,
                sourceId = sourceId,
                adapterId = adapterId,
                url = resolved.url.redacted()
            )
        )

        while (true) {
            coroutineContext.ensureActive()

            // Budget: never keep a caller waiting for an unbounded amount of retries.
            if (clock.nowEpochMillis() - startedAt > options.maxTotalImportMillis) {
                val deferred = defer(
                    current,
                    lastFailure ?: failureClassifier.internal("import budget exhausted before an attempt"),
                    "BUDGET_EXHAUSTED",
                    clock.nowEpochMillis() + DEFER_ON_BUDGET_MILLIS
                )
                return ImportOutcome.Deferred(persisted(deferred), deferred.nextAttemptAtEpochMillis ?: clock.nowEpochMillis())
            }

            // Rate limiting (per source token bucket).
            if (!request.skipRateLimits && options.respectRateLimiter) {
                val waitMillis = environment.rateLimiter?.acquire(sourceId) ?: 0L
                if (waitMillis > 0) {
                    if (waitMillis > options.maxInlineRateLimitWaitMillis) {
                        val deferred = defer(
                            current,
                            SourceFailure(
                                kind = SourceFailureKind.HTTP_RATE_LIMITED,
                                message = "rate limit wait ${waitMillis}ms exceeds the inline budget",
                                sourceId = sourceId,
                                retryAfterSeconds = (waitMillis / 1000).coerceAtLeast(1),
                                occurredAtEpochMillis = clock.nowEpochMillis()
                            ),
                            "RATE_LIMITED",
                            clock.nowEpochMillis() + waitMillis
                        )
                        return ImportOutcome.Deferred(
                            persisted(deferred),
                            deferred.nextAttemptAtEpochMillis ?: clock.nowEpochMillis()
                        )
                    }
                    environment.sleeper.sleep(waitMillis)
                }
            }

            // Circuit breaker.
            val breakerFailure = environment.healthTracker?.beforeFetch(sourceId)
            if (breakerFailure != null) {
                val decision = policy.decide(attempt + 1, breakerFailure, clock.nowEpochMillis())
                return if (decision is RetryDecision.Retry &&
                    clock.nowEpochMillis() + decision.delayMillis - startedAt <= options.maxTotalImportMillis
                ) {
                    lastFailure = breakerFailure
                    current = transition(
                        current,
                        PropertyImportJobState.FETCH_RETRY_SCHEDULED,
                        "CIRCUIT_OPEN",
                        clock.nowEpochMillis(),
                        failure = breakerFailure,
                        nextAttemptAtEpochMillis = decision.nextAttemptAtEpochMillis
                    ).applied
                    environment.sleeper.sleep(decision.delayMillis)
                    attempt++
                    transition(current, PropertyImportJobState.QUEUED, "RETRY_DUE", clock.nowEpochMillis()).let { current = it.applied }
                    continue
                } else {
                    val failed = transition(
                        current,
                        PropertyImportJobState.FETCH_FAILED,
                        "CIRCUIT_OPEN",
                        clock.nowEpochMillis(),
                        failure = breakerFailure
                    ).applied
                    return ImportOutcome.Failed(persisted(failed))
                }
            }

            attempt++
            current = transition(
                current,
                PropertyImportJobState.FETCHING,
                "ATTEMPT_STARTED",
                clock.nowEpochMillis(),
                attemptIncrement = 1
            ).applied

            val context = AdapterContext(
                source = registry.byId(sourceId) ?: resolved.detection.definition
                    ?: com.example.domain.propertyurl.source.SourceCatalog.GENERIC_WEB,
                adapterId = adapterId,
                adapterVersion = adapter.descriptor.version,
                requestId = current.requestId,
                clock = clock,
                httpFetcher = environment.httpFetcher,
                fetchOptions = options.fetchOptions,
                telemetry = environment.telemetry,
                failureClassifier = failureClassifier,
                parserChain = parserChain,
                credentials = environment.credentialProvider.credentialsFor(sourceId)
            )

            val requestToSend = adapter.buildRequest(resolved, context)
            val fetchOutcome = adapter.fetch(requestToSend, context)

            when (fetchOutcome) {
                is SourceFetchOutcome.Fetched -> {
                    lastFailure = null
                    environment.healthTracker?.recordSuccess(sourceId)
                    val afterParse = handleDocument(current, fetchOutcome, adapter, context, resolved, sourceId)
                    if (afterParse is LoopDecision.Continue) {
                        current = afterParse.job
                        continue
                    }
                    return (afterParse as LoopDecision.Finish).outcome
                }

                is SourceFetchOutcome.Failed -> {
                    lastFailure = fetchOutcome.failure
                    environment.healthTracker?.recordFailure(sourceId, fetchOutcome.failure)
                    environment.telemetry.record(
                        TelemetryEvent(
                            name = "import.attempt.failed",
                            jobId = current.jobId,
                            requestId = current.requestId,
                            sourceId = sourceId,
                            adapterId = adapterId,
                            url = resolved.url.redacted(),
                            failureKind = fetchOutcome.failure.kind,
                            attempt = attempt
                        )
                    )
                    val decision = policy.decide(
                        attempt = attempt,
                        failure = fetchOutcome.failure,
                        nowEpochMillis = clock.nowEpochMillis(),
                        jitterFraction = { Random.nextDouble() * 2.0 - 1.0 }
                    )
                    if (decision is RetryDecision.Retry &&
                        clock.nowEpochMillis() + decision.delayMillis - startedAt <= options.maxTotalImportMillis
                    ) {
                        current = transition(
                            current,
                            PropertyImportJobState.FETCH_RETRY_SCHEDULED,
                            "RETRY_SCHEDULED",
                            clock.nowEpochMillis(),
                            failure = fetchOutcome.failure,
                            nextAttemptAtEpochMillis = decision.nextAttemptAtEpochMillis
                        ).applied
                        environment.sleeper.sleep(decision.delayMillis)
                        current = transition(current, PropertyImportJobState.QUEUED, "RETRY_DUE", clock.nowEpochMillis()).applied
                        continue
                    }
                    if (decision is RetryDecision.Retry) {
                        // Retry would exceed the call budget: schedule it for later instead of failing.
                        val deferred = defer(
                            current,
                            fetchOutcome.failure,
                            "DEFERRED_RETRY",
                            decision.nextAttemptAtEpochMillis
                        )
                        return ImportOutcome.Deferred(
                            persisted(deferred),
                            deferred.nextAttemptAtEpochMillis ?: clock.nowEpochMillis()
                        )
                    }
                    val failed = transition(
                        current,
                        PropertyImportJobState.FETCH_FAILED,
                        "FETCH_FAILED",
                        clock.nowEpochMillis(),
                        failure = fetchOutcome.failure
                    ).applied
                    return ImportOutcome.Failed(persisted(failed))
                }

                is SourceFetchOutcome.Skipped -> {
                    val failure = fetchOutcome.failure ?: failureClassifier.policyDisallowed(
                        reason = "adapter skipped the fetch (${fetchOutcome.reason.name.lowercase(Locale.US)})",
                        sourceId = sourceId,
                        url = resolved.url.redacted()
                    )
                    val blocked = transition(
                        current,
                        PropertyImportJobState.BLOCKED_BY_POLICY,
                        fetchOutcome.reason.name,
                        clock.nowEpochMillis(),
                        failure = failure
                    ).applied
                    return ImportOutcome.Rejected(persisted(blocked))
                }
            }
        }
    }

    private sealed interface LoopDecision {
        data class Continue(val job: PropertyImportJob) : LoopDecision
        data class Finish(val outcome: ImportOutcome) : LoopDecision
    }

    private suspend fun handleDocument(
        job: PropertyImportJob,
        fetched: SourceFetchOutcome.Fetched,
        adapter: PropertySourceAdapter,
        context: AdapterContext,
        resolved: ResolvedPropertyUrl,
        sourceId: String
    ): LoopDecision {
        val parsing = transition(
            job,
            PropertyImportJobState.PARSING,
            "DOCUMENT_FETCHED",
            clock.nowEpochMillis(),
            warnings = fetched.warnings
        ).applied

        val parseOutcome: ParseOutcome = adapter.parse(fetched.document, context)

        if (!parseOutcome.hasFacts) {
            val failure = parseOutcome.failure ?: failureClassifier.fromParse(
                parserId = parserChain.parserIds.firstOrNull() ?: "parser-chain",
                reason = "no facts extracted",
                kind = SourceFailureKind.PARSE_FAILED,
                sourceId = sourceId,
                url = fetched.document.finalUrl
            )
            val failed = transition(
                parsing,
                PropertyImportJobState.PARSE_FAILED,
                "NO_FACTS",
                clock.nowEpochMillis(),
                failure = failure,
                warnings = parseOutcome.warnings
            ).applied
            return LoopDecision.Finish(ImportOutcome.Failed(persisted(failed)))
        }

        if (parseOutcome.isPartial) {
            // Parsers reported degraded extraction; the mapping step decides the final status.
            environment.telemetry.record(
                TelemetryEvent(
                    name = "import.parse.partial",
                    jobId = job.jobId,
                    sourceId = sourceId,
                    adapterId = adapter.descriptor.adapterId,
                    url = resolved.url.redacted()
                )
            )
        }

        val normalizing = transition(
            parsing,
            PropertyImportJobState.NORMALIZING,
            "FACTS_EXTRACTED",
            clock.nowEpochMillis(),
            warnings = parseOutcome.warnings,
            usedParsers = parseOutcome.usedParsers
        ).applied

        val mapping = mapper.map(parseOutcome.facts, resolved, sourceId)
        val property = mapping.property
        val usable = property.completeness.isUsable
        val hasIdentity = hasIdentityEvidence(property)

        return when {
            usable -> {
                val succeeded = transition(
                    normalizing,
                    PropertyImportJobState.SUCCEEDED,
                    "CANONICAL_PROPERTY_BUILT",
                    clock.nowEpochMillis(),
                    canonicalProperty = property,
                    warnings = mapping.warnings
                ).applied
                val persistedJob = persisted(succeeded)
                environment.telemetry.record(
                    TelemetryEvent(
                        name = "import.succeeded",
                        jobId = persistedJob.jobId,
                        requestId = persistedJob.requestId,
                        sourceId = sourceId,
                        adapterId = adapter.descriptor.adapterId,
                        url = resolved.url.redacted(),
                        outcome = "SUCCESS",
                        durationMillis = clock.nowEpochMillis() - job.createdAtEpochMillis
                    )
                )
                LoopDecision.Finish(ImportOutcome.Success(persistedJob))
            }

            options.allowPartialSuccess && hasIdentity -> {
                val partial = transition(
                    normalizing,
                    PropertyImportJobState.PARTIAL_SUCCESS,
                    "PARTIAL_CANONICAL_PROPERTY",
                    clock.nowEpochMillis(),
                    canonicalProperty = property,
                    warnings = mapping.warnings
                ).applied
                val persistedJob = persisted(partial)
                environment.telemetry.record(
                    TelemetryEvent(
                        name = "import.partial",
                        jobId = persistedJob.jobId,
                        requestId = persistedJob.requestId,
                        sourceId = sourceId,
                        adapterId = adapter.descriptor.adapterId,
                        url = resolved.url.redacted(),
                        outcome = "PARTIAL",
                        attributes = mapOf(
                            "missing" to property.missingCriticalFields().joinToString(",") { it.name.lowercase(Locale.US) }
                        )
                    )
                )
                LoopDecision.Finish(ImportOutcome.Partial(persistedJob))
            }

            else -> {
                val failure = failureClassifier.fromParse(
                    parserId = parseOutcome.usedParsers.firstOrNull() ?: "parser-chain",
                    reason = "canonical record missing required fields: " +
                        property.missingCriticalFields().joinToString(",") { it.name.lowercase(Locale.US) },
                    kind = SourceFailureKind.MISSING_REQUIRED_FIELDS,
                    missingFields = property.missingCriticalFields(),
                    sourceId = sourceId,
                    adapterId = adapter.descriptor.adapterId,
                    url = fetched.document.finalUrl
                )
                val failed = transition(
                    normalizing,
                    PropertyImportJobState.PARSE_FAILED,
                    "MISSING_REQUIRED_FIELDS",
                    clock.nowEpochMillis(),
                    failure = failure,
                    warnings = mapping.warnings
                ).applied
                LoopDecision.Finish(ImportOutcome.Failed(persisted(failed)))
            }
        }
    }

    private suspend fun resume(
        job: PropertyImportJob,
        requestId: String,
        request: ImportRequest
    ): ImportOutcome {
        val normalizedUrl = job.normalizedUrl
        if (normalizedUrl == null) {
            return ImportOutcome.Cancelled(persisted(transition(
                job, PropertyImportJobState.CANCELLED, "UNRECOVERABLE_NO_URL", clock.nowEpochMillis()
            ).applied))
        }
        val single = resolver.resolveSingle(normalizedUrl, isSingleCandidate = true)
        val resolved = (single as? UrlResolutionResult.Resolved)?.resolved
            ?: return reject(
                rawInput = job.rawInput,
                requestId = requestId,
                idempotencyKey = job.idempotencyKey,
                state = PropertyImportJobState.REJECTED_INVALID_URL,
                reasonCode = "RESUME_RESOLVE_FAILED",
                failure = failureClassifier.invalidUrl("stored URL no longer resolves", Redaction.url(normalizedUrl)),
                persist = true,
                now = clock.nowEpochMillis(),
                resolved = null,
                existingJob = job
            )

        val adapter = adapters.forResolved(resolved)
            ?: return reject(
                rawInput = job.rawInput,
                requestId = requestId,
                idempotencyKey = job.idempotencyKey,
                state = PropertyImportJobState.REJECTED_UNSUPPORTED_SOURCE,
                reasonCode = "RESUME_NO_ADAPTER",
                failure = failureClassifier.notSupported(
                    sourceId = resolved.sourceId ?: "unknown",
                    displayName = resolved.detection.definition?.displayName ?: "This website",
                    url = resolved.url.redacted()
                ),
                persist = true,
                now = clock.nowEpochMillis(),
                resolved = resolved,
                existingJob = job
            )

        // Rewind to QUEUED (interrupted attempts never completed) and run the normal loop.
        val rewound = when (val result = transition(job, PropertyImportJobState.QUEUED, "RECOVERED_AFTER_INTERRUPT", clock.nowEpochMillis())) {
            is TransitionResult.Applied -> result.applied
            is TransitionResult.Illegal -> job
        }
        return execute(persisted(rewound), resolved, adapter.descriptor.adapterId, adapter, request)
    }

    private fun checkSourceSupport(resolved: ResolvedPropertyUrl): SourceFailure? {
        val definition = resolved.detection.definition
        if (definition == null) {
            return if (options.allowGenericFallback) null else failureClassifier.notSupported(
                sourceId = "unknown",
                displayName = "This website",
                url = resolved.url.redacted()
            )
        }
        if (definition.status == SourceStatus.PLANNED && !options.allowPlannedSources) {
            return failureClassifier.notSupported(
                sourceId = definition.sourceId,
                displayName = definition.displayName,
                url = resolved.url.redacted()
            )
        }
        if (definition.status == SourceStatus.DISABLED) {
            return SourceFailure(
                kind = SourceFailureKind.SOURCE_DISABLED,
                message = "source '${definition.sourceId}' is disabled",
                sourceId = definition.sourceId,
                url = resolved.url.redacted(),
                occurredAtEpochMillis = clock.nowEpochMillis()
            )
        }
        return null
    }

    private suspend fun suppressDuplicate(
        previous: PropertyImportJob,
        rawInput: String,
        requestId: String,
        idempotencyKey: String,
        resolved: ResolvedPropertyUrl,
        request: ImportRequest,
        now: Long
    ): ImportOutcome {
        val baseJob = newJob(
            rawInput = rawInput,
            requestId = requestId,
            idempotencyKey = idempotencyKey,
            resolved = resolved,
            adapterId = previous.adapterId,
            now = now
        )
        val suppressed = transition(
            job = baseJob,
            target = PropertyImportJobState.DUPLICATE_SUPPRESSED,
            reasonCode = "IDEMPOTENT_REUSE",
            atEpochMillis = now,
            canonicalProperty = previous.canonicalProperty,
            warnings = listOf(
                ImportWarning(
                    code = ImportWarning.WarningCode.DUPLICATE_SUPPRESSED,
                    message = "Reused the result of ${previous.jobId} (age ${now - previous.updatedAtEpochMillis} ms)",
                    sourceId = previous.sourceId
                )
            ),
            detail = previous.jobId
        ).applied.copy(duplicateOfJobId = previous.jobId, sourceId = previous.sourceId, adapterId = previous.adapterId)

        val toPersist = if (options.recordSuppressedDuplicates) persisted(suppressed) else suppressed
        environment.telemetry.record(
            TelemetryEvent(
                name = "import.duplicate",
                jobId = toPersist.jobId,
                requestId = requestId,
                sourceId = previous.sourceId,
                url = resolved.url.redacted(),
                outcome = "DUPLICATE"
            )
        )
        return ImportOutcome.Duplicate(toPersist, previous.jobId)
    }

    private fun newJob(
        rawInput: String,
        requestId: String,
        idempotencyKey: String,
        resolved: ResolvedPropertyUrl,
        adapterId: String?,
        now: Long
    ): PropertyImportJob = PropertyImportJob(
        jobId = environment.idGenerator.newId("job"),
        rawInput = safeStoredRawInput(rawInput),
        normalizedUrl = resolved.url.normalized,
        sourceId = resolved.sourceId,
        adapterId = adapterId,
        externalListingId = resolved.externalListingId,
        idempotencyKey = idempotencyKey,
        state = PropertyImportJobState.RECEIVED,
        attempts = 0,
        maxAttempts = options.maxFetchAttempts,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
        requestId = requestId,
        transitions = emptyList()
    )

    private suspend fun reject(
        rawInput: String,
        requestId: String,
        idempotencyKey: String,
        state: PropertyImportJobState,
        reasonCode: String,
        failure: SourceFailure,
        persist: Boolean,
        now: Long,
        resolved: ResolvedPropertyUrl? = null,
        existingJob: PropertyImportJob? = null
    ): ImportOutcome {
        val job = existingJob ?: PropertyImportJob(
            jobId = environment.idGenerator.newId("job"),
            rawInput = safeStoredRawInput(rawInput),
            normalizedUrl = resolved?.url?.normalized,
            sourceId = resolved?.sourceId,
            adapterId = null,
            externalListingId = resolved?.externalListingId,
            idempotencyKey = idempotencyKey,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            requestId = requestId
        )
        val rejected = transition(job, state, reasonCode, now, failure = failure).applied
        val finalJob = if (persist) persisted(rejected) else rejected
        environment.telemetry.record(
            TelemetryEvent(
                name = "import.rejected",
                jobId = finalJob.jobId,
                requestId = requestId,
                sourceId = finalJob.sourceId,
                url = Redaction.url(finalJob.normalizedUrl),
                failureKind = failure.kind,
                outcome = state.name
            )
        )
        return ImportOutcome.Rejected(finalJob)
    }

    private fun transition(
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
    ): TransitionResult = PropertyImportStateMachine.transition(
        job = job,
        target = target,
        reasonCode = reasonCode,
        atEpochMillis = atEpochMillis,
        failure = failure,
        attemptIncrement = attemptIncrement,
        nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
        canonicalProperty = canonicalProperty,
        warnings = warnings,
        usedParsers = usedParsers,
        detail = detail
    )

    private fun defer(
        job: PropertyImportJob,
        failure: SourceFailure,
        reasonCode: String,
        nextAttemptAtEpochMillis: Long
    ): PropertyImportJob {
        val scheduled = when (val result = transition(
            job = job,
            target = PropertyImportJobState.FETCH_RETRY_SCHEDULED,
            reasonCode = reasonCode,
            atEpochMillis = clock.nowEpochMillis(),
            failure = failure,
            nextAttemptAtEpochMillis = nextAttemptAtEpochMillis
        )) {
            is TransitionResult.Applied -> result.applied
            is TransitionResult.Illegal -> job.copy(
                nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
                lastFailure = failure
            )
        }
        return scheduled
    }

    /** Persistence boundary: raw pasted URLs are redacted before returning or writing a job. */
    private suspend fun persisted(job: PropertyImportJob): PropertyImportJob {
        val safeJob = job.copy(rawInput = safeStoredRawInput(job.rawInput))
        jobStore.save(safeJob)
        return safeJob
    }

    private fun safeStoredRawInput(rawInput: String): String =
        Redaction.url(rawInput)?.take(2048).orEmpty()

    private fun hasIdentityEvidence(property: CanonicalProperty): Boolean =
        !property.addressLine1.isNullOrBlank() ||
            (!property.city.isNullOrBlank() && !property.state.isNullOrBlank())

    private suspend fun failedOutcome(input: String, requestId: String, error: Exception): ImportOutcome {
        val now = clock.nowEpochMillis()
        val failure = failureClassifier.internal(
            message = "unhandled ${error.javaClass.simpleName} during import",
            sourceId = null
        )
        val job = PropertyImportJob(
            jobId = environment.idGenerator.newId("job"),
            rawInput = safeStoredRawInput(input),
            idempotencyKey = IdempotencyKeys.forDocument(input.take(512)),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            requestId = requestId
        )
        val failed = transition(job, PropertyImportJobState.REJECTED_INVALID_URL, "INTERNAL_ERROR", now, failure = failure)
            .let { if (it is TransitionResult.Applied) it.applied else job }
            .copy(rawInput = safeStoredRawInput(input))
        return withContext(NonCancellable) {
            runCatching { jobStore.save(failed) }
            ImportOutcome.Failed(failed)
        }
    }

    private companion object {
        const val DEFER_ON_BUDGET_MILLIS = 30_000L
    }
}
