package com.example.data.urlintelligence

import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.failure.SourceFailureClassifier
import com.example.urlintelligence.fetch.FetchLimits
import com.example.urlintelligence.idempotency.Digests
import com.example.urlintelligence.provenance.FetchOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Android transport for the URL-intelligence layer.
 *
 * Deliberately credential-free: it sends no Authorization/Cookie/API-key headers,
 * stores nothing, and never logs a response body. Hosts and schemes were already
 * validated (SSRF-guarded) by [com.example.urlintelligence.url.PropertyUrlValidator]
 * before a request reaches this class.
 *
 * It is also the only component that may report [FetchOrigin.LIVE_NETWORK]. That value is
 * what lets the pipeline call data *live-fetch-verified*, so a transport that did not really
 * perform the request (a replay, a cache, a fixture) must never set it.
 *
 * Timeouts, the response-size budget and redirect behaviour all come from [FetchLimits], and
 * the same budget is enforced again by the module's own response guard, so a transport bug
 * cannot push an unbounded document into the parsers.
 */
class OkHttpPropertyTransport(
    private val client: OkHttpClient = defaultClient(),
    private val limits: FetchLimits = FetchLimits.MOBILE,
    private val maxBodyChars: Int = limits.maxResponseBytes,
    private val userAgent: String = DEFAULT_USER_AGENT
) : PropertyHttpTransport {

    override suspend fun execute(request: SourceFetchRequest): SourceFetchResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(request.requestUrl)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .removeHeader("Authorization")
                .removeHeader("Cookie")

            request.headers.forEach { (name, value) ->
                if (name.lowercase() !in SourceFetchRequest.FORBIDDEN_HEADERS) {
                    builder.header(name, value)
                }
            }

            try {
                // The per-request limits win over the transport default: a caller that asks for
                // a tighter budget must get it, and one that asks for a larger one must not be
                // silently capped. `newBuilder` reuses the connection pool and dispatcher.
                val requestLimits = request.limits
                val callTimeout = requestLimits.effectiveTimeoutMillis(request.timeoutMillis)
                val activeClient = client.newBuilder()
                    .connectTimeout(requestLimits.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                    .readTimeout(requestLimits.readTimeoutMillis, TimeUnit.MILLISECONDS)
                    .callTimeout(callTimeout, TimeUnit.MILLISECONDS)
                    .build()

                // Follow at most `requestLimits.maxRedirects` hops: OkHttp reports extra hops
                // through `priorResponse`, and the module re-validates the final URL + host
                // family before parsing anything.
                val bodyBudget = minOf(requestLimits.maxResponseBytes, maxBodyChars)
                var redirects = 0
                activeClient.newCall(builder.build()).execute().use { response ->
                    val headers = response.headers.toMultimap()
                        .mapValues { (_, values) -> values.firstOrNull().orEmpty() }

                    redirects = countRedirects(response)
                    if (redirects > requestLimits.maxRedirects) {
                        return@withContext SourceFetchResponse.Failure(
                            SourceFailure.TooManyRedirects(redirects, requestLimits.maxRedirects)
                        )
                    }

                    if (!response.isSuccessful) {
                        return@withContext SourceFetchResponse.Failure(
                            SourceFailureClassifier.fromStatus(response.code, headers)
                        )
                    }

                    val bodySource = response.body.source()

                    // Read at most the budget plus one byte, so an oversized document is
                    // detected without ever buffering it in full. `request(n)` stops early
                    // when the body is shorter, so drain whatever actually arrived.
                    bodySource.request((bodyBudget + 1).toLong())
                    val arrived = bodySource.buffer.size
                    if (arrived > bodyBudget) {
                        return@withContext SourceFetchResponse.Failure(
                            SourceFailure.PayloadTooLarge(
                                (arrived).toLong(),
                                bodyBudget.toLong()
                            )
                        )
                    }
                    val bytes = bodySource.buffer.readByteArray()

                    val body = String(bytes, Charsets.UTF_8)
                    if (body.isBlank()) {
                        return@withContext SourceFetchResponse.Failure(
                            SourceFailure.EmptyResponse("provider returned an empty document", response.code)
                        )
                    }

                    SourceFetchResponse.Success(
                        statusCode = response.code,
                        body = body,
                        contentType = response.header("Content-Type"),
                        finalUrl = response.request.url.toString(),
                        fetchedAtEpochMillis = System.currentTimeMillis(),
                        headers = headers,
                        reportedByteSize = bytes.size,
                        redirectCount = redirects,
                        // This transport really performed the request; only here may a live
                        // origin be reported.
                        origin = FetchOrigin.LIVE_NETWORK,
                        documentDigest = Digests.sha256Hex(body)
                    )
                }
            } catch (t: Throwable) {
                SourceFetchResponse.Failure(SourceFailureClassifier.fromThrowable(t))
            }
        }

    private fun countRedirects(response: okhttp3.Response): Int {
        var count = 0
        var prior = response.priorResponse
        while (prior != null && count < MAX_REDIRECTS_REPORTED) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        private const val MAX_REDIRECTS_REPORTED = 10

        /** Identify the client honestly; rotate this if the app is published. */
        const val DEFAULT_USER_AGENT: String =
            "RealEstateAI/1.0 (+property-url-import; no-automated-bulk-crawling)"

        /**
         * Client configured from [FetchLimits]. Retries are owned by the resolver, so
         * `retryOnConnectionFailure` stays off to keep attempt accounting honest.
         */
        fun defaultClient(limits: FetchLimits = FetchLimits.MOBILE): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(limits.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(limits.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(limits.callTimeoutMillis, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false) // retry/backoff is owned by the resolver
                .build()
    }
}
