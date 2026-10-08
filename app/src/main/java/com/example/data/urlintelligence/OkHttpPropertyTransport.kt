package com.example.data.urlintelligence

import com.example.domain.propertyurl.port.PublicOnlyDns
import com.example.domain.propertyurl.url.PropertyUrlValidator
import com.example.domain.propertyurl.url.UrlSensitiveParameters
import com.example.domain.propertyurl.url.UrlValidationOptions
import com.example.domain.propertyurl.url.UrlValidationResult
import com.example.domain.propertyurl.util.Redaction
import com.example.urlintelligence.adapter.PropertyHttpTransport
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.failure.SourceFailure
import com.example.urlintelligence.failure.SourceFailureClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Android transport for the URL-intelligence layer.
 *
 * Deliberately credential-free: it sends no Authorization/Cookie/API-key headers,
 * stores nothing, and never logs a response body. The resolver validates the initial URL; this
 * boundary independently refuses cleartext and disables all redirects so a validated public URL
 * cannot silently redirect the app to a private host or downgrade the request to HTTP.
 */
class OkHttpPropertyTransport(
    client: OkHttpClient = defaultClient(),
    private val maxBodyBytes: Int = 2_000_000,
    private val userAgent: String = DEFAULT_USER_AGENT
) : PropertyHttpTransport {

    // Enforce transport policy even when a caller injects an OkHttpClient configured to follow redirects.
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(PublicOnlyDns(client.dns))
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        .build()

    init {
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive" }
    }

    override suspend fun execute(request: SourceFetchRequest): SourceFetchResponse =
        withContext(Dispatchers.IO) {
            val parsedUrl = request.requestUrl.toHttpUrlOrNull()
                ?: return@withContext SourceFetchResponse.Failure(
                    SourceFailure.InvalidUrl("Malformed property URL.")
                )
            if (parsedUrl.scheme != "https") {
                return@withContext SourceFetchResponse.Failure(
                    SourceFailure.InvalidUrl("Only HTTPS property requests are allowed.")
                )
            }
            if (parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) {
                return@withContext SourceFetchResponse.Failure(
                    SourceFailure.InvalidUrl("Property URLs must not include credentials.")
                )
            }
            if (parsedUrl.port != 443 ||
                parsedUrl.queryParameterNames.any(UrlSensitiveParameters::isSensitive) ||
                PropertyUrlValidator(UrlValidationOptions(allowInsecureHttp = false))
                    .validate(parsedUrl.toString()) !is UrlValidationResult.Valid
            ) {
                return@withContext SourceFetchResponse.Failure(
                    SourceFailure.InvalidUrl("Property URL failed secure destination validation.")
                )
            }

            val builder = Request.Builder()
                .url(parsedUrl)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .removeHeader("Authorization")
                .removeHeader("Cookie")

            request.headers.forEach { (name, value) ->
                if (name.lowercase() !in SourceFetchRequest.FORBIDDEN_HEADERS &&
                    !Redaction.isSensitiveHeader(name)
                ) {
                    builder.header(name, value)
                }
            }

            try {
                client.newCall(builder.build()).execute().use { response ->
                    // Response headers may include cookies or authentication challenges. They are
                    // not needed by the property parsers and are intentionally not forwarded.
                    val headers = response.headers.toMultimap()
                        .filterKeys { key ->
                            key.lowercase() !in SENSITIVE_RESPONSE_HEADERS
                        }
                        .mapValues { (_, values) -> values.firstOrNull().orEmpty() }

                    if (!response.isSuccessful) {
                        return@withContext SourceFetchResponse.Failure(
                            SourceFailureClassifier.fromStatus(response.code, headers)
                        )
                    }

                    val bodyResult = readBoundedBody(response.body?.byteStream())
                    if (bodyResult.exceededLimit) {
                        return@withContext SourceFetchResponse.Failure(
                            SourceFailure.PayloadTooLarge(
                                bodyResult.bytesRead.toLong(),
                                maxBodyBytes.toLong()
                            )
                        )
                    }

                    SourceFetchResponse.Success(
                        statusCode = response.code,
                        body = bodyResult.body,
                        contentType = response.header("Content-Type"),
                        finalUrl = response.request.url.toString(),
                        fetchedAtEpochMillis = System.currentTimeMillis(),
                        headers = headers
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SourceFetchResponse.Failure(SourceFailureClassifier.fromThrowable(error))
            }
        }

    private fun readBoundedBody(input: java.io.InputStream?): BoundedBody {
        if (input == null) return BoundedBody("", bytesRead = 0, exceededLimit = false)
        input.use { source ->
            val output = ByteArrayOutputStream(minOf(maxBodyBytes, 64 * 1024))
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (total.toLong() <= maxBodyBytes.toLong()) {
                val remaining = (maxBodyBytes.toLong() + 1L - total).coerceAtMost(chunk.size.toLong()).toInt()
                val count = source.read(chunk, 0, remaining)
                if (count < 0) break
                output.write(chunk, 0, count)
                total += count
            }
            return BoundedBody(
                body = String(output.toByteArray(), StandardCharsets.UTF_8),
                bytesRead = total,
                exceededLimit = total.toLong() > maxBodyBytes.toLong()
            )
        }
    }

    private data class BoundedBody(
        val body: String,
        val bytesRead: Int,
        val exceededLimit: Boolean
    )

    companion object {
        private val SENSITIVE_RESPONSE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "www-authenticate",
            "proxy-authenticate",
            "location",
            "cookie",
            "set-cookie"
        )

        /** Identify the client honestly; rotate this if the app is published. */
        const val DEFAULT_USER_AGENT: String =
            "RealEstateAI/1.0 (+property-url-import; no-automated-bulk-crawling)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // retry/backoff is owned by the resolver
            .build()
    }
}
