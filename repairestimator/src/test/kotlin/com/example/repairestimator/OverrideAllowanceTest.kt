package com.example.repairestimator

import com.example.repairestimator.Fx.issuesOf
import com.example.repairestimator.Fx.line
import com.example.repairestimator.Fx.trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverrideAllowanceTest {

    private fun estimate(vararg lines: RepairScopeLine, strategy: RepairStrategy = RepairStrategy.FIX_AND_FLIP, contingency: Double? = null): RepairEstimate =
        Fx.estimateOf(Fx.request(*lines, strategy = strategy, contingency = contingency))

    /** One line for every trade category: uninspected categories are marked EXCELLENT so they are covered at zero cost. */
    private fun coveredScope(vararg extra: RepairScopeLine): List<RepairScopeLine> {
        val excellent = listOf(
            RepairItemKind.HVAC_SYSTEM, RepairItemKind.PLUMBING_FIXTURE, RepairItemKind.ELECTRICAL_PANEL,
            RepairItemKind.KITCHEN_APPLIANCES, RepairItemKind.BATHROOM_HALF, RepairItemKind.FLOORING_CARPET,
            RepairItemKind.PAINT_INTERIOR, RepairItemKind.WINDOW_REPLACEMENT, RepairItemKind.EXTERIOR_GUTTERS,
            RepairItemKind.FOUNDATION_CRACK_REPAIR,
        ).map { trade("ok-${it.name}", it, ConditionLevel.EXCELLENT, quantity = null) }
        return excellent + extra.toList()
    }

    // ---------------------------------------------------------------- unit cost overrides

    @Test
    fun `a caller unit cost replaces the profile cost on a known condition`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 10.0, override = 300.0))
        val line = estimate.line("roof")
        assertEquals(LineStatus.PRICED_CALLER_OVERRIDE, line.status)
        assertEquals(3000.0, line.amount!!, 0.0)
        assertEquals(300.0, line.calculation.baseUnitCost!!, 0.0)
        assertNull(line.calculation.conditionIntensity)
        assertEquals(ProvenanceSource.CALLER_OVERRIDE, line.provenance.unitCost.source)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.OVERRIDE_APPLIED).size)
        assertTrue(line.formula.contains("caller unit cost"))
    }

    @Test
    fun `a caller unit cost on an excellent condition is applied and flagged`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.EXCELLENT, 10.0, override = 300.0))
        assertEquals(3000.0, estimate.line("roof").amount!!, 0.0)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.OVERRIDE_ON_EXCELLENT).size)
    }

    @Test
    fun `a zero caller unit cost prices the line at zero without an error`() {
        val line = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 10.0, override = 0.0)).line("roof")
        assertEquals(LineStatus.PRICED_CALLER_OVERRIDE, line.status)
        assertEquals(0.0, line.amount!!, 0.0)
    }

    @Test
    fun `a caller unit cost without a quantity is unpriced`() {
        val line = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, quantity = null, override = 300.0)).line("roof")
        assertNull(line.amount)
        assertEquals(listOf(UnpricedReason.QUANTITY_NOT_SUPPLIED), line.unpricedReasons)
    }

    // ---------------------------------------------------------------- allowances on uninspected trade work

    @Test
    fun `a caller amount on an uninspected trade line is an unverified allowance that counts in the trade subtotal`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, condition = null, quantity = 18.0, override = 500.0))
        val line = estimate.line("roof")
        assertEquals(LineStatus.ALLOWANCE_UNVERIFIED, line.status)
        assertEquals(9000.0, line.amount!!, 0.0)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.ALLOWANCE_UNVERIFIED).size)
        assertEquals(1, estimate.totals.allowanceLineCount)
        assertEquals(listOf("roof"), estimate.completeness.unverifiedAllowanceLineIds)
        assertEquals(9000.0, estimate.totals.tradeSubtotal, 0.0)
        assertTrue(line.formula.contains("condition not inspected"))
    }

    @Test
    fun `an unverified allowance does not make an otherwise complete estimate incomplete`() {
        val estimate = estimate(*coveredScope(
            trade("roof", RepairItemKind.ROOF_COVERING, condition = null, quantity = 18.0, override = 500.0),
        ).toTypedArray())
        assertTrue(estimate.completeness.isComplete)
        assertEquals(listOf("roof"), estimate.completeness.unverifiedAllowanceLineIds)
    }

    @Test
    fun `an uninspected trade line with a caller amount but no quantity stays unpriced`() {
        val line = estimate(trade("roof", RepairItemKind.ROOF_COVERING, condition = null, quantity = null, override = 500.0)).line("roof")
        assertNull(line.amount)
        assertEquals(
            listOf(UnpricedReason.CONDITION_NOT_SUPPLIED, UnpricedReason.QUANTITY_NOT_SUPPLIED),
            line.unpricedReasons,
        )
    }

    // ---------------------------------------------------------------- unknown-scope allowances

    @Test
    fun `an unknown-scope allowance is priced but sits outside the trade base and the contingency base`() {
        val estimate = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0),
            trade("hidden", RepairItemKind.UNKNOWN_SCOPE_ALLOWANCE, condition = null, quantity = null, override = 2500.0),
        )
        assertEquals(2500.0, estimate.line("hidden").amount!!, 0.0)
        assertEquals(LineStatus.ALLOWANCE_UNVERIFIED, estimate.line("hidden").status)
        assertEquals(100.0, estimate.totals.tradeSubtotal, 0.0)
        assertEquals(2500.0, estimate.totals.unknownScopeSubtotal, 0.0)
        // trade 100 + general conditions 5 = contingency base 105; FIX_AND_FLIP contingency is 15%
        assertEquals(105.0, estimate.totals.contingencyBase, 0.0)
        assertEquals(15.75, estimate.totals.contingencyAmount, 0.0)
        // roof permit 50 minimum applies: 10% of 100 is below the minimum
        assertEquals(50.0, estimate.totals.permitSubtotal, 0.0)
        assertEquals(100.0 + 5.0 + 50.0 + 2500.0 + 15.75, estimate.totals.totalRehab, 0.0)
    }

    @Test
    fun `an unknown-scope allowance without an amount is unpriced and makes the estimate incomplete`() {
        val estimate = estimate(
            *coveredScope(trade("hidden", RepairItemKind.UNKNOWN_SCOPE_ALLOWANCE, condition = null, quantity = null)).toTypedArray(),
        )
        val line = estimate.line("hidden")
        assertNull(line.amount)
        assertEquals(listOf(UnpricedReason.ALLOWANCE_NOT_SUPPLIED), line.unpricedReasons)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.ALLOWANCE_NOT_SUPPLIED).size)
        assertEquals(false, estimate.completeness.isComplete)
        assertEquals(listOf("hidden"), estimate.completeness.unpricedLineIds)
    }

    @Test
    fun `an unknown-scope allowance is flagged as not inspected`() {
        val estimate = estimate(trade("hidden", RepairItemKind.UNKNOWN_SCOPE_ALLOWANCE, condition = null, quantity = null, override = 800.0))
        assertEquals(1, issuesOf(estimate, RepairIssueCode.ALLOWANCE_UNVERIFIED).size)
        assertEquals(listOf("hidden"), estimate.completeness.unverifiedAllowanceLineIds)
    }

    // ---------------------------------------------------------------- labor

    @Test
    fun `a caller hourly rate replaces the profile rate for labor hours`() {
        val estimate = estimate(trade("labor", RepairItemKind.LABOR_HOURS, condition = null, quantity = 4.0, override = 75.0))
        val line = estimate.line("labor")
        assertEquals(LineStatus.PRICED_CALLER_OVERRIDE, line.status)
        assertEquals(300.0, line.amount!!, 0.0)
        assertEquals(ProvenanceSource.CALLER_OVERRIDE, line.provenance.unitCost.source)
        assertEquals(300.0, estimate.totals.laborSubtotal, 0.0)
    }

    @Test
    fun `labor hours without a quantity are unpriced`() {
        val line = estimate(trade("labor", RepairItemKind.LABOR_HOURS, condition = null, quantity = null)).line("labor")
        assertNull(line.amount)
        assertEquals(listOf(UnpricedReason.QUANTITY_NOT_SUPPLIED), line.unpricedReasons)
    }

    // ---------------------------------------------------------------- explicit replacements of rule lines

    @Test
    fun `an explicit general conditions amount replaces the profile rule`() {
        val estimate = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 18.0),
            trade("gc", RepairItemKind.GENERAL_CONDITIONS, condition = null, quantity = null, override = 750.0),
        )
        assertNull(estimate.lines.find { it.id == "auto:general-conditions" })
        assertEquals(750.0, estimate.line("gc").amount!!, 0.0)
        assertEquals(750.0, estimate.totals.laborSubtotal, 0.0)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.POLICY_SUPPRESSED).size)
    }

    @Test
    fun `an explicit permit fee amount replaces the permit rule even when the rule would give a different figure`() {
        val estimate = estimate(
            trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.POOR, 1.0), // rule would give 200
            trade("pf", RepairItemKind.PERMIT_FEES, condition = null, quantity = null, override = 400.0),
        )
        assertNull(estimate.lines.find { it.id == "auto:permit-fees" })
        assertEquals(400.0, estimate.totals.permitSubtotal, 0.0)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.POLICY_SUPPRESSED).size)
    }

    @Test
    fun `several explicit permit fee lines add up`() {
        val estimate = estimate(
            trade("pf1", RepairItemKind.PERMIT_FEES, condition = null, quantity = null, override = 300.0),
            trade("pf2", RepairItemKind.PERMIT_FEES, condition = null, quantity = null, override = 125.0),
        )
        assertEquals(425.0, estimate.totals.permitSubtotal, 0.0)
    }

    @Test
    fun `an explicit lump sum with a quantity multiplies the amount`() {
        val line = estimate(
            trade("pf", RepairItemKind.PERMIT_FEES, condition = null, quantity = 2.0, unit = MeasureUnit.LUMP_SUM, override = 400.0),
        ).line("pf")
        assertEquals(800.0, line.amount!!, 0.0)
    }

    @Test
    fun `an explicit lump sum without a quantity defaults to one lump and says so`() {
        val estimate = estimate(trade("pf", RepairItemKind.PERMIT_FEES, condition = null, quantity = null, override = 400.0))
        assertEquals(400.0, estimate.line("pf").amount!!, 0.0)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.LUMP_SUM_QUANTITY_DEFAULTED).size)
        assertEquals(ProvenanceSource.ENGINE_RULE, estimate.line("pf").provenance.quantity.source)
    }

    @Test
    fun `an explicit general conditions line has no condition and never a permit trigger`() {
        val line = estimate(trade("gc", RepairItemKind.GENERAL_CONDITIONS, condition = null, quantity = null, override = 300.0)).line("gc")
        assertFalse(line.permitRequired)
        assertNull(line.condition)
    }

    // ---------------------------------------------------------------- contingency override

    @Test
    fun `a request-level contingency override replaces the strategy percentage for that estimate only`() {
        val withOverride = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0), contingency = 25.0)
        assertEquals(25.0, withOverride.totals.contingencyPct, 0.0)
        assertEquals(105.0 * 0.25, withOverride.totals.contingencyAmount, 0.0)
        assertEquals(1, issuesOf(withOverride, RepairIssueCode.CONTINGENCY_OVERRIDDEN).size)

        val withoutOverride = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0))
        assertEquals(15.0, withoutOverride.totals.contingencyPct, 0.0)
        assertEquals(0, issuesOf(withoutOverride, RepairIssueCode.CONTINGENCY_OVERRIDDEN).size)
    }
}
