package com.example.domain.propertyurl.model

/**
 * Immutable bag of extracted facts: several candidates per field are allowed (JSON-LD, embedded
 * state, meta tags and text heuristics frequently overlap). Selection happens once, during
 * canonicalization, through [ProvenancePolicy] — never inside a parser.
 */
class ExtractedFacts private constructor(private val entries: List<Fact>) {

    data class Fact(val field: PropertyField, val value: FieldValue, val provenance: Provenance)

    val facts: List<Fact> get() = entries

    val fields: Set<PropertyField> get() = entries.map { it.field }.toSet()

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun isNotEmpty(): Boolean = entries.isNotEmpty()

    fun contains(field: PropertyField): Boolean = entries.any { it.field == field }

    fun forField(field: PropertyField): List<Fact> = entries.filter { it.field == field }

    /** Returns the strongest candidate for [field] using [policy]. */
    fun best(field: PropertyField, policy: ProvenancePolicy, conflicts: MutableList<FieldConflict>? = null): SourcedField? {
        var winner: SourcedField? = null
        forField(field).forEach { fact ->
            winner = policy.pick(field, winner, SourcedField(fact.value, fact.provenance), conflicts)
        }
        return winner
    }

    /** Collapses every field to its strongest candidate. */
    fun bestPerField(
        policy: ProvenancePolicy,
        conflicts: MutableList<FieldConflict>? = null
    ): Map<PropertyField, SourcedField> {
        val result = LinkedHashMap<PropertyField, SourcedField>()
        entries.map { it.field }.distinct().forEach { field ->
            best(field, policy, conflicts)?.let { result[field] = it }
        }
        return result
    }

    fun with(fact: Fact): ExtractedFacts = ExtractedFacts(entries + fact)

    fun with(field: PropertyField, value: FieldValue, provenance: Provenance): ExtractedFacts =
        with(Fact(field, value, provenance))

    fun plus(other: ExtractedFacts): ExtractedFacts = ExtractedFacts(entries + other.entries)

    override fun toString(): String = "ExtractedFacts(size=$size, fields=${fields.map { it.name }.sorted()})"

    companion object {
        val EMPTY: ExtractedFacts = ExtractedFacts(emptyList())

        fun of(vararg facts: Fact): ExtractedFacts = ExtractedFacts(facts.toList())
    }
}

/** Mutable accumulator used by adapters/parsers while walking a document. */
class ExtractedFactsBuilder {

    private val entries = ArrayList<ExtractedFacts.Fact>()

    fun add(field: PropertyField, value: FieldValue, provenance: Provenance): ExtractedFactsBuilder {
        entries.add(ExtractedFacts.Fact(field, value, provenance))
        return this
    }

    fun addText(field: PropertyField, value: String?, provenance: Provenance): ExtractedFactsBuilder {
        val trimmed = value?.trim()
        if (!trimmed.isNullOrEmpty()) add(field, FieldValue.Text(trimmed), provenance)
        return this
    }

    fun addNumber(field: PropertyField, value: Double?, provenance: Provenance): ExtractedFactsBuilder {
        if (value != null && value.isFinite()) add(field, FieldValue.Decimal(value), provenance)
        return this
    }

    fun addInt(field: PropertyField, value: Int?, provenance: Provenance): ExtractedFactsBuilder {
        if (value != null) add(field, FieldValue.WholeNumber(value), provenance)
        return this
    }

    fun addFlag(field: PropertyField, value: Boolean?, provenance: Provenance): ExtractedFactsBuilder {
        if (value != null) add(field, FieldValue.Flag(value), provenance)
        return this
    }

    fun addList(field: PropertyField, values: List<String>?, provenance: Provenance): ExtractedFactsBuilder {
        val cleaned = values?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
        if (!cleaned.isNullOrEmpty()) add(field, FieldValue.TextList(cleaned), provenance)
        return this
    }

    fun addTimestamp(field: PropertyField, epochMillis: Long?, provenance: Provenance): ExtractedFactsBuilder {
        if (epochMillis != null && epochMillis > 0) add(field, FieldValue.Timestamp(epochMillis), provenance)
        return this
    }

    /** Adds a coordinate pair as two independent fields (guarded by [ValueGuards] callers). */
    fun addCoordinates(latitude: Double?, longitude: Double?, provenance: Provenance): ExtractedFactsBuilder {
        if (latitude != null && latitude in -90.0..90.0) {
            add(PropertyField.LATITUDE, FieldValue.Decimal(latitude), provenance)
        }
        if (longitude != null && longitude in -180.0..180.0) {
            add(PropertyField.LONGITUDE, FieldValue.Decimal(longitude), provenance)
        }
        return this
    }

    fun addAll(facts: ExtractedFacts): ExtractedFactsBuilder {
        entries.addAll(facts.facts)
        return this
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun isNotEmpty(): Boolean = entries.isNotEmpty()

    fun contains(field: PropertyField): Boolean = entries.any { it.field == field }

    fun size(): Int = entries.size

    fun build(): ExtractedFacts = if (entries.isEmpty()) ExtractedFacts.EMPTY else ExtractedFacts.of(*entries.toTypedArray())
}
