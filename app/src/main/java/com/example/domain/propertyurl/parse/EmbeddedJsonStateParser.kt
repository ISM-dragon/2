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
 * Parses listing facts out of JSON state embedded in the page (`__NEXT_DATA__`,
 * `window.__PRELOADED_STATE__`, `window.__INITIAL_STATE__`, inline `application/json` script blocks).
 *
 * Strategy: locate the most listing-like node in the payload with a scoring pass, then map its keys
 * through a normalised alias table. This is what makes the layer resilient against portal redesigns:
 * a renamed wrapper (`props.pageProps.initialReduxState.gdp.home` → `props.pageProps.listing`) does
 * not break extraction as long as the leaf keys stay recognisable, and unknown layouts degrade to
 * "fewer fields + PARSE warnings" instead of a hard failure.
 *
 * All values pass through [ValueGuards], which is what keeps a 3 MB payload full of unrelated
 * numbers from producing nonsense (photo counts, school ratings, agent ids…).
 */
class EmbeddedJsonStateParser(
    override val priority: Int = 20,
    private val maxNodesToVisit: Int = 40_000,
    private val maxDepth: Int = 16
) : SourceDocumentParser {

    override val parserId: String = "embedded-json-state"

    override val version: String = "1.3.0"

    private val markers = listOf(
        HtmlScanner.Marker.NEXT_DATA,
        HtmlScanner.Marker.PRELOADED_STATE,
        HtmlScanner.Marker.INITIAL_STATE,
        HtmlScanner.Marker.REACT_SERVER_STATE,
        HtmlScanner.Marker.windowAssignment("__data__"),
        HtmlScanner.Marker.windowAssignment("__STORE__"),
        HtmlScanner.Marker.windowAssignment("__NUXT__")
    )

    override fun canParse(document: RawSourceDocument): Boolean {
        if (!document.isHtml && !document.isJson) return false
        val body = document.bodyOrEmpty
        return document.isJson || markers.any { body.contains(it.token, ignoreCase = true) }
    }

    override fun parse(document: RawSourceDocument, context: ParserContext): ParserResult {
        val warnings = ArrayList<ImportWarning>()
        val candidates = ArrayList<Pair<String, JsonValue>>()

        if (document.isJson) {
            JsonParser().parse(document.bodyOrEmpty).let { result ->
                if (result is JsonParseResult.Success) candidates.add("body" to result.value)
                else if (result is JsonParseResult.Failure) {
                    warnings.add(
                        ImportWarning(
                            code = ImportWarning.WarningCode.PARSER_SCHEMA_DRIFT,
                            message = "JSON body is malformed: ${result.message}",
                            sourceId = context.source.sourceId
                        )
                    )
                }
            }
        }

        markers.forEach { marker ->
            HtmlScanner.embeddedJsonAfter(document.bodyOrEmpty, marker.token)?.let { json ->
                when (val parsed = JsonParser().parse(json)) {
                    is JsonParseResult.Success -> candidates.add(marker.label to parsed.value)
                    is JsonParseResult.Failure -> warnings.add(
                        ImportWarning(
                            code = ImportWarning.WarningCode.PARSER_SCHEMA_DRIFT,
                            message = "${marker.label} payload is malformed: ${parsed.message}",
                            sourceId = context.source.sourceId
                        )
                    )
                }
            }
        }

        HtmlScanner.scriptBlocksOfType(document.bodyOrEmpty, "application/json").forEachIndexed { index, block ->
            val label = block.id?.let { "script#$it" } ?: "script-json[$index]"
            if (candidates.none { it.first == label }) {
                (JsonParser().parse(block.content) as? JsonParseResult.Success)?.let { candidates.add(label to it.value) }
            }
        }

        if (candidates.isEmpty()) {
            // Markers were present but nothing usable could be parsed: report it (schema drift) instead
            // of silently declining — "the portal changed its payload shape" is exactly what ops needs.
            return if (warnings.isEmpty()) {
                ParserResult.declined("no embedded JSON payload found")
            } else {
                ParserResult(
                    warnings = warnings,
                    declined = true,
                    reason = "embedded JSON payload present but unparseable"
                )
            }
        }

        var visited = 0
        val best = candidates
            .asSequence()
            .mapNotNull { (label, root) ->
                val scored = bestListingNode(root, label, 0, IntArray(1))
                visited += scored.second
                scored.first
            }
            .filter { it.score >= MIN_SCORE }
            .maxByOrNull { it.score }

        if (best == null) {
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.PARTIAL_PARSE,
                    message = "Embedded JSON scanned ($visited nodes) without a recognised listing node",
                    sourceId = context.source.sourceId
                )
            )
            return ParserResult(facts = ExtractedFacts.EMPTY, warnings = warnings)
        }

        val builder = ExtractedFactsBuilder()
        extractListing(best.node, best.path, context, builder, pricesSeen = HashSet())

        if (builder.isEmpty()) {
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.PARSER_SCHEMA_DRIFT,
                    message = "Listing node at ${best.path} contained no mappable fields",
                    sourceId = context.source.sourceId
                )
            )
        }

        return ParserResult(facts = builder.build(), warnings = warnings)
    }

    // --- Node discovery ---------------------------------------------------------------------

    private data class ScoredNode(val node: JsonValue.Obj, val path: String, val score: Int)

    private fun bestListingNode(root: JsonValue, label: String, depth: Int, counter: IntArray): Pair<ScoredNode?, Int> {
        if (depth > maxDepth || counter[0] > maxNodesToVisit) return null to counter[0]
        var best: ScoredNode? = null
        when (root) {
            is JsonValue.Obj -> {
                counter[0]++
                val score = scoreNode(root)
                if (score > (best?.score ?: 0)) best = ScoredNode(root, label, score)
                root.entries.forEach { (key, child) ->
                    val nested = bestListingNode(child, "$label.$key", depth + 1, counter)
                    if ((nested.first?.score ?: 0) > (best?.score ?: 0)) best = nested.first
                }
            }
            is JsonValue.Arr -> root.items.forEachIndexed { index, child ->
                val nested = bestListingNode(child, "$label[$index]", depth + 1, counter)
                if ((nested.first?.score ?: 0) > (best?.score ?: 0)) best = nested.first
            }
            else -> Unit
        }
        return best to counter[0]
    }

    /**
     * Structural score of a node. Deliberately *not* alias driven: it looks for the presence of
     * several independent listing concepts at once.
     */
    private fun scoreNode(node: JsonValue.Obj): Int {
        var score = 0
        val keys = node.entries.keys.map { normalizeKey(it) }.toSet()

        if (keys.any { it in PRICE_KEYS }) score += 3
        if (keys.any { it in BED_KEYS }) score += 2
        if (keys.any { it in BATH_KEYS }) score += 2
        if (keys.any { it in AREA_KEYS }) score += 2
        if (keys.any { it in STREET_KEYS }) score += 3
        if (keys.any { it in CITY_KEYS }) score += 2
        if (keys.any { it in ZIP_KEYS }) score += 2
        if (keys.any { it in PHOTO_KEYS }) score += 1
        if (keys.any { it in TYPE_KEYS }) score += 1
        if (keys.any { it in STATUS_KEYS }) score += 1
        if (node.entries["address"] is JsonValue.Obj) score += 2
        if (node.entries["geo"] is JsonValue.Obj || node.entries["latLong"] is JsonValue.Obj) score += 1
        // Wrapper nodes that merely contain the listing must not win.
        if (keys.any { it == "results" || it == "searchresults" || it == "listings" }) score -= 1
        return score
    }

    private fun normalizeKey(key: String): String =
        key.lowercase(Locale.US).replace("_", "").replace("-", "").replace(" ", "")

    // --- Field extraction -------------------------------------------------------------------

    private data class Alias(
        val field: PropertyField,
        val keys: Set<String>,
        val confidence: Double
    )

    private val aliases: List<Alias> = listOf(
        Alias(PropertyField.PRICE_AMOUNT, setOf("price", "listprice", "unformattedprice", "pricevalue", "listpricevalue", "askingprice", "priceamount", "currentprice"), 0.92),
        Alias(PropertyField.BEDROOMS, setOf("bedrooms", "beds", "bedroomcount", "bedcount", "numbeds", "bedsCount".lowercase()), 0.9),
        Alias(PropertyField.BATHROOMS, setOf("bathrooms", "baths", "bathroomcount", "bathcount", "numbaths", "bathroomstotal", "bathroomstotalinteger"), 0.9),
        Alias(PropertyField.LIVING_AREA_SQFT, setOf("livingarea", "livingareavalue", "livingareasqft", "sqft", "squarefeet", "finishedsqft", "floorarea", "homearea"), 0.88),
        Alias(PropertyField.LOT_SIZE_SQFT, setOf("lotsize", "lotsizesqft", "lotsquarefeet", "lotareavalue", "lotsizearea", "lotsqft", "lotarea"), 0.85),
        Alias(PropertyField.YEAR_BUILT, setOf("yearbuilt", "builtyear", "constructionyear", "yearconstructed"), 0.9),
        Alias(PropertyField.PROPERTY_TYPE, setOf("hometype", "propertytype", "hometypename", "propertysubtype", "hometypeid"), 0.8),
        Alias(PropertyField.LISTING_STATUS, setOf("listingstatus", "standardstatus", "mlsstatus", "status", "listingstatusvalue"), 0.75),
        Alias(PropertyField.MLS_NUMBER, setOf("mlsid", "mlsnumber", "mlslistingid", "mlsnumberstring"), 0.88),
        Alias(PropertyField.SOURCE_LISTING_ID, setOf("zpid", "listingid", "propertyid", "homeid", "propertykey"), 0.85),
        Alias(PropertyField.DESCRIPTION, setOf("description", "publicremarks", "marketingremarks", "remarks", "listingdescription"), 0.8),
        Alias(PropertyField.LISTING_TITLE, setOf("name", "title", "listingtitle", "headline"), 0.6),
        Alias(PropertyField.HOA_FEE_MONTHLY, setOf("hoafee", "hoafeemonthly", "monthlyhoafee", "associationfee", "hoafeeamount", "hoa"), 0.8),
        Alias(PropertyField.ANNUAL_TAX_AMOUNT, setOf("taxannualamount", "annualtaxamount", "propertytax", "taxamount", "taxes"), 0.8),
        Alias(PropertyField.DAYS_ON_MARKET, setOf("daysonzillow", "daysonmarket", "timeonredfin", "dom", "cumulativedaysonmarket"), 0.8),
        Alias(PropertyField.STORIES, setOf("stories", "storiesTotal".lowercase(), "numberofstories", "levels"), 0.7),
        Alias(PropertyField.GARAGE_SPACES, setOf("garagespaces", "garage", "parkingcapacity", "parkingtotal"), 0.7),
        Alias(PropertyField.AGENT_NAME, setOf("agentname", "listingagentname", "listagentfullname", "agentfullname"), 0.7),
        Alias(PropertyField.BROKER_NAME, setOf("brokername", "listofficename", "brokeragename", "officename"), 0.7)
    )

    private val dateAliases = Alias(
        PropertyField.LISTED_AT,
        setOf("dateposted", "listdate", "listingdate", "onmarketdate", "createddate"),
        0.75
    )

    private fun extractListing(
        node: JsonValue.Obj,
        path: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder,
        pricesSeen: MutableSet<String>
    ) {
        fun provenance(field: String, confidence: Double, keyPath: String = field) =
            context.provenance.create(ProvenanceMethod.EMBEDDED_STATE, confidence, "$path.$keyPath")

        // 1. Alias driven scalar extraction at this level.
        node.entries.forEach { (key, value) ->
            val normalized = normalizeKey(key)
            aliases.forEach { alias ->
                if (alias.keys.contains(normalized)) {
                    applyAlias(alias, key, value, path, context, builder)
                }
            }
            if (dateAliases.keys.contains(normalized)) {
                val raw = value.asString()
                ValueParsing.parseIsoTimestamp(raw)?.let { timestamp ->
                    builder.addTimestamp(PropertyField.LISTED_AT, timestamp, provenance(key, dateAliases.confidence))
                }
            }
        }

        // 2. Nested address objects.
        (node.entries["address"] ?: node.entries["location"] as? JsonValue.Obj)?.let { addressValue ->
            when (addressValue) {
                is JsonValue.Obj -> extractAddress(addressValue, "$path.address", context, builder)
                is JsonValue.Str -> {
                    val parts = ValueParsing.splitAddressLine(addressValue.value)
                    val method = ProvenanceMethod.EMBEDDED_STATE
                    parts.streetAddress?.let {
                        builder.addText(PropertyField.ADDRESS_LINE_1, it, context.provenance.create(method, 0.7, "$path.address"))
                    }
                    parts.city?.let {
                        builder.addText(PropertyField.CITY, it, context.provenance.create(method, 0.7, "$path.address"))
                    }
                    parts.state?.let {
                        builder.addText(PropertyField.STATE, it, context.provenance.create(method, 0.7, "$path.address"))
                    }
                    parts.postalCode?.let {
                        builder.addText(PropertyField.POSTAL_CODE, it, context.provenance.create(method, 0.7, "$path.address"))
                    }
                }
                else -> Unit
            }
        }

        // 3. Coordinates (several shapes across portals).
        val geo = node.entries["geo"] as? JsonValue.Obj
            ?: node.entries["latLong"] as? JsonValue.Obj
            ?: node.entries["coordinates"] as? JsonValue.Obj
        val latitude = geo?.entries?.get("latitude")?.asNumber()
            ?: node.entries["latitude"]?.asNumber()
            ?: node.entries["lat"]?.asNumber()
        val longitude = geo?.entries?.get("longitude")?.asNumber()
            ?: node.entries["longitude"]?.asNumber()
            ?: node.entries["lng"]?.asNumber()
        if (ValueGuards.latitude(latitude).accepted && ValueGuards.longitude(longitude).accepted) {
            builder.addCoordinates(latitude, longitude, provenance("geo", 0.85))
        }

        // 4. Photos.
        val photos = ArrayList<String>()
        listOf("photos", "images", "photoUrls", "media", "gallery").forEach { key ->
            collectPhotos(node.entries[key], photos, 0)
        }
        val acceptedPhotos = photos.map { it.trim() }.filter { ValueGuards.imageUrl(it).accepted }.distinct().take(30)
        if (acceptedPhotos.isNotEmpty()) {
            builder.addList(PropertyField.IMAGE_URLS, acceptedPhotos, provenance("photos", 0.8))
        }

        // 5. Deep fallback: some payloads nest price under `price.value` / `listPrice.amount`.
        if (!builder.contains(PropertyField.PRICE_AMOUNT)) {
            PRICE_KEYS.forEach { key ->
                val candidate = node.entries.entries.firstOrNull { normalizeKey(it.key) == key }?.value
                val nested = (candidate as? JsonValue.Obj)?.let { obj ->
                    obj.entries["value"]?.asNumber() ?: obj.entries["amount"]?.asNumber()
                }
                val price = nested ?: candidate?.asNumber() ?: ValueParsing.parseMoney(candidate?.asString())
                val guardKey = "$path.$key=$price"
                if (price != null && pricesSeen.add(guardKey) && ValueGuards.price(price).accepted) {
                    builder.addNumber(PropertyField.PRICE_AMOUNT, price, provenance(key, 0.7, key))
                }
            }
        }
    }

    private fun applyAlias(
        alias: Alias,
        key: String,
        value: JsonValue,
        path: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        val rawPath = "$path.$key"
        val provenance = context.provenance.create(ProvenanceMethod.EMBEDDED_STATE, alias.confidence, rawPath)

        when (alias.field) {
            PropertyField.PRICE_AMOUNT -> {
                val price = value.asNumber() ?: ValueParsing.parseMoney(value.asString())
                    ?: deepNumber(value)
                if (ValueGuards.price(price).accepted) builder.addNumber(alias.field, price, provenance)
            }
            PropertyField.BEDROOMS -> {
                val beds = value.asNumber() ?: ValueParsing.parseBedrooms(value.asString())
                if (ValueGuards.bedrooms(beds).accepted) builder.addNumber(alias.field, beds, provenance)
            }
            PropertyField.BATHROOMS -> {
                val baths = value.asNumber() ?: ValueParsing.parseBathrooms(value.asString())
                if (ValueGuards.bathrooms(baths).accepted) builder.addNumber(alias.field, baths, provenance)
            }
            PropertyField.LIVING_AREA_SQFT -> {
                val sqft = value.asNumber() ?: ValueParsing.parseAreaSqFt(value.asString())
                if (ValueGuards.areaSqFt(sqft).accepted) builder.addInt(alias.field, sqft?.toInt(), provenance)
            }
            PropertyField.LOT_SIZE_SQFT -> {
                val sqft = value.asNumber()?.let { ValueParsing.parseAreaSqFt(it.toString(), rawPath) }
                    ?: ValueParsing.parseAreaSqFt(value.asString())
                if (ValueGuards.lotSqFt(sqft).accepted) builder.addInt(alias.field, sqft?.toInt(), provenance)
            }
            PropertyField.YEAR_BUILT -> {
                val year = value.asNumber()?.toInt() ?: ValueParsing.parseYearBuilt(value.asString())
                if (year != null && year in 1800..2100) builder.addInt(alias.field, year, provenance)
            }
            PropertyField.PROPERTY_TYPE -> {
                ValueParsing.normalizePropertyType(value.asString())?.let { type ->
                    builder.addText(alias.field, type, provenance)
                }
            }
            PropertyField.LISTING_STATUS -> {
                ValueParsing.normalizeListingStatus(value.asString())?.let { status ->
                    builder.addText(alias.field, status, provenance)
                }
            }
            PropertyField.DAYS_ON_MARKET -> {
                val dom = value.asNumber()?.toInt() ?: ValueParsing.parseDaysOnMarket(value.asString())
                if (ValueGuards.daysOnMarket(dom).accepted) builder.addInt(alias.field, dom, provenance)
            }
            PropertyField.HOA_FEE_MONTHLY, PropertyField.ANNUAL_TAX_AMOUNT -> {
                val amount = value.asNumber() ?: ValueParsing.parseMoney(value.asString())
                if (amount != null && amount >= 0 && amount < 1_000_000) {
                    builder.addNumber(alias.field, amount, provenance)
                }
            }
            PropertyField.STORIES -> {
                value.asNumber()?.takeIf { it in 1.0..60.0 }?.let { stories ->
                    builder.addInt(alias.field, stories.toInt(), provenance)
                }
            }
            PropertyField.GARAGE_SPACES -> {
                value.asNumber()?.takeIf { it in 0.0..20.0 }?.let { spaces ->
                    builder.addInt(alias.field, spaces.toInt(), provenance)
                }
            }
            PropertyField.DESCRIPTION -> {
                value.asString()?.trim()?.takeIf { it.length >= 20 }?.let { description ->
                    builder.addText(alias.field, description.take(4000), provenance)
                }
            }
            PropertyField.LISTING_TITLE -> {
                value.asString()?.trim()?.takeIf { it.length in 8..300 }?.let { title ->
                    builder.addText(alias.field, title, provenance)
                }
            }
            PropertyField.MLS_NUMBER, PropertyField.SOURCE_LISTING_ID,
            PropertyField.AGENT_NAME, PropertyField.BROKER_NAME -> {
                val text = value.asString()?.trim() ?: value.asNumber()?.toLong()?.toString()
                if (text != null && text.length in 2..64) builder.addText(alias.field, text, provenance)
            }
            else -> Unit
        }
    }

    private fun deepNumber(value: JsonValue): Double? {
        val obj = value as? JsonValue.Obj ?: return null
        return obj.entries["value"]?.asNumber()
            ?: obj.entries["amount"]?.asNumber()
            ?: obj.entries["raw"]?.asNumber()
    }

    private fun extractAddress(
        address: JsonValue.Obj,
        path: String,
        context: ParserContext,
        builder: ExtractedFactsBuilder
    ) {
        fun provenance(key: String, confidence: Double = 0.9) =
            context.provenance.create(ProvenanceMethod.EMBEDDED_STATE, confidence, "$path.$key")

        address.entries.forEach { (key, value) ->
            val normalized = normalizeKey(key)
            val text = value.asString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            when {
                normalized in STREET_KEYS -> if (ValueGuards.addressLine(text).accepted) {
                    builder.addText(PropertyField.ADDRESS_LINE_1, text, provenance(key))
                }
                normalized in CITY_KEYS -> builder.addText(PropertyField.CITY, text, provenance(key))
                normalized in STATE_KEYS -> ValueParsing.parseUsState(text)?.let { state ->
                    builder.addText(PropertyField.STATE, state, provenance(key))
                }
                normalized in ZIP_KEYS -> ValueGuards.postalCode(text, null).let { guard ->
                    if (guard.accepted) {
                        builder.addText(PropertyField.POSTAL_CODE, ValueParsing.parsePostalCode(text) ?: text, provenance(key))
                    }
                }
                normalized in setOf("unitnumber", "unit", "aptnumber", "apartmentnumber") -> {
                    builder.addText(PropertyField.UNIT_NUMBER, text.take(12), provenance(key, 0.8))
                }
                normalized in setOf("county", "countyname") -> builder.addText(PropertyField.COUNTY, text, provenance(key, 0.7))
                normalized in setOf("country", "countrycode") -> {
                    builder.addText(PropertyField.COUNTRY_CODE, text.take(2).uppercase(Locale.US), provenance(key, 0.7))
                }
            }
        }
    }

    private fun collectPhotos(value: JsonValue?, sink: MutableList<String>, depth: Int) {
        if (value == null || depth > 4) return
        when (value) {
            is JsonValue.Str -> sink.add(value.value)
            is JsonValue.Arr -> value.items.forEach { collectPhotos(it, sink, depth + 1) }
            is JsonValue.Obj -> {
                // Prefer explicit url fields, then recurse into containers.
                val direct = value.entries["url"]?.asString()
                    ?: value.entries["href"]?.asString()
                    ?: value.entries["src"]?.asString()
                if (direct != null) {
                    sink.add(direct)
                } else if (value.entries.containsKey("jpeg") || value.entries.containsKey("mixedSources")) {
                    value.entries.forEach { (_, child) -> collectPhotos(child, sink, depth + 1) }
                } else {
                    value.entries.forEach { (key, child) ->
                        if (key in setOf("photos", "images", "items", "results", "sources", "jpeg")) {
                            collectPhotos(child, sink, depth + 1)
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private companion object {
        const val MIN_SCORE = 6

        val PRICE_KEYS = setOf("price", "listprice", "unformattedprice", "pricevalue", "listpricevalue", "askingprice", "priceamount", "currentprice")
        val BED_KEYS = setOf("bedrooms", "beds", "bedroomcount", "bedcount", "numbeds")
        val BATH_KEYS = setOf("bathrooms", "baths", "bathroomcount", "bathcount", "numbaths", "bathroomstotal")
        val AREA_KEYS = setOf("livingarea", "livingareavalue", "livingareasqft", "sqft", "squarefeet", "finishedsqft", "floorarea")
        val STREET_KEYS = setOf("streetaddress", "addressline1", "address1", "streetline", "line1", "street")
        val CITY_KEYS = setOf("city", "addresslocality", "locality", "cityname")
        val STATE_KEYS = setOf("state", "statecode", "addressregion", "region")
        val ZIP_KEYS = setOf("zipcode", "zip", "postalcode", "zipcode5")
        val PHOTO_KEYS = setOf("photos", "images", "photourls", "media", "gallery")
        val TYPE_KEYS = setOf("hometype", "propertytype", "hometypename", "propertysubtype")
        val STATUS_KEYS = setOf("listingstatus", "standardstatus", "mlsstatus", "status")
    }
}
