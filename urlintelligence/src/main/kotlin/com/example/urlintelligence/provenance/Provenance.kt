package com.example.urlintelligence.provenance

import com.example.urlintelligence.model.PropertyField

/** How a value was obtained. Drives both trust and debugging. */
enum class ExtractionMethod {
    /** schema.org / JSON-LD / embedded JSON documents. */
    STRUCTURED_DATA,

    /** <meta og:*>, <title>, canonical link tags. */
    META_TAG,

    /** DOM attribute or data-* selector declared by a source adapter. */
    DOM_SELECTOR,

    /** Derived from the URL shape itself (e.g. /home/<id>). */
    URL_PATTERN,

    /** Text regex heuristic over the raw document. */
    REGEX_HEURISTIC,

    /** First-party/licensed API response. */
    SOURCE_API,

    /** Computed from other fields (e.g. price per sqft). */
    DERIVED,

    /** Entered or corrected by a human. */
    MANUAL
}

/** Confidence anchors. Adapters should use these instead of magic numbers. */
object Confidence {
    const val EXACT: Double = 1.0
    const val HIGH: Double = 0.9
    const val MEDIUM: Double = 0.7
    const val LOW: Double = 0.45
    const val UNVERIFIED: Double = 0.2
}

/**
 * Immutable audit record for a single field of a canonical property.
 *
 * @property rawValue the exact string found in the document, kept for re-parsing
 *                    and debugging when normalization rules change.
 * @property parserId id of the versioned parser that produced the value (e.g. `zillow.html`).
 * @property parserVersion version of that parser — a markup change bumps it, so a stale or
 *           drifted parse is visible per field.
 * @property documentDigest SHA-256 of the document the value was read from (reproducibility).
 * @property verification how well the value is verified; see [VerificationLevel]. Defaults to
 *           [VerificationLevel.UNVERIFIED] so a record only ever claims what it can prove.
 */
data class FieldProvenance(
    val field: PropertyField,
    val sourceId: String,
    val sourceUrl: String,
    val extractor: String,
    val method: ExtractionMethod,
    val confidence: Double,
    val rawValue: String?,
    val extractedAtEpochMillis: Long,
    val notes: String? = null,
    val parserId: String? = null,
    val parserVersion: String? = null,
    val documentDigest: String? = null,
    val verification: VerificationLevel = VerificationLevel.UNVERIFIED
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(confidence in 0.0..1.0) { "confidence must be within 0.0..1.0, was $confidence" }
    }

    /** `zillow.html@3`, or just the extractor for unversioned/manual values. */
    val parserRef: String
        get() = when {
            parserId != null && parserVersion != null -> "$parserId@$parserVersion"
            else -> extractor
        }

    val isLiveVerified: Boolean
        get() = verification == VerificationLevel.LIVE_FETCH_VERIFIED

    /** True when this record should win over [other] under "trust the better signal" rules. */
    fun isMoreTrustworthyThan(other: FieldProvenance): Boolean {
        if (confidence != other.confidence) return confidence > other.confidence
        return extractedAtEpochMillis >= other.extractedAtEpochMillis
    }
}

/** Conflict resolution strategy when two sources (or two extractors) produce the same field. */
sealed class MergePolicy {
    /** Highest confidence wins; ties prefer the existing value (stable/deterministic). */
    object HighestConfidence : MergePolicy()

    /** First writer wins — useful when the first source is the authoritative one. */
    object PreferExisting : MergePolicy()

    /** Last writer wins — useful for "newest data wins" pipelines. */
    object PreferIncoming : MergePolicy()

    /** Explicit ranking of source ids; unknown sources rank last. */
    data class SourcePriority(val rankedSourceIds: List<String>) : MergePolicy()
}

/** Immutable map of field -> provenance. All mutations return a new instance. */
class ProvenanceMap(entries: Map<PropertyField, FieldProvenance> = emptyMap()) {

    private val entries: LinkedHashMap<PropertyField, FieldProvenance> = LinkedHashMap(entries)

    operator fun get(field: PropertyField): FieldProvenance? = entries[field]

    fun entries(): Map<PropertyField, FieldProvenance> = entries.toMap()

    fun fields(): Set<PropertyField> = entries.keys.toSet()

    val size: Int
        get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun isNotEmpty(): Boolean = entries.isNotEmpty()

    fun contains(field: PropertyField): Boolean = entries.containsKey(field)

    /** Returns a new map with [entry] merged in under [policy]. */
    fun with(entry: FieldProvenance, policy: MergePolicy = MergePolicy.HighestConfidence): ProvenanceMap {
        val next = LinkedHashMap(entries)
        next[entry.field] = pick(next[entry.field], entry, policy)
        return ProvenanceMap(next)
    }

    /** Returns a new map with every entry of [other] merged in under [policy]. */
    fun merge(other: ProvenanceMap, policy: MergePolicy = MergePolicy.HighestConfidence): ProvenanceMap {
        if (other.isEmpty()) return this
        val next = LinkedHashMap(entries)
        other.entries().values.forEach { incoming ->
            next[incoming.field] = pick(next[incoming.field], incoming, policy)
        }
        return ProvenanceMap(next)
    }

    /** Entries for a single source, e.g. to explain "what did Zillow actually give us". */
    fun bySource(sourceId: String): ProvenanceMap =
        ProvenanceMap(entries.filter { (_, v) -> v.sourceId == sourceId })

    /** Entries at or above a verification level, e.g. only live-verified values. */
    fun withVerificationAtLeast(level: VerificationLevel): ProvenanceMap =
        ProvenanceMap(entries.filter { (_, v) -> v.verification.rank >= level.rank })

    /** True when every entry carries a usable provenance record. */
    fun isCompleteFor(fields: Set<PropertyField>): Boolean = fields.all { contains(it) }

    override fun toString(): String = "ProvenanceMap(fields=${entries.keys.size})"

    companion object {
        fun pick(
            current: FieldProvenance?,
            incoming: FieldProvenance,
            policy: MergePolicy
        ): FieldProvenance {
            if (current == null) return incoming
            return when (policy) {
                MergePolicy.PreferExisting -> current
                MergePolicy.PreferIncoming -> incoming
                MergePolicy.HighestConfidence ->
                    if (incoming.isMoreTrustworthyThan(current)) incoming else current
                is MergePolicy.SourcePriority -> {
                    val currentRank = policy.rankedSourceIds.indexOf(current.sourceId).let {
                        if (it < 0) Int.MAX_VALUE else it
                    }
                    val incomingRank = policy.rankedSourceIds.indexOf(incoming.sourceId).let {
                        if (it < 0) Int.MAX_VALUE else it
                    }
                    when {
                        incomingRank < currentRank -> incoming
                        currentRank < incomingRank -> current
                        else -> if (incoming.isMoreTrustworthyThan(current)) incoming else current
                    }
                }
            }
        }
    }
}
