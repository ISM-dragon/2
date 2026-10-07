package com.example.domain.propertyurl.model

/**
 * Raw payload retrieved for a listing URL. Adapters receive this *after* fetching, which is what
 * makes parsers deterministic and unit testable from fixtures without any network access.
 */
data class RawSourceDocument(
    val requestedUrl: String,
    val finalUrl: String,
    val httpStatus: Int,
    val contentType: String?,
    val headers: Map<String, String>,
    val body: String?,
    val bodyBytes: Int,
    val retrievedAtEpochMillis: Long,
    val elapsedMillis: Long = 0,
    val truncated: Boolean = false,
    val redirects: List<String> = emptyList()
) {
    val isHtml: Boolean
        get() = contentType?.lowercase()?.let { it.contains("html") || it.contains("xml") } == true ||
            (contentType == null && body?.trimStart()?.startsWith("<") == true)

    val isJson: Boolean get() = contentType?.lowercase()?.contains("json") == true

    val bodyOrEmpty: String get() = body.orEmpty()

    val hasBody: Boolean get() = !body.isNullOrBlank()

    val isSuspiciouslySmall: Boolean get() = bodyBytes < 4096
}
