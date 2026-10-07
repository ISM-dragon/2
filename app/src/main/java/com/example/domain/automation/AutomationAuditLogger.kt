package com.example.domain.automation

import com.example.data.local.dao.AutomationDao
import com.example.data.local.entity.AutomationJobEntity
import com.example.data.local.entity.AutomationLogEntity
import com.example.data.local.entity.JobState

/**
 * Structured audit trail of the execution system.
 *
 * Every state transition, retry decision, lease takeover, kill switch action and operator
 * intervention is written here with the job/run/correlation ids plus before/after states.
 * Audit writes must never break execution, so failures are swallowed (the execution path is
 * already durable in `automation_jobs` / `automation_runs`).
 */
class AutomationAuditLogger(
    private val dao: AutomationDao,
    private val clock: AutomationClock = SystemWallClock
) {
    suspend fun info(tag: String, message: String, runId: Long? = null, jobId: String? = null, correlationId: String? = null) =
        write("INFO", tag, message, runId, jobId, correlationId)

    suspend fun success(tag: String, message: String, runId: Long? = null, jobId: String? = null, correlationId: String? = null) =
        write("SUCCESS", tag, message, runId, jobId, correlationId)

    suspend fun warn(tag: String, message: String, runId: Long? = null, jobId: String? = null, correlationId: String? = null) =
        write("WARN", tag, message, runId, jobId, correlationId)

    suspend fun error(tag: String, message: String, runId: Long? = null, jobId: String? = null, correlationId: String? = null) =
        write("ERROR", tag, message, runId, jobId, correlationId)

    suspend fun transition(
        job: AutomationJobEntity,
        from: JobState,
        to: JobState,
        runId: Long?,
        correlationId: String?,
        detail: String
    ) {
        write(
            level = if (to.isFailure || to == JobState.BLOCKED) "WARN" else "INFO",
            tag = "STATE_TRANSITION",
            message = "[${job.jobId}] $from -> $to ($detail)",
            runId = runId,
            jobId = job.jobId,
            correlationId = correlationId,
            stateBefore = from.name,
            stateAfter = to.name,
            attempt = job.attempts
        )
    }

    suspend fun retryScheduled(job: AutomationJobEntity, runId: Long?, correlationId: String?, delayMs: Long) {
        write(
            level = "WARN",
            tag = "RETRY_SCHEDULED",
            message = "[${job.jobId}] attempt ${job.attempts}/${job.maxRetries} failed at " +
                "${job.failedStep ?: "step"}; next attempt in ${delayMs / 1000}s (${job.lastError ?: "unknown error"})",
            runId = runId,
            jobId = job.jobId,
            correlationId = correlationId,
            stateBefore = null,
            stateAfter = job.currentState,
            attempt = job.attempts
        )
    }

    private suspend fun write(
        level: String,
        tag: String,
        message: String,
        runId: Long?,
        jobId: String?,
        correlationId: String?,
        stateBefore: String? = null,
        stateAfter: String? = null,
        attempt: Int? = null,
        durationMs: Long? = null
    ) {
        runCatching {
            dao.insertLog(
                AutomationLogEntity(
                    runId = runId,
                    jobId = jobId,
                    correlationId = correlationId,
                    timestamp = clock.now(),
                    level = level,
                    tag = tag,
                    message = message,
                    stateBefore = stateBefore,
                    stateAfter = stateAfter,
                    attempt = attempt,
                    durationMs = durationMs
                )
            )
        }
    }
}
