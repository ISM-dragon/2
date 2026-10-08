package com.example

import com.example.data.urlintelligence.OkHttpPropertyTransport
import com.example.domain.propertyurl.port.HttpRequest
import com.example.domain.propertyurl.port.HttpFetchResult
import com.example.domain.propertyurl.port.OkHttpHttpFetcher
import com.example.urlintelligence.adapter.SourceFetchRequest
import com.example.urlintelligence.adapter.SourceFetchResponse
import com.example.urlintelligence.failure.SourceFailure
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class OkHttpPropertyTransportSecurityTest {
    @Test
    fun `property transport rejects cleartext before sending a request`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val transport = OkHttpPropertyTransport()
            val result = transport.execute(
                SourceFetchRequest(
                    requestUrl = server.url("/listing").toString(),
                    correlationId = "security-test"
                )
            )

            assertTrue(result is SourceFetchResponse.Failure)
            assertTrue((result as SourceFetchResponse.Failure).failure is SourceFailure.InvalidUrl)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `property transport rejects credentialed URLs without exposing credentials`() = runBlocking {
        val transport = OkHttpPropertyTransport()
        val result = transport.execute(
            SourceFetchRequest(
                requestUrl = "https://user:unit-test-secret@example.com/listing",
                correlationId = "security-test"
            )
        )

        assertTrue(result is SourceFetchResponse.Failure)
        val failure = (result as SourceFetchResponse.Failure).failure
        assertTrue(failure is SourceFailure.InvalidUrl)
        assertFalse(failure.detail.contains("unit-test-secret"))
    }

    @Test
    fun `property transport rejects credential-like query parameters without exposing values`() = runBlocking {
        val secret = "unit-test-query-secret"
        val result = OkHttpPropertyTransport().execute(
            SourceFetchRequest(
                requestUrl = "https://www.example.com/listing?access_token=$secret",
                correlationId = "security-test"
            )
        )

        assertTrue(result is SourceFetchResponse.Failure)
        val failure = (result as SourceFetchResponse.Failure).failure
        assertTrue(failure is SourceFailure.InvalidUrl)
        assertFalse(failure.detail.contains(secret))
    }

    @Test
    fun `credential free transport strips sensitive request headers`() = runBlocking {
        var observed: okhttp3.Request? = null
        val injected = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                observed = request
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("safe response".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val result = OkHttpPropertyTransport(client = injected).execute(
            SourceFetchRequest(
                requestUrl = "https://www.example.com/listing/123",
                correlationId = "security-test",
                headers = mapOf(
                    "X-Partner-Secret" to "unit-test-custom-secret",
                    "x-goog-api-key" to "unit-test-api-key",
                    "Accept-Language" to "en-US"
                )
            )
        )

        assertTrue(result is SourceFetchResponse.Success)
        assertEquals(null, observed?.header("X-Partner-Secret"))
        assertEquals(null, observed?.header("x-goog-api-key"))
        assertEquals("en-US", observed?.header("Accept-Language"))
    }

    @Test
    fun `transport disables automatic redirects even for an injected permissive client`() {
        val injected = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val transport = OkHttpPropertyTransport(client = injected)
        val field = OkHttpPropertyTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
        val hardened = field.get(transport) as OkHttpClient

        assertFalse(hardened.followRedirects)
        assertFalse(hardened.followSslRedirects)
    }

    @Test
    fun `legacy property fetcher rejects private DNS answers before connecting`() = runBlocking {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val injected = OkHttpClient.Builder()
            .dns(dnsOverride(listOf(loopback)))
            .build()

        val result = OkHttpHttpFetcher(client = injected).fetch(
            HttpRequest(url = "https://www.example.com/listing/123")
        )

        assertTrue(result is HttpFetchResult.TransportError)
        val error = result as HttpFetchResult.TransportError
        assertEquals(com.example.domain.propertyurl.port.TransportFailureKind.DNS_FAILURE, error.kind)
        assertFalse(error.message.contains("127.0.0.1"))
        assertFalse(error.message.contains("www.example.com"))
    }

    @Test
    fun `cross origin redirects do not forward credentials or custom headers`() = runBlocking {
        val observed = mutableListOf<okhttp3.Request>()
        val injected = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                observed += request
                val isFirstHop = request.url.host == "www.example.com"
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (isFirstHop) 302 else 200)
                    .message(if (isFirstHop) "Found" else "OK")
                    .apply {
                        if (isFirstHop) header("Location", "https://cdn.example.net/listing/123")
                        body("safe response".toResponseBody("text/plain".toMediaType()))
                    }
                    .build()
            }
            .build()

        val result = OkHttpHttpFetcher(client = injected).fetch(
            HttpRequest(
                url = "https://www.example.com/listing/123",
                headers = mapOf(
                    "Authorization" to "Bearer unit-test-secret",
                    "X-Partner-Secret" to "unit-test-custom-secret"
                )
            )
        )

        assertTrue(result is HttpFetchResult.Response)
        assertEquals(2, observed.size)
        assertEquals("Bearer unit-test-secret", observed[0].header("Authorization"))
        assertEquals("unit-test-custom-secret", observed[0].header("X-Partner-Secret"))
        assertEquals(null, observed[1].header("Authorization"))
        assertEquals(null, observed[1].header("X-Partner-Secret"))
    }

    @Test
    fun `DNS policy rejects private and non-routable IPv4 and IPv6 answers`() = runBlocking {
        val blockedAddresses = listOf(
            InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)),
            InetAddress.getByAddress(byteArrayOf(100, 64, 0, 1)),
            InetAddress.getByAddress(
                byteArrayOf(0xfc.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            ),
            InetAddress.getByAddress(
                byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            ),
            InetAddress.getByAddress(
                byteArrayOf(0x20, 0x01, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            )
        )

        blockedAddresses.forEach { address ->
            val injected = OkHttpClient.Builder().dns(dnsOverride(listOf(address))).build()
            val result = OkHttpHttpFetcher(client = injected).fetch(
                HttpRequest(url = "https://www.example.com/listing/123")
            )
            assertTrue(result is HttpFetchResult.TransportError)
            assertEquals(
                com.example.domain.propertyurl.port.TransportFailureKind.DNS_FAILURE,
                (result as HttpFetchResult.TransportError).kind
            )
        }
    }

    @Test
    fun `DNS policy rejects mixed public and private address answers`() = runBlocking {
        val publicAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val privateAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val injected = OkHttpClient.Builder()
            .dns(dnsOverride(listOf(publicAddress, privateAddress)))
            .build()

        val result = OkHttpHttpFetcher(client = injected).fetch(
            HttpRequest(url = "https://www.example.com/listing/123")
        )

        assertTrue(result is HttpFetchResult.TransportError)
        assertEquals(
            com.example.domain.propertyurl.port.TransportFailureKind.DNS_FAILURE,
            (result as HttpFetchResult.TransportError).kind
        )
    }

    @Test
    fun `default redirect policy rejects downgrade credentials and private targets`() {
        val guard = com.example.domain.propertyurl.port.FetchOptions.DEFAULT_REDIRECT_GUARD

        assertTrue(guard("https://www.example.com/listing/123"))
        assertFalse(guard("http://www.example.com/listing/123"))
        assertFalse(guard("https://user:unit-test-secret@example.com/listing/123"))
        assertFalse(guard("https://127.0.0.1/admin"))
        assertFalse(guard("https://service.internal/listing/123"))
    }

    /**
     * okhttp 4 declares [Dns] as a Kotlin interface, so a lambda/SAM conversion is not available
     * and the DNS answers under test must be injected through an explicit implementation.
     */
    private fun dnsOverride(addresses: List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses
    }
}
