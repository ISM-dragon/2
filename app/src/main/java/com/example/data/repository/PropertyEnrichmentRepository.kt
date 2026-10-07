package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.dao.PropertyEnrichmentDao
import com.example.data.local.entity.PropertyEnrichmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Multi-provider enrichment store (rent AVM, valuation AVM, flood, schools, permits, liens, ...).
 *
 * Values are keyed by (propertyId, enrichmentType, provider) so two providers can disagree without
 * overwriting each other; the readers pick by confidence. `expiresAt` supports refresh scheduling.
 */
class PropertyEnrichmentRepository(
    private val database: AppDatabase,
    private val enrichmentDao: PropertyEnrichmentDao,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun observeForProperty(propertyId: String): Flow<List<PropertyEnrichmentEntity>> =
        enrichmentDao.observeForProperty(propertyId)

    suspend fun getForProperty(propertyId: String): List<PropertyEnrichmentEntity> =
        enrichmentDao.getForProperty(propertyId)

    suspend fun getByType(propertyId: String, enrichmentType: String): List<PropertyEnrichmentEntity> =
        enrichmentDao.getByType(propertyId, enrichmentType)

    /** Highest confidence current value for a type, or null when nothing was enriched yet. */
    suspend fun latest(propertyId: String, enrichmentType: String): PropertyEnrichmentEntity? =
        enrichmentDao.getByType(propertyId, enrichmentType)
            .filter { it.isCurrent }
            .maxByOrNull { it.confidence }

    suspend fun upsert(enrichment: PropertyEnrichmentEntity): Long = withContext(Dispatchers.IO) {
        database.withTransaction {
            val existing = enrichmentDao.find(
                propertyId = enrichment.propertyId,
                enrichmentType = enrichment.enrichmentType,
                provider = enrichment.provider
            )
            val now = clock()
            if (existing == null) {
                enrichmentDao.upsert(
                    enrichment.copy(
                        isCurrent = true,
                        ingestedAt = if (enrichment.ingestedAt == 0L) now else enrichment.ingestedAt
                    )
                )
            } else {
                enrichmentDao.refresh(
                    id = existing.id,
                    valueNumeric = enrichment.valueNumeric,
                    valueText = enrichment.valueText,
                    unit = enrichment.unit,
                    confidence = enrichment.confidence,
                    effectiveAt = enrichment.effectiveAt,
                    expiresAt = enrichment.expiresAt,
                    ingestedAt = if (enrichment.ingestedAt == 0L) now else enrichment.ingestedAt,
                    payloadHash = enrichment.payloadHash,
                    payloadRef = enrichment.rawPayloadRef,
                    provenanceId = enrichment.provenanceId,
                    notes = enrichment.notes
                )
                existing.id
            }
        }
    }

    suspend fun importAll(propertyId: String, enrichments: List<PropertyEnrichmentEntity>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                enrichments.forEach { enrichment ->
                    upsert(enrichment.copy(propertyId = propertyId, id = 0))
                }
            }
        }

    /** Marks every stale value as superseded; returns how many rows changed. */
    suspend fun expireStale(limit: Int = 500): Int = withContext(Dispatchers.IO) {
        val now = clock()
        var expired = 0
        database.withTransaction {
            for (enrichment in enrichmentDao.getExpired(now, limit)) {
                enrichmentDao.supersede(
                    propertyId = enrichment.propertyId,
                    enrichmentType = enrichment.enrichmentType,
                    provider = enrichment.provider
                )
                expired++
            }
        }
        expired
    }

    suspend fun deleteForProperty(propertyId: String) = withContext(Dispatchers.IO) {
        enrichmentDao.deleteForProperty(propertyId)
    }

    suspend fun deleteType(propertyId: String, enrichmentType: String) = withContext(Dispatchers.IO) {
        enrichmentDao.deleteByType(propertyId, enrichmentType)
    }
}
