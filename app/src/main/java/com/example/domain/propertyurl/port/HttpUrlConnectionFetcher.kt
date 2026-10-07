package com.example.domain.propertyurl.port

import com.example.domain.propertyurl.util.Redaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/**
 * Default [HttpFetcher] built on `HttpURLConnection`.
 *
 * Deliberately dependency free so the layer can be unit tested on a plain JVM and shipped without
 * growing the APK. Behaviour:
 *  - redirects are followed manually (max [FetchOptions.maxRedirects]) and each hop passes
 *    [FetchOptions.redirectGuard], which blocks SSRF-style redirects into loopback/private ranges
 *  - response bodies are size-capped and marked `truncated` instead of exhausting memory
 *  - gzip is negotiated and transparently decoded
 *  - no cookies are ever sent; credentials are attached by the adapter layer only
 */
class HttpUrlConnectionFetcher(private val clock: Clock = SystemClock()) : HttpFetcher {

    override suspend fun fetch(request: HttpRequest, options: FetchOptions): HttpFetchResult =
        withContext(Dispatchers.IO) {
            fetchBlocking(request, options, coroutineContext)
        }

    private suspend fun fetchBlocking(
        request: HttpRequest,
        options: FetchOptions,
        context: kotlin.coroutines.CoroutineContext
    ): HttpFetchResult {
        val started = clock.nowEpochMillis()
        var currentUrl = request.url
        val redirects = ArrayList<String>()
        var redirectCount = 0

        while (true) {
            context.ensureActive()

            if (!currentUrl.startsWith("http://", true) && !currentUrl.startsWith("https://", true)) {
                return HttpFetchResult.TransportError(
                    kind = TransportFailureKind.UNSUPPORTED_SCHEME,
                    message = "Unsupported scheme for ${Redaction.url(currentUrl)}",
                    requestedUrl = request.url
                )
            }

            val connection = try {
                URL(currentUrl).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return transportError(e, request.url, started)
            } catch (e: RuntimeException) {
                return transportError(e, request.url, started)
            }

            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = request.method
                connection.connectTimeout = options.connectTimeoutMillis
                connection.readTimeout = options.readTimeoutMillis
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", options.userAgent)
                connection.setRequestProperty("Accept", request.accept)
                connection.setRequestProperty("Accept-Language", options.acceptLanguage)
                if (options.allowCompression) connection.setRequestProperty("Accept-Encoding", "gzip")

                request.headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) connection.setRequestProperty(name, value)
                }

                val status = try {
                    connection.responseCode
                } catch (e: IOException) {
                    return transportError(e, request.url, started)
                }

                if (isRedirect(status)) {
                    val location = connection.headerFields
                        .entries.firstOrNull { it.key?.equals("Location", ignoreCase = true) == true }
                        ?.value?.firstOrNull()
                    if (location.isNullOrBlank()) {
                        return HttpFetchResult.Response(
                            requestedUrl = request.url,
                            finalUrl = currentUrl,
                            status = status,
                            headers = collectHeaders(connection),
                            body = null,
                            bodyBytes = 0,
                            contentType = connection.contentType,
                            elapsedMillis = clock.nowEpochMillis() - started,
                            redirects = redirects.toList()
                        )
                    }
                    val next = resolveLocation(currentUrl, location)
                    if (!options.redirectGuard(next)) {
                        return HttpFetchResult.TransportError(
                            kind = TransportFailureKind.BLOCKED_REDIRECT,
                            message = "Redirect to ${Redaction.url(next)} rejected by the redirect guard",
                            requestedUrl = request.url,
                            elapsedMillis = clock.nowEpochMillis() - started
                        )
                    }
                    redirectCount++
                    if (redirectCount > options.maxRedirects) {
                        return HttpFetchResult.TransportError(
                            kind = TransportFailureKind.TOO_MANY_REDIRECTS,
                            message = "More than ${options.maxRedirects} redirects",
                            requestedUrl = request.url,
                            elapsedMillis = clock.nowEpochMillis() - started
                        )
                    }
                    redirects.add(next)
                    currentUrl = next
                    continue
                }

                val headers = collectHeaders(connection)
                val isSuccess = status in 200..299
                val stream = try {
                    if (isSuccess) connection.inputStream else connection.errorStream
                } catch (e: IOException) {
                    null
                }

                val read = if (stream == null) {
                    ReadResult(body = null, bytes = 0, truncated = false)
                } else {
                    readBody(stream, headers["content-encoding"], options.maxBodyBytes)
                }

                return HttpFetchResult.Response(
                    requestedUrl = request.url,
                    finalUrl = currentUrl,
                    status = status,
                    headers = headers,
                    body = read.body,
                    bodyBytes = read.bytes,
                    contentType = headers["content-type"],
                    elapsedMillis = clock.nowEpochMillis() - started,
                    truncated = read.truncated,
                    redirects = redirects.toList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return transportError(e, request.url, started)
            } finally {
                try {
                    connection.disconnect()
                } catch (ignored: Exception) {
                    // Disconnecting is best effort.
                }
            }
        }
    }

    private class ReadResult(val body: String?, val bytes: Int, val truncated: Boolean)

    private fun readBody(stream: InputStream, contentEncoding: String?, maxBytes: Int): ReadResult {
        val gzip = contentEncoding?.lowercase(Locale.US)?.contains("gzip") == true
        val input = if (gzip) {
            try {
                GZIPInputStream(stream)
            } catch (e: IOException) {
                stream
            }
        } else {
            stream
        }

        val buffer = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        var truncated = false
        input.use { source ->
            while (true) {
                val read = source.read(chunk)
                if (read <= 0) break
                val remaining = maxBytes - total
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val toWrite = minOf(read, remaining)
                buffer.write(chunk, 0, toWrite)
                total += toWrite
                if (toWrite < read) {
                    truncated = true
                    break
                }
            }
        }
        return ReadResult(body = String(buffer.toByteArray(), Charsets.UTF_8), bytes = total, truncated = truncated)
    }

    private fun collectHeaders(connection: HttpURLConnection): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        connection.headerFields.forEach { (name, values) ->
            if (name == null || values.isEmpty()) return@forEach
            headers[name.lowercase(Locale.US)] = values.first()
        }
        return headers
    }

    private fun isRedirect(status: Int): Boolean =
        status == HttpURLConnection.HTTP_MOVED_PERM ||
            status == HttpURLConnection.HTTP_MOVED_TEMP ||
            status == HttpURLConnection.HTTP_SEE_OTHER ||
            status == 307 ||
            status == 308

    private fun resolveLocation(baseUrl: String, location: String): String {
        val trimmed = location.trim()
        return when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            trimmed.startsWith("//") -> {
                val scheme = if (baseUrl.startsWith("https", true)) "https:" else "http:"
                "$scheme$trimmed"
            }
            trimmed.startsWith("/") -> {
                val origin = origin(baseUrl)
                origin + trimmed
            }
            else -> {
                val withoutQuery = baseUrl.substringBefore('?').substringBefore('#')
                val directory = withoutQuery.substringBeforeLast('/', "")
                "$directory/$trimmed"
            }
        }
    }

    private fun origin(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val afterScheme = url.substring(schemeEnd + 3)
        val slash = afterScheme.indexOf('/')
        val authority = if (slash >= 0) afterScheme.substring(0, slash) else afterScheme
        return url.substring(0, schemeEnd + 3) + authority
    }

    private fun transportError(t: Throwable, requestedUrl: String, startedAt: Long): HttpFetchResult.TransportError {
        val kind = when (t) {
            is UnknownHostException -> TransportFailureKind.DNS_FAILURE
            is SocketTimeoutException -> TransportFailureKind.TIMEOUT
            is SSLException -> TransportFailureKind.TLS_FAILURE
            is java.net.ConnectException -> TransportFailureKind.NETWORK_UNREACHABLE
            is java.net.NoRouteToHostException -> TransportFailureKind.NETWORK_UNREACHABLE
            is java.net.SocketException -> TransportFailureKind.CONNECTION_RESET
            is IOException -> TransportFailureKind.NETWORK_UNREACHABLE
            else -> TransportFailureKind.INTERNAL
        }
        return HttpFetchResult.TransportError(
            kind = kind,
            message = Redaction.message(t.message ?: t.javaClass.simpleName),
            requestedUrl = requestedUrl,
            causeType = t.javaClass.simpleName,
            elapsedMillis = clock.nowEpochMillis() - startedAt
        )
    }
}
