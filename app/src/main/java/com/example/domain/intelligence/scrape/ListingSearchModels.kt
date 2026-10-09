package com.example.domain.intelligence.scrape

/**
 * Portal listing search — the "go look at what is actually on the market" half of the intelligence
 * layer, as opposed to `domain.propertyurl`, which explains a *single* link the user already has.
 *
 * Flow: [ListingSearchQuery] → search URL → one page fetch → [ListingPortalScraper.interpret] →
 * [ListingScrapeResult] (listings + the contact routes for each listing).
 *
 * Everything in this package is platform-free (no Android, no Room, no OkHttp) so the parsers and
 * the policy are unit-testable on a plain JVM, mirroring the hard rule of `domain.propertyurl`.
 *
 * Honest framing of what "scraping a portal" means in production:
 *  * Portals publish no listing-search API to end users. Retrieval means fetching their HTML and
 *    reading the state they embed for their own front end, which they change without notice. Every
 *    parser here therefore reports *drift* ([ListingScrapeFailureKind.PARSE_DRIFT]) instead of
 *    silently returning an empty list.
 *  * Automated retrieval is governed by the portal's terms and by robots.txt, not by capability.
 *    [ListingScrapePolicy] is fail-closed: nothing is fetched until the operator has consented.
 *  * Anti-bot walls are the normal case for a residential/data-centre IP. They are surfaced as
 *    [ListingScrapeFailureKind.BLOCKED_BY_ANTIBOT] with the remediation that actually works
 *    (sanctioned API, licensed feed, or an operator-supplied proxy) rather than being retried.
 */

/** What the user is looking for; maps onto the portal's own search path. */
enum class ListingIntent(val portalPath: String, val label: String) {
    FOR_SALE("for_sale", "For sale"),
    FOR_RENT("for_rent", "For rent"),
    SOLD("recently_sold", "Recently sold"),
    FOR_AUCTION("for_auction", "Auction")
}

/** Housing type filters. `portalFlag` is the key the portal uses in its own filter state. */
enum class ListingPropertyTypeFilter(val portalFlag: String, val label: String) {
    HOUSE("sf", "House"),
    CONDO("condo", "Condo"),
    TOWNHOUSE("tow", "Townhouse"),
    MULTIFAMILY("mf", "Multi-family"),
    LOT("land", "Lot / Land"),
    MANUFACTURED("manu", "Manufactured"),
    APARTMENT("apa", "Apartment")
}

/**
 * One search the user typed. `location` is free text ("Austin, TX", "78704", "Travis County TX");
 * the URL builder normalizes it into the portal's own location token.
 */
data class ListingSearchQuery(
    val location: String,
    val intent: ListingIntent = ListingIntent.FOR_SALE,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val minBeds: Int? = null,
    val minBaths: Double? = null,
    val propertyTypes: Set<ListingPropertyTypeFilter> = emptySet(),
    val page: Int = 1,
    /** Safety valve: how many pages the scraper is allowed to walk for this query. */
    val maxPages: Int = 1,
    /** Hard cap on listings returned to the UI, regardless of what the portal sent back. */
    val maxResults: Int = DEFAULT_MAX_RESULTS
) {
    init {
        require(location.isNotBlank()) { "search location must not be blank" }
        require(page >= 1) { "page must be >= 1" }
        require(maxPages in 1..MAX_PAGES_LIMIT) { "maxPages must be in 1..$MAX_PAGES_LIMIT" }
        require(maxResults in 1..1_000) { "maxResults must be in 1..1000" }
        require(minPrice == null || minPrice > 0) { "minPrice must be positive" }
        require(maxPrice == null || maxPrice > 0) { "maxPrice must be positive" }
        require(minPrice == null || maxPrice == null || minPrice <= maxPrice) { "minPrice must be <= maxPrice" }
        require(minBeds == null || minBeds in 0..50) { "minBeds must be in 0..50" }
        require(minBaths == null || minBaths in 0.0..50.0) { "minBaths must be in 0..50" }
    }

    companion object {
        const val DEFAULT_MAX_RESULTS = 60
        const val MAX_PAGES_LIMIT = 10
    }
}

/**
 * One listing as the portal published it.
 *
 * Fields are nullable because portals omit them constantly (a condo card has no lot size, an
 * off-market record has no list date). Nothing here is estimated or invented: if the portal did
 * not say it, it stays null, and the caller decides whether that is acceptable.
 */
data class ScrapedListing(
    val sourceId: String,
    val externalId: String?,
    val detailUrl: String,
    val address: String?,
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val zipCode: String? = null,
    val price: Double? = null,
    val priceLabel: String? = null,
    val priceUnit: String? = null,
    val bedrooms: Int? = null,
    val bathrooms: Double? = null,
    val livingAreaSqFt: Int? = null,
    val lotSizeSqFt: Long? = null,
    val yearBuilt: Int? = null,
    val propertyType: String? = null,
    val status: String? = null,
    val statusText: String? = null,
    val daysOnMarket: Int? = null,
    val listDate: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val photos: List<String> = emptyList(),
    val mlsId: String? = null,
    val listingAgentName: String? = null,
    val listingAgentPhone: String? = null,
    val listingOfficeName: String? = null,
    val listingOfficePhone: String? = null,
    val brokerName: String? = null,
    val isBrokerPaidPlacement: Boolean = false,
    val openHouse: String? = null,
    /** Where the record came from inside the page, kept for diagnostics when a parser drifts. */
    val parseStrategy: String
) {
    val displayName: String
        get() = address?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(street, city, state).filter { it.isNotBlank() }.joinToString(", ")
                .ifBlank { detailUrl }
}

/** How a person can be reached. Nothing here transmits; the UI turns these into user intents. */
enum class ContactChannel(val label: String) {
    CALL("Call"),
    SMS("Text"),
    EMAIL("Email"),
    WHATSAPP("WhatsApp"),
    /** The portal's own "contact agent" form — the only route that always exists. */
    PORTAL_MESSAGE("Portal message"),
    /** Open the listing itself (photos, status, the portal's own contact button). */
    LISTING_PAGE("Listing page")
}

/** A ready-to-open contact route. [uri] is what the UI hands to an `Intent`. */
data class ContactAction(
    val channel: ContactChannel,
    val uri: String,
    /** Who/what this reaches, for the button subtitle ("Marcus Lee · Compass"). */
    val target: String?,
    /** Present when the route needs a destination the portal did not publish. */
    val unavailableReason: String? = null
) {
    val isAvailable: Boolean get() = unavailableReason == null
}

/**
 * The contact card for one listing.
 *
 * Portals deliberately gate the agent's direct number behind their contact form, so [phone] is often
 * null. That is reported, not papered over: [actions] then contains the portal-message route plus the
 * listing page, and [missing] explains what could not be found.
 */
data class ListingContact(
    val listingExternalId: String?,
    val listingUrl: String,
    val personName: String?,
    val role: String?,
    val phone: String?,
    val email: String?,
    val brokerage: String?,
    val listingOffice: String?,
    val actions: List<ContactAction>,
    val missing: List<String> = emptyList(),
    /** Which page location produced the values ("payload:attributionInfo", "html:tel-link", …). */
    val provenance: String
) {
    val hasDirectRoute: Boolean
        get() = actions.any { it.isAvailable && it.channel != ContactChannel.PORTAL_MESSAGE && it.channel != ContactChannel.LISTING_PAGE }
}

/** Why a search produced no listings. Every kind carries a message an operator can act on. */
enum class ListingScrapeFailureKind(val retryable: Boolean) {
    /** The compliance gate refused: no consent, robots.txt says no, or the host is not allowed. */
    DENIED_BY_POLICY(false),
    /** The portal returned an anti-bot interstitial (captcha / "access denied"). */
    BLOCKED_BY_ANTIBOT(false),
    /** The portal answered 429, or our own rate limiter refused to fire that soon again. */
    RATE_LIMITED(true),
    /** Non-2xx that is not an anti-bot wall (404, 5xx, …). */
    HTTP_ERROR(true),
    /** DNS/TLS/timeout/reset — no response at all. */
    NETWORK_ERROR(true),
    /** The portal answered normally but the query genuinely matched nothing. */
    EMPTY_RESULTS(false),
    /** Page parsed but no listing node shape we recognise — the portal changed its markup. */
    PARSE_DRIFT(false),
    /** The caller cancelled mid-flight. */
    CANCELLED(false)
}

data class ListingScrapeFailure(
    val kind: ListingScrapeFailureKind,
    val message: String,
    /** What the operator can do about it. Shown verbatim in the UI. */
    val remediation: String? = null,
    /** Non-sensitive diagnostic detail (status code, marker found). Never a URL with credentials. */
    val detail: String? = null
)

/**
 * Result of one search. [listings] and [contacts] are index-aligned by [ScrapedListing.externalId]
 * (falling back to [ScrapedListing.detailUrl] when the portal omitted an id).
 */
data class ListingScrapeResult(
    val query: ListingSearchQuery,
    val sourceId: String,
    val searchUrl: String,
    val listings: List<ScrapedListing> = emptyList(),
    val contacts: List<ListingContact> = emptyList(),
    val failure: ListingScrapeFailure? = null,
    val warnings: List<String> = emptyList(),
    val fetchedPages: Int = 0,
    val elapsedMillis: Long = 0,
    /** Parser strategy that produced the listings — the first thing to check after a portal update. */
    val parseStrategy: String? = null,
    val fetchedAtEpochMillis: Long = 0
) {
    val isSuccessful: Boolean get() = failure == null
    val listingCount: Int get() = listings.size

    fun contactFor(listing: ScrapedListing): ListingContact? =
        contacts.firstOrNull { it.listingExternalId != null && it.listingExternalId == listing.externalId }
            ?: contacts.firstOrNull { it.listingUrl == listing.detailUrl }
}

/** One page of HTML as the fetcher saw it. The scraper never re-fetches; it only interprets. */
data class ListingPageFetch(
    val requestedUrl: String,
    val finalUrl: String = requestedUrl,
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val elapsedMillis: Long = 0,
    /** Transport-level failure when there is no response to interpret. */
    val transportError: String? = null
) {
    val isSuccess: Boolean get() = status in 200..299 && transportError == null
}

/** Fetches one portal page. Implemented by the app's OkHttp adapter; tests hand in fixtures. */
interface ListingPageFetcher {
    suspend fun fetch(url: String): ListingPageFetch

    /** robots.txt text for the portal origin, or null when it could not be read. */
    suspend fun fetchRobotsTxt(origin: String): String?
}
