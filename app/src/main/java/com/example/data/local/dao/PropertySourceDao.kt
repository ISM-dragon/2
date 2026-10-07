package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.PropertyImportJobEntity
import com.example.data.local.entity.PropertyProvenanceEntity
import com.example.data.local.entity.PropertySourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Ingestion side of the data layer: configured sources, per-record provenance and import jobs.
 *
 * All writes are executed by `PropertyImportRepository` inside `withTransaction` blocks so a single
 * imported record always lands atomically with its provenance and job counters.
 */
@Dao
interface PropertySourceDao {

    // ── Sources ─────────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM property_sources ORDER BY priority ASC, name ASC")
    fun observeSources(): Flow<List<PropertySourceEntity>>

    @Query("SELECT * FROM property_sources WHERE isEnabled = 1 ORDER BY priority ASC, name ASC")
    suspend fun getEnabledSources(): List<PropertySourceEntity>

    @Query("SELECT * FROM property_sources WHERE id = :id LIMIT 1")
    suspend fun getSourceById(id: String): PropertySourceEntity?

    @Query("SELECT * FROM property_sources WHERE adapterKey = :adapterKey LIMIT 1")
    suspend fun getSourceByAdapterKey(adapterKey: String): PropertySourceEntity?

    @Query("SELECT COUNT(*) FROM property_sources")
    suspend fun countSources(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceIfAbsent(source: PropertySourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: PropertySourceEntity)

    @Query(
        "UPDATE property_sources SET lastSyncAt = :syncedAt, lastSyncStatus = :status, " +
            "lastSyncError = :error, updatedAt = :syncedAt WHERE id = :id"
    )
    suspend fun updateSyncStatus(id: String, syncedAt: Long, status: String, error: String?)

    // ── Provenance ──────────────────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProvenance(provenance: PropertyProvenanceEntity): Long

    @Query("SELECT * FROM property_provenance WHERE id = :id LIMIT 1")
    suspend fun getProvenanceById(id: Long): PropertyProvenanceEntity?

    @Query("SELECT * FROM property_provenance WHERE sourceId = :sourceId AND externalId = :externalId LIMIT 1")
    suspend fun findProvenance(sourceId: String, externalId: String): PropertyProvenanceEntity?

    @Query(
        "SELECT * FROM property_provenance WHERE propertyId = :propertyId " +
            "ORDER BY isPrimaryForProperty DESC, lastSeenAt DESC"
    )
    fun observeProvenanceForProperty(propertyId: String): Flow<List<PropertyProvenanceEntity>>

    @Query(
        "SELECT * FROM property_provenance WHERE propertyId = :propertyId " +
            "ORDER BY isPrimaryForProperty DESC, lastSeenAt DESC"
    )
    suspend fun getProvenanceForProperty(propertyId: String): List<PropertyProvenanceEntity>

    @Query("SELECT COUNT(*) FROM property_provenance WHERE propertyId = :propertyId")
    suspend fun countProvenanceForProperty(propertyId: String): Int

    @Query(
        "UPDATE property_provenance SET lastSeenAt = :seenAt, fetchedAt = :seenAt, " +
            "ingestJobId = :jobId, confidence = :confidence, rawPayloadHash = :payloadHash, " +
            "rawPayloadRef = :payloadRef, sourceUpdatedAt = :sourceUpdatedAt WHERE id = :id"
    )
    suspend fun refreshProvenance(
        id: Long,
        seenAt: Long,
        jobId: String?,
        confidence: Double,
        payloadHash: String,
        payloadRef: String,
        sourceUpdatedAt: Long
    )

    @Query("UPDATE property_provenance SET isPrimaryForProperty = 0 WHERE propertyId = :propertyId")
    suspend fun clearPrimaryProvenance(propertyId: String)

    @Query("UPDATE property_provenance SET isPrimaryForProperty = 1 WHERE id = :id")
    suspend fun markPrimaryProvenance(id: Long)

    /** Dedup merge: moves every source record of the losing property onto the surviving one. */
    @Query("UPDATE property_provenance SET propertyId = :targetPropertyId WHERE propertyId = :sourcePropertyId")
    suspend fun repointProvenance(sourcePropertyId: String, targetPropertyId: String)

    @Query("DELETE FROM property_provenance WHERE propertyId = :propertyId")
    suspend fun deleteProvenanceForProperty(propertyId: String)

    // ── Import jobs ─────────────────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: PropertyImportJobEntity)

    @Query("SELECT * FROM property_import_jobs WHERE id = :id LIMIT 1")
    suspend fun getJob(id: String): PropertyImportJobEntity?

    @Query("SELECT * FROM property_import_jobs WHERE sourceId = :sourceId ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestJobForSource(sourceId: String): PropertyImportJobEntity?

    @Query("SELECT * FROM property_import_jobs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentJobs(limit: Int): Flow<List<PropertyImportJobEntity>>

    @Query(
        "UPDATE property_import_jobs SET status = :status, finishedAt = :finishedAt, " +
            "cursor = :cursor, recordsFetched = :fetched, recordsInserted = :inserted, " +
            "recordsUpdated = :updated, recordsMerged = :merged, recordsSkipped = :skipped, " +
            "recordsFailed = :failed, lastError = :error, updatedAt = :finishedAt WHERE id = :id"
    )
    suspend fun finishJob(
        id: String,
        status: String,
        finishedAt: Long,
        cursor: String?,
        fetched: Int,
        inserted: Int,
        updated: Int,
        merged: Int,
        skipped: Int,
        failed: Int,
        error: String?
    )

    @Query("SELECT COUNT(*) FROM property_import_jobs")
    suspend fun countJobs(): Int

    /** Prunes old job history; provenance rows keep working through ON DELETE SET NULL. */
    @Query("DELETE FROM property_import_jobs WHERE startedAt < :olderThan AND status <> 'RUNNING'")
    suspend fun pruneJobs(olderThan: Long): Int
}
