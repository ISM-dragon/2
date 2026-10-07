package com.example.domain.finance.underwriting

/**
 * The glue that keeps [UnderwritingEngine] readable: input sanitisation, the
 * capital stack, the core metric block and the rule-based verdict.
 *
 * All of it is pure: no I/O, no clock, no randomness.
 */
object EngineSupport {

    // =======================================================================
    // input sanity
    // =======================================================================

    /**
     * A UI text field can produce `NaN` ("abc" parsed loosely) or `Infinity`
     * ("1e999"). One NaN silently poisons every downstream metric, so the engine
     * refuses them up front: the offending field is reported as an ERROR and
     * treated as "not supplied", which lets the documented default apply.
     */
    fun sanitize(input: UnderwritingInput): SanitizedInput {
        val issues = ArrayList<ValidationIssue>()

        fun check(name: String, value: Double?): Double? {
            if (value != null && !value.isFinite()) {
                issues.add(
                    ValidationIssue(
                        "NON_FINITE_INPUT", IssueSeverity.ERROR,
                        "Input '$name' was not finite (NaN or Infinity) and was ignored; " +
                            "the documented default applies instead."
                    )
                )
                return null
            }
            return value
        }

        fun checkMap(prefix: String, source: Map<String, Double>): Map<String, Double> {
            val clean = LinkedHashMap<String, Double>()
            for ((key, value) in source) {
                val checked = check("$prefix.$key", value)
                if (checked != null) clean[key] = checked
            }
            return clean
        }

        val clean = input.copy(
            purchasePrice = check("purchasePrice", input.purchasePrice) ?: 0.0,
            closingCosts = check("closingCosts", input.closingCosts),
            rehabCost = check("rehabCost", input.rehabCost) ?: 0.0,
            arv = check("arv", input.arv),
            sellerCredits = check("sellerCredits", input.sellerCredits) ?: 0.0,
            monthlyRent = check("monthlyRent", input.monthlyRent) ?: 0.0,
            otherMonthlyIncome = check("otherMonthlyIncome", input.otherMonthlyIncome) ?: 0.0,
            vacancyRatePct = check("vacancyRatePct", input.vacancyRatePct),
            creditLossRatePct = check("creditLossRatePct", input.creditLossRatePct),
            propertyTaxAnnual = check("propertyTaxAnnual", input.propertyTaxAnnual),
            propertyTaxPctOfPrice = check("propertyTaxPctOfPrice", input.propertyTaxPctOfPrice),
            insuranceAnnual = check("insuranceAnnual", input.insuranceAnnual),
            insurancePctOfPrice = check("insurancePctOfPrice", input.insurancePctOfPrice),
            hoaMonthly = check("hoaMonthly", input.hoaMonthly),
            maintenanceAnnual = check("maintenanceAnnual", input.maintenanceAnnual),
            maintenancePctOfGsi = check("maintenancePctOfGsi", input.maintenancePctOfGsi),
            managementAnnual = check("managementAnnual", input.managementAnnual),
            managementPctOfEgi = check("managementPctOfEgi", input.managementPctOfEgi),
            capexReservePctOfGsi = check("capexReservePctOfGsi", input.capexReservePctOfGsi),
            utilitiesMonthly = check("utilitiesMonthly", input.utilitiesMonthly),
            landscapingMonthly = check("landscapingMonthly", input.landscapingMonthly),
            otherOperatingAnnual = check("otherOperatingAnnual", input.otherOperatingAnnual),
            downPaymentPct = check("downPaymentPct", input.downPaymentPct),
            downPaymentAmount = check("downPaymentAmount", input.downPaymentAmount),
            loanAmount = check("loanAmount", input.loanAmount),
            interestRatePct = check("interestRatePct", input.interestRatePct),
            originationPointsPct = check("originationPointsPct", input.originationPointsPct),
            lenderFees = check("lenderFees", input.lenderFees),
            maxLtvPct = check("maxLtvPct", input.maxLtvPct),
            maxLtcPct = check("maxLtcPct", input.maxLtcPct),
            maxLtvArvPct = check("maxLtvArvPct", input.maxLtvArvPct),
            loanOverrides = checkMap("loanOverrides", input.loanOverrides),
            rentGrowthPct = check("rentGrowthPct", input.rentGrowthPct),
            expenseGrowthPct = check("expenseGrowthPct", input.expenseGrowthPct),
            appreciationPct = check("appreciationPct", input.appreciationPct),
            saleCostPct = check("saleCostPct", input.saleCostPct),
            rehabContingencyPct = check("rehabContingencyPct", input.rehabContingencyPct),
            holdingCostsMonthly = check("holdingCostsMonthly", input.holdingCostsMonthly),
            buyClosingCostPct = check("buyClosingCostPct", input.buyClosingCostPct),
            sellClosingCostPct = check("sellClosingCostPct", input.sellClosingCostPct),
            sellerConcessions = check("sellerConcessions", input.sellerConcessions),
            flipTargetProfitPctOfArv = check("flipTargetProfitPctOfArv", input.flipTargetProfitPctOfArv),
            bridgeOverrides = checkMap("bridgeOverrides", input.bridgeOverrides),
            refiLtvPctOfArv = check("refiLtvPctOfArv", input.refiLtvPctOfArv),
            refiInterestRatePct = check("refiInterestRatePct", input.refiInterestRatePct),
            refiOriginationPointsPct = check("refiOriginationPointsPct", input.refiOriginationPointsPct),
            refiLenderFees = check("refiLenderFees", input.refiLenderFees),
            refiClosingCostPct = check("refiClosingCostPct", input.refiClosingCostPct),
            assignmentFee = check("assignmentFee", input.assignmentFee),
            assignmentFeePctOfArv = check("assignmentFeePctOfArv", input.assignmentFeePctOfArv),
            earnestMoney = check("earnestMoney", input.earnestMoney),
            marketingCost = check("marketingCost", input.marketingCost),
            buyerRehabEstimate = check("buyerRehabEstimate", input.buyerRehabEstimate),
            buyerSellCostPct = check("buyerSellCostPct", input.buyerSellCostPct),
            buyerMinProfitPctOfArv = check("buyerMinProfitPctOfArv", input.buyerMinProfitPctOfArv),
            minDscrForLender = check("minDscrForLender", input.minDscrForLender),
            minCashOnCashPct = check("minCashOnCashPct", input.minCashOnCashPct),
            minCapRatePct = check("minCapRatePct", input.minCapRatePct),
            flipMinProfitPctOfArv = check("flipMinProfitPctOfArv", input.flipMinProfitPctOfArv),
            flipMinRoiPctOfCash = check("flipMinRoiPctOfCash", input.flipMinRoiPctOfCash),
            wholesaleMinimumProfit = check("wholesaleMinimumProfit", input.wholesaleMinimumProfit)
        )
        return SanitizedInput(clean, issues)
    }

    fun defaultModelFor(strategy: InvestmentStrategy): FinancingModel = when (strategy) {
        InvestmentStrategy.FIX_AND_FLIP, InvestmentStrategy.BRRRR -> FinancingModel.HARD_MONEY
        InvestmentStrategy.BUY_AND_HOLD, InvestmentStrategy.WHOLESALE -> FinancingModel.CONVENTIONAL
    }

    // =======================================================================
    // capital stack
    // =======================================================================

    /**
     * Every dollar that has to be wired at closing:
     *
     *   down payment + cash rehab + buy-side closing costs + points/lender fees
     *   + lender reserves required to fund + extra project cash
     *   - seller credits
     *
     * Lender reserves are expressed in months of PITI, because that is how the
     * lender states them, and the taxes/insurance inside that figure follow the
     * same explicit rules as the operating statement.
     */
    fun buildCapitalStack(
        input: UnderwritingInput,
        purchasePrice: Double,
        rehabCost: Double,
        closingCosts: Double,
        terms: FinancingTerms,
        extraCash: Double = 0.0
    ): CapitalStackBuild {
        val issues = ArrayList<ValidationIssue>()

        val downPayment = terms.downPaymentAmount
        val cashRehab = (rehabCost - terms.financedRehab).coerceAtLeast(0.0)
        val loanFees = terms.newLoanFeesAndPoints

        val monthlyPiti: Double
        if (terms.lenderReserveMonths > 0) {
            val taxes = input.propertyTaxAnnual
                ?: purchasePrice * UnderwritingAssumptions.PROPERTY_TAX_PCT_OF_PRICE / 100.0
            val monthlyTaxes = taxes / 12.0
            val insurance = input.insuranceAnnual
                ?: purchasePrice * UnderwritingAssumptions.INSURANCE_PCT_OF_PRICE / 100.0
            val monthlyInsurance = insurance / 12.0
            monthlyPiti = terms.monthlyPayment + monthlyTaxes + monthlyInsurance + (input.hoaMonthly ?: 0.0)
        } else {
            monthlyPiti = 0.0
        }
        val reserves = monthlyPiti * terms.lenderReserveMonths
        val sellerCredits = input.sellerCredits

        var total = downPayment + closingCosts + cashRehab + loanFees + reserves - sellerCredits + extraCash
        if (total < 0.0) {
            issues.add(
                ValidationIssue(
                    "NEGATIVE_CASH_REQUIRED", IssueSeverity.WARNING,
                    "Credits exceed the cash needed to close; cash required was floored at zero."
                )
            )
            total = 0.0
        }

        val ltvOfPricePct = if (purchasePrice > 0.0) terms.loanAmount / purchasePrice * 100.0 else null

        val stack = CapitalStack(
            downPaymentAmount = downPayment,
            cashRehab = cashRehab,
            closingCosts = closingCosts,
            loanFeesAndPoints = loanFees,
            lenderReserves = reserves,
            sellerCredits = sellerCredits,
            totalCashRequired = total,
            ltvOfPricePct = ltvOfPricePct
        )
        return CapitalStackBuild(stack, issues)
    }

    // =======================================================================
    // core metrics
    // =======================================================================

    fun buildCore(
        purchasePrice: Double,
        closingCosts: Double,
        rehabCost: Double,
        terms: FinancingTerms,
        operating: OperatingStatementResult,
        stack: CapitalStack
    ): CoreMetrics {
        val cashOnCash = if (stack.totalCashRequired > 0.0) {
            operating.annualCashFlow / stack.totalCashRequired * 100.0
        } else {
            null
        }
        return CoreMetrics(
            purchasePrice = purchasePrice,
            closingCosts = closingCosts,
            rehabCost = rehabCost,
            loanAmount = terms.loanAmount,
            downPaymentAmount = stack.downPaymentAmount,
            ltvOfPricePct = stack.ltvOfPricePct,
            totalCashRequired = stack.totalCashRequired,
            monthlyDebtService = operating.monthlyDebtService,
            annualDebtService = operating.annualDebtService,
            noiAnnual = operating.netOperatingIncomeAnnual,
            monthlyCashFlow = operating.monthlyCashFlow,
            annualCashFlow = operating.annualCashFlow,
            capRateOnPricePct = operating.capRateOnPricePct,
            capRateOnCostPct = operating.capRateOnCostPct,
            cashOnCashPct = cashOnCash,
            dscr = operating.dscr,
            breakEvenOccupancyPct = operating.breakEvenOccupancyPct,
            grossRentMultiplier = operating.grossRentMultiplier,
            operatingExpenseRatioPct = operating.operatingExpenseRatioPct,
            effectiveGrossIncomeAnnual = operating.effectiveGrossIncomeAnnual,
            grossScheduledIncomeAnnual = operating.grossScheduledIncomeAnnual,
            totalOperatingExpensesAnnual = operating.totalOperatingExpensesAnnual
        )
    }

    // =======================================================================
    // verdict
    // =======================================================================

    /**
     * Rule-based verdict. No model, no randomness, no hidden weightings: each
     * criterion either passes or fails against an explicit threshold, and the
     * result lists which ones did what so the user can disagree with a threshold
     * and change it in the input.
     */
    fun qualify(
        input: UnderwritingInput,
        strategy: InvestmentStrategy,
        core: CoreMetrics,
        flip: FlipAnalysis?,
        wholesale: WholesaleAnalysis?,
        brrrr: BrrrrAnalysis?
    ): DealVerdict {
        val passed = ArrayList<String>()
        val failed = ArrayList<String>()

        when (strategy) {
            InvestmentStrategy.BUY_AND_HOLD, InvestmentStrategy.BRRRR -> {
                val minCoc = input.minCashOnCashPct ?: UnderwritingAssumptions.MIN_CASH_ON_CASH_PCT
                val minDscr = input.minDscrForLender ?: UnderwritingAssumptions.MIN_DSCR_FOR_LENDER
                val minCap = input.minCapRatePct ?: UnderwritingAssumptions.MIN_CAP_RATE_PCT

                val coc = core.cashOnCashPct
                if (coc == null) {
                    passed.add("CASH_ON_CASH_NOT_APPLICABLE")
                } else if (coc >= minCoc) {
                    passed.add("CASH_ON_CASH")
                } else {
                    failed.add("CASH_ON_CASH_BELOW_MIN")
                }

                val dscr = core.dscr
                if (dscr == null) {
                    passed.add("DSCR_NOT_APPLICABLE_ALL_CASH")
                } else if (dscr >= minDscr) {
                    passed.add("DSCR")
                } else {
                    failed.add("DSCR_BELOW_MIN")
                }

                val capRate = core.capRateOnPricePct
                if (capRate != null && capRate >= minCap) {
                    passed.add("CAP_RATE")
                } else {
                    failed.add("CAP_RATE_BELOW_MIN")
                }

                if (core.monthlyCashFlow > 0.0) {
                    passed.add("POSITIVE_CASH_FLOW")
                } else {
                    failed.add("NEGATIVE_CASH_FLOW")
                }

                if (strategy == InvestmentStrategy.BRRRR) {
                    if (brrrr == null) {
                        failed.add("MISSING_REQUIRED_INPUT")
                    } else if (brrrr.infiniteCashOnCash) {
                        passed.add("CAPITAL_FULLY_RECOVERED")
                    } else {
                        val postCoc = brrrr.postRefiCashOnCashPct
                        if (postCoc != null && postCoc >= minCoc) {
                            passed.add("POST_REFI_CASH_ON_CASH")
                        } else {
                            failed.add("POST_REFI_CASH_ON_CASH_BELOW_MIN")
                        }
                    }
                }
            }

            InvestmentStrategy.FIX_AND_FLIP -> {
                // Project deals are judged on the project: profit, margin and the
                // return on the cash actually at risk. An annualised figure is
                // reported for reference but never decides a six-month flip.
                if (flip == null) {
                    failed.add("MISSING_REQUIRED_INPUT")
                } else {
                    val minMargin = input.flipMinProfitPctOfArv ?: UnderwritingAssumptions.FLIP_MIN_PROFIT_PCT_OF_ARV
                    val minRoi = input.flipMinRoiPctOfCash ?: UnderwritingAssumptions.FLIP_MIN_ROI_PCT_OF_CASH
                    if (flip.profit > 0.0) passed.add("PROFITABLE") else failed.add("NOT_PROFITABLE")
                    if (flip.profitMarginOnArvPct >= minMargin) {
                        passed.add("PROFIT_MARGIN")
                    } else {
                        failed.add("PROFIT_MARGIN_BELOW_MIN")
                    }
                    val roi = flip.returnMetrics.roiPct
                    if (roi != null && roi >= minRoi) {
                        passed.add("RETURN_ON_CASH")
                    } else {
                        failed.add("RETURN_ON_CASH_BELOW_MIN")
                    }
                }
            }

            InvestmentStrategy.WHOLESALE -> {
                if (wholesale == null) {
                    failed.add("MISSING_REQUIRED_INPUT")
                } else {
                    val minProfit = input.wholesaleMinimumProfit
                        ?: UnderwritingAssumptions.WHOLESALE_MINIMUM_PROFIT_AMOUNT
                    if (wholesale.profit > 0.0) passed.add("PROFITABLE") else failed.add("NOT_PROFITABLE")
                    if (wholesale.profit >= minProfit) {
                        passed.add("FEE_ABOVE_MINIMUM")
                    } else {
                        failed.add("FEE_BELOW_MINIMUM")
                    }
                    if (wholesale.maxAssignableFee >= wholesale.assignmentFee) {
                        passed.add("FEE_WITHIN_BUYER_HEADROOM")
                    } else {
                        failed.add("FEE_EXCEEDS_BUYER_HEADROOM")
                    }
                }
            }
        }

        val rating = when {
            failed.isEmpty() && core.monthlyCashFlow > 0.0 -> DealRating.STRONG
            failed.isEmpty() -> DealRating.ACCEPTABLE
            failed.size == 1 && (failed[0] == "PROFIT_MARGIN_BELOW_MIN" ||
                failed[0] == "RETURN_ON_CASH_BELOW_MIN" ||
                failed[0] == "FEE_BELOW_MINIMUM") -> DealRating.MARGINAL
            failed.contains("NOT_PROFITABLE") || failed.contains("NEGATIVE_CASH_FLOW") ||
                failed.contains("MISSING_REQUIRED_INPUT") -> DealRating.FAILS_CRITERIA
            else -> DealRating.MARGINAL
        }

        return DealVerdict(
            rating = rating,
            passedCriteria = passed,
            failedCriteria = failed
        )
    }

    // =======================================================================
    // reporting helpers
    // =======================================================================

    fun assumptionRecords(provenance: Map<String, ValueSource>): Map<String, AssumptionRecord> {
        val records = LinkedHashMap<String, AssumptionRecord>()
        for (key in provenance.keys.sorted()) {
            val source = provenance.getValue(key)
            records[key] = AssumptionRecord(field = key, source = source, value = source.name)
        }
        return records
    }

    fun applicabilityFor(strategy: InvestmentStrategy): Map<String, MetricApplicability> = when (strategy) {
        InvestmentStrategy.BUY_AND_HOLD, InvestmentStrategy.BRRRR -> mapOf(
            "netOperatingIncomeAnnual" to MetricApplicability.APPLICABLE,
            "capRate" to MetricApplicability.APPLICABLE,
            "dscr" to MetricApplicability.APPLICABLE,
            "cashOnCash" to MetricApplicability.APPLICABLE
        )

        InvestmentStrategy.FIX_AND_FLIP -> mapOf(
            "netOperatingIncomeAnnual" to MetricApplicability.INFORMATIONAL_ONLY,
            "capRate" to MetricApplicability.INFORMATIONAL_ONLY,
            "dscr" to MetricApplicability.NOT_APPLICABLE,
            "cashOnCash" to MetricApplicability.NOT_APPLICABLE
        )

        InvestmentStrategy.WHOLESALE -> mapOf(
            "netOperatingIncomeAnnual" to MetricApplicability.NOT_APPLICABLE,
            "capRate" to MetricApplicability.NOT_APPLICABLE,
            "dscr" to MetricApplicability.NOT_APPLICABLE,
            "cashOnCash" to MetricApplicability.NOT_APPLICABLE
        )
    }
}

data class SanitizedInput(
    val input: UnderwritingInput,
    val issues: List<ValidationIssue>
)

data class CapitalStackBuild(
    val stack: CapitalStack,
    val issues: List<ValidationIssue>
)
