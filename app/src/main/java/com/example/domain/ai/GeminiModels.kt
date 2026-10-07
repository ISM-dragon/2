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

/** Safe, payload-free diagnostics for provider failures; never persist upstream response bodies. */
internal fun geminiHttpErrorMessage(statusCode: Int): String = when (statusCode) {
    401, 403 -> "Gemini rejected the configured API key or permissions (HTTP $statusCode)."
    429 -> "Gemini rate limit or quota reached (HTTP 429)."
    in 400..499 -> "Gemini rejected the request (HTTP $statusCode)."
    in 500..599 -> "Gemini service is temporarily unavailable (HTTP $statusCode)."
    else -> "Gemini request failed (HTTP $statusCode)."
}
