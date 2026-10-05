package com.example.data.repository

import com.example.data.local.dao.AutomationDao
import com.example.data.local.entity.AutomationLogEntity
import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.AutomationRunEntity
import com.example.data.local.entity.AutomationStateEntity
import com.example.domain.automation.AutomationEngine
import com.example.domain.automation.AutomationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class AutomationRepository(
    private val automationDao: AutomationDao,
    private val automationEngine: AutomationEngine
) {
    val engineStatus: StateFlow<AutomationStatus> = automationEngine.status
    val currentTaskDescription: StateFlow<String> = automationEngine.currentTaskDescription

    val rulesFlow: Flow<AutomationRuleEntity?> = automationDao.getRulesFlow()
    val allRuns: Flow<List<AutomationRunEntity>> = automationDao.getAllRuns()
    val allJobs: Flow<List<com.example.data.local.entity.AutomationJobEntity>> = automationDao.getAllJobsFlow()
    val recentLogs: Flow<List<AutomationLogEntity>> = automationDao.getRecentLogs()
    val errorLogs: Flow<List<AutomationLogEntity>> = automationDao.getErrorLogs()
    val automationState: Flow<AutomationStateEntity?> = automationDao.getAutomationStateFlow()

    suspend fun getRules(): AutomationRuleEntity = withContext(Dispatchers.IO) {
        automationDao.getRules() ?: AutomationRuleEntity()
    }

    suspend fun saveRules(rules: AutomationRuleEntity) = withContext(Dispatchers.IO) {
        automationDao.insertOrUpdateRules(rules)
    }

    suspend fun seedDefaultsIfEmpty() = withContext(Dispatchers.IO) {
        if (automationDao.getRules() == null) {
            automationDao.insertOrUpdateRules(AutomationRuleEntity())
        }
        if (automationDao.getAutomationState() == null) {
            automationDao.saveAutomationState(AutomationStateEntity())
        }
    }

    fun startAutomation() {
        automationEngine.startAutomation()
    }

    fun stopAutomation() {
        automationEngine.stopAutomation("Operator stop requested")
    }

    fun globalKillSwitch() {
        automationEngine.globalKillSwitch()
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        automationDao.clearLogs()
    }
}
