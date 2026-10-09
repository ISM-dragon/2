package com.example.reliability

import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferEmailSendStatus
import com.example.data.local.entity.PropertyEnrichmentType
import com.example.data.local.entity.PropertyFinancialDataSource
import com.example.data.local.entity.PropertyFinancialEntity
import com.example.data.local.entity.PropertyImportStatus
import com.example.data.local.entity.PropertyIngestionMethod
import com.example.data.local.entity.PropertySourceDefaults
import com.example.domain.finance.underwriting.ExpenseBasis
import com.example.domain.gmail.GmailSendResult
import com.example.domain.intelligence.job.JobStatus
import com.example.domain.propertyurl.pipeline.ImportRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Happy-path integration audit of the core workflow: import → normalize → deduplicate/provenance →
 * enrichment/comparables → underwriting → deal room → offer → delivery.
 *
 * Every component in the chain is the production one, writing into a real SQLite file through the
 * real Room schema. Only the outside world (portal HTTP transport, Gmail delivery, Gemini) is
 * scripted, and only so that the boundaries can be observed - see `docs/e2e-reliability-audit.md`
 * for what this suite deliberately does not claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PropertyToOfferWorkflowTest {

    private lateinit var harness: WorkflowHarness

    @Before
    fun setUp() {
        harness = WorkflowHarness()
        harness.seedSources()
    }

    @After
    fun tearDown() {
        harness.dispose()
    }

    // ── steps 1-3: import, normalization, dedup, provenance ───────────────────────────────────────

    @Test
    fun `a portal url becomes a canonical row with provenance and no invented economics`() = runBlocking {
        harness.stubZillowListing()

        val state = harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL)

        assertEquals(JobStatus.COMPLETED, state.status)
        assertNull(state.errorMessage)
        val propertyId = requireNotNull(state.propertyId) { "a completed import must return the canonical row id" }

        val property = requireNotNull(harness.database.propertyDao().getPropertyById(propertyId))
        assertEquals("4127 Oak Hollow Dr", property.address)
        assertEquals("Austin", property.city)
        assertEquals("TX", property.state)
        assertEquals(565_000.0, property.price, 0.01)
        assertNotNull("the import pipeline must claim a canonical identity key", property.canonicalKey)
        assertEquals(PropertySourceDefaults.INTERNAL_ID, property.primarySourceId)

        val provenance = harness.database.propertySourceDao().getProvenanceForProperty(propertyId)
        assertEquals(1, provenance.size)
        val record = provenance.single()
        assertTrue("provenance must point back at the fetched listing", record.externalUrl.contains("zillow.com"))
        assertTrue(record.isPrimaryForProperty)
        assertEquals(WorkflowHarness.NOW, record.fetchedAt)
        assertEquals(WorkflowHarness.NOW, record.firstSeenAt)
        // DEF-6: the portal's own listing id and the scrape method must reach the provenance row, or
        // level-1 dedup (sourceId + externalId) can never match a URL import and the record has no
        // traceable origin.
        assertEquals(PropertyIngestionMethod.SCRAPE, record.ingestionMethod)
        assertFalse("the source record id may not collapse onto the canonical id", record.externalId.startsWith("cp-"))
        assertTrue(record.externalId.isNotBlank())

        // Valuation is not derivable from a listing page: those rows must stay explicit zeros.
        val marketData = requireNotNull(harness.database.propertyDao().getMarketData(propertyId))
        assertEquals(0.0, marketData.estimatedValue, 0.001)
        assertEquals(0.0, marketData.medianAreaPrice, 0.001)
        assertEquals(
            "no rent observation may become a fabricated rent",
            0.0,
            requireNotNull(harness.database.propertyDao().getRentEstimate(propertyId)).estimatedRent,
            0.001
        )
    }

    @Test
    fun `importing the same listing twice is idempotent end to end`() = runBlocking {
        harness.stubZillowListing()

        val first = harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL)
        val propertyId = requireNotNull(first.propertyId)
        val firstProvenance = harness.database.propertySourceDao().getProvenanceForProperty(propertyId).single()

        val second = harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL)

        assertEquals(
            "a suppressed duplicate is an idempotent success, not a failed import",
            JobStatus.COMPLETED,
            second.status
        )
        assertEquals(propertyId, second.propertyId)
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
        assertEquals(
            "the idempotency ledger must suppress the second fetch",
            1,
            harness.fetcher.hitsFor(WorkflowHarness.ZILLOW_ROUTE)
        )
        val provenance = harness.database.propertySourceDao().getProvenanceForProperty(propertyId)
        assertEquals(1, provenance.size)
        assertEquals(
            "a suppressed duplicate must not refresh the stored source record",
            firstProvenance.lastSeenAt,
            provenance.single().lastSeenAt
        )
        assertEquals(1, harness.database.propertyDao().getImagesListForProperty(propertyId).size)
    }

    @Test
    fun `a forced refresh refetches and still converges on the canonical row`() = runBlocking {
        harness.stubZillowListing()
        val propertyId = requireNotNull(
            harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL).propertyId
        )

        harness.pipelineClock.advance(60_000)
        harness.clock.set(WorkflowHarness.NOW + 60_000)
        val forced = harness.bridge.importAndStore(WorkflowHarness.ZILLOW_URL, ImportRequest(forceRefresh = true))

        assertTrue("expected a refreshed import, got ${forced.describe()}", forced.isSuccess)
        assertEquals(propertyId, forced.property!!.canonicalId)
        assertEquals(2, harness.fetcher.hitsFor(WorkflowHarness.ZILLOW_ROUTE))
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
        assertEquals(
            "the refresh must be recorded on the same provenance row",
            WorkflowHarness.NOW + 60_000,
            harness.database.propertySourceDao().getProvenanceForProperty(propertyId).single().lastSeenAt
        )
    }

    @Test
    fun `the same house arriving from a second source merges instead of duplicating`() = runBlocking {
        harness.stubZillowListing()
        val propertyId = requireNotNull(
            harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL).propertyId
        )
        val stored = requireNotNull(harness.database.propertyDao().getPropertyById(propertyId))

        // The wholesaler saw the same house: same normalized address, different source and record id.
        val wholesale = WorkflowHarness.bareBundle(
            WorkflowHarness.listingRow(
                id = "wholesale-1",
                address = stored.address,
                city = stored.city,
                state = stored.state,
                zipCode = stored.zipCode,
                price = 545_000.0,
                sourceType = "OFF_MARKET"
            )
        ).copy(
            sourceId = PropertySourceDefaults.WHOLESALE_ID,
            externalId = "ws-99",
            ingestionMethod = PropertyIngestionMethod.MANUAL
        )

        val result = harness.importer.importBundle(wholesale)

        assertEquals("the merge must resolve onto the existing canonical row", propertyId, result.propertyId)
        assertFalse("a known house must not insert a second canonical row", result.outcome.name == "INSERTED")
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
        val provenance = harness.database.propertySourceDao().getProvenanceForProperty(propertyId)
        assertEquals("both sources must be attributable on one canonical row", 2, provenance.size)
        assertEquals(
            setOf(PropertySourceDefaults.INTERNAL_ID, PropertySourceDefaults.WHOLESALE_ID),
            provenance.map { it.sourceId }.toSet()
        )
    }

    // ── steps 1-4: manual entry, import jobs, enrichment and comparables ─────────────────────────

    @Test
    fun `a manual property runs the same job provenance and dedup pipeline`() = runBlocking {
        val bundle = WorkflowHarness.bareBundle(
            WorkflowHarness.listingRow(id = "manual-1", apn = "0412345678")
        ).copy(
            sourceId = PropertySourceDefaults.MANUAL_ID,
            externalId = "manual-1",
            ingestionMethod = PropertyIngestionMethod.MANUAL
        )

        val first = harness.importer.runImport(
            sourceId = PropertySourceDefaults.MANUAL_ID,
            triggerKind = "MANUAL",
            fetch = { listOf(bundle) }
        )

        assertEquals(PropertyImportStatus.COMPLETED, first.status)
        assertEquals(1, first.fetched)
        assertEquals(1, first.inserted)
        assertEquals(0, first.failed)
        val stored = harness.properties.allProperties.first()
        assertEquals("exactly one canonical row must exist", 1, stored.size)
        val propertyId = stored.single().id
        val property = requireNotNull(harness.database.propertyDao().getPropertyById(propertyId))
        assertEquals("0412345678", property.apn)
        assertEquals(PropertySourceDefaults.MANUAL_ID, property.primarySourceId)

        val jobs = harness.properties.observeImportJobs(5).first()
        assertEquals(1, jobs.size)
        assertEquals(PropertyImportStatus.COMPLETED, jobs.single().status)
        assertEquals(1, jobs.single().recordsInserted)
        assertEquals(PropertySourceDefaults.MANUAL_ID, jobs.single().sourceId)
        assertEquals(WorkflowHarness.NOW, jobs.single().startedAt)

        // Re-running the same feed refreshes the row instead of creating a second one.
        harness.clock.set(WorkflowHarness.NOW + 5_000)
        val second = harness.importer.runImport(
            sourceId = PropertySourceDefaults.MANUAL_ID,
            triggerKind = "MANUAL",
            fetch = { listOf(bundle) }
        )

        assertEquals(0, second.inserted)
        assertEquals(1, second.deduplicated)
        assertEquals(1, harness.database.propertyDao().getPropertiesCount())
        assertEquals(2, harness.properties.observeImportJobs(5).first().size)
        val provenance = harness.database.propertySourceDao().getProvenanceForProperty(propertyId)
        assertEquals(1, provenance.size)
        assertEquals(WorkflowHarness.NOW + 5_000, provenance.single().lastSeenAt)
    }

    @Test
    fun `comparables and enrichment carried by a source reach the deal room and never duplicate`() = runBlocking {
        val bundle = WorkflowHarness.bareBundle(WorkflowHarness.listingRow(id = "feed-1")).copy(
            sourceId = PropertySourceDefaults.MLS_ID,
            externalId = "mls-9",
            comps = listOf(
                WorkflowHarness.comp("feed-1", "2402 South Congress Avenue"),
                WorkflowHarness.comp("feed-1", "100 Main St")
            ),
            enrichments = listOf(
                WorkflowHarness.enrichment(
                    propertyId = "feed-1",
                    type = PropertyEnrichmentType.FLOOD_RISK,
                    provider = "FEMA",
                    valueText = "Zone AE",
                    confidence = 95.0
                )
            ),
            financials = PropertyFinancialEntity(
                propertyId = "feed-1",
                annualPropertyTax = 6_200.0,
                monthlyRentEstimate = 2_350.0,
                dataSource = PropertyFinancialDataSource.RECORDS,
                lastUpdatedAt = WorkflowHarness.NOW
            )
        )

        val propertyId = harness.importer.importBundle(bundle).propertyId

        val snapshot = requireNotNull(harness.intelligenceRepository.getCompleteDealRoom(propertyId))
        assertEquals(2, snapshot.comps.size)
        assertEquals(PropertyEnrichmentType.FLOOD_RISK, snapshot.enrichment!!.enrichmentType)
        assertEquals("Zone AE", snapshot.enrichment!!.valueText)
        assertEquals(2_350.0, requireNotNull(snapshot.financials).monthlyRentEstimate, 0.001)
        assertEquals(6_200.0, requireNotNull(snapshot.financials).annualPropertyTax, 0.001)

        // The same comparables with a different street spelling or ordering must not add rows.
        val again = harness.importer.importBundle(
            bundle.copy(
                comps = listOf(
                    WorkflowHarness.comp("feed-1", "100 Main Street"),
                    WorkflowHarness.comp("feed-1", "2402 S Congress Ave")
                )
            )
        )
        assertEquals(propertyId, again.propertyId)
        val afterReimport = requireNotNull(harness.intelligenceRepository.getCompleteDealRoom(propertyId))
        assertEquals(2, afterReimport.comps.size)
        assertEquals(1, afterReimport.enrichments.size)
    }

    // ── step 6: underwriting, then steps 8-10: deal room, offer, delivery ────────────────────────

    @Test
    fun `underwriting consumes stored rows only and stays finite on a thin record`() = runBlocking {
        harness.stubZillowListing()
        val propertyId = requireNotNull(
            harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL).propertyId
        )
        val property = requireNotNull(harness.database.propertyDao().getPropertyById(propertyId))

        harness.financials.analyzeProperty(propertyId)

        val analysis = requireNotNull(harness.database.financialDao().getAnalysis(propertyId))
        assertEquals("the stored price is the underwriting price", property.price, analysis.purchasePrice, 0.01)
        assertEquals(
            "no rent estimate means no rent, not a price-ratio guess",
            0.0,
            analysis.monthlyRent,
            0.001
        )
        assertEquals(
            "no renovation evidence means zero, not an invented rehab number",
            0.0,
            analysis.renovationCost,
            0.001
        )
        // Tax semantics (see docs/underwriting-engine.md, "Missing-data semantics"). The Zillow listing
        // carries no tax figure, so the import bridge stores the 0.0 placeholder. That placeholder means
        // "missing", not a verified zero tax, so the engine must not consume it as a fact.
        assertEquals(
            "a listing without a tax figure stores the missing placeholder, not a verified zero",
            0.0,
            requireNotNull(harness.database.propertyDao().getTaxRecord(propertyId)).annualTaxAmount,
            0.001
        )
        // The engine resolves the missing tax to the named assumption: 1.20% of the 565,000 price
        // = 6,780. The literal is computed by hand here, independently of the production constant.
        assertEquals(
            "a missing tax record resolves to the named 1.20%-of-price assumption",
            6_780.0,
            analysis.propertyTaxAnnual,
            0.001
        )
        // The estimate must be visible, not presented as a stored fact.
        val underwriting = harness.financials.underwriteProperty(propertyId)
        assertTrue(
            "the missing tax must be reported as a validation finding",
            underwriting.validation.any { it.code == "MISSING_PROPERTY_TAX_RECORD" }
        )
        assertEquals(
            "the tax line must be marked as a percent-of-price default, not a fixed annual amount",
            ExpenseBasis.PERCENT_OF_PRICE,
            underwriting.operating.expenseLines.single { it.key == "propertyTax" }.basis
        )
        assertTrue("persisted metrics must stay finite", analysis.noiAnnual.isFinite())
        assertTrue("persisted metrics must stay finite", analysis.monthlyCashFlow.isFinite())
        assertTrue(analysis.dealScore in 0..100)

        val scenarios = harness.database.financialDao().getScenariosListForProperty(propertyId)
        assertTrue("every financing model must be compared, not only the default", scenarios.isNotEmpty())
        assertTrue(scenarios.all { it.monthlyPayment.isFinite() && it.cashRequired.isFinite() })
    }

    @Test
    fun `an offer for a pipeline property persists its document and audit trail once`() = runBlocking {
        harness.stubZillowListing()
        val propertyId = requireNotNull(
            harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL).propertyId
        )

        val offer = harness.offers.generateOffer(
            context = harness.context,
            propertyId = propertyId,
            customPrice = 520_000.0,
            recipientName = "Listing Agent",
            recipientEmail = "agent@example.com"
        )

        assertEquals("READY", offer.status)
        assertEquals(520_000.0, offer.offerPrice, 0.01)
        assertEquals("agent@example.com", offer.recipientEmail)
        val pdfPath = requireNotNull(offer.pdfPath) { "an offer must carry the document it is sent with" }
        assertTrue(
            "the document must live in the app private offers directory",
            File(pdfPath).canonicalFile.path.startsWith(
                File(harness.context.filesDir, "offers").canonicalFile.path
            )
        )

        // Gemini has no configured key: the letter must come from the deterministic template.
        assertTrue(offer.generatedLetterContent.contains("4127 Oak Hollow Dr"))
        assertTrue(offer.generatedLetterContent.contains("520,000"))

        assertEquals(1, harness.offers.getDocumentsForOffer(offer.id).first().size)
        assertTrue(
            harness.offers.getAuditTrailForOffer(offer.id).first().any { it.eventType == "OFFER_GENERATED" }
        )

        // A repeated generation for the same property reuses the offer and its send ledger.
        val again = harness.offers.generateOffer(harness.context, propertyId, customPrice = 499_000.0)
        assertEquals(offer.id, again.id)
        assertEquals("a re-run must not overwrite the drafted price", 520_000.0, again.offerPrice, 0.01)
        assertEquals(1, harness.offers.allOffers.first().size)
    }

    @Test
    fun `delivery through the approved workflow sends once and survives a relaunch`() = runBlocking {
        val propertyId = "prop-delivery"
        harness.properties.createProperty(WorkflowHarness.listingRow(id = propertyId))
        val pdf = harness.writeOfferPdf("offer-delivery")
        harness.database.offerDao().insertOffer(harness.draftOffer(propertyId, "offer-delivery", pdf))
        connectGmail(WorkflowHarness.NOW)
        harness.gmail.script(GmailSendResult(success = true, messageId = "gmail-msg-1"))

        val outcome = harness.offers.sendOfferDetailed(harness.context, "offer-delivery")

        assertTrue(outcome.error ?: "send failed", outcome.success)
        assertEquals(OfferEmailSendStatus.SENT, outcome.status)
        assertEquals("offer-send-v1:offer-delivery", outcome.idempotencyKey)
        assertEquals("gmail-msg-1", outcome.messageId)
        assertEquals(listOf<String?>("offer-send-v1:offer-delivery"), harness.gmail.keysSnapshot())

        // Process death after delivery: the ledger must still prove that the message went out.
        harness.restart()
        val ledger = requireNotNull(harness.database.offerDao().getEmailSendForOffer("offer-delivery"))
        assertEquals(OfferEmailSendStatus.SENT, ledger.status)
        assertEquals("gmail-msg-1", ledger.messageId)
        assertEquals(1, ledger.attemptCount)
        assertEquals("SENT", harness.database.offerDao().getOfferById("offer-delivery")?.status)

        // A re-dispatch after the relaunch must not reach the provider a second time.
        val replay = harness.offers.sendOfferDetailed(harness.context, "offer-delivery")
        assertTrue(replay.success)
        assertEquals(1, harness.gmail.callCount.get())

        val audit = harness.offers.getAuditTrailForOffer("offer-delivery").first()
        assertTrue(
            audit.map { it.eventType }.containsAll(listOf("SEND_RECORD_CREATED", "SEND_ATTEMPT_STARTED", "EMAIL_SENT"))
        )
    }

    @Test
    fun `the deal room rebuilds from disk after an app relaunch`() = runBlocking {
        harness.stubZillowListing()
        val propertyId = requireNotNull(
            harness.intelligenceRepository.importPropertyUrl(WorkflowHarness.ZILLOW_URL).propertyId
        )
        val merged = harness.importer.importBundle(
            WorkflowHarness.bareBundle(WorkflowHarness.listingRow(id = "feed-2")).copy(
                sourceId = PropertySourceDefaults.MLS_ID,
                externalId = "mls-feed-2",
                enrichments = listOf(
                    WorkflowHarness.enrichment(
                        propertyId = "feed-2",
                        type = PropertyEnrichmentType.WALK_SCORE,
                        provider = "WalkScore",
                        valueNumeric = 72.0
                    )
                ),
                comps = listOf(WorkflowHarness.comp("feed-2", "300 Elm St"))
            )
        )
        assertEquals("the feed row must converge on the imported house", propertyId, merged.propertyId)
        harness.financials.analyzeProperty(propertyId)
        connectGmail(WorkflowHarness.NOW)

        val before = requireNotNull(harness.intelligenceRepository.getCompleteDealRoom(propertyId))
        harness.restart()
        val after = requireNotNull(harness.intelligenceRepository.getCompleteDealRoom(propertyId)) {
            "a relaunch must not lose the property"
        }

        assertEquals(before.property.id, after.property.id)
        assertEquals(before.property.price, after.property.price, 0.001)
        assertEquals(before.property.canonicalKey, after.property.canonicalKey)
        assertEquals(before.provenance.map { it.externalId }, after.provenance.map { it.externalId })
        assertEquals(1, after.comps.size)
        assertEquals(1, after.enrichments.size)
        assertFalse("the merged row must survive the relaunch", after.enrichments.isEmpty())
        assertNull("nothing in production writes an AI analysis, so none may appear", after.aiAnalysis)
        assertNotNull("underwriting output must survive the relaunch", harness.database.financialDao().getAnalysis(propertyId))
        assertEquals(
            "the connection state is stored, not process local",
            true,
            harness.configRepository.getGmailConfig().isConnected
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private suspend fun connectGmail(now: Long) {
        harness.configRepository.saveGmailConfig(
            GmailConfigurationEntity(
                accountEmail = "buyer@example.com",
                senderName = "Acquisitions Team",
                accessToken = "test-access-token",
                refreshToken = "test-refresh-token",
                expiresAt = now + 3_600_000L,
                isConnected = true
            )
        )
    }
}
