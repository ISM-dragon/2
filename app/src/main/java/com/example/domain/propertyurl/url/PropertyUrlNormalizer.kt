package com.example.domain.propertyurl.url

import java.util.Locale

enum class SlashPolicy { DROP_TRAILING_EXCEPT_ROOT, KEEP }

/** Knobs of the canonicalization step; defaults are the ones used in production. */
data class UrlNormalizationOptions(
    val stripTrackingParameters: Boolean = true,
    val stripFragment: Boolean = true,
    val stripDefaultPorts: Boolean = true,
    val stripWwwPrefix: Boolean = true,
    val sortQueryParameters: Boolean = true,
    val dropDuplicateQueryParameters: Boolean = true,
    val removeEmptyQueryParameters: Boolean = true,
    val decodeUnreservedEscapes: Boolean = true,
    val slashPolicy: SlashPolicy = SlashPolicy.DROP_TRAILING_EXCEPT_ROOT,
    val lowercaseHost: Boolean = true,
    val maxQueryParameters: Int = 48
) {
    companion object {
        /** Normalization that preserves everything (used for diagnostics / "show original"). */
        val NONE = UrlNormalizationOptions(
            stripTrackingParameters = false,
            stripFragment = false,
            stripDefaultPorts = false,
            stripWwwPrefix = false,
            sortQueryParameters = false,
            dropDuplicateQueryParameters = false,
            removeEmptyQueryParameters = false,
            decodeUnreservedEscapes = false,
            slashPolicy = SlashPolicy.KEEP
        )
    }
}

/** Outcome of a normalization pass, including an audit trail of what changed. */
data class NormalizationResult(
    val url: PropertyUrl,
    val changes: List<String> = emptyList(),
    val removedParameters: List<String> = emptyList(),
    val sensitiveParameters: List<String> = emptyList()
) {
    val wasModified: Boolean get() = changes.isNotEmpty()
}

/**
 * Canonicalizes property URLs so that the same listing pasted in five different ways produces the
 * exact same identity string:
 *
 *  - scheme/host lower-cased, `www.`/`m.` prefixes dropped, default ports removed
 *  - tracking/session query parameters removed (utm_*, fbclid, gclid, …)
 *  - query parameters sorted, de-duplicated and re-encoded canonically
 *  - fragments dropped, duplicate slashes collapsed, dot-segments resolved
 *  - trailing slash removed (except for the root path)
 *
 * The canonical form is what gets stored, deduplicated and fetched.
 */
object PropertyUrlNormalizer {

    private val TRACKING_EXACT = setOf(
        "gclid", "gclsrc", "dclid", "fbclid", "msclkid", "twclid", "igshid", "yclid", "ttclid",
        "mc_cid", "mc_eid", "_ga", "_gl", "_gac", "ref", "referrer", "referer", "source", "src",
        "campaign", "campaignid", "cmpid", "cid", "spmailingid", "s_kwcid", "trk", "trkid",
        "wt_mc", "wt_zmc", "partner", "affiliateid", "affid", "spm", "scid", "ncid", "elqtrackid",
        "so", "clearthis", "fb_action_ids", "y_source", "wickedid", "srsltid", "ss_source"
    )

    private val TRACKING_PREFIXES = listOf("utm_", "pk_", "mtm_", "hsa_", "vero_", "oly_", "dm_i", "itm_")

    private val SENSITIVE_NAMES = setOf(
        "token", "access_token", "id_token", "auth", "apikey", "api_key", "key", "secret", "session",
        "sessionid", "sid", "password", "pwd", "signature", "sig", "email", "phone"
    )

    fun isTrackingParameter(name: String): Boolean {
        val lowered = name.lowercase(Locale.US)
        return TRACKING_EXACT.contains(lowered) || TRACKING_PREFIXES.any { lowered.startsWith(it) }
    }

    fun isSensitiveParameter(name: String): Boolean {
        val lowered = name.lowercase(Locale.US)
        return SENSITIVE_NAMES.contains(lowered) || UrlSensitiveParameters.isSensitive(lowered)
    }

    /** Parses + canonicalizes a raw string (used by the validator). */
    fun normalize(
        rawInput: String,
        options: UrlNormalizationOptions = UrlNormalizationOptions(),
        defaultScheme: String = "https"
    ): NormalizationResult {
        val sanitized = UrlParser.sanitize(rawInput)
        val parts = UrlParser.decompose(sanitized)
        return canonicalize(parts, options, defaultScheme)
    }

    /** Canonicalizes an already parsed URL. */
    fun canonicalize(
        url: PropertyUrl,
        options: UrlNormalizationOptions = UrlNormalizationOptions()
    ): NormalizationResult {
        val parts = UrlParts(
            sanitized = url.original,
            scheme = url.scheme,
            hadAuthorityPrefix = true,
            userInfo = null,
            host = url.host,
            port = url.port,
            portRaw = url.port?.toString(),
            path = url.path,
            queryRaw = url.queryParameters.joinToString("&") { "${it.name}=${it.value}" },
            fragment = url.fragment
        )
        return canonicalize(parts, options, url.scheme.ifBlank { "https" })
    }

    internal fun canonicalize(
        parts: UrlParts,
        options: UrlNormalizationOptions = UrlNormalizationOptions(),
        defaultScheme: String = "https"
    ): NormalizationResult {
        val changes = ArrayList<String>()
        val removedParameters = ArrayList<String>()
        val sensitiveParameters = ArrayList<String>()

        val scheme = (parts.scheme ?: defaultScheme).lowercase(Locale.US)
        if (parts.scheme != null && parts.scheme != scheme) changes.add("scheme-lowercased")

        val rawHost = parts.host.orEmpty()
        var host = if (options.lowercaseHost) rawHost.lowercase(Locale.US) else rawHost
        if (options.stripWwwPrefix) {
            val stripped = UrlHosts.stripLeadingWww(host)
            if (stripped != host) {
                host = stripped
                changes.add("www-prefix-removed")
            }
        }

        var port = parts.port
        if (port != null && options.stripDefaultPorts && UrlRecomposer.isDefaultPort(scheme, port)) {
            port = null
            changes.add("default-port-removed")
        }

        val pathResult = canonicalizePath(parts.path, options)
        if (pathResult.changed) changes.addAll(pathResult.changes)

        val queryResult = canonicalizeQuery(parts.queryRaw.orEmpty(), options)
        removedParameters.addAll(queryResult.removed)
        sensitiveParameters.addAll(queryResult.sensitive)
        if (queryResult.addedChanges.isNotEmpty()) changes.addAll(queryResult.addedChanges)

        val fragment = if (options.stripFragment) null else parts.fragment
        if (options.stripFragment && !parts.fragment.isNullOrEmpty()) changes.add("fragment-removed")

        val normalized = UrlRecomposer.recompose(
            scheme = scheme,
            host = host,
            port = port,
            path = pathResult.path,
            queryParameters = queryResult.parameters,
            fragment = null
        )

        return NormalizationResult(
            url = PropertyUrl(
                original = parts.sanitized,
                normalized = normalized,
                scheme = scheme,
                host = host,
                port = port,
                path = pathResult.path,
                queryParameters = queryResult.parameters,
                fragment = fragment
            ),
            changes = changes,
            removedParameters = removedParameters,
            sensitiveParameters = sensitiveParameters
        )
    }

    private data class PathResult(val path: String, val changes: List<String>) {
        val changed: Boolean get() = changes.isNotEmpty()
    }

    private fun canonicalizePath(rawPath: String, options: UrlNormalizationOptions): PathResult {
        val changes = ArrayList<String>()
        var path = if (rawPath.isEmpty()) "/" else rawPath

        if (Regex("/{2,}").containsMatchIn(path)) {
            path = path.replace(Regex("/{2,}"), "/")
            changes.add("duplicate-slashes-collapsed")
        }

        if (path.contains("..") || path.contains("./")) {
            val resolved = resolveDotSegments(path)
            if (resolved != path) {
                path = resolved
                changes.add("dot-segments-resolved")
            }
        }

        if (options.decodeUnreservedEscapes) {
            val canonical = PercentCodec.canonicalizeEscapes(path)
            if (canonical != path) {
                path = canonical
                changes.add("percent-escapes-canonicalized")
            }
        }

        if (options.slashPolicy == SlashPolicy.DROP_TRAILING_EXCEPT_ROOT && path.length > 1 && path.endsWith("/")) {
            path = path.trimEnd('/')
            changes.add("trailing-slash-removed")
        }

        return PathResult(path.ifEmpty { "/" }, changes)
    }

    private data class QueryResult(
        val parameters: List<QueryParameter>,
        val removed: List<String>,
        val sensitive: List<String>,
        val addedChanges: List<String>
    )

    private fun canonicalizeQuery(rawQuery: String, options: UrlNormalizationOptions): QueryResult {
        val changes = ArrayList<String>()
        if (rawQuery.isEmpty()) return QueryResult(emptyList(), emptyList(), emptyList(), changes)

        val parsed = ArrayList<QueryParameter>()
        var malformed = false
        UrlParser.parseQuery(rawQuery) { malformed = true }.let { parsed.addAll(it) }
        if (malformed) changes.add("query-malformed-segments-dropped")

        val removed = ArrayList<String>()
        val sensitive = ArrayList<String>()
        val kept = ArrayList<QueryParameter>()
        val seenNames = HashSet<String>()

        for (parameter in parsed) {
            val lowered = parameter.name.lowercase(Locale.US)
            if (isSensitiveParameter(lowered)) {
                // Credential-like values are never part of the canonical URL: it is what gets
                // fetched, persisted, logged and used as the deduplication seed. The parameter is
                // recorded by *name* only so diagnostics can still say something was dropped.
                // Every other URL layer in this codebase behaves the same way.
                sensitive.add(parameter.name)
                removed.add(parameter.name)
                continue
            }
            if (options.stripTrackingParameters && isTrackingParameter(lowered)) {
                removed.add(parameter.name)
                continue
            }
            if (options.removeEmptyQueryParameters && parameter.value.isEmpty()) {
                removed.add(parameter.name)
                continue
            }
            if (options.dropDuplicateQueryParameters && !seenNames.add(lowered)) {
                removed.add(parameter.name)
                continue
            }
            kept.add(parameter)
        }

        var parameters: List<QueryParameter> = kept
        if (options.sortQueryParameters) {
            val sorted = kept.sortedWith(compareBy({ it.name.lowercase(Locale.US) }, { it.value }))
            if (sorted != kept) changes.add("query-parameters-sorted")
            parameters = sorted
        }
        if (parameters.size > options.maxQueryParameters) {
            parameters = parameters.take(options.maxQueryParameters)
            changes.add("query-parameters-truncated")
        }

        if (removed.isNotEmpty()) changes.add("tracking-parameters-removed")
        if (changes.isEmpty() && parameters.isEmpty() && parsed.isNotEmpty()) changes.add("query-emptied")

        return QueryResult(parameters, removed, sensitive, changes)
    }

    /** RFC 3986 §5.2.4 remove_dot_segments. */
    private fun resolveDotSegments(path: String): String {
        val output = ArrayList<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (output.isNotEmpty()) output.removeAt(output.size - 1)
                else -> output.add(segment)
            }
        }
        val trailingSlash = path.endsWith("/") || path.endsWith("/.") || path.endsWith("/..")
        return "/" + output.joinToString("/") + if (trailingSlash && output.isNotEmpty()) "/" else ""
    }
}
