package com.example.domain.crm

import kotlin.math.roundToInt

/** How much work the property needs, in wholesale terms. */
enum class RehabSeverity(val tier: Int) {
    /** Nothing known about condition. Never scored as if it were good news. */
    UNKNOWN(0),
    /** Move-in ready; no work needed to rent or resell. */
    NONE(1),
    /** Cosmetic only: paint, carpet, landscaping. */
    MINOR(2),
    /** Real repairs: roof, HVAC, kitchen, baths — still financeable conventionally. */
    MODERATE(3),
    /** Multiple systems and/or structural work; hard-money or cash only. */
    MAJOR(4),
    /** Gut: down to the studs / uninhabitable. */
    GUT(5);

    val isWorkNeeded: Boolean get() = this != NONE && this != UNKNOWN

    /** Beyond this severity, an inspection contingency is strongly advised. */
    val requiresInspection: Boolean get() = this == MAJOR || this == GUT
}

/**
 * Condition bucket of the subject property.
 *
 * Kept coarse on purpose: the CRM only needs to know (a) whether the deal has a repair problem and
 * (b) roughly how big it is, because that is what drives the maximum allowable offer. The precise
 * scope of work belongs to the underwriting/estimate side of the app, which is referenced (not
 * duplicated) here.
 */
enum class PropertyCondition(val severity: RehabSeverity, val typicalRehabPerSqFtUsd: ClosedFloatingPointRange<Double>) {
    UNKNOWN(RehabSeverity.UNKNOWN, 0.0..0.0),
    TURNKEY(RehabSeverity.NONE, 0.0..5.0),
    RENT_READY(RehabSeverity.NONE, 0.0..10.0),
    COSMETIC_UPDATES(RehabSeverity.MINOR, 5.0..15.0),
    MINOR_REHAB(RehabSeverity.MINOR, 10.0..25.0),
    MODERATE_REHAB(RehabSeverity.MODERATE, 20.0..45.0),
    MAJOR_REHAB(RehabSeverity.MAJOR, 40.0..80.0),
    DISTRESSED(RehabSeverity.GUT, 60.0..150.0),
    NEW_CONSTRUCTION(RehabSeverity.NONE, 0.0..5.0);

    val isKnown: Boolean get() = this != UNKNOWN

    val isWorkNeeded: Boolean get() = severity.isWorkNeeded

    /** Wholesale-friendly: a discounted, work-needed property is where the spread comes from. */
    val isDistressed: Boolean get() = severity.tier >= RehabSeverity.MAJOR.tier

    /** Coarse repair estimate when only square footage and the condition bucket are known. */
    fun estimateRepairCostUsd(squareFeet: Int?): Double? {
        if (!isKnown) return null
        if (!isWorkNeeded) return 0.0
        if (squareFeet == null || squareFeet <= 0) return null
        val midpoint = (typicalRehabPerSqFtUsd.start + typicalRehabPerSqFtUsd.endInclusive) / 2.0
        return (midpoint * squareFeet).roundToInt().toDouble()
    }

    companion object {
        /** Ordered from best to worst; used to merge two assessments deterministically. */
        val WORST_FIRST_ORDER: List<PropertyCondition> = listOf(
            DISTRESSED, MAJOR_REHAB, MODERATE_REHAB, MINOR_REHAB, COSMETIC_UPDATES,
            UNKNOWN, NEW_CONSTRUCTION, RENT_READY, TURNKEY
        )
    }
}

/** Where a condition assessment came from. Trust level, not scoring, is what differs. */
enum class ConditionEvidenceSource(val isVerified: Boolean) {
    /** The seller described the condition. */
    SELLER_STATED(isVerified = false),
    /** An agent drove by and looked at the exterior. */
    DRIVE_BY(isVerified = false),
    /** Photos and/or a video walkthrough were reviewed. */
    PHOTOS(isVerified = false),
    /** A third-party (inspector, contractor, appraiser) reported it. */
    INSPECTION(isVerified = true),
    /** Deterministically inferred by the app from other facts. */
    SYSTEM_INFERRED(isVerified = false),
    /** Nothing is known yet. */
    NONE(isVerified = false)
}

/**
 * A condition assessment made at a point in time.
 *
 * Condition is a fact about a property that changes (a roof gets replaced, a tenant trashes the
 * place), so it is timestamped and versioned rather than being a mutable field on the lead.
 */
data class PropertyConditionAssessment(
    val condition: PropertyCondition,
    val source: ConditionEvidenceSource,
    val assessedAtEpochMillis: Long,
    val assessedBy: String,
    val squareFeet: Int? = null,
    /** Explicit repair estimate when one exists; otherwise derived from condition × square feet. */
    val rehabEstimateUsd: Double? = null,
    /** Indicators observed. Free-form, but the classifier consumes the fixed enum. */
    val indicators: Set<ConditionIndicator> = emptySet(),
    val notes: String? = null,
    /** Photos, inspection reports, vendor payloads backing this assessment. */
    val evidenceRefs: List<String> = emptyList()
) {
    init {
        require(assessedAtEpochMillis > 0L) { "Condition assessedAtEpochMillis must be positive" }
        require(assessedBy.isNotBlank()) { "Condition assessment must record who assessed it" }
        require(squareFeet == null || squareFeet > 0) { "Condition squareFeet must be positive when provided" }
        require(rehabEstimateUsd == null || (rehabEstimateUsd.isFinite() && rehabEstimateUsd >= 0.0)) {
            "Condition rehabEstimateUsd must be finite and non-negative"
        }
        require(notes == null || notes.length <= MAX_NOTES_LENGTH) { "Condition notes must be at most $MAX_NOTES_LENGTH characters" }
        require(source != ConditionEvidenceSource.NONE || condition == PropertyCondition.UNKNOWN) {
            "A known condition requires an evidence source"
        }
    }

    val severity: RehabSeverity get() = condition.severity

    val isKnown: Boolean get() = condition.isKnown

    /** True when the assessment is backed by a third party rather than an opinion. */
    val isVerified: Boolean get() = source.isVerified

    /** Repair cost actually used by the qualification math: explicit estimate wins over the bucket. */
    val effectiveRehabEstimateUsd: Double?
        get() = rehabEstimateUsd ?: condition.estimateRepairCostUsd(squareFeet)

    fun rehabPerSqFtUsd(): Double? {
        val estimate = effectiveRehabEstimateUsd ?: return null
        val sqft = squareFeet ?: return null
        return estimate / sqft
    }

    /** Merges two assessments by keeping the worst condition (deterministic, order-independent). */
    fun worstOf(other: PropertyConditionAssessment): PropertyConditionAssessment =
        if (PropertyCondition.WORST_FIRST_ORDER.indexOf(condition) <=
            PropertyCondition.WORST_FIRST_ORDER.indexOf(other.condition)
        ) this else other

    fun describe(): String = buildString {
        append(condition.name).append(" (").append(severity.name).append(", ").append(source.name)
        if (isVerified) append(", verified")
        append(')')
        effectiveRehabEstimateUsd?.let { append(" rehab=\$").append(String.format(java.util.Locale.US, "%.0f", it)) }
    }

    companion object {
        const val MAX_NOTES_LENGTH = 1_000
    }
}

/** Physical observations that force a condition floor. */
enum class ConditionIndicator(val isPositive: Boolean, val note: String) {
    ROOF_FAILURE(isPositive = false, note = "roof at or past end of life"),
    HVAC_FAILURE(isPositive = false, note = "heating/cooling not functional"),
    PLUMBING_FAILURE(isPositive = false, note = "supply or drain failure"),
    ELECTRICAL_HAZARD(isPositive = false, note = "unsafe or obsolete electrical"),
    FOUNDATION_ISSUES(isPositive = false, note = "structural movement or settlement"),
    WATER_INTRUSION(isPositive = false, note = "active or historic water entry"),
    MOLD(isPositive = false, note = "mold present"),
    FIRE_DAMAGE(isPositive = false, note = "fire or smoke damage"),
    VANDALISM(isPositive = false, note = "vandalized / stripped systems"),
    HOARDING(isPositive = false, note = "hoarding cleanup required"),
    UNIT_INTERIOR_GUTTED(isPositive = false, note = "interior removed / uninhabitable"),
    UPDATED_KITCHEN(isPositive = true, note = "kitchen recently updated"),
    UPDATED_BATH(isPositive = true, note = "bathrooms recently updated"),
    NEW_ROOF(isPositive = true, note = "roof replaced recently"),
    NEW_HVAC(isPositive = true, note = "HVAC replaced recently"),
    NEW_WINDOWS(isPositive = true, note = "windows replaced recently");

    companion object {
        val NEGATIVE: List<ConditionIndicator> = entries.filter { !it.isPositive }
        val POSITIVE: List<ConditionIndicator> = entries.filter { it.isPositive }
    }
}

/**
 * Deterministic condition classifier.
 *
 * Precedence is "worst evidence wins": a single gutted unit outranks any number of new windows, and
 * the result never depends on the order the indicators were entered. Positive-only evidence can lift
 * a property to [PropertyCondition.RENT_READY] but never to [PropertyCondition.TURNKEY]: without an
 * inspection, no one has verified everything else in the house.
 */
object PropertyConditionClassifier {

    fun classify(
        indicators: Set<ConditionIndicator>,
        policy: ConditionClassificationPolicy = ConditionClassificationPolicy.DEFAULT
    ): PropertyCondition {
        if (indicators.isEmpty()) return PropertyCondition.UNKNOWN
        val floors = indicators.map { policy.floorFor(it) }
        val worst = floors.reduce { first, second -> moreSevere(first, second) }
        // Positive-only evidence cannot claim a better condition than the policy ceiling: without an
        // inspection nobody has verified the parts of the house that were not photographed.
        return if (indicators.any { !it.isPositive }) {
            worst
        } else {
            lessSevere(worst, policy.positiveOnlyCeiling)
        }
    }

    /** Convenience: classify and wrap the result in a timestamped assessment. */
    fun assess(
        indicators: Set<ConditionIndicator>,
        source: ConditionEvidenceSource,
        assessedAtEpochMillis: Long,
        assessedBy: String,
        squareFeet: Int? = null,
        policy: ConditionClassificationPolicy = ConditionClassificationPolicy.DEFAULT,
        notes: String? = null,
        evidenceRefs: List<String> = emptyList(),
        rehabEstimateUsd: Double? = null
    ): PropertyConditionAssessment = PropertyConditionAssessment(
        condition = classify(indicators, policy),
        source = source,
        assessedAtEpochMillis = assessedAtEpochMillis,
        assessedBy = assessedBy,
        squareFeet = squareFeet,
        rehabEstimateUsd = rehabEstimateUsd,
        indicators = indicators,
        notes = notes,
        evidenceRefs = evidenceRefs
    )

    /** Worse of two conditions: the one that appears earlier in the worst-first order. */
    fun moreSevere(first: PropertyCondition, second: PropertyCondition): PropertyCondition =
        if (PropertyCondition.WORST_FIRST_ORDER.indexOf(first) <=
            PropertyCondition.WORST_FIRST_ORDER.indexOf(second)
        ) first else second

    /** Better of two conditions: the one that appears later in the worst-first order. */
    fun lessSevere(first: PropertyCondition, second: PropertyCondition): PropertyCondition =
        if (PropertyCondition.WORST_FIRST_ORDER.indexOf(first) >=
            PropertyCondition.WORST_FIRST_ORDER.indexOf(second)
        ) first else second
}

/** Maps observed indicators to the condition floor they imply. Validated so it cannot be inconsistent. */
data class ConditionClassificationPolicy(
    val version: String = DEFAULT_VERSION,
    val indicatorFloors: Map<ConditionIndicator, PropertyCondition> = DEFAULT_FLOORS,
    /** Positive-only evidence cannot claim better than this without an inspection. */
    val positiveOnlyCeiling: PropertyCondition = PropertyCondition.RENT_READY
) {
    init {
        require(version.isNotBlank()) { "Condition policy version must not be blank" }
        require(indicatorFloors.keys == ConditionIndicator.entries.toSet()) {
            "indicatorFloors must define every condition indicator exactly once"
        }
        require(indicatorFloors.values.none { it == PropertyCondition.UNKNOWN }) {
            "An observed indicator cannot map to UNKNOWN"
        }
        require(positiveOnlyCeiling.severity.tier <= RehabSeverity.MODERATE.tier) {
            "positiveOnlyCeiling must be a low-severity condition"
        }
    }

    fun floorFor(indicator: ConditionIndicator): PropertyCondition = indicatorFloors.getValue(indicator)

    companion object {
        const val DEFAULT_VERSION = "condition-v1"
        val DEFAULT_FLOORS: Map<ConditionIndicator, PropertyCondition> = mapOf(
            ConditionIndicator.ROOF_FAILURE to PropertyCondition.MINOR_REHAB,
            ConditionIndicator.HVAC_FAILURE to PropertyCondition.MINOR_REHAB,
            ConditionIndicator.PLUMBING_FAILURE to PropertyCondition.MODERATE_REHAB,
            ConditionIndicator.ELECTRICAL_HAZARD to PropertyCondition.MODERATE_REHAB,
            ConditionIndicator.FOUNDATION_ISSUES to PropertyCondition.MAJOR_REHAB,
            ConditionIndicator.WATER_INTRUSION to PropertyCondition.MODERATE_REHAB,
            ConditionIndicator.MOLD to PropertyCondition.MAJOR_REHAB,
            ConditionIndicator.FIRE_DAMAGE to PropertyCondition.MAJOR_REHAB,
            ConditionIndicator.VANDALISM to PropertyCondition.MAJOR_REHAB,
            ConditionIndicator.HOARDING to PropertyCondition.MODERATE_REHAB,
            ConditionIndicator.UNIT_INTERIOR_GUTTED to PropertyCondition.DISTRESSED,
            ConditionIndicator.UPDATED_KITCHEN to PropertyCondition.COSMETIC_UPDATES,
            ConditionIndicator.UPDATED_BATH to PropertyCondition.COSMETIC_UPDATES,
            ConditionIndicator.NEW_ROOF to PropertyCondition.RENT_READY,
            ConditionIndicator.NEW_HVAC to PropertyCondition.RENT_READY,
            ConditionIndicator.NEW_WINDOWS to PropertyCondition.RENT_READY
        )
        val DEFAULT = ConditionClassificationPolicy()
    }
}
