package com.example.urlintelligence.url

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Why a URL was rejected. Codes are stable and safe to log / emit as metrics. */
sealed class UrlValidationError {
    abstract val code: String
    abstract val message: String

    object Blank : UrlValidationError() {
        override val code: String = "URL_BLANK"
        override val message: String = "URL is empty"
    }

    data class TooLong(val length: Int, val maxLength: Int) : UrlValidationError() {
        override val code: String = "URL_TOO_LONG"
        override val message: String = "URL exceeds $maxLength characters (was $length)"
    }

    data class Malformed(override val message: String) : UrlValidationError() {
        override val code: String = "URL_MALFORMED"
    }

    data class UnsupportedScheme(val scheme: String) : UrlValidationError() {
        override val code: String = "URL_SCHEME_UNSUPPORTED"
        override val message: String = "Scheme '$scheme' is not supported"
    }

    object CredentialsInUrl : UrlValidationError() {
        override val code: String = "URL_CREDENTIALS"
        override val message: String = "URLs must not embed credentials"
    }

    data class HostNotAllowed(val host: String) : UrlValidationError() {
        override val code: String = "URL_HOST_NOT_ALLOWED"
        override val message: String = "Host '$host' is not a public, routable property source host"
    }

    object MissingPath : UrlValidationError() {
        override val code: String = "URL_PATH_MISSING"
        override val message: String = "URL does not contain a property path"
    }

    data class SuspiciousPattern(override val message: String) : UrlValidationError() {
        override val code: String = "URL_SUSPICIOUS"
    }
}

/** Result of validation: either a usable, canonical URL or a classified rejection. */
sealed class UrlValidationResult {
    data class Valid(val url: NormalizedUrl) : UrlValidationResult()
    data class Invalid(val error: UrlValidationError) : UrlValidationResult()

    val isValid: Boolean
        get() = this is Valid
}

/**
 * Canonical, comparable form of a property URL.
 *
 * Canonicalization matters twice: it is the basis of idempotency keys (so the same
 * listing shared with different tracking parameters is imported once) and it is what
 * the source detector matches against.
 */
data class NormalizedUrl(
    val original: String,
    val canonical: String,
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: Map<String, String>,
    val droppedParameters: List<String>
) {
    val hostWithoutWww: String
        get() = host.removePrefix("www.")

    val rootUrl: String
        get() = buildString {
            append(scheme).append("://").append(host)
            if (port != null) append(':').append(port)
        }

    fun withPath(newPath: String): NormalizedUrl {
        val nextPath = if (newPath.startsWith("/")) newPath else "/$newPath"
        return copy(path = nextPath, canonical = rootUrl + nextPath)
    }

    override fun toString(): String = canonical
}

data class UrlValidationConfig(
    val maxLength: Int = 2048,
    val allowedSchemes: Set<String> = setOf("http", "https"),
    /** Dropped before canonicalization: tracking noise that breaks dedup. */
    val trackingParameters: Set<String> = DEFAULT_TRACKING_PARAMETERS,
    /** Security: never fetch private/loopback/link-local/metadata hosts (SSRF guard). */
    val blockPrivateHosts: Boolean = true,
    /** A property URL always carries a path; bare hosts are rejected. */
    val requirePropertyPath: Boolean = true,
    /** When false, every query parameter is dropped from the canonical form. */
    val keepQueryParameters: Boolean = true
) {
    init {
        require(maxLength in 16..8192) { "maxLength must be within 16..8192" }
        require(allowedSchemes.isNotEmpty()) { "allowedSchemes must not be empty" }
    }

    companion object {
        val DEFAULT_TRACKING_PARAMETERS: Set<String> = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "utm_id", "utm_name", "utm_reader", "utm_social", "utm_brand",
            "gclid", "gbraid", "wbraid", "fbclid", "msclkid", "twclid", "igshid",
            "mc_cid", "mc_eid", "yclid", "ttclid", "s_kwcid", "trk", "trkemail",
            "ref", "ref_", "referrer", "source", "sourceid", "share_id", "_hsenc", "vero_id"
        )
    }
}

/**
 * Validates and canonicalizes property URLs.
 *
 * Pure function of its input — no DNS, no network. Security-sensitive inputs
 * (credentials, private hosts, non-http schemes) are rejected before any fetch
 * is attempted.
 */
class PropertyUrlValidator(private val config: UrlValidationConfig = UrlValidationConfig()) {

    private val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val dangerous = Regex(
        "(?i)(javascript:|data:|vbscript:|file:|jar:|about:|blob:|\\bjava\\.lang\\b)"
    )

    fun validate(rawUrl: String): UrlValidationResult {
        val cleaned = rawUrl.trim()
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .replace("\\", "/")

        if (cleaned.isBlank()) return UrlValidationResult.Invalid(UrlValidationError.Blank)
        if (cleaned.length > config.maxLength) {
            return UrlValidationResult.Invalid(
                UrlValidationError.TooLong(cleaned.length, config.maxLength)
            )
        }
        if (dangerous.containsMatchIn(cleaned)) {
            return UrlValidationResult.Invalid(
                UrlValidationError.UnsupportedScheme(schemeOf(cleaned) ?: "unknown")
            )
        }
        if (cleaned.contains("@") && cleaned.indexOf('@') < (cleaned.indexOf('/').let { if (it < 0) cleaned.length else it })) {
            // userinfo present (http://user:pass@host/...) — never allowed.
            return UrlValidationResult.Invalid(UrlValidationError.CredentialsInUrl)
        }

        val withScheme = if (cleaned.contains("://")) cleaned else "https://$cleaned"

        val uri = try {
            URI(withScheme)
        } catch (t: Exception) {
            return UrlValidationResult.Invalid(
                UrlValidationError.Malformed(t.message ?: "unparseable URL")
            )
        }
        if (!uri.isAbsolute) {
            return UrlValidationResult.Invalid(UrlValidationError.Malformed("relative URL"))
        }
        if (uri.userInfo != null) {
            return UrlValidationResult.Invalid(UrlValidationError.CredentialsInUrl)
        }

        val scheme = uri.scheme?.lowercase() ?: return UrlValidationResult.Invalid(
            UrlValidationError.Malformed("missing scheme")
        )
        if (scheme !in config.allowedSchemes) {
            return UrlValidationResult.Invalid(UrlValidationError.UnsupportedScheme(scheme))
        }

        val rawHost = uri.host
        if (rawHost.isNullOrBlank()) {
            return UrlValidationResult.Invalid(UrlValidationError.Malformed("missing host"))
        }
        val asciiHost = try {
            IDN.toASCII(rawHost.trimEnd('.'), IDN.ALLOW_UNASSIGNED)
        } catch (t: IllegalArgumentException) {
            return UrlValidationResult.Invalid(UrlValidationError.Malformed("invalid host '$rawHost'"))
        }.lowercase()

        if (asciiHost.isBlank() || asciiHost.startsWith(".") || asciiHost.endsWith(".")) {
            return UrlValidationResult.Invalid(UrlValidationError.HostNotAllowed(asciiHost))
        }
        if (!asciiHost.contains(".")) {
            return UrlValidationResult.Invalid(
                UrlValidationError.HostNotAllowed(asciiHost)
            )
        }
        if (config.blockPrivateHosts && isBlockedHost(asciiHost)) {
            return UrlValidationResult.Invalid(UrlValidationError.HostNotAllowed(asciiHost))
        }

        val path = normalizePath(uri.rawPath ?: "")
        if (config.requirePropertyPath && path.trim('/').isBlank()) {
            return UrlValidationResult.Invalid(UrlValidationError.MissingPath)
        }

        val allParams = parseQuery(uri.rawQuery)
        val kept = LinkedHashMap<String, String>()
        val dropped = ArrayList<String>()
        allParams.forEach { (key, value) ->
            val normalizedKey = key.lowercase()
            val isTracking = normalizedKey.startsWith("utm_") || normalizedKey in config.trackingParameters
            if (isTracking || !config.keepQueryParameters) dropped.add(key) else kept[key] = value
        }

        val port = uri.port.takeIf { it > 0 && it != defaultPort(scheme) }
        val canonical = buildCanonical(scheme, asciiHost, port, path, kept)

        return UrlValidationResult.Valid(
            NormalizedUrl(
                original = rawUrl,
                canonical = canonical,
                scheme = scheme,
                host = asciiHost,
                port = port,
                path = path,
                query = kept.toMap(),
                droppedParameters = dropped
            )
        )
    }

    /** Convenience for callers that only need the canonical string. */
    fun canonicalizeOrNull(rawUrl: String): String? =
        (validate(rawUrl) as? UrlValidationResult.Valid)?.url?.canonical

    private fun schemeOf(value: String): String? =
        value.substringBefore(':').takeIf { it.isNotBlank() && it.length <= 16 }

    internal fun normalizePath(rawPath: String): String {
        if (rawPath.isBlank()) return "/"
        val collapsed = rawPath
            .replace(Regex("/{2,}"), "/")
            .let { if (it.length > 1) it.trimEnd('/') else it }
        return if (collapsed.startsWith("/")) collapsed else "/$collapsed"
    }

    internal fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val eq = pair.indexOf('=')
            val rawKey = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            val key = decode(rawKey).trim()
            if (key.isBlank()) return@forEach
            out[key] = decode(rawValue)
        }
        return out
    }

    private fun buildCanonical(
        scheme: String,
        host: String,
        port: Int?,
        path: String,
        query: Map<String, String>
    ): String = buildString {
        append(scheme).append("://").append(host)
        if (port != null) append(':').append(port)
        append(path)
        if (query.isNotEmpty()) {
            append('?')
            append(
                query.entries
                    .sortedBy { it.key }
                    .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
            )
        }
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (t: Exception) {
        value
    }

    private fun encode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (t: Exception) {
        value
    }

    /**
     * SSRF / sandbox guard: property data must come from public hosts only.
     * Covers loopback, RFC1918, link-local, CGNAT, IPv6 literals and cloud metadata.
     */
    internal fun isBlockedHost(host: String): Boolean {
        if (host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host.endsWith(".home.arpa") ||
            host == "metadata.google.internal" ||
            host == "metadata"
        ) return true

        if (host.startsWith("[") || host.contains(':')) return true // IPv6 literal

        val m = ipv4.matchEntire(host) ?: return false
        val o = (1..4).map { m.groupValues[it].toIntOrNull() ?: return true }
        if (o.any { it > 255 }) return true
        return when {
            o[0] == 0 -> true
            o[0] == 10 -> true
            o[0] == 127 -> true
            o[0] == 169 && o[1] == 254 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 100 && o[1] in 64..127 -> true
            o[0] == 192 && o[1] == 0 && o[2] == 0 -> true
            o[0] == 198 && (o[1] == 18 || o[1] == 19) -> true
            o[0] >= 224 -> true
            else -> false
        }
    }
}
