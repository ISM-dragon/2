package com.example.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.AutomationStateEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.automation.AutomationStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUiState(
    val automationStatus: AutomationStatus = AutomationStatus.IDLE,
    val currentTaskDescription: String = "Engine Standby",
    val propertiesScanned: Int = 0,
    val propertiesAnalyzed: Int = 0,
    val qualifiedDeals: Int = 0,
    val offersGenerated: Int = 0,
    val offersSent: Int = 0,
    val failedOffers: Int = 0,
    val apiSlots: List<ApiConfigurationEntity> = emptyList(),
    val recentOpportunities: List<PropertyEntity> = emptyList(),
    val persistentState: AutomationStateEntity = AutomationStateEntity()
)

private data class PipelineCounts(
    val scanned: Int,
    val analyzed: Int,
    val qualified: Int,
    val offers: Int,
    val sent: Int
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp

    private val propertyRepo = app.propertyRepository
    private val financialRepo = app.financialRepository
    private val offerRepo = app.offerRepository
    private val automationRepo = app.automationRepository
    private val configRepo = app.configRepository

    private val automationInfoFlow = combine(
        automationRepo.engineStatus,
        automationRepo.currentTaskDescription,
        automationRepo.automationState
    ) { status, task, persistentState ->
        Triple(status, task, persistentState ?: AutomationStateEntity())
    }

    private val countsFlow = combine(
        propertyRepo.totalCount,
        financialRepo.totalAnalysesCount,
        propertyRepo.qualifiedDealsCount,
        offerRepo.generatedCount,
        offerRepo.sentCount
    ) { scanned, analyzed, qualified, offers, sent ->
        PipelineCounts(scanned, analyzed, qualified, offers, sent)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        automationInfoFlow,
        countsFlow,
        offerRepo.failedCount,
        configRepo.apiConfigs,
        propertyRepo.recentOpportunities
    ) { autoInfo, counts, failed, apiConfigs, recent ->
        HomeUiState(
            automationStatus = autoInfo.first,
            currentTaskDescription = autoInfo.second,
            propertiesScanned = counts.scanned,
            propertiesAnalyzed = counts.analyzed,
            qualifiedDeals = counts.qualified,
            offersGenerated = counts.offers,
            offersSent = counts.sent,
            failedOffers = failed,
            apiSlots = apiConfigs,
            recentOpportunities = recent,
            persistentState = autoInfo.third
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun startAutomation() {
        automationRepo.startAutomation()
    }

    fun stopAutomation() {
        automationRepo.stopAutomation()
    }

    fun globalKillSwitch() {
        automationRepo.globalKillSwitch()
    }

    fun toggleSave(propertyId: String, currentSaved: Boolean) {
        viewModelScope.launch {
            propertyRepo.toggleSaved(propertyId, currentSaved)
        }
    }
}
