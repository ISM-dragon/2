package com.example.domain.propertyurl.job

import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retry and idempotency are what keep the layer polite and cheap: no hammering, no duplicate imports,
 * no lost work. Both are pure functions here, so they can be pinned exactly.
 */
class RetryAndIdempotencyTest {

    private val policy = RetryPolicy.DEFAULT

    private fun failure(kind: SourceFailureKind, retryAfterSeconds: Long? = null) = SourceFailure(
        kind = kind,
        message = kind.name,
        retryAfterSeconds = retryAfterSeconds,
        sourceId = "zillow",
        occurredAtEpochMillis = 1_000
    )

    private val now = 1_700_000_000_000L

    // --- RetryPolicy -------------------------------------------------------------------------------

    @Test
    fun `backoff grows exponentially and stops at the attempt budget`() {
        val first = policy.decide(1, failure(SourceFailureKind.TIMEOUT), now, jitterFraction = { 0.0 })
        val second = policy.decide(2, failure(SourceFailureKind.TIMEOUT), now, jitterFraction = { 0.0 })
        val third = policy.decide(3, failure(SourceFailureKind.TIMEOUT), now, jitterFraction = { 0.0 })

        assertTrue(first is RetryDecision.Retry)
        assertEquals(2_000L, (first as RetryDecision.Retry).delayMillis)
        assertEquals(4_000L, (second as RetryDecision.Retry).delayMillis)
        assertTrue("the last attempt must give up", third is RetryDecision.GiveUp)
        assertEquals(now + 2_000, first.nextAttemptAtEpochMillis)
    }

    @Test
    fun `jitter stays inside the configured ratio`() {
        val low = policy.decide(1, failure(SourceFailureKind.TIMEOUT), now, jitterFraction = { -1.0 }) as RetryDecision.Retry
        val high = policy.decide(1, failure(SourceFailureKind.TIMEOUT), now, jitterFraction = { 1.0 }) as RetryDecision.Retry

        assertTrue("jitter must not produce a negative delay", low.delayMillis >= 0)
        assertTrue(high.delayMillis > low.delayMillis)
        assertTrue("jitter must stay bounded", high.delayMillis <= (2_000 * (1 + policy.jitterRatio)).toLong() + 1)
    }

    @Test
    fun `permanent failures are never retried`() {
        listOf(
            SourceFailureKind.HTTP_NOT_FOUND,
            SourceFailureKind.HTTP_GONE,
            SourceFailureKind.INVALID_URL,
            SourceFailureKind.SOURCE_NOT_SUPPORTED,
            SourceFailureKind.POLICY_DISALLOWED
        ).forEach { kind ->
            val decision = policy.decide(1, failure(kind), now, jitterFraction = { 0.0 })
            assertTrue("$kind must not be retried", decision is RetryDecision.GiveUp)
        }
    }

    @Test
    fun `rate limits and bot walls get a much longer cool-down`() {
        val transient = policy.decide(1, failure(SourceFailureKind.TIMEOUT), now, jitterFraction = { 0.0 }) as RetryDecision.Retry
        val rateLimited = policy.decide(1, failure(SourceFailureKind.HTTP_RATE_LIMITED), now, jitterFraction = { 0.0 }) as RetryDecision.Retry
        val botWall = policy.decide(1, failure(SourceFailureKind.BOT_PROTECTION_CHALLENGE), now, jitterFraction = { 0.0 }) as RetryDecision.Retry

        assertTrue("cool-down must be far longer than a plain timeout", rateLimited.delayMillis > transient.delayMillis * 3)
        assertTrue(botWall.delayMillis > transient.delayMillis * 3)
    }

    @Test
    fun `retry-after header wins over the computed backoff`() {
        val decision = policy.decide(
            1,
            failure(SourceFailureKind.HTTP_RATE_LIMITED, retryAfterSeconds = 120),
            now,
            jitterFraction = { 0.0 }
        ) as RetryDecision.Retry

        assertTrue("the server's own answer must be honoured", decision.delayMillis >= 120_000)
    }

    @Test
    fun `absurd retry-after values are capped`() {
        val decision = policy.decide(
            1,
            failure(SourceFailureKind.HTTP_RATE_LIMITED, retryAfterSeconds = 86_400),
            now,
            jitterFraction = { 0.0 }
        ) as RetryDecision.Retry

        assertTrue("a day-long backoff must be capped", decision.delayMillis <= policy.maxRetryAfterMillis)
    }

    @Test
    fun `single attempt profile never retries`() {
        val decision = RetryPolicy.SINGLE_ATTEMPT.decide(1, failure(SourceFailureKind.TIMEOUT), now, jitterFraction = { 0.0 })
        assertTrue(decision is RetryDecision.GiveUp)
    }

    @Test
    fun `never-retry list can be extended by configuration`() {
        val custom = RetryPolicy(neverRetryKinds = setOf(SourceFailureKind.TIMEOUT))
        assertTrue(custom.decide(1, failure(SourceFailureKind.TIMEOUT), now) is RetryDecision.GiveUp)
    }

    // --- Idempotency -------------------------------------------------------------------------------

    private fun job(
        key: String,
        state: PropertyImportJobState,
        updatedAt: Long = now,
        jobId: String = "job-1"
    ) = PropertyImportJob(
        jobId = jobId,
        rawInput = "https://www.zillow.com/homedetails/1/20451237_zpid/",
        idempotencyKey = key,
        state = state,
        createdAtEpochMillis = updatedAt,
        updatedAtEpochMillis = updatedAt
    )

    @Test
    fun `keys are stable and source scoped`() {
        assertEquals("zillow:20451237", IdempotencyKeys.forListing("zillow", "20451237"))
        assertEquals(
            IdempotencyKeys.forListing("zillow", "20451237"),
            IdempotencyKeys.forListing("ZILLOW", "20451237")
        )
        assertEquals(
            IdempotencyKeys.forDocument("https://zillow.com/homedetails/1/20451237_zpid"),
            IdempotencyKeys.forDocument("https://zillow.com/homedetails/1/20451237_zpid")
        )
        assertTrue(IdempotencyKeys.forDocument("https://zillow.com/x").startsWith("url:"))
    }

    @Test
    fun `property level keys collapse the same address from two portals`() {
        val property = com.example.domain.propertyurl.normalize.CanonicalPropertyMapper(
            com.example.domain.propertyurl.source.SourceRegistry.build(
                com.example.domain.propertyurl.source.SourceCatalog.ALL
            )
        ).map(
            com.example.domain.propertyurl.model.ExtractedFactsBuilder().apply {
                val provenance = com.example.domain.propertyurl.model.Provenance(
                    sourceId = "zillow",
                    method = com.example.domain.propertyurl.model.ProvenanceMethod.STRUCTURED_DATA,
                    confidence = 0.9,
                    extractedAtEpochMillis = 1_000
                )
                addText(com.example.domain.propertyurl.model.PropertyField.ADDRESS_LINE_1, "4127 Oak Hollow Dr", provenance)
                addText(com.example.domain.propertyurl.model.PropertyField.CITY, "Austin", provenance)
                addText(com.example.domain.propertyurl.model.PropertyField.STATE, "TX", provenance)
                addText(com.example.domain.propertyurl.model.PropertyField.POSTAL_CODE, "78745", provenance)
                addNumber(com.example.domain.propertyurl.model.PropertyField.PRICE_AMOUNT, 565_000.0, provenance)
            }.build(),
            (
                com.example.domain.propertyurl.url.PropertyUrlResolver(
                    com.example.domain.propertyurl.source.SourceRegistry.build(
                        com.example.domain.propertyurl.source.SourceCatalog.ALL
                    )
                ).resolve("https://www.zillow.com/homedetails/1/20451237_zpid/") as
                    com.example.domain.propertyurl.url.UrlResolutionResult.Resolved
                ).resolved,
            "zillow"
        ).property

        val key = IdempotencyKeys.forCanonicalProperty(property)
        assertTrue(key.startsWith("prop:"))
        assertEquals("the key must be deterministic", key, IdempotencyKeys.forCanonicalProperty(property))
        assertTrue(
            "market granularity changes the material but stays stable",
            IdempotencyKeys.forCanonicalProperty(property, "city").startsWith("prop:")
        )
    }

    @Test
    fun `a fresh success is reusable inside the window and not outside it`() {
        val idempotency = IdempotencyPolicy(reuseWindowMillis = 6 * 60 * 60 * 1000)
        val success = job("zillow:20451237", PropertyImportJobState.SUCCEEDED, updatedAt = now - 60_000)

        assertTrue(idempotency.isReusable(success, now))
        assertEquals(success, idempotency.findReusable(success, "zillow:20451237", now))
        assertFalse("stale results must be refreshed", idempotency.isReusable(success, now + 7 * 60 * 60 * 1000))
    }

    @Test
    fun `force refresh ignores reusable results`() {
        val idempotency = IdempotencyPolicy()
        val success = job("zillow:20451237", PropertyImportJobState.SUCCEEDED)

        assertNull(idempotency.findReusable(success, "zillow:20451237", now, forceRefresh = true))
    }

    @Test
    fun `a different listing key is never reused`() {
        val idempotency = IdempotencyPolicy()
        val success = job("zillow:20451237", PropertyImportJobState.SUCCEEDED)

        assertNull(idempotency.findReusable(success, "zillow:99999999", now))
    }

    @Test
    fun `partial results are reusable but failures are not`() {
        val idempotency = IdempotencyPolicy()
        assertTrue(idempotency.isReusable(job("k", PropertyImportJobState.PARTIAL_SUCCESS), now))
        assertFalse(
            "by default a failed job is retried, not reused",
            idempotency.isReusable(job("k", PropertyImportJobState.FETCH_FAILED), now)
        )
        assertTrue(
            "opt-in: a recent failure can short-circuit retries",
            IdempotencyPolicy(reuseFailedJobsWithinMillis = 60_000)
                .isReusable(job("k", PropertyImportJobState.FETCH_FAILED, updatedAt = now - 1_000), now)
        )
    }

    @Test
    fun `in-flight jobs are treated as reusable to prevent parallel double imports`() {
        val idempotency = IdempotencyPolicy()
        assertTrue(idempotency.isReusable(job("k", PropertyImportJobState.FETCHING), now))
    }

    @Test
    fun `duplicate suppression chains stay reusable`() {
        val idempotency = IdempotencyPolicy()
        val suppressed = job("k", PropertyImportJobState.DUPLICATE_SUPPRESSED, updatedAt = now - 10 * 24 * 60 * 60 * 1000)

        assertTrue("chained duplicates must not re-fetch", idempotency.isReusable(suppressed, now))
    }
}
