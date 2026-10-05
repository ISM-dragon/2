package com.example.data.repository

import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.adapter.PropertySeedData
import com.example.data.adapter.PropertySourceManager
import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class PropertyRepository(
    private val propertyDao: PropertyDao,
    private val sourceManager: PropertySourceManager
) {
    val allProperties: Flow<List<PropertyEntity>> = propertyDao.getAllProperties()
    val savedProperties: Flow<List<PropertyEntity>> = propertyDao.getSavedProperties()
    val savedDeals: Flow<List<PropertyEntity>> = propertyDao.getSavedDeals()
    val recentOpportunities: Flow<List<PropertyEntity>> = propertyDao.getRecentOpportunities()
    val totalCount: Flow<Int> = propertyDao.getPropertiesCountFlow()
    val qualifiedDealsCount: Flow<Int> = propertyDao.getQualifiedDealsCountFlow()

    fun getPropertiesBySource(sourceType: String): Flow<List<PropertyEntity>> =
        propertyDao.getPropertiesBySource(sourceType)

    fun getPropertyByIdFlow(id: String): Flow<PropertyEntity?> =
        propertyDao.getPropertyByIdFlow(id)

    suspend fun getPropertyById(id: String): PropertyEntity? =
        propertyDao.getPropertyById(id)

    suspend fun createProperty(
        property: PropertyEntity,
        images: List<PropertyImageEntity> = emptyList(),
        marketData: MarketDataEntity? = null,
        rentEstimate: RentEstimateEntity? = null,
        taxRecord: TaxRecordEntity? = null
    ) = withContext(Dispatchers.IO) {
        propertyDao.insertProperty(property)
        if (images.isNotEmpty()) propertyDao.insertImages(images)
        marketData?.let { propertyDao.insertMarketData(it) }
        rentEstimate?.let { propertyDao.insertRentEstimate(it) }
        taxRecord?.let { propertyDao.insertTaxRecord(it) }
    }

    suspend fun updateProperty(property: PropertyEntity) = withContext(Dispatchers.IO) {
        propertyDao.updateProperty(property)
    }

    suspend fun deleteProperty(id: String) = withContext(Dispatchers.IO) {
        propertyDao.deletePropertyById(id)
        propertyDao.deleteImagesByPropertyId(id)
        propertyDao.deleteMarketDataByPropertyId(id)
        propertyDao.deleteRentEstimateByPropertyId(id)
        propertyDao.deleteTaxRecordByPropertyId(id)
        propertyDao.deleteSalesHistoryByPropertyId(id)
        propertyDao.deleteCompsByPropertyId(id)
    }

    suspend fun toggleSaved(id: String, currentSaved: Boolean) {
        propertyDao.setSavedStatus(id, !currentSaved)
    }

    suspend fun setDealStatus(id: String, isDeal: Boolean, score: Int) {
        propertyDao.setDealStatus(id, isDeal, score)
    }

    fun getImages(propertyId: String): Flow<List<PropertyImageEntity>> =
        propertyDao.getImagesForProperty(propertyId)

    fun getMarketDataFlow(propertyId: String): Flow<MarketDataEntity?> =
        propertyDao.getMarketDataFlow(propertyId)

    suspend fun getMarketData(propertyId: String): MarketDataEntity? =
        propertyDao.getMarketData(propertyId)

    fun getRentEstimateFlow(propertyId: String): Flow<RentEstimateEntity?> =
        propertyDao.getRentEstimateFlow(propertyId)

    suspend fun getRentEstimate(propertyId: String): RentEstimateEntity? =
        propertyDao.getRentEstimate(propertyId)

    fun getTaxRecordFlow(propertyId: String): Flow<TaxRecordEntity?> =
        propertyDao.getTaxRecordFlow(propertyId)

    suspend fun getTaxRecord(propertyId: String): TaxRecordEntity? =
        propertyDao.getTaxRecord(propertyId)

    fun getSalesHistory(propertyId: String): Flow<List<SalesHistoryEntity>> =
        propertyDao.getSalesHistory(propertyId)

    fun getComps(propertyId: String): Flow<List<ComparablePropertyEntity>> =
        propertyDao.getCompsForProperty(propertyId)

    suspend fun seedInitialDataIfEmpty() = withContext(Dispatchers.IO) {
        val count = propertyDao.getPropertiesCount()
        if (count == 0) {
            val bundles = PropertySeedData.getSeedBundles()
            for (bundle in bundles) {
                insertBundle(bundle)
            }
        }
    }

    suspend fun insertBundle(bundle: NormalizedPropertyBundle) = withContext(Dispatchers.IO) {
        propertyDao.insertProperty(bundle.property)
        propertyDao.insertImages(bundle.images)
        propertyDao.insertMarketData(bundle.marketData)
        propertyDao.insertRentEstimate(bundle.rentEstimate)
        propertyDao.insertTaxRecord(bundle.taxRecord)
        propertyDao.insertSalesHistory(bundle.salesHistory)
        propertyDao.insertComps(bundle.comps)
    }

    suspend fun syncFromSources() = withContext(Dispatchers.IO) {
        val bundles = sourceManager.fetchAllSources()
        for (bundle in bundles) {
            insertBundle(bundle)
        }
    }
}
