package com.example.domain.intelligence.scoring

import com.example.domain.intelligence.model.CanonicalProperty
import com.example.domain.intelligence.model.DataProvenanceManifest
import com.example.domain.intelligence.model.DealScoreBreakdown
import com.example.domain.intelligence.model.StrategyFinancialMetrics

/**
 * Compatibility facade for the property-import pipeline.
 *
 * All fact mapping, provenance filtering and score arithmetic live in
 * [DealAnalysisOrchestrator], which delegates every numeric decision to the standalone
 * deterministic [com.example.domain.scoring.DealScoringEngine]. This object only adapts the
 * legacy three-argument call signature; it performs no scoring math of its own, so the import
 * pipeline and the orchestration contract can never drift apart.
 *
 * The mapping preserves the long-standing policy: portal names are not mistaken for seller
 * motivation, generated enrichment/comps are not treated as verified facts, the legacy
 * finance-model fallback rent and fixed assumptions are not promoted into scoring inputs,
 * and AI-inferred fields stay absent.
 */
object DealScoringEngine {

    fun calculateScore(
        property: CanonicalProperty,
        financials: StrategyFinancialMetrics,
        provenance: DataProvenanceManifest
    ): DealScoreBreakdown = DealAnalysisOrchestrator.toBreakdown(
        DealAnalysisOrchestrator.score(
            property = property,
            underwriting = financials,
            provenance = provenance
        )
    )
}
