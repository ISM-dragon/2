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
import org.json.JSONArray
import org.json.JSONObject

enum class CalculationSource {
    NONE,
    GEMINI_AI,
    LOCAL_ENGINE_FALLBACK
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
    private val geminiManager = app.geminiManager

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

                // Automatically trigger Gemini ROI calculation with loaded market data
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

    fun calculateRoiMetrics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val prop = _uiState.value.propertyDetails
            val mkt = _uiState.value.marketData

            val prompt = buildPrompt(prop, mkt)
            val systemInstruction = """
                You are a senior real estate investment analyst specializing in residential underwriting, cap rate estimation, and ROI forecasting.
                Analyze the subject property against its local market indicators and compute exact return on investment metrics.
                Output MUST be strict raw JSON without markdown fences.
                Required JSON keys:
                - "capRatePct": number (e.g. 7.2)
                - "cashOnCashReturnPct": number (e.g. 9.4)
                - "monthlyCashFlow": number (e.g. 520.0)
                - "annualCashFlow": number (e.g. 6240.0)
                - "netOperatingIncomeAnnual": number (e.g. 31200.0)
                - "grossRentalIncomeAnnual": number (e.g. 42000.0)
                - "totalCashRequired": number (e.g. 105000.0)
                - "debtServiceCoverageRatio": number (e.g. 1.35)
                - "grossRentMultiplier": number (e.g. 10.7)
                - "projected5YearRoiPct": number (e.g. 58.5)
                - "breakEvenOccupancyPct": number (e.g. 72.0)
                - "investmentVerdict": string ("Strong Buy", "Moderate Opportunity", "High Risk / Overpriced", or "Borderline Deal")
                - "recommendedStrategy": string (e.g. "Long-Term Buy & Hold", "Value-Add BRRRR", "Medium-Term Rental")
                - "marketInsights": string (concise explanation of ROI dynamics relative to local market appreciation and rents)
                - "riskFactors": array of strings (top 2-3 risk factors)
                - "localMarketScore": integer between 1 and 100
            """.trimIndent()

            val aiResponse = geminiManager.generateContent(
                prompt = prompt,
                systemPrompt = systemInstruction
            )

            if (aiResponse.success && aiResponse.text.isNotBlank()) {
                val parsed = parseGeminiMetrics(aiResponse.text, prop, mkt)
                if (parsed != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            metrics = parsed,
                            calculationSource = CalculationSource.GEMINI_AI,
                            lastCalculatedAt = System.currentTimeMillis()
                        )
                    }
                    return@launch
                }
            }

            // Fallback to local financial engine if Gemini fails or is unavailable
            val fallbackMetrics = computeLocalEngineMetrics(prop, mkt)
            val fallbackReason = if (aiResponse.errorMessage != null) {
                "Gemini AI note: ${aiResponse.errorMessage}. Computed via local financial engine."
            } else {
                "Computed via local financial engine with local market indicators."
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    metrics = fallbackMetrics,
                    calculationSource = CalculationSource.LOCAL_ENGINE_FALLBACK,
                    errorMessage = fallbackReason,
                    lastCalculatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    private fun buildPrompt(prop: PropertyDetailsInput, mkt: LocalMarketDataInput): String {
        return """
            Please analyze the following property and calculate comprehensive return on investment (ROI) metrics based on local market data.
            
            [SUBJECT PROPERTY DETAILS]
            - Title: ${prop.title.ifBlank { prop.address }}
            - Address: ${prop.address}, ${prop.city}, ${prop.state} ${prop.zipCode}
            - Purchase Price: $${prop.purchasePrice}
            - Property Type: ${prop.propertyType}
            - Layout: ${prop.bedrooms} Bed / ${prop.bathrooms} Bath | ${prop.squareFeet} sq ft
            - Year Built: ${prop.yearBuilt}
            - Condition: ${prop.condition}
            - Estimated Renovation Cost: $${prop.renovationCost}
            - Financing: ${prop.downPaymentPct}% down payment at ${prop.interestRatePct}% annual interest (${prop.loanTermYears}-year fixed)
            - Estimated Closing Cost Rate: ${prop.closingCostRatePct}%
            
            [LOCAL MARKET INDICATORS]
            - Local Median Area Price: $${mkt.medianAreaPrice}
            - Local Price Per Sq Ft: $${mkt.pricePerSqFt}
            - Average Days on Market: ${mkt.averageDaysOnMarket} days
            - Neighborhood Appreciation Rate: ${mkt.neighborhoodAppreciationRatePct}% annually
            - Local Market Demand: ${mkt.marketDemand}
            - Estimated Monthly Market Rent: $${mkt.estimatedMonthlyRent} (Range: $${mkt.fairMarketRentRangeLow} - $${mkt.fairMarketRentRangeHigh})
            - Local Expected Vacancy Rate: ${mkt.vacancyRatePct}%
            - Local Annual Property Tax: $${mkt.propertyTaxAnnual}
            - Annual Insurance: $${mkt.insuranceAnnual}
            - Maintenance Reserve: ${mkt.maintenancePct}%
            - Property Management Fee: ${mkt.propertyManagementPct}%
            - Monthly Utilities: $${mkt.utilitiesMonthly}
            
            Evaluate cash flow, cap rate, cash-on-cash return, DSCR, and 5-year total ROI. Return strict JSON.
        """.trimIndent()
    }

    private fun parseGeminiMetrics(
        jsonString: String,
        prop: PropertyDetailsInput,
        mkt: LocalMarketDataInput
    ): CalculatedRoiMetrics? {
        return try {
            val clean = jsonString
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val obj = JSONObject(clean)

            val riskList = mutableListOf<String>()
            val riskArray = obj.optJSONArray("riskFactors")
            if (riskArray != null) {
                for (i in 0 until riskArray.length()) {
                    riskList.add(riskArray.optString(i))
                }
            }

            CalculatedRoiMetrics(
                capRatePct = obj.optDouble("capRatePct", 0.0),
                cashOnCashReturnPct = obj.optDouble("cashOnCashReturnPct", 0.0),
                monthlyCashFlow = obj.optDouble("monthlyCashFlow", 0.0),
                annualCashFlow = obj.optDouble("annualCashFlow", 0.0),
                netOperatingIncomeAnnual = obj.optDouble("netOperatingIncomeAnnual", 0.0),
                grossRentalIncomeAnnual = obj.optDouble("grossRentalIncomeAnnual", mkt.estimatedMonthlyRent * 12),
                totalCashRequired = obj.optDouble("totalCashRequired", 0.0),
                debtServiceCoverageRatio = obj.optDouble("debtServiceCoverageRatio", 1.0),
                grossRentMultiplier = obj.optDouble("grossRentMultiplier", 0.0),
                projected5YearRoiPct = obj.optDouble("projected5YearRoiPct", 0.0),
                breakEvenOccupancyPct = obj.optDouble("breakEvenOccupancyPct", 0.0),
                investmentVerdict = obj.optString("investmentVerdict", "Analyzed"),
                recommendedStrategy = obj.optString("recommendedStrategy", "Long-Term Buy & Hold"),
                marketInsights = obj.optString("marketInsights", "AI ROI analysis completed."),
                riskFactors = riskList,
                localMarketScore = obj.optInt("localMarketScore", 70)
            )
        } catch (e: Exception) {
            null
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
