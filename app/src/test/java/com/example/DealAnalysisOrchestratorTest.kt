package com.example

import com.example.domain.intelligence.engine.DeterministicFinancialEngine
import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.DataProvenanceManifest
import com.example.domain.intelligence.model.DealScoreBreakdown
import com.example.domain.intelligence.model.FieldProvenance
import com.example.domain.intelligence.model.ProvenanceSourceTier
import com.example.domain.intelligence.model.StrategyFinancialMetrics
import com.example.domain.intelligence.scoring.CompCoverage
import com.example.domain.intelligence.scoring.DealAnalysisOrchestrator
import com.example.domain.intelligence.scoring.DealScoringEngine
import com.example.domain.intelligence.scoring.MarketFacts
import com.example.domain.scoring.DealInput
import com.example.domain.scoring.DealScoreResult
import com.example.domain.scoring.ScoringWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Contract tests for the deterministic Deal Analysis orchestration:
 * determinism, missing-data attenuation, provenance filtering and AI isolation.
 *
 * The standalone [com.example.domain.scoring.DealScoringEngine] remains the single
 * authoritative scorer; [DealAnalysisOrchestrator] only maps and filters facts.
 */
class DealAnalysisOrchestratorTest {

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** Strong, fully-covered deal: good cash flow, value entry, healthy market. */
    private fun baseProperty() = CanonicalProperty(
        propertyId = "PROP-ORCH-1",
        sourceUrl = "https://www.zillow.com/123",
        source = "Zillow",
        address = "1420 S Congress Ave, Austin, TX 78704",
        city = "Austin",
        state = "TX",
        zipCode = "78704",
        listPrice = 200_000.0,
        originalListPrice = 210_000.0,
        pricePerSqft = 111.0,
        squareFeet = 1_800,
        yearBuilt = 2015,
        daysOnMarket = 45,
        estimatedRent = 2_600.0
    )

    private fun fullMarket() = MarketFacts(
        estimatedMarketValue = 215_000.0,
        afterRepairValue = 260_000.0,
        renovationCost = 30_000.0,
        medianAreaPrice = 225_000.0,
        areaPricePerSqFt = 125.0,
        areaDaysOnMarket = 25,
        neighborhoodAppreciationPct = 5.0,
        marketDemand = "High",
        floodZone = false,
        taxDelinquent = false,
        sourceType = "ON_MARKET"
    )

    private fun fullProvenance() = DataProvenanceManifest(
        propertyId = "PROP-ORCH-1",
        records = listOf(
            FieldProvenance("listPrice", "$200,000", "Zillow", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.98),
            FieldProvenance("originalListPrice", "$210,000", "Zillow", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.98),
            FieldProvenance("estimatedRent", "$2,600", "Zillow Rent Zestimate", ProvenanceSourceTier.SECONDARY_ESTIMATE, 0L, 0.90),
            FieldProvenance("pricePerSqFt", "$111", "Zillow", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.97),
            FieldProvenance("yearBuilt", "2015", "County assessor", ProvenanceSourceTier.GOVERNMENT_DATA, 0L, 0.95),
            FieldProvenance("daysOnMarket", "45", "Zillow", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.96),
            FieldProvenance("estimatedMarketValue", "$215,000", "Appraisal", ProvenanceSourceTier.LICENSED_API, 0L, 0.90),
            FieldProvenance("afterRepairValue", "$260,000", "Renovation scope", ProvenanceSourceTier.SECONDARY_ESTIMATE, 0L, 0.80),
            FieldProvenance("renovationCost", "$30,000", "Contractor bid", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.90),
            FieldProvenance("medianAreaPrice", "$225,000", "HouseCanary", ProvenanceSourceTier.LICENSED_API, 0L, 0.85),
            FieldProvenance("areaPricePerSqFt", "$125", "HouseCanary", ProvenanceSourceTier.LICENSED_API, 0L, 0.85),
            FieldProvenance("areaDaysOnMarket", "25", "HouseCanary", ProvenanceSourceTier.LICENSED_API, 0L, 0.85),
            FieldProvenance("neighborhoodAppreciationPct", "5.0%", "ATTOM", ProvenanceSourceTier.LICENSED_API, 0L, 0.85),
            FieldProvenance("marketDemand", "High", "HouseCanary", ProvenanceSourceTier.LICENSED_API, 0L, 0.80),
            FieldProvenance("floodZone", "false", "FEMA flood map", ProvenanceSourceTier.GOVERNMENT_DATA, 0L, 0.99),
            FieldProvenance("taxDelinquent", "false", "County tax collector", ProvenanceSourceTier.GOVERNMENT_DATA, 0L, 0.99),
            FieldProvenance("sourceType", "ON_MARKET", "MLS", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.99)
        )
    )

    private fun underwriting(property: CanonicalProperty = baseProperty()) =
        DeterministicFinancialEngine.calculate(property)

    private fun fullContract() = DealAnalysisOrchestrator.score(
        property = baseProperty(),
        underwriting = underwriting(),
        market = fullMarket(),
        comps = CompCoverage(compsCount = 4),
        provenance = fullProvenance()
    )

    private fun subscore(result: DealScoreResult, id: String) =
        result.subscores.first { it.id == id }

    private fun component(result: DealScoreResult, subId: String, compId: String) =
        subscore(result, subId).components.first { it.componentId == compId }

    // -----------------------------------------------------------------------
    // 1. Determinism
    // -----------------------------------------------------------------------

    @Test
    fun orchestrationIsDeterministicAcrossRepeatsAndLocales() {
        val expected = fullContract()
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            repeat(25) {
                assertEquals(
                    "repeat $it must be identical",
                    expected,
                    DealAnalysisOrchestrator.score(
                        property = baseProperty(),
                        underwriting = underwriting(),
                        market = fullMarket(),
                        comps = CompCoverage(compsCount = 4),
                        provenance = fullProvenance()
                    )
                )
            }
            Locale.setDefault(Locale.US)
            assertEquals(
                "Output must not depend on the machine locale",
                expected,
                DealAnalysisOrchestrator.score(
                    property = baseProperty(),
                    underwriting = underwriting(),
                    market = fullMarket(),
                    comps = CompCoverage(compsCount = 4),
                    provenance = fullProvenance()
                )
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun provenanceRecordOrderDoesNotChangeTheResult() {
        val base = fullContract()
        val reversed = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            market = fullMarket(),
            comps = CompCoverage(compsCount = 4),
            provenance = DataProvenanceManifest(
                propertyId = "PROP-ORCH-1",
                records = fullProvenance().records.reversed()
            )
        )
        assertEquals("Provenance manifest order must not matter", base, reversed)
    }

    @Test
    fun contractMappingEqualsDirectStandaloneEngineEvaluation() {
        // The orchestrator must perform NO arithmetic of its own: its mapped DealInput,
        // fed straight into the authoritative engine, must reproduce the contract result.
        val scenario = underwriting()
        val expectedInput = DealInput(
            purchasePrice = scenario.purchasePrice,
            monthlyCashFlow = scenario.monthlyCashFlow,
            annualNoi = scenario.netOperatingIncomeAnnual,
            capRatePct = scenario.capRate,
            cashOnCashPct = scenario.cashOnCashReturn,
            dscr = scenario.dscr,
            monthlyDebtService = scenario.monthlyDebtService,
            grossMonthlyRent = 2_600.0,
            interestRatePct = scenario.interestRate,
            renovationCost = 30_000.0,
            estimatedMarketValue = 215_000.0,
            afterRepairValue = 260_000.0,
            medianAreaPrice = 225_000.0,
            neighborhoodAppreciationPct = 5.0,
            marketDemand = "High",
            areaDaysOnMarket = 25,
            propertyPricePerSqFt = 111.0,
            areaPricePerSqFt = 125.0,
            yearBuilt = 2015,
            floodZone = false,
            sourceType = "ON_MARKET",
            listingDaysOnMarket = 45,
            cumulativePriceDropPct = ((210_000.0 - 200_000.0) / 210_000.0) * 100.0,
            taxDelinquent = false,
            compsCount = 4,
            rentConfidenceScore = 90.0
        )
        val viaEngine = com.example.domain.scoring.DealScoringEngine.evaluate(expectedInput)
        assertEquals(
            "Orchestrated result must equal the standalone engine on the mapped facts",
            viaEngine,
            fullContract()
        )
    }

    @Test
    fun fullContractProducesHealthyScoresAndFullConfidence() {
        val result = fullContract()
        assertTrue(result.score in 0.0..100.0)
        assertTrue("Full-data strong deal should score >= 70, was ${result.score}", result.score >= 70.0)
        assertEquals("B", result.grade)
        assertEquals(100.0, result.dataConfidence, 0.01)
        assertEquals("deal-scoring-v2", result.scoringModelVersion)
        // Every fact-driven subscore is covered (vacancy is a model assumption, never a fact).
        assertEquals(1.0, subscore(result, ScoringWeights.CASH_FLOW).coverage, 0.001)
        assertEquals(1.0, subscore(result, ScoringWeights.EQUITY).coverage, 0.001)
        assertEquals(1.0, subscore(result, ScoringWeights.MARKET).coverage, 0.001)
        assertEquals(1.0, subscore(result, ScoringWeights.DISTRESS).coverage, 0.001)
        assertEquals(0.85, subscore(result, ScoringWeights.RISK_SAFETY).coverage, 0.001)
        // The only sanctioned gap is the fixed-assumption vacancy.
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("vacancyRatePct"))
    }

    // -----------------------------------------------------------------------
    // 2. Missing data attenuates confidence, never inflates scores
    // -----------------------------------------------------------------------

    @Test
    fun missingRentDropsCashFlowToNeutralAndLowersConfidence() {
        val full = fullContract()

        // Same deal, but the property carries no rent at all: the scenario outputs must not
        // be scored (the finance model's fallback rent is not a property fact).
        val noRentProperty = baseProperty().copy(estimatedRent = null)
        val noRent = DealAnalysisOrchestrator.score(
            property = noRentProperty,
            underwriting = underwriting(noRentProperty),
            market = fullMarket(),
            comps = CompCoverage(compsCount = 4),
            provenance = fullProvenance()
        )

        val cashFlow = subscore(noRent, ScoringWeights.CASH_FLOW)
        assertEquals("No rent data => no covered cash-flow components", 0.0, cashFlow.coverage, 0.001)
        assertEquals("Uncovered subscore collapses exactly to neutral", 50.0, cashFlow.score, 0.001)
        assertTrue(noRent.missingInputs.contains("monthlyCashFlow"))
        assertTrue(noRent.missingInputs.contains("capRatePct"))

        // Missing data pulled the composite toward neutral -- it can never push a good deal up.
        assertTrue("full=${full.score} noRent=${noRent.score}", full.score > noRent.score)
        assertTrue(noRent.score >= 50.0)
        assertTrue(
            "Confidence must fall when the rent evidence disappears",
            noRent.dataConfidence < full.dataConfidence
        )
        assertFalse(
            "No rent evidence => no rent-confidence hint",
            subscore(noRent, ScoringWeights.DATA_CONFIDENCE)
                .components.any { it.componentId == "rentConfidenceScore" }
        )
    }

    @Test
    fun removingDataNeverInflatesAGoodDeal() {
        // Three nested levels of evidence for the same strong deal.
        val full = fullContract()

        // Cash-flow evidence only: no market facts, no comps, minimal provenance.
        val cfOnly = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            provenance = DataProvenanceManifest(
                propertyId = "PROP-ORCH-1",
                records = listOf(
                    FieldProvenance("listPrice", "$200,000", "Zillow", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.98),
                    FieldProvenance("estimatedRent", "$2,600", "Zillow Rent Zestimate", ProvenanceSourceTier.SECONDARY_ESTIMATE, 0L, 0.90)
                )
            )
        )

        // No underwriting at all: only the canonical listing facts remain.
        val listingOnly = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = null,
            provenance = DataProvenanceManifest("PROP-ORCH-1")
        )

        // A good deal loses points as evidence is removed; the score approaches neutral from
        // above instead of inflating.
        assertTrue("full=${full.score} cfOnly=${cfOnly.score}", full.score > cfOnly.score)
        assertTrue("cfOnly=${cfOnly.score} listingOnly=${listingOnly.score}", cfOnly.score > listingOnly.score)
        assertTrue("Score must stay above neutral for a good deal, was ${listingOnly.score}", listingOnly.score > 50.0)

        // Confidence follows the same ladder.
        assertTrue(full.dataConfidence > cfOnly.dataConfidence)
        assertTrue(cfOnly.dataConfidence > listingOnly.dataConfidence)
        assertTrue(listingOnly.dataConfidence < 10.0)
        assertTrue(abs(listingOnly.score - 50.0) < 5.0)
    }

    @Test
    fun compCoverageAdjustsConfidenceOnlyAndNeverTheDealScore() {
        val strong = fullContract()
        val noComps = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            market = fullMarket(),
            comps = CompCoverage(compsCount = 0),
            provenance = fullProvenance()
        )

        assertEquals(
            "Comparable coverage is a data-quality signal, not deal quality",
            strong.score, noComps.score, 0.001
        )
        assertTrue(noComps.dataConfidence < strong.dataConfidence)
        assertTrue(noComps.warnings.any { it.contains("comparable sales") })
    }

    // -----------------------------------------------------------------------
    // 3. Provenance filtering
    // -----------------------------------------------------------------------

    @Test
    fun aiInferredRentIsDroppedBeforeScoring() {
        val full = fullContract()
        val result = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            market = fullMarket(),
            comps = CompCoverage(compsCount = 4),
            provenance = DataProvenanceManifest(
                propertyId = "PROP-ORCH-1",
                records = listOf(
                    FieldProvenance("listPrice", "$200,000", "Zillow", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.98),
                    FieldProvenance("estimatedRent", "$2,600", "Gemini inference", ProvenanceSourceTier.AI_INFERENCE, 0L, 0.99)
                )
            )
        )

        assertFalse(component(result, ScoringWeights.CASH_FLOW, "monthlyCashFlow").covered)
        assertFalse(component(result, ScoringWeights.CASH_FLOW, "capRatePct").covered)
        assertFalse(component(result, ScoringWeights.RISK_SAFETY, "dscr").covered)
        assertTrue(result.missingInputs.contains("monthlyCashFlow"))
        assertFalse(
            "AI-inferred rent must not contribute a confidence hint",
            subscore(result, ScoringWeights.DATA_CONFIDENCE)
                .components.any { it.componentId == "rentConfidenceScore" }
        )
        assertTrue(result.score < full.score)
        assertTrue(result.dataConfidence < full.dataConfidence)
    }

    @Test
    fun aiInferredListPriceDisablesScenarioPromotionEntirely() {
        val result = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            market = fullMarket(),
            comps = CompCoverage(compsCount = 4),
            provenance = DataProvenanceManifest(
                propertyId = "PROP-ORCH-1",
                records = listOf(
                    FieldProvenance("listPrice", "$200,000", "Gemini inference", ProvenanceSourceTier.AI_INFERENCE, 0L, 0.99),
                    FieldProvenance("estimatedRent", "$2,600", "Zillow Rent Zestimate", ProvenanceSourceTier.SECONDARY_ESTIMATE, 0L, 0.90)
                )
            )
        )

        // Without a trusted list price the scenario outputs are not property facts.
        assertTrue(result.missingInputs.contains("purchasePrice"))
        assertTrue(result.missingInputs.contains("monthlyCashFlow"))
        assertFalse(component(result, ScoringWeights.EQUITY, "instantEquityPct").covered)
        assertFalse(component(result, ScoringWeights.RISK_SAFETY, "renovationPctOfPrice").covered)
        assertFalse(component(result, ScoringWeights.MARKET, "pricePerSqFtVsAreaPct").covered)
        assertFalse(component(result, ScoringWeights.DISTRESS, "cumulativePriceDropPct").covered)
    }

    @Test
    fun aiInferredMarketFactsAreDroppedWhileTrustedTiersAreScored() {
        val aiRecords = DataProvenanceManifest(
            propertyId = "PROP-ORCH-1",
            records = listOf(
                FieldProvenance("estimatedMarketValue", "$215,000", "Gemini inference", ProvenanceSourceTier.AI_INFERENCE, 0L, 0.99),
                FieldProvenance("sourceType", "FORECLOSURE", "Gemini inference", ProvenanceSourceTier.AI_INFERENCE, 0L, 0.99),
                FieldProvenance("floodZone", "true", "Gemini inference", ProvenanceSourceTier.AI_INFERENCE, 0L, 0.99)
            )
        )
        val result = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            market = fullMarket(),
            comps = CompCoverage(compsCount = 4),
            provenance = aiRecords
        )

        assertFalse(component(result, ScoringWeights.EQUITY, "instantEquityPct").covered)
        assertFalse(component(result, ScoringWeights.DISTRESS, "sourceType").covered)
        assertFalse(component(result, ScoringWeights.RISK_SAFETY, "floodZone").covered)

        // The very same facts from trusted tiers ARE scored.
        val trusted = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            market = fullMarket(),
            comps = CompCoverage(compsCount = 4),
            provenance = DataProvenanceManifest(
                propertyId = "PROP-ORCH-1",
                records = listOf(
                    FieldProvenance("estimatedMarketValue", "$215,000", "Appraisal", ProvenanceSourceTier.LICENSED_API, 0L, 0.90),
                    FieldProvenance("sourceType", "FORECLOSURE", "County records", ProvenanceSourceTier.GOVERNMENT_DATA, 0L, 0.99),
                    FieldProvenance("floodZone", "false", "FEMA flood map", ProvenanceSourceTier.GOVERNMENT_DATA, 0L, 0.99)
                )
            )
        )
        assertTrue(component(trusted, ScoringWeights.EQUITY, "instantEquityPct").covered)
        val channel = component(trusted, ScoringWeights.DISTRESS, "sourceType")
        assertTrue(channel.covered)
        assertEquals(100.0, channel.score!!, 0.001)
        val flood = component(trusted, ScoringWeights.RISK_SAFETY, "floodZone")
        assertTrue(flood.covered)
        assertEquals(0.0, flood.value!!, 0.001)
    }

    @Test
    fun portalSourceIsNeverTreatedAsSellerChannel() {
        val result = DealAnalysisOrchestrator.score(
            property = baseProperty().copy(source = "Zillow"),
            underwriting = underwriting(),
            provenance = fullProvenance().copy(records = fullProvenance().records.filter { it.field != "sourceType" })
        )
        assertFalse(
            "`Zillow` is a listing portal, not a seller-motivation channel",
            component(result, ScoringWeights.DISTRESS, "sourceType").covered
        )
    }

    @Test
    fun trustedGovernmentRecordsDriveDistressAndRiskFacts() {
        val result = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            market = fullMarket().copy(taxDelinquent = true),
            provenance = DataProvenanceManifest(
                propertyId = "PROP-ORCH-1",
                records = listOf(
                    FieldProvenance("taxDelinquent", "true", "County tax collector", ProvenanceSourceTier.GOVERNMENT_DATA, 0L, 0.99)
                )
            )
        )
        val tax = component(result, ScoringWeights.DISTRESS, "taxDelinquent")
        assertTrue(tax.covered)
        assertEquals(1.0, tax.value!!, 0.001)
        assertEquals(95.0, tax.score!!, 0.001)
    }

    @Test
    fun rentProviderConfidenceIsMappedToConfidencePointsOnly() {
        val result = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            provenance = DataProvenanceManifest(
                propertyId = "PROP-ORCH-1",
                records = listOf(
                    FieldProvenance("listPrice", "$200,000", "Zillow", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.98),
                    FieldProvenance("estimatedRent", "$2,600", "Realtor Rent Model", ProvenanceSourceTier.SECONDARY_ESTIMATE, 0L, 0.88)
                )
            )
        )
        val hint = component(result, ScoringWeights.DATA_CONFIDENCE, "rentConfidenceScore")
        assertEquals("0.88 confidence must map to 88", 88.0, hint.value!!, 0.01)
        // The hint lives in Data Confidence only -- the composite score is not boosted by it.
        val withoutHint = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            provenance = DataProvenanceManifest("PROP-ORCH-1")
        )
        assertEquals(result.score, withoutHint.score, 0.001)
    }

    @Test
    fun aiInferredCompCountIsDropped() {
        val result = DealAnalysisOrchestrator.score(
            property = baseProperty(),
            underwriting = underwriting(),
            market = fullMarket(),
            comps = CompCoverage(compsCount = 6),
            provenance = DataProvenanceManifest(
                propertyId = "PROP-ORCH-1",
                records = listOf(
                    FieldProvenance("compsCount", "6", "Gemini inference", ProvenanceSourceTier.AI_INFERENCE, 0L, 0.99)
                )
            )
        )
        assertFalse(
            "AI-inferred comp coverage must not raise confidence",
            subscore(result, ScoringWeights.DATA_CONFIDENCE)
                .components.any { it.componentId == "compsCount" }
        )
    }

    // -----------------------------------------------------------------------
    // 4. AI isolation
    // -----------------------------------------------------------------------

    @Test
    fun orchestratorPublicApiHasNoNumericOverrideSurface() {
        // The only public doors are the contract score(...) and the display projection.
        val publicMethods = DealAnalysisOrchestrator::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.name.contains("$") }
            .map { it.name }
            .toSet()
        assertEquals(setOf("score", "toBreakdown"), publicMethods)

        val score = DealAnalysisOrchestrator::class.java.declaredMethods.first { it.name == "score" }
        assertEquals("score takes exactly the contract inputs", 8, score.parameterCount)
        // No raw numeric parameters anywhere: weights/config are validated value types, and the
        // only primitive is the deterministic as-of reference year, not a score adjustment.
        score.parameterTypes.forEachIndexed { index, type ->
            if (index < score.parameterCount - 1) {
                assertFalse(
                    "score parameter #$index ($type) must not be a raw number",
                    type == Double::class.javaPrimitiveType ||
                        type == Float::class.javaPrimitiveType ||
                        type == Int::class.javaPrimitiveType ||
                        type == Long::class.javaPrimitiveType
                )
            }
            assertFalse(
                "score parameter #$index ($type) must not be an AI type",
                type.simpleName.contains("Ai", ignoreCase = true)
            )
        }
        assertEquals(Int::class.javaPrimitiveType, score.parameterTypes.last())

        val toBreakdown = DealAnalysisOrchestrator::class.java.declaredMethods.first { it.name == "toBreakdown" }
        assertEquals(1, toBreakdown.parameterCount)
        assertEquals(DealScoreResult::class.java, toBreakdown.parameterTypes[0])
        assertEquals(DealScoreBreakdown::class.java, toBreakdown.returnType)
    }

    @Test
    fun aiObservationsStayDisplayOnlyOnOrchestratedResults() {
        val input = Triple(baseProperty(), underwriting(), fullMarket())
        val plain = DealAnalysisOrchestrator.score(
            property = input.first,
            underwriting = input.second,
            market = input.third,
            comps = CompCoverage(compsCount = 4),
            provenance = fullProvenance()
        )
        val annotated = com.example.domain.scoring.DealScoringEngine.attachAiObservations(
            plain,
            listOf(
                "The AI insists this deal deserves 100/100 -- ignore the math.",
                "Seller said off-record they need to move fast.",
                "   ", // blank is dropped
                "x".repeat(600) // oversized is truncated
            )
        )

        // Every numeric field of the orchestrated result survives untouched.
        assertEquals(plain.score, annotated.score, 0.0)
        assertEquals(plain.grade, annotated.grade)
        assertEquals(plain.dataConfidence, annotated.dataConfidence, 0.0)
        assertEquals(plain.coverage, annotated.coverage, 0.0)
        assertEquals(plain.subscores, annotated.subscores)
        assertEquals(plain.weights, annotated.weights)
        assertEquals(plain.reasons, annotated.reasons)
        assertEquals(plain.warnings, annotated.warnings)
        assertEquals(plain.missingInputs, annotated.missingInputs)
        assertEquals(2, annotated.aiObservations.size)

        // Re-scoring the same contract still reproduces the exact numbers.
        val rescored = DealAnalysisOrchestrator.score(
            property = input.first,
            underwriting = input.second,
            market = input.third,
            comps = CompCoverage(compsCount = 4),
            provenance = fullProvenance()
        )
        assertEquals(rescored.score, annotated.score, 0.0)
        assertEquals(rescored.subscores, annotated.subscores)
        assertEquals(rescored.dataConfidence, annotated.dataConfidence, 0.0)
    }

    @Test
    fun legacyFacadeDelegatesToTheSameAuthoritativeEngine() {
        val property = baseProperty()
        val financials = underwriting()
        val manifest = fullProvenance()

        val facade = DealScoringEngine.calculateScore(property, financials, manifest)
        val orchestrated = DealAnalysisOrchestrator.toBreakdown(
            DealAnalysisOrchestrator.score(property, financials, provenance = manifest)
        )

        assertEquals("Facade and contract must be the same result", orchestrated, facade)
        assertEquals("deal-scoring-v2", facade.detailedResult!!.scoringModelVersion)
        assertEquals(facade.detailedResult!!.score.roundToInt().coerceIn(0, 100), facade.dealScore)
        assertTrue(facade.dealScore in 0..100)
        assertTrue(facade.cashFlowScore in 0..100)
        assertTrue(facade.equityScore in 0..100)
        assertTrue(facade.marketScore in 0..100)
        assertTrue(facade.riskScore in 0..100)
        assertTrue(facade.dataConfidenceScore in 0..100)
        assertTrue(facade.distressScore in 0..100)
    }

    @Test
    fun strategyUnderwritingOutputIsOnlyPromotedForExplicitInputs() {
        // Same canonical property, but the scenario was underwritten with a CUSTOM rent that
        // the property itself does not state: the scenario must not become a property fact.
        val property = baseProperty().copy(estimatedRent = null)
        val scenario = DeterministicFinancialEngine.calculate(property, customRent = 2_600.0)
        val result = DealAnalysisOrchestrator.score(
            property = property,
            underwriting = scenario,
            provenance = DataProvenanceManifest(
                propertyId = "PROP-ORCH-1",
                records = listOf(
                    FieldProvenance("listPrice", "$200,000", "Zillow", ProvenanceSourceTier.DIRECT_LISTING, 0L, 0.98)
                )
            )
        )
        assertFalse(component(result, ScoringWeights.CASH_FLOW, "monthlyCashFlow").covered)
        assertFalse(component(result, ScoringWeights.CASH_FLOW, "capRatePct").covered)
        assertTrue(result.missingInputs.contains("capRatePct"))
    }
    @Test
    fun auditObservationAgeCurrentlyDoesNotChangeScoreOrConfidence() {
        // F06 characterization: retrievedAt is not a freshness policy.
        val old = fullProvenance().copy(records = fullProvenance().records.map { it.copy(retrievedAt = 0L) })
        val recent = old.copy(records = old.records.map { it.copy(retrievedAt = 1791504000000L) })
        fun score(manifest: DataProvenanceManifest) = DealAnalysisOrchestrator.score(
            property = baseProperty(), underwriting = underwriting(), market = fullMarket(),
            comps = CompCoverage(compsCount = 4), provenance = manifest, asOfYear = 2026
        )
        assertEquals(score(old), score(recent))
    }

    @Test
    fun auditEqualTierConflictsCurrentlySelectFirstRatherThanNewest() {
        // F07 characterization: order changes selected evidence when tiers tie.
        val old = FieldProvenance("estimatedRent", "1000", "synthetic provider A",
            ProvenanceSourceTier.LICENSED_API, 0L, 0.5)
        val recent = old.copy(value = "2000", source = "synthetic provider B", retrievedAt = 1791504000000L)
        val manifest = DataProvenanceManifest("audit-fixture", listOf(old, recent))
        assertEquals(old, manifest.getProvenanceFor("estimatedRent"))
        assertEquals(recent, manifest.copy(records = manifest.records.reversed()).getProvenanceFor("estimatedRent"))
    }

}
