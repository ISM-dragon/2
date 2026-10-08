package com.example.urlintelligence.adapter

import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.fetch.FetchLimits
import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.provenance.FetchOrigin
import com.example.urlintelligence.source.SourceDescriptor

/** Everything an adapter needs to perform one fetch. Carries no credentials by design. */
data class SourceFetchRequest(
    val requestUrl: String,
    val correlationId: String,
    val sourcePropertyId: String? = null,
    val attempt: Int = 1,
    val timeoutMillis: Long = 15_000L,
    /** Non-sensitive headers only (e.g. Accept-Language). Auth is never part of this layer. */
    val headers: Map<String, String> = emptyMap(),
    /** Hard limits the transport must enforce (timeouts, response size, redirects). */
    val limits: FetchLimits = FetchLimits()
) {
    init {
        require(requestUrl.isNotBlank()) { "requestUrl must not be blank" }
        require(attempt >= 1) { "attempt must be >= 1" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    companion object {
        /** Headers that must never be attached to an outbound property request. */
        val FORBIDDEN_HEADERS: Set<String> = setOf(
            "authorization", "proxy-authorization", "proxy-authenticate", "www-authenticate",
            "cookie", "set-cookie", "x-api-key", "x-goog-api-key", "api-key",
            "x-auth-token", "x-access-token", "x-refresh-token", "x-id-token", "x-bearer-token"
        )
    }
}

sealed class SourceFetchResponse {

    data class Success(
        val statusCode: Int,
        val body: String,
        val contentType: String?,
        val finalUrl: String,
        val fetchedAtEpochMillis: Long,
        val headers: Map<String, String> = emptyMap(),
        /** Bytes the transport actually read; -1 when unreported (falls back to body length). */
        val reportedByteSize: Int = -1,
        /** Number of redirects the transport followed to reach [finalUrl]. */
        val redirectCount: Int = 0,
        /**
         * Where this document came from. Only [FetchOrigin.LIVE_NETWORK] (reported by a
         * transport that really performed the request) can produce live-verified fields.
         */
        val origin: FetchOrigin = FetchOrigin.UNSPECIFIED,
        /** Digest of the raw document, when the transport computed one. */
        val documentDigest: String? = null
    ) : SourceFetchResponse() {

        /** Response size used by the guard: the reported byte count, or the body length. */
        val byteSize: Int
            get() = if (reportedByteSize >= 0) reportedByteSize else body.length
    }

    data class Failure(val failure: SourceFailure) : SourceFetchResponse()
}

/** Transport boundary. Android supplies an OkHttp implementation; tests supply a fake. */
interface PropertyHttpTransport {
    suspend fun execute(request: SourceFetchRequest): SourceFetchResponse
}

sealed class PropertyParseResult {
    data class Success(
        val draft: PropertyDraft,
        /** Non-fatal notes (redirects followed, schema drift, ...) that must reach the caller. */
        val warnings: List<String> = emptyList()
    ) : PropertyParseResult()
    data class Partial(val draft: PropertyDraft, val warnings: List<String>) : PropertyParseResult()
    data class Failure(val failure: SourceFailure) : PropertyParseResult()

    val draftOrNull: PropertyDraft?
        get() = when (this) {
            is Success -> draft
            is Partial -> draft
            is Failure -> null
        }
}

/**
 * Contract every property source implements.
 *
 * `fetch` and `parse` are separate on purpose:
 *  * `fetch` is I/O (retryable, owned by the transport),
 *  * `parse` is a pure function of (document, url) — which is exactly what the
 *    parser fixtures test, with no network, no coroutines and no flakiness.
 */
interface PropertySourceAdapter {
    val descriptor: SourceDescriptor

    suspend fun fetch(request: SourceFetchRequest): SourceFetchResponse

    fun parse(response: SourceFetchResponse.Success, request: SourceFetchRequest): PropertyParseResult
}

/** Convenience: fetch + parse in one call, still fully retryable by the caller. */
suspend fun PropertySourceAdapter.resolve(request: SourceFetchRequest): PropertyParseResult {
    return when (val response = fetch(request)) {
        is SourceFetchResponse.Success -> parse(response, request)
        is SourceFetchResponse.Failure -> PropertyParseResult.Failure(response.failure)
    }
}
