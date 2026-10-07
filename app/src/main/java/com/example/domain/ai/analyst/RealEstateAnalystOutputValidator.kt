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
        // Bound parsing work before touching hostile or runaway model output.
        if (rawText.length > MAX_RESPONSE_CHARS) {
            return AnalystOutputValidation(
                errors = listOf("\$: response exceeds the maximum allowed length of $MAX_RESPONSE_CHARS characters.")
            )
        }

        val parsed = parseResponseObject(rawText)
            ?: return AnalystOutputValidation(errors = listOf("\$ is not a single valid JSON object."))

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

    /**
     * Strict parse pipeline. The whole response must parse as a single strict JSON object, possibly
     * wrapped in one outer code fence. As a controlled malformed-output recovery, a JSON object
     * embedded in surrounding prose is salvaged, but only when it is the unique balanced object in
     * the response; extra objects or braces make the model's intent ambiguous and fail closed.
     * Salvaged candidates pass the identical strict parse, schema, and semantic gates.
     */
    private fun parseResponseObject(rawText: String): JSONObject? {
        val text = unwrapOuterCodeFence(rawText)
        parseStrictObjectOrNull(text)?.let { return it }
        // A valid top-level non-object JSON value (especially an array) must not be unwrapped by
        // the prose-recovery path. Only human-readable surrounding prose may use that recovery.
        val first = text.trimStart().firstOrNull()
        if (first == '[' || first == '"' || text.trim() in setOf("true", "false", "null")) return null
        return salvageEmbeddedObject(text)
    }

    private fun salvageEmbeddedObject(text: String): JSONObject? {
        // Only inspect the first brace candidate. If an outer/malformed object fails to parse, do
        // not continue into it and accidentally accept a nested object with ambiguous provenance.
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = findBalancedObjectEnd(text, start) ?: return null
        val prefix = text.substring(0, start).trimEnd()
        val suffix = text.substring(end + 1).trimStart()
        // Do not unwrap a JSON array containing one object as if it were prose-wrapped JSON.
        if (prefix.endsWith("[") || suffix.startsWith("]")) return null
        val candidate = parseStrictObjectOrNull(text.substring(start, end + 1)) ?: return null
        // Accept only when no further object follows: two candidates mean we would be guessing
        // which one the model intended, so let the bounded retry loop correct the response.
        return if (text.indexOf('{', end + 1) < 0) candidate else null
    }

    /** Returns the index of the '}' closing the object opened at [start], ignoring string contents. */
    private fun findBalancedObjectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                inString && ch == '\\' -> escaped = true
                inString && ch == '"' -> inString = false
                !inString && ch == '"' -> inString = true
                !inString && ch == '{' -> depth += 1
                !inString && ch == '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun parseStrictObjectOrNull(text: String): JSONObject? = try {
        parseStrictObject(text)
    } catch (_: Exception) {
        null
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
            if (claim.statement.containsNumericClaims()) {
                errors += "$path.statement must not contain numeric figures or number words; financial figures stay in the deterministic engine."
            }
            if (claim.statement.containsNarrativeUnsafeCharacter()) {
                errors += "$path.statement must be plain prose without code fences, control characters, or invisible formatting characters."
            }
            if (AnalystTextSanitizer.containsPromptInjectionPattern(claim.statement)) {
                errors += "$path.statement must not repeat instruction-like or credential-exfiltration text from untrusted input."
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
                    if (claim.confidence >= 1.0) errors += "$path ESTIMATE claims must keep confidence below one; only a FACT restatement of a supplied record may reach full confidence."
                    if (citedEvidence.none { it.source in ESTIMATE_SOURCES }) {
                        errors += "$path ESTIMATE claims must cite a supplied market or rent estimate; the AI may not create new estimates."
                    }
                }
                AnalystClaimType.INFERENCE -> {
                    if (claim.evidenceRefs.isEmpty()) errors += "$path INFERENCE claims require supporting evidence."
                    if (claim.confidence <= 0.0) errors += "$path non-UNKNOWN claims require positive confidence."
                    if (claim.confidence >= 1.0) errors += "$path INFERENCE claims must keep confidence below one; unsupported certainty must be reported as UNKNOWN instead."
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
            if (question.question.containsNumericClaims()) {
                errors += "\$.dueDiligenceQuestions[$index].question must not contain numeric figures or number words."
            }
            if (question.question.containsNarrativeUnsafeCharacter()) {
                errors += "\$.dueDiligenceQuestions[$index].question must be plain prose without code fences, control characters, or invisible formatting characters."
            }
            if (AnalystTextSanitizer.containsPromptInjectionPattern(question.question)) {
                errors += "\$.dueDiligenceQuestions[$index].question must not repeat instruction-like or credential-exfiltration text from untrusted input."
            }
            validateClaim(question.basis, "\$.dueDiligenceQuestions[$index].basis")
        }

        return errors
    }

    /**
     * Detects numeric glyphs from any script, not just ASCII digits: superscripts and fractions
     * (OTHER_NUMBER), Roman numerals (LETTER_NUMBER), and fullwidth or Arabic-Indic digits
     * (DECIMAL_DIGIT_NUMBER). It also rejects spelled-out cardinal and ordinal numbers so an
     * unsupported figure cannot be rephrased as words.
     */
    private fun String.containsNumericClaims(): Boolean {
        if (any { it.category in NUMERIC_CHAR_CATEGORIES }) return true
        val tokens = NUMBER_WORD_DELIMITERS.split(lowercase())
        return tokens.any(NUMBER_WORDS::contains)
    }

    /**
     * Narrative text must stay plain prose. Ordinary whitespace (\n, \t, \r) is tolerated, but
     * control characters, invisible formatting characters (zero-width, bidi overrides), exotic
     * separators, private-use glyphs, lone surrogates, and code-fence backticks are rejected:
     * they are the vehicles for echoing injected instructions or hidden structure into the UI.
     */
    private fun String.containsNarrativeUnsafeCharacter(): Boolean = any { ch ->
        when (ch) {
            '\n', '\t', '\r' -> false
            '`' -> true
            else -> ch.category in NARRATIVE_UNSAFE_CHAR_CATEGORIES
        }
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

        /** Hard cap on response size accepted for parsing; generous for a legitimate packet. */
        const val MAX_RESPONSE_CHARS = 100_000

        val ESTIMATE_SOURCES = setOf(
            AnalystEvidenceSource.MARKET_ESTIMATE,
            AnalystEvidenceSource.RENT_ESTIMATE
        )

        /** Every Unicode numeric category, so non-ASCII numerals cannot smuggle figures in. */
        val NUMERIC_CHAR_CATEGORIES = setOf(
            CharCategory.DECIMAL_DIGIT_NUMBER,
            CharCategory.LETTER_NUMBER,
            CharCategory.OTHER_NUMBER
        )

        val NUMBER_WORD_DELIMITERS = Regex("[^\\p{L}]+")
        val NUMBER_WORDS = setOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
            "eighty", "ninety", "hundred", "thousand", "million", "billion", "trillion", "first",
            "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
            "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth",
            "eighteenth", "nineteenth", "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth",
            "seventieth", "eightieth", "ninetieth", "half", "halves", "quarter", "quarters", "dozen",
            "couple"
        )

        /** Character categories banned from narrative output text (whitespace handled separately). */
        val NARRATIVE_UNSAFE_CHAR_CATEGORIES = setOf(
            CharCategory.CONTROL,
            CharCategory.FORMAT,
            CharCategory.LINE_SEPARATOR,
            CharCategory.PARAGRAPH_SEPARATOR,
            CharCategory.SURROGATE,
            CharCategory.PRIVATE_USE
        )
    }
}
