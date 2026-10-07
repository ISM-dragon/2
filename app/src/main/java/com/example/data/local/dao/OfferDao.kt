package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.OfferAuditEventEntity
import com.example.data.local.entity.OfferDocumentEntity
import com.example.data.local.entity.OfferEmailFailureKind
import com.example.data.local.entity.OfferEmailSendEntity
import com.example.data.local.entity.OfferEmailSendStatus
import com.example.data.local.entity.OfferEntity
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@Dao
interface OfferDao {
    @Query("SELECT * FROM offers ORDER BY createdAt DESC")
    fun getAllOffers(): Flow<List<OfferEntity>>

    @Query("SELECT * FROM offers WHERE status = :status ORDER BY createdAt DESC")
    fun getOffersByStatus(status: String): Flow<List<OfferEntity>>

    @Query("SELECT * FROM offers WHERE id = :id LIMIT 1")
    fun getOfferByIdFlow(id: String): Flow<OfferEntity?>

    @Query("SELECT * FROM offers WHERE id = :id LIMIT 1")
    suspend fun getOfferById(id: String): OfferEntity?

    @Query("SELECT * FROM offers WHERE propertyId = :propertyId ORDER BY createdAt DESC")
    fun getOffersForProperty(propertyId: String): Flow<List<OfferEntity>>

    @Query("SELECT COUNT(*) FROM offers")
    fun getGeneratedOffersCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM offers WHERE status IN ('SENT', 'OPENED', 'SIGNED')")
    fun getSentOffersCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM offers")
    suspend fun getGeneratedOffersCount(): Int

    @Query("SELECT COUNT(*) FROM offers WHERE status IN ('SENT', 'OPENED', 'SIGNED')")
    suspend fun getSentOffersCount(): Int

    @Query("SELECT COUNT(*) FROM offers WHERE status = 'FAILED'")
    fun getFailedOffersCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM offers WHERE status = 'FAILED'")
    suspend fun getFailedOffersCount(): Int

    @Query("SELECT * FROM offers WHERE propertyId = :propertyId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getOfferByPropertyId(propertyId: String): OfferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffer(offer: OfferEntity)

    @Transaction
    suspend fun insertOfferWithDocumentAndAudit(
        offer: OfferEntity,
        document: OfferDocumentEntity,
        event: OfferAuditEventEntity
    ) {
        insertOffer(offer)
        insertOfferDocument(document)
        insertAuditEvent(event)
    }

    @Update
    suspend fun updateOffer(offer: OfferEntity)

    @Query("UPDATE offers SET status = :status, sentAt = :sentAt, lastError = :error WHERE id = :id")
    suspend fun updateOfferStatus(id: String, status: String, sentAt: Long?, error: String?)

    // Durable send ledger and append-only audit trail.
    @Query("SELECT * FROM offer_email_sends WHERE idempotencyKey = :key LIMIT 1")
    suspend fun getEmailSend(key: String): OfferEmailSendEntity?

    @Query("SELECT * FROM offer_email_sends WHERE offerId = :offerId LIMIT 1")
    suspend fun getEmailSendForOffer(offerId: String): OfferEmailSendEntity?

    @Query("SELECT * FROM offer_email_sends WHERE status = 'IN_FLIGHT'")
    suspend fun getInFlightEmailSends(): List<OfferEmailSendEntity>

    @Query("SELECT * FROM offer_email_sends ORDER BY createdAt DESC")
    suspend fun getAllEmailSendsList(): List<OfferEmailSendEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEmailSendIfAbsent(send: OfferEmailSendEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEmailSends(sends: List<OfferEmailSendEntity>)

    @Transaction
    suspend fun ensureEmailSendRecord(
        send: OfferEmailSendEntity,
        registrationEvent: OfferAuditEventEntity
    ): OfferEmailSendEntity {
        if (insertEmailSendIfAbsent(send) != -1L) {
            insertAuditEvent(registrationEvent)
        }
        return getEmailSend(send.idempotencyKey) ?: send
    }

    @Update
    suspend fun updateEmailSend(send: OfferEmailSendEntity)

    @Query("""
        UPDATE offer_email_sends
        SET status = 'IN_FLIGHT',
            attemptCount = attemptCount + 1,
            updatedAt = :now,
            startedAt = :now,
            nextAttemptAt = NULL,
            lastError = NULL,
            lastFailureKind = NULL
        WHERE idempotencyKey = :key
          AND status IN ('PENDING', 'RETRYABLE', 'BLOCKED')
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
    """)
    suspend fun claimEmailSend(key: String, now: Long): Int

    @Transaction
    suspend fun claimEmailSendAndAudit(key: String, now: Long): OfferEmailSendEntity? {
        val currentSend = getEmailSend(key) ?: return null
        if (
            currentSend.status !in setOf(
                OfferEmailSendStatus.PENDING,
                OfferEmailSendStatus.RETRYABLE,
                OfferEmailSendStatus.BLOCKED
            ) || currentSend.nextAttemptAt?.let { it > now } == true
        ) return null
        val currentOffer = getOfferById(currentSend.offerId)
        if (currentOffer == null) {
            updateEmailSend(
                currentSend.copy(
                    status = OfferEmailSendStatus.BLOCKED,
                    updatedAt = now,
                    nextAttemptAt = null,
                    lastError = "Offer no longer exists; email was not sent.",
                    lastFailureKind = OfferEmailFailureKind.VALIDATION
                )
            )
            insertAuditEvent(
                OfferAuditEventEntity(
                    offerId = currentSend.offerId,
                    idempotencyKey = key,
                    eventType = "SEND_BLOCKED",
                    timestamp = now,
                    status = OfferEmailSendStatus.BLOCKED,
                    details = "failure=OFFER_MISSING"
                )
            )
            return null
        }

        val offerStatus = currentOffer.status.uppercase(Locale.US)
        if (offerStatus in setOf("SENT", "OPENED", "SIGNED")) {
            val reconciled = currentSend.copy(
                status = OfferEmailSendStatus.SENT,
                updatedAt = now,
                sentAt = currentSend.sentAt ?: currentOffer.sentAt ?: now,
                nextAttemptAt = null,
                lastError = null,
                lastFailureKind = null
            )
            updateEmailSend(reconciled)
            insertAuditEvent(
                OfferAuditEventEntity(
                    offerId = currentSend.offerId,
                    idempotencyKey = key,
                    eventType = "SENT_STATUS_RECONCILED",
                    timestamp = now,
                    status = OfferEmailSendStatus.SENT,
                    details = "Offer lifecycle status already records delivery."
                )
            )
            return null
        }

        if (offerStatus !in setOf("READY", "FAILED")) {
            updateEmailSend(
                currentSend.copy(
                    status = OfferEmailSendStatus.BLOCKED,
                    updatedAt = now,
                    nextAttemptAt = null,
                    lastError = "Offer status '$offerStatus' is not eligible for email delivery.",
                    lastFailureKind = OfferEmailFailureKind.VALIDATION
                )
            )
            insertAuditEvent(
                OfferAuditEventEntity(
                    offerId = currentSend.offerId,
                    idempotencyKey = key,
                    eventType = "SEND_BLOCKED",
                    timestamp = now,
                    status = OfferEmailSendStatus.BLOCKED,
                    details = "failure=OFFER_STATUS"
                )
            )
            return null
        }

        if (claimEmailSend(key, now) != 1) return null
        val claimed = getEmailSend(key) ?: return null
        updateOfferStatus(claimed.offerId, "SENDING", null, null)
        insertAuditEvent(
            OfferAuditEventEntity(
                offerId = claimed.offerId,
                idempotencyKey = key,
                eventType = "SEND_ATTEMPT_STARTED",
                timestamp = now,
                status = claimed.status,
                details = "attempt=${claimed.attemptCount}"
            )
        )
        return claimed
    }

    @Transaction
    suspend fun updateEmailSendAndAudit(
        send: OfferEmailSendEntity,
        offerStatus: String,
        event: OfferAuditEventEntity
    ) {
        updateEmailSend(send)
        updateOfferStatus(send.offerId, offerStatus, send.sentAt, send.lastError)
        insertAuditEvent(event)
    }

    @Transaction
    suspend fun updateOfferStatusAndAudit(
        offerId: String,
        offerStatus: String,
        sentAt: Long?,
        error: String?,
        event: OfferAuditEventEntity
    ): Boolean {
        val currentOffer = getOfferById(offerId) ?: return false
        if (getEmailSendForOffer(offerId)?.status == OfferEmailSendStatus.IN_FLIGHT) {
            insertAuditEvent(
                event.copy(
                    eventType = "OFFER_STATUS_UPDATE_DEFERRED",
                    details = "Status update was not applied while email delivery was in progress."
                )
            )
            return false
        }
        updateOfferStatus(offerId, offerStatus, sentAt ?: currentOffer.sentAt, error)
        insertAuditEvent(event)
        return true
    }

    @Query("SELECT * FROM offer_audit_events WHERE offerId = :offerId ORDER BY timestamp DESC, id DESC")
    fun getAuditTrailForOffer(offerId: String): Flow<List<OfferAuditEventEntity>>

    @Query("SELECT * FROM offer_audit_events ORDER BY timestamp DESC, id DESC")
    suspend fun getAllAuditEventsList(): List<OfferAuditEventEntity>

    @Insert
    suspend fun insertAuditEvent(event: OfferAuditEventEntity)

    @Insert
    suspend fun insertAuditEvents(events: List<OfferAuditEventEntity>)

    @Query("DELETE FROM offers WHERE id = :id")
    suspend fun deleteOffer(id: String)

    @Transaction
    suspend fun deleteOfferWithAudit(id: String, event: OfferAuditEventEntity): Boolean {
        if (getEmailSendForOffer(id)?.status == OfferEmailSendStatus.IN_FLIGHT) {
            insertAuditEvent(
                event.copy(
                    eventType = "OFFER_DELETION_DEFERRED",
                    details = "Deletion was not applied while email delivery was in progress."
                )
            )
            return false
        }
        insertAuditEvent(event)
        deleteOffer(id)
        return true
    }

    // Documents
    @Query("SELECT * FROM offer_documents WHERE offerId = :offerId")
    fun getDocumentsForOffer(offerId: String): Flow<List<OfferDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOfferDocument(document: OfferDocumentEntity)

    @Query("SELECT * FROM offers")
    suspend fun getAllOffersList(): List<OfferEntity>

    @Query("SELECT * FROM offer_documents")
    suspend fun getAllDocumentsList(): List<OfferDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffers(offers: List<OfferEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOfferDocuments(docs: List<OfferDocumentEntity>)
}
