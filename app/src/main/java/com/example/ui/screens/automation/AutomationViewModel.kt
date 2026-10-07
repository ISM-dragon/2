package com.example.ui.screens.automation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.AutomationJobEntity
import com.example.data.local.entity.AutomationLogEntity
import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.AutomationRunEntity
import com.example.domain.automation.AutomationStatus
import com.example.domain.automation.KillSwitchState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AutomationUiState(
    val status: AutomationStatus = AutomationStatus.IDLE,
    val currentTaskDescription: String = "Engine Standby",
    /** Persisted operator intent (survives process death), drives the AUTO MODE switch. */
    val enabled: Boolean = false,
    val killSwitch: KillSwitchState = KillSwitchState(),
    val rules: AutomationRuleEntity = AutomationRuleEntity(),
    val activityLogs: List<AutomationLogEntity> = emptyList(),
    val errorLogs: List<AutomationLogEntity> = emptyList(),
    val jobs: List<AutomationJobEntity> = emptyList(),
    val latestRun: AutomationRunEntity? = null,
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

    private data class AutomationCoreData(
        val status: AutomationStatus,
        val taskDescription: String,
        val rules: AutomationRuleEntity,
        val activityLogs: List<AutomationLogEntity>,
        val errorLogs: List<AutomationLogEntity>,
        val jobs: List<AutomationJobEntity>,
        val enabled: Boolean,
        val killSwitch: KillSwitchState,
        val latestRun: AutomationRunEntity?
    )

    private val pipelineFlow = combine(
        automationRepo.engineStatus,
        automationRepo.currentTaskDescription,
        automationRepo.rulesFlow,
        automationRepo.recentLogs,
        automationRepo.errorLogs
    ) { status, task, rules, recentLogs, errorLogs ->
        AutomationCoreData(
            status = status,
            taskDescription = task,
            rules = rules ?: AutomationRuleEntity(),
            activityLogs = recentLogs,
            errorLogs = errorLogs,
            jobs = emptyList(),
            enabled = false,
            killSwitch = KillSwitchState(),
            latestRun = null
        )
    }

    private val coreDataFlow = combine(
        pipelineFlow,
        automationRepo.allJobs,
        automationRepo.automationState
    ) { core, jobsList, persistentState ->
        core.copy(
            jobs = jobsList,
            enabled = persistentState?.isEnabled == true,
            killSwitch = KillSwitchState(
                engaged = persistentState?.killSwitchEngaged == true,
                reason = persistentState?.killSwitchReason,
                engagedAt = persistentState?.killSwitchEngagedAt
            )
        )
    }.combine(automationRepo.allRuns) { core, runs ->
        core.copy(latestRun = runs.firstOrNull())
    }

    val uiState: StateFlow<AutomationUiState> = combine(
        coreDataFlow,
        configRepo.apiConfigs,
        _selectedTab,
        _selectedJob
    ) { core, apiConfigs, tab, selJob ->
        AutomationUiState(
            status = core.status,
            currentTaskDescription = core.taskDescription,
            enabled = core.enabled,
            killSwitch = core.killSwitch,
            rules = core.rules,
            activityLogs = core.activityLogs,
            errorLogs = core.errorLogs,
            jobs = core.jobs,
            latestRun = core.latestRun,
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
        viewModelScope.launch { automationRepo.startAutomation() }
    }

    fun stopAutomation() {
        viewModelScope.launch { automationRepo.stopAutomation() }
    }

    fun globalKillSwitch() {
        viewModelScope.launch { automationRepo.globalKillSwitch() }
    }

    fun clearKillSwitch() {
        viewModelScope.launch { automationRepo.clearKillSwitch() }
    }

    fun runCycleNow() {
        viewModelScope.launch { automationRepo.runCycleNow() }
    }

    /** Operator retry for blocked/retryable jobs; terminal jobs are re-queued as successors. */
    fun retryJob(jobId: String) {
        viewModelScope.launch { automationRepo.retryJob(jobId) }
    }

    fun cancelJob(jobId: String) {
        viewModelScope.launch { automationRepo.cancelJob(jobId) }
        selectJob(null)
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
