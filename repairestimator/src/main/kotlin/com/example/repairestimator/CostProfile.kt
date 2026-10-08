package com.example.repairestimator

/**
 * A complete, named set of cost assumptions. Every number the estimator applies comes from here, so
 * changing a profile changes the estimate and nothing else does.
 */
data class CostProfile(
    val id: String,
    val version: String,
    val displayName: String,
    val origin: ProfileOrigin,
    /** Free-text description of where the values come from and what they are meant to represent. */
    val sourceNote: String,
    /** Installed cost per pricing unit, for every profile-priced kind (trade kinds and LABOR_HOURS). */
    val unitCosts: Map<RepairItemKind, UnitCost>,
    /** Share of full replacement cost at each condition level, used for every trade kind unless overridden. */
    val conditionIntensity: ConditionIntensity,
    /** Kind-specific intensity tables. A table replaces [conditionIntensity] for its kind entirely. */
    val kindIntensityOverrides: Map<RepairItemKind, ConditionIntensity> = emptyMap(),
    /** Contingency and finish grade for each strategy. All three strategies are required. */
    val strategyPolicies: Map<RepairStrategy, StrategyPolicy>,
    val permitPolicy: PermitPolicy,
    /** Percentage of priced trade work added for general conditions (haul-away, cleanup, supervision). */
    val generalConditions: PercentRule,
    /** Percentage of priced trade work reserved when a trade line is inspection-required. Zero by default. */
    val unknownConditionReserve: PercentRule,
)

/** Installed cost of one pricing unit of a kind, with the citation or rationale it was taken from. */
data class UnitCost(val amount: Double, val reference: String)

/**
 * Share of full replacement cost required at each condition. 0.0 is no work, 1.0 is full replacement.
 * Values must not decrease as the condition worsens.
 */
data class ConditionIntensity(
    val excellent: Double,
    val good: Double,
    val fair: Double,
    val poor: Double,
    val failed: Double,
    val reference: String,
) {
    /** Intensity for a known condition, or null for [ConditionLevel.UNKNOWN], which has no intensity. */
    fun of(level: ConditionLevel): Double? = when (level) {
        ConditionLevel.EXCELLENT -> excellent
        ConditionLevel.GOOD -> good
        ConditionLevel.FAIR -> fair
        ConditionLevel.POOR -> poor
        ConditionLevel.FAILED -> failed
        ConditionLevel.UNKNOWN -> null
    }
}

/**
 * [contingencyPct] is a percentage of the contingency base. [finishMultiplier] scales finish items
 * (cabinets, countertops, appliances, bathrooms, flooring, interior paint).
 */
data class StrategyPolicy(
    val contingencyPct: Double,
    val finishMultiplier: Double,
    val reference: String,
)

/** Permit fee rule: [pctOfPermitScope] of the priced permit-triggering scope, never less than [minimumFee]. */
data class PermitPolicy(
    val pctOfPermitScope: Double,
    val minimumFee: Double,
    val reference: String,
)

/** A plain percentage rule with its reference. */
data class PercentRule(val pct: Double, val reference: String)

/**
 * Checks a profile before it is used. Every problem is reported, so a caller can fix them all at once.
 * The returned issues are all ERROR severity.
 */
object CostProfileValidator {

    fun validate(profile: CostProfile): List<RepairIssue> {
        val issues = ArrayList<RepairIssue>()

        fun invalid(message: String) {
            issues += RepairIssue(RepairIssueCode.PROFILE_INVALID, RepairIssueSeverity.ERROR, message)
        }

        if (profile.id.isBlank()) invalid("profile id must not be blank")
        if (profile.version.isBlank()) invalid("profile version must not be blank")
        if (profile.displayName.isBlank()) invalid("profile display name must not be blank")

        for (kind in RepairItemKind.PROFILE_PRICED) {
            val unitCost = profile.unitCosts[kind]
            if (unitCost == null) {
                invalid("unit cost missing for ${kind.name}")
                continue
            }
            checkMoney("unit cost for ${kind.name}", unitCost.amount, RepairLimits.MAX_UNIT_COST, issues)
            if (unitCost.reference.isBlank()) invalid("unit cost for ${kind.name} needs a reference")
        }
        for (kind in profile.unitCosts.keys) {
            if (kind !in RepairItemKind.PROFILE_PRICED) {
                invalid("unit cost given for ${kind.name}, which is not priced from the profile")
            }
        }

        checkIntensity("conditionIntensity", profile.conditionIntensity, issues)
        for ((kind, table) in profile.kindIntensityOverrides) {
            if (kind !in RepairItemKind.PROFILE_PRICED || kind.role != KindRole.TRADE) {
                invalid("intensity override given for ${kind.name}, which is not a trade kind")
            } else {
                checkIntensity("intensity override for ${kind.name}", table, issues)
            }
        }

        for (strategy in RepairStrategy.entries) {
            val policy = profile.strategyPolicies[strategy]
            if (policy == null) {
                invalid("strategy policy missing for ${strategy.name}")
                continue
            }
            checkPercent("contingency for ${strategy.name}", policy.contingencyPct, issues)
            if (!policy.finishMultiplier.isFinite() || policy.finishMultiplier <= 0.0 ||
                policy.finishMultiplier > RepairLimits.MAX_FINISH_MULTIPLIER
            ) {
                invalid("finish multiplier for ${strategy.name} must be finite, > 0 and <= ${RepairLimits.MAX_FINISH_MULTIPLIER}")
            }
            if (policy.reference.isBlank()) invalid("strategy policy for ${strategy.name} needs a reference")
        }

        checkPercent("permit fee percentage", profile.permitPolicy.pctOfPermitScope, issues)
        checkMoney("permit minimum fee", profile.permitPolicy.minimumFee, RepairLimits.MAX_UNIT_COST, issues)
        if (profile.permitPolicy.reference.isBlank()) invalid("permit policy needs a reference")

        checkPercent("general conditions percentage", profile.generalConditions.pct, issues)
        if (profile.generalConditions.reference.isBlank()) invalid("general conditions rule needs a reference")

        checkPercent("unknown-condition reserve percentage", profile.unknownConditionReserve.pct, issues)
        if (profile.unknownConditionReserve.reference.isBlank()) invalid("unknown-condition reserve rule needs a reference")

        return issues
    }

    private fun checkMoney(label: String, value: Double, max: Double, issues: MutableList<RepairIssue>) {
        if (!value.isFinite() || value < 0.0 || value > max) {
            issues += RepairIssue(
                RepairIssueCode.PROFILE_INVALID, RepairIssueSeverity.ERROR,
                "$label must be finite, >= 0 and <= $max",
            )
        }
    }

    private fun checkPercent(label: String, value: Double, issues: MutableList<RepairIssue>) {
        if (!value.isFinite() || value < 0.0 || value > RepairLimits.MAX_PERCENT) {
            issues += RepairIssue(
                RepairIssueCode.PROFILE_INVALID, RepairIssueSeverity.ERROR,
                "$label must be finite, >= 0 and <= ${RepairLimits.MAX_PERCENT}",
            )
        }
    }

    private fun checkIntensity(label: String, table: ConditionIntensity, issues: MutableList<RepairIssue>) {
        if (table.reference.isBlank()) {
            issues += RepairIssue(RepairIssueCode.PROFILE_INVALID, RepairIssueSeverity.ERROR, "$label needs a reference")
        }
        val levels = listOf(table.excellent, table.good, table.fair, table.poor, table.failed)
        if (levels.any { !it.isFinite() || it < 0.0 || it > RepairLimits.MAX_INTENSITY }) {
            issues += RepairIssue(
                RepairIssueCode.PROFILE_INVALID, RepairIssueSeverity.ERROR,
                "$label values must be finite, >= 0 and <= ${RepairLimits.MAX_INTENSITY}",
            )
            return
        }
        val monotonic = levels.zipWithNext().all { (better, worse) -> better <= worse }
        if (!monotonic) {
            issues += RepairIssue(
                RepairIssueCode.PROFILE_INVALID, RepairIssueSeverity.ERROR,
                "$label must not decrease as condition worsens (EXCELLENT <= GOOD <= FAIR <= POOR <= FAILED)",
            )
        }
    }
}
