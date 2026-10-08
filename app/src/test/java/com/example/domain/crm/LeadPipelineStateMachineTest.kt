package com.example.domain.crm

import com.example.domain.crm.CrmFixtures.ACTOR
import com.example.domain.crm.CrmFixtures.T0
import com.example.domain.crm.CrmFixtures.T1
import com.example.domain.crm.CrmFixtures.assess
import com.example.domain.crm.CrmFixtures.contract
import com.example.domain.crm.CrmFixtures.crmLead
import com.example.domain.crm.CrmFixtures.offerReference
import com.example.domain.crm.CrmFixtures.propertyLink
import com.example.domain.crm.CrmFixtures.qualifiedLead
import com.example.domain.crm.CrmFixtures.seller
import com.example.domain.crm.CrmFixtures.source
import com.example.domain.crm.CrmFixtures.timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeadPipelineStateMachineTest {

    // ── Topology ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every status defines its allowed targets and the table is a closed graph`() {
        LeadPipelineStatus.PIPELINE_ORDER.forEach { status ->
            LeadPipelineStateMachine.allowedTargets(status).forEach { target ->
                assertTrue(
                    "$status → $target must be in the pipeline order",
                    target in LeadPipelineStatus.PIPELINE_ORDER
                )
                if (target != status) {
                    assertTrue(
                        "$status → $target must accept at least one reason",
                        LeadPipelineStateMachine.allowedReasons(status, target).isNotEmpty()
                    )
                }
            }
        }
        assertEquals(
            "CLOSED is terminal and never moves",
            emptySet<LeadPipelineStatus>(),
            LeadPipelineStateMachine.allowedTargets(LeadPipelineStatus.CLOSED)
        )
    }

    @Test
    fun `forward path is allowed and shortcuts are not`() {
        val forward = listOf(
            LeadPipelineStatus.NEW to LeadPipelineStatus.CONTACTED,
            LeadPipelineStatus.CONTACTED to LeadPipelineStatus.RESPONDED,
            LeadPipelineStatus.RESPONDED to LeadPipelineStatus.NEGOTIATING,
            LeadPipelineStatus.NEGOTIATING to LeadPipelineStatus.OFFER_SENT,
            LeadPipelineStatus.OFFER_SENT to LeadPipelineStatus.UNDER_CONTRACT,
            LeadPipelineStatus.UNDER_CONTRACT to LeadPipelineStatus.DUE_DILIGENCE,
            LeadPipelineStatus.DUE_DILIGENCE to LeadPipelineStatus.CLOSED
        )
        forward.forEach { (from, to) ->
            assertTrue("$from → $to must be allowed", LeadPipelineStateMachine.canTransition(from, to))
        }

        val forbidden = listOf(
            LeadPipelineStatus.NEW to LeadPipelineStatus.RESPONDED,
            LeadPipelineStatus.NEW to LeadPipelineStatus.OFFER_SENT,
            LeadPipelineStatus.CONTACTED to LeadPipelineStatus.OFFER_SENT,
            LeadPipelineStatus.RESPONDED to LeadPipelineStatus.UNDER_CONTRACT,
            LeadPipelineStatus.NEGOTIATING to LeadPipelineStatus.DUE_DILIGENCE,
            LeadPipelineStatus.OFFER_SENT to LeadPipelineStatus.DUE_DILIGENCE,
            LeadPipelineStatus.OFFER_SENT to LeadPipelineStatus.CLOSED,
            LeadPipelineStatus.UNDER_CONTRACT to LeadPipelineStatus.OFFER_SENT,
            LeadPipelineStatus.CLOSED to LeadPipelineStatus.NEW,
            LeadPipelineStatus.CLOSED to LeadPipelineStatus.LOST
        )
        forbidden.forEach { (from, to) ->
            assertFalse("$from → $to must be forbidden", LeadPipelineStateMachine.canTransition(from, to))
        }
    }

    @Test
    fun `counter offer walks back to negotiation and a lost lead can be re-engaged`() {
        assertTrue(LeadPipelineStateMachine.canTransition(LeadPipelineStatus.OFFER_SENT, LeadPipelineStatus.NEGOTIATING))
        assertTrue(LeadPipelineStateMachine.canTransition(LeadPipelineStatus.DUE_DILIGENCE, LeadPipelineStatus.UNDER_CONTRACT))
        assertTrue(LeadPipelineStateMachine.canTransition(LeadPipelineStatus.LOST, LeadPipelineStatus.NEW))
        assertTrue(LeadPipelineStateMachine.canTransition(LeadPipelineStatus.LOST, LeadPipelineStatus.CONTACTED))
    }

    @Test
    fun `reasons are scoped to their edge`() {
        assertTrue(
            LeadPipelineStateMachine.allowedReasons(LeadPipelineStatus.NEW, LeadPipelineStatus.CONTACTED)
                .contains(LeadTransitionReason.OUTREACH_LOGGED)
        )
        assertFalse(
            "an offer cannot be reported as submitted from NEW",
            LeadPipelineStateMachine.allowedReasons(LeadPipelineStatus.NEW, LeadPipelineStatus.OFFER_SENT)
                .contains(LeadTransitionReason.OFFER_SUBMITTED)
        )
        assertTrue(
            LeadPipelineStateMachine.allowedReasons(LeadPipelineStatus.LOST, LeadPipelineStatus.NEW)
                .contains(LeadTransitionReason.RE_ENGAGED)
        )
        assertFalse(
            LeadPipelineStateMachine.allowedReasons(LeadPipelineStatus.NEW, LeadPipelineStatus.LOST)
                .contains(LeadTransitionReason.DEAL_LOST)
        )
    }

    // ── Apply / refuse ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `same status request is an idempotent no-op`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val result = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.NEW, LeadTransitionReason.LEAD_CREATED, T0, ACTOR
        )
        assertTrue(result is LeadTransitionResult.NoOp)
        assertEquals(0, (result as LeadTransitionResult.NoOp).lead.transitions.size)
        assertEquals(lead.audit.revision, result.lead.audit.revision)
    }

    @Test
    fun `illegal topology is refused with a typed result and changes nothing`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val result = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.OFFER_SUBMITTED, T0, ACTOR
        )
        assertTrue(result is LeadTransitionResult.Illegal)
        result as LeadTransitionResult.Illegal
        assertEquals(LeadPipelineStatus.NEW, result.from)
        assertEquals(LeadPipelineStatus.OFFER_SENT, result.to)
    }

    @Test
    fun `a reason that does not justify the edge is refused even when the edge exists`() {
        val lead = qualifiedLead(status = LeadPipelineStatus.NEGOTIATING).withOfferReference(offerReference(), ACTOR, T0)
        val result = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.DEAL_CLOSED, T0, ACTOR
        )
        assertTrue(result is LeadTransitionResult.Illegal)
        assertTrue((result as LeadTransitionResult.Illegal).message.contains("does not justify"))
    }

    @Test
    fun `applied transition records the audit entry and the qualification snapshot`() {
        val lead = qualifiedLead(status = LeadPipelineStatus.RESPONDED)
        val result = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0, ACTOR
        )
        assertTrue(result is LeadTransitionResult.Applied)
        result as LeadTransitionResult.Applied
        assertEquals(LeadPipelineStatus.NEGOTIATING, result.lead.pipelineStatus)
        assertEquals(1, result.lead.transitions.size)
        assertEquals(lead.audit.revision + 1, result.lead.audit.revision)
        assertEquals(ACTOR, result.lead.audit.updatedBy)
        assertEquals(LeadQualificationState.QUALIFIED, result.transition.qualificationState)
        assertEquals(result.lead.qualification!!.score, result.transition.qualificationScore)
        assertTrue(result.transition.isForward)
        assertTrue(result.transition.describe().contains("RESPONDED → NEGOTIATING"))
    }

    @Test
    fun `entering a terminal status clears the pending follow-up`() {
        val followUp = LeadFollowUpPlanner.schedule(LeadPipelineStatus.NEGOTIATING, T0, ACTOR)!!
        val lead = qualifiedLead(status = LeadPipelineStatus.NEGOTIATING).withFollowUp(followUp, ACTOR, T0)
        val lost = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.LOST, LeadTransitionReason.SELLER_NOT_INTERESTED, T0, ACTOR
        ).lead
        assertEquals(null, lost.nextFollowUp)
        assertEquals(LeadPipelineStatus.LOST, lost.pipelineStatus)
    }

    @Test
    fun `transitionOrThrow fails loudly on a blocked move`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val failure = runCatching {
            LeadPipelineStateMachine.transitionOrThrow(
                lead, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0, ACTOR
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    // ── Evidence gate ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `contracting a lead without a permitted contact path is blocked`() {
        val frozen = crmLead(
            status = LeadPipelineStatus.NEW,
            sellers = listOf(seller(restrictions = SellerContactRestrictions(doNotContactRequested = true)))
        )
        val blocked = LeadPipelineStateMachine.transition(
            frozen, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0, ACTOR
        )
        assertTrue(blocked is LeadTransitionResult.Blocked)
        assertTrue((blocked as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.CONTACT_RESTRICTED })
    }

    @Test
    fun `responded requires a logged seller response`() {
        val lead = crmLead(
            status = LeadPipelineStatus.CONTACTED,
            communications = listOf(
                CrmFixtures.communication(outcome = CommunicationOutcome.NO_ANSWER, durationSeconds = null)
            )
        )
        val blocked = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.RESPONDED, LeadTransitionReason.SELLER_RESPONDED, T0, ACTOR
        )
        assertTrue(blocked is LeadTransitionResult.Blocked)
        assertTrue((blocked as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.NO_SELLER_RESPONSE })
    }

    @Test
    fun `negotiating requires qualification, motivation and a fresh assessment`() {
        val unassessed = crmLead(status = LeadPipelineStatus.RESPONDED)
        val first = LeadPipelineStateMachine.transition(
            unassessed, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0, ACTOR
        )
        assertTrue(first is LeadTransitionResult.Blocked)
        assertTrue(
            (first as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.QUALIFICATION_NOT_ASSESSED }
        )

        val qualifiedButStale = qualifiedLead(status = LeadPipelineStatus.RESPONDED)
        val staleResult = LeadPipelineStateMachine.transition(
            qualifiedButStale, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0 + 400L * CrmTime.MILLIS_PER_DAY, ACTOR
        )
        assertTrue(staleResult is LeadTransitionResult.Blocked)
        assertTrue(
            (staleResult as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.QUALIFICATION_STALE }
        )

        val noMotivation = crmLead(status = LeadPipelineStatus.RESPONDED, motivationSignals = emptyList()).let { assess(it) }
        val motivationResult = LeadPipelineStateMachine.transition(
            noMotivation, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0, ACTOR
        )
        assertTrue(motivationResult is LeadTransitionResult.Blocked)
        assertTrue(
            (motivationResult as LeadTransitionResult.Blocked).blockers
                .any { it.code == CrmBlockerCodes.MOTIVATION_NOT_EVIDENCED }
        )
    }

    @Test
    fun `an offer requires a property, a submitted offer reference and a qualification`() {
        val qualified = qualifiedLead(status = LeadPipelineStatus.NEGOTIATING)

        val noOffer = LeadPipelineStateMachine.transition(
            qualified, LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.OFFER_SUBMITTED, T0, ACTOR
        )
        assertTrue(noOffer is LeadTransitionResult.Blocked)
        assertTrue(
            (noOffer as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.MISSING_OFFER_REFERENCE }
        )

        val noProperty = qualified
            .withOfferReference(offerReference(), ACTOR, T0)
            .copy(propertyLinks = emptyList())
        val propertyResult = LeadPipelineStateMachine.transition(
            noProperty, LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.OFFER_SUBMITTED, T0, ACTOR
        )
        assertTrue(propertyResult is LeadTransitionResult.Blocked)
        assertTrue(
            (propertyResult as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.MISSING_PROPERTY_LINK }
        )

        val noConversation = qualified
            .withOfferReference(offerReference(), ACTOR, T0)
            .copy(communications = emptyList())
        val conversationResult = LeadPipelineStateMachine.transition(
            noConversation, LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.OFFER_SUBMITTED, T0, ACTOR
        )
        assertTrue(conversationResult is LeadTransitionResult.Blocked)
        assertTrue(
            (conversationResult as LeadTransitionResult.Blocked).blockers
                .any { it.code == CrmBlockerCodes.NO_SELLER_CONVERSATION }
        )

        val ready = qualified.withOfferReference(offerReference(), ACTOR, T0)
        val applied = LeadPipelineStateMachine.transition(
            ready, LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.OFFER_SUBMITTED, T0, ACTOR
        )
        assertTrue(applied is LeadTransitionResult.Applied)
        assertEquals(LeadPipelineStatus.OFFER_SENT, (applied as LeadTransitionResult.Applied).lead.pipelineStatus)
    }

    @Test
    fun `commitment requires the contract and due diligence requires its window`() {
        val negotiated = qualifiedLead(status = LeadPipelineStatus.OFFER_SENT)
            .withOfferReference(offerReference(), ACTOR, T0)

        val noContract = LeadPipelineStateMachine.transition(
            negotiated, LeadPipelineStatus.UNDER_CONTRACT, LeadTransitionReason.OFFER_ACCEPTED, T0, ACTOR
        )
        assertTrue(noContract is LeadTransitionResult.Blocked)
        assertTrue((noContract as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.MISSING_CONTRACT })

        val withContract = negotiated.withContract(contract(), ACTOR, T0)
        val underContract = LeadPipelineStateMachine.transition(
            withContract, LeadPipelineStatus.UNDER_CONTRACT, LeadTransitionReason.OFFER_ACCEPTED, T0, ACTOR
        ).lead
        assertEquals(LeadPipelineStatus.UNDER_CONTRACT, underContract.pipelineStatus)

        val noWindow = underContract.withContract(
            contract(dueDiligenceEndsAtEpochMillis = null, closingAtEpochMillis = null),
            ACTOR,
            T0
        )
        val diligence = LeadPipelineStateMachine.transition(
            noWindow, LeadPipelineStatus.DUE_DILIGENCE, LeadTransitionReason.DUE_DILIGENCE_STARTED, T0, ACTOR
        )
        assertTrue(diligence is LeadTransitionResult.Blocked)
        assertTrue(
            (diligence as LeadTransitionResult.Blocked).blockers
                .any { it.code == CrmBlockerCodes.MISSING_DUE_DILIGENCE_WINDOW }
        )
    }

    @Test
    fun `a disqualified lead cannot be re-engaged until it is reassessed`() {
        val lead = crmLead(status = LeadPipelineStatus.LOST, motivationSignals = emptyList())
            .let { assess(it) }
            .let { qualified ->
                // Simulate the "seller said no" disqualifier without a logged communication.
                LeadQualificationEngine.overrideState(
                    qualified, LeadQualificationState.DISQUALIFIED, T0, ACTOR, "seller refused", LeadQualificationPolicy.DEFAULT
                ).lead
            }
        assertEquals(LeadQualificationState.DISQUALIFIED, lead.qualificationState)

        val blocked = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.NEW, LeadTransitionReason.RE_ENGAGED, T1, ACTOR
        )
        assertTrue(blocked is LeadTransitionResult.Blocked)
        assertTrue(
            (blocked as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.QUALIFICATION_DISQUALIFIED }
        )
    }

    @Test
    fun `a lost lead that is not disqualified can be re-engaged`() {
        val lost = qualifiedLead(status = LeadPipelineStatus.NEGOTIATING).let {
            LeadPipelineStateMachine.transition(
                it, LeadPipelineStatus.LOST, LeadTransitionReason.SELLER_NOT_INTERESTED, T0, ACTOR
            ).lead
        }
        val reEngaged = LeadPipelineStateMachine.transition(
            lost, LeadPipelineStatus.NEW, LeadTransitionReason.RE_ENGAGED, T1, ACTOR
        )
        assertTrue(reEngaged is LeadTransitionResult.Applied)
        assertEquals(LeadPipelineStatus.NEW, (reEngaged as LeadTransitionResult.Applied).lead.pipelineStatus)
    }

    @Test
    fun `policy knobs relax the gates knowingly, not by accident`() {
        val relaxed = LeadTransitionPolicy(
            requireActionableQualificationForNegotiation = false,
            requireMotivationForNegotiation = false
        )
        val lead = crmLead(status = LeadPipelineStatus.RESPONDED, motivationSignals = emptyList()).let { assess(it) }
        val applied = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0, ACTOR, relaxed
        )
        assertTrue(applied is LeadTransitionResult.Applied)
    }

    @Test
    fun `stale qualification tolerates the tighter of the two windows`() {
        val assessmentAt = T0
        val lead = crmLead(status = LeadPipelineStatus.RESPONDED, at = assessmentAt).let { assess(it, assessmentAt) }
        val tightened = LeadTransitionPolicy(qualificationFreshnessDays = 5)
        // 10 days later: still inside the qualification window (30d) but outside the transition window.
        val blocked = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED,
            assessmentAt + 10L * CrmTime.MILLIS_PER_DAY, ACTOR, tightened
        )
        assertTrue(blocked is LeadTransitionResult.Blocked)

        val loose = LeadTransitionPolicy(qualificationFreshnessDays = 20)
        val allowed = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED,
            assessmentAt + 10L * CrmTime.MILLIS_PER_DAY, ACTOR, loose
        )
        assertTrue(allowed is LeadTransitionResult.Applied)
    }

    @Test
    fun `transition policy validates its version and freshness window`() {
        assertTrue(
            runCatching { LeadTransitionPolicy(version = " ") }.isFailure
        )
        assertTrue(
            runCatching { LeadTransitionPolicy(qualificationFreshnessDays = 0) }.isFailure
        )
    }

    @Test
    fun `gate is deterministic and does not depend on the property link presence twice`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val first = LeadTransitionGate.evaluate(lead, LeadPipelineStatus.CONTACTED)
        val second = LeadTransitionGate.evaluate(lead, LeadPipelineStatus.CONTACTED)
        assertEquals(first, second)
        assertTrue(first.isEmpty())
    }

    @Test
    fun `source attribution with no context is rejected`() {
        assertTrue(
            runCatching { LeadSourceAttribution(source = LeadSourceKind.COLD_CALL, capturedAtEpochMillis = T0) }.isFailure
        )
        val ok = LeadSourceAttribution(
            source = LeadSourceKind.COLD_CALL,
            listName = "absentee 2026Q1",
            capturedAtEpochMillis = T0
        )
        assertEquals("absentee 2026Q1", ok.listName)
    }

    @Test
    fun `property link snapshot survives without the property row and still prices the deal`() {
        val link = propertyLink()
        assertEquals("ARV 300k - repairs 45k - asking 180k", 75_000.0, link.impliedSpreadUsd()!!, 0.001)
        assertEquals(SellerTimeline.WITHIN_30_DAYS, timeline(30).timeline)
        assertEquals(1_950.0, link.estimatedRentUsdMonthly!!, 0.001)
    }
}
