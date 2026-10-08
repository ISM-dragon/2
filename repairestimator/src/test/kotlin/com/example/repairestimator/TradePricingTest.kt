package com.example.repairestimator

import com.example.repairestimator.Fx.dec
import com.example.repairestimator.Fx.expectedCents
import com.example.repairestimator.Fx.line
import com.example.repairestimator.Fx.trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TradePricingTest {

    /** Intensity values written out here, separately from the fixture, so the expectations stand on their own. */
    private fun intensityOf(condition: ConditionLevel): BigDecimal = when (condition) {
        ConditionLevel.EXCELLENT -> dec(0.0)
        ConditionLevel.GOOD -> dec(0.25)
        ConditionLevel.FAIR -> dec(0.5)
        ConditionLevel.POOR -> dec(1.0)
        ConditionLevel.FAILED -> dec(1.5)
        ConditionLevel.UNKNOWN -> throw IllegalArgumentException("no intensity for UNKNOWN")
    }

    private fun grade(kind: RepairItemKind, strategy: RepairStrategy): BigDecimal {
        if (!kind.finishItem) return BigDecimal.ONE
        return when (strategy) {
            RepairStrategy.WHOLESALE -> dec(1.0)
            RepairStrategy.BRRRR -> dec(0.5)
            RepairStrategy.FIX_AND_FLIP -> dec(1.0)
        }
    }

    private fun scoped(kind: RepairItemKind, condition: ConditionLevel, quantity: Double, strategy: RepairStrategy) =
        Fx.estimateOf(Fx.request(trade("x", kind, condition, quantity), strategy = strategy)).line("x")

    @Test
    fun `every trade kind, condition and strategy matches an independent calculation`() {
        var checked = 0
        for (strategy in RepairStrategy.entries) {
            for (kind in Fx.TRADE_KINDS) {
                for (condition in Fx.KNOWN_CONDITIONS) {
                    val quantity = 7.25
                    val line = scoped(kind, condition, quantity, strategy)
                    val expected = expectedCents(
                        dec(quantity),
                        dec(Fx.unitCostOf(kind)),
                        grade(kind, strategy),
                        intensityOf(condition),
                    )
                    val label = "${strategy.name} ${kind.name} ${condition.name}"
                    assertEquals(label, expected, line.amount!!, 0.0)
                    assertEquals(label, condition, line.condition)
                    checked++
                }
            }
        }
        assertEquals(Fx.TRADE_KINDS.size * Fx.KNOWN_CONDITIONS.size * RepairStrategy.entries.size, checked)
    }

    @Test
    fun `excellent condition is no work and costs nothing`() {
        for (kind in Fx.TRADE_KINDS) {
            val line = scoped(kind, ConditionLevel.EXCELLENT, 7.25, RepairStrategy.FIX_AND_FLIP)
            assertEquals(kind.name, LineStatus.NO_WORK_REQUIRED, line.status)
            assertEquals(kind.name, 0.0, line.amount!!, 0.0)
        }
    }

    @Test
    fun `an unknown condition is never priced for any trade kind or strategy`() {
        for (strategy in RepairStrategy.entries) {
            for (kind in Fx.TRADE_KINDS) {
                val line = scoped(kind, ConditionLevel.UNKNOWN, 7.25, strategy)
                assertEquals("${strategy.name} ${kind.name}", LineStatus.UNPRICED, line.status)
                assertEquals(null, line.amount)
                assertTrue(line.unpricedReasons.contains(UnpricedReason.CONDITION_UNKNOWN))
            }
        }
    }

    @Test
    fun `a worse condition never costs less, for every kind and strategy`() {
        val quantities = listOf(0.5, 1.0, 2.5, 10.0, 37.25, 250.0)
        for (strategy in RepairStrategy.entries) {
            for (kind in Fx.TRADE_KINDS) {
                for (quantity in quantities) {
                    val amounts = Fx.KNOWN_CONDITIONS.map { scoped(kind, it, quantity, strategy).amount!! }
                    assertTrue(
                        "${kind.name} q=$quantity ${strategy.name}: $amounts",
                        amounts.zipWithNext().all { (better, worse) -> better <= worse },
                    )
                    // Non-zero intensities in the fixture are strictly increasing, so the cost strictly increases too.
                    assertTrue(amounts.drop(1).zipWithNext().all { (a, b) -> a < b })
                }
            }
        }
    }

    @Test
    fun `amounts scale with quantity within one cent per doubling`() {
        for (kind in Fx.TRADE_KINDS) {
            for (quantity in listOf(1.0, 3.5, 12.25, 123.45)) {
                val single = scoped(kind, ConditionLevel.FAIR, quantity, RepairStrategy.FIX_AND_FLIP).amount!!
                val double = scoped(kind, ConditionLevel.FAIR, quantity * 2, RepairStrategy.FIX_AND_FLIP).amount!!
                // Each rounded amount is within half a cent of its exact value, so the doubled and rounded
                // figures can differ by at most one cent. The epsilon only absorbs binary subtraction error.
                assertEquals("${kind.name} q=$quantity", 2 * single, double, 0.01 + 1e-9)
            }
        }
    }

    @Test
    fun `finish grade scales finish items only`() {
        for (kind in Fx.TRADE_KINDS) {
            val brrrr = scoped(kind, ConditionLevel.POOR, 4.0, RepairStrategy.BRRRR).amount!!
            val flip = scoped(kind, ConditionLevel.POOR, 4.0, RepairStrategy.FIX_AND_FLIP).amount!!
            if (kind.finishItem) {
                assertEquals(kind.name, flip * 0.5, brrrr, 0.01)
            } else {
                assertEquals(kind.name, flip, brrrr, 0.0)
            }
        }
    }

    @Test
    fun `hand-checked example, roof at poor condition`() {
        // 18 squares x $100 (test unit cost) x intensity 1.00 = $1,800.00
        assertEquals(1800.0, scoped(RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 18.0, RepairStrategy.FIX_AND_FLIP).amount!!, 0.0)
    }

    @Test
    fun `hand-checked example, hvac system at good condition`() {
        // 1 system x $2,000 x intensity 0.25 = $500.00
        assertEquals(500.0, scoped(RepairItemKind.HVAC_SYSTEM, ConditionLevel.GOOD, 1.0, RepairStrategy.FIX_AND_FLIP).amount!!, 0.0)
    }

    @Test
    fun `hand-checked example, cabinets at fair condition under brrrr`() {
        // 10 lf x $400 x finish 0.50 (BRRRR) x intensity 0.50 = $1,000.00
        assertEquals(1000.0, scoped(RepairItemKind.KITCHEN_CABINETS, ConditionLevel.FAIR, 10.0, RepairStrategy.BRRRR).amount!!, 0.0)
    }

    @Test
    fun `hand-checked example, full bath at failed condition under flip`() {
        // 1 bath x $10,000 x finish 1.00 x intensity 1.50 = $15,000.00
        assertEquals(15000.0, scoped(RepairItemKind.BATHROOM_FULL, ConditionLevel.FAILED, 1.0, RepairStrategy.FIX_AND_FLIP).amount!!, 0.0)
    }

    @Test
    fun `hand-checked example, labor hours are not condition based`() {
        val estimate = Fx.estimateOf(Fx.request(trade("labor", RepairItemKind.LABOR_HOURS, condition = null, quantity = 12.0)))
        val line = estimate.line("labor")
        assertEquals(LineStatus.PRICED_PROFILE, line.status)
        assertEquals(null, line.condition)
        assertEquals(600.0, line.amount!!, 0.0) // 12 hours x $50 (test rate)
    }

    @Test
    fun `cent rounding is half-up and uses exact decimals, not binary doubles`() {
        fun carpetAt(unitCost: Double): Double {
            val profile = Fx.profile(unitCosts = Fx.defaultUnitCosts() + (RepairItemKind.FLOORING_CARPET to unitCost))
            val estimate = Fx.estimateOf(
                Fx.request(trade("c", RepairItemKind.FLOORING_CARPET, ConditionLevel.POOR, 1.0), strategy = RepairStrategy.FIX_AND_FLIP, profile = profile),
            )
            return estimate.line("c").amount!!
        }
        assertEquals(0.13, carpetAt(0.125), 0.0)
        assertEquals(0.12, carpetAt(0.124), 0.0)
        assertEquals(2.35, carpetAt(2.345), 0.0) // 2.345 is not exact in binary; the decimal form must round up
        assertEquals(0.14, carpetAt(0.135), 0.0)
    }

    @Test
    fun `permit trigger follows the kind default for trade lines and never applies to labor`() {
        // A trade line is permit-triggering by kind default; labor never is.
        val estimate = Fx.estimateOf(
            Fx.request(
                trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0),
                trade("labor", RepairItemKind.LABOR_HOURS, condition = null, quantity = 1.0),
            ),
        )
        assertTrue(estimate.line("roof").permitRequired)
        assertTrue(!estimate.line("labor").permitRequired)
    }
}
