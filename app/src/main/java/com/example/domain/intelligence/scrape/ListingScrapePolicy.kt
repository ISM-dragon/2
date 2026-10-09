package com.example.domain.intelligence.scrape

import com.example.domain.propertyurl.parse.RobotsTxt
import java.net.URI
import java.util.Locale

/**
 * How the deployment treats `robots.txt` for portal searches.
 *
 *  * [ENFORCE] (default): a `Disallow` for the search path stops the search. A robots.txt that cannot
 *    be read also stops it — fail-closed, matching RFC 9309 and the `propertyurl` compliance gate.
 *  * [ADVISORY]: read and record the file, warn, proceed anyway. This is an operator decision with
 *    legal consequences, so it is opt-in, per-installation, and surfaced in the UI settings.
 */
enum class RobotsCompliance { ENFORCE, ADVISORY }

/** Everything an operator can change about portal retrieval, with the safe defaults. */
data class PortalScrapeSettings(
    /** The user switched live portal search on after reading what it means. Nothing runs without it. */
    val userConsented: Boolean = false,
    val robotsCompliance: RobotsCompliance = RobotsCompliance.ENFORCE,
    val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
    /** Pages walked per search. Portals paginate at ~42 cards, so 3 pages ≈ 120 listings. */
    val maxPagesPerSearch: Int = 3,
    /** Minimum gap between two page fetches inside one search. */
    val minRequestIntervalMillis: Long = 2_000,
    val userAgent: String = DEFAULT_USER_AGENT,
    /** Response bodies bigger than this are truncated by the fetcher. */
    val maxResponseBytes: Int = 8_000_000
) {
    init {
        require(maxPagesPerSearch in 1..ListingSearchQuery.MAX_PAGES_LIMIT) {
            "maxPagesPerSearch must be in 1..${ListingSearchQuery.MAX_PAGES_LIMIT}"
        }
        require(minRequestIntervalMillis >= 0) { "minRequestIntervalMillis must be >= 0" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "RealEstateAI-ListingSearch/1.0 (+https://realestate-ai.example/bot; contact: ops@realestate-ai.example)"

        val DEFAULT_ALLOWED_HOSTS = setOf("www.zillow.com", "zillow.com")
    }
}

sealed interface ScrapeGate {
    object Allowed : ScrapeGate
    data class Denied(val failure: ListingScrapeFailure) : ScrapeGate
    /** Allowed, but with a compliance note that must reach the UI/log (robots advisory, etc.). */
    data class AllowedWithWarnings(val warnings: List<String>) : ScrapeGate
}

/**
 * The compliance gate in front of every portal fetch.
 *
 * Order matters: consent → host allowlist → robots.txt. A denial names the rule that fired and says
 * what the operator can do, because "search refused" with no reason reads like a bug.
 *
 * Note that robots.txt is read *live*: which paths a portal permits is the portal's decision, and it
 * changes. This class never hardcodes "zillow allows X".
 */
class ListingScrapePolicy(
    private val settingsProvider: () -> PortalScrapeSettings
) {

    val settings: PortalScrapeSettings get() = settingsProvider()

    suspend fun check(
        searchUrl: String,
        robotsTxtFetcher: suspend (origin: String) -> String?
    ): ScrapeGate {
        val current = settingsProvider()

        if (!current.userConsented) {
            return ScrapeGate.Denied(
                ListingScrapeFailure(
                    kind = ListingScrapeFailureKind.DENIED_BY_POLICY,
                    message = "Live portal search is switched off.",
                    remediation = "Turn on Settings → Data sources → Live portal search. It fetches public " +
                        "listing pages directly from the portal, which is restricted by the portal's terms of " +
                        "service and robots.txt — read the notice before enabling it."
                )
            )
        }

        val host = hostOf(searchUrl)
        if (host == null || current.allowedHosts.none { it.equals(host, ignoreCase = true) }) {
            return ScrapeGate.Denied(
                ListingScrapeFailure(
                    kind = ListingScrapeFailureKind.DENIED_BY_POLICY,
                    message = "Host '${host ?: "unknown"}' is not on the allowed portal list.",
                    remediation = "Only these hosts may be fetched: ${current.allowedHosts.sorted().joinToString()}."
                )
            )
        }

        val origin = "${searchUrl.substringBefore("://")}://$host"
        val path = pathOf(searchUrl)
        val robotsText = robotsTxtFetcher(origin)

        if (robotsText == null) {
            val message = "robots.txt could not be read from $origin."
            return when (current.robotsCompliance) {
                RobotsCompliance.ENFORCE -> ScrapeGate.Denied(
                    ListingScrapeFailure(
                        kind = ListingScrapeFailureKind.DENIED_BY_POLICY,
                        message = message,
                        remediation = "Fail-closed: without the file we cannot know what the portal permits. " +
                            "Retry with a working connection, or switch robots.txt handling to 'Advisory' in " +
                            "Settings if you have cleared this origin yourself.",
                        detail = "robots-txt-unavailable"
                    )
                )
                RobotsCompliance.ADVISORY -> ScrapeGate.AllowedWithWarnings(listOf(message + " Proceeding (advisory mode)."))
            }
        }

        val robots = RobotsTxt.parse(robotsText)
        val allowed = robots.isAllowed(path, current.userAgent)
        if (!allowed) {
            val disallow = robots.groups
                .flatMap { group -> group.rules.filter { !it.allow && it.matches(path) } }
                .maxByOrNull { it.specificity }?.pattern
            return when (current.robotsCompliance) {
                RobotsCompliance.ENFORCE -> ScrapeGate.Denied(
                    ListingScrapeFailure(
                        kind = ListingScrapeFailureKind.DENIED_BY_POLICY,
                        message = "$origin/robots.txt disallows '$path' (rule: ${disallow ?: "n/a"}).",
                        remediation = "Use a licensed feed or the portal's partner API for this source. " +
                            "You can switch robots.txt handling to 'Advisory' in Settings — that is an explicit " +
                            "decision to ignore the file, with the legal consequences that come with it.",
                        detail = "robots-disallow:$disallow"
                    )
                )
                RobotsCompliance.ADVISORY -> ScrapeGate.AllowedWithWarnings(
                    listOf("robots.txt disallows '$path' (rule: ${disallow ?: "n/a"}) — proceeding in advisory mode.")
                )
            }
        }

        return ScrapeGate.Allowed
    }

    private fun hostOf(url: String): String? =
        runCatching { URI.create(url.trim()).host?.trimEnd('.')?.lowercase(Locale.US) }.getOrNull()

    private fun pathOf(url: String): String {
        val uri = runCatching { URI.create(url.trim()) }.getOrNull()
        val path = uri?.path?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri?.rawQuery?.takeIf { it.isNotBlank() }
        return if (query == null) path else "$path?$query"
    }
}

/**
 * Keeps one search from turning into a burst.
 *
 * The limiter is per origin and monotonic-clock based, so it is safe to hand to several coroutines.
 * It never sleeps: it reports the wait and the caller decides (the scraper turns a wait into a typed
 * failure rather than blocking a UI coroutine).
 */
class ScrapeRateLimiter(private val clock: () -> Long = System::currentTimeMillis) {

    private val lastRequestAt = HashMap<String, Long>()

    /** @return millis still to wait before the next request to [origin] may be sent (0 = go). */
    fun waitBeforeNextRequest(origin: String, minIntervalMillis: Long): Long {
        if (minIntervalMillis <= 0) return 0
        val last = synchronized(lastRequestAt) { lastRequestAt[origin] } ?: return 0
        return (last + minIntervalMillis - clock()).coerceAtLeast(0)
    }

    fun markRequest(origin: String) {
        synchronized(lastRequestAt) { lastRequestAt[origin] = clock() }
    }
}
