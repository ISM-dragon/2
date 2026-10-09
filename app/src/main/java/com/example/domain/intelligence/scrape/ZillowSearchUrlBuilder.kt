package com.example.domain.intelligence.scrape

/**
 * Builds the portal search URL for a [ListingSearchQuery].
 *
 * Zillow's search page is driven by one parameter: `searchQueryState`, a base64-encoded JSON blob
 * that its own front end posts back on every filter change:
 *
 * ```
 * https://www.zillow.com/homes/for_sale/austin-tx_rb/?searchQueryState=eyJwYWdpbmF0aW9uIjp7fSw…
 * ```
 *
 * The path token (`austin-tx_rb`) is what a human would type; the blob is what carries price/beds/
 * baths/page. Both are emitted so the URL stays openable in a browser (the "Open in portal" action)
 * even if the blob is stale.
 *
 * Note on `regionSelection`: Zillow's own payload also carries a numeric `regionId` obtained from its
 * region-lookup API. We deliberately do not call that API here — it is a second network round trip
 * against the same wall, and `usersSearchTerm` plus the path token are enough for the search page to
 * resolve the location. If a portal build ever requires the id, that is the place to add it.
 */
class ZillowSearchUrlBuilder(
    private val origin: String = DEFAULT_ORIGIN
) {

    val sourceId: String = SOURCE_ID
    val displayName: String = "Zillow"

    /** Absolute search URL for [query]. */
    fun searchUrl(query: ListingSearchQuery): String {
        val path = "/homes/${query.intent.portalPath}/${locationToken(query.location)}_rb/"
        val state = searchQueryState(query)
        return "$origin$path?searchQueryState=${urlEncodeBase64(state)}"
    }

    /** Browser-openable URL without the encoded blob (used for the "open listing/portal" fallback). */
    fun plainSearchUrl(query: ListingSearchQuery): String {
        val path = "/homes/${query.intent.portalPath}/${locationToken(query.location)}_rb/"
        return "$origin$path"
    }

    /** Portal origin, e.g. `https://www.zillow.com` — the compliance gate checks this host. */
    fun origin(): String = origin

    fun robotsTxtUrl(): String = "$origin/robots.txt"

    /**
     * Turns free text into the portal's location token:
     *  * `Austin, TX` → `austin-tx`
     *  * `  78704 ` → `78704`
     *  * `Travis County, Texas` → `travis-county-texas`
     *
     * Unknown or odd input still yields something non-empty; a wrong token surfaces as an empty
     * result set (reported as [ListingScrapeFailureKind.EMPTY_RESULTS]), never as a crash.
     */
    fun locationToken(location: String): String {
        val slug = location.lowercase()
            .map { c ->
                when {
                    c.isLetterOrDigit() -> c
                    else -> '-'
                }
            }
            .joinToString("")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        return slug.ifBlank { "united-states" }
    }

    /** The `searchQueryState` payload as standard base64 (no URL encoding). */
    fun searchQueryState(query: ListingSearchQuery): String {
        val filterState = buildFilterState(query)
        val pagination = if (query.page > 1) """{"currentPage":${query.page}}""" else "{}"
        val json = buildString {
            append('{')
            append(""""pagination":$pagination,""")
            append(""""usersSearchTerm":${jsonString(query.location.trim())},""")
            append(""""mapBounds":{},""")
            append(""""filterState":$filterState,""")
            append(""""isListVisible":true,""")
            append(""""regionSelection":[]""")
            append('}')
        }
        return PortalBase64.encode(json.toByteArray(Charsets.UTF_8))
    }

    private fun buildFilterState(query: ListingSearchQuery): String {
        val parts = ArrayList<String>()
        parts += """"sortSelection":{"value":"globalrelevanceex"}"""
        query.minPrice?.let { min ->
            query.maxPrice?.let { max ->
                parts += """"price":{"min":${min.toLong()},"max":${max.toLong()}}"""
            } ?: run { parts += """"price":{"min":${min.toLong()}}""" }
        } ?: query.maxPrice?.let { max -> parts += """"price":{"max":${max.toLong()}}""" }
        query.minBeds?.takeIf { it > 0 }?.let { parts += """"beds":{"min":$it}""" }
        query.minBaths?.takeIf { it > 0 }?.let { parts += """"ba":{"min":${formatBaths(it)}}""" }

        // Housing types: the portal treats "no flags" as "everything", so flags are only emitted when
        // the user narrowed the set — and then every known type is stated explicitly (true/false),
        // because a partial flag set means "only these" on the portal side.
        if (query.propertyTypes.isNotEmpty()) {
            for (type in ListingPropertyTypeFilter.entries) {
                parts += """"${type.portalFlag}":{"value":${query.propertyTypes.contains(type)}}"""
            }
        }
        if (query.intent == ListingIntent.FOR_RENT) parts += """"fr":{"value":true}"""
        if (query.intent == ListingIntent.FOR_AUCTION) parts += """"ah":{"value":true}"""
        return parts.joinToString(prefix = "{", postfix = "}", separator = ",")
    }

    /** Whole baths are ints in the portal payload; 1.5/2.5 must survive as decimals. */
    private fun formatBaths(baths: Double): String =
        if (baths % 1.0 == 0.0) baths.toLong().toString() else baths.toString()

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** base64 uses `+ / =`, all of which must be percent-encoded inside a query parameter. */
    private fun urlEncodeBase64(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '+' -> append("%2B")
                '/' -> append("%2F")
                '=' -> append("%3D")
                '&' -> append("%26")
                else -> append(c)
            }
        }
    }

    companion object {
        const val SOURCE_ID = "zillow"
        const val DEFAULT_ORIGIN = "https://www.zillow.com"
    }
}

/**
 * Standard base64 encoder.
 *
 * Hand-rolled on purpose: `java.util.Base64` needs API 26 and this app's `minSdk` is 24, while
 * `android.util.Base64` would make this file untestable on a plain JVM. Twelve lines either way.
 */
object PortalBase64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(input: ByteArray): String {
        val out = StringBuilder((input.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < input.size) {
            val n = (input[i].toInt() and 0xFF shl 16) or (input[i + 1].toInt() and 0xFF shl 8) or (input[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                .append(ALPHABET[n ushr 6 and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (input.size - i) {
            1 -> {
                val n = input[i].toInt() and 0xFF shl 16
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63]).append("==")
            }
            2 -> {
                val n = (input[i].toInt() and 0xFF shl 16) or (input[i + 1].toInt() and 0xFF shl 8)
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                    .append(ALPHABET[n ushr 6 and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** Decode used by the unit tests to assert round-tripping (and by diagnostics). */
    fun decode(input: String): String {
        val clean = input.filter { it in ALPHABET || it == '=' }
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            if (c == '=') break
            buffer = (buffer shl 6) or ALPHABET.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append((buffer shr bits and 0xFF).toChar())
            }
        }
        return out.toString()
    }
}
