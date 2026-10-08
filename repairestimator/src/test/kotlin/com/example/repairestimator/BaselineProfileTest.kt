package com.example.repairestimator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineProfileTest {

    private val baseline = BaselineCostProfiles.US_RESIDENTIAL_PLACEHOLDER_2026

    @Test
    fun `the built-in baseline passes every profile rule`() {
        assertEquals(emptyList<RepairIssue>(), CostProfileValidator.validate(baseline))
    }

    @Test
    fun `the baseline is identified as an uncalibrated placeholder`() {
        assertEquals(ProfileOrigin.PLACEHOLDER_BUILT_IN, baseline.origin)
        assertEquals(BaselineCostProfiles.US_RESIDENTIAL_PLACEHOLDER_2026_ID, baseline.id)
        assertTrue(baseline.sourceNote.contains("Replace with local bids"))
    }

    @Test
    fun `every profile-priced kind has a positive unit cost with a 2026 citation`() {
        for (kind in RepairItemKind.PROFILE_PRICED) {
            val unitCost = baseline.unitCosts.getValue(kind)
            assertTrue("${kind.name} must cost more than zero", unitCost.amount > 0.0)
            assertTrue("${kind.name} needs a 2026 citation: ${unitCost.reference}", unitCost.reference.contains("2026"))
        }
    }

    @Test
    fun `the baseline prices no policy kind directly`() {
        val policyKinds = RepairItemKind.entries.filter { !it.isProfilePriced }
        policyKinds.forEach { assertTrue("${it.name} should have no unit cost", !baseline.unitCosts.containsKey(it)) }
    }

    @Test
    fun `baseline intensities start at no work and never decrease as condition worsens`() {
        val table = baseline.conditionIntensity
        assertEquals(0.0, table.excellent, 0.0)
        val ordered = listOf(table.excellent, table.good, table.fair, table.poor, table.failed)
        assertTrue(ordered.zipWithNext().all { (better, worse) -> better <= worse })
    }

    @Test
    fun `brrrr and fix-and-flip use the app's 10 percent rehab contingency`() {
        assertEquals(10.0, baseline.strategyPolicies.getValue(RepairStrategy.BRRRR).contingencyPct, 0.0)
        assertEquals(10.0, baseline.strategyPolicies.getValue(RepairStrategy.FIX_AND_FLIP).contingencyPct, 0.0)
    }

    @Test
    fun `wholesale carries more contingency than fix-and-flip because the scope is less inspected`() {
        assertTrue(
            baseline.strategyPolicies.getValue(RepairStrategy.WHOLESALE).contingencyPct >
                baseline.strategyPolicies.getValue(RepairStrategy.FIX_AND_FLIP).contingencyPct,
        )
    }

    @Test
    fun `brrrr finishes cost less than fix-and-flip finishes`() {
        assertTrue(
            baseline.strategyPolicies.getValue(RepairStrategy.BRRRR).finishMultiplier <
                baseline.strategyPolicies.getValue(RepairStrategy.FIX_AND_FLIP).finishMultiplier,
        )
    }

    @Test
    fun `the unknown-condition reserve is off by default so inspection-required work is never silently priced`() {
        assertEquals(0.0, baseline.unknownConditionReserve.pct, 0.0)
    }

    @Test
    fun `every policy rule and strategy carries a reference`() {
        assertTrue(baseline.permitPolicy.reference.isNotBlank())
        assertTrue(baseline.generalConditions.reference.isNotBlank())
        assertTrue(baseline.unknownConditionReserve.reference.isNotBlank())
        assertTrue(baseline.conditionIntensity.reference.isNotBlank())
        RepairStrategy.entries.forEach { assertTrue(baseline.strategyPolicies.getValue(it).reference.isNotBlank()) }
    }
}
