package com.example.domain.finance.underwriting

/**
 * The four strategies an investor can run on the same property.
 *
 * Each analyzer returns its own block plus the [ReturnMetrics] that strategy is
 * judged on, and never mutates shared state. Inputs it needs and does not get
 * (an ARV for a flip, for example) produce an ERROR issue and a null block
 * rather than a guess - a deal whose thesis cannot be computed is not rated.
 */
object StrategyAnalyzers {

    // =======================================================================
    // BUY_AND_HOLD
    // =======================================================================

    fun analyzeBuyAndHold(input: UnderwritingInput, base: AnalysisBase): StrategyOutcome {
        val issues = ArrayList<ValidationIssue>()

        var holdYears = input.holdYears ?: UnderwritingAssumptions.HOLD_YEARS
        if (holdYears < 1) {
            issues.add(
                ValidationIssue(
                    "HOLD_YEARS_DEFAULTED", IssueSeverity.WARNING,
                    "holdYears must be at least 1; used the documented default of " +
                        "${UnderwritingAssumptions.HOLD_YEARS}."
                )
            )
            holdYears = UnderwritingAssumptions.HOLD_YEARS
        }

        val horizon = Amortization.scheduleToHorizon(base.terms, holdYears * 12)
        if (horizon.refinanceAssumed) {
            issues.add(
                ValidationIssue(
                    "REFINANCE_ASSUMED_AT_MATURITY", IssueSeverity.WARNING,
                    "The ${base.terms.loanTermMonths}-month loan matures before the " +
                        "${holdYears * 12}-month projection: the projection assumes refinancing at maturity " +
                        "on the documented terms."
                )
            )
        }

        val hold = ReturnMetricsCalculator.project(
            operating = base.operating,
            terms = base.terms,
            schedule = horizon.rows,
            purchasePrice = base.purchasePrice,
            holdYears = holdYears,
            rentGrowthPct = input.rentGrowthPct,
            expenseGrowthPct = input.expenseGrowthPct,
            appreciationPct = input.appreciationPct,
            saleCostPct = input.saleCostPct,
            totalCashRequired = base.core.totalCashRequired
        )

        val returns = ReturnMetrics(
            cashInvested = base.core.totalCashRequired,
            holdPeriodMonths = holdYears.toDouble() * 12.0,
            totalProfit = hold.totalProfit,
            roiPct = hold.roiPct,
            simpleAnnualizedRoiPct = hold.simpleAnnualizedRoiPct,
            annualizedRoiPct = hold.annualizedRoiPct,
            equityMultiple = hold.equityMultiple,
            irrAnnualPct = hold.irrAnnualPct
        )
        return StrategyOutcome(hold = hold, returns = returns, issues = issues)
    }

    // =======================================================================
    // BRRRR
    // =======================================================================

    /**
     * Buy (bridge) -> Rehab -> Rent -> Refinance -> Repeat.
     *
     * The refinance is modelled explicitly rather than hand-waved:
     *
     *  * phase 1 cash = down payment + buy-side closing + un-financed rehab +
     *    points/fees + bridge interest actually paid + carrying costs;
     *  * bridge interest is charged for the rehab window, floored by the
     *    lender's contractual minimum-interest months;
     *  * the payoff at refinance is the bridge PRINCIPAL only - the interest was
     *    already paid monthly and the points were already paid at closing, so
     *    adding them again would double-count them;
     *  * the new loan is sized off ARV, its costs are netted from the cash out,
     *    and whatever the investor cannot pull out stays at risk, which is what
     *    the post-refinance cash-on-cash return is measured against.
     */
    fun analyzeBrrrr(input: UnderwritingInput, base: AnalysisBase): BrrrrOutcome {
        val issues = ArrayList<ValidationIssue>()
        val arv = input.arv ?: 0.0
        if (arv <= 0.0) {
            return BrrrrOutcome(
                brrrr = null,
                returns = null,
                issues = listOf(
                    ValidationIssue(
                        "BRRRR_REQUIRES_ARV", IssueSeverity.ERROR,
                        "BRRRR requires an explicit ARV: the refinance is sized off it."
                    )
                )
            )
        }

        val acquisitionModel = input.brrrrAcquisitionModel ?: base.terms.model
        var terms = base.terms
        if (input.bridgeOverrides.isNotEmpty()) {
            val reResolved = FinancingResolver.resolve(acquisitionModel, base.purchasePrice, base.rehabCost, arv, input)
            terms = reResolved.terms
            issues.addAll(reResolved.issues)
        }

        var rehabMonths = input.brrrrRehabMonths ?: UnderwritingAssumptions.FLIP_HOLD_MONTHS
        if (rehabMonths <= 0) {
            issues.add(
                ValidationIssue(
                    "BRRRR_REHAB_MONTHS_DEFAULTED", IssueSeverity.WARNING,
                    "brrrrRehabMonths must be positive; used the documented default of " +
                        "${UnderwritingAssumptions.FLIP_HOLD_MONTHS}."
                )
            )
            rehabMonths = UnderwritingAssumptions.FLIP_HOLD_MONTHS
        }

        val holdingMonthly = input.holdingCostsMonthly
            ?: base.purchasePrice * UnderwritingAssumptions.HOLDING_COSTS_MONTHLY_PCT_OF_PRICE / 100.0
        val holdingCosts = holdingMonthly * rehabMonths

        val monthsCharged = maxOf(rehabMonths, terms.lenderMinimumInterestMonths)
        val bridgeInterest = if (terms.loanAmount > 0.0) terms.interestOnlyPayment * monthsCharged else 0.0

        val cashClosing = base.closingCosts
        val cashRehab = (base.rehabCost - terms.financedRehab).coerceAtLeast(0.0)
        val phase1Cash = (terms.downPaymentAmount + cashClosing + cashRehab +
            terms.newLoanFeesAndPoints + bridgeInterest + holdingCosts).coerceAtLeast(0.0)

        val bridgePayoff = terms.loanAmount

        // ---- refinance -----------------------------------------------------
        val refiLtvPct = input.refiLtvPctOfArv ?: UnderwritingAssumptions.BRRRR_TARGET_LTV_OF_ARV_PCT
        val refiLoan = arv * refiLtvPct / 100.0
        val refiRate = input.refiInterestRatePct ?: UnderwritingAssumptions.BRRRR_REFI_INTEREST_RATE_PCT
        val refiTerm = input.refiLoanTermMonths ?: UnderwritingAssumptions.BRRRR_REFI_TERM_MONTHS
        val refiAmortization = input.refiAmortizationMonths ?: UnderwritingAssumptions.BRRRR_REFI_TERM_MONTHS
        val refiPointsPct = input.refiOriginationPointsPct ?: UnderwritingAssumptions.BRRRR_REFI_POINTS_PCT
        val refiLenderFees = input.refiLenderFees ?: UnderwritingAssumptions.BRRRR_REFI_LENDER_FEES
        val refiClosingPct = input.refiClosingCostPct ?: UnderwritingAssumptions.BRRRR_REFI_CLOSING_COST_PCT
        val refiCosts = refiLoan * refiPointsPct / 100.0 + refiLenderFees + refiLoan * refiClosingPct / 100.0

        val cashOutAtRefi = refiLoan - bridgePayoff - refiCosts
        val cashLeftInDeal = phase1Cash - cashOutAtRefi
        val capitalRecoveredPct = if (phase1Cash > 0.0) cashOutAtRefi / phase1Cash * 100.0 else null
        val infiniteCashOnCash = cashLeftInDeal <= 0.0

        // The refinance is a new loan, not one of the financing models; the
        // SELLER_FINANCING label below is an internal placeholder that is never
        // reported (only the refinance summary is exposed on the result).
        val refiTerms = FinancingTerms(
            model = FinancingModel.SELLER_FINANCING,
            displayName = "Refinance",
            loanAmount = refiLoan,
            downPaymentAmount = 0.0,
            costBasis = arv,
            financedRehab = 0.0,
            financedClosingCosts = 0.0,
            interestRatePct = refiRate,
            loanTermMonths = refiTerm,
            amortizationMonths = refiAmortization,
            interestOnlyMonths = 0,
            originationPointsPct = refiPointsPct,
            lenderFees = refiLenderFees,
            lenderReserveMonths = 0,
            lenderMinimumInterestMonths = 0,
            minDscr = UnderwritingAssumptions.BRRRR_REFI_MIN_DSCR,
            maxLtvPct = refiLtvPct,
            maxLtcPct = null,
            maxLtvArvPct = null,
            requestedLoanAmount = refiLoan,
            bindingLoanCeiling = null,
            downPaymentBasis = DownPaymentBasis.PRICE,
            monthlyPayment = Amortization.amortizingPayment(refiLoan, refiRate, refiAmortization),
            interestOnlyPayment = Amortization.interestOnlyPayment(refiLoan, refiRate),
            newLoanFeesAndPoints = refiLoan * refiPointsPct / 100.0 + refiLenderFees,
            isInterestOnly = false,
            lenderProfitSharePct = 0.0
        )

        val refiSchedule = Amortization.schedule(
            principal = refiLoan,
            annualRatePct = refiRate,
            termMonths = refiTerm,
            amortizationMonths = refiAmortization,
            interestOnlyMonths = 0
        )

        val postBuild = EngineSupport.buildCapitalStack(
            input = input,
            purchasePrice = base.purchasePrice,
            rehabCost = base.rehabCost,
            closingCosts = cashClosing,
            terms = refiTerms,
            extraCash = holdingCosts + bridgeInterest + terms.newLoanFeesAndPoints
        )
        val postOperatingBuild = OperatingStatementCalculator.build(
            input = input,
            purchasePrice = base.purchasePrice,
            rehabCost = base.rehabCost,
            closingCosts = cashClosing,
            terms = refiTerms,
            schedule = refiSchedule
        )
        issues.addAll(postOperatingBuild.issues)

        val postCore = EngineSupport.buildCore(
            purchasePrice = base.purchasePrice,
            closingCosts = cashClosing,
            rehabCost = base.rehabCost,
            terms = refiTerms,
            operating = postOperatingBuild.statement,
            stack = postBuild.stack
        )

        val postCashBasis = if (cashLeftInDeal > 0.0) cashLeftInDeal else 0.0
        val postCoc = if (cashLeftInDeal > 0.0) {
            postOperatingBuild.statement.annualCashFlow / cashLeftInDeal * 100.0
        } else {
            null
        }

        if (postOperatingBuild.statement.dscr != null &&
            postOperatingBuild.statement.dscr < UnderwritingAssumptions.BRRRR_REFI_MIN_DSCR
        ) {
            issues.add(
                ValidationIssue(
                    "REFI_DSCR_SHORTFALL", IssueSeverity.WARNING,
                    "Post-refinance DSCR is below the " + UnderwritingAssumptions.BRRRR_REFI_MIN_DSCR +
                        "x typically required to refinance, and is outside the window a DSCR lender " +
                        "usually approves. Re-examine ARV, rent or the refinance LTV."
                )
            )
        }

        var holdYears = input.holdYears ?: UnderwritingAssumptions.HOLD_YEARS
        if (holdYears < 1) {
            issues.add(
                ValidationIssue(
                    "HOLD_YEARS_DEFAULTED", IssueSeverity.WARNING,
                    "holdYears must be at least 1; used the documented default of " +
                        "${UnderwritingAssumptions.HOLD_YEARS}."
                )
            )
            holdYears = UnderwritingAssumptions.HOLD_YEARS
        }

        val refiHorizon = Amortization.scheduleToHorizon(refiTerms, holdYears * 12)
        if (refiHorizon.refinanceAssumed) {
            issues.add(
                ValidationIssue(
                    "REFINANCE_ASSUMED_AT_MATURITY", IssueSeverity.WARNING,
                    "The refinance loan matures before the ${holdYears * 12}-month projection: the " +
                        "projection assumes refinancing at maturity on identical terms."
                )
            )
        }

        val postHold = ReturnMetricsCalculator.project(
            operating = postOperatingBuild.statement,
            terms = refiTerms,
            schedule = refiHorizon.rows,
            purchasePrice = base.purchasePrice,
            holdYears = holdYears,
            rentGrowthPct = input.rentGrowthPct,
            expenseGrowthPct = input.expenseGrowthPct,
            appreciationPct = input.appreciationPct,
            saleCostPct = input.saleCostPct,
            totalCashRequired = cashLeftInDeal,
            openingValue = arv
        )

        val returns = ReturnMetrics(
            cashInvested = phase1Cash,
            holdPeriodMonths = holdYears.toDouble() * 12.0,
            totalProfit = postHold.totalProfit,
            roiPct = postHold.roiPct,
            simpleAnnualizedRoiPct = postHold.simpleAnnualizedRoiPct,
            annualizedRoiPct = postHold.annualizedRoiPct,
            equityMultiple = postHold.equityMultiple,
            irrAnnualPct = postHold.irrAnnualPct
        )

        val brrrr = BrrrrAnalysis(
            arv = arv,
            acquisitionModel = terms.model,
            bridgeLoanAmount = terms.loanAmount,
            bridgeInterestPaid = bridgeInterest,
            bridgeLoanFeesAndPoints = terms.newLoanFeesAndPoints,
            bridgePayoffAmount = bridgePayoff,
            rehabMonths = rehabMonths,
            holdingCostsDuringRehab = holdingCosts,
            phase1CashInvested = phase1Cash,
            refinance = RefinanceSummary(
                loanAmount = refiLoan,
                ltvOfArvPct = refiLoan / arv * 100.0,
                interestRatePct = refiRate,
                loanTermMonths = refiTerm,
                amortizationMonths = refiAmortization,
                monthlyPayment = refiTerms.monthlyPayment,
                closingCostsAndPoints = refiCosts
            ),
            cashOutAtRefinance = cashOutAtRefi,
            cashLeftInDeal = cashLeftInDeal,
            capitalRecoveredPct = capitalRecoveredPct,
            infiniteCashOnCash = infiniteCashOnCash,
            postRefiCashOnCashPct = postCoc,
            postRefiMonthlyCashFlow = postOperatingBuild.statement.monthlyCashFlow,
            postRefiDscr = postOperatingBuild.statement.dscr,
            postRefiCapRateOnArvPct = postOperatingBuild.statement.netOperatingIncomeAnnual / arv * 100.0,
            equityCreatedAtRefi = arv - refiLoan,
            postRefiCore = postCore.copy(
                totalCashRequired = postCashBasis,
                cashOnCashPct = postCoc,
                infiniteCashOnCash = infiniteCashOnCash
            ),
            postRefiCapitalStack = postBuild.stack,
            postRefiOperating = postOperatingBuild.statement,
            postRefiHold = postHold
        )

        return BrrrrOutcome(brrrr = brrrr, returns = returns, issues = issues)
    }

    // =======================================================================
    // FIX_AND_FLIP
    // =======================================================================

    fun analyzeFixAndFlip(input: UnderwritingInput, base: AnalysisBase): FlipOutcome {
        val issues = ArrayList<ValidationIssue>()
        val arv = input.arv ?: 0.0
        if (arv <= 0.0) {
            return FlipOutcome(
                flip = null,
                returns = null,
                issues = listOf(
                    ValidationIssue(
                        "FLIP_REQUIRES_ARV", IssueSeverity.ERROR,
                        "FIX_AND_FLIP requires an explicit ARV: the exit is the whole thesis."
                    )
                )
            )
        }

        val contingencyPct = input.rehabContingencyPct ?: UnderwritingAssumptions.REHAB_CONTINGENCY_PCT
        val totalRehab = base.rehabCost * (1.0 + contingencyPct / 100.0)

        var holdMonths = input.flipHoldMonths ?: UnderwritingAssumptions.FLIP_HOLD_MONTHS
        if (holdMonths <= 0) {
            issues.add(
                ValidationIssue(
                    "FLIP_HOLD_MONTHS_DEFAULTED", IssueSeverity.WARNING,
                    "Hold months must be positive; used the documented default of " +
                        "${UnderwritingAssumptions.FLIP_HOLD_MONTHS}."
                )
            )
            holdMonths = UnderwritingAssumptions.FLIP_HOLD_MONTHS
        }

        val buyClosing = when {
            input.closingCosts != null -> input.closingCosts
            input.buyClosingCostPct != null -> base.purchasePrice * input.buyClosingCostPct / 100.0
            else -> base.purchasePrice * FinancingModelDefaultsRegistry.of(base.terms.model).closingCostPctOfPrice / 100.0
        }

        val holdingMonthly = input.holdingCostsMonthly
            ?: base.purchasePrice * UnderwritingAssumptions.HOLDING_COSTS_MONTHLY_PCT_OF_PRICE / 100.0
        val holdingCosts = holdingMonthly * holdMonths

        val chargedMonths = maxOf(holdMonths, base.terms.lenderMinimumInterestMonths)
        val interestPaid = if (base.terms.loanAmount > 0.0) {
            if (base.terms.amortizationMonths > 0 &&
                base.terms.interestOnlyMonths < chargedMonths
            ) {
                var total = 0.0
                val schedule = base.schedule
                for (month in 1..chargedMonths) {
                    total += if (month - 1 < schedule.size) {
                        schedule[month - 1].interest
                    } else {
                        base.terms.loanAmount * Amortization.periodicRate(base.terms.interestRatePct)
                    }
                }
                total
            } else {
                base.terms.interestOnlyPayment * chargedMonths
            }
        } else {
            0.0
        }

        val sellCostPct = input.sellClosingCostPct ?: UnderwritingAssumptions.FLIP_SELL_COST_PCT
        val concessions = input.sellerConcessions ?: 0.0

        val allInCost = base.purchasePrice + totalRehab + buyClosing + holdingCosts + interestPaid +
            base.terms.newLoanFeesAndPoints
        val sellingCosts = arv * sellCostPct / 100.0 + concessions
        val netSaleProceeds = arv - sellingCosts
        val profit = netSaleProceeds - allInCost

        var cashInvested = allInCost - base.terms.loanAmount
        if (cashInvested < 0.0) {
            issues.add(
                ValidationIssue(
                    "OVER_FINANCED_PROJECT", IssueSeverity.INFO,
                    "Loan principal exceeds the all-in project cost; cash invested was floored at zero."
                )
            )
            cashInvested = 0.0
        }

        val roiPct = if (cashInvested > 0.0) profit / cashInvested * 100.0 else null
        val annualized = ReturnMetricsCalculator.annualizeFromRoi(
            roiFraction = roiPct?.div(100.0),
            months = holdMonths.toDouble()
        )
        val simple = ReturnMetricsCalculator.simpleAnnualizeFromMonths(roiPct, holdMonths.toDouble())

        val profitMarginOnArv = profit / arv * 100.0
        val profitMarginOnCost = if (allInCost > 0.0) profit / allInCost * 100.0 else null

        val targetProfitPct = input.flipTargetProfitPctOfArv ?: UnderwritingAssumptions.FLIP_MIN_PROFIT_PCT_OF_ARV
        val targetProfit = arv * targetProfitPct / 100.0
        val maxOffer = maxOfferForTargetProfit(
            input = input,
            base = base,
            arv = arv,
            sellCostPct = sellCostPct,
            concessions = concessions,
            rehab = totalRehab,
            targetProfit = targetProfit,
            months = holdMonths
        )

        val maoSeventy = arv * 0.70 - totalRehab
        val breakEvenArv = if (sellCostPct < 100.0) (allInCost + concessions) / (1.0 - sellCostPct / 100.0) else null

        val returnMetrics = ReturnMetrics(
            cashInvested = cashInvested,
            holdPeriodMonths = holdMonths.toDouble(),
            totalProfit = profit,
            roiPct = roiPct,
            simpleAnnualizedRoiPct = simple,
            annualizedRoiPct = annualized?.times(100.0),
            equityMultiple = if (cashInvested > 0.0) (cashInvested + profit) / cashInvested else null,
            irrAnnualPct = annualized?.times(100.0)
        )

        val flip = FlipAnalysis(
            arv = arv,
            rehabBase = base.rehabCost,
            rehabContingencyPct = contingencyPct,
            totalRehabCost = totalRehab,
            holdMonths = holdMonths,
            buyClosingCosts = buyClosing,
            holdingCostsTotal = holdingCosts,
            holdingCostsMonthly = holdingMonthly,
            loanAmount = base.terms.loanAmount,
            loanInterestPaid = interestPaid,
            loanFeesAndPoints = base.terms.newLoanFeesAndPoints,
            lenderMinimumInterestMonths = base.terms.lenderMinimumInterestMonths,
            allInCost = allInCost,
            grossSalePrice = arv,
            sellingCosts = sellingCosts,
            netSaleProceeds = netSaleProceeds,
            profit = profit,
            profitMarginOnArvPct = profitMarginOnArv,
            profitMarginOnCostPct = profitMarginOnCost,
            returnMetrics = returnMetrics,
            maoSeventyRule = maoSeventy,
            maxOfferForTargetProfit = maxOffer,
            targetProfitPctOfArv = targetProfitPct,
            targetProfitAmount = targetProfit,
            breakEvenArv = breakEvenArv,
            breakEvenArvCushionPct = if (breakEvenArv != null && arv > 0.0) {
                (arv - breakEvenArv) / arv * 100.0
            } else {
                null
            }
        )
        return FlipOutcome(flip = flip, returns = returnMetrics, issues = issues)
    }

    /**
     * Highest purchase price that still clears the target profit.
     *
     * Profit is strictly decreasing in price, so a fixed-count bisection is both
     * exact enough (200 iterations) and deterministic. Returns null when even a
     * zero purchase price cannot reach the target.
     */
    private fun maxOfferForTargetProfit(
        input: UnderwritingInput,
        base: AnalysisBase,
        arv: Double,
        sellCostPct: Double,
        concessions: Double,
        rehab: Double,
        targetProfit: Double,
        months: Int
    ): Double? {
        fun profitAt(price: Double): Double {
            // the loan is sized off the CONTINGENT rehab budget, exactly as the
            // deal itself is, otherwise the solver and the deal disagree
            val terms = FinancingResolver.resolve(
                model = base.terms.model,
                purchasePrice = price,
                rehabCost = rehab,
                arv = arv,
                input = input
            ).terms
            val buyClosing = when {
                input.closingCosts != null -> input.closingCosts
                input.buyClosingCostPct != null -> price * input.buyClosingCostPct / 100.0
                else -> price * FinancingModelDefaultsRegistry.of(base.terms.model).closingCostPctOfPrice / 100.0
            }
            val charged = maxOf(months, terms.lenderMinimumInterestMonths)
            val interest = if (terms.loanAmount > 0.0) terms.interestOnlyPayment * charged else 0.0
            // A price-based default must move with the candidate offer. An explicit
            // monthly amount (including zero) remains fixed, as in analyzeFixAndFlip.
            val holdingMonthly = input.holdingCostsMonthly
                ?: price * UnderwritingAssumptions.HOLDING_COSTS_MONTHLY_PCT_OF_PRICE / 100.0
            val holding = holdingMonthly * months
            val allIn = price + rehab + buyClosing + holding + interest + terms.newLoanFeesAndPoints
            return arv * (1.0 - sellCostPct / 100.0) - concessions - allIn
        }

        var low = 0.0
        var high = arv * 2.0
        if (profitAt(low) < targetProfit) return null
        if (profitAt(high) > targetProfit) return high
        for (step in 0 until 200) {
            val mid = (low + high) / 2.0
            if (profitAt(mid) > targetProfit) low = mid else high = mid
        }
        return (low + high) / 2.0
    }

    // =======================================================================
    // WHOLESALE
    // =======================================================================

    fun analyzeWholesale(input: UnderwritingInput, base: AnalysisBase): WholesaleOutcome {
        val issues = ArrayList<ValidationIssue>()
        val contractPrice = base.purchasePrice
        val arv = input.arv ?: contractPrice * 1.25

        val mode = input.wholesaleMode ?: WholesaleMode.ASSIGNMENT

        val assignmentFee: Double
        val feeSource: String
        if (input.assignmentFee != null) {
            assignmentFee = input.assignmentFee
            feeSource = "EXPLICIT"
        } else {
            val pct = input.assignmentFeePctOfArv ?: UnderwritingAssumptions.WHOLESALE_ASSIGNMENT_FEE_PCT_OF_ARV
            assignmentFee = arv * pct / 100.0
            feeSource = "DEFAULT_PCT_OF_ARV"
        }

        val earnest = input.earnestMoney ?: 0.0
        val marketing = input.marketingCost ?: UnderwritingAssumptions.WHOLESALER_MARKETING_COST

        var days = input.daysToClose ?: UnderwritingAssumptions.DAYS_TO_CLOSE_WHOLESALE
        if (days <= 0) {
            issues.add(
                ValidationIssue(
                    "DAYS_TO_CLOSE_DEFAULTED", IssueSeverity.WARNING,
                    "daysToClose must be positive; used the documented default of " +
                        "${UnderwritingAssumptions.DAYS_TO_CLOSE_WHOLESALE}."
                )
            )
            days = UnderwritingAssumptions.DAYS_TO_CLOSE_WHOLESALE
        }

        val buyerRehab = input.buyerRehabEstimate ?: 0.0
        val buyerSellCostPct = input.buyerSellCostPct ?: UnderwritingAssumptions.SALE_COST_PCT
        val buyerMinProfitPct =
            input.buyerMinProfitPctOfArv ?: UnderwritingAssumptions.WHOLESALE_BUYER_MIN_PROFIT_PCT_OF_ARV

        var buySideClosing = 0.0
        var sellSideClosing = 0.0
        if (mode == WholesaleMode.DOUBLE_CLOSE) {
            val buyPct = input.buyClosingCostPct ?: 1.0
            val sellPct = input.sellClosingCostPct ?: 1.0
            buySideClosing = contractPrice * buyPct / 100.0
            sellSideClosing = (contractPrice + assignmentFee) * sellPct / 100.0
        }

        val grossRevenue = assignmentFee
        val totalCosts = marketing + buySideClosing + sellSideClosing
        val profit = grossRevenue - totalCosts
        val cashInvested = earnest + marketing
        val roiPct = if (cashInvested > 0.0) profit / cashInvested * 100.0 else null
        val annualized = ReturnMetricsCalculator.annualizeFromRoi(roiPct?.div(100.0), days / 30.0)
        val simple = ReturnMetricsCalculator.simpleAnnualizeFromDays(roiPct, days.toDouble())

        val buyerAllIn = contractPrice + assignmentFee + buyerRehab
        val buyerProfit = arv * (1.0 - buyerSellCostPct / 100.0) - buyerAllIn
        val buyerProfitPct = if (arv > 0.0) buyerProfit / arv * 100.0 else null
        val maxAssignableFee = arv * (1.0 - buyerSellCostPct / 100.0) - buyerRehab - contractPrice -
            arv * buyerMinProfitPct / 100.0

        if (buyerProfitPct != null && buyerProfitPct < buyerMinProfitPct) {
            issues.add(
                ValidationIssue(
                    "ASSIGNMENT_FEE_MAY_BE_REJECTED", IssueSeverity.WARNING,
                    "The end buyer's projected margin is below the assumption of " + buyerMinProfitPct +
                        "% of ARV, so this assignment fee may not survive a re-trade or a buyer's walk-through."
                )
            )
        }
        val maoSeventy = arv * 0.70 - buyerRehab
        if (contractPrice > maoSeventy) {
            issues.add(
                ValidationIssue(
                    "CONTRACT_ABOVE_70_PCT_RULE", IssueSeverity.WARNING,
                    "The contract price is above ARV x 70% less repairs, the classic wholesale spread " +
                        "test your buyer list will apply."
                )
            )
        }

        val returns = ReturnMetrics(
            cashInvested = cashInvested,
            holdPeriodMonths = days / 30.0,
            totalProfit = profit,
            roiPct = roiPct,
            simpleAnnualizedRoiPct = simple,
            annualizedRoiPct = annualized?.times(100.0),
            equityMultiple = if (cashInvested > 0.0) (cashInvested + profit) / cashInvested else null,
            irrAnnualPct = annualized?.times(100.0)
        )

        val wholesale = WholesaleAnalysis(
            mode = mode,
            contractPrice = contractPrice,
            arv = arv,
            assignmentFee = assignmentFee,
            assignmentFeeSource = feeSource,
            earnestMoney = earnest,
            marketingCost = marketing,
            buySideClosingCosts = buySideClosing,
            sellSideClosingCosts = sellSideClosing,
            grossRevenue = grossRevenue,
            totalCosts = totalCosts,
            profit = profit,
            daysToClose = days,
            buyerAllInCost = buyerAllIn,
            buyerProfit = buyerProfit,
            buyerProfitPctOfArv = buyerProfitPct,
            maxAssignableFee = maxAssignableFee,
            maoSeventyRule = maoSeventy,
            returnMetrics = returns
        )
        return WholesaleOutcome(wholesale = wholesale, returns = returns, issues = issues)
    }
}

/** Everything the analyzers need that the engine has already computed. */
data class AnalysisBase(
    val purchasePrice: Double,
    val rehabCost: Double,
    val closingCosts: Double,
    val terms: FinancingTerms,
    val schedule: List<AmortizationRow>,
    val operating: OperatingStatementResult,
    val core: CoreMetrics,
    val capitalStack: CapitalStack
)

data class StrategyOutcome(
    val hold: HoldAnalysis? = null,
    val brrrr: BrrrrAnalysis? = null,
    val flip: FlipAnalysis? = null,
    val wholesale: WholesaleAnalysis? = null,
    val returns: ReturnMetrics?,
    val issues: List<ValidationIssue>
)

data class BrrrrOutcome(
    val brrrr: BrrrrAnalysis?,
    val returns: ReturnMetrics?,
    val issues: List<ValidationIssue>
)

data class FlipOutcome(
    val flip: FlipAnalysis?,
    val returns: ReturnMetrics?,
    val issues: List<ValidationIssue>
)

data class WholesaleOutcome(
    val wholesale: WholesaleAnalysis?,
    val returns: ReturnMetrics?,
    val issues: List<ValidationIssue>
)
