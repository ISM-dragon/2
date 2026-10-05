package com.example.data.adapter

import com.example.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NormalizedPropertyBundle(
    val property: PropertyEntity,
    val images: List<PropertyImageEntity>,
    val marketData: MarketDataEntity,
    val rentEstimate: RentEstimateEntity,
    val taxRecord: TaxRecordEntity,
    val salesHistory: List<SalesHistoryEntity>,
    val comps: List<ComparablePropertyEntity>
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

class PropertySourceManager(
    private val adapters: List<PropertySourceAdapter>
) {
    suspend fun fetchAllSources(
        query: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        limitPerSource: Int = 10
    ): List<NormalizedPropertyBundle> = withContext(Dispatchers.IO) {
        val results = mutableListOf<NormalizedPropertyBundle>()
        for (adapter in adapters) {
            try {
                val bundles = adapter.fetchProperties(query, minPrice, maxPrice, limitPerSource)
                results.addAll(bundles)
            } catch (e: Exception) {
                // Log and continue with other sources
            }
        }
        results
    }
}
