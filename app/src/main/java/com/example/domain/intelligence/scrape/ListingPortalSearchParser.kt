package com.example.domain.intelligence.scrape

import com.example.domain.propertyurl.json.JsonParser
import com.example.domain.propertyurl.json.JsonValue

/** Outcome of reading one fetched page. */
data class PortalSearchParse(
    val listings: List<ScrapedListing>,
    /** Which reader produced the listings — `null` when nothing was recognised. */
    val strategy: String?,
    val warnings: List<String> = emptyList()
)

/**
 * Reads a portal *search results* page into listings.
 *
 * Three readers, tried in order of trust, because a portal page carries the same facts at several
 * layers and any one of them can disappear without notice:
 *
 *  1. `embedded-state` — the JSON the portal embeds for its own front end
 *     (`searchPageState` inside `__NEXT_DATA__`, or a `window.*_STATE` assignment). This is the only
 *     source that carries attribution/agent data, so it is always preferred.
 *  2. `json-ld` — schema.org `ItemList` blocks. Present on many search pages, no agent data.
 *  3. `html-cards` — regex over the rendered card markup. Last resort: keeps the feature returning
 *     *something* after a payload redesign, and marks every record so the UI can show lower trust.
 *
 * Nothing is estimated. A field the page does not carry stays null; a page we cannot read at all
 * produces zero listings plus a warning, which the scraper turns into
 * [ListingScrapeFailureKind.PARSE_DRIFT] rather than an empty result the user would read as
 * "nothing for sale here".
 */
class ListingPortalSearchParser(
    private val sourceId: String = ZillowSearchUrlBuilder.SOURCE_ID,
    private val origin: String = ZillowSearchUrlBuilder.DEFAULT_ORIGIN,
    private val jsonMaxDepth: Int = 96
) {

    fun parse(html: String, pageUrl: String): PortalSearchParse {
        if (html.isBlank()) return PortalSearchParse(emptyList(), null, listOf("empty response body"))

        val warnings = ArrayList<String>()

        val fromState = embeddedStateListings(html, pageUrl, warnings)
        if (fromState.isNotEmpty()) return PortalSearchParse(dedupe(fromState), "embedded-state", warnings)

        val fromJsonLd = jsonLdListings(html, pageUrl, warnings)
        if (fromJsonLd.isNotEmpty()) return PortalSearchParse(dedupe(fromJsonLd), "json-ld", warnings)

        val fromCards = htmlCardListings(html, pageUrl, warnings)
        if (fromCards.isNotEmpty()) return PortalSearchParse(dedupe(fromCards), "html-cards", warnings)

        return PortalSearchParse(emptyList(), null, warnings)
    }

    // --- Reader 1: embedded JSON state -------------------------------------------------------

    private fun embeddedStateListings(html: String, pageUrl: String, warnings: MutableList<String>): List<ScrapedListing> {
        val listings = ArrayList<ScrapedListing>()
        var foundStates = 0
        var parseFailures = 0

        for (stateJson in extractEmbeddedStates(html)) {
            foundStates++
            for (node in listingNodes(stateJson)) {
                mapListingNode(node, pageUrl)?.let { listings += it }
            }
        }
        if (foundStates == 0) {
            warnings += "no embedded search state found in page"
        } else if (listings.isEmpty() && parseFailures == 0) {
            warnings += "embedded search state present but carried no listing nodes (portal layout drift)"
        }
        return listings
    }

    /**
     * Finds every `searchPageState` object in the page and returns it parsed.
     *
     * Keyed on the state's name rather than on the surrounding `<script>` tag because Zillow has
     * shipped this payload inside `__NEXT_DATA__`, inside `window.DIGITAL_PROP_STATE = {…}`, and
     * inside a `data-zjson` attribute — all of which contain the same `searchPageState` object.
     */
    internal fun extractEmbeddedStates(html: String): List<JsonValue> {
        val states = ArrayList<JsonValue>()
        var searchFrom = 0
        while (states.size < MAX_STATE_BLOCKS) {
            val keyAt = html.indexOf(STATE_KEY, searchFrom, ignoreCase = false)
            if (keyAt < 0) break
            searchFrom = keyAt + STATE_KEY.length
            val braceAt = html.indexOf('{', searchFrom)
            if (braceAt < 0 || braceAt - keyAt > 64) break // not an object assignment we recognise
            val raw = extractBalancedObject(html, braceAt) ?: continue
            JsonParser.parseOrNull(raw, jsonMaxDepth)?.let { states += it }
        }
        return states
    }

    /** Listing arrays inside a `searchPageState` tree, flattened to individual listing nodes. */
    internal fun listingNodes(state: JsonValue): List<JsonValue> {
        val arrays = ArrayList<List<JsonValue>>()
        for (category in listOf("cat1", "cat2", "")) {
            val prefix = if (category.isEmpty()) "" else "$category."
            state.path("${prefix}searchList")?.asArray()?.let { arrays += it }
            state.path("${prefix}searchResults.mapResults")?.asArray()?.let { arrays += it }
            state.path("${prefix}searchResults.listResults")?.asArray()?.let { arrays += it }
        }
        val nodes = ArrayList<JsonValue>()
        val seen = HashSet<String>()
        for (array in arrays) {
            for (element in array) {
                // Newer payloads nest one more level: searchList[i].list[j] is the actual node.
                val nested = element.path("list")?.asArray()?.takeIf { it.isNotEmpty() }
                val candidates = nested ?: listOf(element)
                for (node in candidates) {
                    val key = listingKeyOf(node) ?: continue
                    if (seen.add(key)) nodes += node
                }
            }
        }
        return nodes
    }

    private fun listingKeyOf(node: JsonValue): String? {
        val zpid = node.path("zpid")?.asString()?.takeIf { it.isNotBlank() }
            ?: node.path("hdpData.homeInfo.zpid")?.asString()?.takeIf { it.isNotBlank() }
        val id = node.path("id")?.asString()?.takeIf { it.isNotBlank() }
        val url = node.path("detailUrl")?.asString()?.takeIf { it.isNotBlank() }
        return zpid ?: id ?: url
    }

    private fun mapListingNode(node: JsonValue, pageUrl: String): ScrapedListing? {
        val home = node.path("hdpData.homeInfo")
        val attribution = home?.path("attributionInfo")
        val externalId = node.path("zpid")?.asString()?.takeIf { it.isNotBlank() }
            ?: home?.path("zpid")?.asString()?.takeIf { it.isNotBlank() }
            ?: node.path("id")?.asString()?.let { digitsOf(it) }

        val detailUrl = absoluteUrl(node.path("detailUrl")?.asString(), pageUrl)
        if (externalId == null && detailUrl == null) return null

        val address = node.path("address")?.asString()?.trim()?.takeIf { it.isNotBlank() }
            ?: composeAddress(home)
        val street = home?.path("streetAddress")?.asString()?.takeIf { it.isNotBlank() }
        val city = home?.path("city")?.asString()?.takeIf { it.isNotBlank() }
        val state = home?.path("state")?.asString()?.takeIf { it.isNotBlank() }
        val zip = home?.path("zipcode")?.asString()?.takeIf { it.isNotBlank() }

        val priceLabel = node.path("price")?.asString()?.takeIf { it.isNotBlank() }
        val price = home?.path("listPrice")?.asNumber()
            ?: home?.path("price")?.asNumber()
            ?: priceLabel?.let { parseMoney(it) }

        val beds = node.path("beds")?.asInt() ?: home?.path("bedrooms")?.asInt()
        val baths = node.path("baths")?.asNumber() ?: home?.path("bathrooms")?.asNumber()
        val area = node.path("area")?.asInt() ?: home?.path("livingArea")?.asInt()
        val lot = home?.path("lotSize")?.asNumber()?.let { if (it.isFinite() && it > 0) it.toLong() else null }
        val year = home?.path("yearBuilt")?.asInt()

        val latitude = node.path("latLong.latitude")?.asNumber() ?: home?.path("latitude")?.asNumber()
        val longitude = node.path("latLong.longitude")?.asNumber() ?: home?.path("longitude")?.asNumber()

        val daysOnMarket = home?.path("daysOnZillow")?.asInt()
            ?: node.path("timeOnZillow")?.asNumber()?.let { ms ->
                if (ms.isFinite() && ms > 0) (ms / MILLIS_PER_DAY).toInt() else null
            }

        return ScrapedListing(
            sourceId = sourceId,
            externalId = externalId,
            detailUrl = detailUrl.orEmpty(),
            address = address,
            street = street,
            city = city,
            state = state,
            zipCode = zip,
            price = price,
            priceLabel = priceLabel ?: price?.let { formatMoney(it) },
            priceUnit = priceLabel?.let { rentUnitOf(it) } ?: rentUnitOf(home?.path("homeStatus")?.asString().orEmpty()),
            bedrooms = beds,
            bathrooms = baths,
            livingAreaSqFt = area,
            lotSizeSqFt = lot,
            yearBuilt = year,
            propertyType = propertyTypeOf(home?.path("homeType")?.asString()),
            status = node.path("statusType")?.asString() ?: home?.path("homeStatus")?.asString(),
            statusText = node.path("statusText")?.asString(),
            daysOnMarket = daysOnMarket,
            listDate = home?.path("listDate")?.asString()?.takeIf { it.isNotBlank() },
            latitude = latitude,
            longitude = longitude,
            photos = photosOf(node),
            mlsId = attribution?.path("mlsId")?.asString()?.takeIf { it.isNotBlank() }
                ?: home?.path("mlsid")?.asString()?.takeIf { it.isNotBlank() },
            listingAgentName = firstText(
                attribution?.path("listingAgentName"),
                attribution?.path("agentName"),
                attribution?.path("brokerAgentName")
            ),
            listingAgentPhone = firstPhoneIn(attribution, "agent"),
            listingOfficeName = firstText(
                attribution?.path("listingOfficeName"),
                attribution?.path("brokerName"),
                attribution?.path("officeName")
            ),
            listingOfficePhone = firstPhoneIn(attribution, "office") ?: firstPhoneIn(attribution, "broker"),
            brokerName = attribution?.path("brokerName")?.asString()?.takeIf { it.isNotBlank() },
            isBrokerPaidPlacement = node.path("isPremierAgent")?.asBool()
                ?: home?.path("isPremierAgent")?.asBool() ?: false,
            openHouse = openHouseOf(node, home),
            parseStrategy = "embedded-state"
        )
    }

    /** Phones are only ever published inside the attribution block, under several spellings. */
    private fun firstPhoneIn(attribution: JsonValue?, roleHint: String): String? {
        val obj = attribution?.asObject() ?: return null
        val preferred = obj.entries
            .filter { (key, value) ->
                key.contains("phone", ignoreCase = true) &&
                    key.contains(roleHint, ignoreCase = true) &&
                    value.asString()?.let { digitsOf(it).length >= 10 } == true
            }
            .mapNotNull { (_, value) -> value.asString()?.let { normalizePhone(it) } }
        if (preferred.isNotEmpty()) return preferred.first()
        val any = obj.entries
            .filter { (key, value) ->
                key.contains("phone", ignoreCase = true) &&
                    value.asString()?.let { digitsOf(it).length >= 10 } == true
            }
            .mapNotNull { (_, value) -> value.asString()?.let { normalizePhone(it) } }
        return any.firstOrNull()
    }

    private fun firstText(vararg values: JsonValue?): String? =
        values.asSequence().mapNotNull { it?.asString() }.firstOrNull { it.isNotBlank() }

    private fun photosOf(node: JsonValue): List<String> {
        val urls = ArrayList<String>()
        fun add(raw: String?) {
            absoluteUrl(raw, origin)?.takeIf { it.isNotBlank() }?.let { urls += it }
        }
        add(node.path("imgSrc")?.asString())
        node.path("images")?.asArray()?.forEach { photo -> add(photo.asString()) }
        node.path("hdpData.homeInfo.photos")?.asArray()?.forEach { photo ->
            add(photo.path("url")?.asString() ?: photo.asString())
        }
        return urls.distinct().take(MAX_PHOTOS)
    }

    private fun openHouseOf(node: JsonValue, home: JsonValue?): String? {
        val text = node.path("openHouse")?.asString()?.takeIf { it.isNotBlank() }
        if (text != null) return text
        val upcoming = home?.path("openHouseUpcoming")?.asString()?.takeIf { it.isNotBlank() }
        if (upcoming != null) return upcoming
        val firstSlot = home?.path("openHouseDates")?.asArray()?.firstOrNull()?.asString()
        return firstSlot?.takeIf { it.isNotBlank() }
    }

    private fun composeAddress(home: JsonValue?): String? {
        if (home == null) return null
        val street = home.path("streetAddress")?.asString() ?: return null
        return formatAddress(
            street = street,
            city = home.path("city")?.asString(),
            state = home.path("state")?.asString(),
            zip = home.path("zipcode")?.asString()
        )
    }

    // --- Reader 2: schema.org ItemList --------------------------------------------------------

    private fun jsonLdListings(html: String, pageUrl: String, warnings: MutableList<String>): List<ScrapedListing> {
        val listings = ArrayList<ScrapedListing>()
        for (block in extractJsonLdBlocks(html)) {
            val value = JsonParser.parseOrNull(block, jsonMaxDepth) ?: continue
            for (candidate in listOfNotNull(value) + value.asArray()) {
                val elements = candidate.path("itemListElement")?.asArray() ?: continue
                for (element in elements) {
                    val item = element.path("item") ?: element
                    mapJsonLdItem(item, pageUrl)?.let { listings += it }
                }
            }
        }
        if (listings.isEmpty()) warnings += "no schema.org ItemList listings in page"
        return listings
    }

    private fun mapJsonLdItem(item: JsonValue, pageUrl: String): ScrapedListing? {
        val name = item.path("name")?.asString()?.takeIf { it.isNotBlank() }
        val addressObj = item.path("address")
        val address = addressObj?.path("streetAddress")?.asString()?.let { street ->
            formatAddress(
                street = street,
                city = addressObj.path("addressLocality")?.asString(),
                state = addressObj.path("addressRegion")?.asString(),
                zip = addressObj.path("postalCode")?.asString()
            )
        } ?: name
        if (address.isNullOrBlank()) return null

        val url = absoluteUrl(item.path("url")?.asString(), pageUrl) ?: return null
        val price = item.path("offers.price")?.asNumber() ?: item.path("price")?.asNumber()

        return ScrapedListing(
            sourceId = sourceId,
            externalId = externalIdFromUrl(url),
            detailUrl = url,
            address = address,
            city = addressObj?.path("addressLocality")?.asString(),
            state = addressObj?.path("addressRegion")?.asString(),
            zipCode = addressObj?.path("postalCode")?.asString(),
            price = price,
            priceLabel = price?.let { formatMoney(it) },
            bedrooms = item.path("numberOfBedrooms")?.asInt() ?: item.path("numberOfRooms")?.asInt(),
            bathrooms = item.path("numberOfBathroomsTotal")?.asNumber() ?: item.path("numberOfBathroomsFull")?.asNumber(),
            livingAreaSqFt = item.path("floorSize.value")?.asInt(),
            lotSizeSqFt = item.path("lotSize.value")?.asNumber()?.let { if (it > 0) it.toLong() else null },
            yearBuilt = item.path("yearBuilt")?.asInt(),
            latitude = item.path("geo.latitude")?.asNumber(),
            longitude = item.path("geo.longitude")?.asNumber(),
            photos = item.path("image")?.asArray()?.mapNotNull { it.asString() }?.take(MAX_PHOTOS) ?: emptyList(),
            statusText = item.path("availability")?.asString()?.substringAfterLast('/'),
            listDate = item.path("offers.datePosted")?.asString(),
            parseStrategy = "json-ld"
        )
    }

    // --- Reader 3: rendered card markup --------------------------------------------------------

    private fun htmlCardListings(html: String, pageUrl: String, warnings: MutableList<String>): List<ScrapedListing> {
        val listings = ArrayList<ScrapedListing>()
        val matches = DETAIL_LINK.findIterated(html).toList()
        matches.forEachIndexed { index, match ->
            val href = match.groupValues[1]
            val url = absoluteUrl(href, pageUrl) ?: return@forEachIndexed
            // The window ends where the next card starts, otherwise a card with a missing field
            // silently inherits the neighbouring card's beds/baths/price.
            val windowEnd = (matches.getOrNull(index + 1)?.range?.first ?: (match.range.last + CARD_WINDOW))
                .coerceAtMost(html.length)
            val window = html.substring((match.range.first - CARD_LOOKBEHIND).coerceAtLeast(0), windowEnd)
            val price = MONEY_REGEX.find(window)?.groupValues?.get(1)?.let { parseMoney(it) }
            val beds = BEDS_REGEX.find(window)?.groupValues?.get(1)?.toIntOrNull()
            val baths = BATHS_REGEX.find(window)?.groupValues?.get(1)?.toDoubleOrNull()
            val sqft = Regex("""([\d,]{3,})\s*sqft""", RegexOption.IGNORE_CASE).find(window)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            val address = addressIn(window)

            listings += ScrapedListing(
                sourceId = sourceId,
                externalId = externalIdFromUrl(href),
                detailUrl = url,
                address = address?.trim(),
                price = price,
                priceLabel = price?.let { formatMoney(it) },
                bedrooms = beds,
                bathrooms = baths,
                livingAreaSqFt = sqft,
                photos = Regex("""(?:imgSrc|src)="(https://[^"]*zillowstatic[^"]+)"""").find(window)?.groupValues?.get(1)
                    ?.let { listOf(it) } ?: emptyList(),
                parseStrategy = "html-cards"
            )
        }
        if (listings.isEmpty()) warnings += "no listing cards recognised in rendered markup"
        return listings
    }

    // --- Shared helpers ------------------------------------------------------------------------

    /**
     * Address inside a rendered card. The portal has shipped this as element content
     * (`<address data-test="property-card-addr">`), as an attribute, and as an `aria-label`, so each
     * shape gets its own pattern and the first hit wins.
     */
    private fun addressIn(window: String): String? =
        ADDRESS_PATTERNS.asSequence()
            .mapNotNull { pattern -> pattern.find(window)?.groupValues?.get(1)?.trim() }
            .firstOrNull { it.isNotBlank() }

    private fun dedupe(listings: List<ScrapedListing>): List<ScrapedListing> {
        val seen = HashSet<String>()
        return listings.filter { seen.add(it.externalId ?: it.detailUrl) }
    }

    private fun absoluteUrl(raw: String?, base: String): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:${value}"
            value.startsWith("/") -> origin + value
            else -> {
                val trimmedBase = base.substringBefore('?').trimEnd('/')
                if (trimmedBase.isBlank()) null else "$trimmedBase/$value"
            }
        }
    }

    private fun propertyTypeOf(homeType: String?): String? = when (homeType?.uppercase()) {
        null -> null
        "SINGLE_FAMILY", "SINGLEFAMILYRESIDENCE" -> "Single Family"
        "CONDO" -> "Condo"
        "TOWNHOUSE" -> "Townhouse"
        "MULTI_FAMILY", "MULTIFAMILY" -> "Multi-Family"
        "LOT", "LAND" -> "Land"
        "MANUFACTURED", "MOBILE" -> "Manufactured"
        "APARTMENT" -> "Apartment"
        else -> homeType.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun rentUnitOf(priceLabel: String?): String? = when {
        priceLabel == null -> null
        priceLabel.contains("/mo", ignoreCase = true) -> "month"
        priceLabel.contains("/wk", ignoreCase = true) -> "week"
        else -> null
    }

    internal companion object {
        const val STATE_KEY = "searchPageState"
        const val MAX_STATE_BLOCKS = 4
        const val MAX_PHOTOS = 25
        const val CARD_WINDOW = 1_200
        const val CARD_LOOKBEHIND = 200
        private const val MILLIS_PER_DAY = 86_400_000L

        /** `3 bds`, `3 bd`, `3 beds`, `3 br` — the portal is not consistent about the plural. */
        val BEDS_REGEX = Regex("""(\d+)\s*(?:bds?|beds?|brs?)\b""", RegexOption.IGNORE_CASE)
        val BATHS_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(?:bas?|baths?)\b""", RegexOption.IGNORE_CASE)

        val DETAIL_LINK = Regex("""href="([^"]*(?:homedetails|/b/|apartments/)[^"]*?(\d{5,})_zpid[^"]*)"""", RegexOption.IGNORE_CASE)
        val MONEY_REGEX = Regex("""\$\s*([\d,]+(?:\.\d+)?)""")
        val ZPID_IN_URL = Regex("""(\d{5,})_zpid""", RegexOption.IGNORE_CASE)
        val HOME_ID_IN_URL = Regex("""/(?:home|property)/(\d{6,})""", RegexOption.IGNORE_CASE)

        /** Address shapes seen in rendered cards, most specific first. */
        val ADDRESS_PATTERNS = listOf(
            Regex("""property-card-addr["'][^>]*>\s*([^<]{6,140}?)\s*<""", RegexOption.IGNORE_CASE),
            Regex("""data-address\s*=\s*["']([^"']{6,140})["']""", RegexOption.IGNORE_CASE),
            Regex("""aria-label\s*=\s*["'](\d{1,6}\s[^"']{4,120},\s*[A-Za-z .]+,\s*[A-Z]{2}\s+\d{5})["']"""),
            Regex("""itemprop\s*=\s*["']streetAddress["'][^>]*>\s*([^<]{4,120}?)\s*<""", RegexOption.IGNORE_CASE)
        )

        /** `$565,000`, `$2,400/mo`, `1,234` → number. Returns null when there are no digits. */
        fun parseMoney(text: String): Double? {
            val digits = text.filter { it.isDigit() || it == '.' }
            return digits.toDoubleOrNull()
        }

        fun formatMoney(value: Double): String {
            val rounded = value.toLong()
            return "$" + buildString {
                val s = rounded.toString()
                s.forEachIndexed { index, c ->
                    if (index > 0 && (s.length - index) % 3 == 0) append(',')
                    append(c)
                }
            }
        }

        fun digitsOf(text: String): String = text.filter { it.isDigit() }

        /** Canonical US single-line address: `123 Main St, Austin, TX 78704` (no comma before the zip). */
        fun formatAddress(street: String?, city: String?, state: String?, zip: String?): String? {
            val parts = listOf(street, city, state, zip).map { it?.trim().orEmpty() }
            if (parts.all { it.isEmpty() }) return null
            val streetPart = parts[0]
            val cityPart = parts[1]
            val stateZip = listOf(parts[2], parts[3]).filter { it.isNotEmpty() }.joinToString(" ")
            return listOf(
                streetPart,
                cityPart,
                stateZip
            ).filter { it.isNotEmpty() }.joinToString(", ")
        }

        /**
         * Listing id from a URL. Reading "every digit in the path" would glue the street number and
         * the zip onto the id (`/homedetails/913-E-Dean-Ave-…-78704/77112233_zpid/`), so the id
         * patterns are matched explicitly and only the last path segment is used as a fallback.
         */
        fun externalIdFromUrl(url: String): String? =
            ZPID_IN_URL.find(url)?.groupValues?.get(1)
                ?: HOME_ID_IN_URL.find(url)?.groupValues?.get(2)
                ?: digitsOf(url.substringAfterLast('/').substringBefore('?'))
                    .takeIf { it.length in 5..12 }

        /**
         * Extracts the JSON object starting at `openBrace`, honouring strings and escapes.
         * Listing payloads are megabytes with nested quotes; a naive brace count breaks on the
         * first description that contains `{`.
         */
        fun extractBalancedObject(html: String, openBrace: Int): String? {
            if (openBrace !in html.indices || html[openBrace] != '{') return null
            var depth = 0
            var inString = false
            var escaped = false
            var index = openBrace
            while (index < html.length) {
                val c = html[index]
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) return html.substring(openBrace, index + 1)
                    }
                }
                index++
            }
            return null
        }

        /** Every `<script type="application/ld+json">` body in the page. */
        fun extractJsonLdBlocks(html: String): List<String> {
            val blocks = ArrayList<String>()
            val openTag = Regex("""<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>""", RegexOption.IGNORE_CASE)
            var lastEnd = 0
            while (true) {
                val match = openTag.find(html, lastEnd) ?: break
                val bodyStart = match.range.last + 1
                val bodyEnd = html.indexOf("</script>", bodyStart)
                if (bodyEnd < 0) break
                blocks += html.substring(bodyStart, bodyEnd).trim()
                lastEnd = bodyEnd + 9
            }
            return blocks
        }

        /**
         * `+1 (512) 555-0134` → `+15125550134`.
         *
         * Only North American shapes are accepted, and anything else returns null rather than a
         * number that cannot be dialled: a partial match (a 7-digit local number, a zip code that
         * happened to sit next to the word "phone") is worse than no number, because the UI would
         * offer a call button that fails.
         */
        fun normalizePhone(raw: String): String? {
            val digits = digitsOf(raw)
            return when {
                digits.length == 11 && digits.startsWith("1") -> "+$digits"
                digits.length == 10 -> "+1$digits"
                else -> null
            }
        }
    }
}


