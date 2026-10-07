package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Canonical Room representation of a US investment property.
 *
 * The first block of columns is the legacy/UI contract that the existing screens, view models and
 * automation engine already rely on. The second block ("canonical US fields") is the normalized,
 * deduplicated identity of the property and is populated exclusively by the import pipeline
 * (`PropertyImportRepository`) through [com.example.domain.property.UsPropertyNormalizer].
 *
 * Deduplication contract:
 *  - [canonicalKey] is a deterministic, human readable identity key (normalized street + unit +
 *    city + state + ZIP5). It is UNIQUE when present; legacy rows migrated from v2 keep it NULL
 *    until the deduplication reconcile pass claims them, therefore multiple NULLs are allowed.
 *  - [apn] (Assessor Parcel Number) is the authoritative US county-level parcel identifier and is
 *    used as the strongest non-source-specific match signal.
 *  - [primarySourceId] is a soft pointer to the source that discovered the property; every source
 *    that has seen this property is recorded in `property_provenance`.
 */
@Entity(
    tableName = "properties",
    indices = [
        Index(value = ["canonicalKey"], unique = true),
        Index(value = ["primarySourceId"]),
        Index(value = ["state", "city"]),
        Index(value = ["zipCode"]),
        Index(value = ["countyFips"]),
        Index(value = ["apn"]),
        Index(value = ["mlsNumber"]),
        Index(value = ["status"]),
        Index(value = ["scannedAt"]),
        Index(value = ["dealScore"]),
        Index(value = ["isSaved"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PropertySourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["primarySourceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class PropertyEntity(
    @PrimaryKey
    val id: String,
    val sourceType: String, // "ON_MARKET", "OFF_MARKET", "WHOLESALE", "FORECLOSURE"
    val title: String,
    val address: String,
    val city: String,
    val state: String,
    val zipCode: String,
    val latitude: Double,
    val longitude: Double,
    val price: Double,
    val propertyType: String, // "Single Family", "Multi-Family", "Condo", "Townhouse", "Commercial"
    val bedrooms: Int,
    val bathrooms: Double,
    val squareFeet: Int,
    val yearBuilt: Int,
    val lotSizeSqFt: Int,
    val description: String,
    val status: String, // "Active", "Pending", "Off-Market", "Qualified"
    val primaryImageUrl: String,
    val scannedAt: Long,
    val isSaved: Boolean = false,
    val isSavedDeal: Boolean = false,
    val dealScore: Int = 0, // 0 to 100

    // ── Canonical US fields (v3 data layer) ─────────────────────────────────────────────────────
    val unitNumber: String = "",            // US "unit" / "apt" / "ste" designator, normalized
    val normalizedAddress: String = "",     // single line canonical form used for display/search
    val canonicalKey: String? = null,       // UNIQUE identity key, see class KDoc
    val county: String = "",                // US county name, e.g. "Travis"
    val countyFips: String = "",            // 5 digit county FIPS, e.g. "48453"
    val apn: String = "",                   // Assessor Parcel Number, e.g. "0412345678"
    val mlsNumber: String = "",             // listing id inside the originating MLS
    val propertySubType: String = "",       // canonical sub type, e.g. "DUPLEX", "CONDO"
    val halfBathrooms: Int = 0,             // US convention tracks half baths separately
    val stories: Int = 0,
    val garageSpaces: Int = 0,
    val hasPool: Boolean = false,
    val hoaMonthly: Double = 0.0,
    val primarySourceId: String? = null,    // FK -> property_sources.id (SET NULL on source delete)
    val listingStatusUpdatedAt: Long = 0L,
    val lastVerifiedAt: Long = 0L
)

@Entity(
    tableName = "property_images",
    indices = [Index(value = ["propertyId"])],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PropertyImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    val imageUrl: String,
    val caption: String = "",
    val isPrimary: Boolean = false
)

@Entity(
    tableName = "market_data",
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MarketDataEntity(
    @PrimaryKey
    val propertyId: String,
    val estimatedValue: Double,
    val neighborhoodAppreciationRate: Double,
    val medianAreaPrice: Double,
    val averageDaysOnMarket: Int,
    val pricePerSqFt: Double,
    val marketDemand: String // "High", "Moderate", "Balanced", "Buyer's Market"
)

@Entity(
    tableName = "rent_estimates",
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RentEstimateEntity(
    @PrimaryKey
    val propertyId: String,
    val estimatedRent: Double,
    val rentRangeLow: Double,
    val rentRangeHigh: Double,
    val rentConfidenceScore: Double,
    val grossYield: Double
)

@Entity(
    tableName = "tax_records",
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TaxRecordEntity(
    @PrimaryKey
    val propertyId: String,
    val annualTaxAmount: Double,
    val assessmentYear: Int,
    val assessedValue: Double,
    val taxDelinquent: Boolean = false
)

@Entity(
    tableName = "sales_history",
    indices = [Index(value = ["propertyId", "date"])],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SalesHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    val date: String,
    val price: Double,
    val event: String
)

/**
 * Canonical comparable sale / comparable listing ("comp") attached to a subject property.
 *
 * Replaces the legacy `comparable_properties` table (renamed and widened in migration 2 -> 3).
 * The legacy field names are intentionally preserved so existing screens keep compiling; see the
 * [ComparablePropertyEntity] type alias at the bottom of this file.
 *
 * Deduplication: the (targetPropertyId, compAddress, saleDate) triple is UNIQUE. `compAddress`
 * must always be written normalized by [com.example.domain.property.UsPropertyNormalizer] so the
 * same comp coming from two feeds collapses into a single row.
 */
@Entity(
    tableName = "property_comps",
    indices = [
        Index(value = ["targetPropertyId", "compAddress", "saleDate"], unique = true),
        Index(value = ["targetPropertyId", "similarityScore"]),
        Index(value = ["compPropertyId"]),
        Index(value = ["sourceId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetPropertyId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["compPropertyId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = PropertySourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class PropertyCompEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetPropertyId: String,
    /** Internal property id when the comp itself exists in this database. */
    val compPropertyId: String? = null,
    /** Source that produced the comp (MLS, county records, ...). */
    val sourceId: String? = null,
    // ── Legacy/UI contract (names are consumed by PropertyDetailScreen + AiChatRepository) ──────
    val compAddress: String,
    val compPrice: Double,
    val compBeds: Int,
    val compBaths: Double,
    val compSqFt: Int,
    val distanceMiles: Double,
    val saleDate: String,
    val adjustmentAmount: Double = 0.0,
    // ── Canonical US comp fields ────────────────────────────────────────────────────────────────
    val compUnit: String = "",
    val compCity: String = "",
    val compState: String = "",
    val compZipCode: String = "",
    val compPropertyType: String = "",
    val compYearBuilt: Int = 0,
    val compLotSizeSqFt: Int = 0,
    /** "SOLD", "ACTIVE", "PENDING", "EXPIRED", "WITHDRAWN" */
    val compStatus: String = "SOLD",
    val compLatitude: Double = 0.0,
    val compLongitude: Double = 0.0,
    val pricePerSqFt: Double = 0.0,
    /** Price after all adjustments, i.e. the value the comp suggests for the subject property. */
    val adjustedPrice: Double = 0.0,
    /** 0..100 similarity to the subject property, used to rank and to filter weak comps. */
    val similarityScore: Double = 0.0,
    /** Serialized adjustment breakdown (JSON) produced by the valuation engine. */
    val adjustmentsJson: String = "",
    val isActiveListing: Boolean = false
)

/**
 * @deprecated Legacy name kept as a source-compatible alias for the pre-v3 entity. New code must
 * use [PropertyCompEntity] directly. Kept at zero UI churn cost on purpose: the legacy entity had
 * exactly the same field names for the shared columns.
 */
@Deprecated(
    message = "Use PropertyCompEntity; comparable_properties was folded into property_comps in v3.",
    replaceWith = ReplaceWith("PropertyCompEntity")
)
typealias ComparablePropertyEntity = PropertyCompEntity
