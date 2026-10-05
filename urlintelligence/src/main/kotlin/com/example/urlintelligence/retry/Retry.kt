package com.example.urlintelligence.retry

import com.example.urlintelligence.failure.FailureCategory
import com.example.urlintelligence.failure.SourceFailure
import kotlin.random.Random

/** Injectable time source: tests pin it, production uses the wall clock. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun now(): Long = System.currentTimeMillis()
        }
    }
}

/** Injectable sleep: tests advance virtually, production really suspends. */
fun interface Sleeper {
    suspend fun delay(millis: Long)

    companion object {
        val DEFAULT: Sleeper = object : Sleeper {
            override suspend fun delay(millis: Long) {
                kotlinx.coroutines.delay(millis)
            }
        }

        /** No-op sleeper for fast unit tests. */
        val NO_OP: Sleeper = object : Sleeper {
            override suspend fun delay(millis: Long) = Unit
        }
    }
}

/**
 * Exponential backoff with jitter.
 *
 * @property maxAttempts total attempts (1 = no retry)
 * @property jitterRatio fraction of the computed delay added as random jitter,
 *                       which prevents synchronized retry storms against one source.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 250L,
    val maxDelayMillis: Long = 5_000L,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.25,
    val retryableCategories: Set<FailureCategory> =
        setOf(FailureCategory.TRANSIENT, FailureCategory.UNCLASSIFIED)
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(initialDelayMillis >= 0) { "initialDelayMillis must be >= 0" }
        require(maxDelayMillis >= 0) { "maxDelayMillis must be >= 0" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within 0.0..1.0" }
    }

    fun shouldRetry(failure: SourceFailure, attempt: Int): Boolean {
        if (attempt >= maxAttempts) return false
        return failure.category in retryableCategories
    }

    /** Base delay for [attempt] (1-based) before jitter. */
    fun baseDelayForAttempt(attempt: Int): Long {
        var delay = initialDelayMillis.toDouble()
        repeat((attempt - 1).coerceAtLeast(0)) { delay *= multiplier }
        return delay.toLong().coerceAtMost(maxDelayMillis)
    }

    fun delayForAttempt(attempt: Int, random: () -> Double = { Random.nextDouble() }): Long {
        val base = baseDelayForAttempt(attempt)
        val jitter = (base * jitterRatio * random().coerceIn(0.0, 1.0)).toLong()
        return (base + jitter).coerceAtMost(maxDelayMillis)
    }

    /** Honors server-provided Retry-After when present, otherwise backoff. */
    fun delayFor(
        failure: SourceFailure,
        attempt: Int,
        random: () -> Double = { Random.nextDouble() }
    ): Long {
        val serverHint = when (failure) {
            is SourceFailure.RateLimited -> failure.retryAfterMillis
            is SourceFailure.HttpStatus -> failure.retryAfterMillis
            else -> null
        }
        return serverHint?.coerceIn(0L, 60_000L) ?: delayForAttempt(attempt, random)
    }
}

/** Result of a single attempt — keeps the retry executor free of exceptions-as-control-flow. */
sealed class AttemptOutcome<out T> {
    data class Success<out T>(val value: T) : AttemptOutcome<T>()
    data class Failure(val failure: SourceFailure) : AttemptOutcome<Nothing>()
}

data class AttemptRecord(
    val attempt: Int,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    val failure: SourceFailure?,
    val nextDelayMillis: Long
) {
    val succeeded: Boolean
        get() = failure == null
}

sealed class RetryOutcome<out T> {
    data class Success<out T>(
        val value: T,
        val attempts: Int,
        val history: List<AttemptRecord>
    ) : RetryOutcome<T>()

    data class Exhausted(
        val failure: SourceFailure,
        val attempts: Int,
        val history: List<AttemptRecord>
    ) : RetryOutcome<Nothing>()
}

/**
 * Runs a suspending operation under [policy].
 *
 * Deterministic in tests: inject [Clock], [Sleeper] and the jitter source.
 */
class RetryExecutor(
    private val policy: RetryPolicy = RetryPolicy(),
    private val clock: Clock = Clock.SYSTEM,
    private val sleeper: Sleeper = Sleeper.DEFAULT,
    private val random: () -> Double = { Random.nextDouble() }
) {

    suspend fun <T> execute(
        /** Called before sleeping, with the delay that will be used. */
        onRetry: suspend (attempt: Int, failure: SourceFailure, delayMillis: Long) -> Unit = { _, _, _ -> },
        block: suspend (attempt: Int) -> AttemptOutcome<T>
    ): RetryOutcome<T> {
        val history = ArrayList<AttemptRecord>()
        var attempt = 1
        while (true) {
            val startedAt = clock.now()
            when (val outcome = block(attempt)) {
                is AttemptOutcome.Success -> {
                    history.add(AttemptRecord(attempt, startedAt, clock.now() - startedAt, null, 0L))
                    return RetryOutcome.Success(outcome.value, attempt, history)
                }
                is AttemptOutcome.Failure -> {
                    val failure = outcome.failure
                    val retryable = policy.shouldRetry(failure, attempt)
                    val delay = if (retryable) policy.delayFor(failure, attempt, random) else 0L
                    history.add(
                        AttemptRecord(
                            attempt = attempt,
                            startedAtEpochMillis = startedAt,
                            durationMillis = clock.now() - startedAt,
                            failure = failure,
                            nextDelayMillis = delay
                        )
                    )
                    if (!retryable) {
                        return RetryOutcome.Exhausted(failure, attempt, history)
                    }
                    onRetry(attempt, failure, delay)
                    if (delay > 0) sleeper.delay(delay)
                    attempt++
                }
            }
        }
    }
}
