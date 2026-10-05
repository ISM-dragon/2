package com.example.domain.gmail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Patterns
import androidx.core.content.FileProvider
import com.example.data.local.dao.ConfigDao
import com.example.data.local.entity.GmailConfigurationEntity
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
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GmailSendResult(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
)

class GmailService(
    private val configDao: ConfigDao
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getConfiguration(): GmailConfigurationEntity {
        return configDao.getGmailConfig() ?: GmailConfigurationEntity()
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

        // 3. Validate PDF attachment
        if (pdfFile == null || !pdfFile.exists() || pdfFile.length() == 0L) {
            return@withContext GmailSendResult(
                success = false,
                error = "Required offer PDF attachment is missing or empty on local disk."
            )
        }

        // 4. Verify account authorization
        var config = getConfiguration()
        if (!config.isConnected || config.accessToken.isNullOrBlank()) {
            return@withContext GmailSendResult(
                success = false,
                error = "Gmail OAuth is not configured. Email was not sent."
            )
        }

        // Check token expiration before sending
        if (config.expiresAt > 0L && System.currentTimeMillis() > config.expiresAt) {
            val refreshed = refreshAccessToken(config)
            if (refreshed != null) {
                config = refreshed
            } else {
                return@withContext GmailSendResult(
                    success = false,
                    error = "Gmail authorization expired. Please re-authorize in Settings."
                )
            }
        }

        val result = transmitViaGmailApi(config, cleanEmail, recipientName, subject, htmlBody, pdfFile)
        if (!result.success && result.error?.contains("401") == true && !config.refreshToken.isNullOrBlank()) {
            // Attempt token refresh on 401 Unauthorized
            val refreshed = refreshAccessToken(config)
            if (refreshed != null) {
                config = refreshed
                return@withContext transmitViaGmailApi(config, cleanEmail, recipientName, subject, htmlBody, pdfFile)
            }
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

            val fromHeader = if (config.senderName.isNotBlank() && config.accountEmail.isNotBlank()) {
                "${config.senderName} <${config.accountEmail}>"
            } else if (config.accountEmail.isNotBlank()) {
                config.accountEmail
            } else {
                "me"
            }

            val toHeader = if (recipientName.isNotBlank()) "$recipientName <$recipientEmail>" else recipientEmail

            val headerBuilder = StringBuilder()
            headerBuilder.append("From: $fromHeader\r\n")
            headerBuilder.append("To: $toHeader\r\n")
            headerBuilder.append("Subject: $subject\r\n")
            headerBuilder.append("MIME-Version: 1.0\r\n")
            headerBuilder.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")

            // Part 1: HTML body
            headerBuilder.append("--$boundary\r\n")
            headerBuilder.append("Content-Type: text/html; charset=UTF-8\r\n")
            headerBuilder.append("Content-Transfer-Encoding: 7bit\r\n\r\n")
            headerBuilder.append(fullBody).append("\r\n\r\n")

            // Part 2: PDF attachment
            headerBuilder.append("--$boundary\r\n")
            headerBuilder.append("Content-Type: application/pdf; name=\"${pdfFile.name}\"\r\n")
            headerBuilder.append("Content-Disposition: attachment; filename=\"${pdfFile.name}\"\r\n")
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
                val messageId = json.optString("id", "GMAIL-${System.currentTimeMillis()}")
                return GmailSendResult(success = true, messageId = messageId)
            } else {
                return GmailSendResult(
                    success = false,
                    error = "Gmail API HTTP ${response.code}: ${response.message}. $responseBody"
                )
            }
        } catch (e: Exception) {
            return GmailSendResult(
                success = false,
                error = e.message ?: "Failed to transmit email via Gmail API"
            )
        }
    }

    suspend fun refreshAccessToken(config: GmailConfigurationEntity): GmailConfigurationEntity? {
        val refreshToken = config.refreshToken ?: return null
        return try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", "real-estate-ai-app")
                .build()

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val newAccessToken = json.optString("access_token")
                if (newAccessToken.isNotBlank()) {
                    val updated = config.copy(
                        accessToken = newAccessToken,
                        expiresAt = System.currentTimeMillis() + (json.optLong("expires_in", 3600) * 1000)
                    )
                    configDao.saveGmailConfig(updated)
                    return updated
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun createGmailIntent(
        context: Context,
        recipientEmail: String,
        subject: String,
        bodyText: String,
        pdfFile: File?
    ): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (pdfFile != null && pdfFile.exists()) "application/pdf" else "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, bodyText)
            setPackage("com.google.android.gm")

            if (pdfFile != null && pdfFile.exists()) {
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    pdfFile
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return intent
    }
}
