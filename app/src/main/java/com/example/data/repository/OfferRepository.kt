package com.example.data.repository

import android.content.Context
import com.example.data.local.dao.OfferDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferAuditEventEntity
import com.example.data.local.entity.OfferEmailFailureKind
import com.example.data.local.entity.OfferEmailSendEntity
import com.example.data.local.entity.OfferEmailSendStatus
import com.example.data.local.entity.OfferDocumentEntity
import com.example.data.local.entity.OfferEntity
import com.example.domain.ai.GeminiManager
import com.example.domain.gmail.EmailRetryPolicy
import com.example.domain.gmail.GmailFailureKind
import com.example.domain.gmail.GmailMimeBuilder
import com.example.domain.gmail.GmailSendResult
import com.example.domain.gmail.GmailSender
import com.example.domain.pdf.OfferPdfGenerator
import com.example.domain.propertyurl.util.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class PreSendValidationResult(
    val isValid: Boolean,
    val blockageReason: String? = null
)

data class OfferSendOutcome(
    val success: Boolean,
    val idempotencyKey: String,
    val status: String,
    val messageId: String? = null,
    val error: String? = null,
    val nextAttemptAt: Long? = null,
    val attempted: Boolean = false
) {
    val safeToRetry: Boolean
        get() = status == OfferEmailSendStatus.RETRYABLE || status == OfferEmailSendStatus.BLOCKED
}

class OfferRepository(
    private val offerDao: OfferDao,
    private val propertyDao: PropertyDao,
    private val configRepository: ConfigRepository,
    private val geminiManager: GeminiManager,
    private val gmailService: GmailSender,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val offerGenerationLocks = ConcurrentHashMap<String, Mutex>()

    val allOffers: Flow<List<OfferEntity>> = offerDao.getAllOffers()
    val generatedCount: Flow<Int> = offerDao.getGeneratedOffersCountFlow()
    val sentCount: Flow<Int> = offerDao.getSentOffersCountFlow()
    val failedCount: Flow<Int> = offerDao.getFailedOffersCountFlow()

    fun getOffersByStatus(status: String): Flow<List<OfferEntity>> = offerDao.getOffersByStatus(status)

    fun getOfferByIdFlow(id: String): Flow<OfferEntity?> = offerDao.getOfferByIdFlow(id)

    suspend fun getOfferById(id: String): OfferEntity? = offerDao.getOfferById(id)

    suspend fun getOfferForProperty(propertyId: String): OfferEntity? = offerDao.getOfferByPropertyId(propertyId)

    fun getDocumentsForOffer(offerId: String): Flow<List<OfferDocumentEntity>> =
        offerDao.getDocumentsForOffer(offerId)

    fun getAuditTrailForOffer(offerId: String): Flow<List<OfferAuditEventEntity>> =
        offerDao.getAuditTrailForOffer(offerId)

    suspend fun getEmailSendForOffer(offerId: String): OfferEmailSendEntity? =
        offerDao.getEmailSendForOffer(offerId)

    suspend fun validateOfferPreSend(context: Context, offerId: String): PreSendValidationResult =
        withContext(Dispatchers.IO) {
            val offer = offerDao.getOfferById(offerId)
                ?: return@withContext PreSendValidationResult(false, "Offer does not exist in local database.")

            val lifecycleStatus = offer.status.uppercase(Locale.US)
            if (lifecycleStatus !in setOf("READY", "FAILED")) {
                val reason = when (lifecycleStatus) {
                    "DECLINED" -> "Declined offers cannot be sent."
                    "EXPIRED" -> "Expired offers cannot be sent."
                    "SENT", "OPENED", "SIGNED" -> "Offer is already recorded as delivered."
                    else -> "Offer status '$lifecycleStatus' is not eligible for email delivery."
                }
                return@withContext PreSendValidationResult(false, reason)
            }

            val property = propertyDao.getPropertyById(offer.propertyId)
                ?: return@withContext PreSendValidationResult(false, "Associated property does not exist.")

            if (!offer.offerPrice.isFinite() || offer.offerPrice <= 0.0) {
                return@withContext PreSendValidationResult(false, "Offer purchase price must be positive.")
            }
            if (!offer.earnestMoney.isFinite() || offer.earnestMoney <= 0.0) {
                return@withContext PreSendValidationResult(false, "Earnest money deposit must be greater than zero.")
            }
            if (offer.expirationDate.isBlank()) {
                return@withContext PreSendValidationResult(false, "Offer expiration date is missing.")
            }
            if (property.address.isBlank()) {
                return@withContext PreSendValidationResult(false, "Associated property address is missing.")
            }

            val email = offer.recipientEmail.trim()
            if (!GmailMimeBuilder.isSafeEmail(email)) {
                return@withContext PreSendValidationResult(false, "Recipient email address is invalid.")
            }
            if (GmailMimeBuilder.containsHeaderControls(offer.recipientName)) {
                return@withContext PreSendValidationResult(false, "Recipient name contains invalid header characters.")
            }

            val pdfFile = offer.pdfPath?.let(::File)
            if (pdfFile == null || !GmailMimeBuilder.isApprovedOfferPdf(context, pdfFile)) {
                return@withContext PreSendValidationResult(
                    false,
                    "Offer PDF is missing, invalid, or outside the private offers directory."
                )
            }
            if (pdfFile.length() > GmailMimeBuilder.MAX_ATTACHMENT_BYTES) {
                return@withContext PreSendValidationResult(false, "Offer PDF exceeds the supported 20 MB attachment limit.")
            }

            val gmailConfig = configRepository.getGmailConfig()
            if (!gmailConfig.isConnected || gmailConfig.accessToken.isNullOrBlank()) {
                return@withContext PreSendValidationResult(
                    false,
                    "Gmail account is not connected with an authorized OAuth token. Authorization required in Settings."
                )
            }
            PreSendValidationResult(true, null)
        }

    suspend fun generateOffer(
        context: Context,
        propertyId: String,
        customPrice: Double? = null,
        recipientName: String = "Listing Agent / Seller",
        recipientEmail: String = "agent@realestateteam.com"
    ): OfferEntity = withContext(Dispatchers.IO) {
        val generationLock = offerGenerationLocks.getOrPut(propertyId) { Mutex() }
        generationLock.withLock {
            generateOfferLocked(context, propertyId, customPrice, recipientName, recipientEmail)
        }
    }

    private suspend fun generateOfferLocked(
        context: Context,
        propertyId: String,
        customPrice: Double?,
        recipientName: String,
        recipientEmail: String
    ): OfferEntity {
        // An offer in FAILED state is not a license to create a fresh offer/email. Reuse the same
        // offer and its send ledger so a recurring property scan cannot bypass idempotency.
        offerDao.getOfferByPropertyId(propertyId)?.let { return it }

        val property = propertyDao.getPropertyById(propertyId)
            ?: throw IllegalArgumentException("Property not found: $propertyId")
        val template = configRepository.getOfferTemplate()
            ?: com.example.data.local.entity.OfferTemplateEntity()
        val offerPrice = customPrice ?: (property.price * 0.92)
        require(offerPrice.isFinite() && offerPrice > 0.0) { "Offer price must be positive and finite." }
        val earnestMoney = offerPrice * (template.earnestMoneyPercent / 100.0)

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 5)
        val expirationDate = SimpleDateFormat("MMM dd, yyyy 'at 5:00 PM CST'", Locale.US).format(calendar.time)
        val offerId = "OFFER-" + java.util.UUID.randomUUID().toString().uppercase(Locale.US)

        val prompt = "Write a formal 2-paragraph real estate Letter of Intent purchase offer statement from an institutional investor for property at ${property.address}, ${property.city}, ${property.state}. The offer price is $${String.format(Locale.US, "%,.0f", offerPrice)}. Highlight clean terms, pre-funded earnest money of $${String.format(Locale.US, "%,.0f", earnestMoney)}, a ${template.defaultInspectionDays}-day inspection contingency, and closing in ${template.defaultClosingDays} days. Keep it persuasive, professional, and clear."
        val aiResponse = geminiManager.generateContent(
            prompt = prompt,
            systemPrompt = "You are a senior real estate acquisitions director writing concise, legally polished purchase letters of intent."
        )
        val letterContent = if (aiResponse.success && aiResponse.text.isNotBlank()) {
            aiResponse.text
        } else {
            "Real Estate AI Investment Group is pleased to present this formal Letter of Intent to acquire the property located at ${property.address}, ${property.city}, ${property.state} for the total purchase consideration of $${String.format(Locale.US, "%,.0f", offerPrice)}.\n\nOur offer reflects an expedited closing timeline of ${template.defaultClosingDays} days, supported by proof of funds and an initial earnest money deposit of $${String.format(Locale.US, "%,.0f", earnestMoney)} to be deposited in escrow upon mutual execution. We respect the seller's schedule and maintain an expeditious ${template.defaultInspectionDays}-day inspection window."
        }

        val draftOffer = OfferEntity(
            id = offerId,
            propertyId = propertyId,
            recipientName = recipientName,
            recipientEmail = recipientEmail.trim(),
            offerPrice = offerPrice,
            earnestMoney = earnestMoney,
            inspectionPeriodDays = template.defaultInspectionDays,
            closingPeriodDays = template.defaultClosingDays,
            contingencies = "Clear title, satisfactory home inspection, prorated property taxes and rent roll verification.",
            terms = template.standardTerms,
            conditions = template.standardConditions,
            expirationDate = expirationDate,
            generatedLetterContent = letterContent,
            status = "GENERATED",
            createdAt = clock()
        )

        val pdfFile = OfferPdfGenerator.generateOfferPdf(context, draftOffer, property)
        val readyOffer = draftOffer.copy(pdfPath = pdfFile.absolutePath, status = "READY")
        val generatedAt = clock()
        offerDao.insertOfferWithDocumentAndAudit(
            offer = readyOffer,
            document = OfferDocumentEntity(
                offerId = offerId,
                fileName = pdfFile.name,
                filePath = pdfFile.absolutePath,
                fileSizeBytes = pdfFile.length(),
                createdAt = generatedAt
            ),
            event = OfferAuditEventEntity(
                offerId = offerId,
                eventType = "OFFER_GENERATED",
                timestamp = generatedAt,
                status = "READY",
                details = "Offer and PDF generated."
            )
        )
        return readyOffer
    }

    suspend fun updateOffer(offer: OfferEntity, context: Context) = withContext(Dispatchers.IO) {
        val property = propertyDao.getPropertyById(offer.propertyId)
        val pdfFile = if (property != null) OfferPdfGenerator.generateOfferPdf(context, offer, property) else null
        offerDao.updateOffer(offer.copy(pdfPath = pdfFile?.absolutePath ?: offer.pdfPath))
    }

    /** Backwards-compatible Boolean API used by the Offers screen. */
    suspend fun sendOffer(context: Context, offerId: String): Boolean =
        sendOfferDetailed(context, offerId).success

    /** Sends at most once per offer, persists every transition, and retries only known rejections. */
    suspend fun sendOfferDetailed(
        context: Context,
        offerId: String,
        maxAttempts: Int = EmailRetryPolicy.DEFAULT_MAX_ATTEMPTS
    ): OfferSendOutcome = withContext(Dispatchers.IO) {
        val offer = offerDao.getOfferById(offerId)
            ?: return@withContext OfferSendOutcome(false, idempotencyKey(offerId), OfferEmailSendStatus.FAILED, error = "Offer not found.")
        val key = idempotencyKey(offer.id)
        val now = clock()

        var send = offerDao.getEmailSendForOffer(offer.id)
        if (send == null) {
            val legacyStatus = when {
                isSentStatus(offer.status) -> OfferEmailSendStatus.SENT
                offer.status.equals("SENDING", ignoreCase = true) -> OfferEmailSendStatus.UNKNOWN
                offer.status.equals("FAILED", ignoreCase = true) &&
                    !offer.lastError.orEmpty().startsWith("BLOCKED:", ignoreCase = true) -> OfferEmailSendStatus.UNKNOWN
                offer.status.equals("FAILED", ignoreCase = true) -> OfferEmailSendStatus.BLOCKED
                else -> OfferEmailSendStatus.PENDING
            }
            val legacyMessage = "Existing offer status was reconciled without a send record."
            val initial = OfferEmailSendEntity(
                idempotencyKey = key,
                offerId = offer.id,
                status = legacyStatus,
                attemptCount = if (legacyStatus == OfferEmailSendStatus.SENT || legacyStatus == OfferEmailSendStatus.UNKNOWN) 1 else 0,
                createdAt = offer.createdAt,
                updatedAt = now,
                sentAt = offer.sentAt,
                lastError = if (legacyStatus == OfferEmailSendStatus.UNKNOWN) {
                    "Previous send outcome cannot be proven; automatic retry is disabled to prevent duplicates."
                } else null,
                lastFailureKind = if (legacyStatus == OfferEmailSendStatus.UNKNOWN) {
                    OfferEmailFailureKind.DELIVERY_UNKNOWN
                } else null
            )
            send = offerDao.ensureEmailSendRecord(
                initial,
                auditEvent(
                    offerId = offer.id,
                    key = key,
                    eventType = if (legacyStatus == OfferEmailSendStatus.SENT) "LEGACY_SENT_RECONCILED" else "SEND_RECORD_CREATED",
                    status = legacyStatus,
                    timestamp = now,
                    details = if (legacyStatus == OfferEmailSendStatus.UNKNOWN) legacyMessage else null
                )
            )
        }

        if (isSentStatus(offer.status) && send.status != OfferEmailSendStatus.SENT) {
            val reconciled = send.copy(
                status = OfferEmailSendStatus.SENT,
                updatedAt = now,
                sentAt = send.sentAt ?: offer.sentAt ?: now,
                lastError = null,
                nextAttemptAt = null
            )
            offerDao.updateEmailSendAndAudit(
                send = reconciled,
                offerStatus = offer.status,
                event = auditEvent(offer.id, key, "SENT_STATUS_RECONCILED", OfferEmailSendStatus.SENT, now)
            )
            return@withContext outcome(reconciled, success = true)
        }

        when (send.status) {
            OfferEmailSendStatus.SENT -> {
                if (!isSentStatus(offer.status)) {
                    val reconciledAt = clock()
                    offerDao.updateOfferStatusAndAudit(
                        offerId = offer.id,
                        offerStatus = "SENT",
                        sentAt = send.sentAt,
                        error = null,
                        event = auditEvent(
                            offer.id,
                            key,
                            "SENT_STATUS_RECONCILED",
                            OfferEmailSendStatus.SENT,
                            reconciledAt
                        )
                    )
                }
                return@withContext outcome(send, success = true)
            }
            OfferEmailSendStatus.UNKNOWN -> return@withContext outcome(
                send,
                error = send.lastError ?: "Delivery outcome is unknown; automatic retry is disabled to prevent duplicates."
            )
            OfferEmailSendStatus.FAILED -> return@withContext outcome(
                send,
                error = send.lastError ?: "Email delivery failed permanently."
            )
            OfferEmailSendStatus.IN_FLIGHT -> {
                if (send.startedAt != null && now - send.startedAt >= IN_FLIGHT_STALE_AFTER_MILLIS) {
                    val reconciled = markUnknown(send, "SEND_OUTCOME_RECOVERED_UNKNOWN", now)
                    return@withContext outcome(reconciled)
                }
                return@withContext outcome(send, error = "Email send is already in progress.")
            }
            OfferEmailSendStatus.RETRYABLE -> {
                if (send.nextAttemptAt != null && send.nextAttemptAt > now) {
                    return@withContext outcome(send, error = "Retry is scheduled; automatic retry window has not elapsed.")
                }
            }
        }

        val validation = validateOfferPreSend(context, offerId)
        if (!validation.isValid) {
            val error = validation.blockageReason ?: "Pre-send validation failed."
            val blocked = send.copy(
                status = OfferEmailSendStatus.BLOCKED,
                updatedAt = now,
                nextAttemptAt = null,
                lastError = error,
                lastFailureKind = OfferEmailFailureKind.VALIDATION
            )
            offerDao.updateEmailSendAndAudit(
                blocked,
                offerStatus = if (offer.status.uppercase(Locale.US) in setOf("READY", "FAILED")) {
                    "FAILED"
                } else {
                    offer.status
                },
                event = auditEvent(offer.id, key, "SEND_BLOCKED", blocked.status, now, "failure=VALIDATION")
            )
            return@withContext outcome(blocked)
        }

        val claimed = offerDao.claimEmailSendAndAudit(key, now)
        if (claimed == null) {
            val latest = offerDao.getEmailSend(key) ?: send
            return@withContext outcome(latest, success = latest.status == OfferEmailSendStatus.SENT)
        }

        val property = propertyDao.getPropertyById(offer.propertyId)
        if (property == null) {
            val failed = claimed.copy(
                status = OfferEmailSendStatus.BLOCKED,
                updatedAt = clock(),
                lastError = "Associated property does not exist.",
                lastFailureKind = OfferEmailFailureKind.VALIDATION
            )
            offerDao.updateEmailSendAndAudit(
                failed,
                "FAILED",
                auditEvent(offer.id, key, "SEND_BLOCKED", failed.status, clock(), "failure=PROPERTY_MISSING")
            )
            return@withContext outcome(failed)
        }

        val pdfFile = offer.pdfPath?.let(::File)
        val subject = "Purchase Offer & Letter of Intent - ${property.address}, ${property.city}"
        val htmlBody = buildOfferEmailBody(offer)
        val sendResult = try {
            gmailService.sendOfferEmail(
                context = context,
                recipientEmail = offer.recipientEmail,
                recipientName = offer.recipientName,
                subject = subject,
                htmlBody = htmlBody,
                pdfFile = pdfFile,
                idempotencyKey = key
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The call may have reached Gmail before the exception; keep this outcome conservative.
            GmailSendResult(
                success = false,
                error = "Email send did not return a definitive result; automatic retry is disabled to prevent duplicates.",
                failureKind = GmailFailureKind.DELIVERY_UNKNOWN
            )
        }

        val confirmedMessageId = sendResult.messageId?.takeIf(::isSafeMessageId)
        if (sendResult.success && confirmedMessageId != null) {
            val sent = claimed.copy(
                status = OfferEmailSendStatus.SENT,
                updatedAt = clock(),
                sentAt = clock(),
                messageId = confirmedMessageId,
                nextAttemptAt = null,
                lastError = null,
                lastFailureKind = null
            )
            offerDao.updateEmailSendAndAudit(
                sent,
                offerStatus = "SENT",
                event = auditEvent(
                    offer.id,
                    key,
                    "EMAIL_SENT",
                    sent.status,
                    sent.sentAt ?: clock(),
                    "Delivery acknowledged by Gmail."
                )
            )
            return@withContext outcome(sent, success = true, attempted = true)
        }

        val failureKind = sendResult.failureKind ?: GmailFailureKind.DELIVERY_UNKNOWN
        val safeError = if (failureKind == GmailFailureKind.DELIVERY_UNKNOWN) {
            "Previous send outcome cannot be proven; automatic retry is disabled to prevent duplicates."
        } else {
            redactSensitiveData(sendResult.error ?: "Gmail send failed without a definitive delivery result.")
        }
        val attemptTime = clock()
        val nextAttemptAt = EmailRetryPolicy.nextAttemptAt(
            failureKind = failureKind,
            attemptCount = claimed.attemptCount,
            now = attemptTime,
            serverRetryAfterMillis = sendResult.retryAfterMillis,
            maxAttempts = maxAttempts
        )
        val status = when {
            nextAttemptAt != null -> OfferEmailSendStatus.RETRYABLE
            failureKind == GmailFailureKind.AUTHENTICATION || failureKind == GmailFailureKind.VALIDATION ->
                OfferEmailSendStatus.BLOCKED
            failureKind == GmailFailureKind.DELIVERY_UNKNOWN -> OfferEmailSendStatus.UNKNOWN
            else -> OfferEmailSendStatus.FAILED
        }
        val failed = claimed.copy(
            status = status,
            updatedAt = attemptTime,
            nextAttemptAt = nextAttemptAt,
            sentAt = null,
            messageId = null,
            lastError = safeError,
            lastFailureKind = EmailRetryPolicy.storageFailureKind(failureKind)
        )
        val eventType = when (status) {
            OfferEmailSendStatus.RETRYABLE -> "SEND_RETRY_SCHEDULED"
            OfferEmailSendStatus.BLOCKED -> "SEND_BLOCKED"
            OfferEmailSendStatus.UNKNOWN -> "SEND_OUTCOME_UNKNOWN"
            else -> "SEND_FAILED"
        }
        offerDao.updateEmailSendAndAudit(
            failed,
            offerStatus = "FAILED",
            event = auditEvent(
                offer.id,
                key,
                eventType,
                status,
                attemptTime,
                "failure=${EmailRetryPolicy.storageFailureKind(failureKind)}${nextAttemptAt?.let { "; nextAttemptAt=$it" }.orEmpty()}"
            )
        )
        outcome(failed, error = safeError, attempted = true)
    }

    /** Converts stale in-flight sends to UNKNOWN. They must never be retried automatically. */
    suspend fun recoverInterruptedSends(now: Long = clock()) = withContext(Dispatchers.IO) {
        val staleBefore = now - IN_FLIGHT_STALE_AFTER_MILLIS
        offerDao.getInFlightEmailSends()
            .filter { it.startedAt == null || it.startedAt <= staleBefore }
            .forEach { send ->
                markUnknown(send, "SEND_OUTCOME_RECOVERED_UNKNOWN", now)
            }
    }

    private suspend fun markUnknown(
        send: OfferEmailSendEntity,
        eventType: String,
        timestamp: Long
    ): OfferEmailSendEntity {
        val unknown = send.copy(
            status = OfferEmailSendStatus.UNKNOWN,
            updatedAt = timestamp,
            nextAttemptAt = null,
            lastError = "Previous send outcome cannot be proven; automatic retry is disabled to prevent duplicates.",
            lastFailureKind = OfferEmailFailureKind.DELIVERY_UNKNOWN
        )
        offerDao.updateEmailSendAndAudit(
            unknown,
            offerStatus = "FAILED",
            event = auditEvent(send.offerId, send.idempotencyKey, eventType, unknown.status, timestamp)
        )
        return unknown
    }

    private fun outcome(
        send: OfferEmailSendEntity,
        success: Boolean = false,
        error: String? = send.lastError,
        attempted: Boolean = false
    ) = OfferSendOutcome(
        success = success,
        idempotencyKey = send.idempotencyKey,
        status = send.status,
        messageId = send.messageId,
        error = error,
        nextAttemptAt = send.nextAttemptAt,
        attempted = attempted
    )

    private fun auditEvent(
        offerId: String,
        key: String,
        eventType: String,
        status: String,
        timestamp: Long,
        details: String? = null
    ) = OfferAuditEventEntity(
        offerId = offerId,
        idempotencyKey = key,
        eventType = eventType,
        timestamp = timestamp,
        status = status,
        details = details
    )

    private fun buildOfferEmailBody(offer: OfferEntity): String {
        fun text(value: String) = GmailMimeBuilder.escapeHtml(value).replace("\r\n", "\n").replace("\n", "<br>")
        return """
            <div style="font-family: Arial, sans-serif; color: #1e293b;">
                <h2>Purchase Offer &amp; Letter of Intent</h2>
                <p>Dear ${text(offer.recipientName)},</p>
                <p>${text(offer.generatedLetterContent)}</p>
                <hr/>
                <p><strong>Offer Price:</strong> $${String.format(Locale.US, "%,.0f", offer.offerPrice)}</p>
                <p><strong>Earnest Money:</strong> $${String.format(Locale.US, "%,.0f", offer.earnestMoney)}</p>
                <p><strong>Inspection:</strong> ${offer.inspectionPeriodDays} Days</p>
                <p><strong>Closing:</strong> ${offer.closingPeriodDays} Days</p>
                <p><em>Please find the formal offer PDF attached.</em></p>
            </div>
        """.trimIndent()
    }

    private suspend fun redactSensitiveData(message: String): String {
        var sanitized = message.take(MAX_ERROR_LENGTH)
        try {
            val config: GmailConfigurationEntity = configRepository.getGmailConfig()
            config.accessToken?.takeIf { it.isNotBlank() }?.let { sanitized = sanitized.replace(it, "[REDACTED]") }
            config.refreshToken?.takeIf { it.isNotBlank() }?.let { sanitized = sanitized.replace(it, "[REDACTED]") }
        } catch (_: Exception) {
            // Keep the generic pattern redaction below even if the config store is unavailable.
        }
        return Redaction.message(OAUTH_TOKEN_PATTERN.replace(sanitized, "[REDACTED]"), MAX_ERROR_LENGTH)
    }

    private fun isSafeMessageId(value: String): Boolean =
        value.isNotBlank() &&
            !value.equals("null", ignoreCase = true) &&
            value.length <= 512 &&
            value.matches(Regex("[A-Za-z0-9._@+-]+"))

    private fun isSentStatus(status: String): Boolean =
        status.equals("SENT", ignoreCase = true) ||
            status.equals("OPENED", ignoreCase = true) ||
            status.equals("SIGNED", ignoreCase = true)

    private fun idempotencyKey(offerId: String): String = "offer-send-v1:$offerId"

    suspend fun updateStatus(offerId: String, status: String): Boolean {
        val timestamp = clock()
        return offerDao.updateOfferStatusAndAudit(
            offerId = offerId,
            offerStatus = status,
            sentAt = timestamp.takeIf { status.equals("SENT", ignoreCase = true) },
            error = null,
            event = OfferAuditEventEntity(
                offerId = offerId,
                eventType = "OFFER_STATUS_UPDATED",
                timestamp = timestamp,
                status = status,
                details = "Offer status updated by operator."
            )
        )
    }

    suspend fun deleteOffer(offerId: String): Boolean {
        val timestamp = clock()
        return offerDao.deleteOfferWithAudit(
            id = offerId,
            event = OfferAuditEventEntity(
                offerId = offerId,
                eventType = "OFFER_DELETED",
                timestamp = timestamp,
                status = "DELETED",
                details = "Offer removed from the active offer list."
            )
        )
    }

    companion object {
        const val IN_FLIGHT_STALE_AFTER_MILLIS = 2 * 60 * 1000L
        private const val MAX_ERROR_LENGTH = 500
        private val OAUTH_TOKEN_PATTERN = Regex("(?:ya29\\.[A-Za-z0-9._-]+|1//[A-Za-z0-9._-]+)")
    }
}
