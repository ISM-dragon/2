package com.example.urlintelligence.adapter

import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.source.KnownSources

/**
 * Realtor.com detail pages (`/realestateandhomes-detail/<slug>-<id>`).
 *
 * Realtor.com mixes JSON-LD with `data-testid` attributes; the selector table
 * covers both, and the id is taken from the URL slug when present.
 */
class RealtorAdapter(
    transport: PropertyHttpTransport,
    clock: Clock = Clock.SYSTEM
) : StructuredDataPropertySourceAdapter(transport, KnownSources.REALTOR, clock) {

    override fun selectors(): List<FieldSelector> = SELECTORS

    companion object {
        val SELECTORS: List<FieldSelector> = listOf(
            FieldSelector(
                field = PropertyField.LIST_PRICE,
                pattern = Regex("data-testid=\"list-price\"[^>]*>\\s*\\$?([0-9][0-9,]{3,})", RegexOption.IGNORE_CASE),
                transform = ValueTransform.MONEY,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LIST_PRICE,
                pattern = Regex("\"(?:list_price|price|listPrice)\"\\s*:\\s*\"?\\$?([0-9][0-9,]{4,})\"?"),
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
                field = PropertyField.BATHROOMS,
                pattern = Regex("\"baths\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LIVING_AREA_SQFT,
                pattern = Regex("\"sqft\"\\s*:\\s*\"?([0-9][0-9,]{2,})\"?"),
                transform = ValueTransform.AREA_SQFT,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.YEAR_BUILT,
                pattern = Regex("\"year_built\"\\s*:\\s*\"?([0-9]{4})\"?"),
                transform = ValueTransform.INTEGER,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.ADDRESS_LINE1,
                pattern = Regex("\"line\"\\s*:\\s*\"([^\"]{3,120})\""),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.CITY,
                pattern = Regex("\"city\"\\s*:\\s*\"([^\"]{2,80})\""),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.STATE,
                pattern = Regex("\"state_code\"\\s*:\\s*\"([A-Za-z]{2})\""),
                transform = ValueTransform.STATE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.POSTAL_CODE,
                pattern = Regex("\"postal_code\"\\s*:\\s*\"?([0-9]{5}(?:-[0-9]{4})?)\"?"),
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
                pattern = Regex("\"lot_sqft\"\\s*:\\s*\"?([0-9][0-9,]{2,})\"?"),
                transform = ValueTransform.AREA_SQFT,
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.PROPERTY_TYPE,
                pattern = Regex("\"prop_type\"\\s*:\\s*\"([^\"]{3,40})\""),
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.MLS_ID,
                pattern = Regex("\"mls\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"([A-Za-z0-9_-]{4,24})\""),
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.DESCRIPTION,
                pattern = Regex("data-testid=\"description\"[^>]*>([^<]{20,4000})<", RegexOption.IGNORE_CASE),
                confidence = Confidence.MEDIUM
            )
        )
    }
}
