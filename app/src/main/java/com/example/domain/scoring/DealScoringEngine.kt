package com.example.domain.scoring

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * Deal Scoring Engine -- standalone, deterministic, explainable.
 *
 * Produces for every deal:
 *  - Deal Score (composite 0..100)
 *  - Cash Flow Score, Equity Score, Market Score, Risk Score, Distress Score (0..100 each)
 *  - Data Confidence Score (0..100)
 *
 * Guarantees / policy:
 *  1. DETERMINISTIC -- pure function of (input, weights, config, asOfYear). No clocks,
 *     randomness, I/O, network or environment access. Same arguments => identical result.
 *  2. CONFIGURABLE -- every weight is caller-provided via [ScoringWeights]; the effective
 *     (normalized) weights are echoed back in the result.
 *  3. EXPLAINABLE -- every subscore carries a per-component breakdown (value, threshold
 *     band, mapped points) plus typed positive/negative/neutral reasons.
 *  4. AI ISOLATION -- AI/LLM layers can NEVER change a score. There is deliberately no
 *     API that accepts AI-produced numeric input or score overrides. AI text may only be
 *     attached as display-only observations via [attachAiObservations], which returns a
 *     copy with byte-identical numeric content. All result types are immutable.
 *
 * Scores share one direction: higher = better for Deal/CashFlow/Equity/Market, and the
 * RISK Score is a *safety* score (100 = lowest risk) so it composes consistently.
 * DISTRESS is an opportunity signal (100 = most distressed / most motivated seller).
 *
 * Missing data never inflates a score: uncovered components fall back to the neutral
 * score (default 50) proportionally to their missing weight, and the Data Confidence
 * Score plus warnings make the gap explicit.
 */
object DealScoringEngine {

    /** Deterministic default reference year -- no system clock is ever consulted. */
    const val DEFAULT_AS_OF_YEAR = 2026

    /** Stable identifier for the default thresholds and calibration rules. */
    const val MODEL_VERSION = "deal-scoring-v2"

    private const val SCORE_MIN = 0.0
    private const val SCORE_MAX = 100.0

    /** Raw subscore when none of its components has data (neutral fallback). */
    private const val NEUTRAL_FALLBACK = 50.0

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Scores a deal. Pure and deterministic: identical arguments always produce an
     * identical [DealScoreResult].
     *
     * @param asOfYear reference year used only for property-age math (deterministic
     * constant by default; callers may pass the current year but the engine never
     * reads a clock itself).
     */
    fun evaluate(
        input: DealInput,
        weights: ScoringWeights = ScoringWeights(),
        config: ScoringConfig = ScoringConfig(),
        asOfYear: Int = DEFAULT_AS_OF_YEAR
    ): DealScoreResult {
        // 1. Sanitize invalid numeric inputs without filling gaps with assumptions.
        val sanitized = sanitize(input, config)
        val s = sanitized.input

        // 2. Effective within-subscore component weights (stably normalized).
        val componentWeights = resolveComponentWeights(weights)

        // 3. Evaluate the five fact-driven subscores (raw 0..100 + coverage).
        val cashFlow = evalCashFlow(s, componentWeights.getValue(ScoringWeights.CASH_FLOW), config)
        val equity = evalEquity(s, componentWeights.getValue(ScoringWeights.EQUITY), config)
        val market = evalMarket(s, componentWeights.getValue(ScoringWeights.MARKET), config)
        val risk = evalRisk(s, asOfYear, componentWeights.getValue(ScoringWeights.RISK_SAFETY), config)
        val distress = evalDistress(s, componentWeights.getValue(ScoringWeights.DISTRESS), config)
        val factScores = linkedMapOf(
            ScoringWeights.CASH_FLOW to cashFlow,
            ScoringWeights.EQUITY to equity,
            ScoringWeights.MARKET to market,
            ScoringWeights.RISK_SAFETY to risk,
            ScoringWeights.DISTRESS to distress
        )

        // 4. Consistency / plausibility checks -> warnings + confidence penalties.
        val consistency = runConsistencyChecks(s, asOfYear, config, sanitized.validationIssues)

        // 5. Overall coverage & Data Confidence Score. Every adjustment is returned as a trace.
        val configuredComposite = linkedMapOf(
            ScoringWeights.CASH_FLOW to weights.cashFlow,
            ScoringWeights.EQUITY to weights.equity,
            ScoringWeights.MARKET to weights.market,
            ScoringWeights.RISK_SAFETY to weights.riskSafety,
            ScoringWeights.DISTRESS to weights.distressOpportunity,
            ScoringWeights.DATA_CONFIDENCE to weights.dataConfidence
        )
        val coverageOverall = overallCoverage(factScores, configuredComposite)
        val coveragePoints = SCORE_MAX * coverageOverall
        val rentAdjustment = s.rentConfidenceScore?.let {
            ((it / 100.0) - 0.5) * config.rentConfidenceAdjustmentRangePoints
        } ?: 0.0
        val estimatesPresent = s.estimatedMarketValue != null || s.afterRepairValue != null || s.medianAreaPrice != null
        val comparableAdjustment = s.compsCount?.let { comps ->
            when {
                comps >= config.compsConfidenceStrongThreshold -> config.compsConfidenceStrongPoints
                comps >= config.compsConfidenceLimitedThreshold -> config.compsConfidenceLimitedPoints
                estimatesPresent -> config.compsConfidenceNoCompsWithEstimatePoints
                else -> 0.0
            }
        } ?: 0.0
        val confidenceBeforePenalty = clamp(coveragePoints + rentAdjustment + comparableAdjustment)
        val dataConfidence = clamp(confidenceBeforePenalty - consistency.confidencePenalty).r2()
        val confidenceBreakdown = ConfidenceScoreBreakdown(
            weightedCoverage = coverageOverall.r2(),
            coveragePoints = coveragePoints.r2(),
            rentConfidenceAdjustment = rentAdjustment.r2(),
            comparableSalesAdjustment = comparableAdjustment.r2(),
            scoreBeforeConsistencyPenalty = confidenceBeforePenalty.r2(),
            consistencyPenalty = consistency.confidencePenalty.r2(),
            finalScore = dataConfidence
        )

        // 6. Attenuate raw subscores toward neutral based on their data coverage.
        val attenuated = factScores.mapValues { (_, raw) ->
            attenuate(raw.rawScore, raw.coverage, config).r2()
        }

        // 7. Composite = weighted mean over participating subscores (normalized weights).
        val participating = linkedMapOf<String, Double>()
        linkedMapOf(
            ScoringWeights.CASH_FLOW to weights.cashFlow,
            ScoringWeights.EQUITY to weights.equity,
            ScoringWeights.MARKET to weights.market,
            ScoringWeights.RISK_SAFETY to weights.riskSafety,
            ScoringWeights.DISTRESS to weights.distressOpportunity
        ).forEach { (id, w) -> if (w > 0.0) participating[id] = w }
        if (weights.dataConfidence > 0.0) {
            participating[ScoringWeights.DATA_CONFIDENCE] = weights.dataConfidence
        }
        val normalizedComposite = normalizeWeights(participating)

        val effectiveForComposite: Map<String, Double> = attenuated +
            mapOf(ScoringWeights.DATA_CONFIDENCE to dataConfidence)

        var composite = 0.0
        normalizedComposite.forEach { (id, nw) -> composite += effectiveForComposite.getValue(id) * nw }
        val score = clamp(composite).r2()

        // 8. Build explainable breakdowns in a fixed, deterministic order.
        val displayNames = linkedMapOf(
            ScoringWeights.CASH_FLOW to "Cash Flow Score",
            ScoringWeights.EQUITY to "Equity Score",
            ScoringWeights.MARKET to "Market Score",
            ScoringWeights.RISK_SAFETY to "Risk Score",
            ScoringWeights.DISTRESS to "Distress Score",
            ScoringWeights.DATA_CONFIDENCE to "Data Confidence Score"
        )
        val subscores = mutableListOf<SubScoreBreakdown>()
        for ((id, raw) in factScores) {
            val nw = normalizedComposite[id] ?: 0.0
            val eff = attenuated.getValue(id)
            subscores.add(
                SubScoreBreakdown(
                    id = id,
                    displayName = displayNames.getValue(id),
                    rawScore = raw.rawScore.r2(),
                    score = eff,
                    coverage = raw.coverage.r2(),
                    weight = configuredComposite.getValue(id),
                    normalizedWeight = nw.r2(),
                    contribution = (eff * nw).r2(),
                    participatesInComposite = nw > 0.0,
                    reasons = raw.reasons,
                    components = raw.components
                )
            )
        }
        // Data confidence is reported as a subscore entry too (breakdown of coverage/penalties).
        subscores.add(
            SubScoreBreakdown(
                id = ScoringWeights.DATA_CONFIDENCE,
                displayName = displayNames.getValue(ScoringWeights.DATA_CONFIDENCE),
                rawScore = dataConfidence,
                score = dataConfidence,
                coverage = coverageOverall.r2(),
                weight = weights.dataConfidence,
                normalizedWeight = (normalizedComposite[ScoringWeights.DATA_CONFIDENCE] ?: 0.0).r2(),
                contribution = ((normalizedComposite[ScoringWeights.DATA_CONFIDENCE] ?: 0.0) * dataConfidence).r2(),
                participatesInComposite = weights.dataConfidence > 0.0,
                reasons = confidenceReasons(s, coverageOverall, consistency.penaltyCount, config),
                components = factScores.map { (id, raw) ->
                    ComponentBreakdown(
                        componentId = id,
                        value = raw.coverage.r2(),
                        score = (raw.coverage * 100.0).r2(),
                        weight = coverageWeight(id, configuredComposite),
                        covered = raw.coverage > 0.0,
                        rationale = "${displayNames.getValue(id)} data coverage ${"%.0f".fmt(raw.coverage * 100.0)}%",
                        inputFields = raw.components.flatMap { it.inputFields }.distinct(),
                        ruleTrace = ScoreRuleTrace(
                            "dataConfidence.coverage.$id", raw.components.flatMap { it.inputFields }.distinct(),
                            ScoreRuleKind.COVERAGE_CALIBRATION, matchKey = "weighted coverage 0..1 -> 0..100 points"
                        )
                    )
                } + confidenceBoostComponents(s, consistency, config)
            )
        )

        // 9. Aggregate reasons (fixed order = subscore order, then component order).
        val allReasons = factScores.values.flatMap { it.reasons } +
            subscores.last().reasons
        val reasons = ReasonsSummary(
            positive = allReasons.filter { it.polarity == ReasonPolarity.POSITIVE }.map { it.message },
            neutral = allReasons.filter { it.polarity == ReasonPolarity.NEUTRAL }.map { it.message },
            negative = allReasons.filter { it.polarity == ReasonPolarity.NEGATIVE }.map { it.message }
        )

        // 10. Warnings in a deterministic order.
        val missing = factScores.values.flatMap { it.missingInputs }.distinct()
        val warnings = mutableListOf<String>()
        warnings += sanitized.nonFiniteWarnings
        for ((id, raw) in factScores) {
            if (raw.coverage < 1.0) {
                warnings += "${displayNames.getValue(id)}: missing inputs {${raw.missingInputs.joinToString(", ")}} -- uncovered weight ${"%.0f".fmt((1.0 - raw.coverage) * 100.0)}% moved to neutral"
            }
            if (raw.coverage < config.lowCoverageWarnBelow) {
                warnings += "${displayNames.getValue(id)} coverage is only ${"%.0f".fmt(raw.coverage * 100.0)}% -- treat this subscore with caution"
            }
        }
        warnings += consistency.warnings
        if (dataConfidence < config.lowConfidenceWarnBelow) {
            warnings += "Data Confidence ${"%.0f".fmt(dataConfidence)}% is below ${"%.0f".fmt(config.lowConfidenceWarnBelow)}% -- the Deal Score is mostly neutral due to missing/unreliable data"
        }

        return DealScoreResult(
            score = score,
            grade = gradeOf(score),
            subscores = subscores,
            weights = EffectiveWeights(
                configuredComposite = configuredComposite,
                normalizedComposite = normalizedComposite.mapValues { it.value.r2() },
                components = componentWeights.mapValues { (_, m) -> m.mapValues { it.value.r2() } }
            ),
            reasons = reasons,
            warnings = warnings,
            dataConfidence = dataConfidence,
            coverage = coverageOverall.r2(),
            missingInputs = missing,
            scoringModelVersion = MODEL_VERSION,
            asOfYear = asOfYear,
            neutralScore = config.neutralScore,
            attenuationExponent = config.attenuationExponent,
            confidenceBreakdown = confidenceBreakdown
        )
    }

    /**
     * Attaches AI-generated free-text observations to an already-computed result.
     *
     * AI ISOLATION GUARANTEE: this is the ONLY AI-facing API. It never touches numbers  -- 
     * the returned copy shares every numeric field, every breakdown and every warning
     * with the input result. Notes are sanitized (trimmed, blanks dropped, capped in
     * count and length) purely for display safety.
     */
    fun attachAiObservations(result: DealScoreResult, notes: List<String>): DealScoreResult {
        val sanitized = notes
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(20)
            .map { if (it.length > 500) it.substring(0, 497) + "..." else it }
        return result.copy(aiObservations = sanitized)
    }

    // =========================================================================
    // Deterministic band scoring (piecewise-linear threshold tables)
    // =========================================================================

    private data class BandScore(
        val score: Double,
        val explanation: String,
        val trace: ScoreRuleTrace
    )

    // Versioned defaults. Callers may replace any complete band table via ScoringConfig.
    private val DEFAULT_NUMERIC_BANDS: Map<String, List<ScoreBandPoint>> = linkedMapOf(
        "capRatePct" to bands(0.0 to 0.0, 2.0 to 15.0, 4.0 to 35.0, 6.0 to 58.0, 8.0 to 75.0, 10.0 to 88.0, 12.0 to 100.0),
        "cashOnCashPct" to bands(-10.0 to 0.0, -5.0 to 5.0, 0.0 to 12.0, 4.0 to 35.0, 8.0 to 58.0, 10.0 to 70.0, 12.0 to 82.0, 15.0 to 100.0),
        "monthlyCashFlow" to bands(-1000.0 to 0.0, 0.0 to 30.0, 100.0 to 42.0, 200.0 to 52.0, 300.0 to 62.0, 500.0 to 78.0, 800.0 to 90.0, 1200.0 to 100.0),
        "dscr" to bands(0.0 to 0.0, 0.8 to 8.0, 1.0 to 32.0, 1.1 to 48.0, 1.25 to 65.0, 1.5 to 80.0, 1.75 to 90.0, 2.0 to 100.0),
        "instantEquityPct" to bands(-20.0 to 0.0, -10.0 to 8.0, 0.0 to 28.0, 5.0 to 40.0, 10.0 to 52.0, 15.0 to 64.0, 20.0 to 74.0, 25.0 to 82.0, 30.0 to 90.0, 40.0 to 100.0),
        "arvSpreadPct" to bands(-15.0 to 0.0, -5.0 to 8.0, 0.0 to 22.0, 10.0 to 42.0, 20.0 to 64.0, 25.0 to 74.0, 30.0 to 84.0, 40.0 to 100.0),
        "priceVsMedianPct" to bands(-20.0 to 5.0, -10.0 to 20.0, 0.0 to 45.0, 10.0 to 65.0, 20.0 to 82.0, 30.0 to 100.0),
        "neighborhoodAppreciationPct" to bands(-5.0 to 0.0, -2.0 to 12.0, 0.0 to 30.0, 2.0 to 48.0, 4.0 to 64.0, 6.0 to 80.0, 9.0 to 100.0),
        "areaDaysOnMarket" to bands(10.0 to 100.0, 20.0 to 88.0, 30.0 to 80.0, 45.0 to 65.0, 60.0 to 50.0, 90.0 to 30.0, 120.0 to 10.0),
        "pricePerSqFtVsAreaPct" to bands(-25.0 to 100.0, -15.0 to 85.0, -5.0 to 70.0, 0.0 to 55.0, 10.0 to 35.0, 20.0 to 20.0, 35.0 to 5.0),
        "propertyAgeYears" to bands(0.0 to 95.0, 5.0 to 92.0, 15.0 to 85.0, 30.0 to 70.0, 50.0 to 55.0, 75.0 to 40.0, 100.0 to 28.0, 125.0 to 20.0),
        "riskDscr" to bands(0.0 to 0.0, 0.9 to 5.0, 1.0 to 22.0, 1.1 to 40.0, 1.2 to 52.0, 1.35 to 66.0, 1.5 to 78.0, 1.75 to 90.0, 2.0 to 95.0),
        "vacancyRatePct" to bands(2.0 to 95.0, 3.0 to 90.0, 5.0 to 75.0, 8.0 to 55.0, 12.0 to 35.0, 18.0 to 15.0),
        "renovationPctOfPrice" to bands(0.0 to 95.0, 5.0 to 80.0, 10.0 to 65.0, 20.0 to 45.0, 30.0 to 30.0, 50.0 to 15.0),
        "interestRatePct" to bands(3.0 to 97.0, 4.0 to 92.0, 5.0 to 82.0, 6.0 to 72.0, 7.0 to 58.0, 8.5 to 44.0, 10.0 to 30.0, 12.0 to 18.0),
        "listingDaysOnMarket" to bands(0.0 to 8.0, 7.0 to 15.0, 30.0 to 42.0, 60.0 to 62.0, 90.0 to 75.0, 120.0 to 85.0, 180.0 to 92.0, 365.0 to 100.0),
        "cumulativePriceDropPct" to bands(0.0 to 8.0, 3.0 to 30.0, 5.0 to 45.0, 10.0 to 65.0, 15.0 to 80.0, 20.0 to 90.0, 30.0 to 100.0)
    )

    private val DEFAULT_MARKET_DEMAND_SCORES = mapOf(
        "high" to 90.0, "strong" to 90.0, "sellersmarket" to 90.0, "hot" to 90.0,
        "moderate" to 70.0, "medium" to 70.0, "warm" to 70.0,
        "balanced" to 50.0, "neutral" to 50.0, "stable" to 50.0,
        "buyersmarket" to 30.0, "low" to 30.0, "weak" to 30.0, "cold" to 30.0, "slow" to 30.0
    )
    private val DEFAULT_DISTRESS_SOURCE_SCORES = mapOf(
        "FORECLOSURE" to 100.0, "BANK_OWNED" to 100.0, "REO" to 100.0, "SHORT_SALE" to 100.0,
        "AUCTION" to 95.0, "WHOLESALE" to 85.0,
        "OFF_MARKET" to 60.0, "OFFMARKET" to 60.0, "PRE_FORECLOSURE" to 60.0,
        "ON_MARKET" to 25.0, "ONMARKET" to 25.0, "MLS" to 25.0
    )

    /** Piecewise-linear map clamps to outer anchors and returns the exact applied rule. */
    private fun bandScore(
        x: Double,
        componentId: String,
        inputFields: List<String>,
        config: ScoringConfig
    ): BandScore {
        val anchors = config.numericBandOverrides[componentId] ?: DEFAULT_NUMERIC_BANDS.getValue(componentId)
        val ruleId = "${componentId}.piecewiseLinear"
        if (x <= anchors.first().threshold) {
            val anchor = anchors.first()
            return BandScore(
                anchor.score,
                "at/below ${num(anchor.threshold)} floor -> ${"%.1f".fmt(anchor.score)}",
                ScoreRuleTrace(ruleId, inputFields, ScoreRuleKind.PIECEWISE_LINEAR,
                    lowerThreshold = anchor.threshold, lowerScore = anchor.score)
            )
        }
        if (x >= anchors.last().threshold) {
            val anchor = anchors.last()
            return BandScore(
                anchor.score,
                "at/above ${num(anchor.threshold)} cap -> ${"%.1f".fmt(anchor.score)}",
                ScoreRuleTrace(ruleId, inputFields, ScoreRuleKind.PIECEWISE_LINEAR,
                    upperThreshold = anchor.threshold, upperScore = anchor.score)
            )
        }
        for (i in 1 until anchors.size) {
            val lower = anchors[i - 1]
            val upper = anchors[i]
            if (x <= upper.threshold) {
                val fraction = (x - lower.threshold) / (upper.threshold - lower.threshold)
                val points = lower.score + fraction * (upper.score - lower.score)
                return BandScore(
                    points,
                    "between ${num(lower.threshold)}->${"%.0f".fmt(lower.score)} pts and ${num(upper.threshold)}->${"%.0f".fmt(upper.score)} pts",
                    ScoreRuleTrace(
                        ruleId, inputFields, ScoreRuleKind.PIECEWISE_LINEAR,
                        lowerThreshold = lower.threshold, lowerScore = lower.score,
                        upperThreshold = upper.threshold, upperScore = upper.score,
                        interpolationFraction = fraction
                    )
                )
            }
        }
        error("Validated score band for '$componentId' did not contain $x")
    }

    private fun bands(vararg pairs: Pair<Double, Double>): List<ScoreBandPoint> =
        pairs.map { ScoreBandPoint(it.first, it.second) }

    // =========================================================================
    // Subscore evaluation
    // =========================================================================

    /** Internal accumulator for one subscore. */
    private class RawSubScore(
        val rawScore: Double,
        val coverage: Double,
        val reasons: List<ScoreReason>,
        val components: List<ComponentBreakdown>,
        val missingInputs: List<String>
    )

    private data class Eval(
        val componentId: String,
        val inputName: String,
        val value: Double?,
        val score: Double?,
        val rationale: String,
        val reason: ScoreReason?,
        val ruleTrace: ScoreRuleTrace? = null
    )

    private fun assemble(
        subscoreId: String,
        evals: List<Eval>,
        weights: Map<String, Double>,
        config: ScoringConfig
    ): RawSubScore {
        val totalW = evals.sumOf { weights.getValue(it.componentId) }
        var coveredW = 0.0
        var acc = 0.0
        val components = mutableListOf<ComponentBreakdown>()
        val reasons = mutableListOf<ScoreReason>()
        val missing = mutableListOf<String>()
        for (e in evals) {
            val w = weights.getValue(e.componentId)
            if (e.score != null && w > 0.0) {
                coveredW += w
                acc += e.score * w
            } else if (e.value == null) {
                e.inputName.split('|').forEach { if (it.isNotEmpty()) missing += it }
            }
            val fields = e.inputName.split('|').filter { it.isNotBlank() }
            components += ComponentBreakdown(
                componentId = e.componentId,
                value = e.value?.r2(),
                score = e.score?.r2(),
                weight = if (e.score != null) (w / totalW).r2() else 0.0,
                covered = e.score != null,
                rationale = e.rationale,
                inputFields = fields,
                ruleTrace = e.ruleTrace ?: ScoreRuleTrace(
                    ruleId = "$subscoreId.${e.componentId}.${if (e.score == null) "missing" else "fixed"}",
                    inputFields = fields,
                    kind = if (e.score == null) ScoreRuleKind.MISSING_DATA else ScoreRuleKind.SPECIAL_CASE
                )
            )
            e.reason?.let { reasons += it }
        }
        val coverage = if (totalW > 0.0) coveredW / totalW else 0.0
        val raw = if (coveredW > 0.0) clamp(acc / coveredW) else config.neutralScore
        return RawSubScore(raw, coverage.clamp01(), reasons, components, missing.distinct())
    }

    private fun reason(
        subscoreId: String,
        score: Double,
        config: ScoringConfig,
        positive: String,
        negative: String,
        neutral: String
    ): ScoreReason {
        val (polarity, msg) = when {
            score >= config.positiveReasonThreshold -> ReasonPolarity.POSITIVE to positive
            score <= config.negativeReasonThreshold -> ReasonPolarity.NEGATIVE to negative
            else -> ReasonPolarity.NEUTRAL to neutral
        }
        return ScoreReason(polarity, msg, subscoreId)
    }

    // -- Cash Flow Score ------------------------------------------------------

    private fun evalCashFlow(s: DealInput, w: Map<String, Double>, config: ScoringConfig): RawSubScore {
        val id = ScoringWeights.CASH_FLOW
        val evals = mutableListOf<Eval>()

        val capRate = s.capRatePct
        if (capRate != null) {
            val band = bandScore(capRate, "capRatePct", listOf("capRatePct"), config)
            val sc = band.score
            val how = band.explanation
            evals += Eval(
                "capRatePct", "capRatePct", capRate, sc,
                "Cap rate ${pct(capRate)} mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc, config,
                    "Cap rate ${pct(capRate)} is a strong yield (${"%.1f".fmt(sc)} pts)",
                    "Cap rate ${pct(capRate)} is weak (${"%.1f".fmt(sc)} pts)",
                    "Cap rate ${pct(capRate)} is average (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = band.trace
            )
        } else evals += Eval("capRatePct", "capRatePct", null, null, "capRatePct missing", null)

        val coc = s.cashOnCashPct
        if (coc != null) {
            val band = bandScore(coc, "cashOnCashPct", listOf("cashOnCashPct"), config)
            val sc = band.score
            val how = band.explanation
            evals += Eval(
                "cashOnCashPct", "cashOnCashPct", coc, sc,
                "Cash-on-cash return ${pct(coc)} mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc, config,
                    "Cash-on-cash return ${pct(coc)} beats typical targets (${"%.1f".fmt(sc)} pts)",
                    "Cash-on-cash return ${pct(coc)} is below investor targets (${"%.1f".fmt(sc)} pts)",
                    "Cash-on-cash return ${pct(coc)} is mediocre (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = band.trace
            )
        } else evals += Eval("cashOnCashPct", "cashOnCashPct", null, null, "cashOnCashPct missing", null)

        val cf = s.monthlyCashFlow
        if (cf != null) {
            val band = bandScore(cf, "monthlyCashFlow", listOf("monthlyCashFlow"), config)
            val sc = band.score
            val how = band.explanation
            evals += Eval(
                "monthlyCashFlow", "monthlyCashFlow", cf, sc,
                "Monthly cash flow ${money(cf)}/mo mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc, config,
                    "Monthly cash flow ${money(cf)}/mo is healthy (${"%.1f".fmt(sc)} pts)",
                    "Monthly cash flow ${money(cf)}/mo is negative or too thin (${"%.1f".fmt(sc)} pts)",
                    "Monthly cash flow ${money(cf)}/mo is borderline (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = band.trace
            )
        } else evals += Eval("monthlyCashFlow", "monthlyCashFlow", null, null, "monthlyCashFlow missing", null)

        val dscr = s.dscr
        if (dscr != null) {
            if (dscr >= config.allCashDscrSentinel) {
                val sc = config.allCashCashFlowScore
                evals += Eval(
                    "dscr", "dscr", dscr, sc,
                    "All-cash deal (DSCR sentinel ${"%.0f".fmt(dscr)}) -> ${"%.1f".fmt(sc)} pts -- no debt-service burden",
                    reason(id, sc, config,
                        "All-cash structure -- no debt-service burden (${"%.1f".fmt(sc)} pts)",
                        "All-cash special-case score is configured low (${"%.1f".fmt(sc)} pts)",
                        "All-cash structure (${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = ScoreRuleTrace(
                        "cashFlow.dscr.allCash", listOf("dscr"), ScoreRuleKind.SPECIAL_CASE,
                        lowerThreshold = config.allCashDscrSentinel, lowerScore = sc, matchKey = "ALL_CASH"
                    )
                )
            } else {
                val band = bandScore(dscr, "dscr", listOf("dscr"), config)
                val sc = band.score
                val how = band.explanation
                evals += Eval(
                    "dscr", "dscr", dscr, sc,
                    "DSCR ${"%.2f".fmt(dscr)} mapped to ${"%.1f".fmt(sc)} pts ($how)",
                    reason(id, sc, config,
                        "DSCR ${"%.2f".fmt(dscr)} covers debt comfortably (${"%.1f".fmt(sc)} pts)",
                        "DSCR ${"%.2f".fmt(dscr)} does not safely cover debt service (${"%.1f".fmt(sc)} pts)",
                        "DSCR ${"%.2f".fmt(dscr)} is borderline (${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = band.trace
                )
            }
        } else evals += Eval("dscr", "dscr", null, null, "dscr missing", null)

        return assemble(id, evals, w, config)
    }

    // -- Equity Score ----------------------------------------------------------

    private fun evalEquity(s: DealInput, w: Map<String, Double>, config: ScoringConfig): RawSubScore {
        val id = ScoringWeights.EQUITY
        val evals = mutableListOf<Eval>()

        val price = s.purchasePrice
        val marketValue = s.estimatedMarketValue
        if (price != null && marketValue != null) {
            val equityPct = (marketValue - price) / marketValue * 100.0
            if (equityPct.isFinite()) {
                val band = bandScore(equityPct, "instantEquityPct", listOf("estimatedMarketValue", "purchasePrice"), config)
                val sc = band.score
                evals += Eval(
                    "instantEquityPct", "estimatedMarketValue|purchasePrice", equityPct, sc,
                    "Instant equity ${pct(equityPct)} = (value ${money(marketValue)} - price ${money(price)}) / value; mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                    reason(id, sc, config,
                        "Buying ${pct(equityPct)} below market value -- strong instant equity (${"%.1f".fmt(sc)} pts)",
                        "Price leaves only ${pct(equityPct)} instant equity vs market value (${"%.1f".fmt(sc)} pts)",
                        "Instant equity of ${pct(equityPct)} vs market value is modest (${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = band.trace
                )
            } else {
                evals += Eval("instantEquityPct", "estimatedMarketValue|purchasePrice", null, null,
                    "instantEquityPct overflowed while deriving (value - price) / value; treated as unavailable", null)
            }
        } else {
            evals += Eval("instantEquityPct", "estimatedMarketValue|purchasePrice", null, null,
                "instantEquityPct needs positive estimatedMarketValue and purchasePrice", null)
        }

        val arv = s.afterRepairValue
        val renovation = s.renovationCost
        if (arv != null && price != null && renovation != null) {
            val purchasePlusRehab = price + renovation
            val spreadPct = (arv - purchasePlusRehab) / arv * 100.0
            if (purchasePlusRehab.isFinite() && spreadPct.isFinite()) {
                val band = bandScore(spreadPct, "arvSpreadPct",
                    listOf("afterRepairValue", "purchasePrice", "renovationCost"), config)
                val sc = band.score
                evals += Eval(
                    "arvSpreadPct", "afterRepairValue|purchasePrice|renovationCost", spreadPct, sc,
                    "ARV spread ${pct(spreadPct)} = (ARV ${money(arv)} - purchase price ${money(price)} - explicit rehab ${money(renovation)}) / ARV; closing costs excluded from this 70%-rule-style metric; mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                    reason(id, sc, config,
                        "ARV spread ${pct(spreadPct)} leaves a strong post-rehab value cushion (${"%.1f".fmt(sc)} pts)",
                        "ARV spread ${pct(spreadPct)} leaves no safety margin after rehab (${"%.1f".fmt(sc)} pts)",
                        "ARV spread ${pct(spreadPct)} is workable but thin (${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = band.trace
                )
            } else {
                evals += Eval("arvSpreadPct", "afterRepairValue|purchasePrice|renovationCost", null, null,
                    "arvSpreadPct overflowed while deriving (ARV - price - renovationCost) / ARV; treated as unavailable", null)
            }
        } else {
            evals += Eval("arvSpreadPct", "afterRepairValue|purchasePrice|renovationCost", null, null,
                "arvSpreadPct requires positive afterRepairValue, purchasePrice, and an explicit renovationCost; missing renovationCost is not assumed to be \$0", null)
        }

        val median = s.medianAreaPrice
        if (median != null && price != null) {
            val vsMedian = (median - price) / median * 100.0
            if (vsMedian.isFinite()) {
                val band = bandScore(vsMedian, "priceVsMedianPct", listOf("medianAreaPrice", "purchasePrice"), config)
                val sc = band.score
                evals += Eval(
                    "priceVsMedianPct", "medianAreaPrice|purchasePrice", vsMedian, sc,
                    "Price is ${pct(vsMedian)} vs area median ${money(median)}; mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                    reason(id, sc, config,
                        "Priced ${pct(abs(vsMedian))} below the area median (${"%.1f".fmt(sc)} pts)",
                        "Priced ${pct(abs(vsMedian))} above the area median (${"%.1f".fmt(sc)} pts)",
                        "Price sits near the area median (delta ${pct(vsMedian)}, ${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = band.trace
                )
            } else {
                evals += Eval("priceVsMedianPct", "medianAreaPrice|purchasePrice", null, null,
                    "priceVsMedianPct overflowed while deriving the median-price delta; treated as unavailable", null)
            }
        } else {
            evals += Eval("priceVsMedianPct", "medianAreaPrice|purchasePrice", null, null,
                "priceVsMedianPct needs positive medianAreaPrice and purchasePrice", null)
        }

        return assemble(id, evals, w, config)
    }

    // -- Market Score ----------------------------------------------------------

    private fun evalMarket(s: DealInput, w: Map<String, Double>, config: ScoringConfig): RawSubScore {
        val id = ScoringWeights.MARKET
        val evals = mutableListOf<Eval>()

        val appreciation = s.neighborhoodAppreciationPct
        if (appreciation != null) {
            val band = bandScore(appreciation, "neighborhoodAppreciationPct", listOf("neighborhoodAppreciationPct"), config)
            val sc = band.score
            evals += Eval(
                "neighborhoodAppreciationPct", "neighborhoodAppreciationPct", appreciation, sc,
                "Appreciation ${pct(appreciation)}/yr mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                reason(id, sc, config,
                    "Neighborhood appreciates ${pct(appreciation)}/yr -- strong tailwind (${"%.1f".fmt(sc)} pts)",
                    "Neighborhood appreciation ${pct(appreciation)}/yr is flat or declining (${"%.1f".fmt(sc)} pts)",
                    "Neighborhood appreciation ${pct(appreciation)}/yr is moderate (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = band.trace
            )
        } else evals += Eval("neighborhoodAppreciationPct", "neighborhoodAppreciationPct", null, null,
            "neighborhoodAppreciationPct missing", null)

        val demandScore = mapDemand(s.marketDemand, config)
        if (demandScore != null) {
            val sc = demandScore.score
            evals += Eval(
                "marketDemand", "marketDemand", sc, sc,
                "Market demand \"${demandScore.label}\" -> ${"%.0f".fmt(sc)} pts (configured category lookup)",
                reason(id, sc, config,
                    "Market demand is \"${demandScore.label}\" (${"%.0f".fmt(sc)} pts)",
                    "Market demand is \"${demandScore.label}\" -- slow exits (${"%.0f".fmt(sc)} pts)",
                    "Market demand is \"${demandScore.label}\" (${"%.0f".fmt(sc)} pts)"),
                ruleTrace = ScoreRuleTrace(
                    "market.marketDemand.lookup", listOf("marketDemand"), ScoreRuleKind.CATEGORY_LOOKUP,
                    matchKey = demandScore.key
                )
            )
        } else evals += Eval("marketDemand", "marketDemand", null, null,
            "marketDemand missing or unrecognized", null)

        val daysOnMarket = s.areaDaysOnMarket
        if (daysOnMarket != null) {
            val band = bandScore(daysOnMarket.toDouble(), "areaDaysOnMarket", listOf("areaDaysOnMarket"), config)
            val sc = band.score
            evals += Eval(
                "areaDaysOnMarket", "areaDaysOnMarket", daysOnMarket.toDouble(), sc,
                "Area average DOM $daysOnMarket mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                reason(id, sc, config,
                    "Area sells fast ($daysOnMarket days on market) (${"%.1f".fmt(sc)} pts)",
                    "Area is slow -- $daysOnMarket average days on market (${"%.1f".fmt(sc)} pts)",
                    "Area liquidity is average ($daysOnMarket DOM) (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = band.trace
            )
        } else evals += Eval("areaDaysOnMarket", "areaDaysOnMarket", null, null,
            "areaDaysOnMarket missing", null)

        val propertyPpsf = s.propertyPricePerSqFt
        val areaPpsf = s.areaPricePerSqFt
        if (propertyPpsf != null && areaPpsf != null) {
            // Positive delta = property is MORE expensive per sqft than the area norm.
            val deltaPct = (propertyPpsf - areaPpsf) / areaPpsf * 100.0
            if (deltaPct.isFinite()) {
                val band = bandScore(deltaPct, "pricePerSqFtVsAreaPct",
                    listOf("propertyPricePerSqFt", "areaPricePerSqFt"), config)
                val sc = band.score
                evals += Eval(
                    "pricePerSqFtVsAreaPct", "propertyPricePerSqFt|areaPricePerSqFt", deltaPct, sc,
                    "\$/sqft $${"%.0f".fmt(propertyPpsf)} vs area $${"%.0f".fmt(areaPpsf)} (delta ${pct(deltaPct)}); mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                    reason(id, sc, config,
                        "\$/sqft is ${pct(abs(deltaPct))} below the area norm -- value entry (${"%.1f".fmt(sc)} pts)",
                        "\$/sqft is ${pct(abs(deltaPct))} above the area norm (${"%.1f".fmt(sc)} pts)",
                        "\$/sqft is near the area norm (delta ${pct(deltaPct)}, ${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = band.trace
                )
            } else {
                evals += Eval("pricePerSqFtVsAreaPct", "propertyPricePerSqFt|areaPricePerSqFt", null, null,
                    "pricePerSqFtVsAreaPct overflowed while deriving the area delta; treated as unavailable", null)
            }
        } else evals += Eval("pricePerSqFtVsAreaPct", "propertyPricePerSqFt|areaPricePerSqFt", null, null,
            "pricePerSqFtVsAreaPct needs positive property and area $/sqft", null)

        return assemble(id, evals, w, config)
    }

    // -- Risk Score (safety direction: 100 = lowest risk) ----------------------

    private fun evalRisk(s: DealInput, asOfYear: Int, w: Map<String, Double>, config: ScoringConfig): RawSubScore {
        val id = ScoringWeights.RISK_SAFETY
        val evals = mutableListOf<Eval>()

        val yearBuilt = s.yearBuilt
        if (yearBuilt != null) {
            if (yearBuilt > asOfYear) {
                evals += Eval("propertyAgeYears", "yearBuilt", null, null,
                    "yearBuilt $yearBuilt is in the future (> $asOfYear) -- ignored", null)
            } else {
                val age = (asOfYear.toLong() - yearBuilt.toLong()).toDouble()
                val band = bandScore(age, "propertyAgeYears", listOf("yearBuilt", "asOfYear"), config)
                val sc = band.score
                evals += Eval(
                    "propertyAgeYears", "yearBuilt", age, sc,
                    "Property age ${"%.0f".fmt(age)} yrs (built $yearBuilt; reference year $asOfYear) mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                    reason(id, sc, config,
                        "Only ${"%.0f".fmt(age)} years old -- low deferred-maintenance risk (${"%.1f".fmt(sc)} pts)",
                        "Built $yearBuilt (${"%.0f".fmt(age)} yrs old) -- elevated maintenance/obsolete-systems risk (${"%.1f".fmt(sc)} pts)",
                        "Age ${"%.0f".fmt(age)} yrs is mid-range (${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = band.trace
                )
            }
        } else evals += Eval("propertyAgeYears", "yearBuilt", null, null, "yearBuilt missing", null)

        val dscr = s.dscr
        if (dscr != null) {
            if (dscr >= config.allCashDscrSentinel) {
                val sc = config.allCashRiskSafetyScore
                evals += Eval(
                    "dscr", "dscr", dscr, sc,
                    "All-cash deal -> ${"%.1f".fmt(sc)} pts -- no foreclosure/refinancing risk",
                    reason(id, sc, config,
                        "All-cash structure removes financing risk (${"%.1f".fmt(sc)} pts)",
                        "All-cash special-case score is configured low (${"%.1f".fmt(sc)} pts)",
                        "All-cash structure (${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = ScoreRuleTrace(
                        "risk.dscr.allCash", listOf("dscr"), ScoreRuleKind.SPECIAL_CASE,
                        lowerThreshold = config.allCashDscrSentinel, lowerScore = sc, matchKey = "ALL_CASH"
                    )
                )
            } else {
                val band = bandScore(dscr, "riskDscr", listOf("dscr"), config)
                val sc = band.score
                evals += Eval(
                    "dscr", "dscr", dscr, sc,
                    "DSCR ${"%.2f".fmt(dscr)} mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                    reason(id, sc, config,
                        "DSCR ${"%.2f".fmt(dscr)} gives a wide payment cushion (${"%.1f".fmt(sc)} pts)",
                        "DSCR ${"%.2f".fmt(dscr)} -- thin margin; small rent dips cause default risk (${"%.1f".fmt(sc)} pts)",
                        "DSCR ${"%.2f".fmt(dscr)} is an average cushion (${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = band.trace
                )
            }
        } else evals += Eval("dscr", "dscr", null, null, "dscr missing", null)

        val vacancy = s.vacancyRatePct
        if (vacancy != null) {
            val band = bandScore(vacancy, "vacancyRatePct", listOf("vacancyRatePct"), config)
            val sc = band.score
            evals += Eval(
                "vacancyRatePct", "vacancyRatePct", vacancy, sc,
                "Vacancy ${pct(vacancy)} mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                reason(id, sc, config,
                    "Low vacancy assumption/area (${pct(vacancy)}) (${"%.1f".fmt(sc)} pts)",
                    "High vacancy exposure (${pct(vacancy)}) (${"%.1f".fmt(sc)} pts)",
                    "Vacancy ${pct(vacancy)} is typical (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = band.trace
            )
        } else evals += Eval("vacancyRatePct", "vacancyRatePct", null, null, "vacancyRatePct missing", null)

        val price = s.purchasePrice
        val renovation = s.renovationCost
        if (renovation != null && price != null) {
            val renovationPct = renovation / price * 100.0
            if (renovationPct.isFinite()) {
                val band = bandScore(renovationPct, "renovationPctOfPrice",
                    listOf("renovationCost", "purchasePrice"), config)
                val sc = band.score
                evals += Eval(
                    "renovationPctOfPrice", "renovationCost|purchasePrice", renovationPct, sc,
                    "Renovation is ${pct(renovationPct)} of price (${money(renovation)} / ${money(price)}); mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                    reason(id, sc, config,
                        "Light rehab (${pct(renovationPct)} of price) -- low execution risk (${"%.1f".fmt(sc)} pts)",
                        "Heavy rehab (${pct(renovationPct)} of price) -- high execution/budget risk (${"%.1f".fmt(sc)} pts)",
                        "Moderate rehab (${pct(renovationPct)} of price) (${"%.1f".fmt(sc)} pts)"),
                    ruleTrace = band.trace
                )
            } else {
                evals += Eval("renovationPctOfPrice", "renovationCost|purchasePrice", null, null,
                    "renovationPctOfPrice overflowed while deriving renovationCost / purchasePrice; treated as unavailable", null)
            }
        } else evals += Eval("renovationPctOfPrice", "renovationCost|purchasePrice", null, null,
            "renovationPctOfPrice needs explicit renovationCost and positive purchasePrice", null)

        val rate = s.interestRatePct
        if (rate != null) {
            val band = bandScore(rate, "interestRatePct", listOf("interestRatePct"), config)
            val sc = band.score
            evals += Eval(
                "interestRatePct", "interestRatePct", rate, sc,
                "Interest rate ${pct(rate)} mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                reason(id, sc, config,
                    "Interest rate ${pct(rate)} is favorable (${"%.1f".fmt(sc)} pts)",
                    "Interest rate ${pct(rate)} is expensive / refinancing risk (${"%.1f".fmt(sc)} pts)",
                    "Interest rate ${pct(rate)} is average (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = band.trace
            )
        } else evals += Eval("interestRatePct", "interestRatePct", null, null,
            "interestRatePct missing or implausible", null)

        val flood = s.floodZone
        if (flood != null) {
            val sc = if (flood) config.floodZoneScore else config.noFloodZoneScore
            evals += Eval(
                "floodZone", "floodZone", if (flood) 1.0 else 0.0, sc,
                if (flood) "Flood zone -> ${"%.0f".fmt(sc)} pts (insurance/severity exposure)"
                else "Not in a flood zone -> ${"%.0f".fmt(sc)} pts",
                reason(id, sc, config,
                    "Not in a flood zone (${"%.0f".fmt(sc)} pts)",
                    "Property is in a flood zone -- insurance and severity exposure (${"%.0f".fmt(sc)} pts)",
                    "Flood status noted (${"%.0f".fmt(sc)} pts)"),
                ruleTrace = ScoreRuleTrace(
                    "risk.floodZone.boolean", listOf("floodZone"), ScoreRuleKind.BOOLEAN_RULE,
                    matchKey = if (flood) "IN_FLOOD_ZONE" else "NOT_IN_FLOOD_ZONE"
                )
            )
        } else evals += Eval("floodZone", "floodZone", null, null, "floodZone missing", null)

        return assemble(id, evals, w, config)
    }

    // -- Distress Score (100 = most distressed / motivated seller) --------------

    private fun evalDistress(s: DealInput, w: Map<String, Double>, config: ScoringConfig): RawSubScore {
        val id = ScoringWeights.DISTRESS
        val evals = mutableListOf<Eval>()

        val source = mapSourceType(s.sourceType, config)
        if (source != null) {
            val sc = source.score
            evals += Eval(
                "sourceType", "sourceType", sc, sc,
                "Source type \"${source.label}\" -> ${"%.0f".fmt(sc)} pts (configured motivation lookup)",
                reason(id, sc, config,
                    "Highly motivated seller channel: ${source.label} (${"%.0f".fmt(sc)} pts)",
                    "Low-motivation channel: ${source.label} (${"%.0f".fmt(sc)} pts)",
                    "Moderate-motivation channel: ${source.label} (${"%.0f".fmt(sc)} pts)"),
                ruleTrace = ScoreRuleTrace(
                    "distress.sourceType.lookup", listOf("sourceType"), ScoreRuleKind.CATEGORY_LOOKUP,
                    matchKey = source.key
                )
            )
        } else evals += Eval("sourceType", "sourceType", null, null, "sourceType missing or unrecognized", null)

        val daysOnMarket = s.listingDaysOnMarket
        if (daysOnMarket != null) {
            val band = bandScore(daysOnMarket.toDouble(), "listingDaysOnMarket", listOf("listingDaysOnMarket"), config)
            val sc = band.score
            evals += Eval(
                "listingDaysOnMarket", "listingDaysOnMarket", daysOnMarket.toDouble(), sc,
                "Listing sat $daysOnMarket days on market; mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                reason(id, sc, config,
                    "Stale listing ($daysOnMarket days) -- seller likely flexible (${"%.1f".fmt(sc)} pts)",
                    "Fresh listing ($daysOnMarket days) -- little motivation signal (${"%.1f".fmt(sc)} pts)",
                    "Listing age $daysOnMarket days -- some motivation signal (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = band.trace
            )
        } else evals += Eval("listingDaysOnMarket", "listingDaysOnMarket", null, null,
            "listingDaysOnMarket missing", null)

        val priceDrop = s.cumulativePriceDropPct
        if (priceDrop != null) {
            val band = bandScore(priceDrop, "cumulativePriceDropPct", listOf("cumulativePriceDropPct"), config)
            val sc = band.score
            evals += Eval(
                "cumulativePriceDropPct", "cumulativePriceDropPct", priceDrop, sc,
                "Cumulative price reduction ${pct(priceDrop)}; mapped to ${"%.1f".fmt(sc)} pts (${band.explanation})",
                reason(id, sc, config,
                    "Price already cut ${pct(priceDrop)} -- active motivation (${"%.1f".fmt(sc)} pts)",
                    "No meaningful price cuts (${pct(priceDrop)}) (${"%.1f".fmt(sc)} pts)",
                    "Price cut ${pct(priceDrop)} -- some motivation (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = band.trace
            )
        } else evals += Eval("cumulativePriceDropPct", "cumulativePriceDropPct", null, null,
            "cumulativePriceDropPct missing", null)

        val delinquent = s.taxDelinquent
        if (delinquent != null) {
            val sc = if (delinquent) config.taxDelinquentScore else config.noTaxDelinquentScore
            evals += Eval(
                "taxDelinquent", "taxDelinquent", if (delinquent) 1.0 else 0.0, sc,
                if (delinquent) "Property taxes delinquent -> ${"%.1f".fmt(sc)} pts"
                else "No tax delinquency -> ${"%.1f".fmt(sc)} pts",
                reason(id, sc, config,
                    "Tax delinquency recorded -- owner under financial pressure (${"%.1f".fmt(sc)} pts)",
                    "No tax delinquency signal (${"%.1f".fmt(sc)} pts)",
                    "Tax status noted (${"%.1f".fmt(sc)} pts)"),
                ruleTrace = ScoreRuleTrace(
                    "distress.taxDelinquent.boolean", listOf("taxDelinquent"), ScoreRuleKind.BOOLEAN_RULE,
                    matchKey = if (delinquent) "DELINQUENT" else "NOT_DELINQUENT",
                    lowerScore = sc
                )
            )
        } else evals += Eval("taxDelinquent", "taxDelinquent", null, null, "taxDelinquent missing", null)

        return assemble(id, evals, w, config)
    }

    // =========================================================================
    // Coverage, consistency & confidence
    // =========================================================================

    private data class SanitizedInput(
        val input: DealInput,
        val nonFiniteWarnings: List<String>,
        val validationIssues: List<String>
    )

    private data class ConsistencyResult(
        val warnings: List<String>,
        val confidencePenalty: Double,
        val penaltyCount: Int
    )

    private fun runConsistencyChecks(
        s: DealInput,
        asOfYear: Int,
        config: ScoringConfig,
        validationIssues: List<String>
    ): ConsistencyResult {
        val warnings = mutableListOf<String>()
        var penalty = 0.0
        var flagCount = 0

        fun flag(msg: String) {
            warnings += msg
            penalty += config.consistencyPenaltyPerIssue
            flagCount += 1
        }

        /** Warning without confidence impact (impact already lives elsewhere). */
        fun notice(msg: String) {
            warnings += msg
        }

        validationIssues.forEach { issue -> flag(issue) }

        val price = s.purchasePrice
        val noi = s.annualNoi
        val cap = s.capRatePct
        if (price != null && noi != null && cap != null) {
            val impliedCap = noi / price * 100.0
            if (!impliedCap.isFinite()) {
                flag("Inconsistent data: annualNoi / purchasePrice overflowed while checking capRatePct")
            } else if (abs(impliedCap - cap) > 1.5) {
                flag("Inconsistent data: capRatePct ${pct(cap)} vs implied NOI/price cap rate ${pct(impliedCap)}")
            }
        }

        val dscr = s.dscr
        val debtService = s.monthlyDebtService
        val cashFlow = s.monthlyCashFlow
        if (dscr != null && dscr < config.allCashDscrSentinel && debtService != null && debtService > 0.0 && cashFlow != null) {
            val impliedCashFlow = (dscr - 1.0) * debtService
            val tolerance = maxOf(150.0, 0.3 * abs(cashFlow))
            if (!impliedCashFlow.isFinite()) {
                flag("Inconsistent data: DSCR-implied monthly cash flow overflowed")
            } else if (abs(impliedCashFlow - cashFlow) > tolerance) {
                flag("Inconsistent data: monthlyCashFlow ${money(cashFlow)}/mo vs DSCR-implied ${money(impliedCashFlow)}/mo")
            }
        }

        val marketValue = s.estimatedMarketValue
        if (marketValue != null && price != null) {
            val ratio = marketValue / price
            if (!ratio.isFinite() || ratio > 3.0 || ratio < 0.33) {
                flag("Implausible spread: market value ${money(marketValue)} is ${"%.1f".fmt(ratio)}x the purchase price ${money(price)}")
            }
        }

        val rentLow = s.rentEstimateLow
        val rentHigh = s.rentEstimateHigh
        if (rentLow != null && rentHigh != null) {
            val midpoint = rentLow / 2.0 + rentHigh / 2.0
            val widthFraction = (rentHigh - rentLow) / midpoint
            if (!widthFraction.isFinite()) {
                flag("Rent estimate range width overflowed -- low rent certainty")
            } else if (widthFraction > 0.5) {
                flag("Rent estimate range is wide (${money(rentLow)}-${money(rentHigh)}, ${pct(widthFraction * 100.0)} of midpoint) -- low rent certainty")
            }
        }

        val rent = s.grossMonthlyRent
        if (rent != null && cashFlow != null && cashFlow - rent > 1.0) {
            flag("Implausible: monthlyCashFlow ${money(cashFlow)}/mo exceeds gross rent ${money(rent)}/mo")
        }

        s.yearBuilt?.let { if (it > asOfYear) flag("yearBuilt $it is after reference year $asOfYear -- age ignored") }

        // Warning-only signals (their confidence impact is handled by the boost math, do not double-penalize).
        val estimatesPresent = s.estimatedMarketValue != null || s.afterRepairValue != null || s.medianAreaPrice != null
        if (s.compsCount == 0 && estimatesPresent) {
            notice("0 comparable sales -- value estimate is weakly supported")
        }

        return ConsistencyResult(warnings, penalty, flagCount)
    }

    private fun confidenceReasons(
        s: DealInput,
        coverage: Double,
        penaltyCount: Int,
        config: ScoringConfig
    ): List<ScoreReason> {
        val id = ScoringWeights.DATA_CONFIDENCE
        val out = mutableListOf<ScoreReason>()
        out += ScoreReason(
            if (coverage >= 0.8) ReasonPolarity.POSITIVE else if (coverage >= 0.4) ReasonPolarity.NEUTRAL else ReasonPolarity.NEGATIVE,
            "Input coverage is ${"%.0f".fmt(coverage * 100.0)}% of weighted components",
            id
        )
        s.rentConfidenceScore?.let {
            out += ScoreReason(
                if (it >= 70.0) ReasonPolarity.POSITIVE else if (it >= 40.0) ReasonPolarity.NEUTRAL else ReasonPolarity.NEGATIVE,
                "Rent estimate provider confidence ${"%.0f".fmt(it)}%",
                id
            )
        }
        s.compsCount?.let {
            out += ScoreReason(
                if (it >= config.compsConfidenceStrongThreshold) ReasonPolarity.POSITIVE else ReasonPolarity.NEGATIVE,
                if (it >= config.compsConfidenceStrongThreshold) "$it comparable sales support the value estimate" else "Only $it comparable sales -- value estimate is weakly supported",
                id
            )
        }
        if (penaltyCount > 0) {
            out += ScoreReason(ReasonPolarity.NEGATIVE, "$penaltyCount consistency/plausibility issue(s) detected in inputs", id)
        }
        return out
    }

    private fun confidenceBoostComponents(
        s: DealInput,
        consistency: ConsistencyResult,
        config: ScoringConfig
    ): List<ComponentBreakdown> {
        val out = mutableListOf<ComponentBreakdown>()
        val estimatesPresent = s.estimatedMarketValue != null || s.afterRepairValue != null || s.medianAreaPrice != null
        s.rentConfidenceScore?.let { confidence ->
            val adjustment = ((confidence / 100.0) - 0.5) * config.rentConfidenceAdjustmentRangePoints
            out += ComponentBreakdown(
                componentId = "rentConfidenceScore",
                value = confidence.r2(),
                score = confidence.r2(),
                weight = 0.0,
                covered = true,
                rationale = "Provider rent confidence ${"%.0f".fmt(confidence)}% -> ${if (adjustment >= 0) "+" else ""}${"%.1f".fmt(adjustment)} confidence pts",
                inputFields = listOf("rentConfidenceScore"),
                ruleTrace = ScoreRuleTrace(
                    "dataConfidence.rentProviderAdjustment", listOf("rentConfidenceScore"),
                    ScoreRuleKind.COVERAGE_CALIBRATION,
                    matchKey = "(confidence / 100 - 0.5) * ${num(config.rentConfidenceAdjustmentRangePoints)} points"
                )
            )
        }
        s.compsCount?.let { count ->
            val adjustment = when {
                count >= config.compsConfidenceStrongThreshold -> config.compsConfidenceStrongPoints
                count >= config.compsConfidenceLimitedThreshold -> config.compsConfidenceLimitedPoints
                estimatesPresent -> config.compsConfidenceNoCompsWithEstimatePoints
                else -> 0.0
            }
            out += ComponentBreakdown(
                componentId = "compsCount",
                value = count.toDouble(),
                score = null,
                weight = 0.0,
                covered = true,
                rationale = "$count comps -> ${if (adjustment >= 0) "+" else ""}${"%.0f".fmt(adjustment)} confidence pts",
                inputFields = listOf("compsCount"),
                ruleTrace = ScoreRuleTrace(
                    "dataConfidence.comparableCountAdjustment", listOf("compsCount"),
                    ScoreRuleKind.COVERAGE_CALIBRATION,
                    matchKey = "count >= ${config.compsConfidenceStrongThreshold}: ${num(config.compsConfidenceStrongPoints)}; " +
                        "count >= ${config.compsConfidenceLimitedThreshold}: ${num(config.compsConfidenceLimitedPoints)}; " +
                        "no comps with value estimate: ${num(config.compsConfidenceNoCompsWithEstimatePoints)}; otherwise: 0"
                )
            )
        }
        if (consistency.confidencePenalty > 0.0) {
            out += ComponentBreakdown(
                componentId = "consistencyPenalty",
                value = consistency.penaltyCount.toDouble(),
                score = null,
                weight = 0.0,
                covered = true,
                rationale = "${consistency.penaltyCount} consistency issue(s) -> -${"%.0f".fmt(consistency.confidencePenalty)} confidence pts",
                inputFields = emptyList(),
                ruleTrace = ScoreRuleTrace(
                    "dataConfidence.consistencyPenalty", emptyList(), ScoreRuleKind.COVERAGE_CALIBRATION,
                    matchKey = "${num(config.consistencyPenaltyPerIssue)} points per validation/consistency issue"
                )
            )
        }
        return out
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun resolveComponentWeights(weights: ScoringWeights): Map<String, Map<String, Double>> {
        val out = linkedMapOf<String, Map<String, Double>>()
        for ((subId, defaults) in ScoringWeights.DEFAULT_COMPONENT_WEIGHTS) {
            val provided = weights.components[subId] ?: defaults
            // Components omitted by the caller contribute their default weight.
            val merged = linkedMapOf<String, Double>()
            for ((compId, defaultW) in defaults) {
                merged[compId] = provided[compId] ?: defaultW
            }
            out[subId] = normalizeWeights(merged)
        }
        return out
    }

    private fun overallCoverage(
        factScores: Map<String, RawSubScore>,
        configuredComposite: Map<String, Double>
    ): Double {
        val factWeights = linkedMapOf<String, Double>()
        factScores.keys.forEach { id ->
            configuredComposite.getValue(id).takeIf { it > 0.0 }?.let { factWeights[id] = it }
        }
        val normalized = normalizeWeights(factWeights)
        if (normalized.isEmpty()) {
            // If Data Confidence is the only configured composite dimension, report the
            // unweighted mean of the fact-score coverages rather than claiming 0% by default.
            return factScores.values.map { it.coverage }.average().clamp01()
        }
        return normalized.entries.sumOf { (id, weight) -> weight * factScores.getValue(id).coverage }.clamp01()
    }

    private fun coverageWeight(id: String, configuredComposite: Map<String, Double>): Double {
        val factWeights = linkedMapOf<String, Double>()
        listOf(ScoringWeights.CASH_FLOW, ScoringWeights.EQUITY, ScoringWeights.MARKET,
            ScoringWeights.RISK_SAFETY, ScoringWeights.DISTRESS).forEach { scoreId ->
            configuredComposite.getValue(scoreId).takeIf { it > 0.0 }?.let { factWeights[scoreId] = it }
        }
        val normalized = normalizeWeights(factWeights)
        return normalized[id] ?: if (normalized.isEmpty()) 1.0 / 5.0 else 0.0
    }

    /** Scale before summing so any finite non-negative weights normalize without overflow. */
    private fun normalizeWeights(weights: Map<String, Double>): LinkedHashMap<String, Double> {
        val out = linkedMapOf<String, Double>()
        if (weights.isEmpty()) return out
        val maxWeight = weights.values.maxOrNull() ?: 0.0
        if (maxWeight <= 0.0) {
            weights.keys.forEach { out[it] = 0.0 }
            return out
        }
        val scaled = weights.mapValues { it.value / maxWeight }
        val total = scaled.values.sum()
        scaled.forEach { (id, value) -> out[id] = value / total }
        return out
    }

    private fun attenuate(rawScore: Double, coverage: Double, config: ScoringConfig): Double {
        val c = coverage.clamp01()
        if (c >= 1.0) return clamp(rawScore)
        if (c <= 0.0) return config.neutralScore
        return clamp(config.neutralScore + (rawScore - config.neutralScore) * c.pow(config.attenuationExponent))
    }

    private fun gradeOf(score: Double): String = when {
        score >= 85.0 -> "A"
        score >= 70.0 -> "B"
        score >= 55.0 -> "C"
        score >= 40.0 -> "D"
        else -> "F"
    }

    private data class CategoryScore(val score: Double, val label: String, val key: String)

    private fun mapDemand(raw: String?, config: ScoringConfig): CategoryScore? {
        val key = raw?.trim()?.lowercase(Locale.US)?.replace(Regex("[^a-z]"), "") ?: return null
        val score = config.marketDemandScoreOverrides[key] ?: DEFAULT_MARKET_DEMAND_SCORES[key] ?: return null
        return CategoryScore(score, raw.trim(), key)
    }

    private fun mapSourceType(raw: String?, config: ScoringConfig): CategoryScore? {
        val key = raw?.trim()?.uppercase(Locale.US) ?: return null
        val score = config.distressSourceScoreOverrides[key] ?: DEFAULT_DISTRESS_SOURCE_SCORES[key] ?: return null
        return CategoryScore(score, raw.trim(), key)
    }

    // -- Sanitization -----------------------------------------------------------

    private fun sanitize(input: DealInput, config: ScoringConfig): SanitizedInput {
        val nonFiniteFields = mutableListOf<String>()
        val validationIssues = mutableListOf<String>()

        fun Double?.checked(name: String, isValid: (Double) -> Boolean = { true }): Double? {
            val value = this ?: return null
            if (!value.isFinite()) {
                nonFiniteFields += name
                return null
            }
            if (!isValid(value)) {
                validationIssues += "Invalid input $name=$value: outside the accepted range; treated as missing"
                return null
            }
            return value
        }

        fun Int?.checkedInt(name: String, isValid: (Int) -> Boolean): Int? {
            val value = this ?: return null
            if (!isValid(value)) {
                validationIssues += "Invalid input $name=$value: outside the accepted range; treated as missing"
                return null
            }
            return value
        }

        var low = input.rentEstimateLow.checked("rentEstimateLow") { it > 0.0 }
        var high = input.rentEstimateHigh.checked("rentEstimateHigh") { it > 0.0 }
        if (low != null && high != null && low > high) {
            validationIssues += "Conflicting inputs rentEstimateLow=${money(low)} exceeds rentEstimateHigh=${money(high)}; both range values treated as missing"
            low = null
            high = null
        } else if ((input.rentEstimateLow != null || input.rentEstimateHigh != null) && (low == null || high == null)) {
            validationIssues += "Incomplete rent estimate range: both rentEstimateLow and rentEstimateHigh are required to assess range reliability"
        }

        val safeYearBuilt = input.yearBuilt.checkedInt("yearBuilt") { it >= 1600 }
        val sanitized = input.copy(
            purchasePrice = input.purchasePrice.checked("purchasePrice") { it > 0.0 },
            closingCosts = input.closingCosts.checked("closingCosts") { it >= 0.0 },
            renovationCost = input.renovationCost.checked("renovationCost") { it >= 0.0 },
            monthlyCashFlow = input.monthlyCashFlow.checked("monthlyCashFlow"),
            annualNoi = input.annualNoi.checked("annualNoi"),
            capRatePct = input.capRatePct.checked("capRatePct"),
            cashOnCashPct = input.cashOnCashPct.checked("cashOnCashPct"),
            dscr = input.dscr.checked("dscr"),
            monthlyDebtService = input.monthlyDebtService.checked("monthlyDebtService") { it >= 0.0 },
            grossMonthlyRent = input.grossMonthlyRent.checked("grossMonthlyRent") { it >= 0.0 },
            vacancyRatePct = input.vacancyRatePct.checked("vacancyRatePct") { it in 0.0..100.0 },
            interestRatePct = input.interestRatePct.checked("interestRatePct") {
                it in 0.0..config.maxPlausibleInterestRatePct
            },
            estimatedMarketValue = input.estimatedMarketValue.checked("estimatedMarketValue") { it > 0.0 },
            afterRepairValue = input.afterRepairValue.checked("afterRepairValue") { it > 0.0 },
            medianAreaPrice = input.medianAreaPrice.checked("medianAreaPrice") { it > 0.0 },
            neighborhoodAppreciationPct = input.neighborhoodAppreciationPct.checked("neighborhoodAppreciationPct") {
                it in -100.0..100.0
            },
            marketDemand = input.marketDemand?.takeIf { it.isNotBlank() },
            areaDaysOnMarket = input.areaDaysOnMarket.checkedInt("areaDaysOnMarket") { it >= 0 },
            propertyPricePerSqFt = input.propertyPricePerSqFt.checked("propertyPricePerSqFt") { it > 0.0 },
            areaPricePerSqFt = input.areaPricePerSqFt.checked("areaPricePerSqFt") { it > 0.0 },
            yearBuilt = safeYearBuilt,
            sourceType = input.sourceType?.takeIf { it.isNotBlank() },
            listingDaysOnMarket = input.listingDaysOnMarket.checkedInt("listingDaysOnMarket") { it >= 0 },
            cumulativePriceDropPct = input.cumulativePriceDropPct.checked("cumulativePriceDropPct") { it in 0.0..100.0 },
            compsCount = input.compsCount.checkedInt("compsCount") { it >= 0 },
            rentConfidenceScore = input.rentConfidenceScore.checked("rentConfidenceScore") { it in 0.0..100.0 },
            rentEstimateLow = low,
            rentEstimateHigh = high
        )

        val nonFiniteWarnings = if (nonFiniteFields.isEmpty()) emptyList() else listOf(
            "Ignored ${nonFiniteFields.size} non-finite input value(s): ${nonFiniteFields.joinToString(", ")} -- treated as missing"
        )
        return SanitizedInput(sanitized, nonFiniteWarnings, validationIssues)
    }

    // -- Formatting (Locale-fixed so output strings are deterministic) ----------

    private fun Double.r2(): Double =
        if (!isFinite() || abs(this) > Double.MAX_VALUE / 100.0) this else kotlin.math.round(this * 100.0) / 100.0

    private fun Double.clamp01(): Double = when {
        isNaN() || this <= 0.0 -> 0.0
        this >= 1.0 -> 1.0
        else -> this
    }

    private fun clamp(v: Double): Double = when {
        v.isNaN() -> NEUTRAL_FALLBACK
        v <= SCORE_MIN -> SCORE_MIN
        v >= SCORE_MAX -> SCORE_MAX
        else -> v
    }
    private fun String.fmt(value: Double): String = String.format(Locale.US, this, value)
    private fun num(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".fmt(v)
    private fun pct(v: Double): String = "%.2f".fmt(v) + "%"
    private fun money(v: Double): String = "$" + "%,.0f".fmt(v)
}
