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

private val ANALYST_EVIDENCE_ID_PATTERN = Regex("[a-z][a-z0-9._-]{0,119}")
private val ANALYST_EVIDENCE_FIELD_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

/**
 * Provenance for one allow-listed input value. IDs are local to a single analysis request; they are
 * not database primary keys and are the only references the model is allowed to cite. ID and field
 * names are intentionally constrained to fixed, non-natural-language tokens so metadata cannot be
 * used as a second prompt-injection surface.
 */
data class AnalystEvidence(
    val id: String,
    val source: AnalystEvidenceSource,
    val field: String,
    val asOfEpochMillis: Long? = null
) {
    init {
        require(ANALYST_EVIDENCE_ID_PATTERN.matches(id)) {
            "Analyst evidence IDs must be short lowercase identifier tokens."
        }
        require(ANALYST_EVIDENCE_FIELD_PATTERN.matches(field)) {
            "Analyst evidence field names must be short identifier tokens."
        }
        require(asOfEpochMillis == null || asOfEpochMillis >= 0L) {
            "Analyst evidence timestamps cannot be negative."
        }
    }
}

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
) {
    init {
        sourceConfidence.value?.let { confidence ->
            require(confidence.isFinite() && confidence in 0.0..100.0) {
                "Recorded rent-source confidence must be finite and within the stored 0-to-100 range."
            }
        }
    }
}

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
 *
 * The contract keeps the evidence packet compact and deliberate by construction: every evidence
 * item must be referenced by at least one input value, every value must reference supplied
 * evidence, text values are length-capped and must already be free of control, invisible, and
 * code-fence characters (see [AnalystTextSanitizer]), and the evidence list itself is bounded.
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
        require(schemaVersion == RealEstateAnalystInputSchema.VERSION) {
            "Unsupported analyst input schema version: $schemaVersion"
        }
        require(evidence.map { it.id }.distinct().size == evidence.size) {
            "Analyst input evidence IDs must be unique."
        }
        require(comparables.size <= RealEstateAnalystInputSchema.MAX_COMPARABLES) {
            "Analyst input may contain at most ${RealEstateAnalystInputSchema.MAX_COMPARABLES} comparable records."
        }
        require(evidence.size <= RealEstateAnalystInputSchema.MAX_EVIDENCE_ITEMS) {
            "Analyst input evidence packet may contain at most " +
                "${RealEstateAnalystInputSchema.MAX_EVIDENCE_ITEMS} items; do not attach the database."
        }

        val bindings = allInputBindings()
        bindings.forEach { binding ->
            val value = binding.input.value
            if (value is String) {
                require(value.isNotBlank()) { "Present analyst input text cannot be blank; use an explicit null gap." }
                require(value.length <= RealEstateAnalystInputSchema.MAX_TEXT_VALUE_LENGTH) {
                    "Analyst input text values may not exceed " +
                        "${RealEstateAnalystInputSchema.MAX_TEXT_VALUE_LENGTH} characters."
                }
                require(AnalystTextSanitizer.isModelBoundarySafe(value)) {
                    "Analyst input text must be sanitized before construction: no control, invisible " +
                        "formatting, code-fence, or model-token characters are allowed across the boundary."
                }
                require(!AnalystTextSanitizer.containsPromptInjectionPattern(value)) {
                    "Instruction-like or credential-exfiltration text is not admissible as analyst input evidence."
                }
            }
            if (value is Double) {
                require(value.isFinite()) { "Analyst input numbers must be finite." }
            }
        }

        val evidenceById = evidence.associateBy { it.id }
        val referencedIds = bindings.mapNotNull { it.input.evidenceRef }
        require(referencedIds.distinct().size == referencedIds.size) {
            "Each analyst input value must have its own evidence identifier."
        }
        bindings.forEach { binding ->
            val evidenceRef = binding.input.evidenceRef ?: return@forEach
            val item = evidenceById[evidenceRef]
                ?: throw IllegalArgumentException("Input refers to unknown evidence: $evidenceRef")
            require(item.field == binding.expectedField && item.source == binding.expectedSource) {
                "Evidence $evidenceRef does not match the input field and source for ${binding.expectedField}."
            }
        }
        val referencedIdsSet = referencedIds.toSet()
        evidence.forEach { item ->
            require(item.id in referencedIdsSet) {
                "Evidence packet must not carry unreferenced items: ${item.id}"
            }
        }
    }

    fun evidenceById(): Map<String, AnalystEvidence> = evidence.associateBy { it.id }

    private data class InputBinding(
        val input: AnalystInputValue<*>,
        val expectedField: String,
        val expectedSource: AnalystEvidenceSource
    )

    private fun allInputBindings(): List<InputBinding> = buildList {
        fun <T : Any> bind(
            input: AnalystInputValue<T>,
            field: String,
            source: AnalystEvidenceSource
        ) {
            add(InputBinding(input, field, source))
        }

        with(subject) {
            bind(city, "city", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(state, "state", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(zipCode, "zipCode", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(sourceType, "sourceType", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(listingStatus, "status", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(askingPriceUsd, "askingPriceUsd", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(propertyType, "propertyType", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(bedrooms, "bedrooms", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(bathrooms, "bathrooms", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(squareFeet, "squareFeet", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(yearBuilt, "yearBuilt", AnalystEvidenceSource.PROPERTY_RECORD)
            bind(lotSizeSquareFeet, "lotSizeSquareFeet", AnalystEvidenceSource.PROPERTY_RECORD)
        }
        with(market) {
            bind(estimatedValueUsd, "estimatedValueUsd", AnalystEvidenceSource.MARKET_ESTIMATE)
            bind(medianAreaPriceUsd, "medianAreaPriceUsd", AnalystEvidenceSource.MARKET_ESTIMATE)
            bind(pricePerSquareFootUsd, "pricePerSquareFootUsd", AnalystEvidenceSource.MARKET_ESTIMATE)
            bind(averageDaysOnMarket, "averageDaysOnMarket", AnalystEvidenceSource.MARKET_DATA_RECORD)
            bind(neighborhoodAppreciationRatePct, "neighborhoodAppreciationRatePct", AnalystEvidenceSource.MARKET_ESTIMATE)
            bind(marketDemand, "marketDemand", AnalystEvidenceSource.MARKET_DATA_RECORD)
        }
        with(rent) {
            bind(estimatedMonthlyRentUsd, "estimatedMonthlyRentUsd", AnalystEvidenceSource.RENT_ESTIMATE)
            bind(rangeLowUsd, "rangeLowUsd", AnalystEvidenceSource.RENT_ESTIMATE)
            bind(rangeHighUsd, "rangeHighUsd", AnalystEvidenceSource.RENT_ESTIMATE)
            bind(sourceConfidence, "rentConfidenceScore", AnalystEvidenceSource.RENT_ESTIMATE)
        }
        with(taxes) {
            bind(annualTaxUsd, "annualTaxUsd", AnalystEvidenceSource.TAX_RECORD)
            bind(assessmentYear, "assessmentYear", AnalystEvidenceSource.TAX_RECORD)
            bind(assessedValueUsd, "assessedValueUsd", AnalystEvidenceSource.TAX_RECORD)
            bind(delinquent, "taxDelinquent", AnalystEvidenceSource.TAX_RECORD)
        }
        with(deterministicFinancialMetrics) {
            bind(annualNoiUsd, "noiAnnual", AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE)
            bind(monthlyCashFlowUsd, "monthlyCashFlow", AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE)
            bind(annualCashFlowUsd, "annualCashFlow", AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE)
            bind(capRatePct, "capRate", AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE)
            bind(cashOnCashReturnPct, "cashOnCashReturn", AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE)
            bind(dscr, "dscr", AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE)
            bind(breakEvenOccupancyPct, "breakEvenOccupancyPct", AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE)
            bind(totalCashRequiredUsd, "totalCashRequired", AnalystEvidenceSource.DETERMINISTIC_FINANCIAL_ENGINE)
        }
        comparables.forEachIndexed { index, comparable ->
            val prefix = "comparables.${index + 1}."
            bind(comparable.address, "comparableAddress", AnalystEvidenceSource.COMPARABLE_RECORD)
            bind(comparable.salePriceUsd, "comparablePriceUsd", AnalystEvidenceSource.COMPARABLE_RECORD)
            bind(comparable.bedrooms, "comparableBedrooms", AnalystEvidenceSource.COMPARABLE_RECORD)
            bind(comparable.bathrooms, "comparableBathrooms", AnalystEvidenceSource.COMPARABLE_RECORD)
            bind(comparable.squareFeet, "comparableSquareFeet", AnalystEvidenceSource.COMPARABLE_RECORD)
            bind(comparable.distanceMiles, "comparableDistanceMiles", AnalystEvidenceSource.COMPARABLE_RECORD)
            bind(comparable.saleDate, "comparableSaleDate", AnalystEvidenceSource.COMPARABLE_RECORD)
            // The ID prefix is validated here, not stored in natural-language metadata.
            val evidenceRefs = listOf(
                comparable.address, comparable.salePriceUsd, comparable.bedrooms, comparable.bathrooms,
                comparable.squareFeet, comparable.distanceMiles, comparable.saleDate
            ).mapNotNull { it.evidenceRef }
            require(evidenceRefs.all { it.startsWith(prefix) }) {
                "Comparable evidence IDs must be scoped to their own record."
            }
        }
    }
}

/** One evidence-backed statement. Confidence is epistemic confidence, never an investment score. */
data class AnalystClaim(
    val classification: AnalystClaimType,
    val statement: String,
    val evidenceRefs: List<String>,
    val confidence: Double
) {
    init {
        require(statement.isNotEmpty()) { "Analyst claim statements cannot be empty." }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Analyst claim confidence must be a finite number between zero and one."
        }
    }
}

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

    /** Upper bound for one untrusted text value in the packet; the factory caps at 160. */
    const val MAX_TEXT_VALUE_LENGTH = 200

    /**
     * Hard budget for evidence items: exactly enough for every allow-listed field of one property
     * (12 subject + 6 market + 4 rent + 4 tax + 8 deterministic financial + 5 comparables x 7).
     * This is what keeps the packet a deliberate per-property extract instead of a database dump.
     */
    const val MAX_EVIDENCE_ITEMS = 69
}
