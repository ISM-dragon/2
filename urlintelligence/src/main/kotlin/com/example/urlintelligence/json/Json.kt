package com.example.urlintelligence.json

/**
 * Minimal, dependency-free JSON reader.
 *
 * Why not Moshi/org.json: this module must stay platform-agnostic and free of
 * Android framework classes (org.json exists on Android but pulls an extra jar on
 * the JVM, and Moshi would add codegen to a layer that only needs read access to
 * embedded JSON-LD blobs). The parser is deliberately lenient: a malformed blob
 * degrades to [parseOrNull] == null and the adapter falls back to heuristics.
 */
sealed class JsonValue {

    object JsonNull : JsonValue() {
        override fun toString(): String = "null"
    }

    data class JsonBoolean(val value: Boolean) : JsonValue()

    data class JsonNumber(val raw: String) : JsonValue() {
        fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()
        fun toIntOrNull(): Int? {
            val d = raw.toDoubleOrNull() ?: return null
            if (d.isNaN() || d.isInfinite()) return null
            return d.toInt()
        }

        fun toLongOrNull(): Long? {
            val d = raw.toDoubleOrNull() ?: return null
            if (d.isNaN() || d.isInfinite()) return null
            return d.toLong()
        }
    }

    data class JsonString(val value: String) : JsonValue()

    data class JsonArray(val items: List<JsonValue>) : JsonValue() {
        fun objects(): List<JsonObject> = items.filterIsInstance<JsonObject>()
        fun strings(): List<String> = items.filterIsInstance<JsonString>().map { it.value }
    }

    data class JsonObject(val members: Map<String, JsonValue>) : JsonValue() {

        operator fun get(key: String): JsonValue? = members[key]

        fun has(key: String): Boolean = members.containsKey(key)

        fun string(key: String): String? = (members[key] as? JsonString)?.value

        fun obj(key: String): JsonObject? = members[key] as? JsonObject

        fun array(key: String): JsonArray? = members[key] as? JsonArray

        fun double(key: String): Double? = (members[key] as? JsonNumber)?.toDoubleOrNull()

        fun int(key: String): Int? = (members[key] as? JsonNumber)?.toIntOrNull()

        fun bool(key: String): Boolean? = (members[key] as? JsonBoolean)?.value

        /** Number or number-as-string — very common in real estate JSON blobs. */
        fun numericString(key: String): String? {
            val value = members[key] ?: return null
            return when (value) {
                is JsonString -> value.value
                is JsonNumber -> value.raw
                else -> null
            }
        }

        fun doubleOrNumericString(key: String): Double? {
            val value = members[key] ?: return null
            return when (value) {
                is JsonNumber -> value.toDoubleOrNull()
                is JsonString -> value.value.toDoubleOrNull()
                else -> null
            }
        }

        /** `@type` may be a string or an array of strings. */
        fun types(): List<String> {
            val value = members["@type"] ?: return emptyList()
            return when (value) {
                is JsonString -> listOf(value.value)
                is JsonArray -> value.strings()
                else -> emptyList()
            }
        }

        fun firstString(vararg keys: String): String? {
            keys.forEach { key ->
                string(key)?.let { return it }
            }
            return null
        }

        fun firstDouble(vararg keys: String): Double? {
            keys.forEach { key ->
                doubleOrNumericString(key)?.let { return it }
            }
            return null
        }

        override fun toString(): String = "JsonObject(${members.keys})"
    }
}

class JsonException(message: String, val offset: Int = -1) :
    RuntimeException(if (offset >= 0) "$message (offset=$offset)" else message)

object JsonParser {

    fun parse(input: String): JsonValue {
        val parser = Reader(input)
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Unexpected trailing content", parser.pos)
        return value
    }

    fun parseOrNull(input: String): JsonValue? =
        try {
            parse(input)
        } catch (t: JsonException) {
            null
        } catch (t: RuntimeException) {
            null
        }

    private class Reader(private val src: String) {
        var pos: Int = 0

        fun atEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length && src[pos].isJsonWhitespace()) pos++
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw JsonException("Unexpected end of input", pos)
            return when (val c = src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.JsonString(readString())
                't' -> { expect("true"); JsonValue.JsonBoolean(true) }
                'f' -> { expect("false"); JsonValue.JsonBoolean(false) }
                'n' -> { expect("null"); JsonValue.JsonNull }
                else -> readNumber()
            }
        }

        private fun readObject(): JsonValue.JsonObject {
            pos++ // consume '{'
            val members = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && src[pos] == '}') {
                pos++
                return JsonValue.JsonObject(members)
            }
            while (true) {
                skipWhitespace()
                if (atEnd()) throw JsonException("Unterminated object", pos)
                if (src[pos] != '"') throw JsonException("Expected object key", pos)
                val key = readString()
                skipWhitespace()
                if (atEnd() || src[pos] != ':') throw JsonException("Expected ':'", pos)
                pos++
                val value = readValue()
                members[key] = value
                skipWhitespace()
                if (atEnd()) throw JsonException("Unterminated object", pos)
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.JsonObject(members)
                    }
                    else -> throw JsonException("Expected ',' or '}'", pos)
                }
            }
        }

        private fun readArray(): JsonValue.JsonArray {
            pos++ // consume '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd() && src[pos] == ']') {
                pos++
                return JsonValue.JsonArray(items)
            }
            while (true) {
                items.add(readValue())
                skipWhitespace()
                if (atEnd()) throw JsonException("Unterminated array", pos)
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.JsonArray(items)
                    }
                    else -> throw JsonException("Expected ',' or ']'", pos)
                }
            }
        }

        private fun readString(): String {
            pos++ // consume opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string", pos)
                val c = src[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) throw JsonException("Unterminated escape", pos)
                        val esc = src[pos++]
                        when (esc) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw JsonException("Bad unicode escape", pos)
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonException("Bad unicode escape '$hex'", pos)
                                pos += 4
                                out.append(code.toChar())
                            }
                            else -> out.append(esc)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): JsonValue {
            val start = pos
            if (!atEnd() && (src[pos] == '-' || src[pos] == '+')) pos++
            var sawDigit = false
            while (pos < src.length) {
                val c = src[pos]
                if (c in '0'..'9') {
                    sawDigit = true
                    pos++
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++
                } else {
                    break
                }
            }
            if (!sawDigit) throw JsonException("Unexpected token '${src.getOrNull(start)}'", start)
            return JsonValue.JsonNumber(src.substring(start, pos))
        }

        private fun expect(literal: String) {
            if (!src.startsWith(literal, pos)) throw JsonException("Expected '$literal'", pos)
            pos += literal.length
        }
    }
}

private fun Char.isJsonWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'

/** Depth-first collection of every object in the tree. */
fun JsonValue.collectObjects(): List<JsonValue.JsonObject> {
    val out = ArrayList<JsonValue.JsonObject>()
    collect(this, out)
    return out
}

private fun collect(value: JsonValue, out: MutableList<JsonValue.JsonObject>) {
    when (value) {
        is JsonValue.JsonObject -> {
            out.add(value)
            value.members.values.forEach { collect(it, out) }
        }
        is JsonValue.JsonArray -> value.items.forEach { collect(it, out) }
        else -> Unit
    }
}

fun JsonValue.findObject(predicate: (JsonValue.JsonObject) -> Boolean): JsonValue.JsonObject? =
    collectObjects().firstOrNull(predicate)

/** First value found for [key] anywhere in the tree (breadth-ish, deterministic order). */
fun JsonValue.deepString(key: String): String? =
    collectObjects().firstNotNullOfOrNullOrNull { it.string(key) }

fun JsonValue.deepDouble(key: String): Double? =
    collectObjects().firstNotNullOfOrNullOrNull { it.doubleOrNumericString(key) }

private inline fun <T : Any> List<JsonValue.JsonObject>.firstNotNullOfOrNullOrNull(
    transform: (JsonValue.JsonObject) -> T?
): T? {
    for (item in this) {
        transform(item)?.let { return it }
    }
    return null
}
