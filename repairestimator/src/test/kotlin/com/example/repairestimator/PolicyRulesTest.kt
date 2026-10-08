package com.example.repairestimator

import com.example.repairestimator.Fx.issuesOf
import com.example.repairestimator.Fx.trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyRulesTest {

    private fun estimate(
        vararg lines: RepairScopeLine,
        strategy: RepairStrategy = RepairStrategy.BRRRR,
        profile: CostProfile = Fx.profile(),
        contingency: Double? = null,
    ): RepairEstimate = Fx.estimateOf(Fx.request(*lines, strategy = strategy, profile = profile, contingency = contingency))

    private fun RepairEstimate.policy(id: String): RepairEstimateLine =
        lines.singleOrNull { it.id == "auto:$id" } ?: throw AssertionError("no policy line auto:$id")

    private val roofPoor = trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0) // 100 of trade work

    // ---------------------------------------------------------------- general conditions

    @Test
    fun `general conditions are a percentage of priced trade work`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 18.0))
        val line = estimate.policy("general-conditions")
        assertEquals(LineStatus.POLICY_RULE, line.status)
        assertEquals(90.0, line.amount!!, 0.0) // 5% of 1,800
        assertEquals(5.0, line.calculation.rulePercent!!, 0.0)
        assertEquals(ProvenanceSource.CALLER_PROFILE, line.provenance.rule.source)
    }

    @Test
    fun `general conditions do not apply when there is no priced trade work`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.EXCELLENT, 18.0))
        val line = estimate.policy("general-conditions")
        assertEquals(LineStatus.POLICY_NOT_APPLICABLE, line.status)
        assertEquals(0.0, line.amount!!, 0.0)
        assertEquals("not applied: no priced trade work", line.formula)
    }

    @Test
    fun `general conditions at a zero profile rate are not applied and say so`() {
        val profile = Fx.profile(general = PercentRule(0.0, "zero rate"))
        val line = estimate(roofPoor, profile = profile).policy("general-conditions")
        assertEquals(LineStatus.POLICY_NOT_APPLICABLE, line.status)
        assertEquals("not applied: profile rate is 0.00%", line.formula)
    }

    // ---------------------------------------------------------------- permit fees

    @Test
    fun `permit fee is the minimum when the percentage is smaller`() {
        // 10% of a $100 permit base is $10, below the $50 minimum
        assertEquals(50.0, estimate(roofPoor).policy("permit-fees").amount!!, 0.0)
    }

    @Test
    fun `permit fee is the percentage when it is above the minimum`() {
        // 10% of $2,000 is $200
        val estimate = estimate(trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.POOR, 1.0))
        assertEquals(200.0, estimate.policy("permit-fees").amount!!, 0.0)
    }

    @Test
    fun `permit base counts only permit-triggering trade lines`() {
        val estimate = estimate(
            trade("cab", RepairItemKind.KITCHEN_CABINETS, ConditionLevel.POOR, 20.0), // permit false by default
            trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.POOR, 1.0), // 2,000, permit true
        )
        assertEquals(200.0, estimate.policy("permit-fees").amount!!, 0.0)
    }

    @Test
    fun `permit base includes caller allowances on trade lines that trigger a permit`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, condition = null, quantity = 50.0, override = 100.0))
        // allowance of 50 x 100 = 5,000 is a permit-triggering trade line: 10% = 500
        assertEquals(500.0, estimate.policy("permit-fees").amount!!, 0.0)
    }

    @Test
    fun `permit base excludes trade lines whose permit trigger is turned off`() {
        val estimate = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 50.0, permit = false),
            trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.POOR, 1.0),
        )
        assertEquals(200.0, estimate.policy("permit-fees").amount!!, 0.0)
    }

    @Test
    fun `permit fee is not applied when no permit-triggering work is priced`() {
        val estimate = estimate(trade("cab", RepairItemKind.KITCHEN_CABINETS, ConditionLevel.POOR, 20.0))
        val line = estimate.policy("permit-fees")
        assertEquals(LineStatus.POLICY_NOT_APPLICABLE, line.status)
        assertEquals(0.0, line.amount!!, 0.0)
    }

    @Test
    fun `permit fee warns when a permit-triggering line is unpriced and does not cover it`() {
        val estimate = estimate(
            roofPoor,
            trade("hvac", RepairItemKind.HVAC_SYSTEM, condition = null, quantity = 1.0),
        )
        assertEquals(50.0, estimate.policy("permit-fees").amount!!, 0.0) // only the roof counts
        assertEquals(1, issuesOf(estimate, RepairIssueCode.PERMIT_SCOPE_INCOMPLETE).size)
        assertEquals(false, estimate.completeness.isComplete)
    }

    // ---------------------------------------------------------------- unknown-condition reserve

    @Test
    fun `the reserve is off by default, so inspection-required work is never priced silently`() {
        val estimate = estimate(roofPoor, trade("hvac", RepairItemKind.HVAC_SYSTEM, condition = null, quantity = 1.0))
        val line = estimate.policy("unknown-condition-reserve")
        assertEquals(LineStatus.POLICY_NOT_APPLICABLE, line.status)
        assertEquals("not applied: profile reserve is 0.00%", line.formula)
        assertEquals(0.0, line.amount!!, 0.0)
    }

    @Test
    fun `when enabled the reserve is a percentage of priced trade work whenever a line is inspection-required`() {
        val profile = Fx.profile(reserve = PercentRule(10.0, "test reserve"))
        val estimate = estimate(roofPoor, trade("hvac", RepairItemKind.HVAC_SYSTEM, condition = null, quantity = 1.0), profile = profile)
        val line = estimate.policy("unknown-condition-reserve")
        assertEquals(LineStatus.POLICY_RULE, line.status)
        assertEquals(10.0, line.amount!!, 0.0) // 10% of the $100 priced roof
        assertEquals(10.0, estimate.totals.unknownScopeSubtotal, 0.0)
    }

    @Test
    fun `an enabled reserve is not applied when nothing is inspection-required`() {
        val profile = Fx.profile(reserve = PercentRule(10.0, "test reserve"))
        val line = estimate(roofPoor, profile = profile).policy("unknown-condition-reserve")
        assertEquals(LineStatus.POLICY_NOT_APPLICABLE, line.status)
        assertEquals("not applied: no inspection-required trade lines", line.formula)
    }

    @Test
    fun `the reserve base never includes unpriced lines`() {
        val profile = Fx.profile(reserve = PercentRule(50.0, "test reserve"))
        val line = estimate(
            roofPoor,
            trade("hvac", RepairItemKind.HVAC_SYSTEM, condition = null, quantity = 1.0),
            profile = profile,
        ).policy("unknown-condition-reserve")
        assertEquals(50.0, line.amount!!, 0.0) // 50% of 100, not of 2,100
    }

    // ---------------------------------------------------------------- contingency

    @Test
    fun `contingency base is trade work plus labor, including the general conditions amount`() {
        val estimate = estimate(
            roofPoor, // 100
            trade("labor", RepairItemKind.LABOR_HOURS, condition = null, quantity = 2.0), // 2 x 50 = 100
        )
        // general conditions: 5% of trade 100 = 5, so labor subtotal 105 and contingency base 205
        assertEquals(105.0, estimate.totals.laborSubtotal, 0.0)
        assertEquals(205.0, estimate.totals.contingencyBase, 0.0)
        assertEquals(20.5, estimate.totals.contingencyAmount, 0.0) // BRRRR 10%
    }

    @Test
    fun `contingency base excludes permit fees, the reserve and unknown-scope allowances`() {
        val estimate = estimate(
            roofPoor,
            trade("hidden", RepairItemKind.UNKNOWN_SCOPE_ALLOWANCE, condition = null, quantity = null, override = 1000.0),
        )
        assertEquals(105.0, estimate.totals.contingencyBase, 0.0)
        assertEquals(10.5, estimate.totals.contingencyAmount, 0.0)
    }

    @Test
    fun `each strategy applies its own contingency percentage to the same base`() {
        val scope = arrayOf(roofPoor)
        assertEquals(21.0, estimate(*scope, strategy = RepairStrategy.WHOLESALE).totals.contingencyAmount, 0.0) // 20% of 105
        assertEquals(10.5, estimate(*scope, strategy = RepairStrategy.BRRRR).totals.contingencyAmount, 0.0) // 10% of 105
        assertEquals(15.75, estimate(*scope, strategy = RepairStrategy.FIX_AND_FLIP).totals.contingencyAmount, 0.0) // 15% of 105
    }

    @Test
    fun `a contingency override replaces the strategy percentage`() {
        val estimate = estimate(roofPoor, strategy = RepairStrategy.WHOLESALE, contingency = 0.0)
        assertEquals(0.0, estimate.totals.contingencyPct, 0.0)
        assertEquals(0.0, estimate.totals.contingencyAmount, 0.0)
        assertEquals(LineStatus.POLICY_RULE, estimate.policy("contingency").status)
    }

    @Test
    fun `contingency is not applied when there is no priced trade or labor work`() {
        val estimate = estimate(trade("hidden", RepairItemKind.UNKNOWN_SCOPE_ALLOWANCE, condition = null, quantity = null, override = 1000.0))
        val line = estimate.policy("contingency")
        assertEquals(LineStatus.POLICY_NOT_APPLICABLE, line.status)
        assertEquals(0.0, line.amount!!, 0.0)
        assertEquals("not applied: no priced trade or labor work", line.formula)
    }

    @Test
    fun `contingency records the strategy as its source, or the caller as the source when overridden`() {
        val strategyLine = estimate(roofPoor).policy("contingency")
        assertEquals(ProvenanceSource.STRATEGY_POLICY, strategyLine.provenance.rule.source)
        val overriddenLine = estimate(roofPoor, contingency = 12.0).policy("contingency")
        assertEquals(ProvenanceSource.CALLER_OVERRIDE, overriddenLine.provenance.rule.source)
        assertEquals(12.0, overriddenLine.calculation.rulePercent!!, 0.0)
    }

    // ---------------------------------------------------------------- structure of the rule lines

    @Test
    fun `each applicable rule appears exactly once, with an auto id and its category`() {
        val estimate = estimate(roofPoor, trade("labor", RepairItemKind.LABOR_HOURS, condition = null, quantity = 1.0))
        val autos = estimate.lines.filter { it.id.startsWith("auto:") }
        // Lines come out in category order: permits, labor, contingency, then unknown / inspection-required.
        assertEquals(
            listOf("auto:permit-fees", "auto:general-conditions", "auto:contingency", "auto:unknown-condition-reserve"),
            autos.map { it.id },
        )
        assertEquals(RehabCategory.PERMITS, autos[0].category)
        assertEquals(RehabCategory.LABOR, autos[1].category)
        assertEquals(RehabCategory.CONTINGENCY, autos[2].category)
        assertEquals(RehabCategory.UNKNOWN_INSPECTION_REQUIRED, autos[3].category)
    }

    @Test
    fun `rule lines are never permit triggers and never carry a condition`() {
        val estimate = estimate(roofPoor)
        estimate.lines.filter { it.id.startsWith("auto:") }.forEach {
            assertTrue(it.id, !it.permitRequired)
            assertNull(it.condition)
            assertNull(it.calculation.baseUnitCost)
        }
    }
}
