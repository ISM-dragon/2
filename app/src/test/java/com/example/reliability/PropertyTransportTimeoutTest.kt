package com.example.reliability

import com.example.data.urlintelligence.OkHttpPropertyTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

/**
 * The transport contract of the app's real HTTP client.
 *
 * The import pipeline owns retries, backoff and redirect following, so the OkHttp client the app
 * builds must be bounded (a hung socket is what turns an import into an endless in-flight job) and
 * must not silently add its own retry layer on top. Nothing here talks to a network; it pins the
 * production client configuration, which no other suite asserted before this audit.
 */
class PropertyTransportTimeoutTest {

    @Test
    fun `the app transport bounds every timeout`() {
        val client = OkHttpPropertyTransport.defaultClient()

        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
        assertEquals(45_000, client.callTimeoutMillis)
        assertTrue(
            "the write timeout must be bounded too, or a stalled upload hangs the import call",
            client.writeTimeoutMillis > 0
        )
    }

    @Test
    fun `the app transport leaves retries and redirects to the import layer`() {
        val client = OkHttpPropertyTransport.defaultClient()

        assertFalse(
            "OkHttp must not retry: the layer's RetryPolicy is the only place that counts attempts",
            client.retryOnConnectionFailure
        )
        assertFalse("redirects are the resolver's job (SSRF guard runs on every hop)", client.followRedirects)
        assertFalse("an https->http redirect downgrade must never be followed silently", client.followSslRedirects)
        assertSame("no ambient proxy may be used for portal fetches", Proxy.NO_PROXY, client.proxy)
        assertSame("no cookie store may persist portal cookies", okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
    }
}
