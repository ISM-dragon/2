package com.example

import com.example.domain.finance.underwriting.CapexTreatment
import com.example.domain.finance.underwriting.DealRating
import com.example.domain.finance.underwriting.FinancingModel
import com.example.domain.finance.underwriting.InvestmentStrategy
import com.example.domain.finance.underwriting.IssueSeverity
import com.example.domain.finance.underwriting.UnderwritingEngine
import com.example.domain.finance.underwriting.UnderwritingFlatten
import com.example.domain.finance.underwriting.UnderwritingInput
import com.example.domain.finance.underwriting.UnderwritingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Hostile, degenerate and boundary inputs.
 *
 * The contract these tests enforce: the engine never throws, never returns NaN
 * or Infinity, never divides by zero, and never silently invents a number where
 * the honest answer is "not applicable". Where an input is unusable it is
 * reported with an explicit validation code instead.
 */
class UnderwritingEdgeCasesTest {

    private fun rental(
        strategy: InvestmentStrategy = InvestmentStrategy.BUY_AND_HOLD,
        purchasePrice: Double = 400000.0,
        monthlyRent: Double = 3600.0,
        rehabCost: Double = 0.0,
        closingCosts: Double? = 10000.0
    ) = UnderwritingInput(
        strategy = strategy,
        purchasePrice = purchasePrice,
        closingCosts = closingCosts,
        rehabCost = rehabCost,
        arv = if (strategy == InvestmentStrategy.BUY_AND_HOLD) null else 420000.0,
        monthlyRent = monthlyRent,
        otherMonthlyIncome = 150.0,
        vacancyRatePct = 5.0,
        propertyTaxAnnual = 4800.0,
        insuranceAnnual = 2400.0,
        downPaymentPct = 20.0,
        interestRatePct = 6.75,
        loanTermMonths = 360,
        amortizationMonths = 360,
        holdYears = 5
    )

    private fun codes(result: UnderwritingResult): List<String> = result.validation.map { it.code }

    private fun assertNoNonFiniteValues(result: UnderwritingResult) {
        for ((path, value) in UnderwritingFlatten.flatten(result)) {
            if (value is Number) {
                val asDouble = value.toDouble()
                assertFalse("$path was NaN", asDouble.isNaN())
                assertFalse("$path was infinite", asDouble.isInfinite())
            }
            if (value is List<*>) {
                for (element in value) {
                    if (element is Number) {
                        assertFalse("$path contained NaN", element.toDouble().isNaN())
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // non-finite and hostile numeric input
    // -----------------------------------------------------------------------

    @Test
    fun nanPurchasePriceIsRejectedAndReported() {
        val result = UnderwritingEngine.analyze(rental(purchasePrice = Double.NaN))
        assertTrue(codes(result).contains("NON_FINITE_INPUT"))
        assertTrue(codes(result).contains("NON_POSITIVE_PURCHASE_PRICE"))
        assertNoNonFiniteValues(result)
    }

    @Test
    fun infiniteRateIsIgnoredAndTheDefaultApplies() {
        val clean = UnderwritingEngine.analyze(rental())
        val poisoned = UnderwritingEngine.analyze(rental().copy(interestRatePct = Double.POSITIVE_INFINITY))
        assertTrue(codes(poisoned).contains("NON_FINITE_INPUT"))
        assertEquals(
            clean.financing.interestRatePct,
            poisoned.financing.interestRatePct,
            1e-12
        )
        assertNoNonFiniteValues(poisoned)
    }

    @Test
    fun everyNumericFieldSurvivesNaN() {
        val poisoned = rental().copy(
            monthlyRent = Double.NaN,
            otherMonthlyIncome = Double.NaN,
            vacancyRatePct = Double.NaN,
            propertyTaxAnnual = Double.NaN,
            insuranceAnnual = Double.NaN,
            rehabCost = Double.NaN,
            closingCosts = Double.NaN,
            hoaMonthly = Double.NEGATIVE_INFINITY,
            utilitiesMonthly = Double.NaN,
            maintenancePctOfGsi = Double.NaN,
            managementPctOfEgi = Double.NaN,
            capexReservePctOfGsi = Double.NaN,
            loanAmount = Double.NaN,
            downPaymentPct = Double.NaN,
            appreciationPct = Double.NaN,
            saleCostPct = Double.NaN,
            rentGrowthPct = Double.NaN
        )
        val result = UnderwritingEngine.analyze(poisoned)
        assertTrue(codes(result).count { it == "NON_FINITE_INPUT" } >= 10)
        assertNoNonFiniteValues(result)
        assertTrue(result.verdict.rating in DealRating.entries)
    }

    // -----------------------------------------------------------------------
    // boundary values
    // -----------------------------------------------------------------------

    @Test
    fun zeroPurchasePriceIsAnErrorNotADivideByZero() {
        val result = UnderwritingEngine.analyze(rental(purchasePrice = 0.0, monthlyRent = 0.0, closingCosts = 0.0))
        assertTrue(codes(result).contains("NON_POSITIVE_PURCHASE_PRICE"))
        assertNull(result.core.capRateOnPricePct)
        assertNull(result.core.ltvOfPricePct)
        assertNoNonFiniteValues(result)
    }

    @Test
    fun negativeRehabIsFlooredAndReported() {
        val result = UnderwritingEngine.analyze(rental(rehabCost = -5000.0))
        assertTrue(codes(result).contains("NEGATIVE_REHAB_COST"))
        assertEquals(0.0, result.rehabCost, 1e-9)
    }

    @Test
    fun negativeRentIsReportedAndNeverSilentlyClamped() {
        val result = UnderwritingEngine.analyze(rental(monthlyRent = -500.0))
        assertTrue(codes(result).contains("NEGATIVE_RENT"))
        assertTrue(codes(result).contains("NEGATIVE_NOI"))
        // the honest number is carried through (rent + other income), flagged as an error
        assertEquals((-500.0 + 150.0) * 12.0, result.operating.grossScheduledIncomeAnnual, 1e-6)
        assertTrue(result.core.monthlyCashFlow < 0.0)
        assertNoNonFiniteValues(result)
    }

    @Test
    fun negativeVacancyIsClampedSoEgiNeverExceedsGsi() {
        val result = UnderwritingEngine.analyze(rental().copy(vacancyRatePct = -10.0))
        assertTrue(codes(result).contains("NEGATIVE_VACANCY"))
        val severity = result.validation.first { it.code == "NEGATIVE_VACANCY" }.severity
        assertEquals(IssueSeverity.ERROR, severity)
        assertTrue(
            result.operating.effectiveGrossIncomeAnnual <=
                result.operating.grossScheduledIncomeAnnual + 1e-9
        )
    }

    @Test
    fun vacancyAboveOneHundredPercentIsClampedAndReported() {
        val result = UnderwritingEngine.analyze(rental().copy(vacancyRatePct = 130.0))
        assertTrue(codes(result).contains("TOTAL_VACANCY_ABOVE_100"))
        assertEquals(0.0, result.operating.effectiveGrossIncomeAnnual, 1e-9)
    }

    @Test
    fun oneHundredPercentVacancyYieldsNoIncomeAndNegativeNoi() {
        val result = UnderwritingEngine.analyze(rental().copy(vacancyRatePct = 100.0))
        assertEquals(0.0, result.operating.effectiveGrossIncomeAnnual, 1e-9)
        assertTrue(result.core.noiAnnual < 0.0)
        assertTrue(codes(result).contains("NEGATIVE_NOI"))
    }

    @Test
    fun zeroRentAndNoOtherIncomeHasNoBreakEvenFigureAndNoMultiplier() {
        val result = UnderwritingEngine.analyze(
            rental(monthlyRent = 0.0).copy(otherMonthlyIncome = 0.0)
        )
        assertEquals(0.0, result.operating.grossScheduledIncomeAnnual, 1e-9)
        assertNull(result.core.breakEvenOccupancyPct)
        assertNull(result.core.grossRentMultiplier)
        assertTrue(result.core.monthlyCashFlow < 0.0)
        assertTrue(result.core.dscr!! < 0.0)
        assertEquals(DealRating.FAILS_CRITERIA, result.verdict.rating)
        assertNoNonFiniteValues(result)
    }

    @Test
    fun zeroInterestRatesDegenerateToStraightLinePrincipal() {
        val result = UnderwritingEngine.analyze(rental().copy(interestRatePct = 0.0))
        assertEquals(320000.0 / 360.0, result.core.monthlyDebtService, 1e-6)
    }

    @Test
    fun allCashPurchaseHasNoDebtServiceAndNoDscrRatherThanASentinel() {
        val result = UnderwritingEngine.analyze(rental().copy(loanAmount = 0.0, downPaymentPct = null))
        assertEquals(0.0, result.core.monthlyDebtService, 1e-9)
        assertEquals(0.0, result.core.annualDebtService, 1e-9)
        assertNull("all-cash DSCR must be null, not 999", result.core.dscr)
        assertEquals(result.core.noiAnnual, result.core.annualCashFlow, 1e-6)
        assertTrue(result.verdict.passedCriteria.contains("DSCR_NOT_APPLICABLE_ALL_CASH"))
    }

    @Test
    fun extremeRateOverFortyYearsStaysFinite() {
        val result = UnderwritingEngine.analyze(
            rental().copy(interestRatePct = 300.0, loanTermMonths = 480, amortizationMonths = 480)
        )
        assertTrue(result.core.monthlyDebtService.isFinite())
        assertTrue(result.core.monthlyDebtService > 0.0)
        assertNoNonFiniteValues(result)
    }

    @Test
    fun hugeAndTinyDealsBothRemainFinite() {
        val huge = UnderwritingEngine.analyze(
            UnderwritingInput(
                purchasePrice = 1e9,
                monthlyRent = 1e7,
                propertyTaxAnnual = 1e7,
                insuranceAnnual = 5e6,
                downPaymentPct = 30.0,
                holdYears = 10
            )
        )
        val tiny = UnderwritingEngine.analyze(
            UnderwritingInput(purchasePrice = 1.0, monthlyRent = 1.0, closingCosts = 0.0)
        )
        assertNoNonFiniteValues(huge)
        assertNoNonFiniteValues(tiny)
    }

    @Test
    fun zeroTermLoanDoesNotDivideByZero() {
        val result = UnderwritingEngine.analyze(
            rental().copy(loanTermMonths = 0, amortizationMonths = 0, interestOnlyMonths = 0)
        )
        assertTrue(result.core.monthlyDebtService.isFinite())
        assertNull(result.core.dscr)
    }

    @Test
    fun interestOnlyPeriodLongerThanTermIsClamped() {
        val result = UnderwritingEngine.analyze(
            rental().copy(loanTermMonths = 12, amortizationMonths = 360, interestOnlyMonths = 36)
        )
        assertTrue(codes(result).contains("IO_PERIOD_EXCEEDS_TERM"))
        assertTrue(result.financing.isInterestOnly)
    }

    // -----------------------------------------------------------------------
    // loan sizing conflicts and ceilings
    // -----------------------------------------------------------------------

    @Test
    fun explicitLoanAmountWinsOverDownPaymentAndTheConflictIsReported() {
        val result = UnderwritingEngine.analyze(
            rental().copy(loanAmount = 200000.0, downPaymentAmount = 100000.0, downPaymentPct = null)
        )
        assertEquals(200000.0, result.financing.requestedLoanAmount, 1e-6)
        assertTrue(codes(result).contains("LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT"))
    }

    @Test
    fun conflictingLoanAmountAndPercentageIsReportedForEachConflict() {
        val result = UnderwritingEngine.analyze(
            rental().copy(loanAmount = 200000.0, downPaymentAmount = 100000.0, downPaymentPct = 20.0)
        )
        assertEquals(2, codes(result).count { it == "LOAN_AMOUNT_DOWN_PAYMENT_CONFLICT" })
    }

    @Test
    fun explicitLoanAmountIsStillCappedByTheLenderCeiling() {
        val result = UnderwritingEngine.analyze(rental().copy(loanAmount = 500000.0, downPaymentPct = null))
        assertEquals(320000.0, result.core.loanAmount, 1e-6)
        assertEquals(1, codes(result).count { it == "LOAN_CAPPED_BY_MAX_LTV_OF_PRICE" })
        assertTrue(result.financing.loanCappedByLender)
        assertNotNull(result.financing.bindingLoanCeiling)
    }

    @Test
    fun zeroDownPaymentIsAllowedButStillCappedByTheModelCeiling() {
        val result = UnderwritingEngine.analyze(rental().copy(downPaymentPct = 0.0))
        // conventional caps at 80% LTV, so "nothing down" is still 20% down here
        assertEquals(320000.0, result.financing.loanAmount, 1e-6)
    }

    // -----------------------------------------------------------------------
    // strategy-specific refusals
    // -----------------------------------------------------------------------

    @Test
    fun flipWithoutArvIsRefusedRatherThanGuessed() {
        val result = UnderwritingEngine.analyze(
            UnderwritingInput(
                strategy = InvestmentStrategy.FIX_AND_FLIP,
                purchasePrice = 250000.0,
                rehabCost = 45000.0,
                arv = null,
                closingCosts = 3750.0,
                financingModel = FinancingModel.HARD_MONEY
            )
        )
        assertTrue(codes(result).contains("FLIP_REQUIRES_ARV"))
        assertNull(result.flip)
        assertNull(result.returns)
        assertEquals(DealRating.FAILS_CRITERIA, result.verdict.rating)
        assertEquals(listOf("MISSING_REQUIRED_INPUT"), result.verdict.failedCriteria)
    }

    @Test
    fun brrrrWithoutArvIsRefusedRatherThanGuessed() {
        val result = UnderwritingEngine.analyze(
            UnderwritingInput(
                strategy = InvestmentStrategy.BRRRR,
                purchasePrice = 200000.0,
                rehabCost = 45000.0,
                arv = 0.0,
                monthlyRent = 3060.0,
                financingModel = FinancingModel.HARD_MONEY
            )
        )
        assertTrue(codes(result).contains("BRRRR_REQUIRES_ARV"))
        assertNull(result.brrrr)
        assertNull(result.returns)
        assertEquals(DealRating.FAILS_CRITERIA, result.verdict.rating)
    }

    @Test
    fun zeroFlipMonthsFallsBackToTheDocumentedDefault() {
        val result = UnderwritingEngine.analyze(
            UnderwritingInput(
                strategy = InvestmentStrategy.FIX_AND_FLIP,
                purchasePrice = 250000.0,
                rehabCost = 45000.0,
                arv = 400000.0,
                closingCosts = 3750.0,
                financingModel = FinancingModel.HARD_MONEY,
                flipHoldMonths = 0
            )
        )
        assertTrue(codes(result).contains("FLIP_HOLD_MONTHS_DEFAULTED"))
        assertEquals(6, result.flip!!.holdMonths)
    }

    @Test
    fun zeroHoldYearsFallsBackToTheDocumentedDefault() {
        val result = UnderwritingEngine.analyze(rental().copy(holdYears = 0))
        assertTrue(codes(result).contains("HOLD_YEARS_DEFAULTED"))
        assertEquals(5, result.hold!!.years.size)
    }

    @Test
    fun zeroDaysToCloseFallsBackToTheDocumentedDefault() {
        val result = UnderwritingEngine.analyze(
            UnderwritingInput(
                strategy = InvestmentStrategy.WHOLESALE,
                purchasePrice = 300000.0,
                arv = 420000.0,
                assignmentFee = 12000.0,
                marketingCost = 500.0,
                daysToClose = 0
            )
        )
        assertTrue(codes(result).contains("DAYS_TO_CLOSE_DEFAULTED"))
        assertEquals(30, result.wholesale!!.daysToClose)
    }

    // -----------------------------------------------------------------------
    // long/short money and balloons
    // -----------------------------------------------------------------------

    @Test
    fun bridgeDebtThatMaturesInsideTheProjectionAssumesARefinanceAndSaysSo() {
        // Hard money defaults are a 12-month interest-only note; holding it for
        // ten years therefore requires an assumption the engine must disclose.
        val result = UnderwritingEngine.analyze(
            rental().copy(
                financingModel = FinancingModel.HARD_MONEY,
                interestRatePct = null,
                loanTermMonths = null,
                amortizationMonths = null,
                holdYears = 10
            )
        )
        assertTrue(result.financing.isInterestOnly)
        assertTrue(codes(result).contains("REFINANCE_ASSUMED_AT_MATURITY"))
        assertTrue("year one is interest only", result.hold!!.years[0].debtService > 0.0)
        assertTrue("year two must still pay debt service", result.hold!!.years[1].debtService > 0.0)
    }

    @Test
    fun sellerCarryBalloonAtTheEndOfTheHoldNeedsNoRefinanceAssumption() {
        // The seller note balloons in month 60 and the projection ends there, so
        // there is no month inside the horizon that needs refinancing.
        val result = UnderwritingEngine.analyze(
            rental().copy(
                financingModel = FinancingModel.SELLER_FINANCING,
                interestRatePct = null,
                loanTermMonths = null,
                amortizationMonths = null,
                holdYears = 5
            )
        )
        assertEquals(60, result.financing.loanTermMonths)
        assertFalse(codes(result).contains("REFINANCE_ASSUMED_AT_MATURITY"))
    }

    @Test
    fun belowLineReservesRaiseNoiByExactlyTheReserve() {
        val above = UnderwritingEngine.analyze(rental().copy(capexTreatment = CapexTreatment.ABOVE_LINE_IN_NOI))
        val below = UnderwritingEngine.analyze(rental().copy(capexTreatment = CapexTreatment.BELOW_LINE_RESERVE))
        assertEquals(
            above.operating.capitalReservesAnnual,
            below.core.noiAnnual - above.core.noiAnnual,
            1e-6
        )
        assertEquals(CapexTreatment.BELOW_LINE_RESERVE, below.operating.capexTreatment)
    }

    @Test
    fun totalLossDealReportsNoComplexOrGeometricAnnualisation() {
        val result = UnderwritingEngine.analyze(
            UnderwritingInput(
                purchasePrice = 400000.0,
                monthlyRent = 500.0,
                propertyTaxAnnual = 20000.0,
                insuranceAnnual = 9000.0,
                downPaymentPct = 10.0,
                interestRatePct = 25.0,
                holdYears = 5
            )
        )
        val returns = result.returns!!
        assertTrue(returns.roiPct!! < 0.0)
        assertNull("a non-positive equity multiple cannot be annualised", returns.annualizedRoiPct)
        assertNotNull(returns.simpleAnnualizedRoiPct)
        assertNoNonFiniteValues(result)
    }
}
