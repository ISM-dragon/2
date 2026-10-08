package com.example.repairestimator

import com.example.repairestimator.Fx.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestValidationTest {

    private fun codesOf(vararg lines: RepairScopeLine): List<RepairIssueCode> =
        Fx.rejectionOf(Fx.request(*lines)).map { it.code }

    private fun roof(quantity: Double? = 1.0, unit: MeasureUnit? = null, override: Double? = null) =
        Fx.trade("roof", RepairItemKind.ROOF_COVERING, quantity = quantity, unit = unit, override = override)

    @Test
    fun `an empty scope is rejected`() {
        assertEquals(listOf(RepairIssueCode.EMPTY_SCOPE), codesOf())
    }

    @Test
    fun `more than the line limit is rejected`() {
        val lines = (1..(RepairLimits.MAX_LINES + 1)).map { Fx.trade("l$it", RepairItemKind.PAINT_INTERIOR, quantity = 1.0) }
        val codes = Fx.rejectionOf(Fx.request(*lines.toTypedArray())).map { it.code }
        assertEquals(listOf(RepairIssueCode.TOO_MANY_LINES), codes)
    }

    @Test
    fun `exactly the line limit is accepted`() {
        val lines = (1..RepairLimits.MAX_LINES).map { Fx.trade("l$it", RepairItemKind.PAINT_INTERIOR, quantity = 1.0) }
        val estimate = Fx.estimateOf(Fx.request(*lines.toTypedArray()))
        // Caller lines are echoed one-for-one; the engine adds its own rule lines (auto:) on top.
        assertEquals(RepairLimits.MAX_LINES, estimate.lines.count { !it.id.startsWith(RepairLimits.AUTO_ID_PREFIX) })
    }

    @Test
    fun `blank and whitespace-only ids are rejected`() {
        assertTrue(codesOf(Fx.trade("", RepairItemKind.ROOF_COVERING)).contains(RepairIssueCode.BLANK_LINE_ID))
        assertTrue(codesOf(Fx.trade("   ", RepairItemKind.ROOF_COVERING)).contains(RepairIssueCode.BLANK_LINE_ID))
    }

    @Test
    fun `ids that use the reserved auto prefix are rejected`() {
        assertEquals(
            listOf(RepairIssueCode.RESERVED_LINE_ID),
            codesOf(Fx.trade("auto:roof", RepairItemKind.ROOF_COVERING)),
        )
    }

    @Test
    fun `duplicate ids are rejected`() {
        val codes = codesOf(
            Fx.trade("same", RepairItemKind.ROOF_COVERING),
            Fx.trade("same", RepairItemKind.HVAC_SYSTEM),
            Fx.trade("same", RepairItemKind.WATER_HEATER),
        )
        assertEquals(2, codes.count { it == RepairIssueCode.DUPLICATE_LINE_ID })
    }

    @Test
    fun `policy-calculated kinds cannot be entered as scope lines`() {
        val codes = codesOf(
            Fx.trade("c", RepairItemKind.CONTINGENCY),
            Fx.trade("r", RepairItemKind.UNKNOWN_CONDITION_RESERVE),
        )
        assertEquals(2, codes.count { it == RepairIssueCode.POLICY_KIND_NOT_SUPPLIABLE })
    }

    @Test
    fun `invalid quantities are rejected`() {
        listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, RepairLimits.MAX_QUANTITY + 1.0)
            .forEach { bad ->
                assertEquals("quantity $bad", listOf(RepairIssueCode.INVALID_QUANTITY), codesOf(roof(quantity = bad)))
            }
    }

    @Test
    fun `quantities at the accepted boundaries are priced`() {
        Fx.estimateOf(Fx.request(roof(quantity = 0.000001)))
        val maximum = Fx.estimateOf(Fx.request(roof(quantity = RepairLimits.MAX_QUANTITY)))
        assertEquals(RepairLimits.MAX_QUANTITY * 100.0, maximum.line("roof").amount!!, 0.0)
    }

    @Test
    fun `invalid unit costs are rejected`() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, RepairLimits.MAX_UNIT_COST + 1.0).forEach { bad ->
            assertEquals("unit cost $bad", listOf(RepairIssueCode.INVALID_UNIT_COST), codesOf(roof(override = bad)))
        }
    }

    @Test
    fun `zero and maximum unit costs are accepted`() {
        Fx.estimateOf(Fx.request(roof(override = 0.0)))
        Fx.estimateOf(Fx.request(roof(override = RepairLimits.MAX_UNIT_COST)))
    }

    @Test
    fun `a unit that does not convert to the pricing unit is rejected`() {
        assertEquals(
            listOf(RepairIssueCode.UNIT_NOT_ACCEPTED),
            codesOf(Fx.trade("hvac", RepairItemKind.HVAC_SYSTEM, unit = MeasureUnit.SQUARE)),
        )
        assertEquals(listOf(RepairIssueCode.UNIT_NOT_ACCEPTED), codesOf(roof(unit = MeasureUnit.EACH)))
        assertEquals(
            listOf(RepairIssueCode.UNIT_NOT_ACCEPTED),
            codesOf(Fx.trade("labor", RepairItemKind.LABOR_HOURS, unit = MeasureUnit.LUMP_SUM)),
        )
    }

    @Test
    fun `square feet is accepted for roofing and converted to squares`() {
        val estimate = Fx.estimateOf(Fx.request(roof(quantity = 1800.0, unit = MeasureUnit.SQUARE_FOOT)))
        assertEquals(1800.0, estimate.line("roof").amount!!, 0.0)
    }

    @Test
    fun `explicit general conditions and permit fees need an amount`() {
        assertEquals(
            listOf(RepairIssueCode.EXPLICIT_AMOUNT_REQUIRED),
            codesOf(Fx.trade("gc", RepairItemKind.GENERAL_CONDITIONS, condition = null, quantity = null)),
        )
        assertEquals(
            listOf(RepairIssueCode.EXPLICIT_AMOUNT_REQUIRED),
            codesOf(Fx.trade("pf", RepairItemKind.PERMIT_FEES, condition = null, quantity = null)),
        )
    }

    @Test
    fun `explicit general conditions and permit fees with an amount are accepted`() {
        val estimate = Fx.estimateOf(
            Fx.request(
                Fx.trade("gc", RepairItemKind.GENERAL_CONDITIONS, condition = null, quantity = null, override = 300.0),
                Fx.trade("pf", RepairItemKind.PERMIT_FEES, condition = null, quantity = null, override = 200.0),
            ),
        )
        assertEquals(300.0, estimate.line("gc").amount!!, 0.0)
        assertEquals(200.0, estimate.line("pf").amount!!, 0.0)
    }

    @Test
    fun `contingency override outside 0 to 100 is rejected`() {
        listOf(-0.1, 100.1, Double.NaN).forEach { bad ->
            val codes = Fx.rejectionOf(Fx.request(roof(), contingency = bad)).map { it.code }
            assertEquals("contingency $bad", listOf(RepairIssueCode.INVALID_CONTINGENCY_OVERRIDE), codes)
        }
    }

    @Test
    fun `contingency override at 0 and 100 is accepted`() {
        assertEquals(0.0, Fx.estimateOf(Fx.request(roof(), contingency = 0.0)).totals.contingencyPct, 0.0)
        assertEquals(100.0, Fx.estimateOf(Fx.request(roof(), contingency = 100.0)).totals.contingencyPct, 0.0)
    }

    @Test
    fun `a rejected request returns issues and no estimate`() {
        val outcome = RepairEstimator.estimate(Fx.request(roof(quantity = -1.0)))
        assertTrue(outcome is RepairEstimateOutcome.Rejected)
        val issues = (outcome as RepairEstimateOutcome.Rejected).issues
        assertTrue(issues.isNotEmpty())
        assertTrue(issues.all { it.severity == RepairIssueSeverity.ERROR })
    }

    @Test
    fun `every error in a request is reported together`() {
        val codes = codesOf(
            roof(quantity = -1.0, override = Double.NaN),
            Fx.trade("roof", RepairItemKind.HVAC_SYSTEM),
            Fx.trade("c", RepairItemKind.CONTINGENCY),
        )
        assertTrue(codes.containsAll(listOf(
            RepairIssueCode.INVALID_QUANTITY,
            RepairIssueCode.INVALID_UNIT_COST,
            RepairIssueCode.DUPLICATE_LINE_ID,
            RepairIssueCode.POLICY_KIND_NOT_SUPPLIABLE,
        )))
    }

    @Test
    fun `an invalid profile rejects the request before any line is priced`() {
        val badProfile = Fx.profile(unitCosts = Fx.defaultUnitCosts() - RepairItemKind.ROOF_COVERING)
        val codes = Fx.rejectionOf(Fx.request(roof(), profile = badProfile)).map { it.code }
        assertEquals(listOf(RepairIssueCode.PROFILE_INVALID), codes)
    }
}
