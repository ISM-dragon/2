package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PropertyDao {
    @Query("SELECT * FROM properties ORDER BY scannedAt DESC")
    fun getAllProperties(): Flow<List<PropertyEntity>>

    @Query("SELECT * FROM properties WHERE sourceType = :sourceType ORDER BY scannedAt DESC")
    fun getPropertiesBySource(sourceType: String): Flow<List<PropertyEntity>>

    @Query("SELECT * FROM properties WHERE id = :id LIMIT 1")
    fun getPropertyByIdFlow(id: String): Flow<PropertyEntity?>

    @Query("SELECT * FROM properties WHERE id = :id LIMIT 1")
    suspend fun getPropertyById(id: String): PropertyEntity?

    @Query("SELECT * FROM properties WHERE isSaved = 1 ORDER BY scannedAt DESC")
    fun getSavedProperties(): Flow<List<PropertyEntity>>

    @Query("SELECT * FROM properties WHERE isSavedDeal = 1 ORDER BY dealScore DESC")
    fun getSavedDeals(): Flow<List<PropertyEntity>>

    @Query("SELECT * FROM properties ORDER BY dealScore DESC LIMIT 5")
    fun getRecentOpportunities(): Flow<List<PropertyEntity>>

    @Query("SELECT COUNT(*) FROM properties")
    fun getPropertiesCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM properties")
    suspend fun getPropertiesCount(): Int

    @Query("SELECT COUNT(*) FROM properties WHERE isSavedDeal = 1")
    fun getQualifiedDealsCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProperties(properties: List<PropertyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProperty(property: PropertyEntity)

    @Update
    suspend fun updateProperty(property: PropertyEntity)

    @Query("UPDATE properties SET isSaved = :isSaved WHERE id = :id")
    suspend fun setSavedStatus(id: String, isSaved: Boolean)

    @Query("UPDATE properties SET isSavedDeal = :isDeal, dealScore = :score WHERE id = :id")
    suspend fun setDealStatus(id: String, isDeal: Boolean, score: Int)

    // Images
    @Query("SELECT * FROM property_images WHERE propertyId = :propertyId")
    fun getImagesForProperty(propertyId: String): Flow<List<PropertyImageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<PropertyImageEntity>)

    // Market data
    @Query("SELECT * FROM market_data WHERE propertyId = :propertyId LIMIT 1")
    fun getMarketDataFlow(propertyId: String): Flow<MarketDataEntity?>

    @Query("SELECT * FROM market_data WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getMarketData(propertyId: String): MarketDataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketData(data: MarketDataEntity)

    // Rent estimates
    @Query("SELECT * FROM rent_estimates WHERE propertyId = :propertyId LIMIT 1")
    fun getRentEstimateFlow(propertyId: String): Flow<RentEstimateEntity?>

    @Query("SELECT * FROM rent_estimates WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getRentEstimate(propertyId: String): RentEstimateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRentEstimate(rent: RentEstimateEntity)

    // Tax records
    @Query("SELECT * FROM tax_records WHERE propertyId = :propertyId LIMIT 1")
    fun getTaxRecordFlow(propertyId: String): Flow<TaxRecordEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaxRecord(tax: TaxRecordEntity)

    // Sales history
    @Query("SELECT * FROM sales_history WHERE propertyId = :propertyId ORDER BY date DESC")
    fun getSalesHistory(propertyId: String): Flow<List<SalesHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalesHistory(history: List<SalesHistoryEntity>)

    // Comps
    @Query("SELECT * FROM comparable_properties WHERE targetPropertyId = :propertyId")
    fun getCompsForProperty(propertyId: String): Flow<List<ComparablePropertyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComps(comps: List<ComparablePropertyEntity>)

    // Deletion
    @Query("DELETE FROM properties WHERE id = :id")
    suspend fun deletePropertyById(id: String)

    @Query("DELETE FROM property_images WHERE propertyId = :propertyId")
    suspend fun deleteImagesByPropertyId(propertyId: String)

    @Query("DELETE FROM market_data WHERE propertyId = :propertyId")
    suspend fun deleteMarketDataByPropertyId(propertyId: String)

    @Query("DELETE FROM rent_estimates WHERE propertyId = :propertyId")
    suspend fun deleteRentEstimateByPropertyId(propertyId: String)

    @Query("DELETE FROM tax_records WHERE propertyId = :propertyId")
    suspend fun deleteTaxRecordByPropertyId(propertyId: String)

    @Query("DELETE FROM sales_history WHERE propertyId = :propertyId")
    suspend fun deleteSalesHistoryByPropertyId(propertyId: String)

    @Query("DELETE FROM comparable_properties WHERE targetPropertyId = :propertyId")
    suspend fun deleteCompsByPropertyId(propertyId: String)

    @Query("SELECT * FROM comparable_properties WHERE targetPropertyId = :propertyId")
    suspend fun getCompsListForProperty(propertyId: String): List<ComparablePropertyEntity>

    @Query("SELECT * FROM properties")
    suspend fun getAllPropertiesList(): List<PropertyEntity>

    @Query("SELECT * FROM property_images")
    suspend fun getAllImagesList(): List<PropertyImageEntity>

    @Query("SELECT * FROM market_data")
    suspend fun getAllMarketDataList(): List<MarketDataEntity>

    @Query("SELECT * FROM rent_estimates")
    suspend fun getAllRentEstimatesList(): List<RentEstimateEntity>

    @Query("SELECT * FROM tax_records")
    suspend fun getAllTaxRecordsList(): List<TaxRecordEntity>

    @Query("SELECT * FROM sales_history")
    suspend fun getAllSalesHistoryList(): List<SalesHistoryEntity>

    @Query("SELECT * FROM comparable_properties")
    suspend fun getAllCompsList(): List<ComparablePropertyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketDataList(list: List<MarketDataEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRentEstimateList(list: List<RentEstimateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaxRecordList(list: List<TaxRecordEntity>)
}
