package com.example.urlintelligence

import com.example.urlintelligence.failure.BlockReason
import com.example.urlintelligence.failure.FailureCategory
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.failure.SourceFailureClassifier
import com.example.urlintelligence.retry.AttemptOutcome
import com.example.urlintelligence.retry.RecordingSleeper
import com.example.urlintelligence.retry.RetryExecutor
import com.example.urlintelligence.retry.RetryOutcome
import com.example.urlintelligence.retry.RetryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RetryPolicyTest {

    private val policy = RetryPolicy(
        maxAttempts = 4,
        initialDelayMillis = 200,
        maxDelayMillis = 2_000,
        multiplier = 2.0,
        jitterRatio = 0.0
    )

    @Test
    fun `backoff grows exponentially and is capped`() {
        assertEquals(200L, policy.baseDelayForAttempt(1))
        assertEquals(400L, policy.baseDelayForAttempt(2))
        assertEquals(800L, policy.baseDelayForAttempt(3))
        assertEquals(2_000L, policy.baseDelayForAttempt(10))
    }

    @Test
    fun `jitter is bounded and deterministic with a fixed source`() {
        val jittered = policy.copy(jitterRatio = 0.5)
        assertEquals(300L, jittered.delayForAttempt(1) { 1.0 })
        assertEquals(200L, jittered.delayForAttempt(1) { 0.0 })
    }

    @Test
    fun `retryability follows the failure category`() {
        assertTrue(policy.shouldRetry(SourceFailure.Network("dns blip"), 1))
        assertTrue(policy.shouldRetry(SourceFailure.HttpStatus(503, "unavailable"), 2))
        assertFalse(policy.shouldRetry(SourceFailure.ParseError("nope"), 1))
        assertFalse(policy.shouldRetry(SourceFailure.NotFound("gone"), 1))
        assertFalse(policy.shouldRetry(SourceFailure.Blocked(BlockReason.CAPTCHA, "wall"), 1))
    }

    @Test
    fun `attempts are bounded by maxAttempts`() {
        assertFalse(policy.shouldRetry(SourceFailure.Network("still broken"), 4))
    }

    @Test
    fun `server retry after wins over backoff`() {
        val failure = SourceFailure.RateLimited("slow down", 5_000L)
        assertEquals(5_000L, policy.delayFor(failure, 1) { 0.0 })
        assertEquals(400L, policy.delayFor(SourceFailure.HttpStatus(500, "boom"), 2) { 0.0 })
    }

    @Test
    fun `executor retries transient failures until success`() {
        val sleeper = RecordingSleeper()
        val executor = RetryExecutor(policy, TestClock(), sleeper) { 0.0 }
        var calls = 0

        val outcome = runBlocking {
            executor.execute<String> { attempt ->
                calls++
                if (attempt < 3) AttemptOutcome.Failure(SourceFailure.Network("flaky"))
                else AttemptOutcome.Success("ok")
            }
        }

        assertTrue(outcome is RetryOutcome.Success)
        val success = outcome as RetryOutcome.Success
        assertEquals("ok", success.value)
        assertEquals(3, success.attempts)
        assertEquals(3, calls)
        assertEquals(2, success.history.count { !it.succeeded })
        assertEquals(listOf(200L, 400L), sleeper.delays)
    }

    @Test
    fun `executor stops immediately on permanent failures`() {
        val sleeper = RecordingSleeper()
        val executor = RetryExecutor(policy, TestClock(), sleeper) { 0.0 }
        var calls = 0

        val outcome = runBlocking {
            executor.execute<String> { _ ->
                calls++
                AttemptOutcome.Failure(SourceFailure.NotFound("404"))
            }
        }

        assertTrue(outcome is RetryOutcome.Exhausted)
        assertEquals(1, calls)
        assertTrue(sleeper.delays.isEmpty())
        assertEquals(1, (outcome as RetryOutcome.Exhausted).attempts)
    }

    @Test
    fun `executor reports exhaustion with the last failure`() {
        val executor = RetryExecutor(policy.copy(maxAttempts = 2), TestClock(), RecordingSleeper()) { 0.0 }
        val outcome = runBlocking {
            executor.execute<String> { _ -> AttemptOutcome.Failure(SourceFailure.Timeout(1_000L)) }
        }
        assertTrue(outcome is RetryOutcome.Exhausted)
        val exhausted = outcome as RetryOutcome.Exhausted
        assertEquals(2, exhausted.attempts)
        assertEquals("TIMEOUT", exhausted.failure.code)
        assertEquals(2, exhausted.history.size)
    }
}

class SourceFailureClassifierTest {

    @Test
    fun `maps throwables onto typed failures`() {
        assertTrue(SourceFailureClassifier.fromThrowable(SocketTimeoutException()) is SourceFailure.Timeout)
        assertTrue(SourceFailureClassifier.fromThrowable(UnknownHostException()) is SourceFailure.Network)
        assertTrue(SourceFailureClassifier.fromThrowable(IOException("reset")) is SourceFailure.Network)
        assertTrue(SourceFailureClassifier.fromThrowable(IllegalStateException("boom")) is SourceFailure.Unknown)
    }

    @Test
    fun `exception messages are not persisted in classified failures`() {
        val secret = "unit-test-secret-value"
        val failure = SourceFailureClassifier.fromThrowable(IOException("Authorization: Bearer $secret"))
        assertFalse(failure.detail.contains(secret))
        assertEquals("property request failed", failure.detail)
    }

    @Test
    fun `maps http statuses onto categories`() {
        assertEquals(FailureCategory.TRANSIENT, SourceFailureClassifier.fromStatus(500).category)
        assertEquals(FailureCategory.PERMANENT, SourceFailureClassifier.fromStatus(404).category)
        assertEquals(FailureCategory.POLICY, SourceFailureClassifier.fromStatus(403).category)
        assertEquals(FailureCategory.POLICY, SourceFailureClassifier.fromStatus(401).category)
        assertTrue(SourceFailureClassifier.fromStatus(429) is SourceFailure.RateLimited)
    }

    @Test
    fun `reads retry after headers`() {
        val failure = SourceFailureClassifier.fromStatus(429, mapOf("Retry-After" to "7"))
        assertTrue(failure is SourceFailure.RateLimited)
        assertEquals(7_000L, (failure as SourceFailure.RateLimited).retryAfterMillis)
    }

    @Test
    fun `detects anti bot pages served with a 200`() {
        val body = Fixtures.load("blocked_captcha.html")
        val failure = SourceFailureClassifier.detectSoftBlock(200, body)
        assertTrue(failure is SourceFailure.Blocked)
        assertEquals(BlockReason.CAPTCHA, (failure as SourceFailure.Blocked).reason)
        assertEquals(FailureCategory.POLICY, failure.category)
        assertFalse(failure.retryable)
    }

    @Test
    fun `ignores anti bot detection when the status is not 200`() {
        val body = Fixtures.load("blocked_captcha.html")
        assertEquals(null, SourceFailureClassifier.detectSoftBlock(403, body))
    }

    @Test
    fun `retryable flag matches the category`() {
        assertFalse(SourceFailure.ParseError("bad html").retryable)
        assertTrue(SourceFailure.HttpStatus(503, "unavailable").retryable)
        assertFalse(SourceFailure.HttpStatus(400, "bad request").retryable)
    }

    @Test
    fun `log strings contain no payload bodies`() {
        val log = SourceFailure.Network("connection reset by peer").toLogString()
        assertTrue(log.contains("NETWORK"))
        assertTrue(log.contains("category=TRANSIENT"))
        assertFalse(log.contains("<html"))
    }
}
