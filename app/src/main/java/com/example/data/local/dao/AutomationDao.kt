package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

/**
 * Persistence layer of the automation execution system.
 *
 * Two guarantees are enforced at the SQL level (not in application code):
 *  - **Concurrency**: cycle leases and job leases are acquired with conditional `UPDATE`s and
 *    their affected row count decides the winner. No read-then-write races.
 *  - **Idempotency**: state transitions use compare-and-swap (`casJobState`), jobs carry a unique
 *    `idempotencyKey`, and side effects are recorded in `automation_executions`.
 */
@Dao
interface AutomationDao {
    // ------------------------------------------------------------------ rules

    @Query("SELECT * FROM automation_rules WHERE id = 'DEFAULT' LIMIT 1")
    fun getRulesFlow(): Flow<AutomationRuleEntity?>

    @Query("SELECT * FROM automation_rules WHERE id = 'DEFAULT' LIMIT 1")
    suspend fun getRules(): AutomationRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRules(rules: AutomationRuleEntity)

    // ------------------------------------------------------------------ runs

    @Query("SELECT * FROM automation_runs ORDER BY startTime DESC")
    fun getAllRuns(): Flow<List<AutomationRunEntity>>

    @Query("SELECT * FROM automation_runs ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatestRun(): AutomationRunEntity?

    @Query("SELECT * FROM automation_runs WHERE id = :runId LIMIT 1")
    suspend fun getRunById(runId: Long): AutomationRunEntity?

    @Query("SELECT * FROM automation_runs WHERE status = :status ORDER BY startTime ASC")
    suspend fun getRunsByStatus(status: String): List<AutomationRunEntity>

    /** RUNNING rows whose heartbeat stopped - i.e. the process was killed. */
    @Query("SELECT * FROM automation_runs WHERE status = 'RUNNING' AND heartbeatAt < :staleBefore ORDER BY startTime ASC")
    suspend fun getStaleRuns(staleBefore: Long): List<AutomationRunEntity>

    @Insert
    suspend fun insertRun(run: AutomationRunEntity): Long

    @Update
    suspend fun updateRun(run: AutomationRunEntity)

    @Query("UPDATE automation_runs SET heartbeatAt = :heartbeatAt WHERE id = :runId AND status = 'RUNNING'")
    suspend fun touchRunHeartbeat(runId: Long, heartbeatAt: Long): Int

    /**
     * Closes a run only while it is still RUNNING, so an operator stop / kill switch always wins
     * over a late completion write from an in-flight worker.
     */
    @Query(
        "UPDATE automation_runs SET status = :status, endTime = :endTime, summary = :summary, " +
            "failureReason = :failureReason WHERE id = :runId AND status = 'RUNNING'"
    )
    suspend fun closeRun(
        runId: Long,
        status: String,
        endTime: Long,
        summary: String,
        failureReason: String?
    ): Int

    @Query(
        "UPDATE automation_runs SET propertiesFound = :propertiesFound, propertiesAnalyzed = :propertiesAnalyzed, " +
            "dealsQualified = :dealsQualified, offersCreated = :offersCreated, offersSent = :offersSent, " +
            "jobsProcessed = :jobsProcessed, jobsRecovered = :jobsRecovered, jobsFailed = :jobsFailed, " +
            "jobsBlocked = :jobsBlocked, heartbeatAt = :now WHERE id = :runId"
    )
    suspend fun updateRunStats(
        runId: Long,
        propertiesFound: Int,
        propertiesAnalyzed: Int,
        dealsQualified: Int,
        offersCreated: Int,
        offersSent: Int,
        jobsProcessed: Int,
        jobsRecovered: Int,
        jobsFailed: Int,
        jobsBlocked: Int,
        now: Long
    ): Int

    // ------------------------------------------------------------------ audit logs

    @Query("SELECT * FROM automation_logs ORDER BY timestamp DESC LIMIT 300")
    fun getRecentLogs(): Flow<List<AutomationLogEntity>>

    @Query("SELECT * FROM automation_logs WHERE level = 'ERROR' ORDER BY timestamp DESC LIMIT 100")
    fun getErrorLogs(): Flow<List<AutomationLogEntity>>

    @Query("SELECT * FROM automation_logs WHERE jobId = :jobId ORDER BY timestamp ASC LIMIT 200")
    fun getLogsForJobFlow(jobId: String): Flow<List<AutomationLogEntity>>

    @Query("SELECT * FROM automation_logs WHERE runId = :runId ORDER BY timestamp ASC LIMIT 500")
    suspend fun getLogsForRun(runId: Long): List<AutomationLogEntity>

    @Insert
    suspend fun insertLog(log: AutomationLogEntity)

    @Query("DELETE FROM automation_logs")
    suspend fun clearLogs()

    // ------------------------------------------------------------------ persistent engine state

    @Query("SELECT * FROM automation_state WHERE id = 1 LIMIT 1")
    fun getAutomationStateFlow(): Flow<AutomationStateEntity?>

    @Query("SELECT * FROM automation_state WHERE id = 1 LIMIT 1")
    suspend fun getAutomationState(): AutomationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAutomationState(state: AutomationStateEntity)

    @Query(
        "UPDATE automation_state SET currentOperation = :operation, currentPropertyAddress = :address, " +
            "currentStage = :stage, lastActivityTime = :now WHERE id = 1"
    )
    suspend fun updateProgress(operation: String, address: String, stage: String, now: Long): Int

    @Query("UPDATE automation_state SET isEnabled = :enabled, lastActivityTime = :now WHERE id = 1")
    suspend fun setEnabled(enabled: Boolean, now: Long): Int

    @Query(
        "UPDATE automation_state SET successfulJobs = successfulJobs + 1, lastSuccessfulAction = :action, " +
            "lastActivityTime = :now WHERE id = 1"
    )
    suspend fun registerSuccess(action: String, now: Long): Int

    @Query(
        "UPDATE automation_state SET failedJobs = failedJobs + 1, lastError = :error, " +
            "lastActivityTime = :now WHERE id = 1"
    )
    suspend fun registerFailure(error: String?, now: Long): Int

    @Query(
        "UPDATE automation_state SET killSwitchEngaged = :engaged, killSwitchReason = :reason, " +
            "killSwitchEngagedAt = :since, lastActivityTime = :now WHERE id = 1"
    )
    suspend fun setKillSwitch(engaged: Boolean, reason: String?, since: Long?, now: Long): Int

    @Query("UPDATE automation_state SET engineStartedAt = :startedAt, activeRunId = :runId, lastActivityTime = :now WHERE id = 1")
    suspend fun markEngineStarted(startedAt: Long, runId: Long?, now: Long): Int

    @Query("UPDATE automation_state SET lastRecoveryAt = :now, recoveredJobsTotal = recoveredJobsTotal + :recovered WHERE id = 1")
    suspend fun registerRecovery(now: Long, recovered: Int): Int

    @Query("UPDATE automation_state SET lastWorkerEnqueuedAt = :now WHERE id = 1")
    suspend fun markCycleEnqueued(now: Long): Int

    /**
     * Cycle lease acquisition. Succeeds (1 row) only when no live lease is held by somebody else.
     * This is the cross-process mutual exclusion used by the WorkManager worker.
     */
    @Query(
        "UPDATE automation_state SET cycleLeaseOwner = :owner, cycleLeaseExpiresAt = :leaseExpiresAt, " +
            "activeRunId = :runId, lastActivityTime = :now " +
            "WHERE id = 1 AND (cycleLeaseOwner IS NULL OR cycleLeaseExpiresAt <= :now)"
    )
    suspend fun tryAcquireCycleLease(owner: String, runId: Long, leaseExpiresAt: Long, now: Long): Int

    /** Heartbeat: only the current lease owner can extend the lease. */
    @Query("UPDATE automation_state SET cycleLeaseExpiresAt = :leaseExpiresAt, lastActivityTime = :now WHERE id = 1 AND cycleLeaseOwner = :owner")
    suspend fun renewCycleLease(owner: String, leaseExpiresAt: Long, now: Long): Int

    @Query(
        "UPDATE automation_state SET cycleLeaseOwner = NULL, cycleLeaseExpiresAt = 0, activeRunId = NULL, " +
            "lastActivityTime = :now WHERE id = 1 AND cycleLeaseOwner = :owner"
    )
    suspend fun releaseCycleLease(owner: String, now: Long): Int

    // ------------------------------------------------------------------ jobs

    @Query("SELECT * FROM automation_jobs ORDER BY updatedAt DESC LIMIT 300")
    fun getAllJobsFlow(): Flow<List<AutomationJobEntity>>

    @Query("SELECT * FROM automation_jobs WHERE jobId = :jobId LIMIT 1")
    suspend fun getJobById(jobId: String): AutomationJobEntity?

    @Query("SELECT * FROM automation_jobs WHERE propertyId = :propertyId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getJobByPropertyId(propertyId: String): AutomationJobEntity?

    @Query("SELECT * FROM automation_jobs WHERE idempotencyKey = :key LIMIT 1")
    suspend fun getJobByIdempotencyKey(key: String): AutomationJobEntity?

    @Query(
        "SELECT * FROM automation_jobs WHERE propertyId = :propertyId AND currentState IN (:states) " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun getJobByPropertyIdInStates(propertyId: String, states: List<String>): AutomationJobEntity?

    @Query("SELECT * FROM automation_jobs WHERE currentState IN (:states) ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getJobsInStates(states: List<String>, limit: Int): List<AutomationJobEntity>

    /** Retryable jobs whose backoff has elapsed. */
    @Query(
        "SELECT * FROM automation_jobs WHERE currentState = 'FAILED_RETRYABLE' AND attempts < maxRetries " +
            "AND nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit"
    )
    suspend fun getDueRetryableJobs(now: Long, limit: Int): List<AutomationJobEntity>

    /** Leases abandoned by a dead owner: whoever queries them can safely reclaim them. */
    @Query(
        "SELECT * FROM automation_jobs WHERE currentState IN (:states) AND leaseOwner IS NOT NULL " +
            "AND leaseExpiresAt <= :now ORDER BY updatedAt ASC LIMIT :limit"
    )
    suspend fun getJobsWithExpiredLease(states: List<String>, now: Long, limit: Int): List<AutomationJobEntity>

    @Query("SELECT COUNT(*) FROM automation_jobs WHERE currentState = :state")
    suspend fun countJobsInState(state: String): Int

    @Query("SELECT COUNT(*) FROM automation_jobs WHERE currentState IN (:states)")
    suspend fun countJobsInStates(states: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateJob(job: AutomationJobEntity)

    /**
     * Atomic compare-and-swap of the job state. Returns 0 when another writer already moved the
     * job, in which case the caller must abandon its work (it lost the race).
     */
    @Query("UPDATE automation_jobs SET currentState = :newState, updatedAt = :now WHERE jobId = :jobId AND currentState IN (:expectedStates)")
    suspend fun casJobState(jobId: String, expectedStates: List<String>, newState: String, now: Long): Int

    /** Job lease acquisition; `leaseExpiresAt <= :now` allows reclaiming abandoned leases. */
    @Query(
        "UPDATE automation_jobs SET leaseOwner = :owner, leaseExpiresAt = :leaseExpiresAt, updatedAt = :now " +
            "WHERE jobId = :jobId AND (leaseOwner IS NULL OR leaseExpiresAt <= :now)"
    )
    suspend fun claimJobLease(jobId: String, owner: String, leaseExpiresAt: Long, now: Long): Int

    @Query("UPDATE automation_jobs SET leaseOwner = NULL, leaseExpiresAt = 0, updatedAt = :now WHERE jobId = :jobId AND leaseOwner = :owner")
    suspend fun releaseJobLease(jobId: String, owner: String, now: Long): Int

    @Query("DELETE FROM automation_jobs WHERE currentState IN (:states) AND updatedAt < :before")
    suspend fun pruneJobs(states: List<String>, before: Long): Int

    // ------------------------------------------------------------------ idempotency ledger

    @Query("SELECT * FROM automation_executions WHERE idempotencyKey = :key LIMIT 1")
    suspend fun getExecution(key: String): AutomationExecutionEntity?

    @Query("SELECT * FROM automation_executions WHERE jobId = :jobId ORDER BY startedAt ASC")
    suspend fun getExecutionsForJob(jobId: String): List<AutomationExecutionEntity>

    @Query("SELECT * FROM automation_executions WHERE status = :status LIMIT :limit")
    suspend fun getExecutionsByStatus(status: String, limit: Int): List<AutomationExecutionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateExecution(execution: AutomationExecutionEntity)
}
