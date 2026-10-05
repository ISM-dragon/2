package com.example.domain.propertyurl.parse

import java.util.Locale

/**
 * Dependency-free HTML scanning helpers.
 *
 * Why not Jsoup? The repository already ships no HTML library, this layer must stay usable from a
 * plain JVM (unit tests) and from Android without adding ~400 KB, and property pages are read
 * through a small, well-tested set of patterns (script blocks, meta tags, embedded JSON).
 *
 * All functions are pure and tolerant: hostile or truncated markup yields empty results instead of
 * exceptions, because "the page is broken" is a failure the classifier must express, not a crash.
 */
object HtmlScanner {

    data class ScriptBlock(
        val attributes: Map<String, String>,
        val content: String,
        val startIndex: Int
    ) {
        val type: String? get() = attributes["type"]
        val id: String? get() = attributes["id"]
    }

    /**
     * Markers used to locate embedded JSON payloads. `label` is for provenance/diagnostics and
     * `token` is what is searched for in the markup.
     */
    data class Marker(val label: String, val token: String) {
        companion object {
            val NEXT_DATA = Marker("__NEXT_DATA__", "__NEXT_DATA__")
            val PRELOADED_STATE = Marker("window.__PRELOADED_STATE__", "__PRELOADED_STATE__")
            val INITIAL_STATE = Marker("window.__INITIAL_STATE__", "__INITIAL_STATE__")
            val REACT_SERVER_STATE = Marker("__reactServerState", "__reactServerState")
            val NUXT = Marker("window.__NUXT__", "__NUXT__")

            fun windowAssignment(name: String) = Marker("window.$name", "window.$name")
        }
    }

    private val SCRIPT = Regex("(?is)<script\\b([^>]*)>(.*?)</script\\s*>")
    private val STYLE = Regex("(?is)<style\\b[^>]*>.*?</style\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val TAG = Regex("(?s)<[^>]+>")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title\\s*>")
    private val LINK = Regex("(?is)<link\\b([^>]*)>")
    private val META = Regex("(?is)<meta\\b([^>]*)>")
    private val ATTRIBUTE = Regex(
        "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?"
    )

    fun scriptBlocks(html: String): List<ScriptBlock> =
        SCRIPT.findAll(html).map { match ->
            ScriptBlock(
                attributes = parseAttributes(match.groupValues[1]),
                content = match.groupValues[2],
                startIndex = match.range.first
            )
        }.toList()

    fun scriptBlocksOfType(html: String, type: String): List<ScriptBlock> =
        scriptBlocks(html).filter { it.type?.contains(type, ignoreCase = true) == true }

    fun scriptBlockById(html: String, id: String): ScriptBlock? =
        scriptBlocks(html).firstOrNull { it.id?.equals(id, ignoreCase = true) == true }

    /** All `application/ld+json` blocks, in document order (arrays are flattened by the parser). */
    fun jsonLdBlocks(html: String): List<String> =
        scriptBlocksOfType(html, "ld+json").map { it.content.trim() }.filter { it.isNotEmpty() }

    /** `name`/`property`/`itemprop` → `content`, both keys lower-cased. */
    fun metaTags(html: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        META.findAll(html).forEach { match ->
            val attributes = parseAttributes(match.groupValues[1])
            val content = attributes["content"] ?: return@forEach
            val key = attributes["property"] ?: attributes["name"] ?: attributes["itemprop"] ?: return@forEach
            if (content.isNotBlank()) result.putIfAbsent(key.lowercase(Locale.US), content.trim())
        }
        return result
    }

    /**
     * All values of a repeated meta key (galleries publish several `og:image` tags). Order is document
     * order; duplicates are collapsed. [metaTags] keeps only the first value per key.
     */
    fun metaTagValues(html: String, key: String): List<String> {
        val wanted = key.lowercase(Locale.US)
        val values = LinkedHashSet<String>()
        META.findAll(html).forEach { match ->
            val attributes = parseAttributes(match.groupValues[1])
            val content = attributes["content"] ?: return@forEach
            val name = attributes["property"] ?: attributes["name"] ?: attributes["itemprop"] ?: return@forEach
            if (name.lowercase(Locale.US) == wanted && content.isNotBlank()) values.add(content.trim())
        }
        return values.toList()
    }

    fun titleTag(html: String): String? =
        TITLE.find(html)?.groupValues?.get(1)?.let { decodeEntities(it) }?.let { normalizeWhitespace(it) }
            ?.takeIf { it.isNotEmpty() }

    fun canonicalLink(html: String): String? {
        LINK.findAll(html).forEach { match ->
            val attributes = parseAttributes(match.groupValues[1])
            if (attributes["rel"]?.contains("canonical", ignoreCase = true) == true) {
                attributes["href"]?.let { return it.trim() }
            }
        }
        return null
    }

    fun parseAttributes(raw: String): Map<String, String> {
        val attributes = LinkedHashMap<String, String>()
        ATTRIBUTE.findAll(raw).forEach { match ->
            val name = match.groupValues[1].lowercase(Locale.US)
            val value = match.groupValues[2]
                .ifEmpty { match.groupValues[3] }
                .ifEmpty { match.groupValues[4] }
            if (name.isNotEmpty() && !attributes.containsKey(name)) attributes[name] = decodeEntities(value)
        }
        return attributes
    }

    /**
     * Finds the JSON object/array that follows [marker] (e.g. `window.__INITIAL_STATE__`) using
     * brace balancing that is aware of strings and escapes.
     */
    fun embeddedJsonAfter(html: String, marker: String, searchFrom: Int = 0): String? {
        val markerIndex = html.indexOf(marker, searchFrom)
        if (markerIndex < 0) return null
        var index = markerIndex + marker.length
        // Skip whitespace, `=`, `(` and `:`
        while (index < html.length && (html[index].isWhitespace() || html[index] == '=' || html[index] == ':' ||
                html[index] == '(')
        ) {
            index++
        }
        return balancedJsonAt(html, index)
    }

    /**
     * Returns the balanced JSON substring starting at the first `{`/`[` at or after [fromIndex].
     * The scan window is bounded and stops at markup (`<`) so a marker found in an attribute list
     * (e.g. `<script id="__NEXT_DATA__" type="application/json">`) resolves to the payload that
     * follows it instead of a random brace later in the document.
     */
    fun balancedJsonAt(text: String, fromIndex: Int, maxScan: Int = 1024): String? {
        val limit = minOf(text.length, fromIndex + maxScan)
        var start = -1
        var cursor = fromIndex
        while (cursor < limit) {
            val c = text[cursor]
            if (c == '{' || c == '[') {
                start = cursor
                break
            }
            if (c == '<') return null
            cursor++
        }
        if (start < 0) return null

        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < text.length) {
            val c = text[index]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == open -> depth++
                !inString && c == close -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
            index++
        }
        return null
    }

    /** Visible text: scripts/styles/comments removed, entities decoded, whitespace compacted. */
    fun visibleText(html: String): String {
        var text = COMMENT.replace(html, " ")
        text = SCRIPT.replace(text, " ")
        text = STYLE.replace(text, " ")
        text = text.replace(Regex("(?is)<(br|/p|/div|/li|/tr|/h[1-6])\\b[^>]*>"), "\n")
        text = TAG.replace(text, " ")
        text = decodeEntities(text)
        return normalizeWhitespace(text)
    }

    fun stripTags(html: String): String = visibleText(html)

    fun normalizeWhitespace(text: String): String =
        text.replace(Regex("[\\u00A0\\u2007\\u202F\\s]+"), " ").trim()

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "ldquo" to "“", "rdquo" to "”", "middot" to "·", "bull" to "•", "deg" to "°",
        "eacute" to "é", "egrave" to "è", "agrave" to "à", "uuml" to "ü", "ouml" to "ö",
        "auml" to "ä", "szlig" to "ß", "ntilde" to "ñ", "copy" to "©", "reg" to "®", "trade" to "™",
        "frac12" to "½", "frac14" to "¼", "times" to "×", "minus" to "−", "plusmn" to "±",
        "sol" to "/", "colon" to ":", "commat" to "@", "num" to "#", "dollar" to "$", "percnt" to "%"
    )

    private val ENTITY = Regex("&(#x?[0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")

    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) -> codePointToString(body.substring(2).toIntOrNull(16))
                body.startsWith("#") -> codePointToString(body.substring(1).toIntOrNull())
                else -> NAMED_ENTITIES[body.lowercase(Locale.US)] ?: match.value
            }
        }
    }

    private fun codePointToString(codePoint: Int?): String {
        if (codePoint == null || codePoint <= 0 || codePoint > 0x10FFFF) return ""
        return try {
            String(Character.toChars(codePoint))
        } catch (e: IllegalArgumentException) {
            ""
        }
    }
}
