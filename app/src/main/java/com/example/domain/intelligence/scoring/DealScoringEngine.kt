package com.example.domain.intelligence.scoring

import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.DataProvenanceManifest
import com.example.domain.intelligence.model.DealScoreBreakdown
import com.example.domain.intelligence.model.StrategyFinancialMetrics

object DealScoringEngine {

    fun calculateScore(
        property: CanonicalProperty,
        financials: StrategyFinancialMetrics,
        provenance: DataProvenanceManifest
    ): DealScoreBreakdown {
        val positive = mutableListOf<String>()
        val negative = mutableListOf<String>()

        // 1. Cash Flow Score (0 - 100)
        var cashFlowScore = 50
        if (financials.cashOnCashReturn >= 10.0) {
            cashFlowScore += 40
            positive.add("Strong Cash-on-Cash Return (${String.format("%.1f", financials.cashOnCashReturn)}%)")
        } else if (financials.cashOnCashReturn >= 6.0) {
            cashFlowScore += 25
            positive.add("Healthy Cash-on-Cash Return (${String.format("%.1f", financials.cashOnCashReturn)}%)")
        } else if (financials.cashOnCashReturn < 0.0) {
            cashFlowScore -= 35
            negative.add("Negative monthly cash flow ($${String.format("%,.0f", financials.monthlyCashFlow)}/mo)")
        } else {
            cashFlowScore -= 10
            negative.add("Modest cash flow margin below 6% target")
        }
        cashFlowScore = cashFlowScore.coerceIn(0, 100)

        // 2. Equity & Value Score (0 - 100)
        var equityScore = 60
        val rentToPricePct = (financials.grossMonthlyRent / financials.purchasePrice) * 100.0
        if (rentToPricePct >= 0.8) {
            equityScore += 25
            positive.add("Favorable rent-to-price ratio (${String.format("%.2f", rentToPricePct)}%)")
        } else if (rentToPricePct < 0.6) {
            equityScore -= 15
            negative.add("Rent-to-price ratio is compressed (${String.format("%.2f", rentToPricePct)}%)")
        }
        equityScore = equityScore.coerceIn(0, 100)

        // 3. Market Score (0 - 100)
        var marketScore = 75
        if (property.daysOnMarket != null && property.daysOnMarket < 20) {
            marketScore += 10
            positive.add("High market velocity (only ${property.daysOnMarket} days on market)")
        }
        if (property.zipCode in setOf("78701", "78702", "78704", "78745", "78751")) {
            marketScore += 10
            positive.add("Tier-1 high appreciation submarket (${property.zipCode})")
        }
        marketScore = marketScore.coerceIn(0, 100)

        // 4. Risk Score (Higher score = lower risk)
        var riskScore = 70
        if (financials.dscr >= 1.30) {
            riskScore += 15
            positive.add("Robust debt service coverage ratio (DSCR: ${String.format("%.2f", financials.dscr)})")
        } else if (financials.dscr < 1.15) {
            riskScore -= 25
            negative.add("Tight debt service coverage (DSCR: ${String.format("%.2f", financials.dscr)})")
        }
        val taxRatePct = ((property.propertyTax ?: 0.0) / financials.purchasePrice) * 100.0
        if (taxRatePct > 2.2) {
            riskScore -= 15
            negative.add("High property tax burden (${String.format("%.2f", taxRatePct)}% annual rate)")
        }
        riskScore = riskScore.coerceIn(0, 100)

        // 5. Data Confidence Score
        val hasDirectSource = provenance.records.any { it.source in setOf("Zillow", "Redfin", "Realtor.com", "Homes.com") }
        val hasGovData = provenance.records.any { it.tier == com.example.domain.intelligence.model.ProvenanceSourceTier.GOVERNMENT_DATA }
        var dataConfidenceScore = 60
        if (hasDirectSource) dataConfidenceScore += 20
        if (hasGovData) dataConfidenceScore += 15
        if (property.bedrooms != null && property.bathrooms != null && property.squareFeet != null) dataConfidenceScore += 5
        dataConfidenceScore = dataConfidenceScore.coerceIn(0, 100)

        // 6. Distress Score
        var distressScore = 20
        if (property.daysOnMarket != null && property.daysOnMarket > 45) {
            distressScore += 30
            positive.add("Motivated seller opportunity (${property.daysOnMarket} days on market)")
        }
        if (property.originalListPrice != null && property.originalListPrice > property.listPrice) {
            val discount = property.originalListPrice - property.listPrice
            distressScore += 25
            positive.add("Recent price reduction of $${String.format("%,.0f", discount)}")
        }
        distressScore = distressScore.coerceIn(0, 100)

        // Overall Weighted Deal Score
        val compositeScore = (
            cashFlowScore * 0.35 +
            equityScore * 0.20 +
            marketScore * 0.15 +
            riskScore * 0.20 +
            dataConfidenceScore * 0.10
        ).toInt().coerceIn(0, 100)

        return DealScoreBreakdown(
            dealScore = compositeScore,
            cashFlowScore = cashFlowScore,
            equityScore = equityScore,
            marketScore = marketScore,
            riskScore = riskScore,
            dataConfidenceScore = dataConfidenceScore,
            distressScore = distressScore,
            positiveFactors = positive,
            negativeFactors = negative
        )
    }
}
