package com.example.domain.intelligence.dedup

import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test support for the URL-intelligence deduplication tests.
 *
 * [PropertyDaoStub] is a no-op implementation of the full DAO surface (kept in one place so the
 * fakes stay declarative); tests delegate to it and override only the queries they exercise.
 */
object PropertyDaoStub : PropertyDao {
    override fun getAllProperties(): Flow<List<PropertyEntity>> = flowOf(emptyList())
    override fun getPropertiesBySource(sourceType: String): Flow<List<PropertyEntity>> = flowOf(emptyList())
    override fun getPropertyByIdFlow(id: String): Flow<PropertyEntity?> = flowOf(null)
    override suspend fun getPropertyById(id: String): PropertyEntity? = null
    override fun getSavedProperties(): Flow<List<PropertyEntity>> = flowOf(emptyList())
    override fun getSavedDeals(): Flow<List<PropertyEntity>> = flowOf(emptyList())
    override fun getRecentOpportunities(): Flow<List<PropertyEntity>> = flowOf(emptyList())
    override fun getPropertiesCountFlow(): Flow<Int> = flowOf(0)
    override suspend fun getPropertiesCount(): Int = 0
    override fun getQualifiedDealsCountFlow(): Flow<Int> = flowOf(0)
    override suspend fun insertProperties(properties: List<PropertyEntity>) {}
    override suspend fun insertProperty(property: PropertyEntity) {}
    override suspend fun updateProperty(property: PropertyEntity) {}
    override suspend fun setSavedStatus(id: String, isSaved: Boolean) {}
    override suspend fun setDealStatus(id: String, isDeal: Boolean, score: Int) {}
    override suspend fun getPropertyByCanonicalKey(canonicalKey: String): PropertyEntity? = null
    override suspend fun findPropertiesByApn(apn: String): List<PropertyEntity> = emptyList()
    override suspend fun findPropertiesByMlsNumber(mlsNumber: String): List<PropertyEntity> = emptyList()
    override suspend fun getPropertiesWithoutCanonicalKey(limit: Int): List<PropertyEntity> = emptyList()
    override suspend fun getKeylessCandidates(zipCode: String, city: String, limit: Int): List<PropertyEntity> = emptyList()
    override suspend fun updateCanonicalIdentity( id: String, canonicalKey: String, normalizedAddress: String, unitNumber: String, county: String, countyFips: String, apn: String, propertySubType: String, verifiedAt: Long ) {}
    override suspend fun setPrimarySource(id: String, sourceId: String?) {}
    override suspend fun touchSeenAt(id: String, seenAt: Long) {}
    override suspend fun countPropertiesWithoutCanonicalKey(): Int = 0
    override suspend fun getPropertyIdsByCanonicalKey(canonicalKey: String): List<String> = emptyList()
    override fun getImagesForProperty(propertyId: String): Flow<List<PropertyImageEntity>> = flowOf(emptyList())
    override suspend fun insertImages(images: List<PropertyImageEntity>) {}
    override fun getMarketDataFlow(propertyId: String): Flow<MarketDataEntity?> = flowOf(null)
    override suspend fun getMarketData(propertyId: String): MarketDataEntity? = null
    override suspend fun insertMarketData(data: MarketDataEntity) {}
    override fun getRentEstimateFlow(propertyId: String): Flow<RentEstimateEntity?> = flowOf(null)
    override suspend fun getRentEstimate(propertyId: String): RentEstimateEntity? = null
    override suspend fun insertRentEstimate(rent: RentEstimateEntity) {}
    override fun getTaxRecordFlow(propertyId: String): Flow<TaxRecordEntity?> = flowOf(null)
    override suspend fun getTaxRecord(propertyId: String): TaxRecordEntity? = null
    override suspend fun insertTaxRecord(tax: TaxRecordEntity) {}
    override fun getSalesHistory(propertyId: String): Flow<List<SalesHistoryEntity>> = flowOf(emptyList())
    override suspend fun insertSalesHistory(history: List<SalesHistoryEntity>) {}
    override fun getCompsForProperty(propertyId: String): Flow<List<PropertyCompEntity>> = flowOf(emptyList())
    override suspend fun getCompsListForProperty(propertyId: String): List<PropertyCompEntity> = emptyList()
    override suspend fun findComp(targetPropertyId: String, compAddress: String, saleDate: String): PropertyCompEntity? = null
    override suspend fun insertComps(comps: List<PropertyCompEntity>) {}
    override suspend fun insertComp(comp: PropertyCompEntity): Long = 0L
    override suspend fun updateComp(comp: PropertyCompEntity) {}
    override suspend fun deleteComp(id: Long) {}
    override suspend fun deletePropertyById(id: String) {}
    override suspend fun deleteImagesByPropertyId(propertyId: String) {}
    override suspend fun deleteMarketDataByPropertyId(propertyId: String) {}
    override suspend fun deleteRentEstimateByPropertyId(propertyId: String) {}
    override suspend fun deleteTaxRecordByPropertyId(propertyId: String) {}
    override suspend fun deleteSalesHistoryByPropertyId(propertyId: String) {}
    override suspend fun deleteCompsByPropertyId(propertyId: String) {}
    override suspend fun clearCompLinksByPropertyId(propertyId: String) {}
    override suspend fun getImagesListForProperty(propertyId: String): List<PropertyImageEntity> = emptyList()
    override suspend fun getSalesHistoryListForProperty(propertyId: String): List<SalesHistoryEntity> = emptyList()
    override suspend fun repointImages(sourcePropertyId: String, targetPropertyId: String) {}
    override suspend fun repointSalesHistory(sourcePropertyId: String, targetPropertyId: String) {}
    override suspend fun repointComps(sourcePropertyId: String, targetPropertyId: String) {}
    override suspend fun getAllPropertiesList(): List<PropertyEntity> = emptyList()
    override suspend fun getAllImagesList(): List<PropertyImageEntity> = emptyList()
    override suspend fun getAllMarketDataList(): List<MarketDataEntity> = emptyList()
    override suspend fun getAllRentEstimatesList(): List<RentEstimateEntity> = emptyList()
    override suspend fun getAllTaxRecordsList(): List<TaxRecordEntity> = emptyList()
    override suspend fun getAllSalesHistoryList(): List<SalesHistoryEntity> = emptyList()
    override suspend fun getAllCompsList(): List<PropertyCompEntity> = emptyList()
    override suspend fun insertMarketDataList(list: List<MarketDataEntity>) {}
    override suspend fun insertRentEstimateList(list: List<RentEstimateEntity>) {}
    override suspend fun insertTaxRecordList(list: List<TaxRecordEntity>) {}
}

/**
 * Deterministic in-memory property catalog for deduplication tests.
 *
 * [shuffleSeed] shuffles the row order returned by the catalog queries: the deduplicator must
 * produce the same result no matter what order the DAO happens to return rows in.
 */
class FakePropertyDao(
    private val rows: List<PropertyEntity>,
    private val shuffleSeed: Long? = null
) : PropertyDao by PropertyDaoStub {

    override fun getAllProperties(): Flow<List<PropertyEntity>> = flowOf(orderedRows())

    override suspend fun getAllPropertiesList(): List<PropertyEntity> = orderedRows()

    override suspend fun getPropertyById(id: String): PropertyEntity? = rows.firstOrNull { it.id == id }

    override suspend fun getPropertiesCount(): Int = rows.size

    override fun getPropertiesCountFlow(): Flow<Int> = flowOf(rows.size)

    private fun orderedRows(): List<PropertyEntity> =
        if (shuffleSeed == null) rows else rows.shuffled(java.util.Random(shuffleSeed))
}
