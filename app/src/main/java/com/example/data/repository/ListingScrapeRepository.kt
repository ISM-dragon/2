package com.example.data.repository

import com.example.data.local.entity.MarketDataEntity
import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.PropertyImageEntity
import com.example.data.local.entity.RentEstimateEntity
import com.example.data.scrape.CoroutineSleeper
import com.example.data.scrape.PortalScrapeSettingsStore
import com.example.domain.intelligence.scrape.ListingIntent
import com.example.domain.intelligence.scrape.ListingPageFetcher
import com.example.domain.intelligence.scrape.ListingPortalScraper
import com.example.domain.intelligence.scrape.ListingScrapePolicy
import com.example.domain.intelligence.scrape.ListingSearchQuery
import com.example.domain.intelligence.scrape.ListingScrapeResult
import com.example.domain.intelligence.scrape.PortalScrapeSettings
import com.example.domain.intelligence.scrape.ScrapeProgressListener
import com.example.domain.intelligence.scrape.ScrapedListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Screen state for the live portal search sheet. */
data class PortalSearchUiState(
    val location: String = "",
    val intent: ListingIntent = ListingIntent.FOR_SALE,
    val minPrice: String = "",
    val maxPrice: String = "",
    val minBeds: Int = 0,
    val minBaths: Double = 0.0,
    val isSearching: Boolean = false,
    val progressPage: Int = 0,
    val progressTotalPages: Int = 0,
    val listingsSoFar: Int = 0,
    val result: ListingScrapeResult? = null,
    val savedListingIds: Set<String> = emptySet(),
    val lastSavedListingId: String? = null
) {
    val canSearch: Boolean get() = location.isNotBlank() && !isSearching
}

/**
 * Drives live portal search from the UI and files what comes back into the app's own property store.
 *
 * Two responsibilities, kept separate on purpose:
 *  * **Search** — build the query, hand it to [ListingPortalScraper], publish progress and outcome.
 *    A failure is state, never an exception: the sheet shows the portal's answer (blocked, rate
 *    limited, layout changed) with the remediation attached.
 *  * **Save** — write a chosen listing into Room through [PropertyRepository], so it joins the same
 *    deduplication, underwriting and offer pipeline as every other property. Valuation fields are
 *    written as zeros (the portal publishes no valuation); the app's own analysis fills them in.
 */
class ListingScrapeRepository(
    private val fetcher: ListingPageFetcher,
    private val settingsStore: PortalScrapeSettingsStore,
    private val propertyRepository: PropertyRepository,
    private val financialRepository: FinancialRepository,
    private val scope: CoroutineScope
) {

    private val _uiState = MutableStateFlow(PortalSearchUiState())
    val uiState: StateFlow<PortalSearchUiState> = _uiState.asStateFlow()

    val settings: StateFlow<PortalScrapeSettings> = settingsStore.settings

    fun setLocation(location: String) = _uiState.update { it.copy(location = location) }

    fun setIntent(intent: ListingIntent) = _uiState.update { it.copy(intent = intent) }

    fun setMinPrice(value: String) = _uiState.update { it.copy(minPrice = value.filter { c -> c.isDigit() }) }

    fun setMaxPrice(value: String) = _uiState.update { it.copy(maxPrice = value.filter { c -> c.isDigit() }) }

    fun setMinBeds(beds: Int) = _uiState.update { it.copy(minBeds = beds.coerceIn(0, 20)) }

    fun setMinBaths(baths: Double) = _uiState.update { it.copy(minBaths = baths.coerceIn(0.0, 20.0)) }

    fun setConsent(consented: Boolean) = settingsStore.setConsent(consented)

    fun setRobotsAdvisory(advisory: Boolean) = settingsStore.setRobotsAdvisory(advisory)

    fun clearResult() = _uiState.update { it.copy(result = null, progressPage = 0, progressTotalPages = 0) }

    /** Runs the search for the current filter state. Safe to call while a search is running (ignored). */
    fun search() {
        val state = _uiState.value
        if (state.location.isBlank() || state.isSearching) return

        val query = ListingSearchQuery(
            location = state.location.trim(),
            intent = state.intent,
            minPrice = state.minPrice.toDoubleOrNull()?.takeIf { it > 0 },
            maxPrice = state.maxPrice.toDoubleOrNull()?.takeIf { it > 0 },
            minBeds = state.minBeds.takeIf { it > 0 },
            minBaths = state.minBaths.takeIf { it > 0 },
            maxPages = settingsStore.current().maxPagesPerSearch
        )

        _uiState.update { it.copy(isSearching = true, result = null, progressPage = 0, listingsSoFar = 0) }
        scope.launch {
            val result = runCatching { scraper().search(query) }
                .getOrElse { error ->
                    // Only genuinely unexpected conditions land here; every portal behaviour the
                    // scraper knows about is already a typed failure inside the result.
                    ListingScrapeResult(
                        query = query,
                        sourceId = SOURCE_ID,
                        searchUrl = "",
                        failure = com.example.domain.intelligence.scrape.ListingScrapeFailure(
                            kind = com.example.domain.intelligence.scrape.ListingScrapeFailureKind.NETWORK_ERROR,
                            message = "The search could not be completed.",
                            remediation = "Try again. If it keeps happening, the portal is unreachable from this network.",
                            detail = error.javaClass.simpleName
                        )
                    )
                }
            _uiState.update {
                it.copy(
                    isSearching = false,
                    result = result,
                    listingsSoFar = result.listingCount,
                    progressPage = result.fetchedPages,
                    progressTotalPages = result.fetchedPages
                )
            }
        }
    }

    /**
     * Files one scraped listing into the app's property store.
     *
     * Identity is the portal's listing id, so re-saving the same home updates one row instead of
     * creating a second deal.
     */
    fun saveListing(listing: ScrapedListing) {
        scope.launch {
            val id = propertyIdFor(listing)
            val now = System.currentTimeMillis()
            val images = listing.photos.take(MAX_IMAGES).mapIndexed { index, url ->
                PropertyImageEntity(
                    propertyId = id,
                    imageUrl = url,
                    caption = if (index == 0) "Primary" else "Listing photo ${index + 1}",
                    isPrimary = index == 0
                )
            }
            val entity = PropertyEntity(
                id = id,
                sourceType = sourceTypeFor(listing),
                title = listing.displayName,
                address = listing.street ?: listing.address.orEmpty(),
                city = listing.city.orEmpty(),
                state = listing.state.orEmpty(),
                zipCode = listing.zipCode.orEmpty(),
                latitude = listing.latitude ?: 0.0,
                longitude = listing.longitude ?: 0.0,
                price = listing.price ?: 0.0,
                propertyType = listing.propertyType ?: "Single Family",
                bedrooms = listing.bedrooms ?: 0,
                bathrooms = listing.bathrooms ?: 0.0,
                squareFeet = listing.livingAreaSqFt ?: 0,
                yearBuilt = listing.yearBuilt ?: 0,
                lotSizeSqFt = listing.lotSizeSqFt?.toInt() ?: 0,
                description = descriptionFor(listing),
                status = statusFor(listing),
                primaryImageUrl = listing.photos.firstOrNull().orEmpty(),
                scannedAt = now,
                mlsNumber = listing.mlsId.orEmpty(),
                lastVerifiedAt = now
            )

            runCatching {
                propertyRepository.createProperty(
                    property = entity,
                    images = images,
                    // Placeholders: a portal card carries no valuation, and inventing one here would
                    // leak into the underwriting screen as if it were data.
                    marketData = MarketDataEntity(
                        propertyId = id,
                        estimatedValue = 0.0,
                        neighborhoodAppreciationRate = 0.0,
                        medianAreaPrice = 0.0,
                        averageDaysOnMarket = listing.daysOnMarket ?: 0,
                        pricePerSqFt = listing.price?.takeIf { (listing.livingAreaSqFt ?: 0) > 0 }
                            ?.div((listing.livingAreaSqFt ?: 1).toDouble()) ?: 0.0,
                        marketDemand = "Unknown"
                    ),
                    rentEstimate = RentEstimateEntity(
                        propertyId = id,
                        estimatedRent = 0.0,
                        rentRangeLow = 0.0,
                        rentRangeHigh = 0.0,
                        rentConfidenceScore = 0.0,
                        grossYield = 0.0
                    )
                )
                financialRepository.analyzeProperty(id)
                _uiState.update { it.copy(savedListingIds = it.savedListingIds + id, lastSavedListingId = id) }
            }
        }
    }

    private fun scraper() = ListingPortalScraper(
        fetcher = fetcher,
        policy = ListingScrapePolicy { settingsStore.current() },
        sleeper = CoroutineSleeper,
        onProgress = ScrapeProgressListener { page, totalPages, listingsSoFar ->
            _uiState.update {
                it.copy(progressPage = page, progressTotalPages = totalPages, listingsSoFar = listingsSoFar)
            }
        }
    )

    private fun propertyIdFor(listing: ScrapedListing): String =
        listing.externalId?.takeIf { it.isNotBlank() }?.let { "$ID_PREFIX$it" }
            ?: "$ID_PREFIX${kotlin.math.abs(listing.detailUrl.hashCode()).toString(36)}"

    /** Portal search only returns on-market inventory today; the vocabulary is kept for the future. */
    private fun sourceTypeFor(@Suppress("UNUSED_PARAMETER") listing: ScrapedListing): String = SOURCE_TYPE_ON_MARKET

    private fun statusFor(listing: ScrapedListing): String = when {
        listing.status?.contains("RENT", ignoreCase = true) == true -> "For Rent"
        listing.status?.contains("PENDING", ignoreCase = true) == true -> "Pending"
        listing.status?.contains("SOLD", ignoreCase = true) == true -> "Sold"
        listing.status?.contains("AUCTION", ignoreCase = true) == true -> "Auction"
        else -> "Active"
    }

    private fun descriptionFor(listing: ScrapedListing): String = buildString {
        append(listing.statusText ?: listing.status ?: "Listed")
        append(" — imported from the portal search")
        listing.mlsId?.takeIf { it.isNotBlank() }?.let { append(" · MLS# $it") }
        listing.listingOfficeName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        listing.listingAgentName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        listing.openHouse?.takeIf { it.isNotBlank() }?.let { append(" · Open house: $it") }
        append('.')
    }

    private companion object {
        const val SOURCE_ID = "zillow"
        const val ID_PREFIX = "prop-zil-"
        const val MAX_IMAGES = 25
        const val SOURCE_TYPE_ON_MARKET = "ON_MARKET"
    }
}
