package com.example.ui.screens.roi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.domain.finance.FinancialEngine
import com.example.domain.finance.FinancialInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CalculationSource {
    NONE,
    DETERMINISTIC_LOCAL_ENGINE
}

data class PropertyDetailsInput(
    val propertyId: String? = null,
    val title: String = "",
    val address: String = "1420 South Congress Ave",
    val city: String = "Austin",
    val state: String = "TX",
    val zipCode: String = "78704",
    val purchasePrice: Double = 450000.0,
    val propertyType: String = "Single Family",
    val bedrooms: Int = 3,
    val bathrooms: Double = 2.0,
    val squareFeet: Int = 1800,
    val yearBuilt: Int = 2012,
    val condition: String = "Good",
    val renovationCost: Double = 15000.0,
    val closingCostRatePct: Double = 2.5,
    val downPaymentPct: Double = 20.0,
    val interestRatePct: Double = 6.85,
    val loanTermYears: Int = 30
)

data class LocalMarketDataInput(
    val medianAreaPrice: Double = 485000.0,
    val pricePerSqFt: Double = 270.0,
    val averageDaysOnMarket: Int = 26,
    val neighborhoodAppreciationRatePct: Double = 4.5,
    val marketDemand: String = "High", // "High", "Moderate", "Balanced", "Buyer's Market"
    val estimatedMonthlyRent: Double = 3300.0,
    val fairMarketRentRangeLow: Double = 3000.0,
    val fairMarketRentRangeHigh: Double = 3600.0,
    val vacancyRatePct: Double = 5.0,
    val propertyTaxAnnual: Double = 5400.0,
    val insuranceAnnual: Double = 2500.0,
    val maintenancePct: Double = 5.0,
    val propertyManagementPct: Double = 8.0,
    val utilitiesMonthly: Double = 0.0
)

data class CalculatedRoiMetrics(
    val capRatePct: Double,
    val cashOnCashReturnPct: Double,
    val monthlyCashFlow: Double,
    val annualCashFlow: Double,
    val netOperatingIncomeAnnual: Double,
    val grossRentalIncomeAnnual: Double,
    val totalCashRequired: Double,
    val debtServiceCoverageRatio: Double,
    val grossRentMultiplier: Double,
    val projected5YearRoiPct: Double,
    val breakEvenOccupancyPct: Double,
    val investmentVerdict: String,
    val recommendedStrategy: String,
    val marketInsights: String,
    val riskFactors: List<String> = emptyList(),
    val localMarketScore: Int = 75
)

data class PropertyRoiUiState(
    val isLoading: Boolean = false,
    val propertyDetails: PropertyDetailsInput = PropertyDetailsInput(),
    val marketData: LocalMarketDataInput = LocalMarketDataInput(),
    val metrics: CalculatedRoiMetrics? = null,
    val calculationSource: CalculationSource = CalculationSource.NONE,
    val errorMessage: String? = null,
    val lastCalculatedAt: Long = 0L
)

class PropertyRoiCalculatorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val propertyRepo = app.propertyRepository
    private val financialRepo = app.financialRepository

    private val _uiState = MutableStateFlow(PropertyRoiUiState())
    val uiState: StateFlow<PropertyRoiUiState> = _uiState.asStateFlow()

    fun setPropertyDetails(details: PropertyDetailsInput) {
        _uiState.update { it.copy(propertyDetails = details) }
    }

    fun updatePropertyDetails(update: (PropertyDetailsInput) -> PropertyDetailsInput) {
        _uiState.update { it.copy(propertyDetails = update(it.propertyDetails)) }
    }

    fun setMarketData(market: LocalMarketDataInput) {
        _uiState.update { it.copy(marketData = market) }
    }

    fun updateMarketData(update: (LocalMarketDataInput) -> LocalMarketDataInput) {
        _uiState.update { it.copy(marketData = update(it.marketData)) }
    }

    fun loadProperty(propertyId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val prop = propertyRepo.getPropertyById(propertyId)
                val marketData = propertyRepo.getMarketData(propertyId)
                val rentEstimate = propertyRepo.getRentEstimate(propertyId)
                val taxRecord = propertyRepo.getTaxRecord(propertyId)

                val details = if (prop != null) {
                    PropertyDetailsInput(
                        propertyId = prop.id,
                        title = prop.title,
                        address = prop.address,
                        city = prop.city,
                        state = prop.state,
                        zipCode = prop.zipCode,
                        purchasePrice = prop.price,
                        propertyType = prop.propertyType,
                        bedrooms = prop.bedrooms,
                        bathrooms = prop.bathrooms,
                        squareFeet = prop.squareFeet,
                        yearBuilt = prop.yearBuilt,
                        condition = if (prop.sourceType == "OFF_MARKET") "Needs Cosmetic Rehab" else "Good",
                        renovationCost = if (prop.sourceType == "OFF_MARKET") 35000.0 else 10000.0,
                        downPaymentPct = 20.0,
                        interestRatePct = 6.85,
                        loanTermYears = 30
                    )
                } else {
                    _uiState.value.propertyDetails
                }

                val mData = LocalMarketDataInput(
                    medianAreaPrice = marketData?.medianAreaPrice ?: (details.purchasePrice * 1.05),
                    pricePerSqFt = marketData?.pricePerSqFt ?: if (details.squareFeet > 0) details.purchasePrice / details.squareFeet else 250.0,
                    averageDaysOnMarket = marketData?.averageDaysOnMarket ?: 30,
                    neighborhoodAppreciationRatePct = marketData?.neighborhoodAppreciationRate ?: 4.0,
                    marketDemand = marketData?.marketDemand ?: "Moderate",
                    estimatedMonthlyRent = rentEstimate?.estimatedRent ?: (details.purchasePrice * 0.008),
                    fairMarketRentRangeLow = rentEstimate?.rentRangeLow ?: ((rentEstimate?.estimatedRent ?: 3000.0) * 0.9),
                    fairMarketRentRangeHigh = rentEstimate?.rentRangeHigh ?: ((rentEstimate?.estimatedRent ?: 3000.0) * 1.1),
                    vacancyRatePct = 5.0,
                    propertyTaxAnnual = taxRecord?.annualTaxAmount ?: (details.purchasePrice * 0.015),
                    insuranceAnnual = details.purchasePrice * 0.006,
                    maintenancePct = 5.0,
                    propertyManagementPct = 8.0
                )

                _uiState.update {
                    it.copy(
                        propertyDetails = details,
                        marketData = mData,
                        isLoading = false
                    )
                }

                // The financial engine is the sole owner of ROI metric calculations.
                calculateRoiMetrics()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load property data: ${e.message}"
                    )
                }
            }
        }
    }

    /** Financial metrics are computed only by the deterministic local engine, never by Gemini. */
    fun calculateRoiMetrics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val property = _uiState.value.propertyDetails
            val market = _uiState.value.marketData
            try {
                val metrics = computeLocalEngineMetrics(property, market)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        metrics = metrics,
                        calculationSource = CalculationSource.DETERMINISTIC_LOCAL_ENGINE,
                        errorMessage = null,
                        lastCalculatedAt = System.currentTimeMillis()
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        calculationSource = CalculationSource.NONE,
                        errorMessage = "Local financial calculation failed: ${error.message ?: "invalid input"}."
                    )
                }
            }
        }
    }

    private fun computeLocalEngineMetrics(
        prop: PropertyDetailsInput,
        mkt: LocalMarketDataInput
    ): CalculatedRoiMetrics {
        val closingCosts = prop.purchasePrice * (prop.closingCostRatePct / 100.0)
        val financialInput = FinancialInput(
            purchasePrice = prop.purchasePrice,
            closingCosts = closingCosts,
            renovationCost = prop.renovationCost,
            monthlyRent = mkt.estimatedMonthlyRent,
            otherMonthlyIncome = 0.0,
            vacancyRatePct = mkt.vacancyRatePct,
            propertyTaxAnnual = mkt.propertyTaxAnnual,
            insuranceAnnual = mkt.insuranceAnnual,
            maintenancePct = mkt.maintenancePct,
            managementPct = mkt.propertyManagementPct,
            utilitiesMonthly = mkt.utilitiesMonthly,
            downPaymentPct = prop.downPaymentPct,
            interestRatePct = prop.interestRatePct,
            loanTermYears = prop.loanTermYears
        )

        val result = FinancialEngine.calculate(financialInput)
        val annualRent = mkt.estimatedMonthlyRent * 12.0
        val grm = if (annualRent > 0) prop.purchasePrice / annualRent else 0.0

        // 5-Year total ROI estimation incorporating cash flow and local market appreciation
        val appreciationRate = mkt.neighborhoodAppreciationRatePct / 100.0
        val futureValue5Y = prop.purchasePrice * Math.pow(1.0 + appreciationRate, 5.0)
        val equityGain5Y = (futureValue5Y - prop.purchasePrice)
        val cumulativeCashFlow5Y = result.annualCashFlow * 5.0
        val totalReturn5Y = equityGain5Y + cumulativeCashFlow5Y
        val projected5YearRoiPct = if (result.totalCashRequired > 0) {
            (totalReturn5Y / result.totalCashRequired) * 100.0
        } else 0.0

        val verdict = when {
            result.cashOnCashReturn >= 10.0 && result.dscr >= 1.25 -> "Strong Buy"
            result.cashOnCashReturn >= 6.0 && result.dscr >= 1.15 -> "Moderate Opportunity"
            result.cashOnCashReturn > 0 -> "Borderline Deal"
            else -> "High Risk / Overpriced"
        }

        val strategy = when {
            prop.renovationCost > 25000.0 -> "Value-Add BRRRR"
            mkt.marketDemand == "High" && result.cashOnCashReturn > 8.0 -> "Long-Term Buy & Hold"
            else -> "Turnkey Cash Flow"
        }

        val marketSummary = "Local market median is $${String.format("%,.0f", mkt.medianAreaPrice)} with ${mkt.neighborhoodAppreciationRatePct}% annual appreciation. Subject property offers a ${String.format("%.1f", result.capRate)}% Cap Rate."

        return CalculatedRoiMetrics(
            capRatePct = result.capRate,
            cashOnCashReturnPct = result.cashOnCashReturn,
            monthlyCashFlow = result.monthlyCashFlow,
            annualCashFlow = result.annualCashFlow,
            netOperatingIncomeAnnual = result.noiAnnual,
            grossRentalIncomeAnnual = annualRent,
            totalCashRequired = result.totalCashRequired,
            debtServiceCoverageRatio = result.dscr,
            grossRentMultiplier = grm,
            projected5YearRoiPct = projected5YearRoiPct,
            breakEvenOccupancyPct = result.breakEvenOccupancyPct,
            investmentVerdict = verdict,
            recommendedStrategy = strategy,
            marketInsights = marketSummary,
            riskFactors = listOf(
                "Interest rate exposure at ${prop.interestRatePct}%",
                "Local vacancy estimated at ${mkt.vacancyRatePct}%",
                "Renovation overrun buffer: $${String.format("%,.0f", prop.renovationCost)}"
            ),
            localMarketScore = if (result.dscr >= 1.2) 82 else 65
        )
    }

    fun saveAnalysisToDatabase() {
        val pId = uiState.value.propertyDetails.propertyId ?: return
        val currentMetrics = uiState.value.metrics ?: return
        val p = uiState.value.propertyDetails
        val m = uiState.value.marketData

        viewModelScope.launch {
            val input = FinancialInput(
                purchasePrice = p.purchasePrice,
                closingCosts = p.purchasePrice * (p.closingCostRatePct / 100.0),
                renovationCost = p.renovationCost,
                monthlyRent = m.estimatedMonthlyRent,
                vacancyRatePct = m.vacancyRatePct,
                propertyTaxAnnual = m.propertyTaxAnnual,
                insuranceAnnual = m.insuranceAnnual,
                maintenancePct = m.maintenancePct,
                managementPct = m.propertyManagementPct,
                utilitiesMonthly = m.utilitiesMonthly,
                downPaymentPct = p.downPaymentPct,
                interestRatePct = p.interestRatePct,
                loanTermYears = p.loanTermYears
            )
            financialRepo.analyzeProperty(pId, input)
        }
    }
}
