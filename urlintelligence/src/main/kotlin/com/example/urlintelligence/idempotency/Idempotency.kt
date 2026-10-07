package com.example.urlintelligence.idempotency

import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.retry.Clock

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
            return IdempotencyKey("$namespace:" + sha256(dedupForm(canonicalUrl)))
        }

        /**
         * Form used for identity hashing: lowercased, trimmed, and with a leading `www.`
         * removed, so a share link that spells the host differently is one import. This is
         * deliberately *not* used to rewrite the URL we fetch — the canonical URL keeps the
         * host the provider serves.
         */
        internal fun dedupForm(url: String): String {
            val trimmed = url.trim().lowercase()
            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd < 0) return trimmed.removePrefix("www.")
            val hostStart = schemeEnd + 3
            val hostEnd = trimmed.indexOfFirst(hostStart) { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) trimmed.length else it }
            val host = trimmed.substring(hostStart, hostEnd)
            if (!host.startsWith("www.")) return trimmed
            return trimmed.take(hostStart) + host.removePrefix("www.") + trimmed.substring(hostEnd)
        }

        private fun CharSequence.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
            for (index in from until length) if (predicate(this[index])) return index
            return -1
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
                "${sourceId.lowercase()}:url-hash:" + sha256(dedupForm(canonicalUrl)).take(16)
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

    /**
     * Entry plus the moment the *store* wrote it. Expiry is measured from that (not from the
     * timestamps the caller supplies), so a record can never be purged in the same breath as it
     * is written — which is exactly what would silently break reservation exclusivity.
     */
    private class Stored<T>(val record: IdempotencyRecord<T>, val storedAtEpochMillis: Long)

    private val records: LinkedHashMap<String, Stored<T>> = LinkedHashMap()

    override fun get(key: IdempotencyKey): IdempotencyRecord<T>? = synchronized(lock) {
        purgeExpired()
        records[key.value]?.record
    }

    override fun putIfAbsent(key: IdempotencyKey, record: IdempotencyRecord<T>): Boolean =
        synchronized(lock) {
            purgeExpired()
            if (records.containsKey(key.value)) false
            else {
                records[key.value] = Stored(record, clock.now())
                true
            }
        }

    override fun complete(key: IdempotencyKey, value: T) = synchronized(lock) {
        val existing = records[key.value]?.record
        val now = clock.now()
        records[key.value] = Stored(
            IdempotencyRecord(
                key = key,
                state = IdempotencyState.COMPLETED,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                value = value
            ),
            now
        )
    }

    override fun fail(key: IdempotencyKey, failure: SourceFailure) = synchronized(lock) {
        val existing = records[key.value]?.record
        val now = clock.now()
        records[key.value] = Stored(
            IdempotencyRecord(
                key = key,
                state = IdempotencyState.FAILED,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                failure = failure
            ),
            now
        )
    }

    override fun release(key: IdempotencyKey): Unit = synchronized(lock) {
        records.remove(key.value)
        Unit
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
            if (now - entry.value.storedAtEpochMillis > ttlMillis) iterator.remove()
        }
    }
}
