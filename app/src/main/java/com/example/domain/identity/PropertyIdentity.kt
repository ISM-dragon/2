package com.example.domain.identity

/**
 * A stable, provider-independent identity for a property in the application's catalog.
 *
 * [parcelId] represents an APN or another jurisdiction-specific parcel identifier. A canonical
 * identity can retain multiple [sourceIdentities] as Zillow, Redfin, ATTOM, or another provider is
 * linked to the same property.
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
    val sourceIdentities: Set<SourcePropertyIdentity> = emptySet()
) {
    init {
        require(canonicalId.isNotBlank()) { "canonicalId must not be blank" }
    }
}

/**
 * Identity data supplied by one property source (for example Zillow, Redfin, or ATTOM).
 *
 * [parcelId] is used for either an APN or a jurisdiction-specific parcel ID. Coordinates and
 * address fields are optional because providers often omit some identity signals.
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
    val longitude: Double? = null
)

/** The decision made for one incoming source identity. */
enum class DeduplicationStatus {
    /** A single existing canonical property was identified using at least one exact signal. */
    MATCHED,

    /** No supplied canonical property matched or was sufficiently similar. */
    NEW,

    /** Weak or approximate evidence suggests a candidate; do not merge automatically. */
    POSSIBLE_MATCH,

    /** Strong identity signals disagree or identify more than one canonical property. */
    CONFLICT
}

/** The strongest signal used by the deduplication engine. */
enum class IdentityMatchMethod {
    APN,
    NORMALIZED_ADDRESS,
    COORDINATES,
    PROVIDER_LISTING_ID,
    FUZZY_ADDRESS,
    NONE,
    CONFLICT
}

/**
 * An explainable result from property identity resolution.
 *
 * [canonicalIdentity] is set only for [DeduplicationStatus.MATCHED]. For reviewable candidates,
 * [candidateCanonicalIds] lists the possible/conflicting canonical records in stable order. The
 * [confidence] is the confidence in the resolution for the supplied candidate set; it is zero
 * when no unique resolution is available.
 */
data class DeduplicationResult(
    val status: DeduplicationStatus,
    val matchMethod: IdentityMatchMethod,
    val canonicalIdentity: CanonicalPropertyIdentity? = null,
    val candidateCanonicalIds: List<String> = emptyList(),
    val confidence: Double = 0.0,
    val reason: String
) {
    val matchedCanonicalId: String?
        get() = canonicalIdentity?.canonicalId
}
