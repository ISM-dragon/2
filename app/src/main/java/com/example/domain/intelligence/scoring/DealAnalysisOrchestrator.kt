package com.example.domain.intelligence.scoring

import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.DataProvenanceManifest
import com.example.domain.intelligence.model.DealScoreBreakdown
import com.example.domain.intelligence.model.ProvenanceSourceTier
import com.example.domain.intelligence.model.StrategyFinancialMetrics
import com.example.domain.scoring.DealInput
import com.example.domain.scoring.DealScoringEngine
import com.example.domain.scoring.DealScoreResult
import com.example.domain.scoring.ScoringConfig
import com.example.domain.scoring.ScoringWeights
import kotlin.math.roundToInt

/**
 * Market, public-records and verified-channel facts that live OUTSIDE the canonical listing
 * and the underwriting scenario.
 *
 * Every field is optional and provenance-checked by [DealAnalysisOrchestrator] against the
 * [DataProvenanceManifest] (the `field` argument shown per property is the manifest key).
 *
 * Strict rules enforced by the orchestrator:
 *  - a field whose provenance tier is [ProvenanceSourceTier.AI_INFERENCE] is treated as
 *    missing: it reduces coverage/confidence instead of contributing points;
 *  - a portal name ("Zillow", "Redfin", ...) is NEVER a seller channel and is never mapped to
 *    [sourceType]; only an explicitly verified channel is accepted there;
 *  - projected ARV / fixed repair assumptions from the legacy financial model are NOT value
 *    facts: [afterRepairValue] and [renovationCost] must be explicitly provided and trusted.
 */
data class MarketFacts(
    /** Verified or explicitly estimated current market value (appraisal, recorded sale, verified comp set). Provenance field: `estimatedMarketValue`. */
    val estimatedMarketValue: Double? = null,
    /** Explicitly verified after-repair value; missing means "unknown", never "$0 rehab". Provenance field: `afterRepairValue`. */
    val afterRepairValue: Double? = null,
    /** Explicitly verified renovation cost (contractor bid, recorded scope). Provenance field: `renovationCost`. */
    val renovationCost: Double? = null,
    /** Median sales price of the comparison area. Provenance field: `medianAreaPrice`. */
    val medianAreaPrice: Double? = null,
    /** Median $/sqft of the comparison area. Provenance field: `areaPricePerSqFt`. */
    val areaPricePerSqFt: Double? = null,
    /** Average days-on-market of the comparison area. Provenance field: `areaDaysOnMarket`. */
    val areaDaysOnMarket: Int? = null,
    /** Neighborhood appreciation, percent per year. Provenance field: `neighborhoodAppreciationPct`. */
    val neighborhoodAppreciationPct: Double? = null,
    /** Qualitative market demand ("High", "Buyer's Market", ...). Provenance field: `marketDemand`. */
    val marketDemand: String? = null,
    /** Flood-zone status from a flood map; unknown stays unknown. Provenance field: `floodZone`. */
    val floodZone: Boolean? = null,
    /** Recorded tax-delinquency status; unknown stays unknown. Provenance field: `taxDelinquent`. */
    val taxDelinquent: Boolean? = null,
    /** Verified seller channel ("FORECLOSURE", "WHOLESALE", "ON_MARKET", ...). Provenance field: `sourceType`. */
    val sourceType: String? = null
)

/**
 * Verified coverage behind the estimates used by the score.
 *
 *  - [compsCount]: number of verified comparable sales supporting the value estimate(s).
 *    Drives the comparable-sales Data Confidence adjustment only -- it never adds deal-quality
 *    points on its own. Provenance field: `compsCount`.
 *  - [rentEstimateLow] / [rentEstimateHigh]: provider-reported rent bounds; a wide or
 *    one-sided range lowers confidence (and inconsistent pairs are dropped with a warning).
 *    Provenance fields: `rentEstimateLow`, `rentEstimateHigh`.
 */
data class CompCoverage(
    val compsCount: Int? = null,
    val rentEstimateLow: Double? = null,
    val rentEstimateHigh: Double? = null
)

/**
 * Deterministic Deal Analysis orchestration contract.
 *
 * This is the single, clean entry point that turns canonical property facts, underwriting
 * outputs, market facts, comp coverage and provenance into a [DealScoreResult]:
 *
 * ```
 * DealAnalysisOrchestrator.score(property, underwriting, market, comps, provenance)
 *     -> DealScoreResult   // score, subscores, weights, reasons, warnings, dataConfidence
 * ```
 *
 * Guarantees:
 *  1. AUTHORITATIVE ENGINE -- all score arithmetic is delegated to the standalone, pure
 *     [com.example.domain.scoring.DealScoringEngine] (`evaluate`). This orchestrator performs
 *     no scoring math of its own; it only maps and filters facts. The produced result carries
 *     that engine's `scoringModelVersion`.
 *  2. DETERMINISTIC -- pure function of its arguments. No clocks, randomness, I/O or
 *     environment access; identical arguments always produce an identical [DealScoreResult].
 *  3. PROVENANCE-AWARE -- any fact whose manifest entry is [ProvenanceSourceTier.AI_INFERENCE]
 *     is dropped (treated as missing) before the engine sees it. Underwriting scenario outputs
 *     (cash flow, NOI, cap rate, cash-on-cash, DSCR, debt service) are promoted only when the
 *     canonical inputs the scenario was built on (explicit list price + explicit non-AI rent)
 *     are themselves trusted. Missing data attenuates subscores toward neutral and lowers the
 *     Data Confidence Score; it never inflates a score.
 *  4. AI ISOLATION -- there is no API that accepts AI-produced numbers or score overrides.
 *     [DealScoringEngine.attachAiObservations] is the only AI-facing door and it only appends
 *     sanitized display text to an already-computed result.
 *  5. QUALIFICATION INDEPENDENCE -- this contract has no dependency on the legacy
 *     qualification scoring (`domain/qualification`); qualification remains a separate gate.
 */
object DealAnalysisOrchestrator {

    fun score(
        property: CanonicalProperty,
        underwriting: StrategyFinancialMetrics? = null,
        market: MarketFacts = MarketFacts(),
        comps: CompCoverage = CompCoverage(),
        provenance: DataProvenanceManifest = DataProvenanceManifest(property.propertyId),
        weights: ScoringWeights = ScoringWeights(),
        config: ScoringConfig = ScoringConfig(),
        asOfYear: Int = DealScoringEngine.DEFAULT_AS_OF_YEAR
    ): DealScoreResult {
        val input = mapToFacts(property, underwriting, market, comps, provenance)
        return DealScoringEngine.evaluate(input, weights, config, asOfYear)
    }

    /**
     * Projects an immutable [DealScoreResult] onto the legacy [DealScoreBreakdown] shape used
     * by AI analysis models. Rounding only; no re-scoring, no selection, no overrides.
     */
    fun toBreakdown(result: DealScoreResult): DealScoreBreakdown {
        fun subscore(id: String): Int = result.subscores
            .first { it.id == id }
            .score
            .roundToInt()
            .coerceIn(0, 100)

        return DealScoreBreakdown(
            dealScore = result.score.roundToInt().coerceIn(0, 100),
            cashFlowScore = subscore(ScoringWeights.CASH_FLOW),
            equityScore = subscore(ScoringWeights.EQUITY),
            marketScore = subscore(ScoringWeights.MARKET),
            riskScore = subscore(ScoringWeights.RISK_SAFETY),
            dataConfidenceScore = result.dataConfidence.roundToInt().coerceIn(0, 100),
            distressScore = subscore(ScoringWeights.DISTRESS),
            positiveFactors = result.reasons.positive,
            negativeFactors = result.reasons.negative,
            detailedResult = result
        )
    }

    // =========================================================================
    // Fact mapping + provenance filtering (no scoring math)
    // =========================================================================

    /** True when the manifest has no entry for [field], or its best entry is not AI inference. */
    private fun DataProvenanceManifest.isTrusted(field: String): Boolean =
        getProvenanceFor(field)?.tier != ProvenanceSourceTier.AI_INFERENCE

    /** [value] survives only when its manifest entry (if any) is not an AI inference. */
    private fun <T> T?.takeIfTrusted(manifest: DataProvenanceManifest, field: String): T? =
        this?.takeIf { manifest.isTrusted(field) }

    /**
     * Maps the contract inputs onto the engine's [DealInput].
     *
     * Mapping policy:
     *  - canonical listing facts are provenance-filtered field by field;
     *  - underwriting outputs are scenario projections: they are scored only when the
     *    scenario's canonical inputs (explicit trusted list price + explicit trusted rent)
     *    exist, so a model fallback can never smuggle points into the deal score;
     *  - market/records facts come exclusively from [MarketFacts];
     *  - portal names, legacy fixed assumptions (vacancy, default repairs, projected ARV)
     *    and AI inferences are left missing; the engine then attenuates toward neutral.
     */
    private fun mapToFacts(
        property: CanonicalProperty,
        underwriting: StrategyFinancialMetrics?,
        market: MarketFacts,
        comps: CompCoverage,
        provenance: DataProvenanceManifest
    ): DealInput {
        // The legacy financial model applies an assumed rent when the property has no estimate.
        // Never feed that fallback -- or an explicitly AI-inferred rent -- into deal scoring.
        val rentProvenance = provenance.getProvenanceFor("estimatedRent")
        val explicitRent = property.estimatedRent
            ?.takeIf { it.isFinite() && it > 0.0 && rentProvenance?.tier != ProvenanceSourceTier.AI_INFERENCE }
        val explicitListPriceAvailable = provenance.isTrusted("listPrice")
        val rentConfidencePct = if (explicitRent != null && rentProvenance?.tier != ProvenanceSourceTier.AI_INFERENCE) {
            rentProvenance?.confidence?.times(100.0)
        } else {
            null
        }

        val originalPrice = property.originalListPrice
        val listingPrice = property.listPrice
        val priceDropPct = if (
            explicitListPriceAvailable && provenance.isTrusted("originalListPrice") &&
            originalPrice != null && originalPrice.isFinite() && originalPrice > 0.0 &&
            listingPrice.isFinite() && listingPrice >= 0.0
        ) {
            (((originalPrice - listingPrice) / originalPrice) * 100.0)
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, 100.0)
        } else {
            null
        }

        // Cash-flow outputs are scenario projections; only score them when rent and list price
        // are explicit non-AI inputs, not finance-model fallbacks or AI-inferred values.
        val underwritingMetricsAvailable = underwriting != null && explicitRent != null && explicitListPriceAvailable

        return DealInput(
            purchasePrice = (underwriting?.purchasePrice ?: property.listPrice)
                .takeIf { explicitListPriceAvailable },
            monthlyCashFlow = underwriting?.monthlyCashFlow.takeIf { underwritingMetricsAvailable },
            annualNoi = underwriting?.netOperatingIncomeAnnual.takeIf { underwritingMetricsAvailable },
            capRatePct = underwriting?.capRate.takeIf { underwritingMetricsAvailable },
            cashOnCashPct = underwriting?.cashOnCashReturn.takeIf { underwritingMetricsAvailable },
            dscr = underwriting?.dscr.takeIf { underwritingMetricsAvailable },
            monthlyDebtService = underwriting?.monthlyDebtService.takeIf { underwritingMetricsAvailable },
            grossMonthlyRent = explicitRent,
            // Scenario financing the caller explicitly chose (not a property fact, but an
            // intentional, deterministic input to the underwriting scenario).
            interestRatePct = underwriting?.interestRate,
            // Vacancy, default repairs, closing costs and projected ARV in the legacy model are
            // fixed strategy assumptions, not observed inputs; they stay missing unless the
            // contract carries an explicit, provenance-trusted value below.
            renovationCost = market.renovationCost.takeIfTrusted(provenance, "renovationCost"),
            estimatedMarketValue = market.estimatedMarketValue.takeIfTrusted(provenance, "estimatedMarketValue"),
            afterRepairValue = market.afterRepairValue.takeIfTrusted(provenance, "afterRepairValue"),
            medianAreaPrice = market.medianAreaPrice.takeIfTrusted(provenance, "medianAreaPrice"),
            neighborhoodAppreciationPct = market.neighborhoodAppreciationPct
                .takeIfTrusted(provenance, "neighborhoodAppreciationPct"),
            marketDemand = market.marketDemand?.takeIf {
                it.isNotBlank() && provenance.isTrusted("marketDemand")
            },
            areaDaysOnMarket = market.areaDaysOnMarket.takeIfTrusted(provenance, "areaDaysOnMarket"),
            propertyPricePerSqFt = property.pricePerSqft.takeIf {
                explicitListPriceAvailable && provenance.isTrusted("pricePerSqFt")
            },
            areaPricePerSqFt = market.areaPricePerSqFt.takeIfTrusted(provenance, "areaPricePerSqFt"),
            yearBuilt = property.yearBuilt.takeIf { provenance.isTrusted("yearBuilt") },
            floodZone = market.floodZone.takeIfTrusted(provenance, "floodZone"),
            // A portal (`Zillow`, `Redfin`, ...) is not a seller channel; only an explicitly
            // verified channel from market/records facts may drive the distress dimension.
            sourceType = market.sourceType?.takeIf {
                it.isNotBlank() && provenance.isTrusted("sourceType")
            },
            listingDaysOnMarket = property.daysOnMarket.takeIf { provenance.isTrusted("daysOnMarket") },
            cumulativePriceDropPct = priceDropPct,
            taxDelinquent = market.taxDelinquent.takeIfTrusted(provenance, "taxDelinquent"),
            compsCount = comps.compsCount.takeIfTrusted(provenance, "compsCount"),
            rentConfidenceScore = rentConfidencePct,
            rentEstimateLow = comps.rentEstimateLow.takeIfTrusted(provenance, "rentEstimateLow"),
            rentEstimateHigh = comps.rentEstimateHigh.takeIfTrusted(provenance, "rentEstimateHigh")
        )
    }
}
