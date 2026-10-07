package com.example.data.repository

import com.example.data.local.dao.AutomationDao
import com.example.data.local.entity.*
import com.example.domain.automation.AutomationEngine
import com.example.domain.automation.AutomationStatus
import com.example.domain.automation.CycleOutcome
import com.example.domain.automation.KillSwitchState
import com.example.domain.automation.RecoveryReport
import com.example.domain.automation.StartResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Surface exposed to the UI: operator intents (start/stop/kill switch/retry) plus the observable
 * execution state. Every mutating call is durable - it goes through the engine, which persists the
 * operator intent before doing anything else.
 */
class AutomationRepository(
    private val automationDao: AutomationDao,
    private val automationEngine: AutomationEngine
) {
    val engineStatus: StateFlow<AutomationStatus> = automationEngine.status
    val currentTaskDescription: StateFlow<String> = automationEngine.currentTaskDescription
    val killSwitch: StateFlow<KillSwitchState> = automationEngine.killSwitch

    val rulesFlow: Flow<AutomationRuleEntity?> = automationDao.getRulesFlow()
    val allRuns: Flow<List<AutomationRunEntity>> = automationDao.getAllRuns()
    val allJobs: Flow<List<AutomationJobEntity>> = automationDao.getAllJobsFlow()
    val recentLogs: Flow<List<AutomationLogEntity>> = automationDao.getRecentLogs()
    val errorLogs: Flow<List<AutomationLogEntity>> = automationDao.getErrorLogs()
    val automationState: Flow<AutomationStateEntity?> = automationDao.getAutomationStateFlow()

    suspend fun getRules(): AutomationRuleEntity = withContext(Dispatchers.IO) {
        automationDao.getRules() ?: AutomationRuleEntity()
    }

    suspend fun saveRules(rules: AutomationRuleEntity) = withContext(Dispatchers.IO) {
        automationDao.insertOrUpdateRules(rules)
        // Re-plan the durable worker so the new scan interval takes effect immediately.
        automationEngine.onRulesChanged(rules.scanIntervalMinutes)
    }

    suspend fun seedDefaultsIfEmpty() = withContext(Dispatchers.IO) {
        if (automationDao.getRules() == null) {
            automationDao.insertOrUpdateRules(AutomationRuleEntity())
        }
        if (automationDao.getAutomationState() == null) {
            automationDao.saveAutomationState(AutomationStateEntity())
        }
    }

    // ---------------------------------------------------------------- operator intents

    suspend fun startAutomation(): StartResult = automationEngine.startAutomation()

    suspend fun stopAutomation() = automationEngine.stopAutomation("Operator stop requested")

    suspend fun globalKillSwitch() = automationEngine.engageKillSwitch()

    suspend fun clearKillSwitch() = automationEngine.clearKillSwitch()

    /** One-shot operator cycle; still serialized through the persisted cycle lease. */
    suspend fun runCycleNow(): CycleOutcome = automationEngine.runCycleNow()

    /** Manual retry of a blocked/failed job. Terminal jobs get a successor job instead. */
    suspend fun retryJob(jobId: String): Boolean = automationEngine.retryJob(jobId)

    suspend fun cancelJob(jobId: String): Boolean = automationEngine.cancelJob(jobId)

    /** On-demand crash reconciliation (also runs automatically at every cycle start). */
    suspend fun reconcileNow(): RecoveryReport = automationEngine.recoverInterruptedWork()

    // ---------------------------------------------------------------- audit trail

    fun logsForJob(jobId: String): Flow<List<AutomationLogEntity>> = automationDao.getLogsForJobFlow(jobId)

    suspend fun executionsForJob(jobId: String): List<AutomationExecutionEntity> =
        withContext(Dispatchers.IO) { automationDao.getExecutionsForJob(jobId) }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        automationDao.clearLogs()
    }
}
