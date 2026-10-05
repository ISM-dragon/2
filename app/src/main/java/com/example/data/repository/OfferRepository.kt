package com.example.data.repository

import android.content.Context
import com.example.data.local.dao.ConfigDao
import com.example.data.local.dao.OfferDao
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.OfferDocumentEntity
import com.example.data.local.entity.OfferEntity
import com.example.domain.ai.GeminiManager
import com.example.domain.gmail.GmailService
import com.example.domain.pdf.OfferPdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class PreSendValidationResult(
    val isValid: Boolean,
    val blockageReason: String? = null
)

class OfferRepository(
    private val offerDao: OfferDao,
    private val propertyDao: PropertyDao,
    private val configRepository: ConfigRepository,
    private val geminiManager: GeminiManager,
    private val gmailService: GmailService
) {
    val allOffers: Flow<List<OfferEntity>> = offerDao.getAllOffers()
    val generatedCount: Flow<Int> = offerDao.getGeneratedOffersCountFlow()
    val sentCount: Flow<Int> = offerDao.getSentOffersCountFlow()
    val failedCount: Flow<Int> = offerDao.getFailedOffersCountFlow()

    fun getOffersByStatus(status: String): Flow<List<OfferEntity>> =
        offerDao.getOffersByStatus(status)

    fun getOfferByIdFlow(id: String): Flow<OfferEntity?> =
        offerDao.getOfferByIdFlow(id)

    suspend fun getOfferById(id: String): OfferEntity? =
        offerDao.getOfferById(id)

    fun getDocumentsForOffer(offerId: String): Flow<List<OfferDocumentEntity>> =
        offerDao.getDocumentsForOffer(offerId)

    suspend fun validateOfferPreSend(context: Context, offerId: String): PreSendValidationResult = withContext(Dispatchers.IO) {
        val offer = offerDao.getOfferById(offerId)
            ?: return@withContext PreSendValidationResult(false, "Offer does not exist in local database.")

        val property = propertyDao.getPropertyById(offer.propertyId)
            ?: return@withContext PreSendValidationResult(false, "Associated property does not exist.")

        if (offer.offerPrice <= 0.0) {
            return@withContext PreSendValidationResult(false, "Offer purchase price is non-positive ($${offer.offerPrice}).")
        }
        if (offer.earnestMoney <= 0.0) {
            return@withContext PreSendValidationResult(false, "Earnest money deposit must be greater than zero.")
        }
        if (offer.expirationDate.isBlank()) {
            return@withContext PreSendValidationResult(false, "Offer expiration date is missing.")
        }

        val email = offer.recipientEmail.trim()
        if (email.isBlank()) {
            return@withContext PreSendValidationResult(false, "Recipient email address is blank.")
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return@withContext PreSendValidationResult(false, "Recipient email '$email' is not a valid email address.")
        }

        val pdfFile = offer.pdfPath?.let { File(it) }
        if (pdfFile == null || !pdfFile.exists() || pdfFile.length() == 0L) {
            return@withContext PreSendValidationResult(false, "Offer PDF contract document is missing or corrupted on disk.")
        }

        val gmailConfig = configRepository.getGmailConfig()
        if (!gmailConfig.isConnected || gmailConfig.accessToken.isNullOrBlank()) {
            return@withContext PreSendValidationResult(
                false,
                "Gmail account is not connected with a valid authorized OAuth token. Authorization required in Settings."
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
        // Idempotency Protection: If an offer already exists for this property and is active/sent, return it
        val existing = offerDao.getOfferByPropertyId(propertyId)
        if (existing != null && existing.status != "DRAFT" && existing.status != "FAILED") {
            return@withContext existing
        }

        val property = propertyDao.getPropertyById(propertyId)
            ?: throw IllegalArgumentException("Property not found: $propertyId")

        val template = configRepository.getOfferTemplate() ?: com.example.data.local.entity.OfferTemplateEntity()
        val offerPrice = customPrice ?: (property.price * 0.92)
        val earnestMoney = offerPrice * (template.earnestMoneyPercent / 100.0)

        // Expiration 5 days out
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 5)
        val expFormat = SimpleDateFormat("MMM dd, yyyy 'at 5:00 PM CST'", Locale.US)
        val expirationDate = expFormat.format(cal.time)

        val offerId = "OFFER-" + UUID.randomUUID().toString().take(8).uppercase()

        // Generate Letter with Gemini AI if available, else high-quality fallback
        val prompt = "Write a formal 2-paragraph real estate Letter of Intent purchase offer statement from an institutional investor for property at ${property.address}, ${property.city}, ${property.state}. The offer price is $${String.format("%,.0f", offerPrice)}. Highlight clean terms, pre-funded earnest money of $${String.format("%,.0f", earnestMoney)}, a ${template.defaultInspectionDays}-day inspection contingency, and closing in ${template.defaultClosingDays} days. Keep it persuasive, professional, and clear."

        val aiResponse = geminiManager.generateContent(
            prompt = prompt,
            systemPrompt = "You are a senior real estate acquisitions director writing concise, legally polished purchase letters of intent."
        )

        val letterContent = if (aiResponse.success && aiResponse.text.isNotBlank()) {
            aiResponse.text
        } else {
            "Real Estate AI Investment Group is pleased to present this formal Letter of Intent to acquire the property located at ${property.address}, ${property.city}, ${property.state} for the total purchase consideration of $${String.format("%,.0f", offerPrice)}.\n\nOur offer reflects an expedited closing timeline of ${template.defaultClosingDays} days, supported by proof of funds and an initial earnest money deposit of $${String.format("%,.0f", earnestMoney)} to be deposited in escrow upon mutual execution. We respect the seller's schedule and maintain an expeditious ${template.defaultInspectionDays}-day inspection window."
        }

        val draftOffer = OfferEntity(
            id = offerId,
            propertyId = propertyId,
            recipientName = recipientName,
            recipientEmail = recipientEmail,
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
            createdAt = System.currentTimeMillis()
        )

        // Generate the PDF
        val pdfFile = OfferPdfGenerator.generateOfferPdf(context, draftOffer, property)

        val readyOffer = draftOffer.copy(
            pdfPath = pdfFile.absolutePath,
            status = "READY"
        )
        offerDao.insertOffer(readyOffer)

        // Store Document record
        offerDao.insertOfferDocument(
            OfferDocumentEntity(
                offerId = offerId,
                fileName = pdfFile.name,
                filePath = pdfFile.absolutePath,
                fileSizeBytes = pdfFile.length(),
                createdAt = System.currentTimeMillis()
            )
        )

        readyOffer
    }

    suspend fun updateOffer(offer: OfferEntity, context: Context) = withContext(Dispatchers.IO) {
        val property = propertyDao.getPropertyById(offer.propertyId)
        val pdfFile = if (property != null) {
            OfferPdfGenerator.generateOfferPdf(context, offer, property)
        } else null

        val updated = offer.copy(pdfPath = pdfFile?.absolutePath ?: offer.pdfPath)
        offerDao.updateOffer(updated)
    }

    suspend fun sendOffer(
        context: Context,
        offerId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val offer = offerDao.getOfferById(offerId) ?: return@withContext false
        val property = propertyDao.getPropertyById(offer.propertyId) ?: return@withContext false

        // Idempotency: If already sent, avoid duplicate email transmission
        if (offer.status == "SENT" || offer.status == "OPENED" || offer.status == "SIGNED") {
            return@withContext true
        }

        // Final Pre-Send Validation
        val validation = validateOfferPreSend(context, offerId)
        if (!validation.isValid) {
            offerDao.updateOfferStatus(
                id = offerId,
                status = "FAILED",
                sentAt = null,
                error = "BLOCKED: ${validation.blockageReason}"
            )
            return@withContext false
        }

        // Lock state to prevent race conditions during transmission
        offerDao.updateOfferStatus(
            id = offerId,
            status = "SENDING",
            sentAt = null,
            error = null
        )

        val pdfFile = offer.pdfPath?.let { File(it) }

        val subject = "Purchase Offer & Letter of Intent - ${property.address}, ${property.city}"
        val htmlBody = """
            <div style="font-family: Arial, sans-serif; color: #1e293b;">
                <h2>Purchase Offer & Letter of Intent</h2>
                <p>Dear ${offer.recipientName},</p>
                <p>${offer.generatedLetterContent.replace("\n", "<br>")}</p>
                <hr/>
                <p><strong>Offer Price:</strong> $${String.format("%,.0f", offer.offerPrice)}</p>
                <p><strong>Earnest Money:</strong> $${String.format("%,.0f", offer.earnestMoney)}</p>
                <p><strong>Inspection:</strong> ${offer.inspectionPeriodDays} Days</p>
                <p><strong>Closing:</strong> ${offer.closingPeriodDays} Days</p>
                <p><em>Please find the formal executed PDF agreement attached.</em></p>
            </div>
        """.trimIndent()

        val sendResult = gmailService.sendOfferEmail(
            context = context,
            recipientEmail = offer.recipientEmail,
            recipientName = offer.recipientName,
            subject = subject,
            htmlBody = htmlBody,
            pdfFile = pdfFile
        )

        if (sendResult.success && !sendResult.messageId.isNullOrBlank()) {
            offerDao.updateOfferStatus(
                id = offerId,
                status = "SENT",
                sentAt = System.currentTimeMillis(),
                error = null
            )
            true
        } else {
            val failureError = sendResult.error ?: "Gmail API transmission failed without message ID confirmation."
            offerDao.updateOfferStatus(
                id = offerId,
                status = "FAILED",
                sentAt = null,
                error = failureError
            )
            false
        }
    }

    suspend fun updateStatus(offerId: String, status: String) {
        offerDao.updateOfferStatus(offerId, status, System.currentTimeMillis(), null)
    }

    suspend fun deleteOffer(offerId: String) {
        offerDao.deleteOffer(offerId)
    }
}
