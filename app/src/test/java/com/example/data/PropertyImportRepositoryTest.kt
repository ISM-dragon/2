package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.adapter.PropertySourceAdapter
import com.example.data.adapter.PropertySourceManager
import com.example.data.local.AppDatabase
import com.example.data.local.entity.MarketDataEntity
import com.example.data.local.entity.PropertyCompEntity
import com.example.data.local.entity.PropertyEnrichmentEntity
import com.example.data.local.entity.PropertyEnrichmentType
import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.PropertyImageEntity
import com.example.data.local.entity.PropertyImportStatus
import com.example.data.local.entity.PropertyProvenanceEntity
import com.example.data.local.entity.PropertySourceDefaults
import com.example.data.local.entity.PropertySourceSyncStatus
import com.example.data.local.entity.RentEstimateEntity
import com.example.data.local.entity.SalesHistoryEntity
import com.example.data.local.entity.TaxRecordEntity
import com.example.data.repository.ImportOutcome
import com.example.data.repository.PropertyImportRepository
import com.example.data.repository.PropertyRepository
import com.example.domain.property.DedupStrategy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Write path tests for the deduplication architecture: the same house arriving from an MLS feed and
 * from a wholesaler must end up as one canonical property with two provenance rows, satellites must
 * never be duplicated, and a failing record must leave the database untouched (one transaction per
 * record).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PropertyImportRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var importer: PropertyImportRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importer = PropertyImportRepository(
            database = db,
            propertyDao = db.propertyDao(),
            sourceDao = db.propertySourceDao(),
            enrichmentDao = db.propertyEnrichmentDao(),
            financialDao = db.propertyFinancialDao(),
            analysisDao = db.financialDao()
        )
        runBlocking { importer.seedDefaultSources() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── single record import ────────────────────────────────────────────────────────────────────

    @Test
    fun `first import writes the canonical row its satellites and provenance`() = runBlocking {
        val result = importer.importBundle(bundle())

        assertEquals(ImportOutcome.INSERTED, result.outcome)
        assertEquals(PropertySourceDefaults.MLS_ID, result.property.primarySourceId)
        assertEquals("2418 s congress ave|austin|TX|78704", result.property.canonicalKey)
        assertEquals(1, db.propertyDao().getPropertiesCount())
        assertEquals(1, db.propertyDao().getImagesListForProperty(result.propertyId).size)
        assertNotNull(db.propertyDao().getMarketData(result.propertyId))
        assertNotNull(db.propertyDao().getRentEstimate(result.propertyId))
        assertNotNull(db.propertyDao().getTaxRecord(result.propertyId))
        assertEquals(1, db.propertyDao().getSalesHistoryListForProperty(result.propertyId).size)

        val provenance = db.propertySourceDao().getProvenanceForProperty(result.propertyId).single()
        assertEquals("mls-001", provenance.externalId)
        assertTrue(provenance.isPrimaryForProperty)
    }

    @Test
    fun `re-importing the same source record refreshes it instead of duplicating`() = runBlocking {
        val first = importer.importBundle(bundle(price = 485_000.0))
        val second = importer.importBundle(bundle(price = 465_000.0))

        assertEquals(ImportOutcome.UPDATED, second.outcome)
        assertEquals(DedupStrategy.EXACT_SOURCE_RECORD, second.strategy)
        assertEquals(first.propertyId, second.propertyId)
        assertEquals(1, db.propertyDao().getPropertiesCount())
        assertEquals(1, db.propertySourceDao().getProvenanceForProperty(first.propertyId).size)
        assertEquals(1, db.propertyDao().getImagesListForProperty(first.propertyId).size)
        assertEquals(465_000.0, db.propertyDao().getPropertyById(first.propertyId)!!.price, 0.001)
    }

    @Test
    fun `second source with the same address deduplicates by canonical key`() = runBlocking {
        val mls = importer.importBundle(bundle(sourceId = PropertySourceDefaults.MLS_ID, externalId = "mls-001"))
        val wholesale = importer.importBundle(
            bundle(
                id = "prop-ws-9",
                sourceId = PropertySourceDefaults.WHOLESALE_ID,
                externalId = "ws-9",
                price = 450_000.0
            )
        )

        assertEquals(ImportOutcome.UPDATED, wholesale.outcome)
        assertEquals(DedupStrategy.CANONICAL_KEY, wholesale.strategy)
        assertEquals(mls.propertyId, wholesale.propertyId)
        assertEquals(1, db.propertyDao().getPropertiesCount())
        assertEquals(2, db.propertySourceDao().getProvenanceForProperty(mls.propertyId).size)

        // MLS priority is 10, the wholesaler is 40: the MLS numbers survive the merge.
        val stored = db.propertyDao().getPropertyById(mls.propertyId)!!
        assertEquals(485_000.0, stored.price, 0.001)
        assertEquals(PropertySourceDefaults.MLS_ID, stored.primarySourceId)
    }

    @Test
    fun `different units in the same building stay separate properties`() = runBlocking {
        val unitA = importer.importBundle(bundle(id = "prop-a", externalId = "mls-a", unit = "Apt 1", apn = ""))
        val unitB = importer.importBundle(bundle(id = "prop-b", externalId = "mls-b", unit = "Apt 2", apn = ""))

        assertEquals(ImportOutcome.INSERTED, unitB.outcome)
        assertNotEquals(unitA.propertyId, unitB.propertyId)
        assertEquals(2, db.propertyDao().getPropertiesCount())
    }

    @Test
    fun `comps are deduplicated on canonical address and sale date`() = runBlocking {
        val result = importer.importBundle(
            bundle(
                comps = listOf(
                    comp(address = "2402 South Congress Avenue", saleDate = "2025-03-14"),
                    comp(address = "2402 S Congress Ave", saleDate = "2025-03-14", price = 472_000.0),
                    comp(address = "2402 S Congress Ave", saleDate = "2024-11-02", price = 455_000.0)
                )
            )
        )

        val stored = db.propertyDao().getCompsListForProperty(result.propertyId)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.compAddress == "2402 S Congress Ave" })
        assertEquals(472_000.0, stored.first { it.saleDate == "2025-03-14" }.compPrice, 0.001)
    }

    @Test
    fun `enrichments keep one current row per provider and refresh in place`() = runBlocking {
        val result = importer.importBundle(bundle(enrichments = listOf(enrichment(value = 4100.0))))
        importer.importBundle(bundle(enrichments = listOf(enrichment(value = 4300.0))))

        val rows = db.propertyEnrichmentDao().getForProperty(result.propertyId)
        assertEquals(1, rows.size)
        assertEquals(4300.0, rows.single().valueNumeric!!, 0.001)
        assertTrue(rows.single().isCurrent)
    }

    @Test
    fun `a failing satellite write rolls the whole record back`() = runBlocking {
        val broken = bundle(comps = listOf(comp().copy(sourceId = "src-that-does-not-exist")))

        var failure: Exception? = null
        try {
            importer.importBundle(broken)
        } catch (e: Exception) {
            failure = e
        }

        assertNotNull("the foreign key violation should abort the import", failure)
        assertNull(db.propertyDao().getPropertyById("prop-mls-001"))
        assertEquals(0, db.propertyDao().getPropertiesCount())
        assertTrue(db.propertySourceDao().getProvenanceForProperty("prop-mls-001").isEmpty())
    }

    // ── import runs ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `runImport aggregates counters and mirrors the source sync status`() = runBlocking {
        val summary = importer.runImport(
            sourceId = PropertySourceDefaults.MLS_ID,
            triggerKind = "TEST",
            fetch = {
                listOf(
                    bundle(id = "prop-1", externalId = "mls-1"),
                    bundle(id = "prop-2", externalId = "mls-2", address = "500 E 5th St", apn = "")
                )
            }
        )

        assertEquals(PropertyImportStatus.COMPLETED, summary.status)
        assertEquals(2, summary.fetched)
        assertEquals(2, summary.inserted)
        assertEquals(0, summary.deduplicated)
        assertEquals(0, summary.failed)

        val job = db.propertySourceDao().getJob(summary.jobId!!)!!
        assertEquals(2, job.recordsFetched)
        assertEquals(2, job.recordsInserted)
        assertEquals(PropertyImportStatus.COMPLETED, job.status)

        val source = db.propertySourceDao().getSourceById(PropertySourceDefaults.MLS_ID)!!
        assertEquals(PropertySourceSyncStatus.SUCCESS, source.lastSyncStatus)
    }

    @Test
    fun `a failing fetch marks the job failed without touching stored data`() = runBlocking {
        importer.importBundle(bundle())

        val summary = importer.runImport(
            sourceId = PropertySourceDefaults.MLS_ID,
            triggerKind = "TEST",
            fetch = { throw IllegalStateException("feed down") }
        )

        assertEquals(PropertyImportStatus.FAILED, summary.status)
        assertEquals(1, summary.failed)
        assertEquals(0, summary.fetched)
        assertEquals(1, db.propertyDao().getPropertiesCount())

        val source = db.propertySourceDao().getSourceById(PropertySourceDefaults.MLS_ID)!!
        assertEquals(PropertySourceSyncStatus.FAILED, source.lastSyncStatus)
    }

    @Test
    fun `data added through a source adapter is deduplicated on the way in`() = runBlocking {
        val adapter = FakeAdapter(
            listOf(
                bundle(id = "prop-1", externalId = "mls-1"),
                bundle(id = "prop-2", externalId = "mls-2", address = "500 E 5th St", apn = "")
            )
        )
        val repository = PropertyRepository(
            database = db,
            propertyDao = db.propertyDao(),
            sourceManager = PropertySourceManager(listOf(adapter)),
            importer = importer
        )

        val summaries = repository.syncFromSources()

        assertEquals(1, summaries.size)
        assertEquals(2, summaries.single().inserted)
        assertEquals(2, db.propertyDao().getPropertiesCount())
        assertNotNull(db.propertySourceDao().getJob(summaries.single().jobId!!))
    }

    // ── reconcile pass ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `reconcile claims canonical keys for legacy rows and merges duplicates`() = runBlocking {
        val base = bundle().property
        val legacyA = base.copy(
            id = "legacy-a",
            scannedAt = 1_000L,
            canonicalKey = null,
            normalizedAddress = "",
            unitNumber = ""
        )
        val legacyB = base.copy(
            id = "legacy-b",
            scannedAt = 2_000L,
            canonicalKey = null,
            normalizedAddress = "",
            unitNumber = ""
        )
        db.propertyDao().insertProperties(listOf(legacyA, legacyB))

        val summary = importer.reconcileCanonicalKeys()

        assertEquals(2, summary.scanned)
        assertEquals(1, summary.mergedDuplicates)
        assertEquals(1, db.propertyDao().getPropertiesCount())

        // The row that was seen first survives so existing references keep working.
        val survivor = db.propertyDao().getPropertyById("legacy-a")
        assertNotNull(survivor)
        assertNotNull(survivor!!.canonicalKey)
        assertNull(db.propertyDao().getPropertyById("legacy-b"))
    }

    @Test
    fun `merging a legacy duplicate moves the references that pointed at the loser`() = runBlocking {
        importer.seedDefaultSources()
        val base = bundle().property
        // legacy-a was entered first and survives; legacy-b is the same house entered later.
        val survivorRow = base.copy(
            id = "legacy-a",
            scannedAt = 1_000L,
            canonicalKey = null,
            normalizedAddress = "",
            unitNumber = ""
        )
        val loserRow = base.copy(
            id = "legacy-b",
            scannedAt = 2_000L,
            canonicalKey = null,
            normalizedAddress = "",
            unitNumber = "",
            isSaved = true
        )
        db.propertyDao().insertProperties(listOf(survivorRow, loserRow))
        for ((propertyId, externalId) in listOf("legacy-a" to "mls-a", "legacy-b" to "mls-b")) {
            db.propertySourceDao().insertProvenance(
                PropertyProvenanceEntity(
                    propertyId = propertyId,
                    sourceId = PropertySourceDefaults.MLS_ID,
                    externalId = externalId,
                    fetchedAt = 1L,
                    firstSeenAt = 1L,
                    lastSeenAt = 1L
                )
            )
        }
        // A saved property and an offer hang off the loser. Both must follow the survivor.
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO saved_properties (propertyId, savedAt, notes, tag) VALUES ('legacy-b', 5000, '', 'Watchlist')"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO offers (id, propertyId, recipientName, recipientEmail, offerPrice, earnestMoney, " +
                "inspectionPeriodDays, closingPeriodDays, contingencies, terms, conditions, expirationDate, " +
                "generatedLetterContent, status, createdAt) VALUES ('offer-1', 'legacy-b', 'Seller', " +
                "'seller@example.com', 500000.0, 5000.0, 10, 30, '', '', '', '2026-12-31', 'letter', 'DRAFT', 3000)"
        )

        val summary = importer.reconcileCanonicalKeys()

        assertEquals(1, summary.mergedDuplicates)
        assertEquals(1, db.propertyDao().getPropertiesCount())
        val survivor = db.propertyDao().getPropertyById("legacy-a")
        assertNotNull(survivor)
        assertNotNull("the survivor must take the canonical key the loser held", survivor!!.canonicalKey)
        assertTrue("saved state of the loser must survive the merge", survivor.isSaved)
        assertNull(db.propertyDao().getPropertyById("legacy-b"))

        assertEquals("legacy-a", scalarText("SELECT propertyId FROM offers WHERE id = 'offer-1'"))
        assertEquals("legacy-a", scalarText("SELECT propertyId FROM saved_properties"))

        val provenance = db.propertySourceDao().getProvenanceForProperty("legacy-a")
        assertEquals("both source records must follow the survivor", 2, provenance.size)
        assertEquals(
            "exactly one source record may be primary after the merge",
            1,
            provenance.count { it.isPrimaryForProperty }
        )
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private fun scalarText(sql: String): String? =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun bundle(
        id: String = "prop-mls-001",
        sourceId: String = PropertySourceDefaults.MLS_ID,
        externalId: String = "mls-001",
        address: String = "2418 S Congress Ave",
        unit: String = "",
        city: String = "Austin",
        state: String = "TX",
        zip: String = "78704",
        apn: String = "0412345678",
        price: Double = 485_000.0,
        comps: List<PropertyCompEntity> = emptyList(),
        enrichments: List<PropertyEnrichmentEntity> = emptyList()
    ) = NormalizedPropertyBundle(
        property = PropertyEntity(
            id = id,
            sourceType = "ON_MARKET",
            title = "South Congress Cash Flow Duplex",
            address = address,
            city = city,
            state = state,
            zipCode = zip,
            latitude = 30.2415,
            longitude = -97.7551,
            price = price,
            propertyType = "Multi-Family",
            bedrooms = 4,
            bathrooms = 3.0,
            squareFeet = 2250,
            yearBuilt = 2017,
            lotSizeSqFt = 6500,
            description = "Turnkey duplex",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = 1_700_000_000_000L,
            unitNumber = unit,
            apn = apn
        ),
        images = listOf(
            PropertyImageEntity(propertyId = id, imageUrl = "https://example.com/1.jpg", isPrimary = true)
        ),
        marketData = MarketDataEntity(
            propertyId = id,
            estimatedValue = 500_000.0,
            neighborhoodAppreciationRate = 3.2,
            medianAreaPrice = 450_000.0,
            averageDaysOnMarket = 18,
            pricePerSqFt = 210.0,
            marketDemand = "High"
        ),
        rentEstimate = RentEstimateEntity(
            propertyId = id,
            estimatedRent = 4200.0,
            rentRangeLow = 3900.0,
            rentRangeHigh = 4500.0,
            rentConfidenceScore = 88.0,
            grossYield = 10.4
        ),
        taxRecord = TaxRecordEntity(
            propertyId = id,
            annualTaxAmount = 6200.0,
            assessmentYear = 2025,
            assessedValue = 410_000.0
        ),
        salesHistory = listOf(
            SalesHistoryEntity(propertyId = id, date = "2021-05-01", price = 380_000.0, event = "SOLD")
        ),
        comps = comps,
        sourceId = sourceId,
        externalId = externalId,
        externalUrl = "https://feed.example.com/$externalId",
        payloadHash = "hash-$externalId",
        enrichments = enrichments
    )

    private fun comp(
        address: String = "2402 S Congress Ave",
        saleDate: String = "2025-03-14",
        price: Double = 470_000.0
    ) = PropertyCompEntity(
        targetPropertyId = "prop-mls-001",
        compAddress = address,
        compPrice = price,
        compBeds = 4,
        compBaths = 3.0,
        compSqFt = 2180,
        distanceMiles = 0.3,
        saleDate = saleDate
    )

    private fun enrichment(
        type: String = PropertyEnrichmentType.RENT_AVM,
        provider: String = "ATTOM",
        value: Double = 4100.0
    ) = PropertyEnrichmentEntity(
        propertyId = "prop-mls-001",
        enrichmentType = type,
        provider = provider,
        valueNumeric = value,
        unit = "USD/month",
        confidence = 90.0,
        ingestedAt = 1_700_000_000_000L
    )

    /** Minimal source adapter so the repository/manager/importer chain is exercised end to end. */
    private class FakeAdapter(
        private val bundles: List<NormalizedPropertyBundle>
    ) : PropertySourceAdapter {
        override val sourceName: String = "Fake MLS"
        override val sourceType: String = "ON_MARKET"
        override val sourceId: String = PropertySourceDefaults.MLS_ID

        override suspend fun fetchProperties(
            query: String?,
            minPrice: Double?,
            maxPrice: Double?,
            limit: Int
        ): List<NormalizedPropertyBundle> = bundles.take(limit)
    }
}
