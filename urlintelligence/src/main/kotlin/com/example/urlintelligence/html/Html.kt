package com.example.urlintelligence.html

/**
 * Tiny HTML helpers. Adapters only need to read structured data, meta tags and
 * attribute selectors — not a full DOM — which keeps this layer dependency-free
 * and fast enough to run inside a background importer.
 */
object Html {

    private val JSON_LD_RE = Regex(
        "<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val SCRIPT_STYLE_RE = Regex(
        "<(script|style)\\b[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val META_RE = Regex("<meta\\s+([^>]*?)/?>", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("<[^>]+>")
    private val ENTITY_RE = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val WS_RE = Regex("\\s+")

    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "middot" to "·", "bull" to "•", "deg" to "°", "euro" to "€",
        "pound" to "£", "copy" to "©", "reg" to "®", "trade" to "™",
        "times" to "×", "divide" to "÷", "frac12" to "½", "szlig" to "ß"
    )

    /** Raw contents of every `<script type="application/ld+json">` block. */
    fun jsonLdBlocks(html: String): List<String> =
        JSON_LD_RE.findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /** Content of `<meta name|property|itemprop="<key>" content="...">`. */
    fun metaContent(html: String, key: String): String? {
        for (match in META_RE.findAll(html)) {
            val attrs = match.groupValues[1]
            val name = attribute(attrs, "name")
                ?: attribute(attrs, "property")
                ?: attribute(attrs, "itemprop")
            if (name != null && name.equals(key, ignoreCase = true)) {
                val content = attribute(attrs, "content")
                if (!content.isNullOrBlank()) return decodeEntities(content).trim()
            }
        }
        return null
    }

    /** Reads an attribute out of a raw tag attribute string. */
    fun attribute(tagAttributes: String, name: String): String? {
        val pattern = Regex(
            "(?:^|\\s)${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(tagAttributes) ?: return null
        val raw = when {
            match.groups[1] != null -> match.groupValues[1]
            match.groups[2] != null -> match.groupValues[2]
            else -> match.groupValues[3]
        }
        return raw
    }

    /**
     * `<div data-testid="list-price">$485,000</div>` -> `$485,000`
     * Nested markup inside the element is stripped before returning.
     */
    fun dataTestIdText(html: String, testId: String): String? {
        val pattern = Regex(
            "data-testid\\s*=\\s*[\"']${Regex.escape(testId)}[\"'][^>]*>(.*?)</",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val match = pattern.find(html) ?: return null
        return decodeEntities(stripTags(match.groupValues[1])).trim().takeIf { it.isNotEmpty() }
    }

    /** Visible-ish text: drops script/style/tags and decodes entities. */
    fun text(html: String): String {
        val withoutScripts = SCRIPT_STYLE_RE.replace(html, " ")
        return collapseWhitespace(decodeEntities(TAG_RE.replace(withoutScripts, " ")))
    }

    fun stripTags(html: String): String = TAG_RE.replace(html, " ")

    fun collapseWhitespace(value: String): String = WS_RE.replace(value.trim(), " ")

    fun decodeEntities(value: String): String {
        if (!value.contains('&')) return value
        return ENTITY_RE.replace(value) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.substring(2).toIntOrNull(16)?.toChar()?.toString() ?: match.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.toChar()?.toString() ?: match.value
                else -> NAMED_ENTITIES[body] ?: NAMED_ENTITIES[body.lowercase()] ?: match.value
            }
        }
    }
}
