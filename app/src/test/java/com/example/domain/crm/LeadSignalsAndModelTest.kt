package com.example.domain.crm

import com.example.domain.crm.CrmFixtures.ACTOR
import com.example.domain.crm.CrmFixtures.PHONE
import com.example.domain.crm.CrmFixtures.SELLER_ID
import com.example.domain.crm.CrmFixtures.T0
import com.example.domain.crm.CrmFixtures.T1
import com.example.domain.crm.CrmFixtures.condition
import com.example.domain.crm.CrmFixtures.crmLead
import com.example.domain.crm.CrmFixtures.motivationSignal
import com.example.domain.crm.CrmFixtures.phonePoint
import com.example.domain.crm.CrmFixtures.propertyLink
import com.example.domain.crm.CrmFixtures.seller
import com.example.domain.crm.CrmFixtures.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The CRM's value objects: motivation signals, property condition, seller timeline, priority, tags,
 * contact normalization, identifiers and the read-only offer bridge.
 */
class LeadSignalsAndModelTest {

    // ── Motivation ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `motivation aggregates distinct kinds, caps the bonus and decays old signals`() {
        val signals = listOf(
            motivationSignal(id = "S1", kind = MotivationSignalKind.TAX_DELINQUENT),
            // A second signal of the same kind must not stack.
            motivationSignal(id = "S2", kind = MotivationSignalKind.TAX_DELINQUENT, strength = MotivationStrength.WEAK),
            motivationSignal(
                id = "S3",
                kind = MotivationSignalKind.TIRED_LANDLORD,
                strength = MotivationStrength.MODERATE,
                sourceKind = MotivationSourceKind.SELLER_STATED,
                evidenceRef = "call-77"
            )
        )
        val aggregate = MotivationSignals.aggregate(signals, T0)

        // 26 (strong) + 16 (moderate) + 6 (distinct kind bonus) = 48
        assertEquals(48, aggregate.index)
        assertEquals(MotivationStrength.STRONG, aggregate.strength)
        assertEquals(MotivationSignalKind.TAX_DELINQUENT, aggregate.dominantKind)
        assertEquals(2, aggregate.distinctKinds)
        assertEquals(3, aggregate.freshSignals)
        assertEquals(0, aggregate.staleSignals)
        assertEquals(3, aggregate.documentedSignals)
        assertEquals(2, aggregate.timeBoxedSignals)
        assertTrue(aggregate.hasUrgentEvidence)
        assertTrue(aggregate.describe().contains("index=48"))
    }

    @Test
    fun `stale and future signals are counted but never scored`() {
        val old = motivationSignal(id = "OLD", at = T0, kind = MotivationSignalKind.PRE_FORECLOSURE)
        val future = motivationSignal(id = "FUTURE", at = T1 + 10L * CrmTime.MILLIS_PER_DAY, kind = MotivationSignalKind.DIVORCE)

        val asOfLater = MotivationSignals.aggregate(listOf(old), T0 + 400L * CrmTime.MILLIS_PER_DAY)
        assertEquals(0, asOfLater.index)
        assertEquals(1, asOfLater.staleSignals)
        assertFalse(asOfLater.hasSignals && asOfLater.index > 0)

        val withFuture = MotivationSignals.aggregate(listOf(future), T0)
        assertEquals(0, withFuture.index)
        assertEquals(1, withFuture.staleSignals)

        val empty = MotivationSignals.aggregate(emptyList(), T0)
        assertTrue(empty.isEmpty)
        assertNull(empty.dominantKind)
        assertFalse(empty.hasUrgentEvidence)
    }

    @Test
    fun `motivation weight policy is validated and scalable`() {
        assertEquals(50, MotivationSignals.scaleToPoints(50, 100))
        assertEquals(7, MotivationSignals.scaleToPoints(26, 25))
        assertEquals(0, MotivationSignals.scaleToPoints(0, 25))
        assertEquals(25, MotivationSignals.scaleToPoints(100, 25))
        assertThrows(IllegalArgumentException::class.java) { MotivationSignals.scaleToPoints(101, 25) }

        assertThrows(IllegalArgumentException::class.java) {
            MotivationScoringPolicy(weightByStrength = mapOf(MotivationStrength.WEAK to 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotivationScoringPolicy(freshWindowDays = 400, staleAfterDays = 100)
        }
        assertThrows(IllegalArgumentException::class.java) { MotivationScoringPolicy(version = " ") }
        assertThrows(IllegalArgumentException::class.java) { MotivationScoringPolicy(maximumDistinctKinds = 0) }
        assertThrows(IllegalArgumentException::class.java) { MotivationScoringPolicy(distinctKindBonus = -1) }
    }

    @Test
    fun `a signal must describe when, who and why`() {
        assertThrows(IllegalArgumentException::class.java) {
            motivationSignal(kind = MotivationSignalKind.OTHER, detail = null)
        }
        assertTrue(
            motivationSignal(kind = MotivationSignalKind.OTHER, detail = "estate attorney mentioned a sale")
                .describe().contains("OTHER")
        )
        assertThrows(IllegalArgumentException::class.java) {
            MotivationSignal(
                id = "S",
                kind = MotivationSignalKind.DIVORCE,
                strength = MotivationStrength.WEAK,
                sourceKind = MotivationSourceKind.LEAD_VENDOR,
                observedAtEpochMillis = T1,
                recordedAtEpochMillis = T0,
                recordedBy = ACTOR
            )
        }
    }

    // ── Condition ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `condition classification is worst evidence wins`() {
        assertEquals(
            PropertyCondition.UNKNOWN,
            PropertyConditionClassifier.classify(emptySet())
        )
        assertEquals(
            PropertyCondition.DISTRESSED,
            PropertyConditionClassifier.classify(
                setOf(ConditionIndicator.UNIT_INTERIOR_GUTTED, ConditionIndicator.NEW_ROOF, ConditionIndicator.NEW_WINDOWS)
            )
        )
        assertEquals(
            PropertyCondition.MAJOR_REHAB,
            PropertyConditionClassifier.classify(
                setOf(ConditionIndicator.FOUNDATION_ISSUES, ConditionIndicator.UPDATED_KITCHEN)
            )
        )
        // Positive-only evidence cannot claim better than the policy ceiling.
        assertEquals(
            PropertyCondition.RENT_READY,
            PropertyConditionClassifier.classify(
                setOf(ConditionIndicator.NEW_HVAC, ConditionIndicator.UPDATED_BATH)
            )
        )
        assertEquals(
            PropertyCondition.COSMETIC_UPDATES,
            PropertyConditionClassifier.classify(
                setOf(ConditionIndicator.UPDATED_BATH),
                ConditionClassificationPolicy(positiveOnlyCeiling = PropertyCondition.COSMETIC_UPDATES)
            )
        )
    }

    @Test
    fun `condition assessment carries evidence, severity and an estimate`() {
        val assessed = condition(indicators = setOf(ConditionIndicator.HVAC_FAILURE), squareFeet = 1_200)
        assertEquals(PropertyCondition.MINOR_REHAB, assessed.condition)
        assertEquals(RehabSeverity.MINOR, assessed.severity)
        assertFalse(assessed.isVerified)
        // MINOR_REHAB bucket midpoint 17.5 $/sqft over 1,200 sqft.
        assertEquals(21_000.0, assessed.effectiveRehabEstimateUsd!!, 0.001)
        assertTrue(assessed.describe().contains("MINOR_REHAB"))

        val explicit = assessed.copy(rehabEstimateUsd = 30_000.0)
        assertEquals(30_000.0, explicit.effectiveRehabEstimateUsd!!, 0.001)
        assertEquals(25.0, explicit.rehabPerSqFtUsd()!!, 0.001)

        val verified = PropertyConditionClassifier.assess(
            indicators = setOf(ConditionIndicator.MOLD),
            source = ConditionEvidenceSource.INSPECTION,
            assessedAtEpochMillis = T0,
            assessedBy = "inspector.lee",
            squareFeet = 1_200
        )
        assertTrue(verified.isVerified)
        assertEquals(PropertyCondition.MAJOR_REHAB, verified.condition)
        assertTrue(verified.severity.requiresInspection)
    }

    @Test
    fun `unknown condition never produces a repair estimate`() {
        val unknown = PropertyConditionAssessment(
            condition = PropertyCondition.UNKNOWN,
            source = ConditionEvidenceSource.NONE,
            assessedAtEpochMillis = T0,
            assessedBy = ACTOR,
            squareFeet = 1_500
        )
        assertNull(unknown.effectiveRehabEstimateUsd)
        assertEquals(0.0, PropertyCondition.TURNKEY.estimateRepairCostUsd(1_500)!!, 0.001)
        assertNull(PropertyCondition.MINOR_REHAB.estimateRepairCostUsd(null))
        assertTrue(PropertyCondition.DISTRESSED.isDistressed)
        assertFalse(PropertyCondition.TURNKEY.isWorkNeeded)
    }

    @Test
    fun `assessments merge towards the worse condition`() {
        val minor = condition(indicators = setOf(ConditionIndicator.HVAC_FAILURE))
        val severe = condition(indicators = setOf(ConditionIndicator.FOUNDATION_ISSUES))
        assertEquals(severe.condition, minor.worstOf(severe).condition)
        assertEquals(severe.condition, severe.worstOf(minor).condition)
        assertThrows(IllegalArgumentException::class.java) {
            PropertyConditionAssessment(
                condition = PropertyCondition.TURNKEY,
                source = ConditionEvidenceSource.NONE,
                assessedAtEpochMillis = T0,
                assessedBy = ACTOR
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConditionClassificationPolicy(indicatorFloors = emptyMap())
        }
    }

    // ── Timeline ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `timeline buckets the stated days deterministically`() {
        assertNull(SellerTimeline.fromDays(null).horizonDays)
        assertEquals(SellerTimeline.UNKNOWN, SellerTimeline.fromDays(null))
        assertEquals(SellerTimeline.IMMEDIATE, SellerTimeline.fromDays(0))
        assertEquals(SellerTimeline.IMMEDIATE, SellerTimeline.fromDays(7))
        assertEquals(SellerTimeline.WITHIN_30_DAYS, SellerTimeline.fromDays(8))
        assertEquals(SellerTimeline.WITHIN_30_DAYS, SellerTimeline.fromDays(30))
        assertEquals(SellerTimeline.WITHIN_90_DAYS, SellerTimeline.fromDays(31))
        assertEquals(SellerTimeline.WITHIN_90_DAYS, SellerTimeline.fromDays(90))
        assertEquals(SellerTimeline.WITHIN_6_MONTHS, SellerTimeline.fromDays(91))
        assertEquals(SellerTimeline.WITHIN_6_MONTHS, SellerTimeline.fromDays(180))
        assertEquals(SellerTimeline.WITHIN_12_MONTHS, SellerTimeline.fromDays(181))
        assertEquals(SellerTimeline.WITHIN_12_MONTHS, SellerTimeline.fromDays(365))
        assertEquals(SellerTimeline.OVER_1_YEAR, SellerTimeline.fromDays(366))
        assertTrue(SellerTimeline.IMMEDIATE.isUrgent)
        assertFalse(SellerTimeline.OVER_1_YEAR.isUrgent)
        assertTrue(SellerTimeline.WITHIN_12_MONTHS.isLongHorizon)
        assertFalse(SellerTimeline.UNKNOWN.isKnown)
        assertTrue(SellerTimeline.MOST_URGENT_FIRST.first() == SellerTimeline.IMMEDIATE)
    }

    @Test
    fun `a timeline claim must match its bucket and its source`() {
        val fact = SellerTimelineFact.sellerStated(45, T0, ACTOR, "wants to be out before school starts")
        assertEquals(SellerTimeline.WITHIN_90_DAYS, fact.timeline)
        assertEquals(TimelineSource.SELLER_STATED, fact.source)
        assertTrue(fact.isReliable)
        assertTrue(fact.isKnown)

        assertThrows(IllegalArgumentException::class.java) {
            SellerTimelineFact(
                timeline = SellerTimeline.IMMEDIATE,
                statedDaysToClose = 90,
                source = TimelineSource.SELLER_STATED,
                capturedAtEpochMillis = T0,
                capturedBy = ACTOR
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SellerTimelineFact(
                timeline = SellerTimeline.WITHIN_30_DAYS,
                source = TimelineSource.UNKNOWN,
                capturedAtEpochMillis = T0,
                capturedBy = ACTOR
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SellerTimelineFact(
                timeline = SellerTimeline.UNKNOWN,
                source = TimelineSource.DOCUMENTED,
                capturedAtEpochMillis = T0,
                capturedBy = ACTOR
            )
        }
        assertFalse(SellerTimelineFact.unknown(T0).isKnown)
    }

    // ── Priority ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `priority is derived from facts and explains itself`() {
        val inputs = LeadPriorityInputs(
            pipelineStatus = LeadPipelineStatus.RESPONDED,
            qualificationState = LeadQualificationState.QUALIFIED,
            qualificationScore = 76,
            motivationIndex = 48,
            timeline = SellerTimeline.WITHIN_30_DAYS,
            conditionSeverity = RehabSeverity.MINOR,
            hasSellerResponse = true,
            hasSellerConversation = true,
            followUpState = FollowUpState.DUE,
            daysInStage = 4
        )
        val evaluation = LeadPriorityEngine.derive(inputs)
        // 27 (qualification 76/100 of 35) + 10 (motivation 48/100 of 20) + 13 (timeline 85/100 of 15)
        // + 20 (conversation) + 6 (condition MINOR = 2/3 of 10) + 8 (due follow-up) = 84
        assertEquals(84, evaluation.score)
        assertEquals(LeadPriority.URGENT, evaluation.priority)
        assertEquals(6, evaluation.factors.size)
        assertEquals(27, evaluation.factor(LeadPriorityFactorId.QUALIFICATION)!!.points)
        assertTrue(evaluation.reasons.any { it.contains("seller has responded") })
        assertTrue(evaluation.reasons.any { it.contains("deadline") })
        assertTrue(evaluation.describe().contains("URGENT"))
    }

    @Test
    fun `an overdue follow-up and no follow-up both raise attention, a terminal lead never does`() {
        val base = LeadPriorityInputs(
            pipelineStatus = LeadPipelineStatus.NEGOTIATING,
            qualificationState = LeadQualificationState.WARM,
            qualificationScore = 50,
            motivationIndex = 20,
            timeline = SellerTimeline.WITHIN_90_DAYS
        )
        val none = LeadPriorityEngine.derive(base)
        val due = LeadPriorityEngine.derive(base.copy(followUpState = FollowUpState.DUE))
        val overdue = LeadPriorityEngine.derive(base.copy(followUpState = FollowUpState.OVERDUE))
        assertTrue(overdue.score > due.score)
        assertTrue(due.score > none.score)
        assertTrue(none.reasons.any { it.contains("no follow-up scheduled") })

        val terminal = LeadPriorityEngine.derive(
            base.copy(pipelineStatus = LeadPipelineStatus.CLOSED, followUpState = FollowUpState.OVERDUE)
        )
        assertEquals(LeadPriority.LOW, terminal.priority)
        assertEquals(0, terminal.score)
        assertTrue(terminal.reasons.single().contains("terminal status"))
    }

    @Test
    fun `a frozen contact path caps priority however good the deal looks`() {
        val inputs = LeadPriorityInputs(
            pipelineStatus = LeadPipelineStatus.NEGOTIATING,
            qualificationState = LeadQualificationState.QUALIFIED,
            qualificationScore = 95,
            motivationIndex = 90,
            timeline = SellerTimeline.IMMEDIATE,
            hasSellerResponse = true,
            hasSellerConversation = true,
            isContactFrozen = true,
            followUpState = FollowUpState.OVERDUE
        )
        val evaluation = LeadPriorityEngine.derive(inputs)
        assertEquals(LeadPriority.LOW, evaluation.priority)
        assertTrue(evaluation.score > 50)
        assertTrue(evaluation.reasons.any { it.contains("contact is frozen") })
    }

    @Test
    fun `priority thresholds and policy are validated`() {
        val policy = LeadPriorityPolicy.DEFAULT
        assertEquals(LeadPriority.URGENT, policy.bandFor(100))
        assertEquals(LeadPriority.HIGH, policy.bandFor(50))
        assertEquals(LeadPriority.NORMAL, policy.bandFor(25))
        assertEquals(LeadPriority.LOW, policy.bandFor(0))
        assertThrows(IllegalArgumentException::class.java) {
            LeadPriorityPolicy(highThreshold = 80, urgentThreshold = 70)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadPriorityPolicy(dueFollowUpBonus = 20, overdueFollowUpBonus = 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadPriorityPolicy(weights = LeadPriorityPolicy.DEFAULT_WEIGHTS - LeadPriorityFactorId.ENGAGEMENT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadPriorityPolicy(weights = LeadPriorityPolicy.DEFAULT_WEIGHTS + (LeadPriorityFactorId.QUALIFICATION to 40))
        }
        assertThrows(IllegalArgumentException::class.java) { LeadPriorityInputs(pipelineStatus = LeadPipelineStatus.NEW, qualificationScore = 200) }
    }

    @Test
    fun `priority inputs come from the lead without reading a clock`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW).let { CrmFixtures.assess(it) }
        val inputs = LeadPriorityInputs.fromLead(lead, T0)
        assertEquals(LeadPipelineStatus.NEW, inputs.pipelineStatus)
        assertEquals(LeadQualificationState.QUALIFIED, inputs.qualificationState)
        assertEquals(76, inputs.qualificationScore)
        assertEquals(100, inputs.maximumQualificationScore)
        assertEquals(26, inputs.motivationIndex)
        assertEquals(SellerTimeline.WITHIN_30_DAYS, inputs.timeline)
        assertEquals(RehabSeverity.MINOR, inputs.conditionSeverity)
        assertTrue(inputs.hasSellerResponse)
        assertFalse(inputs.isContactFrozen)
        assertEquals(FollowUpState.UNSCHEDULED, inputs.followUpState)
        assertEquals(0L, inputs.daysInStage)
    }

    // ── Tags ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `tags normalize to one canonical form`() {
        assertEquals("cash-buyer", LeadTags.normalize("  Cash Buyer  "))
        assertEquals("probate", LeadTags.normalize("probate_"))
        assertEquals("needs-roof", LeadTags.normalize("needs   roof"))
        assertEquals("tired-landlord", LeadTags.normalize("tired--landlord"))
        assertEquals("hot", LeadTags.normalize("HOT"))
        assertNull(LeadTags.normalize("   "))
        assertNull(LeadTags.normalize("!!!"))
        assertEquals(32, LeadTags.normalize("x".repeat(60))!!.length)
        assertTrue(LeadTags.isCanonical("cash-buyer"))
        assertFalse(LeadTags.isCanonical("Cash Buyer"))
        assertTrue(LeadTags.isKnown("probate"))
        assertFalse(LeadTags.isKnown("my-custom-tag"))
    }

    @Test
    fun `normalizing a tag collection dedupes, caps and reports what it dropped`() {
        val outcome = LeadTags.normalizeAll(listOf("Vacant", "vacant ", "Probate", "!!!", "zebra", "absentee-owner"), maxTags = 4)
        assertEquals(setOf("absentee-owner", "probate", "vacant", "zebra"), outcome.tags)
        assertTrue(outcome.hasDropped)
        assertTrue(outcome.droppedRawValues.contains("!!!"))

        // Known tags are kept ahead of custom ones when the cap bites.
        val capped = LeadTags.normalizeAll(listOf("zzz-custom", "vacant", "aaa-custom"), maxTags = 2)
        assertEquals(setOf("vacant", "aaa-custom"), capped.tags)
    }

    @Test
    fun `tag policy is validated`() {
        assertThrows(IllegalArgumentException::class.java) { LeadTagPolicy(maxTagsPerLead = 0) }
        assertThrows(IllegalArgumentException::class.java) { LeadTagPolicy(maxTagsPerLead = 5, warnAboveTagCount = 10) }
    }

    @Test
    fun `a lead only accepts canonical tags`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        assertThrows(IllegalArgumentException::class.java) { lead.copy(tags = setOf("Cash Buyer")) }
        assertTrue(lead.hasTag("TAX-DELINQUENT"))
        assertFalse(lead.hasTag("vacant"))
        val tagged = lead.withTag("Vacant", ACTOR, T1).withTag("!!!", ACTOR, T1)
        assertTrue(tagged.hasTag("vacant"))
        assertEquals(3, tagged.tags.size)
        assertEquals(2, tagged.withoutTag("vacant", ACTOR, T1).tags.size)
        assertFalse(tagged.withoutTag("vacant", ACTOR, T1).hasTag("vacant"))
    }

    // ── Contact normalization and sellers ───────────────────────────────────────────────────────

    @Test
    fun `phones and emails normalize to one identity`() {
        assertEquals("+15125550123", ContactNormalizer.normalizePhone("+1 (512) 555-0123"))
        assertEquals("+15125550123", ContactNormalizer.normalizePhone("5125550123"))
        assertEquals("+15125550123", ContactNormalizer.normalizePhone("1-512-555-0123"))
        assertEquals("+442071838750", ContactNormalizer.normalizePhone("+44 20 7183 8750"))
        assertNull(ContactNormalizer.normalizePhone("555-0123"))
        assertNull(ContactNormalizer.normalizePhone("not a phone"))
        assertTrue(ContactNormalizer.looksLikePhone(PHONE))
        assertEquals("0123", ContactNormalizer.phoneLastFour(PHONE))
        assertNull(ContactNormalizer.phoneLastFour("nope"))

        assertEquals("seller@example.com", ContactNormalizer.normalizeEmail(" Seller@Example.COM "))
        assertNull(ContactNormalizer.normalizeEmail("seller@example"))
        assertTrue(ContactNormalizer.looksLikeEmail("a@b.co"))
        assertEquals(
            "2450 Oak St, Austin, TX 78704",
            ContactNormalizer.normalizeMailingAddress(CrmFixtures.address())
        )
    }

    @Test
    fun `a contact point must already be normalized`() {
        assertThrows(IllegalArgumentException::class.java) {
            phonePoint(value = "(512) 555-0123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            phonePoint(value = "seller@example", channel = ContactChannel.EMAIL)
        }
        assertThrows(IllegalArgumentException::class.java) {
            phonePoint(channel = ContactChannel.NONE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SellerContactPoint(
                channel = ContactChannel.PHONE,
                value = "+15125550123",
                verifiedAtEpochMillis = T0 - 1,
                addedAtEpochMillis = T0
            )
        }
    }

    @Test
    fun `seller reachability respects per channel opt-outs and legal freezes`() {
        val mixed = seller(
            contactPoints = listOf(
                phonePoint(doNotContact = true),
                phonePoint(value = "+15125550999", channel = ContactChannel.SMS, primary = false)
            )
        )
        assertTrue(mixed.isContactable)
        assertEquals(ContactChannel.SMS, mixed.preferredContactPoint!!.channel)
        assertFalse(mixed.isFrozen)

        val frozen = mixed.withRestrictions(SellerContactRestrictions(doNotContactRequested = true), T1)
        assertTrue(frozen.isFrozen)
        assertFalse(frozen.isContactable)
        assertThrows(IllegalArgumentException::class.java) {
            mixed.withRestrictions(SellerContactRestrictions(litigationHold = true), T0 - 1)
        }

        val entityOwned = seller().copy(entityType = SellerEntityType.LLC)
        assertTrue(entityOwned.isEntityOwned)
        assertTrue(SellerEntityType.LLC.requiresSigningAuthorityCheck)
        assertTrue(SellerRole.OWNER.isDecisionMakerRole)
        assertFalse(SellerRole.AGENT.isDecisionMakerRole)
        assertTrue(seller().describe().contains("contacts=phone"))
    }

    @Test
    fun `seller records validate their own structure`() {
        assertThrows(IllegalArgumentException::class.java) {
            seller(contactPoints = listOf(phonePoint(), phonePoint()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            seller(contactPoints = listOf(phonePoint(primary = true), phonePoint(value = "+15125550999", primary = true)))
        }
        assertThrows(IllegalArgumentException::class.java) { seller(name = " ") }
        val postal = PostalAddress.orNull("2450 Oak St", null, "Austin", "TX", "7870")
        assertNull("an incomplete address is refused, not guessed", postal)
        assertNotNull(PostalAddress.orNull("2450 Oak St", null, "Austin", "TX", "78704"))
        assertThrows(IllegalArgumentException::class.java) { PostalAddress("", null, "Austin", "TX", "78704") }
    }

    @Test
    fun `skip trace references are inert data and the port is disabled`() {
        val reference = SkipTraceReference(
            provider = "vendor.example",
            providerRecordId = "rec-1",
            fetchedAtEpochMillis = T0,
            confidence = 0.94,
            matchCount = 1
        )
        assertTrue(reference.isAutoApplicable)
        assertFalse(reference.copy(matchCount = 3).isAutoApplicable)
        assertThrows(IllegalArgumentException::class.java) { reference.copy(confidence = 1.4) }

        assertThrows(IllegalArgumentException::class.java) {
            SkipTraceRequest(sellerId = SELLER_ID, fullName = null, mailingAddress = null, propertyAddress = null)
        }

        assertFalse(DisabledSkipTracePort.isEnabled)
        assertEquals("disabled", DisabledSkipTracePort.providerName)
        val result = kotlinx.coroutines.runBlocking {
            DisabledSkipTracePort.lookup(
                SkipTraceRequest(
                    sellerId = SELLER_ID,
                    fullName = "Dana Whitfield",
                    mailingAddress = null,
                    propertyAddress = CrmFixtures.address()
                )
            )
        }
        assertTrue(result is SkipTraceResult.NotConfigured)
        assertNull((result as SkipTraceResult.NotConfigured).requestedProvider)
    }

    // ── Source attribution ──────────────────────────────────────────────────────────────────────

    @Test
    fun `source attribution deduplicates capture events and prices channels`() {
        val first = source(kind = LeadSourceKind.DIRECT_MAIL, listName = "Absentee 2026Q1", detail = "postcard lane 4")
        val second = first.copy()
        assertEquals(first.deduplicationKey(), second.deduplicationKey())
        assertFalse(
            "a different campaign day is a different capture",
            first.deduplicationKey() == first.copy(capturedAtEpochMillis = T0 + CrmTime.MILLIS_PER_DAY).deduplicationKey()
        )
        assertTrue(first.deduplicationKey().startsWith("DIRECT_MAIL||absentee-2026q1|"))
        assertTrue(first.isPaid)
        assertTrue(first.isOutbound)
        assertTrue(first.isDistressDriven)

        val paid = first.copy(costMicros = 2_500_000L)
        assertEquals(2.5, paid.costUsd!!, 0.001)
        assertTrue(paid.describe().contains("list=Absentee 2026Q1"))
        assertTrue(LeadSourceKind.PUBLIC_RECORDS.isDistressChannel)
        assertFalse(LeadSourceKind.WEBSITE.isOutbound)
        assertTrue(LeadSourceCategory.OUTBOUND.isOutboundContact)
        assertFalse(LeadSourceCategory.INBOUND.isOutboundContact)
        assertThrows(IllegalArgumentException::class.java) { first.copy(costMicros = -1) }
    }

    // ── Identifiers ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `identifiers are prefixed and reproducible with an injected source`() {
        val factory = LeadIdFactory { UUID.fromString("12345678-1234-5678-1234-567812345678") }
        assertEquals("LEAD-123456781234", factory.newLeadId())
        assertEquals("SELLER-123456781234", factory.newSellerId())
        assertEquals("COMM-123456781234", factory.newCommunicationId())
        assertEquals("NOTE-123456781234", factory.newNoteId())
        assertEquals("SIG-123456781234", factory.newMotivationSignalId())
        assertEquals("CTR-123456781234", factory.newContractId())
        assertEquals("FU-123456781234", factory.newFollowUpId())
        assertTrue(LeadIdFactory.isLeadId("LEAD-ABC"))
        assertFalse(LeadIdFactory.isLeadId("SELLER-ABC"))
        assertTrue(LeadIdFactory.DEFAULT.newLeadId().startsWith("LEAD-"))
    }

    // ── Offer bridge ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the offer bridge only moves leads for seller decisions`() {
        assertNull("a draft offer is not news", LeadOfferBridge.transitionFor(LeadOfferBridge.STATUS_DRAFT))
        assertNull(LeadOfferBridge.transitionFor(LeadOfferBridge.STATUS_GENERATED))
        assertNull(LeadOfferBridge.transitionFor(LeadOfferBridge.STATUS_READY))
        assertNull("a transport failure is not a seller decision", LeadOfferBridge.transitionFor(LeadOfferBridge.STATUS_FAILED))
        assertNull("unknown statuses never guess", LeadOfferBridge.transitionFor("SOMETHING_ELSE"))

        val sent = LeadOfferBridge.transitionFor(" sent ")
        assertEquals(LeadPipelineStatus.OFFER_SENT, sent!!.target)
        assertEquals(LeadTransitionReason.OFFER_SUBMITTED, sent.reason)

        val opened = LeadOfferBridge.transitionFor(LeadOfferBridge.STATUS_OPENED)!!
        assertEquals(LeadPipelineStatus.OFFER_SENT, opened.target)

        val signed = LeadOfferBridge.transitionFor(LeadOfferBridge.STATUS_SIGNED)!!
        assertEquals(LeadPipelineStatus.UNDER_CONTRACT, signed.target)
        assertEquals(LeadTransitionReason.OFFER_ACCEPTED, signed.reason)

        val declined = LeadOfferBridge.transitionFor(LeadOfferBridge.STATUS_DECLINED)!!
        assertEquals(LeadPipelineStatus.LOST, declined.target)
        assertEquals(LeadTransitionReason.DEAL_LOST, declined.reason)

        assertTrue(LeadOfferBridge.isSellerDecision(LeadOfferBridge.STATUS_EXPIRED))
        assertFalse(LeadOfferBridge.isSellerDecision(LeadOfferBridge.STATUS_FAILED))
        assertTrue(LeadOfferBridge.isKnownStatus("signed"))
        assertFalse(LeadOfferBridge.isKnownStatus("weird"))
    }

    @Test
    fun `the bridge reference normalizes the status and refuses nonsense`() {
        val reference = LeadOfferBridge.reference("OFFER-1", 168_000.0, " sent ", T0, ACTOR)
        assertEquals(LeadOfferBridge.STATUS_SENT, reference.status)
        assertEquals(168_000.0, reference.amountUsd, 0.001)
        assertTrue(reference.describe().contains("OFFER-1"))
        assertThrows(IllegalArgumentException::class.java) {
            LeadOfferBridge.reference("OFFER-1", 168_000.0, "NOT_A_STATUS", T0, ACTOR)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadOfferBridge.reference("OFFER-1", 0.0, LeadOfferBridge.STATUS_SENT, T0, ACTOR)
        }
    }

    // ── Lead aggregate ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the aggregate rejects duplicated children and time travel`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        assertThrows(IllegalArgumentException::class.java) {
            lead.copy(sellers = lead.sellers + seller(id = CrmFixtures.SELLER_ID, name = "Duplicate"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            lead.copy(communications = lead.communications + lead.communications.first())
        }
        assertThrows(IllegalArgumentException::class.java) {
            lead.withPropertyLink(propertyLink(role = PropertyLinkRole.SUBJECT), ACTOR, T1).let {
                it.copy(propertyLinks = it.propertyLinks + propertyLink(propertyId = "PROP-2"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { lead.withTag("vacant", ACTOR, T0 - 1) }
        assertThrows(IllegalArgumentException::class.java) {
            lead.withFollowUp(LeadFollowUpPlanner.schedule(LeadPipelineStatus.NEW, T0, ACTOR)!!, ACTOR, T1)
                .let { it.copy(pipelineStatus = LeadPipelineStatus.LOST) }
        }
    }

    @Test
    fun `mutation helpers refresh the audit trail`() {
        val lead = crmLead(status = LeadPipelineStatus.NEW)
        val initialRevision = lead.audit.revision
        val updated = lead
            .withTag("vacant", ACTOR, T1)
            .withPriority(LeadPriority.HIGH, ACTOR, T1)
            .withAssignment("rep.blake", ACTOR, T1)
            .withNote(
                LeadNote(id = "NOTE-1", leadId = lead.id, body = "Left message with the estate attorney", author = ACTOR, createdAtEpochMillis = T1),
                ACTOR,
                T1
            )
        assertEquals(initialRevision + 4, updated.audit.revision)
        assertEquals(T1, updated.audit.updatedAtEpochMillis)
        assertEquals("rep.blake", updated.assignedTo)
        assertEquals(1, updated.notes.size)
        assertTrue(updated.notes.single().describe().contains("estate attorney"))
        assertTrue(updated.withCorrelationId("cycle-42", ACTOR, T1).correlationId == "cycle-42")
    }

    @Test
    fun `lead notes and communications validate their own shape`() {
        assertThrows(IllegalArgumentException::class.java) {
            LeadNote(id = "N", leadId = "LEAD-X", body = " ", author = ACTOR, createdAtEpochMillis = T0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeadNote(id = "N", leadId = "LEAD-X", body = "ok", author = ACTOR, createdAtEpochMillis = T0, tags = setOf("Bad Tag"))
        }
        assertTrue(
            LeadNote(id = "N", leadId = "LEAD-X", body = "ok", author = ACTOR, createdAtEpochMillis = T0, pinned = true)
                .describe().startsWith("[pinned]")
        )
    }
}
