package com.example.domain.propertyurl.parse

import com.example.domain.propertyurl.model.ExtractedFacts
import com.example.domain.propertyurl.model.ExtractedFactsBuilder
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.normalize.ValueGuards
import com.example.domain.propertyurl.normalize.ValueParsing

/**
 * Regex heuristics over human readable text (page title, meta description, visible body).
 *
 * These are the last resort in the parser chain: they only ever produce facts at low confidence and
 * every value still has to pass [ValueGuards]. That combination is what makes heuristic extraction
 * safe to keep enabled in production, while still enabling partial imports of pages whose structured
 * data is missing or behind a redesign.
 */
object TextFactsHeuristics {

    private val PRICE = Regex("""\$\s?([\d,]{3,}(?:\.\d{1,2})?)\s*(?i:([MK]|MM))?""")
    private val PRICE_PLAIN = Regex("""(?i)\bprice[:\s]+([\d,]{4,})""")
    private val BEDS = Regex("""(?i)(\d{1,2}(?:\.\d)?)\s*(?:bd|bds|bed|beds|bedroom|bedrooms|br)\b""")
    private val BATHS = Regex("""(?i)(\d{1,2}(?:\.\d)?)\s*(?:ba|bas|bath|baths|bathroom|bathrooms)\b""")
    private val PARTS = Regex("""(?i)(\d)\s*(?:full|f)\s*(\d)\s*(?:half|h)\b""")
    private val SQFT = Regex("""(?i)([\d,]{3,}(?:\.\d+)?)\s*(?:sq\.?\s*ft\.?|sqft|square\s*feet|sf)\b""")
    private val ACRES = Regex("""(?i)([\d.]+)\s*(?:acres?|ac\b)""")
    private val LOT_SQFT = Regex("""(?i)(?:lot|land)\D{0,12}([\d,]{4,})\s*(?:sq\.?\s*ft|sqft|sf)\b""")
    private val YEAR = Regex("""(?i)(?:built(?:\s+in)?|year\s*built)\D{0,6}(1[6-9]\d{2}|20\d{2})""")
    private val MLS = Regex("""(?i)\b(?:mls|mls\s*(?:#|no\.?|number)?)\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9-]{4,19})\b""")
    private val DAYS_ON_MARKET = Regex("""(?i)\b(\d{1,4})\s*days?\s*(?:on\s*(?:market|redfin|zillow)|\bon\s*site)?""")
    private val HOA = Regex("""(?i)\bhoa\b\D{0,12}\$?\s?([\d,]{2,7})""")
    private val TAX = Regex("""(?i)(?:annual\s+)?tax(?:es)?\D{0,12}\$?\s?([\d,]{3,9})""")
    private val TYPE_KEYWORDS = listOf(
        "single family", "single-family", "multi family", "multi-family", "duplex", "triplex", "fourplex",
        "condo", "condominium", "townhouse", "townhome", "apartment", "manufactured", "mobile home",
        "land", "vacant land", "commercial", "mixed use"
    )

    /**
     * Extracts facts from a short text (title, meta description).
     *
     * @param allowAddress when false, address components are skipped (body text is too noisy).
     */
    fun extract(
        text: String?,
        context: ParserContext,
        method: ProvenanceMethod,
        confidence: Double,
        rawPath: String,
        allowAddress: Boolean = true,
        allowPrice: Boolean = true
    ): ExtractedFacts {
        val clean = text?.let { HtmlScanner.normalizeWhitespace(HtmlScanner.decodeEntities(it)) }.orEmpty()
        if (clean.length < 6) return ExtractedFacts.EMPTY

        val builder = ExtractedFactsBuilder()
        val provenance = { suffix: String, factor: Double ->
            context.provenance.create(method, confidence * factor, "$rawPath.$suffix")
        }

        if (allowPrice) {
            (PRICE.find(clean) ?: PRICE_PLAIN.find(clean))?.let { match ->
                val value = ValueParsing.parseMoney(match.value)
                if (ValueGuards.price(value).accepted) {
                    builder.addNumber(PropertyField.PRICE_AMOUNT, value, provenance("price", 1.0))
                }
            }
        }

        BEDS.find(clean)?.let { match ->
            val beds = match.groupValues[1].toDoubleOrNull()
            if (ValueGuards.bedrooms(beds).accepted) {
                builder.addNumber(PropertyField.BEDROOMS, beds, provenance("beds", 0.95))
            }
        }
        BATHS.find(clean)?.let { match ->
            val baths = match.groupValues[1].toDoubleOrNull()
            if (ValueGuards.bathrooms(baths).accepted) {
                builder.addNumber(PropertyField.BATHROOMS, baths, provenance("baths", 0.95))
            }
        } ?: PARTS.find(clean)?.let { match ->
            val full = match.groupValues[1].toDoubleOrNull() ?: return@let
            val half = match.groupValues[2].toDoubleOrNull() ?: 0.0
            builder.addNumber(PropertyField.BATHROOMS, full + half * 0.5, provenance("baths", 0.9))
        }

        SQFT.find(clean)?.let { match ->
            val sqft = ValueParsing.parseAreaSqFt(match.groupValues[1])
            if (ValueGuards.areaSqFt(sqft).accepted) {
                builder.addInt(PropertyField.LIVING_AREA_SQFT, sqft?.toInt(), provenance("sqft", 1.0))
            }
        }
        ACRES.find(clean)?.let { match ->
            val sqft = ValueParsing.parseAreaSqFt(match.groupValues[1], "acres")
            if (ValueGuards.lotSqFt(sqft).accepted) {
                builder.addInt(PropertyField.LOT_SIZE_SQFT, sqft?.toInt(), provenance("acres", 0.9))
            }
        }
        LOT_SQFT.find(clean)?.let { match ->
            val sqft = ValueParsing.parseAreaSqFt(match.groupValues[1])
            if (ValueGuards.lotSqFt(sqft).accepted) {
                builder.addInt(PropertyField.LOT_SIZE_SQFT, sqft?.toInt(), provenance("lot", 0.8))
            }
        }

        YEAR.find(clean)?.let { match ->
            val year = match.groupValues[1].toIntOrNull()
            if (year != null && year in 1700..2100) {
                builder.addInt(PropertyField.YEAR_BUILT, year, provenance("year", 0.9))
            }
        }
        MLS.find(clean)?.let { match ->
            val mls = match.groupValues[1]
            if (mls.any { it.isDigit() }) {
                builder.addText(PropertyField.MLS_NUMBER, mls, provenance("mls", 0.7))
            }
        }
        HOA.find(clean)?.let { match ->
            val amount = ValueParsing.parseMoney(match.groupValues[1])
            if (amount != null && amount in 1.0..20_000.0) {
                builder.addNumber(PropertyField.HOA_FEE_MONTHLY, amount, provenance("hoa", 0.6))
            }
        }
        TAX.find(clean)?.let { match ->
            val amount = ValueParsing.parseMoney(match.groupValues[1])
            if (amount != null && amount in 100.0..500_000.0) {
                builder.addNumber(PropertyField.ANNUAL_TAX_AMOUNT, amount, provenance("tax", 0.6))
            }
        }
        DAYS_ON_MARKET.find(clean)?.let { match ->
            val days = match.groupValues[1].toIntOrNull()
            if (ValueGuards.daysOnMarket(days).accepted && !clean.contains("${match.groupValues[1]} sq")) {
                builder.addInt(PropertyField.DAYS_ON_MARKET, days, provenance("dom", 0.6))
            }
        }

        val lowered = clean.lowercase()
        TYPE_KEYWORDS.firstOrNull { lowered.contains(it) }?.let { keyword ->
            ValueParsing.normalizePropertyType(keyword)?.let { type ->
                builder.addText(PropertyField.PROPERTY_TYPE, type, provenance("type", 0.6))
            }
        }

        if (allowAddress) {
            extractAddressFromText(clean, context, method, confidence, rawPath, builder)
        }

        return builder.build()
    }

    /**
     * Address heuristics for a single line. Requires both a street number and a recognisable
     * state/zip so that "3 Beds 2 Baths" style titles cannot masquerade as an address.
     */
    private fun extractAddressFromText(
        text: String,
        context: ParserContext,
        method: ProvenanceMethod,
        confidence: Double,
        rawPath: String,
        builder: ExtractedFactsBuilder
    ) {
        val head = text.substringBefore('|').substringBefore('·').substringBefore(" - ").trim().trimEnd(',')
        if (head.isEmpty() || head.length > 160) return

        val parts = ValueParsing.splitAddressLine(head)
        val hasAnchor = parts.postalCode != null || parts.state != null
        if (!hasAnchor) return

        val provenance = { suffix: String ->
            context.provenance.create(method, confidence * 0.8, "$rawPath.$suffix")
        }

        parts.streetAddress?.let { street ->
            if (ValueGuards.addressLine(street).accepted) {
                builder.addText(PropertyField.ADDRESS_LINE_1, street, provenance("street"))
            }
        }
        parts.unit?.let { builder.addText(PropertyField.UNIT_NUMBER, it, provenance("unit")) }
        parts.city?.let { city ->
            if (city.length in 2..48 && !city.any { it.isDigit() }) {
                builder.addText(PropertyField.CITY, city, provenance("city"))
            }
        }
        parts.state?.let { state ->
            if (ValueGuards.stateCode(state).accepted) {
                builder.addText(PropertyField.STATE, ValueParsing.parseUsState(state) ?: state, provenance("state"))
            }
        }
        parts.postalCode?.let { zip ->
            if (ValueGuards.postalCode(zip, parts.countryCode).accepted) {
                builder.addText(PropertyField.POSTAL_CODE, zip, provenance("postalCode"))
            }
        }
    }
}
