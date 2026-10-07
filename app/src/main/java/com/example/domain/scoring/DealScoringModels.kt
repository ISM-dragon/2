package com.example.domain.scoring

/**
 * Types for the standalone, deterministic Deal Scoring Engine.
 *
 * This package is intentionally free of Android, Room, network and AI dependencies.
 * All models are immutable (`val`-only data classes) so a produced score can never be
 * mutated after the fact -- in particular NOT by AI/LLM code paths. The only thing AI
 * may attach to a result are free-text observations ([DealScoreResult.aiObservations]),
 * which are display-only and never influence numbers.
 */

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

/**
 * Fact input for one deal evaluation.
 *
 * Every field is optional. Missing fields never raise scores: each subscore tracks a
 * data [coverage] (0..1) and unavailable components are attenuated toward the neutral
 * score instead of being treated as good or bad. How much a field matters is exactly
 * what the Data Confidence Score reports.
 *
 * Non-finite values (NaN / Infinity) are treated as "not provided" and reported as
 * warnings -- scoring output is always deterministic and within [0, 100].
 *
 * Typical wiring: compute financial metrics with `FinancialEngine.calculate(...)`
 * and copy them into this input; market / distress facts come from the property
 * and market data stores. Strings are normalized case-insensitively.
 */
data class DealInput(
    // -- Financial performance (usually from FinancialEngine) --
    val purchasePrice: Double? = null,
    val closingCosts: Double? = null,
    val renovationCost: Double? = null,
    val monthlyCashFlow: Double? = null,
    val annualNoi: Double? = null,
    val capRatePct: Double? = null,
    val cashOnCashPct: Double? = null,
    val dscr: Double? = null,
    val monthlyDebtService: Double? = null,
    val grossMonthlyRent: Double? = null,
    val vacancyRatePct: Double? = null,
    val interestRatePct: Double? = null,

    // -- Equity / value --
    val estimatedMarketValue: Double? = null,
    val afterRepairValue: Double? = null,
    val medianAreaPrice: Double? = null,

    // -- Market --
    val neighborhoodAppreciationPct: Double? = null,
    /** "High", "Moderate", "Balanced", "Buyer's Market" (case-insensitive). */
    val marketDemand: String? = null,
    val areaDaysOnMarket: Int? = null,
    val propertyPricePerSqFt: Double? = null,
    val areaPricePerSqFt: Double? = null,

    // -- Property / risk --
    val yearBuilt: Int? = null,
    val floodZone: Boolean? = null,

    // -- Distress signals --
    /** "FORECLOSURE", "WHOLESALE", "OFF_MARKET", "ON_MARKET", ... */
    val sourceType: String? = null,
    val listingDaysOnMarket: Int? = null,
    val cumulativePriceDropPct: Double? = null,
    val taxDelinquent: Boolean? = null,

    // -- Data quality hints (never speed up scores, only confidence) --
    val compsCount: Int? = null,
    val rentConfidenceScore: Double? = null, // 0..100 from the rent estimate provider
    val rentEstimateLow: Double? = null,
    val rentEstimateHigh: Double? = null
)

// ---------------------------------------------------------------------------
// Weights & configuration
// ---------------------------------------------------------------------------

/**
 * Fully configurable weighting for the scoring engine.
 *
 * Two levels of weights exist:
 *  1. Composite weights -- how much each subscore contributes to the final Deal Score.
 *     Any non-negative (finite) numbers work; they are auto-normalized. At least one
 *     participating weight must be > 0. Setting [dataConfidence] > 0 makes data
 *     confidence part of the composite (default 0 -> reported separately only).
 *  2. Component weights -- how much each underlying metric contributes inside its
 *     subscore. Also auto-normalized. Unknown component ids and zero-sum maps are
 *     rejected with [IllegalArgumentException], so typos can never silently change
 *     scoring behavior.
 *
 * The effective (normalized) weights that were actually used are echoed back inside
 * [DealScoreResult.weights] for full transparency.
 */
data class ScoringWeights(
    val cashFlow: Double = 0.35,
    val equity: Double = 0.25,
    val market: Double = 0.20,
    val riskSafety: Double = 0.20,
    /** Distress is an opportunity signal, not investment quality; excluded by default. */
    val distressOpportunity: Double = 0.0,
    /** 0.0 (default) keeps data confidence out of the composite. */
    val dataConfidence: Double = 0.0,
    /** subscore id -> (component id -> weight). Missing subscore = use its defaults. */
    val components: Map<String, Map<String, Double>> = DEFAULT_COMPONENT_WEIGHTS
) {
    init {
        val composite = listOf(cashFlow, equity, market, riskSafety, distressOpportunity, dataConfidence)
        require(composite.all { it.isFinite() && it >= 0.0 }) {
            "Composite weights must be finite numbers >= 0"
        }
        require(composite.any { it > 0.0 }) {
            "At least one composite weight must be > 0"
        }
        components.forEach { (subscoreId, map) ->
            require(subscoreId in DEFAULT_COMPONENT_WEIGHTS.keys) {
                "Unknown subscore id in component weights: '$subscoreId'"
            }
            require(map.isNotEmpty()) { "Component weight map for '$subscoreId' is empty" }
            map.forEach { (componentId, w) ->
                require(componentId in DEFAULT_COMPONENT_WEIGHTS.getValue(subscoreId).keys) {
                    "Unknown component id '$componentId' for subscore '$subscoreId'"
                }
                require(w.isFinite() && w >= 0.0) {
                    "Component weight for '$subscoreId/$componentId' must be finite and >= 0"
                }
            }
            require(map.values.any { it > 0.0 }) {
                "Component weights for '$subscoreId' must contain at least one value > 0"
            }
        }
    }

    companion object {
        const val CASH_FLOW = "CASH_FLOW"
        const val EQUITY = "EQUITY"
        const val MARKET = "MARKET"
        const val RISK_SAFETY = "RISK_SAFETY"
        const val DISTRESS = "DISTRESS"
        const val DATA_CONFIDENCE = "DATA_CONFIDENCE"

        /** Default within-subscore component weights (auto-normalized before use). */
        val DEFAULT_COMPONENT_WEIGHTS: Map<String, Map<String, Double>> = linkedMapOf(
            CASH_FLOW to linkedMapOf(
                "capRatePct" to 0.30,
                "cashOnCashPct" to 0.30,
                "monthlyCashFlow" to 0.20,
                "dscr" to 0.20
            ),
            EQUITY to linkedMapOf(
                "instantEquityPct" to 0.50,
                "arvSpreadPct" to 0.30,
                "priceVsMedianPct" to 0.20
            ),
            MARKET to linkedMapOf(
                "neighborhoodAppreciationPct" to 0.35,
                "marketDemand" to 0.25,
                "areaDaysOnMarket" to 0.20,
                "pricePerSqFtVsAreaPct" to 0.20
            ),
            RISK_SAFETY to linkedMapOf(
                "propertyAgeYears" to 0.20,
                "dscr" to 0.25,
                "vacancyRatePct" to 0.15,
                "renovationPctOfPrice" to 0.15,
                "interestRatePct" to 0.15,
                "floodZone" to 0.10
            ),
            DISTRESS to linkedMapOf(
                "sourceType" to 0.35,
                "listingDaysOnMarket" to 0.25,
                "cumulativePriceDropPct" to 0.20,
                "taxDelinquent" to 0.20
            )
        )
    }
}

/** One anchor in a piecewise-linear score rule: metric threshold -> score points. */
data class ScoreBandPoint(
    val threshold: Double,
    val score: Double
)

/** Deterministic engine knobs -- no clocks, no randomness, no environment access. */
data class ScoringConfig(
    /** Score that unknown data collapses to (fully uncovered subscore). */
    val neutralScore: Double = 50.0,
    /** How sharply missing coverage pulls a subscore toward neutral (>= 0). */
    val attenuationExponent: Double = 1.0,
    /** Subscores with coverage below this produce a warning. */
    val lowCoverageWarnBelow: Double = 0.5,
    /** Data confidence below this value produces a strong warning. */
    val lowConfidenceWarnBelow: Double = 40.0,
    /**
     * Complete replacement tables for selected numeric components. Omitted component ids use
     * the versioned defaults in [DealScoringEngine]. Each table must have strictly increasing
     * thresholds and monotone 0..100 scores; the exact anchors used are returned per component.
     */
    val numericBandOverrides: Map<String, List<ScoreBandPoint>> = emptyMap(),
    /** Overrides for normalized market-demand keys (e.g. "buyersmarket", "high"). */
    val marketDemandScoreOverrides: Map<String, Double> = emptyMap(),
    /** Overrides for normalized distress-source keys (e.g. "FORECLOSURE", "ON_MARKET"). */
    val distressSourceScoreOverrides: Map<String, Double> = emptyMap(),
    /** Score used for flood exposure; the non-flood score is separately configurable. */
    val floodZoneScore: Double = 30.0,
    val noFloodZoneScore: Double = 90.0,
    /** All-cash DSCR sentinel understood by the scoring adapter (FinancialEngine uses 999). */
    val allCashDscrSentinel: Double = 900.0,
    /** Highest plausible annual interest rate accepted as scored input. */
    val maxPlausibleInterestRatePct: Double = 30.0,
    /** Maximum positive/negative adjustment available from a 0..100 rent-provider confidence value. */
    val rentConfidenceAdjustmentRangePoints: Double = 20.0,
    /** Comparable-count score adjustments; the no-comps penalty applies only when value estimates exist. */
    val compsConfidenceNoCompsWithEstimatePoints: Double = -8.0,
    val compsConfidenceLimitedPoints: Double = 4.0,
    val compsConfidenceStrongPoints: Double = 8.0,
    val compsConfidenceLimitedThreshold: Int = 1,
    val compsConfidenceStrongThreshold: Int = 3,
    /** Penalty applied once per validation/consistency issue. */
    val consistencyPenaltyPerIssue: Double = 12.0,
    /** Special-case scores and reason polarity bounds. */
    val allCashCashFlowScore: Double = 100.0,
    val allCashRiskSafetyScore: Double = 95.0,
    val taxDelinquentScore: Double = 95.0,
    val noTaxDelinquentScore: Double = 8.0,
    val positiveReasonThreshold: Double = 70.0,
    val negativeReasonThreshold: Double = 40.0
) {
    init {
        require(neutralScore.isFinite() && neutralScore in 0.0..100.0) {
            "neutralScore must be within 0..100"
        }
        require(attenuationExponent.isFinite() && attenuationExponent >= 0.0) {
            "attenuationExponent must be finite and >= 0"
        }
        require(lowCoverageWarnBelow.isFinite() && lowCoverageWarnBelow in 0.0..1.0) {
            "lowCoverageWarnBelow must be within 0..1"
        }
        require(lowConfidenceWarnBelow.isFinite() && lowConfidenceWarnBelow in 0.0..100.0) {
            "lowConfidenceWarnBelow must be within 0..100"
        }
        require(floodZoneScore.isFinite() && floodZoneScore in 0.0..100.0) {
            "floodZoneScore must be within 0..100"
        }
        require(noFloodZoneScore.isFinite() && noFloodZoneScore in 0.0..100.0) {
            "noFloodZoneScore must be within 0..100"
        }
        require(allCashDscrSentinel.isFinite() && allCashDscrSentinel > 0.0) {
            "allCashDscrSentinel must be finite and > 0"
        }
        require(maxPlausibleInterestRatePct.isFinite() && maxPlausibleInterestRatePct > 0.0) {
            "maxPlausibleInterestRatePct must be finite and > 0"
        }
        require(rentConfidenceAdjustmentRangePoints.isFinite() && rentConfidenceAdjustmentRangePoints in 0.0..100.0) {
            "rentConfidenceAdjustmentRangePoints must be within 0..100"
        }
        require(listOf(
            compsConfidenceNoCompsWithEstimatePoints,
            compsConfidenceLimitedPoints,
            compsConfidenceStrongPoints
        ).all { it.isFinite() && it in -100.0..100.0 }) {
            "Comparable-sales confidence adjustments must be within -100..100"
        }
        require(compsConfidenceLimitedThreshold >= 1 && compsConfidenceStrongThreshold > compsConfidenceLimitedThreshold) {
            "Comparable count thresholds must be positive and strictly increasing"
        }
        require(consistencyPenaltyPerIssue.isFinite() && consistencyPenaltyPerIssue in 0.0..100.0) {
            "consistencyPenaltyPerIssue must be within 0..100"
        }
        require(listOf(
            allCashCashFlowScore,
            allCashRiskSafetyScore,
            taxDelinquentScore,
            noTaxDelinquentScore,
            positiveReasonThreshold,
            negativeReasonThreshold
        ).all { it.isFinite() && it in 0.0..100.0 }) {
            "Special-case scores and reason thresholds must be within 0..100"
        }
        require(positiveReasonThreshold > negativeReasonThreshold) {
            "positiveReasonThreshold must be > negativeReasonThreshold"
        }
        numericBandOverrides.forEach { (componentId, points) ->
            require(componentId in NUMERIC_BAND_COMPONENT_IDS) {
                "Unknown component id in numeric band overrides: '$componentId'"
            }
            require(points.size >= 2) { "Band override for '$componentId' needs at least two points" }
            require(points.all { it.threshold.isFinite() && it.score.isFinite() && it.score in 0.0..100.0 }) {
                "Band override for '$componentId' must contain finite thresholds and scores in 0..100"
            }
            require(points.zipWithNext().all { (left, right) -> left.threshold < right.threshold }) {
                "Band thresholds for '$componentId' must be strictly increasing"
            }
            val scoreDeltas = points.zipWithNext().map { (left, right) -> right.score - left.score }
            require(scoreDeltas.all { it >= 0.0 } || scoreDeltas.all { it <= 0.0 }) {
                "Band scores for '$componentId' must be monotone"
            }
        }
        marketDemandScoreOverrides.forEach { (key, score) ->
            require(key in MARKET_DEMAND_KEYS) { "Unknown normalized market-demand key: '$key'" }
            require(score.isFinite() && score in 0.0..100.0) {
                "Market-demand override '$key' must be within 0..100"
            }
        }
        distressSourceScoreOverrides.forEach { (key, score) ->
            require(key in DISTRESS_SOURCE_KEYS) { "Unknown normalized distress-source key: '$key'" }
            require(score.isFinite() && score in 0.0..100.0) {
                "Distress-source override '$key' must be within 0..100"
            }
        }
    }

    companion object {
        private val NUMERIC_BAND_COMPONENT_IDS = setOf(
            "capRatePct", "cashOnCashPct", "monthlyCashFlow", "dscr",
            "instantEquityPct", "arvSpreadPct", "priceVsMedianPct",
            "neighborhoodAppreciationPct", "areaDaysOnMarket", "pricePerSqFtVsAreaPct",
            "propertyAgeYears", "riskDscr", "vacancyRatePct", "renovationPctOfPrice", "interestRatePct",
            "listingDaysOnMarket", "cumulativePriceDropPct"
        )
        private val MARKET_DEMAND_KEYS = setOf(
            "high", "strong", "sellersmarket", "hot", "moderate", "medium", "warm",
            "balanced", "neutral", "stable", "buyersmarket", "low", "weak", "cold", "slow"
        )
        private val DISTRESS_SOURCE_KEYS = setOf(
            "FORECLOSURE", "BANK_OWNED", "REO", "SHORT_SALE", "AUCTION", "WHOLESALE",
            "OFF_MARKET", "OFFMARKET", "PRE_FORECLOSURE", "ON_MARKET", "ONMARKET", "MLS"
        )
    }
}

// ---------------------------------------------------------------------------
// Output
// ---------------------------------------------------------------------------

/** Polarity of an explanation line. */
enum class ReasonPolarity { POSITIVE, NEUTRAL, NEGATIVE }

/** One human-readable, machine-tagged explanation for a scoring decision. */
data class ScoreReason(
    val polarity: ReasonPolarity,
    val message: String,
    /** Id of the subscore that produced this reason, e.g. "CASH_FLOW". */
    val subscoreId: String
)

/** Shape of the explicit deterministic rule applied to one scoring component. */
enum class ScoreRuleKind {
    PIECEWISE_LINEAR,
    CATEGORY_LOOKUP,
    BOOLEAN_RULE,
    SPECIAL_CASE,
    COVERAGE_CALIBRATION,
    MISSING_DATA
}

/**
 * Machine-readable rule trace suitable for UI rendering or export. Thresholds are the exact
 * anchors used for interpolation (or the exact category/special-case key); no model narration
 * is needed to reconstruct why a component received its points.
 */
data class ScoreRuleTrace(
    val ruleId: String,
    val inputFields: List<String>,
    val kind: ScoreRuleKind,
    val lowerThreshold: Double? = null,
    val lowerScore: Double? = null,
    val upperThreshold: Double? = null,
    val upperScore: Double? = null,
    val interpolationFraction: Double? = null,
    val matchKey: String? = null
)

/** Explainable breakdown of a single metric inside a subscore. */
data class ComponentBreakdown(
    val componentId: String,
    /** Derived metric value actually scored (e.g. 20.0 for "20% instant equity"), null when missing. */
    val value: Double?,
    /** Metric mapped to 0..100, null when the metric is missing. */
    val score: Double?,
    /** Effective normalized weight of this component inside its subscore (0 when missing). */
    val weight: Double,
    /** True when the metric was available and scored. */
    val covered: Boolean,
    /** Exact explanation: value, threshold band used, interpolation. */
    val rationale: String,
    /** DealInput fields used directly or to derive [value]. */
    val inputFields: List<String> = emptyList(),
    /** Explicit rule and interpolation trace; null only for legacy external construction. */
    val ruleTrace: ScoreRuleTrace? = null
)

/** One subscore with its full explainable breakdown. */
data class SubScoreBreakdown(
    val id: String,
    val displayName: String,
    /** Score before data-coverage attenuation. */
    val rawScore: Double,
    /** Effective score after attenuation (what the composite uses). */
    val score: Double,
    /** Fraction (0..1) of this subscore's weighted components that had data. */
    val coverage: Double,
    /** Configured composite weight, as passed in. */
    val weight: Double,
    /** Normalized composite weight actually used (0 when not participating). */
    val normalizedWeight: Double,
    /** Points this subscore contributes to the final Deal Score. */
    val contribution: Double,
    /** Whether this subscore participates in the composite Deal Score. */
    val participatesInComposite: Boolean,
    val reasons: List<ScoreReason>,
    val components: List<ComponentBreakdown>
)

/** Aggregated positive / neutral / negative explanations for the whole evaluation. */
data class ReasonsSummary(
    val positive: List<String>,
    val neutral: List<String>,
    val negative: List<String>
)

/** Exact arithmetic inputs used to produce the Data Confidence Score. */
data class ConfidenceScoreBreakdown(
    val weightedCoverage: Double,
    val coveragePoints: Double,
    val rentConfidenceAdjustment: Double,
    val comparableSalesAdjustment: Double,
    val scoreBeforeConsistencyPenalty: Double,
    val consistencyPenalty: Double,
    val finalScore: Double
)

/** Effective weights used for this evaluation, echoed back for transparency. */
data class EffectiveWeights(
    /** Composite weights as configured (raw, before normalization). */
    val configuredComposite: Map<String, Double>,
    /** Composite weights after normalization; only participating subscores appear. */
    val normalizedComposite: Map<String, Double>,
    /** Effective normalized within-subscore component weights: subscore id -> (component -> weight). */
    val components: Map<String, Map<String, Double>>
)

/**
 * Final, immutable scoring result.
 *
 * Layout matches the required contract:
 *   score, subscores, weights, reasons, warnings, dataConfidence.
 */
data class DealScoreResult(
    /** Composite Deal Score, 0..100. */
    val score: Double,
    /** Letter grade derived deterministically from [score]: A >= 85, B >= 70, C >= 55, D >= 40, else F. */
    val grade: String,
    /** All six named subscores with full breakdowns (includes Data Confidence and Distress). */
    val subscores: List<SubScoreBreakdown>,
    val weights: EffectiveWeights,
    val reasons: ReasonsSummary,
    val warnings: List<String>,
    /** Data Confidence Score, 0..100. */
    val dataConfidence: Double,
    /** Overall data coverage fraction (0..1) behind the composite. */
    val coverage: Double,
    /** Input fields that were missing (informational; ids are [DealInput] property names). */
    val missingInputs: List<String>,
    /**
     * Display-only observations that AI/LLM layers may attach AFTER scoring.
     * These are never read by the engine and never influence any number --
     * see [DealScoringEngine.attachAiObservations].
     */
    val aiObservations: List<String> = emptyList(),
    /** Version of the deterministic threshold/calibration set used. */
    val scoringModelVersion: String = "deal-scoring-v2",
    /** Explicit reference year used for property-age scoring. */
    val asOfYear: Int = DealScoringEngine.DEFAULT_AS_OF_YEAR,
    /** Coverage-to-neutral settings used for all fact-driven subscores. */
    val neutralScore: Double = 50.0,
    val attenuationExponent: Double = 1.0,
    /** Structured calculation path behind [dataConfidence]. */
    val confidenceBreakdown: ConfidenceScoreBreakdown? = null
)
