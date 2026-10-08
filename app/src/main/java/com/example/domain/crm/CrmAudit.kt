package com.example.domain.crm

/**
 * Audit metadata carried by every CRM record.
 *
 * A CRM is a record of *who changed what, when and why* — the pipeline status, the qualification state
 * and the seller's contact permissions all have legal weight (TCPA/DNC disputes, contract disputes,
 * assignment-fee questions). Every mutation therefore goes through [touchedBy], which:
 *
 *  - refuses to move time backwards (`atEpochMillis >= updatedAtEpochMillis`), so the history cannot
 *    be rewritten by a stale writer;
 *  - increments [revision] exactly once per write, giving a cheap optimistic-concurrency token;
 *  - records the actor and an optional correlation id, so a write can be traced back to a specific
 *    operator action, automation cycle or import job.
 */
data class CrmAuditMetadata(
    val createdAtEpochMillis: Long,
    val createdBy: String,
    val updatedAtEpochMillis: Long,
    val updatedBy: String,
    val revision: Long = 0L,
    /** Value of [Lead.correlationId] / automation cycle that produced the last write. */
    val lastCorrelationId: String? = null
) {
    init {
        require(createdAtEpochMillis > 0L) { "createdAtEpochMillis must be positive" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) { "updatedAtEpochMillis must not precede createdAtEpochMillis" }
        require(createdBy.isNotBlank()) { "createdBy must not be blank" }
        require(updatedBy.isNotBlank()) { "updatedBy must not be blank" }
        require(revision >= 0L) { "revision must not be negative" }
        require(lastCorrelationId == null || lastCorrelationId.isNotBlank()) { "lastCorrelationId must not be blank when provided" }
    }

    val isPristine: Boolean get() = revision == 0L

    /** Records a write. */
    fun touchedBy(actor: String, atEpochMillis: Long, correlationId: String? = null): CrmAuditMetadata {
        require(actor.isNotBlank()) { "Audit actor must not be blank" }
        require(atEpochMillis >= updatedAtEpochMillis) {
            "Audit timestamps must not move backwards: $atEpochMillis < $updatedAtEpochMillis"
        }
        return copy(
            updatedAtEpochMillis = atEpochMillis,
            updatedBy = actor,
            revision = revision + 1L,
            lastCorrelationId = correlationId ?: lastCorrelationId
        )
    }

    companion object {
        fun created(atEpochMillis: Long, actor: String): CrmAuditMetadata = CrmAuditMetadata(
            createdAtEpochMillis = atEpochMillis,
            createdBy = actor,
            updatedAtEpochMillis = atEpochMillis,
            updatedBy = actor,
            revision = 0L
        )
    }
}

/**
 * One audited move of a lead through the pipeline.
 *
 * The transition records the *evidence snapshot* at the moment of the move (qualification state and
 * score), so a later dispute can be answered with "this is what was known when the lead moved to
 * UNDER_CONTRACT" without replaying the entire history.
 */
data class LeadTransition(
    val from: LeadPipelineStatus,
    val to: LeadPipelineStatus,
    val reason: LeadTransitionReason,
    val atEpochMillis: Long,
    val actor: String,
    val revision: Long,
    val qualificationState: LeadQualificationState? = null,
    val qualificationScore: Int? = null,
    val correlationId: String? = null,
    val detail: String? = null
) {
    init {
        require(from != to) { "A pipeline transition must change the status" }
        require(atEpochMillis > 0L) { "Transition atEpochMillis must be positive" }
        require(actor.isNotBlank()) { "Transition must record an actor" }
        require(revision >= 0L) { "Transition revision must not be negative" }
        require(qualificationScore == null || qualificationScore in 0..100) { "Transition qualification score must be within 0..100" }
        require(qualificationScore == null || qualificationState != null) {
            "A transition score requires the qualification state it belongs to"
        }
    }

    /**
     * True when the move advanced the forward path. A counter-offer (OFFER_SENT → NEGOTIATING) is a
     * legal move but *not* progress, and a loss is an exit rather than a stage.
     */
    val isForward: Boolean get() = !to.isLost && to.forwardRank > from.forwardRank

    fun describe(): String = buildString {
        append(from.name).append(" → ").append(to.name)
        append(" (").append(reason.name)
        qualificationState?.let { append(", ").append(it.name) }
        qualificationScore?.let { append(' ').append(it) }
        append(", rev ").append(revision).append(" by ").append(actor)
        if (atEpochMillis > 0L) append(" @ ").append(atEpochMillis)
        append(')')
        detail?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }
}

/**
 * The purchase contract behind a committed deal.
 *
 * Only the facts the CRM needs are stored; the document itself lives where it already lives
 * ([documentRef] is a reference, typically a file path managed by the document layer or an external
 * e-signature envelope id). Money fields are validated, and the date ordering is enforced so an
 * impossible contract (diligence ending after closing) cannot be recorded.
 */
data class ContractReference(
    val id: String,
    val contractPriceUsd: Double,
    val earnestMoneyUsd: Double,
    val signedAtEpochMillis: Long,
    val dueDiligenceEndsAtEpochMillis: Long? = null,
    val closingAtEpochMillis: Long? = null,
    /** Assignment fee the deal is expected to produce (disposition side). */
    val assignmentFeeUsd: Double? = null,
    /** Reference to the stored document / e-signature envelope. */
    val documentRef: String? = null,
    /** Buyer the contract would be assigned to, when known. */
    val assigneeRef: String? = null
) {
    init {
        require(id.isNotBlank()) { "Contract id must not be blank" }
        require(contractPriceUsd.isFinite() && contractPriceUsd > 0.0) { "Contract price must be finite and positive" }
        require(earnestMoneyUsd.isFinite() && earnestMoneyUsd >= 0.0) { "Contract earnest money must be finite and non-negative" }
        require(signedAtEpochMillis > 0L) { "Contract signedAtEpochMillis must be positive" }
        require(assignmentFeeUsd == null || (assignmentFeeUsd.isFinite() && assignmentFeeUsd >= 0.0)) {
            "Contract assignment fee must be finite and non-negative"
        }
        require(dueDiligenceEndsAtEpochMillis == null || dueDiligenceEndsAtEpochMillis >= signedAtEpochMillis) {
            "Due diligence cannot end before the contract was signed"
        }
        require(closingAtEpochMillis == null || closingAtEpochMillis >= signedAtEpochMillis) {
            "Closing cannot be scheduled before the contract was signed"
        }
        require(dueDiligenceEndsAtEpochMillis == null || closingAtEpochMillis == null ||
            closingAtEpochMillis >= dueDiligenceEndsAtEpochMillis
        ) { "Closing cannot be scheduled before due diligence ends" }
        require(documentRef == null || documentRef.isNotBlank()) { "Contract documentRef must not be blank when provided" }
    }

    val hasDueDiligenceWindow: Boolean get() = dueDiligenceEndsAtEpochMillis != null

    /** True when the diligence window is still open at [asOfEpochMillis]. */
    fun isDueDiligenceOpen(asOfEpochMillis: Long): Boolean =
        dueDiligenceEndsAtEpochMillis?.let { asOfEpochMillis <= it } ?: false

    /** True when the diligence window has lapsed: the deal either closes or must be renegotiated. */
    fun isDueDiligenceExpired(asOfEpochMillis: Long): Boolean =
        dueDiligenceEndsAtEpochMillis?.let { asOfEpochMillis > it } ?: false

    fun daysUntilClosing(asOfEpochMillis: Long): Long? =
        closingAtEpochMillis?.let { CrmTime.daysBetween(asOfEpochMillis, it) }

    fun describe(): String = buildString {
        append("contract ").append(id).append(" price=").append(String.format(java.util.Locale.US, "%.0f", contractPriceUsd))
        append(" earnest=").append(String.format(java.util.Locale.US, "%.0f", earnestMoneyUsd))
        dueDiligenceEndsAtEpochMillis?.let { append(" diligence-ends=").append(it) }
        closingAtEpochMillis?.let { append(" closes=").append(it) }
        assignmentFeeUsd?.let { append(" assignment-fee=").append(String.format(java.util.Locale.US, "%.0f", it)) }
    }
}
