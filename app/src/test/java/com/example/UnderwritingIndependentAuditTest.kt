package com.example

import com.example.domain.finance.underwriting.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Hand-derived synthetic expectations, NOT generated from the underwriting oracle.
 * Equations and source classification are embedded in independent_audit.json.
 */
class UnderwritingIndependentAuditTest {
    @Test
    fun independentScenariosReplayThroughShippedEngine() {
        val stream = javaClass.classLoader!!.getResourceAsStream("underwriting/independent_audit.json")!!
        val scenarios = JSONObject(stream.bufferedReader().use { it.readText() }).getJSONArray("scenarios")
        assertEquals(13, scenarios.length())
        for (index in 0 until scenarios.length()) {
            val scenario = scenarios.getJSONObject(index)
            val json = scenario.getJSONObject("input")
            val input = json.keys().asSequence().associateWith { json.get(it) }
            val decoded = UnderwritingInputCodec.decode(input)
            val result = UnderwritingFlatten.flatten(UnderwritingEngine.analyze(decoded.input, decoded.issues))
            val expected = scenario.getJSONObject("expected")
            for (key in expected.keys()) {
                val label = "${scenario.getString("id")}: $key"
                assertTrue("$label missing", result.containsKey(key))
                if (expected.isNull(key)) assertNull(label, result[key])
                else {
                    val target = expected.getDouble(key)
                    assertNotNull(label, result[key])
                    assertEquals(label, target, (result[key] as Number).toDouble(),
                        maxOf(1e-6, kotlin.math.abs(target) * 1e-9))
                }
            }
        }
    }

    @Test
    fun maoDefaultHoldingUsesCandidatePriceAndExplicitZeroStaysZero() {
        for (holding in listOf(null, 0.0, 300.0)) {
            val input = UnderwritingInput(
                strategy = InvestmentStrategy.FIX_AND_FLIP,
                financingModel = FinancingModel.CONVENTIONAL,
                purchasePrice = 150000.0, rehabCost = 20000.0, monthlyRent = 0.0,
                arv = 250000.0, closingCosts = 3000.0, downPaymentPct = 100.0,
                interestRatePct = 0.0, lenderFees = 0.0, originationPointsPct = 0.0,
                rehabContingencyPct = 10.0, flipHoldMonths = 6,
                sellClosingCostPct = 8.0, flipTargetProfitPctOfArv = 10.0,
                holdingCostsMonthly = holding
            )
            val offer = UnderwritingEngine.analyze(input).flip!!.maxOfferForTargetProfit!!
            val expected = if (holding == null) 180000.0 / 1.009 else 180000.0 - holding * 6
            assertEquals(expected, offer, 1e-6)
            assertEquals(25000.0, UnderwritingEngine.analyze(input.copy(purchasePrice = offer)).flip!!.profit, 1e-6)
            assertTrue(UnderwritingEngine.analyze(input.copy(purchasePrice = offer + 1)).flip!!.profit < 25000.0)
        }
    }

    @Test
    fun independentZeroRateAndBalloonLedger() {
        assertEquals(1000.0, Amortization.amortizingPayment(12000.0, 0.0, 12), 1e-9)
        val rows = Amortization.schedule(12000.0, 0.0, 6, 12, 0)
        assertEquals(6, rows.size)
        assertEquals(7000.0, rows.last().payment, 1e-9)
        assertEquals(0.0, rows.last().closingBalance, 1e-9)
        assertTrue(rows.last().isBalloonMonth)
        assertEquals(12000.0, rows.sumOf { it.principal }, 1e-9)
        assertNull(ReturnMetricsCalculator.annualizeFromRoi(-1.0, 6.0))
        assertEquals(0.21, ReturnMetricsCalculator.annualizeFromRoi(0.10, 6.0)!!, 1e-9)
    }
}
