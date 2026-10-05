#!/usr/bin/env python3
"""
Generates `app/src/test/resources/underwriting/golden_vectors.json`.

The vectors are the contract between the Python oracle (independent, exact
arithmetic) and the Kotlin engine (shipped production code). The Kotlin JUnit
suite replays every vector through the real engine via
`UnderwritingEngine.flatten(result)` and compares each expected path, so any
divergence between the two implementations fails the build loudly.

Run:  python3 generate_golden_vectors.py [--check]
      --check  verify the committed vectors are up to date (exit 1 if not)
"""

from __future__ import annotations

import json
import os
import sys

from oracle import DEFAULT_ASSUMPTIONS, analyze

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUTPUT_PATH = os.path.join(
    REPO_ROOT, "app", "src", "test", "resources", "underwriting", "golden_vectors.json"
)

# Paths deliberately excluded from the flat contract: they are either huge
# (month-by-month data) or covered by identity tests instead of golden values.
EXCLUDED_PREFIXES = (
    "brrrr.postRefiCapitalStack.issues",
    "hold.years",
    "brrrr.postRefiHold.years",
    "brrrr.schedule",
    "brrrr.bridgeProvenance",
    "operating.expenseLines",
)


def flatten(result):
    """Flat dotted-path view of a result dictionary.

    Lists of scalars stay lists (`verdict.passedCriteria`); lists of objects are
    indexed (`hold.years[0].cashFlow`) - though those are excluded above.
    """
    out = {}

    def walk(prefix, value):
        if any(prefix == p or prefix.startswith(p + ".") or prefix.startswith(p + "[")
               for p in EXCLUDED_PREFIXES):
            return
        if isinstance(value, dict):
            for key, child in sorted(value.items()):
                walk("%s.%s" % (prefix, key) if prefix else key, child)
        elif isinstance(value, list):
            if all(isinstance(x, (str, int, float, bool)) or x is None for x in value):
                out[prefix] = [_clean(x) for x in value]
            else:
                for index, child in enumerate(value):
                    walk("%s[%d]" % (prefix, index), child)
        else:
            out[prefix] = _clean(value)

    walk("", result)
    return out


def _clean(value):
    if isinstance(value, float):
        # strict JSON: no NaN/Inf can ever appear (the engine rejects them)
        if value != value or value in (float("inf"), float("-inf")):
            raise ValueError("non-finite value in result: %r" % value)
        return round(value, 10)
    return value


def rental(**over):
    base = dict(
        strategy="BUY_AND_HOLD", purchasePrice=400000, closingCosts=10000, rehabCost=0,
        monthlyRent=3600, otherMonthlyIncome=150, vacancyRatePct=5, creditLossRatePct=0,
        propertyTaxAnnual=4800, insuranceAnnual=2400, maintenancePctOfGsi=5,
        managementPctOfEgi=8, capexReservePctOfGsi=5, utilitiesMonthly=100,
        downPaymentPct=20, interestRatePct=6.75, loanTermMonths=360, amortizationMonths=360,
        holdYears=5, rentGrowthPct=3, expenseGrowthPct=3, appreciationPct=3.5, saleCostPct=8,
    )
    base.update(over)
    return base


def model_fixture(**over):
    base = rental()
    for key in ("downPaymentPct", "interestRatePct", "loanTermMonths",
                "amortizationMonths", "interestOnlyMonths"):
        base.pop(key, None)
    base.update(over)
    return base


def flip(**over):
    base = dict(
        strategy="FIX_AND_FLIP", purchasePrice=250000, rehabCost=45000, arv=400000,
        closingCosts=3750, flipHoldMonths=6, holdingCostsMonthly=0,
        rehabContingencyPct=10, sellClosingCostPct=8, financingModel="HARD_MONEY",
    )
    base.update(over)
    return base


def brrrr(**over):
    base = dict(
        strategy="BRRRR", purchasePrice=200000, rehabCost=45000, arv=340000,
        monthlyRent=3060, vacancyRatePct=5, propertyTaxAnnual=2400, insuranceAnnual=1200,
        closingCosts=3000, brrrrRehabMonths=5, holdYears=5, financingModel="HARD_MONEY",
    )
    base.update(over)
    return base


def wholesale(**over):
    base = dict(
        strategy="WHOLESALE", purchasePrice=300000, rehabCost=40000, arv=420000,
        assignmentFee=12000, earnestMoney=2000, marketingCost=500, daysToClose=45,
    )
    base.update(over)
    return base


VECTORS = [
    # ---------------- BUY_AND_HOLD ----------------
    ("hold_conventional_base", "Reference buy-and-hold on a 400k SFR, 20% down at 6.75%", rental()),
    ("hold_conventional_leveraged_10pct", "Same deal at 10% down", rental(downPaymentPct=10)),
    ("hold_all_cash", "All-cash purchase: no debt service, DSCR undefined", rental(loanAmount=0)),
    ("hold_zero_interest", "0% interest: payment degenerates to straight-line principal",
     rental(interestRatePct=0)),
    ("hold_explicit_loan_amount", "Explicit loan amount instead of a percentage",
     rental(loanAmount=250000, downPaymentPct=None)),
    ("hold_explicit_down_amount", "Explicit down-payment dollars",
     rental(downPaymentAmount=150000, downPaymentPct=None)),
    ("hold_conflicting_loan_and_down", "Loan amount wins, conflict flagged",
     rental(loanAmount=350000, downPaymentAmount=100000, downPaymentPct=None)),
    ("hold_over_financed_capped", "Requested 500k loan is capped by the 80% LTV ceiling",
     rental(loanAmount=500000, downPaymentPct=None)),
    ("hold_dscr_loan", "DSCR investor loan: 75% LTV, 7.50%, 6 months of reserves",
     model_fixture(financingModel="DSCR")),
    ("hold_hard_money_io", "Hard money: 85% LTC interest-only, rehab financed",
     model_fixture(financingModel="HARD_MONEY", rehabCost=40000)),
    ("hold_private_money_io", "Private money: 90% LTC interest-only",
     model_fixture(financingModel="PRIVATE_MONEY", rehabCost=40000)),
    ("hold_seller_financing_balloon", "Seller carry, 5-year balloon inside a 10-year hold",
     model_fixture(financingModel="SELLER_FINANCING", holdYears=10)),
    ("hold_negative_cash_flow", "Rent too low for the price: negative NOI and DSCR",
     rental(monthlyRent=1900, propertyTaxAnnual=7200, interestRatePct=7.5)),
    ("hold_full_vacancy", "100% vacancy: zero EGI, negative NOI",
     rental(vacancyRatePct=100)),
    ("hold_zero_vacancy", "0% vacancy", rental(vacancyRatePct=0)),
    ("hold_below_line_capex", "Capital reserves treated as a below-the-line reserve",
     rental(capexTreatment="BELOW_LINE_RESERVE")),
    ("hold_explicit_expense_lines", "Every operating expense supplied explicitly",
     rental(propertyTaxAnnual=5500, insuranceAnnual=1800, hoaMonthly=45,
            maintenanceAnnual=2600, managementAnnual=3300, utilitiesMonthly=120,
            landscapingMonthly=60, otherOperatingAnnual=400, capexReservePctOfGsi=0)),
    ("hold_no_growth", "Flat rents, flat expenses, flat value",
     rental(rentGrowthPct=0, expenseGrowthPct=0, appreciationPct=0)),
    ("hold_negative_growth", "Deflation: rents fall, expenses rise",
     rental(rentGrowthPct=-3, expenseGrowthPct=5, appreciationPct=-2)),
    ("hold_zero_hold_years_defaults", "holdYears=0 falls back to the documented default",
     rental(holdYears=0)),
    ("hold_extreme_rate", "300% interest over 40 years must stay finite",
     rental(interestRatePct=300, loanTermMonths=480, amortizationMonths=480)),
    ("hold_commercial", "5M commercial deal, 30% down, 7-year hold",
     rental(purchasePrice=5_000_000, monthlyRent=60_000, propertyTaxAnnual=60_000,
            insuranceAnnual=25_000, holdYears=7, downPaymentPct=30, closingCosts=100000)),
    ("hold_zero_rent", "Vacant with no income: everything floored, DSCR negative",
     rental(monthlyRent=0, otherMonthlyIncome=0)),
    ("hold_unknown_model_falls_back", "Unknown financing model falls back to CONVENTIONAL",
     rental(financingModel="NOT_A_MODEL")),
    # ---------------- FIX_AND_FLIP ----------------
    ("flip_base", "Standard 70%-rule flip bought with hard money", flip()),
    ("flip_deep_discount", "Deeply discounted purchase: strong flip", flip(purchasePrice=185000, arv=430000)),
    ("flip_loss", "ARV collapses: underwater flip", flip(arv=290000)),
    ("flip_high_contingency", "25% rehab contingency", flip(rehabContingencyPct=25)),
    ("flip_zero_months_defaults", "holdMonths=0 falls back to the documented default",
     flip(flipHoldMonths=0)),
    ("flip_requires_arv", "Missing ARV: calc refused, verdict FAILS_CRITERIA",
     flip(arv=None)),
    ("flip_cash_purchase", "All-cash flip: no loan, no points, no interest",
     flip(loanAmount=0, downPaymentPct=None, closingCosts=2500)),
    ("flip_minimum_interest_floor", "Hard money charges 3 months interest on a 1-month hold",
     flip(flipHoldMonths=1, arv=500000)),
    # ---------------- BRRRR ----------------
    ("brrrr_base", "Buy 200k / rehab 45k / ARV 340k, refi at 75% of ARV", brrrr()),
    ("brrrr_thin_deal", "Buy 300k / rehab 40k / ARV 420k: refi does not recover capital",
     brrrr(purchasePrice=300000, arv=420000, rehabCost=40000, monthlyRent=3100,
           closingCosts=4500, propertyTaxAnnual=3600, insuranceAnnual=1800)),
    ("brrrr_full_capital_recovery", "Deep-value BRRRR: all capital out, infinite CoC",
     brrrr(purchasePrice=150000, arv=380000, monthlyRent=3400)),
    ("brrrr_requires_arv", "Missing ARV: calc refused, verdict FAILS_CRITERIA",
     brrrr(arv=None)),
    ("brrrr_zero_rehab_months_defaults", "brrrrRehabMonths=0 falls back to the default",
     brrrr(brrrrRehabMonths=0)),
    # ---------------- WHOLESALE ----------------
    ("wholesale_assignment", "Assignment of a 300k contract with a 12k fee", wholesale()),
    ("wholesale_default_fee", "No fee supplied: defaults to 2% of ARV",
     wholesale(assignmentFee=None)),
    ("wholesale_double_close", "Double close: both sides of closing cost are paid",
     wholesale(wholesaleMode="DOUBLE_CLOSE")),
    ("wholesale_fee_too_big", "Fee eats the end buyer's margin: flagged",
     wholesale(assignmentFee=60000)),
    ("wholesale_default_arv", "No ARV supplied: defaults to 1.25x the contract price",
     wholesale(arv=None)),
    ("wholesale_low_fee", "Fee below the minimum profit assumption",
     wholesale(assignmentFee=2500)),
]


def contract_view(result):
    """The engine's *semantic* contract, with ordering removed.

    Validation and criterion lists are compared as sorted sets: the set of
    findings is contractual, the order they happen to be discovered in is not.
    That keeps both implementations honest about what they report without
    coupling them to the sequence of if-statements.
    """
    view = dict(result)
    view["validation"] = {
        "codes": sorted("%s:%s" % (issue["severity"], issue["code"]) for issue in result["validation"]),
    }
    verdict = dict(result["verdict"])
    verdict["passedCriteria"] = sorted(verdict["passedCriteria"])
    verdict["failedCriteria"] = sorted(verdict["failedCriteria"])
    view["verdict"] = verdict
    return view


def build():
    vectors = []
    for vector_id, description, payload in VECTORS:
        result = analyze(dict(payload))
        vectors.append({
            "id": vector_id,
            "description": description,
            "input": _json_input(payload),
            "expected": flatten(contract_view(result)),
        })
    document = {
        "specVersion": DEFAULT_ASSUMPTIONS["version"],
        "generatedBy": "tools/underwriting_oracle/generate_golden_vectors.py",
        "regenerate": "python3 tools/underwriting_oracle/generate_golden_vectors.py",
        "suite": "python3 tools/underwriting_oracle/selfcheck.py",
        "note": ("Every value below was produced by the independent exact-arithmetic oracle. "
                 "The Kotlin engine must reproduce them through UnderwritingEngine.flatten()."),
        "pathCount": sum(len(v["expected"]) for v in vectors),
        "vectors": vectors,
    }
    return document


def _json_input(payload):
    """Strict-JSON-safe copy of the input map (integers stay integers)."""
    out = {}
    for key, value in payload.items():
        if isinstance(value, float) and (value != value or value in (float("inf"), float("-inf"))):
            raise ValueError("non-finite input in vector: %s" % key)
        out[key] = value
    return out


def main():
    document = build()
    rendered = json.dumps(document, indent=1, sort_keys=False, allow_nan=False) + "\n"

    if "--check" in sys.argv:
        if not os.path.exists(OUTPUT_PATH):
            print("MISSING: %s" % OUTPUT_PATH)
            return 1
        with open(OUTPUT_PATH) as handle:
            existing = handle.read()
        if existing != rendered:
            print("STALE: %s must be regenerated" % OUTPUT_PATH)
            return 1
        print("golden vectors are up to date (%d vectors, %d paths)"
              % (len(document["vectors"]), document["pathCount"]))
        return 0

    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    with open(OUTPUT_PATH, "w") as handle:
        handle.write(rendered)
    print("wrote %s" % OUTPUT_PATH)
    print("vectors: %d   flat paths: %d   bytes: %d"
          % (len(document["vectors"]), document["pathCount"], len(rendered)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
