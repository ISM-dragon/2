package com.example.repairestimator

import org.junit.Assert.*
import org.junit.Test

/** Synthetic prices only. A partial priced scope is not a market-validated full rehab bid. */
class IndependentRepairAuditTest {
    @Test
    fun independentRoofAndKitchenBudgetAcrossStrategies() {
        // Roof: 1800 SF / 100 = 18 squares * $100 = $1800 (physical trade).
        // Cabinets: 10 LF * $400 = $4000, with finish factor .5 for BRRRR.
        for ((strategy, expected) in listOf(
            RepairStrategy.WHOLESALE to 7488.0, // (5800 + 290)*1.20 + 180
            RepairStrategy.BRRRR to 4569.0,     // (3800 + 190)*1.10 + 180
            RepairStrategy.FIX_AND_FLIP to 7183.5 // (5800 + 290)*1.15 + 180
        )) {
            val request = Fx.request(
                Fx.trade("roof", RepairItemKind.ROOF_COVERING, ConditionLevel.POOR,
                    1800.0, MeasureUnit.SQUARE_FOOT, conditionBasis = EvidenceBasis.INSPECTED,
                    quantityBasis = EvidenceBasis.MEASURED, evidence = "synthetic inspection fixture"),
                Fx.trade("kitchen", RepairItemKind.KITCHEN_CABINETS, ConditionLevel.POOR, 10.0),
                strategy = strategy
            )
            val result = Fx.estimateOf(request)
            assertEquals(expected, result.totals.totalRehab, 0.001)
            assertEquals(180.0, result.totals.permitSubtotal, 0.001)
            assertFalse(result.completeness.isComplete)
            assertEquals(result, Fx.estimateOf(request.copy(lines = request.lines.reversed())))
        }
    }

    @Test
    fun unknownConditionIsUnpricedRatherThanAZeroBid() {
        val result = Fx.estimateOf(Fx.request(
            Fx.trade("roof", RepairItemKind.ROOF_COVERING, null, 18.0)
        ))
        assertNull(result.lines.single { it.id == "roof" }.amount)
        assertTrue(result.completeness.unpricedLineIds.contains("roof"))
        assertFalse(result.completeness.isComplete)
    }
}
