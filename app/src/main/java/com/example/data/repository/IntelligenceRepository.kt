package com.example.data.repository

import com.example.data.adapter.PropertyUrlImportBridge
import com.example.data.local.dao.IntelligenceDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.dao.PropertyEnrichmentDao
import com.example.data.local.dao.PropertyFinancialDao
import com.example.data.local.dao.PropertySourceDao
import com.example.data.local.entity.*
import com.example.domain.intelligence.job.JobProgressState
import com.example.domain.intelligence.job.JobStatus
import kotlinx.coroutines.flow.*

/**
 * One-shot snapshot of everything the deal room knows about a property.
 *
 * Every field mirrors a persisted table. Absence is reported as an empty list or `null` — the
 * repository never substitutes a placeholder for a value the database does not hold.
 */
data class CompleteDealRoomData(
    val property: PropertyEntity,
    val images: List<PropertyImageEntity>,
    val sources: List<PropertySourceLinkEntity>,
    val provenance: List<PropertyProvenanceEntity>,
    /** Current, highest-confidence enrichment row (see [IntelligenceRepository.getEnrichmentFlow]). */
    val enrichment: PropertyEnrichmentEntity?,
    val comps: List<ComparablePropertyEntity>,
    val financials: PropertyFinancialEntity?,
    val aiAnalysis: PropertyAiAnalysisEntity?,
    /** Every enrichment row stored for the property, current and superseded, in DAO order. */
    val enrichments: List<PropertyEnrichmentEntity> = emptyList()
)

/**
 * Read model of the intelligence layer.
 *
 * The repository is a thin, read-mostly facade over the persisted truth:
 *
 *  - source links ........ `property_source_links` via [IntelligenceDao]
 *  - provenance .......... `property_provenance`   via [PropertySourceDao]
 *  - comps ............... `property_comps`        via [PropertyDao]
 *  - enrichment .......... `property_enrichments`  via [PropertyEnrichmentDao]
 *  - financial facts ..... `property_financials`   via [PropertyFinancialDao]
 *  - AI analysis ......... `property_ai_analysis`  via [IntelligenceDao]
 *  - source health ....... `source_health`         via [IntelligenceDao]
 *
 * Writes keep flowing through `PropertyImportRepository` (listing + provenance) and the dedicated
 * enrichment/financial repositories; this class only adds the URL import entry point used by the
 * Discover screen, which delegates to [PropertyUrlImportBridge] and therefore to the same
 * `PropertyRepository.insertBundle` path as before.
 *
 * Contract: when a table has no row for a property the corresponding flow emits an empty list or
 * `null`. No defaults, estimates or demo values are ever synthesised here.
 */
class IntelligenceRepository(
    private val propertyUrlImporter: PropertyUrlImportBridge,
    private val propertyDao: PropertyDao,
    private val sourceDao: PropertySourceDao,
    private val enrichmentDao: PropertyEnrichmentDao,
    private val financialDao: PropertyFinancialDao,
    private val intelligenceDao: IntelligenceDao
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

    // ── Source links ────────────────────────────────────────────────────────────────────────────

    /**
     * Portal/listing links recorded for the property, primary link first, then most recently
     * synced. Emits an empty list when no link has been persisted.
     */
    fun getSourcesForProperty(propertyId: String): Flow<List<PropertySourceLinkEntity>> =
        intelligenceDao.getSourcesForProperty(propertyId).map { links -> orderSourceLinks(links) }

    // ── Provenance ──────────────────────────────────────────────────────────────────────────────

    /** Source records that produced the property, primary record first (DAO ordering). */
    fun getProvenanceForProperty(propertyId: String): Flow<List<PropertyProvenanceEntity>> =
        sourceDao.observeProvenanceForProperty(propertyId)

    // ── Comps ───────────────────────────────────────────────────────────────────────────────────

    /** Persisted comparable sales, best similarity first (DAO ordering). */
    fun getCompsForProperty(propertyId: String): Flow<List<ComparablePropertyEntity>> =
        propertyDao.getCompsForProperty(propertyId)

    // ── Enrichment ──────────────────────────────────────────────────────────────────────────────

    /** Every enrichment row stored for the property (all types, all providers, incl. history). */
    fun getEnrichmentsForProperty(propertyId: String): Flow<List<PropertyEnrichmentEntity>> =
        enrichmentDao.observeForProperty(propertyId)

    /**
     * Single-row view consumed by the deal room: the current (not superseded) enrichment with the
     * highest provider confidence, most recent effective date breaking ties. `null` when the
     * property has no current enrichment — superseded history is never promoted to "current".
     */
    fun getEnrichmentFlow(propertyId: String): Flow<PropertyEnrichmentEntity?> =
        enrichmentDao.observeForProperty(propertyId).map { rows -> selectCurrentEnrichment(rows) }

    // ── Financial facts ─────────────────────────────────────────────────────────────────────────

    /** Canonical asset-level financial facts; `null` until something has been persisted. */
    fun getFinancialsFlow(propertyId: String): Flow<PropertyFinancialEntity?> =
        financialDao.observeFinancials(propertyId)

    // ── AI analysis ─────────────────────────────────────────────────────────────────────────────

    /** Last persisted AI analyst result for the property, `null` when none has been stored. */
    fun getAiAnalysisFlow(propertyId: String): Flow<PropertyAiAnalysisEntity?> =
        intelligenceDao.getAiAnalysisFlow(propertyId)

    // ── Source health ───────────────────────────────────────────────────────────────────────────

    /** Health counters of every source that has reported at least once, ordered by source key. */
    fun getSourceHealthFlow(): Flow<List<SourceHealthEntity>> =
        intelligenceDao.getAllSourceHealthFlow().map { rows -> rows.sortedBy { it.source } }

    /** Health counters of one source, `null` when it has never reported. */
    suspend fun getSourceHealth(source: String): SourceHealthEntity? =
        intelligenceDao.getSourceHealth(source)

    // ── Snapshot ────────────────────────────────────────────────────────────────────────────────

    /**
     * One-shot read of every table the deal room renders. Returns `null` when the property itself
     * does not exist; otherwise each satellite reflects exactly what is persisted.
     */
    suspend fun getCompleteDealRoom(propertyId: String): CompleteDealRoomData? {
        val property = propertyDao.getPropertyById(propertyId) ?: return null
        val enrichments = enrichmentDao.getForProperty(propertyId)

        return CompleteDealRoomData(
            property = property,
            images = propertyDao.getImagesListForProperty(propertyId),
            sources = orderSourceLinks(intelligenceDao.getSourcesListForProperty(propertyId)),
            provenance = sourceDao.getProvenanceForProperty(propertyId),
            enrichment = selectCurrentEnrichment(enrichments),
            comps = propertyDao.getCompsListForProperty(propertyId),
            financials = financialDao.getFinancials(propertyId),
            aiAnalysis = intelligenceDao.getAiAnalysis(propertyId),
            enrichments = enrichments
        )
    }

    private fun orderSourceLinks(links: List<PropertySourceLinkEntity>): List<PropertySourceLinkEntity> =
        links.sortedWith(
            compareByDescending<PropertySourceLinkEntity> { it.isPrimary }
                .thenByDescending { it.lastSyncedAt }
                .thenBy { it.id }
        )

    private fun selectCurrentEnrichment(rows: List<PropertyEnrichmentEntity>): PropertyEnrichmentEntity? =
        rows.filter { it.isCurrent }
            .sortedWith(
                compareByDescending<PropertyEnrichmentEntity> { it.confidence }
                    .thenByDescending { it.effectiveAt }
                    .thenByDescending { it.ingestedAt }
            )
            .firstOrNull()
}
