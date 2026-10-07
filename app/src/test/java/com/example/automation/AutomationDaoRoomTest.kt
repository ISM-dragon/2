package com.example.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.dao.AutomationDao
import com.example.data.local.entity.AutomationEffect
import com.example.data.local.entity.AutomationExecutionEntity
import com.example.data.local.entity.AutomationLogEntity
import com.example.data.local.entity.AutomationRunStatus
import com.example.data.local.entity.AutomationStateEntity
import com.example.data.local.entity.EffectStatus
import com.example.data.local.entity.JobState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the persistence primitives the execution system relies on. These are real SQL
 * statements executed against a real (in-memory) Room database - conditional updates, unique
 * indexes, status guards - i.e. the parts that cannot be unit-tested in pure Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationDaoRoomTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: AutomationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.automationDao()
        runBlocking { dao.saveAutomationState(AutomationStateEntity()) }
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------ state transitions

    @Test
    fun `compare and swap only moves the job from an expected state`() = runBlocking {
        dao.insertOrUpdateJob(testJob(jobId = "JOB-1", state = JobState.ANALYZING))

        val rejected = dao.casJobState("JOB-1", listOf(JobState.QUALIFIED.name), JobState.QUALIFIED.name, 10L)
        assertEquals(0, rejected)
        assertEquals(JobState.ANALYZING, dao.getJobById("JOB-1")!!.state())

        val accepted = dao.casJobState("JOB-1", listOf(JobState.ANALYZING.name), JobState.ANALYZED.name, 20L)
        assertEquals(1, accepted)
        val job = dao.getJobById("JOB-1")!!
        assertEquals(JobState.ANALYZED, job.state())
        assertEquals(20L, job.updatedAt)
    }

    @Test
    fun `idempotency key is unique across job rows`() = runBlocking {
        dao.insertOrUpdateJob(testJob(jobId = "JOB-A", idempotencyKey = "1:prop-1"))
        dao.insertOrUpdateJob(testJob(jobId = "JOB-B", idempotencyKey = "1:prop-1"))

        // REPLACE semantics: the unique index keeps a single row for the key.
        assertNull(dao.getJobById("JOB-A"))
        assertNotNull(dao.getJobById("JOB-B"))
        assertEquals("JOB-B", dao.getJobByIdempotencyKey("1:prop-1")!!.jobId)
    }

    // ------------------------------------------------------------------ leases

    @Test
    fun `job lease acquisition is conditional and expires`() = runBlocking {
        dao.insertOrUpdateJob(testJob(jobId = "JOB-LEASE", state = JobState.OFFER_READY))

        assertEquals(1, dao.claimJobLease("JOB-LEASE", "engine-a", 1_000L, 0L))
        assertEquals("a second worker must lose the race", 0, dao.claimJobLease("JOB-LEASE", "engine-b", 2_000L, 500L))
        assertEquals("a third worker must not steal a released lease", 0, dao.releaseJobLease("JOB-LEASE", "engine-b", 600L))
        assertEquals(1, dao.releaseJobLease("JOB-LEASE", "engine-a", 700L))
        assertEquals("after release the job is claimable again", 1, dao.claimJobLease("JOB-LEASE", "engine-b", 5_000L, 800L))
        assertEquals("an expired lease can be taken over", 1, dao.claimJobLease("JOB-LEASE", "engine-c", 9_000L, 6_000L))
        assertEquals("engine-c", dao.getJobById("JOB-LEASE")!!.leaseOwner)
    }

    @Test
    fun `expired leases are queryable for reclamation`() = runBlocking {
        dao.insertOrUpdateJob(testJob(jobId = "JOB-EXPIRED", state = JobState.SENDING, leaseOwner = "dead", leaseExpiresAt = 100L))
        dao.insertOrUpdateJob(testJob(jobId = "JOB-LIVE", state = JobState.SENDING, leaseOwner = "alive", leaseExpiresAt = 10_000L, idempotencyKey = "key-2"))

        val expired = dao.getJobsWithExpiredLease(JobState.names(JobState.IN_FLIGHT), now = 200L, limit = 10)

        assertEquals(listOf("JOB-EXPIRED"), expired.map { it.jobId })
    }

    @Test
    fun `cycle lease is exclusive, renewable only by its owner, and released explicitly`() = runBlocking {
        assertEquals(1, dao.tryAcquireCycleLease("engine-a", 7L, 1_000L, 0L))
        assertEquals("a live lease blocks other engines", 0, dao.tryAcquireCycleLease("engine-b", 8L, 2_000L, 100L))
        assertEquals("only the owner renews", 0, dao.renewCycleLease("engine-b", 3_000L, 200L))
        assertEquals(1, dao.renewCycleLease("engine-a", 3_000L, 200L))

        val state = dao.getAutomationState()!!
        assertEquals("engine-a", state.cycleLeaseOwner)
        assertEquals(7L, state.activeRunId)

        assertEquals(1, dao.releaseCycleLease("engine-a", 300L))
        assertNull(dao.getAutomationState()!!.cycleLeaseOwner)
        assertEquals("an expired lease can be reclaimed", 1, dao.tryAcquireCycleLease("engine-b", 9L, 4_000L, 3_500L))
    }

    // ------------------------------------------------------------------ runs

    @Test
    fun `close run only touches running rows and never rewrites the start time`() = runBlocking {
        val runId = dao.insertRun(testRun(startTime = 111L, heartbeatAt = 111L))

        assertEquals(1, dao.closeRun(runId, AutomationRunStatus.COMPLETED, 999L, "done", null))
        val closed = dao.getRunById(runId)!!
        assertEquals(AutomationRunStatus.COMPLETED, closed.status)
        assertEquals(111L, closed.startTime)
        assertEquals(999L, closed.endTime)

        // An operator stop / kill switch must win over a late completion write.
        assertEquals(0, dao.closeRun(runId, AutomationRunStatus.STOPPED, 1_000L, "late", "late"))
        assertEquals(AutomationRunStatus.COMPLETED, dao.getRunById(runId)!!.status)
    }

    @Test
    fun `stale runs are exactly the running ones without heartbeat`() = runBlocking {
        dao.insertRun(testRun(startTime = 10L, heartbeatAt = 10L))
        dao.insertRun(testRun(startTime = 900L, heartbeatAt = 900L))
        val finished = dao.insertRun(testRun(startTime = 20L, heartbeatAt = 20L))
        dao.closeRun(finished, AutomationRunStatus.COMPLETED, 30L, "done", null)

        val stale = dao.getStaleRuns(staleBefore = 100L)

        assertEquals(1, stale.size)
        assertEquals(10L, stale.single().startTime)
    }

    @Test
    fun `run heartbeat only applies to running rows`() = runBlocking {
        val runId = dao.insertRun(testRun(startTime = 10L, heartbeatAt = 10L))
        assertEquals(1, dao.touchRunHeartbeat(runId, 50L))
        assertEquals(50L, dao.getRunById(runId)!!.heartbeatAt)

        dao.closeRun(runId, AutomationRunStatus.COMPLETED, 60L, "done", null)
        assertEquals(0, dao.touchRunHeartbeat(runId, 70L))
        assertEquals(50L, dao.getRunById(runId)!!.heartbeatAt)
    }

    // ------------------------------------------------------------------ queries used by the cycle

    @Test
    fun `job queries filter by state, attempt budget and due time`() = runBlocking {
        dao.insertOrUpdateJob(testJob(jobId = "JOB-RETRY-DUE", state = JobState.FAILED_RETRYABLE, attempts = 1, nextAttemptAt = 100L, idempotencyKey = "k1"))
        dao.insertOrUpdateJob(testJob(jobId = "JOB-RETRY-WAIT", state = JobState.FAILED_RETRYABLE, attempts = 1, nextAttemptAt = 9_000L, idempotencyKey = "k2"))
        dao.insertOrUpdateJob(testJob(jobId = "JOB-RETRY-EXHAUSTED", state = JobState.FAILED_RETRYABLE, attempts = 3, maxRetries = 3, nextAttemptAt = 100L, idempotencyKey = "k3"))
        dao.insertOrUpdateJob(testJob(jobId = "JOB-SENT", state = JobState.SENT, idempotencyKey = "k4"))

        val due = dao.getDueRetryableJobs(now = 500L, limit = 10)
        assertEquals(listOf("JOB-RETRY-DUE"), due.map { it.jobId })

        val pending = dao.getJobsInStates(JobState.names(JobState.PENDING), 10)
        assertEquals(3, pending.size)
        assertTrue(pending.none { it.jobId == "JOB-SENT" })

        assertEquals(1, dao.countJobsInState(JobState.SENT.name))
        assertEquals(1, dao.countJobsInStates(listOf(JobState.SENT.name)))
        assertEquals(3, dao.countJobsInStates(listOf(JobState.FAILED_RETRYABLE.name)))
    }

    @Test
    fun `property lookups respect active states`() = runBlocking {
        dao.insertOrUpdateJob(testJob(jobId = "JOB-OLD", propertyId = "prop-x", state = JobState.DISQUALIFIED, idempotencyKey = "a"))
        dao.insertOrUpdateJob(
            testJob(
                jobId = "JOB-ACTIVE",
                propertyId = "prop-x",
                state = JobState.QUALIFIED,
                idempotencyKey = "b",
                updatedAt = 1_700_000_001_000L
            )
        )

        val active = dao.getJobByPropertyIdInStates("prop-x", JobState.names(JobState.PENDING + JobState.BLOCKED))
        assertEquals("JOB-ACTIVE", active!!.jobId)
        assertEquals("JOB-ACTIVE", dao.getJobByPropertyId("prop-x")!!.jobId)
    }

    // ------------------------------------------------------------------ ledger & audit

    @Test
    fun `execution ledger upserts by idempotency key`() = runBlocking {
        val key = AutomationEffect.idempotencyKey(AutomationEffect.SEND_OFFER, "OFFER-1")
        dao.insertOrUpdateExecution(
            AutomationExecutionEntity(
                idempotencyKey = key,
                jobId = "JOB-1",
                runId = 3L,
                effect = AutomationEffect.SEND_OFFER,
                status = EffectStatus.IN_PROGRESS.name,
                attempt = 1,
                startedAt = 10L
            )
        )
        dao.insertOrUpdateExecution(
            AutomationExecutionEntity(
                idempotencyKey = key,
                jobId = "JOB-1",
                runId = 3L,
                effect = AutomationEffect.SEND_OFFER,
                status = EffectStatus.SUCCEEDED.name,
                attempt = 1,
                resultRef = "GMAIL-1",
                startedAt = 10L,
                finishedAt = 20L
            )
        )

        val entry = dao.getExecution(key)!!
        assertEquals(EffectStatus.SUCCEEDED.name, entry.status)
        assertEquals("GMAIL-1", entry.resultRef)
        assertEquals(1, dao.getExecutionsForJob("JOB-1").size)
        assertEquals(1, dao.getExecutionsByStatus(EffectStatus.SUCCEEDED.name, 10).size)
    }

    @Test
    fun `audit logs keep the state machine trail per job and run`() = runBlocking {
        val runId = dao.insertRun(testRun(startTime = 10L, heartbeatAt = 10L))
        dao.insertLog(
            AutomationLogEntity(
                runId = runId,
                jobId = "JOB-1",
                correlationId = "CORR",
                timestamp = 11L,
                level = "INFO",
                tag = "STATE_TRANSITION",
                message = "DISCOVERED -> ANALYZING",
                stateBefore = JobState.DISCOVERED.name,
                stateAfter = JobState.ANALYZING.name,
                attempt = 1
            )
        )

        val logs = dao.getLogsForRun(runId)
        assertEquals(1, logs.size)
        assertEquals("DISCOVERED", logs.single().stateBefore)
        assertEquals("ANALYZING", logs.single().stateAfter)
        assertEquals("CORR", logs.single().correlationId)
    }

    @Test
    fun `job pruning only removes finished rows`() = runBlocking {
        dao.insertOrUpdateJob(testJob(jobId = "JOB-OLD-SENT", state = JobState.SENT, updatedAt = 10L, idempotencyKey = "p1"))
        dao.insertOrUpdateJob(testJob(jobId = "JOB-ACTIVE-2", state = JobState.SENDING, updatedAt = 10L, idempotencyKey = "p2"))

        val removed = dao.pruneJobs(JobState.names(JobState.TERMINAL), before = 100L)

        assertEquals(1, removed)
        assertNull(dao.getJobById("JOB-OLD-SENT"))
        assertNotNull(dao.getJobById("JOB-ACTIVE-2"))
    }
}
