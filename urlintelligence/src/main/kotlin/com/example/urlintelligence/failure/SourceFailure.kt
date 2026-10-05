package com.example.urlintelligence.failure

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Coarse bucket used by retry, alerting and dashboards. */
enum class FailureCategory {
    /** Worth retrying: network blips, 5xx, rate limits. */
    TRANSIENT,

    /** Retrying will not help: 404, gone, unparseable payload. */
    PERMANENT,

    /** We are not allowed to fetch this (robots/ToS/opt-in, 401/403, bot walls). */
    POLICY,

    /** The caller gave us something unusable (bad URL, unknown source). */
    CLIENT,

    /** Not enough signal to classify — treated conservatively. */
    UNCLASSIFIED
}

/**
 * Typed failure taxonomy.
 *
 * Every failure carries a [category] and a [retryable] flag so the retry executor
 * never has to string-match exception messages, and a [code] that is safe to log.
 */
sealed class SourceFailure {

    abstract val category: FailureCategory
    abstract val retryable: Boolean
    abstract val detail: String

    open val code: String
        get() = "SOURCE_FAILURE"

    data class InvalidUrl(val reason: String) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.CLIENT
        override val retryable: Boolean = false
        override val detail: String = "Invalid property URL: $reason"
        override val code: String = "INVALID_URL"
    }

    data class UnsupportedSource(val sourceId: String, val host: String?) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.CLIENT
        override val retryable: Boolean = false
        override val detail: String =
            "No adapter registered for source '$sourceId'${host?.let { " ($it)" } ?: ""}"
        override val code: String = "UNSUPPORTED_SOURCE"
    }

    data class PolicyBlocked(val sourceId: String, val rule: String) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.POLICY
        override val retryable: Boolean = false
        override val detail: String = "Source '$sourceId' blocked by policy: $rule"
        override val code: String = "POLICY_BLOCKED"
    }

    data class Network(override val detail: String, val causeType: String? = null) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.TRANSIENT
        override val retryable: Boolean = true
        override val code: String = "NETWORK"
    }

    data class Timeout(val timeoutMillis: Long) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.TRANSIENT
        override val retryable: Boolean = true
        override val detail: String = "Request timed out after ${timeoutMillis}ms"
        override val code: String = "TIMEOUT"
    }

    data class RateLimited(override val detail: String, val retryAfterMillis: Long?) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.TRANSIENT
        override val retryable: Boolean = true
        override val code: String = "RATE_LIMITED"
    }

    data class HttpStatus(
        val statusCode: Int,
        override val detail: String,
        val retryAfterMillis: Long? = null
    ) : SourceFailure() {
        override val category: FailureCategory
            get() = when (statusCode) {
                408, 425, 429 -> FailureCategory.TRANSIENT
                in 500..599 -> FailureCategory.TRANSIENT
                in 400..499 -> FailureCategory.PERMANENT
                else -> FailureCategory.UNCLASSIFIED
            }
        override val retryable: Boolean
            get() = category == FailureCategory.TRANSIENT
        override val code: String = "HTTP_$statusCode"
    }

    data class Blocked(val reason: BlockReason, override val detail: String) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.POLICY
        override val retryable: Boolean = false
        override val code: String = "BLOCKED_${reason.name}"
    }

    data class AuthRequired(override val detail: String) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.POLICY
        override val retryable: Boolean = false
        override val code: String = "AUTH_REQUIRED"
    }

    data class NotFound(override val detail: String) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.PERMANENT
        override val retryable: Boolean = false
        override val code: String = "NOT_FOUND"
    }

    data class PayloadTooLarge(val bytes: Long, val maxBytes: Long) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.PERMANENT
        override val retryable: Boolean = false
        override val detail: String = "Payload of $bytes bytes exceeds limit of $maxBytes bytes"
        override val code: String = "PAYLOAD_TOO_LARGE"
    }

    data class ParseError(override val detail: String, val extractor: String? = null) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.PERMANENT
        override val retryable: Boolean = false
        override val code: String = "PARSE_ERROR"
    }

    data class IncompleteData(val missingFields: List<String>) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.PERMANENT
        override val retryable: Boolean = false
        override val detail: String = "Resolved data is incomplete: ${missingFields.joinToString()}"
        override val code: String = "INCOMPLETE_DATA"
    }

    data class Conflict(override val detail: String) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.TRANSIENT
        override val retryable: Boolean = false
        override val code: String = "CONFLICT"
    }

    data class Unknown(override val detail: String, val causeType: String? = null) : SourceFailure() {
        override val category: FailureCategory = FailureCategory.UNCLASSIFIED
        override val retryable: Boolean = false
        override val code: String = "UNKNOWN"
    }

    /** Redacted, log-safe single line. Never contains credentials or payload bodies. */
    fun toLogString(): String = "$code category=$category retryable=$retryable detail=$detail"
}

enum class BlockReason {
    ROBOTS_DISALLOWED,
    TERMS_OF_SERVICE,
    CAPTCHA,
    BOT_WALL,
    GEO_RESTRICTED,
    LOGIN_REQUIRED
}

/** Maps transport-level signals into the typed taxonomy. */
object SourceFailureClassifier {

    fun fromThrowable(throwable: Throwable): SourceFailure = when (throwable) {
        is SocketTimeoutException -> SourceFailure.Timeout(-1L)
        is UnknownHostException -> SourceFailure.Network("host could not be resolved", throwable.javaClass.simpleName)
        is IOException -> SourceFailure.Network(throwable.message ?: "i/o failure", throwable.javaClass.simpleName)
        is InterruptedException -> SourceFailure.Network("request interrupted", throwable.javaClass.simpleName)
        else -> SourceFailure.Unknown(throwable.message ?: "unexpected failure", throwable.javaClass.simpleName)
    }

    fun fromStatus(statusCode: Int, headers: Map<String, String> = emptyMap()): SourceFailure {
        val retryAfter = parseRetryAfter(headers)
        return when (statusCode) {
            401 -> SourceFailure.AuthRequired("source returned 401")
            403 -> SourceFailure.Blocked(BlockReason.BOT_WALL, "source returned 403")
            404, 410 -> SourceFailure.NotFound("source returned $statusCode")
            429 -> SourceFailure.RateLimited("source returned 429", retryAfter)
            451 -> SourceFailure.PolicyBlocked("unknown", "unavailable for legal reasons (451)")
            else -> SourceFailure.HttpStatus(statusCode, "source returned $statusCode", retryAfter)
        }
    }

    /** Detects bot walls / CAPTCHA pages that arrive with a 200 status. */
    fun detectSoftBlock(statusCode: Int, body: String): SourceFailure? {
        if (statusCode != 200) return null
        if (body.length > 2_000_000) return null
        val lowered = body.lowercase()
        val signals = listOf(
            "are you a human", "verify you are human", "captcha", "recaptcha",
            "px-captcha", "access denied", "request blocked", "unusual traffic"
        )
        return if (signals.any { lowered.contains(it) }) {
            SourceFailure.Blocked(BlockReason.CAPTCHA, "anti-bot page served with HTTP 200")
        } else null
    }

    fun parseRetryAfter(headers: Map<String, String>): Long? {
        val raw = headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value
            ?: return null
        val seconds = raw.trim().toLongOrNull()
        if (seconds != null) return (seconds * 1000L).coerceIn(0L, 300_000L)
        return null
    }
}
