package com.example.urlintelligence.job

import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.idempotency.IdempotencyKey
import java.util.UUID

/** Every state a URL import can occupy. */
enum class ImportJobState {
    CREATED,
    VALIDATING,
    DETECTING,
    DISPATCHING,
    FETCHING,
    RETRY_SCHEDULED,
    PARSING,
    NORMALIZING,
    PARTIALLY_SUCCEEDED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == PARTIALLY_SUCCEEDED || this == SUCCEEDED || this == FAILED || this == CANCELLED

    val isSuccessful: Boolean
        get() = this == SUCCEEDED || this == PARTIALLY_SUCCEEDED
}

/** Events that drive the machine. Anything not allowed in the current state is rejected. */
enum class ImportJobEvent {
    START_VALIDATION,
    VALIDATION_SUCCEEDED,
    VALIDATION_FAILED,
    SOURCE_DETECTED,
    SOURCE_UNSUPPORTED,
    POLICY_BLOCKED,
    DISPATCHED,
    FETCH_SUCCEEDED,
    RETRY_SCHEDULED,
    RETRY_STARTED,
    FETCH_FAILED,
    PARSE_SUCCEEDED,
    PARSE_FAILED,
    NORMALIZE_SUCCEEDED,
    NORMALIZE_PARTIAL,
    NORMALIZE_FAILED,
    CANCEL,
    RESET
}

data class JobTransition(
    val from: ImportJobState,
    val event: ImportJobEvent,
    val to: ImportJobState,
    val atEpochMillis: Long,
    val note: String? = null
)

sealed class TransitionResult {
    data class Accepted(
        val job: PropertyImportJob,
        val transition: JobTransition
    ) : TransitionResult()

    data class Rejected(
        val job: PropertyImportJob,
        val event: ImportJobEvent,
        val state: ImportJobState,
        val reason: String
    ) : TransitionResult()
}

/**
 * Immutable state machine for one URL import.
 *
 * Why explicit states instead of a try/catch flow: retries, cancellation, partial
 * success and "resume after process death" all need a durable, replayable view of
 * where an import is. The machine is pure — [transition] returns a new instance —
 * so it is trivial to test and safe to persist through [PropertyImportJobStore].
 */
data class PropertyImportJob(
    val jobId: String,
    val idempotencyKey: IdempotencyKey,
    val rawUrl: String,
    val canonicalUrl: String? = null,
    val sourceId: String? = null,
    val sourcePropertyId: String? = null,
    val state: ImportJobState = ImportJobState.CREATED,
    val attempt: Int = 0,
    val maxAttempts: Int = 3,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val transitions: List<JobTransition> = emptyList(),
    val failure: SourceFailure? = null,
    val warnings: List<String> = emptyList()
) {

    fun canHandle(event: ImportJobEvent): Boolean = nextStateFor(event) != null

    fun nextStateFor(event: ImportJobEvent): ImportJobState? = TRANSITION_TABLE[state]?.get(event)

    fun allowedEvents(): Set<ImportJobEvent> = TRANSITION_TABLE[state]?.keys ?: emptySet()

    /**
     * Attempts a transition.
     *
     * @param patch applied to the new job after the state change (e.g. storing the
     *              detected source id), keeping every write in one place.
     */
    fun transition(
        event: ImportJobEvent,
        atEpochMillis: Long,
        note: String? = null,
        patch: (PropertyImportJob) -> PropertyImportJob = { it }
    ): TransitionResult {
        val target = nextStateFor(event)
            ?: return TransitionResult.Rejected(
                job = this,
                event = event,
                state = state,
                reason = "event $event is not allowed in state $state"
            )

        val record = JobTransition(
            from = state,
            event = event,
            to = target,
            atEpochMillis = atEpochMillis,
            note = note
        )

        var next = copy(
            state = target,
            updatedAtEpochMillis = atEpochMillis,
            transitions = transitions + record
        )
        next = patch(next).copy(transitions = next.transitions)
        return TransitionResult.Accepted(job = next, transition = record)
    }

    val durationMillis: Long
        get() = (updatedAtEpochMillis - createdAtEpochMillis).coerceAtLeast(0L)

    fun summary(): String = buildString {
        append("job=").append(jobId)
        append(" state=").append(state)
        append(" source=").append(sourceId ?: "-")
        append(" attempt=").append(attempt).append('/').append(maxAttempts)
        failure?.let { append(" failure=").append(it.code) }
    }

    companion object {

        fun create(
            rawUrl: String,
            idempotencyKey: IdempotencyKey,
            jobId: String = UUID.randomUUID().toString(),
            maxAttempts: Int = 3,
            now: Long = 0L
        ): PropertyImportJob = PropertyImportJob(
            jobId = jobId,
            idempotencyKey = idempotencyKey,
            rawUrl = rawUrl,
            maxAttempts = maxAttempts,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )

        /**
         * The complete, auditable transition table.
         *
         * Illegal transitions are rejected rather than coerced: a bug that fires
         * FETCH_SUCCEEDED twice must surface as a rejected event, not silently
         * corrupt the job.
         */
        val TRANSITION_TABLE: Map<ImportJobState, Map<ImportJobEvent, ImportJobState>> = mapOf(
            ImportJobState.CREATED to mapOf(
                ImportJobEvent.START_VALIDATION to ImportJobState.VALIDATING,
                ImportJobEvent.CANCEL to ImportJobState.CANCELLED
            ),
            ImportJobState.VALIDATING to mapOf(
                ImportJobEvent.VALIDATION_SUCCEEDED to ImportJobState.DETECTING,
                ImportJobEvent.VALIDATION_FAILED to ImportJobState.FAILED,
                ImportJobEvent.CANCEL to ImportJobState.CANCELLED
            ),
            ImportJobState.DETECTING to mapOf(
                ImportJobEvent.SOURCE_DETECTED to ImportJobState.DISPATCHING,
                ImportJobEvent.SOURCE_UNSUPPORTED to ImportJobState.FAILED,
                ImportJobEvent.POLICY_BLOCKED to ImportJobState.FAILED,
                ImportJobEvent.CANCEL to ImportJobState.CANCELLED
            ),
            ImportJobState.DISPATCHING to mapOf(
                ImportJobEvent.DISPATCHED to ImportJobState.FETCHING,
                ImportJobEvent.SOURCE_UNSUPPORTED to ImportJobState.FAILED,
                ImportJobEvent.POLICY_BLOCKED to ImportJobState.FAILED,
                ImportJobEvent.CANCEL to ImportJobState.CANCELLED
            ),
            ImportJobState.FETCHING to mapOf(
                ImportJobEvent.FETCH_SUCCEEDED to ImportJobState.PARSING,
                ImportJobEvent.RETRY_SCHEDULED to ImportJobState.RETRY_SCHEDULED,
                ImportJobEvent.FETCH_FAILED to ImportJobState.FAILED,
                ImportJobEvent.CANCEL to ImportJobState.CANCELLED
            ),
            ImportJobState.RETRY_SCHEDULED to mapOf(
                ImportJobEvent.RETRY_STARTED to ImportJobState.FETCHING,
                ImportJobEvent.FETCH_SUCCEEDED to ImportJobState.PARSING,
                ImportJobEvent.FETCH_FAILED to ImportJobState.FAILED,
                ImportJobEvent.CANCEL to ImportJobState.CANCELLED
            ),
            ImportJobState.PARSING to mapOf(
                ImportJobEvent.PARSE_SUCCEEDED to ImportJobState.NORMALIZING,
                ImportJobEvent.PARSE_FAILED to ImportJobState.FAILED,
                // A document can refuse to be used after the fetch (X-Robots-Tag: noindex,
                // login/consent interstitial). That is a policy stop, not a retryable error.
                ImportJobEvent.POLICY_BLOCKED to ImportJobState.FAILED,
                ImportJobEvent.CANCEL to ImportJobState.CANCELLED
            ),
            ImportJobState.NORMALIZING to mapOf(
                ImportJobEvent.NORMALIZE_SUCCEEDED to ImportJobState.SUCCEEDED,
                ImportJobEvent.NORMALIZE_PARTIAL to ImportJobState.PARTIALLY_SUCCEEDED,
                ImportJobEvent.NORMALIZE_FAILED to ImportJobState.FAILED,
                ImportJobEvent.CANCEL to ImportJobState.CANCELLED
            ),
            ImportJobState.SUCCEEDED to mapOf(ImportJobEvent.RESET to ImportJobState.CREATED),
            ImportJobState.PARTIALLY_SUCCEEDED to mapOf(ImportJobEvent.RESET to ImportJobState.CREATED),
            ImportJobState.FAILED to mapOf(
                ImportJobEvent.RESET to ImportJobState.CREATED,
                ImportJobEvent.START_VALIDATION to ImportJobState.VALIDATING
            ),
            ImportJobState.CANCELLED to mapOf(ImportJobEvent.RESET to ImportJobState.CREATED)
        )
    }
}

/** Persistence contract for jobs; back it with Room in the app, or memory in tests. */
interface PropertyImportJobStore {
    fun save(job: PropertyImportJob)

    fun findById(jobId: String): PropertyImportJob?

    fun findByIdempotencyKey(key: IdempotencyKey): PropertyImportJob?

    fun findByState(state: ImportJobState): List<PropertyImportJob>

    fun all(): List<PropertyImportJob>

    fun size(): Int
}

class InMemoryPropertyImportJobStore : PropertyImportJobStore {

    private val lock = Any()
    private val byId: LinkedHashMap<String, PropertyImportJob> = LinkedHashMap()
    private val byKey: LinkedHashMap<String, String> = LinkedHashMap()

    override fun save(job: PropertyImportJob) = synchronized(lock) {
        byId[job.jobId] = job
        byKey[job.idempotencyKey.value] = job.jobId
    }

    override fun findById(jobId: String): PropertyImportJob? = synchronized(lock) { byId[jobId] }

    override fun findByIdempotencyKey(key: IdempotencyKey): PropertyImportJob? = synchronized(lock) {
        byKey[key.value]?.let { byId[it] }
    }

    override fun findByState(state: ImportJobState): List<PropertyImportJob> = synchronized(lock) {
        byId.values.filter { it.state == state }
    }

    override fun all(): List<PropertyImportJob> = synchronized(lock) { byId.values.toList() }

    override fun size(): Int = synchronized(lock) { byId.size }
}
