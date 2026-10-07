package com.example.domain.automation

import android.content.Context
import com.example.data.adapter.PropertySourceManager
import com.example.data.local.dao.AutomationDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.*
import com.example.data.repository.FinancialRepository
import com.example.data.repository.OfferRepository
import com.example.domain.ai.GeminiManager
import com.example.domain.qualification.QualificationEngine
import com.example.util.NetworkMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class AutomationStatus {
    IDLE,
    RUNNING,
    SCANNING,
    ANALYZING,
    QUALIFYING,
    GENERATING_OFFERS,
    SENDING_OFFERS,
    STOPPED,
    ERROR
}

class AutomationEngine(
    private val context: Context,
    private val automationDao: AutomationDao,
    private val propertyDao: PropertyDao,
    private val propertySourceManager: PropertySourceManager,
    private val financialRepository: FinancialRepository,
    private val offerRepository: OfferRepository,
    private val geminiManager: GeminiManager,
    private val networkMonitor: NetworkMonitor
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var automationJob: Job? = null
    private var recoveryJob: Job? = null
    private val isRunningFlag = AtomicBoolean(false)

    private val _status = MutableStateFlow(AutomationStatus.IDLE)
    val status: StateFlow<AutomationStatus> = _status.asStateFlow()

    private val _currentTaskDescription = MutableStateFlow("Engine Standby")
    val currentTaskDescription: StateFlow<String> = _currentTaskDescription.asStateFlow()

    private var consecutiveFailures = 0

    init {
        // Recovery must finish before a new automation cycle is allowed to dispatch email.
        recoveryJob = scope.launch {
            recoverInterruptedJobs()
        }
    }

    fun isRunning(): Boolean = isRunningFlag.get()

    fun startAutomation() {
        if (isRunningFlag.compareAndSet(false, true)) {
            automationJob?.cancel()
            automationJob = scope.launch {
                recoveryJob?.join()
                updatePersistentState(
                    isEnabled = true,
                    op = "Starting Automation Engine",
                    stage = "INITIALIZING",
                    addr = ""
                )
                log("INFO", "START", "Autonomous real estate intelligence cycle started by user")
                runAutomationLoop()
            }
        }
    }

    fun stopAutomation(reason: String = "Manual stop triggered by user") {
        if (isRunningFlag.compareAndSet(true, false)) {
            automationJob?.cancel()
            _status.value = AutomationStatus.STOPPED
            _currentTaskDescription.value = "Automation Stopped: $reason"
            scope.launch {
                updatePersistentState(
                    isEnabled = false,
                    op = "Stopped: $reason",
                    stage = "STOPPED",
                    addr = ""
                )
                log("WARN", "STOP", "Automation halted: $reason")
            }
        }
    }

    fun globalKillSwitch() {
        isRunningFlag.set(false)
        automationJob?.cancel()
        _status.value = AutomationStatus.STOPPED
        _currentTaskDescription.value = "GLOBAL KILL SWITCH ACTIVATED - All background processes killed"
        scope.launch {
            updatePersistentState(
                isEnabled = false,
                op = "EMERGENCY ABORT",
                stage = "KILLED",
                addr = ""
            )
            log("ERROR", "KILL_SWITCH", "EMERGENCY ABORT: Global Kill Switch engaged by operator")
        }
    }

    private suspend fun updatePersistentState(
        isEnabled: Boolean,
        op: String,
        stage: String,
        addr: String = "",
        successAction: String? = null,
        error: String? = null
    ) {
        val current = automationDao.getAutomationState() ?: AutomationStateEntity()
        val updated = current.copy(
            isEnabled = isEnabled,
            currentOperation = op,
            currentPropertyAddress = if (addr.isNotBlank()) addr else current.currentPropertyAddress,
            currentStage = stage,
            successfulJobs = if (successAction != null) current.successfulJobs + 1 else current.successfulJobs,
            failedJobs = if (error != null) current.failedJobs + 1 else current.failedJobs,
            lastError = error ?: current.lastError,
            lastSuccessfulAction = successAction ?: current.lastSuccessfulAction,
            lastActivityTime = System.currentTimeMillis()
        )
        automationDao.saveAutomationState(updated)
    }

    private suspend fun recoverInterruptedJobs() {
        try {
            offerRepository.recoverInterruptedSends()
            val recoveredAt = System.currentTimeMillis()
            automationDao.getIncompleteRuns().forEach { interruptedRun ->
                automationDao.updateRun(
                    interruptedRun.copy(
                        endTime = recoveredAt,
                        status = "STOPPED",
                        summary = "Run was interrupted by process termination and recovered safely."
                    )
                )
            }
            val activeJobs = automationDao.getActiveJobs()
            if (activeJobs.isEmpty()) return

            log(
                "WARN",
                "WORKER_RECOVERY",
                "Found ${activeJobs.size} active jobs interrupted by prior process termination. Reconciling states."
            )
            for (job in activeJobs) {
                val now = System.currentTimeMillis()
                val reconciled = when (job.currentState) {
                    JobState.SENDING.name -> {
                        val offer = job.offerId?.let { offerRepository.getOfferById(it) }
                        val send = job.offerId?.let { offerRepository.getEmailSendForOffer(it) }
                        when {
                            offer?.status == "SENT" || offer?.status == "OPENED" || offer?.status == "SIGNED" || send?.status == "SENT" ->
                                job.copy(
                                    currentState = JobState.SENT.name,
                                    lastSuccessfulState = JobState.SENT.name,
                                    emailMessageId = send?.messageId ?: job.emailMessageId,
                                    lastError = null,
                                    updatedAt = now
                                )
                            send != null && send.status in listOf("UNKNOWN", "IN_FLIGHT", "FAILED") ->
                                job.copy(
                                    currentState = JobState.FAILED_TERMINAL.name,
                                    failedStep = "SEND_OFFER",
                                    lastError = send.lastError
                                        ?: "Send outcome cannot be proven; automatic retry is disabled to prevent duplicates.",
                                    updatedAt = now
                                )
                            else ->
                                job.copy(
                                    currentState = JobState.OFFER_READY.name,
                                    lastSuccessfulState = JobState.OFFER_READY.name,
                                    offerId = offer?.id ?: job.offerId,
                                    recipientEmail = offer?.recipientEmail ?: job.recipientEmail,
                                    updatedAt = now
                                )
                        }
                    }
                    JobState.VALIDATING_SEND.name -> job.copy(
                        currentState = JobState.OFFER_READY.name,
                        lastSuccessfulState = JobState.OFFER_READY.name,
                        updatedAt = now
                    )
                    JobState.OFFER_GENERATION.name -> {
                        val existingOffer = offerRepository.getOfferForProperty(job.propertyId)
                        if (existingOffer != null) {
                            job.copy(
                                currentState = JobState.OFFER_READY.name,
                                lastSuccessfulState = JobState.OFFER_READY.name,
                                offerId = existingOffer.id,
                                recipientEmail = existingOffer.recipientEmail,
                                updatedAt = now
                            )
                        } else {
                            job.copy(
                                currentState = JobState.QUALIFIED.name,
                                lastSuccessfulState = JobState.QUALIFIED.name,
                                updatedAt = now
                            )
                        }
                    }
                    JobState.ANALYZING.name -> job.copy(
                        currentState = JobState.DISCOVERED.name,
                        lastSuccessfulState = JobState.DISCOVERED.name,
                        updatedAt = now
                    )
                    JobState.QUALIFYING.name -> job.copy(
                        currentState = JobState.ANALYZED.name,
                        lastSuccessfulState = JobState.ANALYZED.name,
                        updatedAt = now
                    )
                    else -> null
                }
                if (reconciled != null) automationDao.insertOrUpdateJob(reconciled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("ERROR", "WORKER_RECOVERY", "Failed to reconcile interrupted jobs (${e.javaClass.simpleName}).")
        }
    }

    private suspend fun runAutomationLoop() {
        _status.value = AutomationStatus.RUNNING
        val rules = automationDao.getRules() ?: AutomationRuleEntity()

        while (isRunningFlag.get()) {
            if (!networkMonitor.checkIsOnline()) {
                _status.value = AutomationStatus.IDLE
                _currentTaskDescription.value = "Paused (Offline - Waiting for connectivity)"
                try {
                    updatePersistentState(
                        isEnabled = true,
                        op = "Paused: Waiting for network",
                        stage = "OFFLINE_PAUSED"
                    )
                } catch (_: Exception) {
                    // Keep waiting for connectivity; the state flow still reports the paused state.
                }
                delay(5000)
                continue
            }

            val runStartedAt = System.currentTimeMillis()
            val runId = automationDao.insertRun(
                AutomationRunEntity(
                    startTime = runStartedAt,
                    status = "RUNNING",
                    summary = "Cycle in progress..."
                )
            )

            var propFound = 0
            var propAnalyzed = 0
            var dealsQualified = 0
            var offersCreated = 0
            var offersSent = 0

            try {
                // 1. DISCOVERY
                _status.value = AutomationStatus.SCANNING
                _currentTaskDescription.value = "Scanning property feeds (MLS & Distressed)..."
                updatePersistentState(isEnabled = true, op = "Scanning property feeds", stage = "DISCOVERY")

                val bundles = propertySourceManager.fetchAllSources(limitPerSource = rules.maxPropertiesPerCycle)
                propFound = bundles.size
                log("INFO", "PROPERTY_DISCOVERED", "Discovered $propFound target candidate properties across feeds")

                for (bundle in bundles) {
                    if (!isRunningFlag.get()) break

                    // Persistent Job initialization with state machine
                    val existingJob = automationDao.getJobByPropertyId(bundle.property.id)
                    val jobId = existingJob?.jobId ?: ("JOB-" + UUID.randomUUID().toString().take(8).uppercase())

                    var currentJob = existingJob ?: AutomationJobEntity(
                        jobId = jobId,
                        runId = runId,
                        propertyId = bundle.property.id,
                        propertyAddress = bundle.property.address,
                        currentState = JobState.DISCOVERED.name,
                        lastSuccessfulState = JobState.DISCOVERED.name,
                        attempts = 0,
                        maxRetries = rules.maxRetries,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    automationDao.insertOrUpdateJob(currentJob)

                    // Save property & satellite tables
                    propertyDao.insertProperty(bundle.property)
                    propertyDao.insertImages(bundle.images)
                    propertyDao.insertMarketData(bundle.marketData)
                    propertyDao.insertRentEstimate(bundle.rentEstimate)
                    propertyDao.insertTaxRecord(bundle.taxRecord)

                    if (existingJob?.currentState?.let {
                            it in setOf(
                                JobState.SENT.name,
                                JobState.DISQUALIFIED.name,
                                JobState.FAILED_TERMINAL.name,
                                JobState.CANCELLED.name
                            )
                        } == true
                    ) {
                        continue
                    }

                    // Check run analysis limit
                    if (propAnalyzed >= rules.maxAnalysesPerRun) {
                        log("INFO", "LIMIT_REACHED", "Reached max analyses per run limit (${rules.maxAnalysesPerRun})")
                        break
                    }

                    // 2. ANALYZE
                    _status.value = AutomationStatus.ANALYZING
                    _currentTaskDescription.value = "Analyzing finances for ${bundle.property.address}..."
                    updatePersistentState(
                        isEnabled = true,
                        op = "Underwriting ${bundle.property.address}",
                        stage = "ANALYZING",
                        addr = bundle.property.address
                    )
                    log("INFO", "ANALYSIS_STARTED", "Starting deterministic underwriting for ${bundle.property.address}")

                    currentJob = currentJob.copy(
                        currentState = JobState.ANALYZING.name,
                        updatedAt = System.currentTimeMillis()
                    )
                    automationDao.insertOrUpdateJob(currentJob)

                    val finResult = financialRepository.analyzeProperty(bundle.property.id)
                    propAnalyzed++

                    currentJob = currentJob.copy(
                        currentState = JobState.ANALYZED.name,
                        lastSuccessfulState = JobState.ANALYZED.name,
                        analysisId = bundle.property.id,
                        updatedAt = System.currentTimeMillis()
                    )
                    automationDao.insertOrUpdateJob(currentJob)

                    log("SUCCESS", "ANALYSIS_COMPLETED", "Analyzed ${bundle.property.address}: NOI $${finResult.noiAnnual.toInt()}, Cash Flow $${finResult.monthlyCashFlow.toInt()}/mo, Cap ${String.format("%.1f", finResult.capRate)}%, DSCR ${String.format("%.2f", finResult.dscr)}")

                    // 3. QUALIFY
                    _status.value = AutomationStatus.QUALIFYING
                    currentJob = currentJob.copy(
                        currentState = JobState.QUALIFYING.name,
                        updatedAt = System.currentTimeMillis()
                    )
                    automationDao.insertOrUpdateJob(currentJob)

                    val qualEval = QualificationEngine.evaluate(bundle.property, finResult, rules)

                    if (qualEval.isQualified) {
                        dealsQualified++
                        propertyDao.setDealStatus(bundle.property.id, true, qualEval.score)
                        log("SUCCESS", "QUALIFICATION_RESULT", "DEAL QUALIFIED: ${bundle.property.address} (Score: ${qualEval.score}/100, Suggested: $${qualEval.suggestedOfferPrice.toInt()})")

                        currentJob = currentJob.copy(
                            currentState = JobState.QUALIFIED.name,
                            lastSuccessfulState = JobState.QUALIFIED.name,
                            updatedAt = System.currentTimeMillis()
                        )
                        automationDao.insertOrUpdateJob(currentJob)

                        updatePersistentState(
                            isEnabled = true,
                            op = "Deal Qualified: Score ${qualEval.score}/100",
                            stage = "QUALIFIED",
                            addr = bundle.property.address,
                            successAction = "Qualified ${bundle.property.address}"
                        )

                        // 4. GENERATE OFFER (Idempotent: will not duplicate existing offer)
                        if (rules.autoGenerateOffers && offersCreated < rules.maxOffersPerRun) {
                            _status.value = AutomationStatus.GENERATING_OFFERS
                            _currentTaskDescription.value = "Drafting institutional purchase offer for ${bundle.property.address}..."
                            updatePersistentState(
                                isEnabled = true,
                                op = "Generating LOI for ${bundle.property.address}",
                                stage = "OFFER_GENERATION",
                                addr = bundle.property.address
                            )

                            currentJob = currentJob.copy(
                                currentState = JobState.OFFER_GENERATION.name,
                                updatedAt = System.currentTimeMillis()
                            )
                            automationDao.insertOrUpdateJob(currentJob)

                            val offer = offerRepository.generateOffer(
                                context = context,
                                propertyId = bundle.property.id,
                                customPrice = qualEval.suggestedOfferPrice
                            )
                            offersCreated++
                            log("SUCCESS", "OFFER_GENERATED", "Offer ${offer.id} created at $${String.format("%,.0f", offer.offerPrice)}")
                            log("SUCCESS", "PDF_GENERATED", "PDF agreement generated and validated for offer ${offer.id}")

                            currentJob = currentJob.copy(
                                currentState = JobState.OFFER_READY.name,
                                lastSuccessfulState = JobState.OFFER_READY.name,
                                offerId = offer.id,
                                recipientEmail = offer.recipientEmail,
                                updatedAt = System.currentTimeMillis()
                            )
                            automationDao.insertOrUpdateJob(currentJob)

                            updatePersistentState(
                                isEnabled = true,
                                op = "Offer Generated: ${offer.id}",
                                stage = "OFFER_READY",
                                addr = bundle.property.address,
                                successAction = "Generated Offer ${offer.id}"
                            )

                            // 5. AUTO SEND. OfferRepository owns the final validation, durable claim,
                            // send status and audit event so blocked attempts are tracked as well.
                            if (rules.autoSendOffers && offersSent < rules.maxEmailsPerRun && networkMonitor.checkIsOnline()) {
                                _status.value = AutomationStatus.SENDING_OFFERS
                                _currentTaskDescription.value = "Validating & transmitting offer ${offer.id} via Gmail..."
                                currentJob = currentJob.copy(
                                    currentState = JobState.VALIDATING_SEND.name,
                                    updatedAt = System.currentTimeMillis()
                                )
                                automationDao.insertOrUpdateJob(currentJob)

                                currentJob = currentJob.copy(
                                    currentState = JobState.SENDING.name,
                                    updatedAt = System.currentTimeMillis()
                                )
                                automationDao.insertOrUpdateJob(currentJob)

                                val sendOutcome = offerRepository.sendOfferDetailed(
                                    context = context,
                                    offerId = offer.id,
                                    maxAttempts = rules.maxRetries.coerceAtLeast(1)
                                )
                                if (sendOutcome.success) {
                                    offersSent++
                                    log(
                                        "SUCCESS",
                                        "GMAIL_SENT",
                                        "Sent offer ${offer.id}; Gmail message ID ${sendOutcome.messageId ?: "unavailable"}."
                                    )
                                    currentJob = currentJob.copy(
                                        currentState = JobState.SENT.name,
                                        lastSuccessfulState = JobState.SENT.name,
                                        emailMessageId = sendOutcome.messageId,
                                        lastError = null,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    automationDao.insertOrUpdateJob(currentJob)

                                    updatePersistentState(
                                        isEnabled = true,
                                        op = "Offer Sent via Gmail",
                                        stage = "OFFER_SENT",
                                        addr = bundle.property.address,
                                        successAction = "Sent offer ${offer.id}"
                                    )
                                } else {
                                    val errorMsg = sendOutcome.error ?: "Offer email was not sent."
                                    val attempts = currentJob.attempts + if (sendOutcome.attempted) 1 else 0
                                    val nextState = when {
                                        sendOutcome.status == com.example.data.local.entity.OfferEmailSendStatus.RETRYABLE &&
                                            attempts < rules.maxRetries.coerceAtLeast(1) -> JobState.FAILED_RETRYABLE
                                        sendOutcome.status == com.example.data.local.entity.OfferEmailSendStatus.BLOCKED -> JobState.BLOCKED
                                        sendOutcome.status == com.example.data.local.entity.OfferEmailSendStatus.IN_FLIGHT -> JobState.SENDING
                                        else -> JobState.FAILED_TERMINAL
                                    }
                                    val safeError = when (sendOutcome.status) {
                                        com.example.data.local.entity.OfferEmailSendStatus.UNKNOWN ->
                                            "Delivery outcome is unknown; automatic retry is disabled to prevent duplicate email."
                                        com.example.data.local.entity.OfferEmailSendStatus.IN_FLIGHT ->
                                            "Another request is already sending this offer; delivery remains under observation."
                                        else -> errorMsg
                                    }
                                    log("WARN", "SEND_${sendOutcome.status}", "Offer ${offer.id}: $safeError")
                                    currentJob = currentJob.copy(
                                        currentState = nextState.name,
                                        failedStep = "SEND_OFFER",
                                        attempts = attempts,
                                        lastError = safeError,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    automationDao.insertOrUpdateJob(currentJob)
                                }
                            }
                        }
                    } else {
                        propertyDao.setDealStatus(bundle.property.id, false, qualEval.score)
                        val disqReason = qualEval.failedChecks.joinToString("; ").ifBlank { qualEval.summary }
                        currentJob = currentJob.copy(
                            currentState = JobState.DISQUALIFIED.name,
                            lastSuccessfulState = JobState.DISQUALIFIED.name,
                            blockageReason = disqReason,
                            updatedAt = System.currentTimeMillis()
                        )
                        automationDao.insertOrUpdateJob(currentJob)
                    }

                    delay(250) // Cooperative yield
                }

                // Cycle Complete
                val runEndedAt = System.currentTimeMillis()
                automationDao.updateRun(
                    AutomationRunEntity(
                        id = runId,
                        startTime = runStartedAt,
                        endTime = runEndedAt,
                        propertiesFound = propFound,
                        propertiesAnalyzed = propAnalyzed,
                        dealsQualified = dealsQualified,
                        offersCreated = offersCreated,
                        offersSent = offersSent,
                        status = "COMPLETED",
                        summary = "Found: $propFound, Analyzed: $propAnalyzed, Qualified: $dealsQualified, Offers: $offersCreated, Sent: $offersSent"
                    )
                )

                consecutiveFailures = 0
                _status.value = AutomationStatus.IDLE
                _currentTaskDescription.value = "Cycle Complete. Next scan in ${rules.scanIntervalMinutes} min"
                updatePersistentState(
                    isEnabled = true,
                    op = "Cycle Complete. Waiting for interval.",
                    stage = "CYCLE_COMPLETE",
                    successAction = "Completed full scan cycle ($propFound scanned, $dealsQualified qualified)"
                )
                log("INFO", "STOP", "Completed cycle successfully. Sleeping for ${rules.scanIntervalMinutes}m")

                // Wait for scan interval in 1-second chunks so kill switch stops immediately
                val sleepSecs = (rules.scanIntervalMinutes * 60).coerceAtLeast(15)
                for (s in 0 until sleepSecs) {
                    if (!isRunningFlag.get()) break
                    delay(1000)
                }

            } catch (e: CancellationException) {
                val stoppedAt = System.currentTimeMillis()
                withContext(NonCancellable) {
                    try {
                        automationDao.updateRun(
                            AutomationRunEntity(
                                id = runId,
                                startTime = runStartedAt,
                                endTime = stoppedAt,
                                propertiesFound = propFound,
                                propertiesAnalyzed = propAnalyzed,
                                dealsQualified = dealsQualified,
                                offersCreated = offersCreated,
                                offersSent = offersSent,
                                status = "STOPPED",
                                summary = "Cycle stopped or cancelled before completion."
                            )
                        )
                    } catch (_: Exception) {
                        // The durable job/send ledger remains the source of truth during recovery.
                    }
                }
                log("INFO", "STOP", "Automation cycle cancelled.")
                break
            } catch (e: Exception) {
                consecutiveFailures++
                // Avoid persisting exception messages that may contain request details or credentials.
                val err = "Cycle failed (${e.javaClass.simpleName})."
                val failedAt = System.currentTimeMillis()
                try {
                    automationDao.updateRun(
                        AutomationRunEntity(
                            id = runId,
                            startTime = runStartedAt,
                            endTime = failedAt,
                            propertiesFound = propFound,
                            propertiesAnalyzed = propAnalyzed,
                            dealsQualified = dealsQualified,
                            offersCreated = offersCreated,
                            offersSent = offersSent,
                            status = "ERROR",
                            summary = err
                        )
                    )
                } catch (_: Exception) {
                    // Preserve cycle failure handling even if the run summary cannot be persisted.
                }
                log("ERROR", "FAILURE", err)
                updatePersistentState(
                    isEnabled = isRunningFlag.get(),
                    op = err,
                    stage = "ERROR",
                    error = err
                )
                if (consecutiveFailures >= rules.consecutiveFailureThreshold) {
                    stopAutomation("Exceeded consecutive failure threshold (${rules.consecutiveFailureThreshold})")
                    break
                }
                delay(10000)
            }
        }
    }

    private suspend fun log(level: String, tag: String, message: String) {
        try {
            automationDao.insertLog(
                AutomationLogEntity(
                    timestamp = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = message
                )
            )
        } catch (e: Exception) {
            // Ignore db logging errors
        }
    }
}
