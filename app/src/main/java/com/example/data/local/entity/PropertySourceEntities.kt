package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Kinds of property data sources. Kept as plain string constants (same convention as
 * [GmailAuthStatus]) because the Room entities intentionally avoid type converters.
 */
object PropertySourceKind {
    const val MLS = "MLS"                       // Licensed MLS/IDX feed
    const val PUBLIC_RECORDS = "PUBLIC_RECORDS" // County assessor / recorder bulk data
    const val WHOLESALER = "WHOLESALER"         // Wholesale / off-market list provider
    const val AUCTION = "AUCTION"               // Foreclosure / trustee auction feed
    const val PARTNER_API = "PARTNER_API"       // Third party API (ATTOM, HouseCanary, ...)
    const val USER_ENTERED = "USER_ENTERED"     // Manually added by the investor
    const val INTERNAL = "INTERNAL"             // Seeded/demo content shipped with the app
}

/** Lifecycle of a single import run. */
object PropertyImportStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val PARTIAL = "PARTIAL"   // finished, but at least one record failed validation
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

/** How a record entered the system, recorded per provenance row. */
object PropertyIngestionMethod {
    const val API = "API"
    const val SCRAPE = "SCRAPE"
    const val FILE = "FILE"
    const val SEED = "SEED"
    const val MANUAL = "MANUAL"
}

/** Sync status of a source, mirrors the UI status strings used by the automation screens. */
object PropertySourceSyncStatus {
    const val NEVER = "NEVER"
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val PARTIAL = "PARTIAL"
    const val FAILED = "FAILED"
    const val DISABLED = "DISABLED"
}

/**
 * A configured origin of property data (an MLS/IDX feed, a county recorder export, a wholesaler
 * API, a partner enrichment API, or the manual entry screen).
 *
 * Sources are the anchor of the provenance and import-job tables: every property can be traced back
 * to the sources that reported it, and every import run belongs to exactly one source.
 */
@Entity(
    tableName = "property_sources",
    indices = [
        Index(value = ["adapterKey"], unique = true),
        Index(value = ["sourceKind"]),
        Index(value = ["isEnabled"]),
        Index(value = ["priority"])
    ]
)
data class PropertySourceEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    /** One of [PropertySourceKind]. */
    val sourceKind: String,
    /** Stable key of the adapter implementation, e.g. "mls.demo.v1". Unique. */
    val adapterKey: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    /** Lower value wins when two sources disagree about the same field. */
    val priority: Int = 100,
    val requiresAttribution: Boolean = false,
    val attributionText: String = "",
    val licenseNotes: String = "",
    val maxRequestsPerMinute: Int = 60,
    val refreshIntervalMinutes: Int = 60,
    val lastSyncAt: Long = 0L,
    /** One of [PropertySourceSyncStatus]. */
    val lastSyncStatus: String = PropertySourceSyncStatus.NEVER,
    val lastSyncError: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Where a specific property record came from.
 *
 * This is the primary deduplication anchor: `(sourceId, externalId)` is UNIQUE, so re-importing the
 * same source record can never create a second row. One property usually owns several provenance
 * rows (the same house reported by the MLS and by a wholesaler) - that is the intended shape of the
 * "one canonical property, many source records" model.
 */
@Entity(
    tableName = "property_provenance",
    indices = [
        Index(value = ["sourceId", "externalId"], unique = true),
        Index(value = ["propertyId", "isPrimaryForProperty"]),
        Index(value = ["ingestJobId"]),
        Index(value = ["rawPayloadHash"]),
        Index(value = ["lastSeenAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PropertySourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PropertyImportJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingestJobId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class PropertyProvenanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    val sourceId: String,
    /** Identifier of this record inside the source. Never blank. */
    val externalId: String,
    val externalUrl: String = "",
    /** One of [PropertyIngestionMethod]. */
    val ingestionMethod: String = PropertyIngestionMethod.API,
    /** Import run that produced (or last refreshed) this row. */
    val ingestJobId: String? = null,
    /** 0.0 .. 1.0 - how much the source is trusted for this record. */
    val confidence: Double = 1.0,
    /** True for the source that currently "owns" the displayed values. */
    val isPrimaryForProperty: Boolean = false,
    /** When the payload was pulled from the source. */
    val fetchedAt: Long,
    /** When the source itself claims the record was last modified (0 = unknown). */
    val sourceUpdatedAt: Long = 0L,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** SHA-256 of the raw payload; lets the pipeline skip unchanged records. */
    val rawPayloadHash: String = "",
    /** External storage reference (file path / object key) of the raw payload. */
    val rawPayloadRef: String = ""
)

/**
 * One import/sync run against a single source.
 *
 * Jobs are the audit trail of ingestion and the input for deduplication metrics: [recordsMerged]
 * counts records that matched an existing canonical property instead of creating a new one.
 * `cursor` allows a partially completed run to resume where it stopped.
 *
 * The table is append-only: rows are pruned by age, never mutated into a destructive state.
 */
@Entity(
    tableName = "property_import_jobs",
    indices = [
        Index(value = ["sourceId", "status"]),
        Index(value = ["status", "startedAt"]),
        Index(value = ["startedAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PropertySourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PropertyImportJobEntity(
    @PrimaryKey
    val id: String,
    val sourceId: String,
    /** One of [PropertyImportStatus]. */
    val status: String = PropertyImportStatus.PENDING,
    /** "MANUAL", "SCHEDULED", "AUTOMATION", "APP_START", "SEED". */
    val triggerKind: String = "MANUAL",
    val cursor: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val recordsFetched: Int = 0,
    val recordsInserted: Int = 0,
    val recordsUpdated: Int = 0,
    /** Records that matched an existing canonical property (dedup hit). */
    val recordsMerged: Int = 0,
    /** Records rejected by validation before touching the database. */
    val recordsSkipped: Int = 0,
    val recordsFailed: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Stable identifiers of the sources that ship with the app.
 *
 * The same literals are seeded by `Migration2To3` (rows are created with `INSERT OR IGNORE`) so a
 * migrated database and a freshly created one end up with identical source rows. Unit tests assert
 * that the migrated database contains exactly these ids.
 */
object PropertySourceDefaults {
    const val MLS_ID = "src-mls-default"
    const val WHOLESALE_ID = "src-wholesale-default"
    const val COUNTY_RECORDS_ID = "src-county-records-default"
    const val INTERNAL_ID = "src-internal-default"
    const val MANUAL_ID = "src-user-entered-default"

    val ALL: List<String> = listOf(MLS_ID, WHOLESALE_ID, COUNTY_RECORDS_ID, INTERNAL_ID, MANUAL_ID)

    /** Maps the legacy `sourceType` column onto the canonical source rows. */
    fun sourceIdForSourceType(sourceType: String): String = when (sourceType.uppercase()) {
        "ON_MARKET" -> MLS_ID
        "OFF_MARKET", "WHOLESALE" -> WHOLESALE_ID
        "FORECLOSURE" -> COUNTY_RECORDS_ID
        else -> INTERNAL_ID
    }
}
