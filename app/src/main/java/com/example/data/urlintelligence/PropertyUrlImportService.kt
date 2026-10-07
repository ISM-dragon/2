package com.example.data.urlintelligence

import com.example.data.local.dao.PropertyDao
import com.example.urlintelligence.adapter.GenericWebAdapter
import com.example.urlintelligence.adapter.HomesAdapter
import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.RealtorAdapter
import com.example.urlintelligence.adapter.RedfinAdapter
import com.example.urlintelligence.adapter.ZillowAdapter
import com.example.urlintelligence.idempotency.IdempotencyStore
import com.example.urlintelligence.idempotency.InMemoryIdempotencyStore
import com.example.urlintelligence.job.InMemoryPropertyImportJobStore
import com.example.urlintelligence.job.PropertyImportJobStore
import com.example.urlintelligence.model.CanonicalProperty
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.resolver.PropertyImportResult
import com.example.urlintelligence.resolver.PropertyUrlResolver
import com.example.urlintelligence.resolver.ResolveOptions
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.retry.RetryPolicy
import com.example.urlintelligence.retry.Sleeper
import com.example.urlintelligence.source.KnownSources
import com.example.urlintelligence.source.SourceDetector
import com.example.urlintelligence.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Composition root for the property URL intelligence layer inside the Android app.
 *
 * Everything here is wiring only — no parsing logic, no secrets (the sources are
 * public pages fetched anonymously), no UI access.
 */
object UrlIntelligenceModule {

    /**
     * Sources the app is willing to contact. Every provider marked
     * `requiresOptIn` in [KnownSources] must appear here, otherwise the resolver
     * refuses the request with a policy failure. Keep this list in sync with the
     * app's terms/robots review.
     */
    val OPT_IN_SOURCES: Set<String> = setOf("zillow", "redfin", "realtor", "homes")

    fun createRegistry(transport: PropertyHttpTransport, clock: Clock = Clock.SYSTEM): SourceRegistry =
        SourceRegistry(
            descriptors = KnownSources.all(),
            adapters = listOf(
                ZillowAdapter(transport, clock),
                RedfinAdapter(transport, clock),
                RealtorAdapter(transport, clock),
                HomesAdapter(transport, clock),
                GenericWebAdapter(transport, clock)
            )
        )

    fun createResolver(
        transport: PropertyHttpTransport,
        allowedSourceIds: Set<String> = OPT_IN_SOURCES,
        clock: Clock = Clock.SYSTEM,
        sleeper: Sleeper = Sleeper.DEFAULT,
        retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 3),
        jobStore: PropertyImportJobStore = InMemoryPropertyImportJobStore(),
        resultStore: IdempotencyStore<PropertyImportResult> = InMemoryIdempotencyStore(clock)
    ): PropertyUrlResolver {
        val registry = createRegistry(transport, clock)
        return PropertyUrlResolver(
            registry = registry,
            detector = SourceDetector(registry.descriptors()),
            normalizer = PropertyNormalizer(clock),
            retryPolicy = retryPolicy,
            clock = clock,
            sleeper = sleeper,
            jobStore = jobStore,
            resultStore = resultStore,
            allowedSourceIds = allowedSourceIds
        )
    }

    fun createDefaultResolver(): PropertyUrlResolver =
        createResolver(transport = OkHttpPropertyTransport())
}

/**
 * Application-facing import service: resolves a URL and (optionally) persists the
 * canonical property through the existing Room DAO.
 *
 * Partial results are persisted too, but flagged, so the user never silently gets
 * a half-populated property without knowing what is missing.
 */
class PropertyUrlImportService(
    private val resolver: PropertyUrlResolver,
    private val propertyDao: PropertyDao? = null
) {

    sealed class Outcome {
        data class Success(val property: CanonicalProperty, val persisted: Boolean) : Outcome()
        data class Partial(
            val property: CanonicalProperty,
            val missingFields: Set<PropertyField>,
            val warnings: List<String>,
            val persisted: Boolean
        ) : Outcome()

        data class Failure(val code: String, val detail: String, val retryable: Boolean) : Outcome()

        val isUsable: Boolean
            get() = this is Success || this is Partial
    }

    suspend fun import(
        rawUrl: String,
        options: ResolveOptions = ResolveOptions()
    ): Outcome = withContext(Dispatchers.IO) {
        when (val result = resolver.resolve(rawUrl, options)) {
            is PropertyImportResult.Success -> Outcome.Success(
                property = result.property,
                persisted = persist(result.property)
            )
            is PropertyImportResult.Partial -> Outcome.Partial(
                property = result.property,
                missingFields = result.missingFields,
                warnings = result.warnings,
                persisted = persist(result.property)
            )
            is PropertyImportResult.Failure -> Outcome.Failure(
                code = result.failure.code,
                detail = result.failure.detail,
                retryable = result.failure.retryable
            )
        }
    }

    private suspend fun persist(property: CanonicalProperty): Boolean {
        val dao = propertyDao ?: return false
        val bundle = CanonicalPropertyMapper.toBundle(property)
        dao.insertProperty(bundle.property)
        if (bundle.images.isNotEmpty()) dao.insertImages(bundle.images)
        dao.insertMarketData(bundle.marketData)
        dao.insertRentEstimate(bundle.rentEstimate)
        dao.insertTaxRecord(bundle.taxRecord)
        if (bundle.salesHistory.isNotEmpty()) dao.insertSalesHistory(bundle.salesHistory)
        if (bundle.comps.isNotEmpty()) dao.insertComps(bundle.comps)
        return true
    }
}
