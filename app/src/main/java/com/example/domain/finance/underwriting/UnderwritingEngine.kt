package com.example.domain.finance.underwriting

import kotlin.math.abs

/**
 * The deterministic underwriting engine.
 *
 * `UnderwritingEngine.analyze(input)` is the single entry point used by the app,
 * the Android unit tests and the Python oracle's golden vectors. It performs no
 * I/O, reads no clock, uses no randomness and never calls a language model: the
 * same input produces the same result down to the last floating-point digit.
 *
 * Everything it computes is either
 *   * supplied by the caller,
 *   * a named, overridable assumption (see [UnderwritingAssumptions]), or
 *   * derived by one of the formulas documented on the functions below.
 */
object UnderwritingEngine {

    /**
     * @param input       the deal
     * @param decodeIssues problems found while decoding persisted/serialised
     *                     input (for example an unknown financing model name).
     *                     They are reported with the engine's own findings so a
     *                     caller can never lose them.
     */
    fun analyze(
        input: UnderwritingInput,
        decodeIssues: List<ValidationIssue> = emptyList()
    ): UnderwritingResult {
        val issues = ArrayList<ValidationIssue>()
        issues.addAll(decodeIssues)

        // ---- 1. refuse to compute on non-finite numbers --------------------
        val sanitized = EngineSupport.sanitize(input)
        issues.addAll(sanitized.issues)
        val deal = sanitized.input

        val strategy = deal.strategy
        val purchasePrice = deal.purchasePrice
        if (purchasePrice <= 0.0) {
            issues.add(
                ValidationIssue(
                    "NON_POSITIVE_PURCHASE_PRICE", IssueSeverity.ERROR,
                    "Purchase price must be greater than zero; nothing meaningful can be computed without it."
                )
            )
        }
        var rehabCost = deal.rehabCost
        if (rehabCost < 0.0) {
            issues.add(
                ValidationIssue(
                    "NEGATIVE_REHAB_COST", IssueSeverity.ERROR,
                    "Rehab cost cannot be negative; treated as zero."
                )
            )
            rehabCost = 0.0
        }
        if (deal.monthlyRent < 0.0) {
            issues.add(
                ValidationIssue(
                    "NEGATIVE_RENT", IssueSeverity.ERROR,
                    "Monthly rent cannot be negative; treated as zero."
                )
            )
        }
        val monthlyRent = if (deal.monthlyRent < 0.0) 0.0 else deal.monthlyRent
        val workingInput = deal.copy(rehabCost = rehabCost, monthlyRent = monthlyRent)

        // ---- 2. financing ---------------------------------------------------
        val model = workingInput.financingModel ?: EngineSupport.defaultModelFor(strategy)
        val financing = FinancingResolver.resolve(
            model = model,
            purchasePrice = purchasePrice,
            rehabCost = rehabCost,
            arv = workingInput.arv,
            input = workingInput
        )
        issues.addAll(financing.issues)
        val terms = financing.terms

        val closingCosts = workingInput.closingCosts
            ?: purchasePrice * FinancingModelDefaultsRegistry.of(model).closingCostPctOfPrice / 100.0

        val schedule = Amortization.schedule(
            principal = terms.loanAmount,
            annualRatePct = terms.interestRatePct,
            termMonths = terms.loanTermMonths,
            amortizationMonths = terms.amortizationMonths,
            interestOnlyMonths = terms.interestOnlyMonths
        )

        // ---- 3. income statement, capital stack, core metrics ---------------
        val operatingBuild = OperatingStatementCalculator.build(
            input = workingInput,
            purchasePrice = purchasePrice,
            rehabCost = rehabCost,
            closingCosts = closingCosts,
            terms = terms,
            schedule = schedule
        )
        issues.addAll(operatingBuild.issues)
        val operating = operatingBuild.statement

        val stackBuild = EngineSupport.buildCapitalStack(
            input = workingInput,
            purchasePrice = purchasePrice,
            rehabCost = rehabCost,
            closingCosts = closingCosts,
            terms = terms
        )
        issues.addAll(stackBuild.issues)

        val core = EngineSupport.buildCore(
            purchasePrice = purchasePrice,
            closingCosts = closingCosts,
            rehabCost = rehabCost,
            terms = terms,
            operating = operating,
            stack = stackBuild.stack
        )

        // ---- 4. strategy ----------------------------------------------------
        val base = AnalysisBase(
            purchasePrice = purchasePrice,
            rehabCost = rehabCost,
            closingCosts = closingCosts,
            terms = terms,
            schedule = schedule,
            operating = operating,
            core = core,
            capitalStack = stackBuild.stack
        )

        var hold: HoldAnalysis? = null
        var brrrr: BrrrrAnalysis? = null
        var flip: FlipAnalysis? = null
        var wholesale: WholesaleAnalysis? = null
        var returns: ReturnMetrics? = null
        var verdictCore = core

        when (strategy) {
            InvestmentStrategy.BUY_AND_HOLD -> {
                val outcome = StrategyAnalyzers.analyzeBuyAndHold(workingInput, base)
                hold = outcome.hold
                returns = outcome.returns
                issues.addAll(outcome.issues)
            }

            InvestmentStrategy.BRRRR -> {
                val outcome = StrategyAnalyzers.analyzeBrrrr(workingInput, base)
                brrrr = outcome.brrrr
                returns = outcome.returns
                issues.addAll(outcome.issues)
                val post = outcome.brrrr?.postRefiCore
                if (post != null) {
                    // the deal that is actually kept is the post-refinance deal,
                    // so that is what the verdict is judged on
                    verdictCore = core.copy(
                        loanAmount = post.loanAmount,
                        totalCashRequired = post.totalCashRequired,
                        monthlyCashFlow = post.monthlyCashFlow,
                        annualCashFlow = post.annualCashFlow,
                        dscr = post.dscr,
                        cashOnCashPct = post.cashOnCashPct,
                        capRateOnPricePct = post.capRateOnPricePct
                    )
                }
            }

            InvestmentStrategy.FIX_AND_FLIP -> {
                val outcome = StrategyAnalyzers.analyzeFixAndFlip(workingInput, base)
                flip = outcome.flip
                returns = outcome.returns
                issues.addAll(outcome.issues)
            }

            InvestmentStrategy.WHOLESALE -> {
                val outcome = StrategyAnalyzers.analyzeWholesale(workingInput, base)
                wholesale = outcome.wholesale
                returns = outcome.returns
                issues.addAll(outcome.issues)
            }
        }

        val verdict = EngineSupport.qualify(
            input = workingInput,
            strategy = strategy,
            core = verdictCore,
            flip = flip,
            wholesale = wholesale,
            brrrr = brrrr
        )

        return UnderwritingResult(
            specVersion = UnderwritingAssumptions.SPEC_VERSION,
            strategy = strategy,
            financingModel = model,
            purchasePrice = purchasePrice,
            rehabCost = rehabCost,
            financing = FinancingSummary(
                model = terms.model,
                displayName = terms.displayName,
                loanAmount = terms.loanAmount,
                downPaymentAmount = terms.downPaymentAmount,
                downPaymentPctOfPrice = if (purchasePrice > 0.0) {
                    terms.downPaymentAmount / purchasePrice * 100.0
                } else {
                    null
                },
                interestRatePct = terms.interestRatePct,
                loanTermMonths = terms.loanTermMonths,
                amortizationMonths = terms.amortizationMonths,
                interestOnlyMonths = terms.interestOnlyMonths,
                monthlyPayment = terms.monthlyPayment,
                newLoanFeesAndPoints = terms.newLoanFeesAndPoints,
                originationPointsPct = terms.originationPointsPct,
                lenderFees = terms.lenderFees,
                lenderReserveMonths = terms.lenderReserveMonths,
                isInterestOnly = terms.isInterestOnly,
                requestedLoanAmount = terms.requestedLoanAmount,
                loanCappedByLender = terms.bindingLoanCeiling != null,
                bindingLoanCeiling = terms.bindingLoanCeiling,
                maxLtvPct = terms.maxLtvPct,
                maxLtcPct = terms.maxLtcPct,
                downPaymentBasis = terms.downPaymentBasis
            ),
            operating = operating,
            core = core,
            capitalStack = stackBuild.stack,
            hold = hold,
            brrrr = brrrr,
            flip = flip,
            wholesale = wholesale,
            returns = returns,
            verdict = verdict,
            validation = issues,
            assumptionsUsed = EngineSupport.assumptionRecords(financing.provenance),
            metricApplicability = EngineSupport.applicabilityFor(strategy)
        )
    }

    /**
     * The same property under all five financing models, so a user can see what
     * the capital structure does to the deal rather than what the deal does to
     * the capital structure. Deterministic and side-effect free.
     */
    fun compareFinancingModels(
        input: UnderwritingInput,
        models: List<FinancingModel> = FinancingModel.entries.toList()
    ): List<FinancingComparisonRow> = models.map { model ->
        val result = analyze(input.copy(financingModel = model))
        FinancingComparisonRow(
            model = model,
            displayName = result.financing.displayName,
            loanAmount = result.financing.loanAmount,
            downPaymentAmount = result.financing.downPaymentAmount,
            monthlyPayment = result.financing.monthlyPayment,
            totalCashRequired = result.core.totalCashRequired,
            monthlyCashFlow = result.core.monthlyCashFlow,
            dscr = result.core.dscr,
            cashOnCashPct = result.core.cashOnCashPct,
            bindingLoanCeiling = result.financing.bindingLoanCeiling,
            ltvOfPricePct = result.core.ltvOfPricePct
        )
    }

    /** The same deal run as all four strategies. */
    fun compareStrategies(
        input: UnderwritingInput,
        strategies: List<InvestmentStrategy> = InvestmentStrategy.entries.toList()
    ): List<StrategyComparisonRow> = strategies.map { strategy ->
        val result = analyze(input.copy(strategy = strategy))
        val metric: Double?
        val label: String
        when (strategy) {
            InvestmentStrategy.BUY_AND_HOLD, InvestmentStrategy.BRRRR -> {
                label = "Monthly cash flow"
                metric = result.core.monthlyCashFlow
            }

            InvestmentStrategy.FIX_AND_FLIP -> {
                label = "Project profit"
                metric = result.flip?.profit
            }

            InvestmentStrategy.WHOLESALE -> {
                label = "Assignment profit"
                metric = result.wholesale?.profit
            }
        }
        StrategyComparisonRow(
            strategy = strategy,
            rating = result.verdict.rating,
            metricLabel = label,
            metricValue = metric,
            monthlyCashFlow = if (strategy == InvestmentStrategy.FIX_AND_FLIP ||
                strategy == InvestmentStrategy.WHOLESALE
            ) {
                null
            } else {
                result.core.monthlyCashFlow
            },
            dscr = result.core.dscr,
            cashOnCashPct = result.core.cashOnCashPct,
            totalCashRequired = result.core.totalCashRequired,
            summary = Summaries.describe(result)
        )
    }
}

/**
 * Deterministic English for the headline numbers.
 *
 * This exists so the app never has to ask a language model to phrase a result:
 * the prose is a template over the deterministic numbers, and it cannot invent a
 * figure that is not already in the [UnderwritingResult].
 */
object Summaries {

    fun describe(result: UnderwritingResult): String = when (result.strategy) {
        InvestmentStrategy.BUY_AND_HOLD -> describeBuyAndHold(result)
        InvestmentStrategy.BRRRR -> describeBrrrr(result)
        InvestmentStrategy.FIX_AND_FLIP -> describeFlip(result)
        InvestmentStrategy.WHOLESALE -> describeWholesale(result)
    }

    private fun describeBuyAndHold(result: UnderwritingResult): String {
        val downPct = result.financing.downPaymentPctOfPrice ?: 0.0
        val cashFlow = result.core.monthlyCashFlow
        val direction = if (cashFlow >= 0.0) "positive" else "negative"
        val dscrText = result.core.dscr?.let { "DSCR " + format(it) + "x" }
            ?: "no debt, so DSCR does not apply"
        return "Buy and hold at ${money(result.purchasePrice)} with ${pct(downPct)} down: " +
            "${money(result.core.totalCashRequired)} required at closing, " +
            "${money(abs(cashFlow))} per month $direction cash flow, " +
            "cap rate ${pct(result.core.capRateOnPricePct ?: 0.0)}, " +
            "cash-on-cash ${pct(result.core.cashOnCashPct ?: 0.0)}, $dscrText. " +
            "Outcome: ${rating(result)}."
    }

    private fun describeBrrrr(result: UnderwritingResult): String {
        val block = result.brrrr
            ?: return "BRRRR cannot be evaluated: " +
                (result.validation.firstOrNull()?.code ?: "missing inputs") + "."
        val recovery = if (block.infiniteCashOnCash) {
            "all of the ${money(block.phase1CashInvested)} invested came back at the refinance"
        } else {
            money(block.cashLeftInDeal) + " stays in the deal, " +
                pct(block.capitalRecoveredPct ?: 0.0) + " of the capital recovered"
        }
        val dscrText = block.postRefiDscr?.let { format(it) + "x" } ?: "not applicable"
        return "BRRRR with ARV ${money(block.arv)}: refinance at ${pct(block.refinance.ltvOfArvPct)} of ARV, " +
            "$recovery. Post-refinance cash flow ${money(block.postRefiMonthlyCashFlow)} per month, " +
            "DSCR $dscrText. Outcome: ${rating(result)}."
    }

    private fun describeFlip(result: UnderwritingResult): String {
        val block = result.flip
            ?: return "Fix and flip cannot be evaluated: " +
                (result.validation.firstOrNull()?.code ?: "missing inputs") + "."
        val offer = block.maxOfferForTargetProfit?.let { money(it) } ?: "not reachable"
        return "Fix and flip: all-in cost ${money(block.allInCost)}, net sale proceeds " +
            "${money(block.netSaleProceeds)}, profit ${money(block.profit)} " +
            "(${pct(block.profitMarginOnArvPct)} of ARV) on ${money(block.returnMetrics.cashInvested)} of cash. " +
            "Highest offer that still clears the target profit: $offer. Outcome: ${rating(result)}."
    }

    private fun describeWholesale(result: UnderwritingResult): String {
        val block = result.wholesale
            ?: return "Wholesale cannot be evaluated."
        val buyerMargin = block.buyerProfitPctOfArv?.let { pct(it) } ?: "unknown"
        val mode = block.mode.name.lowercase().replace('_', ' ')
        return "Wholesale ($mode): assignment fee ${money(block.assignmentFee)} against " +
            "${money(block.returnMetrics.cashInvested)} of cash at risk, profit ${money(block.profit)} " +
            "over ${block.daysToClose} days. End buyer's margin: $buyerMargin of ARV. Outcome: ${rating(result)}."
    }

    private fun rating(result: UnderwritingResult): String =
        result.verdict.rating.name.lowercase().replace('_', ' ')

    private fun money(value: Double): String = "$" + format(value)

    private fun pct(value: Double): String = format(value) + "%"

    private fun format(value: Double): String {
        val rounded = Math.round(value * 100.0).toDouble() / 100.0
        return rounded.toString()
    }
}
