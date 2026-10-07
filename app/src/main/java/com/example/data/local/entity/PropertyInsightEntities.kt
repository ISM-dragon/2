package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Third party enrichment categories stored in `property_enrichments`. */
object PropertyEnrichmentType {
    const val RENT_AVM = "RENT_AVM"                 // Automated rental valuation
    const val VALUATION_AVM = "VALUATION_AVM"       // Automated sales valuation
    const val FLOOD_RISK = "FLOOD_RISK"             // FEMA / flood zone
    const val SCHOOL_RATING = "SCHOOL_RATING"       // Assigned school + rating
    const val WALK_SCORE = "WALK_SCORE"             // Walk / transit / bike scores
    const val CRIME_INDEX = "CRIME_INDEX"           // Neighborhood crime index
    const val OWNER_OCCUPANCY = "OWNER_OCCUPANCY"   // Owner occupied vs investor owned
    const val TAX_ASSESSMENT = "TAX_ASSESSMENT"     // County assessment facts
    const val PERMIT = "PERMIT"                     // Recorded building permits
    const val LIEN = "LIEN"                         // Recorded liens / judgments
    const val HOA = "HOA"                           // HOA schedule and dues
    const val MARKET_TREND = "MARKET_TREND"         // Zip/county level trend snapshots
}

/** Provenance class of the numbers stored in `property_financials`. */
object PropertyFinancialDataSource {
    const val RECORDS = "RECORDS"     // County assessor / recorder facts
    const val AVM = "AVM"             // Automated valuation model
    const val ESTIMATE = "ESTIMATE"   // Internal heuristic derived from the listing itself
    const val MANUAL = "MANUAL"       // Entered or overridden by the investor
}

/**
 * Provider supplied facts about a property that are not part of the listing itself (rent AVM,
 * valuation AVM, flood zone, school rating, permits, liens, ...).
 *
 * Boundary with the legacy satellite tables:
 *  - `market_data` / `rent_estimates` remain the *single, UI facing* snapshot the screens read.
 *  - `property_enrichments` is the *multi-provider* store: one row per
 *    (propertyId, enrichmentType, provider) which makes conflicts between providers explicit
 *    instead of silently overwriting each other.
 *  - Unlike the listing columns, enriched values carry their own confidence, effective date and a
 *    link to the [PropertyProvenanceEntity] row that produced them.
 */
@Entity(
    tableName = "property_enrichments",
    indices = [
        Index(value = ["propertyId", "enrichmentType", "provider"], unique = true),
        Index(value = ["propertyId", "enrichmentType"]),
        Index(value = ["provenanceId"]),
        Index(value = ["expiresAt"]),
        Index(value = ["isCurrent"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PropertyProvenanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["provenanceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class PropertyEnrichmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    /** One of [PropertyEnrichmentType]. */
    val enrichmentType: String,
    /** Provider key, e.g. "ATTOM", "HOUSECANARY", "internal.rent-model". */
    val provider: String,
    val valueNumeric: Double? = null,
    val valueText: String = "",
    /** Unit of [valueNumeric]: "USD", "USD/month", "USD/sqft", "pct", "score", "years". */
    val unit: String = "",
    /** 0.0 .. 100.0 confidence reported by the provider (0 = unknown). */
    val confidence: Double = 0.0,
    /** When the value became effective at the source. */
    val effectiveAt: Long = 0L,
    /** When the value must be refreshed (0 = never expires). */
    val expiresAt: Long = 0L,
    /** False for superseded values kept for history. */
    val isCurrent: Boolean = true,
    val ingestedAt: Long,
    val payloadHash: String = "",
    val rawPayloadRef: String = "",
    val provenanceId: Long? = null,
    val notes: String = ""
)

/**
 * Canonical, provider independent financial facts about a property (1:1 with `properties`).
 *
 * Boundary with `financial_analyses`: `financial_analyses` stores the *result of an underwriting
 * run* for a chosen set of assumptions (the investor's deal model). This table stores the *facts
 * about the asset* (taxes, insurance, HOA, achievable rent, yields) that are independent of any
 * deal the investor is modelling, and that every underwriting run should read as its baseline.
 */
@Entity(
    tableName = "property_financials",
    indices = [
        Index(value = ["lastUpdatedAt"]),
        Index(value = ["provenanceId"]),
        Index(value = ["assessmentYear"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PropertyProvenanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["provenanceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class PropertyFinancialEntity(
    @PrimaryKey
    val propertyId: String,
    // ── Property tax facts ──────────────────────────────────────────────────────────────────────
    val assessedValue: Double = 0.0,
    val assessmentYear: Int = 0,
    val annualPropertyTax: Double = 0.0,
    /** Effective (not nominal) tax rate in percent, i.e. tax / market value * 100. */
    val effectiveTaxRatePct: Double = 0.0,
    // ── Recurring ownership costs ───────────────────────────────────────────────────────────────
    val annualInsurance: Double = 0.0,
    val hoaMonthly: Double = 0.0,
    val capitalReserveMonthly: Double = 0.0,
    val maintenanceReserveMonthly: Double = 0.0,
    // ── Income facts ────────────────────────────────────────────────────────────────────────────
    val monthlyRentEstimate: Double = 0.0,
    val rentEstimateLow: Double = 0.0,
    val rentEstimateHigh: Double = 0.0,
    /** 0.0 .. 100.0 */
    val rentConfidence: Double = 0.0,
    val marketRentPerSqFt: Double = 0.0,
    // ── Derived US market metrics ───────────────────────────────────────────────────────────────
    /** Gross rent multiplier = price / annual gross rent. */
    val grossRentMultiplier: Double = 0.0,
    val grossYieldPct: Double = 0.0,
    val operatingExpenseRatioPct: Double = 0.0,
    val vacancyRatePct: Double = 0.0,
    /** One of [PropertyFinancialDataSource]. */
    val dataSource: String = PropertyFinancialDataSource.ESTIMATE,
    val provenanceId: Long? = null,
    val lastUpdatedAt: Long
)
