package com.example.repairestimator

/**
 * Deterministic US residential rehab (repair) estimator for wholesale, BRRRR and fix-and-flip analysis.
 *
 * Contract:
 *  - Pure: the same request always produces an equal [RepairEstimate]. No clock, randomness, network,
 *    database or AI is involved.
 *  - No invented facts: a missing condition, quantity or amount is reported as unpriced or
 *    inspection-required and never filled in with a guess or with zero.
 *  - No market value: the estimator prices scope. It does not compute after-repair value or any
 *    value that depends on a market.
 *  - Transparent: every number carries its source, and every line shows its arithmetic.
 *
 * Use [estimate]. A rejected request returns [RepairEstimateOutcome.Rejected] and no total.
 */
object RepairEstimator {

    /** Version of the rules in this package. Change it when a rule changes the numbers an estimate produces. */
    const val SPEC_VERSION: String = "REPAIR_ESTIMATOR_2026_10"

    fun estimate(request: RepairEstimateRequest): RepairEstimateOutcome {
        val errors = RepairRequestValidator.validate(request)
        if (errors.isNotEmpty()) return RepairEstimateOutcome.Rejected(errors)
        return RepairEstimateOutcome.Estimated(RepairPricer(request).price())
    }
}
