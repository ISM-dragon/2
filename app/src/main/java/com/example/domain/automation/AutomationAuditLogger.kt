package com.example.domain.automation

import com.example.data.local.dao.AutomationDao
import com.example.data.local.entity.AutomationJobEntity
import com.example.data.local.entity.AutomationLogEntity
import com.example.data.local.entity.JobState
import com.example.domain.propertyurl.util.Redaction

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
            // The detail can include arbitrary provider/exception text. Keep audit records to the
            // state-machine event; do not persist payloads, property details, or raw errors.
            message = "Job state changed: $from -> $to.",
            runId = runId,
            jobId = job.jobId,
            correlationId = correlationId,
            stateBefore = from.name,
            stateAfter = to.name,
            attempt = job.attempts
        )
    }

    suspend fun retryScheduled(job: AutomationJobEntity, runId: Long?, correlationId: String?, delayMs: Long) {
        val safeStep = job.failedStep?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "step"
        write(
            level = "WARN",
            tag = "RETRY_SCHEDULED",
            message = "Retry scheduled for $safeStep " +
                "(attempt ${job.attempts}/${job.maxRetries}) after ${delayMs / 1000}s.",
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
                    jobId = safeIdentifier(jobId),
                    correlationId = safeIdentifier(correlationId),
                    timestamp = clock.now(),
                    level = level,
                    tag = safeIdentifier(tag)?.uppercase() ?: "AUDIT",
                    message = Redaction.message(message, maxLength = MAX_AUDIT_MESSAGE_LENGTH),
                    stateBefore = stateBefore,
                    stateAfter = stateAfter,
                    attempt = attempt,
                    durationMs = durationMs
                )
            )
        }
    }

    private fun safeIdentifier(value: String?): String? {
        if (value.isNullOrBlank() || value.length > MAX_AUDIT_IDENTIFIER_LENGTH) return null
        return value.takeIf { it.all { character -> character.isLetterOrDigit() || character in "._:-" } }
    }

    private companion object {
        const val MAX_AUDIT_MESSAGE_LENGTH = 500
        const val MAX_AUDIT_IDENTIFIER_LENGTH = 128
    }
}
