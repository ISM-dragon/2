package com.example.domain.ai

import com.example.data.local.dao.ConfigDao
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.security.CryptoManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.TimeUnit

class GeminiManager(
    private val configDao: ConfigDao
) : GeminiContentGenerator {
    // Provider credentials and prompts must never be replayed to redirect targets or cached in
    // cookies. Disabling retries also keeps generated requests from being duplicated implicitly.
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getUsableConfig(): ApiConfigurationEntity? = withContext(Dispatchers.IO) {
        val configs = configDao.getAllApiConfigsList()
        val now = System.currentTimeMillis()

        for (config in configs) {
            if (config.status == "DISABLED" || config.apiKey.isBlank()) continue
            val decryptedKey = CryptoManager.decryptOrNull(config.apiKey)
            if (decryptedKey == null) {
                configDao.updateSlotStatus(
                    config.slotIndex,
                    "ERROR",
                    0L,
                    API_KEY_DECRYPTION_ERROR
                )
                continue
            }
            if (decryptedKey.isBlank()) continue

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
        val rawKey = CryptoManager.decryptOrNull(config.apiKey)?.trim()
        if (rawKey == null) {
            configDao.updateSlotStatus(slotIndex, "ERROR", 0L, API_KEY_DECRYPTION_ERROR)
            return@withContext Pair(false, API_KEY_DECRYPTION_ERROR)
        }
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val safeError = "Gemini connection test failed. Check the key and network connection."
            configDao.updateSlotStatus(slotIndex, "ERROR", 0L, safeError)
            Pair(false, safeError)
        }
    }

    override suspend fun generateContent(
        prompt: String,
        systemPrompt: String?,
        responseMimeType: String?
    ): GeminiGenerationResponse = withContext(Dispatchers.IO) {
        val configs = configDao.getAllApiConfigsList()
        val now = System.currentTimeMillis()

        // Decrypt each credential once. Corrupt ciphertext is explicitly marked unusable rather
        // than being interpreted as a valid empty key or passed to the provider.
        val eligibleConfigs = configs.mapNotNull { config ->
            if (config.status == "DISABLED" || config.apiKey.isBlank()) return@mapNotNull null
            val apiKey = CryptoManager.decryptOrNull(config.apiKey)
            if (apiKey == null) {
                configDao.updateSlotStatus(
                    config.slotIndex,
                    "ERROR",
                    0L,
                    API_KEY_DECRYPTION_ERROR
                )
                return@mapNotNull null
            }
            if (
                apiKey.isBlank() ||
                (config.status == "COOLDOWN" && now <= config.cooldownUntil) ||
                config.status !in setOf("READY", "ACTIVE", "COOLDOWN")
            ) {
                return@mapNotNull null
            }
            config to apiKey
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
        for ((config, storedPlaintextKey) in eligibleConfigs) {
            val slotIndex = config.slotIndex
            val model = if (config.model.isNotBlank()) config.model else "gemini-2.5-flash"
            val apiKey = storedPlaintextKey.trim()

            configDao.updateSlotStatus(slotIndex, "ACTIVE", 0L, null)

            // Try with up to 2 attempts for transient errors with exponential backoff
            for (attempt in 0..1) {
                try {
                    val (success, resultOrError) = generateContentInternal(prompt, systemPrompt, apiKey, model, responseMimeType)
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val safeError = "Gemini request failed. Check the key and network connection."
                    lastError = safeError
                    if (attempt == 1) {
                        configDao.updateSlotStatus(slotIndex, "ERROR", 0L, safeError)
                        configDao.incrementSlotError(slotIndex, safeError)
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
        model: String,
        responseMimeType: String? = null
    ): Pair<Boolean, String> {
        if (!SAFE_MODEL_NAME.matches(model)) {
            return Pair(false, "Gemini model configuration is invalid.")
        }
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
                responseMimeType?.takeIf { it.isNotBlank() }?.let { put("responseMimeType", it) }
            }
            put("generationConfig", genConfig)
        }

        // Pass API key via x-goog-api-key header instead of query parameter to prevent exposure in logs or URLs
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        if (!isTrustedGeminiEndpoint(url, model)) {
            return Pair(false, "Gemini endpoint validation failed.")
        }
        val body = requestJson.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Never persist or return provider error bodies: they are untrusted and could echo
                // keys, request details, or user/property data.
                Pair(false, geminiHttpErrorMessage(response.code))
            } else {
                val responseBody = response.body?.string().orEmpty()
                val rootJson = JSONObject(if (responseBody.isNotBlank()) responseBody else "{}")
                val candidates = rootJson.optJSONArray("candidates")
                val candidate = candidates?.optJSONObject(0)
                val content = candidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val text = parts?.optJSONObject(0)?.optString("text", "") ?: ""
                if (text.isBlank()) {
                    Pair(false, "Gemini returned an empty response.")
                } else {
                    Pair(true, text.trim())
                }
            }
        }
    }

    private fun isTrustedGeminiEndpoint(value: String, model: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" &&
            url.host == GEMINI_API_HOST &&
            url.port == 443 &&
            url.encodedPath == "/v1beta/models/$model:generateContent" &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.encodedQuery == null &&
            url.fragment == null
    }

    private companion object {
        val SAFE_MODEL_NAME = Regex("[A-Za-z0-9._-]{1,100}")
        const val GEMINI_API_HOST = "generativelanguage.googleapis.com"
        const val API_KEY_DECRYPTION_ERROR = "Stored API key could not be decrypted. Re-enter the key."
    }
}
