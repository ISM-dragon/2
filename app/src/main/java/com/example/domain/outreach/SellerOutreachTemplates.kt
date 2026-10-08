package com.example.domain.outreach

/**
 * Deterministic template fallback. The same channel, purpose, tone, and normalized personalization
 * always render the same subject, body, and call script. Templates interpolate supplied fields only
 * and never invent seller motivation, property facts, prices, or credentials.
 */
object SellerOutreachTemplates {
    fun render(
        channel: OutreachChannel,
        purpose: OutreachPurpose,
        tone: OutreachTone,
        personalization: PersonalizationFields
    ): RenderedOutreachMessage {
        val place = place(personalization)
        val facts = renderFacts(personalization.verifiedFacts)
        return when (channel) {
            OutreachChannel.EMAIL -> RenderedOutreachMessage(
                subject = subject(purpose, place),
                body = emailBody(purpose, tone, personalization, place, facts),
                callScript = null
            )
            OutreachChannel.SMS -> RenderedOutreachMessage(
                subject = null,
                body = smsBody(purpose, tone, personalization, place),
                callScript = null
            )
            OutreachChannel.MANUAL_CALL -> RenderedOutreachMessage(
                subject = null,
                body = queueLine(personalization.senderName, place),
                callScript = callScript(purpose, personalization, place, facts)
            )
        }
    }

    fun place(personalization: PersonalizationFields): String {
        val parts = listOfNotNull(
            personalization.propertyAddress,
            personalization.propertyCity,
            personalization.propertyState
        )
        return if (parts.isEmpty()) OutreachLimits.GENERIC_PLACE else parts.joinToString(", ")
    }

    fun renderFacts(facts: List<VerifiedPropertyFact>): List<String> {
        val guard = OutreachClaimGuard()
        return facts.mapNotNull { fact ->
            val rendered = renderFact(fact) ?: return@mapNotNull null
            val findings = guard.inspect(
                rendered,
                GuardContext(
                    allowedFacts = listOf(fact),
                    sellerStatedNote = null,
                    allowedAddress = null,
                    identifiers = emptyList()
                )
            )
            rendered.takeIf { findings.isEmpty() }
        }
    }

    private fun renderFact(fact: VerifiedPropertyFact): String? {
        val number = OutreachSafetyRules.normalizeNumber(fact.value)
        return when (fact.key) {
            PropertyFactKey.BEDROOMS -> number?.let { "$it bedrooms" }
            PropertyFactKey.BATHROOMS -> number?.let { "$it bathrooms" }
            PropertyFactKey.LIVING_AREA_SQFT -> number?.let { "$it sq ft" }
            PropertyFactKey.YEAR_BUILT -> number?.let { "built in $it" }
            PropertyFactKey.LOT_SIZE -> "lot size ${fact.value}"
            PropertyFactKey.PROPERTY_TYPE -> "property type ${fact.value}"
            PropertyFactKey.LISTING_STATUS -> "listing status ${fact.value}"
            PropertyFactKey.OCCUPANCY -> "occupancy ${fact.value}"
            PropertyFactKey.CONDITION_NOTE -> "condition note ${fact.value}"
            PropertyFactKey.OTHER_DESCRIPTIVE -> null
        }
    }

    private fun subject(purpose: OutreachPurpose, place: String): String {
        val shortPlace = if (place.length <= 90) place else OutreachLimits.GENERIC_PLACE
        val raw = when (purpose) {
            OutreachPurpose.INTRODUCTION -> "Question about $shortPlace"
            OutreachPurpose.FOLLOW_UP -> "Follow-up regarding $shortPlace"
            OutreachPurpose.INFORMATION_REQUEST -> "Right contact for $shortPlace"
            OutreachPurpose.AVAILABILITY_CHECK -> "Availability regarding $shortPlace"
            OutreachPurpose.MEETING_REQUEST -> "Request to schedule a brief conversation"
            OutreachPurpose.STATUS_CHECK -> "Checking in regarding $shortPlace"
            OutreachPurpose.THANK_YOU -> "Thank you"
        }
        return if (raw.length <= OutreachLimits.EMAIL_SUBJECT) raw else raw.take(OutreachLimits.EMAIL_SUBJECT).trimEnd()
    }

    private fun emailBody(
        purpose: OutreachPurpose,
        tone: OutreachTone,
        personalization: PersonalizationFields,
        place: String,
        facts: List<String>
    ): String = buildString {
        appendLine(emailGreeting(tone, personalization.sellerDisplayName))
        appendLine()
        append(senderIntro(personalization.senderName, personalization.senderCompany))
        append(" I am writing about $place.")
        appendLine()
        appendLine()
        appendLine(ask(purpose, short = false))
        if (facts.isNotEmpty()) {
            appendLine()
            append("From the supplied record: ")
            append(facts.take(5).joinToString("; "))
            append(".")
            appendLine()
        }
        personalization.sellerStatedNote?.let { note ->
            appendLine()
            append("You previously noted: \"")
            append(note)
            append("\".")
            appendLine()
        }
        if (tone != OutreachTone.CONCISE) {
            appendLine()
            appendLine(toneClose(tone))
        }
        appendLine()
        appendLine(signature(tone, personalization.senderName))
        appendLine()
        append(OutreachLimits.EMAIL_OPT_OUT)
    }.trimEnd()

    private fun smsBody(
        purpose: OutreachPurpose,
        tone: OutreachTone,
        personalization: PersonalizationFields,
        place: String
    ): String {
        val full = smsCore(
            tone = tone,
            name = personalization.sellerDisplayName?.take(OutreachLimits.SMS_NAME),
            sender = personalization.senderName.take(OutreachLimits.SMS_NAME),
            company = personalization.senderCompany?.take(OutreachLimits.SMS_NAME),
            place = place,
            purpose = purpose
        )
        val fullMessage = full + OutreachLimits.SMS_OPT_OUT
        if (fullMessage.length <= OutreachLimits.SMS_BODY) return fullMessage
        val generic = smsCore(
            tone = tone,
            name = personalization.sellerDisplayName?.take(OutreachLimits.SMS_NAME),
            sender = personalization.senderName.take(OutreachLimits.SMS_NAME),
            company = null,
            place = OutreachLimits.GENERIC_PLACE,
            purpose = purpose
        )
        val genericMessage = generic + OutreachLimits.SMS_OPT_OUT
        if (genericMessage.length <= OutreachLimits.SMS_BODY) return genericMessage
        return fitSms(generic)
    }

    private fun smsCore(
        tone: OutreachTone,
        name: String?,
        sender: String,
        company: String?,
        place: String,
        purpose: OutreachPurpose
    ): String {
        val greeting = smsGreeting(tone, name)
        val who = if (company.isNullOrBlank()) sender else "$sender with $company"
        return "$greeting this is $who. I am reaching out about $place. ${ask(purpose, short = true)}"
    }

    fun fitSms(core: String): String {
        val footer = OutreachLimits.SMS_OPT_OUT
        val room = OutreachLimits.SMS_BODY - footer.length
        val normalized = core.replace(Regex("\\s+"), " ").trim()
        if (normalized.length + footer.length <= OutreachLimits.SMS_BODY) return normalized + footer
        val cut = normalized.take(room).trimEnd()
        val boundary = cut.lastIndexOf(' ')
        val shortened = if (boundary >= room / 2) cut.substring(0, boundary).trimEnd() else cut
        return shortened + footer
    }

    private fun callScript(
        purpose: OutreachPurpose,
        personalization: PersonalizationFields,
        place: String,
        facts: List<String>
    ): String = buildString {
        append("Opening: Introduce yourself as ")
        append(personalization.senderName)
        personalization.senderCompany?.let { append(" with ").append(it) }
        append(". Confirm you are speaking about ")
        append(place)
        appendLine(".")
        personalization.sellerDisplayName?.let {
            append("Ask for ")
            append(it)
            appendLine(" before discussing the property.")
        }
        append("Purpose: ")
        appendLine(ask(purpose, short = false))
        append("Supplied record: ")
        appendLine(if (facts.isEmpty()) "none." else facts.take(5).joinToString("; ") + ".")
        personalization.sellerStatedNote?.let {
            append("They previously said: \"")
            append(it)
            appendLine("\". Quote only that statement.")
        }
        appendLine("Limits: Do not guess personal circumstances. Do not state dollar amounts. Do not add property details beyond the supplied record.")
        append("Close: Thank them and offer to follow up only if they agree.")
    }

    private fun queueLine(sender: String, place: String): String =
        "Manual-call follow-up for $sender regarding $place. Use the call script. This engine does not place calls."

    private fun emailGreeting(tone: OutreachTone, name: String?): String = when (tone) {
        OutreachTone.FORMAL -> if (name != null) "Dear $name," else "Hello,"
        OutreachTone.WARM, OutreachTone.CONCISE -> if (name != null) "Hi $name," else "Hello,"
        OutreachTone.DIRECT -> if (name != null) "$name," else "Hello,"
        OutreachTone.PROFESSIONAL -> if (name != null) "Hello $name," else "Hello,"
    }

    private fun smsGreeting(tone: OutreachTone, name: String?): String = when (tone) {
        OutreachTone.FORMAL, OutreachTone.PROFESSIONAL -> if (name != null) "Hello $name," else "Hello,"
        else -> if (name != null) "Hi $name," else "Hello,"
    }

    private fun senderIntro(sender: String, company: String?): String =
        if (company.isNullOrBlank()) "My name is $sender." else "My name is $sender with $company."

    private fun ask(purpose: OutreachPurpose, short: Boolean): String = when (purpose) {
        OutreachPurpose.INTRODUCTION -> if (short) {
            "If a conversation is useful, reply to this message."
        } else {
            "If you are open to a conversation, I would welcome a convenient time to talk."
        }
        OutreachPurpose.FOLLOW_UP -> if (short) {
            "Following up in case my earlier note was missed."
        } else {
            "I am following up on my earlier note, in case it was missed."
        }
        OutreachPurpose.INFORMATION_REQUEST -> if (short) {
            "Are you the right contact for this property?"
        } else {
            "If you are the right contact, please tell me the best way to discuss the property. Please do not send account numbers or sensitive documents."
        }
        OutreachPurpose.AVAILABILITY_CHECK -> if (short) {
            "Is there a time that works for a brief conversation?"
        } else {
            "Is there a day and time that works for a brief conversation?"
        }
        OutreachPurpose.MEETING_REQUEST -> if (short) {
            "Would you schedule a brief call?"
        } else {
            "Would you be willing to schedule a brief call or meeting?"
        }
        OutreachPurpose.STATUS_CHECK -> if (short) {
            "Did you receive my earlier note?"
        } else {
            "I wanted to check whether you received my earlier note."
        }
        OutreachPurpose.THANK_YOU -> if (short) {
            "Thank you for your time."
        } else {
            "Thank you for your time. I will wait to hear from you before any further contact."
        }
    }

    private fun toneClose(tone: OutreachTone): String = when (tone) {
        OutreachTone.WARM -> "I appreciate you taking a moment."
        OutreachTone.DIRECT -> "Please let me know if a short conversation is useful."
        OutreachTone.FORMAL -> "I would be grateful for a brief reply."
        OutreachTone.PROFESSIONAL -> "Thank you for your time."
        OutreachTone.CONCISE -> "Thank you."
    }

    private fun signature(tone: OutreachTone, sender: String): String =
        if (tone == OutreachTone.FORMAL) "Respectfully,\n$sender" else "Thank you,\n$sender"
}
