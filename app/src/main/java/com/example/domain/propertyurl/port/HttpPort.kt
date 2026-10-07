package com.example.domain.propertyurl.port

import com.example.domain.propertyurl.url.UrlHosts
import java.net.URI
import java.util.Locale

/** Outbound HTTP request built by adapters. URLs must already be normalized and credential-free. */
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val accept: String = DEFAULT_ACCEPT
) {
    companion object {
        const val DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5"
    }
}

/** Per-attempt transport budget. Tuned for mobile networks: fail fast, retry cheaply. */
data class FetchOptions(
    val connectTimeoutMillis: Int = 8_000,
    val readTimeoutMillis: Int = 15_000,
    val maxBodyBytes: Int = 4_000_000,
    val maxRedirects: Int = 5,
    val userAgent: String = DEFAULT_USER_AGENT,
    val acceptLanguage: String = "en-US,en;q=0.9",
    val allowCompression: Boolean = true,
    /** Last line of defence against SSRF through redirects. */
    val redirectGuard: (String) -> Boolean = DEFAULT_REDIRECT_GUARD
) {
    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(maxBodyBytes >= 0) { "maxBodyBytes must not be negative" }
        require(maxRedirects in 0..10) { "maxRedirects must be between 0 and 10" }
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "RealEstateAI-PropertyIntel/1.0 (+https://realestate-ai.example/bot; contact: ops@realestate-ai.example)"

        /** Redirects must remain HTTPS, credential-free, and target a public DNS name. */
        val DEFAULT_REDIRECT_GUARD: (String) -> Boolean = { candidate ->
            val uri = runCatching { URI(candidate.trim()) }.getOrNull()
            val scheme = uri?.scheme?.lowercase(Locale.US)
            val host = uri?.host?.trimEnd('.')?.lowercase(Locale.US)
            scheme == "https" &&
                uri?.rawUserInfo == null &&
                (uri?.port == -1 || uri?.port == 443) &&
                !host.isNullOrBlank() &&
                UrlHosts.isValidHostSyntax(host) &&
                UrlHosts.hasTld(host) &&
                !UrlHosts.isIpLiteral(host) &&
                !UrlHosts.isPrivateNetwork(host) &&
                host != "metadata" &&
                host != "metadata.google.internal" &&
                !host.endsWith(".internal") &&
                !host.endsWith(".home.arpa")
        }
    }
}

/** Transport-level failure classification (HTTP statuses are handled with [HttpFetchResult.Response]). */
enum class TransportFailureKind {
    DNS_FAILURE,
    TIMEOUT,
    TLS_FAILURE,
    NETWORK_UNREACHABLE,
    CONNECTION_RESET,
    TOO_MANY_REDIRECTS,
    BLOCKED_REDIRECT,
    UNSUPPORTED_SCHEME,
    CANCELLED,
    INTERNAL
}

sealed interface HttpFetchResult {

    /**
     * A complete HTTP exchange. Non-2xx responses are *not* failures at this level; the pipeline
     * classifies them (a 404 page still carries provenance-worthy information for diagnostics).
     */
    data class Response(
        val requestedUrl: String,
        val finalUrl: String,
        val status: Int,
        val headers: Map<String, String>,
        val body: String?,
        val bodyBytes: Int,
        val contentType: String?,
        val elapsedMillis: Long,
        val truncated: Boolean = false,
        val redirects: List<String> = emptyList()
    ) : HttpFetchResult {
        val isSuccess: Boolean get() = status in 200..299

        val isHtml: Boolean
            get() = contentType?.lowercase()?.let { it.contains("html") || it.contains("xml") } == true ||
                (contentType == null && body?.trimStart()?.startsWith("<") == true)

        val isJson: Boolean get() = contentType?.lowercase()?.contains("json") == true
    }

    data class TransportError(
        val kind: TransportFailureKind,
        val message: String,
        val requestedUrl: String,
        val causeType: String? = null,
        val elapsedMillis: Long = 0
    ) : HttpFetchResult
}

/**
 * The only way the layer talks to the network.
 *
 * Production wiring uses [com.example.domain.propertyurl.port.OkHttpHttpFetcher], which enforces
 * HTTPS-only requests, public-only DNS answers, bounded manual redirects, and credential isolation.
 * Tests use a fake fetcher driven by fixtures.
 */
interface HttpFetcher {
    suspend fun fetch(request: HttpRequest, options: FetchOptions = FetchOptions()): HttpFetchResult
}
