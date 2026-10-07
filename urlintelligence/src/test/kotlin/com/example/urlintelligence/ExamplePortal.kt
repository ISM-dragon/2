package com.example.urlintelligence

import com.example.urlintelligence.adapter.DeclarativeSourceAdapter
import com.example.urlintelligence.adapter.FieldSelector
import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.ValueTransform
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.parser.ParserSpec
import com.example.urlintelligence.parser.SignatureProbe
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import com.example.urlintelligence.source.SourceDescriptor
import com.example.urlintelligence.source.SourceTier

/**
 * A provider that exists only in the test sources.
 *
 * It is deliberately complete — descriptor, versioned parser spec, selector table, fixture — so
 * the test suite can prove that adding a real provider is the same exercise: no core pipeline
 * change, no resolver change, no state-machine change.
 */
object ExamplePortal {

    const val SOURCE_ID: String = "example-portal"
    const val URL: String = "https://www.example-portal.test/property/18-foundry-row/EP-88213"
    const val FIXTURE: String = "example_portal_listing.html"

    val DESCRIPTOR = SourceDescriptor(
        id = SOURCE_ID,
        displayName = "Example Portal",
        hostSuffixes = listOf("example-portal.test"),
        pathPatterns = listOf(Regex("/property/", RegexOption.IGNORE_CASE)),
        idPatterns = listOf(Regex("/property/[^/]+/([A-Za-z0-9-]{4,24})", RegexOption.IGNORE_CASE)),
        bodyIdPatterns = listOf(Regex("\"exampleId\"\\s*:\\s*\"([A-Za-z0-9-]{4,24})\"")),
        tier = SourceTier.PUBLIC_WEB,
        requiresOptIn = true,
        notes = "Synthetic provider used to prove that adding a source needs no core change."
    )

    val PARSER_SPEC = ParserSpec(
        parserId = "example-portal.html",
        version = "1",
        probes = listOf(
            SignatureProbe("portal-state", Regex("__PORTAL_STATE__"), required = true),
            SignatureProbe("json-ld", Regex("application/ld\\+json", RegexOption.IGNORE_CASE))
        ),
        minProbesMatched = 2,
        minFieldsExtracted = 2
    )

    val SELECTORS: List<FieldSelector> = listOf(
        FieldSelector(
            field = PropertyField.LIST_PRICE,
            pattern = Regex("\"listPrice\"\\s*:\\s*([0-9]{4,12})"),
            transform = ValueTransform.MONEY,
            confidence = Confidence.HIGH,
            method = ExtractionMethod.DOM_SELECTOR
        ),
        FieldSelector(
            field = PropertyField.MLS_ID,
            pattern = Regex("\"exampleId\"\\s*:\\s*\"([A-Za-z0-9-]{4,24})\""),
            confidence = Confidence.MEDIUM
        )
    )

    fun adapter(transport: PropertyHttpTransport): DeclarativeSourceAdapter =
        DeclarativeSourceAdapter(transport, DESCRIPTOR, PARSER_SPEC, SELECTORS)
}
