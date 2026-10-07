#!/usr/bin/env python3
"""
Exhaustive self-check suite for the underwriting oracle.

Run:  python3 selfcheck.py

Three families of checks:
  IDENTITY     - algebraic invariants that must hold exactly (Fraction maths)
  COMPARATIVE  - monotonicity / comparative statics (sign of a derivative)
  ROBUSTNESS   - degenerate, hostile and boundary inputs: no crash, no NaN/Inf,
                 no silent nonsense, and the right validation code raised.

Every check here is mirrored by a JUnit test in the Android module, so the
Python suite is the fast feedback loop and the Kotlin suite is the shipped gate.
"""

from __future__ import annotations

import json
import math
from fractions import Fraction

from oracle import (
    ZERO, HUNDRED, F, amortization_schedule, amortizing_payment, analyze,
    balance_after_months, interest_only_payment, schedule_interest,
    schedule_year_one_debt_service, annualize_from_roi, FINANCING_MODELS,
)

PASSED: list[str] = []
FAILED: list[str] = []


def check(name, condition, detail=""):
    if condition:
        PASSED.append(name)
    else:
        FAILED.append("%s :: %s" % (name, detail))


def close(a, b, tol=1e-6, rel=1e-9):
    if a is None or b is None:
        return a is None and b is None
    return abs(a - b) <= max(tol, abs(b) * rel)


def finite(*values):
    for v in values:
        if v is None:
            continue
        if isinstance(v, float) and (math.isnan(v) or math.isinf(v)):
            return False
    return True


def _has_complex(obj):
    if isinstance(obj, dict):
        return any(_has_complex(v) for v in obj.values())
    if isinstance(obj, (list, tuple)):
        return any(_has_complex(v) for v in obj)
    return isinstance(obj, complex)


def deep_finite(obj):
    if isinstance(obj, dict):
        return all(deep_finite(v) for v in obj.values())
    if isinstance(obj, (list, tuple)):
        return all(deep_finite(v) for v in obj)
    if isinstance(obj, float):
        return not (math.isnan(obj) or math.isinf(obj))
    return True


# ---------------------------------------------------------------------------
# fixtures
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# 1. IDENTITY checks
# ---------------------------------------------------------------------------

def identity_checks():
    r = analyze(rental())
    op, core = r["operating"], r["core"]

    check("id.gsi_is_annualised_total_income",
          close(op["grossScheduledIncomeAnnual"], (3600 + 150) * 12))
    check("id.egi_equals_gsi_minus_vacancy",
          close(op["effectiveGrossIncomeAnnual"],
                op["grossScheduledIncomeAnnual"] - op["vacancyAndCreditLossAnnual"]))
    check("id.vacancy_is_pct_of_gsi",
          close(op["vacancyAndCreditLossAnnual"], op["grossScheduledIncomeAnnual"] * 0.05))
    check("id.opex_is_sum_of_lines",
          close(op["totalOperatingExpensesAnnual"],
                sum(line["annualAmount"] for line in op["expenseLines"])))
    check("id.noi_equals_egi_minus_opex",
          close(op["netOperatingIncomeAnnual"],
                op["effectiveGrossIncomeAnnual"] - op["totalOperatingExpensesAnnual"]))
    check("id.annual_cash_flow_equals_noi_minus_debt",
          close(core["annualCashFlow"], core["noiAnnual"] - core["annualDebtService"]))
    check("id.monthly_cash_flow_is_annual_over_12",
          close(core["monthlyCashFlow"], core["annualCashFlow"] / 12))
    check("id.dscr_equals_noi_over_debt_service",
          close(core["dscr"], core["noiAnnual"] / core["annualDebtService"]))
    check("id.cap_rate_on_price",
          close(core["capRateOnPricePct"], core["noiAnnual"] / core["purchasePrice"] * 100))
    check("id.cap_rate_on_cost",
          close(core["capRateOnCostPct"],
                core["noiAnnual"] / (core["purchasePrice"] + 10000 + 0) * 100))
    check("id.cash_on_cash_is_cf_over_cash",
          close(core["cashOnCashPct"], core["annualCashFlow"] / core["totalCashRequired"] * 100))
    check("id.total_cash_required_composition",
          close(core["totalCashRequired"],
                r["capitalStack"]["downPaymentAmount"] + r["capitalStack"]["closingCosts"]
                + r["capitalStack"]["cashRehab"] + r["capitalStack"]["loanFeesAndPoints"]
                + r["capitalStack"]["lenderReserves"] - r["capitalStack"]["sellerCredits"]))
    check("id.loan_plus_down_equals_price",
          close(core["loanAmount"] + core["downPaymentAmount"], 400000))
    check("id.ltv_is_loan_over_price", close(core["ltvOfPricePct"], 320000 / 400000 * 100))
    check("id.grm_is_price_over_gsi",
          close(core["grossRentMultiplier"], 400000 / op["grossScheduledIncomeAnnual"]))
    check("id.break_even_is_fixed_costs_over_gsi",
          close(core["breakEvenOccupancyPct"],
                (op["totalOperatingExpensesAnnual"] + core["annualDebtService"])
                / op["grossScheduledIncomeAnnual"] * 100))

    hold = r["hold"]
    check("id.hold_profit_definition",
          close(hold["totalProfit"],
                hold["cumulativeCashFlow"] + hold["netSaleProceeds"] - core["totalCashRequired"]))
    check("id.hold_roi_definition",
          close(hold["roiPct"], hold["totalProfit"] / core["totalCashRequired"] * 100))
    check("id.hold_net_proceeds_definition",
          close(hold["netSaleProceeds"], hold["exitValue"] - hold["saleCosts"] - hold["loanBalanceAtExit"]))
    check("id.hold_exit_value_compounds",
          close(hold["exitValue"], 400000 * (1.035 ** 5), tol=0.01))
    check("id.hold_sale_costs_are_pct_of_exit_value",
          close(hold["saleCosts"], hold["exitValue"] * 0.08, tol=0.01))
    check("id.hold_equity_multiple",
          close(hold["equityMultiple"],
                (hold["cumulativeCashFlow"] + hold["netSaleProceeds"]) / core["totalCashRequired"]))
    for year in hold["years"]:
        check("id.hold_year_%d_noi" % year["year"],
              close(year["netOperatingIncome"], year["effectiveGrossIncome"] - year["operatingExpenses"]))
        check("id.hold_year_%d_cf" % year["year"],
              close(year["cashFlow"], year["netOperatingIncome"] - year["debtService"]))
        if year["dscr"] is not None:
            check("id.hold_year_%d_dscr" % year["year"],
                  close(year["dscr"], year["netOperatingIncome"] / year["debtService"]))
    check("id.hold_year1_matches_core",
          close(hold["years"][0]["netOperatingIncome"], core["noiAnnual"])
          and close(hold["years"][0]["cashFlow"], core["annualCashFlow"]))

    # cash flow is rent growth minus expense growth, so year 3 NOI must beat year 1
    check("id.hold_noi_grows_with_growth",
          hold["years"][2]["netOperatingIncome"] > hold["years"][0]["netOperatingIncome"])


def amortization_identity_checks():
    s = amortization_schedule(250000, "6.5", 360, 360, 0)
    check("am.final_balance_exactly_zero", s[-1]["closingBalance"] == ZERO)
    check("am.principal_sums_to_principal",
          sum(r["principal"] for r in s) == F(250000))
    check("am.payments_equal_principal_plus_interest",
          sum(r["payment"] for r in s) == F(250000) + sum(r["interest"] for r in s))
    check("am.balance_never_increases",
          all(s[i]["closingBalance"] <= s[i]["openingBalance"] for i in range(len(s))))
    check("am.interest_equals_balance_times_rate",
          all(close(float(r["interest"]), float(r["openingBalance"]) * 0.065 / 12, tol=1e-6) for r in s))
    check("am.year_one_debt_service_is_12_payments",
          close(float(schedule_year_one_debt_service(s)), float(s[0]["payment"]) * 12, tol=0.01))
    check("am.textbook_payment",
          close(float(amortizing_payment(400000, "6.5", 360)), 2528.27209397185, tol=1e-6))
    check("am.zero_interest_is_straight_line",
          close(float(amortizing_payment(360000, "0", 360)), 1000.0))
    check("am.interest_only_is_principal_times_rate",
          close(float(interest_only_payment(100000, "12")), 1000.0))
    check("am.zero_principal_pays_nothing",
          amortizing_payment(0, "7", 360) == ZERO and interest_only_payment(0, "7") == ZERO)
    check("am.zero_term_pays_nothing", amortizing_payment(250000, "7", 0) == ZERO)
    # 15y vs 30y: shorter term must cost more per month and less total interest
    p15 = amortizing_payment(250000, "6.5", 180)
    p30 = amortizing_payment(250000, "6.5", 360)
    s15 = amortization_schedule(250000, "6.5", 180, 180, 0)
    check("am.shorter_term_higher_payment", p15 > p30)
    check("am.shorter_term_less_total_interest",
          sum(r["interest"] for r in s15) < sum(r["interest"] for r in s))
    # stability at absurd rates: the negative-exponent form must not overflow
    extreme = amortizing_payment(1_000_000, "1000", 480)
    check("am.extreme_rate_is_finite_and_approaches_interest_only",
          math.isfinite(float(extreme)) and float(extreme) > 0)
    check("am.balance_lookup_before_start_and_past_end",
          balance_after_months(s, 0) == F(250000) and balance_after_months(s, 9999) == ZERO)


# ---------------------------------------------------------------------------
# 2. COMPARATIVE STATICS
# ---------------------------------------------------------------------------

def comparative_checks():
    def core(**over):
        return analyze(rental(**over))["core"]

    base = core()
    check("cmp.rate_up_cash_flow_down",
          core(interestRatePct=8.0)["monthlyCashFlow"] < base["monthlyCashFlow"])
    check("cmp.rate_up_dscr_down", core(interestRatePct=8.0)["dscr"] < base["dscr"])
    check("cmp.price_up_cap_rate_down",
          core(purchasePrice=450000)["capRateOnPricePct"] < base["capRateOnPricePct"])
    check("cmp.price_up_coc_down",
          core(purchasePrice=450000)["cashOnCashPct"] < base["cashOnCashPct"])
    check("cmp.rent_up_noi_up", core(monthlyRent=4200)["noiAnnual"] > base["noiAnnual"])
    check("cmp.rent_up_dscr_up", core(monthlyRent=4200)["dscr"] > base["dscr"])
    check("cmp.vacancy_up_noi_down",
          core(vacancyRatePct=12)["noiAnnual"] < base["noiAnnual"])
    check("cmp.more_down_payment_lower_loan",
          core(downPaymentPct=40)["loanAmount"] < base["loanAmount"])
    check("cmp.more_down_payment_higher_cash_flow",
          core(downPaymentPct=40)["monthlyCashFlow"] > base["monthlyCashFlow"])
    # Leverage is *negative* at 6.75% debt against a ~6.6% cap rate, so more
    # equity raises cash-on-cash; with cheap debt the opposite must hold.
    check("cmp.more_equity_raises_coc_when_leverage_is_negative",
          core(downPaymentPct=40)["cashOnCashPct"] > base["cashOnCashPct"])
    cheap = core(interestRatePct=3.0)
    check("cmp.more_equity_lowers_coc_when_leverage_is_positive",
          core(interestRatePct=3.0, downPaymentPct=40)["cashOnCashPct"] < cheap["cashOnCashPct"])
    check("cmp.interest_only_raises_cash_flow",
          core(financingModel="HARD_MONEY", amortizationMonths=0, interestOnlyMonths=12,
               loanTermMonths=12, downPaymentPct=None)["monthlyCashFlow"] >
          core(financingModel="HARD_MONEY", amortizationMonths=12, interestOnlyMonths=0,
               loanTermMonths=12, downPaymentPct=None)["monthlyCashFlow"])
    check("cmp.all_cash_has_no_debt_service",
          core(loanAmount=0, downPaymentAmount=None, downPaymentPct=None)["monthlyDebtService"] == 0)
    all_cash = core(loanAmount=0, downPaymentPct=None)
    no_debt = core(loanAmount=0, downPaymentPct=None, capexReservePctOfGsi=None)
    check("cmp.all_cash_dscr_is_none_not_infinity", all_cash["dscr"] is None)
    check("cmp.all_cash_cash_flow_equals_noi",
          close(all_cash["monthlyCashFlow"] * 12, all_cash["noiAnnual"]))
    check("cmp.finite_dscr_for_all_cash_like_cases", no_debt["dscr"] is None)
    check("cmp.growth_raises_exit_value",
          analyze(rental(appreciationPct=6))["hold"]["exitValue"] >
          analyze(rental(appreciationPct=1))["hold"]["exitValue"])
    check("cmp.negative_growth_lowers_exit_value",
          analyze(rental(appreciationPct=-3))["hold"]["exitValue"] < 400000)
    above = analyze(rental(capexTreatment="ABOVE_LINE_IN_NOI"))
    below = analyze(rental(capexTreatment="BELOW_LINE_RESERVE"))
    check("cmp.below_line_capex_raises_noi_by_capex_amount",
          close(below["core"]["noiAnnual"] - above["core"]["noiAnnual"],
                above["operating"]["capitalReservesAnnual"], tol=0.01))
    check("cmp.below_line_capex_is_excluded_from_opex",
          close(below["operating"]["totalOperatingExpensesAnnual"],
                above["operating"]["totalOperatingExpensesAnnual"]
                - above["operating"]["capitalReservesAnnual"], tol=0.01))


# ---------------------------------------------------------------------------
# 3. STRATEGY checks
# ---------------------------------------------------------------------------

def strategy_checks():
    r = analyze(flip())
    f = r["flip"]
    check("flip.contingency_applied", close(f["totalRehabCost"], 45000 * 1.10))
    check("flip.all_in_cost_composition",
          close(f["allInCost"], 250000 + f["totalRehabCost"] + f["buyClosingCosts"]
                + f["holdingCostsTotal"] + f["loanInterestPaid"] + f["loanFeesAndPoints"], tol=0.01))
    check("flip.profit_definition", close(f["profit"], f["netSaleProceeds"] - f["allInCost"], tol=0.01))
    check("flip.net_proceeds_definition",
          close(f["netSaleProceeds"], f["grossSalePrice"] - f["sellingCosts"], tol=0.01))
    check("flip.cash_invested_is_cost_minus_loan",
          close(f["returnMetrics"]["cashInvested"], f["allInCost"] - f["loanAmount"], tol=0.01))
    check("flip.roi_definition",
          close(f["returnMetrics"]["roiPct"], f["profit"] / f["returnMetrics"]["cashInvested"] * 100, tol=1e-6))
    check("flip.break_even_arv_gives_zero_profit",
          close(f["breakEvenArv"], (f["allInCost"]) / (1 - 0.08), tol=0.05))
    check("flip.mao_70_rule", close(f["maoSeventyRule"], 400000 * 0.70 - f["totalRehabCost"]))
    check("flip.max_offer_hits_target_profit", f["maxOfferForTargetProfit"] is not None
          and f["maxOfferForTargetProfit"] < 250000)
    check("flip.interest_only_interest_is_linear",
          close(f["loanInterestPaid"], f["loanAmount"] * 0.115 / 12 * 6, tol=0.01))
    check("flip.higher_arv_more_profit",
          analyze(flip(arv=440000))["flip"]["profit"] > f["profit"])
    check("flip.higher_rehab_less_profit",
          analyze(flip(rehabCost=65000))["flip"]["profit"] < f["profit"])
    check("flip.higher_sell_cost_less_profit",
          analyze(flip(sellClosingCostPct=10))["flip"]["profit"] < f["profit"])
    check("flip.more_expensive_money_less_profit",
          analyze(flip(interestRatePct=15))["flip"]["profit"] < f["profit"])
    check("flip.loser_is_not_rated_strong",
          analyze(flip(arv=320000))["verdict"]["rating"] in ("MARGINAL", "FAILS_CRITERIA"))
    check("flip.profitable_deal_passes_profit_criterion",
          "PROFITABLE" in analyze(flip(arv=460000))["verdict"]["passedCriteria"])

    r = analyze(wholesale())
    w = r["wholesale"]
    check("wholesale.profit_is_fee_minus_costs",
          close(w["profit"], w["assignmentFee"] - w["marketingCost"]))
    check("wholesale.cash_invested_is_emd_plus_marketing",
          close(r["returns"]["cashInvested"], 2000 + 500))
    check("wholesale.roi_definition",
          close(r["returns"]["roiPct"], w["profit"] / (2000 + 500) * 100))
    check("wholesale.fee_defaults_to_pct_of_arv",
          analyze(wholesale(assignmentFee=None))["wholesale"]["assignmentFee"] == 420000 * 0.02)
    check("wholesale.max_assignable_fee_for_buyer",
          close(w["maxAssignableFee"], 420000 * 0.92 - 0 - 300000 - 420000 * 0.10))
    check("wholesale.double_close_adds_both_sides_of_closing",
          analyze(wholesale(wholesaleMode="DOUBLE_CLOSE"))["wholesale"]["profit"]
          < w["profit"])
    check("wholesale.big_fee_flagged",
          any(i["code"] == "ASSIGNMENT_FEE_MAY_BE_REJECTED"
              for i in analyze(wholesale(assignmentFee=60000))["validation"]))
    check("wholesale.zero_emd_roi_still_defined",
          analyze(wholesale(earnestMoney=0, marketingCost=0))["returns"]["roiPct"] is None)

    r = analyze(brrrr())
    b = r["brrrr"]
    check("brrrr.bridge_is_ltc85_of_cost", close(b["bridgeLoanAmount"], (200000 + 45000) * 0.85))
    check("brrrr.phase1_cash_composition",
          close(b["phase1CashInvested"],
                (245000 - b["bridgeLoanAmount"]) + 3000 + 0 + b["bridgeLoanFeesAndPoints"]
                + b["bridgeInterestPaid"] + b["holdingCostsDuringRehab"], tol=0.01))
    check("brrrr.refi_loan_is_ltv_of_arv", close(b["refinance"]["loanAmount"], 340000 * 0.75))
    check("brrrr.cash_out_definition",
          close(b["cashOutAtRefinance"], b["refinance"]["loanAmount"] - b["bridgePayoffAmount"]
                - b["refinance"]["closingCostsAndPoints"], tol=0.01))
    check("brrrr.cash_left_in_deal_definition",
          close(b["cashLeftInDeal"], b["phase1CashInvested"] - b["cashOutAtRefinance"], tol=0.01))
    check("brrrr.capital_recovered_definition",
          close(b["capitalRecoveredPct"], b["cashOutAtRefinance"] / b["phase1CashInvested"] * 100, tol=1e-6))
    check("brrrr.post_refi_cash_flow_uses_new_debt",
          close(b["postRefiCore"]["annualCashFlow"],
                b["postRefiCore"]["noiAnnual"] - b["postRefiCore"]["annualDebtService"], tol=0.01))
    check("brrrr.post_refi_dscr_definition",
          close(b["postRefiCore"]["dscr"],
                b["postRefiCore"]["noiAnnual"] / b["postRefiCore"]["annualDebtService"], tol=1e-9))
    check("brrrr.ltv_of_arv_after_refi", close(b["refinance"]["ltvOfArvPct"], 75.0))
    check("brrrr.equity_created_definition", close(b["equityCreatedAtRefi"], 340000 - 255000))
    # a deep-value BRRRR recovers all of its capital
    deep = analyze(brrrr(purchasePrice=150000, arv=380000, monthlyRent=3400))
    check("brrrr.full_capital_recovery_flagged",
          deep["brrrr"]["infiniteCashOnCash"] is True
          and deep["brrrr"]["postRefiCashOnCashPct"] is None)
    check("brrrr.capital_recovery_passes_qualification",
          "CAPITAL_FULLY_RECOVERED" in deep["verdict"]["passedCriteria"])
    check("brrrr.refi_dscr_warning_present_when_thin",
          any(i["code"] == "REFI_DSCR_SHORTFALL" for i in r["validation"]))


# ---------------------------------------------------------------------------
# 4. ROBUSTNESS / EDGE CASES
# ---------------------------------------------------------------------------

def robustness_checks():
    cases = {
        "zero_everything": dict(purchasePrice=0, monthlyRent=0, rehabCost=0, closingCosts=0,
                                propertyTaxAnnual=0, insuranceAnnual=0),
        "vacant_property": dict(monthlyRent=0),
        "zero_vacancy": dict(vacancyRatePct=0),
        "full_vacancy": dict(vacancyRatePct=100),
        "vacancy_over_100": dict(vacancyRatePct=130),
        "negative_vacancy": dict(vacancyRatePct=-10),
        "zero_interest": dict(interestRatePct=0),
        "high_interest": dict(interestRatePct=25),
        "absurd_interest_40yr": dict(interestRatePct=300, loanTermMonths=480, amortizationMonths=480),
        "all_cash": dict(loanAmount=0),
        "huge_numbers": dict(purchasePrice=1e9, monthlyRent=1e7, propertyTaxAnnual=1e7,
                             insuranceAnnual=5e6, holdYears=10),
        "tiny_numbers": dict(purchasePrice=1, monthlyRent=1, closingCosts=0),
        "negative_rehab": dict(rehabCost=-5000),
        "zero_hold_years": dict(holdYears=0),
        "long_hold": dict(holdYears=30),
        "negative_growth": dict(rentGrowthPct=-5, expenseGrowthPct=6, appreciationPct=-4),
        "hundred_percent_down": dict(downPaymentPct=100),
        "over_financed": dict(loanAmount=500000),
        "conflicting_loan_and_down": dict(loanAmount=350000, downPaymentAmount=100000),
        "conflicting_loan_and_pct": dict(loanAmount=350000, downPaymentPct=30),
        "explicit_down_amount": dict(downPaymentAmount=120000, downPaymentPct=None),
        "zero_term": dict(loanTermMonths=0, amortizationMonths=0, interestOnlyMonths=0),
        "io_longer_than_term": dict(loanTermMonths=12, amortizationMonths=360, interestOnlyMonths=36),
        "commercial_sized_deal": dict(purchasePrice=5_000_000, monthlyRent=60_000,
                                      propertyTaxAnnual=60_000, insuranceAnnual=25_000,
                                      holdYears=7, downPaymentPct=30),
        "seller_financing_balloon": dict(financingModel="SELLER_FINANCING", holdYears=10),
        "private_money": dict(financingModel="PRIVATE_MONEY"),
        "dscr_loan": dict(financingModel="DSCR"),
        "no_expense_inputs": dict(propertyTaxAnnual=None, insuranceAnnual=None),
        "below_line_capex": dict(capexTreatment="BELOW_LINE_RESERVE"),
        "seller_credits": dict(sellerCredits=9000),
        "total_loss_deal": dict(monthlyRent=0, otherMonthlyIncome=0, interestRatePct=25,
                                vacancyRatePct=20, holdYears=5, appreciationPct=-10),
        "negative_equity_multiple": dict(monthlyRent=500, interestRatePct=25, holdYears=5,
                                         propertyTaxAnnual=20000, insuranceAnnual=9000),
    }
    for name, over in cases.items():
        try:
            r = analyze(rental(**over))
        except Exception as exc:  # noqa: BLE001 - the point is to catch everything
            check("rob.%s_runs" % name, False, "raised %r" % exc)
            continue
        check("rob.%s_runs" % name, True)
        check("rob.%s_finite" % name, deep_finite(r))
        check("rob.%s_no_complex_numbers" % name,
              not _has_complex(r), "complex value leaked into the result")
        check("rob.%s_has_verdict" % name, r["verdict"]["rating"] in
              ("STRONG", "ACCEPTABLE", "MARGINAL", "FAILS_CRITERIA"))
        check("rob.%s_has_applicability" % name, "metricApplicability" in r)
        check("rob.%s_validation_shaped" % name,
              all({"code", "severity", "message"} <= set(i) for i in r["validation"]))

    r = analyze(rental(vacancyRatePct=130))
    check("rob.vacancy_over_100_raises_error",
          any(i["code"] == "TOTAL_VACANCY_ABOVE_100" and i["severity"] == "ERROR" for i in r["validation"]))
    check("rob.vacancy_over_100_clamped_to_zero_egi",
          close(r["operating"]["effectiveGrossIncomeAnnual"], 0))

    r = analyze(rental(vacancyRatePct=-10))
    check("rob.negative_vacancy_raises_error",
          any(i["code"] == "NEGATIVE_VACANCY" and i["severity"] == "ERROR" for i in r["validation"]))
    check("rob.negative_vacancy_clamped_egi_never_exceeds_gsi",
          r["operating"]["effectiveGrossIncomeAnnual"] <= r["operating"]["grossScheduledIncomeAnnual"])

    r = analyze(rental(vacancyRatePct=100))
    check("rob.full_vacancy_zero_egi", close(r["operating"]["effectiveGrossIncomeAnnual"], 0))
    check("rob.full_vacancy_negative_noi", r["core"]["noiAnnual"] < 0)
    check("rob.full_vacancy_grm_still_defined_on_gsi",
          close(r["core"]["grossRentMultiplier"], 400000 / ((3600 + 150) * 12)))
    check("rob.full_vacancy_break_even_is_fixed_costs_over_gsi",
          close(r["core"]["breakEvenOccupancyPct"],
                (r["operating"]["totalOperatingExpensesAnnual"] + r["core"]["annualDebtService"])
                / r["operating"]["grossScheduledIncomeAnnual"] * 100))

    r = analyze(rental(monthlyRent=0, otherMonthlyIncome=0))
    check("rob.zero_rent_no_break_even", r["core"]["breakEvenOccupancyPct"] is None)
    check("rob.zero_rent_no_grm", r["core"]["grossRentMultiplier"] is None)
    check("rob.zero_rent_negative_cash_flow", r["core"]["monthlyCashFlow"] < 0)
    check("rob.zero_rent_negative_dscr_is_reported", r["core"]["dscr"] < 0)
    check("rob.zero_rent_negative_noi_flagged",
          any(i["code"] == "NEGATIVE_NOI" for i in r["validation"]))

    r = analyze(rental(purchasePrice=0, monthlyRent=0, rehabCost=0, closingCosts=0))
    check("rob.zero_price_raises_error",
          any(i["code"] == "NON_POSITIVE_PURCHASE_PRICE" for i in r["validation"]))
    check("rob.zero_price_no_divide_by_zero",
          r["core"]["capRateOnPricePct"] is None and r["core"]["ltvOfPricePct"] is None)

    r = analyze(rental(loanAmount=0))
    check("rob.all_cash_zero_debt", close(r["core"]["annualDebtService"], 0.0, tol=1e-9))
    check("rob.all_cash_dscr_none", r["core"]["dscr"] is None)
    check("rob.all_cash_no_ltv_ceiling_warning",
          not any(i["code"].startswith("LOAN_CAPPED") for i in r["validation"]))

    r = analyze(rental(interestRatePct=0))
    check("rob.zero_rate_payment_is_straight_line",
          close(r["core"]["monthlyDebtService"], 320000 / 360, tol=0.01))

    r = analyze(rental(loanAmount=500000, downPaymentPct=None, downPaymentAmount=None))
    check("rob.over_financed_flagged",
          any(i["code"] == "LOAN_CAPPED_BY_MAX_LTV_OF_PRICE" for i in r["validation"]))
    check("rob.over_financed_loan_capped", close(r["core"]["loanAmount"], 320000.0))

    r = analyze(rental(loanAmount=350000, downPaymentAmount=100000))
    check("rob.conflicting_loan_and_down_flagged",
          any(i["code"] == "LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT" for i in r["validation"]))
    check("rob.loan_amount_takes_precedence_over_down_payment",
          close(r["financing"]["requestedLoanAmount"], 350000.0))
    check("rob.explicit_loan_still_capped_by_lender_ceiling",
          close(r["core"]["loanAmount"], 320000.0)
          and any(i["code"] == "LOAN_CAPPED_BY_MAX_LTV_OF_PRICE" for i in r["validation"]))
    r = analyze(rental(loanAmount=200000, downPaymentAmount=100000, downPaymentPct=None))
    check("rob.loan_amount_below_ceiling_used_verbatim", close(r["core"]["loanAmount"], 200000.0))
    check("rob.implied_down_conflict_only_when_pct_explicit",
          sum(1 for i in r["validation"] if i["code"] == "LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT") == 1)
    r_both = analyze(rental(loanAmount=200000, downPaymentAmount=100000))
    check("rob.explicit_pct_conflict_also_reported",
          sum(1 for i in r_both["validation"] if i["code"] == "LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT") == 2)

    r = analyze(rental(loanAmount=350000, downPaymentPct=30))
    check("rob.conflicting_loan_and_pct_flagged",
          any(i["code"] == "LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT" for i in r["validation"]))

    loss = analyze(rental(monthlyRent=0, otherMonthlyIncome=0, interestRatePct=25, holdYears=5,
                          propertyTaxAnnual=20000, insuranceAnnual=9000))
    check("rob.total_loss_roi_is_negative", loss["returns"]["roiPct"] < 0)
    check("rob.total_loss_annualised_is_none_not_complex",
          loss["returns"]["annualizedRoiPct"] is None)
    check("rob.total_loss_has_linear_annualisation",
          loss["returns"]["simpleAnnualizedRoiPct"] is not None)

    r = analyze(rental(holdYears=0))
    check("rob.zero_hold_years_no_division",
          r["hold"] is not None and (r["returns"]["roiPct"] is None or math.isfinite(r["returns"]["roiPct"])))

    r = analyze(rental(loanTermMonths=12, amortizationMonths=360, interestOnlyMonths=36))
    check("rob.io_clamped_to_term",
          any(i["code"] == "IO_PERIOD_EXCEEDS_TERM" for i in r["validation"]))

    r = analyze(model_fixture(financingModel="SELLER_FINANCING", holdYears=10))
    check("rob.balloon_loan_has_maturity_shorter_than_hold",
          r["financing"]["loanTermMonths"] == 60 and r["financing"]["amortizationMonths"] == 360)
    check("rob.balloon_maturity_inside_hold_is_flagged",
          any(i["code"] == "REFINANCE_ASSUMED_AT_MATURITY" for i in r["validation"]))
    check("rob.balloon_balance_amortises_after_maturity",
          r["hold"]["years"][-1]["loanBalance"] < r["financing"]["loanAmount"])

    r = analyze(rental(rehabCost=-5000))
    check("rob.negative_rehab_flagged",
          any(i["code"] == "NEGATIVE_REHAB_COST" for i in r["validation"]))

    r = analyze(rental(strategy="NOT_A_STRATEGY"))
    check("rob.unknown_strategy_flagged",
          any(i["code"] == "UNKNOWN_STRATEGY" for i in r["validation"]))
    check("rob.unknown_strategy_falls_back_within_enum", r["strategy"] == "BUY_AND_HOLD")

    r = analyze(rental(financingModel="NOPE"))
    check("rob.unknown_model_flagged",
          any(i["code"] == "UNKNOWN_FINANCING_MODEL" for i in r["validation"]))
    check("rob.unknown_model_falls_back", r["financingModel"] == "CONVENTIONAL")

    for bad, code in ((flip(arv=0), "FLIP_REQUIRES_ARV"),
                      (brrrr(arv=0), "BRRRR_REQUIRES_ARV")):
        r = analyze(bad)
        check("rob.%s_flagged" % code.lower(),
              any(i["code"] == code for i in r["validation"]))
        check("rob.%s_returns_none" % code.lower(), r["returns"] is None)

    r = analyze(flip(flipHoldMonths=0))
    check("rob.zero_flip_months_defaulted",
          any(i["code"] == "FLIP_HOLD_MONTHS_DEFAULTED" for i in r["validation"]))

    r = analyze(wholesale(daysToClose=0))
    check("rob.zero_days_to_close_defaulted",
          any(i["code"] == "DAYS_TO_CLOSE_DEFAULTED" for i in r["validation"]))

    r = analyze(rental(capexTreatment="BELOW_LINE_RESERVE"))
    check("rob.below_line_capex_excludes_from_opex",
          close(r["operating"]["netOperatingIncomeAnnual"],
                r["operating"]["effectiveGrossIncomeAnnual"]
                - r["operating"]["totalOperatingExpensesAnnual"]))


# ---------------------------------------------------------------------------
# 5. DETERMINISM + no hidden inputs
# ---------------------------------------------------------------------------

def determinism_checks():
    scenarios = [rental(), flip(), brrrr(), wholesale(), rental(financingModel="DSCR"),
                 rental(financingModel="SELLER_FINANCING")]
    for i, scenario in enumerate(scenarios):
        a = json.dumps(analyze(dict(scenario)), sort_keys=True)
        b = json.dumps(analyze(dict(scenario)), sort_keys=True)
        check("det.scenario_%d_repeatable" % i, a == b)

    # key order / dict mutation must not matter
    shuffled = dict(reversed(list(rental().items())))
    check("det.input_order_irrelevant",
          json.dumps(analyze(rental()), sort_keys=True) == json.dumps(analyze(shuffled), sort_keys=True))

    # assumptions are reported for every resolved value
    r = analyze(rental())
    check("det.provenance_present", len(r["assumptionsUsed"]) > 0)
    check("det.provenance_has_sources",
          all(v.get("source") in ("EXPLICIT", "MODEL_DEFAULT", "DERIVED")
              for v in r["assumptionsUsed"].values()))
    check("det.explicit_override_is_marked_explicit",
          analyze(rental(interestRatePct=7.25))["assumptionsUsed"]["interestRatePct"]["source"] == "EXPLICIT")


# ---------------------------------------------------------------------------
# 6. FINANCING MODEL coverage
# ---------------------------------------------------------------------------

def model_fixture(**over):
    """Like rental() but with NO loan-term overrides, so financing-model
    defaults (IO periods, balloons, terms) are actually exercised."""
    base = rental()
    for key in ("downPaymentPct", "interestRatePct", "loanTermMonths",
                "amortizationMonths", "interestOnlyMonths"):
        base.pop(key, None)
    base.update(over)
    return base


def financing_checks():
    for model in FINANCING_MODELS:
        r = analyze(model_fixture(financingModel=model, holdYears=5))
        f, c = r["financing"], r["core"]
        check("fin.%s_runs" % model, True)
        check("fin.%s_loan_is_lo_minus_down" % model,
              close(f["loanAmount"] + f["downPaymentAmount"],
                    f["loanAmount"] + f["downPaymentAmount"], tol=1e-6))
        check("fin.%s_payment_matches_terms" % model,
              close(c["monthlyDebtService"],
                    float(amortizing_payment(f["loanAmount"], f["interestRatePct"],
                                             f["amortizationMonths"]))
                    if f["amortizationMonths"] > 0 and f["interestOnlyMonths"] < f["loanTermMonths"]
                    else float(interest_only_payment(f["loanAmount"], f["interestRatePct"])),
                    tol=1e-6))
        check("fin.%s_ltv_within_declared_ceiling" % model,
              f["maxLtvPct"] is None or c["ltvOfPricePct"] <= f["maxLtvPct"] + 1e-9)
        check("fin.%s_has_explicit_assumptions" % model,
              "downPaymentPct" in r["assumptionsUsed"] and "interestRatePct" in r["assumptionsUsed"])

    hard = analyze(model_fixture(financingModel="HARD_MONEY", rehabCost=40000))
    check("fin.hard_money_is_interest_only", hard["financing"]["isInterestOnly"] is True)
    check("fin.hard_money_ltc_is_85_of_cost",
          close(hard["financing"]["loanAmount"], (400000 + 40000) * 0.85, tol=0.01))
    check("fin.hard_money_finances_rehab",
          close(hard["capitalStack"]["cashRehab"], 0.0))
    hard_flip = analyze(model_fixture(strategy="FIX_AND_FLIP", financingModel="HARD_MONEY",
                                      arv=500000, flipHoldMonths=1))
    check("fin.hard_money_min_interest_months_are_explicit",
          hard_flip["flip"]["lenderMinimumInterestMonths"] == 3)
    check("fin.hard_money_charges_minimum_interest_even_for_1_month_hold",
          hard_flip["flip"]["loanInterestPaid"] > hard_flip["flip"]["loanAmount"] * 0.115 / 12 * 1.01)
    dscr = analyze(model_fixture(financingModel="DSCR"))
    check("fin.dscr_loan_has_reserves", dscr["financing"]["lenderReserveMonths"] == 6)
    check("fin.dscr_reserves_in_cash_stack",
          dscr["capitalStack"]["lenderReserves"] > 0)
    seller = analyze(model_fixture(financingModel="SELLER_FINANCING"))
    check("fin.seller_financing_has_balloon", seller["financing"]["loanTermMonths"] == 60
          and seller["financing"]["amortizationMonths"] == 360)
    check("fin.balloon_inside_hold_window_is_not_flagged",
          not any(i["code"] == "REFINANCE_ASSUMED_AT_MATURITY" for i in seller["validation"]))
    conv = analyze(model_fixture(financingModel="CONVENTIONAL"))
    check("fin.conventional_20_pct_down",
          close(conv["financing"]["downPaymentPctOfPrice"], 20.0))
    check("fin.ltc_models_have_no_price_ceiling",
          analyze(model_fixture(financingModel="PRIVATE_MONEY"))["financing"]["maxLtvPct"] is None)

    # same property, five capital structures, deterministic comparison
    costs = {m: analyze(model_fixture(financingModel=m, holdYears=10))["hold"]["totalProfit"]
             for m in FINANCING_MODELS}
    check("fin.model_comparison_is_deterministic",
          costs == {m: analyze(model_fixture(financingModel=m, holdYears=10))["hold"]["totalProfit"]
                    for m in FINANCING_MODELS})
    check("fin.seller_financing_more_profitable_than_hard_money",
          costs["SELLER_FINANCING"] > costs["HARD_MONEY"])
    check("fin.balloon_hold_gets_refi_warning",
          any(i["code"] == "REFINANCE_ASSUMED_AT_MATURITY"
              for i in analyze(model_fixture(financingModel="SELLER_FINANCING", holdYears=10))["validation"]))
    hm10 = analyze(model_fixture(financingModel="HARD_MONEY", holdYears=10))
    check("fin.hard_money_long_hold_assumes_refi_and_warns",
          any(i["code"] == "REFINANCE_ASSUMED_AT_MATURITY" for i in hm10["validation"]))
    check("fin.hard_money_long_hold_pays_debt_service_in_years_2_plus",
          hm10["hold"]["years"][1]["debtService"] > 0)


# ---------------------------------------------------------------------------
# runner
# ---------------------------------------------------------------------------

def main():
    identity_checks()
    amortization_identity_checks()
    comparative_checks()
    strategy_checks()
    robustness_checks()
    determinism_checks()
    financing_checks()

    print("=" * 78)
    print("UNDERWRITING ORACLE SELF-CHECK")
    print("=" * 78)
    print("PASSED: %d" % len(PASSED))
    if FAILED:
        print("FAILED: %d" % len(FAILED))
        for f in FAILED:
            print("   ✗", f)
        raise SystemExit(1)
    print("ALL CHECKS PASSED ✅")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
