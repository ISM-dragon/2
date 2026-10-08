package com.example.domain.propertyurl.url

import com.example.domain.propertyurl.source.SourceDetection
import com.example.domain.propertyurl.source.SourceRegistry
import java.util.Locale

/** How the final URL was obtained from the raw user input. */
enum class ResolutionStrategy {
    /** The input was exactly one link. */
    DIRECT_LINK,
    /** The link was cut out of a pasted message/share text. */
    LINK_EXTRACTED_FROM_TEXT,
    /** The source definition's path rule supplied the listing id. */
    SOURCE_PATH_RULE,
    /** The listing id came from a query parameter. */
    QUERY_PARAMETER,
    /** Unknown host: a plausible listing id was guessed from the last path segment. */
    GENERIC_HEURISTIC
}

/**
 * A validated URL bound to a source (or to the generic source) plus, when possible, the
 * source-native listing id used for idempotency.
 */
data class ResolvedPropertyUrl(
    val url: PropertyUrl,
    val detection: SourceDetection,
    val externalListingId: String?,
    val externalIdOrigin: String?,
    val strategy: ResolutionStrategy,
    val notes: List<String> = emptyList()
) {
    val sourceId: String? get() = detection.sourceId

    val normalizedUrl: String get() = url.normalized

    val isSourceSupported: Boolean get() = detection.isSupported

    /**
     * Stable key for deduplication: the source id and its listing id when known, else a digest of
     * the URL.
     *
     * The URL seed is hashed rather than stored verbatim because a pasted link can carry
     * credential-like query values (`access_token=…`, `sig=…`). This key is written to the job
     * store on disk, so it must not become a clear-text copy of a secret; a digest keeps the
     * deduplication semantics (same URL ⇒ same key) without persisting the value.
     */
    val idempotencyKey: String
        get() = if (!sourceId.isNullOrBlank() && !externalListingId.isNullOrBlank()) {
            "$sourceId:$externalListingId"
        } else {
            "url:" + sha256Hex(url.identitySeed).take(32)
        }

    private fun sha256Hex(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

sealed interface UrlResolutionResult {

    data class Resolved(val resolved: ResolvedPropertyUrl) : UrlResolutionResult

    /** The pasted text contained several distinct listing links. */
    data class Ambiguous(val candidates: List<ResolvedPropertyUrl>) : UrlResolutionResult

    data class Rejected(
        val input: String,
        val issues: List<UrlValidationIssue>
    ) : UrlResolutionResult {
        val primaryCode: UrlValidationCode get() = issues.firstOrNull()?.code ?: UrlValidationCode.NOT_A_URL
        val summary: String get() = issues.joinToString("; ") { it.message }
    }

    fun resolvedOrNull(): ResolvedPropertyUrl? = (this as? Resolved)?.resolved
}

/**
 * Generic property URL resolver.
 *
 * Responsibilities (all pure, no I/O):
 *  1. pull one or more link candidates out of arbitrary pasted text (messages, emails, share sheets);
 *  2. validate + canonicalize each candidate ([PropertyUrlValidator]);
 *  3. detect the source ([SourceRegistry]);
 *  4. extract the source-native listing id (or a generic heuristic id) for idempotent imports.
 *
 * Site specific quirks live in [PropertySourceDefinition] path rules, never in this class.
 */
class PropertyUrlResolver(
    private val registry: SourceRegistry,
    private val validator: PropertyUrlValidator = PropertyUrlValidator()
) {

    fun resolve(rawInput: String): UrlResolutionResult {
        val candidates = extractCandidateStrings(rawInput)
        if (candidates.isEmpty()) {
            return UrlResolutionResult.Rejected(
                rawInput,
                listOf(
                    UrlValidationIssue(
                        code = UrlValidationCode.NOT_A_URL,
                        severity = UrlValidationSeverity.ERROR,
                        message = "No link found in the provided text"
                    )
                )
            )
        }

        val resolved = ArrayList<ResolvedPropertyUrl>()
        var firstIssues: List<UrlValidationIssue> = emptyList()

        candidates.forEach { candidate ->
            when (val result = resolveSingle(candidate, candidates.size == 1)) {
                is UrlResolutionResult.Resolved -> resolved.add(result.resolved)
                is UrlResolutionResult.Rejected -> if (firstIssues.isEmpty()) firstIssues = result.issues
                is UrlResolutionResult.Ambiguous -> Unit
            }
        }

        val distinct = dedupe(resolved)
        return when {
            distinct.isEmpty() -> UrlResolutionResult.Rejected(rawInput, firstIssues)
            distinct.size == 1 -> UrlResolutionResult.Resolved(distinct.first())
            else -> UrlResolutionResult.Ambiguous(distinct)
        }
    }

    /** Resolves every link found in the text independently (used by imports of shared deal lists). */
    fun resolveAll(rawInput: String): List<UrlResolutionResult> =
        extractCandidateStrings(rawInput).map { candidate ->
            resolveSingle(candidate, isSingleCandidate = false)
        }

    fun resolveSingle(candidate: String, isSingleCandidate: Boolean): UrlResolutionResult {
        val validation = validator.validate(candidate)
        if (validation is UrlValidationResult.Invalid) {
            return UrlResolutionResult.Rejected(candidate, validation.issues)
        }
        val url = (validation as UrlValidationResult.Valid).url
        val detection = registry.detect(url)
        val notes = ArrayList<String>()
        var strategy = if (isSingleCandidate) ResolutionStrategy.DIRECT_LINK
        else ResolutionStrategy.LINK_EXTRACTED_FROM_TEXT
        var externalId: String? = null
        var origin: String? = null

        val definition = detection.definition
        if (definition != null) {
            val found = definition.extractListingId(url.path, url.query)
            if (found != null) {
                externalId = found.first
                origin = found.second
                strategy = if (found.second.startsWith("query:")) {
                    ResolutionStrategy.QUERY_PARAMETER
                } else {
                    ResolutionStrategy.SOURCE_PATH_RULE
                }
            } else if (definition.domains.isNotEmpty()) {
                notes.add("no listing id pattern matched; falling back to URL identity")
            }
        }

        if (externalId == null) {
            val heuristic = genericListingId(url)
            if (heuristic != null) {
                externalId = heuristic
                origin = "heuristic"
                strategy = ResolutionStrategy.GENERIC_HEURISTIC
            }
        }

        if (detection.alternatives.isNotEmpty()) {
            notes.add("other matching sources: " + detection.alternatives.joinToString(",") { it.sourceId })
        }
        validation.warnings.forEach { warning -> notes.add(warning.code.name.lowercase(Locale.US)) }

        return UrlResolutionResult.Resolved(
            ResolvedPropertyUrl(
                url = url,
                detection = detection,
                externalListingId = externalId,
                externalIdOrigin = origin,
                strategy = strategy,
                notes = notes
            )
        )
    }

    /** Pulls link candidates out of free text: full URLs, `www.` links and bare `domain.tld/path`. */
    fun extractCandidateStrings(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val found = LinkedHashSet<String>()

        FULL_URL.findAll(trimmed).forEach { match -> addCandidate(found, match.value) }

        if (found.isEmpty()) {
            // Pasted text without a scheme: `zillow.com/homedetails/...`.
            trimmed.split(Regex("\\s+")).forEach { token ->
                val cleaned = clean(token)
                if (looksLikeSchemelessUrl(cleaned)) addCandidate(found, cleaned)
            }
        }

        if (found.isEmpty() && looksLikeSchemelessUrl(clean(trimmed))) {
            addCandidate(found, clean(trimmed))
        }
        return found.toList()
    }

    private fun addCandidate(target: MutableSet<String>, raw: String) {
        val cleaned = clean(raw)
        if (cleaned.isNotEmpty()) target.add(cleaned)
    }

    private fun clean(token: String): String = UrlParser.sanitize(token)

    private fun looksLikeSchemelessUrl(token: String): Boolean {
        if (token.isEmpty() || token.length > 2048) return false
        if (token.any { it == ' ' }) return false
        val hostPart = token.substringBefore('/').substringBefore('?').substringBefore('#')
        if (!hostPart.contains('.')) return false
        val tld = hostPart.substringAfterLast('.')
        if (tld.length < 2 || tld.length > 24) return false
        if (tld.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return false
        // A bare domain (no path/query) is only accepted when the registry knows it.
        val hasPathOrQuery = token.length > hostPart.length
        if (hasPathOrQuery) return true
        return registry.candidatesForHost(hostPart.lowercase(Locale.US)).isNotEmpty()
    }

    private fun genericListingId(url: PropertyUrl): String? {
        GENERIC_ID_PARAMETER_NAMES.forEach { name ->
            val value = url.query[name.lowercase(Locale.US)]
            if (!value.isNullOrBlank() && value.length in 4..64) return value
        }
        val last = url.pathSegments.lastOrNull() ?: return null
        return last.takeIf { it.length >= 5 && it.all { ch -> ch.isDigit() } }
    }

    private fun dedupe(candidates: List<ResolvedPropertyUrl>): List<ResolvedPropertyUrl> {
        val seen = LinkedHashMap<String, ResolvedPropertyUrl>()
        candidates.forEach { candidate ->
            val key = candidate.idempotencyKey
            val existing = seen[key]
            if (existing == null) {
                seen[key] = candidate
            } else if (existing.externalListingId == null && candidate.externalListingId != null) {
                seen[key] = candidate
            }
        }
        return seen.values.toList()
    }

    private companion object {
        val FULL_URL = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"'`]+")
        val GENERIC_ID_PARAMETER_NAMES = listOf("listingId", "listing_id", "propertyId", "property_id", "homeId", "id")
    }
}
