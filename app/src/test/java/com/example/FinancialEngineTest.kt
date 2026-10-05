package com.example

import com.example.domain.finance.FinancialEngine
import com.example.domain.finance.FinancialInput
import org.junit.Assert.*
import org.junit.Test

class FinancialEngineTest {

    @Test
    fun testMonthlyMortgagePaymentCalculation() {
        // $360,000 loan at 6.85% for 30 years
        val payment = FinancialEngine.calculateMonthlyPayment(
            loanAmount = 360000.0,
            annualInterestRatePct = 6.85,
            loanTermYears = 30
        )
        // Standard amortization formula gives ~$2,358.74
        assertTrue("Payment should be around 2358.74, was $payment", payment in 2350.0..2370.0)
    }

    @Test
    fun testAllCashZeroDebt() {
        val payment = FinancialEngine.calculateMonthlyPayment(
            loanAmount = 0.0,
            annualInterestRatePct = 0.0,
            loanTermYears = 0
        )
        assertEquals(0.0, payment, 0.001)
    }

    @Test
    fun testFinancialEngineDeterministicMetrics() {
        val input = FinancialInput(
            purchasePrice = 500000.0,
            closingCosts = 10000.0,
            renovationCost = 20000.0,
            monthlyRent = 4500.0,
            otherMonthlyIncome = 200.0,
            vacancyRatePct = 5.0,
            propertyTaxAnnual = 6000.0,
            insuranceAnnual = 3000.0,
            maintenancePct = 5.0,
            managementPct = 8.0,
            utilitiesMonthly = 100.0,
            downPaymentPct = 20.0, // Loan: 400,000, Down: 100,000
            interestRatePct = 6.5,
            loanTermYears = 30
        )

        val result = FinancialEngine.calculate(input)

        // Gross Rent = 4500 + 200 = 4700
        assertEquals(4700.0, result.grossRentalIncome, 0.01)

        // Effective Rent = 4700 * (1 - 0.05) = 4465.0
        assertEquals(4465.0, result.effectiveRentalIncome, 0.01)

        // Expenses:
        // Tax/mo = 500
        // Ins/mo = 250
        // Maint/mo = 4500 * 0.05 = 225
        // Mgmt/mo = 4700 * 0.08 = 376
        // Util = 100
        // Total Opex = 500 + 250 + 225 + 376 + 100 = 1451
        assertEquals(1451.0, result.operatingExpensesMonthly, 0.01)

        // Monthly NOI = 4465 - 1451 = 3014.0
        // Annual NOI = 3014 * 12 = 36168.0
        assertEquals(36168.0, result.noiAnnual, 0.01)

        // Total cash required = Down (100,000) + Closing (10,000) + Reno (20,000) = 130,000
        assertEquals(130000.0, result.totalCashRequired, 0.01)

        // Debt service on 400,000 @ 6.5% for 30 yrs is ~$2,528.27
        assertTrue(result.monthlyDebtService in 2520.0..2535.0)

        // Monthly Cash Flow = 3014 - 2528.27 = ~485.73
        assertTrue(result.monthlyCashFlow in 475.0..495.0)

        // DSCR = (NOI/mo) / debt service = 3014 / 2528.27 = ~1.19
        assertTrue(result.dscr in 1.15..1.25)

        // Cap Rate = NOI / Total Acquisition (530,000) = 36168 / 530000 = ~6.82%
        assertTrue(result.capRate in 6.7..6.9)
    }

    @Test
    fun testFinancingScenarioComparisons() {
        val base = FinancialInput(
            purchasePrice = 400000.0,
            monthlyRent = 3500.0
        )
        val scenarios = FinancialEngine.generateComparisonScenarios(base)
        assertEquals(4, scenarios.size)

        val conventional = scenarios.find { it.first.contains("Conventional") }!!.second
        val allCash = scenarios.find { it.first.contains("All-Cash") }!!.second

        // All cash has 0 debt service and higher monthly cash flow
        assertEquals(0.0, allCash.monthlyDebtService, 0.01)
        assertTrue(allCash.monthlyCashFlow > conventional.monthlyCashFlow)
        assertEquals(999.0, allCash.dscr, 0.01) // Infinite DSCR
    }
}
