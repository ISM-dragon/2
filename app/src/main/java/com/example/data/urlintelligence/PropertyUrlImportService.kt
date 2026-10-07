package com.example.data.urlintelligence

import com.example.data.local.dao.PropertyDao
import com.example.urlintelligence.adapter.GenericWebAdapter
import com.example.urlintelligence.adapter.HomesAdapter
import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.RealtorAdapter
import com.example.urlintelligence.adapter.RedfinAdapter
import com.example.urlintelligence.adapter.ZillowAdapter
import com.example.urlintelligence.compliance.AccessPolicy
import com.example.urlintelligence.compliance.RobotsPolicy
import com.example.urlintelligence.fetch.FetchLimits
import com.example.urlintelligence.idempotency.IdempotencyStore
import com.example.urlintelligence.idempotency.ImportLedger
import com.example.urlintelligence.idempotency.InMemoryIdempotencyStore
import com.example.urlintelligence.idempotency.InMemoryImportLedger
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

    /**
     * User agent used for robots.txt evaluation and for the fetches themselves. It names the
     * app and states the intent, so an operator can identify and contact us.
     */
    const val USER_AGENT: String =
        OkHttpPropertyTransport.DEFAULT_USER_AGENT

    /** Budget every fetch in the app is held to (timeouts, size, redirects). */
    val FETCH_LIMITS: FetchLimits = FetchLimits.MOBILE

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
        resultStore: IdempotencyStore<PropertyImportResult> = InMemoryIdempotencyStore(clock),
        /**
         * Compliance gate. Null means "not configured": imports then run with a warning
         * unless the caller asks for [ResolveOptions.requireAccessPolicy]. Production wiring
         * should pass [robotsAwarePolicy] (or use [createDefaultResolver], which does).
         */
        accessPolicy: AccessPolicy? = null,
        /** Property-level idempotency: the same house via another URL is not imported twice. */
        ledger: ImportLedger<PropertyImportResult> = InMemoryImportLedger(clock)
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
            allowedSourceIds = allowedSourceIds,
            accessPolicy = accessPolicy,
            ledger = ledger
        )
    }

    /**
     * Production resolver: the Android transport plus a robots.txt gate evaluated through the
     * same credential-free transport, so a listing is only fetched when the provider's own
     * robots rules allow it and an unevaluable robots.txt denies the import.
     */
    fun createDefaultResolver(
        transport: PropertyHttpTransport = OkHttpPropertyTransport(),
        clock: Clock = Clock.SYSTEM
    ): PropertyUrlResolver = createResolver(
        transport = transport,
        clock = clock,
        accessPolicy = robotsAwarePolicy(transport, clock)
    )

    /** robots.txt policy bound to the app's user agent and fetch budget. */
    fun robotsAwarePolicy(
        transport: PropertyHttpTransport,
        clock: Clock = Clock.SYSTEM
    ): AccessPolicy = RobotsPolicy(transport, USER_AGENT, clock)
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
    private val propertyDao: PropertyDao? = null,
    /**
     * When true, an import fails if the compliance gate could not be evaluated instead of
     * importing with an unverified access decision. Wiring this on requires a resolver that
     * actually has a policy ([UrlIntelligenceModule.robotsAwarePolicy]); the constructor
     * refuses the combination otherwise.
     */
    private val requirePolicyCheck: Boolean = false,
    private val fetchLimits: FetchLimits = UrlIntelligenceModule.FETCH_LIMITS
) {

    init {
        require(!requirePolicyCheck || resolver.hasAccessPolicy) {
            "requirePolicyCheck needs a resolver wired with an access policy " +
                "(see UrlIntelligenceModule.robotsAwarePolicy)"
        }
    }

    sealed class Outcome {
        data class Success(
            val property: CanonicalProperty,
            val persisted: Boolean,
            val idempotency: com.example.urlintelligence.idempotency.ImportIdempotency =
                com.example.urlintelligence.idempotency.ImportIdempotency.FRESH
        ) : Outcome()

        data class Partial(
            val property: CanonicalProperty,
            val missingFields: Set<PropertyField>,
            val warnings: List<String>,
            val persisted: Boolean,
            val idempotency: com.example.urlintelligence.idempotency.ImportIdempotency =
                com.example.urlintelligence.idempotency.ImportIdempotency.FRESH
        ) : Outcome()

        data class Failure(val code: String, val detail: String, val retryable: Boolean) : Outcome()

        val isUsable: Boolean
            get() = this is Success || this is Partial
    }

    suspend fun import(
        rawUrl: String,
        options: ResolveOptions = ResolveOptions(limits = fetchLimits)
    ): Outcome = withContext(Dispatchers.IO) {
        // Callers may raise the bar (never lower it): the service-level policy check is
        // OR-ed into whatever the caller asked for. Fetch limits are passed through as-is;
        // the default argument supplies `FETCH_LIMITS` when the caller does not choose.
        val effective = options.copy(
            requireAccessPolicy = options.requireAccessPolicy || requirePolicyCheck
        )
        when (val result = resolver.resolve(rawUrl, effective)) {
            is PropertyImportResult.Success -> Outcome.Success(
                property = result.property,
                persisted = persist(result.property),
                idempotency = result.idempotency
            )
            is PropertyImportResult.Partial -> Outcome.Partial(
                property = result.property,
                missingFields = result.missingFields,
                warnings = result.warnings,
                persisted = persist(result.property),
                idempotency = result.idempotency
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
