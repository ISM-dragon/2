package com.example.urlintelligence.adapter

import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.parser.ParserSpec
import com.example.urlintelligence.parser.SignatureProbe
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.source.KnownSources

/**
 * Homes.com property pages.
 *
 * Emits JSON-LD plus an embedded listing model with flat keys (`propertyId`,
 * `listPrice`, `livingArea`, ...). The id falls back to the trailing numeric
 * path segment declared in the descriptor.
 */
class HomesAdapter(
    transport: PropertyHttpTransport,
    clock: Clock = Clock.SYSTEM
) : StructuredDataPropertySourceAdapter(transport, KnownSources.HOMES, clock) {

    override val parserSpec: ParserSpec = PARSER_SPEC

    override fun selectors(): List<FieldSelector> = SELECTORS

    companion object {
        /** Parser contract; bump [ParserSpec.version] when the selector table changes. */
        val PARSER_SPEC: ParserSpec = ParserSpec(
            parserId = "homes.html",
            version = "2",
            probes = listOf(
                SignatureProbe("property-id", Regex("\"propertyId\"\\s*:\\s*\"?[0-9]{6,12}"), required = true),
                SignatureProbe("json-ld", Regex("application/ld\\+json", RegexOption.IGNORE_CASE)),
                SignatureProbe("homes-cdn", Regex("images\\.homes\\.com", RegexOption.IGNORE_CASE))
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
                pattern = Regex("\"listPrice\"\\s*:\\s*\"?\\$?([0-9][0-9,]{4,})\"?"),
                transform = ValueTransform.MONEY,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.BEDROOMS,
                pattern = Regex("\"bedrooms\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.BATHROOMS,
                pattern = Regex("\"bathrooms\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LIVING_AREA_SQFT,
                pattern = Regex("\"livingArea\"\\s*:\\s*\"?([0-9][0-9,]{2,})\"?"),
                transform = ValueTransform.AREA_SQFT,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.YEAR_BUILT,
                pattern = Regex("\"yearBuilt\"\\s*:\\s*\"?([0-9]{4})\"?"),
                transform = ValueTransform.INTEGER,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.ADDRESS_LINE1,
                pattern = Regex("\"(?:streetAddress|address1)\"\\s*:\\s*\"([^\"]{3,120})\""),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.CITY,
                pattern = Regex("\"(?:city|addressLocality)\"\\s*:\\s*\"([^\"]{2,80})\""),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.STATE,
                pattern = Regex("\"(?:state|stateCode|addressRegion)\"\\s*:\\s*\"([A-Za-z]{2})\""),
                transform = ValueTransform.STATE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.POSTAL_CODE,
                pattern = Regex("\"(?:zipCode|postalCode|zip)\"\\s*:\\s*\"?([0-9]{5}(?:-[0-9]{4})?)\"?"),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LATITUDE,
                pattern = Regex("\"latitude\"\\s*:\\s*\"?(-?[0-9]{1,3}\\.[0-9]+)\"?"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LONGITUDE,
                pattern = Regex("\"longitude\"\\s*:\\s*\"?(-?[0-9]{1,3}\\.[0-9]+)\"?"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LOT_SIZE_SQFT,
                pattern = Regex("\"lotSize\"\\s*:\\s*\"?([0-9][0-9,]{2,})\"?"),
                transform = ValueTransform.AREA_SQFT,
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.HOA_MONTHLY_FEE,
                pattern = Regex("\"hoa(?:Fee|FeeAmount)?\"\\s*:\\s*\"?\\$?([0-9][0-9,]{1,6})\"?"),
                transform = ValueTransform.MONEY,
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.PROPERTY_TYPE,
                pattern = Regex("\"propertyType\"\\s*:\\s*\"([^\"]{3,40})\""),
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
