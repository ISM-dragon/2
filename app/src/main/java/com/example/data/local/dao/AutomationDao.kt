package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automation_rules WHERE id = 'DEFAULT' LIMIT 1")
    fun getRulesFlow(): Flow<AutomationRuleEntity?>

    @Query("SELECT * FROM automation_rules WHERE id = 'DEFAULT' LIMIT 1")
    suspend fun getRules(): AutomationRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRules(rules: AutomationRuleEntity)

    // Runs
    @Query("SELECT * FROM automation_runs ORDER BY startTime DESC")
    fun getAllRuns(): Flow<List<AutomationRunEntity>>

    @Query("SELECT * FROM automation_runs ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatestRun(): AutomationRunEntity?

    @Insert
    suspend fun insertRun(run: AutomationRunEntity): Long

    @Update
    suspend fun updateRun(run: AutomationRunEntity)

    // Logs
    @Query("SELECT * FROM automation_logs ORDER BY timestamp DESC LIMIT 300")
    fun getRecentLogs(): Flow<List<AutomationLogEntity>>

    @Query("SELECT * FROM automation_logs WHERE level = 'ERROR' ORDER BY timestamp DESC LIMIT 100")
    fun getErrorLogs(): Flow<List<AutomationLogEntity>>

    @Insert
    suspend fun insertLog(log: AutomationLogEntity)

    @Query("DELETE FROM automation_logs")
    suspend fun clearLogs()

    // Persistent Automation State
    @Query("SELECT * FROM automation_state WHERE id = 1 LIMIT 1")
    fun getAutomationStateFlow(): Flow<AutomationStateEntity?>

    @Query("SELECT * FROM automation_state WHERE id = 1 LIMIT 1")
    suspend fun getAutomationState(): AutomationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAutomationState(state: AutomationStateEntity)

    // Jobs
    @Query("SELECT * FROM automation_jobs ORDER BY updatedAt DESC LIMIT 200")
    fun getAllJobsFlow(): Flow<List<AutomationJobEntity>>

    @Query("SELECT * FROM automation_jobs WHERE jobId = :jobId LIMIT 1")
    suspend fun getJobById(jobId: String): AutomationJobEntity?

    @Query("SELECT * FROM automation_jobs WHERE propertyId = :propertyId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getJobByPropertyId(propertyId: String): AutomationJobEntity?

    @Query("SELECT * FROM automation_jobs WHERE currentState IN ('ANALYZING', 'QUALIFYING', 'OFFER_GENERATION', 'VALIDATING_SEND', 'SENDING')")
    suspend fun getActiveJobs(): List<AutomationJobEntity>

    @Query("SELECT * FROM automation_jobs WHERE currentState = 'FAILED_RETRYABLE' AND attempts < maxRetries")
    suspend fun getRetryableJobs(): List<AutomationJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateJob(job: AutomationJobEntity)
}
