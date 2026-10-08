package com.example.repairestimator

import com.example.repairestimator.Fx.line
import com.example.repairestimator.Fx.trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grid checks: each invariant is asserted over a full grid of inputs rather than a handful of examples. */
class InvariantGridTest {

    private fun total(vararg lines: RepairScopeLine, strategy: RepairStrategy = RepairStrategy.BRRRR, contingency: Double? = null): Double =
        Fx.estimateOf(Fx.request(*lines, strategy = strategy, contingency = contingency)).totals.totalRehab

    @Test
    fun `the total never falls as a single item's condition worsens, across every kind, quantity and strategy`() {
        val quantities = listOf(0.5, 1.0, 4.0, 18.0, 250.0)
        for (strategy in RepairStrategy.entries) {
            for (kind in Fx.TRADE_KINDS) {
                for (quantity in quantities) {
                    val totals = Fx.KNOWN_CONDITIONS.map { total(trade("x", kind, it, quantity), strategy = strategy) }
                    assertTrue(
                        "${strategy.name} ${kind.name} q=$quantity: $totals",
                        totals.zipWithNext().all { (better, worse) -> better <= worse },
                    )
                }
            }
        }
    }

    @Test
    fun `adding any priced line never lowers the total`() {
        val base = trade("base", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 12.0)
        val baseTotal = total(base)
        for (kind in Fx.TRADE_KINDS) {
            for (condition in Fx.KNOWN_CONDITIONS) {
                val withExtra = total(base, trade("extra", kind, condition, 3.0))
                assertTrue("${kind.name} ${condition.name}", withExtra >= baseTotal)
            }
        }
    }

    @Test
    fun `adding a line that is excellent or inspection-required leaves the total unchanged`() {
        val base = trade("base", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 12.0)
        val baseTotal = total(base)
        for (kind in Fx.TRADE_KINDS) {
            assertEquals(kind.name, baseTotal, total(base, trade("ok", kind, ConditionLevel.EXCELLENT, quantity = null)), 0.0)
            assertEquals(kind.name, baseTotal, total(base, trade("unk", kind, condition = null, quantity = 5.0)), 0.0)
        }
    }

    @Test
    fun `a higher contingency percentage never lowers the contingency amount`() {
        val scope = arrayOf(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 18.0),
            trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.FAIR, 1.0),
        )
        var previous = -1.0
        for (pct in 0..100 step 5) {
            val amount = Fx.estimateOf(Fx.request(*scope, contingency = pct.toDouble())).totals.contingencyAmount
            assertTrue("pct $pct: $amount < $previous", amount >= previous)
            previous = amount
        }
    }

    @Test
    fun `a higher caller unit cost never lowers the total`() {
        var previous = -1.0
        for (cost in listOf(0.0, 50.0, 100.0, 250.0, 1_000.0, 5_000.0)) {
            val amount = total(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 10.0, override = cost))
            assertTrue("cost $cost", amount >= previous)
            previous = amount
        }
    }

    @Test
    fun `doubling every trade quantity doubles the trade subtotal within one cent per line`() {
        val kinds = Fx.TRADE_KINDS
        val single = kinds.mapIndexed { i, kind -> trade("t$i", kind, ConditionLevel.FAIR, 3.5) }
        val double = kinds.mapIndexed { i, kind -> trade("t$i", kind, ConditionLevel.FAIR, 7.0) }
        val one = Fx.estimateOf(Fx.request(*single.toTypedArray())).totals.tradeSubtotal
        val two = Fx.estimateOf(Fx.request(*double.toTypedArray())).totals.tradeSubtotal
        assertEquals(2 * one, two, kinds.size * 0.01)
    }

    @Test
    fun `an unpriced line contributes nothing to the total whatever its kind`() {
        for (kind in Fx.TRADE_KINDS) {
            val estimate = Fx.estimateOf(Fx.request(trade("x", kind, condition = null, quantity = 9.0)))
            assertEquals(kind.name, 0.0, estimate.totals.tradeSubtotal, 0.0)
            assertEquals(kind.name, null, estimate.line("x").amount)
        }
    }

    @Test
    fun `every line's amount agrees with its own calculation for many seeded scopes`() {
        val random = java.util.Random(2026L)
        val kinds = Fx.TRADE_KINDS
        repeat(200) { round ->
            val lines = (1..(3 + random.nextInt(12))).map { index ->
                val kind = kinds[random.nextInt(kinds.size)]
                val condition = Fx.KNOWN_CONDITIONS[random.nextInt(Fx.KNOWN_CONDITIONS.size)]
                trade("r$round-$index", kind, condition, 1.0 + random.nextInt(900) / 10.0)
            }
            val strategy = RepairStrategy.entries[random.nextInt(3)]
            val estimate = Fx.estimateOf(Fx.request(*lines.toTypedArray(), strategy = strategy))
            for (line in estimate.lines) {
                val amount = line.amount ?: continue
                val pricing = line.calculation.pricingQuantity ?: continue
                val effective = line.calculation.effectiveUnitCost ?: continue
                val expected = java.math.BigDecimal.valueOf(pricing)
                    .multiply(java.math.BigDecimal.valueOf(effective))
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                    .toDouble()
                assertEquals("round $round ${line.id}", expected, amount, 0.0)
            }
        }
    }
}
