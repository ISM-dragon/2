package com.example.urlintelligence.url

/**
 * Produces the form of a user-supplied URL that may be written to a job store.
 *
 * Job records exist for diagnostics and replay. They must never retain credentials: userinfo
 * (`https://user:pass@host/...`) is replaced by a fixed marker, and the values of credential-like
 * query parameters ([SensitiveUrlParameters]) are replaced by [REDACTED]. Parameter *names* are kept
 * so a support engineer can still see that a token was supplied.
 *
 * This is deliberately string-based and never throws: it must be safe to call on arbitrary,
 * unvalidated input before the URL has been classified as valid or invalid.
 */
object JobUrlRedactor {

    const val REDACTED: String = "REDACTED"

    fun redact(raw: String): String {
        var result = redactUserInfo(raw)
        result = redactSensitiveQuery(result)
        return result
    }

    private fun redactUserInfo(input: String): String {
        val schemeEnd = input.indexOf("://")
        if (schemeEnd < 0) return input
        val authorityStart = schemeEnd + 3
        var authorityEnd = input.length
        for (i in authorityStart until input.length) {
            val c = input[i]
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i
                break
            }
        }
        val authority = input.substring(authorityStart, authorityEnd)
        val at = authority.lastIndexOf('@')
        if (at < 0) return input
        return input.substring(0, authorityStart) + REDACTED + "@" + authority.substring(at + 1) +
            input.substring(authorityEnd)
    }

    private fun redactSensitiveQuery(input: String): String {
        val queryStart = input.indexOf('?')
        if (queryStart < 0) return input
        val fragmentStart = input.indexOf('#', queryStart).let { if (it < 0) input.length else it }
        val query = input.substring(queryStart + 1, fragmentStart)
        val redactedQuery = query.split('&').joinToString("&") { part ->
            val eq = part.indexOf('=')
            val name = if (eq < 0) part else part.substring(0, eq)
            if (name.isNotEmpty() && SensitiveUrlParameters.isSensitive(name)) {
                "$name=$REDACTED"
            } else {
                part
            }
        }
        return input.substring(0, queryStart + 1) + redactedQuery + input.substring(fragmentStart)
    }
}
