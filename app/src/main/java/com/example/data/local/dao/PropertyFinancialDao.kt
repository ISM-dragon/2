package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.PropertyFinancialEntity
import kotlinx.coroutines.flow.Flow

/**
 * Canonical asset-level financial facts (as opposed to `FinancialDao` which owns the underwriting
 * results of a specific deal).
 */
@Dao
interface PropertyFinancialDao {

    @Query("SELECT * FROM property_financials WHERE propertyId = :propertyId LIMIT 1")
    fun observeFinancials(propertyId: String): Flow<PropertyFinancialEntity?>

    @Query("SELECT * FROM property_financials WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getFinancials(propertyId: String): PropertyFinancialEntity?

    @Query("SELECT * FROM property_financials ORDER BY lastUpdatedAt DESC")
    fun observeAll(): Flow<List<PropertyFinancialEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(financials: PropertyFinancialEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(financials: List<PropertyFinancialEntity>)

    @Query(
        "UPDATE property_financials SET assessedValue = :assessedValue, assessmentYear = :assessmentYear, " +
            "annualPropertyTax = :annualPropertyTax, effectiveTaxRatePct = :effectiveTaxRatePct, " +
            "annualInsurance = :annualInsurance, hoaMonthly = :hoaMonthly, " +
            "capitalReserveMonthly = :capitalReserveMonthly, " +
            "maintenanceReserveMonthly = :maintenanceReserveMonthly, " +
            "monthlyRentEstimate = :monthlyRentEstimate, rentEstimateLow = :rentEstimateLow, " +
            "rentEstimateHigh = :rentEstimateHigh, rentConfidence = :rentConfidence, " +
            "marketRentPerSqFt = :marketRentPerSqFt, grossRentMultiplier = :grossRentMultiplier, " +
            "grossYieldPct = :grossYieldPct, operatingExpenseRatioPct = :operatingExpenseRatioPct, " +
            "vacancyRatePct = :vacancyRatePct, dataSource = :dataSource, provenanceId = :provenanceId, " +
            "lastUpdatedAt = :updatedAt WHERE propertyId = :propertyId"
    )
    suspend fun updateFacts(
        propertyId: String,
        assessedValue: Double,
        assessmentYear: Int,
        annualPropertyTax: Double,
        effectiveTaxRatePct: Double,
        annualInsurance: Double,
        hoaMonthly: Double,
        capitalReserveMonthly: Double,
        maintenanceReserveMonthly: Double,
        monthlyRentEstimate: Double,
        rentEstimateLow: Double,
        rentEstimateHigh: Double,
        rentConfidence: Double,
        marketRentPerSqFt: Double,
        grossRentMultiplier: Double,
        grossYieldPct: Double,
        operatingExpenseRatioPct: Double,
        vacancyRatePct: Double,
        dataSource: String,
        provenanceId: Long?,
        updatedAt: Long
    )

    @Query("SELECT * FROM property_financials")
    suspend fun getAll(): List<PropertyFinancialEntity>

    @Query("SELECT COUNT(*) FROM property_financials WHERE propertyId = :propertyId")
    suspend fun countForProperty(propertyId: String): Int

    @Query("DELETE FROM property_financials WHERE propertyId = :propertyId")
    suspend fun deleteForProperty(propertyId: String)
}
