package com.example.repairestimator

import com.example.repairestimator.Fx.line
import com.example.repairestimator.Fx.trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ReconciliationAndDeterminismTest {

    private fun estimate(lines: List<RepairScopeLine>, strategy: RepairStrategy = RepairStrategy.BRRRR, profile: CostProfile = Fx.profile()): RepairEstimate =
        Fx.estimateOf(Fx.request(*lines.toTypedArray(), strategy = strategy, profile = profile))

    /** A deterministic, varied scope: every trade kind, every condition, mixed units, overrides and gaps. */
    private fun richScope(seed: Long, count: Int): List<RepairScopeLine> {
        val random = Random(seed)
        val kinds = RepairItemKind.entries.filter { it.role == KindRole.TRADE || it == RepairItemKind.LABOR_HOURS }
        val conditions = Fx.KNOWN_CONDITIONS + listOf(null, ConditionLevel.UNKNOWN)
        return (1..count).map { index ->
            val kind = kinds[random.nextInt(kinds.size)]
            val condition = conditions[random.nextInt(conditions.size)]
            val quantity = if (random.nextInt(7) == 0) null else 1.0 + random.nextInt(4000) / 10.0
            val override = if (random.nextInt(5) == 0) random.nextInt(900) * 3.17 else null
            RepairScopeLine(
                id = "line-$index",
                kind = kind,
                condition = if (kind == RepairItemKind.LABOR_HOURS) null else condition,
                quantity = quantity,
                unitCostOverride = override,
            )
        }
    }

    // ---------------------------------------------------------------- reconciliation

    @Test
    fun `the total equals the sum of its parts to the cent`() {
        for (seed in 1L..40L) {
            val estimate = estimate(richScope(seed, 60))
            val t = estimate.totals
            val parts = BigDecimal.valueOf(t.tradeSubtotal)
                .add(BigDecimal.valueOf(t.laborSubtotal))
                .add(BigDecimal.valueOf(t.permitSubtotal))
                .add(BigDecimal.valueOf(t.unknownScopeSubtotal))
                .add(BigDecimal.valueOf(t.contingencyAmount))
            assertEquals("seed $seed", 0, parts.compareTo(BigDecimal.valueOf(t.totalRehab)))
        }
    }

    @Test
    fun `the total equals the sum of every line amount to the cent`() {
        for (seed in 41L..60L) {
            val estimate = estimate(richScope(seed, 80))
            val sum = estimate.lines.fold(BigDecimal.ZERO) { acc, line -> acc.add(BigDecimal.valueOf(line.amount ?: 0.0)) }
            assertEquals("seed $seed", 0, sum.setScale(2, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(estimate.totals.totalRehab)))
        }
    }

    @Test
    fun `category subtotals reconcile to the total`() {
        val estimate = estimate(richScope(7L, 120))
        val sum = estimate.categories.fold(BigDecimal.ZERO) { acc, c -> acc.add(BigDecimal.valueOf(c.pricedSubtotal)) }
        assertEquals(0, sum.compareTo(BigDecimal.valueOf(estimate.totals.totalRehab)))
    }

    @Test
    fun `the line count and the unpriced count agree with the lines`() {
        val estimate = estimate(richScope(11L, 90))
        assertEquals(estimate.lines.size, estimate.totals.pricedLineCount + estimate.totals.unpricedLineCount)
        assertEquals(estimate.lines.count { it.amount == null }, estimate.totals.unpricedLineCount)
    }

    @Test
    fun `a permit fee rounds half-up from an exact decimal base`() {
        // Roof scope of 10.0005 squares x $100 = $1,000.05 permit base; 10% = $100.005, which rounds to $100.01.
        val profile = Fx.profile(permit = PermitPolicy(10.0, 0.0, "test"))
        val estimate = estimate(listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 10.0005)), profile = profile)
        assertEquals(1000.05, estimate.line("roof").amount!!, 0.0)
        assertEquals(100.01, estimate.line("auto:permit-fees").amount!!, 0.0)
    }

    @Test
    fun `very large amounts keep every cent`() {
        val estimate = estimate(listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 999_999.0, MeasureUnit.SQUARE, override = 1_000.0)))
        assertEquals(999_999_000.0, estimate.line("roof").amount!!, 0.0)
    }

    @Test
    fun `tiny amounts round to cents at the half-cent boundary`() {
        // FIX_AND_FLIP keeps the finish grade at 1.00, so the amount is exactly the unit cost at POOR (intensity 1.00).
        fun carpetAt(unitCost: Double): Double {
            val profile = Fx.profile(unitCosts = Fx.defaultUnitCosts() + (RepairItemKind.FLOORING_CARPET to unitCost))
            return estimate(
                listOf(trade("c", RepairItemKind.FLOORING_CARPET, ConditionLevel.POOR, 1.0)),
                strategy = RepairStrategy.FIX_AND_FLIP,
                profile = profile,
            ).line("c").amount!!
        }
        assertEquals(0.0, carpetAt(0.004), 0.0)
        assertEquals(0.01, carpetAt(0.005), 0.0)
        assertEquals(0.01, carpetAt(0.014), 0.0)
        assertEquals(0.02, carpetAt(0.015), 0.0)
    }

    // ---------------------------------------------------------------- determinism

    @Test
    fun `the same request always produces an equal estimate`() {
        val lines = richScope(3L, 70)
        assertEquals(estimate(lines), estimate(lines))
    }

    @Test
    fun `the order of input lines does not change the estimate`() {
        val lines = richScope(5L, 70)
        val reference = estimate(lines)
        for (shuffleSeed in 1L..10L) {
            val shuffled = lines.shuffled(Random(shuffleSeed))
            assertEquals("shuffle $shuffleSeed", reference, estimate(shuffled))
        }
    }

    @Test
    fun `the issues come out in the same order however the lines were supplied`() {
        val lines = richScope(9L, 50)
        val reference = estimate(lines).issues
        assertEquals(reference, estimate(lines.reversed()).issues)
    }

    @Test
    fun `lines are returned in category, kind, then id order`() {
        val estimate = estimate(richScope(13L, 80))
        val keys = estimate.lines.map { Triple(it.category.ordinal, it.kind.ordinal, it.id) }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third })), keys)
    }

    @Test
    fun `the input list is not modified`() {
        val lines = richScope(15L, 40)
        val before = lines.toList()
        estimate(lines)
        assertEquals(before, lines)
    }

    @Test
    fun `concurrent evaluations of the same request agree`() {
        val lines = richScope(17L, 60)
        val request = Fx.request(*lines.toTypedArray())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..24).map { pool.submit(Callable { RepairEstimator.estimate(request) }) }.map { it.get() }
            val reference = results.first()
            results.forEach { assertEquals(reference, it) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `changing one input changes the estimate, so equality is meaningful`() {
        val base = listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 10.0))
        val changed = listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.FAIR, 10.0))
        assertNotEquals(estimate(base), estimate(changed))
    }

    @Test
    fun `the estimate contains no timestamps or random identifiers`() {
        // Two independent runs must agree on every field, which rules out clocks and random ids.
        val lines = richScope(19L, 30)
        val a = estimate(lines)
        val b = estimate(lines)
        assertEquals(a.assumptions, b.assumptions)
        assertEquals(a.completeness, b.completeness)
        assertTrue(a.lines.all { it.formula.isNotBlank() })
    }
}
