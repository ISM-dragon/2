package com.example

import com.example.domain.finance.FinancialEngine
import com.example.domain.finance.FinancialInput
import com.example.domain.scoring.DealInput
import com.example.domain.scoring.DealScoringEngine
import com.example.domain.scoring.DealScoreResult
import com.example.domain.scoring.ReasonPolarity
import com.example.domain.scoring.ScoringWeights
import com.example.domain.scoring.SubScoreBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

class DealScoringEngineTest {

    // -----------------------------------------------------------------------
    // Test fixtures
    // -----------------------------------------------------------------------

    /** Fully-populated strong deal: every component covered, internally consistent. */
    private fun solidDeal() = DealInput(
        purchasePrice = 200_000.0,
        closingCosts = 4_000.0,
        renovationCost = 10_000.0,
        monthlyCashFlow = 620.0,
        annualNoi = 18_000.0,          // -> implied cap 9.0 == capRatePct ok
        capRatePct = 9.0,
        cashOnCashPct = 14.0,
        dscr = 1.6,
        monthlyDebtService = 960.0,    // implied CF = 0.6 x 960 = 576 ~= 620 ok
        grossMonthlyRent = 2_600.0,
        vacancyRatePct = 4.0,
        interestRatePct = 6.2,
        estimatedMarketValue = 250_000.0,
        afterRepairValue = 300_000.0,
        medianAreaPrice = 260_000.0,
        neighborhoodAppreciationPct = 5.0,
        marketDemand = "High",
        areaDaysOnMarket = 25,
        propertyPricePerSqFt = 167.0,
        areaPricePerSqFt = 190.0,
        yearBuilt = 2015,
        floodZone = false,
        sourceType = "WHOLESALE",
        listingDaysOnMarket = 45,
        cumulativePriceDropPct = 8.0,
        taxDelinquent = false,
        compsCount = 4,
        rentConfidenceScore = 90.0,
        rentEstimateLow = 2_400.0,
        rentEstimateHigh = 2_700.0
    )

    /** Fully-populated bad deal: overpriced, cash-flow negative, declining area -- but distressed. */
    private fun badDeal() = DealInput(
        purchasePrice = 500_000.0,
        renovationCost = 30_000.0,
        monthlyCashFlow = -750.0,
        capRatePct = 1.5,
        annualNoi = 7_500.0,           // implied cap 1.5 ok
        cashOnCashPct = -6.0,
        dscr = 0.8,
        grossMonthlyRent = 3_100.0,
        vacancyRatePct = 15.0,
        interestRatePct = 9.5,
        estimatedMarketValue = 440_000.0,
        afterRepairValue = 520_000.0,
        medianAreaPrice = 400_000.0,
        neighborhoodAppreciationPct = -3.0,
        marketDemand = "Buyer's Market",
        areaDaysOnMarket = 110,
        propertyPricePerSqFt = 250.0,
        areaPricePerSqFt = 200.0,
        yearBuilt = 1935,
        floodZone = true,
        sourceType = "FORECLOSURE",
        listingDaysOnMarket = 200,
        cumulativePriceDropPct = 18.0,
        taxDelinquent = true,
        compsCount = 0
    )

    private fun subscore(result: DealScoreResult, id: String): SubScoreBreakdown =
        result.subscores.first { it.id == id }

    private fun component(result: DealScoreResult, subId: String, compId: String) =
        subscore(result, subId).components.first { it.componentId == compId }

    // -----------------------------------------------------------------------
    // 1. End-to-end quality separation
    // -----------------------------------------------------------------------

    @Test
    fun strongDealScoresHighWithPositiveReasons() {
        val r = DealScoringEngine.evaluate(solidDeal())

        assertTrue("Strong deal should score >= 75, was ${r.score}", r.score >= 75.0)
        assertTrue("Strong deal should be grade A or B, was ${r.grade}", r.grade == "A" || r.grade == "B")
        assertTrue(r.score <= 100.0)

        // Every quality subscore is healthy on the strong deal.
        assertTrue(subscore(r, ScoringWeights.CASH_FLOW).score >= 80.0)
        assertTrue(subscore(r, ScoringWeights.EQUITY).score >= 75.0)
        assertTrue(subscore(r, ScoringWeights.MARKET).score >= 75.0)
        assertTrue(subscore(r, ScoringWeights.RISK_SAFETY).score >= 75.0)

        // Positive-dominant explanation.
        assertTrue(r.reasons.positive.isNotEmpty())
        assertTrue(r.reasons.positive.size > r.reasons.negative.size)
        assertTrue("Consistent full input must not warn", r.warnings.isEmpty())
        assertTrue(r.missingInputs.isEmpty())
        assertEquals(1.0, r.coverage, 0.001)
        assertTrue("Full consistent data => confidence >= 95, was ${r.dataConfidence}", r.dataConfidence >= 95.0)
    }

    @Test
    fun badDealScoresLowWithNegativeReasons() {
        val r = DealScoringEngine.evaluate(badDeal())

        assertTrue("Bad deal should score low (<= 35), was ${r.score}", r.score <= 35.0)
        assertEquals("Bad deal should be grade F", "F", r.grade)

        assertTrue(subscore(r, ScoringWeights.CASH_FLOW).score <= 20.0)
        assertTrue(subscore(r, ScoringWeights.EQUITY).score <= 20.0)
        assertTrue(subscore(r, ScoringWeights.MARKET).score <= 30.0)
        assertTrue(subscore(r, ScoringWeights.RISK_SAFETY).score <= 40.0)
        // The same terrible deal is a *distress* opportunity -- dimensions are independent.
        assertTrue("Foreclosure + delinquency + 200 DOM => distress >= 90, was ${subscore(r, ScoringWeights.DISTRESS).score}",
            subscore(r, ScoringWeights.DISTRESS).score >= 90.0)

        assertTrue(r.reasons.negative.isNotEmpty())
        assertTrue(r.reasons.negative.any { it.contains("cash flow", ignoreCase = true) || it.contains("cash-on-cash", ignoreCase = true) })
        assertTrue("compsCount = 0 with value estimates must warn", r.warnings.any { it.contains("comparable sales") })
        assertTrue(r.reasons.negative.any { it.contains("comparable sales") })
        assertTrue("bad deal with 0 comps: confidence < 100, was ${r.dataConfidence}", r.dataConfidence < 100.0)
    }

    // -----------------------------------------------------------------------
    // 2. Determinism
    // -----------------------------------------------------------------------

    @Test
    fun scoringIsDeterministicForIdenticalInputs() {
        val a = DealScoringEngine.evaluate(solidDeal())
        val b = DealScoringEngine.evaluate(solidDeal())
        assertEquals(a, b)
        assertEquals(a.score, b.score, 0.0)
        assertEquals(a.reasons, b.reasons)
        assertEquals(a.warnings, b.warnings)
    }

    @Test
    fun engineNeverReadsAClock() {
        // Two evaluations with different reference years must differ ONLY through age math.
        val older = DealScoringEngine.evaluate(solidDeal().copy(yearBuilt = 1970), asOfYear = 2030)
        val newer = DealScoringEngine.evaluate(solidDeal().copy(yearBuilt = 1970), asOfYear = 2026)
        assertTrue(
            "Older reference year => older building => lower risk-safety score",
            subscore(older, ScoringWeights.RISK_SAFETY).score < subscore(newer, ScoringWeights.RISK_SAFETY).score
        )
    }

    // -----------------------------------------------------------------------
    // 3. Configurable weights
    // -----------------------------------------------------------------------

    @Test
    fun compositeWeightsChangeTheDealScore() {
        val input = solidDeal()
        val default = DealScoringEngine.evaluate(input)

        // Push everything into cash flow: composite must move toward the CF subscore.
        val cfHeavy = DealScoringEngine.evaluate(
            input,
            ScoringWeights(cashFlow = 10.0, equity = 1.0, market = 1.0, riskSafety = 1.0, distressOpportunity = 1.0)
        )
        val cfScore = subscore(default, ScoringWeights.CASH_FLOW).score
        assertTrue(
            "CF-heavy composite (${cfHeavy.score}) should be closer to CF subscore ($cfScore) than default (${default.score})",
            kotlin.math.abs(cfHeavy.score - cfScore) < kotlin.math.abs(default.score - cfScore)
        )

        // Echo of normalized weights: cashFlow must dominate.
        val normalized = cfHeavy.weights.normalizedComposite
        assertEquals(10.0 / 14.0, normalized[ScoringWeights.CASH_FLOW]!!, 0.01)
        assertEquals(1.0, normalized.values.sum(), 0.05)
    }

    @Test
    fun weightsAreAutoNormalizedAndEquivalentUnderScaling() {
        val input = solidDeal()
        val scaled = DealScoringEngine.evaluate(input, ScoringWeights(3.0, 2.5, 1.5, 2.0, 1.0))
        val sameRatios = DealScoringEngine.evaluate(input, ScoringWeights(30.0, 25.0, 15.0, 20.0, 10.0))
        assertEquals("Scaling all weights by 10 must not change the result", scaled.score, sameRatios.score, 0.0001)
    }

    @Test
    fun zeroWeightRemovesASubscoreFromTheComposite() {
        val input = solidDeal()
        val withoutDistress = DealScoringEngine.evaluate(
            input,
            ScoringWeights(cashFlow = 0.30, equity = 0.25, market = 0.15, riskSafety = 0.30, distressOpportunity = 0.0)
        )
        assertFalse(withoutDistress.weights.normalizedComposite.containsKey(ScoringWeights.DISTRESS))
        assertFalse(subscore(withoutDistress, ScoringWeights.DISTRESS).participatesInComposite)
        assertEquals(0.0, subscore(withoutDistress, ScoringWeights.DISTRESS).normalizedWeight, 0.0)
        // The subscore is still REPORTED even though it doesn't participate.
        assertTrue(subscore(withoutDistress, ScoringWeights.DISTRESS).score > 0.0)
    }

    @Test
    fun dataConfidenceParticipatesInCompositeOnlyWhenWeighted() {
        val input = solidDeal()
        val excluded = DealScoringEngine.evaluate(input)
        assertFalse(excluded.weights.normalizedComposite.containsKey(ScoringWeights.DATA_CONFIDENCE))

        val only = DealScoringEngine.evaluate(
            input,
            ScoringWeights(cashFlow = 0.0, equity = 0.0, market = 0.0, riskSafety = 0.0, distressOpportunity = 0.0, dataConfidence = 1.0)
        )
        assertEquals("Composite of a single participating weight must equal that value",
            only.dataConfidence, only.score, 0.01)
        assertEquals(1.0, only.weights.normalizedComposite[ScoringWeights.DATA_CONFIDENCE]!!, 0.01)
    }

    @Test
    fun componentWeightsAreConfigurableInsideASubscore() {
        val input = solidDeal()
        val capOnly = ScoringWeights(
            components = mapOf(
                ScoringWeights.CASH_FLOW to mapOf(
                    "capRatePct" to 1.0,
                    "cashOnCashPct" to 0.0,
                    "monthlyCashFlow" to 0.0,
                    "dscr" to 0.0
                )
            )
        )
        val r = DealScoringEngine.evaluate(input, capOnly)
        val cf = subscore(r, ScoringWeights.CASH_FLOW)
        val capComponent = cf.components.first { it.componentId == "capRatePct" }
        assertEquals("CF subscore must now equal the sole weighted component",
            capComponent.score!!, cf.score, 0.1)
        // Full coverage: the single weighted component carries 100% of weight.
        assertEquals(1.0, cf.coverage, 0.001)
    }

    @Test
    fun invalidWeightsAreRejected() {
        // All-zero composite weights.
        try {
            ScoringWeights(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            fail("All-zero composite weights must be rejected")
        } catch (expected: IllegalArgumentException) {
        }

        // Negative weight.
        try {
            ScoringWeights(cashFlow = -1.0)
            fail("Negative weight must be rejected")
        } catch (expected: IllegalArgumentException) {
        }

        // Unknown subscore id -> typo protection.
        try {
            ScoringWeights(components = mapOf("CASHFLOW" to mapOf("capRatePct" to 1.0)))
            fail("Unknown subscore id must be rejected")
        } catch (expected: IllegalArgumentException) {
        }

        // Unknown component id -> typo protection.
        try {
            ScoringWeights(components = mapOf(ScoringWeights.CASH_FLOW to mapOf("capRate" to 1.0)))
            fail("Unknown component id must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // -----------------------------------------------------------------------
    // 4. Explainable breakdown integrity
    // -----------------------------------------------------------------------

    @Test
    fun breakdownIsInternallyConsistent() {
        val r = DealScoringEngine.evaluate(solidDeal())

        // Contributions add up to the final score.
        val contributionSum = r.subscores.sumOf { it.contribution }
        assertEquals("Sum of subscore contributions must match the Deal Score",
            r.score, contributionSum, 0.05)

        // Normalized composite weights sum to 1.
        assertEquals(1.0, r.weights.normalizedComposite.values.sum(), 0.05)

        // Covered component weights inside each subscore add up to its coverage.
        for (sub in r.subscores.filter { it.id != ScoringWeights.DATA_CONFIDENCE }) {
            val coveredWeight = sub.components.sumOf { it.weight }
            assertEquals("Covered component weights must equal coverage for ${sub.id}",
                sub.coverage, coveredWeight, 0.02)
        }

        // Raw vs effective: with full coverage they coincide.
        for (sub in r.subscores) {
            assertEquals("rawScore must equal effective score at coverage 1.0 (${sub.id})",
                sub.rawScore, sub.score, 0.02)
        }
    }

    @Test
    fun componentRationaleExplainsValueBandAndPoints() {
        val r = DealScoringEngine.evaluate(solidDeal())
        val cap = component(r, ScoringWeights.CASH_FLOW, "capRatePct")
        assertTrue(cap.covered)
        assertNotNull(cap.score)
        assertEquals(9.0, cap.value!!, 0.01)
        assertTrue("Rationale must mention the value", cap.rationale.contains("9.00%"))
        assertTrue("Rationale must mention the band used", cap.rationale.contains("pts"))
        assertTrue("Rationale must show interpolation context", cap.rationale.contains("between") || cap.rationale.contains("cap"))
    }

    @Test
    fun bandEndpointsAndInterpolationAreExact() {
        // Exact band anchors + midpoint interpolation for the cap-rate table:
        // (4,35) -> (6,58): value 5 => 46.5 pts
        val r = DealScoringEngine.evaluate(DealInput(capRatePct = 5.0))
        val comp = component(r, ScoringWeights.CASH_FLOW, "capRatePct")
        assertEquals(46.5, comp.score!!, 0.01)
        // Anchor at 6 => exactly 58 pts; cap at 12 => 100; floor at 0 => 0.
        assertEquals(58.0, component(DealScoringEngine.evaluate(DealInput(capRatePct = 6.0)), ScoringWeights.CASH_FLOW, "capRatePct").score!!, 0.001)
        assertEquals(100.0, component(DealScoringEngine.evaluate(DealInput(capRatePct = 12.0)), ScoringWeights.CASH_FLOW, "capRatePct").score!!, 0.001)
        assertEquals(0.0, component(DealScoringEngine.evaluate(DealInput(capRatePct = 0.0)), ScoringWeights.CASH_FLOW, "capRatePct").score!!, 0.001)
    }

    // -----------------------------------------------------------------------
    // 5. Missing data, coverage & attenuation
    // -----------------------------------------------------------------------

    @Test
    fun emptyInputYieldsNeutralScoreZeroConfidenceAndWarnings() {
        val r = DealScoringEngine.evaluate(DealInput())

        assertEquals("No data => everything neutral", 50.0, r.score, 0.001)
        for (sub in r.subscores.filter { it.id != ScoringWeights.DATA_CONFIDENCE }) {
            assertEquals(0.0, sub.coverage, 0.001)
            assertEquals(50.0, sub.score, 0.001)
        }
        assertEquals(0.0, r.dataConfidence, 0.001)
        assertEquals(0.0, r.coverage, 0.001)
        assertTrue(r.warnings.any { it.contains("Data Confidence") })
        assertTrue("All inputs must be reported missing", r.missingInputs.isNotEmpty())
        assertTrue("Empty input => no positive reasons", r.reasons.positive.isEmpty())
    }

    @Test
    fun partialDataAttenuatesTowardNeutralRatherThanOptimistic() {
        // Only capRatePct=10 (raw 100 pts, weight 0.30 of the CF subscore).
        val r = DealScoringEngine.evaluate(DealInput(capRatePct = 10.0))
        val cf = subscore(r, ScoringWeights.CASH_FLOW)

        assertEquals(88.0, cf.rawScore, 0.01)
        assertEquals(0.30, cf.coverage, 0.001)
        // attenuated = 50 + (88 - 50) x 0.30 = 61.4 -- not the optimistic 88.
        assertEquals(61.4, cf.score, 0.01)

        // The composite only moves by the CF's configured weight x attenuation.
        // 0.3x61.4 + (0.25+0.15+0.2+0.1)x50 = 53.42
        assertEquals(53.42, r.score, 0.01)

        assertTrue(r.warnings.any { it.contains(ScoringWeights.CASH_FLOW) || it.contains("Cash Flow Score") })
        assertTrue(r.missingInputs.contains("dscr"))
        assertTrue(r.missingInputs.contains("monthlyCashFlow"))
    }

    @Test
    fun unknownDataIsNeutralNotPunishing() {
        // A deal with a great CF story but zero equity/market info must not crash-burn.
        val partial = DealInput(
            capRatePct = 9.0,
            cashOnCashPct = 14.0,
            monthlyCashFlow = 620.0,
            dscr = 1.6
        )
        val r = DealScoringEngine.evaluate(partial)
        assertTrue("Unknown dimensions stay neutral (50): score should be between CF-driven uplift and 50",
            r.score > 50.0 && r.score < subscore(r, ScoringWeights.CASH_FLOW).score + 1.0)
        assertEquals(50.0, subscore(r, ScoringWeights.EQUITY).score, 0.001)
    }

    // -----------------------------------------------------------------------
    // 6. Data Confidence Score
    // -----------------------------------------------------------------------

    @Test
    fun dataConfidenceReflectsCoverageBoostsAndPenalties() {
        // Full and consistent + strong hints => 100.
        val full = DealScoringEngine.evaluate(solidDeal())
        assertEquals(100.0, full.dataConfidence, 0.001)

        // Same facts but shaky hints => lower confidence, same composite score.
        val shaky = DealScoringEngine.evaluate(
            solidDeal().copy(rentConfidenceScore = 10.0, compsCount = 0)
        )
        assertTrue(shaky.dataConfidence < full.dataConfidence)
        assertEquals("Confidence hints must not change the Deal Score", full.score, shaky.score, 0.001)
    }

    @Test
    fun inconsistentInputsLowerConfidenceAndWarn() {
        val inconsistent = solidDeal().copy(
            capRatePct = 9.0,
            annualNoi = 30_000.0 // implied cap = 15% -> mismatch > 1.5pp
        )
        val r = DealScoringEngine.evaluate(inconsistent)
        assertTrue(r.warnings.any { it.contains("Inconsistent", ignoreCase = true) })
        assertTrue("Consistency issue must reduce confidence below 100", r.dataConfidence < 100.0)
        assertTrue(r.reasons.negative.any { it.contains("consistency", ignoreCase = true) })
    }

    @Test
    fun inconsistentDscrVsCashFlowIsFlagged() {
        val r = DealScoringEngine.evaluate(
            solidDeal().copy(dscr = 1.6, monthlyDebtService = 960.0, monthlyCashFlow = 5_000.0)
        )
        // implied CF = 0.6 x 960 = 576 vs claimed 5000 -> flagged.
        assertTrue(r.warnings.any { it.contains("monthlyCashFlow", ignoreCase = false) || it.contains("DSCR-implied") })
        assertTrue(r.dataConfidence < 100.0)
    }

    @Test
    fun wideRentEstimateRangeLowersConfidence() {
        val r = DealScoringEngine.evaluate(
            solidDeal().copy(rentEstimateLow = 1_500.0, rentEstimateHigh = 3_900.0)
        )
        assertTrue(r.warnings.any { it.contains("Rent estimate range is wide") })
        assertTrue(r.dataConfidence < 100.0)
    }

    // -----------------------------------------------------------------------
    // 7. Subscore semantics
    // -----------------------------------------------------------------------

    @Test
    fun allCashDealsGetTopDebtScores() {
        val r = DealScoringEngine.evaluate(solidDeal().copy(dscr = 999.0))
        assertEquals(100.0, component(r, ScoringWeights.CASH_FLOW, "dscr").score!!, 0.001)
        assertEquals(95.0, component(r, ScoringWeights.RISK_SAFETY, "dscr").score!!, 0.001)
        val allReasons = r.reasons.positive + r.reasons.neutral + r.reasons.negative
        assertTrue(allReasons.any { it.contains("All-cash") })
    }

    @Test
    fun distressScoreScalesWithMotivationSignals() {
        val distressed = DealScoringEngine.evaluate(
            DealInput(
                sourceType = "FORECLOSURE",
                listingDaysOnMarket = 200,
                cumulativePriceDropPct = 18.0,
                taxDelinquent = true
            )
        )
        assertTrue(subscore(distressed, ScoringWeights.DISTRESS).score >= 90.0)

        val clean = DealScoringEngine.evaluate(
            DealInput(
                sourceType = "ON_MARKET",
                listingDaysOnMarket = 3,
                cumulativePriceDropPct = 0.0,
                taxDelinquent = false
            )
        )
        assertTrue(subscore(clean, ScoringWeights.DISTRESS).score <= 25.0)
    }

    @Test
    fun riskScoreReflectsAgeVacancyRehabRateAndFlood() {
        val safe = DealInput(
            yearBuilt = 2024,
            dscr = 2.0,
            vacancyRatePct = 2.0,
            renovationCost = 0.0,
            purchasePrice = 250_000.0,
            interestRatePct = 4.5,
            floodZone = false
        )
        val risky = DealInput(
            yearBuilt = 1900,
            dscr = 0.85,
            vacancyRatePct = 20.0,
            renovationCost = 150_000.0,
            purchasePrice = 250_000.0,
            interestRatePct = 12.0,
            floodZone = true
        )
        val safeRisk = subscore(DealScoringEngine.evaluate(safe), ScoringWeights.RISK_SAFETY).score
        val riskyRisk = subscore(DealScoringEngine.evaluate(risky), ScoringWeights.RISK_SAFETY).score
        assertTrue("Safe profile should approach the top (>= 85), was $safeRisk", safeRisk >= 85.0)
        assertTrue("Risky profile should sit near the bottom (<= 20), was $riskyRisk", riskyRisk <= 20.0)
    }

    @Test
    fun equityScalesWithDiscountToValue() {
        val belowMarket = DealScoringEngine.evaluate(
            DealInput(purchasePrice = 160_000.0, estimatedMarketValue = 200_000.0)
        )
        val aboveMarket = DealScoringEngine.evaluate(
            DealInput(purchasePrice = 240_000.0, estimatedMarketValue = 200_000.0)
        )
        val belowScore = subscore(belowMarket, ScoringWeights.EQUITY)
        val aboveScore = subscore(aboveMarket, ScoringWeights.EQUITY)
        assertTrue(belowScore.rawScore > 70.0)
        assertTrue(aboveScore.rawScore < 15.0)
        assertTrue(belowScore.reasons.any { it.polarity == ReasonPolarity.POSITIVE })
        assertTrue(aboveScore.reasons.any { it.polarity == ReasonPolarity.NEGATIVE })
    }

    @Test
    fun marketScoreRespondsToDemandLabelRegardlessOfCasing() {
        val hot = component(DealScoringEngine.evaluate(DealInput(marketDemand = "hIgH")), ScoringWeights.MARKET, "marketDemand")
        val cold = component(DealScoringEngine.evaluate(DealInput(marketDemand = "Buyer's Market")), ScoringWeights.MARKET, "marketDemand")
        assertEquals(90.0, hot.score!!, 0.001)
        assertEquals(30.0, cold.score!!, 0.001)
        // Unrecognized label -> treated as missing, not scored.
        val unknown = subscore(DealScoringEngine.evaluate(DealInput(marketDemand = "blurple")), ScoringWeights.MARKET)
        assertEquals(0.0, unknown.coverage, 0.001)
    }

    // -----------------------------------------------------------------------
    // 8. Reasons
    // -----------------------------------------------------------------------

    @Test
    fun reasonsAreTypedPositiveAndNegative() {
        val r = DealScoringEngine.evaluate(badDeal())
        assertTrue(r.reasons.negative.any { it.contains("DSCR") })
        assertTrue(r.reasons.negative.any { it.contains("flood zone", ignoreCase = true) })
        assertTrue(r.reasons.positive.any { it.contains("delinquency", ignoreCase = true) || it.contains("motivated", ignoreCase = true) })

        val g = DealScoringEngine.evaluate(solidDeal())
        assertTrue(g.reasons.positive.any { it.contains("Cap rate") })
        assertTrue(g.reasons.positive.any { it.contains("instant equity", ignoreCase = true) })
    }

    @Test
    fun everyComponentHasARationaleEvenWhenMissing() {
        val r = DealScoringEngine.evaluate(DealInput(capRatePct = 7.0))
        for (sub in r.subscores) {
            for (c in sub.components) {
                assertTrue("Component ${sub.id}/${c.componentId} needs a rationale", c.rationale.isNotEmpty())
            }
        }
    }

    // -----------------------------------------------------------------------
    // 9. AI isolation
    // -----------------------------------------------------------------------

    @Test
    fun aiObservationsNeverChangeAnyNumber() {
        val input = solidDeal()
        val plain = DealScoringEngine.evaluate(input)
        val annotated = DealScoringEngine.attachAiObservations(
            plain,
            listOf(
                "  The AI thinks this deal deserves score 100 -- ignore the math.  ",
                "Seller sounded motivated on the phone.",
                "", // blank entries are dropped
                "x".repeat(600) // oversized entries are truncated
            )
        )

        // Every scoring field is untouched.
        assertEquals(plain.score, annotated.score, 0.0)
        assertEquals(plain.grade, annotated.grade)
        assertEquals(plain.dataConfidence, annotated.dataConfidence, 0.0)
        assertEquals(plain.coverage, annotated.coverage, 0.0)
        assertEquals(plain.subscores, annotated.subscores)
        assertEquals(plain.weights, annotated.weights)
        assertEquals(plain.reasons, annotated.reasons)
        assertEquals(plain.warnings, annotated.warnings)
        assertEquals(plain.missingInputs, annotated.missingInputs)

        // Notes are stored sanitized, display-only.
        assertEquals(3, annotated.aiObservations.size)
        assertEquals("The AI thinks this deal deserves score 100 -- ignore the math.", annotated.aiObservations[0])
        assertTrue(annotated.aiObservations[2].length <= 500)
        // And re-scoring the same input still equals the annotated numerics.
        val rescored = DealScoringEngine.evaluate(input)
        assertEquals(rescored.score, annotated.score, 0.0)
        assertEquals(rescored.subscores, annotated.subscores)
    }

    @Test
    fun scoringModelsAreImmutableSoAiCannotMutateAResult() {
        val immutableClasses = listOf(
            DealInput::class.java,
            ScoringWeights::class.java,
            DealScoreResult::class.java,
            SubScoreBreakdown::class.java,
            com.example.domain.scoring.ComponentBreakdown::class.java,
            com.example.domain.scoring.ScoreReason::class.java,
            com.example.domain.scoring.ReasonsSummary::class.java,
            com.example.domain.scoring.EffectiveWeights::class.java
        )
        for (cls in immutableClasses) {
            val mutableFields = cls.declaredFields.filter { !Modifier.isFinal(it.modifiers) }
            assertTrue("${cls.simpleName} must have no mutable fields (found: ${mutableFields.map { it.name }})", mutableFields.isEmpty())
        }
    }

    @Test
    fun engineApiHasNoNumericOverrideSurface() {
        // The only PUBLIC entry points are evaluate(...) and attachAiObservations(...)
        // ("$...$default" synthetics from default arguments are compiler noise, not API).
        val publicMethods = DealScoringEngine::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.name.contains("$") }
            .map { it.name }
            .toSet()
        assertEquals(setOf("evaluate", "attachAiObservations"), publicMethods)
        // attachAiObservations is the ONLY AI-facing door and it takes plain notes, not numbers.
        val attach = DealScoringEngine::class.java.declaredMethods.first { it.name == "attachAiObservations" }
        assertEquals(List::class.java, attach.parameterTypes[1])
        assertTrue(DealScoreResult::class.java.isAssignableFrom(attach.returnType))
    }

    // -----------------------------------------------------------------------
    // 10. Robustness
    // -----------------------------------------------------------------------

    @Test
    fun nonFiniteInputsAreTreatedAsMissingWithWarning() {
        val withNull = DealScoringEngine.evaluate(solidDeal().copy(capRatePct = null))
        val withNaN = DealScoringEngine.evaluate(solidDeal().copy(capRatePct = Double.NaN))

        assertEquals("NaN must behave exactly like a missing value for scoring", withNull.score, withNaN.score, 0.0)
        assertEquals(withNull.subscores, withNaN.subscores)
        assertTrue(withNaN.warnings.any { it.contains("non-finite") })

        val withInfinity = DealScoringEngine.evaluate(solidDeal().copy(dscr = Double.POSITIVE_INFINITY))
        assertTrue(withInfinity.warnings.any { it.contains("non-finite") })
        assertFalse(component(withInfinity, ScoringWeights.CASH_FLOW, "dscr").covered)
    }

    @Test
    fun extremeInputsNeverProduceOutOfRangeScores() {
        val crazy = DealInput(
            purchasePrice = 1.0,
            renovationCost = 1e12,
            monthlyCashFlow = -1e9,
            capRatePct = 5000.0,
            cashOnCashPct = -9e9,
            dscr = -3.0,
            interestRatePct = 400.0,
            estimatedMarketValue = 1e15,
            medianAreaPrice = 0.0,
            neighborhoodAppreciationPct = 900.0,
            areaDaysOnMarket = -5,
            yearBuilt = -100,
            listingDaysOnMarket = 100_000,
            cumulativePriceDropPct = 99.0
        )
        val r = DealScoringEngine.evaluate(crazy)
        assertTrue(r.score in 0.0..100.0)
        assertTrue(r.dataConfidence in 0.0..100.0)
        for (sub in r.subscores) {
            assertTrue("${sub.id} out of range: ${sub.score}", sub.score in 0.0..100.0)
        }
    }

    @Test
    fun gradesFollowScoreBands() {
        val onlyCashFlow = ScoringWeights(
            cashFlow = 1.0, equity = 0.0, market = 0.0, riskSafety = 0.0, distressOpportunity = 0.0
        )

        // A (>= 85): a maxed-out cash-flow profile scores exactly 100.
        val maxedCf = DealInput(capRatePct = 12.0, cashOnCashPct = 15.0, monthlyCashFlow = 1200.0, dscr = 2.5)
        val a = DealScoringEngine.evaluate(maxedCf, onlyCashFlow)
        assertEquals(100.0, a.score, 0.01)
        assertEquals("A", a.grade)

        // B (70..84): the solid fixture lands ~79.8.
        assertEquals("B", DealScoringEngine.evaluate(solidDeal()).grade)

        // C (55..69): capRate 5 (46.5) + CoC 8 (58) + CF $500 (78) + DSCR 1.2 (59.33)
        // weighted .3/.3/.2/.2 = 58.82.
        val midCf = DealInput(capRatePct = 5.0, cashOnCashPct = 8.0, monthlyCashFlow = 500.0, dscr = 1.2)
        val c = DealScoringEngine.evaluate(midCf, onlyCashFlow)
        assertEquals(58.82, c.score, 0.01)
        assertEquals("C", c.grade)

        // D (40..54): no data at all collapses to the neutral 50.
        assertEquals("D", DealScoringEngine.evaluate(DealInput()).grade)

        // F (< 40): the bad fixture lands ~22.
        assertEquals("F", DealScoringEngine.evaluate(badDeal()).grade)
    }

    // -----------------------------------------------------------------------
    // 11. Integration with the existing FinancialEngine (pure-Kotlin wiring)
    // -----------------------------------------------------------------------

    @Test
    fun wiresCleanlyWithFinancialEngineOutput() {
        val fin = FinancialEngine.calculate(
            FinancialInput(
                purchasePrice = 300_000.0,
                closingCosts = 9_000.0,
                renovationCost = 15_000.0,
                monthlyRent = 3_000.0,
                propertyTaxAnnual = 4_800.0,
                insuranceAnnual = 1_800.0,
                downPaymentPct = 20.0,
                interestRatePct = 6.5
            )
        )
        val deal = DealInput(
            purchasePrice = fin.input.purchasePrice,
            renovationCost = fin.input.renovationCost,
            monthlyCashFlow = fin.monthlyCashFlow,
            annualNoi = fin.noiAnnual,
            capRatePct = fin.capRate,
            cashOnCashPct = fin.cashOnCashReturn,
            dscr = fin.dscr,
            grossMonthlyRent = fin.grossRentalIncome,
            vacancyRatePct = fin.input.vacancyRatePct,
            interestRatePct = fin.input.interestRatePct
        )
        val r = DealScoringEngine.evaluate(deal)

        val cf = subscore(r, ScoringWeights.CASH_FLOW)
        assertEquals("All four CF components came from FinancialEngine", 1.0, cf.coverage, 0.001)
        assertTrue(cf.score in 0.0..100.0)
        assertTrue(r.score in 0.0..100.0)
        // FinancialEngine is internally consistent => no consistency warnings.
        assertFalse(r.warnings.any { it.contains("Inconsistent", ignoreCase = true) })
    }
}
