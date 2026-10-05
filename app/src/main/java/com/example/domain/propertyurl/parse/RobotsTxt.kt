package com.example.domain.propertyurl.parse

import java.util.Locale

/** A single `Allow:`/`Disallow:` directive. */
data class RobotsRule(val allow: Boolean, val pattern: String) {

    val specificity: Int get() = pattern.trimEnd('$').length

    /** RFC 9309 / Google interpretation: prefix match, `*` wildcards, `$` end anchor. */
    fun matches(path: String): Boolean {
        if (pattern.isEmpty()) return false
        if (pattern == "/") return true
        val anchored = pattern.endsWith("$")
        val body = if (anchored) pattern.dropLast(1) else pattern
        val bodyRegex = buildString {
            append("^")
            body.split('*').forEachIndexed { index, part ->
                if (index > 0) append(".*")
                append(Regex.escape(part))
            }
            if (anchored) append("$")
        }
        return Regex(bodyRegex).containsMatchIn(path)
    }
}

/** Rules for one user-agent group. */
data class RobotsGroup(val agents: List<String>, val rules: List<RobotsRule>, val crawlDelaySeconds: Double?)

/**
 * Minimal, dependency-free `robots.txt` implementation.
 *
 * Used by [com.example.domain.propertyurl.port.RobotsTxtFetchPolicy] so a deployment can be
 * compliant by default without adopting a scraping framework. It is deliberately conservative:
 * unknown directives are ignored, the most specific matching rule wins, and ties resolve to "allow"
 * (the same tie-break browsers and Google use).
 */
class RobotsTxt private constructor(
    /** Parsed user-agent groups, in file order (diagnostics/tests). */
    val groups: List<RobotsGroup>,
    val crawlDelaySeconds: Double?
) {

    /** @return true when [path] may be fetched by [userAgent]. */
    fun isAllowed(path: String, userAgent: String): Boolean {
        val group = selectGroup(userAgent) ?: return true
        val candidates = group.rules.filter { it.matches(path) }
        if (candidates.isEmpty()) return true
        val best = candidates.maxWithOrNull(compareBy({ it.specificity }, { if (it.allow) 1 else 0 }))
        return best?.allow ?: true
    }

    fun selectGroup(userAgent: String): RobotsGroup? {
        val loweredAgent = userAgent.lowercase(Locale.US)
        val specific = groups
            .filter { group -> group.agents.none { it == "*" } }
            .filter { group -> group.agents.any { loweredAgent.contains(it) } }
            .maxByOrNull { group -> group.agents.maxOf { it.length } }
        return specific ?: groups.firstOrNull { group -> group.agents.any { it == "*" } }
    }

    companion object {

        /** Used when a site has no robots.txt (HTTP 404) or no rules apply. */
        val ALLOW_ALL = RobotsTxt(emptyList(), null)

        /** Used when robots.txt cannot be trusted/parsed and the deployment wants to be strict. */
        val DENY_ALL = RobotsTxt(
            groups = listOf(RobotsGroup(listOf("*"), listOf(RobotsRule(allow = false, pattern = "/")), null)),
            crawlDelaySeconds = null
        )

        fun parse(text: String): RobotsTxt {
            val groups = ArrayList<RobotsGroup>()
            var currentAgents = ArrayList<String>()
            var currentRules = ArrayList<RobotsRule>()
            var currentDelay: Double? = null

            fun flush() {
                if (currentAgents.isNotEmpty()) {
                    groups.add(RobotsGroup(currentAgents, currentRules, currentDelay))
                }
                currentAgents = ArrayList()
                currentRules = ArrayList()
                currentDelay = null
            }

            text.lineSequence().forEach { rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim().lowercase(Locale.US)
                val value = line.substring(separator + 1).trim()
                when (key) {
                    "user-agent", "useragent" -> {
                        if (currentRules.isNotEmpty() || currentDelay != null) flush()
                        currentAgents.add(value.lowercase(Locale.US))
                    }
                    "disallow" -> if (value.isNotEmpty()) currentRules.add(RobotsRule(allow = false, pattern = value))
                    "allow" -> if (value.isNotEmpty()) currentRules.add(RobotsRule(allow = true, pattern = value))
                    "crawl-delay" -> currentDelay = value.toDoubleOrNull()
                }
            }
            flush()
            val delay = groups.mapNotNull { it.crawlDelaySeconds }.minOrNull()
            return RobotsTxt(groups, delay)
        }
    }
}
