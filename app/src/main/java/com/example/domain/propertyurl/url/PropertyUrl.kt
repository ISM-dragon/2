package com.example.domain.propertyurl.url

import java.util.Locale

/** A single query parameter, stored percent-decoded. */
data class QueryParameter(val name: String, val value: String)

/**
 * A decomposed, validated property listing URL.
 *
 * [original] keeps exactly what the user pasted (for audit); [normalized] is the canonical form used
 * for identity, deduplication and fetching. Identity never depends on the fragment and never on
 * tracking parameters — see [PropertyUrlNormalizer].
 */
data class PropertyUrl(
    val original: String,
    val normalized: String,
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val queryParameters: List<QueryParameter> = emptyList(),
    val fragment: String? = null
) {

    /** Query lookup map (first occurrence wins, names lower-cased). */
    val query: Map<String, String> by lazy {
        val map = LinkedHashMap<String, String>()
        queryParameters.forEach { map.putIfAbsent(it.name.lowercase(Locale.US), it.value) }
        map
    }

    fun queryValue(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> query[name.lowercase(Locale.US)]?.takeIf { it.isNotBlank() } }

    val pathSegments: List<String> by lazy {
        path.split('/').filter { it.isNotEmpty() }
    }

    val registrableDomain: String get() = UrlHosts.registrableDomain(host)

    val isSecure: Boolean get() = scheme.equals("https", ignoreCase = true)

    val hasCredentials: Boolean get() = false // credentials are rejected during validation, never carried

    /** Canonical identity of the document: scheme-independent, fragment-free, tracking-free path+query. */
    val identitySeed: String by lazy {
        buildString {
            append(host)
            append(path)
            if (queryParameters.isNotEmpty()) {
                append('?')
                append(queryParameters.sortedBy { it.name }.joinToString("&") { "${it.name}=${it.value}" })
            }
        }
    }

    fun withNormalized(
        normalized: String,
        host: String = this.host,
        port: Int? = this.port,
        path: String = this.path,
        queryParameters: List<QueryParameter> = this.queryParameters
    ): PropertyUrl = copy(
        normalized = normalized,
        host = host,
        port = port,
        path = path,
        queryParameters = queryParameters
    )

    /** Log-safe rendering: sensitive looking parameters are masked, userinfo is never included. */
    fun redacted(): String {
        val masked = queryParameters.map { parameter ->
            if (UrlSensitiveParameters.isSensitive(parameter.name)) QueryParameter(parameter.name, "***")
            else parameter
        }
        return UrlRecomposer.recompose(scheme, host, port, path, masked, fragment = null)
    }

    fun displayTarget(): String = if (port == null) "$host$path" else "$host:$port$path"

    override fun toString(): String = normalized
}

/** Query parameter names whose values must never be logged or persisted in clear text. */
object UrlSensitiveParameters {
    private val EXACT = setOf(
        "token", "access_token", "id_token", "refresh_token", "auth", "authorization", "apikey", "api_key",
        "key", "secret", "signature", "sig", "session", "sessionid", "sid", "password", "pwd", "otp",
        "code", "sharedid", "authid", "credential", "email", "phone", "mobile", "user", "username",
        "user_id", "userid", "customer_id"
    )

    fun isSensitive(name: String): Boolean {
        val lowered = name.lowercase(Locale.US).replace('-', '_')
        return EXACT.contains(lowered) ||
            lowered.endsWith("_token") ||
            lowered.endsWith("token") ||
            lowered.endsWith("_key") ||
            lowered.endsWith("secret") ||
            lowered.endsWith("password")
    }
}

/** Host helpers: registrable domain, IP literals, private ranges. */
object UrlHosts {

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** Second level labels of multi-part public suffixes we care about (kept intentionally small). */
    private val MULTI_PART_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "co.jp", "ne.jp", "or.jp", "com.au", "net.au", "org.au",
        "co.nz", "com.br", "com.mx", "co.in", "co.za", "com.sg", "com.hk", "com.tr", "co.il", "com.ar",
        "com.co", "com.pe", "com.ph", "com.my", "com.tw", "co.kr", "com.cn", "com.ua"
    )

    fun stripLeadingWww(host: String): String {
        val lowered = host.lowercase(Locale.US)
        return when {
            lowered.startsWith("www.") -> lowered.removePrefix("www.")
            lowered.startsWith("m.") -> lowered.removePrefix("m.")
            lowered.startsWith("mobile.") -> lowered.removePrefix("mobile.")
            else -> lowered
        }
    }

    fun registrableDomain(host: String): String {
        val stripped = stripLeadingWww(host).trim('.')
        val labels = stripped.split('.')
        if (labels.size <= 2) return stripped
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (MULTI_PART_SUFFIXES.contains(lastTwo) && labels.size >= 3) {
            labels.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    fun isIpLiteral(host: String): Boolean {
        val candidate = host.removePrefix("[").removeSuffix("]")
        if (candidate.contains(':')) return true // IPv6
        val match = IPV4.matchEntire(candidate) ?: return false
        return match.groupValues.drop(1).all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
    }

    fun isLoopback(host: String): Boolean {
        val lowered = stripLeadingWww(host)
        if (lowered == "localhost" || lowered.endsWith(".localhost") || lowered.endsWith(".local")) return true
        return ipv4Octets(lowered)?.let { it[0] == 127 } == true
    }

    /** True for RFC1918 / link-local / CGNAT / unique-local ranges — the layer refuses to fetch these. */
    fun isPrivateNetwork(host: String): Boolean {
        val lowered = stripLeadingWww(host)
        if (isLoopback(lowered)) return true
        val octets = ipv4Octets(lowered) ?: return lowered.startsWith("fc") || lowered.startsWith("fd") ||
            lowered.startsWith("fe80:") || lowered.startsWith("[fc") || lowered.startsWith("[fd")
        return when (octets[0]) {
            10 -> true
            172 -> octets[1] in 16..31
            192 -> octets[1] == 168
            169 -> octets[1] == 254
            100 -> octets[1] in 64..127
            0 -> true
            else -> false
        }
    }

    private fun ipv4Octets(host: String): List<Int>? {
        val match = IPV4.matchEntire(host) ?: return null
        val octets = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        return if (octets.size == 4 && octets.all { it in 0..255 }) octets else null
    }

    fun isValidHostSyntax(host: String): Boolean {
        if (host.isBlank() || host.length > 253) return false
        val candidate = host.removePrefix("[").removeSuffix("]")
        if (candidate.isEmpty()) return false
        if (isIpLiteral(host)) return true
        val labels = candidate.split('.')
        if (labels.any { it.isEmpty() }) return false
        return labels.all { label ->
            label.length in 1..63 &&
                !label.startsWith("-") &&
                !label.endsWith("-") &&
                label.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' }
        }
    }

    fun hasTld(host: String): Boolean {
        if (isIpLiteral(host)) return true
        val labels = host.trim('.').split('.')
        if (labels.size < 2) return false
        val tld = labels.last()
        // a-z or punycode (`xn--…`) TLDs only
        return (tld.length >= 2 && tld.all { it in 'a'..'z' }) ||
            (tld.startsWith("xn--") && tld.length > 4)
    }

    fun isAscii(host: String): Boolean = host.all { it.code < 128 }
}

/** Percent-encoding / decoding helpers (RFC 3986), deliberately dependency free. */
object PercentCodec {

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun hasInvalidEscape(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            if (value[i] == '%') {
                if (i + 2 >= value.length) return true
                val hex = value.substring(i + 1, i + 3)
                if (hex.length != 2 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    fun decode(value: String, plusAsSpace: Boolean = false): String {
        if (!value.contains('%') && !(plusAsSpace && value.contains('+'))) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length + 0 && i + 2 <= value.length - 1 -> {
                    val hex = value.substring(i + 1, i + 3)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 3
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '+' && plusAsSpace -> {
                    out.append(' ')
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Canonical encoding: unreserved characters stay literal, upper-case hex for the rest. */
    fun encodeComponent(value: String, extraSafe: String = ""): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (UNRESERVED.contains(c) || extraSafe.contains(c)) {
                out.append(c)
            } else {
                out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    /** Upper-cases percent escapes and decodes escapes of unreserved characters (canonical form). */
    fun canonicalizeEscapes(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    val decoded = code.toChar()
                    if (UNRESERVED.contains(decoded)) {
                        out.append(decoded)
                    } else {
                        out.append('%').append(hex.uppercase(Locale.US))
                    }
                    i += 3
                } else {
                    out.append(c)
                    i++
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** Rebuilds a URL string from its parts; all callers go through it so formatting stays consistent. */
object UrlRecomposer {

    fun recompose(
        scheme: String,
        host: String,
        port: Int?,
        path: String,
        queryParameters: List<QueryParameter>,
        fragment: String?,
        includeFragment: Boolean = false
    ): String = buildString {
        append(scheme).append("://").append(host)
        if (port != null && !isDefaultPort(scheme, port)) append(':').append(port)
        append(path.ifEmpty { "/" })
        if (queryParameters.isNotEmpty()) {
            append('?')
            append(
                queryParameters.joinToString("&") { parameter ->
                    val name = PercentCodec.encodeComponent(parameter.name, extraSafe = "-._~")
                    if (parameter.value.isEmpty()) name
                    else "$name=${PercentCodec.encodeComponent(parameter.value, extraSafe = "-._~!$'()*+,;:@/?")}"
                }
            )
        }
        if (includeFragment && !fragment.isNullOrEmpty()) append('#').append(fragment)
    }

    fun isDefaultPort(scheme: String, port: Int): Boolean =
        (scheme.equals("http", true) && port == 80) || (scheme.equals("https", true) && port == 443)
}

/** Structural decomposition result (internal to the url package). */
internal data class UrlParts(
    val sanitized: String,
    val scheme: String?,
    val hadAuthorityPrefix: Boolean,
    val userInfo: String?,
    val host: String?,
    val port: Int?,
    val portRaw: String?,
    val path: String,
    val queryRaw: String?,
    val fragment: String?
)

/** Tolerant, dependency-free URL decomposer used by the validator/normalizer. */
internal object UrlParser {

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")

    /** Trailing characters commonly glued to a pasted URL by chat apps / markdown. */
    private const val TRAILING_NOISE = ".,;:!?)]}»…\"'"

    fun sanitize(input: String): String {
        var value = input.trim()
        // Markdown/HTML wrappers: [text](url), <url>, "url"
        if (value.startsWith("<") && value.endsWith(">")) value = value.substring(1, value.length - 1).trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1).trim()
        }
        val markdown = Regex("^\\[[^\\]]*]\\((.+)\\)$").find(value)
        if (markdown != null) value = markdown.groupValues[1].trim()
        value = value.trimEnd { TRAILING_NOISE.contains(it) }
        // Kill zero-width and control characters early (they break host comparisons).
        return value.filter { it.code >= 0x20 && it.code != 0x7F }
    }

    fun decompose(sanitized: String): UrlParts {
        var rest = sanitized
        var scheme: String? = null

        val schemeMatch = SCHEME.find(rest)
        if (schemeMatch != null) {
            val candidate = schemeMatch.groupValues[1]
            val after = rest.substring(schemeMatch.value.length)
            val looksLikeHostPort = candidate.contains('.') && after.isNotEmpty() && after.first().isDigit()
            if (!looksLikeHostPort) {
                scheme = candidate.lowercase(Locale.US)
                rest = after
            }
        }

        var hadAuthority = false
        if (rest.startsWith("//")) {
            hadAuthority = true
            rest = rest.substring(2)
        }

        var fragment: String? = null
        val hashIndex = rest.indexOf('#')
        if (hashIndex >= 0) {
            fragment = rest.substring(hashIndex + 1)
            rest = rest.substring(0, hashIndex)
        }

        var queryRaw: String? = null
        val queryIndex = rest.indexOf('?')
        if (queryIndex >= 0) {
            queryRaw = rest.substring(queryIndex + 1)
            rest = rest.substring(0, queryIndex)
        }

        val authorityEnd = rest.indexOfFirst { it == '/' }
        val authority = if (authorityEnd >= 0) rest.substring(0, authorityEnd) else rest
        var path = if (authorityEnd >= 0) rest.substring(authorityEnd) else ""

        var userInfo: String? = null
        var hostPort = authority
        val atIndex = authority.lastIndexOf('@')
        if (atIndex >= 0) {
            userInfo = authority.substring(0, atIndex)
            hostPort = authority.substring(atIndex + 1)
        }

        var host: String? = null
        var port: Int? = null
        var portRaw: String? = null

        if (hostPort.isNotEmpty()) {
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close >= 0) {
                    host = hostPort.substring(0, close + 1)
                    val remainder = hostPort.substring(close + 1)
                    if (remainder.startsWith(":")) {
                        portRaw = remainder.substring(1)
                        port = portRaw.toIntOrNull()
                    }
                } else {
                    host = hostPort
                }
            } else {
                val colon = hostPort.lastIndexOf(':')
                if (colon >= 0) {
                    host = hostPort.substring(0, colon)
                    portRaw = hostPort.substring(colon + 1)
                    port = portRaw.toIntOrNull()
                } else {
                    host = hostPort
                }
            }
        }

        if (path.isEmpty()) path = "/"
        if (!path.startsWith("/")) path = "/$path"

        return UrlParts(
            sanitized = sanitized,
            scheme = scheme,
            hadAuthorityPrefix = hadAuthority,
            userInfo = userInfo,
            host = host?.lowercase(Locale.US)?.trim('.'),
            port = port,
            portRaw = portRaw,
            path = path,
            queryRaw = queryRaw,
            fragment = fragment
        )
    }

    /** Parses `a=1&b=2` into decoded pairs; malformed pairs are surfaced through [onMalformed]. */
    fun parseQuery(raw: String, onMalformed: (String) -> Unit = {}): List<QueryParameter> {
        if (raw.isEmpty()) return emptyList()
        val parameters = ArrayList<QueryParameter>()
        raw.split('&').forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val separator = chunk.indexOf('=')
            val rawName = if (separator >= 0) chunk.substring(0, separator) else chunk
            val rawValue = if (separator >= 0) chunk.substring(separator + 1) else ""
            if (rawName.isEmpty()) {
                onMalformed(chunk)
                return@forEach
            }
            parameters.add(QueryParameter(PercentCodec.decode(rawName, plusAsSpace = true), PercentCodec.decode(rawValue, plusAsSpace = true)))
        }
        return parameters
    }
}
