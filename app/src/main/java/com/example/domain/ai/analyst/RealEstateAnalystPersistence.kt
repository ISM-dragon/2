package com.example.domain.ai.analyst

import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable projection of one validated analyst analysis.
 *
 * Property names mirror `PropertyAiAnalysisEntity` (`property_ai_analysis`) one-for-one, in the same
 * declaration order, so later repository work can persist these values directly and cannot
 * accidentally reinterpret a claim's epistemic status. Room, DAOs, and repositories stay out of this
 * package: the analyst contract produces values, persistence decides where they live.
 *
 * Column mapping and its deliberate gaps:
 *
 * | Entity column          | Source                                                                 |
 * |------------------------|------------------------------------------------------------------------|
 * | `summary`              | First sentence of the validated investment thesis (bounded preview).  |
 * | `investmentThesis`     | The investment thesis statement, verbatim.                            |
 * | `strengthsJson`        | Encoded `strengths` claims (classification, statement, refs, confidence). |
 * | `weaknessesJson`       | `[]` - this contract has no weaknesses section; risks carry that role. |
 * | `risksJson`            | Encoded `risks` claims.                                                |
 * | `redFlagsJson`         | Encoded `redFlags` claims.                                             |
 * | `recommendedStrategy`  | The recommended-strategy statement, verbatim (qualitative, never a figure). |
 * | `recommendedOfferRange`| `""` - offer ranges are financial figures owned by the deterministic engine. |
 * | `questionsForSellerJson` | The due-diligence question strings, in order.                        |
 * | `dueDiligenceJson`     | Encoded due-diligence questions (question, priority, basis).           |
 * | `confidence`           | Minimum claim confidence, i.e. the most conservative claim in the set. |
 * | `evidenceJson`         | Digest (id, source, field, asOfEpochMillis) of every cited evidence ID. |
 * | `analyzedAt`           | Caller-supplied timestamp; this contract never reads a wall clock.      |
 */
data class RealEstateAnalystPersistenceValues(
    val propertyId: String,
    val summary: String,
    val investmentThesis: String,
    val strengthsJson: String,
    val weaknessesJson: String,
    val risksJson: String,
    val redFlagsJson: String,
    val recommendedStrategy: String,
    val recommendedOfferRange: String,
    val questionsForSellerJson: String,
    val dueDiligenceJson: String,
    val confidence: Double,
    val evidenceJson: String,
    val analyzedAt: Long
)

/**
 * Fail-closed bridge between the model boundary and storage.
 *
 * A [RealEstateAnalystOutput] is re-encoded to its schema form and pushed back through
 * [RealEstateAnalystOutputValidator] before any value is produced, so a DTO constructed by hand -
 * bypassing [RealEstateAnalyst] - can never reach a database column with an unsupported
 * classification, a dangling evidence reference, an unvalidated numeric narrative, or an inflated
 * confidence. Serialization itself is deterministic: keys are fixed, evidence is sorted by ID, and
 * nothing depends on the current time.
 */
object RealEstateAnalystPersistence {
    /** The analyst contract has no weaknesses section; risks already carry that material. */
    const val EMPTY_CLAIM_ARRAY_JSON = "[]"

    /** No offer range is persisted: financial figures belong to the deterministic engine. */
    const val NO_OFFER_RANGE = ""

    /** Longest preview string derived for the `summary` column. */
    const val SUMMARY_MAX_CHARS = 240

    fun toPersistenceValues(
        analysis: RealEstateAnalystOutput,
        evidence: Map<String, AnalystEvidence>,
        propertyId: String,
        analyzedAtEpochMillis: Long,
        validator: RealEstateAnalystOutputValidator = RealEstateAnalystOutputValidator()
    ): RealEstateAnalystPersistenceValues {
        require(propertyId.isNotBlank()) { "An analyst analysis must be persisted against a property ID." }
        require(analyzedAtEpochMillis >= 0L) { "Analysis timestamps cannot be negative." }

        // Re-validation makes persistence itself a contract gate, not just a copy step.
        val validation = validator.validate(analysis.toJsonObject().toString(), evidence)
        if (!validation.isValid) {
            throw IllegalArgumentException(
                "Refusing to persist an analyst analysis that does not satisfy the output contract: " +
                    validation.errors.joinToString(" ")
            )
        }
        val validated = requireNotNull(validation.output)

        return RealEstateAnalystPersistenceValues(
            propertyId = propertyId,
            summary = validated.investmentThesis.summarize(),
            investmentThesis = validated.investmentThesis.statement,
            strengthsJson = validated.strengths.toClaimsJsonArray().toString(),
            weaknessesJson = EMPTY_CLAIM_ARRAY_JSON,
            risksJson = validated.risks.toClaimsJsonArray().toString(),
            redFlagsJson = validated.redFlags.toClaimsJsonArray().toString(),
            recommendedStrategy = validated.recommendedStrategy.statement,
            recommendedOfferRange = NO_OFFER_RANGE,
            questionsForSellerJson = JSONArray(validated.dueDiligenceQuestions.map { it.question }).toString(),
            dueDiligenceJson = validated.dueDiligenceQuestions.toQuestionsJsonArray().toString(),
            confidence = validated.minimumConfidence(),
            evidenceJson = evidenceDigest(validated.allClaims().flatMap { it.evidenceRefs }.distinct().sorted(), evidence).toString(),
            analyzedAt = analyzedAtEpochMillis
        )
    }

    /**
     * Audit digest of the evidence a persisted analysis actually cites. Keeping the source and field
     * alongside the ID means a stored claim stays explainable after the request-scoped input packet
     * is gone, and keeps the app's client/server view of an analysis honest.
     */
    private fun evidenceDigest(
        citedIds: List<String>,
        evidence: Map<String, AnalystEvidence>
    ): JSONArray = JSONArray().apply {
        citedIds.forEach { id ->
            val item = requireNotNull(evidence[id]) { "Cited evidence $id is not part of this analysis request." }
            put(
                JSONObject().apply {
                    put("id", item.id)
                    put("source", item.source.name)
                    put("field", item.field)
                    put("asOfEpochMillis", item.asOfEpochMillis ?: JSONObject.NULL)
                }
            )
        }
    }

}

/** Schema-shaped encoding of a validated analysis; the input to re-validation before persistence. */
fun RealEstateAnalystOutput.toJsonObject(): JSONObject = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("investmentThesis", investmentThesis.toJsonObject())
    put("strengths", strengths.toClaimsJsonArray())
    put("risks", risks.toClaimsJsonArray())
    put("redFlags", redFlags.toClaimsJsonArray())
    put("unknowns", unknowns.toClaimsJsonArray())
    put("recommendedStrategy", recommendedStrategy.toJsonObject())
    put("dueDiligenceQuestions", dueDiligenceQuestions.toQuestionsJsonArray())
}

fun AnalystClaim.toJsonObject(): JSONObject = JSONObject().apply {
    put("classification", classification.name)
    put("statement", statement)
    put("evidenceRefs", JSONArray(evidenceRefs))
    put("confidence", confidence)
}

fun AnalystDueDiligenceQuestion.toJsonObject(): JSONObject = JSONObject().apply {
    put("question", question)
    put("priority", priority.name)
    put("basis", basis.toJsonObject())
}

private fun List<AnalystClaim>.toClaimsJsonArray(): JSONArray {
    val array = JSONArray()
    for (claim in this) array.put(claim.toJsonObject())
    return array
}

private fun List<AnalystDueDiligenceQuestion>.toQuestionsJsonArray(): JSONArray {
    val array = JSONArray()
    for (question in this) array.put(question.toJsonObject())
    return array
}

/**
 * Every claim carried by an analysis, in schema order; used for audit and confidence roll-up.
 * Deliberately separate from the encoded schema so a future schema addition cannot silently drop a
 * claim from the persisted audit trail.
 */
fun RealEstateAnalystOutput.allClaims(): List<AnalystClaim> = buildList {
    add(investmentThesis)
    addAll(strengths)
    addAll(risks)
    addAll(redFlags)
    addAll(unknowns)
    add(recommendedStrategy)
    dueDiligenceQuestions.forEach { add(it.basis) }
}

/**
 * Document-level confidence for storage: the minimum across claims, never an average and never a
 * new number. One low-confidence or UNKNOWN claim therefore keeps the stored row conservative, which
 * is the only safe direction for a value a later surface may render as "confidence".
 */
fun RealEstateAnalystOutput.minimumConfidence(): Double = allClaims().minOf { it.confidence }

/**
 * Deterministic single-line preview for the `summary` column: the first sentence of the
 * already-validated investment thesis, capped and trimmed. It introduces no new content, so a list
 * row can never assert something the analysis itself did not assert.
 */
private fun AnalystClaim.summarize(): String {
    val singleLine = statement.replace(WHITESPACE_RUN, " ").trim()
    val sentenceEnd = singleLine.indexOfFirst { it == '.' || it == '!' || it == '?' }
    val firstSentence = if (sentenceEnd >= 0) singleLine.take(sentenceEnd + 1) else singleLine
    return if (firstSentence.length <= RealEstateAnalystPersistence.SUMMARY_MAX_CHARS) {
        firstSentence
    } else {
        firstSentence.take(RealEstateAnalystPersistence.SUMMARY_MAX_CHARS).trimEnd() + "..."
    }
}

private val WHITESPACE_RUN = Regex("\\s+")
