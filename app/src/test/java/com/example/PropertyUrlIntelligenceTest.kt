package com.example

import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.PropertyEntity
import com.example.domain.intelligence.ai.AiAnalystEngine
import com.example.domain.intelligence.dedup.PropertyDeduplicator
import com.example.domain.intelligence.engine.DeterministicFinancialEngine
import com.example.domain.intelligence.model.*
import com.example.domain.intelligence.scoring.DealScoringEngine
import com.example.domain.intelligence.source.PropertyUrlResolver
import com.example.domain.intelligence.source.SourceRegistry
import com.example.domain.intelligence.source.adapters.*
import com.example.domain.scoring.ScoringWeights
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PropertyUrlIntelligenceTest {

    @Test
    fun testUrlValidationAndSourceDetection() {
        // 1. Zillow
        val zillowRes = PropertyUrlResolver.resolve("https://www.zillow.com/homedetails/1420-S-Congress-Ave-Austin-TX-78704/29481920_zpid/?utm_source=email&utm_campaign=daily_deals&token=secret123")
        assertTrue(zillowRes.isValid)
        assertEquals("Zillow", zillowRes.identifiedSource)
        assertEquals("www.zillow.com", zillowRes.domain)
        assertFalse("Sanitized URL must strip tracking params", zillowRes.sanitizedUrl.contains("utm_source"))
        assertFalse("Sanitized URL must strip token params", zillowRes.sanitizedUrl.contains("secret123"))

        // 2. Redfin
        val redfinRes = PropertyUrlResolver.resolve("https://www.redfin.com/TX/Austin/2804-E-4th-St-78702/home/9876543")
        assertTrue(redfinRes.isValid)
        assertEquals("Redfin", redfinRes.identifiedSource)

        // 3. Realtor
        val realtorRes = PropertyUrlResolver.resolve("https://www.realtor.com/realestateandhomes-detail/1105-Nueces-St_Austin_TX_78701_M1105")
        assertTrue(realtorRes.isValid)
        assertEquals("Realtor.com", realtorRes.identifiedSource)

        // 4. Homes.com
        val homesRes = PropertyUrlResolver.resolve("https://www.homes.com/property/5204-menchaca-rd-austin-tx/abc123xyz/")
        assertTrue(homesRes.isValid)
        assertEquals("Homes.com", homesRes.identifiedSource)

        // 5. Generic / Other portal
        val genericRes = PropertyUrlResolver.resolve("https://www.compass.com/listing/1500-w-6th-st-austin-tx-78703/12345/")
        assertTrue(genericRes.isValid)
        assertEquals("Generic", genericRes.identifiedSource)

        // 6. Invalid URL
        val invalidRes = PropertyUrlResolver.resolve("not-a-valid-url")
        assertFalse(invalidRes.isValid)
        assertNotNull(invalidRes.validationError)

        // 7. Cleartext and domain lookalikes are not accepted as a trusted listing source.
        val cleartext = PropertyUrlResolver.resolve("http://www.zillow.com/homedetails/1/12345_zpid/")
        assertFalse(cleartext.isValid)
        val lookalike = PropertyUrlResolver.resolve("https://www.zillow.com.evil-clone.net/homedetails/1/12345_zpid/")
        assertEquals("Unsupported", lookalike.identifiedSource)

        // 8. Blank URL
        val blankRes = PropertyUrlResolver.resolve("   ")
        assertFalse(blankRes.isValid)
    }

    @Test
    fun testZillowAdapterParsingWithOfflineFixture() = runBlocking {
        val adapter = ZillowUrlSourceAdapter()
        val url = "https://www.zillow.com/homedetails/1420-S-Congress-Ave-Austin-TX-78704/29481920_zpid/"

        assertTrue(adapter.supports(url))
        assertFalse(adapter.supports("https://www.redfin.com/home/123"))
        assertFalse(adapter.supports("https://www.zillow.com.evil-clone.net/homedetails/123"))

        val result = adapter.extract(url)
        assertTrue("Extraction should succeed", result.success)
        assertNotNull("Canonical property must not be null", result.canonicalProperty)

        val prop = result.canonicalProperty!!
        assertEquals("29481920", prop.listingId)
        assertTrue(prop.address.contains("Austin"))
        assertEquals("TX", prop.state)
        assertEquals("78704", prop.zipCode)
        assertEquals(485000.0, prop.listPrice, 0.01)
        assertTrue(prop.bedrooms!! >= 3)
        assertTrue(prop.bathrooms!! >= 2.0)
        assertTrue(prop.photos.isNotEmpty())

        // Provenance checks
        assertTrue("Must include provenance records", result.provenanceRecords.isNotEmpty())
        val priceProv = result.provenanceRecords.firstOrNull { it.field == "listPrice" }
        assertNotNull(priceProv)
        assertEquals("Zillow", priceProv?.source)
        assertEquals(ProvenanceSourceTier.DIRECT_LISTING, priceProv?.tier)
        assertTrue((priceProv?.confidence ?: 0.0) >= 0.95)
    }

    @Test
    fun testSourceRegistryExtensibility() {
        val registry = SourceRegistry()
        registry.registerAdapter(ZillowUrlSourceAdapter())
        registry.registerAdapter(RedfinUrlSourceAdapter())
        registry.registerAdapter(RealtorUrlSourceAdapter())
        registry.registerAdapter(HomesUrlSourceAdapter())
        registry.registerAdapter(GenericUrlSourceAdapter())

        val sources = registry.getSupportedSources()
        assertTrue(sources.contains("Zillow"))
        assertTrue(sources.contains("Redfin"))
        assertTrue(sources.contains("Realtor.com"))
        assertTrue(sources.contains("Homes.com"))

        val foundZillow = registry.findAdapter("https://www.zillow.com/homedetails/123_zpid/")
        assertNotNull(foundZillow)
        assertEquals("Zillow", foundZillow?.sourceName)

        val foundRedfin = registry.findAdapter("https://www.redfin.com/TX/Austin/home/123")
        assertNotNull(foundRedfin)
        assertEquals("Redfin", foundRedfin?.sourceName)

        val foundGeneric = registry.findAdapter("https://myrealtyportal.com/homes/123")
        assertNotNull(foundGeneric)
    }

    @Test
    fun testDataProvenanceHierarchy() {
        val directPrice = FieldProvenance("listPrice", "$485,000", "Zillow Listing", ProvenanceSourceTier.DIRECT_LISTING, confidence = 0.99)
        val aiInferredRent = FieldProvenance("estimatedRent", "$3,500", "Gemini AI", ProvenanceSourceTier.AI_INFERENCE, confidence = 0.70)
        val govTax = FieldProvenance("propertyTax", "$8,200", "Travis County Assessor", ProvenanceSourceTier.GOVERNMENT_DATA, confidence = 0.98)

        val manifest = DataProvenanceManifest(
            propertyId = "PROP-123",
            records = listOf(directPrice, aiInferredRent, govTax)
        )

        val retrievedPrice = manifest.getProvenanceFor("listPrice")
        assertEquals(ProvenanceSourceTier.DIRECT_LISTING, retrievedPrice?.tier)
        assertEquals("$485,000", retrievedPrice?.value)

        val retrievedRent = manifest.getProvenanceFor("estimatedRent")
        assertEquals(ProvenanceSourceTier.AI_INFERENCE, retrievedRent?.tier)

        // Tier priority check
        assertTrue(ProvenanceSourceTier.DIRECT_LISTING.priority < ProvenanceSourceTier.GOVERNMENT_DATA.priority)
        assertTrue(ProvenanceSourceTier.GOVERNMENT_DATA.priority < ProvenanceSourceTier.LICENSED_API.priority)
        assertTrue(ProvenanceSourceTier.LICENSED_API.priority < ProvenanceSourceTier.SECONDARY_ESTIMATE.priority)
        assertTrue(ProvenanceSourceTier.SECONDARY_ESTIMATE.priority < ProvenanceSourceTier.AI_INFERENCE.priority)
    }

    @Test
    fun testDeterministicFinancialEngineAllStrategies() {
        val canonical = CanonicalProperty(
            propertyId = "PROP-FIN-1",
            sourceUrl = "https://www.zillow.com/123",
            source = "Zillow",
            address = "1420 S Congress Ave, Austin, TX 78704",
            city = "Austin",
            state = "TX",
            zipCode = "78704",
            listPrice = 500000.0,
            estimatedRent = 3800.0,
            propertyTax = 9000.0
        )

        // 1. Buy & Hold with Conventional
        val buyAndHold = DeterministicFinancialEngine.calculate(
            property = canonical,
            strategy = InvestmentStrategy.BUY_AND_HOLD,
            financingType = FinancingType.CONVENTIONAL
        )
        assertEquals(500000.0, buyAndHold.purchasePrice, 0.01)
        assertEquals(100000.0, buyAndHold.downPayment, 0.01) // 20% down
        assertEquals(400000.0, buyAndHold.loanAmount, 0.01)
        assertTrue("Cash flow should be deterministic", buyAndHold.monthlyCashFlow != 0.0)
        assertTrue("Cap rate should be positive", buyAndHold.capRate > 0.0)
        assertTrue("DSCR should be positive", buyAndHold.dscr > 0.0)
        assertTrue("LTV should be 80%", buyAndHold.ltv == 80.0)

        // 2. BRRRR with Hard Money
        val brrrr = DeterministicFinancialEngine.calculate(
            property = canonical,
            strategy = InvestmentStrategy.BRRRR,
            financingType = FinancingType.HARD_MONEY
        )
        assertEquals(InvestmentStrategy.BRRRR, brrrr.strategy)
        assertEquals(FinancingType.HARD_MONEY, brrrr.financingType)
        assertEquals(35000.0, brrrr.estimatedRepairs, 0.01)
        assertNotNull(brrrr.projectedArv)
        assertTrue(brrrr.projectedArv!! > brrrr.purchasePrice)

        // 3. Fix & Flip
        val flip = DeterministicFinancialEngine.calculate(
            property = canonical,
            strategy = InvestmentStrategy.FIX_AND_FLIP,
            financingType = FinancingType.PRIVATE_MONEY
        )
        assertEquals(50000.0, flip.estimatedRepairs, 0.01)

        // 4. Wholesale
        val wholesale = DeterministicFinancialEngine.calculate(
            property = canonical,
            strategy = InvestmentStrategy.WHOLESALE,
            financingType = FinancingType.SELLER_FINANCING
        )
        assertEquals(0.0, wholesale.estimatedRepairs, 0.01)
    }

    @Test
    fun testDealScoringEngineBreakdown() {
        val canonical = CanonicalProperty(
            propertyId = "PROP-SCORE-1",
            sourceUrl = "https://www.zillow.com/123",
            source = "Zillow",
            address = "1420 S Congress Ave, Austin, TX 78704",
            city = "Austin",
            state = "TX",
            zipCode = "78704",
            listPrice = 450000.0,
            estimatedRent = 3600.0,
            propertyTax = 7500.0,
            daysOnMarket = 12,
            bedrooms = 3,
            bathrooms = 2.0,
            squareFeet = 1800
        )

        val fin = DeterministicFinancialEngine.calculate(canonical)
        val manifest = DataProvenanceManifest(
            propertyId = canonical.propertyId,
            records = listOf(
                FieldProvenance("listPrice", "$450,000", "Zillow", ProvenanceSourceTier.DIRECT_LISTING),
                FieldProvenance("estimatedRent", "$3,600", "Zillow Rent Zestimate", ProvenanceSourceTier.SECONDARY_ESTIMATE, confidence = 0.88)
            )
        )

        val score = DealScoringEngine.calculateScore(canonical, fin, manifest)

        assertTrue("Deal score must be in range 0..100", score.dealScore in 0..100)
        assertTrue("Cash flow score must be in range 0..100", score.cashFlowScore in 0..100)
        assertTrue("Equity score must be in range 0..100", score.equityScore in 0..100)
        assertTrue("Market score must be in range 0..100", score.marketScore in 0..100)
        assertTrue("Risk score must be in range 0..100", score.riskScore in 0..100)
        assertTrue("Data confidence score must be in range 0..100", score.dataConfidenceScore in 0..100)
        assertTrue("Positive factors must be present", score.positiveFactors.isNotEmpty())
        val detailed = score.detailedResult!!
        assertEquals("deal-scoring-v2", detailed.scoringModelVersion)
        assertFalse(
            "Legacy default repairs are an assumption, not a verified renovation input",
            detailed.subscores.first { it.id == ScoringWeights.RISK_SAFETY }
                .components.first { it.componentId == "renovationPctOfPrice" }.covered
        )
        assertFalse(
            "Zillow is a listing portal, not a seller-motivation channel",
            detailed.subscores.first { it.id == ScoringWeights.DISTRESS }
                .components.first { it.componentId == "sourceType" }.covered
        )
    }

    @Test
    fun legacyFinanceRentFallbackIsNotPromotedIntoScoringInputs() {
        val property = CanonicalProperty(
            propertyId = "PROP-SCORE-MISSING-RENT",
            sourceUrl = "https://www.zillow.com/123",
            source = "Zillow",
            address = "1420 S Congress Ave, Austin, TX 78704",
            city = "Austin",
            state = "TX",
            zipCode = "78704",
            listPrice = 450_000.0,
            estimatedRent = null
        )
        val financials = DeterministicFinancialEngine.calculate(property)
        val score = DealScoringEngine.calculateScore(
            property,
            financials,
            DataProvenanceManifest(property.propertyId)
        )
        val detailed = score.detailedResult!!
        val cashFlow = detailed.subscores.first { it.id == ScoringWeights.CASH_FLOW }
        val risk = detailed.subscores.first { it.id == ScoringWeights.RISK_SAFETY }

        assertFalse(cashFlow.components.first { it.componentId == "monthlyCashFlow" }.covered)
        assertFalse(cashFlow.components.first { it.componentId == "capRatePct" }.covered)
        assertFalse(risk.components.first { it.componentId == "dscr" }.covered)
        assertFalse(risk.components.first { it.componentId == "vacancyRatePct" }.covered)
        assertFalse(risk.components.first { it.componentId == "renovationPctOfPrice" }.covered)
        assertTrue(detailed.missingInputs.contains("monthlyCashFlow"))
        assertTrue(detailed.missingInputs.contains("capRatePct"))
        assertTrue(detailed.missingInputs.contains("dscr"))
    }


    @Test
    fun aiInferredRentIsNotUsedByTheDeterministicScore() {
        val property = CanonicalProperty(
            propertyId = "PROP-SCORE-AI-RENT",
            sourceUrl = "https://www.zillow.com/123",
            source = "Zillow",
            address = "1420 S Congress Ave, Austin, TX 78704",
            city = "Austin",
            state = "TX",
            zipCode = "78704",
            listPrice = 450_000.0,
            estimatedRent = 3_600.0
        )
        val financials = DeterministicFinancialEngine.calculate(property)
        val provenance = DataProvenanceManifest(
            propertyId = property.propertyId,
            records = listOf(
                FieldProvenance(
                    "estimatedRent", "$3,600", "Gemini inference",
                    ProvenanceSourceTier.AI_INFERENCE, confidence = 0.99
                )
            )
        )
        val detailed = DealScoringEngine.calculateScore(property, financials, provenance).detailedResult!!
        val cashFlow = detailed.subscores.first { it.id == ScoringWeights.CASH_FLOW }
        val confidence = detailed.subscores.first { it.id == ScoringWeights.DATA_CONFIDENCE }

        assertFalse(cashFlow.components.first { it.componentId == "monthlyCashFlow" }.covered)
        assertFalse(cashFlow.components.first { it.componentId == "capRatePct" }.covered)
        assertFalse(confidence.components.any { it.componentId == "rentConfidenceScore" })
    }

    @Test
    fun testPropertyDeduplicator() = runBlocking {
        val existingProperty = PropertyEntity(
            id = "PROP-EXISTING-99",
            sourceType = "ON_MARKET",
            title = "Congress Home",
            address = "1420 South Congress Avenue",
            city = "Austin",
            state = "TX",
            zipCode = "78704",
            latitude = 30.2501,
            longitude = -97.7495,
            price = 485000.0,
            propertyType = "Single Family",
            bedrooms = 3,
            bathrooms = 2.0,
            squareFeet = 1850,
            yearBuilt = 2014,
            lotSizeSqFt = 6500,
            description = "Existing listing in database",
            status = "Active",
            primaryImageUrl = "https://example.com/photo.jpg",
            scannedAt = System.currentTimeMillis()
        )

        // Mock DAO containing the single existing property
        val fakePropertyDao = object : PropertyDao {
            override fun getAllProperties(): Flow<List<PropertyEntity>> = flowOf(listOf(existingProperty))
            override fun getPropertiesBySource(sourceType: String): Flow<List<PropertyEntity>> = flowOf(listOf(existingProperty).filter { it.sourceType == sourceType })
            override suspend fun getAllPropertiesList(): List<PropertyEntity> = listOf(existingProperty)
            override suspend fun getPropertyById(id: String): PropertyEntity? = if (id == existingProperty.id) existingProperty else null
            override fun getPropertyByIdFlow(id: String): Flow<PropertyEntity?> = flowOf(if (id == existingProperty.id) existingProperty else null)
            override fun getRecentOpportunities(): Flow<List<PropertyEntity>> = flowOf(emptyList())
            override fun getPropertiesCountFlow(): Flow<Int> = flowOf(1)
            override suspend fun getPropertiesCount(): Int = 1
            override fun getQualifiedDealsCountFlow(): Flow<Int> = flowOf(0)
            override fun getSavedProperties(): Flow<List<PropertyEntity>> = flowOf(emptyList())
            override fun getSavedDeals(): Flow<List<PropertyEntity>> = flowOf(emptyList())
            override suspend fun insertProperty(property: PropertyEntity) {}
            override suspend fun insertProperties(properties: List<PropertyEntity>) {}
            override suspend fun updateProperty(property: PropertyEntity) {}
            override suspend fun deletePropertyById(id: String) {}
            override suspend fun setSavedStatus(id: String, isSaved: Boolean) {}
            override suspend fun setDealStatus(id: String, isDeal: Boolean, score: Int) {}
            override fun getImagesForProperty(propertyId: String) = flowOf(emptyList<com.example.data.local.entity.PropertyImageEntity>())
            override suspend fun insertImages(images: List<com.example.data.local.entity.PropertyImageEntity>) {}
            override suspend fun deleteImagesByPropertyId(propertyId: String) {}
            override fun getMarketDataFlow(propertyId: String) = flowOf(null)
            override suspend fun getMarketData(propertyId: String) = null
            override suspend fun insertMarketData(data: com.example.data.local.entity.MarketDataEntity) {}
            override suspend fun deleteMarketDataByPropertyId(propertyId: String) {}
            override fun getRentEstimateFlow(propertyId: String) = flowOf(null)
            override suspend fun getRentEstimate(propertyId: String) = null
            override suspend fun insertRentEstimate(rent: com.example.data.local.entity.RentEstimateEntity) {}
            override suspend fun deleteRentEstimateByPropertyId(propertyId: String) {}
            override fun getTaxRecordFlow(propertyId: String) = flowOf(null)
            override suspend fun getTaxRecord(propertyId: String) = null
            override suspend fun insertTaxRecord(tax: com.example.data.local.entity.TaxRecordEntity) {}
            override suspend fun deleteTaxRecordByPropertyId(propertyId: String) {}
            override fun getSalesHistory(propertyId: String) = flowOf(emptyList<com.example.data.local.entity.SalesHistoryEntity>())
            override suspend fun insertSalesHistory(history: List<com.example.data.local.entity.SalesHistoryEntity>) {}
            override suspend fun deleteSalesHistoryByPropertyId(propertyId: String) {}
            override fun getCompsForProperty(propertyId: String) = flowOf(emptyList<com.example.data.local.entity.ComparablePropertyEntity>())
            override suspend fun insertComps(comps: List<com.example.data.local.entity.ComparablePropertyEntity>) {}
            override suspend fun deleteCompsByPropertyId(propertyId: String) {}
            override suspend fun getCompsListForProperty(propertyId: String): List<com.example.data.local.entity.ComparablePropertyEntity> = emptyList()
            override suspend fun getAllImagesList(): List<com.example.data.local.entity.PropertyImageEntity> = emptyList()
            override suspend fun getAllMarketDataList(): List<com.example.data.local.entity.MarketDataEntity> = emptyList()
            override suspend fun getAllRentEstimatesList(): List<com.example.data.local.entity.RentEstimateEntity> = emptyList()
            override suspend fun getAllTaxRecordsList(): List<com.example.data.local.entity.TaxRecordEntity> = emptyList()
            override suspend fun getAllSalesHistoryList(): List<com.example.data.local.entity.SalesHistoryEntity> = emptyList()
            override suspend fun getAllCompsList(): List<com.example.data.local.entity.ComparablePropertyEntity> = emptyList()
            override suspend fun insertMarketDataList(list: List<com.example.data.local.entity.MarketDataEntity>) {}
            override suspend fun insertRentEstimateList(list: List<com.example.data.local.entity.RentEstimateEntity>) {}
            override suspend fun insertTaxRecordList(list: List<com.example.data.local.entity.TaxRecordEntity>) {}
        }

        val deduplicator = PropertyDeduplicator(fakePropertyDao)

        // Incoming property with abbreviated address "1420 S Congress Ave"
        val incoming = CanonicalProperty(
            propertyId = "PROP-NEW-123",
            sourceUrl = "https://www.redfin.com/123",
            source = "Redfin",
            address = "1420 S Congress Ave, Austin, TX 78704",
            city = "Austin",
            state = "TX",
            zipCode = "78704",
            latitude = 30.2501,
            longitude = -97.7495,
            listPrice = 485000.0
        )

        val match = deduplicator.findExistingMatch(incoming)
        assertTrue("Deduplicator must find existing property", match.isMatch)
        assertEquals(existingProperty.id, match.existingProperty?.id)
    }

    @Test
    fun testAiEvidenceClassification() {
        val fact = ClassifiedKnowledgeItem("List Price", "$485,000 verified asking price", KnowledgeType.FACT)
        val estimate = ClassifiedKnowledgeItem("Monthly Rent", "$3,450 estimated market rent", KnowledgeType.ESTIMATE)
        val inference = ClassifiedKnowledgeItem("Negotiation", "Likely 3-5% discount due to DOM", KnowledgeType.INFERENCE)
        val unknown = ClassifiedKnowledgeItem("Sewer Scope", "Unverified municipal sewer connection", KnowledgeType.UNKNOWN)

        assertEquals(KnowledgeType.FACT, fact.classification)
        assertEquals(KnowledgeType.ESTIMATE, estimate.classification)
        assertEquals(KnowledgeType.INFERENCE, inference.classification)
        assertEquals(KnowledgeType.UNKNOWN, unknown.classification)

        val aiResult = AiAnalystResult(
            summary = "Executive summary",
            investmentThesis = "Viable long-term buy & hold",
            strengths = listOf("High rent-to-price ratio"),
            weaknesses = listOf("Moderate taxes"),
            risks = listOf("Vacancy risk"),
            redFlags = emptyList(),
            recommendedStrategy = "BUY_AND_HOLD",
            recommendedOfferRange = "$440,000 - $460,000",
            questionsForSeller = listOf("Roof age?"),
            dueDiligenceItems = listOf("HVAC inspection"),
            confidence = 0.92,
            evidence = listOf(fact, estimate, inference, unknown)
        )

        assertEquals(4, aiResult.evidence.size)
        assertTrue(aiResult.evidence.any { it.classification == KnowledgeType.FACT })
        assertTrue(aiResult.evidence.any { it.classification == KnowledgeType.ESTIMATE })
        assertTrue(aiResult.evidence.any { it.classification == KnowledgeType.INFERENCE })
        assertTrue(aiResult.evidence.any { it.classification == KnowledgeType.UNKNOWN })
    }
}
