package com.example.domain.propertyurl.util

import com.example.domain.propertyurl.url.PropertyUrlNormalizer
import java.net.URI
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
        "(?i)(authorization|proxy-authorization|bearer|x-goog-api-key|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|secret|password|pwd|cookie)\\s*[:=]\\s*(?:bearer\\s+)?([^\\s,;\"']+)"
    )
    private val JWT = Regex("[A-Za-z0-9_\\-]{16,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}")
    private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}")
    private val GOOGLE_API_KEY = Regex("\\bAIza[0-9A-Za-z_-]{20,}\\b")
    private val GOOGLE_OAUTH_TOKEN = Regex("\\b(?:ya29\\.[A-Za-z0-9._-]{8,}|1//[A-Za-z0-9._-]{8,})\\b")
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("\\+?\\d[\\d\\-\\s().]{8,}\\d")
    private val LONG_DIGITS = Regex("\\b\\d{12,19}\\b")

    /** Redacts a raw URL string, masking credential-like query parameters. */
    fun url(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        val trimmed = raw.trim()
        val candidate = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed) -> trimmed
            else -> "https://$trimmed"
        }
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return "Invalid URL (redacted)"
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            return "Invalid URL (redacted)"
        }
        return try {
            val normalization = PropertyUrlNormalizer.normalize(
                candidate,
                com.example.domain.propertyurl.url.UrlNormalizationOptions.NONE
            )
            normalization.url.redacted().ifBlank { "Invalid URL (redacted)" }
        } catch (_: Exception) {
            "Invalid URL (redacted)"
        }
    }

    /** Redacts free text (exception messages, payload snippets, operator notes). */
    fun message(text: String?, maxLength: Int = 400): String {
        if (text.isNullOrEmpty()) return ""
        var sanitized = text
        // Redact bearer values before key/value pairs so an Authorization header cannot leave
        // its token behind when the header name itself is replaced.
        sanitized = BEARER.replace(sanitized, "Bearer $MASK")
        sanitized = GOOGLE_OAUTH_TOKEN.replace(sanitized, MASK)
        sanitized = GOOGLE_API_KEY.replace(sanitized, MASK)
        sanitized = SECRET_ASSIGNMENTS.replace(sanitized) { match -> "${match.groupValues[1]}=$MASK" }
        sanitized = JWT.replace(sanitized, MASK)
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
