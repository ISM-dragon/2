package com.example.domain.propertyurl.pipeline

import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.port.Clock
import java.util.Locale

/**
 * Token bucket rate limiter, one bucket per source.
 *
 * Deterministic by construction (the clock is injected), so tests can assert exact throttling
 * behaviour while production gets per-source pacing that respects the site's capacity — the single
 * most effective way to avoid bot walls in the first place.
 */
class SourceRateLimiter(
    private val clock: Clock,
    private val defaultRequestsPerMinute: Int = 12,
    private val burstAllowance: Int = 1,
    private val overrides: Map<String, Int> = emptyMap()
) {

    private class Bucket(
        var tokens: Double,
        var lastRefillEpochMillis: Long,
        var capacity: Double,
        val refillPerMillis: Double
    )

    private val buckets = LinkedHashMap<String, Bucket>()
    private val lock = Any()

    /** @return wait time in millis (0 = proceed now). */
    fun acquire(sourceId: String): Long {
        val now = clock.nowEpochMillis()
        val perMinute = (overrides[sourceId.lowercase(Locale.US)] ?: defaultRequestsPerMinute).coerceAtLeast(1)
        val capacity = 1.0 + burstAllowance

        synchronized(lock) {
            val bucket = buckets.getOrPut(sourceId.lowercase(Locale.US)) {
                Bucket(tokens = capacity, lastRefillEpochMillis = now, capacity = capacity, refillPerMillis = perMinute / 60_000.0)
            }
            val elapsed = (now - bucket.lastRefillEpochMillis).coerceAtLeast(0)
            bucket.tokens = (bucket.tokens + elapsed * bucket.refillPerMillis).coerceAtMost(capacity)
            bucket.lastRefillEpochMillis = now

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return 0L
            }
            val deficit = 1.0 - bucket.tokens
            val waitMillis = Math.ceil(deficit / bucket.refillPerMillis).toLong().coerceAtLeast(1L)
            // Consume the token in advance: the caller is expected to wait, and concurrent callers
            // must not all see the same available token.
            bucket.tokens -= 1.0
            return waitMillis
        }
    }

    fun reset() = synchronized(lock) { buckets.clear() }

    fun snapshot(): Map<String, Double> = synchronized(lock) { buckets.mapValues { it.value.tokens } }
}

/**
 * Per-source circuit breaker.
 *
 * A portal that served a bot wall or a 429 will keep doing so for a while; hammering it wastes
 * battery, burns the IP reputation and can escalate the block. After [failureThreshold] consecutive
 * source-level failures the source is opened for [openDurationMillis]; then a single half-open trial
 * decides whether the circuit closes again.
 */
class SourceHealthTracker(
    private val clock: Clock,
    private val failureThreshold: Int = 3,
    private val openDurationMillis: Long = 10 * 60 * 1000,
    private val halfOpenTrials: Int = 1
) {

    enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    data class Snapshot(
        val state: CircuitState,
        val consecutiveFailures: Int,
        val openedAtEpochMillis: Long?,
        val halfOpenRemaining: Int
    )

    private class Entry(
        var consecutiveFailures: Int = 0,
        var state: CircuitState = CircuitState.CLOSED,
        var openedAt: Long? = null,
        var trialsRemaining: Int = 1
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val lock = Any()

    /** @return null when the call may proceed, otherwise the failure to surface. */
    fun beforeFetch(sourceId: String): SourceFailure? {
        val key = sourceId.lowercase(Locale.US)
        val now = clock.nowEpochMillis()
        synchronized(lock) {
            val entry = entries.getOrPut(key) { Entry() }
            when (entry.state) {
                CircuitState.CLOSED -> return null
                CircuitState.OPEN -> {
                    val openedAt = entry.openedAt ?: now
                    if (now - openedAt >= openDurationMillis) {
                        entry.state = CircuitState.HALF_OPEN
                        entry.trialsRemaining = halfOpenTrials
                        entry.trialsRemaining--
                        return null
                    }
                    val waitSeconds = ((openDurationMillis - (now - openedAt)) / 1000).coerceAtLeast(1)
                    return SourceFailure(
                        kind = com.example.domain.propertyurl.model.SourceFailureKind.SOURCE_DISABLED,
                        message = "circuit open for '$key' after ${entry.consecutiveFailures} consecutive failures",
                        sourceId = key,
                        retryAfterSeconds = waitSeconds,
                        diagnostics = mapOf("retryAfter" to waitSeconds.toString()),
                        occurredAtEpochMillis = now
                    )
                }
                CircuitState.HALF_OPEN -> {
                    if (entry.trialsRemaining > 0) {
                        entry.trialsRemaining--
                        return null
                    }
                    return SourceFailure(
                        kind = com.example.domain.propertyurl.model.SourceFailureKind.HTTP_RATE_LIMITED,
                        message = "half-open trial already in flight for '$key'",
                        sourceId = key,
                        retryAfterSeconds = 5,
                        occurredAtEpochMillis = now
                    )
                }
            }
        }
    }

    fun recordSuccess(sourceId: String) {
        val key = sourceId.lowercase(Locale.US)
        synchronized(lock) {
            val entry = entries[key] ?: return
            entry.consecutiveFailures = 0
            entry.state = CircuitState.CLOSED
            entry.openedAt = null
            entry.trialsRemaining = halfOpenTrials
        }
    }

    fun recordFailure(sourceId: String, failure: SourceFailure) {
        val key = sourceId.lowercase(Locale.US)
        synchronized(lock) {
            val entry = entries.getOrPut(key) { Entry() }
            val counts = failure.isSourceLevel ||
                failure.kind == com.example.domain.propertyurl.model.SourceFailureKind.HTTP_FORBIDDEN
            entry.consecutiveFailures = if (counts) entry.consecutiveFailures + 1 else 0
            if (counts && entry.consecutiveFailures >= failureThreshold) {
                entry.state = CircuitState.OPEN
                entry.openedAt = clock.nowEpochMillis()
            }
        }
    }

    fun state(sourceId: String): CircuitState = snapshot(sourceId).state

    fun snapshot(sourceId: String): Snapshot {
        val key = sourceId.lowercase(Locale.US)
        synchronized(lock) {
            val entry = entries[key] ?: return Snapshot(CircuitState.CLOSED, 0, null, halfOpenTrials)
            return Snapshot(entry.state, entry.consecutiveFailures, entry.openedAt, entry.trialsRemaining)
        }
    }

    fun reset(sourceId: String? = null) = synchronized(lock) {
        if (sourceId == null) entries.clear() else entries.remove(sourceId.lowercase(Locale.US))
    }
}
