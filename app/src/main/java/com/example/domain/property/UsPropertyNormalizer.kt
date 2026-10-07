package com.example.domain.property

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalization rules for US property data.
 *
 * The same record reaches the app through several feeds that spell the same address differently
 * ("2418 South Congress Avenue" vs "2418 S Congress Ave"), use different state/zip formats and
 * different property type vocabularies. Normalizing here - once - is what makes cross-source
 * deduplication reliable, because every downstream key (canonical key, comp keys, APN lookups) is
 * derived from these functions.
 *
 * Abbreviations follow USPS Publication 28 (suffix and directional abbreviation tables).
 */
object UsPropertyNormalizer {

    /** 50 states + DC + inhabited territories. */
    val US_STATE_CODES: Set<String> = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN",
        "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH",
        "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT",
        "VT", "VA", "WA", "WV", "WI", "WY", "PR", "VI", "GU", "AS", "MP"
    )

    private val STREET_SUFFIXES = mapOf(
        "street" to "st", "avenue" to "ave", "boulevard" to "blvd", "circle" to "cir",
        "court" to "ct", "drive" to "dr", "expressway" to "expy", "freeway" to "fwy",
        "highway" to "hwy", "lane" to "ln", "parkway" to "pkwy", "place" to "pl", "road" to "rd",
        "square" to "sq", "terrace" to "ter", "trail" to "trl", "turnpike" to "tpke",
        "crossing" to "xing", "extension" to "ext", "garden" to "gdn", "gardens" to "gdns",
        "grove" to "grv", "heights" to "hts", "junction" to "jct", "manor" to "mnr",
        "meadow" to "mdw", "mountain" to "mtn", "ridge" to "rdg", "spring" to "spg",
        "springs" to "spgs", "view" to "vw", "village" to "vlg", "point" to "pt", "port" to "prt",
        "river" to "riv", "ranch" to "rnch", "summit" to "smt", "valley" to "vly", "creek" to "crk",
        "knoll" to "knl", "landing" to "lndg", "orchards" to "orch", "plaza" to "plz",
        "station" to "sta", "stream" to "strm", "trace" to "trce", "glen" to "gln",
        "north" to "n", "south" to "s", "east" to "e", "west" to "w",
        "northeast" to "ne", "northwest" to "nw", "southeast" to "se", "southwest" to "sw",
        "north east" to "ne", "north west" to "nw", "south east" to "se", "south west" to "sw"
    )

    private val UNIT_PREFIXES = listOf(
        "apartment", "apt", "unit", "suite", "ste", "building", "bldg", "floor", "fl", "no",
        "number", "num", "space", "spc"
    )

    /** Collapses whitespace, strips punctuation that carries no identity, lowercases. */
    fun normalizeWhitespace(raw: String): String = raw
        .trim()
        .replace(Regex("[\\u00A0\\s]+"), " ")

    fun normalizeStreet(raw: String): String {
        val cleaned = normalizeWhitespace(raw)
            .lowercase()
            .replace(Regex("[.,#]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return ""
        val tokens = cleaned.split(" ").flatMap { token ->
            // handle hyphenated suffixes such as "south-west"
            token.split("-").filter { it.isNotEmpty() }
        }
        val mapped = tokens.map { token -> STREET_SUFFIXES[token] ?: token }
        return mapped.joinToString(" ").trim()
    }

    /** Normalizes a US unit designator to the form "unit 4b" (empty when there is no unit). */
    fun normalizeUnit(raw: String): String {
        var value = normalizeWhitespace(raw).lowercase().replace(Regex("[#.,]"), " ").replace(Regex("\\s+"), " ").trim()
        if (value.isEmpty()) return ""
        for (prefix in UNIT_PREFIXES) {
            if (value == prefix) return ""
            if (value.startsWith("$prefix ")) {
                value = value.removePrefix("$prefix ").trim()
                break
            }
        }
        if (value.isEmpty()) return ""
        return "unit " + value.replace(" ", "")
    }

    fun normalizeCity(raw: String): String = normalizeWhitespace(raw).lowercase()

    /**
     * Canonical *display* form of a street line: USPS abbreviations, single spaces, title case.
     * Two feeds spelling the same comp differently ("2402 South Congress Avenue" and
     * "2402 S Congress Ave") both collapse to "2402 S Congress Ave", which is what makes the comp
     * dedup index effective while keeping the address readable in the UI.
     */
    fun canonicalStreetLabel(raw: String): String {
        val normalized = normalizeStreet(raw)
        if (normalized.isEmpty()) return ""
        return normalized.split(" ").joinToString(" ") { token ->
            when {
                token.all { it.isDigit() } -> token
                token.length <= 2 -> token.uppercase()
                else -> token.replaceFirstChar { it.uppercase() }
            }
        }
    }

    fun normalizeStateCode(raw: String): String {
        val value = normalizeWhitespace(raw).uppercase()
        return if (value in US_STATE_CODES) value else value.take(2)
    }

    fun isValidStateCode(raw: String): Boolean = normalizeWhitespace(raw).uppercase() in US_STATE_CODES

    /** Splits "78704-1234" into ("78704", "1234"); tolerates junk and 9 digit zips. */
    fun splitZip(raw: String): Pair<String, String> {
        val digits = normalizeWhitespace(raw).filter { it.isDigit() }
        return when {
            digits.length >= 9 -> Pair(digits.substring(0, 5), digits.substring(5, 9))
            digits.length == 5 -> Pair(digits, "")
            digits.length > 5 -> Pair(digits.substring(0, 5), digits.substring(5))
            else -> Pair(digits, "")
        }
    }

    /** Assessor Parcel Numbers are alphanumeric; digits-only APNs of the same county are padded. */
    fun normalizeApn(raw: String): String = normalizeWhitespace(raw)
        .uppercase()
        .filter { it.isLetterOrDigit() }

    /** 5 digit county FIPS; when only a state is known the state FIPS prefix is still validated. */
    fun normalizeCountyFips(raw: String, stateCode: String = ""): String {
        val digits = normalizeWhitespace(raw).filter { it.isDigit() }
        val normalizedState = normalizeStateCode(stateCode)
        return when {
            digits.length == 5 -> digits
            digits.length in 1..4 -> digits.padStart(5, '0')
            digits.isNotEmpty() -> digits.take(5)
            else -> STATE_FIPS[normalizedState].orEmpty()
        }
    }

    fun normalizeMlsNumber(raw: String): String = normalizeWhitespace(raw).uppercase().filter { it.isLetterOrDigit() }

    /** Deterministic property identity: street + unit + city + state + ZIP5. */
    fun canonicalKey(address: CanonicalAddress): String {
        val (zip5, _) = splitZip(address.zip5)
        return buildString {
            append(normalizeStreet(address.streetAddress))
            val unit = normalizeUnit(address.unit)
            if (unit.isNotEmpty()) append(' ').append(unit)
            append('|').append(normalizeCity(address.city))
            append('|').append(normalizeStateCode(address.stateCode))
            append('|').append(zip5)
        }
    }

    /** Builds a fully normalized address from raw listing fields. */
    fun address(
        streetAddress: String,
        unit: String = "",
        city: String,
        stateCode: String,
        zipCode: String,
        county: String = "",
        countyFips: String = "",
        latitude: Double = 0.0,
        longitude: Double = 0.0
    ): CanonicalAddress {
        val (zip5, plus4) = splitZip(zipCode)
        return CanonicalAddress(
            streetAddress = normalizeWhitespace(streetAddress),
            unit = normalizeUnit(unit),
            city = normalizeWhitespace(city),
            stateCode = normalizeStateCode(stateCode),
            zip5 = zip5,
            zipPlus4 = plus4,
            county = normalizeWhitespace(county),
            countyFips = normalizeCountyFips(countyFips, stateCode),
            latitude = latitude,
            longitude = longitude
        )
    }

    fun propertyType(raw: String): UsPropertyType = UsPropertyType.fromRaw(raw)

    fun listingStatus(raw: String): UsListingStatus = UsListingStatus.fromRaw(raw)

    /** Great-circle distance in miles, used by the fuzzy dedup matcher. */
    fun distanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return Double.MAX_VALUE
        if (lat2 == 0.0 && lon2 == 0.0) return Double.MAX_VALUE
        val earthRadiusMiles = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return earthRadiusMiles * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private val STATE_FIPS = mapOf(
        "AL" to "01", "AK" to "02", "AZ" to "04", "AR" to "05", "CA" to "06", "CO" to "08",
        "CT" to "09", "DE" to "10", "DC" to "11", "FL" to "12", "GA" to "13", "HI" to "15",
        "ID" to "16", "IL" to "17", "IN" to "18", "IA" to "19", "KS" to "20", "KY" to "21",
        "LA" to "22", "ME" to "23", "MD" to "24", "MA" to "25", "MI" to "26", "MN" to "27",
        "MS" to "28", "MO" to "29", "MT" to "30", "NE" to "31", "NV" to "32", "NH" to "33",
        "NJ" to "34", "NM" to "35", "NY" to "36", "NC" to "37", "ND" to "38", "OH" to "39",
        "OK" to "40", "OR" to "41", "PA" to "42", "RI" to "44", "SC" to "45", "SD" to "46",
        "TN" to "47", "TX" to "48", "UT" to "49", "VT" to "50", "VA" to "51", "WA" to "53",
        "WV" to "54", "WI" to "55", "WY" to "56", "PR" to "72", "VI" to "78", "GU" to "66",
        "AS" to "60", "MP" to "69"
    )
}
