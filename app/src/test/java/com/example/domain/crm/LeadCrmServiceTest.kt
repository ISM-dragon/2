package com.example.domain.crm

import com.example.domain.crm.CrmFixtures.ACTOR
import com.example.domain.crm.CrmFixtures.LEAD_ID
import com.example.domain.crm.CrmFixtures.OFFER_ID
import com.example.domain.crm.CrmFixtures.PHONE
import com.example.domain.crm.CrmFixtures.PROPERTY_ID
import com.example.domain.crm.CrmFixtures.SELLER_ID
import com.example.domain.crm.CrmFixtures.T0
import com.example.domain.crm.CrmFixtures.T1
import com.example.domain.crm.CrmFixtures.address
import com.example.domain.crm.CrmFixtures.condition
import com.example.domain.crm.CrmFixtures.contract
import com.example.domain.crm.CrmFixtures.crmLead
import com.example.domain.crm.CrmFixtures.motivationSignal
import com.example.domain.crm.CrmFixtures.propertyLink
import com.example.domain.crm.CrmFixtures.seller
import com.example.domain.crm.CrmFixtures.source
import com.example.domain.crm.CrmFixtures.timeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service is the seam this branch promises to the integration branch: every write is
 * load → pure domain operation → validate → persist → typed result, with no clock and no network.
 */
class LeadCrmServiceTest {

    private val day = CrmTime.MILLIS_PER_DAY

    private fun service(
        store: LeadStore = InMemoryLeadStore(),
        policy: LeadCrmPolicy = LeadCrmPolicy.DEFAULT,
        ids: LeadIdFactory = LeadIdFactory.DEFAULT
    ) = LeadCrmService(store, policy, ids)

    private fun request(leadId: String? = LEAD_ID) = NewLeadRequest(
        displayName = "Whitfield — 2450 Oak St",
        source = source(),
        sellers = listOf(seller()),
        propertyLinks = listOf(propertyLink()),
        motivationSignals = listOf(motivationSignal()),
        condition = condition(),
        timeline = timeline(),
        tags = listOf("Tax Delinquent", "Absentee Owner"),
        assignedTo = ACTOR,
        leadId = leadId
    )

    private fun draft(
        at: Long = T0,
        direction: CommunicationDirection = CommunicationDirection.OUTBOUND,
        outcome: CommunicationOutcome = CommunicationOutcome.CONNECTED,
        channel: CommunicationChannel = CommunicationChannel.CALL,
        durationSeconds: Int? = 300
    ) = LeadCommunicationDraft(
        channel = channel,
        direction = direction,
        outcome = outcome,
        occurredAtEpochMillis = at,
        summary = "spoke with the seller",
        durationSeconds = durationSeconds,
        sellerId = SELLER_ID
    )

    // ── Capture ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `capturing a lead normalizes tags, schedules the cadence and assesses it`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val result = service(store).createLead(request(leadId = null), T0, ACTOR)
        assertTrue(result is LeadMutationResult.Applied)
        result as LeadMutationResult.Applied

        assertEquals(LeadPipelineStatus.NEW, result.lead.pipelineStatus)
        assertEquals(setOf("absentee-owner", "tax-delinquent"), result.lead.tags)
        assertNotNull("capture schedules the first touch", result.lead.nextFollowUp)
        assertEquals(T0 + 3 * day, result.lead.nextFollowUp!!.dueAtEpochMillis)
        assertEquals(LeadQualificationState.QUALIFIED, result.lead.qualificationState)
        assertEquals("no conversation has happened yet: reachability scores 4/12", 68, result.lead.qualification!!.score)
        assertEquals(LeadPriority.NORMAL, result.lead.priority)
        assertEquals("the assessment is an audited write", 1, result.lead.audit.revision)
        assertEquals(1, store.count())
        assertTrue(result.issues.none { it.severity == LeadValidationSeverity.ERROR })
        assertTrue(result.lead.id.startsWith("LEAD-"))
    }

    @Test
    fun `an injected id factory keeps creation reproducible and the caller's id wins`(): Unit = runBlocking<Unit> {
        val deterministic = LeadIdFactory { java.util.UUID.fromString("12345678-1234-5678-1234-567812345678") }
        val created = service(ids = deterministic).createLead(request(leadId = null), T0, ACTOR) as LeadMutationResult.Applied
        assertEquals("LEAD-123456781234", created.lead.id)

        val pinned = service().createLead(request(leadId = "LEAD-MINE"), T0, ACTOR) as LeadMutationResult.Applied
        assertEquals("LEAD-MINE", pinned.lead.id)
    }

    @Test
    fun `a request without an actor is refused before anything is stored`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service(store).createLead(request(), T0, "  ") }
        }
        assertEquals(0, store.count())
    }

    // ── Evidence ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an outbound attempt moves a new lead to CONTACTED and re-arms the cadence`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)

        val logged = crm.recordCommunication(LEAD_ID, draft(at = T0 + 1, outcome = CommunicationOutcome.LEFT_VOICEMAIL, durationSeconds = null), T0 + 1, ACTOR)
        logged as LeadMutationResult.Applied
        assertEquals(LeadPipelineStatus.CONTACTED, logged.lead.pipelineStatus)
        assertEquals(LeadTransitionReason.OUTREACH_LOGGED, logged.lead.transitions.last().reason)
        assertEquals(1, logged.lead.communications.size)
        assertEquals("the new stage re-arms the cadence (CONTACTED = 2 days)", T0 + 1 + 2 * day, logged.lead.nextFollowUp!!.dueAtEpochMillis)
        assertEquals(logged.lead, store.findById(LEAD_ID))
    }

    @Test
    fun `a seller reply advances the lead without an explicit move`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)

        val replied = crm.recordCommunication(
            LEAD_ID,
            draft(at = T0 + 1, direction = CommunicationDirection.INBOUND, outcome = CommunicationOutcome.SELLER_INTERESTED, durationSeconds = null),
            T0 + 1,
            ACTOR
        ) as LeadMutationResult.Applied
        assertEquals(LeadPipelineStatus.RESPONDED, replied.lead.pipelineStatus)
        assertTrue(replied.lead.hasSellerResponse)
        assertEquals(LeadTransitionReason.SELLER_RESPONDED, replied.lead.transitions.last().reason)
    }

    @Test
    fun `a do-not-contact request freezes the lead and stops any further stage move`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)

        val frozen = crm.recordCommunication(
            LEAD_ID,
            draft(at = T0 + 1, direction = CommunicationDirection.INBOUND, outcome = CommunicationOutcome.DO_NOT_CONTACT_REQUESTED, durationSeconds = null),
            T0 + 1,
            ACTOR
        ) as LeadMutationResult.Applied
        assertTrue(frozen.lead.isContactFrozen)
        assertTrue(frozen.issues.any { it.code == LeadValidationCodes.CONTACT_FROZEN_BUT_OPEN })

        val blocked = crm.moveTo(LEAD_ID, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0 + 2, ACTOR)
        assertTrue("a frozen lead cannot enter an outreach stage", blocked is LeadMoveResult.Blocked)
        assertEquals(LeadPipelineStatus.NEW, store.findById(LEAD_ID)!!.pipelineStatus)
    }

    @Test
    fun `operator evidence is stored with an audited revision`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        val created = crm.createLead(request(), T0, ACTOR) as LeadMutationResult.Applied
        var revision = created.lead.audit.revision

        val noted = crm.addNote(LEAD_ID, "Left a message with the estate attorney", T0 + 1, ACTOR, pinned = true)
        noted as LeadMutationResult.Applied
        assertEquals(1, noted.lead.notes.size)
        assertTrue(noted.lead.notes.single().pinned)
        assertTrue(noted.lead.audit.revision > revision)
        revision = noted.lead.audit.revision

        val tagged = crm.addTag(LEAD_ID, "  Probate ", T0 + 2, ACTOR) as LeadMutationResult.Applied
        assertTrue(tagged.lead.hasTag("probate"))
        val untagged = crm.removeTag(LEAD_ID, "probate", T0 + 3, ACTOR) as LeadMutationResult.Applied
        assertFalse(untagged.lead.hasTag("probate"))
        assertTrue(untagged.lead.audit.revision > tagged.lead.audit.revision)

        val assigned = crm.assign(LEAD_ID, "rep.blake", T0 + 4, ACTOR) as LeadMutationResult.Applied
        assertEquals("rep.blake", assigned.lead.assignedTo)
        assertEquals("rep.blake", store.findById(LEAD_ID)!!.assignedTo)

        val assessed = crm.recordCondition(LEAD_ID, condition(indicators = setOf(ConditionIndicator.FOUNDATION_ISSUES)), T0 + 5, ACTOR) as LeadMutationResult.Applied
        assertEquals(PropertyCondition.MAJOR_REHAB, assessed.lead.condition!!.condition)

        val timed = crm.recordTimeline(LEAD_ID, daysToClose = 7, source = TimelineSource.SELLER_STATED, atEpochMillis = T0 + 6, actor = ACTOR) as LeadMutationResult.Applied
        assertEquals(SellerTimeline.IMMEDIATE, timed.lead.timeline.timeline)

        val signaled = crm.addMotivationSignal(
            leadId = LEAD_ID,
            kind = MotivationSignalKind.PROBATE_OR_ESTATE,
            strength = MotivationStrength.MODERATE,
            sourceKind = MotivationSourceKind.SELLER_STATED,
            observedAtEpochMillis = T0 + 6,
            atEpochMillis = T0 + 7,
            actor = ACTOR,
            detail = "estate attorney confirmed the sale"
        ) as LeadMutationResult.Applied
        assertEquals(2, signaled.lead.motivationSignals.size)

        val linked = crm.addPropertyLink(LEAD_ID, propertyLink(propertyId = "PROP-2", role = PropertyLinkRole.PORTFOLIO), T0 + 8, ACTOR) as LeadMutationResult.Applied
        assertEquals(2, linked.lead.propertyLinks.size)

        val updatedSeller = crm.updateSeller(LEAD_ID, seller(name = "Dana B. Whitfield"), T0 + 9, ACTOR) as LeadMutationResult.Applied
        assertEquals("Dana B. Whitfield", updatedSeller.lead.primarySeller!!.fullName)
    }

    @Test
    fun `an invalid note or tag is refused by the aggregate, not silently saved`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { crm.addNote(LEAD_ID, "   ", T0 + 1, ACTOR) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                crm.addMotivationSignal(
                    leadId = LEAD_ID,
                    kind = MotivationSignalKind.OTHER,
                    strength = MotivationStrength.WEAK,
                    sourceKind = MotivationSourceKind.AGENT_NOTE,
                    observedAtEpochMillis = T0 + 1,
                    atEpochMillis = T0 + 1,
                    actor = ACTOR
                )
            }
        }
    }

    // ── Pipeline ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `moveTo applies a gated move and persists it`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)

        val moved = crm.moveTo(LEAD_ID, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0 + 1, ACTOR, detail = "postcard lane 4")
        moved as LeadMoveResult.Applied
        assertEquals(LeadPipelineStatus.CONTACTED, moved.lead.pipelineStatus)
        assertEquals("postcard lane 4", moved.transition.detail)
        assertEquals(LeadPipelineStatus.CONTACTED, store.findById(LEAD_ID)!!.pipelineStatus)
        assertEquals(1, moved.lead.transitions.size)

        val again = crm.moveTo(LEAD_ID, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0 + 2, ACTOR)
        assertTrue(again is LeadMoveResult.NoOp)

        val illegal = crm.moveTo(LEAD_ID, LeadPipelineStatus.CLOSED, LeadTransitionReason.DEAL_CLOSED, T0 + 3, ACTOR)
        assertTrue(illegal is LeadMoveResult.Illegal)

        val blocked = crm.moveTo(LEAD_ID, LeadPipelineStatus.RESPONDED, LeadTransitionReason.SELLER_RESPONDED, T0 + 4, ACTOR)
        assertTrue("RESPONDED needs a logged seller response", blocked is LeadMoveResult.Blocked)
        assertEquals(LeadPipelineStatus.CONTACTED, store.findById(LEAD_ID)!!.pipelineStatus)
    }

    @Test
    fun `registering an offer records the bridge move and the contract completes it`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)

        crm.recordCommunication(LEAD_ID, draft(at = T0 + 1), T0 + 1, ACTOR)
        crm.recordCommunication(LEAD_ID, draft(at = T0 + 2, direction = CommunicationDirection.INBOUND, outcome = CommunicationOutcome.SELLER_REPLIED, durationSeconds = null), T0 + 2, ACTOR)
        crm.moveTo(LEAD_ID, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0 + 3, ACTOR)

        val draftOffer = crm.registerOffer(LEAD_ID, OFFER_ID, 168_000.0, LeadOfferBridge.STATUS_DRAFT, T0 + 4, ACTOR) as LeadMutationResult.Applied
        assertEquals("a draft offer does not move a lead", LeadPipelineStatus.NEGOTIATING, draftOffer.lead.pipelineStatus)
        assertEquals(1, draftOffer.lead.offerReferences.size)

        val sent = crm.registerOffer(LEAD_ID, OFFER_ID, 168_000.0, LeadOfferBridge.STATUS_SENT, T0 + 5, ACTOR) as LeadMutationResult.Applied
        assertEquals(LeadPipelineStatus.OFFER_SENT, sent.lead.pipelineStatus)
        assertEquals(LeadTransitionReason.OFFER_SUBMITTED, sent.lead.transitions.last().reason)

        // The seller signs: the bridge commits the deal once a contract is referenced.
        val signed = crm.registerOffer(LEAD_ID, OFFER_ID, 168_000.0, LeadOfferBridge.STATUS_SIGNED, T0 + 6, ACTOR) as LeadMutationResult.Applied
        assertEquals("the offer is accepted but there is no contract yet", LeadPipelineStatus.OFFER_SENT, signed.lead.pipelineStatus)
        crm.registerContract(LEAD_ID, contract(), T0 + 7, ACTOR)

        val committed = crm.moveTo(LEAD_ID, LeadPipelineStatus.UNDER_CONTRACT, LeadTransitionReason.OFFER_ACCEPTED, T0 + 8, ACTOR)
        assertTrue(committed is LeadMoveResult.Applied)
        assertEquals(LeadPipelineStatus.UNDER_CONTRACT, store.findById(LEAD_ID)!!.pipelineStatus)
    }

    // ── Qualification ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `qualification can be reassessed, decayed and overridden, and never writes raw`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)

        val unchanged = crm.reassessQualification(LEAD_ID, T0 + 1, ACTOR)
        assertTrue(unchanged is LeadQualificationOutcome.Unchanged)

        val decayed = crm.decayStaleQualification(LEAD_ID, T0 + 200 * day, ACTOR)
        assertTrue("a stale assessment degrades", decayed is LeadQualificationOutcome.Applied)
        decayed as LeadQualificationOutcome.Applied
        assertEquals(LeadQualificationState.WARM, decayed.assessment.state)
        assertEquals(LeadQualificationState.WARM, store.findById(LEAD_ID)!!.qualificationState)
        assertTrue(
            "the decay is explained",
            decayed.assessment.warnings.any { it.contains("evidence decayed") }
        )

        val fresh = crm.reassessQualification(LEAD_ID, T0 + 201 * day, ACTOR) as LeadQualificationOutcome.Applied
        assertEquals(LeadQualificationState.QUALIFIED, fresh.assessment.state)

        val overridden = crm.overrideQualification(LEAD_ID, LeadQualificationState.WARM, "buy box is full this month", T0 + 202 * day, ACTOR)
        assertTrue(overridden is LeadQualificationOutcome.Applied)
        overridden as LeadQualificationOutcome.Applied
        assertEquals(LeadQualificationReason.MANUAL_OVERRIDE, overridden.transition.reason)
        assertEquals(LeadQualificationState.WARM, store.findById(LEAD_ID)!!.qualificationState)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { crm.overrideQualification(LEAD_ID, LeadQualificationState.WARM, "  ", T0 + 203 * day, ACTOR) }
        }

        assertEquals(LeadQualificationOutcome.NotFound("LEAD-NOPE"), crm.reassessQualification("LEAD-NOPE", T0, ACTOR))
    }

    @Test
    fun `a qualification change that would corrupt the record is refused, and a no-op writes nothing`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)
        crm.recordCommunication(LEAD_ID, draft(at = T0 + 1), T0 + 1, ACTOR)
        crm.recordCommunication(
            LEAD_ID,
            draft(at = T0 + 2, direction = CommunicationDirection.INBOUND, outcome = CommunicationOutcome.SELLER_REPLIED, durationSeconds = null),
            T0 + 2,
            ACTOR
        )
        crm.moveTo(LEAD_ID, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0 + 3, ACTOR)
        val before = store.findById(LEAD_ID)!!

        val rejected = crm.overrideQualification(LEAD_ID, LeadQualificationState.DISQUALIFIED, "seller said no", T0 + 4, ACTOR)
        assertTrue(rejected is LeadQualificationOutcome.Rejected)
        rejected as LeadQualificationOutcome.Rejected
        assertEquals(LeadValidationCodes.QUALIFICATION_DISQUALIFIED_WHILE_ENGAGED, rejected.issues.single().code)
        assertEquals("nothing was written", before, store.findById(LEAD_ID))

        val unchanged = crm.reassessQualification(LEAD_ID, T0 + 5, ACTOR)
        assertTrue(unchanged is LeadQualificationOutcome.Unchanged)
        assertEquals("an unchanged reassessment writes nothing", before.audit.revision, store.findById(LEAD_ID)!!.audit.revision)
    }

    @Test
    fun `the service exposes the derived priority and the validation report`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)

        val suggestion = crm.suggestedPriority(LEAD_ID, T0)!!
        assertEquals(LeadPriority.NORMAL, suggestion.priority)
        assertEquals(48, suggestion.score)
        assertEquals(68, crm.find(LEAD_ID)!!.qualification!!.score)
        assertNull(crm.suggestedPriority("LEAD-NOPE", T0))

        val report = crm.validate(LEAD_ID, T0)!!
        assertTrue(report.none { it.severity == LeadValidationSeverity.ERROR })
        assertNull(crm.validate("LEAD-NOPE"))
    }

    // ── Follow-up ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an explicit follow-up can be scheduled, listed when due and completed`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)

        val snoozed = crm.scheduleFollowUp(LEAD_ID, T0 + day, ACTOR, note = "call after 5pm", daysFromNow = 10)
        snoozed as LeadMutationResult.Applied
        assertEquals(T0 + 11 * day, snoozed.lead.nextFollowUp!!.dueAtEpochMillis)
        assertEquals("call after 5pm", snoozed.lead.nextFollowUp!!.note)

        assertTrue(crm.dueFollowUps(T0 + 2 * day).isEmpty())
        assertEquals(listOf(LEAD_ID), crm.dueFollowUps(T0 + 12 * day).map { it.id })
        assertEquals(listOf(LEAD_ID), crm.openPipeline().map { it.id })

        val completed = crm.completeFollowUp(LEAD_ID, T0 + 12 * day, ACTOR, summary = "seller wants to negotiate price")
        completed as LeadMutationResult.Applied
        assertTrue(completed.lead.nextFollowUp!!.isCompleted.not())
        assertTrue(completed.lead.notes.any { it.isSystemGenerated })
        assertEquals(T0 + 12 * day + 3 * day, completed.lead.nextFollowUp!!.dueAtEpochMillis)
    }

    @Test
    fun `a terminal lead takes no follow-up and no cadence means a refusal, not a crash`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)
        val closed = crm.moveTo(LEAD_ID, LeadPipelineStatus.LOST, LeadTransitionReason.SELLER_NOT_INTERESTED, T1, ACTOR)
        closed as LeadMoveResult.Applied
        assertNull("a lost lead owes nothing", closed.lead.nextFollowUp)

        val rejected = crm.scheduleFollowUp(LEAD_ID, T1, ACTOR)
        rejected as LeadMutationResult.Rejected
        assertEquals(LeadValidationCodes.LEAD_TERMINAL, rejected.issues.single().code)
    }

    @Test
    fun `completing a follow-up that does not exist is refused with a stable code`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store, policy = LeadCrmPolicy(automation = LeadAutomationPolicy(autoScheduleFollowUp = false)))
        crm.createLead(request(), T0, ACTOR)
        val rejected = crm.completeFollowUp(LEAD_ID, T0 + day, ACTOR)
        rejected as LeadMutationResult.Rejected
        assertEquals(LeadValidationCodes.NO_PENDING_FOLLOW_UP, rejected.issues.single().code)
    }

    // ── Skip tracing ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the skip-trace seam is disabled and never writes anything`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        crm.createLead(request(), T0, ACTOR)

        val result = crm.requestSellerContacts(LEAD_ID, SELLER_ID, address())
        assertTrue(result is SkipTraceResult.NotConfigured)
        assertNull("no network lookup happens on this branch", store.findById(LEAD_ID)!!.primarySeller!!.skipTrace)

        val unknown = crm.requestSellerContacts(LEAD_ID, "SELLER-GHOST")
        assertTrue(unknown is SkipTraceResult.Failed)
    }

    // ── Store ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the store lists open leads in priority then longest-waiting order`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        store.save(crmLead(status = LeadPipelineStatus.NEW, leadId = "LEAD-LOW", priority = LeadPriority.LOW))
        store.save(crmLead(status = LeadPipelineStatus.NEW, leadId = "LEAD-HIGH", priority = LeadPriority.HIGH, at = T0 + day))
        store.save(crmLead(status = LeadPipelineStatus.NEW, leadId = "LEAD-URGENT", priority = LeadPriority.URGENT, at = T0 + 2 * day))
        store.save(crmLead(status = LeadPipelineStatus.CLOSED, leadId = "LEAD-DEAD"))

        assertEquals(listOf("LEAD-URGENT", "LEAD-HIGH", "LEAD-LOW"), store.listOpen().map { it.id })
        assertEquals(listOf("LEAD-DEAD"), store.listByStatus(setOf(LeadPipelineStatus.CLOSED)).map { it.id })
        assertEquals(4, store.count())

        // The lookup questions the CRM actually asks.
        assertEquals(listOf("LEAD-URGENT"), store.listByPropertyId(PROPERTY_ID).map { it.id }.filter { it == "LEAD-URGENT" })
        assertTrue(store.listByTag("TAX-DELINQUENT").isNotEmpty())
        assertTrue(store.listBySellerId(SELLER_ID).isNotEmpty())
        assertTrue(store.findBySourceDeduplicationKey(CrmFixtures.source().deduplicationKey()).isNotEmpty())
        assertTrue(store.listByStatus(emptySet()).isEmpty())
        assertTrue(store.listOpen(limit = 0).isEmpty())
    }

    @Test
    fun `the store answers follow-up queries and retains terminal leads last`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val live = crmLead(status = LeadPipelineStatus.NEW, at = T0)
            .withFollowUp(LeadFollowUpPlanner.schedule(LeadPipelineStatus.NEW, T0, ACTOR)!!, ACTOR, T0)
        store.save(live)
        store.save(crmLead(status = LeadPipelineStatus.CLOSED, leadId = "LEAD-DEAD", at = T0))

        assertTrue(store.listFollowUpsDueAtOrBefore(T0).isEmpty())
        assertEquals(listOf(LEAD_ID), store.listFollowUpsDueAtOrBefore(T0 + 4 * day).map { it.id })
        assertEquals("a terminal lead never shows up in the call list", 1, store.listFollowUpsDueAtOrBefore(T0 + 4 * day).size)

        assertEquals(1, store.deleteTerminalOlderThan(T0 + day))
        assertNull(store.findById("LEAD-DEAD"))
        assertNotNull(store.findById(LEAD_ID))
        assertEquals(1, store.deleteByIds(setOf(LEAD_ID, "LEAD-GHOST")))
        assertEquals(0, store.count())
    }

    @Test
    fun `bounded storage evicts terminal work before open deals`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore(maxLeads = 2, retainTerminalLeads = 1)
        store.save(crmLead(status = LeadPipelineStatus.CLOSED, leadId = "LEAD-DEAD", at = T0))
        store.save(crmLead(status = LeadPipelineStatus.NEW, leadId = "LEAD-LIVE", at = T0 + day))
        store.save(crmLead(status = LeadPipelineStatus.NEW, leadId = "LEAD-LIVE-2", at = T0 + 2 * day))
        assertEquals(2, store.count())
        assertNull("the dead deal made room", store.findById("LEAD-DEAD"))
        assertNotNull(store.findById("LEAD-LIVE-2"))

        assertThrows(IllegalArgumentException::class.java) { InMemoryLeadStore(maxLeads = 0) }
        assertThrows(IllegalArgumentException::class.java) { InMemoryLeadStore(maxLeads = 5, retainTerminalLeads = 6) }
    }

    // ── Automation switches ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a conservative deployment can switch the automation off and still work by hand`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val manual = LeadCrmPolicy(
            automation = LeadAutomationPolicy(
                autoScheduleFollowUp = false,
                autoAdvanceToContactedOnOutboundAttempt = false,
                autoAdvanceToRespondedOnSellerResponse = false,
                autoReassessOnEvidenceChange = false,
                autoRefreshPriority = false
            )
        )
        val crm = service(store, manual)
        val created = crm.createLead(request(), T0, ACTOR) as LeadMutationResult.Applied
        assertNull(created.lead.nextFollowUp)
        assertNull(created.lead.qualification)
        assertEquals(LeadPriority.NORMAL, created.lead.priority)

        val logged = crm.recordCommunication(LEAD_ID, draft(at = T0 + 1), T0 + 1, ACTOR) as LeadMutationResult.Applied
        assertEquals("no automatic advance", LeadPipelineStatus.NEW, logged.lead.pipelineStatus)

        val moved = crm.moveTo(LEAD_ID, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0 + 2, ACTOR)
        assertTrue("the gate still works", moved is LeadMoveResult.Applied)
    }

    @Test
    fun `an inconsistent write is refused instead of stored`(): Unit = runBlocking<Unit> {
        val store = InMemoryLeadStore()
        val crm = service(store)
        // The lead is already in a committed stage without a contract: the validator (not just the
        // aggregate) has to stop further writes from cementing the inconsistency.
        store.save(crmLead(status = LeadPipelineStatus.UNDER_CONTRACT))
        val rejected = crm.addTag(LEAD_ID, "vacant", T0 + 1, ACTOR)
        assertTrue("a corrupt record must not be written through", rejected is LeadMutationResult.Rejected)
        assertEquals("the stored lead is untouched", setOf("absentee-owner", "tax-delinquent"), store.findById(LEAD_ID)!!.tags)
    }
}
