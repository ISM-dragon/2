package com.example.domain.outreach

import java.util.concurrent.CancellationException

/**
 * Provider-neutral seller communication workflow.
 *
 * Composes seller message drafts, follow-up sequences, and scheduled follow-ups for Email, SMS, and
 * manual-call channels. Channels are abstract: this engine does not transmit SMS, does not perform
 * email delivery, and does not place calls. AI may generate text only and may never determine price
 * or financial terms. Unsafe model text is discarded and replaced by a deterministic template.
 *
 * The engine does not read or write CRM records, offer records, or mail-provider sessions.
 */
class SellerOutreachEngine(
    private val textGenerator: SellerOutreachTextGenerator? = null,
    private val clock: OutreachClock = SystemOutreachClock,
    private val ids: OutreachIds = UuidOutreachIds,
    private val guard: OutreachClaimGuard = OutreachClaimGuard(),
    private val channels: OutreachChannelRegistry = OutreachChannelRegistry.standard()
) {
    suspend fun start(request: StartOutreachRequest): SellerOutreachWorkflow {
        validateSteps(request.steps)
        val now = clock.nowMillis()
        val anchor = request.anchorEpochMillis ?: now
        require(anchor >= 0L) { "Sequence anchor cannot be negative." }
        val normalized = PersonalizationNormalizer.normalize(request.personalization).fields
        val sequenceId = ids.next("sequence")
        val steps = request.steps.map { step -> step.copy(tone = step.tone ?: request.tone) }
        val sequence = FollowUpSequence(
            id = sequenceId,
            name = sequenceName(request.sequenceName),
            propertyReference = PersonalizationNormalizer.opaqueReference(request.propertyReference),
            sellerReference = PersonalizationNormalizer.opaqueReference(request.sellerReference),
            tone = request.tone,
            status = SequenceStatus.ACTIVE,
            steps = steps,
            createdAtEpochMillis = now,
            anchorEpochMillis = anchor,
            engineVersion = SellerOutreachDefaults.ENGINE_VERSION
        )
        val workflow = SellerOutreachWorkflow(
            sequence = sequence,
            scheduledFollowUps = scheduleFollowUps(sequenceId, steps, request.tone, anchor, now),
            drafts = emptyList(),
            personalization = normalized,
            recipient = PersonalizationNormalizer.normalizeRecipient(request.recipient),
            preferAiText = request.preferAiText
        )
        return composeDue(workflow, now)
    }

    suspend fun composeDraft(request: ComposeDraftRequest): SellerMessageDraft {
        require(request.stepIndex >= 0) { "Draft step index cannot be negative." }
        require(request.sequenceId.isNotBlank()) { "Draft sequence id must not be blank." }
        val normalized = PersonalizationNormalizer.normalize(request.personalization).fields
        val createdAt = request.createdAtEpochMillis ?: clock.nowMillis()
        require(createdAt >= 0L) { "Draft timestamp cannot be negative." }
        if (normalized.senderName.isBlank()) {
            return blockedDraft(request, normalized, createdAt, "Sender name is required.")
        }
        val template = SellerOutreachTemplates.render(
            request.channel,
            request.purpose,
            request.tone,
            normalized
        )
        val templateFindings = guard.inspect(sellerFacing(template), GuardContext.from(normalized))
        if (templateFindings.isNotEmpty()) {
            return blockedDraft(
                request,
                normalized,
                createdAt,
                "Deterministic template failed the safety check."
            )
        }
        if (!request.preferAiText) {
            return draftFrom(request, normalized, createdAt, template, DraftTextSource.DETERMINISTIC_TEMPLATE, null)
        }
        return when (val attempt = tryAi(request, normalized, template)) {
            is AiAttempt.Accepted -> draftFrom(
                request,
                normalized,
                createdAt,
                attempt.message,
                DraftTextSource.AI_GENERATED,
                null
            )
            is AiAttempt.Fallback -> draftFrom(
                request,
                normalized,
                createdAt,
                template,
                DraftTextSource.DETERMINISTIC_TEMPLATE,
                attempt.reason
            )
        }
    }

    suspend fun composeDue(
        workflow: SellerOutreachWorkflow,
        nowEpochMillis: Long = clock.nowMillis(),
        personalization: PersonalizationFields = workflow.personalization,
        force: Boolean = false
    ): SellerOutreachWorkflow {
        require(nowEpochMillis >= 0L) { "Clock time cannot be negative." }
        val normalized = PersonalizationNormalizer.normalize(personalization).fields
        val changed = normalized != workflow.personalization
        var current = refreshDue(workflow, nowEpochMillis).copy(personalization = normalized)
        if (current.sequence.status != SequenceStatus.ACTIVE) return current
        val due = current.scheduledFollowUps
            .filter { item ->
                item.status.isOpen && item.scheduledAtEpochMillis <= nowEpochMillis
            }
            .sortedBy { it.stepIndex }
        for (item in due) {
            if (item.status == ScheduledFollowUpStatus.DRAFT_READY && !force && !changed) continue
            val existing = current.drafts.find { it.sequenceId == current.sequence.id && it.stepIndex == item.stepIndex }
            if (!force && !changed && existing != null) continue
            val draft = composeDraft(
                ComposeDraftRequest(
                    sequenceId = current.sequence.id,
                    stepIndex = item.stepIndex,
                    channel = item.channel,
                    purpose = item.purpose,
                    tone = item.tone,
                    personalization = normalized,
                    preferAiText = current.preferAiText,
                    createdAtEpochMillis = nowEpochMillis
                )
            )
            current = upsertDraft(current, draft)
            if (!draft.blocked) {
                current = replaceFollowUp(current, item.id) { followUp ->
                    followUp.copy(status = ScheduledFollowUpStatus.DRAFT_READY, draftId = draft.id)
                }
            }
        }
        return current
    }

    fun refreshDue(
        workflow: SellerOutreachWorkflow,
        nowEpochMillis: Long = clock.nowMillis()
    ): SellerOutreachWorkflow {
        if (workflow.sequence.status != SequenceStatus.ACTIVE) return workflow
        val updated = workflow.scheduledFollowUps.map { item ->
            if (item.status == ScheduledFollowUpStatus.SCHEDULED && item.scheduledAtEpochMillis <= nowEpochMillis) {
                item.copy(status = ScheduledFollowUpStatus.DUE)
            } else {
                item
            }
        }
        return workflow.copy(scheduledFollowUps = updated)
    }

    fun scheduleFollowUps(
        sequenceId: String,
        steps: List<FollowUpStep>,
        tone: OutreachTone,
        anchorEpochMillis: Long,
        nowEpochMillis: Long = clock.nowMillis()
    ): List<ScheduledFollowUp> {
        validateSteps(steps)
        require(sequenceId.isNotBlank()) { "Sequence id must not be blank." }
        require(anchorEpochMillis >= 0L) { "Sequence anchor cannot be negative." }
        var cursor = anchorEpochMillis
        return steps.sortedBy { it.stepIndex }.map { step ->
            val next = cursor + step.delayAfterPreviousMillis
            require(next >= cursor) { "Scheduled follow-up time overflowed." }
            cursor = next
            ScheduledFollowUp(
                id = ids.next("follow-up"),
                sequenceId = sequenceId,
                stepIndex = step.stepIndex,
                channel = step.channel,
                purpose = step.purpose,
                tone = step.tone ?: tone,
                scheduledAtEpochMillis = cursor,
                status = if (cursor <= nowEpochMillis) {
                    ScheduledFollowUpStatus.DUE
                } else {
                    ScheduledFollowUpStatus.SCHEDULED
                }
            )
        }
    }

    fun recordOutcome(
        workflow: SellerOutreachWorkflow,
        followUpId: String,
        outcome: FollowUpOutcome,
        atEpochMillis: Long = clock.nowMillis()
    ): SellerOutreachWorkflow {
        require(atEpochMillis >= 0L) { "Outcome timestamp cannot be negative." }
        val item = workflow.scheduledFollowUps.find { it.id == followUpId }
            ?: throw IllegalArgumentException("Unknown scheduled follow-up.")
        if (item.status == ScheduledFollowUpStatus.COMPLETED && outcome == FollowUpOutcome.COMPLETED) {
            return workflow
        }
        require(item.status.isOpen) { "Only an open follow-up can change outcome." }
        if (outcome == FollowUpOutcome.OPTED_OUT || workflow.sequence.status == SequenceStatus.OPTED_OUT) {
            val cancelled = workflow.scheduledFollowUps.map { current ->
                if (current.status.isOpen) {
                    current.copy(
                        status = ScheduledFollowUpStatus.CANCELLED,
                        cancellationReason = "Seller opted out."
                    )
                } else {
                    current
                }
            }
            return workflow.copy(
                sequence = workflow.sequence.copy(status = SequenceStatus.OPTED_OUT),
                scheduledFollowUps = cancelled
            )
        }
        val updatedItem = when (outcome) {
            FollowUpOutcome.COMPLETED -> item.copy(
                status = ScheduledFollowUpStatus.COMPLETED,
                completedAtEpochMillis = atEpochMillis
            )
            FollowUpOutcome.SKIPPED -> item.copy(
                status = ScheduledFollowUpStatus.SKIPPED,
                completedAtEpochMillis = atEpochMillis
            )
            FollowUpOutcome.CANCELLED -> item.copy(
                status = ScheduledFollowUpStatus.CANCELLED,
                cancellationReason = "Cancelled by operator."
            )
            FollowUpOutcome.OPTED_OUT -> item
        }
        val items = workflow.scheduledFollowUps.map { if (it.id == item.id) updatedItem else it }
        return workflow.copy(
            sequence = workflow.sequence.copy(status = deriveStatus(items, workflow.sequence.status)),
            scheduledFollowUps = items
        )
    }

    fun dueFollowUps(
        workflow: SellerOutreachWorkflow,
        nowEpochMillis: Long = clock.nowMillis()
    ): List<ScheduledFollowUp> {
        if (workflow.sequence.status != SequenceStatus.ACTIVE) return emptyList()
        return workflow.scheduledFollowUps.filter { item ->
            item.status.isOpen && item.scheduledAtEpochMillis <= nowEpochMillis
        }
    }

    fun prepareChannel(draft: SellerMessageDraft, recipient: OutreachRecipient): ChannelPreparation =
        channels.adapter(draft.channel).prepare(draft, PersonalizationNormalizer.normalizeRecipient(recipient))

    fun preparations(workflow: SellerOutreachWorkflow): List<ChannelPreparation> =
        workflow.drafts.map { prepareChannel(it, workflow.recipient) }

    /**
     * Refuses delivery for every built-in channel. SMS transmission is not implemented. Email is not
     * handed to a mail provider. Manual-call follow-up is not dialed.
     */
    fun transmit(preparation: ChannelPreparation): TransmissionRefusal {
        require(!preparation.automaticTransmission) { "Automatic transmission is not available." }
        return when (preparation.channel) {
            OutreachChannel.SMS -> SmsChannelAdapter().transmit(
                preparation.sms ?: SmsChannelPayload(to = null, body = preparation.complianceNotes.joinToString(" "))
            )
            OutreachChannel.EMAIL -> TransmissionRefusal(
                channel = OutreachChannel.EMAIL,
                code = "EMAIL_TRANSMISSION_NOT_PERFORMED",
                message = "Email transmission is provider-specific and is not performed by this engine."
            )
            OutreachChannel.MANUAL_CALL -> TransmissionRefusal(
                channel = OutreachChannel.MANUAL_CALL,
                code = "MANUAL_CALL_NOT_DIALED",
                message = "Manual-call follow-up is a script for a person. This engine does not place calls."
            )
        }
    }

    fun textPromptFor(request: ComposeDraftRequest): OutreachTextPrompt =
        promptFor(
            PersonalizationNormalizer.normalize(request.personalization).fields,
            request.channel,
            request.purpose,
            request.tone
        )

    fun validateSteps(steps: List<FollowUpStep>) {
        require(steps.isNotEmpty()) { "A follow-up sequence needs at least one step." }
        require(steps.size <= SellerOutreachDefaults.MAX_STEPS) {
            "A follow-up sequence cannot exceed ${SellerOutreachDefaults.MAX_STEPS} steps."
        }
        val indexes = steps.map { it.stepIndex }
        require(indexes.distinct().size == indexes.size) { "Follow-up step indexes must be unique." }
        require(indexes.sorted() == (0 until steps.size).toList()) {
            "Follow-up step indexes must be contiguous and start at 0."
        }
        require(steps.all { it.delayAfterPreviousMillis <= SellerOutreachDefaults.MAX_DELAY_MILLIS }) {
            "Follow-up delays must be between 0 and ${SellerOutreachDefaults.MAX_DELAY_MILLIS} milliseconds."
        }
    }

    private suspend fun tryAi(
        request: ComposeDraftRequest,
        personalization: PersonalizationFields,
        template: RenderedOutreachMessage
    ): AiAttempt {
        val generator = textGenerator ?: return AiAttempt.Fallback(AiFallbackReason.GENERATOR_ABSENT)
        val prompt = promptFor(personalization, request.channel, request.purpose, request.tone)
        val raw = try {
            generator.generate(prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AiAttempt.Fallback(AiFallbackReason.GENERATOR_FAILED)
        }
        if (raw.isNullOrBlank()) return AiAttempt.Fallback(AiFallbackReason.GENERATOR_RETURNED_EMPTY)
        if (raw.length > OutreachLimits.MAX_AI_RESPONSE_CHARS) {
            return AiAttempt.Fallback(AiFallbackReason.OUTPUT_REJECTED)
        }
        val invisibleStripped = OutreachTextSanitizer.stripInvisible(raw)
        if (OutreachTextSanitizer.containsCredential(invisibleStripped)) {
            return AiAttempt.Fallback(AiFallbackReason.CREDENTIALS_DETECTED)
        }
        if (OutreachTextSanitizer.containsInjection(invisibleStripped)) {
            return AiAttempt.Fallback(AiFallbackReason.PROMPT_INJECTION)
        }
        val parsed = OutreachAiResponseParser.parse(raw, request.channel)
        if (parsed is AiParseResult.Rejected) return AiAttempt.Fallback(parsed.reason)
        val generated = (parsed as AiParseResult.Accepted).text
        val rendered = renderAcceptedAi(generated, template, request)
        if (rendered is AiRender.Rejected) return AiAttempt.Fallback(rendered.reason)
        val message = (rendered as AiRender.Ready).message
        val findings = guard.inspect(sellerFacing(message), GuardContext.from(personalization))
        val reason = guard.primaryReason(findings)
        return if (reason == null) AiAttempt.Accepted(message) else AiAttempt.Fallback(reason)
    }

    private fun renderAcceptedAi(
        generated: AiGeneratedText,
        template: RenderedOutreachMessage,
        request: ComposeDraftRequest
    ): AiRender {
        val subject = generated.subject?.let { OutreachTextSanitizer.clean(it, OutreachLimits.EMAIL_SUBJECT, false) }
        val body = OutreachTextSanitizer.clean(
            generated.body,
            bodyCap(request.channel),
            request.channel != OutreachChannel.SMS
        )
        val script = generated.callScript?.let { OutreachTextSanitizer.clean(it, OutreachLimits.CALL_SCRIPT, true) }
        val cleaned = listOfNotNull(subject, body, script)
        cleaned.firstNotNullOfOrNull { rejectionReason(it) }?.let { return AiRender.Rejected(it) }
        return when (request.channel) {
            OutreachChannel.EMAIL -> {
                val prose = body.value ?: return AiRender.Rejected(AiFallbackReason.GENERATOR_RETURNED_EMPTY)
                val withOptOut = appendEmailOptOut(prose)
                if (withOptOut.length > OutreachLimits.EMAIL_BODY) {
                    return AiRender.Rejected(AiFallbackReason.OUTPUT_REJECTED)
                }
                val resolvedSubject = subject?.value ?: template.subject
                if (resolvedSubject != null && resolvedSubject.length > OutreachLimits.EMAIL_SUBJECT) {
                    return AiRender.Rejected(AiFallbackReason.OUTPUT_REJECTED)
                }
                AiRender.Ready(RenderedOutreachMessage(resolvedSubject, withOptOut, null))
            }
            OutreachChannel.SMS -> {
                val prose = body.value?.replace(Regex("\\s+"), " ")?.trim()
                    ?: return AiRender.Rejected(AiFallbackReason.GENERATOR_RETURNED_EMPTY)
                val message = if (prose.contains("STOP", ignoreCase = true)) {
                    if (prose.length > OutreachLimits.SMS_BODY) {
                        return AiRender.Rejected(AiFallbackReason.OUTPUT_REJECTED)
                    }
                    prose
                } else {
                    val withFooter = prose + OutreachLimits.SMS_OPT_OUT
                    if (withFooter.length > OutreachLimits.SMS_BODY) {
                        return AiRender.Rejected(AiFallbackReason.OUTPUT_REJECTED)
                    }
                    withFooter
                }
                AiRender.Ready(RenderedOutreachMessage(null, message, null))
            }
            OutreachChannel.MANUAL_CALL -> {
                val prose = script?.value ?: body.value
                    ?: return AiRender.Rejected(AiFallbackReason.GENERATOR_RETURNED_EMPTY)
                val withLimits = appendCallLimits(prose)
                if (withLimits.length > OutreachLimits.CALL_SCRIPT) {
                    return AiRender.Rejected(AiFallbackReason.OUTPUT_REJECTED)
                }
                AiRender.Ready(RenderedOutreachMessage(subject = null, body = template.body, callScript = withLimits))
            }
        }
    }

    private fun rejectionReason(cleaned: OutreachTextSanitizer.Cleaned): AiFallbackReason? = when {
        cleaned.credentialsDetected -> AiFallbackReason.CREDENTIALS_DETECTED
        cleaned.injectionDetected -> AiFallbackReason.PROMPT_INJECTION
        cleaned.linkDetected -> AiFallbackReason.UNSUPPORTED_CLAIM
        else -> null
    }

    private fun promptFor(
        personalization: PersonalizationFields,
        channel: OutreachChannel,
        purpose: OutreachPurpose,
        tone: OutreachTone
    ): OutreachTextPrompt = OutreachTextPrompt(
        channel = channel,
        purpose = purpose,
        tone = tone,
        sellerDisplayName = personalization.sellerDisplayName,
        senderName = personalization.senderName.ifBlank { "Sender" },
        senderCompany = personalization.senderCompany,
        propertyAddress = personalization.propertyAddress,
        propertyCity = personalization.propertyCity,
        propertyState = personalization.propertyState,
        allowedFacts = personalization.verifiedFacts.map { fact ->
            "${fact.key.name.lowercase()}: ${fact.value}"
        },
        sellerStatedNote = personalization.sellerStatedNote,
        maxBodyCharacters = bodyCap(channel)
    )

    private fun draftFrom(
        request: ComposeDraftRequest,
        personalization: PersonalizationFields,
        createdAt: Long,
        message: RenderedOutreachMessage,
        source: DraftTextSource,
        fallback: AiFallbackReason?
    ): SellerMessageDraft = SellerMessageDraft(
        id = ids.next("draft"),
        sequenceId = request.sequenceId,
        stepIndex = request.stepIndex,
        channel = request.channel,
        purpose = request.purpose,
        tone = request.tone,
        subject = message.subject,
        body = message.body,
        callScript = message.callScript,
        textSource = source,
        fallbackReason = fallback,
        personalization = personalization,
        complianceNotes = OutreachCompliance.notesFor(request.channel, blocked = false),
        createdAtEpochMillis = createdAt
    )

    private fun blockedDraft(
        request: ComposeDraftRequest,
        personalization: PersonalizationFields,
        createdAt: Long,
        reason: String
    ): SellerMessageDraft = SellerMessageDraft(
        id = ids.next("draft"),
        sequenceId = request.sequenceId,
        stepIndex = request.stepIndex,
        channel = request.channel,
        purpose = request.purpose,
        tone = request.tone,
        subject = null,
        body = "",
        callScript = null,
        textSource = DraftTextSource.DETERMINISTIC_TEMPLATE,
        fallbackReason = null,
        personalization = personalization,
        complianceNotes = OutreachCompliance.notesFor(request.channel, blocked = true),
        createdAtEpochMillis = createdAt,
        blocked = true,
        blockReasons = listOf(reason)
    )

    private fun sequenceName(raw: String): String {
        val cleaned = OutreachTextSanitizer.clean(raw, 80, multiline = false)
        return if (cleaned.rejected || cleaned.value == null) SellerOutreachDefaults.SEQUENCE_NAME else cleaned.value
    }

    private fun sellerFacing(message: RenderedOutreachMessage): String =
        listOfNotNull(message.subject, message.body, message.callScript)
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private fun bodyCap(channel: OutreachChannel): Int = when (channel) {
        OutreachChannel.EMAIL -> OutreachLimits.EMAIL_BODY
        OutreachChannel.SMS -> OutreachLimits.SMS_BODY
        OutreachChannel.MANUAL_CALL -> OutreachLimits.CALL_SCRIPT
    }

    private fun appendEmailOptOut(body: String): String =
        if (body.contains("prefer not to hear from me", ignoreCase = true)) {
            body
        } else {
            body.trimEnd() + "\n\n" + OutreachLimits.EMAIL_OPT_OUT
        }

    private fun appendCallLimits(script: String): String =
        if (script.contains("Do not guess personal circumstances", ignoreCase = true)) {
            script
        } else {
            script.trimEnd() + "\nLimits: Do not guess personal circumstances. Do not state dollar amounts. Do not add property details beyond the supplied record."
        }

    private fun upsertDraft(workflow: SellerOutreachWorkflow, draft: SellerMessageDraft): SellerOutreachWorkflow {
        val others = workflow.drafts.filterNot { it.sequenceId == draft.sequenceId && it.stepIndex == draft.stepIndex }
        return workflow.copy(drafts = others + draft)
    }

    private fun replaceFollowUp(
        workflow: SellerOutreachWorkflow,
        followUpId: String,
        transform: (ScheduledFollowUp) -> ScheduledFollowUp
    ): SellerOutreachWorkflow = workflow.copy(
        scheduledFollowUps = workflow.scheduledFollowUps.map { item ->
            if (item.id == followUpId) transform(item) else item
        }
    )

    private fun deriveStatus(items: List<ScheduledFollowUp>, current: SequenceStatus): SequenceStatus {
        if (current == SequenceStatus.OPTED_OUT || current == SequenceStatus.CANCELLED) return current
        if (items.any { it.status.isOpen }) return SequenceStatus.ACTIVE
        return if (items.any { it.status == ScheduledFollowUpStatus.COMPLETED }) {
            SequenceStatus.COMPLETED
        } else {
            SequenceStatus.CANCELLED
        }
    }

    private sealed interface AiAttempt {
        data class Accepted(val message: RenderedOutreachMessage) : AiAttempt
        data class Fallback(val reason: AiFallbackReason) : AiAttempt
    }

    private sealed interface AiRender {
        data class Ready(val message: RenderedOutreachMessage) : AiRender
        data class Rejected(val reason: AiFallbackReason) : AiRender
    }
}
