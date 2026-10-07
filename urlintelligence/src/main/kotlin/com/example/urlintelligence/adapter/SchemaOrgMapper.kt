package com.example.urlintelligence.adapter

import com.example.urlintelligence.html.Html
import com.example.urlintelligence.json.JsonValue
import com.example.urlintelligence.model.CanonicalListingStatus
import com.example.urlintelligence.model.CanonicalPropertyType
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.AddressParser
import com.example.urlintelligence.normalization.AreaParser
import com.example.urlintelligence.normalization.ListingStatusParser
import com.example.urlintelligence.normalization.MoneyParser
import com.example.urlintelligence.normalization.PropertyTypeParser
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import java.util.Locale

/**
 * Maps schema.org (JSON-LD) property nodes onto a [PropertyDraft].
 *
 * This is the highest-confidence extractor in the stack: it is the only one whose
 * values come from a machine-readable contract instead of markup scraping, so the
 * recorded provenance for these fields is [ExtractionMethod.STRUCTURED_DATA].
 */
object SchemaOrgMapper {

    fun apply(draft: PropertyDraft, node: JsonValue.JsonObject, extractor: String): Int {
        var applied = 0

        fun put(
            field: PropertyField,
            value: Any?,
            confidence: Double = Confidence.EXACT,
            raw: String? = null,
            method: ExtractionMethod = ExtractionMethod.STRUCTURED_DATA,
            note: String? = null
        ) {
            if (draft.put(field, value, method, confidence, extractor, raw, note)) applied++
        }

        // ---- type -----------------------------------------------------------------
        for (typeName in node.types()) {
            val parsed = PropertyTypeParser.parse(typeName.substringAfterLast('/'))
            if (parsed != null && parsed != CanonicalPropertyType.UNKNOWN) {
                put(PropertyField.PROPERTY_TYPE, parsed, Confidence.HIGH, typeName)
                break
            }
        }

        // ---- address --------------------------------------------------------------
        when (val address = node["address"]) {
            is JsonValue.JsonString -> {
                val parsed = AddressParser.parseOneLine(Html.decodeEntities(address.value))
                put(PropertyField.ADDRESS_LINE1, parsed.line1, Confidence.HIGH, address.value)
                put(PropertyField.UNIT, parsed.unit, Confidence.MEDIUM)
                put(PropertyField.CITY, parsed.city, Confidence.HIGH)
                put(PropertyField.STATE, parsed.state, Confidence.HIGH)
                put(PropertyField.POSTAL_CODE, parsed.postalCode, Confidence.HIGH)
            }
            is JsonValue.JsonObject -> {
                put(
                    PropertyField.ADDRESS_LINE1,
                    address.firstString("streetAddress", "streetaddress")?.let { Html.decodeEntities(it) },
                    Confidence.EXACT
                )
                put(PropertyField.CITY, address.firstString("addressLocality", "addresslocality"), Confidence.EXACT)
                put(PropertyField.STATE, address.firstString("addressRegion", "addressregion"), Confidence.EXACT)
                put(PropertyField.POSTAL_CODE, address.firstString("postalCode", "postalcode"), Confidence.EXACT)
                put(PropertyField.COUNTRY, address.firstString("addressCountry", "addresscountry"), Confidence.MEDIUM)
            }
            else -> Unit
        }

        // ---- geo ------------------------------------------------------------------
        val geo = node.obj("geo")
        if (geo != null) {
            put(PropertyField.LATITUDE, geo.firstDouble("latitude", "lat"), Confidence.HIGH)
            put(PropertyField.LONGITUDE, geo.firstDouble("longitude", "lng", "lon"), Confidence.HIGH)
        } else {
            put(PropertyField.LATITUDE, node.doubleOrNumericString("latitude"), Confidence.MEDIUM)
            put(PropertyField.LONGITUDE, node.doubleOrNumericString("longitude"), Confidence.MEDIUM)
        }

        // ---- dimensions ----------------------------------------------------------
        when (val floorSize = node["floorSize"]) {
            is JsonValue.JsonNumber -> put(PropertyField.LIVING_AREA_SQFT, floorSize.toIntOrNull(), Confidence.HIGH, floorSize.raw)
            is JsonValue.JsonString -> put(
                PropertyField.LIVING_AREA_SQFT,
                AreaParser.sqFt(floorSize.value),
                Confidence.MEDIUM,
                floorSize.value
            )
            is JsonValue.JsonObject -> put(
                PropertyField.LIVING_AREA_SQFT,
                floorSize.doubleOrNumericString("value")?.toInt(),
                Confidence.HIGH
            )
            else -> Unit
        }

        put(
            PropertyField.LIVING_AREA_SQFT,
            node.array("floorSize")?.let { AreaParser.sqFt(it.strings().firstOrNull()) },
            Confidence.LOW
        )

        // ---- rooms / age ---------------------------------------------------------
        val bedrooms = node.doubleOrNumericString("numberOfBedrooms")
            ?: node.doubleOrNumericString("numberOfRooms")
        put(PropertyField.BEDROOMS, bedrooms, if (node.has("numberOfBedrooms")) Confidence.EXACT else Confidence.MEDIUM)

        val bathrooms = node.doubleOrNumericString("numberOfBathroomsTotal")
            ?: node.doubleOrNumericString("numberOfFullBathrooms")
            ?: node.doubleOrNumericString("numberOfBathrooms")
        put(PropertyField.BATHROOMS, bathrooms, Confidence.EXACT)

        put(PropertyField.YEAR_BUILT, node.doubleOrNumericString("yearBuilt")?.toInt(), Confidence.EXACT)
        put(
            PropertyField.DESCRIPTION,
            node.string("description")?.let { Html.decodeEntities(it) },
            Confidence.EXACT
        )

        // ---- offers --------------------------------------------------------------
        val offer = when (val offers = node["offers"]) {
            is JsonValue.JsonObject -> offers
            is JsonValue.JsonArray -> offers.objects().firstOrNull()
            else -> null
        }
        if (offer != null) {
            val amount = offer.doubleOrNumericString("price")
                ?: offer.doubleOrNumericString("lowPrice")
                ?: offer.obj("priceSpecification")?.doubleOrNumericString("price")
            val currency = offer.string("priceCurrency")
            val isRental = offer.string("businessFunction")?.contains("lease", ignoreCase = true) == true ||
                offer.string("category")?.contains("rent", ignoreCase = true) == true ||
                offer.string("name")?.contains("rent", ignoreCase = true) == true

            if (amount != null) {
                if (isRental) {
                    put(PropertyField.ESTIMATED_MONTHLY_RENT, amount, Confidence.HIGH, amount.toString())
                } else {
                    put(
                        PropertyField.LIST_PRICE,
                        amount,
                        Confidence.EXACT,
                        offer.numericString("price") ?: amount.toString(),
                        note = if (currency == null || currency.equals("USD", ignoreCase = true)) null
                        else "currency=$currency"
                    )
                }
            }
            val availability = offer.firstString("availability", "itemCondition")
                ?: offer.string("availabilityStarts")
            ListingStatusParser.parse(availability)?.let { status ->
                put(PropertyField.LISTING_STATUS, status, Confidence.MEDIUM, availability)
            }
        }

        // ---- images --------------------------------------------------------------
        val images = collectImages(node["image"])
        if (images.isNotEmpty()) {
            put(PropertyField.PRIMARY_IMAGE_URL, images.first(), Confidence.HIGH)
            put(PropertyField.IMAGE_URLS, images, Confidence.HIGH)
        }

        // ---- additionalProperty (source-specific key/value pairs) -----------------
        val additional = when (val value = node["additionalProperty"]) {
            is JsonValue.JsonArray -> value.objects()
            is JsonValue.JsonObject -> listOf(value)
            else -> emptyList()
        }
        additional.forEach { propertyValue ->
            val name = propertyValue.string("name") ?: propertyValue.string("propertyID")
            val raw = propertyValue.numericString("value") ?: propertyValue.string("value")
                ?: propertyValue.numericString("amount")
            if (name != null && raw != null) {
                val mapped = mapAdditionalProperty(name, raw)
                if (mapped != null) {
                    put(mapped.first, mapped.second, Confidence.MEDIUM, raw, note = "schema.org additionalProperty '$name'")
                }
            }
        }

        // ---- explicit status on the listing node ---------------------------------
        val status = node.firstString("availability", "listingStatus", "status")
        if (status != null) {
            val parsed = ListingStatusParser.parse(status)
            if (parsed != null && parsed != CanonicalListingStatus.UNKNOWN) {
                put(PropertyField.LISTING_STATUS, parsed, Confidence.MEDIUM, status)
            }
        }

        return applied
    }

    fun collectImages(value: JsonValue?): List<String> {
        val out = ArrayList<String>()
        when (value) {
            is JsonValue.JsonString -> if (value.value.isNotBlank()) out.add(value.value)
            is JsonValue.JsonArray -> value.items.forEach { out.addAll(collectImages(it)) }
            is JsonValue.JsonObject -> {
                value.firstString("url", "contentUrl")?.let { out.add(it) }
            }
            else -> Unit
        }
        return out.distinct()
    }

    /** Maps a schema.org PropertyValue name/value pair onto a canonical field. */
    fun mapAdditionalProperty(name: String, raw: String): Pair<PropertyField, Any?>? {
        val key = name.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        return when {
            key.contains("lotsize") || key == "lot" || key.contains("lotsqft") ->
                PropertyField.LOT_SIZE_SQFT to AreaParser.sqFt(raw)
            key.contains("yearbuilt") || key == "year" ->
                PropertyField.YEAR_BUILT to raw.replace(Regex("[^0-9]"), "").take(4).toIntOrNull()
            key.contains("hoa") || key.contains("associationfee") ->
                PropertyField.HOA_MONTHLY_FEE to MoneyParser.parse(raw)
            key.contains("propertytax") || key.contains("annualtax") || key == "taxes" ||
                key.contains("taxamount") ->
                PropertyField.ANNUAL_TAX_AMOUNT to MoneyParser.parse(raw)
            key.contains("mls") ->
                PropertyField.MLS_ID to raw.trim()
            key.contains("parcel") || key == "apn" || key.contains("taxid") ->
                PropertyField.PARCEL_ID to raw.trim()
            key.contains("daysonmarket") || key == "dom" ->
                PropertyField.DAYS_ON_MARKET to raw.replace(Regex("[^0-9]"), "").toIntOrNull()
            key.contains("rent") ->
                PropertyField.ESTIMATED_MONTHLY_RENT to MoneyParser.parse(raw)
            key.contains("listprice") || key == "price" ->
                PropertyField.LIST_PRICE to MoneyParser.parse(raw)
            key.contains("bedroom") || key == "beds" ->
                PropertyField.BEDROOMS to raw.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
            key.contains("bathroom") || key == "baths" ->
                PropertyField.BATHROOMS to raw.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
            key.contains("livingarea") || key.contains("sqft") || key.contains("interiorsize") ->
                PropertyField.LIVING_AREA_SQFT to AreaParser.sqFt(raw)
            key.contains("propertytype") || key.contains("hometype") ->
                PropertyField.PROPERTY_TYPE to (PropertyTypeParser.parse(raw) ?: CanonicalPropertyType.UNKNOWN)
            else -> null
        }
    }
}
