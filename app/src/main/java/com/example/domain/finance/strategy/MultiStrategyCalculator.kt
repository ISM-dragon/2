package com.example.domain.finance.strategy

import kotlin.math.pow

enum class InvestmentStrategyType {
    BUY_AND_HOLD,
    BRRRR,
    FIX_AND_FLIP,
    WHOLESALE
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Buy & Hold Model
// ─────────────────────────────────────────────────────────────────────────────
data class BuyAndHoldInputs(
    val purchasePrice: Double,
    val downPaymentPct: Double = 20.0,
    val interestRatePct: Double = 6.85,
    val loanTermYears: Int = 30,
    val closingCosts: Double = 0.0,
    val initialRehab: Double = 0.0,
    val monthlyRent: Double,
    val otherIncomeMonthly: Double = 0.0,
    val vacancyRatePct: Double = 5.0,
    val propertyTaxAnnual: Double = 0.0,
    val insuranceAnnual: Double = 0.0,
    val maintenancePct: Double = 5.0,
    val managementPct: Double = 8.0,
    val utilitiesMonthly: Double = 0.0
)

data class BuyAndHoldResult(
    val monthlyGrossRent: Double,
    val effectiveGrossIncome: Double,
    val monthlyOperatingExpenses: Double,
    val monthlyNoi: Double,
    val annualNoi: Double,
    val loanAmount: Double,
    val monthlyMortgage: Double,
    val monthlyCashFlow: Double,
    val annualCashFlow: Double,
    val totalCashInvested: Double,
    val capRate: Double,
    val cashOnCashReturn: Double,
    val dscr: Double,
    val grossRentMultiplier: Double,
    val onePercentRuleMet: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// 2. BRRRR Model
// ─────────────────────────────────────────────────────────────────────────────
data class BrrrrInputs(
    val purchasePrice: Double,
    val rehabCost: Double,
    val holdingPeriodMonths: Int = 4,
    val monthlyHoldingCost: Double = 1200.0,
    val arv: Double,
    val refiLtvPct: Double = 75.0,
    val refiInterestRatePct: Double = 6.85,
    val refiLoanTermYears: Int = 30,
    val postRehabMonthlyRent: Double,
    val postRehabExpensesMonthly: Double
)

data class BrrrrResult(
    val totalInitialCashInvested: Double,
    val newLoanAmount: Double,
    val cashOutRefiProceeds: Double,
    val netCapitalLeftInDeal: Double,
    val capitalRecoveryPct: Double,
    val isInfiniteReturn: Boolean,
    val postRefiMonthlyMortgage: Double,
    val postRefiMonthlyCashFlow: Double,
    val postRefiAnnualCashFlow: Double,
    val postRefiCashOnCash: Double,
    val instantEquityCreated: Double
)

// ─────────────────────────────────────────────────────────────────────────────
// 3. Fix & Flip Model
// ─────────────────────────────────────────────────────────────────────────────
data class FixAndFlipInputs(
    val purchasePrice: Double,
    val rehabBudget: Double,
    val projectDurationMonths: Int = 6,
    val hardMoneyInterestRatePct: Double = 11.0,
    val hardMoneyPointsPct: Double = 2.0,
    val monthlyCarryingCosts: Double = 800.0,
    val sellingCostsPct: Double = 7.0, // Realtor commission 5-6% + closing fees 1-2%
    val expectedSalePrice: Double // ARV
)

data class FixAndFlipResult(
    val financingFees: Double,
    val totalHoldingCosts: Double,
    val sellingCosts: Double,
    val totalProjectCost: Double,
    val grossProfit: Double,
    val netProfit: Double,
    val returnOnInvestmentPct: Double,
    val annualizedRoiPct: Double,
    val seventyPercentRuleMao: Double,
    val isProfitable: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// 4. Wholesale / MAO Model
// ─────────────────────────────────────────────────────────────────────────────
data class WholesaleInputs(
    val arv: Double,
    val estimatedRepairs: Double,
    val desiredAssignmentFee: Double = 10000.0,
    val buyerTargetDiscountPct: Double = 70.0 // Standard 70% rule
)

data class WholesaleResult(
    val maxAllowableOffer: Double,
    val buyerPurchasePrice: Double,
    val targetInvestorEquity: Double,
    val assignmentFee: Double,
    val recommendedOfferRangeLow: Double,
    val recommendedOfferRangeHigh: Double
)

// ─────────────────────────────────────────────────────────────────────────────
// 5. 10-Year Wealth Forecast
// ─────────────────────────────────────────────────────────────────────────────
data class YearWealthMilestone(
    val year: Int,
    val propertyValue: Double,
    val loanBalance: Double,
    val totalEquity: Double,
    val cumulativeCashFlow: Double,
    val totalWealthCreated: Double
)

object MultiStrategyCalculator {

    fun calculateBuyAndHold(input: BuyAndHoldInputs): BuyAndHoldResult {
        val grossRent = input.monthlyRent + input.otherIncomeMonthly
        val vacancyLoss = grossRent * (input.vacancyRatePct / 100.0)
        val effectiveIncome = (grossRent - vacancyLoss).coerceAtLeast(0.0)

        val taxMonthly = input.propertyTaxAnnual / 12.0
        val insMonthly = input.insuranceAnnual / 12.0
        val maintMonthly = input.monthlyRent * (input.maintenancePct / 100.0)
        val mgmtMonthly = grossRent * (input.managementPct / 100.0)
        val operatingExpenses = taxMonthly + insMonthly + maintMonthly + mgmtMonthly + input.utilitiesMonthly

        val monthlyNoi = effectiveIncome - operatingExpenses
        val annualNoi = monthlyNoi * 12.0

        val downPayment = input.purchasePrice * (input.downPaymentPct / 100.0)
        val loanAmount = (input.purchasePrice - downPayment).coerceAtLeast(0.0)
        val monthlyMortgage = calculateMortgagePayment(loanAmount, input.interestRatePct, input.loanTermYears)

        val monthlyCashFlow = effectiveIncome - operatingExpenses - monthlyMortgage
        val annualCashFlow = monthlyCashFlow * 12.0

        val totalCashInvested = (downPayment + input.closingCosts + input.initialRehab).coerceAtLeast(1.0)
        val capRate = if (input.purchasePrice > 0) (annualNoi / input.purchasePrice) * 100.0 else 0.0
        val cashOnCash = (annualCashFlow / totalCashInvested) * 100.0

        val dscr = if (monthlyMortgage > 0) monthlyNoi / monthlyMortgage else 99.0
        val grm = if (grossRent > 0) input.purchasePrice / (grossRent * 12.0) else 0.0
        val onePercentRule = if (input.purchasePrice > 0) (grossRent / input.purchasePrice) >= 0.01 else false

        return BuyAndHoldResult(
            monthlyGrossRent = grossRent,
            effectiveGrossIncome = effectiveIncome,
            monthlyOperatingExpenses = operatingExpenses,
            monthlyNoi = monthlyNoi,
            annualNoi = annualNoi,
            loanAmount = loanAmount,
            monthlyMortgage = monthlyMortgage,
            monthlyCashFlow = monthlyCashFlow,
            annualCashFlow = annualCashFlow,
            totalCashInvested = totalCashInvested,
            capRate = capRate,
            cashOnCashReturn = cashOnCash,
            dscr = dscr,
            grossRentMultiplier = grm,
            onePercentRuleMet = onePercentRule
        )
    }

    fun calculateBrrrr(input: BrrrrInputs): BrrrrResult {
        val totalHoldingCosts = input.monthlyHoldingCost * input.holdingPeriodMonths
        val initialCashInvested = input.purchasePrice + input.rehabCost + totalHoldingCosts

        val newLoanAmount = input.arv * (input.refiLtvPct / 100.0)
        val cashOutRefiProceeds = newLoanAmount
        val netCapitalLeftInDeal = (initialCashInvested - cashOutRefiProceeds).coerceAtLeast(0.0)
        val capitalRecovery = if (initialCashInvested > 0) (cashOutRefiProceeds / initialCashInvested) * 100.0 else 0.0
        val isInfinite = cashOutRefiProceeds >= initialCashInvested

        val postRefiMortgage = calculateMortgagePayment(newLoanAmount, input.refiInterestRatePct, input.refiLoanTermYears)
        val postRefiMonthlyCashFlow = input.postRehabMonthlyRent - input.postRehabExpensesMonthly - postRefiMortgage
        val postRefiAnnualCashFlow = postRefiMonthlyCashFlow * 12.0

        val postRefiCoC = if (isInfinite) 999.0 else if (netCapitalLeftInDeal > 0) (postRefiAnnualCashFlow / netCapitalLeftInDeal) * 100.0 else 0.0
        val instantEquity = (input.arv - newLoanAmount).coerceAtLeast(0.0)

        return BrrrrResult(
            totalInitialCashInvested = initialCashInvested,
            newLoanAmount = newLoanAmount,
            cashOutRefiProceeds = cashOutRefiProceeds,
            netCapitalLeftInDeal = netCapitalLeftInDeal,
            capitalRecoveryPct = capitalRecovery,
            isInfiniteReturn = isInfinite,
            postRefiMonthlyMortgage = postRefiMortgage,
            postRefiMonthlyCashFlow = postRefiMonthlyCashFlow,
            postRefiAnnualCashFlow = postRefiAnnualCashFlow,
            postRefiCashOnCash = postRefiCoC,
            instantEquityCreated = instantEquity
        )
    }

    fun calculateFixAndFlip(input: FixAndFlipInputs): FixAndFlipResult {
        val loanAmount = (input.purchasePrice + input.rehabBudget) * 0.90
        val pointsCost = loanAmount * (input.hardMoneyPointsPct / 100.0)
        val monthlyInterest = (loanAmount * (input.hardMoneyInterestRatePct / 100.0)) / 12.0
        val totalFinancing = pointsCost + (monthlyInterest * input.projectDurationMonths)

        val totalHolding = input.monthlyCarryingCosts * input.projectDurationMonths
        val sellingCosts = input.expectedSalePrice * (input.sellingCostsPct / 100.0)

        val totalProjectCost = input.purchasePrice + input.rehabBudget + totalFinancing + totalHolding + sellingCosts
        val grossProfit = input.expectedSalePrice - (input.purchasePrice + input.rehabBudget)
        val netProfit = input.expectedSalePrice - totalProjectCost

        val totalOutlay = input.purchasePrice * 0.10 + totalFinancing + totalHolding // Cash required
        val roi = if (totalProjectCost > 0) (netProfit / totalProjectCost) * 100.0 else 0.0
        val annualizedRoi = if (input.projectDurationMonths > 0) roi * (12.0 / input.projectDurationMonths) else roi

        val seventyPctMao = (input.expectedSalePrice * 0.70) - input.rehabBudget

        return FixAndFlipResult(
            financingFees = totalFinancing,
            totalHoldingCosts = totalHolding,
            sellingCosts = sellingCosts,
            totalProjectCost = totalProjectCost,
            grossProfit = grossProfit,
            netProfit = netProfit,
            returnOnInvestmentPct = roi,
            annualizedRoiPct = annualizedRoi,
            seventyPercentRuleMao = seventyPctMao,
            isProfitable = netProfit > 0
        )
    }

    fun calculateWholesale(input: WholesaleInputs): WholesaleResult {
        val buyerPurchasePrice = (input.arv * (input.buyerTargetDiscountPct / 100.0)) - input.estimatedRepairs
        val maxAllowableOffer = buyerPurchasePrice - input.desiredAssignmentFee
        val targetInvestorEquity = input.arv - (buyerPurchasePrice + input.estimatedRepairs)

        return WholesaleResult(
            maxAllowableOffer = maxAllowableOffer.coerceAtLeast(0.0),
            buyerPurchasePrice = buyerPurchasePrice.coerceAtLeast(0.0),
            targetInvestorEquity = targetInvestorEquity.coerceAtLeast(0.0),
            assignmentFee = input.desiredAssignmentFee,
            recommendedOfferRangeLow = (maxAllowableOffer * 0.90).coerceAtLeast(0.0),
            recommendedOfferRangeHigh = maxAllowableOffer.coerceAtLeast(0.0)
        )
    }

    fun calculate10YearForecast(
        initialPrice: Double,
        downPaymentPct: Double,
        loanInterestRate: Double,
        loanTermYears: Int,
        initialAnnualCashFlow: Double,
        appreciationRatePct: Double = 4.0,
        rentGrowthPct: Double = 3.0
    ): List<YearWealthMilestone> {
        val milestones = mutableListOf<YearWealthMilestone>()
        val initialLoan = initialPrice * (1.0 - (downPaymentPct / 100.0))
        val monthlyRate = (loanInterestRate / 100.0) / 12.0
        val totalMonths = loanTermYears * 12

        var currentPrice = initialPrice
        var currentAnnualCashFlow = initialAnnualCashFlow
        var cumulativeCashFlow = 0.0

        for (year in 1..10) {
            currentPrice *= (1.0 + (appreciationRatePct / 100.0))
            currentAnnualCashFlow *= (1.0 + (rentGrowthPct / 100.0))
            cumulativeCashFlow += currentAnnualCashFlow

            val elapsedMonths = year * 12
            val remainingBalance = if (monthlyRate > 0) {
                val factor = (1 + monthlyRate).pow(totalMonths.toDouble())
                val elapsedFactor = (1 + monthlyRate).pow(elapsedMonths.toDouble())
                (initialLoan * (factor - elapsedFactor) / (factor - 1)).coerceAtLeast(0.0)
            } else 0.0

            val equity = (currentPrice - remainingBalance).coerceAtLeast(0.0)
            val totalWealth = equity + cumulativeCashFlow

            milestones.add(
                YearWealthMilestone(
                    year = year,
                    propertyValue = currentPrice,
                    loanBalance = remainingBalance,
                    totalEquity = equity,
                    cumulativeCashFlow = cumulativeCashFlow,
                    totalWealthCreated = totalWealth
                )
            )
        }
        return milestones
    }

    private fun calculateMortgagePayment(principal: Double, annualRatePct: Double, termYears: Int): Double {
        if (principal <= 0.0 || termYears <= 0) return 0.0
        val monthlyRate = (annualRatePct / 100.0) / 12.0
        if (monthlyRate == 0.0) return principal / (termYears * 12.0)
        val numPayments = termYears * 12.0
        val factor = (1.0 + monthlyRate).pow(numPayments)
        return principal * (monthlyRate * factor) / (factor - 1.0)
    }
}
