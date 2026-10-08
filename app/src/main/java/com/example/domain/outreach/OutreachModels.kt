package com.example.domain.outreach

import java.util.UUID

/**
 * Provider-neutral seller outreach models.
 *
 * These types describe drafts, channels, purpose, tone, personalization, follow-up sequences, and
 * scheduled follow-ups. They are not CRM records and they are not delivery receipts. Nothing in this
 * package transmits Email or SMS, places a call, or decides price or financial terms.
 */

/** Abstract delivery channel. Built-in channels prepare messages only; none transmit. */
enum class OutreachChannel(val wireName: String) {
    EMAIL("email"),
    SMS("sms"),
    MANUAL_CALL("manual-call");

    /** Always false. SMS transmission in particular is not implemented. */
    val transmitsAutomatically: Boolean get() = false

    companion object {
        fun fromWireName(raw: String?): OutreachChannel? {
            val key = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == key }
        }
    }
}

/** Why this touch exists. None of these purposes authorize a price or financial-term decision. */
enum class OutreachPurpose(val wireName: String) {
    INTRODUCTION("introduction"),
    FOLLOW_UP("follow-up"),
    INFORMATION_REQUEST("information-request"),
    AVAILABILITY_CHECK("availability-check"),
    MEETING_REQUEST("meeting-request"),
    STATUS_CHECK("status-check"),
    THANK_YOU("thank-you");

    companion object {
        fun fromWireName(raw: String?): OutreachPurpose? {
            val key = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == key }
        }
    }
}

/** Salutation and closing voice. Tone never changes which facts may be stated. */
enum class OutreachTone(val wireName: String) {
    PROFESSIONAL("professional"),
    WARM("warm"),
    DIRECT("direct"),
    CONCISE("concise"),
    FORMAL("formal");

    companion object {
        fun fromWireName(raw: String?): OutreachTone? {
            val key = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == key }
        }
    }
}

/** Where the seller-facing prose came from. */
enum class DraftTextSource {
    DETERMINISTIC_TEMPLATE,
    AI_GENERATED
}

/**
 * Why AI text was not used. Null when the caller did not request AI text, or when AI text was
 * accepted. A non-null reason always means the deterministic template (or a blocked draft) was used.
 */
enum class AiFallbackReason {
    GENERATOR_ABSENT,
    GENERATOR_RETURNED_EMPTY,
    GENERATOR_FAILED,
    OUTPUT_REJECTED,
    CREDENTIALS_DETECTED,
    UNSUPPORTED_CLAIM,
    FABRICATED_SELLER_MOTIVATION,
    INVENTED_PROPERTY_FACT,
    FINANCIAL_TERMS_DETECTED,
    PROMPT_INJECTION,
    UNSAFE_CONTENT
}

enum class ScheduledFollowUpStatus {
    SCHEDULED,
    DUE,
    DRAFT_READY,
    COMPLETED,
    SKIPPED,
    CANCELLED;

    val isOpen: Boolean
        get() = this == SCHEDULED || this == DUE || this == DRAFT_READY
}

enum class SequenceStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    OPTED_OUT
}

enum class FollowUpOutcome {
    COMPLETED,
    SKIPPED,
    CANCELLED,
    OPTED_OUT
}

/** Descriptive property attributes the operator already has on record. There is no price key. */
enum class PropertyFactKey {
    BEDROOMS,
    BATHROOMS,
    LIVING_AREA_SQFT,
    YEAR_BUILT,
    LOT_SIZE,
    PROPERTY_TYPE,
    LISTING_STATUS,
    OCCUPANCY,
    CONDITION_NOTE,
    OTHER_DESCRIPTIVE
}

data class VerifiedPropertyFact(
    val key: PropertyFactKey,
    val value: String,
    val sourceLabel: String
) {
    init {
        require(value.isNotBlank()) { "A verified property fact needs a value." }
        require(sourceLabel.isNotBlank()) { "A verified property fact needs a source label." }
        require(value.length <= OutreachLimits.FACT_VALUE) { "Verified property fact value is too long." }
        require(sourceLabel.length <= OutreachLimits.SOURCE_LABEL) { "Verified property fact source is too long." }
    }
}

/**
 * Personalization fields supplied by the caller.
 *
 * Only values that survive [PersonalizationNormalizer] may be interpolated. Seller motivation may be
 * quoted only from [sellerStatedNote], and only when [sellerStatedNoteSource] says the seller, owner,
 * or listing agent actually said it. Price and financial terms are not personalization fields.
 */
data class PersonalizationFields(
    val sellerDisplayName: String? = null,
    val senderName: String,
    val senderCompany: String? = null,
    val propertyAddress: String? = null,
    val propertyCity: String? = null,
    val propertyState: String? = null,
    val propertyPostalCode: String? = null,
    val replyEmail: String? = null,
    val callbackPhone: String? = null,
    val verifiedFacts: List<VerifiedPropertyFact> = emptyList(),
    val sellerStatedNote: String? = null,
    val sellerStatedNoteSource: String? = null
)

/** Opaque destination hints. The engine does not dial, text, or email them. */
data class OutreachRecipient(
    val displayName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val smsConsentRecorded: Boolean = false,
    val emailConsentRecorded: Boolean = false,
    val callConsentRecorded: Boolean = false
)

data class SellerMessageDraft(
    val id: String,
    val sequenceId: String,
    val stepIndex: Int,
    val channel: OutreachChannel,
    val purpose: OutreachPurpose,
    val tone: OutreachTone,
    val subject: String?,
    val body: String,
    val callScript: String?,
    val textSource: DraftTextSource,
    val fallbackReason: AiFallbackReason?,
    val personalization: PersonalizationFields,
    val complianceNotes: List<String>,
    val createdAtEpochMillis: Long,
    val blocked: Boolean = false,
    val blockReasons: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank()) { "Draft id must not be blank." }
        require(sequenceId.isNotBlank()) { "Draft sequence id must not be blank." }
        require(stepIndex >= 0) { "Draft step index cannot be negative." }
        require(createdAtEpochMillis >= 0L) { "Draft timestamp cannot be negative." }
        require(!blocked || blockReasons.isNotEmpty()) { "A blocked draft must explain why." }
    }

    /** Seller-facing prose. Compliance notes are operator-facing and are not included. */
    fun sellerFacingText(): String = listOfNotNull(subject, body, callScript)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

/** One step inside a [FollowUpSequence]. Delay is relative to the previous step, not the anchor. */
data class FollowUpStep(
    val stepIndex: Int,
    val channel: OutreachChannel,
    val purpose: OutreachPurpose,
    val delayAfterPreviousMillis: Long,
    val tone: OutreachTone? = null
) {
    init {
        require(stepIndex >= 0) { "Follow-up step index cannot be negative." }
        require(delayAfterPreviousMillis >= 0L) { "Follow-up delay cannot be negative." }
    }
}

data class FollowUpSequence(
    val id: String,
    val name: String,
    val propertyReference: String?,
    val sellerReference: String?,
    val tone: OutreachTone,
    val status: SequenceStatus,
    val steps: List<FollowUpStep>,
    val createdAtEpochMillis: Long,
    val anchorEpochMillis: Long,
    val engineVersion: String
) {
    init {
        require(id.isNotBlank()) { "Sequence id must not be blank." }
        require(name.isNotBlank()) { "Sequence name must not be blank." }
        require(steps.isNotEmpty()) { "A follow-up sequence needs at least one step." }
        require(createdAtEpochMillis >= 0L && anchorEpochMillis >= 0L) {
            "Sequence timestamps cannot be negative."
        }
        require(engineVersion.isNotBlank()) { "Engine version must not be blank." }
    }
}

data class ScheduledFollowUp(
    val id: String,
    val sequenceId: String,
    val stepIndex: Int,
    val channel: OutreachChannel,
    val purpose: OutreachPurpose,
    val tone: OutreachTone,
    val scheduledAtEpochMillis: Long,
    val status: ScheduledFollowUpStatus,
    val draftId: String? = null,
    val completedAtEpochMillis: Long? = null,
    val cancellationReason: String? = null
) {
    init {
        require(id.isNotBlank()) { "Scheduled follow-up id must not be blank." }
        require(sequenceId.isNotBlank()) { "Scheduled follow-up sequence id must not be blank." }
        require(stepIndex >= 0) { "Scheduled follow-up step index cannot be negative." }
        require(scheduledAtEpochMillis >= 0L) { "Scheduled time cannot be negative." }
        require(completedAtEpochMillis == null || completedAtEpochMillis >= 0L) {
            "Completion time cannot be negative."
        }
    }
}

/**
 * In-memory workflow produced by [SellerOutreachEngine]. Persistence, if any, belongs to a caller.
 * This is not a CRM entity.
 */
data class SellerOutreachWorkflow(
    val sequence: FollowUpSequence,
    val scheduledFollowUps: List<ScheduledFollowUp>,
    val drafts: List<SellerMessageDraft>,
    val personalization: PersonalizationFields,
    val recipient: OutreachRecipient,
    val preferAiText: Boolean
)

data class StartOutreachRequest(
    val sequenceName: String = SellerOutreachDefaults.SEQUENCE_NAME,
    val propertyReference: String? = null,
    val sellerReference: String? = null,
    val personalization: PersonalizationFields,
    val tone: OutreachTone = OutreachTone.PROFESSIONAL,
    val steps: List<FollowUpStep> = SellerOutreachDefaults.steps(),
    val anchorEpochMillis: Long? = null,
    val preferAiText: Boolean = false,
    val recipient: OutreachRecipient = OutreachRecipient()
)

data class ComposeDraftRequest(
    val sequenceId: String,
    val stepIndex: Int,
    val channel: OutreachChannel,
    val purpose: OutreachPurpose,
    val tone: OutreachTone,
    val personalization: PersonalizationFields,
    val preferAiText: Boolean = false,
    val createdAtEpochMillis: Long? = null
)

data class RenderedOutreachMessage(
    val subject: String?,
    val body: String,
    val callScript: String?
)

/** Injectable clock so scheduled follow-ups are deterministic in tests. */
fun interface OutreachClock {
    fun nowMillis(): Long
}

object SystemOutreachClock : OutreachClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

fun interface OutreachIds {
    fun next(kind: String): String
}

object UuidOutreachIds : OutreachIds {
    override fun next(kind: String): String = "$kind-${UUID.randomUUID()}"
}

object SellerOutreachDefaults {
    const val ENGINE_VERSION = "seller-outreach-v1"
    const val SEQUENCE_NAME = "Seller follow-up"
    const val DAY_MILLIS = 86_400_000L
    const val MAX_STEPS = 12
    const val MAX_DELAY_MILLIS = 90L * DAY_MILLIS

    /**
     * Email introduction, SMS follow-up (draft only), manual-call follow-up, then an email status
     * check. Delays are cumulative from the previous step.
     */
    fun steps(tone: OutreachTone? = null): List<FollowUpStep> = listOf(
        FollowUpStep(0, OutreachChannel.EMAIL, OutreachPurpose.INTRODUCTION, 0L, tone),
        FollowUpStep(1, OutreachChannel.SMS, OutreachPurpose.FOLLOW_UP, 2 * DAY_MILLIS, tone),
        FollowUpStep(2, OutreachChannel.MANUAL_CALL, OutreachPurpose.FOLLOW_UP, 3 * DAY_MILLIS, tone),
        FollowUpStep(3, OutreachChannel.EMAIL, OutreachPurpose.STATUS_CHECK, 3 * DAY_MILLIS, tone)
    )
}

object OutreachLimits {
    const val EMAIL_SUBJECT = 120
    const val EMAIL_BODY = 4_000
    const val SMS_BODY = 320
    const val CALL_SCRIPT = 2_500
    const val NAME = 80
    const val COMPANY = 80
    const val ADDRESS = 120
    const val CITY = 60
    const val NOTE = 300
    const val FACT_VALUE = 80
    const val SOURCE_LABEL = 60
    const val MAX_FACTS = 8
    const val MAX_AI_RESPONSE_CHARS = 8_000
    const val SMS_NAME = 40

    const val EMAIL_OPT_OUT =
        "If you prefer not to hear from me, reply and I will not contact you again about this property."
    const val SMS_OPT_OUT = " Reply STOP to opt out."
    const val GENERIC_PLACE = "the property associated with this inquiry"
}

object OutreachPolicy {
    /**
     * Fixed constraint handed to any AI text generator. It is not caller-editable.
     * AI may generate text only and may never determine price or financial terms.
     */
    const val TEXT_ONLY_CONSTRAINT =
        "AI may generate text only and may never determine price or financial terms. " +
            "Do not invent property facts. Do not fabricate seller motivation. " +
            "Do not include credentials, secrets, unsupported claims, links, or guarantees. " +
            "Return prose only. If you cannot comply, return an empty body."

    const val PREPARES_ONLY = "This engine prepares messages only and does not transmit them."
    const val SMS_NOT_IMPLEMENTED = "SMS transmission is not implemented."
    const val EMAIL_NOT_PERFORMED =
        "Email is an abstract channel. This engine does not perform delivery."
    const val MANUAL_CALL_NOT_DIALED =
        "Manual-call follow-up is a script for a person. This engine does not place calls."
    const val NO_FINANCIAL_TERMS =
        "This workflow does not determine price or financial terms."
}

object OutreachCompliance {
    fun notesFor(channel: OutreachChannel, blocked: Boolean): List<String> {
        val notes = mutableListOf(
            OutreachPolicy.PREPARES_ONLY,
            OutreachPolicy.NO_FINANCIAL_TERMS,
            OutreachPolicy.TEXT_ONLY_CONSTRAINT
        )
        when (channel) {
            OutreachChannel.EMAIL -> notes += OutreachPolicy.EMAIL_NOT_PERFORMED
            OutreachChannel.SMS -> notes += OutreachPolicy.SMS_NOT_IMPLEMENTED
            OutreachChannel.MANUAL_CALL -> notes += OutreachPolicy.MANUAL_CALL_NOT_DIALED
        }
        if (blocked) notes += "This draft is blocked and must not be delivered."
        return notes
    }
}
