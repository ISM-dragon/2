package com.example.ui.screens.analyzer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.PropertyEntity
import com.example.domain.finance.FinancialEngine
import com.example.domain.finance.FinancialInput
import com.example.domain.finance.FinancialResult
import com.example.domain.finance.strategy.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalyzerUiState(
    val property: PropertyEntity? = null,
    val selectedStrategy: InvestmentStrategyType = InvestmentStrategyType.BUY_AND_HOLD,
    // Base inputs
    val input: FinancialInput = FinancialInput(
        purchasePrice = 450000.0,
        monthlyRent = 3600.0
    ),
    val result: FinancialResult = FinancialEngine.calculate(
        FinancialInput(purchasePrice = 450000.0, monthlyRent = 3600.0)
    ),
    // Strategy Specific
    val buyAndHoldInputs: BuyAndHoldInputs = BuyAndHoldInputs(
        purchasePrice = 450000.0,
        monthlyRent = 3600.0
    ),
    val buyAndHoldResult: BuyAndHoldResult = MultiStrategyCalculator.calculateBuyAndHold(
        BuyAndHoldInputs(purchasePrice = 450000.0, monthlyRent = 3600.0)
    ),
    val brrrrInputs: BrrrrInputs = BrrrrInputs(
        purchasePrice = 350000.0,
        rehabCost = 50000.0,
        arv = 475000.0,
        postRehabMonthlyRent = 3600.0,
        postRehabExpensesMonthly = 950.0
    ),
    val brrrrResult: BrrrrResult = MultiStrategyCalculator.calculateBrrrr(
        BrrrrInputs(
            purchasePrice = 350000.0,
            rehabCost = 50000.0,
            arv = 475000.0,
            postRehabMonthlyRent = 3600.0,
            postRehabExpensesMonthly = 950.0
        )
    ),
    val fixAndFlipInputs: FixAndFlipInputs = FixAndFlipInputs(
        purchasePrice = 320000.0,
        rehabBudget = 45000.0,
        expectedSalePrice = 440000.0
    ),
    val fixAndFlipResult: FixAndFlipResult = MultiStrategyCalculator.calculateFixAndFlip(
        FixAndFlipInputs(
            purchasePrice = 320000.0,
            rehabBudget = 45000.0,
            expectedSalePrice = 440000.0
        )
    ),
    val wholesaleInputs: WholesaleInputs = WholesaleInputs(
        arv = 450000.0,
        estimatedRepairs = 45000.0
    ),
    val wholesaleResult: WholesaleResult = MultiStrategyCalculator.calculateWholesale(
        WholesaleInputs(arv = 450000.0, estimatedRepairs = 45000.0)
    ),
    val wealthForecast: List<YearWealthMilestone> = emptyList(),
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
            val rent = propertyRepo.getRentEstimate(propertyId)?.estimatedRent ?: ((prop?.price ?: 400000.0) * 0.0075)
            val price = prop?.price ?: 400000.0
            val taxes = propertyRepo.getTaxRecord(propertyId)?.annualTaxAmount ?: (price * 0.015)

            val baseInput = FinancialInput(
                purchasePrice = price,
                closingCosts = price * 0.025,
                renovationCost = if (prop?.sourceType == "OFF_MARKET") 35000.0 else 5000.0,
                monthlyRent = rent,
                otherMonthlyIncome = 0.0,
                vacancyRatePct = 5.0,
                propertyTaxAnnual = taxes,
                insuranceAnnual = price * 0.006,
                maintenancePct = 5.0,
                managementPct = 8.0,
                utilitiesMonthly = 0.0,
                downPaymentPct = 20.0,
                interestRatePct = 6.85,
                loanTermYears = 30
            )

            val bnhInput = BuyAndHoldInputs(
                purchasePrice = price,
                downPaymentPct = 20.0,
                interestRatePct = 6.85,
                loanTermYears = 30,
                closingCosts = price * 0.025,
                initialRehab = if (prop?.sourceType == "OFF_MARKET") 35000.0 else 5000.0,
                monthlyRent = rent,
                vacancyRatePct = 5.0,
                propertyTaxAnnual = taxes,
                insuranceAnnual = price * 0.006,
                maintenancePct = 5.0,
                managementPct = 8.0
            )

            val arvEst = price * 1.25
            val brrrrInput = BrrrrInputs(
                purchasePrice = price * 0.85,
                rehabCost = price * 0.12,
                arv = arvEst,
                postRehabMonthlyRent = rent * 1.15,
                postRehabExpensesMonthly = rent * 0.35
            )

            val flipInput = FixAndFlipInputs(
                purchasePrice = price * 0.82,
                rehabBudget = price * 0.14,
                expectedSalePrice = arvEst
            )

            val wholesaleInput = WholesaleInputs(
                arv = arvEst,
                estimatedRepairs = price * 0.12
            )

            val calcResult = FinancialEngine.calculate(baseInput)
            val scenarios = FinancialEngine.generateComparisonScenarios(baseInput)
            val bnhResult = MultiStrategyCalculator.calculateBuyAndHold(bnhInput)
            val brrrrResult = MultiStrategyCalculator.calculateBrrrr(brrrrInput)
            val flipResult = MultiStrategyCalculator.calculateFixAndFlip(flipInput)
            val wsResult = MultiStrategyCalculator.calculateWholesale(wholesaleInput)
            val forecast = MultiStrategyCalculator.calculate10YearForecast(
                initialPrice = price,
                downPaymentPct = 20.0,
                loanInterestRate = 6.85,
                loanTermYears = 30,
                initialAnnualCashFlow = bnhResult.annualCashFlow
            )

            _uiState.update {
                it.copy(
                    property = prop,
                    input = baseInput,
                    result = calcResult,
                    buyAndHoldInputs = bnhInput,
                    buyAndHoldResult = bnhResult,
                    brrrrInputs = brrrrInput,
                    brrrrResult = brrrrResult,
                    fixAndFlipInputs = flipInput,
                    fixAndFlipResult = flipResult,
                    wholesaleInputs = wholesaleInput,
                    wholesaleResult = wsResult,
                    wealthForecast = forecast,
                    comparisonScenarios = scenarios
                )
            }
        }
    }

    fun setStrategy(strategy: InvestmentStrategyType) {
        _uiState.update { it.copy(selectedStrategy = strategy) }
    }

    fun updateSensitivity(
        interestRate: Double? = null,
        vacancyRate: Double? = null,
        downPaymentPct: Double? = null,
        rent: Double? = null
    ) {
        val curr = _uiState.value.buyAndHoldInputs
        val updated = curr.copy(
            interestRatePct = interestRate ?: curr.interestRatePct,
            vacancyRatePct = vacancyRate ?: curr.vacancyRatePct,
            downPaymentPct = downPaymentPct ?: curr.downPaymentPct,
            monthlyRent = rent ?: curr.monthlyRent
        )
        val result = MultiStrategyCalculator.calculateBuyAndHold(updated)
        val forecast = MultiStrategyCalculator.calculate10YearForecast(
            initialPrice = updated.purchasePrice,
            downPaymentPct = updated.downPaymentPct,
            loanInterestRate = updated.interestRatePct,
            loanTermYears = updated.loanTermYears,
            initialAnnualCashFlow = result.annualCashFlow
        )

        _uiState.update {
            it.copy(
                buyAndHoldInputs = updated,
                buyAndHoldResult = result,
                wealthForecast = forecast
            )
        }
    }

    fun updateBrrrrInputs(rehabCost: Double? = null, arv: Double? = null) {
        val curr = _uiState.value.brrrrInputs
        val updated = curr.copy(
            rehabCost = rehabCost ?: curr.rehabCost,
            arv = arv ?: curr.arv
        )
        val res = MultiStrategyCalculator.calculateBrrrr(updated)
        _uiState.update { it.copy(brrrrInputs = updated, brrrrResult = res) }
    }

    fun updateFlipInputs(rehabBudget: Double? = null, salePrice: Double? = null) {
        val curr = _uiState.value.fixAndFlipInputs
        val updated = curr.copy(
            rehabBudget = rehabBudget ?: curr.rehabBudget,
            expectedSalePrice = salePrice ?: curr.expectedSalePrice
        )
        val res = MultiStrategyCalculator.calculateFixAndFlip(updated)
        _uiState.update { it.copy(fixAndFlipInputs = updated, fixAndFlipResult = res) }
    }

    fun updateWholesaleInputs(fee: Double? = null, repairs: Double? = null) {
        val curr = _uiState.value.wholesaleInputs
        val updated = curr.copy(
            desiredAssignmentFee = fee ?: curr.desiredAssignmentFee,
            estimatedRepairs = repairs ?: curr.estimatedRepairs
        )
        val res = MultiStrategyCalculator.calculateWholesale(updated)
        _uiState.update { it.copy(wholesaleInputs = updated, wholesaleResult = res) }
    }

    fun saveAnalysis() {
        val propId = uiState.value.property?.id ?: return
        viewModelScope.launch {
            financialRepo.analyzeProperty(propId, uiState.value.input)
        }
    }
}
