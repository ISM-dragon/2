package com.example.domain.finance

import kotlin.math.pow

data class FinancialInput(
    val purchasePrice: Double,
    val closingCosts: Double = 0.0,
    val renovationCost: Double = 0.0,
    val monthlyRent: Double,
    val otherMonthlyIncome: Double = 0.0,
    val vacancyRatePct: Double = 5.0,
    val propertyTaxAnnual: Double = 0.0,
    val insuranceAnnual: Double = 0.0,
    val maintenancePct: Double = 5.0,
    val managementPct: Double = 8.0,
    val utilitiesMonthly: Double = 0.0,
    val downPaymentPct: Double = 20.0,
    val interestRatePct: Double = 6.85,
    val loanTermYears: Int = 30
)

data class FinancialResult(
    val input: FinancialInput,
    val grossRentalIncome: Double,
    val effectiveRentalIncome: Double,
    val operatingExpensesMonthly: Double,
    val noiAnnual: Double,
    val loanAmount: Double,
    val monthlyDebtService: Double,
    val monthlyCashFlow: Double,
    val annualCashFlow: Double,
    val capRate: Double,
    val totalCashRequired: Double,
    val cashOnCashReturn: Double,
    val dscr: Double,
    val breakEvenOccupancyPct: Double
)

/**
 * LEGACY calculator, frozen for the standalone Analyzer screen only.
 *
 * [com.example.domain.finance.underwriting.UnderwritingEngine] is the single financial source
 * of truth: every persisted, automated or qualified underwriting goes through it and is
 * verified against the Python oracle's golden vectors. Nothing new should call this object;
 * the [FinancialInput]/[FinancialResult] types remain because older screens and the
 * qualification contract still speak them (see `FinancialResultProjection`).
 */
@Deprecated(
    message = "UnderwritingEngine is the single financial source of truth; this legacy " +
        "calculator survives only for the standalone Analyzer screen. Persisted and automated " +
        "underwriting must go through UnderwritingEngine (via FinancialRepository).",
    replaceWith = ReplaceWith(
        "UnderwritingEngine",
        "com.example.domain.finance.underwriting.UnderwritingEngine"
    )
)
object FinancialEngine {

    fun calculate(input: FinancialInput): FinancialResult {
        val grossRent = input.monthlyRent + input.otherMonthlyIncome
        val vacancyLoss = grossRent * (input.vacancyRatePct / 100.0)
        val effectiveRent = (grossRent - vacancyLoss).coerceAtLeast(0.0)

        val taxMonthly = input.propertyTaxAnnual / 12.0
        val insMonthly = input.insuranceAnnual / 12.0
        val maintMonthly = input.monthlyRent * (input.maintenancePct / 100.0)
        val mgmtMonthly = grossRent * (input.managementPct / 100.0)
        val operatingExpensesMonthly = taxMonthly + insMonthly + maintMonthly + mgmtMonthly + input.utilitiesMonthly

        val noiMonthly = effectiveRent - operatingExpensesMonthly
        val noiAnnual = noiMonthly * 12.0

        val downPaymentAmount = input.purchasePrice * (input.downPaymentPct / 100.0)
        val loanAmount = (input.purchasePrice - downPaymentAmount).coerceAtLeast(0.0)

        val monthlyDebtService = calculateMonthlyPayment(
            loanAmount = loanAmount,
            annualInterestRatePct = input.interestRatePct,
            loanTermYears = input.loanTermYears
        )

        val monthlyCashFlow = effectiveRent - operatingExpensesMonthly - monthlyDebtService
        val annualCashFlow = monthlyCashFlow * 12.0

        val totalAcquisitionCost = input.purchasePrice + input.closingCosts + input.renovationCost
        val capRate = if (totalAcquisitionCost > 0.0) {
            (noiAnnual / totalAcquisitionCost) * 100.0
        } else 0.0

        val totalCashRequired = downPaymentAmount + input.closingCosts + input.renovationCost
        val cashOnCashReturn = if (totalCashRequired > 0.0) {
            (annualCashFlow / totalCashRequired) * 100.0
        } else 0.0

        val dscr = if (monthlyDebtService > 0.0) {
            noiMonthly / monthlyDebtService
        } else {
            999.0 // Infinite DSCR for all-cash
        }

        val totalMonthlyFixedObligations = operatingExpensesMonthly + monthlyDebtService
        val breakEvenOccupancyPct = if (grossRent > 0.0) {
            ((totalMonthlyFixedObligations / grossRent) * 100.0).coerceIn(0.0, 100.0)
        } else 0.0

        return FinancialResult(
            input = input,
            grossRentalIncome = grossRent,
            effectiveRentalIncome = effectiveRent,
            operatingExpensesMonthly = operatingExpensesMonthly,
            noiAnnual = noiAnnual,
            loanAmount = loanAmount,
            monthlyDebtService = monthlyDebtService,
            monthlyCashFlow = monthlyCashFlow,
            annualCashFlow = annualCashFlow,
            capRate = capRate,
            totalCashRequired = totalCashRequired,
            cashOnCashReturn = cashOnCashReturn,
            dscr = dscr,
            breakEvenOccupancyPct = breakEvenOccupancyPct
        )
    }

    fun calculateMonthlyPayment(
        loanAmount: Double,
        annualInterestRatePct: Double,
        loanTermYears: Int
    ): Double {
        if (loanAmount <= 0.0 || loanTermYears <= 0) return 0.0
        if (annualInterestRatePct <= 0.0) {
            return loanAmount / (loanTermYears * 12.0)
        }
        val monthlyRate = (annualInterestRatePct / 100.0) / 12.0
        val totalMonths = (loanTermYears * 12).toDouble()
        val factor = (1.0 + monthlyRate).pow(totalMonths)
        return if (factor - 1.0 != 0.0) {
            loanAmount * (monthlyRate * factor) / (factor - 1.0)
        } else {
            0.0
        }
    }

    fun generateComparisonScenarios(baseInput: FinancialInput): List<Pair<String, FinancialResult>> {
        val conventional = baseInput.copy(downPaymentPct = 20.0, interestRatePct = 6.85, loanTermYears = 30)
        val dscrLoan = baseInput.copy(downPaymentPct = 15.0, interestRatePct = 7.50, loanTermYears = 30)
        val aggressive = baseInput.copy(downPaymentPct = 10.0, interestRatePct = 8.25, loanTermYears = 30)
        val allCash = baseInput.copy(downPaymentPct = 100.0, interestRatePct = 0.0, loanTermYears = 0)

        return listOf(
            "Conventional 20%" to calculate(conventional),
            "DSCR Investor 15%" to calculate(dscrLoan),
            "Leveraged 10%" to calculate(aggressive),
            "All-Cash 100%" to calculate(allCash)
        )
    }
}
