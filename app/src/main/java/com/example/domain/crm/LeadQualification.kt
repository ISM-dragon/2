package com.example.domain.crm

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Deterministic qualification of a wholesale lead.
 *
 * Qualification answers one question: *may this lead be worked further, and how well does it fit the
 * buy box?* It is computed from explicit facts only — never from AI output, never from a clock, never
 * from a network call. The same inputs and the same [LeadQualificationPolicy] always produce the same
 * score, the same state and the same blockers, which is what makes the pipeline auditable.
 *
 * The state ladder is intentionally coarser than the score:
 *
 * ```
 * NOT_ASSESSED → UNQUALIFIED → NURTURE → WARM → QUALIFIED
 *                     ↘──────── DISQUALIFIED ────────↗   (re-assessment can lift it)
 * ```
 *
 *  - [LeadQualificationState.UNQUALIFIED]: nothing disqualifying, but the fit is too weak to spend time on;
 *  - [LeadQualificationState.NURTURE]: interesting, not yet provable (evidence blockers);
 *  - [LeadQualificationState.WARM]: worth a conversation/offer prep, but not fully proven;
 *  - [LeadQualificationState.QUALIFIED]: every required fact is present, scored above the policy bar;
 *  - [LeadQualificationState.DISQUALIFIED]: a hard fact says no (seller declined, no permitted contact
 *    path, outside the service area, or the spread cannot reach the floor).
 */
enum class LeadQualificationState(val rank: Int) {

    /** No assessment has ever been made. */
    NOT_ASSESSED(-1),

    /** A hard disqualifier applies. Re-assessable when the fact changes (documented, audited). */
    DISQUALIFIED(0),

    /** Assessed, no disqualifier, but below the nurture bar. */
    UNQUALIFIED(1),

    /** Promising but under-evidenced: qualifies for follow-up work, not for an offer. */
    NURTURE(2),

    /** Actionable: worth negotiation time, still short of a written offer in the default policy. */
    WARM(3),

    /** Fully evidenced and above the qualified bar. Required for OFFER_SENT and beyond. */
    QUALIFIED(4);

    val isAssessed: Boolean get() = this != NOT_ASSESSED

    val isDisqualified: Boolean get() = this == DISQUALIFIED

    /** The seller is worth active work (call-backs, follow-up campaign). */
    val isActionable: Boolean get() = this == WARM || this == QUALIFIED

    /** The default transition policy allows entering NEGOTIATING from here. */
    val permitsNegotiation: Boolean get() = isActionable

    /** The default transition policy allows submitting a written offer from here. */
    val permitsOffer: Boolean get() = this == QUALIFIED

    /** Lower of two states, used to cap a state by its blockers. */
    fun atMost(ceiling: LeadQualificationState): LeadQualificationState =
        if (rank <= ceiling.rank) this else ceiling

    /** True when the target would be an improvement over this state. */
    fun improvesTo(target: LeadQualificationState): Boolean = target.rank > rank
}

/** Why a qualification state changed. Audited on every transition. */
enum class LeadQualificationReason {
    /** First assessment of the lead. */
    FIRST_ASSESSMENT,
    /** New facts raised the state. */
    EVIDENCE_ADDED,
    /** Facts aged out or were invalidated, lowering the state. */
    EVIDENCE_DECAYED,
    /** New facts contradicted the previous assessment (including a fresh disqualifier). */
    EVIDENCE_CONTRADICTED,
    /** The seller responded, which by itself can raise the state. */
    SELLER_RESPONDED,
    /** The seller declined or asked not to be contacted. */
    SELLER_DECLINED,
    /** An operator overrode the computed state (recorded with the operator's note). */
    MANUAL_OVERRIDE,
    /** A plain re-run of the assessment that produced the same state (assessment refreshed). */
    REASSESSED;

    val isAutomatic: Boolean get() = this != MANUAL_OVERRIDE
}

/** Factors that contribute points to the qualification score. */
enum class LeadQualificationFactorId(val label: String) {
    SPREAD("Spread vs. 70% rule"),
    MOTIVATION("Seller motivation"),
    TIMELINE("Seller timeline"),
    CONDITION("Property condition"),
    REACHABILITY("Seller reachability"),
    DATA_COMPLETENESS("Evidence completeness")
}

/** Items counted by the data completeness factor. */
enum class DataCompletenessItem(val label: String) {
    PROPERTY_LINK("subject property linked"),
    PROPERTY_ADDRESS("property address known"),
    PRICE_EVIDENCE("asking price known"),
    REPAIR_ESTIMATE("repair estimate known"),
    MOTIVATION_EVIDENCE("at least one motivation signal")
}

/** Points awarded for contact facts. */
data class ReachabilityRubric(
    val hasChannelPoints: Int = 4,
    val sellerRespondedPoints: Int = 5,
    val sellerConnectedPoints: Int = 3
) {
    init {
        require(listOf(hasChannelPoints, sellerRespondedPoints, sellerConnectedPoints).all { it >= 0 }) {
            "Reachability points must not be negative"
        }
    }

    val maximumPoints: Int get() = hasChannelPoints + sellerRespondedPoints + sellerConnectedPoints

    fun pointsFor(hasChannel: Boolean, sellerResponded: Boolean, sellerConnected: Boolean): Int =
        (if (hasChannel) hasChannelPoints else 0) +
            (if (sellerResponded) sellerRespondedPoints else 0) +
            (if (sellerConnected) sellerConnectedPoints else 0)

    companion object {
        val DEFAULT = ReachabilityRubric()
    }
}

/** Points awarded per piece of evidence that exists. */
data class DataCompletenessRubric(
    val pointsByItem: Map<DataCompletenessItem, Int> = DEFAULT_POINTS
) {
    init {
        require(pointsByItem.keys == DataCompletenessItem.entries.toSet()) {
            "pointsByItem must define every data completeness item exactly once"
        }
        require(pointsByItem.values.all { it >= 0 }) { "Data completeness points must not be negative" }
    }

    val maximumPoints: Int get() = pointsByItem.values.sum()

    fun pointsFor(presentItems: Set<DataCompletenessItem>): Int =
        presentItems.sumOf { pointsByItem.getValue(it) }

    companion object {
        val DEFAULT_POINTS: Map<DataCompletenessItem, Int> = mapOf(
            DataCompletenessItem.PROPERTY_LINK to 2,
            DataCompletenessItem.PROPERTY_ADDRESS to 2,
            DataCompletenessItem.PRICE_EVIDENCE to 1,
            DataCompletenessItem.REPAIR_ESTIMATE to 1,
            DataCompletenessItem.MOTIVATION_EVIDENCE to 2
        )
        val DEFAULT = DataCompletenessRubric()
    }
}

/**
 * The complete, validated configuration of lead qualification.
 *
 * Every knob has a documented default and is checked for internal consistency at construction time
 * (weights sum to [maximumScore], thresholds are ordered, rubrics match their factor weights), so a
 * misconfigured policy fails loudly instead of quietly skewing the pipeline.
 */
data class LeadQualificationPolicy(
    val version: String = DEFAULT_VERSION,
    val maximumScore: Int = 100,
    val weights: Map<LeadQualificationFactorId, Int> = DEFAULT_WEIGHTS,
    /** The classic wholesale ceiling: pay at most 70% of ARV (before repairs). */
    val wholesaleDiscountPct: Double = 30.0,
    /** Repair cushion applied on top of any repair estimate. */
    val repairContingencyPct: Double = 10.0,
    /** Spread (ARV - repairs - asking) that earns full SPREAD points. */
    val minimumSpreadPctOfArv: Double = 15.0,
    /** Below this spread the deal is disqualified outright. */
    val disqualifyingSpreadPctOfArv: Double = 5.0,
    /** Absolute floor: a deal that cannot produce this much spread is not worth a contract. */
    val minimumWorkableSpreadUsd: Double = 10_000.0,
    val nurtureThreshold: Int = 25,
    val warmThreshold: Int = 45,
    val qualifiedThreshold: Int = 65,
    /** An assessment older than this can no longer justify offer-level moves. */
    val stalenessWindowDays: Int = 30,
    /** Motivation freshness used when the engine aggregates signals itself. */
    val motivationScoring: MotivationScoringPolicy = MotivationScoringPolicy.DEFAULT,
    val reachability: ReachabilityRubric = ReachabilityRubric.DEFAULT,
    val dataCompleteness: DataCompletenessRubric = DataCompletenessRubric.DEFAULT
) {
    init {
        require(version.isNotBlank()) { "Qualification policy version must not be blank" }
        require(maximumScore in 10..100) { "Qualification maximumScore must be within 10..100" }
        require(weights.keys == LeadQualificationFactorId.entries.toSet()) {
            "weights must define every qualification factor exactly once"
        }
        require(weights.values.all { it > 0 }) { "Qualification factor weights must be positive" }
        require(weights.values.sum() == maximumScore) {
            "Qualification factor weights must sum to maximumScore ($maximumScore), got ${weights.values.sum()}"
        }
        require(weights.getValue(LeadQualificationFactorId.REACHABILITY) == reachability.maximumPoints) {
            "REACHABILITY weight must equal the reachability rubric total (${reachability.maximumPoints})"
        }
        require(weights.getValue(LeadQualificationFactorId.DATA_COMPLETENESS) == dataCompleteness.maximumPoints) {
            "DATA_COMPLETENESS weight must equal the data completeness rubric total (${dataCompleteness.maximumPoints})"
        }
        require(wholesaleDiscountPct.isFinite() && wholesaleDiscountPct in 0.0..80.0) {
            "wholesaleDiscountPct must be finite and within 0..80"
        }
        require(repairContingencyPct.isFinite() && repairContingencyPct in 0.0..100.0) {
            "repairContingencyPct must be finite and within 0..100"
        }
        require(minimumSpreadPctOfArv.isFinite() && disqualifyingSpreadPctOfArv.isFinite()) {
            "Spread percentages must be finite"
        }
        require(disqualifyingSpreadPctOfArv in 0.0..50.0 && minimumSpreadPctOfArv in 0.0..90.0) {
            "Spread percentages must be within 0..50 (disqualifying) and 0..90 (minimum)"
        }
        require(disqualifyingSpreadPctOfArv <= minimumSpreadPctOfArv) {
            "disqualifyingSpreadPctOfArv must not exceed minimumSpreadPctOfArv"
        }
        require(minimumWorkableSpreadUsd.isFinite() && minimumWorkableSpreadUsd >= 0.0) {
            "minimumWorkableSpreadUsd must be finite and non-negative"
        }
        require(nurtureThreshold in 0..maximumScore && warmThreshold in 0..maximumScore && qualifiedThreshold in 0..maximumScore) {
            "Qualification thresholds must be within 0..maximumScore"
        }
        require(nurtureThreshold <= warmThreshold && warmThreshold <= qualifiedThreshold) {
            "Qualification thresholds must be ordered nurture <= warm <= qualified"
        }
        require(stalenessWindowDays in 1..365) { "stalenessWindowDays must be within 1..365" }
    }

    companion object {
        const val DEFAULT_VERSION = "wholesale-lead-qualification-v1"

        /** Default weights: spread and motivation dominate, evidence quality closes the loop. */
        val DEFAULT_WEIGHTS: Map<LeadQualificationFactorId, Int> = mapOf(
            LeadQualificationFactorId.SPREAD to 30,
            LeadQualificationFactorId.MOTIVATION to 25,
            LeadQualificationFactorId.TIMELINE to 15,
            LeadQualificationFactorId.CONDITION to 10,
            LeadQualificationFactorId.REACHABILITY to 12,
            LeadQualificationFactorId.DATA_COMPLETENESS to 8
        )

        /** Points per condition severity, capped at the CONDITION weight by construction. */
        val DEFAULT_CONDITION_POINTS: Map<RehabSeverity, Int> = mapOf(
            RehabSeverity.UNKNOWN to 0,
            RehabSeverity.NONE to 3,
            RehabSeverity.MINOR to 6,
            RehabSeverity.MODERATE to 8,
            RehabSeverity.MAJOR to 10,
            RehabSeverity.GUT to 10
        )

        val DEFAULT = LeadQualificationPolicy()
    }

    fun weightOf(factor: LeadQualificationFactorId): Int = weights.getValue(factor)

    fun conditionPointsFor(severity: RehabSeverity, maximumPoints: Int): Int =
        (DEFAULT_CONDITION_POINTS[severity] ?: 0).coerceAtMost(maximumPoints)
}

/**
 * Facts the qualification engine consumes.
 *
 * Every monetary field is optional and validated to be finite and non-negative: missing facts lower
 * the score (and raise blockers) instead of being treated as zero, and non-finite input is rejected
 * rather than silently poisoning the pipeline.
 */
data class LeadQualificationInputs(
    val askingPriceUsd: Double? = null,
    val afterRepairValueUsd: Double? = null,
    val estimatedRepairCostUsd: Double? = null,
    val estimatedAsIsValueUsd: Double? = null,
    val estimatedRentUsdMonthly: Double? = null,
    val motivationSignals: List<MotivationSignal> = emptyList(),
    val timeline: SellerTimeline = SellerTimeline.UNKNOWN,
    val condition: PropertyConditionAssessment? = null,
    val hasPropertyLink: Boolean = false,
    val hasPropertyAddress: Boolean = false,
    val hasUsableContactChannel: Boolean = false,
    val sellerResponded: Boolean = false,
    val sellerConnected: Boolean = false,
    /** The seller said no, or the deal was rejected: a hard disqualifier. */
    val sellerDeclined: Boolean = false,
    /** A do-not-contact request (or a legal freeze) is in effect. */
    val doNotContact: Boolean = false,
    /** `null` when the service area is unknown, which never disqualifies by itself. */
    val withinServiceArea: Boolean? = null
) {
    init {
        listOf(
            "askingPriceUsd" to askingPriceUsd,
            "afterRepairValueUsd" to afterRepairValueUsd,
            "estimatedRepairCostUsd" to estimatedRepairCostUsd,
            "estimatedAsIsValueUsd" to estimatedAsIsValueUsd,
            "estimatedRentUsdMonthly" to estimatedRentUsdMonthly
        ).forEach { (name, value) ->
            require(value == null || (value.isFinite() && value >= 0.0)) {
                "LeadQualificationInputs.$name must be finite and non-negative"
            }
        }
        require(askingPriceUsd == null || askingPriceUsd > 0.0) { "askingPriceUsd must be positive when provided" }
        require(afterRepairValueUsd == null || afterRepairValueUsd > 0.0) { "afterRepairValueUsd must be positive when provided" }
        require(!sellerConnected || sellerResponded) { "A connected seller has necessarily responded" }
    }

    /** Items of the data-completeness rubric that are present. */
    fun completenessItems(policy: LeadQualificationPolicy): Set<DataCompletenessItem> = buildSet {
        if (hasPropertyLink) add(DataCompletenessItem.PROPERTY_LINK)
        if (hasPropertyAddress) add(DataCompletenessItem.PROPERTY_ADDRESS)
        if (askingPriceUsd != null) add(DataCompletenessItem.PRICE_EVIDENCE)
        if (effectiveRepairCostUsd() != null) add(DataCompletenessItem.REPAIR_ESTIMATE)
        if (motivationSignals.isNotEmpty()) add(DataCompletenessItem.MOTIVATION_EVIDENCE)
    }

    /** Repair cost, from the condition assessment (explicit estimate or bucket × square feet). */
    fun effectiveRepairCostUsd(): Double? = estimatedRepairCostUsd ?: condition?.effectiveRehabEstimateUsd

    companion object {
        /**
         * Maps the lead aggregate onto qualification inputs.
         *
         * This is the only supported bridge from the CRM records to the engine, so the operator UI and
         * the automation path can never disagree about what the facts are.
         */
        fun fromLead(lead: Lead, withinServiceArea: Boolean? = null): LeadQualificationInputs {
            val property = lead.subjectPropertyLink
            val doNotContact = lead.sellers.any { it.isFrozen } ||
                lead.communications.any { it.demandsDoNotContact }
            val sellerDeclined = lead.communications.any {
                it.outcome == CommunicationOutcome.SELLER_NOT_INTERESTED || it.outcome == CommunicationOutcome.OFFER_REJECTED
            }
            val sellerResponded = lead.communications.any { it.outcome.isSellerResponse }
            val sellerConnected = lead.communications.any { it.outcome.isConnected }
            return LeadQualificationInputs(
                askingPriceUsd = property?.askingPriceUsd,
                afterRepairValueUsd = property?.afterRepairValueUsd,
                estimatedRepairCostUsd = property?.estimatedRepairCostUsd,
                estimatedAsIsValueUsd = property?.estimatedAsIsValueUsd,
                estimatedRentUsdMonthly = property?.estimatedRentUsdMonthly,
                motivationSignals = lead.motivationSignals,
                timeline = lead.timeline.timeline,
                condition = lead.condition,
                hasPropertyLink = property != null,
                hasPropertyAddress = property?.address != null,
                hasUsableContactChannel = lead.sellers.any { it.isContactable },
                sellerResponded = sellerResponded,
                sellerConnected = sellerConnected,
                sellerDeclined = sellerDeclined,
                doNotContact = doNotContact,
                withinServiceArea = withinServiceArea
            )
        }
    }
}

/** Arithmetic behind the spread, exposed so an operator (and the tests) can see exactly why. */
data class LeadSpreadAnalysis(
    val askingPriceUsd: Double,
    val afterRepairValueUsd: Double,
    val repairCostUsd: Double,
    val repairContingencyUsd: Double,
    /** Everything the wholesaler pays/must fund before the spread: price + repairs + contingency. */
    val totalProjectCostUsd: Double,
    /** ARV - total project cost. */
    val projectedSpreadUsd: Double,
    val spreadPctOfArv: Double,
    /** 70%-rule ceiling: ARV × (1 - discount) - repairs (contingency included). */
    val maximumAllowableOfferUsd: Double,
    /** MAO - asking. Positive means the asking price is already inside the buy box. */
    val negotiationRoomUsd: Double,
    val meetsPolicyFloor: Boolean,
    val floorDescription: String
) {
    init {
        require(askingPriceUsd.isFinite() && askingPriceUsd > 0.0) { "Spread analysis requires a positive asking price" }
        require(afterRepairValueUsd.isFinite() && afterRepairValueUsd > 0.0) { "Spread analysis requires a positive ARV" }
        require(repairCostUsd.isFinite() && repairCostUsd >= 0.0) { "Spread analysis repair cost must be finite and non-negative" }
        require(repairContingencyUsd.isFinite() && repairContingencyUsd >= 0.0) { "Spread analysis contingency must be finite and non-negative" }
        require(projectedSpreadUsd.isFinite()) { "Spread analysis projected spread must be finite" }
        require(spreadPctOfArv.isFinite()) { "Spread analysis spread percentage must be finite" }
    }

    val isThin: Boolean get() = !meetsPolicyFloor

    fun describe(): String = String.format(
        Locale.US,
        "asking=\$%.0f arv=\$%.0f repairs=\$%.0f spread=\$%.0f (%.1f%% of ARV) mao=\$%.0f room=\$%.0f",
        askingPriceUsd,
        afterRepairValueUsd,
        repairCostUsd + repairContingencyUsd,
        projectedSpreadUsd,
        spreadPctOfArv,
        maximumAllowableOfferUsd,
        negotiationRoomUsd
    )
}

/** One factor's contribution, with the exact numbers behind it. */
data class LeadQualificationFactor(
    val id: LeadQualificationFactorId,
    val points: Int,
    val maximumPoints: Int,
    val detail: String
) {
    init {
        require(points in 0..maximumPoints) { "Factor ${id.name} points must be within 0..$maximumPoints" }
    }

    val label: String get() = id.label
}

/** A deterministic reason a lead is blocked or disqualified. */
data class LeadQualificationBlocker(val code: String, val message: String) {
    init {
        require(code.isNotBlank()) { "Blocker code must not be blank" }
        require(message.isNotBlank()) { "Blocker message must not be blank" }
    }
}

/** Result of one qualification assessment. Immutable, self-describing and replayable from the audit log. */
data class LeadQualificationAssessment(
    val state: LeadQualificationState,
    val score: Int,
    val rawScore: Int,
    val maximumScore: Int,
    val factors: List<LeadQualificationFactor>,
    val blockers: List<LeadQualificationBlocker> = emptyList(),
    val disqualifiers: List<LeadQualificationBlocker> = emptyList(),
    val warnings: List<String> = emptyList(),
    val spread: LeadSpreadAnalysis? = null,
    val motivation: MotivationAggregate,
    val evaluatedAtEpochMillis: Long,
    val policyVersion: String = LeadQualificationPolicy.DEFAULT_VERSION
) {
    init {
        require(maximumScore in 10..100) { "maximumScore must be within 10..100" }
        require(rawScore in 0..maximumScore) { "rawScore must be within 0..maximumScore" }
        require(score == rawScore.coerceIn(0, maximumScore)) { "score must be the clamped rawScore" }
        require(evaluatedAtEpochMillis > 0L) { "Assessment evaluatedAtEpochMillis must be positive" }
        require(policyVersion.isNotBlank()) { "Assessment policy version must not be blank" }
        require(state != LeadQualificationState.NOT_ASSESSED) { "An assessment result cannot be NOT_ASSESSED" }
        require(factors.map { it.id }.toSet().size == factors.size) { "Assessment factors must be unique" }
        require((state == LeadQualificationState.DISQUALIFIED) == disqualifiers.isNotEmpty()) {
            "A disqualifying assessment must carry disqualifiers, and only a disqualifying one may"
        }
    }

    val isQualified: Boolean get() = state == LeadQualificationState.QUALIFIED

    val isActionable: Boolean get() = state.isActionable

    val isDisqualified: Boolean get() = state.isDisqualified

    val isBlocked: Boolean get() = blockers.isNotEmpty()

    /** True when evidence blockers capped the state below what the score alone would allow. */
    val wasCappedByBlockers: Boolean get() = blockers.isNotEmpty() && !isDisqualified

    fun factor(id: LeadQualificationFactorId): LeadQualificationFactor? = factors.firstOrNull { it.id == id }

    fun blockerCodes(): List<String> = blockers.map { it.code }

    fun disqualifierCodes(): List<String> = disqualifiers.map { it.code }

    fun ageDays(asOfEpochMillis: Long): Long = CrmTime.daysBetween(evaluatedAtEpochMillis, asOfEpochMillis)

    /** True when the assessment may no longer justify offer-level decisions. */
    fun isStale(asOfEpochMillis: Long, policy: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT): Boolean =
        ageDays(asOfEpochMillis) > policy.stalenessWindowDays

    fun summary(): String {
        val base = "${state.name} (score $score/$maximumScore, $policyVersion)"
        return if (disqualifiers.isNotEmpty()) {
            "$base — disqualified: ${disqualifiers.first().message}"
        } else if (blockers.isNotEmpty()) {
            "$base — blocked: ${blockers.first().message}"
        } else {
            base
        }
    }

    fun describe(): String = buildString {
        append(summary())
        factors.forEach { append('\n').append("  ").append(it.label).append(": ") }
        append('\n')
        spread?.let { append("  ").append(it.describe()).append('\n') }
        append("  motivation: ").append(motivation.describe())
    }
}

/** One audited qualification state change. */
data class LeadQualificationTransition(
    val from: LeadQualificationState,
    val to: LeadQualificationState,
    val reason: LeadQualificationReason,
    val atEpochMillis: Long,
    val actor: String,
    val score: Int,
    val maximumScore: Int,
    val policyVersion: String,
    val detail: String? = null
) {
    init {
        require(from != to) { "A qualification transition must change the state" }
        require(atEpochMillis > 0L) { "Qualification transition atEpochMillis must be positive" }
        require(actor.isNotBlank()) { "Qualification transition must record an actor" }
        require(score in 0..maximumScore) { "Qualification transition score must be within 0..maximumScore" }
        require(policyVersion.isNotBlank()) { "Qualification transition policy version must not be blank" }
    }

    /** True when the move went up the ladder (a higher state). */
    val isImprovement: Boolean get() = from.rank < to.rank

    fun describe(): String = buildString {
        append(from.name).append(" → ").append(to.name)
        append(" (").append(reason.name).append(", score ").append(score).append('/').append(maximumScore)
        append(", ").append(policyVersion).append(')')
        detail?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }
}

/** Outcome of a qualification state change request. */
sealed interface LeadQualificationResult {

    /** The state changed; the transition is recorded on the lead's qualification history. */
    data class Applied(
        val lead: Lead,
        val assessment: LeadQualificationAssessment,
        val transition: LeadQualificationTransition
    ) : LeadQualificationResult

    /**
     * The recomputed state equals the current one: the assessment is refreshed (and the audit metadata
     * touched) but no history entry is appended, because nothing changed for an auditor to review.
     */
    data class NoOp(
        val lead: Lead,
        val assessment: LeadQualificationAssessment?,
        val state: LeadQualificationState
    ) : LeadQualificationResult

    /** The state machine refused the change (unauditable or meaningless). */
    data class Illegal(
        val from: LeadQualificationState,
        val to: LeadQualificationState,
        val reason: LeadQualificationReason,
        val message: String
    ) : LeadQualificationResult
}

/** Convenience accessor: the resulting lead, or a hard failure when the move was refused. */
val LeadQualificationResult.lead: Lead
    get() = when (this) {
        is LeadQualificationResult.Applied -> lead
        is LeadQualificationResult.NoOp -> lead
        is LeadQualificationResult.Illegal ->
            throw IllegalStateException("Illegal qualification transition $from → $to ($reason): $message")
    }

/**
 * Deterministic qualification state machine.
 *
 * Every pair of states is reachable *when the evidence justifies it* — qualification is a judgement
 * about facts, not a workflow, so the guardrail is the reason code the move must name (see
 * [allowedReasons]) rather than an artificially sparse topology. The one exception is
 * [LeadQualificationState.NOT_ASSESSED], which can only be exited with `FIRST_ASSESSMENT`.
 *
 * The table is the single source of truth for where qualification may go. Note that
 * [LeadQualificationState.DISQUALIFIED] is *not* a dead end: a disqualified lead whose disqualifying
 * fact stops being true (the seller called back, the price dropped, the property turned out to be
 * inside the service area) is re-assessable, but only with an explicit reason code, so an operator can
 * always see why a previously rejected lead became workable again.
 */
object LeadQualificationStateMachine {

    private val ALLOWED: Map<LeadQualificationState, Set<LeadQualificationState>> = mapOf(
        LeadQualificationState.NOT_ASSESSED to setOf(
            LeadQualificationState.DISQUALIFIED,
            LeadQualificationState.UNQUALIFIED,
            LeadQualificationState.NURTURE,
            LeadQualificationState.WARM,
            LeadQualificationState.QUALIFIED
        ),
        LeadQualificationState.DISQUALIFIED to setOf(
            LeadQualificationState.UNQUALIFIED,
            LeadQualificationState.NURTURE,
            LeadQualificationState.WARM,
            LeadQualificationState.QUALIFIED
        ),
        LeadQualificationState.UNQUALIFIED to setOf(
            LeadQualificationState.DISQUALIFIED,
            LeadQualificationState.NURTURE,
            LeadQualificationState.WARM,
            LeadQualificationState.QUALIFIED
        ),
        LeadQualificationState.NURTURE to setOf(
            LeadQualificationState.DISQUALIFIED,
            LeadQualificationState.UNQUALIFIED,
            LeadQualificationState.WARM,
            LeadQualificationState.QUALIFIED
        ),
        LeadQualificationState.WARM to setOf(
            LeadQualificationState.DISQUALIFIED,
            LeadQualificationState.UNQUALIFIED,
            LeadQualificationState.NURTURE,
            LeadQualificationState.QUALIFIED
        ),
        LeadQualificationState.QUALIFIED to setOf(
            LeadQualificationState.DISQUALIFIED,
            LeadQualificationState.UNQUALIFIED,
            LeadQualificationState.NURTURE,
            LeadQualificationState.WARM
        )
    )

    fun allowedTargets(from: LeadQualificationState): Set<LeadQualificationState> = ALLOWED[from] ?: emptySet()

    fun canTransition(from: LeadQualificationState, to: LeadQualificationState): Boolean =
        from == to || allowedTargets(from).contains(to)

    /** Reason codes accepted for an edge, so the audit trail cannot claim a nonsensical cause. */
    fun allowedReasons(from: LeadQualificationState, to: LeadQualificationState): Set<LeadQualificationReason> {
        if (from == to) return setOf(LeadQualificationReason.REASSESSED)
        if (!canTransition(from, to)) return emptySet()
        return when {
            from == LeadQualificationState.NOT_ASSESSED -> setOf(LeadQualificationReason.FIRST_ASSESSMENT)
            to == LeadQualificationState.DISQUALIFIED -> setOf(
                LeadQualificationReason.EVIDENCE_CONTRADICTED,
                LeadQualificationReason.SELLER_DECLINED,
                LeadQualificationReason.MANUAL_OVERRIDE
            )
            to.rank > from.rank -> setOf(
                LeadQualificationReason.EVIDENCE_ADDED,
                LeadQualificationReason.SELLER_RESPONDED,
                LeadQualificationReason.MANUAL_OVERRIDE
            )
            else -> setOf(
                LeadQualificationReason.EVIDENCE_DECAYED,
                LeadQualificationReason.EVIDENCE_CONTRADICTED,
                LeadQualificationReason.MANUAL_OVERRIDE
            )
        }
    }

    /**
     * Applies a qualification state change to [lead].
     *
     * The machine owns every mutation of [Lead.qualification] / [Lead.qualificationHistory]: it stamps
     * the audit metadata, appends the transition and refuses anything the table or the reason set
     * forbids.
     */
    fun transition(
        lead: Lead,
        target: LeadQualificationState,
        reason: LeadQualificationReason,
        atEpochMillis: Long,
        actor: String,
        assessment: LeadQualificationAssessment,
        detail: String? = null
    ): LeadQualificationResult {
        val from = lead.qualificationState
        if (!canTransition(from, target)) {
            return LeadQualificationResult.Illegal(
                from, target, reason, "transition not allowed by the qualification state machine"
            )
        }
        if (!allowedReasons(from, target).contains(reason)) {
            return LeadQualificationResult.Illegal(
                from, target, reason, "reason ${reason.name} does not justify $from → $target"
            )
        }
        require(assessment.state == target) {
            "The stored assessment must describe the state it justifies: assessment says " +
                "${assessment.state}, transition targets $target"
        }
        val auditedLead = lead.copy(
            qualification = assessment,
            audit = lead.audit.touchedBy(actor, atEpochMillis)
        )
        if (from == target) {
            return LeadQualificationResult.NoOp(auditedLead, assessment, target)
        }
        val transition = LeadQualificationTransition(
            from = from,
            to = target,
            reason = reason,
            atEpochMillis = atEpochMillis,
            actor = actor,
            score = assessment.score,
            maximumScore = assessment.maximumScore,
            policyVersion = assessment.policyVersion,
            detail = detail
        )
        return LeadQualificationResult.Applied(
            lead = auditedLead.copy(qualificationHistory = lead.qualificationHistory + transition),
            assessment = assessment,
            transition = transition
        )
    }
}

/**
 * The deterministic qualification engine.
 *
 * Every step is pure: inputs + policy + timestamp in, [LeadQualificationAssessment] out. There is no
 * clock read, no randomness, no AI and no network. Two operators, two devices and two days apart
 * produce identical results from identical facts, which is what lets the CRM explain a past decision.
 */
object LeadQualificationEngine {

    fun evaluate(
        inputs: LeadQualificationInputs,
        atEpochMillis: Long,
        policy: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT
    ): LeadQualificationAssessment {
        require(atEpochMillis > 0L) { "atEpochMillis must be positive" }

        val motivScoring = policy.motivationScoring
        val motivation = MotivationSignals.aggregate(inputs.motivationSignals, atEpochMillis, motivScoring)
        val factors = mutableListOf<LeadQualificationFactor>()
        val blockers = mutableListOf<LeadQualificationBlocker>()
        val disqualifiers = mutableListOf<LeadQualificationBlocker>()
        val warnings = mutableListOf<String>()

        // ── SPREAD: the whole reason a wholesale deal exists ───────────────────────────────────
        val spread = computeSpread(inputs, policy)
        val spreadMaximum = policy.weightOf(LeadQualificationFactorId.SPREAD)
        val spreadPoints: Int
        val spreadDetail: String
        if (spread == null) {
            spreadPoints = 0
            spreadDetail = "not computable — ${spreadUnavailableReason(inputs)}"
            warnings += "Spread not computable: ${spreadUnavailableReason(inputs)}"
        } else {
            spreadPoints = scoreSpread(spread, policy, spreadMaximum)
            spreadDetail = spread.describe()
            if (!spread.meetsPolicyFloor) {
                disqualifiers += LeadQualificationBlocker(
                    CrmBlockerCodes.SPREAD_BELOW_FLOOR,
                    "projected spread ${formatUsd(spread.projectedSpreadUsd)} " +
                        "(${formatPct(spread.spreadPctOfArv)} of ARV) is below the policy floor " +
                        "(${spread.floorDescription})"
                )
            }
            if (spread.negotiationRoomUsd < 0.0) {
                warnings += "Asking price is ${formatUsd(-spread.negotiationRoomUsd)} above the " +
                    "${formatPct(100.0 - policy.wholesaleDiscountPct)} of ARV ceiling"
            }
        }
        factors += LeadQualificationFactor(
            LeadQualificationFactorId.SPREAD, spreadPoints, spreadMaximum, spreadDetail
        )

        // ── MOTIVATION ─────────────────────────────────────────────────────────────────────────
        val motivationMaximum = policy.weightOf(LeadQualificationFactorId.MOTIVATION)
        val motivationPoints = MotivationSignals.scaleToPoints(motivation.index, motivationMaximum)
        if (!motivation.hasSignals) {
            warnings += "No motivation signal recorded: the seller's reason to sell is unknown"
        }
        factors += LeadQualificationFactor(
            LeadQualificationFactorId.MOTIVATION, motivationPoints, motivationMaximum, motivation.describe()
        )

        // ── TIMELINE ───────────────────────────────────────────────────────────────────────────
        val timelineMaximum = policy.weightOf(LeadQualificationFactorId.TIMELINE)
        val timelinePoints = scaleUrgency(inputs.timeline.urgencyPoints, timelineMaximum)
        if (!inputs.timeline.isKnown) {
            warnings += "Seller timeline unknown: closing feasibility is unverified"
        }
        factors += LeadQualificationFactor(
            LeadQualificationFactorId.TIMELINE,
            timelinePoints,
            timelineMaximum,
            "timeline ${inputs.timeline.name} (urgency ${inputs.timeline.urgencyPoints}/100)"
        )

        // ── CONDITION ──────────────────────────────────────────────────────────────────────────
        val conditionMaximum = policy.weightOf(LeadQualificationFactorId.CONDITION)
        val severity = inputs.condition?.severity ?: RehabSeverity.UNKNOWN
        val conditionPoints = policy.conditionPointsFor(severity, conditionMaximum)
        when {
            inputs.condition == null -> warnings += "Condition not assessed: repair scope is unverified"
            severity.requiresInspection -> warnings +=
                "Repairs are ${severity.name.lowercase(Locale.US)}: order an inspection before the contract's contingency expires"
        }
        factors += LeadQualificationFactor(
            LeadQualificationFactorId.CONDITION,
            conditionPoints,
            conditionMaximum,
            inputs.condition?.describe() ?: "no assessment (severity UNKNOWN)"
        )

        // ── REACHABILITY ───────────────────────────────────────────────────────────────────────
        val reachabilityMaximum = policy.weightOf(LeadQualificationFactorId.REACHABILITY)
        val reachabilityPoints = policy.reachability.pointsFor(
            hasChannel = inputs.hasUsableContactChannel,
            sellerResponded = inputs.sellerResponded,
            sellerConnected = inputs.sellerConnected
        )
        factors += LeadQualificationFactor(
            LeadQualificationFactorId.REACHABILITY,
            reachabilityPoints,
            reachabilityMaximum,
            "channel=${inputs.hasUsableContactChannel} responded=${inputs.sellerResponded} " +
                "connected=${inputs.sellerConnected}"
        )

        // ── DATA COMPLETENESS ──────────────────────────────────────────────────────────────────
        val completenessMaximum = policy.weightOf(LeadQualificationFactorId.DATA_COMPLETENESS)
        val presentItems = inputs.completenessItems(policy)
        val completenessPoints = policy.dataCompleteness.pointsFor(presentItems)
        val missingItems = DataCompletenessItem.entries.filterNot { it in presentItems }
        factors += LeadQualificationFactor(
            LeadQualificationFactorId.DATA_COMPLETENESS,
            completenessPoints,
            completenessMaximum,
            if (missingItems.isEmpty()) {
                "all ${DataCompletenessItem.entries.size} evidence items present"
            } else {
                "missing: ${missingItems.joinToString(", ") { it.label }}"
            }
        )

        // ── Blockers (workable, unproven) and disqualifiers (a hard fact says no) ──────────────
        if (!inputs.hasPropertyLink) {
            blockers += LeadQualificationBlocker(
                CrmBlockerCodes.MISSING_PROPERTY_LINK, "no subject property is linked to the lead"
            )
        }
        if (!inputs.hasPropertyAddress) {
            blockers += LeadQualificationBlocker(
                CrmBlockerCodes.MISSING_PROPERTY_ADDRESS, "the subject property address is unknown"
            )
        }
        if (inputs.askingPriceUsd == null) {
            blockers += LeadQualificationBlocker(
                CrmBlockerCodes.MISSING_PRICE_EVIDENCE, "no asking price on record"
            )
        }
        if (inputs.afterRepairValueUsd == null) {
            blockers += LeadQualificationBlocker(
                CrmBlockerCodes.MISSING_VALUE_EVIDENCE, "no after-repair value on record"
            )
        }
        if (inputs.effectiveRepairCostUsd() == null) {
            blockers += LeadQualificationBlocker(
                CrmBlockerCodes.MISSING_REPAIR_ESTIMATE, "no repair estimate or condition assessment on record"
            )
        }
        if (inputs.motivationSignals.isEmpty()) {
            blockers += LeadQualificationBlocker(
                CrmBlockerCodes.MISSING_MOTIVATION_EVIDENCE, "no motivation signal has been captured"
            )
        } else if (motivScoring.requireDocumentedEvidenceForQualified && motivation.documentedSignals == 0) {
            blockers += LeadQualificationBlocker(
                CrmBlockerCodes.MISSING_DOCUMENTED_MOTIVATION,
                "motivation is recorded but nothing documents it (public record, seller statement or field visit)"
            )
        }
        if (!inputs.hasUsableContactChannel) {
            blockers += LeadQualificationBlocker(
                CrmBlockerCodes.MISSING_CONTACT_CHANNEL, "no permitted contact channel for the seller"
            )
        }

        if (inputs.sellerDeclined) {
            disqualifiers += LeadQualificationBlocker(
                CrmBlockerCodes.SELLER_DECLINED, "the seller declined or rejected an offer"
            )
        }
        if (inputs.doNotContact) {
            disqualifiers += LeadQualificationBlocker(
                CrmBlockerCodes.DO_NOT_CONTACT, "the seller is on do-not-contact (or legally frozen)"
            )
        }
        if (inputs.withinServiceArea == false) {
            disqualifiers += LeadQualificationBlocker(
                CrmBlockerCodes.OUTSIDE_SERVICE_AREA, "the property is outside the buy box's service area"
            )
        }

        val rawScore = factors.sumOf { it.points }
        val score = rawScore.coerceIn(0, policy.maximumScore)
        val scoredState = when {
            score >= policy.qualifiedThreshold -> LeadQualificationState.QUALIFIED
            score >= policy.warmThreshold -> LeadQualificationState.WARM
            score >= policy.nurtureThreshold -> LeadQualificationState.NURTURE
            else -> LeadQualificationState.UNQUALIFIED
        }
        val state = when {
            disqualifiers.isNotEmpty() -> LeadQualificationState.DISQUALIFIED
            blockers.isNotEmpty() -> {
                val capped = scoredState.atMost(LeadQualificationState.NURTURE)
                if (capped != scoredState) {
                    warnings += "State capped at NURTURE by ${blockers.size} evidence blocker(s): " +
                        blockers.joinToString(", ") { it.code }
                }
                capped
            }
            else -> scoredState
        }
        if (state == LeadQualificationState.UNQUALIFIED && disqualifiers.isEmpty()) {
            warnings += "Score $score is below the nurture threshold ${policy.nurtureThreshold}"
        }

        return LeadQualificationAssessment(
            state = state,
            score = score,
            rawScore = rawScore,
            maximumScore = policy.maximumScore,
            factors = factors,
            blockers = blockers,
            disqualifiers = disqualifiers,
            warnings = warnings.distinct(),
            spread = spread,
            motivation = motivation,
            evaluatedAtEpochMillis = atEpochMillis,
            policyVersion = policy.version
        )
    }

    /**
     * Recomputes qualification from current facts and records the resulting state change, if any.
     *
     * This is *the* deterministic qualification transition: the target state is derived from the
     * assessment (never chosen by the caller) and the reason code is derived from how the state moved,
     * so the audit trail always explains itself.
     */
    fun reassess(
        lead: Lead,
        atEpochMillis: Long,
        actor: String,
        inputs: LeadQualificationInputs = LeadQualificationInputs.fromLead(lead),
        policy: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT
    ): LeadQualificationResult {
        val assessment = evaluate(inputs, atEpochMillis, policy)
        val reason = deriveReason(lead, assessment)
        return LeadQualificationStateMachine.transition(
            lead = lead,
            target = assessment.state,
            reason = reason,
            atEpochMillis = atEpochMillis,
            actor = actor,
            assessment = assessment
        )
    }

    /**
     * Degrades a stale assessment ([LeadQualificationPolicy.stalenessWindowDays]).
     *
     * Nothing about the seller changed — the *evidence* did. A QUALIFIED lead that has not been
     * re-verified inside the window drops to WARM, and a stale WARM drops to NURTURE, because an offer
     * may no longer be justified by facts nobody has confirmed recently.
     */
    fun decayIfStale(
        lead: Lead,
        atEpochMillis: Long,
        actor: String,
        policy: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT
    ): LeadQualificationResult {
        val assessment = lead.qualification
            ?: return LeadQualificationResult.NoOp(lead, null, lead.qualificationState)
        if (!assessment.isStale(atEpochMillis, policy)) {
            return LeadQualificationResult.NoOp(lead, assessment, lead.qualificationState)
        }
        val target = when (assessment.state) {
            LeadQualificationState.QUALIFIED -> LeadQualificationState.WARM
            LeadQualificationState.WARM -> LeadQualificationState.NURTURE
            else -> return LeadQualificationResult.NoOp(lead, assessment, lead.qualificationState)
        }
        val decayWarning = "evidence decayed: the assessment is " +
            "${assessment.ageDays(atEpochMillis)} days old (policy window ${policy.stalenessWindowDays} days)"
        val decayed = assessment.copy(state = target, warnings = assessment.warnings + decayWarning)
        return LeadQualificationStateMachine.transition(
            lead = lead,
            target = target,
            reason = LeadQualificationReason.EVIDENCE_DECAYED,
            atEpochMillis = atEpochMillis,
            actor = actor,
            assessment = decayed,
            detail = "assessment is ${assessment.ageDays(atEpochMillis)} days old " +
                "(policy window ${policy.stalenessWindowDays} days)"
        )
    }

    /**
     * Operator override. The only way to move qualification against the computed score, and therefore
     * the only reason code that is not automatic — it must be justified with a note.
     */
    fun overrideState(
        lead: Lead,
        target: LeadQualificationState,
        atEpochMillis: Long,
        actor: String,
        note: String,
        policy: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT
    ): LeadQualificationResult {
        require(note.isNotBlank()) { "A manual qualification override must be justified with a note" }
        val assessment = lead.qualification
            ?: return LeadQualificationResult.Illegal(
                lead.qualificationState, target, LeadQualificationReason.MANUAL_OVERRIDE,
                "no assessment exists yet: assess the lead before overriding its state"
            )
        val overridden = when {
            target == LeadQualificationState.DISQUALIFIED && assessment.disqualifiers.isEmpty() ->
                assessment.copy(
                    state = target,
                    disqualifiers = listOf(
                        LeadQualificationBlocker(CrmBlockerCodes.MANUAL_OVERRIDE_QUALIFICATION, "operator override: $note")
                    ),
                    warnings = assessment.warnings + "state overridden to DISQUALIFIED by $actor"
                )
            target != LeadQualificationState.DISQUALIFIED && assessment.disqualifiers.isNotEmpty() ->
                assessment.copy(
                    state = target,
                    disqualifiers = emptyList(),
                    warnings = assessment.warnings + "disqualifiers cleared by $actor: $note"
                )
            else -> assessment.copy(
                state = target,
                warnings = assessment.warnings + "state overridden to ${target.name} by $actor: $note"
            )
        }
        return LeadQualificationStateMachine.transition(
            lead = lead,
            target = target,
            reason = LeadQualificationReason.MANUAL_OVERRIDE,
            atEpochMillis = atEpochMillis,
            actor = actor,
            assessment = overridden,
            detail = note.take(MAX_OVERRIDE_NOTE_LENGTH)
        )
    }

    private const val MAX_OVERRIDE_NOTE_LENGTH = 300

    /** Derives the audit reason from the movement of the state ladder. */
    private fun deriveReason(lead: Lead, assessment: LeadQualificationAssessment): LeadQualificationReason {
        val previous = lead.qualificationState
        val target = assessment.state
        if (previous == LeadQualificationState.NOT_ASSESSED) return LeadQualificationReason.FIRST_ASSESSMENT
        if (target == LeadQualificationState.DISQUALIFIED) {
            val codes = assessment.disqualifierCodes()
            return if (CrmBlockerCodes.SELLER_DECLINED in codes || CrmBlockerCodes.DO_NOT_CONTACT in codes) {
                LeadQualificationReason.SELLER_DECLINED
            } else {
                LeadQualificationReason.EVIDENCE_CONTRADICTED
            }
        }
        if (target.rank > previous.rank) {
            // "SELLER_RESPONDED" is only honest when the response is *newer than the last assessment*:
            // a lead that already had a connected call and merely gained other evidence is EVIDENCE_ADDED.
            val lastAssessmentAt = lead.qualification?.evaluatedAtEpochMillis ?: 0L
            val freshResponse = lead.communications.any {
                it.outcome.isSellerResponse && it.loggedAtEpochMillis > lastAssessmentAt
            }
            return if (freshResponse) {
                LeadQualificationReason.SELLER_RESPONDED
            } else {
                LeadQualificationReason.EVIDENCE_ADDED
            }
        }
        if (target.rank < previous.rank) return LeadQualificationReason.EVIDENCE_DECAYED
        return LeadQualificationReason.REASSESSED
    }

    /**
     * Spread arithmetic, or null when the inputs cannot support it.
     *
     * An unknown repair scope is *not* treated as zero repairs: valuing a distressed house as if it
     * needed no work is how wholesalers overpay. The caller sees a blocker instead.
     */
    private fun computeSpread(inputs: LeadQualificationInputs, policy: LeadQualificationPolicy): LeadSpreadAnalysis? {
        val asking = inputs.askingPriceUsd ?: return null
        val arv = inputs.afterRepairValueUsd ?: return null
        val repairs = inputs.effectiveRepairCostUsd() ?: return null
        val contingency = repairs * policy.repairContingencyPct / 100.0
        val totalProjectCost = asking + repairs + contingency
        val projectedSpread = arv - totalProjectCost
        val spreadPct = projectedSpread / arv * 100.0
        val mao = arv * (1.0 - policy.wholesaleDiscountPct / 100.0) - (repairs + contingency)
        val meetsFloor = projectedSpread >= policy.minimumWorkableSpreadUsd &&
            spreadPct >= policy.disqualifyingSpreadPctOfArv
        return LeadSpreadAnalysis(
            askingPriceUsd = asking,
            afterRepairValueUsd = arv,
            repairCostUsd = repairs,
            repairContingencyUsd = contingency,
            totalProjectCostUsd = totalProjectCost,
            projectedSpreadUsd = projectedSpread,
            spreadPctOfArv = spreadPct,
            maximumAllowableOfferUsd = mao,
            negotiationRoomUsd = mao - asking,
            meetsPolicyFloor = meetsFloor,
            floorDescription = "${formatUsd(policy.minimumWorkableSpreadUsd)} and " +
                "${formatPct(policy.disqualifyingSpreadPctOfArv)} of ARV"
        )
    }

    private fun spreadUnavailableReason(inputs: LeadQualificationInputs): String {
        val missing = buildList {
            if (inputs.askingPriceUsd == null) add("asking price")
            if (inputs.afterRepairValueUsd == null) add("after-repair value")
            if (inputs.effectiveRepairCostUsd() == null) add("repair estimate")
        }
        return if (missing.isEmpty()) "inputs are unusable" else "missing ${missing.joinToString(", ")}"
    }

    /** Linear interpolation between the disqualifying floor and full credit. */
    private fun scoreSpread(spread: LeadSpreadAnalysis, policy: LeadQualificationPolicy, maximumPoints: Int): Int {
        if (!spread.meetsPolicyFloor) return 0
        val floor = policy.disqualifyingSpreadPctOfArv
        val full = policy.minimumSpreadPctOfArv
        if (full <= floor) return maximumPoints
        val fraction = ((spread.spreadPctOfArv - floor) / (full - floor)).coerceIn(0.0, 1.0)
        return (fraction * maximumPoints).roundToInt().coerceIn(0, maximumPoints)
    }

    private fun scaleUrgency(urgencyPoints: Int, maximumPoints: Int): Int {
        require(urgencyPoints in 0..100) { "urgencyPoints must be within 0..100" }
        return ((urgencyPoints.toDouble() / 100.0) * maximumPoints).roundToInt().coerceIn(0, maximumPoints)
    }

    private fun formatUsd(amount: Double): String =
        if (amount.isFinite()) String.format(Locale.US, "\$%,.0f", amount) else "non-finite"

    private fun formatPct(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.1f%%", value) else "non-finite%"
}
