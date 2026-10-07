package com.example.domain.propertyurl.util

import com.example.domain.propertyurl.url.PropertyUrlNormalizer
import java.util.Locale

/**
 * Central sanitizer for everything the layer logs, persists or reports.
 *
 * Rules: no credentials, no cookies, no emails, no phone numbers, no tokens — in URLs, headers,
 * exception messages or payload snippets. This is the reason the layer can run with verbose
 * telemetry in a mobile app without leaking the operator's secrets or a user's PII.
 */
object Redaction {

    const val MASK = "***"

    private val SECRET_ASSIGNMENTS = Regex(
        "(?i)(authorization|bearer|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|secret|password|pwd|cookie)\\s*[:=]\\s*([^\\s,;\"']+)"
    )
    private val JWT = Regex("[A-Za-z0-9_\\-]{16,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}")
    private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}")
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("\\+?\\d[\\d\\-\\s().]{8,}\\d")
    private val LONG_DIGITS = Regex("\\b\\d{12,19}\\b")

    /** Redacts a raw URL string, masking credential-like query parameters. */
    fun url(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return try {
            val normalization = com.example.domain.propertyurl.url.PropertyUrlNormalizer.normalize(
                raw,
                com.example.domain.propertyurl.url.UrlNormalizationOptions.NONE
            )
            normalization.url.redacted().ifBlank { message(raw) }
        } catch (e: Exception) {
            message(raw)
        }
    }

    /** Redacts free text (exception messages, payload snippets, operator notes). */
    fun message(text: String?, maxLength: Int = 400): String {
        if (text.isNullOrEmpty()) return ""
        var sanitized = text
        sanitized = SECRET_ASSIGNMENTS.replace(sanitized) { match -> "${match.groupValues[1]}=$MASK" }
        sanitized = JWT.replace(sanitized, MASK)
        sanitized = BEARER.replace(sanitized, "Bearer $MASK")
        sanitized = EMAIL.replace(sanitized, MASK)
        sanitized = LONG_DIGITS.replace(sanitized, MASK)
        sanitized = PHONE.replace(sanitized) { match ->
            // Keep short numeric values (prices, counts) intact; only mask phone-like strings with separators.
            if (match.value.any { it == '-' || it == '(' || it == ')' || it == ' ' }) MASK else match.value
        }
        sanitized = sanitized.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return if (sanitized.length <= maxLength) sanitized else sanitized.take(maxLength) + "…"
    }

    /** Masks credential-bearing headers before they are persisted or logged. */
    fun headers(headers: Map<String, String>): Map<String, String> = headers.mapValues { (key, value) ->
        if (isSensitiveHeader(key) || PropertyUrlNormalizer.isSensitiveParameter(key)) MASK else message(value, 120)
    }

    fun isSensitiveHeader(name: String): Boolean {
        val lowered = name.lowercase(Locale.US)
        return lowered == "authorization" ||
            lowered == "cookie" ||
            lowered == "set-cookie" ||
            lowered == "proxy-authorization" ||
            lowered == "x-api-key" ||
            lowered.contains("token") ||
            lowered.contains("secret") ||
            lowered.contains("api-key") ||
            lowered.contains("apikey")
    }
}
