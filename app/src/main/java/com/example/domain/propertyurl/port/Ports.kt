package com.example.domain.propertyurl.port

import com.example.domain.propertyurl.model.SourceFailureKind
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID

/** Injectable time source: every timestamp the layer writes goes through it (deterministic tests). */
interface Clock {
    fun nowEpochMillis(): Long
}

class SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

/** Deterministic id generator (jobs, requests). */
interface IdGenerator {
    fun newId(prefix: String): String
}

class UuidIdGenerator : IdGenerator {
    override fun newId(prefix: String): String =
        if (prefix.isEmpty()) UUID.randomUUID().toString() else "$prefix-" + UUID.randomUUID().toString()
}

/**
 * Telemetry port. Implementations must be cheap and non-blocking; the layer treats them as fire and
 * forget. Events never contain credentials, cookies or unredacted URLs.
 */
interface TelemetrySink {
    fun record(event: TelemetryEvent)
}

data class TelemetryEvent(
    val name: String,
    val jobId: String? = null,
    val requestId: String? = null,
    val sourceId: String? = null,
    val adapterId: String? = null,
    /** Redacted URL (see `PropertyUrl.redacted()`). */
    val url: String? = null,
    val outcome: String? = null,
    val failureKind: SourceFailureKind? = null,
    val attempt: Int? = null,
    val durationMillis: Long? = null,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Injectable sleep used by the retry/backoff loop, so tests never actually wait.
 * Production wiring uses [CoroutineSleeper].
 */
fun interface Sleeper {
    suspend fun sleep(millis: Long)
}

class CoroutineSleeper : Sleeper {
    override suspend fun sleep(millis: Long) {
        if (millis > 0) delay(millis)
    }
}

/** Records requested sleeps; used by unit tests to assert backoff without waiting. */
object NoopSleeper : Sleeper {
    override suspend fun sleep(millis: Long) = Unit
}

object NoopTelemetry : TelemetrySink {
    override fun record(event: TelemetryEvent) = Unit
}

/**
 * Runtime credentials for credentialed sources (MLS/IDX, partner APIs).
 *
 * Secrets are supplied by the host application (server side / secure storage) and are never part of
 * the repository, the APK assets or the job store. [toString] is redacted so an accidental log or
 * crash report cannot leak a token.
 */
data class SourceCredentials(
    val authHeaderName: String? = null,
    val authHeaderValue: String? = null,
    val apiKey: String? = null,
    val cookieHeader: String? = null,
    val extraHeaders: Map<String, String> = emptyMap()
) {
    val isEmpty: Boolean
        get() = authHeaderValue.isNullOrBlank() && apiKey.isNullOrBlank() &&
            cookieHeader.isNullOrBlank() && extraHeaders.isEmpty()

    /** HTTP headers to apply; the returned map is the only shape credentials ever take. */
    fun asHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        if (!authHeaderValue.isNullOrBlank()) {
            headers[authHeaderName?.takeIf { it.isNotBlank() } ?: "Authorization"] = authHeaderValue
        }
        if (!apiKey.isNullOrBlank()) headers["X-Api-Key"] = apiKey
        if (!cookieHeader.isNullOrBlank()) headers["Cookie"] = cookieHeader
        extraHeaders.forEach { (key, value) -> if (value.isNotBlank()) headers[key] = value }
        return headers
    }

    override fun toString(): String = "SourceCredentials(redacted, headers=${asHeaders().keys.joinToString(",")})"

    companion object {
        val NONE = SourceCredentials()
    }
}

/** Supplies credentials at runtime; returning null means "not configured" (never "empty token"). */
interface CredentialProvider {
    fun credentialsFor(sourceId: String): SourceCredentials?

    companion object {
        val NONE = object : CredentialProvider {
            override fun credentialsFor(sourceId: String): SourceCredentials? = null
        }

        fun of(vararg entries: Pair<String, SourceCredentials>): CredentialProvider {
            val map = entries.associate { it.first.lowercase(Locale.US) to it.second }
            return object : CredentialProvider {
                override fun credentialsFor(sourceId: String): SourceCredentials? = map[sourceId.lowercase(Locale.US)]
            }
        }
    }
}
