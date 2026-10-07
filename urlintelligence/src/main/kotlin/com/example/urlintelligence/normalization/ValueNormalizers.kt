package com.example.urlintelligence.normalization

import com.example.urlintelligence.model.CanonicalListingStatus
import com.example.urlintelligence.model.CanonicalPropertyType
import java.text.SimpleDateFormat
import java.util.Locale

/** Money text -> USD amount. Handles "$485,000", "485000", "$1.25M", "1,200/mo". */
object MoneyParser {

    private val AMOUNT = Regex("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([KkMmBb]{0,2})")

    fun parse(raw: String?): Double? {
        if (raw == null) return null
        val text = raw.replace('\u00A0', ' ').trim()
        if (text.isBlank()) return null
        val match = AMOUNT.find(text) ?: return null
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val scaled = when (match.groupValues[2].uppercase(Locale.US)) {
            "K" -> number * 1_000.0
            "M", "MM" -> number * 1_000_000.0
            "B" -> number * 1_000_000_000.0
            else -> number
        }
        return if (scaled.isNaN() || scaled.isInfinite()) null else scaled
    }

    /** Rejects absurd values before they reach the financial engines. */
    fun isValidPrice(value: Double?): Boolean = value != null && value in 1_000.0..500_000_000.0

    fun isValidRent(value: Double?): Boolean = value != null && value in 100.0..100_000.0
}

/** Area/lot text -> square feet. Handles "2,250 sqft", "0.14 acres", "2250". */
object AreaParser {

    private val SQFT = Regex("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:sq\\.?\\s?ft|square\\s?feet|sqft|sf)\\b", RegexOption.IGNORE_CASE)
    private val ACRES = Regex("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*acres?\\b", RegexOption.IGNORE_CASE)
    private val BARE = Regex("([0-9][0-9,]*(?:\\.[0-9]+)?)")

    fun sqFt(raw: String?): Int? {
        if (raw == null) return null
        val text = raw.trim()
        SQFT.find(text)?.let {
            return it.groupValues[1].replace(",", "").toDoubleOrNull()?.toInt()
        }
        ACRES.find(text)?.let {
            val acres = it.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            return (acres * 43_560.0).toInt()
        }
        BARE.find(text)?.let {
            return it.groupValues[1].replace(",", "").toDoubleOrNull()?.toInt()
        }
        return null
    }

    fun isValidLivingArea(value: Int?): Boolean = value != null && value in 50..1_000_000
    fun isValidLotSize(value: Int?): Boolean = value != null && value in 0..100_000_000
}

/** US state / province text -> two-letter code. */
object StateNormalizer {

    private val STATES: Map<String, String> = mapOf(
        "alabama" to "AL", "alaska" to "AK", "arizona" to "AZ", "arkansas" to "AR",
        "california" to "CA", "colorado" to "CO", "connecticut" to "CT", "delaware" to "DE",
        "district of columbia" to "DC", "florida" to "FL", "georgia" to "GA", "hawaii" to "HI",
        "idaho" to "ID", "illinois" to "IL", "indiana" to "IN", "iowa" to "IA",
        "kansas" to "KS", "kentucky" to "KY", "louisiana" to "LA", "maine" to "ME",
        "maryland" to "MD", "massachusetts" to "MA", "michigan" to "MI", "minnesota" to "MN",
        "mississippi" to "MS", "missouri" to "MO", "montana" to "MT", "nebraska" to "NE",
        "nevada" to "NV", "new hampshire" to "NH", "new jersey" to "NJ", "new mexico" to "NM",
        "new york" to "NY", "north carolina" to "NC", "north dakota" to "ND", "ohio" to "OH",
        "oklahoma" to "OK", "oregon" to "OR", "pennsylvania" to "PA", "rhode island" to "RI",
        "south carolina" to "SC", "south dakota" to "SD", "tennessee" to "TN", "texas" to "TX",
        "utah" to "UT", "vermont" to "VT", "virginia" to "VA", "washington" to "WA",
        "west virginia" to "WV", "wisconsin" to "WI", "wyoming" to "WY",
        "puerto rico" to "PR", "washington dc" to "DC"
    )

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.trim('.') ?: return null
        if (value.isBlank()) return null
        val lowered = value.lowercase(Locale.US)
        if (lowered.length == 2 && lowered.all { it.isLetter() }) return lowered.uppercase(Locale.US)
        STATES[lowered]?.let { return it }
        // "Austin, TX" or "TX 78704" style leftovers
        val token = lowered.split(Regex("[,\\s]+")).lastOrNull { it.length == 2 } ?: return null
        return token.uppercase(Locale.US)
    }
}

/** Free-text home type -> canonical enum. */
object PropertyTypeParser {

    fun parse(raw: String?): CanonicalPropertyType? {
        val value = raw?.lowercase(Locale.US)?.replace(Regex("[^a-z]"), "") ?: return null
        if (value.isBlank()) return null
        return when {
            value.contains("singlefamily") || value == "house" || value == "home" -> CanonicalPropertyType.SINGLE_FAMILY
            value.contains("townhouse") || value.contains("townhome") || value.contains("rowhouse") -> CanonicalPropertyType.TOWNHOUSE
            value.contains("condo") || value.contains("condominium") -> CanonicalPropertyType.CONDO
            value.contains("multifamily") || value.contains("duplex") || value.contains("triplex") ||
                value.contains("quadplex") || value.contains("fourplex") || value.contains("apartmentcomplex") ||
                value.contains("multifamilyresidence") -> CanonicalPropertyType.MULTI_FAMILY
            value.contains("apartment") -> CanonicalPropertyType.APARTMENT
            value.contains("manufactured") || value.contains("mobile") || value.contains("trailer") -> CanonicalPropertyType.MANUFACTURED
            value.contains("land") || value.contains("lot") -> CanonicalPropertyType.LAND
            value.contains("commercial") || value.contains("office") || value.contains("retail") -> CanonicalPropertyType.COMMERCIAL
            else -> null
        }
    }
}

/** Free-text / schema.org availability -> canonical listing status. */
object ListingStatusParser {

    fun parse(raw: String?): CanonicalListingStatus? {
        val value = raw?.lowercase(Locale.US) ?: return null
        return when {
            value.contains("forrent") || value.contains("for rent") || value.contains("rental") ||
                value.contains("lease") -> CanonicalListingStatus.FOR_RENT
            value.contains("forsale") || value.contains("for sale") || value.contains("instock") ||
                value.contains("active") -> CanonicalListingStatus.FOR_SALE
            value.contains("pending") || value.contains("contingent") || value.contains("presale") ||
                value.contains("preorder") || value.contains("limitedavailability") -> CanonicalListingStatus.PENDING
            value.contains("sold") || value.contains("soldout") || value.contains("offmarket") && value.contains("sold") ->
                CanonicalListingStatus.SOLD
            value.contains("auction") || value.contains("foreclos") || value.contains("sheriff") ->
                CanonicalListingStatus.AUCTION
            value.contains("offmarket") || value.contains("off market") || value.contains("withdrawn") ||
                value.contains("expired") -> CanonicalListingStatus.OFF_MARKET
            else -> null
        }
    }
}

/** ISO dates, US dates and epoch seconds/millis -> epoch millis. */
object DateParser {

    private val FORMATS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
        "MM/dd/yyyy",
        "MMM d, yyyy",
        "MMMM d, yyyy"
    )

    fun epochMillis(raw: String?): Long? {
        val value = raw?.trim() ?: return null
        if (value.isBlank()) return null
        if (value.all { it.isDigit() }) {
            val numeric = value.toLongOrNull() ?: return null
            return when {
                numeric > 100_000_000_000L -> numeric          // already epoch millis
                numeric > 100_000_000L -> numeric * 1000L      // epoch seconds
                else -> null
            }
        }
        // Strip a trailing UTC designator / offset; we intentionally normalize to UTC.
        val candidate = value
            .replace(Regex("Z$"), "")
            .replace(Regex("[+-][0-9]{2}:?[0-9]{2}$"), "")
            .replace(Regex("\.[0-9]+$"), "")
            .trim()
        FORMATS.forEach { pattern ->
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.isLenient = false
                val date = format.parse(candidate)
                if (date != null) return date.time
            } catch (t: Throwable) {
                // try the next pattern
            }
        }
        return null
    }
}

/** Splits "2418 S Congress Ave #B" style strings and one-line addresses. */
object AddressParser {

    private val UNIT_RE = Regex(
        "(?:#|apt\\.?|unit|ste\\.?|suite)\\s*([A-Za-z0-9-]+)",
        RegexOption.IGNORE_CASE
    )

    fun splitUnit(raw: String?): Pair<String?, String?> {
        val value = raw?.trim() ?: return null to null
        if (value.isBlank()) return null to null
        val match = UNIT_RE.find(value)
        if (match == null) return value to null
        val unit = match.groupValues[1]
        val line = value.removeRange(match.range).trim().trimEnd(',', ' ')
        return line.ifBlank { null } to unit
    }

    /** Parses "2418 S Congress Ave, Austin, TX 78704" into its components. */
    fun parseOneLine(raw: String?): ParsedAddress {
        val value = raw?.trim() ?: return ParsedAddress.empty()
        if (value.isBlank()) return ParsedAddress.empty()
        val parts = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ParsedAddress.empty()
        val (line1, unit) = splitUnit(parts.first())
        if (parts.size == 1) return ParsedAddress(line1, unit, null, null, null)
        val city = parts.getOrNull(1)
        val tail = parts.drop(2).joinToString(" ")
        val stateZip = Regex("([A-Za-z]{2})\\s+([0-9]{5}(?:-[0-9]{4})?)").find(tail)
            ?: Regex("([A-Za-z]{2})\\s+([0-9]{5}(?:-[0-9]{4})?)").find(parts.lastOrNull().orEmpty())
        val state = stateZip?.groupValues?.getOrNull(1)?.let { StateNormalizer.normalize(it) }
            ?: StateNormalizer.normalize(tail)
        val postal = stateZip?.groupValues?.getOrNull(2)
        return ParsedAddress(line1, unit, city, state, postal)
    }
}

data class ParsedAddress(
    val line1: String?,
    val unit: String?,
    val city: String?,
    val state: String?,
    val postalCode: String?
) {
    companion object {
        fun empty(): ParsedAddress = ParsedAddress(null, null, null, null, null)
    }
}
