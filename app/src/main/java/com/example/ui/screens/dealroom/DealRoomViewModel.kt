package com.example.ui.screens.dealroom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.*
import com.example.domain.intelligence.engine.DeterministicFinancialEngine
import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.FinancingType
import com.example.domain.intelligence.model.InvestmentStrategy
import com.example.domain.intelligence.model.StrategyFinancialMetrics
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray

data class DealRoomUiState(
    val isLoading: Boolean = true,
    val property: PropertyEntity? = null,
    val images: List<PropertyImageEntity> = emptyList(),
    val sources: List<PropertySourceLinkEntity> = emptyList(),
    val provenance: List<PropertyProvenanceEntity> = emptyList(),
    val enrichment: PropertyEnrichmentEntity? = null,
    val comps: List<PropertyCompEntity> = emptyList(),
    val financials: PropertyFinancialEntity? = null,
    val aiAnalysis: PropertyAiAnalysisEntity? = null,
    val selectedStrategy: InvestmentStrategy = InvestmentStrategy.BUY_AND_HOLD,
    val selectedFinancing: FinancingType = FinancingType.CONVENTIONAL,
    val activeTab: String = "Overview",
    val dynamicFinancials: StrategyFinancialMetrics? = null,
    val errorMessage: String? = null
)

data class SourcesAndMedia(
    val sources: List<PropertySourceLinkEntity>,
    val provenance: List<PropertyProvenanceEntity>,
    val images: List<PropertyImageEntity>
)

data class IntelAnalysisData(
    val comps: List<PropertyCompEntity>,
    val enrichment: PropertyEnrichmentEntity?,
    val financials: PropertyFinancialEntity?,
    val aiAnalysis: PropertyAiAnalysisEntity?
)

class DealRoomViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val propertyRepo = app.propertyRepository
    private val intelligenceRepo = app.intelligenceRepository

    private val _uiState = MutableStateFlow(DealRoomUiState())
    val uiState: StateFlow<DealRoomUiState> = _uiState.asStateFlow()

    fun loadDealRoom(propertyId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val prop = propertyRepo.getPropertyById(propertyId)
            if (prop == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Property not found.") }
                return@launch
            }

            val mediaFlow = combine(
                intelligenceRepo.getSourcesForProperty(propertyId),
                intelligenceRepo.getProvenanceForProperty(propertyId),
                propertyRepo.getImages(propertyId)
            ) { sources, prov, imgs ->
                SourcesAndMedia(sources, prov, imgs)
            }

            val intelFlow = combine(
                intelligenceRepo.getCompsForProperty(propertyId),
                intelligenceRepo.getEnrichmentFlow(propertyId),
                intelligenceRepo.getFinancialsFlow(propertyId),
                intelligenceRepo.getAiAnalysisFlow(propertyId)
            ) { comps, enrich, fin, ai ->
                IntelAnalysisData(comps, enrich, fin, ai)
            }

            combine(mediaFlow, intelFlow) { media, intel ->
                val canonical = CanonicalProperty(
                    propertyId = prop.id,
                    sourceUrl = media.sources.firstOrNull()?.sourceUrl ?: "",
                    source = media.sources.firstOrNull()?.source ?: "Direct",
                    address = prop.address,
                    city = prop.city,
                    state = prop.state,
                    zipCode = prop.zipCode,
                    listPrice = prop.price,
                    bedrooms = prop.bedrooms,
                    bathrooms = prop.bathrooms,
                    squareFeet = prop.squareFeet,
                    yearBuilt = prop.yearBuilt,
                    estimatedRent = intel.financials?.monthlyRentEstimate ?: (prop.price * 0.0078)
                )

                val dynFin = DeterministicFinancialEngine.calculate(
                    canonical,
                    _uiState.value.selectedStrategy,
                    _uiState.value.selectedFinancing
                )

                DealRoomUiState(
                    isLoading = false,
                    property = prop,
                    images = media.images,
                    sources = media.sources,
                    provenance = media.provenance,
                    enrichment = intel.enrichment,
                    comps = intel.comps,
                    financials = intel.financials,
                    aiAnalysis = intel.aiAnalysis,
                    selectedStrategy = _uiState.value.selectedStrategy,
                    selectedFinancing = _uiState.value.selectedFinancing,
                    activeTab = _uiState.value.activeTab,
                    dynamicFinancials = dynFin
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun selectTab(tab: String) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setStrategy(strategy: InvestmentStrategy) {
        _uiState.update { it.copy(selectedStrategy = strategy) }
        recalculateDynamic()
    }

    fun setFinancing(financing: FinancingType) {
        _uiState.update { it.copy(selectedFinancing = financing) }
        recalculateDynamic()
    }

    private fun recalculateDynamic() {
        val prop = _uiState.value.property ?: return
        val canonical = CanonicalProperty(
            propertyId = prop.id,
            sourceUrl = "",
            source = "Direct",
            address = prop.address,
            city = prop.city,
            state = prop.state,
            zipCode = prop.zipCode,
            listPrice = prop.price,
            bedrooms = prop.bedrooms,
            bathrooms = prop.bathrooms,
            squareFeet = prop.squareFeet,
            yearBuilt = prop.yearBuilt,
            estimatedRent = _uiState.value.financials?.monthlyRentEstimate ?: (prop.price * 0.0078)
        )
        val dyn = DeterministicFinancialEngine.calculate(
            canonical,
            _uiState.value.selectedStrategy,
            _uiState.value.selectedFinancing
        )
        _uiState.update { it.copy(dynamicFinancials = dyn) }
    }
}
