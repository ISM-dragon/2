package com.example.domain.intelligence.dedup

import com.example.data.adapter.toCanonicalPropertyIdentity
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.PropertyEntity
import com.example.domain.identity.DeduplicationStatus
import com.example.domain.identity.IdentityMatchMethod
import com.example.domain.identity.PropertyIdentityDeduplicationEngine
import com.example.domain.identity.SourcePropertyIdentity
import com.example.domain.intelligence.model.CanonicalProperty

/**
 * The outcome of resolving one incoming listing against the local catalog.
 *
 * [isMatch] is true only for unambiguous resolutions (an exact re-import of the same source
 * record, or a cross-source match through an exact identity signal). Weak evidence - fuzzy
 * addresses or nearby-but-not-identical coordinates - is reported as [needsReview] with
 * [isMatch] = false, so ambiguous candidates are NEVER silently merged. [reason] and [matchType]
 * make every decision explainable at the call site and in import logs.
 */
data class MatchResult(
    val isMatch: Boolean,
    val existingProperty: PropertyEntity? = null,
    /** "APN", "MLS_ID", "SOURCE_LISTING_ID", "SOURCE_URL", "EXACT_ADDRESS", "COORDINATES", "FUZZY_ADDRESS", "CONFLICT", "NONE" */
    val matchType: String? = null,
    /** True when the evidence is ambiguous (possible match or conflict) and a human must review. */
    val needsReview: Boolean = false,
    /** Explainable summary of the decision, safe to log and surface. */
    val reason: String = "",
    val confidence: Double = 0.0
)

/**
 * Deduplicates URL-intelligence imports against the persisted property catalog.
 *
 * This class used to implement its own first-hit-wins ladder (exact address, then coordinates
 * within 35 m, then a fuzzy Jaccard address match that merged silently at >= 0.75). That ladder
 * depended on DAO iteration order and auto-merged ambiguous fuzzy evidence. It is now a thin
 * adapter over [PropertyIdentityDeduplicationEngine], which guarantees:
 *
 *  - deterministic matching precedence: APN/parcel ID > MLS ID > source listing ID > source URL >
 *    normalized address > normalized coordinates;
 *  - fuzzy address and merely-nearby coordinates produce POSSIBLE_MATCH (review) and never merge;
 *  - disagreeing exact signals produce CONFLICT (review) and never merge;
 *  - identical inputs produce identical [MatchResult]s on every run (idempotent re-imports), and
 *    candidate ordering cannot influence the outcome.
 */
class PropertyDeduplicator(
    private val propertyDao: PropertyDao,
    private val identityEngine: PropertyIdentityDeduplicationEngine = PropertyIdentityDeduplicationEngine()
) {
    suspend fun findExistingMatch(canonical: CanonicalProperty): MatchResult {
        // Sort by id so the lookup and any tie-breaking are fully deterministic.
        val allProps = propertyDao.getAllPropertiesList().sortedBy { it.id }
        if (allProps.isEmpty()) {
            return MatchResult(
                isMatch = false,
                matchType = "NONE",
                reason = "catalog is empty; the listing is new"
            )
        }

        val incoming = canonical.toSourcePropertyIdentity()
        val candidates = allProps.map { it.toCanonicalPropertyIdentity() }
        val result = identityEngine.deduplicate(incoming, candidates)

        return when (result.status) {
            DeduplicationStatus.EXACT_MATCH,
            DeduplicationStatus.CANONICAL_MATCH -> MatchResult(
                isMatch = true,
                existingProperty = allProps.firstOrNull { it.id == result.matchedCanonicalId },
                matchType = result.matchMethod.toLegacyMatchType(),
                reason = result.reason,
                confidence = result.confidence
            )

            DeduplicationStatus.POSSIBLE_MATCH,
            DeduplicationStatus.CONFLICT -> MatchResult(
                isMatch = false,
                existingProperty = null,
                matchType = result.matchMethod.toLegacyMatchType(),
                needsReview = true,
                reason = result.reason,
                confidence = result.confidence
            )

            DeduplicationStatus.NEW -> MatchResult(
                isMatch = false,
                matchType = "NONE",
                reason = result.reason
            )
        }
    }

    /**
     * Maps the extraction result onto the identity signals the shared engine understands.
     * `listingId` is the provider's own key (namespaced by [CanonicalProperty.source]); `apn` /
     * `parcelId` identify the physical parcel; `sourceUrl` makes repeated imports of the same
     * listing URL an idempotent exact match.
     */
    private fun CanonicalProperty.toSourcePropertyIdentity(): SourcePropertyIdentity =
        SourcePropertyIdentity(
            source = source,
            providerListingId = listingId,
            parcelId = apn?.takeIf { it.isNotBlank() } ?: parcelId?.takeIf { it.isNotBlank() },
            address = street?.takeIf { it.isNotBlank() } ?: address,
            city = city,
            state = state,
            postalCode = zipCode,
            latitude = latitude,
            longitude = longitude,
            sourceUrl = sourceUrl.takeIf { it.isNotBlank() }
        )

    private fun IdentityMatchMethod.toLegacyMatchType(): String = when (this) {
        IdentityMatchMethod.APN -> "APN"
        IdentityMatchMethod.MLS_ID -> "MLS_ID"
        IdentityMatchMethod.SOURCE_LISTING_ID -> "SOURCE_LISTING_ID"
        IdentityMatchMethod.SOURCE_URL -> "SOURCE_URL"
        IdentityMatchMethod.NORMALIZED_ADDRESS -> "EXACT_ADDRESS"
        IdentityMatchMethod.COORDINATES -> "COORDINATES"
        IdentityMatchMethod.FUZZY_ADDRESS -> "FUZZY_ADDRESS"
        IdentityMatchMethod.NONE -> "NONE"
        IdentityMatchMethod.CONFLICT -> "CONFLICT"
    }
}
