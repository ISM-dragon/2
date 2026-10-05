package com.example.domain.finance.underwriting

import kotlin.math.pow

/**
 * Multi-year projections, exit maths and the internal rate of return.
 *
 * Two annualisation conventions are reported side by side because they answer
 * different questions and conflating them is a classic underwriting error:
 *
 *  * [ReturnMetrics.annualizedRoiPct] is the geometric CAGR - what the money
 *    compounded at. For a very short hold it becomes enormous (a 460% return in
 *    six weeks annualises to a nine-figure percentage), so project deals are
 *    judged on the period return instead.
 *  * [ReturnMetrics.simpleAnnualizedRoiPct] is the linear extrapolation
 *    (return x 12 / months). It is the figure most flippers quote, and it is
 *    always finite.
 *
 * The IRR is the only iterative computation in the engine. Its bracket and
 * iteration count are fixed constants, so it is deterministic: the same cash
 * flow stream always yields the same rate.
 */
object ReturnMetricsCalculator {

    /**
     * Year-by-year hold projection from the stabilised operating statement.
     *
     * @param openingValue value the growth starts from (ARV for a BRRRR refi,
     *                     purchase price otherwise)
     */
    fun project(
        operating: OperatingStatementResult,
        terms: FinancingTerms,
        schedule: List<AmortizationRow>,
        purchasePrice: Double,
        holdYears: Int,
        rentGrowthPct: Double?,
        expenseGrowthPct: Double?,
        appreciationPct: Double?,
        saleCostPct: Double?,
        totalCashRequired: Double,
        openingValue: Double? = null
    ): HoldAnalysis {
        val rentGrowth = (rentGrowthPct ?: UnderwritingAssumptions.RENT_GROWTH_PCT) / 100.0
        val expenseGrowth = (expenseGrowthPct ?: UnderwritingAssumptions.EXPENSE_GROWTH_PCT) / 100.0
        val appreciation = (appreciationPct ?: UnderwritingAssumptions.APPRECIATION_PCT) / 100.0
        val saleCost = (saleCostPct ?: UnderwritingAssumptions.SALE_COST_PCT) / 100.0

        val startValue = openingValue ?: purchasePrice
        val egi = operating.effectiveGrossIncomeAnnual
        val opex = operating.totalOperatingExpensesAnnual

        val years = ArrayList<ProjectionYear>(holdYears)
        var cumulativeCashFlow = 0.0

        for (year in 1..holdYears) {
            val yearEgi = egi * (1.0 + rentGrowth).pow(year - 1)
            val yearOpex = opex * (1.0 + expenseGrowth).pow(year - 1)
            val yearNoi = yearEgi - yearOpex

            var yearDebtService = 0.0
            val firstMonth = (year - 1) * 12 + 1
            val lastMonth = year * 12
            for (month in firstMonth..lastMonth) {
                if (month - 1 < schedule.size) yearDebtService += schedule[month - 1].payment
            }
            val yearCashFlow = yearNoi - yearDebtService
            cumulativeCashFlow += yearCashFlow

            years.add(
                ProjectionYear(
                    year = year,
                    effectiveGrossIncome = yearEgi,
                    operatingExpenses = yearOpex,
                    netOperatingIncome = yearNoi,
                    debtService = yearDebtService,
                    cashFlow = yearCashFlow,
                    dscr = if (yearDebtService > 0.0) yearNoi / yearDebtService else null,
                    propertyValue = startValue * (1.0 + appreciation).pow(year.toDouble()),
                    loanBalance = Amortization.balanceAfter(schedule, year * 12)
                )
            )
        }

        val exitValue = startValue * (1.0 + appreciation).pow(holdYears.toDouble())
        val saleCosts = exitValue * saleCost
        val loanBalanceAtExit = Amortization.balanceAfter(schedule, holdYears * 12)
        val netSaleProceeds = exitValue - saleCosts - loanBalanceAtExit

        val cashInvested = totalCashRequired
        val totalProfit = cumulativeCashFlow + netSaleProceeds - cashInvested
        val equityMultiple = if (cashInvested > 0.0) (cumulativeCashFlow + netSaleProceeds) / cashInvested else null
        val roiPct = if (cashInvested > 0.0) totalProfit / cashInvested * 100.0 else null

        val annualizedRoi = annualizeFromMultiple(equityMultiple, holdYears)
        val simpleAnnualized = if (roiPct != null && holdYears > 0) roiPct / holdYears else null

        // monthly IRR stream: equity out at month 0, one twelfth of each year's
        // cash flow thereafter, with the net sale proceeds on the final month
        val stream = ArrayList<Double>(holdYears * 12 + 1)
        stream.add(-cashInvested)
        for (year in 1..holdYears) {
            for (month in 1..12) stream.add(years[year - 1].cashFlow / 12.0)
        }
        if (stream.size > 1) {
            stream[stream.size - 1] = stream[stream.size - 1] + netSaleProceeds
        }
        val irrMonthly = internalRateOfReturn(stream)
        val irrAnnual = if (irrMonthly != null) (1.0 + irrMonthly).pow(12.0) - 1.0 else null

        return HoldAnalysis(
            simpleAnnualizedRoiPct = simpleAnnualized,
            years = years,
            exitValue = exitValue,
            saleCosts = saleCosts,
            loanBalanceAtExit = loanBalanceAtExit,
            netSaleProceeds = netSaleProceeds,
            cumulativeCashFlow = cumulativeCashFlow,
            totalProfit = totalProfit,
            roiPct = roiPct,
            annualizedRoiPct = annualizedRoi?.times(100.0),
            equityMultiple = equityMultiple,
            irrMonthly = irrMonthly,
            irrAnnualPct = irrAnnual?.times(100.0)
        )
    }

    /**
     * Deterministic monthly IRR by bisection over a fixed bracket.
     *
     * Returns null - never NaN, never a guessed number - when the stream has no
     * sign change (no real IRR exists) or when the bracket does not contain one.
     */
    fun internalRateOfReturn(
        cashFlows: List<Double>,
        lowRate: Double = UnderwritingAssumptions.IRR_LOW_MONTHLY,
        highRate: Double = UnderwritingAssumptions.IRR_HIGH_MONTHLY,
        iterations: Int = UnderwritingAssumptions.IRR_ITERATIONS
    ): Double? {
        if (cashFlows.size < 2) return null
        var hasPositive = false
        var hasNegative = false
        for (flow in cashFlows) {
            if (flow > 0.0) hasPositive = true
            if (flow < 0.0) hasNegative = true
        }
        if (!hasPositive || !hasNegative) return null

        fun npv(rate: Double): Double {
            var total = 0.0
            for (period in cashFlows.indices) {
                val flow = cashFlows[period]
                if (flow == 0.0) continue
                // Math.pow returns Infinity on overflow rather than throwing; a
                // discount factor that has collapsed to 0 (or blown up to
                // Infinity) contributes nothing to present value.
                val denominator = (1.0 + rate).pow(period.toDouble())
                if (denominator == 0.0 || denominator.isInfinite()) continue
                total += flow / denominator
            }
            return total
        }

        var low = lowRate
        var high = highRate
        var lowValue = npv(low)
        val highValue = npv(high)
        if (!lowValue.isFinite() || !highValue.isFinite()) return null
        if (lowValue * highValue > 0.0) return null

        for (step in 0 until iterations) {
            val mid = (low + high) / 2.0
            val midValue = npv(mid)
            if (lowValue * midValue <= 0.0) {
                high = mid
            } else {
                low = mid
                lowValue = midValue
            }
        }
        val result = (low + high) / 2.0
        return if (result.isFinite()) result else null
    }

    /**
     * Geometric annualisation (CAGR). Undefined - and therefore null - when the
     * multiple is not positive: compounding a total loss is not a number, and
     * letting a power operator produce NaN or a complex value would poison every
     * downstream comparison.
     */
    fun annualizeFromMultiple(multiple: Double?, years: Int): Double? {
        if (multiple == null || years <= 0) return null
        if (multiple <= 0.0) return null
        return multiple.pow(1.0 / years) - 1.0
    }

    /** Geometric annualisation of a period return; null when (1 + roi) <= 0. */
    fun annualizeFromRoi(roiFraction: Double?, months: Double): Double? {
        if (roiFraction == null || months <= 0.0) return null
        val base = 1.0 + roiFraction
        if (base <= 0.0) return null
        return base.pow(12.0 / months) - 1.0
    }

    /** Linear annualisation over a month count (12 / months). */
    fun simpleAnnualizeFromMonths(roiPct: Double?, months: Double): Double? {
        if (roiPct == null || months <= 0.0) return null
        return roiPct * 12.0 / months
    }

    /**
     * Linear annualisation over a calendar day count (365 / days), which is the
     * convention wholesale deals are quoted in because they close in days
     * rather than months.
     */
    fun simpleAnnualizeFromDays(roiPct: Double?, days: Double): Double? {
        if (roiPct == null || days <= 0.0) return null
        return roiPct * 365.0 / days
    }

}
