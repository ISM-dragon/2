package com.example.domain.crm

import com.example.domain.crm.CrmFixtures.ACTOR
import com.example.domain.crm.CrmFixtures.T0
import com.example.domain.crm.CrmFixtures.T1
import com.example.domain.crm.CrmFixtures.assess
import com.example.domain.crm.CrmFixtures.communication
import com.example.domain.crm.CrmFixtures.condition
import com.example.domain.crm.CrmFixtures.crmLead
import com.example.domain.crm.CrmFixtures.motivationSignal
import com.example.domain.crm.CrmFixtures.timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic qualification: scoring, evidence blockers, hard disqualifiers, state transitions,
 * staleness decay and operator overrides.
 */
class LeadQualificationEngineTest {

    // ── Golden case ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the reference deal scores exactly 76 and qualifies`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val assessment = LeadQualificationEngine.evaluate(LeadQualificationInputs.fromLead(lead), T0)

        // Full arithmetic, so a policy change that shifts the score cannot pass silently:
        //   spread        23.5% of ARV (>= 15%)                    -> 30/30
        //   motivation    index 26 (one STRONG documented signal)  ->  7/25  (0.26 * 25)
        //   timeline      30 days (urgency 85)                     -> 13/15  (0.85 * 15)
        //   condition     MINOR_REHAB (roof failure)               ->  6/10
        //   reachability  channel + response + conversation        -> 12/12
        //   completeness  link, address, price, repairs, signals   ->  8/8
        assertEquals(LeadQualificationState.QUALIFIED, assessment.state)
        assertEquals(76, assessment.score)
        assertEquals(76, assessment.rawScore)
        assertEquals(100, assessment.maximumScore)
        assertEquals(assessment.score, assessment.factors.sumOf { it.points })
        assertEquals(30, assessment.factor(LeadQualificationFactorId.SPREAD)!!.points)
        assertEquals(7, assessment.factor(LeadQualificationFactorId.MOTIVATION)!!.points)
        assertEquals(13, assessment.factor(LeadQualificationFactorId.TIMELINE)!!.points)
        assertEquals(6, assessment.factor(LeadQualificationFactorId.CONDITION)!!.points)
        assertEquals(12, assessment.factor(LeadQualificationFactorId.REACHABILITY)!!.points)
        assertEquals(8, assessment.factor(LeadQualificationFactorId.DATA_COMPLETENESS)!!.points)

        assertTrue(assessment.blockers.isEmpty())
        assertTrue(assessment.disqualifiers.isEmpty())
        assertTrue(assessment.isQualified)
        assertTrue(assessment.isActionable)
        assertFalse(assessment.isBlocked)
        assertFalse(assessment.wasCappedByBlockers)

        // The only warning: the asking price sits above the 70% rule ceiling, so the deal only works if
        // the seller negotiates down — exactly what an operator needs to see.
        assertEquals(1, assessment.warnings.size)
        assertTrue(assessment.warnings.single().contains("above the"))

        val spread = assessment.spread!!
        assertEquals(70_500.0, spread.projectedSpreadUsd, 0.001)
        assertEquals(23.5, spread.spreadPctOfArv, 0.001)
        assertEquals(160_500.0, spread.maximumAllowableOfferUsd, 0.001)
        assertEquals(-19_500.0, spread.negotiationRoomUsd, 0.001)
        assertTrue(spread.meetsPolicyFloor)
        assertTrue(assessment.summary().contains("QUALIFIED"))
        assertTrue(assessment.describe().contains("Spread vs. 70% rule"))
    }

    @Test
    fun `qualification is deterministic and order independent`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val first = LeadQualificationEngine.evaluate(LeadQualificationInputs.fromLead(lead), T0)
        val second = LeadQualificationEngine.evaluate(LeadQualificationInputs.fromLead(lead), T0)
        assertEquals(first, second)

        val reordered = lead.copy(
            motivationSignals = listOf(
                motivationSignal(id = "SIG-B", kind = MotivationSignalKind.TAX_DELINQUENT),
                motivationSignal(
                    id = "SIG-A",
                    kind = MotivationSignalKind.PRE_FORECLOSURE,
                    strength = MotivationStrength.MODERATE,
                    evidenceRef = "notice-2026-77"
                )
            )
        )
        val a = LeadQualificationInputs.fromLead(reordered)
        val b = LeadQualificationInputs.fromLead(reordered.copy(motivationSignals = reordered.motivationSignals.reversed()))
        assertEquals(
            LeadQualificationEngine.evaluate(a, T0).score,
            LeadQualificationEngine.evaluate(b, T0).score
        )
    }

    // ── Hard disqualifiers ──────────────────────────────────────────────────────────────────────

    @Test
    fun `hard disqualifiers beat any score`() {
        val base = crmLead(status = LeadPipelineStatus.NEW)
        val inputs = LeadQualificationInputs.fromLead(base)

        val declined = LeadQualificationEngine.evaluate(inputs.copy(sellerDeclined = true), T0)
        assertEquals(LeadQualificationState.DISQUALIFIED, declined.state)
        assertTrue(declined.disqualifierCodes().contains(CrmBlockerCodes.SELLER_DECLINED))

        val frozen = LeadQualificationEngine.evaluate(inputs.copy(doNotContact = true), T0)
        assertTrue(frozen.disqualifierCodes().contains(CrmBlockerCodes.DO_NOT_CONTACT))

        val outsideArea = LeadQualificationEngine.evaluate(inputs.copy(withinServiceArea = false), T0)
        assertTrue(outsideArea.disqualifierCodes().contains(CrmBlockerCodes.OUTSIDE_SERVICE_AREA))

        val thin = LeadQualificationEngine.evaluate(
            inputs.copy(askingPriceUsd = 292_000.0, afterRepairValueUsd = 300_000.0),
            T0
        )
        assertEquals(LeadQualificationState.DISQUALIFIED, thin.state)
        assertTrue(thin.disqualifierCodes().contains(CrmBlockerCodes.SPREAD_BELOW_FLOOR))
        assertTrue(thin.spread!!.projectedSpreadUsd < 0.0)
        assertTrue(thin.disqualifiers.single().message.contains("below the policy floor"))
    }

    @Test
    fun `a thin but not disqualifying spread earns partial spread points`() {
        val inputs = LeadQualificationInputs.fromLead(crmLead(status = LeadPipelineStatus.NEW))
        // 10% of ARV: above the 5% floor, below the 15% full-credit line.
        val partial = LeadQualificationEngine.evaluate(
            inputs.copy(askingPriceUsd = 219_500.0, afterRepairValueUsd = 300_000.0, estimatedRepairCostUsd = 45_000.0),
            T0
        )
        val spreadPoints = partial.factor(LeadQualificationFactorId.SPREAD)!!.points
        assertTrue("partial credit expected, got $spreadPoints", spreadPoints in 1..29)
        assertFalse(partial.isDisqualified)
    }

    // ── Evidence blockers ───────────────────────────────────────────────────────────────────────

    @Test
    fun `missing evidence blocks qualification without disqualifying the lead`() {
        val inputs = LeadQualificationInputs.fromLead(crmLead(status = LeadPipelineStatus.NEW))
        val noLink = LeadQualificationEngine.evaluate(
            inputs.copy(hasPropertyLink = false, hasPropertyAddress = false),
            T0
        )
        assertEquals(LeadQualificationState.NURTURE, noLink.state)
        assertTrue(noLink.wasCappedByBlockers)
        assertEquals(
            listOf(CrmBlockerCodes.MISSING_PROPERTY_LINK, CrmBlockerCodes.MISSING_PROPERTY_ADDRESS),
            noLink.blockerCodes()
        )
        assertTrue(noLink.warnings.any { it.contains("capped at NURTURE") })

        val noChannel = LeadQualificationEngine.evaluate(
            inputs.copy(hasUsableContactChannel = false, sellerResponded = false, sellerConnected = false),
            T0
        )
        assertTrue(noChannel.blockerCodes().contains(CrmBlockerCodes.MISSING_CONTACT_CHANNEL))

        val noMotivation = LeadQualificationEngine.evaluate(inputs.copy(motivationSignals = emptyList()), T0)
        assertTrue(noMotivation.blockerCodes().contains(CrmBlockerCodes.MISSING_MOTIVATION_EVIDENCE))

        val undocumented = LeadQualificationEngine.evaluate(
            inputs.copy(motivationSignals = listOf(motivationSignal(evidenceRef = null))),
            T0
        )
        assertTrue(undocumented.blockerCodes().contains(CrmBlockerCodes.MISSING_DOCUMENTED_MOTIVATION))

        val tolerant = LeadQualificationEngine.evaluate(
            inputs.copy(motivationSignals = listOf(motivationSignal(evidenceRef = null))),
            T0,
            LeadQualificationPolicy(motivationScoring = MotivationScoringPolicy(requireDocumentedEvidenceForQualified = false))
        )
        assertFalse(tolerant.blockerCodes().contains(CrmBlockerCodes.MISSING_DOCUMENTED_MOTIVATION))
    }

    @Test
    fun `an unknown repair scope is never treated as zero repairs`() {
        val inputs = LeadQualificationInputs.fromLead(
            crmLead(status = LeadPipelineStatus.NEW, condition = null)
        ).copy(estimatedRepairCostUsd = null)
        val assessment = LeadQualificationEngine.evaluate(inputs, T0)

        assertNull("no spread without a repair figure", assessment.spread)
        assertEquals(0, assessment.factor(LeadQualificationFactorId.SPREAD)!!.points)
        assertTrue(assessment.blockerCodes().contains(CrmBlockerCodes.MISSING_REPAIR_ESTIMATE))
        assertTrue(assessment.warnings.any { it.contains("Spread not computable") })
        assertTrue(assessment.warnings.any { it.contains("Condition not assessed") })
    }

    @Test
    fun `structural repairs are scored and flagged for inspection`() {
        val lead = crmLead(
            status = LeadPipelineStatus.NEW,
            condition = condition(indicators = setOf(ConditionIndicator.FOUNDATION_ISSUES, ConditionIndicator.NEW_WINDOWS))
        )
        val assessment = LeadQualificationEngine.evaluate(LeadQualificationInputs.fromLead(lead), T0)
        assertEquals(PropertyCondition.MAJOR_REHAB, lead.condition!!.condition)
        assertEquals(10, assessment.factor(LeadQualificationFactorId.CONDITION)!!.points)
        assertTrue(assessment.warnings.any { it.contains("order an inspection") })
    }

    // ── State transitions ───────────────────────────────────────────────────────────────────────

    @Test
    fun `first assessment records FIRST_ASSESSMENT and later evidence records EVIDENCE_ADDED`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW, motivationSignals = emptyList())
        val weakInputs = LeadQualificationInputs.fromLead(lead)
        val firstResult = LeadQualificationEngine.reassess(lead, T0, ACTOR, weakInputs)
        assertTrue(firstResult is LeadQualificationResult.Applied)
        firstResult as LeadQualificationResult.Applied
        assertEquals(LeadQualificationReason.FIRST_ASSESSMENT, firstResult.transition.reason)
        assertEquals(LeadQualificationState.NURTURE, firstResult.lead.qualificationState)
        assertEquals(1, firstResult.lead.qualificationHistory.size)
        assertEquals(ACTOR, firstResult.lead.audit.updatedBy)

        // Adding the missing evidence raises the state, and the reason says so.
        val enriched = firstResult.lead.withMotivationSignal(motivationSignal(at = T1), ACTOR, T1)
        val secondResult = LeadQualificationEngine.reassess(
            enriched, T1, ACTOR, LeadQualificationInputs.fromLead(enriched)
        )
        assertTrue(secondResult is LeadQualificationResult.Applied)
        secondResult as LeadQualificationResult.Applied
        assertEquals(LeadQualificationState.QUALIFIED, secondResult.transition.to)
        assertEquals(LeadQualificationReason.EVIDENCE_ADDED, secondResult.transition.reason)
        assertTrue(secondResult.transition.isImprovement)
        assertEquals(2, secondResult.lead.qualificationHistory.size)
        assertTrue(secondResult.transition.describe().contains("NURTURE → QUALIFIED"))
    }

    @Test
    fun `a seller response is named as the reason when it is what lifts the lead`() {
        val lead = crmLead(
            status = LeadPipelineStatus.CONTACTED,
            motivationSignals = emptyList(),
            communications = listOf(
                communication(outcome = CommunicationOutcome.NO_ANSWER, durationSeconds = null)
            )
        )
        val scored = LeadQualificationEngine.reassess(
            lead, T0, ACTOR, LeadQualificationInputs.fromLead(lead)
        )
        assertTrue(scored is LeadQualificationResult.Applied)
        assertEquals(LeadQualificationState.NURTURE, (scored as LeadQualificationResult.Applied).lead.qualificationState)

        val responded = scored.lead
            .withCommunication(communication(id = "COMM-2", at = T1, sellerId = CrmFixtures.SELLER_ID), ACTOR, T1)
            .withMotivationSignal(motivationSignal(id = "SIG-2", at = T1), ACTOR, T1)
        val lifted = LeadQualificationEngine.reassess(
            responded, T1, ACTOR, LeadQualificationInputs.fromLead(responded)
        )
        assertTrue(lifted is LeadQualificationResult.Applied)
        assertEquals(
            LeadQualificationReason.SELLER_RESPONDED,
            (lifted as LeadQualificationResult.Applied).transition.reason
        )
    }

    @Test
    fun `re-running the same facts refreshes the assessment without inventing history`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val first = LeadQualificationEngine.reassess(lead, T0, ACTOR)
        assertTrue(first is LeadQualificationResult.Applied)
        val assessed = (first as LeadQualificationResult.Applied).lead

        val second = LeadQualificationEngine.reassess(assessed, T0, ACTOR)
        assertTrue(second is LeadQualificationResult.NoOp)
        second as LeadQualificationResult.NoOp
        assertEquals(LeadQualificationState.QUALIFIED, second.state)
        assertEquals(1, second.lead.qualificationHistory.size)
        assertNotNull(second.assessment)
    }

    @Test
    fun `a seller decline reassesses straight into DISQUALIFIED with its own reason`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW).let { assess(it) }
        val declined = LeadQualificationEngine.reassess(
            lead,
            T1,
            ACTOR,
            LeadQualificationInputs.fromLead(lead).copy(sellerDeclined = true)
        )
        assertTrue(declined is LeadQualificationResult.Applied)
        declined as LeadQualificationResult.Applied
        assertEquals(LeadQualificationState.DISQUALIFIED, declined.transition.to)
        assertEquals(LeadQualificationReason.SELLER_DECLINED, declined.transition.reason)
        assertEquals(LeadQualificationState.QUALIFIED, declined.transition.from)
        assertFalse(declined.transition.isImprovement)
    }

    @Test
    fun `stale evidence decays the state instead of keeping an offer justified`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW).let { assess(it) }
        assertFalse(lead.qualification!!.isStale(T0 + 10L * CrmTime.MILLIS_PER_DAY))

        val stillFresh = LeadQualificationEngine.decayIfStale(lead, T0 + 10L * CrmTime.MILLIS_PER_DAY, ACTOR)
        assertTrue(stillFresh is LeadQualificationResult.NoOp)

        val decayed = LeadQualificationEngine.decayIfStale(lead, T0 + 40L * CrmTime.MILLIS_PER_DAY, ACTOR)
        assertTrue(decayed is LeadQualificationResult.Applied)
        decayed as LeadQualificationResult.Applied
        assertEquals(LeadQualificationState.WARM, decayed.lead.qualificationState)
        assertEquals(LeadQualificationReason.EVIDENCE_DECAYED, decayed.transition.reason)
        assertTrue(decayed.transition.detail!!.contains("40 days old"))

        val decayedAgain = LeadQualificationEngine.decayIfStale(
            decayed.lead, T0 + 80L * CrmTime.MILLIS_PER_DAY, ACTOR
        )
        assertEquals(
            LeadQualificationState.NURTURE,
            (decayedAgain as LeadQualificationResult.Applied).lead.qualificationState
        )

        val floor = LeadQualificationEngine.decayIfStale(
            decayedAgain.lead, T0 + 120L * CrmTime.MILLIS_PER_DAY, ACTOR
        )
        assertTrue("NURTURE does not decay further", floor is LeadQualificationResult.NoOp)
    }

    @Test
    fun `an assessment that has never been made cannot decay`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val result = LeadQualificationEngine.decayIfStale(lead, T1, ACTOR)
        assertTrue(result is LeadQualificationResult.NoOp)
        assertNull((result as LeadQualificationResult.NoOp).assessment)
        assertEquals(LeadQualificationState.NOT_ASSESSED, result.state)
    }

    // ── Overrides ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an operator can override a computed state but must justify it`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW).let { assess(it) }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationEngine.overrideState(lead, LeadQualificationState.UNQUALIFIED, T1, ACTOR, "  ")
        }

        val overridden = LeadQualificationEngine.overrideState(
            lead, LeadQualificationState.UNQUALIFIED, T1, ACTOR, "seller is also listing with an agent"
        )
        assertTrue(overridden is LeadQualificationResult.Applied)
        overridden as LeadQualificationResult.Applied
        assertEquals(LeadQualificationState.UNQUALIFIED, overridden.lead.qualificationState)
        assertEquals(LeadQualificationReason.MANUAL_OVERRIDE, overridden.transition.reason)
        assertFalse(overridden.transition.reason.isAutomatic)
        assertTrue(overridden.assessment.warnings.any { it.contains("state overridden") })
        assertEquals(2, overridden.lead.qualificationHistory.size)
        assertEquals(LeadQualificationState.UNQUALIFIED, overridden.lead.qualification!!.state)
    }

    @Test
    fun `overriding a disqualified lead clears the disqualifier and vice versa`() {
        val disqualified = crmLead(status = LeadPipelineStatus.NEW)
            .let { assess(it) }
            .let {
                LeadQualificationEngine.reassess(
                    it, T1, ACTOR, LeadQualificationInputs.fromLead(it).copy(sellerDeclined = true)
                ) as LeadQualificationResult.Applied
            }
            .lead
        assertTrue(disqualified.qualificationState.isDisqualified)

        val cleared = LeadQualificationEngine.overrideState(
            disqualified, LeadQualificationState.WARM, T1 + 1, ACTOR, "seller called back and is interested again"
        ) as LeadQualificationResult.Applied
        assertFalse(cleared.assessment.disqualifiers.isNotEmpty())
        assertTrue(cleared.assessment.warnings.any { it.contains("disqualifiers cleared") })

        val reDisqualified = LeadQualificationEngine.overrideState(
            cleared.lead, LeadQualificationState.DISQUALIFIED, T1 + 2, ACTOR, "title defect discovered"
        ) as LeadQualificationResult.Applied
        assertEquals(
            CrmBlockerCodes.MANUAL_OVERRIDE_QUALIFICATION,
            reDisqualified.assessment.disqualifiers.single().code
        )
    }

    @Test
    fun `an override without an assessment is refused`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val refused = LeadQualificationEngine.overrideState(
            lead, LeadQualificationState.QUALIFIED, T1, ACTOR, "looks good to me"
        )
        assertTrue(refused is LeadQualificationResult.Illegal)
        assertTrue((refused as LeadQualificationResult.Illegal).message.contains("no assessment exists"))
    }

    // ── State machine ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `qualification moves are evidence driven but reasons are scoped`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW).let { assess(it) }
        assertEquals(LeadQualificationState.QUALIFIED, lead.qualificationState)

        // Every target is reachable when the evidence justifies it …
        LeadQualificationState.entries.filter { it != LeadQualificationState.NOT_ASSESSED }.forEach { target ->
            assertTrue(
                "QUALIFIED → $target must be allowed",
                LeadQualificationStateMachine.canTransition(LeadQualificationState.QUALIFIED, target)
            )
        }

        // … but a reason that does not justify the move is refused.
        val assessment = lead.qualification!!
        val refused = LeadQualificationStateMachine.transition(
            lead,
            LeadQualificationState.WARM,
            LeadQualificationReason.FIRST_ASSESSMENT,
            T1,
            ACTOR,
            assessment
        )
        assertTrue(refused is LeadQualificationResult.Illegal)
        assertTrue((refused as LeadQualificationResult.Illegal).message.contains("does not justify"))

        // NOT_ASSESSED can only be exited with FIRST_ASSESSMENT.
        val fresh = crmLead(status = LeadPipelineStatus.NEW)
        assertEquals(
            setOf(LeadQualificationReason.FIRST_ASSESSMENT),
            LeadQualificationStateMachine.allowedReasons(LeadQualificationState.NOT_ASSESSED, LeadQualificationState.WARM)
        )
        val badFirst = LeadQualificationStateMachine.transition(
            fresh,
            LeadQualificationState.WARM,
            LeadQualificationReason.EVIDENCE_ADDED,
            T0,
            ACTOR,
            assessment
        )
        assertTrue(badFirst is LeadQualificationResult.Illegal)
    }

    @Test
    fun `the state ladder exposes what each state permits`() {
        assertTrue(LeadQualificationState.QUALIFIED.permitsOffer)
        assertTrue(LeadQualificationState.WARM.permitsNegotiation)
        assertFalse(LeadQualificationState.WARM.permitsOffer)
        assertFalse(LeadQualificationState.NURTURE.isActionable)
        assertTrue(LeadQualificationState.DISQUALIFIED.isDisqualified)
        assertEquals(LeadQualificationState.NURTURE, LeadQualificationState.QUALIFIED.atMost(LeadQualificationState.NURTURE))
        assertEquals(LeadQualificationState.WARM, LeadQualificationState.WARM.atMost(LeadQualificationState.QUALIFIED))
        assertTrue(LeadQualificationState.WARM.improvesTo(LeadQualificationState.QUALIFIED))
        assertFalse(LeadQualificationState.QUALIFIED.improvesTo(LeadQualificationState.WARM))
        assertTrue(LeadQualificationState.QUALIFIED.improvesTo(LeadQualificationState.QUALIFIED).not())
    }

    // ── Policy consistency ──────────────────────────────────────────────────────────────────────

    @Test
    fun `policy rejects configurations that would skew the pipeline`() {
        val weights = LeadQualificationPolicy.DEFAULT_WEIGHTS

        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationPolicy(weights = weights + (LeadQualificationFactorId.SPREAD to 40))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationPolicy(weights = weights - LeadQualificationFactorId.SPREAD)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationPolicy(nurtureThreshold = 70, warmThreshold = 40, qualifiedThreshold = 65)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationPolicy(reachability = ReachabilityRubric(hasChannelPoints = 99))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationPolicy(minimumSpreadPctOfArv = 3.0, disqualifyingSpreadPctOfArv = 5.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationPolicy(stalenessWindowDays = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationPolicy(version = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationPolicy(wholesaleDiscountPct = 95.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationPolicy(repairContingencyPct = -1.0)
        }
    }

    @Test
    fun `inputs reject non-finite money and impossible engagement`() {
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationInputs(askingPriceUsd = Double.NaN, afterRepairValueUsd = 300_000.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationInputs(askingPriceUsd = -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadQualificationInputs(sellerConnected = true, sellerResponded = false)
        }
        assertThrows(IllegalArgumentException::class.java) { LeadQualificationInputs(askingPriceUsd = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { LeadQualificationEngine.evaluate(LeadQualificationInputs(), 0L) }
    }

    @Test
    fun `fromLead maps the aggregate without inventing facts`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW, communications = emptyList())
        val inputs = LeadQualificationInputs.fromLead(lead)
        assertEquals(180_000.0, inputs.askingPriceUsd!!, 0.001)
        assertEquals(300_000.0, inputs.afterRepairValueUsd!!, 0.001)
        assertEquals(45_000.0, inputs.effectiveRepairCostUsd()!!, 0.001)
        assertTrue(inputs.hasPropertyLink)
        assertTrue(inputs.hasPropertyAddress)
        assertTrue(inputs.hasUsableContactChannel)
        assertFalse(inputs.sellerResponded)
        assertFalse(inputs.sellerConnected)
        assertFalse(inputs.sellerDeclined)
        assertFalse(inputs.doNotContact)
        assertNull(inputs.withinServiceArea)
        assertEquals(SellerTimeline.WITHIN_30_DAYS, inputs.timeline)
        assertEquals(1, inputs.motivationSignals.size)
    }

    @Test
    fun `stale assessment ageing is measured in whole days`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW).let { assess(it) }
        val assessment = lead.qualification!!
        assertEquals(0L, assessment.ageDays(T0))
        assertEquals(29L, assessment.ageDays(T0 + 29L * CrmTime.MILLIS_PER_DAY))
        assertFalse(assessment.isStale(T0 + 30L * CrmTime.MILLIS_PER_DAY))
        assertTrue(assessment.isStale(T0 + 31L * CrmTime.MILLIS_PER_DAY))
        assertEquals(LeadQualificationPolicy.DEFAULT_VERSION, assessment.policyVersion)
        assertEquals(MotivationScoringPolicy.DEFAULT_VERSION, assessment.motivation.policyVersion)
    }

    @Test
    fun `timeline urgency feeds the score ladder`() {
        fun scoreFor(days: Int?): Int {
            val lead = crmLead(status = LeadPipelineStatus.NEW, timeline = timeline(days))
            return LeadQualificationEngine.evaluate(LeadQualificationInputs.fromLead(lead), T0)
                .factor(LeadQualificationFactorId.TIMELINE)!!.points
        }
        assertTrue("immediate must outscore 6 months", scoreFor(5) > scoreFor(120))
        assertTrue("6 months must outscore a year plus", scoreFor(120) > scoreFor(500))
        assertEquals(0, scoreFor(null))
        assertEquals(15, scoreFor(3))
    }
}
