package com.example.domain.automation

import android.content.Context
import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.local.entity.OfferEntity
import com.example.data.repository.PreSendValidationResult
import com.example.domain.finance.FinancialResult

/** Injectable clock: every timestamp in the execution system comes from here, so tests can travel in time. */
interface AutomationClock {
    fun now(): Long
}

object SystemWallClock : AutomationClock {
    override fun now(): Long = System.currentTimeMillis()
}

/** Connectivity check used to decide whether network-bound steps may run. */
interface NetworkStatusProvider {
    fun isOnlineNow(): Boolean
}

/**
 * One discovered listing together with the identity-resolution decision taken for it.
 * [isNew] is true only when the listing is confidently a not-yet-known property.
 */
data class DiscoveredProperty(
    val bundle: NormalizedPropertyBundle,
    val isNew: Boolean,
    /** True when identity resolution matched ambiguously and a human should review it. */
    val needsReview: Boolean = false
)

/** Property discovery feed. */
interface PropertySourceGateway {
    suspend fun fetchBundles(limitPerSource: Int): List<NormalizedPropertyBundle>

    /**
     * Discovery with property-identity deduplication: [existing] are the canonical identities already
     * stored locally, so the feed can report which listings are genuinely new instead of re-importing
     * duplicates. Sources that cannot deduplicate report every bundle as new.
     */
    suspend fun fetchResolvedBundles(
        existing: Collection<com.example.domain.identity.CanonicalPropertyIdentity>,
        limitPerSource: Int
    ): List<DiscoveredProperty> = fetchBundles(limitPerSource).map { DiscoveredProperty(bundle = it, isNew = true) }
}

/** Deterministic underwriting (no external side effects - safe to re-run). */
interface FinancialAnalysisGateway {
    suspend fun runAnalysis(propertyId: String): FinancialResult
    suspend fun hasAnalysis(propertyId: String): Boolean
}

/** Offer generation / validation / delivery. Every method must be idempotent. */
interface OfferGateway {
    suspend fun findOffer(offerId: String): OfferEntity?
    suspend fun findOfferForProperty(propertyId: String): OfferEntity?
    suspend fun validateForSend(context: Context, offerId: String): PreSendValidationResult
    suspend fun generateDraftOffer(
        context: Context,
        propertyId: String,
        customPrice: Double?,
        suggestedRecipientEmail: String? = null
    ): OfferEntity
    suspend fun transmitOffer(context: Context, offerId: String): Boolean
}

/**
 * Durable scheduling of automation cycles.
 *
 * A persistent implementation (WorkManager) is what keeps long-running jobs alive across process
 * death; a process-local coroutine scope is only a fallback for environments where durable
 * scheduling is unavailable (unit tests, tooling).
 */
interface AutomationWorkScheduler {
    /** True when the scheduler survives process death (i.e. WorkManager). */
    val isPersistent: Boolean

    fun enqueueImmediateCycle(reason: String)

    fun schedulePeriodic(intervalMinutes: Int)

    fun cancelAll(reason: String)
}

/** Canonical trigger names persisted on [com.example.data.local.entity.AutomationRunEntity.trigger]. */
object CycleTrigger {
    const val MANUAL = "MANUAL"
    const val OPERATOR_NOW = "OPERATOR_NOW"
    const val WORKER = "WORKER"
    const val PERIODIC = "PERIODIC"
    const val PROCESS_START = "PROCESS_START"
    const val RULES_CHANGED = "RULES_CHANGED"
}
