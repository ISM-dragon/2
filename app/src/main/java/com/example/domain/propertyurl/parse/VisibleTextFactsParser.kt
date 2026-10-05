package com.example.domain.propertyurl.parse

import com.example.domain.propertyurl.model.ExtractedFacts
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.model.RawSourceDocument

/**
 * Last-resort parser over the *visible* page text.
 *
 * Useful when a portal renders the listing server-side without JSON-LD or embedded state (common in
 * brokerage and county sites). Kept strictly behind guards: it only contributes when the text looks
 * like a listing and never wins a conflict against a structured parser (see `ProvenanceMethod`).
 */
class VisibleTextFactsParser(
    override val priority: Int = 40,
    private val maxCharacters: Int = 120_000
) : SourceDocumentParser {

    override val parserId: String = "visible-text"

    override val version: String = "1.0.0"

    override fun canParse(document: RawSourceDocument): Boolean = document.isHtml && document.hasBody

    override fun parse(document: RawSourceDocument, context: ParserContext): ParserResult {
        val text = HtmlScanner.visibleText(document.bodyOrEmpty.take(maxCharacters))
        if (text.length < 120) return ParserResult.declined("document too small for text heuristics")

        val facts = TextFactsHeuristics.extract(
            text = text,
            context = context,
            method = ProvenanceMethod.TEXT_HEURISTIC,
            confidence = 0.45,
            rawPath = "body-text",
            allowAddress = true,
            allowPrice = true
        )

        if (facts.isEmpty()) return ParserResult.declined("no listing signals in visible text")

        val warnings = ArrayList<ImportWarning>()
        val hasPrice = facts.contains(PropertyField.PRICE_AMOUNT)
        val hasPhysical = facts.contains(PropertyField.BEDROOMS) || facts.contains(PropertyField.LIVING_AREA_SQFT)
        if (!hasPrice && !hasPhysical) {
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.PARTIAL_PARSE,
                    message = "Only textual hints were extracted; no price or physical attributes found",
                    sourceId = context.source.sourceId
                )
            )
        }

        return ParserResult(facts = facts, warnings = warnings)
    }
}
