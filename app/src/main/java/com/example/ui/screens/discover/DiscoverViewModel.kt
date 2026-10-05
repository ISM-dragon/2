package com.example.ui.screens.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.PropertyEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class DiscoverViewMode {
    LIST,
    GRID,
    MAP
}

enum class DiscoverSortOption {
    DEAL_SCORE,
    PRICE_LOW_HIGH,
    PRICE_HIGH_LOW,
    HIGHEST_CASH_FLOW,
    HIGHEST_CAP_RATE
}

data class DiscoverFilterState(
    val searchQuery: String = "",
    val sourceType: String = "ALL", // "ALL", "ON_MARKET", "OFF_MARKET"
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val propertyType: String = "ALL",
    val minBeds: Int = 0,
    val minBaths: Double = 0.0,
    val minCapRate: Double = 0.0,
    val minCashFlow: Double = 0.0,
    val minDscr: Double = 0.0,
    val sortOption: DiscoverSortOption = DiscoverSortOption.DEAL_SCORE,
    val viewMode: DiscoverViewMode = DiscoverViewMode.LIST
)

data class DiscoverUiState(
    val filter: DiscoverFilterState = DiscoverFilterState(),
    val properties: List<PropertyEntity> = emptyList(),
    val isFilterSheetOpen: Boolean = false,
    val isRefreshing: Boolean = false
)

class DiscoverViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val propertyRepo = app.propertyRepository

    private val _filterState = MutableStateFlow(DiscoverFilterState())
    private val _isFilterSheetOpen = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<DiscoverUiState> = combine(
        propertyRepo.allProperties,
        _filterState,
        _isFilterSheetOpen,
        _isRefreshing
    ) { allProps, filter, isSheetOpen, refreshing ->
        val filtered = allProps.filter { p ->
            val matchesSearch = filter.searchQuery.isBlank() ||
                    p.address.contains(filter.searchQuery, ignoreCase = true) ||
                    p.city.contains(filter.searchQuery, ignoreCase = true) ||
                    p.state.contains(filter.searchQuery, ignoreCase = true)

            val matchesSource = filter.sourceType == "ALL" || p.sourceType == filter.sourceType
            val matchesMinPrice = filter.minPrice == null || p.price >= filter.minPrice
            val matchesMaxPrice = filter.maxPrice == null || p.price <= filter.maxPrice
            val matchesType = filter.propertyType == "ALL" || p.propertyType.equals(filter.propertyType, ignoreCase = true)
            val matchesBeds = p.bedrooms >= filter.minBeds
            val matchesBaths = p.bathrooms >= filter.minBaths

            matchesSearch && matchesSource && matchesMinPrice && matchesMaxPrice && matchesType && matchesBeds && matchesBaths
        }.sortedWith { a, b ->
            when (filter.sortOption) {
                DiscoverSortOption.DEAL_SCORE -> b.dealScore.compareTo(a.dealScore)
                DiscoverSortOption.PRICE_LOW_HIGH -> a.price.compareTo(b.price)
                DiscoverSortOption.PRICE_HIGH_LOW -> b.price.compareTo(a.price)
                DiscoverSortOption.HIGHEST_CASH_FLOW -> b.dealScore.compareTo(a.dealScore)
                DiscoverSortOption.HIGHEST_CAP_RATE -> b.dealScore.compareTo(a.dealScore)
            }
        }

        DiscoverUiState(
            filter = filter,
            properties = filtered,
            isFilterSheetOpen = isSheetOpen,
            isRefreshing = refreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DiscoverUiState()
    )

    fun setSearchQuery(query: String) {
        _filterState.update { it.copy(searchQuery = query) }
    }

    fun setSourceTab(tab: String) {
        _filterState.update { it.copy(sourceType = tab) }
    }

    fun setViewMode(mode: DiscoverViewMode) {
        _filterState.update { it.copy(viewMode = mode) }
    }

    fun setSortOption(sort: DiscoverSortOption) {
        _filterState.update { it.copy(sortOption = sort) }
    }

    fun openFilterSheet(open: Boolean) {
        _isFilterSheetOpen.value = open
    }

    fun updateFilters(newFilter: DiscoverFilterState) {
        _filterState.value = newFilter
        _isFilterSheetOpen.value = false
    }

    fun resetFilters() {
        _filterState.update {
            DiscoverFilterState(viewMode = it.viewMode, sourceType = it.sourceType)
        }
        _isFilterSheetOpen.value = false
    }

    fun toggleSave(propertyId: String, currentSaved: Boolean) {
        viewModelScope.launch {
            propertyRepo.toggleSaved(propertyId, currentSaved)
        }
    }

    fun refreshFromSources() {
        viewModelScope.launch {
            _isRefreshing.value = true
            propertyRepo.syncFromSources()
            _isRefreshing.value = false
        }
    }

    fun addCustomProperty(
        title: String,
        address: String,
        city: String,
        state: String,
        zipCode: String,
        price: Double,
        propertyType: String,
        beds: Int,
        baths: Double,
        sqft: Int,
        rent: Double,
        sourceType: String = "ON_MARKET"
    ) {
        viewModelScope.launch {
            val propId = "prop-custom-" + java.util.UUID.randomUUID().toString().take(6)
            val property = PropertyEntity(
                id = propId,
                sourceType = sourceType,
                title = title.ifBlank { address },
                address = address,
                city = city,
                state = state,
                zipCode = zipCode,
                latitude = 30.26 + (Math.random() - 0.5) * 0.1,
                longitude = -97.74 + (Math.random() - 0.5) * 0.1,
                price = price,
                propertyType = propertyType,
                bedrooms = beds,
                bathrooms = baths,
                squareFeet = sqft,
                yearBuilt = 2020,
                lotSizeSqFt = 5000,
                description = "Custom property investment opportunity in $city, $state.",
                status = "Active",
                primaryImageUrl = "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=800",
                scannedAt = System.currentTimeMillis()
            )

            val rentEst = com.example.data.local.entity.RentEstimateEntity(
                propertyId = propId,
                estimatedRent = rent,
                rentRangeLow = rent * 0.9,
                rentRangeHigh = rent * 1.1,
                rentConfidenceScore = 88.0,
                grossYield = (rent * 12.0 / price) * 100.0
            )

            val marketData = com.example.data.local.entity.MarketDataEntity(
                propertyId = propId,
                estimatedValue = price * 1.05,
                neighborhoodAppreciationRate = 5.2,
                medianAreaPrice = price,
                averageDaysOnMarket = 20,
                pricePerSqFt = price / sqft.coerceAtLeast(1),
                marketDemand = "High"
            )

            propertyRepo.createProperty(
                property = property,
                rentEstimate = rentEst,
                marketData = marketData
            )
            // Immediately run deterministic underwrite
            app.financialRepository.analyzeProperty(propId)
        }
    }
}
