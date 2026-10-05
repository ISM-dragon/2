package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offers")
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

@Entity(tableName = "offer_documents")
data class OfferDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val offerId: String,
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val createdAt: Long
)
