package com.example.data.scrape

import com.example.domain.intelligence.scrape.PortalScrapeSettings
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transport behaviour of the OkHttp fetcher against a local server.
 *
 * What is pinned here is the part the parsers cannot see: the identity headers we send, the response
 * size cap, robots.txt status semantics (404 = "no rules published", 5xx = "unknown"), and that a
 * dead connection comes back as a classified transport error instead of an exception.
 */
class HttpListingPageFetcherTest {

    private lateinit var server: MockWebServer

    private val settings = PortalScrapeSettings(userConsented = true, maxResponseBytes = 1_000)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun fetcher() = HttpListingPageFetcher(OkHttpClient(), { settings }, requireHttps = false)

    @Test
    fun `a 200 response carries body, final url and identity headers`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "text/html; charset=utf-8")
                .setBody("<html>search results</html>")
        )
        val url = server.url("/homes/for_sale/austin-tx_rb/").toString()

        val page = fetcher().fetch(url)

        assertEquals(200, page.status)
        assertEquals("<html>search results</html>", page.body)
        assertEquals(url, page.finalUrl)
        assertNull(page.transportError)

        val recorded = server.takeRequest()
        assertEquals(PortalScrapeSettings.DEFAULT_USER_AGENT, recorded.getHeader("User-Agent"))
        assertTrue(recorded.getHeader("Accept")!!.contains("text/html"))
        assertNotNull(recorded.getHeader("Accept-Language"))
    }

    @Test
    fun `the response body is truncated at the configured cap`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(5_000)))

        val page = fetcher().fetch(server.url("/homes/for_sale/austin-tx_rb/").toString())

        assertEquals(1_000, page.body!!.length)
    }

    @Test
    fun `robots 404 means no rules published, 5xx means unknown, 200 returns the file`() = runTest {
        val origin = server.url("/").toString().trimEnd('/')

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals("", fetcher().fetchRobotsTxt(origin))

        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(fetcher().fetchRobotsTxt(origin))

        server.enqueue(MockResponse().setResponseCode(200).setBody("User-agent: *\nDisallow: /homes/for_sale/"))
        assertEquals("User-agent: *\nDisallow: /homes/for_sale/", fetcher().fetchRobotsTxt(origin))

        assertEquals("/robots.txt", server.takeRequest().path)
    }

    @Test
    fun `a non 2xx listing response is returned for classification, not thrown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html><div id=\"px-captcha\"></div></html>"))

        val page = fetcher().fetch(server.url("/homes/for_sale/austin-tx_rb/").toString())

        assertEquals(403, page.status)
        assertTrue(page.body!!.contains("px-captcha"))
        assertNull("an HTTP answer is not a transport failure", page.transportError)
    }

    @Test
    fun `a refused connection becomes a classified transport error`() = runTest {
        // Port 1 on loopback: nothing listens there, and the refusal is immediate.
        val page = fetcher().fetch("http://127.0.0.1:1/homes/for_sale/austin-tx_rb/")

        assertEquals(0, page.status)
        assertNotNull(page.transportError)
        assertNull(page.body)
    }

    @Test
    fun `plain http is refused when https is required`() = runTest {
        val strict = HttpListingPageFetcher(OkHttpClient(), { settings })

        val page = strict.fetch(server.url("/homes/for_sale/austin-tx_rb/").toString())

        assertEquals(0, page.status)
        assertEquals("UNSUPPORTED_SCHEME", page.transportError)
        assertEquals("no request may leave the device", 0, server.requestCount)
    }
}
