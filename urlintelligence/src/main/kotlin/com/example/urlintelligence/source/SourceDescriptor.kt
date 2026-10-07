package com.example.urlintelligence.source

/** How trustworthy / how licensed a source is. Drives opt-in and defaults. */
enum class SourceTier {
    /** First-party API with a contract. */
    OFFICIAL_API,

    /** Licensed feed (IDX/MLS/ATTOM-style). */
    LICENSED_FEED,

    /** Public web page parsing — best effort, subject to change. */
    PUBLIC_WEB,

    /** Anything unverified. */
    BEST_EFFORT
}

/** What an adapter can actually deliver; used for capability-aware pipelines. */
data class SourceCapabilities(
    val canResolveByUrl: Boolean = true,
    val canExtractRentEstimate: Boolean = false,
    val canExtractTaxRecord: Boolean = false,
    val canExtractSalesHistory: Boolean = false,
    val canExtractComps: Boolean = false,
    val requiresJavascript: Boolean = false,
    val supportsPartialData: Boolean = true
)

/**
 * Declaration of a source: how to recognize it, how to extract its listing id,
 * and what it is allowed to do. Adding a new provider means adding one instance
 * of this class plus an adapter — nothing else in the pipeline changes.
 */
data class SourceDescriptor(
    val id: String,
    val displayName: String,
    /** Suffix match against the canonical host, e.g. "zillow.com" matches "www.zillow.com". */
    val hostSuffixes: List<String>,
    val pathPatterns: List<Regex> = emptyList(),
    /** First capture group must be the listing id. */
    val idPatterns: List<Regex> = emptyList(),
    /** Fallback id extraction from the document body (embedded JSON blobs). */
    val bodyIdPatterns: List<Regex> = emptyList(),
    val capabilities: SourceCapabilities = SourceCapabilities(),
    val tier: SourceTier = SourceTier.PUBLIC_WEB,
    val confidenceFloor: Double = 0.5,
    /**
     * When true the resolver refuses to fetch unless the caller explicitly
     * allow-lists the source id (used for ToS/robots-gated providers).
     */
    val requiresOptIn: Boolean = false,
    val enabledByDefault: Boolean = true,
    val notes: String? = null
) {
    init {
        require(id.isNotBlank()) { "source id must not be blank" }
        require(hostSuffixes.isNotEmpty() || id == GENERIC_SOURCE_ID) {
            "at least one host suffix is required (or use the generic source id)"
        }
        require(confidenceFloor in 0.0..1.0) { "confidenceFloor must be within 0.0..1.0" }
    }

    fun matchesHost(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        return hostSuffixes.any { suffix ->
            val s = suffix.lowercase().trimEnd('.')
            normalized == s || normalized.endsWith(".$s")
        }
    }

    fun matchesPath(path: String): Boolean = pathPatterns.any { it.containsMatchIn(path) }

    fun extractIdFromPath(path: String): String? =
        idPatterns.firstNotNullOfOrNullOrNull { pattern ->
            pattern.find(path)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }

    fun extractIdFromBody(body: String): String? =
        bodyIdPatterns.firstNotNullOfOrNullOrNull { pattern ->
            pattern.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }

    companion object {
        /** Catch-all source: parses whatever public page it is given. */
        const val GENERIC_SOURCE_ID: String = "generic.web"
    }
}

private inline fun <T : Any> List<Regex>.firstNotNullOfOrNullOrNull(transform: (Regex) -> T?): T? {
    for (item in this) {
        transform(item)?.let { return it }
    }
    return null
}

/**
 * Registry of known providers.
 *
 * Detection rules are intentionally declarative (host + path + id regexes) so a new
 * provider can be announced before its adapter exists: register the descriptor, and
 * imports for that host fail with a clear "no adapter" classification instead of
 * silently hitting the generic parser.
 */
object KnownSources {

    val ZILLOW: SourceDescriptor = SourceDescriptor(
        id = "zillow",
        displayName = "Zillow",
        hostSuffixes = listOf("zillow.com"),
        pathPatterns = listOf(
            Regex("/homedetails/", RegexOption.IGNORE_CASE),
            Regex("/homes/", RegexOption.IGNORE_CASE),
            Regex("/b/", RegexOption.IGNORE_CASE)
        ),
        idPatterns = listOf(
            Regex("/(\\d{6,12})_zpid", RegexOption.IGNORE_CASE),
            Regex("zpid/(\\d{6,12})", RegexOption.IGNORE_CASE)
        ),
        bodyIdPatterns = listOf(
            Regex("\"zpid\"\\s*:\\s*\"?(\\d{6,12})\"?"),
            Regex("\"zpid\"\\s*=\\s*\"?(\\d{6,12})\"?")
        ),
        capabilities = SourceCapabilities(
            canExtractRentEstimate = true,
            canExtractTaxRecord = true,
            canExtractSalesHistory = true
        ),
        tier = SourceTier.PUBLIC_WEB,
        requiresOptIn = true,
        notes = "Public web pages. Respect robots.txt and Zillow ToS; enable only with an explicit opt-in."
    )

    val REDFIN: SourceDescriptor = SourceDescriptor(
        id = "redfin",
        displayName = "Redfin",
        hostSuffixes = listOf("redfin.com"),
        pathPatterns = listOf(
            Regex("/[A-Z]{2}/(?:[^/]+/){1,4}home/\\d+", RegexOption.IGNORE_CASE),
            Regex("/stingray/", RegexOption.IGNORE_CASE)
        ),
        idPatterns = listOf(
            Regex("/home/(\\d{5,12})", RegexOption.IGNORE_CASE),
            Regex("[?&]listingId=(\\d{5,12})", RegexOption.IGNORE_CASE)
        ),
        bodyIdPatterns = listOf(
            Regex("\"listingId\"\\s*:\\s*\"?(\\d{5,12})\"?"),
            Regex("\"propertyId\"\\s*:\\s*\"?(\\d{5,12})\"?")
        ),
        capabilities = SourceCapabilities(
            canExtractTaxRecord = true,
            canExtractSalesHistory = true
        ),
        tier = SourceTier.PUBLIC_WEB,
        requiresOptIn = true,
        notes = "Public web pages. Respect robots.txt and Redfin ToS."
    )

    val REALTOR: SourceDescriptor = SourceDescriptor(
        id = "realtor",
        displayName = "Realtor.com",
        hostSuffixes = listOf("realtor.com"),
        pathPatterns = listOf(
            Regex("/realestateandhomes-detail/", RegexOption.IGNORE_CASE),
            Regex("/realestateandhomes-search/", RegexOption.IGNORE_CASE)
        ),
        idPatterns = listOf(
            Regex("-([A-Z0-9]{6,24})$", RegexOption.IGNORE_CASE),
            Regex("/realestateandhomes-detail/[^/]*?([0-9]{5,12})", RegexOption.IGNORE_CASE)
        ),
        bodyIdPatterns = listOf(
            Regex("\"listing_id\"\\s*:\\s*\"?([A-Za-z0-9_-]{6,24})\"?"),
            Regex("\"property_id\"\\s*:\\s*\"?([A-Za-z0-9_-]{6,24})\"?")
        ),
        capabilities = SourceCapabilities(
            canExtractTaxRecord = true
        ),
        tier = SourceTier.PUBLIC_WEB,
        requiresOptIn = true,
        notes = "Public web pages. Respect robots.txt and Realtor.com ToS."
    )

    val HOMES: SourceDescriptor = SourceDescriptor(
        id = "homes",
        displayName = "Homes.com",
        hostSuffixes = listOf("homes.com"),
        pathPatterns = listOf(
            Regex("/property/", RegexOption.IGNORE_CASE),
            Regex("/homes-for-sale/", RegexOption.IGNORE_CASE),
            Regex("/homes-for-rent/", RegexOption.IGNORE_CASE)
        ),
        idPatterns = listOf(
            Regex("/(\\d{6,12})/?(?:\\?|$)"),
            Regex("[?&]propertyId=(\\d{5,12})", RegexOption.IGNORE_CASE)
        ),
        bodyIdPatterns = listOf(
            Regex("\"propertyId\"\\s*:\\s*\"?(\\d{5,12})\"?"),
            Regex("\"listingId\"\\s*:\\s*\"?(\\d{5,12})\"?")
        ),
        tier = SourceTier.PUBLIC_WEB,
        requiresOptIn = true,
        notes = "Public web pages. Respect robots.txt and Homes.com ToS."
    )

    val GENERIC: SourceDescriptor = SourceDescriptor(
        id = SourceDescriptor.GENERIC_SOURCE_ID,
        displayName = "Generic public web page",
        hostSuffixes = emptyList(),
        pathPatterns = emptyList(),
        idPatterns = emptyList(),
        bodyIdPatterns = listOf(
            Regex("\"(?:mlsId|mlsNumber|mlsNumber)\"\\s*:\\s*\"?([A-Za-z0-9_-]{4,24})\"?")
        ),
        capabilities = SourceCapabilities(supportsPartialData = true),
        tier = SourceTier.BEST_EFFORT,
        confidenceFloor = 0.2,
        requiresOptIn = false,
        enabledByDefault = true,
        notes = "Fallback parser for unknown hosts: JSON-LD, meta tags and text heuristics."
    )

    /** All sources the layer knows about, in detection order. */
    fun all(): List<SourceDescriptor> = listOf(ZILLOW, REDFIN, REALTOR, HOMES, GENERIC)

    /** Sources that have a real adapter implementation in this build. */
    fun withAdapters(): List<SourceDescriptor> =
        listOf(ZILLOW, REDFIN, REALTOR, HOMES, GENERIC)

    fun byId(id: String): SourceDescriptor? = all().firstOrNull { it.id == id }
}
