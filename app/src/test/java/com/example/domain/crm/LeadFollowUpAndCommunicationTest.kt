package com.example.domain.crm

import com.example.domain.crm.CrmFixtures.ACTOR
import com.example.domain.crm.CrmFixtures.T0
import com.example.domain.crm.CrmFixtures.communication
import com.example.domain.crm.CrmFixtures.crmLead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Follow-up cadence and the communication history: the two things that decide whether a lead is
 * actually worked, and the compliance record of what was said to the seller.
 */
class LeadFollowUpAndCommunicationTest {

    private val day = CrmTime.MILLIS_PER_DAY

    // ── Cadence ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the cadence follows the stage and disappears when the lead is terminal`() {
        val policy = LeadFollowUpPolicy.DEFAULT
        LeadPipelineStatus.OPEN_STATUSES.forEach { status ->
            val due = LeadFollowUpPlanner.nextDueAtEpochMillis(status, T0, policy)
            assertNotNull("$status must have a cadence", due)
            assertEquals(policy.intervalDaysFor(status)!!.toLong() * day, due!! - T0)
        }
        assertNull(LeadFollowUpPlanner.nextDueAtEpochMillis(LeadPipelineStatus.CLOSED, T0, policy))
        assertNull(LeadFollowUpPlanner.nextDueAtEpochMillis(LeadPipelineStatus.LOST, T0, policy))
    }

    @Test
    fun `a scheduled follow-up is due, then overdue, then a policy breach`() {
        val followUp = LeadFollowUpPlanner.schedule(LeadPipelineStatus.NEW, T0, ACTOR)!!
        assertEquals(T0 + 3 * day, followUp.dueAtEpochMillis)
        assertEquals(T0, followUp.scheduledAtEpochMillis)
        assertEquals(LeadPipelineStatus.NEW, followUp.scheduledFromStatus)
        assertEquals(FollowUpState.SCHEDULED, followUp.state(LeadPipelineStatus.NEW, T0 + day))
        // The due window opens 24h before the due instant.
        assertEquals(FollowUpState.DUE, followUp.state(LeadPipelineStatus.NEW, T0 + 3 * day - 1))
        assertEquals(FollowUpState.DUE, followUp.state(LeadPipelineStatus.NEW, T0 + 3 * day))
        // … and stays actionable during the grace period.
        assertEquals(FollowUpState.DUE, followUp.state(LeadPipelineStatus.NEW, T0 + 3 * day + 12 * CrmTime.MILLIS_PER_HOUR))
        assertEquals(FollowUpState.OVERDUE, followUp.state(LeadPipelineStatus.NEW, T0 + 5 * day))
        assertTrue(followUp.state(LeadPipelineStatus.NEW, T0 + 5 * day).isBreaching)
        assertTrue(followUp.state(LeadPipelineStatus.NEW, T0 + 5 * day).isActionableNow)
        assertEquals(2 * day, followUp.latenessMillis(T0 + 5 * day))
        assertEquals(0L, followUp.latenessMillis(T0))
    }

    @Test
    fun `a terminal lead owes nothing and a completed follow-up is no longer a scheduling state`() {
        val followUp = LeadFollowUpPlanner.schedule(LeadPipelineStatus.NEGOTIATING, T0, ACTOR)!!
        assertEquals(
            FollowUpState.UNSCHEDULED,
            followUp.state(LeadPipelineStatus.LOST, T0 + 10 * day)
        )
        val completed = followUp.complete(T0 + day, ACTOR, "spoke with seller")
        assertEquals(FollowUpState.COMPLETED, completed.state(LeadPipelineStatus.NEGOTIATING, T0 + 10 * day))
        assertTrue(completed.isCompleted)
        assertThrows(IllegalArgumentException::class.java) { completed.complete(T0 + 2 * day, ACTOR) }
    }

    @Test
    fun `snoozing and attempt counting move the follow-up forward without losing it`() {
        val followUp = LeadFollowUpPlanner.schedule(LeadPipelineStatus.CONTACTED, T0, ACTOR)!!
        val attempted = followUp.withAttempt(T0 + CrmTime.MILLIS_PER_HOUR)
        assertEquals(1, attempted.attempts)
        val snoozed = attempted.snoozeUntil(T0 + 5 * day)
        assertTrue(snoozed.isSnoozed)
        assertEquals(T0 + 5 * day, snoozed.effectiveDueAtEpochMillis)
        assertEquals(FollowUpState.SCHEDULED, snoozed.state(LeadPipelineStatus.CONTACTED, T0 + 3 * day))
        assertThrows(IllegalArgumentException::class.java) { snoozed.snoozeUntil(T0 + day) }
        assertTrue(snoozed.describe().contains("attempts=1"))
    }

    @Test
    fun `a plan is breached when an open lead has no next touch or one that has rotted`() {
        val looseCrm = crmLead(status = LeadPipelineStatus.NEW)
        assertTrue(looseCrm.isFollowUpBreachingPlan(T0, LeadFollowUpPolicy.DEFAULT))
        assertEquals(
            "no follow-up scheduled for NEW",
            LeadFollowUpPlanner.breachReason(looseCrm, T0, LeadFollowUpPolicy.DEFAULT)
        )

        val scheduled = looseCrm.withFollowUp(
            LeadFollowUpPlanner.schedule(LeadPipelineStatus.NEW, T0, ACTOR)!!, ACTOR, T0
        )
        assertFalse(scheduled.isFollowUpBreachingPlan(T0 + day, LeadFollowUpPolicy.DEFAULT))
        assertFalse(scheduled.isFollowUpBreachingPlan(T0 + 5 * day, LeadFollowUpPolicy.DEFAULT))
        assertTrue("a month late is a breach", scheduled.isFollowUpBreachingPlan(T0 + 40 * day, LeadFollowUpPolicy.DEFAULT))
        assertTrue(LeadFollowUpPlanner.breachReason(scheduled, T0 + 40 * day, LeadFollowUpPolicy.DEFAULT)!!.contains("overdue by"))

        val completed = scheduled.copy(
            nextFollowUp = scheduled.nextFollowUp!!.complete(T0 + day, ACTOR, "left a message")
        )
        assertTrue(LeadFollowUpPlanner.isBreachingPlan(completed, T0 + 2 * day, LeadFollowUpPolicy.DEFAULT))
        assertEquals(
            "follow-up completed without scheduling the next touch",
            LeadFollowUpPlanner.breachReason(completed, T0 + 2 * day, LeadFollowUpPolicy.DEFAULT)
        )

        val closed = crmLead(status = LeadPipelineStatus.CLOSED)
        assertFalse(closed.isFollowUpBreachingPlan(T0 + 100 * day, LeadFollowUpPolicy.DEFAULT))
        assertNull(LeadFollowUpPlanner.breachReason(closed, T0 + 100 * day, LeadFollowUpPolicy.DEFAULT))
    }

    @Test
    fun `the follow-up policy must cover every open stage and no terminal one`() {
        assertThrows(IllegalArgumentException::class.java) {
            LeadFollowUpPolicy(intervalDaysByStatus = LeadFollowUpPolicy.DEFAULT_INTERVALS - LeadPipelineStatus.NEW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadFollowUpPolicy(
                intervalDaysByStatus = LeadFollowUpPolicy.DEFAULT_INTERVALS + (LeadPipelineStatus.CLOSED to 3)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadFollowUpPolicy(intervalDaysByStatus = LeadFollowUpPolicy.DEFAULT_INTERVALS + (LeadPipelineStatus.NEW to 0))
        }
        assertThrows(IllegalArgumentException::class.java) { LeadFollowUpPolicy(dueWindowHours = 200) }
        assertThrows(IllegalArgumentException::class.java) { LeadFollowUpPolicy(maxFollowUpAgeDays = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            LeadFollowUpPolicy(overdueGraceHours = -1)
        }
    }

    @Test
    fun `follow-up records validate their own shape`() {
        assertThrows(IllegalArgumentException::class.java) {
            LeadFollowUp(
                dueAtEpochMillis = T0,
                scheduledAtEpochMillis = T0,
                scheduledBy = " ",
                scheduledFromStatus = LeadPipelineStatus.NEW
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadFollowUp(
                dueAtEpochMillis = T0,
                scheduledAtEpochMillis = T0,
                scheduledBy = ACTOR,
                scheduledFromStatus = LeadPipelineStatus.NEW,
                completedAtEpochMillis = T0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadFollowUp(
                dueAtEpochMillis = T0,
                scheduledAtEpochMillis = T0,
                scheduledBy = ACTOR,
                scheduledFromStatus = LeadPipelineStatus.NEW,
                attempts = -1
            )
        }
    }

    // ── Communication history ───────────────────────────────────────────────────────────────────

    @Test
    fun `outcomes are grouped so an impossible entry cannot exist`() {
        assertTrue(CommunicationOutcome.CONNECTED.isCompatibleWith(CommunicationDirection.OUTBOUND))
        assertFalse(CommunicationOutcome.SELLER_REPLIED.isCompatibleWith(CommunicationDirection.OUTBOUND))
        assertTrue(CommunicationOutcome.SELLER_REPLIED.isCompatibleWith(CommunicationDirection.INBOUND))
        assertFalse(CommunicationOutcome.NO_ANSWER.isCompatibleWith(CommunicationDirection.INBOUND))

        assertThrows(IllegalArgumentException::class.java) {
            communication(direction = CommunicationDirection.INBOUND, outcome = CommunicationOutcome.NO_ANSWER, durationSeconds = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            communication(direction = CommunicationDirection.OUTBOUND, outcome = CommunicationOutcome.SELLER_INTERESTED, durationSeconds = 60)
        }
    }

    @Test
    fun `communication classification drives the pipeline and compliance`() {
        val connected = communication(outcome = CommunicationOutcome.CONNECTED)
        assertTrue(connected.isConnected)
        assertTrue(connected.isConversation)
        assertTrue(connected.isAttempt.not())
        assertTrue(connected.hasProviderReference.not())

        val thirdParty = communication(outcome = CommunicationOutcome.REACHED_THIRD_PARTY, durationSeconds = 45)
        assertTrue("a third party proves the number works", thirdParty.isConnected)
        assertFalse("but not that the seller responded", thirdParty.outcome.isSellerResponse)

        val replied = communication(direction = CommunicationDirection.INBOUND, outcome = CommunicationOutcome.SELLER_REPLIED, durationSeconds = null)
        assertTrue(replied.isInbound)
        assertTrue(replied.outcome.isSellerResponse)
        assertTrue(replied.outcome.provesContactPath())

        val dnc = communication(direction = CommunicationDirection.INBOUND, outcome = CommunicationOutcome.DO_NOT_CONTACT_REQUESTED, durationSeconds = null)
        assertTrue(dnc.demandsDoNotContact)
        assertTrue(dnc.isNegative)

        val bounced = communication(outcome = CommunicationOutcome.EMAIL_BOUNCED, channel = CommunicationChannel.EMAIL, durationSeconds = null)
        assertTrue(bounced.isAttempt)
        assertTrue(bounced.outcome.isNegative)
        assertTrue(bounced.outcome.isDeliveryArtefact.not())
        assertTrue(communication(outcome = CommunicationOutcome.EMAIL_SENT, channel = CommunicationChannel.EMAIL, durationSeconds = null)
            .outcome.isDeliveryArtefact)
        assertTrue(CommunicationChannel.SMS.isRegulatedOutbound)
        assertFalse(CommunicationChannel.IN_PERSON.isAutomatedCapable)
    }

    @Test
    fun `a communication is a reference, not a payload copy`() {
        val entry = communication(
            outcome = CommunicationOutcome.CONNECTED,
            durationSeconds = 120
        ).copy(externalRef = "dialer-call-88", relatedOfferId = "OFFER-9")
        assertTrue(entry.hasProviderReference)
        assertTrue(entry.describe().contains("outbound call"))
        assertEquals("OFFER-9", entry.relatedOfferId)
        assertThrows(IllegalArgumentException::class.java) { entry.copy(supersedesId = entry.id) }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(summary = "x".repeat(501)) }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(loggedAtEpochMillis = T0 - 1) }
    }

    @Test
    fun `recording a do-not-contact response freezes the seller and blocks outreach`() {
        var lead = crmLead(status = LeadPipelineStatus.RESPONDED)
        assertFalse(lead.isContactFrozen)
        lead = lead.withCommunication(
            communication(
                id = "COMM-DNC",
                direction = CommunicationDirection.INBOUND,
                outcome = CommunicationOutcome.DO_NOT_CONTACT_REQUESTED,
                channel = CommunicationChannel.CALL,
                durationSeconds = null,
                at = T0 + 1
            ),
            ACTOR,
            T0 + 1
        )
        assertTrue(lead.isContactFrozen)
        assertTrue(lead.primarySeller!!.isFrozen)
        // The freeze keeps the human summary when there is one.
        assertEquals("Seller answered, wants to close this quarter", lead.primarySeller!!.restrictions.note)

        // Without a summary the freeze still records why it happened.
        val silent = crmLead(status = LeadPipelineStatus.RESPONDED).withCommunication(
            communication(
                id = "COMM-DNC-2",
                direction = CommunicationDirection.INBOUND,
                outcome = CommunicationOutcome.DO_NOT_CONTACT_REQUESTED,
                channel = CommunicationChannel.CALL,
                durationSeconds = null,
                summary = "",
                at = T0 + 1
            ),
            ACTOR,
            T0 + 1
        )
        assertTrue(silent.primarySeller!!.restrictions.note!!.contains("do-not-contact"))

        val blocked = LeadPipelineStateMachine.transition(
            lead, LeadPipelineStatus.NEGOTIATING, LeadTransitionReason.NEGOTIATION_OPENED, T0 + 1, ACTOR
        )
        assertTrue(blocked is LeadTransitionResult.Blocked)
    }

    @Test
    fun `the most recent communication and the conversation flags come from the history`() {
        val lead = crmLead(
            status = LeadPipelineStatus.CONTACTED,
            communications = listOf(
                communication(id = "C1", at = T0, outcome = CommunicationOutcome.NO_ANSWER, durationSeconds = null),
                communication(id = "C2", at = T0 + day, outcome = CommunicationOutcome.LEFT_VOICEMAIL, durationSeconds = null)
            )
        )
        assertEquals("C2", lead.lastCommunication!!.id)
        assertFalse(lead.hasSellerResponse)
        assertFalse(lead.hasSellerConversation)

        val answered = lead.withCommunication(
            communication(
                id = "C3",
                at = T0 + 2 * day,
                direction = CommunicationDirection.INBOUND,
                outcome = CommunicationOutcome.SELLER_REPLIED,
                durationSeconds = null
            ),
            ACTOR,
            T0 + 2 * day
        )
        assertTrue(answered.hasSellerResponse)
        assertTrue(answered.hasSellerConversation)
        assertEquals("C3", answered.lastCommunication!!.id)
    }
}
