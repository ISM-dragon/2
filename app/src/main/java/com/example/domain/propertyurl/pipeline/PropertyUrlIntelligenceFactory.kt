package com.example.domain.propertyurl.pipeline

import com.example.domain.propertyurl.adapter.GenericWebListingAdapter
import com.example.domain.propertyurl.adapter.HomesComAdapter
import com.example.domain.propertyurl.adapter.PropertyAdapterRegistry
import com.example.domain.propertyurl.adapter.RealtorComAdapter
import com.example.domain.propertyurl.adapter.RedfinAdapter
import com.example.domain.propertyurl.adapter.ZillowAdapter
import com.example.domain.propertyurl.job.IdempotencyPolicy
import com.example.domain.propertyurl.job.PropertyImportJobStore
import com.example.domain.propertyurl.job.RetryPolicy
import com.example.domain.propertyurl.normalize.CanonicalPropertyMapper
import com.example.domain.propertyurl.parse.EmbeddedJsonStateParser
import com.example.domain.propertyurl.parse.MetaAndTitleFactsParser
import com.example.domain.propertyurl.parse.ParserChain
import com.example.domain.propertyurl.parse.SchemaOrgJsonLdParser
import com.example.domain.propertyurl.parse.VisibleTextFactsParser
import com.example.domain.propertyurl.port.Clock
import com.example.domain.propertyurl.port.CoroutineSleeper
import com.example.domain.propertyurl.port.CredentialProvider
import com.example.domain.propertyurl.port.DefaultFetchPolicies
import com.example.domain.propertyurl.port.FetchOptions
import com.example.domain.propertyurl.port.HttpFetcher
import com.example.domain.propertyurl.port.HttpUrlConnectionFetcher
import com.example.domain.propertyurl.port.IdGenerator
import com.example.domain.propertyurl.port.Sleeper
import com.example.domain.propertyurl.port.SystemClock
import com.example.domain.propertyurl.port.TelemetrySink
import com.example.domain.propertyurl.port.UuidIdGenerator
import com.example.domain.propertyurl.source.SourceCatalog
import com.example.domain.propertyurl.source.SourceRegistry
import com.example.domain.propertyurl.url.PropertyUrlResolver
import com.example.domain.propertyurl.url.PropertyUrlValidator

/**
 * Composition root of the layer.
 *
 * Everything is injected so that production, tests and (later) a server-side worker use the exact
 * same code paths. The only *policy* decision the app has to make is the [FetchPolicy]: the default
 * here is capability-only (`DefaultFetchPolicies.unrestricted()`); pass
 * `DefaultFetchPolicies.robotsAware(fetcher, clock, userAgent)` to honour `/robots.txt`.
 *
 * Nothing in this file reads a secret: credentials arrive through [CredentialProvider], which the app
 * backs with its existing secure configuration store (never with values compiled into the APK).
 */
object PropertyUrlIntelligenceFactory {

    /** Full catalogue: available sources plus the planned ones (used for "not supported yet" copy). */
    fun defaultRegistry(): SourceRegistry = SourceRegistry.build(SourceCatalog.ALL)

    /** Every shipped adapter; adding a portal means adding one entry here. */
    fun defaultAdapters(): PropertyAdapterRegistry = PropertyAdapterRegistry.of(
        ZillowAdapter(),
        RedfinAdapter(),
        RealtorComAdapter(),
        HomesComAdapter(),
        GenericWebListingAdapter()
    )

    /** Shared, source-agnostic parsers. Adapter-local parsers are added by each adapter. */
    fun defaultParsers(): List<com.example.domain.propertyurl.parse.SourceDocumentParser> = listOf(
        SchemaOrgJsonLdParser(),
        EmbeddedJsonStateParser(),
        MetaAndTitleFactsParser(),
        VisibleTextFactsParser()
    )

    fun defaultParserChain(registry: SourceRegistry = defaultRegistry()): ParserChain = ParserChain(defaultParsers())

    /**
     * Builds a ready-to-use engine.
     *
     * @param jobStore persistence for jobs; share one instance with the UI (it is the source of truth).
     * @param credentialProvider supplies per-source credentials at runtime; defaults to none.
     * @param fetchPolicy compliance gate; defaults to capability checks only.
     * @param useRobotsTxt when true the policy chain also honours robots.txt for the target origin.
     * @param sleeper retry backoff sleeper (inject a no-op in tests).
     */
    fun create(
        jobStore: PropertyImportJobStore,
        httpFetcher: HttpFetcher = HttpUrlConnectionFetcher(),
        clock: Clock = SystemClock(),
        idGenerator: IdGenerator = UuidIdGenerator(),
        telemetry: TelemetrySink = com.example.domain.propertyurl.port.NoopTelemetry,
        credentialProvider: CredentialProvider = CredentialProvider.NONE,
        /** Backoff sleep; tests inject a recording/no-op sleeper so retries do not really wait. */
        sleeper: Sleeper = CoroutineSleeper(),
        registry: SourceRegistry = defaultRegistry(),
        adapters: PropertyAdapterRegistry = defaultAdapters(),
        options: PropertyImportOptions = PropertyImportOptions(),
        retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
        idempotencyPolicy: IdempotencyPolicy = IdempotencyPolicy(),
        fetchPolicy: com.example.domain.propertyurl.port.FetchPolicy? = null,
        useRobotsTxt: Boolean = false,
        rateLimiter: SourceRateLimiter? = SourceRateLimiter(clock),
        healthTracker: SourceHealthTracker? = SourceHealthTracker(clock)
    ): PropertyUrlIntelligence {
        val parserChain = defaultParserChain(registry)
        val effectivePolicy = fetchPolicy ?: if (useRobotsTxt) {
            DefaultFetchPolicies.robotsAware(httpFetcher, clock, options.fetchOptions.userAgent)
        } else {
            DefaultFetchPolicies.unrestricted()
        }

        return PropertyUrlIntelligence(
            registry = registry,
            adapters = adapters,
            resolver = PropertyUrlResolver(registry, PropertyUrlValidator()),
            mapper = CanonicalPropertyMapper(registry, now = { clock.nowEpochMillis() }),
            jobStore = jobStore,
            parserChain = parserChain,
            failureClassifier = com.example.domain.propertyurl.model.SourceFailureClassifier {
                clock.nowEpochMillis()
            },
            environment = PropertyIntelEnvironment(
                clock = clock,
                idGenerator = idGenerator,
                httpFetcher = httpFetcher,
                sleeper = sleeper,
                telemetry = telemetry,
                credentialProvider = credentialProvider,
                fetchPolicy = effectivePolicy,
                retryPolicy = retryPolicy,
                idempotencyPolicy = idempotencyPolicy,
                options = options,
                rateLimiter = rateLimiter,
                healthTracker = healthTracker
            )
        )
    }

    /** Convenience wrapper for the common Android wiring: engine + background queue. */
    fun createQueue(
        intelligence: PropertyUrlIntelligence,
        jobStore: PropertyImportJobStore,
        clock: Clock,
        telemetry: TelemetrySink = com.example.domain.propertyurl.port.NoopTelemetry,
        scope: kotlinx.coroutines.CoroutineScope,
        pollIntervalMillis: Long = 15_000,
        maxParallel: Int = 2
    ): PropertyImportQueue = PropertyImportQueue(
        intelligence = intelligence,
        jobStore = jobStore,
        clock = clock,
        telemetry = telemetry,
        scope = scope,
        pollIntervalMillis = pollIntervalMillis,
        maxParallel = maxParallel
    )

    /** Options with the layer's recommended mobile defaults (4 MB bodies, 3 attempts, 2 workers). */
    fun mobileOptions(
        userAgent: String = FetchOptions.DEFAULT_USER_AGENT
    ): PropertyImportOptions = PropertyImportOptions(
        fetchOptions = FetchOptions(userAgent = userAgent),
        maxConcurrentImports = 2,
        maxFetchAttempts = 3,
        maxInlineRateLimitWaitMillis = 5_000,
        maxTotalImportMillis = 90_000
    )
}
