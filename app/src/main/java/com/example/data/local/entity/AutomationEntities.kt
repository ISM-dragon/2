package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable lifecycle states for a single automation job (one property pipeline).
 *
 * The state machine is the single source of truth for what work is still owed for a
 * property. Every state is persisted *before* the side effect that follows it, so a
 * process death at any point can always be reconciled from the database alone.
 *
 * Terminal states: [SENT], [DISQUALIFIED], [FAILED_TERMINAL], [CANCELLED].
 */
enum class JobState {
    DISCOVERED,
    ANALYZING,
    ANALYZED,
    QUALIFYING,
    QUALIFIED,
    DISQUALIFIED,
    OFFER_GENERATION,
    OFFER_READY,
    VALIDATING_SEND,
    SENDING,

    /** Delivery outcome is unknown (crash / lost acknowledgement). Must be reconciled, never blindly re-sent. */
    RECONCILING,

    SENT,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,

    /** Deterministic blocker that requires operator intervention (bad config, missing PDF, disconnected Gmail). */
    BLOCKED,
    CANCELLED;

    /** No further automatic work is possible for this job. */
    val isTerminal: Boolean
        get() = this == SENT || this == DISQUALIFIED || this == FAILED_TERMINAL || this == CANCELLED

    /** The engine may be mid-step for this job; requires crash reconciliation. */
    val isInFlight: Boolean
        get() = this == ANALYZING || this == QUALIFYING || this == OFFER_GENERATION ||
                this == VALIDATING_SEND || this == SENDING || this == RECONCILING

    val isFailure: Boolean
        get() = this == FAILED_RETRYABLE || this == FAILED_TERMINAL

    val isRetryableFailure: Boolean
        get() = this == FAILED_RETRYABLE

    val isTerminalFailure: Boolean
        get() = this == FAILED_TERMINAL

    /** Deterministic blocker: retrying without operator action would loop forever. */
    val needsOperatorAction: Boolean
        get() = this == BLOCKED

    val isSendStage: Boolean
        get() = this == VALIDATING_SEND || this == SENDING || this == RECONCILING

    /** States that update [AutomationJobEntity.lastSuccessfulState]. */
    val isMilestone: Boolean
        get() = this == ANALYZED || this == QUALIFIED || this == DISQUALIFIED ||
                this == OFFER_READY || this == SENT

    /** The engine can pick the job up without operator intervention. */
    val isAutoResumable: Boolean
        get() = isInFlight || this == DISCOVERED || this == ANALYZED || this == QUALIFIED ||
                this == OFFER_READY || this == FAILED_RETRYABLE

    /**
     * Pure topology of the state machine. Same-state "transitions" are treated as idempotent
     * no-ops (the caller decides whether work still needs to happen).
     */
    fun canTransitionTo(next: JobState): Boolean {
        if (this == next) return true
        return when (this) {
            DISCOVERED -> next == ANALYZING || next == BLOCKED || next == FAILED_RETRYABLE ||
                    next == FAILED_TERMINAL || next == CANCELLED
            ANALYZING -> next == ANALYZED || next == FAILED_RETRYABLE || next == FAILED_TERMINAL ||
                    next == CANCELLED || next == BLOCKED
            ANALYZED -> next == QUALIFYING || next == FAILED_RETRYABLE || next == FAILED_TERMINAL ||
                    next == CANCELLED || next == BLOCKED
            QUALIFYING -> next == QUALIFIED || next == DISQUALIFIED || next == FAILED_RETRYABLE ||
                    next == FAILED_TERMINAL || next == CANCELLED
            QUALIFIED -> next == OFFER_GENERATION || next == FAILED_RETRYABLE ||
                    next == FAILED_TERMINAL || next == CANCELLED || next == BLOCKED
            DISQUALIFIED -> next == CANCELLED
            // OFFER_GENERATION -> QUALIFIED is the crash-recovery path: the offer was never
            // persisted, so the step must be replayed from the last durable milestone.
            OFFER_GENERATION -> next == OFFER_READY || next == QUALIFIED || next == FAILED_RETRYABLE ||
                    next == FAILED_TERMINAL || next == CANCELLED || next == BLOCKED
            OFFER_READY -> next == VALIDATING_SEND || next == BLOCKED || next == FAILED_RETRYABLE ||
                    next == FAILED_TERMINAL || next == CANCELLED
            VALIDATING_SEND -> next == SENDING || next == BLOCKED || next == FAILED_RETRYABLE ||
                    next == FAILED_TERMINAL || next == CANCELLED
            SENDING -> next == SENT || next == RECONCILING || next == FAILED_RETRYABLE ||
                    next == FAILED_TERMINAL || next == CANCELLED
            RECONCILING -> next == SENT || next == OFFER_READY || next == FAILED_RETRYABLE ||
                    next == FAILED_TERMINAL || next == CANCELLED
            SENT -> false
            FAILED_RETRYABLE -> next == DISCOVERED || next == ANALYZING || next == ANALYZED ||
                    next == QUALIFYING || next == QUALIFIED || next == OFFER_GENERATION ||
                    next == OFFER_READY || next == VALIDATING_SEND || next == SENDING ||
                    next == RECONCILING || next == FAILED_RETRYABLE || next == FAILED_TERMINAL ||
                    next == CANCELLED || next == BLOCKED
            FAILED_TERMINAL -> next == CANCELLED
            BLOCKED -> next == VALIDATING_SEND || next == OFFER_READY || next == ANALYZING ||
                    next == QUALIFIED || next == FAILED_RETRYABLE || next == FAILED_TERMINAL ||
                    next == CANCELLED
            CANCELLED -> false
        }
    }

    companion object {
        /** States in which the process may have been killed mid-step. */
        val IN_FLIGHT: List<JobState> = listOf(
            ANALYZING, QUALIFYING, OFFER_GENERATION, VALIDATING_SEND, SENDING, RECONCILING
        )

        /** States the engine processes automatically. */
        val RESUMABLE: List<JobState> = listOf(
            DISCOVERED, ANALYZED, QUALIFIED, OFFER_READY, FAILED_RETRYABLE
        )

        val PENDING: List<JobState> = RESUMABLE + IN_FLIGHT

        val TERMINAL: List<JobState> = listOf(SENT, DISQUALIFIED, FAILED_TERMINAL, CANCELLED)

        fun names(states: List<JobState>): List<String> = states.map { it.name }

        /** Parses a persisted state name; unknown/corrupt values degrade to [DISCOVERED]. */
        fun fromName(raw: String?): JobState =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DISCOVERED
    }
}

/** Status of a persisted [AutomationRunEntity]. Only [RUNNING] rows are considered live. */
object AutomationRunStatus {
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val STOPPED = "STOPPED"
    const val INTERRUPTED = "INTERRUPTED"
    const val ERROR = "ERROR"
    const val KILLED = "KILLED"
}

/** Status of a durable idempotency-ledger entry. */
enum class EffectStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED
}

/** Side effects that must never be applied twice. */
object AutomationEffect {
    const val ANALYZE_PROPERTY = "ANALYZE_PROPERTY"
    const val GENERATE_OFFER = "GENERATE_OFFER"
    const val VALIDATE_SEND = "VALIDATE_SEND"
    const val SEND_OFFER = "SEND_OFFER"

    fun idempotencyKey(effect: String, subjectId: String): String = "$effect:$subjectId"
}

/**
 * Durable automation job for one property.
 *
 * `propertyId` is a soft reference on purpose (no foreign key): the job history must survive
 * property cleanup so the engine can report what it did, and the engine creates the job row before
 * the property row exists. The index keeps per-property lookups fast.
 */
@Entity(
    tableName = "automation_jobs",
    indices = [
        Index(value = ["propertyId"]),
        Index(value = ["currentState"]),
        Index(value = ["runId"]),
        Index(value = ["currentState", "attempts"]),
        Index(value = ["updatedAt"]),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["leaseExpiresAt"]),
        Index(value = ["nextAttemptAt"])
    ]
)
data class AutomationJobEntity(
    @PrimaryKey
    val jobId: String,
    val runId: Long,
    val propertyId: String,
    val propertyAddress: String,
    /** [JobState] name. Never mutated directly - always through the persisted state machine. */
    val currentState: String,
    val lastSuccessfulState: String,
    /**
     * Deterministic per-run key (`"<runId>:<propertyId>"`). The unique index makes duplicate
     * job creation for the same property inside one run impossible, even with concurrent writers.
     */
    val idempotencyKey: String = "",
    val failedStep: String? = null,
    val analysisId: String? = null,
    val offerId: String? = null,
    val emailMessageId: String? = null,
    val recipientEmail: String? = null,
    /** Consecutive failed attempts for the current step. Reset when a step succeeds. */
    val attempts: Int = 0,
    val maxRetries: Int = 3,
    val lastError: String? = null,
    /** [com.example.domain.automation.FailureKind] name, persisted for auditing. */
    val failureKind: String? = null,
    val blockageReason: String? = null,
    /** Wall-clock time before which a retryable job must not be picked up again. */
    val nextAttemptAt: Long = 0L,
    /** Owner of the execution lease; null when the job is free to be claimed. */
    val leaseOwner: String? = null,
    val leaseExpiresAt: Long = 0L,
    /** How many times crash recovery had to reconcile this job. Protects against crash loops. */
    val recoveryCount: Int = 0,
    val lastRecoveredAt: Long? = null,
    /** First time the engine actually started working this job (real start time). */
    val startedAt: Long? = null,
    /** Set once the job reaches a terminal state. */
    val completedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun state(): JobState = JobState.fromName(currentState)

    fun hasActiveLease(now: Long): Boolean = leaseOwner != null && leaseExpiresAt > now
}

@Entity(
    tableName = "automation_executions",
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["runId"]),
        Index(value = ["status"])
    ]
)
data class AutomationExecutionEntity(
    /** [AutomationEffect.idempotencyKey] - the durable dedupe key of the side effect. */
    @PrimaryKey
    val idempotencyKey: String,
    val jobId: String? = null,
    val runId: Long,
    /** AutomationEffect name. */
    val effect: String,
    /** [EffectStatus] name. */
    val status: String,
    val attempt: Int = 1,
    /** Reference produced by the effect (offer id, message id, ...). */
    val resultRef: String? = null,
    val error: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null
)

@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey
    val id: String = "DEFAULT",
    val maxPurchasePrice: Double = 600000.0,
    val minCashFlow: Double = 300.0,
    val minCapRate: Double = 7.0,
    val minDscr: Double = 1.25,
    val minCashOnCash: Double = 8.0,
    val allowedLocations: String = "Austin, Dallas, Houston, Phoenix, Atlanta, Miami, Chicago",
    val allowedPropertyTypes: String = "Single Family, Multi-Family, Condo, Townhouse",
    val maxRenovationCost: Double = 75000.0,
    val minEstimatedRent: Double = 1500.0,
    val maxRiskScore: Int = 40,
    val offerDiscountPercent: Double = 8.5,
    val scanIntervalMinutes: Int = 15,
    val maxPropertiesPerCycle: Int = 10,
    val maxAnalysesPerRun: Int = 5,
    val maxOffersPerRun: Int = 3,
    val maxEmailsPerRun: Int = 3,
    val maxRetries: Int = 3,
    val autoGenerateOffers: Boolean = true,
    val autoSendOffers: Boolean = false,
    val consecutiveFailureThreshold: Int = 3,
    /** Base delay of the exponential retry backoff. */
    val retryBackoffBaseSeconds: Int = 30,
    val retryBackoffMaxMinutes: Int = 30,
    /** A RUNNING cycle whose heartbeat is older than this is considered killed by the OS. */
    val staleRunTimeoutMinutes: Int = 15,
    /** Hard cap on crash-recovery reconciliations per job (crash-loop protection). */
    val maxRecoveryAttempts: Int = 20,
    /** When false, interrupted jobs are parked instead of being reconciled and resumed. */
    val autoResumeInterruptedJobs: Boolean = true,
    /** Upper bound of jobs handled per cycle, keeps each work request short. */
    val maxJobsPerCycle: Int = 25,
    /** Execution lease held while a single job step is running. */
    val jobLeaseTtlMinutes: Int = 5,
    /** Execution lease held while a cycle is running (renewed by a heartbeat). */
    val cycleLeaseTtlMinutes: Int = 3
)

@Entity(
    tableName = "automation_runs",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["status", "startTime"])
    ]
)
data class AutomationRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Real start time. Immutable for the lifetime of the run. */
    val startTime: Long,
    val endTime: Long? = null,
    val propertiesFound: Int = 0,
    val propertiesAnalyzed: Int = 0,
    val dealsQualified: Int = 0,
    val offersCreated: Int = 0,
    val offersSent: Int = 0,
    val jobsProcessed: Int = 0,
    val jobsRecovered: Int = 0,
    val jobsFailed: Int = 0,
    val jobsBlocked: Int = 0,
    /** [AutomationRunStatus] name. */
    val status: String,
    val summary: String = "",
    /** What requested this cycle: MANUAL, WORKER, PROCESS_START, PERIODIC, OPERATOR_RETRY. */
    val trigger: String = "MANUAL",
    val correlationId: String = "",
    val workerRunAttempt: Int = 0,
    val failureReason: String? = null,
    /** Liveness signal; a stale heartbeat means the process died. */
    val heartbeatAt: Long = startTime
)

@Entity(
    tableName = "automation_logs",
    indices = [
        Index(value = ["runId"]),
        Index(value = ["timestamp"]),
        Index(value = ["level", "timestamp"])
    ]
)
data class AutomationLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: Long? = null,
    val jobId: String? = null,
    val correlationId: String? = null,
    val timestamp: Long,
    val level: String, // "INFO", "WARN", "ERROR", "SUCCESS"
    val tag: String,
    val message: String,
    /** State machine audit trail. */
    val stateBefore: String? = null,
    val stateAfter: String? = null,
    val attempt: Int? = null,
    val durationMs: Long? = null
)

@Entity(tableName = "automation_state")
data class AutomationStateEntity(
    @PrimaryKey
    val id: Int = 1,
    val isEnabled: Boolean = false,
    val currentOperation: String = "Engine Standby",
    val currentPropertyAddress: String = "",
    val currentStage: String = "Standby",
    val successfulJobs: Int = 0,
    val failedJobs: Int = 0,
    val lastError: String? = null,
    val lastSuccessfulAction: String = "None",
    val lastActivityTime: Long = 0L,
    /** Fail-closed emergency stop. Persisted: it survives process death and reboots. */
    val killSwitchEngaged: Boolean = false,
    val killSwitchReason: String? = null,
    val killSwitchEngagedAt: Long? = null,
    val engineStartedAt: Long? = null,
    /** Run currently owning the execution lease. */
    val activeRunId: Long? = null,
    val cycleLeaseOwner: String? = null,
    val cycleLeaseExpiresAt: Long = 0L,
    val lastRecoveryAt: Long? = null,
    val recoveredJobsTotal: Int = 0,
    val lastWorkerEnqueuedAt: Long? = null
)
