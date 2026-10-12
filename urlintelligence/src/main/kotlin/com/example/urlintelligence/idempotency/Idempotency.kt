package com.example.urlintelligence.idempotency

import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.retry.Clock
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Deterministic key for an import request.
 *
 * Derived from the canonical URL so that the same listing pasted with different
 * tracking parameters, casing or fragment produces the same job — and therefore
 * only one network fetch and one database write.
 */
data class IdempotencyKey(val value: String) {

    override fun toString(): String = value

    companion object {
        const val DEFAULT_NAMESPACE: String = "property-url"

        fun forUrl(canonicalUrl: String, namespace: String = DEFAULT_NAMESPACE): IdempotencyKey {
            return IdempotencyKey("$namespace:" + sha256(identityOf(canonicalUrl)))
        }

        /**
         * Identity form of a canonical listing URL: the string that decides whether two imports are
         * the same listing.
         *
         * A canonical URL keeps the host spelling the provider serves (that is what is fetched and what
         * robots.txt governs), so `www.zillow.com` and `zillow.com` are different canonical URLs for the
         * same listing. The identity drops the `www.` label and lowercases scheme and host. It keeps the
         * path, the query and any non-default port exactly as canonicalised, because case and
         * parameters can separate genuinely different listings; that is deliberately not normalised away.
         */
        fun identityOf(canonicalUrl: String): String {
            val trimmed = canonicalUrl.trim()
            val uri = try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                return trimmed.lowercase(Locale.US)
            }
            val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.")
                ?: return trimmed.lowercase(Locale.US)
            val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty()
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return "$scheme://$host$port$path$query"
        }

        /**
         * Stable identity of a property within the whole system.
         *
         * Prefers the source-native listing id (deterministic across URL rewrites and
         * tracking noise); falls back to a hash of the canonical URL when the source
         * does not expose an id.
         */
        fun canonicalPropertyId(
            sourceId: String,
            sourcePropertyId: String?,
            canonicalUrl: String
        ): String {
            val id = sourcePropertyId?.trim().orEmpty()
            return if (id.isBlank()) {
                "${sourceId.lowercase()}:url-hash:" + sha256(identityOf(canonicalUrl)).take(16)
            } else {
                "${sourceId.lowercase()}:${id.lowercase()}"
            }
        }

        private fun sha256(input: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(bytes.size * 2)
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return hex.toString()
        }

        private const val HEX: String = "0123456789abcdef"
    }
}

enum class IdempotencyState { IN_FLIGHT, COMPLETED, FAILED }

data class IdempotencyRecord<T>(
    val key: IdempotencyKey,
    val state: IdempotencyState,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val value: T? = null,
    val failure: SourceFailure? = null
) {
    val isTerminal: Boolean
        get() = state != IdempotencyState.IN_FLIGHT
}

/**
 * Storage contract for in-flight/completed import results.
 *
 * The provided in-memory implementation is process-local; a production build can
 * back this with the Room table that already stores property records, keeping the
 * same semantics (reserve -> complete/fail -> release).
 */
interface IdempotencyStore<T> {
    fun get(key: IdempotencyKey): IdempotencyRecord<T>?

    /** Reserves [key]; returns false when another import already owns it. */
    fun putIfAbsent(key: IdempotencyKey, record: IdempotencyRecord<T>): Boolean

    fun complete(key: IdempotencyKey, value: T)

    fun fail(key: IdempotencyKey, failure: SourceFailure)

    /** Frees an IN_FLIGHT/FAILED reservation so the caller may retry. */
    fun release(key: IdempotencyKey)

    fun size(): Int
}

class InMemoryIdempotencyStore<T>(
    private val clock: Clock = Clock.SYSTEM,
    private val ttlMillis: Long = 24 * 60 * 60 * 1000L
) : IdempotencyStore<T> {

    private val lock = Any()
    private val records: LinkedHashMap<String, IdempotencyRecord<T>> = LinkedHashMap()

    override fun get(key: IdempotencyKey): IdempotencyRecord<T>? = synchronized(lock) {
        purgeExpired()
        records[key.value]
    }

    override fun putIfAbsent(key: IdempotencyKey, record: IdempotencyRecord<T>): Boolean =
        synchronized(lock) {
            purgeExpired()
            if (records.containsKey(key.value)) false
            else {
                records[key.value] = record
                true
            }
        }

    override fun complete(key: IdempotencyKey, value: T) = synchronized(lock) {
        val existing = records[key.value]
        val now = clock.now()
        records[key.value] = IdempotencyRecord(
            key = key,
            state = IdempotencyState.COMPLETED,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
            value = value
        )
    }

    override fun fail(key: IdempotencyKey, failure: SourceFailure) = synchronized(lock) {
        val existing = records[key.value]
        val now = clock.now()
        records[key.value] = IdempotencyRecord(
            key = key,
            state = IdempotencyState.FAILED,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
            failure = failure
        )
    }

    override fun release(key: IdempotencyKey) {
        synchronized(lock) {
            records.remove(key.value)
        }
    }

    override fun size(): Int = synchronized(lock) {
        purgeExpired()
        records.size
    }

    private fun purgeExpired() {
        if (ttlMillis <= 0) return
        val now = clock.now()
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.updatedAtEpochMillis > ttlMillis) iterator.remove()
        }
    }
}
