package com.example.domain.qualification

import com.example.data.local.entity.AutomationRuleEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.finance.FinancialResult
import java.util.Locale

data class QualificationPointAdjustment(
    val passed: Int,
    val failed: Int
)

/** Explicit, versioned rules used to turn qualification checks into a bounded score. */
data class QualificationScoringPolicy(
    val version: String = DEFAULT_VERSION,
    val neutralBaseScore: Int = 50,
    val pointAdjustments: Map<String, QualificationPointAdjustment> = DEFAULT_POINT_ADJUSTMENTS,
    val minimumYearBuilt: Int = 1600,
    val maximumRiskAgeYears: Int = 50,
    val lowDscrRiskThreshold: Double = 1.3,
    val lowDscrRiskPenalty: Int = 20,
    val minimumOfferDiscountPct: Double = 0.0,
    val maximumOfferDiscountPct: Double = 40.0
) {
    init {
        require(version.isNotBlank()) { "Qualification policy version must not be blank" }
        require(neutralBaseScore in -1_000..1_000) { "neutralBaseScore must be within -1000..1000" }
        require(pointAdjustments.keys == CHECK_IDS) { "pointAdjustments must specify every qualification check exactly once" }
        require(pointAdjustments.values.all { it.passed in -1_000..1_000 && it.failed in -1_000..1_000 }) {
            "Qualification point adjustments must be within -1000..1000"
        }
        require(minimumYearBuilt in 1600..3000) { "minimumYearBuilt must be within 1600..3000" }
        require(maximumRiskAgeYears in 0..200) { "maximumRiskAgeYears must be within 0..200" }
        require(lowDscrRiskThreshold.isFinite() && lowDscrRiskThreshold >= 0.0) {
            "lowDscrRiskThreshold must be finite and >= 0"
        }
        require(lowDscrRiskPenalty in 0..1_000) { "lowDscrRiskPenalty must be within 0..1000" }
        require(minimumOfferDiscountPct.isFinite() && maximumOfferDiscountPct.isFinite() &&
            minimumOfferDiscountPct >= 0.0 && maximumOfferDiscountPct <= 100.0 &&
            minimumOfferDiscountPct <= maximumOfferDiscountPct
        ) { "Offer discount bounds must be finite, ordered, and within 0..100" }
    }

    companion object {
        const val DEFAULT_VERSION = "qualification-v2"
        val CHECK_IDS = setOf(
            "purchase_price", "monthly_cash_flow", "cap_rate", "dscr", "cash_on_cash",
            "location", "property_type", "estimated_rent", "renovation_cost", "risk_score"
        )
        val DEFAULT_POINT_ADJUSTMENTS = linkedMapOf(
            "purchase_price" to QualificationPointAdjustment(10, -15),
            "monthly_cash_flow" to QualificationPointAdjustment(15, -20),
            "cap_rate" to QualificationPointAdjustment(10, -10),
            "dscr" to QualificationPointAdjustment(10, -15),
            "cash_on_cash" to QualificationPointAdjustment(10, -10),
            "location" to QualificationPointAdjustment(5, -10),
            "property_type" to QualificationPointAdjustment(5, -10),
            "estimated_rent" to QualificationPointAdjustment(5, -10),
            "renovation_cost" to QualificationPointAdjustment(5, -10),
            "risk_score" to QualificationPointAdjustment(5, -10)
        )
        val DEFAULT = QualificationScoringPolicy()
    }
}

data class QualificationCheckResult(
    val id: String,
    val passed: Boolean,
    /** Stable display form of the observed value (invalid/non-finite values are identified). */
    val actualValue: String,
    /** Stable display form of the configured cutoff or accepted set. */
    val threshold: String,
    /** Exact points added or subtracted from the raw Qualification Score. */
    val scoreDelta: Int,
    val message: String
)

data class QualificationEvaluation(
    val isQualified: Boolean,
    val score: Int, // 0 to 100, clamped from rawScore
    val passedChecks: List<String>,
    val failedChecks: List<String>,
    val suggestedOfferPrice: Double?,
    val summary: String,
    val checkResults: List<QualificationCheckResult> = emptyList(),
    val rawScore: Int = score,
    val scoreWasClamped: Boolean = false,
    val effectiveOfferDiscountPercent: Double? = null,
    val warnings: List<String> = emptyList(),
    val asOfYear: Int = QualificationEngine.DEFAULT_AS_OF_YEAR,
    val policyVersion: String = QualificationScoringPolicy.DEFAULT_VERSION
)

/**
 * Deterministic qualification policy. Its points remain deliberately separate from the broader
 * Deal Score; every check reports its pass/fail result and exact score delta.
 */
object QualificationEngine {
    const val DEFAULT_AS_OF_YEAR = 2026

    fun evaluate(
        property: PropertyEntity,
        financial: FinancialResult,
        rules: AutomationRuleEntity,
        asOfYear: Int = DEFAULT_AS_OF_YEAR,
        scoringPolicy: QualificationScoringPolicy = QualificationScoringPolicy.DEFAULT
    ): QualificationEvaluation {
        val passed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val checkResults = mutableListOf<QualificationCheckResult>()
        val warnings = mutableListOf<String>()
        var scoreAcc = scoringPolicy.neutralBaseScore // explicit neutral base

        fun record(
            id: String,
            ok: Boolean,
            actual: String,
            threshold: String,
            passMessage: String,
            failMessage: String
        ) {
            val adjustments = scoringPolicy.pointAdjustments.getValue(id)
            val delta = if (ok) adjustments.passed else adjustments.failed
            val message = if (ok) passMessage else failMessage
            scoreAcc += delta
            if (ok) passed += message else failed += message
            checkResults += QualificationCheckResult(id, ok, actual, threshold, delta, message)
        }

        // 1. Purchase price
        val priceValid = property.price.isFinite() && property.price > 0.0 &&
            rules.maxPurchasePrice.isFinite() && rules.maxPurchasePrice >= 0.0
        val priceOk = priceValid && property.price <= rules.maxPurchasePrice
        val pricePassText = "Price ${'$'}${formatPrice(property.price)} <= Max ${'$'}${formatPrice(rules.maxPurchasePrice)}"
        val priceFailText = if (priceValid) {
            "Price ${'$'}${formatPrice(property.price)} exceeds Max ${'$'}${formatPrice(rules.maxPurchasePrice)}"
        } else {
            "Price check unavailable: property price or maximum price is invalid/non-finite"
        }
        record(
            "purchase_price", priceOk,
            actual = if (property.price.isFinite()) formatPrice(property.price) else "non-finite",
            threshold = if (rules.maxPurchasePrice.isFinite()) formatPrice(rules.maxPurchasePrice) else "non-finite",
            passMessage = pricePassText, failMessage = priceFailText
        )

        // 2. Monthly cash flow
        val minCashFlow = rules.minCashFlow
        val cashFlowValid = financial.monthlyCashFlow.isFinite() && minCashFlow.isFinite()
        val cashFlowOk = cashFlowValid && financial.monthlyCashFlow >= minCashFlow
        val cashFlowPassText = "Cash Flow ${'$'}${formatPrice(financial.monthlyCashFlow)}/mo >= Min ${'$'}${formatPrice(minCashFlow)}/mo"
        val cashFlowFailText = if (cashFlowValid) {
            "Cash Flow ${'$'}${formatPrice(financial.monthlyCashFlow)}/mo < Min ${'$'}${formatPrice(minCashFlow)}/mo"
        } else {
            "Cash Flow check unavailable: actual value or minimum is non-finite"
        }
        record(
            "monthly_cash_flow", cashFlowOk,
            actual = if (financial.monthlyCashFlow.isFinite()) formatPrice(financial.monthlyCashFlow) else "non-finite",
            threshold = if (minCashFlow.isFinite()) formatPrice(minCashFlow) else "non-finite",
            passMessage = cashFlowPassText, failMessage = cashFlowFailText
        )

        // 3. Cap rate
        val minCapRate = rules.minCapRate
        val capRateValid = financial.capRate.isFinite() && minCapRate.isFinite()
        val capRateOk = capRateValid && financial.capRate >= minCapRate
        val capRatePassText = "Cap Rate ${formatFixed(financial.capRate, 2)}% >= Min ${formatFixed(minCapRate, 2)}%"
        val capRateFailText = if (capRateValid) {
            "Cap Rate ${formatFixed(financial.capRate, 2)}% < Min ${formatFixed(minCapRate, 2)}%"
        } else {
            "Cap Rate check unavailable: actual value or minimum is non-finite"
        }
        record(
            "cap_rate", capRateOk,
            actual = if (financial.capRate.isFinite()) formatFixed(financial.capRate, 2) else "non-finite",
            threshold = if (minCapRate.isFinite()) formatFixed(minCapRate, 2) else "non-finite",
            passMessage = capRatePassText, failMessage = capRateFailText
        )

        // 4. DSCR
        val minDscr = rules.minDscr
        val dscrValid = financial.dscr.isFinite() && minDscr.isFinite()
        val dscrOk = dscrValid && financial.dscr >= minDscr
        val dscrValue = when {
            !financial.dscr.isFinite() -> "non-finite"
            financial.dscr > 50.0 -> "All-Cash (No Debt)"
            else -> formatFixed(financial.dscr, 2)
        }
        val dscrPassText = "DSCR $dscrValue >= Min ${formatFixed(minDscr, 2)}"
        val dscrFailText = if (dscrValid) {
            "DSCR ${formatFixed(financial.dscr, 2)} < Min ${formatFixed(minDscr, 2)}"
        } else {
            "DSCR check unavailable: actual value or minimum is non-finite"
        }
        record(
            "dscr", dscrOk,
            actual = if (financial.dscr.isFinite()) formatFixed(financial.dscr, 2) else "non-finite",
            threshold = if (minDscr.isFinite()) formatFixed(minDscr, 2) else "non-finite",
            passMessage = dscrPassText, failMessage = dscrFailText
        )

        // 5. Cash-on-cash return
        val minCashOnCash = rules.minCashOnCash
        val cashOnCashValid = financial.cashOnCashReturn.isFinite() && minCashOnCash.isFinite()
        val cashOnCashOk = cashOnCashValid && financial.cashOnCashReturn >= minCashOnCash
        val cashOnCashPassText = "CoC Return ${formatFixed(financial.cashOnCashReturn, 2)}% >= Min ${formatFixed(minCashOnCash, 2)}%"
        val cashOnCashFailText = if (cashOnCashValid) {
            "CoC Return ${formatFixed(financial.cashOnCashReturn, 2)}% < Min ${formatFixed(minCashOnCash, 2)}%"
        } else {
            "CoC Return check unavailable: actual value or minimum is non-finite"
        }
        record(
            "cash_on_cash", cashOnCashOk,
            actual = if (financial.cashOnCashReturn.isFinite()) formatFixed(financial.cashOnCashReturn, 2) else "non-finite",
            threshold = if (minCashOnCash.isFinite()) formatFixed(minCashOnCash, 2) else "non-finite",
            passMessage = cashOnCashPassText, failMessage = cashOnCashFailText
        )

        // 6. Location. Empty allow-lists intentionally mean unrestricted.
        val allowedLocations = rules.allowedLocations.split(",")
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
        val matchesLocation = allowedLocations.isEmpty() || allowedLocations.any {
            property.city.lowercase(Locale.US).contains(it) ||
                property.state.lowercase(Locale.US).contains(it) ||
                property.address.lowercase(Locale.US).contains(it)
        }
        record(
            "location", matchesLocation,
            actual = "${property.city}, ${property.state}",
            threshold = allowedLocations.joinToString(", ").ifEmpty { "unrestricted" },
            passMessage = "Location ${property.city}, ${property.state} is in target markets",
            failMessage = "Location ${property.city}, ${property.state} not in target markets"
        )

        // 7. Property type. Empty allow-lists intentionally mean unrestricted.
        val allowedTypes = rules.allowedPropertyTypes.split(",")
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
        val normalizedType = property.propertyType.lowercase(Locale.US)
        val matchesType = allowedTypes.isEmpty() || allowedTypes.any { normalizedType.contains(it) }
        record(
            "property_type", matchesType,
            actual = property.propertyType,
            threshold = allowedTypes.joinToString(", ").ifEmpty { "unrestricted" },
            passMessage = "Property type ${property.propertyType} allowed",
            failMessage = "Property type ${property.propertyType} not in allowed types"
        )

        // 8. Estimated rent
        val minRent = rules.minEstimatedRent
        val monthlyRent = financial.input.monthlyRent
        val rentValid = monthlyRent.isFinite() && monthlyRent >= 0.0 && minRent.isFinite() && minRent >= 0.0
        val rentOk = rentValid && monthlyRent >= minRent
        val rentPassText = "Rent ${'$'}${formatPrice(monthlyRent)}/mo >= Min ${'$'}${formatPrice(minRent)}/mo"
        val rentFailText = if (rentValid) {
            "Rent ${'$'}${formatPrice(monthlyRent)}/mo < Min ${'$'}${formatPrice(minRent)}/mo"
        } else {
            "Rent check unavailable: actual value or minimum is negative or non-finite"
        }
        record(
            "estimated_rent", rentOk,
            actual = if (monthlyRent.isFinite()) formatPrice(monthlyRent) else "non-finite",
            threshold = if (minRent.isFinite()) formatPrice(minRent) else "non-finite",
            passMessage = rentPassText, failMessage = rentFailText
        )

        // 9. Renovation cost
        val maxRenovation = rules.maxRenovationCost
        val renovationCost = financial.input.renovationCost
        val renovationValid = renovationCost.isFinite() && renovationCost >= 0.0 &&
            maxRenovation.isFinite() && maxRenovation >= 0.0
        val renovationOk = renovationValid && renovationCost <= maxRenovation
        val renovationPassText = "Reno ${'$'}${formatPrice(renovationCost)} <= Max ${'$'}${formatPrice(maxRenovation)}"
        val renovationFailText = if (renovationValid) {
            "Reno ${'$'}${formatPrice(renovationCost)} exceeds Max ${'$'}${formatPrice(maxRenovation)}"
        } else {
            "Renovation check unavailable: actual value or maximum is invalid/non-finite"
        }
        record(
            "renovation_cost", renovationOk,
            actual = if (renovationCost.isFinite()) formatPrice(renovationCost) else "non-finite",
            threshold = if (maxRenovation.isFinite()) formatPrice(maxRenovation) else "non-finite",
            passMessage = renovationPassText, failMessage = renovationFailText
        )

        // 10. Risk (lower is safer). Long arithmetic prevents Int overflow for hostile years.
        val yearBuilt = property.yearBuilt
        val dscrForRisk = financial.dscr
        val riskInputsValid = yearBuilt >= scoringPolicy.minimumYearBuilt && yearBuilt <= asOfYear && dscrForRisk.isFinite() &&
            rules.maxRiskScore in 0..100
        val estimatedRisk = if (riskInputsValid) {
            val age = (asOfYear.toLong() - yearBuilt.toLong())
                .coerceIn(0L, scoringPolicy.maximumRiskAgeYears.toLong()).toInt()
            (age + if (dscrForRisk < scoringPolicy.lowDscrRiskThreshold) scoringPolicy.lowDscrRiskPenalty else 0)
                .coerceIn(5, 80)
        } else {
            null
        }
        val riskOk = estimatedRisk != null && estimatedRisk <= rules.maxRiskScore
        val riskPassText = if (estimatedRisk != null) {
            "Risk Score $estimatedRisk <= Max ${rules.maxRiskScore}"
        } else {
            "Risk check unavailable: yearBuilt, DSCR, or maxRiskScore is invalid"
        }
        val riskFailText = if (estimatedRisk != null) {
            "Risk Score $estimatedRisk > Max ${rules.maxRiskScore}"
        } else {
            "Risk check unavailable: yearBuilt, DSCR, or maxRiskScore is invalid"
        }
        record(
            "risk_score", riskOk,
            actual = estimatedRisk?.toString() ?: "unavailable",
            threshold = rules.maxRiskScore.toString(),
            passMessage = riskPassText, failMessage = riskFailText
        )

        val rawScore = scoreAcc
        val finalScore = rawScore.coerceIn(0, 100)
        val isQualified = failed.isEmpty()

        // Invalid discounts/prices produce no offer recommendation; there is no synthetic fallback.
        val rawDiscount = rules.offerDiscountPercent
        val effectiveDiscount = rawDiscount.takeIf { it.isFinite() }
            ?.coerceIn(scoringPolicy.minimumOfferDiscountPct, scoringPolicy.maximumOfferDiscountPct)
        if (effectiveDiscount == null) {
            warnings += "Offer discount is non-finite; suggested offer is unavailable"
        } else if (effectiveDiscount != rawDiscount) {
            warnings += "Offer discount ${formatFixed(rawDiscount, 2)}% clamped to ${formatFixed(effectiveDiscount, 2)}% (allowed ${formatFixed(scoringPolicy.minimumOfferDiscountPct, 2)}..${formatFixed(scoringPolicy.maximumOfferDiscountPct, 2)}%)"
        }
        val suggestedOfferPrice = if (property.price.isFinite() && property.price > 0.0 && effectiveDiscount != null) {
            property.price * (1.0 - effectiveDiscount / 100.0)
        } else {
            warnings += "Suggested offer unavailable: property price must be finite and positive"
            null
        }

        val summary = if (isQualified) {
            "Qualified Deal (Score $finalScore/100): Strong cash flow of ${'$'}${formatPrice(financial.monthlyCashFlow)}/mo and Cap Rate of ${formatFixed(financial.capRate, 1)}%."
        } else {
            "Did not qualify (Score $finalScore/100): ${failed.firstOrNull() ?: "Fails core investment criteria"}."
        }

        return QualificationEvaluation(
            isQualified = isQualified,
            score = finalScore,
            passedChecks = passed,
            failedChecks = failed,
            suggestedOfferPrice = suggestedOfferPrice,
            summary = summary,
            checkResults = checkResults,
            rawScore = rawScore,
            scoreWasClamped = rawScore != finalScore,
            effectiveOfferDiscountPercent = effectiveDiscount,
            warnings = warnings,
            asOfYear = asOfYear,
            policyVersion = scoringPolicy.version
        )
    }

    private fun formatPrice(amount: Double): String =
        if (amount.isFinite()) String.format(Locale.US, "%,.0f", amount) else "non-finite"

    private fun formatFixed(value: Double, decimals: Int): String =
        if (value.isFinite()) String.format(Locale.US, "%.${decimals}f", value) else "non-finite"
}
