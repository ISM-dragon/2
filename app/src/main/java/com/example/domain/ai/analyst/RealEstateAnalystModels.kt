package com.example.domain.ai.analyst

/** Epistemic status for every narrative claim returned by the analyst. */
enum class AnalystClaimType {
    FACT,
    ESTIMATE,
    INFERENCE,
    UNKNOWN
}

enum class DueDiligencePriority {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Provenance for one allow-listed input value. IDs are local to a single analysis request; they are
 * not database primary keys and are the only references the model is allowed to cite.
 */
data class AnalystEvidence(
    val id: String,
    val source: AnalystEvidenceSource,
    val field: String,
    val asOfEpochMillis: Long? = null
)

enum class AnalystEvidenceSource {
    PROPERTY_RECORD,
    MARKET_DATA_RECORD,
    MARKET_ESTIMATE,
    RENT_ESTIMATE,
    TAX_RECORD,
    COMPARABLE_RECORD,
    DETERMINISTIC_FINANCIAL_ENGINE
}

/** A value is either present with a matching evidence ID, or explicitly absent. */
data class AnalystInputValue<T : Any>(
    val value: T? = null,
    val evidenceRef: String? = null
) {
    init {
        require((value == null) == (evidenceRef == null)) {
            "An analyst input value and its evidence reference must either both be present or both be absent."
        }
        require(evidenceRef == null || evidenceRef.isNotBlank()) {
            "Evidence references cannot be blank."
        }
    }
}

data class AnalystPropertyInput(
    val city: AnalystInputValue<String> = AnalystInputValue(),
    val state: AnalystInputValue<String> = AnalystInputValue(),
    val zipCode: AnalystInputValue<String> = AnalystInputValue(),
    val sourceType: AnalystInputValue<String> = AnalystInputValue(),
    val listingStatus: AnalystInputValue<String> = AnalystInputValue(),
    val askingPriceUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val propertyType: AnalystInputValue<String> = AnalystInputValue(),
    val bedrooms: AnalystInputValue<Int> = AnalystInputValue(),
    val bathrooms: AnalystInputValue<Double> = AnalystInputValue(),
    val squareFeet: AnalystInputValue<Int> = AnalystInputValue(),
    val yearBuilt: AnalystInputValue<Int> = AnalystInputValue(),
    val lotSizeSquareFeet: AnalystInputValue<Int> = AnalystInputValue()
)

data class AnalystMarketInput(
    val estimatedValueUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val medianAreaPriceUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val pricePerSquareFootUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val averageDaysOnMarket: AnalystInputValue<Int> = AnalystInputValue(),
    val neighborhoodAppreciationRatePct: AnalystInputValue<Double> = AnalystInputValue(),
    val marketDemand: AnalystInputValue<String> = AnalystInputValue()
)

data class AnalystRentInput(
    val estimatedMonthlyRentUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val rangeLowUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val rangeHighUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val sourceConfidence: AnalystInputValue<Double> = AnalystInputValue()
)

data class AnalystTaxInput(
    val annualTaxUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val assessmentYear: AnalystInputValue<Int> = AnalystInputValue(),
    val assessedValueUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val delinquent: AnalystInputValue<Boolean> = AnalystInputValue()
)

/**
 * Deterministic outputs produced by the app's financial engine. These are read-only context for
 * qualitative interpretation; the analyst response has no financial-metric output fields.
 */
data class AnalystFinancialMetricsInput(
    val annualNoiUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val monthlyCashFlowUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val annualCashFlowUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val capRatePct: AnalystInputValue<Double> = AnalystInputValue(),
    val cashOnCashReturnPct: AnalystInputValue<Double> = AnalystInputValue(),
    val dscr: AnalystInputValue<Double> = AnalystInputValue(),
    val breakEvenOccupancyPct: AnalystInputValue<Double> = AnalystInputValue(),
    val totalCashRequiredUsd: AnalystInputValue<Double> = AnalystInputValue()
)

data class AnalystComparableInput(
    val address: AnalystInputValue<String> = AnalystInputValue(),
    val salePriceUsd: AnalystInputValue<Double> = AnalystInputValue(),
    val bedrooms: AnalystInputValue<Int> = AnalystInputValue(),
    val bathrooms: AnalystInputValue<Double> = AnalystInputValue(),
    val squareFeet: AnalystInputValue<Int> = AnalystInputValue(),
    val distanceMiles: AnalystInputValue<Double> = AnalystInputValue(),
    val saleDate: AnalystInputValue<String> = AnalystInputValue()
)

/**
 * Deliberately small, per-property analyst payload. Do not replace with a Room entity, DAO result,
 * or database export. The input factory limits comparables and excludes descriptions, images,
 * other properties, chat history, API configuration and internal database IDs.
 */
data class RealEstateAnalystInput(
    val subject: AnalystPropertyInput,
    val market: AnalystMarketInput = AnalystMarketInput(),
    val rent: AnalystRentInput = AnalystRentInput(),
    val taxes: AnalystTaxInput = AnalystTaxInput(),
    val deterministicFinancialMetrics: AnalystFinancialMetricsInput = AnalystFinancialMetricsInput(),
    val comparables: List<AnalystComparableInput> = emptyList(),
    val evidence: List<AnalystEvidence> = emptyList(),
    val schemaVersion: String = RealEstateAnalystInputSchema.VERSION
) {
    init {
        require(evidence.map { it.id }.distinct().size == evidence.size) {
            "Analyst input evidence IDs must be unique."
        }
        require(comparables.size <= RealEstateAnalystInputSchema.MAX_COMPARABLES) {
            "Analyst input may contain at most ${RealEstateAnalystInputSchema.MAX_COMPARABLES} comparable records."
        }
        val evidenceIds = evidence.mapTo(mutableSetOf()) { it.id }
        allInputEvidenceRefs().forEach { evidenceRef ->
            require(evidenceRef in evidenceIds) { "Input refers to unknown evidence: $evidenceRef" }
        }
    }

    fun evidenceById(): Map<String, AnalystEvidence> = evidence.associateBy { it.id }

    private fun allInputEvidenceRefs(): List<String> = buildList {
        subject.run {
            addAll(listOf<AnalystInputValue<*>>(city, state, zipCode, sourceType, listingStatus, askingPriceUsd, propertyType,
                bedrooms, bathrooms, squareFeet, yearBuilt, lotSizeSquareFeet).mapNotNull { it.evidenceRef })
        }
        market.run {
            addAll(listOf<AnalystInputValue<*>>(estimatedValueUsd, medianAreaPriceUsd, pricePerSquareFootUsd,
                averageDaysOnMarket, neighborhoodAppreciationRatePct, marketDemand).mapNotNull { it.evidenceRef })
        }
        rent.run {
            addAll(listOf<AnalystInputValue<*>>(estimatedMonthlyRentUsd, rangeLowUsd, rangeHighUsd, sourceConfidence).mapNotNull { it.evidenceRef })
        }
        taxes.run {
            addAll(listOf<AnalystInputValue<*>>(annualTaxUsd, assessmentYear, assessedValueUsd, delinquent).mapNotNull { it.evidenceRef })
        }
        deterministicFinancialMetrics.run {
            addAll(listOf<AnalystInputValue<*>>(annualNoiUsd, monthlyCashFlowUsd, annualCashFlowUsd, capRatePct,
                cashOnCashReturnPct, dscr, breakEvenOccupancyPct, totalCashRequiredUsd).mapNotNull { it.evidenceRef })
        }
        comparables.forEach { comparable ->
            addAll(listOf<AnalystInputValue<*>>(comparable.address, comparable.salePriceUsd, comparable.bedrooms,
                comparable.bathrooms, comparable.squareFeet, comparable.distanceMiles,
                comparable.saleDate).mapNotNull { it.evidenceRef })
        }
    }
}

/** One evidence-backed statement. Confidence is epistemic confidence, never an investment score. */
data class AnalystClaim(
    val classification: AnalystClaimType,
    val statement: String,
    val evidenceRefs: List<String>,
    val confidence: Double
)

data class AnalystDueDiligenceQuestion(
    val question: String,
    val priority: DueDiligencePriority,
    val basis: AnalystClaim
)

data class RealEstateAnalystOutput(
    val schemaVersion: String,
    val investmentThesis: AnalystClaim,
    val strengths: List<AnalystClaim>,
    val risks: List<AnalystClaim>,
    val redFlags: List<AnalystClaim>,
    val unknowns: List<AnalystClaim>,
    val recommendedStrategy: AnalystClaim,
    val dueDiligenceQuestions: List<AnalystDueDiligenceQuestion>
)

sealed class RealEstateAnalystResult {
    data class Success(
        val analysis: RealEstateAnalystOutput,
        val attempts: Int
    ) : RealEstateAnalystResult()

    data class Failure(
        val message: String,
        val attempts: Int,
        val validationErrors: List<String> = emptyList()
    ) : RealEstateAnalystResult()
}

object RealEstateAnalystInputSchema {
    const val VERSION = "1.0"
    const val MAX_COMPARABLES = 5
}
