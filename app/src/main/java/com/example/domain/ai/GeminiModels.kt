package com.example.domain.ai

data class GeminiGenerationResponse(
    val success: Boolean,
    val text: String,
    val slotUsed: Int,
    val modelUsed: String,
    val errorMessage: String? = null
)

/** Shared generation boundary; GeminiManager remains responsible for API slots and failover. */
interface GeminiContentGenerator {
    suspend fun generateContent(
        prompt: String,
        systemPrompt: String? = null,
        responseMimeType: String? = null
    ): GeminiGenerationResponse
}
