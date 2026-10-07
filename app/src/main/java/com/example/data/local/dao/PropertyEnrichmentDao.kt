package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.PropertyEnrichmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PropertyEnrichmentDao {

    @Query("SELECT * FROM property_enrichments WHERE propertyId = :propertyId ORDER BY enrichmentType ASC, provider ASC")
    fun observeForProperty(propertyId: String): Flow<List<PropertyEnrichmentEntity>>

    @Query("SELECT * FROM property_enrichments WHERE propertyId = :propertyId ORDER BY enrichmentType ASC, provider ASC")
    suspend fun getForProperty(propertyId: String): List<PropertyEnrichmentEntity>

    @Query(
        "SELECT * FROM property_enrichments WHERE propertyId = :propertyId AND enrichmentType = :enrichmentType " +
            "ORDER BY confidence DESC, effectiveAt DESC"
    )
    suspend fun getByType(propertyId: String, enrichmentType: String): List<PropertyEnrichmentEntity>

    @Query(
        "SELECT * FROM property_enrichments WHERE propertyId = :propertyId AND enrichmentType = :enrichmentType " +
            "AND provider = :provider LIMIT 1"
    )
    suspend fun find(propertyId: String, enrichmentType: String, provider: String): PropertyEnrichmentEntity?

    @Query("SELECT * FROM property_enrichments WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PropertyEnrichmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(enrichment: PropertyEnrichmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(enrichments: List<PropertyEnrichmentEntity>)

    /** Supersedes the previous value of the same (property, type, provider) triple. */
    @Query(
        "UPDATE property_enrichments SET valueNumeric = :valueNumeric, valueText = :valueText, unit = :unit, " +
            "confidence = :confidence, effectiveAt = :effectiveAt, expiresAt = :expiresAt, " +
            "ingestedAt = :ingestedAt, payloadHash = :payloadHash, rawPayloadRef = :payloadRef, " +
            "provenanceId = :provenanceId, notes = :notes, isCurrent = 1 WHERE id = :id"
    )
    suspend fun refresh(
        id: Long,
        valueNumeric: Double?,
        valueText: String,
        unit: String,
        confidence: Double,
        effectiveAt: Long,
        expiresAt: Long,
        ingestedAt: Long,
        payloadHash: String,
        payloadRef: String,
        provenanceId: Long?,
        notes: String
    )

    @Query("UPDATE property_enrichments SET isCurrent = 0 WHERE propertyId = :propertyId AND enrichmentType = :enrichmentType AND provider = :provider")
    suspend fun supersede(propertyId: String, enrichmentType: String, provider: String)

    @Query("SELECT * FROM property_enrichments WHERE isCurrent = 1 AND expiresAt > 0 AND expiresAt <= :now LIMIT :limit")
    suspend fun getExpired(now: Long, limit: Int): List<PropertyEnrichmentEntity>

    @Query("SELECT * FROM property_enrichments")
    suspend fun getAll(): List<PropertyEnrichmentEntity>

    @Query("DELETE FROM property_enrichments WHERE propertyId = :propertyId")
    suspend fun deleteForProperty(propertyId: String)

    @Query("DELETE FROM property_enrichments WHERE propertyId = :propertyId AND enrichmentType = :enrichmentType")
    suspend fun deleteByType(propertyId: String, enrichmentType: String)

    @Query("SELECT COUNT(*) FROM property_enrichments WHERE propertyId = :propertyId")
    suspend fun countForProperty(propertyId: String): Int
}
