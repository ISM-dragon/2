package com.example.data.scrape

import com.example.domain.intelligence.scrape.ListingPageFetch
import com.example.domain.intelligence.scrape.ListingPageFetcher
import com.example.domain.intelligence.scrape.PortalScrapeSettings
import com.example.domain.intelligence.scrape.Sleeper
import com.example.domain.propertyurl.port.PublicOnlyDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * OkHttp transport for portal listing pages.
 *
 * Same defensive posture as [com.example.domain.image.ImageFetchPolicy], because the URLs come from
 * third-party pages: HTTPS only, public DNS only (no private ranges, no cloud metadata), no cookies,
 * no proxy, no authenticator, and a hard response-size cap so a hostile page cannot exhaust memory.
 *
 * Unlike the image loader this client *does* follow redirects — portals redirect search URLs to
 * canonical paths — but never to plain HTTP, and every hop stays inside the public-DNS guard.
 */
class HttpListingPageFetcher(
    private val client: OkHttpClient,
    private val settingsProvider: () -> PortalScrapeSettings,
    /**
     * Production is HTTPS-only. Tests point at a local `MockWebServer`, which serves plain HTTP, so
     * the seam exists — but the default is the strict one and [create] never relaxes it.
     */
    private val requireHttps: Boolean = true
) : ListingPageFetcher {

    override suspend fun fetch(url: String): ListingPageFetch = withContext(Dispatchers.IO) {
        if (requireHttps && !url.startsWith("https://")) {
            return@withContext ListingPageFetch(
                requestedUrl = url,
                status = 0,
                transportError = "UNSUPPORTED_SCHEME"
            )
        }
        val startedAt = System.currentTimeMillis()
        try {
            client.newCall(buildRequest(url)).execute().use { response ->
                ListingPageFetch(
                    requestedUrl = url,
                    finalUrl = response.request.url.toString(),
                    status = response.code,
                    headers = response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(",") },
                    body = response.body?.let { readAtMost(it, settingsProvider().maxResponseBytes) },
                    elapsedMillis = System.currentTimeMillis() - startedAt
                )
            }
        } catch (e: IOException) {
            ListingPageFetch(
                requestedUrl = url,
                status = 0,
                elapsedMillis = System.currentTimeMillis() - startedAt,
                // Classified, not verbatim: the exception text can contain the full URL.
                transportError = classifyTransport(e)
            )
        }
    }

    /**
     * robots.txt for an origin.
     *
     * `404`/`410` means "the site publishes no rules", which is an empty ruleset — not an error.
     * Anything else returns null so [com.example.domain.intelligence.scrape.ListingScrapePolicy]
     * fails closed rather than assuming permission.
     */
    override suspend fun fetchRobotsTxt(origin: String): String? = withContext(Dispatchers.IO) {
        val url = "${origin.trimEnd('/')}/robots.txt"
        if (requireHttps && !url.startsWith("https://")) return@withContext null
        try {
            client.newCall(buildRequest(url)).execute().use { response ->
                when {
                    response.code == 404 || response.code == 410 -> ""
                    response.isSuccessful -> response.body?.let { readAtMost(it, ROBOTS_LIMIT_BYTES) }
                    else -> null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun buildRequest(url: String): Request {
        val settings = settingsProvider()
        return Request.Builder()
            .url(url)
            .header("User-Agent", settings.userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
    }

    /** Reads up to [limit] bytes and stops. A truncated body is still parseable for our purposes. */
    private fun readAtMost(body: ResponseBody, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        body.byteStream().use { input ->
            while (out.size() < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun classifyTransport(e: IOException): String = when (e) {
        is SocketTimeoutException -> "TIMEOUT"
        is UnknownHostException -> "DNS_FAILURE"
        is SSLException -> "TLS_FAILURE"
        else -> e.javaClass.simpleName.uppercase()
    }

    companion object {
        private const val ROBOTS_LIMIT_BYTES = 64_000

        fun create(settingsProvider: () -> PortalScrapeSettings): HttpListingPageFetcher {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .dns(PublicOnlyDns(Dns.SYSTEM))
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request()
                    if (request.url.scheme != "https") {
                        // Do not echo the URL back: it is third-party input and ends up in logs.
                        throw IOException("Portal request refused: HTTPS only")
                    }
                    chain.proceed(request)
                })
                .build()
            return HttpListingPageFetcher(client, settingsProvider)
        }
    }
}

/** Coroutine-backed [Sleeper] so page pacing does not block a thread. */
object CoroutineSleeper : Sleeper {
    override suspend fun sleep(millis: Long) = delay(millis)
}
