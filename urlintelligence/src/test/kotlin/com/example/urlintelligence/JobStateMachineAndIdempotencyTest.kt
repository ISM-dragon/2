package com.example.urlintelligence

import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.idempotency.IdempotencyKey
import com.example.urlintelligence.idempotency.IdempotencyState
import com.example.urlintelligence.idempotency.IdempotencyStore
import com.example.urlintelligence.idempotency.InMemoryIdempotencyStore
import com.example.urlintelligence.job.ImportJobEvent
import com.example.urlintelligence.job.ImportJobState
import com.example.urlintelligence.job.InMemoryPropertyImportJobStore
import com.example.urlintelligence.job.PropertyImportJob
import com.example.urlintelligence.job.TransitionResult
import com.example.urlintelligence.resolver.PropertyImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyImportJobStateMachineTest {

    private val clock = TestClock()
    private val key = IdempotencyKey("test-key")

    private fun job(): PropertyImportJob =
        PropertyImportJob.create("https://zillow.com/homedetails/1_zpid", key, "job-1", 3, clock.now())

    private fun PropertyImportJob.fire(
        event: ImportJobEvent,
        patch: (PropertyImportJob) -> PropertyImportJob = { it }
    ): PropertyImportJob {
        val result = transition(event, clock.now(), null, patch)
        assertTrue("expected $event to be accepted from $state: $result", result is TransitionResult.Accepted)
        return (result as TransitionResult.Accepted).job
    }

    @Test
    fun `happy path walks the full pipeline`() {
        var job = job()
        assertEquals(ImportJobState.CREATED, job.state)

        job = job.fire(ImportJobEvent.START_VALIDATION)
        assertEquals(ImportJobState.VALIDATING, job.state)

        job = job.fire(ImportJobEvent.VALIDATION_SUCCEEDED) { it.copy(canonicalUrl = "https://zillow.com/x/1_zpid") }
        assertEquals(ImportJobState.DETECTING, job.state)

        job = job.fire(ImportJobEvent.SOURCE_DETECTED) { it.copy(sourceId = "zillow", sourcePropertyId = "1") }
        assertEquals(ImportJobState.DISPATCHING, job.state)

        job = job.fire(ImportJobEvent.DISPATCHED)
        assertEquals(ImportJobState.FETCHING, job.state)

        job = job.fire(ImportJobEvent.FETCH_SUCCEEDED)
        assertEquals(ImportJobState.PARSING, job.state)

        job = job.fire(ImportJobEvent.PARSE_SUCCEEDED)
        assertEquals(ImportJobState.NORMALIZING, job.state)

        job = job.fire(ImportJobEvent.NORMALIZE_SUCCEEDED)
        assertEquals(ImportJobState.SUCCEEDED, job.state)
        assertTrue(job.state.isTerminal)
        assertTrue(job.state.isSuccessful)
        // One transition per accepted event; creation itself is not a transition.
        assertEquals(7, job.transitions.size)
        assertEquals("zillow", job.sourceId)
        assertEquals(ImportJobState.CREATED, job.transitions.first().from)
        assertEquals(ImportJobState.SUCCEEDED, job.transitions.last().to)
    }

    @Test
    fun `partial success ends in a distinct terminal state`() {
        var job = job()
            .fire(ImportJobEvent.START_VALIDATION)
            .fire(ImportJobEvent.VALIDATION_SUCCEEDED)
            .fire(ImportJobEvent.SOURCE_DETECTED)
            .fire(ImportJobEvent.DISPATCHED)
            .fire(ImportJobEvent.FETCH_SUCCEEDED)
            .fire(ImportJobEvent.PARSE_SUCCEEDED)
            .fire(ImportJobEvent.NORMALIZE_PARTIAL)
        assertEquals(ImportJobState.PARTIALLY_SUCCEEDED, job.state)
        assertTrue(job.state.isTerminal)
        assertTrue(job.state.isSuccessful)
    }

    @Test
    fun `retry loop moves through the retry state`() {
        var job = job()
            .fire(ImportJobEvent.START_VALIDATION)
            .fire(ImportJobEvent.VALIDATION_SUCCEEDED)
            .fire(ImportJobEvent.SOURCE_DETECTED)
            .fire(ImportJobEvent.DISPATCHED)
        assertEquals(ImportJobState.FETCHING, job.state)

        job = job.fire(ImportJobEvent.RETRY_SCHEDULED) { it.copy(attempt = 1) }
        assertEquals(ImportJobState.RETRY_SCHEDULED, job.state)

        job = job.fire(ImportJobEvent.RETRY_STARTED) { it.copy(attempt = 2) }
        assertEquals(ImportJobState.FETCHING, job.state)
        assertEquals(2, job.attempt)
    }

    @Test
    fun `illegal transitions are rejected and never mutate the job`() {
        val job = job()
        val result = job.transition(ImportJobEvent.FETCH_SUCCEEDED, clock.now())
        assertTrue(result is TransitionResult.Rejected)
        val rejected = result as TransitionResult.Rejected
        assertEquals(ImportJobState.CREATED, rejected.state)
        assertEquals(ImportJobState.CREATED, rejected.job.state)
        assertTrue(rejected.job.transitions.isEmpty())
    }

    @Test
    fun `every state except the terminals disallows nothing unexpected`() {
        ImportJobState.values().forEach { state ->
            val allowed = PropertyImportJob.TRANSITION_TABLE[state]?.keys ?: emptySet()
            if (state.isTerminal) {
                assertTrue("terminal state $state must not allow work events",
                    allowed.none { it == ImportJobEvent.FETCH_SUCCEEDED || it == ImportJobEvent.PARSE_SUCCEEDED })
            } else {
                assertTrue("state $state must allow CANCEL", allowed.contains(ImportJobEvent.CANCEL))
            }
        }
    }

    @Test
    fun `terminal state only allows a reset`() {
        val failed = job().fire(ImportJobEvent.START_VALIDATION).let {
            val result = it.transition(ImportJobEvent.VALIDATION_FAILED, clock.now()) { j ->
                j.copy(failure = SourceFailure.InvalidUrl("bad"))
            }
            (result as TransitionResult.Accepted).job
        }
        assertEquals(ImportJobState.FAILED, failed.state)
        assertNotNull(failed.failure)
        assertEquals(0, failed.allowedEvents().count { it != ImportJobEvent.RESET && it != ImportJobEvent.START_VALIDATION })

        val reset = failed.fire(ImportJobEvent.RESET)
        assertEquals(ImportJobState.CREATED, reset.state)
    }

    @Test
    fun `cancel is allowed from every working state`() {
        val states = listOf(
            ImportJobState.CREATED, ImportJobState.VALIDATING, ImportJobState.DETECTING,
            ImportJobState.DISPATCHING, ImportJobState.FETCHING, ImportJobState.RETRY_SCHEDULED,
            ImportJobState.PARSING, ImportJobState.NORMALIZING
        )
        states.forEach { state ->
            val job = job().copy(state = state)
            assertTrue("$state should allow CANCEL", job.canHandle(ImportJobEvent.CANCEL))
            assertEquals(ImportJobState.CANCELLED, job.fire(ImportJobEvent.CANCEL).state)
        }
    }

    @Test
    fun `job store indexes by id and idempotency key`() {
        val store = InMemoryPropertyImportJobStore()
        val job = job()
        store.save(job)
        assertEquals(job, store.findById("job-1"))
        assertEquals(job, store.findByIdempotencyKey(key))
        assertEquals(1, store.size())

        store.save(job.copy(state = ImportJobState.FAILED))
        assertEquals(ImportJobState.FAILED, store.findById("job-1")!!.state)
        assertEquals(1, store.size())
        assertEquals(1, store.findByState(ImportJobState.FAILED).size)
    }

    @Test
    fun `job summary is compact and log safe`() {
        val summary = job().copy(sourceId = "zillow").summary()
        assertTrue(summary.contains("job-1"))
        assertTrue(summary.contains("state=CREATED"))
        assertTrue(summary.contains("source=zillow"))
    }
}

class IdempotencyTest {

    @Test
    fun `keys are derived from the url and are stable`() {
        val first = IdempotencyKey.forUrl("https://www.zillow.com/homedetails/1_zpid")
        val second = IdempotencyKey.forUrl("https://www.zillow.com/homedetails/1_zpid")
        val third = IdempotencyKey.forUrl("https://www.zillow.com/homedetails/2_zpid")
        assertEquals(first, second)
        assertFalse(first == third)
        assertTrue(first.value.startsWith("property-url:"))
        assertTrue(first.value.length > 20)
    }

    @Test
    fun `canonical property id prefers the source id but falls back to the url hash`() {
        assertEquals(
            "zillow:12345678",
            IdempotencyKey.canonicalPropertyId("zillow", "12345678", "https://zillow.com/x")
        )
        val hashed = IdempotencyKey.canonicalPropertyId("zillow", null, "https://zillow.com/x")
        assertTrue(hashed.startsWith("zillow:url-hash:"))
        assertEquals(hashed, IdempotencyKey.canonicalPropertyId("zillow", null, "https://zillow.com/x"))
        assertFalse(hashed == IdempotencyKey.canonicalPropertyId("zillow", null, "https://zillow.com/y"))
    }

    @Test
    fun `reservations are exclusive`() {
        val store: IdempotencyStore<String> = InMemoryIdempotencyStore<String>(TestClock())
        val key = IdempotencyKey("k")
        val record = com.example.urlintelligence.idempotency.IdempotencyRecord<String>(
            key = key,
            state = IdempotencyState.IN_FLIGHT,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L
        )
        assertTrue(store.putIfAbsent(key, record))
        assertFalse(store.putIfAbsent(key, record))
        assertEquals(IdempotencyState.IN_FLIGHT, store.get(key)!!.state)
    }

    @Test
    fun `completing stores the value and releasing frees the slot`() {
        val store: IdempotencyStore<PropertyImportResult> = InMemoryIdempotencyStore(TestClock())
        val key = IdempotencyKey("k2")
        store.putIfAbsent(
            key,
            com.example.urlintelligence.idempotency.IdempotencyRecord(key, IdempotencyState.IN_FLIGHT, 0L, 0L)
        )
        val failure = SourceFailure.Network("down")
        val value = PropertyImportResult.Failure(failure, "job-1", 1, 10L)
        store.complete(key, value)
        assertEquals(IdempotencyState.COMPLETED, store.get(key)!!.state)
        assertEquals(value, store.get(key)!!.value)

        store.release(key)
        assertNull(store.get(key))
        assertEquals(0, store.size())
    }

    @Test
    fun `expired records are purged`() {
        val clock = TestClock(1_000L)
        val store: IdempotencyStore<String> = InMemoryIdempotencyStore(clock, ttlMillis = 100L)
        val key = IdempotencyKey("k3")
        store.putIfAbsent(
            key,
            com.example.urlintelligence.idempotency.IdempotencyRecord(key, IdempotencyState.IN_FLIGHT, clock.now(), clock.now())
        )
        assertEquals(1, store.size())
        clock.advance(500L)
        assertNull(store.get(key))
        assertEquals(0, store.size())
    }
}
