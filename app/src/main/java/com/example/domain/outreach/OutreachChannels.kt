package com.example.domain.outreach

/**
 * Abstract channel adapters. They prepare a payload a person can review. They do not open a socket,
 * call a provider SDK, or transmit SMS. Email delivery and manual dialing are also outside this engine.
 */
data class EmailChannelPayload(
    val to: String?,
    val subject: String,
    val body: String
)

data class SmsChannelPayload(
    val to: String?,
    val body: String,
    val transmissionImplemented: Boolean = false
) {
    init {
        require(!transmissionImplemented) { "SMS transmission is not implemented." }
    }
}

data class ManualCallChannelPayload(
    val phone: String?,
    val script: String,
    val spokenSummary: String
)

data class ChannelPreparation(
    val channel: OutreachChannel,
    val automaticTransmission: Boolean,
    val requiresHumanAction: Boolean,
    val deliveryBlocked: Boolean,
    val blockReasons: List<String>,
    val email: EmailChannelPayload?,
    val sms: SmsChannelPayload?,
    val manualCall: ManualCallChannelPayload?,
    val complianceNotes: List<String>
) {
    init {
        require(!automaticTransmission) { "Built-in outreach channels never transmit automatically." }
        require(requiresHumanAction) { "Abstract channels always require a person before any delivery." }
    }
}

data class TransmissionRefusal(
    val channel: OutreachChannel,
    val code: String,
    val message: String
)

interface OutreachChannelAdapter {
    val channel: OutreachChannel

    /** Built-in adapters must return false. This engine has no provider transport. */
    val transmits: Boolean

    fun prepare(draft: SellerMessageDraft, recipient: OutreachRecipient): ChannelPreparation
}

class EmailChannelAdapter : OutreachChannelAdapter {
    override val channel: OutreachChannel = OutreachChannel.EMAIL
    override val transmits: Boolean = false

    override fun prepare(draft: SellerMessageDraft, recipient: OutreachRecipient): ChannelPreparation {
        require(draft.channel == channel) { "Email adapter received a ${draft.channel} draft." }
        val blocked = draft.blocked || draft.body.isBlank()
        val reasons = draft.blockReasons.toMutableList()
        if (draft.body.isBlank()) reasons += "Email body is empty."
        return ChannelPreparation(
            channel = channel,
            automaticTransmission = false,
            requiresHumanAction = true,
            deliveryBlocked = blocked,
            blockReasons = reasons,
            email = EmailChannelPayload(
                to = recipient.email,
                subject = draft.subject ?: "Question about a property",
                body = draft.body
            ),
            sms = null,
            manualCall = null,
            complianceNotes = channelNotes(draft, recipient.email == null, "Recipient email is missing.")
        )
    }
}

class SmsChannelAdapter : OutreachChannelAdapter {
    override val channel: OutreachChannel = OutreachChannel.SMS
    override val transmits: Boolean = false

    override fun prepare(draft: SellerMessageDraft, recipient: OutreachRecipient): ChannelPreparation {
        require(draft.channel == channel) { "SMS adapter received a ${draft.channel} draft." }
        val reasons = draft.blockReasons.toMutableList()
        if (draft.body.isBlank()) reasons += "SMS body is empty."
        if (!recipient.smsConsentRecorded) {
            reasons += "SMS consent has not been recorded. Do not hand this draft to a transmitter."
        }
        val payload = SmsChannelPayload(
            to = recipient.phone,
            body = draft.body,
            transmissionImplemented = false
        )
        val blocked = draft.blocked || draft.body.isBlank() || !recipient.smsConsentRecorded
        return ChannelPreparation(
            channel = channel,
            automaticTransmission = false,
            requiresHumanAction = true,
            deliveryBlocked = blocked,
            blockReasons = if (blocked) reasons.ifEmpty { listOf(OutreachPolicy.SMS_NOT_IMPLEMENTED) } else reasons,
            email = null,
            sms = payload,
            manualCall = null,
            complianceNotes = channelNotes(draft, recipient.phone == null, "Recipient phone is missing.") +
                OutreachPolicy.SMS_NOT_IMPLEMENTED
        )
    }

    /** Always refuses. There is no SMS provider, gateway, or handset integration. */
    fun transmit(payload: SmsChannelPayload): TransmissionRefusal {
        require(!payload.transmissionImplemented)
        return TransmissionRefusal(
            channel = OutreachChannel.SMS,
            code = "SMS_TRANSMISSION_NOT_IMPLEMENTED",
            message = "SMS transmission is not implemented. This engine only prepares an abstract SMS draft."
        )
    }
}

class ManualCallChannelAdapter : OutreachChannelAdapter {
    override val channel: OutreachChannel = OutreachChannel.MANUAL_CALL
    override val transmits: Boolean = false

    override fun prepare(draft: SellerMessageDraft, recipient: OutreachRecipient): ChannelPreparation {
        require(draft.channel == channel) { "Manual-call adapter received a ${draft.channel} draft." }
        val script = draft.callScript.orEmpty()
        val blocked = draft.blocked || script.isBlank()
        val reasons = draft.blockReasons.toMutableList()
        if (script.isBlank()) reasons += "Manual-call script is empty."
        return ChannelPreparation(
            channel = channel,
            automaticTransmission = false,
            requiresHumanAction = true,
            deliveryBlocked = blocked,
            blockReasons = reasons,
            email = null,
            sms = null,
            manualCall = ManualCallChannelPayload(
                phone = recipient.phone,
                script = script,
                spokenSummary = draft.body
            ),
            complianceNotes = channelNotes(draft, false, "") + OutreachPolicy.MANUAL_CALL_NOT_DIALED
        )
    }
}

class OutreachChannelRegistry(adapters: List<OutreachChannelAdapter>) {
    private val byChannel = adapters.associateBy { it.channel }

    init {
        require(byChannel.keys == OutreachChannel.entries.toSet()) {
            "Channel registry must provide Email, SMS, and manual-call adapters."
        }
        require(adapters.all { !it.transmits }) { "Registry adapters must not transmit." }
    }

    fun adapter(channel: OutreachChannel): OutreachChannelAdapter = byChannel.getValue(channel)

    companion object {
        fun standard(): OutreachChannelRegistry = OutreachChannelRegistry(
            listOf(EmailChannelAdapter(), SmsChannelAdapter(), ManualCallChannelAdapter())
        )
    }
}

private fun channelNotes(draft: SellerMessageDraft, missingDestination: Boolean, missingMessage: String): List<String> {
    val notes = OutreachCompliance.notesFor(draft.channel, draft.blocked).toMutableList()
    if (missingDestination) notes += missingMessage
    return notes
}
