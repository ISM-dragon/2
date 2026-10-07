package com.example.domain.ai.analyst

import com.squareup.moshi.JsonReader
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * The model boundary has a strict JSON Schema (Draft 2020-12) and a small validator for the exact
 * JSON Schema keywords used by that contract. Semantic checks add provenance rules that JSON Schema
 * cannot express: evidence IDs must be supplied, estimates must cite source estimates, and UNKNOWN
 * claims cannot have evidence or confidence.
 */
object RealEstateAnalystOutputSchema {
    const val VERSION = "1.0"

    val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "${'$'}id": "urn:real-estate-analyst:us:v1",
          "title": "US Real Estate Analyst Output",
          "type": "object",
          "additionalProperties": false,
          "required": [
            "schemaVersion",
            "investmentThesis",
            "strengths",
            "risks",
            "redFlags",
            "unknowns",
            "recommendedStrategy",
            "dueDiligenceQuestions"
          ],
          "properties": {
            "schemaVersion": { "type": "string", "const": "1.0" },
            "investmentThesis": { "${'$'}ref": "#/${'$'}defs/claim" },
            "strengths": {
              "type": "array", "maxItems": 8,
              "items": { "${'$'}ref": "#/${'$'}defs/claim" }
            },
            "risks": {
              "type": "array", "maxItems": 10,
              "items": { "${'$'}ref": "#/${'$'}defs/claim" }
            },
            "redFlags": {
              "type": "array", "maxItems": 10,
              "items": { "${'$'}ref": "#/${'$'}defs/claim" }
            },
            "unknowns": {
              "type": "array", "maxItems": 12,
              "items": { "${'$'}ref": "#/${'$'}defs/claim" }
            },
            "recommendedStrategy": { "${'$'}ref": "#/${'$'}defs/claim" },
            "dueDiligenceQuestions": {
              "type": "array", "minItems": 1, "maxItems": 10,
              "items": { "${'$'}ref": "#/${'$'}defs/dueDiligenceQuestion" }
            }
          },
          "${'$'}defs": {
            "claim": {
              "type": "object",
              "additionalProperties": false,
              "required": ["classification", "statement", "evidenceRefs", "confidence"],
              "properties": {
                "classification": {
                  "type": "string",
                  "enum": ["FACT", "ESTIMATE", "INFERENCE", "UNKNOWN"]
                },
                "statement": { "type": "string", "minLength": 1, "maxLength": 800 },
                "evidenceRefs": {
                  "type": "array", "maxItems": 8, "uniqueItems": true,
                  "items": { "type": "string", "minLength": 1, "maxLength": 120 }
                },
                "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
              }
            },
            "dueDiligenceQuestion": {
              "type": "object",
              "additionalProperties": false,
              "required": ["question", "priority", "basis"],
              "properties": {
                "question": { "type": "string", "minLength": 1, "maxLength": 500 },
                "priority": { "type": "string", "enum": ["HIGH", "MEDIUM", "LOW"] },
                "basis": { "${'$'}ref": "#/${'$'}defs/claim" }
              }
            }
          }
        }
    """.trimIndent()
}

data class AnalystOutputValidation(
    val output: RealEstateAnalystOutput? = null,
    val errors: List<String> = emptyList()
) {
    val isValid: Boolean get() = output != null && errors.isEmpty()
}

class RealEstateAnalystOutputValidator {
    private val schema: JSONObject by lazy { JSONObject(RealEstateAnalystOutputSchema.JSON_SCHEMA) }

    fun validate(
        rawText: String,
        allowedEvidence: Map<String, AnalystEvidence>
    ): AnalystOutputValidation {
        val parsed = try {
            parseStrictObject(unwrapOuterCodeFence(rawText))
        } catch (_: Exception) {
            return AnalystOutputValidation(errors = listOf("\$ is not a single valid JSON object."))
        }

        val schemaErrors = mutableListOf<String>()
        validateAgainstSchema(parsed, schema, "$", schema, schemaErrors)
        if (schemaErrors.isNotEmpty()) {
            return AnalystOutputValidation(errors = schemaErrors.take(MAX_ERRORS))
        }

        val output = try {
            parseOutput(parsed)
        } catch (_: Exception) {
            return AnalystOutputValidation(errors = listOf("\$ could not be decoded into the analyst output model."))
        }

        val semanticErrors = validateSemantics(output, allowedEvidence)
        return if (semanticErrors.isEmpty()) {
            AnalystOutputValidation(output = output)
        } else {
            AnalystOutputValidation(errors = semanticErrors.take(MAX_ERRORS))
        }
    }

    private fun parseStrictObject(text: String): JSONObject {
        val reader = JsonReader.of(Buffer().writeUtf8(text))
        reader.isLenient = false
        val parsed = reader.readJsonValue()
        if (reader.peek() != JsonReader.Token.END_DOCUMENT) {
            throw IllegalArgumentException("Trailing content after JSON value")
        }
        val compatible = parsed.toJsonCompatible()
        return compatible as? JSONObject ?: throw IllegalArgumentException("Expected a JSON object")
    }

    private fun Any?.toJsonCompatible(): Any? = when (this) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            for ((key, value) in this@toJsonCompatible) {
                if (key !is String) throw IllegalArgumentException("JSON object keys must be strings")
                put(key, value.toJsonCompatible())
            }
        }
        is List<*> -> JSONArray().apply {
            this@toJsonCompatible.forEach { put(it.toJsonCompatible()) }
        }
        else -> this
    }

    private fun unwrapOuterCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0) return trimmed
        return trimmed.substring(firstLineEnd + 1, trimmed.length - 3).trim()
    }

    private fun validateAgainstSchema(
        value: Any?,
        valueSchema: JSONObject,
        path: String,
        rootSchema: JSONObject,
        errors: MutableList<String>
    ) {
        if (errors.size >= MAX_ERRORS) return

        val reference = valueSchema.optString("${'$'}ref", "")
        if (reference.isNotEmpty()) {
            val referencedSchema = resolveReference(reference, rootSchema)
            if (referencedSchema == null) {
                errors += "$path has an invalid schema reference."
                return
            }
            validateAgainstSchema(value, referencedSchema, path, rootSchema, errors)
            return
        }

        val expectedType = valueSchema.optString("type", "")
        if (expectedType.isNotEmpty() && !matchesType(value, expectedType)) {
            errors += "$path must be ${expectedType.toJsonTypeDescription()}."
            return
        }

        if (valueSchema.has("const")) {
            val expected = valueSchema.opt("const")
            if (!jsonValuesEqual(value, expected)) errors += "$path must equal $expected."
        }

        val enum = valueSchema.optJSONArray("enum")
        if (enum != null && (0 until enum.length()).none { jsonValuesEqual(value, enum.opt(it)) }) {
            errors += "$path must be one of the allowed enum values."
        }

        when (value) {
            is JSONObject -> validateObject(value, valueSchema, path, rootSchema, errors)
            is JSONArray -> validateArray(value, valueSchema, path, rootSchema, errors)
            is String -> validateString(value, valueSchema, path, errors)
            is Number -> validateNumber(value, valueSchema, path, errors)
        }
    }

    private fun validateObject(
        value: JSONObject,
        valueSchema: JSONObject,
        path: String,
        rootSchema: JSONObject,
        errors: MutableList<String>
    ) {
        val required = valueSchema.optJSONArray("required")
        if (required != null) {
            for (index in 0 until required.length()) {
                val key = required.optString(index)
                if (!value.has(key)) errors += "$path.$key is required."
            }
        }

        val properties = valueSchema.optJSONObject("properties") ?: JSONObject()
        val additional = valueSchema.opt("additionalProperties")
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!properties.has(key)) {
                when (additional) {
                    is JSONObject -> validateAgainstSchema(value.opt(key), additional, "$path.$key", rootSchema, errors)
                    false -> errors += "$path.$key is not allowed."
                }
            } else {
                validateAgainstSchema(value.opt(key), properties.getJSONObject(key), "$path.$key", rootSchema, errors)
            }
            if (errors.size >= MAX_ERRORS) return
        }
    }

    private fun validateArray(
        value: JSONArray,
        valueSchema: JSONObject,
        path: String,
        rootSchema: JSONObject,
        errors: MutableList<String>
    ) {
        val minItems = valueSchema.optInt("minItems", Int.MIN_VALUE)
        val maxItems = valueSchema.optInt("maxItems", Int.MAX_VALUE)
        if (value.length() < minItems) errors += "$path must contain at least $minItems item(s)."
        if (value.length() > maxItems) errors += "$path must contain no more than $maxItems item(s)."

        if (valueSchema.optBoolean("uniqueItems", false)) {
            val serialized = (0 until value.length()).map { value.opt(it).toString() }
            if (serialized.distinct().size != serialized.size) errors += "$path must not contain duplicate items."
        }

        val itemSchema = valueSchema.optJSONObject("items")
        if (itemSchema != null) {
            for (index in 0 until value.length()) {
                validateAgainstSchema(value.opt(index), itemSchema, "$path[$index]", rootSchema, errors)
                if (errors.size >= MAX_ERRORS) return
            }
        }
    }

    private fun validateString(value: String, valueSchema: JSONObject, path: String, errors: MutableList<String>) {
        val minLength = valueSchema.optInt("minLength", Int.MIN_VALUE)
        val maxLength = valueSchema.optInt("maxLength", Int.MAX_VALUE)
        if (value.length < minLength) errors += "$path must not be empty."
        if (value.length > maxLength) errors += "$path is too long."
    }

    private fun validateNumber(value: Number, valueSchema: JSONObject, path: String, errors: MutableList<String>) {
        val number = value.toDouble()
        if (!number.isFinite()) {
            errors += "$path must be a finite number."
            return
        }
        val minimum = valueSchema.optDouble("minimum", Double.NEGATIVE_INFINITY)
        val maximum = valueSchema.optDouble("maximum", Double.POSITIVE_INFINITY)
        if (number < minimum) errors += "$path must be at least $minimum."
        if (number > maximum) errors += "$path must be no more than $maximum."
    }

    private fun validateSemantics(
        output: RealEstateAnalystOutput,
        allowedEvidence: Map<String, AnalystEvidence>
    ): List<String> {
        val errors = mutableListOf<String>()

        fun validateClaim(claim: AnalystClaim, path: String) {
            if (claim.statement.isBlank()) errors += "$path.statement must not be blank."
            if (claim.statement.any(Char::isDigit)) {
                errors += "$path.statement must not contain numeric figures; financial figures stay in the deterministic engine."
            }

            val unknownRefs = claim.evidenceRefs.filterNot(allowedEvidence::containsKey)
            if (unknownRefs.isNotEmpty()) errors += "$path.evidenceRefs contains evidence not present in this request."

            val citedEvidence = claim.evidenceRefs.mapNotNull(allowedEvidence::get)
            when (claim.classification) {
                AnalystClaimType.UNKNOWN -> {
                    if (claim.evidenceRefs.isNotEmpty()) errors += "$path UNKNOWN claims must not cite evidence."
                    if (claim.confidence != 0.0) errors += "$path UNKNOWN claims must have zero confidence."
                }
                AnalystClaimType.FACT -> {
                    if (claim.evidenceRefs.isEmpty()) errors += "$path FACT claims require evidence."
                    if (claim.confidence <= 0.0) errors += "$path non-UNKNOWN claims require positive confidence."
                    if (citedEvidence.any { it.source in ESTIMATE_SOURCES }) {
                        errors += "$path must be ESTIMATE when it relies on a supplied estimate."
                    }
                }
                AnalystClaimType.ESTIMATE -> {
                    if (claim.evidenceRefs.isEmpty()) errors += "$path ESTIMATE claims require source evidence."
                    if (claim.confidence <= 0.0) errors += "$path non-UNKNOWN claims require positive confidence."
                    if (citedEvidence.none { it.source in ESTIMATE_SOURCES }) {
                        errors += "$path ESTIMATE claims must cite a supplied market or rent estimate; the AI may not create new estimates."
                    }
                }
                AnalystClaimType.INFERENCE -> {
                    if (claim.evidenceRefs.isEmpty()) errors += "$path INFERENCE claims require supporting evidence."
                    if (claim.confidence <= 0.0) errors += "$path non-UNKNOWN claims require positive confidence."
                }
            }
        }

        validateClaim(output.investmentThesis, "\$.investmentThesis")
        output.strengths.forEachIndexed { index, claim ->
            validateClaim(claim, "\$.strengths[$index]")
            if (claim.classification == AnalystClaimType.UNKNOWN) {
                errors += "\$.strengths[$index] cannot be UNKNOWN; omit unsupported strengths."
            }
        }
        output.risks.forEachIndexed { index, claim ->
            validateClaim(claim, "\$.risks[$index]")
            if (claim.classification == AnalystClaimType.UNKNOWN) {
                errors += "\$.risks[$index] cannot be UNKNOWN; put missing information in unknowns or due diligence."
            }
        }
        output.redFlags.forEachIndexed { index, claim ->
            validateClaim(claim, "\$.redFlags[$index]")
            if (claim.classification == AnalystClaimType.UNKNOWN) {
                errors += "\$.redFlags[$index] cannot be UNKNOWN; omit red flags that are not supported by evidence."
            }
        }
        output.unknowns.forEachIndexed { index, claim ->
            validateClaim(claim, "\$.unknowns[$index]")
            if (claim.classification != AnalystClaimType.UNKNOWN) {
                errors += "\$.unknowns[$index] must use UNKNOWN classification."
            }
        }
        validateClaim(output.recommendedStrategy, "\$.recommendedStrategy")
        output.dueDiligenceQuestions.forEachIndexed { index, question ->
            if (question.question.isBlank()) {
                errors += "\$.dueDiligenceQuestions[$index].question must not be blank."
            }
            if (question.question.any(Char::isDigit)) {
                errors += "\$.dueDiligenceQuestions[$index].question must not contain numeric figures."
            }
            validateClaim(question.basis, "\$.dueDiligenceQuestions[$index].basis")
        }

        return errors
    }

    private fun parseOutput(root: JSONObject): RealEstateAnalystOutput {
        fun parseClaim(json: JSONObject): AnalystClaim {
            val refsJson = json.getJSONArray("evidenceRefs")
            val refs = (0 until refsJson.length()).map { refsJson.getString(it) }
            return AnalystClaim(
                classification = AnalystClaimType.valueOf(json.getString("classification")),
                statement = json.getString("statement"),
                evidenceRefs = refs,
                confidence = json.getDouble("confidence")
            )
        }

        fun parseClaims(name: String): List<AnalystClaim> {
            val array = root.getJSONArray(name)
            return (0 until array.length()).map { parseClaim(array.getJSONObject(it)) }
        }

        val questionsJson = root.getJSONArray("dueDiligenceQuestions")
        val questions = (0 until questionsJson.length()).map { index ->
            val item = questionsJson.getJSONObject(index)
            AnalystDueDiligenceQuestion(
                question = item.getString("question"),
                priority = DueDiligencePriority.valueOf(item.getString("priority")),
                basis = parseClaim(item.getJSONObject("basis"))
            )
        }

        return RealEstateAnalystOutput(
            schemaVersion = root.getString("schemaVersion"),
            investmentThesis = parseClaim(root.getJSONObject("investmentThesis")),
            strengths = parseClaims("strengths"),
            risks = parseClaims("risks"),
            redFlags = parseClaims("redFlags"),
            unknowns = parseClaims("unknowns"),
            recommendedStrategy = parseClaim(root.getJSONObject("recommendedStrategy")),
            dueDiligenceQuestions = questions
        )
    }

    private fun resolveReference(reference: String, root: JSONObject): JSONObject? {
        if (!reference.startsWith("#/")) return null
        var current: Any = root
        val segments = reference.removePrefix("#/").split('/')
        for (rawSegment in segments) {
            val segment = rawSegment.replace("~1", "/").replace("~0", "~")
            current = (current as? JSONObject)?.opt(segment) ?: return null
        }
        return current as? JSONObject
    }

    private fun matchesType(value: Any?, type: String): Boolean = when (type) {
        "object" -> value is JSONObject
        "array" -> value is JSONArray
        "string" -> value is String
        "number" -> value is Number && value.toDouble().isFinite()
        "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
        "boolean" -> value is Boolean
        "null" -> value == null || value === JSONObject.NULL
        else -> false
    }

    private fun jsonValuesEqual(left: Any?, right: Any?): Boolean {
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        return left == right || (left === JSONObject.NULL && right == null) || (right === JSONObject.NULL && left == null)
    }

    private fun String.toJsonTypeDescription(): String = when (this) {
        "object" -> "a JSON object"
        "array" -> "a JSON array"
        "string" -> "a string"
        "number" -> "a number"
        "integer" -> "an integer"
        "boolean" -> "a boolean"
        "null" -> "null"
        else -> this
    }

    private companion object {
        const val MAX_ERRORS = 30
        val ESTIMATE_SOURCES = setOf(
            AnalystEvidenceSource.MARKET_ESTIMATE,
            AnalystEvidenceSource.RENT_ESTIMATE
        )
    }
}
