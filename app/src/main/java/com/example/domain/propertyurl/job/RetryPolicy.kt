package com.example.domain.propertyurl.job

import com.example.domain.propertyurl.model.Retryability
import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureKind
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Outcome of a retry decision. */
sealed interface RetryDecision {

    data class Retry(
        val delayMillis: Long,
        val nextAttemptAtEpochMillis: Long,
        val attempt: Int,
        val reason: String
    ) : RetryDecision

    data class GiveUp(val attempt: Int, val reason: String) : RetryDecision
}

/**
 * Exponential backoff with jitter, Retry-After support and per-kind cool-downs.
 *
 * Deterministic by construction: the jitter source is injected, so tests assert exact delays while
 * production gets randomized jitter (thundering herd protection when a source comes back up).
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 2_000,
    val maxDelayMillis: Long = 10 * 60_000,
    /** Multiplier applied to failures that need a cool-down (rate limits, bot walls, outages). */
    val cooldownMultiplier: Double = 6.0,
    val jitterRatio: Double = 0.25,
    val respectRetryAfter: Boolean = true,
    val maxRetryAfterMillis: Long = 30 * 60_000,
    /** Kinds that are never retried, regardless of their taxonomy default. */
    val neverRetryKinds: Set<SourceFailureKind> = setOf(
        SourceFailureKind.INVALID_URL,
        SourceFailureKind.SOURCE_NOT_SUPPORTED,
        SourceFailureKind.POLICY_DISALLOWED,
        SourceFailureKind.HTTP_NOT_FOUND,
        SourceFailureKind.HTTP_GONE,
        SourceFailureKind.PAYWALL,
        SourceFailureKind.LOGIN_REQUIRED,
        SourceFailureKind.CANCELLED
    )
) {

    /**
     * @param attempt 1-based attempt number that just failed.
     * @param jitterFraction source of randomness in `[-1, 1]`; defaults to uniform random.
     */
    fun decide(
        attempt: Int,
        failure: SourceFailure,
        nowEpochMillis: Long,
        jitterFraction: () -> Double = { (Random.nextDouble() * 2.0) - 1.0 }
    ): RetryDecision {
        if (failure.kind in neverRetryKinds) {
            return RetryDecision.GiveUp(attempt, "${failure.kind.name} is never retried")
        }
        if (failure.retryability == Retryability.PERMANENT) {
            return RetryDecision.GiveUp(attempt, "${failure.kind.name} is permanent")
        }
        if (attempt >= maxAttempts) {
            return RetryDecision.GiveUp(attempt, "attempts exhausted ($attempt/$maxAttempts)")
        }

        val retryAfter = failure.retryAfterSeconds?.takeIf { it > 0 }?.times(1000)
        if (respectRetryAfter && retryAfter != null) {
            val capped = min(retryAfter, maxRetryAfterMillis)
            return RetryDecision.Retry(
                delayMillis = capped,
                nextAttemptAtEpochMillis = nowEpochMillis + capped,
                attempt = attempt,
                reason = "honouring Retry-After (${capped / 1000}s)"
            )
        }

        val exponential = baseDelayMillis * 2.0.pow((attempt - 1).toDouble())
        val withCoolDown = if (failure.retryability == Retryability.RETRYABLE_AFTER_COOLDOWN) {
            exponential * cooldownMultiplier
        } else {
            exponential
        }
        val bounded = min(withCoolDown, maxDelayMillis.toDouble())
        val jittered = (bounded * (1.0 + jitterRatio * jitterFraction().coerceIn(-1.0, 1.0))).coerceAtLeast(0.0)
        val delay = jittered.toLong()

        return RetryDecision.Retry(
            delayMillis = delay,
            nextAttemptAtEpochMillis = nowEpochMillis + delay,
            attempt = attempt,
            reason = "attempt $attempt/$maxAttempts failed with ${failure.kind.name}"
        )
    }

    companion object {
        /** Aggressive profile used by tests and by single-shot user initiated imports. */
        val SINGLE_ATTEMPT = RetryPolicy(maxAttempts = 1)

        /** Default production profile: 3 attempts, cool-down aware. */
        val DEFAULT = RetryPolicy()
    }
}
