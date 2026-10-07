package com.example.domain.propertyurl.port

import com.example.domain.propertyurl.url.PropertyUrlValidator
import com.example.domain.propertyurl.url.UrlSensitiveParameters
import com.example.domain.propertyurl.url.UrlValidationOptions
import com.example.domain.propertyurl.url.UrlValidationResult
import com.example.domain.propertyurl.util.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Secure OkHttp implementation of the app-facing property transport.
 *
 * It accepts HTTPS public property URLs only, disables OkHttp's automatic redirects and cookies,
 * validates every manual redirect, and uses [PublicOnlyDns] so the checked DNS answers are exactly
 * those used by the socket connection. Credential-bearing request headers are retained only for
 * the original HTTPS origin; they are stripped before any cross-origin redirect.
 */
class OkHttpHttpFetcher(
    private val clock: Clock = SystemClock(),
    client: OkHttpClient = defaultClient()
) : HttpFetcher {

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

    override suspend fun fetch(request: HttpRequest, options: FetchOptions): HttpFetchResult =
        withContext(Dispatchers.IO) {
            fetchBlocking(request, options, coroutineContext)
        }

    private suspend fun fetchBlocking(
        request: HttpRequest,
        options: FetchOptions,
        context: kotlin.coroutines.CoroutineContext
    ): HttpFetchResult {
        val startedAt = clock.nowEpochMillis()
        val initialUrl = request.url.toHttpUrlOrNull()
            ?: return transportError(TransportFailureKind.UNSUPPORTED_SCHEME, request.url, startedAt)
        if (!isAllowedPublicUrl(initialUrl) || !options.redirectGuard(initialUrl.toString())) {
            return transportError(TransportFailureKind.UNSUPPORTED_SCHEME, request.url, startedAt)
        }

        val method = request.method.uppercase(Locale.US)
        if (method !in setOf("GET", "HEAD")) {
            return transportError(TransportFailureKind.UNSUPPORTED_SCHEME, request.url, startedAt)
        }

        val originalOrigin = initialUrl.originKey()
        var currentUrl = initialUrl
        val redirects = ArrayList<String>()
        val safeBaseClient = client.newBuilder()
            .connectTimeout(options.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(options.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(
                (options.connectTimeoutMillis.toLong() + options.readTimeoutMillis.toLong()).coerceAtLeast(1L),
                TimeUnit.MILLISECONDS
            )
            .build()

        while (true) {
            context.ensureActive()
            val credentialHeadersAllowed = currentUrl.originKey() == originalOrigin
            val headers = safeRequestHeaders(request.headers, credentialHeadersAllowed)
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", options.userAgent)
                .header("Accept", request.accept)
                .header("Accept-Language", options.acceptLanguage)
                .method(method, null)
            if (!options.allowCompression) requestBuilder.header("Accept-Encoding", "identity")
            headers.forEach { (name, value) ->
                if (options.allowCompression || !name.equals("Accept-Encoding", ignoreCase = true)) {
                    requestBuilder.header(name, value)
                }
            }

            try {
                val nextUrl = safeBaseClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (isRedirect(response.code)) {
                        val location = response.header("Location")
                        val next = location?.let { currentUrl.resolve(it) }
                        if (next == null || !isAllowedPublicUrl(next) || !options.redirectGuard(next.toString())) {
                            return transportError(
                                TransportFailureKind.BLOCKED_REDIRECT,
                                request.url,
                                startedAt
                            )
                        }
                        if (redirects.size >= options.maxRedirects) {
                            return transportError(
                                TransportFailureKind.TOO_MANY_REDIRECTS,
                                request.url,
                                startedAt
                            )
                        }
                        redirects += Redaction.url(next.toString()).orEmpty()
                        next
                    } else {
                        val body = readBody(response.body?.byteStream(), options.maxBodyBytes)
                        val responseHeaders = response.headers.names()
                            .filterNot { isSensitiveResponseHeader(it) }
                            .associate { name -> name.lowercase(Locale.US) to response.header(name).orEmpty() }

                        return HttpFetchResult.Response(
                            requestedUrl = Redaction.url(request.url).orEmpty(),
                            finalUrl = Redaction.url(currentUrl.toString()).orEmpty(),
                            status = response.code,
                            headers = responseHeaders,
                            body = body.text,
                            bodyBytes = body.bytes,
                            contentType = response.header("Content-Type"),
                            elapsedMillis = (clock.nowEpochMillis() - startedAt).coerceAtLeast(0L),
                            truncated = body.truncated,
                            redirects = redirects.toList()
                        )
                    }
                }
                currentUrl = nextUrl
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val kind = when (error) {
                    is UnknownHostException -> TransportFailureKind.DNS_FAILURE
                    is InterruptedIOException -> TransportFailureKind.TIMEOUT
                    is SSLException -> TransportFailureKind.TLS_FAILURE
                    is ConnectException -> TransportFailureKind.NETWORK_UNREACHABLE
                    is SocketException -> TransportFailureKind.CONNECTION_RESET
                    else -> TransportFailureKind.INTERNAL
                }
                return transportError(kind, request.url, startedAt)
            }
        }
        return transportError(TransportFailureKind.INTERNAL, request.url, startedAt)
    }

    private fun isAllowedPublicUrl(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.queryParameterNames.any(UrlSensitiveParameters::isSensitive)) return false
        return PropertyUrlValidator(
            UrlValidationOptions(allowInsecureHttp = false)
        ).validate(url.toString()) is UrlValidationResult.Valid
    }

    private fun safeRequestHeaders(
        supplied: Map<String, String>,
        sameOrigin: Boolean
    ): Map<String, String> {
        // Any custom header may carry a provider credential, so a cross-origin redirect gets only
        // the transport's ordinary User-Agent/Accept headers built separately above.
        if (!sameOrigin) return emptyMap()
        return supplied.filter { (name, value) ->
            val lowered = name.lowercase(Locale.US)
            lowered !in HOP_BY_HOP_HEADERS && value.isNotBlank()
        }
    }

    private fun readBody(input: java.io.InputStream?, maxBytes: Int): BodyRead {
        if (input == null) return BodyRead(text = null, bytes = 0, truncated = false)
        input.use { source ->
            val buffer = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val chunk = ByteArray(16 * 1024)
            var total = 0
            var truncated = false
            while (true) {
                val count = source.read(chunk)
                if (count <= 0) break
                val remaining = maxBytes - total
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val writeCount = minOf(count, remaining)
                buffer.write(chunk, 0, writeCount)
                total += writeCount
                if (writeCount < count) {
                    truncated = true
                    break
                }
            }
            return BodyRead(
                text = String(buffer.toByteArray(), Charsets.UTF_8),
                bytes = total,
                truncated = truncated
            )
        }
    }

    private fun transportError(
        kind: TransportFailureKind,
        requestedUrl: String,
        startedAt: Long,
        message: String = kind.safeMessage()
    ) = HttpFetchResult.TransportError(
        kind = kind,
        message = Redaction.message(message, maxLength = 200),
        requestedUrl = Redaction.url(requestedUrl).orEmpty(),
        elapsedMillis = (clock.nowEpochMillis() - startedAt).coerceAtLeast(0L)
    )

    private fun isRedirect(status: Int): Boolean = status in setOf(300, 301, 302, 303, 307, 308)

    private fun isSensitiveResponseHeader(name: String): Boolean {
        val lowered = name.lowercase(Locale.US)
        return Redaction.isSensitiveHeader(lowered) || lowered in setOf(
            "www-authenticate", "proxy-authenticate", "location"
        )
    }

    private fun HttpUrl.originKey(): String = "$scheme://$host:$port"

    private data class BodyRead(val text: String?, val bytes: Int, val truncated: Boolean)

    companion object {
        private val HOP_BY_HOP_HEADERS = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "accept-encoding"
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(23, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}


private fun TransportFailureKind.safeMessage(): String = when (this) {
    TransportFailureKind.DNS_FAILURE -> "Public destination name could not be resolved."
    TransportFailureKind.TIMEOUT -> "Property request timed out."
    TransportFailureKind.TLS_FAILURE -> "Secure connection could not be established."
    TransportFailureKind.NETWORK_UNREACHABLE -> "Property host could not be reached."
    TransportFailureKind.CONNECTION_RESET -> "Property connection was interrupted."
    TransportFailureKind.BLOCKED_REDIRECT -> "Redirect rejected by HTTPS public-host policy."
    TransportFailureKind.TOO_MANY_REDIRECTS -> "Redirect limit exceeded."
    TransportFailureKind.UNSUPPORTED_SCHEME -> "Only HTTPS public property URLs are supported."
    TransportFailureKind.CANCELLED -> "Property request was cancelled."
    TransportFailureKind.INTERNAL -> "Property request failed."
}
