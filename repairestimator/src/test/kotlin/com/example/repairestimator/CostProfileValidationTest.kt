package com.example.repairestimator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostProfileValidationTest {

    private fun errorsOf(profile: CostProfile): List<RepairIssue> = CostProfileValidator.validate(profile)

    private fun assertRejectedWith(profile: CostProfile, fragment: String) {
        val errors = errorsOf(profile)
        assertTrue("expected at least one error mentioning '$fragment'", errors.any { it.message.contains(fragment) })
        assertTrue("every profile problem must be an ERROR", errors.all {
            it.code == RepairIssueCode.PROFILE_INVALID && it.severity == RepairIssueSeverity.ERROR
        })
    }

    private fun withUnitCost(kind: RepairItemKind, amount: Double): CostProfile =
        Fx.profile(unitCosts = Fx.defaultUnitCosts() + (kind to amount))

    @Test
    fun `the fixture profile is valid`() {
        assertEquals(emptyList<RepairIssue>(), errorsOf(Fx.profile()))
    }

    @Test
    fun `a missing unit cost is reported by kind name`() {
        val withoutRoof = Fx.defaultUnitCosts() - RepairItemKind.ROOF_COVERING
        assertRejectedWith(Fx.profile(unitCosts = withoutRoof), "ROOF_COVERING")
    }

    @Test
    fun `a unit cost given for a rule-priced kind is reported`() {
        assertRejectedWith(withUnitCost(RepairItemKind.GENERAL_CONDITIONS, 10.0), "GENERAL_CONDITIONS")
    }

    @Test
    fun `negative, non-finite and over-limit unit costs are each reported`() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, RepairLimits.MAX_UNIT_COST + 1.0)
            .forEach { bad -> assertRejectedWith(withUnitCost(RepairItemKind.ROOF_COVERING, bad), "unit cost for ROOF_COVERING") }
    }

    @Test
    fun `zero and exactly the maximum unit cost are accepted`() {
        assertEquals(emptyList<RepairIssue>(), errorsOf(withUnitCost(RepairItemKind.ROOF_COVERING, 0.0)))
        assertEquals(
            emptyList<RepairIssue>(),
            errorsOf(withUnitCost(RepairItemKind.ROOF_COVERING, RepairLimits.MAX_UNIT_COST)),
        )
    }

    @Test
    fun `a unit cost without a reference is reported`() {
        val profile = Fx.profile().copy(
            unitCosts = Fx.defaultUnitCosts().mapValues { (kind, amount) ->
                UnitCost(amount, if (kind == RepairItemKind.HVAC_SYSTEM) " " else "ref ${kind.name}")
            },
        )
        assertRejectedWith(profile, "HVAC_SYSTEM needs a reference")
    }

    @Test
    fun `intensities that decrease as condition worsens are reported`() {
        val bad = ConditionIntensity(excellent = 0.0, good = 0.5, fair = 0.4, poor = 1.0, failed = 1.5, reference = "r")
        assertRejectedWith(Fx.profile(intensity = bad), "must not decrease as condition worsens")
    }

    @Test
    fun `intensities below zero, above the maximum or not finite are reported`() {
        val cases = listOf(
            ConditionIntensity(-0.1, 0.25, 0.5, 1.0, 1.5, "r"),
            ConditionIntensity(0.0, 0.25, 0.5, 1.0, RepairLimits.MAX_INTENSITY + 0.1, "r"),
            ConditionIntensity(0.0, Double.NaN, 0.5, 1.0, 1.5, "r"),
        )
        cases.forEach { assertRejectedWith(Fx.profile(intensity = it), "values must be finite") }
    }

    @Test
    fun `intensity boundaries 0 and 5 are accepted`() {
        val boundary = ConditionIntensity(0.0, 0.0, 0.0, 5.0, 5.0, "r")
        assertEquals(emptyList<RepairIssue>(), errorsOf(Fx.profile(intensity = boundary)))
    }

    @Test
    fun `a kind override on a non-trade kind is reported`() {
        val table = Fx.INTENSITY
        assertRejectedWith(
            Fx.profile(kindOverrides = mapOf(RepairItemKind.LABOR_HOURS to table)),
            "LABOR_HOURS, which is not a trade kind",
        )
    }

    @Test
    fun `a kind override that decreases as condition worsens is reported`() {
        val bad = ConditionIntensity(0.0, 0.9, 0.5, 1.0, 1.5, "kind table")
        assertRejectedWith(
            Fx.profile(kindOverrides = mapOf(RepairItemKind.ROOF_COVERING to bad)),
            "intensity override for ROOF_COVERING must not decrease",
        )
    }

    @Test
    fun `a missing strategy policy is reported`() {
        val without = Fx.STRATEGIES - RepairStrategy.WHOLESALE
        assertRejectedWith(Fx.profile(strategies = without), "strategy policy missing for WHOLESALE")
    }

    @Test
    fun `contingency outside 0 to 100 is reported`() {
        listOf(-0.5, 100.5, Double.NaN).forEach { bad ->
            val strategies = Fx.STRATEGIES + (RepairStrategy.BRRRR to StrategyPolicy(bad, 0.5, "r"))
            assertRejectedWith(Fx.profile(strategies = strategies), "contingency for BRRRR")
        }
    }

    @Test
    fun `finish multiplier must be above zero and at most five`() {
        listOf(0.0, -0.5, 5.5, Double.NaN).forEach { bad ->
            val strategies = Fx.STRATEGIES + (RepairStrategy.FIX_AND_FLIP to StrategyPolicy(10.0, bad, "r"))
            assertRejectedWith(Fx.profile(strategies = strategies), "finish multiplier for FIX_AND_FLIP")
        }
    }

    @Test
    fun `finish multiplier at the limits is accepted`() {
        val strategies = Fx.STRATEGIES + (RepairStrategy.FIX_AND_FLIP to StrategyPolicy(10.0, 5.0, "r")) +
            (RepairStrategy.BRRRR to StrategyPolicy(10.0, 0.0001, "r"))
        assertEquals(emptyList<RepairIssue>(), errorsOf(Fx.profile(strategies = strategies)))
    }

    @Test
    fun `permit percentage and minimum fee are checked`() {
        assertRejectedWith(
            Fx.profile(permit = PermitPolicy(101.0, 50.0, "r")),
            "permit fee percentage",
        )
        assertRejectedWith(
            Fx.profile(permit = PermitPolicy(10.0, -1.0, "r")),
            "permit minimum fee",
        )
    }

    @Test
    fun `general conditions and reserve percentages are checked`() {
        assertRejectedWith(Fx.profile(general = PercentRule(-1.0, "r")), "general conditions percentage")
        assertRejectedWith(Fx.profile(reserve = PercentRule(150.0, "r")), "unknown-condition reserve percentage")
    }

    @Test
    fun `blank id, version and display name are each reported`() {
        assertRejectedWith(Fx.profile().copy(id = " "), "profile id")
        assertRejectedWith(Fx.profile().copy(version = ""), "profile version")
        assertRejectedWith(Fx.profile().copy(displayName = "\t"), "display name")
    }

    @Test
    fun `missing references on policy rules are reported`() {
        assertRejectedWith(Fx.profile(permit = PermitPolicy(10.0, 50.0, "")), "permit policy needs a reference")
        assertRejectedWith(Fx.profile(general = PercentRule(5.0, "")), "general conditions rule needs a reference")
        assertRejectedWith(Fx.profile(reserve = PercentRule(0.0, "")), "unknown-condition reserve rule needs a reference")
    }

    @Test
    fun `every problem is reported together, not only the first`() {
        val broken = Fx.profile(
            unitCosts = Fx.defaultUnitCosts() - RepairItemKind.HVAC_SYSTEM,
            permit = PermitPolicy(-1.0, -1.0, ""),
        ).copy(id = "", version = "")
        val messages = errorsOf(broken).map { it.message }
        assertTrue(messages.size >= 6)
        assertTrue(messages.any { it.contains("HVAC_SYSTEM") })
        assertTrue(messages.any { it.contains("profile id") })
        assertTrue(messages.any { it.contains("permit fee percentage") })
    }
}
