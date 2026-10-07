package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Result of an underwriting run for a property (the investor's deal model).
 *
 * Note: asset-level facts (taxes, HOA, achievable rent, ...) live in [PropertyFinancialEntity].
 */
@Entity(
    tableName = "financial_analyses",
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FinancialAnalysisEntity(
    @PrimaryKey
    val propertyId: String,
    val purchasePrice: Double,
    val closingCosts: Double,
    val renovationCost: Double,
    val monthlyRent: Double,
    val otherMonthlyIncome: Double,
    val vacancyRatePct: Double,
    val propertyTaxAnnual: Double,
    val insuranceAnnual: Double,
    val maintenancePct: Double,
    val managementPct: Double,
    val utilitiesMonthly: Double,
    val downPaymentPct: Double,
    val interestRatePct: Double,
    val loanTermYears: Int,

    // Core Calculated Metrics
    val grossRentalIncome: Double,
    val effectiveRentalIncome: Double,
    val operatingExpensesMonthly: Double,
    val noiAnnual: Double,
    val monthlyDebtService: Double,
    val monthlyCashFlow: Double,
    val annualCashFlow: Double,
    val capRate: Double,
    val cashOnCashReturn: Double,
    val dscr: Double,
    val breakEvenOccupancyPct: Double,
    val totalCashRequired: Double,
    val calculatedAt: Long,
    val isQualified: Boolean = false,
    val dealScore: Int = 0,
    val qualificationSummary: String = ""
)

@Entity(
    tableName = "financing_scenarios",
    indices = [
        Index(value = ["propertyId"]),
        Index(value = ["propertyId", "scenarioName"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FinancingScenarioEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val propertyId: String,
    val scenarioName: String,
    val downPaymentPct: Double,
    val interestRatePct: Double,
    val loanTermYears: Int,
    val monthlyPayment: Double,
    val cashRequired: Double,
    val monthlyCashFlow: Double,
    val cashOnCash: Double,
    val dscr: Double
)

/**
 * Watchlist entry. Kept in sync with the `properties.isSaved` flag which remains the value the UI
 * reads; the FK guarantees a watchlist row cannot outlive its property.
 */
@Entity(
    tableName = "saved_properties",
    indices = [Index(value = ["savedAt"])],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SavedPropertyEntity(
    @PrimaryKey
    val propertyId: String,
    val savedAt: Long,
    val notes: String = "",
    val tag: String = "Watchlist"
)

@Entity(
    tableName = "saved_deals",
    indices = [
        Index(value = ["propertyId"]),
        Index(value = ["savedAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PropertyEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SavedDealEntity(
    @PrimaryKey
    val dealId: String,
    val propertyId: String,
    val qualificationReason: String,
    val targetOfferPrice: Double,
    val expectedRoi: Double,
    val savedAt: Long
)
