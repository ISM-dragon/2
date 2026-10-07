#!/usr/bin/env python3
"""
exact-arithmetic reference oracle for the Financial Underwriting Engine
======================================================================

This module is an INDEPENDENT implementation of the underwriting specification
that the Kotlin engine (`com.example.domain.finance.underwriting`) implements.

Why it exists
-------------
The Android unit-test runner cannot be executed in every environment (no JDK /
no network).  This oracle therefore plays two roles:

1. It is a second, independently written implementation of every formula.  It
   uses `fractions.Fraction` (exact rational arithmetic) wherever the spec is
   purely algebraic, so the *spec* itself is verified without floating point
   noise.
2. It generates `golden_vectors.json`, which the Kotlin JUnit suite replays
   against the real production code.  One fixture set, two implementations.

Division of labour
------------------
  spec (this file)  --generate-->  golden_vectors.json  --replay-->  Kotlin tests

Any disagreement between the two implementations is a defect in one of them;
the vectors make that disagreement loud instead of silent.

Only two places intentionally use floating point:
  * `irr_monthly` - root finding is inherently iterative (bisection, documented
    tolerance, fixed iteration count so it stays deterministic).
  * the final `to_float` conversion for JSON serialisation.

Nothing in this file performs I/O, network access, randomness, clock reads or
any model/AI inference.  Same input -> same output, forever.
"""

from __future__ import annotations

import math
from fractions import Fraction

# ---------------------------------------------------------------------------
# 0. primitives
# ---------------------------------------------------------------------------

ZERO = Fraction(0)
ONE = Fraction(1)
HUNDRED = Fraction(100)


def F(value) -> Fraction:
    """Exact conversion of a decimal literal/float/int into a Fraction.

    Non-finite floats are rejected outright: a NaN inside an underwriting model
    is a bug, never a number. `analyze()` sanitises inputs before this point.
    """
    if isinstance(value, Fraction):
        return value
    if isinstance(value, bool):
        raise TypeError("bool is not a number")
    if isinstance(value, int):
        return Fraction(value)
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        raise InputError("non-finite input rejected: %r" % value)
    return Fraction(str(value))  # via str -> exact for decimal literals


def pct_to_rate(pct) -> Fraction:
    return F(pct) / HUNDRED


def to_float(value) -> float:
    if isinstance(value, Fraction):
        return float(value)
    return float(value)


# ---------------------------------------------------------------------------
# 1. explicit assumption sets  (mirrors UnderwritingAssumptions.kt)
# ---------------------------------------------------------------------------

# Every value below is a *stated, overridable default* - not a market promise.
# Sources are recorded so an underwriter can audit or replace them.

DEFAULT_ASSUMPTIONS = {
    "version": "US_INVESTOR_DEFAULTS_2026_01",

    # --- operating expenses -------------------------------------------------
    "vacancyRatePct": "5.0",
    "creditLossRatePct": "0.0",
    "maintenancePctOfGsi": "5.0",
    "managementPctOfEgi": "8.0",
    "capexReservePctOfGsi": "5.0",
    "propertyTaxPctOfPrice": "1.20",
    "insurancePctOfPrice": "0.60",
    "capexTreatment": "ABOVE_LINE_IN_NOI",

    # --- growth / exit ------------------------------------------------------
    "holdYears": 5,
    "rentGrowthPct": "3.0",
    "expenseGrowthPct": "3.0",
    "appreciationPct": "3.5",
    "saleCostPct": "8.0",
    "flipHoldMonths": 6,
    "rehabContingencyPct": "10.0",
    "flipSellCostPct": "8.0",

    # --- lender / qualification thresholds ---------------------------------
    "minDscrForLender": "1.25",
    "minCashOnCashPct": "6.0",
    "minCapRatePct": "5.0",
    "flipMinProfitPctOfArv": "10.0",
    "flipMinRoiPctOfCash": "15.0",
    "flipMinAnnualizedRoiPct": "20.0",
    "wholesaleMinimumProfitAmount": 5000,
    "wholesaleMinRoiPctOfCash": "50.0",
    "brrrrTargetLtvOfArvPct": "75.0",
    "wholesaleBuyerMinProfitPctOfArv": "10.0",
    "wholesaleAssignmentFeePctOfArv": "2.0",
    "wholesalerMarketingCost": 500,
    "daysToCloseWholesale": 30,
    "holdingCostsMonthlyPctOfPrice": "0.15",
}

# Financing model defaults.  `ltc` = loan-to-cost of (purchase + rehab),
# `ltvArv` = loan-to-ARV ceiling (hard money / bridge), `ltv` = loan-to-purchase.
FINANCING_DEFAULTS = {
    "CONVENTIONAL": {
        "displayName": "Conventional (Fannie/Freddie investor)",
        "downPaymentPct": "20.0",
        "downPaymentBasis": "PRICE",
        "interestRatePct": "6.75",
        "loanTermMonths": 360,
        "amortizationMonths": 360,
        "interestOnlyMonths": 0,
        "originationPointsPct": "0.0",
        "lenderFees": 1200,
        "closingCostPctOfPrice": "2.5",
        "lenderReserveMonths": 0,
        "financesRehab": False,
        "financesClosingCosts": False,
        "maxLtvPct": "80.0",
        "minDscr": None,
        "lenderMinimumInterestMonths": 0,
    },
    "DSCR": {
        "displayName": "DSCR investor loan",
        "downPaymentPct": "25.0",
        "downPaymentBasis": "PRICE",
        "interestRatePct": "7.50",
        "loanTermMonths": 360,
        "amortizationMonths": 360,
        "interestOnlyMonths": 0,
        "originationPointsPct": "1.5",
        "lenderFees": 1500,
        "closingCostPctOfPrice": "2.0",
        "lenderReserveMonths": 6,
        "financesRehab": False,
        "financesClosingCosts": False,
        "maxLtvPct": "75.0",
        "minDscr": "1.25",
        "lenderMinimumInterestMonths": 0,
    },
    "HARD_MONEY": {
        "displayName": "Hard money / bridge",
        "downPaymentPct": "15.0",
        "downPaymentBasis": "COST",
        "interestRatePct": "11.50",
        "loanTermMonths": 12,
        "amortizationMonths": 0,          # 0 == interest only
        "interestOnlyMonths": 12,
        "originationPointsPct": "3.0",
        "lenderFees": 2500,
        "closingCostPctOfPrice": "1.5",
        "lenderReserveMonths": 0,
        "financesRehab": True,
        "financesClosingCosts": False,
        "maxLtcPct": "85.0",              # of cost (purchase + rehab) - the binding constraint
        "maxLtvPct": None,                # not constrained by purchase price
        "maxLtvArvPct": "70.0",           # of after-repair value
        "minDscr": None,
        "lenderMinimumInterestMonths": 3,
    },
    "PRIVATE_MONEY": {
        "displayName": "Private money",
        "downPaymentPct": "10.0",
        "downPaymentBasis": "COST",
        "interestRatePct": "9.00",
        "loanTermMonths": 24,
        "amortizationMonths": 0,
        "interestOnlyMonths": 24,
        "originationPointsPct": "1.5",
        "lenderFees": 500,
        "closingCostPctOfPrice": "1.0",
        "lenderReserveMonths": 0,
        "financesRehab": True,
        "financesClosingCosts": False,
        "maxLtcPct": "90.0",              # of cost (purchase + rehab)
        "maxLtvPct": None,                # not constrained by purchase price
        "maxLtvArvPct": "75.0",
        "minDscr": None,
        "lenderMinimumInterestMonths": 0,
        "lenderProfitSharePct": "0.0",
    },
    "SELLER_FINANCING": {
        "displayName": "Seller financing",
        "downPaymentPct": "10.0",
        "downPaymentBasis": "PRICE",
        "interestRatePct": "6.00",
        "loanTermMonths": 60,             # balloon / maturity
        "amortizationMonths": 360,
        "interestOnlyMonths": 0,
        "originationPointsPct": "0.0",
        "lenderFees": 500,
        "closingCostPctOfPrice": "1.0",
        "lenderReserveMonths": 0,
        "financesRehab": False,
        "financesClosingCosts": False,
        "maxLtvPct": "90.0",
        "minDscr": None,
        "lenderMinimumInterestMonths": 0,
    },
}

STRATEGIES = ("BUY_AND_HOLD", "BRRRR", "FIX_AND_FLIP", "WHOLESALE")
FINANCING_MODELS = tuple(FINANCING_DEFAULTS.keys())

# validation severities
ERROR = "ERROR"
WARNING = "WARNING"
INFO = "INFO"


class InputError(ValueError):
    """Raised for spec violations that make a calculation meaningless."""


# ---------------------------------------------------------------------------
# 2. amortisation
# ---------------------------------------------------------------------------

def amortizing_payment(principal, annual_rate_pct, term_months) -> Fraction:
    """Fully amortising monthly payment (P&I).

    Uses  P * r / (1 - (1 + r)^-n)  which is algebraically identical to the
    textbook  P * r * (1+r)^n / ((1+r)^n - 1)  but is numerically stable for
    large n (the (1+r)^n term never overflows: it decays to 0 instead).
    Zero interest degenerates to straight-line principal.
    """
    principal = F(principal)
    term_months = int(term_months)
    if principal <= ZERO or term_months <= 0:
        return ZERO
    r = pct_to_rate(annual_rate_pct) / 12
    if r <= ZERO:
        return principal / term_months
    growth = (ONE + r) ** term_months        # exact for rational r, int n
    return principal * r * growth / (growth - ONE)


def interest_only_payment(principal, annual_rate_pct) -> Fraction:
    principal = F(principal)
    if principal <= ZERO:
        return ZERO
    return principal * pct_to_rate(annual_rate_pct) / 12


def interest_for_period(principal, annual_rate_pct, days=30, basis_days=360) -> Fraction:
    """Simple (non-compounding) bridge interest, 30/360 convention."""
    principal = F(principal)
    if principal <= ZERO:
        return ZERO
    return principal * pct_to_rate(annual_rate_pct) * F(days) / F(basis_days)


def amortization_schedule(principal, annual_rate_pct, term_months,
                          amortization_months=None, interest_only_months=0,
                          max_months=None):
    """Month-by-month schedule -> list of dicts.

    `term_months`      = maturity / balloon length
    `amortization_months` = amortisation basis (0 or None => interest only)

    The schedule stops at maturity (`term_months`) unless `max_months` is
    larger, which is what refi / hold analysis needs (continue simulating debt
    service past the balloon as if it were refinanced).
    """
    principal = F(principal)
    rows = []
    if principal <= ZERO:
        return rows
    amortization_months = amortization_months or 0
    interest_only_months = int(interest_only_months or 0)
    term_months = int(term_months or 0)
    horizon = int(max_months if max_months is not None else term_months)
    if horizon <= 0:
        return rows

    periodic_rate = pct_to_rate(annual_rate_pct) / 12
    if amortization_months and amortization_months <= interest_only_months:
        raise InputError("amortizationMonths must exceed interestOnlyMonths")

    amortizing = amortization_months > 0
    payment_amount = (
        amortizing_payment(principal, annual_rate_pct, amortization_months)
        if amortizing else interest_only_payment(principal, annual_rate_pct)
    )
    io_payment = interest_only_payment(principal, annual_rate_pct)

    balance = principal
    stops_at_maturity = horizon <= term_months
    for month in range(1, horizon + 1):
        if not amortizing:
            interest = balance * periodic_rate
            principal_paid = ZERO
            cash_payment = io_payment
        elif month <= interest_only_months:
            interest = balance * periodic_rate
            principal_paid = ZERO
            cash_payment = io_payment
        else:
            interest = balance * periodic_rate
            cash_payment = payment_amount
            principal_paid = cash_payment - interest
            if principal_paid > balance:
                principal_paid = balance
                cash_payment = principal_paid + interest
            if principal_paid < ZERO and principal_paid + balance < ZERO:
                principal_paid = -balance
                cash_payment = principal_paid + interest
        # Contractual balloon: if the schedule stops at maturity and money is
        # still owed, the final payment retires the loan. Schedules that run PAST
        # maturity (hold projections) instead assume a refinance on the same
        # terms - the engine emits an explicit WARNING for that assumption.
        is_balloon = stops_at_maturity and month == term_months and month == horizon \
            and amortizing and balance - principal_paid > 0
        if is_balloon:
            principal_paid = balance
            cash_payment = principal_paid + interest
        balance_after = balance - principal_paid
        if balance_after < ZERO and balance_after > Fraction(-1, 10**9):
            balance_after = ZERO
        rows.append({
            "month": month,
            "openingBalance": balance,
            "payment": cash_payment,
            "interest": interest,
            "principal": principal_paid,
            "closingBalance": balance_after,
            "isBalloonMonth": bool(is_balloon),
            "assumesRefinance": bool(month > term_months),
        })
        balance = balance_after
        if balance <= ZERO and month >= interest_only_months:
            # loan is fully retired; remaining months have zero debt service
            for extra in range(month + 1, horizon + 1):
                rows.append({
                    "month": extra, "openingBalance": ZERO, "payment": ZERO,
                    "interest": ZERO, "principal": ZERO, "closingBalance": ZERO,
                    "isBalloonMonth": False,
                })
            break
    return rows


def schedule_to_horizon(terms, months):
    """Contractual schedule extended to `months`, returning (rows, refinanced).

    A loan that matures before the projection ends has to be dealt with, and
    quietly pretending the debt disappears is the single most common way a
    spreadsheet lies. The documented assumption here is:

      * interest-only bridge debt (hard/private money) refinances at maturity
        into a fully amortising 30-year loan at the SAME rate;
      * already-amortising debt (seller carry with a balloon) simply continues
        amortising on its original schedule at maturity.

    Either way the caller flags it (REFINANCE_ASSUMED_AT_MATURITY) so the result
    is never presented as if the original loan ran the whole term.
    """
    months = int(months)
    if months <= 0 or terms["loanAmount"] <= ZERO:
        return [], False
    # the refinance assumption bites whenever the contract matures inside the
    # projection window, regardless of how the rows are generated
    needs_refinance = 0 < terms["loanTermMonths"] < months
    rows = amortization_schedule(
        terms["loanAmount"], terms["interestRatePct"], terms["loanTermMonths"],
        terms["amortizationMonths"], terms["interestOnlyMonths"],
        max_months=months if terms["amortizationMonths"] > 0 else None)
    if len(rows) >= months:
        return rows, needs_refinance
    if terms["amortizationMonths"] > 0:
        return rows, needs_refinance

    rate = terms["interestRatePct"]
    periodic_rate = pct_to_rate(rate) / 12
    balance = rows[-1]["closingBalance"] if rows else terms["loanAmount"]
    payment = amortizing_payment(balance, rate, 360)
    start = len(rows) + 1
    for month in range(start, months + 1):
        interest = balance * periodic_rate
        principal = payment - interest
        if principal > balance:
            principal = balance
        cash_payment = principal + interest
        rows.append({
            "month": month,
            "openingBalance": balance,
            "payment": cash_payment,
            "interest": interest,
            "principal": principal,
            "closingBalance": balance - principal,
            "isBalloonMonth": False,
            "assumesRefinance": True,
        })
        balance -= principal
    return rows, True


def schedule_year_one_debt_service(rows) -> Fraction:
    """Cash actually paid to the lender during the first 12 months.

    For a balloon shorter than 12 months only the months that exist are counted
    (you cannot pay debt service on a loan you already repaid).
    """
    return sum((r["payment"] for r in rows[:12]), ZERO)


def schedule_interest(rows) -> Fraction:
    return sum((r["interest"] for r in rows), ZERO)


def balance_after_months(rows, months) -> Fraction:
    if months <= 0:
        return rows[0]["openingBalance"] if rows else ZERO
    if months >= len(rows):
        return rows[-1]["closingBalance"] if rows else ZERO
    return rows[months - 1]["closingBalance"]


# ---------------------------------------------------------------------------
# 3. financing model resolution
# ---------------------------------------------------------------------------

def resolve_financing(model, purchase_price, rehab_cost, arv, overrides):
    """Returns (terms, provenance, issues).

    Deterministic precedence for the loan amount:
        explicit loanAmount  >  explicit downPaymentAmount
                             >  explicit downPaymentPct
                             >  financing-model default downPaymentPct
    """
    overrides = dict(overrides or {})
    defaults = FINANCING_DEFAULTS[model]
    provenance = {}
    issues = []

    def pick(key, default_key=None):
        if key in overrides and overrides[key] is not None:
            provenance[key] = "EXPLICIT"
            return overrides[key]
        src = default_key or key
        provenance[key] = "MODEL_DEFAULT"
        return defaults.get(src)

    purchase_price = F(purchase_price)
    rehab_cost = F(rehab_cost or 0)
    arv = F(arv) if arv is not None else ZERO

    finances_rehab = pick("financesRehab")
    finances_closing = pick("financesClosingCosts", "financesClosingCosts")

    # ---- capitalised (financed) cost base ---------------------------------
    financed_rehab = rehab_cost if finances_rehab else ZERO
    financed_closing = ZERO
    closing_override_pct = overrides.get("closingCostPctOfPrice")
    if finances_closing:
        closing_pct = closing_override_pct if closing_override_pct is not None else defaults["closingCostPctOfPrice"]
        financed_closing = purchase_price * pct_to_rate(closing_pct)

    cost_basis = purchase_price + financed_rehab + financed_closing
    provenance["costBasis"] = "DERIVED"

    explicit_loan = overrides.get("loanAmount")
    explicit_down_amount = overrides.get("downPaymentAmount")
    explicit_down_pct = overrides.get("downPaymentPct")

    requested_loan = cost_basis
    if explicit_loan is not None:
        requested_loan = F(explicit_loan)
        provenance["loanAmount"] = "EXPLICIT"
        derived_down = cost_basis - requested_loan
        if explicit_down_amount is not None and abs(F(explicit_down_amount) - derived_down) > Fraction(1, 100):
            issues.append((WARNING, "LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT",
                           "explicit loanAmount and downPaymentAmount disagree by more than $0.01; "
                           "loanAmount takes precedence"))
        if explicit_down_pct is not None and purchase_price > 0 and overrides.get("downPaymentPct") is not None:
            implied_pct = derived_down / purchase_price * HUNDRED
            if abs(implied_pct - F(explicit_down_pct)) > Fraction(1, 100):
                issues.append((WARNING, "LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT",
                               "explicit loanAmount implies a down payment of %.2f%% but downPaymentPct was %.2f%%; "
                               "loanAmount takes precedence" % (to_float(implied_pct), to_float(F(explicit_down_pct)))))
    elif explicit_down_amount is not None:
        requested_loan = cost_basis - F(explicit_down_amount)
        provenance["loanAmount"] = "DERIVED"
        provenance["downPaymentAmount"] = "EXPLICIT"
    else:
        if explicit_down_pct is not None:
            down_pct = F(explicit_down_pct)
            provenance["downPaymentPct"] = "EXPLICIT"
        else:
            down_pct = F(defaults["downPaymentPct"])
            provenance["downPaymentPct"] = "MODEL_DEFAULT"
        # A down payment is a share of PRICE for LTV-driven loans and a share of
        # COST (purchase + financed rehab) for LTC-driven bridge loans.
        basis = overrides.get("downPaymentBasis") or defaults.get("downPaymentBasis", "PRICE")
        if basis not in ("PRICE", "COST"):
            issues.append((ERROR, "UNKNOWN_DOWN_PAYMENT_BASIS", "downPaymentBasis defaulted to PRICE"))
            basis = "PRICE"
        provenance["downPaymentBasis"] = "EXPLICIT" if overrides.get("downPaymentBasis") else "MODEL_DEFAULT"
        basis_amount = purchase_price if basis == "PRICE" else cost_basis
        requested_loan = cost_basis - basis_amount * pct_to_rate(down_pct)
        provenance["loanAmount"] = "DERIVED"
        provenance["downPaymentAmount"] = "DERIVED"

    # ---- lender ceilings --------------------------------------------------
    # A loan is limited by the tightest of: cost (LTC), purchase price (LTV) and
    # after-repair value (ARV).  Hard/private money is LTC-driven; agency and
    # DSCR loans are LTV-driven.  Only the constraints a model declares apply.
    ceilings = []
    max_ltv_pct = None
    if defaults.get("maxLtvPct") is not None:
        max_ltv_pct = overrides.get("maxLtvPct") if overrides.get("maxLtvPct") is not None else F(defaults["maxLtvPct"])
        provenance["maxLtvPct"] = "EXPLICIT" if overrides.get("maxLtvPct") is not None else "MODEL_DEFAULT"
        ceilings.append(("MAX_LTV_OF_PRICE", purchase_price * pct_to_rate(max_ltv_pct)))
    if defaults.get("maxLtcPct") is not None:
        ltc_pct = overrides.get("maxLtcPct") if overrides.get("maxLtcPct") is not None else F(defaults["maxLtcPct"])
        provenance["maxLtcPct"] = "EXPLICIT" if overrides.get("maxLtcPct") is not None else "MODEL_DEFAULT"
        ceilings.append(("MAX_LTC_OF_COST", cost_basis * pct_to_rate(ltc_pct)))
    if defaults.get("maxLtvArvPct") is not None and arv > ZERO:
        arv_pct = overrides.get("maxLtvArvPct") if overrides.get("maxLtvArvPct") is not None \
            else F(defaults["maxLtvArvPct"])
        provenance["maxLtvArvPct"] = "EXPLICIT" if overrides.get("maxLtvArvPct") is not None else "MODEL_DEFAULT"
        ceilings.append(("MAX_LTV_OF_ARV", arv * pct_to_rate(arv_pct)))

    loan_amount = requested_loan
    binding_ceiling = None
    for name, ceiling in ceilings:
        if ceiling < loan_amount:
            loan_amount = ceiling
            binding_ceiling = name
    if binding_ceiling:
        issues.append((INFO, "LOAN_CAPPED_BY_" + binding_ceiling,
                       "requested loan %.2f was capped by lender ceiling %s" % (to_float(requested_loan), binding_ceiling)))

    if loan_amount < ZERO:
        issues.append((ERROR, "NEGATIVE_LOAN_AMOUNT", "resolved loan amount is negative"))
        loan_amount = ZERO

    down_payment = cost_basis - loan_amount
    provenance["downPaymentAmount"] = provenance.get("downPaymentAmount", "DERIVED")

    # ---- rate / term ------------------------------------------------------
    interest_rate_pct = pick("interestRatePct")
    loan_term_months = int(pick("loanTermMonths"))
    amortization_months = int(pick("amortizationMonths"))
    interest_only_months = int(pick("interestOnlyMonths"))
    points_pct = F(pick("originationPointsPct"))
    lender_fees = F(pick("lenderFees"))
    reserve_months = int(pick("lenderReserveMonths"))
    min_interest_months = int(pick("lenderMinimumInterestMonths"))
    profit_share_pct = F(pick("lenderProfitSharePct")) if "lenderProfitSharePct" in defaults else ZERO

    if interest_only_months > loan_term_months:
        issues.append((WARNING, "IO_PERIOD_EXCEEDS_TERM",
                       "interestOnlyMonths clamped to loanTermMonths"))
        interest_only_months = loan_term_months
    if amortization_months and amortization_months < loan_term_months and amortization_months > 0:
        # balloon: amortisation longer than maturity is the normal case
        pass
    if amortization_months and amortization_months <= interest_only_months and interest_only_months > 0:
        issues.append((WARNING, "IO_PERIOD_CONSUMES_AMORTIZATION",
                       "amortisation period does not exceed the interest-only period; treated as interest only"))

    terms = {
        "model": model,
        "loanAmount": loan_amount,
        "downPaymentAmount": down_payment,
        "costBasis": cost_basis,
        "financedRehab": financed_rehab,
        "financedClosingCosts": financed_closing,
        "interestRatePct": F(interest_rate_pct),
        "loanTermMonths": loan_term_months,
        "amortizationMonths": amortization_months,
        "interestOnlyMonths": interest_only_months,
        "originationPointsPct": points_pct,
        "lenderFees": lender_fees,
        "lenderReserveMonths": reserve_months,
        "financesRehab": bool(finances_rehab),
        "financesClosingCosts": bool(finances_closing),
        "lenderMinimumInterestMonths": min_interest_months,
        "lenderProfitSharePct": profit_share_pct,
        "maxLtvPct": max_ltv_pct,
        "bindingLoanCeiling": binding_ceiling,
        "maxLtcPct": F(defaults["maxLtcPct"]) if defaults.get("maxLtcPct") is not None else None,
        "downPaymentBasis": provenance.get("downPaymentBasis", "MODEL_DEFAULT"),
        "minDscr": F(defaults["minDscr"]) if defaults.get("minDscr") else None,
        "isInterestOnly": amortization_months == 0 or interest_only_months >= loan_term_months,
    }
    terms["requestedLoanAmount"] = requested_loan
    terms["loanCappedByLender"] = binding_ceiling is not None
    terms["newLoanFees"] = loan_amount * pct_to_rate(points_pct) + lender_fees
    terms["monthlyPayment"] = (
        amortizing_payment(loan_amount, interest_rate_pct, amortization_months)
        if amortization_months > 0 and interest_only_months < loan_term_months
        else interest_only_payment(loan_amount, interest_rate_pct)
    )
    terms["interestOnlyPayment"] = interest_only_payment(loan_amount, interest_rate_pct)
    return terms, provenance, issues


# ---------------------------------------------------------------------------
# 4. operating statement
# ---------------------------------------------------------------------------

def resolve_expense_line(explicit_amount, basis, value, gsi=None, egi=None,
                         price=None, monthly=False):
    """One operating-expense line, resolved against an explicit basis."""
    if explicit_amount is not None:
        amount = F(explicit_amount) * (12 if monthly else 1)
        return amount, "EXPLICIT_ANNUAL" if not monthly else "EXPLICIT_MONTHLY"
    if basis == "NONE" or value is None:
        return ZERO, "NONE"
    value = F(value)
    if basis == "FIXED_ANNUAL":
        return value, "FIXED_ANNUAL"
    if basis == "FIXED_MONTHLY":
        return value * 12, "FIXED_MONTHLY"
    if basis == "PERCENT_OF_PRICE":
        return (price or ZERO) * pct_to_rate(value), "PERCENT_OF_PRICE"
    if basis == "PERCENT_OF_GSI":
        return (gsi or ZERO) * pct_to_rate(value), "PERCENT_OF_GSI"
    if basis == "PERCENT_OF_EGI":
        return (egi or ZERO) * pct_to_rate(value), "PERCENT_OF_EGI"
    raise InputError("unknown expense basis: %s" % basis)


def operating_statement(inp, purchase_price, rehab_cost, loan_terms, schedule):
    """Gross scheduled income -> EGI -> NOI -> cash flow.

    All bases are named in the output so every dollar is reproducible by hand.
    """
    asm = DEFAULT_ASSUMPTIONS
    issues = []
    trace = {}

    monthly_rent = F(inp.get("monthlyRent") or 0)
    other_monthly = F(inp.get("otherMonthlyIncome") or 0)
    gsi = (monthly_rent + other_monthly) * 12
    trace["grossScheduledIncome"] = "(monthlyRent + otherMonthlyIncome) x 12"

    vacancy_pct = F(inp["vacancyRatePct"]) if inp.get("vacancyRatePct") is not None else F(asm["vacancyRatePct"])
    credit_pct = F(inp["creditLossRatePct"]) if inp.get("creditLossRatePct") is not None else F(asm["creditLossRatePct"])
    if vacancy_pct < 0 or credit_pct < 0:
        issues.append((ERROR, "NEGATIVE_VACANCY",
                       "vacancy/credit loss cannot be negative; both were floored at 0"))
        vacancy_pct = max(vacancy_pct, ZERO)
        credit_pct = max(credit_pct, ZERO)
    if vacancy_pct + credit_pct > 100:
        issues.append((ERROR, "TOTAL_VACANCY_ABOVE_100",
                       "vacancy + credit loss above 100% clamped to 100%"))
        vacancy_pct = HUNDRED - credit_pct if credit_pct < HUNDRED else HUNDRED
    vacancy_loss = gsi * (vacancy_pct + credit_pct) / HUNDRED
    egi = gsi - vacancy_loss
    trace["vacancyAndCreditLoss"] = "grossScheduledIncome x (vacancyRatePct + creditLossRatePct) / 100"
    trace["effectiveGrossIncome"] = "grossScheduledIncome - vacancyAndCreditLoss"

    price_for_tax = purchase_price
    if inp.get("propertyTaxAnnual") is not None:
        property_tax, tax_basis = resolve_expense_line(inp["propertyTaxAnnual"], "FIXED_ANNUAL", None)
    else:
        pct = F(inp["propertyTaxPctOfPrice"]) if inp.get("propertyTaxPctOfPrice") is not None else F(asm["propertyTaxPctOfPrice"])
        property_tax, tax_basis = resolve_expense_line(None, "PERCENT_OF_PRICE", pct, price=price_for_tax)

    if inp.get("insuranceAnnual") is not None:
        insurance, ins_basis = resolve_expense_line(inp["insuranceAnnual"], "FIXED_ANNUAL", None)
    else:
        pct = F(inp["insurancePctOfPrice"]) if inp.get("insurancePctOfPrice") is not None else F(asm["insurancePctOfPrice"])
        insurance, ins_basis = resolve_expense_line(None, "PERCENT_OF_PRICE", pct, price=price_for_tax)

    hoa, hoa_basis = resolve_expense_line(inp.get("hoaMonthly"), None, None, monthly=True)

    if inp.get("maintenanceAnnual") is not None:
        maintenance, maint_basis = resolve_expense_line(inp["maintenanceAnnual"], "FIXED_ANNUAL", None)
    else:
        pct = F(inp["maintenancePctOfGsi"]) if inp.get("maintenancePctOfGsi") is not None else F(asm["maintenancePctOfGsi"])
        base = inp.get("maintenanceBasis") or "PERCENT_OF_GSI"
        maintenance, maint_basis = resolve_expense_line(
            None, base, pct, gsi=gsi, egi=egi, price=price_for_tax)

    if inp.get("managementAnnual") is not None:
        management, mgmt_basis = resolve_expense_line(inp["managementAnnual"], "FIXED_ANNUAL", None)
    else:
        pct = F(inp["managementPctOfEgi"]) if inp.get("managementPctOfEgi") is not None else F(asm["managementPctOfEgi"])
        base = inp.get("managementBasis") or "PERCENT_OF_EGI"
        management, mgmt_basis = resolve_expense_line(
            None, base, pct, gsi=gsi, egi=egi, price=price_for_tax)

    capex_pct = F(inp["capexReservePctOfGsi"]) if inp.get("capexReservePctOfGsi") is not None else F(asm["capexReservePctOfGsi"])
    capex, capex_basis = resolve_expense_line(None, "PERCENT_OF_GSI", capex_pct, gsi=gsi)
    capex_treatment = inp.get("capexTreatment") or asm["capexTreatment"]
    if capex_treatment not in ("ABOVE_LINE_IN_NOI", "BELOW_LINE_RESERVE"):
        issues.append((ERROR, "UNKNOWN_CAPEX_TREATMENT", "capexTreatment defaulted to ABOVE_LINE_IN_NOI"))
        capex_treatment = "ABOVE_LINE_IN_NOI"

    utilities, util_basis = resolve_expense_line(inp.get("utilitiesMonthly"), None, None, monthly=True)
    landscaping, land_basis = resolve_expense_line(inp.get("landscapingMonthly"), None, None, monthly=True)
    other_annual = F(inp.get("otherOperatingAnnual") or 0)

    lines = [
        ("propertyTax", property_tax, tax_basis),
        ("insurance", insurance, ins_basis),
        ("hoa", hoa, hoa_basis),
        ("maintenanceRepairs", maintenance, maint_basis),
        ("propertyManagement", management, mgmt_basis),
        ("utilities", utilities, util_basis),
        ("landscapingPest", landscaping, land_basis),
        ("otherOperating", other_annual, "EXPLICIT_ANNUAL" if other_annual else "NONE"),
    ]
    if capex_treatment == "ABOVE_LINE_IN_NOI":
        lines.append(("capitalReserves", capex, capex_basis))
    total_opex = sum((amount for _, amount, _ in lines), ZERO)

    noi = egi - total_opex
    trace["totalOperatingExpenses"] = "sum(propertyTax, insurance, hoa, maintenanceRepairs, propertyManagement, utilities, landscapingPest, otherOperating%s)" % (
        ", capitalReserves" if capex_treatment == "ABOVE_LINE_IN_NOI" else "")
    trace["netOperatingIncome"] = "effectiveGrossIncome - totalOperatingExpenses"

    year_one_debt = schedule_year_one_debt_service(schedule)
    monthly_debt = loan_terms["monthlyPayment"]
    if loan_terms["loanAmount"] <= ZERO:
        monthly_debt = ZERO
    annual_cash_flow = noi - year_one_debt
    monthly_cash_flow = annual_cash_flow / 12
    trace["annualDebtService"] = "sum(loan payments actually due in months 1..min(12, loanTermMonths))"
    trace["annualCashFlow"] = "netOperatingIncome - annualDebtService"

    dscr = None
    if year_one_debt > ZERO:
        dscr = noi / year_one_debt
        if noi < ZERO:
            issues.append((WARNING, "NEGATIVE_NOI",
                           "net operating income is negative; DSCR %.2f means the property cannot cover "
                           "debt service from operations" % to_float(dscr)))

    cap_on_price = None
    if purchase_price > ZERO:
        cap_on_price = noi / purchase_price * HUNDRED
    cap_on_cost = None
    all_in_cost = purchase_price + F(inp.get("closingCosts") or 0) + rehab_cost
    if all_in_cost > ZERO:
        cap_on_cost = noi / all_in_cost * HUNDRED

    grm = None
    if gsi > ZERO:
        grm = purchase_price / gsi

    break_even = None
    if gsi > ZERO:
        break_even = (total_opex + year_one_debt) / gsi * HUNDRED

    expense_ratio = None
    if egi > ZERO:
        expense_ratio = total_opex / egi * HUNDRED

    return {
        "grossScheduledIncomeAnnual": gsi,
        "vacancyAndCreditLossAnnual": vacancy_loss,
        "effectiveGrossIncomeAnnual": egi,
        "expenseLines": lines,
        "capitalReservesAnnual": capex,
        "capexTreatment": capex_treatment,
        "totalOperatingExpensesAnnual": total_opex,
        "netOperatingIncomeAnnual": noi,
        "annualDebtService": year_one_debt,
        "monthlyDebtService": monthly_debt,
        "monthlyCashFlow": monthly_cash_flow,
        "annualCashFlow": annual_cash_flow,
        "dscr": dscr,
        "capRateOnPricePct": cap_on_price,
        "capRateOnCostPct": cap_on_cost,
        "grossRentMultiplier": grm,
        "breakEvenOccupancyPct": break_even,
        "operatingExpenseRatioPct": expense_ratio,
        "trace": trace,
        "issues": issues,
        "vacancyRatePct": vacancy_pct,
        "creditLossRatePct": credit_pct,
    }


# ---------------------------------------------------------------------------
# 5. total cash required / LTV / CoC
# ---------------------------------------------------------------------------

def capital_stack(inp, purchase_price, rehab_cost, closing_costs, loan_terms, extra_cash=ZERO):
    """Every dollar the investor must wire at closing."""
    issues = []
    down_payment = loan_terms["downPaymentAmount"]
    financed_rehab = loan_terms["financedRehab"]
    cash_rehab = F(rehab_cost) - financed_rehab
    if cash_rehab < ZERO:
        cash_rehab = ZERO
    loan_fees = loan_terms["newLoanFees"]

    # lender reserve requirement, expressed in months of PITI
    reserve_months = loan_terms["lenderReserveMonths"]
    monthly_piti = ZERO
    if reserve_months:
        # PITI proxy: P&I + monthly taxes + monthly insurance (both required)
        taxes = F(inp["propertyTaxAnnual"]) / 12 if inp.get("propertyTaxAnnual") is not None else purchase_price * pct_to_rate(DEFAULT_ASSUMPTIONS["propertyTaxPctOfPrice"]) / 12
        ins = F(inp["insuranceAnnual"]) / 12 if inp.get("insuranceAnnual") is not None else purchase_price * pct_to_rate(DEFAULT_ASSUMPTIONS["insurancePctOfPrice"]) / 12
        monthly_piti = loan_terms["monthlyPayment"] + taxes + ins + F(inp.get("hoaMonthly") or 0)
    reserves = monthly_piti * reserve_months
    seller_credit = F(inp.get("sellerCredits") or 0)

    total_cash = down_payment + closing_costs + cash_rehab + loan_fees + reserves - seller_credit + extra_cash
    if total_cash < ZERO:
        issues.append((WARNING, "NEGATIVE_CASH_REQUIRED",
                       "credits exceed cash needed; cash required floored at 0"))
        total_cash = ZERO

    ltv_of_price = None
    if purchase_price > ZERO:
        ltv_of_price = loan_terms["loanAmount"] / purchase_price * HUNDRED

    return {
        "downPaymentAmount": down_payment,
        "cashRehab": cash_rehab,
        "closingCosts": closing_costs,
        "loanFeesAndPoints": loan_fees,
        "lenderReserves": reserves,
        "sellerCredits": seller_credit,
        "totalCashRequired": total_cash,
        "ltvOfPricePct": ltv_of_price,
        "issues": issues,
    }


def cash_on_cash_pct(annual_cash_flow, total_cash_required):
    """None when nothing is invested - a divide-by-zero is not a return."""
    if total_cash_required is None or total_cash_required <= ZERO:
        return None
    return annual_cash_flow / total_cash_required * HUNDRED


# ---------------------------------------------------------------------------
# 6. multi-year projection + return metrics (hold / BRRRR)
# ---------------------------------------------------------------------------

def project_holding(operating, loan_terms, schedule, purchase_price, hold_years,
                    rent_growth_pct, expense_growth_pct, appreciation_pct,
                    sale_cost_pct, total_cash_required, opening_value=None):
    """`hold_years` must be >= 1; callers validate and default it explicitly."""
    """Year-by-year hold projection and exit maths.

    Value of the property at exit = purchasePrice x (1 + g)^n  (or ARV basis for
    a BRRRR refinance, which is passed in as `opening_value`).

    Profit = sum(cash flow) + (net sale proceeds - cash invested)
    The loan balance reduction (principal paydown) is *inside* net sale
    proceeds, so it is never double counted.
    """
    asm = DEFAULT_ASSUMPTIONS
    hold_years = int(hold_years)
    if hold_years < 1:
        raise InputError("holdYears must be at least 1 (validate before calling)")
    rent_growth = pct_to_rate(rent_growth_pct if rent_growth_pct is not None else asm["rentGrowthPct"])
    expense_growth = pct_to_rate(expense_growth_pct if expense_growth_pct is not None else asm["expenseGrowthPct"])
    appreciation = pct_to_rate(appreciation_pct if appreciation_pct is not None else asm["appreciationPct"])
    sale_cost = pct_to_rate(sale_cost_pct if sale_cost_pct is not None else asm["saleCostPct"])

    start_value = F(opening_value) if opening_value is not None else F(purchase_price)
    egi = operating["effectiveGrossIncomeAnnual"]
    opex = operating["totalOperatingExpensesAnnual"]
    vacancy_share = (egi / operating["grossScheduledIncomeAnnual"]) if operating["grossScheduledIncomeAnnual"] > ZERO else ONE

    years = []
    cumulative_cash_flow = ZERO
    for year in range(1, hold_years + 1):
        year_egi = egi * (ONE + rent_growth) ** (year - 1)
        year_opex = opex * (ONE + expense_growth) ** (year - 1)
        year_noi = year_egi - year_opex
        year_debt = ZERO
        for month in range((year - 1) * 12 + 1, year * 12 + 1):
            if month - 1 < len(schedule):
                year_debt += schedule[month - 1]["payment"]
        year_cf = year_noi - year_debt
        cumulative_cash_flow += year_cf
        years.append({
            "year": year,
            "effectiveGrossIncome": year_egi,
            "operatingExpenses": year_opex,
            "netOperatingIncome": year_noi,
            "debtService": year_debt,
            "cashFlow": year_cf,
            "dscr": (year_noi / year_debt) if year_debt > ZERO else None,
            "propertyValue": start_value * (ONE + appreciation) ** year,
            "loanBalance": balance_after_months(schedule, year * 12),
            "vacancyShareOfEgi": vacancy_share,
        })

    exit_value = start_value * (ONE + appreciation) ** hold_years
    sale_costs = exit_value * sale_cost
    loan_balance = balance_after_months(schedule, hold_years * 12)
    net_sale_proceeds = exit_value - sale_costs - loan_balance
    cash_invested = F(total_cash_required)
    total_profit = cumulative_cash_flow + net_sale_proceeds - cash_invested
    equity_multiple = None
    if cash_invested > ZERO:
        equity_multiple = (cumulative_cash_flow + net_sale_proceeds) / cash_invested
    roi_pct = None
    if cash_invested > ZERO:
        roi_pct = total_profit / cash_invested * HUNDRED
    annualized = annualize_from_multiple(equity_multiple, hold_years) if equity_multiple else None

    # monthly cash-flow stream for IRR: month 0 = equity out, then 1/12th of each
    # year's cash flow, with net sale proceeds landing on the final month.
    stream = [-float(cash_invested)]
    for year in range(1, hold_years + 1):
        for _ in range(12):
            stream.append(float(years[year - 1]["cashFlow"]) / 12.0)
    stream[-1] += float(net_sale_proceeds)
    irr = irr_monthly(stream)
    simple_annualized_pct = None
    if roi_pct is not None and hold_years > 0:
        simple_annualized_pct = float(roi_pct) / hold_years
    return {
        "years": years,
        "simpleAnnualizedRoiPct": simple_annualized_pct,
        "exitValue": exit_value,
        "saleCosts": sale_costs,
        "loanBalanceAtExit": loan_balance,
        "netSaleProceeds": net_sale_proceeds,
        "cumulativeCashFlow": cumulative_cash_flow,
        "totalProfit": total_profit,
        "roiPct": roi_pct,
        "annualizedRoiPct": annualized * HUNDRED if annualized is not None else None,
        "equityMultiple": equity_multiple,
        "irrMonthly": irr,
        "irrAnnualPct": ((ONE + F(irr)) ** 12 - ONE) * HUNDRED if irr is not None else None,
    }


def annualize_from_multiple(multiple, years):
    """Geometric annualisation (CAGR). Undefined - and therefore None - when the
    multiple is not positive: you cannot compound a total loss, and NaN or a
    complex number leaking out of a power operator is a bug, not a result."""
    if multiple is None or years is None or years <= 0:
        return None
    multiple = float(multiple)
    if multiple <= 0.0:
        return None
    return multiple ** (1.0 / years) - 1.0


def annualize_from_roi(roi_fraction, months):
    """Geometric annualisation of a period ROI. None when (1 + roi) <= 0."""
    if roi_fraction is None or months is None or months <= 0:
        return None
    base = 1.0 + float(roi_fraction)
    if base <= 0.0:
        return None
    return base ** (12.0 / months) - 1.0


def irr_monthly(cash_flows, low=-0.9999, high=10.0, iterations=200):
    """Deterministic bisection IRR over a monthly stream (rates are monthly).

    Returns None when the stream never changes sign (no real IRR).
    """
    flows = [float(c) for c in cash_flows]
    if len(flows) < 2:
        return None
    if not (any(c > 0 for c in flows) and any(c < 0 for c in flows)):
        return None

    def npv(rate):
        total = 0.0
        for period, flow in enumerate(flows):
            if flow == 0.0:
                continue
            try:
                denominator = (1.0 + rate) ** period
            except OverflowError:
                denominator = float("inf")
            if denominator == 0.0 or denominator == float("inf"):
                # an over/under-flowed discount factor contributes (almost)
                # nothing to present value; skip it instead of raising
                continue
            total += flow / denominator
        return total

    f_low, f_high = npv(low), npv(high)
    if not (math.isfinite(f_low) and math.isfinite(f_high)):
        return None
    if f_low * f_high > 0:
        return None
    for _ in range(iterations):
        mid = (low + high) / 2.0
        f_mid = npv(mid)
        if f_low * f_mid <= 0:
            high, f_high = mid, f_mid
        else:
            low, f_low = mid, f_mid
    return (low + high) / 2.0


# ---------------------------------------------------------------------------
# 7. strategy analyzers
# ---------------------------------------------------------------------------

def _closing_cost_for(inp, purchase_price):
    """Buy-side closing costs: explicit dollar amount wins over the model %."""
    if inp.get("closingCosts") is not None:
        return F(inp["closingCosts"]), "EXPLICIT"
    return ZERO, "NONE"


def analyze_buy_and_hold(inp, base):
    asm = DEFAULT_ASSUMPTIONS
    issues = []
    hold_years = inp.get("holdYears")
    if hold_years is None:
        hold_years = asm["holdYears"]
    elif int(hold_years) < 1:
        issues.append((WARNING, "HOLD_YEARS_DEFAULTED",
                       "holdYears must be at least 1; used the documented default of %d" % asm["holdYears"]))
        hold_years = asm["holdYears"]
    hold_years = int(hold_years)
    horizon_schedule, refinanced = schedule_to_horizon(base["financing"], hold_years * 12)
    if refinanced:
        issues.append((WARNING, "REFINANCE_ASSUMED_AT_MATURITY",
                       "the %d-month loan matures before the %d-month projection; the projection assumes "
                       "refinancing at maturity on the documented terms"
                       % (base["financing"]["loanTermMonths"], hold_years * 12)))
    proj = project_holding(
        operating=base["operating"],
        loan_terms=base["financing"],
        schedule=horizon_schedule,
        purchase_price=base["purchasePrice"],
        hold_years=hold_years,
        rent_growth_pct=inp.get("rentGrowthPct"),
        expense_growth_pct=inp.get("expenseGrowthPct"),
        appreciation_pct=inp.get("appreciationPct"),
        sale_cost_pct=inp.get("saleCostPct"),
        total_cash_required=base["core"]["totalCashRequired"],
    )
    return {
        "hold": proj,
        "returns": {
            "cashInvested": base["core"]["totalCashRequired"],
            "holdPeriodMonths": int(hold_years) * 12,
            "totalProfit": proj["totalProfit"],
            "roiPct": proj["roiPct"],
            "simpleAnnualizedRoiPct": proj["simpleAnnualizedRoiPct"],
            "annualizedRoiPct": proj["annualizedRoiPct"],
            "equityMultiple": proj["equityMultiple"],
            "irrAnnualPct": proj["irrAnnualPct"],
        },
        "issues": issues,
    }


def analyze_brrrr(inp, base):
    """Buy (bridge) -> Rehab -> Rent -> Refinance -> Repeat.

    Phase 2 (the refinance) is modelled explicitly: the bridge loan is paid off
    with a new ARV-based loan and the difference is cash back to the investor.
    `cashLeftInDeal` may be <= 0, which is the definition of an infinite
    cash-on-cash return; the engine reports that as a flag instead of a number,
    because dividing by zero is not a return.
    """
    asm = DEFAULT_ASSUMPTIONS
    purchase_price = base["purchasePrice"]
    rehab_cost = base["rehabCost"]
    arv = F(inp["arv"]) if inp.get("arv") is not None else ZERO
    if arv <= ZERO:
        return {
            "brrrr": None,
            "returns": None,
            "issues": [(ERROR, "BRRRR_REQUIRES_ARV", "BRRRR requires an explicit ARV")],
        }
    issues = []

    acquisition_model = inp.get("brrrrAcquisitionModel") or "HARD_MONEY"
    if acquisition_model not in FINANCING_MODELS:
        issues.append((ERROR, "UNKNOWN_ACQUISITION_MODEL", "unknown brrrrAcquisitionModel; used HARD_MONEY"))
        acquisition_model = "HARD_MONEY"
    refi_model = "SELLER_FINANCING"  # placeholder, refi terms are passed explicitly
    _ = refi_model

    # The bridge/acquisition loan is the loan `analyze()` already resolved with
    # the strategy's financing model; bridgeOverrides only re-resolve if given.
    bridge_overrides = dict(inp.get("bridgeOverrides") or {})
    if bridge_overrides:
        bridge, bridge_prov, bridge_issues = resolve_financing(
            acquisition_model, purchase_price, rehab_cost, arv, bridge_overrides)
        issues.extend(bridge_issues)
    else:
        bridge = base["financing"]
        bridge_prov = base.get("provenance") or {}
        if bridge["model"] != acquisition_model:
            issues.append((INFO, "BRIDGE_MODEL_FROM_INPUT",
                           "acquisition loan used the strategy financing model"))
    acquisition_model = bridge["model"]
    bridge_schedule = amortization_schedule(
        bridge["loanAmount"], bridge["interestRatePct"], bridge["loanTermMonths"],
        bridge["amortizationMonths"], bridge["interestOnlyMonths"])

    rehab_months = int(inp.get("brrrrRehabMonths") if inp.get("brrrrRehabMonths") is not None else asm["flipHoldMonths"])
    if rehab_months <= 0:
        issues.append((WARNING, "BRRRR_REHAB_MONTHS_DEFAULTED",
                       "brrrrRehabMonths must be positive; used the documented default of %d" % asm["flipHoldMonths"]))
        rehab_months = int(asm["flipHoldMonths"])
    holding_monthly = inp.get("holdingCostsMonthly")
    if holding_monthly is None:
        holding_monthly = purchase_price * pct_to_rate(asm["holdingCostsMonthlyPctOfPrice"])
    holding_monthly = F(holding_monthly)
    holding_costs = holding_monthly * rehab_months

    # bridge interest actually paid during the rehab window (floored by the
    # lender's minimum interest months, which hard money contracts impose)
    months_charged = max(rehab_months, bridge["lenderMinimumInterestMonths"])
    bridge_interest = interest_only_payment(bridge["loanAmount"], bridge["interestRatePct"]) * months_charged \
        if bridge["loanAmount"] > ZERO else ZERO

    # Interest is paid monthly during the hold and points/fees are paid at
    # closing (both already inside phase1_cash), so the payoff is the principal.
    bridge_payoff = bridge["loanAmount"]
    bridge_interest_paid_to_date = bridge_interest
    # the same resolved closing costs the core used (explicit, or the model's
    # percentage of price) - never a silent zero
    cash_closing = base["core"]["closingCosts"]
    phase1_cash = bridge["downPaymentAmount"] + cash_closing + (rehab_cost - bridge["financedRehab"]) \
        + bridge["newLoanFees"] + bridge_interest + holding_costs
    if phase1_cash < ZERO:
        phase1_cash = ZERO

    # ---- refinance --------------------------------------------------------
    refi_ltv_pct = F(inp["refiLtvPctOfArv"]) if inp.get("refiLtvPctOfArv") is not None \
        else F(asm["brrrrTargetLtvOfArvPct"])
    refi_loan = arv * pct_to_rate(refi_ltv_pct)
    refi_rate = F(inp["refiInterestRatePct"]) if inp.get("refiInterestRatePct") is not None else Fraction("7.50")
    refi_term = int(inp["refiLoanTermMonths"]) if inp.get("refiLoanTermMonths") is not None else 360
    refi_amort = int(inp["refiAmortizationMonths"]) if inp.get("refiAmortizationMonths") is not None else 360
    refi_points_pct = F(inp["refiOriginationPointsPct"]) if inp.get("refiOriginationPointsPct") is not None else Fraction("1.5")
    refi_fees = F(inp["refiLenderFees"]) if inp.get("refiLenderFees") is not None else Fraction(1500)
    refi_closing_pct = F(inp["refiClosingCostPct"]) if inp.get("refiClosingCostPct") is not None else Fraction("2.0")
    refi_costs = refi_loan * pct_to_rate(refi_points_pct) + refi_fees + refi_loan * pct_to_rate(refi_closing_pct)

    cash_out_at_refi = refi_loan - bridge_payoff - refi_costs
    cash_left_in_deal = phase1_cash - cash_out_at_refi
    capital_recovered_pct = None
    if phase1_cash > ZERO:
        capital_recovered_pct = cash_out_at_refi / phase1_cash * HUNDRED
    infinite_coc = cash_left_in_deal <= ZERO

    refi_terms = {
        "model": "REFINANCE",
        "loanAmount": refi_loan,
        "downPaymentAmount": ZERO,
        "costBasis": arv,
        "financedRehab": ZERO,
        "financedClosingCosts": ZERO,
        "interestRatePct": refi_rate,
        "loanTermMonths": refi_term,
        "amortizationMonths": refi_amort,
        "interestOnlyMonths": 0,
        "originationPointsPct": refi_points_pct,
        "lenderFees": refi_fees,
        "lenderReserveMonths": 0,
        "financesRehab": False,
        "financesClosingCosts": False,
        "lenderMinimumInterestMonths": 0,
        "lenderProfitSharePct": ZERO,
        "maxLtvPct": refi_ltv_pct,
        "bindingLoanCeiling": None,
        "minDscr": Fraction("1.20"),
        "isInterestOnly": False,
        "newLoanFees": refi_loan * pct_to_rate(refi_points_pct) + refi_fees,
    }
    refi_terms["monthlyPayment"] = amortizing_payment(refi_loan, refi_rate, refi_amort)
    refi_terms["interestOnlyPayment"] = interest_only_payment(refi_loan, refi_rate)
    refi_schedule = amortization_schedule(refi_loan, refi_rate, refi_term, refi_amort, 0)

    # ---- post-refi operating metrics (same property, new debt) ------------
    refi_closing_costs = refi_costs
    post = operating_statement(inp, purchase_price, rehab_cost, refi_terms, refi_schedule)
    issues.extend(post["issues"])
    post_core = build_core(inp, purchase_price, rehab_cost, cash_closing, refi_terms, post, refi_schedule,
                           extra_cash=holding_costs + bridge_interest + bridge["newLoanFees"])
    post_coc = cash_on_cash_pct(post["annualCashFlow"], cash_left_in_deal)
    post_core["totalCashRequired"] = cash_left_in_deal if cash_left_in_deal > ZERO else ZERO
    post_core["cashOnCashPct"] = post_coc
    post_core["infiniteCashOnCash"] = infinite_coc
    post_ltv_arv = refi_loan / arv * HUNDRED
    refi_ltv_arv = post_ltv_arv
    post_dscr = post["dscr"]

    verdict_issues = []
    if post_dscr is not None and refi_terms["minDscr"] is not None and post_dscr < refi_terms["minDscr"]:
        verdict_issues.append((WARNING, "REFI_DSCR_SHORTFALL",
                               "post-refinance DSCR %.2f is below the 1.20x typically required to refinance"
                               % to_float(post_dscr)))

    hold_years = inp.get("holdYears") if inp.get("holdYears") is not None else asm["holdYears"]
    if int(hold_years) < 1:
        issues.append((WARNING, "HOLD_YEARS_DEFAULTED",
                       "holdYears must be at least 1; used the documented default of %d" % asm["holdYears"]))
        hold_years = asm["holdYears"]
    hold_years = int(hold_years)
    horizon_schedule, refinanced = schedule_to_horizon(refi_terms, hold_years * 12)
    if refinanced:
        issues.append((WARNING, "REFINANCE_ASSUMED_AT_MATURITY",
                       "the refinance loan matures before the %d-month projection; the projection assumes "
                       "refinancing at maturity on identical terms" % (hold_years * 12)))
    proj = project_holding(
        operating=post,
        loan_terms=refi_terms,
        schedule=horizon_schedule,
        purchase_price=purchase_price,
        hold_years=hold_years,
        rent_growth_pct=inp.get("rentGrowthPct"),
        expense_growth_pct=inp.get("expenseGrowthPct"),
        appreciation_pct=inp.get("appreciationPct"),
        sale_cost_pct=inp.get("saleCostPct"),
        total_cash_required=cash_left_in_deal,
        opening_value=arv,   # value at refinance is the ARV, growth starts from there
    )
    issues.extend(verdict_issues)

    brrrr_block = {
        "arv": arv,
        "acquisitionModel": acquisition_model,
        "bridgeLoanAmount": bridge["loanAmount"],
        "bridgeInterestPaid": bridge_interest,
        "bridgeLoanFeesAndPoints": bridge["newLoanFees"],
        "bridgePayoffAmount": bridge_payoff,
        "rehabMonths": rehab_months,
        "holdingCostsDuringRehab": holding_costs,
        "phase1CashInvested": phase1_cash,
        "refinance": {
            "loanAmount": refi_loan,
            "ltvOfArvPct": refi_ltv_arv,
            "interestRatePct": refi_rate,
            "loanTermMonths": refi_term,
            "amortizationMonths": refi_amort,
            "monthlyPayment": refi_terms["monthlyPayment"],
            "closingCostsAndPoints": refi_costs,
        },
        "cashOutAtRefinance": cash_out_at_refi,
        "cashLeftInDeal": cash_left_in_deal,
        "capitalRecoveredPct": capital_recovered_pct,
        "infiniteCashOnCash": infinite_coc,
        "postRefiCashOnCashPct": None if infinite_coc else post_coc,
        "postRefiMonthlyCashFlow": post["monthlyCashFlow"],
        "postRefiDscr": post_dscr,
        "postRefiCapRateOnArvPct": (post["netOperatingIncomeAnnual"] / arv * HUNDRED) if arv > ZERO else None,
        "equityCreatedAtRefi": arv - refi_loan,
        "postRefiCore": _jsonify({k: v for k, v in post_core.items() if k != "stack"}),
        "postRefiCapitalStack": _jsonify(post_core["stack"]),
        "postRefiOperating": _jsonify({
            k: v for k, v in post.items()
            if k in ("grossScheduledIncomeAnnual", "effectiveGrossIncomeAnnual",
                     "totalOperatingExpensesAnnual", "netOperatingIncomeAnnual",
                     "annualDebtService", "monthlyDebtService", "monthlyCashFlow",
                     "annualCashFlow", "dscr", "capRateOnPricePct", "capRateOnCostPct",
                     "breakEvenOccupancyPct", "operatingExpenseRatioPct", "grossRentMultiplier")
        }),
        "postRefiHold": proj,
        "bridgeProvenance": bridge_prov,
        "schedule": bridge_schedule,
    }
    returns = {
        "cashInvested": phase1_cash,
        "holdPeriodMonths": int(hold_years) * 12,
        "totalProfit": proj["totalProfit"],
        "roiPct": proj["roiPct"],
        "simpleAnnualizedRoiPct": proj["simpleAnnualizedRoiPct"],
        "annualizedRoiPct": proj["annualizedRoiPct"],
        "equityMultiple": proj["equityMultiple"],
        "irrAnnualPct": proj["irrAnnualPct"],
    }
    return {"brrrr": brrrr_block, "returns": returns, "issues": issues,
            "postCore": post_core, "postOperating": post, "refiSchedule": refi_schedule,
            "refiTerms": refi_terms}


def analyze_fix_and_flip(inp, base):
    asm = DEFAULT_ASSUMPTIONS
    purchase_price = base["purchasePrice"]
    base_rehab = base["rehabCost"]
    issues = []
    arv = F(inp["arv"]) if inp.get("arv") is not None else ZERO
    if arv <= ZERO:
        return {"flip": None, "returns": None,
                "issues": [(ERROR, "FLIP_REQUIRES_ARV", "FIX_AND_FLIP requires an explicit ARV")]}

    contingency_pct = F(inp["rehabContingencyPct"]) if inp.get("rehabContingencyPct") is not None \
        else F(asm["rehabContingencyPct"])
    total_rehab = base_rehab * (ONE + pct_to_rate(contingency_pct))
    hold_months = int(inp.get("flipHoldMonths") if inp.get("flipHoldMonths") is not None else inp.get("holdMonths") or asm["flipHoldMonths"])
    if hold_months <= 0:
        issues.append((WARNING, "FLIP_HOLD_MONTHS_DEFAULTED",
                       "hold months must be positive; used the documented default of %d" % asm["flipHoldMonths"]))
        hold_months = int(asm["flipHoldMonths"])

    model = inp.get("financingModel") or "HARD_MONEY"
    loan_terms = base["financing"]
    loan_amount = loan_terms["loanAmount"]
    loan_fees = loan_terms["newLoanFees"]
    schedule = base["schedule"]

    buy_closing_pct = F(inp["buyClosingCostPct"]) if inp.get("buyClosingCostPct") is not None else None
    if inp.get("closingCosts") is not None:
        buy_closing = F(inp["closingCosts"])
        buy_closing_source = "EXPLICIT"
    elif buy_closing_pct is not None:
        buy_closing = purchase_price * pct_to_rate(buy_closing_pct)
        buy_closing_source = "EXPLICIT_PCT"
    else:
        buy_closing = purchase_price * pct_to_rate(FINANCING_DEFAULTS[model]["closingCostPctOfPrice"])
        buy_closing_source = "MODEL_DEFAULT"
    _ = buy_closing_source

    holding_monthly = inp.get("holdingCostsMonthly")
    if holding_monthly is None:
        holding_monthly = purchase_price * pct_to_rate(asm["holdingCostsMonthlyPctOfPrice"])
    holding_monthly = F(holding_monthly)
    holding_costs = holding_monthly * hold_months

    charged_months = max(hold_months, loan_terms["lenderMinimumInterestMonths"])
    if loan_amount > ZERO:
        monthly_rate = pct_to_rate(loan_terms["interestRatePct"]) / 12
        if loan_terms["amortizationMonths"] > 0 and loan_terms["interestOnlyMonths"] < charged_months:
            interest_paid = ZERO
            for month in range(1, charged_months + 1):
                if month - 1 < len(schedule):
                    interest_paid += schedule[month - 1]["interest"]
                else:
                    interest_paid += loan_amount * monthly_rate
        else:
            interest_paid = interest_only_payment(loan_amount, loan_terms["interestRatePct"]) * charged_months
    else:
        interest_paid = ZERO

    sell_cost_pct = F(inp["sellClosingCostPct"]) if inp.get("sellClosingCostPct") is not None else F(asm["flipSellCostPct"])
    concessions = F(inp.get("sellerConcessions") or 0)

    all_in_cost = purchase_price + total_rehab + buy_closing + holding_costs + interest_paid + loan_fees
    gross_sale = arv
    selling_costs = arv * pct_to_rate(sell_cost_pct) + concessions
    net_sale_proceeds = gross_sale - selling_costs
    profit = net_sale_proceeds - all_in_cost
    cash_invested = all_in_cost - loan_amount
    if cash_invested < ZERO:
        issues.append((INFO, "OVER_FINANCED_PROJECT",
                       "loan principal exceeds all-in cost; cash invested floored at 0"))
        cash_invested = ZERO

    roi_pct = None
    if cash_invested > ZERO:
        roi_pct = profit / cash_invested * HUNDRED
    elif profit > ZERO:
        issues.append((INFO, "INFINITE_RETURN_NO_CASH", "no cash invested and profit positive; ROI undefined"))
    annualized = annualize_from_roi(None if roi_pct is None else roi_pct / HUNDRED, hold_months)
    annualized_pct = annualized * HUNDRED if annualized is not None else None

    profit_margin_arv = profit / arv * HUNDRED
    profit_margin_cost = profit / all_in_cost * HUNDRED if all_in_cost > ZERO else None

    # 70% rule of thumb: MAO = ARV x 0.70 - repairs
    mao_seventy = arv * Fraction("0.70") - total_rehab

    # break-even ARV: profit = 0 -> ARV(1 - sellPct) - concessions = allInCost
    break_even_arv = (all_in_cost + concessions) / (ONE - pct_to_rate(sell_cost_pct)) \
        if sell_cost_pct < HUNDRED else None

    target_profit_pct = F(inp["flipTargetProfitPctOfArv"]) if inp.get("flipTargetProfitPctOfArv") is not None \
        else F(asm["flipMinProfitPctOfArv"])
    target_profit = arv * pct_to_rate(target_profit_pct)
    max_offer = max_offer_for_target_profit(
        arv=arv, sell_cost_pct=sell_cost_pct, concessions=concessions,
        rehab=total_rehab, holding=holding_costs, buy_closing_pct_or_None=buy_closing_pct,
        model=model, target_profit=target_profit, inp=inp, months=hold_months)

    simple_annualized = None
    if roi_pct is not None and hold_months > 0:
        simple_annualized = roi_pct * F(12) / F(hold_months)
    return_metrics = {
        "cashInvested": cash_invested,
        "holdPeriodMonths": hold_months,
        "totalProfit": profit,
        "roiPct": roi_pct,
        "simpleAnnualizedRoiPct": to_float(simple_annualized) if simple_annualized is not None else None,
        "annualizedRoiPct": annualized_pct,
        "equityMultiple": ((cash_invested + profit) / cash_invested) if cash_invested > ZERO else None,
        "irrAnnualPct": annualized_pct,
    }
    flip_block = {
        "arv": arv,
        "rehabBase": base_rehab,
        "rehabContingencyPct": contingency_pct,
        "totalRehabCost": total_rehab,
        "holdMonths": hold_months,
        "buyClosingCosts": buy_closing,
        "holdingCostsTotal": holding_costs,
        "holdingCostsMonthly": holding_monthly,
        "loanAmount": loan_amount,
        "loanInterestPaid": interest_paid,
        "loanFeesAndPoints": loan_fees,
        "lenderMinimumInterestMonths": loan_terms["lenderMinimumInterestMonths"],
        "allInCost": all_in_cost,
        "grossSalePrice": gross_sale,
        "sellingCosts": selling_costs,
        "netSaleProceeds": net_sale_proceeds,
        "profit": profit,
        "profitMarginOnArvPct": profit_margin_arv,
        "profitMarginOnCostPct": profit_margin_cost,
        "returnMetrics": return_metrics,
        "maoSeventyRule": mao_seventy,
        "maxOfferForTargetProfit": max_offer,
        "targetProfitPctOfArv": target_profit_pct,
        "targetProfitAmount": target_profit,
        "breakEvenArv": break_even_arv,
        "breakEvenArvCushionPct": ((arv - break_even_arv) / arv * HUNDRED) if break_even_arv is not None and arv > ZERO else None,
    }
    return {"flip": flip_block, "returns": return_metrics, "issues": issues}


def flip_costs_for_price(price, arv, sell_cost_pct, concessions, rehab, holding,
                         model, inp, months):
    """All-in cost and profit for an arbitrary purchase price (used by the MAO solver)."""
    overrides = dict(inp.get("bridgeOverrides") or inp.get("loanOverrides") or {})
    overrides = {k: v for k, v in overrides.items() if k in
                 ("interestRatePct", "loanTermMonths", "amortizationMonths",
                  "interestOnlyMonths", "originationPointsPct", "lenderFees",
                  "downPaymentPct", "loanAmount", "maxLtvPct")}
    if inp.get("interestRatePct") is not None:
        overrides["interestRatePct"] = inp["interestRatePct"]
    if inp.get("loanTermMonths") is not None:
        overrides["loanTermMonths"] = inp["loanTermMonths"]
    if inp.get("amortizationMonths") is not None:
        overrides["amortizationMonths"] = inp["amortizationMonths"]
    if inp.get("interestOnlyMonths") is not None:
        overrides["interestOnlyMonths"] = inp["interestOnlyMonths"]
    if inp.get("originationPointsPct") is not None:
        overrides["originationPointsPct"] = inp["originationPointsPct"]
    if inp.get("lenderFees") is not None:
        overrides["lenderFees"] = inp["lenderFees"]
    if inp.get("downPaymentPct") is not None:
        overrides["downPaymentPct"] = inp["downPaymentPct"]

    terms, _, _ = resolve_financing(model, price, rehab, arv, overrides)
    if inp.get("closingCosts") is not None:
        buy_closing = F(inp["closingCosts"])
    elif inp.get("buyClosingCostPct") is not None:
        buy_closing = price * pct_to_rate(inp["buyClosingCostPct"])
    else:
        buy_closing = price * pct_to_rate(FINANCING_DEFAULTS[model]["closingCostPctOfPrice"])

    charged = max(months, terms["lenderMinimumInterestMonths"])
    interest = interest_only_payment(terms["loanAmount"], terms["interestRatePct"]) * charged \
        if terms["loanAmount"] > ZERO else ZERO
    all_in = price + rehab + buy_closing + holding + interest + terms["newLoanFees"]
    profit = arv * (ONE - pct_to_rate(sell_cost_pct)) - concessions - all_in
    return profit, all_in, terms


def max_offer_for_target_profit(arv, sell_cost_pct, concessions, rehab, holding,
                                buy_closing_pct_or_None, model, target_profit, inp, months):
    """Bisection on purchase price. Profit is strictly decreasing in price, so a
    bracketed bisection converges deterministically (200 iterations, no epsilon
    surprises). Returns None when even a $0 purchase misses the target."""
    _ = buy_closing_pct_or_None

    def profit_at(price):
        p, _, _ = flip_costs_for_price(price, arv, sell_cost_pct, concessions, rehab,
                                       holding, model, inp, months)
        return p

    low, high = ZERO, arv * 2
    if profit_at(low) < target_profit:
        return None
    if profit_at(high) > target_profit:
        return high
    for _ in range(200):
        mid = (low + high) / 2
        if profit_at(mid) > target_profit:
            low = mid
        else:
            high = mid
    return (low + high) / 2


def analyze_wholesale(inp, base):
    asm = DEFAULT_ASSUMPTIONS
    issues = []
    contract_price = base["purchasePrice"]
    arv = F(inp["arv"]) if inp.get("arv") is not None else (base["purchasePrice"] * Fraction("1.25"))
    mode = inp.get("wholesaleMode") or "ASSIGNMENT"
    if mode not in ("ASSIGNMENT", "DOUBLE_CLOSE"):
        issues.append((ERROR, "UNKNOWN_WHOLESALE_MODE", "wholesaleMode defaulted to ASSIGNMENT"))
        mode = "ASSIGNMENT"

    assignment_fee = inp.get("assignmentFee")
    if assignment_fee is None:
        assignment_fee = arv * pct_to_rate(inp.get("assignmentFeePctOfArv") or asm["wholesaleAssignmentFeePctOfArv"])
        fee_source = "DEFAULT_PCT_OF_ARV"
    else:
        fee_source = "EXPLICIT"
    assignment_fee = F(assignment_fee)

    earnest = F(inp.get("earnestMoney") or 0)
    marketing = F(inp.get("marketingCost") if inp.get("marketingCost") is not None else asm["wholesalerMarketingCost"])
    days = int(inp.get("daysToClose") if inp.get("daysToClose") is not None else asm["daysToCloseWholesale"])
    if days <= 0:
        issues.append((WARNING, "DAYS_TO_CLOSE_DEFAULTED",
                       "daysToClose must be positive; used the documented default of %d" % asm["daysToCloseWholesale"]))
        days = int(asm["daysToCloseWholesale"])

    buyer_rehab = F(inp.get("buyerRehabEstimate") or 0)
    buyer_sell_cost_pct = F(inp.get("buyerSellCostPct") if inp.get("buyerSellCostPct") is not None else asm["saleCostPct"])
    buyer_min_profit_pct = F(inp.get("buyerMinProfitPctOfArv") if inp.get("buyerMinProfitPctOfArv") is not None
                             else asm["wholesaleBuyerMinProfitPctOfArv"])

    buy_side_closing = ZERO
    sell_side_closing = ZERO
    if mode == "DOUBLE_CLOSE":
        buy_pct = F(inp.get("buyClosingCostPct") if inp.get("buyClosingCostPct") is not None else "1.0")
        sell_pct = F(inp.get("sellClosingCostPct") if inp.get("sellClosingCostPct") is not None else "1.0")
        buy_side_closing = contract_price * pct_to_rate(buy_pct)
        sell_side_closing = (contract_price + assignment_fee) * pct_to_rate(sell_pct)

    gross_revenue = assignment_fee
    total_costs = marketing + buy_side_closing + sell_side_closing
    profit = gross_revenue - total_costs
    cash_invested = earnest + marketing
    roi_pct = None
    if cash_invested > ZERO:
        roi_pct = profit / cash_invested * HUNDRED
    annualized = annualize_from_roi(None if roi_pct is None else roi_pct / HUNDRED, days / 30.0)
    annualized_pct = annualized * HUNDRED if annualized is not None else None

    buyer_all_in = contract_price + assignment_fee + buyer_rehab
    buyer_profit = arv * (ONE - pct_to_rate(buyer_sell_cost_pct)) - buyer_all_in
    buyer_profit_pct = buyer_profit / arv * HUNDRED if arv > ZERO else None
    max_assignable_fee = arv * (ONE - pct_to_rate(buyer_sell_cost_pct)) - buyer_rehab - contract_price \
        - arv * pct_to_rate(buyer_min_profit_pct)

    if buyer_profit_pct is not None and buyer_profit_pct < buyer_min_profit_pct:
        issues.append((WARNING, "ASSIGNMENT_FEE_MAY_BE_REJECTED",
                       "end buyer's projected margin %.2f%% of ARV is below the %.2f%% target"
                       % (to_float(buyer_profit_pct), to_float(buyer_min_profit_pct))))
    mao_seventy = arv * Fraction("0.70") - buyer_rehab
    if contract_price > mao_seventy:
        issues.append((WARNING, "CONTRACT_ABOVE_70_PCT_RULE",
                       "contract price is above ARV x 70%% - repairs (%.2f)" % to_float(mao_seventy)))

    simple_annualized = None
    if roi_pct is not None and days > 0:
        simple_annualized = roi_pct * F(365) / F(days)
    returns = {
        "cashInvested": cash_invested,
        "holdPeriodMonths": days / 30.0,
        "totalProfit": profit,
        "roiPct": roi_pct,
        "simpleAnnualizedRoiPct": to_float(simple_annualized) if simple_annualized is not None else None,
        "annualizedRoiPct": annualized_pct,
        "equityMultiple": ((cash_invested + profit) / cash_invested) if cash_invested > ZERO else None,
        "irrAnnualPct": annualized_pct,
    }
    block = {
        "mode": mode,
        "contractPrice": contract_price,
        "arv": arv,
        "assignmentFee": assignment_fee,
        "assignmentFeeSource": fee_source,
        "earnestMoney": earnest,
        "marketingCost": marketing,
        "buySideClosingCosts": buy_side_closing,
        "sellSideClosingCosts": sell_side_closing,
        "grossRevenue": gross_revenue,
        "totalCosts": total_costs,
        "profit": profit,
        "daysToClose": days,
        "buyerAllInCost": buyer_all_in,
        "buyerProfit": buyer_profit,
        "buyerProfitPctOfArv": buyer_profit_pct,
        "maxAssignableFee": max_assignable_fee,
        "maoSeventyRule": mao_seventy,
        "returnMetrics": returns,
    }
    return {"wholesale": block, "returns": returns, "issues": issues}


# ---------------------------------------------------------------------------
# 8. core assembly + orchestrator
# ---------------------------------------------------------------------------

def build_core(inp, purchase_price, rehab_cost, closing_costs, loan_terms, operating,
               schedule, extra_cash=ZERO):
    stack = capital_stack(inp, purchase_price, rehab_cost, closing_costs, loan_terms, extra_cash)
    total_cash = stack["totalCashRequired"]
    coc = cash_on_cash_pct(operating["annualCashFlow"], total_cash)
    return {
        "purchasePrice": purchase_price,
        "closingCosts": closing_costs,
        "rehabCost": rehab_cost,
        "loanAmount": loan_terms["loanAmount"],
        "downPaymentAmount": stack["downPaymentAmount"],
        "ltvOfPricePct": stack["ltvOfPricePct"],
        "totalCashRequired": total_cash,
        "cashOnCashPct": coc,
        "capRateOnPricePct": operating["capRateOnPricePct"],
        "capRateOnCostPct": operating["capRateOnCostPct"],
        "grossRentMultiplier": operating["grossRentMultiplier"],
        "breakEvenOccupancyPct": operating["breakEvenOccupancyPct"],
        "operatingExpenseRatioPct": operating["operatingExpenseRatioPct"],
        "monthlyDebtService": operating["monthlyDebtService"],
        "annualDebtService": operating["annualDebtService"],
        "dscr": operating["dscr"],
        "noiAnnual": operating["netOperatingIncomeAnnual"],
        "monthlyCashFlow": operating["monthlyCashFlow"],
        "annualCashFlow": operating["annualCashFlow"],
        "effectiveGrossIncomeAnnual": operating["effectiveGrossIncomeAnnual"],
        "grossScheduledIncomeAnnual": operating["grossScheduledIncomeAnnual"],
        "totalOperatingExpensesAnnual": operating["totalOperatingExpensesAnnual"],
        "stack": stack,
    }


def qualify(inp, strategy, core, operating, extra=None):
    """Deterministic, rule-based verdict - no model, no randomness.

    A strategy whose required inputs are missing cannot be rated: it fails
    outright with an explicit criterion instead of being silently judged on
    half-empty inputs.
    """
    asm = DEFAULT_ASSUMPTIONS
    failed = []
    passed = []
    extra = extra or {}

    required = {"BUY_AND_HOLD": None, "BRRRR": "brrrr", "FIX_AND_FLIP": "flip", "WHOLESALE": "wholesale"}
    key = required.get(strategy)
    if key and not extra.get(key):
        return {"rating": "FAILS_CRITERIA", "passedCriteria": [],
                "failedCriteria": ["MISSING_REQUIRED_INPUT"]}

    if strategy in ("BUY_AND_HOLD", "BRRRR"):
        min_coc = F(inp["minCashOnCashPct"]) if inp.get("minCashOnCashPct") is not None else F(asm["minCashOnCashPct"])
        min_dscr = F(inp["minDscrForLender"]) if inp.get("minDscrForLender") is not None else F(asm["minDscrForLender"])
        min_cap = F(inp["minCapRatePct"]) if inp.get("minCapRatePct") is not None else F(asm["minCapRatePct"])
        coc = core["cashOnCashPct"]
        if coc is None:
            passed.append("CASH_ON_CASH_NOT_APPLICABLE")
        elif coc >= min_coc:
            passed.append("CASH_ON_CASH")
        else:
            failed.append("CASH_ON_CASH_BELOW_MIN")
        if core["dscr"] is None:
            passed.append("DSCR_NOT_APPLICABLE_ALL_CASH")
        elif core["dscr"] >= min_dscr:
            passed.append("DSCR")
        else:
            failed.append("DSCR_BELOW_MIN")
        if core["capRateOnPricePct"] is not None and core["capRateOnPricePct"] >= min_cap:
            passed.append("CAP_RATE")
        else:
            failed.append("CAP_RATE_BELOW_MIN")
        if core["monthlyCashFlow"] > ZERO:
            passed.append("POSITIVE_CASH_FLOW")
        else:
            failed.append("NEGATIVE_CASH_FLOW")

        if strategy == "BRRRR":
            if extra and extra.get("infiniteCashOnCash"):
                passed.append("CAPITAL_FULLY_RECOVERED")
            elif extra and extra.get("postRefiCashOnCashPct") is not None \
                    and extra["postRefiCashOnCashPct"] >= min_coc:
                passed.append("POST_REFI_CASH_ON_CASH")
            else:
                failed.append("POST_REFI_CASH_ON_CASH_BELOW_MIN")
    elif strategy == "FIX_AND_FLIP":
        # Project deals are judged on the deal itself (profit, margin, return on
        # cash) - not on an annualised figure that explodes for short holds.
        flip = extra["flip"]
        min_margin = F(inp["flipMinProfitPctOfArv"]) if inp.get("flipMinProfitPctOfArv") is not None \
            else F(asm["flipMinProfitPctOfArv"])
        min_roi = F(inp["flipMinRoiPctOfCash"]) if inp.get("flipMinRoiPctOfCash") is not None \
            else F(asm["flipMinRoiPctOfCash"])
        if flip["profit"] > ZERO:
            passed.append("PROFITABLE")
        else:
            failed.append("NOT_PROFITABLE")
        if flip["profitMarginOnArvPct"] >= min_margin:
            passed.append("PROFIT_MARGIN")
        else:
            failed.append("PROFIT_MARGIN_BELOW_MIN")
        roi = flip["returnMetrics"]["roiPct"]
        if roi is not None and roi >= min_roi:
            passed.append("RETURN_ON_CASH")
        else:
            failed.append("RETURN_ON_CASH_BELOW_MIN")
    elif strategy == "WHOLESALE":
        wholesale = extra["wholesale"]
        min_profit = F(inp["wholesaleMinimumProfit"]) if inp.get("wholesaleMinimumProfit") is not None \
            else F(asm["wholesaleMinimumProfitAmount"])
        if wholesale["profit"] > ZERO:
            passed.append("PROFITABLE")
        else:
            failed.append("NOT_PROFITABLE")
        if wholesale["profit"] >= min_profit:
            passed.append("FEE_ABOVE_MINIMUM")
        else:
            failed.append("FEE_BELOW_MINIMUM")
        if wholesale["maxAssignableFee"] >= wholesale["assignmentFee"]:
            passed.append("FEE_WITHIN_BUYER_HEADROOM")
        else:
            failed.append("FEE_EXCEEDS_BUYER_HEADROOM")

    if failed:
        if len(failed) == 1 and failed[0] in ("PROFIT_MARGIN_BELOW_MIN", "RETURN_ON_CASH_BELOW_MIN",
                                              "FEE_BELOW_MINIMUM"):
            rating = "MARGINAL"
        elif "NOT_PROFITABLE" in failed or "NEGATIVE_CASH_FLOW" in failed:
            rating = "FAILS_CRITERIA"
        else:
            rating = "MARGINAL"
    else:
        rating = "STRONG" if (core["monthlyCashFlow"] > ZERO) else "ACCEPTABLE"

    return {"rating": rating, "passedCriteria": passed, "failedCriteria": failed}


def sanitize_inputs(raw):
    """Replace non-finite numbers (NaN, +/-Inf) with "not supplied" + an ERROR.

    A UI text field can hand us Infinity ("1e999") or NaN, and one NaN silently
    poisons every downstream metric. The engine refuses to do that: it reports
    the offending field and falls back to the documented default for it.
    """
    issues = []
    clean = {}
    for key, value in (raw or {}).items():
        if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
            issues.append((ERROR, "NON_FINITE_INPUT",
                           "input '%s' was not finite and was ignored (documented default applies)" % key))
            clean[key] = None
        elif isinstance(value, dict):
            sub = {}
            for sub_key, sub_value in value.items():
                if isinstance(sub_value, float) and (math.isnan(sub_value) or math.isinf(sub_value)):
                    issues.append((ERROR, "NON_FINITE_INPUT",
                                   "input '%s.%s' was not finite and was ignored" % (key, sub_key)))
                    sub[sub_key] = None
                else:
                    sub[sub_key] = sub_value
            clean[key] = sub
        else:
            clean[key] = value
    return clean, issues


def analyze(inp, include_schedules=False):
    """The single entry point. Deterministic: identical dict in -> identical dict out."""
    inp, sanitize_issues = sanitize_inputs(inp)
    strategy = inp.get("strategy") or "BUY_AND_HOLD"
    issues = list(sanitize_issues)
    if strategy not in STRATEGIES:
        issues.append((ERROR, "UNKNOWN_STRATEGY", "unknown strategy '%s'; used BUY_AND_HOLD" % strategy))
        strategy = "BUY_AND_HOLD"

    purchase_price = F(inp.get("purchasePrice") or 0)   # sanitised NaN/Inf arrives as None
    if purchase_price <= ZERO:
        issues.append((ERROR, "NON_POSITIVE_PURCHASE_PRICE", "purchasePrice must be greater than zero"))
    rehab_cost = F(inp.get("rehabCost") or 0)
    if rehab_cost < ZERO:
        issues.append((ERROR, "NEGATIVE_REHAB_COST", "rehabCost cannot be negative"))
        rehab_cost = ZERO
    if inp.get("monthlyRent") is not None and F(inp["monthlyRent"]) < ZERO:
        issues.append((ERROR, "NEGATIVE_RENT", "monthlyRent cannot be negative"))

    model = inp.get("financingModel") or ("HARD_MONEY" if strategy in ("FIX_AND_FLIP", "BRRRR") else "CONVENTIONAL")
    if model not in FINANCING_MODELS:
        issues.append((ERROR, "UNKNOWN_FINANCING_MODEL", "unknown financingModel '%s'; used CONVENTIONAL" % model))
        model = "CONVENTIONAL"

    arv = inp.get("arv")
    overrides = dict(inp.get("loanOverrides") or {})
    for key in ("downPaymentPct", "downPaymentAmount", "loanAmount", "interestRatePct",
                "loanTermMonths", "amortizationMonths", "interestOnlyMonths",
                "originationPointsPct", "lenderFees", "lenderReserveMonths", "maxLtvPct"):
        if inp.get(key) is not None:
            overrides[key] = inp[key]

    loan_terms, provenance, financing_issues = resolve_financing(model, purchase_price, rehab_cost, arv, overrides)
    issues.extend(financing_issues)

    closing_costs, closing_source = _closing_cost_for(inp, purchase_price)
    if closing_source == "NONE":
        closing_costs = purchase_price * pct_to_rate(FINANCING_DEFAULTS[model]["closingCostPctOfPrice"])

    schedule = amortization_schedule(
        loan_terms["loanAmount"], loan_terms["interestRatePct"], loan_terms["loanTermMonths"],
        loan_terms["amortizationMonths"], loan_terms["interestOnlyMonths"])

    operating = operating_statement(inp, purchase_price, rehab_cost, loan_terms, schedule)
    issues.extend(operating["issues"])

    core = build_core(inp, purchase_price, rehab_cost, closing_costs, loan_terms, operating, schedule)

    result = {
        "specVersion": DEFAULT_ASSUMPTIONS["version"],
        "strategy": strategy,
        "financingModel": model,
        "purchasePrice": to_float(purchase_price),
        "rehabCost": to_float(rehab_cost),
        "financing": {
            "model": model,
            "displayName": FINANCING_DEFAULTS[model]["displayName"],
            "loanAmount": to_float(loan_terms["loanAmount"]),
            "downPaymentAmount": to_float(loan_terms["downPaymentAmount"]),
            "downPaymentPctOfPrice": to_float(loan_terms["downPaymentAmount"] / purchase_price * HUNDRED) if purchase_price > 0 else None,
            "interestRatePct": to_float(loan_terms["interestRatePct"]),
            "loanTermMonths": loan_terms["loanTermMonths"],
            "amortizationMonths": loan_terms["amortizationMonths"],
            "interestOnlyMonths": loan_terms["interestOnlyMonths"],
            "monthlyPayment": to_float(loan_terms["monthlyPayment"]),
            "newLoanFeesAndPoints": to_float(loan_terms["newLoanFees"]),
            "originationPointsPct": to_float(loan_terms["originationPointsPct"]),
            "lenderFees": to_float(loan_terms["lenderFees"]),
            "lenderReserveMonths": loan_terms["lenderReserveMonths"],
            "isInterestOnly": loan_terms["isInterestOnly"],
            "requestedLoanAmount": to_float(loan_terms["requestedLoanAmount"]),
            "loanCappedByLender": loan_terms["loanCappedByLender"],
            "bindingLoanCeiling": loan_terms["bindingLoanCeiling"],
            "maxLtvPct": to_float(loan_terms["maxLtvPct"]) if loan_terms["maxLtvPct"] is not None else None,
        "maxLtcPct": to_float(loan_terms["maxLtcPct"]) if loan_terms["maxLtcPct"] is not None else None,
        },
        "operating": {
            "grossScheduledIncomeAnnual": to_float(operating["grossScheduledIncomeAnnual"]),
            "vacancyAndCreditLossAnnual": to_float(operating["vacancyAndCreditLossAnnual"]),
            "effectiveGrossIncomeAnnual": to_float(operating["effectiveGrossIncomeAnnual"]),
            "totalOperatingExpensesAnnual": to_float(operating["totalOperatingExpensesAnnual"]),
            "netOperatingIncomeAnnual": to_float(operating["netOperatingIncomeAnnual"]),
            "capitalReservesAnnual": to_float(operating["capitalReservesAnnual"]),
            "capexTreatment": operating["capexTreatment"],
            "expenseLines": [
                {"key": k, "annualAmount": to_float(a), "basis": b} for k, a, b in operating["expenseLines"]
            ],
        },
        "core": {
            "purchasePrice": to_float(core["purchasePrice"]),
            "closingCosts": to_float(closing_costs),
            "rehabCost": to_float(rehab_cost),
            "loanAmount": to_float(core["loanAmount"]),
            "downPaymentAmount": to_float(core["downPaymentAmount"]),
            "ltvOfPricePct": to_float(core["ltvOfPricePct"]) if core["ltvOfPricePct"] is not None else None,
            "totalCashRequired": to_float(core["totalCashRequired"]),
            "monthlyDebtService": to_float(core["monthlyDebtService"]),
            "annualDebtService": to_float(core["annualDebtService"]),
            "noiAnnual": to_float(core["noiAnnual"]),
            "monthlyCashFlow": to_float(core["monthlyCashFlow"]),
            "annualCashFlow": to_float(core["annualCashFlow"]),
            "capRateOnPricePct": to_float(core["capRateOnPricePct"]) if core["capRateOnPricePct"] is not None else None,
            "capRateOnCostPct": to_float(core["capRateOnCostPct"]) if core["capRateOnCostPct"] is not None else None,
            "cashOnCashPct": to_float(core["cashOnCashPct"]) if core["cashOnCashPct"] is not None else None,
            "dscr": to_float(core["dscr"]) if core["dscr"] is not None else None,
            "breakEvenOccupancyPct": to_float(core["breakEvenOccupancyPct"]) if core["breakEvenOccupancyPct"] is not None else None,
            "grossRentMultiplier": to_float(core["grossRentMultiplier"]) if core["grossRentMultiplier"] is not None else None,
            "operatingExpenseRatioPct": to_float(core["operatingExpenseRatioPct"]) if core["operatingExpenseRatioPct"] is not None else None,
            "effectiveGrossIncomeAnnual": to_float(core["effectiveGrossIncomeAnnual"]),
        },
        "capitalStack": {
            "downPaymentAmount": to_float(core["stack"]["downPaymentAmount"]),
            "cashRehab": to_float(core["stack"]["cashRehab"]),
            "closingCosts": to_float(core["stack"]["closingCosts"]),
            "loanFeesAndPoints": to_float(core["stack"]["loanFeesAndPoints"]),
            "lenderReserves": to_float(core["stack"]["lenderReserves"]),
            "sellerCredits": to_float(core["stack"]["sellerCredits"]),
            "totalCashRequired": to_float(core["stack"]["totalCashRequired"]),
        },
        # provenance: every resolved field records WHERE its value came from
        "assumptionsUsed": {k: {"source": v} for k, v in sorted(provenance.items())},
        "validation": [{"code": c, "severity": s, "message": m} for s, c, m in issues],
    }

    extra = None
    if strategy == "BUY_AND_HOLD":
        extra = analyze_buy_and_hold(inp, {"operating": operating, "financing": loan_terms,
                                           "schedule": schedule, "purchasePrice": purchase_price,
                                           "rehabCost": rehab_cost, "core": core})
    elif strategy == "BRRRR":
        extra = analyze_brrrr(inp, {"operating": operating, "financing": loan_terms, "schedule": schedule,
                                    "purchasePrice": purchase_price, "rehabCost": rehab_cost, "core": core,
                                    "provenance": provenance})
    elif strategy == "FIX_AND_FLIP":
        extra = analyze_fix_and_flip(inp, {"operating": operating, "financing": loan_terms, "schedule": schedule,
                                             "purchasePrice": purchase_price, "rehabCost": rehab_cost, "core": core})
    else:
        extra = analyze_wholesale(inp, {"operating": operating, "financing": loan_terms, "schedule": schedule,
                                        "purchasePrice": purchase_price, "rehabCost": rehab_cost, "core": core})

    for key in ("hold", "brrrr", "flip", "wholesale"):
        if extra.get(key) is not None:
            result[key] = _jsonify(extra[key])
    result["validation"].extend(
        {"code": c, "severity": s, "message": m} for s, c, m in extra.get("issues", []))
    result["returns"] = _jsonify(extra.get("returns")) if extra.get("returns") else None

    # Which headline rental metrics actually drive THIS strategy's decision?
    applicability = {
        "netOperatingIncomeAnnual": "APPLICABLE",
        "capRate": "APPLICABLE",
        "dscr": "APPLICABLE",
        "cashOnCash": "APPLICABLE",
    }
    if strategy == "FIX_AND_FLIP":
        applicability = {
            "netOperatingIncomeAnnual": "INFORMATIONAL_ONLY",
            "capRate": "INFORMATIONAL_ONLY",
            "dscr": "NOT_APPLICABLE",
            "cashOnCash": "NOT_APPLICABLE",
        }
    elif strategy == "WHOLESALE":
        applicability = {
            "netOperatingIncomeAnnual": "NOT_APPLICABLE",
            "capRate": "NOT_APPLICABLE",
            "dscr": "NOT_APPLICABLE",
            "cashOnCash": "NOT_APPLICABLE",
        }
    result["metricApplicability"] = applicability

    # BRRRR decisions are made on the STABILISED (post-refinance) numbers.
    if strategy == "BRRRR" and result.get("brrrr"):
        post_core = extra["postCore"]
        decision_core = dict(result["core"])
        decision_core.update({
            "loanAmount": to_float(post_core["loanAmount"]),
            "ltvOfPricePct": to_float(post_core["ltvOfPricePct"]) if post_core["ltvOfPricePct"] is not None else None,
            "totalCashRequired": to_float(post_core["totalCashRequired"]),
            "monthlyDebtService": to_float(post_core["monthlyDebtService"]),
            "annualDebtService": to_float(post_core["annualDebtService"]),
            "monthlyCashFlow": to_float(post_core["monthlyCashFlow"]),
            "annualCashFlow": to_float(post_core["annualCashFlow"]),
            "dscr": to_float(post_core["dscr"]) if post_core["dscr"] is not None else None,
            "cashOnCashPct": to_float(post_core["cashOnCashPct"]) if post_core["cashOnCashPct"] is not None else None,
        })
        core = {**core, **{k: post_core[k] for k in ("loanAmount", "totalCashRequired")}}
        core["dscr"] = post_core["dscr"]
        core["cashOnCashPct"] = post_core["cashOnCashPct"]
        verdict_core = {**result["core"], **decision_core}
        # keep a numeric copy for the pure-Fraction qualifier
        core = {**core,
                "monthlyCashFlow": post_core["monthlyCashFlow"],
                "dscr": post_core["dscr"],
                "cashOnCashPct": post_core["cashOnCashPct"],
                "capRateOnPricePct": post_core["capRateOnPricePct"]}
    verdict_inputs = {"flip": extra.get("flip"), "wholesale": extra.get("wholesale"),
                      "brrrr": extra.get("brrrr"), "postRefiCashOnCashPct":
                          (Fraction(str(result["brrrr"]["postRefiCashOnCashPct"]))
                           if result.get("brrrr") and result["brrrr"]["postRefiCashOnCashPct"] is not None
                           else None),
                      "infiniteCashOnCash": bool(result.get("brrrr") and result["brrrr"]["infiniteCashOnCash"])}
    result["verdict"] = _jsonify(qualify(inp, strategy, core, operating, verdict_inputs))
    if not include_schedules:
        for key in ("brrrr",):
            if result.get(key) and "schedule" in result[key]:
                del result[key]["schedule"]
    return result


def _jsonify(value):
    """Fraction/float/None -> JSON-safe primitives, keys preserved."""
    if isinstance(value, Fraction):
        return to_float(value)
    if isinstance(value, dict):
        return {k: _jsonify(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonify(v) for v in value]
    if isinstance(value, bool):
        return value
    return value
