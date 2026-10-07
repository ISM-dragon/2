package com.example.urlintelligence.adapter

import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.parser.ParserSpec
import com.example.urlintelligence.parser.SignatureProbe
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.source.KnownSources

/**
 * Redfin property detail pages (`/<STATE>/<city>/<slug>/home/<id>`).
 *
 * Redfin's public pages expose a `SingleFamilyResidence` JSON-LD node plus an
 * embedded model where numeric facts are wrapped in `{ "value": ... }` objects —
 * both shapes are covered below.
 */
class RedfinAdapter(
    transport: PropertyHttpTransport,
    clock: Clock = Clock.SYSTEM
) : StructuredDataPropertySourceAdapter(transport, KnownSources.REDFIN, clock) {

    override val parserSpec: ParserSpec = PARSER_SPEC

    override fun selectors(): List<FieldSelector> = SELECTORS

    companion object {
        /** Parser contract; bump [ParserSpec.version] when the selector table changes. */
        val PARSER_SPEC: ParserSpec = ParserSpec(
            parserId = "redfin.html",
            version = "2",
            probes = listOf(
                SignatureProbe("listing-id", Regex("\"(?:listingId|propertyId)\"\\s*:"), required = true),
                SignatureProbe("json-ld", Regex("application/ld\\+json", RegexOption.IGNORE_CASE)),
                SignatureProbe("redfin-cdn", Regex("cdn-redfin\\.com", RegexOption.IGNORE_CASE))
            ),
            minProbesMatched = 2,
            minFieldsExtracted = 3,
            expectedFields = setOf(
                PropertyField.LIST_PRICE,
                PropertyField.BEDROOMS,
                PropertyField.ADDRESS_LINE1
            )
        )

        val SELECTORS: List<FieldSelector> = listOf(
            FieldSelector(
                field = PropertyField.LIST_PRICE,
                pattern = Regex("\"price\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*([0-9]{4,12})"),
                transform = ValueTransform.MONEY,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LIST_PRICE,
                pattern = Regex("\"(?:listPrice|price)\"\\s*:\\s*\"?\\$?([0-9][0-9,]{4,})\"?"),
                transform = ValueTransform.MONEY,
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.BEDROOMS,
                pattern = Regex("\"beds\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.BEDROOMS,
                pattern = Regex("\"numBeds\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*([0-9]+)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.BATHROOMS,
                pattern = Regex("\"baths\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.BATHROOMS,
                pattern = Regex("\"numBaths\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*([0-9.]+)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LIVING_AREA_SQFT,
                pattern = Regex("\"sqFt\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*([0-9][0-9,]{2,})"),
                transform = ValueTransform.AREA_SQFT,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.YEAR_BUILT,
                pattern = Regex("\"yearBuilt\"\\s*:\\s*\\{?\\s*\"?value\"?\\s*:?\\s*\"?([0-9]{4})\"?"),
                transform = ValueTransform.INTEGER,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.ADDRESS_LINE1,
                pattern = Regex("\"streetLine\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]{3,120})\""),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.CITY,
                pattern = Regex("\"city\"\\s*:\\s*\"([^\"]{2,80})\""),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.STATE,
                pattern = Regex("\"stateOrProvince\"\\s*:\\s*\"([A-Za-z]{2})\""),
                transform = ValueTransform.STATE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.POSTAL_CODE,
                pattern = Regex("\"zip\"\\s*:\\s*\"?([0-9]{5}(?:-[0-9]{4})?)\"?"),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LATITUDE,
                pattern = Regex("\"lat(?:itude)?\"\\s*:\\s*(-?[0-9]{1,3}\\.[0-9]+)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LONGITUDE,
                pattern = Regex("\"lon(?:gitude)?\"\\s*:\\s*(-?[0-9]{1,3}\\.[0-9]+)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LOT_SIZE_SQFT,
                pattern = Regex("\"lotSize\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*([0-9][0-9,]{2,})"),
                transform = ValueTransform.AREA_SQFT,
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.PROPERTY_TYPE,
                pattern = Regex("\"propertyType\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"?([0-9]+)\"?\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]{3,40})\""),
                group = 2,
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.DESCRIPTION,
                pattern = Regex("\"description\"\\s*:\\s*\"([^\"]{20,4000})\""),
                confidence = Confidence.MEDIUM
            )
        )
    }
}
