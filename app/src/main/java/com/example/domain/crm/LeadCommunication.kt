package com.example.domain.crm

/**
 * One entry of the communication history of a lead.
 *
 * The CRM stores *references*, not payloads: [externalRef] points at the provider-side message,
 * recording or email (Gmail message id, dialer call id, SMS thread id) and [relatedOfferId] points at
 * an offer in the existing offer pipeline. Communication bodies, recordings and attachments stay
 * where they already live; the lead keeps the fact, the outcome, the time and the link.
 *
 * The history is append-only. Corrections are new entries (with [LeadCommunication.supersedesId]),
 * never edits, because a contact log is the compliance record of who was contacted, when, how and
 * with what result.
 */
data class LeadCommunication(
    val id: String,
    val leadId: String,
    val channel: CommunicationChannel,
    val direction: CommunicationDirection,
    val outcome: CommunicationOutcome,
    /** When the contact actually happened (dialer/SMS/email timestamp). */
    val occurredAtEpochMillis: Long,
    /** When it was written into the CRM (usually within seconds; never before [occurredAtEpochMillis]). */
    val loggedAtEpochMillis: Long,
    /** Operator who logged it, or "system" for inbound ingestion. */
    val loggedBy: String,
    /** Seller the contact was with, when the lead has more than one. */
    val sellerId: String? = null,
    val summary: String = "",
    val durationSeconds: Int? = null,
    /** Provider-side identifier (reference only — the payload is not copied into the CRM). */
    val externalRef: String? = null,
    /** Offer this communication belongs to, when it is about a written offer. */
    val relatedOfferId: String? = null,
    /** Corrects an earlier entry (append-only correction chain). */
    val supersedesId: String? = null
) {
    init {
        require(id.isNotBlank()) { "Communication id must not be blank" }
        require(leadId.isNotBlank()) { "Communication leadId must not be blank" }
        require(occurredAtEpochMillis > 0L) { "Communication occurredAtEpochMillis must be positive" }
        require(loggedAtEpochMillis >= occurredAtEpochMillis) { "Communication cannot be logged before it occurred" }
        require(loggedBy.isNotBlank()) { "Communication must record who logged it" }
        require(summary.length <= MAX_SUMMARY_LENGTH) { "Communication summary must be at most $MAX_SUMMARY_LENGTH characters" }
        require(durationSeconds == null || durationSeconds >= 0) { "Communication durationSeconds must not be negative" }
        require(supersedesId == null || supersedesId != id) { "A communication cannot supersede itself" }
        require(durationSeconds == null || channel == CommunicationChannel.CALL) {
            "Only call communications carry a duration"
        }
        require(outcome.isCompatibleWith(direction)) {
            "Outcome ${outcome.name} is not compatible with direction ${direction.name}"
        }
    }

    /** Inbound: the seller initiated or replied. Inbound evidence is what unlocks RESPONDED. */
    val isInbound: Boolean get() = direction == CommunicationDirection.INBOUND

    /** A human on the other end: the only outcome that proves the contact path works. */
    val isConnected: Boolean get() = outcome.isConnected

    /** True when a two-way conversation happened (either direction). */
    val isConversation: Boolean get() = outcome.isConnected

    val isNegative: Boolean get() = outcome.isNegative

    /** True when the seller asked not to be contacted again — freezes the lead immediately. */
    val demandsDoNotContact: Boolean get() = outcome == CommunicationOutcome.DO_NOT_CONTACT_REQUESTED

    /** True when the entry is a failed attempt rather than a contact. */
    val isAttempt: Boolean get() = !isConnected

    /** Points at the artifact behind this entry, for audit. */
    val hasProviderReference: Boolean get() = !externalRef.isNullOrBlank()

    fun describe(): String = buildString {
        append(occurredAtEpochMillis).append(' ')
        append(direction.name.lowercase(java.util.Locale.US)).append(' ')
        append(channel.name.lowercase(java.util.Locale.US)).append(": ")
        append(outcome.name)
        if (summary.isNotBlank()) append(" — ").append(summary)
    }

    companion object {
        const val MAX_SUMMARY_LENGTH = 500
    }
}

/** Channel of a logged communication. */
enum class CommunicationChannel {
    CALL,
    SMS,
    EMAIL,
    LETTER,
    IN_PERSON,
    DRIVE_BY,
    OTHER;

    /** Channels an automated workflow is allowed to send through. */
    val isAutomatedCapable: Boolean
        get() = this == SMS || this == EMAIL

    /** Channels that require a recorded opt-out check before use. */
    val isRegulatedOutbound: Boolean
        get() = this == CALL || this == SMS || this == EMAIL
}

/** Direction of a communication. */
enum class CommunicationDirection {
    OUTBOUND,
    INBOUND;

    val isOutbound: Boolean get() = this == OUTBOUND
}

/**
 * Result of a contact attempt or of an inbound message.
 *
 * Outcomes are grouped by direction compatibility rather than by convenience, so an impossible entry
 * (an "inbound no answer") cannot be constructed.
 */
enum class CommunicationOutcome {
    /** Outbound call not answered. */
    NO_ANSWER,
    /** Outbound call reached voicemail. */
    LEFT_VOICEMAIL,
    /** A third party (relative, tenant, agent) answered. */
    REACHED_THIRD_PARTY,
    /** A real conversation happened. */
    CONNECTED,
    /** Number is wrong/disconnected. */
    BAD_NUMBER,
    /** Outbound letter was sent. */
    LETTER_SENT,
    /** Outbound letter came back undeliverable. */
    LETTER_RETURNED,
    /** Outbound email was sent. */
    EMAIL_SENT,
    /** Outbound email bounced. */
    EMAIL_BOUNCED,
    /** Inbound email/SMS reply. */
    SELLER_REPLIED,
    /** Seller asked for time or a callback (positive). */
    SELLER_INTERESTED,
    /** Seller said no. */
    SELLER_NOT_INTERESTED,
    /** Seller asked not to be contacted again (compliance event). */
    DO_NOT_CONTACT_REQUESTED,
    /** Seller accepted an offer verbally (still needs a signed contract). */
    OFFER_ACCEPTED_VERBALLY,
    /** Seller rejected the written offer. */
    OFFER_REJECTED,
    /** Seller countered. */
    OFFER_COUNTERED;

    /** Any human answered — including a third party, which proves the number works. */
    val isConnected: Boolean
        get() = this == REACHED_THIRD_PARTY || isSellerResponse

    /**
     * Outcomes that prove the *seller* engaged, whatever the sentiment (a "stop calling me" is a
     * response). A connected outbound call counts: the seller answered. A third party answering does
     * not. This is what unlocks the RESPONDED status.
     */
    val isSellerResponse: Boolean
        get() = this == CONNECTED || this == SELLER_REPLIED || this == SELLER_INTERESTED ||
            this == SELLER_NOT_INTERESTED || this == DO_NOT_CONTACT_REQUESTED ||
            this == OFFER_ACCEPTED_VERBALLY || this == OFFER_REJECTED || this == OFFER_COUNTERED

    val isNegative: Boolean
        get() = this == SELLER_NOT_INTERESTED || this == DO_NOT_CONTACT_REQUESTED || this == BAD_NUMBER ||
            this == LETTER_RETURNED || this == EMAIL_BOUNCED || this == OFFER_REJECTED

    /** Delivery artefacts of an outbound message that was never read by a human. */
    val isDeliveryArtefact: Boolean
        get() = this == LETTER_SENT || this == EMAIL_SENT

    val isInboundOnly: Boolean
        get() = this == SELLER_REPLIED || this == SELLER_INTERESTED || this == SELLER_NOT_INTERESTED ||
            this == DO_NOT_CONTACT_REQUESTED || this == OFFER_ACCEPTED_VERBALLY || this == OFFER_REJECTED ||
            this == OFFER_COUNTERED

    val allowsOutbound: Boolean get() = !isInboundOnly

    fun isCompatibleWith(direction: CommunicationDirection): Boolean =
        if (direction == CommunicationDirection.INBOUND) isInboundOnly else allowsOutbound

    /** Outcome that proves a working contact path exists (even if only a third party answered). */
    fun provesContactPath(): Boolean = isSellerResponse || this == CONNECTED || this == REACHED_THIRD_PARTY
}
