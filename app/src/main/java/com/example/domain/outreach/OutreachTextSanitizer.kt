package com.example.domain.outreach

/**
 * Lossy cleaner for untrusted outreach text.
 *
 * Drops invisible and framing characters, detects credentials, prompt injection, and links, and
 * caps length. A field that is only an injection payload or a secret becomes absent. Credential
 * material is detected and never echoed back in an error string.
 */
object OutreachTextSanitizer {
    private const val MAX_RAW_TO_CLEAN_RATIO = 8

    private val whitespaceRun = Regex("[ \\t\\f\\u000B]+")
    private val anyWhitespaceRun = Regex("\\s+")
    private val newlineRun = Regex("\\n{3,}")
    private val urlPattern = Regex("(?i)\\b(?:https?|ftp|file|data|javascript|mailto):\\S+|\\bwww\\.\\S+")

    private val credentialPatterns = listOf(
        Regex("(?i)\\b(?:api[_-]?key|apikey|secret|password|passwd|token|bearer|authorization|client[_-]?secret|refresh[_-]?token|access[_-]?token)\\b\\s*[:=]\\s*\\S{4,}"),
        Regex("\\bAIza[0-9A-Za-z\\-_]{20,}\\b"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\bya29\\.[0-9A-Za-z\\-_]+"),
        Regex("(?i)\\b(?:sk|pk)_(?:live|test)_[A-Za-z0-9]{8,}\\b"),
        Regex("(?i)\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
        Regex("(?i)\\bghp_[A-Za-z0-9]{20,}\\b"),
        Regex("(?i)\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("\\beyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}"),
        Regex("(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/]{8,}={0,2}")
    )

    private val injectionPatterns = listOf(
        Regex(
            "(?is)\\b(?:ignore|disregard|forget|override|bypass|disobey)\\b.{0,80}\\b" +
                "(?:previous|prior|above|system|developer|all|safety)?\\s*" +
                "(?:instructions?|prompts?|rules?|schema|safeguards?)\\b"
        ),
        Regex(
            "(?is)\\b(?:reveal|expose|leak|dump|print|copy|export|exfiltrate|disclose)\\b.{0,64}\\b" +
                "(?:api[_ -]?keys?|credentials?|secrets?|system prompt|developer message|configuration)\\b"
        ),
        Regex("(?is)\\bpretend\\s+to\\s+be\\b|\\bact\\s+as\\b.{0,40}\\b(?:system|developer|admin)\\b"),
        Regex("(?is)\\bbegin[_ ]untrusted\\b|\\bend[_ ]untrusted\\b|\\bbegin_supplied_note\\b|\\bend_supplied_note\\b")
    )

    private val framingCharacters = setOf('`', '~', '<', '>', '|')

    private val separatorLike = setOf(
        CharCategory.CONTROL,
        CharCategory.SPACE_SEPARATOR,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR
    )

    private val unsafeCategories = setOf(
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.SPACE_SEPARATOR,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
        CharCategory.SURROGATE,
        CharCategory.PRIVATE_USE,
        CharCategory.UNASSIGNED
    )

    data class Cleaned(
        val value: String?,
        val credentialsDetected: Boolean,
        val injectionDetected: Boolean,
        val linkDetected: Boolean
    ) {
        val rejected: Boolean get() = credentialsDetected || injectionDetected || linkDetected
    }

    fun clean(raw: String?, maxLength: Int, multiline: Boolean): Cleaned {
        require(maxLength > 0) { "Sanitized text length cap must be positive." }
        if (raw == null) return Cleaned(null, false, false, false)
        val invisibleStripped = stripInvisible(raw)
        val credentials = containsCredential(invisibleStripped)
        val injection = containsInjection(invisibleStripped)
        val link = containsLink(invisibleStripped)
        if (raw.length > maxLength * MAX_RAW_TO_CLEAN_RATIO) {
            return Cleaned(null, credentials, injection, link)
        }
        val stripped = buildString(raw.length) {
            for (ch in raw) {
                when {
                    ch == '\n' && multiline -> append('\n')
                    ch == '\t' || ch == '\r' -> if (multiline && ch == '\t') append(' ') else Unit
                    !ch.isBoundarySafe() -> if (ch.category in separatorLike) append(' ') else Unit
                    else -> append(ch)
                }
            }
        }
        val normalized = if (multiline) {
            stripped
                .replace(whitespaceRun, " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(newlineRun, "\n\n")
                .trim()
        } else {
            stripped.replace(anyWhitespaceRun, " ").trim()
        }
        val value = normalized.take(maxLength).trim().takeIf { it.isNotEmpty() }
        return Cleaned(
            value = value,
            credentialsDetected = credentials || (value != null && containsCredential(value)),
            injectionDetected = injection || (value != null && containsInjection(value)),
            linkDetected = link || (value != null && containsLink(value))
        )
    }

    fun containsCredential(text: String): Boolean = credentialPatterns.any { it.containsMatchIn(text) }

    fun containsInjection(text: String): Boolean = injectionPatterns.any { it.containsMatchIn(text) }

    fun containsLink(text: String): Boolean = urlPattern.containsMatchIn(text)

    fun stripInvisible(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            if (ch.category != CharCategory.FORMAT && ch.category != CharCategory.SURROGATE) append(ch)
        }
    }

    private fun Char.isBoundarySafe(): Boolean =
        this !in framingCharacters && (category !in unsafeCategories || this == ' ')
}
