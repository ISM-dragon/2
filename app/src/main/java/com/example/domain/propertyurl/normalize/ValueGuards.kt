package com.example.domain.propertyurl.normalize

import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.url.UrlHosts
import com.example.domain.propertyurl.url.UrlParser
import java.util.Locale

/**
 * Sanity gates applied to every extracted value.
 *
 * Portal payloads are huge and contain numbers that look plausible in isolation (school ratings,
 * agent ids, photo counts). Guards are the reason the layer can parse a 3 MB `__NEXT_DATA__` blob
 * without importing nonsense, and every rejection is reported as a warning instead of silently
 * dropping data.
 */
object ValueGuards {

    const val MIN_PRICE = 1_000.0
    const val MAX_PRICE = 500_000_000.0
    const val MAX_SQFT = 5_000_000.0
    const val MIN_YEAR_BUILT = 1600

    data class GuardResult(val accepted: Boolean, val reason: String? = null)

    fun price(value: Double?): GuardResult = when {
        value == null -> GuardResult(false, "missing")
        !value.isFinite() -> GuardResult(false, "not finite")
        value < MIN_PRICE -> GuardResult(false, "price $value below floor")
        value > MAX_PRICE -> GuardResult(false, "price $value above ceiling")
        else -> ACCEPT
    }

    fun bedrooms(value: Double?): GuardResult = when {
        value == null -> GuardResult(false, "missing")
        value < 0 || value > 50 -> GuardResult(false, "bedrooms out of range: $value")
        else -> ACCEPT
    }

    fun bathrooms(value: Double?): GuardResult = when {
        value == null -> GuardResult(false, "missing")
        value < 0 || value > 50 -> GuardResult(false, "bathrooms out of range: $value")
        else -> ACCEPT
    }

    fun areaSqFt(value: Double?): GuardResult = when {
        value == null -> GuardResult(false, "missing")
        value < 50 || value > MAX_SQFT -> GuardResult(false, "area out of range: $value")
        else -> ACCEPT
    }

    fun lotSqFt(value: Double?): GuardResult = when {
        value == null -> GuardResult(false, "missing")
        value < 0 || value > 100_000_000 -> GuardResult(false, "lot size out of range: $value")
        else -> ACCEPT
    }

    fun yearBuilt(value: Int?, currentYear: Int): GuardResult = when {
        value == null -> GuardResult(false, "missing")
        value < MIN_YEAR_BUILT || value > currentYear + 2 ->
            GuardResult(false, "year built out of range: $value")
        else -> ACCEPT
    }

    fun latitude(value: Double?): GuardResult =
        if (value != null && value.isFinite() && value in -90.0..90.0) ACCEPT
        else GuardResult(false, "latitude out of range: $value")

    fun longitude(value: Double?): GuardResult =
        if (value != null && value.isFinite() && value in -180.0..180.0) ACCEPT
        else GuardResult(false, "longitude out of range: $value")

    fun daysOnMarket(value: Int?): GuardResult = when {
        value == null -> GuardResult(false, "missing")
        value < 0 || value > 3650 -> GuardResult(false, "days on market out of range: $value")
        else -> ACCEPT
    }

    /** Zip codes: US 5 (+4), Canadian A1A1A1, generic 3–10 alphanumerics. */
    fun postalCode(value: String?, countryCode: String?): GuardResult {
        val cleaned = value?.trim()?.uppercase(Locale.US) ?: return GuardResult(false, "missing")
        if (cleaned.length !in 3..10) return GuardResult(false, "postal code length: ${cleaned.length}")
        if (cleaned.any { it !in 'A'..'Z' && it !in '0'..'9' && it != '-' && it != ' ' }) {
            return GuardResult(false, "postal code contains unexpected characters")
        }
        // Every real postal code on earth contains a digit; "NOT A ZIP" style placeholder text must not
        // reach the canonical record just because the source did not tell us which country it is in.
        if (cleaned.none { it in '0'..'9' }) return GuardResult(false, "postal code without a digit: $cleaned")
        if (countryCode.equals("US", ignoreCase = true) && !Regex("^\\d{5}(-\\d{4})?$").matches(cleaned)) {
            return GuardResult(false, "not a US zip code: $cleaned")
        }
        return ACCEPT
    }

    /** Two-letter US state (or Canadian province) code, upper case. */
    fun stateCode(value: String?): GuardResult {
        val cleaned = value?.trim()?.uppercase(Locale.US) ?: return GuardResult(false, "missing")
        if (cleaned.length == 2 && cleaned.all { it in 'A'..'Z' }) return ACCEPT
        // Full names are acceptable here; the mapper converts them via ValueParsing.parseUsState.
        if (UsStates.byName(cleaned) != null) return ACCEPT
        return GuardResult(false, "unrecognised state: $value")
    }

    /**
     * Screens an image URL taken from an untrusted listing document.
     *
     * These URLs are persisted and later dereferenced by the image loader, so a hostile page could
     * otherwise point the app at loopback, link-local, RFC1918 or cloud-metadata endpoints — the
     * same destinations the document fetch path already refuses. Absolute https/http on a public
     * host name is therefore required: no protocol-relative, credentialed, IP-literal or
     * non-routable targets.
     */
    fun imageUrl(value: String?): GuardResult {
        val cleaned = value?.trim() ?: return GuardResult(false, "missing")
        if (cleaned.length < 12 || cleaned.length > 2048) return GuardResult(false, "image url length")
        val lowered = cleaned.lowercase(Locale.US)
        if (!lowered.startsWith("https://") && !lowered.startsWith("http://")) {
            return GuardResult(false, "image url scheme not supported")
        }
        if (lowered.contains("data:image")) return GuardResult(false, "inline data images are not persisted")

        val parts = UrlParser.decompose(cleaned)
        if (!parts.userInfo.isNullOrEmpty()) return GuardResult(false, "image url credentials")
        val host = parts.host
        if (host.isNullOrBlank()) return GuardResult(false, "image url host missing")
        if (UrlHosts.isIpLiteral(host)) return GuardResult(false, "image url host is an ip literal")
        if (!UrlHosts.isValidHostSyntax(host) || !UrlHosts.hasTld(host)) {
            return GuardResult(false, "image url host is malformed")
        }
        if (UrlHosts.isPrivateNetwork(host)) return GuardResult(false, "image url host is not public")
        if (parts.path.split('/').any { it == ".." }) return GuardResult(false, "image url path traversal")
        return ACCEPT
    }

    /** Rejects obvious non-address strings (placeholders that portals like to emit). */
    fun addressLine(value: String?): GuardResult {
        val cleaned = value?.trim() ?: return GuardResult(false, "missing")
        if (cleaned.length < 4 || cleaned.length > 180) return GuardResult(false, "address length")
        if (cleaned.equals("address not available", true) || cleaned.equals("n/a", true)) {
            return GuardResult(false, "placeholder address")
        }
        if (cleaned.none { it.isDigit() }) return GuardResult(false, "address without a street number")
        return ACCEPT
    }

    fun propertyType(canonical: String?): GuardResult =
        if (canonical.isNullOrBlank()) GuardResult(false, "unrecognised property type") else ACCEPT

    private val ACCEPT = GuardResult(true)
}

/** US states, DC and territories; extended with Canadian provinces for cross-border listings. */
object UsStates {

    internal val NAME_TO_CODE: Map<String, String> = mapOf(
        "alabama" to "AL", "alaska" to "AK", "arizona" to "AZ", "arkansas" to "AR", "california" to "CA",
        "colorado" to "CO", "connecticut" to "CT", "delaware" to "DE", "district of columbia" to "DC",
        "washington dc" to "DC", "washington, d.c." to "DC", "florida" to "FL", "georgia" to "GA",
        "hawaii" to "HI", "idaho" to "ID", "illinois" to "IL", "indiana" to "IN", "iowa" to "IA",
        "kansas" to "KS", "kentucky" to "KY", "louisiana" to "LA", "maine" to "ME", "maryland" to "MD",
        "massachusetts" to "MA", "michigan" to "MI", "minnesota" to "MN", "mississippi" to "MS",
        "missouri" to "MO", "montana" to "MT", "nebraska" to "NE", "nevada" to "NV",
        "new hampshire" to "NH", "new jersey" to "NJ", "new mexico" to "NM", "new york" to "NY",
        "north carolina" to "NC", "north dakota" to "ND", "ohio" to "OH", "oklahoma" to "OK",
        "oregon" to "OR", "pennsylvania" to "PA", "puerto rico" to "PR", "rhode island" to "RI",
        "south carolina" to "SC", "south dakota" to "SD", "tennessee" to "TN", "texas" to "TX",
        "utah" to "UT", "vermont" to "VT", "virgin islands" to "VI", "virginia" to "VA",
        "washington" to "WA", "west virginia" to "WV", "wisconsin" to "WI", "wyoming" to "WY",
        "alberta" to "AB", "british columbia" to "BC", "manitoba" to "MB", "new brunswick" to "NB",
        "newfoundland and labrador" to "NL", "nova scotia" to "NS", "ontario" to "ON",
        "prince edward island" to "PE", "quebec" to "QC", "saskatchewan" to "SK"
    )

    private val CODES: Set<String> = NAME_TO_CODE.values.toSet()

    fun byName(name: String): String? = NAME_TO_CODE[name.trim().lowercase(Locale.US)]

    fun isCode(code: String): Boolean = CODES.contains(code.trim().uppercase(Locale.US))
}
