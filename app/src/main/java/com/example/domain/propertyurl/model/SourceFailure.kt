package com.example.domain.propertyurl.model

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Broad bucket of a failure — drives dashboards, alerting and the user-facing message. */
enum class SourceFailureCategory {
    INPUT,
    POLICY,
    TRANSPORT,
    HTTP,
    ANTI_BOT,
    ACCESS,
    CONTENT,
    PARSING,
    INTERNAL
}

/**
 * What the pipeline is allowed to do next with a failed attempt.
 *  - [RETRYABLE]: retry immediately with exponential backoff.
 *  - [RETRYABLE_AFTER_COOLDOWN]: retry, but with a long cool-down (rate limits, bot walls, outages).
 *  - [PERMANENT]: do not retry; surface to the user/operator.
 */
enum class Retryability { RETRYABLE, RETRYABLE_AFTER_COOLDOWN, PERMANENT }

/**
 * Exhaustive failure taxonomy of the URL intelligence layer.
 *
 * Every failure carries a stable [kind]; the message is technical detail (sanitized), the
 * [userMessage] is what a UI may show.
 */
enum class SourceFailureKind(
    val category: SourceFailureCategory,
    val retryability: Retryability,
    val userMessage: String
) {
    INVALID_URL(SourceFailureCategory.INPUT, Retryability.PERMANENT, "This link is not a valid property URL."),
    MULTIPLE_URLS_FOUND(
        SourceFailureCategory.INPUT,
        Retryability.PERMANENT,
        "The message contains more than one listing link. Import them one by one."
    ),
    SOURCE_NOT_SUPPORTED(
        SourceFailureCategory.POLICY,
        Retryability.PERMANENT,
        "We recognise this website but do not support importing from it yet."
    ),
    SOURCE_DISABLED(
        SourceFailureCategory.POLICY,
        Retryability.RETRYABLE_AFTER_COOLDOWN,
        "Importing from this source is currently switched off."
    ),
    POLICY_DISALLOWED(
        SourceFailureCategory.POLICY,
        Retryability.PERMANENT,
        "Retrieving this page is not allowed by the deployment's fetch policy (robots.txt / terms)."
    ),
    NETWORK_UNREACHABLE(SourceFailureCategory.TRANSPORT, Retryability.RETRYABLE, "Network is unavailable. Retrying shortly."),
    DNS_FAILURE(SourceFailureCategory.TRANSPORT, Retryability.RETRYABLE, "Could not resolve the website address."),
    TLS_FAILURE(SourceFailureCategory.TRANSPORT, Retryability.PERMANENT, "Secure connection to the website failed."),
    TIMEOUT(SourceFailureCategory.TRANSPORT, Retryability.RETRYABLE, "The website took too long to answer."),
    CONNECTION_RESET(SourceFailureCategory.TRANSPORT, Retryability.RETRYABLE, "The connection was interrupted."),
    HTTP_BAD_REQUEST(SourceFailureCategory.HTTP, Retryability.PERMANENT, "The website rejected the request."),
    HTTP_UNAUTHORIZED(SourceFailureCategory.HTTP, Retryability.PERMANENT, "This source requires credentials."),
    HTTP_FORBIDDEN(
        SourceFailureCategory.HTTP,
        Retryability.RETRYABLE_AFTER_COOLDOWN,
        "The website refused the request (possible bot protection)."
    ),
    HTTP_NOT_FOUND(SourceFailureCategory.HTTP, Retryability.PERMANENT, "The listing no longer exists."),
    HTTP_GONE(SourceFailureCategory.HTTP, Retryability.PERMANENT, "The listing was removed."),
    HTTP_RATE_LIMITED(
        SourceFailureCategory.HTTP,
        Retryability.RETRYABLE_AFTER_COOLDOWN,
        "The source is rate limiting us. Will retry later."
    ),
    HTTP_SERVER_ERROR(SourceFailureCategory.HTTP, Retryability.RETRYABLE, "The website is having problems."),
    HTTP_UNEXPECTED(SourceFailureCategory.HTTP, Retryability.RETRYABLE_AFTER_COOLDOWN, "Unexpected response from the website."),
    BOT_PROTECTION_CHALLENGE(
        SourceFailureCategory.ANTI_BOT,
        Retryability.RETRYABLE_AFTER_COOLDOWN,
        "The website served a bot-protection challenge instead of the listing."
    ),
    CAPTCHA_REQUIRED(
        SourceFailureCategory.ANTI_BOT,
        Retryability.RETRYABLE_AFTER_COOLDOWN,
        "The website requires a CAPTCHA to continue."
    ),
    CONSENT_WALL(
        SourceFailureCategory.ANTI_BOT,
        Retryability.RETRYABLE_AFTER_COOLDOWN,
        "The website requires accepting a cookie/consent wall."
    ),
    LOGIN_REQUIRED(SourceFailureCategory.ACCESS, Retryability.PERMANENT, "This listing requires a signed-in account."),
    PAYWALL(SourceFailureCategory.ACCESS, Retryability.PERMANENT, "This listing is behind a paywall."),
    GEO_BLOCKED(SourceFailureCategory.ACCESS, Retryability.PERMANENT, "This listing is not available in our region."),
    EMPTY_RESPONSE(SourceFailureCategory.CONTENT, Retryability.RETRYABLE, "The website returned an empty page."),
    OVERSIZED_RESPONSE(SourceFailureCategory.CONTENT, Retryability.PERMANENT, "The page is too large to process."),
    UNSUPPORTED_CONTENT_TYPE(SourceFailureCategory.CONTENT, Retryability.PERMANENT, "The link does not point to a web page."),
    PARSE_FAILED(
        SourceFailureCategory.PARSING,
        Retryability.RETRYABLE_AFTER_COOLDOWN,
        "The page could not be interpreted. The website probably changed its layout."
    ),
    SCHEMA_DRIFT(
        SourceFailureCategory.PARSING,
        Retryability.RETRYABLE_AFTER_COOLDOWN,
        "The page uses an unrecognised layout — the parser needs updating."
    ),
    MISSING_REQUIRED_FIELDS(
        SourceFailureCategory.PARSING,
        Retryability.PERMANENT,
        "The page did not contain enough property information."
    ),
    MALFORMED_PAYLOAD(SourceFailureCategory.PARSING, Retryability.RETRYABLE, "The page content was malformed."),
    CANCELLED(SourceFailureCategory.INTERNAL, Retryability.PERMANENT, "Import was cancelled."),
    INTERNAL_ERROR(SourceFailureCategory.INTERNAL, Retryability.RETRYABLE, "Internal error while importing the listing."),
    UNKNOWN(SourceFailureCategory.INTERNAL, Retryability.RETRYABLE_AFTER_COOLDOWN, "Unknown error while importing the listing.");
}

/**
 * A single classified failure. Immutable, serializable and free of secrets/PII: [message] is
 * sanitized by [Redaction] before it is stored anywhere.
 */
data class SourceFailure(
    val kind: SourceFailureKind,
    val message: String,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
    val sourceId: String? = null,
    val url: String? = null,
    val adapterId: String? = null,
    val causeType: String? = null,
    val diagnostics: Map<String, String> = emptyMap(),
    val occurredAtEpochMillis: Long = 0L
) {
    val category: SourceFailureCategory get() = kind.category

    val retryability: Retryability get() = kind.retryability

    val isRetryable: Boolean get() = kind.retryability != Retryability.PERMANENT

    val isCooldownRetry: Boolean get() = kind.retryability == Retryability.RETRYABLE_AFTER_COOLDOWN

    /** True when the failure blocks the whole source, not just this URL (bot wall, 429, outage). */
    val isSourceLevel: Boolean
        get() = kind.category == SourceFailureCategory.ANTI_BOT ||
            kind == SourceFailureKind.HTTP_RATE_LIMITED ||
            kind == SourceFailureKind.HTTP_SERVER_ERROR

    val userMessage: String get() = kind.userMessage

    fun toLogLine(): String = buildString {
        append(kind.name)
        append(" [").append(category.name.lowercase(Locale.US)).append(']')
        if (httpStatus != null) append(" status=").append(httpStatus)
        if (sourceId != null) append(" source=").append(sourceId)
        if (url != null) append(" url=").append(url)
        if (causeType != null) append(" cause=").append(causeType)
        append(" retry=").append(retryability.name)
    }

    fun withContext(sourceId: String? = null, adapterId: String? = null, url: String? = null): SourceFailure =
        copy(
            sourceId = this.sourceId ?: sourceId,
            adapterId = this.adapterId ?: adapterId,
            url = this.url ?: url
        )
}

/** Body/header markers of anti-bot and consent interstitials served instead of the listing. */
object AntiBotDetector {

    private val BODY_MARKERS: List<Pair<String, SourceFailureKind>> = listOf(
        "px-captcha" to SourceFailureKind.CAPTCHA_REQUIRED,
        "perimeterx" to SourceFailureKind.CAPTCHA_REQUIRED,
        "press & hold" to SourceFailureKind.CAPTCHA_REQUIRED,
        "press and hold" to SourceFailureKind.CAPTCHA_REQUIRED,
        "captcha" to SourceFailureKind.CAPTCHA_REQUIRED,
        "recaptcha" to SourceFailureKind.CAPTCHA_REQUIRED,
        "hcaptcha" to SourceFailureKind.CAPTCHA_REQUIRED,
        "are you a human" to SourceFailureKind.CAPTCHA_REQUIRED,
        "verify you are human" to SourceFailureKind.CAPTCHA_REQUIRED,
        "unusual traffic" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "just a moment" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "checking your browser" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "cf-challenge" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "cf_chl_" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "attention required" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "access denied" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "enable javascript and cookies to continue" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "datadome" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "incapsula" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "consent.zillow" to SourceFailureKind.CONSENT_WALL,
        "before you continue" to SourceFailureKind.CONSENT_WALL,
        "manage consent preferences" to SourceFailureKind.CONSENT_WALL,
        "sign in to continue" to SourceFailureKind.LOGIN_REQUIRED,
        "log in to view" to SourceFailureKind.LOGIN_REQUIRED,
        "subscribe to view" to SourceFailureKind.PAYWALL,
        "this listing is no longer available" to SourceFailureKind.HTTP_GONE
    )

    private val HEADER_MARKERS: List<Pair<String, SourceFailureKind>> = listOf(
        "cf-mitigated" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "x-px-block" to SourceFailureKind.CAPTCHA_REQUIRED,
        "x-datadome" to SourceFailureKind.BOT_PROTECTION_CHALLENGE,
        "x-iinfo" to SourceFailureKind.BOT_PROTECTION_CHALLENGE
    )

    /**
     * Detects an interstitial page. Only consulted for non-2xx responses and for 2xx responses whose
     * body is suspiciously small (portals serve the challenge with HTTP 200).
     */
    fun detect(
        status: Int,
        headers: Map<String, String>,
        bodySnippet: String,
        isBodySuspiciouslySmall: Boolean
    ): SourceFailureKind? {
        val loweredHeaders = headers.entries.associate { it.key.lowercase(Locale.US) to it.value.lowercase(Locale.US) }
        HEADER_MARKERS.forEach { (marker, kind) ->
            if (loweredHeaders.containsKey(marker)) return kind
        }

        if (status in 200..299 && !isBodySuspiciouslySmall) return null

        val haystack = bodySnippet.lowercase(Locale.US)
        BODY_MARKERS.forEach { (marker, kind) ->
            if (haystack.contains(marker)) return kind
        }

        val serverHeader = loweredHeaders["server"].orEmpty()
        if (status == 403 && (serverHeader.contains("cloudflare") || serverHeader.contains("akamai"))) {
            return SourceFailureKind.BOT_PROTECTION_CHALLENGE
        }
        return null
    }

    const val SMALL_BODY_THRESHOLD_BYTES = 4096
}

/**
 * Maps transport/HTTP/parse observations onto [SourceFailure]. The classifier is the single place
 * where "what went wrong" is decided, which keeps retry decisions and UI copy consistent.
 */
class SourceFailureClassifier(private val now: () -> Long = { System.currentTimeMillis() }) {

    /** @return null when the response is acceptable (2xx/3xx handled by the fetcher). */
    fun fromHttp(
        status: Int,
        headers: Map<String, String> = emptyMap(),
        bodySnippet: String = "",
        bodyLength: Int = bodySnippet.length,
        sourceId: String? = null,
        adapterId: String? = null,
        url: String? = null
    ): SourceFailure? {
        if (status in 200..299) return null

        val retryAfter = headers.entries
            .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()

        val interstitial = AntiBotDetector.detect(
            status = status,
            headers = headers,
            bodySnippet = bodySnippet.take(8192),
            isBodySuspiciouslySmall = bodyLength < AntiBotDetector.SMALL_BODY_THRESHOLD_BYTES
        )

        val kind = when {
            interstitial != null -> interstitial
            status == 400 -> SourceFailureKind.HTTP_BAD_REQUEST
            status == 401 -> SourceFailureKind.HTTP_UNAUTHORIZED
            status == 403 -> SourceFailureKind.HTTP_FORBIDDEN
            status == 404 -> SourceFailureKind.HTTP_NOT_FOUND
            status == 410 -> SourceFailureKind.HTTP_GONE
            status == 429 -> SourceFailureKind.HTTP_RATE_LIMITED
            status in 500..504 -> SourceFailureKind.HTTP_SERVER_ERROR
            status in 505..599 -> SourceFailureKind.HTTP_SERVER_ERROR
            else -> SourceFailureKind.HTTP_UNEXPECTED
        }

        return SourceFailure(
            kind = kind,
            message = "HTTP $status${if (interstitial != null) " (interstitial detected)" else ""}",
            httpStatus = status,
            retryAfterSeconds = retryAfter,
            sourceId = sourceId,
            adapterId = adapterId,
            url = url,
            diagnostics = buildMap {
                if (bodyLength > 0) put("bodyLength", bodyLength.toString())
                headers["content-type"]?.let { put("contentType", it.take(80)) }
            },
            occurredAtEpochMillis = now()
        )
    }

    fun fromThrowable(
        throwable: Throwable,
        sourceId: String? = null,
        adapterId: String? = null,
        url: String? = null
    ): SourceFailure {
        val kind = when (throwable) {
            is CancellationException -> SourceFailureKind.CANCELLED
            is UnknownHostException -> SourceFailureKind.DNS_FAILURE
            is SocketTimeoutException -> SourceFailureKind.TIMEOUT
            is SSLException -> SourceFailureKind.TLS_FAILURE
            is NoRouteToHostException -> SourceFailureKind.NETWORK_UNREACHABLE
            is ConnectException -> SourceFailureKind.NETWORK_UNREACHABLE
            is SocketException -> SourceFailureKind.CONNECTION_RESET
            is java.io.InterruptedIOException -> SourceFailureKind.TIMEOUT
            is java.io.IOException -> SourceFailureKind.NETWORK_UNREACHABLE
            is IllegalArgumentException -> SourceFailureKind.INTERNAL_ERROR
            else -> SourceFailureKind.UNKNOWN
        }
        return SourceFailure(
            kind = kind,
            message = throwable.message ?: throwable.javaClass.simpleName,
            sourceId = sourceId,
            adapterId = adapterId,
            url = url,
            causeType = throwable.javaClass.simpleName,
            occurredAtEpochMillis = now()
        )
    }

    /**
     * Maps a transport-level failure reported by the [com.example.domain.propertyurl.port.HttpFetcher]
     * onto the taxonomy (keeps HTTP concerns out of the port layer).
     */
    fun fromTransport(
        kindName: String,
        message: String,
        causeType: String? = null,
        sourceId: String? = null,
        adapterId: String? = null,
        url: String? = null
    ): SourceFailure {
        val kind = when (kindName) {
            "DNS_FAILURE" -> SourceFailureKind.DNS_FAILURE
            "TIMEOUT" -> SourceFailureKind.TIMEOUT
            "TLS_FAILURE" -> SourceFailureKind.TLS_FAILURE
            "NETWORK_UNREACHABLE" -> SourceFailureKind.NETWORK_UNREACHABLE
            "CONNECTION_RESET" -> SourceFailureKind.CONNECTION_RESET
            "TOO_MANY_REDIRECTS" -> SourceFailureKind.HTTP_UNEXPECTED
            "BLOCKED_REDIRECT" -> SourceFailureKind.POLICY_DISALLOWED
            "UNSUPPORTED_SCHEME" -> SourceFailureKind.INVALID_URL
            "CANCELLED" -> SourceFailureKind.CANCELLED
            else -> SourceFailureKind.INTERNAL_ERROR
        }
        return SourceFailure(
            kind = kind,
            message = message,
            sourceId = sourceId,
            adapterId = adapterId,
            url = url,
            causeType = causeType,
            diagnostics = mapOf("transport" to kindName),
            occurredAtEpochMillis = now()
        )
    }

    fun fromParse(
        parserId: String,
        reason: String,
        kind: SourceFailureKind = SourceFailureKind.PARSE_FAILED,
        missingFields: Set<PropertyField> = emptySet(),
        sourceId: String? = null,
        adapterId: String? = null,
        url: String? = null
    ): SourceFailure = SourceFailure(
        kind = kind,
        message = reason,
        sourceId = sourceId,
        adapterId = adapterId,
        url = url,
        diagnostics = buildMap {
            put("parser", parserId)
            if (missingFields.isNotEmpty()) {
                put("missingFields", missingFields.joinToString(",") { it.name.lowercase(Locale.US) })
            }
        },
        occurredAtEpochMillis = now()
    )

    fun multipleUrls(candidates: Int, url: String? = null): SourceFailure = SourceFailure(
        kind = SourceFailureKind.MULTIPLE_URLS_FOUND,
        message = "$candidates distinct listing links found in the input",
        url = url,
        diagnostics = mapOf("candidates" to candidates.toString()),
        occurredAtEpochMillis = now()
    )

    fun invalidUrl(reason: String, url: String? = null): SourceFailure = SourceFailure(
        kind = SourceFailureKind.INVALID_URL,
        message = reason,
        url = url,
        occurredAtEpochMillis = now()
    )

    fun policyDisallowed(reason: String, sourceId: String? = null, url: String? = null): SourceFailure =
        SourceFailure(
            kind = SourceFailureKind.POLICY_DISALLOWED,
            message = reason,
            sourceId = sourceId,
            url = url,
            occurredAtEpochMillis = now()
        )

    fun notSupported(sourceId: String, displayName: String, url: String? = null): SourceFailure = SourceFailure(
        kind = SourceFailureKind.SOURCE_NOT_SUPPORTED,
        message = "$displayName is recognised but has no adapter yet",
        sourceId = sourceId,
        url = url,
        diagnostics = mapOf("source" to sourceId),
        occurredAtEpochMillis = now()
    )

    fun internal(message: String, sourceId: String? = null, adapterId: String? = null): SourceFailure =
        SourceFailure(
            kind = SourceFailureKind.INTERNAL_ERROR,
            message = message,
            sourceId = sourceId,
            adapterId = adapterId,
            occurredAtEpochMillis = now()
        )
}
