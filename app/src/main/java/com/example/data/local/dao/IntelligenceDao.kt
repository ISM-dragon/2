package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface IntelligenceDao {

    // Import Jobs
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateJob(job: PropertyImportJobEntity)

    @Query("SELECT * FROM property_import_jobs WHERE jobId = :jobId LIMIT 1")
    suspend fun getJobById(jobId: String): PropertyImportJobEntity?

    @Query("SELECT * FROM property_import_jobs WHERE jobId = :jobId LIMIT 1")
    fun getJobFlow(jobId: String): Flow<PropertyImportJobEntity?>

    @Query("SELECT * FROM property_import_jobs WHERE url = :url ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestJobForUrl(url: String): PropertyImportJobEntity?

    @Query("SELECT * FROM property_import_jobs ORDER BY createdAt DESC")
    fun getAllJobsFlow(): Flow<List<PropertyImportJobEntity>>

    // Property Sources
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSourceLink(sourceLink: PropertySourceLinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSourceLinks(sourceLinks: List<PropertySourceLinkEntity>)

    @Query("SELECT * FROM property_sources WHERE propertyId = :propertyId")
    fun getSourcesForProperty(propertyId: String): Flow<List<PropertySourceLinkEntity>>

    @Query("SELECT * FROM property_sources WHERE sourceUrl = :url LIMIT 1")
    suspend fun findSourceByUrl(url: String): PropertySourceLinkEntity?

    // Provenance
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvenanceRecords(records: List<PropertyProvenanceEntity>)

    @Query("SELECT * FROM property_provenance WHERE propertyId = :propertyId")
    fun getProvenanceForProperty(propertyId: String): Flow<List<PropertyProvenanceEntity>>

    @Query("SELECT * FROM property_provenance WHERE propertyId = :propertyId")
    suspend fun getProvenanceList(propertyId: String): List<PropertyProvenanceEntity>

    // Comps
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComps(comps: List<PropertyCompEntity>)

    @Query("SELECT * FROM property_comps WHERE targetPropertyId = :propertyId")
    fun getCompsForProperty(propertyId: String): Flow<List<PropertyCompEntity>>

    // Enrichment
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnrichment(enrichment: PropertyEnrichmentEntity)

    @Query("SELECT * FROM property_enrichment WHERE propertyId = :propertyId LIMIT 1")
    fun getEnrichmentFlow(propertyId: String): Flow<PropertyEnrichmentEntity?>

    @Query("SELECT * FROM property_enrichment WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getEnrichment(propertyId: String): PropertyEnrichmentEntity?

    // Financials
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFinancials(financial: PropertyFinancialEntity)

    @Query("SELECT * FROM property_financials WHERE propertyId = :propertyId LIMIT 1")
    fun getFinancialsFlow(propertyId: String): Flow<PropertyFinancialEntity?>

    @Query("SELECT * FROM property_financials WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getFinancials(propertyId: String): PropertyFinancialEntity?

    // AI Analysis
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiAnalysis(aiAnalysis: PropertyAiAnalysisEntity)

    @Query("SELECT * FROM property_ai_analysis WHERE propertyId = :propertyId LIMIT 1")
    fun getAiAnalysisFlow(propertyId: String): Flow<PropertyAiAnalysisEntity?>

    @Query("SELECT * FROM property_ai_analysis WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getAiAnalysis(propertyId: String): PropertyAiAnalysisEntity?

    // Source Health
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSourceHealth(health: SourceHealthEntity)

    @Query("SELECT * FROM source_health WHERE source = :source LIMIT 1")
    suspend fun getSourceHealth(source: String): SourceHealthEntity?

    @Query("SELECT * FROM source_health")
    fun getAllSourceHealthFlow(): Flow<List<SourceHealthEntity>>

    @Query("SELECT * FROM source_health")
    suspend fun getAllSourceHealthList(): List<SourceHealthEntity>

    // Transaction for Complete Canonical Property Ingestion
    @Transaction
    suspend fun persistCompletePropertyIngestion(
        property: PropertyEntity,
        images: List<PropertyImageEntity>,
        sourceLink: PropertySourceLinkEntity,
        provenanceRecords: List<PropertyProvenanceEntity>,
        enrichment: PropertyEnrichmentEntity,
        comps: List<PropertyCompEntity>,
        financials: PropertyFinancialEntity,
        aiAnalysis: PropertyAiAnalysisEntity?
    ) {
        insertSourceLink(sourceLink)
        insertProvenanceRecords(provenanceRecords)
        insertEnrichment(enrichment)
        insertComps(comps)
        insertFinancials(financials)
        if (aiAnalysis != null) {
            insertAiAnalysis(aiAnalysis)
        }
    }
}
