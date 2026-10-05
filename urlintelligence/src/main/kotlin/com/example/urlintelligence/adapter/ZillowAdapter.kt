package com.example.urlintelligence.adapter

import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.source.KnownSources

/**
 * Zillow public listing pages.
 *
 * Zillow ships schema.org JSON-LD on property detail pages and, in addition, an
 * embedded state blob that carries the same facts as JSON keys. The selector table
 * below targets that blob so the adapter keeps working when only one of the two
 * representations is present. Nothing here authenticates: the adapter reads public
 * pages only, and the descriptor requires an explicit opt-in before any fetch.
 */
class ZillowAdapter(
    transport: PropertyHttpTransport,
    clock: Clock = Clock.SYSTEM
) : StructuredDataPropertySourceAdapter(transport, KnownSources.ZILLOW, clock) {

    override fun selectors(): List<FieldSelector> = SELECTORS

    companion object {
        val SELECTORS: List<FieldSelector> = listOf(
            FieldSelector(
                field = PropertyField.LIST_PRICE,
                pattern = Regex("\"price\"\\s*:\\s*\"?\\$?([0-9][0-9,]{3,})\"?"),
                transform = ValueTransform.MONEY,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LIST_PRICE,
                pattern = Regex("\"(?:listPrice|priceForHdp|amount)\"\\s*:\\s*\"?\\$?([0-9][0-9,]{3,})\"?"),
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
                pattern = Regex("\"livingArea(?:Value)?\"\\s*:\\s*\"?([0-9][0-9,]{2,})\"?"),
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
                pattern = Regex("\"streetAddress\"\\s*:\\s*\"([^\"]{3,120})\""),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.CITY,
                pattern = Regex("\"(?:city|addressLocality)\"\\s*:\\s*\"([^\"]{2,80})\""),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.STATE,
                pattern = Regex("\"(?:state|addressRegion)\"\\s*:\\s*\"([A-Za-z]{2})\""),
                transform = ValueTransform.STATE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.POSTAL_CODE,
                pattern = Regex("\"(?:zipcode|zipCode|postalCode)\"\\s*:\\s*\"?([0-9]{5}(?:-[0-9]{4})?)\"?"),
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LATITUDE,
                pattern = Regex("\"latitude\"\\s*:\\s*(-?[0-9]{1,3}\\.[0-9]+)"),
                transform = ValueTransform.DOUBLE,
                confidence = Confidence.HIGH
            ),
            FieldSelector(
                field = PropertyField.LONGITUDE,
                pattern = Regex("\"longitude\"\\s*:\\s*(-?[0-9]{1,3}\\.[0-9]+)"),
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
                field = PropertyField.ESTIMATED_MONTHLY_RENT,
                pattern = Regex("\"rentZestimate\"\\s*:\\s*\"?\\$?([0-9][0-9,]{2,})\"?"),
                transform = ValueTransform.MONEY,
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.ANNUAL_TAX_AMOUNT,
                pattern = Regex("\"(?:taxAnnualAmount|propertyTaxRate|taxPaid)\"\\s*:\\s*\"?\\$?([0-9][0-9,]{2,})\"?"),
                transform = ValueTransform.MONEY,
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.MLS_ID,
                pattern = Regex("\"mlsid\"\\s*:\\s*\"([A-Za-z0-9_-]{4,24})\"", RegexOption.IGNORE_CASE),
                confidence = Confidence.MEDIUM
            ),
            FieldSelector(
                field = PropertyField.DESCRIPTION,
                pattern = Regex("\"description\"\\s*:\\s*\"([^\"]{20,4000})\""),
                method = ExtractionMethod.DOM_SELECTOR,
                confidence = Confidence.MEDIUM
            )
        )
    }
}
