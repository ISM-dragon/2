package com.example.domain.propertyurl.parse

import com.example.domain.propertyurl.model.ExtractedFacts
import com.example.domain.propertyurl.model.ExtractedFactsBuilder
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.model.RawSourceDocument
import com.example.domain.propertyurl.normalize.ValueGuards
import com.example.domain.propertyurl.normalize.ValueParsing

/**
 * Extracts facts from OpenGraph/meta tags and the document title.
 *
 * Runs late in the chain: it contributes address, price and image fallbacks when structured data or
 * embedded state is missing, and it is also what produces a usable `LISTING_TITLE`/`DESCRIPTION`
 * for the canonical record.
 */
class MetaAndTitleFactsParser(
    override val priority: Int = 30
) : SourceDocumentParser {

    override val parserId: String = "meta-and-title"

    override val version: String = "1.1.0"

    override fun canParse(document: RawSourceDocument): Boolean = document.isHtml

    override fun parse(document: RawSourceDocument, context: ParserContext): ParserResult {
        val html = document.bodyOrEmpty
        val meta = HtmlScanner.metaTags(html)
        val title = HtmlScanner.titleTag(html)
        val warnings = ArrayList<ImportWarning>()

        var facts = ExtractedFacts.EMPTY

        fun metaProvenance(key: String, confidence: Double = 0.7) =
            context.provenance.create(ProvenanceMethod.HTML_META, confidence, "meta[$key]")

        val builder = ExtractedFactsBuilder()

        (meta["og:title"] ?: meta["twitter:title"] ?: title)?.let { heading ->
            val normalized = HtmlScanner.normalizeWhitespace(heading).orEmpty()
            if (normalized.length in 8..300) {
                builder.addText(PropertyField.LISTING_TITLE, normalized, metaProvenance("og:title", 0.75))
            }
        }

        (meta["og:description"] ?: meta["description"] ?: meta["twitter:description"])?.let { description ->
            val normalized = HtmlScanner.normalizeWhitespace(description).orEmpty()
            if (normalized.length >= 40) {
                builder.addText(PropertyField.DESCRIPTION, normalized.take(4000), metaProvenance("og:description", 0.7))
            }
        }

        // Galleries repeat og:image, so collect every occurrence rather than the first one only.
        val images = (
            HtmlScanner.metaTagValues(html, "og:image") +
                HtmlScanner.metaTagValues(html, "og:image:secure_url") +
                HtmlScanner.metaTagValues(html, "twitter:image")
            )
            .map { it.trim() }
            .filter { ValueGuards.imageUrl(it).accepted }
            .distinct()
            .take(MAX_IMAGES)
        if (images.isNotEmpty()) {
            builder.addList(PropertyField.IMAGE_URLS, images, metaProvenance("og:image", 0.75))
        }

        // Facebook "place" tags occasionally expose coordinates.
        val latitude = meta["place:location:latitude"]?.toDoubleOrNull()
        val longitude = meta["place:location:longitude"]?.toDoubleOrNull()
        if (ValueGuards.latitude(latitude).accepted && ValueGuards.longitude(longitude).accepted) {
            builder.addCoordinates(latitude, longitude, metaProvenance("place:location", 0.6))
        }

        // Structured meta used by some IDX/brokerage sites.
        listOf("og:street-address", "address", "street-address").forEach { key ->
            meta[key]?.let { street ->
                val normalized = HtmlScanner.normalizeWhitespace(street).orEmpty()
                if (ValueGuards.addressLine(normalized).accepted) {
                    builder.addText(PropertyField.ADDRESS_LINE_1, normalized, metaProvenance(key, 0.6))
                }
            }
        }
        meta["og:locality"]?.let { builder.addText(PropertyField.CITY, it, metaProvenance("og:locality", 0.6)) }
        meta["og:region"]?.let { region ->
            ValueParsing.parseUsState(region)?.let { state ->
                builder.addText(PropertyField.STATE, state, metaProvenance("og:region", 0.6))
            }
        }
        meta["og:postal-code"]?.let { postal ->
            ValueGuards.postalCode(postal, null).let { guard ->
                if (guard.accepted) {
                    builder.addText(
                        PropertyField.POSTAL_CODE,
                        ValueParsing.parsePostalCode(postal) ?: postal,
                        metaProvenance("og:postal-code", 0.6)
                    )
                }
            }
        }

        facts = facts.plus(builder.build())

        // Title/description heuristics (price, beds, baths, sqft, address).
        val titleText = title ?: meta["og:title"]
        facts = facts.plus(
            TextFactsHeuristics.extract(
                text = titleText,
                context = context,
                method = ProvenanceMethod.TEXT_HEURISTIC,
                confidence = 0.6,
                rawPath = "title",
                allowAddress = true,
                allowPrice = true
            )
        )
        // Run the heuristics over the *longer* of the description variants: portals put marketing copy
        // in og:description but keep year built / MLS / lot size in the plain description tag.
        val descriptions = listOfNotNull(meta["og:description"], meta["description"], meta["twitter:description"])
            .map { HtmlScanner.normalizeWhitespace(it).orEmpty() }
            .distinct()
        val richestDescription = descriptions.maxByOrNull { it.length }

        facts = facts.plus(
            TextFactsHeuristics.extract(
                text = richestDescription,
                context = context,
                method = ProvenanceMethod.TEXT_HEURISTIC,
                confidence = 0.5,
                rawPath = "meta[description]",
                allowAddress = false,
                allowPrice = true
            )
        )

        if (facts.isEmpty()) {
            return ParserResult.declined("no meta/title facts")
        }

        if (!facts.contains(PropertyField.ADDRESS_LINE_1)) {
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.MISSING_CRITICAL_FIELD,
                    message = "Title/meta tags did not carry a street address",
                    field = PropertyField.ADDRESS_LINE_1,
                    sourceId = context.source.sourceId
                )
            )
        }

        return ParserResult(facts = facts, warnings = warnings)
    }

    private companion object {
        const val MAX_IMAGES = 25
    }
}
