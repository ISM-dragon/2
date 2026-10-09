package com.example.domain.gmail

import android.content.Context
import android.util.Base64
import com.example.data.local.entity.GmailAuthStatus
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.repository.ConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

interface GmailSender {
    suspend fun sendOfferEmail(
        context: Context,
        recipientEmail: String,
        recipientName: String,
        subject: String,
        htmlBody: String,
        pdfFile: File?,
        idempotencyKey: String? = null
    ): GmailSendResult
}

enum class GmailFailureKind {
    VALIDATION,
    AUTHENTICATION,
    RETRYABLE_REJECTED,
    PERMANENT_REJECTED,
    DELIVERY_UNKNOWN
}

data class GmailSendResult(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null,
    val failureKind: GmailFailureKind? = null,
    val httpStatusCode: Int? = null,
    val retryAfterMillis: Long? = null
)

class GmailService(
    private val configRepository: ConfigRepository,
    httpClient: OkHttpClient = defaultHttpClient(),
    private val gmailSendUrl: String = GMAIL_SEND_URL,
    private val oauthTokenUrl: String = OAUTH_TOKEN_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val oauthClientIdProvider: () -> String? = { configRepository.getOAuthClientId() }
) : GmailSender {
    /** Credentials are sent only to fixed HTTPS endpoints, with redirects, cookies, proxies and
     * automatic authenticators disabled even if a caller injects a permissive client. */
    private val safeHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * A single-flight lock shared by proactive refreshes, 401 recovery, and the Settings action.
     * After acquiring it, a caller re-reads storage and uses a token another caller just refreshed.
     */
    private val tokenRefreshMutex = Mutex()
    private var failedRefreshFingerprint: String? = null
    private var failedRefreshAt: Long = 0L

    suspend fun getConfiguration(): GmailConfigurationEntity = configRepository.getGmailConfig()

    override suspend fun sendOfferEmail(
        context: Context,
        recipientEmail: String,
        recipientName: String,
        subject: String,
        htmlBody: String,
        pdfFile: File?,
        idempotencyKey: String?
    ): GmailSendResult = withContext(Dispatchers.IO) {
        val cleanEmail = recipientEmail.trim()
        if (!GmailMimeBuilder.isSafeEmail(cleanEmail)) {
            return@withContext validationFailure("Invalid recipient email address.")
        }
        if (subject.isBlank() || GmailMimeBuilder.containsHeaderControls(subject)) {
            return@withContext validationFailure("Email subject is empty or contains invalid header characters.")
        }
        if (GmailMimeBuilder.containsHeaderControls(recipientName)) {
            return@withContext validationFailure("Recipient name contains invalid header characters.")
        }
        if (htmlBody.isBlank()) {
            return@withContext validationFailure("Email content body cannot be empty.")
        }
        if (pdfFile == null || !GmailMimeBuilder.isApprovedOfferPdf(context, pdfFile)) {
            return@withContext validationFailure("Required offer PDF is missing, invalid, or outside the private offers directory.")
        }
        if (pdfFile.length() > GmailMimeBuilder.MAX_ATTACHMENT_BYTES) {
            return@withContext validationFailure("Offer PDF exceeds the supported 20 MB attachment limit.")
        }

        val config = getConfiguration()
        if (!config.isConnected || config.accessToken.isNullOrBlank()) {
            val error = "Gmail OAuth is not configured. Email was not sent."
            configRepository.updateGmailAuthStatus(GmailAuthStatus.AUTH_REQUIRED, error)
            return@withContext GmailSendResult(
                success = false,
                error = error,
                failureKind = GmailFailureKind.AUTHENTICATION
            )
        }

        val sendKey = idempotencyKey?.takeIf { it.isNotBlank() }
            ?: fallbackIdempotencyKey(cleanEmail, subject, pdfFile)
        val pdfBytes = try {
            pdfFile.readBytes()
        } catch (_: IOException) {
            return@withContext validationFailure("Offer PDF could not be read from local storage.")
        }
        if (pdfBytes.isEmpty() || pdfBytes.size.toLong() > GmailMimeBuilder.MAX_ATTACHMENT_BYTES) {
            return@withContext validationFailure("Offer PDF is empty or exceeds the supported attachment limit.")
        }

        var activeConfig = getUsableConfiguration(config)
            ?: return@withContext GmailSendResult(
                success = false,
                error = "Gmail authorization expired. Please re-authorize in Settings.",
                failureKind = GmailFailureKind.AUTHENTICATION
            )

        val mimeMessage = try {
            GmailMimeBuilder.build(
                config = activeConfig,
                recipientEmail = cleanEmail,
                recipientName = recipientName,
                subject = subject,
                htmlBody = htmlBody,
                pdfBytes = pdfBytes,
                idempotencyKey = sendKey,
                nowMillis = clock()
            )
        } catch (_: IllegalArgumentException) {
            return@withContext validationFailure("Email headers are invalid.")
        }

        configRepository.updateGmailAuthStatus(GmailAuthStatus.SENDING, null)
        var result = transmitViaGmailApi(activeConfig, mimeMessage, sendKey)

        // A 401 is a definite rejection, so one refresh-and-resend is safe. Do not retry network
        // errors, 5xx responses, or successful responses missing a message id: those are ambiguous.
        if (result.httpStatusCode == 401) {
            configRepository.updateGmailAuthStatus(GmailAuthStatus.AUTH_EXPIRED, "Gmail rejected the access token.")
            val refreshed = refreshAccessToken(activeConfig)
            if (refreshed == null || refreshed.accessToken.isNullOrBlank()) {
                val error = "Gmail authorization expired. Please re-authorize in Settings."
                configRepository.updateGmailAuthStatus(GmailAuthStatus.AUTH_REQUIRED, error)
                return@withContext GmailSendResult(
                    success = false,
                    error = error,
                    failureKind = GmailFailureKind.AUTHENTICATION,
                    httpStatusCode = 401
                )
            }
            activeConfig = refreshed
            configRepository.updateGmailAuthStatus(GmailAuthStatus.SENDING, null)
            result = transmitViaGmailApi(activeConfig, mimeMessage, sendKey)
            if (result.httpStatusCode == 401) {
                configRepository.updateGmailAuthStatus(
                    GmailAuthStatus.AUTH_REQUIRED,
                    "Gmail rejected the refreshed access token. Re-authorize the account."
                )
                return@withContext result.copy(
                    error = "Gmail rejected the refreshed access token. Re-authorize the account.",
                    failureKind = GmailFailureKind.AUTHENTICATION
                )
            }
        }

        if (result.success) {
            configRepository.updateGmailAuthStatus(GmailAuthStatus.SENT, null)
        } else {
            val status = if (result.failureKind == GmailFailureKind.AUTHENTICATION) {
                GmailAuthStatus.AUTH_REQUIRED
            } else {
                GmailAuthStatus.FAILED
            }
            configRepository.updateGmailAuthStatus(status, result.error)
        }
        result
    }

    /** Refreshes once for all concurrent callers and safely reuses the new stored token. */
    suspend fun refreshAccessToken(config: GmailConfigurationEntity): GmailConfigurationEntity? =
        withContext(Dispatchers.IO) {
            tokenRefreshMutex.withLock {
                val latest = getConfiguration()
                val tokenWasRefreshedByAnotherCaller =
                    latest.accessToken != config.accessToken &&
                        !latest.accessToken.isNullOrBlank() &&
                        latest.isConnected &&
                        latest.expiresAt > clock()
                if (tokenWasRefreshedByAnotherCaller) return@withLock latest
                if (latest.refreshToken.isNullOrBlank()) return@withLock null

                val fingerprint = refreshFingerprint(latest)
                val now = clock()
                if (
                    failedRefreshFingerprint == fingerprint &&
                    now - failedRefreshAt in 0L..FAILED_REFRESH_SINGLE_FLIGHT_WINDOW_MILLIS
                ) return@withLock null

                // Storage is authoritative; do not use a stale UI/caller snapshot after disconnect.
                val refreshed = refreshAccessTokenLocked(latest)
                if (refreshed == null) {
                    failedRefreshFingerprint = fingerprint
                    failedRefreshAt = clock()
                } else {
                    failedRefreshFingerprint = null
                    failedRefreshAt = 0L
                }
                refreshed
            }
        }

    private suspend fun getUsableConfiguration(initial: GmailConfigurationEntity): GmailConfigurationEntity? {
        if (!isExpiringSoon(initial)) return initial
        val refreshed = refreshAccessToken(initial)
        if (refreshed == null || refreshed.accessToken.isNullOrBlank()) return null
        return refreshed
    }

    private fun isExpiringSoon(config: GmailConfigurationEntity): Boolean =
        config.expiresAt > 0L && config.expiresAt <= clock() + TOKEN_REFRESH_SKEW_MILLIS

    private suspend fun refreshAccessTokenLocked(config: GmailConfigurationEntity): GmailConfigurationEntity? {
        val refreshToken = config.refreshToken
        if (!isTrustedGoogleEndpoint(oauthTokenUrl, OAUTH_TOKEN_HOST, OAUTH_TOKEN_PATH)) {
            configRepository.updateGmailAuthStatus(
                GmailAuthStatus.FAILED,
                "Google OAuth token endpoint configuration is invalid."
            )
            return null
        }
        if (refreshToken.isNullOrBlank()) {
            configRepository.updateGmailAuthStatus(
                GmailAuthStatus.AUTH_EXPIRED,
                "Refresh token is missing. Please authorize Gmail account in Settings."
            )
            return null
        }

        val clientId = oauthClientIdProvider()
        if (clientId.isNullOrBlank()) {
            configRepository.updateGmailAuthStatus(
                GmailAuthStatus.FAILED,
                "OAuth Client ID is not configured. Google OAuth token refresh cannot proceed without a valid Client ID."
            )
            return null
        }

        return try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build()
            val request = Request.Builder()
                .url(oauthTokenUrl)
                .post(body)
                .build()

            safeHttpClient.newCall(request).execute().use { response ->
                val responseText = if (response.isSuccessful) response.body?.string().orEmpty() else ""
                if (!response.isSuccessful) {
                    // Do not persist Google's raw response body: OAuth responses can echo credentials.
                    configRepository.updateGmailAuthStatus(
                        GmailAuthStatus.FAILED,
                        "Google OAuth token refresh failed (HTTP ${response.code})."
                    )
                    null
                } else {
                    val json = try {
                        JSONObject(responseText)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    val newAccessToken = json.optString("access_token", "").trim()
                    if (newAccessToken.isBlank()) {
                        configRepository.updateGmailAuthStatus(
                            GmailAuthStatus.FAILED,
                            "Google OAuth token refresh response did not contain an access token."
                        )
                        null
                    } else {
                        val expiresInSeconds = json.optLong("expires_in", 3600L).coerceAtLeast(1L)
                        val newExpiresAt = clock() + expiresInSeconds.coerceAtMost(MAX_TOKEN_LIFETIME_SECONDS) * 1000L
                        val rotatedRefreshToken = json.optString("refresh_token", "")
                            .takeIf { it.isNotBlank() }
                        val persisted = configRepository.updateGmailTokensIfUnchanged(
                            expectedAccessToken = config.accessToken,
                            expectedRefreshToken = refreshToken,
                            newAccessToken = newAccessToken,
                            newRefreshToken = rotatedRefreshToken,
                            expiresAt = newExpiresAt,
                            authStatus = GmailAuthStatus.AUTH_REQUIRED,
                            lastError = null
                        )
                        val latest = getConfiguration()
                        if (persisted) {
                            latest
                        } else if (
                            latest.isConnected &&
                            !latest.accessToken.isNullOrBlank() &&
                            latest.accessToken != config.accessToken &&
                            latest.expiresAt > clock()
                        ) {
                            // Another repository/service won the compare-and-set refresh race.
                            latest
                        } else {
                            null
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            configRepository.updateGmailAuthStatus(
                GmailAuthStatus.FAILED,
                "Google OAuth token refresh could not reach the token service."
            )
            null
        }
    }

    private fun transmitViaGmailApi(
        config: GmailConfigurationEntity,
        mimeMessage: ByteArray,
        idempotencyKey: String
    ): GmailSendResult {
        if (!isTrustedGoogleEndpoint(gmailSendUrl, GMAIL_SEND_HOST, GMAIL_SEND_PATH)) {
            return GmailSendResult(
                success = false,
                error = "Gmail send endpoint configuration is invalid.",
                failureKind = GmailFailureKind.VALIDATION
            )
        }
        val jsonPayload = JSONObject().put(
            "raw",
            Base64.encodeToString(
                mimeMessage,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        )
        val requestBody = jsonPayload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(gmailSendUrl)
            .header("Authorization", "Bearer ${config.accessToken}")
            .header("Accept", "application/json")
            // Gmail currently ignores this as an idempotency contract. It is a stable correlation
            // key only; the persisted local ledger is what prevents automatic duplicate sends.
            .header("Idempotency-Key", sha256Hex(idempotencyKey))
            .post(requestBody)
            .build()

        return try {
            safeHttpClient.newCall(request).execute().use { response ->
                val responseText = if (response.isSuccessful) response.body?.string().orEmpty() else ""
                if (response.isSuccessful) {
                    val messageId = try {
                        JSONObject(responseText).optString("id", "").trim()
                    } catch (_: Exception) {
                        ""
                    }
                    if (!isValidMessageId(messageId)) {
                        GmailSendResult(
                            success = false,
                            error = "Gmail accepted the request but did not confirm a message id; delivery outcome is unknown.",
                            failureKind = GmailFailureKind.DELIVERY_UNKNOWN,
                            httpStatusCode = response.code
                        )
                    } else {
                        GmailSendResult(success = true, messageId = messageId, httpStatusCode = response.code)
                    }
                } else {
                    val failureKind = when {
                        response.code == 401 -> GmailFailureKind.AUTHENTICATION
                        response.code == 429 -> GmailFailureKind.RETRYABLE_REJECTED
                        response.code in 400..499 && response.code != 408 -> GmailFailureKind.PERMANENT_REJECTED
                        else -> GmailFailureKind.DELIVERY_UNKNOWN
                    }
                    GmailSendResult(
                        success = false,
                        error = when (failureKind) {
                            GmailFailureKind.AUTHENTICATION -> "Gmail rejected the access token (HTTP 401)."
                            GmailFailureKind.RETRYABLE_REJECTED -> "Gmail rate-limited the send (HTTP 429)."
                            GmailFailureKind.PERMANENT_REJECTED -> "Gmail rejected the message (HTTP ${response.code})."
                            else -> "Gmail send outcome is unknown (HTTP ${response.code}); automatic retry is disabled to prevent duplicates."
                        },
                        failureKind = failureKind,
                        httpStatusCode = response.code,
                        retryAfterMillis = parseRetryAfter(response.header("Retry-After"), clock())
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A timeout/I/O failure can happen after Gmail accepted the message. Never blindly retry.
            GmailSendResult(
                success = false,
                error = "Network failure while sending; delivery outcome is unknown and automatic retry is disabled to prevent duplicates.",
                failureKind = GmailFailureKind.DELIVERY_UNKNOWN
            )
        }
    }

    private fun validationFailure(message: String) = GmailSendResult(
        success = false,
        error = message,
        failureKind = GmailFailureKind.VALIDATION
    )

    private fun fallbackIdempotencyKey(recipient: String, subject: String, pdfFile: File): String =
        "gmail-direct-${sha256Hex("${pdfFile.absolutePath}|$recipient|$subject")}"
    private fun refreshFingerprint(config: GmailConfigurationEntity): String =
        sha256Hex("${config.accessToken.orEmpty()}|${config.refreshToken.orEmpty()}|${config.expiresAt}")

    private fun isValidMessageId(value: String): Boolean =
        value.isNotBlank() &&
            !value.equals("null", ignoreCase = true) &&
            value.length <= 512 &&
            value.matches(Regex("[A-Za-z0-9._@+-]+"))

    private fun isTrustedGoogleEndpoint(value: String, expectedHost: String, expectedPath: String): Boolean {
        val url: HttpUrl = value.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" &&
            url.host == expectedHost &&
            url.port == 443 &&
            url.encodedPath == expectedPath &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.encodedQuery == null &&
            url.fragment == null
    }

    private fun parseRetryAfter(value: String?, now: Long): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { seconds ->
            return seconds.coerceAtLeast(0L).coerceAtMost(MAX_RETRY_AFTER_MILLIS / 1000L) * 1000L
        }
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            (format.parse(value)?.time?.minus(now))
                ?.coerceAtLeast(0L)
                ?.coerceAtMost(MAX_RETRY_AFTER_MILLIS)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val GMAIL_SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
        private const val GMAIL_SEND_HOST = "gmail.googleapis.com"
        private const val GMAIL_SEND_PATH = "/gmail/v1/users/me/messages/send"
        private const val OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val OAUTH_TOKEN_HOST = "oauth2.googleapis.com"
        private const val OAUTH_TOKEN_PATH = "/token"
        private const val TOKEN_REFRESH_SKEW_MILLIS = 60_000L
        private const val FAILED_REFRESH_SINGLE_FLIGHT_WINDOW_MILLIS = 5_000L
        private const val MAX_TOKEN_LIFETIME_SECONDS = 86_400L
        private const val MAX_RETRY_AFTER_MILLIS = 6 * 60 * 60 * 1000L

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
