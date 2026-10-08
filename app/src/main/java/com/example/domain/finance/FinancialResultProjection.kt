package com.example.domain.finance

import com.example.domain.finance.underwriting.InvestmentStrategy
import com.example.domain.finance.underwriting.UnderwritingInput
import com.example.domain.finance.underwriting.UnderwritingResult

/**
 * The single financial source of truth is [com.example.domain.finance.underwriting.UnderwritingEngine].
 *
 * This file exists because two older contracts still speak the legacy [FinancialResult] shape:
 *
 *  * the automation port `FinancialAnalysisGateway.runAnalysis`, and
 *  * [com.example.domain.qualification.QualificationEngine], whose checks read
 *    [FinancialResult] fields and whose DSCR check understands the all-cash sentinel.
 *
 * Rather than recompute anything with the legacy formulas, both contracts are served by a pure,
 * deterministic **projection** of the canonical [UnderwritingResult]: every number below is read
 * straight out of the engine's output. Nothing here performs arithmetic of its own beyond unit
 * conversions (annual -> monthly, months -> years) and the two documented legacy sentinels.
 *
 * Rules that keep the projection honest:
 *  * A metric the engine reports as undefined (`null`) is preserved as null inside the
 *    [UnderwritingResult]; the legacy shape has no nulls, so the two legacy sentinels are applied
 *    here and *only* here, each with a named constant and a comment saying why.
 *  * The [FinancialInput] snapshot mirrors the values the engine actually used (after its
 *    documented sanitisation), not whatever the caller typed, so a persisted row can never claim
 *    an input the run ignored.
 */
object FinancialResultProjection {

    /**
     * Legacy all-cash DSCR sentinel. The canonical engine reports `dscr = null` for a deal with
     * no debt service (dividing by zero is not a return); the legacy contracts expect a large
     * finite sentinel instead. 999.0 is the value the old `FinancialEngine` always produced and
     * is above the scoring engine's `allCashDscrSentinel` window (>= 900) and the qualification
     * engine's "All-Cash (No Debt)" display threshold (> 50).
     */
    const val LEGACY_ALL_CASH_DSCR_SENTINEL: Double = 999.0

    /**
     * Projects the canonical result of underwriting [requested] into the legacy shape.
     * Deterministic: the same input/result pair always yields the same projection.
     */
    fun fromUnderwriting(requested: UnderwritingInput, result: UnderwritingResult): FinancialResult {
        val operating = result.operating
        val core = result.core
        val financing = result.financing

        val grossScheduledIncomeAnnual = operating.grossScheduledIncomeAnnual
        val effectiveGrossIncomeAnnual = operating.effectiveGrossIncomeAnnual
        val propertyTaxAnnual = annualAmount(operating.expenseLines, "propertyTax")
        val insuranceAnnual = annualAmount(operating.expenseLines, "insurance")
        val maintenanceAnnual = annualAmount(operating.expenseLines, "maintenanceRepairs")
        val managementAnnual = annualAmount(operating.expenseLines, "propertyManagement")

        val snapshot = FinancialInput(
            purchasePrice = result.purchasePrice,
            closingCosts = core.closingCosts,
            renovationCost = result.rehabCost,
            monthlyRent = effectiveMonthlyRent(requested),
            otherMonthlyIncome = finiteOrZero(requested.otherMonthlyIncome),
            vacancyRatePct = operating.vacancyRatePct,
            propertyTaxAnnual = propertyTaxAnnual,
            insuranceAnnual = insuranceAnnual,
            maintenancePct = percentOf(maintenanceAnnual, grossScheduledIncomeAnnual),
            managementPct = percentOf(managementAnnual, effectiveGrossIncomeAnnual),
            utilitiesMonthly = finiteOrZero(requested.utilitiesMonthly),
            // Legacy shape has a single down-payment percentage; the engine states its basis on
            // `financing.downPaymentBasis`. The share of price is what the old screens displayed.
            downPaymentPct = financing.downPaymentPctOfPrice ?: 0.0,
            interestRatePct = financing.interestRatePct,
            loanTermYears = financing.loanTermMonths / MONTHS_PER_YEAR
        )

        return FinancialResult(
            input = snapshot,
            grossRentalIncome = grossScheduledIncomeAnnual / MONTHS_PER_YEAR,
            effectiveRentalIncome = effectiveGrossIncomeAnnual / MONTHS_PER_YEAR,
            operatingExpensesMonthly = operating.totalOperatingExpensesAnnual / MONTHS_PER_YEAR,
            noiAnnual = core.noiAnnual,
            loanAmount = core.loanAmount,
            monthlyDebtService = core.monthlyDebtService,
            monthlyCashFlow = core.monthlyCashFlow,
            annualCashFlow = core.annualCashFlow,
            // Undefined cap rate (no positive price) is already a validation ERROR inside the
            // result; the legacy shape cannot carry null, so it degrades to the neutral 0.0.
            capRate = core.capRateOnPricePct ?: 0.0,
            totalCashRequired = core.totalCashRequired,
            // Undefined cash-on-cash (no cash left at risk) degrades to 0.0 in the legacy shape.
            cashOnCashReturn = core.cashOnCashPct ?: 0.0,
            dscr = core.dscr ?: LEGACY_ALL_CASH_DSCR_SENTINEL,
            breakEvenOccupancyPct = core.breakEvenOccupancyPct ?: 0.0
        )
    }

    private fun annualAmount(lines: List<com.example.domain.finance.underwriting.ExpenseLineResult>, key: String): Double =
        lines.firstOrNull { it.key == key }?.annualAmount ?: 0.0

    private fun percentOf(annualAmount: Double, baseAnnualAmount: Double): Double =
        if (baseAnnualAmount > 0.0) annualAmount / baseAnnualAmount * 100.0 else 0.0

    /**
     * The monthly rent the engine actually underwrote: the documented sanitisation drops
     * non-finite values and the analyser floors a negative rent at zero (reporting NEGATIVE_RENT).
     */
    private fun effectiveMonthlyRent(requested: UnderwritingInput): Double {
        val rent = requested.monthlyRent
        return if (rent.isFinite()) rent.coerceAtLeast(0.0) else 0.0
    }

    private fun finiteOrZero(value: Double?): Double = value?.takeIf { it.isFinite() } ?: 0.0

    private const val MONTHS_PER_YEAR = 12
}

/**
 * Exact translation of a legacy [FinancialInput] into the canonical [UnderwritingInput].
 *
 * Every legacy field is a caller-supplied number, so every canonical field it feeds is EXPLICIT;
 * nothing is defaulted here. Conventions are mapped, not approximated:
 *
 *  * `renovationCost` -> `rehabCost`;
 *  * `maintenancePct` is the legacy share of gross scheduled income (`maintenancePctOfGsi`);
 *  * `managementPct` is the legacy share of effective gross income (`managementPctOfEgi`);
 *  * `loanTermYears` becomes months on both the term and the amortisation schedule; a zero term
 *    (the legacy "all-cash" scenario) stays a zero-term loan, which the engine resolves to no
 *    debt service and `dscr = null`.
 *
 * The strategy is [InvestmentStrategy.BUY_AND_HOLD] because the legacy shape only ever described
 * rental deals; callers that need another strategy construct an [UnderwritingInput] directly.
 */
fun FinancialInput.toUnderwritingInput(): UnderwritingInput {
    val termMonths = if (loanTermYears > 0) loanTermYears * 12 else 0
    return UnderwritingInput(
        strategy = InvestmentStrategy.BUY_AND_HOLD,
        purchasePrice = purchasePrice,
        closingCosts = closingCosts,
        rehabCost = renovationCost,
        monthlyRent = monthlyRent,
        otherMonthlyIncome = otherMonthlyIncome,
        vacancyRatePct = vacancyRatePct,
        propertyTaxAnnual = propertyTaxAnnual,
        insuranceAnnual = insuranceAnnual,
        maintenancePctOfGsi = maintenancePct,
        managementPctOfEgi = managementPct,
        utilitiesMonthly = utilitiesMonthly,
        downPaymentPct = downPaymentPct,
        interestRatePct = interestRatePct,
        loanTermMonths = termMonths,
        amortizationMonths = termMonths
    )
}
