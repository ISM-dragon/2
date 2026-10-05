package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class JobState {
    DISCOVERED,
    ANALYZING,
    ANALYZED,
    QUALIFYING,
    QUALIFIED,
    DISQUALIFIED,
    OFFER_GENERATION,
    OFFER_READY,
    VALIDATING_SEND,
    SENDING,
    SENT,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    BLOCKED,
    CANCELLED;

    fun canTransitionTo(next: JobState): Boolean {
        if (this == next) return true
        if (next == CANCELLED) return true
        if (next == FAILED_RETRYABLE || next == FAILED_TERMINAL) return true
        return when (this) {
            DISCOVERED -> next == ANALYZING || next == BLOCKED
            ANALYZING -> next == ANALYZED || next == FAILED_RETRYABLE || next == FAILED_TERMINAL
            ANALYZED -> next == QUALIFYING
            QUALIFYING -> next == QUALIFIED || next == DISQUALIFIED
            QUALIFIED -> next == OFFER_GENERATION
            DISQUALIFIED -> false
            OFFER_GENERATION -> next == OFFER_READY || next == FAILED_RETRYABLE || next == FAILED_TERMINAL
            OFFER_READY -> next == VALIDATING_SEND
            VALIDATING_SEND -> next == SENDING || next == BLOCKED
            SENDING -> next == SENT || next == FAILED_RETRYABLE || next == FAILED_TERMINAL
            SENT -> false
            FAILED_RETRYABLE -> next == ANALYZING || next == QUALIFYING || next == OFFER_GENERATION || next == VALIDATING_SEND || next == SENDING
            FAILED_TERMINAL -> false
            BLOCKED -> next == VALIDATING_SEND || next == ANALYZING
            CANCELLED -> false
        }
    }
}

@Entity(
    tableName = "automation_jobs",
    indices = [
        Index(value = ["propertyId"]),
        Index(value = ["currentState"]),
        Index(value = ["runId"])
    ]
)
data class AutomationJobEntity(
    @PrimaryKey
    val jobId: String,
    val runId: Long,
    val propertyId: String,
    val propertyAddress: String,
    val currentState: String, // JobState.name
    val lastSuccessfulState: String,
    val failedStep: String? = null,
    val analysisId: String? = null,
    val offerId: String? = null,
    val emailMessageId: String? = null,
    val recipientEmail: String? = null,
    val attempts: Int = 0,
    val maxRetries: Int = 3,
    val lastError: String? = null,
    val blockageReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey
    val id: String = "DEFAULT",
    val maxPurchasePrice: Double = 600000.0,
    val minCashFlow: Double = 300.0,
    val minCapRate: Double = 7.0,
    val minDscr: Double = 1.25,
    val minCashOnCash: Double = 8.0,
    val allowedLocations: String = "Austin, Dallas, Houston, Phoenix, Atlanta, Miami, Chicago",
    val allowedPropertyTypes: String = "Single Family, Multi-Family, Condo, Townhouse",
    val maxRenovationCost: Double = 75000.0,
    val minEstimatedRent: Double = 1500.0,
    val maxRiskScore: Int = 40,
    val offerDiscountPercent: Double = 8.5,
    val scanIntervalMinutes: Int = 15,
    val maxPropertiesPerCycle: Int = 10,
    val maxAnalysesPerRun: Int = 5,
    val maxOffersPerRun: Int = 3,
    val maxEmailsPerRun: Int = 3,
    val maxRetries: Int = 3,
    val autoGenerateOffers: Boolean = true,
    val autoSendOffers: Boolean = false,
    val consecutiveFailureThreshold: Int = 3
)

@Entity(tableName = "automation_runs")
data class AutomationRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val propertiesFound: Int = 0,
    val propertiesAnalyzed: Int = 0,
    val dealsQualified: Int = 0,
    val offersCreated: Int = 0,
    val offersSent: Int = 0,
    val status: String, // "RUNNING", "COMPLETED", "STOPPED", "ERROR"
    val summary: String = ""
)

@Entity(tableName = "automation_logs")
data class AutomationLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: Long? = null,
    val timestamp: Long,
    val level: String, // "INFO", "WARN", "ERROR", "SUCCESS"
    val tag: String,
    val message: String
)

@Entity(tableName = "automation_state")
data class AutomationStateEntity(
    @PrimaryKey
    val id: Int = 1,
    val isEnabled: Boolean = false,
    val currentOperation: String = "Engine Standby",
    val currentPropertyAddress: String = "",
    val currentStage: String = "Standby",
    val successfulJobs: Int = 0,
    val failedJobs: Int = 0,
    val lastError: String? = null,
    val lastSuccessfulAction: String = "None",
    val lastActivityTime: Long = 0L
)

