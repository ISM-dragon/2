package com.example.urlintelligence.idempotency

import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.retry.Clock

/**
 * SHA-256 helpers used for identity.
 *
 * A *content digest* answers "is this the same data as last time?" independent of the URL it
 * arrived on, which is what makes imports idempotent across tracking parameters, vanity URLs,
 * mobile variants and multiple portals pointing at the same house.
 */
object Digests {

    fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.toString()
    }

    /** Digest of the extracted field values, in stable field order. */
    fun fieldsDigest(sourceId: String, values: Map<PropertyField, Any?>): String {
        val canonical = values.entries
            .filter { it.value != null }
            .sortedBy { it.key.stableName }
            .joinToString(separator = "|") { (field, value) ->
                field.stableName + "=" + render(value)
            }
        return sha256Hex("$sourceId::$canonical")
    }

    private fun render(value: Any?): String = when (value) {
        null -> ""
        is Collection<*> -> value.joinToString(",")
        else -> value.toString().trim().lowercase()
    }

    private const val HEX: String = "0123456789abcdef"
}

/** Why an import produced a result, from an idempotency point of view. */
enum class ImportIdempotency {
    /** The listing was fetched and imported now. */
    FRESH,

    /** The same canonical URL was already imported; the previous result was reused. */
    REPLAYED_URL,

    /** The same canonical property was already imported (possibly from another URL). */
    REPLAYED_PROPERTY
}

/**
 * One recorded import of one canonical property.
 *
 * @property contentDigest digest of the extracted data at [recordedAtEpochMillis]; comparing it
 *           with a fresh parse tells the caller whether anything actually changed.
 * @property value the caller's stored payload (e.g. the previous import result).
 */
data class ImportLedgerEntry<T>(
    val canonicalId: String,
    val sourceId: String,
    val canonicalUrl: String,
    val contentDigest: String,
    val recordedAtEpochMillis: Long,
    val imports: Int = 1,
    val value: T? = null
)

/**
 * Property-level idempotency.
 *
 * URL-level idempotency (see [IdempotencyStore]) answers "have I imported this URL?". The
 * ledger answers the harder question: "have I already imported this *property*, whatever URL
 * it arrived on?" — `…/homedetails/…_zpid/`, a share link with tracking noise, a mobile
 * subdomain and a canonicalised variant all collapse onto one canonical id.
 *
 * Implementations must be safe under concurrent access.
 */
interface ImportLedger<T> {
    fun find(canonicalId: String): ImportLedgerEntry<T>?

    /** Inserts or refreshes an entry, returning the stored version. */
    fun record(entry: ImportLedgerEntry<T>): ImportLedgerEntry<T>

    fun forget(canonicalId: String)

    fun size(): Int
}

/** Process-local ledger. Back it with Room in production to survive process death. */
class InMemoryImportLedger<T>(
    private val clock: Clock = Clock.SYSTEM,
    private val ttlMillis: Long = 7 * 24 * 60 * 60 * 1000L,
    private val maxEntries: Int = 5_000
) : ImportLedger<T> {

    private val lock = Any()
    private val entries = LinkedHashMap<String, ImportLedgerEntry<T>>()

    override fun find(canonicalId: String): ImportLedgerEntry<T>? = synchronized(lock) {
        purgeExpired()
        entries[canonicalId]
    }

    override fun record(entry: ImportLedgerEntry<T>): ImportLedgerEntry<T> = synchronized(lock) {
        purgeExpired()
        val existing = entries[entry.canonicalId]
        val stored = entry.copy(
            imports = (existing?.imports ?: 0) + 1,
            recordedAtEpochMillis = if (entry.recordedAtEpochMillis > 0L) {
                entry.recordedAtEpochMillis
            } else {
                clock.now()
            }
        )
        entries[entry.canonicalId] = stored
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        stored
    }

    override fun forget(canonicalId: String): Unit = synchronized(lock) {
        entries.remove(canonicalId)
        Unit
    }

    override fun size(): Int = synchronized(lock) {
        purgeExpired()
        entries.size
    }

    fun all(): List<ImportLedgerEntry<T>> = synchronized(lock) {
        purgeExpired()
        entries.values.toList()
    }

    private fun purgeExpired() {
        if (ttlMillis <= 0L) return
        val now = clock.now()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.recordedAtEpochMillis > ttlMillis) iterator.remove()
        }
    }
}
