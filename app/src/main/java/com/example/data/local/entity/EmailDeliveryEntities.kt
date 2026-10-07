package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object OfferEmailSendStatus {
    const val PENDING = "PENDING"
    const val IN_FLIGHT = "IN_FLIGHT"
    const val RETRYABLE = "RETRYABLE"
    const val SENT = "SENT"
    const val BLOCKED = "BLOCKED"
    const val FAILED = "FAILED"
    const val UNKNOWN = "UNKNOWN"
}

object OfferEmailFailureKind {
    const val VALIDATION = "VALIDATION"
    const val AUTHENTICATION = "AUTHENTICATION"
    const val RETRYABLE_REJECTED = "RETRYABLE_REJECTED"
    const val PERMANENT_REJECTED = "PERMANENT_REJECTED"
    const val DELIVERY_UNKNOWN = "DELIVERY_UNKNOWN"
}

/** Durable, one-record-per-offer delivery state. The stable key prevents concurrent/repeated sends. */
@Entity(
    tableName = "offer_email_sends",
    indices = [
        Index(value = ["offerId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["nextAttemptAt"])
    ]
)
data class OfferEmailSendEntity(
    @PrimaryKey val idempotencyKey: String,
    val offerId: String,
    val status: String = OfferEmailSendStatus.PENDING,
    val attemptCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val sentAt: Long? = null,
    val messageId: String? = null,
    val nextAttemptAt: Long? = null,
    val lastError: String? = null,
    val lastFailureKind: String? = null
)

/** Append-only audit record; token values, message contents, and full request/response bodies are excluded. */
@Entity(
    tableName = "offer_audit_events",
    indices = [
        Index(value = ["offerId", "timestamp"]),
        Index(value = ["idempotencyKey"])
    ]
)
data class OfferAuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val offerId: String,
    val idempotencyKey: String? = null,
    val eventType: String,
    val timestamp: Long,
    val status: String? = null,
    val details: String? = null
)
