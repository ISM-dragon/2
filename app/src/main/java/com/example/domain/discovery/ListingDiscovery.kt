package com.example.domain.discovery

/** Provider observations are not underwriting estimates or canonical Room property rows. */
data class ListingObservation(
    val providerId: String,
    val providerPropertyId: String,
    val canonicalPropertyId: String? = null,
    val address: String? = null,
    val city: String? = null,
    val stateCode: String? = null,
    val postalCode: String? = null,
    val askingPrice: Double? = null,
    val updatedAt: Long? = null,
    val sourceRetrievedAt: Long
)

data class DiscoveryQuery(
    val stateCode: String? = null,
    val city: String? = null,
    val postalCode: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val propertyType: String? = null,
    val minBedrooms: Int? = null,
    val minBathrooms: Double? = null
) {
    val scope: String get() = when {
        !postalCode.isNullOrBlank() -> "ZIP code"
        !city.isNullOrBlank() -> "City"
        !stateCode.isNullOrBlank() -> "State"
        else -> "Nationwide"
    }
}

data class ProviderCapabilities(
    val nationwideSearch: Boolean,
    val supportedFilters: Set<String>,
    val incrementalUpdates: Boolean,
    val regions: Set<String> = emptySet()
)

data class ListingPage(
    val observations: List<ListingObservation>,
    val continuationToken: String?,
    val incompleteReason: String? = null
)

/** Only implement this port after verifying a licensed provider's API and permitted retention rules. */
interface ListingProvider {
    val id: String
    fun getProviderCapabilities(): ProviderCapabilities
    fun getCoverageMetadata(): String
    suspend fun searchProperties(query: DiscoveryQuery, continuationToken: String?): ListingPage
    suspend fun fetchPropertyDetails(providerPropertyId: String): ListingObservation?
    suspend fun fetchListingUpdates(syncCursor: String?): ListingPage
}

/** A single bounded page per call. Tokens are opaque and must be bound to the same query. */
class ListingDiscovery(private val providers: List<ListingProvider>) {
    private val states = setOf("AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA","HI","ID","IL","IN","IA","KS","KY","LA","ME","MD","MA","MI","MN","MS","MO","MT","NE","NV","NH","NJ","NM","NY","NC","ND","OH","OK","OR","PA","RI","SC","SD","TN","TX","UT","VT","VA","WA","WV","WI","WY","DC")

    suspend fun page(provider: ListingProvider, query: DiscoveryQuery, token: String? = null): ListingPage {
        require(provider in providers) { "Provider is not configured" }
        val state = query.stateCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        require(state == null || state in states || state in provider.getProviderCapabilities().regions) { "Unsupported state or territory" }
        require(query.minPrice == null || query.minPrice >= 0) { "Invalid minimum price" }
        require(query.maxPrice == null || query.maxPrice >= 0) { "Invalid maximum price" }
        require(query.minPrice == null || query.maxPrice == null || query.minPrice <= query.maxPrice) { "Invalid price range" }
        val normalized = query.copy(stateCode = state, city = query.city?.trim()?.takeIf { it.isNotEmpty() }, postalCode = query.postalCode?.trim()?.takeIf { it.isNotEmpty() })
        val capabilities = provider.getProviderCapabilities()
        require(normalized.scope != "Nationwide" || capabilities.nationwideSearch) { "Provider does not support nationwide search" }
        val filters = buildSet {
            if (normalized.city != null) add("city")
            if (normalized.postalCode != null) add("postalCode")
            if (normalized.minPrice != null || normalized.maxPrice != null) add("price")
            if (normalized.propertyType != null) add("propertyType")
            if (normalized.minBedrooms != null) add("bedrooms")
            if (normalized.minBathrooms != null) add("bathrooms")
        }
        require(capabilities.supportedFilters.containsAll(filters)) { "Provider does not support requested filters" }
        val response = provider.searchProperties(normalized, token)
        return response.copy(observations = response.observations.distinctBy { it.providerId to it.providerPropertyId })
    }
}
