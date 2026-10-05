package com.example.domain.propertyurl.source

/** Category of the data provider behind a URL. */
enum class PropertySourceKind {
    /** Consumer listing portal (Zillow, Redfin, Realtor.com, Homes.com…). */
    LISTING_PORTAL,
    /** Brokerage / agent website. */
    BROKERAGE_SITE,
    /** Licensed MLS/IDX feed (API, usually credentialed). */
    MLS_FEED,
    /** Off-market / wholesale marketplace. */
    WHOLESALE_MARKETPLACE,
    /** County recorder / appraisal district / tax authority. */
    PUBLIC_RECORD,
    /** Anything else that can still be parsed by the generic pipeline. */
    GENERIC_WEB
}

/** Market classification used by the rest of the app (mirrors `PropertyEntity.sourceType`). */
enum class MarketType(val entitySourceType: String) {
    ON_MARKET("ON_MARKET"),
    OFF_MARKET("OFF_MARKET"),
    WHOLESALE("WHOLESALE"),
    FORECLOSURE("FORECLOSURE"),
    PUBLIC_RECORD("PUBLIC_RECORD"),
    UNKNOWN("UNKNOWN")
}

/**
 * Implementation status of a source.
 *  - [AVAILABLE]: detection + adapter implemented.
 *  - [PLANNED]: detection implemented so users get an actionable message, adapter not wired yet.
 *  - [DISABLED]: temporarily switched off (compliance, contract, incident).
 */
enum class SourceStatus { AVAILABLE, PLANNED, DISABLED }

/** What an adapter for this source can do. Drives request building and user-facing limits. */
data class SourceCapabilities(
    val supportsStructuredData: Boolean = true,
    val supportsImages: Boolean = true,
    val supportsRentEstimate: Boolean = false,
    val supportsTaxRecord: Boolean = false,
    val requiresCredentials: Boolean = false,
    val requiresApiKey: Boolean = false,
    /** False for sources whose terms forbid automated retrieval; the pipeline then refuses to fetch. */
    val allowsAutomatedFetching: Boolean = true,
    /** Advisory throttle applied by the source rate limiter. */
    val requestsPerMinute: Int = 12
)

/**
 * Declarative rule describing how a listing URL for a source looks.
 *
 * @param regex matched against the URL path (or `path?query` when [applyToQuery]).
 * @param externalIdGroup 1-based capture group holding the listing id, or null when the id is
 *  carried by a query parameter (see [PropertySourceDefinition.queryIdNames]).
 */
data class SourcePathRule(
    val name: String,
    val regex: Regex,
    val externalIdGroup: Int? = null,
    val confidence: Double = 0.9,
    val applyToQuery: Boolean = false
) {
    init {
        require(confidence in 0.0..1.0) { "Rule confidence must be within 0.0..1.0 but was $confidence" }
    }

    fun matches(value: String): MatchResult? = regex.find(value)

    fun extractId(value: String): String? {
        val group = externalIdGroup ?: return null
        val match = regex.find(value) ?: return null
        return match.groupValues.getOrNull(group)?.takeIf { it.isNotBlank() }
    }
}

/**
 * A recognised property data source.
 *
 * Definitions are data, not code: adding Zillow/Redfin/Realtor/Homes later means adding a definition
 * plus an adapter — resolver, detector, registry and pipeline stay untouched.
 */
data class PropertySourceDefinition(
    val sourceId: String,
    val displayName: String,
    val kind: PropertySourceKind,
    val marketType: MarketType,
    /** Registrable domains, e.g. `zillow.com`. Sub-domains are matched automatically. */
    val domains: List<String>,
    val aliases: List<String> = emptyList(),
    val pathRules: List<SourcePathRule> = emptyList(),
    /** Query parameters that carry the listing id, e.g. `zpid` on Zillow. */
    val queryIdNames: List<String> = emptyList(),
    val capabilities: SourceCapabilities = SourceCapabilities(),
    val status: SourceStatus = SourceStatus.AVAILABLE,
    /** 0..10 — used by provenance ranking when the same field arrives from several sources. */
    val trustRank: Int = 5,
    val notes: String? = null
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(trustRank in 0..10) { "trustRank must be within 0..10 but was $trustRank" }
        // URL-addressable sources must declare their domains; feeds (MLS/records/wholesale) are
        // reached through an adapter instead and are therefore allowed to have none.
        val urlAddressable = kind == PropertySourceKind.LISTING_PORTAL || kind == PropertySourceKind.BROKERAGE_SITE
        require(!urlAddressable || domains.isNotEmpty()) {
            "Source '$sourceId' must declare at least one domain"
        }
    }

    val isSupported: Boolean get() = status == SourceStatus.AVAILABLE

    val marketSourceType: String get() = marketType.entitySourceType

    fun matchesHost(host: String): Boolean {
        val lowered = host.lowercase()
        return domains.any { domain ->
            val d = domain.lowercase()
            lowered == d || lowered.endsWith(".$d")
        }
    }

    /**
     * Extracts the source-native listing id from a normalized path + query.
     * @return the id and a human readable origin (e.g. `query:zpid`, `path-rule:homedetails`).
     */
    fun extractListingId(path: String, query: Map<String, String>): Pair<String, String>? {
        for (name in queryIdNames) {
            val value = query.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            if (!value.isNullOrBlank() && value.length <= 64) return value to "query:$name"
        }
        val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        for (rule in pathRules.sortedByDescending { it.confidence }) {
            val target = if (rule.applyToQuery) "$path?$queryString" else path
            val id = rule.extractId(target)
            if (!id.isNullOrBlank()) return id to "path-rule:${rule.name}"
        }
        return null
    }
}

/** Signal that produced a [SourceDetection]. */
enum class DetectionSignal {
    EXACT_HOST,
    SUBDOMAIN_HOST,
    ALIAS_MATCH,
    PATH_RULE,
    QUERY_PARAMETER,
    GENERIC_FALLBACK,
    NONE
}

/** Result of asking the registry "whose URL is this?". */
data class SourceDetection(
    val definition: PropertySourceDefinition?,
    val confidence: Double,
    val signal: DetectionSignal,
    val evidence: List<String> = emptyList(),
    /** Other sources that matched as well (shared CDNs, aggregator domains, typos). */
    val alternatives: List<PropertySourceDefinition> = emptyList()
) {
    val isDetected: Boolean get() = definition != null

    val isSupported: Boolean get() = definition?.isSupported == true

    val sourceId: String? get() = definition?.sourceId

    companion object {
        fun none(evidence: List<String> = emptyList()): SourceDetection =
            SourceDetection(definition = null, confidence = 0.0, signal = DetectionSignal.NONE, evidence = evidence)
    }
}
