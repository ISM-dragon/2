# Deterministic Underwriting Engine — audit notes

**The rule this engine enforces: no financial figure in the app is ever produced
by a language model.** Gemini may write prose about a deal; every number is
computed by arithmetic that two independent implementations agree on.

---

## 1. What exists

| Piece | Path | Role |
| --- | --- | --- |
| Reference spec | `tools/underwriting_oracle/oracle.py` | The authoritative definition of every formula, default and threshold. Exact `Fraction` arithmetic; floating point only in the IRR bisection and JSON output. |
| Self-check suite | `tools/underwriting_oracle/selfcheck.py` | 402 assertions over the spec, including hostile fixtures (`total_loss_deal`, `negative_equity_multiple`, non-finite input sweeps). |
| Golden vectors | `tools/underwriting_oracle/generate_golden_vectors.py` → `app/src/test/resources/underwriting/golden_vectors.json` | 43 deals, 4,902 flat dotted paths. The acceptance contract for the Kotlin port. |
| Shipped engine | `app/src/main/java/com/example/domain/finance/underwriting/` (12 files) | The pure-Kotlin port the app calls. No Android dependencies. |
| Parity tests | `app/src/test/java/com/example/UnderwritingGoldenVectorsTest.kt`, `UnderwritingEdgeCasesTest.kt` | Replay every vector through the real engine and diff path by path; plus degenerate-input tests. |

The legacy `FinancialEngine` (`com.example.domain.finance.FinancialEngine`)
is **deprecated** and frozen for the standalone Analyzer screen only. It is not
the underwriting spec and its semantics (e.g. a `999.0` all-cash DSCR
sentinel) must not leak into new code. `UnderwritingEngine` is the single
financial source of truth: `FinancialRepository` computes every persisted,
automated and qualified figure through it, and the legacy `FinancialResult`
shape survives only as the deterministic projection in
`FinancialResultProjection`.

## 2. Verifying it

```bash
# 1. the specification's own suite — must print ALL CHECKS PASSED
cd tools/underwriting_oracle && python3 selfcheck.py

# 2. regenerate the contract after any intended semantic change
cd tools/underwriting_oracle && python3 generate_golden_vectors.py

# 3. fail if the committed vectors are stale
cd tools/underwriting_oracle && python3 generate_golden_vectors.py --check

# 4. the Kotlin port against the contract (needs a JDK + Android SDK)
./gradlew :app:testDebugUnitTest --tests 'com.example.Underwriting*'
```

`selfcheck.py` is the source of truth when the two disagree; the parity test
fails on a **missing or extra** metric path, not just a different value, so a
field silently dropped from the port cannot pass.

## 3. The contract

`UnderwritingFlatten.flatten(result)` emits one flat `Map<String, Any?>` per
deal. Rules that callers may rely on:

* Two sets are compared as **sets**: `validation.codes` (sorted
  `"SEVERITY:CODE"` strings) and `verdict.passedCriteria` / `failedCriteria`.
  Discovery order is not contractual.
* A metric that does not exist for a deal is emitted as **null**, never as a
  sentinel: an all-cash purchase has `core.dscr = null`, and a deal with no
  price has `core.capRateOnPricePct = null`. `brrrr.infiniteCashOnCash` is the
  one boolean.
* Nullable strategy blocks (`hold`, `brrrr`, `flip`, `wholesale`) are omitted
  when the deal cannot be evaluated; `returns` is always present (null when
  undefined).
* Deliberately **excluded** from the contract (volatile detail, contractual
  content is summarised elsewhere): `hold.years`, `brrrr.postRefiHold.years`,
  `brrrr.schedule`, `brrrr.bridgeProvenance`, `brrrr.postRefiCapitalStack.issues`,
  `operating.expenseLines`.

Top-level roots: `specVersion`, `strategy`, `financingModel`, `purchasePrice`,
`rehabCost`, `financing` (20 paths), `operating` (7), `core` (20), `capitalStack`
(7), one strategy block, `returns` (9), `verdict` (3), `validation`,
`assumptionsUsed`, `metricApplicability`.

## 4. Assumptions are part of the answer

`assumptionsUsed` maps every resolved field to
`{"source": "EXPLICIT" | "MODEL_DEFAULT" | "DERIVED"}`. A value the user did not
state can therefore never be mistaken for one they did. (`value` is carried on
the Kotlin record for display.) Defaults live in
`US_INVESTOR_DEFAULTS_2026_01`:

| Item | Default |
| --- | --- |
| Vacancy / credit loss | 5% / 0% of GSI (floored at 0) |
| Maintenance / management / capex | 5% of GSI / 8% of EGI / 5% of GSI (`ABOVE_LINE_IN_NOI`, switchable to `BELOW_LINE_RESERVE`) |
| Property tax / insurance | 1.20% / 0.60% of price |
| Closing costs (if unstated) | 1.5% of price |
| Hold / growth / appreciation / sale cost | 5 years / 3% / 3% / 3.5% / 8% |
| Flip | 6 months, 10% rehab contingency, 0.15%/month of price holding costs, thresholds 10% of ARV + 15% ROI of cash |
| Wholesale | $5,000 minimum profit and 50% ROI-of-cash threshold, buyer floor 10% of ARV, 2% of ARV default fee, 30 days to close |
| Verdict thresholds | DSCR 1.25, cash-on-cash 6%, cap rate 5% |

Financing models (down payment basis / rate / term / amortisation / IO / points
/ fees / closing %): Conventional 20% / 6.75% / 360 / 360 / — / 0 / $1,200 / 2.5%;
DSCR 25% / 7.50% / 360 / 360 / — / 1.5 / $1,500 / 2.0% (6 months reserves,
minimum DSCR 1.25); Hard money 15% of **cost** / 11.50% / 12 / — / 12 / 3.0 /
$2,500 / 1.5% (85% LTC, 70% of ARV, 3-month minimum interest); Private money 10%
of cost / 9.00% / 24 / — / 24 / 1.5 / $500 / 1.0% (90% LTC, 75% of ARV, 0%
lender profit share); Seller financing 10% of price / 6.00% / 60 balloon / 360 /
— / 0 / $500 / 1.0% (90% LTV).

Hard and private money are **loan-to-cost** products: they are sized on
purchase + financed rehab, never on purchase price alone.

## 5. Semantics worth knowing

* Payment: `M = P·r / (1 − (1+r)^−n)`; `r = 0` degenerates to `P/n`; a
  non-positive principal or term yields 0. A loan whose term is shorter than its
  amortisation balloons; each projection row carries `isBalloonMonth` /
  `assumesRefinance`, and debt maturing inside the projection triggers an
  explicit `REFINANCE_ASSUMED_AT_MATURITY` warning.
* `core.dscr` is `NOI / annual debt service`, and null when there is no debt.
  Negative NOI produces a negative DSCR plus `NEGATIVE_NOI` — never a clamp.
* Returns report both a linear `simpleAnnualizedRoiPct` and a compound
  `annualizedRoiPct`; the compound figure is **null** when the equity multiple
  is non-positive (a complex root is not a return), and flip/wholesale verdicts
  are judged on period ROI, not on annualised numbers.
* Loan sizing precedence: explicit `loanAmount` > `downPaymentAmount` >
  `downPaymentPct` > model default; every request is still capped by the
  tightest of LTV-of-price / LTC-of-cost / LTV-of-ARV, and the result reports
  which ceiling bound (`financing.bindingLoanCeiling`).
* BRRRR: the bridge loan pays off principal only (interest and points are
  already in phase-1 cash); the refinance is 75% of ARV at 7.5%/30 years with
  ~1.5 points + $1,500 + 2%; `cashLeftInDeal`, `capitalRecoveredPct` and
  `infiniteCashOnCash` describe the outcome, and a post-refi DSCR below 1.25
  raises `REFI_DSCR_SHORTFALL`.

## 6. Status and open items

* The oracle suite and the vector file are green and current
  (`402 PASSED / 0 FAILED`; `--check` reports "up to date": 43 vectors,
  4,902 paths).
* The Kotlin port and the parity tests **have not been compiled or executed in
  this environment** (no JDK, no network). Run the Gradle command in §2 before
  trusting them; the tests are written to fail loudly on any divergence.
* `PropertyRoiCalculatorViewModel` no longer asks the model for numbers: the
  engine computes the metrics, the model is asked for qualitative commentary
  only, and `RoiMetricsCalculator.withNarrative` is the single, prose-only code
  path from model output into the screen model. Its persistence call now
  stores the exact canonical `UnderwritingInput` that rendered the screen
  (`FinancialRepository.underwriteProperty`), so the saved row and the
  displayed metrics can never disagree.
* `FinancialRepository` is fully migrated to the canonical engine: automated
  runs are assembled by `PropertyUnderwritingFactory`, which uses observed
  data (rent estimates, tax records, HOA) exactly and reports every missing
  observation with an explicit validation finding (`MISSING_RENT_ESTIMATE`,
  `MISSING_PROPERTY_TAX_RECORD`, ...) instead of the old silent fallbacks
  (0.8%-of-price rent, 1.2%-of-price taxes, $35k/$5k source-type renovation).
  Remaining defaults are the named assumptions in `UnderwritingAssumptions` /
  `FinancingModelDefaultsRegistry`. Persisted comparison scenarios are the
  same deal re-run under every declared financing model. Regression and
  edge-case coverage lives in `FinancialRepositorySourceOfTruthTest`.

## Independent accuracy audit (2026-10-09)

See [financial and data accuracy audit](financial-data-accuracy-audit.md) for the
independent scenario matrix, actual execution results, and unresolved defects.
Oracle/port parity is **not** proof of economic correctness. In particular,
wholesale missing-ARV handling, below-line reserves and financed-flip MAO remain
open. The Deal Room still calls a separate `DeterministicFinancialEngine`, so the
repository-wide single-source-of-truth language above is not a verified guarantee.
The audit corrects candidate-price holding costs in the flip MAO solver without
changing named model defaults. JVM execution remains outstanding.
