package com.example.domain.propertyurl.parse

import com.example.domain.propertyurl.model.ExtractedFacts
import com.example.domain.propertyurl.model.FieldConflict
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.ProvenanceFactory
import com.example.domain.propertyurl.model.ProvenancePolicy
import com.example.domain.propertyurl.model.RawSourceDocument
import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureClassifier
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.source.PropertySourceDefinition
import java.util.concurrent.CancellationException

/** What a parser receives: the document, the provenance factory and the source it belongs to. */
data class ParserContext(
    val source: PropertySourceDefinition,
    val provenance: ProvenanceFactory,
    val document: RawSourceDocument
)

/** Result of a single parser. A parser never throws for "unexpected layout"; it reports it. */
data class ParserResult(
    val facts: ExtractedFacts = ExtractedFacts.EMPTY,
    val warnings: List<ImportWarning> = emptyList(),
    /** Set when the parser recognised its format but the content was unusable. */
    val failure: SourceFailure? = null,
    /** Set when the parser declined the document (format mismatch) — not an error. */
    val declined: Boolean = false,
    val reason: String? = null
) {
    val hasFacts: Boolean get() = facts.isNotEmpty()

    companion object {
        fun declined(reason: String): ParserResult = ParserResult(declined = true, reason = reason)

        fun failed(failure: SourceFailure, warnings: List<ImportWarning> = emptyList()): ParserResult =
            ParserResult(warnings = warnings, failure = failure)
    }
}

/**
 * A document parser. Implementations must be pure and deterministic: the same [RawSourceDocument]
 * always produces the same facts (fixture tests rely on it).
 *
 * @param priority lower runs first (structured data before heuristics).
 */
interface SourceDocumentParser {
    val parserId: String
    val version: String
    val priority: Int
    /** Cheap pre-check; when false the parser is not run at all. */
    fun canParse(document: RawSourceDocument): Boolean
    fun parse(document: RawSourceDocument, context: ParserContext): ParserResult
}

/** Result of running a whole parser chain. */
data class ParseOutcome(
    val facts: ExtractedFacts,
    val usedParsers: List<String>,
    val warnings: List<ImportWarning> = emptyList(),
    /** Non-null only when nothing usable could be extracted at all. */
    val failure: SourceFailure? = null
) {
    val hasFacts: Boolean get() = facts.isNotEmpty()

    val isEmpty: Boolean get() = facts.isEmpty()

    /** True when some fields were extracted but the record is not complete (partial success). */
    val isPartial: Boolean get() = facts.isNotEmpty() && warnings.any {
        it.code == ImportWarning.WarningCode.PARTIAL_PARSE ||
            it.code == ImportWarning.WarningCode.PARSER_SCHEMA_DRIFT ||
            it.code == ImportWarning.WarningCode.MISSING_CRITICAL_FIELD
    }

    companion object {
        fun failed(failure: SourceFailure, warnings: List<ImportWarning> = emptyList()): ParseOutcome =
            ParseOutcome(ExtractedFacts.EMPTY, emptyList(), warnings, failure)
    }
}

data class ParserChainOptions(
    /** Upper bound on how many parsers run per document (cost control on huge payloads). */
    val maxParsersPerRun: Int = 5,
    /**
     * Stop as soon as these fields are present — avoids re-parsing a 3 MB payload for one field.
     *
     * The set spans identity, price and the physical essentials on purpose: stopping after a
     * JSON-LD block that only carries an address and a price would silently degrade a complete
     * listing into a partial record, when the page's embedded state also had the beds/baths/area.
     */
    val shortCircuitFields: Set<PropertyField> = setOf(
        PropertyField.ADDRESS_LINE_1,
        PropertyField.CITY,
        PropertyField.PRICE_AMOUNT,
        PropertyField.BEDROOMS,
        PropertyField.BATHROOMS,
        PropertyField.LIVING_AREA_SQFT
    ),
    /** Minimum share of critical fields a partial parse must reach before it is not a failure. */
    val minimumCriticalFieldsForPartialSuccess: Int = 1
)

/**
 * Runs the parsers in priority order, merges their facts through [ProvenancePolicy] and turns the
 * outcome into either facts (full/partial success) or a classified failure.
 */
class ParserChain(
    parsers: List<SourceDocumentParser>,
    private val options: ParserChainOptions = ParserChainOptions(),
    private val mergePolicy: ProvenancePolicy = ProvenancePolicy(),
    private val failureClassifier: SourceFailureClassifier = SourceFailureClassifier()
) {

    /** Sorted (priority ascending) parsers this chain runs. */
    val parsers: List<SourceDocumentParser> = parsers.sortedBy { it.priority }

    val parserIds: List<String> get() = parsers.map { it.parserId }

    fun parse(document: RawSourceDocument, context: ParserContext): ParseOutcome {
        val applicable = parsers.filter { it.canParse(document) }
        if (applicable.isEmpty()) {
            return ParseOutcome.failed(
                failureClassifier.fromParse(
                    parserId = "parser-chain",
                    reason = "no parser accepts content type ${document.contentType ?: "unknown"}",
                    kind = SourceFailureKind.UNSUPPORTED_CONTENT_TYPE,
                    sourceId = context.source.sourceId,
                    url = document.finalUrl
                ),
                warnings = listOf(
                    ImportWarning(
                        code = ImportWarning.WarningCode.UNSUPPORTED_DOCUMENT_TYPE,
                        message = "No parser accepts this document type",
                        sourceId = context.source.sourceId
                    )
                )
            )
        }

        var facts = ExtractedFacts.EMPTY
        val usedParsers = ArrayList<String>()
        val warnings = ArrayList<ImportWarning>()
        val conflicts = ArrayList<FieldConflict>()
        var hardFailure: SourceFailure? = null
        var ran = 0

        for (parser in applicable) {
            if (ran >= options.maxParsersPerRun) break
            ran++
            val result = try {
                parser.parse(document, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ParserResult.failed(
                    failureClassifier.fromParse(
                        parserId = parser.parserId,
                        reason = "parser threw ${e.javaClass.simpleName}",
                        kind = SourceFailureKind.PARSE_FAILED,
                        sourceId = context.source.sourceId,
                        url = document.finalUrl
                    )
                )
            }

            if (result.declined) continue
            if (result.hasFacts) {
                usedParsers.add("${parser.parserId}@${parser.version}")
                facts = facts.plus(result.facts)
            }
            warnings.addAll(result.warnings)
            if (result.failure != null && hardFailure == null) hardFailure = result.failure

            val merged = facts.bestPerField(mergePolicy, conflicts)
            if (options.shortCircuitFields.all { merged.containsKey(it) }) break
        }

        conflicts.forEach { conflict ->
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.FIELD_CONFLICT,
                    message = "Conflicting values for ${conflict.field.name.lowercase()}: " +
                        "kept ${conflict.kept.provenance.sourceId} (${conflict.reason})",
                    field = conflict.field,
                    sourceId = conflict.kept.provenance.sourceId,
                    detail = "dropped=${conflict.dropped.provenance.sourceId}"
                )
            )
        }

        if (facts.isEmpty()) {
            val failure = hardFailure ?: failureClassifier.fromParse(
                parserId = usedParsers.firstOrNull() ?: "parser-chain",
                reason = "no property facts could be extracted from the document",
                kind = SourceFailureKind.PARSE_FAILED,
                sourceId = context.source.sourceId,
                url = document.finalUrl
            )
            return ParseOutcome.failed(failure, warnings)
        }

        val criticalPresent = facts.fields.count { it.isCritical }
        if (criticalPresent < options.minimumCriticalFieldsForPartialSuccess) {
            warnings.add(
                ImportWarning(
                    code = ImportWarning.WarningCode.PARTIAL_PARSE,
                    message = "Only ${facts.fields.size} non-critical field(s) were extracted",
                    sourceId = context.source.sourceId
                )
            )
        }

        return ParseOutcome(facts = facts, usedParsers = usedParsers, warnings = warnings, failure = null)
    }
}
