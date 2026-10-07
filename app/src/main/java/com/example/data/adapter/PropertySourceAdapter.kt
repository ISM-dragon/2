package com.example.data.adapter

import com.example.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A single normalized record coming from a source, ready to be handed to
 * `PropertyImportRepository`.
 *
 * `sourceId` / `externalId` feed the provenance table and are the first deduplication level; when
 * `externalId` is blank the importer falls back to the property id.
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
    val financials: PropertyFinancialEntity? = null
)

interface PropertySourceAdapter {
    val sourceName: String
    val sourceType: String // "ON_MARKET" or "OFF_MARKET"
    /** `property_sources.id` this adapter feeds; see [PropertySourceDefaults]. */
    val sourceId: String

    suspend fun fetchProperties(
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        limit: Int = 20
    ): List<NormalizedPropertyBundle>
}

class PropertySourceManager(
    private val adapters: List<PropertySourceAdapter>
) {
    /** Source ids this manager can pull from; one import job is created per id. */
    val availableSourceIds: List<String>
        get() = adapters.map { it.sourceId }.distinct()

    /** Fetches a single source so the importer can attribute one job to it. */
    suspend fun fetchSource(
        sourceId: String,
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        limit: Int = 20
    ): List<NormalizedPropertyBundle> = fetchAllSources(
        query = query,
        minPrice = minPrice,
        maxPrice = maxPrice,
        limitPerSource = limit
    ).filter { it.sourceId == sourceId }

    suspend fun fetchAllSources(
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
                for (b in bundles) {
                    if (seenIds.add(b.property.id)) {
                        results.add(
                            b.copy(
                                sourceId = b.sourceId.ifBlank { adapter.sourceId },
                                externalId = b.externalId.ifBlank { b.property.id }
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Safe logging: source name and error class without sensitive payload exposure
                android.util.Log.w(
                    "PropertySourceManager",
                    "Source [${adapter.sourceName}] query failed: ${e.javaClass.simpleName} - ${e.message}"
                )
            }
        }
        results
    }
}
