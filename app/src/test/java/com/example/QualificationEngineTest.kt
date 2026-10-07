package com.example

import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.finance.FinancialEngine
import com.example.domain.finance.FinancialInput
import com.example.domain.qualification.QualificationEngine
import com.example.domain.qualification.QualificationPointAdjustment
import com.example.domain.qualification.QualificationScoringPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class QualificationEngineTest {

    @Test
    fun testHighCashFlowDealQualifies() {
        val property = PropertyEntity(
            id = "test-prop-1",
            sourceType = "ON_MARKET",
            title = "Cash Flow Winner",
            address = "123 Main St",
            city = "Austin",
            state = "TX",
            zipCode = "78701",
            latitude = 30.26,
            longitude = -97.74,
            price = 380000.0,
            propertyType = "Multi-Family",
            bedrooms = 4,
            bathrooms = 2.0,
            squareFeet = 2100,
            yearBuilt = 2015,
            lotSizeSqFt = 6000,
            description = "Great duplex",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = System.currentTimeMillis()
        )

        // Rent of $4,200 on $380,000 purchase gives high cash flow & cap rate
        val finResult = FinancialEngine.calculate(
            FinancialInput(
                purchasePrice = property.price,
                monthlyRent = 4200.0,
                propertyTaxAnnual = 4500.0,
                insuranceAnnual = 2200.0,
                downPaymentPct = 25.0,
                interestRatePct = 6.5
            )
        )

        val rules = AutomationRuleEntity(
            maxPurchasePrice = 500000.0,
            minCashFlow = 300.0,
            minCapRate = 6.5,
            minDscr = 1.25,
            allowedLocations = "Austin, Dallas, Houston",
            allowedPropertyTypes = "Multi-Family, Single Family"
        )

        val evaluation = QualificationEngine.evaluate(property, finResult, rules)
        assertTrue("High cash flow deal should qualify", evaluation.isQualified)
        assertTrue(evaluation.score >= 70)
        assertTrue(evaluation.failedChecks.isEmpty())
        assertTrue(evaluation.suggestedOfferPrice!! < property.price)
        assertEquals(10, evaluation.checkResults.size)
        assertEquals(evaluation.score, evaluation.score.coerceIn(0, 100))
    }

    @Test
    fun testOverpricedNegativeCashFlowFails() {
        val property = PropertyEntity(
            id = "test-prop-2",
            sourceType = "ON_MARKET",
            title = "Overpriced Negative Cashflow",
            address = "999 Luxury Way",
            city = "Seattle",
            state = "WA",
            zipCode = "98101",
            latitude = 47.60,
            longitude = -122.33,
            price = 950000.0, // Exceeds max purchase price 600k
            propertyType = "Single Family",
            bedrooms = 2,
            bathrooms = 1.0,
            squareFeet = 1000,
            yearBuilt = 1950,
            lotSizeSqFt = 3000,
            description = "Expensive tiny home",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = System.currentTimeMillis()
        )

        val finResult = FinancialEngine.calculate(
            FinancialInput(
                purchasePrice = property.price,
                monthlyRent = 2500.0, // Way too low rent for 950k price -> negative cash flow
                propertyTaxAnnual = 10000.0,
                insuranceAnnual = 4000.0,
                downPaymentPct = 10.0,
                interestRatePct = 7.5
            )
        )

        val rules = AutomationRuleEntity(
            maxPurchasePrice = 600000.0,
            minCashFlow = 250.0,
            minCapRate = 6.0,
            allowedLocations = "Austin, Dallas, Houston"
        )

        val evaluation = QualificationEngine.evaluate(property, finResult, rules)
        assertFalse("Overpriced deal should NOT qualify", evaluation.isQualified)
        assertTrue("Should have failed rules", evaluation.failedChecks.isNotEmpty())
    }

    @Test
    fun qualificationThresholdsAreInclusiveAndEveryAdjustmentIsAuditable() {
        val property = fixtureProperty(price = 600_000.0, yearBuilt = 2026)
        val financial = FinancialEngine.calculate(
            FinancialInput(
                purchasePrice = property.price,
                monthlyRent = 1_500.0,
                renovationCost = 0.0
            )
        ).copy(
            monthlyCashFlow = 300.0,
            capRate = 7.0,
            dscr = 1.25,
            cashOnCashReturn = 8.0
        )
        val evaluation = QualificationEngine.evaluate(property, financial, AutomationRuleEntity())

        assertTrue("Equality at each configured minimum/maximum should pass", evaluation.isQualified)
        assertTrue(evaluation.failedChecks.isEmpty())
        assertEquals(10, evaluation.checkResults.size)
        assertEquals(130, evaluation.rawScore)
        assertEquals(100, evaluation.score)
        assertTrue(evaluation.scoreWasClamped)
        assertEquals(
            listOf("purchase_price", "monthly_cash_flow", "cap_rate", "dscr", "cash_on_cash",
                "location", "property_type", "estimated_rent", "renovation_cost", "risk_score"),
            evaluation.checkResults.map { it.id }
        )
        assertTrue(evaluation.checkResults.all { it.passed })
        assertEquals(80, evaluation.checkResults.sumOf { it.scoreDelta })
        assertEquals(8.5, evaluation.effectiveOfferDiscountPercent!!, 0.0)
        assertEquals(549_000.0, evaluation.suggestedOfferPrice!!, 0.01)
        assertEquals(2026, evaluation.asOfYear)
    }

    @Test
    fun nonFiniteQualificationInputsFailClosedWithoutProducingAnOffer() {
        val property = fixtureProperty().copy(price = Double.NaN, yearBuilt = 2040)
        val financial = FinancialEngine.calculate(
            FinancialInput(purchasePrice = 300_000.0, monthlyRent = 2_500.0)
        ).copy(
            monthlyCashFlow = Double.NaN,
            capRate = Double.POSITIVE_INFINITY,
            dscr = Double.NaN,
            cashOnCashReturn = Double.NaN
        )
        val rules = AutomationRuleEntity(
            maxPurchasePrice = Double.NaN,
            minCashFlow = Double.NaN
        )
        val evaluation = QualificationEngine.evaluate(property, financial, rules)

        assertFalse(evaluation.isQualified)
        assertNull(evaluation.suggestedOfferPrice)
        assertTrue(evaluation.checkResults.take(5).all { !it.passed })
        assertTrue(evaluation.checkResults.take(5).all { it.message.contains("unavailable") })
        assertTrue(evaluation.warnings.any { it.contains("Suggested offer unavailable") })
        assertTrue(evaluation.score in 0..100)
        assertEquals(2026, evaluation.asOfYear)
    }

    @Test
    fun qualificationIsRepeatedAndLocaleIndependent() {
        val property = fixtureProperty()
        val financial = FinancialEngine.calculate(
            FinancialInput(
                purchasePrice = property.price,
                monthlyRent = 3_200.0,
                propertyTaxAnnual = 5_400.0,
                insuranceAnnual = 1_800.0
            )
        )
        val rules = AutomationRuleEntity()
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val expected = QualificationEngine.evaluate(property, financial, rules)
            repeat(25) {
                assertEquals("qualification repeat $it", expected, QualificationEngine.evaluate(property, financial, rules))
            }
            Locale.setDefault(Locale.GERMANY)
            assertEquals(expected, QualificationEngine.evaluate(property, financial, rules))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }


    @Test
    fun qualificationPointPolicyIsConfigurableAndEchoedInTheTrace() {
        val pointAdjustments = QualificationScoringPolicy.DEFAULT.pointAdjustments + mapOf(
            "monthly_cash_flow" to QualificationPointAdjustment(passed = 3, failed = -4)
        )
        val policy = QualificationScoringPolicy.DEFAULT.copy(
            version = "qualification-test-v1",
            neutralBaseScore = 40,
            pointAdjustments = pointAdjustments
        )
        val property = fixtureProperty(price = 600_000.0, yearBuilt = 2026)
        val financial = FinancialEngine.calculate(
            FinancialInput(purchasePrice = property.price, monthlyRent = 1_500.0)
        ).copy(monthlyCashFlow = 300.0, capRate = 7.0, dscr = 1.25, cashOnCashReturn = 8.0)
        val result = QualificationEngine.evaluate(property, financial, AutomationRuleEntity(), scoringPolicy = policy)

        assertEquals("qualification-test-v1", result.policyVersion)
        assertEquals(3, result.checkResults.first { it.id == "monthly_cash_flow" }.scoreDelta)
        assertEquals(40 + 80 - 15 + 3, result.rawScore)
    }

    @Test
    fun qualificationPolicyRejectsUnknownOrIncompleteRules() {
        try {
            QualificationScoringPolicy(pointAdjustments = mapOf(
                "purchase_price" to QualificationPointAdjustment(1, -1)
            ))
            fail("Incomplete policy must be rejected")
        } catch (_: IllegalArgumentException) {
        }

        try {
            QualificationScoringPolicy.DEFAULT.copy(lowDscrRiskThreshold = Double.NaN)
            fail("Non-finite risk threshold must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun fixtureProperty(price: Double = 300_000.0, yearBuilt: Int = 2000): PropertyEntity =
        PropertyEntity(
            id = "qualification-test",
            sourceType = "ON_MARKET",
            title = "Qualification fixture",
            address = "123 Main St",
            city = "Austin",
            state = "TX",
            zipCode = "78701",
            latitude = 30.26,
            longitude = -97.74,
            price = price,
            propertyType = "Single Family",
            bedrooms = 3,
            bathrooms = 2.0,
            squareFeet = 1_800,
            yearBuilt = yearBuilt,
            lotSizeSqFt = 5_000,
            description = "Deterministic test fixture",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = 0L
        )

}
