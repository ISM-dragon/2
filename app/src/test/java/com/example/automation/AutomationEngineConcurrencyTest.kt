package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.AutomationRunStatus
import com.example.data.local.entity.AutomationStateEntity
import com.example.data.local.entity.JobState
import com.example.domain.automation.CycleOutcome
import com.example.domain.automation.CycleRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Concurrency protection: a cycle must run once, a job must be executed by exactly one worker, and
 * an abandoned lease must be reclaimable. The guarantees come from conditional SQL updates, so they
 * are verified against a real database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationEngineConcurrencyTest {

    private lateinit var harness: EngineTestHarness
    private lateinit var secondEngine: EngineTestHarness

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        harness = EngineTestHarness(context, instanceId = "engine-a")
        harness.seedRules(testRules(autoSendOffers = true))
        harness.source.bundles = listOf(testBundle(id = "prop-1"))
        secondEngine = EngineTestHarness(
            context = context,
            instanceId = "engine-b",
            database = harness.database,
            ownsDatabase = false
        )
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // ------------------------------------------------------------------ cycle lease

    @Test
    fun `a second engine cannot start a cycle while the lease is held`() = runBlocking {
        harness.dao.saveAutomationState(
            AutomationStateEntity(cycleLeaseOwner = "engine-a", cycleLeaseExpiresAt = harness.clock.now() + 60_000L)
        )

        val outcome = secondEngine.engine.executeCycle(CycleRequest())

        assertTrue(outcome is CycleOutcome.Skipped)
        val skipped = outcome as CycleOutcome.Skipped
        assertNotNull("the scheduler must be told when to come back", skipped.retryAfterMs)
        assertEquals("no run may be created while another lease is live", 0, harness.runs().size)
    }

    @Test
    fun `an abandoned cycle lease is reclaimed`() = runBlocking {
        harness.dao.saveAutomationState(
            AutomationStateEntity(cycleLeaseOwner = "engine-dead", cycleLeaseExpiresAt = harness.clock.now() - 1_000L)
        )

        val outcome = secondEngine.engine.executeCycle(CycleRequest())

        assertTrue("an expired lease belongs to the new worker: $outcome", outcome is CycleOutcome.Completed)
        assertEquals(1, harness.runs().size)
    }

    @Test
    fun `concurrent cycles on the same database elect exactly one winner`() = runBlocking {
        val results = listOf(
            async(Dispatchers.Default) { harness.engine.executeCycle(CycleRequest()) },
            async(Dispatchers.Default) { secondEngine.engine.executeCycle(CycleRequest()) }
        ).awaitAll()

        // Invariants that must hold no matter how the two workers interleave.
        assertTrue("at least one cycle must win: $results", results.any { it is CycleOutcome.Completed })
        results.forEach { assertFalse("no terminal cycle failure expected: $it", it is CycleOutcome.Terminal) }
        assertTrue("at most one cycle may be skipped while the lease is live", results.count { it is CycleOutcome.Skipped } <= 1)
        assertEquals("the property must be processed once", 1, harness.offers.generateCalls)
        assertEquals("exactly one email may leave the device", 1, harness.offers.sendAttempts)
        assertEquals(JobState.SENT, harness.jobs().single().state())

        val runs = harness.runs()
        assertTrue("only cycle winners may create runs", runs.size in 1..2)
        assertTrue("no run may be left RUNNING", runs.all { it.status != AutomationRunStatus.RUNNING })
    }

    // ------------------------------------------------------------------ job lease

    @Test
    fun `a job leased by a live worker is not processed by another engine`() = runBlocking {
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-LEASED",
                state = JobState.OFFER_READY,
                lastSuccessful = JobState.OFFER_READY,
                offerId = "OFFER-1",
                leaseOwner = "engine-a",
                leaseExpiresAt = harness.clock.now() + 120_000L
            )
        )
        harness.offers.offers["OFFER-1"] = testOffer(id = "OFFER-1", propertyId = "prop-1", status = "READY")
        harness.source.bundles = emptyList()

        secondEngine.engine.executeCycle(CycleRequest())

        assertEquals("a live lease must never be stolen", 0, harness.offers.sendAttempts)
        assertEquals(JobState.OFFER_READY, harness.dao.getJobById("JOB-LEASED")!!.state())
        assertEquals("engine-a", harness.dao.getJobById("JOB-LEASED")!!.leaseOwner)
    }

    @Test
    fun `an abandoned job lease is taken over and the work completes once`() = runBlocking {
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-ABANDONED",
                state = JobState.OFFER_READY,
                lastSuccessful = JobState.OFFER_READY,
                offerId = "OFFER-1",
                leaseOwner = "engine-dead",
                leaseExpiresAt = harness.clock.now() - 5_000L
            )
        )
        harness.offers.offers["OFFER-1"] = testOffer(id = "OFFER-1", propertyId = "prop-1", status = "READY")
        harness.source.bundles = emptyList()

        secondEngine.engine.executeCycle(CycleRequest())

        assertEquals(1, harness.offers.sendAttempts)
        assertEquals(JobState.SENT, harness.dao.getJobById("JOB-ABANDONED")!!.state())
    }

    @Test
    fun `two engines racing for the same abandoned job produce a single delivery`() = runBlocking {
        harness.dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-RACE",
                state = JobState.OFFER_READY,
                lastSuccessful = JobState.OFFER_READY,
                offerId = "OFFER-1",
                leaseOwner = "engine-dead",
                leaseExpiresAt = harness.clock.now() - 5_000L
            )
        )
        harness.offers.offers["OFFER-1"] = testOffer(id = "OFFER-1", propertyId = "prop-1", status = "READY")
        harness.source.bundles = emptyList()

        val results = listOf(
            async(Dispatchers.Default) { harness.engine.executeCycle(CycleRequest()) },
            async(Dispatchers.Default) { secondEngine.engine.executeCycle(CycleRequest()) }
        ).awaitAll()

        // The cycle and job leases serialize them; whichever order they run in, delivery happens once.
        assertTrue(results.any { it is CycleOutcome.Completed })
        assertTrue(results.none { it is CycleOutcome.Terminal })
        assertEquals("a single email despite two engines", 1, harness.offers.sendAttempts)
        assertEquals(JobState.SENT, harness.dao.getJobById("JOB-RACE")!!.state())
    }

    @Test
    fun `the compare and swap rejects a transition written by another writer`() = runBlocking {
        harness.dao.insertOrUpdateJob(testJob(jobId = "JOB-CAS", state = JobState.ANALYZED))

        val moved = harness.dao.casJobState(
            jobId = "JOB-CAS",
            expectedStates = listOf(JobState.ANALYZING.name),
            newState = JobState.ANALYZED.name,
            now = harness.clock.now()
        )

        assertEquals("a stale expectation must lose the race", 0, moved)
        assertEquals(JobState.ANALYZED, harness.dao.getJobById("JOB-CAS")!!.state())
    }
}
