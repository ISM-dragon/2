package com.example.urlintelligence.fetch

import com.example.urlintelligence.failure.SourceFailure

/**
 * Hard limits every property fetch must respect.
 *
 * The module itself performs no I/O, so these values are handed to the transport through
 * [com.example.urlintelligence.adapter.SourceFetchRequest] and are *also* enforced by
 * [ResponseGuard] on everything that comes back. A transport that ignores them (or a
 * transport written by someone else) can therefore not smuggle an unbounded response into
 * the parsers.
 */
data class FetchLimits(
    /** Time to establish a connection. */
    val connectTimeoutMillis: Long = 10_000L,
    /** Time to wait for the first byte / between reads. */
    val readTimeoutMillis: Long = 15_000L,
    /** Whole-request budget (connect + redirects + body). */
    val callTimeoutMillis: Long = 30_000L,
    /** Largest document we are willing to parse, in bytes. */
    val maxResponseBytes: Int = 4 * 1024 * 1024,
    /** Largest redirect chain we follow; more is treated as a loop/misconfiguration. */
    val maxRedirects: Int = 3,
    /** Byte prefixes smaller than this never count as a usable listing document. */
    val minResponseBytes: Int = 64,
    /** Content types a property document may be served with. */
    val allowedContentTypes: Set<String> = DEFAULT_CONTENT_TYPES,
    /**
     * Whether a redirect that leaves the provider's host family may be followed.
     * Off by default: cross-host redirects are the classic way a scraper is walked
     * onto an unrelated (or internal) host.
     */
    val allowCrossHostRedirects: Boolean = false
) {
    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(callTimeoutMillis >= readTimeoutMillis) { "callTimeoutMillis must cover readTimeoutMillis" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(minResponseBytes >= 0) { "minResponseBytes must be >= 0" }
        require(minResponseBytes <= maxResponseBytes) { "minResponseBytes must not exceed maxResponseBytes" }
        require(maxRedirects >= 0) { "maxRedirects must be >= 0" }
        require(allowedContentTypes.isNotEmpty()) { "allowedContentTypes must not be empty" }
    }

    /** The timeout a caller may ask for, clamped to this budget. */
    fun effectiveTimeoutMillis(requested: Long): Long =
        requested.coerceIn(1_000L, callTimeoutMillis)

    companion object {
        val DEFAULT_CONTENT_TYPES: Set<String> = setOf(
            "text/html",
            "application/xhtml+xml",
            "application/json",
            "text/plain"
        )

        /** Conservative defaults for background imports on a mobile connection. */
        val MOBILE: FetchLimits = FetchLimits(
            connectTimeoutMillis = 10_000L,
            readTimeoutMillis = 20_000L,
            callTimeoutMillis = 45_000L,
            maxResponseBytes = 2 * 1024 * 1024,
            maxRedirects = 3
        )
    }
}

/** Coarse classification of a response body, used by [ResponseGuard]. */
enum class ContentKind {
    HTML,
    JSON,
    PLAIN_TEXT,
    EMPTY,
    BINARY,
    UNKNOWN
}

/** Content-type helpers: parsing the header and sniffing the first bytes of a body. */
object ContentTypes {

    fun parse(contentType: String?): String? {
        val raw = contentType?.trim()?.substringBefore(';')?.trim()?.lowercase()
        return raw?.takeIf { it.isNotEmpty() }
    }

    fun kindOf(contentType: String?): ContentKind = when (parse(contentType)) {
        null -> ContentKind.UNKNOWN
        "text/html", "application/xhtml+xml" -> ContentKind.HTML
        "application/json", "application/ld+json" -> ContentKind.JSON
        "text/plain", "text/xml", "application/xml" -> ContentKind.PLAIN_TEXT
        else -> ContentKind.UNKNOWN
    }

    /**
     * Sniffs a body when the header is missing or wrong. Property pages are frequently
     * served as `application/octet-stream` or without a content type at all, so the body
     * itself is the more reliable signal.
     */
    fun sniff(body: String): ContentKind {
        if (body.isBlank()) return ContentKind.EMPTY
        val head = body.take(512).trimStart()
        return when {
            head.startsWith("<!doctype html", ignoreCase = true) -> ContentKind.HTML
            head.startsWith("<html", ignoreCase = true) -> ContentKind.HTML
            head.startsWith("{") || head.startsWith("[") -> ContentKind.JSON
            isMostlyText(body) -> ContentKind.PLAIN_TEXT
            else -> ContentKind.BINARY
        }
    }

    private fun isMostlyText(body: String): Boolean {
        val sample = body.take(1024)
        var control = 0
        sample.forEach { ch ->
            if (ch.code < 0x09 || (ch.code in 0x0E..0x1F)) control++
        }
        return control.toDouble() / sample.length.coerceAtLeast(1) < 0.05
    }
}

/** Outcome of inspecting one fetched response before it is handed to a parser. */
sealed class ResponseGuardResult {
    data class Usable(val inspected: InspectedResponse) : ResponseGuardResult()
    data class Rejected(val failure: SourceFailure) : ResponseGuardResult()

    val isUsable: Boolean
        get() = this is Usable
}

/**
 * A response that passed every transport-level check.
 *
 * @property crossHostRedirect true when the final URL left the host family of the request
 *           (allowed only when [FetchLimits.allowCrossHostRedirects] is enabled).
 */
data class InspectedResponse(
    val body: String,
    val kind: ContentKind,
    val byteSize: Int,
    val statusCode: Int,
    val requestUrl: String,
    val finalUrl: String,
    val redirected: Boolean,
    val redirectCount: Int,
    val crossHostRedirect: Boolean,
    val contentType: String?
) {
    /** Host of the final (post-redirect) URL, lowercased, or null when unparseable. */
    val finalHost: String?
        get() = com.example.urlintelligence.url.UrlParts.hostOf(finalUrl).takeIf { it.isNotBlank() }
}
