package com.example.domain.intelligence.source

import com.example.domain.propertyurl.url.UrlHosts
import com.example.domain.propertyurl.url.UrlSensitiveParameters
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
        } catch (_: Exception) {
            return ResolvedUrlInfo(
                isValid = false,
                originalUrl = rawUrl,
                sanitizedUrl = "",
                domain = "",
                identifiedSource = "Unsupported",
                validationError = "Invalid URL syntax."
            )
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" || uri.rawUserInfo != null) {
            return ResolvedUrlInfo(
                isValid = false,
                originalUrl = rawUrl,
                sanitizedUrl = "",
                domain = uri.host.orEmpty(),
                identifiedSource = "Unsupported",
                validationError = "Only public HTTPS property links without embedded credentials are allowed."
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
        if (UrlHosts.isIpLiteral(host) || UrlHosts.isPrivateNetwork(host) ||
            host == "metadata.google.internal" || host == "metadata" ||
            host.endsWith(".internal") || host.endsWith(".home.arpa")
        ) {
            return ResolvedUrlInfo(
                isValid = false,
                originalUrl = rawUrl,
                sanitizedUrl = "",
                domain = "",
                identifiedSource = "Unsupported",
                validationError = "Local, private, and IP-literal destinations are not supported."
            )
        }

        // Sanitize URL by removing tracking and credential-like query parameters
        val sanitizedQuery = uri.query?.split("&")?.filter { param ->
            val key = param.substringBefore("=").lowercase()
            !SENSITIVE_QUERY_PARAMS.contains(key) && !UrlSensitiveParameters.isSensitive(key)
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
            isDomainOrSubdomain(host, "zillow.com") -> "Zillow"
            isDomainOrSubdomain(host, "redfin.com") -> "Redfin"
            isDomainOrSubdomain(host, "realtor.com") -> "Realtor.com"
            isDomainOrSubdomain(host, "homes.com") -> "Homes.com"
            isDomainOrSubdomain(host, "trulia.com") ||
                isDomainOrSubdomain(host, "compass.com") ||
                isDomainOrSubdomain(host, "coldwellbanker.com") -> "Generic"
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

    private fun isDomainOrSubdomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")
}
