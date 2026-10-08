package com.example.domain.propertyurl.port

import com.example.domain.propertyurl.parse.RobotsTxt
import com.example.domain.propertyurl.source.PropertySourceDefinition
import com.example.domain.propertyurl.source.SourceStatus
import com.example.domain.propertyurl.url.PropertyUrl
import com.example.domain.propertyurl.url.UrlRecomposer

sealed interface FetchPolicyDecision {

    object Allowed : FetchPolicyDecision

    data class Denied(val reason: String, val rule: String? = null) : FetchPolicyDecision

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * Compliance gate consulted before every fetch.
 *
 * This is where a deployment encodes its own legal position (robots.txt, allow/deny lists, partner
 * agreements) *without* the adapters knowing anything about it. Defaults are permissive so the
 * layer works out of the box, but every production deployment is expected to compose its own policy.
 */
interface FetchPolicy {

    suspend fun check(url: PropertyUrl, source: PropertySourceDefinition?): FetchPolicyDecision

    companion object {
        val ALLOW_ALL: FetchPolicy = object : FetchPolicy {
            override suspend fun check(url: PropertyUrl, source: PropertySourceDefinition?) = FetchPolicyDecision.Allowed
        }
    }
}

/** Denies sources that are disabled, not implemented, or that forbid automated retrieval. */
class SourceCapabilityFetchPolicy(
    private val denyPlannedSources: Boolean = true,
    private val denyDisallowedAutomation: Boolean = true
) : FetchPolicy {

    override suspend fun check(url: PropertyUrl, source: PropertySourceDefinition?): FetchPolicyDecision {
        val definition = source ?: return FetchPolicyDecision.Allowed
        if (definition.status == SourceStatus.DISABLED) {
            return FetchPolicyDecision.Denied("source '${definition.sourceId}' is disabled", "source-disabled")
        }
        if (denyPlannedSources && definition.status == SourceStatus.PLANNED) {
            return FetchPolicyDecision.Denied(
                "source '${definition.sourceId}' has no adapter yet",
                "source-planned"
            )
        }
        if (denyDisallowedAutomation && !definition.capabilities.allowsAutomatedFetching) {
            return FetchPolicyDecision.Denied(
                "automated retrieval is not enabled for '${definition.sourceId}'",
                "source-automation-disabled"
            )
        }
        return FetchPolicyDecision.Allowed
    }
}

/** Applies several policies; the first denial wins. */
class CompositeFetchPolicy(private val policies: List<FetchPolicy>) : FetchPolicy {

    override suspend fun check(url: PropertyUrl, source: PropertySourceDefinition?): FetchPolicyDecision {
        for (policy in policies) {
            val decision = policy.check(url, source)
            if (decision is FetchPolicyDecision.Denied) return decision
        }
        return FetchPolicyDecision.Allowed
    }

    companion object {
        fun of(vararg policies: FetchPolicy): FetchPolicy = CompositeFetchPolicy(policies.toList())
    }
}

/**
 * robots.txt aware policy.
 *
 * Fetches `https://host/robots.txt` through the same [HttpFetcher] used for listings, caches the
 * parsed rules per origin (TTL) and applies the most specific rule for the request path. Robots
 * responses are never cached on failure.
 *
 * Fail-closed by default: when robots.txt cannot be evaluated (5xx, timeout, redirect loop,
 * transport error) the origin is denied rather than assumed permissive, matching RFC 9309 and the
 * `urlintelligence` compliance gate. `404`/`410` still mean "no rules published" and allow.
 * [UnavailableBehavior.ALLOW] remains available for deployments that have cleared an origin offline.
 */
class RobotsTxtFetchPolicy(
    private val httpFetcher: HttpFetcher,
    private val clock: Clock,
    private val userAgent: String = FetchOptions.DEFAULT_USER_AGENT,
    private val cacheTtlMillis: Long = 6 * 60 * 60 * 1000,
    /** Behaviour when robots.txt cannot be retrieved (5xx, timeout, redirect loop). */
    private val behaviorWhenUnavailable: UnavailableBehavior = UnavailableBehavior.DENY
) : FetchPolicy {

    enum class UnavailableBehavior { ALLOW, DENY }

    private class CacheEntry(val robots: RobotsTxt, val fetchedAtEpochMillis: Long)

    private val cache = LinkedHashMap<String, CacheEntry>()
    private val lock = Any()

    override suspend fun check(url: PropertyUrl, source: PropertySourceDefinition?): FetchPolicyDecision {
        val origin = "${url.scheme}://${originHost(url)}"
        val robots = loadRobots(origin, url.scheme, url.host, url.port)
            ?: return when (behaviorWhenUnavailable) {
                UnavailableBehavior.ALLOW -> FetchPolicyDecision.Allowed
                UnavailableBehavior.DENY -> FetchPolicyDecision.Denied(
                    "robots.txt unavailable for $origin",
                    "robots-unavailable"
                )
            }

        val path = if (url.path.isEmpty()) "/" else url.path
        return if (robots.isAllowed(path, userAgent)) {
            FetchPolicyDecision.Allowed
        } else {
            FetchPolicyDecision.Denied("disallowed by robots.txt for ${robotsDirSource(origin)}", "robots-disallow")
        }
    }

    /** Exposed for tests and diagnostics. */
    fun cachedOrigins(): Set<String> = synchronized(lock) { cache.keys.toSet() }

    fun clearCache() = synchronized(lock) { cache.clear() }

    private fun originHost(url: PropertyUrl): String =
        if (url.port == null) url.host else "${url.host}:${url.port}"

    private fun robotsDirSource(origin: String): String = "$origin/robots.txt"

    private suspend fun loadRobots(origin: String, scheme: String, host: String, port: Int?): RobotsTxt? {
        val now = clock.nowEpochMillis()
        synchronized(lock) {
            cache[origin]?.let { entry ->
                if (now - entry.fetchedAtEpochMillis <= cacheTtlMillis) return entry.robots
            }
        }

        val robotsUrl = UrlRecomposer.recompose(
            scheme = scheme,
            host = host,
            port = port,
            path = "/robots.txt",
            queryParameters = emptyList(),
            fragment = null
        )

        val result = try {
            httpFetcher.fetch(
                HttpRequest(url = robotsUrl, headers = mapOf("User-Agent" to userAgent)),
                FetchOptions(
                    connectTimeoutMillis = 4_000,
                    readTimeoutMillis = 6_000,
                    maxBodyBytes = 512_000,
                    maxRedirects = 2,
                    userAgent = userAgent
                )
            )
        } catch (e: Exception) {
            null
        }

        val robots = when (result) {
            is HttpFetchResult.Response -> when {
                result.status == 404 || result.status == 410 -> RobotsTxt.ALLOW_ALL
                result.status in 200..299 -> result.body?.let { RobotsTxt.parse(it) }
                else -> null
            }
            is HttpFetchResult.TransportError -> null
            null -> null
        }

        if (robots != null) {
            synchronized(lock) {
                cache[origin] = CacheEntry(robots, now)
                // Bound the cache: policies can be long lived in a mobile process.
                if (cache.size > MAX_CACHED_ORIGINS) {
                    cache.keys.firstOrNull()?.let { cache.remove(it) }
                }
            }
        }
        return robots
    }

    private companion object {
        const val MAX_CACHED_ORIGINS = 64
    }
}

/** Convenience factory for a permissive-but-honest default policy chain. */
object DefaultFetchPolicies {

    fun robotsAware(httpFetcher: HttpFetcher, clock: Clock, userAgent: String): FetchPolicy =
        CompositeFetchPolicy.of(
            SourceCapabilityFetchPolicy(),
            RobotsTxtFetchPolicy(httpFetcher, clock, userAgent)
        )

    fun unrestricted(): FetchPolicy = CompositeFetchPolicy.of(SourceCapabilityFetchPolicy())
}
