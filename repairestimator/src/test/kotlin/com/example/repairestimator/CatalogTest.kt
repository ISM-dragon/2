package com.example.repairestimator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    @Test
    fun `every kind has a non-blank and unique label`() {
        val labels = RepairItemKind.entries.map { it.label }
        labels.forEach { assertTrue("blank label", it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `there are 22 trade kinds and they cover the 11 trade categories in brief order`() {
        assertEquals(22, Fx.TRADE_KINDS.size)
        assertEquals(
            listOf(
                RehabCategory.ROOF, RehabCategory.HVAC, RehabCategory.PLUMBING, RehabCategory.ELECTRICAL,
                RehabCategory.KITCHEN, RehabCategory.BATHROOMS, RehabCategory.FLOORING, RehabCategory.PAINT,
                RehabCategory.WINDOWS, RehabCategory.EXTERIOR, RehabCategory.FOUNDATION,
            ),
            RehabCategory.entries.filter { it.isTrade },
        )
    }

    @Test
    fun `every trade category has at least one trade kind`() {
        RehabCategory.entries.filter { it.isTrade }.forEach { category ->
            assertTrue("${category.name} has no trade kind", Fx.TRADE_KINDS.any { it.category == category })
        }
    }

    @Test
    fun `non-trade categories are permits, labor, contingency and unknown`() {
        assertEquals(
            setOf(
                RehabCategory.PERMITS,
                RehabCategory.LABOR,
                RehabCategory.CONTINGENCY,
                RehabCategory.UNKNOWN_INSPECTION_REQUIRED,
            ),
            RehabCategory.entries.filter { !it.isTrade }.toSet(),
        )
    }

    @Test
    fun `every category has at least one item kind`() {
        RehabCategory.entries.forEach { category ->
            assertTrue("${category.name} has no kind", RepairItemKind.entries.any { it.category == category })
        }
    }

    @Test
    fun `category order is the order the brief lists them in`() {
        assertEquals(
            listOf(
                "ROOF", "HVAC", "PLUMBING", "ELECTRICAL", "KITCHEN", "BATHROOMS", "FLOORING", "PAINT", "WINDOWS",
                "EXTERIOR", "FOUNDATION", "PERMITS", "LABOR", "CONTINGENCY", "UNKNOWN_INSPECTION_REQUIRED",
            ),
            RehabCategory.entries.map { it.name },
        )
    }

    @Test
    fun `profile-priced kinds are the trade kinds plus labor hours`() {
        assertEquals(Fx.TRADE_KINDS.toSet() + setOf(RepairItemKind.LABOR_HOURS), RepairItemKind.PROFILE_PRICED)
        assertEquals(23, RepairItemKind.PROFILE_PRICED.size)
    }

    @Test
    fun `each role holds exactly the kinds the brief expects`() {
        assertEquals(setOf(RepairItemKind.LABOR_HOURS), kindsWithRole(KindRole.LABOR_HOURS))
        assertEquals(
            setOf(RepairItemKind.GENERAL_CONDITIONS, RepairItemKind.PERMIT_FEES),
            kindsWithRole(KindRole.EXPLICIT_OR_RULE),
        )
        assertEquals(setOf(RepairItemKind.UNKNOWN_SCOPE_ALLOWANCE), kindsWithRole(KindRole.CALLER_ALLOWANCE))
        assertEquals(
            setOf(RepairItemKind.CONTINGENCY, RepairItemKind.UNKNOWN_CONDITION_RESERVE),
            kindsWithRole(KindRole.POLICY_ONLY),
        )
    }

    @Test
    fun `finish items are kitchen, bathroom, flooring and interior paint`() {
        assertEquals(
            setOf(
                RepairItemKind.KITCHEN_CABINETS,
                RepairItemKind.KITCHEN_COUNTERTOPS,
                RepairItemKind.KITCHEN_APPLIANCES,
                RepairItemKind.BATHROOM_FULL,
                RepairItemKind.BATHROOM_HALF,
                RepairItemKind.FLOORING_HARD_SURFACE,
                RepairItemKind.FLOORING_CARPET,
                RepairItemKind.PAINT_INTERIOR,
            ),
            RepairItemKind.entries.filter { it.finishItem }.toSet(),
        )
    }

    @Test
    fun `permit trigger is on by default only for structural and system work`() {
        assertEquals(
            setOf(
                RepairItemKind.ROOF_COVERING,
                RepairItemKind.HVAC_SYSTEM,
                RepairItemKind.PLUMBING_REPIPE,
                RepairItemKind.WATER_HEATER,
                RepairItemKind.ELECTRICAL_PANEL,
                RepairItemKind.ELECTRICAL_REWIRE,
                RepairItemKind.FOUNDATION_CRACK_REPAIR,
                RepairItemKind.FOUNDATION_PIER_SUPPORT,
            ),
            RepairItemKind.entries.filter { it.permitTriggerByDefault }.toSet(),
        )
    }

    @Test
    fun `pricing unit of every kind is the documented unit`() {
        val expected = mapOf(
            RepairItemKind.ROOF_COVERING to MeasureUnit.SQUARE,
            RepairItemKind.HVAC_SYSTEM to MeasureUnit.EACH,
            RepairItemKind.HVAC_DUCTWORK to MeasureUnit.LINEAR_FOOT,
            RepairItemKind.PLUMBING_REPIPE to MeasureUnit.LINEAR_FOOT,
            RepairItemKind.PLUMBING_FIXTURE to MeasureUnit.EACH,
            RepairItemKind.WATER_HEATER to MeasureUnit.EACH,
            RepairItemKind.ELECTRICAL_PANEL to MeasureUnit.EACH,
            RepairItemKind.ELECTRICAL_REWIRE to MeasureUnit.SQUARE_FOOT,
            RepairItemKind.KITCHEN_CABINETS to MeasureUnit.LINEAR_FOOT,
            RepairItemKind.KITCHEN_COUNTERTOPS to MeasureUnit.SQUARE_FOOT,
            RepairItemKind.KITCHEN_APPLIANCES to MeasureUnit.EACH,
            RepairItemKind.BATHROOM_FULL to MeasureUnit.EACH,
            RepairItemKind.BATHROOM_HALF to MeasureUnit.EACH,
            RepairItemKind.FLOORING_HARD_SURFACE to MeasureUnit.SQUARE_FOOT,
            RepairItemKind.FLOORING_CARPET to MeasureUnit.SQUARE_FOOT,
            RepairItemKind.PAINT_INTERIOR to MeasureUnit.SQUARE_FOOT,
            RepairItemKind.WINDOW_REPLACEMENT to MeasureUnit.EACH,
            RepairItemKind.EXTERIOR_SIDING to MeasureUnit.SQUARE_FOOT,
            RepairItemKind.EXTERIOR_PAINT to MeasureUnit.SQUARE_FOOT,
            RepairItemKind.EXTERIOR_GUTTERS to MeasureUnit.LINEAR_FOOT,
            RepairItemKind.FOUNDATION_CRACK_REPAIR to MeasureUnit.EACH,
            RepairItemKind.FOUNDATION_PIER_SUPPORT to MeasureUnit.EACH,
            RepairItemKind.LABOR_HOURS to MeasureUnit.HOUR,
            RepairItemKind.GENERAL_CONDITIONS to MeasureUnit.LUMP_SUM,
            RepairItemKind.PERMIT_FEES to MeasureUnit.LUMP_SUM,
            RepairItemKind.CONTINGENCY to MeasureUnit.LUMP_SUM,
            RepairItemKind.UNKNOWN_SCOPE_ALLOWANCE to MeasureUnit.LUMP_SUM,
            RepairItemKind.UNKNOWN_CONDITION_RESERVE to MeasureUnit.LUMP_SUM,
        )
        assertEquals("every kind must be listed", RepairItemKind.entries.toSet(), expected.keys)
        expected.forEach { (kind, unit) -> assertEquals(kind.name, unit, kind.pricingUnit) }
    }

    @Test
    fun `the conversion table is exactly identity plus square feet to squares`() {
        for (from in MeasureUnit.entries) {
            for (target in MeasureUnit.entries) {
                val factor = UnitConversions.factor(from, target)
                val expectedDefined = from == target || (from == MeasureUnit.SQUARE_FOOT && target == MeasureUnit.SQUARE)
                assertEquals("${from.name} -> ${target.name}", expectedDefined, factor != null)
                if (from == MeasureUnit.SQUARE_FOOT && target == MeasureUnit.SQUARE) {
                    assertEquals(0, java.math.BigDecimal("0.01").compareTo(factor))
                }
            }
        }
    }

    @Test
    fun `roofing accepts squares and square feet, and every other kind accepts only its pricing unit`() {
        RepairItemKind.entries.forEach { kind ->
            val accepted = UnitConversions.acceptedUnits(kind.pricingUnit)
            assertTrue("${kind.name} must accept its own pricing unit", accepted.contains(kind.pricingUnit))
            val expected = if (kind == RepairItemKind.ROOF_COVERING) {
                setOf(MeasureUnit.SQUARE, MeasureUnit.SQUARE_FOOT)
            } else {
                setOf(kind.pricingUnit)
            }
            assertEquals(kind.name, expected, accepted)
        }
    }

    @Test
    fun `limits are the documented values`() {
        assertEquals(500, RepairLimits.MAX_LINES)
        assertEquals(1_000_000.0, RepairLimits.MAX_QUANTITY, 0.0)
        assertEquals(10_000_000.0, RepairLimits.MAX_UNIT_COST, 0.0)
        assertEquals(100.0, RepairLimits.MAX_PERCENT, 0.0)
        assertEquals(5.0, RepairLimits.MAX_INTENSITY, 0.0)
        assertEquals(5.0, RepairLimits.MAX_FINISH_MULTIPLIER, 0.0)
        assertEquals("auto:", RepairLimits.AUTO_ID_PREFIX)
    }

    private fun kindsWithRole(role: KindRole): Set<RepairItemKind> =
        RepairItemKind.entries.filter { it.role == role }.toSet()
}
