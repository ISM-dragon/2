package com.example.domain.crm

/**
 * Validation of a lead record.
 *
 * The aggregate's `init` blocks only *impossible* values (blank ids, duplicate children, non-finite
 * money). Everything that is a judgement call — "an open lead should have a seller", "an offer-stage
 * lead must reference an offer", "this follow-up is overdue" — is reported here as a
 * [LeadValidationIssue] with a stable code, so imports, the UI and the automation can decide how loudly
 * to react instead of being unable to store a partially known lead at all.
 */
object LeadValidationCodes {
    const val MISSING_SELLER = "missing-seller"
    const val NO_CONTACT_CHANNEL = "no-contact-channel"
    const val CONTACT_FROZEN_BUT_OPEN = "contact-frozen-but-open"
    const val MISSING_PROPERTY_LINK = "missing-property-link"
    const val MISSING_FOLLOW_UP = "missing-follow-up"
    const val FOLLOW_UP_STAGE_MISMATCH = "follow-up-stage-mismatch"
    const val FOLLOW_UP_OVERDUE = "follow-up-overdue"
    const val MISSING_QUALIFICATION = "missing-qualification"
    const val QUALIFICATION_DISQUALIFIED_WHILE_ENGAGED = "qualification-disqualified-while-engaged"
    const val MISSING_TRANSITION_HISTORY = "missing-transition-history"
    const val MISSING_OFFER_REFERENCE = "missing-offer-reference"
    const val MISSING_CONTRACT = "missing-contract"
    const val MISSING_DUE_DILIGENCE_WINDOW = "missing-due-diligence-window"
    const val CONTRACT_WITHOUT_OFFER_STAGE = "contract-without-offer-stage"
    const val FUTURE_DATED_EVIDENCE = "future-dated-evidence"
    const val UNKNOWN_COMMUNICATION_SELLER = "unknown-communication-seller"
    const val TAG_COUNT_HIGH = "tag-count-high"
    const val TAG_COUNT_EXCEEDED = "tag-count-exceeded"
    const val MISSING_MOTIVATION = "missing-motivation"
    const val UNKNOWN_CONDITION = "unknown-condition"
    const val UNKNOWN_TIMELINE = "unknown-timeline"
    const val PRIORITY_DRIFT = "priority-drift"
    const val REFERRAL_WITHOUT_REFERRER = "referral-without-referrer"
    const val DIRECT_MAIL_WITHOUT_LIST = "direct-mail-without-list"
    const val PAID_SOURCE_WITHOUT_COST = "paid-source-without-cost"
    const val STALE_QUALIFICATION = "stale-qualification"
    const val LEAD_TERMINAL = "lead-terminal"
    const val NO_FOLLOW_UP_CADENCE = "no-follow-up-cadence"
    const val NO_PENDING_FOLLOW_UP = "no-pending-follow-up"
}

enum class LeadValidationSeverity {
    /** The record contradicts itself or loses legally relevant information: fix before persisting. */
    ERROR,
    /** Workable, but the pipeline is missing something an operator should fix. */
    WARNING,
    /** Informational: useful context, no action implied. */
    INFO
}

data class LeadValidationIssue(
    val code: String,
    val severity: LeadValidationSeverity,
    val field: String,
    val message: String
) {
    init {
        require(code.isNotBlank()) { "Validation issue code must not be blank" }
        require(field.isNotBlank()) { "Validation issue field must not be blank" }
        require(message.isNotBlank()) { "Validation issue message must not be blank" }
    }

    fun describe(): String = "${severity.name} [$code] $field: $message"
}

/** Thresholds and switches for validation. Validated so a bad configuration fails loudly. */
data class LeadValidationPolicy(
    val tagPolicy: LeadTagPolicy = LeadTagPolicy.DEFAULT,
    /** An open lead is expected to have at least one seller (and one permitted channel). */
    val requireSellerForOpenLeads: Boolean = true,
    val requireUsableChannelForOpenLeads: Boolean = true,
    /** An open lead is expected to have a next touch scheduled. */
    val requireFollowUpForOpenLeads: Boolean = true,
    /** Days a pending follow-up may sit past its due instant before it is reported. */
    val followUpOverdueWarningDays: Int = 1,
    /** Offer-stage leads must reference an offer from the offer pipeline. */
    val requireOfferReferenceForOfferStage: Boolean = true,
    /** Committed leads must reference the purchase contract. */
    val requireContractForCommittedStage: Boolean = true,
    /** Engaged leads must not be sitting on a DISQUALIFIED qualification. */
    val forbidDisqualifiedWhileEngaged: Boolean = true,
    /** Report an operator-set priority that differs from the deterministic suggestion. */
    val reportPriorityDrift: Boolean = true
) {
    init {
        require(followUpOverdueWarningDays in 0..90) { "followUpOverdueWarningDays must be within 0..90" }
    }

    companion object {
        val DEFAULT = LeadValidationPolicy()
    }
}

/** Deterministic validation of a lead and everything it carries. */
object LeadValidator {

    fun validate(
        lead: Lead,
        nowEpochMillis: Long? = null,
        policy: LeadValidationPolicy = LeadValidationPolicy.DEFAULT,
        transitionPolicy: LeadTransitionPolicy = LeadTransitionPolicy.DEFAULT,
        priorityPolicy: LeadPriorityPolicy = LeadPriorityPolicy.DEFAULT
    ): List<LeadValidationIssue> {
        val issues = mutableListOf<LeadValidationIssue>()

        fun issue(code: String, severity: LeadValidationSeverity, field: String, message: String) {
            issues += LeadValidationIssue(code, severity, field, message)
        }

        // ── Pipeline / evidence coherence ──────────────────────────────────────────────────────
        if (lead.pipelineStatus != LeadPipelineStatus.NEW && lead.transitions.isEmpty()) {
            issue(
                LeadValidationCodes.MISSING_TRANSITION_HISTORY,
                LeadValidationSeverity.ERROR,
                "transitions",
                "status ${lead.pipelineStatus.name} without an audited transition: the pipeline history was lost"
            )
        }
        if (policy.requireOfferReferenceForOfferStage && lead.pipelineStatus.hasWrittenOffer && lead.offerReferences.isEmpty()) {
            issue(
                LeadValidationCodes.MISSING_OFFER_REFERENCE,
                LeadValidationSeverity.ERROR,
                "offerReferences",
                "status ${lead.pipelineStatus.name} requires a reference to the offer that was submitted"
            )
        }
        if (policy.requireContractForCommittedStage && lead.pipelineStatus.requiresContract && lead.contract == null) {
            issue(
                LeadValidationCodes.MISSING_CONTRACT,
                LeadValidationSeverity.ERROR,
                "contract",
                "status ${lead.pipelineStatus.name} requires the referenced purchase contract"
            )
        }
        if (lead.pipelineStatus == LeadPipelineStatus.DUE_DILIGENCE && lead.contract?.hasDueDiligenceWindow != true) {
            issue(
                LeadValidationCodes.MISSING_DUE_DILIGENCE_WINDOW,
                LeadValidationSeverity.ERROR,
                "contract.dueDiligenceEndsAtEpochMillis",
                "DUE_DILIGENCE requires the contract's inspection period"
            )
        }
        if (lead.contract != null && !lead.pipelineStatus.hasWrittenOffer && !lead.pipelineStatus.isLost) {
            issue(
                LeadValidationCodes.CONTRACT_WITHOUT_OFFER_STAGE,
                LeadValidationSeverity.WARNING,
                "contract",
                "a contract is referenced while the status is ${lead.pipelineStatus.name}: the offer stage was skipped"
            )
        }
        if (lead.pipelineStatus.requiresQualification && lead.qualification == null) {
            issue(
                LeadValidationCodes.MISSING_QUALIFICATION,
                LeadValidationSeverity.ERROR,
                "qualification",
                "status ${lead.pipelineStatus.name} requires a qualification assessment"
            )
        }
        if (policy.forbidDisqualifiedWhileEngaged && lead.qualificationState == LeadQualificationState.DISQUALIFIED &&
            (lead.pipelineStatus.isEngaged || lead.pipelineStatus.hasWrittenOffer)
        ) {
            issue(
                LeadValidationCodes.QUALIFICATION_DISQUALIFIED_WHILE_ENGAGED,
                LeadValidationSeverity.ERROR,
                "qualification.state",
                "the lead is DISQUALIFIED but still in ${lead.pipelineStatus.name}"
            )
        }

        // ── Seller / contactability ────────────────────────────────────────────────────────────
        if (lead.sellers.isEmpty() && lead.isOpen && policy.requireSellerForOpenLeads) {
            issue(
                LeadValidationCodes.MISSING_SELLER,
                LeadValidationSeverity.WARNING,
                "sellers",
                "no seller/owner recorded on an open lead"
            )
        }
        if (lead.sellers.isNotEmpty() && !lead.isSellerReachable && lead.isOpen &&
            policy.requireUsableChannelForOpenLeads
        ) {
            issue(
                LeadValidationCodes.NO_CONTACT_CHANNEL,
                LeadValidationSeverity.WARNING,
                "sellers.contactPoints",
                "no permitted contact channel: on ${lead.sellers.first().restrictions.describe()}"
            )
        }
        if (lead.isContactFrozen && lead.pipelineStatus.isOpen) {
            // A warning, not an error: the do-not-contact outcome itself has to be recordable, and the
            // operator must be able to see the lead before moving it out. The transition gate is what
            // actually refuses further outreach.
            issue(
                LeadValidationCodes.CONTACT_FROZEN_BUT_OPEN,
                LeadValidationSeverity.WARNING,
                "sellers.restrictions",
                "contact is frozen (do-not-contact/litigation/bankruptcy) but the status is " +
                    "${lead.pipelineStatus.name}: move the lead out of an outreach stage"
            )
        }

        // ── Property linkage ───────────────────────────────────────────────────────────────────
        if (lead.subjectPropertyLink == null && lead.pipelineStatus != LeadPipelineStatus.LOST) {
            issue(
                LeadValidationCodes.MISSING_PROPERTY_LINK,
                LeadValidationSeverity.WARNING,
                "propertyLinks",
                "no subject property linked"
            )
        }

        // ── Follow-up ──────────────────────────────────────────────────────────────────────────
        val followUp = lead.nextFollowUp
        if (lead.isOpen && policy.requireFollowUpForOpenLeads && followUp == null) {
            issue(
                LeadValidationCodes.MISSING_FOLLOW_UP,
                LeadValidationSeverity.WARNING,
                "nextFollowUp",
                "no next follow-up scheduled on an open lead"
            )
        }
        if (followUp != null && followUp.scheduledFromStatus != lead.pipelineStatus && !followUp.isCompleted) {
            issue(
                LeadValidationCodes.FOLLOW_UP_STAGE_MISMATCH,
                LeadValidationSeverity.INFO,
                "nextFollowUp.scheduledFromStatus",
                "follow-up was scheduled from ${followUp.scheduledFromStatus.name} " +
                    "but the lead is in ${lead.pipelineStatus.name}"
            )
        }
        if (nowEpochMillis != null && followUp != null && !followUp.isCompleted) {
            val overdueDays = CrmTime.daysBetween(followUp.effectiveDueAtEpochMillis, nowEpochMillis)
            if (overdueDays > policy.followUpOverdueWarningDays) {
                issue(
                    LeadValidationCodes.FOLLOW_UP_OVERDUE,
                    LeadValidationSeverity.WARNING,
                    "nextFollowUp.dueAtEpochMillis",
                    "follow-up is $overdueDays day(s) late"
                )
            }
        }

        // ── Evidence timestamps (nothing may be dated after the record was last written) ───────
        val updatedAt = lead.audit.updatedAtEpochMillis
        if (lead.condition?.assessedAtEpochMillis?.let { it > updatedAt } == true) {
            issue(
                LeadValidationCodes.FUTURE_DATED_EVIDENCE,
                LeadValidationSeverity.ERROR,
                "condition.assessedAtEpochMillis",
                "the condition assessment is dated after the last write"
            )
        }
        lead.motivationSignals.filter { it.recordedAtEpochMillis > updatedAt }.forEach { signal ->
            issue(
                LeadValidationCodes.FUTURE_DATED_EVIDENCE,
                LeadValidationSeverity.ERROR,
                "motivationSignals[${signal.id}]",
                "the signal is dated after the last write"
            )
        }
        lead.communications.filter { it.loggedAtEpochMillis > updatedAt }.forEach { entry ->
            issue(
                LeadValidationCodes.FUTURE_DATED_EVIDENCE,
                LeadValidationSeverity.ERROR,
                "communications[${entry.id}]",
                "the communication is dated after the last write"
            )
        }
        lead.timeline.capturedAtEpochMillis.takeIf { it > updatedAt }?.let {
            issue(
                LeadValidationCodes.FUTURE_DATED_EVIDENCE,
                LeadValidationSeverity.ERROR,
                "timeline.capturedAtEpochMillis",
                "the timeline claim is dated after the last write"
            )
        }
        val sellerIds = lead.sellers.map { it.id }.toSet()
        lead.communications.mapNotNull { it.sellerId }.filterNot { it in sellerIds }.distinct().forEach { unknown ->
            issue(
                LeadValidationCodes.UNKNOWN_COMMUNICATION_SELLER,
                LeadValidationSeverity.WARNING,
                "communications.sellerId",
                "communication references seller $unknown, which is not on the lead"
            )
        }

        // ── Completeness (soft) ────────────────────────────────────────────────────────────────
        if (lead.motivationSignals.isEmpty() && lead.isOpen) {
            issue(
                LeadValidationCodes.MISSING_MOTIVATION,
                LeadValidationSeverity.WARNING,
                "motivationSignals",
                "no motivation signal: the seller's reason to sell is unknown"
            )
        }
        if (lead.condition?.isKnown != true && lead.isOpen) {
            issue(
                LeadValidationCodes.UNKNOWN_CONDITION,
                LeadValidationSeverity.INFO,
                "condition",
                "property condition not assessed"
            )
        }
        if (!lead.timeline.isKnown && lead.isOpen) {
            issue(
                LeadValidationCodes.UNKNOWN_TIMELINE,
                LeadValidationSeverity.INFO,
                "timeline",
                "seller timeline not captured"
            )
        }
        if (lead.tags.size > policy.tagPolicy.warnAboveTagCount) {
            issue(
                LeadValidationCodes.TAG_COUNT_HIGH,
                LeadValidationSeverity.WARNING,
                "tags",
                "${lead.tags.size} tags: usually a sign of tag sprawl"
            )
        }
        if (lead.tags.size > policy.tagPolicy.maxTagsPerLead) {
            issue(
                LeadValidationCodes.TAG_COUNT_EXCEEDED,
                LeadValidationSeverity.ERROR,
                "tags",
                "${lead.tags.size} tags exceeds the policy maximum ${policy.tagPolicy.maxTagsPerLead}"
            )
        }
        if (lead.source.source == LeadSourceKind.REFERRAL && lead.source.referrerName.isNullOrBlank()) {
            issue(
                LeadValidationCodes.REFERRAL_WITHOUT_REFERRER,
                LeadValidationSeverity.WARNING,
                "source.referrerName",
                "a referral without a referrer cannot be thanked, tracked or repeated"
            )
        }
        if (lead.source.source == LeadSourceKind.DIRECT_MAIL && lead.source.listName.isNullOrBlank()) {
            issue(
                LeadValidationCodes.DIRECT_MAIL_WITHOUT_LIST,
                LeadValidationSeverity.WARNING,
                "source.listName",
                "direct mail without the list name makes the campaign unattributable"
            )
        }
        if (lead.source.isPaid && lead.source.costMicros == null) {
            issue(
                LeadValidationCodes.PAID_SOURCE_WITHOUT_COST,
                LeadValidationSeverity.INFO,
                "source.costMicros",
                "paid source without an attributed cost: cost per contract cannot be computed"
            )
        }

        // ── Qualification freshness and priority drift (need "now") ────────────────────────────
        if (nowEpochMillis != null) {
            val assessment = lead.qualification
            if (assessment != null && assessment.isStale(nowEpochMillis) && lead.pipelineStatus.requiresQualification) {
                issue(
                    LeadValidationCodes.STALE_QUALIFICATION,
                    LeadValidationSeverity.WARNING,
                    "qualification.evaluatedAtEpochMillis",
                    "the qualification is ${assessment.ageDays(nowEpochMillis)} days old: re-assess before the next move"
                )
            }
            if (policy.reportPriorityDrift) {
                val derived = LeadPriorityEngine.derive(
                    LeadPriorityInputs.fromLead(lead, nowEpochMillis, priorityPolicy),
                    priorityPolicy
                ).priority
                if (derived != lead.priority) {
                    issue(
                        LeadValidationCodes.PRIORITY_DRIFT,
                        LeadValidationSeverity.INFO,
                        "priority",
                        "stored priority ${lead.priority.name} differs from the derived ${derived.name}"
                    )
                }
            }
        }

        // Anything the transition gate would refuse right now is worth surfacing before the operator
        // tries the move: the codes are the same, so the UI can explain it once.
        if (!transitionPolicy.version.isBlank() && lead.pipelineStatus.isOpen) {
            lead.pipelineStatus.allowedNext().filterNot { it == lead.pipelineStatus }.forEach { target ->
                LeadTransitionGate.evaluate(lead, target, transitionPolicy, atEpochMillis = lead.audit.updatedAtEpochMillis)
                    .forEach { blocker ->
                        issue(
                            blocker.code,
                            LeadValidationSeverity.INFO,
                            "pipelineStatus",
                            "${target.name} is blocked: ${blocker.message}"
                        )
                    }
            }
        }

        return issues.distinctBy { Triple(it.code, it.field, it.message) }
    }
}

/** Convenience: validation issues of a lead, using the default policy. */
fun Lead.validate(
    nowEpochMillis: Long? = null,
    policy: LeadValidationPolicy = LeadValidationPolicy.DEFAULT
): List<LeadValidationIssue> = LeadValidator.validate(this, nowEpochMillis, policy)

/** True when a lead carries at least one [LeadValidationSeverity.ERROR]. */
fun Lead.hasValidationErrors(policy: LeadValidationPolicy = LeadValidationPolicy.DEFAULT): Boolean =
    validate(policy = policy).any { it.severity == LeadValidationSeverity.ERROR }
