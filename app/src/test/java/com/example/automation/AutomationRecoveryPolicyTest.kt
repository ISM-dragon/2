package com.example.automation

import com.example.data.local.entity.JobState
import com.example.domain.automation.AutomationRecoveryPolicy
import com.example.domain.automation.AutomationSteps
import com.example.domain.automation.RecoveryEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crash recovery decisions: where does an interrupted job continue, and what must never happen
 * again (duplicate offer generation, duplicate email, infinite crash loop)?
 */
class AutomationRecoveryPolicyTest {

    @Test
    fun `interrupted analysis resumes from durable evidence`() {
        val job = testJob(state = JobState.ANALYZING, lastSuccessful = JobState.DISCOVERED)

        val withAnalysis = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(analysisAvailable = true))
        assertEquals(JobState.ANALYZED, withAnalysis.targetState)

        val withoutAnalysis = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(analysisAvailable = false))
        assertEquals(JobState.DISCOVERED, withoutAnalysis.targetState)
    }

    @Test
    fun `interrupted qualification replays the pure step`() {
        val job = testJob(state = JobState.QUALIFYING, lastSuccessful = JobState.ANALYZED)
        val decision = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(analysisAvailable = true))
        assertEquals(JobState.ANALYZED, decision.targetState)
    }

    @Test
    fun `offer generation recovery links an offer that already exists instead of regenerating`() {
        val job = testJob(state = JobState.OFFER_GENERATION, lastSuccessful = JobState.QUALIFIED)

        val reusable = AutomationRecoveryPolicy.decide(
            job,
            RecoveryEvidence(offerStatus = "READY", offerId = "OFFER-77")
        )
        assertEquals(JobState.OFFER_READY, reusable.targetState)
        assertEquals("the existing offer must be adopted", "OFFER-77", reusable.offerId)

        val nothingGenerated = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(offerStatus = null))
        assertEquals(JobState.QUALIFIED, nothingGenerated.targetState)

        val alreadySent = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(offerStatus = "SENT", offerId = "OFFER-78"))
        assertEquals(JobState.SENT, alreadySent.targetState)
    }

    @Test
    fun `a declined offer is never auto resent`() {
        val job = testJob(state = JobState.OFFER_GENERATION, lastSuccessful = JobState.QUALIFIED)
        val decision = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(offerStatus = "DECLINED"))
        assertEquals(JobState.BLOCKED, decision.targetState)
        assertTrue(decision.requiresOperatorAction)
    }

    @Test
    fun `interrupted validation returns to the ready state without transmitting`() {
        val job = testJob(state = JobState.VALIDATING_SEND, lastSuccessful = JobState.OFFER_READY, offerId = "OFFER-1")
        val decision = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(offerStatus = "READY", offerId = "OFFER-1"))
        assertEquals(JobState.OFFER_READY, decision.targetState)
    }

    @Test
    fun `an ambiguous send is reconciled instead of blindly retried`() {
        val job = testJob(state = JobState.SENDING, lastSuccessful = JobState.OFFER_READY, offerId = "OFFER-1")
        val decision = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(offerStatus = "READY", offerId = "OFFER-1"))
        assertEquals(JobState.RECONCILING, decision.targetState)
    }

    @Test
    fun `delivery confirmed by the ledger completes the job without a second email`() {
        val job = testJob(state = JobState.SENDING, lastSuccessful = JobState.OFFER_READY, offerId = "OFFER-1")
        val decision = AutomationRecoveryPolicy.decide(
            job,
            RecoveryEvidence(
                offerStatus = "READY",
                offerId = "OFFER-1",
                sendRecordedByLedger = true,
                ledgerMessageId = "GMAIL-OFFER-1"
            )
        )
        assertEquals(JobState.SENT, decision.targetState)
        assertEquals("GMAIL-OFFER-1", decision.emailMessageId)
    }

    @Test
    fun `delivery confirmed by the offer status completes the job`() {
        val job = testJob(state = JobState.SENDING, lastSuccessful = JobState.OFFER_READY, offerId = "OFFER-1")
        val decision = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(offerStatus = "SENT", offerId = "OFFER-1"))
        assertEquals(JobState.SENT, decision.targetState)
    }

    @Test
    fun `a job that is not in flight is left alone`() {
        val ready = testJob(state = JobState.OFFER_READY, lastSuccessful = JobState.OFFER_READY, offerId = "OFFER-1")
        assertEquals(
            JobState.OFFER_READY,
            AutomationRecoveryPolicy.decide(ready, RecoveryEvidence(offerStatus = "READY")).targetState
        )

        val sent = testJob(state = JobState.SENT, lastSuccessful = JobState.SENT)
        assertEquals(JobState.SENT, AutomationRecoveryPolicy.decide(sent, RecoveryEvidence(offerStatus = "SENT")).targetState)
    }

    @Test
    fun `blocked jobs stay blocked and require operator action`() {
        val job = testJob(state = JobState.BLOCKED, lastSuccessful = JobState.OFFER_READY, offerId = "OFFER-1")
        val decision = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(offerStatus = "READY"))
        assertEquals(JobState.BLOCKED, decision.targetState)
        assertTrue(decision.requiresOperatorAction)
    }

    @Test
    fun `retryable failures resume the failed step until retries are exhausted`() {
        val retryable = testJob(
            state = JobState.FAILED_RETRYABLE,
            lastSuccessful = JobState.OFFER_READY,
            attempts = 1,
            maxRetries = 3,
            failedStep = AutomationSteps.SEND_OFFER,
            offerId = "OFFER-1"
        )
        assertEquals(JobState.SENDING, AutomationRecoveryPolicy.decide(retryable, RecoveryEvidence(offerStatus = "READY")).targetState)

        val exhausted = retryable.copy(attempts = 3)
        assertEquals(
            JobState.FAILED_TERMINAL,
            AutomationRecoveryPolicy.decide(exhausted, RecoveryEvidence(offerStatus = "READY")).targetState
        )
    }

    @Test
    fun `retryable failure without a recorded step falls back to the last successful milestone`() {
        val job = testJob(
            state = JobState.FAILED_RETRYABLE,
            lastSuccessful = JobState.QUALIFIED,
            attempts = 1,
            failedStep = null
        )
        val decision = AutomationRecoveryPolicy.decide(job, RecoveryEvidence())
        assertEquals(JobState.OFFER_GENERATION, decision.targetState)
    }

    @Test
    fun `the recovery budget stops a crash loop`() {
        val job = testJob(state = JobState.ANALYZING, recoveryCount = 5)
        val decision = AutomationRecoveryPolicy.decide(job, RecoveryEvidence(analysisAvailable = true), maxRecoveryAttempts = 5)
        assertEquals(JobState.FAILED_TERMINAL, decision.targetState)
        assertTrue(decision.reason.contains("Recovery budget"))
    }

    @Test
    fun `offer lifecycle helpers encode the delivery contract`() {
        assertTrue(com.example.domain.automation.OfferLifecycle.isDelivered("SENT"))
        assertTrue(com.example.domain.automation.OfferLifecycle.isDelivered("opened"))
        assertFalse(com.example.domain.automation.OfferLifecycle.isDelivered("READY"))
        assertFalse(com.example.domain.automation.OfferLifecycle.isDelivered(null))
        assertTrue(com.example.domain.automation.OfferLifecycle.isReusable("READY"))
        assertTrue(com.example.domain.automation.OfferLifecycle.isRegenerable("FAILED"))
        assertFalse(com.example.domain.automation.OfferLifecycle.isRegenerable("READY"))
        assertTrue(com.example.domain.automation.OfferLifecycle.isDead("EXPIRED"))
    }
}
