package com.example.domain.crm

import kotlin.math.roundToInt

/**
 * Seller motivation signals — the observable reasons a seller might sell below market.
 *
 * The vocabulary is closed on purpose: signals come from public records, vendors, field visits and
 * the seller's own words, and only a fixed list can be scored, filtered and audited consistently.
 * Free text belongs in [MotivationSignal.detail].
 */
enum class MotivationSignalKind(
    /** Signals a time box: the seller is being pushed by an external deadline. */
    val isTimeBoxed: Boolean,
    /** Distress that usually means the property is not in retail condition. */
    val impliesConditionIssue: Boolean
) {
    /** Notice of default / lis pendens recorded. */
    PRE_FORECLOSURE(isTimeBoxed = true, impliesConditionIssue = false),
    /** Delinquent property taxes: a cheap, public, high-intent signal. */
    TAX_DELINQUENT(isTimeBoxed = true, impliesConditionIssue = false),
    /** Judgment, mechanic's or HOA lien recorded against the property. */
    LIEN_OR_JUDGMENT(isTimeBoxed = true, impliesConditionIssue = false),
    /** Water/power shut off, boarded windows, no occupancy evidence. */
    VACANT(isTimeBoxed = false, impliesConditionIssue = true),
    /** Owner died; property is in (or heading into) probate. */
    PROBATE_OR_ESTATE(isTimeBoxed = false, impliesConditionIssue = true),
    DIVORCE(isTimeBoxed = true, impliesConditionIssue = false),
    RELOCATION(isTimeBoxed = true, impliesConditionIssue = false),
    JOB_LOSS(isTimeBoxed = true, impliesConditionIssue = false),
    /** Landlord is done with tenants, repairs and turnover. */
    TIRED_LANDLORD(isTimeBoxed = false, impliesConditionIssue = true),
    /** Owns free and clear or near it: room for a discount without a short sale. */
    HIGH_EQUITY(isTimeBoxed = false, impliesConditionIssue = false),
    /** Mailing address differs from the property address. */
    ABSENTEE_OWNER(isTimeBoxed = false, impliesConditionIssue = true),
    /** Listing expired or was withdrawn without selling. */
    EXPIRED_LISTING(isTimeBoxed = true, impliesConditionIssue = false),
    /** Municipal code violation on file. */
    CODE_VIOLATION(isTimeBoxed = true, impliesConditionIssue = true),
    /** Fire, storm or flood damage. */
    DAMAGE_INSURANCE_CLAIM(isTimeBoxed = true, impliesConditionIssue = true),
    /** Inherited the property and does not want it. */
    INHERITED_PROPERTY(isTimeBoxed = false, impliesConditionIssue = true),
    /** Seller said it out loud: "I need to sell", "make me an offer". */
    SELLER_STATED_URGENCY(isTimeBoxed = true, impliesConditionIssue = false),
    /** Anything else, always with a detail string. */
    OTHER(isTimeBoxed = false, impliesConditionIssue = false);

    val isDistress: Boolean
        get() = this != HIGH_EQUITY && this != SELLER_STATED_URGENCY && this != OTHER
}

/** How strong a single signal is, judged by the person who recorded it. */
enum class MotivationStrength(val weight: Int) {
    /** A hint (an old lead-list row, an unverified vendor flag). */
    WEAK(1),
    /** Corroborated by one source, or stated by the seller in passing. */
    MODERATE(2),
    /** Documented and time-boxed (a recorded notice, a seller's explicit deadline). */
    STRONG(3)
}

/** Where a signal came from. Drives evidence quality, never scoring by itself. */
enum class MotivationSourceKind {
    /** The seller said it (highest trust for intent). */
    SELLER_STATED,
    /** A lead vendor / list provider flagged it. */
    LEAD_VENDOR,
    /** County assessor, recorder, court or code-enforcement data. */
    PUBLIC_RECORD,
    /** Observed on a drive-by or field visit. */
    FIELD_OBSERVATION,
    /** Recorded by an agent or acquisitions rep after a conversation. */
    AGENT_NOTE,
    /** Derived deterministically by the app from other known facts. */
    SYSTEM_INFERRED;

    /** Signals from these sources are considered documented evidence. */
    val isDocumentedEvidence: Boolean
        get() = this == PUBLIC_RECORD || this == SELLER_STATED || this == FIELD_OBSERVATION
}

/**
 * One motivation signal observed at a known time.
 *
 * `evidenceRef` points at the artifact behind the claim (a document id, a vendor record id, a call
 * id). A signal without a reference is still allowed, but it cannot be the sole basis of a
 * "QUALIFIED" decision — see [MotivationScoringPolicy.requireDocumentedEvidenceForQualified].
 */
data class MotivationSignal(
    val id: String,
    val kind: MotivationSignalKind,
    val strength: MotivationStrength,
    val sourceKind: MotivationSourceKind,
    val observedAtEpochMillis: Long,
    val recordedAtEpochMillis: Long,
    val recordedBy: String,
    val evidenceRef: String? = null,
    val detail: String? = null
) {
    init {
        require(id.isNotBlank()) { "Motivation signal id must not be blank" }
        require(observedAtEpochMillis > 0L) { "Motivation signal observedAtEpochMillis must be positive" }
        require(recordedAtEpochMillis >= observedAtEpochMillis) { "Motivation signal cannot be recorded before it was observed" }
        require(recordedBy.isNotBlank()) { "Motivation signal must record who captured it" }
        require(detail == null || detail.length <= MAX_DETAIL_LENGTH) { "Motivation signal detail must be at most $MAX_DETAIL_LENGTH characters" }
        require(kind != MotivationSignalKind.OTHER || !detail.isNullOrBlank()) {
            "MotivationSignalKind.OTHER requires a detail"
        }
    }

    val isDocumentedEvidence: Boolean get() = sourceKind.isDocumentedEvidence && !evidenceRef.isNullOrBlank()

    val isTimeBoxed: Boolean get() = kind.isTimeBoxed

    fun describe(): String = buildString {
        append(kind.name).append('/').append(strength.name).append(" from ").append(sourceKind.name)
        if (!evidenceRef.isNullOrBlank()) append(" ref=").append(evidenceRef)
        if (!detail.isNullOrBlank()) append(" (").append(detail).append(')')
    }

    companion object {
        const val MAX_DETAIL_LENGTH = 400
    }
}

/**
 * Aggregated motivation for a lead, produced by [MotivationSignals.aggregate].
 *
 * The index is intentionally bounded and explainable: it is a weighted count of *distinct* signal
 * kinds (repeating the same signal must not inflate the score) with a freshness decay, so an old
 * distressed-list flag decays instead of propping up a lead forever.
 */
data class MotivationAggregate(
    /** 0..100, deterministic given the signals and the policy. */
    val index: Int,
    val strength: MotivationStrength,
    val dominantKind: MotivationSignalKind?,
    val distinctKinds: Int,
    val freshSignals: Int,
    val staleSignals: Int,
    val documentedSignals: Int,
    val timeBoxedSignals: Int,
    val asOfEpochMillis: Long,
    val policyVersion: String
) {
    init {
        require(index in 0..100) { "Motivation index must be within 0..100" }
        require(freshSignals >= 0 && staleSignals >= 0) { "Motivation signal counts must not be negative" }
        require(asOfEpochMillis > 0L) { "Motivation aggregate asOfEpochMillis must be positive" }
        require(policyVersion.isNotBlank()) { "Motivation policy version must not be blank" }
    }

    val hasSignals: Boolean get() = freshSignals + staleSignals > 0

    /** True when at least one fresh, documented, time-boxed signal exists. */
    val hasUrgentEvidence: Boolean get() = timeBoxedSignals > 0 && documentedSignals > 0

    val isEmpty: Boolean get() = index == 0 && !hasSignals

    fun describe(): String = buildString {
        append("index=").append(index)
        append(" strength=").append(strength.name)
        dominantKind?.let { append(" dominant=").append(it.name) }
        append(" fresh=").append(freshSignals).append(" stale=").append(staleSignals)
    }

    companion object {
        fun empty(asOfEpochMillis: Long, policyVersion: String = MotivationScoringPolicy.DEFAULT_VERSION) =
            MotivationAggregate(
                index = 0,
                strength = MotivationStrength.WEAK,
                dominantKind = null,
                distinctKinds = 0,
                freshSignals = 0,
                staleSignals = 0,
                documentedSignals = 0,
                timeBoxedSignals = 0,
                asOfEpochMillis = asOfEpochMillis,
                policyVersion = policyVersion
            )
    }
}

/**
 * Deterministic scoring policy for motivation signals.
 *
 * Every knob is explicit and validated, so two builds with the same policy and the same signals
 * always produce the same index — the property the CRM relies on for auditable qualification.
 */
data class MotivationScoringPolicy(
    val version: String = DEFAULT_VERSION,
    /** Signals observed within this window count at full weight. */
    val freshWindowDays: Int = 45,
    /** Signals older than this are ignored entirely (not merely discounted). */
    val staleAfterDays: Int = 365,
    /** Index contribution per fresh signal, by strength. */
    val weightByStrength: Map<MotivationStrength, Int> = DEFAULT_WEIGHTS,
    /** Extra index per distinct fresh signal kind beyond the first. */
    val distinctKindBonus: Int = 6,
    /** Cap so that a long list of weak signals cannot beat one strong one. */
    val maximumDistinctKinds: Int = 5,
    /** Accepted: documented evidence required before a lead can be called qualified. */
    val requireDocumentedEvidenceForQualified: Boolean = true
) {
    init {
        require(version.isNotBlank()) { "Motivation policy version must not be blank" }
        require(freshWindowDays > 0) { "freshWindowDays must be positive" }
        require(staleAfterDays >= freshWindowDays) { "staleAfterDays must not be shorter than freshWindowDays" }
        require(weightByStrength.keys == MotivationStrength.entries.toSet()) {
            "weightByStrength must define every motivation strength exactly once"
        }
        require(weightByStrength.values.all { it in 1..100 }) { "Motivation weights must be within 1..100" }
        require(distinctKindBonus in 0..100) { "distinctKindBonus must be within 0..100" }
        require(maximumDistinctKinds in 1..20) { "maximumDistinctKinds must be within 1..20" }
    }

    /** Signals whose age (in days) is within the fresh window count fully; beyond [staleAfterDays] they are dropped. */
    fun isFresh(ageDays: Long): Boolean = ageDays <= freshWindowDays

    fun isStale(ageDays: Long): Boolean = ageDays > staleAfterDays

    companion object {
        const val DEFAULT_VERSION = "motivation-v1"
        val DEFAULT_WEIGHTS: Map<MotivationStrength, Int> = mapOf(
            MotivationStrength.WEAK to 8,
            MotivationStrength.MODERATE to 16,
            MotivationStrength.STRONG to 26
        )
        val DEFAULT = MotivationScoringPolicy()
    }
}

/** Pure aggregation of motivation signals into a bounded, explainable index. */
object MotivationSignals {

    /**
     * Aggregates signals as of [asOfEpochMillis].
     *
     * Rules, in order:
     *  1. signals older than [MotivationScoringPolicy.staleAfterDays] are dropped (`staleSignals`);
     *  2. within a signal kind only the strongest fresh signal counts — duplicates never stack;
     *  3. points = weight(strength) of each surviving kind, plus `distinctKindBonus` per extra kind,
     *     capped at `maximumDistinctKinds` distinct kinds;
     *  4. the index is the capped sum, clamped to 0..100.
     *
     * Fresh signals are ordered by strength, then by recency, then by id, so the "dominant" kind is
     * stable even when several signals tie.
     */
    fun aggregate(
        signals: List<MotivationSignal>,
        asOfEpochMillis: Long,
        policy: MotivationScoringPolicy = MotivationScoringPolicy.DEFAULT
    ): MotivationAggregate {
        require(asOfEpochMillis > 0L) { "asOfEpochMillis must be positive" }

        if (signals.isEmpty()) return MotivationAggregate.empty(asOfEpochMillis, policy.version)

        val fresh = mutableListOf<MotivationSignal>()
        val stale = mutableListOf<MotivationSignal>()
        val future = mutableListOf<MotivationSignal>()
        signals.forEach { signal ->
            val ageDays = CrmTime.daysBetween(signal.observedAtEpochMillis, asOfEpochMillis)
            when {
                ageDays < 0L -> future += signal
                policy.isStale(ageDays) -> stale += signal
                else -> fresh += signal
            }
        }

        // Same-observed-time ties are broken by id so the aggregate never depends on input order.
        val ordered = fresh.sortedWith(
            compareByDescending<MotivationSignal> { it.strength.weight }
                .thenByDescending { it.observedAtEpochMillis }
                .thenBy { it.id }
        )

        val representativeByKind = LinkedHashMap<MotivationSignalKind, MotivationSignal>()
        ordered.forEach { signal ->
            if (!representativeByKind.containsKey(signal.kind)) representativeByKind[signal.kind] = signal
        }

        val countedKinds = representativeByKind.values
            .sortedWith(compareByDescending<MotivationSignal> { it.strength.weight }.thenBy { it.kind.ordinal })
            .take(policy.maximumDistinctKinds)

        var points = 0
        countedKinds.forEachIndexed { index, signal ->
            points += policy.weightByStrength.getValue(signal.strength)
            if (index > 0) points += policy.distinctKindBonus
        }

        val index = points.coerceIn(0, 100)
        val dominant = countedKinds.firstOrNull()?.kind
        val strongest = countedKinds.maxOfOrNull { it.strength } ?: MotivationStrength.WEAK

        return MotivationAggregate(
            index = index,
            strength = strongest,
            dominantKind = dominant,
            distinctKinds = representativeByKind.size,
            freshSignals = fresh.size,
            staleSignals = stale.size + future.size,
            documentedSignals = fresh.count { it.isDocumentedEvidence },
            timeBoxedSignals = fresh.count { it.isTimeBoxed },
            asOfEpochMillis = asOfEpochMillis,
            policyVersion = policy.version
        )
    }

    /**
     * Expands an index (0..100) onto a 0..[maximumPoints] factor contribution.
     * Kept here so qualification and priority score motivation identically.
     */
    fun scaleToPoints(index: Int, maximumPoints: Int): Int {
        require(index in 0..100) { "Motivation index must be within 0..100" }
        require(maximumPoints >= 0) { "maximumPoints must not be negative" }
        return ((index.toDouble() / 100.0) * maximumPoints).roundToInt().coerceIn(0, maximumPoints)
    }
}
