package com.example.domain.automation

import kotlinx.coroutines.CancellationException

/**
 * Decides whether a failure is worth retrying.
 *
 * Matching is done on simple class names and messages on purpose:
 *  - it keeps this class free of Android/Retrofit types, so it is unit-testable on the JVM,
 *  - it survives dependency upgrades (an exception type we do not know still degrades safely).
 *
 * The default is [FailureKind.RETRYABLE]: unknown failures are retried a bounded number of times
 * and then escalated to `FAILED_TERMINAL` by the state machine, which is safer than silently
 * dropping work.
 */
object FailureClassifier {

    private val HARD_TERMINAL_NAMES = setOf(
        "FileNotFoundException",
        "IllegalArgumentException",
        "IllegalStateException",
        "UnsupportedOperationException",
        "ClassCastException",
        "NullPointerException",
        "NumberFormatException",
        "SecurityException",
        "GoogleAuthException",
        "UserRecoverableAuthException",
        "AuthException"
    )

    private val BLOCKED_NAMES = setOf(
        "ValidationException",
        "PreSendValidationException",
        "BlockedException"
    )

    private val RETRYABLE_NAMES = setOf(
        "IOException",
        "InterruptedIOException",
        "SocketTimeoutException",
        "SocketException",
        "ConnectException",
        "UnknownHostException",
        "NoRouteToHostException",
        "SSLException",
        "SSLHandshakeException",
        "TimeoutException",
        "TimeoutCancellationException",
        "HttpException",
        "RetryableException",
        "ServiceUnavailableException",
        "SQLiteException",
        "SQLiteDiskIOException",
        "SQLiteFullException",
        "SQLiteDatabaseLockedException",
        "SQLiteBusyException",
        "SQLiteLockedException",
        "DeadlockDetectedException",
        "NetworkOnMainThreadException"
    )

    private val RETRYABLE_MESSAGE_HINTS = listOf(
        "timeout", "timed out", "network", "offline", "no connection", "connection reset",
        "connection refused", "unavailable", "temporarily", "try again", "rate limit",
        "too many requests", "server error", "internal error", "bad gateway", "gateway timeout",
        "service unavailable", "database is locked", "disk i/o", "eof", "reset by peer",
        "failed to connect", "socket", "host is unreachable", "quota exceeded"
    )

    private val BLOCKED_MESSAGE_HINTS = listOf(
        "not connected", "disconnected", "authorization required", "unauthorized", "not authorized",
        "invalid email", "not a valid email", "blank", "missing", "no valid", "reconnect",
        "permission denied", "configuration", "not configured", "expired token", "invalid_grant"
    )

    private val TERMINAL_MESSAGE_HINTS = listOf(
        "non-positive", "does not exist", "not found", "unsupported", "corrupt", "malformed"
    )

    fun classify(error: Throwable?): FailureKind {
        if (error == null) return FailureKind.RETRYABLE
        if (error is CancellationException) return FailureKind.CANCELLED

        val names = classNames(error)
        if (names.any { it in BLOCKED_NAMES }) return FailureKind.BLOCKED
        if (names.any { it in HARD_TERMINAL_NAMES }) return FailureKind.TERMINAL

        httpStatus(error)?.let { status ->
            return when {
                status == 408 || status == 429 -> FailureKind.RETRYABLE
                status in 400..499 -> FailureKind.BLOCKED
                status >= 500 -> FailureKind.RETRYABLE
                else -> FailureKind.RETRYABLE
            }
        }

        if (names.any { it in RETRYABLE_NAMES }) return FailureKind.RETRYABLE

        error.message?.let { return classifyMessage(it) }
        return FailureKind.RETRYABLE
    }

    fun isRetryable(error: Throwable?): Boolean = classify(error) == FailureKind.RETRYABLE

    /** Message-only classification, used when an operation reports failure through a boolean/string. */
    fun classifyMessage(message: String?): FailureKind {
        if (message.isNullOrBlank()) return FailureKind.RETRYABLE
        val lower = message.lowercase()
        if (BLOCKED_MESSAGE_HINTS.any { lower.contains(it) }) return FailureKind.BLOCKED
        if (RETRYABLE_MESSAGE_HINTS.any { lower.contains(it) }) return FailureKind.RETRYABLE
        if (TERMINAL_MESSAGE_HINTS.any { lower.contains(it) }) return FailureKind.TERMINAL
        return FailureKind.RETRYABLE
    }

    private fun classNames(error: Throwable): List<String> {
        val names = mutableListOf<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 12) {
            names += current.javaClass.simpleName
            current = current.cause
            depth++
        }
        return names
    }

    /**
     * Reads `code()` reflectively so we do not need a compile-time dependency on Retrofit.
     * The exception chain is walked because HTTP errors are frequently wrapped.
     */
    private fun httpStatus(error: Throwable): Int? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 12) {
            val code = runCatching {
                current.javaClass.getMethod("code").invoke(current) as? Int
            }.getOrNull()
            if (code != null && code in 100..599) return code
            current = current.cause
            depth++
        }
        return null
    }
}
