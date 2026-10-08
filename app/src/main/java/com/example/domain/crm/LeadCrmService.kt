package com.example.domain.crm

/**
 * The configuration of the whole CRM layer, in one validated object.
 *
 * Everything the service does is governed by these policies; passing a different bundle is the only way
 * to change behaviour, which keeps the layer testable (tests build small, explicit policies) and makes
 * a deployment's rules reviewable in one place.
 */
data class LeadCrmPolicy(
    val transition: LeadTransitionPolicy = LeadTransitionPolicy.DEFAULT,
    val qualification: LeadQualificationPolicy = LeadQualificationPolicy.DEFAULT,
    val priority: LeadPriorityPolicy = LeadPriorityPolicy.DEFAULT,
    val validation: LeadValidationPolicy = LeadValidationPolicy.DEFAULT,
    val followUp: LeadFollowUpPolicy = LeadFollowUpPolicy.DEFAULT,
    val tags: LeadTagPolicy = LeadTagPolicy.DEFAULT,
    val automation: LeadAutomationPolicy = LeadAutomationPolicy.DEFAULT
) {
    init {
        require(tags.maxTagsPerLead == validation.tagPolicy.maxTagsPerLead) {
            "tags.maxTagsPerLead and validation.tagPolicy.maxTagsPerLead must agree"
        }
        require(followUp.requireFollowUpForOpenLeads == validation.requireFollowUpForOpenLeads) {
            "followUp and validation must agree about requiring follow-ups on open leads"
        }
    }

    companion object {
        val DEFAULT = LeadCrmPolicy()
    }
}

/**
 * What the CRM does *by itself* when evidence arrives.
 *
 * Every switch is explicit and every automatic move goes through the same state machine as a manual
 * one, so the audit trail looks identical. Defaults are deliberately conservative: the CRM advances a
 * lead when the evidence already justifies it, and never closes a deal or kills a lead behind an
 * operator's back unless asked to.
 */
data class LeadAutomationPolicy(
    /** Scheduling the cadence follow-up when a lead is created or a stage is entered. */
    val autoScheduleFollowUp: Boolean = true,
    /** An outbound attempt moves NEW → CONTACTED. */
    val autoAdvanceToContactedOnOutboundAttempt: Boolean = true,
    /** A logged seller response moves the lead to RESPONDED (via CONTACTED when needed). */
    val autoAdvanceToRespondedOnSellerResponse: Boolean = true,
    /** A "not interested" outcome closes the lead out. Off by default: a human decides. */
    val autoLoseOnSellerDecline: Boolean = false,
    /** Re-run the deterministic qualification after every evidence change. */
    val autoReassessOnEvidenceChange: Boolean = true,
    /** Refresh the stored priority from the deterministic ladder after every write. */
    val autoRefreshPriority: Boolean = true
) {
    companion object {
        val DEFAULT = LeadAutomationPolicy()
    }
}

/** Everything needed to capture a lead. */
data class NewLeadRequest(
    val displayName: String,
    val source: LeadSourceAttribution,
    val sellers: List<Seller> = emptyList(),
    val propertyLinks: List<LeadPropertyLink> = emptyList(),
    val motivationSignals: List<MotivationSignal> = emptyList(),
    val condition: PropertyConditionAssessment? = null,
    val timeline: SellerTimelineFact? = null,
    val tags: Iterable<String> = emptyList(),
    val notes: List<LeadNote> = emptyList(),
    val assignedTo: String? = null,
    val correlationId: String? = null,
    /** Injected id, so imports and tests can be deterministic. Generated when absent. */
    val leadId: String? = null
)

/** A communication to log, before the CRM assigns it an id and a lead. */
data class LeadCommunicationDraft(
    val channel: CommunicationChannel,
    val direction: CommunicationDirection,
    val outcome: CommunicationOutcome,
    val occurredAtEpochMillis: Long,
    val summary: String = "",
    val durationSeconds: Int? = null,
    val externalRef: String? = null,
    val relatedOfferId: String? = null,
    val sellerId: String? = null
)

sealed interface LeadMutationResult {

    /** The write happened. [issues] carries non-blocking findings (warnings/info), never errors. */
    data class Applied(val lead: Lead, val issues: List<LeadValidationIssue> = emptyList()) : LeadMutationResult

    /** The write was refused because the record would be inconsistent. Nothing was persisted. */
    data class Rejected(val issues: List<LeadValidationIssue>) : LeadMutationResult

    data class NotFound(val leadId: String) : LeadMutationResult
}

sealed interface LeadMoveResult {

    data class Applied(val lead: Lead, val transition: LeadTransition) : LeadMoveResult

    data class NoOp(val lead: Lead, val status: LeadPipelineStatus) : LeadMoveResult

    /** Legal move, missing evidence. [blockers] is what the UI must show the operator. */
    data class Blocked(val lead: Lead, val blockers: List<LeadTransitionBlocker>) : LeadMoveResult

    data class Illegal(val lead: Lead, val message: String) : LeadMoveResult

    data class NotFound(val leadId: String) : LeadMoveResult
}

sealed interface LeadQualificationOutcome {

    data class Applied(
        val lead: Lead,
        val transition: LeadQualificationTransition,
        val assessment: LeadQualificationAssessment
    ) : LeadQualificationOutcome

    /** Nothing changed (a repeat reassessment, or an assessment that is still fresh). */
    data class Unchanged(val lead: Lead, val state: LeadQualificationState) : LeadQualificationOutcome

    /**
     * The change was computed but would have left an inconsistent record (for example DISQUALIFIED
     * while the lead is still engaged), so nothing was written. [issues] carries the errors.
     */
    data class Rejected(val lead: Lead, val issues: List<LeadValidationIssue>) : LeadQualificationOutcome

    data class Illegal(val lead: Lead, val message: String) : LeadQualificationOutcome

    data class NotFound(val leadId: String) : LeadQualificationOutcome
}

/**
 * The CRM application service: the only place where store writes, state machines, validation and
 * follow-up planning are composed.
 *
 * Each method follows the same shape: load → apply a *pure* domain operation → validate → persist →
 * return a typed result. Nothing here reads a clock (every method takes `atEpochMillis`) and nothing
 * here talks to the network, so the whole lifecycle is reproducible and the integration branch only has
 * to provide a [LeadStore] backed by Room plus a UI.
 */
class LeadCrmService(
    private val store: LeadStore,
    private val policy: LeadCrmPolicy = LeadCrmPolicy.DEFAULT,
    private val ids: LeadIdFactory = LeadIdFactory.DEFAULT,
    /**
     * Skip-trace seam. The default ([DisabledSkipTracePort]) is disabled and performs no lookup; this
     * branch never calls a real provider.
     */
    private val skipTrace: SkipTracePort = DisabledSkipTracePort
) {

    // ── Capture ─────────────────────────────────────────────────────────────────────────────────

    suspend fun createLead(request: NewLeadRequest, atEpochMillis: Long, actor: String): LeadMutationResult {
        require(actor.isNotBlank()) { "createLead requires an actor" }
        val leadId = request.leadId ?: ids.newLeadId()
        val normalizedTags = LeadTags.normalizeAll(request.tags, policy.tags.maxTagsPerLead).tags
        val followUp = if (policy.automation.autoScheduleFollowUp) {
            LeadFollowUpPlanner.schedule(LeadPipelineStatus.NEW, atEpochMillis, actor, policy.followUp)
        } else {
            null
        }
        var lead = Lead(
            id = leadId,
            displayName = request.displayName,
            pipelineStatus = LeadPipelineStatus.NEW,
            priority = LeadPriority.NORMAL,
            source = request.source,
            sellers = request.sellers,
            propertyLinks = request.propertyLinks,
            motivationSignals = request.motivationSignals,
            condition = request.condition,
            timeline = request.timeline ?: SellerTimelineFact.unknown(atEpochMillis),
            tags = normalizedTags,
            notes = request.notes,
            nextFollowUp = followUp,
            assignedTo = request.assignedTo,
            correlationId = request.correlationId,
            audit = CrmAuditMetadata.created(atEpochMillis, actor)
        )
        lead = autoAssess(lead, atEpochMillis, actor)
        lead = autoRefreshPriority(lead, atEpochMillis, actor)
        // Note: creation records no transition. NEW is the audit trail's starting point, so
        // `transitions` only ever contains real moves.
        return persist(lead, atEpochMillis)
    }

    // ── Evidence ────────────────────────────────────────────────────────────────────────────────

    suspend fun recordCommunication(
        leadId: String,
        draft: LeadCommunicationDraft,
        atEpochMillis: Long,
        actor: String
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val entry = LeadCommunication(
            id = ids.newCommunicationId(),
            leadId = leadId,
            channel = draft.channel,
            direction = draft.direction,
            outcome = draft.outcome,
            occurredAtEpochMillis = draft.occurredAtEpochMillis,
            loggedAtEpochMillis = atEpochMillis,
            loggedBy = actor,
            sellerId = draft.sellerId,
            summary = draft.summary,
            durationSeconds = draft.durationSeconds,
            externalRef = draft.externalRef,
            relatedOfferId = draft.relatedOfferId
        )
        var lead = loaded.withCommunication(entry, actor, atEpochMillis)
        lead = autoAssess(lead, atEpochMillis, actor)
        val stageBeforeAdvance = lead.pipelineStatus
        lead = autoAdvanceForCommunication(lead, entry, atEpochMillis, actor)
        if (lead.pipelineStatus != stageBeforeAdvance) lead = armFollowUp(lead, atEpochMillis, actor)
        return persist(lead, atEpochMillis)
    }

    suspend fun addNote(
        leadId: String,
        body: String,
        atEpochMillis: Long,
        actor: String,
        pinned: Boolean = false,
        tags: Iterable<String> = emptyList(),
        supersedesId: String? = null
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val note = LeadNote(
            id = ids.newNoteId(),
            leadId = leadId,
            body = body,
            author = actor,
            createdAtEpochMillis = atEpochMillis,
            pinned = pinned,
            tags = LeadTags.normalizeAll(tags, policy.tags.maxTagsPerLead).tags,
            supersedesId = supersedesId
        )
        val lead = loaded.withNote(note, actor, atEpochMillis)
        return persist(lead, atEpochMillis)
    }

    suspend fun addMotivationSignal(
        leadId: String,
        kind: MotivationSignalKind,
        strength: MotivationStrength,
        sourceKind: MotivationSourceKind,
        observedAtEpochMillis: Long,
        atEpochMillis: Long,
        actor: String,
        detail: String? = null,
        evidenceRef: String? = null
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val signal = MotivationSignal(
            id = ids.newMotivationSignalId(),
            kind = kind,
            strength = strength,
            sourceKind = sourceKind,
            observedAtEpochMillis = observedAtEpochMillis,
            recordedAtEpochMillis = atEpochMillis,
            recordedBy = actor,
            evidenceRef = evidenceRef,
            detail = detail
        )
        var lead = loaded.withMotivationSignal(signal, actor, atEpochMillis)
        lead = autoAssess(lead, atEpochMillis, actor)
        return persist(lead, atEpochMillis)
    }

    suspend fun recordCondition(
        leadId: String,
        assessment: PropertyConditionAssessment,
        atEpochMillis: Long,
        actor: String
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        var lead = loaded.withCondition(assessment, actor, atEpochMillis)
        lead = autoAssess(lead, atEpochMillis, actor)
        return persist(lead, atEpochMillis)
    }

    suspend fun recordTimeline(
        leadId: String,
        daysToClose: Int?,
        source: TimelineSource,
        atEpochMillis: Long,
        actor: String,
        detail: String? = null
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val fact = SellerTimelineFact(
            timeline = SellerTimeline.fromDays(daysToClose),
            statedDaysToClose = daysToClose,
            source = source,
            capturedAtEpochMillis = atEpochMillis,
            capturedBy = actor,
            detail = detail
        )
        var lead = loaded.withTimeline(fact, actor, atEpochMillis)
        lead = autoAssess(lead, atEpochMillis, actor)
        return persist(lead, atEpochMillis)
    }

    suspend fun addPropertyLink(
        leadId: String,
        link: LeadPropertyLink,
        atEpochMillis: Long,
        actor: String
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        var lead = loaded.withPropertyLink(link, actor, atEpochMillis)
        lead = autoAssess(lead, atEpochMillis, actor)
        return persist(lead, atEpochMillis)
    }

    suspend fun updateSeller(leadId: String, seller: Seller, atEpochMillis: Long, actor: String): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        var lead = loaded.withSeller(seller, actor, atEpochMillis)
        lead = autoAssess(lead, atEpochMillis, actor)
        return persist(lead, atEpochMillis)
    }

    suspend fun addTag(leadId: String, tag: String, atEpochMillis: Long, actor: String): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val lead = loaded.withTag(tag, actor, atEpochMillis)
        return persist(lead, atEpochMillis)
    }

    suspend fun removeTag(leadId: String, tag: String, atEpochMillis: Long, actor: String): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val lead = loaded.withoutTag(tag, actor, atEpochMillis)
        return persist(lead, atEpochMillis)
    }

    // ── Qualification ───────────────────────────────────────────────────────────────────────────

    /** Deterministic re-assessment, recorded through the qualification state machine. */
    suspend fun reassessQualification(
        leadId: String,
        atEpochMillis: Long,
        actor: String,
        inputs: LeadQualificationInputs? = null,
        withinServiceArea: Boolean? = null
    ): LeadQualificationOutcome {
        val loaded = store.findById(leadId) ?: return LeadQualificationOutcome.NotFound(leadId)
        val resolvedInputs = inputs ?: LeadQualificationInputs.fromLead(loaded, withinServiceArea)
        return qualificationWrite(
            loaded,
            LeadQualificationEngine.reassess(loaded, atEpochMillis, actor, resolvedInputs, policy.qualification),
            atEpochMillis
        )
    }

    /** Degrades a stale assessment; a no-op when the assessment is still fresh. */
    suspend fun decayStaleQualification(leadId: String, atEpochMillis: Long, actor: String): LeadQualificationOutcome {
        val loaded = store.findById(leadId) ?: return LeadQualificationOutcome.NotFound(leadId)
        return qualificationWrite(
            loaded,
            LeadQualificationEngine.decayIfStale(loaded, atEpochMillis, actor, policy.qualification),
            atEpochMillis
        )
    }

    /** Operator override, always audited with its justification. */
    suspend fun overrideQualification(
        leadId: String,
        target: LeadQualificationState,
        note: String,
        atEpochMillis: Long,
        actor: String
    ): LeadQualificationOutcome {
        val loaded = store.findById(leadId) ?: return LeadQualificationOutcome.NotFound(leadId)
        return qualificationWrite(
            loaded,
            LeadQualificationEngine.overrideState(loaded, target, atEpochMillis, actor, note, policy.qualification),
            atEpochMillis
        )
    }

    // ── Pipeline ────────────────────────────────────────────────────────────────────────────────

    /** Moves a lead through the pipeline, gating on evidence and recording the audit entry. */
    suspend fun moveTo(
        leadId: String,
        target: LeadPipelineStatus,
        reason: LeadTransitionReason,
        atEpochMillis: Long,
        actor: String,
        detail: String? = null,
        correlationId: String? = null
    ): LeadMoveResult {
        val loaded = store.findById(leadId) ?: return LeadMoveResult.NotFound(leadId)
        return when (
            val result = LeadPipelineStateMachine.transition(
                lead = loaded,
                target = target,
                reason = reason,
                atEpochMillis = atEpochMillis,
                actor = actor,
                policy = policy.transition,
                qualificationPolicy = policy.qualification,
                correlationId = correlationId,
                detail = detail
            )
        ) {
            is LeadTransitionResult.Applied -> {
                val refreshed = autoRefreshPriority(
                    armFollowUp(result.lead, atEpochMillis, actor),
                    atEpochMillis,
                    actor
                )
                val issues = validate(refreshed, atEpochMillis).filter { it.severity == LeadValidationSeverity.ERROR }
                if (issues.isNotEmpty()) {
                    LeadMoveResult.Illegal(refreshed, "the move would leave an inconsistent record: " + issues.first().message)
                } else {
                    store.save(refreshed)
                    LeadMoveResult.Applied(refreshed, result.transition)
                }
            }
            is LeadTransitionResult.NoOp -> LeadMoveResult.NoOp(result.lead, result.status)
            is LeadTransitionResult.Blocked -> LeadMoveResult.Blocked(loaded, result.blockers)
            is LeadTransitionResult.Illegal -> LeadMoveResult.Illegal(loaded, result.message)
        }
    }

    /**
     * Records an offer from the existing offer pipeline and applies the pipeline move its status
     * justifies (see [LeadOfferBridge]). Unknown or non-decisional statuses record the reference only.
     */
    suspend fun registerOffer(
        leadId: String,
        offerId: String,
        amountUsd: Double,
        offerStatus: String,
        atEpochMillis: Long,
        actor: String
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val reference = LeadOfferBridge.reference(offerId, amountUsd, offerStatus, atEpochMillis, actor)
        val lead = loaded.withOfferReference(reference, actor, atEpochMillis)
        val transition = LeadOfferBridge.transitionFor(offerStatus)
        val moved = if (transition == null) {
            lead
        } else {
            when (
                val result = LeadPipelineStateMachine.transition(
                    lead = lead,
                    target = transition.target,
                    reason = transition.reason,
                    atEpochMillis = atEpochMillis,
                    actor = actor,
                    policy = policy.transition,
                    qualificationPolicy = policy.qualification,
                    detail = "offer $offerId reported $offerStatus"
                )
            ) {
                is LeadTransitionResult.Applied -> result.lead
                // A blocked move is not an error here: the offer record is still valid, the lead simply
                // does not advance (the caller can inspect the blockers by calling moveTo itself).
                is LeadTransitionResult.NoOp, is LeadTransitionResult.Blocked, is LeadTransitionResult.Illegal -> lead
            }
        }
        return persist(moved, atEpochMillis)
    }

    suspend fun registerContract(
        leadId: String,
        contract: ContractReference,
        atEpochMillis: Long,
        actor: String
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val lead = loaded.withContract(contract, actor, atEpochMillis)
        return persist(lead, atEpochMillis)
    }

    suspend fun assign(leadId: String, assignee: String?, atEpochMillis: Long, actor: String): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val lead = loaded.withAssignment(assignee, actor, atEpochMillis)
        return persist(lead, atEpochMillis)
    }

    // ── Follow-up ───────────────────────────────────────────────────────────────────────────────

    suspend fun scheduleFollowUp(
        leadId: String,
        atEpochMillis: Long,
        actor: String,
        channel: CommunicationChannel? = null,
        note: String? = null,
        daysFromNow: Int? = null
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        if (loaded.isTerminal) {
            return LeadMutationResult.Rejected(
                listOf(
                    LeadValidationIssue(
                        LeadValidationCodes.LEAD_TERMINAL,
                        LeadValidationSeverity.ERROR,
                        "nextFollowUp",
                        "a terminal lead (${loaded.pipelineStatus.name}) does not take a follow-up"
                    )
                )
            )
        }
        val cadence = LeadFollowUpPlanner.schedule(
            status = loaded.pipelineStatus,
            atEpochMillis = atEpochMillis,
            scheduledBy = actor,
            policy = policy.followUp,
            channel = channel ?: policy.followUp.defaultChannel,
            note = note
        ) ?: return LeadMutationResult.Rejected(
            listOf(
                LeadValidationIssue(
                    LeadValidationCodes.NO_FOLLOW_UP_CADENCE,
                    LeadValidationSeverity.ERROR,
                    "nextFollowUp",
                    "the follow-up policy defines no cadence for ${loaded.pipelineStatus.name}"
                )
            )
        )
        val explicit = daysFromNow?.let { days ->
            require(days > 0) { "daysFromNow must be positive" }
            cadence.copy(dueAtEpochMillis = CrmTime.plusDays(atEpochMillis, days))
        } ?: cadence
        val lead = loaded.withFollowUp(explicit, actor, atEpochMillis)
        return persist(lead, atEpochMillis)
    }

    suspend fun completeFollowUp(
        leadId: String,
        atEpochMillis: Long,
        actor: String,
        summary: String? = null,
        nextFollowUpInDays: Int? = null
    ): LeadMutationResult {
        val loaded = store.findById(leadId) ?: return LeadMutationResult.NotFound(leadId)
        val current = loaded.nextFollowUp ?: return LeadMutationResult.Rejected(
            listOf(
                LeadValidationIssue(
                    LeadValidationCodes.NO_PENDING_FOLLOW_UP,
                    LeadValidationSeverity.ERROR,
                    "nextFollowUp",
                    "there is no pending follow-up to complete"
                )
            )
        )
        val completed = current.complete(atEpochMillis, actor, summary)
        val next = when {
            loaded.isTerminal -> null
            nextFollowUpInDays != null -> LeadFollowUpPlanner.schedule(
                status = loaded.pipelineStatus,
                atEpochMillis = atEpochMillis,
                scheduledBy = actor,
                policy = policy.followUp
            )?.copy(dueAtEpochMillis = CrmTime.plusDays(atEpochMillis, nextFollowUpInDays))
            policy.automation.autoScheduleFollowUp -> LeadFollowUpPlanner.schedule(
                status = loaded.pipelineStatus,
                atEpochMillis = atEpochMillis,
                scheduledBy = actor,
                policy = policy.followUp
            )
            else -> null
        }
        // The completed follow-up is kept in the note trail so the cadence is auditable even after the
        // next one is scheduled.
        var lead = loaded.withNote(
            LeadNote(
                id = ids.newNoteId(),
                leadId = leadId,
                body = summary?.takeIf { it.isNotBlank() } ?: "follow-up completed (${current.channel.name})",
                author = actor,
                createdAtEpochMillis = atEpochMillis,
                isSystemGenerated = true
            ),
            actor,
            atEpochMillis
        )
        lead = lead.withFollowUp(next, actor, atEpochMillis)
        return persist(lead, atEpochMillis)
    }

    suspend fun dueFollowUps(nowEpochMillis: Long, limit: Int = LeadStore.DEFAULT_LIMIT): List<Lead> =
        store.listFollowUpsDueAtOrBefore(nowEpochMillis, limit)

    suspend fun openPipeline(limit: Int = LeadStore.DEFAULT_LIMIT): List<Lead> = store.listOpen(limit)

    // ── Read helpers ────────────────────────────────────────────────────────────────────────────

    suspend fun find(leadId: String): Lead? = store.findById(leadId)

    /** Recomputes the deterministic priority suggestion without persisting it. */
    suspend fun suggestedPriority(leadId: String, nowEpochMillis: Long): LeadPriorityEvaluation? {
        val lead = store.findById(leadId) ?: return null
        return LeadPriorityEngine.derive(
            LeadPriorityInputs.fromLead(lead, nowEpochMillis, policy.priority, policy.qualification, policy.followUp),
            policy.priority
        )
    }

    suspend fun validate(leadId: String, nowEpochMillis: Long? = null): List<LeadValidationIssue>? {
        val lead = store.findById(leadId) ?: return null
        return validate(lead, nowEpochMillis)
    }

    /**
     * Skip-trace seam. Delegates to the configured [SkipTracePort] — disabled on this branch — and never
     * applies results: candidates are returned for operator review, because writing a vendor's guess onto
     * a seller's record is a data-quality decision, not an automatic one.
     */
    suspend fun requestSellerContacts(
        leadId: String,
        sellerId: String,
        propertyAddress: PostalAddress? = null
    ): SkipTraceResult {
        val lead = store.findById(leadId) ?: return SkipTraceResult.NotConfigured(requestedProvider = skipTrace.providerName)
        val seller = lead.sellers.firstOrNull { it.id == sellerId }
            ?: return SkipTraceResult.Failed(skipTrace.providerName, "seller $sellerId is not on lead $leadId")
        if (!skipTrace.isEnabled) return SkipTraceResult.NotConfigured(requestedProvider = skipTrace.providerName)
        return skipTrace.lookup(
            SkipTraceRequest(
                sellerId = seller.id,
                fullName = seller.fullName,
                mailingAddress = seller.mailingAddress,
                propertyAddress = propertyAddress ?: lead.subjectPropertyLink?.address
            )
        )
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────────

    private fun validate(lead: Lead, nowEpochMillis: Long?): List<LeadValidationIssue> =
        LeadValidator.validate(
            lead = lead,
            nowEpochMillis = nowEpochMillis,
            policy = policy.validation,
            transitionPolicy = policy.transition,
            priorityPolicy = policy.priority
        )

    /**
     * Writes a qualification-only change through the same validation gate as every other write: a
     * change that would leave the record inconsistent (say, DISQUALIFIED while the lead is engaged) is
     * reported as [LeadQualificationOutcome.Rejected] and nothing is stored. A no-op stores nothing,
     * because "unchanged" must mean exactly that.
     */
    private suspend fun qualificationWrite(
        previous: Lead,
        result: LeadQualificationResult,
        atEpochMillis: Long
    ): LeadQualificationOutcome = when (result) {
        is LeadQualificationResult.Applied -> {
            val errors = validate(result.lead, atEpochMillis).filter { it.severity == LeadValidationSeverity.ERROR }
            if (errors.isNotEmpty()) {
                LeadQualificationOutcome.Rejected(previous, errors)
            } else {
                store.save(result.lead)
                LeadQualificationOutcome.Applied(result.lead, result.transition, result.assessment)
            }
        }
        is LeadQualificationResult.NoOp -> LeadQualificationOutcome.Unchanged(result.lead, result.state)
        is LeadQualificationResult.Illegal ->
            LeadQualificationOutcome.Illegal(previous, "${result.from} → ${result.to}: ${result.message}")
    }

    private suspend fun persist(
        lead: Lead,
        atEpochMillis: Long,
        transform: (Lead) -> LeadMutationResult = { LeadMutationResult.Applied(it) }
    ): LeadMutationResult {
        val issues = validate(lead, atEpochMillis)
        val errors = issues.filter { it.severity == LeadValidationSeverity.ERROR }
        if (errors.isNotEmpty()) return LeadMutationResult.Rejected(errors)
        store.save(lead)
        return when (val result = transform(lead)) {
            is LeadMutationResult.Applied -> result.copy(issues = issues.filter { it.severity != LeadValidationSeverity.ERROR })
            else -> result
        }
    }

    private suspend fun autoAssess(lead: Lead, atEpochMillis: Long, actor: String): Lead {
        if (!policy.automation.autoReassessOnEvidenceChange) return lead
        if (lead.isTerminal) return lead
        val inputs = LeadQualificationInputs.fromLead(lead)
        return when (
            val result = LeadQualificationEngine.reassess(lead, atEpochMillis, actor, inputs, policy.qualification)
        ) {
            is LeadQualificationResult.Applied -> result.lead
            is LeadQualificationResult.NoOp -> result.lead
            is LeadQualificationResult.Illegal -> lead
        }
    }

    /**
     * Re-arms the follow-up cadence for the stage a lead has just entered, when the deployment asks
     * for it. A terminal lead owes nothing, and a stage with no cadence defined is left alone.
     */
    private fun armFollowUp(lead: Lead, atEpochMillis: Long, actor: String): Lead {
        if (!policy.automation.autoScheduleFollowUp || lead.isTerminal) return lead
        val scheduled = LeadFollowUpPlanner.schedule(lead.pipelineStatus, atEpochMillis, actor, policy.followUp)
            ?: return lead
        return lead.withFollowUp(scheduled, actor, atEpochMillis)
    }

    private suspend fun autoRefreshPriority(lead: Lead, atEpochMillis: Long, actor: String): Lead {
        if (!policy.automation.autoRefreshPriority) return lead
        val derived = LeadPriorityEngine.derive(
            LeadPriorityInputs.fromLead(lead, atEpochMillis, policy.priority, policy.qualification, policy.followUp),
            policy.priority
        ).priority
        if (derived == lead.priority) return lead
        return lead.withPriority(derived, actor, atEpochMillis)
    }

    /**
     * Applies the deterministic follow-up of a logged communication: an outbound attempt marks the lead
     * as contacted, a seller response advances it, and (when the deployment asks for it) a decline closes
     * it out. Each move goes through the state machine, so a move the evidence does not support is simply
     * skipped rather than forced.
     */
    private suspend fun autoAdvanceForCommunication(
        lead: Lead,
        entry: LeadCommunication,
        atEpochMillis: Long,
        actor: String
    ): Lead {
        var current = lead
        fun move(target: LeadPipelineStatus, reason: LeadTransitionReason, detail: String): Lead = when (
            val result = LeadPipelineStateMachine.transition(
                current, target, reason, atEpochMillis, actor, policy.transition, policy.qualification, detail = detail
            )
        ) {
            is LeadTransitionResult.Applied -> result.lead
            else -> current
        }

        if (policy.automation.autoLoseOnSellerDecline && entry.isNegative && !entry.demandsDoNotContact &&
            current.pipelineStatus.isOpen
        ) {
            current = move(LeadPipelineStatus.LOST, LeadTransitionReason.SELLER_NOT_INTERESTED, "seller declined: ${entry.id}")
            return current
        }
        if (policy.automation.autoAdvanceToRespondedOnSellerResponse && entry.outcome.isSellerResponse) {
            if (current.pipelineStatus == LeadPipelineStatus.NEW) {
                // NEW → CONTACTED is an outreach edge, whatever the seller then said: the audit trail
                // records that the outreach happened, and the next hop records the response.
                current = move(LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, "seller responded: ${entry.id}")
            }
            if (current.pipelineStatus == LeadPipelineStatus.CONTACTED) {
                current = move(LeadPipelineStatus.RESPONDED, LeadTransitionReason.SELLER_RESPONDED, "seller responded: ${entry.id}")
            }
            return current
        }
        if (policy.automation.autoAdvanceToContactedOnOutboundAttempt && entry.direction.isOutbound &&
            current.pipelineStatus == LeadPipelineStatus.NEW
        ) {
            current = move(LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, "outbound attempt: ${entry.id}")
        }
        return current
    }
}
