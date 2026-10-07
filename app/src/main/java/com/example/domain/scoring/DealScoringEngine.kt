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

    /** Matches [com.example.domain.finance.FinancialEngine]'s all-cash DSCR sentinel. */
    private const val ALL_CASH_DSCR_SENTINEL = 900.0

    private const val SCORE_MIN = 0.0
    private const val SCORE_MAX = 100.0

    // Reason polarity thresholds.
    private const val POSITIVE_AT = 70.0
    private const val NEGATIVE_BELOW_OR_AT = 40.0

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
        // 1. Sanitize: non-finite numbers are treated as missing + warning.
        val sanitizeWarnings = mutableListOf<String>()
        val s = sanitize(input, sanitizeWarnings)

        // 2. Effective within-subscore component weights (normalized).
        val componentWeights = resolveComponentWeights(weights)

        // 3. Evaluate the five fact-driven subscores (raw 0..100 + coverage).
        val cashFlow = evalCashFlow(s, componentWeights.getValue(ScoringWeights.CASH_FLOW))
        val equity = evalEquity(s, componentWeights.getValue(ScoringWeights.EQUITY))
        val market = evalMarket(s, componentWeights.getValue(ScoringWeights.MARKET))
        val risk = evalRisk(s, asOfYear, componentWeights.getValue(ScoringWeights.RISK_SAFETY))
        val distress = evalDistress(s, componentWeights.getValue(ScoringWeights.DISTRESS))
        val factScores = linkedMapOf(
            ScoringWeights.CASH_FLOW to cashFlow,
            ScoringWeights.EQUITY to equity,
            ScoringWeights.MARKET to market,
            ScoringWeights.RISK_SAFETY to risk,
            ScoringWeights.DISTRESS to distress
        )

        // 4. Consistency / plausibility checks -> warnings + confidence penalties.
        val consistency = runConsistencyChecks(s, asOfYear)

        // 5. Overall coverage & Data Confidence Score.
        val configuredComposite = linkedMapOf(
            ScoringWeights.CASH_FLOW to weights.cashFlow,
            ScoringWeights.EQUITY to weights.equity,
            ScoringWeights.MARKET to weights.market,
            ScoringWeights.RISK_SAFETY to weights.riskSafety,
            ScoringWeights.DISTRESS to weights.distressOpportunity,
            ScoringWeights.DATA_CONFIDENCE to weights.dataConfidence
        )
        // Coverage (scaled by source-quality boosts) first, then consistency penalties:
        // clamping in between guarantees penalties are always visible in the result.
        val coverageOverall = overallCoverage(factScores, configuredComposite)
        var confidenceBase = SCORE_MAX * coverageOverall
        s.rentConfidenceScore?.let { confidenceBase += ((it.coerceIn(0.0, 100.0) / 100.0) - 0.5) * 20.0 }
        run {
            val estimatesPresent = s.estimatedMarketValue != null || s.afterRepairValue != null || s.medianAreaPrice != null
            val comps = s.compsCount
            if (comps != null) {
                confidenceBase += when {
                    comps >= 3 -> 8.0
                    comps >= 1 -> 4.0
                    estimatesPresent -> -8.0 // value estimates with zero comps are fragile
                    else -> 0.0
                }
            }
        }
        val dataConfidence = clamp(clamp(confidenceBase) - consistency.confidencePenalty).r2()

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
        val weightTotal = participating.values.sum()
        val normalizedComposite = linkedMapOf<String, Double>()
        participating.forEach { (id, w) -> normalizedComposite[id] = w / weightTotal }

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
                reasons = confidenceReasons(s, coverageOverall, consistency.penaltyCount),
                components = factScores.map { (id, raw) ->
                    ComponentBreakdown(
                        componentId = id,
                        value = raw.coverage.r2(),
                        score = raw.coverage.r2() * 100.0,
                        weight = coverageWeight(id, configuredComposite),
                        covered = raw.coverage > 0.0,
                        rationale = "${displayNames.getValue(id)} data coverage ${"%.0f".fmt(raw.coverage * 100.0)}%"
                    )
                } + confidenceBoostComponents(s, consistency)
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
        warnings += sanitizeWarnings
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
            missingInputs = missing
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

    private data class Band(val at: Double, val score: Double)

    /** Piecewise-linear map clamps to the outer bands; explains itself in-band. */
    private fun bandScore(x: Double, bands: List<Band>): Pair<Double, String> {
        if (x <= bands.first().at) {
            return bands.first().score to "at/below ${num(bands.first().at)} floor -> ${"%.1f".fmt(bands.first().score)}"
        }
        if (x >= bands.last().at) {
            return bands.last().score to "at/above ${num(bands.last().at)} cap -> ${"%.1f".fmt(bands.last().score)}"
        }
        for (i in 1 until bands.size) {
            val lo = bands[i - 1]
            val hi = bands[i]
            if (x <= hi.at) {
                val t = (x - lo.at) / (hi.at - lo.at)
                val y = lo.score + t * (hi.score - lo.score)
                return y to "between ${num(lo.at)}->${"%.0f".fmt(lo.score)} pts and ${num(hi.at)}->${"%.0f".fmt(hi.score)} pts"
            }
        }
        return bands.last().score to "at/above ${num(bands.last().at)} cap"
    }

    // -- Threshold tables (fixed; changing them is a deliberate code change) --

    private val CAP_RATE_BANDS = bands(0.0 to 0.0, 2.0 to 15.0, 4.0 to 35.0, 6.0 to 58.0, 8.0 to 75.0, 10.0 to 88.0, 12.0 to 100.0)
    private val COC_BANDS = bands(-10.0 to 0.0, -5.0 to 5.0, 0.0 to 12.0, 4.0 to 35.0, 8.0 to 58.0, 10.0 to 70.0, 12.0 to 82.0, 15.0 to 100.0)
    private val MONTHLY_CF_BANDS = bands(-1000.0 to 0.0, 0.0 to 30.0, 100.0 to 42.0, 200.0 to 52.0, 300.0 to 62.0, 500.0 to 78.0, 800.0 to 90.0, 1200.0 to 100.0)
    private val DSCR_BANDS = bands(0.0 to 0.0, 0.8 to 8.0, 1.0 to 32.0, 1.1 to 48.0, 1.25 to 65.0, 1.5 to 80.0, 1.75 to 90.0, 2.0 to 100.0)
    private val INSTANT_EQUITY_BANDS = bands(-20.0 to 0.0, -10.0 to 8.0, 0.0 to 28.0, 5.0 to 40.0, 10.0 to 52.0, 15.0 to 64.0, 20.0 to 74.0, 25.0 to 82.0, 30.0 to 90.0, 40.0 to 100.0)
    private val ARV_SPREAD_BANDS = bands(-15.0 to 0.0, -5.0 to 8.0, 0.0 to 22.0, 10.0 to 42.0, 20.0 to 64.0, 25.0 to 74.0, 30.0 to 84.0, 40.0 to 100.0)
    private val VS_MEDIAN_BANDS = bands(-20.0 to 5.0, -10.0 to 20.0, 0.0 to 45.0, 10.0 to 65.0, 20.0 to 82.0, 30.0 to 100.0)
    private val APPRECIATION_BANDS = bands(-5.0 to 0.0, -2.0 to 12.0, 0.0 to 30.0, 2.0 to 48.0, 4.0 to 64.0, 6.0 to 80.0, 9.0 to 100.0)
    private val AREA_DOM_BANDS = bands(10.0 to 100.0, 20.0 to 88.0, 30.0 to 80.0, 45.0 to 65.0, 60.0 to 50.0, 90.0 to 30.0, 120.0 to 10.0)
    private val PPSQFT_DELTA_BANDS = bands(-25.0 to 100.0, -15.0 to 85.0, -5.0 to 70.0, 0.0 to 55.0, 10.0 to 35.0, 20.0 to 20.0, 35.0 to 5.0)
    private val AGE_BANDS = bands(0.0 to 95.0, 5.0 to 92.0, 15.0 to 85.0, 30.0 to 70.0, 50.0 to 55.0, 75.0 to 40.0, 100.0 to 28.0, 125.0 to 20.0)
    private val RISK_DSCR_BANDS = bands(0.0 to 0.0, 0.9 to 5.0, 1.0 to 22.0, 1.1 to 40.0, 1.2 to 52.0, 1.35 to 66.0, 1.5 to 78.0, 1.75 to 90.0, 2.0 to 95.0)
    private val VACANCY_BANDS = bands(2.0 to 95.0, 3.0 to 90.0, 5.0 to 75.0, 8.0 to 55.0, 12.0 to 35.0, 18.0 to 15.0)
    private val RENO_PCT_BANDS = bands(0.0 to 95.0, 5.0 to 80.0, 10.0 to 65.0, 20.0 to 45.0, 30.0 to 30.0, 50.0 to 15.0)
    private val RATE_BANDS = bands(3.0 to 97.0, 4.0 to 92.0, 5.0 to 82.0, 6.0 to 72.0, 7.0 to 58.0, 8.5 to 44.0, 10.0 to 30.0, 12.0 to 18.0)
    private val LISTING_DOM_BANDS = bands(0.0 to 8.0, 7.0 to 15.0, 30.0 to 42.0, 60.0 to 62.0, 90.0 to 75.0, 120.0 to 85.0, 180.0 to 92.0, 365.0 to 100.0)
    private val PRICE_DROP_BANDS = bands(0.0 to 8.0, 3.0 to 30.0, 5.0 to 45.0, 10.0 to 65.0, 15.0 to 80.0, 20.0 to 90.0, 30.0 to 100.0)

    private fun bands(vararg pairs: Pair<Double, Double>): List<Band> = pairs.map { Band(it.first, it.second) }

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
        val reason: ScoreReason?
    )

    private fun assemble(
        subscoreId: String,
        evals: List<Eval>,
        weights: Map<String, Double>
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
            components += ComponentBreakdown(
                componentId = e.componentId,
                value = e.value?.r2(),
                score = e.score?.r2(),
                weight = if (e.score != null) (w / totalW).r2() else 0.0,
                covered = e.score != null,
                rationale = e.rationale
            )
            e.reason?.let { reasons += it }
        }
        val coverage = if (totalW > 0.0) coveredW / totalW else 0.0
        val raw = if (coveredW > 0.0) clamp(acc / coveredW) else NEUTRAL_FALLBACK
        return RawSubScore(raw, coverage.clamp01(), reasons, components, missing.distinct())
    }

    private fun reason(subscoreId: String, score: Double, positive: String, negative: String, neutral: String): ScoreReason {
        val (polarity, msg) = when {
            score >= POSITIVE_AT -> ReasonPolarity.POSITIVE to positive
            score <= NEGATIVE_BELOW_OR_AT -> ReasonPolarity.NEGATIVE to negative
            else -> ReasonPolarity.NEUTRAL to neutral
        }
        return ScoreReason(polarity, msg, subscoreId)
    }

    // -- Cash Flow Score ------------------------------------------------------

    private fun evalCashFlow(s: DealInput, w: Map<String, Double>): RawSubScore {
        val id = ScoringWeights.CASH_FLOW
        val evals = mutableListOf<Eval>()

        val capRate = s.capRatePct
        if (capRate != null) {
            val (sc, how) = bandScore(capRate, CAP_RATE_BANDS)
            evals += Eval(
                "capRatePct", "capRatePct", capRate, sc,
                "Cap rate ${pct(capRate)} mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Cap rate ${pct(capRate)} is a strong yield (${"%.1f".fmt(sc)} pts)",
                    "Cap rate ${pct(capRate)} is weak (${"%.1f".fmt(sc)} pts)",
                    "Cap rate ${pct(capRate)} is average (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("capRatePct", "capRatePct", null, null, "capRatePct missing", null)

        val coc = s.cashOnCashPct
        if (coc != null) {
            val (sc, how) = bandScore(coc, COC_BANDS)
            evals += Eval(
                "cashOnCashPct", "cashOnCashPct", coc, sc,
                "Cash-on-cash return ${pct(coc)} mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Cash-on-cash return ${pct(coc)} beats typical targets (${"%.1f".fmt(sc)} pts)",
                    "Cash-on-cash return ${pct(coc)} is below investor targets (${"%.1f".fmt(sc)} pts)",
                    "Cash-on-cash return ${pct(coc)} is mediocre (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("cashOnCashPct", "cashOnCashPct", null, null, "cashOnCashPct missing", null)

        val cf = s.monthlyCashFlow
        if (cf != null) {
            val (sc, how) = bandScore(cf, MONTHLY_CF_BANDS)
            evals += Eval(
                "monthlyCashFlow", "monthlyCashFlow", cf, sc,
                "Monthly cash flow ${money(cf)}/mo mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Monthly cash flow ${money(cf)}/mo is healthy (${"%.1f".fmt(sc)} pts)",
                    "Monthly cash flow ${money(cf)}/mo is negative or too thin (${"%.1f".fmt(sc)} pts)",
                    "Monthly cash flow ${money(cf)}/mo is borderline (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("monthlyCashFlow", "monthlyCashFlow", null, null, "monthlyCashFlow missing", null)

        val dscr = s.dscr
        if (dscr != null) {
            if (dscr >= ALL_CASH_DSCR_SENTINEL) {
                evals += Eval(
                    "dscr", "dscr", dscr, 100.0,
                    "All-cash deal (DSCR sentinel ${"%.0f".fmt(dscr)}) -> 100 pts -- no debt-service burden",
                    ScoreReason(ReasonPolarity.POSITIVE, "All-cash structure -- no debt-service coverage risk", id)
                )
            } else {
                val (sc, how) = bandScore(dscr, DSCR_BANDS)
                evals += Eval(
                    "dscr", "dscr", dscr, sc,
                    "DSCR ${"%.2f".fmt(dscr)} mapped to ${"%.1f".fmt(sc)} pts ($how)",
                    reason(id, sc,
                        "DSCR ${"%.2f".fmt(dscr)} covers debt comfortably (${"%.1f".fmt(sc)} pts)",
                        "DSCR ${"%.2f".fmt(dscr)} does not safely cover debt service (${"%.1f".fmt(sc)} pts)",
                        "DSCR ${"%.2f".fmt(dscr)} is borderline (${"%.1f".fmt(sc)} pts)")
                )
            }
        } else evals += Eval("dscr", "dscr", null, null, "dscr missing", null)

        return assemble(id, evals, w)
    }

    // -- Equity Score ----------------------------------------------------------

    private fun evalEquity(s: DealInput, w: Map<String, Double>): RawSubScore {
        val id = ScoringWeights.EQUITY
        val evals = mutableListOf<Eval>()

        val price = s.purchasePrice?.takeIf { it > 0.0 }
        val mv = s.estimatedMarketValue?.takeIf { it > 0.0 }
        if (price != null && mv != null) {
            val equityPct = (mv - price) / mv * 100.0
            val (sc, how) = bandScore(equityPct, INSTANT_EQUITY_BANDS)
            evals += Eval(
                "instantEquityPct", "estimatedMarketValue|purchasePrice", equityPct, sc,
                "Instant equity ${pct(equityPct)} = (value ${money(mv)} - price ${money(price)}) / value; mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Buying ${pct(equityPct)} below market value -- strong instant equity (${"%.1f".fmt(sc)} pts)",
                    "Price leaves only ${pct(equityPct)} instant equity vs market value (${"%.1f".fmt(sc)} pts)",
                    "Instant equity of ${pct(equityPct)} vs market value is modest (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("instantEquityPct", "estimatedMarketValue|purchasePrice", null, null, "instantEquityPct needs estimatedMarketValue & purchasePrice", null)

        val arv = s.afterRepairValue?.takeIf { it > 0.0 }
        if (arv != null && price != null) {
            val reno = s.renovationCost ?: 0.0
            val spreadPct = (arv - (price + reno)) / arv * 100.0
            val (sc, how) = bandScore(spreadPct, ARV_SPREAD_BANDS)
            val renoNote = if (s.renovationCost == null) " (renovationCost not provided -- assumed \$0)" else " including reno ${money(reno)}"
            evals += Eval(
                "arvSpreadPct", "afterRepairValue|purchasePrice", spreadPct, sc,
                "ARV spread ${pct(spreadPct)} = (ARV ${money(arv)} - all-in ${money(price + reno)}) / ARV$renoNote; mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "ARV spread ${pct(spreadPct)} beats the 70%-rule cushion (${"%.1f".fmt(sc)} pts)",
                    "ARV spread ${pct(spreadPct)} leaves no safety margin after rehab (${"%.1f".fmt(sc)} pts)",
                    "ARV spread ${pct(spreadPct)} is workable but thin (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("arvSpreadPct", "afterRepairValue|purchasePrice", null, null, "arvSpreadPct needs afterRepairValue & purchasePrice", null)

        val median = s.medianAreaPrice?.takeIf { it > 0.0 }
        if (median != null && price != null) {
            val vsMedian = (median - price) / median * 100.0
            val (sc, how) = bandScore(vsMedian, VS_MEDIAN_BANDS)
            evals += Eval(
                "priceVsMedianPct", "medianAreaPrice|purchasePrice", vsMedian, sc,
                "Price is ${pct(vsMedian)} vs area median ${money(median)}; mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Priced ${pct(abs(vsMedian))} below the area median (${"%.1f".fmt(sc)} pts)",
                    "Priced ${pct(abs(vsMedian))} above the area median (${"%.1f".fmt(sc)} pts)",
                    "Price sits near the area median (delta ${pct(vsMedian)}, ${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("priceVsMedianPct", "medianAreaPrice|purchasePrice", null, null, "priceVsMedianPct needs medianAreaPrice & purchasePrice", null)

        return assemble(id, evals, w)
    }

    // -- Market Score ----------------------------------------------------------

    private fun evalMarket(s: DealInput, w: Map<String, Double>): RawSubScore {
        val id = ScoringWeights.MARKET
        val evals = mutableListOf<Eval>()

        val appr = s.neighborhoodAppreciationPct
        if (appr != null) {
            val (sc, how) = bandScore(appr, APPRECIATION_BANDS)
            evals += Eval(
                "neighborhoodAppreciationPct", "neighborhoodAppreciationPct", appr, sc,
                "Appreciation ${pct(appr)}/yr mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Neighborhood appreciates ${pct(appr)}/yr -- strong tailwind (${"%.1f".fmt(sc)} pts)",
                    "Neighborhood appreciation ${pct(appr)}/yr is flat or declining (${"%.1f".fmt(sc)} pts)",
                    "Neighborhood appreciation ${pct(appr)}/yr is moderate (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("neighborhoodAppreciationPct", "neighborhoodAppreciationPct", null, null, "neighborhoodAppreciationPct missing", null)

        val demandScore = mapDemand(s.marketDemand)
        if (demandScore != null) {
            val (sc, label) = demandScore
            evals += Eval(
                "marketDemand", "marketDemand", sc, sc,
                "Market demand \"$label\" -> ${"%.0f".fmt(sc)} pts (fixed demand scale)",
                reason(id, sc,
                    "Market demand is \"$label\" (${"%.0f".fmt(sc)} pts)",
                    "Market demand is \"$label\" -- slow exits (${"%.0f".fmt(sc)} pts)",
                    "Market demand is \"$label\" (${"%.0f".fmt(sc)} pts)")
            )
        } else evals += Eval("marketDemand", "marketDemand", null, null, "marketDemand missing or unrecognized", null)

        val dom = s.areaDaysOnMarket
        if (dom != null) {
            val (sc, how) = bandScore(dom.toDouble(), AREA_DOM_BANDS)
            evals += Eval(
                "areaDaysOnMarket", "areaDaysOnMarket", dom.toDouble(), sc,
                "Area average DOM $dom mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Area sells fast ($dom days on market) (${"%.1f".fmt(sc)} pts)",
                    "Area is slow -- $dom average days on market (${"%.1f".fmt(sc)} pts)",
                    "Area liquidity is average ($dom DOM) (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("areaDaysOnMarket", "areaDaysOnMarket", null, null, "areaDaysOnMarket missing", null)

        val propPpsf = s.propertyPricePerSqFt?.takeIf { it > 0.0 }
        val areaPpsf = s.areaPricePerSqFt?.takeIf { it > 0.0 }
        if (propPpsf != null && areaPpsf != null) {
            // Positive delta = property is MORE expensive per sqft than the area norm.
            val deltaPct = (propPpsf - areaPpsf) / areaPpsf * 100.0
            val (sc, how) = bandScore(deltaPct, PPSQFT_DELTA_BANDS)
            evals += Eval(
                "pricePerSqFtVsAreaPct", "propertyPricePerSqFt|areaPricePerSqFt", deltaPct, sc,
                "\$/sqft $${"%.0f".fmt(propPpsf)} vs area $${"%.0f".fmt(areaPpsf)} (delta ${pct(deltaPct)}); mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "\$/sqft is ${pct(abs(deltaPct))} below the area norm -- value entry (${"%.1f".fmt(sc)} pts)",
                    "\$/sqft is ${pct(abs(deltaPct))} above the area norm (${"%.1f".fmt(sc)} pts)",
                    "\$/sqft is near the area norm (delta ${pct(deltaPct)}, ${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("pricePerSqFtVsAreaPct", "propertyPricePerSqFt|areaPricePerSqFt", null, null, "pricePerSqFtVsAreaPct needs propertyPricePerSqFt & areaPricePerSqFt", null)

        return assemble(id, evals, w)
    }

    // -- Risk Score (safety direction: 100 = lowest risk) ----------------------

    private fun evalRisk(s: DealInput, asOfYear: Int, w: Map<String, Double>): RawSubScore {
        val id = ScoringWeights.RISK_SAFETY
        val evals = mutableListOf<Eval>()

        val yearBuilt = s.yearBuilt
        if (yearBuilt != null) {
            if (yearBuilt > asOfYear) {
                evals += Eval("propertyAgeYears", "yearBuilt", null, null, "yearBuilt $yearBuilt is in the future (> $asOfYear) -- ignored", null)
            } else {
                val age = (asOfYear - yearBuilt).toDouble()
                val (sc, how) = bandScore(age, AGE_BANDS)
                evals += Eval(
                    "propertyAgeYears", "yearBuilt", age, sc,
                    "Property age ${"%.0f".fmt(age)} yrs (built $yearBuilt) mapped to ${"%.1f".fmt(sc)} pts ($how)",
                    reason(id, sc,
                        "Only ${"%.0f".fmt(age)} years old -- low deferred-maintenance risk (${"%.1f".fmt(sc)} pts)",
                        "Built $yearBuilt (${"%.0f".fmt(age)} yrs old) -- elevated maintenance/obsolete-systems risk (${"%.1f".fmt(sc)} pts)",
                        "Age ${"%.0f".fmt(age)} yrs is mid-range (${"%.1f".fmt(sc)} pts)")
                )
            }
        } else evals += Eval("propertyAgeYears", "yearBuilt", null, null, "yearBuilt missing", null)

        val dscr = s.dscr
        if (dscr != null) {
            if (dscr >= ALL_CASH_DSCR_SENTINEL) {
                evals += Eval(
                    "dscr", "dscr", dscr, 95.0,
                    "All-cash deal -> 95 pts -- no foreclosure/refinancing risk",
                    ScoreReason(ReasonPolarity.POSITIVE, "All-cash structure removes financing risk (95 pts)", id)
                )
            } else {
                val (sc, how) = bandScore(dscr, RISK_DSCR_BANDS)
                evals += Eval(
                    "dscr", "dscr", dscr, sc,
                    "DSCR ${"%.2f".fmt(dscr)} mapped to ${"%.1f".fmt(sc)} pts ($how)",
                    reason(id, sc,
                        "DSCR ${"%.2f".fmt(dscr)} gives a wide payment cushion (${"%.1f".fmt(sc)} pts)",
                        "DSCR ${"%.2f".fmt(dscr)} -- thin margin; small rent dips cause default risk (${"%.1f".fmt(sc)} pts)",
                        "DSCR ${"%.2f".fmt(dscr)} is an average cushion (${"%.1f".fmt(sc)} pts)")
                )
            }
        } else evals += Eval("dscr", "dscr", null, null, "dscr missing", null)

        val vac = s.vacancyRatePct
        if (vac != null) {
            val (sc, how) = bandScore(vac, VACANCY_BANDS)
            evals += Eval(
                "vacancyRatePct", "vacancyRatePct", vac, sc,
                "Vacancy ${pct(vac)} mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Low vacancy assumption/area (${pct(vac)}) (${"%.1f".fmt(sc)} pts)",
                    "High vacancy exposure (${pct(vac)}) (${"%.1f".fmt(sc)} pts)",
                    "Vacancy ${pct(vac)} is typical (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("vacancyRatePct", "vacancyRatePct", null, null, "vacancyRatePct missing", null)

        val price = s.purchasePrice?.takeIf { it > 0.0 }
        val reno = s.renovationCost
        if (reno != null && price != null) {
            val renoPct = reno / price * 100.0
            val (sc, how) = bandScore(renoPct, RENO_PCT_BANDS)
            evals += Eval(
                "renovationPctOfPrice", "renovationCost|purchasePrice", renoPct, sc,
                "Renovation is ${pct(renoPct)} of price (${money(reno)} / ${money(price)}); mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Light rehab (${pct(renoPct)} of price) -- low execution risk (${"%.1f".fmt(sc)} pts)",
                    "Heavy rehab (${pct(renoPct)} of price) -- high execution/budget risk (${"%.1f".fmt(sc)} pts)",
                    "Moderate rehab (${pct(renoPct)} of price) (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("renovationPctOfPrice", "renovationCost|purchasePrice", null, null, "renovationPctOfPrice needs renovationCost & purchasePrice", null)

        val rate = s.interestRatePct
        if (rate != null && rate <= 30.0) {
            val (sc, how) = bandScore(rate, RATE_BANDS)
            evals += Eval(
                "interestRatePct", "interestRatePct", rate, sc,
                "Interest rate ${pct(rate)} mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Interest rate ${pct(rate)} is favorable (${"%.1f".fmt(sc)} pts)",
                    "Interest rate ${pct(rate)} is expensive / refinancing risk (${"%.1f".fmt(sc)} pts)",
                    "Interest rate ${pct(rate)} is average (${"%.1f".fmt(sc)} pts)")
            )
        } else if (rate != null) {
            evals += Eval("interestRatePct", "interestRatePct", null, null, "interestRatePct ${pct(rate)} is implausible -- ignored", null)
        } else evals += Eval("interestRatePct", "interestRatePct", null, null, "interestRatePct missing", null)

        val flood = s.floodZone
        if (flood != null) {
            val sc = if (flood) 30.0 else 90.0
            evals += Eval(
                "floodZone", "floodZone", if (flood) 1.0 else 0.0, sc,
                if (flood) "Flood zone -> 30 pts (insurance/severity exposure)" else "Not in a flood zone -> 90 pts",
                reason(id, sc,
                    "Not in a flood zone (${"%.0f".fmt(sc)} pts)",
                    "Property is in a flood zone -- insurance and severity exposure (${"%.0f".fmt(sc)} pts)",
                    "Flood status noted (${"%.0f".fmt(sc)} pts)")
            )
        } else evals += Eval("floodZone", "floodZone", null, null, "floodZone missing", null)

        return assemble(id, evals, w)
    }

    // -- Distress Score (100 = most distressed / motivated seller) --------------

    private fun evalDistress(s: DealInput, w: Map<String, Double>): RawSubScore {
        val id = ScoringWeights.DISTRESS
        val evals = mutableListOf<Eval>()

        val source = mapSourceType(s.sourceType)
        if (source != null) {
            val (sc, label) = source
            evals += Eval(
                "sourceType", "sourceType", sc, sc,
                "Source type \"$label\" -> ${"%.0f".fmt(sc)} pts (fixed source-motivation scale)",
                reason(id, sc,
                    "Highly motivated seller channel: $label (${"%.0f".fmt(sc)} pts)",
                    "Low-motivation channel: $label (${"%.0f".fmt(sc)} pts)",
                    "Moderate-motivation channel: $label (${"%.0f".fmt(sc)} pts)")
            )
        } else evals += Eval("sourceType", "sourceType", null, null, "sourceType missing", null)

        val dom = s.listingDaysOnMarket
        if (dom != null) {
            val (sc, how) = bandScore(dom.toDouble(), LISTING_DOM_BANDS)
            evals += Eval(
                "listingDaysOnMarket", "listingDaysOnMarket", dom.toDouble(), sc,
                "Listing sat $dom days on market; mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Stale listing ($dom days) -- seller likely flexible (${"%.1f".fmt(sc)} pts)",
                    "Fresh listing ($dom days) -- little motivation signal (${"%.1f".fmt(sc)} pts)",
                    "Listing age $dom days -- some motivation signal (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("listingDaysOnMarket", "listingDaysOnMarket", null, null, "listingDaysOnMarket missing", null)

        val drop = s.cumulativePriceDropPct
        if (drop != null) {
            val (sc, how) = bandScore(drop, PRICE_DROP_BANDS)
            evals += Eval(
                "cumulativePriceDropPct", "cumulativePriceDropPct", drop, sc,
                "Cumulative price reduction ${pct(drop)}; mapped to ${"%.1f".fmt(sc)} pts ($how)",
                reason(id, sc,
                    "Price already cut ${pct(drop)} -- active motivation (${"%.1f".fmt(sc)} pts)",
                    "No meaningful price cuts (${pct(drop)}) (${"%.1f".fmt(sc)} pts)",
                    "Price cut ${pct(drop)} -- some motivation (${"%.1f".fmt(sc)} pts)")
            )
        } else evals += Eval("cumulativePriceDropPct", "cumulativePriceDropPct", null, null, "cumulativePriceDropPct missing", null)

        val delinquent = s.taxDelinquent
        if (delinquent != null) {
            val sc = if (delinquent) 95.0 else 8.0
            evals += Eval(
                "taxDelinquent", "taxDelinquent", if (delinquent) 1.0 else 0.0, sc,
                if (delinquent) "Property taxes delinquent -> 95 pts" else "No tax delinquency -> 8 pts",
                reason(id, sc,
                    "Tax delinquency recorded -- owner under financial pressure (${"%.0f".fmt(sc)} pts)",
                    "No tax delinquency signal (${"%.0f".fmt(sc)} pts)",
                    "Tax status noted (${"%.0f".fmt(sc)} pts)")
            )
        } else evals += Eval("taxDelinquent", "taxDelinquent", null, null, "taxDelinquent missing", null)

        return assemble(id, evals, w)
    }

    // =========================================================================
    // Coverage, consistency & confidence
    // =========================================================================

    private data class ConsistencyResult(
        val warnings: List<String>,
        val confidencePenalty: Double,
        val penaltyCount: Int
    )

    private fun runConsistencyChecks(s: DealInput, asOfYear: Int): ConsistencyResult {
        val warnings = mutableListOf<String>()
        var penalty = 0.0
        var flagCount = 0

        fun flag(msg: String) {
            warnings += msg
            penalty += 12.0
            flagCount += 1
        }

        /** Warning without confidence impact (impact already lives elsewhere). */
        fun notice(msg: String) {
            warnings += msg
        }

        s.purchasePrice?.let { if (it <= 0.0) flag("purchasePrice ${money(it)} is not positive -- treated as missing for equity math") }

        val price = s.purchasePrice?.takeIf { it > 0.0 }
        val noi = s.annualNoi
        val cap = s.capRatePct
        if (price != null && noi != null && cap != null) {
            val impliedCap = noi / price * 100.0
            if (abs(impliedCap - cap) > 1.5) {
                flag("Inconsistent data: capRatePct ${pct(cap)} vs implied NOI/price cap rate ${pct(impliedCap)}")
            }
        }

        val dscr = s.dscr
        val ds = s.monthlyDebtService
        val cf = s.monthlyCashFlow
        if (dscr != null && dscr < ALL_CASH_DSCR_SENTINEL && ds != null && ds > 0.0 && cf != null) {
            val impliedCf = (dscr - 1.0) * ds
            val tolerance = maxOf(150.0, 0.3 * abs(cf))
            if (abs(impliedCf - cf) > tolerance) {
                flag("Inconsistent data: monthlyCashFlow ${money(cf)}/mo vs DSCR-implied ${money(impliedCf)}/mo")
            }
        }

        val mv = s.estimatedMarketValue?.takeIf { it > 0.0 }
        if (mv != null && price != null) {
            val ratio = mv / price
            if (ratio > 3.0 || ratio < 0.33) {
                flag("Implausible spread: market value ${money(mv)} is ${"%.1f".fmt(ratio)}x the purchase price ${money(price)}")
            }
        }

        val lo = s.rentEstimateLow
        val hi = s.rentEstimateHigh
        if (lo != null && hi != null && lo > 0.0 && hi > 0.0 && hi >= lo) {
            val mid = (lo + hi) / 2.0
            val width = (hi - lo) / mid
            if (width > 0.5) {
                flag("Rent estimate range is wide (${money(lo)}-${money(hi)}, ${pct(width * 100.0)} of midpoint) -- low rent certainty")
            }
        }

        val rent = s.grossMonthlyRent
        if (rent != null && cf != null && cf - rent > 1.0) {
            flag("Implausible: monthlyCashFlow ${money(cf)}/mo exceeds gross rent ${money(rent)}/mo")
        }

        s.yearBuilt?.let { if (it > asOfYear) flag("yearBuilt $it is after reference year $asOfYear -- age ignored") }
        s.interestRatePct?.let { if (it > 30.0) flag("interestRatePct ${pct(it)} is implausible -- treated as missing") }

        // Warning-only signals (their confidence impact is handled by the boost math, do not double-penalize).
        val estimatesPresent = s.estimatedMarketValue != null || s.afterRepairValue != null || s.medianAreaPrice != null
        if (s.compsCount != null && s.compsCount == 0 && estimatesPresent) {
            notice("0 comparable sales -- value estimate is weakly supported")
        }

        return ConsistencyResult(warnings, penalty, flagCount)
    }

    private fun confidenceReasons(s: DealInput, coverage: Double, penaltyCount: Int): List<ScoreReason> {
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
                if (it >= 3) ReasonPolarity.POSITIVE else ReasonPolarity.NEGATIVE,
                if (it >= 3) "$it comparable sales support the value estimate" else "Only $it comparable sales -- value estimate is weakly supported",
                id
            )
        }
        if (penaltyCount > 0) {
            out += ScoreReason(ReasonPolarity.NEGATIVE, "$penaltyCount consistency/plausibility issue(s) detected in inputs", id)
        }
        return out
    }

    private fun confidenceBoostComponents(s: DealInput, consistency: ConsistencyResult): List<ComponentBreakdown> {
        val out = mutableListOf<ComponentBreakdown>()
        s.rentConfidenceScore?.let {
            out += ComponentBreakdown("rentConfidenceScore", it.r2(), it.r2(), 0.0, true,
                "Provider rent confidence ${"%.0f".fmt(it)}% -> ${if (it >= 50) "+" else ""}${"%.1f".fmt(((it.coerceIn(0.0, 100.0) / 100.0) - 0.5) * 20.0)} confidence pts")
        }
        s.compsCount?.let {
            val boost = when {
                it >= 3 -> 8.0
                it >= 1 -> 4.0
                else -> -8.0
            }
            out += ComponentBreakdown("compsCount", it.toDouble(), null, 0.0, true,
                "$it comps -> ${if (boost >= 0) "+" else ""}${"%.0f".fmt(boost)} confidence pts")
        }
        if (consistency.confidencePenalty > 0.0) {
            out += ComponentBreakdown("consistencyPenalty", null, null, 0.0, true,
                "${consistency.penaltyCount} consistency issue(s) -> -${"%.0f".fmt(consistency.confidencePenalty)} confidence pts")
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
            val total = merged.values.sum()
            out[subId] = merged.mapValues { if (total > 0.0) it.value / total else 0.0 }
        }
        return out
    }

    private fun overallCoverage(
        factScores: Map<String, RawSubScore>,
        configuredComposite: Map<String, Double>
    ): Double {
        var wSum = 0.0
        var acc = 0.0
        for ((id, raw) in factScores) {
            val w = configuredComposite.getValue(id)
            wSum += w
            acc += w * raw.coverage
        }
        if (wSum <= 0.0) {
            // Degenerate config (all fact weights 0): fall back to the plain mean.
            return factScores.values.map { it.coverage }.average().clamp01()
        }
        return (acc / wSum).clamp01()
    }

    private fun coverageWeight(id: String, configuredComposite: Map<String, Double>): Double {
        val pool = listOf(ScoringWeights.CASH_FLOW, ScoringWeights.EQUITY, ScoringWeights.MARKET, ScoringWeights.RISK_SAFETY, ScoringWeights.DISTRESS)
        val total = pool.sumOf { configuredComposite.getValue(it) }
        return if (total > 0.0) (configuredComposite.getValue(id) / total).r2() else 0.0
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

    private fun mapDemand(raw: String?): Pair<Double, String>? {
        val key = raw?.trim()?.lowercase(Locale.US)?.replace(Regex("[^a-z]"), "") ?: return null
        return when (key) {
            "high", "strong", "sellersmarket", "hot" -> 90.0 to raw.trim()
            "moderate", "medium", "warm" -> 70.0 to raw.trim()
            "balanced", "neutral", "stable" -> 50.0 to raw.trim()
            "buyersmarket", "low", "weak", "cold", "slow" -> 30.0 to raw.trim()
            else -> null
        }
    }

    private fun mapSourceType(raw: String?): Pair<Double, String>? {
        val key = raw?.trim()?.uppercase(Locale.US) ?: return null
        return when (key) {
            "FORECLOSURE", "BANK_OWNED", "REO", "SHORT_SALE" -> 100.0 to raw.trim()
            "AUCTION" -> 95.0 to raw.trim()
            "WHOLESALE" -> 85.0 to raw.trim()
            "OFF_MARKET", "OFFMARKET", "PRE_FORECLOSURE" -> 60.0 to raw.trim()
            "ON_MARKET", "ONMARKET", "MLS" -> 25.0 to raw.trim()
            else -> null
        }
    }

    // -- Sanitization -----------------------------------------------------------

    private fun sanitize(input: DealInput, warnings: MutableList<String>): DealInput {
        val dropped = mutableListOf<String>()
        fun Double?.keep(name: String): Double? =
            if (this != null && !this.isFinite()) {
                dropped += name
                null
            } else this
        val out = input.copy(
            purchasePrice = input.purchasePrice.keep("purchasePrice"),
            closingCosts = input.closingCosts.keep("closingCosts"),
            renovationCost = input.renovationCost.keep("renovationCost"),
            monthlyCashFlow = input.monthlyCashFlow.keep("monthlyCashFlow"),
            annualNoi = input.annualNoi.keep("annualNoi"),
            capRatePct = input.capRatePct.keep("capRatePct"),
            cashOnCashPct = input.cashOnCashPct.keep("cashOnCashPct"),
            dscr = input.dscr.keep("dscr"),
            monthlyDebtService = input.monthlyDebtService.keep("monthlyDebtService"),
            grossMonthlyRent = input.grossMonthlyRent.keep("grossMonthlyRent"),
            vacancyRatePct = input.vacancyRatePct.keep("vacancyRatePct"),
            interestRatePct = input.interestRatePct.keep("interestRatePct"),
            estimatedMarketValue = input.estimatedMarketValue.keep("estimatedMarketValue"),
            afterRepairValue = input.afterRepairValue.keep("afterRepairValue"),
            medianAreaPrice = input.medianAreaPrice.keep("medianAreaPrice"),
            neighborhoodAppreciationPct = input.neighborhoodAppreciationPct.keep("neighborhoodAppreciationPct"),
            propertyPricePerSqFt = input.propertyPricePerSqFt.keep("propertyPricePerSqFt"),
            areaPricePerSqFt = input.areaPricePerSqFt.keep("areaPricePerSqFt"),
            cumulativePriceDropPct = input.cumulativePriceDropPct.keep("cumulativePriceDropPct"),
            rentConfidenceScore = input.rentConfidenceScore.keep("rentConfidenceScore"),
            rentEstimateLow = input.rentEstimateLow.keep("rentEstimateLow"),
            rentEstimateHigh = input.rentEstimateHigh.keep("rentEstimateHigh")
        )
        if (dropped.isNotEmpty()) {
            warnings += "Ignored ${dropped.size} non-finite input value(s): ${dropped.joinToString(", ")} -- treated as missing"
        }
        return out
    }

    // -- Formatting (Locale-fixed so output strings are deterministic) ----------

    private fun Double.r2(): Double = kotlin.math.round(this * 100.0) / 100.0
    private fun Double.clamp01(): Double = coerceIn(0.0, 1.0)
    private fun clamp(v: Double): Double = v.coerceIn(SCORE_MIN, SCORE_MAX)
    private fun String.fmt(value: Double): String = String.format(Locale.US, this, value)
    private fun num(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".fmt(v)
    private fun pct(v: Double): String = "%.2f".fmt(v) + "%"
    private fun money(v: Double): String = "$" + "%,.0f".fmt(v)
}
