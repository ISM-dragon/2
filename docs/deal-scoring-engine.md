# Deal Scoring Engine

Standalone, deterministic, explainable deal scoring for RealEstateAI.

Location: `app/src/main/java/com/example/domain/scoring/`
Pure Kotlin. No Android, Room, network, clock, randomness, or AI dependencies.
The UI layer is untouched; scoring is consumed as a plain function call.

## Scores produced

| Score | Range | Meaning |
|---|---|---|
| **Deal Score** | 0..100 | Weighted composite of the participating subscores. |
| **Cash Flow Score** | 0..100 | Cap rate, cash-on-cash, monthly cash flow, DSCR. |
| **Equity Score** | 0..100 | Instant equity vs market value, ARV spread (70%-rule style), price vs area median. |
| **Market Score** | 0..100 | Appreciation, demand level, area days-on-market, $/sqft vs area. |
| **Risk Score** | 0..100 | Safety direction: **100 = lowest risk** (age, DSCR cushion, vacancy, rehab weight, rate, flood). |
| **Distress Score** | 0..100 | Opportunity signal: **100 = most distressed / motivated** (source channel, listing age, price cuts, tax delinquency). |
| **Data Confidence Score** | 0..100 | How much of the composite is backed by data + consistency/plausibility checks. |

## Guarantees

1. **Deterministic** — `evaluate(input, weights, config, asOfYear)` is a pure function.
   No `System.currentTimeMillis`, no randomness, no environment reads.
   Same arguments → identical `DealScoreResult` (data-class equality).
2. **Configurable weights** — `ScoringWeights` covers the composite and every component
   inside each subscore. All auto-normalized. Unknown component ids, zero-sum maps and
   negative values are rejected with `IllegalArgumentException` (typo protection).
3. **Explainable** — every subscore returns a full breakdown: raw score, coverage-attenuated
   effective score, normalized weight, contribution points, per-component value → threshold
   band → interpolated points, and typed POSITIVE / NEUTRAL / NEGATIVE reasons.
4. **AI isolation** — AI/LLM code can never change a score. There is no API accepting
   AI-produced numbers or overrides. `attachAiObservations(result, notes)` is the only
   AI-facing door: notes are sanitized, stored as display-only text, and every numeric
   field of the result stays byte-identical. All models are immutable `val` data classes
   (verified by reflection tests).

## Missing data model

Uncovered components never inflate scores. Each subscore tracks `coverage` (0..1);
the effective score is attenuated toward a neutral 50:

```
effective = neutral + (raw - neutral) * coverage ^ attenuationExponent   (neutral=50, exponent=1 by default)
```

The Data Confidence Score = weighted coverage, adjusted for source-quality hints
(rent-estimate provider confidence, comparable-count) minus consistency/plausibility
penalties. Warnings always list exactly which inputs were missing or inconsistent.

## Usage

```kotlin
val fin = FinancialEngine.calculate(financialInput)
val deal = DealInput(
    purchasePrice = fin.input.purchasePrice,
    monthlyCashFlow = fin.monthlyCashFlow,
    capRatePct = fin.capRate,
    cashOnCashPct = fin.cashOnCashReturn,
    dscr = fin.dscr,              // 999.0 all-cash sentinel from FinancialEngine is understood
    estimatedMarketValue = 250_000.0,
    yearBuilt = 2015,
    sourceType = property.sourceType,   // "FORECLOSURE" / "WHOLESALE" / ...
)

val result = DealScoringEngine.evaluate(deal)          // default weights
val custom = DealScoringEngine.evaluate(
    deal,
    ScoringWeights(cashFlow = 0.40, equity = 0.25, market = 0.10, riskSafety = 0.20, distressOpportunity = 0.05)
)

result.score            // 0..100 composite
result.subscores        // full breakdown per named subscore
result.weights          // effective configured + normalized weights used
result.reasons          // positive / neutral / negative explanation lists
result.warnings         // missing data, low coverage, inconsistencies
result.dataConfidence   // 0..100 trust in the data behind the score
```

## Tests

`app/src/test/java/com/example/DealScoringEngineTest.kt` — 34 unit tests covering:
quality separation, determinism, weight configurability/normalization/rejection,
breakdown integrity (contributions, coverage, band interpolation), missing-data
attenuation, data-confidence boosts/penalties, subscore semantics, reason polarity,
non-finite/extreme inputs, AI immutability & API surface, and wiring with
`FinancialEngine`.
