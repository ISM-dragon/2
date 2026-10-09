package com.example.domain.outreach

/**
 * Interface for AI-generated text.
 *
 * Implementations may return prose, or JSON whose only fields are text. They must not determine
 * price or financial terms. The request type has no price, offer, rent, or loan fields. Returning
 * null, empty text, or disallowed keys forces the deterministic template fallback.
 */
fun interface SellerOutreachTextGenerator {
    suspend fun generate(prompt: OutreachTextPrompt): String?
}

data class OutreachTextPrompt(
    val channel: OutreachChannel,
    val purpose: OutreachPurpose,
    val tone: OutreachTone,
    val sellerDisplayName: String?,
    val senderName: String,
    val senderCompany: String?,
    val propertyAddress: String?,
    val propertyCity: String?,
    val propertyState: String?,
    val allowedFacts: List<String>,
    val sellerStatedNote: String?,
    val maxBodyCharacters: Int,
    val policyStatement: String = OutreachPolicy.TEXT_ONLY_CONSTRAINT
) {
    init {
        require(maxBodyCharacters > 0) { "AI body cap must be positive." }
        require(policyStatement == OutreachPolicy.TEXT_ONLY_CONSTRAINT) {
            "AI policy text is fixed and cannot be overridden by the caller."
        }
    }
}

data class AiGeneratedText(
    val subject: String?,
    val body: String,
    val callScript: String?
)

sealed class AiParseResult {
    data class Accepted(val text: AiGeneratedText) : AiParseResult()
    data class Rejected(val reason: AiFallbackReason) : AiParseResult()
}

object OutreachPromptBuilder {
    const val BEGIN_NOTE = "BEGIN_SUPPLIED_NOTE"
    const val END_NOTE = "END_SUPPLIED_NOTE"

    fun build(prompt: OutreachTextPrompt): String = buildString {
        appendLine(prompt.policyStatement)
        appendLine("Write one ${prompt.channel.wireName} message for purpose ${prompt.purpose.wireName} in a ${prompt.tone.wireName} tone.")
        appendLine("Use only the supplied data below. Missing data is unknown. Do not fill gaps.")
        appendLine("Do not determine, suggest, calculate, or mention price or financial terms.")
        appendLine("Do not invent property facts, condition, renovations, or a street address that was not supplied.")
        if (prompt.sellerStatedNote == null) {
            appendLine("Seller motivation is unknown. Do not fabricate seller motivation or personal circumstances.")
        } else {
            appendLine("Do not add seller motivation beyond the supplied note. Quote it only if needed, and do not paraphrase it into a new claim.")
        }
        appendLine("Return a single JSON object with only these text fields: subject, body, callScript. No other keys.")
        appendLine("subject and callScript may be null. body must be plain text no longer than ${prompt.maxBodyCharacters} characters.")
        appendLine()
        appendLine("sender: ${prompt.senderName}")
        prompt.senderCompany?.let { appendLine("company: $it") }
        prompt.sellerDisplayName?.let { appendLine("seller_name: $it") }
        prompt.propertyAddress?.let { appendLine("address: $it") }
        prompt.propertyCity?.let { appendLine("city: $it") }
        prompt.propertyState?.let { appendLine("state: $it") }
        if (prompt.allowedFacts.isEmpty()) {
            appendLine("allowed_facts: none")
        } else {
            appendLine("allowed_facts:")
            prompt.allowedFacts.forEach { appendLine("- $it") }
        }
        if (prompt.sellerStatedNote != null) {
            appendLine(BEGIN_NOTE)
            appendLine(prompt.sellerStatedNote)
            appendLine(END_NOTE)
        }
        appendLine()
        append(prompt.policyStatement)
    }
}

object OutreachAiResponseParser {
    private val allowedKeys = setOf("subject", "body", "callscript")
    private val forbiddenKeys = setOf(
        "price", "offerprice", "offeramount", "earnestmoney", "caprate", "cashflow",
        "dscr", "interestrate", "noi", "arv", "terms", "financialterms", "closingcosts",
        "purchaseprice", "monthlyrent", "downpayment", "loanamount", "interestratepct",
        "cashoncash", "noi", "wholesalefee", "sellercredit", "sellercredits"
    )
    private val fence = Regex("(?is)^```(?:json)?\\s*([\\s\\S]*?)\\s*```$")
    private val forbiddenKeyInText = Regex(
        "(?i)\"(?:price|offer[_-]?price|offerprice|purchase[_-]?price|earnest[_-]?money|cap[_-]?rate|cash[_-]?flow|financial[_-]?terms)\"\\s*:"
    )

    fun parse(raw: String, channel: OutreachChannel): AiParseResult {
        val stripped = OutreachTextSanitizer.stripInvisible(raw).trim().removePrefix("\uFEFF")
        val unfenced = unwrapFence(stripped)
        if (forbiddenKeyInText.containsMatchIn(unfenced)) {
            return AiParseResult.Rejected(AiFallbackReason.FINANCIAL_TERMS_DETECTED)
        }
        if (!unfenced.startsWith("{")) {
            return acceptPlain(unfenced, channel)
        }
        val value = JsonValues.parse(unfenced) ?: return AiParseResult.Rejected(AiFallbackReason.OUTPUT_REJECTED)
        val objectValue = value as? JsonValues.Obj ?: return AiParseResult.Rejected(AiFallbackReason.OUTPUT_REJECTED)
        val keys = mutableListOf<String>()
        collectKeys(objectValue, keys)
        val normalized = keys.map(::normalizeKey)
        if (normalized.any { it in forbiddenKeys }) {
            return AiParseResult.Rejected(AiFallbackReason.FINANCIAL_TERMS_DETECTED)
        }
        if (objectValue.fields.values.any { it !is JsonValues.Str && it !is JsonValues.NullVal }) {
            return AiParseResult.Rejected(AiFallbackReason.OUTPUT_REJECTED)
        }
        if (normalized.any { it !in allowedKeys }) {
            return AiParseResult.Rejected(AiFallbackReason.OUTPUT_REJECTED)
        }
        val subject = stringField(objectValue, "subject")
        val body = stringField(objectValue, "body").orEmpty()
        val callScript = stringField(objectValue, "callScript")
        if (body.isBlank() && callScript.isNullOrBlank()) {
            return AiParseResult.Rejected(AiFallbackReason.GENERATOR_RETURNED_EMPTY)
        }
        return AiParseResult.Accepted(
            AiGeneratedText(
                subject = subject?.takeIf { it.isNotBlank() },
                body = body,
                callScript = callScript?.takeIf { it.isNotBlank() }
            )
        )
    }

    private fun acceptPlain(text: String, channel: OutreachChannel): AiParseResult {
        if (text.isBlank()) return AiParseResult.Rejected(AiFallbackReason.GENERATOR_RETURNED_EMPTY)
        return AiParseResult.Accepted(
            AiGeneratedText(
                subject = null,
                body = if (channel == OutreachChannel.MANUAL_CALL) "" else text,
                callScript = if (channel == OutreachChannel.MANUAL_CALL) text else null
            )
        )
    }

    private fun unwrapFence(raw: String): String =
        fence.matchEntire(raw)?.groupValues?.getOrNull(1)?.trim() ?: raw

    private fun stringField(obj: JsonValues.Obj, name: String): String? {
        val value = obj.fields.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        return (value as? JsonValues.Str)?.value
    }

    private fun collectKeys(value: JsonValues.Value, out: MutableList<String>) {
        when (value) {
            is JsonValues.Obj -> value.fields.forEach { (key, child) ->
                out += key
                collectKeys(child, out)
            }
            is JsonValues.Arr -> value.values.forEach { collectKeys(it, out) }
            else -> Unit
        }
    }

    private fun normalizeKey(key: String): String = key.lowercase().replace("_", "").replace("-", "")
}

/** Minimal JSON reader for the AI text contract. Numbers and nested objects are preserved so they can be rejected. */
internal object JsonValues {
    sealed interface Value
    data class Str(val value: String) : Value
    data object NullVal : Value
    data class Num(val raw: String) : Value
    data class BoolVal(val value: Boolean) : Value
    data class Obj(val fields: Map<String, Value>) : Value
    data class Arr(val values: List<Value>) : Value

    fun parse(text: String): Value? = try {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWs()
        if (parser.ended()) value else null
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun ended(): Boolean = index >= text.length

        fun skipWs() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseValue(): Value {
            skipWs()
            if (index >= text.length) fail()
            return when (text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> literal("true", BoolVal(true))
                'f' -> literal("false", BoolVal(false))
                'n' -> literal("null", NullVal)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Obj {
            expect('{')
            val fields = linkedMapOf<String, Value>()
            skipWs()
            if (peek('}')) {
                index++
                return Obj(fields)
            }
            while (index < text.length) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                if (fields.containsKey(key)) fail()
                fields[key] = value
                skipWs()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return Obj(fields)
                    }
                    else -> fail()
                }
            }
            fail()
        }

        private fun parseArray(): Arr {
            expect('[')
            val values = mutableListOf<Value>()
            skipWs()
            if (peek(']')) {
                index++
                return Arr(values)
            }
            while (index < text.length) {
                values += parseValue()
                skipWs()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return Arr(values)
                    }
                    else -> fail()
                }
            }
            fail()
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) fail()
                        when (val esc = text[index++]) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail()
                                val hex = text.substring(index, index + 4)
                                index += 4
                                val code = hex.toIntOrNull(16) ?: fail()
                                out.append(code.toChar())
                            }
                            else -> fail()
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) fail()
                        out.append(ch)
                    }
                }
            }
            fail()
        }

        private fun parseNumber(): Num {
            val start = index
            if (peek('-')) index++
            if (index >= text.length || !text[index].isDigit()) fail()
            if (text[index] == '0') {
                index++
            } else {
                while (index < text.length && text[index].isDigit()) index++
            }
            if (peek('.')) {
                index++
                if (index >= text.length || !text[index].isDigit()) fail()
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                if (index >= text.length || !text[index].isDigit()) fail()
                while (index < text.length && text[index].isDigit()) index++
            }
            return Num(text.substring(start, index))
        }

        private fun literal(token: String, value: Value): Value {
            if (!text.startsWith(token, index)) fail()
            index += token.length
            return value
        }

        private fun expect(ch: Char) {
            if (index >= text.length || text[index] != ch) fail()
            index++
        }

        private fun peek(ch: Char): Boolean = index < text.length && text[index] == ch

        private fun fail(): Nothing = throw IllegalArgumentException("invalid json")
    }
}
