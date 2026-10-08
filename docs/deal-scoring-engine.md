# Deal Scoring Engine

Deterministic, explainable deal scoring for RealEstateAI. The authoritative scorer is the
pure-Kotlin engine in `app/src/main/java/com/example/domain/scoring/`. The clean
orchestration contract is `domain/intelligence/scoring/DealAnalysisOrchestrator.kt`; the
property-import pipeline's legacy entry point
(`domain/intelligence/scoring/DealScoringEngine.kt`) is now a thin facade that delegates to
the orchestrator. There are no Android UI changes or AI dependencies in score calculation.

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

1. **Deterministic** — `evaluate(input, weights, config, asOfYear)` and
   `DealAnalysisOrchestrator.score(...)` are pure functions. No clock, randomness, I/O, or
   environment reads. Formatting is locale-fixed. Repeated identical calls return equal
   `DealScoreResult`s.
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

## Deal analysis orchestration contract

`DealAnalysisOrchestrator` (in `domain/intelligence/scoring`) is the single clean entry
point that turns pipeline facts into a `DealScoreResult`:

```kotlin
DealAnalysisOrchestrator.score(
    property,      // CanonicalProperty            -- canonical listing facts
    underwriting,  // StrategyFinancialMetrics?    -- deterministic scenario outputs
    market,        // MarketFacts                  -- market/records/verified-channel facts
    comps,         // CompCoverage                 -- verified comp + rent-range coverage
    provenance,    // DataProvenanceManifest       -- per-field source tier & confidence
    weights,       // ScoringWeights               -- caller-validated deterministic config
    config,        // ScoringConfig                -- caller-validated deterministic config
    asOfYear       // Int                          -- deterministic reference year
): DealScoreResult
```

Mapping and filtering policy (all in the orchestrator, **no scoring math**):

- **Provenance-aware** — any fact whose best manifest entry has tier
  `ProvenanceSourceTier.AI_INFERENCE` is dropped before the engine sees it. Absence of a
  manifest entry does not poison a value; only an explicit AI-inference entry does.
- **Scenario isolation** — underwriting outputs (cash flow, NOI, cap rate, cash-on-cash,
  DSCR, debt service) are promoted into the score only when the canonical inputs the
  scenario was built on (explicit trusted list price **and** explicit trusted rent) exist.
  A finance-model fallback rent, a custom scenario rent, or an AI-inferred rent can never
  smuggle points into the deal score.
- **No fake facts** — portal names (`Zillow`, `Redfin`, ...) are never a seller channel;
  only an explicit, provenance-trusted `MarketFacts.sourceType` drives the distress
  dimension. Legacy fixed assumptions (5% vacancy, default repairs, projected ARV) are not
  promoted to property facts; `renovationCost` / `afterRepairValue` must be explicitly
  provided (and trusted) to be scored.
- **Authoritative engine** — the orchestrator builds the `DealInput` and delegates
  exclusively to `DealScoringEngine.evaluate`; the result carries that engine's
  `scoringModelVersion`. `toBreakdown(result)` projects the immutable result onto the
  legacy `DealScoreBreakdown` shape used by AI analysis models (rounding only).

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

The legacy intelligence package name (`domain/intelligence/scoring/DealScoringEngine.kt`)
remains as a compatibility facade for the import pipeline and AI analysis models. It performs
**no arithmetic**: it delegates to `DealAnalysisOrchestrator.score(...)` and projects the
result through `toBreakdown(...)`, carrying the complete `DealScoreResult` in
`DealScoreBreakdown.detailedResult`. Because the facade and the orchestration contract share
one mapping, they can never drift apart.

The mapping policy is unchanged: canonical facts and finance outputs are mapped only when
their underlying explicit inputs are available. In particular, a portal (`Zillow`, `Redfin`,
etc.) is not a seller channel; the legacy model's fallback rent, fixed vacancy,
repair/closing-cost defaults, and projected ARV are not promoted as property facts; and
synthetic or unverified enrichment is not promoted to comps, market value, flood, or
tax-delinquency facts.

Qualification independence. The deterministic deal-analysis orchestration has **no dependency
on the obsolete qualification scoring** (`domain/qualification`); an architecture guard test
enforces that neither `domain/scoring` nor `domain/intelligence/scoring` (nor the
`DealScoreBreakdown` projection) references it. Qualification remains a separate gate from
Deal Score: `QualificationScoringPolicy` versions the neutral base, point deltas, age/DSCR risk
heuristic, and permitted offer-discount bounds. Each of the ten required checks includes its
observed value, configured threshold, pass/fail result, and exact score delta in
`QualificationEvaluation.checkResults`; the raw score, clamped score, reference year, policy
version, effective discount, and validation warnings are also returned. Invalid/non-finite
required inputs fail closed. Offer generation is blocked if qualification cannot produce a
finite, positive deterministic offer price.

## Tests

`app/src/test/java/com/example/DealScoringEngineTest.kt` covers score separation, repeated and
locale-independent determinism, configurable and overflow-safe weight normalization, rule-trace
integrity, boundary interpolation, missing-data attenuation, conflicting/out-of-range inputs,
confidence calibration, all-cash handling, AI isolation, and FinancialEngine integration.
`app/src/test/java/com/example/DealAnalysisOrchestratorTest.kt` covers the orchestration
contract: repeated and locale-independent determinism, provenance-record-order invariance,
mapping equality with a direct standalone-engine evaluation, missing-data attenuation (scores
approach neutral and confidence falls), provenance filtering (AI-inference tiers dropped,
trusted tiers scored, portals never treated as seller channels, rent/comp coverage hints),
AI isolation (no numeric override surface in the public API, AI observations stay display-only),
and facade/contract/engine delegation equivalence.
`app/src/test/java/com/example/DealScoringArchitectureGuardTest.kt` enforces the purity
constraints: no Android/network/data-layer/AI imports in the scoring packages, no dependency on
the obsolete qualification scoring, and a facade that delegates rather than re-implements.
`app/src/test/java/com/example/PropertyUrlIntelligenceTest.kt` exercises the production adapter.
Qualification-boundary and locale-stability tests live in `app/src/test/java/com/example/QualificationEngineTest.kt`.
