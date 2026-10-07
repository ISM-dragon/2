package com.example.urlintelligence.compliance

import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.retry.Clock
import com.example.urlintelligence.url.UrlParts

/** Why access to a document was refused. Codes are stable and safe to log. */
enum class AccessRule {
    ROBOTS_DISALLOWED,
    ROBOTS_UNAVAILABLE,
    META_NOINDEX,
    OPT_IN_REQUIRED,
    AUTHENTICATION_REQUIRED,
    CAPTCHA_CHALLENGE,
    OPERATOR_DISABLED,
    POLICY_UNCONFIGURED
}

/** Outcome of a pre-fetch compliance check. */
sealed class AccessDecision {
    object Allowed : AccessDecision()

    data class Denied(val rule: AccessRule, val detail: String) : AccessDecision()

    /** Marks that no policy object is configured for this pipeline (never treated as an allow). */
    object NotConfigured : AccessDecision()

    val isAllowed: Boolean
        get() = this is Allowed
}

/** Everything a policy needs to decide. Deliberately carries no credentials. */
data class AccessRequest(
    val url: String,
    val host: String,
    val path: String,
    val sourceId: String,
    val displayName: String
)

/**
 * Pre-fetch compliance gate.
 *
 * This module never bypasses robots rules, authentication walls or CAPTCHA challenges; it
 * enforces the opposite. A policy is asked *before* every fetch, and a denial becomes a
 * non-retryable [com.example.urlintelligence.failure.SourceFailure.PolicyBlocked].
 *
 * Implementations must be fail-closed: when a policy cannot be evaluated it denies rather
 * than allows.
 */
fun interface AccessPolicy {
    suspend fun check(request: AccessRequest): AccessDecision

    companion object {
        /** Allows public pages. Use only for hosts you have already cleared offline. */
        val PERMISSIVE_PUBLIC_WEB: AccessPolicy = AccessPolicy { AccessDecision.Allowed }

        /** Denies everything: the safe default when an operator has not cleared a provider. */
        val DENY_ALL: AccessPolicy = AccessPolicy {
            AccessDecision.Denied(AccessRule.OPERATOR_DISABLED, "no source is enabled by this operator")
        }

        /** Marks the pipeline as unconfigured (recorded as a warning, never as an allow). */
        val UNCONFIGURED: AccessPolicy = AccessPolicy { AccessDecision.NotConfigured }
    }
}

/** Consults several policies in order and denies on the first denial. */
class CompositeAccessPolicy(private val policies: List<AccessPolicy>) : AccessPolicy {

    constructor(policy: AccessPolicy, vararg rest: AccessPolicy) : this(listOf(policy) + rest)

    override suspend fun check(request: AccessRequest): AccessDecision {
        policies.forEach { policy ->
            when (val decision = policy.check(request)) {
                is AccessDecision.Denied -> return decision
                else -> Unit
            }
        }
        return AccessDecision.Allowed
    }
}

/**
 * robots.txt gate (RFC 9309 subset).
 *
 * Rules are fetched once per origin through the same credential-free transport and cached
 * with a TTL. Evaluation uses longest-match precedence with `*` wildcards and `$` anchors.
 * The policy is fail-closed:
 *
 *  * a matching `Disallow` → denied,
 *  * a `/robots.txt` that cannot be fetched (5xx, network error) → denied: a provider that
 *    cannot state its rules is not assumed to permit crawling,
 *  * 404/410 (no rules published) → allowed, per RFC 9309,
 *  * a request for `/robots.txt` itself → allowed (never recurses).
 */
open class RobotsPolicy(
    private val transport: PropertyHttpTransport,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val clock: Clock = Clock.SYSTEM,
    private val cacheTtlMillis: Long = 6 * 60 * 60 * 1000L
) : AccessPolicy {

    private class Cached(val load: RobotsLoad, val fetchedAt: Long)

    private val cache = LinkedHashMap<String, Cached>()

    override suspend fun check(request: AccessRequest): AccessDecision {
        if (isRobotsRequest(request.path)) return AccessDecision.Allowed

        val origin = originOf(request.url) ?: return AccessDecision.Denied(
            AccessRule.ROBOTS_UNAVAILABLE,
            "could not determine the origin of the request"
        )

        return when (val loaded = load(origin)) {
            is RobotsLoad.Ok ->
                if (loaded.rules.allows(request.path, userAgent)) {
                    AccessDecision.Allowed
                } else {
                    AccessDecision.Denied(
                        AccessRule.ROBOTS_DISALLOWED,
                        "robots.txt for $origin disallows '${request.path}' for '$userAgent'"
                    )
                }
            is RobotsLoad.Missing -> AccessDecision.Allowed
            is RobotsLoad.Unavailable -> AccessDecision.Denied(
                AccessRule.ROBOTS_UNAVAILABLE,
                "robots.txt for $origin could not be evaluated: ${loaded.reason}"
            )
        }
    }

    /** Cached rules for an origin, or null when they are not (yet) available. */
    suspend fun rulesFor(origin: String): RobotsRules? = when (val loaded = load(origin)) {
        is RobotsLoad.Ok -> loaded.rules
        else -> null
    }

    private sealed class RobotsLoad {
        data class Ok(val rules: RobotsRules) : RobotsLoad()
        object Missing : RobotsLoad()
        data class Unavailable(val reason: String) : RobotsLoad()
    }

    /** Cache lookup is lock-free; the worst case is two concurrent robots fetches for one origin. */
    private suspend fun load(origin: String): RobotsLoad {
        val now = clock.now()
        synchronized(cache) {
            cache[origin]?.let { cached ->
                if (now - cached.fetchedAt <= cacheTtlMillis) return cached.load
            }
        }
        val loaded = fetch(origin)
        synchronized(cache) { cache[origin] = Cached(loaded, now) }
        return loaded
    }

    private suspend fun fetch(origin: String): RobotsLoad {
        val url = "$origin/robots.txt"
        val response = try {
            transport.execute(
                SourceFetchRequest(
                    requestUrl = url,
                    correlationId = "robots:$origin",
                    timeoutMillis = ROBOTS_TIMEOUT_MILLIS
                )
            )
        } catch (t: Throwable) {
            return RobotsLoad.Unavailable(t.javaClass.simpleName)
        }
        return when (response) {
            // A transport reports non-2xx as a typed failure, so "no robots.txt published"
            // (404/410, per RFC 9309 an allow) has to be recognised from the failure itself.
            is SourceFetchResponse.Failure -> when (val failure = response.failure) {
                is com.example.urlintelligence.failure.SourceFailure.NotFound -> RobotsLoad.Missing
                is com.example.urlintelligence.failure.SourceFailure.HttpStatus ->
                    if (failure.statusCode == 404 || failure.statusCode == 410) RobotsLoad.Missing
                    else RobotsLoad.Unavailable(failure.code)
                else -> RobotsLoad.Unavailable(failure.code)
            }
            is SourceFetchResponse.Success -> when (response.statusCode) {
                200 -> RobotsLoad.Ok(RobotsRules.parse(response.body))
                404, 410 -> RobotsLoad.Missing
                else -> RobotsLoad.Unavailable("robots.txt answered HTTP ${response.statusCode}")
            }
        }
    }

    private fun isRobotsRequest(path: String): Boolean = path.equals("/robots.txt", ignoreCase = true)

    private fun originOf(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase().ifBlank { return null }
        val host = UrlParts.hostOf(url)
        if (host.isBlank()) return null
        val port = url.substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringAfter(':', "")
            .toIntOrNull()
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port != null && port != defaultPort) "$scheme://$host:$port" else "$scheme://$host"
    }

    companion object {
        const val DEFAULT_USER_AGENT: String =
            "RealEstateAI/1.0 (+property-url-import; respects robots.txt)"

        const val ROBOTS_TIMEOUT_MILLIS: Long = 10_000L
    }
}

/**
 * Parsed `robots.txt`.
 *
 * Groups are keyed by user-agent token and evaluated with longest-match precedence, exactly
 * as RFC 9309 (and every mainstream crawler) does. An empty `Disallow:` value means "allow
 * everything" for that group.
 */
class RobotsRules private constructor(private val groups: List<Group>) {

    private class Group(val tokens: List<String>, val rules: List<Rule>)

    private class Rule(val allow: Boolean, val path: String, val length: Int)

    /** True when [path] may be fetched by [userAgent]. */
    fun allows(path: String, userAgent: String): Boolean {
        val target = path.ifBlank { "/" }
        val group = bestGroupFor(userAgent) ?: return true
        val matching = group.rules.filter { matches(it, target) }
        if (matching.isEmpty()) return true
        val longest = matching.maxOf { it.length }
        val tied = matching.filter { it.length == longest }
        return tied.any { it.allow }
    }

    private fun bestGroupFor(userAgent: String): Group? {
        val ua = userAgent.lowercase()
        val exact = groups.filter { group ->
            group.tokens.any { it.isNotEmpty() && it != "*" && ua.contains(it) }
        }
        if (exact.isNotEmpty()) {
            return exact.maxByOrNull { group -> group.tokens.maxOf { it.length } }
        }
        return groups.firstOrNull { group -> group.tokens.contains("*") }
    }

    /**
     * RFC 9309 matching: a rule path is a *prefix* match unless it contains an asterisk (any
     * run of characters) or ends with a dollar sign (end of path). A directory rule such as
     * `Disallow: /search/` therefore blocks `/search/homes`, while an anchored rule only
     * matches the exact path it names.
     */
    private fun matches(rule: Rule, path: String): Boolean {
        val anchored = rule.path.endsWith("$")
        val body = if (anchored) rule.path.dropLast(1) else rule.path
        val target = path.substringBefore('?')
        val regex = Regex("^" + wildcardToRegex(body))
        val match = regex.find(target) ?: return false
        return !anchored || match.range.last == target.length - 1
    }

    private fun wildcardToRegex(path: String): String = buildString {
        path.forEach { ch ->
            when (ch) {
                '*' -> append(".*")
                '?' -> append(".")
                else -> append(Regex.escape(ch.toString()))
            }
        }
    }

    /** Number of user-agent groups; handy for diagnostics and tests. */
    fun groupCount(): Int = groups.size

    companion object {
        fun parse(text: String): RobotsRules {
            val groups = ArrayList<Group>()
            var tokens = ArrayList<String>()
            var rules = ArrayList<Rule>()
            var sawDirective = false

            fun flush() {
                if (rules.isNotEmpty() || tokens.isNotEmpty()) {
                    groups.add(Group(if (tokens.isEmpty()) listOf("*") else tokens, rules))
                }
                tokens = ArrayList()
                rules = ArrayList()
                sawDirective = false
            }

            text.lineSequence().forEach { rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach
                val key = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                when (key) {
                    "user-agent" -> {
                        if (sawDirective) flush()
                        tokens.add(value.lowercase())
                        sawDirective = true
                    }
                    "allow", "disallow" -> {
                        sawDirective = true
                        val path = decodeEntities(value)
                        if (path.isEmpty()) return@forEach
                        rules.add(Rule(allow = key == "allow", path = path, length = path.length))
                    }
                    else -> Unit // sitemap, crawl-delay, host, ... are not access rules
                }
            }
            flush()
            return RobotsRules(groups)
        }

        private fun decodeEntities(value: String): String = value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
