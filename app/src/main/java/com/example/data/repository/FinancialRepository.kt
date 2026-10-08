package com.example.data.repository

import com.example.data.local.dao.AutomationDao
import com.example.data.local.dao.FinancialDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.FinancialAnalysisEntity
import com.example.data.local.entity.FinancingScenarioEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.finance.FinancialInput
import com.example.domain.finance.FinancialResult
import com.example.domain.finance.FinancialResultProjection
import com.example.domain.finance.toUnderwritingInput
import com.example.domain.finance.underwriting.FinancingModel
import com.example.domain.finance.underwriting.InvestmentStrategy
import com.example.domain.finance.underwriting.UnderwritingEngine
import com.example.domain.finance.underwriting.UnderwritingInput
import com.example.domain.finance.underwriting.UnderwritingResult
import com.example.domain.finance.underwriting.ValidationIssue
import com.example.domain.qualification.QualificationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Underwriting runs for stored properties.
 *
 * **Single source of truth.** Every figure this repository computes comes from
 * [UnderwritingEngine] - the deterministic engine verified by the Python oracle's golden
 * vectors. The legacy `FinancialEngine` formulas are never called here: [FinancialResult]
 * survives only as the compatibility projection that [FinancialResultProjection] derives from
 * the canonical result for the automation port and the qualification policy.
 *
 * **No silent economics.** When an automated run is built from a property row
 * ([PropertyUnderwritingFactory]), observed data (rent estimates, tax records, HOA) is used
 * exactly, and a missing observation is reported with an explicit [ValidationIssue] instead of
 * being replaced by a hardcoded guess. Whatever remains unspecified resolves to the *named*
 * assumptions in `UnderwritingAssumptions` / `FinancingModelDefaultsRegistry`, and the engine
 * records the provenance of each one in the result.
 *
 * **All strategies, all financing models.** [underwriteProperty] runs any
 * [InvestmentStrategy] (BUY_AND_HOLD, BRRRR, FIX_AND_FLIP, WHOLESALE) with any
 * [FinancingModel]; the persisted comparison scenarios are the same deal re-run through the
 * canonical engine under every declared financing model.
 */
class FinancialRepository(
    private val financialDao: FinancialDao,
    private val propertyDao: PropertyDao,
    private val automationDao: AutomationDao? = null
) : com.example.domain.automation.FinancialAnalysisGateway {

    /**
     * [com.example.domain.automation.FinancialAnalysisGateway] port used by the automation
     * execution system. Deterministic and free of external side effects, hence safe to re-run;
     * the returned legacy shape is a projection of the canonical underwriting result.
     */
    override suspend fun runAnalysis(propertyId: String): FinancialResult = analyzeProperty(propertyId)

    /** Durable evidence check used by crash recovery (was the analysis already persisted?). */
    override suspend fun hasAnalysis(propertyId: String): Boolean = getAnalysis(propertyId) != null

    val totalAnalysesCount: Flow<Int> = financialDao.getAnalysesCountFlow()
    val allAnalyses: Flow<List<FinancialAnalysisEntity>> = financialDao.getAllAnalysesFlow()

    fun getAnalysisFlow(propertyId: String): Flow<FinancialAnalysisEntity?> =
        financialDao.getAnalysisFlow(propertyId)

    suspend fun getAnalysis(propertyId: String): FinancialAnalysisEntity? =
        financialDao.getAnalysis(propertyId)

    fun getScenariosForProperty(propertyId: String): Flow<List<FinancingScenarioEntity>> =
        financialDao.getScenariosForProperty(propertyId)

    /**
     * Canonical entry point: underwrites the property with [UnderwritingEngine] and returns the
     * full canonical result (validation findings, assumption provenance, strategy blocks).
     *
     * @param customInput     a complete deal supplied by the caller; used exactly as given. When
     *                        null, the deal is assembled from the property's observed data by
     *                        [PropertyUnderwritingFactory].
     * @param strategy        only used when [customInput] is null (a custom input carries its
     *                        own strategy).
     * @param financingModel  only used when [customInput] is null; null means the engine's
     *                        documented default model for the strategy.
     */
    suspend fun underwriteProperty(
        propertyId: String,
        customInput: UnderwritingInput? = null,
        strategy: InvestmentStrategy = InvestmentStrategy.BUY_AND_HOLD,
        financingModel: FinancingModel? = null
    ): UnderwritingResult = runUnderwriting(propertyId, customInput, strategy, financingModel).result

    /**
     * Compatibility entry point kept for the automation port and the older screens. A legacy
     * [FinancialInput] is translated field-for-field into an [UnderwritingInput] (every value
     * stays EXPLICIT), the canonical engine does the computation, and the result is projected
     * back into the legacy shape - no legacy formula is ever evaluated.
     */
    suspend fun analyzeProperty(
        propertyId: String,
        customInput: FinancialInput? = null
    ): FinancialResult {
        val run = runUnderwriting(
            propertyId = propertyId,
            customInput = customInput?.toUnderwritingInput(),
            strategy = InvestmentStrategy.BUY_AND_HOLD,
            financingModel = null
        )
        return FinancialResultProjection.fromUnderwriting(run.input, run.result)
    }

    private data class UnderwritingRun(
        val property: PropertyEntity,
        val input: UnderwritingInput,
        val result: UnderwritingResult
    )

    private suspend fun runUnderwriting(
        propertyId: String,
        customInput: UnderwritingInput?,
        strategy: InvestmentStrategy,
        financingModel: FinancingModel?
    ): UnderwritingRun = withContext(Dispatchers.IO) {
        val property = propertyDao.getPropertyById(id = propertyId)
            ?: throw IllegalArgumentException("Property not found: $propertyId")

        val input: UnderwritingInput
        val factoryIssues: List<ValidationIssue>
        if (customInput != null) {
            input = customInput
            factoryIssues = emptyList()
        } else {
            val build = PropertyUnderwritingFactory.build(
                property = property,
                rentEstimate = propertyDao.getRentEstimate(propertyId),
                taxRecord = propertyDao.getTaxRecord(propertyId),
                strategy = strategy,
                financingModel = financingModel
            )
            input = build.input
            factoryIssues = build.issues
        }

        val result = UnderwritingEngine.analyze(input, factoryIssues)
        persistRun(propertyId, property, input, result)
        UnderwritingRun(property, input, result)
    }

    private suspend fun persistRun(
        propertyId: String,
        property: PropertyEntity,
        input: UnderwritingInput,
        result: UnderwritingResult
    ) {
        val projection = FinancialResultProjection.fromUnderwriting(input, result)

        val rules = automationDao?.getRules() ?: AutomationRuleEntity()
        val qualification = QualificationEngine.evaluate(property, projection, rules)

        val snapshot = projection.input
        val entity = FinancialAnalysisEntity(
            propertyId = propertyId,
            purchasePrice = snapshot.purchasePrice,
            closingCosts = snapshot.closingCosts,
            renovationCost = snapshot.renovationCost,
            monthlyRent = snapshot.monthlyRent,
            otherMonthlyIncome = snapshot.otherMonthlyIncome,
            vacancyRatePct = snapshot.vacancyRatePct,
            propertyTaxAnnual = snapshot.propertyTaxAnnual,
            insuranceAnnual = snapshot.insuranceAnnual,
            maintenancePct = snapshot.maintenancePct,
            managementPct = snapshot.managementPct,
            utilitiesMonthly = snapshot.utilitiesMonthly,
            downPaymentPct = snapshot.downPaymentPct,
            interestRatePct = snapshot.interestRatePct,
            loanTermYears = snapshot.loanTermYears,
            grossRentalIncome = projection.grossRentalIncome,
            effectiveRentalIncome = projection.effectiveRentalIncome,
            operatingExpensesMonthly = projection.operatingExpensesMonthly,
            noiAnnual = projection.noiAnnual,
            monthlyDebtService = projection.monthlyDebtService,
            monthlyCashFlow = projection.monthlyCashFlow,
            annualCashFlow = projection.annualCashFlow,
            capRate = projection.capRate,
            cashOnCashReturn = projection.cashOnCashReturn,
            dscr = projection.dscr,
            breakEvenOccupancyPct = projection.breakEvenOccupancyPct,
            totalCashRequired = projection.totalCashRequired,
            calculatedAt = System.currentTimeMillis(),
            isQualified = qualification.isQualified,
            dealScore = qualification.score,
            qualificationSummary = qualification.summary
        )

        financialDao.insertAnalysis(entity)

        // The comparison rows are the same deal re-run through the canonical engine under every
        // declared financing model, so the rows can never disagree with the primary analysis.
        val scenarios = FinancingModel.entries.map { model ->
            scenarioEntity(propertyId, input, model)
        }
        financialDao.clearScenariosForProperty(propertyId)
        financialDao.insertScenarios(scenarios)
    }

    private fun scenarioEntity(
        propertyId: String,
        input: UnderwritingInput,
        model: FinancingModel
    ): FinancingScenarioEntity {
        val result = UnderwritingEngine.analyze(input.copy(financingModel = model))
        return FinancingScenarioEntity(
            propertyId = propertyId,
            scenarioName = result.financing.displayName,
            downPaymentPct = result.financing.downPaymentPctOfPrice ?: 0.0,
            interestRatePct = result.financing.interestRatePct,
            loanTermYears = result.financing.loanTermMonths / 12,
            monthlyPayment = result.financing.monthlyPayment,
            cashRequired = result.core.totalCashRequired,
            monthlyCashFlow = result.core.monthlyCashFlow,
            cashOnCash = result.core.cashOnCashPct ?: 0.0,
            // Legacy entity column has no null; the documented all-cash sentinel applies.
            dscr = result.core.dscr ?: FinancialResultProjection.LEGACY_ALL_CASH_DSCR_SENTINEL
        )
    }
}
