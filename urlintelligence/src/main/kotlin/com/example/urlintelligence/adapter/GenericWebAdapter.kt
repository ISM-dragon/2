package com.example.urlintelligence.adapter

import com.example.urlintelligence.html.Html
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.parser.ParserSpec
import com.example.urlintelligence.parser.SignatureProbe
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.source.KnownSources

/**
 * Fallback parser for hosts we do not know.
 *
 * It always tries schema.org first (many smaller brokerages publish clean JSON-LD),
 * then meta tags, then conservative text heuristics over the rendered document.
 * Results are deliberately lower-confidence than a dedicated adapter's, which the
 * provenance map makes visible to every downstream consumer.
 */
class GenericWebAdapter(
    transport: PropertyHttpTransport,
    clock: Clock = Clock.SYSTEM
) : StructuredDataPropertySourceAdapter(transport, KnownSources.GENERIC, clock) {

    override val parserSpec: ParserSpec = PARSER_SPEC

    override fun selectors(): List<FieldSelector> = SELECTORS

    override fun onDocumentParsed(draft: PropertyDraft, body: String, extractor: String): Int {
        var applied = 0
        val heading = H1_RE.find(body)?.groupValues?.getOrNull(1)?.let {
            Html.decodeEntities(Html.stripTags(it)).trim()
        }
        if (heading != null && looksLikeAddress(heading)) {
            applied += SelectorRunner.apply(
                draft = draft,
                document = heading,
                selectors = listOf(
                    FieldSelector(
                        field = PropertyField.ADDRESS_LINE1,
                        pattern = Regex("^(.+?)(?:,|\\|)"),
                        method = ExtractionMethod.REGEX_HEURISTIC,
                        confidence = Confidence.LOW
                    )
                ),
                extractor = extractor
            )
        }
        return applied
    }

    private companion object {

        /**
         * Deliberately loose contract: the generic adapter accepts any public page, so it
         * needs no required probes. It still watches for its three entry points, which turns
         * "this page used to have JSON-LD" into an observable warning.
         */
        val PARSER_SPEC: ParserSpec = ParserSpec(
            parserId = "generic.html",
            version = "1",
            probes = listOf(
                SignatureProbe("json-ld", Regex("application/ld\\+json", RegexOption.IGNORE_CASE)),
                SignatureProbe("og-title", Regex("property=\"og:title\"", RegexOption.IGNORE_CASE)),
                SignatureProbe("address-heading", Regex("<h1[^>]*>\\s*[0-9]", RegexOption.IGNORE_CASE))
            ),
            minProbesMatched = 1,
            minFieldsExtracted = 1,
            downgradeUnverifiedOnDrift = true
        )

        val H1_RE = Regex(
            "<h1[^>]*>(.*?)</h1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val SELECTORS: List<FieldSelector> = listOf(
            FieldSelector(
                field = PropertyField.LIST_PRICE,
                pattern = Regex("\\$\\s?([0-9][0-9,]{5,})(?:\\.[0-9]{2})?"),
                method = ExtractionMethod.REGEX_HEURISTIC,
                transform = ValueTransform.MONEY,
                confidence = Confidence.LOW
            ),
            FieldSelector(
                field = PropertyField.BEDROOMS,
                pattern = Regex(
                    "([0-9]+(?:\\.[0-9])?)\\s*(?:bd|beds?|bedrooms?)\\b",
                    RegexOption.IGNORE_CASE
                ),
                method = ExtractionMethod.REGEX_HEURISTIC,
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.LOW
            ),
            FieldSelector(
                field = PropertyField.BATHROOMS,
                pattern = Regex(
                    "([0-9]+(?:\\.[0-9])?)\\s*(?:ba|baths?|bathrooms?)\\b",
                    RegexOption.IGNORE_CASE
                ),
                method = ExtractionMethod.REGEX_HEURISTIC,
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.LOW
            ),
            FieldSelector(
                field = PropertyField.LIVING_AREA_SQFT,
                pattern = Regex(
                    "([0-9][0-9,]{2,})\\s*(?:sq\\.?\\s?ft\\.?|square\\s?feet|sqft)",
                    RegexOption.IGNORE_CASE
                ),
                method = ExtractionMethod.REGEX_HEURISTIC,
                transform = ValueTransform.AREA_SQFT,
                confidence = Confidence.LOW
            ),
            FieldSelector(
                field = PropertyField.YEAR_BUILT,
                pattern = Regex(
                    "(?:year\\s?built|built\\s?in|year)\\D{0,15}(1[6-9][0-9]{2}|20[0-9]{2})",
                    RegexOption.IGNORE_CASE
                ),
                method = ExtractionMethod.REGEX_HEURISTIC,
                transform = ValueTransform.INTEGER,
                confidence = Confidence.LOW
            ),
            FieldSelector(
                field = PropertyField.ESTIMATED_MONTHLY_RENT,
                pattern = Regex("\\$\\s?([0-9][0-9,]{3,5})\\s*(?:/|per)\\s?(?:mo|month)", RegexOption.IGNORE_CASE),
                method = ExtractionMethod.REGEX_HEURISTIC,
                transform = ValueTransform.MONEY,
                confidence = Confidence.LOW
            ),
            FieldSelector(
                field = PropertyField.POSTAL_CODE,
                pattern = Regex("\\b([0-9]{5})(?:-[0-9]{4})?\\b"),
                method = ExtractionMethod.REGEX_HEURISTIC,
                confidence = Confidence.UNVERIFIED
            ),
            FieldSelector(
                field = PropertyField.STATE,
                pattern = Regex("\\b([A-Za-z]{2})\\s+[0-9]{5}\\b"),
                method = ExtractionMethod.REGEX_HEURISTIC,
                transform = ValueTransform.STATE,
                confidence = Confidence.UNVERIFIED
            )
        )
    }
}
