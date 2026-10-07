package com.example.domain.intelligence.scoring

import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.DataProvenanceManifest
import com.example.domain.intelligence.model.DealScoreBreakdown
import com.example.domain.intelligence.model.ProvenanceSourceTier
import com.example.domain.intelligence.model.StrategyFinancialMetrics
import com.example.domain.scoring.DealInput
import com.example.domain.scoring.DealScoringEngine as DeterministicScoringEngine
import com.example.domain.scoring.ScoringWeights
import kotlin.math.roundToInt

/**
 * Compatibility adapter for the property-import pipeline.
 *
 * All score arithmetic and explanation is delegated to the standalone deterministic engine. This
 * adapter only maps explicitly available canonical/listing and underwriting facts; portal names
 * are not mistaken for seller motivation, generated enrichment/comps are not treated as verified
 * facts, and absent value/flood/delinquency metrics remain absent.
 */
object DealScoringEngine {

    fun calculateScore(
        property: CanonicalProperty,
        financials: StrategyFinancialMetrics,
        provenance: DataProvenanceManifest
    ): DealScoreBreakdown {
        // The legacy financial model applies an assumed rent when the property has no estimate.
        // Do not feed that fallback, or an explicitly AI-inferred metric, into deal scoring.
        fun isNotAiInferred(field: String): Boolean =
            provenance.getProvenanceFor(field)?.tier != ProvenanceSourceTier.AI_INFERENCE

        val rentProvenance = provenance.getProvenanceFor("estimatedRent")
        val explicitRent = property.estimatedRent
            ?.takeIf { it.isFinite() && it > 0.0 && rentProvenance?.tier != ProvenanceSourceTier.AI_INFERENCE }
        val explicitListPriceAvailable = isNotAiInferred("listPrice")
        val rentConfidencePct = if (explicitRent != null && rentProvenance?.tier != ProvenanceSourceTier.AI_INFERENCE) {
            rentProvenance?.confidence?.times(100.0)
        } else {
            null
        }

        val originalPrice = property.originalListPrice
        val listingPrice = property.listPrice
        val priceDropPct = if (
            explicitListPriceAvailable && isNotAiInferred("originalListPrice") &&
            originalPrice != null && originalPrice.isFinite() && originalPrice > 0.0 &&
            listingPrice.isFinite() && listingPrice >= 0.0
        ) {
            (((originalPrice - listingPrice) / originalPrice) * 100.0)
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, 100.0)
        } else {
            null
        }

        val underwritingMetricsAvailable = explicitRent != null && explicitListPriceAvailable
        val input = DealInput(
            purchasePrice = financials.purchasePrice.takeIf { explicitListPriceAvailable },
            // Cash-flow outputs are scenario projections; only score them when rent and list price
            // are explicit non-AI inputs, not finance-model fallbacks or AI-inferred values.
            monthlyCashFlow = financials.monthlyCashFlow.takeIf { underwritingMetricsAvailable },
            annualNoi = financials.netOperatingIncomeAnnual.takeIf { underwritingMetricsAvailable },
            capRatePct = financials.capRate.takeIf { underwritingMetricsAvailable },
            cashOnCashPct = financials.cashOnCashReturn.takeIf { underwritingMetricsAvailable },
            dscr = financials.dscr.takeIf { underwritingMetricsAvailable },
            monthlyDebtService = financials.monthlyDebtService.takeIf { underwritingMetricsAvailable },
            grossMonthlyRent = explicitRent,
            // Vacancy, rehab, closing costs and ARV in the legacy model are fixed strategy
            // assumptions/projections, not observed property inputs; leave these unscored.
            interestRatePct = financials.interestRate,
            propertyPricePerSqFt = property.pricePerSqft.takeIf {
                explicitListPriceAvailable && isNotAiInferred("pricePerSqFt")
            },
            yearBuilt = property.yearBuilt.takeIf { isNotAiInferred("yearBuilt") },
            listingDaysOnMarket = property.daysOnMarket.takeIf { isNotAiInferred("daysOnMarket") },
            cumulativePriceDropPct = priceDropPct,
            rentConfidenceScore = rentConfidencePct
        )
        val result = DeterministicScoringEngine.evaluate(input)

        fun score(id: String): Int = result.subscores
            .first { it.id == id }
            .score
            .roundToInt()
            .coerceIn(0, 100)

        return DealScoreBreakdown(
            dealScore = result.score.roundToInt().coerceIn(0, 100),
            cashFlowScore = score(ScoringWeights.CASH_FLOW),
            equityScore = score(ScoringWeights.EQUITY),
            marketScore = score(ScoringWeights.MARKET),
            riskScore = score(ScoringWeights.RISK_SAFETY),
            dataConfidenceScore = result.dataConfidence.roundToInt().coerceIn(0, 100),
            distressScore = score(ScoringWeights.DISTRESS),
            positiveFactors = result.reasons.positive,
            negativeFactors = result.reasons.negative,
            detailedResult = result
        )
    }
}
