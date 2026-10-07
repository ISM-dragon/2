package com.example.urlintelligence.url

/** Cheap, allocation-light URL component access used by adapters and detectors. */
object UrlParts {

    fun hostOf(url: String): String {
        val uri = try {
            java.net.URI(url)
        } catch (t: Exception) {
            null
        }
        if (uri != null && !uri.host.isNullOrBlank()) return uri.host!!.lowercase()
        return url.substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
            .lowercase()
    }

    fun pathOf(url: String): String {
        val uri = try {
            java.net.URI(url)
        } catch (t: Exception) {
            null
        }
        val rawPath = uri?.rawPath
        if (!rawPath.isNullOrBlank()) return rawPath
        return url.substringAfter("://", "")
            .substringAfter(hostOf(url), "")
            .substringAfter(':', "")
            .substringBefore('#')
            .substringBefore('?')
            .let { if (it.startsWith("/")) it else "/$it" }
            .ifBlank { "/" }
    }

    fun queryOf(url: String): Map<String, String> {
        val raw = url.substringAfter('?', "").substringBefore('#')
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val eq = pair.indexOf('=')
            val key = (if (eq < 0) pair else pair.substring(0, eq)).trim()
            if (key.isBlank()) return@forEach
            out[key] = if (eq < 0) "" else pair.substring(eq + 1)
        }
        return out
    }

    /** True when both URLs point at the same host and the same path. */
    fun sameEndpoint(first: String, second: String): Boolean =
        hostOf(first) == hostOf(second) && pathOf(first) == pathOf(second)
}
