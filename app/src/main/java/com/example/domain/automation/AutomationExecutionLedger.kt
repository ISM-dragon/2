package com.example.domain.automation

import com.example.data.local.dao.AutomationDao
import com.example.data.local.entity.AutomationEffect
import com.example.data.local.entity.AutomationExecutionEntity
import com.example.data.local.entity.EffectStatus

/** Decision returned by [AutomationExecutionLedger.begin]. */
data class EffectDecision(
    val allowed: Boolean,
    val alreadySucceeded: Boolean,
    val resultRef: String? = null,
    val attempt: Int = 1
)

/**
 * Durable idempotency ledger for side effects (offer generation, delivery, ...).
 *
 * Every irreversible operation is keyed deterministically ([AutomationEffect.idempotencyKey]).
 * Before performing it the engine asks the ledger:
 *  - `allowed = false, alreadySucceeded = true` -> the effect already happened, skip it and
 *    reconcile the job from the recorded result instead of repeating the side effect.
 *  - `allowed = true` -> the effect is ours to perform; the ledger row stays IN_PROGRESS until
 *    the outcome is written. A row still IN_PROGRESS after a crash is *not* trusted, but the
 *    domain-level dedupe keys (property/offer status) still protect against duplicates.
 */
class AutomationExecutionLedger(
    private val dao: AutomationDao,
    private val clock: AutomationClock = SystemWallClock
) {
    suspend fun begin(
        effect: String,
        subjectId: String,
        jobId: String?,
        runId: Long
    ): EffectDecision {
        val key = AutomationEffect.idempotencyKey(effect, subjectId)
        val now = clock.now()
        val existing = runCatching { dao.getExecution(key) }.getOrNull()
        if (existing?.status == EffectStatus.SUCCEEDED.name) {
            return EffectDecision(allowed = false, alreadySucceeded = true, resultRef = existing.resultRef, attempt = existing.attempt)
        }
        val attempt = (existing?.attempt ?: 0) + 1
        runCatching {
            dao.insertOrUpdateExecution(
                AutomationExecutionEntity(
                    idempotencyKey = key,
                    jobId = jobId,
                    runId = runId,
                    effect = effect,
                    status = EffectStatus.IN_PROGRESS.name,
                    attempt = attempt,
                    resultRef = existing?.resultRef,
                    error = null,
                    startedAt = now,
                    finishedAt = null
                )
            )
        }
        return EffectDecision(allowed = true, alreadySucceeded = false, attempt = attempt)
    }

    suspend fun markSucceeded(effect: String, subjectId: String, jobId: String?, runId: Long, resultRef: String?) {
        val key = AutomationEffect.idempotencyKey(effect, subjectId)
        val now = clock.now()
        val existing = runCatching { dao.getExecution(key) }.getOrNull()
        runCatching {
            dao.insertOrUpdateExecution(
                AutomationExecutionEntity(
                    idempotencyKey = key,
                    jobId = jobId ?: existing?.jobId,
                    runId = runId,
                    effect = effect,
                    status = EffectStatus.SUCCEEDED.name,
                    attempt = existing?.attempt ?: 1,
                    resultRef = resultRef ?: existing?.resultRef,
                    error = null,
                    startedAt = existing?.startedAt ?: now,
                    finishedAt = now
                )
            )
        }
    }

    suspend fun markFailed(effect: String, subjectId: String, jobId: String?, runId: Long, error: String?) {
        val key = AutomationEffect.idempotencyKey(effect, subjectId)
        val now = clock.now()
        val existing = runCatching { dao.getExecution(key) }.getOrNull()
        runCatching {
            dao.insertOrUpdateExecution(
                AutomationExecutionEntity(
                    idempotencyKey = key,
                    jobId = jobId ?: existing?.jobId,
                    runId = runId,
                    effect = effect,
                    status = EffectStatus.FAILED.name,
                    attempt = existing?.attempt ?: 1,
                    resultRef = existing?.resultRef,
                    error = error,
                    startedAt = existing?.startedAt ?: now,
                    finishedAt = now
                )
            )
        }
    }

    suspend fun snapshot(effect: String, subjectId: String): AutomationExecutionEntity? =
        runCatching { dao.getExecution(AutomationEffect.idempotencyKey(effect, subjectId)) }.getOrNull()
}
