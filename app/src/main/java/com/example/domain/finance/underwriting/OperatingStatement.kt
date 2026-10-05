package com.example.domain.finance.underwriting

import kotlin.math.max
import kotlin.math.min

/**
 * The income statement, built the way a US lender or appraiser would build it:
 *
 *   Gross Scheduled Income  (rent + other income, annualised)
 *     - vacancy & credit loss
 *   = Effective Gross Income
 *     - operating expenses (taxes, insurance, HOA, maintenance, management,
 *       utilities, landscaping, other, and - by default - replacement reserves)
 *   = Net Operating Income
 *     - debt service actually due in the period
 *   = Cash Flow
 *
 * Rules that matter:
 *  * NOI never includes debt service. That is what makes cap rate and DSCR
 *    comparable across capital structures.
 *  * Replacement reserves sit inside NOI by default (agency convention). A user
 *    who wants the other convention sets [CapexTreatment.BELOW_LINE_RESERVE],
 *    and the result says which convention was used.
 *  * Every expense line reports the [ExpenseBasis] that produced it, so a
 *    five-dollar difference in NOI can always be traced to its source.
 *  * Debt service for the period is the sum of payments *actually due*, which
 *    matters when a bridge loan balloons inside the first twelve months.
 */
object OperatingStatementCalculator {

    fun build(
        input: UnderwritingInput,
        purchasePrice: Double,
        rehabCost: Double,
        closingCosts: Double,
        terms: FinancingTerms,
        schedule: List<AmortizationRow>
    ): OperatingStatementBuild {
        val issues = ArrayList<ValidationIssue>()
        val lines = ArrayList<ExpenseLineResult>()

        val monthlyRent = input.monthlyRent
        val otherMonthly = input.otherMonthlyIncome
        val grossScheduledIncome = (monthlyRent + otherMonthly) * 12.0

        var vacancyPct = input.vacancyRatePct ?: UnderwritingAssumptions.VACANCY_RATE_PCT
        var creditPct = input.creditLossRatePct ?: UnderwritingAssumptions.CREDIT_LOSS_RATE_PCT
        if (vacancyPct < 0.0 || creditPct < 0.0) {
            // one finding for both fields: they are a single economic concept
            issues.add(
                ValidationIssue(
                    "NEGATIVE_VACANCY", IssueSeverity.ERROR,
                    "Vacancy and credit loss cannot be negative; both were floored at 0%."
                )
            )
            vacancyPct = max(vacancyPct, 0.0)
            creditPct = max(creditPct, 0.0)
        }
        if (vacancyPct + creditPct > 100.0) {
            issues.add(
                ValidationIssue(
                    "TOTAL_VACANCY_ABOVE_100", IssueSeverity.ERROR,
                    "Vacancy plus credit loss exceeds 100% and was clamped to 100%."
                )
            )
            vacancyPct = max(0.0, 100.0 - creditPct)
            creditPct = min(creditPct, 100.0)
        }
        val vacancyLoss = grossScheduledIncome * (vacancyPct + creditPct) / 100.0
        val effectiveGrossIncome = grossScheduledIncome - vacancyLoss

        // ---- expense lines ------------------------------------------------
        val propertyTax = resolveLine(
            explicit = input.propertyTaxAnnual,
            basisOverride = null,
            percent = input.propertyTaxPctOfPrice ?: UnderwritingAssumptions.PROPERTY_TAX_PCT_OF_PRICE,
            defaultBasis = ExpenseBasis.PERCENT_OF_PRICE,
            gsi = grossScheduledIncome, egi = effectiveGrossIncome, price = purchasePrice
        )
        lines.add(ExpenseLineResult("propertyTax", propertyTax.amount, propertyTax.basis))

        val insurance = resolveLine(
            explicit = input.insuranceAnnual,
            basisOverride = null,
            percent = input.insurancePctOfPrice ?: UnderwritingAssumptions.INSURANCE_PCT_OF_PRICE,
            defaultBasis = ExpenseBasis.PERCENT_OF_PRICE,
            gsi = grossScheduledIncome, egi = effectiveGrossIncome, price = purchasePrice
        )
        lines.add(ExpenseLineResult("insurance", insurance.amount, insurance.basis))

        val hoa = fixedMonthlyLine(input.hoaMonthly)
        lines.add(ExpenseLineResult("hoa", hoa.amount, hoa.basis))

        val maintenance = resolveLine(
            explicit = input.maintenanceAnnual,
            basisOverride = input.maintenanceBasis,
            percent = input.maintenancePctOfGsi ?: UnderwritingAssumptions.MAINTENANCE_PCT_OF_GSI,
            defaultBasis = ExpenseBasis.PERCENT_OF_GSI,
            gsi = grossScheduledIncome, egi = effectiveGrossIncome, price = purchasePrice
        )
        lines.add(ExpenseLineResult("maintenanceRepairs", maintenance.amount, maintenance.basis))

        val management = resolveLine(
            explicit = input.managementAnnual,
            basisOverride = input.managementBasis,
            percent = input.managementPctOfEgi ?: UnderwritingAssumptions.MANAGEMENT_PCT_OF_EGI,
            defaultBasis = ExpenseBasis.PERCENT_OF_EGI,
            gsi = grossScheduledIncome, egi = effectiveGrossIncome, price = purchasePrice
        )
        lines.add(ExpenseLineResult("propertyManagement", management.amount, management.basis))

        val utilities = fixedMonthlyLine(input.utilitiesMonthly)
        lines.add(ExpenseLineResult("utilities", utilities.amount, utilities.basis))

        val landscaping = fixedMonthlyLine(input.landscapingMonthly)
        lines.add(ExpenseLineResult("landscapingPest", landscaping.amount, landscaping.basis))

        val other = input.otherOperatingAnnual ?: 0.0
        lines.add(
            ExpenseLineResult(
                "otherOperating",
                other,
                if (other > 0.0) ExpenseBasis.FIXED_ANNUAL else ExpenseBasis.NONE
            )
        )

        val capexPct = input.capexReservePctOfGsi ?: UnderwritingAssumptions.CAPEX_RESERVE_PCT_OF_GSI
        val capitalReserves = grossScheduledIncome * capexPct / 100.0
        val capexTreatment = input.capexTreatment ?: CapexTreatment.ABOVE_LINE_IN_NOI
        if (capexTreatment == CapexTreatment.ABOVE_LINE_IN_NOI) {
            lines.add(ExpenseLineResult("capitalReserves", capitalReserves, ExpenseBasis.PERCENT_OF_GSI))
        }

        var totalOperatingExpenses = 0.0
        for (line in lines) totalOperatingExpenses += line.annualAmount

        val noi = effectiveGrossIncome - totalOperatingExpenses

        // ---- debt service --------------------------------------------------
        val annualDebtService = Amortization.yearOneDebtService(schedule)
        val annualCashFlow = noi - annualDebtService
        val monthlyCashFlow = annualCashFlow / 12.0

        val dscr: Double? = if (annualDebtService > 0.0) noi / annualDebtService else null
        if (dscr != null && noi < 0.0) {
            issues.add(
                ValidationIssue(
                    "NEGATIVE_NOI", IssueSeverity.WARNING,
                    "Net operating income is negative, so DSCR (${format(dscr)}) means nothing: " +
                        "the property cannot cover debt service from operations."
                )
            )
        }

        val capOnPrice = if (purchasePrice > 0.0) noi / purchasePrice * 100.0 else null
        val allInCost = purchasePrice + closingCosts + rehabCost
        val capOnCost = if (allInCost > 0.0) noi / allInCost * 100.0 else null
        val grm = if (grossScheduledIncome > 0.0) purchasePrice / grossScheduledIncome else null
        val breakEven =
            if (grossScheduledIncome > 0.0) (totalOperatingExpenses + annualDebtService) / grossScheduledIncome * 100.0
            else null
        val expenseRatio = if (effectiveGrossIncome > 0.0) totalOperatingExpenses / effectiveGrossIncome * 100.0 else null

        val statement = OperatingStatementResult(
            grossScheduledIncomeAnnual = grossScheduledIncome,
            vacancyAndCreditLossAnnual = vacancyLoss,
            effectiveGrossIncomeAnnual = effectiveGrossIncome,
            expenseLines = lines,
            capitalReservesAnnual = capitalReserves,
            capexTreatment = capexTreatment,
            totalOperatingExpensesAnnual = totalOperatingExpenses,
            netOperatingIncomeAnnual = noi,
            annualDebtService = annualDebtService,
            monthlyDebtService = terms.monthlyPayment,
            monthlyCashFlow = monthlyCashFlow,
            annualCashFlow = annualCashFlow,
            dscr = dscr,
            capRateOnPricePct = capOnPrice,
            capRateOnCostPct = capOnCost,
            grossRentMultiplier = grm,
            breakEvenOccupancyPct = breakEven,
            operatingExpenseRatioPct = expenseRatio,
            vacancyRatePct = vacancyPct,
            creditLossRatePct = creditPct
        )
        return OperatingStatementBuild(statement, issues)
    }

    /**
     * One expense line: an explicit dollar figure always wins; otherwise the
     * named basis (percent of price / GSI / EGI, or a fixed monthly figure whose
     * value may itself be zero and still be a real, reported line).
     */
    private fun resolveLine(
        explicit: Double?,
        basisOverride: ExpenseBasis?,
        percent: Double?,
        defaultBasis: ExpenseBasis,
        gsi: Double,
        egi: Double,
        price: Double
    ): ResolvedExpenseLine {
        if (explicit != null) return ResolvedExpenseLine(explicit, ExpenseBasis.FIXED_ANNUAL)

        val basis = basisOverride ?: defaultBasis
        val pct = percent ?: 0.0
        val amount = when (basis) {
            ExpenseBasis.NONE -> 0.0
            ExpenseBasis.FIXED_ANNUAL -> pct
            ExpenseBasis.FIXED_MONTHLY -> pct * 12.0
            ExpenseBasis.PERCENT_OF_PRICE -> price * pct / 100.0
            ExpenseBasis.PERCENT_OF_GSI -> gsi * pct / 100.0
            ExpenseBasis.PERCENT_OF_EGI -> egi * pct / 100.0
        }
        return ResolvedExpenseLine(amount, if (basis == ExpenseBasis.NONE) ExpenseBasis.NONE else basis)
    }

    /** A monthly line the user did not supply is absent, not zero-percent. */
    private fun fixedMonthlyLine(monthly: Double?): ResolvedExpenseLine =
        if (monthly != null) ResolvedExpenseLine(monthly * 12.0, ExpenseBasis.FIXED_MONTHLY)
        else ResolvedExpenseLine(0.0, ExpenseBasis.NONE)

    private fun format(value: Double): String {
        val rounded = Math.round(value * 10000.0).toDouble() / 10000.0
        return rounded.toString()
    }

    internal data class ResolvedExpenseLine(val amount: Double, val basis: ExpenseBasis)
}

internal data class OperatingStatementBuild(
    val statement: OperatingStatementResult,
    val issues: List<ValidationIssue>
)
