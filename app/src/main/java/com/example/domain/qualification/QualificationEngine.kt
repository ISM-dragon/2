package com.example.domain.qualification

import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.finance.FinancialResult

data class QualificationEvaluation(
    val isQualified: Boolean,
    val score: Int, // 0 to 100
    val passedChecks: List<String>,
    val failedChecks: List<String>,
    val suggestedOfferPrice: Double,
    val summary: String
)

object QualificationEngine {

    fun evaluate(
        property: PropertyEntity,
        financial: FinancialResult,
        rules: AutomationRuleEntity
    ): QualificationEvaluation {
        val passed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var scoreAcc = 50 // Base score

        // 1. Purchase Price Check
        if (property.price <= rules.maxPurchasePrice) {
            passed.add("Price $${formatPrice(property.price)} <= Max $${formatPrice(rules.maxPurchasePrice)}")
            scoreAcc += 10
        } else {
            failed.add("Price $${formatPrice(property.price)} exceeds Max $${formatPrice(rules.maxPurchasePrice)}")
            scoreAcc -= 15
        }

        // 2. Monthly Cash Flow Check
        if (financial.monthlyCashFlow >= rules.minCashFlow) {
            passed.add("Cash Flow $${formatPrice(financial.monthlyCashFlow)}/mo >= Min $${rules.minCashFlow.toInt()}/mo")
            scoreAcc += 15
        } else {
            failed.add("Cash Flow $${formatPrice(financial.monthlyCashFlow)}/mo < Min $${rules.minCashFlow.toInt()}/mo")
            scoreAcc -= 20
        }

        // 3. Cap Rate Check
        if (financial.capRate >= rules.minCapRate) {
            passed.add("Cap Rate ${String.format("%.2f", financial.capRate)}% >= Min ${rules.minCapRate}%")
            scoreAcc += 10
        } else {
            failed.add("Cap Rate ${String.format("%.2f", financial.capRate)}% < Min ${rules.minCapRate}%")
            scoreAcc -= 10
        }

        // 4. DSCR Check
        if (financial.dscr >= rules.minDscr) {
            val dscrText = if (financial.dscr > 50.0) "All-Cash (No Debt)" else String.format("%.2f", financial.dscr)
            passed.add("DSCR $dscrText >= Min ${rules.minDscr}")
            scoreAcc += 10
        } else {
            failed.add("DSCR ${String.format("%.2f", financial.dscr)} < Min ${rules.minDscr}")
            scoreAcc -= 15
        }

        // 5. Cash-on-Cash Return Check
        if (financial.cashOnCashReturn >= rules.minCashOnCash) {
            passed.add("CoC Return ${String.format("%.2f", financial.cashOnCashReturn)}% >= Min ${rules.minCashOnCash}%")
            scoreAcc += 10
        } else {
            failed.add("CoC Return ${String.format("%.2f", financial.cashOnCashReturn)}% < Min ${rules.minCashOnCash}%")
            scoreAcc -= 10
        }

        // 6. Location Check
        val allowedLocList = rules.allowedLocations.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val matchesLoc = allowedLocList.isEmpty() || allowedLocList.any { 
            property.city.lowercase().contains(it) || property.state.lowercase().contains(it) || property.address.lowercase().contains(it)
        }
        if (matchesLoc) {
            passed.add("Location ${property.city}, ${property.state} is in target markets")
            scoreAcc += 5
        } else {
            failed.add("Location ${property.city}, ${property.state} not in target markets")
            scoreAcc -= 10
        }

        // 7. Property Type Check
        val allowedTypes = rules.allowedPropertyTypes.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val matchesType = allowedTypes.isEmpty() || allowedTypes.any { property.propertyType.lowercase().contains(it) }
        if (matchesType) {
            passed.add("Property type ${property.propertyType} allowed")
            scoreAcc += 5
        } else {
            failed.add("Property type ${property.propertyType} not in allowed types")
            scoreAcc -= 10
        }

        // 8. Estimated Rent Check
        if (financial.input.monthlyRent >= rules.minEstimatedRent) {
            passed.add("Rent $${formatPrice(financial.input.monthlyRent)}/mo >= Min $${rules.minEstimatedRent.toInt()}/mo")
            scoreAcc += 5
        } else {
            failed.add("Rent $${formatPrice(financial.input.monthlyRent)}/mo < Min $${rules.minEstimatedRent.toInt()}/mo")
            scoreAcc -= 10
        }

        // 9. Max Renovation Cost Check
        if (financial.input.renovationCost <= rules.maxRenovationCost) {
            passed.add("Reno $${formatPrice(financial.input.renovationCost)} <= Max $${formatPrice(rules.maxRenovationCost)}")
            scoreAcc += 5
        } else {
            failed.add("Reno $${formatPrice(financial.input.renovationCost)} exceeds Max $${formatPrice(rules.maxRenovationCost)}")
            scoreAcc -= 10
        }

        // 10. Max Risk Score Check (estimated property risk 0-100 based on age and cap rate stability)
        val estimatedRisk = ((2025 - property.yearBuilt).coerceIn(0, 50) + if (financial.dscr < 1.3) 20 else 0).coerceIn(5, 80)
        if (estimatedRisk <= rules.maxRiskScore) {
            passed.add("Risk Score $estimatedRisk <= Max ${rules.maxRiskScore}")
            scoreAcc += 5
        } else {
            failed.add("Risk Score $estimatedRisk > Max ${rules.maxRiskScore}")
            scoreAcc -= 10
        }

        val finalScore = scoreAcc.coerceIn(0, 100)
        // A deal qualifies if all required investment checks pass
        val isQualified = failed.isEmpty()

        // Target Offer Calculation (e.g. discount off list price according to rules)
        val discount = (rules.offerDiscountPercent / 100.0).coerceIn(0.0, 0.40)
        val suggestedOffer = property.price * (1.0 - discount)

        val summary = if (isQualified) {
            "Qualified Deal (Score $finalScore/100): Strong cash flow of $${formatPrice(financial.monthlyCashFlow)}/mo and Cap Rate of ${String.format("%.1f", financial.capRate)}%."
        } else {
            "Did not qualify (Score $finalScore/100): ${failed.firstOrNull() ?: "Fails core investment criteria"}."
        }

        return QualificationEvaluation(
            isQualified = isQualified,
            score = finalScore,
            passedChecks = passed,
            failedChecks = failed,
            suggestedOfferPrice = suggestedOffer,
            summary = summary
        )
    }

    private fun formatPrice(amount: Double): String {
        return "%,.0f".format(amount)
    }
}
