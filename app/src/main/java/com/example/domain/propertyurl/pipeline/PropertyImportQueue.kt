package com.example.domain.propertyurl.pipeline

import com.example.domain.propertyurl.job.PropertyImportJob
import com.example.domain.propertyurl.job.PropertyImportJobStore
import com.example.domain.propertyurl.job.PropertyImportJobState
import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.port.Clock
import com.example.domain.propertyurl.port.TelemetryEvent
import com.example.domain.propertyurl.port.TelemetrySink
import com.example.domain.propertyurl.util.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The background worker of the layer.
 *
 * Responsibilities:
 *  - accept inputs from the UI without blocking it ([enqueue]),
 *  - fetch them with bounded concurrency ([maxParallel]),
 *  - pick up deferred retries and jobs interrupted by process death ([pollOnce], timer-driven),
 *  - expose counters so the app can show "3 imports running".
 *
 * The queue is *not* a second scheduler: all job state lives in [PropertyImportJobStore], so killing
 * the process loses nothing but the in-memory channel (the queued rows are recovered on next start).
 *
 * The queue does not know where canonical records are stored. It hands every record it produced -
 * including the ones it recovered after a process death - to [onPropertyImported]; the composition
 * root decides what "imported" means (in the app that is the same bridge the UI import path uses).
 * The default sink is a deliberate no-op for tooling and tests: a queue built without one maintains
 * the job ledger only, so it must never be presented as "the property was imported". The app wiring
 * is pinned by `WorkflowWiringGuardTest` for exactly that reason.
 */
class PropertyImportQueue(
    private val intelligence: PropertyUrlIntelligence,
    private val jobStore: PropertyImportJobStore,
    private val clock: Clock,
    private val telemetry: TelemetrySink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** How often deferred/queued work is re-checked. */
    private val pollIntervalMillis: Long = 15_000,
    private val maxParallel: Int = 2,
    /** Backlog limit; older items beyond this are dropped with a warning instead of growing unbounded. */
    private val capacity: Int = 64,
    /** Persistence sink for canonical records produced by background or recovered work. */
    private val onPropertyImported: suspend (CanonicalProperty) -> Unit = { }
) {

    private val pending = Channel<QueuedItem>(capacity = capacity)
    private val permits = Semaphore(maxParallel.coerceAtLeast(1))

    @Volatile
    private var started = false

    private var worker: Job? = null
    private var poller: Job? = null

    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val backlog = java.util.concurrent.atomic.AtomicInteger(0)

    /** Number of imports currently being executed (not counting the backlog). */
    val runningCount: Int get() = inFlight.get()

    /** Number of inputs accepted but not yet handed to the pipeline. */
    val backlogCount: Int get() = backlog.get()

    /** Starts the worker + retry poller. Idempotent; safe to call from `Application.onCreate`. */
    fun start() {
        if (started) return
        started = true

        worker = scope.launch {
            for (item in pending) {
                backlog.decrementAndGet()
                permits.withPermit { processOne(item) }
            }
        }

        poller = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                runCatching { pollOnce() }
                    .onFailure { telemetry.record(TelemetryEvent(name = "queue.poll.failed", attributes = mapOf("error" to it.javaClass.simpleName))) }
            }
        }
    }

    /** Stops accepting work and cancels the background coroutines. */
    fun stop() {
        started = false
        worker?.cancel()
        poller?.cancel()
        worker = null
        poller = null
        pending.close()
    }

    /** Queues one input for background import. Returns false when the backlog is full. */
    fun enqueue(input: String, request: ImportRequest = ImportRequest()): Boolean {
        val result = pending.trySend(QueuedItem(input, request))
        if (result.isSuccess) backlog.incrementAndGet()
        if (result.isFailure) {
            telemetry.record(
                TelemetryEvent(
                    name = "queue.rejected",
                    outcome = "BACKLOG_FULL",
                    url = Redaction.url(input.take(512))
                )
            )
            return false
        }
        return true
    }

    /** Runs deferred retries and jobs interrupted by process death (used by tests/WorkManager). */
    suspend fun pollOnce(requestId: String = "queue-poll"): ImportBatchReport {
        val startedAt = clock.nowEpochMillis()
        val outcomes = ArrayList<ImportOutcome>()

        // Retries whose backoff elapsed, plus jobs left in-flight by a previous process.
        outcomes.addAll(intelligence.retryDueJobs(ImportRequest(requestId = requestId)).outcomes)
        outcomes.addAll(intelligence.resumeInterruptedJobs(ImportRequest(requestId = requestId)).outcomes)
        outcomes.forEach { persist(it) }

        return ImportBatchReport(requestId, outcomes, startedAt, clock.nowEpochMillis())
    }

    /** Jobs that still need work (queued or waiting for a retry) — what the queue would resume. */
    suspend fun pendingJobs(limit: Int = 50): List<PropertyImportJob> = jobStore.recent(limit)
        .filter { it.state == PropertyImportJobState.QUEUED || it.state == PropertyImportJobState.FETCH_RETRY_SCHEDULED }

    private suspend fun processOne(item: QueuedItem) {
        inFlight.incrementAndGet()
        val input = item.input
        try {
            val outcome = intelligence.import(input, item.request)
            persist(outcome)
            telemetry.record(
                TelemetryEvent(
                    name = "queue.item.finished",
                    jobId = outcome.job.jobId,
                    sourceId = outcome.job.sourceId,
                    url = Redaction.url(input.take(512)),
                    outcome = outcome.status.name
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Import() already converts expected failures into outcomes; this is the last-resort net.
            telemetry.record(
                TelemetryEvent(
                    name = "queue.item.crashed",
                    url = Redaction.url(input.take(512)),
                    attributes = mapOf("error" to e.javaClass.name)
                )
            )
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /**
     * Hands one produced record to the persistence sink.
     *
     * A sink failure is recorded, never rethrown: an exception here would tear down the worker (or
     * the poller) and stop every later import, turning one bad record into an outage. The job ledger
     * keeps reporting what the *layer* is responsible for - the fetch, parse and normalize succeeded
     * - and `queue.persist.failed` is the durable signal that the record did not reach the store.
     */
    private suspend fun persist(outcome: ImportOutcome) {
        val property = outcome.property ?: return
        if (!outcome.isSuccess) return
        try {
            onPropertyImported(property)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            telemetry.record(
                TelemetryEvent(
                    name = "queue.persist.failed",
                    jobId = outcome.job.jobId,
                    sourceId = outcome.job.sourceId,
                    outcome = "PERSIST_FAILED",
                    attributes = mapOf("error" to e.javaClass.simpleName)
                )
            )
        }
    }

    private data class QueuedItem(val input: String, val request: ImportRequest)
}
