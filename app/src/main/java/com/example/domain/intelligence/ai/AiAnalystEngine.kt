package com.example.domain.intelligence.ai

import com.example.domain.ai.GeminiManager
import com.example.domain.intelligence.model.*
import com.example.domain.scoring.ScoringWeights
import java.util.Locale
import org.json.JSONObject

class AiAnalystEngine(
    private val geminiManager: GeminiManager
) {

    suspend fun analyzeProperty(
        property: CanonicalProperty,
        financials: StrategyFinancialMetrics,
        score: DealScoreBreakdown
    ): AiAnalystResult {
        val prompt = buildStructuredPrompt(property, financials, score)
        val systemInstruction = """
            You are a Wall Street real estate investment analyst and underwriting director.
            Analyze the subject property against financial underwriting metrics and market conditions.
            The deterministic Deal Score and its component breakdown are authoritative: never recalculate,
            replace, or claim to improve any numeric score. Distress is an opportunity signal, not deal quality;
            missing or weakly covered distress evidence must remain UNKNOWN and must not alone justify WHOLESALE.
            A property portal/source name is not evidence of seller motivation. Do not invent missing facts.
            You MUST strictly classify each key finding as one of:
            - "FACT" (verified listing data, public recorded records, actual taxes)
            - "ESTIMATE" (rental comps, valuation models, depreciation benchmarks)
            - "INFERENCE" (potential upside, negotiation posture, ADU potential)
            - "UNKNOWN" (missing data, unverified condition, hidden costs)
            
            Return raw strict JSON without markdown formatting or code fences:
            {
              "summary": "1-2 sentence executive briefing",
              "investmentThesis": "2-3 sentences on financial viability, with unsupported inputs called out",
              "strengths": ["...", "..."],
              "weaknesses": ["...", "..."],
              "risks": ["...", "..."],
              "redFlags": ["...", "..."],
              "recommendedStrategy": "BUY_AND_HOLD" | "BRRRR" | "FIX_AND_FLIP" | "WHOLESALE",
              "recommendedOfferRange": "$420,000 - $445,000",
              "questionsForSeller": ["...", "..."],
              "dueDiligenceItems": ["...", "..."],
              "confidence": 0.90,
              "evidence": [
                {"category": "List Price", "statement": "Source-supported asking price, if available", "classification": "FACT"},
                {"category": "Monthly Rent", "statement": "Explicitly sourced rent estimate, if available", "classification": "ESTIMATE"},
                {"category": "Negotiation", "statement": "Use days-on-market evidence only as a limited inference; otherwise report unknown", "classification": "UNKNOWN"}
              ]
            }
        """.trimIndent()

        val response = geminiManager.generateContent(prompt, systemInstruction)
        if (response.success && response.text.isNotBlank()) {
            val parsed = parseAiOutput(response.text)
            if (parsed != null) return parsed
        }

        // Deterministic Fallback if Gemini is unavailable
        return buildDeterministicAnalysis(property, financials, score)
    }

    private fun buildStructuredPrompt(
        property: CanonicalProperty,
        financials: StrategyFinancialMetrics,
        score: DealScoreBreakdown
    ): String {
        val detailed = score.detailedResult
        val cashFlowCoverage = detailed?.subscores
            ?.firstOrNull { it.id == ScoringWeights.CASH_FLOW }
            ?.coverage
        val distressCoverage = detailed?.subscores
            ?.firstOrNull { it.id == ScoringWeights.DISTRESS }
            ?.coverage
        return """
            [PROPERTY SUMMARY]
            Address: ${property.address}
            Asking Price: $${financials.purchasePrice}
            Type: ${property.propertyType} | ${property.bedrooms} Bed / ${property.bathrooms} Bath | ${property.squareFeet} SqFt
            Year Built: ${property.yearBuilt} | Days on Market: ${property.daysOnMarket ?: "Unknown"}
            
            [FINANCIAL UNDERWRITING (DETERMINISTIC SCENARIO)]
            Strategy: ${financials.strategy.name} | Financing: ${financials.financingType.name}
            Rent evidence used by the deterministic score: ${if ((cashFlowCoverage ?: 0.0) > 0.0) "present" else "missing; any displayed finance-model fallback is not a property fact"}
            Monthly Rent: $${financials.grossMonthlyRent}
            Net Operating Income (Annual): $${financials.netOperatingIncomeAnnual}
            Monthly Cash Flow: $${financials.monthlyCashFlow}
            Cap Rate: ${String.format(Locale.US, "%.2f", financials.capRate)}%
            Cash-on-Cash Return: ${String.format(Locale.US, "%.2f", financials.cashOnCashReturn)}%
            DSCR: ${String.format(Locale.US, "%.2f", financials.dscr)}
            Total Cash Required: $${financials.totalCashRequired}
            
            [AUTHORITATIVE DETERMINISTIC SCORES — DO NOT RECALCULATE OR OVERRIDE]
            Model: ${detailed?.scoringModelVersion ?: "legacy/unknown"}
            Deal: ${score.dealScore}/100 | Cash Flow: ${score.cashFlowScore}/100 | Equity: ${score.equityScore}/100
            Market: ${score.marketScore}/100 | Risk/Safety: ${score.riskScore}/100 | Data Confidence: ${score.dataConfidenceScore}/100
            Distress Opportunity (not deal quality): ${score.distressScore}/100; evidence coverage: ${distressCoverage ?: "unknown"}
            Cash Flow evidence coverage: ${cashFlowCoverage ?: "unknown"}
            Positive Drivers: ${score.positiveFactors.joinToString("; ")}
            Negative Constraints: ${score.negativeFactors.joinToString("; ")}
        """.trimIndent()
    }

    private fun parseAiOutput(rawText: String): AiAnalystResult? {
        return try {
            val clean = rawText.replace("```json", "").replace("```", "").trim()
            val obj = JSONObject(clean)

            fun optStringList(key: String): List<String> {
                val arr = obj.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).map { arr.getString(it) }
            }

            val evidenceList = mutableListOf<ClassifiedKnowledgeItem>()
            val evidenceArray = obj.optJSONArray("evidence")
            if (evidenceArray != null) {
                for (i in 0 until evidenceArray.length()) {
                    val item = evidenceArray.getJSONObject(i)
                    val cat = item.optString("category", "General")
                    val stmt = item.optString("statement", "")
                    val clsStr = item.optString("classification", "INFERENCE").uppercase(Locale.US)
                    val cls = try { KnowledgeType.valueOf(clsStr) } catch (e: Exception) { KnowledgeType.INFERENCE }
                    evidenceList.add(ClassifiedKnowledgeItem(cat, stmt, cls))
                }
            }

            AiAnalystResult(
                summary = obj.optString("summary", "Property underwriting summary unavailable."),
                investmentThesis = obj.optString("investmentThesis", "Insufficient supported information for an investment thesis."),
                strengths = optStringList("strengths"),
                weaknesses = optStringList("weaknesses"),
                risks = optStringList("risks"),
                redFlags = optStringList("redFlags"),
                recommendedStrategy = obj.optString("recommendedStrategy", "BUY_AND_HOLD"),
                recommendedOfferRange = obj.optString("recommendedOfferRange", "Unavailable; do not infer from missing data"),
                questionsForSeller = optStringList("questionsForSeller"),
                dueDiligenceItems = optStringList("dueDiligenceItems"),
                confidence = obj.optDouble("confidence", 0.0)
                    .takeIf { it.isFinite() }
                    ?.coerceIn(0.0, 1.0)
                    ?: 0.0,
                evidence = evidenceList
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildDeterministicAnalysis(
        property: CanonicalProperty,
        financials: StrategyFinancialMetrics,
        score: DealScoreBreakdown
    ): AiAnalystResult {
        val price = financials.purchasePrice.takeIf { it.isFinite() && it > 0.0 }
        val detailed = score.detailedResult
        val distressCoverage = detailed?.subscores
            ?.firstOrNull { it.id == ScoringWeights.DISTRESS }
            ?.coverage
        val hasStrongDistressEvidence = distressCoverage != null && distressCoverage >= 0.75 &&
            score.distressScore >= 75
        val cashFlowCoverage = detailed?.subscores
            ?.firstOrNull { it.id == ScoringWeights.CASH_FLOW }
            ?.coverage ?: 0.0
        val supportedRent = property.estimatedRent
            ?.takeIf { it.isFinite() && it > 0.0 && cashFlowCoverage > 0.0 }
        val offerLow = price?.times(0.92)
        val offerHigh = price?.times(0.96)
        val taxAuthority = property.county?.takeIf { it.isNotBlank() } ?: "local tax authority"
        val daysOnMarket = property.daysOnMarket?.takeIf { it >= 0 }

        val strategy = if (hasStrongDistressEvidence) "WHOLESALE" else "BUY_AND_HOLD"

        val evidence = buildList {
            if (price != null) {
                add(ClassifiedKnowledgeItem(
                    "Asking Price", "Canonical listing asking price of ${'$'}${formatUs("%,.0f", price)}", KnowledgeType.FACT
                ))
            } else {
                add(ClassifiedKnowledgeItem("Asking Price", "A finite positive asking price is unavailable.", KnowledgeType.UNKNOWN))
            }
            add(ClassifiedKnowledgeItem("Address", property.address, KnowledgeType.FACT))
            if (supportedRent != null) {
                add(ClassifiedKnowledgeItem(
                    "Market Rent", "Canonical rent estimate of ${'$'}${formatUs("%,.0f", supportedRent)}/mo", KnowledgeType.ESTIMATE
                ))
            } else {
                add(ClassifiedKnowledgeItem(
                    "Market Rent", "Rent is unavailable as a score-supported input; do not treat finance-model fallback rent as property evidence.",
                    KnowledgeType.UNKNOWN
                ))
            }
            if (financials.capRate.isFinite()) {
                add(ClassifiedKnowledgeItem(
                    "Cap Rate", "Underwritten capitalization rate of ${formatUs("%.1f", financials.capRate)}% under the stated scenario.",
                    KnowledgeType.ESTIMATE
                ))
            } else {
                add(ClassifiedKnowledgeItem("Cap Rate", "Cap rate is unavailable because the value is non-finite.", KnowledgeType.UNKNOWN))
            }
            when {
                offerLow != null && daysOnMarket != null -> add(ClassifiedKnowledgeItem(
                    "Negotiation", "An illustrative initial offer near ${'$'}${formatUs("%,.0f", offerLow)}; $daysOnMarket listing days may inform discussion but do not prove seller motivation.",
                    KnowledgeType.INFERENCE
                ))
                offerLow != null -> add(ClassifiedKnowledgeItem(
                    "Negotiation", "An illustrative offer near ${'$'}${formatUs("%,.0f", offerLow)}; days on market and seller motivation are unknown.",
                    KnowledgeType.INFERENCE
                ))
                else -> add(ClassifiedKnowledgeItem(
                    "Negotiation", "Offer guidance is unavailable until a finite positive asking price is provided.", KnowledgeType.UNKNOWN
                ))
            }
            if (hasStrongDistressEvidence) {
                add(ClassifiedKnowledgeItem(
                    "Distress", "Distress score ${score.distressScore}/100 with ${formatUs("%.0f", distressCoverage!! * 100.0)}% evidence coverage; verify seller circumstances before acting.",
                    KnowledgeType.INFERENCE
                ))
            } else {
                add(ClassifiedKnowledgeItem(
                    "Distress", "Distress evidence is weak or incomplete; seller motivation is unknown.", KnowledgeType.UNKNOWN
                ))
            }
        }

        val propertyTaxEvidence = property.propertyTax
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { "Annual property-tax estimate of ${'$'}${formatUs("%,.0f", it)}" }
            ?: "Property tax is unavailable; verify with the $taxAuthority"
        val cashFlowText = if (financials.monthlyCashFlow.isFinite()) {
            "${'$'}${formatUs("%,.0f", financials.monthlyCashFlow)}/mo"
        } else {
            "unavailable"
        }
        val capRateText = if (financials.capRate.isFinite()) "${formatUs("%.1f", financials.capRate)}%" else "unavailable"
        val dscrText = if (financials.dscr.isFinite()) "${formatUs("%.2f", financials.dscr)}x" else "unavailable"
        val offerRange = if (offerLow != null && offerHigh != null) {
            "${'$'}${formatUs("%,.0f", offerLow)} - ${'$'}${formatUs("%,.0f", offerHigh)} (illustrative; not score output)"
        } else {
            "Unavailable — finite positive asking price required"
        }
        val strategyConfidence = when {
            supportedRent != null && hasStrongDistressEvidence -> 0.82
            supportedRent != null -> 0.72
            price != null -> 0.55
            else -> 0.30
        }

        return AiAnalystResult(
            summary = if (supportedRent != null && financials.cashOnCashReturn.isFinite()) {
                "Property located at ${property.address} has underwritten cash-on-cash potential of ${formatUs("%.1f", financials.cashOnCashReturn)}% under the stated scenario."
            } else {
                "Property located at ${property.address}; rent support is incomplete, so the displayed financial scenario should not be treated as verified property performance."
            },
            investmentThesis = "Conservative fallback recommendation: ${strategy.replace("_", " ")}. " +
                "Deterministic Deal Score remains authoritative; cash flow is $cashFlowText, cap rate is $capRateText, and DSCR is $dscrText.",
            strengths = buildList {
                if (financials.capRate.isFinite()) add("Scenario cap rate of $capRateText; validate underlying estimates before relying on it")
                if (financials.totalCashRequired.isFinite() && financials.totalCashRequired >= 0.0) {
                    add("Scenario total initial capital requirement of ${'$'}${formatUs("%,.0f", financials.totalCashRequired)}")
                }
            },
            weaknesses = listOf(
                propertyTaxEvidence,
                "Underwriting uses a 5% vacancy assumption; verify local leasing and vacancy data"
            ),
            risks = listOf(
                "Interest-rate and refinancing sensitivity under the selected financing scenario",
                "Local tenant demand and placement timing have not been independently verified"
            ),
            redFlags = if (financials.monthlyCashFlow.isFinite() && financials.monthlyCashFlow < 0.0) {
                listOf("Negative monthly cash flow under current financing assumptions")
            } else {
                emptyList()
            },
            recommendedStrategy = strategy,
            recommendedOfferRange = offerRange,
            questionsForSeller = listOf(
                "Age of current roof and HVAC systems?",
                "Are there any transferable foundation or roof warranties?",
                "Has the property been owner-occupied or tenant-leased?"
            ),
            dueDiligenceItems = listOf(
                "Schedule comprehensive mechanical and structural inspection",
                "Verify property-tax assessment with the $taxAuthority",
                "Review title report for liens, easements, and other recorded encumbrances"
            ),
            confidence = strategyConfidence,
            evidence = evidence
        )
    }

    private fun formatUs(pattern: String, value: Double): String =
        if (value.isFinite()) String.format(Locale.US, pattern, value) else "unavailable"
}
