package com.example.ui.screens.urlintelligence

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.urlintelligence.CanonicalPropertyMapper
import com.example.data.urlintelligence.HybridPropertyTransport
import com.example.data.urlintelligence.UrlIntelligenceModule
import com.example.urlintelligence.model.CanonicalProperty
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.resolver.PropertyImportResult
import com.example.urlintelligence.resolver.PropertyUrlResolver
import com.example.urlintelligence.resolver.ResolveOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UrlParserUiState(
    val urlInput: String = "",
    val isParsing: Boolean = false,
    val parsingStep: String = "",
    val result: CanonicalProperty? = null,
    val missingFields: Set<PropertyField> = emptySet(),
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
    val errorDetail: String? = null,
    val isRetryable: Boolean = false,
    val isPersisted: Boolean = false,
    val persistedPropertyId: String? = null,
    val forceRefresh: Boolean = false,
    val requireAccessPolicy: Boolean = false,
    val selectedDetailTab: Int = 0 // 0: Overview, 1: Pricing & Financials, 2: Specs & Building, 3: Quality & Provenance
)

class UrlIntelligenceParserViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val propertyRepo = app.propertyRepository
    private val financialRepo = app.financialRepository

    // Dedicated resolver instance using the hybrid live/fixture transport
    private val resolver: PropertyUrlResolver = UrlIntelligenceModule.createResolver(
        transport = HybridPropertyTransport()
    )

    private val _uiState = MutableStateFlow(UrlParserUiState())
    val uiState: StateFlow<UrlParserUiState> = _uiState.asStateFlow()

    fun setUrlInput(url: String) {
        _uiState.update { it.copy(urlInput = url, errorMessage = null, errorDetail = null) }
    }

    fun setForceRefresh(refresh: Boolean) {
        _uiState.update { it.copy(forceRefresh = refresh) }
    }

    fun setRequireAccessPolicy(require: Boolean) {
        _uiState.update { it.copy(requireAccessPolicy = require) }
    }

    fun setSelectedDetailTab(tab: Int) {
        _uiState.update { it.copy(selectedDetailTab = tab) }
    }

    fun selectSample(sampleUrl: String) {
        _uiState.update {
            it.copy(
                urlInput = sampleUrl,
                errorMessage = null,
                errorDetail = null,
                result = null
            )
        }
        parseUrl()
    }

    fun parseUrl() {
        val url = _uiState.value.urlInput.trim()
        if (url.isBlank()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isParsing = true,
                    parsingStep = "1/4 Validating listing URL structure...",
                    errorMessage = null,
                    errorDetail = null,
                    result = null,
                    isPersisted = false
                )
            }
            delay(150)

            _uiState.update { it.copy(parsingStep = "2/4 Detecting source portal & compliance gate...") }
            delay(150)

            _uiState.update { it.copy(parsingStep = "3/4 Ingesting HTML, JSON-LD & meta tags...") }

            val options = ResolveOptions(
                refresh = _uiState.value.forceRefresh,
                requireAccessPolicy = _uiState.value.requireAccessPolicy
            )

            try {
                val outcome = resolver.resolve(url, options)

                _uiState.update { it.copy(parsingStep = "4/4 Normalizing canonical property metadata...") }
                delay(100)

                when (outcome) {
                    is PropertyImportResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isParsing = false,
                                parsingStep = "",
                                result = outcome.property,
                                missingFields = emptySet(),
                                warnings = outcome.warnings,
                                errorMessage = null,
                                errorDetail = null
                            )
                        }
                    }

                    is PropertyImportResult.Partial -> {
                        _uiState.update {
                            it.copy(
                                isParsing = false,
                                parsingStep = "",
                                result = outcome.property,
                                missingFields = outcome.missingFields,
                                warnings = outcome.warnings,
                                errorMessage = null,
                                errorDetail = null
                            )
                        }
                    }

                    is PropertyImportResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                isParsing = false,
                                parsingStep = "",
                                result = null,
                                errorMessage = "${outcome.failure.code}: ${outcome.failure.detail}",
                                errorDetail = outcome.failure.detail,
                                isRetryable = outcome.failure.retryable
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isParsing = false,
                        parsingStep = "",
                        result = null,
                        errorMessage = "Unexpected parser exception: ${e.message}",
                        errorDetail = e.localizedMessage,
                        isRetryable = true
                    )
                }
            }
        }
    }

    fun saveToPortfolio(onSuccess: (String) -> Unit) {
        val property = _uiState.value.result ?: return
        viewModelScope.launch {
            val bundle = CanonicalPropertyMapper.toBundle(property)
            propertyRepo.createProperty(
                property = bundle.property,
                images = bundle.images,
                marketData = bundle.marketData,
                rentEstimate = bundle.rentEstimate,
                taxRecord = bundle.taxRecord
            )
            financialRepo.analyzeProperty(bundle.property.id)
            _uiState.update {
                it.copy(
                    isPersisted = true,
                    persistedPropertyId = bundle.property.id
                )
            }
            onSuccess(bundle.property.id)
        }
    }

    fun clear() {
        _uiState.update {
            UrlParserUiState()
        }
    }
}
