package com.example.data.adapter

import com.example.data.local.entity.*
import com.example.domain.automation.DiscoveredProperty
import com.example.domain.automation.PropertySourceGateway
import com.example.domain.identity.CanonicalPropertyIdentity
import com.example.domain.identity.DeduplicationResult
import com.example.domain.identity.DeduplicationStatus
import com.example.domain.identity.PropertyIdentityDeduplicationEngine
import com.example.domain.identity.SourcePropertyIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A single normalized record coming from a source, ready to be handed to
 * `PropertyImportRepository`.
 *
 * `sourceId` / `externalId` feed the provenance table and are the first deduplication level; when
 * `externalId` is blank the importer falls back to the property id. `sourceIdentity` carries the
 * provider-specific identifiers (including the county APN) used by the identity engine.
 */
data class NormalizedPropertyBundle(
    val property: PropertyEntity,
    val images: List<PropertyImageEntity>,
    val marketData: MarketDataEntity,
    val rentEstimate: RentEstimateEntity,
    val taxRecord: TaxRecordEntity,
    val salesHistory: List<SalesHistoryEntity>,
    val comps: List<PropertyCompEntity>,
    // ── provenance & canonical ingestion metadata ───────────────────────────────────────────────
    val sourceId: String = "",
    val externalId: String = "",
    val externalUrl: String = "",
    val confidence: Double = 1.0,
    val ingestionMethod: String = PropertyIngestionMethod.API,
    val payloadHash: String = "",
    val rawPayloadRef: String = "",
    val sourceUpdatedAt: Long = 0L,
    val enrichments: List<PropertyEnrichmentEntity> = emptyList(),
    val financials: PropertyFinancialEntity? = null,
    /** Optional provider-specific identity data, including APN/parcel ID when available. */
    val sourceIdentity: SourcePropertyIdentity? = null
)

interface PropertySourceAdapter {
    val sourceName: String
    val sourceType: String // "ON_MARKET" or "OFF_MARKET"

    /**
     * `property_sources.id` this adapter feeds; see [PropertySourceDefaults].
     *
     * Defaults to the adapter name so lightweight/anonymous adapters keep working without a
     * registered source row.
     */
    val sourceId: String
        get() = sourceName

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
) : PropertySourceGateway {

    /** Source ids this manager can pull from; one import job is created per id. */
    val availableSourceIds: List<String>
        get() = adapters.map { it.sourceId }.distinct()

    /** [PropertySourceGateway] port: listings that are confidently new. */
    override suspend fun fetchBundles(limitPerSource: Int): List<NormalizedPropertyBundle> =
        fetchAllSources(limitPerSource = limitPerSource)

    /**
     * [PropertySourceGateway] port with deduplication: the caller passes the canonical identities
     * already stored locally, so previously imported properties are reported as known rather than new.
     */
    override suspend fun fetchResolvedBundles(
        existing: Collection<CanonicalPropertyIdentity>,
        limitPerSource: Int
    ): List<DiscoveredProperty> = fetchAllSourcesWithDeduplication(
        existingCanonicalProperties = existing,
        limitPerSource = limitPerSource
    ).map { decision ->
        DiscoveredProperty(
            bundle = decision.bundle,
            isNew = decision.result.status == DeduplicationStatus.NEW,
            needsReview = decision.result.status == DeduplicationStatus.POSSIBLE_MATCH ||
                decision.result.status == DeduplicationStatus.CONFLICT
        )
    }

    /**
     * Fetches source listings and returns only confidently new properties. Use
     * [fetchAllSourcesWithDeduplication] when callers need MATCHED/POSSIBLE_MATCH/CONFLICT
     * decisions as well, or [fetchSource] when an already-known listing must be re-imported so the
     * importer can refresh it.
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
                    decisions += PropertySourceDeduplicationDecision(
                        bundle = bundle.withProvenance(adapter),
                        sourceIdentity = identity,
                        result = result
                    )

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
                // Exception messages may contain property search queries, URLs, or response data.
                android.util.Log.w(
                    "PropertySourceManager",
                    "Source query failed (${e.javaClass.simpleName})"
                )
            }
        }
        decisions
    }

    /**
     * Fetches one source's raw listings, including addresses that are already stored locally, so
     * the importer can attribute a job to that source and refresh or merge the record it matched.
     */
    suspend fun fetchSource(
        sourceId: String,
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        limit: Int = 20
    ): List<NormalizedPropertyBundle> = fetchAllRawSources(
        query = query,
        minPrice = minPrice,
        maxPrice = maxPrice,
        limitPerSource = limit
    ).filter { it.sourceId == sourceId }

    /** Every adapter's listings, deduplicated by property id, tagged with their provenance. */
    suspend fun fetchAllRawSources(
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        limitPerSource: Int = 10
    ): List<NormalizedPropertyBundle> = withContext(Dispatchers.IO) {
        val results = mutableListOf<NormalizedPropertyBundle>()
        val seenIds = mutableSetOf<String>()

        for (adapter in adapters) {
            try {
                val bundles = adapter.fetchProperties(query, minPrice, maxPrice, limitPerSource)
                for (bundle in bundles) {
                    if (seenIds.add(bundle.property.id)) {
                        results += bundle.withProvenance(adapter)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Avoid logging exception messages, which may contain user query or response data.
                android.util.Log.w(
                    "PropertySourceManager",
                    "Property source request failed (${e.javaClass.simpleName})"
                )
            }
        }
        results
    }

    /** Fills in blank provenance fields from the adapter that produced the record. */
    private fun NormalizedPropertyBundle.withProvenance(adapter: PropertySourceAdapter): NormalizedPropertyBundle =
        if (sourceId.isNotBlank() && externalId.isNotBlank()) {
            this
        } else {
            copy(
                sourceId = sourceId.ifBlank { adapter.sourceId },
                externalId = externalId.ifBlank { property.id }
            )
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
