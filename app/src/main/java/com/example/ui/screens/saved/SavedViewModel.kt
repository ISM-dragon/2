package com.example.ui.screens.saved

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.FinancialAnalysisEntity
import com.example.data.local.entity.PropertyEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SavedUiState(
    val selectedTab: Int = 0, // 0: Saved Properties, 1: Saved Deals, 2: Saved Analyses
    val savedProperties: List<PropertyEntity> = emptyList(),
    val savedDeals: List<PropertyEntity> = emptyList(),
    val savedAnalyses: List<FinancialAnalysisEntity> = emptyList(),
    val propertyMap: Map<String, PropertyEntity> = emptyMap()
)

class SavedViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val propertyRepo = app.propertyRepository
    private val financialRepo = app.financialRepository

    private val _selectedTab = MutableStateFlow(0)

    val uiState: StateFlow<SavedUiState> = combine(
        _selectedTab,
        propertyRepo.savedProperties,
        propertyRepo.savedDeals,
        financialRepo.allAnalyses,
        propertyRepo.allProperties
    ) { tab, props, deals, analyses, allProps ->
        SavedUiState(
            selectedTab = tab,
            savedProperties = props,
            savedDeals = deals,
            savedAnalyses = analyses,
            propertyMap = allProps.associateBy { it.id }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SavedUiState()
    )

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun toggleSave(propertyId: String, currentSaved: Boolean) {
        viewModelScope.launch {
            propertyRepo.toggleSaved(propertyId, currentSaved)
        }
    }
}
