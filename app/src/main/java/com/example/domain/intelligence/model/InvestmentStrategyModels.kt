package com.example.domain.intelligence.model

enum class InvestmentStrategy {
    BUY_AND_HOLD,
    BRRRR,
    FIX_AND_FLIP,
    WHOLESALE
}

enum class FinancingType {
    CONVENTIONAL,
    DSCR,
    SELLER_FINANCING,
    PRIVATE_MONEY,
    HARD_MONEY
}

data class StrategyFinancialMetrics(
    val strategy: InvestmentStrategy,
    val financingType: FinancingType,
    val purchasePrice: Double,
    val downPayment: Double,
    val loanAmount: Double,
    val interestRate: Double,
    val loanTermYears: Int,
    val closingCosts: Double,
    val estimatedRepairs: Double,
    val totalCashRequired: Double,
    val grossMonthlyRent: Double,
    val vacancyLossMonthly: Double,
    val effectiveGrossIncomeMonthly: Double,
    val operatingExpensesMonthly: Double,
    val netOperatingIncomeAnnual: Double,
    val monthlyDebtService: Double,
    val monthlyCashFlow: Double,
    val annualCashFlow: Double,
    val capRate: Double,
    val cashOnCashReturn: Double,
    val dscr: Double,
    val ltv: Double,
    val projectedArv: Double? = null,
    val projectedRoi5Years: Double? = null
)
