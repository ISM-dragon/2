package com.example.domain.crm

import com.example.domain.crm.CrmFixtures.ACTOR
import com.example.domain.crm.CrmFixtures.LEAD_ID
import com.example.domain.crm.CrmFixtures.T0
import com.example.domain.crm.CrmFixtures.T1
import com.example.domain.crm.CrmFixtures.assess
import com.example.domain.crm.CrmFixtures.communication
import com.example.domain.crm.CrmFixtures.contract
import com.example.domain.crm.CrmFixtures.crmLead
import com.example.domain.crm.CrmFixtures.motivationSignal
import com.example.domain.crm.CrmFixtures.offerReference
import com.example.domain.crm.CrmFixtures.qualifiedLead
import com.example.domain.crm.CrmFixtures.seller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end lifecycle of a wholesale lead: capture → contact → response → negotiation → offer →
 * contract → diligence → closing, plus the exits (lost, disqualified) and the re-engagement path.
 *
 * These tests are the specification of the pipeline: they assert the lead *cannot* skip work, that every
 * move is audited with its evidence snapshot, and that the resulting record passes validation.
 */
class LeadPipelineLifecycleTest {

    @Test
    fun `happy path walks every stage with evidence and no consistency errors`() {
        val followUp = LeadFollowUpPlanner.schedule(LeadPipelineStatus.NEW, T0, ACTOR)!!
        var lead = crmLead(status = LeadPipelineStatus.NEW, nextFollowUp = followUp)

        // The seller's first conversation is already logged in the fixture; qualification is computed
        // from the same facts the operator sees.
        lead = assess(lead)

        lead = CrmFixtures.move(lead, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0)
        lead = CrmFixtures.move(lead, LeadPipelineStatus.RESPONDED, LeadTransitionReason.SELLER_RESPONDED, T0)
        lead = CrmFixtures.move(lead, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0)

        lead = lead.withOfferReference(offerReference(at = T0 + 1), ACTOR, T0 + 1)
        lead = CrmFixtures.move(lead, LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.OFFER_SUBMITTED, T0 + 1)

        lead = lead.withContract(contract(signedAt = T0 + 2), ACTOR, T0 + 2)
        lead = CrmFixtures.move(lead, LeadPipelineStatus.UNDER_CONTRACT, LeadTransitionReason.OFFER_ACCEPTED, T0 + 2)
        lead = CrmFixtures.move(lead, LeadPipelineStatus.DUE_DILIGENCE, LeadTransitionReason.DUE_DILIGENCE_STARTED, T0 + 2)
        lead = CrmFixtures.move(lead, LeadPipelineStatus.CLOSED, LeadTransitionReason.DEAL_CLOSED, T1)

        assertEquals(LeadPipelineStatus.CLOSED, lead.pipelineStatus)
        assertTrue(lead.isTerminal)
        assertTrue(lead.pipelineStatus.isWon)
        assertNull("a closed lead owes no follow-up", lead.nextFollowUp)

        assertEquals(7, lead.transitions.size)
        assertTrue(lead.transitions.all { it.isForward })
        assertEquals(
            listOf(
                LeadPipelineStatus.CONTACTED,
                LeadPipelineStatus.RESPONDED,
                LeadPipelineStatus.NEGOTIATING,
                LeadPipelineStatus.OFFER_SENT,
                LeadPipelineStatus.UNDER_CONTRACT,
                LeadPipelineStatus.DUE_DILIGENCE,
                LeadPipelineStatus.CLOSED
            ),
            lead.transitions.map { it.to }
        )
        // Revisions are strictly increasing and every move records who did it.
        lead.transitions.zipWithNext { earlier, later ->
            assertTrue(later.revision > earlier.revision)
        }
        assertEquals(ACTOR, lead.audit.updatedBy)
        assertEquals(T1, lead.audit.updatedAtEpochMillis)
        assertEquals(T1, lead.stageEnteredAtEpochMillis)
        assertTrue(lead.timeInStageMillis(T1 + CrmTime.MILLIS_PER_DAY) == CrmTime.MILLIS_PER_DAY)

        val errors = lead.validate(T1).filter { it.severity == LeadValidationSeverity.ERROR }
        assertEquals("a completed lifecycle must not produce consistency errors", emptyList<LeadValidationIssue>(), errors)
        assertFalse(lead.hasValidationErrors())
    }

    @Test
    fun `every open stage can be lost with its own reason`() {
        val exits = listOf(
            LeadPipelineStatus.NEW to LeadTransitionReason.LEAD_DISQUALIFIED,
            LeadPipelineStatus.CONTACTED to LeadTransitionReason.SELLER_NOT_INTERESTED,
            LeadPipelineStatus.RESPONDED to LeadTransitionReason.SELLER_NOT_INTERESTED,
            LeadPipelineStatus.NEGOTIATING to LeadTransitionReason.SELLER_NOT_INTERESTED,
            LeadPipelineStatus.OFFER_SENT to LeadTransitionReason.DEAL_LOST,
            LeadPipelineStatus.UNDER_CONTRACT to LeadTransitionReason.DEAL_LOST,
            LeadPipelineStatus.DUE_DILIGENCE to LeadTransitionReason.DEAL_LOST
        )
        exits.forEach { (stage, reason) ->
            val lead = qualifiedLead(status = stage).withOfferReference(offerReference(), ACTOR, T0)
                .let { if (stage.isCommitted) it.withContract(contract(), ACTOR, T0) else it }
            val result = LeadPipelineStateMachine.transition(lead, LeadPipelineStatus.LOST, reason, T1, ACTOR)
            assertTrue("$stage must be exitable", result is LeadTransitionResult.Applied)
            val lost = (result as LeadTransitionResult.Applied).lead
            assertEquals(LeadPipelineStatus.LOST, lost.pipelineStatus)
            assertTrue(lost.isTerminal)
            assertEquals(reason, lost.transitions.last().reason)
        }
    }

    @Test
    fun `a declined offer can come back through negotiation`() {
        val negotiating = qualifiedLead(status = LeadPipelineStatus.NEGOTIATING)
            .withOfferReference(offerReference(), ACTOR, T0)
        val offerSent = CrmFixtures.move(negotiating, LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.OFFER_SUBMITTED, T0)
        val countered = CrmFixtures.move(offerSent, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.OFFER_COUNTERED, T1)

        assertEquals(LeadPipelineStatus.NEGOTIATING, countered.pipelineStatus)
        val lastTransition = countered.transitions.last()
        assertEquals(LeadTransitionReason.OFFER_COUNTERED, lastTransition.reason)
        assertFalse("a counter-offer walks backwards on purpose", lastTransition.isForward)
        assertTrue(countered.hasBackwardMoves)

        // The revised offer can be submitted again with the same evidence.
        val resubmitted = CrmFixtures.move(
            countered, LeadPipelineStatus.OFFER_SENT, LeadTransitionReason.OFFER_SUBMITTED, T1 + 1
        )
        assertEquals(LeadPipelineStatus.OFFER_SENT, resubmitted.pipelineStatus)
    }

    @Test
    fun `an amended contract after inspection returns to under contract`() {
        val diligence = qualifiedLead(status = LeadPipelineStatus.DUE_DILIGENCE)
            .withOfferReference(offerReference(), ACTOR, T0)
            .withContract(contract(), ACTOR, T0)
        val amended = CrmFixtures.move(
            diligence, LeadPipelineStatus.UNDER_CONTRACT, LeadTransitionReason.CONTRACT_AMENDED, T1
        )
        assertEquals(LeadPipelineStatus.UNDER_CONTRACT, amended.pipelineStatus)
        assertEquals(LeadTransitionReason.CONTRACT_AMENDED, amended.transitions.last().reason)
    }

    @Test
    fun `the pipeline refuses to skip qualification even when the operator insists`() {
        val raw = crmLead(status = LeadPipelineStatus.NEW)
        val blocked = LeadPipelineStateMachine.transition(
            raw, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0, ACTOR
        )
        // Contact itself is allowed; the *skipped* stages are not.
        assertTrue(blocked is LeadTransitionResult.Applied)

        val shortcut = LeadPipelineStateMachine.transition(
            (blocked as LeadTransitionResult.Applied).lead,
            LeadPipelineStatus.UNDER_CONTRACT,
            LeadTransitionReason.OFFER_ACCEPTED,
            T0,
            ACTOR
        )
        assertTrue(shortcut is LeadTransitionResult.Illegal)
    }

    @Test
    fun `a do-not-contact request freezes outreach without deleting the deal`() {
        val lead = assess(crmLead(status = LeadPipelineStatus.NEW))
        val dnc = communication(
            id = "COMM-DNC",
            direction = CommunicationDirection.INBOUND,
            outcome = CommunicationOutcome.DO_NOT_CONTACT_REQUESTED,
            channel = CommunicationChannel.SMS,
            summary = "Seller texted: stop contacting me",
            durationSeconds = null
        )
        val frozen = lead.withCommunication(dnc, ACTOR, T0 + 1)

        assertTrue(frozen.isContactFrozen)
        assertTrue(frozen.sellers.all { it.restrictions.doNotContactRequested })
        assertFalse(frozen.isSellerReachable)

        val blocked = LeadPipelineStateMachine.transition(
            frozen, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0 + 1, ACTOR
        )
        assertTrue(blocked is LeadTransitionResult.Blocked)
        assertTrue((blocked as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.CONTACT_RESTRICTED })

        // The record is still saveable and auditable, and the *loss* exit remains available.
        assertTrue(frozen.validate(T0 + 1).none { it.severity == LeadValidationSeverity.ERROR })
        val lost = LeadPipelineStateMachine.transition(
            frozen, LeadPipelineStatus.LOST, LeadTransitionReason.SELLER_NOT_INTERESTED, T0 + 1, ACTOR
        )
        assertTrue(lost is LeadTransitionResult.Applied)
    }

    @Test
    fun `a lost disqualified lead is reopened by reassessment, not by a status move`() {
        // No motivation evidence and an asking price above the buy box: the lead is disqualified.
        val thin = crmLead(
            status = LeadPipelineStatus.NEW,
            motivationSignals = emptyList(),
            propertyLinks = listOf(CrmFixtures.propertyLink(askingPriceUsd = 292_000.0))
        ).let { assess(it) }
        assertTrue(thin.qualificationState.isDisqualified)

        val lost = CrmFixtures.move(thin, LeadPipelineStatus.LOST, LeadTransitionReason.LEAD_DISQUALIFIED, T0)
        val stillDisqualified = LeadPipelineStateMachine.transition(
            lost, LeadPipelineStatus.NEW, LeadTransitionReason.RE_ENGAGED, T1, ACTOR
        )
        assertTrue(stillDisqualified is LeadTransitionResult.Blocked)

        // New facts (the seller came back motivated, price dropped) lift the disqualifier.
        val revived = lost
            .withPropertyLink(CrmFixtures.propertyLink(askingPriceUsd = 172_000.0, at = T1), ACTOR, T1)
            .withMotivationSignal(motivationSignal(id = "SIG-REVIVED", at = T1), ACTOR, T1)
            .let { assess(it, T1) }
        assertFalse(revived.qualificationState.isDisqualified)
        assertTrue(revived.qualificationHistory.isNotEmpty())

        val reEngaged = LeadPipelineStateMachine.transition(
            revived, LeadPipelineStatus.NEW, LeadTransitionReason.RE_ENGAGED, T1, ACTOR
        )
        assertTrue(reEngaged is LeadTransitionResult.Applied)
        assertEquals(LeadPipelineStatus.NEW, (reEngaged as LeadTransitionResult.Applied).lead.pipelineStatus)
    }

    @Test
    fun `terminal closed lead never moves again`() {
        val closed = qualifiedLead(status = LeadPipelineStatus.CLOSED)
            .withOfferReference(offerReference(), ACTOR, T0)
            .withContract(contract(), ACTOR, T0)
        LeadPipelineStatus.PIPELINE_ORDER.filter { it != LeadPipelineStatus.CLOSED }.forEach { target ->
            assertFalse(LeadPipelineStateMachine.canTransition(LeadPipelineStatus.CLOSED, target))
            val result = LeadPipelineStateMachine.transition(
                closed, target, LeadTransitionReason.DEAL_LOST, T1, ACTOR
            )
            assertTrue("CLOSED → $target must be refused", result is LeadTransitionResult.Illegal)
        }
    }

    @Test
    fun `stage timing comes from the audited transition, not from the record creation`() {
        val lead = qualifiedLead(status = LeadPipelineStatus.NEGOTIATING)
        assertEquals(T0, lead.stageEnteredAtEpochMillis)
        val moved = CrmFixtures.move(
            lead.withOfferReference(offerReference(at = T1), ACTOR, T1),
            LeadPipelineStatus.OFFER_SENT,
            LeadTransitionReason.OFFER_SUBMITTED,
            T1
        )
        assertEquals(T1, moved.stageEnteredAtEpochMillis)
        assertEquals(2L * CrmTime.MILLIS_PER_DAY, moved.timeInStageMillis(T1 + 2L * CrmTime.MILLIS_PER_DAY))
    }

    @Test
    fun `a lead is described without leaking provider payloads`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val description = lead.describe()
        assertTrue(description.contains(LEAD_ID))
        assertTrue(description.contains(LeadPipelineStatus.NEW.name))
        assertTrue(description.contains("Dana Whitfield"))
        assertNotNull(lead.primarySeller)
        assertNotNull(lead.subjectPropertyLink)
        assertEquals(CrmFixtures.PROPERTY_ID, lead.subjectPropertyId)
        assertEquals(LeadQualificationState.NOT_ASSESSED, lead.qualificationState)
        assertNull(lead.lastCommunication?.externalRef)
        assertEquals(CommunicationOutcome.CONNECTED, lead.lastCommunication!!.outcome)
        assertEquals(MotivationStrength.STRONG, lead.strongestMotivationStrength)
        assertTrue(lead.hasSellerResponse)
        assertTrue(lead.hasSellerConversation)
    }

    @Test
    fun `an unreachable seller with every channel opted out cannot be marked reachable`() {
        val lead = crmLead(
            status = LeadPipelineStatus.NEW,
            sellers = listOf(
                seller(
                    contactPoints = listOf(
                        CrmFixtures.phonePoint(doNotContact = true),
                        CrmFixtures.phonePoint(value = "+15125550999", primary = false, doNotContact = true, channel = ContactChannel.SMS)
                    )
                )
            )
        )
        assertFalse(lead.isSellerReachable)
        val blocked = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0, ACTOR
        )
        assertTrue(blocked is LeadTransitionResult.Blocked)
        assertTrue(
            (blocked as LeadTransitionResult.Blocked).blockers.any { it.code == CrmBlockerCodes.NO_USABLE_CONTACT_CHANNEL }
        )
    }
}
