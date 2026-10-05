package com.example.domain.propertyurl.normalize

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * String → typed value parsing for the messy formats portals publish (`$1,250,000`, `1.25M`,
 * `3 bd | 2.5 ba`, `1,850 sq ft`, `Built in 1998`, `2 full 1 half baths`, ISO-8601 dates…).
 *
 * Everything here is pure and covered by unit tests; no `java.time` (Android API 24 compatible).
 */
object ValueParsing {

    private val MONEY = Regex("(?i)(?:usd|\\$)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k|m|mm|bn|b)?")
    private val NUMBER = Regex("-?\\d[\\d,]*(?:\\.\\d+)?")
    private val ISO_DATE = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})" +
            "(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.(\\d{1,3}))?" +
            "(Z|[+-]\\d{2}:?\\d{2})?)?$"
    )

    /** `$1,250,000`, `1250000`, `1.25M`, `USD 450,000`, `485000.0` → 485000.0 */
    fun parseMoney(raw: String?): Double? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty()) return null
        val match = MONEY.find(text) ?: return null
        val base = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase(Locale.US)) {
            "k" -> 1_000.0
            "m", "mm" -> 1_000_000.0
            "b", "bn" -> 1_000_000_000.0
            else -> 1.0
        }
        val value = base * multiplier
        return value.takeIf { it.isFinite() }
    }

    fun parseDecimal(raw: String?): Double? {
        val text = raw?.trim() ?: return null
        val match = NUMBER.find(text) ?: return null
        return match.value.replace(",", "").toDoubleOrNull()
    }

    fun parseWholeNumber(raw: String?): Int? {
        val value = parseDecimal(raw) ?: return null
        return if (value.isFinite() && Math.abs(value) < Int.MAX_VALUE) value.toInt() else null
    }

    /** `3`, `3 bd`, `3 Beds`, `Studio` → 0.0 */
    fun parseBedrooms(raw: String?): Double? {
        val text = raw?.trim()?.lowercase(Locale.US) ?: return null
        if (text.isEmpty()) return null
        if (text.contains("studio")) return 0.0
        if (text.contains("10+")) return 10.0
        return parseDecimal(text)
    }

    /** `2.5`, `2 ba`, `2 full 1 half`, `3 bathrooms` → 2.5 */
    fun parseBathrooms(raw: String?): Double? {
        val text = raw?.trim()?.lowercase(Locale.US) ?: return null
        if (text.isEmpty()) return null
        val full = Regex("(\\d+(?:\\.\\d+)?)\\s*full").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val half = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:half|1/2)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        if (full != null || half != null) return (full ?: 0.0) + (half ?: 0.0) * 0.5
        return parseDecimal(text)
    }

    /** `1,850 sq ft`, `1850sqft`, `172 m²`, `0.25 acres` → square feet (unit aware). */
    fun parseAreaSqFt(raw: String?, unitHint: String? = null): Double? {
        val text = raw?.trim()?.lowercase(Locale.US) ?: return null
        val value = parseDecimal(text) ?: return null
        val unit = (unitHint ?: text).lowercase(Locale.US)
        return when {
            unit.contains("acre") -> value * 43_560.0
            unit.contains("hectare") -> value * 107_639.0
            Regex("m2|m²|sqm|square meter").containsMatchIn(unit) -> value * 10.7639
            else -> value
        }
    }

    fun parseYearBuilt(raw: String?): Int? {
        val text = raw?.trim() ?: return null
        val match = Regex("(1[6-9]\\d{2}|20\\d{2})").find(text) ?: return null
        return match.value.toIntOrNull()
    }

    fun parseDaysOnMarket(raw: String?): Int? {
        val text = raw?.trim()?.lowercase(Locale.US) ?: return null
        val unit = when {
            text.contains("hour") -> "hours"
            text.contains("day") -> "days"
            text.contains("week") -> "weeks"
            text.contains("month") -> "months"
            else -> null
        }
        val value = parseDecimal(text) ?: return null
        return when (unit) {
            "hours" -> 0
            "days" -> value.toInt()
            "weeks" -> (value * 7).toInt()
            "months" -> (value * 30).toInt()
            else -> null
        }
    }

    /** `TX`, `Texas`, `texas`, `TX 78704` → `TX`. */
    fun parseUsState(raw: String?): String? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty()) return null
        val cleaned = text.replace(".", "").trim()
        if (cleaned.length == 2 && cleaned.all { it.isLetter() }) return cleaned.uppercase(Locale.US)
        UsStates.byName(cleaned)?.let { return it }
        // "Austin, TX 78704" style leftovers.
        Regex("\\b([A-Za-z]{2})\\b\\s*\\d{5}").find(text)?.let { return it.groupValues[1].uppercase(Locale.US) }
        return UsStates.byName(cleaned.substringBefore(','))
    }

    /** `78704`, `78704-1234`, `94110 US` → `78704-1234`. */
    fun parsePostalCode(raw: String?): String? {
        val text = raw?.trim()?.uppercase(Locale.US) ?: return null
        if (text.isEmpty()) return null
        Regex("\\b(\\d{5})(?:-(\\d{4}))?\\b").find(text)?.let { match ->
            val plus4 = match.groupValues[2]
            return if (plus4.isEmpty()) match.groupValues[1] else "${match.groupValues[1]}-$plus4"
        }
        Regex("\\b([A-Z]\\d[A-Z]\\s?\\d[A-Z]\\d)\\b").find(text)?.let { return it.groupValues[1].replace(" ", "") }
        return text.takeIf { it.length in 3..10 && it.none { ch -> ch.isWhitespace() } && it.any { ch -> ch.isDigit() } }
    }

    /**
     * ISO-8601 → epoch millis (UTC unless an offset is present). Implemented with [Calendar] on
     * purpose so it also works on Android API 24 without core-library desugaring.
     */
    fun parseIsoTimestamp(raw: String?, defaultTimeZone: TimeZone = TimeZone.getTimeZone("UTC")): Long? {
        val text = raw?.trim() ?: return null
        val match = ISO_DATE.matchEntire(text) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null

        val hour = match.groupValues[4].ifEmpty { "0" }.toIntOrNull() ?: 0
        val minute = match.groupValues[5].ifEmpty { "0" }.toIntOrNull() ?: 0
        val second = match.groupValues[6].ifEmpty { "0" }.toIntOrNull() ?: 0
        val millis = match.groupValues[7].ifEmpty { "0" }.padEnd(3, '0').toIntOrNull() ?: 0
        val offset = match.groupValues[8]

        val zone = when {
            offset.isEmpty() -> defaultTimeZone
            offset == "Z" -> TimeZone.getTimeZone("UTC")
            else -> {
                val sign = if (offset.startsWith("-")) -1 else 1
                val digits = offset.substring(1).replace(":", "")
                val hours = digits.take(2).toIntOrNull() ?: 0
                val minutes = digits.drop(2).take(2).toIntOrNull() ?: 0
                val totalMinutes = sign * (hours * 60 + minutes)
                TimeZone.getTimeZone(String.format(Locale.US, "GMT%+03d:%02d", totalMinutes / 60, Math.abs(totalMinutes % 60)))
            }
        }

        val calendar = Calendar.getInstance(zone, Locale.US)
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(Calendar.MILLISECOND, millis)
        return calendar.timeInMillis
    }

    // --- Enumerations ---------------------------------------------------------------------

    /** Canonical property type strings, aligned with `PropertyEntity.propertyType` values in the app. */
    object PropertyTypes {
        const val SINGLE_FAMILY = "Single Family"
        const val MULTI_FAMILY = "Multi-Family"
        const val CONDO = "Condo"
        const val TOWNHOUSE = "Townhouse"
        const val APARTMENT = "Apartment"
        const val MANUFACTURED = "Manufactured"
        const val LAND = "Land"
        const val COMMERCIAL = "Commercial"
        const val OTHER = "Other"
    }

    private val PROPERTY_TYPE_ALIASES: Map<String, String> = buildMap {
        fun alias(canonical: String, vararg aliases: String) {
            aliases.forEach { value -> put(value, canonical) }
        }
        alias(
            PropertyTypes.SINGLE_FAMILY,
            "single family", "single-family", "singlefamily", "sfh", "house", "detached", "residential",
            "single family residence", "ranch", "craftsman", "colonial"
        )
        alias(
            PropertyTypes.MULTI_FAMILY,
            "multi family", "multi-family", "multifamily", "duplex", "triplex", "fourplex", "quadplex",
            "4-plex", "2-4 unit", "multi dwelling", "income property"
        )
        alias(PropertyTypes.CONDO, "condo", "condominium", "condos", "co-op", "coop", "co op", "apartment condo")
        alias(PropertyTypes.TOWNHOUSE, "townhouse", "townhome", "town house", "row house", "rowhouse", "twh")
        alias(PropertyTypes.APARTMENT, "apartment", "apartments", "apartment community", "flat", "unit")
        alias(PropertyTypes.MANUFACTURED, "manufactured", "mobile home", "modular", "manufactured home", "trailer")
        alias(PropertyTypes.LAND, "land", "lot", "lots", "acreage", "vacant land", "unimproved", "raw land")
        alias(PropertyTypes.COMMERCIAL, "commercial", "office", "retail", "industrial", "mixed use", "mixed-use")
    }

    fun normalizePropertyType(raw: String?): String? {
        val text = raw?.trim()?.lowercase(Locale.US) ?: return null
        if (text.isEmpty()) return null
        PROPERTY_TYPE_ALIASES[text]?.let { return it }
        PROPERTY_TYPE_ALIASES.entries
            .firstOrNull { (alias, _) -> text.contains(alias) }
            ?.let { return it.value }
        // CamelCase/SCREAMING_CASE payloads: `SINGLE_FAMILY`, `MultiFamily`.
        val squashed = text.replace("_", " ").replace("-", " ").replace(Regex("\\s+"), " ").trim()
        if (squashed != text) PROPERTY_TYPE_ALIASES[squashed]?.let { return it }
        return null
    }

    private val STATUS_ALIASES: Map<String, String> = buildMap {
        fun alias(canonical: String, vararg aliases: String) {
            aliases.forEach { value -> put(value, canonical) }
        }
        alias("Active", "active", "for sale", "for_sale", "forsale", "on market", "on_market", "available", "in stock", "comingsoon-listed")
        alias("Pending", "pending", "under contract", "contingent", "accepting backup offers", "under_contract")
        alias("Sold", "sold", "closed", "off market sold")
        alias("Off-Market", "off market", "off_market", "offmarket", "not for sale", "withdrawn", "coming soon")
        alias("Foreclosure", "foreclosure", "bank owned", "reo", "pre-foreclosure", "short sale", "auction")
    }

    fun normalizeListingStatus(raw: String?): String? {
        val text = raw?.trim()?.lowercase(Locale.US) ?: return null
        if (text.isEmpty()) return null
        STATUS_ALIASES[text]?.let { return it }
        STATUS_ALIASES.entries.firstOrNull { (alias, _) -> text.contains(alias) }?.let { return it.value }
        return null
    }

    // --- Address handling -----------------------------------------------------------------

    data class AddressParts(
        val streetAddress: String? = null,
        val unit: String? = null,
        val city: String? = null,
        val state: String? = null,
        val postalCode: String? = null,
        val countryCode: String? = null
    ) {
        val hasAny: Boolean
            get() = listOfNotNull(streetAddress, city, state, postalCode).isNotEmpty()
    }

    private val UNIT_PATTERN = Regex(
        "(?i)(?:^|[,\\s])(?:apt|apartment|unit|ste|suite|#|no)\\.?\\s*([A-Za-z0-9\\-]{1,8})\\b"
    )

    /**
     * Splits a single-line US style address. Returns whatever could be identified; callers merge
     * the result with facts extracted from structured data and keep the structured ones on conflict.
     */
    fun splitAddressLine(line: String?): AddressParts {
        val text = normalizeWhitespace(line ?: "") ?: return AddressParts()
        if (text.isEmpty()) return AddressParts()

        val zipMatch = Regex("\\b(\\d{5})(?:-(\\d{4}))?\\b").find(text)
        val stateMatch = Regex("(?<!\\w)([A-Za-z]{2})(?!\\w)\\s*(?:\\d{5})?").findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { UsStates.isCode(it) }
        val stateFromName = UsStates.NAME_TO_CODE.entries
            .firstOrNull { (name, _) -> text.lowercase(Locale.US).contains(", $name") }?.value

        val state = stateMatch?.uppercase(Locale.US) ?: stateFromName
        val postalCode = zipMatch?.let { match ->
            val plus4 = match.groupValues[2]
            if (plus4.isEmpty()) match.groupValues[1] else "${match.groupValues[1]}-$plus4"
        }

        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var street: String? = null
        var city: String? = null

        when {
            parts.size >= 3 -> {
                street = parts[0]
                city = parts[1]
            }
            parts.size == 2 -> {
                // `1234 Elm St, Austin TX 78704` or `1234 Elm St, Austin`
                val second = parts[1]
                val stateInSecond = Regex("([A-Za-z]{2})\\s*\\d{5}").find(second)
                if (stateInSecond != null) {
                    city = second.substring(0, stateInSecond.range.first).trim().trimEnd(',')
                } else if (UsStates.isCode(second)) {
                    city = null
                } else {
                    city = second
                }
                street = parts[0]
            }
            else -> {
                val single = parts[0]
                val tail = state?.let { Regex("(?i)\\s+$it\\s*\\d{0,5}$").find(single) }
                if (tail != null) {
                    val head = single.substring(0, tail.range.first).trim()
                    street = head.substringBeforeLast(' ').ifBlank { head }
                    val cityCandidate = head.substringAfterLast(' ').trim()
                    city = cityCandidate.takeIf { it.length > 1 && it.any { ch -> ch.isLetter() } }
                } else {
                    street = single
                }
            }
        }

        val unit = street?.let { streetValue ->
            UNIT_PATTERN.find(streetValue)?.let { match ->
                val captured = match.groupValues[1]
                if (captured.length <= 6 && !captured.all { it.isDigit() && captured.length == 5 }) captured else null
            }
        }
        if (unit != null && street != null) {
            street = street.replace(UNIT_PATTERN, " ").replace(Regex("\\s+"), " ").trim().trimEnd(',')
        }

        return AddressParts(
            streetAddress = street?.trim()?.takeIf { it.isNotEmpty() && it.any { ch -> ch.isDigit() } },
            unit = unit,
            city = city?.trim()?.trimEnd(',')?.takeIf { it.length > 1 },
            state = state,
            postalCode = postalCode,
            countryCode = if (state != null && UsStates.isCode(state) && state.length == 2 && state !in setOf("AB", "BC", "MB", "NB", "NL", "NS", "ON", "PE", "QC", "SK")) "US" else null
        )
    }

    fun normalizeWhitespace(text: String?): String? =
        text?.replace(Regex("[\\u00A0\\u2007\\u202F\\s]+"), " ")?.trim()

    /** `2418 S Congress Ave` + `Austin, TX 78704` → one line, used for display and fingerprinting. */
    fun joinAddress(street: String?, city: String?, state: String?, postalCode: String?): String? {
        val parts = ArrayList<String>()
        street?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        val cityState = listOfNotNull(city?.takeIf { it.isNotBlank() }, state?.takeIf { it.isNotBlank() })
            .joinToString(", ")
        if (cityState.isNotBlank()) parts.add(cityState)
        postalCode?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
