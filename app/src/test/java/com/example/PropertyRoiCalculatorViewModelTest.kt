package com.example

import com.example.domain.finance.underwriting.UnderwritingAssumptions
import com.example.domain.finance.underwriting.UnderwritingEngine
import com.example.domain.finance.underwriting.ValueSource
import com.example.ui.screens.roi.AiNarrative
import com.example.ui.screens.roi.CalculatedRoiMetrics
import com.example.ui.screens.roi.CalculationSource
import com.example.ui.screens.roi.LocalMarketDataInput
import com.example.ui.screens.roi.PropertyDetailsInput
import com.example.ui.screens.roi.RoiMetricsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The rule these tests exist to defend: **the language model never produces a
 * financial figure.**
 *
 * The ROI screen asks Gemini for qualitative commentary only. Every number it
 * shows comes from the deterministic underwriting engine, and these tests feed
 * the calculator deliberately hostile AI payloads - payloads that claim absurd
 * cap rates and cash flows - and prove the numbers do not move.
 */
class PropertyRoiCalculatorViewModelTest {

    private val property = PropertyDetailsInput(
        title = "Austin Duplex Opportunity",
        address = "2100 E 6th St",
        city = "Austin",
        state = "TX",
        zipCode = "78702",
        purchasePrice = 520000.0,
        squareFeet = 2200,
        bedrooms = 4,
        bathrooms = 3.0,
        renovationCost = 20000.0,
        downPaymentPct = 25.0
    )

    private val market = LocalMarketDataInput(
        medianAreaPrice = 550000.0,
        pricePerSqFt = 265.0,
        estimatedMonthlyRent = 4200.0,
        neighborhoodAppreciationRatePct = 5.0,
        marketDemand = "High"
    )

    @Test
    fun testPropertyDetailsAndMarketDataInputs() {
        assertEquals("Austin Duplex Opportunity", property.title)
        assertEquals(520000.0, property.purchasePrice, 0.01)
        assertEquals(4200.0, market.estimatedMonthlyRent, 0.01)
        assertEquals(5.0, market.neighborhoodAppreciationRatePct, 0.01)
    }

    @Test
    fun deterministicMetricsComeFromTheUnderwritingEngine() {
        val metrics = RoiMetricsCalculator.deterministic(property, market)
        val engineResult = UnderwritingEngine.analyze(
            RoiMetricsCalculator.underwritingInput(property, market)
        )

        assertEquals(UnderwritingAssumptions.SPEC_VERSION, metrics.specVersion)
        assertEquals(engineResult.core.capRateOnPricePct!!, metrics.capRatePct!!, 1e-9)
        assertEquals(engineResult.core.cashOnCashPct!!, metrics.cashOnCashReturnPct!!, 1e-9)
        assertEquals(engineResult.core.dscr!!, metrics.debtServiceCoverageRatio!!, 1e-9)
        assertEquals(engineResult.core.noiAnnual, metrics.netOperatingIncomeAnnual, 1e-6)
        assertEquals(
            engineResult.operating.grossScheduledIncomeAnnual,
            metrics.grossRentalIncomeAnnual,
            1e-6
        )
        assertEquals(engineResult.core.totalCashRequired, metrics.totalCashRequired, 1e-6)
    }

    @Test
    fun headlineNumbersAreInternallyConsistent() {
        val metrics = RoiMetricsCalculator.deterministic(property, market)
        // Annual cash flow is NOI less debt service; the monthly figure is a twelfth of it.
        val annualDebtService = UnderwritingEngine.analyze(
            RoiMetricsCalculator.underwritingInput(property, market)
        ).core.annualDebtService
        assertEquals(
            metrics.netOperatingIncomeAnnual - annualDebtService,
            metrics.annualCashFlow,
            1e-6
        )
        assertEquals(metrics.annualCashFlow / 12.0, metrics.monthlyCashFlow, 1e-6)
        // Cash-on-cash is the annual cash flow on the cash actually required.
        assertEquals(
            metrics.annualCashFlow / metrics.totalCashRequired * 100.0,
            metrics.cashOnCashReturnPct!!,
            1e-6
        )
        assertTrue("the five-year projection must be a real number", metrics.projected5YearRoiPct.isFinite())
    }

    @Test
    fun theEnginesAssumptionsAreSurfacedNotHidden() {
        val metrics = RoiMetricsCalculator.deterministic(property, market)
        assertTrue("assumption provenance must be reported", metrics.assumptionsUsed.isNotEmpty())
        // A defaulted value must say it was defaulted, never masquerade as user input.
        val sources = metrics.assumptionsUsed.values.map { it.source }.toSet()
        assertTrue(sources.contains(ValueSource.EXPLICIT))
        assertTrue(
            "fields the user did not state must be marked MODEL_DEFAULT",
            metrics.assumptionsUsed.values.count { it.source == ValueSource.MODEL_DEFAULT } > 0
        )
    }

    @Test
    fun hostileAiNumbersCannotChangeASingleMetric() {
        val before = RoiMetricsCalculator.deterministic(property, market)
        val hostilePayload = """
            {
                "capRatePct": 99.9,
                "cashOnCashReturnPct": 98.7,
                "monthlyCashFlow": 999999.0,
                "annualCashFlow": 12000000.0,
                "netOperatingIncomeAnnual": 1.0,
                "totalCashRequired": 1.0,
                "debtServiceCoverageRatio": 42.0,
                "projected5YearRoiPct": 5000.0,
                "breakEvenOccupancyPct": 1.0,
                "investmentVerdict": "Guaranteed 500% return",
                "marketInsights": "Demand in this submarket remains firm with constrained supply.",
                "riskFactors": ["Tenant turnover", "Property tax reassessment"],
                "localMarketScore": 100
            }
        """.trimIndent()

        val narrative = RoiMetricsCalculator.narrativeFrom(hostilePayload)
        assertNotNull("well-formed commentary is still accepted", narrative)
        val after = RoiMetricsCalculator.withNarrative(before, narrative!!)

        // every field except the two prose fields is untouched
        assertEquals(
            before.copy(marketInsights = after.marketInsights, riskFactors = after.riskFactors),
            after
        )
        assertNotEquals("AI cap rate must never be shown", 99.9, metricsCapRate(after))
        assertNotEquals("AI cash flow must never be shown", 999999.0, after.monthlyCashFlow)
        assertEquals(before.monthlyCashFlow, after.monthlyCashFlow, 0.0)
        assertEquals(before.totalCashRequired, after.totalCashRequired, 0.0)
        assertEquals(before.projected5YearRoiPct, after.projected5YearRoiPct, 0.0)
        // the prose itself does come from the model
        assertEquals(
            "Demand in this submarket remains firm with constrained supply.",
            after.marketInsights
        )
        assertEquals(listOf("Tenant turnover", "Property tax reassessment"), after.riskFactors)
    }

    private fun metricsCapRate(metrics: CalculatedRoiMetrics): Double = metrics.capRatePct ?: 0.0

    @Test
    fun malformedOrMetricOnlyAiPayloadsAreDiscarded() {
        assertNull(RoiMetricsCalculator.narrativeFrom(null))
        assertNull(RoiMetricsCalculator.narrativeFrom(""))
        assertNull(RoiMetricsCalculator.narrativeFrom("   "))
        assertNull(RoiMetricsCalculator.narrativeFrom("I could not analyse this property."))
        assertNull(RoiMetricsCalculator.narrativeFrom("{ not json at all"))
        assertNull(
            "a payload that only carries numbers has no commentary to offer",
            RoiMetricsCalculator.narrativeFrom("""{"capRatePct": 7.4, "monthlyCashFlow": 620.0}""")
        )
    }

    @Test
    fun narrativeSurvivesMarkdownFencesAndMissingRiskFactorList() {
        val narrative = RoiMetricsCalculator.narrativeFrom(
            """
            ```json
            {"marketInsights": "Tight supply keeps rents firm."}
            ```
            """.trimIndent()
        )
        assertNotNull(narrative)
        assertEquals("Tight supply keeps rents firm.", narrative!!.marketInsights)
        assertTrue(narrative.riskFactors.isEmpty())
    }

    @Test
    fun anEmptyNarrativeNeverErasesTheDeterministicProse() {
        val before = RoiMetricsCalculator.deterministic(property, market)
        val after = RoiMetricsCalculator.withNarrative(before, AiNarrative("", emptyList()))
        assertEquals(before.marketInsights, after.marketInsights)
        assertEquals(before.riskFactors, after.riskFactors)
        assertEquals(before, after)
    }

    @Test
    fun aiIsNotEvenAValidCalculationSource() {
        val sources = CalculationSource.entries.map { it.name }
        assertFalse("AI must never be a calculation source", sources.any { it.contains("AI") })
        assertFalse(sources.any { it.contains("GEMINI") })
        assertEquals(listOf("NONE", "DETERMINISTIC_ENGINE"), sources)
    }

    @Test
    fun anAllCashPurchaseReportsNoDscrRatherThanAnInventedSentinel() {
        val allCash = property.copy(downPaymentPct = 100.0)
        val metrics = RoiMetricsCalculator.deterministic(allCash, market)
        assertNull("no debt means no DSCR", metrics.debtServiceCoverageRatio)
        // with no debt service, cash flow is exactly NOI
        assertEquals(metrics.netOperatingIncomeAnnual, metrics.annualCashFlow, 1e-6)
        assertTrue(metrics.cashOnCashReturnPct!!.isFinite())
    }

    @Test
    fun findingsAreReportedAndOrderedBySeverity() {
        val metrics = RoiMetricsCalculator.deterministic(
            property.copy(purchasePrice = 0.0, renovationCost = -1.0),
            market
        )
        assertTrue("a zero price must be reported", metrics.findings.isNotEmpty())
        assertNull("no price means no meaningful cap rate", metrics.capRatePct)
        val severities = metrics.findings.map { it.severity.ordinal }
        assertEquals(severities.sorted(), severities)
    }

    @Test
    fun testCalculatedRoiMetricsCompleteness() {
        val metrics = CalculatedRoiMetrics(
            capRatePct = 6.8,
            cashOnCashReturnPct = 8.5,
            monthlyCashFlow = 450.0,
            annualCashFlow = 5400.0,
            netOperatingIncomeAnnual = 32000.0,
            grossRentalIncomeAnnual = 42000.0,
            totalCashRequired = 110000.0,
            debtServiceCoverageRatio = 1.28,
            grossRentMultiplier = 11.2,
            projected5YearRoiPct = 52.4,
            breakEvenOccupancyPct = 74.0,
            investmentVerdict = "Moderate Opportunity",
            recommendedStrategy = "Turnkey Cash Flow",
            marketInsights = "Healthy cash flow in expanding suburban pocket.",
            riskFactors = listOf("Tenant turnover", "Maintenance reserve"),
            localMarketScore = 78
        )

        assertTrue(metrics.capRatePct!! > 0)
        assertTrue(metrics.cashOnCashReturnPct!! > 0)
        assertTrue(metrics.debtServiceCoverageRatio!! > 1.0)
        assertEquals(2, metrics.riskFactors.size)
        assertEquals("Moderate Opportunity", metrics.investmentVerdict)
        assertTrue(abs(metrics.capRatePct!! - 6.8) < 0.01)
    }
}
