package com.example.ui.screens.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.AIMessageEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.ai.analyst.RealEstateAnalystInputFactory
import com.example.domain.ai.analyst.RealEstateAnalystOutput
import com.example.domain.ai.analyst.RealEstateAnalystResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AiUiState(
    val selectedTab: Int = 0, // 0: Property AI Analysis, 1: Deal Intelligence, 2: AI Chat
    val property: PropertyEntity? = null,
    val conversationId: String = "GENERAL",
    val messages: List<AIMessageEntity> = emptyList(),
    val isAnalyzing: Boolean = false,
    val isSendingMessage: Boolean = false,
    val analysisResult: RealEstateAnalystOutput? = null,
    val analysisError: String? = null,
    val quickPrompts: List<String> = listOf(
        "Analyze this property",
        "Explain the financial results",
        "Identify risks and hidden costs",
        "Compare conventional vs DSCR financing",
        "Draft an aggressive purchase offer"
    )
)

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val propertyRepo = app.propertyRepository
    private val financialRepo = app.financialRepository
    private val aiChatRepo = app.aiChatRepository
    private val realEstateAnalyst = app.realEstateAnalyst
    private val analystInputFactory = RealEstateAnalystInputFactory()
    private var analystJob: Job? = null

    private val _uiState = MutableStateFlow(AiUiState())
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    fun loadContext(propertyId: String) {
        analystJob?.cancel()
        viewModelScope.launch {
            val property = if (propertyId != "general") propertyRepo.getPropertyById(propertyId) else null
            val conversation = aiChatRepo.getOrCreateConversation(if (propertyId != "general") propertyId else null)

            _uiState.update {
                it.copy(
                    property = property,
                    conversationId = conversation.id,
                    isAnalyzing = false,
                    analysisResult = null,
                    analysisError = null
                )
            }

            if (property != null) {
                runStructuredAnalysis()
            } else {
                _uiState.update {
                    it.copy(analysisError = "Select a property to run a source-backed analysis.")
                }
            }

            // Observe only the active conversation; chat context remains separate from the analyst DTO.
            aiChatRepo.getMessages(conversation.id).collectLatest { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    fun selectTab(tabIndex: Int) {
        _uiState.update { it.copy(selectedTab = tabIndex) }
        if (tabIndex == 0 && _uiState.value.analysisResult == null && !_uiState.value.isAnalyzing) {
            runStructuredAnalysis()
        }
    }

    fun runStructuredAnalysis() {
        val property = _uiState.value.property ?: return
        if (_uiState.value.isAnalyzing) return
        _uiState.update { it.copy(isAnalyzing = true, analysisError = null) }
        analystJob = viewModelScope.launch {
            try {
                // Read only the selected property's compact records. The mapper applies a strict field
                // allow-list and caps comparable sales before anything reaches Gemini.
                val financial = financialRepo.getAnalysis(property.id)
                val rent = propertyRepo.getRentEstimate(property.id)
                val market = propertyRepo.getMarketData(property.id)
                val tax = propertyRepo.getTaxRecord(property.id)
                val comparables = propertyRepo.getCompsListForProperty(property.id)
                val input = analystInputFactory.create(
                    property = property,
                    market = market,
                    rent = rent,
                    tax = tax,
                    financial = financial,
                    comparables = comparables
                )

                when (val result = realEstateAnalyst.analyze(input)) {
                    is RealEstateAnalystResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isAnalyzing = false,
                                analysisResult = result.analysis,
                                analysisError = null
                            )
                        }
                    }
                    is RealEstateAnalystResult.Failure -> {
                        val details = result.validationErrors.take(3).joinToString(separator = " ")
                        _uiState.update {
                            it.copy(
                                isAnalyzing = false,
                                analysisResult = null,
                                analysisError = listOfNotNull(result.message, details.takeIf { it.isNotBlank() })
                                    .joinToString(" ")
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        analysisResult = null,
                        analysisError = "Source-backed analysis is unavailable: ${error.message ?: "local data could not be read"}."
                    )
                }
            }
        }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return
        val conversationId = _uiState.value.conversationId
        val propertyId = _uiState.value.property?.id

        viewModelScope.launch {
            _uiState.update { it.copy(isSendingMessage = true) }
            try {
                aiChatRepo.sendMessage(conversationId, userText, propertyId)
            } finally {
                _uiState.update { it.copy(isSendingMessage = false) }
            }
        }
    }
}
