package com.example.repairestimator

import java.math.BigDecimal

/** Units a quantity can be stated in. Each item kind has one pricing unit; other units must convert to it. */
enum class MeasureUnit(val symbol: String) {
    EACH("EA"),
    SQUARE("SQ"),
    SQUARE_FOOT("SF"),
    LINEAR_FOOT("LF"),
    HOUR("HR"),
    LUMP_SUM("LS"),
}

/**
 * The only unit conversions the engine performs. Any other combination is a validation error, never a guess.
 */
object UnitConversions {

    /** Multiplier that converts one unit of [from] into [target], or null when no conversion is defined. */
    fun factor(from: MeasureUnit, target: MeasureUnit): BigDecimal? {
        if (from == target) return BigDecimal.ONE
        return when (from to target) {
            // a roofing square is 100 square feet
            MeasureUnit.SQUARE_FOOT to MeasureUnit.SQUARE -> BigDecimal("0.01")
            else -> null
        }
    }

    /** Every unit a line for [pricingUnit] may be stated in. Always contains [pricingUnit] itself. */
    fun acceptedUnits(pricingUnit: MeasureUnit): Set<MeasureUnit> =
        MeasureUnit.entries.filterTo(LinkedHashSet()) { factor(it, pricingUnit) != null }
}

/** How an item kind is priced. Determines which fields a scope line may use and which rules apply. */
enum class KindRole {
    /** Physical scope: profile unit cost x condition intensity (x strategy finish grade for finish items). */
    TRADE,

    /** Explicit hourly labor: hours x hourly rate. Not condition-based. */
    LABOR_HOURS,

    /**
     * General conditions and permit fees. Calculated by a profile rule, unless the caller supplies a
     * priced lump sum, which replaces the rule.
     */
    EXPLICIT_OR_RULE,

    /** A caller-stated allowance for hidden or unknown scope. The profile never prices it. */
    CALLER_ALLOWANCE,

    /** Calculated only by the profile (contingency, unknown-condition reserve). A caller may not supply it. */
    POLICY_ONLY,
}

/**
 * Every kind of cost line the estimator knows about.
 *
 * [permitTriggerByDefault] says whether the kind adds to the permit base unless a line overrides it.
 * [finishItem] says whether the strategy's finish multiplier applies to the kind.
 */
enum class RepairItemKind(
    val category: RehabCategory,
    val label: String,
    val role: KindRole,
    val pricingUnit: MeasureUnit,
    val permitTriggerByDefault: Boolean = false,
    val finishItem: Boolean = false,
) {
    ROOF_COVERING(
        RehabCategory.ROOF, "Roof covering (shingle, metal or tile)", KindRole.TRADE, MeasureUnit.SQUARE,
        permitTriggerByDefault = true,
    ),

    HVAC_SYSTEM(
        RehabCategory.HVAC, "Heating and cooling system (furnace, AC or heat pump)", KindRole.TRADE, MeasureUnit.EACH,
        permitTriggerByDefault = true,
    ),
    HVAC_DUCTWORK(RehabCategory.HVAC, "Ductwork", KindRole.TRADE, MeasureUnit.LINEAR_FOOT),

    PLUMBING_REPIPE(
        RehabCategory.PLUMBING, "Supply and drain repipe", KindRole.TRADE, MeasureUnit.LINEAR_FOOT,
        permitTriggerByDefault = true,
    ),
    PLUMBING_FIXTURE(RehabCategory.PLUMBING, "Plumbing fixture (faucet, toilet or vanity sink)", KindRole.TRADE, MeasureUnit.EACH),
    WATER_HEATER(
        RehabCategory.PLUMBING, "Water heater", KindRole.TRADE, MeasureUnit.EACH,
        permitTriggerByDefault = true,
    ),

    ELECTRICAL_PANEL(
        RehabCategory.ELECTRICAL, "Electrical service panel", KindRole.TRADE, MeasureUnit.EACH,
        permitTriggerByDefault = true,
    ),
    ELECTRICAL_REWIRE(
        RehabCategory.ELECTRICAL, "Rewire (priced per square foot of living area)", KindRole.TRADE, MeasureUnit.SQUARE_FOOT,
        permitTriggerByDefault = true,
    ),

    KITCHEN_CABINETS(
        RehabCategory.KITCHEN, "Cabinets, installed (per linear foot)", KindRole.TRADE, MeasureUnit.LINEAR_FOOT,
        finishItem = true,
    ),
    KITCHEN_COUNTERTOPS(
        RehabCategory.KITCHEN, "Countertops, installed (per square foot)", KindRole.TRADE, MeasureUnit.SQUARE_FOOT,
        finishItem = true,
    ),
    KITCHEN_APPLIANCES(
        RehabCategory.KITCHEN, "Appliance package (range, refrigerator, dishwasher, microwave)", KindRole.TRADE, MeasureUnit.EACH,
        finishItem = true,
    ),

    BATHROOM_FULL(RehabCategory.BATHROOMS, "Full bathroom remodel", KindRole.TRADE, MeasureUnit.EACH, finishItem = true),
    BATHROOM_HALF(RehabCategory.BATHROOMS, "Half bath or powder room remodel", KindRole.TRADE, MeasureUnit.EACH, finishItem = true),

    FLOORING_HARD_SURFACE(
        RehabCategory.FLOORING, "Hard-surface flooring (LVP, tile or hardwood)", KindRole.TRADE, MeasureUnit.SQUARE_FOOT,
        finishItem = true,
    ),
    FLOORING_CARPET(RehabCategory.FLOORING, "Carpet", KindRole.TRADE, MeasureUnit.SQUARE_FOOT, finishItem = true),

    PAINT_INTERIOR(
        RehabCategory.PAINT, "Interior paint, walls and ceilings (per square foot of floor area)", KindRole.TRADE,
        MeasureUnit.SQUARE_FOOT, finishItem = true,
    ),

    WINDOW_REPLACEMENT(RehabCategory.WINDOWS, "Window replacement (per window)", KindRole.TRADE, MeasureUnit.EACH),

    EXTERIOR_SIDING(RehabCategory.EXTERIOR, "Siding (per square foot of wall area)", KindRole.TRADE, MeasureUnit.SQUARE_FOOT),
    EXTERIOR_PAINT(
        RehabCategory.EXTERIOR, "Exterior paint (per square foot of paintable surface)", KindRole.TRADE, MeasureUnit.SQUARE_FOOT,
    ),
    EXTERIOR_GUTTERS(RehabCategory.EXTERIOR, "Gutters and downspouts", KindRole.TRADE, MeasureUnit.LINEAR_FOOT),

    FOUNDATION_CRACK_REPAIR(
        RehabCategory.FOUNDATION, "Foundation crack repair (per crack)", KindRole.TRADE, MeasureUnit.EACH,
        permitTriggerByDefault = true,
    ),
    FOUNDATION_PIER_SUPPORT(
        RehabCategory.FOUNDATION, "Foundation pier support (per pier)", KindRole.TRADE, MeasureUnit.EACH,
        permitTriggerByDefault = true,
    ),

    LABOR_HOURS(RehabCategory.LABOR, "Labor hours (explicit hourly work)", KindRole.LABOR_HOURS, MeasureUnit.HOUR),
    GENERAL_CONDITIONS(
        RehabCategory.LABOR, "General conditions (haul-away, cleanup, supervision)", KindRole.EXPLICIT_OR_RULE,
        MeasureUnit.LUMP_SUM,
    ),

    PERMIT_FEES(RehabCategory.PERMITS, "Permit and inspection fees", KindRole.EXPLICIT_OR_RULE, MeasureUnit.LUMP_SUM),

    CONTINGENCY(RehabCategory.CONTINGENCY, "Contingency", KindRole.POLICY_ONLY, MeasureUnit.LUMP_SUM),

    UNKNOWN_SCOPE_ALLOWANCE(
        RehabCategory.UNKNOWN_INSPECTION_REQUIRED, "Allowance for hidden or unknown scope (caller amount)",
        KindRole.CALLER_ALLOWANCE, MeasureUnit.LUMP_SUM,
    ),
    UNKNOWN_CONDITION_RESERVE(
        RehabCategory.UNKNOWN_INSPECTION_REQUIRED, "Reserve for inspection-required trade items (profile rule)",
        KindRole.POLICY_ONLY, MeasureUnit.LUMP_SUM,
    ),
    ;

    /** True for kinds whose unit cost comes from the cost profile and whose amount is a unit cost times a quantity. */
    val isProfilePriced: Boolean
        get() = role == KindRole.TRADE || role == KindRole.LABOR_HOURS

    companion object {
        /** Kinds the cost profile must give a unit cost for. Every other kind is priced by a rule or by the caller. */
        val PROFILE_PRICED: Set<RepairItemKind> = entries.filterTo(LinkedHashSet()) { it.isProfilePriced }
    }
}

/** Limits enforced by validation. They are part of the contract and are documented in docs/repair-estimator.md. */
object RepairLimits {
    const val MAX_LINES: Int = 500
    const val MAX_QUANTITY: Double = 1_000_000.0
    const val MAX_UNIT_COST: Double = 10_000_000.0
    const val MAX_PERCENT: Double = 100.0
    const val MAX_INTENSITY: Double = 5.0
    const val MAX_FINISH_MULTIPLIER: Double = 5.0

    /** Prefix reserved for engine-generated line ids. Caller ids may not use it. */
    const val AUTO_ID_PREFIX: String = "auto:"
}
