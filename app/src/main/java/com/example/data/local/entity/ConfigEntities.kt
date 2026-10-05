package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "api_configurations")
data class ApiConfigurationEntity(
    @PrimaryKey
    val slotIndex: Int, // 1 to 6
    val label: String,
    val apiKey: String,
    val model: String = "gemini-2.5-flash",
    val projectId: String = "",
    val status: String = "READY", // "READY", "ACTIVE", "COOLDOWN", "ERROR", "DISABLED"
    val lastRequestTime: Long = 0L,
    val cooldownUntil: Long = 0L,
    val usageCount: Int = 0,
    val errorCount: Int = 0,
    val lastErrorMessage: String? = null
)

@Entity(tableName = "gmail_configuration")
data class GmailConfigurationEntity(
    @PrimaryKey
    val id: Int = 1,
    val isConnected: Boolean = false,
    val accountEmail: String = "",
    val senderName: String = "",
    val signature: String = "Best regards,\nReal Estate Investment Team",
    val defaultSubjectTemplate: String = "Purchase Offer & Letter of Intent - {property_address}",
    val defaultCc: String = "",
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L
)

@Entity(tableName = "offer_templates")
data class OfferTemplateEntity(
    @PrimaryKey
    val id: String = "DEFAULT",
    val templateName: String = "Standard Cash & Financing Purchase Agreement",
    val headerTitle: String = "REAL ESTATE PURCHASE OFFER & LETTER OF INTENT",
    val earnestMoneyPercent: Double = 1.5,
    val defaultInspectionDays: Int = 10,
    val defaultClosingDays: Int = 21,
    val standardTerms: String = "1. Property is purchased AS-IS with right of inspection.\n2. Buyer requires clear and marketable title.\n3. Taxes, utilities, and assessments to be prorated to closing date.\n4. Closing costs split per local real estate custom.",
    val standardConditions: String = "Subject to satisfactory physical inspection and review of lease agreements if tenant occupied."
)
