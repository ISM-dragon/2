package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "financial_analyses")
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

@Entity(tableName = "financing_scenarios")
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

@Entity(tableName = "saved_properties")
data class SavedPropertyEntity(
    @PrimaryKey
    val propertyId: String,
    val savedAt: Long,
    val notes: String = "",
    val tag: String = "Watchlist"
)

@Entity(tableName = "saved_deals")
data class SavedDealEntity(
    @PrimaryKey
    val dealId: String,
    val propertyId: String,
    val qualificationReason: String,
    val targetOfferPrice: Double,
    val expectedRoi: Double,
    val savedAt: Long
)
