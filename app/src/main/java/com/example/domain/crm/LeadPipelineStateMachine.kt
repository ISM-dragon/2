package com.example.domain.crm

/**
 * Why a lead moved. Every pipeline transition must name one of these, and each edge accepts only a
 * documented subset — an audit trail that says "moved to OFFER_SENT because SELLER_NOT_INTERESTED"
 * is worse than no audit trail at all.
 */
enum class LeadTransitionReason {
    /** Lead created at [LeadPipelineStatus.NEW]. */
    LEAD_CREATED,
    /** Outbound contact attempted (call, SMS, mail, door knock). */
    OUTREACH_LOGGED,
    /** The seller answered or replied. */
    SELLER_RESPONDED,
    /** Price/terms discussion started with a qualified seller. */
    NEGOTIATION_OPENED,
    /** A written offer was submitted. */
    OFFER_SUBMITTED,
    /** The seller countered a submitted offer. */
    OFFER_COUNTERED,
    /** The seller accepted the written offer (a contract follows). */
    OFFER_ACCEPTED,
    /** Contract amended after inspection/renegotiation. */
    CONTRACT_AMENDED,
    /** The inspection/due-diligence window started. */
    DUE_DILIGENCE_STARTED,
    /** Deal closed. */
    DEAL_CLOSED,
    /** Deal fell apart after commitment (financing, title, inspection, buyer walked). */
    DEAL_LOST,
    /** Lead did not meet the buy box. */
    LEAD_DISQUALIFIED,
    /** The seller said no. */
    SELLER_NOT_INTERESTED,
    /** A lost lead re-entered the pipeline by explicit operator intent. */
    RE_ENGAGED;

    val isNegative: Boolean
        get() = this == DEAL_LOST || this == LEAD_DISQUALIFIED || this == SELLER_NOT_INTERESTED
}

/** A deterministic reason a pipeline move is refused even though the topology allows it. */
data class LeadTransitionBlocker(val code: String, val message: String) {
    init {
        require(code.isNotBlank()) { "Transition blocker code must not be blank" }
        require(message.isNotBlank()) { "Transition blocker message must not be blank" }
    }
}

/**
 * Evidence requirements for pipeline moves.
 *
 * The topology ("OFFER_SENT can follow NEGOTIATING") is not the same thing as the *business* rule
 * ("do not submit an offer for a lead nobody has qualified"). This policy holds the second part, and
 * every switch is explicit so a deployment can loosen a rule knowingly rather than by accident.
 */
data class LeadTransitionPolicy(
    val version: String = DEFAULT_VERSION,
    /** NEGOTIATING and beyond require a qualification assessment at or above WARM. */
    val requireActionableQualificationForNegotiation: Boolean = true,
    /** OFFER_SENT and beyond require a QUALIFIED assessment. */
    val requireQualifiedForOffer: Boolean = true,
    /** UNDER_CONTRACT and beyond require QUALIFIED (i.e. a fully evidenced file before commitment). */
    val requireQualifiedForContract: Boolean = true,
    /** A qualification assessment older than this may not justify offer-level moves. */
    val qualificationFreshnessDays: Int = 30,
    /** An offer cannot be submitted without a linked subject property. */
    val requirePropertyLinkForOffer: Boolean = true,
    /** An offer cannot be submitted before the seller has actually engaged. */
    val requireSellerConversationForOffer: Boolean = true,
    /** NEGOTIATING requires at least one motivation signal: never negotiate without a reason to sell. */
    val requireMotivationForNegotiation: Boolean = true,
    /** CONTACTED/RESPONDED require a permitted contact path (no DNC, no legal freeze). */
    val blockContactWhenRestricted: Boolean = true,
    /** Re-engaging a disqualified (LOST) lead is allowed, but never while it is still disqualified. */
    val blockReengageWhileDisqualified: Boolean = true
) {
    init {
        require(version.isNotBlank()) { "Transition policy version must not be blank" }
        require(qualificationFreshnessDays in 1..365) { "qualificationFreshnessDays must be within 1..365" }
    }

    companion object {
        const val DEFAULT_VERSION = "wholesale-lead-transition-v1"
        val DEFAULT = LeadTransitionPolicy()
    }
}

/** Outcome of a pipeline move request. */
sealed interface LeadTransitionResult {

    /** The move happened; the audited transition is on the lead. */
    data class Applied(val lead: Lead, val transition: LeadTransition) : LeadTransitionResult

    /** The lead is already in the requested status: nothing to audit, assessment/evidence untouched. */
    data class NoOp(val lead: Lead, val status: LeadPipelineStatus) : LeadTransitionResult

    /** The topology or reason set forbids the move. This is a programming error, not an operator one. */
    data class Illegal(
        val from: LeadPipelineStatus,
        val to: LeadPipelineStatus,
        val reason: LeadTransitionReason,
        val message: String
    ) : LeadTransitionResult

    /** The move is topologically legal but the evidence is not there yet. */
    data class Blocked(
        val from: LeadPipelineStatus,
        val to: LeadPipelineStatus,
        val reason: LeadTransitionReason,
        val blockers: List<LeadTransitionBlocker>
    ) : LeadTransitionResult
}

/** Convenience accessor: the resulting lead, or a hard failure when the move was refused. */
val LeadTransitionResult.lead: Lead
    get() = when (this) {
        is LeadTransitionResult.Applied -> lead
        is LeadTransitionResult.NoOp -> lead
        is LeadTransitionResult.Illegal ->
            throw IllegalStateException("Illegal pipeline transition $from → $to ($reason): $message")
        is LeadTransitionResult.Blocked ->
            throw IllegalStateException(
                "Blocked pipeline transition $from → $to ($reason): " + blockers.joinToString(", ") { it.code }
            )
    }

/** The evidence gate: answers "is the lead actually ready for this status?". */
object LeadTransitionGate {

    /** Blockers preventing [target], in a stable order. Empty means the move is allowed. */
    fun evaluate(
        lead: Lead,
        target: LeadPipelineStatus,
        policy: LeadTransitionPolicy = LeadTransitionPolicy.DEFAULT,
        qualificationPolicy: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT,
        atEpochMillis: Long = lead.audit.updatedAtEpochMillis
    ): List<LeadTransitionBlocker> {
        val blockers = mutableListOf<LeadTransitionBlocker>()

        // Contact path: needed for every status in which the app contacts the seller.
        if (policy.blockContactWhenRestricted && target.requiresContactPath) {
            if (lead.isContactFrozen) {
                blockers += LeadTransitionBlocker(
                    CrmBlockerCodes.CONTACT_RESTRICTED,
                    "the seller is on do-not-contact / legally frozen"
                )
            } else if (lead.sellers.isNotEmpty() && !lead.isSellerReachable) {
                blockers += LeadTransitionBlocker(
                    CrmBlockerCodes.NO_USABLE_CONTACT_CHANNEL,
                    "no usable contact channel (every recorded channel is opted out or missing)"
                )
            }
        }

        if (target == LeadPipelineStatus.RESPONDED && !lead.hasSellerResponse) {
            blockers += LeadTransitionBlocker(
                CrmBlockerCodes.NO_SELLER_RESPONSE,
                "no inbound response from the seller has been logged"
            )
        }

        // Qualification: the ladder of evidence the later stages stand on.
        if (target.requiresQualification) {
            val assessment = lead.qualification
            val freshnessLimit = minOf(policy.qualificationFreshnessDays, qualificationPolicy.stalenessWindowDays)
            when {
                assessment == null -> blockers += LeadTransitionBlocker(
                    CrmBlockerCodes.QUALIFICATION_NOT_ASSESSED,
                    "the lead has never been qualified"
                )
                assessment.isDisqualified -> blockers += LeadTransitionBlocker(
                    CrmBlockerCodes.QUALIFICATION_DISQUALIFIED,
                    "qualification is DISQUALIFIED: " + assessment.disqualifiers.joinToString("; ") { it.message }
                )
                assessment.ageDays(atEpochMillis) > freshnessLimit -> blockers += LeadTransitionBlocker(
                    CrmBlockerCodes.QUALIFICATION_STALE,
                    "the last qualification is ${assessment.ageDays(atEpochMillis)} days old " +
                        "(the tighter of the transition window ${policy.qualificationFreshnessDays} and the " +
                        "qualification window ${qualificationPolicy.stalenessWindowDays} applies)"
                )
                policy.requireActionableQualificationForNegotiation && !assessment.state.permitsNegotiation ->
                    blockers += LeadTransitionBlocker(
                        CrmBlockerCodes.QUALIFICATION_INSUFFICIENT,
                        "qualification ${assessment.state.name} is below WARM: keep working the seller"
                    )
                target.hasWrittenOffer && policy.requireQualifiedForOffer && !assessment.state.permitsOffer ->
                    blockers += LeadTransitionBlocker(
                        CrmBlockerCodes.QUALIFICATION_INSUFFICIENT,
                        "qualification ${assessment.state.name} is below QUALIFIED: an offer needs a fully evidenced file"
                    )
                target.isCommitted && policy.requireQualifiedForContract && !assessment.state.permitsOffer ->
                    blockers += LeadTransitionBlocker(
                        CrmBlockerCodes.QUALIFICATION_INSUFFICIENT,
                        "qualification ${assessment.state.name} is below QUALIFIED: do not commit without one"
                    )
            }
        }

        if (policy.requireMotivationForNegotiation && target == LeadPipelineStatus.NEGOTIATING &&
            lead.motivationSignals.isEmpty()
        ) {
            blockers += LeadTransitionBlocker(
                CrmBlockerCodes.MOTIVATION_NOT_EVIDENCED,
                "no motivation signal recorded: never negotiate without knowing why the seller would sell"
            )
        }

        // Written evidence.
        if (target.hasWrittenOffer && lead.offerReferences.isEmpty()) {
            blockers += LeadTransitionBlocker(
                CrmBlockerCodes.MISSING_OFFER_REFERENCE,
                "no offer from the offer pipeline is referenced on this lead"
            )
        }
        if (policy.requirePropertyLinkForOffer && target.hasWrittenOffer && lead.subjectPropertyLink == null) {
            blockers += LeadTransitionBlocker(
                CrmBlockerCodes.MISSING_PROPERTY_LINK,
                "no subject property is linked: an offer must name a property"
            )
        }
        if (policy.requireSellerConversationForOffer && target.hasWrittenOffer && !lead.hasSellerConversation) {
            blockers += LeadTransitionBlocker(
                CrmBlockerCodes.NO_SELLER_CONVERSATION,
                "no conversation with the seller is logged: do not submit an offer into the void"
            )
        }
        if (target.requiresContract && lead.contract == null) {
            blockers += LeadTransitionBlocker(
                CrmBlockerCodes.MISSING_CONTRACT,
                "no purchase contract is referenced on this lead"
            )
        }
        if (target == LeadPipelineStatus.DUE_DILIGENCE && lead.contract?.hasDueDiligenceWindow != true) {
            blockers += LeadTransitionBlocker(
                CrmBlockerCodes.MISSING_DUE_DILIGENCE_WINDOW,
                "the referenced contract has no inspection/due-diligence period"
            )
        }
        if (policy.blockReengageWhileDisqualified && lead.pipelineStatus == LeadPipelineStatus.LOST &&
            lead.qualificationState == LeadQualificationState.DISQUALIFIED
        ) {
            // Re-engagement must start from evidence, not from a status change: re-assess the lead
            // (new price, new motivation, seller called back) and the disqualifier disappears on its own.
            blockers += LeadTransitionBlocker(
                CrmBlockerCodes.QUALIFICATION_DISQUALIFIED,
                "the lead is still disqualified: re-assess it before re-engaging"
            )
        }

        return blockers.distinctBy { it.code to it.message }
    }
}

/**
 * The wholesale pipeline state machine.
 *
 * The transition table below is the single source of truth for "what may follow what", and every move
 * is applied through [transition], which:
 *
 *  1. refuses illegal topology (and nonsensical reasons) with a typed result instead of corrupting the
 *     pipeline;
 *  2. runs the evidence gate ([LeadTransitionGate]) so a lead cannot skip the work that justifies a
 *     status;
 *  3. appends an immutable [LeadTransition] carrying the qualification snapshot, the actor and the
 *     revision;
 *  4. clears the pending follow-up when the lead becomes terminal.
 *
 * Two edges are worth calling out because they are *by design*:
 *
 *  - `OFFER_SENT → NEGOTIATING` (a counter-offer) walks backwards on purpose: the seller rejected the
 *    written offer without ending the deal, which is a normal, expected path;
 *  - `DUE_DILIGENCE → UNDER_CONTRACT` covers an amended contract after inspection renegotiation.
 */
object LeadPipelineStateMachine {

    private val ALLOWED: Map<LeadPipelineStatus, Set<LeadPipelineStatus>> = mapOf(
        LeadPipelineStatus.NEW to setOf(
            LeadPipelineStatus.CONTACTED,
            LeadPipelineStatus.LOST
        ),
        LeadPipelineStatus.CONTACTED to setOf(
            LeadPipelineStatus.RESPONDED,
            // A seller can answer on the first call and negotiate immediately.
            LeadPipelineStatus.NEGOTIATING,
            LeadPipelineStatus.LOST
        ),
        LeadPipelineStatus.RESPONDED to setOf(
            LeadPipelineStatus.NEGOTIATING,
            LeadPipelineStatus.LOST
        ),
        LeadPipelineStatus.NEGOTIATING to setOf(
            LeadPipelineStatus.OFFER_SENT,
            LeadPipelineStatus.LOST
        ),
        LeadPipelineStatus.OFFER_SENT to setOf(
            LeadPipelineStatus.UNDER_CONTRACT,
            // Counter-offer: back to negotiation, the deal is alive.
            LeadPipelineStatus.NEGOTIATING,
            LeadPipelineStatus.LOST
        ),
        LeadPipelineStatus.UNDER_CONTRACT to setOf(
            LeadPipelineStatus.DUE_DILIGENCE,
            LeadPipelineStatus.CLOSED,
            LeadPipelineStatus.LOST
        ),
        LeadPipelineStatus.DUE_DILIGENCE to setOf(
            LeadPipelineStatus.CLOSED,
            // Amended contract after inspection renegotiation.
            LeadPipelineStatus.UNDER_CONTRACT,
            LeadPipelineStatus.LOST
        ),
        LeadPipelineStatus.CLOSED to emptySet(),
        LeadPipelineStatus.LOST to setOf(
            // Re-engagement: a "no" in March is a "yes" in September often enough to keep the path.
            LeadPipelineStatus.NEW,
            LeadPipelineStatus.CONTACTED
        )
    )

    private val ALLOWED_REASONS: Map<Pair<LeadPipelineStatus, LeadPipelineStatus>, Set<LeadTransitionReason>> = mapOf(
        (LeadPipelineStatus.NEW to LeadPipelineStatus.CONTACTED) to setOf(LeadTransitionReason.OUTREACH_LOGGED),
        (LeadPipelineStatus.NEW to LeadPipelineStatus.LOST) to setOf(
            LeadTransitionReason.LEAD_DISQUALIFIED,
            LeadTransitionReason.SELLER_NOT_INTERESTED
        ),
        (LeadPipelineStatus.CONTACTED to LeadPipelineStatus.RESPONDED) to setOf(LeadTransitionReason.SELLER_RESPONDED),
        (LeadPipelineStatus.CONTACTED to LeadPipelineStatus.NEGOTIATING) to setOf(LeadTransitionReason.NEGOTIATION_OPENED),
        (LeadPipelineStatus.CONTACTED to LeadPipelineStatus.LOST) to setOf(
            LeadTransitionReason.SELLER_NOT_INTERESTED,
            LeadTransitionReason.LEAD_DISQUALIFIED
        ),
        (LeadPipelineStatus.RESPONDED to LeadPipelineStatus.NEGOTIATING) to setOf(LeadTransitionReason.NEGOTIATION_OPENED),
        (LeadPipelineStatus.RESPONDED to LeadPipelineStatus.LOST) to setOf(
            LeadTransitionReason.SELLER_NOT_INTERESTED,
            LeadTransitionReason.LEAD_DISQUALIFIED
        ),
        (LeadPipelineStatus.NEGOTIATING to LeadPipelineStatus.OFFER_SENT) to setOf(LeadTransitionReason.OFFER_SUBMITTED),
        (LeadPipelineStatus.NEGOTIATING to LeadPipelineStatus.LOST) to setOf(
            LeadTransitionReason.SELLER_NOT_INTERESTED,
            LeadTransitionReason.DEAL_LOST,
            LeadTransitionReason.LEAD_DISQUALIFIED
        ),
        (LeadPipelineStatus.OFFER_SENT to LeadPipelineStatus.UNDER_CONTRACT) to setOf(LeadTransitionReason.OFFER_ACCEPTED),
        (LeadPipelineStatus.OFFER_SENT to LeadPipelineStatus.NEGOTIATING) to setOf(LeadTransitionReason.OFFER_COUNTERED),
        (LeadPipelineStatus.OFFER_SENT to LeadPipelineStatus.LOST) to setOf(
            LeadTransitionReason.DEAL_LOST,
            LeadTransitionReason.SELLER_NOT_INTERESTED
        ),
        (LeadPipelineStatus.UNDER_CONTRACT to LeadPipelineStatus.DUE_DILIGENCE) to setOf(LeadTransitionReason.DUE_DILIGENCE_STARTED),
        (LeadPipelineStatus.UNDER_CONTRACT to LeadPipelineStatus.CLOSED) to setOf(LeadTransitionReason.DEAL_CLOSED),
        (LeadPipelineStatus.UNDER_CONTRACT to LeadPipelineStatus.LOST) to setOf(LeadTransitionReason.DEAL_LOST),
        (LeadPipelineStatus.DUE_DILIGENCE to LeadPipelineStatus.CLOSED) to setOf(LeadTransitionReason.DEAL_CLOSED),
        (LeadPipelineStatus.DUE_DILIGENCE to LeadPipelineStatus.UNDER_CONTRACT) to setOf(LeadTransitionReason.CONTRACT_AMENDED),
        (LeadPipelineStatus.DUE_DILIGENCE to LeadPipelineStatus.LOST) to setOf(LeadTransitionReason.DEAL_LOST),
        (LeadPipelineStatus.LOST to LeadPipelineStatus.NEW) to setOf(LeadTransitionReason.RE_ENGAGED),
        (LeadPipelineStatus.LOST to LeadPipelineStatus.CONTACTED) to setOf(LeadTransitionReason.RE_ENGAGED)
    )

    fun allowedTargets(from: LeadPipelineStatus): Set<LeadPipelineStatus> = ALLOWED[from] ?: emptySet()

    /** Same-state requests are idempotent no-ops, never illegal (mobile workflows retry). */
    fun canTransition(from: LeadPipelineStatus, to: LeadPipelineStatus): Boolean =
        from == to || allowedTargets(from).contains(to)

    fun allowedReasons(from: LeadPipelineStatus, to: LeadPipelineStatus): Set<LeadTransitionReason> =
        if (from == to) emptySet() else ALLOWED_REASONS[Pair(from, to)].orEmpty()

    /**
     * Applies a pipeline move, or explains why it was refused.
     *
     * @param atEpochMillis the moment of the move. It is also the reference instant for qualification
     *   freshness, so the result depends only on the arguments (no hidden clock).
     */
    fun transition(
        lead: Lead,
        target: LeadPipelineStatus,
        reason: LeadTransitionReason,
        atEpochMillis: Long,
        actor: String,
        policy: LeadTransitionPolicy = LeadTransitionPolicy.DEFAULT,
        qualificationPolicy: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT,
        correlationId: String? = null,
        detail: String? = null
    ): LeadTransitionResult {
        require(actor.isNotBlank()) { "A pipeline transition requires an actor" }
        val from = lead.pipelineStatus
        if (from == target) return LeadTransitionResult.NoOp(lead, from)
        if (!canTransition(from, target)) {
            return LeadTransitionResult.Illegal(
                from, target, reason, "transition not allowed by the pipeline state machine"
            )
        }
        if (reason !in allowedReasons(from, target)) {
            return LeadTransitionResult.Illegal(
                from, target, reason, "reason ${reason.name} does not justify $from → $target"
            )
        }
        val blockers = LeadTransitionGate.evaluate(lead, target, policy, qualificationPolicy, atEpochMillis)
        if (blockers.isNotEmpty()) {
            return LeadTransitionResult.Blocked(from, target, reason, blockers)
        }

        val transition = LeadTransition(
            from = from,
            to = target,
            reason = reason,
            atEpochMillis = atEpochMillis,
            actor = actor,
            revision = lead.audit.revision + 1L,
            qualificationState = lead.qualification?.state,
            qualificationScore = lead.qualification?.score,
            correlationId = correlationId,
            detail = detail
        )
        val updated = lead.withPipelineTransition(target, transition, actor, atEpochMillis)
        return LeadTransitionResult.Applied(updated, transition)
    }

    /** Convenience for call sites where a refused move can only be a programming error. */
    fun transitionOrThrow(
        lead: Lead,
        target: LeadPipelineStatus,
        reason: LeadTransitionReason,
        atEpochMillis: Long,
        actor: String,
        policy: LeadTransitionPolicy = LeadTransitionPolicy.DEFAULT,
        qualificationPolicy: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT,
        correlationId: String? = null,
        detail: String? = null
    ): Lead = transition(lead, target, reason, atEpochMillis, actor, policy, qualificationPolicy, correlationId, detail).lead
}
