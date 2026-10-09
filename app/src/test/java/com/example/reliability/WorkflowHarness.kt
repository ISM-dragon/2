package com.example.reliability

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.adapter.NormalizedPropertyBundle
import com.example.data.adapter.PropertySourceAdapter
import com.example.data.adapter.PropertySourceManager
import com.example.data.adapter.PropertyUrlImportBridge
import com.example.data.local.AppDatabase
import com.example.data.local.entity.MarketDataEntity
import com.example.data.local.entity.OfferEntity
import com.example.data.local.entity.PropertyCompEntity
import com.example.data.local.entity.PropertyEnrichmentEntity
import com.example.data.local.entity.PropertyEntity
import com.example.data.local.entity.PropertyImageEntity
import com.example.data.local.entity.RentEstimateEntity
import com.example.data.local.entity.TaxRecordEntity
import com.example.data.local.migration.DatabaseMigrations
import com.example.data.repository.ConfigRepository
import com.example.data.repository.FinancialRepository
import com.example.data.repository.IntelligenceRepository
import com.example.data.repository.OfferRepository
import com.example.data.repository.PropertyImportRepository
import com.example.data.repository.PropertyRepository
import com.example.domain.ai.GeminiManager
import com.example.domain.gmail.GmailSendResult
import com.example.domain.gmail.GmailSender
import com.example.domain.propertyurl.FakeHttpFetcher
import com.example.domain.propertyurl.Fixtures
import com.example.domain.propertyurl.FixedClock
import com.example.domain.propertyurl.RecordingSleeper
import com.example.domain.propertyurl.job.RetryPolicy
import com.example.domain.propertyurl.pipeline.PropertyImportOptions
import com.example.domain.propertyurl.pipeline.PropertyUrlIntelligence
import com.example.domain.propertyurl.pipeline.PropertyUrlIntelligenceFactory
import com.example.domain.propertyurl.store.FilePropertyImportJobStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared fixture for the property-to-offer reliability suite.
 *
 * It builds the object graph exactly the way `RealEstateAiApp.onCreate` builds the production one -
 * the same Room database, the same repositories, the same URL-intelligence pipeline - so a test
 * failure here means a boundary really disagreed, not that a stub was configured wrong.
 *
 * Deliberate limits, restated in `docs/e2e-reliability-audit.md`:
 *  - the portal HTTP transport, the Gmail delivery call and the Gemini endpoint are faked. These
 *    fakes exist to inject failures deterministically; they are NOT evidence about a live provider.
 *  - everything the app itself owns (normalization, dedup, provenance, satellites, underwriting,
 *    qualification, the deal room read model, the offer ledger and its audit trail) is the real
 *    implementation, backed by a real SQLite database in a file, so a "process death" is a real
 *    close-and-reopen of that file.
 */
class WorkflowHarness(
    private val dbName: String = "reliability-${UUID.randomUUID()}.db"
) {
    val context: Context = ApplicationProvider.getApplicationContext<Context>()

    /** Clock handed to the repositories: every persisted timestamp is a test value. */
    val clock = AtomicLong(NOW)

    /** Canned portal transport shared by every "process" of this harness. */
    val fetcher = FakeHttpFetcher()

    /** Canned Gmail delivery, scripted per test. */
    val gmail = ScriptedGmailSender()

    /** Clock of the URL-intelligence layer (kept separate so retry backoff is explicit). */
    val pipelineClock = FixedClock(NOW)

    /** Records retry waits instead of sleeping. */
    val sleeper = RecordingSleeper()

    /**
     * Total time budget of one pipeline import call. A test shrinks this to make the layer schedule a
     * retry instead of finishing inside the call, which is how an interrupted job is produced.
     */
    var maxTotalImportMillis: Long = 90_000

    private val jobStoreRoot = File(context.filesDir, "reliability-jobs/$dbName")

    private val pdfFiles = mutableListOf<File>()

    lateinit var database: AppDatabase
        private set
    lateinit var importer: PropertyImportRepository
        private set
    lateinit var properties: PropertyRepository
        private set
    lateinit var financials: FinancialRepository
        private set
    lateinit var configRepository: ConfigRepository
        private set
    lateinit var jobStore: FilePropertyImportJobStore
        private set
    lateinit var pipeline: PropertyUrlIntelligence
        private set
    lateinit var bridge: PropertyUrlImportBridge
        private set
    lateinit var intelligenceRepository: IntelligenceRepository
        private set
    lateinit var offers: OfferRepository
        private set

    init {
        start()
    }

    /**
     * Builds every component of the workflow. Called again by [restart], which is what makes the
     * "process death" scenarios honest: a fresh `AppDatabase`, a fresh pipeline and a fresh
     * `FilePropertyImportJobStore` (its in-memory cache is dropped), all reading the same files.
     */
    fun start() {
        database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()

        importer = PropertyImportRepository(
            database = database,
            propertyDao = database.propertyDao(),
            sourceDao = database.propertySourceDao(),
            enrichmentDao = database.propertyEnrichmentDao(),
            financialDao = database.propertyFinancialDao(),
            analysisDao = database.financialDao(),
            clock = { clock.get() }
        )
        properties = PropertyRepository(
            database = database,
            propertyDao = database.propertyDao(),
            sourceManager = PropertySourceManager(emptyList()),
            importer = importer
        )
        financials = FinancialRepository(
            database.financialDao(),
            database.propertyDao(),
            database.automationDao()
        )
        configRepository = ConfigRepository(database.configDao())

        // A new store instance per start(): the file directory is the only state that survives.
        jobStore = FilePropertyImportJobStore(jobStoreRoot)
        pipeline = PropertyUrlIntelligenceFactory.create(
            jobStore = jobStore,
            httpFetcher = fetcher,
            clock = pipelineClock,
            sleeper = sleeper,
            options = PropertyImportOptions(
                maxFetchAttempts = MAX_FETCH_ATTEMPTS,
                maxTotalImportMillis = maxTotalImportMillis
            ),
            retryPolicy = RetryPolicy(baseDelayMillis = 1_000, jitterRatio = 0.0),
            rateLimiter = null,
            healthTracker = null
        )
        bridge = PropertyUrlImportBridge(pipeline, properties)
        intelligenceRepository = IntelligenceRepository(
            propertyUrlImporter = bridge,
            propertyDao = database.propertyDao(),
            sourceDao = database.propertySourceDao(),
            enrichmentDao = database.propertyEnrichmentDao(),
            financialDao = database.propertyFinancialDao(),
            intelligenceDao = database.intelligenceDao()
        )
        offers = OfferRepository(
            offerDao = database.offerDao(),
            propertyDao = database.propertyDao(),
            configRepository = configRepository,
            geminiManager = GeminiManager(database.configDao()),
            gmailService = gmail,
            clock = { clock.get() }
        )
    }

    /** Drops every in-memory object and rebuilds the graph from the same files. */
    fun restart() {
        database.close()
        start()
    }

    fun dispose() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(dbName)
        jobStoreRoot.deleteRecursively()
        pdfFiles.forEach { it.delete() }
    }

    /** Registers the default source rows the pipeline resolves `sourceId`s against. */
    fun seedSources() {
        kotlinx.coroutines.runBlocking { importer.seedDefaultSources() }
    }

    /** Serves the bundled Zillow listing fixture for [ZILLOW_URL]. */
    fun stubZillowListing(failuresBeforeSuccess: Int = 0, status: Int = 200) {
        fetcher.on(
            ZILLOW_ROUTE,
            status = status,
            body = Fixtures.text(ZILLOW_FIXTURE),
            failuresBeforeSuccess = failuresBeforeSuccess
        )
    }

    /** A file in the approved offers directory that passes the PDF pre-send gate. */
    fun writeOfferPdf(name: String): File {
        val directory = File(context.filesDir, "offers").apply { mkdirs() }
        val file = File(directory, "$name-${System.nanoTime()}.pdf").apply {
            writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x0a))
        }
        pdfFiles += file
        return file
    }

    fun draftOffer(
        propertyId: String,
        offerId: String,
        pdfFile: File,
        status: String = "READY",
        recipientEmail: String = "agent@example.com"
    ): OfferEntity = OfferEntity(
        id = offerId,
        propertyId = propertyId,
        recipientName = "Listing Agent",
        recipientEmail = recipientEmail,
        offerPrice = 520_000.0,
        earnestMoney = 7_800.0,
        inspectionPeriodDays = 10,
        closingPeriodDays = 21,
        contingencies = "Inspection",
        terms = "AS-IS",
        conditions = "Clear title",
        expirationDate = "Nov 30, 2026",
        generatedLetterContent = "Formal letter of intent for the property.",
        pdfPath = pdfFile.absolutePath,
        status = status,
        createdAt = clock.get()
    )

    /**
     * In-test feed adapter: the seam a real MLS/IDX/wholesale provider would occupy. It yields
     * whatever bundles a test hands it, so `runImport` (job rows, counters, dedup, provenance,
     * satellites) is exercised against the real repository.
     */
    class ScriptedFeedAdapter(
        override val sourceName: String,
        override val sourceId: String,
        override val sourceType: String = "ON_MARKET",
        private val bundles: MutableList<NormalizedPropertyBundle> = mutableListOf()
    ) : PropertySourceAdapter {
        val calls = AtomicInteger(0)

        fun add(bundle: NormalizedPropertyBundle) {
            bundles += bundle
        }

        override suspend fun fetchProperties(
            query: String?,
            minPrice: Double?,
            maxPrice: Double?,
            limit: Int
        ): List<NormalizedPropertyBundle> {
            calls.incrementAndGet()
            return bundles.take(limit)
        }
    }

    /** A source adapter whose provider call fails outright (a provider outage). */
    class OutagedFeedAdapter(
        override val sourceName: String,
        override val sourceId: String,
        override val sourceType: String = "OFF_MARKET",
        private val error: Exception = IllegalStateException("provider unavailable")
    ) : PropertySourceAdapter {
        val calls = AtomicInteger(0)

        override suspend fun fetchProperties(
            query: String?,
            minPrice: Double?,
            maxPrice: Double?,
            limit: Int
        ): List<NormalizedPropertyBundle> {
            calls.incrementAndGet()
            throw error
        }
    }

    /**
     * Gmail stand-in. Results are scripted so a test can express "the provider answered 401",
     * "the call never returned" or "the call succeeded once and must not be repeated".
     */
    class ScriptedGmailSender : GmailSender {
        val callCount = AtomicInteger(0)
        val keys = mutableListOf<String?>()
        private val results = ConcurrentLinkedQueue<GmailSendResult?>()

        fun script(vararg result: GmailSendResult?) {
            results.addAll(result.toList())
        }

        /** Idempotency keys the provider actually received, in call order. */
        fun keysSnapshot(): List<String?> = synchronized(keys) { keys.toList() }

        /** One extra scripted result per call; exhausted scripts fail the test loudly. */
        override suspend fun sendOfferEmail(
            context: Context,
            recipientEmail: String,
            recipientName: String,
            subject: String,
            htmlBody: String,
            pdfFile: File?,
            idempotencyKey: String?
        ): GmailSendResult {
            callCount.incrementAndGet()
            synchronized(keys) { keys.add(idempotencyKey) }
            return results.poll() ?: throw AssertionError("Unexpected Gmail call (idempotency key: $idempotencyKey)")
        }
    }

    companion object {
        const val NOW = 1_760_000_000_000L
        const val ZILLOW_URL =
            "https://www.zillow.com/homedetails/4127-Oak-Hollow-Dr-Austin-TX-78745/20451237_zpid/"
        const val ZILLOW_ROUTE = "zillow.com/homedetails"
        const val ZILLOW_FIXTURE = "zillow-homedetails.html"
        const val MAX_FETCH_ATTEMPTS = 3

        fun listingRow(
            id: String,
            address: String = "4127 Oak Hollow Dr",
            city: String = "Austin",
            state: String = "TX",
            zipCode: String = "78745",
            price: Double = 565_000.0,
            sourceType: String = "ON_MARKET",
            apn: String = "",
            mlsNumber: String = "",
            scannedAt: Long = NOW
        ) = PropertyEntity(
            id = id,
            sourceType = sourceType,
            title = "Test listing $id",
            address = address,
            city = city,
            state = state,
            zipCode = zipCode,
            latitude = 30.2415,
            longitude = -97.7551,
            price = price,
            propertyType = "Single Family",
            bedrooms = 3,
            bathrooms = 2.0,
            squareFeet = 1_842,
            yearBuilt = 1988,
            lotSizeSqFt = 7_200,
            description = "Three bedroom single family home.",
            status = "Active",
            primaryImageUrl = "",
            scannedAt = scannedAt,
            apn = apn,
            mlsNumber = mlsNumber
        )

        /** Bundle with no valuation satellites: the same shape a portal import produces. */
        fun bareBundle(property: PropertyEntity) = NormalizedPropertyBundle(
            property = property,
            images = listOf(
                PropertyImageEntity(propertyId = property.id, imageUrl = "https://img.example/1.jpg", isPrimary = true)
            ),
            marketData = MarketDataEntity(
                propertyId = property.id,
                estimatedValue = 0.0,
                neighborhoodAppreciationRate = 0.0,
                medianAreaPrice = 0.0,
                averageDaysOnMarket = 0,
                pricePerSqFt = 0.0,
                marketDemand = "Unknown"
            ),
            rentEstimate = RentEstimateEntity(
                propertyId = property.id,
                estimatedRent = 0.0,
                rentRangeLow = 0.0,
                rentRangeHigh = 0.0,
                rentConfidenceScore = 0.0,
                grossYield = 0.0
            ),
            taxRecord = TaxRecordEntity(
                propertyId = property.id,
                annualTaxAmount = 0.0,
                assessmentYear = 2026,
                assessedValue = 0.0,
                taxDelinquent = false
            ),
            salesHistory = emptyList(),
            comps = emptyList()
        )

        fun comp(
            propertyId: String,
            address: String,
            price: Double = 540_000.0,
            sqft: Int = 1_800,
            saleDate: String = "2026-06-01"
        ) = PropertyCompEntity(
            targetPropertyId = propertyId,
            compAddress = address,
            compPrice = price,
            compBeds = 3,
            compBaths = 2.0,
            compSqFt = sqft,
            distanceMiles = 0.4,
            saleDate = saleDate,
            similarityScore = 88.0
        )

        fun enrichment(
            propertyId: String,
            type: String,
            provider: String,
            valueNumeric: Double? = null,
            valueText: String = "",
            confidence: Double = 90.0
        ) = PropertyEnrichmentEntity(
            propertyId = propertyId,
            enrichmentType = type,
            provider = provider,
            valueNumeric = valueNumeric,
            valueText = valueText,
            confidence = confidence,
            effectiveAt = NOW,
            isCurrent = true,
            ingestedAt = NOW
        )
    }
}
