package com.example.domain.intelligence.source

import java.net.URI

data class ResolvedUrlInfo(
    val isValid: Boolean,
    val originalUrl: String,
    val sanitizedUrl: String,
    val domain: String,
    val identifiedSource: String, // "Zillow", "Redfin", "Realtor.com", "Homes.com", "Generic", "Unsupported"
    val validationError: String? = null
)

object PropertyUrlResolver {

    private val SENSITIVE_QUERY_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "token", "session", "user_id", "email", "auth"
    )

    fun resolve(rawUrl: String): ResolvedUrlInfo {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return ResolvedUrlInfo(
                isValid = false,
                originalUrl = rawUrl,
                sanitizedUrl = "",
                domain = "",
                identifiedSource = "Unsupported",
                validationError = "URL cannot be empty."
            )
        }

        val uri = try {
            val withProtocol = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                "https://$trimmed"
            } else {
                trimmed
            }
            URI.create(withProtocol)
        } catch (e: Exception) {
            return ResolvedUrlInfo(
                isValid = false,
                originalUrl = rawUrl,
                sanitizedUrl = "",
                domain = "",
                identifiedSource = "Unsupported",
                validationError = "Invalid URL syntax: ${e.message}"
            )
        }

        val host = uri.host?.lowercase() ?: ""
        if (host.isBlank() || !host.contains(".")) {
            return ResolvedUrlInfo(
                isValid = false,
                originalUrl = rawUrl,
                sanitizedUrl = "",
                domain = host,
                identifiedSource = "Unsupported",
                validationError = "Invalid web domain."
            )
        }

        // Sanitize URL by removing tracking query parameters
        val sanitizedQuery = uri.query?.split("&")?.filter { param ->
            val key = param.substringBefore("=").lowercase()
            !SENSITIVE_QUERY_PARAMS.contains(key)
        }?.joinToString("&")

        val sanitized = buildString {
            append(uri.scheme ?: "https")
            append("://")
            append(uri.host)
            if (uri.port != -1 && uri.port != 80 && uri.port != 443) {
                append(":").append(uri.port)
            }
            append(uri.path ?: "")
            if (!sanitizedQuery.isNullOrBlank()) {
                append("?").append(sanitizedQuery)
            }
        }

        val source = when {
            host.contains("zillow.com") -> "Zillow"
            host.contains("redfin.com") -> "Redfin"
            host.contains("realtor.com") -> "Realtor.com"
            host.contains("homes.com") -> "Homes.com"
            host.contains("trulia.com") || host.contains("compass.com") || host.contains("coldwellbanker.com") -> "Generic"
            else -> "Unsupported"
        }

        return ResolvedUrlInfo(
            isValid = true,
            originalUrl = trimmed,
            sanitizedUrl = sanitized,
            domain = host,
            identifiedSource = source
        )
    }
}
