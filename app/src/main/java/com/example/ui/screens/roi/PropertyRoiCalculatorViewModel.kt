package com.example.ui.screens.roi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.domain.finance.underwriting.AssumptionRecord
import com.example.domain.finance.underwriting.DealRating
import com.example.domain.finance.underwriting.InvestmentStrategy
import com.example.domain.finance.underwriting.IssueSeverity
import com.example.domain.finance.underwriting.UnderwritingAssumptions
import com.example.domain.finance.underwriting.UnderwritingEngine
import com.example.domain.finance.underwriting.UnderwritingInput
import com.example.domain.finance.underwriting.UnderwritingResult
import com.example.domain.finance.underwriting.ValidationIssue
import com.example.domain.finance.underwriting.Summaries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Which numbers the screen is showing.
 *
 * There is deliberately no "AI" member: the language model is never a source of
 * a financial figure in this app, so it can never be a source of a *metric*.
 */
enum class CalculationSource {
    NONE,

    /** Every figure was computed by the deterministic underwriting engine. */
    DETERMINISTIC_ENGINE
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

/**
 * The screen's view of a deterministic underwriting result.
 *
 * Metrics that genuinely do not exist for a deal (DSCR on an all-cash purchase,
 * a cap rate with no price) are null here rather than invented as 0 or 999.
 * [assumptionsUsed] and [findings] carry the engine's provenance and warnings so
 * the UI can show *why* a number is what it is.
 */
data class CalculatedRoiMetrics(
    val capRatePct: Double?,
    val cashOnCashReturnPct: Double?,
    val monthlyCashFlow: Double,
    val annualCashFlow: Double,
    val netOperatingIncomeAnnual: Double,
    val grossRentalIncomeAnnual: Double,
    val totalCashRequired: Double,
    val debtServiceCoverageRatio: Double?,
    val grossRentMultiplier: Double?,
    val projected5YearRoiPct: Double,
    val breakEvenOccupancyPct: Double?,
    val investmentVerdict: String,
    val recommendedStrategy: String,
    val marketInsights: String,
    val riskFactors: List<String> = emptyList(),
    val localMarketScore: Int = 75,
    val specVersion: String = UnderwritingAssumptions.SPEC_VERSION,
    val assumptionsUsed: Map<String, AssumptionRecord> = emptyMap(),
    val findings: List<ValidationIssue> = emptyList()
)

/** Qualitative commentary. Never contains a computed financial figure. */
data class AiNarrative(
    val marketInsights: String,
    val riskFactors: List<String> = emptyList()
)

data class PropertyRoiUiState(
    val isLoading: Boolean = false,
    val propertyDetails: PropertyDetailsInput = PropertyDetailsInput(),
    val marketData: LocalMarketDataInput = LocalMarketDataInput(),
    val metrics: CalculatedRoiMetrics? = null,
    val calculationSource: CalculationSource = CalculationSource.NONE,
    /** True when the (qualitative only) commentary below came from the model. */
    val aiNarrativeUsed: Boolean = false,
    val errorMessage: String? = null,
    val lastCalculatedAt: Long = 0L
)

/**
 * Pure functions behind the ROI screen.
 *
 * Splitting the arithmetic out of the ViewModel is what makes the "no AI maths"
 * rule testable: [deterministic] takes the user's inputs and nothing else, and
 * [withNarrative] is the *only* place AI output is allowed to touch a
 * [CalculatedRoiMetrics] - and it can only replace prose.
 */
object RoiMetricsCalculator {

    fun deterministic(
        prop: PropertyDetailsInput,
        mkt: LocalMarketDataInput
    ): CalculatedRoiMetrics {
        val result = UnderwritingEngine.analyze(underwritingInput(prop, mkt))
        return fromResult(prop, mkt, result)
    }

    /** The exact deal handed to the engine, so the same inputs reproduce the same metrics. */
    fun underwritingInput(prop: PropertyDetailsInput, mkt: LocalMarketDataInput): UnderwritingInput =
        UnderwritingInput(
            strategy = InvestmentStrategy.BUY_AND_HOLD,
            purchasePrice = prop.purchasePrice,
            closingCosts = prop.purchasePrice * (prop.closingCostRatePct / 100.0),
            rehabCost = prop.renovationCost,
            monthlyRent = mkt.estimatedMonthlyRent,
            vacancyRatePct = mkt.vacancyRatePct,
            propertyTaxAnnual = mkt.propertyTaxAnnual,
            insuranceAnnual = mkt.insuranceAnnual,
            maintenancePctOfGsi = mkt.maintenancePct,
            managementPctOfEgi = mkt.propertyManagementPct,
            utilitiesMonthly = mkt.utilitiesMonthly,
            downPaymentPct = prop.downPaymentPct,
            interestRatePct = prop.interestRatePct,
            loanTermMonths = prop.loanTermYears * 12,
            amortizationMonths = prop.loanTermYears * 12,
            holdYears = FIVE_YEAR_PROJECTION_YEARS,
            appreciationPct = mkt.neighborhoodAppreciationRatePct
        )

    private const val FIVE_YEAR_PROJECTION_YEARS = 5

    fun fromResult(
        prop: PropertyDetailsInput,
        mkt: LocalMarketDataInput,
        result: UnderwritingResult
    ): CalculatedRoiMetrics {
        val findings = result.validation
            .filter { it.severity != IssueSeverity.INFO }
            .sortedWith(compareBy({ it.severity.ordinal }, { it.code }))

        return CalculatedRoiMetrics(
            capRatePct = result.core.capRateOnPricePct,
            cashOnCashReturnPct = result.core.cashOnCashPct,
            monthlyCashFlow = result.core.monthlyCashFlow,
            annualCashFlow = result.core.annualCashFlow,
            netOperatingIncomeAnnual = result.core.noiAnnual,
            grossRentalIncomeAnnual = result.operating.grossScheduledIncomeAnnual,
            totalCashRequired = result.core.totalCashRequired,
            debtServiceCoverageRatio = result.core.dscr,
            grossRentMultiplier = result.core.grossRentMultiplier,
            // the five-year projection, including sale costs and loan payoff
            projected5YearRoiPct = result.hold?.roiPct ?: result.returns?.roiPct ?: 0.0,
            breakEvenOccupancyPct = result.core.breakEvenOccupancyPct,
            investmentVerdict = verdictLabel(result.verdict.rating),
            recommendedStrategy = recommendedStrategy(prop, mkt, result),
            marketInsights = Summaries.describe(result),
            riskFactors = riskFactors(prop, mkt, findings),
            localMarketScore = localMarketScore(mkt),
            specVersion = result.specVersion,
            assumptionsUsed = result.assumptionsUsed,
            findings = findings
        )
    }

    /**
     * Fold qualitative commentary into the metrics. Financial fields are copied
     * through untouched: there is no code path from [AiNarrative] to a number,
     * which is exactly the property the unit test pins down.
     */
    fun withNarrative(metrics: CalculatedRoiMetrics, narrative: AiNarrative): CalculatedRoiMetrics =
        metrics.copy(
            marketInsights = narrative.marketInsights.ifBlank { metrics.marketInsights },
            riskFactors = narrative.riskFactors.ifEmpty { metrics.riskFactors }
        )

    /**
     * Reads commentary from a model response. Numeric keys in the payload are
     * ignored, whatever they claim; a malformed payload simply yields null.
     */
    fun narrativeFrom(rawJson: String?): AiNarrative? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val clean = rawJson
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val obj = JSONObject(clean)
            val insights = obj.optString("marketInsights", "").trim()
            val risks = ArrayList<String>()
            obj.optJSONArray("riskFactors")?.let { array ->
                for (index in 0 until array.length()) {
                    val risk = array.optString(index, "").trim()
                    if (risk.isNotEmpty()) risks.add(risk)
                }
            }
            if (insights.isEmpty() && risks.isEmpty()) null else AiNarrative(insights, risks)
        } catch (e: Exception) {
            null
        }
    }

    private fun verdictLabel(rating: DealRating): String = when (rating) {
        DealRating.STRONG -> "Strong Buy"
        DealRating.ACCEPTABLE -> "Moderate Opportunity"
        DealRating.MARGINAL -> "Borderline Deal"
        DealRating.FAILS_CRITERIA -> "High Risk / Overpriced"
    }

    private fun recommendedStrategy(
        prop: PropertyDetailsInput,
        mkt: LocalMarketDataInput,
        result: UnderwritingResult
    ): String = when {
        prop.renovationCost > 25000.0 -> "Value-Add BRRRR"
        mkt.marketDemand.equals("High", ignoreCase = true) &&
            (result.core.cashOnCashPct ?: 0.0) > 8.0 -> "Long-Term Buy & Hold"
        else -> "Turnkey Cash Flow"
    }

    private fun riskFactors(
        prop: PropertyDetailsInput,
        mkt: LocalMarketDataInput,
        findings: List<ValidationIssue>
    ): List<String> {
        val risks = ArrayList<String>()
        risks.add("Interest rate exposure at ${prop.interestRatePct}% on a ${prop.loanTermYears}-year note")
        risks.add("Vacancy assumption of ${mkt.vacancyRatePct}% of gross rent")
        if (prop.renovationCost > 0.0) {
            risks.add("Renovation budget buffer: $${formatMoney(prop.renovationCost)}")
        }
        for (finding in findings) {
            if (risks.size >= 5) break
            risks.add(finding.message)
        }
        return risks.distinct().take(5)
    }

    /** A qualitative read of the market inputs, never of a computed return. */
    private fun localMarketScore(mkt: LocalMarketDataInput): Int {
        var score = 50
        score += when (mkt.marketDemand.lowercase()) {
            "high" -> 20
            "moderate" -> 10
            "balanced" -> 0
            "buyer's market", "buyers market" -> -10
            else -> 0
        }
        score += ((mkt.neighborhoodAppreciationRatePct - 3.0) * 4.0).toInt().coerceIn(-12, 20)
        score += when {
            mkt.averageDaysOnMarket <= 30 -> 10
            mkt.averageDaysOnMarket <= 60 -> 0
            else -> -10
        }
        return score.coerceIn(1, 100)
    }

    private fun formatMoney(value: Double): String =
        String.format("%,.0f", value)
}

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

    /**
     * Computes the metrics, then (optionally) asks the model for commentary.
     *
     * The order is the point: the numbers exist before the model is called, they
     * are shown even if the model call fails, and no model output can move them.
     */
    fun calculateRoiMetrics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val prop = _uiState.value.propertyDetails
            val mkt = _uiState.value.marketData

            val metrics = try {
                RoiMetricsCalculator.deterministic(prop, mkt)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Underwriting failed: ${e.message}")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    metrics = metrics,
                    calculationSource = CalculationSource.DETERMINISTIC_ENGINE,
                    aiNarrativeUsed = false,
                    lastCalculatedAt = System.currentTimeMillis()
                )
            }

            val response = geminiManager.generateContent(
                prompt = buildNarrativePrompt(prop, mkt),
                systemPrompt = NARRATIVE_SYSTEM_PROMPT
            )
            val narrative = if (response.success) {
                RoiMetricsCalculator.narrativeFrom(response.text)
            } else {
                null
            }

            _uiState.update { state ->
                val current = state.metrics ?: metrics
                state.copy(
                    isLoading = false,
                    metrics = narrative?.let { RoiMetricsCalculator.withNarrative(current, it) } ?: current,
                    aiNarrativeUsed = narrative != null,
                    errorMessage = if (response.success || response.errorMessage == null) {
                        null
                    } else {
                        "Market commentary unavailable (${response.errorMessage}); figures are unchanged."
                    }
                )
            }
        }
    }

    private fun buildNarrativePrompt(prop: PropertyDetailsInput, mkt: LocalMarketDataInput): String {
        return """
            A deterministic underwriting engine has already computed every financial figure for this deal.
            Write qualitative market commentary only.

            [SUBJECT PROPERTY]
            - Address: ${prop.address}, ${prop.city}, ${prop.state} ${prop.zipCode}
            - Type: ${prop.propertyType}; Layout: ${prop.bedrooms} Bed / ${prop.bathrooms} Bath; ${prop.squareFeet} sq ft
            - Year Built: ${prop.yearBuilt}; Condition: ${prop.condition}

            [LOCAL MARKET CONTEXT]
            - Market demand: ${mkt.marketDemand}
            - Average days on market: ${mkt.averageDaysOnMarket}
            - Neighborhood appreciation trend: ${mkt.neighborhoodAppreciationRatePct}% per year
            - Fair market rent range: ${mkt.fairMarketRentRangeLow} - ${mkt.fairMarketRentRangeHigh} per month

            Return raw JSON with exactly these keys:
            - "marketInsights": string, 2-4 sentences of qualitative commentary about demand, rent trends and liquidity in this submarket.
            - "riskFactors": array of 2-4 short strings, each naming a qualitative risk (tenant, regulatory, liquidity, condition...).
        """.trimIndent()
    }

    /**
     * Persists the analysis. The stored row is produced by the same deterministic
     * underwriting run that rendered the screen: [RoiMetricsCalculator.underwritingInput] is
     * the exact deal the user is looking at, and
     * [com.example.data.repository.FinancialRepository] underwrites it through the canonical
     * [com.example.domain.finance.underwriting.UnderwritingEngine], so the saved numbers can
     * never drift from the displayed ones.
     */
    fun saveAnalysisToDatabase() {
        val pId = uiState.value.propertyDetails.propertyId ?: return
        if (uiState.value.metrics == null) return
        val p = uiState.value.propertyDetails
        val m = uiState.value.marketData

        viewModelScope.launch {
            financialRepo.underwriteProperty(pId, RoiMetricsCalculator.underwritingInput(p, m))
        }
    }

    private companion object {
        val NARRATIVE_SYSTEM_PROMPT = """
            You are a real estate market commentator.

            A deterministic underwriting engine computes every financial figure in this application.
            You must NOT compute, estimate, restate or invent any numbers: no currency amounts, no
            percentages, no ratios, no scores, no ranges. Qualitative prose only.

            Output MUST be strict raw JSON without markdown fences, with exactly the keys
            "marketInsights" (string) and "riskFactors" (array of strings).
        """.trimIndent()
    }
}
