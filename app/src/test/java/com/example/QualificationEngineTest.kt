package com.example

import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.finance.FinancialEngine
import com.example.domain.finance.FinancialInput
import com.example.domain.qualification.QualificationEngine
import org.junit.Assert.*
import org.junit.Test

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
        assertTrue(evaluation.suggestedOfferPrice < property.price)
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
}
