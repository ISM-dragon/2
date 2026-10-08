package com.example.domain.crm

import com.example.domain.crm.CrmFixtures.ACTOR
import com.example.domain.crm.CrmFixtures.T0
import com.example.domain.crm.CrmFixtures.T1
import com.example.domain.crm.CrmFixtures.assess
import com.example.domain.crm.CrmFixtures.communication
import com.example.domain.crm.CrmFixtures.condition
import com.example.domain.crm.CrmFixtures.contract
import com.example.domain.crm.CrmFixtures.crmLead
import com.example.domain.crm.CrmFixtures.motivationSignal
import com.example.domain.crm.CrmFixtures.phonePoint
import com.example.domain.crm.CrmFixtures.qualifiedLead
import com.example.domain.crm.CrmFixtures.seller
import com.example.domain.crm.CrmFixtures.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LeadValidator] reports judgement calls as stable codes instead of refusing to store the lead.
 * These tests pin the codes and severities — and, above all, that a well-formed lead is quiet.
 */
class LeadValidationTest {

    private val day = CrmTime.MILLIS_PER_DAY

    private fun issues(lead: Lead, now: Long? = null, policy: LeadValidationPolicy = LeadValidationPolicy.DEFAULT) =
        LeadValidator.validate(lead, now, policy)

    private fun codes(lead: Lead, now: Long? = null, policy: LeadValidationPolicy = LeadValidationPolicy.DEFAULT) =
        issues(lead, now, policy).map { it.code }

    private fun Lead.scheduleFollowUp(at: Long): Lead =
        withFollowUp(LeadFollowUpPlanner.schedule(pipelineStatus, at, ACTOR)!!, ACTOR, at)

    @Test
    fun `a fully formed lead validates quietly`() {
        val lead = CrmFixtures.workedLead()
        val found = issues(lead, T0)
        assertTrue(
            "unexpected issues: ${found.joinToString { it.describe() }}",
            found.none { it.severity != LeadValidationSeverity.INFO }
        )
        assertFalse(lead.hasValidationErrors())
    }

    @Test
    fun `open leads must name a seller, a property, a motivation and a next touch`() {
        val bare = crmLead(
            status = LeadPipelineStatus.NEW,
            sellers = emptyList(),
            propertyLinks = emptyList(),
            motivationSignals = emptyList(),
            condition = null,
            timeline = SellerTimelineFact.unknown(T0)
        )
        val found = codes(bare)
        assertTrue(LeadValidationCodes.MISSING_SELLER in found)
        assertTrue(LeadValidationCodes.MISSING_PROPERTY_LINK in found)
        assertTrue(LeadValidationCodes.MISSING_FOLLOW_UP in found)
        assertTrue(LeadValidationCodes.MISSING_MOTIVATION in found)
        assertTrue(LeadValidationCodes.UNKNOWN_CONDITION in found)
        assertTrue(LeadValidationCodes.UNKNOWN_TIMELINE in found)
        assertFalse(bare.hasValidationErrors())

        // … and each switch lets an importer/storefront relax the loudness.
        val relaxed = LeadValidationPolicy(requireSellerForOpenLeads = false, requireFollowUpForOpenLeads = false)
        assertFalse(LeadValidationCodes.MISSING_SELLER in codes(bare, policy = relaxed))
        assertFalse(LeadValidationCodes.MISSING_FOLLOW_UP in codes(bare, policy = relaxed))
        assertTrue(LeadValidationCodes.MISSING_PROPERTY_LINK in codes(bare, policy = relaxed))
    }

    @Test
    fun `a seller without a permitted channel is reported as unreachable`() {
        val frozen = crmLead(
            status = LeadPipelineStatus.NEW,
            sellers = listOf(
                seller(
                    restrictions = SellerContactRestrictions(doNotCall = true, doNotText = true, doNotEmail = true, doNotMail = true),
                    contactPoints = listOf(phonePoint(doNotContact = true))
                )
            )
        )
        assertTrue(LeadValidationCodes.NO_CONTACT_CHANNEL in codes(frozen))

        val noPoints = crmLead(status = LeadPipelineStatus.NEW, sellers = listOf(seller(contactPoints = emptyList())))
        assertTrue(LeadValidationCodes.NO_CONTACT_CHANNEL in codes(noPoints))
    }

    @Test
    fun `a frozen contact path on an open lead is a warning, never an error`() {
        val lead = CrmFixtures.workedLead().copy(
            sellers = listOf(seller(restrictions = SellerContactRestrictions(litigationHold = true)))
        )
        val issue = issues(lead).single { it.code == LeadValidationCodes.CONTACT_FROZEN_BUT_OPEN }
        assertEquals(LeadValidationSeverity.WARNING, issue.severity)
        assertFalse(lead.hasValidationErrors())
    }

    @Test
    fun `offer and contract stages must carry their references`() {
        val offerStage = crmLead(status = LeadPipelineStatus.OFFER_SENT)
        val offerCodes = codes(offerStage)
        assertTrue(LeadValidationCodes.MISSING_OFFER_REFERENCE in offerCodes)
        assertTrue(LeadValidationCodes.MISSING_QUALIFICATION in offerCodes)
        assertTrue(offerStage.hasValidationErrors())

        assertTrue(LeadValidationCodes.MISSING_CONTRACT in codes(crmLead(status = LeadPipelineStatus.UNDER_CONTRACT)))

        val noWindow = crmLead(
            status = LeadPipelineStatus.DUE_DILIGENCE,
            contract = contract(dueDiligenceEndsAtEpochMillis = null),
            offerReferences = listOf(CrmFixtures.offerReference())
        )
        assertTrue(LeadValidationCodes.MISSING_DUE_DILIGENCE_WINDOW in codes(noWindow))

        // A contract referenced before the offer stage: warning, because the stage was skipped.
        val earlyContract = crmLead(status = LeadPipelineStatus.CONTACTED, contract = contract())
        val skipped = issues(earlyContract).single { it.code == LeadValidationCodes.CONTRACT_WITHOUT_OFFER_STAGE }
        assertEquals(LeadValidationSeverity.WARNING, skipped.severity)
    }

    @Test
    fun `a disqualified lead that is still engaged is an error`() {
        val base = crmLead(status = LeadPipelineStatus.NEGOTIATING)
        val disqualified = base.copy(
            qualification = LeadQualificationEngine.evaluate(
                LeadQualificationInputs.fromLead(base).copy(doNotContact = true),
                T0
            )
        )
        assertEquals(LeadQualificationState.DISQUALIFIED, disqualified.qualificationState)
        val finding = issues(disqualified).single { it.code == LeadValidationCodes.QUALIFICATION_DISQUALIFIED_WHILE_ENGAGED }
        assertEquals(LeadValidationSeverity.ERROR, finding.severity)
        assertTrue(disqualified.hasValidationErrors())

        // LOST is where a disqualified lead belongs, so it is not reported there.
        val lost = CrmFixtures.move(disqualified, LeadPipelineStatus.LOST, LeadTransitionReason.DEAL_LOST, T1)
        assertFalse(LeadValidationCodes.QUALIFICATION_DISQUALIFIED_WHILE_ENGAGED in codes(lost))

        // … and the switch lets an operator park a disqualified lead in an engaged stage knowingly.
        val tolerated = LeadValidationPolicy(forbidDisqualifiedWhileEngaged = false)
        assertFalse(LeadValidationCodes.QUALIFICATION_DISQUALIFIED_WHILE_ENGAGED in codes(disqualified, policy = tolerated))
    }

    @Test
    fun `an unaudited status is an error`() {
        val forged = crmLead(status = LeadPipelineStatus.NEGOTIATING)
        assertTrue(LeadValidationCodes.MISSING_TRANSITION_HISTORY in codes(forged))
        assertTrue(forged.hasValidationErrors())
        val audited = CrmFixtures.move(crmLead(status = LeadPipelineStatus.NEW), LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0, ACTOR)
        assertFalse(LeadValidationCodes.MISSING_TRANSITION_HISTORY in codes(audited))
    }

    @Test
    fun `evidence dated after the last write is an error`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val writtenAt = lead.audit.updatedAtEpochMillis

        val futureComm = lead.copy(communications = lead.communications + communication(id = "COMM-LATE", at = writtenAt + day))
        assertTrue(LeadValidationCodes.FUTURE_DATED_EVIDENCE in codes(futureComm))
        assertTrue(futureComm.hasValidationErrors())

        val lateSignal = lead.copy(
            motivationSignals = lead.motivationSignals + motivationSignal(id = "SIG-LATE", at = writtenAt + day)
        )
        assertTrue(LeadValidationCodes.FUTURE_DATED_EVIDENCE in codes(lateSignal))

        val lateCondition = lead.copy(condition = condition(at = writtenAt + day))
        assertTrue(LeadValidationCodes.FUTURE_DATED_EVIDENCE in codes(lateCondition))

        val lateTimeline = lead.copy(timeline = CrmFixtures.timeline(at = writtenAt + day))
        assertTrue(LeadValidationCodes.FUTURE_DATED_EVIDENCE in codes(lateTimeline))
    }

    @Test
    fun `communications referencing a seller who is not on the lead are reported`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW, communications = listOf(communication(sellerId = "SELLER-GHOST")))
        assertTrue(LeadValidationCodes.UNKNOWN_COMMUNICATION_SELLER in codes(lead))
        assertFalse(LeadValidationCodes.UNKNOWN_COMMUNICATION_SELLER in codes(crmLead()))
    }

    @Test
    fun `follow-up ageing is policed against the supplied clock only`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW).scheduleFollowUp(T0)
        assertFalse(LeadValidationCodes.FOLLOW_UP_OVERDUE in codes(lead, T0 + day))
        assertTrue(LeadValidationCodes.FOLLOW_UP_OVERDUE in codes(lead, T0 + 10 * day))
        assertFalse("no clock, no ageing complaints", LeadValidationCodes.FOLLOW_UP_OVERDUE in codes(lead))

        // A follow-up scheduled from a stage the lead has since left is informational.
        val moved = CrmFixtures.move(lead, LeadPipelineStatus.CONTACTED, LeadTransitionReason.OUTREACH_LOGGED, T0 + day)
        assertEquals(
            LeadValidationSeverity.INFO,
            issues(moved).single { it.code == LeadValidationCodes.FOLLOW_UP_STAGE_MISMATCH }.severity
        )
    }

    @Test
    fun `tag sprawl, unthanked referrals and unattributable campaigns are reported`() {
        val crowded = crmLead(status = LeadPipelineStatus.NEW, tags = (1..13).map { "tag-$it" }.toSet())
        assertEquals(
            LeadValidationSeverity.WARNING,
            issues(crowded).single { it.code == LeadValidationCodes.TAG_COUNT_HIGH }.severity
        )
        val excessive = LeadValidationPolicy(tagPolicy = LeadTagPolicy(maxTagsPerLead = 3, warnAboveTagCount = 2))
        assertEquals(
            LeadValidationSeverity.ERROR,
            issues(crowded, policy = excessive).single { it.code == LeadValidationCodes.TAG_COUNT_EXCEEDED }.severity
        )

        assertTrue(
            LeadValidationCodes.REFERRAL_WITHOUT_REFERRER in
                codes(crmLead(status = LeadPipelineStatus.NEW, source = source(kind = LeadSourceKind.REFERRAL, referrerName = null)))
        )
        assertTrue(
            LeadValidationCodes.DIRECT_MAIL_WITHOUT_LIST in
                codes(crmLead(status = LeadPipelineStatus.NEW, source = source(kind = LeadSourceKind.DIRECT_MAIL, listName = null)))
        )
        assertTrue(
            LeadValidationCodes.PAID_SOURCE_WITHOUT_COST in
                codes(crmLead(status = LeadPipelineStatus.NEW, source = source(kind = LeadSourceKind.PAID_ADS)))
        )
    }

    @Test
    fun `a stale qualification warns and priority drift is informational`() {
        val lead = qualifiedLead()
        val later = lead.qualification!!.evaluatedAtEpochMillis + 45L * day
        val found = issues(lead, later)
        assertEquals(
            LeadValidationSeverity.WARNING,
            found.single { it.code == LeadValidationCodes.STALE_QUALIFICATION }.severity
        )
        assertEquals(
            LeadValidationSeverity.INFO,
            found.single { it.code == LeadValidationCodes.PRIORITY_DRIFT }.severity
        )

        assertFalse(
            LeadValidationCodes.PRIORITY_DRIFT in
                codes(lead, later, LeadValidationPolicy(reportPriorityDrift = false))
        )
        // A pre-offer lead is allowed to carry an old assessment: nothing downstream depends on it.
        val early = crmLead(status = LeadPipelineStatus.NEW, at = T0).copy(qualification = lead.qualification)
        assertFalse(LeadValidationCodes.STALE_QUALIFICATION in codes(early, later))
    }

    @Test
    fun `what the transition gate would refuse is surfaced as advisory info`() {
        fun advisory(lead: Lead) = issues(lead).filter {
            it.severity == LeadValidationSeverity.INFO && it.field == "pipelineStatus"
        }

        // Nothing stands between a fresh, evidenced lead and its next stage.
        assertEquals(emptyList<LeadValidationIssue>(), advisory(CrmFixtures.workedLead(at = T0, status = LeadPipelineStatus.NEW)))

        // The next stage of an offer-stage lead is refused by the gate; the same code is reported here.
        val offerStage = crmLead(status = LeadPipelineStatus.OFFER_SENT)
        assertTrue(advisory(offerStage).any { it.code == LeadValidationCodes.MISSING_OFFER_REFERENCE })
        assertTrue(advisory(offerStage).all { it.message.contains("is blocked") })
    }

    @Test
    fun `issues deduplicate by code, field and message and describe themselves`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW, sellers = emptyList())
        val found = issues(lead)
        assertEquals(found.size, found.distinctBy { Triple(it.code, it.field, it.message) }.size)
        assertEquals(1, found.count { it.code == LeadValidationCodes.MISSING_SELLER })

        val issue = LeadValidationIssue(
            code = LeadValidationCodes.MISSING_SELLER,
            severity = LeadValidationSeverity.WARNING,
            field = "sellers",
            message = "none recorded"
        )
        assertTrue(issue.describe().startsWith("WARNING [missing-seller]"))
        assertThrows(IllegalArgumentException::class.java) { issue.copy(field = " ") }
        assertThrows(IllegalArgumentException::class.java) { issue.copy(message = "") }
        assertThrows(IllegalArgumentException::class.java) { issue.copy(code = " ") }
    }

    @Test
    fun `terminal leads stop being asked for open-lead housekeeping`() {
        val closed = crmLead(status = LeadPipelineStatus.CLOSED, sellers = emptyList(), propertyLinks = emptyList())
        val found = codes(closed)
        assertFalse(LeadValidationCodes.MISSING_SELLER in found)
        assertFalse(LeadValidationCodes.MISSING_FOLLOW_UP in found)
        assertFalse(LeadValidationCodes.MISSING_MOTIVATION in found)
        assertFalse(LeadValidationCodes.CONTACT_FROZEN_BUT_OPEN in found)
        assertFalse(LeadValidationCodes.UNKNOWN_CONDITION in found)
    }

    @Test
    fun `the validation policy refuses thresholds it could not honour`() {
        assertThrows(IllegalArgumentException::class.java) {
            LeadValidationPolicy(followUpOverdueWarningDays = 91)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadValidationPolicy(followUpOverdueWarningDays = -1)
        }
    }

    @Test
    fun `the lead extensions expose the verdict without a validator call site`() {
        val lead = CrmFixtures.workedLead()
        assertFalse(lead.hasValidationErrors())
        assertTrue(lead.validate(T0).isNotEmpty())
        assertTrue(lead.validate(T0).any { it.severity == LeadValidationSeverity.INFO })
    }
}
