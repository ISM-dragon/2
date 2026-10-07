package com.example.data.urlintelligence

import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.failure.SourceFailureClassifier
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
 */
class OkHttpPropertyTransport(
    private val client: OkHttpClient = defaultClient(),
    private val maxBodyChars: Int = 2_000_000,
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
                client.newCall(builder.build()).execute().use { response ->
                    val headers = response.headers.toMultimap()
                        .mapValues { (_, values) -> values.firstOrNull().orEmpty() }

                    if (!response.isSuccessful) {
                        return@withContext SourceFetchResponse.Failure(
                            SourceFailureClassifier.fromStatus(response.code, headers)
                        )
                    }

                    val body = response.body?.string().orEmpty()
                    if (body.length > maxBodyChars) {
                        return@withContext SourceFetchResponse.Failure(
                            SourceFailure.PayloadTooLarge(body.length.toLong(), maxBodyChars.toLong())
                        )
                    }

                    SourceFetchResponse.Success(
                        statusCode = response.code,
                        body = body,
                        contentType = response.header("Content-Type"),
                        finalUrl = response.request.url.toString(),
                        fetchedAtEpochMillis = System.currentTimeMillis(),
                        headers = headers
                    )
                }
            } catch (t: Throwable) {
                SourceFetchResponse.Failure(SourceFailureClassifier.fromThrowable(t))
            }
        }

    companion object {
        /** Identify the client honestly; rotate this if the app is published. */
        const val DEFAULT_USER_AGENT: String =
            "RealEstateAI/1.0 (+property-url-import; no-automated-bulk-crawling)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // retry/backoff is owned by the resolver
            .build()
    }
}
