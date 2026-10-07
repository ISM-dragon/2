package com.example.domain.automation

import android.content.Context
import com.example.data.adapter.PropertySourceManager
import com.example.data.local.dao.AutomationDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.*
import com.example.data.repository.FinancialRepository
import com.example.data.repository.ImportOutcome
import com.example.data.repository.OfferRepository
import com.example.data.repository.PropertyImportRepository
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
    private val propertyImporter: PropertyImportRepository,
    private val financialRepository: FinancialRepository,
    private val offerRepository: OfferRepository,
    private val geminiManager: GeminiManager,
    private val networkMonitor: NetworkMonitor
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var automationJob: Job? = null
    private val isRunningFlag = AtomicBoolean(false)

    private val _status = MutableStateFlow(AutomationStatus.IDLE)
    val status: StateFlow<AutomationStatus> = _status.asStateFlow()

    private val _currentTaskDescription = MutableStateFlow("Engine Standby")
    val currentTaskDescription: StateFlow<String> = _currentTaskDescription.asStateFlow()

    private var consecutiveFailures = 0

    init {
        // Automatically check and recover any interrupted jobs from previous process death
        scope.launch {
            recoverInterruptedJobs()
        }
    }

    fun isRunning(): Boolean = isRunningFlag.get()

    fun startAutomation() {
        if (isRunningFlag.compareAndSet(false, true)) {
            automationJob?.cancel()
            automationJob = scope.launch {
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
            val activeJobs = automationDao.getActiveJobs()
            if (activeJobs.isNotEmpty()) {
                log("WARN", "WORKER_RECOVERY", "Found ${activeJobs.size} active jobs interrupted by prior process termination. Reconciling states.")
                for (job in activeJobs) {
                    when (job.currentState) {
                        JobState.SENDING.name -> {
                            val offer = job.offerId?.let { offerRepository.getOfferById(it) }
                            if (offer?.status == "SENT") {
                                automationDao.insertOrUpdateJob(
                                    job.copy(
                                        currentState = JobState.SENT.name,
                                        lastSuccessfulState = "SENT",
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            } else {
                                automationDao.insertOrUpdateJob(
                                    job.copy(
                                        currentState = JobState.OFFER_READY.name,
                                        lastSuccessfulState = "OFFER_READY",
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                        JobState.OFFER_GENERATION.name -> {
                            val existingOffer = offerRepository.getOffersByStatus("READY")
                            automationDao.insertOrUpdateJob(
                                job.copy(
                                    currentState = JobState.QUALIFIED.name,
                                    lastSuccessfulState = "QUALIFIED",
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        JobState.ANALYZING.name -> {
                            automationDao.insertOrUpdateJob(
                                job.copy(
                                    currentState = JobState.DISCOVERED.name,
                                    lastSuccessfulState = "DISCOVERED",
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("ERROR", "WORKER_RECOVERY", "Failed to reconcile interrupted jobs: ${e.message}")
        }
    }

    private suspend fun runAutomationLoop() {
        _status.value = AutomationStatus.RUNNING
        val rules = automationDao.getRules() ?: AutomationRuleEntity()

        while (isRunningFlag.get()) {
            val runId = automationDao.insertRun(
                AutomationRunEntity(
                    startTime = System.currentTimeMillis(),
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
                // Check offline state
                if (!networkMonitor.checkIsOnline()) {
                    _status.value = AutomationStatus.IDLE
                    _currentTaskDescription.value = "Paused (Offline - Waiting for connectivity)"
                    updatePersistentState(
                        isEnabled = true,
                        op = "Paused: Waiting for network",
                        stage = "OFFLINE_PAUSED"
                    )
                    delay(5000)
                    continue
                }

                // 1. DISCOVERY
                _status.value = AutomationStatus.SCANNING
                _currentTaskDescription.value = "Scanning property feeds (MLS & Distressed)..."
                updatePersistentState(isEnabled = true, op = "Scanning property feeds", stage = "DISCOVERY")

                val bundles = propertySourceManager.fetchAllSources(limitPerSource = rules.maxPropertiesPerCycle)
                propFound = bundles.size
                log("INFO", "PROPERTY_DISCOVERED", "Discovered $propFound target candidate properties across feeds")

                for (bundle in bundles) {
                    if (!isRunningFlag.get()) break

                    // Deduplicate and persist atomically: canonical row + provenance + satellites.
                    // `property` may be an existing canonical row that this record was merged into,
                    // so every later step (job row, underwriting, offers) keys off its id.
                    val importResult = propertyImporter.importBundle(bundle)
                    val property = importResult.property
                    if (importResult.outcome == ImportOutcome.SKIPPED) {
                        log("INFO", "PROPERTY_SKIPPED", "Skipped duplicate record: ${importResult.reason}")
                        continue
                    }

                    // Persistent Job initialization with state machine
                    val existingJob = automationDao.getJobByPropertyId(property.id)
                    val jobId = existingJob?.jobId ?: ("JOB-" + UUID.randomUUID().toString().take(8).uppercase())

                    var currentJob = existingJob ?: AutomationJobEntity(
                        jobId = jobId,
                        runId = runId,
                        propertyId = property.id,
                        propertyAddress = property.address,
                        currentState = JobState.DISCOVERED.name,
                        lastSuccessfulState = JobState.DISCOVERED.name,
                        attempts = 0,
                        maxRetries = rules.maxRetries,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    automationDao.insertOrUpdateJob(currentJob)

                    // Check run analysis limit
                    if (propAnalyzed >= rules.maxAnalysesPerRun) {
                        log("INFO", "LIMIT_REACHED", "Reached max analyses per run limit (${rules.maxAnalysesPerRun})")
                        break
                    }

                    // 2. ANALYZE
                    _status.value = AutomationStatus.ANALYZING
                    _currentTaskDescription.value = "Analyzing finances for ${property.address}..."
                    updatePersistentState(
                        isEnabled = true,
                        op = "Underwriting ${property.address}",
                        stage = "ANALYZING",
                        addr = property.address
                    )
                    log("INFO", "ANALYSIS_STARTED", "Starting deterministic underwriting for ${property.address}")

                    currentJob = currentJob.copy(
                        currentState = JobState.ANALYZING.name,
                        updatedAt = System.currentTimeMillis()
                    )
                    automationDao.insertOrUpdateJob(currentJob)

                    val finResult = financialRepository.analyzeProperty(property.id)
                    propAnalyzed++

                    currentJob = currentJob.copy(
                        currentState = JobState.ANALYZED.name,
                        lastSuccessfulState = JobState.ANALYZED.name,
                        analysisId = property.id,
                        updatedAt = System.currentTimeMillis()
                    )
                    automationDao.insertOrUpdateJob(currentJob)

                    log("SUCCESS", "ANALYSIS_COMPLETED", "Analyzed ${property.address}: NOI $${finResult.noiAnnual.toInt()}, Cash Flow $${finResult.monthlyCashFlow.toInt()}/mo, Cap ${String.format("%.1f", finResult.capRate)}%, DSCR ${String.format("%.2f", finResult.dscr)}")

                    // 3. QUALIFY
                    _status.value = AutomationStatus.QUALIFYING
                    currentJob = currentJob.copy(
                        currentState = JobState.QUALIFYING.name,
                        updatedAt = System.currentTimeMillis()
                    )
                    automationDao.insertOrUpdateJob(currentJob)

                    val qualEval = QualificationEngine.evaluate(property, finResult, rules)

                    if (qualEval.isQualified) {
                        dealsQualified++
                        propertyDao.setDealStatus(property.id, true, qualEval.score)
                        log("SUCCESS", "QUALIFICATION_RESULT", "DEAL QUALIFIED: ${property.address} (Score: ${qualEval.score}/100, Suggested: $${qualEval.suggestedOfferPrice.toInt()})")

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
                            addr = property.address,
                            successAction = "Qualified ${property.address}"
                        )

                        // 4. GENERATE OFFER (Idempotent: will not duplicate existing offer)
                        if (rules.autoGenerateOffers && offersCreated < rules.maxOffersPerRun) {
                            _status.value = AutomationStatus.GENERATING_OFFERS
                            _currentTaskDescription.value = "Drafting institutional purchase offer for ${property.address}..."
                            updatePersistentState(
                                isEnabled = true,
                                op = "Generating LOI for ${property.address}",
                                stage = "OFFER_GENERATION",
                                addr = property.address
                            )

                            currentJob = currentJob.copy(
                                currentState = JobState.OFFER_GENERATION.name,
                                updatedAt = System.currentTimeMillis()
                            )
                            automationDao.insertOrUpdateJob(currentJob)

                            val offer = offerRepository.generateOffer(
                                context = context,
                                propertyId = property.id,
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
                                addr = property.address,
                                successAction = "Generated Offer ${offer.id}"
                            )

                            // 5. AUTO SEND WITH FINAL PRE-SEND VALIDATION
                            if (rules.autoSendOffers && offersSent < rules.maxEmailsPerRun && networkMonitor.checkIsOnline()) {
                                _status.value = AutomationStatus.SENDING_OFFERS
                                _currentTaskDescription.value = "Validating & transmitting offer ${offer.id} via Gmail..."
                                
                                currentJob = currentJob.copy(
                                    currentState = JobState.VALIDATING_SEND.name,
                                    updatedAt = System.currentTimeMillis()
                                )
                                automationDao.insertOrUpdateJob(currentJob)

                                val preValidation = offerRepository.validateOfferPreSend(context, offer.id)
                                if (!preValidation.isValid) {
                                    val blockReason = preValidation.blockageReason ?: "Pre-send validation failed."
                                    log("WARN", "VALIDATION_BLOCKED", "Offer ${offer.id} delivery blocked: $blockReason")
                                    currentJob = currentJob.copy(
                                        currentState = JobState.BLOCKED.name,
                                        blockageReason = blockReason,
                                        lastError = blockReason,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    automationDao.insertOrUpdateJob(currentJob)
                                } else {
                                    currentJob = currentJob.copy(
                                        currentState = JobState.SENDING.name,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    automationDao.insertOrUpdateJob(currentJob)

                                    val sent = offerRepository.sendOffer(context, offer.id)
                                    if (sent) {
                                        offersSent++
                                        val updatedOffer = offerRepository.getOfferById(offer.id)
                                        log("SUCCESS", "GMAIL_SENT", "Sent offer ${offer.id} with PDF to ${offer.recipientEmail}")

                                        currentJob = currentJob.copy(
                                            currentState = JobState.SENT.name,
                                            lastSuccessfulState = JobState.SENT.name,
                                            emailMessageId = "GMAIL-${offer.id}",
                                            updatedAt = System.currentTimeMillis()
                                        )
                                        automationDao.insertOrUpdateJob(currentJob)

                                        updatePersistentState(
                                            isEnabled = true,
                                            op = "Offer Sent via Gmail",
                                            stage = "OFFER_SENT",
                                            addr = property.address,
                                            successAction = "Sent offer ${offer.id} to ${offer.recipientEmail}"
                                        )
                                    } else {
                                        val errorMsg = "Gmail delivery failed or rejected."
                                        log("WARN", "FAILURE", "Failed to auto-send offer ${offer.id}: $errorMsg")
                                        val attempts = currentJob.attempts + 1
                                        val nextState = if (attempts < rules.maxRetries) JobState.FAILED_RETRYABLE else JobState.FAILED_TERMINAL

                                        currentJob = currentJob.copy(
                                            currentState = nextState.name,
                                            failedStep = "SEND_OFFER",
                                            attempts = attempts,
                                            lastError = errorMsg,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                        automationDao.insertOrUpdateJob(currentJob)
                                    }
                                }
                            }
                        }
                    } else {
                        propertyDao.setDealStatus(property.id, false, qualEval.score)
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
                automationDao.updateRun(
                    AutomationRunEntity(
                        id = runId,
                        startTime = System.currentTimeMillis(),
                        endTime = System.currentTimeMillis(),
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
                log("INFO", "STOP", "Automation cycle cancelled.")
                break
            } catch (e: Exception) {
                consecutiveFailures++
                val err = e.message ?: "Cycle error"
                log("ERROR", "FAILURE", "Cycle error: $err")
                updatePersistentState(
                    isEnabled = isRunningFlag.get(),
                    op = "Error: $err",
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
