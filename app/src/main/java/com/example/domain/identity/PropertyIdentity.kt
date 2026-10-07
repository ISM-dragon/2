package com.example.domain.identity

/**
 * A stable, provider-independent identity for a property in the application's catalog.
 *
 * [parcelId] represents an APN or another jurisdiction-specific parcel identifier. [mlsId] is the
 * listing number inside the originating MLS; unlike provider listing ids it is shared across every
 * portal that syndicates the listing, which makes it a strong cross-source identity signal.
 *
 * A canonical identity can retain multiple [sourceIdentities] as Zillow, Redfin, ATTOM, or another
 * provider is linked to the same property. Field values on the canonical row are (re)computed from
 * those observations by [CanonicalIdentityMerger] using explicit source priority and provenance,
 * so repeated imports converge deterministically instead of "last writer wins".
 */
data class CanonicalPropertyIdentity(
    val canonicalId: String,
    val parcelId: String? = null,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sourceIdentities: Set<SourcePropertyIdentity> = emptySet(),
    val mlsId: String? = null
) {
    init {
        require(canonicalId.isNotBlank()) { "canonicalId must not be blank" }
    }
}

/**
 * Identity data supplied by one property source (for example Zillow, Redfin, or ATTOM).
 *
 * Identifier semantics:
 *  - [parcelId]: an APN or jurisdiction-specific parcel ID; identifies the physical parcel.
 *  - [mlsId]: the MLS/listing number of the originating MLS board; shared across portals that
 *    syndicate the same listing, so it is NOT namespaced by [source].
 *  - [providerListingId]: the source's own listing key (a "source listing ID"); namespaced by
 *    [source] because ids only unique inside one provider.
 *  - [sourceUrl]: the listing URL this record was imported from; the URL host is its namespace,
 *    so the same normalized URL always denotes the same source record even when two adapters
 *    report different [source] labels for it.
 *
 * [sourcePriority] and [observedAt] feed provenance-aware conflict resolution: when two sources
 * disagree about a field value, the lower priority value wins; ties are broken by recency and
 * then deterministically by source name (see [SourceValueResolver]).
 *
 * Coordinates and address fields are optional because providers often omit some identity signals.
 */
data class SourcePropertyIdentity(
    val source: String,
    val providerListingId: String? = null,
    val parcelId: String? = null,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** MLS/listing number from the originating MLS board (cross-provider identifier). */
    val mlsId: String? = null,
    /** Listing URL this record came from; matched after normalization (tracking params removed). */
    val sourceUrl: String? = null,
    /** Lower value wins field conflicts; mirrors `property_sources.priority`. */
    val sourcePriority: Int = SourceProvenance.DEFAULT_SOURCE_PRIORITY,
    /** When the source observed this record (epoch millis); recency tie-breaker. */
    val observedAt: Long = 0L
) {
    /** Provenance stamp of this observation, used by [SourceValueResolver]/[CanonicalIdentityMerger]. */
    fun provenance(): SourceProvenance = SourceProvenance(
        source = source,
        priority = sourcePriority,
        observedAt = observedAt,
        recordRef = providerListingId ?: sourceUrl
    )
}

/**
 * The decision made for one incoming source identity.
 *
 * The five outcomes form the contract of the deduplication layer:
 *  - [EXACT_MATCH] and [CANONICAL_MATCH] are the only statuses that may be merged automatically.
 *  - [POSSIBLE_MATCH] and [CONFLICT] always require review; nothing is ever merged silently.
 */
enum class DeduplicationStatus {
    /**
     * The same source record was imported before: a same-source listing ID or the same normalized
     * listing URL already links to a canonical property. Re-importing is an idempotent refresh.
     */
    EXACT_MATCH,

    /**
     * A different source record resolved to a single existing canonical property through at least
     * one exact identity signal (APN/parcel ID, MLS ID, normalized address, or tight coordinates).
     */
    CANONICAL_MATCH,

    /** Weak or approximate evidence suggests a candidate; do not merge automatically. */
    POSSIBLE_MATCH,

    /** Strong identity signals disagree or identify more than one canonical property. */
    CONFLICT,

    /** No supplied canonical property matched or was sufficiently similar. */
    NEW;

    /** True for the two statuses that identify an existing property well enough to merge into. */
    val isMatch: Boolean
        get() = this == EXACT_MATCH || this == CANONICAL_MATCH

    /** True when a human must look at the decision before anything is merged. */
    val requiresReview: Boolean
        get() = this == POSSIBLE_MATCH || this == CONFLICT

    /** Automatic merging is only ever allowed for unambiguous, explainable matches. */
    val autoMergeAllowed: Boolean
        get() = isMatch
}

/**
 * The identity signal that produced a decision, in strict matching precedence (top = strongest).
 *
 * The precedence below is the single deterministic order used by
 * [PropertyIdentityDeduplicationEngine]; see its KDoc for the full ladder.
 */
enum class IdentityMatchMethod {
    /** County APN / parcel ID - identifies the physical parcel. */
    APN,

    /** MLS listing number - shared by every portal syndicating the listing. */
    MLS_ID,

    /** Source listing ID - the provider's own key, namespaced by provider. */
    SOURCE_LISTING_ID,

    /** Normalized listing URL - namespaced by URL host; a same-record (idempotency) signal. */
    SOURCE_URL,

    /** Normalized street address + locality. */
    NORMALIZED_ADDRESS,

    /** Normalized coordinates inside the exact-match radius. */
    COORDINATES,

    /** Fuzzy address similarity - review-only evidence, never merges. */
    FUZZY_ADDRESS,

    /** No signal matched. */
    NONE,

    /** Exact signals disagreed; the decision is a conflict for human review. */
    CONFLICT
}

/**
 * One explainable step of a deduplication decision.
 *
 * Every result carries the full ordered trail of the signals that were evaluated
 * ([DeduplicationResult.evidence]), so any merge - or refusal to merge - can be reconstructed:
 * which identifier was supplied, which canonical records it matched, which records it
 * contradicted, and what the engine concluded from it.
 */
data class IdentityEvidence(
    /** The evaluated signal, in matching-precedence order. */
    val signal: IdentityMatchMethod,
    /** The identity value that was compared (already normalized). */
    val incomingValue: String,
    /** Canonical ids this signal matched (stable, sorted order). */
    val matchedCanonicalIds: List<String> = emptyList(),
    /** Canonical ids this signal contradicted (stable, sorted order). */
    val contradictedCanonicalIds: List<String> = emptyList(),
    /** Human-readable explanation of what this signal contributed to the decision. */
    val detail: String
)

/**
 * An explainable result from property identity resolution.
 *
 * [canonicalIdentity] is set only for [DeduplicationStatus.EXACT_MATCH] and
 * [DeduplicationStatus.CANONICAL_MATCH]. For reviewable candidates, [candidateCanonicalIds] lists
 * the possible/conflicting canonical records in stable order. The [confidence] is the confidence
 * in the resolution for the supplied candidate set; it is zero when no unique resolution is
 * available. [evidence] is the deterministic audit trail behind [reason].
 */
data class DeduplicationResult(
    val status: DeduplicationStatus,
    val matchMethod: IdentityMatchMethod,
    val canonicalIdentity: CanonicalPropertyIdentity? = null,
    val candidateCanonicalIds: List<String> = emptyList(),
    val confidence: Double = 0.0,
    val reason: String,
    val evidence: List<IdentityEvidence> = emptyList(),
    /**
     * For [DeduplicationStatus.NEW] decisions: the canonical identity the caller should persist
     * when it accepts the listing as new. The engine itself has no id to assign, so the importer
     * fills this in; it is null for every other status.
     */
    val proposedCanonicalIdentity: CanonicalPropertyIdentity? = null
) {
    val matchedCanonicalId: String?
        get() = canonicalIdentity?.canonicalId

    /** True when this result may be merged/refreshed automatically. */
    val autoMergeAllowed: Boolean
        get() = status.autoMergeAllowed

    /** True when a human must review before any merge happens. */
    val requiresReview: Boolean
        get() = status.requiresReview

    /** The canonical record this decision refers to - matched, or proposed for a new listing. */
    val resolvedCanonicalIdentity: CanonicalPropertyIdentity?
        get() = canonicalIdentity ?: proposedCanonicalIdentity
}
