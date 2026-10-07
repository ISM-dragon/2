package com.example.domain.intelligence.engine

import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.FinancingType
import com.example.domain.intelligence.model.InvestmentStrategy
import com.example.domain.intelligence.model.StrategyFinancialMetrics
import kotlin.math.pow

object DeterministicFinancialEngine {

    fun calculate(
        property: CanonicalProperty,
        strategy: InvestmentStrategy = InvestmentStrategy.BUY_AND_HOLD,
        financingType: FinancingType = FinancingType.CONVENTIONAL,
        customPrice: Double? = null,
        customRent: Double? = null,
        customDownPaymentPct: Double? = null,
        customInterestRatePct: Double? = null
    ): StrategyFinancialMetrics {
        val purchasePrice = customPrice ?: property.listPrice
        val monthlyRent = customRent ?: (property.estimatedRent ?: (purchasePrice * 0.0075))

        val (defaultDownPct, defaultInterest, defaultTermYears) = when (financingType) {
            FinancingType.CONVENTIONAL -> Triple(20.0, 6.85, 30)
            FinancingType.DSCR -> Triple(25.0, 7.50, 30)
            FinancingType.SELLER_FINANCING -> Triple(15.0, 5.50, 20)
            FinancingType.PRIVATE_MONEY -> Triple(10.0, 9.00, 10)
            FinancingType.HARD_MONEY -> Triple(10.0, 11.50, 1)
        }

        val downPaymentPct = customDownPaymentPct ?: defaultDownPct
        val interestRatePct = customInterestRatePct ?: defaultInterest
        val loanTermYears = defaultTermYears

        val downPayment = purchasePrice * (downPaymentPct / 100.0)
        val loanAmount = (purchasePrice - downPayment).coerceAtLeast(0.0)
        val closingCosts = purchasePrice * 0.025 // 2.5% closing costs
        val estimatedRepairs = when (strategy) {
            InvestmentStrategy.BRRRR -> 35000.0
            InvestmentStrategy.FIX_AND_FLIP -> 50000.0
            InvestmentStrategy.WHOLESALE -> 0.0
            InvestmentStrategy.BUY_AND_HOLD -> 5000.0
        }

        val totalCashRequired = downPayment + closingCosts + estimatedRepairs

        // Income & Expenses
        val vacancyRatePct = 5.0
        val vacancyLoss = monthlyRent * (vacancyRatePct / 100.0)
        val effectiveGrossIncomeMonthly = (monthlyRent - vacancyLoss).coerceAtLeast(0.0)

        val propertyTaxMonthly = (property.propertyTax ?: (purchasePrice * 0.018)) / 12.0
        val insuranceMonthly = (purchasePrice * 0.006) / 12.0
        val maintenanceMonthly = monthlyRent * 0.05 // 5% maintenance
        val managementMonthly = monthlyRent * 0.08 // 8% property management
        val hoaMonthly = property.hoaFee ?: 0.0

        val operatingExpensesMonthly = propertyTaxMonthly + insuranceMonthly + maintenanceMonthly + managementMonthly + hoaMonthly
        val noiMonthly = effectiveGrossIncomeMonthly - operatingExpensesMonthly
        val netOperatingIncomeAnnual = noiMonthly * 12.0

        // Debt service calculation
        val monthlyDebtService = if (loanAmount > 0.0 && interestRatePct > 0.0) {
            if (financingType == FinancingType.HARD_MONEY) {
                // Interest-only monthly
                loanAmount * (interestRatePct / 100.0 / 12.0)
            } else {
                val monthlyRate = interestRatePct / 100.0 / 12.0
                val totalPayments = loanTermYears * 12
                val factor = (1.0 + monthlyRate).pow(totalPayments.toDouble())
                loanAmount * (monthlyRate * factor) / (factor - 1.0)
            }
        } else 0.0

        val monthlyCashFlow = noiMonthly - monthlyDebtService
        val annualCashFlow = monthlyCashFlow * 12.0

        val capRate = if (purchasePrice > 0.0) (netOperatingIncomeAnnual / purchasePrice) * 100.0 else 0.0
        val cashOnCashReturn = if (totalCashRequired > 0.0) (annualCashFlow / totalCashRequired) * 100.0 else 0.0
        val dscr = if (monthlyDebtService > 0.0) (noiMonthly / monthlyDebtService).coerceAtLeast(0.0) else 999.0
        val ltv = if (purchasePrice > 0.0) (loanAmount / purchasePrice) * 100.0 else 0.0

        val projectedArv = when (strategy) {
            InvestmentStrategy.BRRRR -> purchasePrice * 1.30
            InvestmentStrategy.FIX_AND_FLIP -> purchasePrice * 1.35
            InvestmentStrategy.WHOLESALE -> purchasePrice * 1.10
            InvestmentStrategy.BUY_AND_HOLD -> purchasePrice * 1.05
        }

        // 5-Year Cumulative ROI assuming 4.5% annual appreciation
        val equity5Y = purchasePrice * ((1.045).pow(5.0) - 1.0)
        val cumulativeCashFlow5Y = annualCashFlow * 5.0
        val projectedRoi5Y = if (totalCashRequired > 0.0) ((equity5Y + cumulativeCashFlow5Y) / totalCashRequired) * 100.0 else 0.0

        return StrategyFinancialMetrics(
            strategy = strategy,
            financingType = financingType,
            purchasePrice = purchasePrice,
            downPayment = downPayment,
            loanAmount = loanAmount,
            interestRate = interestRatePct,
            loanTermYears = loanTermYears,
            closingCosts = closingCosts,
            estimatedRepairs = estimatedRepairs,
            totalCashRequired = totalCashRequired,
            grossMonthlyRent = monthlyRent,
            vacancyLossMonthly = vacancyLoss,
            effectiveGrossIncomeMonthly = effectiveGrossIncomeMonthly,
            operatingExpensesMonthly = operatingExpensesMonthly,
            netOperatingIncomeAnnual = netOperatingIncomeAnnual,
            monthlyDebtService = monthlyDebtService,
            monthlyCashFlow = monthlyCashFlow,
            annualCashFlow = annualCashFlow,
            capRate = capRate,
            cashOnCashReturn = cashOnCashReturn,
            dscr = dscr,
            ltv = ltv,
            projectedArv = projectedArv,
            projectedRoi5Years = projectedRoi5Y
        )
    }
}
