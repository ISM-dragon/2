package com.example.domain.crm

/**
 * The next follow-up owed on a lead.
 *
 * Wholesale pipelines die from silence, not from bad math: a lead with no scheduled next touch is
 * indistinguishable from a lost lead. The model therefore keeps at most **one** pending follow-up per
 * lead ([Lead.nextFollowUp]) plus a computed [FollowUpState], and the store can answer "what is due
 * now?" without scanning the whole pipeline.
 *
 * Scheduling is interval-based (`now + N days`, see [LeadFollowUpPolicy]) rather than calendar-based:
 * business hours, weekends and timezones are display concerns, and a deterministic interval keeps the
 * plan reproducible in tests and in the audit trail.
 */
data class LeadFollowUp(
    /** When the next touch is owed. */
    val dueAtEpochMillis: Long,
    val channel: CommunicationChannel = CommunicationChannel.CALL,
    val note: String? = null,
    val scheduledAtEpochMillis: Long,
    val scheduledBy: String,
    /** Pipeline status the lead was in when this follow-up was scheduled. */
    val scheduledFromStatus: LeadPipelineStatus,
    /** Attempts already consumed against this follow-up. */
    val attempts: Int = 0,
    /** Set when the operator explicitly deferred it; the follow-up stays open. */
    val snoozedUntilEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val completedBy: String? = null,
    val completionSummary: String? = null
) {
    init {
        require(dueAtEpochMillis > 0L) { "Follow-up dueAtEpochMillis must be positive" }
        require(scheduledAtEpochMillis > 0L) { "Follow-up scheduledAtEpochMillis must be positive" }
        require(scheduledBy.isNotBlank()) { "Follow-up must record who scheduled it" }
        require(attempts >= 0) { "Follow-up attempts must not be negative" }
        require(note == null || note.length <= MAX_NOTE_LENGTH) { "Follow-up note must be at most $MAX_NOTE_LENGTH characters" }
        require(snoozedUntilEpochMillis == null || snoozedUntilEpochMillis > scheduledAtEpochMillis) {
            "A snooze must move the follow-up forward"
        }
        if (completedAtEpochMillis != null) {
            require(completedAtEpochMillis >= scheduledAtEpochMillis) { "A follow-up cannot be completed before it was scheduled" }
            require(!completedBy.isNullOrBlank()) { "Completing a follow-up requires who did it" }
        } else {
            require(completedBy == null && completionSummary == null) {
                "Completion metadata requires completedAtEpochMillis"
            }
        }
    }

    val isCompleted: Boolean get() = completedAtEpochMillis != null

    val isSnoozed: Boolean get() = snoozedUntilEpochMillis != null

    /** Effective due instant, taking the snooze into account. */
    val effectiveDueAtEpochMillis: Long get() = snoozedUntilEpochMillis ?: dueAtEpochMillis

    /** How late the follow-up is, floored at zero. */
    fun latenessMillis(nowEpochMillis: Long): Long = (nowEpochMillis - effectiveDueAtEpochMillis).coerceAtLeast(0L)

    fun state(leadStatus: LeadPipelineStatus, nowEpochMillis: Long, policy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT): FollowUpState =
        LeadFollowUpPlanner.stateOf(this, leadStatus, nowEpochMillis, policy)

    fun isDue(nowEpochMillis: Long, policy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT): Boolean =
        state(LeadPipelineStatus.NEW, nowEpochMillis, policy).isActionableNow

    fun withAttempt(atEpochMillis: Long): LeadFollowUp {
        require(atEpochMillis >= scheduledAtEpochMillis) { "Cannot register an attempt before the follow-up was scheduled" }
        require(!isCompleted) { "Cannot register an attempt on a completed follow-up" }
        return copy(attempts = attempts + 1)
    }

    fun snoozeUntil(untilEpochMillis: Long): LeadFollowUp {
        require(untilEpochMillis > effectiveDueAtEpochMillis) { "A snooze must move the follow-up into the future" }
        require(!isCompleted) { "Cannot snooze a completed follow-up" }
        return copy(snoozedUntilEpochMillis = untilEpochMillis)
    }

    fun complete(atEpochMillis: Long, completedBy: String, summary: String? = null): LeadFollowUp {
        require(!isCompleted) { "Follow-up is already completed" }
        require(atEpochMillis >= scheduledAtEpochMillis) { "Cannot complete a follow-up before it was scheduled" }
        return copy(
            completedAtEpochMillis = atEpochMillis,
            completedBy = completedBy,
            completionSummary = summary
        )
    }

    fun describe(): String = buildString {
        append(if (isCompleted) "completed" else "due")
        append('@').append(effectiveDueAtEpochMillis)
        append(' ').append(channel.name)
        append(" from ").append(scheduledFromStatus.name)
        if (attempts > 0) append(" attempts=").append(attempts)
        if (isSnoozed) append(" snoozed")
        note?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
    }

    companion object {
        const val MAX_NOTE_LENGTH = 300
    }
}

/** Where a follow-up stands relative to now. */
enum class FollowUpState {
    /** Nothing scheduled (terminal leads, or a fresh lead before the first touch). */
    UNSCHEDULED,
    /** Scheduled in the future, beyond the due window. */
    SCHEDULED,
    /** Inside the due window: work it now. */
    DUE,
    /** Past the grace window: the pipeline is leaking. */
    OVERDUE,
    /** Already worked. */
    COMPLETED;

    val isActionableNow: Boolean get() = this == DUE || this == OVERDUE

    val isBreaching: Boolean get() = this == OVERDUE || this == UNSCHEDULED

    val needsAttention: Boolean get() = this == DUE || this == OVERDUE
}

/**
 * Follow-up cadence policy.
 *
 * [intervalDaysByStatus] must define a cadence for *every open status* and must not define one for a
 * terminal status (a closed or lost lead owes nothing), which is enforced here so a missing entry
 * can never silently mean "never follow up".
 */
data class LeadFollowUpPolicy(
    val intervalDaysByStatus: Map<LeadPipelineStatus, Int> = DEFAULT_INTERVALS,
    /** A follow-up becomes actionable this many hours before it is due. */
    val dueWindowHours: Int = 24,
    /** Grace after the due instant before the follow-up counts as overdue. */
    val overdueGraceHours: Int = 24,
    val defaultChannel: CommunicationChannel = CommunicationChannel.CALL,
    /** A follow-up older than this is escalated as a policy breach rather than quietly forgotten. */
    val maxFollowUpAgeDays: Int = 30,
    /** Open leads are expected to always have a next touch scheduled. */
    val requireFollowUpForOpenLeads: Boolean = true
) {
    init {
        require(intervalDaysByStatus.keys.containsAll(LeadPipelineStatus.OPEN_STATUSES)) {
            "intervalDaysByStatus must define a follow-up cadence for every open status"
        }
        require(intervalDaysByStatus.keys.none { it.isTerminal }) {
            "intervalDaysByStatus must not define a cadence for terminal statuses"
        }
        require(intervalDaysByStatus.values.all { it in 1..120 }) { "Follow-up intervals must be within 1..120 days" }
        require(dueWindowHours in 0..168) { "dueWindowHours must be within 0..168" }
        require(overdueGraceHours in 0..168) { "overdueGraceHours must be within 0..168" }
        require(maxFollowUpAgeDays in 1..365) { "maxFollowUpAgeDays must be within 1..365" }
    }

    fun intervalDaysFor(status: LeadPipelineStatus): Int? = intervalDaysByStatus[status]

    companion object {
        /** Tighter cadence the closer the seller is to a decision; terminal statuses are absent. */
        val DEFAULT_INTERVALS: Map<LeadPipelineStatus, Int> = mapOf(
            LeadPipelineStatus.NEW to 3,
            LeadPipelineStatus.CONTACTED to 2,
            LeadPipelineStatus.RESPONDED to 2,
            LeadPipelineStatus.NEGOTIATING to 3,
            LeadPipelineStatus.OFFER_SENT to 2,
            LeadPipelineStatus.UNDER_CONTRACT to 1,
            LeadPipelineStatus.DUE_DILIGENCE to 1
        )
        val DEFAULT = LeadFollowUpPolicy()
    }
}

/** Deterministic follow-up planning: when the next touch is owed and whether it is late. */
object LeadFollowUpPlanner {

    /**
     * Next due instant for a lead entering [status] at [atEpochMillis].
     * Returns null for terminal statuses: a closed or lost lead owes no follow-up.
     */
    fun nextDueAtEpochMillis(
        status: LeadPipelineStatus,
        atEpochMillis: Long,
        policy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT
    ): Long? {
        val days = policy.intervalDaysFor(status) ?: return null
        return CrmTime.plusDays(atEpochMillis, days)
    }

    /** Builds the follow-up a lead owes after entering [status]. */
    fun schedule(
        status: LeadPipelineStatus,
        atEpochMillis: Long,
        scheduledBy: String,
        policy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT,
        channel: CommunicationChannel = policy.defaultChannel,
        note: String? = null
    ): LeadFollowUp? {
        val dueAt = nextDueAtEpochMillis(status, atEpochMillis, policy) ?: return null
        return LeadFollowUp(
            dueAtEpochMillis = dueAt,
            channel = channel,
            note = note,
            scheduledAtEpochMillis = atEpochMillis,
            scheduledBy = scheduledBy,
            scheduledFromStatus = status
        )
    }

    /**
     * State of a follow-up as of [nowEpochMillis].
     *
     *  - terminal lead status → [FollowUpState.UNSCHEDULED] (nothing is owed anymore);
     *  - no follow-up → [FollowUpState.UNSCHEDULED];
     *  - completed → [FollowUpState.COMPLETED] (a completed follow-up on an open lead is a *policy*
     *    breach, reported by [isBreachingPlan], not a scheduling state);
     *  - inside `[-dueWindow, +grace]` → [FollowUpState.DUE];
     *  - past the grace window → [FollowUpState.OVERDUE];
     *  - earlier than that → [FollowUpState.SCHEDULED].
     */
    fun stateOf(
        followUp: LeadFollowUp?,
        leadStatus: LeadPipelineStatus,
        nowEpochMillis: Long,
        policy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT
    ): FollowUpState {
        require(nowEpochMillis > 0L) { "nowEpochMillis must be positive" }
        if (leadStatus.isTerminal) return FollowUpState.UNSCHEDULED
        val effective = followUp ?: return FollowUpState.UNSCHEDULED
        if (effective.isCompleted) return FollowUpState.COMPLETED
        val dueAt = effective.effectiveDueAtEpochMillis
        val windowStart = dueAt - policy.dueWindowHours * CrmTime.MILLIS_PER_HOUR
        val overdueAt = dueAt + policy.overdueGraceHours * CrmTime.MILLIS_PER_HOUR
        return when {
            nowEpochMillis > overdueAt -> FollowUpState.OVERDUE
            nowEpochMillis >= windowStart -> FollowUpState.DUE
            else -> FollowUpState.SCHEDULED
        }
    }

    /**
     * True when an open lead is not following the cadence: no pending follow-up, a completed one, or
     * one that has been sitting overdue past [LeadFollowUpPolicy.maxFollowUpAgeDays].
     */
    fun isBreachingPlan(
        lead: Lead,
        nowEpochMillis: Long,
        policy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT
    ): Boolean {
        if (lead.pipelineStatus.isTerminal) return false
        val followUp = lead.nextFollowUp
        if (followUp == null) return policy.requireFollowUpForOpenLeads
        if (followUp.isCompleted) return true
        val overdueMillis = nowEpochMillis - followUp.effectiveDueAtEpochMillis
        return overdueMillis > policy.maxFollowUpAgeDays * CrmTime.MILLIS_PER_DAY
    }

    /** Human-readable breach description, or null when the plan is healthy. */
    fun breachReason(
        lead: Lead,
        nowEpochMillis: Long,
        policy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT
    ): String? {
        if (lead.pipelineStatus.isTerminal) return null
        val followUp = lead.nextFollowUp ?: return "no follow-up scheduled for ${lead.pipelineStatus.name}"
        if (followUp.isCompleted) return "follow-up completed without scheduling the next touch"
        val overdueDays = CrmTime.daysBetween(followUp.effectiveDueAtEpochMillis, nowEpochMillis)
        return if (overdueDays > policy.maxFollowUpAgeDays) {
            "follow-up overdue by $overdueDays days (policy limit ${policy.maxFollowUpAgeDays})"
        } else {
            null
        }
    }
}
