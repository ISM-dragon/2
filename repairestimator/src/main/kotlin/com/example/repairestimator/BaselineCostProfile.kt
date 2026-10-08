package com.example.repairestimator

/**
 * Built-in planning profiles.
 *
 * [US_RESIDENTIAL_PLACEHOLDER_2026] is an UNCALIBRATED placeholder. Its unit costs are midpoints of
 * published 2026 national installed-price ranges, each cited in its [UnitCost.reference]. Its policy
 * values (intensities, finish grades, general conditions, permit minimum) are engine assumptions with no
 * market source. Every estimate that uses it carries a PLACEHOLDER_PROFILE warning. Replace it with local
 * bids before relying on a total.
 */
object BaselineCostProfiles {

    const val US_RESIDENTIAL_PLACEHOLDER_2026_ID: String = "US_RESIDENTIAL_PLACEHOLDER_2026"

    val US_RESIDENTIAL_PLACEHOLDER_2026: CostProfile = CostProfile(
        id = US_RESIDENTIAL_PLACEHOLDER_2026_ID,
        version = "2026.10-1",
        displayName = "US residential planning placeholder (2026)",
        origin = ProfileOrigin.PLACEHOLDER_BUILT_IN,
        sourceNote = "Unit costs are midpoints of 2026 national installed ranges from the sources cited per kind. " +
            "Policy values are engine assumptions. Not calibrated to any market or contractor. Replace with local bids.",
        unitCosts = mapOf(
            RepairItemKind.ROOF_COVERING to UnitCost(
                650.0,
                "Architectural asphalt shingle, installed, $550-$800/SQ (buildvisionai.com, 2026)",
            ),
            RepairItemKind.HVAC_SYSTEM to UnitCost(
                8_500.0,
                "Furnace plus central AC replacement, $5,000-$12,500 (vannuyshvacpro.com, 2026); " +
                    "typical $7,000-$15,000 (candcair.com, 2026)",
            ),
            RepairItemKind.HVAC_DUCTWORK to UnitCost(
                40.0,
                "Installed duct, $20-$60/LF (hvacprojectcost.com, 2026); sheet metal $35-$60/LF (pipelineon.com, 2026)",
            ),
            RepairItemKind.PLUMBING_REPIPE to UnitCost(
                5.25,
                "PEX repipe installed, $3.50-$7.00/LF (ibuyer.com, 2026)",
            ),
            RepairItemKind.PLUMBING_FIXTURE to UnitCost(
                350.0,
                "Toilet replacement average $350; faucet $250 and vanity sink $500 averages (currentcost.org, 2026)",
            ),
            RepairItemKind.WATER_HEATER to UnitCost(
                1_500.0,
                "40-50 gal tank installed, $882-$1,814 (rateyourplumber.com, 2026); $1,000-$2,000 (g4electrical.com, 2026)",
            ),
            RepairItemKind.ELECTRICAL_PANEL to UnitCost(
                2_500.0,
                "200-amp panel replacement, $1,500-$3,000 (ibelectric.com, 2026); $1,800-$4,000 (nearmetips.com, 2026)",
            ),
            RepairItemKind.ELECTRICAL_REWIRE to UnitCost(
                6.0,
                "Whole-house rewire, $3-$9/sq ft (caudills.com, 2026); $6-$15/sq ft (baltimorechronicle.com, 2026)",
            ),
            RepairItemKind.KITCHEN_CABINETS to UnitCost(
                450.0,
                "Semi-custom cabinets installed, $300-$720/LF (estimationpro.ai, 2026); $250-$650/LF (drcabinet.com, 2026)",
            ),
            RepairItemKind.KITCHEN_COUNTERTOPS to UnitCost(
                100.0,
                "Quartz installed, $50-$150/sq ft (msisurfaces.com, 2026); $65-$150/sq ft (kitchenremodelingranked.com, 2026)",
            ),
            RepairItemKind.KITCHEN_APPLIANCES to UnitCost(
                3_750.0,
                "Appliance package (fridge, microwave, range, dishwasher), $2,100-$5,400 (homeguide.com, 2026)",
            ),
            RepairItemKind.BATHROOM_FULL to UnitCost(
                15_000.0,
                "Mid-range full bath, $10,000-$25,000 (costtobuildhouse.com, 2026); average $12,400 (costprism.com, 2026)",
            ),
            RepairItemKind.BATHROOM_HALF to UnitCost(
                6_750.0,
                "Half bath, $3,500-$10,000 (costtobuildhouse.com, 2026)",
            ),
            RepairItemKind.FLOORING_HARD_SURFACE to UnitCost(
                6.0,
                "Luxury vinyl plank installed, $3-$9/sq ft (realcostiq.com, 2026)",
            ),
            RepairItemKind.FLOORING_CARPET to UnitCost(
                4.5,
                "Mid-grade carpet installed, $4.50/sq ft; range $3-$6 (carpetnow.com, 2026)",
            ),
            RepairItemKind.PAINT_INTERIOR to UnitCost(
                4.0,
                "Walls and ceilings, $3.00-$5.00 per sq ft of floor area (clearhomeprojects.com, 2026)",
            ),
            RepairItemKind.WINDOW_REPLACEMENT to UnitCost(
                975.0,
                "Vinyl window installed, $650-$1,300 (simplywise.com, 2026); $800-$1,600 (pella.com, 2026)",
            ),
            RepairItemKind.EXTERIOR_SIDING to UnitCost(
                6.0,
                "Mid-range vinyl siding installed, $4-$8/sq ft (amazingexteriors.com, 2026)",
            ),
            RepairItemKind.EXTERIOR_PAINT to UnitCost(
                3.0,
                "Exterior paint, $1.50-$4.50/sq ft of paintable surface (facadecolorizer.com, 2026)",
            ),
            RepairItemKind.EXTERIOR_GUTTERS to UnitCost(
                11.0,
                "Seamless aluminum gutters installed, $9-$13/LF (bigriverroofs.com, 2026); $6-$14/LF (gutters4lessga.com, 2026)",
            ),
            RepairItemKind.FOUNDATION_CRACK_REPAIR to UnitCost(
                525.0,
                "Minor crack repair, $250-$800 per crack (servicover.com, 2026)",
            ),
            RepairItemKind.FOUNDATION_PIER_SUPPORT to UnitCost(
                2_500.0,
                "Steel push pier $1,500-$3,500 (twobrosfoundationrepair.com, 2026); helical pier $2,000-$4,000 (same source)",
            ),
            RepairItemKind.LABOR_HOURS to UnitCost(
                65.0,
                "Handyman hourly rate, national average $50-$80/hr (kickbackservices.com, 2026)",
            ),
        ),
        conditionIntensity = ConditionIntensity(
            excellent = 0.0,
            good = 0.10,
            fair = 0.40,
            poor = 1.00,
            failed = 1.25,
            reference = "Engine assumption, no market source. EXCELLENT: no work. GOOD: service only. " +
                "FAIR: partial repair. POOR: full replacement. FAILED: replacement plus tear-out.",
        ),
        strategyPolicies = mapOf(
            RepairStrategy.WHOLESALE to StrategyPolicy(
                contingencyPct = 15.0,
                finishMultiplier = 1.0,
                reference = "Wholesale: end-buyer scope without owner access, so higher contingency (engine assumption). " +
                    "Resale-grade finishes.",
            ),
            RepairStrategy.BRRRR to StrategyPolicy(
                contingencyPct = 10.0,
                finishMultiplier = 0.90,
                reference = "BRRRR: durable rent-ready finishes at 0.90 (engine assumption). Contingency 10% matches " +
                    "UnderwritingAssumptions.REHAB_CONTINGENCY_PCT in the app.",
            ),
            RepairStrategy.FIX_AND_FLIP to StrategyPolicy(
                contingencyPct = 10.0,
                finishMultiplier = 1.0,
                reference = "Fix-and-flip: resale finishes at 1.00. Contingency 10% matches " +
                    "UnderwritingAssumptions.REHAB_CONTINGENCY_PCT in the app.",
            ),
        ),
        permitPolicy = PermitPolicy(
            pctOfPermitScope = 1.5,
            minimumFee = 150.0,
            reference = "Permit fees typically 1%-2.5% of construction value (permitmint.com, 2026; goldenstatede.com, 2025). " +
                "1.5% and the $150 minimum are engine assumptions.",
        ),
        generalConditions = PercentRule(
            pct = 5.0,
            reference = "Engine assumption, no market source: haul-away, cleanup and supervision as 5% of priced trade work.",
        ),
        unknownConditionReserve = PercentRule(
            pct = 0.0,
            reference = "Engine default: no reserve. Inspection-required lines stay unpriced unless the profile sets a reserve.",
        ),
    )
}
