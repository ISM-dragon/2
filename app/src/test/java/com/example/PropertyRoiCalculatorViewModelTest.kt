package com.example

import com.example.ui.screens.roi.CalculatedRoiMetrics
import com.example.ui.screens.roi.CalculationSource
import com.example.ui.screens.roi.LocalMarketDataInput
import com.example.ui.screens.roi.PropertyDetailsInput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PropertyRoiCalculatorViewModelTest {

    @Test
    fun testPropertyDetailsAndMarketDataInputs() {
        val details = PropertyDetailsInput(
            title = "Austin Duplex Opportunity",
            address = "2100 E 6th St",
            city = "Austin",
            state = "TX",
            zipCode = "78702",
            purchasePrice = 520000.0,
            squareFeet = 2200,
            bedrooms = 4,
            bathrooms = 3.0,
            renovationCost = 20000.0,
            downPaymentPct = 25.0
        )

        val marketData = LocalMarketDataInput(
            medianAreaPrice = 550000.0,
            pricePerSqFt = 265.0,
            estimatedMonthlyRent = 4200.0,
            neighborhoodAppreciationRatePct = 5.0,
            marketDemand = "High"
        )

        assertEquals("Austin Duplex Opportunity", details.title)
        assertEquals(520000.0, details.purchasePrice, 0.01)
        assertEquals(4200.0, marketData.estimatedMonthlyRent, 0.01)
        assertEquals(5.0, marketData.neighborhoodAppreciationRatePct, 0.01)
    }

    @Test
    fun testGeminiResponseJsonParsing() {
        val sampleAiJson = """
            {
                "capRatePct": 7.4,
                "cashOnCashReturnPct": 9.8,
                "monthlyCashFlow": 620.0,
                "annualCashFlow": 7440.0,
                "netOperatingIncomeAnnual": 38480.0,
                "grossRentalIncomeAnnual": 50400.0,
                "totalCashRequired": 150000.0,
                "debtServiceCoverageRatio": 1.34,
                "grossRentMultiplier": 10.3,
                "projected5YearRoiPct": 68.2,
                "breakEvenOccupancyPct": 71.5,
                "investmentVerdict": "Strong Buy",
                "recommendedStrategy": "Long-Term Buy & Hold",
                "marketInsights": "Submarket exhibits 5% annual appreciation with solid rental demand.",
                "riskFactors": ["Interest rate sensitivity", "Property tax reassessment"],
                "localMarketScore": 88
            }
        """.trimIndent()

        val obj = JSONObject(sampleAiJson)
        val capRate = obj.getDouble("capRatePct")
        val coc = obj.getDouble("cashOnCashReturnPct")
        val verdict = obj.getString("investmentVerdict")
        val dscr = obj.getDouble("debtServiceCoverageRatio")

        assertEquals(7.4, capRate, 0.01)
        assertEquals(9.8, coc, 0.01)
        assertEquals("Strong Buy", verdict)
        assertEquals(1.34, dscr, 0.01)
    }

    @Test
    fun testCalculatedRoiMetricsCompleteness() {
        val metrics = CalculatedRoiMetrics(
            capRatePct = 6.8,
            cashOnCashReturnPct = 8.5,
            monthlyCashFlow = 450.0,
            annualCashFlow = 5400.0,
            netOperatingIncomeAnnual = 32000.0,
            grossRentalIncomeAnnual = 42000.0,
            totalCashRequired = 110000.0,
            debtServiceCoverageRatio = 1.28,
            grossRentMultiplier = 11.2,
            projected5YearRoiPct = 52.4,
            breakEvenOccupancyPct = 74.0,
            investmentVerdict = "Moderate Opportunity",
            recommendedStrategy = "Turnkey Cash Flow",
            marketInsights = "Healthy cash flow in expanding suburban pocket.",
            riskFactors = listOf("Tenant turnover", "Maintenance reserve"),
            localMarketScore = 78
        )

        assertTrue(metrics.capRatePct > 0)
        assertTrue(metrics.cashOnCashReturnPct > 0)
        assertTrue(metrics.debtServiceCoverageRatio > 1.0)
        assertEquals(2, metrics.riskFactors.size)
        assertEquals("Moderate Opportunity", metrics.investmentVerdict)
    }
}
