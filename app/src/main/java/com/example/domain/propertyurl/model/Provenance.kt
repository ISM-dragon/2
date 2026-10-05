package com.example.domain.propertyurl.model

/**
 * How a value was obtained. The weight expresses how much the extraction method is trusted when
 * two sources disagree about the same field.
 */
enum class ProvenanceMethod(val weight: Double, val label: String) {
    /** JSON-LD / microdata / RDFa structured data published by the site itself. */
    STRUCTURED_DATA(1.00, "structured-data"),

    /** First-party API (MLS/IDX feed, partner API). */
    SOURCE_API(1.00, "source-api"),

    /** Server-side rendered state embedded in the page (`__NEXT_DATA__`, preloaded state…). */
    EMBEDDED_STATE(0.92, "embedded-state"),

    /** Values the operator supplied explicitly (URL import with manual overrides). */
    USER_PROVIDED(1.00, "user-provided"),

    /** Computed by this layer from other fields (e.g. price per sqft, state code from name). */
    DERIVED(0.80, "derived"),

    /** OpenGraph / meta tags. */
    HTML_META(0.70, "html-meta"),

    /** Regex heuristics over visible text or the title. */
    TEXT_HEURISTIC(0.50, "text-heuristic"),

    /** Static defaults / seed values. */
    DEFAULT(0.20, "default");

    companion object {
        /** Confidence ceiling applied automatically per method so adapters cannot over-claim. */
        fun ceilingFor(method: ProvenanceMethod): Double = when (method) {
            STRUCTURED_DATA, SOURCE_API, USER_PROVIDED -> 0.98
            EMBEDDED_STATE -> 0.95
            DERIVED -> 0.85
            HTML_META -> 0.80
            TEXT_HEURISTIC -> 0.65
            DEFAULT -> 0.40
        }
    }
}

/**
 * Immutable audit record attached to every canonical value: which source/adapter produced it, how,
 * when, and where inside the payload it was found.
 *
 * Provenance intentionally contains no credentials, cookies or PII beyond the (redacted) source URL.
 */
data class Provenance(
    val sourceId: String,
    val method: ProvenanceMethod,
    val confidence: Double,
    val extractedAtEpochMillis: Long,
    val adapterId: String? = null,
    val adapterVersion: String = UNKNOWN_VERSION,
    val sourceUrl: String? = null,
    /** Path inside the payload, e.g. `json-ld[0].offers.price` or `__NEXT_DATA__.props.pageProps.home.price`. */
    val rawPath: String? = null,
    val note: String? = null
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Provenance confidence must be within 0.0..1.0 but was $confidence"
        }
    }

    /** Confidence after applying the method ceiling and the source trust rank. */
    fun effectiveConfidence(sourceTrustRanks: Map<String, Int> = emptyMap()): Double {
        val ceiling = ProvenanceMethod.ceilingFor(method)
        val trust = sourceTrustRanks[sourceId]?.coerceIn(0, 10)?.let { 0.75 + (it / 10.0) * 0.25 } ?: 0.85
        return (confidence.coerceAtMost(ceiling) * trust).coerceIn(0.0, 1.0)
    }

    fun describe(): String = buildString {
        append(method.label)
        append(" via ")
        append(adapterId ?: sourceId)
        append(" (confidence ")
        append(String.format(java.util.Locale.US, "%.2f", confidence))
        append(")")
        if (rawPath != null) append(" at ").append(rawPath)
    }

    companion object {
        const val UNKNOWN_VERSION = "unknown"
    }
}

/** Recorded whenever two candidate values disagreed for the same field. */
data class FieldConflict(
    val field: PropertyField,
    val kept: SourcedField,
    val dropped: SourcedField,
    val reason: String
)

/**
 * Field merge policy: decides which candidate wins and records the losers.
 *
 * Ranking (dominant first): trust rank of the source → method weight → confidence → recency.
 * The policy is deterministic: equal scores keep the incumbent, which makes imports idempotent
 * (re-running an import never reshuffles a canonical property).
 */
class ProvenancePolicy(
    private val sourceTrustRanks: Map<String, Int> = emptyMap(),
    /** When true, a newer value of the same source+method replaces an older one. */
    private val preferFreshnessOnTie: Boolean = true
) {

    fun rank(field: SourcedField): Double {
        val trust = sourceTrustRanks[field.provenance.sourceId]?.coerceIn(0, 10) ?: 5
        val trustScore = trust / 10.0
        val efficiency = field.provenance.effectiveConfidence(sourceTrustRanks)
        return (trustScore * 0.45) + (field.provenance.method.weight * 0.35) + (efficiency * 0.20)
    }

    /**
     * Picks the winner between an incumbent and a challenger.
     *
     * @param field the field being merged (only used to label conflicts).
     * @param conflicts sink that receives a [FieldConflict] when the two candidates disagree.
     */
    fun pick(
        field: PropertyField,
        incumbent: SourcedField?,
        challenger: SourcedField,
        conflicts: MutableList<FieldConflict>? = null
    ): SourcedField {
        if (incumbent == null) return challenger

        val incumbentRank = rank(incumbent)
        val challengerRank = rank(challenger)
        val sameValue = sameValue(incumbent.value, challenger.value)

        var winner = incumbent
        var loser = challenger
        var reason = "lower rank (${format(challengerRank)} < ${format(incumbentRank)})"

        if (challengerRank > incumbentRank) {
            // Numeric values that are equal only after coercion (e.g. "485,000" vs 485000) are not conflicts.
            val replacementConfidenceGain = challengerRank - incumbentRank
            if (sameValue || replacementConfidenceGain > TIE_EPSILON) {
                winner = challenger
                loser = incumbent
                reason = "higher rank (${format(challengerRank)} > ${format(incumbentRank)})"
            }
        } else if (challengerRank == incumbentRank && preferFreshnessOnTie) {
            if (challenger.provenance.extractedAtEpochMillis > incumbent.provenance.extractedAtEpochMillis) {
                if (!sameValue) {
                    winner = challenger
                    loser = incumbent
                    reason = "fresher value at equal rank"
                }
            }
        }

        if (!sameValue && loser.provenance.sourceId != winner.provenance.sourceId && conflicts != null) {
            // Only record when the two candidates disagree, to keep warning volume sane.
            conflicts.add(FieldConflict(field = field, kept = winner, dropped = loser, reason = reason))
        }
        return winner
    }

    private fun sameValue(a: FieldValue, b: FieldValue): Boolean {
        val aText = a.asText()?.trim()?.lowercase()
        val bText = b.asText()?.trim()?.lowercase()
        if (aText != null && bText != null && aText == bText) return true
        val aNum = a.asDecimal()
        val bNum = b.asDecimal()
        if (aNum != null && bNum != null) return Math.abs(aNum - bNum) < 0.001
        val aList = a.asTextList().map { it.trim().lowercase() }.sorted()
        val bList = b.asTextList().map { it.trim().lowercase() }.sorted()
        return aList.isNotEmpty() && aList == bList
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    private companion object {
        const val TIE_EPSILON = 0.0001
    }
}

/**
 * Creates [Provenance] records for one extraction run.
 *
 * The clock is injected as a lambda so the model layer stays free of port dependencies (and tests
 * can freeze time).
 */
class ProvenanceFactory(
    private val sourceId: String,
    private val adapterId: String?,
    private val adapterVersion: String,
    private val sourceUrl: String?,
    private val now: () -> Long
) {

    fun create(
        method: ProvenanceMethod,
        confidence: Double,
        rawPath: String? = null,
        note: String? = null
    ): Provenance = Provenance(
        sourceId = sourceId,
        method = method,
        confidence = confidence.coerceIn(0.0, 1.0),
        extractedAtEpochMillis = now(),
        adapterId = adapterId,
        adapterVersion = adapterVersion,
        sourceUrl = sourceUrl,
        rawPath = rawPath,
        note = note
    )

    fun derived(rawPath: String? = null, note: String? = null, confidence: Double = 0.8): Provenance =
        create(ProvenanceMethod.DERIVED, confidence, rawPath, note)
}
