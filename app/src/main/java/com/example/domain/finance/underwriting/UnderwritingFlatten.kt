package com.example.domain.finance.underwriting

/**
 * A flat, dotted-path view of an [UnderwritingResult].
 *
 * Two jobs:
 *
 *  1. **Audit / export.** Every number in a result can be addressed as
 *     `core.monthlyCashFlow` or `brrrr.postRefiCore.dscr`, which is what an
 *     "explain this number" screen, a CSV export or a support ticket needs.
 *  2. **Cross-implementation verification.** The Kotlin unit tests replay the
 *     golden vectors produced by the independent Python oracle
 *     (`tools/underwriting_oracle`) and compare this map against it path by
 *     path. Nothing is compared through reflection: the mapping below is
 *     explicit, so a field that is silently dropped or renamed is caught.
 *
 * Null values are emitted as null rather than omitted, because "this metric is
 * undefined for this deal" (an all-cash DSCR, an infinite cash-on-cash) is a
 * result worth asserting on.
 *
 * `validation` and the criterion lists are emitted sorted: the *set* of findings
 * is contractual, the order they were discovered in is not.
 */
object UnderwritingFlatten {

    fun flatten(result: UnderwritingResult): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()

        out["specVersion"] = result.specVersion
        out["strategy"] = result.strategy.name
        out["financingModel"] = result.financingModel.name
        out["purchasePrice"] = result.purchasePrice
        out["rehabCost"] = result.rehabCost

        // ---- financing ----------------------------------------------------
        out["financing.model"] = result.financing.model.name
        out["financing.displayName"] = result.financing.displayName
        out["financing.loanAmount"] = result.financing.loanAmount
        out["financing.downPaymentAmount"] = result.financing.downPaymentAmount
        out["financing.downPaymentPctOfPrice"] = result.financing.downPaymentPctOfPrice
        out["financing.interestRatePct"] = result.financing.interestRatePct
        out["financing.loanTermMonths"] = result.financing.loanTermMonths.toDouble()
        out["financing.amortizationMonths"] = result.financing.amortizationMonths.toDouble()
        out["financing.interestOnlyMonths"] = result.financing.interestOnlyMonths.toDouble()
        out["financing.monthlyPayment"] = result.financing.monthlyPayment
        out["financing.newLoanFeesAndPoints"] = result.financing.newLoanFeesAndPoints
        out["financing.originationPointsPct"] = result.financing.originationPointsPct
        out["financing.lenderFees"] = result.financing.lenderFees
        out["financing.lenderReserveMonths"] = result.financing.lenderReserveMonths.toDouble()
        out["financing.isInterestOnly"] = result.financing.isInterestOnly
        out["financing.requestedLoanAmount"] = result.financing.requestedLoanAmount
        out["financing.loanCappedByLender"] = result.financing.loanCappedByLender
        out["financing.bindingLoanCeiling"] = result.financing.bindingLoanCeiling?.name
        out["financing.maxLtvPct"] = result.financing.maxLtvPct
        out["financing.maxLtcPct"] = result.financing.maxLtcPct

        // ---- operating statement (the seven reported lines) ---------------
        val operating = result.operating
        out["operating.grossScheduledIncomeAnnual"] = operating.grossScheduledIncomeAnnual
        out["operating.vacancyAndCreditLossAnnual"] = operating.vacancyAndCreditLossAnnual
        out["operating.effectiveGrossIncomeAnnual"] = operating.effectiveGrossIncomeAnnual
        out["operating.totalOperatingExpensesAnnual"] = operating.totalOperatingExpensesAnnual
        out["operating.netOperatingIncomeAnnual"] = operating.netOperatingIncomeAnnual
        out["operating.capitalReservesAnnual"] = operating.capitalReservesAnnual
        out["operating.capexTreatment"] = operating.capexTreatment.name

        // ---- core (the twenty primary metrics) ---------------------------
        putCore(out, "core", result.core, extended = false)

        // ---- capital stack (selection reported at the top level) ----------
        val stack = result.capitalStack
        out["capitalStack.downPaymentAmount"] = stack.downPaymentAmount
        out["capitalStack.cashRehab"] = stack.cashRehab
        out["capitalStack.closingCosts"] = stack.closingCosts
        out["capitalStack.loanFeesAndPoints"] = stack.loanFeesAndPoints
        out["capitalStack.lenderReserves"] = stack.lenderReserves
        out["capitalStack.sellerCredits"] = stack.sellerCredits
        out["capitalStack.totalCashRequired"] = stack.totalCashRequired

        // ---- strategy blocks ---------------------------------------------
        result.hold?.let { putHold(out, "hold", it) }
        result.flip?.let { putFlip(out, it) }
        result.wholesale?.let { putWholesale(out, it) }
        result.brrrr?.let { putBrrrr(out, it) }

        // ---- returns / verdict / validation / assumptions -----------------
        val returns = result.returns
        if (returns == null) {
            out["returns"] = null
        } else {
            out["returns.cashInvested"] = returns.cashInvested
            out["returns.holdPeriodMonths"] = returns.holdPeriodMonths
            out["returns.totalProfit"] = returns.totalProfit
            out["returns.roiPct"] = returns.roiPct
            out["returns.simpleAnnualizedRoiPct"] = returns.simpleAnnualizedRoiPct
            out["returns.annualizedRoiPct"] = returns.annualizedRoiPct
            out["returns.equityMultiple"] = returns.equityMultiple
            out["returns.irrAnnualPct"] = returns.irrAnnualPct
        }

        out["verdict.rating"] = result.verdict.rating.name
        out["verdict.passedCriteria"] = result.verdict.passedCriteria.sorted()
        out["verdict.failedCriteria"] = result.verdict.failedCriteria.sorted()

        out["validation.codes"] = result.validation
            .map { it.severity.name + ":" + it.code }
            .sorted()

        for ((field, record) in result.assumptionsUsed) {
            out["assumptionsUsed.$field.source"] = record.source.name
        }

        for ((metric, applicability) in result.metricApplicability) {
            out["metricApplicability.$metric"] = applicability.name
        }

        return out
    }

    private fun putCore(out: MutableMap<String, Any?>, prefix: String, core: CoreMetrics, extended: Boolean) {
        out["$prefix.purchasePrice"] = core.purchasePrice
        out["$prefix.closingCosts"] = core.closingCosts
        out["$prefix.rehabCost"] = core.rehabCost
        out["$prefix.loanAmount"] = core.loanAmount
        out["$prefix.downPaymentAmount"] = core.downPaymentAmount
        out["$prefix.ltvOfPricePct"] = core.ltvOfPricePct
        out["$prefix.totalCashRequired"] = core.totalCashRequired
        out["$prefix.monthlyDebtService"] = core.monthlyDebtService
        out["$prefix.annualDebtService"] = core.annualDebtService
        out["$prefix.noiAnnual"] = core.noiAnnual
        out["$prefix.monthlyCashFlow"] = core.monthlyCashFlow
        out["$prefix.annualCashFlow"] = core.annualCashFlow
        out["$prefix.capRateOnPricePct"] = core.capRateOnPricePct
        out["$prefix.capRateOnCostPct"] = core.capRateOnCostPct
        out["$prefix.cashOnCashPct"] = core.cashOnCashPct
        out["$prefix.dscr"] = core.dscr
        out["$prefix.breakEvenOccupancyPct"] = core.breakEvenOccupancyPct
        out["$prefix.grossRentMultiplier"] = core.grossRentMultiplier
        out["$prefix.operatingExpenseRatioPct"] = core.operatingExpenseRatioPct
        out["$prefix.effectiveGrossIncomeAnnual"] = core.effectiveGrossIncomeAnnual
        if (extended) {
            out["$prefix.grossScheduledIncomeAnnual"] = core.grossScheduledIncomeAnnual
            out["$prefix.totalOperatingExpensesAnnual"] = core.totalOperatingExpensesAnnual
            out["$prefix.infiniteCashOnCash"] = core.infiniteCashOnCash
        }
    }

    private fun putOperatingSummary(out: MutableMap<String, Any?>, prefix: String, statement: OperatingStatementResult) {
        out["$prefix.grossScheduledIncomeAnnual"] = statement.grossScheduledIncomeAnnual
        out["$prefix.effectiveGrossIncomeAnnual"] = statement.effectiveGrossIncomeAnnual
        out["$prefix.totalOperatingExpensesAnnual"] = statement.totalOperatingExpensesAnnual
        out["$prefix.netOperatingIncomeAnnual"] = statement.netOperatingIncomeAnnual
        out["$prefix.annualDebtService"] = statement.annualDebtService
        out["$prefix.monthlyDebtService"] = statement.monthlyDebtService
        out["$prefix.monthlyCashFlow"] = statement.monthlyCashFlow
        out["$prefix.annualCashFlow"] = statement.annualCashFlow
        out["$prefix.dscr"] = statement.dscr
        out["$prefix.capRateOnPricePct"] = statement.capRateOnPricePct
        out["$prefix.capRateOnCostPct"] = statement.capRateOnCostPct
        out["$prefix.breakEvenOccupancyPct"] = statement.breakEvenOccupancyPct
        out["$prefix.operatingExpenseRatioPct"] = statement.operatingExpenseRatioPct
        out["$prefix.grossRentMultiplier"] = statement.grossRentMultiplier
    }

    private fun putHold(out: MutableMap<String, Any?>, prefix: String, hold: HoldAnalysis) {
        out["$prefix.simpleAnnualizedRoiPct"] = hold.simpleAnnualizedRoiPct
        out["$prefix.exitValue"] = hold.exitValue
        out["$prefix.saleCosts"] = hold.saleCosts
        out["$prefix.loanBalanceAtExit"] = hold.loanBalanceAtExit
        out["$prefix.netSaleProceeds"] = hold.netSaleProceeds
        out["$prefix.cumulativeCashFlow"] = hold.cumulativeCashFlow
        out["$prefix.totalProfit"] = hold.totalProfit
        out["$prefix.roiPct"] = hold.roiPct
        out["$prefix.annualizedRoiPct"] = hold.annualizedRoiPct
        out["$prefix.equityMultiple"] = hold.equityMultiple
        out["$prefix.irrMonthly"] = hold.irrMonthly
        out["$prefix.irrAnnualPct"] = hold.irrAnnualPct
    }

    private fun putReturnMetrics(out: MutableMap<String, Any?>, prefix: String, returns: ReturnMetrics) {
        out["$prefix.cashInvested"] = returns.cashInvested
        out["$prefix.holdPeriodMonths"] = returns.holdPeriodMonths
        out["$prefix.totalProfit"] = returns.totalProfit
        out["$prefix.roiPct"] = returns.roiPct
        out["$prefix.simpleAnnualizedRoiPct"] = returns.simpleAnnualizedRoiPct
        out["$prefix.annualizedRoiPct"] = returns.annualizedRoiPct
        out["$prefix.equityMultiple"] = returns.equityMultiple
        out["$prefix.irrAnnualPct"] = returns.irrAnnualPct
    }

    private fun putFlip(out: MutableMap<String, Any?>, flip: FlipAnalysis) {
        out["flip.arv"] = flip.arv
        out["flip.rehabBase"] = flip.rehabBase
        out["flip.rehabContingencyPct"] = flip.rehabContingencyPct
        out["flip.totalRehabCost"] = flip.totalRehabCost
        out["flip.holdMonths"] = flip.holdMonths.toDouble()
        out["flip.buyClosingCosts"] = flip.buyClosingCosts
        out["flip.holdingCostsTotal"] = flip.holdingCostsTotal
        out["flip.holdingCostsMonthly"] = flip.holdingCostsMonthly
        out["flip.loanAmount"] = flip.loanAmount
        out["flip.loanInterestPaid"] = flip.loanInterestPaid
        out["flip.loanFeesAndPoints"] = flip.loanFeesAndPoints
        out["flip.lenderMinimumInterestMonths"] = flip.lenderMinimumInterestMonths.toDouble()
        out["flip.allInCost"] = flip.allInCost
        out["flip.grossSalePrice"] = flip.grossSalePrice
        out["flip.sellingCosts"] = flip.sellingCosts
        out["flip.netSaleProceeds"] = flip.netSaleProceeds
        out["flip.profit"] = flip.profit
        out["flip.profitMarginOnArvPct"] = flip.profitMarginOnArvPct
        out["flip.profitMarginOnCostPct"] = flip.profitMarginOnCostPct
        out["flip.maoSeventyRule"] = flip.maoSeventyRule
        out["flip.maxOfferForTargetProfit"] = flip.maxOfferForTargetProfit
        out["flip.targetProfitPctOfArv"] = flip.targetProfitPctOfArv
        out["flip.targetProfitAmount"] = flip.targetProfitAmount
        out["flip.breakEvenArv"] = flip.breakEvenArv
        out["flip.breakEvenArvCushionPct"] = flip.breakEvenArvCushionPct
        putReturnMetrics(out, "flip.returnMetrics", flip.returnMetrics)
    }

    private fun putWholesale(out: MutableMap<String, Any?>, wholesale: WholesaleAnalysis) {
        out["wholesale.mode"] = wholesale.mode.name
        out["wholesale.contractPrice"] = wholesale.contractPrice
        out["wholesale.arv"] = wholesale.arv
        out["wholesale.assignmentFee"] = wholesale.assignmentFee
        out["wholesale.assignmentFeeSource"] = wholesale.assignmentFeeSource
        out["wholesale.earnestMoney"] = wholesale.earnestMoney
        out["wholesale.marketingCost"] = wholesale.marketingCost
        out["wholesale.buySideClosingCosts"] = wholesale.buySideClosingCosts
        out["wholesale.sellSideClosingCosts"] = wholesale.sellSideClosingCosts
        out["wholesale.grossRevenue"] = wholesale.grossRevenue
        out["wholesale.totalCosts"] = wholesale.totalCosts
        out["wholesale.profit"] = wholesale.profit
        out["wholesale.daysToClose"] = wholesale.daysToClose.toDouble()
        out["wholesale.buyerAllInCost"] = wholesale.buyerAllInCost
        out["wholesale.buyerProfit"] = wholesale.buyerProfit
        out["wholesale.buyerProfitPctOfArv"] = wholesale.buyerProfitPctOfArv
        out["wholesale.maxAssignableFee"] = wholesale.maxAssignableFee
        out["wholesale.maoSeventyRule"] = wholesale.maoSeventyRule
        putReturnMetrics(out, "wholesale.returnMetrics", wholesale.returnMetrics)
    }

    private fun putBrrrr(out: MutableMap<String, Any?>, brrrr: BrrrrAnalysis) {
        out["brrrr.arv"] = brrrr.arv
        out["brrrr.acquisitionModel"] = brrrr.acquisitionModel.name
        out["brrrr.bridgeLoanAmount"] = brrrr.bridgeLoanAmount
        out["brrrr.bridgeInterestPaid"] = brrrr.bridgeInterestPaid
        out["brrrr.bridgeLoanFeesAndPoints"] = brrrr.bridgeLoanFeesAndPoints
        out["brrrr.bridgePayoffAmount"] = brrrr.bridgePayoffAmount
        out["brrrr.rehabMonths"] = brrrr.rehabMonths.toDouble()
        out["brrrr.holdingCostsDuringRehab"] = brrrr.holdingCostsDuringRehab
        out["brrrr.phase1CashInvested"] = brrrr.phase1CashInvested
        out["brrrr.cashOutAtRefinance"] = brrrr.cashOutAtRefinance
        out["brrrr.cashLeftInDeal"] = brrrr.cashLeftInDeal
        out["brrrr.capitalRecoveredPct"] = brrrr.capitalRecoveredPct
        out["brrrr.infiniteCashOnCash"] = brrrr.infiniteCashOnCash
        out["brrrr.postRefiCashOnCashPct"] = brrrr.postRefiCashOnCashPct
        out["brrrr.postRefiMonthlyCashFlow"] = brrrr.postRefiMonthlyCashFlow
        out["brrrr.postRefiDscr"] = brrrr.postRefiDscr
        out["brrrr.postRefiCapRateOnArvPct"] = brrrr.postRefiCapRateOnArvPct
        out["brrrr.equityCreatedAtRefi"] = brrrr.equityCreatedAtRefi

        out["brrrr.refinance.loanAmount"] = brrrr.refinance.loanAmount
        out["brrrr.refinance.ltvOfArvPct"] = brrrr.refinance.ltvOfArvPct
        out["brrrr.refinance.interestRatePct"] = brrrr.refinance.interestRatePct
        out["brrrr.refinance.loanTermMonths"] = brrrr.refinance.loanTermMonths.toDouble()
        out["brrrr.refinance.amortizationMonths"] = brrrr.refinance.amortizationMonths.toDouble()
        out["brrrr.refinance.monthlyPayment"] = brrrr.refinance.monthlyPayment
        out["brrrr.refinance.closingCostsAndPoints"] = brrrr.refinance.closingCostsAndPoints

        // the stabilised core is reported in full (23 metrics, not the 20 of the
        // top-level block) because it is the basis of the BRRRR verdict
        putCore(out, "brrrr.postRefiCore", brrrr.postRefiCore, extended = true)
        putOperatingSummary(out, "brrrr.postRefiOperating", brrrr.postRefiOperating)
        out["brrrr.postRefiCapitalStack.downPaymentAmount"] = brrrr.postRefiCapitalStack.downPaymentAmount
        out["brrrr.postRefiCapitalStack.cashRehab"] = brrrr.postRefiCapitalStack.cashRehab
        out["brrrr.postRefiCapitalStack.closingCosts"] = brrrr.postRefiCapitalStack.closingCosts
        out["brrrr.postRefiCapitalStack.loanFeesAndPoints"] = brrrr.postRefiCapitalStack.loanFeesAndPoints
        out["brrrr.postRefiCapitalStack.lenderReserves"] = brrrr.postRefiCapitalStack.lenderReserves
        out["brrrr.postRefiCapitalStack.sellerCredits"] = brrrr.postRefiCapitalStack.sellerCredits
        out["brrrr.postRefiCapitalStack.totalCashRequired"] = brrrr.postRefiCapitalStack.totalCashRequired
        out["brrrr.postRefiCapitalStack.ltvOfPricePct"] = brrrr.postRefiCapitalStack.ltvOfPricePct
        putHold(out, "brrrr.postRefiHold", brrrr.postRefiHold)
    }
}
