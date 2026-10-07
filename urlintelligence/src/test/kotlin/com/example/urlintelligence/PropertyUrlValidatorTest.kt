package com.example.urlintelligence

import com.example.urlintelligence.url.NormalizedUrl
import com.example.urlintelligence.url.PropertyUrlValidator
import com.example.urlintelligence.url.UrlValidationError
import com.example.urlintelligence.url.UrlValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyUrlValidatorTest {

    private val validator = PropertyUrlValidator()

    private fun valid(url: String): NormalizedUrl {
        val result = validator.validate(url)
        assertTrue("expected $url to be valid but was $result", result is UrlValidationResult.Valid)
        return (result as UrlValidationResult.Valid).url
    }

    private fun invalid(url: String): UrlValidationError {
        val result = validator.validate(url)
        assertTrue("expected $url to be invalid", result is UrlValidationResult.Invalid)
        return (result as UrlValidationResult.Invalid).error
    }

    @Test
    fun `adds missing scheme and normalizes host casing`() {
        val url = valid("WWW.Zillow.com/HomeDetails/12345678_zpid/")
        assertEquals("https", url.scheme)
        assertEquals("www.zillow.com", url.host)
        assertEquals("/HomeDetails/12345678_zpid", url.path)
    }

    @Test
    fun `removes trailing slash and default port`() {
        val url = valid("https://www.zillow.com:443/homedetails/12345678_zpid/")
        assertEquals("https://www.zillow.com/homedetails/12345678_zpid", url.canonical)
    }

    @Test
    fun `keeps non default port`() {
        val url = valid("https://www.zillow.com:8443/homedetails/12345678_zpid")
        assertTrue(url.canonical.contains(":8443"))
        assertEquals(8443, url.port)
    }

    @Test
    fun `drops tracking parameters and sorts the rest`() {
        val url = valid("https://www.zillow.com/homedetails/123_zpid/?utm_source=newsletter&z=1&a=2&fbclid=abc#section")
        assertEquals("https://www.zillow.com/homedetails/123_zpid?a=2&z=1", url.canonical)
        assertTrue(url.droppedParameters.containsAll(listOf("utm_source", "fbclid")))
    }

    @Test
    fun `credential-like query parameters are dropped from canonical URLs`() {
        val token = "unit-test-query-token"
        val url = valid(
            "https://www.zillow.com/homedetails/12345678_zpid?token=$token&access_token=another-unit-test-token" +
                "&api-key=unit-test-api-secret&email=unit.test%40example.invalid&listing_id=123"
        )
        assertFalse(url.canonical.contains(token))
        assertFalse(url.canonical.contains("access_token"))
        assertFalse(url.canonical.contains("unit-test-api-secret"))
        assertFalse(url.canonical.contains("unit.test"))
        assertEquals("123", url.query["listing_id"])
        assertTrue(url.droppedParameters.containsAll(listOf("token", "access_token", "api-key", "email")))
    }

    @Test
    fun `canonical form is stable for idempotency`() {
        val first = valid("https://www.Zillow.com/homedetails/12345678_zpid/?utm_source=x")
        val second = valid("zillow.com/homedetails/12345678_zpid")
        val third = valid("  https://www.zillow.com/homedetails/12345678_zpid#photos  ")
        assertEquals(first.canonical, second.canonical)
        assertEquals(second.canonical, third.canonical)
    }

    @Test
    fun `rejects embedded credentials`() {
        assertTrue(invalid("https://user:password@www.zillow.com/homedetails/1_zpid") is UrlValidationError.CredentialsInUrl)
    }

    @Test
    fun `rejects loopback private and metadata hosts`() {
        val blocked = listOf(
            "http://localhost/admin",
            "http://127.0.0.1/x",
            "http://10.0.4.9/x",
            "http://192.168.1.20/x",
            "http://172.16.5.4/x",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/x"
        )
        blocked.forEach { url ->
            val error = invalid(url)
            assertTrue("$url should be blocked as host", error is UrlValidationError.HostNotAllowed)
        }
    }

    @Test
    fun `rejects cleartext property URLs`() {
        assertTrue(invalid("http://www.zillow.com/homedetails/12345678_zpid") is UrlValidationError.UnsupportedScheme)
    }

    @Test
    fun `rejects unsupported schemes`() {
        assertTrue(invalid("javascript:alert(1)") is UrlValidationError.UnsupportedScheme)
        assertTrue(invalid("file:///etc/passwd") is UrlValidationError.UnsupportedScheme)
        assertTrue(invalid("ftp://zillow.com/x") is UrlValidationError.UnsupportedScheme)
    }

    @Test
    fun `rejects blank and oversized urls`() {
        assertTrue(invalid("   ") is UrlValidationError.Blank)
        val tooLong = "https://www.zillow.com/homedetails/" + "a".repeat(5000)
        assertTrue(invalid(tooLong) is UrlValidationError.TooLong)
    }

    @Test
    fun `rejects bare host without property path`() {
        assertTrue(invalid("https://www.zillow.com") is UrlValidationError.MissingPath)
        assertTrue(invalid("https://www.zillow.com/") is UrlValidationError.MissingPath)
    }

    @Test
    fun `collapses duplicate slashes in path`() {
        val url = valid("https://www.zillow.com//homedetails///12345678_zpid")
        assertEquals("/homedetails/12345678_zpid", url.path)
    }

    @Test
    fun `hostWithoutWww strips the www prefix`() {
        val url = valid("https://www.zillow.com/homedetails/1_zpid")
        assertEquals("zillow.com", url.hostWithoutWww)
    }
}
