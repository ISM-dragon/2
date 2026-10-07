package com.example.domain.propertyurl.store

import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.SourceFailureCategory
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.job.InMemoryPropertyImportJobStore
import com.example.domain.propertyurl.job.PropertyImportJob
import com.example.domain.propertyurl.job.PropertyImportJobState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Job persistence is what makes imports survive process death and what makes retries deterministic
 * across app restarts. The codec is versioned so an app update never loses in-flight work.
 */
class JobPersistenceTest {

    private fun sampleJob(
        id: String = "job-1",
        state: PropertyImportJobState = PropertyImportJobState.FETCH_RETRY_SCHEDULED
    ) = PropertyImportJob(
        jobId = id,
        rawInput = "https://www.zillow.com/homedetails/1/20451237_zpid/",
        normalizedUrl = "https://zillow.com/homedetails/1/20451237_zpid",
        sourceId = "zillow",
        adapterId = "zillow-html",
        externalListingId = "20451237",
        idempotencyKey = "zillow:20451237",
        state = state,
        attempts = 1,
        maxAttempts = 3,
        createdAtEpochMillis = 1_700_000_000_000L,
        updatedAtEpochMillis = 1_700_000_005_000L,
        nextAttemptAtEpochMillis = 1_700_000_010_000L,
        lastFailure = com.example.domain.propertyurl.model.SourceFailure(
            kind = SourceFailureKind.HTTP_RATE_LIMITED,
            message = "HTTP 429",
            httpStatus = 429,
            retryAfterSeconds = 30,
            sourceId = "zillow",
            adapterId = "zillow-html",
            url = "https://zillow.com/homedetails/1/20451237_zpid",
            occurredAtEpochMillis = 1_700_000_005_000L
        ),
        failures = listOf(
            com.example.domain.propertyurl.model.SourceFailure(
                kind = SourceFailureKind.TIMEOUT,
                message = "connect timed out",
                sourceId = "zillow",
                occurredAtEpochMillis = 1_700_000_001_000L
            )
        ),
        warnings = listOf(
            ImportWarning(
                code = ImportWarning.WarningCode.MISSING_CRITICAL_FIELD,
                message = "price missing",
                field = PropertyField.PRICE_AMOUNT,
                sourceId = "zillow"
            )
        ),
        usedParsers = listOf("schema-org-json-ld@1.2.0", "meta-and-title@1.1.0"),
        requestId = "req-1",
        transitions = listOf(
            com.example.domain.propertyurl.job.JobStateTransition(
                from = PropertyImportJobState.QUEUED,
                to = PropertyImportJobState.FETCHING,
                atEpochMillis = 1_700_000_000_500L,
                reasonCode = "ATTEMPT_STARTED"
            ),
            com.example.domain.propertyurl.job.JobStateTransition(
                from = PropertyImportJobState.FETCHING,
                to = PropertyImportJobState.FETCH_RETRY_SCHEDULED,
                atEpochMillis = 1_700_000_005_000L,
                reasonCode = "RETRY_SCHEDULED",
                detail = "attempt 1"
            )
        )
    )

    @Test
    fun `codec round-trips a job without losing audit information`() {
        val original = sampleJob()

        val decoded = JobCodec.decode(JobCodec.encode(original))

        assertNotNull(decoded)
        assertEquals(original.jobId, decoded!!.jobId)
        assertEquals(original.state, decoded.state)
        assertEquals(original.attempts, decoded.attempts)
        assertEquals(original.maxAttempts, decoded.maxAttempts)
        assertEquals(original.normalizedUrl, decoded.normalizedUrl)
        assertEquals(original.externalListingId, decoded.externalListingId)
        assertEquals(original.idempotencyKey, decoded.idempotencyKey)
        assertEquals(original.nextAttemptAtEpochMillis, decoded.nextAttemptAtEpochMillis)
        assertEquals(original.createdAtEpochMillis, decoded.createdAtEpochMillis)
        assertEquals(SourceFailureKind.HTTP_RATE_LIMITED, decoded.lastFailure?.kind)
        assertEquals(429, decoded.lastFailure?.httpStatus)
        assertNotNull(decoded.lastFailure?.retryAfterSeconds)
        assertEquals(30L, decoded.lastFailure?.retryAfterSeconds)
        assertEquals(SourceFailureCategory.HTTP, decoded.lastFailure?.category)
        assertEquals(1, decoded.failures.size)
        assertEquals(1, decoded.warnings.size)
        assertEquals(PropertyField.PRICE_AMOUNT, decoded.warnings.first().field)
        assertEquals(original.usedParsers, decoded.usedParsers)
        assertEquals(2, decoded.transitions.size)
        assertEquals("RETRY_SCHEDULED", decoded.transitions.last().reasonCode)
        assertEquals("attempt 1", decoded.transitions.last().detail)
    }

    @Test
    fun `codec redacts URL credentials and sensitive query values before persistence`() {
        val password = "unit-test-embedded-password"
        val apiKey = "unit-test-query-api-key"
        val job = sampleJob().copy(
            rawInput = "https://listing-user:$password@www.zillow.com/homedetails/1/?api-key=$apiKey"
        )

        val encoded = JobCodec.encode(job)
        val decoded = JobCodec.decode(encoded)

        assertFalse(encoded.contains(password))
        assertFalse(encoded.contains("listing-user"))
        assertFalse(encoded.contains(apiKey))
        assertNotNull(decoded)
        assertFalse(decoded!!.rawInput.contains(password))
        assertFalse(decoded.rawInput.contains(apiKey))
    }

    @Test
    fun `codec is versioned and refuses to invent a job from junk`() {
        val encoded = JobCodec.encode(sampleJob())
        assertTrue("payload must carry its schema version", encoded.contains("schemaVersion"))
        assertNull(JobCodec.decode("not json at all"))
        assertNull(JobCodec.decode("{}"))
        assertNull(JobCodec.decode("""{"schemaVersion":1}"""))
    }

    @Test
    fun `codec survives unknown enum values from a newer schema`() {
        val encoded = JobCodec.encode(sampleJob()).replace("\"zillow\"", "\"brand_new_portal\"")

        val decoded = JobCodec.decode(encoded)

        assertNotNull("an unknown source id must not break recovery", decoded)
        assertEquals("brand_new_portal", decoded!!.sourceId)
    }

    @Test
    fun `in-memory store indexes by key and keeps the newest job per key`() = runBlocking {
        val store = InMemoryPropertyImportJobStore()
        val older = sampleJob(id = "job-old", state = PropertyImportJobState.SUCCEEDED)
            .copy(updatedAtEpochMillis = 1_700_000_000_000L)
        val newer = sampleJob(id = "job-new", state = PropertyImportJobState.SUCCEEDED)
            .copy(updatedAtEpochMillis = 1_700_000_100_000L)

        store.save(older)
        store.save(newer)

        assertEquals("job-new", store.findLatestByKey("zillow:20451237")?.jobId)
        assertEquals(newer, store.findById("job-new"))
        assertNull(store.findById("nope"))
        assertEquals(2, store.count())
    }

    @Test
    fun `store evicts old finished jobs but never in-flight ones`() = runBlocking {
        val store = InMemoryPropertyImportJobStore(maxJobs = 5, retainTerminalJobs = 1)
        repeat(4) { index ->
            store.save(sampleJob(id = "finished-$index", state = PropertyImportJobState.SUCCEEDED))
        }
        val running = sampleJob(id = "running", state = PropertyImportJobState.FETCHING)
        store.save(running)

        assertNotNull("in-flight work must survive eviction", store.findById("running"))
        assertTrue("the store stays bounded", store.count() <= 5)
    }

    @Test
    fun `file store persists jobs across instances and clears on demand`() = runBlocking {
        val directory = File(System.getProperty("java.io.tmpdir"), "property-intel-store-test-${System.nanoTime()}")
        try {
            val first = FilePropertyImportJobStore(directory)
            first.save(sampleJob(id = "in-flight", state = PropertyImportJobState.FETCH_RETRY_SCHEDULED))
            first.save(sampleJob(id = "finished", state = PropertyImportJobState.SUCCEEDED))

            val second = FilePropertyImportJobStore(directory)
            val reloaded = second.findById("in-flight")
            assertNotNull("a new instance must see persisted jobs", reloaded)
            assertEquals(PropertyImportJobState.FETCH_RETRY_SCHEDULED, reloaded!!.state)
            assertEquals(1_700_000_010_000L, reloaded.nextAttemptAtEpochMillis)

            second.deleteOlderThan(1_700_000_020_000L)
            assertNull("terminal cleanup must remove finished jobs", second.findById("finished"))
            assertNotNull("cleanup must never delete work that is still in flight", second.findById("in-flight"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
