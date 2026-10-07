package com.example.automation

import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.local.dao.AutomationDao
import com.example.data.local.entity.*
import com.example.data.repository.PreSendValidationResult
import com.example.domain.automation.AutomationClock
import com.example.domain.automation.AutomationWorkScheduler
import com.example.domain.automation.FinancialAnalysisGateway
import com.example.domain.automation.NetworkStatusProvider
import com.example.domain.automation.OfferGateway
import com.example.domain.automation.PropertySourceGateway
import com.example.domain.finance.FinancialResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [AutomationDao] that mirrors the semantics that matter for the execution system:
 * compare-and-swap transitions, lease acquisition with expiry, unique idempotency keys
 * (REPLACE semantics) and "close a run only while RUNNING". All access is serialized, so the
 * concurrency tests exercise the real cross-engine races.
 */
class FakeAutomationDao : AutomationDao {

    private val lock = Any()
    private val rules = HashMap<String, AutomationRuleEntity>()
    private val runs = LinkedHashMap<Long, AutomationRunEntity>()
    private val logs = mutableListOf<AutomationLogEntity>()
    private val jobs = LinkedHashMap<String, AutomationJobEntity>()
    private val executions = LinkedHashMap<String, AutomationExecutionEntity>()
    private var state: AutomationStateEntity? = null
    private val runIds = AtomicInteger(1)
    private val logIds = AtomicInteger(1)

    // ------------------------------------------------------------------ test helpers

    fun seedRules(entity: AutomationRuleEntity) = synchronized(lock) { rules["DEFAULT"] = entity }

    fun seedState(entity: AutomationStateEntity) = synchronized(lock) { state = entity }

    fun seedJob(job: AutomationJobEntity) = synchronized(lock) { jobs[job.jobId] = job }

    fun seedRun(run: AutomationRunEntity): Long = synchronized(lock) {
        val id = if (run.id != 0L) run.id else runIds.getAndIncrement().toLong()
        runs[id] = run.copy(id = id)
        id
    }

    fun job(jobId: String): AutomationJobEntity? = synchronized(lock) { jobs[jobId] }

    fun allJobs(): List<AutomationJobEntity> = synchronized(lock) { jobs.values.toList() }

    fun logMessages(): List<String> = synchronized(lock) { logs.map { "${it.tag}:${it.message}" } }

    fun logsForJob(jobId: String): List<AutomationLogEntity> =
        synchronized(lock) { logs.filter { it.jobId == jobId } }

    fun execution(key: String): AutomationExecutionEntity? = synchronized(lock) { executions[key] }

    fun executionCount(): Int = synchronized(lock) { executions.size }

    fun runCount(): Int = synchronized(lock) { runs.size }

    fun currentState(): AutomationStateEntity? = synchronized(lock) { state }

    fun mutationsOf(jobId: String): Int = synchronized(lock) { logs.count { it.jobId == jobId } }

    // ------------------------------------------------------------------ rules

    override fun getRulesFlow(): Flow<AutomationRuleEntity?> = flow { emit(getRules()) }

    override suspend fun getRules(): AutomationRuleEntity? = synchronized(lock) { rules["DEFAULT"] }

    override suspend fun insertOrUpdateRules(rules: AutomationRuleEntity) {
        synchronized(lock) { this.rules[rules.id] = rules }
    }

    // ------------------------------------------------------------------ runs

    override fun getAllRuns(): Flow<List<AutomationRunEntity>> =
        flow { emit(synchronized(lock) { runs.values.sortedByDescending { it.startTime } }) }

    override suspend fun getLatestRun(): AutomationRunEntity? =
        synchronized(lock) { runs.values.maxByOrNull { it.startTime } }

    override suspend fun getRunById(runId: Long): AutomationRunEntity? = synchronized(lock) { runs[runId] }

    override suspend fun getRunsByStatus(status: String): List<AutomationRunEntity> =
        synchronized(lock) { runs.values.filter { it.status == status }.sortedBy { it.startTime } }

    override suspend fun getStaleRuns(staleBefore: Long): List<AutomationRunEntity> = synchronized(lock) {
        runs.values.filter { it.status == AutomationRunStatus.RUNNING && it.heartbeatAt < staleBefore }
            .sortedBy { it.startTime }
    }

    override suspend fun insertRun(run: AutomationRunEntity): Long = synchronized(lock) {
        val id = if (run.id != 0L) run.id else runIds.getAndIncrement().toLong()
        runs[id] = run.copy(id = id)
        id
    }

    override suspend fun updateRun(run: AutomationRunEntity) {
        synchronized(lock) { runs[run.id] = run }
    }

    override suspend fun touchRunHeartbeat(runId: Long, heartbeatAt: Long): Int = synchronized(lock) {
        val run = runs[runId] ?: return@synchronized 0
        if (run.status != AutomationRunStatus.RUNNING) return@synchronized 0
        runs[runId] = run.copy(heartbeatAt = heartbeatAt)
        1
    }

    override suspend fun closeRun(
        runId: Long,
        status: String,
        endTime: Long,
        summary: String,
        failureReason: String?
    ): Int = synchronized(lock) {
        val run = runs[runId] ?: return@synchronized 0
        if (run.status != AutomationRunStatus.RUNNING) return@synchronized 0
        runs[runId] = run.copy(status = status, endTime = endTime, summary = summary, failureReason = failureReason)
        1
    }

    override suspend fun updateRunStats(
        runId: Long,
        propertiesFound: Int,
        propertiesAnalyzed: Int,
        dealsQualified: Int,
        offersCreated: Int,
        offersSent: Int,
        jobsProcessed: Int,
        jobsRecovered: Int,
        jobsFailed: Int,
        jobsBlocked: Int,
        now: Long
    ): Int = synchronized(lock) {
        val run = runs[runId] ?: return@synchronized 0
        runs[runId] = run.copy(
            propertiesFound = propertiesFound,
            propertiesAnalyzed = propertiesAnalyzed,
            dealsQualified = dealsQualified,
            offersCreated = offersCreated,
            offersSent = offersSent,
            jobsProcessed = jobsProcessed,
            jobsRecovered = jobsRecovered,
            jobsFailed = jobsFailed,
            jobsBlocked = jobsBlocked,
            heartbeatAt = now
        )
        1
    }

    // ------------------------------------------------------------------ logs

    override fun getRecentLogs(): Flow<List<AutomationLogEntity>> =
        flow { emit(synchronized(lock) { logs.sortedByDescending { it.timestamp }.take(300) }) }

    override fun getErrorLogs(): Flow<List<AutomationLogEntity>> =
        flow { emit(synchronized(lock) { logs.filter { it.level == "ERROR" }.sortedByDescending { it.timestamp } }) }

    override fun getLogsForJobFlow(jobId: String): Flow<List<AutomationLogEntity>> =
        flow { emit(synchronized(lock) { logs.filter { it.jobId == jobId }.sortedBy { it.timestamp } }) }

    override suspend fun getLogsForRun(runId: Long): List<AutomationLogEntity> =
        synchronized(lock) { logs.filter { it.runId == runId }.sortedBy { it.timestamp } }

    override suspend fun insertLog(log: AutomationLogEntity) {
        synchronized(lock) { logs += log.copy(id = logIds.getAndIncrement().toLong()) }
    }

    override suspend fun clearLogs() {
        synchronized(lock) { logs.clear() }
    }

    // ------------------------------------------------------------------ engine state

    override fun getAutomationStateFlow(): Flow<AutomationStateEntity?> = flow { emit(getAutomationState()) }

    override suspend fun getAutomationState(): AutomationStateEntity? = synchronized(lock) { state }

    override suspend fun saveAutomationState(state: AutomationStateEntity) {
        synchronized(lock) { this.state = state }
    }

    override suspend fun updateProgress(operation: String, address: String, stage: String, now: Long): Int =
        synchronized(lock) {
            val current = state ?: return@synchronized 0
            state = current.copy(
                currentOperation = operation,
                currentPropertyAddress = if (address.isNotBlank()) address else current.currentPropertyAddress,
                currentStage = stage,
                lastActivityTime = now
            )
            1
        }

    override suspend fun setEnabled(enabled: Boolean, now: Long): Int = synchronized(lock) {
        val current = state ?: return@synchronized 0
        state = current.copy(isEnabled = enabled, lastActivityTime = now)
        1
    }

    override suspend fun registerSuccess(action: String, now: Long): Int = synchronized(lock) {
        val current = state ?: return@synchronized 0
        state = current.copy(
            successfulJobs = current.successfulJobs + 1,
            lastSuccessfulAction = action,
            lastActivityTime = now
        )
        1
    }

    override suspend fun registerFailure(error: String?, now: Long): Int = synchronized(lock) {
        val current = state ?: return@synchronized 0
        state = current.copy(
            failedJobs = current.failedJobs + 1,
            lastError = error ?: current.lastError,
            lastActivityTime = now
        )
        1
    }

    override suspend fun setKillSwitch(engaged: Boolean, reason: String?, since: Long?, now: Long): Int =
        synchronized(lock) {
            val current = state ?: return@synchronized 0
            state = current.copy(
                killSwitchEngaged = engaged,
                killSwitchReason = reason,
                killSwitchEngagedAt = since,
                lastActivityTime = now
            )
            1
        }

    override suspend fun markEngineStarted(startedAt: Long, runId: Long?, now: Long): Int = synchronized(lock) {
        val current = state ?: return@synchronized 0
        state = current.copy(engineStartedAt = startedAt, activeRunId = runId, lastActivityTime = now)
        1
    }

    override suspend fun registerRecovery(now: Long, recovered: Int): Int = synchronized(lock) {
        val current = state ?: return@synchronized 0
        state = current.copy(
            lastRecoveryAt = now,
            recoveredJobsTotal = current.recoveredJobsTotal + recovered,
            lastActivityTime = now
        )
        1
    }

    override suspend fun markCycleEnqueued(now: Long): Int = synchronized(lock) {
        val current = state ?: return@synchronized 0
        state = current.copy(lastWorkerEnqueuedAt = now)
        1
    }

    override suspend fun tryAcquireCycleLease(
        owner: String,
        runId: Long,
        leaseExpiresAt: Long,
        now: Long
    ): Int = synchronized(lock) {
        val current = state ?: return@synchronized 0
        if (current.cycleLeaseOwner != null && current.cycleLeaseExpiresAt > now) return@synchronized 0
        state = current.copy(
            cycleLeaseOwner = owner,
            cycleLeaseExpiresAt = leaseExpiresAt,
            activeRunId = if (runId != 0L) runId else current.activeRunId,
            lastActivityTime = now
        )
        1
    }

    override suspend fun renewCycleLease(owner: String, leaseExpiresAt: Long, now: Long): Int = synchronized(lock) {
        val current = state ?: return@synchronized 0
        if (current.cycleLeaseOwner != owner) return@synchronized 0
        state = current.copy(cycleLeaseExpiresAt = leaseExpiresAt, lastActivityTime = now)
        1
    }

    override suspend fun releaseCycleLease(owner: String, now: Long): Int = synchronized(lock) {
        val current = state ?: return@synchronized 0
        if (current.cycleLeaseOwner != owner) return@synchronized 0
        state = current.copy(
            cycleLeaseOwner = null,
            cycleLeaseExpiresAt = 0L,
            activeRunId = null,
            lastActivityTime = now
        )
        1
    }

    // ------------------------------------------------------------------ jobs

    override fun getAllJobsFlow(): Flow<List<AutomationJobEntity>> =
        flow { emit(synchronized(lock) { jobs.values.sortedByDescending { it.updatedAt }.take(300) }) }

    override suspend fun getJobById(jobId: String): AutomationJobEntity? = synchronized(lock) { jobs[jobId] }

    override suspend fun getJobByPropertyId(propertyId: String): AutomationJobEntity? = synchronized(lock) {
        jobs.values.filter { it.propertyId == propertyId }.maxByOrNull { it.updatedAt }
    }

    override suspend fun getJobByIdempotencyKey(key: String): AutomationJobEntity? = synchronized(lock) {
        jobs.values.firstOrNull { it.idempotencyKey == key }
    }

    override suspend fun getJobByPropertyIdInStates(
        propertyId: String,
        states: List<String>
    ): AutomationJobEntity? = synchronized(lock) {
        jobs.values.filter { it.propertyId == propertyId && it.currentState in states }
            .maxByOrNull { it.updatedAt }
    }

    override suspend fun getJobsInStates(states: List<String>, limit: Int): List<AutomationJobEntity> =
        synchronized(lock) {
            jobs.values.filter { it.currentState in states }.sortedBy { it.updatedAt }.take(limit)
        }

    override suspend fun getDueRetryableJobs(now: Long, limit: Int): List<AutomationJobEntity> = synchronized(lock) {
        jobs.values.filter {
            it.currentState == JobState.FAILED_RETRYABLE.name && it.attempts < it.maxRetries && it.nextAttemptAt <= now
        }.sortedBy { it.nextAttemptAt }.take(limit)
    }

    override suspend fun getJobsWithExpiredLease(
        states: List<String>,
        now: Long,
        limit: Int
    ): List<AutomationJobEntity> = synchronized(lock) {
        jobs.values.filter {
            it.currentState in states && it.leaseOwner != null && it.leaseExpiresAt <= now
        }.sortedBy { it.updatedAt }.take(limit)
    }

    override suspend fun countJobsInState(state: String): Int =
        synchronized(lock) { jobs.values.count { it.currentState == state } }

    override suspend fun countJobsInStates(states: List<String>): Int =
        synchronized(lock) { jobs.values.count { it.currentState in states } }

    override suspend fun insertOrUpdateJob(job: AutomationJobEntity) {
        synchronized(lock) {
            // SQLite REPLACE semantics for the primary key and the unique idempotency key.
            val conflictingKeys = jobs.filterValues {
                it.jobId == job.jobId ||
                    (job.idempotencyKey.isNotEmpty() && it.idempotencyKey == job.idempotencyKey)
            }.keys
            conflictingKeys.forEach { jobs.remove(it) }
            jobs[job.jobId] = job
        }
    }

    override suspend fun casJobState(
        jobId: String,
        expectedStates: List<String>,
        newState: String,
        now: Long
    ): Int = synchronized(lock) {
        val current = jobs[jobId] ?: return@synchronized 0
        if (current.currentState !in expectedStates) return@synchronized 0
        jobs[jobId] = current.copy(currentState = newState, updatedAt = now)
        1
    }

    override suspend fun claimJobLease(
        jobId: String,
        owner: String,
        leaseExpiresAt: Long,
        now: Long
    ): Int = synchronized(lock) {
        val current = jobs[jobId] ?: return@synchronized 0
        if (current.leaseOwner != null && current.leaseExpiresAt > now) return@synchronized 0
        jobs[jobId] = current.copy(leaseOwner = owner, leaseExpiresAt = leaseExpiresAt, updatedAt = now)
        1
    }

    override suspend fun releaseJobLease(jobId: String, owner: String, now: Long): Int = synchronized(lock) {
        val current = jobs[jobId] ?: return@synchronized 0
        if (current.leaseOwner != owner) return@synchronized 0
        jobs[jobId] = current.copy(leaseOwner = null, leaseExpiresAt = 0L, updatedAt = now)
        1
    }

    override suspend fun pruneJobs(states: List<String>, before: Long): Int = synchronized(lock) {
        val toRemove = jobs.filterValues { it.currentState in states && it.updatedAt < before }.keys
        toRemove.forEach { jobs.remove(it) }
        toRemove.size
    }

    // ------------------------------------------------------------------ idempotency ledger

    override suspend fun getExecution(key: String): AutomationExecutionEntity? =
        synchronized(lock) { executions[key] }

    override suspend fun getExecutionsForJob(jobId: String): List<AutomationExecutionEntity> =
        synchronized(lock) { executions.values.filter { it.jobId == jobId }.sortedBy { it.startedAt } }

    override suspend fun getExecutionsByStatus(status: String, limit: Int): List<AutomationExecutionEntity> =
        synchronized(lock) { executions.values.filter { it.status == status }.take(limit) }

    override suspend fun insertOrUpdateExecution(execution: AutomationExecutionEntity) {
        synchronized(lock) { executions[execution.idempotencyKey] = execution }
    }
}

/** Injectable clock for deterministic retry/backoff/lease tests. */
class FakeClock(var currentTime: Long = 1_700_000_000_000L) : AutomationClock {
    override fun now(): Long = currentTime

    fun advance(ms: Long) {
        currentTime += ms
    }
}

/** Records what the engine asked of the durable scheduler. */
class RecordingScheduler(override val isPersistent: Boolean = true) : AutomationWorkScheduler {
    val immediateRequests = mutableListOf<String>()
    val periodicRequests = mutableListOf<Int>()
    var cancellations = 0
        private set

    override fun enqueueImmediateCycle(reason: String) {
        immediateRequests += reason
    }

    override fun schedulePeriodic(intervalMinutes: Int) {
        periodicRequests += intervalMinutes
    }

    override fun cancelAll(reason: String) {
        cancellations++
    }
}

class FakeNetworkMonitor(var online: Boolean = true) : NetworkStatusProvider {
    override fun isOnlineNow(): Boolean = online
}

class FakeFinancialGateway(
    var analysis: FinancialResult,
    var hasAnalysisFlag: Boolean = false
) : FinancialAnalysisGateway {
    var analysisCalls = 0
    var failure: Throwable? = null

    override suspend fun runAnalysis(propertyId: String): FinancialResult {
        analysisCalls++
        failure?.let { throw it }
        hasAnalysisFlag = true
        return analysis
    }

    override suspend fun hasAnalysis(propertyId: String): Boolean = hasAnalysisFlag
}

class FakeOfferGateway : OfferGateway {
    val offers = LinkedHashMap<String, OfferEntity>()
    var sendSucceeds = true
    var sendFailureMessage = "Network timeout while contacting Gmail"
    var generateFails: Throwable? = null
    var validationResult = PreSendValidationResult(true, null)
    var generateCalls = 0
    var sendAttempts = 0
    var onGenerate: ((String) -> Unit)? = null

    override suspend fun findOffer(offerId: String): OfferEntity? = synchronized(offers) { offers[offerId] }

    override suspend fun findOfferForProperty(propertyId: String): OfferEntity? = synchronized(offers) {
        offers.values.lastOrNull { it.propertyId == propertyId }
    }

    override suspend fun validateForSend(context: android.content.Context, offerId: String): PreSendValidationResult =
        validationResult

    override suspend fun generateDraftOffer(
        context: android.content.Context,
        propertyId: String,
        customPrice: Double?,
        suggestedRecipientEmail: String?
    ): OfferEntity {
        generateFails?.let { throw it }
        synchronized(offers) {
            val existing = offers.values.lastOrNull { it.propertyId == propertyId }
            if (existing != null && existing.status != "DRAFT" && existing.status != "FAILED") return existing
            generateCalls++
            onGenerate?.invoke(propertyId)
            val offer = testOffer(
                id = "OFFER-T$generateCalls",
                propertyId = propertyId,
                price = customPrice ?: 250_000.0,
                email = suggestedRecipientEmail ?: "agent@example.com"
            )
            offers[offer.id] = offer
            return offer
        }
    }

    override suspend fun transmitOffer(context: android.content.Context, offerId: String): Boolean {
        sendAttempts++
        synchronized(offers) {
            val offer = offers[offerId] ?: return false
            return if (sendSucceeds) {
                offers[offerId] = offer.copy(status = "SENT", sentAt = 1L, lastError = null)
                true
            } else {
                offers[offerId] = offer.copy(status = "FAILED", lastError = sendFailureMessage)
                false
            }
        }
    }

    fun offerFor(propertyId: String): OfferEntity? = synchronized(offers) {
        offers.values.lastOrNull { it.propertyId == propertyId }
    }
}

class FakePropertySource(var bundles: List<NormalizedPropertyBundle> = emptyList()) : PropertySourceGateway {
    var fetchCalls = 0
    var onFetch: (() -> Unit)? = null

    override suspend fun fetchBundles(limitPerSource: Int): List<NormalizedPropertyBundle> {
        fetchCalls++
        onFetch?.invoke()
        return bundles.take(limitPerSource)
    }
}

// ------------------------------------------------------------------ builders

fun testOffer(
    id: String = "OFFER-1",
    propertyId: String = "prop-1",
    status: String = "READY",
    price: Double = 250_000.0,
    email: String = "agent@example.com"
) = OfferEntity(
    id = id,
    propertyId = propertyId,
    recipientName = "Listing Agent",
    recipientEmail = email,
    offerPrice = price,
    earnestMoney = price * 0.02,
    inspectionPeriodDays = 10,
    closingPeriodDays = 21,
    contingencies = "Inspection",
    terms = "AS-IS",
    conditions = "Clear title",
    expirationDate = "Dec 01, 2026",
    generatedLetterContent = "Letter of intent",
    pdfPath = "/tmp/$id.pdf",
    status = status,
    createdAt = 1_700_000_000_000L
)

fun testJob(
    jobId: String = "JOB-1",
    runId: Long = 1L,
    propertyId: String = "prop-1",
    address: String = "100 Test St",
    state: JobState = JobState.DISCOVERED,
    lastSuccessful: JobState = JobState.DISCOVERED,
    idempotencyKey: String = "$runId:$propertyId",
    attempts: Int = 0,
    maxRetries: Int = 3,
    offerId: String? = null,
    emailMessageId: String? = null,
    failedStep: String? = null,
    nextAttemptAt: Long = 0L,
    leaseOwner: String? = null,
    leaseExpiresAt: Long = 0L,
    recoveryCount: Int = 0,
    startedAt: Long? = null,
    completedAt: Long? = null,
    createdAt: Long = 1_700_000_000_000L,
    updatedAt: Long = 1_700_000_000_000L
) = AutomationJobEntity(
    jobId = jobId,
    runId = runId,
    propertyId = propertyId,
    propertyAddress = address,
    currentState = state.name,
    lastSuccessfulState = lastSuccessful.name,
    idempotencyKey = idempotencyKey,
    failedStep = failedStep,
    offerId = offerId,
    emailMessageId = emailMessageId,
    recipientEmail = "agent@example.com",
    attempts = attempts,
    maxRetries = maxRetries,
    nextAttemptAt = nextAttemptAt,
    leaseOwner = leaseOwner,
    leaseExpiresAt = leaseExpiresAt,
    recoveryCount = recoveryCount,
    startedAt = startedAt,
    completedAt = completedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun testRun(
    id: Long = 0L,
    startTime: Long = 1_700_000_000_000L,
    status: String = AutomationRunStatus.RUNNING,
    heartbeatAt: Long = startTime,
    trigger: String = "MANUAL"
) = AutomationRunEntity(
    id = id,
    startTime = startTime,
    status = status,
    summary = "",
    trigger = trigger,
    heartbeatAt = heartbeatAt
)

fun testRules(
    maxRetries: Int = 3,
    autoGenerateOffers: Boolean = true,
    autoSendOffers: Boolean = false,
    scanIntervalMinutes: Int = 15,
    staleRunTimeoutMinutes: Int = 15,
    maxRecoveryAttempts: Int = 20,
    maxJobsPerCycle: Int = 25,
    maxAnalysesPerRun: Int = 5,
    maxPropertiesPerCycle: Int = 10,
    retryBackoffBaseSeconds: Int = 30,
    retryBackoffMaxMinutes: Int = 30
) = AutomationRuleEntity(
    // Permissive qualification thresholds: engine tests exercise the pipeline, not the qualifier.
    maxPurchasePrice = 10_000_000.0,
    minCashFlow = 0.0,
    minCapRate = 0.0,
    minDscr = 0.0,
    minCashOnCash = 0.0,
    maxRenovationCost = 10_000_000.0,
    minEstimatedRent = 0.0,
    maxRiskScore = 100,
    allowedLocations = "Austin, Dallas, Houston, Phoenix, Atlanta, Miami, Chicago",
    allowedPropertyTypes = "Single Family, Multi-Family, Condo, Townhouse",
    maxRetries = maxRetries,
    autoGenerateOffers = autoGenerateOffers,
    autoSendOffers = autoSendOffers,
    scanIntervalMinutes = scanIntervalMinutes,
    staleRunTimeoutMinutes = staleRunTimeoutMinutes,
    maxRecoveryAttempts = maxRecoveryAttempts,
    maxJobsPerCycle = maxJobsPerCycle,
    maxAnalysesPerRun = maxAnalysesPerRun,
    maxPropertiesPerCycle = maxPropertiesPerCycle,
    retryBackoffBaseSeconds = retryBackoffBaseSeconds,
    retryBackoffMaxMinutes = retryBackoffMaxMinutes
)

// ------------------------------------------------------------------ engine harness

/**
 * Wires a real (in-memory) Room database - so the SQL compare-and-swap, lease and unique-index
 * semantics of the production DAO are exercised - together with fake gateway implementations and
 * a deterministic clock.
 */
class EngineTestHarness(
    context: android.content.Context,
    instanceId: String = "test-1"
) {
    val database: com.example.data.local.AppDatabase =
        androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.local.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    val dao: AutomationDao = database.automationDao()
    val propertyDao = database.propertyDao()
    val clock = FakeClock()
    val network = FakeNetworkMonitor()
    val scheduler = RecordingScheduler()
    val offers = FakeOfferGateway()
    val financial = FakeFinancialGateway(qualifyingAnalysis())
    val source = FakePropertySource()
    val engine = com.example.domain.automation.AutomationEngine(
        context = context,
        automationDao = dao,
        propertyDao = propertyDao,
        propertySourceManager = source,
        financialRepository = financial,
        offerRepository = offers,
        networkMonitor = network,
        scheduler = scheduler,
        clock = clock,
        random = kotlin.random.Random(7),
        instanceId = instanceId
    )

    fun seedRules(rules: AutomationRuleEntity = testRules()) = kotlinx.coroutines.runBlocking {
        dao.insertOrUpdateRules(rules)
    }

    fun job(jobId: String): AutomationJobEntity? = kotlinx.coroutines.runBlocking { dao.getJobById(jobId) }

    fun jobs(): List<AutomationJobEntity> = kotlinx.coroutines.runBlocking {
        dao.getAllJobsFlow().first()
    }

    fun runs(): List<AutomationRunEntity> = kotlinx.coroutines.runBlocking {
        dao.getAllRuns().first()
    }

    fun state(): AutomationStateEntity? = kotlinx.coroutines.runBlocking { dao.getAutomationState() }

    fun close() {
        if (ownsDatabase) database.close()
    }
}

/** A deterministic financial result used by the engine tests. */
fun qualifyingAnalysis(): FinancialResult = com.example.domain.finance.FinancialEngine.calculate(
    com.example.domain.finance.FinancialInput(
        purchasePrice = 300_000.0,
        closingCosts = 7_500.0,
        renovationCost = 5_000.0,
        monthlyRent = 4_000.0,
        propertyTaxAnnual = 3_600.0,
        insuranceAnnual = 1_800.0
    )
)

/** Builds a normalized discovery bundle for the fake property source. */
fun testBundle(
    id: String = "prop-1",
    address: String = "100 Test St",
    city: String = "Austin",
    state: String = "TX",
    price: Double = 300_000.0,
    propertyType: String = "Single Family",
    sourceType: String = "ON_MARKET"
): NormalizedPropertyBundle {
    val property = PropertyEntity(
        id = id,
        sourceType = sourceType,
        title = "Test Property $id",
        address = address,
        city = city,
        state = state,
        zipCode = "78701",
        latitude = 30.27,
        longitude = -97.74,
        price = price,
        propertyType = propertyType,
        bedrooms = 3,
        bathrooms = 2.0,
        squareFeet = 1_700,
        yearBuilt = 1998,
        lotSizeSqFt = 5_500,
        description = "Test listing",
        status = "Active",
        primaryImageUrl = "",
        scannedAt = 1_700_000_000_000L
    )
    return NormalizedPropertyBundle(
        property = property,
        images = listOf(
            PropertyImageEntity(
                propertyId = id,
                imageUrl = "https://example.com/$id.jpg",
                caption = "front",
                isPrimary = true
            )
        ),
        marketData = MarketDataEntity(
            propertyId = id,
            estimatedValue = price,
            neighborhoodAppreciationRate = 3.0,
            medianAreaPrice = price,
            averageDaysOnMarket = 30,
            pricePerSqFt = price / 1_700.0,
            marketDemand = "High"
        ),
        rentEstimate = RentEstimateEntity(
            propertyId = id,
            estimatedRent = 4_000.0,
            rentRangeLow = 3_600.0,
            rentRangeHigh = 4_400.0,
            rentConfidenceScore = 0.9,
            grossYield = 16.0
        ),
        taxRecord = TaxRecordEntity(
            propertyId = id,
            annualTaxAmount = 3_600.0,
            assessmentYear = 2026,
            assessedValue = price
        ),
        salesHistory = emptyList(),
        comps = emptyList()
    )
}
