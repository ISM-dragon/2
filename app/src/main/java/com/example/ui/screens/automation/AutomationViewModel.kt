package com.example.ui.screens.automation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.AutomationJobEntity
import com.example.data.local.entity.AutomationLogEntity
import com.example.data.local.entity.AutomationRuleEntity
import com.example.domain.automation.AutomationStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AutomationUiState(
    val status: AutomationStatus = AutomationStatus.IDLE,
    val currentTaskDescription: String = "Engine Standby",
    val rules: AutomationRuleEntity = AutomationRuleEntity(),
    val activityLogs: List<AutomationLogEntity> = emptyList(),
    val errorLogs: List<AutomationLogEntity> = emptyList(),
    val jobs: List<AutomationJobEntity> = emptyList(),
    val apiSlots: List<ApiConfigurationEntity> = emptyList(),
    val selectedTab: Int = 0, // 0: Rules, 1: Jobs, 2: Activity Log, 3: Error Log, 4: API Status
    val selectedJob: AutomationJobEntity? = null
)

class AutomationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val automationRepo = app.automationRepository
    private val configRepo = app.configRepository

    private val _selectedTab = MutableStateFlow(0)
    private val _selectedJob = MutableStateFlow<AutomationJobEntity?>(null)

    private val engineInfoFlow = combine(
        automationRepo.engineStatus,
        automationRepo.currentTaskDescription
    ) { status, task -> Pair(status, task) }

    private val logsFlow = combine(
        automationRepo.recentLogs,
        automationRepo.errorLogs
    ) { recent, errors -> Pair(recent, errors) }

    val uiState: StateFlow<AutomationUiState> = combine(
        engineInfoFlow,
        automationRepo.rulesFlow,
        logsFlow,
        automationRepo.allJobs,
        configRepo.apiConfigs,
        _selectedTab,
        _selectedJob
    ) { engineInfo, rules, logs, jobsList, apiConfigs, tab, selJob ->
        AutomationUiState(
            status = engineInfo.first,
            currentTaskDescription = engineInfo.second,
            rules = rules ?: AutomationRuleEntity(),
            activityLogs = logs.first,
            errorLogs = logs.second,
            jobs = jobsList,
            apiSlots = apiConfigs,
            selectedTab = tab,
            selectedJob = selJob
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AutomationUiState()
    )

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun selectJob(job: AutomationJobEntity?) {
        _selectedJob.value = job
    }

    fun startAutomation() {
        automationRepo.startAutomation()
    }

    fun stopAutomation() {
        automationRepo.stopAutomation()
    }

    fun globalKillSwitch() {
        automationRepo.globalKillSwitch()
    }

    fun updateRules(newRules: AutomationRuleEntity) {
        viewModelScope.launch {
            automationRepo.saveRules(newRules)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            automationRepo.clearLogs()
        }
    }
}

