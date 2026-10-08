package com.example.domain.crm

/**
 * A wholesale lead: one seller (or seller group) and the property (or properties) they may sell.
 *
 * ## Why this shape
 *
 * `Lead` is the aggregate root of the CRM. It carries the facts (source, sellers, property links,
 * condition, timeline, motivation), the operator layer (tags, notes, priority, next follow-up), the
 * evidence layer (communications, offers, contract) and the audit layer (transitions,
 * qualification history, revision counters) as one immutable value. A write is therefore a single
 * `copy(...)` of a consistent snapshot, which is exactly what a Room transaction (`leads` + child
 * tables) or a snapshot store needs — and what makes the lifecycle reproducible in tests.
 *
 * ## Rules the aggregate enforces
 *
 *  - every child collection is keyed by a unique id, so a duplicated note/communication/seller cannot
 *    exist even temporarily;
 *  - tags are canonical ([LeadTags]) and child time-stamps are validated against the record;
 *  - `pipelineStatus` and `qualification` may only be changed by the state machines
 *    ([LeadPipelineStateMachine], [LeadQualificationStateMachine]) — the `with*` helpers below exist for
 *    the *evidence* that justifies such a move;
 *  - every mutation refreshes [CrmAuditMetadata] through [audit], which refuses to move time backwards.
 *
 * ## Integration notes (this branch)
 *
 * The aggregate is deliberately plain Kotlin: no Room annotations, no Android imports, no repository
 * calls. `propertyId` / `offerId` / `documentRef` are soft references into the existing data layer
 * (the same convention `OfferEntity.propertyId` already uses), so the CRM can be persisted and wired
 * up later without a schema rewrite and without breaking when a listing is merged or pruned.
 */
data class Lead(
    val id: String,
    val displayName: String,
    val pipelineStatus: LeadPipelineStatus = LeadPipelineStatus.NEW,
    val priority: LeadPriority = LeadPriority.NORMAL,
    val source: LeadSourceAttribution,
    /** Owners/decision makers, primary first. A lead without a seller is allowed while it is being qualified. */
    val sellers: List<Seller> = emptyList(),
    val propertyLinks: List<LeadPropertyLink> = emptyList(),
    val motivationSignals: List<MotivationSignal> = emptyList(),
    val condition: PropertyConditionAssessment? = null,
    val timeline: SellerTimelineFact,
    val tags: Set<String> = emptySet(),
    val notes: List<LeadNote> = emptyList(),
    val communications: List<LeadCommunication> = emptyList(),
    /** Offers submitted through the existing offer pipeline (references only). */
    val offerReferences: List<LeadOfferReference> = emptyList(),
    val contract: ContractReference? = null,
    val nextFollowUp: LeadFollowUp? = null,
    val qualification: LeadQualificationAssessment? = null,
    val qualificationHistory: List<LeadQualificationTransition> = emptyList(),
    /** Append-only pipeline audit trail. */
    val transitions: List<LeadTransition> = emptyList(),
    /** Operator the lead is assigned to, when the pipeline is worked by more than one person. */
    val assignedTo: String? = null,
    /** Correlation id of the automation cycle / import job that last wrote this lead. */
    val correlationId: String? = null,
    val audit: CrmAuditMetadata
) {
    init {
        require(id.isNotBlank()) { "Lead id must not be blank" }
        require(displayName.isNotBlank()) { "Lead display name must not be blank" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Lead display name must be at most $MAX_DISPLAY_NAME_LENGTH characters"
        }
        require(sellers.map { it.id }.toSet().size == sellers.size) { "Lead sellers must have unique ids" }
        require(sellers.count { it.isPrimaryContact } <= 1) { "At most one seller can be the primary contact" }
        require(propertyLinks.map { it.propertyId }.toSet().size == propertyLinks.size) {
            "Lead property links must be unique per property"
        }
        require(propertyLinks.count { it.role == PropertyLinkRole.SUBJECT } <= 1) {
            "A lead can have at most one subject property"
        }
        require(motivationSignals.map { it.id }.toSet().size == motivationSignals.size) {
            "Motivation signals must have unique ids"
        }
        require(notes.map { it.id }.toSet().size == notes.size) { "Notes must have unique ids" }
        require(communications.map { it.id }.toSet().size == communications.size) {
            "Communications must have unique ids"
        }
        require(offerReferences.map { it.offerId }.toSet().size == offerReferences.size) {
            "Offer references must be unique per offer"
        }
        require(notes.all { it.leadId == id }) { "Every note must belong to this lead" }
        require(communications.all { it.leadId == id }) { "Every communication must belong to this lead" }
        require(tags.all { LeadTags.isCanonical(it) }) {
            "Lead tags must be normalized with LeadTags.normalizeAll before they are stored"
        }
        require(nextFollowUp == null || !pipelineStatus.isTerminal) {
            "A terminal lead must not carry a pending follow-up"
        }
        require(qualification == null || qualification.evaluatedAtEpochMillis <= audit.updatedAtEpochMillis) {
            "A lead cannot hold an assessment from the future"
        }
        require(transitions.all { it.atEpochMillis <= audit.updatedAtEpochMillis }) {
            "A transition cannot be dated after the lead was last updated"
        }
        require(qualificationHistory.all { it.atEpochMillis <= audit.updatedAtEpochMillis }) {
            "A qualification transition cannot be dated after the lead was last updated"
        }
        require(assignedTo == null || assignedTo.isNotBlank()) { "assignedTo must not be blank when provided" }
        // A contract may legitimately exist *before* the status catches up: the seller signs, the
        // operator records the document, and only then does the lead move to UNDER_CONTRACT (and that
        // move is gated). `LeadValidator` reports the mismatch instead of forbidding the record.
    }

    // ── Derived facts an operator or a gate needs ───────────────────────────────────────────────

    /** The property the deal is about. */
    val subjectPropertyLink: LeadPropertyLink?
        get() = propertyLinks.firstOrNull { it.role == PropertyLinkRole.SUBJECT } ?: propertyLinks.firstOrNull()

    val subjectPropertyId: String? get() = subjectPropertyLink?.propertyId

    val primarySeller: Seller? get() = sellers.firstOrNull { it.isPrimaryContact } ?: sellers.firstOrNull()

    val qualificationState: LeadQualificationState
        get() = qualification?.state ?: LeadQualificationState.NOT_ASSESSED

    val isTerminal: Boolean get() = pipelineStatus.isTerminal

    val isOpen: Boolean get() = pipelineStatus.isOpen

    val hasPendingFollowUp: Boolean get() = nextFollowUp?.isCompleted == false

    /** At least one seller can lawfully be contacted through a channel we have. */
    val isSellerReachable: Boolean get() = sellers.any { it.isContactable }

    /** A do-not-contact request or a legal freeze is in force for any seller on this lead. */
    val isContactFrozen: Boolean
        get() = sellers.any { it.isFrozen } || communications.any { it.demandsDoNotContact }

    /** The seller has responded at least once. */
    val hasSellerResponse: Boolean get() = communications.any { it.outcome.isSellerResponse }

    /**
     * A two-way conversation with the *seller* has happened.
     *
     * Deliberately stricter than [CommunicationOutcome.isConnected]: a tenant, relative or agent
     * answering the phone proves the number works (which is what reachability scoring cares about) but
     * it is not a conversation with the person who can sign.
     */
    val hasSellerConversation: Boolean get() = communications.any { it.outcome.isSellerResponse }

    /** Most recent communication, by occurrence time. */
    val lastCommunication: LeadCommunication?
        get() = communications.maxByOrNull { it.occurredAtEpochMillis }

    /** Most recent offer reference, by the time it was referenced. */
    val latestOfferReference: LeadOfferReference?
        get() = offerReferences.maxByOrNull { it.referencedAtEpochMillis }

    /** When the current pipeline status was entered. */
    val stageEnteredAtEpochMillis: Long
        get() = transitions.lastOrNull { it.to == pipelineStatus }?.atEpochMillis ?: audit.createdAtEpochMillis

    /** How long the lead has been sitting in its current status. */
    fun timeInStageMillis(nowEpochMillis: Long): Long =
        (nowEpochMillis - stageEnteredAtEpochMillis).coerceAtLeast(0L)

    /** True when the lead has moved beyond its current status at any point (reopened/stalled leads). */
    val hasBackwardMoves: Boolean get() = transitions.any { !it.isForward }

    fun hasTag(tag: String): Boolean = LeadTags.normalize(tag)?.let { it in tags } ?: false

    fun followUpState(nowEpochMillis: Long, policy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT): FollowUpState =
        LeadFollowUpPlanner.stateOf(nextFollowUp, pipelineStatus, nowEpochMillis, policy)

    fun isFollowUpBreachingPlan(nowEpochMillis: Long, policy: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT): Boolean =
        LeadFollowUpPlanner.isBreachingPlan(this, nowEpochMillis, policy)

    /** Motivation aggregate as of a moment, using the given scoring policy. */
    fun motivationAggregate(
        asOfEpochMillis: Long,
        policy: MotivationScoringPolicy = MotivationScoringPolicy.DEFAULT
    ): MotivationAggregate = MotivationSignals.aggregate(motivationSignals, asOfEpochMillis, policy)

    /** Clock-free convenience: the strongest recorded signal strength. */
    val strongestMotivationStrength: MotivationStrength?
        get() = motivationSignals.maxByOrNull { it.strength.weight }?.strength

    fun describe(): String = buildString {
        append(id).append(" \"").append(displayName).append('"')
        append(" [").append(pipelineStatus.name)
        append(", ").append(priority.name).append(']')
        append(" source=").append(source.source.name)
        primarySeller?.let { append(" seller=").append(it.fullName) }
        subjectPropertyId?.let { append(" property=").append(it) }
        append(" qualification=").append(qualificationState.name)
        if (tags.isNotEmpty()) append(" tags=").append(tags.joinToString(","))
        if (isContactFrozen) append(" FROZEN")
    }

    // ── Evidence mutations (all audited, all append-only) ───────────────────────────────────────

    private fun touched(actor: String, atEpochMillis: Long, correlationId: String? = null): Lead = copy(
        audit = audit.touchedBy(actor, atEpochMillis, correlationId)
    )

    fun withSellers(newSellers: List<Seller>, actor: String, atEpochMillis: Long): Lead {
        require(newSellers.isNotEmpty()) { "A lead must keep at least one seller" }
        return copy(sellers = newSellers).touched(actor, atEpochMillis)
    }

    fun withSeller(seller: Seller, actor: String, atEpochMillis: Long): Lead {
        val replaced = sellers.filterNot { it.id == seller.id }
        return withSellers(replaced + seller, actor, atEpochMillis)
    }

    fun withPropertyLink(link: LeadPropertyLink, actor: String, atEpochMillis: Long): Lead = copy(
        propertyLinks = propertyLinks.filterNot { it.propertyId == link.propertyId } + link
    ).touched(actor, atEpochMillis)

    fun withMotivationSignal(signal: MotivationSignal, actor: String, atEpochMillis: Long): Lead {
        require(motivationSignals.none { it.id == signal.id }) { "Motivation signal ${signal.id} already exists" }
        return copy(motivationSignals = motivationSignals + signal).touched(actor, atEpochMillis)
    }

    fun withCondition(assessment: PropertyConditionAssessment, actor: String, atEpochMillis: Long): Lead =
        copy(condition = condition?.worstOf(assessment) ?: assessment).touched(actor, atEpochMillis)

    fun withTimeline(fact: SellerTimelineFact, actor: String, atEpochMillis: Long): Lead =
        copy(timeline = fact).touched(actor, atEpochMillis)

    fun withTag(tag: String, actor: String, atEpochMillis: Long): Lead {
        val normalized = LeadTags.normalize(tag) ?: return this
        return copy(tags = (tags + normalized).toSortedSet()).touched(actor, atEpochMillis)
    }

    fun withTags(newTags: Iterable<String>, actor: String, atEpochMillis: Long, policy: LeadTagPolicy = LeadTagPolicy.DEFAULT): Lead {
        val outcome = LeadTags.normalizeAll(tags + newTags, policy.maxTagsPerLead)
        return copy(tags = outcome.tags).touched(actor, atEpochMillis)
    }

    fun withoutTag(tag: String, actor: String, atEpochMillis: Long): Lead {
        val normalized = LeadTags.normalize(tag) ?: return this
        if (normalized !in tags) return this
        return copy(tags = (tags - normalized).toSortedSet()).touched(actor, atEpochMillis)
    }

    fun withNote(note: LeadNote, actor: String, atEpochMillis: Long): Lead {
        require(note.leadId == id) { "Note must belong to lead $id" }
        require(notes.none { it.id == note.id }) { "Note ${note.id} already exists" }
        return copy(notes = notes + note).touched(actor, atEpochMillis)
    }

    fun withCommunication(entry: LeadCommunication, actor: String, atEpochMillis: Long): Lead {
        require(entry.leadId == id) { "Communication must belong to lead $id" }
        require(communications.none { it.id == entry.id }) { "Communication ${entry.id} already exists" }
        val updatedSellers = if (entry.demandsDoNotContact && entry.isInbound) {
            sellers.map { seller ->
                if (entry.sellerId == null || seller.id == entry.sellerId) {
                    seller.withRestrictions(
                        seller.restrictions.copy(
                            doNotContactRequested = true,
                            note = entry.summary.ifBlank { "do-not-contact requested in communication ${entry.id}" }
                        ),
                        atEpochMillis = maxOf(seller.updatedAtEpochMillis, atEpochMillis)
                    )
                } else {
                    seller
                }
            }
        } else {
            sellers
        }
        return copy(sellers = updatedSellers, communications = communications + entry).touched(actor, atEpochMillis)
    }

    fun withOfferReference(reference: LeadOfferReference, actor: String, atEpochMillis: Long): Lead = copy(
        offerReferences = offerReferences.filterNot { it.offerId == reference.offerId } + reference
    ).touched(actor, atEpochMillis)

    fun withContract(contract: ContractReference, actor: String, atEpochMillis: Long): Lead =
        copy(contract = contract).touched(actor, atEpochMillis)

    fun withPriority(priority: LeadPriority, actor: String, atEpochMillis: Long): Lead =
        copy(priority = priority).touched(actor, atEpochMillis)

    fun withAssignment(assignee: String?, actor: String, atEpochMillis: Long): Lead =
        copy(assignedTo = assignee).touched(actor, atEpochMillis)

    fun withFollowUp(followUp: LeadFollowUp?, actor: String, atEpochMillis: Long): Lead {
        require(followUp == null || !pipelineStatus.isTerminal) { "A terminal lead does not take a follow-up" }
        return copy(nextFollowUp = followUp).touched(actor, atEpochMillis)
    }

    fun withCorrelationId(correlationId: String, actor: String, atEpochMillis: Long): Lead =
        copy(correlationId = correlationId).touched(actor, atEpochMillis, correlationId)

    /**
     * Applies a pipeline move. Internal to the CRM layer: callers use
     * [LeadPipelineStateMachine.transition], which validates, gates and audits the move.
     */
    internal fun withPipelineTransition(
        target: LeadPipelineStatus,
        transition: LeadTransition,
        actor: String,
        atEpochMillis: Long
    ): Lead {
        require(transition.to == target) { "Transition target must match the new pipeline status" }
        return copy(
            pipelineStatus = target,
            transitions = transitions + transition,
            nextFollowUp = if (target.isTerminal) null else nextFollowUp,
            audit = audit.touchedBy(actor, atEpochMillis, transition.correlationId)
        )
    }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 120
    }
}

/**
 * Reference to an offer produced by the existing offer pipeline.
 *
 * The CRM never owns offers (that is `OfferRepository`/`OfferEntity` territory); it records which
 * offer was made, for how much, and the offer status string it observed. This keeps the two bounded
 * contexts decoupled — a CRM bug can never corrupt a legal offer record, and the offer pipeline can be
 * refactored without touching this model.
 */
data class LeadOfferReference(
    val offerId: String,
    val amountUsd: Double,
    /** Offer status as reported by the offer pipeline (see [LeadOfferBridge]). */
    val status: String,
    val referencedAtEpochMillis: Long,
    val referencedBy: String
) {
    init {
        require(offerId.isNotBlank()) { "Offer reference must carry an offerId" }
        require(amountUsd.isFinite() && amountUsd > 0.0) { "Offer reference amount must be finite and positive" }
        require(status.isNotBlank()) { "Offer reference must carry the observed status" }
        require(referencedAtEpochMillis > 0L) { "Offer reference referencedAtEpochMillis must be positive" }
        require(referencedBy.isNotBlank()) { "Offer reference must record who recorded it" }
    }

    fun describe(): String = "offer $offerId $status $" + String.format(java.util.Locale.US, "%.0f", amountUsd)
}
