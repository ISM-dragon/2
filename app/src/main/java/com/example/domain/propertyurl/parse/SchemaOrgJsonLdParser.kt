package com.example.domain.propertyurl.parse

import com.example.domain.propertyurl.json.JsonParseResult
import com.example.domain.propertyurl.json.JsonParser
import com.example.domain.propertyurl.json.JsonValue
import com.example.domain.propertyurl.model.ExtractedFacts
import com.example.domain.propertyurl.model.ExtractedFactsBuilder
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.model.RawSourceDocument
import com.example.domain.propertyurl.normalize.ValueGuards
import com.example.domain.propertyurl.normalize.ValueParsing
import java.util.Locale

/**
 * Parses schema.org structured data (`application/ld+json`), the most stable contract most listing
 * portals publish.
 *
 * Handles the shapes seen in the wild:
 *  - a single node, an array of nodes, or `@graph` wrappers (nested arbitrarily)
 *  - `RealEstateListing`, `SingleFamilyResidence`, `House`, `Apartment`, `Residence`, `Product`
 *  - `offers` as object or array, `price` as number or formatted string
 *  - `address` as `PostalAddress` object or as a single string
 *  - `geo` object or top-level `latitude`/`longitude`
 *  - `image` as string, array, or `{url}` objects
 *  - `floorSize`/`lotSize` with `value` + `unitCode`/`unitText` (sqft/sqm/acres)
 *
 * Every emitted fact carries the JSON path it came from, so the UI can show "price ← json-ld[0].offers.price".
 */
class SchemaOrgJsonLdParser(
    override val priority: Int = 10
) : SourceDocumentParser {

    override val parserId: String = "schema-org-json-ld"

    override val version: String = "1.2.0"

    override fun canParse(document: RawSourceDocument): Boolean =
        document.isHtml && document.bodyOrEmpty.contains("ld+json", ignoreCase = true)

    override fun parse(document: RawSourceDocument, context: ParserContext): ParserResult {
        val blocks = HtmlScanner.jsonLdBlocks(document.bodyOrEmpty)
        if (blocks.isEmpty()) return ParserResult.declined("no ld+json blocks")

        val warnings = ArrayList<ImportWarning>()
        val nodes = ArrayList<Pair<String, JsonValue.Obj>>()
        var malformed = 0

        blocks.forEachIndexed { index, block ->
            when (val parsed = JsonParser().parse(block)) {
                is JsonParseResult.Success -> collectNodes(parsed.value, "$index", nodes)
                is JsonParseResult.Failure -> {
                    malformed++
                    warnings.add(
                        ImportWarning(
                            code = ImportWarning.WarningCode.PARSER_SCHEMA_DRIFT,
                            message = "Malformed JSON-LD block #$index: ${parsed.message}",
                            sourceId = context.source.sourceId,
                            detail = "offset=${parsed.offset}"
                        )
                    )
                }
            }
        }

        if (nodes.isEmpty()) {
            return ParserResult(
                facts = ExtractedFacts.EMPTY,
                warnings = warnings,
                declined = malformed > 0 && malformed == blocks.size,
                reason = "no usable JSON-LD nodes"
            )
        }

        val ranked = nodes.sortedByDescending { (_, node) -> scoreNode(node) }
        val builder = ExtractedFactsBuilder()
        var consumed = 0

        ranked.forEach { (path, node) ->
            if (scoreNode(node) <= 0) return@forEach
            consumed++
            val rootPath = "json-ld[$path]"
            extractNode(node, rootPath, context, builder)
        }

        if (builder.isEmpty()) {
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.PARTIAL_PARSE,
                    message = "JSON-LD present but contained no recognised property fields",
                    sourceId = context.source.sourceId
                )
            )
        }

        return ParserResult(facts = builder.build(), warnings = warnings)
    }

    // --- Node discovery --------------------------------------------------------------------

    private val PROPERTY_TYPES = setOf(
        "realestatelisting", "singlefamilyresidence", "house", "apartment", "apartmentcomplex",
        "residence", "product", "offer", "condominium", "townhouse", "multifamilyresidence",
        "place", "accommodation", "lodgingbusiness"
    )

    private fun collectNodes(value: JsonValue, path: String, sink: MutableList<Pair<String, JsonValue.Obj>>) {
        when (value) {
            is JsonValue.Obj -> {
                if (looksLikeNode(value)) {
                    sink.add(path to value)
                } else if (value.entries.containsKey("@graph")) {
                    sink.add(path to value)
                }
                value.entries.forEach { (key, child) ->
                    if (key != "@context") collectNodes(child, "$path.$key", sink)
                }
            }
            is JsonValue.Arr -> value.items.forEachIndexed { index, child ->
                collectNodes(child, "$path[$index]", sink)
            }
            else -> Unit
        }
    }

    private fun looksLikeNode(node: JsonValue.Obj): Boolean {
        val types = typesOf(node)
        if (types.any { PROPERTY_TYPES.contains(it) }) return true
        // Untyped but obviously a listing payload.
        return node.entries.containsKey("address") ||
            node.entries.containsKey("numberOfBedrooms") ||
            node.entries.containsKey("floorSize")
    }

    private fun scoreNode(node: JsonValue.Obj): Int {
        var score = 0
        val types = typesOf(node)
        if (types.any { PROPERTY_TYPES.contains(it) }) score += 3
        if (node.entries.containsKey("address")) score += 3
        if (node.entries.containsKey("offers")) score += 2
        if (node.entries.containsKey("numberOfBedrooms") || node.entries.containsKey("numberOfBathroomsTotal")) score += 2
        if (node.entries.containsKey("floorSize")) score += 1
        if (node.entries.containsKey("geo") || node.entries.containsKey("latitude")) score += 1
        if (node.entries.containsKey("image")) score += 1
        return score
    }

    private fun typesOf(node: JsonValue.Obj): List<String> {
        val raw = node.entries["@type"] ?: node.entries["type"] ?: return emptyList()
        return raw.asArray().mapNotNull { it.asString()?.lowercase(Locale.US) }
    }

    // --- Field extraction ------------------------------------------------------------------

    private fun extractNode(
        node: JsonValue.Obj,
        rootPath: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        fun provenance(path: String, confidence: Double = 0.95) =
            context.provenance.create(ProvenanceMethod.STRUCTURED_DATA, confidence, "$rootPath.$path")

        val name = node.stringOrNull("name")
        if (name != null && name.length in 4..200) {
            builder.addText(PropertyField.LISTING_TITLE, name, provenance("name", 0.8))
            // Portal JSON-LD usually puts the full address in `name`.
            if (node.entries["address"] == null) {
                val parts = ValueParsing.splitAddressLine(name)
                parts.streetAddress?.let { builder.addText(PropertyField.ADDRESS_LINE_1, it, provenance("name", 0.6)) }
                parts.city?.let { builder.addText(PropertyField.CITY, it, provenance("name", 0.6)) }
                parts.state?.let { builder.addText(PropertyField.STATE, it, provenance("name", 0.6)) }
                parts.postalCode?.let { builder.addText(PropertyField.POSTAL_CODE, it, provenance("name", 0.6)) }
            }
        }

        node.stringOrNull("description")?.let { description ->
            builder.addText(
                PropertyField.DESCRIPTION,
                description.take(4000),
                provenance("description", 0.85)
            )
        }

        extractAddress(node, rootPath, context, builder)
        extractGeo(node, rootPath, context, builder)
        extractOffer(node, rootPath, context, builder)
        extractPhysical(node, rootPath, context, builder)
        extractImages(node, rootPath, context, builder)
        extractIdentifiers(node, rootPath, context, builder)
        extractPeople(node, rootPath, context, builder)
    }

    private fun extractAddress(
        node: JsonValue.Obj,
        rootPath: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        fun provenance(path: String) =
            context.provenance.create(ProvenanceMethod.STRUCTURED_DATA, 0.96, "$rootPath.$path")

        when (val address = node.entries["address"]) {
            is JsonValue.Obj -> {
                val street = address.stringOrNull("streetAddress")
                if (street != null && ValueGuards.addressLine(street).accepted) {
                    builder.addText(PropertyField.ADDRESS_LINE_1, street, provenance("address.streetAddress"))
                }
                val unit = address.stringOrNull("unitNumber") ?: address.stringOrNull("extendedAddress")
                unit?.let { builder.addText(PropertyField.UNIT_NUMBER, it.take(12), provenance("address.unitNumber")) }
                address.stringOrNull("addressLocality")?.let {
                    builder.addText(PropertyField.CITY, it, provenance("address.addressLocality"))
                }
                val stateRaw = address.stringOrNull("addressRegion")
                val state = ValueParsing.parseUsState(stateRaw)
                if (state != null) builder.addText(PropertyField.STATE, state, provenance("address.addressRegion"))
                val postal = address.stringOrNull("postalCode")
                if (postal != null && ValueGuards.postalCode(postal, null).accepted) {
                    builder.addText(PropertyField.POSTAL_CODE, ValueParsing.parsePostalCode(postal) ?: postal, provenance("address.postalCode"))
                }
                countryCode(address)?.let { builder.addText(PropertyField.COUNTRY_CODE, it, provenance("address.addressCountry")) }
            }
            is JsonValue.Str -> {
                val parts = ValueParsing.splitAddressLine(address.value)
                parts.streetAddress?.let { builder.addText(PropertyField.ADDRESS_LINE_1, it, provenance("address")) }
                parts.city?.let { builder.addText(PropertyField.CITY, it, provenance("address")) }
                parts.state?.let { builder.addText(PropertyField.STATE, it, provenance("address")) }
                parts.postalCode?.let { builder.addText(PropertyField.POSTAL_CODE, it, provenance("address")) }
            }
            else -> Unit
        }
    }

    private fun countryCode(address: JsonValue.Obj): String? {
        val raw = address.entries["addressCountry"] ?: return null
        val value = when (raw) {
            is JsonValue.Str -> raw.value
            is JsonValue.Obj -> raw.stringOrNull("name") ?: raw.stringOrNull("alternateName")
            else -> null
        } ?: return null
        val trimmed = value.trim()
        if (trimmed.length == 2) return trimmed.uppercase(Locale.US)
        return when (trimmed.lowercase(Locale.US)) {
            "united states", "united states of america", "usa", "us" -> "US"
            "canada" -> "CA"
            "mexico" -> "MX"
            else -> null
        }
    }

    private fun extractGeo(
        node: JsonValue.Obj,
        rootPath: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        fun provenance(path: String) =
            context.provenance.create(ProvenanceMethod.STRUCTURED_DATA, 0.95, "$rootPath.$path")

        val geo = node.entries["geo"] as? JsonValue.Obj
        val latitude = geo?.entries?.get("latitude")?.asNumber() ?: node.entries["latitude"]?.asNumber()
        val longitude = geo?.entries?.get("longitude")?.asNumber() ?: node.entries["longitude"]?.asNumber()
        if (ValueGuards.latitude(latitude).accepted && ValueGuards.longitude(longitude).accepted) {
            builder.addCoordinates(latitude, longitude, provenance("geo"))
        }
    }

    private fun extractOffer(
        node: JsonValue.Obj,
        rootPath: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        val offer = (node.entries["offers"] as? JsonValue.Obj)
            ?: (node.entries["offers"] as? JsonValue.Arr)?.items?.firstOrNull() as? JsonValue.Obj
            ?: node
        val path = if (offer === node) "offers" else "offers"

        fun provenance(field: String) =
            context.provenance.create(ProvenanceMethod.STRUCTURED_DATA, 0.96, "$rootPath.$path.$field")

        val priceRaw = offer.entries["price"] ?: offer.entries["lowPrice"] ?: node.entries["price"]
        val price = priceRaw?.let { raw ->
            raw.asNumber() ?: ValueParsing.parseMoney(raw.asString())
        }
        if (ValueGuards.price(price).accepted) {
            builder.addNumber(PropertyField.PRICE_AMOUNT, price, provenance("price"))
        }
        (offer.stringOrNull("priceCurrency") ?: node.stringOrNull("priceCurrency"))?.let { currency ->
            builder.addText(PropertyField.PRICE_CURRENCY, currency.uppercase(Locale.US).take(3), provenance("priceCurrency"))
        }
        (offer.stringOrNull("availability") ?: node.stringOrNull("availability"))?.let { availability ->
            ValueParsing.normalizeListingStatus(availability)?.let { status ->
                builder.addText(PropertyField.LISTING_STATUS, status, provenance("availability"))
            }
        }
        if (offer.entries["price"] != null || node.entries["price"] != null) {
            // Also carry the raw price string for diagnostics? Not needed: guards already applied.
        }
    }

    private fun extractPhysical(
        node: JsonValue.Obj,
        rootPath: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        fun provenance(field: String, confidence: Double = 0.94) =
            context.provenance.create(ProvenanceMethod.STRUCTURED_DATA, confidence, "$rootPath.$field")

        numberOf("numberOfBedrooms", node)?.let { bedrooms ->
            if (ValueGuards.bedrooms(bedrooms).accepted) {
                builder.addNumber(PropertyField.BEDROOMS, bedrooms, provenance("numberOfBedrooms"))
            }
        }
        numberOf("numberOfBathroomsTotal", node)?.let { bathrooms ->
            if (ValueGuards.bathrooms(bathrooms).accepted) {
                builder.addNumber(PropertyField.BATHROOMS, bathrooms, provenance("numberOfBathroomsTotal"))
            }
        }

        unitValue(node.entries["floorSize"])?.let { (value, unit) ->
            val sqft = ValueParsing.parseAreaSqFt(value.toString(), unit)
            if (ValueGuards.areaSqFt(sqft).accepted) {
                builder.addInt(PropertyField.LIVING_AREA_SQFT, sqft?.toInt(), provenance("floorSize"))
            }
        }
        unitValue(node.entries["lotSize"])?.let { (value, unit) ->
            val sqft = ValueParsing.parseAreaSqFt(value.toString(), unit)
            if (ValueGuards.lotSqFt(sqft).accepted) {
                builder.addInt(PropertyField.LOT_SIZE_SQFT, sqft?.toInt(), provenance("lotSize"))
            }
        }

        node.stringOrNull("yearBuilt")?.let { raw ->
            ValueParsing.parseYearBuilt(raw)?.let { year ->
                val currentYear = 2100 // guards the upper bound; refined by the mapper with the real clock
                if (ValueGuards.yearBuilt(year, currentYear).accepted) {
                    builder.addInt(PropertyField.YEAR_BUILT, year, provenance("yearBuilt"))
                }
            }
        }

        val typeRaw = node.stringOrNull("additionalType") ?: typesOf(node).firstOrNull { it != "product" && it != "offer" }
        ValueParsing.normalizePropertyType(typeRaw)?.let { type ->
            builder.addText(PropertyField.PROPERTY_TYPE, type, provenance("additionalType", 0.7))
        }

        node.stringOrNull("datePosted")?.let { raw ->
            ValueParsing.parseIsoTimestamp(raw)?.let { timestamp ->
                builder.addTimestamp(PropertyField.LISTED_AT, timestamp, provenance("datePosted", 0.8))
            }
        }
    }

    private fun numberOf(key: String, node: JsonValue.Obj): Double? {
        val raw = node.entries[key] ?: return null
        return raw.asNumber() ?: ValueParsing.parseBathrooms(raw.asString())
    }

    private fun unitValue(value: JsonValue?): Pair<Double, String?>? {
        val obj = value as? JsonValue.Obj ?: return null
        val number = obj.entries["value"]?.asNumber() ?: return null
        val unit = obj.stringOrNull("unitCode") ?: obj.stringOrNull("unitText")
        return number to unit
    }

    private fun extractImages(
        node: JsonValue.Obj,
        rootPath: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        val urls = ArrayList<String>()
        fun collect(value: JsonValue?) {
            value?.asArray()?.forEach { item ->
                when (item) {
                    is JsonValue.Str -> urls.add(item.value)
                    is JsonValue.Obj -> collect(item.entries["url"] ?: item.entries["contentUrl"])
                    else -> Unit
                }
            }
            if (value is JsonValue.Str) urls.add(value.value)
        }
        collect(node.entries["image"])
        collect(node.entries["photo"])

        val accepted = urls.map { it.trim() }.filter { ValueGuards.imageUrl(it).accepted }.distinct().take(30)
        if (accepted.isNotEmpty()) {
            builder.addList(
                PropertyField.IMAGE_URLS,
                accepted,
                context.provenance.create(ProvenanceMethod.STRUCTURED_DATA, 0.9, "$rootPath.image")
            )
        }
    }

    private fun extractIdentifiers(
        node: JsonValue.Obj,
        rootPath: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        fun provenance(field: String) =
            context.provenance.create(ProvenanceMethod.STRUCTURED_DATA, 0.9, "$rootPath.$field")

        val identifier = node.entries["identifier"] ?: node.entries["productID"]
        val identifierValue = when (identifier) {
            is JsonValue.Str -> identifier.value
            is JsonValue.Num -> identifier.raw
            is JsonValue.Obj -> identifier.stringOrNull("value") ?: identifier.stringOrNull("name")
            is JsonValue.Arr -> identifier.items.firstOrNull()?.let { item ->
                if (item is JsonValue.Obj) item.stringOrNull("value") ?: item.stringOrNull("name") else item.asString()
            }
            else -> null
        }
        identifierValue?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }?.let { value ->
            builder.addText(PropertyField.SOURCE_LISTING_ID, value, provenance("identifier"))
        }
        (node.stringOrNull("mlsNumber") ?: node.stringOrNull("mlsId"))?.let { mls ->
            builder.addText(PropertyField.MLS_NUMBER, mls.trim().take(32), provenance("mlsNumber"))
        }
    }

    private fun extractPeople(
        node: JsonValue.Obj,
        rootPath: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        fun provenance(field: String) =
            context.provenance.create(ProvenanceMethod.STRUCTURED_DATA, 0.75, "$rootPath.$field")

        val offer = node.entries["offers"] as? JsonValue.Obj
        val broker = (offer?.entries?.get("offeredBy") ?: node.entries["broker"]) as? JsonValue.Obj
        broker?.stringOrNull("name")?.let { builder.addText(PropertyField.BROKER_NAME, it.take(120), provenance("broker.name")) }
        val agent = (broker?.entries?.get("agent") ?: node.entries["agent"]) as? JsonValue.Obj
        agent?.stringOrNull("name")?.let { builder.addText(PropertyField.AGENT_NAME, it.take(120), provenance("agent.name")) }
        if (node.stringOrNull("agentName") != null) {
            builder.addText(PropertyField.AGENT_NAME, node.stringOrNull("agentName")!!.take(120), provenance("agentName"))
        }
    }

    private fun JsonValue.Obj.stringOrNull(key: String): String? =
        entries[key]?.let { raw ->
            raw.asString() ?: (raw as? JsonValue.Obj)?.stringOrNull("name")
        }?.trim()?.takeIf { it.isNotEmpty() }
}
