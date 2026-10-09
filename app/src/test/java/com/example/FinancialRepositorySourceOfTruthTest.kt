package com.example

import com.example.data.local.dao.FinancialDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.FinancialAnalysisEntity
import com.example.data.local.entity.FinancingScenarioEntity
import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.RentEstimateEntity
import com.example.data.local.entity.TaxRecordEntity
import com.example.data.repository.FinancialRepository
import com.example.data.repository.PropertyUnderwritingFactory
import com.example.domain.finance.FinancialInput
import com.example.domain.finance.FinancialResultProjection
import com.example.domain.finance.toUnderwritingInput
import com.example.domain.finance.underwriting.DealRating
import com.example.domain.finance.underwriting.FinancingModel
import com.example.domain.finance.underwriting.FinancingModelDefaultsRegistry
import com.example.domain.finance.underwriting.InvestmentStrategy
import com.example.domain.finance.underwriting.UnderwritingAssumptions
import com.example.domain.finance.underwriting.UnderwritingEngine
import com.example.domain.finance.underwriting.UnderwritingFlatten
import com.example.domain.finance.underwriting.UnderwritingInput
import com.example.domain.finance.underwriting.UnderwritingResult
import com.example.domain.intelligence.dedup.PropertyDaoStub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression and edge-case coverage for the underwriting single source of truth:
 *
 *  * [com.example.data.repository.FinancialRepository] computes every figure through
 *    [UnderwritingEngine] and never through the legacy formulas;
 *  * missing observed inputs stay explicit (validation findings) instead of being replaced by
 *    the old silent economic fallbacks (0.8% rent-to-price, 1.2% taxes, $35k/$5k renovation);
 *  * all four strategies and all five declared financing models run through the repository;
 *  * the legacy [com.example.domain.finance.FinancialResult] contract used by automation and
 *    qualification is a pure projection of the canonical result, sentinels included.
 */
class FinancialRepositorySourceOfTruthTest {

    // ------------------------------------------------------------------
    // in-memory persistence doubles
    // ------------------------------------------------------------------

    private class InMemoryFinancialDao : FinancialDao {
        val analyses = LinkedHashMap<String, FinancialAnalysisEntity>()
        val scenarios = LinkedHashMap<String, MutableList<FinancingScenarioEntity>>()

        override fun getAnalysisFlow(propertyId: String): Flow<FinancialAnalysisEntity?> =
            flowOf(analyses[propertyId])

        override suspend fun getAnalysis(propertyId: String): FinancialAnalysisEntity? =
            analyses[propertyId]

        override fun getAllAnalysesFlow(): Flow<List<FinancialAnalysisEntity>> =
            flowOf(analyses.values.toList())

        override fun getAnalysesCountFlow(): Flow<Int> = flowOf(analyses.size)

        override suspend fun getAnalysesCount(): Int = analyses.size

        override suspend fun insertAnalysis(analysis: FinancialAnalysisEntity) {
            analyses[analysis.propertyId] = analysis
        }

        override fun getScenariosForProperty(propertyId: String): Flow<List<FinancingScenarioEntity>> =
            flowOf(scenarios[propertyId].orEmpty())

        override suspend fun insertScenarios(scenarios: List<FinancingScenarioEntity>) {
            for (scenario in scenarios) {
                this.scenarios.getOrPut(scenario.propertyId) { mutableListOf() }.add(scenario)
            }
        }

        override suspend fun getScenariosListForProperty(propertyId: String): List<FinancingScenarioEntity> =
            scenarios[propertyId].orEmpty()

        override suspend fun getAllAnalysesList(): List<FinancialAnalysisEntity> = analyses.values.toList()

        override suspend fun getAllScenariosList(): List<FinancingScenarioEntity> = scenarios.values.flatten()

        override suspend fun insertAnalyses(analyses: List<FinancialAnalysisEntity>) {
            analyses.forEach { insertAnalysis(it) }
        }

        override suspend fun clearScenariosForProperty(propertyId: String) {
            scenarios.remove(propertyId)
        }
    }

    private class TestPropertyDao(
        private val property: PropertyEntity?,
        private val rentEstimate: RentEstimateEntity? = null,
        private val taxRecord: TaxRecordEntity? = null
    ) : PropertyDao by PropertyDaoStub {
        override suspend fun getPropertyById(id: String): PropertyEntity? =
            property?.takeIf { it.id == id }

        override suspend fun getRentEstimate(propertyId: String): RentEstimateEntity? =
            rentEstimate?.takeIf { it.propertyId == propertyId }

        override suspend fun getTaxRecord(propertyId: String): TaxRecordEntity? =
            taxRecord?.takeIf { it.propertyId == propertyId }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private fun property(
        id: String = "p1",
        price: Double = 400000.0,
        sourceType: String = "ON_MARKET",
        hoaMonthly: Double = 0.0
    ) = PropertyEntity(
        id = id,
        sourceType = sourceType,
        title = "Test property",
        address = "1 Main St",
        city = "Austin",
        state = "TX",
        zipCode = "78704",
        latitude = 30.25,
        longitude = -97.75,
        price = price,
        propertyType = "Single Family",
        bedrooms = 3,
        bathrooms = 2.0,
        squareFeet = 1800,
        yearBuilt = 2000,
        lotSizeSqFt = 6000,
        description = "",
        status = "Active",
        primaryImageUrl = "",
        scannedAt = 0L,
        hoaMonthly = hoaMonthly
    )

    private fun rentEstimate(amount: Double = 3200.0, propertyId: String = "p1") = RentEstimateEntity(
        propertyId = propertyId,
        estimatedRent = amount,
        rentRangeLow = amount * 0.9,
        rentRangeHigh = amount * 1.1,
        rentConfidenceScore = 90.0,
        grossYield = 9.0
    )

    private fun taxRecord(annual: Double = 4800.0, propertyId: String = "p1") = TaxRecordEntity(
        propertyId = propertyId,
        annualTaxAmount = annual,
        assessmentYear = 2025,
        assessedValue = 380000.0
    )

    private fun repository(
        property: PropertyEntity? = property(),
        rentEstimate: RentEstimateEntity? = rentEstimate(),
        taxRecord: TaxRecordEntity? = taxRecord()
    ): Pair<FinancialRepository, InMemoryFinancialDao> {
        val financialDao = InMemoryFinancialDao()
        val repository = FinancialRepository(
            financialDao = financialDao,
            propertyDao = TestPropertyDao(property, rentEstimate, taxRecord),
            automationDao = null
        )
        return repository to financialDao
    }

    private fun codes(result: UnderwritingResult): List<String> = result.validation.map { it.code }

    private fun annualLine(result: UnderwritingResult, key: String): Double =
        result.operating.expenseLines.firstOrNull { it.key == key }?.annualAmount ?: 0.0

    // ------------------------------------------------------------------
    // the repository IS the canonical engine, nothing else
    // ------------------------------------------------------------------

    @Test
    fun repositoryUnderwritesThroughTheCanonicalEngineExactly() {
        val (repository, _) = repository()
        val viaRepository = runBlocking { repository.underwriteProperty("p1") }

        val build = PropertyUnderwritingFactory.build(
            property = property(),
            rentEstimate = rentEstimate(),
            taxRecord = taxRecord()
        )
        val direct = UnderwritingEngine.analyze(build.input, build.issues)

        assertEquals(
            "the repository must add nothing to and hide nothing from the canonical result",
            UnderwritingFlatten.flatten(direct),
            UnderwritingFlatten.flatten(viaRepository)
        )
    }

    @Test
    fun repeatedRunsAreDeterministic() {
        val (repository, _) = repository()
        val first = runBlocking { repository.underwriteProperty("p1") }
        val second = runBlocking { repository.underwriteProperty("p1") }
        assertEquals(UnderwritingFlatten.flatten(first), UnderwritingFlatten.flatten(second))
    }

    // ------------------------------------------------------------------
    // no silent economic fallbacks (regressions)
    // ------------------------------------------------------------------

    @Test
    fun missingRentEstimateStaysExplicitInsteadOfFabricatingPriceToRent() {
        val (repository, dao) = repository(rentEstimate = null)
        val result = runBlocking { repository.underwriteProperty("p1") }

        assertTrue(codes(result).contains("MISSING_RENT_ESTIMATE"))
        assertEquals(0.0, result.operating.grossScheduledIncomeAnnual, 1e-9)

        val entity = runBlocking { dao.getAnalysis("p1") }!!
        // the removed legacy fallback fabricated 0.8% of price ($3,200 on a $400k house)
        assertEquals(0.0, entity.monthlyRent, 1e-9)
        assertFalse(entity.monthlyRent == 400000.0 * 0.008)
    }

    @Test
    fun missingTaxRecordAppliesTheNamedAssumptionAndSaysSo() {
        val (repository, _) = repository(taxRecord = null)
        val result = runBlocking { repository.underwriteProperty("p1") }

        assertTrue(codes(result).contains("MISSING_PROPERTY_TAX_RECORD"))
        // the named assumption, not the removed silent 1.2% / market-data guess
        assertEquals(
            400000.0 * UnderwritingAssumptions.PROPERTY_TAX_PCT_OF_PRICE / 100.0,
            annualLine(result, "propertyTax"),
            1e-9
        )
    }

    @Test
    fun renovationIsNeverGuessedFromTheSourceType() {
        for (sourceType in listOf("OFF_MARKET", "ON_MARKET", "FORECLOSURE", "WHOLESALE")) {
            val (repository, _) = repository(property = property(sourceType = sourceType))
            val result = runBlocking { repository.underwriteProperty("p1") }

            assertEquals("source type $sourceType must not invent rehab", 0.0, result.rehabCost, 1e-9)
            assertTrue(codes(result).contains("NO_RENOVATION_ESTIMATE"))
        }
    }

    @Test
    fun observedRentAndTaxesAreUsedExactly() {
        val (repository, _) = repository(rentEstimate = rentEstimate(3150.0), taxRecord = taxRecord(5040.0))
        val result = runBlocking { repository.underwriteProperty("p1") }

        assertEquals(3150.0 * 12.0, result.operating.grossScheduledIncomeAnnual, 1e-9)
        assertEquals(5040.0, annualLine(result, "propertyTax"), 1e-9)
        assertFalse(codes(result).contains("MISSING_RENT_ESTIMATE"))
        assertFalse(codes(result).contains("MISSING_PROPERTY_TAX_RECORD"))
    }

    @Test
    fun hostileObservedValuesAreTreatedAsMissingNotTrusted() {
        val (repository, _) = repository(
            rentEstimate = rentEstimate(Double.NaN),
            taxRecord = taxRecord(-100.0)
        )
        val result = runBlocking { repository.underwriteProperty("p1") }

        assertTrue(codes(result).contains("MISSING_RENT_ESTIMATE"))
        assertTrue(codes(result).contains("MISSING_PROPERTY_TAX_RECORD"))
        assertEquals(0.0, result.operating.grossScheduledIncomeAnnual, 1e-9)
    }

    @Test
    fun observedHoaBecomesAnExplicitExpenseLine() {
        val (repository, _) = repository(property = property(hoaMonthly = 85.0))
        val result = runBlocking { repository.underwriteProperty("p1") }
        assertEquals(85.0 * 12.0, annualLine(result, "hoa"), 1e-9)
    }

    // ------------------------------------------------------------------
    // all declared financing models as comparison scenarios
    // ------------------------------------------------------------------

    @Test
    fun scenariosCoverEveryDeclaredFinancingModel() {
        val (repository, dao) = repository()
        runBlocking { repository.underwriteProperty("p1") }

        val scenarios = runBlocking { dao.getScenariosListForProperty("p1") }
        assertEquals(FinancingModel.entries.size, scenarios.size)
        val expectedNames = FinancingModelDefaultsRegistry.allModels().map { it.displayName }.toSet()
        assertEquals(expectedNames, scenarios.map { it.scenarioName }.toSet())
        for (scenario in scenarios) {
            assertTrue("payment must be finite", scenario.monthlyPayment.isFinite())
            assertTrue("cash required must be finite", scenario.cashRequired.isFinite())
            assertTrue("dscr must be finite (sentinel allowed)", scenario.dscr.isFinite())
        }
    }

    @Test
    fun scenariosAreDeterministicAcrossRuns() {
        val (repository, dao) = repository()
        runBlocking { repository.underwriteProperty("p1") }
        val first = runBlocking { dao.getScenariosListForProperty("p1") }
            .sortedBy { it.scenarioName }
            .map { it.monthlyPayment to it.cashOnCash }
        runBlocking { repository.underwriteProperty("p1") }
        val second = runBlocking { dao.getScenariosListForProperty("p1") }
            .sortedBy { it.scenarioName }
            .map { it.monthlyPayment to it.cashOnCash }
        assertEquals(first, second)
    }

    // ------------------------------------------------------------------
    // all four strategies run through the repository
    // ------------------------------------------------------------------

    @Test
    fun everyStrategyRunsThroughTheRepository() {
        val deals = mapOf(
            InvestmentStrategy.BUY_AND_HOLD to UnderwritingInput(
                strategy = InvestmentStrategy.BUY_AND_HOLD,
                purchasePrice = 400000.0,
                monthlyRent = 3200.0,
                propertyTaxAnnual = 4800.0,
                insuranceAnnual = 2400.0,
                downPaymentPct = 20.0
            ),
            InvestmentStrategy.BRRRR to UnderwritingInput(
                strategy = InvestmentStrategy.BRRRR,
                purchasePrice = 250000.0,
                rehabCost = 45000.0,
                arv = 400000.0,
                monthlyRent = 2600.0
            ),
            InvestmentStrategy.FIX_AND_FLIP to UnderwritingInput(
                strategy = InvestmentStrategy.FIX_AND_FLIP,
                purchasePrice = 250000.0,
                rehabCost = 45000.0,
                arv = 400000.0
            ),
            InvestmentStrategy.WHOLESALE to UnderwritingInput(
                strategy = InvestmentStrategy.WHOLESALE,
                purchasePrice = 300000.0,
                arv = 420000.0,
                assignmentFee = 12000.0
            )
        )
        for ((strategy, input) in deals) {
            val (repository, _) = repository()
            val result = runBlocking { repository.underwriteProperty("p1", customInput = input) }
            assertEquals(strategy, result.strategy)
            assertNotNull("$strategy must produce returns", result.returns)
            when (strategy) {
                InvestmentStrategy.BUY_AND_HOLD -> assertNotNull(result.hold)
                InvestmentStrategy.BRRRR -> assertNotNull(result.brrrr)
                InvestmentStrategy.FIX_AND_FLIP -> assertNotNull(result.flip)
                InvestmentStrategy.WHOLESALE -> assertNotNull(result.wholesale)
            }
        }
    }

    @Test
    fun strategiesMissingRequiredInputsRefuseExplicitlyInsteadOfGuessing() {
        val (flipRepository, _) = repository()
        val flip = runBlocking { flipRepository.underwriteProperty("p1", strategy = InvestmentStrategy.FIX_AND_FLIP) }
        assertTrue(codes(flip).contains("FLIP_REQUIRES_ARV"))
        assertNull(flip.flip)
        assertEquals(DealRating.FAILS_CRITERIA, flip.verdict.rating)

        val (brrrrRepository, _) = repository()
        val brrrr = runBlocking { brrrrRepository.underwriteProperty("p1", strategy = InvestmentStrategy.BRRRR) }
        assertTrue(codes(brrrr).contains("BRRRR_REQUIRES_ARV"))
        assertNull(brrrr.brrrr)
        assertEquals(DealRating.FAILS_CRITERIA, brrrr.verdict.rating)
    }

    @Test
    fun wholesaleFromPropertyRowUsesTheDocumentedArvDefaultNotAnInventedOne() {
        val (repository, _) = repository()
        val result = runBlocking { repository.underwriteProperty("p1", strategy = InvestmentStrategy.WHOLESALE) }
        assertNotNull("wholesale block missing", result.wholesale)
        // the engine's documented default (1.25x contract price) - the repository adds nothing
        assertEquals(400000.0 * 1.25, result.wholesale!!.arv, 1e-9)
    }

    @Test
    fun strategyAndFinancingModelPassThroughTheFactory() {
        val build = PropertyUnderwritingFactory.build(
            property = property(),
            rentEstimate = rentEstimate(),
            taxRecord = taxRecord(),
            strategy = InvestmentStrategy.FIX_AND_FLIP,
            financingModel = FinancingModel.HARD_MONEY
        )
        assertEquals(InvestmentStrategy.FIX_AND_FLIP, build.input.strategy)
        assertEquals(FinancingModel.HARD_MONEY, build.input.financingModel)
    }

    // ------------------------------------------------------------------
    // legacy compatibility contract (automation / qualification)
    // ------------------------------------------------------------------

    @Test
    fun automationPortProjectsTheCanonicalResult() {
        val (repository, _) = repository()
        val projected = runBlocking { repository.runAnalysis("p1") }

        val build = PropertyUnderwritingFactory.build(
            property = property(),
            rentEstimate = rentEstimate(),
            taxRecord = taxRecord()
        )
        val canonical = UnderwritingEngine.analyze(build.input, build.issues)

        assertEquals(canonical.core.noiAnnual, projected.noiAnnual, 1e-9)
        assertEquals(canonical.core.monthlyCashFlow, projected.monthlyCashFlow, 1e-9)
        assertEquals(canonical.core.annualCashFlow, projected.annualCashFlow, 1e-9)
        assertEquals(canonical.core.totalCashRequired, projected.totalCashRequired, 1e-9)
        assertEquals(canonical.core.loanAmount, projected.loanAmount, 1e-9)
        assertEquals(canonical.core.capRateOnPricePct!!, projected.capRate, 1e-9)
        assertEquals(canonical.core.dscr!!, projected.dscr, 1e-9)
        assertEquals(build.input.monthlyRent, projected.input.monthlyRent, 1e-9)
    }

    @Test
    fun legacyFinancialInputIsTranslatedExplicitlyAndUnderwrittenCanonically() {
        val legacy = FinancialInput(
            purchasePrice = 500000.0,
            closingCosts = 10000.0,
            renovationCost = 20000.0,
            monthlyRent = 4500.0,
            otherMonthlyIncome = 200.0,
            vacancyRatePct = 5.0,
            propertyTaxAnnual = 6000.0,
            insuranceAnnual = 3000.0,
            maintenancePct = 5.0,
            managementPct = 8.0,
            utilitiesMonthly = 100.0,
            downPaymentPct = 20.0,
            interestRatePct = 6.5,
            loanTermYears = 30
        )
        val mapped = legacy.toUnderwritingInput()
        assertEquals(500000.0, mapped.purchasePrice, 0.0)
        assertEquals(10000.0, mapped.closingCosts!!, 0.0)
        assertEquals(20000.0, mapped.rehabCost, 0.0)
        assertEquals(4500.0, mapped.monthlyRent, 0.0)
        assertEquals(200.0, mapped.otherMonthlyIncome, 0.0)
        assertEquals(5.0, mapped.vacancyRatePct!!, 0.0)
        assertEquals(6000.0, mapped.propertyTaxAnnual!!, 0.0)
        assertEquals(3000.0, mapped.insuranceAnnual!!, 0.0)
        assertEquals(5.0, mapped.maintenancePctOfGsi!!, 0.0)
        assertEquals(8.0, mapped.managementPctOfEgi!!, 0.0)
        assertEquals(100.0, mapped.utilitiesMonthly!!, 0.0)
        assertEquals(20.0, mapped.downPaymentPct!!, 0.0)
        assertEquals(6.5, mapped.interestRatePct!!, 0.0)
        assertEquals(360, mapped.loanTermMonths)
        assertEquals(360, mapped.amortizationMonths)

        val (repository, dao) = repository()
        val projected = runBlocking { repository.analyzeProperty("p1", legacy) }
        val canonical = UnderwritingEngine.analyze(mapped)

        assertEquals(canonical.core.noiAnnual, projected.noiAnnual, 1e-9)
        assertEquals(canonical.core.monthlyCashFlow, projected.monthlyCashFlow, 1e-9)
        assertEquals(canonical.core.totalCashRequired, projected.totalCashRequired, 1e-9)
        assertEquals(canonical.core.dscr!!, projected.dscr, 1e-9)

        val entity = runBlocking { dao.getAnalysis("p1") }!!
        assertEquals(20000.0, entity.renovationCost, 1e-9)
        assertEquals(4500.0, entity.monthlyRent, 1e-9)
        assertEquals(6000.0, entity.propertyTaxAnnual, 1e-9)
    }

    @Test
    fun legacyAllCashScenarioGetsTheDocumentedSentinelNotADivisionByZero() {
        val allCash = FinancialInput(
            purchasePrice = 400000.0,
            monthlyRent = 3500.0,
            downPaymentPct = 100.0,
            interestRatePct = 0.0,
            loanTermYears = 0
        )
        val (repository, _) = repository()
        val projected = runBlocking { repository.analyzeProperty("p1", allCash) }

        assertEquals(0.0, projected.loanAmount, 1e-9)
        assertEquals(0.0, projected.monthlyDebtService, 1e-9)
        assertEquals(FinancialResultProjection.LEGACY_ALL_CASH_DSCR_SENTINEL, projected.dscr, 1e-9)
    }

    @Test
    fun projectionNeverEmitsNonFiniteValues() {
        val (repository, _) = repository(rentEstimate = null, taxRecord = null)
        val projected = runBlocking { repository.runAnalysis("p1") }
        val values = listOf(
            projected.grossRentalIncome, projected.effectiveRentalIncome,
            projected.operatingExpensesMonthly, projected.noiAnnual, projected.loanAmount,
            projected.monthlyDebtService, projected.monthlyCashFlow, projected.annualCashFlow,
            projected.capRate, projected.totalCashRequired, projected.cashOnCashReturn,
            projected.dscr, projected.breakEvenOccupancyPct
        )
        for (value in values) {
            assertTrue("projection value must be finite, was $value", value.isFinite())
        }
    }

    // ------------------------------------------------------------------
    // edge cases
    // ------------------------------------------------------------------

    @Test
    fun unknownPropertyIsRefused() {
        val (repository, _) = repository()
        try {
            runBlocking { repository.underwriteProperty("does-not-exist") }
            fail("expected IllegalArgumentException for an unknown property")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does-not-exist"))
        }
    }

    @Test
    fun zeroPricePropertyProducesAnExplicitErrorNotACrash() {
        val (repository, dao) = repository(property = property(price = 0.0))
        val result = runBlocking { repository.underwriteProperty("p1") }
        assertTrue(codes(result).contains("NON_POSITIVE_PURCHASE_PRICE"))
        assertNull(result.core.capRateOnPricePct)
        // the run is still persisted so the UI can show why it failed
        assertNotNull(runBlocking { dao.getAnalysis("p1") })
    }

    @Test
    fun hasAnalysisReflectsPersistence() {
        val (repository, _) = repository()
        assertFalse(runBlocking { repository.hasAnalysis("p1") })
        runBlocking { repository.underwriteProperty("p1") }
        assertTrue(runBlocking { repository.hasAnalysis("p1") })
    }
    // Audit characterizations: these document OPEN limitations, not safe-data guarantees.
    @Test
    fun auditStaleTaxYearCurrentlyHasNoFreshnessGate() {
        val old = PropertyUnderwritingFactory.build(property(), rentEstimate(), taxRecord().copy(assessmentYear = 1990))
        val current = PropertyUnderwritingFactory.build(property(), rentEstimate(), taxRecord().copy(assessmentYear = 2026))
        assertEquals(current, old) // F06: year is discarded, with no stale-observation warning.
    }

    @Test
    fun auditConflictingProviderRentRangeCurrentlyDoesNotBlockEstimate() {
        val supplied = rentEstimate().copy(rentRangeLow = 5000.0, rentRangeHigh = 1000.0, rentConfidenceScore = 0.0)
        val result = PropertyUnderwritingFactory.build(property(), supplied, taxRecord())
        assertEquals(3200.0, result.input.monthlyRent, 0.0)
        assertEquals(PropertyUnderwritingFactory.build(property(), rentEstimate(), taxRecord()).issues, result.issues)
    }

    @Test
    fun auditMissingRentCarriesFindingButUsesZeroScenarioInput() {
        val built = PropertyUnderwritingFactory.build(property(), null, null)
        assertEquals(0.0, built.input.monthlyRent, 0.0)
        assertTrue(built.issues.any { it.code == "MISSING_RENT_ESTIMATE" })
        assertTrue(built.issues.any { it.code == "MISSING_PROPERTY_TAX_RECORD" })
        assertNull(built.input.propertyTaxAnnual)
    }

}
