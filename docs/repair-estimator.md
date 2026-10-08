# Repair Estimator

A standalone, deterministic rehab (repair) estimator for US residential property, used for wholesale, BRRRR and fix-and-flip analysis.

It turns a list of scope lines into an itemised, auditable rehab total. Every number comes from arithmetic on supplied inputs and a named cost profile, and every number states where it came from.

Code: `repairestimator/` (Gradle module `:repairestimator`, package `com.example.repairestimator`).

## What it is not

- **Not a market value.** It does not compute ARV, comps, resale price, rent or any value that depends on a market. Those belong to other layers.
- **Not an inspector.** It does not know a roof's condition, a system's age or a room's size unless the caller supplies it. It never guesses them.
- **Not financing.** No holding costs, loan costs, interest or closing costs.
- **Not connected to the app.** The Android app does not depend on this module yet. Wiring it into a screen or repository is a separate change. `FinancialRepository`, `UnderwritingEngine`, the DealRoom UI and the Room entities are unchanged.

## Hard rules

| Rule | How it is enforced |
| --- | --- |
| Independent of AI, network and database | Module has no runtime dependencies. `ArchitectureGuardsTest` forbids Android, app, persistence, network, AI and clock/random imports in `src/main`. |
| Deterministic | No clock, randomness or environment access. Input order does not change output (lines are processed in canonical order). `estimate()` is a pure function: equal inputs give an equal `RepairEstimate`. |
| No invented market value | No market value is computed or accepted. |
| No pretended knowledge of condition or quantity | A missing or `UNKNOWN` condition is inspection-required and unpriced, never $0 and never guessed. A missing quantity leaves the line unpriced. |
| Transparent provenance | Each value records its source (`CALLER_INPUT`, `CALLER_OVERRIDE`, `PLACEHOLDER_PROFILE`, `CALLER_PROFILE`, `STRATEGY_POLICY`, `ENGINE_RULE`, `NOT_SUPPLIED`, `NOT_APPLICABLE`). Each line has a human-readable formula. |

## Strategies

| Strategy | Meaning in this estimator | Built-in profile policy |
| --- | --- | --- |
| `WHOLESALE` | The scope and cost an end buyer should expect, priced with a higher contingency because the wholesaler usually has limited inspection access. | 15% contingency, finish grade 1.00 |
| `BRRRR` | Durable, rent-ready finishes. | 10% contingency, finish grade 0.90 (placeholder) |
| `FIX_AND_FLIP` | Resale-grade finishes. | 10% contingency, finish grade 1.00 |

The strategy changes only the **finish grade** (applied to finish items) and the **contingency percentage**. Physical trade work costs the same under every strategy. A roof does not change because the strategy changed.

## Categories

Reported in this order in every estimate. Trade categories are physical scope. The rest are cross-cutting or derived.

| Category | Kind | Trade? |
| --- | --- | --- |
| Roof | `ROOF_COVERING` | yes |
| HVAC | `HVAC_SYSTEM`, `HVAC_DUCTWORK` | yes |
| Plumbing | `PLUMBING_REPIPE`, `PLUMBING_FIXTURE`, `WATER_HEATER` | yes |
| Electrical | `ELECTRICAL_PANEL`, `ELECTRICAL_REWIRE` | yes |
| Kitchen | `KITCHEN_CABINETS`, `KITCHEN_COUNTERTOPS`, `KITCHEN_APPLIANCES` | yes |
| Bathrooms | `BATHROOM_FULL`, `BATHROOM_HALF` | yes |
| Flooring | `FLOORING_HARD_SURFACE`, `FLOORING_CARPET` | yes |
| Paint | `PAINT_INTERIOR` | yes |
| Windows | `WINDOW_REPLACEMENT` | yes |
| Exterior | `EXTERIOR_SIDING`, `EXTERIOR_PAINT`, `EXTERIOR_GUTTERS` | yes |
| Foundation | `FOUNDATION_CRACK_REPAIR`, `FOUNDATION_PIER_SUPPORT` | yes |
| Permits | `PERMIT_FEES` (rule or explicit) | no |
| Labor | `LABOR_HOURS` (explicit), `GENERAL_CONDITIONS` (rule or explicit) | no |
| Contingency | `CONTINGENCY` (rule only) | no |
| Unknown / inspection required | `UNKNOWN_SCOPE_ALLOWANCE` (explicit), `UNKNOWN_CONDITION_RESERVE` (rule, off by default) | no |

### Coverage

A trade category with no supplied line is reported as `NOT_COVERED`. It is **not assessed**, and it adds nothing to the total. The estimate is then **incomplete**, and the missing category is listed. A line marked `EXCELLENT` covers its category at zero cost, which is different from not assessing it.

## Scope lines

```kotlin
RepairScopeLine(
    id = "roof-1",                       // unique; must not start with "auto:"
    kind = RepairItemKind.ROOF_COVERING,
    condition = ConditionLevel.POOR,     // null means not supplied (inspection required)
    conditionBasis = EvidenceBasis.INSPECTED,
    quantity = 1800.0,
    unit = MeasureUnit.SQUARE_FOOT,      // converted to SQUARE (100 SF per square)
    quantityBasis = EvidenceBasis.MEASURED,
    unitCostOverride = null,             // replaces the profile unit cost for this line
    permitRequired = null,               // null uses the kind default
    evidence = "inspection report p.4",
)
```

### Conditions

| Level | Meaning | Intensity (share of full replacement) |
| --- | --- | --- |
| `EXCELLENT` | Updated or like-new. No work. | 0.00 |
| `GOOD` | Serviceable, minor wear. Service only. | 0.10 (placeholder) |
| `FAIR` | Worn, partly failing. Partial repair. | 0.40 (placeholder) |
| `POOR` | Deteriorated. Full replacement. | 1.00 |
| `FAILED` | Non-functional or hazardous. Replacement plus tear-out. | 1.25 (placeholder) |
| `UNKNOWN` | Not inspected or not supplied. Never priced. | not applicable |

Intensities are engine assumptions and come from the profile. They are not market data.

### Evidence basis

`EvidenceBasis` is `INSPECTED`, `MEASURED`, `ESTIMATED`, `ASSUMED` or `NOT_STATED`. The engine records what the caller said and never upgrades a value. `ASSUMED` raises a warning, so an assumption is never mistaken for an observation.

### Units

Each kind has one pricing unit. Only these conversions are defined. Anything else is rejected.

| From | To | Factor |
| --- | --- | --- |
| `SQUARE_FOOT` | `SQUARE` | 0.01 (roofing only) |

| Kind family | Pricing unit |
| --- | --- |
| Roof covering | `SQUARE` (accepts `SQUARE_FOOT`) |
| Linear items (ductwork, repipe, cabinets, gutters) | `LINEAR_FOOT` |
| Area items (rewire, countertops, flooring, interior paint, siding, exterior paint) | `SQUARE_FOOT` |
| Per-item (system, panel, water heater, fixture, appliance package, bath, window, crack, pier) | `EACH` |
| Labor | `HOUR` |
| Lump sums (general conditions, permit fees, allowances) | `LUMP_SUM` |

Paint is priced per square foot of **floor area** (walls and ceilings). Exterior paint and siding are priced per square foot of paintable or wall area.

## Pricing rules

### Trade lines (`TRADE` kinds)

1. **Known condition, no override.** `amount = quantity x base x finishGrade x intensity`, rounded to cents.
   - `finishGrade` is the strategy's finish multiplier. It applies only to finish items (kitchen, bathrooms, flooring, interior paint). Other kinds use 1.
   - `intensity` comes from the condition table, for example POOR is 1.00.
2. **Known condition, EXCELLENT.** Intensity 0, so the line is `NO_WORK_REQUIRED` and costs $0. No quantity is needed.
3. **Known condition, caller override.** `amount = quantity x override`. The override replaces the profile unit cost, and the intensity and finish grade are bypassed. The line still needs a quantity.
4. **UNKNOWN or missing condition, no override.** `UNPRICED`, with reason `CONDITION_NOT_SUPPLIED` or `CONDITION_UNKNOWN` and an `INSPECTION_REQUIRED` warning.
5. **UNKNOWN condition with override and quantity.** This is a caller **allowance** for uninspected work. It is priced, status `ALLOWANCE_UNVERIFIED`, flagged in `completeness.unverifiedAllowanceLineIds`, and counted in the trade subtotal. Contingency applies to it, because the work is identified even though its condition is not.
6. **Missing quantity** on a line that needs one: `UNPRICED` with `QUANTITY_NOT_SUPPLIED`.

### Labor

- `LABOR_HOURS`: `hours x rate`. The rate is the profile rate, or the caller's `unitCostOverride` (an hourly rate). Labor is not condition-based.
- `GENERAL_CONDITIONS` (rule): `generalConditions.pct` of priced trade work. Applies when there is priced trade work, and is `POLICY_NOT_APPLICABLE` otherwise.
- `GENERAL_CONDITIONS` (explicit): a caller lump sum (`unitCostOverride` is the dollar amount). It **replaces** the rule and the rule is suppressed, with a `POLICY_SUPPRESSED` note.

### Permits

- The **permit base** is the sum of priced trade lines whose `permitRequired` is true. The default is per kind (roof, HVAC system, repipe, water heater, panel, rewire, crack repair, pier support). A line can override it.
- Permit fee (rule) = `max(minimumFee, pct x permit base)`. The built-in profile uses 1.5% with a $150 minimum.
- If any permit-triggering line is unpriced, `PERMIT_SCOPE_INCOMPLETE` is raised: the fee covers only the priced portion.
- Explicit `PERMIT_FEES` (lump sum) **replaces** the rule and needs an amount. Several explicit lines add up.

### Unknown-condition reserve

Off by default (0%). When enabled and at least one trade line is inspection-required, the reserve is `pct x priced trade work`. Unpriced lines are never part of the base.

Inspection-required work is therefore **not priced silently**. The total stays incomplete until the caller inspects the item or supplies an allowance.

### Unknown-scope allowance

`UNKNOWN_SCOPE_ALLOWANCE` is a caller lump sum for work that has not been identified at all (for example "hidden structural, allowance $2,500"). It is priced, status `ALLOWANCE_UNVERIFIED`, and counted in the total. It is **not** part of the contingency base. Contingency protects the identified scope. A caller who wants a markup on the allowance can raise the contingency percentage for the estimate.

### Contingency

- `contingency = pct x contingencyBase`, where `contingencyBase = trade subtotal + labor subtotal`. Labor includes the general-conditions amount.
- Permit fees, the reserve and unknown-scope allowances are **not** in the base.
- `pct` is the strategy's contingency (10% for BRRRR and fix-and-flip, 15% for wholesale in the built-in profile). A request can override it with `contingencyPctOverride` (0 to 100), which is reported as `CONTINGENCY_OVERRIDDEN`.

### Totals

```
totalRehab = tradeSubtotal + laborSubtotal + permitSubtotal + unknownScopeSubtotal + contingencyAmount
```

Every part is the sum of line amounts, so the total reconciles to the cent.

### Completeness

`completeness.isComplete` is true only when every line is priced **and** every trade category is covered. `completeness.reasons` says why not. Unverified allowances are listed separately and do not make the estimate incomplete on their own, because they are priced.

## Rounding and arithmetic

- All arithmetic uses `BigDecimal`. Doubles are converted with `BigDecimal.valueOf`, which uses the shortest decimal that round-trips. So `0.1` stays exactly `0.1`.
- Each line amount rounds to cents with `HALF_UP`, once.
- Percentages convert with `movePointLeft(2)`, which is exact. A division would round to the dividend's scale and could silently lose digits.

## Worked example

Built-in profile, `BRRRR`:

| Line | Inputs | Calculation | Amount |
| --- | --- | --- | --- |
| Roof | POOR, 1,800 SF | 18 SQ x $650.00 x 1.00 | $11,700.00 |
| Cabinets | FAIR, 20 LF | 20 x $450.00 x 0.90 x 0.40 | $3,240.00 |
| Full bath | GOOD, 1 EA | 1 x $15,000.00 x 0.90 x 0.10 | $1,350.00 |
| HVAC | condition not supplied | not priced: inspection required | unpriced |
| Allowance, hidden scope | caller amount $2,500 | 1 LS x $2,500.00 | $2,500.00 |
| Labor | 12 HR | 12 x $65.00 | $780.00 |
| General conditions (rule) | 5% of trade $16,290.00 | | $814.50 |
| Permit fee (rule) | max($150, 1.5% of $11,700) | | $175.50 |
| Contingency (rule) | 10% of $17,884.50 | trade + labor | $1,788.45 |

Trade subtotal $16,290.00, labor $1,594.50, permits $175.50, unknown-scope $2,500.00, contingency $1,788.45. **Total rehab $22,348.45**, but the estimate is **incomplete**: the HVAC line is unpriced and seven trade categories are not assessed.

## Cost profiles

A profile is the complete set of numbers the estimator uses. Changing a profile changes the estimate and nothing else does.

- `unitCosts`: installed cost per pricing unit for every trade kind and for `LABOR_HOURS`. Each carries a reference.
- `conditionIntensity` and optional `kindIntensityOverrides`: must not decrease as condition worsens.
- `strategyPolicies`: contingency and finish grade for all three strategies.
- `permitPolicy`, `generalConditions`, `unknownConditionReserve`: rule parameters.

`CostProfileValidator` checks every rule and reports all problems at once. A profile with an error rejects the request.

### Built-in placeholder: `US_RESIDENTIAL_PLACEHOLDER_2026`

**This profile is an uncalibrated planning placeholder.** Every estimate that uses it carries a `PLACEHOLDER_PROFILE` warning. Before relying on a total, replace the unit costs with local bids.

- Unit costs are midpoints of the 2026 national installed ranges below, each in the profile's `reference` field.
- The intensities, finish grades, general conditions (5%), permit minimum ($150), and the 0% reserve are **engine assumptions** with no market source.
- The contingency percentages are 10% for BRRRR and fix-and-flip, matching `UnderwritingAssumptions.REHAB_CONTINGENCY_PCT` in the app, and 15% for wholesale (engine assumption).

Sources (accessed while the profile was built):

| Kind | Used | Range cited | Source |
| --- | --- | --- | --- |
| Roof covering | $650 / SQ | $550-$800/SQ architectural asphalt | [buildvisionai.com](https://www.buildvisionai.com/roof-replacement-cost) |
| HVAC system | $8,500 / EA | $5,000-$12,500 furnace plus AC | [vannuyshvacpro.com](https://vannuyshvacpro.com/blog/how-much-does-it-cost-to-replace-furnace-and-ac/); [candcair.com](https://candcair.com/blog/hvac-replacement-cost/) |
| HVAC ductwork | $40 / LF | $20-$60/LF | [hvacprojectcost.com](https://hvacprojectcost.com/ductwork-replacement-cost/); [pipelineon.com](https://pipelineon.com/blog/hvac-ductwork-pricing/) |
| Repipe | $5.25 / LF | $3.50-$7.00/LF PEX installed | [ibuyer.com](https://ibuyer.com/blog/how-much-does-it-cost-to-replumb-a-house/) |
| Plumbing fixture | $350 / EA | toilet $350, faucet $250, vanity sink $500 averages | [currentcost.org](https://currentcost.org/plumbing-prices-per-fixture-cost-u-s-homeowners/) |
| Water heater | $1,500 / EA | 40-50 gal tank $882-$2,000 | [rateyourplumber.com](https://rateyourplumber.com/guides/water-heater-replacement-cost); [g4electrical.com](https://www.g4electrical.com/replace-water-heater-cost/) |
| Electrical panel | $2,500 / EA | 200-amp $1,500-$4,000 | [ibelectric.com](https://ibelectric.com/cost-to-replace-an-electrical-panel/); [nearmetips.com](https://www.nearmetips.com/electrical-panel-replacement-cost/) |
| Rewire | $6.00 / SF | $3-$9/sq ft; $6-$15/sq ft | [caudills.com](https://caudills.com/home-rewiring-cost-guide/); [baltimorechronicle.com](https://baltimorechronicle.com/society/how-much-to-rewire-a-house-2026/) |
| Cabinets | $450 / LF | semi-custom $250-$720/LF | [estimationpro.ai](https://estimationpro.ai/tools/kitchen-cabinet-cost-calculator); [drcabinet.com](https://drcabinet.com/how-much-to-replace-kitchen-cabinets/) |
| Countertops | $100 / SF | quartz $50-$150/sq ft | [msisurfaces.com](https://www.msisurfaces.com/blogs/post/2025/09/01/how-much-should-you-expect-to-pay-for-quartz-countertops.aspx); [kitchenremodelingranked.com](https://kitchenremodelingranked.com/blog/kitchen-countertop-cost-per-square-foot-2026) |
| Appliance package | $3,750 / EA | $2,100-$5,400 | [homeguide.com](https://homeguide.com/costs/appliances-prices) |
| Full bath | $15,000 / EA | mid-range $10,000-$25,000; average $12,400 | [costtobuildhouse.com](https://www.costtobuildhouse.com/bathroom-remodel-calculator); [costprism.com](https://costprism.com/guides/bathroom-remodel-cost-guide/) |
| Half bath | $6,750 / EA | $3,500-$10,000 | [costtobuildhouse.com](https://www.costtobuildhouse.com/bathroom-remodel-calculator) |
| Hard-surface flooring | $6.00 / SF | LVP $3-$9/sq ft installed | [realcostiq.com](https://realcostiq.com/lvp-flooring-calculator/) |
| Carpet | $4.50 / SF | mid-grade $4.50; range $3-$6 | [carpetnow.com](https://carpetnow.com/how-much-does-it-cost-to-install-new-carpet/) |
| Interior paint | $4.00 / SF floor area | walls and ceilings $3-$5 per sq ft floor area | [clearhomeprojects.com](https://clearhomeprojects.com/painting/interior-painting-cost-calculator/) |
| Windows | $975 / EA | vinyl $650-$1,300; $800-$1,600 | [simplywise.com](https://www.simplywise.com/blog/cost-to-replace-windows/); [pella.com](https://www.pella.com/ideas/windows/replacement-window-cost/) |
| Siding | $6.00 / SF | mid-range vinyl $4-$8/sq ft | [amazingexteriors.com](https://amazingexteriors.com/feeds/blog/vinyl-siding-installation-cost) |
| Exterior paint | $3.00 / SF | $1.50-$4.50/sq ft | [facadecolorizer.com](https://facadecolorizer.com/us/blog/exterior-paint-cost-2026-complete-guide) |
| Gutters | $11.00 / LF | seamless aluminum $9-$13/LF; $6-$14/LF | [bigriverroofs.com](https://www.bigriverroofs.com/aluminum-gutter-installation-cost-a-complete-price-guide/); [gutters4lessga.com](https://www.gutters4lessga.com/how-much-does-gutter-installation-cost) |
| Foundation crack | $525 / EA | $250-$800 per crack | [servicover.com](https://servicover.com/pages/foundation-repair.html) |
| Foundation pier | $2,500 / EA | steel $1,500-$3,500; helical $2,000-$4,000 | [twobrosfoundationrepair.com](https://www.twobrosfoundationrepair.com/research/foundation-repair-cost) |
| Labor | $65 / HR | handyman national $50-$80/hr | [kickbackservices.com](https://kickbackservices.com/how-much-does-a-handyman-cost-per-hour) |
| Permit fee | 1.5%, $150 minimum | typically 1%-2.5% of construction value | [permitmint.com](https://permitmint.com/calculator.php); [goldenstatede.com](https://goldenstatede.com/how-much-does-a-building-permit-cost/) |

## Output

`RepairEstimateOutcome` is either:

- `Estimated(estimate)`: a `RepairEstimate` with `lines` (sorted by category, kind, id), `categories` (one per category, in order), `totals`, `completeness`, `assumptions` (sorted by key, each with source and reference), `issues`, and the profile identity.
- `Rejected(issues)`: validation errors only. No total is produced, so a partial figure cannot leak out.

### Validation errors (reject the request)

| Code | Meaning |
| --- | --- |
| `EMPTY_SCOPE` | No lines. |
| `TOO_MANY_LINES` | More than 500 lines. |
| `BLANK_LINE_ID`, `DUPLICATE_LINE_ID`, `RESERVED_LINE_ID` | Line ids must be unique, non-blank and must not start with `auto:`. |
| `POLICY_KIND_NOT_SUPPLIABLE` | `CONTINGENCY` and `UNKNOWN_CONDITION_RESERVE` are calculated, not entered. |
| `INVALID_QUANTITY` | Must be finite, greater than 0, and at most 1,000,000. |
| `INVALID_UNIT_COST` | Must be finite, at least 0, and at most 10,000,000. |
| `UNIT_NOT_ACCEPTED` | The unit does not convert to the kind's pricing unit. |
| `EXPLICIT_AMOUNT_REQUIRED` | An explicit `GENERAL_CONDITIONS` or `PERMIT_FEES` line needs an amount. |
| `INVALID_CONTINGENCY_OVERRIDE` | Must be finite and between 0 and 100. |
| `PROFILE_INVALID` | A profile rule failed (see `CostProfileValidator`). |

### Warnings and information (estimate still produced)

| Code | Meaning |
| --- | --- |
| `PLACEHOLDER_PROFILE` | The built-in uncalibrated profile was used. |
| `INSPECTION_REQUIRED` | A trade line has no known condition and is unpriced. |
| `QUANTITY_NOT_SUPPLIED` | A line needs a quantity and has none. |
| `ALLOWANCE_NOT_SUPPLIED` | An unknown-scope allowance has no amount. |
| `ALLOWANCE_UNVERIFIED` | A caller allowance is in the total but not inspected. |
| `CONDITION_ASSUMED`, `QUANTITY_ASSUMED` | The caller marked a value as assumed. |
| `OVERRIDE_ON_EXCELLENT` | A caller cost is applied to a line marked EXCELLENT. |
| `FIELD_NOT_APPLICABLE` | `condition` or `permitRequired` was given for a kind where it does not apply, and is ignored. |
| `PERMIT_SCOPE_INCOMPLETE` | Permit-triggering lines are unpriced, so the permit fee is understated. |
| `CATEGORY_NOT_COVERED` | A trade category has no line and is not assessed. |
| `OVERRIDE_APPLIED`, `UNIT_CONVERTED`, `LUMP_SUM_QUANTITY_DEFAULTED`, `PERMIT_REQUIRED_OVERRIDDEN`, `POLICY_SUPPRESSED`, `CONTINGENCY_OVERRIDDEN` | Information about how a value was derived. |

## Determinism and verification

Tests are in `repairestimator/src/test/kotlin/com/example/repairestimator/`:

- **Catalog and profiles**: every kind, unit, category, conversion and profile rule.
- **Validation**: every request and profile error, with boundary values on both sides.
- **Trade pricing**: every trade kind, every known condition and every strategy (330 combinations), checked against an independent `BigDecimal` calculation. Also monotonicity, linearity, finish-grade scope and rounding.
- **Conditions, units and overrides**: conversions, missing data, allowances and explicit rule replacements.
- **Policy rules**: general conditions, permit base and minimum, reserve, contingency base and strategy selection.
- **Completeness, coverage and strategies**: hand-checked totals for each strategy.
- **Provenance and formulas**: exact formula strings, sorted assumptions, and recomputation of every amount.
- **Reconciliation and determinism**: totals equal the sum of parts to the cent, input order does not matter, and concurrent evaluations agree.
- **Invariant grids**: monotonicity over quantities, conditions, contingency and unit costs.
- **Architecture guards**: no forbidden imports, clock, randomness, environment, network, persistence, AI or secrets in `src/main`; no runtime dependencies in the module.

Run them with:

```
./gradlew :repairestimator:test
```

`:repairestimator` is **not** part of the existing CI workflow, which runs only `:app:testDebugUnitTest`. Add `:repairestimator:test` to CI to gate changes to this module.

Outside the repository, the arithmetic was also cross-checked against an independent reference implementation written from this document. It generated seeded random scopes covering every kind, condition, unit conversion, override, allowance, rule replacement and strategy, and compared every line status, amount and total. That check is a development aid and is not committed.
