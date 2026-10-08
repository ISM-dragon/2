package com.example.data.repository

import com.example.data.adapter.PropertyUrlImportBridge
import com.example.data.local.dao.PropertyDao
import com.example.data.local.dao.PropertyEnrichmentDao
import com.example.data.local.dao.PropertyFinancialDao
import com.example.data.local.dao.PropertySourceDao
import com.example.data.local.entity.*
import com.example.domain.intelligence.job.JobProgressState
import com.example.domain.intelligence.job.JobStatus
import kotlinx.coroutines.flow.*

data class CompleteDealRoomData(
    val property: PropertyEntity,
    val images: List<PropertyImageEntity>,
    val sources: List<PropertySourceLinkEntity>,
    val provenance: List<PropertyProvenanceEntity>,
    val enrichment: PropertyEnrichmentEntity?,
    val comps: List<ComparablePropertyEntity>,
    val financials: PropertyFinancialEntity?,
    val aiAnalysis: PropertyAiAnalysisEntity?
)

class IntelligenceRepository(
    private val propertyUrlImporter: PropertyUrlImportBridge,
    private val propertyDao: PropertyDao,
    private val sourceDao: PropertySourceDao,
    private val enrichmentDao: PropertyEnrichmentDao,
    private val financialDao: PropertyFinancialDao
) {
    private val _currentJobProgress = MutableStateFlow<JobProgressState?>(null)
    val currentJobProgress: StateFlow<JobProgressState?> = _currentJobProgress.asStateFlow()

    suspend fun importPropertyUrl(url: String, forceRefresh: Boolean = false): JobProgressState {
        _currentJobProgress.value = JobProgressState(
            jobId = "job-" + System.currentTimeMillis(),
            url = url,
            source = "Web",
            status = JobStatus.QUEUED,
            progressPercent = 0.1f,
            currentStepDescription = "Starting import..."
        )
        try {
            val outcome = propertyUrlImporter.importAndStore(url)
            val propertyId = outcome.propertyOrNull()?.canonicalId
            val success = outcome.isSuccess
            val state = JobProgressState(
                jobId = outcome.job.jobId,
                url = url,
                source = "Web",
                status = if (success) JobStatus.COMPLETED else JobStatus.FAILED,
                progressPercent = 1.0f,
                currentStepDescription = if (success) "Import completed successfully" else "Import failed",
                propertyId = propertyId,
                errorMessage = outcome.failure?.message
            )
            _currentJobProgress.value = state
            return state
        } catch (e: Exception) {
            val errState = JobProgressState(
                jobId = "err-" + System.currentTimeMillis(),
                url = url,
                source = "Web",
                status = JobStatus.FAILED,
                progressPercent = 0f,
                currentStepDescription = "Import error",
                errorMessage = e.message
            )
            _currentJobProgress.value = errState
            return errState
        }
    }

    fun cancelJob(jobId: String) {
        _currentJobProgress.value = null
    }

    fun clearJob() {
        _currentJobProgress.value = null
    }

    fun getSourcesForProperty(propertyId: String): Flow<List<PropertySourceLinkEntity>> =
        flowOf(emptyList())

    fun getProvenanceForProperty(propertyId: String): Flow<List<PropertyProvenanceEntity>> =
        sourceDao.observeProvenanceForProperty(propertyId)

    fun getCompsForProperty(propertyId: String): Flow<List<ComparablePropertyEntity>> =
        propertyDao.getCompsForProperty(propertyId)

    fun getEnrichmentFlow(propertyId: String): Flow<PropertyEnrichmentEntity?> =
        enrichmentDao.observeForProperty(propertyId).map { it.firstOrNull() }

    fun getFinancialsFlow(propertyId: String): Flow<PropertyFinancialEntity?> =
        financialDao.observeFinancials(propertyId)

    fun getAiAnalysisFlow(propertyId: String): Flow<PropertyAiAnalysisEntity?> =
        flowOf(null)

    fun getSourceHealthFlow(): Flow<List<SourceHealthEntity>> =
        flowOf(emptyList())

    suspend fun getCompleteDealRoom(propertyId: String): CompleteDealRoomData? {
        val property = propertyDao.getPropertyById(propertyId) ?: return null
        val provenance = sourceDao.getProvenanceForProperty(propertyId)
        val enrichment = enrichmentDao.getForProperty(propertyId).firstOrNull()
        val financials = financialDao.getFinancials(propertyId)

        return CompleteDealRoomData(
            property = property,
            images = propertyDao.getImagesListForProperty(propertyId),
            sources = emptyList(),
            provenance = provenance,
            enrichment = enrichment,
            comps = propertyDao.getCompsListForProperty(propertyId),
            financials = financials,
            aiAnalysis = null
        )
    }
}
