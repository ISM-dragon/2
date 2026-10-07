package com.example.domain.identity

/**
 * Where one observed field value came from, and how authoritative that origin is.
 *
 * Conflict resolution between sources is never implicit: [priority] is the explicit source
 * ranking (lower wins, mirroring `property_sources.priority`), [observedAt] is the recency
 * tie-breaker, and [source]/[recordRef] make every accepted or rejected value traceable.
 *
 * The comparison order below is total and deterministic, so resolution results never depend on
 * the order in which imports happened to arrive:
 *
 *  1. lower [priority] value wins (an MLS feed outranks a wholesaler list);
 *  2. on equal priority, the more recent [observedAt] wins;
 *  3. on equal recency, the lexicographically smaller [source] name wins;
 *  4. finally the smaller [recordRef] (nulls last) - a last resort that keeps the order total.
 */
data class SourceProvenance(
    val source: String,
    val priority: Int = DEFAULT_SOURCE_PRIORITY,
    /** Epoch millis when the source observed the value; 0 means "unknown age". */
    val observedAt: Long = 0L,
    /** The source's record key (listing id or URL) for full traceability. */
    val recordRef: String? = null
) : Comparable<SourceProvenance> {

    /** Total, deterministic "winner-first" ordering (see class KDoc). */
    override fun compareTo(other: SourceProvenance): Int = compareValuesBy(
        this,
        other,
        { it.priority },
        { -it.observedAt },
        { it.source },
        { it.recordRef ?: NULL_REF_SENTINEL }
    )

    /** Human-readable form used in merge explanations. */
    fun describe(): String = buildString {
        append(source.ifBlank { "unknown-source" })
        append(" (priority ").append(priority)
        if (observedAt > 0L) append(", observedAt ").append(observedAt)
        if (!recordRef.isNullOrBlank()) append(", record ").append(recordRef)
        append(')')
    }

    companion object {
        /** Mirrors the default `property_sources.priority`; neutral authority. */
        const val DEFAULT_SOURCE_PRIORITY = 100

        /** Sorts null record refs after all real refs, keeping [compareTo] total. */
        private const val NULL_REF_SENTINEL = "\uFFFF"
    }
}

/** One field value together with the source that reported it. */
data class ProvenancedValue<T>(
    val value: T?,
    val provenance: SourceProvenance
)

/** One rejected value from a field conflict, kept for the audit trail. */
data class FieldConflict(
    val field: String,
    val rejectedValue: String,
    val rejectedProvenance: SourceProvenance,
    val winningValue: String,
    val winningProvenance: SourceProvenance,
    val reason: String
)

/**
 * The outcome of resolving one field across sources.
 *
 * [value] is null when no source supplied a usable value. [rule] explains which resolution rule
 * produced the winner; [conflicts] lists every differing value that lost, with its provenance -
 * conflicting source values are recorded, never silently dropped.
 */
data class ResolvedValue<T>(
    val field: String,
    val value: T,
    val provenance: SourceProvenance?,
    val rule: String,
    val conflicts: List<FieldConflict> = emptyList()
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

/**
 * Deterministic, provenance-aware resolution of conflicting source values.
 *
 * Guarantees:
 *  - the winner is a pure function of the observation *set* (input order does not matter);
 *  - missing values (null/blank/sentinel) never win over real values and never create conflicts;
 *  - equal values from several sources produce no conflict, and the winner is the strongest
 *    provenance among them (so the recorded origin is the authoritative one);
 *  - every rejected differing value is reported in [ResolvedValue.conflicts] with provenance.
 */
object SourceValueResolver {

    /**
     * Resolves one field. [normalize] canonicalizes values for equality (defaults to identity),
     * [isMissing] marks values that carry no information (defaults to "never"), and [render]
     * stringifies values for the conflict trail.
     */
    fun <T : Any> resolve(
        field: String,
        observations: List<ProvenancedValue<T?>>,
        normalize: (T) -> T = { it },
        isMissing: (T) -> Boolean = { false },
        render: (T) -> String = { it.toString() }
    ): ResolvedValue<T?> {
        val usable: List<Pair<T, SourceProvenance>> = observations
            .mapNotNull { observation ->
                val raw = observation.value ?: return@mapNotNull null
                val normalized = normalize(raw)
                if (isMissing(normalized)) null else normalized to observation.provenance
            }
            // Deterministic regardless of import order: strongest provenance first, with the
            // rendered value as the final tie-breaker so identical provenances cannot flip the
            // winner between runs.
            .sortedWith(compareBy({ it.second }, { render(it.first) }))

        if (usable.isEmpty()) {
            return ResolvedValue(
                field = field,
                value = null,
                provenance = null,
                rule = "no source supplied a usable value for '$field'"
            )
        }

        val (winnerValue, winnerProvenance) = usable.first()
        val conflicts = usable
            .drop(1)
            .filter { (value, _) -> value != winnerValue }
            .map { (value, provenance) ->
                FieldConflict(
                    field = field,
                    rejectedValue = render(value),
                    rejectedProvenance = provenance,
                    winningValue = render(winnerValue),
                    winningProvenance = winnerProvenance,
                    reason = "source priority/provenance: ${winnerProvenance.describe()} outranks " +
                    "${provenance.describe()} for '$field'"
                )
            }

        val agreeingSources = usable.count { (value, _) -> value == winnerValue }
        val rule = when {
            conflicts.isEmpty() && agreeingSources > 1 ->
                "'$field' agreed across $agreeingSources sources; recorded from " +
                    winnerProvenance.describe()
            conflicts.isEmpty() ->
                "'$field' supplied by a single source ${winnerProvenance.describe()}"
            else ->
                "'$field' resolved by source precedence to ${winnerProvenance.describe()}; " +
                    "${conflicts.size} differing value(s) recorded as conflicts"
        }
        return ResolvedValue(
            field = field,
            value = winnerValue,
            provenance = winnerProvenance,
            rule = rule,
            conflicts = conflicts
        )
    }

    /** String resolution where blank values count as missing. */
    fun resolveString(field: String, observations: List<ProvenancedValue<String?>>): ResolvedValue<String?> =
        resolve(
            field = field,
            observations = observations,
            normalize = { it.trim() },
            isMissing = { it.isBlank() }
        )

    /** Coordinate-pair resolution: incomplete or sentinel pairs count as missing. */
    fun resolveCoordinates(
        field: String,
        observations: List<ProvenancedValue<Pair<Double, Double>?>>
    ): ResolvedValue<Pair<Double, Double>?> = resolve(
        field = field,
        observations = observations,
        isMissing = { pair ->
            !pair.first.isFinite() || !pair.second.isFinite() ||
                pair.first !in -90.0..90.0 || pair.second !in -180.0..180.0 ||
                (pair.first == 0.0 && pair.second == 0.0) // Null Island sentinel = missing
        },
        render = { pair -> "${pair.first},${pair.second}" }
    )
}

/**
 * The explainable outcome of merging source observations into one canonical identity.
 *
 * [fieldProvenance] records which source each accepted field value came from; [conflicts] keeps
 * every rejected differing value with its provenance, so a later audit can answer "why does the
 * canonical record show this address and not the other one?".
 */
data class CanonicalIdentityMergeReport(
    val merged: CanonicalPropertyIdentity,
    val fieldProvenance: Map<String, SourceProvenance>,
    val conflicts: List<FieldConflict>,
    val rules: List<String>
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()

    /** Deterministic one-line summary suitable for logs and import reports. */
    fun explain(): String = buildString {
        append("canonical ").append(merged.canonicalId).append(": ")
        append(rules.joinToString(" | "))
        if (conflicts.isNotEmpty()) {
            append(" | conflicts: ")
            append(
                conflicts.joinToString("; ") {
                    "${it.field} kept '${it.winningValue}' from ${it.winningProvenance.source}, " +
                        "rejected '${it.rejectedValue}' from ${it.rejectedProvenance.source}"
                }
            )
        }
    }
}

/**
 * Merges the source observations linked to a canonical property into deterministic field values.
 *
 * The merger is idempotent and order-independent: merging the same observation set again (a
 * repeated import, in any order) produces the exact same canonical identity, because every field
 * is resolved by [SourceValueResolver]'s total provenance ordering rather than by write order.
 *
 * The canonical row's own current values act as the fallback when no observation supplies a
 * field, so merging never loses information that only the canonical record knows.
 */
object CanonicalIdentityMerger {

    fun merge(
        canonical: CanonicalPropertyIdentity,
        additionalObservations: Collection<SourcePropertyIdentity> = emptyList()
    ): CanonicalIdentityMergeReport {
        val observations = (canonical.sourceIdentities + additionalObservations)
            // Defensive: identical observations cannot flip results; distinct keeps the set small.
            .distinct()
            .sortedBy { it.provenance() }

        val parcelId = SourceValueResolver.resolveString(
            "parcelId",
            observations.map { ProvenancedValue(it.parcelId, it.provenance()) }
        ).withFallback(canonical.parcelId)
        val mlsId = SourceValueResolver.resolveString(
            "mlsId",
            observations.map { ProvenancedValue(it.mlsId, it.provenance()) }
        ).withFallback(canonical.mlsId)
        val address = SourceValueResolver.resolveString(
            "address",
            observations.map { ProvenancedValue(it.address, it.provenance()) }
        ).withFallback(canonical.address)
        val city = SourceValueResolver.resolveString(
            "city",
            observations.map { ProvenancedValue(it.city, it.provenance()) }
        ).withFallback(canonical.city)
        val state = SourceValueResolver.resolveString(
            "state",
            observations.map { ProvenancedValue(it.state, it.provenance()) }
        ).withFallback(canonical.state)
        val postalCode = SourceValueResolver.resolveString(
            "postalCode",
            observations.map { ProvenancedValue(it.postalCode, it.provenance()) }
        ).withFallback(canonical.postalCode)
        val coordinates = SourceValueResolver.resolveCoordinates(
            "coordinates",
            observations.map {
                ProvenancedValue(
                    if (it.latitude != null && it.longitude != null) it.latitude to it.longitude else null,
                    it.provenance()
                )
            }
        )

        val resolvedFields = listOf(parcelId, mlsId, address, city, state, postalCode)
        val fieldProvenance = buildMap {
            resolvedFields.forEach { field ->
                field.provenance?.let { put(field.field, it) }
            }
            coordinates.provenance?.let { put(coordinates.field, it) }
        }
        val conflicts = resolvedFields.flatMap { it.conflicts } + coordinates.conflicts
        val rules = resolvedFields.map { it.rule } + coordinates.rule

        val merged = canonical.copy(
            parcelId = parcelId.value ?: canonical.parcelId,
            mlsId = mlsId.value ?: canonical.mlsId,
            address = address.value ?: canonical.address,
            city = city.value ?: canonical.city,
            state = state.value ?: canonical.state,
            postalCode = postalCode.value ?: canonical.postalCode,
            latitude = coordinates.value?.first ?: canonical.latitude,
            longitude = coordinates.value?.second ?: canonical.longitude,
            sourceIdentities = canonical.sourceIdentities + additionalObservations
        )

        return CanonicalIdentityMergeReport(
            merged = merged,
            fieldProvenance = fieldProvenance,
            conflicts = conflicts,
            rules = rules
        )
    }

    /** Falls back to the canonical row's current value when no source supplied one. */
    private fun ResolvedValue<String?>.withFallback(fallback: String?): ResolvedValue<String?> =
        if (value != null) {
            this
        } else {
            copy(
                value = fallback?.takeIf { it.isNotBlank() },
                rule = if (fallback.isNullOrBlank()) rule else "$rule; kept existing canonical value"
            )
        }
}
