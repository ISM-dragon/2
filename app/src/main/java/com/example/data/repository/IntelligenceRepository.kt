package com.example.data.repository

import com.example.data.local.dao.IntelligenceDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.*
import com.example.domain.ai.GeminiManager
import com.example.domain.intelligence.job.JobProgressState
import com.example.domain.intelligence.job.PropertyImportJobManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class CompleteDealRoomData(
    val property: PropertyEntity,
    val images: List<PropertyImageEntity>,
    val sources: List<PropertySourceLinkEntity>,
    val provenance: List<PropertyProvenanceEntity>,
    val enrichment: PropertyEnrichmentEntity?,
    val comps: List<PropertyCompEntity>,
    val financials: PropertyFinancialEntity?,
    val aiAnalysis: PropertyAiAnalysisEntity?
)

class IntelligenceRepository(
    private val intelligenceDao: IntelligenceDao,
    private val propertyDao: PropertyDao,
    private val geminiManager: GeminiManager
) {
    val jobManager = PropertyImportJobManager(intelligenceDao, propertyDao, geminiManager)
    val currentJobProgress: StateFlow<JobProgressState?> = jobManager.currentJobProgress

    suspend fun importPropertyUrl(url: String, forceRefresh: Boolean = false): JobProgressState {
        return jobManager.startImportJob(url, forceRefresh)
    }

    fun cancelJob(jobId: String) {
        jobManager.cancelJob(jobId)
    }

    fun clearJob() {
        jobManager.clearProgress()
    }

    fun getSourcesForProperty(propertyId: String): Flow<List<PropertySourceLinkEntity>> =
        intelligenceDao.getSourcesForProperty(propertyId)

    fun getProvenanceForProperty(propertyId: String): Flow<List<PropertyProvenanceEntity>> =
        intelligenceDao.getProvenanceForProperty(propertyId)

    fun getCompsForProperty(propertyId: String): Flow<List<PropertyCompEntity>> =
        intelligenceDao.getCompsForProperty(propertyId)

    fun getEnrichmentFlow(propertyId: String): Flow<PropertyEnrichmentEntity?> =
        intelligenceDao.getEnrichmentFlow(propertyId)

    fun getFinancialsFlow(propertyId: String): Flow<PropertyFinancialEntity?> =
        intelligenceDao.getFinancialsFlow(propertyId)

    fun getAiAnalysisFlow(propertyId: String): Flow<PropertyAiAnalysisEntity?> =
        intelligenceDao.getAiAnalysisFlow(propertyId)

    fun getSourceHealthFlow(): Flow<List<SourceHealthEntity>> =
        intelligenceDao.getAllSourceHealthFlow()

    suspend fun getCompleteDealRoom(propertyId: String): CompleteDealRoomData? {
        val property = propertyDao.getPropertyById(propertyId) ?: return null
        val sources = intelligenceDao.findSourceByUrl("") // or list
        val provenance = intelligenceDao.getProvenanceList(propertyId)
        val enrichment = intelligenceDao.getEnrichment(propertyId)
        val financials = intelligenceDao.getFinancials(propertyId)
        val ai = intelligenceDao.getAiAnalysis(propertyId)

        return CompleteDealRoomData(
            property = property,
            images = emptyList(),
            sources = emptyList(),
            provenance = provenance,
            enrichment = enrichment,
            comps = emptyList(),
            financials = financials,
            aiAnalysis = ai
        )
    }
}
