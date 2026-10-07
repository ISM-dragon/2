package com.example.data.adapter

import com.example.data.local.entity.*
import com.example.domain.identity.CanonicalPropertyIdentity
import com.example.domain.identity.DeduplicationResult
import com.example.domain.identity.DeduplicationStatus
import com.example.domain.identity.PropertyIdentityDeduplicationEngine
import com.example.domain.identity.SourcePropertyIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NormalizedPropertyBundle(
    val property: PropertyEntity,
    val images: List<PropertyImageEntity>,
    val marketData: MarketDataEntity,
    val rentEstimate: RentEstimateEntity,
    val taxRecord: TaxRecordEntity,
    val salesHistory: List<SalesHistoryEntity>,
    val comps: List<ComparablePropertyEntity>,
    /** Optional provider-specific identity data, including APN/parcel ID when available. */
    val sourceIdentity: SourcePropertyIdentity? = null
)

interface PropertySourceAdapter {
    val sourceName: String
    val sourceType: String // "ON_MARKET" or "OFF_MARKET"
    suspend fun fetchProperties(
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        limit: Int = 20
    ): List<NormalizedPropertyBundle>
}

/** One source listing and the explainable identity decision made for it. */
data class PropertySourceDeduplicationDecision(
    val bundle: NormalizedPropertyBundle,
    val sourceIdentity: SourcePropertyIdentity,
    val result: DeduplicationResult
)

/**
 * Converts the identity data currently persisted on a property row into a canonical candidate.
 * Provider IDs and APNs are intentionally not inferred from [PropertyEntity.id]/[PropertyEntity.sourceType]:
 * sourceType is a listing category, not a provider namespace.
 */
fun PropertyEntity.toCanonicalPropertyIdentity(): CanonicalPropertyIdentity = CanonicalPropertyIdentity(
    canonicalId = id,
    address = address,
    city = city,
    state = state,
    postalCode = zipCode,
    latitude = latitude,
    longitude = longitude
)

class PropertySourceManager(
    private val adapters: List<PropertySourceAdapter>,
    private val identityEngine: PropertyIdentityDeduplicationEngine = PropertyIdentityDeduplicationEngine()
) {
    /**
     * Fetches source listings and returns only confidently new properties. Use
     * [fetchAllSourcesWithDeduplication] when callers need MATCHED/POSSIBLE_MATCH/CONFLICT
     * decisions as well.
     */
    suspend fun fetchAllSources(
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        limitPerSource: Int = 10
    ): List<NormalizedPropertyBundle> = fetchAllSourcesWithDeduplication(
        query = query,
        minPrice = minPrice,
        maxPrice = maxPrice,
        limitPerSource = limitPerSource
    ).filter { it.result.status == DeduplicationStatus.NEW }
        .map { it.bundle }

    /**
     * Fetches listings in provider order, resolving each against the supplied canonical catalog and
     * the earlier listings in this fetch. NEW listings are added to the in-memory candidate catalog
     * so a later Zillow/Redfin/ATTOM result can match them. POSSIBLE_MATCH and CONFLICT results are
     * returned for review and are not treated as new canonical records.
     */
    suspend fun fetchAllSourcesWithDeduplication(
        existingCanonicalProperties: Collection<CanonicalPropertyIdentity> = emptyList(),
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        limitPerSource: Int = 10
    ): List<PropertySourceDeduplicationDecision> = withContext(Dispatchers.IO) {
        val knownIdentities = existingCanonicalProperties.toMutableList()
        val decisions = mutableListOf<PropertySourceDeduplicationDecision>()

        for (adapter in adapters) {
            try {
                val bundles = adapter.fetchProperties(query, minPrice, maxPrice, limitPerSource)
                for (bundle in bundles) {
                    val identity = sourceIdentityFor(adapter, bundle)
                    val result = identityEngine.deduplicate(identity, knownIdentities)
                    decisions += PropertySourceDeduplicationDecision(bundle, identity, result)

                    when (result.status) {
                        DeduplicationStatus.NEW -> knownIdentities += CanonicalPropertyIdentity(
                            canonicalId = bundle.property.id,
                            parcelId = identity.parcelId,
                            address = identity.address,
                            city = identity.city,
                            state = identity.state,
                            postalCode = identity.postalCode,
                            latitude = identity.latitude,
                            longitude = identity.longitude,
                            sourceIdentities = setOf(identity)
                        )

                        DeduplicationStatus.MATCHED -> {
                            val matchedId = result.matchedCanonicalId
                            val matchedIndex = knownIdentities.indexOfFirst { it.canonicalId == matchedId }
                            if (matchedIndex >= 0) {
                                val matched = knownIdentities[matchedIndex]
                                knownIdentities[matchedIndex] = matched.copy(
                                    sourceIdentities = matched.sourceIdentities + identity
                                )
                            }
                        }

                        DeduplicationStatus.POSSIBLE_MATCH,
                        DeduplicationStatus.CONFLICT -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Safe logging: source name and error class without sensitive payload exposure.
                android.util.Log.w(
                    "PropertySourceManager",
                    "Source [${adapter.sourceName}] query failed: ${e.javaClass.simpleName} - ${e.message}"
                )
            }
        }
        decisions
    }

    private fun sourceIdentityFor(
        adapter: PropertySourceAdapter,
        bundle: NormalizedPropertyBundle
    ): SourcePropertyIdentity {
        val supplied = bundle.sourceIdentity
        val property = bundle.property
        return SourcePropertyIdentity(
            source = supplied?.source?.takeIf { it.isNotBlank() } ?: adapter.sourceName,
            providerListingId = supplied?.providerListingId?.takeIf { it.isNotBlank() } ?: property.id,
            parcelId = supplied?.parcelId,
            address = supplied?.address?.takeIf { it.isNotBlank() } ?: property.address,
            city = supplied?.city?.takeIf { it.isNotBlank() } ?: property.city,
            state = supplied?.state?.takeIf { it.isNotBlank() } ?: property.state,
            postalCode = supplied?.postalCode?.takeIf { it.isNotBlank() } ?: property.zipCode,
            latitude = supplied?.latitude ?: property.latitude,
            longitude = supplied?.longitude ?: property.longitude
        )
    }
}
