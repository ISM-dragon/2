package com.example.ui.screens.analyzer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.PropertyEntity
import com.example.domain.finance.FinancialEngine
import com.example.domain.finance.FinancialInput
import com.example.domain.finance.FinancialResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalyzerUiState(
    val property: PropertyEntity? = null,
    val input: FinancialInput = FinancialInput(
        purchasePrice = 450000.0,
        monthlyRent = 3600.0
    ),
    val result: FinancialResult = FinancialEngine.calculate(
        FinancialInput(purchasePrice = 450000.0, monthlyRent = 3600.0)
    ),
    val comparisonScenarios: List<Pair<String, FinancialResult>> = emptyList()
)

class AnalyzerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val propertyRepo = app.propertyRepository
    private val financialRepo = app.financialRepository

    private val _uiState = MutableStateFlow(AnalyzerUiState())
    val uiState: StateFlow<AnalyzerUiState> = _uiState.asStateFlow()

    fun loadProperty(propertyId: String) {
        viewModelScope.launch {
            val prop = propertyRepo.getPropertyById(propertyId)
            val rent = propertyRepo.getRentEstimate(propertyId)?.estimatedRent ?: ((prop?.price ?: 400000.0) * 0.008)
            val taxes = propertyRepo.getTaxRecordFlow(propertyId)

            val initialInput = if (prop != null) {
                FinancialInput(
                    purchasePrice = prop.price,
                    closingCosts = prop.price * 0.025,
                    renovationCost = if (prop.sourceType == "OFF_MARKET") 35000.0 else 5000.0,
                    monthlyRent = rent,
                    otherMonthlyIncome = 0.0,
                    vacancyRatePct = 5.0,
                    propertyTaxAnnual = prop.price * 0.012,
                    insuranceAnnual = prop.price * 0.006,
                    maintenancePct = 5.0,
                    managementPct = 8.0,
                    utilitiesMonthly = 0.0,
                    downPaymentPct = 20.0,
                    interestRatePct = 6.85,
                    loanTermYears = 30
                )
            } else {
                FinancialInput(purchasePrice = 450000.0, monthlyRent = 3600.0)
            }

            val calcResult = FinancialEngine.calculate(initialInput)
            val scenarios = FinancialEngine.generateComparisonScenarios(initialInput)

            _uiState.update {
                it.copy(
                    property = prop,
                    input = initialInput,
                    result = calcResult,
                    comparisonScenarios = scenarios
                )
            }
        }
    }

    fun updateInput(newInput: FinancialInput) {
        val calcResult = FinancialEngine.calculate(newInput)
        val scenarios = FinancialEngine.generateComparisonScenarios(newInput)
        _uiState.update {
            it.copy(
                input = newInput,
                result = calcResult,
                comparisonScenarios = scenarios
            )
        }
    }

    fun saveAnalysis() {
        val propId = uiState.value.property?.id ?: return
        viewModelScope.launch {
            financialRepo.analyzeProperty(propId, uiState.value.input)
        }
    }
}
