package com.example.domain.automation

import com.example.data.local.entity.AutomationJobEntity
import com.example.data.local.entity.JobState

/** Offer statuses that prove the email actually left the device. */
object OfferLifecycle {
    val DELIVERED = setOf("SENT", "OPENED", "SIGNED")
    val REUSABLE = setOf("READY", "GENERATED", "DRAFT", "SENT", "OPENED", "SIGNED", "SENDING")
    val DEAD = setOf("DECLINED", "EXPIRED")

    fun isDelivered(status: String?): Boolean = status != null && status.uppercase() in DELIVERED

    fun isReusable(status: String?): Boolean = status != null && status.uppercase() in REUSABLE

    fun isDead(status: String?): Boolean = status != null && status.uppercase() in DEAD

    /** `sendOffer` treats DRAFT/FAILED as regenerable, everything else is kept. */
    fun isRegenerable(status: String?): Boolean =
        status == null || status.uppercase() == "DRAFT" || status.uppercase() == "FAILED"
}

/**
 * Everything recovery is allowed to look at. All of it is *durable evidence*, never in-memory
 * state, because recovery runs after a process death.
 */
data class RecoveryEvidence(
    val analysisAvailable: Boolean = false,
    val offerStatus: String? = null,
    val offerId: String? = null,
    val emailMessageId: String? = null,
    /** The idempotency ledger recorded the send effect as SUCCEEDED. */
    val sendRecordedByLedger: Boolean = false,
    val ledgerMessageId: String? = null
) {
    val offerExists: Boolean get() = offerStatus != null
    val delivered: Boolean get() = OfferLifecycle.isDelivered(offerStatus) || sendRecordedByLedger
}

data class RecoveryDecision(
    val targetState: JobState,
    val reason: String,
    val offerId: String? = null,
    val emailMessageId: String? = null,
    val requiresOperatorAction: Boolean = false
)

/**
 * Decides where an interrupted job must continue.
 *
 * Rules of thumb:
 *  - never skip a durable milestone: resume from the earliest step whose result is not proven,
 *  - never regenerate an artifact that already exists (offer generation recovery),
 *  - never blindly re-send: an ambiguous send goes through [JobState.RECONCILING],
 *  - deterministic blockers stay blocked (operator action), they are not auto-retried.
 */
object AutomationRecoveryPolicy {

    fun decide(
        job: AutomationJobEntity,
        evidence: RecoveryEvidence,
        maxRecoveryAttempts: Int = RetryPolicy.DEFAULT.recoveryBudget
    ): RecoveryDecision {
        val current = job.state()
        val resumeFrom = JobState.fromName(job.lastSuccessfulState)

        if (job.recoveryCount >= maxRecoveryAttempts) {
            return RecoveryDecision(
                targetState = JobState.FAILED_TERMINAL,
                reason = "Recovery budget exhausted (${job.recoveryCount}/$maxRecoveryAttempts) - " +
                    "job kept crashing while in $current"
            )
        }

        if (current == JobState.BLOCKED) {
            return RecoveryDecision(
                targetState = JobState.BLOCKED,
                reason = job.blockageReason ?: "Blocked: operator action required",
                offerId = job.offerId,
                requiresOperatorAction = true
            )
        }

        if (current.isRetryableFailure) {
            if (job.attempts >= job.maxRetries) {
                return RecoveryDecision(
                    targetState = JobState.FAILED_TERMINAL,
                    reason = "Retries exhausted for ${job.failedStep ?: "step"} (${job.attempts}/${job.maxRetries})"
                )
            }
            val target = AutomationSteps.stateForStep(job.failedStep)
                ?: AutomationSteps.nextStateAfter(resumeFrom)
            return RecoveryDecision(
                targetState = target,
                reason = "Resuming failed step ${job.failedStep ?: target.name} after interruption",
                offerId = job.offerId,
                emailMessageId = job.emailMessageId
            )
        }

        if (!current.isInFlight) {
            return RecoveryDecision(
                targetState = current,
                reason = "State $current is not interrupted; nothing to reconcile",
                offerId = job.offerId
            )
        }

        return when (current) {
            JobState.ANALYZING -> if (evidence.analysisAvailable) {
                RecoveryDecision(JobState.ANALYZED, "Analysis had already been persisted before the crash")
            } else {
                RecoveryDecision(JobState.DISCOVERED, "Analysis never completed; restarting the job")
            }

            JobState.QUALIFYING -> RecoveryDecision(
                JobState.ANALYZED,
                "Qualification is pure and was interrupted; re-running from ANALYZED"
            )

            // The bug fixed here: an offer that already exists must be linked instead of
            // regenerated, otherwise the job loses its offerId and re-runs the whole pipeline.
            JobState.OFFER_GENERATION, JobState.OFFER_READY -> when {
                evidence.delivered -> RecoveryDecision(
                    JobState.SENT,
                    "Offer was already delivered before the crash",
                    offerId = evidence.offerId ?: job.offerId,
                    emailMessageId = evidence.ledgerMessageId ?: job.emailMessageId
                )
                OfferLifecycle.isDead(evidence.offerStatus) -> RecoveryDecision(
                    JobState.BLOCKED,
                    "Offer status ${evidence.offerStatus} requires operator action (not auto re-sent)",
                    offerId = evidence.offerId ?: job.offerId,
                    requiresOperatorAction = true
                )
                evidence.offerExists -> RecoveryDecision(
                    JobState.OFFER_READY,
                    "Reusing the offer that was generated before the crash (no duplicate generation)",
                    offerId = evidence.offerId ?: job.offerId
                )
                else -> RecoveryDecision(
                    JobState.QUALIFIED,
                    "Offer generation never persisted an offer; replaying the step",
                    offerId = job.offerId
                )
            }

            JobState.VALIDATING_SEND -> when {
                evidence.delivered -> RecoveryDecision(
                    JobState.SENT,
                    "Delivery confirmed while validating; completing the job",
                    offerId = evidence.offerId ?: job.offerId,
                    emailMessageId = evidence.ledgerMessageId ?: job.emailMessageId
                )
                evidence.offerExists -> RecoveryDecision(
                    JobState.OFFER_READY,
                    "Pre-send validation was interrupted before transmission; re-validating",
                    offerId = evidence.offerId ?: job.offerId
                )
                else -> RecoveryDecision(
                    JobState.QUALIFIED,
                    "Offer missing while validating send; replaying offer generation"
                )
            }

            // Ambiguous delivery: reconcile instead of assuming success or failure.
            JobState.SENDING, JobState.RECONCILING -> when {
                evidence.delivered -> RecoveryDecision(
                    JobState.SENT,
                    "Delivery confirmed via ${if (evidence.sendRecordedByLedger) "idempotency ledger" else "offer status"}",
                    offerId = evidence.offerId ?: job.offerId,
                    emailMessageId = evidence.ledgerMessageId ?: job.emailMessageId
                )
                evidence.offerExists -> RecoveryDecision(
                    JobState.RECONCILING,
                    "Delivery outcome unknown after interruption; reconciling before any re-send",
                    offerId = evidence.offerId ?: job.offerId
                )
                else -> RecoveryDecision(
                    JobState.QUALIFIED,
                    "Send was interrupted before an offer existed; replaying offer generation"
                )
            }

            else -> RecoveryDecision(current, "No recovery rule for $current")
        }
    }
}
