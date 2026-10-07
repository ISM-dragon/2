package com.example.domain.propertyurl.json

/**
 * Minimal, dependency-free JSON model + parser used by the Property URL Intelligence layer.
 *
 * Why not Moshi/org.json?
 *  - The layer must stay independent from platform APIs (android.util / org.json are only present on
 *    Android or in unit-test runtimes), so it can be reused server side and unit tested on a plain JVM.
 *  - Listing pages embed multi-megabyte JSON state trees; this parser is allocation-conscious,
 *    depth-limited and never throws on hostile input (it returns a typed failure instead).
 *
 * The parser accepts strict RFC 8259 JSON (no comments, no trailing commas) and is intentionally
 * strict because a lenient parser hides "the portal changed its markup" signals that the
 * source-failure classifier relies on.
 */
sealed interface JsonValue {

    object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data class Num(val value: Double, val raw: String) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Obj(val entries: Map<String, JsonValue>) : JsonValue

    val isNull: Boolean get() = this is Null

    /** Object member lookup; returns null for non-objects. */
    fun get(key: String): JsonValue? = (this as? Obj)?.entries?.get(key)

    /** Case-insensitive member lookup (portal payloads are not consistent about casing). */
    fun getIgnoreCase(key: String): JsonValue? {
        val obj = this as? Obj ?: return null
        obj.entries[key]?.let { return it }
        return obj.entries.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    }

    fun asString(): String? = when (this) {
        is Str -> value
        is Num -> raw
        is Bool -> value.toString()
        else -> null
    }

    fun asNumber(): Double? = when (this) {
        is Num -> value
        is Str -> value.trim().toDoubleOrNull()
        else -> null
    }

    fun asInt(): Int? = asNumber()?.let { if (it.isFinite()) it.toInt() else null }

    fun asBool(): Boolean? = (this as? Bool)?.value

    fun asArray(): List<JsonValue> = when (this) {
        is Arr -> items
        is Null -> emptyList()
        else -> listOf(this)
    }

    fun asObject(): Map<String, JsonValue> = (this as? Obj)?.entries ?: emptyMap()

    /**
     * Dot/bracket path lookup: `a.b[0].c`, `a.b[*].c` (wildcard expands arrays/objects).
     * Returns the first match in document order, or null.
     */
    fun path(dotPath: String): JsonValue? = JsonNavigator.lookup(this, dotPath).firstOrNull()?.value

    /** Short, log-safe rendering (never dumps multi-KB subtrees into telemetry). */
    fun describe(maxChars: Int = 120): String {
        val rendered = JsonWriter.write(this)
        return if (rendered.length <= maxChars) rendered else rendered.take(maxChars) + "…"
    }
}

/** Result of [JsonParser.parse]: either a value or a typed, non-throwing failure. */
sealed interface JsonParseResult {
    data class Success(val value: JsonValue) : JsonParseResult
    data class Failure(val message: String, val offset: Int) : JsonParseResult
}

class JsonParseException(message: String, val offset: Int, val inputLength: Int) :
    Exception("$message (offset=$offset, length=$inputLength)")

/**
 * Iterative-safe (depth limited) recursive descent JSON parser.
 *
 * @param maxDepth guards against stack overflow on hostile deeply nested payloads.
 */
class JsonParser(private val maxDepth: Int = DEFAULT_MAX_DEPTH) {

    private var text: String = ""
    private var index: Int = 0

    fun parse(input: String): JsonParseResult {
        text = input
        index = 0
        return try {
            skipWhitespaceAndBom()
            if (index >= text.length) {
                JsonParseResult.Failure("empty input", index)
            } else {
                val value = readValue(0)
                skipWhitespaceAndBom()
                if (index != text.length) {
                    JsonParseResult.Failure("trailing content after JSON value", index)
                } else {
                    JsonParseResult.Success(value)
                }
            }
        } catch (e: JsonParseException) {
            JsonParseResult.Failure(e.message ?: "malformed JSON", e.offset)
        }
    }

    fun parseOrNull(input: String): JsonValue? = (parse(input) as? JsonParseResult.Success)?.value

    /** Parses and throws a [JsonParseException]; convenient inside adapters that want to fail loudly. */
    fun parseStrict(input: String): JsonValue = when (val result = parse(input)) {
        is JsonParseResult.Success -> result.value
        is JsonParseResult.Failure -> throw JsonParseException(result.message, result.offset, input.length)
    }

    private fun readValue(depth: Int): JsonValue {
        if (depth > maxDepth) throw JsonParseException("maximum JSON nesting depth exceeded", index, text.length)
        skipWhitespaceAndBom()
        if (index >= text.length) throw JsonParseException("unexpected end of input", index, text.length)
        return when (val c = text[index]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonValue.Str(readString())
            't', 'f' -> readBoolean()
            'n' -> readNull()
            else -> if (c == '-' || c in '0'..'9') readNumber() else {
                throw JsonParseException("unexpected character '$c'", index, text.length)
            }
        }
    }

    private fun readObject(depth: Int): JsonValue.Obj {
        expect('{')
        val entries = LinkedHashMap<String, JsonValue>()
        skipWhitespaceAndBom()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(entries)
        }
        while (true) {
            skipWhitespaceAndBom()
            if (peek() != '"') throw JsonParseException("object key must be a string", index, text.length)
            val key = readString()
            skipWhitespaceAndBom()
            expect(':')
            val value = readValue(depth + 1)
            entries[key] = value
            skipWhitespaceAndBom()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.Obj(entries)
                }
                else -> throw JsonParseException("expected ',' or '}' in object", index, text.length)
            }
        }
    }

    private fun readArray(depth: Int): JsonValue.Arr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespaceAndBom()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(readValue(depth + 1))
            skipWhitespaceAndBom()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> throw JsonParseException("expected ',' or ']' in array", index, text.length)
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (index >= text.length) throw JsonParseException("unterminated string", index, text.length)
            when (val c = text[index]) {
                '"' -> {
                    index++
                    return sb.toString()
                }
                '\\' -> {
                    index++
                    if (index >= text.length) throw JsonParseException("unterminated escape", index, text.length)
                    when (val escape = text[index]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (index + 4 >= text.length) {
                                throw JsonParseException("truncated unicode escape", index, text.length)
                            }
                            val hex = text.substring(index + 1, index + 5)
                            val code = hex.toIntOrNull(16)
                                ?: throw JsonParseException("invalid unicode escape '\\u$hex'", index, text.length)
                            sb.append(code.toChar())
                            index += 4
                        }
                        else -> throw JsonParseException("invalid escape '\\$escape'", index, text.length)
                    }
                    index++
                }
                else -> {
                    if (c.code < 0x20) throw JsonParseException("unescaped control character in string", index, text.length)
                    sb.append(c)
                    index++
                }
            }
        }
    }

    private fun readNumber(): JsonValue.Num {
        val start = index
        if (peek() == '-') index++
        if (peek() == '0') {
            index++
        } else {
            if (peek() !in '1'..'9') throw JsonParseException("invalid number", index, text.length)
            while (peek() in '0'..'9') index++
        }
        if (peek() == '.') {
            index++
            if (peek() !in '0'..'9') throw JsonParseException("invalid fraction", index, text.length)
            while (peek() in '0'..'9') index++
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            if (peek() !in '0'..'9') throw JsonParseException("invalid exponent", index, text.length)
            while (peek() in '0'..'9') index++
        }
        val raw = text.substring(start, index)
        val parsed = raw.toDoubleOrNull()
            ?: throw JsonParseException("number out of range: $raw", start, text.length)
        return JsonValue.Num(parsed, raw)
    }

    private fun readBoolean(): JsonValue.Bool = when {
        text.startsWith("true", index) -> {
            index += 4
            JsonValue.Bool(true)
        }
        text.startsWith("false", index) -> {
            index += 5
            JsonValue.Bool(false)
        }
        else -> throw JsonParseException("invalid literal", index, text.length)
    }

    private fun readNull(): JsonValue {
        if (!text.startsWith("null", index)) throw JsonParseException("invalid literal", index, text.length)
        index += 4
        return JsonValue.Null
    }

    private fun skipWhitespaceAndBom() {
        while (index < text.length) {
            val c = text[index]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') index++ else break
        }
    }

    private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

    private fun expect(c: Char) {
        if (peek() != c) throw JsonParseException("expected '$c'", index, text.length)
        index++
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 64

        /** Parses `text`, returning null when malformed (callers usually record a warning). */
        fun parseOrNull(text: String, maxDepth: Int = DEFAULT_MAX_DEPTH): JsonValue? =
            JsonParser(maxDepth).parseOrNull(text)
    }
}

object JsonNavigator {

    /** A value together with the concrete path it was found at (used for provenance `rawPath`). */
    data class Match(val path: String, val value: JsonValue)

    /**
     * Resolves a dot/bracket path against [root] and returns every match in document order.
     * Wildcards (`[*]`) and numeric indexes (`[0]`) are supported.
     */
    fun lookup(root: JsonValue, path: String): List<Match> {
        if (path.isBlank()) return listOf(Match("$", root))
        val tokens = tokenize(path)
        var current = listOf(Match("$", root))
        for (token in tokens) {
            val next = ArrayList<Match>()
            for (match in current) {
                when (token) {
                    is Token.Wildcard -> {
                        when (val v = match.value) {
                            is JsonValue.Arr -> v.items.forEachIndexed { i, item ->
                                next.add(Match("${match.path}[$i]", item))
                            }
                            is JsonValue.Obj -> v.entries.forEach { (k, item) ->
                                next.add(Match("${match.path}.$k", item))
                            }
                            else -> Unit
                        }
                    }
                    is Token.Index -> {
                        val items = (match.value as? JsonValue.Arr)?.items ?: continue
                        items.getOrNull(token.value)?.let { next.add(Match("${match.path}[${token.value}]", it)) }
                    }
                    is Token.Key -> {
                        val obj = match.value as? JsonValue.Obj ?: continue
                        val hit = obj.entries[token.value]
                            ?: obj.entries.entries.firstOrNull { it.key.equals(token.value, ignoreCase = true) }?.value
                        if (hit != null) next.add(Match("${match.path}.${token.value}", hit))
                    }
                }
            }
            current = next
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    private sealed interface Token {
        data class Key(val value: String) : Token
        data class Index(val value: Int) : Token
        object Wildcard : Token
    }

    private fun tokenize(path: String): List<Token> {
        val tokens = ArrayList<Token>()
        val sb = StringBuilder()
        var i = 0
        fun flushKey() {
            if (sb.isNotEmpty()) {
                tokens.add(Token.Key(sb.toString()))
                sb.setLength(0)
            }
        }
        while (i < path.length) {
            when (val c = path[i]) {
                '.' -> {
                    flushKey()
                    i++
                }
                '[' -> {
                    flushKey()
                    val close = path.indexOf(']', i)
                    if (close < 0) return tokens
                    val inner = path.substring(i + 1, close).trim()
                    when {
                        inner == "*" -> tokens.add(Token.Wildcard)
                        else -> inner.toIntOrNull()?.let { tokens.add(Token.Index(it)) }
                    }
                    i = close + 1
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        flushKey()
        return tokens
    }
}

object JsonWriter {

    /** Renders a JSON value as compact, deterministic text (object keys keep insertion order). */
    fun write(value: JsonValue): String {
        val sb = StringBuilder()
        writeValue(value, sb)
        return sb.toString()
    }

    fun writePretty(value: JsonValue, indent: String = "  "): String {
        val sb = StringBuilder()
        writePrettyValue(value, sb, indent, 0)
        return sb.toString()
    }

    private fun writeValue(value: JsonValue, sb: StringBuilder) {
        when (value) {
            is JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.Num -> sb.append(normalizeNumber(value))
            is JsonValue.Str -> writeString(value.value, sb)
            is JsonValue.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    writeValue(item, sb)
                }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                sb.append('{')
                var first = true
                value.entries.forEach { (k, v) ->
                    if (!first) sb.append(',')
                    first = false
                    writeString(k, sb)
                    sb.append(':')
                    writeValue(v, sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writePrettyValue(value: JsonValue, sb: StringBuilder, indent: String, depth: Int) {
        when (value) {
            is JsonValue.Arr -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                value.items.forEachIndexed { i, item ->
                    repeat(depth + 1) { sb.append(indent) }
                    writePrettyValue(item, sb, indent, depth + 1)
                    if (i < value.items.size - 1) sb.append(',')
                    sb.append('\n')
                }
                repeat(depth) { sb.append(indent) }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                if (value.entries.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                var first = true
                value.entries.forEach { (k, v) ->
                    if (!first) sb.append(",\n")
                    first = false
                    repeat(depth + 1) { sb.append(indent) }
                    writeString(k, sb)
                    sb.append(": ")
                    writePrettyValue(v, sb, indent, depth + 1)
                }
                sb.append('\n')
                repeat(depth) { sb.append(indent) }
                sb.append('}')
            }
            else -> writeValue(value, sb)
        }
    }

    private fun normalizeNumber(num: JsonValue.Num): String {
        val d = num.value
        if (!d.isFinite()) return "null"
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else num.raw
    }

    private fun writeString(value: String, sb: StringBuilder) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    // --- Convenience builders used by the persistence codec -------------------------------

    fun obj(vararg pairs: Pair<String, JsonValue?>): JsonValue.Obj =
        JsonValue.Obj(pairs.filter { it.second != null }.associate { it.first to it.second!! })

    fun arr(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)

    fun str(value: String?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Str(value)

    fun num(value: Double?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value, value.toString())

    fun int(value: Int?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value.toDouble(), value.toString())

    fun long(value: Long?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value.toDouble(), value.toString())

    fun bool(value: Boolean?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Bool(value)

    fun strArr(items: List<String>): JsonValue = JsonValue.Arr(items.map { JsonValue.Str(it) })
}
