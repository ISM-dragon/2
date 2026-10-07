package com.example.domain.ai.analyst

/**
 * Neutralizes untrusted free text before it crosses the model boundary.
 *
 * Property records, listing imports, notes, documents, and URL-derived fields are attacker-writable:
 * a hostile listing can embed instruction-like text, code fences, ANSI/control characters, zero-width
 * and bidi-override characters (Trojan Source style), or invisible separators into fields such as
 * city, status, comparable addresses, or market-demand labels. JSON serialization already escapes
 * quotes and structure, but escaping does not stop semantic prompt injection or hidden-glyph tricks.
 *
 * The sanitizer is deliberately lossy for hostile content and conservative for ordinary text:
 * it preserves normal letters, digits, punctuation, and word spacing while dropping framing and
 * invisible characters. Values that reduce to nothing after sanitizing are treated as absent, so a
 * field made entirely of injection payload becomes `null` and the analyst must answer UNKNOWN for
 * it instead of repeating attacker text.
 */
object AnalystTextSanitizer {
    private const val MAX_RAW_TO_SANITIZED_RATIO = 4
    private val WHITESPACE_RUN = Regex("\\s+")
    private val URL_PATTERN = Regex("(?i)\\b(?:https?|ftp|file|data|javascript):[^\\s]+|\\bwww\\.[^\\s]+")

    /**
     * Cleans one untrusted text value: drops invisible/formatting and private-use characters,
     * replaces control characters and exotic separators with a single space, removes code-fence
     * and model-token framing characters, collapses whitespace, trims, and caps the length.
     * Returns null when the value is null or nothing meaningful survives sanitization.
     */
    fun sanitize(raw: String?, maxLength: Int): String? {
        require(maxLength > 0) { "Sanitized text length cap must be positive." }
        if (raw == null) return null
        if (raw.length.toLong() > maxLength.toLong() * MAX_RAW_TO_SANITIZED_RATIO) return null
        if (URL_PATTERN.containsMatchIn(raw)) return null
        val stripped = buildString(raw.length) {
            for (ch in raw) {
                when {
                    !ch.isModelBoundarySafe() ->
                        if (ch.category in SEPARATOR_LIKE_CATEGORIES) append(' ') else Unit
                    else -> append(ch)
                }
            }
        }
        val normalized = stripped
            .replace(WHITESPACE_RUN, " ")
            .trim()
        // Run the denylist *after* removing hidden glyphs as well: an attacker must not be able to
        // split "ignore" with zero-width or bidi characters and have it become executable-looking
        // language only after the first scan.
        if (containsPromptInjectionPattern(normalized)) return null
        return normalized
            .take(maxLength)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /** Detects common instruction-override or credential-exfiltration language for output rejection. */
    fun containsPromptInjectionPattern(text: String): Boolean =
        PROMPT_INJECTION_PATTERNS.any { it.containsMatchIn(text) }

    /**
     * True when the text contains no character that could smuggle hidden instructions or invisible
     * structure across the model boundary. Sanitized values always pass; DTO construction rejects
     * values that do not.
     */
    fun isModelBoundarySafe(text: String): Boolean = text.all { it.isModelBoundarySafe() }

    private fun Char.isModelBoundarySafe(): Boolean =
        this !in MODEL_TOKEN_FRAMING_CHARACTERS &&
            (category !in UNSAFE_CHAR_CATEGORIES || this == ' ')

    /** Common instruction override / credential-exfiltration phrases, bounded against backtracking. */
    private val PROMPT_INJECTION_PATTERNS = listOf(
        Regex(
            "(?is)\\b(ignore|disregard|forget|override|bypass|disobey)\\b.{0,80}\\b" +
                "(previous|prior|above|system|developer|all|safety)?\\s*" +
                "(instructions?|prompts?|rules?|schema|safeguards?)\\b"
        ),
        Regex(
            "(?is)\\b(reveal|expose|leak|dump|send|share|print|copy|export|exfiltrate|disclose)\\b" +
                ".{0,64}\\b(api[ -]?keys?|credentials?|secrets?|system prompt|developer message|configuration)\\b"
        ),
        Regex("(?is)\\bpretend\\s+to\\s+be\\b|\\bact\\s+as\\b.{0,40}\\b(system|developer|admin)\\b")
    )

    /** Control characters and all Unicode spacing separators become a space to preserve words. */
    private val SEPARATOR_LIKE_CATEGORIES = setOf(
        CharCategory.CONTROL,
        CharCategory.SPACE_SEPARATOR,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR
    )

    /** ASCII framing characters used by code fences and common model-special-token spellings. */
    private val MODEL_TOKEN_FRAMING_CHARACTERS = setOf('`', '~', '<', '>', '|')

    /**
     * Categories removed outright: Cc control (incl. C1 and ANSI escape payloads), Cf formatting
     * (zero-width joiners/spaces, soft hyphen, bidi overrides, BOM), Unicode spacing/line/paragraph
     * separators, lone surrogates, private-use glyphs, and unassigned code points.
     */
    private val UNSAFE_CHAR_CATEGORIES = setOf(
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.SPACE_SEPARATOR,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
        CharCategory.SURROGATE,
        CharCategory.PRIVATE_USE,
        CharCategory.UNASSIGNED
    )
}
