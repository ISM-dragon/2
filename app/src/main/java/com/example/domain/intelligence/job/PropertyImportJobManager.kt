package com.example.domain.intelligence.job

import com.example.data.local.dao.IntelligenceDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.*
import com.example.domain.ai.GeminiManager
import com.example.domain.intelligence.ai.AiAnalystEngine
import com.example.domain.intelligence.dedup.PropertyDeduplicator
import com.example.domain.intelligence.enrichment.PropertyEnrichmentProvider
import com.example.domain.intelligence.enrichment.StandardPublicRecordsEnrichmentProvider
import com.example.domain.intelligence.engine.DeterministicFinancialEngine
import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.DataProvenanceManifest
import com.example.domain.intelligence.model.ProvenanceSourceTier
import com.example.domain.intelligence.scoring.DealScoringEngine
import com.example.domain.intelligence.source.PropertyUrlResolver
import com.example.domain.intelligence.source.SourceRegistry
import com.example.domain.intelligence.source.adapters.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

enum class JobStatus {
    QUEUED,
    FETCHING,
    PARSING,
    NORMALIZING,
    ENRICHING,
    ANALYZING,
    COMPLETED,
    FAILED
}

data class JobProgressState(
    val jobId: String,
    val url: String,
    val source: String,
    val status: JobStatus,
    val progressPercent: Float, // 0.0 to 1.0
    val currentStepDescription: String,
    val propertyId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

class PropertyImportJobManager(
    private val intelligenceDao: IntelligenceDao,
    private val propertyDao: PropertyDao,
    private val geminiManager: GeminiManager
) {
    private val sourceRegistry = SourceRegistry().apply {
        registerAdapter(ZillowUrlSourceAdapter())
        registerAdapter(RedfinUrlSourceAdapter())
        registerAdapter(RealtorUrlSourceAdapter())
        registerAdapter(HomesUrlSourceAdapter())
        registerAdapter(GenericUrlSourceAdapter())
    }

    private val enrichmentProvider: PropertyEnrichmentProvider = StandardPublicRecordsEnrichmentProvider()
    private val deduplicator = PropertyDeduplicator(propertyDao)
    private val aiAnalystEngine = AiAnalystEngine(geminiManager)

    private val _currentJobProgress = MutableStateFlow<JobProgressState?>(null)
    val currentJobProgress = _currentJobProgress.asStateFlow()

    // 24-hour cache TTL
    private val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    suspend fun startImportJob(rawUrl: String, forceRefresh: Boolean = false): JobProgressState = withContext(Dispatchers.IO) {
        val resolved = PropertyUrlResolver.resolve(rawUrl)
        if (!resolved.isValid) {
            val failedState = JobProgressState(
                jobId = "ERR-" + UUID.randomUUID().toString().take(6),
                url = rawUrl,
                source = "Unsupported",
                status = JobStatus.FAILED,
                progressPercent = 0.0f,
                currentStepDescription = "URL Validation Failed",
                errorCode = "INVALID_URL",
                errorMessage = resolved.validationError ?: "Invalid URL format."
            )
            _currentJobProgress.value = failedState
            return@withContext failedState
        }

        // Caching check
        if (!forceRefresh) {
            val existingSourceLink = intelligenceDao.findSourceByUrl(resolved.sanitizedUrl)
            if (existingSourceLink != null && (System.currentTimeMillis() - existingSourceLink.lastSyncedAt < CACHE_TTL_MS)) {
                val cachedState = JobProgressState(
                    jobId = "CACHE-" + existingSourceLink.propertyId,
                    url = resolved.sanitizedUrl,
                    source = existingSourceLink.source,
                    status = JobStatus.COMPLETED,
                    progressPercent = 1.0f,
                    currentStepDescription = "Loaded from secure cache",
                    propertyId = existingSourceLink.propertyId
                )
                _currentJobProgress.value = cachedState
                return@withContext cachedState
            }
        }

        val adapter = sourceRegistry.findAdapter(resolved.sanitizedUrl)
        if (adapter == null) {
            val supported = sourceRegistry.getSupportedSources().joinToString(", ")
            val failedState = JobProgressState(
                jobId = "ERR-" + UUID.randomUUID().toString().take(6),
                url = resolved.sanitizedUrl,
                source = resolved.identifiedSource,
                status = JobStatus.FAILED,
                progressPercent = 0.0f,
                currentStepDescription = "Source Unsupported",
                errorCode = "SOURCE_UNSUPPORTED",
                errorMessage = "This source is not currently supported. Detected domain: ${resolved.domain}. Supported sources: $supported"
            )
            _currentJobProgress.value = failedState
            return@withContext failedState
        }

        val jobId = "JOB-" + UUID.randomUUID().toString().take(8).uppercase()
        var jobEntity = PropertyImportJobEntity(
            jobId = jobId,
            source = adapter.sourceName,
            url = resolved.sanitizedUrl,
            status = JobStatus.QUEUED.name,
            createdAt = System.currentTimeMillis()
        )
        intelligenceDao.insertOrUpdateJob(jobEntity)

        updateProgress(jobId, resolved.sanitizedUrl, adapter.sourceName, JobStatus.QUEUED, 0.10f, "Job queued for ingestion")

        // 1. Fetching
        updateProgress(jobId, resolved.sanitizedUrl, adapter.sourceName, JobStatus.FETCHING, 0.25f, "Fetching property metadata from ${adapter.sourceName}")
        jobEntity = jobEntity.copy(status = JobStatus.FETCHING.name, startedAt = System.currentTimeMillis())
        intelligenceDao.insertOrUpdateJob(jobEntity)

        // 2. Parsing
        updateProgress(jobId, resolved.sanitizedUrl, adapter.sourceName, JobStatus.PARSING, 0.40f, "Parsing canonical listing schema")
        val extraction = adapter.extract(resolved.sanitizedUrl)

        recordSourceHealth(adapter.sourceName, extraction.success, extraction.latencyMs, extraction.schemaChanged, extraction.errorMessage)

        if (!extraction.success || extraction.canonicalProperty == null) {
            val errCode = extraction.errorCode ?: "PARSING_FAILED"
            val errMsg = extraction.errorMessage ?: "Failed to extract canonical property data."
            jobEntity = jobEntity.copy(
                status = JobStatus.FAILED.name,
                completedAt = System.currentTimeMillis(),
                errorCode = errCode,
                errorMessage = errMsg
            )
            intelligenceDao.insertOrUpdateJob(jobEntity)
            val fail = JobProgressState(jobId, resolved.sanitizedUrl, adapter.sourceName, JobStatus.FAILED, 0.4f, "Extraction Failed", null, errCode, errMsg)
            _currentJobProgress.value = fail
            return@withContext fail
        }

        val canonical = extraction.canonicalProperty

        // 3. Normalizing
        updateProgress(jobId, resolved.sanitizedUrl, adapter.sourceName, JobStatus.NORMALIZING, 0.55f, "Normalizing property structure")
        jobEntity = jobEntity.copy(status = JobStatus.NORMALIZING.name)
        intelligenceDao.insertOrUpdateJob(jobEntity)

        // 4. Deduplication
        val matchResult = deduplicator.findExistingMatch(canonical)
        val finalPropertyId = matchResult.existingProperty?.id ?: canonical.propertyId

        // 5. Enriching
        updateProgress(jobId, resolved.sanitizedUrl, adapter.sourceName, JobStatus.ENRICHING, 0.70f, "Enriching with public records, comps & flood data")
        jobEntity = jobEntity.copy(status = JobStatus.ENRICHING.name, propertyId = finalPropertyId)
        intelligenceDao.insertOrUpdateJob(jobEntity)

        val enrichmentResult = try {
            enrichmentProvider.enrich(canonical.copy(propertyId = finalPropertyId))
        } catch (e: Exception) {
            null
        }

        // 6. Financial Calculations & Scoring
        updateProgress(jobId, resolved.sanitizedUrl, adapter.sourceName, JobStatus.ANALYZING, 0.85f, "Computing underwriting metrics & running AI Analyst")
        jobEntity = jobEntity.copy(status = JobStatus.ANALYZING.name)
        intelligenceDao.insertOrUpdateJob(jobEntity)

        val financialMetrics = DeterministicFinancialEngine.calculate(canonical)
        val allProvenance = extraction.provenanceRecords + (enrichmentResult?.provenanceRecords ?: emptyList())

        val provenanceManifest = DataProvenanceManifest(
            propertyId = finalPropertyId,
            records = allProvenance
        )
        val dealScoreBreakdown = DealScoringEngine.calculateScore(canonical, financialMetrics, provenanceManifest)

        // 7. AI Analyst
        val aiResult = aiAnalystEngine.analyzeProperty(canonical, financialMetrics, dealScoreBreakdown)

        // 8. Persist Everything in Transaction
        val propertyEntity = PropertyEntity(
            id = finalPropertyId,
            sourceType = "US_URL_INTELLIGENCE",
            title = canonical.street ?: canonical.address,
            address = canonical.address,
            city = canonical.city,
            state = canonical.state,
            zipCode = canonical.zipCode,
            latitude = canonical.latitude ?: 30.2672,
            longitude = canonical.longitude ?: -97.7431,
            price = canonical.listPrice,
            propertyType = canonical.propertyType,
            bedrooms = canonical.bedrooms ?: 3,
            bathrooms = canonical.bathrooms ?: 2.0,
            squareFeet = canonical.squareFeet ?: 1800,
            yearBuilt = canonical.yearBuilt ?: 2010,
            lotSizeSqFt = canonical.lotSquareFeet ?: 5000,
            description = canonical.description ?: "Imported US Property Intelligence listing.",
            status = canonical.status,
            primaryImageUrl = canonical.photos.firstOrNull() ?: "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=800",
            scannedAt = System.currentTimeMillis(),
            isSaved = true,
            isSavedDeal = dealScoreBreakdown.dealScore >= 75,
            dealScore = dealScoreBreakdown.dealScore
        )

        val images = canonical.photos.mapIndexed { index, url ->
            PropertyImageEntity(
                propertyId = finalPropertyId,
                imageUrl = url,
                caption = "Listing photo ${index + 1}",
                isPrimary = index == 0
            )
        }

        val sourceLink = PropertySourceLinkEntity(
            propertyId = finalPropertyId,
            source = adapter.sourceName,
            sourceUrl = resolved.sanitizedUrl,
            listingId = canonical.listingId,
            isPrimary = true,
            lastSyncedAt = System.currentTimeMillis()
        )

        val provenanceEntities = allProvenance.map {
            PropertyProvenanceEntity(
                propertyId = finalPropertyId,
                field = it.field,
                value = it.value,
                source = it.source,
                tier = it.tier.name,
                retrievedAt = it.retrievedAt,
                confidence = it.confidence
            )
        }

        val enrichmentEntity = enrichmentResult?.enrichmentData ?: PropertyEnrichmentEntity(propertyId = finalPropertyId)
        val compsEntities = enrichmentResult?.comps?.map { it.copy(targetPropertyId = finalPropertyId) } ?: emptyList()

        val financialEntity = PropertyFinancialEntity(
            propertyId = finalPropertyId,
            strategy = financialMetrics.strategy.name,
            financingType = financialMetrics.financingType.name,
            purchasePrice = financialMetrics.purchasePrice,
            downPayment = financialMetrics.downPayment,
            loanAmount = financialMetrics.loanAmount,
            interestRate = financialMetrics.interestRate,
            monthlyRent = financialMetrics.grossMonthlyRent,
            operatingExpensesMonthly = financialMetrics.operatingExpensesMonthly,
            netOperatingIncomeAnnual = financialMetrics.netOperatingIncomeAnnual,
            monthlyDebtService = financialMetrics.monthlyDebtService,
            monthlyCashFlow = financialMetrics.monthlyCashFlow,
            annualCashFlow = financialMetrics.annualCashFlow,
            capRate = financialMetrics.capRate,
            cashOnCashReturn = financialMetrics.cashOnCashReturn,
            dscr = financialMetrics.dscr,
            ltv = financialMetrics.ltv,
            totalCashRequired = financialMetrics.totalCashRequired,
            dealScore = dealScoreBreakdown.dealScore
        )

        val aiAnalysisEntity = PropertyAiAnalysisEntity(
            propertyId = finalPropertyId,
            summary = aiResult.summary,
            investmentThesis = aiResult.investmentThesis,
            strengthsJson = JSONArray(aiResult.strengths).toString(),
            weaknessesJson = JSONArray(aiResult.weaknesses).toString(),
            risksJson = JSONArray(aiResult.risks).toString(),
            redFlagsJson = JSONArray(aiResult.redFlags).toString(),
            recommendedStrategy = aiResult.recommendedStrategy,
            recommendedOfferRange = aiResult.recommendedOfferRange,
            questionsForSellerJson = JSONArray(aiResult.questionsForSeller).toString(),
            dueDiligenceJson = JSONArray(aiResult.dueDiligenceItems).toString(),
            confidence = aiResult.confidence,
            evidenceJson = JSONArray(aiResult.evidence.map { "${it.classification.name}: [${it.category}] ${it.statement}" }).toString()
        )

        propertyDao.insertProperty(propertyEntity)
        intelligenceDao.persistCompletePropertyIngestion(
            property = propertyEntity,
            images = images,
            sourceLink = sourceLink,
            provenanceRecords = provenanceEntities,
            enrichment = enrichmentEntity,
            comps = compsEntities,
            financials = financialEntity,
            aiAnalysis = aiAnalysisEntity
        )

        // 9. Mark Job Completed
        jobEntity = jobEntity.copy(
            status = JobStatus.COMPLETED.name,
            completedAt = System.currentTimeMillis()
        )
        intelligenceDao.insertOrUpdateJob(jobEntity)

        val completedState = JobProgressState(
            jobId = jobId,
            url = resolved.sanitizedUrl,
            source = adapter.sourceName,
            status = JobStatus.COMPLETED,
            progressPercent = 1.0f,
            currentStepDescription = "Property Deal Room ready",
            propertyId = finalPropertyId
        )
        _currentJobProgress.value = completedState
        completedState
    }

    private fun updateProgress(
        jobId: String,
        url: String,
        source: String,
        status: JobStatus,
        pct: Float,
        description: String,
        propId: String? = null
    ) {
        _currentJobProgress.value = JobProgressState(
            jobId = jobId,
            url = url,
            source = source,
            status = status,
            progressPercent = pct,
            currentStepDescription = description,
            propertyId = propId
        )
    }

    private suspend fun recordSourceHealth(
        source: String,
        success: Boolean,
        latencyMs: Long,
        schemaChanged: Boolean,
        lastError: String?
    ) {
        val existing = intelligenceDao.getSourceHealth(source)
        val successCount = (existing?.successCount ?: 0) + if (success) 1 else 0
        val failureCount = (existing?.failureCount ?: 0) + if (!success) 1 else 0
        val total = successCount + failureCount
        val successRate = if (total > 0) (successCount.toDouble() / total) * 100.0 else 100.0
        val failureRate = if (total > 0) (failureCount.toDouble() / total) * 100.0 else 0.0

        val healthStatus = when {
            schemaChanged -> "SCHEMA_DRIFT"
            failureRate > 40.0 -> "DEGRADED"
            failureRate > 80.0 -> "BLOCKED"
            else -> "HEALTHY"
        }

        val updated = SourceHealthEntity(
            source = source,
            successCount = successCount,
            failureCount = failureCount,
            successRate = successRate,
            failureRate = failureRate,
            averageLatencyMs = latencyMs,
            lastSuccessAt = if (success) System.currentTimeMillis() else existing?.lastSuccessAt,
            lastFailureAt = if (!success) System.currentTimeMillis() else existing?.lastFailureAt,
            parserVersion = existing?.parserVersion ?: "1.0.0",
            healthStatus = healthStatus,
            lastErrorReason = if (schemaChanged) "SOURCE_SCHEMA_CHANGED: $lastError" else lastError ?: existing?.lastErrorReason
        )
        intelligenceDao.insertOrUpdateSourceHealth(updated)
    }

    fun cancelJob(jobId: String) {
        _currentJobProgress.value = JobProgressState(
            jobId = jobId,
            url = "",
            source = "",
            status = JobStatus.FAILED,
            progressPercent = 0.0f,
            currentStepDescription = "Job cancelled by user",
            errorCode = "USER_CANCELLED",
            errorMessage = "The ingestion job was cancelled."
        )
    }

    fun clearProgress() {
        _currentJobProgress.value = null
    }
}
