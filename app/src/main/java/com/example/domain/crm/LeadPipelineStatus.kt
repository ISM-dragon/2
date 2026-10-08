package com.example.domain.crm

/**
 * Canonical wholesale pipeline for a lead.
 *
 * The statuses describe *where the deal is with the seller*, not what the app is doing. A lead moves
 * through them only through [LeadPipelineStateMachine], which owns the transition table, the
 * evidence gates and the audit trail; nothing else may assign [Lead.pipelineStatus] directly.
 *
 * The ordering of the constants is the reading order used by pipeline boards (roughly the order a
 * seller conversation progresses). It is deliberately *not* used for transition legality: the
 * transition table is explicit so that, for example, `OFFER_SENT -> NEGOTIATING` (a counter offer)
 * is a legal move even though it walks backwards.
 *
 * Terminal statuses: [CLOSED] (won) and [LOST] (dead or disqualified). A [LOST] lead may be
 * re-engaged by explicit operator intent (`RE_ENGAGED`); a [CLOSED] lead never moves again.
 */
enum class LeadPipelineStatus {

    /** Captured, not yet worked. Nobody has spoken to the seller. */
    NEW,

    /** Outbound contact has been attempted (mail, call, SMS, door knock). No seller response yet. */
    CONTACTED,

    /** The seller answered / replied. This is the first proof of a working contact path. */
    RESPONDED,

    /** Price, terms, timing or condition are actively being discussed with a qualified seller. */
    NEGOTIATING,

    /** A written offer was submitted to the seller (see [Lead.offerReferences]). */
    OFFER_SENT,

    /** The seller accepted; a purchase contract exists (see [Lead.contract]). */
    UNDER_CONTRACT,

    /** The contract's inspection / due-diligence window is running. Exit/assign decision pending. */
    DUE_DILIGENCE,

    /** The deal closed. Terminal. */
    CLOSED,

    /** Dead: disqualified, seller declined, or the deal fell apart. Terminal, but re-engageable. */
    LOST;

    /** No further automatic work is possible. */
    val isTerminal: Boolean
        get() = this == CLOSED || this == LOST

    /** The lead still needs attention (follow-up, negotiation, diligence). */
    val isOpen: Boolean
        get() = !isTerminal

    val isWon: Boolean get() = this == CLOSED

    val isLost: Boolean get() = this == LOST

    /** A real conversation with the seller has happened at some point. */
    val isEngaged: Boolean
        get() = this == CONTACTED || this == RESPONDED || this == NEGOTIATING || this == OFFER_SENT ||
            this == UNDER_CONTRACT || this == DUE_DILIGENCE || this == CLOSED

    /** A written offer is (or was) on the table. */
    val hasWrittenOffer: Boolean
        get() = this == OFFER_SENT || this == UNDER_CONTRACT || this == DUE_DILIGENCE || this == CLOSED

    /** Offer accepted: the deal is contractually committed, so changes need an audited reason. */
    val isCommitted: Boolean
        get() = this == UNDER_CONTRACT || this == DUE_DILIGENCE || this == CLOSED

    /**
     * Statuses that require a working contact path to be entered at all. Used by the transition
     * gate, not by the table: a lead whose only channel is do-not-contact must never be contacted.
     */
    val requiresContactPath: Boolean
        get() = this == CONTACTED || this == RESPONDED || this == NEGOTIATING || this.hasWrittenOffer

    /** Statuses where a seller has to be qualified before the app will move the lead further. */
    val requiresQualification: Boolean
        get() = this == NEGOTIATING || this == OFFER_SENT || this.isCommitted

    /** Statuses where a purchase contract reference must exist on the lead. */
    val requiresContract: Boolean
        get() = this.isCommitted || this == DUE_DILIGENCE

    /**
     * Statuses where a do-not-contact request must never have been recorded. Kept as an explicit
     * set so compliance rules can be reviewed at a glance.
     */
    val isForbiddenAfterDoNotContact: Boolean
        get() = this == CONTACTED || this == RESPONDED || this == NEGOTIATING || this.hasWrittenOffer

    /**
     * Position on the forward path, used for reporting ("how far did this lead get?") and for
     * classifying a transition as forward or backward. [LOST] has no position: it is an exit, not a
     * stage, and must never count as progress.
     */
    val forwardRank: Int
        get() = when (this) {
            NEW -> 0
            CONTACTED -> 1
            RESPONDED -> 2
            NEGOTIATING -> 3
            OFFER_SENT -> 4
            UNDER_CONTRACT -> 5
            DUE_DILIGENCE -> 6
            CLOSED -> 7
            LOST -> -1
        }

    /** Targets the pipeline allows from this status, same-state included (idempotent no-op). */
    fun allowedNext(): Set<LeadPipelineStatus> = LeadPipelineStateMachine.allowedTargets(this)

    fun canTransitionTo(next: LeadPipelineStatus): Boolean =
        LeadPipelineStateMachine.canTransition(this, next)

    companion object {
        /** Board/UI reading order. Identical to the declaration order, made explicit on purpose. */
        val PIPELINE_ORDER: List<LeadPipelineStatus> = listOf(
            NEW, CONTACTED, RESPONDED, NEGOTIATING, OFFER_SENT, UNDER_CONTRACT, DUE_DILIGENCE, CLOSED, LOST
        )

        /** Statuses a lead can be in while it still owes the operator work. */
        val OPEN_STATUSES: Set<LeadPipelineStatus> = PIPELINE_ORDER.filter { it.isOpen }.toSet()

        val TERMINAL_STATUSES: Set<LeadPipelineStatus> = PIPELINE_ORDER.filter { it.isTerminal }.toSet()
    }
}
