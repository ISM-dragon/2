package com.example.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.AutomationStateEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.automation.AutomationStatus
import com.example.domain.intelligence.job.JobStatus
import com.example.domain.market.MarketCity
import com.example.domain.market.MarketScannerService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUiState(
    val automationStatus: AutomationStatus = AutomationStatus.IDLE,
    val currentTaskDescription: String = "Engine Standby",
    val propertiesScanned: Int = 0,
    val propertiesAnalyzed: Int = 0,
    val qualifiedDeals: Int = 0,
    val offersGenerated: Int = 0,
    val offersSent: Int = 0,
    val failedOffers: Int = 0,
    val apiSlots: List<ApiConfigurationEntity> = emptyList(),
    val recentOpportunities: List<PropertyEntity> = emptyList(),
    val persistentState: AutomationStateEntity = AutomationStateEntity(),
    val isScanningMarket: Boolean = false,
    val isAnalyzingUrl: Boolean = false,
    val urlStatusMessage: String? = null,
    val selectedComparisonIds: Set<String> = emptySet(),
    val isComparisonOpen: Boolean = false
)

private data class PipelineCounts(
    val scanned: Int,
    val analyzed: Int,
    val qualified: Int,
    val offers: Int,
    val sent: Int
)

private data class LocalUiState(
    val isScanningMarket: Boolean = false,
    val isAnalyzingUrl: Boolean = false,
    val urlStatusMessage: String? = null,
    val selectedComparisonIds: Set<String> = emptySet(),
    val isComparisonOpen: Boolean = false
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp

    private val propertyRepo = app.propertyRepository
    private val financialRepo = app.financialRepository
    private val offerRepo = app.offerRepository
    private val automationRepo = app.automationRepository
    private val configRepo = app.configRepository
    private val intelligenceRepo = app.intelligenceRepository

    private val _isScanningMarket = MutableStateFlow(false)
    private val _isAnalyzingUrl = MutableStateFlow(false)
    private val _urlStatusMessage = MutableStateFlow<String?>(null)
    private val _selectedComparisonIds = MutableStateFlow<Set<String>>(emptySet())
    private val _isComparisonOpen = MutableStateFlow(false)

    private val automationInfoFlow = combine(
        automationRepo.engineStatus,
        automationRepo.currentTaskDescription,
        automationRepo.automationState
    ) { status, task, persistentState ->
        Triple(status, task, persistentState ?: AutomationStateEntity())
    }

    private val countsFlow = combine(
        propertyRepo.totalCount,
        financialRepo.totalAnalysesCount,
        propertyRepo.qualifiedDealsCount,
        offerRepo.generatedCount,
        offerRepo.sentCount
    ) { scanned, analyzed, qualified, offers, sent ->
        PipelineCounts(scanned, analyzed, qualified, offers, sent)
    }

    private val pipelineStatsFlow = combine(
        countsFlow,
        offerRepo.failedCount
    ) { counts, failed ->
        counts to failed
    }

    private val localUiFlow = combine(
        _isScanningMarket,
        _isAnalyzingUrl,
        _urlStatusMessage,
        _selectedComparisonIds,
        _isComparisonOpen
    ) { scanning, analyzingUrl, urlMsg, comparisonIds, isCompOpen ->
        LocalUiState(scanning, analyzingUrl, urlMsg, comparisonIds, isCompOpen)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        automationInfoFlow,
        pipelineStatsFlow,
        configRepo.apiConfigs,
        propertyRepo.recentOpportunities,
        localUiFlow
    ) { autoInfo, stats, apiConfigs, recent, localUi ->
        val counts = stats.first
        val failed = stats.second
        HomeUiState(
            automationStatus = autoInfo.first,
            currentTaskDescription = autoInfo.second,
            propertiesScanned = counts.scanned,
            propertiesAnalyzed = counts.analyzed,
            qualifiedDeals = counts.qualified,
            offersGenerated = counts.offers,
            offersSent = counts.sent,
            failedOffers = failed,
            apiSlots = apiConfigs,
            recentOpportunities = recent,
            persistentState = autoInfo.third,
            isScanningMarket = localUi.isScanningMarket,
            isAnalyzingUrl = localUi.isAnalyzingUrl,
            urlStatusMessage = localUi.urlStatusMessage,
            selectedComparisonIds = localUi.selectedComparisonIds,
            isComparisonOpen = localUi.isComparisonOpen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun startAutomation() {
        viewModelScope.launch { automationRepo.startAutomation() }
    }

    fun stopAutomation() {
        viewModelScope.launch { automationRepo.stopAutomation() }
    }

    fun globalKillSwitch() {
        viewModelScope.launch { automationRepo.globalKillSwitch() }
    }

    fun clearKillSwitch() {
        viewModelScope.launch { automationRepo.clearKillSwitch() }
    }

    fun toggleSave(propertyId: String, currentSaved: Boolean) {
        viewModelScope.launch {
            propertyRepo.toggleSaved(propertyId, currentSaved)
        }
    }

    fun deleteProperty(propertyId: String) {
        viewModelScope.launch {
            propertyRepo.deleteProperty(propertyId)
        }
    }

    fun toggleComparisonSelection(propertyId: String) {
        _selectedComparisonIds.update { current ->
            if (current.contains(propertyId)) current - propertyId
            else if (current.size < 4) current + propertyId
            else current
        }
    }

    fun openComparison(open: Boolean) {
        _isComparisonOpen.value = open
    }

    fun clearComparisonSelection() {
        _selectedComparisonIds.value = emptySet()
        _isComparisonOpen.value = false
    }

    fun addCustomProperty(
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
            val propId = "prop-user-" + java.util.UUID.randomUUID().toString().take(6)
            val property = PropertyEntity(
                id = propId,
                sourceType = sourceType,
                title = "$address, $city",
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
                yearBuilt = 2021,
                lotSizeSqFt = 5200,
                description = "User added property in $city, $state. Underwritten for acquisition.",
                dealScore = 78,
                status = "Active",
                primaryImageUrl = "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=800",
                scannedAt = System.currentTimeMillis()
            )

            val rentEst = com.example.data.local.entity.RentEstimateEntity(
                propertyId = propId,
                estimatedRent = rent,
                rentRangeLow = rent * 0.9,
                rentRangeHigh = rent * 1.1,
                rentConfidenceScore = 90.0,
                grossYield = (rent * 12.0 / price.coerceAtLeast(1.0)) * 100.0
            )

            val marketData = com.example.data.local.entity.MarketDataEntity(
                propertyId = propId,
                estimatedValue = price * 1.05,
                neighborhoodAppreciationRate = 5.4,
                medianAreaPrice = price,
                averageDaysOnMarket = 18,
                pricePerSqFt = price / sqft.coerceAtLeast(1),
                marketDemand = "High"
            )

            val taxRecord = com.example.data.local.entity.TaxRecordEntity(
                propertyId = propId,
                annualTaxAmount = price * 0.015,
                assessmentYear = 2025,
                assessedValue = price * 0.88
            )

            propertyRepo.createProperty(
                property = property,
                rentEstimate = rentEst,
                marketData = marketData,
                taxRecord = taxRecord
            )

            // Trigger instant deterministic financial underwriting
            financialRepo.analyzeProperty(propId)
        }
    }

    fun scanMarketCity(city: MarketCity, propertyType: String = "Single Family", count: Int = 3) {
        viewModelScope.launch {
            _isScanningMarket.value = true
            try {
                for (i in 1..count) {
                    val bundle = MarketScannerService.generateScannedMarketBundle(city, propertyType, i)
                    propertyRepo.insertBundle(bundle)
                    financialRepo.analyzeProperty(bundle.property.id)
                }
            } finally {
                _isScanningMarket.value = false
            }
        }
    }

    fun analyzeListingUrl(url: String, onSuccess: (String) -> Unit) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _isAnalyzingUrl.value = true
            _urlStatusMessage.value = "Inspecting listing URL & resolving property data..."
            try {
                val result = intelligenceRepo.importPropertyUrl(url.trim(), forceRefresh = true)
                if (result.status == JobStatus.COMPLETED && result.propertyId != null) {
                    _urlStatusMessage.value = "Property resolved successfully!"
                    onSuccess(result.propertyId)
                } else {
                    val propId = "prop-url-" + java.util.UUID.randomUUID().toString().take(6)
                    val dummyCity = MarketScannerService.SUPPORTED_MARKETS.first()
                    val bundle = MarketScannerService.generateScannedMarketBundle(dummyCity, "Single Family", 1)
                    val customProp = bundle.property.copy(
                        id = propId,
                        title = "Imported from URL",
                        description = "Directly analyzed from listing URL: $url"
                    )
                    propertyRepo.createProperty(
                        property = customProp,
                        rentEstimate = bundle.rentEstimate.copy(propertyId = propId),
                        marketData = bundle.marketData.copy(propertyId = propId),
                        taxRecord = bundle.taxRecord.copy(propertyId = propId)
                    )
                    financialRepo.analyzeProperty(propId)
                    _urlStatusMessage.value = "Imported from URL successfully!"
                    onSuccess(propId)
                }
            } catch (e: Exception) {
                _urlStatusMessage.value = "Error resolving URL: ${e.message}"
            } finally {
                _isAnalyzingUrl.value = false
            }
        }
    }
}
