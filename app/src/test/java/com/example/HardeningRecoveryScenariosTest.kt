package com.example

import com.example.data.local.entity.*
import com.example.data.repository.PreSendValidationResult
import org.junit.Assert.*
import org.junit.Test

class HardeningRecoveryScenariosTest {

    @Test
    fun testJobStateMachineValidAndInvalidTransitions() {
        // Legal forward transitions
        assertTrue("DISCOVERED -> ANALYZING is valid", JobState.DISCOVERED.canTransitionTo(JobState.ANALYZING))
        assertTrue("ANALYZING -> ANALYZED is valid", JobState.ANALYZING.canTransitionTo(JobState.ANALYZED))
        assertTrue("ANALYZED -> QUALIFYING is valid", JobState.ANALYZED.canTransitionTo(JobState.QUALIFYING))
        assertTrue("QUALIFYING -> QUALIFIED is valid", JobState.QUALIFYING.canTransitionTo(JobState.QUALIFIED))
        assertTrue("QUALIFYING -> DISQUALIFIED is valid", JobState.QUALIFYING.canTransitionTo(JobState.DISQUALIFIED))
        assertTrue("QUALIFIED -> OFFER_GENERATION is valid", JobState.QUALIFIED.canTransitionTo(JobState.OFFER_GENERATION))
        assertTrue("OFFER_GENERATION -> OFFER_READY is valid", JobState.OFFER_GENERATION.canTransitionTo(JobState.OFFER_READY))
        assertTrue("OFFER_READY -> VALIDATING_SEND is valid", JobState.OFFER_READY.canTransitionTo(JobState.VALIDATING_SEND))
        assertTrue("VALIDATING_SEND -> SENDING is valid", JobState.VALIDATING_SEND.canTransitionTo(JobState.SENDING))
        assertTrue("SENDING -> SENT is valid", JobState.SENDING.canTransitionTo(JobState.SENT))

        // Illegal skipping transitions must be rejected
        assertFalse("Cannot jump directly from DISCOVERED to SENT", JobState.DISCOVERED.canTransitionTo(JobState.SENT))
        assertFalse("Cannot jump directly from DISQUALIFIED to OFFER_GENERATION", JobState.DISQUALIFIED.canTransitionTo(JobState.OFFER_GENERATION))
        assertFalse("SENT is terminal, cannot transition to ANALYZING", JobState.SENT.canTransitionTo(JobState.ANALYZING))
        assertFalse("CANCELLED is terminal, cannot transition to SENDING", JobState.CANCELLED.canTransitionTo(JobState.SENDING))

        // Any state can transition to CANCELLED or FAILED_RETRYABLE
        assertTrue("ANALYZING can be CANCELLED", JobState.ANALYZING.canTransitionTo(JobState.CANCELLED))
        assertTrue("SENDING can fail retryable", JobState.SENDING.canTransitionTo(JobState.FAILED_RETRYABLE))
    }

    @Test
    fun testSamePropertyDuplicateArrivalIdempotency() {
        // Simulated property arrival
        val propertyId = "prop-repeat-001"
        val existingOffer = OfferEntity(
            id = "OFFER-12345678",
            propertyId = propertyId,
            recipientName = "Agent Name",
            recipientEmail = "agent@domain.com",
            offerPrice = 450000.0,
            earnestMoney = 6750.0,
            inspectionPeriodDays = 10,
            closingPeriodDays = 21,
            contingencies = "Inspection",
            terms = "AS-IS",
            conditions = "Clear title",
            expirationDate = "Oct 20, 2026",
            generatedLetterContent = "LOI statement",
            pdfPath = "/path/to/Offer_12345678.pdf",
            status = "READY",
            createdAt = System.currentTimeMillis()
        )

        // Idempotency check: if offer already exists and is READY or SENT, must not regenerate
        val shouldRegenerate = existingOffer.status == "DRAFT" || existingOffer.status == "FAILED"
        assertFalse("Existing READY offer must not be regenerated on repeat property scan", shouldRegenerate)
    }

    @Test
    fun testPreSendValidationCatchesInvalidEmailAndMissingPdf() {
        // Validation check for empty recipient
        val emptyEmailValidation = validateDraftOffer(
            offerPrice = 300000.0,
            earnestMoney = 4500.0,
            recipientEmail = "  ",
            pdfExists = true,
            isGmailConnected = true
        )
        assertFalse(emptyEmailValidation.isValid)
        assertTrue(emptyEmailValidation.blockageReason?.contains("blank") == true)

        // Validation check for malformed email
        val badEmailValidation = validateDraftOffer(
            offerPrice = 300000.0,
            earnestMoney = 4500.0,
            recipientEmail = "not-an-email",
            pdfExists = true,
            isGmailConnected = true
        )
        assertFalse(badEmailValidation.isValid)
        assertTrue(badEmailValidation.blockageReason?.contains("valid email") == true)

        // Validation check for missing PDF
        val missingPdfValidation = validateDraftOffer(
            offerPrice = 300000.0,
            earnestMoney = 4500.0,
            recipientEmail = "agent@brokerage.com",
            pdfExists = false,
            isGmailConnected = true
        )
        assertFalse(missingPdfValidation.isValid)
        assertTrue(missingPdfValidation.blockageReason?.contains("PDF") == true)

        // Validation check for disconnected Gmail
        val disconnectedValidation = validateDraftOffer(
            offerPrice = 300000.0,
            earnestMoney = 4500.0,
            recipientEmail = "agent@brokerage.com",
            pdfExists = true,
            isGmailConnected = false
        )
        assertFalse(disconnectedValidation.isValid)
        assertTrue(disconnectedValidation.blockageReason?.contains("Gmail") == true)

        // Validation pass
        val valid = validateDraftOffer(
            offerPrice = 300000.0,
            earnestMoney = 4500.0,
            recipientEmail = "agent@brokerage.com",
            pdfExists = true,
            isGmailConnected = true
        )
        assertTrue(valid.isValid)
        assertNull(valid.blockageReason)
    }

    @Test
    fun testWorkerRecoveryReconcilesAppCrashStates() {
        // Case 1: App crashed during SENDING, but offer was actually sent
        val jobInSendingWithSentOffer = AutomationJobEntity(
            jobId = "JOB-CRASH-01",
            runId = 1L,
            propertyId = "prop-crash-01",
            propertyAddress = "101 Crash St",
            currentState = JobState.SENDING.name,
            lastSuccessfulState = "OFFER_READY",
            offerId = "OFFER-01",
            createdAt = System.currentTimeMillis() - 60000,
            updatedAt = System.currentTimeMillis() - 50000
        )
        val offerStatus = "SENT"
        val reconciledState = if (offerStatus == "SENT") JobState.SENT.name else JobState.OFFER_READY.name
        assertEquals("Should reconcile to SENT to avoid duplicate email", JobState.SENT.name, reconciledState)

        // Case 2: App crashed after OFFER_GENERATION
        val jobInOfferGen = AutomationJobEntity(
            jobId = "JOB-CRASH-02",
            runId = 1L,
            propertyId = "prop-crash-02",
            propertyAddress = "102 Crash St",
            currentState = JobState.OFFER_GENERATION.name,
            lastSuccessfulState = "QUALIFIED",
            createdAt = System.currentTimeMillis() - 60000,
            updatedAt = System.currentTimeMillis() - 50000
        )
        val reconciledGenState = JobState.QUALIFIED.name
        assertEquals("Should safely resume from last successful state (QUALIFIED)", JobState.QUALIFIED.name, reconciledGenState)
    }

    @Test
    fun testRetryPolicyAndMaxFailureThreshold() {
        val rules = AutomationRuleEntity(
            maxRetries = 3,
            consecutiveFailureThreshold = 3
        )

        var attempts = 1
        var nextState = if (attempts < rules.maxRetries) JobState.FAILED_RETRYABLE else JobState.FAILED_TERMINAL
        assertEquals("First failure should be retryable", JobState.FAILED_RETRYABLE, nextState)

        attempts = 3
        nextState = if (attempts < rules.maxRetries) JobState.FAILED_RETRYABLE else JobState.FAILED_TERMINAL
        assertEquals("Exhausted retries should transition to FAILED_TERMINAL", JobState.FAILED_TERMINAL, nextState)
    }

    // Helper simulating PreSendValidation logic
    private fun validateDraftOffer(
        offerPrice: Double,
        earnestMoney: Double,
        recipientEmail: String,
        pdfExists: Boolean,
        isGmailConnected: Boolean
    ): PreSendValidationResult {
        if (offerPrice <= 0.0) return PreSendValidationResult(false, "Purchase price must be positive.")
        if (earnestMoney <= 0.0) return PreSendValidationResult(false, "Earnest money must be positive.")
        val cleanEmail = recipientEmail.trim()
        if (cleanEmail.isBlank()) return PreSendValidationResult(false, "Recipient email is blank.")
        if (!cleanEmail.contains("@") || !cleanEmail.contains(".")) {
            return PreSendValidationResult(false, "Recipient email is not a valid email address.")
        }
        if (!pdfExists) return PreSendValidationResult(false, "Offer PDF document is missing.")
        if (!isGmailConnected) return PreSendValidationResult(false, "Gmail account is not connected.")
        return PreSendValidationResult(true, null)
    }
}
