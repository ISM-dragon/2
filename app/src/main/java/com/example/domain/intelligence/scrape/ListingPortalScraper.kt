package com.example.domain.intelligence.scrape

/**
 * Sleeping between page fetches is the fetcher's job, not the domain's — this keeps the package free
 * of `kotlinx.coroutines` so it stays unit-testable on a plain JVM. The app injects a coroutine
 * `delay`; tests inject a recorder.
 */
interface Sleeper {
    suspend fun sleep(millis: Long)

    companion object {
        /** Default: no waiting (used by tests and by single-page searches). */
        val NOOP: Sleeper = object : Sleeper {
            override suspend fun sleep(millis: Long) = Unit
        }
    }
}

/** Progress callback so the UI can show "page 2 of 3" instead of a frozen spinner. */
fun interface ScrapeProgressListener {
    fun onProgress(page: Int, totalPages: Int, listingsSoFar: Int)
}

/**
 * Runs one portal search end to end: policy gate → search URL → page fetch(es) → parse → contacts.
 *
 * Deliberately split in two halves:
 *  * [search] owns the parts that need the world (consent, robots.txt, HTTP, pacing),
 *  * [interpretPage] is a pure function over one already-fetched page, which is what every parser
 *    test drives — no network, no clock, no coroutine.
 *
 * Failure behaviour: expected conditions come back as a [ListingScrapeFailure] inside the result.
 * Nothing here throws for "the portal blocked us", and nothing here returns an empty list while
 * claiming success — an unreadable page is [ListingScrapeFailureKind.PARSE_DRIFT], a genuinely empty
 * result set is [ListingScrapeFailureKind.EMPTY_RESULTS], and the UI can tell them apart.
 */
class ListingPortalScraper(
    private val fetcher: ListingPageFetcher,
    private val urlBuilder: ZillowSearchUrlBuilder = ZillowSearchUrlBuilder(),
    private val parser: ListingPortalSearchParser = ListingPortalSearchParser(),
    private val contactExtractor: ListingContactExtractor = ListingContactExtractor(),
    private val policy: ListingScrapePolicy = ListingScrapePolicy { PortalScrapeSettings() },
    private val rateLimiter: ScrapeRateLimiter = ScrapeRateLimiter(),
    private val sleeper: Sleeper = Sleeper.NOOP,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onProgress: ScrapeProgressListener? = null
) {

    val sourceId: String get() = urlBuilder.sourceId

    suspend fun search(query: ListingSearchQuery): ListingScrapeResult {
        val startedAt = clock()
        val searchUrl = urlBuilder.searchUrl(query)
        val warnings = ArrayList<String>()

        when (val gate = policy.check(searchUrl) { origin -> fetcher.fetchRobotsTxt(origin) }) {
            is ScrapeGate.Denied -> return ListingScrapeResult(
                query = query,
                sourceId = sourceId,
                searchUrl = searchUrl,
                failure = gate.failure,
                elapsedMillis = clock() - startedAt,
                fetchedAtEpochMillis = startedAt
            )
            is ScrapeGate.AllowedWithWarnings -> warnings += gate.warnings
            ScrapeGate.Allowed -> Unit
        }

        val settings = policy.settings
        val pages = minOf(query.maxPages, settings.maxPagesPerSearch).coerceAtLeast(1)
        val listings = ArrayList<ScrapedListing>()
        val contacts = ArrayList<ListingContact>()
        val seenKeys = HashSet<String>()
        var strategy: String? = null
        var fetchedPages = 0
        var origin = urlBuilder.origin()

        for (page in 1..pages) {
            val pageQuery = query.copy(page = page)
            val pageUrl = urlBuilder.searchUrl(pageQuery)
            onProgress?.onProgress(page, pages, listings.size)

            if (page > 1) {
                val wait = rateLimiter.waitBeforeNextRequest(origin, settings.minRequestIntervalMillis)
                if (wait > 0) sleeper.sleep(wait)
            }
            rateLimiter.markRequest(origin)

            val fetch = fetcher.fetch(pageUrl)
            fetchedPages++
            origin = runCatching { java.net.URI.create(fetch.finalUrl).let { "${it.scheme}://${it.host}" } }
                .getOrNull() ?: origin

            val pageResult = interpretPage(fetch, pageQuery, pageUrl)
            warnings += pageResult.warnings

            if (pageResult.failure != null) {
                // The first page decides the outcome; later pages only add to what we already have.
                return if (listings.isEmpty()) {
                    pageResult.copy(
                        searchUrl = searchUrl,
                        warnings = warnings.distinct(),
                        fetchedPages = fetchedPages,
                        elapsedMillis = clock() - startedAt,
                        fetchedAtEpochMillis = startedAt
                    )
                } else {
                    ListingScrapeResult(
                        query = query,
                        sourceId = sourceId,
                        searchUrl = searchUrl,
                        listings = listings,
                        contacts = contacts,
                        warnings = (warnings + "Stopped after page $page: ${pageResult.failure.message}").distinct(),
                        fetchedPages = fetchedPages,
                        parseStrategy = strategy,
                        elapsedMillis = clock() - startedAt,
                        fetchedAtEpochMillis = startedAt
                    )
                }
            }

            // Dedupe across pages: portals repeat cards at page boundaries, and a repeated page must
            // not be reported as new inventory.
            val newOnes = pageResult.listings.filter { seenKeys.add(it.externalId ?: it.detailUrl) }
            listings += newOnes
            contacts += pageResult.contacts.filter { contact ->
                newOnes.any { it.detailUrl == contact.listingUrl || it.externalId == contact.listingExternalId }
            }
            strategy = pageResult.parseStrategy ?: strategy

            // Nothing new means the result set is exhausted; asking for more pages would only burn
            // requests against the portal.
            if (newOnes.isEmpty()) break
            if (listings.size >= query.maxResults) break
        }

        val trimmed = listings.take(query.maxResults)
        val trimmedContacts = contacts.filter { contact ->
            trimmed.any { it.detailUrl == contact.listingUrl || (it.externalId != null && it.externalId == contact.listingExternalId) }
        }

        if (trimmed.isEmpty()) {
            return ListingScrapeResult(
                query = query,
                sourceId = sourceId,
                searchUrl = searchUrl,
                warnings = warnings.distinct(),
                fetchedPages = fetchedPages,
                failure = ListingScrapeFailure(
                    kind = ListingScrapeFailureKind.EMPTY_RESULTS,
                    message = "No listings came back for '${query.location}'.",
                    remediation = "Widen the price range or filters, or check the search URL in a browser — " +
                        "if it shows results there, the portal's page layout changed and the parser needs an update."
                ),
                elapsedMillis = clock() - startedAt,
                fetchedAtEpochMillis = startedAt
            )
        }

        return ListingScrapeResult(
            query = query,
            sourceId = sourceId,
            searchUrl = searchUrl,
            listings = trimmed,
            contacts = trimmedContacts,
            warnings = warnings.distinct(),
            fetchedPages = fetchedPages,
            parseStrategy = strategy,
            elapsedMillis = clock() - startedAt,
            fetchedAtEpochMillis = startedAt
        )
    }

    /**
     * Pure interpretation of one fetched page. Classifies transport/HTTP/anti-bot outcomes first,
     * then parses, then attaches contacts.
     */
    fun interpretPage(fetch: ListingPageFetch, query: ListingSearchQuery, pageUrl: String): ListingScrapeResult {
        val failure = classify(fetch)
        if (failure != null) {
            return ListingScrapeResult(
                query = query,
                sourceId = sourceId,
                searchUrl = pageUrl,
                failure = failure
            )
        }

        val body = fetch.body.orEmpty()
        val parse = parser.parse(body, fetch.finalUrl)
        val contacts = contactExtractor.contactsFor(parse.listings, body)

        if (parse.listings.isEmpty()) {
            val kind = if (looksLikeNoResults(body)) {
                ListingScrapeFailureKind.EMPTY_RESULTS
            } else {
                ListingScrapeFailureKind.PARSE_DRIFT
            }
            return ListingScrapeResult(
                query = query,
                sourceId = sourceId,
                searchUrl = pageUrl,
                contacts = contacts,
                warnings = parse.warnings,
                parseStrategy = parse.strategy,
                failure = ListingScrapeFailure(
                    kind = kind,
                    message = when (kind) {
                        ListingScrapeFailureKind.EMPTY_RESULTS ->
                            "The portal answered normally but reported no matching listings."
                        else ->
                            "The portal page was fetched but no listing records could be read from it."
                    },
                    remediation = when (kind) {
                        ListingScrapeFailureKind.EMPTY_RESULTS ->
                            "Try a wider price range or a different location."
                        else ->
                            "The portal changed its page structure. Open the search URL in a browser to confirm " +
                            "results exist, then update ListingPortalSearchParser for the new payload shape."
                    },
                    detail = "strategy=${parse.strategy ?: "none"}; warnings=${parse.warnings.size}"
                )
            )
        }

        return ListingScrapeResult(
            query = query,
            sourceId = sourceId,
            searchUrl = pageUrl,
            listings = parse.listings,
            contacts = contacts,
            warnings = parse.warnings,
            parseStrategy = parse.strategy
        )
    }

    /** Transport → HTTP → anti-bot, in the order that produces the most useful diagnosis. */
    internal fun classify(fetch: ListingPageFetch): ListingScrapeFailure? {
        fetch.transportError?.let { error ->
            return ListingScrapeFailure(
                kind = ListingScrapeFailureKind.NETWORK_ERROR,
                message = "Could not reach the portal.",
                remediation = "Check the connection. If you are on a carrier network, the portal may be " +
                    "blocking this IP range — an operator-supplied proxy or a licensed feed avoids that.",
                detail = error
            )
        }
        if (fetch.status == 429) {
            return ListingScrapeFailure(
                kind = ListingScrapeFailureKind.RATE_LIMITED,
                message = "The portal answered 429 (too many requests).",
                remediation = "Wait a few minutes before the next search, or lower the page count per search.",
                detail = "http-429"
            )
        }
        val wall = PortalAntiBotDetector.detect(fetch)
        if (wall != null) {
            return ListingScrapeFailure(
                kind = ListingScrapeFailureKind.BLOCKED_BY_ANTIBOT,
                message = "The portal served an anti-bot wall (${wall.vendor}) instead of search results.",
                remediation = "This is the portal's bot protection, not a bug: direct retrieval from a " +
                    "phone or data-centre IP is refused. Use a licensed feed / partner API, or configure an " +
                    "operator-supplied residential proxy for this source.",
                detail = "vendor=${wall.vendor}; marker=${wall.marker}; status=${fetch.status}"
            )
        }
        if (!fetch.isSuccess) {
            return ListingScrapeFailure(
                kind = ListingScrapeFailureKind.HTTP_ERROR,
                message = "The portal returned HTTP ${fetch.status}.",
                remediation = when (fetch.status) {
                    404 -> "The search path does not exist — the location token is probably wrong."
                    in 500..599 -> "Portal-side failure; retry in a little while."
                    else -> "Unexpected status; check the portal in a browser."
                },
                detail = "http-${fetch.status}"
            )
        }
        return null
    }

    /** Phrases portals print when a query legitimately matched nothing. */
    private fun looksLikeNoResults(body: String): Boolean {
        val lowered = body.take(400_000).lowercase()
        return NO_RESULTS_MARKERS.any { it in lowered }
    }

    private companion object {
        val NO_RESULTS_MARKERS = listOf(
            "no results found",
            "0 results",
            "we couldn't find any",
            "no homes matched",
            "no matching homes",
            "\"totalresultcount\":0",
            "\"resultcount\":0"
        )
    }
}
