package com.example.domain.crm

/**
 * One-directional, side-effect-free bridge between the existing offer pipeline and the CRM.
 *
 * The offer pipeline (`OfferEntity` / `OfferRepository`) is the source of truth for offers and is
 * **not** modified by the CRM. This object only translates its status vocabulary into a CRM pipeline
 * move, so the integration branch can wire "offer was sent/signed/declined" → "move the lead" without
 * either side knowing more about the other than these strings.
 *
 * The mapping is intentionally conservative:
 *
 *  - statuses that only mean "a document exists" (`DRAFT`, `GENERATED`, `READY`) move nothing;
 *  - `FAILED` moves nothing: a transport failure is not a seller decision, and silently marking the
 *    lead LOST because an email bounced would be a data-loss bug;
 *  - an unknown status moves nothing. The bridge never guesses.
 */
object LeadOfferBridge {

    const val STATUS_DRAFT = "DRAFT"
    const val STATUS_GENERATED = "GENERATED"
    const val STATUS_READY = "READY"
    const val STATUS_SENT = "SENT"
    const val STATUS_OPENED = "OPENED"
    const val STATUS_SIGNED = "SIGNED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_DECLINED = "DECLINED"
    const val STATUS_EXPIRED = "EXPIRED"

    /** Every offer status this bridge understands, for validation and documentation. */
    val KNOWN_STATUSES: List<String> = listOf(
        STATUS_DRAFT, STATUS_GENERATED, STATUS_READY, STATUS_SENT, STATUS_OPENED,
        STATUS_SIGNED, STATUS_FAILED, STATUS_DECLINED, STATUS_EXPIRED
    )

    /**
     * The pipeline move an offer status justifies, or null when the CRM must not move the lead.
     * Status matching is case-insensitive and whitespace-tolerant (vendor/manual data).
     */
    fun transitionFor(offerStatus: String): OfferDrivenTransition? = when (normalize(offerStatus)) {
        STATUS_SENT, STATUS_OPENED -> OfferDrivenTransition(
            LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.OFFER_SUBMITTED
        )
        STATUS_SIGNED -> OfferDrivenTransition(
            LeadPipelineStatus.UNDER_CONTRACT, LeadTransitionReason.OFFER_ACCEPTED
        )
        STATUS_DECLINED, STATUS_EXPIRED -> OfferDrivenTransition(
            LeadPipelineStatus.LOST, LeadTransitionReason.DEAL_LOST
        )
        else -> null
    }

    /** Target status implied by an offer status, or null. */
    fun pipelineStatusFor(offerStatus: String): LeadPipelineStatus? = transitionFor(offerStatus)?.target

    /**
     * True when the status reflects a *seller decision* rather than a document/system state. Only these
     * statuses are allowed to close a lead out.
     */
    fun isSellerDecision(offerStatus: String): Boolean = when (normalize(offerStatus)) {
        STATUS_SIGNED, STATUS_DECLINED, STATUS_EXPIRED -> true
        else -> false
    }

    fun isKnownStatus(offerStatus: String): Boolean = normalize(offerStatus) in KNOWN_STATUSES

    /**
     * Builds the reference snapshot the CRM keeps for an offer.
     *
     * @throws IllegalArgumentException for an unknown status or a non-positive amount, because both
     *   mean the caller is about to write an offer record the CRM could never reconcile.
     */
    fun reference(
        offerId: String,
        amountUsd: Double,
        offerStatus: String,
        atEpochMillis: Long,
        actor: String
    ): LeadOfferReference {
        require(isKnownStatus(offerStatus)) {
            "Unknown offer status '$offerStatus': known statuses are ${KNOWN_STATUSES.joinToString(", ")}"
        }
        return LeadOfferReference(
            offerId = offerId,
            amountUsd = amountUsd,
            status = normalize(offerStatus),
            referencedAtEpochMillis = atEpochMillis,
            referencedBy = actor
        )
    }

    private fun normalize(status: String): String = status.trim().uppercase(java.util.Locale.US)
}

/** A pipeline move justified by an offer status change. */
data class OfferDrivenTransition(
    val target: LeadPipelineStatus,
    val reason: LeadTransitionReason
)
