package com.example.repairestimator

/**
 * Structural checks on a request. Every problem is reported at once, so a caller can fix them in one pass.
 * Each check is a rule the engine relies on for correctness, so an invalid request is rejected instead of
 * being priced on a guess.
 */
internal object RepairRequestValidator {

    /** Kinds whose amount must be stated explicitly, because no meaningful default exists. */
    private val REQUIRES_AMOUNT: Set<RepairItemKind> = setOf(
        RepairItemKind.GENERAL_CONDITIONS,
        RepairItemKind.PERMIT_FEES,
    )

    fun validate(request: RepairEstimateRequest): List<RepairIssue> {
        val errors = ArrayList<RepairIssue>()
        errors += CostProfileValidator.validate(request.profile)

        val lines = request.lines
        if (lines.isEmpty()) {
            errors += error(
                RepairIssueCode.EMPTY_SCOPE,
                "no scope lines supplied: add at least one line (use condition EXCELLENT for 'inspected, no work needed')",
            )
        }
        if (lines.size > RepairLimits.MAX_LINES) {
            errors += error(
                RepairIssueCode.TOO_MANY_LINES,
                "${lines.size} scope lines supplied; the limit is ${RepairLimits.MAX_LINES}",
            )
        }
        request.contingencyPctOverride?.let { pct ->
            if (!pct.isFinite() || pct < 0.0 || pct > RepairLimits.MAX_PERCENT) {
                errors += error(
                    RepairIssueCode.INVALID_CONTINGENCY_OVERRIDE,
                    "contingency override must be finite, >= 0 and <= ${RepairLimits.MAX_PERCENT}",
                )
            }
        }

        val seenIds = HashSet<String>()
        lines.forEachIndexed { index, line -> validateLine(index, line, seenIds, errors) }
        return errors
    }

    private fun validateLine(index: Int, line: RepairScopeLine, seenIds: MutableSet<String>, out: MutableList<RepairIssue>) {
        val position = "line #${index + 1}"
        val id = line.id

        if (id.isBlank()) {
            out += error(RepairIssueCode.BLANK_LINE_ID, "$position has a blank id", null)
        } else {
            if (id.startsWith(RepairLimits.AUTO_ID_PREFIX)) {
                out += error(
                    RepairIssueCode.RESERVED_LINE_ID,
                    "line id '$id' uses the reserved prefix '${RepairLimits.AUTO_ID_PREFIX}'",
                    id,
                )
            }
            if (!seenIds.add(id)) {
                out += error(RepairIssueCode.DUPLICATE_LINE_ID, "line id '$id' is used by more than one line", id)
            }
        }

        val kind = line.kind
        if (kind.role == KindRole.POLICY_ONLY) {
            out += error(
                RepairIssueCode.POLICY_KIND_NOT_SUPPLIABLE,
                "${kind.name} is calculated by the cost profile and cannot be entered as a scope line",
                id,
            )
        }

        line.quantity?.let { quantity ->
            if (!quantity.isFinite() || quantity <= 0.0 || quantity > RepairLimits.MAX_QUANTITY) {
                out += error(
                    RepairIssueCode.INVALID_QUANTITY,
                    "quantity for $position must be finite, > 0 and <= ${RepairLimits.MAX_QUANTITY}",
                    id,
                )
            }
        }

        line.unitCostOverride?.let { override ->
            if (!override.isFinite() || override < 0.0 || override > RepairLimits.MAX_UNIT_COST) {
                out += error(
                    RepairIssueCode.INVALID_UNIT_COST,
                    "unit cost for $position must be finite, >= 0 and <= ${RepairLimits.MAX_UNIT_COST}",
                    id,
                )
            }
        }

        line.unit?.let { unit ->
            if (kind.role != KindRole.POLICY_ONLY && UnitConversions.factor(unit, kind.pricingUnit) == null) {
                val accepted = UnitConversions.acceptedUnits(kind.pricingUnit).joinToString { it.symbol }
                out += error(
                    RepairIssueCode.UNIT_NOT_ACCEPTED,
                    "unit ${unit.symbol} is not accepted for ${kind.name}; accepted units: $accepted",
                    id,
                )
            }
        }

        if (kind in REQUIRES_AMOUNT && line.unitCostOverride == null) {
            out += error(
                RepairIssueCode.EXPLICIT_AMOUNT_REQUIRED,
                "${kind.name} on $position needs unitCostOverride, the dollar amount for the lump sum",
                id,
            )
        }
    }

    private fun error(code: RepairIssueCode, message: String, lineId: String? = null): RepairIssue =
        RepairIssue(code, RepairIssueSeverity.ERROR, message, lineId)
}
