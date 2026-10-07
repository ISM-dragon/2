package com.example.urlintelligence.parser

import com.example.urlintelligence.model.PropertyField

/**
 * A structural probe into a provider document.
 *
 * Probes are the cheap "is this still the page we think it is" checks that make schema
 * drift observable instead of silent. Each probe is intentionally tiny (a marker string or
 * a small regex) so that a markup rework shows up as a failing probe rather than as a
 * quietly emptier property record.
 */
data class SignatureProbe(
    val name: String,
    val pattern: Regex,
    /** A required probe failing means the parser no longer recognises the page at all. */
    val required: Boolean = false
) {
    constructor(name: String, literal: String, required: Boolean = false) :
        this(name, Regex(Regex.escape(literal), RegexOption.IGNORE_CASE), required)

    fun matches(document: String): Boolean = pattern.containsMatchIn(document)
}

/** What to do when drift is detected. */
enum class DriftPolicy {
    /** Record nothing (not recommended; keeps legacy behaviour). */
    OFF,

    /** Record a warning on the import result and mark affected fields as not parser-verified. */
    WARN,

    /** Refuse the parse when the drift is [DriftLevel.MAJOR]. */
    FAIL
}

/** How badly a document drifted away from the shape the parser was written for. */
enum class DriftLevel {
    NONE,

    /** The page is recognisably the same, but optional structure moved or thinned out. */
    MINOR,

    /** Required structure is gone (or almost nothing was extracted): assume the layout changed. */
    MAJOR
}

/**
 * Result of comparing a document against a [ParserSpec].
 *
 * @property extractedFields how many fields the parser managed to produce for this document
 */
data class SchemaDriftReport(
    val parserId: String,
    val parserVersion: String,
    val level: DriftLevel,
    val matchedProbes: List<String>,
    val missingProbes: List<String>,
    val extractedFields: Int,
    val detail: String
) {
    val isDrift: Boolean
        get() = level != DriftLevel.NONE

    /** Log-safe one-liner, safe to attach to warnings and telemetry. */
    fun summary(): String =
        "schema drift[$level] $parserId@$parserVersion: $detail " +
            "(matched=${matchedProbes.size} missing=${missingProbes.size} fields=$extractedFields)"

    companion object {
        fun none(spec: ParserSpec, extractedFields: Int, matched: List<String>): SchemaDriftReport =
            SchemaDriftReport(
                parserId = spec.parserId,
                parserVersion = spec.version,
                level = DriftLevel.NONE,
                matchedProbes = matched,
                missingProbes = emptyList(),
                extractedFields = extractedFields,
                detail = "document matched the parser signature"
            )
    }
}

/**
 * Versioned parser contract.
 *
 * Every provider parser declares one spec: an id, a version that is bumped whenever the
 * extraction rules change, the probes that identify a healthy document, and the minimum
 * amount of data the parser must be able to produce. That gives the pipeline three things
 * it cannot get from selectors alone:
 *
 *  1. **parser versioning** — every extracted field is stamped with the parser id/version
 *     (see [com.example.urlintelligence.provenance.FieldProvenance.parserVersion]),
 *  2. **schema-drift detection** — a silent markup change becomes a typed warning or a
 *     [com.example.urlintelligence.failure.SourceFailure.SchemaDrift] failure,
 *  3. **a safe extension point** — adding a provider is a spec + a selector table.
 */
data class ParserSpec(
    val parserId: String,
    val version: String,
    val probes: List<SignatureProbe> = emptyList(),
    /** How many of the (non-required) probes must match for the page to look healthy. */
    val minProbesMatched: Int = 1,
    /** Minimum number of fields a healthy parse should produce. */
    val minFieldsExtracted: Int = 1,
    /** Structural fields this parser is expected to be able to produce when they exist. */
    val expectedFields: Set<PropertyField> = emptySet(),
    /** Mark a field as not parser-verified when its value came from a drifted document. */
    val downgradeUnverifiedOnDrift: Boolean = true
) {
    init {
        require(parserId.isNotBlank()) { "parserId must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(minProbesMatched >= 0) { "minProbesMatched must be >= 0" }
        require(minFieldsExtracted >= 0) { "minFieldsExtracted must be >= 0" }
    }

    val qualifiedVersion: String
        get() = "$parserId@$version"

    /**
     * Compares a document to this spec.
     *
     * MAJOR drift means the parser must not be trusted for this document: a required probe is
     * missing, fewer probes matched than required, or the parse produced fewer fields than the
     * contract promises. MINOR drift means the page still looks right but optional structure
     * moved (e.g. one of the embedded blobs is gone while the others are intact).
     */
    fun inspect(document: String, extractedFields: Int): SchemaDriftReport {
        val matched = ArrayList<String>()
        val missing = ArrayList<String>()
        var requiredMissing = 0
        probes.forEach { probe ->
            if (probe.matches(document)) {
                matched.add(probe.name)
            } else {
                missing.add(probe.name)
                if (probe.required) requiredMissing++
            }
        }

        val requiredTotal = probes.count { it.required }
        val optionalRequired = (minProbesMatched - requiredTotal).coerceAtLeast(0)
        val optionalMatched = matched.count { name -> probes.first { it.name == name }.required.not() }

        val detail: String
        val level: DriftLevel = when {
            requiredMissing > 0 -> {
                detail = "required probe(s) missing: ${missing.joinToString()}"
                DriftLevel.MAJOR
            }
            extractedFields < minFieldsExtracted -> {
                detail = "extracted $extractedFields field(s), expected at least $minFieldsExtracted"
                DriftLevel.MAJOR
            }
            optionalMatched < optionalRequired -> {
                detail = "only $optionalMatched of the expected signature marker(s) matched"
                DriftLevel.MAJOR
            }
            missing.isNotEmpty() -> {
                detail = "optional probe(s) missing: ${missing.joinToString()}"
                DriftLevel.MINOR
            }
            else -> {
                detail = "document matched the parser signature"
                DriftLevel.NONE
            }
        }
        return SchemaDriftReport(
            parserId = parserId,
            parserVersion = version,
            level = level,
            matchedProbes = matched,
            missingProbes = missing,
            extractedFields = extractedFields,
            detail = detail
        )
    }

    /** Convenience for specs without probes (used by the most generic parsers). */
    fun hasSignature(): Boolean = probes.isNotEmpty()

    companion object {
        /**
         * Default contract for a schema.org/HTML parser: healthy if the document carries either
         * JSON-LD or an embedded state blob and yields at least one field.
         */
        fun structuredHtml(parserId: String, version: String): ParserSpec = ParserSpec(
            parserId = parserId,
            version = version,
            probes = listOf(
                SignatureProbe("json-ld", Regex("application/ld\\+json", RegexOption.IGNORE_CASE)),
                SignatureProbe("embedded-state", Regex("\"(@?[a-zA-Z]*[Pp]roperty|listing|zpid|price)\"\\s*:"))
            ),
            minProbesMatched = 1,
            minFieldsExtracted = 1
        )
    }
}
