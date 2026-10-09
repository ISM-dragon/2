package com.example.domain.intelligence.scrape

import java.io.File

/**
 * Loads the portal-search fixtures (`app/src/test/resources/fixtures/listing-search`).
 *
 * Mirrors the lookup order of `com.example.domain.propertyurl.Fixtures`: classpath first (Gradle
 * puts test resources there), then the working-directory candidates that a single-module IDE run
 * lands in.
 */
object ScrapeFixtures {

    private const val ROOT = "fixtures/listing-search"

    fun text(name: String): String {
        Thread.currentThread().contextClassLoader.getResourceAsStream("$ROOT/$name")?.let { resource ->
            return resource.use { it.readBytes().decodeToString() }
        }
        val candidates = listOf(
            File("app/src/test/resources/$ROOT/$name"),
            File("src/test/resources/$ROOT/$name"),
            File("../app/src/test/resources/$ROOT/$name")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException("Fixture '$name' not found (looked at ${candidates.joinToString()})")
        return file.readText()
    }

    /** A fetched page carrying the named fixture as its body. */
    fun page(
        name: String,
        url: String = "https://www.zillow.com/homes/for_sale/austin-tx_rb/",
        status: Int = 200,
        transportError: String? = null
    ): ListingPageFetch = ListingPageFetch(
        requestedUrl = url,
        finalUrl = url,
        status = status,
        headers = mapOf("content-type" to "text/html; charset=utf-8"),
        body = text(name),
        elapsedMillis = 120,
        transportError = transportError
    )
}

/** Fetcher that serves fixtures and records what was asked for. */
class RecordingFetcher(
    private val responder: (url: String) -> ListingPageFetch,
    private val robotsTxt: String? = null
) : ListingPageFetcher {

    val requestedUrls = ArrayList<String>()
    var robotsRequests = 0
        private set

    override suspend fun fetch(url: String): ListingPageFetch {
        requestedUrls += url
        return responder(url)
    }

    override suspend fun fetchRobotsTxt(origin: String): String? {
        robotsRequests++
        return robotsTxt
    }
}

/** Sleeper that records instead of waiting, so tests stay instant. */
class RecordingSleeper : Sleeper {
    val sleeps = ArrayList<Long>()
    override suspend fun sleep(millis: Long) {
        sleeps += millis
    }
}

/** Permissive robots.txt: a `*` group that disallows nothing. */
const val ALLOW_ALL_ROBOTS = """
User-agent: *
Disallow:
"""

/** Zillow-style robots.txt: search paths are disallowed, detail pages are not. */
const val RESTRICTIVE_ROBOTS = """
User-agent: *
Disallow: /homes/for_sale/
Disallow: /homes/for_rent/
Disallow: /search/
Allow: /homedetails/
Crawl-delay: 5
"""
