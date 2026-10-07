package com.example.domain.intelligence.ai

import com.example.domain.ai.GeminiManager
import com.example.domain.intelligence.model.*
import org.json.JSONArray
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
            You MUST strictly classify each key finding as one of:
            - "FACT" (verified listing data, public recorded records, actual taxes)
            - "ESTIMATE" (rental comps, valuation models, depreciation benchmarks)
            - "INFERENCE" (potential upside, negotiation posture, ADU potential)
            - "UNKNOWN" (missing data, unverified condition, hidden costs)
            
            Return raw strict JSON without markdown formatting or code fences:
            {
              "summary": "1-2 sentence executive briefing",
              "investmentThesis": "2-3 sentences on financial viability",
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
                {"category": "List Price", "statement": "Official listed asking price of $485,000", "classification": "FACT"},
                {"category": "Monthly Rent", "statement": "Zestimate rental benchmark of $3,450/month", "classification": "ESTIMATE"},
                {"category": "Negotiation", "statement": "14 days on market indicates potential 4-6% discount margin", "classification": "INFERENCE"}
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
        return """
            [PROPERTY SUMMARY]
            Address: ${property.address}
            Asking Price: $${financials.purchasePrice}
            Type: ${property.propertyType} | ${property.bedrooms} Bed / ${property.bathrooms} Bath | ${property.squareFeet} SqFt
            Year Built: ${property.yearBuilt} | Days on Market: ${property.daysOnMarket ?: "Unknown"}
            
            [FINANCIAL UNDERWRITING (DETERMINISTIC ENGINE)]
            Strategy: ${financials.strategy.name} | Financing: ${financials.financingType.name}
            Monthly Rent: $${financials.grossMonthlyRent}
            Net Operating Income (Annual): $${financials.netOperatingIncomeAnnual}
            Monthly Cash Flow: $${financials.monthlyCashFlow}
            Cap Rate: ${String.format("%.2f", financials.capRate)}%
            Cash-on-Cash Return: ${String.format("%.2f", financials.cashOnCashReturn)}%
            DSCR: ${String.format("%.2f", financials.dscr)}
            Total Cash Required: $${financials.totalCashRequired}
            
            [DEAL SCORE: ${score.dealScore}/100]
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
                    val clsStr = item.optString("classification", "INFERENCE").uppercase()
                    val cls = try { KnowledgeType.valueOf(clsStr) } catch (e: Exception) { KnowledgeType.INFERENCE }
                    evidenceList.add(ClassifiedKnowledgeItem(cat, stmt, cls))
                }
            }

            AiAnalystResult(
                summary = obj.optString("summary", "Property underwriting complete."),
                investmentThesis = obj.optString("investmentThesis", "Strong rental asset in core submarket."),
                strengths = optStringList("strengths"),
                weaknesses = optStringList("weaknesses"),
                risks = optStringList("risks"),
                redFlags = optStringList("redFlags"),
                recommendedStrategy = obj.optString("recommendedStrategy", "BUY_AND_HOLD"),
                recommendedOfferRange = obj.optString("recommendedOfferRange", "$${obj.optString("listPrice")}"),
                questionsForSeller = optStringList("questionsForSeller"),
                dueDiligenceItems = optStringList("dueDiligenceItems"),
                confidence = obj.optDouble("confidence", 0.90),
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
        val offerLow = financials.purchasePrice * 0.92
        val offerHigh = financials.purchasePrice * 0.96

        val strategy = when {
            financials.cashOnCashReturn >= 8.0 -> "BUY_AND_HOLD"
            financials.estimatedRepairs > 20000.0 -> "BRRRR"
            score.distressScore > 40 -> "WHOLESALE"
            else -> "BUY_AND_HOLD"
        }

        val evidence = listOf(
            ClassifiedKnowledgeItem("Asking Price", "$${String.format("%,.0f", financials.purchasePrice)} official asking price", KnowledgeType.FACT),
            ClassifiedKnowledgeItem("Address", property.address, KnowledgeType.FACT),
            ClassifiedKnowledgeItem("Market Rent", "Estimated rent of $${String.format("%,.0f", financials.grossMonthlyRent)}/mo", KnowledgeType.ESTIMATE),
            ClassifiedKnowledgeItem("Cap Rate", "Underwritten capitalization rate of ${String.format("%.1f", financials.capRate)}%", KnowledgeType.ESTIMATE),
            ClassifiedKnowledgeItem("Negotiation", "Recommend initial offer at $${String.format("%,.0f", offerLow)} based on local days on market", KnowledgeType.INFERENCE)
        )

        return AiAnalystResult(
            summary = "Property located at ${property.address} shows ${if (financials.cashOnCashReturn > 6.0) "solid" else "moderate"} cash-on-cash potential of ${String.format("%.1f", financials.cashOnCashReturn)}%.",
            investmentThesis = "Ideal for ${strategy.replace("_", " ")} execution. Debt coverage is ${if (financials.dscr >= 1.25) "healthy" else "tight"} at ${String.format("%.2f", financials.dscr)}x DSCR.",
            strengths = listOf(
                "Cap Rate of ${String.format("%.1f", financials.capRate)}% aligns with submarket benchmarks",
                "Total initial capital requirement of $${String.format("%,.0f", financials.totalCashRequired)}"
            ),
            weaknesses = listOf(
                "Annual property tax estimate of $${String.format("%,.0f", property.propertyTax ?: 0.0)}",
                "5% vacancy reserve required for conservative underwriting"
            ),
            risks = listOf(
                "Interest rate sensitivity on floating or refinance debt",
                "Tenant placement timing during winter cycle"
            ),
            redFlags = if (financials.monthlyCashFlow < 0.0) listOf("Negative monthly cash flow under current financing assumptions") else emptyList(),
            recommendedStrategy = strategy,
            recommendedOfferRange = "$${String.format("%,.0f", offerLow)} - $${String.format("%,.0f", offerHigh)}",
            questionsForSeller = listOf(
                "Age of current roof and HVAC systems?",
                "Are there any transferable foundation or roof warranties?",
                "Has the property been owner-occupied or tenant-leased?"
            ),
            dueDiligenceItems = listOf(
                "Schedule comprehensive 4-point mechanical and structural inspection",
                "Verify Travis County property tax assessment and exemptions",
                "Review title report for municipal liens or utility easements"
            ),
            confidence = 0.88,
            evidence = evidence
        )
    }
}
