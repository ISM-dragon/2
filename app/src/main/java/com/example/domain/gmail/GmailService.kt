package com.example.domain.gmail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Patterns
import androidx.core.content.FileProvider
import com.example.data.local.entity.GmailAuthStatus
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.repository.ConfigRepository
import com.example.domain.pdf.OfferPdfStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class GmailSendResult(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
)

internal fun sanitizeMimeHeader(value: String): String = buildString(value.length) {
    var previousWasControl = false
    value.forEach { character ->
        val isControl = character.code < 0x20 || character.code == 0x7f
        if (isControl) {
            if (!previousWasControl) append(' ')
        } else {
            append(character)
        }
        previousWasControl = isControl
    }
}.trim()

class GmailService(
    private val configRepository: ConfigRepository
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getConfiguration(): GmailConfigurationEntity {
        // Reads via ConfigRepository which decrypts access & refresh tokens
        return configRepository.getGmailConfig()
    }

    suspend fun sendOfferEmail(
        context: Context,
        recipientEmail: String,
        recipientName: String,
        subject: String,
        htmlBody: String,
        pdfFile: File?
    ): GmailSendResult = withContext(Dispatchers.IO) {
        // 1. Validate recipient email
        val cleanEmail = recipientEmail.trim()
        if (cleanEmail.isBlank()) {
            return@withContext GmailSendResult(success = false, error = "Recipient email is blank.")
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            return@withContext GmailSendResult(success = false, error = "Invalid recipient email address format: $cleanEmail")
        }

        // 2. Validate subject and body
        if (subject.isBlank()) {
            return@withContext GmailSendResult(success = false, error = "Email subject cannot be empty.")
        }
        if (htmlBody.isBlank()) {
            return@withContext GmailSendResult(success = false, error = "Email content body cannot be empty.")
        }

        // 3. Validate the attachment is a regular PDF inside the private offers directory.
        val safePdfFile = OfferPdfStorage.resolveExistingPdf(context, pdfFile?.path)
        if (safePdfFile == null || safePdfFile.length() == 0L) {
            return@withContext GmailSendResult(
                success = false,
                error = "Required offer PDF attachment is missing, empty, or outside private offer storage."
            )
        }

        // 4. Verify account authorization via single source of truth (ConfigRepository)
        var config = getConfiguration()
        if (!config.isConnected || config.accessToken.isNullOrBlank()) {
            configRepository.updateGmailAuthStatus(
                GmailAuthStatus.AUTH_REQUIRED,
                "Gmail OAuth is not configured. Email was not sent."
            )
            return@withContext GmailSendResult(
                success = false,
                error = "Gmail OAuth is not configured. Email was not sent."
            )
        }

        // Check token expiration before sending
        if (config.expiresAt > 0L && System.currentTimeMillis() > config.expiresAt) {
            configRepository.updateGmailAuthStatus(GmailAuthStatus.AUTH_EXPIRED, "OAuth access token expired")
            val refreshed = refreshAccessToken(config)
            if (refreshed != null && !refreshed.accessToken.isNullOrBlank()) {
                config = refreshed
            } else {
                return@withContext GmailSendResult(
                    success = false,
                    error = "Gmail authorization expired. Please re-authorize in Settings."
                )
            }
        }

        // Update status to SENDING
        configRepository.updateGmailAuthStatus(GmailAuthStatus.SENDING, null)

        val result = transmitViaGmailApi(config, cleanEmail, recipientName, subject, htmlBody, safePdfFile)
        if (!result.success && result.error?.contains("401") == true && !config.refreshToken.isNullOrBlank()) {
            // Attempt one token refresh on 401 Unauthorized
            configRepository.updateGmailAuthStatus(GmailAuthStatus.AUTH_EXPIRED, "HTTP 401 Unauthorized received from Gmail API")
            val refreshed = refreshAccessToken(config)
            if (refreshed != null && !refreshed.accessToken.isNullOrBlank()) {
                config = refreshed
                configRepository.updateGmailAuthStatus(GmailAuthStatus.SENDING, null)
                val retryResult = transmitViaGmailApi(config, cleanEmail, recipientName, subject, htmlBody, safePdfFile)
                if (retryResult.success) {
                    configRepository.updateGmailAuthStatus(GmailAuthStatus.SENT, null)
                } else {
                    configRepository.updateGmailAuthStatus(GmailAuthStatus.FAILED, retryResult.error)
                }
                return@withContext retryResult
            }
        }

        if (result.success) {
            configRepository.updateGmailAuthStatus(GmailAuthStatus.SENT, null)
        } else {
            configRepository.updateGmailAuthStatus(GmailAuthStatus.FAILED, result.error)
        }

        return@withContext result
    }

    private fun transmitViaGmailApi(
        config: GmailConfigurationEntity,
        recipientEmail: String,
        recipientName: String,
        subject: String,
        htmlBody: String,
        pdfFile: File
    ): GmailSendResult {
        try {
            val fullBody = buildString {
                append(htmlBody)
                if (config.signature.isNotBlank()) {
                    append("<br><br>--<br>")
                    append(config.signature.replace("\n", "<br>"))
                }
            }

            // Construct RFC 822 MIME message
            val boundary = "==Multipart_Boundary_${System.currentTimeMillis()}=="
            val baos = ByteArrayOutputStream()

            val safeAccountEmail = sanitizeMimeHeader(config.accountEmail)
            val safeRecipientEmail = sanitizeMimeHeader(recipientEmail)
            val fromHeader = if (safeAccountEmail.isNotBlank()) {
                formatMailboxHeader(config.senderName, safeAccountEmail)
            } else {
                "me"
            }
            val toHeader = formatMailboxHeader(recipientName, safeRecipientEmail)
            val safeSubject = sanitizeMimeHeader(subject)
            val safeAttachmentName = sanitizeMimeHeader(pdfFile.name)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")

            val headerBuilder = StringBuilder()
            headerBuilder.append("From: $fromHeader\r\n")
            headerBuilder.append("To: $toHeader\r\n")
            headerBuilder.append("Subject: $safeSubject\r\n")
            headerBuilder.append("MIME-Version: 1.0\r\n")
            headerBuilder.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")

            // Part 1: HTML body
            headerBuilder.append("--$boundary\r\n")
            headerBuilder.append("Content-Type: text/html; charset=UTF-8\r\n")
            headerBuilder.append("Content-Transfer-Encoding: 7bit\r\n\r\n")
            headerBuilder.append(fullBody).append("\r\n\r\n")

            // Part 2: PDF attachment
            headerBuilder.append("--$boundary\r\n")
            headerBuilder.append("Content-Type: application/pdf; name=\"$safeAttachmentName\"\r\n")
            headerBuilder.append("Content-Disposition: attachment; filename=\"$safeAttachmentName\"\r\n")
            headerBuilder.append("Content-Transfer-Encoding: base64\r\n\r\n")

            baos.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))

            // Write Base64 PDF
            val fileBytes = pdfFile.readBytes()
            val encodedPdf = Base64.encodeToString(fileBytes, Base64.CRLF)
            baos.write(encodedPdf.toByteArray(StandardCharsets.UTF_8))
            baos.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))

            val rawMimeBytes = baos.toByteArray()
            val rawBase64Url = Base64.encodeToString(
                rawMimeBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val jsonPayload = JSONObject().apply {
                put("raw", rawBase64Url)
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
                .addHeader("Authorization", "Bearer ${config.accessToken}")
                .addHeader("Accept", "application/json")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(if (responseBody.isNotBlank()) responseBody else "{}")
                val messageId = json.optString("id", "").trim()
                // Strict check: If Gmail response does not contain a real message ID, treat as failure
                if (messageId.isBlank()) {
                    return GmailSendResult(
                        success = false,
                        error = "Gmail API response succeeded but did not return a valid message id."
                    )
                }
                return GmailSendResult(success = true, messageId = messageId)
            } else {
                val sanitized = sanitizeSensitiveData(
                    "Gmail API HTTP ${response.code}: ${response.message}. $responseBody",
                    config
                )
                return GmailSendResult(
                    success = false,
                    error = sanitized
                )
            }
        } catch (e: Exception) {
            val sanitized = sanitizeSensitiveData(
                e.message ?: "Failed to transmit email via Gmail API",
                config
            )
            return GmailSendResult(
                success = false,
                error = sanitized
            )
        }
    }

    suspend fun refreshAccessToken(config: GmailConfigurationEntity): GmailConfigurationEntity? {
        val refreshToken = config.refreshToken
        if (refreshToken.isNullOrBlank()) {
            configRepository.updateGmailAuthStatus(
                GmailAuthStatus.AUTH_EXPIRED,
                "Refresh token is missing. Please authorize Gmail account in Settings."
            )
            return null
        }

        val clientId = configRepository.getOAuthClientId()
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
                .url("https://oauth2.googleapis.com/token")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val newAccessToken = json.optString("access_token", "").trim()
                if (newAccessToken.isNotBlank()) {
                    val expiresInSec = json.optLong("expires_in", 3600L)
                    val newExpiresAt = System.currentTimeMillis() + (expiresInSec * 1000L)
                    val newRefreshToken = json.optString("refresh_token", "").takeIf { it.isNotBlank() } ?: refreshToken

                    // Save encrypted through ConfigRepository
                    configRepository.updateGmailTokens(
                        newAccessToken = newAccessToken,
                        newRefreshToken = newRefreshToken,
                        expiresAt = newExpiresAt,
                        authStatus = GmailAuthStatus.AUTH_REQUIRED,
                        lastError = null
                    )
                    return configRepository.getGmailConfig()
                }
            } else {
                val errorBody = sanitizeSensitiveData(response.body?.string() ?: "", config)
                configRepository.updateGmailAuthStatus(
                    GmailAuthStatus.FAILED,
                    "Google OAuth refresh failed: HTTP ${response.code}. $errorBody"
                )
            }
            null
        } catch (e: Exception) {
            val errorMsg = sanitizeSensitiveData(e.message ?: "OAuth refresh failed", config)
            configRepository.updateGmailAuthStatus(
                GmailAuthStatus.FAILED,
                errorMsg
            )
            null
        }
    }

    private fun formatMailboxHeader(displayName: String, email: String): String {
        val safeName = sanitizeMimeHeader(displayName)
        if (safeName.isBlank()) return email
        val quotedName = safeName.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$quotedName\" <$email>"
    }

    private fun sanitizeSensitiveData(message: String, config: GmailConfigurationEntity?): String {
        var sanitized = message
        config?.accessToken?.let { token ->
            if (token.isNotBlank()) sanitized = sanitized.replace(token, "[REDACTED_ACCESS_TOKEN]")
        }
        config?.refreshToken?.let { token ->
            if (token.isNotBlank()) sanitized = sanitized.replace(token, "[REDACTED_REFRESH_TOKEN]")
        }
        return sanitized
    }

    fun createGmailIntent(
        context: Context,
        recipientEmail: String,
        subject: String,
        bodyText: String,
        pdfFile: File?
    ): Intent {
        val safePdfFile = OfferPdfStorage.resolveExistingPdf(context, pdfFile?.path)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (safePdfFile != null) "application/pdf" else "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, bodyText)
            setPackage("com.google.android.gm")

            if (safePdfFile != null) {
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    safePdfFile
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return intent
    }
}
