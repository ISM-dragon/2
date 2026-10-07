package com.example.data.repository

import com.example.data.local.dao.AutomationDao
import com.example.data.local.dao.FinancialDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.FinancialAnalysisEntity
import com.example.data.local.entity.FinancingScenarioEntity
import com.example.domain.finance.FinancialEngine
import com.example.domain.finance.FinancialInput
import com.example.domain.finance.FinancialResult
import com.example.domain.qualification.QualificationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FinancialRepository(
    private val financialDao: FinancialDao,
    private val propertyDao: PropertyDao,
    private val automationDao: AutomationDao? = null
) : com.example.domain.automation.FinancialAnalysisGateway {

    /**
     * [com.example.domain.automation.FinancialAnalysisGateway] port used by the automation
     * execution system. Deterministic and free of external side effects, hence safe to re-run.
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

    suspend fun analyzeProperty(
        propertyId: String,
        customInput: FinancialInput? = null
    ): FinancialResult = withContext(Dispatchers.IO) {
        val property = propertyDao.getPropertyById(id = propertyId)
            ?: throw IllegalArgumentException("Property not found: $propertyId")
        val rent = propertyDao.getRentEstimate(propertyId)?.estimatedRent ?: (property.price * 0.008)
        val taxes = propertyDao.getMarketData(propertyId)?.estimatedValue?.let { it * 0.012 } ?: (property.price * 0.012)

        val input = customInput ?: FinancialInput(
            purchasePrice = property.price,
            closingCosts = property.price * 0.025,
            renovationCost = if (property.sourceType == "OFF_MARKET") 35000.0 else 5000.0,
            monthlyRent = rent,
            otherMonthlyIncome = 0.0,
            vacancyRatePct = 5.0,
            propertyTaxAnnual = taxes,
            insuranceAnnual = property.price * 0.006,
            maintenancePct = 5.0,
            managementPct = 8.0,
            utilitiesMonthly = 0.0,
            downPaymentPct = 20.0,
            interestRatePct = 6.85,
            loanTermYears = 30
        )

        val result = FinancialEngine.calculate(input)

        val rules = automationDao?.getRules() ?: AutomationRuleEntity()
        val qual = QualificationEngine.evaluate(property, result, rules)

        val entity = FinancialAnalysisEntity(
            propertyId = propertyId,
            purchasePrice = input.purchasePrice,
            closingCosts = input.closingCosts,
            renovationCost = input.renovationCost,
            monthlyRent = input.monthlyRent,
            otherMonthlyIncome = input.otherMonthlyIncome,
            vacancyRatePct = input.vacancyRatePct,
            propertyTaxAnnual = input.propertyTaxAnnual,
            insuranceAnnual = input.insuranceAnnual,
            maintenancePct = input.maintenancePct,
            managementPct = input.managementPct,
            utilitiesMonthly = input.utilitiesMonthly,
            downPaymentPct = input.downPaymentPct,
            interestRatePct = input.interestRatePct,
            loanTermYears = input.loanTermYears,
            grossRentalIncome = result.grossRentalIncome,
            effectiveRentalIncome = result.effectiveRentalIncome,
            operatingExpensesMonthly = result.operatingExpensesMonthly,
            noiAnnual = result.noiAnnual,
            monthlyDebtService = result.monthlyDebtService,
            monthlyCashFlow = result.monthlyCashFlow,
            annualCashFlow = result.annualCashFlow,
            capRate = result.capRate,
            cashOnCashReturn = result.cashOnCashReturn,
            dscr = result.dscr,
            breakEvenOccupancyPct = result.breakEvenOccupancyPct,
            totalCashRequired = result.totalCashRequired,
            calculatedAt = System.currentTimeMillis(),
            isQualified = qual.isQualified,
            dealScore = qual.score,
            qualificationSummary = qual.summary
        )

        financialDao.insertAnalysis(entity)

        // Generate and persist 4 comparison scenarios
        val scenarioList = FinancialEngine.generateComparisonScenarios(input)
        val scenarioEntities = scenarioList.map { (name, res) ->
            FinancingScenarioEntity(
                propertyId = propertyId,
                scenarioName = name,
                downPaymentPct = res.input.downPaymentPct,
                interestRatePct = res.input.interestRatePct,
                loanTermYears = res.input.loanTermYears,
                monthlyPayment = res.monthlyDebtService,
                cashRequired = res.totalCashRequired,
                monthlyCashFlow = res.monthlyCashFlow,
                cashOnCash = res.cashOnCashReturn,
                dscr = res.dscr
            )
        }
        financialDao.clearScenariosForProperty(propertyId)
        financialDao.insertScenarios(scenarioEntities)

        result
    }
}
