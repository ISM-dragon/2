package com.example.domain.intelligence.model

import com.example.domain.scoring.DealScoreResult

data class DealScoreBreakdown(
    val dealScore: Int, // 0 to 100; rounded from the deterministic scoring result
    val cashFlowScore: Int,
    val equityScore: Int,
    val marketScore: Int,
    val riskScore: Int,
    val dataConfidenceScore: Int,
    val distressScore: Int,
    val positiveFactors: List<String>,
    val negativeFactors: List<String>,
    /** Full immutable deterministic trace retained for consumers that need UI/export detail. */
    val detailedResult: DealScoreResult? = null
)

enum class KnowledgeType {
    FACT,
    ESTIMATE,
    INFERENCE,
    UNKNOWN
}

data class ClassifiedKnowledgeItem(
    val category: String,
    val statement: String,
    val classification: KnowledgeType
)

data class AiAnalystResult(
    val summary: String,
    val investmentThesis: String,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val risks: List<String>,
    val redFlags: List<String>,
    val recommendedStrategy: String, // "BUY_AND_HOLD", "BRRRR", "FIX_AND_FLIP", "WHOLESALE"
    val recommendedOfferRange: String,
    val questionsForSeller: List<String>,
    val dueDiligenceItems: List<String>,
    val confidence: Double,
    val evidence: List<ClassifiedKnowledgeItem>
)
