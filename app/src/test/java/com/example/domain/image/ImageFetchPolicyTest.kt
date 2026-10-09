package com.example.domain.image

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

/**
 * Regression: image fetches must not follow redirects, use cookies, or resolve to private addresses.
 * This test needs OkHttp and is executed by the app's Gradle unit-test task (it was not run in the
 * offline review sandbox).
 */
class ImageFetchPolicyTest {

    private val client: OkHttpClient = ImageFetchPolicy.createClient()

    @Test
    fun `redirects are never followed`() {
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `no cookies, proxy or credentials are used`() {
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.interceptors.isNotEmpty())
    }

    @Test
    fun `non public image urls are refused before any request`() {
        listOf(
            "https://192.168.1.1/a.jpg",
            "http://169.254.169.254/latest/meta-data/",
            "https://localhost/a.jpg",
            "https://user:fakePassword@cdn.example.test/a.jpg"
        ).forEach { url -> assertFalse(url, ImageFetchPolicy.isAllowedImageUrl(url)) }
    }

    @Test
    fun `public image urls are allowed`() {
        assertTrue(ImageFetchPolicy.isAllowedImageUrl("https://photos.zillowstatic.com/fp/sample-front.jpg"))
    }
}
