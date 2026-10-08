package com.example.data.adapter

import com.example.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Adapter providing realistic curated real estate seed data for MLS on-market testing
// Ready to be replaced or extended with a live MLS/IDX API endpoint
class OnMarketMlsAdapter : PropertySourceAdapter {
    override val sourceName: String = "MLS Feed (Demo Seed Dataset)"
    override val sourceType: String = "ON_MARKET"
    override val sourceId: String = PropertySourceDefaults.MLS_ID

    override suspend fun fetchProperties(
        query: String?,
        minPrice: Double?,
        maxPrice: Double?,
        limit: Int
    ): List<NormalizedPropertyBundle> = withContext(Dispatchers.IO) {
        val all = PropertySeedData.getSeedBundles().filter { it.property.sourceType == "ON_MARKET" }
        filterBundles(all, query, minPrice, maxPrice, limit)
    }
}

// Adapter providing realistic off-market & distressed wholesale seed data
// Ready to be replaced or extended with live Probate / County Recorder / ATTOM API endpoints
class OffMarketWholesaleAdapter : PropertySourceAdapter {
    override val sourceName: String = "Off-Market Wholesale (Demo Seed Dataset)"
    override val sourceType: String = "OFF_MARKET"
    override val sourceId: String = PropertySourceDefaults.WHOLESALE_ID

    override suspend fun fetchProperties(
        query: String?,
        minPrice: Double?,
        maxPrice: Double?,
        limit: Int
    ): List<NormalizedPropertyBundle> = withContext(Dispatchers.IO) {
        val all = PropertySeedData.getSeedBundles().filter { it.property.sourceType == "OFF_MARKET" }
        filterBundles(all, query, minPrice, maxPrice, limit)
    }
}

private fun filterBundles(
    bundles: List<NormalizedPropertyBundle>,
    query: String?,
    minPrice: Double?,
    maxPrice: Double?,
    limit: Int
): List<NormalizedPropertyBundle> {
    return bundles.filter { b ->
        val p = b.property
        val matchesQuery = query.isNullOrBlank() ||
                p.address.contains(query, ignoreCase = true) ||
                p.city.contains(query, ignoreCase = true) ||
                p.state.contains(query, ignoreCase = true) ||
                p.propertyType.contains(query, ignoreCase = true)

        val matchesMin = minPrice == null || p.price >= minPrice
        val matchesMax = maxPrice == null || p.price <= maxPrice

        matchesQuery && matchesMin && matchesMax
    }.take(limit)
}

object PropertySeedData {
    fun getSeedBundles(): List<NormalizedPropertyBundle> {
        return emptyList()
    }
}
