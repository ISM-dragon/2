package com.example.repairestimator

import com.example.repairestimator.RepairMath.cents
import com.example.repairestimator.RepairMath.dec
import com.example.repairestimator.RepairMath.dollars
import java.math.BigDecimal
import java.util.TreeMap

/**
 * Prices a request that has already passed [RepairRequestValidator] and [CostProfileValidator].
 *
 * Order of work, which is what makes the result reproducible:
 *  1. Caller lines are priced in canonical order (category, kind, id). Input order cannot change the result.
 *  2. Rule lines are derived from the priced caller lines: general conditions, permit fees, the
 *     unknown-condition reserve, and finally contingency on the trade plus labor base.
 *  3. Totals, category coverage, completeness and assumptions are assembled from the finished lines.
 */
internal class RepairPricer(private val request: RepairEstimateRequest) {

    private val profile: CostProfile = request.profile
    private val strategy: StrategyPolicy = profile.strategyPolicies.getValue(request.strategy)
    private val contingencyPct: Double = request.contingencyPctOverride ?: strategy.contingencyPct
    private val profileSource: ProvenanceSource =
        if (profile.origin == ProfileOrigin.PLACEHOLDER_BUILT_IN) ProvenanceSource.PLACEHOLDER_PROFILE
        else ProvenanceSource.CALLER_PROFILE

    private val issues = ArrayList<RepairIssue>()
    private val assumptions = TreeMap<String, AssumptionRecord>()

    fun price(): RepairEstimate {
        if (profile.origin == ProfileOrigin.PLACEHOLDER_BUILT_IN) {
            issue(
                RepairIssueCode.PLACEHOLDER_PROFILE, RepairIssueSeverity.WARNING,
                "profile '${profile.id}' is an uncalibrated built-in placeholder; replace its unit costs with local bids before relying on the total",
            )
        }
        assume("profile.id", profile.id, profileSource, profile.displayName)
        assume("profile.version", profile.version, profileSource, null)
        assume("strategy", request.strategy.name, ProvenanceSource.CALLER_INPUT, strategy.reference)

        val callerLines = request.lines.sortedWith(CALLER_ORDER).map { priceCallerLine(it) }

        val tradeTotal = RepairMath.sumAmounts(callerLines.filter { it.category.isTrade })
        val permitScope = callerLines.filter { it.category.isTrade && it.permitRequired }
        val permitBase = RepairMath.sumAmounts(permitScope)
        val permitUnpriced = permitScope.count { it.amount == null }
        val inspectionCount = callerLines.count { line ->
            line.category.isTrade && line.unpricedReasons.any {
                it == UnpricedReason.CONDITION_NOT_SUPPLIED || it == UnpricedReason.CONDITION_UNKNOWN
            }
        }

        val ruleLines = ArrayList<RepairEstimateLine>()

        val explicitGeneral = request.lines.any { it.kind == RepairItemKind.GENERAL_CONDITIONS }
        if (explicitGeneral) {
            suppressedByCaller("general conditions", "GENERAL_CONDITIONS")
        } else {
            ruleLines += generalConditionsLine(tradeTotal)
        }

        val explicitPermit = request.lines.any { it.kind == RepairItemKind.PERMIT_FEES }
        if (explicitPermit) {
            suppressedByCaller("permit fees", "PERMIT_FEES")
        } else {
            ruleLines += permitFeesLine(permitBase, permitUnpriced)
        }

        ruleLines += unknownConditionReserveLine(tradeTotal, inspectionCount)

        val laborBase = RepairMath.sumAmounts(
            (callerLines + ruleLines).filter { it.category == RehabCategory.LABOR },
        )
        ruleLines += contingencyLine(tradeTotal + laborBase)

        val lines = (callerLines + ruleLines).sortedWith(OUTPUT_ORDER)
        val categories = summarizeCategories(lines)
        val totals = totalsFor(lines)
        val completeness = completenessFor(lines, categories)

        return RepairEstimate(
            specVersion = RepairEstimator.SPEC_VERSION,
            strategy = request.strategy,
            profile = ProfileSummary(profile.id, profile.version, profile.displayName, profile.origin, profile.sourceNote),
            lines = lines,
            categories = categories,
            totals = totals,
            completeness = completeness,
            assumptions = assumptions.values.toList(),
            issues = issues.toList(),
        )
    }

    // ------------------------------------------------------------------ caller lines

    private fun priceCallerLine(line: RepairScopeLine): RepairEstimateLine = when (line.kind.role) {
        KindRole.TRADE -> priceTrade(line)
        KindRole.LABOR_HOURS -> priceLaborHours(line)
        KindRole.EXPLICIT_OR_RULE -> priceExplicitLump(line)
        KindRole.CALLER_ALLOWANCE -> priceUnknownScopeAllowance(line)
        // Validation rejects caller lines of this role before pricing starts.
        KindRole.POLICY_ONLY -> throw IllegalStateException("${line.kind.name} reached pricing; validation should have rejected it")
    }

    private fun priceTrade(line: RepairScopeLine): RepairEstimateLine {
        val kind = line.kind
        val suppliedCondition = line.condition
        val condition = suppliedCondition ?: ConditionLevel.UNKNOWN
        val conditionProvenance = conditionProvenance(line)
        val permitRequired = line.permitRequired ?: kind.permitTriggerByDefault
        if (line.permitRequired != null && line.permitRequired != kind.permitTriggerByDefault) {
            issue(
                RepairIssueCode.PERMIT_REQUIRED_OVERRIDDEN, RepairIssueSeverity.INFO,
                "line '${line.id}': permit trigger set to $permitRequired for ${kind.name} (kind default ${kind.permitTriggerByDefault})",
                line.id,
            )
        }
        if (suppliedCondition != null && line.conditionBasis == EvidenceBasis.ASSUMED) {
            issue(
                RepairIssueCode.CONDITION_ASSUMED, RepairIssueSeverity.WARNING,
                "line '${line.id}': condition ${suppliedCondition.name} is an assumption, not an observation",
                line.id,
            )
        }
        if (suppliedCondition == null) {
            assume("condition.notSupplied", ConditionLevel.UNKNOWN.name, ProvenanceSource.ENGINE_RULE, "a line without a condition is inspection-required")
        }

        val quantity = resolveQuantity(line)
        val override = line.unitCostOverride
        val target = kind.pricingUnit

        if (condition == ConditionLevel.UNKNOWN) {
            // The only way to put a number on uninspected work is a caller allowance with a quantity.
            if (override != null && quantity.pricing != null) {
                issue(
                    RepairIssueCode.ALLOWANCE_UNVERIFIED, RepairIssueSeverity.WARNING,
                    "line '${line.id}': ${kind.label} is priced as a caller allowance; its condition has not been inspected",
                    line.id,
                )
                val amount = cents(quantity.pricing * dec(override))
                return buildLine(
                    line = line,
                    condition = condition,
                    status = LineStatus.ALLOWANCE_UNVERIFIED,
                    amount = amount,
                    reasons = emptyList(),
                    permitRequired = permitRequired,
                    calculation = LineCalculation(
                        suppliedQuantity = line.quantity,
                        suppliedUnit = line.unit,
                        pricingQuantity = quantity.pricing.toDouble(),
                        pricingUnit = target,
                        baseUnitCost = override,
                        finishMultiplier = null,
                        conditionIntensity = null,
                        effectiveUnitCost = override,
                        rulePercent = null,
                    ),
                    provenance = LineProvenance(
                        condition = conditionProvenance,
                        quantity = quantity.provenance,
                        unitCost = ValueProvenance(ProvenanceSource.CALLER_OVERRIDE, null, "caller unit cost on an uninspected line"),
                        conditionIntensity = notApplicable(),
                        finishMultiplier = notApplicable(),
                        rule = notApplicable(),
                    ),
                    formula = "${quantityPhrase(line, quantity)} x ${RepairMath.rate(dec(override))}/${target.symbol} " +
                        "caller allowance, condition not inspected = ${RepairMath.money(amount)}",
                )
            }
            val reasons = buildList {
                add(if (suppliedCondition == null) UnpricedReason.CONDITION_NOT_SUPPLIED else UnpricedReason.CONDITION_UNKNOWN)
                if (quantity.pricing == null) add(UnpricedReason.QUANTITY_NOT_SUPPLIED)
            }
            return unpricedLine(line, condition, reasons, permitRequired, conditionProvenance, quantity)
        }

        if (override != null) {
            if (quantity.pricing == null) {
                return unpricedLine(line, condition, listOf(UnpricedReason.QUANTITY_NOT_SUPPLIED), permitRequired, conditionProvenance, quantity)
            }
            issue(
                RepairIssueCode.OVERRIDE_APPLIED, RepairIssueSeverity.INFO,
                "line '${line.id}': caller unit cost ${RepairMath.rate(dec(override))}/${target.symbol} replaces the profile unit cost",
                line.id,
            )
            if (condition == ConditionLevel.EXCELLENT) {
                issue(
                    RepairIssueCode.OVERRIDE_ON_EXCELLENT, RepairIssueSeverity.WARNING,
                    "line '${line.id}': a caller cost is applied to an EXCELLENT (no work) condition; check the condition or the cost",
                    line.id,
                )
            }
            val amount = cents(quantity.pricing * dec(override))
            return buildLine(
                line = line,
                condition = condition,
                status = LineStatus.PRICED_CALLER_OVERRIDE,
                amount = amount,
                reasons = emptyList(),
                permitRequired = permitRequired,
                calculation = LineCalculation(
                    suppliedQuantity = line.quantity,
                    suppliedUnit = line.unit,
                    pricingQuantity = quantity.pricing.toDouble(),
                    pricingUnit = target,
                    baseUnitCost = override,
                    finishMultiplier = null,
                    conditionIntensity = null,
                    effectiveUnitCost = override,
                    rulePercent = null,
                ),
                provenance = LineProvenance(
                    condition = conditionProvenance,
                    quantity = quantity.provenance,
                    unitCost = ValueProvenance(ProvenanceSource.CALLER_OVERRIDE, null, "caller unit cost"),
                    conditionIntensity = notApplicable(),
                    finishMultiplier = notApplicable(),
                    rule = notApplicable(),
                ),
                formula = "${quantityPhrase(line, quantity)} x ${RepairMath.rate(dec(override))}/${target.symbol} " +
                    "caller unit cost = ${RepairMath.money(amount)}",
            )
        }

        // Profile-priced trade work: unit cost x finish grade x condition intensity, times quantity.
        val table = profile.kindIntensityOverrides[kind] ?: profile.conditionIntensity
        val intensityValue = requireNotNull(table.of(condition)) { "known condition has an intensity" }
        val intensity = dec(intensityValue)
        val unitCost = profile.unitCosts.getValue(kind)
        val base = dec(unitCost.amount)
        val grade = if (kind.finishItem) dec(strategy.finishMultiplier) else null
        val effective = base * (grade ?: BigDecimal.ONE) * intensity

        assume("unitCost.${kind.name}", "${RepairMath.rate(base)}/${target.symbol}", profileSource, unitCost.reference)
        assume("intensity.${kind.name}.${condition.name}", RepairMath.factor(intensity), profileSource, table.reference)
        if (grade != null) {
            assume("finishMultiplier.${request.strategy.name}", RepairMath.factor(grade), ProvenanceSource.STRATEGY_POLICY, strategy.reference)
        }

        val calculation = LineCalculation(
            suppliedQuantity = line.quantity,
            suppliedUnit = line.unit,
            pricingQuantity = quantity.pricing?.toDouble(),
            pricingUnit = target,
            baseUnitCost = unitCost.amount,
            finishMultiplier = grade?.let { strategy.finishMultiplier },
            conditionIntensity = intensityValue,
            effectiveUnitCost = effective.toDouble(),
            rulePercent = null,
        )
        val provenance = LineProvenance(
            condition = conditionProvenance,
            quantity = quantity.provenance,
            unitCost = ValueProvenance(profileSource, null, unitCost.reference),
            conditionIntensity = ValueProvenance(profileSource, null, table.reference),
            finishMultiplier = if (grade != null) ValueProvenance(ProvenanceSource.STRATEGY_POLICY, null, strategy.reference) else notApplicable(),
            rule = notApplicable(),
        )

        if (intensity.signum() == 0) {
            return buildLine(
                line = line,
                condition = condition,
                status = LineStatus.NO_WORK_REQUIRED,
                amount = BigDecimal.ZERO,
                reasons = emptyList(),
                permitRequired = permitRequired,
                calculation = calculation,
                provenance = provenance,
                formula = "no work required: condition ${condition.name} has intensity 0.00",
            )
        }
        if (quantity.pricing == null) {
            return unpricedLine(line, condition, listOf(UnpricedReason.QUANTITY_NOT_SUPPLIED), permitRequired, conditionProvenance, quantity)
        }

        val amount = cents(quantity.pricing * effective)
        val finishText = if (grade != null) " x ${RepairMath.factor(grade)} finish grade for ${request.strategy.name}" else ""
        return buildLine(
            line = line,
            condition = condition,
            status = LineStatus.PRICED_PROFILE,
            amount = amount,
            reasons = emptyList(),
            permitRequired = permitRequired,
            calculation = calculation,
            provenance = provenance,
            formula = "${quantityPhrase(line, quantity)} x ${RepairMath.rate(effective)}/${target.symbol} " +
                "(${RepairMath.rate(base)} base$finishText x ${RepairMath.factor(intensity)} ${condition.name} intensity) " +
                "= ${RepairMath.money(amount)}",
        )
    }

    private fun priceLaborHours(line: RepairScopeLine): RepairEstimateLine {
        val kind = line.kind
        ignoreInapplicableFields(line)
        val quantity = resolveQuantity(line)
        val profileRate = profile.unitCosts.getValue(kind)
        val override = line.unitCostOverride
        val rate = override ?: profileRate.amount
        val rateDecimal = dec(rate)
        val rateProvenance = if (override != null) {
            ValueProvenance(ProvenanceSource.CALLER_OVERRIDE, null, "caller hourly rate")
        } else {
            assume("unitCost.${kind.name}", "${RepairMath.rate(rateDecimal)}/HR", profileSource, profileRate.reference)
            ValueProvenance(profileSource, null, profileRate.reference)
        }
        if (override != null) {
            issue(
                RepairIssueCode.OVERRIDE_APPLIED, RepairIssueSeverity.INFO,
                "line '${line.id}': caller hourly rate ${RepairMath.rate(rateDecimal)}/HR replaces the profile rate",
                line.id,
            )
        }
        val calculation = LineCalculation(
            suppliedQuantity = line.quantity,
            suppliedUnit = line.unit,
            pricingQuantity = quantity.pricing?.toDouble(),
            pricingUnit = kind.pricingUnit,
            baseUnitCost = rate,
            finishMultiplier = null,
            conditionIntensity = null,
            effectiveUnitCost = rate,
            rulePercent = null,
        )
        val provenance = LineProvenance(
            condition = notApplicable(),
            quantity = quantity.provenance,
            unitCost = rateProvenance,
            conditionIntensity = notApplicable(),
            finishMultiplier = notApplicable(),
            rule = notApplicable(),
        )
        if (quantity.pricing == null) {
            return unpricedLine(line, null, listOf(UnpricedReason.QUANTITY_NOT_SUPPLIED), false, notApplicable(), quantity, calculation, provenance)
        }
        val amount = cents(quantity.pricing * rateDecimal)
        return buildLine(
            line = line,
            condition = null,
            status = if (override != null) LineStatus.PRICED_CALLER_OVERRIDE else LineStatus.PRICED_PROFILE,
            amount = amount,
            reasons = emptyList(),
            permitRequired = false,
            calculation = calculation,
            provenance = provenance,
            formula = "${quantityPhrase(line, quantity)} x ${RepairMath.rate(rateDecimal)}/HR = ${RepairMath.money(amount)}",
        )
    }

    private fun priceExplicitLump(line: RepairScopeLine): RepairEstimateLine {
        val kind = line.kind
        ignoreInapplicableFields(line)
        val quantity = resolveQuantity(line)
        val lumpAmount = dec(requireNotNull(line.unitCostOverride) { "validation requires an amount for ${kind.name}" })
        val lumps = requireNotNull(quantity.pricing) { "a lump-sum quantity always resolves" }
        val amount = cents(lumps * lumpAmount)
        issue(
            RepairIssueCode.OVERRIDE_APPLIED, RepairIssueSeverity.INFO,
            "line '${line.id}': caller amount ${RepairMath.money(lumpAmount)} for ${kind.label} replaces the profile rule",
            line.id,
        )
        return buildLine(
            line = line,
            condition = null,
            status = LineStatus.PRICED_CALLER_OVERRIDE,
            amount = amount,
            reasons = emptyList(),
            permitRequired = false,
            calculation = LineCalculation(
                suppliedQuantity = line.quantity,
                suppliedUnit = line.unit,
                pricingQuantity = lumps.toDouble(),
                pricingUnit = kind.pricingUnit,
                baseUnitCost = lumpAmount.toDouble(),
                finishMultiplier = null,
                conditionIntensity = null,
                effectiveUnitCost = lumpAmount.toDouble(),
                rulePercent = null,
            ),
            provenance = LineProvenance(
                condition = notApplicable(),
                quantity = quantity.provenance,
                unitCost = ValueProvenance(ProvenanceSource.CALLER_OVERRIDE, null, "caller amount for this lump sum"),
                conditionIntensity = notApplicable(),
                finishMultiplier = notApplicable(),
                rule = notApplicable(),
            ),
            formula = "${RepairMath.quantity(lumps)} LS x ${RepairMath.money(lumpAmount)}/LS caller amount " +
                "replaces the profile rule = ${RepairMath.money(amount)}",
        )
    }

    private fun priceUnknownScopeAllowance(line: RepairScopeLine): RepairEstimateLine {
        val kind = line.kind
        ignoreInapplicableFields(line)
        val quantity = resolveQuantity(line)
        val override = line.unitCostOverride
        val lumps = requireNotNull(quantity.pricing) { "a lump-sum quantity always resolves" }
        if (override == null) {
            issue(
                RepairIssueCode.ALLOWANCE_NOT_SUPPLIED, RepairIssueSeverity.WARNING,
                "line '${line.id}': no amount was given for hidden or unknown scope, so the line is not priced",
                line.id,
            )
            return buildLine(
                line = line,
                condition = null,
                status = LineStatus.UNPRICED,
                amount = null,
                reasons = listOf(UnpricedReason.ALLOWANCE_NOT_SUPPLIED),
                permitRequired = false,
                calculation = LineCalculation(
                    suppliedQuantity = line.quantity,
                    suppliedUnit = line.unit,
                    pricingQuantity = lumps.toDouble(),
                    pricingUnit = kind.pricingUnit,
                    baseUnitCost = null,
                    finishMultiplier = null,
                    conditionIntensity = null,
                    effectiveUnitCost = null,
                    rulePercent = null,
                ),
                provenance = LineProvenance(
                    condition = notApplicable(),
                    quantity = quantity.provenance,
                    unitCost = ValueProvenance(ProvenanceSource.NOT_SUPPLIED),
                    conditionIntensity = notApplicable(),
                    finishMultiplier = notApplicable(),
                    rule = notApplicable(),
                ),
                formula = "not priced: no allowance amount supplied for hidden or unknown scope",
            )
        }
        issue(
            RepairIssueCode.ALLOWANCE_UNVERIFIED, RepairIssueSeverity.WARNING,
            "line '${line.id}': allowance for hidden or unknown scope is not inspected",
            line.id,
        )
        val dollarsPerLump = dec(override)
        val amount = cents(lumps * dollarsPerLump)
        return buildLine(
            line = line,
            condition = null,
            status = LineStatus.ALLOWANCE_UNVERIFIED,
            amount = amount,
            reasons = emptyList(),
            permitRequired = false,
            calculation = LineCalculation(
                suppliedQuantity = line.quantity,
                suppliedUnit = line.unit,
                pricingQuantity = lumps.toDouble(),
                pricingUnit = kind.pricingUnit,
                baseUnitCost = override,
                finishMultiplier = null,
                conditionIntensity = null,
                effectiveUnitCost = override,
                rulePercent = null,
            ),
            provenance = LineProvenance(
                condition = notApplicable(),
                quantity = quantity.provenance,
                unitCost = ValueProvenance(ProvenanceSource.CALLER_OVERRIDE, null, "caller allowance for unknown scope"),
                conditionIntensity = notApplicable(),
                finishMultiplier = notApplicable(),
                rule = notApplicable(),
            ),
            formula = "${RepairMath.quantity(lumps)} LS x ${RepairMath.money(dollarsPerLump)}/LS caller allowance " +
                "for unknown scope = ${RepairMath.money(amount)}",
        )
    }

    // ------------------------------------------------------------------ profile rule lines

    private fun generalConditionsLine(tradeTotal: BigDecimal): RepairEstimateLine {
        val kind = RepairItemKind.GENERAL_CONDITIONS
        val rule = profile.generalConditions
        val ruleProvenance = ValueProvenance(profileSource, null, rule.reference)
        return if (tradeTotal.signum() > 0 && rule.pct > 0.0) {
            val amount = cents(tradeTotal * RepairMath.fraction(rule.pct))
            assume("generalConditions.pctOfTradeWork", RepairMath.percent(rule.pct), profileSource, rule.reference)
            policyLine(
                kind, LineStatus.POLICY_RULE, amount, rule.pct, ruleProvenance,
                "${RepairMath.percent(rule.pct)} x priced trade work ${RepairMath.money(tradeTotal)} = ${RepairMath.money(amount)}",
            )
        } else {
            policyLine(
                kind, LineStatus.POLICY_NOT_APPLICABLE, BigDecimal.ZERO, rule.pct, ruleProvenance,
                if (rule.pct <= 0.0) "not applied: profile rate is 0.00%" else "not applied: no priced trade work",
            )
        }
    }

    private fun permitFeesLine(permitBase: BigDecimal, unpricedCount: Int): RepairEstimateLine {
        val kind = RepairItemKind.PERMIT_FEES
        val rule = profile.permitPolicy
        val ruleProvenance = ValueProvenance(profileSource, null, rule.reference)
        if (unpricedCount > 0) {
            issue(
                RepairIssueCode.PERMIT_SCOPE_INCOMPLETE, RepairIssueSeverity.WARNING,
                "$unpricedCount permit-triggering line(s) are unpriced, so permit fees do not cover them",
            )
        }
        return if (permitBase.signum() > 0) {
            val minimum = cents(dec(rule.minimumFee))
            val computed = cents(permitBase * RepairMath.fraction(rule.pctOfPermitScope))
            val amount = maxOf(computed, minimum)
            assume("permit.pctOfPermitScope", RepairMath.percent(rule.pctOfPermitScope), profileSource, rule.reference)
            assume("permit.minimumFee", RepairMath.money(minimum), profileSource, rule.reference)
            policyLine(
                kind, LineStatus.POLICY_RULE, amount, rule.pctOfPermitScope, ruleProvenance,
                "max(${RepairMath.money(minimum)} minimum, ${RepairMath.percent(rule.pctOfPermitScope)} x " +
                    "permit-triggering scope ${RepairMath.money(permitBase)}) = ${RepairMath.money(amount)}",
            )
        } else {
            policyLine(
                kind, LineStatus.POLICY_NOT_APPLICABLE, BigDecimal.ZERO, rule.pctOfPermitScope, ruleProvenance,
                "not applied: no priced permit-triggering scope",
            )
        }
    }

    private fun unknownConditionReserveLine(tradeTotal: BigDecimal, inspectionCount: Int): RepairEstimateLine {
        val kind = RepairItemKind.UNKNOWN_CONDITION_RESERVE
        val rule = profile.unknownConditionReserve
        val ruleProvenance = ValueProvenance(profileSource, null, rule.reference)
        return if (rule.pct > 0.0 && inspectionCount > 0) {
            val amount = cents(tradeTotal * RepairMath.fraction(rule.pct))
            assume("unknownConditionReserve.pctOfTradeWork", RepairMath.percent(rule.pct), profileSource, rule.reference)
            policyLine(
                kind, LineStatus.POLICY_RULE, amount, rule.pct, ruleProvenance,
                "${RepairMath.percent(rule.pct)} x priced trade work ${RepairMath.money(tradeTotal)} for " +
                    "$inspectionCount inspection-required line(s) = ${RepairMath.money(amount)}",
            )
        } else {
            policyLine(
                kind, LineStatus.POLICY_NOT_APPLICABLE, BigDecimal.ZERO, rule.pct, ruleProvenance,
                if (rule.pct <= 0.0) "not applied: profile reserve is 0.00%" else "not applied: no inspection-required trade lines",
            )
        }
    }

    private fun contingencyLine(base: BigDecimal): RepairEstimateLine {
        val kind = RepairItemKind.CONTINGENCY
        val overridden = request.contingencyPctOverride != null
        val source = if (overridden) ProvenanceSource.CALLER_OVERRIDE else ProvenanceSource.STRATEGY_POLICY
        val reference = if (overridden) "caller contingency override" else strategy.reference
        if (overridden) {
            issue(
                RepairIssueCode.CONTINGENCY_OVERRIDDEN, RepairIssueSeverity.INFO,
                "contingency set to ${RepairMath.percent(contingencyPct)} by the caller (${request.strategy.name} default ${RepairMath.percent(strategy.contingencyPct)})",
            )
        }
        val ruleProvenance = ValueProvenance(source, null, reference)
        return if (base.signum() > 0) {
            val amount = cents(base * RepairMath.fraction(contingencyPct))
            assume("contingency.pct", RepairMath.percent(contingencyPct), source, reference)
            policyLine(
                kind, LineStatus.POLICY_RULE, amount, contingencyPct, ruleProvenance,
                "${RepairMath.percent(contingencyPct)} x contingency base ${RepairMath.money(base)} " +
                    "(priced trade work plus labor) = ${RepairMath.money(amount)}",
            )
        } else {
            policyLine(
                kind, LineStatus.POLICY_NOT_APPLICABLE, BigDecimal.ZERO, contingencyPct, ruleProvenance,
                "not applied: no priced trade or labor work",
            )
        }
    }

    private fun policyLine(
        kind: RepairItemKind,
        status: LineStatus,
        amount: BigDecimal,
        rulePercent: Double,
        ruleProvenance: ValueProvenance,
        formula: String,
    ): RepairEstimateLine = RepairEstimateLine(
        id = RepairLimits.AUTO_ID_PREFIX + kind.name.lowercase().replace('_', '-'),
        category = kind.category,
        kind = kind,
        label = kind.label,
        status = status,
        condition = null,
        amount = dollars(amount),
        unpricedReasons = emptyList(),
        permitRequired = false,
        calculation = LineCalculation(
            suppliedQuantity = null,
            suppliedUnit = null,
            pricingQuantity = null,
            pricingUnit = kind.pricingUnit,
            baseUnitCost = null,
            finishMultiplier = null,
            conditionIntensity = null,
            effectiveUnitCost = null,
            rulePercent = rulePercent,
        ),
        provenance = LineProvenance(
            condition = notApplicable(),
            quantity = notApplicable(),
            unitCost = notApplicable(),
            conditionIntensity = notApplicable(),
            finishMultiplier = notApplicable(),
            rule = ruleProvenance,
        ),
        formula = formula,
        evidence = null,
    )

    // ------------------------------------------------------------------ shared line helpers

    private class QuantityResolution(
        val supplied: Double?,
        val pricing: BigDecimal?,
        val provenance: ValueProvenance,
    )

    /** Converts the supplied quantity to the kind's pricing unit. Lump sums default to one lump. */
    private fun resolveQuantity(line: RepairScopeLine): QuantityResolution {
        val kind = line.kind
        val target = kind.pricingUnit
        val unit = line.unit ?: target
        val factor = requireNotNull(UnitConversions.factor(unit, target)) { "validation rejects ${unit.name} for ${kind.name}" }
        if (unit != target) {
            issue(
                RepairIssueCode.UNIT_CONVERTED, RepairIssueSeverity.INFO,
                "line '${line.id}': quantity converted from ${unit.symbol} to ${target.symbol} at ${RepairMath.factor(factor)}",
                line.id,
            )
            assume("conversion.${unit.name}.TO.${target.name}", RepairMath.factor(factor), ProvenanceSource.ENGINE_RULE, "unit conversion applied to a caller quantity")
        }

        val supplied = line.quantity
        if (supplied == null) {
            if (target == MeasureUnit.LUMP_SUM) {
                issue(
                    RepairIssueCode.LUMP_SUM_QUANTITY_DEFAULTED, RepairIssueSeverity.INFO,
                    "line '${line.id}': no quantity given, so one lump sum is used",
                    line.id,
                )
                assume("quantity.lumpSumDefault", "1", ProvenanceSource.ENGINE_RULE, "a lump-sum line without a quantity is one lump")
                return QuantityResolution(null, BigDecimal.ONE, ValueProvenance(ProvenanceSource.ENGINE_RULE, null, "one lump sum by rule"))
            }
            return QuantityResolution(null, null, ValueProvenance(ProvenanceSource.NOT_SUPPLIED))
        }
        if (line.quantityBasis == EvidenceBasis.ASSUMED) {
            issue(
                RepairIssueCode.QUANTITY_ASSUMED, RepairIssueSeverity.WARNING,
                "line '${line.id}': quantity ${RepairMath.quantity(dec(supplied))} ${unit.symbol} is an assumption, not a measurement",
                line.id,
            )
        }
        return QuantityResolution(supplied, dec(supplied) * factor, ValueProvenance(ProvenanceSource.CALLER_INPUT, line.quantityBasis))
    }

    private fun conditionProvenance(line: RepairScopeLine): ValueProvenance = when (line.condition) {
        null -> ValueProvenance(ProvenanceSource.NOT_SUPPLIED, null, "no condition supplied: treated as UNKNOWN, inspection required")
        ConditionLevel.UNKNOWN -> ValueProvenance(ProvenanceSource.CALLER_INPUT, line.conditionBasis, "caller stated the condition is unknown")
        else -> ValueProvenance(ProvenanceSource.CALLER_INPUT, line.conditionBasis)
    }

    private fun quantityPhrase(line: RepairScopeLine, quantity: QuantityResolution): String {
        val pricing = requireNotNull(quantity.pricing)
        val text = "${RepairMath.quantity(pricing)} ${line.kind.pricingUnit.symbol}"
        val supplied = quantity.supplied
        val unit = line.unit
        return if (supplied != null && unit != null && unit != line.kind.pricingUnit) {
            "$text (from ${RepairMath.quantity(dec(supplied))} ${unit.symbol})"
        } else {
            text
        }
    }

    private fun unpricedLine(
        line: RepairScopeLine,
        condition: ConditionLevel?,
        reasons: List<UnpricedReason>,
        permitRequired: Boolean,
        conditionProvenance: ValueProvenance,
        quantity: QuantityResolution,
        calculation: LineCalculation? = null,
        provenance: LineProvenance? = null,
    ): RepairEstimateLine {
        val kind = line.kind
        if (reasons.any { it == UnpricedReason.CONDITION_NOT_SUPPLIED || it == UnpricedReason.CONDITION_UNKNOWN }) {
            issue(
                RepairIssueCode.INSPECTION_REQUIRED, RepairIssueSeverity.WARNING,
                "line '${line.id}' (${kind.label}) is not priced: inspection required",
                line.id,
            )
        }
        if (reasons.contains(UnpricedReason.QUANTITY_NOT_SUPPLIED)) {
            issue(
                RepairIssueCode.QUANTITY_NOT_SUPPLIED, RepairIssueSeverity.WARNING,
                "line '${line.id}' (${kind.label}) is not priced: no quantity supplied",
                line.id,
            )
        }
        val reasonText = reasons.joinToString("; ") {
            when (it) {
                UnpricedReason.CONDITION_NOT_SUPPLIED -> "condition not supplied, inspection required"
                UnpricedReason.CONDITION_UNKNOWN -> "condition unknown, inspection required"
                UnpricedReason.QUANTITY_NOT_SUPPLIED -> "quantity not supplied"
                UnpricedReason.ALLOWANCE_NOT_SUPPLIED -> "allowance amount not supplied"
            }
        }
        return buildLine(
            line = line,
            condition = condition,
            status = LineStatus.UNPRICED,
            amount = null,
            reasons = reasons,
            permitRequired = permitRequired,
            calculation = calculation ?: LineCalculation(
                suppliedQuantity = line.quantity,
                suppliedUnit = line.unit,
                pricingQuantity = quantity.pricing?.toDouble(),
                pricingUnit = kind.pricingUnit,
                baseUnitCost = null,
                finishMultiplier = null,
                conditionIntensity = null,
                effectiveUnitCost = null,
                rulePercent = null,
            ),
            provenance = provenance ?: LineProvenance(
                condition = conditionProvenance,
                quantity = quantity.provenance,
                unitCost = notApplicable(),
                conditionIntensity = notApplicable(),
                finishMultiplier = notApplicable(),
                rule = notApplicable(),
            ),
            formula = "not priced: $reasonText",
        )
    }

    private fun buildLine(
        line: RepairScopeLine,
        condition: ConditionLevel?,
        status: LineStatus,
        amount: BigDecimal?,
        reasons: List<UnpricedReason>,
        permitRequired: Boolean,
        calculation: LineCalculation,
        provenance: LineProvenance,
        formula: String,
    ): RepairEstimateLine = RepairEstimateLine(
        id = line.id,
        category = line.kind.category,
        kind = line.kind,
        label = line.kind.label,
        status = status,
        condition = condition,
        amount = amount?.let { dollars(it) },
        unpricedReasons = reasons,
        permitRequired = permitRequired,
        calculation = calculation,
        provenance = provenance,
        formula = formula,
        evidence = line.evidence,
    )

    private fun ignoreInapplicableFields(line: RepairScopeLine) {
        val ignored = buildList {
            if (line.condition != null) add("condition")
            if (line.permitRequired != null) add("permitRequired")
        }
        if (ignored.isNotEmpty()) {
            issue(
                RepairIssueCode.FIELD_NOT_APPLICABLE, RepairIssueSeverity.WARNING,
                "line '${line.id}': ${ignored.joinToString()} does not apply to ${line.kind.name} and is ignored",
                line.id,
            )
        }
    }

    private fun suppressedByCaller(label: String, kindName: String) {
        issue(
            RepairIssueCode.POLICY_SUPPRESSED, RepairIssueSeverity.INFO,
            "$label are set by a caller amount ($kindName line), so the profile rule is not applied",
        )
    }

    private fun notApplicable(): ValueProvenance = ValueProvenance(ProvenanceSource.NOT_APPLICABLE)

    private fun issue(code: RepairIssueCode, severity: RepairIssueSeverity, message: String, lineId: String? = null) {
        issues += RepairIssue(code, severity, message, lineId)
    }

    private fun assume(key: String, value: String, source: ProvenanceSource, reference: String?) {
        assumptions.putIfAbsent(key, AssumptionRecord(key, value, source, reference))
    }

    // ------------------------------------------------------------------ roll-ups

    private fun summarizeCategories(lines: List<RepairEstimateLine>): List<CategorySummary> {
        val summaries = RehabCategory.entries.map { category ->
            val members = lines.filter { it.category == category }
            val coverage = when {
                !category.isTrade -> CategoryCoverage.DERIVED
                members.isEmpty() -> CategoryCoverage.NOT_COVERED
                else -> CategoryCoverage.COVERED
            }
            CategorySummary(
                category = category,
                coverage = coverage,
                lineCount = members.size,
                pricedSubtotal = dollars(RepairMath.sumAmounts(members)),
                unpricedLineCount = members.count { it.amount == null },
            )
        }
        for (summary in summaries) {
            if (summary.coverage == CategoryCoverage.NOT_COVERED) {
                issue(
                    RepairIssueCode.CATEGORY_NOT_COVERED, RepairIssueSeverity.WARNING,
                    "${summary.category.label} has no scope line, so it is not assessed and adds nothing to the total",
                )
            }
        }
        return summaries
    }

    private fun totalsFor(lines: List<RepairEstimateLine>): RehabTotals {
        val trade = RepairMath.sumAmounts(lines.filter { it.category.isTrade })
        val labor = RepairMath.sumAmounts(lines.filter { it.category == RehabCategory.LABOR })
        val permits = RepairMath.sumAmounts(lines.filter { it.category == RehabCategory.PERMITS })
        val unknown = RepairMath.sumAmounts(lines.filter { it.category == RehabCategory.UNKNOWN_INSPECTION_REQUIRED })
        val contingency = RepairMath.sumAmounts(lines.filter { it.category == RehabCategory.CONTINGENCY })
        val contingencyBase = trade + labor
        val total = trade + labor + permits + unknown + contingency
        return RehabTotals(
            tradeSubtotal = dollars(trade),
            laborSubtotal = dollars(labor),
            permitSubtotal = dollars(permits),
            unknownScopeSubtotal = dollars(unknown),
            contingencyPct = contingencyPct,
            contingencyBase = dollars(contingencyBase),
            contingencyAmount = dollars(contingency),
            totalRehab = dollars(total),
            pricedLineCount = lines.count { it.amount != null },
            unpricedLineCount = lines.count { it.amount == null },
            allowanceLineCount = lines.count { it.status == LineStatus.ALLOWANCE_UNVERIFIED },
        )
    }

    private fun completenessFor(lines: List<RepairEstimateLine>, categories: List<CategorySummary>): Completeness {
        val unpriced = lines.filter { it.amount == null }.map { it.id }
        val notCovered = categories.filter { it.coverage == CategoryCoverage.NOT_COVERED }.map { it.category }
        val reasons = buildList {
            if (unpriced.isNotEmpty()) add("${unpriced.size} line(s) unpriced: ${unpriced.joinToString()}")
            if (notCovered.isNotEmpty()) {
                val noun = if (notCovered.size == 1) "category" else "categories"
                add("${notCovered.size} trade $noun not assessed: ${notCovered.joinToString { it.label }}")
            }
        }
        return Completeness(
            isComplete = reasons.isEmpty(),
            unpricedLineIds = unpriced,
            notCoveredCategories = notCovered,
            unverifiedAllowanceLineIds = lines.filter { it.status == LineStatus.ALLOWANCE_UNVERIFIED }.map { it.id },
            reasons = reasons,
        )
    }

    private companion object {
        val CALLER_ORDER: Comparator<RepairScopeLine> =
            compareBy<RepairScopeLine>({ it.kind.category.ordinal }, { it.kind.ordinal }, { it.id })

        val OUTPUT_ORDER: Comparator<RepairEstimateLine> =
            compareBy<RepairEstimateLine>({ it.category.ordinal }, { it.kind.ordinal }, { it.id })
    }
}
