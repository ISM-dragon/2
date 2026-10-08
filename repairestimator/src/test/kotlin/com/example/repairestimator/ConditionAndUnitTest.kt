package com.example.repairestimator

import com.example.repairestimator.Fx.issuesOf
import com.example.repairestimator.Fx.line
import com.example.repairestimator.Fx.trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionAndUnitTest {

    private fun estimate(vararg lines: RepairScopeLine, strategy: RepairStrategy = RepairStrategy.FIX_AND_FLIP, profile: CostProfile = Fx.profile()): RepairEstimate =
        Fx.estimateOf(Fx.request(*lines, strategy = strategy, profile = profile))

    // ---------------------------------------------------------------- units

    @Test
    fun `square feet are converted to roofing squares and the conversion is shown`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1800.0, MeasureUnit.SQUARE_FOOT))
        val line = estimate.line("roof")
        assertEquals(18.0, line.calculation.pricingQuantity!!, 0.0)
        assertEquals(MeasureUnit.SQUARE, line.calculation.pricingUnit)
        assertEquals(MeasureUnit.SQUARE_FOOT, line.calculation.suppliedUnit)
        assertEquals(1800.0, line.amount!!, 0.0)
        assertTrue(line.formula.contains("18 SQ (from 1800 SF)"))
        assertEquals(1, issuesOf(estimate, RepairIssueCode.UNIT_CONVERTED).size)
        assertTrue(estimate.assumptions.any { it.key == "conversion.SQUARE_FOOT.TO.SQUARE" && it.value == "0.01" })
    }

    @Test
    fun `square feet keep full precision through the conversion`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1234.5, MeasureUnit.SQUARE_FOOT))
        assertEquals(1234.5, estimate.line("roof").amount!!, 0.0) // 12.345 squares x $100
    }

    @Test
    fun `stating the pricing unit explicitly produces no conversion`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 18.0, MeasureUnit.SQUARE))
        assertEquals(0, issuesOf(estimate, RepairIssueCode.UNIT_CONVERTED).size)
        assertEquals(1800.0, estimate.line("roof").amount!!, 0.0)
    }

    @Test
    fun `a line without a unit uses the kind's pricing unit`() {
        val estimate = estimate(trade("cab", RepairItemKind.KITCHEN_CABINETS, ConditionLevel.POOR, 10.0))
        assertEquals(MeasureUnit.LINEAR_FOOT, estimate.line("cab").calculation.pricingUnit)
        assertNull(estimate.line("cab").calculation.suppliedUnit)
    }

    // ---------------------------------------------------------------- condition

    @Test
    fun `a missing condition is inspection-required and resolves to UNKNOWN`() {
        val estimate = estimate(trade("hvac", RepairItemKind.HVAC_SYSTEM, condition = null, quantity = 1.0))
        val line = estimate.line("hvac")
        assertEquals(ConditionLevel.UNKNOWN, line.condition)
        assertEquals(LineStatus.UNPRICED, line.status)
        assertNull(line.amount)
        assertEquals(listOf(UnpricedReason.CONDITION_NOT_SUPPLIED), line.unpricedReasons)
        assertEquals(ProvenanceSource.NOT_SUPPLIED, line.provenance.condition.source)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.INSPECTION_REQUIRED).size)
        assertTrue(estimate.assumptions.any { it.key == "condition.notSupplied" && it.value == "UNKNOWN" })
    }

    @Test
    fun `an explicit UNKNOWN is inspection-required and keeps the caller's basis`() {
        val estimate = estimate(
            trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.UNKNOWN, 1.0, conditionBasis = EvidenceBasis.NOT_STATED),
        )
        val line = estimate.line("hvac")
        assertEquals(listOf(UnpricedReason.CONDITION_UNKNOWN), line.unpricedReasons)
        assertEquals(ProvenanceSource.CALLER_INPUT, line.provenance.condition.source)
    }

    @Test
    fun `a condition marked as assumed raises a warning, and other bases do not`() {
        val assumed = estimate(trade("r", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0, conditionBasis = EvidenceBasis.ASSUMED))
        assertEquals(1, issuesOf(assumed, RepairIssueCode.CONDITION_ASSUMED).size)
        for (basis in listOf(EvidenceBasis.INSPECTED, EvidenceBasis.MEASURED, EvidenceBasis.ESTIMATED, EvidenceBasis.NOT_STATED)) {
            val other = estimate(trade("r", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0, conditionBasis = basis))
            assertEquals(basis.name, 0, issuesOf(other, RepairIssueCode.CONDITION_ASSUMED).size)
        }
    }

    @Test
    fun `a quantity marked as assumed raises a warning but is still priced`() {
        val estimate = estimate(trade("r", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 10.0, quantityBasis = EvidenceBasis.ASSUMED))
        assertEquals(1, issuesOf(estimate, RepairIssueCode.QUANTITY_ASSUMED).size)
        assertEquals(1000.0, estimate.line("r").amount!!, 0.0)
        assertEquals(ProvenanceSource.CALLER_INPUT, estimate.line("r").provenance.quantity.source)
        assertEquals(EvidenceBasis.ASSUMED, estimate.line("r").provenance.quantity.basis)
    }

    @Test
    fun `a condition level maps to exactly its intensity`() {
        val expected = mapOf(
            ConditionLevel.EXCELLENT to 0.0,
            ConditionLevel.GOOD to 0.25,
            ConditionLevel.FAIR to 0.5,
            ConditionLevel.POOR to 1.0,
            ConditionLevel.FAILED to 1.5,
        )
        expected.forEach { (condition, intensity) ->
            val line = estimate(trade("r", RepairItemKind.ROOF_COVERING, condition, 1.0)).line("r")
            assertEquals(condition.name, intensity, line.calculation.conditionIntensity!!, 0.0)
        }
    }

    @Test
    fun `excellent prices to zero even when no quantity is given`() {
        val line = estimate(trade("r", RepairItemKind.ROOF_COVERING, ConditionLevel.EXCELLENT, quantity = null)).line("r")
        assertEquals(LineStatus.NO_WORK_REQUIRED, line.status)
        assertEquals(0.0, line.amount!!, 0.0)
        assertEquals(emptyList<UnpricedReason>(), line.unpricedReasons)
    }

    @Test
    fun `a known condition without a quantity is unpriced and says the quantity is missing`() {
        val estimate = estimate(trade("r", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, quantity = null))
        val line = estimate.line("r")
        assertNull(line.amount)
        assertEquals(listOf(UnpricedReason.QUANTITY_NOT_SUPPLIED), line.unpricedReasons)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.QUANTITY_NOT_SUPPLIED).size)
        assertEquals(ProvenanceSource.NOT_SUPPLIED, line.provenance.quantity.source)
    }

    @Test
    fun `a kind-specific intensity table replaces the general table for that kind only`() {
        val roofTable = ConditionIntensity(0.0, 0.1, 0.9, 1.0, 1.5, "roof-specific table")
        val profile = Fx.profile(kindOverrides = mapOf(RepairItemKind.ROOF_COVERING to roofTable))
        val estimate = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.FAIR, 1.0),
            trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.FAIR, 1.0),
            profile = profile,
        )
        assertEquals(90.0, estimate.line("roof").amount!!, 0.0) // 100 x 0.90
        assertEquals(1000.0, estimate.line("hvac").amount!!, 0.0) // 2000 x 0.50 from the general table
        assertEquals("roof-specific table", estimate.line("roof").provenance.conditionIntensity.reference)
    }

    // ---------------------------------------------------------------- permit trigger

    @Test
    fun `permitRequired false removes a trade line from the permit base, with an information note`() {
        val estimate = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0, permit = false),
            trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.POOR, 1.0),
        )
        assertFalse(estimate.line("roof").permitRequired)
        assertTrue(estimate.line("hvac").permitRequired)
        // permit base is the HVAC line only: 2000 x 10% = 200, above the 50 minimum
        assertEquals(200.0, estimate.line("auto:permit-fees").amount!!, 0.0)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.PERMIT_REQUIRED_OVERRIDDEN).size)
    }

    @Test
    fun `permitRequired matching the kind default produces no note`() {
        val estimate = estimate(trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.POOR, 1.0, permit = true))
        assertEquals(0, issuesOf(estimate, RepairIssueCode.PERMIT_REQUIRED_OVERRIDDEN).size)
    }

    @Test
    fun `an unused field on a non-trade line is ignored and reported`() {
        val estimate = estimate(
            trade("labor", RepairItemKind.LABOR_HOURS, ConditionLevel.GOOD, 2.0, permit = true),
        )
        assertEquals(1, issuesOf(estimate, RepairIssueCode.FIELD_NOT_APPLICABLE).size)
        assertEquals(100.0, estimate.line("labor").amount!!, 0.0) // 2 hours x $50, condition ignored
        assertFalse(estimate.line("labor").permitRequired)
    }
}
