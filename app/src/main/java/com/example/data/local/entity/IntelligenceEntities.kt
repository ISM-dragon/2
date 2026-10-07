package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "property_sources",
    indices = [
        Index(value = ["propertyId"]),
        Index(value = ["sourceUrl"])
    ]
)
data class PropertySourceLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    val source: String,
    val sourceUrl: String,
    val listingId: String? = null,
    val isPrimary: Boolean = false,
    val lastSyncedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "property_provenance",
    indices = [
        Index(value = ["propertyId"]),
        Index(value = ["field"])
    ]
)
data class PropertyProvenanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    val field: String,
    val value: String,
    val source: String,
    val tier: String = "DIRECT_LISTING",
    val retrievedAt: Long = System.currentTimeMillis(),
    val confidence: Double = 0.95
)

@Entity(
    tableName = "property_import_jobs",
    indices = [
        Index(value = ["url"]),
        Index(value = ["propertyId"])
    ]
)
data class PropertyImportJobEntity(
    @PrimaryKey
    val jobId: String,
    val propertyId: String? = null,
    val source: String,
    val url: String,
    val status: String, // "QUEUED", "FETCHING", "PARSING", "NORMALIZING", "ENRICHING", "ANALYZING", "COMPLETED", "FAILED"
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0
)

@Entity(
    tableName = "property_comps",
    indices = [
        Index(value = ["targetPropertyId"])
    ]
)
data class PropertyCompEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetPropertyId: String,
    val address: String,
    val city: String,
    val state: String,
    val zipCode: String,
    val price: Double,
    val bedrooms: Int,
    val bathrooms: Double,
    val squareFeet: Int,
    val distanceMiles: Double,
    val pricePerSqFt: Double,
    val similarityScore: Int,
    val saleDate: String
)

@Entity(tableName = "property_enrichment")
data class PropertyEnrichmentEntity(
    @PrimaryKey
    val propertyId: String,
    val floodZone: String = "X (Minimal Risk)",
    val floodRiskLevel: String = "LOW",
    val censusTract: String = "",
    val medianHouseholdIncome: Double = 84000.0,
    val schoolRating: Int = 8,
    val crimeIndex: String = "Low-Moderate",
    val walkScore: Int = 74,
    val rentBenchmark: Double = 3100.0,
    val marketAppreciationRate: Double = 4.8,
    val taxAssessmentValue: Double = 410000.0,
    val lastEnrichedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "property_financials")
data class PropertyFinancialEntity(
    @PrimaryKey
    val propertyId: String,
    val strategy: String = "BUY_AND_HOLD",
    val financingType: String = "CONVENTIONAL",
    val purchasePrice: Double,
    val downPayment: Double,
    val loanAmount: Double,
    val interestRate: Double,
    val monthlyRent: Double,
    val operatingExpensesMonthly: Double,
    val netOperatingIncomeAnnual: Double,
    val monthlyDebtService: Double,
    val monthlyCashFlow: Double,
    val annualCashFlow: Double,
    val capRate: Double,
    val cashOnCashReturn: Double,
    val dscr: Double,
    val ltv: Double,
    val totalCashRequired: Double,
    val dealScore: Int,
    val calculatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "property_ai_analysis")
data class PropertyAiAnalysisEntity(
    @PrimaryKey
    val propertyId: String,
    val summary: String,
    val investmentThesis: String,
    val strengthsJson: String,
    val weaknessesJson: String,
    val risksJson: String,
    val redFlagsJson: String,
    val recommendedStrategy: String,
    val recommendedOfferRange: String,
    val questionsForSellerJson: String,
    val dueDiligenceJson: String,
    val confidence: Double,
    val evidenceJson: String,
    val analyzedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey
    val source: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val successRate: Double = 100.0,
    val failureRate: Double = 0.0,
    val averageLatencyMs: Long = 250L,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val parserVersion: String = "1.0.0",
    val healthStatus: String = "HEALTHY", // "HEALTHY", "DEGRADED", "SCHEMA_DRIFT", "BLOCKED"
    val lastErrorReason: String? = null
)
