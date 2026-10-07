package com.example.urlintelligence.provenance

import com.example.urlintelligence.model.PropertyField

/**
 * Where a parsed document came from.
 *
 * This is the *only* input that can upgrade a value to [VerificationLevel.LIVE_FETCH_VERIFIED],
 * and it must be reported by the transport that actually performed the request. Nothing in this
 * module ever guesses it: the default is [UNSPECIFIED], so a caller that has not proven a live
 * fetch can never end up claiming one.
 */
enum class FetchOrigin {
    /** A live network request performed by the transport in this process. */
    LIVE_NETWORK,

    /** A previously recorded response replayed by the transport (fixtures, recorded fixtures, tests). */
    REPLAYED,

    /** Served from a local cache without contacting the provider in this run. */
    LOCAL_CACHE,

    /** The caller supplied the document directly; no fetch is associated with it. */
    PROVIDED_DOCUMENT,

    /** The transport did not report an origin. Treat as "we cannot prove anything". */
    UNSPECIFIED
}

/**
 * How strongly a field is verified.
 *
 * The three levels are deliberately distinct so that "we parsed something" is never
 * confused with "we fetched this from the provider just now":
 *
 *  * [UNVERIFIED] — the value came from a heuristic that is not covered by a versioned
 *    parser contract, or a degraded/drifted parse. Useful, but never presented as verified.
 *  * [PARSER_VERIFIED] — a versioned parser matched the document and produced the value.
 *    This is what fixture/offline parsing can legitimately claim.
 *  * [LIVE_FETCH_VERIFIED] — as above **and** the document was fetched from the provider by
 *    a live transport in this run ([FetchOrigin.LIVE_NETWORK]).
 */
enum class VerificationLevel {
    UNVERIFIED,
    PARSER_VERIFIED,
    LIVE_FETCH_VERIFIED;

    /** Convenience for sorting/summaries; higher is better. */
    val rank: Int
        get() = when (this) {
            UNVERIFIED -> 0
            PARSER_VERIFIED -> 1
            LIVE_FETCH_VERIFIED -> 2
        }
}

/**
 * Field-level tally of how well an imported property is verified.
 *
 * Callers (and UIs) should read [isLiveVerified] before telling a user that data was
 * "verified against <provider>" — it is true only when a live fetch actually happened.
 */
data class VerificationSummary(
    val origin: FetchOrigin,
    val liveVerifiedFields: Int,
    val parserVerifiedFields: Int,
    val unverifiedFields: Int,
    /** Parser ids + versions that produced fields on this property, e.g. `zillow.html@3`. */
    val parsers: List<String> = emptyList(),
    /** Document digests (one per fetched document) that contributed to this property. */
    val documentDigests: List<String> = emptyList()
) {
    val liveVerified: Boolean
        get() = origin == FetchOrigin.LIVE_NETWORK && liveVerifiedFields > 0

    val parserVerified: Boolean
        get() = liveVerifiedFields + parserVerifiedFields > 0

    val totalFields: Int
        get() = liveVerifiedFields + parserVerifiedFields + unverifiedFields

    /** Human-readable, log-safe explanation of what was actually verified. */
    fun describe(): String = when {
        liveVerifiedInvalidCombination() ->
            "live fetch claimed for a non-live document"
        liveVerified -> "live fetch verified ($liveVerifiedFields fields via ${parsers.joinToString()})"
        parserVerified -> "parser verified ($parserVerifiedFields fields via ${parsers.joinToString()})"
        else -> "unverified"
    }

    private fun liveVerifiedInvalidCombination(): Boolean =
        origin != FetchOrigin.LIVE_NETWORK && liveVerifiedFields > 0

    companion object {
        val NONE: VerificationSummary =
            VerificationSummary(FetchOrigin.UNSPECIFIED, 0, 0, 0)

        /** Builds a summary from a provenance map (the source of truth per field). */
        fun from(provenance: Map<PropertyField, FieldProvenance>, origin: FetchOrigin): VerificationSummary {
            var live = 0
            var parser = 0
            var unverified = 0
            val parsers = LinkedHashSet<String>()
            val digests = LinkedHashSet<String>()
            provenance.values.forEach { entry ->
                when (entry.verification) {
                    VerificationLevel.LIVE_FETCH_VERIFIED -> live++
                    VerificationLevel.PARSER_VERIFIED -> parser++
                    VerificationLevel.UNVERIFIED -> unverified++
                }
                entry.parserVersion?.let { version ->
                    val id = entry.parserId ?: entry.extractor
                    parsers.add("$id@$version")
                }
                entry.documentDigest?.let { digests.add(it) }
            }
            return VerificationSummary(
                origin = origin,
                liveVerifiedFields = live,
                parserVerifiedFields = parser,
                unverifiedFields = unverified,
                parsers = parsers.toList(),
                documentDigests = digests.toList()
            )
        }
    }
}

/**
 * Resolves the verification level of one field.
 *
 * @param verifiedByParser set to false by extractors that are not covered by a versioned
 *        parser contract (free-text heuristics, degraded/drifted parses).
 */
fun verificationLevelFor(verifiedByParser: Boolean, origin: FetchOrigin): VerificationLevel = when {
    !verifiedByParser -> VerificationLevel.UNVERIFIED
    origin == FetchOrigin.LIVE_NETWORK -> VerificationLevel.LIVE_FETCH_VERIFIED
    else -> VerificationLevel.PARSER_VERIFIED
}
