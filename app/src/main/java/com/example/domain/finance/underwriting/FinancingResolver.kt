package com.example.domain.finance.underwriting

import kotlin.math.abs

/**
 * Turns "the borrower wants a DSCR loan on this house" into an exact loan.
 *
 * Sizing precedence (documented in [UnderwritingInput]):
 *   explicit loan amount > explicit down payment > down payment percentage >
 *   financing-model default.
 *
 * After the requested amount is known, the lender's ceilings are applied - the
 * tightest of loan-to-price, loan-to-cost and loan-to-ARV wins, but only the
 * ceilings the chosen model actually declares. Conventional and DSCR products
 * are LTV-driven; hard and private money are LTC-driven with an ARV backstop;
 * seller carry is LTV-driven with a balloon.
 *
 * A down payment is always measured against an explicit basis: a share of PRICE
 * for LTV products and a share of COST (purchase + financed rehab) for LTC
 * products. That is why 15% down on a hard-money deal is not the same number as
 * 15% down on a conventional one, and why the basis is reported in the result.
 */
object FinancingResolver {

    fun resolve(
        model: FinancingModel,
        purchasePrice: Double,
        rehabCost: Double,
        arv: Double?,
        input: UnderwritingInput
    ): FinancingResolution {
        val defaults = FinancingModelDefaultsRegistry.of(model)
        val issues = ArrayList<ValidationIssue>()
        val provenance = LinkedHashMap<String, ValueSource>()

        val overrides: Map<String, Double> = buildMap {
            putAll(input.loanOverrides)
            input.downPaymentPct?.let { put("downPaymentPct", it) }
            input.downPaymentAmount?.let { put("downPaymentAmount", it) }
            input.loanAmount?.let { put("loanAmount", it) }
            input.interestRatePct?.let { put("interestRatePct", it) }
            input.loanTermMonths?.let { put("loanTermMonths", it.toDouble()) }
            input.amortizationMonths?.let { put("amortizationMonths", it.toDouble()) }
            input.interestOnlyMonths?.let { put("interestOnlyMonths", it.toDouble()) }
            input.originationPointsPct?.let { put("originationPointsPct", it) }
            input.lenderFees?.let { put("lenderFees", it) }
            input.lenderReserveMonths?.let { put("lenderReserveMonths", it.toDouble()) }
            input.maxLtvPct?.let { put("maxLtvPct", it) }
            input.maxLtcPct?.let { put("maxLtcPct", it) }
            input.maxLtvArvPct?.let { put("maxLtvArvPct", it) }
        }

        fun isExplicit(key: String): Boolean = overrides[key] != null

        fun number(key: String, fallback: Double): Pair<Double, ValueSource> {
            val explicit = overrides[key]
            return if (explicit != null) explicit to ValueSource.EXPLICIT else fallback to ValueSource.MODEL_DEFAULT
        }

        // ---- capitalised cost base ----------------------------------------
        val financesRehab = isExplicit("financesRehab") || defaults.financesRehab
        val financesClosingCosts = isExplicit("financesClosingCosts") || defaults.financesClosingCosts
        provenance["financesRehab"] =
            if (isExplicit("financesRehab")) ValueSource.EXPLICIT else ValueSource.MODEL_DEFAULT
        provenance["financesClosingCosts"] =
            if (isExplicit("financesClosingCosts")) ValueSource.EXPLICIT else ValueSource.MODEL_DEFAULT

        val financedRehab = if (financesRehab) rehabCost else 0.0
        val financedClosingRatio = if (financesClosingCosts) {
            number("closingCostPctOfPrice", defaults.closingCostPctOfPrice).first
        } else {
            0.0
        }
        val financedClosingCosts = purchasePrice * financedClosingRatio / 100.0
        val costBasis = purchasePrice + financedRehab + financedClosingCosts
        provenance["costBasis"] = ValueSource.DERIVED

        // ---- requested loan ------------------------------------------------
        val explicitLoan = overrides["loanAmount"]
        val explicitDownAmount = overrides["downPaymentAmount"]
        val explicitDownPct = overrides["downPaymentPct"]

        var requestedLoan: Double
        var downPaymentSource: ValueSource

        if (explicitLoan != null) {
            requestedLoan = explicitLoan
            provenance["loanAmount"] = ValueSource.EXPLICIT
            val derivedDown = costBasis - explicitLoan
            downPaymentSource = ValueSource.DERIVED
            if (explicitDownAmount != null && abs(explicitDownAmount - derivedDown) > CONSISTENCY_TOLERANCE) {
                issues.add(
                    ValidationIssue(
                        code = "LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT",
                        severity = IssueSeverity.WARNING,
                        message = "Explicit loanAmount and downPaymentAmount disagree by more than " +
                            "one cent; loanAmount takes precedence."
                    )
                )
            }
            if (explicitDownPct != null && purchasePrice > 0.0) {
                val impliedPct = derivedDown / purchasePrice * 100.0
                if (abs(impliedPct - explicitDownPct) > CONSISTENCY_TOLERANCE) {
                    issues.add(
                        ValidationIssue(
                            code = "LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT",
                            severity = IssueSeverity.WARNING,
                            message = "Explicit loanAmount implies a down payment of " +
                                "${round2(impliedPct)}% of price but downPaymentPct was ${round2(explicitDownPct)}%; " +
                                "loanAmount takes precedence."
                        )
                    )
                }
            }
        } else if (explicitDownAmount != null) {
            requestedLoan = costBasis - explicitDownAmount
            downPaymentSource = ValueSource.EXPLICIT
            provenance["loanAmount"] = ValueSource.DERIVED
        } else {
            val downPct: Double
            if (explicitDownPct != null) {
                downPct = explicitDownPct
                provenance["downPaymentPct"] = ValueSource.EXPLICIT
            } else {
                downPct = defaults.downPaymentPct
                provenance["downPaymentPct"] = ValueSource.MODEL_DEFAULT
            }
            val basis = input.downPaymentBasis ?: defaults.downPaymentBasis
            provenance["downPaymentBasis"] =
                if (input.downPaymentBasis != null) ValueSource.EXPLICIT else ValueSource.MODEL_DEFAULT
            val basisAmount = if (basis == DownPaymentBasis.PRICE) purchasePrice else costBasis
            requestedLoan = costBasis - basisAmount * downPct / 100.0
            downPaymentSource = ValueSource.DERIVED
            provenance["loanAmount"] = ValueSource.DERIVED
        }

        // ---- lender ceilings -----------------------------------------------
        val ceilings = ArrayList<Pair<LoanCeiling, Double>>()
        val maxLtvPct: Double?
        if (isExplicit("maxLtvPct") || defaults.maxLtvPct != null) {
            val resolved = number("maxLtvPct", defaults.maxLtvPct ?: 0.0)
            provenance["maxLtvPct"] = resolved.second
            maxLtvPct = resolved.first
            ceilings.add(LoanCeiling.MAX_LTV_OF_PRICE to purchasePrice * resolved.first / 100.0)
        } else {
            maxLtvPct = null
        }

        val maxLtcPct: Double?
        if (isExplicit("maxLtcPct") || defaults.maxLtcPct != null) {
            val resolved = number("maxLtcPct", defaults.maxLtcPct ?: 0.0)
            provenance["maxLtcPct"] = resolved.second
            maxLtcPct = resolved.first
            ceilings.add(LoanCeiling.MAX_LTC_OF_COST to costBasis * resolved.first / 100.0)
        } else {
            maxLtcPct = null
        }

        val maxLtvArvPct: Double?
        if ((isExplicit("maxLtvArvPct") || defaults.maxLtvArvPct != null) && arv != null && arv > 0.0) {
            val resolved = number("maxLtvArvPct", defaults.maxLtvArvPct ?: 0.0)
            provenance["maxLtvArvPct"] = resolved.second
            maxLtvArvPct = resolved.first
            ceilings.add(LoanCeiling.MAX_LTV_OF_ARV to arv * resolved.first / 100.0)
        } else {
            maxLtvArvPct = null
        }

        var loanAmount = requestedLoan
        var bindingCeiling: LoanCeiling? = null
        for ((ceiling, limit) in ceilings) {
            if (limit < loanAmount) {
                loanAmount = limit
                bindingCeiling = ceiling
            }
        }
        if (bindingCeiling != null) {
            issues.add(
                ValidationIssue(
                    code = "LOAN_CAPPED_BY_" + bindingCeiling.name,
                    severity = IssueSeverity.INFO,
                    message = "Requested loan of ${round2(requestedLoan)} was capped by the lender ceiling " +
                        "${bindingCeiling.name}."
                )
            )
        }
        if (loanAmount < 0.0) {
            issues.add(
                ValidationIssue(
                    code = "NEGATIVE_LOAN_AMOUNT",
                    severity = IssueSeverity.ERROR,
                    message = "Resolved loan amount is negative; floored at zero. Check the down payment."
                )
            )
            loanAmount = 0.0
        }

        val downPayment = costBasis - loanAmount

        // ---- rate, term, fees ---------------------------------------------
        val rate = number("interestRatePct", defaults.interestRatePct)
        provenance["interestRatePct"] = rate.second
        val termMonths = number("loanTermMonths", defaults.loanTermMonths.toDouble())
        provenance["loanTermMonths"] = termMonths.second
        val amortization = number("amortizationMonths", defaults.amortizationMonths.toDouble())
        provenance["amortizationMonths"] = amortization.second
        val ioMonths = number("interestOnlyMonths", defaults.interestOnlyMonths.toDouble())
        provenance["interestOnlyMonths"] = ioMonths.second
        val pointsPct = number("originationPointsPct", defaults.originationPointsPct)
        provenance["originationPointsPct"] = pointsPct.second
        val lenderFees = number("lenderFees", defaults.lenderFees)
        provenance["lenderFees"] = lenderFees.second
        val reserveMonths = number("lenderReserveMonths", defaults.lenderReserveMonths.toDouble())
        provenance["lenderReserveMonths"] = reserveMonths.second
        provenance["lenderMinimumInterestMonths"] =
            if (isExplicit("lenderMinimumInterestMonths")) ValueSource.EXPLICIT else ValueSource.MODEL_DEFAULT
        // only models that declare a profit share record one (private money)
        defaults.lenderProfitSharePct?.let {
            provenance["lenderProfitSharePct"] =
                if (isExplicit("lenderProfitSharePct")) ValueSource.EXPLICIT else ValueSource.MODEL_DEFAULT
        }

        var resolvedTerm = termMonths.first.toInt()
        if (resolvedTerm < 0) resolvedTerm = 0
        var resolvedAmortization = amortization.first.toInt()
        if (resolvedAmortization < 0) resolvedAmortization = 0
        var resolvedIo = ioMonths.first.toInt()
        if (resolvedIo < 0) resolvedIo = 0
        if (resolvedIo > resolvedTerm) {
            issues.add(
                ValidationIssue(
                    code = "IO_PERIOD_EXCEEDS_TERM",
                    severity = IssueSeverity.WARNING,
                    message = "Interest-only period ($resolvedIo months) exceeds the loan term " +
                        "($resolvedTerm months) and was clamped to the term."
                )
            )
            resolvedIo = resolvedTerm
        }
        val isInterestOnly = resolvedAmortization <= 0 || resolvedIo >= resolvedTerm
        if (!isInterestOnly && resolvedAmortization <= resolvedIo) {
            issues.add(
                ValidationIssue(
                    code = "IO_PERIOD_CONSUMES_AMORTIZATION",
                    severity = IssueSeverity.WARNING,
                    message = "The amortisation period does not exceed the interest-only period; " +
                        "the loan behaves as interest only within its term."
                )
            )
        }

        val monthlyPayment = if (isInterestOnly) {
            Amortization.interestOnlyPayment(loanAmount, rate.first)
        } else {
            Amortization.amortizingPayment(loanAmount, rate.first, resolvedAmortization)
        }

        provenance["downPaymentAmount"] = downPaymentSource

        val terms = FinancingTerms(
            model = model,
            displayName = defaults.displayName,
            loanAmount = loanAmount,
            downPaymentAmount = downPayment,
            costBasis = costBasis,
            financedRehab = financedRehab,
            financedClosingCosts = financedClosingCosts,
            interestRatePct = rate.first,
            loanTermMonths = resolvedTerm,
            amortizationMonths = resolvedAmortization,
            interestOnlyMonths = resolvedIo,
            originationPointsPct = pointsPct.first,
            lenderFees = lenderFees.first,
            lenderReserveMonths = reserveMonths.first.toInt().coerceAtLeast(0),
            lenderMinimumInterestMonths = defaults.lenderMinimumInterestMonths,
            minDscr = defaults.minDscr,
            maxLtvPct = maxLtvPct,
            maxLtcPct = maxLtcPct,
            maxLtvArvPct = maxLtvArvPct,
            requestedLoanAmount = requestedLoan,
            bindingLoanCeiling = bindingCeiling,
            downPaymentBasis = input.downPaymentBasis ?: defaults.downPaymentBasis,
            monthlyPayment = monthlyPayment,
            interestOnlyPayment = Amortization.interestOnlyPayment(loanAmount, rate.first),
            newLoanFeesAndPoints = loanAmount * pointsPct.first / 100.0 + lenderFees.first,
            isInterestOnly = isInterestOnly,
            lenderProfitSharePct = defaults.lenderProfitSharePct ?: 0.0
        )
        return FinancingResolution(terms, provenance, issues)
    }

    private const val CONSISTENCY_TOLERANCE = 0.01

    private fun round2(value: Double): String {
        val cents = Math.round(value * 100.0).toDouble() / 100.0
        return cents.toString()
    }
}

data class FinancingResolution(
    val terms: FinancingTerms,
    val provenance: Map<String, ValueSource>,
    val issues: List<ValidationIssue>
)
