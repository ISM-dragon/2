package com.example.domain.crm

import kotlin.math.roundToInt

/**
 * Working priority of a lead.
 *
 * Priority is *derived, not hand-set*: an operator can override it, but the CRM always computes a
 * deterministic suggestion ([LeadPriorityEngine.derive]) from the facts it already holds, so two
 * reps looking at the same board see the same order. Keep this list short — a priority ladder with
 * seven levels is a spreadsheet, not a decision.
 */
enum class LeadPriority(val rank: Int) {
    LOW(0),
    NORMAL(1),
    HIGH(2),
    URGENT(3);

    val isElevated: Boolean get() = this == HIGH || this == URGENT

    fun atLeast(other: LeadPriority): Boolean = rank >= other.rank
}

/** Factors that contribute to the priority score. */
enum class LeadPriorityFactorId(val label: String) {
    QUALIFICATION("Qualification"),
    MOTIVATION("Motivation"),
    TIMELINE("Timeline"),
    ENGAGEMENT("Engagement"),
    CONDITION("Condition / spread"),
    FOLLOW_UP("Follow-up urgency");

    companion object {
        /** Weights of the *additive* score. FOLLOW_UP is a bonus applied on top. */
        val SCORED: List<LeadPriorityFactorId> = listOf(QUALIFICATION, MOTIVATION, TIMELINE, ENGAGEMENT, CONDITION)
    }
}

/** One factor's contribution to the priority score. */
data class LeadPriorityFactor(
    val id: LeadPriorityFactorId,
    val points: Int,
    val detail: String
)

/** The computed priority plus the exact reasons, so a board can explain its ordering. */
data class LeadPriorityEvaluation(
    val priority: LeadPriority,
    val score: Int,
    val factors: List<LeadPriorityFactor>,
    val reasons: List<String>
) {
    init {
        require(score in 0..100) { "Priority score must be within 0..100" }
    }

    fun factor(id: LeadPriorityFactorId): LeadPriorityFactor? = factors.firstOrNull { it.id == id }

    fun describe(): String = "priority ${priority.name} (score $score): " + reasons.joinToString("; ")
}

/** Validated configuration of the priority ladder. */
data class LeadPriorityPolicy(
    val version: String = DEFAULT_VERSION,
    val maximumScore: Int = 100,
    val weights: Map<LeadPriorityFactorId, Int> = DEFAULT_WEIGHTS,
    val highThreshold: Int = 50,
    val urgentThreshold: Int = 72,
    /** Added when the scheduled follow-up is due now. */
    val dueFollowUpBonus: Int = 8,
    /** Added when the follow-up is overdue — a slipping lead is the most expensive kind. */
    val overdueFollowUpBonus: Int = 18,
    /** Hard blocker: a lead the seller froze can never be urgent. */
    val frozenCeiling: LeadPriority = LeadPriority.LOW
) {
    init {
        require(version.isNotBlank()) { "Priority policy version must not be blank" }
        require(maximumScore in 10..100) { "Priority maximumScore must be within 10..100" }
        require(weights.keys == LeadPriorityFactorId.SCORED.toSet()) {
            "weights must define every scored priority factor exactly once (FOLLOW_UP is a bonus)"
        }
        require(weights.values.all { it > 0 }) { "Priority weights must be positive" }
        require(weights.values.sum() == maximumScore) {
            "Priority weights must sum to maximumScore ($maximumScore), got ${weights.values.sum()}"
        }
        require(highThreshold in 0..maximumScore && urgentThreshold in 0..maximumScore) {
            "Priority thresholds must be within 0..maximumScore"
        }
        require(highThreshold <= urgentThreshold) { "highThreshold must not exceed urgentThreshold" }
        require(dueFollowUpBonus in 0..50 && overdueFollowUpBonus in 0..50) { "Follow-up bonuses must be within 0..50" }
        require(dueFollowUpBonus <= overdueFollowUpBonus) { "An overdue follow-up must never score below a merely due one" }
    }

    fun weightOf(factor: LeadPriorityFactorId): Int = weights.getValue(factor)

    fun bandFor(score: Int): LeadPriority = when {
        score >= urgentThreshold -> LeadPriority.URGENT
        score >= highThreshold -> LeadPriority.HIGH
        score >= highThreshold / 2 -> LeadPriority.NORMAL
        else -> LeadPriority.LOW
    }

    companion object {
        const val DEFAULT_VERSION = "wholesale-lead-priority-v1"

        /** Qualification and engagement dominate: work what sellers respond to. */
        val DEFAULT_WEIGHTS: Map<LeadPriorityFactorId, Int> = mapOf(
            LeadPriorityFactorId.QUALIFICATION to 35,
            LeadPriorityFactorId.MOTIVATION to 20,
            LeadPriorityFactorId.TIMELINE to 15,
            LeadPriorityFactorId.ENGAGEMENT to 20,
            LeadPriorityFactorId.CONDITION to 10
        )

        val DEFAULT = LeadPriorityPolicy()
    }
}

/** Frozen input to the priority computation: nothing here reads a clock. */
data class LeadPriorityInputs(
    val pipelineStatus: LeadPipelineStatus,
    val qualificationState: LeadQualificationState = LeadQualificationState.NOT_ASSESSED,
    val qualificationScore: Int = 0,
    val maximumQualificationScore: Int = 100,
    val motivationIndex: Int = 0,
    val timeline: SellerTimeline = SellerTimeline.UNKNOWN,
    val conditionSeverity: RehabSeverity = RehabSeverity.UNKNOWN,
    val hasSellerResponse: Boolean = false,
    val hasSellerConversation: Boolean = false,
    val isContactFrozen: Boolean = false,
    val followUpState: FollowUpState = FollowUpState.UNSCHEDULED,
    /** Whole days the lead has been sitting in its current stage. */
    val daysInStage: Long = 0L
) {
    init {
        require(qualificationScore in 0..maximumQualificationScore) {
            "qualificationScore must be within 0..maximumQualificationScore"
        }
        require(maximumQualificationScore > 0) { "maximumQualificationScore must be positive" }
        require(motivationIndex in 0..100) { "motivationIndex must be within 0..100" }
        require(daysInStage >= 0L) { "daysInStage must not be negative" }
    }

    companion object {
        /** Builds the inputs from a lead and a moment in time (deterministic: the clock is passed in). */
        fun fromLead(
            lead: Lead,
            nowEpochMillis: Long,
            policy: LeadPriorityPolicy = LeadPriorityPolicy.DEFAULT,
            qualificationPolicy: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT,
            followUpPolicy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT
        ): LeadPriorityInputs {
            val assessment = lead.qualification
            val motivation = lead.motivationAggregate(nowEpochMillis, qualificationPolicy.motivationScoring)
            return LeadPriorityInputs(
                pipelineStatus = lead.pipelineStatus,
                qualificationState = lead.qualificationState,
                qualificationScore = assessment?.score ?: 0,
                maximumQualificationScore = assessment?.maximumScore ?: qualificationPolicy.maximumScore,
                motivationIndex = motivation.index,
                timeline = lead.timeline.timeline,
                conditionSeverity = lead.condition?.severity ?: RehabSeverity.UNKNOWN,
                hasSellerResponse = lead.hasSellerResponse,
                hasSellerConversation = lead.hasSellerConversation,
                isContactFrozen = lead.isContactFrozen,
                followUpState = lead.followUpState(nowEpochMillis, followUpPolicy),
                daysInStage = CrmTime.daysBetween(lead.stageEnteredAtEpochMillis, nowEpochMillis).coerceAtLeast(0L)
            )
        }
    }
}

/**
 * Deterministic priority ladder.
 *
 * The score is the sum of five weighted factors (each scaled to its weight) plus a follow-up urgency
 * bonus, clamped to the policy maximum. Two rules override the score entirely, because they are not
 * matters of degree:
 *
 *  - a terminal lead is always [LeadPriority.LOW] (there is nothing to work);
 *  - a frozen contact path caps the lead at [LeadPriorityPolicy.frozenCeiling] — a seller who asked not
 *    to be contacted (or is under a litigation/bankruptcy freeze) must never bubble to the top of an
 *    outbound call list, however attractive the deal looks.
 */
object LeadPriorityEngine {

    fun derive(
        inputs: LeadPriorityInputs,
        policy: LeadPriorityPolicy = LeadPriorityPolicy.DEFAULT
    ): LeadPriorityEvaluation {
        val factors = mutableListOf<LeadPriorityFactor>()
        val reasons = mutableListOf<String>()

        if (inputs.pipelineStatus.isTerminal) {
            return LeadPriorityEvaluation(
                priority = LeadPriority.LOW,
                score = 0,
                factors = factors,
                reasons = listOf("terminal status ${inputs.pipelineStatus.name}: nothing left to work")
            )
        }

        val qualificationMaximum = policy.weightOf(LeadPriorityFactorId.QUALIFICATION)
        val qualificationPoints = (
            (inputs.qualificationScore.toDouble() / inputs.maximumQualificationScore) * qualificationMaximum
            ).roundToInt().coerceIn(0, qualificationMaximum)
        factors += LeadPriorityFactor(
            LeadPriorityFactorId.QUALIFICATION,
            qualificationPoints,
            "qualification ${inputs.qualificationState.name} (${inputs.qualificationScore}/${inputs.maximumQualificationScore})"
        )
        if (inputs.qualificationState.isActionable) reasons += "seller is a qualified buyer of attention"
        if (inputs.qualificationState == LeadQualificationState.NOT_ASSESSED) reasons += "not yet qualified"

        val motivationMaximum = policy.weightOf(LeadPriorityFactorId.MOTIVATION)
        val motivationPoints = MotivationSignals.scaleToPoints(inputs.motivationIndex, motivationMaximum)
        factors += LeadPriorityFactor(
            LeadPriorityFactorId.MOTIVATION, motivationPoints, "motivation index ${inputs.motivationIndex}"
        )
        if (inputs.motivationIndex >= 50) reasons += "strong motivation signals"

        val timelineMaximum = policy.weightOf(LeadPriorityFactorId.TIMELINE)
        val timelinePoints = ((inputs.timeline.urgencyPoints.toDouble() / 100.0) * timelineMaximum)
            .roundToInt().coerceIn(0, timelineMaximum)
        factors += LeadPriorityFactor(
            LeadPriorityFactorId.TIMELINE, timelinePoints, "timeline ${inputs.timeline.name}"
        )
        if (inputs.timeline.isUrgent) reasons += "seller is on a deadline (${inputs.timeline.name})"

        val engagementMaximum = policy.weightOf(LeadPriorityFactorId.ENGAGEMENT)
        val engagementPoints = when {
            inputs.hasSellerConversation -> engagementMaximum
            inputs.hasSellerResponse -> (engagementMaximum * 2) / 3
            else -> 0
        }
        factors += LeadPriorityFactor(
            LeadPriorityFactorId.ENGAGEMENT,
            engagementPoints,
            "responded=${inputs.hasSellerResponse} conversation=${inputs.hasSellerConversation}"
        )
        if (inputs.hasSellerResponse) reasons += "seller has responded"

        val conditionMaximum = policy.weightOf(LeadPriorityFactorId.CONDITION)
        val conditionPoints = when (inputs.conditionSeverity) {
            RehabSeverity.UNKNOWN -> 0
            RehabSeverity.NONE -> conditionMaximum / 3
            RehabSeverity.MINOR -> (conditionMaximum * 2) / 3
            RehabSeverity.MODERATE -> conditionMaximum
            RehabSeverity.MAJOR -> (conditionMaximum * 3) / 4
            RehabSeverity.GUT -> conditionMaximum / 2
        }
        factors += LeadPriorityFactor(
            LeadPriorityFactorId.CONDITION, conditionPoints, "condition severity ${inputs.conditionSeverity.name}"
        )
        if (inputs.conditionSeverity.requiresInspection) reasons += "structural repairs: spread comes with execution risk"

        val followUpBonus = when (inputs.followUpState) {
            FollowUpState.OVERDUE -> policy.overdueFollowUpBonus
            FollowUpState.DUE -> policy.dueFollowUpBonus
            FollowUpState.UNSCHEDULED, FollowUpState.SCHEDULED, FollowUpState.COMPLETED -> 0
        }
        factors += LeadPriorityFactor(
            LeadPriorityFactorId.FOLLOW_UP,
            followUpBonus,
            "follow-up ${inputs.followUpState.name}" + if (inputs.daysInStage > 0) " (${inputs.daysInStage}d in stage)" else ""
        )
        if (inputs.followUpState == FollowUpState.OVERDUE) reasons += "follow-up overdue"
        if (inputs.followUpState == FollowUpState.UNSCHEDULED) reasons += "no follow-up scheduled"

        val rawScore = factors.sumOf { it.points }
        val score = rawScore.coerceIn(0, policy.maximumScore)
        val computed = policy.bandFor(score)
        val priority = if (inputs.isContactFrozen) {
            if (computed.rank > policy.frozenCeiling.rank) {
                reasons += "contact is frozen: priority capped at ${policy.frozenCeiling.name}"
            } else {
                reasons += "contact is frozen"
            }
            policy.frozenCeiling
        } else {
            computed
        }
        return LeadPriorityEvaluation(priority, score, factors, reasons)
    }
}
