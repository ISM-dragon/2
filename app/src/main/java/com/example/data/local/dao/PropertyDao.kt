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

    // ── Canonical identity / deduplication ──────────────────────────────────────────────────────

    @Query("SELECT * FROM properties WHERE canonicalKey = :canonicalKey LIMIT 1")
    suspend fun getPropertyByCanonicalKey(canonicalKey: String): PropertyEntity?

    @Query("SELECT * FROM properties WHERE apn <> '' AND apn = :apn LIMIT 5")
    suspend fun findPropertiesByApn(apn: String): List<PropertyEntity>

    @Query("SELECT * FROM properties WHERE mlsNumber <> '' AND mlsNumber = :mlsNumber LIMIT 5")
    suspend fun findPropertiesByMlsNumber(mlsNumber: String): List<PropertyEntity>

    /** Legacy rows that were migrated without a canonical key; used by the reconcile pass. */
    @Query("SELECT * FROM properties WHERE canonicalKey IS NULL ORDER BY scannedAt DESC LIMIT :limit")
    suspend fun getPropertiesWithoutCanonicalKey(limit: Int): List<PropertyEntity>

    /** Cheap pre-filter (zip + city are indexed) before comparing normalized addresses in Kotlin. */
    @Query(
        "SELECT * FROM properties WHERE canonicalKey IS NULL AND zipCode = :zipCode AND city = :city " +
            "ORDER BY scannedAt DESC LIMIT :limit"
    )
    suspend fun getKeylessCandidates(zipCode: String, city: String, limit: Int): List<PropertyEntity>

    @Query(
        "UPDATE properties SET canonicalKey = :canonicalKey, normalizedAddress = :normalizedAddress, " +
            "unitNumber = :unitNumber, county = :county, countyFips = :countyFips, apn = :apn, " +
            "propertySubType = :propertySubType, lastVerifiedAt = :verifiedAt WHERE id = :id"
    )
    suspend fun updateCanonicalIdentity(
        id: String,
        canonicalKey: String,
        normalizedAddress: String,
        unitNumber: String,
        county: String,
        countyFips: String,
        apn: String,
        propertySubType: String,
        verifiedAt: Long
    )

    @Query("UPDATE properties SET primarySourceId = :sourceId WHERE id = :id")
    suspend fun setPrimarySource(id: String, sourceId: String?)

    @Query("UPDATE properties SET scannedAt = :seenAt WHERE id = :id AND scannedAt < :seenAt")
    suspend fun touchSeenAt(id: String, seenAt: Long)

    @Query("SELECT COUNT(*) FROM properties WHERE canonicalKey IS NULL")
    suspend fun countPropertiesWithoutCanonicalKey(): Int

    /** Property ids ordered by priority; used to pick the survivor of a dedup merge. */
    @Query("SELECT id FROM properties WHERE canonicalKey = :canonicalKey ORDER BY scannedAt ASC")
    suspend fun getPropertyIdsByCanonicalKey(canonicalKey: String): List<String>

    // ── Images ──────────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM property_images WHERE propertyId = :propertyId")
    fun getImagesForProperty(propertyId: String): Flow<List<PropertyImageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<PropertyImageEntity>)

    // ── Market data ─────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM market_data WHERE propertyId = :propertyId LIMIT 1")
    fun getMarketDataFlow(propertyId: String): Flow<MarketDataEntity?>

    @Query("SELECT * FROM market_data WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getMarketData(propertyId: String): MarketDataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketData(data: MarketDataEntity)

    // ── Rent estimates ──────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM rent_estimates WHERE propertyId = :propertyId LIMIT 1")
    fun getRentEstimateFlow(propertyId: String): Flow<RentEstimateEntity?>

    @Query("SELECT * FROM rent_estimates WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getRentEstimate(propertyId: String): RentEstimateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRentEstimate(rent: RentEstimateEntity)

    // ── Tax records ─────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM tax_records WHERE propertyId = :propertyId LIMIT 1")
    fun getTaxRecordFlow(propertyId: String): Flow<TaxRecordEntity?>

    @Query("SELECT * FROM tax_records WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getTaxRecord(propertyId: String): TaxRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaxRecord(tax: TaxRecordEntity)

    // ── Sales history ───────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM sales_history WHERE propertyId = :propertyId ORDER BY date DESC")
    fun getSalesHistory(propertyId: String): Flow<List<SalesHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalesHistory(history: List<SalesHistoryEntity>)

    // ── Canonical comps (property_comps) ────────────────────────────────────────────────────────

    @Query(
        "SELECT * FROM property_comps WHERE targetPropertyId = :propertyId " +
            "ORDER BY similarityScore DESC, distanceMiles ASC"
    )
    fun getCompsForProperty(propertyId: String): Flow<List<PropertyCompEntity>>

    @Query(
        "SELECT * FROM property_comps WHERE targetPropertyId = :propertyId ORDER BY saleDate DESC"
    )
    suspend fun getCompsListForProperty(propertyId: String): List<PropertyCompEntity>

    @Query(
        "SELECT * FROM property_comps WHERE targetPropertyId = :targetPropertyId AND compAddress = :compAddress " +
            "AND saleDate = :saleDate LIMIT 1"
    )
    suspend fun findComp(targetPropertyId: String, compAddress: String, saleDate: String): PropertyCompEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComps(comps: List<PropertyCompEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComp(comp: PropertyCompEntity): Long

    @Update
    suspend fun updateComp(comp: PropertyCompEntity)

    @Query("DELETE FROM property_comps WHERE id = :id")
    suspend fun deleteComp(id: Long)

    // ── Deletion ────────────────────────────────────────────────────────────────────────────────
    // Child rows (images, market data, rent, tax, sales, comps, provenance, enrichments,
    // financials, analyses, scenarios, saved rows) are removed by ON DELETE CASCADE; the explicit
    // helper queries below stay for the backup/restore path and for targeted cleanups.

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

    @Query("DELETE FROM property_comps WHERE targetPropertyId = :propertyId")
    suspend fun deleteCompsByPropertyId(propertyId: String)

    @Query("DELETE FROM property_comps WHERE compPropertyId = :propertyId")
    suspend fun clearCompLinksByPropertyId(propertyId: String)

    // ── Dedup merge support ─────────────────────────────────────────────────────────────────────
    // When two canonical rows turn out to describe the same property, the loser's child rows are
    // re-pointed onto the survivor (only where safe) before the loser is deleted.

    @Query("SELECT * FROM property_images WHERE propertyId = :propertyId")
    suspend fun getImagesListForProperty(propertyId: String): List<PropertyImageEntity>

    @Query("SELECT * FROM sales_history WHERE propertyId = :propertyId")
    suspend fun getSalesHistoryListForProperty(propertyId: String): List<SalesHistoryEntity>

    @Query("UPDATE property_images SET propertyId = :targetPropertyId WHERE propertyId = :sourcePropertyId")
    suspend fun repointImages(sourcePropertyId: String, targetPropertyId: String)

    @Query("UPDATE sales_history SET propertyId = :targetPropertyId WHERE propertyId = :sourcePropertyId")
    suspend fun repointSalesHistory(sourcePropertyId: String, targetPropertyId: String)

    @Query("UPDATE property_comps SET targetPropertyId = :targetPropertyId WHERE targetPropertyId = :sourcePropertyId")
    suspend fun repointComps(sourcePropertyId: String, targetPropertyId: String)

    // ── Backup / restore support ────────────────────────────────────────────────────────────────

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

    @Query("SELECT * FROM property_comps")
    suspend fun getAllCompsList(): List<PropertyCompEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketDataList(list: List<MarketDataEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRentEstimateList(list: List<RentEstimateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaxRecordList(list: List<TaxRecordEntity>)
}
