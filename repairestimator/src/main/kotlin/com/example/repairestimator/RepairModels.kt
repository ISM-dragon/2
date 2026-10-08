package com.example.repairestimator

/**
 * Public vocabulary, inputs and results of the Repair Estimator.
 *
 * Everything here is plain data. Nothing in this package touches Android, the
 * network, a database, a clock, randomness or an AI model. The rules each type
 * encodes are documented in docs/repair-estimator.md.
 */

/** The strategies the estimator prices for. Each one selects a [StrategyPolicy] in the cost profile. */
enum class RepairStrategy { WHOLESALE, BRRRR, FIX_AND_FLIP }

/**
 * Report categories, in the order they appear in every estimate.
 *
 * [isTrade] categories are physical scope. A trade category with no supplied line is reported as
 * NOT_COVERED: the engine never assumes an uninspected system is in good shape. The other categories
 * are cross-cutting or derived from the trade lines.
 */
enum class RehabCategory(val label: String, val isTrade: Boolean) {
    ROOF("Roof", true),
    HVAC("HVAC", true),
    PLUMBING("Plumbing", true),
    ELECTRICAL("Electrical", true),
    KITCHEN("Kitchen", true),
    BATHROOMS("Bathrooms", true),
    FLOORING("Flooring", true),
    PAINT("Paint", true),
    WINDOWS("Windows", true),
    EXTERIOR("Exterior", true),
    FOUNDATION("Foundation", true),
    PERMITS("Permits", false),
    LABOR("Labor", false),
    CONTINGENCY("Contingency", false),
    UNKNOWN_INSPECTION_REQUIRED("Unknown / inspection required", false),
}

/** Physical condition of a trade item. [UNKNOWN] means not inspected or not supplied, and is never priced. */
enum class ConditionLevel(val label: String) {
    EXCELLENT("Excellent / updated"),
    GOOD("Good / serviceable"),
    FAIR("Fair / worn"),
    POOR("Poor / deteriorated"),
    FAILED("Failed / hazardous"),
    UNKNOWN("Unknown / not inspected"),
}

/**
 * How a caller came to a quantity or condition. Stated, never inferred: the engine records what the
 * caller said and does not upgrade [NOT_STATED] or [ASSUMED] values into observations.
 */
enum class EvidenceBasis { INSPECTED, MEASURED, ESTIMATED, ASSUMED, NOT_STATED }

/** Where a value in an estimate came from. Reported next to every value that the engine used. */
enum class ProvenanceSource {
    /** Supplied by the caller on the scope line. Its [EvidenceBasis] says how it was established. */
    CALLER_INPUT,

    /** A unit cost, amount or percentage the caller supplied, replacing the profile value. */
    CALLER_OVERRIDE,

    /** Value from the built-in placeholder profile. Uncalibrated; replace with local bids. */
    PLACEHOLDER_PROFILE,

    /** Value from a profile the caller defined. */
    CALLER_PROFILE,

    /** Value from the strategy policy of the profile (contingency, finish grade). */
    STRATEGY_POLICY,

    /** A fixed engine rule, such as a unit conversion or a default lump-sum quantity of one. */
    ENGINE_RULE,

    /** The caller did not supply the value and the engine did not invent one. */
    NOT_SUPPLIED,

    /** The provenance field does not apply to this line. */
    NOT_APPLICABLE,
}

/** Whether a cost profile is the built-in uncalibrated placeholder or was defined by the caller. */
enum class ProfileOrigin { PLACEHOLDER_BUILT_IN, CALLER_DEFINED }

/** Outcome of pricing one line. */
enum class LineStatus {
    /** Priced from the profile unit cost, scaled by condition intensity (and finish grade where it applies). */
    PRICED_PROFILE,

    /** Priced from a caller unit cost, amount or hourly rate. */
    PRICED_CALLER_OVERRIDE,

    /** Condition is EXCELLENT (intensity 0): inspected, no work required. Priced at zero. */
    NO_WORK_REQUIRED,

    /** A caller allowance: a stated amount for work that has not been inspected. */
    ALLOWANCE_UNVERIFIED,

    /** A profile rule (general conditions, permit fees, reserve, contingency) that produced an amount. */
    POLICY_RULE,

    /** A profile rule that did not apply, so its amount is zero. The reason is in the formula. */
    POLICY_NOT_APPLICABLE,

    /** No amount. The reasons are listed in [RepairEstimateLine.unpricedReasons]. */
    UNPRICED,
}

/** Why a line has no amount. Unpriced lines are excluded from totals and make the estimate incomplete. */
enum class UnpricedReason {
    /** No condition was supplied. Treated as UNKNOWN: inspection required. */
    CONDITION_NOT_SUPPLIED,

    /** The caller stated the condition is unknown. Inspection required. */
    CONDITION_UNKNOWN,

    /** No quantity was supplied, so there is nothing to multiply. */
    QUANTITY_NOT_SUPPLIED,

    /** An allowance line was supplied without an amount. */
    ALLOWANCE_NOT_SUPPLIED,
}

/** Whether a report category was assessed by the caller. */
enum class CategoryCoverage {
    /** A trade category with at least one supplied line, including zero-cost "no work required" lines. */
    COVERED,

    /** A trade category with no supplied line. Not assessed, and excluded from the total. */
    NOT_COVERED,

    /** A cross-cutting or derived category (permits, labor, contingency, unknown). */
    DERIVED,
}

enum class RepairIssueSeverity { ERROR, WARNING, INFO }

/** Stable machine-readable issue codes. Errors reject the request. Warnings and information do not. */
enum class RepairIssueCode {
    // errors: the request is rejected and no total is produced
    EMPTY_SCOPE,
    TOO_MANY_LINES,
    BLANK_LINE_ID,
    DUPLICATE_LINE_ID,
    RESERVED_LINE_ID,
    POLICY_KIND_NOT_SUPPLIABLE,
    INVALID_QUANTITY,
    INVALID_UNIT_COST,
    UNIT_NOT_ACCEPTED,
    EXPLICIT_AMOUNT_REQUIRED,
    INVALID_CONTINGENCY_OVERRIDE,
    PROFILE_INVALID,

    // warnings and information: the estimate is produced and the issue is reported with it
    PLACEHOLDER_PROFILE,
    FIELD_NOT_APPLICABLE,
    INSPECTION_REQUIRED,
    QUANTITY_NOT_SUPPLIED,
    ALLOWANCE_NOT_SUPPLIED,
    ALLOWANCE_UNVERIFIED,
    OVERRIDE_APPLIED,
    OVERRIDE_ON_EXCELLENT,
    CONDITION_ASSUMED,
    QUANTITY_ASSUMED,
    UNIT_CONVERTED,
    LUMP_SUM_QUANTITY_DEFAULTED,
    PERMIT_REQUIRED_OVERRIDDEN,
    POLICY_SUPPRESSED,
    CONTINGENCY_OVERRIDDEN,
    PERMIT_SCOPE_INCOMPLETE,
    CATEGORY_NOT_COVERED,
}

/**
 * One line of scope as the caller describes it.
 *
 * Only [id] and [kind] are required. Every other field is optional and an absent value is never
 * replaced by a guess:
 *  - [condition] null  -> UNKNOWN: the line is inspection-required and unpriced.
 *  - [quantity] null   -> the line is unpriced, except lump-sum kinds, which default to one lump.
 *  - [unit] null       -> the kind's pricing unit is used. Only conversions listed in [UnitConversions] apply.
 *  - [unitCostOverride] replaces the profile unit cost for this line. For a lump-sum kind it is the
 *    dollar amount per lump. On an UNKNOWN line it becomes an unverified allowance.
 *  - [permitRequired] null -> the kind's default permit trigger is used.
 */
data class RepairScopeLine(
    val id: String,
    val kind: RepairItemKind,
    val condition: ConditionLevel? = null,
    val conditionBasis: EvidenceBasis = EvidenceBasis.NOT_STATED,
    val quantity: Double? = null,
    val unit: MeasureUnit? = null,
    val quantityBasis: EvidenceBasis = EvidenceBasis.NOT_STATED,
    val unitCostOverride: Double? = null,
    val permitRequired: Boolean? = null,
    /** Free-text provenance, for example "inspection report p.4" or "contractor bid 2026-09-30". */
    val evidence: String? = null,
)

/** A complete estimate request. [profile] defaults to the built-in placeholder, which is flagged in every result. */
data class RepairEstimateRequest(
    val strategy: RepairStrategy,
    val lines: List<RepairScopeLine>,
    val profile: CostProfile = BaselineCostProfiles.US_RESIDENTIAL_PLACEHOLDER_2026,
    /** Replaces the strategy's contingency percentage for this estimate only. Range 0 to 100. */
    val contingencyPctOverride: Double? = null,
)

/** Where one value came from. [basis] is set for caller-supplied values, [reference] names the profile entry or rule. */
data class ValueProvenance(
    val source: ProvenanceSource,
    val basis: EvidenceBasis? = null,
    val reference: String? = null,
)

/** The numbers behind a line, so an amount can be re-derived by hand. */
data class LineCalculation(
    val suppliedQuantity: Double?,
    val suppliedUnit: MeasureUnit?,
    /** Quantity in the kind's pricing unit, after any conversion. */
    val pricingQuantity: Double?,
    val pricingUnit: MeasureUnit,
    /** Profile or caller unit cost, per pricing unit, before finish grade and intensity. */
    val baseUnitCost: Double?,
    /** Strategy finish multiplier. Null when the kind is not a finish item or the line is not profile-priced. */
    val finishMultiplier: Double?,
    /** Share of full replacement cost for the condition. Null for caller overrides and non-trade lines. */
    val conditionIntensity: Double?,
    /** baseUnitCost x finishMultiplier x conditionIntensity, unrounded. The amount is pricingQuantity x this, rounded to cents. */
    val effectiveUnitCost: Double?,
    /** The percentage used by a profile rule line (general conditions, permits, reserve, contingency). */
    val rulePercent: Double?,
)

/** Where each input to a line's arithmetic came from. */
data class LineProvenance(
    val condition: ValueProvenance,
    val quantity: ValueProvenance,
    val unitCost: ValueProvenance,
    val conditionIntensity: ValueProvenance,
    val finishMultiplier: ValueProvenance,
    val rule: ValueProvenance,
)

/** One priced (or unpriced) line of the estimate. Policy lines have ids that start with "auto:". */
data class RepairEstimateLine(
    val id: String,
    val category: RehabCategory,
    val kind: RepairItemKind,
    val label: String,
    val status: LineStatus,
    /** Resolved condition. Null for kinds where condition does not apply. */
    val condition: ConditionLevel?,
    /** Dollars rounded to cents, or null when the line is unpriced. */
    val amount: Double?,
    val unpricedReasons: List<UnpricedReason>,
    /** Whether this line triggers a permit fee. Always false for non-trade lines. */
    val permitRequired: Boolean,
    val calculation: LineCalculation,
    val provenance: LineProvenance,
    /** Human-readable arithmetic, for example "18 SQ x $650.00/SQ (...) = $11,700.00". */
    val formula: String,
    /** The caller's free-text evidence note, echoed unchanged. */
    val evidence: String?,
)

/** Per-category roll-up. [pricedSubtotal] excludes unpriced lines. */
data class CategorySummary(
    val category: RehabCategory,
    val coverage: CategoryCoverage,
    val lineCount: Int,
    val pricedSubtotal: Double,
    val unpricedLineCount: Int,
)

/**
 * Totals. The parts reconcile exactly to [totalRehab]:
 * totalRehab = tradeSubtotal + laborSubtotal + permitSubtotal + unknownScopeSubtotal + contingencyAmount.
 * Contingency applies to [contingencyBase] = tradeSubtotal + laborSubtotal.
 */
data class RehabTotals(
    val tradeSubtotal: Double,
    val laborSubtotal: Double,
    val permitSubtotal: Double,
    val unknownScopeSubtotal: Double,
    val contingencyPct: Double,
    val contingencyBase: Double,
    val contingencyAmount: Double,
    val totalRehab: Double,
    val pricedLineCount: Int,
    val unpricedLineCount: Int,
    val allowanceLineCount: Int,
)

/**
 * Whether [RehabTotals.totalRehab] covers the whole scope. It is complete only when every line is priced
 * and every trade category was assessed. Allowances are priced but unverified, so they are listed
 * separately and do not make the estimate incomplete.
 */
data class Completeness(
    val isComplete: Boolean,
    val unpricedLineIds: List<String>,
    val notCoveredCategories: List<RehabCategory>,
    val unverifiedAllowanceLineIds: List<String>,
    val reasons: List<String>,
)

/** One model parameter the estimate used, with its source. Sorted by [key]. */
data class AssumptionRecord(
    val key: String,
    val value: String,
    val source: ProvenanceSource,
    val reference: String?,
)

data class RepairIssue(
    val code: RepairIssueCode,
    val severity: RepairIssueSeverity,
    val message: String,
    val lineId: String? = null,
)

/** Identity of the cost profile that priced an estimate. */
data class ProfileSummary(
    val id: String,
    val version: String,
    val displayName: String,
    val origin: ProfileOrigin,
    val sourceNote: String,
)

/** A complete, reproducible estimate. Two runs over equal inputs produce equal values. */
data class RepairEstimate(
    val specVersion: String,
    val strategy: RepairStrategy,
    val profile: ProfileSummary,
    /** Sorted by category, then item kind, then id. The order does not depend on the order of the input lines. */
    val lines: List<RepairEstimateLine>,
    /** One entry per [RehabCategory], in enum order. */
    val categories: List<CategorySummary>,
    val totals: RehabTotals,
    val completeness: Completeness,
    /** Every model parameter used, sorted by key. */
    val assumptions: List<AssumptionRecord>,
    val issues: List<RepairIssue>,
)

/** Result of [RepairEstimator.estimate]. A rejected request produces no estimate, so no partial total can leak out. */
sealed interface RepairEstimateOutcome {
    data class Estimated(val estimate: RepairEstimate) : RepairEstimateOutcome

    data class Rejected(val issues: List<RepairIssue>) : RepairEstimateOutcome
}
