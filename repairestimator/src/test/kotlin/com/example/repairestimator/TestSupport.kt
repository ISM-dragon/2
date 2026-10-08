package com.example.repairestimator

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Fixtures for the estimator tests.
 *
 * Unit costs are chosen so hand calculations stay readable. Expected amounts in the tests are computed
 * with an independent [BigDecimal] path ([expectedCents]), never by calling the engine's own helpers.
 */
internal object Fx {

    /** Unit costs written out for the kinds that the hand-checked tests use. Every other kind gets a distinct value. */
    private val explicitUnitCosts: Map<RepairItemKind, Double> = mapOf(
        RepairItemKind.ROOF_COVERING to 100.0,
        RepairItemKind.HVAC_SYSTEM to 2_000.0,
        RepairItemKind.KITCHEN_CABINETS to 400.0,
        RepairItemKind.BATHROOM_FULL to 10_000.0,
        RepairItemKind.LABOR_HOURS to 50.0,
    )

    fun unitCostOf(kind: RepairItemKind): Double = explicitUnitCosts[kind] ?: (100.0 + 7.0 * kind.ordinal)

    val TRADE_KINDS: List<RepairItemKind> = RepairItemKind.entries.filter { it.role == KindRole.TRADE }

    /** Known condition levels in the order they should get worse. */
    val KNOWN_CONDITIONS: List<ConditionLevel> = listOf(
        ConditionLevel.EXCELLENT,
        ConditionLevel.GOOD,
        ConditionLevel.FAIR,
        ConditionLevel.POOR,
        ConditionLevel.FAILED,
    )

    val INTENSITY: ConditionIntensity = ConditionIntensity(
        excellent = 0.0,
        good = 0.25,
        fair = 0.5,
        poor = 1.0,
        failed = 1.5,
        reference = "test intensity table",
    )

    val STRATEGIES: Map<RepairStrategy, StrategyPolicy> = mapOf(
        RepairStrategy.WHOLESALE to StrategyPolicy(20.0, 1.0, "test wholesale policy"),
        RepairStrategy.BRRRR to StrategyPolicy(10.0, 0.5, "test BRRRR policy"),
        RepairStrategy.FIX_AND_FLIP to StrategyPolicy(15.0, 1.0, "test flip policy"),
    )

    fun defaultUnitCosts(): Map<RepairItemKind, Double> =
        RepairItemKind.PROFILE_PRICED.associateWith { unitCostOf(it) }

    fun profile(
        unitCosts: Map<RepairItemKind, Double> = defaultUnitCosts(),
        intensity: ConditionIntensity = INTENSITY,
        kindOverrides: Map<RepairItemKind, ConditionIntensity> = emptyMap(),
        strategies: Map<RepairStrategy, StrategyPolicy> = STRATEGIES,
        permit: PermitPolicy = PermitPolicy(pctOfPermitScope = 10.0, minimumFee = 50.0, reference = "test permit policy"),
        general: PercentRule = PercentRule(pct = 5.0, reference = "test general conditions"),
        reserve: PercentRule = PercentRule(pct = 0.0, reference = "test reserve"),
        origin: ProfileOrigin = ProfileOrigin.CALLER_DEFINED,
    ): CostProfile = CostProfile(
        id = "TEST_PROFILE",
        version = "test-1",
        displayName = "Test profile",
        origin = origin,
        sourceNote = "test fixture",
        unitCosts = unitCosts.mapValues { (kind, amount) -> UnitCost(amount, "test reference for ${kind.name}") },
        conditionIntensity = intensity,
        kindIntensityOverrides = kindOverrides,
        strategyPolicies = strategies,
        permitPolicy = permit,
        generalConditions = general,
        unknownConditionReserve = reserve,
    )

    fun trade(
        id: String,
        kind: RepairItemKind,
        condition: ConditionLevel? = ConditionLevel.POOR,
        quantity: Double? = 1.0,
        unit: MeasureUnit? = null,
        override: Double? = null,
        permit: Boolean? = null,
        conditionBasis: EvidenceBasis = EvidenceBasis.NOT_STATED,
        quantityBasis: EvidenceBasis = EvidenceBasis.NOT_STATED,
        evidence: String? = null,
    ): RepairScopeLine = RepairScopeLine(
        id = id,
        kind = kind,
        condition = condition,
        conditionBasis = conditionBasis,
        quantity = quantity,
        unit = unit,
        quantityBasis = quantityBasis,
        unitCostOverride = override,
        permitRequired = permit,
        evidence = evidence,
    )

    fun request(
        vararg lines: RepairScopeLine,
        strategy: RepairStrategy = RepairStrategy.BRRRR,
        profile: CostProfile = profile(),
        contingency: Double? = null,
    ): RepairEstimateRequest = RepairEstimateRequest(
        strategy = strategy,
        lines = lines.toList(),
        profile = profile,
        contingencyPctOverride = contingency,
    )

    fun estimateOf(request: RepairEstimateRequest): RepairEstimate =
        when (val outcome = RepairEstimator.estimate(request)) {
            is RepairEstimateOutcome.Estimated -> outcome.estimate
            is RepairEstimateOutcome.Rejected -> throw AssertionError("expected an estimate, got rejection: ${outcome.issues}")
        }

    fun rejectionOf(request: RepairEstimateRequest): List<RepairIssue> =
        when (val outcome = RepairEstimator.estimate(request)) {
            is RepairEstimateOutcome.Rejected -> outcome.issues
            is RepairEstimateOutcome.Estimated -> throw AssertionError("expected a rejection, got an estimate")
        }

    fun RepairEstimate.line(id: String): RepairEstimateLine =
        lines.singleOrNull { it.id == id } ?: throw AssertionError("no single line '$id' in ${lines.map { it.id }}")

    fun RepairEstimate.issueCodes(): List<RepairIssueCode> = issues.map { it.code }

    fun issuesOf(estimate: RepairEstimate, code: RepairIssueCode): List<RepairIssue> =
        estimate.issues.filter { it.code == code }

    /** Exact cents for a product of decimal factors, computed independently of the engine. */
    fun expectedCents(vararg factors: BigDecimal): Double {
        var product = BigDecimal.ONE
        for (factor in factors) product = product.multiply(factor)
        return product.setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    fun dec(value: Double): BigDecimal = BigDecimal.valueOf(value)

    fun dec(value: Int): BigDecimal = BigDecimal.valueOf(value.toLong())

    /** Percent to fraction, independent of the engine. */
    fun pct(value: Double): BigDecimal = BigDecimal.valueOf(value).divide(BigDecimal.valueOf(100L))
}
