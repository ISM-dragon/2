package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.adapter.PropertySeedData
import com.example.data.adapter.PropertySourceDeduplicationDecision
import com.example.data.adapter.PropertySourceManager
import com.example.data.local.AppDatabase
import com.example.data.local.dao.PropertyDao
import com.example.data.local.dao.PropertySourceDao
import com.example.data.local.entity.*
import com.example.domain.property.PropertyMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Read model used by the UI plus the write facade for property data.
 *
 * Everything that touches more than one table runs inside `withTransaction`: a property, its
 * satellites and its provenance row are written atomically. Ingestion goes through
 * [PropertyImportRepository] so deduplication and provenance are always applied.
 */
class PropertyRepository(
    private val database: AppDatabase,
    private val propertyDao: PropertyDao,
    private val sourceManager: PropertySourceManager,
    private val importer: PropertyImportRepository
) {
    private val sourceDao: PropertySourceDao get() = database.propertySourceDao()

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

    suspend fun getPropertyByCanonicalKey(canonicalKey: String): PropertyEntity? =
        propertyDao.getPropertyByCanonicalKey(canonicalKey)

    /** Every source record known for a property (MLS, wholesaler, county records, ...). */
    fun getProvenance(propertyId: String): Flow<List<PropertyProvenanceEntity>> =
        sourceDao.observeProvenanceForProperty(propertyId)

    suspend fun getProvenanceList(propertyId: String): List<PropertyProvenanceEntity> =
        sourceDao.getProvenanceForProperty(propertyId)

    // ── Writes ──────────────────────────────────────────────────────────────────────────────────

    suspend fun createProperty(
        property: PropertyEntity,
        images: List<PropertyImageEntity> = emptyList(),
        marketData: MarketDataEntity? = null,
        rentEstimate: RentEstimateEntity? = null,
        taxRecord: TaxRecordEntity? = null
    ) = withContext(Dispatchers.IO) {
        val canonical = PropertyMapper.canonicalized(property)
        database.withTransaction {
            propertyDao.insertProperty(canonical)
            if (images.isNotEmpty()) {
                propertyDao.insertImages(images.map { it.copy(id = 0, propertyId = canonical.id) })
            }
            marketData?.let { propertyDao.insertMarketData(it.copy(propertyId = canonical.id)) }
            rentEstimate?.let { propertyDao.insertRentEstimate(it.copy(propertyId = canonical.id)) }
            taxRecord?.let { propertyDao.insertTaxRecord(it.copy(propertyId = canonical.id)) }
        }
    }

    suspend fun updateProperty(property: PropertyEntity) = withContext(Dispatchers.IO) {
        database.withTransaction {
            propertyDao.updateProperty(PropertyMapper.canonicalized(property))
        }
    }

    /** Deleting the canonical row cascades to every child table (FKs own the cleanup). */
    suspend fun deleteProperty(id: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            propertyDao.deletePropertyById(id)
        }
    }

    suspend fun toggleSaved(id: String, currentSaved: Boolean) {
        propertyDao.setSavedStatus(id, !currentSaved)
    }

    suspend fun setDealStatus(id: String, isDeal: Boolean, score: Int) {
        propertyDao.setDealStatus(id, isDeal, score)
    }

    // ── Reads of satellite tables ───────────────────────────────────────────────────────────────

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

    // ── Ingestion ───────────────────────────────────────────────────────────────────────────────

    /** Seeds sources, the demo dataset (through the dedup pipeline) and canonical identities. */
    suspend fun seedInitialDataIfEmpty() = withContext(Dispatchers.IO) {
        importer.seedDefaultSources()
        val count = propertyDao.getPropertiesCount()
        if (count == 0) {
            for (bundle in PropertySeedData.getSeedBundles()) {
                importer.importBundle(bundle)
            }
        }
        importer.reconcileCanonicalKeys()
    }

    /** Imports one bundle (dedup + provenance + satellites) atomically. */
    suspend fun insertBundle(bundle: NormalizedPropertyBundle): ImportResult =
        importer.importBundle(bundle)

    /**
     * One import job per configured source, so sync status and counters stay attributable.
     *
     * Listings are handed to the importer rather than filtered here: an already-known address is
     * deduplicated inside [PropertyImportRepository], which keeps the fresher payload, merges the
     * conflicting fields and records a provenance row instead of dropping the update silently.
     */
    suspend fun syncFromSources(): List<ImportSummary> = withContext(Dispatchers.IO) {
        importer.seedDefaultSources()
        val sourceIds = sourceManager.availableSourceIds.ifEmpty { listOf(PropertySourceDefaults.INTERNAL_ID) }
        sourceIds.map { sourceId ->
            importer.runImport(
                sourceId = sourceId,
                triggerKind = "MANUAL",
                fetch = { sourceManager.fetchSource(sourceId, limit = 20) }
            )
        }
    }

    /** Claims canonical keys for rows migrated from v2 and merges legacy duplicates. */
    suspend fun reconcileIdentities(limit: Int = 250): ReconcileSummary =
        importer.reconcileCanonicalKeys(limit)

    fun observeImportJobs(limit: Int = 50): Flow<List<PropertyImportJobEntity>> =
        importer.observeRecentJobs(limit)
}
