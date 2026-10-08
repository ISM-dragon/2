package com.example.domain.propertyurl.url

import java.util.Locale

/** Machine-readable validation finding. UI/telemetry must switch on the code, never on the message. */
enum class UrlValidationCode {
    EMPTY_INPUT,
    TOO_LONG,
    CONTROL_CHARACTERS,
    WHITESPACE_IN_URL,
    MISSING_SCHEME,
    SCHEME_ASSUMED_HTTPS,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    INVALID_HOST,
    NON_ASCII_HOST,
    MISSING_TLD,
    IP_LITERAL_HOST,
    PRIVATE_NETWORK_HOST,
    CREDENTIALS_IN_URL,
    INVALID_PORT,
    DISALLOWED_PORT,
    INVALID_PERCENT_ENCODING,
    PATH_TRAVERSAL,
    TOO_MANY_QUERY_PARAMETERS,
    SUSPICIOUS_FRAGMENT,
    TRACKING_PARAMETERS_PRESENT,
    SENSITIVE_PARAMETERS_PRESENT,
    NOT_A_URL
}

enum class UrlValidationSeverity { ERROR, WARNING }

data class UrlValidationIssue(
    val code: UrlValidationCode,
    val severity: UrlValidationSeverity,
    val message: String,
    val detail: String? = null
)

/** Validation policy. Defaults are the production values used for user-supplied property URLs. */
data class UrlValidationOptions(
    /** Cleartext is disabled by the Android app and must be explicitly opted into by non-production callers. */
    val allowInsecureHttp: Boolean = false,
    /** Refusing raw IP hosts also blocks a large family of SSRF probes. */
    val allowIpLiteralHosts: Boolean = false,
    /** The intelligence layer must never be used to fetch cloud metadata / localhost. */
    val allowPrivateNetworkHosts: Boolean = false,
    val allowCredentialsInUrl: Boolean = false,
    val requireTld: Boolean = true,
    val maxLength: Int = 2048,
    val allowedPorts: Set<Int> = setOf(80, 443),
    val maxQueryParameters: Int = 48,
    val assumeHttpsWhenSchemeMissing: Boolean = true
) {
    companion object {
        /** Relaxed profile: used for unit tests, offline fixtures and internal tooling. */
        val RELAXED = UrlValidationOptions(
            allowIpLiteralHosts = true,
            allowPrivateNetworkHosts = true,
            allowedPorts = (1..65535).toSet()
        )
    }
}

sealed interface UrlValidationResult {

    data class Valid(
        val url: PropertyUrl,
        val warnings: List<UrlValidationIssue> = emptyList(),
        val normalization: NormalizationResult? = null
    ) : UrlValidationResult {

        val normalizedUrl: String get() = url.normalized

        fun issueCodes(): List<UrlValidationCode> = warnings.map { it.code }
    }

    data class Invalid(
        val input: String,
        val issues: List<UrlValidationIssue>
    ) : UrlValidationResult {

        val primaryCode: UrlValidationCode get() = issues.firstOrNull()?.code ?: UrlValidationCode.NOT_A_URL

        val summary: String get() = issues.joinToString("; ") { it.message }
    }

    val isValid: Boolean get() = this is Valid

    fun urlOrNull(): PropertyUrl? = (this as? Valid)?.url
}

/**
 * Syntactic + safety validation of property listing URLs.
 *
 * Layering: validation is *pure* (no I/O) and independent from source detection, so it can run in a
 * UI preview, in a background import or on a server without any adapter being registered.
 */
class PropertyUrlValidator(
    private val options: UrlValidationOptions = UrlValidationOptions(),
    private val normalizationOptions: UrlNormalizationOptions = UrlNormalizationOptions()
) {

    fun validate(input: String): UrlValidationResult {
        val issues = ArrayList<UrlValidationIssue>()

        if (input.isBlank()) {
            return UrlValidationResult.Invalid(input, listOf(error(UrlValidationCode.EMPTY_INPUT, "URL is empty")))
        }
        if (input.length > options.maxLength) {
            return UrlValidationResult.Invalid(
                input,
                listOf(
                    error(
                        UrlValidationCode.TOO_LONG,
                        "URL is longer than ${options.maxLength} characters",
                        "length=${input.length}"
                    )
                )
            )
        }
        if (input.any { it.code < 0x20 || it.code == 0x7F }) {
            issues.add(error(UrlValidationCode.CONTROL_CHARACTERS, "URL contains control characters"))
        }
        if (input.contains('\u3000')) {
            issues.add(error(UrlValidationCode.WHITESPACE_IN_URL, "URL contains unsupported whitespace"))
        }

        val sanitized = UrlParser.sanitize(input)
        if (sanitized.isEmpty()) {
            return UrlValidationResult.Invalid(input, listOf(error(UrlValidationCode.EMPTY_INPUT, "URL is empty")))
        }
        if (sanitized.any { it == ' ' }) {
            issues.add(error(UrlValidationCode.WHITESPACE_IN_URL, "URL contains spaces; paste a single link"))
        }
        if (sanitized.none { it == '/' } && sanitized.none { it == '.' }) {
            issues.add(error(UrlValidationCode.NOT_A_URL, "Input does not look like a URL", sanitized.take(48)))
        }

        val parts = UrlParser.decompose(sanitized)

        val scheme = parts.scheme
        if (scheme == null) {
            if (options.assumeHttpsWhenSchemeMissing) {
                issues.add(
                    warning(
                        UrlValidationCode.SCHEME_ASSUMED_HTTPS,
                        "No scheme supplied — assuming https://"
                    )
                )
            } else {
                issues.add(error(UrlValidationCode.MISSING_SCHEME, "URL must start with https://"))
            }
        } else if (scheme != "http" && scheme != "https") {
            issues.add(
                error(
                    UrlValidationCode.UNSUPPORTED_SCHEME,
                    "Only http(s) links are supported",
                    "scheme=$scheme"
                )
            )
        } else if (scheme == "http" && !options.allowInsecureHttp) {
            issues.add(error(UrlValidationCode.UNSUPPORTED_SCHEME, "Plain http links are disabled"))
        }

        val host = parts.host
        if (host.isNullOrBlank()) {
            issues.add(error(UrlValidationCode.MISSING_HOST, "URL has no host"))
            return UrlValidationResult.Invalid(input, issues)
        }

        if (!UrlHosts.isAscii(host)) {
            issues.add(error(UrlValidationCode.NON_ASCII_HOST, "Non-ASCII host names are not supported", host))
        } else if (!UrlHosts.isValidHostSyntax(host)) {
            issues.add(error(UrlValidationCode.INVALID_HOST, "Host name is malformed", host))
        }

        if (UrlHosts.isIpLiteral(host)) {
            if (options.allowIpLiteralHosts) {
                issues.add(warning(UrlValidationCode.IP_LITERAL_HOST, "URL uses a raw IP address", host))
            } else {
                issues.add(error(UrlValidationCode.IP_LITERAL_HOST, "IP addresses are not accepted", host))
            }
        }

        if (UrlHosts.isPrivateNetwork(host) && !options.allowPrivateNetworkHosts) {
            issues.add(
                error(
                    UrlValidationCode.PRIVATE_NETWORK_HOST,
                    "Local/private network addresses are blocked",
                    host
                )
            )
        }

        if (options.requireTld && !UrlHosts.isIpLiteral(host) && !UrlHosts.hasTld(host)) {
            issues.add(error(UrlValidationCode.MISSING_TLD, "Host name is missing a public suffix", host))
        }

        if (!parts.userInfo.isNullOrEmpty()) {
            if (options.allowCredentialsInUrl) {
                issues.add(warning(UrlValidationCode.CREDENTIALS_IN_URL, "URL contains credentials", host))
            } else {
                issues.add(
                    error(
                        UrlValidationCode.CREDENTIALS_IN_URL,
                        "URLs with embedded credentials are rejected (and never logged)",
                        host
                    )
                )
            }
        }

        if (parts.portRaw != null) {
            val port = parts.port
            when {
                port == null -> issues.add(error(UrlValidationCode.INVALID_PORT, "Port is not a number", parts.portRaw))
                port !in 1..65535 -> issues.add(error(UrlValidationCode.INVALID_PORT, "Port is out of range", parts.portRaw))
                !options.allowedPorts.contains(port) && !UrlRecomposer.isDefaultPort(scheme ?: "https", port) ->
                    issues.add(error(UrlValidationCode.DISALLOWED_PORT, "Port $port is not allowed", parts.portRaw))
            }
        }

        if (PercentCodec.hasInvalidEscape(parts.path) ||
            (parts.queryRaw?.let { PercentCodec.hasInvalidEscape(it) } == true)
        ) {
            issues.add(error(UrlValidationCode.INVALID_PERCENT_ENCODING, "URL contains invalid percent-encoding"))
        }

        if (parts.path.split('/').any { it == ".." }) {
            issues.add(error(UrlValidationCode.PATH_TRAVERSAL, "URL contains '..' path segments"))
        }

        val queryParameters = parts.queryRaw?.let { UrlParser.parseQuery(it) }.orEmpty()
        if (queryParameters.size > options.maxQueryParameters) {
            issues.add(
                error(
                    UrlValidationCode.TOO_MANY_QUERY_PARAMETERS,
                    "URL carries ${queryParameters.size} query parameters (limit ${options.maxQueryParameters})"
                )
            )
        }
        val tracking = queryParameters.filter { PropertyUrlNormalizer.isTrackingParameter(it.name) }
        if (tracking.isNotEmpty()) {
            issues.add(
                warning(
                    UrlValidationCode.TRACKING_PARAMETERS_PRESENT,
                    "Tracking parameters will be removed (${tracking.joinToString(", ") { it.name }})"
                )
            )
        }
        val sensitive = queryParameters.filter { PropertyUrlNormalizer.isSensitiveParameter(it.name) }
        if (sensitive.isNotEmpty()) {
            // Accurate as of the normalizer change: credential-like parameters are dropped from the
            // canonical URL, so they are not fetched, logged, persisted or deduplicated on.
            issues.add(
                warning(
                    UrlValidationCode.SENSITIVE_PARAMETERS_PRESENT,
                    "URL contains credential-like parameters; they are stripped before fetch and never logged",
                    sensitive.joinToString(", ") { it.name }
                )
            )
        }
        if (!parts.fragment.isNullOrEmpty() && parts.fragment.length > 24) {
            issues.add(
                warning(
                    UrlValidationCode.SUSPICIOUS_FRAGMENT,
                    "Deep fragment detected; fragments are ignored for identity"
                )
            )
        }

        val fatal = issues.filter { it.severity == UrlValidationSeverity.ERROR }
        if (fatal.isNotEmpty()) return UrlValidationResult.Invalid(input, issues)

        val normalization = PropertyUrlNormalizer.canonicalize(parts, normalizationOptions, defaultScheme = "https")
        return UrlValidationResult.Valid(
            url = normalization.url,
            warnings = issues,
            normalization = normalization
        )
    }

    private fun error(code: UrlValidationCode, message: String, detail: String? = null) =
        UrlValidationIssue(code, UrlValidationSeverity.ERROR, message, detail)

    private fun warning(code: UrlValidationCode, message: String, detail: String? = null) =
        UrlValidationIssue(code, UrlValidationSeverity.WARNING, message, detail)

    companion object {
        /** Convenience for UI previews and batch import: returns the canonical URL or null. */
        fun canonicalUrlOrNull(input: String, options: UrlValidationOptions = UrlValidationOptions()): String? =
            PropertyUrlValidator(options).validate(input).urlOrNull()?.normalized

        fun looksLikeUrl(token: String): Boolean {
            val lowered = token.trim().lowercase(Locale.US)
            if (lowered.startsWith("http://") || lowered.startsWith("https://") || lowered.startsWith("www.")) {
                return lowered.contains('.')
            }
            return false
        }
    }
}
