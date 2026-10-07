# Deal Scoring Engine

Deterministic, explainable deal scoring for RealEstateAI. The authoritative scorer is the
pure-Kotlin engine in `app/src/main/java/com/example/domain/scoring/`; the property-import
pipeline calls it through `domain/intelligence/scoring/DealScoringEngine.kt`. There are no
Android UI changes or AI dependencies in score calculation.

## Scores produced

| Score | Range | Meaning |
|---|---:|---|
| **Deal Score** | 0..100 | Weighted composite of the participating subscores. |
| **Cash Flow Score** | 0..100 | Cap rate, cash-on-cash, monthly cash flow, and DSCR. |
| **Equity Score** | 0..100 | Instant equity vs market value, ARV spread, and price vs area median. |
| **Market Score** | 0..100 | Appreciation, demand, area days-on-market, and $/sqft vs area. |
| **Risk Score** | 0..100 | Safety direction: 100 = lowest risk (age, DSCR, vacancy, rehab, rate, flood). |
| **Distress Score** | 0..100 | Opportunity signal: 100 = most motivated/distressed (source channel, listing age, price cuts, tax delinquency). |
| **Data Confidence Score** | 0..100 | Weighted input coverage with source-confidence hints and explicit consistency penalties. |

Distress is an opportunity dimension, not a measure of investment quality. It is reported but
has **zero composite weight by default**. Data Confidence is also reported separately by default.
The default quality composite weights are Cash Flow 0.35, Equity 0.25, Market 0.20, and Risk
0.20. A caller can explicitly opt either separate score into the composite.

## Guarantees and audit contract

1. **Deterministic** — `evaluate(input, weights, config, asOfYear)` is a pure function. No clock,
   randomness, I/O, or environment reads. Formatting is locale-fixed. Repeated identical calls
   return equal `DealScoreResult`s.
2. **Configurable and normalized** — composite and within-subscore weights accept finite,
   non-negative values and are stably normalized (including very large finite weights). Unknown
   IDs and all-zero configured groups are rejected. Numeric band tables, category lookups,
   flood scores, the all-cash DSCR sentinel, neutral score, and coverage attenuation can be
   overridden through `ScoringConfig`, including rent-provider confidence scaling,
   comparable-count adjustments, consistency-issue penalties, all-cash and delinquency special
   scores, reason-polarity thresholds, and validation bounds; overrides are validated before use.
3. **Structured explanations** — each component returns its input field IDs, scored value,
   points, normalized component weight, human-readable rationale, rule ID, and exact applied
   threshold anchors/interpolation or category key. Each subscore reports raw/effective score,
   data coverage, composite weight, normalized weight, and contribution. The composite therefore
   reconciles to the displayed contributions. Data Confidence includes a separate arithmetic
   breakdown of coverage points, provider-confidence adjustment, comparable-sales adjustment,
   consistency penalty, and final points.
4. **Explicit missing/conflicting data** — missing components reduce coverage and attenuate to
   neutral; invalid ranges are dropped and called out; conflicting rent bounds, implausible values,
   and financial inconsistencies are surfaced as warnings and confidence penalties. ARV spread
   requires an explicit renovation cost: missing rehab is not assumed to be `$0`.
5. **AI isolation** — the production import path computes the deterministic result first. AI sees
   score values only as context and cannot submit score overrides. The standalone
   `attachAiObservations(result, notes)` API only adds sanitized display text; score fields and
   explanations are unchanged.

## Missing data and confidence

For each fact-driven subscore, uncovered component weights are excluded from the raw weighted
mean and included in `coverage`. The effective score is pulled toward the configured neutral:

```text
effective = neutral + (raw - neutral) * coverage ^ attenuationExponent
```

Default neutral is 50 and exponent is 1. The Data Confidence Score starts from weighted fact
coverage, applies an optional rent-provider hint and comparable-sales hint, then subtracts
consistency/plausibility penalties. It is not a substitute for component-level coverage. Unknown
or invalid metrics stay missing; the scorer does not infer market value, seller channel, flood
status, comps, or delinquency from a portal name or a missing field.

## Usage

```kotlin
val result = DealScoringEngine.evaluate(
    DealInput(
        purchasePrice = financial.purchasePrice,
        monthlyCashFlow = financial.monthlyCashFlow,
        annualNoi = financial.noiAnnual,
        capRatePct = financial.capRate,
        cashOnCashPct = financial.cashOnCashReturn,
        dscr = financial.dscr, // FinancialEngine's 999 all-cash sentinel is understood
        renovationCost = financial.input.renovationCost,
        estimatedMarketValue = verifiedOrExplicitlyEstimatedValue,
        yearBuilt = explicitlyKnownYearBuilt
    )
)

result.score              // 0..100 composite
result.subscores          // all six detailed subscores
result.weights            // configured and effective normalized weights
result.reasons             // typed positive / neutral / negative explanations
result.warnings            // missing, invalid, and inconsistent data
result.confidenceBreakdown // exact Data Confidence arithmetic
```

For new/custom numeric rules, `ScoringConfig.numericBandOverrides` replaces the complete band
table for the named component. Each table needs at least two points, ascending thresholds, and
monotone scores in 0..100. The result records the exact anchors applied for every scored value.

## Production adapter boundary

The legacy intelligence package name remains as a compatibility adapter for import and AI
analysis models. It delegates scoring to the standalone engine and carries the complete
`DealScoreResult` in `DealScoreBreakdown.detailedResult`. It maps canonical facts and finance
outputs only when their underlying explicit inputs are available. In particular, a portal
(`Zillow`, `Redfin`, etc.) is not a seller channel; the legacy model's fallback rent, fixed vacancy,
repair/closing-cost defaults, and projected ARV are not promoted as property facts; and synthetic
or unverified enrichment is not promoted to comps, market value, flood, or tax-delinquency facts.

Qualification remains a separate gate from Deal Score. `QualificationScoringPolicy` versions the
neutral base, point deltas, age/DSCR risk heuristic, and permitted offer-discount bounds. Each of
the ten required checks includes its observed value, configured threshold, pass/fail result, and
exact score delta in `QualificationEvaluation.checkResults`; the raw score, clamped score,
reference year, policy version, effective discount, and validation warnings are also returned.
Invalid/non-finite required inputs fail closed. Offer generation is blocked if qualification
cannot produce a finite, positive deterministic offer price.

## Tests

`app/src/test/java/com/example/DealScoringEngineTest.kt` covers score separation, repeated and
locale-independent determinism, configurable and overflow-safe weight normalization, rule-trace
integrity, boundary interpolation, missing-data attenuation, conflicting/out-of-range inputs,
confidence calibration, all-cash handling, AI isolation, and FinancialEngine integration.
`app/src/test/java/com/example/PropertyUrlIntelligenceTest.kt` exercises the production adapter.
Qualification-boundary and locale-stability tests live in `app/src/test/java/com/example/QualificationEngineTest.kt`.
