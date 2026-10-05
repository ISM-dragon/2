package com.example.urlintelligence.source

import com.example.urlintelligence.url.NormalizedUrl

/** Outcome of "which provider does this URL belong to, and which listing is it". */
data class SourceDetection(
    val sourceId: String,
    val sourcePropertyId: String?,
    /** 0.0..1.0 — how sure we are about the provider (not about the data). */
    val confidence: Double,
    val descriptor: SourceDescriptor?,
    val reason: String
) {
    val isKnownSource: Boolean
        get() = descriptor != null && sourceId != SourceDescriptor.GENERIC_SOURCE_ID
}

/**
 * Maps a canonical URL onto a source descriptor and extracts the listing id.
 *
 * Scoring: host match is the floor, a matching path shape adds confidence, and a
 * successfully extracted id adds the final increment. The highest score wins so a
 * specific provider always beats the generic fallback.
 */
class SourceDetector(
    private val descriptors: List<SourceDescriptor> = KnownSources.all()
) {

    fun detect(url: NormalizedUrl, body: String? = null): SourceDetection {
        val host = url.host.lowercase()
        val path = url.path

        val candidates = descriptors
            .filter { it.id != SourceDescriptor.GENERIC_SOURCE_ID }
            .filter { it.matchesHost(host) }
            .map { descriptor -> score(descriptor, host, path) }
            .sortedByDescending { it.confidence }
            .toList()

        val best = candidates.firstOrNull()
        val generic = descriptors.firstOrNull { it.id == SourceDescriptor.GENERIC_SOURCE_ID }

        if (best == null) {
            val genericId = generic?.let { extractGenericId(path) }
            return SourceDetection(
                sourceId = SourceDescriptor.GENERIC_SOURCE_ID,
                sourcePropertyId = genericId,
                confidence = generic?.confidenceFloor ?: 0.2,
                descriptor = generic,
                reason = "no registered provider matches host '$host'; using generic parser"
            )
        }

        var sourcePropertyId = best.sourcePropertyId
        val noteBuilder = StringBuilder(best.reason)
        if (sourcePropertyId == null && body != null) {
            val fromBody = best.descriptor.extractIdFromBody(body)
            if (fromBody != null) {
                sourcePropertyId = fromBody
                noteBuilder.append("; id recovered from document body")
            }
        }

        return SourceDetection(
            sourceId = best.descriptor.id,
            sourcePropertyId = sourcePropertyId,
            confidence = best.confidence.coerceIn(0.0, 1.0),
            descriptor = best.descriptor,
            reason = noteBuilder.toString()
        )
    }

    /**
     * Convenience overload for callers that already hold a canonical string.
     * Falls back to the generic source when the string cannot be parsed.
     */
    fun detectCanonical(canonicalUrl: String): SourceDetection {
        val host: String
        val path: String
        val scheme: String
        val uri = try {
            java.net.URI(canonicalUrl)
        } catch (t: Exception) {
            null
        }
        if (uri != null && !uri.host.isNullOrBlank()) {
            scheme = uri.scheme ?: "https"
            host = uri.host!!.lowercase()
            path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        } else {
            scheme = "https"
            host = canonicalUrl.substringAfter("://", "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore(':')
                .lowercase()
            path = canonicalUrl.substringAfter("://", "")
                .substringAfter(host, "")
                .substringAfter(':', "")
                .substringBefore('?')
                .let { if (it.startsWith("/")) it else "/$it" }
                .ifBlank { "/" }
        }
        return detect(
            NormalizedUrl(
                original = canonicalUrl,
                canonical = canonicalUrl,
                scheme = scheme,
                host = host,
                port = null,
                path = path,
                query = emptyMap(),
                droppedParameters = emptyList()
            )
        )
    }

    private fun score(descriptor: SourceDescriptor, host: String, path: String): ScoredCandidate {
        var confidence = descriptor.confidenceFloor
        val reasons = mutableListOf("host matches ${descriptor.id}")

        if (descriptor.matchesPath(path)) {
            confidence += 0.3
            reasons.add("path shape matches")
        }
        val id = descriptor.extractIdFromPath(path)
        if (id != null) {
            confidence += 0.2
            reasons.add("listing id extracted from path")
        } else {
            reasons.add("no listing id in path")
        }

        return ScoredCandidate(
            descriptor = descriptor,
            sourcePropertyId = id,
            confidence = confidence.coerceAtMost(1.0),
            reason = reasons.joinToString("; ")
        )
    }

    /** Last-resort id: trailing numeric path segment or the last slug segment. */
    private fun extractGenericId(path: String): String? {
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        val numeric = segments.lastOrNull { it.all { ch -> ch.isDigit() } && it.length in 4..12 }
        if (numeric != null) return numeric
        return segments.lastOrNull()?.takeIf { it.length in 4..64 }
    }

    private data class ScoredCandidate(
        val descriptor: SourceDescriptor,
        val sourcePropertyId: String?,
        val confidence: Double,
        val reason: String
    )

    companion object {
        fun default(): SourceDetector = SourceDetector(KnownSources.all())
    }
}
