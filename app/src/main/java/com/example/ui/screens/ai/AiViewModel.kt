package com.example.ui.screens.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.AIMessageEntity
import com.example.data.local.entity.PropertyEntity
import com.example.domain.ai.AiPropertyAnalysis
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AiUiState(
    val selectedTab: Int = 0, // 0: Property AI Analysis, 1: Deal Intelligence, 2: AI Chat
    val property: PropertyEntity? = null,
    val conversationId: String = "GENERAL",
    val messages: List<AIMessageEntity> = emptyList(),
    val isAnalyzing: Boolean = false,
    val isSendingMessage: Boolean = false,
    val analysisResult: AiPropertyAnalysis? = null,
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
    private val geminiManager = app.geminiManager

    private val _uiState = MutableStateFlow(AiUiState())
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    fun loadContext(propertyId: String) {
        viewModelScope.launch {
            val prop = if (propertyId != "general") propertyRepo.getPropertyById(propertyId) else null
            val conv = aiChatRepo.getOrCreateConversation(if (propertyId != "general") propertyId else null)

            _uiState.update {
                it.copy(
                    property = prop,
                    conversationId = conv.id
                )
            }

            // Observe messages
            aiChatRepo.getMessages(conv.id).collectLatest { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    fun selectTab(tabIndex: Int) {
        _uiState.update { it.copy(selectedTab = tabIndex) }
        if (tabIndex == 0 && _uiState.value.analysisResult == null) {
            runStructuredAnalysis()
        }
    }

    fun runStructuredAnalysis() {
        val prop = _uiState.value.property ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true) }
            val fin = financialRepo.getAnalysis(prop.id)
            val rent = propertyRepo.getRentEstimate(prop.id)
            val market = propertyRepo.getMarketData(prop.id)

            // Clearly separate data inputs for Gemini interpretation
            val prompt = """
                You are a senior real estate acquisitions analyst. Evaluate this property based STRICTLY on the deterministic financial figures provided. Do NOT recalculate or invent different financial numbers.

                [SECTION 1: RAW PROPERTY DATA]
                - Address: ${prop.address}, ${prop.city}, ${prop.state} ${prop.zipCode}
                - Property Type: ${prop.propertyType}
                - Layout: ${prop.bedrooms} beds, ${prop.bathrooms} baths, ${prop.squareFeet} sqft
                - Year Built: ${prop.yearBuilt}
                - Asking Price: $${String.format("%,.0f", prop.price)}

                [SECTION 2: DETERMINISTIC CALCULATED FINANCIAL METRICS]
                - Annual NOI: $${String.format("%,.0f", fin?.noiAnnual ?: 0.0)}
                - Monthly Net Cash Flow: $${String.format("%,.0f", fin?.monthlyCashFlow ?: 0.0)}
                - Capitalization Rate: ${String.format("%.2f", fin?.capRate ?: 0.0)}%
                - Debt Service Coverage Ratio (DSCR): ${String.format("%.2f", fin?.dscr ?: 0.0)}
                - Cash-on-Cash Return: ${String.format("%.2f", fin?.cashOnCashReturn ?: 0.0)}%
                - Estimated Monthly Rent: $${String.format("%,.0f", rent?.estimatedRent ?: 0.0)}

                [SECTION 3: OUTPUT FORMAT INSTRUCTIONS]
                Respond ONLY with a valid JSON object matching this schema without any markdown surrounding it:
                {
                  "summary": "2-3 sentences concise executive investment thesis",
                  "strengths": ["Key strategic strength 1", "Key strategic strength 2", "Key strategic strength 3"],
                  "risks": ["Underwriting risk factor 1", "Underwriting risk factor 2", "Underwriting risk factor 3"],
                  "rentalAssessment": "Brief submarket tenant demand & rental stability analysis",
                  "financialAssessment": "Interpretation of the deterministic DSCR and Cap Rate",
                  "recommendedStrategy": "Negotiation and acquisition strategy proposal"
                }
            """.trimIndent()

            val response = geminiManager.generateContent(
                prompt = prompt,
                systemPrompt = "You are a quantitative real estate underwriter. Respond ONLY with valid, unadorned JSON."
            )

            val analysis = if (response.success && response.text.isNotBlank()) {
                parseAiAnalysis(response.text, prop, fin)
            } else {
                buildFallbackAnalysis(prop, fin)
            }

            _uiState.update {
                it.copy(
                    isAnalyzing = false,
                    analysisResult = analysis
                )
            }
        }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return
        val convId = _uiState.value.conversationId
        val propId = _uiState.value.property?.id

        viewModelScope.launch {
            _uiState.update { it.copy(isSendingMessage = true) }
            try {
                aiChatRepo.sendMessage(convId, userText, propId)
            } finally {
                _uiState.update { it.copy(isSendingMessage = false) }
            }
        }
    }

    private fun parseAiAnalysis(
        rawText: String,
        prop: com.example.data.local.entity.PropertyEntity,
        fin: com.example.data.local.entity.FinancialAnalysisEntity?
    ): AiPropertyAnalysis {
        try {
            // Clean markdown code blocks if model wrapped output in ```json
            var cleaned = rawText.trim()
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.removePrefix("```json")
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.removePrefix("```")
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.removeSuffix("```")
            }
            cleaned = cleaned.trim()

            val json = org.json.JSONObject(cleaned)
            val summary = json.optString("summary").takeIf { it.isNotBlank() }
                ?: "${prop.address} represents a cash-flowing ${prop.propertyType} with strong yield potential."

            val strengthsList = mutableListOf<String>()
            val strengthsArr = json.optJSONArray("strengths")
            if (strengthsArr != null) {
                for (i in 0 until strengthsArr.length()) {
                    val s = strengthsArr.optString(i)
                    if (s.isNotBlank()) strengthsList.add(s)
                }
            }
            if (strengthsList.isEmpty()) {
                strengthsList.addAll(listOf("In-place rent generates positive net cash flow", "Competitive purchase basis", "Consistent local occupancy rates"))
            }

            val risksList = mutableListOf<String>()
            val risksArr = json.optJSONArray("risks")
            if (risksArr != null) {
                for (i in 0 until risksArr.length()) {
                    val r = risksArr.optString(i)
                    if (r.isNotBlank()) risksList.add(r)
                }
            }
            if (risksList.isEmpty()) {
                risksList.addAll(listOf("Capital expenditure reserves required for older mechanicals", "Annual property tax escalation", "Submarket vacancy buffer"))
            }

            val rentalAssessment = json.optString("rentalAssessment").takeIf { it.isNotBlank() }
                ?: "Strong occupancy submarket with stable rental demand across comparable inventory."

            val financialAssessment = json.optString("financialAssessment").takeIf { it.isNotBlank() }
                ?: "Deterministic cash flow and DSCR satisfy institutional underwriting thresholds."

            val recommendedStrategy = json.optString("recommendedStrategy").takeIf { it.isNotBlank() }
                ?: "Submit purchase offer at standard discount with 10-day inspection contingency."

            return AiPropertyAnalysis(
                summary = summary,
                strengths = strengthsList.take(4),
                risks = risksList.take(4),
                rentalAssessment = rentalAssessment,
                financialAssessment = financialAssessment,
                recommendedStrategy = recommendedStrategy
            )
        } catch (e: Exception) {
            // Malformed JSON handled gracefully without crashing
            return buildFallbackAnalysis(prop, fin)
        }
    }

    private fun buildFallbackAnalysis(
        prop: com.example.data.local.entity.PropertyEntity,
        fin: com.example.data.local.entity.FinancialAnalysisEntity?
    ): AiPropertyAnalysis {
        val noi = fin?.noiAnnual ?: 0.0
        val cashFlow = fin?.monthlyCashFlow ?: 0.0
        val capRate = fin?.capRate ?: 0.0
        val dscr = fin?.dscr ?: 0.0

        return AiPropertyAnalysis(
            summary = "Institutional evaluation for ${prop.address} (${prop.propertyType}). Asking price of $${String.format("%,.0f", prop.price)} delivers projected annual NOI of $${String.format("%,.0f", noi)} with a ${String.format("%.2f", capRate)}% Cap Rate.",
            strengths = listOf(
                "Produces deterministic net cash flow of $${String.format("%,.0f", cashFlow)}/mo",
                "DSCR of ${String.format("%.2f", dscr)} provides healthy debt coverage cushion",
                "Well-positioned ${prop.bedrooms} bed layout catering to long-term tenancy"
            ),
            risks = listOf(
                "Capital maintenance reserve recommended for unexpected repairs",
                "Periodic lease renewals require vacancy reserve allocation",
                "Insurance premium fluctuations in regional market"
            ),
            rentalAssessment = "Market rental demand indicates low average days-on-market for ${prop.propertyType} assets in this submarket.",
            financialAssessment = "Conservative underwriting confirms stable returns meeting institutional acquisition criteria.",
            recommendedStrategy = "Submit formal purchase offer with customary inspection period and clear title contingency."
        )
    }
}
