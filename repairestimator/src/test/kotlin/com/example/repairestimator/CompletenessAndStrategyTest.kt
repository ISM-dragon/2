package com.example.repairestimator

import com.example.repairestimator.Fx.issuesOf
import com.example.repairestimator.Fx.line
import com.example.repairestimator.Fx.trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletenessAndStrategyTest {

    private val allTradeCategories = listOf(
        RehabCategory.ROOF, RehabCategory.HVAC, RehabCategory.PLUMBING, RehabCategory.ELECTRICAL,
        RehabCategory.KITCHEN, RehabCategory.BATHROOMS, RehabCategory.FLOORING, RehabCategory.PAINT,
        RehabCategory.WINDOWS, RehabCategory.EXTERIOR, RehabCategory.FOUNDATION,
    )

    /** One EXCELLENT (no work) line in each trade category except the ones named, so every category is covered at zero cost. */
    private fun coverageOnly(except: Set<RehabCategory> = emptySet()): List<RepairScopeLine> {
        val representative = mapOf(
            RehabCategory.ROOF to RepairItemKind.ROOF_COVERING,
            RehabCategory.HVAC to RepairItemKind.HVAC_SYSTEM,
            RehabCategory.PLUMBING to RepairItemKind.PLUMBING_FIXTURE,
            RehabCategory.ELECTRICAL to RepairItemKind.ELECTRICAL_PANEL,
            RehabCategory.KITCHEN to RepairItemKind.KITCHEN_APPLIANCES,
            RehabCategory.BATHROOMS to RepairItemKind.BATHROOM_HALF,
            RehabCategory.FLOORING to RepairItemKind.FLOORING_CARPET,
            RehabCategory.PAINT to RepairItemKind.PAINT_INTERIOR,
            RehabCategory.WINDOWS to RepairItemKind.WINDOW_REPLACEMENT,
            RehabCategory.EXTERIOR to RepairItemKind.EXTERIOR_GUTTERS,
            RehabCategory.FOUNDATION to RepairItemKind.FOUNDATION_CRACK_REPAIR,
        )
        return allTradeCategories.filter { it !in except }.map { category ->
            trade("c-${category.name}", representative.getValue(category), ConditionLevel.EXCELLENT, quantity = null)
        }
    }

    private fun estimate(lines: List<RepairScopeLine>, strategy: RepairStrategy = RepairStrategy.BRRRR): RepairEstimate =
        Fx.estimateOf(Fx.request(*lines.toTypedArray(), strategy = strategy))

    // ---------------------------------------------------------------- completeness

    @Test
    fun `an estimate with every trade category covered and priced is complete`() {
        val estimate = estimate(coverageOnly())
        assertTrue(estimate.completeness.isComplete)
        assertEquals(emptyList<String>(), estimate.completeness.reasons)
        assertEquals(emptyList<RehabCategory>(), estimate.completeness.notCoveredCategories)
        assertEquals(0, estimate.issuesOfAny(RepairIssueCode.CATEGORY_NOT_COVERED))
    }

    @Test
    fun `an estimate with one trade priced is incomplete and lists every uncovered trade category`() {
        val estimate = estimate(listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0)))
        assertFalse(estimate.completeness.isComplete)
        assertEquals(allTradeCategories.drop(1), estimate.completeness.notCoveredCategories)
        assertEquals(10, issuesOf(estimate, RepairIssueCode.CATEGORY_NOT_COVERED).size)
        assertTrue(estimate.completeness.reasons.single().contains("trade categories not assessed"))
    }

    @Test
    fun `a single uncovered trade category uses the singular wording`() {
        val estimate = estimate(coverageOnly(except = setOf(RehabCategory.WINDOWS)))
        assertEquals(listOf(RehabCategory.WINDOWS), estimate.completeness.notCoveredCategories)
        assertTrue(estimate.completeness.reasons.single().contains("1 trade category not assessed"))
    }

    @Test
    fun `an unpriced line makes the estimate incomplete and is listed by id`() {
        val estimate = estimate(coverageOnly() + trade("hvac-unknown", RepairItemKind.HVAC_DUCTWORK, condition = null, quantity = 10.0))
        assertFalse(estimate.completeness.isComplete)
        assertEquals(listOf("hvac-unknown"), estimate.completeness.unpricedLineIds)
        assertTrue(estimate.completeness.reasons.any { it.contains("hvac-unknown") })
    }

    @Test
    fun `an excellent line counts as coverage at zero cost, not as a gap`() {
        val estimate = estimate(listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.EXCELLENT, quantity = null)))
        val roof = estimate.categories.single { it.category == RehabCategory.ROOF }
        assertEquals(CategoryCoverage.COVERED, roof.coverage)
        assertEquals(0.0, roof.pricedSubtotal, 0.0)
        assertEquals(0, roof.unpricedLineCount)
    }

    // ---------------------------------------------------------------- category summaries

    @Test
    fun `categories are reported in enum order with one entry per category`() {
        val estimate = estimate(listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0)))
        assertEquals(RehabCategory.entries, estimate.categories.map { it.category })
    }

    @Test
    fun `derived categories are marked derived and trade categories are covered or not covered`() {
        val estimate = estimate(listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0)))
        val coverage = estimate.categories.associate { it.category to it.coverage }
        assertEquals(CategoryCoverage.COVERED, coverage[RehabCategory.ROOF])
        assertEquals(CategoryCoverage.NOT_COVERED, coverage[RehabCategory.HVAC])
        listOf(RehabCategory.PERMITS, RehabCategory.LABOR, RehabCategory.CONTINGENCY, RehabCategory.UNKNOWN_INSPECTION_REQUIRED)
            .forEach { assertEquals(it.name, CategoryCoverage.DERIVED, coverage[it]) }
    }

    @Test
    fun `category subtotals and unpriced counts add up to the totals`() {
        val estimate = estimate(
            listOf(
                trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0),
                trade("roof2", RepairItemKind.ROOF_COVERING, condition = null, quantity = 1.0),
                trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.GOOD, 1.0),
            ),
        )
        val roof = estimate.categories.single { it.category == RehabCategory.ROOF }
        assertEquals(2, roof.lineCount)
        assertEquals(100.0, roof.pricedSubtotal, 0.0)
        assertEquals(1, roof.unpricedLineCount)
        val hvac = estimate.categories.single { it.category == RehabCategory.HVAC }
        assertEquals(500.0, hvac.pricedSubtotal, 0.0)
        assertEquals(estimate.totals.tradeSubtotal, estimate.categories.filter { it.category.isTrade }.sumOf { it.pricedSubtotal }, 0.0)
    }

    @Test
    fun `totals count priced and unpriced lines across the whole estimate`() {
        val estimate = estimate(
            listOf(
                trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0),
                trade("hvac", RepairItemKind.HVAC_SYSTEM, condition = null, quantity = 1.0),
            ),
        )
        assertEquals(1, estimate.totals.unpricedLineCount)
        assertEquals(estimate.lines.size - 1, estimate.totals.pricedLineCount)
    }

    // ---------------------------------------------------------------- strategies

    private val threeTradeScope = listOf(
        trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0), // not a finish item: 100
        trade("cab", RepairItemKind.KITCHEN_CABINETS, ConditionLevel.FAIR, 10.0), // finish item: 10 x 400 x 0.5 = 2000 base
        trade("bath", RepairItemKind.BATHROOM_FULL, ConditionLevel.GOOD, 1.0), // finish item: 10,000 x 0.25 = 2500 base
    )

    @Test
    fun `wholesale uses a full finish grade and a 20 percent contingency`() {
        val estimate = estimate(threeTradeScope, RepairStrategy.WHOLESALE)
        // cabinets 10 x 400 x 1.0 x 0.5 = 2000; bath 10,000 x 1.0 x 0.25 = 2500; roof 100
        assertEquals(4600.0, estimate.totals.tradeSubtotal, 0.0)
        assertEquals(230.0, estimate.totals.laborSubtotal, 0.0) // 5% general conditions
        assertEquals(20.0, estimate.totals.contingencyPct, 0.0)
        assertEquals(966.0, estimate.totals.contingencyAmount, 0.0) // 20% of 4,830
        assertEquals(50.0, estimate.totals.permitSubtotal, 0.0)
        assertEquals(5846.0, estimate.totals.totalRehab, 0.0)
    }

    @Test
    fun `brrrr applies a half finish grade and a 10 percent contingency`() {
        val estimate = estimate(threeTradeScope, RepairStrategy.BRRRR)
        // cabinets 10 x 400 x 0.5 x 0.5 = 1000; bath 10,000 x 0.5 x 0.25 = 1250; roof 100
        assertEquals(2350.0, estimate.totals.tradeSubtotal, 0.0)
        assertEquals(117.5, estimate.totals.laborSubtotal, 0.0)
        assertEquals(246.75, estimate.totals.contingencyAmount, 0.0) // 10% of 2,467.50
        assertEquals(2764.25, estimate.totals.totalRehab, 0.0)
    }

    @Test
    fun `fix-and-flip applies a full finish grade and a 15 percent contingency`() {
        val estimate = estimate(threeTradeScope, RepairStrategy.FIX_AND_FLIP)
        assertEquals(4600.0, estimate.totals.tradeSubtotal, 0.0)
        assertEquals(724.5, estimate.totals.contingencyAmount, 0.0) // 15% of 4,830
        assertEquals(5604.5, estimate.totals.totalRehab, 0.0)
    }

    @Test
    fun `the strategy is echoed on the estimate`() {
        RepairStrategy.entries.forEach { assertEquals(it, estimate(threeTradeScope, it).strategy) }
    }

    @Test
    fun `the finish grade changes finish items only, so a roof costs the same under every strategy`() {
        val roofOnly = listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0))
        val amounts = RepairStrategy.entries.map { estimate(roofOnly, it).line("roof").amount }
        assertEquals(listOf(100.0, 100.0, 100.0), amounts)
    }

    @Test
    fun `the estimate records the strategy's finish grade only when a finish item is priced`() {
        val roofOnly = estimate(listOf(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0)))
        assertTrue(roofOnly.assumptions.none { it.key.startsWith("finishMultiplier.") })
        val withFinish = estimate(threeTradeScope)
        assertTrue(withFinish.assumptions.any { it.key == "finishMultiplier.BRRRR" && it.value == "0.50" })
    }

    private fun RepairEstimate.issuesOfAny(code: RepairIssueCode): Int = issuesOf(this, code).size
}
