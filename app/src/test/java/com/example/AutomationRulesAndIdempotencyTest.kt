package com.example

import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.finance.FinancialEngine
import com.example.domain.finance.FinancialInput
import com.example.domain.qualification.QualificationEngine
import org.junit.Assert.*
import org.junit.Test

class AutomationRulesAndIdempotencyTest {

    @Test
    fun testAllTenQualificationRulesExplicitEvaluation() {
        val property = PropertyEntity(
            id = "prop-test-all-rules",
            sourceType = "ON_MARKET",
            title = "Test House",
            address = "742 Evergreen Terrace",
            city = "Austin",
            state = "TX",
            zipCode = "78704",
            latitude = 30.25,
            longitude = -97.75,
            price = 420000.0,
            propertyType = "Single Family",
            bedrooms = 3,
            bathrooms = 2.0,
            squareFeet = 1750,
            yearBuilt = 2018,
            lotSizeSqFt = 5500,
            description = "Solid turnkey investment",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = System.currentTimeMillis()
        )

        val input = FinancialInput(
            purchasePrice = property.price,
            closingCosts = 8000.0,
            renovationCost = 15000.0,
            monthlyRent = 4500.0,
            propertyTaxAnnual = 5000.0,
            insuranceAnnual = 2000.0,
            downPaymentPct = 20.0,
            interestRatePct = 6.5,
            loanTermYears = 30
        )
        val financial = FinancialEngine.calculate(input)

        val rules = AutomationRuleEntity(
            maxPurchasePrice = 500000.0,
            minCashFlow = 200.0,
            minCapRate = 6.0,
            minDscr = 1.15,
            minCashOnCash = 5.0,
            allowedLocations = "Austin, Dallas, Houston",
            allowedPropertyTypes = "Single Family, Multi-Family",
            maxRenovationCost = 50000.0,
            minEstimatedRent = 2000.0,
            maxRiskScore = 40
        )

        val eval = QualificationEngine.evaluate(property, financial, rules)

        // Must show clear pass reasons
        assertTrue("Property should qualify", eval.isQualified)
        assertTrue(eval.passedChecks.isNotEmpty())
        assertTrue("Summary should report deal qualified", eval.summary.contains("Qualified Deal"))

        // Now test a failing criteria: e.g. location outside whitelist
        val badLocationProp = property.copy(city = "Denver", state = "CO")
        val failEval = QualificationEngine.evaluate(badLocationProp, financial, rules)
        assertFalse("Denver is not in allowed target markets (Austin, Dallas, Houston)", failEval.isQualified)
        assertTrue(failEval.failedChecks.any { it.contains("Denver") })
    }

    @Test
    fun testRenovationCostExceededFails() {
        val property = PropertyEntity(
            id = "prop-high-reno",
            sourceType = "OFF_MARKET",
            title = "Distressed Rehab",
            address = "123 Heavy Fixer",
            city = "Austin",
            state = "TX",
            zipCode = "78701",
            latitude = 30.26,
            longitude = -97.74,
            price = 300000.0,
            propertyType = "Single Family",
            bedrooms = 3,
            bathrooms = 1.0,
            squareFeet = 1200,
            yearBuilt = 1960,
            lotSizeSqFt = 6000,
            description = "Complete teardown",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = System.currentTimeMillis()
        )

        val input = FinancialInput(
            purchasePrice = property.price,
            renovationCost = 120000.0, // Exceeds 75,000 threshold
            monthlyRent = 3000.0
        )
        val financial = FinancialEngine.calculate(input)

        val rules = AutomationRuleEntity(
            maxRenovationCost = 75000.0
        )

        val eval = QualificationEngine.evaluate(property, financial, rules)
        assertTrue(eval.failedChecks.any { it.contains("Reno") && it.contains("exceeds Max") })
    }
}
