package com.example.urlintelligence.model

import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import com.example.urlintelligence.provenance.FieldProvenance
import com.example.urlintelligence.provenance.ProvenanceMap

/** A single extracted value together with everything needed to build provenance for it. */
data class DraftField(
    val field: PropertyField,
    val value: Any?,
    val method: ExtractionMethod,
    val confidence: Double,
    val extractor: String,
    val rawValue: String?,
    val note: String?
)

/**
 * Mutable accumulator used while parsing a source document.
 *
 * Adapters write into a draft; the [com.example.urlintelligence.normalization.PropertyNormalizer]
 * turns it into an immutable [CanonicalProperty]. Keeping parsing/normalization separate
 * means parsers stay trivially unit-testable against fixtures.
 */
class PropertyDraft(
    val sourceId: String,
    val requestUrl: String,
    val resolvedUrl: String = requestUrl,
    var sourcePropertyId: String? = null
) {

    private val fields: LinkedHashMap<PropertyField, DraftField> = LinkedHashMap()

    /**
     * Records [value] for [field].
     *
     * @return true when the value was stored. A weaker (lower confidence) value never
     *         overwrites a stronger one, so adapters can apply extractors best-first.
     */
    fun put(
        field: PropertyField,
        value: Any?,
        method: ExtractionMethod = ExtractionMethod.REGEX_HEURISTIC,
        confidence: Double = Confidence.MEDIUM,
        extractor: String = "unknown",
        rawValue: String? = null,
        note: String? = null
    ): Boolean {
        if (value == null) return false
        if (value is String && value.isBlank()) return false
        if (value is Collection<*> && value.isEmpty()) return false

        val normalizedConfidence = confidence.coerceIn(0.0, 1.0)
        val existing = fields[field]
        if (existing != null && existing.confidence >= normalizedConfidence) return false

        fields[field] = DraftField(
            field = field,
            value = value,
            method = method,
            confidence = normalizedConfidence,
            extractor = extractor,
            rawValue = rawValue ?: value.toString(),
            note = note
        )
        return true
    }

    fun putAll(
        values: Map<PropertyField, Any?>,
        method: ExtractionMethod,
        confidence: Double,
        extractor: String
    ) {
        values.forEach { (field, value) -> put(field, value, method, confidence, extractor) }
    }

    fun raw(field: PropertyField): Any? = fields[field]?.value

    fun string(field: PropertyField): String? = fields[field]?.value as? String

    fun double(field: PropertyField): Double? {
        return when (val value = fields[field]?.value) {
            null -> null
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    fun int(field: PropertyField): Int? {
        return when (val value = fields[field]?.value) {
            null -> null
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is String -> value.toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun stringList(field: PropertyField): List<String> {
        val value = fields[field]?.value ?: return emptyList()
        return when (value) {
            is List<*> -> value.filterIsInstance<String>()
            is String -> listOf(value)
            else -> emptyList()
        }
    }

    fun present(): Set<PropertyField> = fields.keys.toSet()

    fun snapshot(): Map<PropertyField, DraftField> = fields.toMap()

    fun isEmpty(): Boolean = fields.isEmpty()

    fun confidenceOf(field: PropertyField): Double = fields[field]?.confidence ?: 0.0

    /** Turns the recorded fields into an immutable provenance map. */
    fun provenance(extractedAtEpochMillis: Long): ProvenanceMap {
        val map = LinkedHashMap<PropertyField, FieldProvenance>()
        fields.values.forEach { draft ->
            map[draft.field] = FieldProvenance(
                field = draft.field,
                sourceId = sourceId,
                sourceUrl = resolvedUrl,
                extractor = draft.extractor,
                method = draft.method,
                confidence = draft.confidence,
                rawValue = draft.rawValue,
                extractedAtEpochMillis = extractedAtEpochMillis,
                notes = draft.note
            )
        }
        return ProvenanceMap(map)
    }

    override fun toString(): String =
        "PropertyDraft(source=$sourceId, fields=${fields.size}, id=$sourcePropertyId)"
}
