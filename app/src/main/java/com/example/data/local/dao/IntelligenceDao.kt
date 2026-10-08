package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface IntelligenceDao {

    @Query("SELECT * FROM property_source_links WHERE sourceUrl = :url LIMIT 1")
    suspend fun findSourceByUrl(url: String): PropertySourceLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateJob(job: PropertyImportJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSourceHealth(health: SourceHealthEntity)

    @Query("SELECT * FROM source_health WHERE source = :source LIMIT 1")
    suspend fun getSourceHealth(source: String): SourceHealthEntity?

    @Query("SELECT * FROM property_source_links WHERE propertyId = :propertyId")
    fun getSourcesForProperty(propertyId: String): Flow<List<PropertySourceLinkEntity>>

    @Query("SELECT * FROM property_provenance WHERE propertyId = :propertyId")
    fun getProvenanceForProperty(propertyId: String): Flow<List<PropertyProvenanceEntity>>

    @Query("SELECT * FROM property_provenance WHERE propertyId = :propertyId")
    suspend fun getProvenanceList(propertyId: String): List<PropertyProvenanceEntity>

    @Query("SELECT * FROM property_comps WHERE targetPropertyId = :propertyId")
    fun getCompsForProperty(propertyId: String): Flow<List<ComparablePropertyEntity>>

    @Query("SELECT * FROM property_enrichments WHERE propertyId = :propertyId LIMIT 1")
    fun getEnrichmentFlow(propertyId: String): Flow<PropertyEnrichmentEntity?>

    @Query("SELECT * FROM property_enrichments WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getEnrichment(propertyId: String): PropertyEnrichmentEntity?

    @Query("SELECT * FROM property_financials WHERE propertyId = :propertyId LIMIT 1")
    fun getFinancialsFlow(propertyId: String): Flow<PropertyFinancialEntity?>

    @Query("SELECT * FROM property_financials WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getFinancials(propertyId: String): PropertyFinancialEntity?

    @Query("SELECT * FROM property_ai_analysis WHERE propertyId = :propertyId LIMIT 1")
    fun getAiAnalysisFlow(propertyId: String): Flow<PropertyAiAnalysisEntity?>

    @Query("SELECT * FROM property_ai_analysis WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getAiAnalysis(propertyId: String): PropertyAiAnalysisEntity?

    @Query("SELECT * FROM source_health")
    fun getAllSourceHealthFlow(): Flow<List<SourceHealthEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSourceLink(link: PropertySourceLinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvenanceList(list: List<PropertyProvenanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnrichment(enrichment: PropertyEnrichmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComps(comps: List<ComparablePropertyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFinancials(financials: PropertyFinancialEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiAnalysis(ai: PropertyAiAnalysisEntity)

    @Transaction
    suspend fun persistCompletePropertyIngestion(
        property: PropertyEntity,
        images: List<PropertyImageEntity>,
        sourceLink: PropertySourceLinkEntity?,
        provenanceRecords: List<PropertyProvenanceEntity>,
        enrichment: PropertyEnrichmentEntity?,
        comps: List<ComparablePropertyEntity>,
        financials: PropertyFinancialEntity?,
        aiAnalysis: PropertyAiAnalysisEntity?
    ) {
        if (sourceLink != null) insertSourceLink(sourceLink)
        if (provenanceRecords.isNotEmpty()) insertProvenanceList(provenanceRecords)
        if (enrichment != null) insertEnrichment(enrichment)
        if (comps.isNotEmpty()) insertComps(comps)
        if (financials != null) insertFinancials(financials)
        if (aiAnalysis != null) insertAiAnalysis(aiAnalysis)
    }
}
