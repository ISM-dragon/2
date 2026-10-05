package com.example.domain.finance.underwriting

import kotlin.math.pow

/**
 * One month of a loan's life. Amounts are always positive cash out the door for
 * the borrower; a negative `principal` only appears in pathological inputs that
 * the validators refuse before we get here.
 */
data class AmortizationRow(
    val month: Int,
    val openingBalance: Double,
    val payment: Double,
    val interest: Double,
    val principal: Double,
    val closingBalance: Double,
    /** Final contractual month: the balloon comes due and retires the loan. */
    val isBalloonMonth: Boolean = false,
    /** Row sits past the loan's maturity because the projection assumes a refinance. */
    val assumesRefinance: Boolean = false
)

/**
 * Closed-form and schedule-based loan maths.
 *
 * The monthly payment uses
 *
 *     M = P * r / (1 - (1 + r)^-n)
 *
 * which is algebraically identical to the textbook `P * r * (1+r)^n / ((1+r)^n - 1)`
 * but is numerically stable: for very long terms or very high rates the
 * positive-power form overflows to `Infinity` and then divides Infinity by
 * Infinity (NaN), while the negative-power form decays cleanly to zero and the
 * payment converges on interest-only.
 *
 * Everything here is deterministic and side-effect free.
 */
object Amortization {

    private const val MONTHS_PER_YEAR = 12.0
    private const val PERCENT = 100.0

    /** Monthly periodic rate as a fraction (6.75% -> 0.005625). */
    fun periodicRate(annualRatePct: Double): Double = annualRatePct / PERCENT / MONTHS_PER_YEAR

    /**
     * Fully amortising monthly payment (principal + interest).
     * Returns 0 for a non-positive principal or term.
     */
    fun amortizingPayment(principal: Double, annualRatePct: Double, termMonths: Int): Double {
        if (principal <= 0.0 || termMonths <= 0) return 0.0
        val rate = periodicRate(annualRatePct)
        if (rate <= 0.0) return principal / termMonths
        // negative exponent: (1 + r)^-n -> 0 as n grows, so this cannot overflow
        val discount = (1.0 + rate).pow(-termMonths)
        val denominator = 1.0 - discount
        if (denominator == 0.0) return 0.0
        return principal * rate / denominator
    }

    /** Interest-only monthly payment. */
    fun interestOnlyPayment(principal: Double, annualRatePct: Double): Double {
        if (principal <= 0.0) return 0.0
        return principal * periodicRate(annualRatePct)
    }

    /**
     * Month-by-month schedule.
     *
     * @param termMonths           maturity / balloon length
     * @param amortizationMonths   amortisation basis; <= 0 means interest only
     * @param interestOnlyMonths   interest-only months at the front of the loan
     * @param maxMonths            generate past maturity when the caller wants a
     *                             projection longer than the loan (the loan is
     *                             then assumed refinanced on the same terms)
     *
     * A loan is treated as amortising only when it has an amortisation basis
     * that outlives its interest-only period; otherwise every payment in the
     * contract window is interest only. That mirrors [FinancingTerms.isInterestOnly]
     * exactly, so a schedule can never disagree with the quoted payment.
     */
    fun schedule(
        principal: Double,
        annualRatePct: Double,
        termMonths: Int,
        amortizationMonths: Int,
        interestOnlyMonths: Int,
        maxMonths: Int? = null
    ): List<AmortizationRow> {
        if (principal <= 0.0) return emptyList()
        val ioMonths = interestOnlyMonths.coerceAtLeast(0)
        val maturity = termMonths.coerceAtLeast(0)
        val horizon = (maxMonths ?: maturity).coerceAtLeast(0)
        if (horizon <= 0) return emptyList()

        val rate = periodicRate(annualRatePct)
        val amortizing = amortizationMonths > 0 && ioMonths < maturity
        val amortizingInstallment =
            if (amortizing) amortizingPayment(principal, annualRatePct, amortizationMonths) else 0.0
        val ioPayment = interestOnlyPayment(principal, annualRatePct)
        val stopsAtMaturity = horizon <= maturity

        val rows = ArrayList<AmortizationRow>(horizon)
        var balance = principal

        for (month in 1..horizon) {
            val interest = balance * rate
            var cashPayment: Double
            var principalPaid: Double

            if (!amortizing || month <= ioMonths) {
                principalPaid = 0.0
                cashPayment = ioPayment
            } else {
                cashPayment = amortizingInstallment
                principalPaid = cashPayment - interest
                if (principalPaid > balance) {
                    principalPaid = balance
                    cashPayment = principalPaid + interest
                }
                if (principalPaid < 0.0 && principalPaid + balance < 0.0) {
                    principalPaid = -balance
                    cashPayment = principalPaid + interest
                }
            }

            // Contractual balloon: the schedule stops at maturity and money is
            // still owed, so the final payment retires the loan.
            val isBalloon = stopsAtMaturity && month == maturity && month == horizon &&
                amortizing && balance - principalPaid > 0.0
            if (isBalloon) {
                principalPaid = balance
                cashPayment = principalPaid + interest
            }

            var balanceAfter = balance - principalPaid
            if (balanceAfter < 0.0 && balanceAfter > -1e-9) balanceAfter = 0.0

            rows.add(
                AmortizationRow(
                    month = month,
                    openingBalance = balance,
                    payment = cashPayment,
                    interest = interest,
                    principal = principalPaid,
                    closingBalance = balanceAfter,
                    isBalloonMonth = isBalloon,
                    assumesRefinance = month > maturity
                )
            )

            balance = balanceAfter
            if (balance <= 0.0 && month >= ioMonths) {
                // loan retired early: the remaining months carry no debt service
                for (extra in (month + 1)..horizon) {
                    rows.add(
                        AmortizationRow(
                            month = extra,
                            openingBalance = 0.0,
                            payment = 0.0,
                            interest = 0.0,
                            principal = 0.0,
                            closingBalance = 0.0,
                            isBalloonMonth = false,
                            assumesRefinance = extra > maturity
                        )
                    )
                }
                break
            }
        }
        return rows
    }

    /**
     * Cash actually paid to the lender in months 1..12.
     *
     * A loan that balloons in month 12 only has twelve payments: you cannot pay
     * debt service on a loan you have already repaid. Using the scheduled
     * payment times 12 for short-term bridge debt overstates year-one debt.
     */
    fun yearOneDebtService(rows: List<AmortizationRow>): Double {
        var total = 0.0
        val limit = minOf(12, rows.size)
        for (index in 0 until limit) total += rows[index].payment
        return total
    }

    fun totalInterest(rows: List<AmortizationRow>): Double {
        var total = 0.0
        for (row in rows) total += row.interest
        return total
    }

    /** Outstanding balance after `months` payments (0 before the loan starts). */
    fun balanceAfter(rows: List<AmortizationRow>, months: Int): Double {
        if (rows.isEmpty()) return 0.0
        if (months <= 0) return rows[0].openingBalance
        if (months >= rows.size) return rows[rows.size - 1].closingBalance
        return rows[months - 1].closingBalance
    }

    /**
     * Schedule extended to the projection horizon, plus a flag saying whether a
     * refinance had to be assumed.
     *
     * Quietly letting debt vanish when a loan matures inside the projection is
     * the most common way a spreadsheet lies about a deal. The documented
     * assumption is:
     *
     *  * interest-only bridge debt (hard / private money) refinances at
     *    maturity into a fully amortising 30-year loan at the same rate;
     *  * already-amortising debt keeps amortising on its original schedule.
     *
     * Callers surface this as a `REFINANCE_ASSUMED_AT_MATURITY` warning so the
     * number is never presented as if the original loan ran the full term.
     */
    fun scheduleToHorizon(terms: FinancingTerms, months: Int): ScheduleToHorizon {
        if (months <= 0 || terms.loanAmount <= 0.0) return ScheduleToHorizon(emptyList(), false)

        val needsRefinance = terms.loanTermMonths in 1 until months
        val extendable = if (terms.amortizationMonths > 0) months else null
        val rows = schedule(
            principal = terms.loanAmount,
            annualRatePct = terms.interestRatePct,
            termMonths = terms.loanTermMonths,
            amortizationMonths = terms.amortizationMonths,
            interestOnlyMonths = terms.interestOnlyMonths,
            maxMonths = extendable
        ).toMutableList()

        if (rows.size >= months) return ScheduleToHorizon(rows, needsRefinance)
        if (terms.amortizationMonths > 0) return ScheduleToHorizon(rows, needsRefinance)

        // interest-only bridge: model the take-out loan explicitly
        val rate = periodicRate(terms.interestRatePct)
        var balance = if (rows.isEmpty()) terms.loanAmount else rows[rows.size - 1].closingBalance
        val payment = amortizingPayment(balance, terms.interestRatePct, UnderwritingAssumptions.REFINANCE_EXTENSION_AMORTIZATION_MONTHS)
        val start = rows.size + 1
        for (month in start..months) {
            val interest = balance * rate
            var principalPaid = payment - interest
            if (principalPaid > balance) principalPaid = balance
            val cashPayment = principalPaid + interest
            rows.add(
                AmortizationRow(
                    month = month,
                    openingBalance = balance,
                    payment = cashPayment,
                    interest = interest,
                    principal = principalPaid,
                    closingBalance = balance - principalPaid,
                    isBalloonMonth = false,
                    assumesRefinance = true
                )
            )
            balance -= principalPaid
        }
        return ScheduleToHorizon(rows, needsRefinance)
    }

}

data class ScheduleToHorizon(
    val rows: List<AmortizationRow>,
    val refinanceAssumed: Boolean
)
