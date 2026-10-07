package com.example.domain.automation

import android.content.Context
import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.adapter.toCanonicalPropertyIdentity
import com.example.data.local.dao.AutomationDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.*
import com.example.data.repository.ImportOutcome
import com.example.data.repository.PropertyImportRepository
import com.example.domain.finance.FinancialResult
import com.example.domain.qualification.QualificationEngine
import com.example.domain.qualification.QualificationEvaluation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** High level engine status shown in the UI. */
enum class AutomationStatus {
    IDLE,
    RUNNING,
    SCANNING,
    ANALYZING,
    QUALIFYING,
    GENERATING_OFFERS,
    SENDING_OFFERS,
    RECONCILING,
    RECOVERING,
    RETRYING,
    PAUSED_OFFLINE,
    STOPPED,
    KILLED,
    ERROR
}

/** Persisted, fail-closed emergency stop. */
data class KillSwitchState(
    val engaged: Boolean = false,
    val reason: String? = null,
    val engagedAt: Long? = null
)

data class CycleRequest(
    val trigger: String = CycleTrigger.MANUAL,
    val correlationId: String = UUID.randomUUID().toString().take(8).uppercase(),
    val workerRunAttempt: Int = 0
)

data class CycleStats(
    val propertiesFound: Int = 0,
    val propertiesAnalyzed: Int = 0,
    val dealsQualified: Int = 0,
    val offersCreated: Int = 0,
    val offersSent: Int = 0,
    val jobsProcessed: Int = 0,
    val jobsRecovered: Int = 0,
    val jobsFailed: Int = 0,
    val jobsBlocked: Int = 0,
    val recoveryReconciled: Int = 0,
    val pausedOffline: Boolean = false
) {
    fun summary(): String =
        "Found: $propertiesFound, Analyzed: $propertiesAnalyzed, Qualified: $dealsQualified, " +
            "Offers: $offersCreated, Sent: $offersSent, Jobs: $jobsProcessed, " +
            "Recovered: $recoveryReconciled, Failed: $jobsFailed, Blocked: $jobsBlocked"
}

sealed interface CycleOutcome {
    val runId: Long?
    val reason: String?

    data class Completed(override val runId: Long?, val stats: CycleStats) : CycleOutcome {
        override val reason: String? get() = null
    }

    /**
     * [retryAfterMs] tells the durable scheduler (and the operator UI) that the cycle should be
     * attempted again shortly - it is how crash recovery is accelerated while another lease is
     * still held by a possibly-dead worker.
     */
    data class Skipped(
        override val reason: String,
        val killSwitch: Boolean = false,
        val retryAfterMs: Long? = null
    ) : CycleOutcome {
        override val runId: Long? get() = null
    }

    data class Retryable(override val runId: Long?, override val reason: String) : CycleOutcome

    data class Terminal(override val runId: Long?, override val reason: String) : CycleOutcome

    data class Killed(override val runId: Long?, override val reason: String) : CycleOutcome
}

data class RecoveryReport(
    val interruptedRuns: Int = 0,
    val reconciledJobs: Int = 0,
    val reclaimedLeases: Int = 0,
    val details: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = interruptedRuns == 0 && reconciledJobs == 0 && reclaimedLeases == 0
}

data class StartResult(val started: Boolean, val reason: String)

/** Cooperative halt: raised at every step boundary when the operator stopped the engine. */
class AutomationHaltException(message: String, val killSwitch: Boolean) : Exception(message)

/**
 * Durable automation execution system.
 *
 * Design contract:
 *  1. **Nothing long-running lives only in a process-local scope.** Cycles are executed by
 *     WorkManager work requests ([AutomationWorkScheduler]); the engine's own scope is used for
 *     housekeeping only (kill switch wiring + fallback when no durable scheduler exists).
 *  2. **State before side effect.** Every step persists its state transition first, so a process
 *     death is always reconciliable from the database.
 *  3. **Exactly-once side effects.** Jobs are claimed with a database lease, transitions use a
 *     compare-and-swap, and irreversible effects are deduplicated through
 *     [AutomationExecutionLedger].
 *  4. **Fail closed.** A persisted kill switch refuses all execution; unknown failures are
 *     bounded by retry/attempt budgets and then escalated to a terminal state.
 */
class AutomationEngine(
    private val context: Context,
    private val automationDao: AutomationDao,
    private val propertyDao: PropertyDao,
    private val propertySourceManager: PropertySourceGateway,
    /**
     * Persists discovered bundles through the canonical/dedup pipeline. Optional so test harnesses
     * can exercise the engine without the property data layer wired in.
     */
    private val propertyImporter: PropertyImportRepository? = null,
    private val financialRepository: FinancialAnalysisGateway,
    private val offerRepository: OfferGateway,
    private val networkMonitor: NetworkStatusProvider,
    private val scheduler: AutomationWorkScheduler,
    private val clock: AutomationClock = SystemWallClock,
    private val random: Random = Random.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    instanceId: String = UUID.randomUUID().toString().take(8)
) {
    /** Instance identity used as lease owner. */
    val leaseOwner: String = "engine-$instanceId"

    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val cycleMutex = Mutex()
    private val runningFlag = AtomicBoolean(false)
    private val audit = AutomationAuditLogger(automationDao, clock)
    private val ledger = AutomationExecutionLedger(automationDao, clock)

    private val _status = MutableStateFlow(AutomationStatus.IDLE)
    val status: StateFlow<AutomationStatus> = _status.asStateFlow()

    private val _currentTaskDescription = MutableStateFlow("Engine Standby")
    val currentTaskDescription: StateFlow<String> = _currentTaskDescription.asStateFlow()

    private val _killSwitch = MutableStateFlow(KillSwitchState())
    val killSwitch: StateFlow<KillSwitchState> = _killSwitch.asStateFlow()

    /** Only used for the non-persistent fallback path; never the primary execution vehicle. */
    private var fallbackJob: Job? = null

    private val retryPolicy: RetryPolicy
        get() = RetryPolicy.DEFAULT

    fun isRunning(): Boolean = runningFlag.get()

    // ------------------------------------------------------------------ lifecycle / kill switch

    /**
     * Arms the automation: persists the operator intent, schedules durable periodic work and asks
     * for an immediate cycle. Safe to call multiple times (idempotent).
     */
    suspend fun startAutomation(trigger: String = CycleTrigger.MANUAL): StartResult {
        ensureStateRow()
        val now = clock.now()
        val state = automationDao.getAutomationState()
        if (state?.killSwitchEngaged == true) {
            _killSwitch.value = KillSwitchState(true, state.killSwitchReason, state.killSwitchEngagedAt)
            return StartResult(false, "Global kill switch is engaged. Clear it before starting automation.")
        }
        automationDao.setEnabled(true, now)
        automationDao.updateProgress("Automation armed - work queued", "", "ARMED", now)
        _currentTaskDescription.value = "Automation armed (durable worker scheduled)"
        audit.info("START", "Autonomous intelligence cycle armed by operator (trigger=$trigger)", correlationId = trigger)

        val rules = automationDao.getRules() ?: AutomationRuleEntity()
        scheduler.schedulePeriodic(rules.scanIntervalMinutes)
        scheduler.enqueueImmediateCycle(trigger)
        automationDao.markCycleEnqueued(now)

        if (!scheduler.isPersistent) {
            // Fallback only: no durable scheduler available in this environment.
            startFallbackLoopIfNeeded(trigger)
        }
        return StartResult(true, "Automation armed and cycle enqueued")
    }

    /** Graceful stop: keeps already finished work, cancels queued/in-flight cycles. */
    suspend fun stopAutomation(reason: String = "Manual stop triggered by user") {
        ensureStateRow()
        val now = clock.now()
        automationDao.setEnabled(false, now)
        automationDao.updateProgress("Stopped: $reason", "", "STOPPED", now)
        runningFlag.set(false)
        scheduler.cancelAll(reason)
        fallbackJob?.cancel()
        fallbackJob = null
        _status.value = AutomationStatus.STOPPED
        _currentTaskDescription.value = "Automation Stopped: $reason"
        closeActiveRuns(AutomationRunStatus.STOPPED, reason)
        audit.warn("STOP", "Automation halted: $reason")
    }

    /**
     * Fail-closed emergency stop. Persisted first, so it survives process death and device
     * reboots; nothing is executed again until an operator clears it explicitly.
     */
    suspend fun engageKillSwitch(reason: String = "EMERGENCY ABORT: Global Kill Switch engaged by operator") {
        ensureStateRow()
        val now = clock.now()
        automationDao.setKillSwitch(true, reason, now, now)
        automationDao.setEnabled(false, now)
        automationDao.updateProgress("EMERGENCY ABORT", "", "KILLED", now)
        _killSwitch.value = KillSwitchState(true, reason, now)
        runningFlag.set(false)
        scheduler.cancelAll("kill switch engaged")
        fallbackJob?.cancel()
        fallbackJob = null
        _status.value = AutomationStatus.KILLED
        _currentTaskDescription.value = "GLOBAL KILL SWITCH ENGAGED - $reason"
        closeActiveRuns(AutomationRunStatus.KILLED, reason)
        audit.error("KILL_SWITCH", "EMERGENCY ABORT: $reason")
    }

    /** Clears the latch. Does not resume anything: the operator must arm automation again. */
    suspend fun clearKillSwitch(operatorName: String = "operator") {
        ensureStateRow()
        val now = clock.now()
        automationDao.setKillSwitch(false, null, null, now)
        automationDao.updateProgress("Kill switch cleared by $operatorName", "", "STOPPED", now)
        _killSwitch.value = KillSwitchState(false, null, null)
        _status.value = AutomationStatus.STOPPED
        _currentTaskDescription.value = "Kill switch cleared. Automation remains stopped until armed."
        audit.warn("KILL_SWITCH", "Kill switch cleared by operator '$operatorName'; automation stays stopped")
    }

    /**
     * Process-start hook. Reconciles durable state and, when automation was armed, re-enqueues a
     * durable cycle so unfinished jobs survive a process death.
     */
    suspend fun onProcessStart() {
        ensureStateRow()
        val state = automationDao.getAutomationState()
        _killSwitch.value = KillSwitchState(
            engaged = state?.killSwitchEngaged == true,
            reason = state?.killSwitchReason,
            engagedAt = state?.killSwitchEngagedAt
        )
        if (state?.killSwitchEngaged == true) {
            _status.value = AutomationStatus.KILLED
            _currentTaskDescription.value = "Global kill switch engaged (persisted)"
            scheduler.cancelAll("persisted kill switch")
            return
        }
        val report = recoverInterruptedWork()
        if (report.interruptedRuns > 0 || report.reconciledJobs > 0) {
            audit.warn(
                "PROCESS_START",
                "Startup reconciliation: ${report.interruptedRuns} interrupted run(s), " +
                    "${report.reconciledJobs} job(s), ${report.reclaimedLeases} reclaimed lease(s)"
            )
        }
        if (state?.isEnabled == true) {
            val rules = automationDao.getRules() ?: AutomationRuleEntity()
            scheduler.schedulePeriodic(rules.scanIntervalMinutes)
            scheduler.enqueueImmediateCycle(CycleTrigger.PROCESS_START)
            if (!scheduler.isPersistent) startFallbackLoopIfNeeded(CycleTrigger.PROCESS_START)
        }
    }

    /**
     * Gate used by the durable worker before doing any work: a queued request must never resurrect
     * automation that the operator stopped, and the kill switch always wins (fail closed).
     */
    suspend fun shouldRunScheduledWork(): Boolean {
        val state = automationDao.getAutomationState() ?: return false
        if (state.killSwitchEngaged) {
            _killSwitch.value = KillSwitchState(true, state.killSwitchReason, state.killSwitchEngagedAt)
            return false
        }
        return state.isEnabled
    }

    suspend fun onRulesChanged(scanIntervalMinutes: Int) {
        val state = automationDao.getAutomationState()
        if (state?.isEnabled == true && state.killSwitchEngaged.not()) {
            scheduler.schedulePeriodic(scanIntervalMinutes)
        }
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Crash recovery. Runs before any new work:
     *  - RUNNING runs whose heartbeat died are marked INTERRUPTED (their real startTime is kept),
     *  - in-flight jobs whose lease expired are reconciled through [AutomationRecoveryPolicy],
     *  - abandoned leases are reclaimed so the jobs become claimable again.
     */
    suspend fun recoverInterruptedWork(): RecoveryReport {
        val now = clock.now()
        val rules = automationDao.getRules() ?: AutomationRuleEntity()
        val details = mutableListOf<String>()
        var interruptedRuns = 0
        var reconciled = 0
        var reclaimed = 0

        val staleBefore = now - rules.staleRunTimeoutMinutes.coerceAtLeast(1) * 60_000L
        for (run in automationDao.getStaleRuns(staleBefore)) {
            val closed = automationDao.closeRun(
                runId = run.id,
                status = AutomationRunStatus.INTERRUPTED,
                endTime = now,
                summary = "Interrupted by process death after ${(now - run.startTime) / 1000}s (heartbeat stale)",
                failureReason = "Process terminated while the cycle was running"
            )
            if (closed > 0) {
                interruptedRuns++
                details += "run #${run.id} (started ${run.startTime}) -> INTERRUPTED"
                audit.warn(
                    "CRASH_RECOVERY",
                    "Run #${run.id} marked INTERRUPTED: no heartbeat since ${run.heartbeatAt}",
                    runId = run.id
                )
            }
        }

        for (job in automationDao.getJobsInStates(JobState.names(JobState.IN_FLIGHT), rules.maxJobsPerCycle * 2)) {
            if (job.leaseOwner == leaseOwner) continue
            if (job.hasActiveLease(now)) {
                details += "[${job.jobId}] left alone: lease held by ${job.leaseOwner} until ${job.leaseExpiresAt}"
                continue
            }
            if (!rules.autoResumeInterruptedJobs) {
                details += "[${job.jobId}] parked: automatic resume is disabled by rules"
                continue
            }
            val applied = reconcileInterruptedJob(job, rules, now, details)
            if (applied) reconciled++
        }

        for (job in automationDao.getJobsWithExpiredLease(JobState.names(JobState.PENDING), now, rules.maxJobsPerCycle * 2)) {
            val owner = job.leaseOwner ?: continue
            val released = automationDao.releaseJobLease(job.jobId, owner, now)
            if (released > 0) {
                reclaimed++
                details += "[${job.jobId}] reclaimed abandoned lease from $owner"
            }
        }

        if (interruptedRuns > 0 || reconciled > 0 || reclaimed > 0) {
            automationDao.registerRecovery(now, reconciled)
        }
        return RecoveryReport(interruptedRuns, reconciled, reclaimed, details)
    }

    private suspend fun reconcileInterruptedJob(
        job: AutomationJobEntity,
        rules: AutomationRuleEntity,
        now: Long,
        details: MutableList<String>
    ): Boolean {
        val evidence = collectEvidence(job)
        val decision = AutomationRecoveryPolicy.decide(job, evidence, rules.maxRecoveryAttempts)
        if (decision.targetState == job.state() && !job.state().isFailure) {
            details += "[${job.jobId}] no reconciliation needed (${job.state()})"
            return false
        }
        val from = job.state()
        val outcome = if (decision.targetState == JobState.FAILED_TERMINAL) {
            JobStateMachine.escalatedOutcome(job, now, decision.reason, job.failedStep)
        } else {
            JobStateMachine.transition(
                job = job.copy(leaseOwner = null, leaseExpiresAt = 0L),
                target = decision.targetState,
                now = now,
                retryPolicy = buildRetryPolicy(rules),
                error = if (decision.targetState.isFailure || decision.targetState == JobState.BLOCKED) decision.reason else null,
                failedStep = if (decision.targetState.isFailure || decision.targetState == JobState.BLOCKED) {
                    AutomationSteps.stepForState(from)
                } else null,
                failureKind = if (decision.targetState == JobState.BLOCKED) FailureKind.BLOCKED else null,
                offerId = decision.offerId,
                emailMessageId = decision.emailMessageId,
                blockageReason = if (decision.targetState == JobState.BLOCKED) decision.reason else null,
                recovery = true,
                random = random
            )
        }
        if (!outcome.applied) {
            details += "[${job.jobId}] recovery skipped: ${outcome.rejectionReason}"
            return false
        }
        val persisted = persistTransition(job, outcome.job, runId = null, correlationId = "CRASH_RECOVERY", details = details)
        if (persisted != null) {
            audit.transition(job, from, outcome.job.state(), null, "CRASH_RECOVERY", decision.reason)
        }
        return persisted != null
    }

    /** Durable evidence needed to decide where an interrupted job continues. */
    private suspend fun collectEvidence(job: AutomationJobEntity): RecoveryEvidence {
        val offer = job.offerId?.let { offerRepository.findOffer(it) }
            ?: offerRepository.findOfferForProperty(job.propertyId)
        val ledgerEntry = job.offerId?.let { ledger.snapshot(AutomationEffect.SEND_OFFER, it) }
        val analysisAvailable = runCatching { financialRepository.hasAnalysis(job.propertyId) }.getOrDefault(false)
        return RecoveryEvidence(
            analysisAvailable = analysisAvailable,
            offerStatus = offer?.status,
            offerId = offer?.id ?: job.offerId,
            emailMessageId = job.emailMessageId,
            sendRecordedByLedger = ledgerEntry?.status == EffectStatus.SUCCEEDED.name,
            ledgerMessageId = ledgerEntry?.resultRef
        )
    }

    // ------------------------------------------------------------------ cycle execution

    /**
     * Executes exactly one durable cycle. Called by the WorkManager worker (primary path) or by an
     * operator "run now" action. Concurrency is enforced by the persisted cycle lease, so two
     * workers can never process the same cycle.
     */
    suspend fun executeCycle(request: CycleRequest = CycleRequest()): CycleOutcome {
        ensureStateRow()
        val persisted = automationDao.getAutomationState()
        if (persisted?.killSwitchEngaged == true) {
            _killSwitch.value = KillSwitchState(true, persisted.killSwitchReason, persisted.killSwitchEngagedAt)
            _status.value = AutomationStatus.KILLED
            return CycleOutcome.Skipped("Global kill switch is engaged; execution refused", killSwitch = true)
        }

        if (!cycleMutex.tryLock()) {
            return CycleOutcome.Skipped("A cycle is already running in this process")
        }

        val now = clock.now()
        val rules = automationDao.getRules() ?: AutomationRuleEntity()
        val leaseTtl = rules.cycleLeaseTtlMinutes.coerceAtLeast(1) * 60_000L

        val claimed = automationDao.tryAcquireCycleLease(leaseOwner, 0L, now + leaseTtl, now)
        if (claimed == 0) {
            val retryAfter = ((persisted?.cycleLeaseExpiresAt ?: now) - now).coerceIn(15_000L, 120_000L)
            return CycleOutcome.Skipped(
                "Another execution lease is active (owner=${persisted?.cycleLeaseOwner}); will retry shortly",
                retryAfterMs = retryAfter
            )
        }

        val runId = automationDao.insertRun(
            AutomationRunEntity(
                startTime = now,
                status = AutomationRunStatus.RUNNING,
                summary = "Cycle in progress (trigger=${request.trigger})",
                trigger = request.trigger,
                correlationId = request.correlationId,
                workerRunAttempt = request.workerRunAttempt,
                heartbeatAt = now
            )
        )
        automationDao.markEngineStarted(now, runId, now)
        runningFlag.set(true)
        _status.value = AutomationStatus.RECOVERING
        _currentTaskDescription.value = "Cycle started (${request.trigger})"

        val heartbeat = engineScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val tick = clock.now()
                runCatching {
                    automationDao.renewCycleLease(leaseOwner, tick + leaseTtl, tick)
                    automationDao.touchRunHeartbeat(runId, tick)
                }
            }
        }

        var stats = CycleStats()
        return try {
            stats = runCycleBody(runId, request, rules)
            automationDao.updateRunStats(
                runId = runId,
                propertiesFound = stats.propertiesFound,
                propertiesAnalyzed = stats.propertiesAnalyzed,
                dealsQualified = stats.dealsQualified,
                offersCreated = stats.offersCreated,
                offersSent = stats.offersSent,
                jobsProcessed = stats.jobsProcessed,
                jobsRecovered = stats.recoveryReconciled,
                jobsFailed = stats.jobsFailed,
                jobsBlocked = stats.jobsBlocked,
                now = clock.now()
            )
            closeRun(runId, AutomationRunStatus.COMPLETED, "Cycle complete: ${stats.summary()}", null)
            audit.info("CYCLE_COMPLETE", "Cycle finished: ${stats.summary()}", runId = runId, correlationId = request.correlationId)
            _status.value = AutomationStatus.IDLE
            _currentTaskDescription.value = if (stats.pausedOffline) {
                "Paused (offline) - will resume when connectivity returns"
            } else {
                "Cycle complete. Durable worker will run again on schedule."
            }
            CycleOutcome.Completed(runId, stats)
        } catch (halt: AutomationHaltException) {
            val status = if (halt.killSwitch) AutomationRunStatus.KILLED else AutomationRunStatus.STOPPED
            closeRun(runId, status, "Cycle halted: ${halt.message}", halt.message)
            audit.warn("CYCLE_HALT", "Cycle halted: ${halt.message}", runId = runId, correlationId = request.correlationId)
            if (halt.killSwitch) CycleOutcome.Killed(runId, halt.message ?: "kill switch") else CycleOutcome.Skipped(halt.message ?: "stopped")
        } catch (cancelled: CancellationException) {
            closeRun(runId, AutomationRunStatus.STOPPED, "Cycle cancelled", "Cancelled by operator or scheduler")
            audit.warn("CYCLE_CANCELLED", "Cycle cancelled; jobs stay durable and will resume", runId = runId)
            throw cancelled
        } catch (error: Throwable) {
            val kind = FailureClassifier.classify(error)
            val message = error.message ?: error.javaClass.simpleName
            audit.error(
                "CYCLE_FAILURE",
                "Cycle failed (kind=$kind): $message",
                runId = runId,
                correlationId = request.correlationId
            )
            automationDao.registerFailure(message, clock.now())
            val runStatus = if (kind == FailureKind.RETRYABLE) AutomationRunStatus.ERROR else AutomationRunStatus.ERROR
            closeRun(runId, runStatus, "Cycle failed ($kind): $message", message)
            _status.value = AutomationStatus.ERROR
            _currentTaskDescription.value = "Cycle error ($kind): $message"
            if (kind == FailureKind.RETRYABLE) {
                CycleOutcome.Retryable(runId, message)
            } else {
                CycleOutcome.Terminal(runId, message)
            }
        } finally {
            heartbeat.cancel()
            runningFlag.set(false)
            withContext(NonCancellable) {
                automationDao.releaseCycleLease(leaseOwner, clock.now())
                cycleMutex.unlock()
            }
        }
    }

    private suspend fun runCycleBody(runId: Long, request: CycleRequest, rules: AutomationRuleEntity): CycleStats {
        var stats = CycleStats()

        // 1. Reconcile anything the previous process left behind before creating new work.
        _status.value = AutomationStatus.RECOVERING
        _currentTaskDescription.value = "Reconciling interrupted work..."
        val report = recoverInterruptedWork()
        stats = stats.copy(jobsRecovered = report.interruptedRuns, recoveryReconciled = report.reconciledJobs)

        // 2. Offline: persist a paused stage and end the cycle cleanly; WorkManager will retry.
        if (!networkMonitor.isOnlineNow()) {
            _status.value = AutomationStatus.PAUSED_OFFLINE
            _currentTaskDescription.value = "Paused (Offline - Waiting for connectivity)"
            automationDao.updateProgress("Paused: waiting for network", "", "OFFLINE_PAUSED", clock.now())
            audit.warn("OFFLINE", "Cycle skipped: device is offline", runId = runId, correlationId = request.correlationId)
            return stats.copy(pausedOffline = true)
        }

        val processed = mutableSetOf<String>()

        // 3. Finish durable work from earlier cycles first (retries, resumed jobs, reconciled sends).
        val pending = automationDao.getJobsInStates(JobState.names(JobState.PENDING), rules.maxJobsPerCycle)
        if (pending.isNotEmpty()) {
            _status.value = AutomationStatus.RETRYING
            _currentTaskDescription.value = "Resuming ${pending.size} unfinished job(s) from earlier cycles..."
        }
        for (job in pending) {
            ensureExecutionAllowed()
            if (processed.contains(job.jobId)) continue
            val outcome = processJob(job, rules, runId, request.correlationId)
            processed += job.jobId
            stats = mergeStats(stats, outcome)
        }

        // 4. Discovery of new properties.
        ensureExecutionAllowed()
        if (stats.propertiesAnalyzed >= rules.maxAnalysesPerRun) {
            audit.info(
                "LIMIT_REACHED",
                "Reached max analyses per run (${rules.maxAnalysesPerRun}); skipping discovery",
                runId = runId
            )
            return stats
        }

        _status.value = AutomationStatus.SCANNING
        _currentTaskDescription.value = "Scanning property feeds (MLS & Distressed)..."
        automationDao.updateProgress("Scanning property feeds", "", "DISCOVERY", clock.now())

        // Resolve listings against the canonical identities already on disk so a property that
        // another source already delivered is not re-imported as a duplicate.
        val existingIdentities = propertyDao.getAllPropertiesList().map { it.toCanonicalPropertyIdentity() }
        val discovered = propertySourceManager.fetchResolvedBundles(existingIdentities, rules.maxPropertiesPerCycle)
        val bundles = discovered.filter { it.isNew }.map { it.bundle }
        val reviewCount = discovered.count { it.needsReview }
        stats = stats.copy(propertiesFound = bundles.size)
        audit.info(
            "DISCOVERY",
            "Discovered ${bundles.size} new properties across feeds" +
                if (reviewCount > 0) "; $reviewCount need identity review" else "",
            runId = runId
        )

        for (bundle in bundles) {
            ensureExecutionAllowed()
            if (stats.propertiesAnalyzed >= rules.maxAnalysesPerRun) break
            val job = registerDiscoveredProperty(bundle, runId, rules) ?: continue
            if (processed.contains(job.jobId)) continue
            val outcome = processJob(job, rules, runId, request.correlationId)
            processed += job.jobId
            stats = mergeStats(stats, outcome)
        }
        return stats
    }

    // ------------------------------------------------------------------ job pipeline

    private suspend fun registerDiscoveredProperty(
        bundle: NormalizedPropertyBundle,
        runId: Long,
        rules: AutomationRuleEntity
    ): AutomationJobEntity? {
        val propertyId = bundle.property.id
        val now = clock.now()

        // Already unresolved for this property (any earlier run): keep working on it, do not duplicate.
        val unresolved = automationDao.getJobByPropertyIdInStates(propertyId, JobState.names(JobState.PENDING + JobState.BLOCKED))
        if (unresolved != null) return unresolved

        // Already resolved by a previous cycle (offer ready/sent/dq): do not replay the pipeline.
        val previous = automationDao.getJobByPropertyId(propertyId)
        if (previous != null && (previous.state() == JobState.SENT || previous.state() == JobState.OFFER_READY)) {
            return null
        }

        automationDao.getJobByIdempotencyKey("$runId:$propertyId")?.let { return it }

        // One transaction for the canonical row, its provenance and its satellites. When the row
        // already exists under another source the importer merges it and hands back the survivor,
        // so the job below tracks the canonical id rather than the incoming listing id.
        val importer = propertyImporter
        val canonicalProperty = if (importer != null) {
            val result = importer.importBundle(bundle)
            if (result.outcome == ImportOutcome.SKIPPED) {
                audit.info(
                    "PROPERTY_SKIPPED",
                    "Skipped duplicate record: ${result.reason}",
                    runId = runId
                )
                return null
            }
            result.property
        } else {
            propertyDao.insertProperty(bundle.property)
            propertyDao.insertImages(bundle.images)
            propertyDao.insertMarketData(bundle.marketData)
            propertyDao.insertRentEstimate(bundle.rentEstimate)
            propertyDao.insertTaxRecord(bundle.taxRecord)
            bundle.property
        }

        // The record merged into a row this run already tracks: keep the existing job, never a twin.
        if (canonicalProperty.id != propertyId) {
            automationDao.getJobByPropertyId(canonicalProperty.id)?.let { return it }
        }
        val key = "$runId:${canonicalProperty.id}"

        val job = AutomationJobEntity(
            jobId = "JOB-" + UUID.randomUUID().toString().take(8).uppercase(),
            runId = runId,
            propertyId = canonicalProperty.id,
            propertyAddress = canonicalProperty.address,
            currentState = JobState.DISCOVERED.name,
            lastSuccessfulState = JobState.DISCOVERED.name,
            idempotencyKey = key,
            attempts = 0,
            maxRetries = rules.maxRetries,
            createdAt = now,
            updatedAt = now
        )
        automationDao.insertOrUpdateJob(job)
        audit.info(
            "JOB_CREATED",
            "[${job.jobId}] tracking ${bundle.property.address} (${bundle.property.sourceType})",
            runId = runId,
            jobId = job.jobId
        )
        return job
    }

    private suspend fun processJob(
        initial: AutomationJobEntity,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String
    ): JobOutcome {
        val now = clock.now()
        val state = initial.state()
        if (state.isTerminal) return JobOutcome.skipped()
        if (state == JobState.BLOCKED) return JobOutcome.skipped(blocked = true)
        if (state == JobState.FAILED_RETRYABLE && initial.nextAttemptAt > now) return JobOutcome.skipped()
        if (initial.hasActiveLease(now) && initial.leaseOwner != leaseOwner) {
            return JobOutcome.skipped() // another worker holds a live lease
        }

        val leaseTtl = rules.jobLeaseTtlMinutes.coerceAtLeast(1) * 60_000L
        val claimed = automationDao.claimJobLease(initial.jobId, leaseOwner, now + leaseTtl, now)
        if (claimed == 0) return JobOutcome.skipped()

        var outcome = JobOutcome.skipped()
        try {
            var current = automationDao.getJobById(initial.jobId) ?: return JobOutcome.skipped()
            var cachedAnalysis: FinancialResult? = null
            var steps = 0
            while (steps < MAX_STEPS_PER_JOB) {
                steps++
                ensureExecutionAllowed()
                val step = executeStep(current, rules, runId, correlationId, cachedAnalysis)
                cachedAnalysis = step.analysis
                outcome = outcome.merge(step.outcome)
                if (step.advanced) outcome = outcome.copy(processed = true)
                if (!step.advanced) break
                current = step.job
            }
        } finally {
            runCatching { automationDao.releaseJobLease(initial.jobId, leaseOwner, clock.now()) }
        }
        return outcome
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun executeStep(
        job: AutomationJobEntity,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String,
        cachedAnalysis: FinancialResult?
    ): StepResult {
        return when (job.state()) {
            JobState.DISCOVERED, JobState.ANALYZING -> runAnalysisStep(job, rules, runId, correlationId)

            JobState.ANALYZED, JobState.QUALIFYING -> runQualifyStep(job, rules, runId, correlationId, cachedAnalysis)

            JobState.QUALIFIED, JobState.OFFER_GENERATION ->
                runOfferStep(job, rules, runId, correlationId, cachedAnalysis)

            JobState.OFFER_READY -> if (rules.autoSendOffers) {
                runValidateSendStep(job, rules, runId, correlationId)
            } else {
                StepResult(job, advanced = false, outcome = JobOutcome.skipped())
            }

            JobState.VALIDATING_SEND -> runValidateSendStep(job, rules, runId, correlationId)

            JobState.SENDING -> runSendStep(job, rules, runId, correlationId)

            JobState.RECONCILING -> runReconcileStep(job, rules, runId, correlationId)

            JobState.FAILED_RETRYABLE -> runRetryResumeStep(job, rules, runId, correlationId)

            else -> StepResult(job, advanced = false, outcome = JobOutcome.skipped(blocked = job.state().needsOperatorAction))
        }
    }

    private suspend fun runAnalysisStep(
        job: AutomationJobEntity,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String
    ): StepResult {
        _status.value = AutomationStatus.ANALYZING
        _currentTaskDescription.value = "Analyzing finances for ${job.propertyAddress}..."
        automationDao.updateProgress("Underwriting ${job.propertyAddress}", job.propertyAddress, "ANALYZING", clock.now())

        val analyzing = persistTransition(
            job,
            JobStateMachine.transition(job, JobState.ANALYZING, clock.now(), buildRetryPolicy(rules), random = random).job,
            runId,
            correlationId
        ) ?: return StepResult.contended(job)

        return try {
            doubleCheckKillSwitch()
            val analysis = financialRepository.runAnalysis(job.propertyId)
            ledger.markSucceeded(AutomationEffect.ANALYZE_PROPERTY, job.propertyId, job.jobId, runId, "ANALYSIS")
            automationDao.registerSuccess("Analyzed ${job.propertyAddress}", clock.now())
            audit.success(
                "ANALYSIS_COMPLETED",
                "[${job.jobId}] Underwrote ${job.propertyAddress}: NOI ${analysis.noiAnnual.toInt()}, " +
                    "cash flow ${analysis.monthlyCashFlow.toInt()}/mo, cap ${String.format("%.1f", analysis.capRate)}%",
                runId = runId,
                jobId = job.jobId,
                correlationId = correlationId
            )
            val transitioned = JobStateMachine.transition(
                analyzing,
                JobState.ANALYZED,
                clock.now(),
                buildRetryPolicy(rules),
                analysisId = job.propertyId,
                random = random
            )
            val persisted = persistTransition(analyzing, transitioned.job, runId, correlationId)
            logTransition(analyzing, transitioned.job, persisted, "analysis persisted", runId, correlationId)
            StepResult(
                job = persisted ?: analyzing,
                advanced = persisted != null,
                outcome = JobOutcome.skipped(analyzed = 1),
                analysis = analysis
            )
        } catch (halt: AutomationHaltException) {
            throw halt
        } catch (error: Throwable) {
            StepResult(job = failStep(analyzing, error, AutomationSteps.ANALYSIS, rules, runId, correlationId), advanced = false, outcome = JobOutcome.skipped(failed = 1))
        }
    }

    private suspend fun runQualifyStep(
        job: AutomationJobEntity,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String,
        cachedAnalysis: FinancialResult?
    ): StepResult {
        val property = propertyDao.getPropertyById(job.propertyId)
            ?: return StepResult(
                job = failStep(job, IllegalStateException("Property ${job.propertyId} does not exist locally"), AutomationSteps.QUALIFY, rules, runId, correlationId),
                advanced = false,
                outcome = JobOutcome.skipped(failed = 1)
            )

        val qualifying = persistTransition(
            job,
            JobStateMachine.transition(job, JobState.QUALIFYING, clock.now(), buildRetryPolicy(rules), random = random).job,
            runId,
            correlationId
        ) ?: return StepResult.contended(job)

        _status.value = AutomationStatus.QUALIFYING
        _currentTaskDescription.value = "Qualifying ${job.propertyAddress}..."
        return try {
            doubleCheckKillSwitch()
            val analysis = cachedAnalysis ?: financialRepository.runAnalysis(job.propertyId)
            val evaluation = QualificationEngine.evaluate(property, analysis, rules)
            if (evaluation.isQualified) {
                val suggestedOfferLabel = evaluation.suggestedOfferPrice
                    ?.let { String.format(java.util.Locale.US, "%,.0f", it) }
                    ?: "unavailable"
                propertyDao.setDealStatus(job.propertyId, true, evaluation.score)
                val transitioned = JobStateMachine.transition(qualifying, JobState.QUALIFIED, clock.now(), buildRetryPolicy(rules), random = random)
                val persisted = persistTransition(qualifying, transitioned.job, runId, correlationId)
                logTransition(qualifying, transitioned.job, persisted, "deal qualified (score ${evaluation.score})", runId, correlationId)
                if (persisted != null) automationDao.registerSuccess("Qualified ${job.propertyAddress}", clock.now())
                audit.success(
                    "QUALIFICATION_RESULT",
                    "[${job.jobId}] DEAL QUALIFIED: ${job.propertyAddress} (score ${evaluation.score}/100, " +
                        "suggested $suggestedOfferLabel)",
                    runId = runId,
                    jobId = job.jobId,
                    correlationId = correlationId
                )
                StepResult(persisted ?: qualifying, advanced = persisted != null, outcome = JobOutcome.skipped(qualified = 1))
            } else {
                propertyDao.setDealStatus(job.propertyId, false, evaluation.score)
                val reason = evaluation.failedChecks.joinToString("; ").ifBlank { evaluation.summary }
                val transitioned = JobStateMachine.transition(
                    qualifying,
                    JobState.DISQUALIFIED,
                    clock.now(),
                    buildRetryPolicy(rules),
                    blockageReason = reason,
                    random = random
                )
                val persisted = persistTransition(qualifying, transitioned.job, runId, correlationId)
                logTransition(qualifying, transitioned.job, persisted, "deal disqualified", runId, correlationId)
                audit.info(
                    "QUALIFICATION_RESULT",
                    "[${job.jobId}] DISQUALIFIED: ${job.propertyAddress} - $reason",
                    runId = runId,
                    jobId = job.jobId,
                    correlationId = correlationId
                )
                StepResult(persisted ?: qualifying, advanced = false, outcome = JobOutcome.skipped())
            }
        } catch (halt: AutomationHaltException) {
            throw halt
        } catch (error: Throwable) {
            StepResult(failStep(qualifying, error, AutomationSteps.QUALIFY, rules, runId, correlationId), advanced = false, outcome = JobOutcome.skipped(failed = 1))
        }
    }

    @Suppress("LongMethod")
    private suspend fun runOfferStep(
        job: AutomationJobEntity,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String,
        cachedAnalysis: FinancialResult?
    ): StepResult {
        // Already have a usable offer (resumed job): do not regenerate, just re-link it.
        val existingOffer = offerRepository.findOfferForProperty(job.propertyId)
        if (existingOffer != null && OfferLifecycle.isReusable(existingOffer.status)) {
            val transitioned = JobStateMachine.transition(
                job,
                JobState.OFFER_READY,
                clock.now(),
                buildRetryPolicy(rules),
                offerId = existingOffer.id,
                recipientEmail = existingOffer.recipientEmail,
                random = random
            )
            val persisted = persistTransition(job, transitioned.job, runId, correlationId)
            if (persisted != null) {
                audit.info(
                    "OFFER_REUSED",
                    "[${job.jobId}] reusing existing offer ${existingOffer.id} (${existingOffer.status}) - no regeneration",
                    runId = runId,
                    jobId = job.jobId,
                    correlationId = correlationId
                )
            }
            logTransition(job, transitioned.job, persisted, "existing offer linked", runId, correlationId)
            return StepResult(persisted ?: job, advanced = true, outcome = JobOutcome.skipped())
        }

        if (OfferLifecycle.isDelivered(existingOffer?.status)) {
            val transitioned = JobStateMachine.transition(
                job,
                JobState.SENT,
                clock.now(),
                buildRetryPolicy(rules),
                offerId = existingOffer?.id,
                emailMessageId = job.emailMessageId ?: existingOffer?.id?.let { "GMAIL-$it" },
                random = random
            )
            val persisted = persistTransition(job, transitioned.job, runId, correlationId)
            logTransition(job, transitioned.job, persisted, "offer already delivered", runId, correlationId)
            return StepResult(persisted ?: job, advanced = false, outcome = JobOutcome.skipped(sent = 1))
        }

        if (!rules.autoGenerateOffers) {
            val transitioned = JobStateMachine.transition(
                job,
                JobState.BLOCKED,
                clock.now(),
                buildRetryPolicy(rules),
                error = "Offer generation is disabled by automation rules",
                blockageReason = "Offer generation disabled by policy (autoGenerateOffers = false)",
                failureKind = FailureKind.BLOCKED,
                random = random
            )
            val persisted = persistTransition(job, transitioned.job, runId, correlationId)
            logTransition(job, transitioned.job, persisted, "generation disabled by rules", runId, correlationId)
            return StepResult(persisted ?: job, advanced = false, outcome = JobOutcome.skipped(blocked = true))
        }

        val generating = persistTransition(
            job,
            JobStateMachine.transition(job, JobState.OFFER_GENERATION, clock.now(), buildRetryPolicy(rules), random = random).job,
            runId,
            correlationId
        ) ?: return StepResult.contended(job)

        _status.value = AutomationStatus.GENERATING_OFFERS
        _currentTaskDescription.value = "Drafting institutional purchase offer for ${job.propertyAddress}..."
        automationDao.updateProgress("Generating LOI for ${job.propertyAddress}", job.propertyAddress, "OFFER_GENERATION", clock.now())

        return try {
            doubleCheckKillSwitch()
            val evaluation = cachedAnalysis?.let { analysis ->
                propertyDao.getPropertyById(job.propertyId)?.let { QualificationEngine.evaluate(it, analysis, rules) }
            } ?: deriveEvaluation(job.propertyId, rules)
            val offerPrice = evaluation?.suggestedOfferPrice?.takeIf { it.isFinite() && it > 0.0 }
            if (offerPrice == null) {
                val reason = "Offer generation blocked: no finite positive suggested offer price is available"
                val blocked = JobStateMachine.transition(
                    generating,
                    JobState.BLOCKED,
                    clock.now(),
                    buildRetryPolicy(rules),
                    error = reason,
                    blockageReason = reason,
                    failureKind = FailureKind.BLOCKED,
                    random = random
                )
                val persisted = persistTransition(generating, blocked.job, runId, correlationId)
                audit.warn("OFFER_BLOCKED", "[${job.jobId}] $reason", runId, job.jobId, correlationId)
                logTransition(generating, blocked.job, persisted, "missing deterministic offer price", runId, correlationId)
                return StepResult(persisted ?: generating, advanced = persisted != null, outcome = JobOutcome.skipped(blocked = 1))
            }

            val decision = ledger.begin(AutomationEffect.GENERATE_OFFER, job.propertyId, job.jobId, runId)
            val offer = if (decision.allowed || decision.alreadySucceeded) {
                val generated = offerRepository.generateDraftOffer(
                    context = context,
                    propertyId = job.propertyId,
                    customPrice = offerPrice,
                    suggestedRecipientEmail = job.recipientEmail
                )
                ledger.markSucceeded(AutomationEffect.GENERATE_OFFER, job.propertyId, job.jobId, runId, generated.id)
                generated
            } else {
                offerRepository.findOfferForProperty(job.propertyId)
                    ?: throw IllegalStateException("Offer generation ledger is inconsistent for ${job.propertyId}")
            }

            val transitioned = JobStateMachine.transition(
                generating,
                JobState.OFFER_READY,
                clock.now(),
                buildRetryPolicy(rules),
                offerId = offer.id,
                recipientEmail = offer.recipientEmail,
                random = random
            )
            val persisted = persistTransition(generating, transitioned.job, runId, correlationId)
            if (persisted != null) {
                automationDao.registerSuccess("Generated offer ${offer.id}", clock.now())
                audit.success("OFFER_GENERATED", "[${job.jobId}] offer ${offer.id} created for ${job.propertyAddress}", runId, job.jobId, correlationId)
                audit.success("PDF_GENERATED", "PDF agreement generated and validated for offer ${offer.id}", runId, job.jobId, correlationId)
            }
            logTransition(generating, transitioned.job, persisted, "offer persisted", runId, correlationId)
            StepResult(persisted ?: generating, advanced = persisted != null, outcome = JobOutcome.skipped(offersCreated = 1))
        } catch (halt: AutomationHaltException) {
            throw halt
        } catch (error: Throwable) {
            ledger.markFailed(AutomationEffect.GENERATE_OFFER, job.propertyId, job.jobId, runId, error.message)
            StepResult(failStep(generating, error, AutomationSteps.GENERATE_OFFER, rules, runId, correlationId), advanced = false, outcome = JobOutcome.skipped(failed = 1))
        }
    }

    private suspend fun runValidateSendStep(
        job: AutomationJobEntity,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String
    ): StepResult {
        val offerId = job.offerId
            ?: return StepResult(
                job = failStep(job, IllegalStateException("Send validation without an offer id"), AutomationSteps.VALIDATE_SEND, rules, runId, correlationId),
                advanced = false,
                outcome = JobOutcome.skipped(failed = 1)
            )

        if (!rules.autoSendOffers) {
            // Nothing to do: the offer stays ready until the operator enables sending.
            return StepResult(job, advanced = false, outcome = JobOutcome.skipped())
        }

        val validating = persistTransition(
            job,
            JobStateMachine.transition(job, JobState.VALIDATING_SEND, clock.now(), buildRetryPolicy(rules), random = random).job,
            runId,
            correlationId
        ) ?: return StepResult.contended(job)

        _status.value = AutomationStatus.SENDING_OFFERS
        _currentTaskDescription.value = "Validating offer $offerId before transmission..."
        return try {
            doubleCheckKillSwitch()
            val validation = offerRepository.validateForSend(context, offerId)
            if (!validation.isValid) {
                val reason = validation.blockageReason ?: "Pre-send validation failed."
                val transitioned = JobStateMachine.transition(
                    validating,
                    JobState.BLOCKED,
                    clock.now(),
                    buildRetryPolicy(rules),
                    error = reason,
                    blockageReason = reason,
                    failureKind = FailureKind.BLOCKED,
                    random = random
                )
                val persisted = persistTransition(validating, transitioned.job, runId, correlationId)
                logTransition(validating, transitioned.job, persisted, "delivery blocked: $reason", runId, correlationId)
                audit.warn("VALIDATION_BLOCKED", "[${job.jobId}] offer $offerId blocked: $reason", runId, job.jobId, correlationId)
                StepResult(persisted ?: validating, advanced = false, outcome = JobOutcome.skipped(blocked = true))
            } else {
                val transitioned = JobStateMachine.transition(validating, JobState.SENDING, clock.now(), buildRetryPolicy(rules), random = random)
                val persisted = persistTransition(validating, transitioned.job, runId, correlationId)
                logTransition(validating, transitioned.job, persisted, "pre-send validation passed", runId, correlationId)
                StepResult(persisted ?: validating, advanced = persisted != null, outcome = JobOutcome.skipped())
            }
        } catch (halt: AutomationHaltException) {
            throw halt
        } catch (error: Throwable) {
            StepResult(failStep(validating, error, AutomationSteps.VALIDATE_SEND, rules, runId, correlationId), advanced = false, outcome = JobOutcome.skipped(failed = 1))
        }
    }

    private suspend fun runSendStep(
        job: AutomationJobEntity,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String
    ): StepResult {
        val offerId = job.offerId
            ?: return StepResult(
                job = failStep(job, IllegalStateException("Send without an offer id"), AutomationSteps.SEND_OFFER, rules, runId, correlationId),
                advanced = false,
                outcome = JobOutcome.skipped(failed = 1)
            )

        // Idempotency guard #1: durable ledger already recorded a successful delivery.
        val decision = ledger.begin(AutomationEffect.SEND_OFFER, offerId, job.jobId, runId)
        if (!decision.allowed && decision.alreadySucceeded) {
            val reconcile = JobStateMachine.transition(
                job,
                JobState.RECONCILING,
                clock.now(),
                buildRetryPolicy(rules),
                emailMessageId = decision.resultRef,
                random = random
            )
            val persisted = persistTransition(job, reconcile.job, runId, correlationId)
            logTransition(job, reconcile.job, persisted, "delivery already recorded in ledger", runId, correlationId)
            return StepResult(persisted ?: job, advanced = persisted != null, outcome = JobOutcome.skipped())
        }

        // Idempotency guard #2: the offer itself is already delivered.
        val offer = offerRepository.findOffer(offerId)
        if (OfferLifecycle.isDelivered(offer?.status)) {
            val transitioned = JobStateMachine.transition(
                job,
                JobState.SENT,
                clock.now(),
                buildRetryPolicy(rules),
                emailMessageId = job.emailMessageId ?: "GMAIL-$offerId",
                random = random
            )
            ledger.markSucceeded(AutomationEffect.SEND_OFFER, offerId, job.jobId, runId, "GMAIL-$offerId")
            val persisted = persistTransition(job, transitioned.job, runId, correlationId)
            logTransition(job, transitioned.job, persisted, "offer already delivered", runId, correlationId)
            return StepResult(persisted ?: job, advanced = false, outcome = JobOutcome.skipped(sent = 1))
        }

        _status.value = AutomationStatus.SENDING_OFFERS
        _currentTaskDescription.value = "Transmitting offer $offerId via Gmail..."
        return try {
            doubleCheckKillSwitch()
            val sent = offerRepository.transmitOffer(context, offerId)
            if (sent) {
                val messageId = "GMAIL-$offerId"
                ledger.markSucceeded(AutomationEffect.SEND_OFFER, offerId, job.jobId, runId, messageId)
                val transitioned = JobStateMachine.transition(
                    job,
                    JobState.SENT,
                    clock.now(),
                    buildRetryPolicy(rules),
                    emailMessageId = messageId,
                    random = random
                )
                val persisted = persistTransition(job, transitioned.job, runId, correlationId)
                if (persisted != null) {
                    automationDao.registerSuccess("Sent offer $offerId", clock.now())
                    audit.success(
                        "GMAIL_SENT",
                        "[${job.jobId}] offer $offerId sent to ${job.recipientEmail ?: offer?.recipientEmail ?: "recipient"}",
                        runId,
                        job.jobId,
                        correlationId
                    )
                }
                logTransition(job, transitioned.job, persisted, "offer transmitted", runId, correlationId)
                StepResult(persisted ?: job, advanced = false, outcome = JobOutcome.skipped(sent = 1))
            } else {
                val refreshed = offerRepository.findOffer(offerId)
                val errorText = refreshed?.lastError ?: "Gmail delivery failed or rejected."
                ledger.markFailed(AutomationEffect.SEND_OFFER, offerId, job.jobId, runId, errorText)
                StepResult(failStep(job, RuntimeException(errorText), AutomationSteps.SEND_OFFER, rules, runId, correlationId), advanced = false, outcome = JobOutcome.skipped(failed = 1))
            }
        } catch (halt: AutomationHaltException) {
            throw halt
        } catch (error: Throwable) {
            ledger.markFailed(AutomationEffect.SEND_OFFER, offerId, job.jobId, runId, error.message)
            StepResult(failStep(job, error, AutomationSteps.SEND_OFFER, rules, runId, correlationId), advanced = false, outcome = JobOutcome.skipped(failed = 1))
        }
    }

    private suspend fun runReconcileStep(
        job: AutomationJobEntity,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String
    ): StepResult {
        _status.value = AutomationStatus.RECONCILING
        _currentTaskDescription.value = "Reconciling delivery outcome for ${job.propertyAddress}..."
        doubleCheckKillSwitch()

        val offerId = job.offerId
        val ledgerEntry = offerId?.let { ledger.snapshot(AutomationEffect.SEND_OFFER, it) }
        val offer = offerId?.let { offerRepository.findOffer(it) } ?: offerRepository.findOfferForProperty(job.propertyId)
        val evidence = RecoveryEvidence(
            offerStatus = offer?.status,
            offerId = offer?.id,
            emailMessageId = job.emailMessageId,
            sendRecordedByLedger = ledgerEntry?.status == EffectStatus.SUCCEEDED.name,
            ledgerMessageId = ledgerEntry?.resultRef
        )

        val target: JobState
        val reason: String
        if (evidence.delivered) {
            target = JobState.SENT
            reason = "Delivery confirmed while reconciling"
        } else if (job.attempts + 1 >= job.maxRetries) {
            target = JobState.FAILED_TERMINAL
            reason = "Delivery outcome unknown after interruption and retries are exhausted"
        } else {
            target = JobState.FAILED_RETRYABLE
            reason = "Delivery outcome unknown after interruption; a safe re-send was scheduled"
        }

        val outcome = if (target == JobState.FAILED_TERMINAL) {
            JobStateMachine.escalatedOutcome(job, clock.now(), reason, AutomationSteps.SEND_OFFER)
        } else {
            JobStateMachine.transition(
                job,
                target,
                clock.now(),
                buildRetryPolicy(rules),
                error = if (target == JobState.FAILED_RETRYABLE) reason else null,
                failedStep = AutomationSteps.SEND_OFFER,
                emailMessageId = evidence.ledgerMessageId ?: job.emailMessageId,
                random = random
            )
        }
        val persisted = persistTransition(job, outcome.job, runId, correlationId)
        logTransition(job, outcome.job, persisted, reason, runId, correlationId)
        if (!evidence.delivered && offerId != null && ledgerEntry?.status == EffectStatus.IN_PROGRESS.name) {
            // Keep the ledger truthful: the in-flight attempt died without an acknowledgement.
            ledger.markFailed(AutomationEffect.SEND_OFFER, offerId, job.jobId, runId, reason)
        }
        if (target == JobState.FAILED_RETRYABLE) {
            audit.retryScheduled(outcome.job, runId, correlationId, outcome.job.nextAttemptAt - clock.now())
        }
        return StepResult(persisted ?: job, advanced = false, outcome = JobOutcome.skipped())
    }

    private suspend fun runRetryResumeStep(
        job: AutomationJobEntity,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String
    ): StepResult {
        val now = clock.now()
        if (job.nextAttemptAt > now) return StepResult(job, advanced = false, outcome = JobOutcome.skipped())
        if (job.attempts >= job.maxRetries) {
            val escalated = JobStateMachine.escalatedOutcome(
                job,
                now,
                "Retries exhausted for ${job.failedStep ?: "step"} (${job.attempts}/${job.maxRetries})",
                job.failedStep
            )
            val persisted = persistTransition(job, escalated.job, runId, correlationId)
            logTransition(job, escalated.job, persisted, "retries exhausted", runId, correlationId)
            return StepResult(persisted ?: job, advanced = false, outcome = JobOutcome.skipped(failed = 1))
        }
        val target = AutomationSteps.stateForStep(job.failedStep)
            ?: AutomationSteps.nextStateAfter(JobState.fromName(job.lastSuccessfulState))
        val outcome = JobStateMachine.transition(job, target, now, buildRetryPolicy(rules), random = random)
        if (!outcome.applied) {
            return StepResult(job, advanced = false, outcome = JobOutcome.skipped())
        }
        val persisted = persistTransition(job, outcome.job, runId, correlationId)
        logTransition(job, outcome.job, persisted, "retry due; resuming ${job.failedStep ?: target.name}", runId, correlationId)
        audit.info(
            "RETRY_RESUMED",
            "[${job.jobId}] attempt ${job.attempts + 1}/${job.maxRetries} resuming ${job.failedStep ?: target.name}",
            runId,
            job.jobId,
            correlationId
        )
        return StepResult(persisted ?: job, advanced = persisted != null, outcome = JobOutcome.skipped())
    }

    // ------------------------------------------------------------------ operator actions

    /**
     * Operator retry.
     *  - BLOCKED / FAILED_RETRYABLE jobs are moved back to the step that failed.
     *  - Terminal jobs cannot be revived in place (the audit trail stays immutable); a successor
     *    job is created for the same property instead.
     */
    suspend fun retryJob(jobId: String, operatorName: String = "operator"): Boolean {
        val job = automationDao.getJobById(jobId) ?: return false
        val now = clock.now()
        val state = job.state()
        return when {
            state == JobState.SENT || state == JobState.CANCELLED || state == JobState.DISQUALIFIED -> false

            state.isTerminal -> {
                val successor = job.copy(
                    jobId = "JOB-" + UUID.randomUUID().toString().take(8).uppercase(),
                    runId = 0L,
                    currentState = JobState.DISCOVERED.name,
                    lastSuccessfulState = JobState.DISCOVERED.name,
                    idempotencyKey = "REQUEUE:$jobId:$now",
                    attempts = 0,
                    lastError = null,
                    failureKind = null,
                    blockageReason = null,
                    nextAttemptAt = 0L,
                    leaseOwner = null,
                    leaseExpiresAt = 0L,
                    recoveryCount = 0,
                    lastRecoveredAt = null,
                    startedAt = null,
                    completedAt = null,
                    createdAt = now,
                    updatedAt = now
                )
                automationDao.insertOrUpdateJob(successor)
                audit.warn(
                    "OPERATOR_RETRY",
                    "[$jobId] was ${state.name}; created successor job ${successor.jobId} instead of reviving a terminal job ($operatorName)",
                    jobId = successor.jobId
                )
                true
            }

            else -> {
                val target = AutomationSteps.stateForStep(job.failedStep)
                    ?: AutomationSteps.nextStateAfter(JobState.fromName(job.lastSuccessfulState))
                val forced = if (state.canTransitionTo(target)) target else JobState.DISCOVERED
                val outcome = JobStateMachine.transition(
                    job = job.copy(attempts = 0, nextAttemptAt = 0L, leaseOwner = null, leaseExpiresAt = 0L),
                    target = forced,
                    now = now,
                    retryPolicy = retryPolicy,
                    random = random
                )
                if (!outcome.applied) return false
                val persisted = persistTransition(job, outcome.job, runId = null, correlationId = "OPERATOR_RETRY")
                if (persisted != null) {
                    audit.warn(
                        "OPERATOR_RETRY",
                        "[$jobId] retried by $operatorName: ${job.state()} -> ${outcome.job.state()}",
                        jobId = jobId
                    )
                    scheduler.enqueueImmediateCycle(CycleTrigger.OPERATOR_NOW)
                }
                persisted != null
            }
        }
    }

    /** Cancels a job from the operator UI. */
    suspend fun cancelJob(jobId: String, operatorName: String = "operator"): Boolean {
        val job = automationDao.getJobById(jobId) ?: return false
        if (job.state() == JobState.CANCELLED || job.state().isTerminal && job.state() != JobState.FAILED_TERMINAL) return false
        val outcome = JobStateMachine.transition(
            job = job.copy(leaseOwner = null, leaseExpiresAt = 0L),
            target = JobState.CANCELLED,
            now = clock.now(),
            retryPolicy = retryPolicy,
            random = random
        )
        if (!outcome.applied) return false
        val persisted = persistTransition(job, outcome.job, runId = null, correlationId = "OPERATOR_CANCEL")
        if (persisted != null) {
            audit.warn("OPERATOR_CANCEL", "[$jobId] cancelled by $operatorName", jobId = jobId)
        }
        return persisted != null
    }

    /** Runs one cycle immediately from an operator action (still serialized by the cycle lease). */
    suspend fun runCycleNow(): CycleOutcome = executeCycle(CycleRequest(trigger = CycleTrigger.OPERATOR_NOW))

    // ------------------------------------------------------------------ internals

    private fun buildRetryPolicy(rules: AutomationRuleEntity): RetryPolicy = RetryPolicy.fromRules(rules)

    /**
     * Persists a state transition: compare-and-swap first (so a concurrent writer always wins),
     * then the full row. Returns the persisted job, or null when the CAS lost the race.
     */
    private suspend fun persistTransition(
        previous: AutomationJobEntity,
        next: AutomationJobEntity,
        runId: Long?,
        correlationId: String?,
        details: MutableList<String>? = null
    ): AutomationJobEntity? {
        if (next.currentState == previous.currentState && next == previous) return previous
        val now = clock.now()
        val swapped = automationDao.casJobState(
            jobId = previous.jobId,
            expectedStates = listOf(previous.currentState),
            newState = next.currentState,
            now = now
        )
        if (swapped == 0) {
            details?.add("[${previous.jobId}] transition CAS rejected (another worker moved the job)")
            audit.warn(
                "TRANSITION_REJECTED",
                "[${previous.jobId}] ${previous.currentState} -> ${next.currentState} rejected by compare-and-swap",
                runId,
                previous.jobId,
                correlationId
            )
            return null
        }
        automationDao.insertOrUpdateJob(next.copy(updatedAt = now))
        return next.copy(updatedAt = now)
    }

    private suspend fun logTransition(
        previous: AutomationJobEntity,
        next: AutomationJobEntity,
        persisted: AutomationJobEntity?,
        detail: String,
        runId: Long?,
        correlationId: String?
    ) {
        if (persisted == null) return
        audit.transition(previous, previous.state(), next.state(), runId, correlationId, detail)
    }

    /** Records the failure on the job with the correct retryable/terminal/blocked semantics. */
    private suspend fun failStep(
        job: AutomationJobEntity,
        error: Throwable,
        step: String,
        rules: AutomationRuleEntity,
        runId: Long,
        correlationId: String
    ): AutomationJobEntity {
        val kind = FailureClassifier.classify(error)
        val message = error.message ?: error.javaClass.simpleName
        val target = when (kind) {
            FailureKind.BLOCKED -> JobState.BLOCKED
            FailureKind.TERMINAL -> JobState.FAILED_TERMINAL
            FailureKind.CANCELLED -> JobState.CANCELLED
            FailureKind.RETRYABLE -> JobState.FAILED_RETRYABLE
        }
        val outcome = JobStateMachine.transition(
            job = job,
            target = target,
            now = clock.now(),
            retryPolicy = buildRetryPolicy(rules),
            error = message,
            failedStep = step,
            failureKind = kind,
            blockageReason = if (kind == FailureKind.BLOCKED) message else null,
            random = random
        )
        val persisted = persistTransition(job, outcome.job, runId, correlationId)
        val result = persisted ?: job
        automationDao.registerFailure(message, clock.now())
        audit.warn(
            if (kind == FailureKind.RETRYABLE) "RETRY_SCHEDULED" else "FAILURE",
            "[${job.jobId}] step $step failed ($kind): $message -> ${result.currentState}" +
                if (kind == FailureKind.RETRYABLE) " (next attempt at ${result.nextAttemptAt})" else "",
            runId,
            job.jobId,
            correlationId
        )
        if (kind == FailureKind.RETRYABLE) {
            audit.retryScheduled(result, runId, correlationId, result.nextAttemptAt - clock.now())
        }
        return result
    }

    private suspend fun deriveEvaluation(propertyId: String, rules: AutomationRuleEntity): QualificationEvaluation? {
        val property = propertyDao.getPropertyById(propertyId) ?: return null
        val analysis = runCatching { financialRepository.runAnalysis(propertyId) }.getOrNull() ?: return null
        return QualificationEngine.evaluate(property, analysis, rules)
    }

    private suspend fun closeRun(runId: Long, status: String, summary: String, failureReason: String?) {
        withContext(NonCancellable) {
            automationDao.closeRun(
                runId = runId,
                status = status,
                endTime = clock.now(),
                summary = summary,
                failureReason = failureReason
            )
        }
    }

    private suspend fun closeActiveRuns(status: String, reason: String) {
        withContext(NonCancellable) {
            val now = clock.now()
            for (run in automationDao.getRunsByStatus(AutomationRunStatus.RUNNING)) {
                automationDao.closeRun(
                    runId = run.id,
                    status = status,
                    endTime = now,
                    summary = "Closed by operator: $reason",
                    failureReason = reason
                )
            }
        }
    }

    private suspend fun ensureStateRow() {
        if (automationDao.getAutomationState() == null) {
            automationDao.saveAutomationState(AutomationStateEntity())
        }
    }

    /** Fail-closed check performed at every step boundary. */
    private fun ensureExecutionAllowed() {
        val persistedKill = _killSwitch.value.engaged
        if (persistedKill) {
            throw AutomationHaltException(_killSwitch.value.reason ?: "Global kill switch engaged", killSwitch = true)
        }
        if (!runningFlag.get()) {
            throw AutomationHaltException("Execution stopped by operator", killSwitch = false)
        }
    }

    private suspend fun doubleCheckKillSwitch() {
        val persisted = runCatching { automationDao.getAutomationState() }.getOrNull()
        if (persisted?.killSwitchEngaged == true) {
            _killSwitch.value = KillSwitchState(true, persisted.killSwitchReason, persisted.killSwitchEngagedAt)
            throw AutomationHaltException(persisted.killSwitchReason ?: "Global kill switch engaged", killSwitch = true)
        }
        ensureExecutionAllowed()
    }

    private fun startFallbackLoopIfNeeded(trigger: String) {
        if (fallbackJob?.isActive == true) return
        fallbackJob = engineScope.launch {
            val outcome = runCatching { executeCycle(CycleRequest(trigger = trigger)) }
            outcome.exceptionOrNull()?.let { error ->
                if (error !is CancellationException) {
                    audit.error("FALLBACK_CYCLE", "In-process fallback cycle failed: ${error.message}")
                }
            }
        }
    }

    private fun mergeStats(stats: CycleStats, outcome: JobOutcome): CycleStats = stats.copy(
        propertiesAnalyzed = stats.propertiesAnalyzed + outcome.analyzed,
        dealsQualified = stats.dealsQualified + outcome.qualified,
        offersCreated = stats.offersCreated + outcome.offersCreated,
        offersSent = stats.offersSent + outcome.sent,
        jobsProcessed = stats.jobsProcessed + if (outcome.processed) 1 else 0,
        jobsFailed = stats.jobsFailed + outcome.failed,
        jobsBlocked = stats.jobsBlocked + outcome.blocked
    )

    private data class StepResult(
        val job: AutomationJobEntity,
        val advanced: Boolean,
        val outcome: JobOutcome,
        val analysis: FinancialResult? = null,
        val evaluation: QualificationEvaluation? = null
    ) {
        companion object {
            fun contended(job: AutomationJobEntity) = StepResult(job, advanced = false, outcome = JobOutcome.skipped())
        }
    }

    private data class JobOutcome(
        val analyzed: Int = 0,
        val qualified: Int = 0,
        val offersCreated: Int = 0,
        val sent: Int = 0,
        val failed: Int = 0,
        val blocked: Int = 0,
        val processed: Boolean = false
    ) {
        fun merge(other: JobOutcome) = JobOutcome(
            analyzed = analyzed + other.analyzed,
            qualified = qualified + other.qualified,
            offersCreated = offersCreated + other.offersCreated,
            sent = sent + other.sent,
            failed = failed + other.failed,
            blocked = blocked + other.blocked,
            processed = processed || other.processed
        )

        companion object {
            fun skipped(
                analyzed: Int = 0,
                qualified: Int = 0,
                offersCreated: Int = 0,
                sent: Int = 0,
                failed: Int = 0,
                blocked: Boolean = false
            ) = JobOutcome(
                analyzed = analyzed,
                qualified = qualified,
                offersCreated = offersCreated,
                sent = sent,
                failed = failed,
                blocked = if (blocked) 1 else 0,
                processed = analyzed + qualified + offersCreated + sent + failed > 0 || blocked
            )
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        const val MAX_STEPS_PER_JOB = 8
    }
}
