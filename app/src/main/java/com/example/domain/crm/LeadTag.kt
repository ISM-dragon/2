package com.example.domain.crm

import java.util.Locale

/**
 * Deterministic tag handling.
 *
 * Tags are free-form operator vocabulary ("cash-buyer", "probate", "needs-roof"), which is useful
 * precisely because it is free-form — and useless if "Probate", "probate " and "probate_" exist side
 * by side. Every tag entering the model therefore goes through [normalize], and [Lead] refuses to
 * hold a tag that is not in canonical form. That single rule keeps tag filters exact (no fuzzy
 * matching anywhere) and makes tag-based reporting reproducible.
 */
object LeadTags {

    const val MAX_TAG_LENGTH = 32

    /** Well-known tags with product meaning. Custom tags are allowed; these are the shared ones. */
    val KNOWN: List<String> = listOf(
        "absentee-owner",
        "cash-buyer",
        "code-violation",
        "divorce",
        "expired-listing",
        "fire-damage",
        "high-equity",
        "hot",
        "inherited",
        "landlord",
        "no-equity",
        "out-of-state-owner",
        "probate",
        "referral",
        "rehab-heavy",
        "renter-occupied",
        "tax-delinquent",
        "tired-landlord",
        "vacant",
        "wholesale-list"
    ).sorted()

    /** Tags that indicate a property-level disposition strategy rather than a seller characteristic. */
    val DISPOSITION_TAGS: List<String> = listOf("cash-buyer", "hot", "rehab-heavy")

    /**
     * Canonical form of a tag: lower case, trimmed, internal whitespace/underscores collapsed to a
     * single hyphen, unsupported characters removed. Returns null when nothing usable is left, so the
     * caller can surface "tag ignored" instead of silently storing junk.
     */
    fun normalize(raw: String): String? {
        val collapsed = raw.trim().lowercase(Locale.US)
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("[^a-z0-9-]"), "")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        if (collapsed.isEmpty()) return null
        return collapsed.take(MAX_TAG_LENGTH).trim('-').ifEmpty { null }
    }

    /**
     * Normalizes a collection of tags into a canonical, sorted, de-duplicated set.
     *
     * [maxTags] is enforced by keeping the first [maxTags] tags in a deterministic order (known tags
     * first, then alphabetical), never by dropping a random subset. Ignored tags are reported by
     * [NormalizationOutcome] so the intake path can tell the operator what was dropped.
     */
    fun normalizeAll(rawTags: Iterable<String>, maxTags: Int = LeadTagPolicy.DEFAULT.maxTagsPerLead): NormalizationOutcome {
        val dropped = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        rawTags.forEach { raw ->
            val normalized = normalize(raw)
            when {
                normalized == null -> dropped += raw
                normalized !in accepted -> accepted += normalized
            }
        }
        val ordered = accepted.sortedWith(
            compareBy<String> { tag -> if (tag in KNOWN) 0 else 1 }.thenBy { it }
        )
        val kept = ordered.take(maxTags).toSortedSet()
        dropped += ordered.drop(maxTags)
        return NormalizationOutcome(tags = kept, droppedRawValues = dropped.distinct())
    }

    fun isCanonical(tag: String): Boolean = normalize(tag) == tag

    /** True when the tag is part of the shared vocabulary rather than a per-operator custom tag. */
    fun isKnown(tag: String): Boolean = tag in KNOWN

    /** Result of a normalization pass, kept so intake can report what it ignored and why. */
    data class NormalizationOutcome(
        val tags: Set<String>,
        val droppedRawValues: List<String> = emptyList()
    ) {
        val hasDropped: Boolean get() = droppedRawValues.isNotEmpty()
    }
}

/**
 * Limits for tag vocabularies. Validated so a misconfigured policy fails at construction time
 * rather than silently truncating operator data.
 */
data class LeadTagPolicy(
    val maxTagsPerLead: Int = 20,
    /** Warn when a lead carries more than this many tags: usually a sign of tag sprawl. */
    val warnAboveTagCount: Int = 12
) {
    init {
        require(maxTagsPerLead in 1..100) { "maxTagsPerLead must be within 1..100" }
        require(warnAboveTagCount in 1..100) { "warnAboveTagCount must be within 1..100" }
        require(warnAboveTagCount <= maxTagsPerLead) { "warnAboveTagCount must not exceed maxTagsPerLead" }
    }

    companion object {
        val DEFAULT = LeadTagPolicy()
    }
}
