# Financial and property-data accuracy audit

**Audit date:** 2026-10-09 UTC  
**Base:** `43ac4cdf5995f42df9ac2eabb6257582a4347684`  
**Working branch:** `arena/2696b4f9-2` (session-fixed; requested `feature/arina-financial-data-validation` was not created)  
**Disposition:** NOT a clean financial or data-quality sign-off. One bounded correction; open high-severity findings and unexecuted JVM tests remain. No merge to main.

## Independence and evidence

Read the underwriting, scoring and repair contracts before editing, then inspected the engine, resolver, schedules, return projections, strategy analyzers, property adapter, provenance selection, comp persistence/import adapters, scoring entry points and repair pricing/validation code.

The existing Python oracle is useful for port parity but **is not independent evidence that the economic model is correct**. It shares economic assumptions and defects with Kotlin. New expectations in `tools/financial_audit/scenarios.py` do not import either implementation. They use Decimal arithmetic, annuity equations and a separate balance ledger. Their committed output is `app/src/test/resources/underwriting/independent_audit.json`, including equations. Only actual results come from the oracle/engine. Dollar tolerance is max($0.000001, |expected| × 1e-9); assertions are on unrounded metrics, not presentation strings.

All scenarios are **synthetic QA assumptions**. No property, provider price, rent, sale or contractor bid in this audit is claimed to be observed or market-validated. `ALL_CASH` is only an audit fixture label: the engine's five financing enums do not include cash. Cash fixtures use CONVENTIONAL with 100% down, zero points/fees and zero rate. Acquisition financing uses base rehab; flip contingency is an additional investor-funded budget in the current evaluator.

## Scenario matrix / independent calculations

All rental rows: price $200,000, closing $4,000, rehab $0, rent $2,400/month, vacancy 5%, tax $2,400/year, insurance $1,200/year. Other income/expenses/reserves are zero unless stated. One-year hold, zero growth/appreciation, 8% sale costs. Defaults here are explicit fixture assumptions, not market recommendations.

`GSI=2400×12=28,800; EGI=28,800×.95=27,360; NOI=27,360−3,600=23,760; cap=11.88%`.

For amortizing debt, `r=annualRate/1200`, `M=Lr/[1−(1+r)^−360]`. Independent monthly balance ledger: `B(t)=B(t−1)(1+r)−M`. IO uses `M=Lr` and constant principal. `CF=NOI−12M; DSCR=NOI/(12M)` (null for cash). `exit=184,000−B12; profit=CF+exit−cash`. Capital reserves at origination use PITI, not just P&I.

| Fixture | Financing / independently stated capital | Assertions |
|---|---|---|
| hold_all_cash | Loan $0; cash $204,000 | CF $23,760; DSCR null; cap 11.88%; profit $3,760 |
| hold_conventional | L=$160,000, 6.75%, fees $1,200; cash $45,200 | Payment, debt, CF, DSCR, CoC, exit balance, profit |
| hold_dscr | L=$150,000, 7.5%, points+fees $3,750 | Cash=$57,750+6×(M+$300); same operating/return checks |
| hold_hard_money | L=$170,000, 11.5% IO, fees $7,600; cash $41,600 | Loan, IO payment, CF, DSCR, exit principal |
| hold_private_money | L=$180,000, 9% IO, fees $3,200; cash $27,200 | Same checks |
| hold_seller_financing | L=$180,000, 6%, 360-month amortization / 60-month balloon; cash $24,500 | First-year debt and exit balance (before maturity) |
| wholesale_assignment | Contract $150k, explicit ARV $250k, buyer rehab $30k, fee $10k, earnest $1k, marketing $500 | Profit $9,500; buyer cost $190k; buyer profit $40k; max fee $25k; heuristic MAO $145k; reported ROI=9500/1500 |
| wholesale_double_close | Same; 1% each-side costs | Closing $1,500+$1,600=$3,100; profit $6,400. ROI remains a limited implementation convention, not funding feasibility |
| flip_cash | Price $150k, ARV $250k, rehab $20k + 10%, closing $3k, hold 6×$300, selling 8% | Rehab $22k; cost $176,800; profit $53,200; MAO70=$153k; target-profit offer $178,200; period ROI=53200/176800; annualized=(1+ROI)^2−1 |
| flip_mao_price_based_holding | Cash flip, holding omitted | Candidate `x`: `1.009x+22000+3000=250000×.92−25000`; **x=$178,394.449950446** |
| flip_hard_money | Base financed cost $170k; L=min(.85×170k,.70×250k)=$144,500 | Fees $6,835; interest $8,308.75; cost $191,943.75; cash $47,443.75; profit $38,056.25 |
| brrrr_all_cash | Price $150k, rehab $30k, closing $3k, carry $1,800; ARV $300k | Phase cash $184,800; zero-rate refi at 60% ARV=$180k; retained $4,800 |
| brrrr_hard_money | Bridge .85×$180k=$153k; fees $7,090; six-month interest $8,797.50 | Phase cash=$27k+$3k+$7090+$8797.50+$1800=$47,687.50; refi cashout $27k; retained $20,687.50 |

BRRRR refi assumptions deliberately simplify verification: zero rate, 360 months, zero points/fees/closing. Both rows use rent $2,500, no vacancy, tax $3k and insurance $1,200. Post-refi NOI=$25,800; debt=$6,000; CF=$19,800; DSCR=4.3. The fixtures test phase cash, payoff, cash recovery and post-refi metrics, **not the correctness of full-cycle BRRRR IRR**.

Additional checks: missing ARV for flip/BRRRR, non-finite values, negative price, zero-rate annuity, conflicting loan/down payment, ARV ceiling, ±$1 around MAO, explicit zero holding costs, rent/vacancy/rate sensitivity, −10% ARV (cash-flip profit −$23,000), +10% rehab (profit −$2,200), and +3 months carry (profit −$900). Kotlin adds a zero-rate balloon ledger: $12k over 12 months, maturity 6, five $1k payments then $7k; principal reconciles to $12k.

### Repair scope (new JVM tests, not executed here)

Caller-defined synthetic profile: roof $100/square, cabinets $400/linear foot; poor condition multiplier 1; general conditions 5% of priced trade; permit 10% of permit-triggering roof scope, minimum $50. Roof 1,800 SF /100 ×$100=$1,800. Cabinets 10×$400=$4,000, reduced by explicit .5 finish factor for BRRRR. Contingency applies to trade+labor, not permits.

- Wholesale: ($5,800+$290)×1.20+$180 = **$7,488**.
- BRRRR: ($3,800+$190)×1.10+$180 = **$4,569**.
- Flip: ($5,800+$290)×1.15+$180 = **$7,183.50**.
- Unknown condition without allowance stays **unpriced**, not a zero bid. Other unassessed categories make these partial scopes incomplete. Reversing lines must preserve the result.

These are test-profile policies, **not** the built-in placeholder rates. Existing repair tests cover invalid profiles/quantities, overrides, rounding, provenance and completeness. The standalone estimator is not wired into underwriting; no end-to-end repair transfer is claimed.

## Ranked defects and limitations

Severity: High can materially misstate value, spendable cash or deal comparability; Medium affects offer accuracy/evidence selection under narrower conditions. “Reproduced” below means executable Python behavior plus matching Kotlin source inspection, not executed Kotlin proof.

| ID | Severity / status | Evidence and impact |
|---|---|---|
| F02 | **High — OPEN, reproduced** | `StrategyAnalyzers.analyzeWholesale` / oracle use price×1.25 when ARV is missing. A $150k contract becomes an unsupported $187,500 ARV, affecting buyer profit, default fee and MAO. Flip/BRRRR correctly require ARV. The fallback is not a comp valuation. Expected-failure test requires no wholesale block absent ARV; a future contract could instead retain assignment cash economics while suppressing ARV-dependent metrics. |
| F03 | **High — OPEN, reproduced** | `OperatingStatementCalculator` removes below-line capex from NOI but never subtracts it from reported cash flow. `ReturnMetricsCalculator.project` likewise uses NOI−debt only. On the cash rental, a $1,440 reserve changes cash flow from $22,320 to $23,760 merely by moving presentation below NOI. If “cash flow” is intended to be pre-reserve, explicit after-reserve metrics and labels are needed; current CoC/returns cannot be treated as spendable cash. |
| F06 | **High — OPEN, source-inspected; characterization tests added** | `PropertyUnderwritingFactory` ignores tax assessment year and provider rent range/confidence; rent entity has no observation timestamp. Tax year 1990 and 2026 with same amount yield identical input/issues. Reversed rent bounds and 0 confidence still supply $3,200 rent without a conflict warning. Scoring uses source tiers but does not discount observation age. Freshness limits and an explicit as-of policy are needed; no threshold was invented in this patch. |
| F08 | **High — OPEN, source-inspected** | `domain/intelligence/engine/DeterministicFinancialEngine` is still called by `DealRoomViewModel` (calculate/recalculate paths). It has different rates, rent/repair/ARV guesses, and treats zero-rate debt service as zero rather than principal/term. Thus repository-wide financial single-source-of-truth claims are overstated. The scorer filters some inferred facts but does not reconcile these engines. Migration requires adapter/contract work, deferred here. |
| F01 | **Medium — FIXED, reproduced before/after** | MAO freezes default holding cost at original price. Original $150k cash scenario returned $178,650; re-underwriting it earned $24,742.15 instead of $25k. Correct candidate-price cost gives $178,394.449950446 and exactly $25k profit within tolerance. |
| F04 | **Medium — OPEN, reproduced** | MAO sizes the flip loan on contingency-inclusive rehab, while initial underwriting sizes on base rehab. Hard-money fixture MAO=$162,013.961606 re-underwrites to $25,148.75, not $25k. Solver and evaluator must use one declared financing budget. No contingency policy was silently changed. |
| F05 | **Medium — OPEN, reproduced** | MAO charges IO interest even when the deal evaluator sums declining-balance scheduled interest. Conventional 20%-down, 6.75% cash-flip variant yields MAO=$173,515.092502 and actual profit $25,010.159991. A single consistent financing-cost evaluator is needed. Principal-payment effects on peak cash/ROI also need a full cash ledger. |
| F07 | **Medium — OPEN, source-inspected; characterization test added** | `DataProvenanceManifest.getProvenanceFor` chooses first minimum-priority tier. Equal-tier conflicting records select different evidence when reordered, ignoring age/confidence. Higher-tier precedence is not a complete conflict-resolution policy. |

The four open executable economic assertions are deliberately `unittest.expectedFailure`, named F02–F05. They assert the desired invariant, not the broken value; an unexpected pass fails the suite so resolution must remove the marker. They do **not** mean those requirements pass. JVM freshness/conflict tests explicitly characterize current limitations and must change with remediation.

## Formula change and contract impact

Only F01 production behavior changed. In the private Kotlin MAO solver and Python helper:

```text
holding(candidate) = months × (explicitMonthlyHolding ?? candidatePrice × .0015)
```

Previously it used `originalPrice × .0015 × months` for every candidate. Explicit monthly costs, including zero, remain constant. No public Kotlin types, strategy thresholds, lender defaults, AI behavior, ARV assumptions or unrelated features changed. Python helper signature is retained for compatibility. Existing 43 golden vectors regenerate **byte-identically** because their coverage did not expose this default-holding case. Model-default version remains unchanged; this is a solver consistency bug fix, not a new assumption set. The new independent fixture prevents recurrence.

## Provenance / uncertainty assessment

| Classification | Actual handling / limitation |
|---|---|
| Observed fact | Tax/physical records require a source and observation period. Presence/finite-positive validation alone does not verify source, age or correctness. No external records verified here. |
| Provider estimate | Rent estimates are estimates, not observed lease income. Factory marks them explicit numeric inputs, losing estimate confidence/range at the underwriting boundary. |
| Named assumption | Underwriting defaults and repair profiles are identified; built-in repair costs explicitly warn they are uncalibrated placeholders. EXPLICIT / MODEL_DEFAULT / DERIVED describes calculation origin, not evidence quality. |
| AI inference | Scoring orchestrator drops fields explicitly tagged AI_INFERENCE; AI notes are prose-only. No AI integration was altered. Missing provenance is allowed through, so absence of a tag is not proof of non-AI origin. Existing AI-isolation tests were inspected, not run. |
| Unknown | Flip/BRRRR ARV is gated; repair unknown scope stays unpriced. Factory uses zero rent/rehab plus findings; consumers must not mistake these for observed zeros. Wholesale ARV violates this distinction (F02). |

### Comparable sales / ARV boundary

No executable comparable-sales valuation/adjustment engine was located in this checkout. `PropertyCompEntity` stores sold/active status, sale date, source, adjustment and similarity fields; DAO returns ranked stored rows. Import bridges currently produce empty comp lists. Scoring consumes `CompCoverage` hints, not independently verified sales. Entity comments mentioning a “valuation engine” do not establish an implementation.

Consequently this audit does **not** validate sale verification, active-listing exclusion, geographic/time filters, comparable deduplication economics, adjustment coefficients, outlier treatment, weighted ARV, or confidence intervals. There are no verified transactions or provider snapshots with which to test these. Explicit ARV in the fixtures is a scenario assumption. The 70% rule is a heuristic, not an appraisal or a calibrated MAO policy. No comp engine or backend feature was added to fill this gap.

## Actual test results

Executed in this environment after the change:

| Command | Result |
|---|---|
| `python3 -m unittest discover -s tools/financial_audit -v` | **12 tests: 8 pass, 4 expected failures**. Matrix contains 13 scenarios / 139 metric expectations. |
| `python3 tools/underwriting_oracle/selfcheck.py` | **402 passed**, ALL CHECKS PASSED |
| `python3 tools/underwriting_oracle/generate_golden_vectors.py --check` | Up to date: **43 vectors, 4,902 paths** |
| `git diff --check` | Passed |
| `./gradlew :app:testDebugUnitTest --tests 'com.example.UnderwritingIndependentAuditTest'` | **Not run: exit 127, `./gradlew` not found** |

Before the fix the new matrix and MAO re-underwriting tests failed ($255.55005 offer error, $257.85 target-profit miss); the four marked open defects also reproduced. Initial fixture authoring corrected the cash encoding, documented PITI reserve basis, JSON block names and base-rehab financing assumptions before the final expectations were established.

No JDK, Kotlin compiler, Gradle executable, Android SDK or Gradle wrapper was available on the inspected execution path. Outbound hosts do not include Google/Maven artifact repositories. **None of the new or existing Kotlin/JUnit suites was compiled or executed here.** Python success cannot certify Kotlin compilation, parity, Android integration, repair pricing or scoring runtime behavior.

New JVM coverage: `UnderwritingIndependentAuditTest` (3 methods), `IndependentRepairAuditTest` (2), and 5 provenance/staleness/unknown characterizations added to existing repository/scoring suites. Once the build toolchain is provisioned, run:

```sh
# Use gradle, or the wrapper if the project separately provisions one.
gradle :app:testDebugUnitTest --tests 'com.example.Underwriting*' \
  --tests 'com.example.FinancialRepositorySourceOfTruthTest' \
  --tests 'com.example.DealAnalysisOrchestratorTest' \
  --tests 'com.example.DealScoringEngineTest' \
  --tests 'com.example.DealScoringArchitectureGuardTest'
gradle :repairestimator:test
```

## Remaining validation boundaries

- Do not release on this audit as a clean sign-off; resolve high-severity findings and execute JVM tests.
- Full-cycle BRRRR timing/IRR, amortizing bridge payoff, reserve release at exit, double-close acquisition/transactional funding, lender profit sharing and maturity/refinance availability are not independently certified. Wholesale ROI omits acquisition funding; BRRRR reported phase-cash denominator and post-refi return basis need reconciliation.
- No real loan term sheets, contractor bids, rent rolls, tax bills, comparable transactions or source licenses were supplied. Default rates and cost tables are not market validation.
- Sensitivity checks are deterministic perturbations, not probabilities, Monte Carlo risk estimates or confidence intervals. Extreme finite input overflow and every override combination were not exhaustively explored.
- Commit SHA and draft PR URL are provided in the handoff / Git history; a file cannot embed its own eventual commit hash. No merge requested or performed.
