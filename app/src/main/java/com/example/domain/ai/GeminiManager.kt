package com.example.domain.ai

import com.example.data.local.dao.ConfigDao
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.security.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal fun sanitizeGeminiError(message: String, apiKey: String): String =
    if (apiKey.isBlank()) message else message.replace(apiKey, "[REDACTED]")

class GeminiManager(
    private val configDao: ConfigDao
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getUsableConfig(): ApiConfigurationEntity? = withContext(Dispatchers.IO) {
        val configs = configDao.getAllApiConfigsList()
        val now = System.currentTimeMillis()

        for (config in configs) {
            val decryptedKey = CryptoManager.decrypt(config.apiKey)
            if (decryptedKey.isBlank() || config.status == "DISABLED") {
                continue
            }
            // Check if cooldown elapsed
            if (config.status == "COOLDOWN" && now > config.cooldownUntil) {
                configDao.updateSlotStatus(config.slotIndex, "READY", 0L, null)
                return@withContext config.copy(status = "READY", cooldownUntil = 0L)
            }
            if (config.status == "READY" || config.status == "ACTIVE") {
                return@withContext config
            }
        }
        null
    }

    suspend fun testConnection(slotIndex: Int): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val config = configDao.getApiConfigBySlot(slotIndex) ?: return@withContext Pair(false, "Slot not found")
        val rawKey = CryptoManager.decrypt(config.apiKey).trim()
        if (rawKey.isBlank()) return@withContext Pair(false, "API Key is empty")

        try {
            val testResponse = generateContentInternal(
                prompt = "Ping test. Respond with OK.",
                systemPrompt = null,
                apiKey = rawKey,
                model = if (config.model.isNotBlank()) config.model else "gemini-2.5-flash"
            )
            if (testResponse.first) {
                configDao.updateSlotStatus(slotIndex, "READY", 0L, null)
                Pair(true, "Connection successful! Engine verified.")
            } else {
                configDao.updateSlotStatus(slotIndex, "ERROR", 0L, testResponse.second)
                Pair(false, testResponse.second)
            }
        } catch (e: Exception) {
            val err = sanitizeGeminiError(e.message ?: "Connection test failed", rawKey)
            configDao.updateSlotStatus(slotIndex, "ERROR", 0L, err)
            Pair(false, err)
        }
    }

    suspend fun generateContent(prompt: String, systemPrompt: String? = null): GeminiGenerationResponse = withContext(Dispatchers.IO) {
        val configs = configDao.getAllApiConfigsList()
        val now = System.currentTimeMillis()

        // Filter valid candidates
        val eligibleConfigs = configs.filter { config ->
            val decryptedKey = CryptoManager.decrypt(config.apiKey)
            decryptedKey.isNotBlank() &&
            config.status != "DISABLED" &&
            (config.status != "COOLDOWN" || now > config.cooldownUntil)
        }

        if (eligibleConfigs.isEmpty()) {
            return@withContext GeminiGenerationResponse(
                success = false,
                text = "",
                slotUsed = -1,
                modelUsed = "None",
                errorMessage = "No usable Gemini API configurations available. Please configure API keys in Settings."
            )
        }

        var lastError: String? = null

        // Try eligible configurations with automatic failover and backoff
        for (config in eligibleConfigs) {
            val slotIndex = config.slotIndex
            val model = if (config.model.isNotBlank()) config.model else "gemini-2.5-flash"
            val apiKey = CryptoManager.decrypt(config.apiKey).trim()

            configDao.updateSlotStatus(slotIndex, "ACTIVE", 0L, null)

            // Try with up to 2 attempts for transient errors with exponential backoff
            for (attempt in 0..1) {
                try {
                    val (success, resultOrError) = generateContentInternal(prompt, systemPrompt, apiKey, model)
                    if (success) {
                        configDao.incrementSlotUsage(slotIndex, System.currentTimeMillis())
                        configDao.updateSlotStatus(slotIndex, "READY", 0L, null)
                        return@withContext GeminiGenerationResponse(
                            success = true,
                            text = resultOrError,
                            slotUsed = slotIndex,
                            modelUsed = model
                        )
                    } else {
                        lastError = resultOrError
                        if (resultOrError.contains("429") || resultOrError.contains("Quota", ignoreCase = true)) {
                            // Rate limit / Quota exceeded -> Set 60-second cooldown and failover immediately
                            val cooldownUntil = System.currentTimeMillis() + 60_000L
                            configDao.updateSlotStatus(slotIndex, "COOLDOWN", cooldownUntil, "Quota exceeded / Rate-limited")
                            configDao.incrementSlotError(slotIndex, "HTTP 429 Too Many Requests")
                            break // Stop retrying this slot, go to next slot
                        } else if (resultOrError.contains("401") || resultOrError.contains("403")) {
                            // Invalid key or forbidden -> Mark slot error immediately without retry
                            configDao.updateSlotStatus(slotIndex, "ERROR", 0L, "API key unauthorized or forbidden")
                            configDao.incrementSlotError(slotIndex, "HTTP Authorization Failure")
                            break // Go to next slot
                        } else {
                            if (attempt == 0) {
                                delay(1200) // Backoff for transient network / 5xx error
                            } else {
                                configDao.updateSlotStatus(slotIndex, "ERROR", 0L, resultOrError)
                                configDao.incrementSlotError(slotIndex, resultOrError)
                            }
                        }
                    }
                } catch (e: Exception) {
                    val msg = sanitizeGeminiError(e.message ?: "Network error", apiKey)
                    lastError = msg
                    if (attempt == 1) {
                        configDao.updateSlotStatus(slotIndex, "ERROR", 0L, msg)
                        configDao.incrementSlotError(slotIndex, msg)
                    }
                }
            }
        }

        GeminiGenerationResponse(
            success = false,
            text = "",
            slotUsed = -1,
            modelUsed = "Failover exhausted",
            errorMessage = lastError ?: "All Gemini API slots failed"
        )
    }

    private fun generateContentInternal(
        prompt: String,
        systemPrompt: String?,
        apiKey: String,
        model: String
    ): Pair<Boolean, String> {
        val requestJson = JSONObject().apply {
            val contentsArray = JSONArray()
            val userContent = JSONObject().apply {
                put("role", "user")
                val partsArray = JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                }
                put("parts", partsArray)
            }
            contentsArray.put(userContent)
            put("contents", contentsArray)

            if (!systemPrompt.isNullOrBlank()) {
                val sysInstruction = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", systemPrompt))
                    }
                    put("parts", parts)
                }
                put("systemInstruction", sysInstruction)
            }

            val genConfig = JSONObject().apply {
                put("temperature", 0.4)
            }
            put("generationConfig", genConfig)
        }

        // Pass API key via x-goog-api-key header instead of query parameter to prevent exposure in logs or URLs
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val body = requestJson.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return if (response.isSuccessful) {
            val rootJson = JSONObject(if (responseBody.isNotBlank()) responseBody else "{}")
            val candidates = rootJson.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text", "") ?: ""
            if (text.isBlank()) {
                Pair(false, "Gemini returned empty response")
            } else {
                Pair(true, text.trim())
            }
        } else {
            // Mask any key if present in error message
            val sanitized = sanitizeGeminiError(responseBody, apiKey)
            when (response.code) {
                401, 403 -> Pair(false, "HTTP ${response.code} (Unauthorized): API key is invalid or lacks permission.")
                429 -> Pair(false, "HTTP 429 (Rate Limit): Quota exhausted for slot.")
                in 500..599 -> Pair(false, "HTTP ${response.code} (Service Unavailable): Upstream Gemini service error.")
                else -> Pair(false, "HTTP ${response.code}: $sanitized")
            }
        }
    }
}
