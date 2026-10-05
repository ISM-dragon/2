package com.example.domain.ai

data class GeminiGenerationResponse(
    val success: Boolean,
    val text: String,
    val slotUsed: Int,
    val modelUsed: String,
    val errorMessage: String? = null
)

data class AiPropertyAnalysis(
    val summary: String,
    val strengths: List<String>,
    val risks: List<String>,
    val rentalAssessment: String,
    val financialAssessment: String,
    val recommendedStrategy: String
)
