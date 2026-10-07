package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.dao.PropertyDao
import com.example.data.local.dao.PropertyFinancialDao
import com.example.data.local.entity.PropertyFinancialDataSource
import com.example.data.local.entity.PropertyFinancialEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Canonical asset-level financial facts.
 *
 * `financial_analyses` (FinancialRepository) keeps the *deal* model produced by an underwriting run;
 * this repository keeps the *asset* facts those runs should start from: taxes, insurance, HOA,
 * achievable rent and the derived US yield metrics (GRM, gross yield, expense ratio).
 */
class PropertyFinancialRepository(
    private val database: AppDatabase,
    private val financialDao: PropertyFinancialDao,
    private val propertyDao: PropertyDao,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun observeFinancials(propertyId: String): Flow<PropertyFinancialEntity?> =
        financialDao.observeFinancials(propertyId)

    suspend fun getFinancials(propertyId: String): PropertyFinancialEntity? =
        financialDao.getFinancials(propertyId)

    suspend fun upsert(financials: PropertyFinancialEntity) = withContext(Dispatchers.IO) {
        database.withTransaction {
            financialDao.upsert(
                financials.copy(
                    lastUpdatedAt = if (financials.lastUpdatedAt == 0L) clock() else financials.lastUpdatedAt
                )
            )
        }
    }

    /**
     * Rebuilds the canonical facts from the legacy satellite tables (`tax_records`,
     * `rent_estimates`) and the property row itself. Used by the import pipeline and by the
     * reconcile pass so v2 databases gain canonical financials without any manual work.
     */
    suspend fun deriveFromSatellites(propertyId: String): PropertyFinancialEntity? =
        withContext(Dispatchers.IO) {
            val property = propertyDao.getPropertyById(propertyId) ?: return@withContext null
            val tax = propertyDao.getTaxRecord(propertyId)
            val rent = propertyDao.getRentEstimate(propertyId)

            val assessedValue = tax?.assessedValue ?: 0.0
            val annualTax = tax?.annualTaxAmount ?: (property.price * DEFAULT_TAX_RATE_PCT / 100.0)
            val effectiveTaxRate = if (assessedValue > 0) (annualTax / assessedValue) * 100.0 else 0.0
            val monthlyRent = rent?.estimatedRent ?: 0.0
            val annualRent = monthlyRent * 12.0
            val grossRentMultiplier = if (annualRent > 0 && property.price > 0) property.price / annualRent else 0.0
            val grossYield = if (property.price > 0) (annualRent / property.price) * 100.0 else 0.0
            val marketRentPerSqFt = if (property.squareFeet > 0) monthlyRent / property.squareFeet else 0.0
            val expenseRatio = if (annualRent > 0) {
                (annualTax + estimatedInsurance(property.price)) / annualRent * 100.0
            } else {
                0.0
            }

            PropertyFinancialEntity(
                propertyId = propertyId,
                assessedValue = assessedValue,
                assessmentYear = tax?.assessmentYear ?: 0,
                annualPropertyTax = annualTax,
                effectiveTaxRatePct = effectiveTaxRate,
                annualInsurance = estimatedInsurance(property.price),
                hoaMonthly = property.hoaMonthly,
                capitalReserveMonthly = monthlyRent * CAPEX_RESERVE_PCT / 100.0,
                maintenanceReserveMonthly = monthlyRent * MAINTENANCE_RESERVE_PCT / 100.0,
                monthlyRentEstimate = monthlyRent,
                rentEstimateLow = rent?.rentRangeLow ?: 0.0,
                rentEstimateHigh = rent?.rentRangeHigh ?: 0.0,
                rentConfidence = rent?.rentConfidenceScore ?: 0.0,
                marketRentPerSqFt = marketRentPerSqFt,
                grossRentMultiplier = grossRentMultiplier,
                grossYieldPct = grossYield,
                operatingExpenseRatioPct = expenseRatio,
                vacancyRatePct = rent?.let { DEFAULT_VACANCY_PCT } ?: 0.0,
                dataSource = if (tax != null) PropertyFinancialDataSource.RECORDS else PropertyFinancialDataSource.ESTIMATE,
                provenanceId = null,
                lastUpdatedAt = clock()
            )
        }

    suspend fun recompute(propertyId: String): PropertyFinancialEntity? {
        val derived = deriveFromSatellites(propertyId) ?: return null
        upsert(derived)
        return derived
    }

    suspend fun deleteForProperty(propertyId: String) = withContext(Dispatchers.IO) {
        financialDao.deleteForProperty(propertyId)
    }

    private fun estimatedInsurance(price: Double): Double =
        if (price <= 0) 0.0 else (price * INSURANCE_RATE_PCT / 100.0)

    private companion object {
        const val DEFAULT_TAX_RATE_PCT = 1.2
        const val INSURANCE_RATE_PCT = 0.45
        const val CAPEX_RESERVE_PCT = 5.0
        const val MAINTENANCE_RESERVE_PCT = 5.0
        const val DEFAULT_VACANCY_PCT = 5.0
    }
}