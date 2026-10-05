package com.example.ui.screens.property

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PropertyDetailUiState(
    val property: PropertyEntity? = null,
    val images: List<PropertyImageEntity> = emptyList(),
    val marketData: MarketDataEntity? = null,
    val rentEstimate: RentEstimateEntity? = null,
    val taxRecord: TaxRecordEntity? = null,
    val salesHistory: List<SalesHistoryEntity> = emptyList(),
    val comps: List<ComparablePropertyEntity> = emptyList(),
    val financialAnalysis: FinancialAnalysisEntity? = null,
    val isDraftingOffer: Boolean = false,
    val generatedOffer: OfferEntity? = null
)

private data class PropertyExtraData(
    val marketData: MarketDataEntity?,
    val rentEstimate: RentEstimateEntity?,
    val taxRecord: TaxRecordEntity?,
    val salesHistory: List<SalesHistoryEntity>,
    val comps: List<ComparablePropertyEntity>
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PropertyDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp

    private val propertyRepo = app.propertyRepository
    private val financialRepo = app.financialRepository
    private val offerRepo = app.offerRepository

    private val _propertyId = MutableStateFlow<String?>(null)
    private val _isDraftingOffer = MutableStateFlow(false)
    private val _generatedOffer = MutableStateFlow<OfferEntity?>(null)

    val uiState: StateFlow<PropertyDetailUiState> = _propertyId.flatMapLatest { id ->
        if (id == null) {
            flowOf(PropertyDetailUiState())
        } else {
            val extraFlow = combine(
                propertyRepo.getMarketDataFlow(id),
                propertyRepo.getRentEstimateFlow(id),
                propertyRepo.getTaxRecordFlow(id),
                propertyRepo.getSalesHistory(id),
                propertyRepo.getComps(id)
            ) { market, rent, tax, hist, comps ->
                PropertyExtraData(market, rent, tax, hist, comps)
            }

            combine(
                propertyRepo.getPropertyByIdFlow(id),
                propertyRepo.getImages(id),
                extraFlow,
                financialRepo.getAnalysisFlow(id),
                combine(_isDraftingOffer, _generatedOffer) { d, o -> Pair(d, o) }
            ) { prop, imgs, extra, fin, offerState ->
                PropertyDetailUiState(
                    property = prop,
                    images = imgs,
                    marketData = extra.marketData,
                    rentEstimate = extra.rentEstimate,
                    taxRecord = extra.taxRecord,
                    salesHistory = extra.salesHistory,
                    comps = extra.comps,
                    financialAnalysis = fin,
                    isDraftingOffer = offerState.first,
                    generatedOffer = offerState.second
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PropertyDetailUiState()
    )

    fun loadProperty(id: String) {
        _propertyId.value = id
        // Ensure analysis exists
        viewModelScope.launch {
            try {
                financialRepo.analyzeProperty(id)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun toggleSave() {
        val p = uiState.value.property ?: return
        viewModelScope.launch {
            propertyRepo.toggleSaved(p.id, p.isSaved)
        }
    }

    fun draftOffer(onSuccess: (String) -> Unit) {
        val p = uiState.value.property ?: return
        viewModelScope.launch {
            _isDraftingOffer.value = true
            try {
                val offer = offerRepo.generateOffer(
                    context = getApplication(),
                    propertyId = p.id
                )
                _generatedOffer.value = offer
                onSuccess(offer.id)
            } catch (e: Exception) {
                // Ignore
            } finally {
                _isDraftingOffer.value = false
            }
        }
    }

    fun updateProperty(
        title: String,
        address: String,
        price: Double,
        beds: Int,
        baths: Double,
        sqft: Int,
        propertyType: String
    ) {
        val current = uiState.value.property ?: return
        val updated = current.copy(
            title = title,
            address = address,
            price = price,
            bedrooms = beds,
            bathrooms = baths,
            squareFeet = sqft,
            propertyType = propertyType
        )
        viewModelScope.launch {
            propertyRepo.updateProperty(updated)
            try {
                financialRepo.analyzeProperty(current.id)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun deleteProperty(onDeleted: () -> Unit) {
        val current = uiState.value.property ?: return
        viewModelScope.launch {
            propertyRepo.deleteProperty(current.id)
            onDeleted()
        }
    }
}
