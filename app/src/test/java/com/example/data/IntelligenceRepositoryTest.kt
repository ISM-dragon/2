package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.adapter.PropertyUrlImportBridge
import com.example.data.local.AppDatabase
import com.example.data.local.entity.PropertyAiAnalysisEntity
import com.example.data.local.entity.PropertyCompEntity
import com.example.data.local.entity.PropertyEnrichmentEntity
import com.example.data.local.entity.PropertyEnrichmentType
import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.PropertyFinancialDataSource
import com.example.data.local.entity.PropertyFinancialEntity
import com.example.data.local.entity.PropertyImageEntity
import com.example.data.local.entity.PropertyIngestionMethod
import com.example.data.local.entity.PropertyProvenanceEntity
import com.example.data.local.entity.PropertySourceEntity
import com.example.data.local.entity.PropertySourceKind
import com.example.data.local.entity.PropertySourceLinkEntity
import com.example.data.local.entity.SourceHealthEntity
import com.example.data.repository.IntelligenceRepository
import com.example.domain.intelligence.job.JobStatus
import com.example.domain.propertyurl.FakeHttpFetcher
import com.example.domain.propertyurl.job.InMemoryPropertyImportJobStore
import com.example.domain.propertyurl.pipeline.PropertyUrlIntelligenceFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Read path tests for [IntelligenceRepository]: every flow must mirror the persisted tables —
 * populated rows come back as stored, missing rows come back as empty/null, and a partially
 * populated property never has its gaps filled with placeholders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntelligenceRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: IntelligenceRepository
    private lateinit var fetcher: FakeHttpFetcher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fetcher = FakeHttpFetcher()
        val bridge = PropertyUrlImportBridge(
            PropertyUrlIntelligenceFactory.create(
                jobStore = InMemoryPropertyImportJobStore(),
                httpFetcher = fetcher,
                rateLimiter = null,
                healthTracker = null
            )
        )
        repository = IntelligenceRepository(
            propertyUrlImporter = bridge,
            propertyDao = db.propertyDao(),
            sourceDao = db.propertySourceDao(),
            enrichmentDao = db.propertyEnrichmentDao(),
            financialDao = db.propertyFinancialDao(),
            intelligenceDao = db.intelligenceDao()
        )
        runBlocking { db.propertySourceDao().upsertSource(source()) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── populated ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `populated property exposes every persisted satellite through the flows`() = runBlocking {
        persistProperty(PROPERTY_ID)
        db.intelligenceDao().insertSourceLink(sourceLink(PROPERTY_ID, "https://www.zillow.com/homedetails/1", "Zillow"))
        db.propertySourceDao().insertProvenance(provenance(PROPERTY_ID, externalId = "mls-1", primary = true))
        db.propertyDao().insertComps(listOf(comp(PROPERTY_ID, "100 Main St", similarity = 91.0)))
        db.propertyEnrichmentDao().upsert(enrichment(PROPERTY_ID, PropertyEnrichmentType.FLOOD_RISK, "FEMA", valueText = "Zone AE", confidence = 95.0))
        db.propertyFinancialDao().upsert(financials(PROPERTY_ID, monthlyRent = 2_350.0))
        db.intelligenceDao().insertAiAnalysis(aiAnalysis(PROPERTY_ID, summary = "Solid cash flow duplex"))

        val sources = repository.getSourcesForProperty(PROPERTY_ID).first()
        assertEquals(1, sources.size)
        assertEquals("https://www.zillow.com/homedetails/1", sources.single().sourceUrl)
        assertEquals("Zillow", sources.single().source)

        val provenance = repository.getProvenanceForProperty(PROPERTY_ID).first()
        assertEquals(listOf("mls-1"), provenance.map { it.externalId })

        val comps = repository.getCompsForProperty(PROPERTY_ID).first()
        assertEquals(listOf("100 Main St"), comps.map { it.compAddress })

        val enrichment = repository.getEnrichmentFlow(PROPERTY_ID).first()
        assertNotNull(enrichment)
        assertEquals("Zone AE", enrichment!!.valueText)
        assertEquals(PropertyEnrichmentType.FLOOD_RISK, enrichment.enrichmentType)

        val financials = repository.getFinancialsFlow(PROPERTY_ID).first()
        assertNotNull(financials)
        assertEquals(2_350.0, financials!!.monthlyRentEstimate, 0.001)
        assertEquals(PropertyFinancialDataSource.RECORDS, financials.dataSource)

        val ai = repository.getAiAnalysisFlow(PROPERTY_ID).first()
        assertNotNull(ai)
        assertEquals("Solid cash flow duplex", ai!!.summary)
        assertEquals(0.82, ai.confidence, 0.0001)
    }

    @Test
    fun `complete deal room snapshot mirrors the persisted tables`() = runBlocking {
        persistProperty(PROPERTY_ID)
        db.propertyDao().insertImages(listOf(PropertyImageEntity(propertyId = PROPERTY_ID, imageUrl = "https://img/1.jpg", isPrimary = true)))
        db.intelligenceDao().insertSourceLink(sourceLink(PROPERTY_ID, "https://www.redfin.com/home/1", "Redfin"))
        db.propertySourceDao().insertProvenance(provenance(PROPERTY_ID, externalId = "mls-7", primary = true))
        db.propertyDao().insertComps(listOf(comp(PROPERTY_ID, "200 Oak St", similarity = 80.0), comp(PROPERTY_ID, "300 Elm St", similarity = 70.0)))
        db.propertyEnrichmentDao().upsert(enrichment(PROPERTY_ID, PropertyEnrichmentType.WALK_SCORE, "WalkScore", valueNumeric = 72.0, confidence = 60.0))
        db.propertyFinancialDao().upsert(financials(PROPERTY_ID, monthlyRent = 1_900.0))
        db.intelligenceDao().insertAiAnalysis(aiAnalysis(PROPERTY_ID, summary = "Needs roof work"))

        val snapshot = requireNotNull(repository.getCompleteDealRoom(PROPERTY_ID))

        assertEquals(PROPERTY_ID, snapshot.property.id)
        assertEquals(1, snapshot.images.size)
        assertEquals("https://www.redfin.com/home/1", snapshot.sources.single().sourceUrl)
        assertEquals("mls-7", snapshot.provenance.single().externalId)
        assertEquals(setOf("200 Oak St", "300 Elm St"), snapshot.comps.map { it.compAddress }.toSet())
        assertEquals(72.0, snapshot.enrichment!!.valueNumeric!!, 0.001)
        assertEquals(1, snapshot.enrichments.size)
        assertEquals(1_900.0, snapshot.financials!!.monthlyRentEstimate, 0.001)
        assertEquals("Needs roof work", snapshot.aiAnalysis!!.summary)
    }

    @Test
    fun `source links are ordered primary first then most recently synced`() = runBlocking {
        persistProperty(PROPERTY_ID)
        db.intelligenceDao().insertSourceLink(sourceLink(PROPERTY_ID, "https://a.example/1", "A", primary = false, syncedAt = 3_000L))
        db.intelligenceDao().insertSourceLink(sourceLink(PROPERTY_ID, "https://b.example/1", "B", primary = true, syncedAt = 1_000L))
        db.intelligenceDao().insertSourceLink(sourceLink(PROPERTY_ID, "https://c.example/1", "C", primary = false, syncedAt = 5_000L))

        val ordered = repository.getSourcesForProperty(PROPERTY_ID).first().map { it.source }

        assertEquals(listOf("B", "C", "A"), ordered)
    }

    @Test
    fun `source links of another property are not leaked`() = runBlocking {
        persistProperty(PROPERTY_ID)
        persistProperty(OTHER_PROPERTY_ID)
        db.intelligenceDao().insertSourceLink(sourceLink(OTHER_PROPERTY_ID, "https://other.example/1", "Other"))

        assertTrue(repository.getSourcesForProperty(PROPERTY_ID).first().isEmpty())
        assertEquals(1, repository.getSourcesForProperty(OTHER_PROPERTY_ID).first().size)
    }

    @Test
    fun `enrichment flow prefers the current highest confidence row and hides superseded history`() = runBlocking {
        persistProperty(PROPERTY_ID)
        db.propertyEnrichmentDao().upsert(enrichment(PROPERTY_ID, PropertyEnrichmentType.FLOOD_RISK, "FEMA", valueText = "Zone X", confidence = 50.0))
        db.propertyEnrichmentDao().upsert(enrichment(PROPERTY_ID, PropertyEnrichmentType.SCHOOL_RATING, "GreatSchools", valueNumeric = 8.0, confidence = 90.0))
        db.propertyEnrichmentDao().upsert(
            enrichment(PROPERTY_ID, PropertyEnrichmentType.VALUATION_AVM, "StaleAvm", valueNumeric = 999_999.0, confidence = 100.0, isCurrent = false)
        )

        val selected = repository.getEnrichmentFlow(PROPERTY_ID).first()
        val all = repository.getEnrichmentsForProperty(PROPERTY_ID).first()

        assertEquals("GreatSchools", selected!!.provider)
        assertEquals(3, all.size)
        assertEquals(1, all.count { !it.isCurrent })
    }

    @Test
    fun `only superseded enrichment rows yield no current enrichment`() = runBlocking {
        persistProperty(PROPERTY_ID)
        db.propertyEnrichmentDao().upsert(
            enrichment(PROPERTY_ID, PropertyEnrichmentType.RENT_AVM, "OldProvider", valueNumeric = 1_500.0, confidence = 80.0, isCurrent = false)
        )

        assertNull(repository.getEnrichmentFlow(PROPERTY_ID).first())
        assertEquals(1, repository.getEnrichmentsForProperty(PROPERTY_ID).first().size)
        assertNull(repository.getCompleteDealRoom(PROPERTY_ID)!!.enrichment)
    }

    @Test
    fun `source health reflects every persisted source row`() = runBlocking {
        db.intelligenceDao().insertOrUpdateSourceHealth(sourceHealth("zillow", successCount = 9, failureCount = 1, status = "HEALTHY"))
        db.intelligenceDao().insertOrUpdateSourceHealth(sourceHealth("homes", successCount = 0, failureCount = 4, status = "DEGRADED", lastError = "HTTP 403"))

        val health = repository.getSourceHealthFlow().first()

        assertEquals(listOf("homes", "zillow"), health.map { it.source })
        assertEquals("HTTP 403", health.first().lastErrorReason)
        assertEquals(9, health.last().successCount)
        assertEquals("HEALTHY", repository.getSourceHealth("zillow")!!.healthStatus)
        assertNull(repository.getSourceHealth("never-seen"))
    }

    @Test
    fun `flows observe rows written after subscription`() = runBlocking {
        persistProperty(PROPERTY_ID)
        assertNull(repository.getAiAnalysisFlow(PROPERTY_ID).first())
        assertTrue(repository.getSourcesForProperty(PROPERTY_ID).first().isEmpty())

        db.intelligenceDao().insertAiAnalysis(aiAnalysis(PROPERTY_ID, summary = "Late analysis"))
        db.intelligenceDao().insertSourceLink(sourceLink(PROPERTY_ID, "https://late.example/1", "Late"))

        assertEquals("Late analysis", repository.getAiAnalysisFlow(PROPERTY_ID).first()!!.summary)
        assertEquals("Late", repository.getSourcesForProperty(PROPERTY_ID).first().single().source)
    }

    // ── empty ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `property without intelligence emits empty collections and nulls`() = runBlocking {
        persistProperty(PROPERTY_ID)

        assertTrue(repository.getSourcesForProperty(PROPERTY_ID).first().isEmpty())
        assertTrue(repository.getProvenanceForProperty(PROPERTY_ID).first().isEmpty())
        assertTrue(repository.getCompsForProperty(PROPERTY_ID).first().isEmpty())
        assertTrue(repository.getEnrichmentsForProperty(PROPERTY_ID).first().isEmpty())
        assertNull(repository.getEnrichmentFlow(PROPERTY_ID).first())
        assertNull(repository.getFinancialsFlow(PROPERTY_ID).first())
        assertNull(repository.getAiAnalysisFlow(PROPERTY_ID).first())
        assertTrue(repository.getSourceHealthFlow().first().isEmpty())
    }

    @Test
    fun `complete deal room of an empty property has no fabricated satellites`() = runBlocking {
        persistProperty(PROPERTY_ID)

        val snapshot = requireNotNull(repository.getCompleteDealRoom(PROPERTY_ID))

        assertEquals(PROPERTY_ID, snapshot.property.id)
        assertTrue(snapshot.images.isEmpty())
        assertTrue(snapshot.sources.isEmpty())
        assertTrue(snapshot.provenance.isEmpty())
        assertTrue(snapshot.comps.isEmpty())
        assertTrue(snapshot.enrichments.isEmpty())
        assertNull(snapshot.enrichment)
        assertNull(snapshot.financials)
        assertNull(snapshot.aiAnalysis)
    }

    @Test
    fun `unknown property yields null snapshot and empty flows`() = runBlocking {
        assertNull(repository.getCompleteDealRoom("does-not-exist"))
        assertTrue(repository.getSourcesForProperty("does-not-exist").first().isEmpty())
        assertNull(repository.getFinancialsFlow("does-not-exist").first())
        assertNull(repository.getAiAnalysisFlow("does-not-exist").first())
    }

    // ── partial ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `partially enriched property reports exactly what exists`() = runBlocking {
        persistProperty(PROPERTY_ID)
        db.propertySourceDao().insertProvenance(provenance(PROPERTY_ID, externalId = "county-42", primary = true))
        db.propertyFinancialDao().upsert(financials(PROPERTY_ID, monthlyRent = 0.0, annualTax = 6_200.0))

        // Present
        assertEquals("county-42", repository.getProvenanceForProperty(PROPERTY_ID).first().single().externalId)
        val financials = repository.getFinancialsFlow(PROPERTY_ID).first()
        assertEquals(6_200.0, financials!!.annualPropertyTax, 0.001)
        assertEquals("a zero stays a zero – no rent is estimated on the read path", 0.0, financials.monthlyRentEstimate, 0.0)

        // Absent
        assertTrue(repository.getSourcesForProperty(PROPERTY_ID).first().isEmpty())
        assertTrue(repository.getCompsForProperty(PROPERTY_ID).first().isEmpty())
        assertNull(repository.getEnrichmentFlow(PROPERTY_ID).first())
        assertNull(repository.getAiAnalysisFlow(PROPERTY_ID).first())

        val snapshot = repository.getCompleteDealRoom(PROPERTY_ID)!!
        assertEquals(1, snapshot.provenance.size)
        assertNotNull(snapshot.financials)
        assertTrue(snapshot.sources.isEmpty())
        assertTrue(snapshot.comps.isEmpty())
        assertNull(snapshot.enrichment)
        assertNull(snapshot.aiAnalysis)
    }

    @Test
    fun `comps and ai analysis without financials leave financials null`() = runBlocking {
        persistProperty(PROPERTY_ID)
        db.propertyDao().insertComps(listOf(comp(PROPERTY_ID, "400 Pine St", similarity = 55.0)))
        db.intelligenceDao().insertAiAnalysis(aiAnalysis(PROPERTY_ID, summary = "Thin comps"))

        assertEquals(1, repository.getCompsForProperty(PROPERTY_ID).first().size)
        assertEquals("Thin comps", repository.getAiAnalysisFlow(PROPERTY_ID).first()!!.summary)
        assertNull(repository.getFinancialsFlow(PROPERTY_ID).first())
        assertNull(repository.getEnrichmentFlow(PROPERTY_ID).first())
        assertTrue(repository.getSourcesForProperty(PROPERTY_ID).first().isEmpty())
    }

    // ── import entry point ──────────────────────────────────────────────────────────────────────

    @Test
    fun `rejected import reports failure and persists nothing`() = runBlocking {
        val state = repository.importPropertyUrl("call me about the Oak Hollow house")

        assertEquals(JobStatus.FAILED, state.status)
        assertNull(state.propertyId)
        assertNotNull(state.errorMessage)
        assertEquals(state, repository.currentJobProgress.value)
        assertEquals("nothing may be fetched", 0, fetcher.totalHits)
        assertEquals(0, db.propertyDao().getPropertiesCount())

        repository.clearJob()
        assertNull(repository.currentJobProgress.value)
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private suspend fun persistProperty(id: String) {
        db.propertyDao().insertProperty(
            PropertyEntity(
                id = id,
                sourceType = "ON_MARKET",
                title = "Test property $id",
                address = "2418 S Congress Ave",
                city = "Austin",
                state = "TX",
                zipCode = "78704",
                latitude = 30.2415,
                longitude = -97.7551,
                price = 485_000.0,
                propertyType = "Multi-Family",
                bedrooms = 4,
                bathrooms = 3.0,
                squareFeet = 2_250,
                yearBuilt = 2017,
                lotSizeSqFt = 6_500,
                description = "",
                status = "Active",
                primaryImageUrl = "",
                scannedAt = NOW,
                primarySourceId = SOURCE_ID
            )
        )
    }

    private fun source() = PropertySourceEntity(
        id = SOURCE_ID,
        name = "Test MLS",
        sourceKind = PropertySourceKind.MLS,
        adapterKey = "mls.test.v1",
        priority = 10,
        createdAt = NOW,
        updatedAt = NOW
    )

    private fun sourceLink(
        propertyId: String,
        url: String,
        source: String,
        primary: Boolean = true,
        syncedAt: Long = NOW
    ) = PropertySourceLinkEntity(
        propertyId = propertyId,
        source = source,
        sourceUrl = url,
        listingId = null,
        isPrimary = primary,
        lastSyncedAt = syncedAt
    )

    private fun provenance(propertyId: String, externalId: String, primary: Boolean) = PropertyProvenanceEntity(
        propertyId = propertyId,
        sourceId = SOURCE_ID,
        externalId = externalId,
        externalUrl = "https://mls.example/$externalId",
        ingestionMethod = PropertyIngestionMethod.API,
        confidence = 0.9,
        isPrimaryForProperty = primary,
        fetchedAt = NOW,
        firstSeenAt = NOW,
        lastSeenAt = NOW
    )

    private fun comp(targetPropertyId: String, address: String, similarity: Double) = PropertyCompEntity(
        targetPropertyId = targetPropertyId,
        compAddress = address,
        compPrice = 450_000.0,
        compBeds = 4,
        compBaths = 3.0,
        compSqFt = 2_100,
        distanceMiles = 0.4,
        saleDate = "2024-06-01",
        similarityScore = similarity
    )

    private fun enrichment(
        propertyId: String,
        type: String,
        provider: String,
        valueNumeric: Double? = null,
        valueText: String = "",
        confidence: Double,
        isCurrent: Boolean = true
    ) = PropertyEnrichmentEntity(
        propertyId = propertyId,
        enrichmentType = type,
        provider = provider,
        valueNumeric = valueNumeric,
        valueText = valueText,
        confidence = confidence,
        effectiveAt = NOW,
        isCurrent = isCurrent,
        ingestedAt = NOW
    )

    private fun financials(propertyId: String, monthlyRent: Double, annualTax: Double = 5_400.0) = PropertyFinancialEntity(
        propertyId = propertyId,
        assessedValue = 410_000.0,
        assessmentYear = 2024,
        annualPropertyTax = annualTax,
        monthlyRentEstimate = monthlyRent,
        dataSource = PropertyFinancialDataSource.RECORDS,
        lastUpdatedAt = NOW
    )

    private fun aiAnalysis(propertyId: String, summary: String) = PropertyAiAnalysisEntity(
        propertyId = propertyId,
        summary = summary,
        investmentThesis = "Hold for cash flow",
        strengthsJson = "[\"location\"]",
        weaknessesJson = "[]",
        risksJson = "[]",
        redFlagsJson = "[]",
        recommendedStrategy = "BUY_AND_HOLD",
        recommendedOfferRange = "440000-460000",
        questionsForSellerJson = "[]",
        dueDiligenceJson = "[]",
        confidence = 0.82,
        evidenceJson = "[]",
        analyzedAt = NOW
    )

    private fun sourceHealth(
        source: String,
        successCount: Int,
        failureCount: Int,
        status: String,
        lastError: String? = null
    ): SourceHealthEntity {
        val total = (successCount + failureCount).coerceAtLeast(1)
        return SourceHealthEntity(
            source = source,
            successCount = successCount,
            failureCount = failureCount,
            successRate = successCount.toDouble() / total,
            failureRate = failureCount.toDouble() / total,
            averageLatencyMs = 420L,
            lastSuccessAt = if (successCount > 0) NOW else null,
            lastFailureAt = if (failureCount > 0) NOW else null,
            parserVersion = "1",
            healthStatus = status,
            lastErrorReason = lastError
        )
    }

    private companion object {
        const val PROPERTY_ID = "cp-test-property"
        const val OTHER_PROPERTY_ID = "cp-other-property"
        const val SOURCE_ID = "src-test-mls"
        const val NOW = 1_700_000_000_000L
    }
}
