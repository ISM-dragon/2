package com.example.repairestimator

import com.example.repairestimator.Fx.issuesOf
import com.example.repairestimator.Fx.line
import com.example.repairestimator.Fx.trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceAndFormulaTest {

    private fun estimate(vararg lines: RepairScopeLine, profile: CostProfile = Fx.profile(), strategy: RepairStrategy = RepairStrategy.BRRRR): RepairEstimate =
        Fx.estimateOf(Fx.request(*lines, strategy = strategy, profile = profile))

    // ---------------------------------------------------------------- profile identity

    @Test
    fun `the built-in placeholder profile is flagged in the estimate`() {
        val estimate = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0),
            profile = BaselineCostProfiles.US_RESIDENTIAL_PLACEHOLDER_2026,
        )
        assertEquals(ProfileOrigin.PLACEHOLDER_BUILT_IN, estimate.profile.origin)
        assertEquals(1, issuesOf(estimate, RepairIssueCode.PLACEHOLDER_PROFILE).size)
        assertEquals(
            ProvenanceSource.PLACEHOLDER_PROFILE,
            estimate.assumptions.single { it.key == "profile.id" }.source,
        )
        assertTrue(estimate.assumptions.any { it.key == "unitCost.ROOF_COVERING" && it.source == ProvenanceSource.PLACEHOLDER_PROFILE })
    }

    @Test
    fun `a caller-defined profile is not flagged as a placeholder`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0))
        assertEquals(0, issuesOf(estimate, RepairIssueCode.PLACEHOLDER_PROFILE).size)
        assertEquals(ProfileOrigin.CALLER_DEFINED, estimate.profile.origin)
        assertEquals(ProvenanceSource.CALLER_PROFILE, estimate.assumptions.single { it.key == "profile.id" }.source)
    }

    @Test
    fun `the estimate echoes the profile identity and the spec version`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0))
        assertEquals("TEST_PROFILE", estimate.profile.id)
        assertEquals("test-1", estimate.profile.version)
        assertEquals("test fixture", estimate.profile.sourceNote)
        assertEquals(RepairEstimator.SPEC_VERSION, estimate.specVersion)
    }

    // ---------------------------------------------------------------- line provenance

    @Test
    fun `a profile-priced line records the source of its unit cost, intensity and finish grade`() {
        val line = estimate(trade("cab", RepairItemKind.KITCHEN_CABINETS, ConditionLevel.FAIR, 10.0)).line("cab")
        assertEquals(ProvenanceSource.CALLER_PROFILE, line.provenance.unitCost.source)
        assertEquals("test reference for KITCHEN_CABINETS", line.provenance.unitCost.reference)
        assertEquals(ProvenanceSource.CALLER_PROFILE, line.provenance.conditionIntensity.source)
        assertEquals("test intensity table", line.provenance.conditionIntensity.reference)
        assertEquals(ProvenanceSource.STRATEGY_POLICY, line.provenance.finishMultiplier.source)
        assertEquals("test BRRRR policy", line.provenance.finishMultiplier.reference)
    }

    @Test
    fun `a non-finish line records no finish grade`() {
        val line = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0)).line("roof")
        assertEquals(ProvenanceSource.NOT_APPLICABLE, line.provenance.finishMultiplier.source)
        assertNull(line.calculation.finishMultiplier)
    }

    @Test
    fun `a caller-stated condition keeps its evidence basis`() {
        val line = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0, conditionBasis = EvidenceBasis.INSPECTED),
        ).line("roof")
        assertEquals(ProvenanceSource.CALLER_INPUT, line.provenance.condition.source)
        assertEquals(EvidenceBasis.INSPECTED, line.provenance.condition.basis)
    }

    @Test
    fun `a measured quantity keeps its evidence basis and a converted quantity records the conversion`() {
        val line = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1800.0, MeasureUnit.SQUARE_FOOT, quantityBasis = EvidenceBasis.MEASURED),
        ).line("roof")
        assertEquals(EvidenceBasis.MEASURED, line.provenance.quantity.basis)
        assertEquals(1800.0, line.calculation.suppliedQuantity!!, 0.0)
        assertEquals(18.0, line.calculation.pricingQuantity!!, 0.0)
    }

    @Test
    fun `a caller override records the caller as the unit cost source`() {
        val line = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0, override = 300.0)).line("roof")
        assertEquals(ProvenanceSource.CALLER_OVERRIDE, line.provenance.unitCost.source)
    }

    @Test
    fun `the caller's evidence note is echoed unchanged on the line`() {
        val line = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0, evidence = "inspection report p.4"),
        ).line("roof")
        assertEquals("inspection report p.4", line.evidence)
    }

    @Test
    fun `every policy line records the rule it applied`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0))
        val gc = estimate.line("auto:general-conditions")
        assertNotNull(gc.provenance.rule.reference)
        assertEquals(ProvenanceSource.CALLER_PROFILE, gc.provenance.rule.source)
        assertEquals(ProvenanceSource.STRATEGY_POLICY, estimate.line("auto:contingency").provenance.rule.source)
    }

    // ---------------------------------------------------------------- formula text

    @Test
    fun `formula for a converted profile-priced line shows every factor`() {
        val line = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1800.0, MeasureUnit.SQUARE_FOOT)).line("roof")
        assertEquals(
            "18 SQ (from 1800 SF) x \$100.00/SQ (\$100.00 base x 1.00 POOR intensity) = \$1,800.00",
            line.formula,
        )
    }

    @Test
    fun `formula for a finish item shows the finish grade`() {
        val line = estimate(trade("cab", RepairItemKind.KITCHEN_CABINETS, ConditionLevel.FAIR, 10.0)).line("cab")
        assertEquals(
            "10 LF x \$100.00/LF (\$400.00 base x 0.50 finish grade for BRRRR x 0.50 FAIR intensity) = \$1,000.00",
            line.formula,
        )
    }

    @Test
    fun `formula for a caller override`() {
        val line = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 10.0, override = 300.0)).line("roof")
        assertEquals("10 SQ x \$300.00/SQ caller unit cost = \$3,000.00", line.formula)
    }

    @Test
    fun `formula for an unverified allowance`() {
        val line = estimate(trade("roof", RepairItemKind.ROOF_COVERING, condition = null, quantity = 18.0, override = 500.0)).line("roof")
        assertEquals("18 SQ x \$500.00/SQ caller allowance, condition not inspected = \$9,000.00", line.formula)
    }

    @Test
    fun `formula for an unpriced line says why`() {
        val line = estimate(trade("hvac", RepairItemKind.HVAC_SYSTEM, condition = null, quantity = null)).line("hvac")
        assertEquals("not priced: condition not supplied, inspection required; quantity not supplied", line.formula)
    }

    @Test
    fun `formula for a no-work line states the zero intensity`() {
        val line = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.EXCELLENT, quantity = null)).line("roof")
        assertEquals("no work required: condition EXCELLENT has intensity 0.00", line.formula)
    }

    @Test
    fun `formula for labor hours`() {
        val line = estimate(trade("labor", RepairItemKind.LABOR_HOURS, condition = null, quantity = 12.0)).line("labor")
        assertEquals("12 HR x \$50.00/HR = \$600.00", line.formula)
    }

    @Test
    fun `formulas for the rule lines`() {
        val estimate = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0))
        assertEquals("5.00% x priced trade work \$100.00 = \$5.00", estimate.line("auto:general-conditions").formula)
        assertEquals(
            "max(\$50.00 minimum, 10.00% x permit-triggering scope \$100.00) = \$50.00",
            estimate.line("auto:permit-fees").formula,
        )
        assertEquals(
            "10.00% x contingency base \$105.00 (priced trade work plus labor) = \$10.50",
            estimate.line("auto:contingency").formula,
        )
    }

    // ---------------------------------------------------------------- derivation

    @Test
    fun `the calculation fields reproduce every priced amount`() {
        val estimate = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1800.0, MeasureUnit.SQUARE_FOOT),
            trade("cab", RepairItemKind.KITCHEN_CABINETS, ConditionLevel.FAIR, 10.0),
            trade("hvac", RepairItemKind.HVAC_SYSTEM, ConditionLevel.GOOD, 1.0, override = 1234.56),
            trade("labor", RepairItemKind.LABOR_HOURS, condition = null, quantity = 3.5),
        )
        for (line in estimate.lines.filter { it.amount != null && it.calculation.pricingQuantity != null && it.calculation.effectiveUnitCost != null }) {
            val recomputed = java.math.BigDecimal.valueOf(line.calculation.pricingQuantity!!)
                .multiply(java.math.BigDecimal.valueOf(line.calculation.effectiveUnitCost!!))
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .toDouble()
            assertEquals(line.id, recomputed, line.amount!!, 0.0)
        }
    }

    @Test
    fun `assumptions are sorted by key and every key appears once`() {
        val estimate = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1800.0, MeasureUnit.SQUARE_FOOT),
            trade("cab", RepairItemKind.KITCHEN_CABINETS, ConditionLevel.FAIR, 10.0),
            trade("hvac", RepairItemKind.HVAC_SYSTEM, condition = null, quantity = 1.0),
        )
        val keys = estimate.assumptions.map { it.key }
        assertEquals(keys.sorted(), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `assumptions name the conversion, the intensity, the unit cost and the policy values that were used`() {
        val keys = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1800.0, MeasureUnit.SQUARE_FOOT),
            trade("labor", RepairItemKind.LABOR_HOURS, condition = null, quantity = 1.0),
        ).assumptions.map { it.key }
        listOf(
            "conversion.SQUARE_FOOT.TO.SQUARE",
            "intensity.ROOF_COVERING.POOR",
            "unitCost.ROOF_COVERING",
            "unitCost.LABOR_HOURS",
            "generalConditions.pctOfTradeWork",
            "permit.pctOfPermitScope",
            "permit.minimumFee",
            "contingency.pct",
            "strategy",
        ).forEach { assertTrue(it, keys.contains(it)) }
        assertTrue(!keys.any { it.startsWith("unknownConditionReserve") })
    }

    @Test
    fun `an unused rule is not listed as an assumption`() {
        val keys = estimate(trade("roof", RepairItemKind.ROOF_COVERING, condition = null, quantity = 1.0)).assumptions.map { it.key }
        assertTrue(!keys.any { it.startsWith("unitCost.ROOF_COVERING") })
        assertTrue(!keys.any { it.startsWith("intensity.") })
    }

    @Test
    fun `the strategy's contingency is attributed to the strategy policy`() {
        val assumption = estimate(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0)).assumptions.single { it.key == "contingency.pct" }
        assertEquals(ProvenanceSource.STRATEGY_POLICY, assumption.source)
        assertEquals("10.00%", assumption.value)
    }

    @Test
    fun `a request contingency override is attributed to the caller`() {
        val assumption = Fx.estimateOf(
            Fx.request(trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1.0), contingency = 7.5),
        ).assumptions.single { it.key == "contingency.pct" }
        assertEquals(ProvenanceSource.CALLER_OVERRIDE, assumption.source)
        assertEquals("7.50%", assumption.value)
    }

    @Test
    fun `lump-sum defaults and unit conversions are recorded as engine rules`() {
        val estimate = estimate(
            trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR, 1800.0, MeasureUnit.SQUARE_FOOT),
            trade("pf", RepairItemKind.PERMIT_FEES, condition = null, quantity = null, override = 200.0),
        )
        assertEquals(ProvenanceSource.ENGINE_RULE, estimate.assumptions.single { it.key == "quantity.lumpSumDefault" }.source)
        assertEquals(ProvenanceSource.ENGINE_RULE, estimate.assumptions.single { it.key == "conversion.SQUARE_FOOT.TO.SQUARE" }.source)
    }
}
