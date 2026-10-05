package com.example.domain.propertyurl.source

/**
 * Declarative catalogue of property sources.
 *
 * Everything the layer knows about a website lives here — no site specific logic leaks into the
 * resolver, detector or pipeline. To support a new portal later:
 *
 *  1. add a [PropertySourceDefinition] here (domains, path rules, id parameters),
 *  2. add its adapter in `com.example.domain.propertyurl.adapter`,
 *  3. register both in `PropertyUrlIntelligenceFactory`.
 *
 * Detection starts working with step 1 alone (until the adapter lands the URL resolves to
 * `SourceStatus.PLANNED` and produces an actionable "not implemented yet" failure).
 *
 * Compliance note: automated retrieval is a contractual decision, not a technical one. The layer
 * exposes [SourceCapabilities.allowsAutomatedFetching] and a `FetchPolicy` port so deployments can
 * disable a source (or require robots.txt compliance) without touching adapter code. Always prefer
 * a sanctioned MLS/IDX or partner API where one exists.
 */
object SourceCatalog {

    // --- Consumer portals ---------------------------------------------------------------

    val ZILLOW = PropertySourceDefinition(
        sourceId = "zillow",
        displayName = "Zillow",
        kind = PropertySourceKind.LISTING_PORTAL,
        marketType = MarketType.ON_MARKET,
        domains = listOf("zillow.com"),
        aliases = listOf("zillow", "zillowstatic"),
        pathRules = listOf(
            SourcePathRule(
                name = "homedetails",
                regex = Regex("^/homedetails/(?:.*/)?(\\d+)_zpid", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.96
            ),
            SourcePathRule(
                name = "building",
                regex = Regex("^/b/(\\d+)_zpid", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.9
            ),
            SourcePathRule(
                name = "community-detail",
                regex = Regex("^/(?:apartments|community)/[^?]*?/(\\d+)_zpid", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.85
            )
        ),
        queryIdNames = listOf("zpid"),
        capabilities = SourceCapabilities(
            supportsStructuredData = true,
            supportsImages = true,
            supportsRentEstimate = true,
            supportsTaxRecord = true,
            requestsPerMinute = 8
        ),
        trustRank = 9,
        notes = "Deep listing links only; automated retrieval requires a Zillow partner agreement in most deployments."
    )

    val REDFIN = PropertySourceDefinition(
        sourceId = "redfin",
        displayName = "Redfin",
        kind = PropertySourceKind.LISTING_PORTAL,
        marketType = MarketType.ON_MARKET,
        domains = listOf("redfin.com"),
        aliases = listOf("redfin"),
        pathRules = listOf(
            SourcePathRule(
                name = "address-slug",
                regex = Regex("^/[A-Za-z]{2}/[^/]+/[^/]+/(\\d{6,})/?$", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.93
            ),
            SourcePathRule(
                name = "home-id",
                regex = Regex("/home/(\\d{6,})/?$", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.9
            )
        ),
        queryIdNames = listOf("listingId"),
        capabilities = SourceCapabilities(supportsRentEstimate = false, supportsTaxRecord = true, requestsPerMinute = 8),
        trustRank = 8,
        notes = "Redfin publishes extensive structured data; keep parsers pinned to the JSON-LD/embedded state contract."
    )

    val REALTOR_COM = PropertySourceDefinition(
        sourceId = "realtor_com",
        displayName = "Realtor.com",
        kind = PropertySourceKind.LISTING_PORTAL,
        marketType = MarketType.ON_MARKET,
        domains = listOf("realtor.com"),
        aliases = listOf("realtor"),
        pathRules = listOf(
            SourcePathRule(
                name = "detail",
                regex = Regex("(M\\d{5,}-\\d{5,})", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.92
            ),
            SourcePathRule(
                name = "detail-legacy",
                regex = Regex("^/realestateandhomes-detail/[^/]*?_(\\d{6,})", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.8
            )
        ),
        capabilities = SourceCapabilities(supportsTaxRecord = true, requestsPerMinute = 8),
        trustRank = 8,
        notes = "Realtor.com is a licensed MLS aggregator; use the OFFICIAL RESO Web API feed when available."
    )

    val HOMES_COM = PropertySourceDefinition(
        sourceId = "homes_com",
        displayName = "Homes.com",
        kind = PropertySourceKind.LISTING_PORTAL,
        marketType = MarketType.ON_MARKET,
        domains = listOf("homes.com"),
        aliases = listOf("homes.com"),
        pathRules = listOf(
            SourcePathRule(
                name = "property-slug",
                regex = Regex("^/property/[^/]+/([A-Za-z0-9]{6,})/?$", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.9
            ),
            SourcePathRule(
                name = "property-typed-id",
                regex = Regex("^/property/[^/]+/(?:pid-)?(\\d{6,})/?$", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.88
            )
        ),
        queryIdNames = listOf("listingId", "pid"),
        capabilities = SourceCapabilities(supportsTaxRecord = true, requestsPerMinute = 8),
        trustRank = 7
    )

    // --- Credentialed / partner feeds (never URL-detected) --------------------------------

    val MLS_FEED = PropertySourceDefinition(
        sourceId = "mls_feed",
        displayName = "MLS/IDX Feed",
        kind = PropertySourceKind.MLS_FEED,
        marketType = MarketType.ON_MARKET,
        domains = emptyList(),
        capabilities = SourceCapabilities(
            supportsStructuredData = true,
            supportsRentEstimate = true,
            supportsTaxRecord = true,
            requiresCredentials = true,
            requiresApiKey = true,
            requestsPerMinute = 60
        ),
        status = SourceStatus.AVAILABLE,
        trustRank = 10,
        notes = "Canonical source of truth. Credentials are injected at runtime from the server side and never stored in the APK."
    )

    val OFF_MARKET_WHOLESALE = PropertySourceDefinition(
        sourceId = "off_market_wholesale",
        displayName = "Off-Market Wholesale",
        kind = PropertySourceKind.WHOLESALE_MARKETPLACE,
        marketType = MarketType.WHOLESALE,
        domains = emptyList(),
        capabilities = SourceCapabilities(supportsTaxRecord = false, requestsPerMinute = 10),
        trustRank = 6,
        notes = "Curated/wholesale inventory (deal lists, county lists). Fetched through the injected adapter."
    )

    val COUNTY_RECORDS = PropertySourceDefinition(
        sourceId = "county_records",
        displayName = "County Records",
        kind = PropertySourceKind.PUBLIC_RECORD,
        marketType = MarketType.PUBLIC_RECORD,
        domains = emptyList(),
        capabilities = SourceCapabilities(
            supportsStructuredData = false,
            supportsImages = false,
            supportsTaxRecord = true,
            requiresCredentials = true,
            requestsPerMinute = 6
        ),
        trustRank = 7,
        notes = "Appraisal district / recorder data for tax, lien and ownership enrichment."
    )

    // --- Generic fallback ----------------------------------------------------------------

    val GENERIC_WEB = PropertySourceDefinition(
        sourceId = "generic_web",
        displayName = "Generic Web Listing",
        kind = PropertySourceKind.GENERIC_WEB,
        marketType = MarketType.UNKNOWN,
        domains = emptyList(),
        capabilities = SourceCapabilities(supportsStructuredData = true, requestsPerMinute = 6),
        trustRank = 2,
        notes = "Fallback for brokerage/unknown sites. Only structured data, meta tags or title heuristics are trusted."
    )

    // --- Recognition without an adapter yet (add these later) -----------------------------

    val APARTMENTS_COM = PropertySourceDefinition(
        sourceId = "apartments_com",
        displayName = "Apartments.com",
        kind = PropertySourceKind.LISTING_PORTAL,
        marketType = MarketType.ON_MARKET,
        domains = listOf("apartments.com"),
        pathRules = listOf(
            SourcePathRule(
                name = "property",
                regex = Regex("^/[^/]+/([a-z0-9]{6,})/?$", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.7
            )
        ),
        status = SourceStatus.PLANNED,
        trustRank = 6
    )

    val TRULIA = PropertySourceDefinition(
        sourceId = "trulia",
        displayName = "Trulia",
        kind = PropertySourceKind.LISTING_PORTAL,
        marketType = MarketType.ON_MARKET,
        domains = listOf("trulia.com"),
        pathRules = listOf(
            SourcePathRule(
                name = "property",
                regex = Regex("^/p/([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE),
                externalIdGroup = 1,
                confidence = 0.8
            )
        ),
        status = SourceStatus.PLANNED,
        trustRank = 7
    )

    val LOOPNET = PropertySourceDefinition(
        sourceId = "loopnet",
        displayName = "LoopNet (commercial)",
        kind = PropertySourceKind.LISTING_PORTAL,
        marketType = MarketType.ON_MARKET,
        domains = listOf("loopnet.com"),
        pathRules = listOf(
            SourcePathRule(
                name = "listing",
                regex = Regex("/(\\d{6,})/?$"),
                externalIdGroup = 1,
                confidence = 0.75
            )
        ),
        status = SourceStatus.PLANNED,
        trustRank = 6,
        notes = "Commercial listings need a different canonical model (cap rate, NOI); adapter planned."
    )

    val ALL: List<PropertySourceDefinition> = listOf(
        MLS_FEED,
        ZILLOW,
        REDFIN,
        REALTOR_COM,
        HOMES_COM,
        COUNTY_RECORDS,
        OFF_MARKET_WHOLESALE,
        GENERIC_WEB,
        APARTMENTS_COM,
        TRULIA,
        LOOPNET
    )

    /** Registry with every known source; planned sources resolve but fail with a clear message. */
    fun default(): SourceRegistry = SourceRegistry.build(ALL)

    /** Registry containing only sources whose adapters are actually wired. */
    fun availableOnly(): SourceRegistry = SourceRegistry.build(ALL.filter { it.status == SourceStatus.AVAILABLE })
}
