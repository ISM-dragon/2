package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Purchase offer.
 *
 * `propertyId` is deliberately a *soft* relationship (no foreign key): offers are legal/commercial
 * records that must never be silently removed because a listing row was cleaned up. The index keeps
 * the per-property lookups fast instead.
 */
@Entity(
    tableName = "offers",
    indices = [
        Index(value = ["propertyId", "createdAt"]),
        Index(value = ["status", "createdAt"]),
        Index(value = ["createdAt"])
    ]
)
data class OfferEntity(
    @PrimaryKey
    val id: String,
    val propertyId: String,
    val recipientName: String,
    val recipientEmail: String,
    val offerPrice: Double,
    val earnestMoney: Double,
    val inspectionPeriodDays: Int,
    val closingPeriodDays: Int,
    val contingencies: String,
    val terms: String,
    val conditions: String,
    val expirationDate: String,
    val generatedLetterContent: String,
    val pdfPath: String? = null,
    val status: String, // "DRAFT", "GENERATED", "READY", "SENT", "OPENED", "SIGNED", "FAILED", "DECLINED", "EXPIRED"
    val createdAt: Long,
    val sentAt: Long? = null,
    val lastError: String? = null
)

/** Generated document for an offer. Cascade delete: a document cannot outlive its offer. */
@Entity(
    tableName = "offer_documents",
    indices = [Index(value = ["offerId"])],
    foreignKeys = [
        ForeignKey(
            entity = OfferEntity::class,
            parentColumns = ["id"],
            childColumns = ["offerId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class OfferDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val offerId: String,
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val createdAt: Long
)
