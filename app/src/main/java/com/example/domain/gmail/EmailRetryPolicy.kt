package com.example.domain.gmail

import com.example.data.local.entity.OfferEmailFailureKind
import kotlin.math.min
import kotlin.math.pow

/** A conservative retry policy: retry only responses known to have been rejected before delivery. */
object EmailRetryPolicy {
    const val DEFAULT_MAX_ATTEMPTS = 5
    const val BASE_BACKOFF_MILLIS = 30_000L
    const val MAX_BACKOFF_MILLIS = 6 * 60 * 60 * 1000L

    fun nextAttemptAt(
        failureKind: GmailFailureKind?,
        attemptCount: Int,
        now: Long,
        serverRetryAfterMillis: Long? = null,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    ): Long? {
        if (failureKind != GmailFailureKind.RETRYABLE_REJECTED || attemptCount >= maxAttempts.coerceAtLeast(1)) {
            return null
        }
        val exponent = (attemptCount - 1).coerceAtLeast(0).coerceAtMost(20)
        val backoff = min(
            (BASE_BACKOFF_MILLIS * 2.0.pow(exponent.toDouble())).toLong(),
            MAX_BACKOFF_MILLIS
        )
        val requestedDelay = maxOf(backoff, serverRetryAfterMillis ?: 0L)
            .coerceIn(BASE_BACKOFF_MILLIS, MAX_BACKOFF_MILLIS)
        return now + requestedDelay
    }

    fun storageFailureKind(kind: GmailFailureKind?): String = when (kind) {
        GmailFailureKind.VALIDATION -> OfferEmailFailureKind.VALIDATION
        GmailFailureKind.AUTHENTICATION -> OfferEmailFailureKind.AUTHENTICATION
        GmailFailureKind.RETRYABLE_REJECTED -> OfferEmailFailureKind.RETRYABLE_REJECTED
        GmailFailureKind.PERMANENT_REJECTED -> OfferEmailFailureKind.PERMANENT_REJECTED
        GmailFailureKind.DELIVERY_UNKNOWN, null -> OfferEmailFailureKind.DELIVERY_UNKNOWN
    }
}
